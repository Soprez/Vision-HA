// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context

/**
 * Faille du 16-10 (proposition validée en essai) : un appareil Décideur a toujours un code de gestion ; sans lui,
 * l'accueil, À décider et les réglages imposent de le choisir. Les essais à l'écran en posent donc un au démarrage
 * (s'il n'y en a pas déjà) et rendent l'appareil tel qu'ils l'ont trouvé à la fin.
 */
object CodeEssai {
    const val CODE = "246810"
    private const val PREFS = "vision" // Config
    private var avant: String? = null
    private var pose = false

    fun poser(ctx: Context) {
        val cfg = Config(ctx)
        if (cfg.aUnCode) return
        avant = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("pin", null)
        cfg.definirCode(CODE)
        pose = true
    }

    fun rendre(ctx: Context) {
        if (!pose) return
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (avant == null) p.remove("pin") else p.putString("pin", avant)
        p.commit()
        pose = false
        avant = null
    }
}
