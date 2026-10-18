// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 16-10 (retour d'essai) : finitions des widgets du téléphone (courbe sur toute la largeur, libellé du thermostat jamais retiré) et
 * parité des widgets entre téléphone, tablette et télé.
 */
class WidgetsParite1016Test {
    private val src = listOf(File("src/main/java/org/soprez/vision"), File("app/src/main/java/org/soprez/vision")).first { it.isDirectory }
    private fun lire(n: String) = File(src, n).readText()
    private fun p(ts: Long, v: Double) = WidgetsHa.Point(ts, v)

    /** La courbe prend toute la largeur : la dernière valeur tient jusqu'à maintenant, la valeur d'avant depuis le début. */
    @Test fun courbe_sur_toute_la_largeur() {
        val debut = 0L; val fin = 24_000L
        // Capteur qui n'envoie qu'aux changements : une valeur avant la période, deux dedans, la dernière il y a longtemps.
        val (f1, t1) = WidgetsHa.courbe(listOf(p(-5_000, 19.0), p(6_000, 20.0), p(10_000, 21.0)), debut, fin)
        assertEquals(debut to fin, f1)
        assertEquals(listOf(p(0, 19.0), p(6_000, 20.0), p(10_000, 21.0), p(24_000, 21.0)), t1)
        // Historique plus court (capteur récent, Home Assistant redémarré à mi-période) : la fenêtre suit les données.
        val (f2, t2) = WidgetsHa.courbe(listOf(p(12_000, 20.0), p(18_000, 22.0)), debut, fin)
        assertEquals(12_000L to fin, f2)
        assertEquals(p(24_000, 22.0), t2.last())
        // Une seule mesure : un trait plat d'un bord à l'autre.
        val (f3, t3) = WidgetsHa.courbe(listOf(p(24_000, 20.0)), debut, fin)
        assertEquals(debut to fin, f3)
        assertEquals(listOf(p(0, 20.0), p(24_000, 20.0)), t3)
        // Rien : rien à tracer.
        assertTrue(WidgetsHa.courbe(emptyList(), debut, fin).second.isEmpty())
        // Le rendu se refait à la vraie taille (avant : bâti à 0 × 0, parfois seule la moitié dessinée).
        val v = lire("VueWidgetHa.kt")
        assertTrue(v.contains("override fun onSizeChanged(") && v.contains("WidgetsHa.courbe(h.points, debut, finMs)"))
    }

    /** Thermostat : les boutons − / + prennent la taille que le widget leur laisse ; le nom n'est plus retiré en silence. */
    @Test fun thermostat_boutons_et_libelle() {
        val dp = 2f
        assertEquals(48f * dp, WidgetsHa.coteBouton(400f * dp, 300f * dp, dp), 0.01f)   // grand : 48 dp
        assertEquals(30f * dp, WidgetsHa.coteBouton(80f * dp, 60f * dp, dp), 0.01f)     // 1 × 1 : 30 dp, jamais moins
        assertEquals(35f * dp, WidgetsHa.coteBouton(140f * dp, 100f * dp, dp), 0.01f)   // 2 × 1 : un quart de la largeur
        val v = lire("VueWidgetHa.kt")
        assertFalse("le nom ne doit plus être retiré (sansNom)", v.contains("val sansNom"))
        val regler = v.substring(v.indexOf("private fun reglerValeur("), v.indexOf("private fun coteBouton()"))
        assertTrue(regler.contains("enteteSelon(c, e)") && regler.contains("val cote0 = coteBouton()"))
    }

    /** Parité : les widgets de Vision du téléphone se posent sur la télé, selon le public, les modules et le lieu. */
    @Test fun widgets_de_vision_permis_sur_la_tele() {
        val w = listOf(Triple("chauffage", emptySet<String>(), true), Triple("demandes", setOf("parent"), true), Triple("devoirs", setOf("enfant"), true),
            Triple("ecole", emptySet<String>(), true), Triple("temps", setOf("enfant"), false), Triple(ZoneHaut.HORLOGE, emptySet<String>(), true))
        assertEquals(listOf("chauffage", "demandes"), DispositionAccueil.widgetsVisionPermis(w, "parent", setOf("chauffage")))
        assertEquals(listOf("chauffage", "devoirs", "ecole"), DispositionAccueil.widgetsVisionPermis(w, "enfant", null))
    }

    /** Parité : sur la télé, les widgets Home Assistant agissent, et les widgets de Vision sont dessinés comme sur le téléphone. */
    @Test fun la_tele_agit_et_dessine_pareil() {
        val l = lire("LanceurActivity.kt")
        val tele = l.substring(l.indexOf("private fun widgetHaTele("), l.indexOf("private fun autoriserWidget("))
        assertTrue(tele.contains("lectureSeule = false") && tele.contains("agirAvec = { e, s, d -> agirHa(e, s, d) }"))
        assertTrue(tele.contains("v.commandes().firstOrNull()?.let { it.requestFocus()"))
        assertTrue(l.contains("private fun contenuWidget(code: String): View") && l.contains("widgetIntegreTele(pl.code, pl.w, pl.h)"))
        assertTrue(l.contains("private fun autoriserWidget(ok: () -> Unit) = if (tele) ok() else autoriserAccueil(ok)"))
        assertTrue(lire("VueWidgetHa.kt").contains("Ui.anneauFocus(this, theme, 999f, agrandir = false)"))
    }
}
