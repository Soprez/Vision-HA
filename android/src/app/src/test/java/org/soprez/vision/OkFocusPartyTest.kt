// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.app.AlertDialog
import android.view.KeyEvent
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import org.soprez.vision.captures.FauxHa
import org.soprez.vision.captures.Recette
import java.util.concurrent.TimeUnit

/**
 * 1.93.06.1 (l'auteur, 19/10 : « clics fantômes » — un appui qui agit sur un autre élément que celui sélectionné, une action qui
 * part toute seule ; même famille que « je clique sur Jump Around, c'est The Golden Path qui s'affiche »).
 *
 * L'écran Party de la télé se rebâtit à chaque relecture de Home Assistant (titre suivant : la file monte d'une ligne).
 * Règles vérifiées ici, sur une longue file, avec Home Assistant simulé ([FauxHa]) :
 * - un rafraîchissement ne déplace pas le focus : la ligne du MÊME titre (même identifiant) reste focalisée, même si
 *   son rang a changé ;
 * - un OK enfoncé sur un titre, relâché après un rafraîchissement, ouvre le menu de CE titre ;
 * - un OK dont le focus a changé d'élément pendant l'appui ne fait rien ;
 * - un rafraîchissement ne rejoue jamais un appui : aucun menu ne s'ouvre tout seul.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OkFocusPartyTest {
    private val tele = Recette.TAILLES.first { it.nom == "tv-1080p" }
    @Volatile private var enCours = 3
    private val ha = FauxHa()

    private fun titre(k: Int) = JSONObject().put("titre", "Titre $k").put("artiste", "Artiste $k").put("id", "q$k")

    /** Une file de 900 titres : 10 passés, le titre en cours, 30 à suivre (ce que Home Assistant envoie à l'écran). */
    private fun etat(): JSONObject = JSONObject().put("ma", true).put("ici", true).put("actif", true)
        .put("lecteur", "media_player.salon").put("lecteur_nom", "Salon").put("piloter", true).put("relire", 2)
        .put("maintenant", JSONObject().put("etat", "playing").put("titre", "Titre $enCours").put("artiste", "Artiste $enCours")
            .put("volume", 0.4).put("duree", 214.0).put("position", 5.0))
        .put("file_passes", JSONArray((maxOf(0, enCours - 10) until enCours).map { titre(it) }))
        .put("file", JSONArray((enCours + 1..enCours + 30).map { titre(it) }))
        .put("file_total", 900 - enCours - 1)

    @Before fun avant() {
        Recette.poser(tele, 1.0f)
        val ctx = RuntimeEnvironment.getApplication()
        ZoneSure.poser(ctx, 0, 0)
        Config(ctx).apply { id = "essai_tele"; secret = "s".repeat(32); urlInterne = ha.adresse; urlExterne = ""; urlActive = "" }
        ha.reponses["/api/pc_parental/party/etat"] = { etat() }
        ha.reponses["/api/pc_parental/party/commande"] = { JSONObject().put("ok", true).put("retour", "C'est parti.") }
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
    }

    @After fun apres() { ha.close() }

    private fun ouvrir(): Activity {
        val a = Robolectric.buildActivity(PartyActivity::class.java).setup().visible().get()
        attendreTitre(a, enCours)
        ShadowLooper.idleMainLooper(600, TimeUnit.MILLISECONDS)
        return a
    }

    private fun attendreTitre(a: Activity, k: Int) {
        val fin = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < fin) {
            if ((a.window.decorView.findViewWithTag<View>("lecteur_titre") as? TextView)?.text?.toString() == "Titre $k") return
            ShadowLooper.idleMainLooper(250, TimeUnit.MILLISECONDS); Thread.sleep(30)
        }
        throw AssertionError("le titre $k n'est pas arrivé à l'écran")
    }

    private fun ligne(a: Activity, id: String): View? = a.window.decorView.findViewWithTag("file:$id")
    private fun rang(a: Activity, v: View?): Int {
        val col = a.window.decorView.findViewWithTag<ScrollView>("lecteur_file").getChildAt(0) as android.view.ViewGroup
        return (0 until col.childCount).firstOrNull { col.getChildAt(it) === v } ?: -1
    }
    private fun focus(a: Activity): View? = a.window.decorView.findFocus()
    private fun ok(a: Activity, action: Int) { a.dispatchKeyEvent(KeyEvent(action, KeyEvent.KEYCODE_DPAD_CENTER)); shadowOf(android.os.Looper.getMainLooper()).idle() }
    private fun titreDuDialogue(): String? = (ShadowDialog.getLatestDialog() as? AlertDialog)?.takeIf { it.isShowing }?.let { shadowOf(it).title?.toString() }

    @Test fun le_focus_reste_sur_le_meme_titre_quand_la_file_monte() {
        val a = ouvrir()
        val l10 = ligne(a, "q10") ?: throw AssertionError("ligne q10 absente")
        l10.requestFocus(); shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("file:q10", focus(a)?.tag)
        val rangAvant = rang(a, l10)
        // Titre suivant : Home Assistant renvoie l'état, l'écran se rebâtit et la file monte d'une ligne.
        enCours = 4
        attendreTitre(a, 4)
        ShadowLooper.idleMainLooper(800, TimeUnit.MILLISECONDS)
        val f = focus(a)
        assertEquals("le focus reste sur le MÊME titre (même identifiant), pas sur le même rang", "file:q10", f?.tag)
        assertTrue("la ligne a été rebâtie", f !== l10)
        // Dix titres passés affichés avant comme après : le rang de la ligne reste le même (ou baisse d'un cran s'il y en avait moins).
        assertTrue("rang ${rang(a, f)} (avant : $rangAvant)", rang(a, f) in (rangAvant - 1)..rangAvant)
        assertNull("aucun menu ne s'ouvre tout seul au rafraîchissement", titreDuDialogue())
    }

    @Test fun un_ok_commence_sur_un_titre_vaut_pour_ce_titre_apres_un_rafraichissement() {
        val a = ouvrir()
        val l12 = ligne(a, "q12") ?: throw AssertionError("ligne q12 absente")
        l12.requestFocus(); shadowOf(android.os.Looper.getMainLooper()).idle()
        ok(a, KeyEvent.ACTION_DOWN)
        // Entre l'enfoncement et le relâché : titre suivant, écran rebâti (la ligne q12 est une nouvelle vue).
        enCours = 4
        attendreTitre(a, 4)
        ShadowLooper.idleMainLooper(800, TimeUnit.MILLISECONDS)
        assertTrue(ligne(a, "q12") !== l12)
        ok(a, KeyEvent.ACTION_UP)
        assertEquals("le menu ouvert est celui du titre choisi à l'enfoncement", "Titre 12", titreDuDialogue())
    }

    @Test fun un_ok_dont_le_focus_a_change_d_element_pendant_l_appui_ne_fait_rien() {
        val a = ouvrir()
        val l12 = ligne(a, "q12") ?: throw AssertionError("ligne q12 absente")
        l12.requestFocus(); shadowOf(android.os.Looper.getMainLooper()).idle()
        ok(a, KeyEvent.ACTION_DOWN)
        // Le focus part ailleurs pendant l'appui (rafraîchissement qui le déplace, ou autre) : rien ne doit partir.
        (ligne(a, "q15") ?: throw AssertionError("ligne q15 absente")).requestFocus(); shadowOf(android.os.Looper.getMainLooper()).idle()
        ok(a, KeyEvent.ACTION_UP)
        assertNull("aucun menu : l'appui est ignoré", titreDuDialogue())
        // Un appui propre ensuite marche normalement.
        ok(a, KeyEvent.ACTION_DOWN); ok(a, KeyEvent.ACTION_UP)
        assertEquals("Titre 15", titreDuDialogue())
    }

    @Test fun un_ok_normal_ouvre_le_menu_du_titre_focalise() {
        val a = ouvrir()
        val l30 = ligne(a, "q33") ?: throw AssertionError("ligne q33 (dernière affichée) absente")
        l30.requestFocus(); shadowOf(android.os.Looper.getMainLooper()).idle()
        ok(a, KeyEvent.ACTION_DOWN); ok(a, KeyEvent.ACTION_UP)
        assertEquals("Titre 33", titreDuDialogue())
        assertNotNull(ShadowDialog.getLatestDialog())
    }
}
