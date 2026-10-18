// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Intent
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup

/**
 * 1.93.02 (télé du salon de l'auteur, 17-10) : en disposition « Vertical », sans widget dans les rangées du haut et du bas,
 * l'accueil de la 1.93.01 ne montrait que le fond (la rangée vide prenait toute la hauteur, la colonne tombait à 0 px).
 */
class AccueilVerticalRangeesVidesTest : InstrumentationTestCase() {
    private val ctx get() = instrumentation.targetContext
    private var avant: Map<String, *> = emptyMap<String, Any>()

    override fun setUp() {
        super.setUp()
        CodeEssai.poser(ctx)
        EcransVivants.suivre(instrumentation)
        avant = HashMap(ctx.getSharedPreferences("lanceur", 0).all)
    }

    override fun tearDown() {
        val e = ctx.getSharedPreferences("lanceur", 0).edit().clear()
        avant.forEach { (k, v) ->
            when (v) { is String -> e.putString(k, v); is Boolean -> e.putBoolean(k, v); is Int -> e.putInt(k, v); is Long -> e.putLong(k, v); is Float -> e.putFloat(k, v) }
        }
        e.commit()
        CodeEssai.rendre(ctx)
        super.tearDown()
    }

    private fun vue(v: View, tag: String): View? {
        if (v.tag == tag) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) vue(v.getChildAt(i), tag)?.let { return it }
        return null
    }

    fun testColonneVisibleSansRangees() {
        if (!ReglagesTvActivity.estTele(ctx)) return
        DispositionAccueil.poserDisposTele(ctx, DispositionAccueil.TELE_VERTICAL)
        for (z in DispositionAccueil.RANGEES) { DispositionAccueil.poserWidgetsZone(ctx, z, emptyList()); DispositionAccueil.poserZoneActivee(ctx, z, false) }
        val a = EcransVivants.lancer(instrumentation, Intent(ctx, LanceurActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        instrumentation.waitForIdleSync()
        var hLigne = -1; var hCol = -1; var hHaut = -1
        instrumentation.runOnMainSync {
            val d = a.window.decorView
            hLigne = vue(d, "tele_ligne")?.height ?: -1
            hCol = vue(d, "colonne_tele")?.height ?: -1
            hHaut = vue(d, "zone_tele_" + DispositionAccueil.ZONE_H)?.height ?: -1
        }
        a.finish()
        val ecran = ctx.resources.displayMetrics.heightPixels
        assertTrue("la ligne de l'accueil Vertical est écrasée : $hLigne px (rangée du haut : $hHaut px)", hLigne > ecran / 2)
        assertTrue("la colonne des applis est écrasée : $hCol px", hCol > ecran / 2)
    }
}
