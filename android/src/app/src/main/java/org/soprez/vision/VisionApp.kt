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
        Etat.charger(this)
        Vocabulaire.charger(this)
        // Installations et désinstallations : accueil, tiroir et Home Assistant suivent aussitôt.
        Paquets.ecouter(this)
        if (Config(this).inscrit) AgentService.demarrer(this)
        registerActivityLifecycleCallbacks(Bords)
    }

    /**
     * Android 15+ dessine chaque écran jusque sous les barres du système (heure, gestes) et ne laisse
     * plus le choix. Les écrans de réglages retrouvent leurs marges ; l'accueil, le verrouillage et la
     * veille gardent le plein écran et ne s'écartent que de la barre de gestes, en bas.
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
                    if (plein) v.setPadding(0, 0, 0, barres.bottom)
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
