// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.ComponentName
import android.content.Context
import android.os.Build

/**
 * 1.93.06 (l'auteur, point 6a : Charlie jouait à Fortnite sur HDMI 3 et la musique de Vision continuait) : les entrées de la
 * télé — HDMI, TV en direct — passent par l'appli d'entrées du fabricant (TIF), qui ne demande pas toujours le focus
 * audio. Vision la reconnaît quand elle passe au premier plan (accessibilité, sinon historique d'usage) :
 * - les applis d'entrées connues (MediaTek des TCL, AOSP et Google, Philips, Sony, Amlogic…) ;
 * - les paquets qui déclarent une entrée TV au système ([android.media.tv.TvInputManager]) ;
 * - un écran dont le nom dit l'entrée (HDMI, TvInput, LiveTv, Passthrough).
 * Dans la liste des applis de [FocusMusique] (mode « certaines sources ou applis »), [JETON] les représente toutes.
 */
object SourcesTele {
    /** L'entrée « Entrées de la télé (HDMI, TV en direct) » de la liste (forme d'un nom de paquet, accepté par HA). */
    const val JETON = "vision.entrees_tele"

    private val CONNUES = setOf(
        "com.android.tv", "com.google.android.tv", "com.google.android.apps.tv.livetv",
        "com.mediatek.wwtv.tvcenter", "com.mediatek.tvinput", "com.mediatek.tv.oneworld.tvcenter",
        "com.tcl.tv", "com.tcl.tvinput", "com.tcl.tvweishi", "com.tcl.channelplus",
        "com.droidlogic.tvinput", "com.droidlogic.android.tv", "org.droidtv.playtv", "org.droidtv.channels",
        "com.sony.dtv.tvx", "com.sony.dtv.tvinput.external", "com.hisense.tv.tvplayer", "com.xiaomi.mitv.tvplayer",
        "com.realtek.tv", "com.amlogic.tvinput",
    )
    private val MOTS_ECRAN = listOf("hdmi", "tvinput", "livetv", "passthrough", "tvcenter", "inputsource")

    /** Pur : [pkg] (écran [classe]) est-il une entrée de la télé ? [declarees] : les paquets des entrées TV du système. */
    fun estEntree(pkg: String, classe: String, declarees: Set<String>, moi: String): Boolean {
        if (pkg.isEmpty() || pkg == moi) return false
        if (pkg in CONNUES || pkg in declarees) return true
        val p = pkg.lowercase(); val c = classe.lowercase()
        if (p.endsWith(".tvinput") || "livetv" in p || "tvcenter" in p) return true
        return MOTS_ECRAN.any { it in c }
    }

    @Volatile private var cache: Set<String>? = null

    /** Les paquets qui fournissent une entrée TV (HDMI, tuner) d'après le système ; vide hors télé. */
    fun declarees(ctx: Context): Set<String> {
        cache?.let { return it }
        val l = if (Build.VERSION.SDK_INT < 21) emptySet() else try {
            val tim = ctx.getSystemService(Context.TV_INPUT_SERVICE) as? android.media.tv.TvInputManager
            tim?.tvInputList?.mapNotNull { it.serviceInfo?.packageName }?.toSet() ?: emptySet()
        } catch (_: Exception) { emptySet() }
        cache = l
        return l
    }

    fun estEntree(ctx: Context, pkg: String, classe: String): Boolean = estEntree(pkg, classe, declarees(ctx), ctx.packageName)

    /** Une vraie activité (et non une fenêtre posée par-dessus : bulle, volume, notification) ? */
    fun estActivite(ctx: Context, pkg: String, classe: String): Boolean = classe.isNotEmpty() && try {
        ctx.packageManager.getActivityInfo(ComponentName(pkg, classe), 0); true
    } catch (_: Exception) { false }
}
