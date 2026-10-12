// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.test.InstrumentationTestCase
import org.json.JSONObject

/**
 * Soutenir le projet : la clé `soutien` du relevé est lue (actif avec code ou sur l'honneur, inactif, absente
 * sur un ancien Home Assistant) et retenue avec le reste de l'état.
 *
 *   adb shell am instrument -w -e class org.soprez.vision.SoutienTest org.soprez.vision.test/android.test.InstrumentationTestRunner
 */
class SoutienTest : InstrumentationTestCase() {
    private fun releve(soutien: JSONObject?): JSONObject =
        JSONObject().put("action", "allow").put("poll", 20).apply { if (soutien != null) put("soutien", soutien) }

    fun testLecture() {
        val code = Soutien.lire(JSONObject("""{"actif": true, "mode": "code"}"""))
        assertTrue(code.actif); assertEquals("code", code.mode)
        val honneur = Soutien.lire(JSONObject("""{"actif": true, "mode": "honneur"}"""))
        assertTrue(honneur.actif); assertEquals("honneur", honneur.mode)
        assertFalse(Soutien.lire(JSONObject("""{"actif": false}""")).actif)
        assertFalse(Soutien.lire(JSONObject("{}")).actif)
        assertFalse(Soutien.lire(null).actif)
    }

    fun testReleve() {
        val avant = Etat.soutien
        try {
            Etat.appliquer(releve(JSONObject().put("actif", true).put("mode", "code")), sauver = null)
            assertTrue(Etat.soutien.actif); assertTrue(Soutien.actif())
            Etat.appliquer(releve(JSONObject().put("actif", false)), sauver = null)
            assertFalse(Etat.soutien.actif)
            Etat.appliquer(releve(JSONObject().put("actif", true).put("mode", "honneur")), sauver = null)
            Etat.appliquer(releve(null), sauver = null)
            assertFalse("clé absente : inactif", Etat.soutien.actif)
        } finally { Etat.soutien = avant }
    }

    /** Hors ligne : le relevé gardé (texte JSON) redonne le soutien au redémarrage. */
    fun testReleveGarde() {
        val avant = Etat.soutien
        try {
            val garde = releve(JSONObject().put("actif", true).put("mode", "honneur")).toString()
            Etat.soutien = Soutien.INACTIF
            Etat.appliquer(JSONObject(garde), sauver = null)
            assertTrue(Etat.soutien.actif); assertEquals("honneur", Etat.soutien.mode)
        } finally { Etat.soutien = avant }
    }

    fun testTextes() {
        val ctx = instrumentation.targetContext
        assertFalse(ctx.getString(R.string.soutien_titre).isEmpty())
        assertTrue(ctx.getString(R.string.soutien_badge).startsWith("♥"))
        assertEquals("buymeacoffee.com/soprez", ctx.getString(R.string.soutien_lien))
        assertTrue(Soutien.LIEN.endsWith(ctx.getString(R.string.soutien_lien)))
    }
}
