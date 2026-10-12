// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Widgets Home Assistant : analyse des réponses /widgets/…, styles, tailles, historique, actions (docs/widgets-ha.md). */
class WidgetsHaTest {

    // ------------------------------------------------------------------ JSON

    @Test fun json_lit_et_ecrit() {
        val o = Json.lire("""{"a": [1, 2.5, "x\"y", true, null], "b": {"c": "éé"}}""") as Map<*, *>
        assertEquals(listOf(1.0, 2.5, "x\"y", true, null), o["a"])
        assertEquals("éé", (o["b"] as Map<*, *>)["c"])
        val texte = Json.ecrire(o)
        assertEquals(o, Json.lire(texte))
        assertNull(Json.objet("pas du json"))
        assertNull(Json.objet("[1,2]"))
    }

    // ------------------------------------------------------------------ réponses

    private val entites = """
        {"version": 3, "entites": [
          {"entity_id": "switch.prise", "nom": "Prise du bureau", "domaine": "switch", "icone": "mdi:power", "unite": "", "device_class": "",
           "etat": "on", "attributs_utiles": {"friendly_name": "Prise du bureau"}, "actions": ["toggle", "turn_on", "turn_off"], "styles": ["interrupteur", "tuile"]},
          {"entity_id": "sensor.temp", "nom": "Température salon", "domaine": "sensor", "unite": "°C", "device_class": "temperature",
           "etat": "21.5", "attributs_utiles": {"unit_of_measurement": "°C"}, "actions": [], "styles": []},
          {"entity_id": "pas_un_id", "nom": "Mauvaise"}
        ]}"""

    @Test fun analyse_des_entites() {
        val l = WidgetsHa.analyserEntites(entites)
        assertEquals(listOf("switch.prise", "sensor.temp"), l.map { it.entityId })
        val prise = l.first { it.entityId == "switch.prise" }
        assertEquals(listOf("toggle", "turn_on", "turn_off"), prise.actions)
        assertEquals(listOf("interrupteur", "tuile", "mini_carte"), WidgetsHa.stylesProposes(prise))
        val temp = l.first { it.entityId == "sensor.temp" }
        assertEquals("°C", temp.unite)
        // Pas de styles du serveur : repli par domaine (capteur numérique).
        assertEquals(listOf("tuile", "jauge", "graphique", "mini_carte"), WidgetsHa.stylesProposes(temp))
        assertTrue(WidgetsHa.analyserEntites("{}").isEmpty())
        assertTrue(WidgetsHa.analyserEntites("illisible").isEmpty())
    }

    @Test fun analyse_des_etats_et_du_flux() {
        val e = WidgetsHa.analyserEtats("""{"etats": {"light.salon": {"etat": "on", "attributs_utiles": {"brightness": 200}, "depuis": "2026-10-08T10:00:00+00:00"}}}""")
        assertEquals("on", e["light.salon"]!!.etat)
        assertEquals(200.0, e["light.salon"]!!.attributs["brightness"])
        val ev = WidgetsHa.analyserEvenement("""{"entity_id": "sensor.temp", "etat": "22", "attributs_utiles": {}, "depuis": "1759917600"}""")
        assertEquals("sensor.temp", ev!!.first); assertEquals("22", ev.second.etat)
        assertNull(WidgetsHa.analyserEvenement("""{"rien": 1}"""))
    }

    @Test fun lecteur_sse_battement_evenements_et_fin() {
        val l = WidgetsHa.LecteurSse()
        assertNull(l.ligne(": battement"))
        assertNull(l.ligne(""))
        assertNull(l.ligne("data: {\"entity_id\": \"a.b\","))
        assertNull(l.ligne("data:  \"etat\": \"on\"}\r"))
        val ev = l.ligne("")!!
        assertEquals("message", ev.type)
        assertEquals("{\"entity_id\": \"a.b\",\n \"etat\": \"on\"}", ev.donnees)
        assertEquals("on", WidgetsHa.analyserEvenement(ev.donnees)!!.second.etat)
        assertNull(l.ligne("event: fin"))
        assertNull(l.ligne("data: {}"))
        assertEquals("fin", l.ligne("")!!.type)
    }

    @Test fun historique_nombres_en_secondes_et_etats() {
        val h = WidgetsHa.analyserHistorique("""{"type": "nombre", "unite": "W", "points": [[1759917660, 12.5], [1759917600, 10], [1759917720, "x"]]}""") as WidgetsHa.Historique.Nombres
        assertEquals("W", h.unite)
        assertEquals(listOf(1759917600000L, 1759917660000L), h.points.map { it.ts })   // secondes → ms, trié, valeur illisible écartée
        val s = WidgetsHa.analyserHistorique("""{"type": "etats", "segments": [[1759917600, 1759921200, "on"], [1759921200, 1759924800, "off"]]}""") as WidgetsHa.Historique.Etats
        assertEquals(2, s.segments.size); assertEquals("on", s.segments[0].etat)
        assertNull(WidgetsHa.analyserHistorique("""{"type": "autre"}"""))
    }

    @Test fun horodatages_iso() {
        assertEquals(1759917600000L, WidgetsHa.iso("2025-10-08T10:00:00Z"))
        assertEquals(1759917600000L, WidgetsHa.iso("2025-10-08T12:00:00+02:00"))
        assertEquals(1759917600123L, WidgetsHa.iso("2025-10-08T10:00:00.123456+00:00"))
        assertEquals(1759917600000L, WidgetsHa.horodatage(1759917600.0))
        assertEquals(1759917600000L, WidgetsHa.horodatage(1759917600000.0))
    }

    @Test fun erreurs_en_clair_sans_code_brut() {
        assertEquals("piloter", WidgetsHa.motif("""{"error": "piloter"}"""))
        val messages = listOf(403 to "module", 403 to "piloter", 403 to "action", 403 to "entite", 404 to "absente", 429 to "trop_vite",
            502 to "service", 400 to "periode", 400 to "data", 0 to "", 401 to "")
        val vus = messages.map { (c, m) -> WidgetsHa.messageErreur(c, m) }
        vus.forEach { assertFalse("Pas de code brut : $it", Regex("""\b(403|404|429|502|400|401)\b""").containsMatchIn(it)) }
        assertEquals(vus.size, vus.toSet().size)
        assertTrue(WidgetsHa.messageErreur(403, "piloter").contains("rôle"))
    }

    // ------------------------------------------------------------------ styles et tailles

    @Test fun styles_par_domaine() {
        assertEquals(WidgetsHa.INTERRUPTEUR, WidgetsHa.stylesParDomaine("light", "", "on").first())
        assertEquals(WidgetsHa.BOUTON, WidgetsHa.stylesParDomaine("script", "", "off").first())
        assertEquals(WidgetsHa.BOUTON, WidgetsHa.stylesParDomaine("scene", "", "").first())
        assertEquals(WidgetsHa.METEO, WidgetsHa.stylesParDomaine("weather", "", "sunny").first())
        assertEquals(WidgetsHa.OUVERTURE, WidgetsHa.stylesParDomaine("binary_sensor", "door", "off").first())
        assertFalse(WidgetsHa.OUVERTURE in WidgetsHa.stylesParDomaine("binary_sensor", "motion", "off"))
        assertTrue(WidgetsHa.JAUGE in WidgetsHa.stylesParDomaine("sensor", "humidity", "57"))
        assertFalse(WidgetsHa.JAUGE in WidgetsHa.stylesParDomaine("sensor", "", "texte"))
        for (d in listOf("light", "sensor", "weather", "script", "lock")) assertTrue(WidgetsHa.MINI_CARTE in WidgetsHa.stylesParDomaine(d, "", "1"))
        // Les styles inconnus du serveur sont écartés.
        val e = WidgetsHa.Entite("x.y", "X", "x", "", "", "", "", emptyMap(), emptyList(), listOf("hologramme", "tuile"))
        assertEquals(listOf("tuile", "mini_carte"), WidgetsHa.stylesProposes(e))
    }

    @Test fun tailles_permises_et_bornes() {
        for (s in WidgetsHa.STYLES) {
            val t = WidgetsHa.taillesPermises(s)
            assertTrue(t.isNotEmpty())
            val b = WidgetsHa.bornes(s)
            t.forEach { (w, h) -> assertTrue("$s $w×$h", b.permet(w, h) && w <= Accueil.COLONNES) }
        }
        assertEquals(4 to 2, WidgetsHa.taillesPermises(WidgetsHa.GRAPHIQUE).first())
        assertEquals(Redim.Bornes(1, 1, 2, 2), WidgetsHa.bornes(WidgetsHa.BOUTON))
    }

    @Test fun reglage_aller_retour() {
        val r = WidgetsHa.Reglage("abc", listOf("sensor.a", "light.b"), WidgetsHa.MINI_CARTE, 4, 2, "Salon « bas »", "ampoule", "barres", "7j")
        assertEquals(r, WidgetsHa.decoder(WidgetsHa.encoder(r)))
        assertNull(WidgetsHa.decoder("""{"id": "x", "entites": [], "style": "tuile"}"""))
        assertNull(WidgetsHa.decoder("""{"id": "x", "entites": ["a.b"], "style": "inconnu"}"""))
    }

    // ------------------------------------------------------------------ actions et textes

    @Test fun actions_seulement_si_permises() {
        val prise = WidgetsHa.analyserEntites(entites).first { it.entityId == "switch.prise" }
        val temp = WidgetsHa.analyserEntites(entites).first { it.entityId == "sensor.temp" }
        assertTrue(WidgetsHa.pilotable(prise, tele = false))
        assertFalse("TV : lecture seule", WidgetsHa.pilotable(prise, tele = true))
        assertFalse(WidgetsHa.pilotable(temp, tele = false))
        assertEquals("toggle", WidgetsHa.serviceBascule(prise, "on"))
        val sansToggle = prise.copy(actions = listOf("turn_on"))
        assertEquals("turn_on", WidgetsHa.serviceBascule(sansToggle, "off"))
        assertNull(WidgetsHa.serviceBascule(sansToggle, "on"))
        assertEquals("press", WidgetsHa.serviceBouton(prise.copy(domaine = "button", actions = listOf("press"))))
        assertEquals("turn_on", WidgetsHa.serviceBouton(prise.copy(domaine = "script", actions = listOf("turn_on"))))
        assertNull(WidgetsHa.serviceBouton(prise.copy(actions = emptyList())))
    }

    @Test fun valeurs_affichees() {
        assertEquals("21,5°C", WidgetsHa.valeurAffichee("21.5", "°C", "sensor", "temperature"))
        assertEquals("57%", WidgetsHa.valeurAffichee("57", "%", "sensor", "humidity"))
        assertEquals("1250 W", WidgetsHa.valeurAffichee("1250", "W", "sensor", "power"))
        assertEquals("Ouvert", WidgetsHa.valeurAffichee("on", "", "binary_sensor", "door"))
        assertEquals("Fermé", WidgetsHa.valeurAffichee("off", "", "binary_sensor", "window"))
        assertEquals("Allumé", WidgetsHa.valeurAffichee("on", "", "light", ""))
        assertEquals("–", WidgetsHa.valeurAffichee("unavailable", "", "light", ""))
        assertEquals("Éclaircies", WidgetsHa.valeurAffichee("partlycloudy", "", "weather", ""))
        assertEquals("ampoule", WidgetsHa.pictoAuto("light", ""))
        assertEquals("thermometre", WidgetsHa.pictoAuto("sensor", "temperature"))
        assertEquals("porte", WidgetsHa.pictoAuto("binary_sensor", "door"))
    }

    // ------------------------------------------------------------------ historique et jauge

    @Test fun reduction_de_l_historique() {
        val pts = (0 until 1000).map { WidgetsHa.Point(it * 60_000L, it.toDouble()) }
        val r = WidgetsHa.reduire(pts, 100)
        assertTrue(r.size <= 100 && r.size >= 95)
        assertEquals(pts.map { it.v }.average(), r.map { it.v }.average(), 1.0)
        assertTrue(r.zipWithNext().all { (a, b) -> a.ts < b.ts })
        assertEquals(pts.take(5), WidgetsHa.reduire(pts.take(5), 100))
    }

    @Test fun barres_par_heure_et_par_jour() {
        val fin = 100L * 3_600_000L
        val pts = listOf(WidgetsHa.Point(fin - 30 * 60_000L, 4.0), WidgetsHa.Point(fin - 20 * 60_000L, 6.0), WidgetsHa.Point(fin - 25 * 3_600_000L, 99.0))
        val b = WidgetsHa.barres(pts, "24h", fin)
        assertEquals(24, b.size)
        assertEquals(5.0, b.last()!!, 1e-9)
        assertTrue(b.dropLast(1).all { it == null })   // le point d'il y a 25 h est hors période
        assertEquals(7, WidgetsHa.barres(pts, "7j", fin).size)
    }

    @Test fun echelle_jauge_et_bandes() {
        assertEquals(0.0 to 100.0, WidgetsHa.minMax(emptyMap(), "%", 57.0))
        assertEquals(10.0 to 30.0, WidgetsHa.minMax(mapOf("min" to 10.0, "max" to 30.0), "", 20.0))
        assertEquals(0.5f, WidgetsHa.fraction(20.0, 10.0, 30.0), 1e-6f)
        assertEquals(1f, WidgetsHa.fraction(99.0, 10.0, 30.0), 1e-6f)
        val (mi, ma) = WidgetsHa.echelle(listOf(5.0, 5.0))
        assertTrue(ma > mi)
        val bandes = WidgetsHa.bandes(listOf(WidgetsHa.Segment(0, 50, "on"), WidgetsHa.Segment(50, 200, "off")), 0, 100)
        assertEquals(listOf(Triple(0f, 0.5f, "on"), Triple(0.5f, 1f, "off")), bandes)
    }

    @Test fun reconnexion_au_delai_croissant_et_borne() {
        val d = (0 until 12).map { WidgetsHa.delaiReconnexion(it) }
        assertEquals(2_000L, d[0]); assertEquals(4_000L, d[1])
        assertTrue(d.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(120_000L, d.last())
    }

    @Test fun version_du_releve() {
        assertEquals("7", WidgetsHa.versionReleve(mapOf("version" to 7.0), null))
        assertEquals("7", WidgetsHa.versionReleve(mapOf("version" to "7"), null))
        assertEquals("abc", WidgetsHa.versionReleve(null, "abc"))
        assertEquals("", WidgetsHa.versionReleve(null, null))
    }
}
