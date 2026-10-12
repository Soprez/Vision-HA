// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.os.Build

/**
 * Comment cet appareil se présente à Home Assistant, quel que soit le chemin d'inscription
 * (clé, découverte, QR) : le même identifiant, le même modèle, le même nom.
 */
object Identite {
    /** Identifiant propre à l'appareil (et à la clé de signature de Vision) : reconnaît une appli réinstallée. */
    fun uid(ctx: Context): String = try {
        android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: ""
    } catch (_: Exception) { "" }

    /** Le nom donné à la télé (« Télé du salon ») ; vide sur téléphone. */
    fun nom(ctx: Context): String = if (!ReglagesTvActivity.estTele(ctx)) "" else try {
        (android.provider.Settings.Global.getString(ctx.contentResolver, "device_name") ?: "").trim().take(60)
    } catch (_: Exception) { "" }

    /** Le type d'appareil pour la matrice des réglages (Vision 1.72.0) : « tv », « tablette » ou « telephone ».
     *  Tablette : le plus petit côté de l'écran fait au moins 600 dp (la règle d'Android). */
    fun type(ctx: Context): String = when {
        ReglagesTvActivity.estTele(ctx) -> "tv"
        ctx.resources.configuration.smallestScreenWidthDp >= 600 -> "tablette"
        else -> "telephone"
    }

    /** Un téléphone : pas d'écran de veille (décision du projet du 2026-10-06 ; les tablettes la gardent). */
    fun telephone(ctx: Context): Boolean = type(ctx) == "telephone"

    /** Fabricant et modèle. */
    fun appareil(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifEmpty { "Appareil Android" }
}
