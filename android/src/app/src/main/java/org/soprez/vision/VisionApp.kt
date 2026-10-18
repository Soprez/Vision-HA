// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.app.Application
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets

/** Charge le dernier état connu dès le démarrage, pour agir même hors ligne. */
class VisionApp : Application() {
    /** Les textes traduits (Tr) : posés avant tout fournisseur de contenu, service ou récepteur. */
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(base)
        Tr.installer(base)
    }

    override fun onCreate() {
        super.onCreate()
        Tr.installer(this)
        // 1.90.2 : les pochettes d'une Party précédente ne restent pas sur le disque.
        CachePochettes.vider(this)
        Etat.charger(this)
        Vocabulaire.charger(this)
        // Installations et désinstallations : accueil, tiroir et Home Assistant suivent aussitôt.
        Paquets.ecouter(this)
        if (Config(this).inscrit) AgentService.demarrer(this)
        registerActivityLifecycleCallbacks(Bords)
        registerActivityLifecycleCallbacks(ThemeUi)
    }

    /**
     * 17-10 (l'auteur, point 19 : « avec le thème sombre et or, les boutons de choix de widget sont en BLANC ») : les couleurs
     * des briques d'interface (Ui.boutonSecondaire, cartes, lignes…) sont des valeurs globales, que seul Ui.charger
     * posait — et l'accueil, la veille, le lecteur ne l'appelaient pas. Leurs fenêtres (choix des widgets d'une zone…)
     * gardaient les couleurs du dernier écran ouvert, ou celles de Beige : des boutons clairs sur un thème sombre.
     * Chaque écran pose maintenant le thème courant à sa création (avant de bâtir ses vues : Activity.onCreate appelle
     * ce rappel dès son début) et à chaque retour au premier plan (le thème a pu changer entre-temps).
     */
    private object ThemeUi : ActivityLifecycleCallbacks {
        private fun poser(a: Activity) { try {
            val th = Local.themeEffectif(a, Palette.APPLI)
            Ui.appliquer(th)
            // Le fond de la fenêtre (derrière les vues : il se voit aux ouvertures, aux bords, entre deux écrans) était le
            // gris de Material (#303030) quel que soit le thème. Pas pour une fenêtre translucide (elle laisse voir dessous).
            val translucide = a.theme.obtainStyledAttributes(intArrayOf(android.R.attr.windowIsTranslucent)).let { t ->
                try { t.getBoolean(0, false) } finally { t.recycle() } }
            if (!translucide) a.window?.setBackgroundDrawable(ColorDrawable(th.fond or 0xFF000000.toInt()))
        } catch (_: Exception) {} }
        override fun onActivityCreated(a: Activity, b: Bundle?) { poser(a) }
        override fun onActivityResumed(a: Activity) { poser(a) }
        override fun onActivityStarted(a: Activity) {}
        override fun onActivityPaused(a: Activity) {}
        override fun onActivityStopped(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
        override fun onActivityDestroyed(a: Activity) {}
    }

    /**
     * Android 15+ dessine chaque écran jusque sous les barres du système (heure, gestes) et ne laisse
     * plus le choix. Les écrans de réglages retrouvent leurs marges ; le verrouillage et la veille gardent le
     * plein écran et ne s'écartent que de la barre de gestes, en bas ; l'accueil s'écarte aussi des côtés (1.90.1).
     */
    private object Bords : ActivityLifecycleCallbacks {
        private val pleinEcran = setOf("LanceurActivity", "VeilleActivity", "RetourActivity")

        override fun onActivityStarted(a: Activity) {
            if (Build.VERSION.SDK_INT < 35) return
            try {
                val contenu = a.findViewById<View>(android.R.id.content) ?: return
                val plein = a.javaClass.simpleName in pleinEcran
                if (!plein) a.window.setBackgroundDrawable(ColorDrawable(Ui.FOND))
                if (contenu.getTag(R.id.bords_poses) == true) return
                contenu.setTag(R.id.bords_poses, true)
                contenu.setOnApplyWindowInsetsListener { v, insets ->
                    val barres = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    val clavier = insets.getInsets(WindowInsets.Type.ime())
                    // 1.90.1 (l'auteur, Pixel en paysage) : l'accueil s'écarte aussi de la barre de navigation à gauche ou à droite
                    // (et de l'encoche) ; la veille et le retour restent bord à bord sur les côtés.
                    if (plein && a is LanceurActivity) v.setPadding(barres.left, 0, barres.right, barres.bottom)
                    else if (plein) v.setPadding(0, 0, 0, barres.bottom)
                    else v.setPadding(barres.left, barres.top, barres.right, maxOf(barres.bottom, clavier.bottom))
                    insets
                }
                contenu.requestApplyInsets()
            } catch (_: Exception) {}
        }

        override fun onActivityCreated(a: Activity, b: Bundle?) {}
        override fun onActivityResumed(a: Activity) {}
        override fun onActivityPaused(a: Activity) {}
        override fun onActivityStopped(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
        override fun onActivityDestroyed(a: Activity) {}
    }
}
