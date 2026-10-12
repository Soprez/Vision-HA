// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 1.81.0 (point 14 bis) : les pochettes des lecteurs (widget Lecteur, écran Party), lues par Vision dans Home
 * Assistant (/api/pc_parental/party/pochette) avec l'identifiant de l'appareil : jamais l'adresse de l'image ni
 * son jeton. Une empreinte (`pochette`, dans les attributs) dit quand le morceau change ; on garde les dernières.
 */
object Pochettes {
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 12
    }
    private val enCours = ConcurrentHashMap.newKeySet<String>()
    /** Pas d'image pour cette empreinte (le serveur a répondu 204) : on ne redemande pas. */
    private val sansImage = ConcurrentHashMap.newKeySet<String>()

    fun garde(entite: String, empreinte: String): Bitmap? = synchronized(cache) { cache["$entite|$empreinte"] }

    /** Pose la pochette dans l'image (tout de suite si elle est gardée, sinon dès qu'elle arrive) ; vide sans empreinte. */
    fun poser(ctx: Context, vue: ImageView, entite: String, empreinte: String) {
        val cle = "$entite|$empreinte"
        vue.tag = cle
        if (empreinte.isEmpty()) { vue.setImageDrawable(null); return }
        garde(entite, empreinte)?.let { vue.setImageBitmap(it); return }
        vue.setImageDrawable(null)
        if (cle in sansImage || !enCours.add(cle)) return
        val app = ctx.applicationContext
        Thread {
            val octets = try {
                val cfg = Config(app)
                Net.postBytes(app, cfg, "/api/pc_parental/party/pochette",
                    JSONObject().put("id", cfg.id).put("secret", cfg.secret).put("entite", entite), 10_000)
            } catch (_: Exception) { null }
            enCours.remove(cle)
            // Échec réseau : on réessaiera au prochain dessin. Réponse vide (pas d'image) : on ne redemande pas.
            if (octets == null) return@Thread
            val bmp = try { BitmapFactory.decodeByteArray(octets, 0, octets.size) } catch (_: Exception) { null }
            if (bmp == null) { sansImage.add(cle); return@Thread }
            synchronized(cache) { cache[cle] = bmp }
            main.post { if (vue.tag == cle) vue.setImageBitmap(bmp) }
        }.also { it.isDaemon = true }.start()
    }
}
