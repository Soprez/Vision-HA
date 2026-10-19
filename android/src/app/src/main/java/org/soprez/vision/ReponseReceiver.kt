// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.json.JSONObject

/**
 * Le parent a touché « 30 min », « 1 h » ou « Non » sur la notification :
 * on transmet à Home Assistant, qui ouvre l'appareil de l'enfant et le
 * prévient. L'appareil qui répond doit porter l'étiquette « Parents ».
 */
class ReponseReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        if (i.action != ACTION) return
        // 1.65.0 : plus de réponse directe (notifications d'avant la mise à jour) : empreinte ou code d'abord.
        try { ctx.startActivity(GardeActivity.intent(ctx, i)) } catch (_: Exception) {}
    }

    companion object {
        const val ACTION = "org.soprez.vision.REPONSE_TEMPS"
        const val EXTRA_PC = "pc"
        const val EXTRA_DEMANDE = "demande"
        const val EXTRA_MINUTES = "minutes"
        const val EXTRA_NOTIF = "notif"
        /** Présent pour une demande d'accès : toujours | temporaire | non. */
        const val EXTRA_DECISION = "decision"

        /** Le choix « Autre… » d'une notification : ouvre la garde puis le choix de la durée. */
        const val EXTRA_CHOISIR = "choisir"
        const val EXTRA_PROCHAIN = "prochain"

        fun envoyer(ctx: Context, pc: String, demande: String, minutes: Int, apres: ((Geste.Resultat) -> Unit)? = null) =
            envoyer(ctx, pc, demande, minutes, false, apres)

        fun envoyer(ctx: Context, pc: String, demande: String, minutes: Int, jusquaPlage: Boolean, apres: ((Geste.Resultat) -> Unit)? = null) {
            val cfg = Config(ctx)
            val principal = Handler(Looper.getMainLooper())
            Thread {
                var ok = false
                val texte = try {
                    val r = Net.post(ctx, cfg, "/api/pc_parental/reponse", JSONObject()
                        .put("id", cfg.id).put("secret", cfg.secret)
                        .put("pc", pc).put("demande", demande).put("minutes", minutes)
                        .apply { if (jusquaPlage) put("jusqua_plage", true) })
                    // Déjà réglée ailleurs : c'est fini aussi, l'affichage retire la demande.
                    ok = r.optBoolean("deja") || r.optBoolean("ok")
                    when {
                        r.optBoolean("deja") -> ctx.getString(R.string.reponse_deja_traite, Vocabulaire.decideur(ctx))
                        !r.optBoolean("ok") -> ctx.getString(R.string.reponse_refusee_ha)
                        jusquaPlage -> r.optString("retour").ifEmpty { ctx.getString(R.string.reponse_accorde_plage) }
                        minutes > 0 -> ctx.getString(R.string.reponse_accorde_minutes, Temps.duree(minutes))
                        else -> ctx.getString(R.string.reponse_refuse)
                    }
                } catch (e: Net.Echec) {
                    Geste.messageEchec(e.code)
                } catch (e: Exception) { ReseauLocal.message(ctx, e) }
                principal.post {
                    Ui.toast(ctx, texte)
                    apres?.invoke(Geste.Resultat(ok, texte))
                }
            }.start()
        }
    }
}
