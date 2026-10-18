// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build

/**
 * Les « paramètres restreints » d'Android (13 et plus), pour toute installation hors Play Store : une
 * appli installée hors d'une boutique (APK téléchargé) ne reçoit pas certaines autorisations sensibles —
 * accessibilité, et selon la version d'Android d'autres encore (données d'utilisation…) — tant qu'on ne
 * l'a pas autorisé dans Infos de l'appli → ⋮ → « Autoriser les paramètres restreints ». Android ne
 * propose ce menu qu'après un premier refus (« Paramètre restreint », « Accès refusé »).
 *
 * (Le passage depuis la première appli, sous un autre identifiant de paquet, est retiré depuis la
 * 1.93.04 : plus aucun appareil ne l'avait. L'identifiant de Vision, org.soprez.vision, ne change pas.)
 */
object Migration {
    // ---------------------------------------------------- Paramètres restreints

    /**
     * Vrai si Android retient encore l'accessibilité de Vision (appli installée hors boutique,
     * pas encore autorisée). Faux si c'est autorisé, si l'accessibilité est déjà active, ou si
     * le système ne dit rien (Android plus ancien, ou constructeur qui ne l'expose pas).
     */
    fun parametresRestreints(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        if (VisionAccessibility.estActive(ctx)) return false
        val mode = try {
            val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            ops.unsafeCheckOpNoThrow("android:access_restricted_settings", ctx.applicationInfo.uid, ctx.packageName)
        } catch (_: Exception) { -1 }
        if (mode == AppOpsManager.MODE_ERRORED) return true
        if (mode == AppOpsManager.MODE_ALLOWED) return false
        // Android 15 et plus ne posent plus forcément ce drapeau dès l'installation : on se fie à la
        // provenance de l'APK.
        return installeeHorsBoutique(ctx)
    }

    /**
     * Un refus d'une permission sensible peut-il venir des paramètres restreints ? Oui sur Android 13
     * et plus, pour un APK venu d'un fichier, tant qu'Android ne dit pas qu'ils sont autorisés.
     */
    fun refusPeutVenirDesRestreints(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        val mode = try {
            val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            ops.unsafeCheckOpNoThrow("android:access_restricted_settings", ctx.applicationInfo.uid, ctx.packageName)
        } catch (_: Exception) { -1 }
        if (mode == AppOpsManager.MODE_ALLOWED) return false
        return mode == AppOpsManager.MODE_ERRORED || installeeHorsBoutique(ctx)
    }

    /** Vision vient d'un APK téléchargé ou d'un fichier local : les paramètres restreints s'appliquent. */
    fun installeeHorsBoutique(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        return try {
            val source = ctx.packageManager.getInstallSourceInfo(ctx.packageName).packageSource
            source == android.content.pm.PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE ||
                source == android.content.pm.PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE
        } catch (_: Exception) { false }
    }


    /** L'écran « Infos de l'appli » de Vision, où se trouve le menu ⋮. */
    fun ouvrirInfosAppli(ctx: Context) {
        val i = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(i) } catch (_: Exception) {}
    }
}
