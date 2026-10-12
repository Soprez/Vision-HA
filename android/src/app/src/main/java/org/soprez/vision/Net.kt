// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Appels vers Home Assistant, avec bascule interne / externe.
 *
 * Les vieux Android (avant 7.1.1, donc Fire OS 5 et 6) ne connaissent pas la
 * racine Let's Encrypt actuelle : on l'embarque et on l'ajoute à celles du
 * système, sans jamais désactiver la vérification.
 */
object Net {
    class Echec(val code: Int, message: String, val corps: JSONObject? = null) : IOException(message)

    @Volatile private var fabrique: SSLSocketFactory? = null

    fun post(ctx: Context, cfg: Config, chemin: String, corps: JSONObject, delaiMs: Int = 8000): JSONObject {
        var derniere: Exception? = null
        for (base in cfg.adresses()) {
            try {
                val r = postUne(ctx, base + chemin, corps, delaiMs)
                if (cfg.urlActive != base) cfg.urlActive = base
                return r
            } catch (e: Echec) {
                // Le serveur a répondu : inutile d'essayer l'autre adresse.
                if (e.code in 400..499) throw e
                derniere = e
            } catch (e: Exception) {
                derniere = e
            }
        }
        throw derniere ?: IOException(ctx.getString(R.string.net_aucune_adresse))
    }

    fun postUne(ctx: Context, url: String, corps: JSONObject, delaiMs: Int = 8000): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        if (c is HttpsURLConnection) c.sslSocketFactory = fabrique(ctx)
        c.connectTimeout = delaiMs
        c.readTimeout = delaiMs
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        c.setRequestProperty("User-Agent", "Vision-Android/" + BuildConfigCompat.version(ctx))
        try {
            c.outputStream.use { it.write(corps.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val flux = if (code in 200..299) c.inputStream else c.errorStream
            val texte = flux?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val json = try { JSONObject(texte) } catch (_: Exception) { JSONObject() }
            if (code !in 200..299) throw Echec(code, json.optString("error", "HTTP $code"), json)
            return json
        } finally {
            c.disconnect()
        }
    }

    /** Un POST qui renvoie des octets (une image de caméra). Null en cas d'échec, sans lever. */
    fun postBytes(ctx: Context, cfg: Config, chemin: String, corps: JSONObject, delaiMs: Int = 8000): ByteArray? {
        val base = cfg.urlActive.ifEmpty { cfg.adresses().firstOrNull() ?: return null }
        val c = URL(base + chemin).openConnection() as HttpURLConnection
        if (c is HttpsURLConnection) c.sslSocketFactory = fabrique(ctx)
        c.connectTimeout = delaiMs
        c.readTimeout = delaiMs
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        c.setRequestProperty("User-Agent", "Vision-Android/" + BuildConfigCompat.version(ctx))
        return try {
            c.outputStream.use { it.write(corps.toString().toByteArray(Charsets.UTF_8)) }
            if (c.responseCode !in 200..299) null else c.inputStream.use { it.readBytes() }
        } catch (_: Exception) { null } finally { c.disconnect() }
    }

    /** Télécharge un fichier (l'APK d'une mise à jour). Null en cas d'échec. */
    @Volatile var derniereErreur: String = ""

    fun getBytes(ctx: Context, url: String, delaiMs: Int = 60000): ByteArray? {
        derniereErreur = ""
        val c = try { URL(url).openConnection() as HttpURLConnection } catch (e: Exception) { derniereErreur = e.message ?: ctx.getString(R.string.net_adresse_invalide); return null }
        if (c is HttpsURLConnection) c.sslSocketFactory = fabrique(ctx)
        c.connectTimeout = 15000
        c.readTimeout = delaiMs
        c.requestMethod = "GET"
        c.setRequestProperty("User-Agent", "Vision-Android/" + BuildConfigCompat.version(ctx))
        return try {
            val code = c.responseCode
            if (code !in 200..299) { derniereErreur = "HTTP $code"; null }
            else c.inputStream.use { it.readBytes() }
        } catch (e: Exception) { derniereErreur = e.javaClass.simpleName + (e.message?.let { " $it" } ?: ""); null } finally { c.disconnect() }
    }

    /** La fabrique TLS de l'application (racines du système + Let's Encrypt embarqué), pour les autres clients HTTP. */
    fun fabriqueSsl(ctx: Context): SSLSocketFactory = fabrique(ctx)

    @Synchronized
    private fun fabrique(ctx: Context): SSLSocketFactory {
        fabrique?.let { return it }
        val systeme = trustManager(null)
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        val cf = CertificateFactory.getInstance("X.509")
        listOf(R.raw.isrg_root_x1, R.raw.isrg_root_x2).forEachIndexed { i, res ->
            ctx.resources.openRawResource(res).use { ks.setCertificateEntry("le$i", cf.generateCertificate(it)) }
        }
        val embarque = trustManager(ks)
        val combine = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
                systeme.checkClientTrusted(chain, authType)
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                try { systeme.checkServerTrusted(chain, authType) }
                catch (e: Exception) { embarque.checkServerTrusted(chain, authType) }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> =
                systeme.acceptedIssuers + embarque.acceptedIssuers
        }
        val proto = if (Build.VERSION.SDK_INT >= 29) "TLS" else "TLSv1.2"
        val ssl = SSLContext.getInstance(proto).apply { init(null, arrayOf(combine), null) }
        return ssl.socketFactory.also { fabrique = it }
    }

    private fun trustManager(ks: KeyStore?): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(ks)
        return tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager
    }
}

object BuildConfigCompat {
    fun version(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }
}
