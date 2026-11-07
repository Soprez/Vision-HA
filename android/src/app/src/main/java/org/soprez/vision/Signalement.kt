// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * « Signaler un problème » (1.94) : un responsable refait ses gestes pendant que Vision garde le journal et quelques
 * captures ; à la fin, un aperçu anonymisé, puis un envoi explicite vers soprez.org. Rien ne sort sans « Envoyer ».
 *
 * Ce fichier : l'état de l'enregistrement, la fiche matériel, l'assemblage du paquet (zip) et l'envoi. Les écrans sont
 * dans [SignalerActivity], la pastille dans [PastilleSignalement], les captures dans [CaptureEcran].
 * Jamais dans le paquet : identifiant, secret, ANDROID_ID, adresse MAC, SSID, jetons (essai `SignalementTest`).
 */
object Signalement {
    const val URL_SITE = "https://soprez.org/api/signalement/"
    /** Clé de dépôt : publique, propre à la version de l'appli ; elle écarte les robots, ce n'est pas un secret. */
    const val CLE_DEPOT = "vision-android-1.94"
    const val DUREE_MAX_MS = 10 * 60_000L
    const val MAX_CAPTURES = 5
    const val MAX_PAQUET = 8 * 1024 * 1024
    const val MAX_DESCRIPTION = 500
    private const val AVANT_MS = 2 * 60_000L
    private const val PREFS = "signalements"

    @Volatile var debut = 0L
        private set
    @Volatile var enCours = false
        private set
    private val captures = ArrayList<File>()

    /** Le contenu d'un signalement prêt à l'aperçu (déjà anonymisé). */
    class Brouillon(
        val debut: Long, val fin: Long, val journalAppli: String, val journalHa: String,
        val fiche: JSONObject, val remplacements: Int, val captures: List<ByteArray>,
        var description: String = "",
    )

    fun commencer(maintenant: Long = System.currentTimeMillis()) {
        synchronized(captures) { captures.clear() }
        debut = maintenant
        enCours = true
    }

    fun terminer() { enCours = false }

    fun ajouterCapture(f: File): Boolean = synchronized(captures) {
        if (captures.size >= MAX_CAPTURES) false else { captures += f; true }
    }

    fun fichiersCaptures(): List<File> = synchronized(captures) { captures.toList() }

    fun oublier() {
        synchronized(captures) { captures.forEach { it.delete() }; captures.clear() }
        enCours = false; debut = 0L
    }

    /** Fin automatique : 10 minutes après le début. */
    fun expire(maintenant: Long = System.currentTimeMillis()): Boolean = enCours && maintenant - debut >= DUREE_MAX_MS

    // ------------------------------------------------------------------ la fiche matériel

    /** Sans aucun identifiant : ni id, ni secret, ni ANDROID_ID, ni MAC, ni SSID (seulement le TYPE de réseau). */
    fun fiche(ctx: Context): JSONObject {
        val dm = ctx.resources.displayMetrics
        val mi = android.app.ActivityManager.MemoryInfo()
        try { (ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(mi) } catch (_: Exception) {}
        return JSONObject()
            .put("type", Identite.type(ctx))
            .put("modele", Identite.appareil())
            .put("android", Build.VERSION.SDK_INT)
            .put("ecran", "${dm.widthPixels}x${dm.heightPixels}@${dm.densityDpi}dpi")
            .put("memoire_mo", mi.totalMem / (1024 * 1024))
            .put("reseau", typeReseau(ctx))
            .put("langue", Locale.getDefault().toLanguageTag())
            .put("fuseau", TimeZone.getDefault().id)
            .put("appli", BuildConfigCompat.version(ctx))
    }

    private fun typeReseau(ctx: Context): String = try {
        @Suppress("DEPRECATION")
        when ((ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).activeNetworkInfo?.type) {
            ConnectivityManager.TYPE_ETHERNET -> "ethernet"
            ConnectivityManager.TYPE_WIFI -> "wifi"
            ConnectivityManager.TYPE_MOBILE -> "mobile"
            null -> "aucun"
            else -> "autre"
        }
    } catch (_: Exception) { "inconnu" }

    // ------------------------------------------------------------------ le journal de l'appli, anonymisé

    fun horodatage(ts: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT).format(Date(ts))

    /** Les lignes de la période (2 minutes avant le début comprises), une par ligne, anonymisées. */
    fun journalAppli(debut: Long, fin: Long, anon: Anonymisation, lignes: List<Journal.Ligne> = Journal.periode(debut - AVANT_MS, fin)): String =
        lignes.joinToString("\n") { "${horodatage(it.ts)} ${it.niveau} ${it.tag} ${anon.ligne(it.texte)}" }

    // ------------------------------------------------------------------ le paquet

    fun manifeste(b: Brouillon, parties: List<String>): JSONObject = JSONObject()
        .put("version", 1)
        .put("reference", JSONObject.NULL)
        .put("cree", horodatage(System.currentTimeMillis()))
        .put("appareil", b.fiche)
        .put("periode", JSONObject().put("debut", horodatage(b.debut)).put("fin", horodatage(b.fin)))
        .put("parties", JSONArray(parties))
        .put("anonymisation", JSONObject().put("regles", "v1").put("remplacements", b.remplacements))
        .put("description", b.description.take(MAX_DESCRIPTION))

    /**
     * Écrit le zip dans [sortie] et rend sa taille. Au-delà de [max] : les captures partent d'abord (la dernière
     * en premier), puis le début des journaux est tronqué. Le zip est écrit sur disque (flux), jamais tout en mémoire.
     */
    fun assembler(b: Brouillon, sortie: File, max: Int = MAX_PAQUET): Int {
        var caps = b.captures.take(MAX_CAPTURES)
        var appli = b.journalAppli
        var ha = b.journalHa
        while (true) {
            ecrire(b, sortie, caps, appli, ha)
            val taille = sortie.length().toInt()
            if (taille <= max) return taille
            when {
                caps.isNotEmpty() -> caps = caps.dropLast(1)
                appli.length > 1024 || ha.length > 1024 -> { appli = appli.takeLast(appli.length * 3 / 4); ha = ha.takeLast(ha.length * 3 / 4) }
                else -> return taille
            }
        }
    }

    private fun ecrire(b: Brouillon, sortie: File, caps: List<ByteArray>, appli: String, ha: String) {
        val parties = ArrayList<String>()
        parties += "journal_appli.txt"
        if (ha.isNotEmpty()) parties += "journal_ha.txt"
        caps.indices.forEach { parties += "captures/%02d.jpg".format(it + 1) }
        ZipOutputStream(sortie.outputStream().buffered()).use { z ->
            fun entree(nom: String, octets: ByteArray) { z.putNextEntry(ZipEntry(nom)); z.write(octets); z.closeEntry() }
            entree("manifeste.json", manifeste(b, parties).toString(2).toByteArray(Charsets.UTF_8))
            entree("journal_appli.txt", appli.toByteArray(Charsets.UTF_8))
            if (ha.isNotEmpty()) entree("journal_ha.txt", ha.toByteArray(Charsets.UTF_8))
            caps.forEachIndexed { i, c -> entree("captures/%02d.jpg".format(i + 1), c) }
        }
    }

    // ------------------------------------------------------------------ l'envoi et la référence

    class Recu(val reference: String, val effacement: String, val conservationJours: Int)

    /** Envoie le paquet ; lève une exception en cas d'échec réseau ou de refus (l'appelant le dit à l'écran). */
    fun envoyer(ctx: Context, b: Brouillon, paquet: File, url: String = URL_SITE): Recu {
        val m = manifeste(b, emptyList())
        val r = Net.postMultipart(ctx, url, mapOf("manifeste" to m.toString(), "cle" to CLE_DEPOT), "paquet", "signalement.zip", paquet)
        val recu = Recu(r.optString("reference"), r.optString("effacement"), r.optInt("conservation_jours", 90))
        if (recu.reference.isEmpty()) throw java.io.IOException("réponse sans référence")
        garder(ctx, recu)
        return recu
    }

    fun effacer(ctx: Context, reference: String): Boolean {
        val liste = references(ctx)
        val lien = liste.firstOrNull { it.first == reference }?.second ?: return false
        return try { Net.postVide(ctx, lien); retirerReference(ctx, reference); true } catch (_: Exception) { false }
    }

    /** Les signalements envoyés depuis cet appareil : (référence, lien d'effacement signé). */
    fun references(ctx: Context): List<Pair<String, String>> = try {
        val a = JSONArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("liste", "[]"))
        List(a.length()) { a.getJSONObject(it).let { o -> o.getString("ref") to o.getString("lien") } }
    } catch (_: Exception) { emptyList() }

    private fun garder(ctx: Context, r: Recu) {
        val a = JSONArray()
        (references(ctx) + (r.reference to r.effacement)).takeLast(20).forEach { a.put(JSONObject().put("ref", it.first).put("lien", it.second)) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("liste", a.toString()).apply()
    }

    private fun retirerReference(ctx: Context, reference: String) {
        val a = JSONArray()
        references(ctx).filter { it.first != reference }.forEach { a.put(JSONObject().put("ref", it.first).put("lien", it.second)) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("liste", a.toString()).apply()
    }
}
