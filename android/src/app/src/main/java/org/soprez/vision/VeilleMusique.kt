// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.os.PowerManager

/**
 * 1.86.14 (l'auteur : « j'ai l'écran de veille qui essaie de se lancer » pendant la musique) : sur une télé, aucune veille
 * de Vision tant que le lecteur Music Assistant de CETTE télé joue — même si Vision n'est pas au premier plan (récepteur
 * Cast devant) et même si la lecture est pilotée depuis un téléphone. Le critère vient de Home Assistant (l'état
 * « playing » des lecteurs de la télé, dans le relevé) et, en direct, du lecteur de Vision lui-même (LecteurMusique : Squeezelite ou Sendspin).
 *
 * Tant que ça joue, l'écran reste allumé (le rêve du système ne vient pas non plus) ; le verrou se relâche tout seul
 * si Home Assistant se tait (3 min), pour ne jamais garder une télé allumée à tort.
 */
object VeilleMusique {
    private const val FRAIS_MS = 120_000L
    private const val VERROU_MS = 180_000L
    @Volatile private var haJoue = false
    @Volatile private var haA = 0L
    private var verrou: PowerManager.WakeLock? = null

    /** Le relevé de Home Assistant : le lecteur de la télé joue-t-il ? */
    fun releve(ctx: Context, joue: Boolean, maintenant: Long = System.currentTimeMillis()) {
        haJoue = joue; haA = maintenant
        maintenir(ctx)
    }

    fun enLecture(maintenant: Long = System.currentTimeMillis()): Boolean =
        (haJoue && maintenant - haA < FRAIS_MS) || LecteurMusique.etat == "lecture"

    /**
     * Vrai : pas de veille automatique sur cet appareil (télé seulement). 1.91.0 : la musique que la veille a lancée
     * elle-même (VeilleAudio) ne compte pas ; elle s'arrête de toute façon à la sortie de veille.
     */
    fun bloque(ctx: Context): Boolean = try { ReglagesTvActivity.estTele(ctx) && enLecture() && !VeilleAudio.demandee } catch (_: Exception) { false }

    /** Écran allumé tant que ça joue (le rêve du système attend), relâché sinon. */
    @Suppress("DEPRECATION")
    @Synchronized fun maintenir(ctx: Context) {
        try {
            val v = verrou
            if (bloque(ctx)) {
                val pm = ctx.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                val n = v ?: pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "vision:musique").apply { setReferenceCounted(false) }
                n.acquire(VERROU_MS)
                verrou = n
            } else if (v != null) {
                if (v.isHeld) v.release()
                verrou = null
            }
        } catch (_: Exception) {}
    }

    /** Pour les tests. */
    fun oublier() { haJoue = false; haA = 0L }
}
