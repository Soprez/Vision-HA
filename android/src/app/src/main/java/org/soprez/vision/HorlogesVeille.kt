// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import java.util.Calendar
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * Veille-lot : les styles de l'horloge de la veille, au choix dans les réglages (aperçu animé de chacun).
 *
 * Les couleurs viennent toujours du thème (encre, accent, or, carte, fond) : un style ne fixe que la forme. Le dessin
 * ne crée aucun objet par image (pinceaux, rectangle et calendrier réutilisés ; heure et minutes recalculées une fois
 * par seconde, l'heure en toutes lettres une fois par minute), pour tenir 60 images par seconde sur une petite télé.
 *
 * Mêmes identifiants sur la page de veille de Home Assistant (vision-veille-coeur.js, « hstyle ») et sur la veille du
 * PC (ha-parental-agent.ps1, « horloge » dans veille.json).
 */
object HorlogesVeille {
    const val ANNEAU = "anneau"
    const val FINE = "fine"
    const val GRASSE = "grasse"
    const val ANALOGIQUE = "analogique"
    const val CLASSIQUE = "classique"
    const val MOTS = "mots"
    const val FLIP = "flip"
    const val EMPILEE = "empilee"
    val STYLES = listOf(ANNEAU, FINE, GRASSE, ANALOGIQUE, CLASSIQUE, MOTS, FLIP, EMPILEE)
    /** « Celle du thème » : aucun style choisi ; la page de veille garde l'horloge du thème, le dessin de l'appli l'anneau. */
    const val THEME = "theme"
    /** Ce que propose le sélecteur : l'horloge du thème d'abord, puis les styles. */
    val CHOIX = listOf(THEME) + STYLES

    /** Durée de la bascule des palettes du style « flip » (ms). */
    const val DUREE_BASCULE = 600L

    /**
     * Un style lu dans les réglages : les anciens noms gardent le choix de chacun (« chiffres » → « fine »,
     * « aiguilles » → « analogique ») ; inconnu ou absent : l'anneau, le défaut.
     */
    fun normaliser(s: String?): String = when (s) {
        "chiffres" -> FINE
        "aiguilles" -> ANALOGIQUE
        else -> if (s != null && s in STYLES) s else ANNEAU
    }

    /** 1.91.2 : les chiffres de l'heure — « 14 » / « 09 » sur 24 h, « 2 » / « 9 » sur 12 h (12 pour midi et minuit). */
    fun chiffresHeure(heure: Int, h24: Boolean): String {
        val h = ((heure % 24) + 24) % 24
        return if (h24) (if (h < 10) "0$h" else h.toString()) else ((h + 11) % 12 + 1).toString()
    }

    /** 1.91.2 : le repère du matin ou de l'après-midi sur 12 h, dans la langue de l'appareil (« AM », « PM »…) ; rien sur 24 h. */
    fun repere12h(heure: Int, h24: Boolean, locale: Locale): String =
        if (h24) "" else java.text.DateFormatSymbols.getInstance(locale).amPmStrings[if (((heure % 24) + 24) % 24 < 12) 0 else 1]

    /** La chaîne (traduite) du nom de chaque style. */
    fun nomRes(style: String): Int = when (style) {
        THEME -> R.string.horloge_theme
        FINE -> R.string.horloge_fine
        GRASSE -> R.string.horloge_grasse
        ANALOGIQUE -> R.string.horloge_analogique
        CLASSIQUE -> R.string.horloge_classique
        MOTS -> R.string.horloge_mots
        FLIP -> R.string.horloge_flip
        EMPILEE -> R.string.horloge_empilee
        else -> R.string.horloge_anneau
    }

    fun nom(ctx: Context, style: String): String = ctx.getString(nomRes(style))

    /** Où la date et la ligne météo se posent sous l'horloge (fractions de la hauteur). */
    fun lignesSous(style: String): FloatArray = when (style) {
        FINE -> floatArrayOf(0.66f, 0.76f)
        GRASSE -> floatArrayOf(0.70f, 0.80f)
        ANALOGIQUE, CLASSIQUE, EMPILEE, FLIP -> floatArrayOf(0.76f, 0.85f)
        MOTS -> floatArrayOf(0.76f, 0.85f)
        else -> floatArrayOf(0.66f, 0.76f)
    }

    /**
     * Vrai quand le style bouge entre deux secondes (balayage d'une trotteuse, anneau des secondes, bascule des
     * palettes) : les vignettes ne redessinent alors plus d'une fois par seconde que dans ces cas-là.
     */
    fun bougeEnContinu(style: String, p: Pinceaux, murale: Long): Boolean = when (style) {
        ANNEAU, CLASSIQUE -> true
        FLIP -> murale - p.basculeA in 0 until DUREE_BASCULE
        else -> false
    }

    // --- L'heure en toutes lettres -------------------------------------------------------------------------------

    private val FR = arrayOf("zéro", "une", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf", "dix", "onze", "douze",
        "treize", "quatorze", "quinze", "seize", "dix-sept", "dix-huit", "dix-neuf")
    private val FR_DIZAINES = arrayOf("", "", "vingt", "trente", "quarante", "cinquante")
    private val EN = arrayOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
        "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
    private val EN_DIZAINES = arrayOf("", "", "twenty", "thirty", "forty", "fifty")

    private fun fr(n: Int): String = if (n < 20) FR[n] else FR_DIZAINES[n / 10] + when (n % 10) { 0 -> ""; 1 -> " et une"; else -> "-" + FR[n % 10] }
    private fun en(n: Int): String = if (n < 20) EN[n] else EN_DIZAINES[n / 10] + if (n % 10 == 0) "" else "-" + EN[n % 10]

    /**
     * L'heure en toutes lettres, dans la langue de l'appareil (français ; anglais pour les autres langues) :
     * quatre lignes [avant l'heure, l'heure, après l'heure, moment de la journée], vides quand elles ne servent pas.
     * « dix heures » / « vingt-cinq » / « du soir » ; « twenty-five past » / « ten » / « in the evening ».
     */
    fun enMots(heure: Int, minute: Int, langue: String): Array<String> {
        val h = ((heure % 24) + 24) % 24; val m = ((minute % 60) + 60) % 60
        return if (langue.lowercase(Locale.ROOT).startsWith("fr")) {
            // 40, 45, 50, 55 : « moins » l'heure suivante, comme on le dit.
            val moins = m in intArrayOf(40, 45, 50, 55)
            val hh = if (moins) (h + 1) % 24 else h
            val nomH = when (hh) { 0 -> "minuit"; 12 -> "midi"; else -> { val d = if (hh > 12) hh - 12 else hh; fr(d) + if (d > 1) " heures" else " heure" } }
            val apres = when {
                m == 0 -> ""
                m == 15 -> "et quart"
                m == 30 -> "et demie"
                m == 45 -> "moins le quart"
                moins -> "moins " + fr(60 - m)
                else -> fr(m)
            }
            val moment = when (hh) { 0, 12 -> ""; in 1..11 -> "du matin"; in 13..17 -> "de l'après-midi"; else -> "du soir" }
            arrayOf("", nomH, apres, moment)
        } else {
            val vers = m in intArrayOf(35, 40, 45, 50, 55)
            val hh = if (vers) (h + 1) % 24 else h
            val douze = if (hh % 12 == 0) 12 else hh % 12
            val moment = when (hh) { in 5..11 -> "in the morning"; in 12..17 -> "in the afternoon"; in 18..21 -> "in the evening"; else -> "at night" }
            when {
                m == 0 && hh == 0 -> arrayOf("", "midnight", "", "")
                m == 0 && hh == 12 -> arrayOf("", "noon", "", "")
                m == 0 -> arrayOf("", en(douze) + " o'clock", "", moment)
                m == 15 -> arrayOf("quarter past", en(douze), "", moment)
                m == 30 -> arrayOf("half past", en(douze), "", moment)
                m == 45 -> arrayOf("quarter to", en(douze), "", moment)
                vers -> arrayOf(en(60 - m) + " to", en(douze), "", moment)
                m % 5 == 0 -> arrayOf(en(m) + " past", en(douze), "", moment)
                else -> arrayOf("", en(douze), if (m < 10) "oh " + en(m) else en(m), moment)
            }
        }
    }

    // --- Dessin -------------------------------------------------------------------------------------------------

    /** Les couleurs du thème, recopiées (aucune lecture des réglages pendant le dessin). */
    class Couleurs(var encre: Int, var encre2: Int, var accent: Int, var or: Int, var carte: Int, var fond: Int)

    /** Ce qu'une horloge réutilise d'une image à l'autre. */
    class Pinceaux(val langue: String = Locale.getDefault().language) {
        val texte = Paint(Paint.ANTI_ALIAS_FLAG)
        val forme = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = RectF()
        val cal: Calendar = Calendar.getInstance()
        val fin: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        val epais: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val noir: Typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        var hh = "00"; var mm = "00"
        /** 1.91.2 : « AM » / « PM » sur 12 h (vide sur 24 h), dessiné en petit par les styles en chiffres. */
        var ampm = ""
        /** 1.91.2 : faux pour l'heure sur 12 (« 2:05 » au lieu de « 14:05 », sans zéro devant l'heure). */
        var h24 = true
        /** Les chiffres d'avant la dernière bascule (style « flip ») et l'instant (horloge murale) où elle a commencé. */
        var ahh = "00"; var amm = "00"
        var basculeA = Long.MIN_VALUE
        /** L'heure en toutes lettres, recalculée quand la minute change. */
        var mots: Array<String> = arrayOf("", "", "", "")
        private var seconde = -1L
        private var minuteVue = -1
        var sec = 0f
        var heure = 0
        var minute = 0

        fun maj(murale: Long) {
            cal.timeInMillis = murale
            sec = cal.get(Calendar.SECOND) + cal.get(Calendar.MILLISECOND) / 1000f
            val s = murale / 1000
            if (s != seconde) {
                seconde = s
                heure = cal.get(Calendar.HOUR_OF_DAY); minute = cal.get(Calendar.MINUTE)
                val cle = heure * 60 + minute
                if (cle != minuteVue) {
                    val nh = chiffresHeure(heure, h24); val nm = deux(minute)
                    ampm = repere12h(heure, h24, Locale(langue))
                    // La première lecture ne bascule pas : les palettes arrivent déjà à l'heure.
                    if (minuteVue >= 0) { ahh = hh; amm = mm; basculeA = murale - (murale % 1000) } else { ahh = nh; amm = nm }
                    hh = nh; mm = nm
                    mots = enMots(heure, minute, langue)
                    minuteVue = cle
                }
            }
        }

        private fun deux(v: Int): String = if (v < 10) "0$v" else v.toString()

        val chiffres12 = Array(12) { (if (it == 0) 12 else it).toString() }
    }

    private fun lisser(x: Float): Float { val c = x.coerceIn(0f, 1f); return c * c * (3 - 2 * c) }

    /**
     * Dessine l'horloge `style` dans une zone de largeur `w` et de hauteur `h` (l'écran, ou une vignette).
     * `p0` : apparition 0..1 ; `alpha` : opacité générale.
     */
    fun dessiner(c: Canvas, style: String, w: Float, h: Float, p: Pinceaux, k: Couleurs, alpha: Float, p0: Float, murale: Long = System.currentTimeMillis()) {
        p.maj(murale)
        when (style) {
            FINE -> fine(c, w, h, p, k, alpha, p0)
            GRASSE -> grasse(c, w, h, p, k, alpha, p0)
            ANALOGIQUE -> analogique(c, w, h, p, k, alpha, p0)
            CLASSIQUE -> classique(c, w, h, p, k, alpha, p0)
            MOTS -> mots(c, w, h, p, k, alpha, p0)
            FLIP -> flip(c, w, h, p, k, alpha, p0, murale)
            EMPILEE -> empilee(c, w, h, p, k, alpha, p0)
            else -> anneau(c, w, h, p, k, alpha, p0)
        }
    }

    private fun texte(c: Canvas, p: Pinceaux, s: String, x: Float, y: Float, taille: Float, couleur: Int, alpha: Float, police: Typeface, align: Paint.Align = Paint.Align.LEFT) {
        val t = p.texte
        t.typeface = police; t.textSize = taille; t.color = couleur; t.alpha = (alpha * 255).toInt().coerceIn(0, 255); t.textAlign = align; t.letterSpacing = 0f
        c.drawText(s, Math.round(x).toFloat(), Math.round(y).toFloat(), t)
    }

    private fun largeur(p: Pinceaux, s: String, taille: Float, police: Typeface): Float {
        p.texte.typeface = police; p.texte.textSize = taille; p.texte.letterSpacing = 0f
        return p.texte.measureText(s)
    }

    /** Les deux points battent la seconde. */
    private fun battement(sec: Float): Float = 0.35f + 0.65f * (0.5f + 0.5f * cos((sec % 1f) * 2 * Math.PI).toFloat())

    /** L'heure en chiffres, centrée, deux points à l'accent. */
    private fun ligneHeure(c: Canvas, p: Pinceaux, k: Couleurs, cx: Float, y: Float, taille: Float, alpha: Float, police: Typeface, battre: Boolean = true) {
        val lH = largeur(p, p.hh, taille, police); val lD = largeur(p, ":", taille, police); val lM = largeur(p, p.mm, taille, police)
        var x = cx - (lH + lD + lM) / 2
        texte(c, p, p.hh, x, y, taille, k.encre, alpha, police); x += lH
        texte(c, p, ":", x, y, taille, k.accent, if (battre) alpha * battement(p.sec) else alpha, police); x += lD
        texte(c, p, p.mm, x, y, taille, k.encre, alpha, police)
        // 12 h : le repère en petit, après les minutes (hors du centrage : l'heure reste au milieu).
        if (p.ampm.isNotEmpty()) texte(c, p, p.ampm, x + lM + taille * 0.06f, y, taille * 0.22f, k.encre2, alpha, p.epais)
    }

    /** Le style d'origine : l'anneau des secondes qui s'écrit autour de l'heure. */
    private fun anneau(c: Canvas, w: Float, h: Float, p: Pinceaux, k: Couleurs, alpha: Float, p0: Float) {
        val r = h * 0.42f
        p.rect.set(w / 2 - r, h * 0.5f - r, w / 2 + r, h * 0.5f + r)
        val f = p.forme
        f.style = Paint.Style.STROKE; f.strokeWidth = h * 0.006f; f.strokeCap = Paint.Cap.ROUND
        f.color = k.or; f.alpha = (alpha * 60).toInt()
        c.drawArc(p.rect, -90f, 360f, false, f)
        f.alpha = (alpha * 220 * p0).toInt()
        c.drawArc(p.rect, -90f, 360f * p.sec / 60f * p0, false, f)
        f.style = Paint.Style.FILL
        val ang = Math.toRadians((-90f + 360f * p.sec / 60f * p0).toDouble())
        f.color = k.or; f.alpha = (alpha * 255 * p0).toInt()
        c.drawCircle(w / 2 + (r * cos(ang)).toFloat(), h * 0.5f + (r * sin(ang)).toFloat(), h * 0.012f, f)
        val echelle = 0.9f + 0.1f * lisser(p0)
        c.save(); c.scale(echelle, echelle, w / 2, h * 0.5f)
        ligneHeure(c, p, k, w / 2, h * 0.55f, h * 0.30f, alpha * p0, p.fin)
        c.restore()
    }

    /** Numérique fine : grands chiffres fins, un trait d'accent dessous qui se remplit avec les secondes. */
    private fun fine(c: Canvas, w: Float, h: Float, p: Pinceaux, k: Couleurs, alpha: Float, p0: Float) {
        ligneHeure(c, p, k, w / 2, h * 0.53f, h * 0.38f, alpha * p0, p.fin)
        val l = h * 0.5f; val y = h * 0.585f; val e = h * 0.004f
        val f = p.forme
        f.style = Paint.Style.FILL
        f.color = k.encre2; f.alpha = (alpha * 50 * p0).toInt()
        p.rect.set(w / 2 - l / 2, y - e, w / 2 + l / 2, y + e); c.drawRoundRect(p.rect, e, e, f)
        f.color = k.accent; f.alpha = (alpha * 220 * p0).toInt()
        p.rect.set(w / 2 - l / 2, y - e, w / 2 - l / 2 + l * (p.sec / 60f) * p0, y + e); c.drawRoundRect(p.rect, e, e, f)
    }

    /** Numérique grasse : des chiffres très épais, deux points pleins à l'accent. */
    private fun grasse(c: Canvas, w: Float, h: Float, p: Pinceaux, k: Couleurs, alpha: Float, p0: Float) {
        val echelle = 0.92f + 0.08f * lisser(p0)
        c.save(); c.scale(echelle, echelle, w / 2, h * 0.45f)
        // Plus large que haut : la taille suit la largeur sur un écran étroit.
        val taille = minOf(h * 0.42f, w * 0.30f)
        ligneHeure(c, p, k, w / 2, h * 0.45f + taille * 0.36f, taille, alpha * p0, p.noir, battre = false)
        c.restore()
    }

    private fun aiguille(c: Canvas, p: Pinceaux, cx: Float, cy: Float, tours: Double, longueur: Float, queue: Float, epaisseur: Float, couleur: Int, alpha: Float) {
        val a = tours * 2 * Math.PI
        val f = p.forme
        f.style = Paint.Style.STROKE; f.strokeCap = Paint.Cap.ROUND
        f.color = couleur; f.alpha = (alpha * 255).toInt().coerceIn(0, 255); f.strokeWidth = epaisseur
        c.drawLine(cx - (queue * sin(a)).toFloat(), cy + (queue * cos(a)).toFloat(), cx + (longueur * sin(a)).toFloat(), cy - (longueur * cos(a)).toFloat(), f)
    }

    /** Analogique minimaliste : un cadran nu (un filet), deux aiguilles fines, l'axe à l'accent. */
    private fun analogique(c: Canvas, w: Float, h: Float, p: Pinceaux, k: Couleurs, alpha: Float, p0: Float) {
        val cx = w / 2; val cy = h * 0.40f; val r = h * 0.30f * (0.9f + 0.1f * lisser(p0))
        val a = alpha * p0
        val f = p.forme
        f.style = Paint.Style.STROKE; f.color = k.encre2; f.alpha = (a * 60).toInt(); f.strokeWidth = h * 0.003f
        c.drawCircle(cx, cy, r, f)
        // Un seul repère, à midi.
        f.style = Paint.Style.FILL; f.color = k.or; f.alpha = (a * 220).toInt()
        c.drawCircle(cx, cy - r * 0.90f, h * 0.007f, f)
        val minutes = p.minute + p.sec / 60.0
        aiguille(c, p, cx, cy, ((p.heure % 12) + minutes / 60.0) / 12.0, r * 0.55f, 0f, h * 0.008f, k.encre, a)
        aiguille(c, p, cx, cy, minutes / 60.0, r * 0.84f, 0f, h * 0.005f, k.encre, a)
        f.style = Paint.Style.FILL; f.color = k.accent; f.alpha = (a * 255).toInt()
        c.drawCircle(cx, cy, h * 0.010f, f)
    }

    /** Analogique classique : soixante graduations, les douze chiffres, la trotteuse qui balaie. */
    private fun classique(c: Canvas, w: Float, h: Float, p: Pinceaux, k: Couleurs, alpha: Float, p0: Float) {
        val cx = w / 2; val cy = h * 0.40f; val r = h * 0.31f * (0.9f + 0.1f * lisser(p0))
        val a = alpha * p0
        val f = p.forme
        f.style = Paint.Style.STROKE; f.strokeCap = Paint.Cap.ROUND
        f.color = k.or; f.alpha = (a * 110).toInt(); f.strokeWidth = h * 0.005f
        c.drawCircle(cx, cy, r, f)
        for (i in 0 until 60) {
            val ang = i * Math.PI / 30
            val heure = i % 5 == 0
            val r1 = if (heure) r * 0.86f else r * 0.91f
            f.color = if (heure) k.encre else k.encre2; f.alpha = (a * (if (heure) 230 else 120)).toInt()
            f.strokeWidth = h * (if (heure) 0.007f else 0.0025f)
            c.drawLine(cx + (r1 * sin(ang)).toFloat(), cy - (r1 * cos(ang)).toFloat(), cx + (r * 0.95f * sin(ang)).toFloat(), cy - (r * 0.95f * cos(ang)).toFloat(), f)
        }
        val tc = r * 0.17f
        val t = p.texte
        t.typeface = p.epais; t.textSize = tc; t.color = k.encre; t.alpha = (a * 255).toInt().coerceIn(0, 255); t.textAlign = Paint.Align.CENTER; t.letterSpacing = 0f
        for (i in 0 until 12) {
            val ang = i * Math.PI / 6
            c.drawText(p.chiffres12[i], cx + (r * 0.70f * sin(ang)).toFloat(), cy - (r * 0.70f * cos(ang)).toFloat() + tc * 0.36f, t)
        }
        val minutes = p.minute + p.sec / 60.0
        aiguille(c, p, cx, cy, ((p.heure % 12) + minutes / 60.0) / 12.0, r * 0.50f, r * 0.08f, h * 0.014f, k.encre, a)
        aiguille(c, p, cx, cy, minutes / 60.0, r * 0.78f, r * 0.08f, h * 0.009f, k.encre, a)
        aiguille(c, p, cx, cy, p.sec / 60.0, r * 0.86f, r * 0.18f, h * 0.0035f, k.accent, a)
        f.style = Paint.Style.FILL; f.color = k.accent; f.alpha = (a * 255).toInt()
        c.drawCircle(cx, cy, h * 0.013f, f)
    }

    /** L'heure en toutes lettres : l'heure à l'encre, les minutes à l'accent, le moment de la journée plus petit. */
    private fun mots(c: Canvas, w: Float, h: Float, p: Pinceaux, k: Couleurs, alpha: Float, p0: Float) {
        val m = p.mots
        val a = alpha * p0
        // Une taille pour toutes les grandes lignes : la plus longue tient dans 86 % de la largeur.
        var lMax = 0f; var n = 0
        val base = h * 0.13f
        for (i in 0..2) if (m[i].isNotEmpty()) { lMax = maxOf(lMax, largeur(p, m[i], base, p.fin)); n++ }
        if (n == 0) return
        val taille = if (lMax > w * 0.86f) base * (w * 0.86f / lMax) else base
        val pas = taille * 1.12f
        val petit = taille * 0.42f
        val total = pas * n + (if (m[3].isNotEmpty()) petit * 1.5f else 0f)
        var haut = h * 0.42f - total / 2 + (1f - lisser(p0)) * h * 0.02f
        for (i in 0..2) if (m[i].isNotEmpty()) {
            texte(c, p, m[i], w / 2, haut + taille * 0.85f, taille, if (i == 1) k.encre else k.accent, a, if (i == 1) p.epais else p.fin, Paint.Align.CENTER)
            haut += pas
        }
        if (m[3].isNotEmpty()) texte(c, p, m[3], w / 2, haut + petit * 0.35f + petit * 0.85f, petit, k.encre2, a, p.fin, Paint.Align.CENTER)
    }

    /**
     * Les palettes : une carte pour les heures, une pour les minutes. Quand la minute change, la moitié haute de
     * l'ancienne valeur se rabat (DUREE_BASCULE), puis la moitié basse de la nouvelle descend.
     */
    private fun flip(c: Canvas, w: Float, h: Float, p: Pinceaux, k: Couleurs, alpha: Float, p0: Float, murale: Long) {
        val a = alpha * p0
        // 1.91.2 : des cartes assez larges pour deux chiffres (« 10 », « 08 » débordaient des cartes de 0,86).
        val ch = minOf(h * 0.46f, w * 0.30f); val cw = ch * 1.04f; val g = ch * 0.10f
        val cy = h * 0.42f
        val t = ((murale - p.basculeA).toFloat() / DUREE_BASCULE).let { if (murale - p.basculeA < 0 || it >= 1f) 1f else it }
        palette(c, p, k, w / 2 - g / 2 - cw, cy, cw, ch, p.ahh, p.hh, t, a)
        palette(c, p, k, w / 2 + g / 2, cy, cw, ch, p.amm, p.mm, t, a)
        // Les deux points, à l'accent, entre les cartes.
        val f = p.forme
        f.style = Paint.Style.FILL; f.color = k.accent; f.alpha = (a * 230).toInt()
        c.drawCircle(w / 2, cy - ch * 0.14f, ch * 0.03f, f); c.drawCircle(w / 2, cy + ch * 0.14f, ch * 0.03f, f)
        // 12 h : le repère dans le coin bas de la carte des minutes.
        if (p.ampm.isNotEmpty()) texte(c, p, p.ampm, w / 2 + g / 2 + cw - ch * 0.06f, cy + ch / 2 - ch * 0.07f, ch * 0.10f, k.encre2, a, p.epais, Paint.Align.RIGHT)
    }

    private fun palette(c: Canvas, p: Pinceaux, k: Couleurs, x: Float, cy: Float, cw: Float, ch: Float, avant: String, apres: String, t: Float, a: Float) {
        if (t >= 1f || avant == apres) { demiPalette(c, p, k, x, cy, cw, ch, apres, true, 1f, a); demiPalette(c, p, k, x, cy, cw, ch, apres, false, 1f, a); return }
        // Derrière : la nouvelle valeur en haut, l'ancienne en bas (tant que le volet ne l'a pas recouverte).
        demiPalette(c, p, k, x, cy, cw, ch, apres, true, 1f, a)
        demiPalette(c, p, k, x, cy, cw, ch, avant, false, 1f, a)
        val e = lisser(t)
        if (e < 0.5f) demiPalette(c, p, k, x, cy, cw, ch, avant, true, 1f - e * 2f, a)
        else demiPalette(c, p, k, x, cy, cw, ch, apres, false, e * 2f - 1f, a)
    }

    /** Une moitié de palette (haute ou basse), écrasée verticalement vers la charnière par `echelle` (0..1). */
    private fun demiPalette(c: Canvas, p: Pinceaux, k: Couleurs, x: Float, cy: Float, cw: Float, ch: Float, v: String, hautMoitie: Boolean, echelle: Float, a: Float) {
        if (echelle <= 0.01f) return
        c.save()
        if (hautMoitie) c.clipRect(x, cy - ch / 2, x + cw, cy) else c.clipRect(x, cy, x + cw, cy + ch / 2)
        c.scale(1f, echelle, x + cw / 2, cy)
        val f = p.forme
        f.style = Paint.Style.FILL; f.color = k.carte; f.alpha = (a * 255).toInt()
        val r = ch * 0.08f
        p.rect.set(x, cy - ch / 2, x + cw, cy + ch / 2); c.drawRoundRect(p.rect, r, r, f)
        // Le volet qui tourne s'assombrit un peu, comme une palette qui quitte la lumière.
        if (echelle < 1f) { f.color = k.fond; f.alpha = (a * 140 * (1f - echelle)).toInt(); c.drawRoundRect(p.rect, r, r, f) }
        val taille = ch * 0.74f
        texte(c, p, v, x + cw / 2, cy + taille * 0.36f, taille, k.encre, a, p.epais, Paint.Align.CENTER)
        c.restore()
        // Une fine charnière au fond du thème.
        f.color = k.fond; f.alpha = (a * 255).toInt()
        p.rect.set(x, cy - ch * 0.006f, x + cw, cy + ch * 0.006f); c.drawRect(p.rect, f)
    }

    /** L'heure au-dessus des minutes, les minutes à l'accent, une barre de secondes sur le côté. */
    private fun empilee(c: Canvas, w: Float, h: Float, p: Pinceaux, k: Couleurs, alpha: Float, p0: Float) {
        val taille = h * 0.27f
        val a = alpha * p0
        texte(c, p, p.hh, w / 2, h * 0.38f, taille, k.encre, a, p.epais, Paint.Align.CENTER)
        texte(c, p, p.mm, w / 2, h * 0.62f, taille, k.accent, a, p.epais, Paint.Align.CENTER)
        val lMax = maxOf(largeur(p, p.hh, taille, p.epais), largeur(p, p.mm, taille, p.epais))
        val x = w / 2 + lMax / 2 + h * 0.04f; val haut = h * 0.20f; val bas = h * 0.62f; val e = h * 0.004f
        val f = p.forme
        f.style = Paint.Style.FILL
        f.color = k.encre2; f.alpha = (a * 50).toInt()
        p.rect.set(x - e, haut, x + e, bas); c.drawRoundRect(p.rect, e, e, f)
        f.color = k.or; f.alpha = (a * 230).toInt()
        p.rect.set(x - e, bas - (bas - haut) * (p.sec / 60f), x + e, bas); c.drawRoundRect(p.rect, e, e, f)
        // 12 h : le repère au pied de la barre des secondes.
        if (p.ampm.isNotEmpty()) texte(c, p, p.ampm, x, bas + taille * 0.30f, taille * 0.20f, k.encre2, a, p.epais, Paint.Align.CENTER)
    }
}

/**
 * La vignette animée d'un style d'horloge (réglages de la veille), et le grand aperçu du sélecteur : l'horloge réelle,
 * dans les couleurs du thème, tant qu'elle est à l'écran (et seulement là). Une image par seconde, sauf pendant
 * l'arrivée, le balayage d'une trotteuse ou la bascule des palettes (30 images par seconde).
 */
class VignetteHorloge(ctx: Context, style: String, theme: Theme) : View(ctx) {
    private val pinceaux = HorlogesVeille.Pinceaux()
    private val couleurs = HorlogesVeille.Couleurs(theme.encre, theme.encre2, theme.pourpre, theme.or, theme.carte or 0xFF000000.toInt(), theme.fond or 0xFF000000.toInt())
    private val fond = theme.fond or 0xFF000000.toInt()
    private var depart = SystemClock.uptimeMillis()

    /** Le style montré ; en changer rejoue l'arrivée de l'horloge (le grand aperçu suit le focus). */
    var style: String = style
        set(v) { if (v == field) return; field = v; depart = SystemClock.uptimeMillis(); relancer() }

    private val tic = object : Runnable {
        override fun run() {
            invalidate()
            if (!isAttachedToWindow || windowVisibility != VISIBLE) return
            val murale = System.currentTimeMillis()
            val vite = SystemClock.uptimeMillis() - depart < 900L || HorlogesVeille.bougeEnContinu(style, pinceaux, murale)
            // Sinon : juste après le changement de seconde, une seule image.
            postOnAnimationDelayed(this, if (vite) 33L else 1005L - murale % 1000L)
        }
    }

    init { isFocusable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    private fun relancer() { removeCallbacks(tic); if (isAttachedToWindow) post(tic) }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); relancer() }
    override fun onDetachedFromWindow() { removeCallbacks(tic); super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(v: Int) { super.onWindowVisibilityChanged(v); if (v == VISIBLE) relancer() else removeCallbacks(tic) }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        c.drawColor(fond)
        val p0 = ((SystemClock.uptimeMillis() - depart) / 900f).coerceIn(0f, 1f)
        HorlogesVeille.dessiner(c, style, w, h, pinceaux, couleurs, 1f, p0)
    }
}
