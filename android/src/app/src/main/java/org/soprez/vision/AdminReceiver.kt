// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context

/**
 * Administrateur de l'appareil : empêche la désinstallation directe de Vision.
 * Volontairement limité à `force-lock` ; aucun effacement, aucune prise de
 * contrôle. La désinstallation reste possible, mais passe par le code parent.
 */
class AdminReceiver : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: android.content.Intent): CharSequence =
        context.getString(R.string.admin_desactivation)

    companion object {
        fun composant(ctx: Context) = ComponentName(ctx, AdminReceiver::class.java)

        @Volatile private var demandeA = 0L
        /** Vision vient d'ouvrir l'écran d'activation : la protection des réglages le laisse passer une minute. */
        fun noterDemande() { demandeA = System.currentTimeMillis() }
        fun demandeEnCours(): Boolean = System.currentTimeMillis() - demandeA < 60_000

        fun estActif(ctx: Context): Boolean {
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            return dpm.isAdminActive(composant(ctx))
        }
    }
}
