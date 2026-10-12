// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.graphics.Color
import org.json.JSONObject

/**
 * Les couleurs à la carte, les mêmes pour tout l'appareil (lanceur, écran de
 * veille, écrans de l'appli). On part du thème de l'appareil, puis on change
 * le fond, les cartes, le texte ou l'accent ; « Revenir au thème » efface
 * tout. Rangé sur l'appareil.
 *
 * Un réglage : {"theme": "Sombre", "fond": "#17121C", "carte": …, "encre": …, "accent": …}
 * Les clés absentes gardent la valeur du thème.
 */
object Palette {
    // Une seule palette pour tout l'appareil : lanceur, écran de veille et écrans de l'appli.
    // Les deux noms restent pour le code qui les emploie ; ils désignent le même réglage.
    const val LANCEUR = "tout"
    const val APPLI = "tout"
    private const val PREFS = "couleurs"

    fun lire(ctx: Context, cible: String): JSONObject? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Avant 1.27, le lanceur et l'appli avaient chacun leurs couleurs : on reprend celles du lanceur.
        val brut = p.getString(cible, null) ?: p.getString("lanceur", null) ?: p.getString("appli", null) ?: return null
        return try { JSONObject(brut) } catch (_: Exception) { null }
    }

    fun ecrire(ctx: Context, cible: String, reglage: JSONObject?) {
        val e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("lanceur").remove("appli")
        if (reglage == null || reglage.length() == 0) e.remove(cible) else e.putString(cible, reglage.toString())
        e.apply()
    }

    /** Vrai si cette cible a au moins une couleur changée à la main. */
    fun personnalisee(ctx: Context, cible: String): Boolean {
        val r = lire(ctx, cible) ?: return false
        return listOf("fond", "carte", "encre", "accent").any { r.has(it) }
    }

    private fun couleur(s: String?): Int? = try { if (s.isNullOrEmpty()) null else Color.parseColor(s) } catch (_: Exception) { null }

    /** Le thème effectif d'une cible : le thème de base (celui donné), corrigé des couleurs choisies. */
    fun appliquer(ctx: Context, cible: String, base: Theme): Theme {
        val r = lire(ctx, cible) ?: return base
        val fond = couleur(r.optString("fond")) ?: base.fond
        val carte = couleur(r.optString("carte")) ?: base.carte
        val encre = couleur(r.optString("encre")) ?: base.encre
        val accent = couleur(r.optString("accent")) ?: base.pourpre
        if (fond == base.fond && carte == base.carte && encre == base.encre && accent == base.pourpre) return base
        return deriver(base, fond, carte, encre, accent)
    }

    /** Fabrique un thème cohérent à partir de quatre couleurs : encres secondaires, bord, lueurs en découlent. */
    fun deriver(base: Theme, fond: Int, carte: Int, encre: Int, accent: Int): Theme {
        val sombre = luminance(fond) < 0.45f
        val encre2 = melanger(encre, fond, 0.30f)
        val encre3 = melanger(encre, fond, 0.52f)
        val or = if (sombre) 0xFFE2B24A.toInt() else 0xFF8F6110.toInt()
        val bord = (accent and 0x00FFFFFF) or 0x55000000
        val halos = intArrayOf(melanger(accent, fond, 0.3f) and 0x00FFFFFF, melanger(or, fond, 0.4f) and 0x00FFFFFF, melanger(accent, 0xFF5050A0.toInt(), 0.5f) and 0x00FFFFFF)
        return Theme(base.nom, fond, encre, encre2, encre3, carte, bord, accent, or, halos, sombre)
    }

    fun luminance(c: Int): Float = (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f

    /** a vers b, à la proportion t (0 = a, 1 = b). */
    fun melanger(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        fun m(x: Int, y: Int) = (x + (y - x) * u).toInt().coerceIn(0, 255)
        return Color.argb(255, m(Color.red(a), Color.red(b)), m(Color.green(a), Color.green(b)), m(Color.blue(a), Color.blue(b)))
    }

    fun hex(c: Int): String = String.format("#%06X", c and 0xFFFFFF)
}
