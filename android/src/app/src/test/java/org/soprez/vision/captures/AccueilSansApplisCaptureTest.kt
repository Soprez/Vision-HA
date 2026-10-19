// SPDX-License-Identifier: MIT
package org.soprez.vision.captures

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.Rect
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
import org.soprez.vision.Accueil
import org.soprez.vision.AffichageApplis
import org.soprez.vision.DispositionAccueil
import org.soprez.vision.Garde
import org.soprez.vision.LanceurActivity
import org.soprez.vision.ReglagesTvActivity
import org.soprez.vision.SetupActivity
import org.soprez.vision.ZoneHaut
import java.util.concurrent.TimeUnit

/**
 * 1.93.06.1 (l'auteur, tablettes utilisées comme tableau de bord) : « Afficher la liste des applications » désactivée.
 * Tablette en portrait et en paysage :
 * - l'accueil ne montre que les widgets (ni applis, ni dock, ni poignée du tiroir), sans les lignes laissées vides ;
 * - un appui long sur l'heure (l'en-tête) ouvre les paramètres, derrière le code ou l'empreinte (ici la fenêtre de la
 *   garde est ouverte : les paramètres s'ouvrent ; fermée, la garde demande la preuve) ;
 * - sans aucun widget, la liste reste (jamais un accueil vide, jamais enfermé dehors).
 * Télé en disposition verticale : pas de colonne d'applis ; appui long sur OK sur l'horloge → paramètres de la télé.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class AccueilSansApplisCaptureTest(private val taille: Recette.Taille) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun parametres(): List<Array<Any>> = Recette.TAILLES.filter { it.nom in setOf("tablette-portrait", "tablette-paysage", "tv-1080p") }.map { arrayOf<Any>(it) }
    }

    private val ctx get() = RuntimeEnvironment.getApplication()

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
            pm.addIntentFilterForActivity(cn, IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(if (taille.tele) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER) })
        }
    }

    /** La fenêtre de la garde, ouverte comme après une preuve réussie (champ privé, posé par réflexion comme ailleurs). */
    private fun ouvrirGarde() {
        val f = Garde::class.java.getDeclaredField("jusqua").apply { isAccessible = true }
        f.setLong(Garde, android.os.SystemClock.elapsedRealtime() + 60_000L)
    }

    private fun preparer(widgets: Boolean) {
        Recette.poser(taille, 1.0f)
        org.soprez.vision.Config(ctx).apply { id = "essai_tablette"; secret = "s".repeat(32); urlInterne = "http://127.0.0.1:9"; urlExterne = ""; urlActive = "" }
        installer()
        val apps = AccueilMiseAJourCaptureTest.APPLIS.map { it.first }
        if (taille.tele) {
            ctx.getSharedPreferences("lanceur", android.content.Context.MODE_PRIVATE).edit().putString("tele_mode", "masquees").commit()
            DispositionAccueil.poserDisposTele(ctx, DispositionAccueil.TELE_VERTICAL)
            DispositionAccueil.poserColonne(ctx, DispositionAccueil.COLONNE_DROITE)
            if (widgets) {
                DispositionAccueil.poserWidgetsZone(ctx, DispositionAccueil.ZONE_G, listOf(ZoneHaut.HORLOGE, ZoneHaut.METEO))
                DispositionAccueil.poserZoneActivee(ctx, DispositionAccueil.ZONE_G, true)
            }
        } else {
            // Des applis sur les deux premières lignes et la cinquième, des widgets entre elles et après.
            val l = ArrayList<Accueil.Place>()
            apps.take(8).forEachIndexed { i, p -> l += Accueil.Place(p, i % 4, i / 4, 1, 1) }
            apps.drop(8).take(4).forEachIndexed { i, p -> l += Accueil.Place(p, i, 4, 1, 1) }
            if (widgets) {
                l += Accueil.Place(Accueil.PREFIXE_WIDGET + "chauffage", 0, 2, 2, 2)
                l += Accueil.Place(Accueil.PREFIXE_WIDGET + "fioul", 2, 2, 2, 2)
                l += Accueil.Place(Accueil.PREFIXE_WIDGET + "batteries", 0, 5, 2, 2)
            }
            Accueil.ecrireDisposition(ctx, l)
            ZoneHaut.poser(ctx, listOf(ZoneHaut.HORLOGE))
        }
        AffichageApplis.poser(ctx, false)
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(taille.tele.not())
    }

    private fun ouvrir(): Activity {
        val a = Robolectric.buildActivity(LanceurActivity::class.java).setup().visible().get()
        ShadowLooper.idleMainLooper(600, TimeUnit.MILLISECONDS)
        return a
    }

    private fun visible(d: View, tag: String) = d.findViewWithTag<View>(tag)?.isShown == true

    private fun places(v: View, sortie: MutableList<Accueil.Place> = ArrayList()): List<Accueil.Place> {
        if (v.isShown) (v.tag as? Accueil.Place)?.let { sortie += it }
        if (v is ViewGroup) for (i in 0 until v.childCount) places(v.getChildAt(i), sortie)
        return sortie
    }

    @Test fun accueil_widgets_seuls_et_acces_aux_parametres() {
        preparer(widgets = true)
        val a = ouvrir()
        val d = a.window.decorView
        val defauts = ArrayList<String>()
        if (taille.tele) {
            if (visible(d, "colonne_tele")) defauts += "la colonne d'applis est encore là"
            if (visible(d, "parametres")) defauts += "la tuile Paramètres est encore là (colonne)"
            val horloge = d.findViewWithTag<View>("carte_zone_${DispositionAccueil.ZONE_G}_${ZoneHaut.HORLOGE}") ?: error("pas d'horloge dans la zone")
            Recette.capturer(d, "accueil-sans-applis", taille, 1.0f).let { defauts += it }
            Debordements.horsZone(d, Debordements.zoneTele(a, d.width, d.height), defauts, Rect(0, 0, d.width, d.height))
            // Appui long sur OK sur l'horloge : les paramètres de la télé (garde ouverte).
            horloge.requestFocus(); ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)
            ouvrirGarde()
            LanceurActivity::class.java.getDeclaredMethod("appuiLongOk").apply { isAccessible = true }.invoke(a)
            val suivante = shadowOf(a).nextStartedActivity
            if (suivante?.component?.className != ReglagesTvActivity::class.java.name) defauts += "appui long sur l'horloge : ${suivante?.component?.className} au lieu des paramètres"
        } else {
            if (visible(d, "dock")) defauts += "le dock est encore là"
            if (visible(d, "poignee_tiroir")) defauts += "la poignée du tiroir est encore là"
            val vues = places(d)
            val applis = vues.filter { !it.estWidget }
            if (applis.isNotEmpty()) defauts += "${applis.size} appli(s) encore sur l'accueil : ${applis.joinToString { it.cle }}"
            if (vues.count { it.estWidget } != 3) defauts += "${vues.count { it.estWidget }} widgets au lieu de 3"
            vues.firstOrNull { it.code == "batteries" }?.let { if (it.row != 2) defauts += "batteries en ligne ${it.row} au lieu de 2 (lignes vides non retirées)" }
            // Les widgets prennent la place : chacun de 2 colonnes fait au moins le quart de l'écran en largeur, et tous
            // tiennent dans l'écran (rien sous le bord).
            fun vueDe(code: String): View? { var r: View? = null
                fun cherche(v: View) { if ((v.tag as? Accueil.Place)?.code == code) r = v; if (v is ViewGroup) for (i in 0 until v.childCount) cherche(v.getChildAt(i)) }
                cherche(d); return r }
            for (code in listOf("chauffage", "fioul", "batteries")) {
                val v = vueDe(code) ?: continue
                val p = IntArray(2).also { v.getLocationOnScreen(it) }
                if (v.width < d.width / 4 - 2) defauts += "$code : ${v.width} px de large, moins du quart de l'écran (${d.width})"
                if (p[1] + v.height > d.height + 2) defauts += "$code sous le bord de l'écran (${p[1] + v.height} > ${d.height})"
            }
            // La disposition enregistrée n'a pas changé.
            if (Accueil.disposition(ctx).firstOrNull { it.code == "batteries" }?.row != 5) defauts += "la disposition enregistrée a changé"
            defauts += Recette.capturer(d, "accueil-sans-applis", taille, 1.0f)
            // Appui long sur l'heure (l'en-tête) : les paramètres, derrière la garde (ouverte ici).
            val entete = d.findViewWithTag<View>("entete_telephone") ?: error("pas d'en-tête (heure)")
            ouvrirGarde()
            if (!entete.performLongClick()) defauts += "l'appui long sur l'heure ne fait rien"
            val suivante = shadowOf(a).nextStartedActivity
            if (suivante?.component?.className != SetupActivity::class.java.name || suivante.getStringExtra(SetupActivity.EXTRA_ESPACE) != org.soprez.vision.Espaces.REGLAGES)
                defauts += "appui long sur l'heure : ${suivante?.component?.className} (${suivante?.getStringExtra(SetupActivity.EXTRA_ESPACE)}) au lieu des réglages"
        }
        Garde.fermer()
        assertEquals("${taille.nom} :\n" + defauts.joinToString("\n"), emptyList<String>(), defauts)
    }

    @Test fun sans_aucun_widget_la_liste_reste() {
        preparer(widgets = false)
        val d = ouvrir().window.decorView
        val ok = if (taille.tele) visible(d, "colonne_tele") && visible(d, "parametres") else visible(d, "dock") && places(d).any { !it.estWidget }
        assertEquals("${taille.nom} : sans widget, la liste des applis doit rester", true, ok)
    }
}
