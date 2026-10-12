// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * 1.79.0 (point 13 bis, validé en essai) : l'habillage de l'écran verrouillé des téléphones et tablettes. Le verrou
 * d'Android reste ; Vision n'en change que le fond : aux couleurs du thème, le prénom, et l'état du moment (ouvert,
 * pause, temps écoulé, plage fermée), redessiné quand l'état change.
 *
 * Limites d'Android (dites à l'auteur, acceptées) : lire le fond actuel pour le sauvegarder est réservé au système
 * (Android 14). « Restaurer » efface donc le fond de verrouillage posé par Vision (clear(FLAG_LOCK)) : l'écran
 * verrouillé reprend le fond d'accueil. Couper l'option ou désinstaller depuis Vision restaure ; une
 * désinstallation depuis les réglages d'Android ne peut rien exécuter (le fond Vision reste).
 */
object EcranVerrou {
    enum class Moment { OUVERT, PAUSE, TEMPS, PLAGE, HORS_LIGNE }

    private const val CLE = "verrou_habille"
    private const val CLE_DETAIL = "verrou_detail"
    @Volatile private var dernier = ""

    fun possible(ctx: Context): Boolean = Build.VERSION.SDK_INT >= 24 && !ReglagesTvActivity.estTele(ctx)
    fun voulu(ctx: Context): Boolean = prefs(ctx).getBoolean(CLE, false)
    /** Le détail du statut (message de fermeture) sur l'écran verrouillé ; sinon le titre seulement. */
    fun detailVisible(ctx: Context): Boolean = prefs(ctx).getBoolean(CLE_DETAIL, false)
    fun poserDetail(ctx: Context, oui: Boolean) = prefs(ctx).edit().putBoolean(CLE_DETAIL, oui).apply()
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("local", Context.MODE_PRIVATE)

    /**
     * Mots qui, dans la raison de fermeture envoyée par Home Assistant (dans la langue de l'appareil),
     * désignent le temps d'écran épuisé plutôt qu'une plage horaire. Ce ne sont pas des textes affichés :
     * des motifs de reconnaissance, un jeu par langue (« Zeit » seul est évité : il est aussi dans « Zeitfenster »).
     */
    private val MOTS_TEMPS = listOf(
        "temps", "limite", "écoulé",                          // fr
        "screen time", "limit", "time's up", "time is up",     // en
        "Bildschirmzeit", "Limit", "abgelaufen",               // de
        "tiempo", "límite", "agotado",                         // es
        "tempo", "limite", "scaduto", "esaurito",              // it
        "schermtijd", "limiet",                                // nl
    )

    /** Logique pure : l'état à montrer. */
    fun etat(horsLigne: Boolean, ferme: Boolean, pauseFin: Long, raison: String, maintenant: Long): Moment = when {
        horsLigne -> Moment.HORS_LIGNE
        pauseFin > maintenant -> Moment.PAUSE
        ferme && MOTS_TEMPS.any { raison.contains(it, true) } -> Moment.TEMPS
        ferme -> Moment.PLAGE
        else -> Moment.OUVERT
    }

    fun activer(ctx: Context, oui: Boolean) {
        prefs(ctx).edit().putBoolean(CLE, oui).apply()
        dernier = ""
        Thread { if (oui) maj(ctx, force = true) else restaurer(ctx) }.start()
    }

    /** Le fond de verrouillage posé par Vision s'efface : l'écran verrouillé reprend le fond d'accueil. */
    fun restaurer(ctx: Context) {
        if (Build.VERSION.SDK_INT < 24) return
        try { WallpaperManager.getInstance(ctx).clear(WallpaperManager.FLAG_LOCK) } catch (_: Exception) {}
        dernier = ""
    }

    /** Appelé à chaque mise à jour du statut : ne redessine que si ce qui est montré change. */
    fun maj(ctx: Context, force: Boolean = false) {
        if (!possible(ctx) || !voulu(ctx) || !Config(ctx).inscrit) return
        val (titre, texte) = StatutNotif.textes(Etat.horsLigne(), Etat.verrouilleEffectif(), Config(ctx).parentJusqua,
            Etat.prochainChangement, Etat.message, System.currentTimeMillis()) { Etat.heure(it) }
        val e = etat(Etat.horsLigne(), Etat.verrouilleEffectif(), Config(ctx).parentJusqua, Etat.message, System.currentTimeMillis())
        val theme = Local.themeEffectif(ctx, Palette.LANCEUR)
        // « Ouvert · encore 1 h 20 » change chaque minute : sur le fond, l'heure de fermeture seulement (pas le décompte).
        // Le séparateur « · » est le même dans toutes les langues (statut_ouvert_encore, lot C1).
        val titreFond = if (e == Moment.OUVERT) titre.substringBefore(" · ") else titre
        val cle = "$e|$titreFond|${theme.nom}|${Config(ctx).utilisateur}"
        if (!force && cle == dernier) return
        dernier = cle
        try {
            val (w, h) = taille(ctx)
            val img = dessiner(w, h, theme, prenom(ctx), e, titreFond, if (e == Moment.OUVERT) texte else "")
            WallpaperManager.getInstance(ctx).setBitmap(img, null, true, WallpaperManager.FLAG_LOCK)
        } catch (_: Exception) { dernier = "" }
    }

    private fun prenom(ctx: Context): String {
        val cfg = Config(ctx)
        return cfg.utilisateur.ifEmpty { cfg.nom }
    }

    private fun taille(ctx: Context): Pair<Int, Int> {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION") (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        // Toujours en portrait (l'écran verrouillé d'un téléphone l'est).
        return minOf(dm.widthPixels, dm.heightPixels).coerceAtLeast(720) to maxOf(dm.widthPixels, dm.heightPixels).coerceAtLeast(1280)
    }

    /** La couleur de l'état : or (ouvert), bleu (pause), orange (temps écoulé), pourpre (plage), gris (hors ligne). */
    fun couleur(e: Moment, t: Theme): Int = when (e) {
        Moment.OUVERT -> t.or
        Moment.PAUSE -> if (t.sombre) 0xFF7FB8FF.toInt() else 0xFF2F6FB5.toInt()
        Moment.TEMPS -> if (t.sombre) 0xFFF2A65A.toInt() else 0xFFB25E12.toInt()
        Moment.PLAGE -> t.pourpre
        Moment.HORS_LIGNE -> t.encre3
    }

    /** Dessin pur (testé) : fond du thème, halos, le prénom, l'état ; le haut reste libre pour l'horloge d'Android. */
    fun dessiner(w: Int, h: Int, t: Theme, prenom: String, e: Moment, titre: String, detail: String): Bitmap {
        val img = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(img)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), t.fond, Themes.melanger(t.fond, t.carte, 0.55f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        val accent = couleur(e, t)
        // Halos doux du thème, et celui de l'état en bas.
        t.halos.forEachIndexed { i, halo ->
            val cx = w * (0.15f + 0.35f * i); val cy = h * (0.25f + 0.2f * (i % 2)); val r = w * 0.55f
            p.shader = RadialGradient(cx, cy, r, (0x33000000 or (halo and 0xFFFFFF)), 0, Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, r, p)
        }
        p.shader = RadialGradient(w * 0.5f, h * 0.78f, w * 0.75f, (0x55000000 or (accent and 0xFFFFFF)), 0, Shader.TileMode.CLAMP)
        c.drawCircle(w * 0.5f, h * 0.78f, w * 0.75f, p)
        p.shader = null
        val marge = w * 0.08f
        val largeur = (w - 2 * marge).toInt()
        fun texte(s: String, taille: Float, couleur: Int, gras: Boolean, y: Float): Float {
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = taille; color = couleur; typeface = Typeface.create(Typeface.SANS_SERIF, if (gras) Typeface.BOLD else Typeface.NORMAL)
            }
            @Suppress("DEPRECATION") val l = StaticLayout(s, tp, largeur, Layout.Alignment.ALIGN_CENTER, 1.1f, 0f, false)
            c.save(); c.translate(marge, y); l.draw(c); c.restore()
            return y + l.height
        }
        // Le bas de l'écran, au-dessus des raccourcis d'Android : prénom, pastille d'état, détail.
        var y = h * 0.56f
        if (prenom.isNotEmpty()) y = texte(prenom, w * 0.085f, t.encre, true, y) + w * 0.03f
        p.color = accent
        val r = w * 0.022f
        c.drawCircle(w / 2f, y + r, r, p)
        y += 2 * r + w * 0.03f
        y = texte(titre, w * 0.06f, accent, true, y) + w * 0.02f
        if (detail.isNotEmpty()) texte(detail, w * 0.04f, t.encre2, false, y)
        // La marque, discrète, tout en bas.
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = w * 0.032f; color = t.encre3; letterSpacing = 0.3f; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
        c.drawText("VISION", w / 2f, h * 0.925f, tp)
        return img
    }
}
