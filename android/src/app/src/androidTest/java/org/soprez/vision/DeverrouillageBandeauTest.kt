// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * 1.93.06 (Déverrouillage simplifié) sur le banc : une approbation à l'initiative d'un responsable (signée ici avec une
 * clé d'essai, à la place de celle de HA : le prochain relevé la remplace) ouvre l'accès total ; le bandeau
 * « Déverrouillé, reste 15 min · Reverrouiller » s'affiche par-dessus les applis. Le banc (shell) vérifie le bandeau et
 * les paramètres d'Android, puis touche « Reverrouiller » : l'essai attend le reverrouillage (120 s au plus).
 *
 *   adb shell am instrument -w -e class org.soprez.vision.DeverrouillageBandeauTest org.soprez.vision.test/android.test.InstrumentationTestRunner
 */
class DeverrouillageBandeauTest : InstrumentationTestCase() {
    private val ctx: Context get() = instrumentation.targetContext

    private fun attendre(ms: Long, condition: () -> Boolean): Boolean {
        val fin = SystemClock.uptimeMillis() + ms
        while (SystemClock.uptimeMillis() < fin) { if (condition()) return true; SystemClock.sleep(250) }
        return condition()
    }

    fun testBandeauEtReverrouiller() {
        assertTrue("Vision n'est pas inscrit sur le banc", Config(ctx).inscrit)
        Deverrouillage.reverrouiller(ctx, "essai")
        val cles = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val k = MessageDigest.getInstance("SHA-256").digest(cles.public.encoded).joinToString("") { "%02x".format(it) }.take(16)
        Deverrouillage.releve(ctx, JSONObject().put("deverrouillage_cle", JSONObject().put("k", k)
            .put("pub", Base64.encodeToString(cles.public.encoded, Base64.NO_WRAP))))
        val id = "init-essai-" + System.currentTimeMillis()
        val expire = System.currentTimeMillis() / 1000 + 600
        val msg = Deverrouillage.message(Deverrouillage.VERSION_INITIATIVE, Config(ctx).id, id, "parent", 15, expire, Deverrouillage.DEFI_INITIATIVE)
        val sig = Signature.getInstance("SHA256withECDSA").run { initSign(cles.private); update(msg); sign() }
        val a = JSONObject().put("k", k).put("appareil", Config(ctx).id).put("demande", id).put("action", "parent").put("minutes", 15)
            .put("expire", expire).put("par", "Banc").put("remise", "directe").put("version", "2")
            .put("signature", Base64.encodeToString(sig, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
        val retour = Deverrouillage.directe(ctx, a)
        assertNotNull(retour)
        assertTrue("pas d'accès total ($retour)", Deverrouillage.accesTotal(ctx))
        Log.i("VisionEssai", "ESSAI-BANDEAU-PRET $retour")
        assertTrue("le banc n'a pas touché « Reverrouiller »", attendre(120_000) { !Deverrouillage.accesTotal(ctx) })
        Log.i("VisionEssai", "ESSAI-BANDEAU-REVERROUILLE")
    }
}
