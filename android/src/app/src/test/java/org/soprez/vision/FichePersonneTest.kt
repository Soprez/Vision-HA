// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Refonte lot 3 : la fiche d'une personne (analyse défensive du contrat HA, textes, passage d'une personne à l'autre). */
class FichePersonneTest {
    @After fun langue() { TrJvm.langue = "fr" }

    private val reponse = """{"ok":true,"personnes":[
        {"entite":"person.lou","prenom":"Lou","role":"Demandeur","appareils":["tab_lou","tel_lou"],"temps":{"actif":95},"parent":false,
         "fiche":{"modules":{"plages":true,"reveil":true,"temps_gagne":true,"regles_lieu":false,"entites":true},
                  "role_nom":"Demandeur","reveils":[{"nom":"École","actif":true,"prochain":"2026-10-13T07:00:00+02:00"},{"nom":"Week-end","actif":false}],
                  "recompenses":{"liste":"Tâches de Lou","minutes":15,"max":60,"accorde":30},
                  "regles_lieu":[{"zone":"Collège","categories":["Jeux","Réseaux sociaux"]},{"categories":["sans zone"]}],
                  "widgets":3}},
        {"entite":"person.sam","prenom":"Sam","appareils":[],"parent":true},
        {"prenom":"sans entité"}
    ]}"""

    @Test fun analyse_complete() {
        val ps = FichePersonne.personnes(reponse)
        assertEquals(listOf("person.lou", "person.sam"), ps.map { it.entite })
        val lou = ps[0]
        assertEquals("Lou", lou.prenom)
        assertEquals(listOf("tab_lou", "tel_lou"), lou.appareils)
        assertEquals(95, lou.tempsActif)
        assertFalse(lou.decideur)
        val f = lou.fiche!!
        assertTrue(f.module(FichePersonne.M_PLAGES))
        assertFalse(f.module(FichePersonne.M_REGLES_LIEU))
        assertEquals("Demandeur", f.roleNom)
        assertEquals(2, f.reveils.size)
        assertTrue(f.reveils[0].actif)
        assertEquals(java.time.Instant.parse("2026-10-13T05:00:00Z").toEpochMilli(), f.reveils[0].prochain)
        assertEquals(0L, f.reveils[1].prochain)
        assertEquals(FichePersonne.Recompense("Tâches de Lou", 15, 60, 30), f.recompenses)
        assertEquals(1, f.reglesLieu.size)  // la règle sans zone est écartée
        assertEquals(listOf("Jeux", "Réseaux sociaux"), f.reglesLieu[0].categories)
        assertEquals(3, f.widgets)
        assertTrue(ps[1].decideur)
        assertNull(ps[1].fiche)
        assertNull(ps[1].tempsActif)
    }

    @Test fun champs_absents_ou_de_mauvais_type() {
        val f = FichePersonne.analyser("""{"modules":{"reveil":"oui","entites":1},"reveils":"non","recompenses":null,"regles_lieu":{},"widgets":"trois"}""")!!
        assertFalse(f.module(FichePersonne.M_REVEIL))
        assertFalse(f.module(FichePersonne.M_ENTITES))
        assertFalse(f.module(FichePersonne.M_PLAGES))  // absent : section masquée
        assertTrue(f.reveils.isEmpty())
        assertNull(f.recompenses)
        assertTrue(f.reglesLieu.isEmpty())
        assertEquals(0, f.widgets)
        assertEquals("", f.roleNom)
        assertEquals(FichePersonne.Fiche(), FichePersonne.analyser("{}"))
        assertNull(FichePersonne.analyser("pas du JSON"))
        assertNull(FichePersonne.analyser(null))
        assertTrue(FichePersonne.personnes("""{"ok":true}""").isEmpty())
        assertTrue(FichePersonne.personnes("illisible").isEmpty())
        val p = FichePersonne.personne(mapOf("entite" to "person.x", "temps" to "?", "appareils" to listOf("a", 3, "")))
        assertEquals(listOf("a"), p.appareils)
        assertNull(p.tempsActif)
        assertNull(p.fiche)
        assertEquals(0L, FichePersonne.dateIso("demain"))
        assertEquals(java.time.Instant.parse("2026-10-13T07:00:00Z").toEpochMilli(), FichePersonne.dateIso("2026-10-13T07:00:00.123456Z"))
    }

    @Test fun textes() {
        TrJvm.langue = "fr"
        assertEquals("15 min par tâche de « Tâches de Lou », 60 min au plus par jour ; 30 min gagnées aujourd'hui",
            FichePersonne.texteRecompense(FichePersonne.Recompense("Tâches de Lou", 15, 60, 30)))
        assertEquals("Pas de récompense réglée", FichePersonne.texteRecompense(null))
        assertEquals("1 widget de Home Assistant", FichePersonne.texteWidgets(1))
        assertEquals("3 widgets de Home Assistant", FichePersonne.texteWidgets(3))
        assertEquals("Collège : Jeux, Réseaux sociaux", FichePersonne.texteRegle(FichePersonne.RegleLieu("Collège", listOf("Jeux", "Réseaux sociaux"))))
        assertEquals("Collège : JEUX", FichePersonne.texteRegle(FichePersonne.RegleLieu("Collège", listOf("Jeux"))) { it.uppercase() })
        assertEquals("Activé", FichePersonne.etatReveil(FichePersonne.Reveil("École", true, 0)))
        assertEquals("Désactivé", FichePersonne.etatReveil(FichePersonne.Reveil("École", false, 0)))
        TrJvm.langue = "en"
        assertEquals("0 Home Assistant widgets", FichePersonne.texteWidgets(0))
        assertEquals("1 Home Assistant widget", FichePersonne.texteWidgets(1))
        assertEquals("No reward set", FichePersonne.texteRecompense(null))
        // Jamais le vocabulaire proscrit dans les nouveaux textes, dans aucune langue.
        for (l in listOf("en", "fr", "de", "es", "it", "nl")) {
            TrJvm.langue = l
            val textes = TrJvm.dossier(TrJvm.dossierDe(l)).chaines.filterKeys { it.startsWith("fiche_") || it.startsWith("lot3_") }.values
            assertEquals(28, textes.size)
            for (t in textes) assertFalse("$l : $t", Regex("(?i)parent|enfant|famille|maison|child|kind|eltern|familie|hijo|padre|genitor|figli|famiglia|ouder").containsMatchIn(t))
        }
    }

    @Test fun personne_precedente_et_suivante() {
        val l = listOf("a", "b", "c")
        assertEquals("b", FichePersonne.voisine(l, "a", 1))
        assertEquals("a", FichePersonne.voisine(l, "c", 1))   // en boucle
        assertEquals("c", FichePersonne.voisine(l, "a", -1))
        assertEquals("b", FichePersonne.voisine(l, "c", -1))
        assertEquals("a", FichePersonne.voisine(l, "inconnue", 1))
        assertEquals("c", FichePersonne.voisine(l, "inconnue", -1))
        assertEquals("a", FichePersonne.voisine(listOf("a"), "a", 1))
        assertNull(FichePersonne.voisine(emptyList(), "a", 1))
    }

    @Test fun initiale_de_l_avatar() {
        assertEquals("L", FichePersonne.initiale("lou"))
        assertEquals("É", FichePersonne.initiale(" Élise"))
        assertEquals("?", FichePersonne.initiale(""))
    }
}
