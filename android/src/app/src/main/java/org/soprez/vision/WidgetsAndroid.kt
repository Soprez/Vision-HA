// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewConfiguration

/**
 * Les widgets de toutes les applis Android sur l'accueil Vision (Vision 1.62.0, point 5 du backlog) :
 * Vision les héberge comme un lanceur (AppWidgetHost). Téléphones et tablettes seulement ; sur un rôle
 * sans la fonction « accueil », poser un widget demande le code de gestion (décision du projet 3-A).
 *
 * Une case de la grille porte la clé « widget:android:<id> », où <id> est l'identifiant Android du widget.
 * Le retirer de l'accueil libère son identifiant (nettoyer), sinon l'appli qui le fournit le tiendrait à jour pour rien.
 */
object WidgetsAndroid {
    private const val HOTE = 0x5649 // « VI »
    const val REQ_LIER = 4101
    const val REQ_REGLER = 4102
    const val PREFIXE = "android:"

    @Volatile private var hote: Hote? = null

    fun hote(ctx: Context): AppWidgetHost = hote ?: synchronized(this) { hote ?: Hote(ctx.applicationContext).also { hote = it } }

    fun estAndroid(code: String) = code.startsWith(PREFIXE)
    fun id(code: String): Int? = code.removePrefix(PREFIXE).toIntOrNull()
    fun cle(id: Int) = Accueil.PREFIXE_WIDGET + PREFIXE + id

    fun gestionnaire(ctx: Context): AppWidgetManager = AppWidgetManager.getInstance(ctx)

    /** Les widgets proposés par les applis installées, triés par appli puis par nom. */
    fun fournisseurs(ctx: Context): List<AppWidgetProviderInfo> = try {
        gestionnaire(ctx).installedProviders.sortedWith(compareBy({ appli(ctx, it).lowercase() }, { libelle(ctx, it).lowercase() }))
    } catch (_: Exception) { emptyList() }

    fun libelle(ctx: Context, info: AppWidgetProviderInfo): String = try {
        if (Build.VERSION.SDK_INT >= 21) info.loadLabel(ctx.packageManager) else @Suppress("DEPRECATION") info.label
    } catch (_: Exception) { info.provider.className.substringAfterLast('.') } ?: ""

    fun appli(ctx: Context, info: AppWidgetProviderInfo): String = try {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(info.provider.packageName, 0)).toString()
    } catch (_: Exception) { info.provider.packageName }

    /** Sa taille de départ en cases de la grille, d'après la taille minimale que l'appli demande. */
    fun cellules(info: AppWidgetProviderInfo, celluleLargeurDp: Float, celluleHauteurDp: Float): Pair<Int, Int> {
        fun n(dp: Int, cellule: Float) = kotlin.math.ceil(dp / cellule.coerceAtLeast(1f)).toInt().coerceIn(1, Accueil.COLONNES)
        return n(info.minWidth, celluleLargeurDp) to n(info.minHeight, celluleHauteurDp)
    }

    /** Sa taille de départ : celle que l'appli vise (targetCell, Android 12+), sinon d'après sa taille minimale. */
    fun cellulesDepart(info: AppWidgetProviderInfo, celluleLargeurDp: Float, celluleHauteurDp: Float): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= 31 && info.targetCellWidth > 0 && info.targetCellHeight > 0)
            return info.targetCellWidth.coerceIn(1, Accueil.COLONNES) to info.targetCellHeight.coerceIn(1, Redim.LIGNES_MAX)
        return cellules(info, celluleLargeurDp, celluleHauteurDp)
    }

    /** Les tailles permises par l'appli (min, minResize, maxResize, resizeMode), en cases de la grille. */
    fun bornes(info: AppWidgetProviderInfo?, celluleLargeurDp: Float, celluleHauteurDp: Float, actuelle: Pair<Int, Int>): Redim.Bornes {
        if (info == null) return Redim.Bornes(actuelle.first, actuelle.second, actuelle.first, actuelle.second, false, false)
        val maxL = if (Build.VERSION.SDK_INT >= 31) info.maxResizeWidth else 0
        val maxH = if (Build.VERSION.SDK_INT >= 31) info.maxResizeHeight else 0
        return Redim.bornesAndroid(info.minWidth, info.minHeight, info.minResizeWidth, info.minResizeHeight, maxL, maxH,
            info.resizeMode, celluleLargeurDp, celluleHauteurDp, Accueil.COLONNES, actuelle)
    }

    /**
     * Dit au widget la place qu'il a (en dp), pour qu'il choisisse sa mise en page : OPTION_APPWIDGET_MIN/MAX_WIDTH/HEIGHT,
     * et OPTION_APPWIDGET_SIZES sur Android 12+ (par la vue quand elle est là, sinon par AppWidgetManager).
     */
    fun annoncerTaille(ctx: Context, id: Int, vue: AppWidgetHostView?, largeurDp: Int, hauteurDp: Int) {
        try {
            val options = Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, largeurDp); putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, largeurDp)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, hauteurDp); putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, hauteurDp)
            }
            if (Build.VERSION.SDK_INT >= 31) {
                val tailles = arrayListOf(android.util.SizeF(largeurDp.toFloat(), hauteurDp.toFloat()))
                options.putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, tailles)
                if (vue != null) { vue.updateAppWidgetSize(options, tailles); return }
            } else if (vue != null) {
                @Suppress("DEPRECATION") vue.updateAppWidgetSize(options, largeurDp, hauteurDp, largeurDp, hauteurDp); return
            }
            // Sans vue (widget hors de l'écran) : les options vont directement au widget.
            val actuelles = gestionnaire(ctx).getAppWidgetOptions(id)
            actuelles.putAll(options)
            gestionnaire(ctx).updateAppWidgetOptions(id, actuelles)
        } catch (_: Exception) {}
    }

    /** Libère les identifiants qui ne sont plus sur l'accueil (retrait, page supprimée, ajout abandonné). */
    fun nettoyer(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val poses = Accueil.disposition(ctx).mapNotNull { p -> if (p.estWidget && estAndroid(p.code)) id(p.code) else null }.toSet()
        val h = hote(ctx)
        try { h.appWidgetIds.filter { it !in poses }.forEach { h.deleteAppWidgetId(it) } } catch (_: Exception) {}
    }

    /** L'hôte crée nos vues : elles savent repérer l'appui long même quand le widget garde le toucher. */
    private class Hote(ctx: Context) : AppWidgetHost(ctx, HOTE) {
        override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?): AppWidgetHostView = VueWidget(context)
    }

    /** Un widget d'une autre appli : ses gestes à lui, sauf l'appui long, qui sert à le déplacer, le redimensionner, le retirer. */
    class VueWidget(ctx: Context) : AppWidgetHostView(ctx) {
        var appuiLong: (() -> Unit)? = null
        private val main = Handler(Looper.getMainLooper())
        private var x0 = 0f
        private var y0 = 0f
        private var pris = false
        private val seuil = ViewConfiguration.get(ctx).scaledTouchSlop
        private val declenche = Runnable { pris = true; appuiLong?.invoke() }

        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pris = false; x0 = e.x; y0 = e.y
                    main.postDelayed(declenche, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> if (kotlin.math.abs(e.x - x0) > seuil || kotlin.math.abs(e.y - y0) > seuil) main.removeCallbacks(declenche)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> main.removeCallbacks(declenche)
            }
            return pris
        }
    }
}
