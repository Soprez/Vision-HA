// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context

/**
 * 1.76.0 (point 6 bis) : le statut permanent de l'appareil dans les notifications (« Ouvert · encore 1 h 20 »),
 * quel que soit le lanceur. Visible si le module « Temps d'écran » est actif et que l'interrupteur de l'appareil
 * (« Statut dans les notifications ») est ouvert ; sinon la notification imposée par Android reste minimale.
 */
object StatutNotif {
    const val CANAL = "vision_statut"

    fun voulu(ctx: Context): Boolean = ctx.getSharedPreferences("local", Context.MODE_PRIVATE).getBoolean("statut_visible", true)
    fun poser(ctx: Context, oui: Boolean) = ctx.getSharedPreferences("local", Context.MODE_PRIVATE).edit().putBoolean("statut_visible", oui).apply()
    fun actif(ctx: Context): Boolean = voulu(ctx) && Config(ctx).inscrit && Modules.actif(ctx, Modules.TEMPS)

    /** « 1 h 20 », « 45 min ». */
    fun duree(min: Int): String = Tr.duree(min)

    /**
     * Logique pure : le titre et le texte du statut. `heure` met une heure en forme (« 20h00 »).
     */
    fun textes(horsLigne: Boolean, ferme: Boolean, pauseFin: Long, prochain: Long, message: String, maintenant: Long,
               heure: (Long) -> String): Pair<String, String> {
        val reste = if (prochain > maintenant) ((prochain - maintenant + 59_999) / 60_000).toInt() else -1
        return when {
            horsLigne -> Tr.s(R.string.statut_hors_ligne) to Tr.s(R.string.statut_hors_ligne_texte)
            pauseFin > maintenant -> Tr.s(R.string.statut_pause_jusqua, heure(pauseFin)) to Tr.s(R.string.statut_pause_texte)
            ferme -> (if (reste >= 0) Tr.s(R.string.statut_ferme_reouverture, heure(prochain)) else Tr.s(R.string.statut_ferme)) to
                message.ifEmpty { Tr.s(R.string.statut_ferme_texte) }
            reste in 0..(24 * 60) -> Tr.s(R.string.statut_ouvert_encore, duree(reste)) to Tr.s(R.string.statut_fermeture_a, heure(prochain))
            else -> Tr.s(R.string.statut_ouvert) to Tr.s(R.string.statut_aucune_fermeture)
        }
    }
}
