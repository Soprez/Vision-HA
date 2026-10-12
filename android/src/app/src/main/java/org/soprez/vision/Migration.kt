// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

/**
 * Deux aides pour le passage à la nouvelle appli (et pour toute installation hors Play Store).
 *
 * 1. Les « paramètres restreints » d'Android (13 et plus) : une appli installée hors d'une
 *    boutique (APK téléchargé) ne reçoit pas certaines autorisations sensibles — accessibilité,
 *    et selon la version d'Android d'autres encore (données d'utilisation…) — tant qu'on ne l'a
 *    pas autorisé dans Infos de l'appli → ⋮ → « Autoriser les paramètres restreints ». Android
 *    ne propose ce menu qu'après un premier refus (« Paramètre restreint », « Accès refusé »).
 *
 * 2. L'ancienne Vision (paquet Migration.ANCIEN, même signature) : la nouvelle la laisse
 *    désinstaller sans code ni pause, et seulement elle. La fenêtre s'ouvre au bouton
 *    « Supprimer l'ancienne version » et se referme dès qu'on quitte l'écran de
 *    désinstallation, que l'ancienne a disparu, ou au bout de trois minutes.
 */
object Migration {
    // Identifiant de paquet des premières versions de Vision (jusqu'à la 1.48) : sert seulement
    // à reconnaître l'ancienne appli pour proposer sa désinstallation.
    const val ANCIEN = "fr.familleroy.vision"

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

    // ------------------------------------------------------- Ancienne Vision

    /** L'ancienne Vision est là, et c'est bien la nôtre (même certificat de signature). */
    fun ancienneInstallee(ctx: Context): Boolean {
        val pm = ctx.packageManager
        return try {
            pm.getPackageInfo(ANCIEN, 0)
            pm.checkSignatures(ctx.packageName, ANCIEN) == PackageManager.SIGNATURE_MATCH
        } catch (_: Exception) { false }
    }

    @Volatile private var fenetreDepuis = 0L
    private const val FENETRE = 3 * 60_000L

    /** Ouvre l'écran système de désinstallation de l'ancienne Vision, et seulement d'elle. */
    fun supprimerAncienne(ctx: Context): Boolean {
        if (!ancienneInstallee(ctx)) return false
        fenetreDepuis = System.currentTimeMillis()
        val i = Intent(Intent.ACTION_DELETE, Uri.parse("package:$ANCIEN")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { ctx.startActivity(i); true } catch (_: Exception) { fenetreDepuis = 0; false }
    }

    private fun fermer() { fenetreDepuis = 0 }

    /**
     * Cet écran fait-il partie de la désinstallation de l'ancienne Vision qu'on vient de lancer ?
     * L'installateur du système, et l'écran d'administrateur de l'appareil que l'ancienne Vision
     * doit d'abord quitter. Tout autre écran referme la fenêtre : on ne peut pas s'en servir pour
     * aller désinstaller autre chose, la nouvelle Vision comprise.
     */
    fun ecranAutorise(ctx: Context, pkg: String, classe: String): Boolean {
        if (fenetreDepuis == 0L) return false
        if (System.currentTimeMillis() - fenetreDepuis > FENETRE || !ancienneInstallee(ctx)) { fermer(); return false }
        if (pkg in MiseAJour.INSTALLATEURS) return true
        if (pkg.endsWith(".settings") && classe.contains("DeviceAdminAdd")) return true
        return false
    }

    /** Un écran a pris la main : s'il ne fait pas partie de la désinstallation, la fenêtre se ferme. */
    fun ecranVu(ctx: Context, pkg: String, classe: String) {
        if (fenetreDepuis == 0L) return
        if (pkg == ctx.packageName || pkg == "com.android.systemui" || pkg.contains("inputmethod") || pkg.contains("keyboard")) return
        if (!ecranAutorise(ctx, pkg, classe)) fermer()
    }
}
