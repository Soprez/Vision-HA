// SPDX-License-Identifier: MIT
package org.soprez.vision.captures

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import org.soprez.vision.GestesVeille
import org.soprez.vision.PartyActivity
import org.soprez.vision.ToucheMedia
import org.soprez.vision.VeilleMusique

/**
 * 1.93.05 (l'auteur, retours 37 et 38, TV du salon), Home Assistant simulé ([FauxHa]) :
 * - 37 : les huit touches média de la télécommande pilotent la musique de cet appareil — dans le lecteur (chaque touche
 *   donne l'ordre attendu à HA, avance et recul de 10 s) et dans la veille (vers HA, plus vers les autres applis) ;
 *   sans musique de Vision ici, la veille les laisse aux autres applis (TV en direct, YouTube) ;
 * - 38 : les touches de volume ne sont JAMAIS prises (le système règle le son) et le lecteur montre sa jauge à droite,
 *   dans la zone sûre, avec le pourcentage.
 * Télé 720p et 1080p.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class TouchesMediaTeleTest(private val taille: Recette.Taille) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun parametres(): List<Array<Any>> = Recette.TAILLES.filter { it.tele }.map { arrayOf<Any>(it) }
    }

    @After fun nettoyer() { VeilleMusique.oublier() }

    /** L'état de la musique, qui suit les ordres reçus (lecture / pause). */
    @Volatile private var joue = true

    private fun etat(): JSONObject = JSONObject().put("ma", true).put("ici", true).put("actif", true).put("lecteur", "media_player.salon")
        .put("lecteur_nom", "Salon").put("piloter", true)
        .put("maintenant", JSONObject().put("etat", if (joue) "playing" else "paused").put("titre", "Groovy Beat").put("artiste", "The Funky Band")
            .put("album", "Night Moves").put("volume", 0.4).put("duree", 214.0).put("position", 61.0))
        .put("file", JSONArray())

    private fun relier(ha: FauxHa) {
        val ctx = RuntimeEnvironment.getApplication()
        org.soprez.vision.Config(ctx).apply { id = "essai_tele"; secret = "s".repeat(32); urlInterne = ha.adresse; urlExterne = ""; urlActive = "" }
        ha.reponses["/api/pc_parental/party/etat"] = { etat() }
        ha.reponses["/api/pc_parental/party/commande"] = {
            val c = ha.recus.last { it.first == "/api/pc_parental/party/commande" }.second
            if (c.optString("action") == "lecture") joue = c.optString("voulu") == "lecture"
            JSONObject().put("ok", true)
        }
    }

    private fun commandes(ha: FauxHa) = ha.recus.filter { it.first == "/api/pc_parental/party/commande" }.map { it.second }

    private fun appuyer(cible: (KeyEvent) -> Boolean, code: Int): Boolean {
        val bas = cible(KeyEvent(KeyEvent.ACTION_DOWN, code))
        cible(KeyEvent(KeyEvent.ACTION_UP, code))
        return bas
    }

    /** Attend un ordre de plus que [avant] (ou rien, pour [aucun]). */
    private fun attendre(ha: FauxHa, avant: Int, aucun: Boolean = false): JSONObject? {
        val fin = System.currentTimeMillis() + if (aucun) 700 else 6000
        while (System.currentTimeMillis() < fin) {
            ShadowLooper.idleMainLooper(); Thread.sleep(30)
            if (commandes(ha).size > avant) break
        }
        ShadowLooper.idleMainLooper(300, java.util.concurrent.TimeUnit.MILLISECONDS)
        return commandes(ha).getOrNull(avant)
    }

    private fun lecteur(ha: FauxHa): Activity {
        Recette.poser(taille, 1.0f)
        relier(ha)
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val a = Robolectric.buildActivity(PartyActivity::class.java).setup().visible().get()
        val fin = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < fin) {
            ShadowLooper.idleMainLooper(); Thread.sleep(40)
            if (a.window.decorView.findViewWithTag<View>("lecteur_gauche") != null && ha.recus.any { it.first == "/api/pc_parental/party/etat" }) break
        }
        ShadowLooper.idleMainLooper(600, java.util.concurrent.TimeUnit.MILLISECONDS)
        return a
    }

    private fun resume(c: JSONObject?): String = when {
        c == null -> "rien"
        c.optString("action") == "lecture" -> "lecture:" + c.optString("voulu")
        c.optString("action") == "position" -> "position:" + c.optDouble("position")
        else -> c.optString("action")
    }

    @Test fun lecteur_les_huit_touches() {
        FauxHa().use { ha ->
            val a = lecteur(ha)
            val defauts = ArrayList<String>()
            // [touche, ordre attendu] ; la musique joue au départ.
            val attendus = listOf(
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to "lecture:pause",
                KeyEvent.KEYCODE_MEDIA_PLAY to "lecture:lecture",
                KeyEvent.KEYCODE_MEDIA_STOP to "lecture:pause",
                KeyEvent.KEYCODE_MEDIA_PLAY to "lecture:lecture",
                KeyEvent.KEYCODE_MEDIA_PAUSE to "lecture:pause",
                KeyEvent.KEYCODE_MEDIA_PAUSE to "rien",           // déjà en pause : rien à faire
                KeyEvent.KEYCODE_MEDIA_PLAY to "lecture:lecture",
                KeyEvent.KEYCODE_MEDIA_NEXT to "suivant",
                KeyEvent.KEYCODE_MEDIA_PREVIOUS to "precedent",
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD to "position:71.0",
            )
            for ((code, voulu) in attendus) {
                val avant = commandes(ha).size
                val prise = appuyer({ a.dispatchKeyEvent(it) }, code)
                val r = resume(attendre(ha, avant, aucun = voulu == "rien"))
                if (!prise) defauts += "${KeyEvent.keyCodeToString(code)} n'est pas prise par le lecteur"
                if (r != voulu) defauts += "${KeyEvent.keyCodeToString(code)} : « $r » au lieu de « $voulu »"
            }
            // Le recul part de la position voulue (71 s) ou, si le saut est déjà pris, de celle de HA (61 s).
            val avant = commandes(ha).size
            appuyer({ a.dispatchKeyEvent(it) }, KeyEvent.KEYCODE_MEDIA_REWIND)
            val r = resume(attendre(ha, avant))
            if (r !in setOf("position:61.0", "position:51.0")) defauts += "MEDIA_REWIND : « $r » au lieu d'un recul de 10 s"
            assertEquals(defauts.joinToString("\n"), emptyList<String>(), defauts)
        }
    }

    @Test fun lecteur_volume_au_systeme_et_jauge_de_vision() {
        FauxHa().use { ha ->
            val a = lecteur(ha)
            val am = a.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 0.4f), 0)
            val defauts = ArrayList<String>()
            val avant = commandes(ha).size
            for (code in listOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE))
                if (appuyer({ a.dispatchKeyEvent(it) }, code)) defauts += "${KeyEvent.keyCodeToString(code)} est prise par Vision (le système doit régler le son)"
            appuyer({ a.dispatchKeyEvent(it) }, KeyEvent.KEYCODE_VOLUME_UP)
            ShadowLooper.idleMainLooper(300, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (commandes(ha).size != avant) defauts += "une touche de volume a envoyé un ordre à HA"
            val d = a.window.decorView
            val j = d.findViewWithTag<View>("jauge_volume")
            val p = org.soprez.vision.JaugeVolume.pourcentDe(am.getStreamVolume(AudioManager.STREAM_MUSIC), am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
            if (j == null || j.visibility != View.VISIBLE) defauts += "pas de jauge de volume dans le lecteur"
            else {
                // 1.93.05 (règles d'Emil) : arrivée en fondu de 180 ms, finie au moment de la capture.
                if (j.alpha < 0.99f || j.scaleX < 0.99f) defauts += "la jauge n'a pas fini d'apparaître (alpha ${j.alpha}, échelle ${j.scaleX})"
                if (j.contentDescription?.toString()?.contains("$p") != true) defauts += "la jauge ne dit pas $p % (${j.contentDescription})"
                val pos = IntArray(2).also { j.getLocationOnScreen(it) }
                val zx = d.width * 5 / 100; val zy = d.height * 5 / 100
                if (pos[0] + j.width > d.width - zx + 2 || pos[0] < d.width / 2) defauts += "la jauge n'est pas à droite dans la zone sûre (x ${pos[0]}..${pos[0] + j.width}, écran ${d.width})"
                if (pos[1] < zy - 2 || pos[1] + j.height > d.height - zy + 2) defauts += "la jauge sort de la zone sûre en hauteur"
            }
            defauts.addAll(Recette.capturer(d, "lecteur-jauge-volume", taille, 1.0f))
            // Elle s'efface seule.
            ShadowLooper.idleMainLooper(2500, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (j?.visibility == View.VISIBLE) defauts += "la jauge reste affichée"
            assertEquals(defauts.joinToString("\n"), emptyList<String>(), defauts)
        }
    }

    @Test fun veille_touches_vers_la_musique_de_cet_appareil() {
        FauxHa().use { ha ->
            Recette.poser(taille, 1.0f)
            relier(ha)
            val ctx = RuntimeEnvironment.getApplication()
            val g = GestesVeille(ctx, { null }) { }
            val defauts = ArrayList<String>()
            // Sans musique de Vision ici : la touche va aux autres applis, rien vers HA.
            VeilleMusique.oublier()
            if (ToucheMedia.touche(ctx, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT))) defauts += "sans musique ici, Vision prend la touche"
            appuyer({ g.touche(it) }, KeyEvent.KEYCODE_MEDIA_NEXT)
            if (attendre(ha, 0, aucun = true) != null) defauts += "sans musique ici, la veille envoie un ordre à HA"
            // La musique de cet appareil joue : la veille pilote Music Assistant.
            VeilleMusique.releve(ctx, true)
            for ((code, voulu) in listOf(KeyEvent.KEYCODE_MEDIA_NEXT to "suivant", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to "lecture:pause",
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD to "position:71.0")) {
                val avant = commandes(ha).size
                if (!appuyer({ g.touche(it) }, code)) defauts += "la veille ne prend pas ${KeyEvent.keyCodeToString(code)}"
                val r = resume(attendre(ha, avant))
                if (r != voulu) defauts += "veille, ${KeyEvent.keyCodeToString(code)} : « $r » au lieu de « $voulu »"
            }
            // Le volume reste au système, dans la veille aussi.
            for (code in listOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE))
                if (g.touche(KeyEvent(KeyEvent.ACTION_DOWN, code))) defauts += "la veille prend ${KeyEvent.keyCodeToString(code)}"
            assertEquals(defauts.joinToString("\n"), emptyList<String>(), defauts)
        }
    }

    @Test fun session_seulement_vision_devant_et_musique_ici() {
        // Une autre appli devant (TV en direct, YouTube) : jamais de session ; le lecteur Sendspin a déjà la sienne.
        assertEquals(true, ToucheMedia.sessionVoulue(devant = true, joue = true, sendspin = false))
        assertEquals(false, ToucheMedia.sessionVoulue(devant = false, joue = true, sendspin = false))
        assertEquals(false, ToucheMedia.sessionVoulue(devant = true, joue = false, sendspin = false))
        assertEquals(false, ToucheMedia.sessionVoulue(devant = true, joue = true, sendspin = true))
        // Les touches : les huit touches média, jamais le volume.
        val media = listOf(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP,
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND)
        assertEquals(8, media.mapNotNull { ToucheMedia.action(it) }.toSet().size)
        for (v in listOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_DPAD_CENTER))
            assertEquals(null, ToucheMedia.action(v))
        // Avance et recul bornés au titre.
        assertEquals(213.0, ToucheMedia.commande(ToucheMedia.Action.AVANCER, true, 210.0, 214.0)!!.second.getDouble("position"), 0.01)
        assertEquals(0.0, ToucheMedia.commande(ToucheMedia.Action.RECULER, true, 4.0, 214.0)!!.second.getDouble("position"), 0.01)
        assertEquals(null, ToucheMedia.commande(ToucheMedia.Action.AVANCER, true, null, 214.0))
    }
}
