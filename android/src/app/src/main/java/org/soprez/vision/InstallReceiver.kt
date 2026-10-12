// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/**
 * Résultat d'une session d'installation de Vision par elle-même.
 *
 * Trois issues : le système veut l'accord de l'utilisateur (première mise à
 * jour, ou Android trop ancien), c'est réussi (l'appli est relancée par le
 * système, on n'a rien à faire), ou c'est raté (on le dit).
 */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        if (i.action != ACTION) return
        val statut = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        MiseAJour.resultat(ctx, statut, i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        android.util.Log.i("Vision", "MiseAJour : résultat statut=$statut message=${i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)} " +
            "legacy=${i.getIntExtra("android.content.pm.extra.LEGACY_STATUS", 0)} extras=${i.extras?.keySet()}")
        when (statut) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // La confirmation du système va s'afficher : elle concerne Vision elle-même.
                MiseAJour.installationPropre()
                @Suppress("DEPRECATION")
                val confirmation = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Depuis l'app, l'écran du système s'ouvre tout de suite. Depuis le
                // fond, Android peut le refuser : la notification prend le relais.
                val ouvert = try { ctx.startActivity(confirmation); true } catch (_: Exception) { false }
                val pi = PendingIntent.getActivity(ctx, 8, confirmation,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                if (!ouvert || !SetupActivity.auPremierPlan) {
                    MiseAJour.notifier(ctx, ctx.getString(R.string.install_confirmer_titre),
                        ctx.getString(R.string.install_confirmer_texte), pi)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // Le processus est remplacé dans la foulée ; BootReceiver relance l'agent.
            }
            else -> {
                // Échec de la session (souvent une installation silencieuse refusée
                // tant que Vision n'est pas propriétaire des mises à jour). On ne
                // montre pas d'erreur : on retente par l'écran du système, qui
                // demande une confirmation et accorde le rôle au passage.
                MiseAJour.reessayerInteractif(ctx)
                if (!SetupActivity.auPremierPlan) {
                    val ouvrir = PendingIntent.getActivity(ctx, 9, Intent(ctx, SetupActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    MiseAJour.notifier(ctx, ctx.getString(R.string.install_prete_titre),
                        ctx.getString(R.string.install_prete_texte), ouvrir)
                }
            }
        }
    }

    companion object {
        const val ACTION = "org.soprez.vision.INSTALLATION"
    }
}
