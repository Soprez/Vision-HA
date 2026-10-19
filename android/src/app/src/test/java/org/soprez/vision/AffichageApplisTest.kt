// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 1.93.06.1 (l'auteur, tablette tableau de bord) : « Afficher la liste des applications », par appareil et depuis HA. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR")
class AffichageApplisTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    @Test fun affichee_par_defaut_et_jamais_un_accueil_vide() {
        assertTrue(AffichageApplis.liste(ctx))
        assertFalse(AffichageApplis.masquee(true, 3))
        assertTrue(AffichageApplis.masquee(false, 1))
        // Sans aucun widget, la liste reste : on ne s'enferme jamais dehors.
        assertFalse(AffichageApplis.masquee(false, 0))
    }

    @Test fun les_lignes_laissees_vides_par_les_applis_sont_retirees_a_l_ecran() {
        val w = listOf(Accueil.Place("widget:chauffage", 0, 2, 2, 2), Accueil.Place("widget:fioul", 2, 2, 2, 2),
            Accueil.Place("widget:batteries", 0, 5, 2, 2))
        val vues = AffichageApplis.sansLignesVides(w)
        assertEquals(listOf(0, 0, 2), vues.map { it.row })
        assertEquals(listOf(0, 2, 0), vues.map { it.col })
        assertEquals(listOf(2, 2, 2), vues.map { it.h })
        // La disposition enregistrée n'est pas touchée (copies).
        assertEquals(listOf(2, 2, 5), w.map { it.row })
    }

    @Test fun masquer_demande_une_confirmation_explicite() {
        // 1.93.06.1 (retour d'essai) : désactiver ne s'applique qu'après « J'ai compris » ; le message dit comment revenir.
        AffichageApplis.poser(ctx, true)
        val act = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        var faits = 0
        AffichageApplis.changer(act, tele = false) { faits++ }
        var d = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        val message = org.robolectric.Shadows.shadowOf(d).message.toString()
        assertTrue(message, message.contains("appui long sur l'heure") && message.contains("Home Assistant"))
        assertTrue("rien ne change avant la confirmation", AffichageApplis.liste(ctx))
        // Annuler : rien ne change.
        d.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertTrue(AffichageApplis.liste(ctx)); assertEquals(0, faits)
        // « J'ai compris » : la liste est masquée.
        AffichageApplis.changer(act, tele = false) { faits++ }
        d = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        assertEquals("J'ai compris", d.getButton(android.content.DialogInterface.BUTTON_POSITIVE).text.toString())
        d.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertFalse(AffichageApplis.liste(ctx)); assertEquals(1, faits)
        // Réafficher : sans question.
        AffichageApplis.changer(act, tele = false) { faits++ }
        assertTrue(AffichageApplis.liste(ctx)); assertEquals(2, faits)
    }

    @Test fun home_assistant_regle_seulement_s_il_est_plus_recent() {
        AffichageApplis.poser(ctx, true)
        assertFalse(AffichageApplis.releve(ctx, null))
        assertFalse(AffichageApplis.releve(ctx, JSONObject().put("depuis", 10L)))  // bloc sans le réglage
        assertTrue(AffichageApplis.releve(ctx, JSONObject().put("depuis", 10L).put("liste_applis", false)))
        assertFalse(AffichageApplis.liste(ctx))
        // Le choix fait ensuite sur l'appareil reste ; un ancien réglage de HA (même « depuis ») n'y touche pas.
        AffichageApplis.poser(ctx, true)
        assertFalse(AffichageApplis.releve(ctx, JSONObject().put("depuis", 10L).put("liste_applis", false)))
        assertTrue(AffichageApplis.liste(ctx))
        // Un réglage plus récent de HA gagne.
        assertTrue(AffichageApplis.releve(ctx, JSONObject().put("depuis", 11L).put("liste_applis", false)))
        assertFalse(AffichageApplis.liste(ctx))
    }
}
