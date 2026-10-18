// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.Build
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.view.KeyEvent
import org.json.JSONObject
import java.util.Locale

/**
 * 2.0.0 (point 8, décisions du projet du 2026-10-10) : notifications v2.
 * - « Important » est mis en avant (notification prioritaire), jamais bloquant ni plein écran.
 * - Le son est choisi par la personne sur son appareil : selon le type de message, discret, ou silencieux.
 * - Réponses : boutons proposés et texte libre sur le téléphone ; réponses proposées seulement sur la télé.
 * - La télé ne se met en pause que si l'expéditeur l'a demandé.
 */
object MessagesV2 {
    const val SON_SELON = "selon"
    const val SON_DISCRET = "discret"
    const val SON_SILENCIEUX = "silencieux"
    val SONS = listOf(SON_SELON, SON_DISCRET, SON_SILENCIEUX)
    private const val PREFS = "vision_messages"
    const val CLE_REPONSE = "reponse_libre"

    const val CANAL = "vision_v2"
    const val CANAL_IMPORTANT = "vision_v2_important"
    const val CANAL_INSISTANT = "vision_v2_insistant"
    const val CANAL_SILENCIEUX = "vision_v2_silencieux"

    fun estV2(m: JSONObject): Boolean = m.optInt("v") >= 2

    fun reponses(m: JSONObject): List<String> {
        val a = m.optJSONArray("reponses") ?: return emptyList()
        return (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() }.take(3)
    }

    fun nomSon(v: String): String = when (v) {
        SON_DISCRET -> Tr.s(R.string.msgv2_son_discret)
        SON_SILENCIEUX -> Tr.s(R.string.msgv2_son_silencieux)
        else -> Tr.s(R.string.msgv2_son_selon)
    }

    fun sonAppareil(ctx: Context): String = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("son", SON_SELON)?.takeIf { it in SONS } ?: SON_SELON
    fun noterSon(ctx: Context, v: String) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("son", v).apply()

    /** Le canal (donc le son) : le réglage de l'appareil d'abord ; « selon » suit le son du type envoyé. */
    fun canal(sonAppareil: String, sonType: String, important: Boolean): String = when {
        sonAppareil == SON_SILENCIEUX -> CANAL_SILENCIEUX
        sonAppareil == SON_DISCRET -> if (important) CANAL_IMPORTANT else CANAL
        sonType.isEmpty() || sonType.contains("Silent", true) -> CANAL_SILENCIEUX  // i18n-exception : noms de sons envoyés par le serveur (données)
        sonType.contains("Looping", true) -> CANAL_INSISTANT  // i18n-exception : noms de sons envoyés par le serveur (données)
        important -> CANAL_IMPORTANT
        else -> CANAL
    }

    /** La ligne d'en-tête : « Nolan · important ». */
    fun entete(m: JSONObject): String = listOf(m.optString("de"), if (m.optBoolean("important")) Tr.s(R.string.msgv2_important) else "",
        // Les noms de type par défaut du serveur (données) ne se répètent pas.
        m.optString("type_nom").takeIf { it != "Message" && it != "Important" }.orEmpty())  // i18n-exception : valeurs du serveur
        .filter { it.isNotEmpty() }.joinToString(" · ")

    // ------------------------------------------------------------------ téléphone

    private fun creerCanaux(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT < 26) return
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        nm.createNotificationChannel(NotificationChannel(CANAL, Tr.s(R.string.msgv2_canal_messages), NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = Tr.s(R.string.msgv2_canal_messages_desc); setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), attrs)
        })
        nm.createNotificationChannel(NotificationChannel(CANAL_IMPORTANT, Tr.s(R.string.msgv2_canal_importants), NotificationManager.IMPORTANCE_HIGH).apply {
            description = Tr.s(R.string.msgv2_canal_importants_desc); setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), attrs); enableVibration(true)
        })
        nm.createNotificationChannel(NotificationChannel(CANAL_INSISTANT, Tr.s(R.string.msgv2_canal_insistants), NotificationManager.IMPORTANCE_HIGH).apply {
            description = Tr.s(R.string.msgv2_canal_insistants_desc); setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), attrs); enableVibration(true)
        })
        nm.createNotificationChannel(NotificationChannel(CANAL_SILENCIEUX, Tr.s(R.string.msgv2_canal_silencieux), NotificationManager.IMPORTANCE_LOW).apply {
            description = Tr.s(R.string.msgv2_canal_silencieux_desc); setSound(null, null); enableVibration(false)
        })
    }

    private fun idNotif(m: JSONObject) = 3000 + (m.optInt("id") % 500)

    /** La notification d'un message v2 : boutons de réponse, « Répondre » en texte libre, jamais plein écran. */
    fun notifier(ctx: Context, m: JSONObject) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        creerCanaux(nm)
        val important = m.optBoolean("important")
        val canal = canal(sonAppareil(ctx), m.optString("sound"), important)
        val id = idNotif(m)
        val texte = m.optString("text")
        val titre = m.optString("title").ifEmpty { m.optString("de").ifEmpty { "Vision" } }
        val ouvrir = MessageActivity.intention(ctx, titre, texte, false, null, null).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        val pi = PendingIntent.getActivity(ctx, id, ouvrir, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, canal) else @Suppress("DEPRECATION") Notification.Builder(ctx).apply {
            setPriority(if (important) Notification.PRIORITY_HIGH else Notification.PRIORITY_DEFAULT)
        }
        b.setSmallIcon(R.drawable.ic_vision).setContentTitle(titre).setContentText(texte)
            .setSubText(entete(m).ifEmpty { null })
            .setStyle(Notification.BigTextStyle().bigText(texte))
            .setCategory(Notification.CATEGORY_MESSAGE).setAutoCancel(true).setContentIntent(pi)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
        if (important && Build.VERSION.SDK_INT >= 26) b.setColorized(false)
        reponses(m).forEachIndexed { k, r ->
            val i = Intent(ctx, MessageReponseReceiver::class.java).setAction(MessageReponseReceiver.ACTION)
                .putExtra("message", m.optInt("id")).putExtra("reponse", r).putExtra("notif", id).putExtra("titre", titre)
            val p = PendingIntent.getBroadcast(ctx, id * 10 + k, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            b.addAction(Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_vision), r, p).build())
        }
        if (m.optBoolean("libre", true) && Build.VERSION.SDK_INT >= 24) {
            val ri = RemoteInput.Builder(CLE_REPONSE).setLabel(ctx.getString(R.string.msgv2_ta_reponse)).build()
            val i = Intent(ctx, MessageReponseReceiver::class.java).setAction(MessageReponseReceiver.ACTION)
                .putExtra("message", m.optInt("id")).putExtra("notif", id).putExtra("titre", titre)
            val p = PendingIntent.getBroadcast(ctx, id * 10 + 9, i, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            b.addAction(Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_vision), ctx.getString(R.string.msgv2_repondre), p).addRemoteInput(ri).build())
        }
        try { nm.notify(id, b.build()) } catch (_: Exception) {}
        accuser(ctx, m.optInt("id"), "affiche")
        if (m.optBoolean("voix")) parler(ctx, "$titre. $texte")
    }

    /** Après une réponse : la notification le dit (et ne propose plus de répondre). */
    fun confirmer(ctx: Context, notif: Int, titre: String, reponse: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        creerCanaux(nm)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CANAL_SILENCIEUX) else @Suppress("DEPRECATION") Notification.Builder(ctx)
        b.setSmallIcon(R.drawable.ic_vision).setContentTitle(titre).setContentText(ctx.getString(R.string.msgv2_reponse_envoyee, reponse)).setAutoCancel(true).setTimeoutAfter(8000)
        try { nm.notify(notif, b.build()) } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ commun

    fun accuser(ctx: Context, id: Int, etat: String, detail: String = "") = MessagesRiches.accuserId(ctx, id, etat, detail)

    @Volatile private var tts: TextToSpeech? = null

    fun parler(ctx: Context, texte: String) {
        val app = ctx.applicationContext
        try {
            val t = tts
            if (t != null) { t.speak(texte, TextToSpeech.QUEUE_ADD, null, "vision" + SystemClock.elapsedRealtime()); return }
            var nouveau: TextToSpeech? = null
            nouveau = TextToSpeech(app) { ok ->
                if (ok == TextToSpeech.SUCCESS) {
                    nouveau?.language = Locale.FRANCE
                    nouveau?.speak(texte, TextToSpeech.QUEUE_ADD, null, "vision" + SystemClock.elapsedRealtime())
                }
            }
            tts = nouveau
        } catch (_: Exception) {}
    }

    /** Télé : pause de ce qui joue, seulement si l'expéditeur l'a demandé. */
    fun pauseMedia(ctx: Context) {
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
        } catch (_: Exception) {}
    }
}
