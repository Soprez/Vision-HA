// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.91.0 : le fond animé de l'écran Musique — réglages, couleurs, légèreté, niveau du son (sans Android). */
class FondLecteurTest {
    @Test fun lesReglagesInconnusRetombentSurLeDefaut() {
        assertEquals(FondLecteur.Style.AURORE, FondLecteur.style("aurore"))
        assertEquals(FondLecteur.Style.AUCUN, FondLecteur.style("aucun"))
        assertEquals(FondLecteur.STYLE_DEFAUT, FondLecteur.style("n'importe quoi"))
        assertEquals(FondLecteur.STYLE_DEFAUT, FondLecteur.style(null as String?))
        assertEquals(FondLecteur.Intensite.VIVE, FondLecteur.intensite("vive"))
        assertEquals(FondLecteur.INTENSITE_DEFAUT, FondLecteur.intensite(null as String?))
        // Les clés rangées sur l'appareil ne changent jamais (un réglage survit aux mises à jour).
        assertEquals(listOf("aucun", "ondes", "particules", "aurore", "degrade"), FondLecteur.Style.values().map { it.cle })
        assertEquals(listOf("douce", "moyenne", "vive"), FondLecteur.Intensite.values().map { it.cle })
    }

    @Test fun uneTeleModesteDessineMoinsSouvent() {
        assertEquals(30, FondLecteur.cadence(tele = false, modeste = false))
        assertTrue(FondLecteur.cadence(tele = true, modeste = false) < 30)
        assertTrue(FondLecteur.cadence(tele = true, modeste = true) < FondLecteur.cadence(tele = true, modeste = false))
    }

    @Test fun lesCouleursDuThemeRestentLisibles() {
        for (t in Themes.liste) {
            val c = FondLecteur.couleurs(t.fond, t.halos, t.pourpre, t.sombre, null)
            assertEquals(4, c.size)
            assertEquals(t.fond, c[0])
            for (x in c.drop(1)) {
                val l = FondLecteur.luminance(x)
                if (t.sombre) assertTrue("${t.nom} : teinte trop claire ($l)", l <= 0.42f + 1e-3f)
                else assertTrue("${t.nom} : teinte trop sombre ($l)", l >= 0.62f - 1e-3f || l >= FondLecteur.luminance(t.fond) - 0.25f)
            }
        }
    }

    @Test fun lesCouleursDUnePochette() {
        val rouge = 0xFFD02020.toInt(); val bleu = 0xFF2040D0.toInt(); val gris = 0xFF808080.toInt()
        // Surtout du rouge, un peu de bleu, beaucoup de gris : le rouge d'abord, puis le bleu ; le gris ne compte pas.
        val px = IntArray(400) { i -> when { i < 200 -> rouge; i < 260 -> bleu; else -> gris } }
        val c = FondLecteur.couleursPochette(px)
        assertNotNull(c); c!!
        assertEquals(3, c.size)
        assertTrue(FondLecteur.teinte(c[0]) < 15f || FondLecteur.teinte(c[0]) > 345f)
        assertEquals(230f, FondLecteur.teinte(c[1]), 15f)
        // Une pochette sans couleur vive (noir et blanc) : on garde le thème.
        assertNull(FondLecteur.couleursPochette(IntArray(100) { if (it % 2 == 0) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }))
        assertNull(FondLecteur.couleursPochette(IntArray(0)))
        // Des pixels transparents ne comptent pas.
        assertNull(FondLecteur.couleursPochette(IntArray(50) { 0x00D02020 }))
    }

    @Test fun laPochetteEstAdoucieVersLeFond() {
        val sombre = Themes.liste.first { it.sombre }
        val c = FondLecteur.couleurs(sombre.fond, sombre.halos, sombre.pourpre, true, intArrayOf(0xFFFFFF00.toInt(), 0xFF00FFFF.toInt(), 0xFFFF00FF.toInt()))
        c.drop(1).forEach { assertTrue(FondLecteur.luminance(it) <= 0.42f + 1e-3f) }
        val clair = Themes.liste.first { !it.sombre }
        val d = FondLecteur.couleurs(clair.fond, clair.halos, clair.pourpre, false, intArrayOf(0xFF200000.toInt(), 0xFF002000.toInt(), 0xFF000020.toInt()))
        d.drop(1).forEach { assertTrue(FondLecteur.luminance(it) > 0.45f) }
    }

    @Test fun lOpaciteResteUnVoile() {
        for (i in FondLecteur.Intensite.values()) for (n in listOf(-1f, 0f, 0.5f, 1f, 3f)) {
            val o = FondLecteur.opacite(0.6f, i, n)
            assertTrue(o in 0f..0.85f)
        }
        assertTrue(FondLecteur.opacite(0.3f, FondLecteur.Intensite.DOUCE, 0f) < FondLecteur.opacite(0.3f, FondLecteur.Intensite.VIVE, 0f))
        assertTrue(FondLecteur.opacite(0.3f, FondLecteur.Intensite.MOYENNE, 0f) < FondLecteur.opacite(0.3f, FondLecteur.Intensite.MOYENNE, 1f))
    }

    @Test fun leBruitEstDeterministeEtBorne() {
        assertEquals(FondLecteur.bruit(0.7f, 12.3f, 2), FondLecteur.bruit(0.7f, 12.3f, 2), 0f)
        for (k in 0 until 200) {
            val b = FondLecteur.bruit(k * 0.37f, k * 1.9f, k % 7)
            assertTrue(b in -1f..1f)
            val h = FondLecteur.hasard(k, k % 5)
            assertTrue(h in 0f..1f)
        }
        assertEquals(FondLecteur.hasard(17, 3), FondLecteur.hasard(17, 3), 0f)
    }

    @Test fun leNiveauDuSon() {
        assertEquals(0f, NiveauSon.mesurer(ShortArray(0)), 0f)
        assertEquals(0f, NiveauSon.mesurer(ShortArray(1024)), 0f)
        val fort = NiveauSon.mesurer(ShortArray(1024) { if (it % 2 == 0) 30000 else -30000 })
        assertEquals(1f, fort, 0.001f)
        val moyen = NiveauSon.mesurer(ShortArray(1024) { if (it % 2 == 0) 3000 else -3000 })
        assertTrue(moyen > 0.2f && moyen < 0.8f)
        // Rien n'est écrit depuis une demi-seconde : silence.
        NiveauSon.noter(ShortArray(256) { 20000 }, 1_000)
        assertTrue(NiveauSon.niveau(1_100) > 0f)
        assertEquals(0f, NiveauSon.niveau(1_700), 0f)
    }

    @Test fun lesCouleursSeMelangentSansAndroid() {
        assertEquals(0xFF7F7F7F.toInt(), FondLecteur.melange(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0.5f))
        assertArrayEquals(intArrayOf(0xFF000000.toInt()), intArrayOf(FondLecteur.melange(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0f)))
        assertEquals(0xFFFFFFFF.toInt(), FondLecteur.melange(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 1f))
        assertEquals(0f, FondLecteur.saturation(0xFF777777.toInt()), 0f)
        assertEquals(1f, FondLecteur.saturation(0xFFFF0000.toInt()), 0f)
        assertEquals(120f, FondLecteur.teinte(0xFF00FF00.toInt()), 0.5f)
    }
}
