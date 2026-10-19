// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color

/**
 * 1.93.06 (retour d'essai) : le QR des invités (« Ajoute ta musique »), sur la veille et sur le lecteur, à la taille choisie pour
 * CET appareil (Réglages › Écran de veille, ou Home Assistant : service veille_regler, « qr_taille ») :
 * - « Petit » (par défaut, le choix de l'auteur) : la moitié de l'ancienne taille, à scanner de près (≈ 1 m) ;
 * - « Moyen » : les trois quarts (≈ 1,5 m) ;
 * - « Grand » : l'ancienne taille (≈ 2 m).
 *
 * Distances estimées pour une TV de 50 pouces en 1080p (image de 62 cm de haut, 0,58 mm par pixel) : un téléphone lit
 * un QR jusqu'à environ 15 fois son côté. Côté du code sur la veille : Petit ≈ 6 cm, Moyen ≈ 9 cm, Grand ≈ 12 cm.
 *
 * Pour qu'il reste lisible même petit : au moins 2 pixels réels par module ([cotePx] : le code grandit au besoin, en
 * 720p surtout), sans lissage, avec une zone de silence blanche de [SILENCE] modules (la norme du QR).
 */
object QrInvites {
    const val PETIT = "petit"
    const val MOYEN = "moyen"
    const val GRAND = "grand"
    const val DEFAUT = PETIT
    val TAILLES = listOf(PETIT, MOYEN, GRAND)
    const val SILENCE = 4
    const val MODULE_MIN_PX = 2

    fun valide(t: String?): Boolean = t != null && t in TAILLES

    /** La part de l'ancienne taille (avant 1.93.06, la taille « Grand »). */
    fun facteur(taille: String): Float = when (taille) { GRAND -> 1f; MOYEN -> 0.75f; else -> 0.5f }

    /** « Petit — à scanner de près (≈ 1 m) », pour les réglages. */
    fun libelle(ctx: Context, taille: String): String = ctx.getString(when (taille) {
        GRAND -> R.string.qr_taille_grand
        MOYEN -> R.string.qr_taille_moyen
        else -> R.string.qr_taille_petit
    })

    /**
     * Le côté en px pour [modules] modules (zone de silence comprise) : [ciblePx], mais jamais moins de [minModulePx]
     * px par module. Pur.
     */
    fun cotePx(ciblePx: Float, modules: Int, minModulePx: Float = MODULE_MIN_PX.toFloat()): Float =
        if (modules <= 0) 0f else maxOf(ciblePx, minModulePx * modules)

    /** Le code à un pixel par module (zone de silence comprise), noir sur blanc ; null si [contenu] ne s'encode pas. */
    fun matrice(contenu: String): Bitmap? = try {
        val m = com.google.zxing.qrcode.QRCodeWriter().encode(contenu, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0,
            mapOf(com.google.zxing.EncodeHintType.MARGIN to SILENCE))
        val l = m.width; val h = m.height
        val pts = IntArray(l * h)
        for (y in 0 until h) for (x in 0 until l) pts[y * l + x] = if (m[x, y]) Color.BLACK else Color.WHITE
        Bitmap.createBitmap(pts, l, h, Bitmap.Config.RGB_565)
    } catch (_: Exception) { null }
}
