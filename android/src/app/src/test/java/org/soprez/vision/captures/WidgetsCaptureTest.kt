// SPDX-License-Identifier: MIT
package org.soprez.vision.captures

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import org.soprez.vision.Accueil
import org.soprez.vision.Local
import org.soprez.vision.Palette
import org.soprez.vision.Ui
import org.soprez.vision.VueWidgetHa
import org.soprez.vision.WidgetsHa
import org.soprez.vision.WidgetsHaStock

/**
 * Recette calculée des widgets (17/10), à toutes les tailles (télé comprise) et polices 100 et 130 % :
 * - « widgets-chauffage » : le réglage « Consigne » et la courbe de température en 1×1, 2×1, 3×1, 4×1, 2×2 et 4×2
 *   (les cases de l'accueil : un quart de la largeur, 112 dp au plus en paysage) ;
 * - « widgets-ha » : les widgets d'essai du catalogue (liste longue, devoirs, réglage, liste de choix, bouton).
 * Mêmes données que la recette sur émulateur (catalogue de AntiDebordementTest, gardé dans captures/catalogue_widgets.json).
 * Vérifié en plus : « Consigne » en entier, la courbe sur toute la largeur et tracée aux deux bords ; seul le titre d'une
 * courbe d'une case de large (1×1) peut prendre des « … ».
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class WidgetsCaptureTest(private val taille: Recette.Taille, private val police: Float) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun parametres(): List<Array<Any>> = Recette.combinaisons()

        val TAILLES_CHAUFFAGE = listOf(1 to 1, 2 to 1, 3 to 1, 4 to 1, 2 to 2, 4 to 2)
    }

    private fun hote(): Activity {
        Recette.poser(taille, police)
        val a = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val catalogue = javaClass.getResource("/captures/catalogue_widgets.json")!!.readText()
        WidgetsHaStock.garderCatalogue(a, catalogue)
        return a
    }

    /** La colonne de widgets, à la taille des cases de l'accueil, posée dans une page qui défile. */
    private fun planche(a: Activity, remplir: (LinearLayout, Int, Int) -> Unit): LinearLayout {
        val th = Local.themeEffectif(a, Palette.LANCEUR)
        val dm = a.resources.displayMetrics
        val marge = Ui.dp(a, 16f)
        val l = ((dm.widthPixels - 2 * marge) / Accueil.COLONNES).let { if (dm.widthPixels > dm.heightPixels) minOf(it, Ui.dp(a, 112f)) else it }
        val h = (l * 1.14f).toInt()
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(marge, marge, marge, marge); setBackgroundColor(th.fond) }
        a.setContentView(ScrollView(a).apply { setBackgroundColor(th.fond); addView(col) })
        remplir(col, l, h)
        ShadowLooper.idleMainLooper()
        // Les widgets se refont à leur taille réelle (onSizeChanged) : une passe de plus.
        ShadowLooper.idleMainLooper()
        return col
    }

    private fun parametres(l: Int, h: Int, w: Int, hh: Int, a: Activity) =
        LinearLayout.LayoutParams(l * w, h * hh).apply { bottomMargin = Ui.dp(a, 10f) }

    private fun ancetreTag(v: View, tag: String): Boolean {
        var p: Any? = v
        while (p is View) { if (p.tag == tag) return true; p = p.parent }
        return false
    }

    private fun points(tv: TextView): Boolean = tv.layout?.let { l -> (0 until l.lineCount).any { l.getEllipsisCount(it) > 0 } } == true

    private fun vues(v: View, l: ArrayList<View> = ArrayList()): ArrayList<View> {
        l.add(v); if (v is ViewGroup) for (i in 0 until v.childCount) vues(v.getChildAt(i), l); return l
    }

    @Test fun chauffage() {
        val a = hote()
        val th = Local.themeEffectif(a, Palette.LANCEUR)
        val fin = System.currentTimeMillis()
        // Les 9 dernières heures seulement, une mesure toutes les 30 min, la dernière il y a 2 h (courbe partielle).
        val points = (0 until 14).map { k -> WidgetsHa.Point(fin - 9 * 3_600_000L + k * 1_800_000L, 19.0 + (k % 5) * 0.4) }
        val col = planche(a) { c, l, h ->
            for ((w, hh) in TAILLES_CHAUFFAGE) {
                val consigne = WidgetsHa.Reglage("ch-$w-$hh", listOf("input_number.consigne"), WidgetsHa.REGLAGE, w, hh, libelle = "Consigne")
                c.addView(VueWidgetHa(a, th, consigne, lectureSeule = false, agir = { _, _ -> }, details = { _, _ -> }, agirAvec = { _, _, _ -> })
                    .apply { tag = "essai_consigne_${w}x$hh" }, parametres(l, h, w, hh, a))
                val courbe = WidgetsHa.Reglage("co-$w-$hh", listOf("input_number.consigne"), WidgetsHa.GRAPHIQUE, w, hh, libelle = "Température ambiante")
                c.addView(VueWidgetHa(a, th, courbe, lectureSeule = false, historiqueInitial = WidgetsHa.Historique.Nombres("°C", points))
                    .apply { tag = "essai_courbe_${w}x$hh" }, parametres(l, h, w, hh, a))
            }
        }
        val defauts = ArrayList(Recette.capturer(col, "widgets-chauffage", taille, police) { tv -> ancetreTag(tv, "essai_courbe_1x1") })
        val dejaNotes = defauts.size
        for ((w, hh) in TAILLES_CHAUFFAGE) {
            val t = col.findViewWithTag<View>("essai_consigne_${w}x$hh")
            val lib = vues(t).filterIsInstance<TextView>().firstOrNull { it.text.toString() == "Consigne" }
            if (lib == null || lib.width <= 0) defauts += "chauffage ${w}×$hh : le libellé « Consigne » ne s'affiche pas"
            else if (points(lib)) defauts += "chauffage ${w}×$hh : « Consigne » coupé"
            // 17-10 (widget chauffage lisible) : la ligne sous la valeur est là, entière : ni « … », ni lignes cachées en
            // bas (texte plus haut que sa vue), ni débordant du widget.
            val ligne = t.findViewWithTag<TextView>("widget_texte")
            if (w <= 1 && hh < 2) { if (ligne != null) defauts += "chauffage 1×1 : la ligne n'y tient pas, elle ne doit pas s'afficher" }
            else if (ligne == null || ligne.width <= 0 || ligne.height <= 0) {
                // Une case de haut, trop étroit pour la forme courte entre − et + (téléphone en 2 × 1) : pas de ligne, mais dite.
                if (!(hh < 2 && t.contentDescription?.toString()?.contains("Réduit") == true)) defauts += "chauffage ${w}×$hh : la ligne sous la valeur ne s'affiche pas"
            }
            else {
                val lay = ligne.layout
                if (points(ligne)) defauts += "chauffage ${w}×$hh : ligne sous la valeur coupée (…)"
                if (lay != null && lay.height > ligne.height - ligne.paddingTop - ligne.paddingBottom + 2) defauts += "chauffage ${w}×$hh : ligne sous la valeur coupée en bas (${lay.height} px de texte pour ${ligne.height})"
                val pl = IntArray(2).also { ligne.getLocationOnScreen(it) }; val pw = IntArray(2).also { t.getLocationOnScreen(it) }
                if (pl[1] + ligne.height > pw[1] + t.height - t.paddingBottom + 2) defauts += "chauffage ${w}×$hh : ligne sous la valeur hors du widget"
                if (w <= 1 && ligne.text.toString() != WidgetsHa.texteCourt(ligne.contentDescription.toString())) defauts += "chauffage ${w}×$hh : pas la forme courte en une case de large"
            }
            val c = col.findViewWithTag<View>("essai_courbe_${w}x$hh")
            // Le titre d'une courbe : en entier (rapetissé s'il le faut) dès 2 cases de large — jamais « … », même dit en entier.
            if (w >= 2) vues(c).filterIsInstance<TextView>().firstOrNull { it.text.toString() == "Température ambiante" }?.let { tt ->
                if (points(tt)) defauts += "courbe ${w}×$hh : « Température ambiante » coupé" }
            val dessin = vues(c).firstOrNull { it.javaClass.simpleName == "Courbe" }
            if (dessin == null) { defauts += "courbe ${w}×$hh : pas de courbe"; continue }
            val parent = dessin.parent as View
            val utile = parent.width - parent.paddingLeft - parent.paddingRight
            if (dessin.width < utile * 0.95f) defauts += "courbe ${w}×$hh : ${dessin.width} px sur $utile de large"
            if (dessin.width <= 0 || dessin.height <= 0) continue
            val img = Bitmap.createBitmap(dessin.width, dessin.height, Bitmap.Config.ARGB_8888)
            dessin.draw(Canvas(img))
            fun encre(x: Int) = (0 until img.height).any { y -> val p = img.getPixel(x, y); Color.alpha(p) > 200 &&
                Math.abs(Color.red(p) - Color.red(th.or)) < 30 && Math.abs(Color.green(p) - Color.green(th.or)) < 30 && Math.abs(Color.blue(p) - Color.blue(th.or)) < 30 }
            val bord = (img.width * 0.04f).toInt().coerceAtLeast(2)
            // 1.93.06 : l'échelle des valeurs prend la gauche (au plus 40 %) ; la courbe part du bord de la zone de tracé.
            val premier = (0 until img.width).firstOrNull { encre(it) }
            if (premier == null || premier > img.width * 0.40f + bord) defauts += "courbe ${w}×$hh : rien de tracé au bord gauche"
            if (!((img.width - bord) until img.width).any { encre(it) }) defauts += "courbe ${w}×$hh : rien de tracé au bord droit"
        }
        Recette.noter(taille, police, defauts.drop(dejaNotes))
        assertEquals("widgets-chauffage (${taille.nom}, police ${Recette.police(police)} %) :\n" + defauts.joinToString("\n"), emptyList<String>(), defauts)
    }

    @Test fun catalogue_ha() {
        val a = hote()
        val th = Local.themeEffectif(a, Palette.LANCEUR)
        val col = planche(a) { c, l, h ->
            listOf(
                WidgetsHa.Reglage("p1", listOf("todo.devoirs"), WidgetsHa.TUILE, 2, 2, affichage = WidgetsHa.AFF_VALEUR_DETAIL),
                WidgetsHa.Reglage("p2", listOf("todo.devoirs"), WidgetsHa.TUILE, 4, 1, affichage = WidgetsHa.AFF_DETAIL),
                WidgetsHa.Reglage("p3", listOf("input_number.consigne"), WidgetsHa.REGLAGE, 2, 1),
                WidgetsHa.Reglage("p4", listOf("select.mode"), WidgetsHa.REGLAGE, 2, 1),
                WidgetsHa.Reglage("p5", listOf("script.nuit"), WidgetsHa.BOUTON, 1, 1),
                WidgetsHa.Reglage("p6", listOf("sensor.liste_longue"), WidgetsHa.TUILE, 2, 2, affichage = WidgetsHa.AFF_DETAIL),
                WidgetsHa.Reglage("p7", listOf("sensor.liste_longue"), WidgetsHa.TUILE, 4, 3, affichage = WidgetsHa.AFF_VALEUR_DETAIL),
            ).forEach { r ->
                c.addView(VueWidgetHa(a, th, r, lectureSeule = false, agir = { _, _ -> }, details = { _, _ -> }, agirAvec = { _, _, _ -> }),
                    parametres(l, h, r.w, r.h, a))
            }
        }
        // Comme la recette sur émulateur : un nom venu de Home Assistant peut prendre des « … » s'il est dit en entier
        // (Debordements.admis) ; le détail d'une liste s'arrête à ce qui tient, puis « +N ».
        val defauts = Recette.capturer(col, "widgets-ha", taille, police)
        assertEquals("widgets-ha (${taille.nom}, police ${Recette.police(police)} %) :\n" + defauts.joinToString("\n"), emptyList<String>(), defauts)
    }
}
