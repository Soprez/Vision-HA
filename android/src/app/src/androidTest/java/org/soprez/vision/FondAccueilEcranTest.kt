package org.soprez.vision

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup

/**
 * 1.91.x (retour d'essai) : l'arrière-plan de l'accueil, à l'écran.
 * - chaque sorte de fond se pose (défaut, uni, dégradé, photo, animé) ;
 * - une photo très claire sous un thème sombre reçoit un voile, et le texte du thème garde 4,5:1 sur la photo voilée ;
 * - un fond déjà calme n'a pas de voile.
 */
class FondAccueilEcranTest : InstrumentationTestCase() {
    private val ctx get() = instrumentation.targetContext
    private var sorteAvant = FondAccueil.Sorte.DEFAUT
    private var themeAvant = ""

    override fun setUp() {
        super.setUp()
        CodeEssai.poser(instrumentation.targetContext)  // faille du 16-10 : un Décideur a toujours un code
        sorteAvant = FondAccueil.sorte(ctx)
        themeAvant = ctx.getSharedPreferences("local", 0).getString("theme", "") ?: ""
    }

    override fun tearDown() {
        FondAccueil.poser(ctx, sorteAvant)
        ctx.getSharedPreferences("local", 0).edit().putString("theme", themeAvant).apply()
        Local.toucher()
        CodeEssai.rendre(instrumentation.targetContext)
        super.tearDown()
    }

    private fun accueil(): android.app.Activity {
        val a = instrumentation.startActivitySync(Intent(ctx, LanceurActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        instrumentation.waitForIdleSync(); SystemClock.sleep(800)
        return a
    }

    private fun fond(a: android.app.Activity): ViewGroup? { var v: ViewGroup? = null; instrumentation.runOnMainSync { v = a.window.decorView.findViewWithTag(FondAccueil.TAG) }; return v }
    private fun voile(a: android.app.Activity): View? { var v: View? = null; instrumentation.runOnMainSync { v = a.window.decorView.findViewWithTag(FondAccueil.TAG_VOILE) }; return v }

    fun testChaqueSorteSePose() {
        for (s in FondAccueil.Sorte.values()) {
            if (s == FondAccueil.Sorte.PHOTO) FondAccueil.garderPhoto(ctx, FondAccueil.imageClaire(540, 960), 960)
            FondAccueil.poser(ctx, s, if (s == FondAccueil.Sorte.DEGRADE) 1 else 0)
            val a = accueil()
            try {
                val f = fond(a)
                assertNotNull("$s : pas de fond", f)
                assertTrue("$s : fond vide", (f?.childCount ?: 0) >= 1)
            } finally { a.finish(); instrumentation.waitForIdleSync(); SystemClock.sleep(300) }
        }
    }

    fun testPhotoClaireSousUnThemeSombreResteLisible() {
        ctx.getSharedPreferences("local", 0).edit().putString("theme", "Sombre").apply(); Local.toucher()
        val img = FondAccueil.imageClaire(540, 960)
        FondAccueil.garderPhoto(ctx, img, 960)
        FondAccueil.poser(ctx, FondAccueil.Sorte.PHOTO)
        val a = accueil()
        try {
            val v = voile(a)
            assertNotNull("photo très claire : un voile est attendu", v)
            val c = (v!!.background as ColorDrawable).color
            val alpha = (c ushr 24) / 255f
            val th = Local.themeEffectif(ctx, Palette.LANCEUR)  // le thème de l'accueil
            for (px in FondAccueil.echantillon(img)) {
                val vu = FondAccueil.melange(px, th.fond, alpha)
                for (texte in listOf(th.encre, th.encre2))
                    assertTrue("contraste ${FondAccueil.contraste(vu, texte)} < 4,5 (voile $alpha)", FondAccueil.contraste(vu, texte) >= FondAccueil.CONTRASTE_MIN)
            }
        } finally { a.finish(); instrumentation.waitForIdleSync() }
    }

    fun testFondCalmeSansVoile() {
        FondAccueil.poser(ctx, FondAccueil.Sorte.UNI, 0)
        val a = accueil()
        try { assertNull("le fond du thème n'a pas besoin de voile", voile(a)) } finally { a.finish(); instrumentation.waitForIdleSync() }
    }
}
