// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 1.90.5 (l'auteur, variante B validée) : la réaction de l'œil à la place des petits bandeaux gris (Toast). L'œil paraît,
 * se ferme vite et se rouvre sur un symbole (✓, ✗, i) dans un disque de couleur, qui s'efface.
 *
 * - Succès : l'œil seul (le ✓ dit tout), puis il disparaît.
 * - Info et erreur : l'œil réagit et le texte reste à côté de lui, dans une bulle, le temps de le lire (beaucoup de ces
 *   messages disent quoi faire : « Choisis une enceinte… », « Ouvre les réglages… »).
 * - Rien ne bloque, rien ne prend le focus ; le texte est annoncé à TalkBack (région « live »).
 * - L'œil se pose dans un coin libre de l'écran utile (hors des barres du système) : jamais sur un bouton, un champ ou
 *   un texte ([placer]) ; à défaut, en bas au centre, comme l'ancien bandeau.
 * - Animations du système coupées : le symbole s'affiche sans mouvement (OeilVue).
 */
object ReactionOeil {
    private const val PREFS = "vision"
    private const val CLE_ACTIF = "reactions_oeil"
    const val TAG = "reaction_oeil"
    /** Le temps de lire un texte (court / long), comme les bandeaux d'avant. */
    const val LIRE_COURT_MS = 2_500L
    const val LIRE_LONG_MS = 4_000L

    fun actif(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, 0).getBoolean(CLE_ACTIF, true)
    fun actif(ctx: Context, oui: Boolean) = ctx.getSharedPreferences(PREFS, 0).edit().putBoolean(CLE_ACTIF, oui).apply()

    /** Le texte reste-t-il visible à côté de l'œil ? Succès : non (le ✓ suffit) ; info et erreur : oui. */
    fun avecTexte(genre: OeilModele.Genre): Boolean = genre != OeilModele.Genre.SUCCES && genre != OeilModele.Genre.JOIE

    /** Un rectangle (px), sans Android : pour [placer] et ses essais. */
    data class Boite(val g: Int, val h: Int, val d: Int, val b: Int) {
        fun croise(o: Boite) = g < o.d && o.g < d && h < o.b && o.h < b
        fun dans(o: Boite) = g >= o.g && h >= o.h && d <= o.d && b <= o.b
    }

    /**
     * Où poser une réaction de [l]×[hauteur] dans [utile] : le premier coin (haut-fin, bas-fin, haut-début, bas-début,
     * puis bas et haut au centre) qui ne touche aucun des [occupes] ; à défaut, en bas au centre. Pur, pour les essais.
     */
    fun placer(utile: Boite, l: Int, hauteur: Int, marge: Int, occupes: List<Boite>, rtl: Boolean = false): Boite {
        val gauche = utile.g + marge; val droite = utile.d - marge - l
        val haut = utile.h + marge; val bas = utile.b - marge - hauteur
        val cx = (utile.g + utile.d) / 2 - l / 2
        val debut = if (rtl) droite else gauche; val fin = if (rtl) gauche else droite
        fun libre(x: Int, y: Int): Boite? = Boite(x, y, x + l, y + hauteur).takeIf { r -> r.dans(utile) && occupes.none { it.croise(r) } }
        // D'abord les coins, puis le bas et le haut au centre.
        for ((x, y) in listOf(fin to haut, fin to bas, debut to haut, debut to bas, cx to bas, cx to haut)) libre(x, y)?.let { return it }
        // Aucun coin libre : une place libre le long du bord de fin, du bord de début, puis au centre, de haut en bas
        // (la bande vide entre l'en-tête et la rangée d'applis d'une télé, par exemple).
        val pas = maxOf(1, hauteur / 4)
        for (x in listOf(fin, debut, cx)) { var y = haut + pas; while (y < bas) { libre(x, y)?.let { return it }; y += pas } }
        return Boite(cx, bas, cx + l, bas + hauteur)
    }

    /** Un coin libre existe-t-il ? (Sinon [placer] retombe en bas au centre, comme l'ancien bandeau.) */
    fun coinLibre(utile: Boite, l: Int, hauteur: Int, marge: Int, occupes: List<Boite>): Boolean {
        val r = placer(utile, l, hauteur, marge, occupes)
        return occupes.none { it.croise(r) }
    }

    /** Montre la réaction ; rend faux si elle n'a pas pu l'être (pas d'écran) : l'appelant garde alors son bandeau. */
    fun montrer(a: Activity, genre: OeilModele.Genre, texte: String, court: Boolean = false): Boolean {
        if (a.isFinishing || (Build.VERSION.SDK_INT >= 17 && a.isDestroyed)) return false
        val racine = a.window?.decorView as? ViewGroup ?: return false
        if (racine.width == 0 || racine.height == 0) return false
        val tele = ReglagesTvActivity.estTele(a)
        val theme = Local.themeEffectif(a, Palette.APPLI)
        val cote = Ui.dp(a, if (tele) 96f else 72f)
        val marge = Ui.dp(a, if (tele) 32f else 16f)
        // Une seule réaction à la fois : la suivante remplace la précédente.
        (0 until racine.childCount).map { racine.getChildAt(it) }.filter { it.tag == TAG }.forEach { racine.removeView(it) }

        val unite = LinearLayout(a).apply {
            tag = TAG
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            isFocusable = false; isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = texte
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            if (Build.VERSION.SDK_INT >= 21) translationZ = Ui.dp(a, 24f).toFloat()
        }
        val oeil = OeilVue(a).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        unite.addView(oeil, LinearLayout.LayoutParams(cote, cote))
        val utile = zoneUtile(a, racine)
        if (avecTexte(genre) && texte.isNotBlank()) {
            val bulle = TextView(a).apply {
                text = texte
                setTextColor(theme.encre)
                textSize = if (tele) 20f else 15f
                typeface = Polices.texte(a)
                Ui.sansCesure(this)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                val p = Ui.dp(a, if (tele) 16f else 12f)
                setPadding(p, p * 2 / 3, p, p * 2 / 3)
                maxWidth = (utile.width() * if (tele) 0.42f else 0.62f).toInt() - cote
                background = GradientDrawable().apply {
                    cornerRadius = Ui.dp(a, 14f).toFloat(); setColor(theme.carte)
                    setStroke(Ui.dp(a, 2f), when (genre) { OeilModele.Genre.ERREUR -> Ui.ROUGE; else -> theme.carteBord })
                }
            }
            unite.addView(bulle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = Ui.dp(a, 8f) })
        }
        unite.measure(View.MeasureSpec.makeMeasureSpec(utile.width(), View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(utile.height(), View.MeasureSpec.AT_MOST))
        val rtl = racine.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val ou = placer(Boite(utile.left, utile.top, utile.right, utile.bottom), unite.measuredWidth, unite.measuredHeight, marge,
            occupes(racine).map { Boite(it.left, it.top, it.right, it.bottom) }, rtl)
        // La taille naturelle (la mesure a servi à choisir le coin) : rien n'est rogné si la mise en page finale diffère.
        racine.addView(unite, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.TOP or Gravity.START; leftMargin = ou.g; topMargin = ou.h })
        unite.announceForAccessibility(texte)
        val reste = if (avecTexte(genre)) (if (court) LIRE_COURT_MS else LIRE_LONG_MS) else 0L
        oeil.fin = {
            // Le symbole s'efface avec la réaction ; le texte reste encore le temps de le lire.
            unite.postDelayed({ unite.animate().alpha(0f).setDuration(220).withEndAction { racine.removeView(unite) }.start() }, reste)
        }
        oeil.reagir(OeilModele.Variante.B, genre)
        return true
    }

    /** L'écran utile : la fenêtre moins les barres du système et l'encoche. */
    private fun zoneUtile(a: Activity, racine: ViewGroup): Rect {
        val r = Rect(0, 0, racine.width, racine.height)
        if (Build.VERSION.SDK_INT >= 30) {
            val i = racine.rootWindowInsets?.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            if (i != null) { r.left += i.left; r.top += i.top; r.right -= i.right; r.bottom -= i.bottom }
        } else if (Build.VERSION.SDK_INT >= 23) {
            @Suppress("DEPRECATION") racine.rootWindowInsets?.let { r.left += it.systemWindowInsetLeft; r.top += it.systemWindowInsetTop; r.right -= it.systemWindowInsetRight; r.bottom -= it.systemWindowInsetBottom }
        }
        return r
    }

    /** Ce que la réaction ne doit pas couvrir : les vues visibles qu'on touche ou qu'on lit (boutons, champs, textes). */
    fun occupes(racine: ViewGroup): List<Rect> {
        val sortie = ArrayList<Rect>()
        val pos = IntArray(2); val origine = IntArray(2)
        racine.getLocationInWindow(origine)
        fun parcourir(v: View) {
            if (v.visibility != View.VISIBLE || v.tag == TAG || v.width == 0 || v.height == 0) return
            val utile = v.isClickable || v.isFocusable || (v is TextView && v.text.isNotBlank())
            if (utile && v !== racine) {
                v.getLocationInWindow(pos)
                val r = Rect(pos[0] - origine[0], pos[1] - origine[1], pos[0] - origine[0] + v.width, pos[1] - origine[1] + v.height)
                // Un texte non cliquable n'occupe que l'étendue de ses lignes (« Merci… » dans une vue étirée sur toute la
                // largeur laissait croire que tout le haut de l'écran était pris).
                val l = (v as? TextView)?.layout
                if (l != null && !v.isClickable && !v.isFocusable && l.lineCount > 0) {
                    val g = (0 until l.lineCount).minOf { l.getLineLeft(it) }.toInt(); val d = (0 until l.lineCount).maxOf { l.getLineRight(it) }.toInt()
                    val x0 = r.left + v.totalPaddingLeft - v.scrollX
                    r.set(maxOf(r.left, x0 + g), r.top, minOf(r.right, x0 + d + 1), r.bottom)
                }
                // Un grand conteneur focalisable (un panneau, une liste entière) ne compte pas : seuls ses éléments comptent.
                // Un conteneur qui défile non plus (une rangée de messages vide, focalisable par nature, barrait toute la
                // largeur de l'accueil TV).
                val grand = v is ViewGroup && r.width().toLong() * r.height() > 0.2 * racine.width.toLong() * racine.height
                val defile = v is android.widget.ScrollView || v is android.widget.HorizontalScrollView || v is android.widget.AbsListView
                if (!grand && !defile) sortie.add(r)
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) parcourir(v.getChildAt(i))
        }
        parcourir(racine)
        return sortie
    }
}
