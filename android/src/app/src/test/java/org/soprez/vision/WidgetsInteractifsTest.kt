// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.88.0 (widgets interactifs, proposition validée en essai le 2026-10-12) : rôle, confirmation, affichage et détail
 * (« +N »), réglage d'une valeur et d'une liste, icône « mdi: » de Home Assistant, choix d'affichage par widget.
 */
class WidgetsInteractifsTest {
    private val reponse = """
        {"version": 7, "entites": [
          {"entity_id": "todo.devoirs", "nom": "Mes devoirs", "nom_ha": "Devoirs", "domaine": "todo", "icone": "mdi:school",
           "etat": "4", "attributs_utiles": {}, "actions": [], "styles": ["tuile", "mini_carte"], "role": "lecture",
           "confirmer": false, "affichage": "valeur_detail",
           "detail": [{"texte": "Exercice 1", "valeur": "2026-10-13"}, {"texte": "Exercice 2", "valeur": ""},
                      {"texte": "Exercice 3", "valeur": ""}, {"texte": "Exercice 4", "valeur": ""}], "detail_total": 25},
          {"entity_id": "input_number.consigne", "nom": "Consigne", "domaine": "input_number", "icone": "",
           "etat": "20.0", "attributs_utiles": {"min": 15, "max": 21, "step": 0.5}, "actions": ["set_value"],
           "styles": ["reglage", "tuile"], "role": "reglage", "confirmer": true, "affichage": "valeur"},
          {"entity_id": "select.mode", "nom": "Mode", "domaine": "select", "etat": "auto",
           "attributs_utiles": {"options": ["auto", "eco", "confort"]}, "actions": ["select_option"], "role": "reglage"}
        ]}""".trimIndent()

    private fun parId() = WidgetsHa.analyserEntites(reponse).associateBy { it.entityId }

    @Test fun role_confirmation_affichage_et_detail_lus() {
        val e = parId()
        val devoirs = e.getValue("todo.devoirs")
        assertEquals("lecture", devoirs.role)
        assertEquals(WidgetsHa.AFF_VALEUR_DETAIL, devoirs.affichage)
        assertEquals(4, devoirs.detail.size)
        assertEquals(25, devoirs.detailTotal)
        assertEquals(WidgetsHa.Ligne("Exercice 1", "2026-10-13"), devoirs.detail[0])
        assertTrue(e.getValue("input_number.consigne").confirmer)
        // Un ancien Home Assistant (sans ces champs) : lecture de la valeur, sans confirmation.
        val ancien = WidgetsHa.entiteDe(mapOf("entity_id" to "sensor.x", "etat" to "1"))!!
        assertEquals(WidgetsHa.AFF_VALEUR, ancien.affichage); assertFalse(ancien.confirmer); assertTrue(ancien.detail.isEmpty())
    }

    @Test fun le_widget_choisit_son_affichage_sinon_home_assistant() {
        val devoirs = parId().getValue("todo.devoirs")
        val r = WidgetsHa.Reglage("w", listOf("todo.devoirs"), WidgetsHa.TUILE, 2, 2)
        assertEquals(WidgetsHa.AFF_VALEUR_DETAIL, WidgetsHa.affichage(r, devoirs))
        assertEquals(WidgetsHa.AFF_VALEUR, WidgetsHa.affichage(r.copy(affichage = WidgetsHa.AFF_VALEUR), devoirs))
        assertTrue(WidgetsHa.montreDetail(WidgetsHa.AFF_DETAIL) && !WidgetsHa.montreValeur(WidgetsHa.AFF_DETAIL))
        // Le choix est gardé avec le widget.
        assertEquals(WidgetsHa.AFF_DETAIL, WidgetsHa.decoder(WidgetsHa.encoder(r.copy(affichage = WidgetsHa.AFF_DETAIL)))!!.affichage)
        assertEquals(WidgetsHa.AFF_HA, WidgetsHa.decoder("""{"id":"w","entites":["todo.devoirs"],"style":"tuile"}""")!!.affichage)
    }

    @Test fun detail_tronque_avec_plus_n() {
        val lignes = (1..4).map { WidgetsHa.Ligne("L$it") }
        // 25 éléments, 3 places : 2 lignes et « +23 ».
        val (montrees, autres) = WidgetsHa.detailTronque(lignes, 25, 3)
        assertEquals(listOf("L1", "L2"), montrees.map { it.texte }); assertEquals(23, autres)
        // Tout tient : pas de « +N ».
        assertEquals(4 to 0, WidgetsHa.detailTronque(lignes, 4, 5).let { it.first.size to it.second })
        // Une seule place : une ligne et le reste en « +N ».
        assertEquals(1 to 3, WidgetsHa.detailTronque(lignes, 4, 1).let { it.first.size to it.second })
        // Le flux sans détail garde celui du catalogue.
        val devoirs = parId().getValue("todo.devoirs")
        assertEquals(25, WidgetsHa.detail(devoirs, WidgetsHa.EtatEntite("5")).second)
    }

    @Test fun reglage_pas_et_bornes_de_l_entite() {
        val c = parId().getValue("input_number.consigne")
        val st = WidgetsHa.EtatEntite("20.0", c.attributs)
        assertEquals(20.5, WidgetsHa.valeurApres(c, st, 1)!!, 1e-9)
        assertEquals(19.5, WidgetsHa.valeurApres(c, st, -1)!!, 1e-9)
        assertEquals(21.0, WidgetsHa.valeurApres(c, WidgetsHa.EtatEntite("21", c.attributs), 1)!!, 1e-9)  // borne haute
        assertEquals(15.0, WidgetsHa.valeurApres(c, WidgetsHa.EtatEntite("15", c.attributs), -1)!!, 1e-9)  // borne basse
        assertNull(WidgetsHa.valeurApres(c.copy(actions = emptyList()), st, 1))  // pas permis : rien
        assertNull(WidgetsHa.valeurApres(c, WidgetsHa.EtatEntite("unavailable", c.attributs), 1))
        val mode = parId().getValue("select.mode")
        assertEquals(listOf("auto", "eco", "confort"), WidgetsHa.options(WidgetsHa.EtatEntite("auto", mode.attributs)))
        assertEquals(WidgetsHa.REGLAGE, WidgetsHa.stylesParDomaine("select", "", "auto").first())
        assertTrue(WidgetsHa.REGLAGE in WidgetsHa.STYLES)
    }

    @Test fun icone_du_widget_puis_home_assistant_puis_domaine() {
        val devoirs = parId().getValue("todo.devoirs")
        val r = WidgetsHa.Reglage("w", listOf("todo.devoirs"), WidgetsHa.TUILE, 2, 1)
        assertEquals("batiment", WidgetsHa.picto(r, devoirs))  // mdi:school
        assertEquals("ampoule", WidgetsHa.picto(r.copy(icone = "ampoule"), devoirs))
        assertNull(WidgetsHa.picto(r.copy(icone = "aucune"), devoirs))
        assertEquals("ampoule", WidgetsHa.pictoDepuisMdi("mdi:lightbulb-outline"))
        assertEquals("prise", WidgetsHa.pictoDepuisMdi("mdi:power-socket-eu"))
        assertNull(WidgetsHa.pictoDepuisMdi("mdi:zzz-inconnu"))
        val consigne = parId().getValue("input_number.consigne")
        assertEquals(WidgetsHa.pictoAuto("input_number", ""), WidgetsHa.picto(r, consigne))  // sans icône HA
    }
}
