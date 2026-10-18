// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Veille-lot : les cases d'un tableau de veille telles que Home Assistant les envoie (docs/veille-contrat.md §1, §4).
 *
 * - Les entités d'action et de saisie (boutons, scripts, scènes, champs…) ne sont jamais montrées, même si un Home
 *   Assistant plus ancien les envoie encore.
 * - Les unités viennent dans la langue de Home Assistant (« j ») ; un Home Assistant plus ancien envoie « d » : on
 *   convertit ici, selon la langue de l'appareil.
 * - Les cases typées (`genre`) : météo, agenda, image (picture-elements), radar de pluie, caméra, iframe et « Derniers
 *   passages » (Frigate). Tout champ manquant retombe sur le texte résumé (`texte`) que toute case garde.
 */
object CasesVeille {

    /** Domaines jamais montrés dans la veille (contrat §4.4) ; `input_boolean` se lit comme un interrupteur et reste. */
    val DOMAINES_EXCLUS = setOf(
        "button", "input_button", "script", "scene", "automation", "input_number", "input_text", "input_select",
        "input_datetime", "number", "select", "text", "notify", "tts", "conversation",
    )

    /** Vrai si l'entité est une action ou une saisie : la case n'a rien à faire dans une veille. */
    fun exclue(entite: String): Boolean {
        val domaine = entite.substringBefore('.', "").lowercase(Locale.ROOT)
        if (domaine.isEmpty()) return false
        return domaine in DOMAINES_EXCLUS || (domaine.startsWith("input_") && domaine != "input_boolean")
    }

    /** Les unités de durée (code anglais de Home Assistant) dans la langue de l'appareil ; inchangées ailleurs. */
    private val UNITES_FR = mapOf("d" to "j", "w" to "sem.", "mo" to "mois", "y" to "an")

    /**
     * L'unité à afficher. Ce que Home Assistant envoie est gardé tel quel, sauf les codes anglais d'un Home Assistant
     * ancien (« d », « L/d », « kWh/d »…) quand l'appareil parle français. Idempotent (« j » reste « j »).
     */
    fun unite(u: String, langue: String = Locale.getDefault().language): String {
        if (u.isEmpty() || !langue.equals("fr", ignoreCase = true)) return u
        UNITES_FR[u]?.let { return it }
        val barre = u.lastIndexOf('/')
        if (barre in 0 until u.length - 1) {
            val fin = u.substring(barre + 1)
            UNITES_FR[fin]?.let { return u.substring(0, barre + 1) + it }
        }
        return u
    }

    // ------------------------------------------------------------------ cases typées

    class Prevision(val quand: String, val etat: String, val max: Double?, val min: Double?, val pluie: Double?) {
        val quandMs: Long? = instant(quand)
    }
    class Evenement(val debut: String, val fin: String, val titre: String, val journee: Boolean, val calendrier: String) {
        /** Lu une fois, à la réception : jamais d'analyse de date pendant le dessin. */
        val debutMs: Long? = instant(debut)
    }
    class Element(val x: Float, val y: Float, val texte: String)
    class Calque(val x: Float, val y: Float, val largeur: Float, val url: String, val entite: String)
    class Clip(val camera: String, val entite: String, val quand: String, val objet: String, val objetNom: String, val vignette: String) {
        val quandMs: Long? = instant(quand)
    }

    /** Une case typée (le reste de la case — nom, texte, taille — est porté par la case elle-même). */
    sealed class Typee {
        class Meteo(val etat: String, val etatTexte: String, val temperature: Double?, val unite: String, val previsions: List<Prevision>) : Typee()
        class Agenda(val evenements: List<Evenement>) : Typee()
        class Image(val url: String, val elements: List<Element>, val calques: List<Calque>) : Typee()
        class Radar(val url: String, val calque: Calque?) : Typee()
        class Camera(val entite: String, val direct: String, val url: String) : Typee()
        class Iframe(val url: String) : Typee()
        class Clips(val liste: List<Clip>) : Typee()

        /** Les adresses d'images que la case montre (à précharger avant que sa page n'arrive). */
        fun images(): List<String> = when (this) {
            is Image -> listOf(url) + calques.map { it.url }
            is Radar -> listOfNotNull(url, calque?.url)
            is Camera -> listOf(url)
            is Clips -> liste.map { it.vignette }
            else -> emptyList()
        }.filter { it.isNotEmpty() }
    }

    private fun JSONObject.dbl(cle: String): Double? = if (has(cle) && !isNull(cle)) optDouble(cle).takeIf { !it.isNaN() } else null
    private inline fun <T> liste(a: JSONArray?, max: Int, f: (JSONObject) -> T?): List<T> =
        (0 until minOf(a?.length() ?: 0, max)).mapNotNull { i -> a!!.optJSONObject(i)?.let(f) }

    private fun calque(o: JSONObject?): Calque? = o?.let {
        val url = it.optString("url"); if (url.isEmpty()) null
        else Calque(it.optDouble("x", 50.0).toFloat(), it.optDouble("y", 50.0).toFloat(), it.optDouble("largeur", 100.0).toFloat(), url, it.optString("entite"))
    }

    /** Lit le `genre` d'une case ; null pour une simple valeur (ou un genre inconnu : la case garde son texte). */
    fun lire(c: JSONObject): Typee? = try {
        when (c.optString("genre")) {
            "meteo" -> Typee.Meteo(c.optString("etat"), c.optString("etat_texte"), c.dbl("temperature"), c.optString("unite"),
                liste(c.optJSONArray("previsions"), 5) { p -> Prevision(p.optString("quand"), p.optString("etat"), p.dbl("max"), p.dbl("min"), p.dbl("pluie")) })
            "agenda" -> Typee.Agenda(liste(c.optJSONArray("evenements"), 8) { e ->
                Evenement(e.optString("debut"), e.optString("fin"), e.optString("titre"), e.optBoolean("journee"), e.optString("calendrier")) })
            "image" -> c.optString("url").takeIf { it.isNotEmpty() }?.let { url ->
                Typee.Image(url, liste(c.optJSONArray("elements"), 12) { e -> Element(e.optDouble("x", 50.0).toFloat(), e.optDouble("y", 50.0).toFloat(), e.optString("texte")) },
                    liste(c.optJSONArray("calques"), 4) { calque(it) }) }
            "radar" -> c.optString("url").takeIf { it.isNotEmpty() }?.let { Typee.Radar(it, calque(c.optJSONObject("calque"))) }
            "camera" -> c.optString("entite").takeIf { it.isNotEmpty() }?.let { Typee.Camera(it, c.optString("direct"), c.optString("url")) }
            "iframe" -> c.optString("url").takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let { Typee.Iframe(it) }
            "clips" -> Typee.Clips(liste(c.optJSONArray("liste"), 8) { k ->
                Clip(k.optString("camera"), k.optString("entite"), k.optString("quand"), k.optString("objet"), k.optString("objet_nom"), k.optString("vignette")) })
            else -> null
        }
    } catch (_: Exception) { null }

    // ------------------------------------------------------------------ dates du contrat (ISO 8601)

    /** « 2026-10-11T05:24:39+02:00 » (ou « 2026-10-12 », journée entière) → instant en ms ; null si illisible. */
    fun instant(iso: String): Long? {
        if (iso.isEmpty()) return null
        return try {
            if (iso.length == 10) java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(iso)?.time
            else {
                // « +02:00 » → « +0200 » (SimpleDateFormat « Z », lisible dès Android 5) ; « Z » → « +0000 ».
                var s = iso.replace("Z", "+0000")
                val m = Regex("([+-]\\d\\d):(\\d\\d)$").find(s)
                if (m != null) s = s.substring(0, m.range.first) + m.groupValues[1] + m.groupValues[2]
                s = s.replace(Regex("\\.\\d+"), "")
                java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ROOT).parse(s)?.time
            }
        } catch (_: Exception) { null }
    }

    /** L'écart depuis `ts`, en unité lisible : « instant », « min », « h », « hier » ou « j », et le nombre (traduit à l'affichage). */
    fun ecart(ts: Long, maintenant: Long): Pair<String, Long> {
        val min = ((maintenant - ts) / 60_000L).coerceAtLeast(0)
        return when {
            min < 1 -> "instant" to 0L
            min < 60 -> "min" to min
            min < 24 * 60 -> "h" to min / 60
            min < 48 * 60 -> "hier" to 1L
            else -> "j" to min / (24 * 60)
        }
    }
}
