package org.soprez.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.test.InstrumentationTestCase
import android.view.View
import java.io.File
import java.io.FileOutputStream

/**
 * Les maquettes de l'œil rendues par le vrai dessin Android (OeilVue + OeilModele), image par image, pour l'auteur :
 * réactions A, B, C × succès, erreur, info (30 i/s sur 750 ms) et l'œil joyeux (25 i/s sur 6 s).
 * Les images vont dans files/oeil/<nom>/NNN.png ; un script les assemble en GIF.
 */
class OeilRenduTest : InstrumentationTestCase() {
    private val ctx get() = instrumentation.targetContext

    private fun rendre(nom: String, images: Int, pose: (Float) -> OeilModele.Pose, genre: OeilModele.Genre) {
        val dossier = File(ctx.getExternalFilesDir(null), "oeil/$nom").apply { deleteRecursively(); mkdirs() }
        val taille = 240
        instrumentation.runOnMainSync {
            val v = OeilVue(ctx)
            v.measure(View.MeasureSpec.makeMeasureSpec(taille, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(taille, View.MeasureSpec.EXACTLY))
            v.layout(0, 0, taille, taille)
            for (i in 0 until images) {
                v.poser(pose(i / (images - 1f)), genre)
                val b = Bitmap.createBitmap(taille, taille, Bitmap.Config.ARGB_8888)
                val c = Canvas(b); c.drawColor(0xFF24181E.toInt()); v.draw(c)
                FileOutputStream(File(dossier, "%03d.png".format(i))).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }

    fun testMaquettes() {
        val genres = mapOf("succes" to OeilModele.Genre.SUCCES, "erreur" to OeilModele.Genre.ERREUR, "info" to OeilModele.Genre.INFO)
        for (v in OeilModele.Variante.values()) for ((n, g) in genres) rendre("ok-${v.name}-$n", 23, { t -> OeilModele.reaction(v, g, t) }, g)
        rendre("joyeux", 150, { t -> OeilModele.joyeux(t * OeilModele.CYCLE_JOYEUX_S) }, OeilModele.Genre.JOIE)
    }
}
