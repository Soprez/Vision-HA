// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.test.InstrumentationTestCase
import android.view.KeyEvent
import android.view.View

/**
 * 1.90.1 (l'auteur : « j'avais pas toutes les infos, ça me mettait +24 ») : le détail d'un widget défile.
 * - widget d'écran d'accueil : toutes les lignes reçues dans la liste, jamais « +N » ; la tuile est coupée au-dessus de
 *   la zone du détail ;
 * - tuile : le nombre de lignes suit la taille ; sur la télé, haut et bas font défiler quand la tuile a le focus.
 * Catalogue d'essai : AntiDebordementTest.CATALOGUE_WIDGETS (« sensor.liste_longue », 27 lignes).
 *
 *   adb shell am instrument -w -e class org.soprez.vision.WidgetListeTest org.soprez.vision.test/android.test.InstrumentationTestRunner
 */
class WidgetListeTest : InstrumentationTestCase() {
    private val ctx get() = instrumentation.targetContext
    // 1.91.2 : le défilement coupé (ce qui tient, puis « Voir tout »), hauteur grande pour que la place décide seule ;
    // le défaut (défilement) est essayé par WidgetDefilementEcranTest.
    private val reglage = WidgetsHa.Reglage("liste", listOf("sensor.liste_longue"), WidgetsHa.TUILE, 4, 3, affichage = WidgetsHa.AFF_DETAIL,
        defilement = false, hauteur = WidgetsHa.HAUTEUR_GRANDE)

    override fun setUp() {
        super.setUp()
        WidgetsHaStock.garderCatalogue(ctx, AntiDebordementTest.CATALOGUE_WIDGETS)
        FluxWidgets.etats.clear()
    }

    fun testEcranDAccueilToutesLesLignes() {
        val id = 990_001
        WidgetsSysteme.ecrire(ctx, id, reglage)
        try {
            assertTrue(WidgetsSysteme.listeDefilante(ctx, reglage, 300))
            assertFalse("trop bas : présentation compacte", WidgetsSysteme.listeDefilante(ctx, reglage, 80))
            val lignes = WidgetsSysteme.lignesDetail(ctx, id)
            assertEquals(27, lignes.size)
            assertTrue(lignes.first().startsWith("• Élément 1 "))
            assertTrue(lignes.last().startsWith("• Élément 27 "))
            assertFalse("jamais « +N » dans la liste", lignes.any { it.startsWith("+") })
            var coupe: Pair<android.graphics.Bitmap, Int>? = null
            instrumentation.runOnMainSync { coupe = WidgetsSysteme.imageSansDetail(ctx, reglage, 360, 300) }
            val (image, y) = coupe!!
            val d = ctx.resources.displayMetrics.density
            assertTrue("la zone du détail commence sous l'en-tête ($y px)", y > 20 * d && y < image.height - 24 * d)
        } finally { WidgetsSysteme.oublier(ctx, id) }
    }

    fun testTuileSelonLaPlaceEtTelecommande() {
        var petite = 0; var grande = 0; var consomme = false; var debut = -1
        instrumentation.runOnMainSync {
            val th = Local.themeEffectif(ctx, Palette.LANCEUR)
            fun lignesVisibles(hDp: Int): Pair<VueWidgetHa, Int> {
                val v = VueWidgetHa(ctx, th, reglage, lectureSeule = true)
                val d = ctx.resources.displayMetrics.density
                val sw = View.MeasureSpec.makeMeasureSpec((340 * d).toInt(), View.MeasureSpec.EXACTLY)
                val sh = View.MeasureSpec.makeMeasureSpec((hDp * d).toInt(), View.MeasureSpec.EXACTLY)
                v.measure(sw, sh); v.layout(0, 0, (340 * d).toInt(), (hDp * d).toInt())
                v.maj(); v.measure(sw, sh); v.layout(0, 0, (340 * d).toInt(), (hDp * d).toInt())
                val l = liste(v)
                return v to (l?.montrees ?: 0)
            }
            // 1.91.2 : la hauteur maximale (10 lignes) borne la grande tuile ; la petite reste sous ce plafond.
            val (vPetite, nPetite) = lignesVisibles(100)
            petite = nPetite
            grande = lignesVisibles(420).second
            // La petite tuile ne montre pas tout : c'est là que la télécommande fait défiler.
            consomme = vPetite.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
            debut = liste(vPetite)?.debut ?: -1
        }
        assertTrue("au moins une ligne en petit ($petite)", petite >= 1)
        assertTrue("plus de place, plus de lignes ($petite → $grande)", grande > petite)
        if (ReglagesTvActivity.estTele(ctx)) {
            assertTrue("la télécommande fait défiler", consomme)
            assertEquals(1, debut)
        } else {
            assertFalse("hors télé, la touche n'est pas prise", consomme)
            assertEquals(0, debut)
        }
    }

    private fun liste(v: VueWidgetHa): ListeDetail? {
        val f = VueWidgetHa::class.java.getDeclaredField("listeDetail"); f.isAccessible = true
        return f.get(v) as? ListeDetail
    }
}
