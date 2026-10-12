// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * 1.86.7 (l'auteur : « play / pause en retard ») : Home Assistant prévient dès que l'enceinte de la soirée change (lecture,
 * pause, titre) ; l'écran Musique et le mini-lecteur relisent alors tout de suite, au lieu d'attendre la relecture
 * suivante. Un seul fil pour toute l'appli ; il vit tant que quelqu'un écoute, et se reconnecte seul (3 s).
 */
object FluxMusique {
    private val main = Handler(Looper.getMainLooper())
    private val ecoutes = LinkedHashMap<String, () -> Unit>()
    @Volatile private var fil: Thread? = null
    @Volatile private var connexion: HttpURLConnection? = null

    @Synchronized
    fun ecouter(ctx: Context, cle: String, surChange: () -> Unit) {
        ecoutes[cle] = surChange
        if (fil?.isAlive == true) return
        val app = ctx.applicationContext
        fil = Thread { boucle(app) }.also { it.isDaemon = true; it.start() }
    }

    @Synchronized
    fun retirer(cle: String) {
        ecoutes.remove(cle)
        if (ecoutes.isEmpty()) {
            fil?.interrupt(); fil = null
            try { connexion?.disconnect() } catch (_: Exception) {}
            connexion = null
        }
    }

    private fun prevenir() {
        val l = synchronized(this) { ecoutes.values.toList() }
        main.post { l.forEach { it() } }
    }

    private fun boucle(ctx: Context) {
        val moi = Thread.currentThread()
        while (!moi.isInterrupted && fil === moi) {
            try { ecouter(ctx) } catch (_: InterruptedException) { return } catch (_: Exception) {}
            if (moi.isInterrupted || fil !== moi) return
            try { Thread.sleep(3_000) } catch (_: InterruptedException) { return }
        }
    }

    private fun ecouter(ctx: Context) {
        val cfg = Config(ctx)
        val base = cfg.urlActive.ifEmpty { cfg.adresses().firstOrNull() ?: return }
        val c = URL("$base/api/pc_parental/party/flux").openConnection() as HttpURLConnection
        if (c is HttpsURLConnection) c.sslSocketFactory = Net.fabriqueSsl(ctx)
        connexion = c
        c.connectTimeout = 10_000; c.readTimeout = 60_000   // battement toutes les 20 s
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        c.setRequestProperty("Accept", "text/event-stream")
        try {
            c.outputStream.use { it.write(JSONObject().put("id", cfg.id).put("secret", cfg.secret).toString().toByteArray(Charsets.UTF_8)) }
            if (c.responseCode !in 200..299) return
            c.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                while (!Thread.currentThread().isInterrupted) {
                    val l = r.readLine() ?: break
                    if (l.startsWith("event: fin")) return
                    if (l.startsWith("data:")) prevenir()
                }
            }
        } finally { try { c.disconnect() } catch (_: Exception) {} }
    }
}
