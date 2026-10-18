// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 16-10 (l'auteur, test réel de la 1.93.0 sur la télé du salon) : accueil « Vertical », widgets, clavier. */
class TeleAccueil1016Test {
    private val src = listOf(File("src/main/java/org/soprez/vision"), File("app/src/main/java/org/soprez/vision")).first { it.isDirectory }
    private fun lire(n: String) = File(src, n).readText()

    @Test fun rangees_en_haut_et_en_bas() {
        assertEquals(listOf("d", "h", "b"), DispositionAccueil.zonesTele(DispositionAccueil.COLONNE_GAUCHE))
        assertEquals(listOf("g", "d", "h", "b"), DispositionAccueil.zonesTele(DispositionAccueil.COLONNE_CENTRE))
        assertEquals(listOf("g", "h", "b"), DispositionAccueil.zonesTele(DispositionAccueil.COLONNE_DROITE))
        val l = lire("LanceurActivity.kt")
        assertTrue(l.contains("val haut = zone(DispositionAccueil.ZONE_H)") && l.contains("val bas = zone(DispositionAccueil.ZONE_B)"))
    }

    /** 4b : les widgets que Home Assistant partage (température, consigne, planning…) se posent aussi dans les zones. */
    @Test fun widgets_ha_posables_dans_les_zones() {
        val ha = listOf("ha:sensor.salon_temperature", "ha:climate.salon")
        val cat = DispositionAccueil.catalogueZone(ha)
        assertTrue(cat.containsAll(ha)); assertTrue(ZoneHaut.HORLOGE in cat); assertFalse(ZoneHaut.CLASSIQUE in cat)
        // Le nettoyage les garde (avant : ils disparaissaient à l'enregistrement).
        assertEquals(listOf("meteo", "ha:climate.salon"), DispositionAccueil.nettoyerZone(listOf("meteo", "ha:climate.salon", "inconnu", "meteo")))
    }

    /** 4a et 4c : plus de code pour les zones de la télé ; une zone vide ouvre le choix de ses widgets. */
    @Test fun zones_sans_code_et_zone_vide() {
        val d = lire("DispositionAccueil.kt")
        val f = d.substring(d.indexOf("fun DispositionAccueil.personnaliserZone("))
        assertFalse(f.contains("Garde.exiger"))
        assertTrue(f.contains("if (widgetsZone(a, z).isEmpty()) { composer(); return }"))
        val z = lire("ZoneHaut.kt")
        assertTrue(z.contains("if (ReglagesTvActivity.estTele(a)) composer(a, apres)"))
    }

    /** 1 et 2 : fond des applis et cadre des widgets, réglables ; par défaut comme avant (sûr pour les autres foyers). */
    @Test fun fond_et_cadre() {
        val d = lire("DispositionAccueil.kt")
        assertTrue(d.contains("getBoolean(\"tele_fond_applis\", true)") && d.contains("getBoolean(\"tele_cadre_widgets\", true)"))
        val l = lire("LanceurActivity.kt")
        assertTrue(l.contains("fondLigneTele(focus = a, enDeplacement = false)") && l.contains("fondCarteZone(focus = a)"))
        val r = lire("ReglagesTvActivity.kt")
        assertTrue(r.contains("\"tele_fond_applis\"") && r.contains("\"tele_cadre_widgets\"") && r.contains("DispositionAccueil.zonesTele(pos)"))
    }

    /** 3 : sur la télé, les champs ouvrent le clavier de Vision (celui de la musique), pas le clavier Android. */
    @Test fun clavier_de_vision_sur_la_tele() {
        assertTrue(lire("Ui.kt").contains("EditText(ctx).also { ClavierTele.brancher(it) }"))
        for (f in listOf("ZoneHaut.kt", "LanceurActivity.kt", "TableauEditeurActivity.kt", "DeverrouillageActivity.kt"))
            assertTrue(f, lire(f).contains("ClavierTele.brancher(it)"))
        // Plus aucun EditText créé sans le clavier de Vision, hors la recherche de musique (qui a le sien, toujours ouvert).
        val sans = src.listFiles()!!.filter { it.name.endsWith(".kt") && it.name != "ChoixMusiqueActivity.kt" }
            .filter { Regex("""EditText\(\w+\)\.apply""").containsMatchIn(it.readText()) }.map { it.name }
        assertEquals(emptyList<String>(), sans)
        val c = lire("ClavierTele.kt")
        assertTrue(c.contains("champ.showSoftInputOnFocus = false") && c.contains("if (!ReglagesTvActivity.estTele(ctx)) return"))
    }
}
