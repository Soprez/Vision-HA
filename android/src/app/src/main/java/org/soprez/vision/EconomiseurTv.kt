// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * L'économiseur d'écran du système, sur télé : est-ce Vision ? Sinon, ouvrir son réglage, ou
 * s'en remettre au repli (Vision lance sa propre veille sur son accueil, voir ReglesVeille).
 */
object EconomiseurTv {
    /** Vrai : l'économiseur du système est Vision ; faux : un autre ; null : illisible sur cet appareil. */
    fun estVision(ctx: Context): Boolean? = try {
        ReglesVeille.economiseurEstVision(Settings.Secure.getString(ctx.contentResolver, "screensaver_components"))
    } catch (_: Exception) { null }

    /** L'étape de l'assistant est faite : Vision est l'économiseur, ou le repli a été choisi en connaissance de cause. */
    fun regle(ctx: Context): Boolean = estVision(ctx) == true || Local.repliChoisi(ctx)

    /** Ouvre le réglage de l'économiseur ; à défaut, l'affichage, puis les réglages. Faux si rien ne s'ouvre. */
    fun ouvrirReglage(act: Activity): Boolean {
        for (action in listOf(Settings.ACTION_DREAM_SETTINGS, Settings.ACTION_DISPLAY_SETTINGS, Settings.ACTION_SETTINGS)) {
            val i = Intent(action)
            if (try { i.resolveActivity(act.packageManager) == null } catch (_: Exception) { true }) continue
            try { Assistant.ouvrir(act); act.startActivity(i); return true } catch (_: Exception) {}
        }
        return false
    }

    /** La boîte qui explique et propose : ouvrir le réglage de la télé, ou utiliser la veille de Vision sur l'accueil. */
    fun proposer(act: Activity, apres: () -> Unit) {
        val etat = when (estVision(act)) {
            true -> act.getString(R.string.eco_est_vision)
            false -> act.getString(R.string.eco_pas_vision)
            null -> act.getString(R.string.eco_inconnu)
        }
        val delai = Local.delaiRepli(act)
        Ui.dialogue(act).setTitle(R.string.eco_titre)
            .setMessage(if (delai > 0) act.resources.getQuantityString(R.plurals.eco_explication_delai, delai, etat, delai)
                else act.getString(R.string.eco_explication, etat))
            .setPositiveButton(R.string.eco_ouvrir_reglage) { _, _ ->
                if (!ouvrirReglage(act)) {
                    Ui.dialogue(act).setTitle(R.string.eco_introuvable_titre)
                        .setMessage(R.string.eco_introuvable_texte)
                        .setPositiveButton(R.string.commun_ok) { _, _ -> Local.poserRepliChoisi(act, true); apres() }.show()
                }
            }
            .setNeutralButton(R.string.eco_utiliser_veille) { _, _ ->
                Local.poserRepliChoisi(act, true)
                if (Local.delaiRepli(act) <= 0) Local.poserDelaiRepli(act, ReglesVeille.REPLI_DEFAUT)
                apres()
            }
            .setNegativeButton(R.string.commun_plus_tard, null)
            .show()
    }
}
