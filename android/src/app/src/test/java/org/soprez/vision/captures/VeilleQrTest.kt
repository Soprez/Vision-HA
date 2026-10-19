// SPDX-License-Identifier: MIT
package org.soprez.vision.captures

import android.graphics.Bitmap
import android.graphics.RectF
import android.view.View
import android.widget.FrameLayout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import org.soprez.vision.CoinsVeille
import org.soprez.vision.Local
import org.soprez.vision.OptionsVeille
import org.soprez.vision.Palette
import org.soprez.vision.Party
import org.soprez.vision.ReglagesTvActivity
import org.soprez.vision.VeilleView
import java.io.File

/**
 * 1.93.05 (l'auteur, retour 39, TV du salon) : le QR code des invités dans l'écran de veille.
 * - Mode soirée ouvert : le QR (le même que celui du lecteur, même jeton), lisible (relu ici par ZXing sur la capture),
 *   19 % de la hauteur, « Ajoute ta musique », dans la zone sûre ; mode fermé : rien.
 * - Son coin et celui du logo se règlent (4 coins ou masqué ; QR en bas à droite et logo à sa place d'avant par
 *   défaut), sur la télé et depuis Home Assistant ; deux éléments dans un coin : refusé. Le bandeau musique prend un
 *   coin libre ; la demande et la petite heure se décalent. Aucune combinaison ne se chevauche ni ne touche l'horloge.
 * Télé 720p et 1080p, captures de trois combinaisons.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class VeilleQrTest(private val taille: Recette.Taille) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun parametres(): List<Array<Any>> = Recette.TAILLES.filter { it.tele }.map { arrayOf<Any>(it) }

        const val LIEN = "https://exemple.invalid/api/pc_parental/party/invites?c=JETON-ESSAI-123"

        fun soiree(ouverte: Boolean): Party.Etat = Party.analyser(JSONObject().put("ma", true).put("ici", true).put("actif", true)
            .put("lecteur", "media_player.salon").put("lecteur_nom", "Salon").put("invites_ouverts", ouverte).put("qr", if (ouverte) LIEN else "")
            .put("maintenant", JSONObject().put("etat", "playing").put("titre", "Groovy Beat").put("artiste", "The Funky Band").put("duree", 214.0)))
    }

    private fun intersecte(a: RectF, b: RectF) = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    private fun toucheAnneau(b: RectF, w: Float, h: Float): Boolean {
        val dx = maxOf(b.left - w / 2, 0f, w / 2 - b.right); val dy = maxOf(b.top - h / 2, 0f, h / 2 - b.bottom)
        return dx * dx + dy * dy < (h * 0.42f) * (h * 0.42f)
    }

    @Test fun aucune_combinaison_ne_se_chevauche() {
        val w = 1920f; val h = 1080f
        val defauts = ArrayList<String>()
        val tous = OptionsVeille.COINS + OptionsVeille.CACHE
        for (qr in tous) for (logo in tous) {
            if (qr == logo && qr != OptionsVeille.CACHE) continue
            val boites = ArrayList<Pair<String, RectF>>()
            if (qr != OptionsVeille.CACHE) boites += "QR" to RectF().also { CoinsVeille.Coins.qr(qr, w, h, it) }
            if (logo != OptionsVeille.CACHE) boites += "logo" to RectF().also { CoinsVeille.Coins.logo(logo, w, h, it) }
            boites += "bandeau" to RectF().also { CoinsVeille.Coins.bandeau(CoinsVeille.Coins.coinBandeau(qr, logo), w, h, it) }
            val cas = "QR $qr, logo $logo"
            for (i in boites.indices) for (j in i + 1 until boites.size)
                if (intersecte(boites[i].second, boites[j].second)) defauts += "$cas : ${boites[i].first} et ${boites[j].first} se chevauchent"
            for ((nom, b) in boites) {
                if (toucheAnneau(b, w, h)) defauts += "$cas : $nom touche l'horloge du centre"
                // Le QR, le bandeau et le logo restent dans la marge de 5 %.
                // 1.93.06 : le logo aussi (photo de l'auteur : « Vision » à moitié coupé par le bas de l'écran).
                if ((b.left < w * 0.05f - 1 || b.top < h * 0.05f - 1 || b.right > w * 0.95f + 1 || b.bottom > h * 0.95f + 1))
                    defauts += "$cas : $nom sort de la zone sûre"
            }
            // La demande (en bas à droite) et la petite heure (en haut à droite) ne passent jamais dessous.
            for ((coin, nom) in listOf(OptionsVeille.BD to "la demande", OptionsVeille.HD to "la petite heure")) {
                val droite = CoinsVeille.Coins.bordDroit(coin, qr, logo, true, w, h, w * 0.92f)
                CoinsVeille.Coins.occupant(coin, qr, logo, true, w, h)?.let { o -> if (droite > o.left) defauts += "$cas : $nom passe sous ce qui occupe le coin" }
            }
        }
        // Deux éléments dans un coin : le logo garde sa place, le QR prend un coin libre.
        assertEquals(OptionsVeille.BG to OptionsVeille.BD, OptionsVeille.effectifs(OptionsVeille.BD, OptionsVeille.BD))
        assertEquals(defauts.joinToString("\n"), emptyList<String>(), defauts)
    }

    @Test fun choix_refuse_et_home_assistant() {
        Recette.poser(taille, 1.0f)
        val ctx = RuntimeEnvironment.getApplication()
        // Par défaut : QR en bas à droite, logo à sa place d'avant (en bas à gauche).
        assertEquals(OptionsVeille.BD, OptionsVeille.qr(ctx)); assertEquals(OptionsVeille.BG, OptionsVeille.logo(ctx))
        // Le logo ne prend pas le coin du QR (et inversement) ; masqué : toujours permis.
        assertEquals(false, OptionsVeille.poserLogo(ctx, OptionsVeille.BD)); assertEquals(OptionsVeille.BG, OptionsVeille.logo(ctx))
        assertEquals(false, OptionsVeille.poserQr(ctx, OptionsVeille.BG)); assertEquals(OptionsVeille.BD, OptionsVeille.qr(ctx))
        assertEquals(true, OptionsVeille.poserQr(ctx, OptionsVeille.CACHE)); assertEquals(true, OptionsVeille.poserLogo(ctx, OptionsVeille.BD))
        assertEquals(true, OptionsVeille.poserQr(ctx, OptionsVeille.HG))
        // Sur la télé : la ligne « QR code des invités » ; choisir le coin du logo est refusé.
        org.soprez.vision.Config(ctx).apply { id = "essai_tele"; secret = "s".repeat(32); urlInterne = "http://127.0.0.1:9"; urlExterne = ""; urlActive = "" }
        val a = Robolectric.buildActivity(ReglagesTvActivity::class.java,
            android.content.Intent(ctx, ReglagesTvActivity::class.java).putExtra(ReglagesTvActivity.EXTRA_SECTION, "veille")).setup().visible().get()
        ShadowLooper.idleMainLooper(300, java.util.concurrent.TimeUnit.MILLISECONDS)
        for (t in listOf("veille_qr", "veille_logo")) assertEquals("ligne « $t »", true, a.window.decorView.findViewWithTag<View>(t) != null)
        a.choisirCoin(qr = true, choix = (OptionsVeille.COINS + OptionsVeille.CACHE).indexOf(OptionsVeille.BD))
        assertEquals("le coin du logo est refusé au QR", OptionsVeille.HG, OptionsVeille.qr(ctx))
        // Home Assistant : appliqué s'il est plus récent ; un conflit garde le logo à sa place.
        assertEquals(true, OptionsVeille.releve(ctx, JSONObject().put("qr", "haut_droite").put("logo", "bas_gauche").put("depuis", 5000L)))
        assertEquals(OptionsVeille.HD, OptionsVeille.qr(ctx)); assertEquals(OptionsVeille.BG, OptionsVeille.logo(ctx))
        assertEquals(true, OptionsVeille.releve(ctx, JSONObject().put("qr", "bas_gauche").put("logo", "bas_gauche").put("depuis", 6000L)))
        assertEquals(OptionsVeille.BG, OptionsVeille.logo(ctx)); assertEquals(true, OptionsVeille.qr(ctx) != OptionsVeille.BG)
        // La page de Home Assistant reçoit le coin du logo et les décalages.
        val q = org.soprez.vision.PageVeille.coinsPage(ctx)
        assertEquals(true, q.contains("&logo=bas_gauche"))
        assertEquals(true, OptionsVeille.releve(ctx, JSONObject().put("qr", "bas_droite").put("logo", "bas_gauche").put("lecteur", false).put("depuis", 7000L)))
    }

    /** La veille et ses coins, dans un cadre à la taille de l'écran. */
    private fun cadre(v: VeilleView): Pair<FrameLayout, CoinsVeille> {
        val ctx = RuntimeEnvironment.getApplication()
        val f = FrameLayout(ctx)
        val coins = CoinsVeille(ctx, v)
        f.addView(v, FrameLayout.LayoutParams(-1, -1)); f.addView(coins, FrameLayout.LayoutParams(-1, -1))
        val dm = ctx.resources.displayMetrics
        f.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY))
        f.layout(0, 0, dm.widthPixels, dm.heightPixels)
        return f to coins
    }

    /** Relit le QR dessiné dans [b] (ZXing, comme un téléphone qui le scanne). */
    private fun relire(image: Bitmap, b: RectF): String? = try {
        val x = b.left.toInt().coerceAtLeast(0); val y = b.top.toInt().coerceAtLeast(0)
        val lw = minOf(b.width().toInt(), image.width - x); val lh = minOf(b.height().toInt(), image.height - y)
        val pts = IntArray(lw * lh); image.getPixels(pts, 0, lw, x, y, lw, lh)
        val source = com.google.zxing.RGBLuminanceSource(lw, lh, pts)
        com.google.zxing.qrcode.QRCodeReader().decode(com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(source))).text
    } catch (_: Exception) { null }

    @Test fun qr_des_invites_trois_combinaisons() {
        Recette.poser(taille, 1.0f)
        val ctx = RuntimeEnvironment.getApplication()
        val defauts = ArrayList<String>()
        val combinaisons = listOf(
            Triple(OptionsVeille.BD, OptionsVeille.BG, "defaut"),          // QR en bas à droite, logo à sa place d'avant
            Triple(OptionsVeille.HG, OptionsVeille.HD, "qr-haut-gauche"),  // QR en haut à gauche, logo en haut à droite
            Triple(OptionsVeille.HD, OptionsVeille.CACHE, "qr-haut-droite-sans-logo"))
        FauxHa().use { ha ->
            val v = VeilleEssai.vue(ha) { VeilleHorlogeSeuleTest.meteo() }
            val (f, coins) = cadre(v)
            OptionsVeille.poserLecteur(ctx, true)
            for ((qr, logo, nom) in combinaisons) {
                OptionsVeille.poserQr(ctx, OptionsVeille.CACHE); OptionsVeille.poserLogo(ctx, logo); OptionsVeille.poserQr(ctx, qr)
                if (OptionsVeille.qr(ctx) != qr || OptionsVeille.logo(ctx) != logo) defauts += "$nom : réglage non posé"
                coins.poserEtat(soiree(true))
                val b = Bitmap.createBitmap(f.width, f.height, Bitmap.Config.ARGB_8888)
                // 1.93.05 (fondus d'Emil) : une image lance le fondu, puis l'image enregistrée une fois le fondu fini.
                f.draw(android.graphics.Canvas(b)); ShadowLooper.idleMainLooper(300, java.util.concurrent.TimeUnit.MILLISECONDS)
                val c = VeilleEssai.Enregistreur(b); f.draw(c)
                File(Recette.dossier, "recette-calc-veille-qr-$nom-${taille.nom}-100.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                val q = RectF(coins.boiteQr); val ban = RectF(coins.boiteBandeau)
                if (q.isEmpty) { defauts += "$nom : pas de QR"; continue }
                val lu = relire(b, q)
                val attendu = Party.qrAvecTheme(LIEN, Local.themeEffectif(ctx, Palette.APPLI))
                if (lu != attendu) defauts += "$nom : le QR ne se relit pas comme celui du lecteur (« $lu »)"
                if (lu?.contains("JETON-ESSAI-123") != true) defauts += "$nom : le QR n'a pas le jeton des invités"
                // 1.93.06 : « Petit » par défaut (la moitié d'avant), au moins 2 px par module.
                if (q.height() < f.height * (CoinsVeille.Coins.QR * 0.45f + 0.07f)) defauts += "$nom : carte du QR trop petite (${q.height()} px)"
                if (c.textes.none { it == ctx.getString(org.soprez.vision.R.string.veille_qr_ajoute) }) defauts += "$nom : pas de « Ajoute ta musique »"
                if (!ban.isEmpty && intersecte(q, ban)) defauts += "$nom : le QR et le bandeau se chevauchent"
                if (ban.isEmpty) defauts += "$nom : pas de bandeau"
            }
            // Mode soirée fermé : plus de QR.
            coins.poserEtat(soiree(false))
            f.draw(VeilleEssai.Enregistreur(Bitmap.createBitmap(f.width, f.height, Bitmap.Config.ARGB_8888)))
            ShadowLooper.idleMainLooper(300, java.util.concurrent.TimeUnit.MILLISECONDS)
            f.draw(VeilleEssai.Enregistreur(Bitmap.createBitmap(f.width, f.height, Bitmap.Config.ARGB_8888)))
            if (!coins.boiteQr.isEmpty) defauts += "mode soirée fermé : le QR est encore là"
            v.arreter()
        }
        OptionsVeille.poserLecteur(ctx, false)
        assertEquals(defauts.joinToString("\n"), emptyList<String>(), defauts)
    }
}
