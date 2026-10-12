// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Préavis avant coupure (1.69.0, point 14.1) : Home Assistant dit au relevé quand l'appareil va se fermer
 * (`preavis` : fin, motif, seuils) et quelles applis approchent de leur limite du jour
 * (`preavis_apps` : nom, libelle, reste_s, seuils). L'appareil annonce chaque seuil une fois, par une notification.
 *
 * Lot Accueil : les seuils suivent le réglage « Avertir avant fermeture » de Home Assistant (X minutes, puis 5 et 1
 * s'ils sont plus petits ; X = 0 : rien n'est envoyé). Un Home Assistant d'avant n'envoie pas de seuils pour les
 * applis : on garde alors 10 / 5 / 1.
 */
object Preavis {
    private val dits = LinkedHashSet<String>()

    /** Les seuils d'un Home Assistant qui n'en envoie pas (avant le délai réglable). */
    val SEUILS_PAR_DEFAUT = listOf(10, 5, 1)

    /** Les seuils lus au relevé : absents = ceux d'avant ; présents, même vides, ils font foi. Logique pure, testée. */
    fun seuilsDe(lus: List<Int>?): List<Int> =
        lus?.filter { it > 0 }?.distinct()?.sortedDescending() ?: SEUILS_PAR_DEFAUT

    /** Le seuil à annoncer maintenant (le plus petit franchi et pas encore dit), ou null. Logique pure, testée. */
    fun seuilAAnnoncer(resteS: Long, seuils: List<Int>, cle: String, deja: Set<String>): Int? {
        if (resteS <= 0) return null
        // Le plus urgent des seuils franchis et pas encore dits (à 4 min : « 5 minutes », pas « 10 »).
        return seuils.filter { resteS <= it * 60L && "$cle|$it" !in deja }.minOrNull()
    }

    fun quand(minutes: Int): String = Tr.p(R.plurals.preavis_dans_minutes, minutes.coerceAtLeast(1), minutes.coerceAtLeast(1))

    fun texteAppareil(seuil: Int, finMs: Long, motif: String): String {
        val heure = Tr.heure(finMs)
        return if (motif.isNotEmpty()) Tr.s(R.string.preavis_appareil_motif, quand(seuil), heure, motif.replaceFirstChar { it.lowercase() })
            else Tr.s(R.string.preavis_appareil, quand(seuil), heure)
    }

    fun texteAppli(seuil: Int, libelle: String): String =
        Tr.p(R.plurals.preavis_appli, seuil.coerceAtLeast(1), libelle, seuil.coerceAtLeast(1))

    /** Le relevé : rend vrai si un préavis d'appareil est en cours (l'ancien avertissement unique se tait alors). */
    fun releve(ctx: Context, r: JSONObject): Boolean {
        if (!Modules.actif(ctx, Modules.PREAVIS)) return false
        val maintenantS = System.currentTimeMillis() / 1000
        var enCours = false
        r.optJSONObject("preavis")?.let { p ->
            enCours = true
            val fin = p.optLong("fin")
            val seuils = seuilsDe(lireSeuils(p.optJSONArray("seuils")))
            synchronized(dits) {
                val s = seuilAAnnoncer(fin - maintenantS, seuils, "app:$fin", dits)
                if (s != null) {
                    // Les seuils plus grands, passés sans être dits (appareil hors ligne), ne seront plus dits.
                    seuils.filter { it >= s }.forEach { dits.add("app:$fin|$it") }
                    Messages.info(ctx, "Vision", texteAppareil(s, fin * 1000, p.optString("motif")))
                }
            }
        }
        val apps = r.optJSONArray("preavis_apps") ?: JSONArray()
        val jour = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
        for (i in 0 until apps.length()) {
            val a = apps.optJSONObject(i) ?: continue
            val cle = "appli:${a.optString("nom")}:$jour"
            val seuilsApp = seuilsDe(lireSeuils(a.optJSONArray("seuils")))
            synchronized(dits) {
                val s = seuilAAnnoncer(a.optLong("reste_s"), seuilsApp, cle, dits)
                if (s != null) {
                    seuilsApp.filter { it >= s }.forEach { dits.add("$cle|$it") }
                    Messages.info(ctx, "Vision", texteAppli(s, Usage.libelle(ctx, a.optString("nom")).ifEmpty { a.optString("libelle") }))
                }
            }
        }
        synchronized(dits) { while (dits.size > 200) dits.remove(dits.first()) }
        return enCours
    }

    private fun lireSeuils(a: JSONArray?): List<Int>? = a?.let { t -> (0 until t.length()).map { t.optInt(it) } }
}
