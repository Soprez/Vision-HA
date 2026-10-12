// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver

/**
 * Lot Accueil (point 10) : un message Vision plein écran (MessageActivity) entre en douceur.
 * - Animations actives (échelle 1) : la carte est à alpha 0 au premier rendu, puis à 1 à la fin de l'entrée.
 * - Échelle d'animation à 0 (« supprimer les animations ») : la carte est à 1 dès le premier rendu.
 * - La fermeture attend la fin de la sortie (l'activité n'est pas finie tout de suite).
 * - Retour de l'intégrateur : la fenêtre décor a la taille de l'écran dès la première image visible et la carte ne
 *   change plus de place (getLocationOnScreen, glissement et agrandissement retirés) jusqu'à la fin de l'entrée.
 *
 * Lancement (émulateur seulement, par l'intégrateur) :
 *   adb shell am instrument -w -e class org.soprez.vision.MessageAnimTest org.soprez.vision.test/android.test.InstrumentationTestRunner
 */
class MessageAnimTest : InstrumentationTestCase() {
    private val ctx get() = instrumentation.targetContext
    private var echelleAvant = "1"

    private fun shell(cmd: String): String {
        val pfd = instrumentation.uiAutomation.executeShellCommand(cmd)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { String(it.readBytes()) }.trim()
    }

    override fun setUp() {
        super.setUp()
        echelleAvant = shell("settings get global animator_duration_scale").takeIf { it.toFloatOrNull() != null } ?: "1"
    }

    override fun tearDown() {
        shell("settings put global animator_duration_scale $echelleAvant")
        super.tearDown()
    }

    /** Une image dessinée : taille de la fenêtre décor, opacités, position de la carte (à l'écran et sans transformation). */
    data class Image(val decorL: Int, val decorH: Int, val fondAlpha: Float, val carteAlpha: Float,
                     val ecranX: Int, val ecranY: Int, val baseX: Float, val baseY: Float)

    private val images = ArrayList<Image>()
    private var ecouteur: ViewTreeObserver.OnDrawListener? = null

    /** Ouvre un message et rend (alpha de la carte au premier rendu, l'activité) ; chaque image est notée dans [images]. */
    private fun ouvrir(): Pair<Float, Activity> {
        var premier = -1f
        images.clear()
        val app = ctx.applicationContext as Application
        val suivi = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) {
                if (a !is MessageActivity) return
                val carte = carte(a) ?: return
                val fond = carte.parent as View
                val decor = a.window.decorView
                premier = carte.alpha  // avant toute image : l'état préparé
                decor.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        decor.viewTreeObserver.removeOnPreDrawListener(this)
                        premier = maxOf(premier, carte.alpha)  // la première image dessinée
                        return true
                    }
                })
                val e = ViewTreeObserver.OnDrawListener {
                    val loc = IntArray(2); carte.getLocationOnScreen(loc)
                    // Position sans glissement ni agrandissement (échelle autour du pivot) : la place de mise en page.
                    val bx = loc[0] - carte.translationX - carte.pivotX * (1f - carte.scaleX)
                    val by = loc[1] - carte.translationY - carte.pivotY * (1f - carte.scaleY)
                    images += Image(decor.width, decor.height, fond.alpha, carte.alpha, loc[0], loc[1], bx, by)
                }
                ecouteur = e
                decor.viewTreeObserver.addOnDrawListener(e)
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        }
        app.registerActivityLifecycleCallbacks(suivi)
        try {
            val a = instrumentation.startActivitySync(MessageActivity.intention(ctx, "Vision", "À table dans 5 minutes", false, null)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.waitForIdleSync()
            return premier to a
        } finally {
            app.unregisterActivityLifecycleCallbacks(suivi)
        }
    }

    /** Arrête de noter les images et rend celles où le message se voit (fond ou carte au-dessus de 0). */
    private fun imagesVisibles(a: Activity): List<Image> {
        instrumentation.runOnMainSync { ecouteur?.let { a.window.decorView.viewTreeObserver.removeOnDrawListener(it) }; ecouteur = null }
        return images.filter { it.fondAlpha > 0f || it.carteAlpha > 0f }
    }

    /** Fenêtre plein écran dès la première image visible ; la carte ne change plus de place jusqu'à la fin. */
    private fun verifierSansSaut(a: Activity) {
        val toutes = ArrayList(images)
        val vues = imagesVisibles(a)
        assertTrue("Aucune image visible notée", vues.isNotEmpty())
        var attendue = android.graphics.Point()
        instrumentation.runOnMainSync { attendue = (a as MessageActivity).tailleAttendue() }
        val p = vues.first()
        assertEquals("Fenêtre décor pas à la largeur de l'écran dès la première image visible (toutes : ${toutes.map { "${it.decorL}x${it.decorH}" }})", attendue.x, p.decorL)
        assertEquals("Fenêtre décor pas à la hauteur de l'écran dès la première image visible (toutes : ${toutes.map { "${it.decorL}x${it.decorH}" }})", attendue.y, p.decorH)
        for ((k, im) in vues.withIndex()) {
            assertEquals("Image $k : la fenêtre décor a changé de taille", p.decorL to p.decorH, im.decorL to im.decorH)
            assertEquals("Image $k : la carte a sauté en X (${p.baseX} → ${im.baseX})", p.baseX, im.baseX, 1.5f)
            assertEquals("Image $k : la carte a sauté en Y (${p.baseY} → ${im.baseY})", p.baseY, im.baseY, 1.5f)
        }
        // À la fin de l'animation, getLocationOnScreen (sans transformation restante) tombe sur la place de la 1re image.
        val loc = IntArray(2); instrumentation.runOnMainSync { carte(a)!!.getLocationOnScreen(loc) }
        assertEquals("Position finale X", p.baseX, loc[0].toFloat(), 1.5f)
        assertEquals("Position finale Y", p.baseY, loc[1].toFloat(), 1.5f)
    }

    private fun carte(a: Activity): View? =
        ((a.findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0)) as? ViewGroup)?.getChildAt(0)

    private fun alpha(a: Activity): Float { var v = -1f; instrumentation.runOnMainSync { v = carte(a)?.alpha ?: -1f }; return v }

    fun testEntreeDouce() {
        shell("settings put global animator_duration_scale 1")
        SystemClock.sleep(500)
        val (premier, a) = ouvrir()
        assertEquals("La carte doit partir invisible (alpha 0) au premier rendu", 0f, premier, 0.01f)
        SystemClock.sleep(1000)
        assertEquals("La carte doit finir à alpha 1", 1f, alpha(a), 0.001f)
        verifierSansSaut(a)
        // Fermeture : la sortie se joue d'abord, l'activité ne finit qu'après.
        instrumentation.runOnMainSync { a.finish() }
        assertFalse("L'activité ne doit pas finir avant la fin de la sortie", a.isFinishing)
        SystemClock.sleep(800)
        assertTrue("L'activité doit être finie après la sortie", a.isFinishing || a.isDestroyed)
    }

    fun testAnimationsSupprimees() {
        shell("settings put global animator_duration_scale 0")
        SystemClock.sleep(800)
        val (premier, a) = ouvrir()
        assertEquals("Échelle 0 : la carte est visible dès le premier rendu", 1f, premier, 0.001f)
        assertEquals(1f, alpha(a), 0.001f)
        verifierSansSaut(a)
        instrumentation.runOnMainSync { a.finish() }
        assertTrue("Échelle 0 : fermeture immédiate", a.isFinishing)
        instrumentation.waitForIdleSync()
    }
}
