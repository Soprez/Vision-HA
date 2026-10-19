// SPDX-License-Identifier: MIT
package org.soprez.vision.captures

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.Rect
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import org.soprez.vision.Accueil
import org.soprez.vision.DispositionAccueil
import org.soprez.vision.LanceurActivity
import org.soprez.vision.WidgetsHa
import org.soprez.vision.WidgetsHaStock
import org.soprez.vision.ZoneAjustee
import org.soprez.vision.ZoneHaut
import org.soprez.vision.ZoneSure
import java.util.concurrent.TimeUnit

/**
 * 1.93.06.1 (l'auteur, accueil de la télé en disposition verticale), en 720p et 1080p, « Ajuster l'écran » à 0 % et à 5 % :
 * - la roue des réglages n'est plus en bas de la colonne ; « Paramètres » est la DERNIÈRE tuile de la liste, sans menu
 *   (pas de « Retirer »), et le reste après le déplacement d'une appli ;
 * - la liste des applis a un fondu en haut et en bas (bord estompé d'Android), et l'appli prise n'est jamais dans le fondu ;
 * - la liste prend la hauteur libérée : elle descend jusqu'au bas de la zone ;
 * - la zone de gauche de la télé du salon (horloge, chauffage, consigne en 2 × 2) tient dans l'écran AU REPOS : − et +
 *   entiers et dans la zone, sans défilement.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class AccueilVerticalCaptureTest(private val taille: Recette.Taille, private val zone: Int) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-zone{1}")
        fun parametres(): List<Array<Any>> = Recette.TAILLES.filter { it.tele }.flatMap { t -> listOf(0, 5).map { z -> arrayOf<Any>(t, z) } }
    }

    private val ctx get() = RuntimeEnvironment.getApplication()

    /** Les applis d'une vraie télé (AccueilMiseAJourCaptureTest.APPLIS) : la liste dépasse la colonne et défile. */
    private fun installer() {
        val pm = shadowOf(ctx.packageManager)
        for ((pkg, nom) in AccueilMiseAJourCaptureTest.APPLIS) {
            pm.installPackage(PackageInfo().apply {
                packageName = pkg
                applicationInfo = ApplicationInfo().apply { packageName = pkg; nonLocalizedLabel = nom; enabled = true }
            })
            pm.setApplicationIcon(pkg, android.graphics.drawable.ShapeDrawable(android.graphics.drawable.shapes.OvalShape()).apply {
                paint.color = 0xFF000000.toInt() or (pkg.hashCode() and 0x00FFFFFF); intrinsicWidth = 96; intrinsicHeight = 96 })
            val cn = ComponentName(pkg, "$pkg.Principale")
            pm.addActivityIfNotPresent(cn)
            pm.addIntentFilterForActivity(cn, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER) })
        }
        ctx.getSharedPreferences("lanceur", android.content.Context.MODE_PRIVATE).edit().putString("tele_mode", "masquees").commit()
    }

    private fun ouvrir(ha: FauxHa): Activity {
        Recette.poser(taille, 1.0f)
        installer()
        ZoneSure.poser(ctx, zone, zone)
        org.soprez.vision.Config(ctx).apply { id = "essai_tele"; secret = "s".repeat(32); urlInterne = ha.adresse; urlExterne = ""; urlActive = "" }
        val cat = javaClass.getResource("/captures/catalogue_widgets.json")!!.readText()
            .replace("\"role\": \"reglage\", \"confirmer\": true,", "\"role\": \"reglage\", \"confirmer\": false,")
        WidgetsHaStock.garderCatalogue(ctx, cat)
        val eid = "input_number.consigne"
        WidgetsHaStock.ecrire(ctx, WidgetsHa.Reglage("zone-$eid", listOf(eid), WidgetsHa.REGLAGE, 2, 2, libelle = "Consigne chauffage"))
        DispositionAccueil.poserDisposTele(ctx, DispositionAccueil.TELE_VERTICAL)
        DispositionAccueil.poserColonne(ctx, DispositionAccueil.COLONNE_DROITE)
        DispositionAccueil.poserWidgetsZone(ctx, DispositionAccueil.ZONE_G,
            // La zone de la télé du salon (horloge, chauffage, consigne), plus la météo : elle dépasse sûrement la hauteur.
            listOf(ZoneHaut.HORLOGE, Accueil.PREFIXE_WIDGET + "chauffage", ZoneHaut.METEO, ZoneHaut.PREFIXE_HA + eid))
        DispositionAccueil.poserZoneActivee(ctx, DispositionAccueil.ZONE_G, true)
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val a = Robolectric.buildActivity(LanceurActivity::class.java).setup().visible().get()
        ShadowLooper.idleMainLooper(600, TimeUnit.MILLISECONDS)
        return a
    }

    /** Le rectangle dessiné d'une vue à l'écran (échelle des parents comprise). */
    private fun dessine(v: View): Rect {
        val p = IntArray(2).also { v.getLocationOnScreen(it) }
        var ky = 1f; var kx = 1f; var c: View? = v
        while (c != null) { kx *= c.scaleX; ky *= c.scaleY; c = c.parent as? View }
        return Rect(p[0], p[1], p[0] + (v.width * kx).toInt(), p[1] + (v.height * ky).toInt())
    }

    @Test fun accueil_vertical_parametres_fondu_et_zone_au_repos() {
        FauxHa().use { ha ->
            val a = ouvrir(ha)
            val d = a.window.decorView
            val zr = Debordements.zoneTele(a, d.width, d.height)
            val defauts = ArrayList<String>()

            // 1. Plus de roue en bas ; « Paramètres » est la dernière tuile, sans menu.
            if (d.findViewWithTag<View>("roue")?.isShown == true) defauts += "la roue des réglages est encore sur l'accueil vertical"
            val liste = d.findViewWithTag<ViewGroup>("colonne_applis") ?: error("pas de liste d'applis")
            val tuiles = (0 until liste.childCount).map { liste.getChildAt(it) }.filter { it.isFocusable }
            if (tuiles.lastOrNull()?.tag != "parametres") defauts += "la dernière tuile est « ${tuiles.lastOrNull()?.contentDescription} », pas Paramètres"
            val param = d.findViewWithTag<View>("parametres")
            if (param?.contentDescription != "Paramètres") defauts += "tuile Paramètres : libellé « ${param?.contentDescription} »"
            if (param?.getTag(org.soprez.vision.R.id.cadre_tele) != null) defauts += "Paramètres a un cadre d'appli (déplaçable, retirable)"
            // La touche Menu sur Paramètres n'ouvre rien (pas de « Retirer »).
            param?.requestFocus(); ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)
            param?.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MENU)); param?.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MENU))
            ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)
            if (org.robolectric.shadows.ShadowDialog.getLatestDialog()?.isShowing == true) defauts += "Menu sur Paramètres ouvre un menu"

            // 2. Fondu en haut et en bas de la liste ; l'appli prise reste hors du fondu.
            val defile = d.findViewWithTag<ScrollView>("defile_applis") ?: error("pas de liste défilante")
            if (!defile.canScrollVertically(-1)) defauts += "la liste n'a pas défilé jusqu'à Paramètres : l'essai du fondu ne vérifie rien"
            // Pendant la navigation, comme à la télécommande : en haut de la liste, puis quelques tuiles plus bas ; la liste
            // continue vers le bas : le fondu du bas, sur la capture.
            tuiles.firstOrNull()?.requestFocus(); ShadowLooper.idleMainLooper(800, TimeUnit.MILLISECONDS)
            for (k in 1..minOf(6, tuiles.size - 1)) { tuiles[k].requestFocus(); ShadowLooper.idleMainLooper(400, TimeUnit.MILLISECONDS) }
            // Android n'estompe que le côté où la liste continue : ici le bas (le haut l'est en fin de liste, plus loin).
            if (!defile.canScrollVertically(1)) defauts += "pendant la navigation, la liste ne continue pas vers le bas"
            Recette.capturer(d, "accueil-vertical-navigation-zone$zone", taille, 1.0f)
            param?.requestFocus(); ShadowLooper.idleMainLooper(400, TimeUnit.MILLISECONDS)
            if (!defile.isVerticalFadingEdgeEnabled || defile.verticalFadingEdgeLength <= 0) defauts += "pas de fondu en haut et en bas de la liste"
            // Android n'estompe un bord que si la liste continue de ce côté : l'appli prise (au milieu de la liste, liste
            // amenée par la télécommande) ne doit pas être dans un fondu actif.
            tuiles.getOrNull(tuiles.size / 2)?.let { t ->
                t.requestFocus(); ShadowLooper.idleMainLooper(400, TimeUnit.MILLISECONDS)
                val rt = dessine(t); val rd = dessine(defile); val f = defile.verticalFadingEdgeLength
                if (defile.canScrollVertically(1) && rt.bottom > rd.bottom - f + 2) defauts += "l'appli prise est dans le fondu du bas (${rt.bottom} > ${rd.bottom - f})"
                if (defile.canScrollVertically(-1) && rt.top < rd.top + f - 2) defauts += "l'appli prise est dans le fondu du haut (${rt.top} < ${rd.top + f})"
            }

            // 3. La liste prend la hauteur libérée : elle descend jusqu'au bas de la zone.
            val rd = dessine(defile)
            val bas = zr.bottom - rd.bottom
            if (bas > (40 * a.resources.displayMetrics.density).toInt()) defauts += "la liste s'arrête $bas px au-dessus du bas de la zone"

            // 4. La zone de gauche tient dans l'écran au repos : − et + entiers, dans la zone, sans défilement.
            val zg = d.findViewWithTag<ZoneAjustee>("zone_ajustee_${DispositionAccueil.ZONE_G}")
            if (zg == null) defauts += "la zone de gauche n'est pas ajustée"
            else if (zg.echelle >= 1f) defauts += "la zone de gauche n'a pas eu à être réduite : l'essai ne vérifie rien (échelle ${zg.echelle})"
            for (t in listOf("widget_moins", "widget_plus")) {
                val b = d.findViewWithTag<View>(t) ?: run { defauts += "$t absent"; null } ?: continue
                val r = dessine(b)
                if (r.top < zr.top - 2 || r.bottom > zr.bottom + 2 || r.height() < b.height * 0.5f) defauts += "$t hors de la zone au repos (${r.toShortString()}, zone ${zr.toShortString()})"
            }
            Debordements.horsZone(d, zr, defauts, Rect(0, 0, d.width, d.height))
            Recette.capturer(d, "accueil-vertical-zone$zone", taille, 1.0f)

            // 5. Paramètres reste la dernière tuile après le déplacement d'une appli (à la fin de la liste).
            val cles = Accueil.cases(ctx).map { it.cle }
            if (cles.size >= 2) Accueil.deplacer(ctx, cles, cles.first(), cles.size - 1)
            val a2 = Robolectric.buildActivity(LanceurActivity::class.java).setup().visible().get()
            ShadowLooper.idleMainLooper(600, TimeUnit.MILLISECONDS)
            val liste2 = a2.window.decorView.findViewWithTag<ViewGroup>("colonne_applis")
            val derniere = liste2?.let { l -> (0 until l.childCount).map { l.getChildAt(it) }.lastOrNull { it.isFocusable } }
            if (derniere?.tag != "parametres") defauts += "après un déplacement, la dernière tuile est « ${derniere?.contentDescription} »"
            assertEquals("${taille.nom} zone $zone :\n" + defauts.joinToString("\n"), emptyList<String>(), defauts)
        }
    }
}
