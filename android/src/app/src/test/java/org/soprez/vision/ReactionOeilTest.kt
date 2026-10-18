package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.soprez.vision.ReactionOeil.Boite

/** 1.90.5 (l'auteur, variante B) : où l'œil se pose, quand il garde son texte, et la zone du haut par défaut. */
class ReactionOeilTest {
    private val ecran = Boite(0, 80, 1080, 2200)

    @Test fun coinLibrePrisEnPremier() {
        val r = ReactionOeil.placer(ecran, 200, 200, 40, emptyList())
        assertEquals(Boite(840, 120, 1040, 320), r)  // haut, côté fin
    }

    @Test fun jamaisSurUnBoutonOuUnTexte() {
        // Un bouton en haut à droite (barre d'outils), un texte en bas à droite : l'œil part en haut à gauche.
        val occupes = listOf(Boite(800, 100, 1080, 260), Boite(700, 1900, 1080, 2200))
        val r = ReactionOeil.placer(ecran, 200, 200, 40, occupes)
        assertTrue("dans l'écran utile", r.dans(ecran))
        assertTrue("ne touche rien : $r", occupes.none { it.croise(r) })
        assertEquals(40, r.g)
    }

    @Test fun droiteAGaucheLeCoinDeFinEstAGauche() {
        val r = ReactionOeil.placer(ecran, 200, 200, 40, emptyList(), rtl = true)
        assertEquals(40, r.g)
    }

    /** Coins pris (en-tête en haut, rangée d'applis en bas) : la bande libre au milieu, le long du bord de fin. */
    @Test fun sansCoinLibreUnePlaceLeLongDuBord() {
        val tele = Boite(0, 0, 1920, 1080)
        val occupes = listOf(Boite(0, 0, 1920, 220), Boite(0, 640, 1920, 1080))
        val r = ReactionOeil.placer(tele, 600, 262, 64, occupes)
        assertTrue("dans l'écran : $r", r.dans(tele))
        assertTrue("ne touche rien : $r", occupes.none { it.croise(r) })
        assertEquals(1920 - 64 - 600, r.g)
    }

    @Test fun toutEstPrisEnBasAuCentreCommeLAncienBandeau() {
        val r = ReactionOeil.placer(ecran, 200, 200, 40, listOf(ecran))
        assertEquals(Boite(440, 1960, 640, 2160), r)
    }

    @Test fun succesSansTexteInfoEtErreurAvecTexte() {
        assertFalse(ReactionOeil.avecTexte(OeilModele.Genre.SUCCES))
        assertTrue(ReactionOeil.avecTexte(OeilModele.Genre.INFO))
        assertTrue(ReactionOeil.avecTexte(OeilModele.Genre.ERREUR))
    }

    @Test fun zoneDuHautParDefaut() {
        // Télé : le merci (et l'œil joyeux) pour une nouvelle installation ; 1.91.2 : une télé reliée a un tableau de
        // bord — l'horloge stylisée, puis les widgets partagés par Home Assistant (quatre au plus, dans leur ordre).
        assertEquals(listOf(ZoneHaut.REMERCIEMENT), ZoneHaut.initial(tele = true, dejaRelie = false))
        assertEquals(listOf(ZoneHaut.HORLOGE), ZoneHaut.initial(tele = true, dejaRelie = true))
        val ha = (1..6).map { ZoneHaut.PREFIXE_HA + "sensor.s$it" }
        assertEquals(listOf(ZoneHaut.HORLOGE) + ha.take(4), ZoneHaut.initial(tele = true, dejaRelie = true, ha = ha))
        assertEquals(listOf(ZoneHaut.REMERCIEMENT), ZoneHaut.initial(tele = true, dejaRelie = false, ha = ha))
        assertEquals("le téléphone garde sa zone", listOf(ZoneHaut.HORLOGE), ZoneHaut.initial(tele = false, dejaRelie = true, ha = ha))
        // Les défauts d'avant (non choisis) cèdent la place au tableau de bord ; un choix du Décideur reste.
        assertTrue(ZoneHaut.estAncienDefaut(listOf(ZoneHaut.CLASSIQUE)) && ZoneHaut.estAncienDefaut(listOf(ZoneHaut.REMERCIEMENT)))
        assertFalse(ZoneHaut.estAncienDefaut(listOf(ZoneHaut.HORLOGE, ZoneHaut.METEO)))
        // Téléphone (retour d'essai) : l'horloge stylisée, sans météo, dans tous les cas.
        assertEquals(listOf(ZoneHaut.HORLOGE), ZoneHaut.initial(tele = false, dejaRelie = false))
        assertEquals(listOf(ZoneHaut.HORLOGE), ZoneHaut.initial(tele = false, dejaRelie = true))
        // La météo reste une option du Décideur sur le téléphone ; l'ancien bloc n'est plus proposé.
        assertTrue(ZoneHaut.METEO in ZoneHaut.catalogue(tele = false))
        assertFalse(ZoneHaut.CLASSIQUE in ZoneHaut.catalogue(tele = false))
    }

    /** La zone est une liste de widgets : ordre gardé, doublons et widgets non permis retirés, « classique » seul. */
    @Test fun zoneDuHautListeDeWidgets() {
        assertEquals(listOf(ZoneHaut.METEO, ZoneHaut.HORLOGE), ZoneHaut.nettoyer(listOf(ZoneHaut.METEO, ZoneHaut.HORLOGE, ZoneHaut.METEO), tele = false))
        assertEquals(listOf(ZoneHaut.HORLOGE), ZoneHaut.nettoyer(listOf(ZoneHaut.CLASSIQUE, ZoneHaut.HORLOGE), tele = true))
        assertEquals(listOf(ZoneHaut.HORLOGE), ZoneHaut.nettoyer(listOf(ZoneHaut.CLASSIQUE, ZoneHaut.HORLOGE), tele = false))
        assertEquals(listOf(ZoneHaut.CLASSIQUE), ZoneHaut.nettoyer(listOf(ZoneHaut.CLASSIQUE), tele = true))
        // 1.91.2 : un widget Home Assistant tient dans la zone de la télé (dans l'ordre choisi), jamais sur le téléphone.
        val w = ZoneHaut.PREFIXE_HA + "sensor.temperature"
        assertEquals(listOf(w, ZoneHaut.HORLOGE), ZoneHaut.nettoyer(listOf(w, ZoneHaut.HORLOGE, w), tele = true))
        assertEquals(listOf(ZoneHaut.HORLOGE), ZoneHaut.nettoyer(listOf(w, ZoneHaut.HORLOGE), tele = false))
        assertTrue(ZoneHaut.estHa(w)); assertEquals("sensor.temperature", ZoneHaut.entiteHa(w))
        assertTrue(w in ZoneHaut.catalogue(tele = true, ha = listOf(w)))
        // Les modes des premières versions deviennent des listes.
        assertEquals(listOf(ZoneHaut.HORLOGE, ZoneHaut.METEO), ZoneHaut.depuisMode("horloge_meteo", tele = false))
        assertEquals(emptyList<String>(), ZoneHaut.depuisMode("vide", tele = true))
    }
}
