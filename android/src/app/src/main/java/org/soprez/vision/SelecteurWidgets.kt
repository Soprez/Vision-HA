// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.app.Dialog
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/**
 * Le sélecteur des widgets des autres applis, dans le thème Vision : groupés par appli (nom et icône), recherche,
 * aperçu (le previewLayout de l'appli sur Android 12+, sinon son image d'aperçu, sinon l'icône de l'appli), nom du
 * widget et taille en cases. Les noms passent à la ligne, jamais coupés.
 */
object SelecteurWidgets {
    fun ouvrir(act: Activity, theme: Theme, celluleLDp: Float, celluleHDp: Float, choisi: (AppWidgetProviderInfo) -> Unit) {
        val liste = WidgetsAndroid.fournisseurs(act)
        val dlg = Dialog(act, android.R.style.Theme_Material_NoActionBar)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        fun px(v: Float) = Ui.dp(act, v)
        fun texte(s: String, taille: Float, couleur: Int, police: android.graphics.Typeface = Polices.texte(act)): TextView =
            Ui.sansCesure(TextView(act).apply { text = s; textSize = taille; setTextColor(couleur); typeface = police })
        fun fond(couleur: Int, rayon: Float, bord: Int = theme.carteBord, ep: Float = 1f) = GradientDrawable().apply { cornerRadius = px(rayon).toFloat(); setColor(couleur); setStroke(px(ep), bord) }

        val racine = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(theme.fond); setPadding(px(16f), px(28f), px(16f), px(8f)) }
        val tete = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL }
        tete.addView(texte(act.getString(R.string.selecteurwidgets_titre), 22f, theme.encre, Polices.outfit(act)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tete.addView(Ui.boutonSecondaire(act, act.getString(R.string.commun_lot2_fermer)) { dlg.dismiss() })
        racine.addView(tete)
        val champ = Ui.champ(act, act.getString(R.string.selecteurwidgets_rechercher), "").apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setTextColor(theme.encre); setHintTextColor(theme.encre3); background = fond(theme.carte, 14f)
        }
        racine.addView(champ)
        val defile = ScrollView(act).apply { isVerticalScrollBarEnabled = false; clipToPadding = false }
        val colonne = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(0, px(8f), 0, px(24f)) }
        defile.addView(colonne)
        racine.addView(defile, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val largeurDispo = act.resources.displayMetrics.widthPixels - px(64f)
        val parAppli = liste.groupBy { it.provider.packageName }
        val apercus = HashMap<AppWidgetProviderInfo, View>()

        fun remplir(q: String) {
            colonne.removeAllViews()
            val r = q.trim().lowercase(Locale.FRANCE)
            var vus = 0
            for ((pkg, infos) in parAppli) {
                val appli = WidgetsAndroid.appli(act, infos.first())
                val gardes = infos.filter { r.isEmpty() || appli.lowercase(Locale.FRANCE).contains(r) || WidgetsAndroid.libelle(act, it).lowercase(Locale.FRANCE).contains(r) }
                if (gardes.isEmpty()) continue
                vus += gardes.size
                val entete = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(px(4f), px(16f), 0, px(8f)) }
                entete.addView(ImageView(act).apply { setImageDrawable(Accueil.icone(act, pkg)) }, LinearLayout.LayoutParams(px(28f), px(28f)).apply { rightMargin = px(10f) })
                entete.addView(texte(appli, 16f, theme.encre, Polices.gras(act)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                entete.addView(texte(act.resources.getQuantityString(R.plurals.selecteurwidgets_nombre, gardes.size, gardes.size), 12f, theme.encre3))
                colonne.addView(entete)
                for (info in gardes) {
                    val (w, h) = WidgetsAndroid.cellulesDepart(info, celluleLDp, celluleHDp)
                    val carte = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; background = fond(theme.carte, 20f); setPadding(px(14f), px(12f), px(14f), px(12f)); isClickable = true; isFocusable = true }
                    carte.setOnFocusChangeListener { v, a -> v.background = if (a) fond(theme.carte, 20f, theme.or, 2f) else fond(theme.carte, 20f) }
                    val boite = FrameLayout(act).apply { background = fond(Palette.melanger(theme.carte, theme.encre, 0.05f), 14f, 0) ; setPadding(px(8f), px(8f), px(8f), px(8f)) }
                    val ap = apercus.getOrPut(info) { apercu(act, info, w, h, celluleLDp, celluleHDp, largeurDispo - px(44f)) }
                    (ap.parent as? ViewGroup)?.removeView(ap)
                    boite.addView(ap, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                    carte.addView(boite, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    val bas = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(8f), 0, 0) }
                    val noms = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
                    noms.addView(texte(WidgetsAndroid.libelle(act, info), 15f, theme.encre, Polices.demiGras(act)))
                    noms.addView(texte(appli, 12f, theme.encre2))
                    bas.addView(noms, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    bas.addView(texte("$w × $h", 13f, theme.or, Polices.gras(act)).apply { background = fond(Palette.melanger(theme.carte, theme.or, 0.14f), 999f, 0, 0f); setPadding(px(10f), px(4f), px(10f), px(4f)) })
                    carte.addView(bas)
                    carte.contentDescription = act.getString(R.string.selecteurwidgets_description, WidgetsAndroid.libelle(act, info), appli, w, h)
                    carte.setOnClickListener { dlg.dismiss(); choisi(info) }
                    colonne.addView(carte, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = px(10f) })
                }
            }
            if (vus == 0) colonne.addView(texte(if (liste.isEmpty()) act.getString(R.string.selecteurwidgets_aucune_appli) else act.getString(R.string.selecteurwidgets_aucun_resultat, q.trim()), 14f, theme.encre2).apply { setPadding(px(4f), px(16f), px(4f), 0) })
        }
        remplir("")
        champ.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { remplir(s?.toString().orEmpty()) }
        })
        dlg.setContentView(racine)
        dlg.window?.let { w ->
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            w.statusBarColor = theme.fond; w.navigationBarColor = theme.fond
            w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dlg.show()
    }

    /**
     * L'aperçu d'un widget, du plus fidèle au plus simple : sur Android 15+, l'aperçu que l'appli a généré (le
     * widget tel qu'il est, avec ses données) ; sur Android 12+, son previewLayout ; sinon son image d'aperçu ;
     * sinon l'icône de l'appli. Les deux premiers sont rendus dans une vue d'hôte, comme le fait le lanceur
     * d'Android, puis réduits pour tenir dans la largeur.
     */
    private fun apercu(act: Activity, info: AppWidgetProviderInfo, w: Int, h: Int, celluleLDp: Float, celluleHDp: Float, largeurMax: Int): View {
        val d = act.resources.displayMetrics.density
        val largeur = (w * celluleLDp * d).toInt(); val hauteur = (h * celluleHDp * d).toInt()
        val vues = apercuGenere(act, info)
            ?: if (Build.VERSION.SDK_INT >= 31 && info.previewLayout != 0) RemoteViews(info.provider.packageName, info.previewLayout) else null
        if (vues != null) {
            try {
                val hote = object : AppWidgetHostView(act) {
                    // Un aperçu ne se touche pas : le toucher va à la carte.
                    override fun onInterceptTouchEvent(e: MotionEvent): Boolean = true
                }
                hote.setAppWidget(-1, info)
                hote.updateAppWidget(vues)
                hote.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                return Reduit(act, hote, largeur, hauteur, largeurMax, (180 * d).toInt())
            } catch (_: Exception) {}
        }
        val image: Drawable? = try { info.loadPreviewImage(act, act.resources.displayMetrics.densityDpi) } catch (_: Exception) { null }
        if (image != null) {
            val iv = ImageView(act).apply { setImageDrawable(image); scaleType = ImageView.ScaleType.FIT_CENTER; adjustViewBounds = true; maxHeight = (160 * d).toInt(); maxWidth = largeurMax }
            return iv
        }
        val icone = try { info.loadIcon(act, act.resources.displayMetrics.densityDpi) } catch (_: Exception) { null } ?: Accueil.icone(act, info.provider.packageName)
        return ImageView(act).apply { setImageDrawable(icone); layoutParams = ViewGroup.LayoutParams((56 * d).toInt(), (56 * d).toInt()); minimumWidth = (56 * d).toInt(); minimumHeight = (56 * d).toInt(); scaleType = ImageView.ScaleType.FIT_CENTER }
    }

    /**
     * Android 15 : l'aperçu généré par l'appli pour l'écran d'accueil (AppWidgetManager.getWidgetPreview), ou null.
     * Appelé par réflexion : l'appli se compile avec le SDK 34, où ces appels n'existent pas encore.
     */
    private fun apercuGenere(act: Activity, info: AppWidgetProviderInfo): RemoteViews? {
        if (Build.VERSION.SDK_INT < 35) return null
        return try {
            val accueil = AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN
            val categories = AppWidgetProviderInfo::class.java.getField("generatedPreviewCategories").getInt(info)
            if (categories and accueil == 0) return null
            val methode = android.appwidget.AppWidgetManager::class.java.getMethod(
                "getWidgetPreview", android.content.ComponentName::class.java, android.os.UserHandle::class.java,
                Int::class.javaPrimitiveType)
            methode.invoke(android.appwidget.AppWidgetManager.getInstance(act), info.provider, info.profile, accueil) as? RemoteViews
        } catch (_: Exception) { null }
    }

    /** Montre un enfant mesuré à sa vraie taille (celle du widget posé), réduit d'un facteur pour tenir dans la place. */
    private class Reduit(ctx: android.content.Context, private val enfant: View, private val vraiL: Int, private val vraiH: Int, maxL: Int, maxH: Int) : ViewGroup(ctx) {
        private val f = minOf(1f, maxL.toFloat() / vraiL.coerceAtLeast(1), maxH.toFloat() / vraiH.coerceAtLeast(1))
        init { addView(enfant); enfant.pivotX = 0f; enfant.pivotY = 0f; enfant.scaleX = f; enfant.scaleY = f }
        override fun onMeasure(a: Int, b: Int) {
            enfant.measure(MeasureSpec.makeMeasureSpec(vraiL, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(vraiH, MeasureSpec.EXACTLY))
            setMeasuredDimension((vraiL * f).toInt(), (vraiH * f).toInt())
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) { enfant.layout(0, 0, vraiL, vraiH) }
        override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean = true
    }
}
