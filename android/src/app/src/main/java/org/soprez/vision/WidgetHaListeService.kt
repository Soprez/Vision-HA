// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService

/**
 * 1.90.1 (l'auteur : « j'avais pas toutes les infos, ça me mettait +24 ») : les lignes du détail d'un widget d'écran
 * d'accueil, dans une vraie liste du système qui défile ; toutes les lignes reçues, la hauteur suit la taille du widget.
 */
class WidgetHaListeService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        Fabrique(applicationContext, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0))

    private class Fabrique(private val ctx: Context, private val id: Int) : RemoteViewsFactory {
        private var lignes: List<String> = emptyList()
        private var couleur = 0

        override fun onCreate() {}
        override fun onDestroy() {}
        override fun onDataSetChanged() {
            lignes = WidgetsSysteme.lignesDetail(ctx, id)
            couleur = Local.themeEffectif(ctx, Palette.APPLI).encre2
        }
        override fun getCount(): Int = lignes.size
        override fun getViewAt(position: Int): RemoteViews = RemoteViews(ctx.packageName, R.layout.widget_ha_ligne).apply {
            setTextViewText(R.id.widget_ligne, lignes.getOrElse(position) { "" })
            setTextColor(R.id.widget_ligne, couleur)
            // Un appui sur une ligne fait comme un appui sur le widget (« Au toucher »).
            setOnClickFillInIntent(R.id.widget_ligne, Intent())
        }
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount(): Int = 1
        override fun getItemId(position: Int): Long = position.toLong()
        override fun hasStableIds(): Boolean = false
    }
}
