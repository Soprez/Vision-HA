// SPDX-License-Identifier: MIT
package org.soprez.vision.captures

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import org.soprez.vision.CoinsVeille
import org.soprez.vision.DeriveVeille
import org.soprez.vision.LanceurActivity
import org.soprez.vision.Local
import org.soprez.vision.MessageActivity
import org.soprez.vision.OptionsVeille
import org.soprez.vision.Palette
import org.soprez.vision.Party
import org.soprez.vision.QrInvites
import org.soprez.vision.ReglagesTvActivity
import org.soprez.vision.VeilleView
import org.soprez.vision.ZoneSure
import java.io.File

/**
 * 1.93.06 (l'auteur, photos du salon, « Ajuster l'écran » réglé au ras des bords) : « Ajuster l'écran » dit seulement où sont
 * les bords visibles ; Vision garde TOUJOURS sa marge de confort en plus (48 dp sur les côtés, 27 dp en haut et en bas).
 * Avec le réglage à 0 % et à 5 %, en 720p et en 1080p, rien ne doit être à moins de cette marge du bord réglé :
 * - la veille et ses coins (logo, QR, bandeau), pour les 3 tailles du QR (Petit, Moyen, Grand) : QR relu, au moins
 *   2 pixels réels par module, légende sur une ligne, aucun chevauchement ;
 * - l'accueil, les réglages de la télé, un écran sans zone à lui (le message, par le conteneur racine) et un dialogue.
 * Le lecteur (file d'attente comprise) et la recherche : LecteurTeleCaptureTest et PanneauMusiqueCaptureTest (0 et 5 %).
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ZoneConfortCaptureTest(private val taille: Recette.Taille, private val zone: Int) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-zone{1}")
        fun parametres(): List<Array<Any>> = Recette.TAILLES.filter { it.tele }.flatMap { t -> listOf(0, 5).map { z -> arrayOf<Any>(t, z) } }
    }

    private val ctx get() = RuntimeEnvironment.getApplication()

    private fun preparer() {
        Recette.poser(taille, 1.0f)
        ZoneSure.poser(ctx, zone, zone)
        org.soprez.vision.Config(ctx).apply { id = "essai_tele"; secret = "s".repeat(32); urlInterne = "http://127.0.0.1:9"; urlExterne = ""; urlActive = "" }
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
    }

    private fun zoneDe(a: Activity): Rect { val d = a.window.decorView; return Debordements.zoneTele(a, d.width, d.height) }

    private fun verifierEcran(a: Activity, nom: String): List<String> {
        ShadowLooper.idleMainLooper(400, java.util.concurrent.TimeUnit.MILLISECONDS)
        val d = a.window.decorView
        val defauts = ArrayList(Recette.capturer(d, "zone-$nom-zone$zone", taille, 1.0f))
        Debordements.horsZone(d, zoneDe(a), defauts, Rect(0, 0, d.width, d.height))
        return defauts.map { "$nom : $it" }
    }

    @Test fun la_marge_de_confort_est_toujours_gardee() {
        preparer()
        val dm = ctx.resources.displayMetrics
        val z = ZoneSure.zone(ctx)
        assertEquals(dm.widthPixels * zone / 100 + Math.round(ZoneSure.CONFORT_X_DP * dm.density), z.left)
        assertEquals(dm.heightPixels * zone / 100 + Math.round(ZoneSure.CONFORT_Y_DP * dm.density), z.top)
        // Un écran dessiné pour 5 % garde au moins sa fraction, jamais moins que le confort.
        assertTrue(ZoneSure.x(ctx) >= z.left)
        assertTrue(ZoneSure.y(ctx, 0.035f) >= z.top)
    }

    @Test fun accueil_reglages_message_et_dialogue_dans_la_zone() {
        preparer()
        val defauts = ArrayList<String>()
        defauts += verifierEcran(Robolectric.buildActivity(LanceurActivity::class.java).setup().visible().get(), "accueil")
        for (section in listOf("parametres", "veille")) {
            val a = Robolectric.buildActivity(ReglagesTvActivity::class.java,
                Intent(ctx, ReglagesTvActivity::class.java).putExtra(ReglagesTvActivity.EXTRA_SECTION, section)).setup().visible().get()
            defauts += verifierEcran(a, "reglages-$section")
            if (section == "veille") {
                // Un dialogue (la taille du QR) : il tient dans la zone, centré.
                a.choisirTailleQr()
                ShadowLooper.idleMainLooper(300, java.util.concurrent.TimeUnit.MILLISECONDS)
                val dlg = org.robolectric.shadows.ShadowDialog.getLatestDialog()
                val dv = dlg?.window?.decorView
                if (dv == null) defauts += "dialogue : pas ouvert"
                else {
                    val zr = zoneDe(a)
                    if (dv.width > zr.width() || dv.height > zr.height()) defauts += "dialogue : plus grand que la zone (${dv.width}×${dv.height}, zone ${zr.width()}×${zr.height()})"
                    for (t in QrInvites.TAILLES) if (!Debordements.texteVisible(dv, QrInvites.libelle(ctx, t))) defauts += "dialogue : « ${QrInvites.libelle(ctx, t)} » absent"
                    dlg.dismiss()
                }
            }
        }
        // Un écran qui ne place pas lui-même sa zone : elle lui vient du conteneur racine (VisionApp).
        val m = Robolectric.buildActivity(MessageActivity::class.java,
            Intent(ctx, MessageActivity::class.java).putExtra("titre", "Vision").putExtra("texte", "Le repas est prêt, venez à table")).setup().start().visible().get()
        val contenu = m.findViewById<View>(android.R.id.content)
        val zr = ZoneSure.zone(m)
        if (contenu.paddingLeft < zr.left || contenu.paddingTop < zr.top) defauts += "message : le conteneur racine n'a pas la zone (${contenu.paddingLeft}, ${contenu.paddingTop})"
        defauts += verifierEcran(m, "message")
        assertEquals(defauts.joinToString("\n"), emptyList<String>(), defauts)
    }

    /** La veille et ses coins, dans un cadre à la taille de l'écran. */
    private fun cadre(v: VeilleView): Pair<FrameLayout, CoinsVeille> {
        val f = FrameLayout(ctx)
        val coins = CoinsVeille(ctx, v)
        f.addView(v, FrameLayout.LayoutParams(-1, -1)); f.addView(coins, FrameLayout.LayoutParams(-1, -1))
        val dm = ctx.resources.displayMetrics
        f.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY))
        f.layout(0, 0, dm.widthPixels, dm.heightPixels)
        return f to coins
    }

    /** Une boîte de la veille (coordonnées du dessin) à l'écran : resserrée autour du centre par « Ajuster l'écran ». */
    private fun aLEcran(b: RectF, w: Float, h: Float, e: Float) =
        RectF(w / 2 + (b.left - w / 2) * e, h / 2 + (b.top - h / 2) * e, w / 2 + (b.right - w / 2) * e, h / 2 + (b.bottom - h / 2) * e)

    private fun relire(image: Bitmap, b: RectF): String? = try {
        val x = b.left.toInt().coerceAtLeast(0); val y = b.top.toInt().coerceAtLeast(0)
        val lw = minOf(b.width().toInt(), image.width - x); val lh = minOf(b.height().toInt(), image.height - y)
        val pts = IntArray(lw * lh); image.getPixels(pts, 0, lw, x, y, lw, lh)
        com.google.zxing.qrcode.QRCodeReader().decode(com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(
            com.google.zxing.RGBLuminanceSource(lw, lh, pts)))).text
    } catch (_: Exception) { null }

    @Test fun veille_coins_dans_la_zone_pour_les_trois_tailles_de_qr() {
        preparer()
        val defauts = ArrayList<String>()
        val cotes = LinkedHashMap<String, Float>()
        FauxHa().use { ha ->
            val v = VeilleEssai.vue(ha) { VeilleHorlogeSeuleTest.meteo() }
            val (f, coins) = cadre(v)
            OptionsVeille.poserLecteur(ctx, true)
            OptionsVeille.poserQr(ctx, OptionsVeille.CACHE); OptionsVeille.poserLogo(ctx, OptionsVeille.BG); OptionsVeille.poserQr(ctx, OptionsVeille.BD)
            val w = f.width.toFloat(); val h = f.height.toFloat()
            val e = ZoneSure.echelle(ctx)
            // La lente dérive anti-marquage (± 3 dp) est tolérée.
            val tol = DeriveVeille.amplitude(ctx.resources.displayMetrics.density) * e + 1f
            val zr = Debordements.zoneTele(ctx, f.width, f.height)
            for (t in QrInvites.TAILLES) {
                OptionsVeille.poserQrTaille(ctx, t)
                coins.poserEtat(VeilleQrTest.soiree(true))
                val b = Bitmap.createBitmap(f.width, f.height, Bitmap.Config.ARGB_8888)
                f.draw(android.graphics.Canvas(b)); ShadowLooper.idleMainLooper(300, java.util.concurrent.TimeUnit.MILLISECONDS)
                val c = VeilleEssai.Enregistreur(b); f.draw(c)
                File(Recette.dossier, "recette-calc-zone-veille-qr-$t-zone$zone-${taille.nom}-100.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                val q = RectF(coins.boiteQr); val ban = RectF(coins.boiteBandeau)
                val logo = RectF().also { CoinsVeille.Coins.logo(OptionsVeille.logo(ctx), w, h, it) }
                if (q.isEmpty) { defauts += "$t : pas de QR"; continue }
                for ((nom, boite) in listOf("QR" to q, "bandeau" to ban, "logo" to logo)) {
                    if (boite.isEmpty) continue
                    val r = aLEcran(boite, w, h, e)
                    if (r.left < zr.left - tol || r.top < zr.top - tol || r.right > zr.right + tol || r.bottom > zr.bottom + tol)
                        defauts += "$t : $nom à moins de la marge de confort du bord (${r.toShortString()}, zone ${zr.toShortString()})"
                }
                if (RectF.intersects(q, ban)) defauts += "$t : le QR et le bandeau se chevauchent"
                if (RectF.intersects(q, logo)) defauts += "$t : le QR et le logo se chevauchent"
                // Lisible : relu par ZXing, au moins 2 pixels réels par module, légende sur une ligne entière.
                val attendu = Party.qrAvecTheme(VeilleQrTest.LIEN, Local.themeEffectif(ctx, Palette.APPLI))
                if (relire(b, aLEcran(q, w, h, e)) != attendu) defauts += "$t : le QR ne se relit pas"
                val cote = CoinsVeille.Coins.cotePx(h, t, CoinsVeille.Coins.modulesVus, e)
                cotes[t] = cote
                val parModule = cote * e / CoinsVeille.Coins.modulesVus
                if (parModule < QrInvites.MODULE_MIN_PX - 0.01f) defauts += "$t : $parModule px par module (au moins ${QrInvites.MODULE_MIN_PX})"
                val legende = ctx.getString(org.soprez.vision.R.string.veille_qr_ajoute)
                if (c.textes.count { it == legende } != 1) defauts += "$t : la légende n'est pas sur une seule ligne"
                if (q.width() + 0.5f < CoinsVeille.largeurLegende(ctx, h) + 2 * h * CoinsVeille.Coins.PAD) defauts += "$t : la légende déborde de sa carte"
            }
            v.arreter()
        }
        OptionsVeille.poserLecteur(ctx, false); OptionsVeille.poserQrTaille(ctx, QrInvites.DEFAUT)
        val (p, m, g) = Triple(cotes[QrInvites.PETIT] ?: 0f, cotes[QrInvites.MOYEN] ?: 0f, cotes[QrInvites.GRAND] ?: 0f)
        // Petit ≤ Moyen ≤ Grand ; Petit et Moyen se rejoignent au minimum de 2 px par module en 720p.
        if (!(p <= m && m <= g && p < g)) defauts += "tailles du QR dans le désordre : petit $p, moyen $m, grand $g"
        assertEquals(defauts.joinToString("\n"), emptyList<String>(), defauts)
    }
}
