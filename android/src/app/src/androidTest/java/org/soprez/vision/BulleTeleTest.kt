// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.test.InstrumentationTestCase

/**
 * 1.85.1 (bug télé d'essai) : l'astuce « Accueil ×2 : écran de veille » s'affiche, puis disparaît d'elle-même au bout
 * du délai, même quand une autre appli passe au premier plan et que l'accueil Vision est en arrière-plan.
 */
class BulleTeleTest : InstrumentationTestCase() {
    private val ctx get() = instrumentation.targetContext

    fun testDisparaitSeuleAvecUneAutreAppliDevant() {
        if (!ReglagesTvActivity.estTele(ctx)) return
        assertTrue("permission « par-dessus les autres applis » absente sur l'émulateur", Bulle.permise(ctx))
        instrumentation.runOnMainSync { Bulle.montrerUnTemps(ctx, "essai.autre.appli") }
        assertTrue("l'astuce ne s'est pas affichée", Bulle.visible())
        // Une autre appli devant (pas Réglages d'Android : il masque toute incrustation, l'essai ne prouverait rien).
        val autre = ctx.packageManager.getLaunchIntentForPackage("com.google.android.youtube.tv")
            ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        ctx.startActivity(autre.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        SystemClock.sleep(2000)
        var pendant = false
        instrumentation.runOnMainSync { pendant = Bulle.visible() }
        assertTrue("l'astuce a disparu trop tôt", pendant)
        SystemClock.sleep(Bulle.DUREE_TELE_MS - 2000 + 1500)
        var encore = true
        instrumentation.runOnMainSync { encore = Bulle.visible() }
        assertFalse("l'astuce est restée par-dessus l'autre appli", encore)
        ctx.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
