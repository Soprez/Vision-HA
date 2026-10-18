package org.soprez.vision

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.accessibility.AccessibilityWindowInfo
import java.io.File

/**
 * 1.90.5, PROTOTYPE (retour d'essai) : l'œil Vision posé sur le bouton Accueil de la barre de navigation à 3 boutons.
 * - l'œil est centré sur le vrai bouton Accueil (lu ici, à part, par l'automate d'essai), sans déborder ;
 * - rien en navigation par gestes ; rien quand l'option est coupée.
 * Capture : dans files/recette/oeil-accueil.
 */
class OeilAccueilEcranTest : InstrumentationTestCase() {
    private val ctx get() = instrumentation.targetContext
    // Sans ce drapeau, l'automate d'essai suspend les autres services d'accessibilité (dont celui de Vision).
    private val ua get() = instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    private fun sh(c: String): String = ParcelFileDescriptor.AutoCloseInputStream(ua.executeShellCommand(c)).bufferedReader().readText().trim()

    private var servicesAvant = ""

    override fun setUp() {
        super.setUp()
        CodeEssai.poser(instrumentation.targetContext)  // faille du 16-10 : un Décideur a toujours un code
        servicesAvant = sh("settings get secure enabled_accessibility_services")
        // Le lancement de l'essai tue l'appli : Android classe le service « planté » et ne le relie qu'à un changement
        // du réglage. On le coupe puis on le remet.
        sh("settings put secure enabled_accessibility_services \"\"")
        SystemClock.sleep(800)
        sh("settings put secure enabled_accessibility_services org.soprez.vision/org.soprez.vision.VisionAccessibility")
        sh("settings put secure accessibility_enabled 1")
        val fin = SystemClock.elapsedRealtime() + 10_000
        while (VisionAccessibility.instance?.get() == null && SystemClock.elapsedRealtime() < fin) SystemClock.sleep(200)
        assertNotNull("le service d'accessibilité de Vision ne démarre pas", VisionAccessibility.instance?.get())
    }

    override fun tearDown() {
        instrumentation.runOnMainSync { OeilAccueil.activer(ctx, false) }
        sh("settings put secure enabled_accessibility_services " + (if (servicesAvant == "null") "\"\"" else servicesAvant))
        CodeEssai.rendre(instrumentation.targetContext)
        super.tearDown()
    }

    /**
     * Le bouton Accueil vu par l'automate d'essai, indépendamment de Vision : le nœud « …:id/home » décrit « Home » ou
     * « Accueil », en bas de l'écran (SystemUI ou lanceur du système).
     */
    private fun boutonAccueil(): Rect? {
        ua.serviceInfo = ua.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        SystemClock.sleep(300)
        val wm = ctx.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
        val ecran = wm.maximumWindowMetrics.bounds
        val h = ecran.height(); val l = ecran.width()
        fun chercher(n: android.view.accessibility.AccessibilityNodeInfo?): Rect? {
            n ?: return null
            val id = n.viewIdResourceName.orEmpty(); val d = n.contentDescription?.toString().orEmpty()
            if (id.endsWith(":id/home") && (d == "Home" || d == "Accueil")) {
                val r = Rect().also { n.getBoundsInScreen(it) }
                // En bas (portrait) ou collé à un bord (paysage).
                if (r.top > h * 0.8f || r.left > l * 0.8f || r.right < l * 0.2f) return r
            }
            for (i in 0 until n.childCount) chercher(n.getChild(i))?.let { return it }
            return null
        }
        for (w in ua.windows) chercher(w.root)?.let { return it }
        return null
    }

    private fun accueil() {
        sh("input keyevent KEYCODE_HOME")
        SystemClock.sleep(1500)
    }

    private fun capturer(nom: String) {
        val b: Bitmap = ua.takeScreenshot() ?: return
        val d = File(ctx.getExternalFilesDir(null), "recette/oeil-accueil").apply { mkdirs() }
        File(d, "$nom.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    fun testOeilSurLeBoutonAccueil3Boutons() {
        if (ReglagesTvActivity.estTele(ctx)) return
        if (OeilAccueil.modeNavigation(ctx) != 0) return  // l'essai vise la barre à 3 boutons
        accueil()
        instrumentation.runOnMainSync { OeilAccueil.activer(ctx, true) }
        SystemClock.sleep(1500)
        val bouton = boutonAccueil()
        assertNotNull("pas de bouton Accueil dans la barre (3 boutons attendus)", bouton)
        var place: Rect? = null
        val fin = SystemClock.elapsedRealtime() + 5_000
        while (place == null && SystemClock.elapsedRealtime() < fin) { instrumentation.runOnMainSync { place = OeilAccueil.placeActuelle() }; SystemClock.sleep(200) }
        assertNotNull("l'œil n'est pas posé", place)
        val p = place!!; val b = bouton!!
        assertTrue("œil décentré : œil $p, bouton $b", Math.abs(p.centerX() - b.centerX()) <= 2 && Math.abs(p.centerY() - b.centerY()) <= 2)
        assertTrue("l'œil déborde du bouton : œil $p, bouton $b", b.contains(p))
        SystemClock.sleep(1200)
        capturer("accueil-3-boutons-" + ctx.resources.configuration.screenWidthDp + "dp")
        // Option coupée : plus rien.
        instrumentation.runOnMainSync { OeilAccueil.activer(ctx, false) }
        SystemClock.sleep(500)
        instrumentation.runOnMainSync { place = OeilAccueil.placeActuelle() }
        assertNull("l'œil reste après avoir coupé l'option", place)
    }

    /**
     * Pour la capture de l'auteur (seulement si /sdcard/oeil-capture existe) : l'œil reste posé 30 s sur l'accueil, le temps
     * d'une capture prise de l'extérieur (console de l'émulateur), puis une pression sur Accueil le fait réagir.
     */
    fun testPoseLonguePourCapture() {
        if (sh("ls /sdcard/oeil-capture").contains("No such")) return
        if (ReglagesTvActivity.estTele(ctx) || OeilAccueil.modeNavigation(ctx) != 0) return
        accueil()
        instrumentation.runOnMainSync { OeilAccueil.activer(ctx, true) }
        SystemClock.sleep(15_000)
        sh("input keyevent KEYCODE_HOME")
        SystemClock.sleep(15_000)
    }

    /** Paysage (barre verticale à droite, puis à gauche) : l'œil suit le vrai bouton ; un appui sur Accueil le fait réagir. */
    fun testPaysageEtJoie() {
        if (ReglagesTvActivity.estTele(ctx) || OeilAccueil.modeNavigation(ctx) != 0) return
        val avantRotation = sh("settings get system user_rotation"); val avantAuto = sh("settings get system accelerometer_rotation")
        try {
            sh("settings put system accelerometer_rotation 0")
            instrumentation.runOnMainSync { OeilAccueil.activer(ctx, true) }
            for (rot in listOf(1, 3)) {
                // Une appli qui tourne (l'accueil du système reste en portrait sur un téléphone).
                sh("am start -a android.settings.SETTINGS")
                SystemClock.sleep(1500)
                sh("settings put system user_rotation $rot")
                // Android 14+ : « settings user_rotation » seul ne tourne pas toujours l'écran ; verrouillage explicite,
                // puis on attend la rotation effective (comme outils/recette-visuelle.sh).
                sh("wm user-rotation lock $rot")
                val tourne = SystemClock.elapsedRealtime() + 10_000
                while (SystemClock.elapsedRealtime() < tourne && !sh("dumpsys window displays").let { it.contains("mRotation=$rot") || it.contains("mCurrentRotation=ROTATION_${rot * 90}") }) SystemClock.sleep(500)
                SystemClock.sleep(2000)
                val b = boutonAccueil()
                assertNotNull("rotation $rot : pas de bouton Accueil trouvé par l'essai", b)
                var p: Rect? = null
                val fin = SystemClock.elapsedRealtime() + 5_000
                while (SystemClock.elapsedRealtime() < fin) {
                    instrumentation.runOnMainSync { p = OeilAccueil.placeActuelle() }
                    if (p != null && Math.abs(p!!.centerX() - b!!.centerX()) <= 2 && Math.abs(p!!.centerY() - b.centerY()) <= 2) break
                    SystemClock.sleep(200)
                }
                assertNotNull("rotation $rot : l'œil n'est pas posé", p)
                assertTrue("rotation $rot : œil décentré : œil $p, bouton $b", Math.abs(p!!.centerX() - b!!.centerX()) <= 2 && Math.abs(p!!.centerY() - b.centerY()) <= 2)
                assertTrue("rotation $rot : l'œil déborde : œil $p, bouton $b", b.contains(p!!))
            }
            // Joie : un vrai toucher sur le bouton Accueil (la touche passe au système, l'œil réagit).
            sh("settings put system user_rotation 0"); sh("wm user-rotation lock 0"); SystemClock.sleep(3000)
            accueil()
            val b = boutonAccueil()!!
            val avant = OeilAccueil.joies
            sh("input tap ${b.centerX()} ${b.centerY()}")
            val fin = SystemClock.elapsedRealtime() + 4_000
            while (OeilAccueil.joies == avant && SystemClock.elapsedRealtime() < fin) SystemClock.sleep(100)
            assertTrue("l'appui sur Accueil n'a pas fait réagir l'œil", OeilAccueil.joies > avant)
        } finally {
            sh("settings put system user_rotation " + (if (avantRotation == "null") "0" else avantRotation))
            sh("wm user-rotation lock " + (if (avantRotation == "null") "0" else avantRotation))
            sh("settings put system accelerometer_rotation " + (if (avantAuto == "null") "0" else avantAuto))
            if (avantAuto == "1") sh("wm user-rotation free")
        }
    }

    fun testRienAvecLeClavier() {
        if (ReglagesTvActivity.estTele(ctx) || OeilAccueil.modeNavigation(ctx) != 0) return
        val a = instrumentation.startActivitySync(android.content.Intent(ctx, SetupActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.runOnMainSync { OeilAccueil.activer(ctx, true) }
        SystemClock.sleep(1000)
        // Un champ de saisie prend le focus : le clavier sort.
        var champ: android.widget.EditText? = null
        instrumentation.runOnMainSync {
            champ = android.widget.EditText(a).also { e -> (a.window.decorView as android.view.ViewGroup).addView(e, 400, 120); e.requestFocus()
                (a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(e, 0) }
        }
        SystemClock.sleep(2500)
        val clavier = ua.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        var place: Rect? = null
        instrumentation.runOnMainSync { place = OeilAccueil.placeActuelle() }
        if (clavier) assertNull("l'œil reste avec le clavier", place)
        a.finish()
    }
}
