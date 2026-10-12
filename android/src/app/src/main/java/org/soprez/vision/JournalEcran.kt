// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/**
 * 1.86.13 (l'auteur : « crée un système de log pour savoir d'où ça vient ») : sur la télé, quand l'écran Musique passe
 * derrière une autre appli (et pas derrière un écran de Vision), Home Assistant le note dans le journal Musique avec
 * l'appli qui a pris l'écran (accès « usage » déjà accordé à Vision ; sinon seulement l'heure).
 */
object JournalEcran {
    private val main = Handler(Looper.getMainLooper())
    private var tache: Runnable? = null

    fun surveiller(ctx: Context) {
        annuler()
        val app = ctx.applicationContext
        val t = Runnable {
            val pkg = try { Usage.premierPlan(app) } catch (_: Exception) { null }
            if (pkg == app.packageName) return@Runnable  // un autre écran de Vision (recherche, réglages…)
            Thread {
                try {
                    val cfg = Config(app)
                    Net.post(app, cfg, "/api/pc_parental/party/commande", JSONObject().put("id", cfg.id).put("secret", cfg.secret)
                        .put("action", "premier_plan_perdu").put("paquet", pkg ?: "inconnu (accès « usage » refusé)"), 8_000)
                } catch (_: Exception) {}
            }.also { it.isDaemon = true }.start()
        }
        tache = t
        main.postDelayed(t, 1_500)
    }

    fun annuler() { tache?.let { main.removeCallbacks(it) }; tache = null }
}
