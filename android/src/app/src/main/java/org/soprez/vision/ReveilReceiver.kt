// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Réveil (lot 4) : les alarmes système arrivent ici.
 * - SONNER : l'occurrence (ou un report) est là → le service de sonnerie démarre, puis l'occurrence suivante s'arme ;
 * - SYNCHRO : H − synchro_avant_min → un relevé tout de suite (dernier changement fait par une automatisation) ;
 * - démarrage, mise à jour de l'appli, changement d'heure, de fuseau ou de date → tout se réarme en heure locale.
 */
class ReveilReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_SONNER = "org.soprez.vision.REVEIL_SONNER"
        const val ACTION_SYNCHRO = "org.soprez.vision.REVEIL_SYNCHRO"
        const val EXTRA_REVEIL = "reveil"
        const val EXTRA_OCC = "occurrence"
        const val EXTRA_PREVU = "prevu"
        const val EXTRA_REPORTS = "reports"
        const val EXTRA_ORDRE = "ordre"
    }

    override fun onReceive(ctx: Context, i: Intent) {
        when (i.action) {
            ACTION_SONNER -> {
                val occ = i.getStringExtra(EXTRA_OCC).orEmpty()
                if (i.getIntExtra(EXTRA_REPORTS, 0) > 0) Reveils.reportJoue(ctx)
                ReveilService.sonner(ctx, i.getStringExtra(EXTRA_REVEIL).orEmpty(), occ, i.getLongExtra(EXTRA_PREVU, System.currentTimeMillis()),
                    i.getIntExtra(EXTRA_REPORTS, 0), i.getStringExtra(EXTRA_ORDRE))
                Reveils.noterSonnee(ctx, occ)
                Reveils.rearmer(ctx)
            }
            ACTION_SYNCHRO -> Reveils.demanderReleve(ctx)
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_DATE_CHANGED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" -> {
                // Un réveil manqué (appareil éteint, redémarré) sonne tout de suite ; un report gardé revient.
                if (i.action != "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" && i.action != Intent.ACTION_DATE_CHANGED) {
                    Reveils.rattraper(ctx); Reveils.reprendreReport(ctx)
                }
                Reveils.rearmer(ctx)
            }
        }
    }
}
