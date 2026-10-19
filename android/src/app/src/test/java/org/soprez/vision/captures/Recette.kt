// SPDX-License-Identifier: MIT
package org.soprez.vision.captures

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import com.github.takahirom.roborazzi.captureRoboImage
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * Recette calculée (version suivante, 17/10, demande d'essai : une recette en 30 à 60 min au lieu de plusieurs heures).
 *
 * Les écrans tournent sur la JVM (Robolectric, rendu natif : vraies polices, vraie mesure des textes) à chaque taille et
 * police de la recette ; chaque capture est vérifiée par [Debordements] (« … », lignes cachées, texte coupé, mot coupé en
 * deux, vue hors de son parent ou de l'écran, bouton − / + rogné ou déformé) et écrite en PNG avec un rapport par
 * taille et police, comme la recette sur émulateur (AntiDebordementTest) : recette-calc-<écran>-<taille>-<police>.png.
 *
 * Ce qui ne se calcule pas reste sur l'émulateur : rotation réelle, encoche, barres du système, clavier, télécommande,
 * services, réseau. Lancer : gradle :app:testDebugUnitTest -Pcaptures --tests "org.soprez.vision.captures.*"
 */
object Recette {
    /** Une taille de la recette : le nom (celui de recette-visuelle.sh) et les qualificatifs Robolectric. */
    data class Taille(val nom: String, val qualificatifs: String, val tele: Boolean = false) {
        override fun toString() = nom
    }

    /** Les tailles de la recette sur émulateur (outils/recette-visuelle.sh), en dp et densité. */
    val TAILLES = listOf(
        Taille("360dp", "w360dp-h780dp-port-xxhdpi"),
        Taille("412dp", "w412dp-h915dp-port-xxhdpi"),
        Taille("360dp-paysage", "w800dp-h360dp-land-xxhdpi"),
        Taille("tablette-portrait", "w800dp-h1280dp-port-xhdpi"),
        Taille("tablette-paysage", "w1280dp-h800dp-land-xhdpi"),
        Taille("tv-720p", "w960dp-h540dp-land-television-tvdpi", tele = true),
        Taille("tv-1080p", "w960dp-h540dp-land-television-xhdpi", tele = true),
    )
    val TELEPHONES_TABLETTES = TAILLES.filter { !it.tele }
    val POLICES = listOf(1.0f, 1.3f)
    val LANGUE: String = System.getProperty("captures.langue")?.takeIf { it.isNotBlank() } ?: "fr"

    /** Les combinaisons taille × police, pour les essais paramétrés. */
    fun combinaisons(tailles: List<Taille> = TAILLES): List<Array<Any>> =
        tailles.flatMap { t -> POLICES.map { p -> arrayOf<Any>(t, p) } }

    val dossier: File by lazy {
        File(System.getProperty("captures.dossier") ?: "build/captures-recette").apply { mkdirs() }
    }

    /** Pose la taille, la densité, l'orientation, le mode télé et la police avant de construire un écran. */
    fun poser(t: Taille, police: Float) {
        // La langue de la recette (comme recette-visuelle.sh : « -l fr ») ; captures.langue=de pour les textes longs.
        RuntimeEnvironment.setQualifiers(LANGUE + "-" + t.qualificatifs)
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag(LANGUE))  // dates et heures dans la langue de la recette
        RuntimeEnvironment.setFontScale(police)
        val ctx = RuntimeEnvironment.getApplication()
        val ui = ctx.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        shadowOf(ui).setCurrentModeType(if (t.tele) Configuration.UI_MODE_TYPE_TELEVISION else Configuration.UI_MODE_TYPE_NORMAL)
        shadowOf(ctx.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, t.tele)
    }

    fun police(p: Float) = "${(p * 100).toInt()}"

    /** Les défauts trouvés, par taille et police : un rapport texte chacun (comme la recette sur émulateur). */
    private val rapports = LinkedHashMap<String, MutableList<String>>()

    /**
     * Mesure, vérifie et capture [vue] (déjà attachée à une fenêtre, ou posée seule à [largeurPx] × [hauteurPx]).
     * Rend les défauts trouvés (aussi notés au rapport de la taille et de la police).
     */
    fun capturer(vue: View, ecran: String, t: Taille, police: Float, permis: (TextView) -> Boolean = { false }): List<String> {
        ShadowLooper.idleMainLooper()
        // Les vérifications portent sur toute la page (même ce qui est plus bas, hors de l'écran).
        val defauts = Debordements.verifier(vue, ecran, permis)
        // Les images, comme sur émulateur : ce que montre l'écran, puis une image par cran de défilement (-2, -3…).
        val fenetre = vue.rootView
        val defile = generateSequence(vue.parent) { it.parent }.filterIsInstance<ScrollView>().firstOrNull()
        defile?.scrollTo(0, 0)
        ShadowLooper.idleMainLooper()
        val image = File(dossier, "recette-calc-$ecran-${t.nom}-${police(police)}.png")
        // Une fenêtre de dialogue ne se capture pas par Roborazzi (Espresso ne la trouve pas) : on la dessine.
        try { fenetre.captureRoboImage(image.path) } catch (_: Throwable) { dessiner(fenetre, image) }
        if (defile != null) for (page in 2..8) {
            val avant = defile.scrollY
            defile.scrollBy(0, (defile.height * 0.85f).toInt())
            ShadowLooper.idleMainLooper()
            if (defile.scrollY == avant) break
            fenetre.captureRoboImage(File(dossier, "recette-calc-$ecran-$page-${t.nom}-${police(police)}.png").path)
        }
        defile?.scrollTo(0, 0)
        noter(t, police, defauts)
        return defauts
    }

    /** Dessine [v] dans une image (repli de Roborazzi, pour les fenêtres de dialogue). */
    fun dessiner(v: View, f: File) {
        if (v.width <= 0 || v.height <= 0) return
        val b = android.graphics.Bitmap.createBitmap(v.width, v.height, android.graphics.Bitmap.Config.ARGB_8888)
        v.draw(android.graphics.Canvas(b))
        f.outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        b.recycle()
    }

    @Synchronized fun noter(t: Taille, police: Float, defauts: List<String>) {
        val cle = "${t.nom}-${police(police)}"
        rapports.getOrPut(cle) { ArrayList() }.addAll(defauts)
        File(dossier, "recette-calc-rapport-$cle.txt").writeText(
            "Recette calculée — ${t.nom}, police ${police(police)} %\n" +
                (rapports[cle]!!.ifEmpty { listOf("Aucun défaut.") }).joinToString("\n") + "\n")
    }
}

/**
 * Les vérifications de la recette sur émulateur (AntiDebordementTest.verifier), sur une vue mesurée hors appareil.
 * Une vue dans un parent qui défile dans un sens n'est pas jugée dans ce sens (elle défile, elle n'est pas coupée).
 */
object Debordements {
    fun verifier(racine: View, ecran: String, permis: (TextView) -> Boolean = { false }): List<String> {
        val defauts = ArrayList<String>()
        val cadre = Rect(0, 0, racine.width, racine.height)
        parcourir(racine, racine, ecran, cadre, defauts, permis)
        return defauts.distinct()
    }

    /**
     * Les « … » admis, mêmes règles que la recette sur émulateur (AntiDebordementTest) :
     * - le texte entier est dit en description (nom venu de Home Assistant, règle de la recette 1.87.0) ;
     * - le nom d'une icône sur deux lignes, quand l'icône porte la description (grille, tiroir).
     */
    fun admis(tv: TextView): Boolean {
        val d = tv.contentDescription?.toString().orEmpty()
        val t = tv.text.toString().removePrefix("• ").removeSuffix("▾").trim()
        if (d.isNotEmpty() && t.isNotEmpty() && d.contains(t)) return true
        if (tv.maxLines == 2) {
            var p: Any? = tv.parent; var n = 0
            while (p is View && n < 3) { if (!p.contentDescription.isNullOrEmpty()) return true; p = p.parent; n++ }
        }
        return false
    }

    private fun decrire(v: View): String {
        val nom = (v.tag as? String)?.let { "[$it]" } ?: ""
        val texte = (v as? TextView)?.text?.toString()?.replace('\n', ' ')?.take(60) ?: v.contentDescription?.toString()?.take(60).orEmpty()
        return "${v.javaClass.simpleName}$nom" + if (texte.isNotEmpty()) " « $texte »" else ""
    }

    private fun defile(v: View, enLargeur: Boolean): Boolean {
        var p = v.parent
        while (p is View) {
            if (enLargeur && (p is HorizontalScrollView || p.canScrollHorizontally(1) || p.canScrollHorizontally(-1))) return true
            if (!enLargeur && (p is ScrollView || p is android.widget.AbsListView || p.canScrollVertically(1) || p.canScrollVertically(-1))) return true
            p = p.parent
        }
        return false
    }

    private fun position(v: View, racine: View): Rect {
        var x = 0; var y = 0; var c: View = v
        while (c !== racine) {
            x += c.left - ((c.parent as? View)?.scrollX ?: 0)
            y += c.top - ((c.parent as? View)?.scrollY ?: 0)
            c = c.parent as? View ?: break
        }
        return Rect(x, y, x + v.width, y + v.height)
    }

    private fun parcourir(v: View, racine: View, ecran: String, cadre: Rect, defauts: MutableList<String>, permis: (TextView) -> Boolean) {
        if (v.visibility != View.VISIBLE || v.alpha == 0f || v.width <= 0 || v.height <= 0) return
        val ou = "$ecran · ${decrire(v)}"
        val enLargeur = defile(v, true)
        val enHauteur = defile(v, false)
        // 1. Texte : « … », lignes cachées, texte coupé en bas, mot coupé en deux.
        if (v is TextView && v !is EditText && v.text.isNotEmpty()) {
            val l = v.layout
            if (l != null) {
                val ellipse = (0 until l.lineCount).any { l.getEllipsisCount(it) > 0 }
                if (ellipse && !permis(v) && !admis(v)) defauts += "$ou : texte ellipsé (…)"
                val visibles = minOf(l.lineCount, v.maxLines.takeIf { it > 0 } ?: Int.MAX_VALUE)
                if (visibles < l.lineCount && !permis(v) && !admis(v)) defauts += "$ou : lignes coupées (${l.lineCount} nécessaires, $visibles affichées)"
                val utile = v.height - v.totalPaddingTop - v.totalPaddingBottom
                if (!ellipse && visibles > 0 && l.getLineBottom(visibles - 1) > utile + 2)
                    defauts += "$ou : texte coupé en bas (${l.getLineBottom(visibles - 1)} px de lignes pour $utile px)"
                val t = v.text
                for (i in 0 until l.lineCount - 1) {
                    val fin = l.getLineEnd(i)
                    if (fin in 1 until t.length && t[fin - 1].isLetter() && t[fin].isLetter()) { defauts += "$ou : mot coupé en deux (ligne ${i + 1})"; break }
                }
            }
        }
        // 2. Hors de son parent (sauf s'il défile dans ce sens).
        val parent = v.parent as? ViewGroup
        if (parent != null && v !== racine) {
            // 1.93.06 : une vue RÉDUITE par une échelle (widgets de Vision agrandis ou réduits pour la télé, Agrandi) est
            // jugée sur ce qu'elle occupe une fois réduite, pas sur sa mise en page d'origine.
            val reduite = v.scaleX < 1f || v.scaleY < 1f
            val g = if (reduite) Math.round(v.left + v.translationX + v.pivotX * (1 - v.scaleX)) else v.left
            val d = if (reduite) Math.round(g + v.width * v.scaleX) else v.right
            val h = if (reduite) Math.round(v.top + v.translationY + v.pivotY * (1 - v.scaleY)) else v.top
            val b = if (reduite) Math.round(h + v.height * v.scaleY) else v.bottom
            if (!enLargeur && (g < -2 || d > parent.width + 2))
                defauts += "$ou : sort de son parent ${parent.javaClass.simpleName} ($g..$d sur ${parent.width} px)"
            if (!enHauteur && (h < -2 || b > parent.height + 2))
                defauts += "$ou : sort de son parent ${parent.javaClass.simpleName} en hauteur ($h..$b sur ${parent.height} px)"
        }
        // 3. Hors de l'écran.
        if (v !== racine) {
            val r = position(v, racine)
            if (!enLargeur && (r.left < cadre.left - 1 || r.right > cadre.right + 1)) defauts += "$ou : sort de l'écran (${r.left}..${r.right}, écran ${cadre.left}..${cadre.right})"
            if (!enHauteur && (r.top < cadre.top - 1 || r.bottom > cadre.bottom + 1)) defauts += "$ou : sort de l'écran en hauteur (${r.top}..${r.bottom}, écran ${cadre.top}..${cadre.bottom})"
        }
        // 4. Les boutons − / + d'un widget : entiers (dans tous leurs parents, marges comprises) et ronds.
        if (v.tag == "widget_moins" || v.tag == "widget_plus") {
            var c: View = v; var x = 0; var y = 0
            while (c !== racine) {
                x += c.left; y += c.top
                val p = c.parent as? ViewGroup ?: break
                if (x < p.paddingLeft - 1 || y < p.paddingTop - 1 || x + v.width > p.width - p.paddingRight + 1 || y + v.height > p.height - p.paddingBottom + 1) {
                    defauts += "$ou : bouton rogné"; break
                }
                if (p.javaClass.simpleName == "VueWidgetHa") break
                c = p; x = 0; y = 0
            }
            if (Math.abs(v.width - v.height) > 2) defauts += "$ou : bouton déformé (${v.width}×${v.height} px)"
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) parcourir(v.getChildAt(i), racine, ecran, cadre, defauts, permis)
    }

    /** Un texte visible contenant [t] sous [v]. */
    fun texteVisible(v: View, t: String): Boolean {
        if (v.visibility != View.VISIBLE) return false
        if (v is TextView && v.text.toString().contains(t)) return true
        return v is ViewGroup && (0 until v.childCount).any { texteVisible(v.getChildAt(it), t) }
    }

    /**
     * 1.93.06 (retour d'essai) : la zone où tout doit tenir sur une télé de [l] × [h] px : le bord réglé (« Ajuster l'écran ») plus la
     * marge de confort, depuis chaque bord de l'écran.
     */
    fun zoneTele(ctx: Context, l: Int, h: Int): Rect {
        val z = org.soprez.vision.ZoneSure.zone(ctx)
        return Rect(z.left, z.top, l - z.right, h - z.bottom)
    }

    /**
     * 1.93.06 : ce qui sort de [zone] sous [v] (textes, images, commandes ; à l'écran, après défilement et échelle). Les
     * fonds plein écran n'y sont pas soumis ; une vue coupée par sa liste défilante n'est jugée que sur sa partie visible.
     */
    fun horsZone(v: View, zone: Rect, sortie: MutableList<String>, ecran: Rect = zone, tolerance: Int = 2) {
        if (v.visibility != View.VISIBLE || v.alpha == 0f || v.width <= 0 || v.height <= 0) return
        val p = IntArray(2).also { v.getLocationOnScreen(it) }
        // L'échelle des parents (focus agrandi, panneau réduit) : la vue est jugée à sa taille dessinée.
        var kx = 1f; var ky = 1f
        var c: View? = v
        while (c != null) { kx *= c.scaleX; ky *= c.scaleY; c = c.parent as? View }
        val r = Rect(p[0], p[1], p[0] + (v.width * kx).toInt(), p[1] + (v.height * ky).toInt())
        for (sv in generateSequence(v.parent) { it.parent }.filterIsInstance<View>().filter { it is ScrollView || it is HorizontalScrollView || it is android.widget.AbsListView || it.javaClass.simpleName.contains("RecyclerView") }) {
            val q = IntArray(2).also { sv.getLocationOnScreen(it) }
            // Une liste qui découpe à sa marge intérieure (clipToPadding) ne montre rien dans cette marge.
            val g = sv as android.view.ViewGroup
            val (pg, ph, pd, pb) = if (g.clipToPadding) listOf(g.paddingLeft, g.paddingTop, g.paddingRight, g.paddingBottom) else listOf(0, 0, 0, 0)
            if (!r.intersect(q[0] + pg, q[1] + ph, q[0] + sv.width - pd, q[1] + sv.height - pb)) return
        }
        val plein = r.width() >= ecran.width() * 0.9f && r.height() >= ecran.height() * 0.9f
        val contenu = v is TextView || v is android.widget.ImageView || (v !is ViewGroup && (v.isFocusable || v.isClickable)) ||
            (v is ViewGroup && v.isClickable && v !is ScrollView)
        if (contenu && !plein && !(r.left >= zone.left - tolerance && r.top >= zone.top - tolerance && r.right <= zone.right + tolerance && r.bottom <= zone.bottom + tolerance)) {
            val liste = generateSequence(v.parent) { it.parent }.filterIsInstance<ScrollView>().firstOrNull()?.let { sv ->
                val q = IntArray(2).also { sv.getLocationOnScreen(it) }
                " ; dans une liste ${q[0]},${q[1]} ${sv.width}×${sv.height}, marges ${sv.paddingTop}/${sv.paddingBottom}, découpe ${sv.clipToPadding}"
            } ?: ""
            sortie += "${v.javaClass.simpleName}${(v.tag as? String)?.let { "[$it]" } ?: ""}${(v as? TextView)?.text?.toString()?.take(30)?.let { " « $it »" } ?: ""} sort de la zone (${r.toShortString()}, zone ${zone.toShortString()}$liste)"
            return
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) horsZone(v.getChildAt(i), zone, sortie, ecran, tolerance)
    }
}
