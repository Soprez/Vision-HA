// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.widget.ImageView
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 1.93.06 (retour d'essai) : la pochette reste jusqu'à l'arrivée de la suivante ; un vrai cache ; le focus audio réglé par HA. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PochettesAttenteTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    @Test fun cache_borne_en_octets() {
        assertEquals(16 * 1024 * 1024, Pochettes.memoireMax(512L * 1024 * 1024))
        assertEquals(8 * 1024 * 1024, Pochettes.memoireMax(64L * 1024 * 1024))
        assertEquals(2 * 1024 * 1024, Pochettes.memoireMax(4L * 1024 * 1024))
    }

    @Test fun l_ancienne_pochette_reste_jusqu_a_la_suivante() {
        Pochettes.vider(ctx)
        val a = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        Pochettes.essaiGarder("media_player.tele", "titre-a", a)
        val vue = ImageView(ctx)
        Pochettes.poser(ctx, vue, "media_player.tele", "titre-a")
        assertSame(a, (vue.drawable as BitmapDrawable).bitmap)
        // Titre suivant, pochette pas encore là (Home Assistant injoignable ici) : l'ancienne reste, jamais une case vide.
        Pochettes.poser(ctx, vue, "media_player.tele", "titre-b")
        assertNotNull(vue.drawable)
        assertSame(a, (vue.drawable as BitmapDrawable).bitmap)
        // Une nouvelle vue (écran rebâti) montre aussi la dernière pochette de ce lecteur en attendant.
        val autre = ImageView(ctx)
        Pochettes.poser(ctx, autre, "media_player.tele", "titre-b")
        assertSame(a, (autre.drawable as BitmapDrawable).bitmap)
        // L'image d'attente (titre suivant préchargé) passe avant l'ancienne pochette.
        val attente = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val troisieme = ImageView(ctx)
        Pochettes.poser(ctx, troisieme, "media_player.tele", "titre-c", attente)
        assertSame(attente, (troisieme.drawable as BitmapDrawable).bitmap)
        // Fin de la Party : plus rien.
        Pochettes.vider(ctx)
        val vide = ImageView(ctx)
        Pochettes.poser(ctx, vide, "media_player.tele", "titre-d")
        assertEquals(null, vide.drawable)
    }

    @Test fun lecteur_en_pause_sans_empreinte_la_vignette_du_titre() {
        // 1.93.06 (TCL, 19/10) : en pause ou au repos, HA n'envoie pas d'empreinte ; la grande pochette restait vide.
        Pochettes.vider(ctx)
        val vignette = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val vue = ImageView(ctx)
        Pochettes.poser(ctx, vue, "media_player.tele", "", vignette)
        assertSame(vignette, (vue.drawable as BitmapDrawable).bitmap)
        // Sans vignette connue : la case du thème, comme avant.
        val vide = ImageView(ctx)
        Pochettes.poser(ctx, vide, "media_player.tele", "")
        assertEquals(null, vide.drawable)
    }

    @Test fun focus_regle_par_home_assistant_seulement_s_il_est_plus_recent() {
        assertEquals(FocusMusique.TOUJOURS, FocusMusique.mode(ctx))
        assertFalse(FocusMusique.reprise(ctx))
        assertFalse(FocusMusique.releve(ctx, null))
        assertTrue(FocusMusique.releve(ctx, JSONObject().put("depuis", 10L).put("focus", "applis").put("reprise", true)
            .put("applis", org.json.JSONArray().put("com.google.android.youtube.tv"))))
        assertEquals(FocusMusique.APPLIS, FocusMusique.mode(ctx))
        assertEquals(setOf("com.google.android.youtube.tv"), FocusMusique.applis(ctx))
        assertTrue(FocusMusique.reprise(ctx))
        // Un choix fait ensuite sur la télé reste ; un ancien réglage de HA (même « depuis ») n'y touche pas.
        FocusMusique.poserMode(ctx, FocusMusique.JAMAIS)
        assertFalse(FocusMusique.releve(ctx, JSONObject().put("depuis", 10L).put("focus", "toujours")))
        assertEquals(FocusMusique.JAMAIS, FocusMusique.mode(ctx))
        // Un réglage plus récent de HA gagne.
        assertTrue(FocusMusique.releve(ctx, JSONObject().put("depuis", 11L).put("focus", "toujours")))
        assertEquals(FocusMusique.TOUJOURS, FocusMusique.mode(ctx))
    }
}
