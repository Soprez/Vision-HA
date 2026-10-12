// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.Process

/** Ce qui est à l'écran, ce qui a servi, et depuis quand personne n'y touche. */
object Usage {
    @Volatile var dernierPaquet: String = ""

    /** 1.82.2 : un clavier (méthode de saisie) n'est jamais « l'appli au premier plan ». */
    fun estClavier(ctx: android.content.Context, pkg: String): Boolean = pkg.isNotEmpty() && try {
        (ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .inputMethodList.any { it.packageName == pkg }
    } catch (_: Exception) { false }
    /** L'appli qui était devant juste avant celle-ci (celle qu'on vient de quitter, vue de l'accueil). */
    @Volatile var paquetQuitte: String = ""
    @Volatile var derniereInteraction: Long = System.currentTimeMillis()
    @Volatile var ecranEteintDepuis: Long = 0

    fun accesUsage(ctx: Context): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        else ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Appli au premier plan d'après l'historique d'usage (repli sans accessibilité). */
    fun premierPlan(ctx: Context): String? {
        if (!accesUsage(ctx)) return null
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val fin = System.currentTimeMillis()
        val ev = usm.queryEvents(fin - 60_000, fin)
        val e = UsageEvents.Event()
        var dernier: String? = null
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            @Suppress("DEPRECATION")
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) dernier = e.packageName
        }
        return dernier
    }

    /** Applis passées au premier plan depuis `depuisMs`. */
    fun ouvertes(ctx: Context, depuisMs: Long): List<String> {
        if (!accesUsage(ctx)) return emptyList()
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val fin = System.currentTimeMillis()
        val ev = usm.queryEvents(fin - depuisMs, fin)
        val e = UsageEvents.Event()
        val vus = LinkedHashSet<String>()
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            @Suppress("DEPRECATION")
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) vus.add(e.packageName)
        }
        val lanceurs = lanceurs(ctx)
        return vus.filter { it != ctx.packageName && it !in lanceurs && !estSysteme(it) }.take(60)
    }

    fun ecranAllume(ctx: Context): Boolean =
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive

    fun inactifSecondes(ctx: Context): Int {
        val maintenant = System.currentTimeMillis()
        if (!ecranAllume(ctx)) {
            val depuis = if (ecranEteintDepuis > 0) ecranEteintDepuis else derniereInteraction
            return ((maintenant - depuis) / 1000).toInt().coerceAtLeast(600)
        }
        return ((maintenant - derniereInteraction) / 1000).toInt().coerceAtLeast(0)
    }

    @Volatile private var cacheLanceurs: Set<String>? = null
    /** Une appli installée ou supprimée : la liste des accueils sera relue. */
    fun oublierLanceurs() { cacheLanceurs = null }
    fun lanceurs(ctx: Context): Set<String> {
        cacheLanceurs?.let { return it }
        val i = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_HOME)
        val s = ctx.packageManager.queryIntentActivities(i, 0).map { it.activityInfo.packageName }.toSet()
        cacheLanceurs = s
        return s
    }

    private fun estSysteme(p: String): Boolean =
        p == "android" || p == "com.android.systemui" || p.startsWith("com.android.inputmethod") ||
            p.startsWith("com.google.android.inputmethod") || p.endsWith(".launcher")

    /** Libellé lisible d'un paquet (pour l'écran de blocage). */
    fun libelle(ctx: Context, pkg: String): String = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }

    /** Nom affiché de chaque paquet cité, pour que le catalogue dise « YouTube ». */
    fun libelles(ctx: Context, pkgs: Collection<String>): Map<String, String> =
        pkgs.filter { it.isNotEmpty() }.distinct().associateWith { libelle(ctx, it) }

    /**
     * Applis installées qui ont une icône de lancement (téléphone ou TV), sauf
     * Vision elle-même. C'est l'inventaire qu'un contrôle parental doit
     * connaître : on peut vouloir bloquer une appli avant qu'elle ne serve.
     */
    fun inventaire(ctx: Context): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val vus = LinkedHashMap<String, String>()
        for (cat in listOf(android.content.Intent.CATEGORY_LAUNCHER,
                           android.content.Intent.CATEGORY_LEANBACK_LAUNCHER)) {
            val i = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(cat)
            val liste = try { pm.queryIntentActivities(i, 0) } catch (_: Exception) { emptyList() }
            for (ri in liste) {
                val p = ri.activityInfo?.packageName ?: continue
                if (p == ctx.packageName || vus.containsKey(p)) continue
                vus[p] = try { ri.loadLabel(pm).toString() } catch (_: Exception) { p }
            }
        }
        return vus.entries.map { it.key to it.value }
    }
}
