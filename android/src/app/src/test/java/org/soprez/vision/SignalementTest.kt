// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.graphics.Bitmap
import android.graphics.Color
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile

/** « Signaler un problème » : le contenu du paquet, ses limites, et l'absence de tout identifiant. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SignalementTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    private fun brouillon(caps: List<ByteArray> = emptyList(), appli: String = "ligne appli", ha: String = "ligne ha") =
        Signalement.Brouillon(1_000L, 2_000L, appli, ha, Signalement.fiche(ctx), 3, caps, "ça plante")

    private fun zipDe(f: File): Map<String, ByteArray> = ZipFile(f).use { z -> z.entries().asSequence().associate { it.name to z.getInputStream(it).readBytes() } }

    @Test fun le_paquet_contient_le_manifeste_les_journaux_et_les_captures() {
        val f = File.createTempFile("sig", ".zip")
        val jpeg = CaptureEcran.jpeg(Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888))
        Signalement.assembler(brouillon(listOf(jpeg, jpeg)), f)
        val z = zipDe(f)
        assertEquals(setOf("manifeste.json", "journal_appli.txt", "journal_ha.txt", "captures/01.jpg", "captures/02.jpg"), z.keys)
        val m = JSONObject(String(z.getValue("manifeste.json")))
        assertEquals(1, m.getInt("version")); assertTrue(m.isNull("reference"))
        assertEquals(3, m.getJSONObject("anonymisation").getInt("remplacements"))
        assertEquals("ça plante", m.getString("description"))
        assertEquals(4, m.getJSONArray("parties").length())
        // Gardé pour le contrôle de bout en bout contre le serveur du site (build/tmp, ignoré par git).
        File("build/tmp").mkdirs(); f.copyTo(File("build/tmp/signalement-essai.zip"), overwrite = true)
        f.delete()
    }

    @Test fun un_journal_ha_absent_n_est_pas_dans_le_paquet() {
        val f = File.createTempFile("sig", ".zip")
        Signalement.assembler(brouillon(ha = ""), f)
        assertFalse("journal_ha.txt" in zipDe(f).keys)
        f.delete()
    }

    @Test fun aucun_identifiant_dans_le_paquet() {
        val cfg = Config(ctx); cfg.id = "pc-essai-un"; cfg.secret = "valeur essai appareil"
        val f = File.createTempFile("sig", ".zip")
        Signalement.assembler(brouillon(), f)
        val tout = zipDe(f).values.joinToString("\n") { String(it, Charsets.ISO_8859_1) }
        val androidId = Identite.uid(ctx)
        assertFalse(tout.contains(cfg.secret)); assertFalse(tout.contains(cfg.id))
        if (androidId.isNotEmpty()) assertFalse(tout.contains(androidId))
        val fiche = Signalement.fiche(ctx)
        for (interdit in listOf("id", "secret", "uid", "mac", "ssid", "token")) assertFalse(interdit, fiche.has(interdit))
        f.delete()
    }

    @Test fun au_dela_de_la_limite_les_captures_partent_puis_le_debut_des_journaux() {
        val gros = ByteArray(300_000).also { java.util.Random(7).nextBytes(it) }
        val f = File.createTempFile("sig", ".zip")
        val b = brouillon(listOf(gros, gros, gros), appli = "a".repeat(10) + "\n" + "b".repeat(60_000))
        val taille = Signalement.assembler(b, f, max = 200_000)
        assertTrue("$taille", taille <= 200_000)
        val z = zipDe(f)
        assertTrue(z.keys.none { it.startsWith("captures/") })
        assertTrue(String(z.getValue("journal_appli.txt")).endsWith("b"))
        f.delete()
    }

    @Test fun une_capture_est_reduite_a_1280_de_large_et_300_ko() {
        val bmp = Bitmap.createBitmap(2560, 1440, Bitmap.Config.ARGB_8888)
        val rnd = java.util.Random(1)
        for (y in 0 until 1440 step 3) for (x in 0 until 2560 step 3) bmp.setPixel(x, y, Color.rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)))
        val octets = CaptureEcran.jpeg(bmp)
        assertTrue("${octets.size}", octets.size <= CaptureEcran.OCTETS_MAX)
        val d = android.graphics.BitmapFactory.decodeByteArray(octets, 0, octets.size)
        assertEquals(1280, d.width); assertEquals(720, d.height)
    }

    @Test fun la_duree_est_limitee_a_dix_minutes_et_cinq_captures() {
        Signalement.commencer(1_000L)
        assertFalse(Signalement.expire(1_000L + 599_999L)); assertTrue(Signalement.expire(1_000L + 600_000L))
        repeat(Signalement.MAX_CAPTURES) { assertTrue(Signalement.ajouterCapture(File("c$it.jpg"))) }
        assertFalse(Signalement.ajouterCapture(File("trop.jpg")))
        Signalement.oublier()
        assertFalse(Signalement.enCours)
    }

    @Test fun les_references_envoyees_se_gardent_et_s_effacent_de_la_liste() {
        assertTrue(Signalement.references(ctx).isEmpty())
        val p = ctx.getSharedPreferences("signalements", android.content.Context.MODE_PRIVATE)
        p.edit().putString("liste", "[{\"ref\":\"VIS-1\",\"lien\":\"https://exemple.test/e\"}]").commit()
        assertEquals(listOf("VIS-1" to "https://exemple.test/e"), Signalement.references(ctx))
    }

    @Test fun les_touches_de_la_tele_comptent_seulement_pour_ok_et_menu() {
        assertTrue(PastilleSignalement.touchesTele(android.view.KeyEvent.KEYCODE_DPAD_CENTER))
        assertTrue(PastilleSignalement.touchesTele(android.view.KeyEvent.KEYCODE_MENU))
        assertFalse(PastilleSignalement.touchesTele(android.view.KeyEvent.KEYCODE_BACK))
    }
}
