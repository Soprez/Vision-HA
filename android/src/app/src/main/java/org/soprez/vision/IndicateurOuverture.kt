// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * L'indicateur des ouvertures de la veille (l'auteur, 15/10) : portes, fenêtres, portails, stores, garages, serrures, vannes.
 *
 * Home Assistant dit, pour chaque case, si c'est une ouverture et de quel type (veille_tableaux.ouverture) ; l'appareil
 * choisit comment la montrer — réglages de cet appareil, défauts sûrs pour qui n'a rien réglé :
 * - **style** : pastille (défaut), icône du type (variante ouverte / fermée), liseré de couleur au bord de la case, ou
 *   aucun indicateur (le texte seul) ;
 * - **couleurs** : ouvert en rouge, fermé en vert (défauts), au choix parmi six ;
 * - **seulement quand c'est ouvert** : non par défaut (une porte fermée garde son indicateur).
 * Un état inconnu (capteur muet) a toujours un indicateur neutre, jamais la couleur « fermé ».
 * La partie pure ([montrer], [couleurEtat]) est essayée en JUnit (IndicateurOuvertureTest).
 */
object IndicateurOuverture {
    const val PASTILLE = "pastille"
    const val ICONE = "icone"
    const val LISERE = "lisere"
    const val AUCUN = "aucun"
    val STYLES = listOf(PASTILLE, ICONE, LISERE, AUCUN)

    const val ROUGE = "rouge"
    const val ORANGE = "orange"
    const val VERT = "vert"
    const val BLEU = "bleu"
    const val ACCENT = "accent"
    const val GRIS = "gris"
    val COULEURS = listOf(ROUGE, ORANGE, VERT, BLEU, ACCENT, GRIS)

    private const val PREFS = "veille_ouvertures"
    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun lire(ctx: Context, cle: String, defaut: String, permis: List<String>) =
        (p(ctx).getString(cle, defaut) ?: defaut).takeIf { it in permis } ?: defaut
    private fun ecrire(ctx: Context, cle: String, v: String) { p(ctx).edit().putString(cle, v).apply(); Local.toucher() }

    fun style(ctx: Context): String = lire(ctx, "style", PASTILLE, STYLES)
    fun poserStyle(ctx: Context, v: String) = ecrire(ctx, "style", v)
    fun couleurOuvert(ctx: Context): String = lire(ctx, "ouvert", ROUGE, COULEURS)
    fun poserCouleurOuvert(ctx: Context, v: String) = ecrire(ctx, "ouvert", v)
    fun couleurFerme(ctx: Context): String = lire(ctx, "ferme", VERT, COULEURS)
    fun poserCouleurFerme(ctx: Context, v: String) = ecrire(ctx, "ferme", v)
    fun seulementOuvert(ctx: Context): Boolean = p(ctx).getBoolean("seulement_ouvert", false)
    fun poserSeulementOuvert(ctx: Context, v: Boolean) { p(ctx).edit().putBoolean("seulement_ouvert", v).apply(); Local.toucher() }

    /** Les réglages lus une fois (la veille les relit au plus toutes les cinq secondes, jamais à chaque image). */
    data class Reglages(val style: String = PASTILLE, val ouvert: String = ROUGE, val ferme: String = VERT, val seulementOuvert: Boolean = false)
    fun reglages(ctx: Context) = Reglages(style(ctx), couleurOuvert(ctx), couleurFerme(ctx), seulementOuvert(ctx))

    // ------------------------------------------------------------------ partie pure

    /** L'indicateur se montre-t-il ? Jamais avec « aucun » ; « seulement ouvert » le cache sur fermé (pas sur inconnu). */
    fun montrer(r: Reglages, on: Boolean?): Boolean = r.style != AUCUN && !(r.seulementOuvert && on == false)

    /** Le code de couleur d'un état : ouvert, fermé, ou null pour l'indicateur neutre (état inconnu). */
    fun couleurEtat(r: Reglages, on: Boolean?): String? = when (on) { true -> r.ouvert; false -> r.ferme; null -> null }

    /** Les couleurs réelles : celles du thème pour l'accent et le gris, des teintes franches sinon. */
    fun argb(code: String?, accent: Int, gris: Int, neutre: Int): Int = when (code) {
        ROUGE -> 0xFFD13B3B.toInt(); ORANGE -> 0xFFE58A1F.toInt(); VERT -> 0xFF2F8F5B.toInt(); BLEU -> 0xFF3A7BD5.toInt()
        ACCENT -> accent; GRIS -> gris; else -> neutre
    }

    fun nomStyle(ctx: Context, s: String): String = ctx.getString(when (s) {
        ICONE -> R.string.ouv_style_icone; LISERE -> R.string.ouv_style_lisere; AUCUN -> R.string.ouv_style_aucun; else -> R.string.ouv_style_pastille
    })

    fun nomCouleur(ctx: Context, c: String): String = ctx.getString(when (c) {
        ORANGE -> R.string.ouv_couleur_orange; VERT -> R.string.ouv_couleur_vert; BLEU -> R.string.ouv_couleur_bleu
        ACCENT -> R.string.ouv_couleur_accent; GRIS -> R.string.ouv_couleur_gris; else -> R.string.ouv_couleur_rouge
    })

    // ------------------------------------------------------------------ icônes (traits, aux couleurs du thème)

    private val chemin = Path()
    private val boite = RectF()

    /**
     * L'icône d'un type d'ouverture, centrée en ([cx], [cy]), dans un carré de côté 2·[r] ; ouverte, fermée ou inconnue
     * (dessinée fermée, la couleur neutre dit l'incertitude). [p] arrive réglée (couleur, opacité) ; son style change.
     */
    fun dessinerIcone(c: Canvas, type: String, ouvert: Boolean?, cx: Float, cy: Float, r: Float, p: Paint) {
        val o = ouvert == true
        val e = r * 0.16f
        p.style = Paint.Style.STROKE; p.strokeWidth = e; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
        val g = cx - r * 0.75f; val d = cx + r * 0.75f; val h = cy - r * 0.9f; val b = cy + r * 0.9f
        chemin.reset()
        when (type) {
            "fenetre" -> {
                boite.set(g, h, d, b); c.drawRect(boite, p)
                if (o) {  // un battant ouvert vers soi : un trapèze sur la moitié droite
                    c.drawLine(cx, h, cx, b, p)
                    chemin.moveTo(cx, h); chemin.lineTo(d + r * 0.25f, h + r * 0.35f); chemin.lineTo(d + r * 0.25f, b - r * 0.35f); chemin.lineTo(cx, b)
                    c.drawPath(chemin, p)
                } else { c.drawLine(cx, h, cx, b, p); c.drawLine(g, cy, d, cy, p) }
            }
            "portail" -> {
                c.drawLine(g, h, g, b, p); c.drawLine(d, h, d, b, p)  // les piliers
                if (o) {  // les deux vantaux ouverts vers soi, comme la porte ouverte : deux trapèzes contre les piliers
                    chemin.moveTo(g, h + r * 0.25f); chemin.lineTo(g + r * 0.5f, h + r * 0.45f); chemin.lineTo(g + r * 0.5f, b + r * 0.05f); chemin.lineTo(g, b - r * 0.15f)
                    chemin.moveTo(d, h + r * 0.25f); chemin.lineTo(d - r * 0.5f, h + r * 0.45f); chemin.lineTo(d - r * 0.5f, b + r * 0.05f); chemin.lineTo(d, b - r * 0.15f)
                    c.drawPath(chemin, p)
                } else {
                    c.drawLine(g, h + r * 0.35f, d, h + r * 0.35f, p); c.drawLine(g, b - r * 0.3f, d, b - r * 0.3f, p)
                    for (k in 1..3) { val x = g + (d - g) * k / 4f; c.drawLine(x, h + r * 0.2f, x, b - r * 0.15f, p) }
                }
            }
            "store" -> {
                c.drawLine(g - r * 0.1f, h, d + r * 0.1f, h, p)  // le coffre
                val lames = if (o) 1 else 4
                for (k in 1..lames) { val y = h + (b - h) * k / 5f; c.drawLine(g, y, d, y, p) }
                if (!o) { c.drawLine(g, h, g, b - (b - h) / 5f, p); c.drawLine(d, h, d, b - (b - h) / 5f, p) }
            }
            "garage" -> {
                chemin.moveTo(g - r * 0.1f, cy - r * 0.2f); chemin.lineTo(cx, h); chemin.lineTo(d + r * 0.1f, cy - r * 0.2f)
                c.drawPath(chemin, p)
                c.drawLine(g, cy - r * 0.2f, g, b, p); c.drawLine(d, cy - r * 0.2f, d, b, p)
                val lames = if (o) 1 else 3
                for (k in 0 until lames) { val y = cy + r * 0.05f + k * r * 0.3f; c.drawLine(g + e, y, d - e, y, p) }
            }
            "cadenas" -> {
                boite.set(cx - r * 0.65f, cy - r * 0.05f, cx + r * 0.65f, b); c.drawRoundRect(boite, r * 0.15f, r * 0.15f, p)
                boite.set(cx - r * 0.42f, h, cx + r * 0.42f, cy + r * 0.3f)
                if (o) { c.save(); c.translate(r * 0.42f, -r * 0.2f) }  // l'anse soulevée, décalée
                c.drawArc(boite, 180f, 180f, false, p)
                c.drawLine(cx - r * 0.42f, cy - r * 0.3f, cx - r * 0.42f, cy - r * 0.05f, p)
                if (!o) c.drawLine(cx + r * 0.42f, cy - r * 0.3f, cx + r * 0.42f, cy - r * 0.05f, p)
                if (o) c.restore()
            }
            "vanne" -> {
                c.drawLine(g - r * 0.1f, cy, cx - r * 0.3f, cy, p); c.drawLine(cx + r * 0.3f, cy, d + r * 0.1f, cy, p)
                boite.set(cx - r * 0.3f, cy - r * 0.3f, cx + r * 0.3f, cy + r * 0.3f); c.drawOval(boite, p)
                if (o) c.drawLine(cx, cy - r * 0.3f, cx, h, p) else c.drawLine(cx - r * 0.45f, h + r * 0.15f, cx + r * 0.45f, h + r * 0.15f, p)
            }
            else -> {  // porte
                boite.set(g, h, d, b); c.drawRect(boite, p)
                if (o) {  // le battant ouvert vers soi
                    chemin.moveTo(g, h); chemin.lineTo(g + r * 0.85f, h + r * 0.3f); chemin.lineTo(g + r * 0.85f, b + r * 0.15f); chemin.lineTo(g, b)
                    c.drawPath(chemin, p)
                } else {
                    p.style = Paint.Style.FILL; c.drawCircle(d - r * 0.35f, cy + r * 0.1f, e * 0.9f, p)
                }
            }
        }
        p.style = Paint.Style.FILL
    }
}
