// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * Les réponses posées sous une notification (« 30 min », « 1 h », « Non »…) passent par ici :
 * écran transparent, empreinte ou code de gestion (Garde), puis la réponse part vers Home Assistant.
 * Avant 1.65.0, la notification répondait directement, même écran verrouillé, sans rien demander.
 */
class GardeActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val i = intent
        val pc = i.getStringExtra(ReponseReceiver.EXTRA_PC)
        val demande = i.getStringExtra(ReponseReceiver.EXTRA_DEMANDE)
        if (pc == null || demande == null) { finish(); return }
        val minutes = i.getIntExtra(ReponseReceiver.EXTRA_MINUTES, 0)
        val notif = i.getIntExtra(ReponseReceiver.EXTRA_NOTIF, 0)
        val decision = i.getStringExtra(ReponseReceiver.EXTRA_DECISION)
        val choisir = i.getBooleanExtra(ReponseReceiver.EXTRA_CHOISIR, false)
        val prochain = Etat.lireDate(i.getStringExtra(ReponseReceiver.EXTRA_PROCHAIN) ?: "")
        fun fermerNotif() { if (notif != 0) (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(notif) }
        // FAILLE (l'auteur, 16-10) : accorder depuis la notification = empreinte ou code à chaque fois ; refuser : garde ordinaire.
        val accorde = if (decision != null) decision != "non" else (choisir || minutes > 0)
        val garde: (() -> Unit) -> Unit = { f -> if (accorde) Garde.exigerOctroi(this, ParentsActivity.RAISON, annule = { finish() }, ok = f)
            else Garde.exiger(this, ParentsActivity.RAISON, annule = { finish() }, ok = f) }
        garde {
            when {
                decision != null -> { fermerNotif(); Acces.repondre(applicationContext, pc, demande, decision, minutes); finish() }
                choisir -> Temps.choisir(this, getString(R.string.garde_accorder_du_temps), prochain, annule = { finish() }) { ch ->
                    fermerNotif()
                    ReponseReceiver.envoyer(applicationContext, pc, demande,
                        if (ch.jusquaPlage) Temps.minutesJusqua(prochain) else ch.minutes, ch.jusquaPlage)
                    finish()
                }
                else -> { fermerNotif(); ReponseReceiver.envoyer(applicationContext, pc, demande, minutes); finish() }
            }
        }
    }

    companion object {
        fun intent(ctx: Context, src: Intent): Intent = Intent(ctx, GardeActivity::class.java)
            .putExtras(src).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
    }
}
