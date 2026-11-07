// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * 1.94 (décision de l'auteur) : sur la télé, l'interface web de Music Assistant ne se pilote pas à la télécommande. Sans l'appli
 * Music Assistant sur la télé, la tuile montre ceci : « Ouvre Music Assistant sur ton téléphone », avec un QR code vers son
 * interface (adresse locale, ou ingress de Home Assistant hors du réseau local : voir [MusicAssistant.choisir]). Aucune
 * WebView. Retour ou « Fermer » ramène à l'accueil.
 */
class MaQrActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val url = intent.getStringExtra("url").orEmpty()
        val th = Local.themeEffectif(this, Palette.APPLI)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(th.fond))
        val bmp = QrInvites.matrice(url)
        if (bmp == null || !(url.startsWith("http://") || url.startsWith("https://"))) { Ui.toast(this, getString(R.string.ma_indisponible), genre = OeilModele.Genre.ERREUR); finish(); return }
        val dm = resources.displayMetrics
        val racine = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ZoneSure.x(this@MaQrActivity, 0.05f) + Ui.dp(this@MaQrActivity, 24f), ZoneSure.y(this@MaQrActivity, 0.05f) + Ui.dp(this@MaQrActivity, 16f),
                ZoneSure.x(this@MaQrActivity, 0.05f) + Ui.dp(this@MaQrActivity, 24f), ZoneSure.y(this@MaQrActivity, 0.05f) + Ui.dp(this@MaQrActivity, 16f))
        }
        racine.addView(Ui.texte(this, getString(R.string.ma_qr_titre), 28f, th.encre, gras = true, centre = true))
        racine.addView(Ui.marge(this, Ui.texte(this, getString(R.string.ma_qr_texte), 16f, th.encre2, centre = true), haut = 8f))
        // Le code, en gros, net (sans lissage), sur fond blanc : la zone de silence est dans l'image ; au moins le tiers de la hauteur.
        val cote = (minOf(dm.heightPixels * 0.5f, dm.widthPixels * 0.6f)).toInt().coerceAtLeast(Ui.dp(this, 200f))
        val pas = (cote / bmp.width).coerceAtLeast(1)
        val image = ImageView(this).apply {
            val d = BitmapDrawable(resources, bmp); d.isFilterBitmap = false
            setImageDrawable(d); scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = getString(R.string.ma_qr_description)
        }
        racine.addView(image, LinearLayout.LayoutParams(bmp.width * pas, bmp.height * pas).apply { topMargin = Ui.dp(this@MaQrActivity, 20f); gravity = Gravity.CENTER_HORIZONTAL })
        racine.addView(Ui.marge(this, Ui.texte(this, url, 13f, th.encre3, centre = true), haut = 10f))
        val fermer = Ui.boutonPrimaire(this, getString(R.string.commun_fermer)) { finish() }
        fermer.isFocusableInTouchMode = true
        racine.addView(Ui.marge(this, fermer, haut = 18f), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(this@MaQrActivity, 18f) })
        setContentView(ScrollView(this).apply { addView(racine); isFillViewport = true })
        fermer.requestFocus()
    }
}
