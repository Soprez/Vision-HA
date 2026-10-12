// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context

/** 1.86.11 : le réglage « Vision lecteur de musique » de l'appareil (oui par défaut sur une télé). */
object LecteurVision {
    fun actif(ctx: Context): Boolean = ctx.getSharedPreferences("lecteur", 0).getBoolean("actif", true)
    fun poser(ctx: Context, oui: Boolean) {
        ctx.getSharedPreferences("lecteur", 0).edit().putBoolean("actif", oui).apply()
        if (oui && ReglagesTvActivity.estTele(ctx)) LecteurMusique.demarrer(ctx) else LecteurMusique.arreter()
    }
}
