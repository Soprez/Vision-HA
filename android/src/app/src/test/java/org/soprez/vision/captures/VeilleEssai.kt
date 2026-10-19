// SPDX-License-Identifier: MIT
package org.soprez.vision.captures

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import org.json.JSONObject
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowLooper
import org.soprez.vision.VeilleView
import java.io.File

/**
 * 1.93.05 (retours 35, 36 et 39) : l'écran de veille dessiné à la taille de la télé, Home Assistant simulé ([FauxHa]),
 * avec la liste des textes réellement dessinés (la veille est une seule vue qui dessine tout sur son canevas).
 */
object VeilleEssai {
    /** Un canevas qui retient chaque texte dessiné. */
    class Enregistreur(b: Bitmap) : Canvas(b) {
        val textes = ArrayList<String>()
        override fun drawText(text: String, x: Float, y: Float, paint: Paint) { textes += text; super.drawText(text, x, y, paint) }
    }

    /** Une veille reliée au faux Home Assistant, posée à la taille de l'écran ; elle a reçu ses données. */
    fun vue(ha: FauxHa, veille: () -> JSONObject): VeilleView {
        val ctx = RuntimeEnvironment.getApplication()
        org.soprez.vision.Config(ctx).apply { id = "essai_tele"; secret = "s".repeat(32); urlInterne = ha.adresse; urlExterne = ""; urlActive = "" }
        ha.reponses["/api/pc_parental/veille"] = veille
        val v = VeilleView(ctx)
        val dm = ctx.resources.displayMetrics
        v.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, dm.widthPixels, dm.heightPixels)
        v.demarrer()
        val fin = System.currentTimeMillis() + 6000
        while (System.currentTimeMillis() < fin && ha.recus.none { it.first == "/api/pc_parental/veille" }) { ShadowLooper.idleMainLooper(); Thread.sleep(30) }
        Thread.sleep(300)
        // Les entrées en fondu sont finies (l'horloge, la date…).
        ShadowLooper.idleMainLooper(3000, java.util.concurrent.TimeUnit.MILLISECONDS)
        return v
    }

    /** Dessine la veille ([nom] : capture « recette-calc-<nom>-<taille>-100.png ») et rend les textes dessinés. */
    fun dessiner(v: VeilleView, nom: String, taille: Recette.Taille): List<String> {
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        val c = Enregistreur(b)
        v.draw(c)
        File(Recette.dossier, "recette-calc-$nom-${taille.nom}-100.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return c.textes
    }
}
