// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lot accueil (point 3) : une appli désinstallée (même « pour l'utilisateur 0 » seulement) ou désactivée quitte
 * aussitôt l'accueil, le dock et le tiroir ; elle revient quand elle est réinstallée ou réactivée.
 */
class PresenceAppliTest {
    private fun etat(installe: Boolean = true, reglage: Int = NettoyageAccueil.ACTIVATION_DEFAUT, manifeste: Boolean = true, lancable: Boolean = true) =
        NettoyageAccueil.EtatPaquet(installe, reglage, manifeste, lancable)

    @Test fun appli_installee_activee_lancable_presente() {
        assertTrue(NettoyageAccueil.presente(etat()))
        assertTrue(NettoyageAccueil.presente(etat(reglage = NettoyageAccueil.ACTIVEE)))
        // Activée explicitement alors que le manifeste la dit coupée : présente.
        assertTrue(NettoyageAccueil.presente(etat(reglage = NettoyageAccueil.ACTIVEE, manifeste = false)))
    }

    @Test fun inconnue_du_systeme_absente() {
        // « pm uninstall » complet : le paquet n'existe plus du tout.
        assertFalse(NettoyageAccueil.presente(null))
    }

    @Test fun desinstallee_pour_l_utilisateur_seulement_absente() {
        // « pm uninstall --user 0 » d'une appli système (Chrome) : le paquet existe encore pour le système.
        assertFalse(NettoyageAccueil.presente(etat(installe = false)))
        assertFalse(NettoyageAccueil.presente(etat(installe = false, reglage = NettoyageAccueil.ACTIVEE)))
    }

    @Test fun desactivee_absente() {
        assertFalse(NettoyageAccueil.presente(etat(reglage = NettoyageAccueil.DESACTIVEE_PAR_UTILISATEUR)))
        assertFalse(NettoyageAccueil.presente(etat(reglage = NettoyageAccueil.DESACTIVEE)))
        assertFalse(NettoyageAccueil.presente(etat(reglage = NettoyageAccueil.DESACTIVEE_JUSQU_A_USAGE)))
        // Réglage par défaut mais coupée dans son manifeste.
        assertFalse(NettoyageAccueil.presente(etat(manifeste = false)))
    }

    @Test fun sans_lanceur_absente() {
        assertFalse(NettoyageAccueil.presente(etat(lancable = false)))
    }

    @Test fun constantes_alignees_sur_android() {
        // PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, _ENABLED, _DISABLED, _DISABLED_USER, _DISABLED_UNTIL_USED.
        assertEquals(listOf(0, 1, 2, 3, 4), listOf(NettoyageAccueil.ACTIVATION_DEFAUT, NettoyageAccueil.ACTIVEE,
            NettoyageAccueil.DESACTIVEE, NettoyageAccueil.DESACTIVEE_PAR_UTILISATEUR, NettoyageAccueil.DESACTIVEE_JUSQU_A_USAGE))
    }

    // ------------------------------------------------------------------ dock

    private val tel = "com.android.dialer"
    private val sms = "com.google.android.apps.messaging"
    private val chrome = "com.android.chrome"
    private val photo = "com.android.camera2"

    @Test fun dock_par_defaut_sans_l_appli_partie() {
        val presentes = mutableSetOf(tel, sms, chrome, photo)
        val defaut = { listOf(tel, sms, chrome, photo) }
        assertEquals(listOf(tel, sms, chrome), NettoyageAccueil.dockAffiche(null, defaut) { it in presentes })
        presentes.remove(chrome)
        // Le système résout encore le navigateur sur Chrome un instant : il ne reste pas au dock.
        assertEquals(listOf(tel, sms, photo), NettoyageAccueil.dockAffiche(null, defaut) { it in presentes })
        // Réinstallée : elle revient dans le dock par défaut.
        presentes.add(chrome)
        assertEquals(listOf(tel, sms, chrome), NettoyageAccueil.dockAffiche(null, defaut) { it in presentes })
    }

    @Test fun dock_choisi_sans_l_appli_partie_ni_doublon() {
        val choisi = listOf(chrome, tel, chrome, sms)
        assertEquals(listOf(tel, sms), NettoyageAccueil.dockAffiche(choisi, { listOf(photo) }) { it != chrome })
        assertEquals(listOf(chrome, tel, sms), NettoyageAccueil.dockAffiche(choisi, { listOf(photo) }) { true })
        // Dock choisi vide : il reste vide (le dock par défaut ne revient pas).
        assertEquals(emptyList<String>(), NettoyageAccueil.dockAffiche(emptyList(), { listOf(photo) }) { true })
    }

    @Test fun dock_par_defaut_sans_doublon_ni_plus_de_trois() {
        assertEquals(listOf(tel, sms, photo), NettoyageAccueil.dockAffiche(null, { listOf(tel, tel, sms, photo, chrome) }) { true })
    }

    // ------------------------------------------------------------------ suivi de présence (trois voies)

    private val gms = "com.google.android.gms"

    @Test fun paquet_sans_lanceur_jamais_present_modifie_repete_aucun_rebatissage() {
        val suivi = NettoyageAccueil.SuiviPresence(listOf(tel, sms, chrome))
        // Services Google Play : « modifié » toutes les 3 à 5 s, toujours absent (pas de lanceur), rien sur l'accueil.
        repeat(50) { assertFalse(suivi.constat(gms, presente = false, accueilChange = false)) }
        assertFalse(suivi.etaitPresente(gms))
    }

    @Test fun desinstallation_puis_reinstallation_rebatissent_une_fois_chacune() {
        val suivi = NettoyageAccueil.SuiviPresence(listOf(tel, sms, chrome))
        assertTrue(suivi.constat(chrome, presente = false, accueilChange = true))
        // La même désinstallation vue par la diffusion puis par le relevé : plus rien.
        assertFalse(suivi.constat(chrome, presente = false, accueilChange = false))
        assertFalse(suivi.constat(chrome, presente = false, accueilChange = false))
        assertTrue(suivi.constat(chrome, presente = true, accueilChange = false))
        assertFalse(suivi.constat(chrome, presente = true, accueilChange = false))
    }

    @Test fun appli_presente_modifiee_sans_changement_de_presence_ignoree() {
        val suivi = NettoyageAccueil.SuiviPresence(listOf(chrome))
        repeat(10) { assertFalse(suivi.constat(chrome, presente = true, accueilChange = false)) }
    }

    @Test fun absente_jamais_vue_mais_restee_sur_l_accueil_rebatit() {
        // Absente mais encore mentionnée par l'accueil enregistré (nettoyage manqué) : le nettoyage l'enlève, on rebâtit.
        val suivi = NettoyageAccueil.SuiviPresence(emptyList())
        assertTrue(suivi.constat(chrome, presente = false, accueilChange = true))
    }

    @Test fun accueil_concerne_par_un_paquet() {
        val e = NettoyageAccueil.EtatAccueil(listOf(Accueil.Place(chrome, 0, 0, 1, 1)), listOf(Accueil.Dossier("aa", "D", mutableListOf(sms))),
            emptyList(), listOf(tel), emptySet(), emptySet())
        assertTrue(NettoyageAccueil.concerne(chrome, e))
        assertTrue(NettoyageAccueil.concerne(sms, e))
        assertTrue(NettoyageAccueil.concerne(tel, e))
        assertFalse(NettoyageAccueil.concerne(gms, e))
    }

    // ------------------------------------------------------------------ quand rebâtir

    @Test fun rebatir_tout_de_suite_au_premier_plan() {
        assertEquals(NettoyageAccueil.Maj.MAINTENANT, NettoyageAccueil.quandRebatir(true, false, null))
    }

    @Test fun cache_au_retour() {
        assertEquals(NettoyageAccueil.Maj.AU_RETOUR, NettoyageAccueil.quandRebatir(false, true, 10))
        assertEquals(NettoyageAccueil.Maj.AU_RETOUR, NettoyageAccueil.quandRebatir(false, false, null))
    }

    @Test fun glisser_en_cours_on_reessaie_sans_perdre_le_changement() {
        assertEquals(NettoyageAccueil.Maj.REESSAYER, NettoyageAccueil.quandRebatir(true, true, null))
    }

    @Test fun touche_tenue_on_attend_mais_pas_indefiniment() {
        assertEquals(NettoyageAccueil.Maj.REESSAYER, NettoyageAccueil.quandRebatir(true, false, 200))
        assertEquals(NettoyageAccueil.Maj.MAINTENANT, NettoyageAccueil.quandRebatir(true, false, NettoyageAccueil.ATTENTE_TOUCHE_MS))
    }
}
