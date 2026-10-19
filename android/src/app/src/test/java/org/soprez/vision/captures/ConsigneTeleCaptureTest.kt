// SPDX-License-Identifier: MIT
package org.soprez.vision.captures

import android.app.Activity
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import org.soprez.vision.AppuisReglage
import org.soprez.vision.DispositionAccueil
import org.soprez.vision.LanceurActivity
import org.soprez.vision.WidgetsHa
import org.soprez.vision.WidgetsHaStock
import org.soprez.vision.ZoneHaut
import java.util.concurrent.TimeUnit

/**
 * 17-10 (l'auteur, retour 20 : « régler la consigne depuis l'accueil de la télé, comme dans l'appli mobile ») : le widget
 * « Consigne » posé dans une zone de l'accueil de la télé, réglé à la télécommande, comme sur le téléphone :
 * - le focus arrive sur − ; ► passe à + ;
 * - OK sur − (trois fois, vite) : la valeur affichée suit tout de suite (19,5… 19,0), le focus reste sur −, RIEN ne
 *   part avant 600 ms, puis UN seul envoi avec la valeur finale (AppuisReglage) ;
 * - en 1 × 1, 2 × 1, 2 × 2 et 4 × 2, télé 1080p et 720p. Home Assistant simulé ([FauxHa]).
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ConsigneTeleCaptureTest(private val taille: Recette.Taille, private val w: Int, private val h: Int) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}x{2}")
        fun parametres(): List<Array<Any>> = Recette.TAILLES.filter { it.tele }.flatMap { t ->
            listOf(1 to 1, 2 to 1, 2 to 2, 4 to 2).map { (w, h) -> arrayOf<Any>(t, w, h) } }
    }

    private fun focus(a: Activity): View? = a.window.decorView.findFocus()

    /** Une touche de la télécommande ; les flèches déplacent le focus comme le fait ViewRootImpl (absent de Robolectric). */
    private fun touche(a: Activity, code: Int, attente: Long = 50) {
        val prise = a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        val sens = when (code) { KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT; KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT; else -> 0 }
        if (!prise && sens != 0) focus(a)?.focusSearch(sens)?.requestFocus(sens)
        ShadowLooper.idleMainLooper(attente, TimeUnit.MILLISECONDS)
    }

    private fun valeur(a: Activity, code: String): String =
        a.window.decorView.findViewWithTag<View>(code)?.findViewWithTag<TextView>("widget_valeur")?.text?.toString().orEmpty()

    @Test fun consigne_a_la_telecommande() {
        FauxHa().use { ha ->
            AppuisReglage.vider()
            org.soprez.vision.FluxWidgets.etats.clear()  // l'état d'un cas précédent ne doit pas servir de départ
            Recette.poser(taille, 1.0f)
            val ctx = RuntimeEnvironment.getApplication()
            org.soprez.vision.Config(ctx).apply { id = "essai_tele"; secret = "s".repeat(32); urlInterne = ha.adresse; urlExterne = ""; urlActive = "" }
            ha.reponses["/api/pc_parental/widgets/action"] = { JSONObject().put("ok", true) }
            // Le catalogue de recette, consigne sans confirmation (le chemin avec confirmation : une fenêtre de plus).
            val cat = javaClass.getResource("/captures/catalogue_widgets.json")!!.readText()
                .replace("\"role\": \"reglage\", \"confirmer\": true,", "\"role\": \"reglage\", \"confirmer\": false,")
            WidgetsHaStock.garderCatalogue(ctx, cat)
            val eid = "input_number.consigne"
            val code = ZoneHaut.PREFIXE_HA + eid
            WidgetsHaStock.ecrire(ctx, WidgetsHa.Reglage("zone-$eid", listOf(eid), WidgetsHa.REGLAGE, w, h, libelle = "Consigne chauffage"))
            DispositionAccueil.poserDisposTele(ctx, DispositionAccueil.TELE_VERTICAL)
            DispositionAccueil.poserWidgetsZone(ctx, DispositionAccueil.ZONE_D, listOf(code))
            DispositionAccueil.poserZoneActivee(ctx, DispositionAccueil.ZONE_D, true)
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
            val a = Robolectric.buildActivity(LanceurActivity::class.java).setup().visible().get()
            ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS)
            val defauts = ArrayList<String>()
            val cadre = a.window.decorView.findViewWithTag<View>(code) ?: error("le widget n'est pas sur l'accueil")
            cadre.requestFocus()
            ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS)
            if (focus(a)?.tag != "widget_moins") defauts += "le focus ne va pas sur − (${focus(a)?.tag})"
            // − et + entiers dans la carte du widget (en 1 × 1 et 2 × 1, ils étaient coupés sous le bord de la zone).
            val carte = generateSequence(focus(a)?.parent) { it.parent }.filterIsInstance<org.soprez.vision.VueWidgetHa>().firstOrNull()
            for (t in listOf("widget_moins", "widget_plus")) {
                val b = cadre.findViewWithTag<View>(t) ?: run { defauts += "$t absent"; null } ?: continue
                if (carte == null) continue
                val pb = IntArray(2).also { b.getLocationOnScreen(it) }; val pc = IntArray(2).also { carte.getLocationOnScreen(it) }
                if (pb[1] < pc[1] - 1 || pb[1] + b.height > pc[1] + carte.height + 1 || pb[0] < pc[0] - 1 || pb[0] + b.width > pc[0] + carte.width + 1)
                    defauts += "$t coupé par le bord de la carte (${pb[1] + b.height} > ${pc[1] + carte.height})"
            }
            val avant = valeur(a, code)
            // Trois appuis rapides sur − (OK de la télécommande).
            val attendus = listOf("20", "19,5", "19")
            for ((k, v) in attendus.withIndex()) {
                touche(a, KeyEvent.KEYCODE_DPAD_CENTER, 100)
                if (!valeur(a, code).startsWith(v)) defauts += "appui ${k + 1} : la valeur affichée est « ${valeur(a, code)} », attendu $v (avant : $avant)"
                if (focus(a)?.tag != "widget_moins") defauts += "appui ${k + 1} : le focus quitte − (${focus(a)?.tag})"
            }
            if (ha.recus.any { it.first.endsWith("/widgets/action") }) defauts += "un envoi est parti avant 600 ms"
            // La ligne de HA sous la valeur : entière dans la carte, ou absente mais dite (jamais coupée sous le bord).
            val vue = cadre.findViewWithTag<View>("widget_valeur")?.let { generateSequence(it.parent) { p -> p.parent }.filterIsInstance<org.soprez.vision.VueWidgetHa>().firstOrNull() }
            val ligne = cadre.findViewWithTag<TextView>("widget_texte")
            if (vue != null && ligne != null && ligne.height > 0) {
                val pl = IntArray(2).also { ligne.getLocationOnScreen(it) }; val pv = IntArray(2).also { vue.getLocationOnScreen(it) }
                val texteH = ligne.layout?.height ?: 0
                if (pl[1] + ligne.height > pv[1] + vue.height + 2 || texteH > ligne.height + 2) defauts += "la ligne sous la valeur est coupée (bas ${pl[1] + ligne.height} > ${pv[1] + vue.height})"
            } else if (vue?.contentDescription?.toString()?.contains("Réduit") != true) defauts += "la ligne sous la valeur n'est ni montrée ni dite"
            Recette.capturer(a.window.decorView, "consigne-tele-${w}x$h", taille, 1.0f)
            ShadowLooper.idleMainLooper(AppuisReglage.REGROUPER_MS + 50, TimeUnit.MILLISECONDS)
            val fin = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < fin && ha.recus.none { it.first.endsWith("/widgets/action") }) { ShadowLooper.idleMainLooper(); Thread.sleep(30) }
            Thread.sleep(300); ShadowLooper.idleMainLooper()
            val envois = ha.recus.filter { it.first.endsWith("/widgets/action") }
            if (envois.size != 1) defauts += "${envois.size} envois au lieu d'un"
            envois.firstOrNull()?.let { val d = it.second.optJSONObject("data")?.optDouble("value"); if (d != 19.0) defauts += "valeur envoyée $d au lieu de 19,0" }
            // ► passe à +.
            touche(a, KeyEvent.KEYCODE_DPAD_RIGHT)
            if (focus(a)?.tag != "widget_plus") defauts += "► ne passe pas à + (${focus(a)?.tag})"
            // Rien ne reste pour les essais suivants (valeur demandée, état du flux).
            AppuisReglage.vider(); org.soprez.vision.FluxWidgets.etats.clear()
            assertEquals("${taille.nom} ${w}×$h :\n" + defauts.joinToString("\n"), emptyList<String>(), defauts)
        }
    }
}
