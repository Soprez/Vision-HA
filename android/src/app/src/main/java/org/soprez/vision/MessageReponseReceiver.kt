// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 2.0.0 (notifications v2) : une réponse touchée sous la notification (bouton proposé) ou écrite dans
 * « Répondre » (texte libre). Elle part à Home Assistant comme accusé « repondu » (renvoyée plus tard si le réseau
 * manque) ; la notification dit « Réponse envoyée ».
 */
class MessageReponseReceiver : BroadcastReceiver() {
    companion object { const val ACTION = "org.soprez.vision.REPONSE_MESSAGE" }

    override fun onReceive(ctx: Context, i: Intent) {
        if (i.action != ACTION) return
        val id = i.getIntExtra("message", 0)
        val libre = RemoteInput.getResultsFromIntent(i)?.getCharSequence(MessagesV2.CLE_REPONSE)?.toString()
        val reponse = (libre ?: i.getStringExtra("reponse")).orEmpty().trim().take(200)
        if (id <= 0 || reponse.isEmpty()) return
        MessagesV2.accuser(ctx, id, "repondu", reponse)
        MessagesV2.confirmer(ctx, i.getIntExtra("notif", 3000), i.getStringExtra("titre").orEmpty().ifEmpty { "Vision" }, reponse)
    }
}
