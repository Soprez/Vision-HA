// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Intent
import android.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * 1.93.06 (Déverrouillage simplifié) : un responsable déverrouille cet appareil à son initiative. L'approbation
 * signée par Home Assistant (version 2, ECDSA P-256 : la même clé que les approbations de demande) arrive par le
 * relevé ; elle ouvre l'accès total pour la durée choisie, une seule fois, sur CET appareil seulement. « Reverrouiller »
 * et un redémarrage referment tout. Le côté de HA : essai/deverrouillage_initiative_test.py.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeverrouillageInitiativeTest {
    private val ctx get() = RuntimeEnvironment.getApplication()
    private lateinit var cles: KeyPair
    private lateinit var k: String

    @Before fun preparer() {
        cles = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val pub = Base64.encodeToString(cles.public.encoded, Base64.NO_WRAP)
        k = MessageDigest.getInstance("SHA-256").digest(cles.public.encoded).joinToString("") { "%02x".format(it) }.take(16)
        org.soprez.vision.Config(ctx).apply { id = "tel-lou"; secret = "s".repeat(32); parentJusqua = 0L }
        Deverrouillage.releve(ctx, JSONObject().put("deverrouillage_cle", JSONObject().put("k", k).put("pub", pub)))
    }

    /** Ce que Home Assistant envoie (deverrouillage.initiative). */
    private fun approbation(appareil: String = "tel-lou", minutes: Int = 30, version: String = Deverrouillage.VERSION_INITIATIVE,
                            demande: String = "init-" + System.nanoTime(), expire: Long = System.currentTimeMillis() / 1000 + 600,
                            action: String = "parent", signerPour: String = appareil): JSONObject {
        val msg = Deverrouillage.message(version, signerPour, demande, action, minutes, expire, Deverrouillage.DEFI_INITIATIVE)
        val sig = Signature.getInstance("SHA256withECDSA").run { initSign(cles.private); update(msg); sign() }
        return JSONObject().put("k", k).put("appareil", appareil).put("demande", demande).put("action", action)
            .put("minutes", minutes).put("expire", expire).put("par", "Nolan").put("remise", "directe").put("version", version)
            .put("signature", Base64.encodeToString(sig, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
    }

    @Test fun l_approbation_ouvre_l_acces_total_pour_la_duree() {
        assertFalse(Deverrouillage.accesTotal(ctx))
        val msg = Deverrouillage.directe(ctx, approbation(minutes = 30))
        assertNotNull(msg); assertTrue(msg!!, msg.contains("Nolan") && msg.contains("30"))
        assertTrue(Deverrouillage.accesTotal(ctx))
        assertTrue(org.soprez.vision.Config(ctx).modeParent)  // réglages d'Android, installations, plus d'écran de blocage
        assertEquals(30, Deverrouillage.minutesRestantes(ctx))
        assertEquals(ctx.getString(R.string.bandeau_deverrouille, 30), BandeauDeverrouille.libelle(ctx, 30))
        assertNull(BandeauDeverrouille.libelle(ctx, 0))
    }

    @Test fun rejouee_elle_ne_sert_plus() {
        val a = approbation(minutes = 15)
        assertNotNull(Deverrouillage.directe(ctx, a))
        Deverrouillage.reverrouiller(ctx, "essai")
        assertNull("rejouée par le relevé : ignorée", Deverrouillage.directe(ctx, a))
        assertNotNull(Deverrouillage.verifierInitiative(ctx, a))
        assertFalse(Deverrouillage.accesTotal(ctx))
    }

    @Test fun refusee_ailleurs_ou_hors_contrat() {
        // Un autre appareil (approbation volée), une signature pour un autre appareil, une approbation de demande (v1).
        assertNotNull(Deverrouillage.verifierInitiative(ctx, approbation(appareil = "tel-noa")))
        assertNotNull(Deverrouillage.verifierInitiative(ctx, approbation(signerPour = "tel-noa")))
        assertNotNull(Deverrouillage.verifierInitiative(ctx, approbation(version = "1")))
        // Expirée, durée hors 15/30/60, autre action, identifiant qui n'est pas une initiative.
        assertNotNull(Deverrouillage.verifierInitiative(ctx, approbation(expire = System.currentTimeMillis() / 1000 - 5)))
        assertNotNull(Deverrouillage.verifierInitiative(ctx, approbation(minutes = 20)))
        assertNotNull(Deverrouillage.verifierInitiative(ctx, approbation(action = "reglages")))
        assertNotNull(Deverrouillage.verifierInitiative(ctx, approbation(demande = "abc")))
        // Une autre clé.
        val autre = approbation().put("k", "0000000000000000")
        assertNotNull(Deverrouillage.verifierInitiative(ctx, autre))
        assertFalse(Deverrouillage.accesTotal(ctx))
        // La bonne passe.
        assertNull(Deverrouillage.verifierInitiative(ctx, approbation()))
    }

    @Test fun reverrouiller_et_redemarrage() {
        Deverrouillage.directe(ctx, approbation(minutes = 60))
        assertTrue(Deverrouillage.accesTotal(ctx))
        Deverrouillage.reverrouiller(ctx, "bandeau")
        assertFalse(Deverrouillage.accesTotal(ctx)); assertFalse(Deverrouillage.reglagesOuverts(ctx))
        // Redémarrage : reverrouillé ; mise à jour de Vision : non.
        Deverrouillage.directe(ctx, approbation(minutes = 60))
        BootReceiver().onReceive(ctx, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertTrue("une mise à jour de Vision ne reverrouille pas", Deverrouillage.accesTotal(ctx))
        BootReceiver().onReceive(ctx, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertFalse("un redémarrage reverrouille toujours", Deverrouillage.accesTotal(ctx))
    }

    @Test fun le_responsable_doit_prouver_a_chaque_fois() {
        assertTrue(Octroi.estOctroi("https://ha/api/pc_parental/deverrouillage/initiative", "", "", 15))
        assertTrue(Octroi.estOctroi("/api/pc_parental/deverrouillage/initiative", "", "deverrouiller", 30))
        assertFalse("annuler n'accorde rien", Octroi.estOctroi("/api/pc_parental/deverrouillage/initiative", "", "annuler", 0))
    }
}
