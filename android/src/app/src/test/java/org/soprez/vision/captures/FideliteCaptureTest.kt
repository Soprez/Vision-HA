// SPDX-License-Identifier: MIT
package org.soprez.vision.captures

import android.text.TextPaint
import android.util.TypedValue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.soprez.vision.Polices

/**
 * Fidélité de la recette calculée à un vrai appareil, mesurée le 17/10 contre l'émulateur ux-tv (télé 1080p, police
 * 130 %, journal du 16/10) : même police agrandie non proportionnelle (Android 14 : 13 sp = 34,4 px et non 33,8) et même
 * largeur de texte (« Température ambiante » en Figtree demi-gras à 33,8 px : 342 px). Si ces nombres bougent (nouvelle
 * version de Robolectric, police changée), les captures ne disent plus la même chose qu'un appareil : à revoir d'abord.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "fr-w960dp-h540dp-land-television-xhdpi")
class FideliteCaptureTest {
    @Test fun police_agrandie_et_largeur_des_textes_comme_sur_l_appareil() {
        RuntimeEnvironment.setFontScale(1.3f)
        val ctx = RuntimeEnvironment.getApplication()
        val dm = ctx.resources.displayMetrics
        assertEquals(34.4f, TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, dm), 0.05f)
        val p = TextPaint().apply { typeface = Polices.demiGras(ctx); textSize = 33.8f }
        assertEquals(342.0f, p.measureText("Température ambiante"), 1f)
    }
}
