// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ce que Home Assistant veut dire à la personne qui tient l'appareil :
 * un message envoyé depuis Vision (« à table », « c'est l'heure »), un
 * préavis de fermeture de session, une plage d'étiquettes qui va fermer,
 * ou, pour un parent, la demande de temps d'un enfant à laquelle on répond
 * directement depuis la notification.
 *
 * Tout passe par une vraie notification système, visible même si l'appareil
 * est sur une autre appli ou en veille. Un message urgent s'affiche en plus
 * en plein écran quand Android l'autorise.
 */
object Messages {
    private const val CANAL = "vision_messages"
    private const val CANAL_URGENT = "vision_urgent"
    private const val BASE_MESSAGE = 1000
    private const val BASE_PREAVIS = 2000
    private const val RAPPEL_MS = 20 * 60_000L

    /** Préavis déjà annoncés : texte → horodatage, pour ne pas répéter à chaque relevé. */
    private val annonces = HashMap<String, Long>()
    private var compteur = 0

    /** Messages du service pc_parental.notify : { id, title, text, urgent, demande? }. */
    fun afficher(ctx: Context, a: JSONArray?) {
        a ?: return
        for (i in 0 until a.length()) {
            val m = a.optJSONObject(i) ?: continue
            // Chaque notification relève d'un module (messages, demandes, déverrouillage) : coupé, elle ne s'affiche pas.
            if (!Modules.notificationPermise(Modules.actifs(ctx), m.optJSONObject("demande") != null,
                    m.optJSONObject("acces") != null, m.optJSONObject("deverrouillage") != null)) continue
            if (m.optJSONObject("deverrouillage") != null) { notifierDeverrouillage(ctx, m); continue }
            // 2.0.0 (notifications v2) : réponses sous la notification, « Important » sans plein écran.
            if (MessagesV2.estV2(m) && m.optString("bloquant", "non") == "non") { MessagesV2.notifier(ctx, m); continue }
            val titre = m.optString("title", "").ifEmpty { "Vision" }
            val texte = m.optString("text", "")
            if (texte.isEmpty()) continue
            val urgent = m.optBoolean("urgent", false)
            val demande = m.optJSONObject("demande")
            val acces = m.optJSONObject("acces")
            val id = BASE_MESSAGE + (m.optInt("id", ++compteur) % 500)
            notifier(ctx, id, titre, texte, urgent, demande, acces)
            // En plus de la notification, on tente l'écran plein : il n'apparaît
            // que si l'utilisateur a accordé « Afficher par-dessus », sinon
            // Android l'ignore sans bruit et la notification reste.
            if (urgent && peutRecouvrir(ctx)) MessageActivity.afficher(ctx, titre, texte, true, demande, acces)
        }
    }

    /**
     * Préavis : le verrouillage qui approche (`warn` + `message`) et les
     * plages d'étiquettes (`avertissements`). Chaque texte n'est annoncé
     * qu'une fois par période, pas toutes les vingt secondes.
     */
    fun preavis(ctx: Context, verrouillage: String, plages: JSONArray?) {
        val maintenant = System.currentTimeMillis()
        val textes = ArrayList<String>()
        if (verrouillage.isNotEmpty()) textes.add(verrouillage)
        if (plages != null) for (i in 0 until plages.length()) {
            plages.optString(i).takeIf { it.isNotEmpty() }?.let { textes.add(it) }
        }
        synchronized(annonces) {
            annonces.entries.removeAll { maintenant - it.value > RAPPEL_MS }
            for (t in textes) {
                if (annonces.containsKey(t)) continue
                annonces[t] = maintenant
                notifier(ctx, BASE_PREAVIS + (t.hashCode() and 0xFF), "Vision", t, false, null)
            }
        }
    }

    /** Un message simple, en notification (déverrouillage réussi…). */
    fun info(ctx: Context, titre: String, texte: String) = notifier(ctx, BASE_MESSAGE + (++compteur % 500), titre, texte, false, null)

    /** Demande de déverrouillage d'un enfant, chez un parent : un toucher ouvre l'écran pour décider. */
    private fun notifierDeverrouillage(ctx: Context, m: JSONObject) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) creerCanaux(nm)
        val d = m.optJSONObject("deverrouillage")!!
        val qui = d.optString("personne").ifEmpty { d.optString("nom") }
        val quoi = Deverrouillage.libelle(ctx, d.optString("action"))
        val motif = d.optString("motif")
        val texte = if (motif.isNotEmpty()) ctx.getString(R.string.messagesnotif_deverrouillage_motif, qui, quoi, d.optInt("minutes"), motif)
            else ctx.getString(R.string.messagesnotif_deverrouillage, qui, quoi, d.optInt("minutes"))
        val id = 3000 + (d.optString("demande").hashCode() and 0xFFF)
        val pi = PendingIntent.getActivity(ctx, id, DeverrouillageParentActivity.intention(ctx), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CANAL_URGENT) else @Suppress("DEPRECATION") Notification.Builder(ctx).apply { setDefaults(Notification.DEFAULT_ALL); setPriority(Notification.PRIORITY_HIGH) }
        b.setSmallIcon(R.drawable.ic_vision).setContentTitle(ctx.getString(R.string.messagesnotif_deverrouillage_titre)).setContentText(texte)
            .setStyle(Notification.BigTextStyle().bigText(texte)).setAutoCancel(true).setContentIntent(pi)
            .setCategory(Notification.CATEGORY_MESSAGE)
        try { nm.notify(id, b.build()) } catch (_: Exception) {}
    }

    private fun notifier(ctx: Context, id: Int, titre: String, texte: String, urgent: Boolean, demande: JSONObject?, acces: JSONObject? = null) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val canal = if (urgent) CANAL_URGENT else CANAL
        if (Build.VERSION.SDK_INT >= 26) creerCanaux(nm)
        val ouvrir = MessageActivity.intention(ctx, titre, texte, urgent, demande, acces).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(ctx, id, ouvrir,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, canal)
                else @Suppress("DEPRECATION") Notification.Builder(ctx).apply {
                    setDefaults(Notification.DEFAULT_ALL)
                    setPriority(if (urgent) Notification.PRIORITY_MAX else Notification.PRIORITY_HIGH)
                }
        b.setSmallIcon(R.drawable.ic_vision)
            .setContentTitle(titre)
            .setContentText(texte)
            .setStyle(Notification.BigTextStyle().bigText(texte))
            .setCategory(if (urgent) Notification.CATEGORY_ALARM else Notification.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        if (urgent) b.setFullScreenIntent(pi, true)
        if (demande != null) {
            // Les trois réponses, directement sous la notification.
            var k = 0
            for ((minutes, lbl) in listOf(30 to Tr.duree(30), -1 to ctx.getString(R.string.messagesnotif_autre), 0 to ctx.getString(R.string.messagesnotif_non))) {
                val rep = Intent(ctx, GardeActivity::class.java).setAction(ReponseReceiver.ACTION)
                    .putExtra(ReponseReceiver.EXTRA_PC, demande.optString("pc"))
                    .putExtra(ReponseReceiver.EXTRA_DEMANDE, demande.optString("id"))
                    .putExtra(ReponseReceiver.EXTRA_MINUTES, minutes.coerceAtLeast(0))
                    .putExtra(ReponseReceiver.EXTRA_NOTIF, id)
                    .putExtra(ReponseReceiver.EXTRA_CHOISIR, minutes < 0)
                    .putExtra(ReponseReceiver.EXTRA_PROCHAIN, demande.optString("prochain"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                val pr = PendingIntent.getActivity(ctx, id * 10 + k++, rep,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                b.addAction(if (Build.VERSION.SDK_INT >= 23)
                    Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_vision), lbl, pr).build()
                    else @Suppress("DEPRECATION") Notification.Action.Builder(R.drawable.ic_vision, lbl, pr).build())
            }
        }
        if (acces != null) {
            var k = 0
            for ((decision, lbl) in listOf("temporaire" to Tr.duree(60), "toujours" to ctx.getString(R.string.messagesnotif_autoriser), "non" to ctx.getString(R.string.messagesnotif_non))) {
                val rep = Intent(ctx, GardeActivity::class.java).setAction(ReponseReceiver.ACTION)
                    .putExtra(ReponseReceiver.EXTRA_PC, acces.optString("pc"))
                    .putExtra(ReponseReceiver.EXTRA_DEMANDE, acces.optString("id"))
                    .putExtra(ReponseReceiver.EXTRA_DECISION, decision)
                    .putExtra(ReponseReceiver.EXTRA_MINUTES, 60)
                    .putExtra(ReponseReceiver.EXTRA_NOTIF, id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                val pr = PendingIntent.getActivity(ctx, id * 10 + 5 + k++, rep,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                b.addAction(if (Build.VERSION.SDK_INT >= 23)
                    Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_vision), lbl, pr).build()
                    else @Suppress("DEPRECATION") Notification.Action.Builder(R.drawable.ic_vision, lbl, pr).build())
            }
        }
        try { nm.notify(id, b.build()) } catch (_: Exception) {}
    }

    private fun creerCanaux(nm: NotificationManager) {
        fun t(id: Int) = Tr.s(id)
        val son = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        nm.createNotificationChannel(NotificationChannel(
            CANAL, t(R.string.messagesnotif_canal), NotificationManager.IMPORTANCE_HIGH).apply {
            description = t(R.string.messagesnotif_canal_description)
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), son)
            enableVibration(true)
        })
        nm.createNotificationChannel(NotificationChannel(
            CANAL_URGENT, t(R.string.messagesnotif_canal_urgent), NotificationManager.IMPORTANCE_HIGH).apply {
            description = t(R.string.messagesnotif_canal_urgent_description)
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), son)
            enableVibration(true)
            setBypassDnd(true)
        })
    }

    private fun peutRecouvrir(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx)
}
