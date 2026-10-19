// SPDX-License-Identifier: MIT
package org.soprez.vision.captures

import android.app.Activity
import android.text.TextUtils
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * Témoin de la recette calculée : des défauts posés exprès DOIVENT être trouvés. Si ce témoin passe au vert sans rien
 * trouver, la recette ne vaut rien (rendu ou mesure des textes cassés) — c'est lui qu'on regarde d'abord.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "fr-w360dp-h780dp-port-xxhdpi")
class DebordementsCaptureTest {
    private fun page(remplir: (Activity, LinearLayout) -> Unit): LinearLayout {
        val a = Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        a.setContentView(col)
        remplir(a, col)
        ShadowLooper.idleMainLooper()
        return col
    }

    private fun px(a: Activity, dp: Float) = (dp * a.resources.displayMetrics.density).toInt()

    @Test fun les_defauts_poses_exprès_sont_trouves() {
        val col = page { a, c ->
            c.addView(TextView(a).apply { text = "Température ambiante du séjour"; textSize = 16f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(px(a, 70f), LinearLayout.LayoutParams.WRAP_CONTENT))
            c.addView(TextView(a).apply { text = "Une phrase sur trois lignes au moins dans une case trop basse"; textSize = 16f },
                LinearLayout.LayoutParams(px(a, 90f), px(a, 20f)))
            c.addView(TextView(a).apply { text = "Anticonstitutionnellement"; textSize = 18f; setHorizontallyScrolling(false) },
                LinearLayout.LayoutParams(px(a, 60f), LinearLayout.LayoutParams.WRAP_CONTENT))
            c.addView(FrameLayout(a).apply {
                addView(View(a).apply { tag = "widget_plus" }, FrameLayout.LayoutParams(px(a, 20f), px(a, 40f)))
            }, LinearLayout.LayoutParams(px(a, 60f), px(a, 60f)))
        }
        val d = Debordements.verifier(col, "temoin")
        val tout = d.joinToString("\n")
        assertTrue(tout, d.any { "Température ambiante" in it && "ellipsé" in it })
        assertTrue(tout, d.any { "Une phrase" in it && "coupé en bas" in it })
        assertTrue(tout, d.any { "Anticonstitutionnellement" in it && "mot coupé" in it })
        assertTrue(tout, d.any { "widget_plus" in it && "déformé" in it })
    }

    @Test fun un_nom_dit_en_entier_peut_prendre_des_points() {
        val col = page { a, c ->
            c.addView(TextView(a).apply { text = "Mode de fonctionnement de la ventilation"; contentDescription = text; maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(px(a, 70f), LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        assertTrue(Debordements.verifier(col, "temoin").isEmpty())
    }
}
