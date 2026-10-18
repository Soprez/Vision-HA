// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.os.SystemClock
import android.test.InstrumentationTestCase

/**
 * Client Sendspin natif, lot 5 : l'interrupteur entre les deux moteurs et le moteur Sendspin relié au vrai Music
 * Assistant (hôte passé par `-e hote 10.1.1.x` ; sans hôte, seul l'interrupteur est essayé). Le lecteur porte le
 * nom d'essai « Essai Sendspin natif · Vision » ; la config de l'appareil est remise comme avant à la fin.
 *
 *   adb shell am instrument -w -e class org.soprez.vision.LecteurSendspinTest -e hote 10.1.1.11 \
 *       org.soprez.vision.test/android.test.InstrumentationTestRunner
 */
class LecteurSendspinTest : InstrumentationTestCase() {
    private val ctx get() = instrumentation.targetContext
    private var avant: Triple<String, String, String>? = null

    override fun setUp() {
        super.setUp()
        val c = Config(ctx)
        avant = Triple(c.urlInterne, c.nom, LecteurMusique.moteur(ctx))
    }

    override fun tearDown() {
        LecteurMusique.arreter()
        avant?.let { (u, n, m) -> Config(ctx).apply { urlInterne = u; nom = n }; ctx.getSharedPreferences("lecteur", 0).edit().putString("moteur", m).apply() }
        super.tearDown()
    }

    private fun hote(): String? = (instrumentation as? android.test.InstrumentationTestRunner)?.arguments?.getString("hote")

    fun testInterrupteurUnSeulMoteurALaFois() {
        // Juste après l'installation, le service de Vision peut démarrer son lecteur en même temps : on part d'un arrêt net.
        instrumentation.waitForIdleSync(); LecteurMusique.arreter()
        ctx.getSharedPreferences("lecteur", 0).edit().putString("moteur", LecteurMusique.SLIM).commit()
        assertEquals(LecteurMusique.SLIM, LecteurMusique.moteur(ctx))
        LecteurMusique.demarrer(ctx)
        assertFalse(LecteurSendspin.actif)
        LecteurMusique.poserMoteur(ctx, LecteurMusique.SENDSPIN)
        assertEquals(LecteurMusique.SENDSPIN, LecteurMusique.moteur(ctx))
        // Sur une télé, le nouveau moteur part aussitôt et l'ancien est arrêté.
        if (ReglagesTvActivity.estTele(ctx)) assertTrue(LecteurSendspin.actif)
        LecteurMusique.poserMoteur(ctx, LecteurMusique.SLIM)
        assertFalse(LecteurSendspin.actif)
        assertEquals("arrêt", LecteurSendspin.etat)
    }

    fun testReliéAuVraiMusicAssistant() {
        val h = hote() ?: return
        Config(ctx).apply { urlInterne = "http://$h:8123"; nom = "Essai Sendspin natif" }
        assertEquals("Essai Sendspin natif · Vision", LecteurMusique.nom(ctx))
        ctx.getSharedPreferences("lecteur", 0).edit().putString("moteur", LecteurMusique.SENDSPIN).apply()
        LecteurMusique.demarrer(ctx)
        val fin = SystemClock.elapsedRealtime() + 15_000
        while (!LecteurMusique.connecte && SystemClock.elapsedRealtime() < fin) SystemClock.sleep(200)
        assertTrue("pas relié à Music Assistant ($h:8927)", LecteurMusique.connecte)
        // Aucune position tant que rien ne joue ; jamais celle d'un autre lecteur.
        assertNull(LecteurMusique.positionLocale(ctx, false, "Salon · Sonos"))
    }
}
