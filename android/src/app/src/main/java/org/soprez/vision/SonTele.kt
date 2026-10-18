// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log

/**
 * 1.90.2 (l'auteur, télé du bureau : « 3 notifications, dont Alarme insistant, sans aucun son ; Free TV a du son ») : sur
 * une télé, la sonnerie de notification du système est absente ou muette (Google TV), et son canal n'est pas celui que
 * règle la télécommande. Le son d'un message vient donc de l'appli (carillon, ou alarme pour un message insistant) et
 * passe par le canal MÉDIA : il sort au volume de la télé choisi par l'auteur. Jamais de réglage du volume système, jamais
 * de sourdine, jamais de demande de focus audio (la musique en cours continue).
 */
object SonTele {
    private const val TAG = "VisionSon"
    private var joueur: MediaPlayer? = null

    /** Pure : quel son pour ce message (null : aucun). */
    fun choix(sound: String, loop: Boolean, important: Boolean): Int? {
        if (sound.isEmpty() || sound.contains("Silent", true) || sound.contains("silencieux", true)) return null
        val alarme = loop || important || listOf("alarm", "alarme", "insist", "urgent").any { sound.contains(it, true) }
        return if (alarme) R.raw.vision_alarme else R.raw.vision_carillon
    }

    /** Les attributs du son : canal média (volume de la télé). */
    fun attributs(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()

    fun jouer(ctx: Context, son: Int, boucle: Boolean): Boolean {
        arreter()
        return try {
            val afd = ctx.resources.openRawResourceFd(son)
            val p = MediaPlayer()
            p.setAudioAttributes(attributs())
            afd.use { p.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            p.isLooping = boucle
            p.setOnCompletionListener { if (!boucle) arreter() }
            p.prepare(); p.start()
            joueur = p
            true
        } catch (e: Exception) { Log.w(TAG, "son impossible : $e"); false }
    }

    val joue: Boolean get() = try { joueur?.isPlaying == true } catch (_: Exception) { false }

    fun arreter() {
        val p = joueur ?: return
        joueur = null
        try { p.stop() } catch (_: Exception) {}
        try { p.release() } catch (_: Exception) {}
    }
}
