// SPDX-License-Identifier: MIT
package org.soprez.vision.captures

import android.view.View
import android.view.ViewGroup
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import org.soprez.vision.ReglagesTvActivity
import org.soprez.vision.SetupActivity

/**
 * 1.93.05 (l'auteur, retour 33, TV du salon) : en bas des réglages de la télé, la version ; pour la mise à jour, l'auteur a dû
 * passer par « Modifier », qui ouvrait l'ancien menu de téléphone (SetupActivity : Moi, état, demander du temps,
 * Réglages, « Payez-moi un café »…). Sur une télé reliée :
 * - la page « Paramètres » montre la version, « Installer » (mise à jour prête) ou « Rechercher une mise à jour » ;
 * - AUCUNE ligne ni bouton de la page n'ouvre plus SetupActivity ;
 * - SetupActivity ouvert quand même (ancien raccourci) renvoie aux réglages de la télé.
 * Télé 720p et 1080p, captures de la page.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ReglagesTeleMenuCaptureTest(private val taille: Recette.Taille) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun parametres(): List<Array<Any>> = Recette.TAILLES.filter { it.tele }.map { arrayOf<Any>(it) }
    }

    private fun relier() {
        val ctx = RuntimeEnvironment.getApplication()
        org.soprez.vision.Config(ctx).apply { id = "essai_tele"; secret = "s".repeat(32); urlInterne = "http://127.0.0.1:9"; urlExterne = ""; urlActive = ""; nom = "Télé du salon" }
    }

    private fun cliquables(v: View, l: MutableList<View> = ArrayList()): MutableList<View> {
        if (v.visibility == View.VISIBLE && v.isClickable && v.hasOnClickListeners()) l.add(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) cliquables(v.getChildAt(i), l)
        return l
    }

    @Test fun parametres_sans_ancien_menu() {
        Recette.poser(taille, 1.0f)
        relier()
        val ctx = RuntimeEnvironment.getApplication()
        val a = Robolectric.buildActivity(ReglagesTvActivity::class.java,
            android.content.Intent(ctx, ReglagesTvActivity::class.java).putExtra(ReglagesTvActivity.EXTRA_SECTION, "parametres")).setup().visible().get()
        ShadowLooper.idleMainLooper(300, java.util.concurrent.TimeUnit.MILLISECONDS)
        val d = a.window.decorView
        val defauts = ArrayList(Recette.capturer(d, "reglages-tele-parametres", taille, 1.0f))
        for (t in listOf("version", "maj_rechercher")) if (d.findViewWithTag<View>(t) == null) defauts += "pas de ligne « $t »"
        // Le bas de la page (version, mise à jour), comme à la télécommande : le focus sur « Rechercher une mise à jour ».
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        d.findViewWithTag<View>("maj_rechercher")?.let { v ->
            v.requestFocus()
            val sv = generateSequence(v.parent) { it.parent }.filterIsInstance<android.widget.ScrollView>().firstOrNull()
            var y = 0; var x: View = v
            while (x.parent !== sv && x.parent is View) { y += x.top; x = x.parent as View }
            sv?.scrollTo(0, y + x.top - d.height / 3)
        }
        ShadowLooper.idleMainLooper(600, java.util.concurrent.TimeUnit.MILLISECONDS)
        defauts.addAll(Recette.capturer(d, "reglages-tele-parametres-bas", taille, 1.0f))
        // Aucune commande de la page ne mène à l'ancien menu de téléphone.
        val app = shadowOf(ctx)
        val page = d.findViewWithTag<View>("version")?.parent as? ViewGroup ?: d as ViewGroup
        for (c in cliquables(page)) {
            while (app.nextStartedActivity != null) { }
            c.performClick(); ShadowLooper.idleMainLooper()
            val lance = app.nextStartedActivity
            if (lance?.component?.className == SetupActivity::class.java.name)
                defauts += "« ${(c.tag as? String) ?: c.javaClass.simpleName} » ouvre encore l'ancien menu (SetupActivity)"
            org.robolectric.shadows.ShadowDialog.getLatestDialog()?.dismiss()
        }
        assertEquals(defauts.joinToString("\n"), emptyList<String>(), defauts)
    }

    @Test fun ancien_menu_renvoie_aux_reglages_de_la_tele() {
        Recette.poser(taille, 1.0f)
        relier()
        val ctx = RuntimeEnvironment.getApplication()
        val c = Robolectric.buildActivity(SetupActivity::class.java, android.content.Intent(ctx, SetupActivity::class.java)).setup()
        ShadowLooper.idleMainLooper()
        val lance = shadowOf(ctx).nextStartedActivity
        assertEquals("SetupActivity sur une télé reliée : renvoi aux réglages de la télé", ReglagesTvActivity::class.java.name, lance?.component?.className)
        assertEquals(true, c.get().isFinishing)
    }
}
