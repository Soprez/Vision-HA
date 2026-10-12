// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context

/**
 * Vision ne se bloque pas elle-même pendant son assistant de premier lancement.
 *
 * Pour accorder ses autorisations (accessibilité, administrateur de l'appareil, paramètres
 * restreints…), l'assistant ouvre des écrans du système que la protection des réglages ferme
 * d'ordinaire. Tant que l'assistant n'est pas terminé, quand il ouvre lui-même l'un de ces écrans,
 * les écrans du système passent : ciblé (Réglages, installateur, autorisations), et limité — la
 * fenêtre se ferme au retour dans Vision, dès qu'une autre appli prend la main, ou après trois
 * minutes.
 *
 * Une fois toutes les autorisations accordées, l'assistant est « terminé » pour de bon : la
 * protection redevient entière, et rouvrir l'assistant plus tard n'ouvre plus de fenêtre.
 */
object Assistant {
    private const val FENETRE = 3 * 60_000L

    /** Les écrans dont l'assistant a besoin : Réglages (de tout constructeur), installateur, autorisations. */
    private fun systeme(pkg: String): Boolean =
        pkg.endsWith(".settings") || pkg.endsWith("settings.v2") || pkg in MiseAJour.INSTALLATEURS ||
            pkg.endsWith(".permissioncontroller") || pkg == "com.miui.securitycenter" ||
            pkg == "com.coloros.safecenter" || pkg == "com.huawei.systemmanager"

    @Volatile private var depuis = 0L

    /**
     * Un appareil réglé avant cette version : l'assistant est réputé terminé (aucune fenêtre ne
     * s'ouvre chez lui). Un appareil neuf l'est quand plus aucune autorisation ne manque.
     */
    fun termine(ctx: Context): Boolean {
        val cfg = Config(ctx)
        if (cfg.assistantTermine) return true
        if (cfg.inscrit && AdminReceiver.estActif(ctx) && VisionAccessibility.estActive(ctx)) {
            cfg.assistantTermine = true
            return true
        }
        return false
    }

    fun terminer(ctx: Context) { Config(ctx).assistantTermine = true; depuis = 0 }

    /** L'assistant ouvre l'écran d'une de ses étapes. */
    fun ouvrir(ctx: Context) { if (!termine(ctx)) depuis = System.currentTimeMillis() }

    /** Retour dans Vision : la fenêtre se ferme (l'étape suivante en rouvrira une). */
    fun retour() { depuis = 0 }

    /** Cet écran du système vient-il d'être ouvert par l'assistant, pour une de ses étapes ? */
    fun autorise(ctx: Context, pkg: String): Boolean {
        if (depuis == 0L) return false
        if (System.currentTimeMillis() - depuis > FENETRE || termine(ctx)) { depuis = 0; return false }
        return systeme(pkg)
    }

    /** Un écran a pris la main : si ce n'est ni le système ni Vision, la fenêtre se ferme. */
    fun ecranVu(ctx: Context, pkg: String) {
        if (depuis == 0L) return
        if (pkg == ctx.packageName || pkg == "com.android.systemui" || pkg.contains("inputmethod") || pkg.contains("keyboard")) return
        if (!systeme(pkg)) depuis = 0
    }
}
