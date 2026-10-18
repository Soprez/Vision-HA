// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.test.InstrumentationTestCase
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar

/**
 * 1.91.2 (l'auteur : « on avait créé plusieurs types d'horloges ») : chaque style d'horloge dessiné à une heure fixe
 * (mercredi 14 octobre, 10:08:37), en 1280 × 720, dans quelques thèmes ; les images servent à la revue des styles.
 * Elles vont dans les fichiers externes de l'appli, dossier « horloges ».
 *
 *   adb shell am instrument -w -e class org.soprez.vision.HorlogesRenduTest org.soprez.vision.test/android.test.InstrumentationTestRunner
 */
class HorlogesRenduTest : InstrumentationTestCase() {
    private val ctx get() = instrumentation.targetContext

    fun testRendreChaqueStyle() {
        val dossier = File(ctx.getExternalFilesDir(null), "horloges").apply { deleteRecursively(); mkdirs() }
        val murale = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 14, 10, 8, 37); set(Calendar.MILLISECOND, 0) }.timeInMillis
        val w = 1280; val h = 720
        for (nomTheme in listOf("Sombre", "Beige", "Tactique")) {
            val th = Themes.parNom(nomTheme)
            val k = HorlogesVeille.Couleurs(th.encre, th.encre2, th.pourpre, th.or, th.carte or 0xFF000000.toInt(), th.fond or 0xFF000000.toInt())
            for (style in HorlogesVeille.STYLES) {
                val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val c = Canvas(b)
                c.drawColor(th.fond or 0xFF000000.toInt())
                HorlogesVeille.dessiner(c, style, w.toFloat(), h.toFloat(), HorlogesVeille.Pinceaux("fr"), k, 1f, 1f, murale)
                val l = HorlogesVeille.lignesSous(style)
                val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
                t.textSize = h * 0.045f; t.color = th.encre2
                c.drawText("Mercredi 14 octobre", w / 2f, h * l[0], t)
                t.textSize = h * 0.032f; t.color = th.pourpre
                c.drawText("12° · Éclaircies", w / 2f, h * l[1], t)
                FileOutputStream(File(dossier, "$style-${nomTheme.lowercase()}.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        assertEquals(HorlogesVeille.STYLES.size * 3, dossier.list()!!.size)
    }
}
