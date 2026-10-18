// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.91.0 : choisir le moment de la musique — la logique du saut, sans Android. */
class SautTest {
    private fun e(etat: String = "playing", duree: Double? = 240.0, piloter: Boolean = false, ici: Boolean = false,
                  participer: Boolean = false, actif: Boolean = true, titre: String = "Titre", lecteur: String = "media_player.salon") =
        Party.Etat(actif, "t1", "Salon", ici, lecteur, "Salon", Party.Maintenant(etat, titre, "", "", "", 0.3, duree, 12.0, 1000.0, ""),
            emptyList(), piloter, emptyList(), emptyList(), 3, participer = participer)

    @Test fun lePasAccelereEnAppuiLong() {
        assertEquals(10.0, Saut.pas(0), 0.0)
        assertEquals(10.0, Saut.pas(5), 0.0)
        assertEquals(30.0, Saut.pas(6), 0.0)
        assertEquals(30.0, Saut.pas(15), 0.0)
        assertEquals(60.0, Saut.pas(16), 0.0)
        assertEquals(60.0, Saut.pas(500), 0.0)
    }

    @Test fun uneCibleResteDansLeTitre() {
        assertEquals(0.0, Saut.borner(-12.0, 240.0), 0.0)
        assertEquals(239.0, Saut.borner(400.0, 240.0), 0.0)  // jamais la toute fin : le titre suivant partirait
        assertEquals(95.5, Saut.borner(95.5, 240.0), 0.0)
        assertEquals(0.0, Saut.borner(10.0, 0.0), 0.0)
    }

    @Test fun leDoigtDonneUnePosition() {
        assertEquals(120.0, Saut.depuisPart(0.5f, 240.0), 0.001)
        assertEquals(0.0, Saut.depuisPart(-0.3f, 240.0), 0.0)
        assertEquals(239.0, Saut.depuisPart(1.4f, 240.0), 0.0)
        assertEquals(0.25f, Saut.part(60.0, 240.0), 0.0001f)
        assertEquals(0f, Saut.part(null, 240.0), 0f)
        assertEquals(0f, Saut.part(60.0, null), 0f)
        assertEquals(1f, Saut.part(500.0, 240.0), 0f)
    }

    @Test fun quiPeutSauter() {
        assertTrue(Saut.permis(e(piloter = true)))
        assertTrue(Saut.permis(e(ici = true)))
        assertTrue(Saut.permis(e(participer = true)))
        assertTrue(Saut.permis(e(piloter = true, etat = "paused")))
        // Hors Party, sans pilotage : rien.
        assertFalse(Saut.permis(e(participer = true, actif = false)))
        assertFalse(Saut.permis(e()))
        // Une radio (sans durée), rien en lecture, pas de lecteur : pas de curseur.
        assertFalse(Saut.permis(e(piloter = true, duree = null)))
        assertFalse(Saut.permis(e(piloter = true, duree = 0.0)))
        assertFalse(Saut.permis(e(piloter = true, etat = "idle")))
        assertFalse(Saut.permis(e(piloter = true, titre = "")))
        assertFalse(Saut.permis(e(piloter = true, lecteur = "")))
    }

    @Test fun laPositionVoulueAvanceEtTientJusquALaConfirmation() {
        assertEquals(95.0, Saut.attendue(90.0, 1_000, 6_000, joue = true, duree = 240.0), 0.001)
        assertEquals(90.0, Saut.attendue(90.0, 1_000, 6_000, joue = false, duree = 240.0), 0.001)
        assertEquals(240.0, Saut.attendue(238.0, 0, 9_000, joue = true, duree = 240.0), 0.001)
        // Le lecteur annonce encore l'ancienne position : on tient ; il annonce la nouvelle : fini ; 8 s : abandon.
        assertFalse(Saut.fini(12.0, 92.0, 1_000, 3_000))
        assertFalse(Saut.fini(null, 92.0, 1_000, 3_000))
        assertTrue(Saut.fini(91.0, 92.0, 1_000, 3_000))
        assertTrue(Saut.fini(12.0, 92.0, 1_000, 1_000 + Saut.TENIR_MS + 1))
    }

    @Test fun leLecteurClassiqueSeRecaleSurLeFluxDuSaut() {
        val r = Saut.Recalage()
        r.nouveauFlux(1_000)            // le titre part
        assertEquals(0L, r.decalageMs)
        r.saut(95_000, 50_000, 50_000)  // saut demandé ici, le flux n'est pas encore reparti
        assertEquals(0L, r.decalageMs)
        r.nouveauFlux(50_800)           // Music Assistant relance le flux à 95 s
        assertEquals(95_000L, r.decalageMs)
        // Home Assistant annonce le même saut (un peu plus tard, à chaque relecture) : rien ne bouge.
        r.saut(95_000, 51_200, 53_000)
        r.saut(95_000, 51_100, 56_000)
        assertEquals(95_000L, r.decalageMs)
        // Le titre suivant repart de zéro.
        r.nouveauFlux(200_000)
        assertEquals(0L, r.decalageMs)
        // Le même saut annoncé encore (moins de 30 s) ne décale jamais le titre suivant.
        r.saut(95_000, 51_100, 201_000)
        assertEquals(0L, r.decalageMs)
    }

    @Test fun unSautDemandeAilleursEtConnuApresLaRelanceDuFlux() {
        val r = Saut.Recalage()
        r.nouveauFlux(1_000)
        // Le téléphone a demandé le saut à 40 s ; le flux de la télé est reparti à 40,5 s ; la télé l'apprend à 43 s.
        r.nouveauFlux(40_500)
        r.saut(120_000, 40_000, 43_000)
        assertEquals(120_000L, r.decalageMs)
    }

    @Test fun unSautApprisApresLeTitreSuivantNeLeDecalePas() {
        val r = Saut.Recalage()
        r.nouveauFlux(1_000)
        r.nouveauFlux(40_500)   // flux du saut
        r.nouveauFlux(45_000)   // titre suivant (saut tout près de la fin)
        r.saut(230_000, 40_000, 47_000)
        assertEquals(0L, r.decalageMs)
    }

    @Test fun unSautSansRelanceEstOublie() {
        val r = Saut.Recalage()
        r.nouveauFlux(1_000)
        r.saut(60_000, 10_000, 10_000)
        r.nouveauFlux(10_000 + Saut.Recalage.ATTENTE_MS + 1)  // bien plus tard : un autre titre
        assertEquals(0L, r.decalageMs)
    }

    @Test fun deuxSautsRapprochesComptentTousLesDeux() {
        val r = Saut.Recalage()
        r.nouveauFlux(1_000)
        r.saut(60_000, 10_000, 10_000)
        r.nouveauFlux(10_300)
        assertEquals(60_000L, r.decalageMs)
        r.saut(150_000, 11_500, 11_500)  // un second glisser, 1,5 s après
        r.nouveauFlux(11_900)
        assertEquals(150_000L, r.decalageMs)
    }

    /** La fin de la barre est la fin du titre : remplissage et curseur finissent au même point, le doigt vise ce qu'il touche. */
    @Test fun finDeBarreEgaleFinDuTitre() {
        for ((w, m) in listOf(1080f to 30f, 1920f to 49.5f, 720f to 33f)) {
            val fin = Saut.xDePart(1f, w, m)
            assertEquals("$w : le curseur au bout de la piste", w - m, fin, 0.001f)
            // À la dernière seconde jouée, le curseur est où finit le remplissage (une seule course).
            val d = 245.0
            val q = Saut.part(d, d)
            assertEquals(1f, q, 0f)
            assertEquals(fin, Saut.xDePart(q, w, m), 0.001f)
            // Le doigt posé sur le curseur vise la position qu'il montre (aller-retour exact).
            for (k in 0..20) { val pt = k / 20f; assertEquals(pt, Saut.partDeX(Saut.xDePart(pt, w, m), w, m), 1e-5f) }
            // Un doigt au-delà du bout de piste vise la fin (bornée à la dernière seconde du titre).
            assertEquals(d - 1.0, Saut.depuisPart(Saut.partDeX(w, w, m), d), 1e-9)
            assertEquals(0.0, Saut.depuisPart(Saut.partDeX(0f, w, m), d), 1e-9)
        }
    }
}
