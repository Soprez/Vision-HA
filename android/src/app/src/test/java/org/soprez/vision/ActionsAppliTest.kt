// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.78.1 : sans décideur, la page système d'une appli ne laisse passer que « Forcer l'arrêt » et « Vider le cache ». */
class ActionsAppliTest {
    @Test
    fun boutonsLibres() {
        for (t in listOf("Clear cache 487 kB", "Vider le cache", "Force stop", "Forcer l’arrêt", "Clear cache?", "Force stop? If you force stop an app, it may misbehave."))
            assertFalse(t, ActionsAppli.interdit(t))
    }

    @Test
    fun boutonsInterdits() {
        for (t in listOf("Clear data 1.11 MB", "Effacer les données", "Uninstall", "Désinstaller", "Disable", "Désactiver",
            "Permissions", "Autorisations", "Clear data? All of this app's data will be deleted permanently.", "Clear storage"))
            assertTrue(t, ActionsAppli.interdit(t))
    }

    @Test
    fun rienNEstInterditSansTexte() {
        assertFalse(ActionsAppli.interdit(""))
        assertFalse(ActionsAppli.interdit("Storage used 631 kB used in Internal shared storage"))
    }

    @Test
    fun pagesAFermer() {
        // La confirmation d'effacement de la télé : à refermer ; la page de l'appli (qui a aussi « Vider le cache ») : non.
        assertTrue(ActionsAppli.pageInterdite("YouTube Clear data All this app’s data will be deleted permanently. OK Cancel"))
        assertTrue(ActionsAppli.pageInterdite("Disable app? If you disable this app, Android and other apps may no longer function as intended. OK Cancel"))
        assertFalse(ActionsAppli.pageInterdite("YouTube Open Force stop Uninstall updates Disable Storage used Clear data Clear cache 487 kB Clear defaults Notifications Permissions"))
        assertFalse(ActionsAppli.pageInterdite("Clear cache? OK Cancel"))
    }

    // ---- Lot accueil (point 7) : sans code quand l'accessibilité filtre la page, code sinon.

    @Test
    fun voieSelonApiEtAccessibilite() {
        // Avant Android 14, « Forcer l'arrêt » : Vision arrête l'appli elle-même, sans page.
        assertEquals(ActionsAppli.Voie.DIRECT, ActionsAppli.voie(arretDirect = true, accessibilite = false, decideur = false))
        // Android 14+ ou « Vider le cache » : page restreinte sans code si le service tourne…
        assertEquals(ActionsAppli.Voie.RESTREINT, ActionsAppli.voie(arretDirect = false, accessibilite = true, decideur = false))
        // … sinon rien ne filtrerait Désinstaller, Effacer les données : code (repli sûr).
        assertEquals(ActionsAppli.Voie.CODE, ActionsAppli.voie(arretDirect = false, accessibilite = false, decideur = false))
        // Décideur déjà reconnu : la page entière.
        assertEquals(ActionsAppli.Voie.CODE, ActionsAppli.voie(arretDirect = false, accessibilite = true, decideur = true))
    }

    @Test
    fun pageStockageDuTelephone() {
        // Android 12+ : « Vider le cache » est dans « Stockage et cache », à côté de « Vider l'espace de stockage ».
        for (t in listOf("Stockage et cache", "Storage & cache", "Vider le cache", "Clear cache"))
            assertFalse(t, ActionsAppli.interdit(t))
        for (t in listOf("Vider l'espace de stockage", "Effacer le stockage", "Clear storage", "Gérer l'espace", "Manage space"))
            assertTrue(t, ActionsAppli.interdit(t))
        assertFalse(ActionsAppli.pageInterdite("Stockage et cache Espace utilisé 12 Mo Vider l'espace de stockage Vider le cache"))
        assertTrue(ActionsAppli.pageInterdite("Effacer les données de l'application ? Les données de cette application seront définitivement supprimées de cet appareil. Annuler OK"))
        assertTrue(ActionsAppli.pageInterdite("Clear app data? This app’s data, including files and settings, will be permanently deleted from this device. Cancel Delete"))
    }

    @Test
    fun boutonsFrancaisEtAccesSpeciaux() {
        for (t in listOf("Désinstaller les mises à jour", "Uninstall updates", "Désactiver l'application ?", "Si vous désactivez cette application",
            "Afficher par-dessus les autres applis", "Display over other apps", "Modifier les paramètres système", "Install unknown apps", "Installer des applis inconnues"))
            assertTrue(t, ActionsAppli.interdit(t))
        assertFalse(ActionsAppli.interdit("Forcer l'arrêt ? Si vous forcez l'arrêt d'une application, cela peut provoquer son dysfonctionnement."))
        assertTrue(ActionsAppli.pageInterdite("Autorisations des applications Appareil photo Micro"))
        assertFalse(ActionsAppli.pageInterdite("YouTube Ouvrir Forcer l'arrêt Désinstaller Désactiver Espace de stockage utilisé Effacer les données Vider le cache Autorisations"))
    }

    @Test
    fun leModeRestreintFinitQuandOnQuitteLesReglages() {
        val v = "org.soprez.vision"
        assertFalse(ActionsAppli.finRestreint("com.android.settings", reglages = true, vision = v, depuisOuvertureMs = 60_000))
        assertFalse(ActionsAppli.finRestreint("com.google.android.inputmethod.latin", reglages = false, vision = v, depuisOuvertureMs = 60_000))
        assertFalse(ActionsAppli.finRestreint("com.android.systemui", reglages = false, vision = v, depuisOuvertureMs = 60_000))
        assertTrue(ActionsAppli.finRestreint("com.google.android.youtube.tv", reglages = false, vision = v, depuisOuvertureMs = 500))
        // Retour à Vision : fini, sauf dans l'instant où Vision ouvre la page.
        assertTrue(ActionsAppli.finRestreint(v, reglages = false, vision = v, depuisOuvertureMs = 5_000))
        assertFalse(ActionsAppli.finRestreint(v, reglages = false, vision = v, depuisOuvertureMs = 500))
    }
}
