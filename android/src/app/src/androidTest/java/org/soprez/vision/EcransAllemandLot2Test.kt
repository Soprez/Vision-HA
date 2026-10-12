// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * i18n, lot C2 : les écrans du lot en allemand (la langue la plus longue), à la taille normale puis en grands
 * caractères (1,3) : aucun TextView coupé (points de suspension, hauteur trop courte, lignes cachées, mot coupé en
 * deux, débord du parent). Mêmes critères que EcransGrandsCaracteresTest et EcransAllemandLot1Test.
 * Captures dans /sdcard/Android/data/org.soprez.vision/files/captures_de_lot2.
 *
 * Les dessins sur toile (VeilleView, écran verrouillé) ne sont pas des TextView : ils sont seulement capturés.
 *
 * Lancement (émulateur seulement, pas lancé par l'agent du lot) :
 *   gradle :app:assembleDebug :app:assembleDebugAndroidTest
 *   adb install -r app-debug.apk && adb install -r app-debug-androidTest.apk
 *   adb shell am instrument -w -e class org.soprez.vision.EcransAllemandLot2Test org.soprez.vision.test/android.test.InstrumentationTestRunner
 */
class EcransAllemandLot2Test : InstrumentationTestCase() {
    private val ctx get() = instrumentation.targetContext
    private val problemes = ArrayList<String>()
    private val dossier by lazy { File(ctx.getExternalFilesDir(null), "captures_de_lot2").apply { mkdirs() } }

    private fun shell(cmd: String) {
        val pfd = instrumentation.uiAutomation.executeShellCommand(cmd)
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }

    override fun setUp() {
        super.setUp()
        shell("cmd locale set-app-locales org.soprez.vision --locales de-DE")
        SystemClock.sleep(1500)
    }

    override fun tearDown() {
        shell("settings put system font_scale 1.0")
        shell("cmd locale set-app-locales org.soprez.vision --locales \"\"")
        super.tearDown()
    }

    fun testAllemandTailleNormale() = parcourir(1.0f)
    fun testAllemandGrandsCaracteres() = parcourir(1.3f)

    /** La langue a pris : un texte du lot est bien en allemand. */
    fun testLaLangueAPris() {
        val a = instrumentation.startActivitySync(Intent(ctx, CouleursActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val t = a.getString(R.string.couleurs_revenir_theme)
        instrumentation.runOnMainSync { a.finish() }
        assertEquals("Zurück zum Design", t)
    }

    private val fiche = JSONObject("""{
        "id":"essai_tablette","nom":"Tablette du salon de Maximilien","prenom":"Maximilien-Alexandre",
        "apps":[{"nom":"org.exemple.dessin","libelle":"Application de dessin extraordinaire"}],"sites":[{"nom":"exemple.fr"}],
        "motifs":["regle|Von einem Verwalter gesperrt.","securite|Gefährliche Website"],"raisons":{"org.exemple.dessin":0,"exemple.fr":1},
        "exceptions":{"autorises":[{"genre":"apps","nom":"org.exemple.jeu","libelle":"Jeu de construction"}],
            "bloques":[{"genre":"sites","nom":"video.exemple","libelle":"video.exemple","maison":true}],"toujours":[]}
    }""")
    private val categories = JSONArray("""[{"id":"jeux","nom":"Jeux","apps":[{"nom":"org.exemple.jeu","libelle":"Jeu de construction"}],"sites":["jeux.exemple"]}]""")

    private fun parcourir(echelle: Float) {
        shell("settings put system font_scale $echelle")
        SystemClock.sleep(1500)
        val s = "${(echelle * 100).toInt()}"
        ecran("accueil_$s", Intent(ctx, LanceurActivity::class.java))
        ecran("reglages_$s", Intent(ctx, ReglagesTvActivity::class.java), defiler = true)
        ecran("couleurs_$s", Intent(ctx, CouleursActivity::class.java), defiler = true)
        ecran("musique_$s", Intent(ctx, PartyActivity::class.java), defiler = true)
        ecran("choix_musique_$s", Intent(ctx, ChoixMusiqueActivity::class.java).putExtra("lecteur_nom", "Lautsprecher im Wohnzimmer"))
        ecran("editeur_widget_$s", Intent(ctx, EditeurWidgetHaActivity::class.java), defiler = true)
        ExceptionsActivity.fiche = fiche; ExceptionsActivity.cats = categories
        ecran("exceptions_$s", Intent(ctx, ExceptionsActivity::class.java), defiler = true)
        DetailFermesActivity.fiche = fiche
        ecran("detail_fermes_$s", Intent(ctx, DetailFermesActivity::class.java), defiler = true)
        ecran("scan_$s", Intent(ctx, ScanActivity::class.java))
        ecran("appairage_$s", Intent(ctx, AppairageActivity::class.java))  // sans adresse : l'erreur et ses boutons
        ecran("blocage_$s", Intent(ctx, BlockActivity::class.java).putExtra("raison", BlockActivity.RAISON_REGLAGE).putExtra("pkg", "com.android.settings"))
        ecran("message_$s", Intent(ctx, MessageActivity::class.java).putExtra("titre", "Robin · wichtig").putExtra("texte", "Das Abendessen ist fertig, komm bitte runter."))
        ecran("message_demande_$s", MessageActivity.intention(ctx, "Anfrage", "Maximilien-Alexandre fragt nach mehr Zeit", false,
            JSONObject().put("pc", "essai_tablette").put("id", "d1").put("nom", "Maximilien-Alexandre")))
        // Les boîtes du lot : demande d'accès, sélecteur de widgets, menus de l'accueil.
        ecran("acces_$s", Intent(ctx, CouleursActivity::class.java), boite = { a -> Acces.dialogue(a, "sites", "exemple.fr", "Application de dessin extraordinaire") })
        ecran("selecteur_widgets_$s", Intent(ctx, CouleursActivity::class.java), boite = { a -> SelecteurWidgets.ouvrir(a, Themes.liste[0], 80f, 100f) {}; null })
        File(dossier, "problemes_$s.txt").writeText(problemes.joinToString("\n").ifEmpty { "Aucun texte coupé." })
        assertTrue("Textes coupés en allemand (font_scale $echelle) :\n" + problemes.joinToString("\n"), problemes.isEmpty())
    }

    private fun ecran(nom: String, intent: Intent, boite: ((Activity) -> Any?)? = null, defiler: Boolean = false) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val a = instrumentation.startActivitySync(intent)
        instrumentation.waitForIdleSync(); SystemClock.sleep(1500)
        if (boite != null) { instrumentation.runOnMainSync { boite(a) }; instrumentation.waitForIdleSync(); SystemClock.sleep(800) }
        if (!a.isFinishing) instrumentation.runOnMainSync { racines(a).forEach { verifier(it, nom) } }
        capturer(nom)
        if (defiler) for (page in 2..6) {
            var bouge = false
            instrumentation.runOnMainSync {
                val sv = defilement(a.window.decorView)
                if (sv != null) { val avant = sv.scrollY; sv.scrollBy(0, (sv.height * 0.85f).toInt()); bouge = sv.scrollY != avant }
            }
            if (!bouge) break
            instrumentation.waitForIdleSync(); SystemClock.sleep(400)
            instrumentation.runOnMainSync { racines(a).forEach { verifier(it, "${nom}_$page") } }
            capturer("${nom}_$page")
        }
        instrumentation.runOnMainSync { a.finish() }
        instrumentation.waitForIdleSync(); SystemClock.sleep(400)
    }

    @Suppress("UNCHECKED_CAST")
    private fun racines(a: Activity): List<View> = try {
        val wmg = Class.forName("android.view.WindowManagerGlobal")
        val g = wmg.getMethod("getInstance").invoke(null)
        val vues = wmg.getDeclaredField("mViews").apply { isAccessible = true }.get(g) as List<View>
        vues.filter { it.context.let { c -> c === a || (c as? android.content.ContextWrapper)?.baseContext === a } || it === a.window.decorView }
            .ifEmpty { listOf(a.window.decorView) }
    } catch (_: Throwable) { listOf(a.window.decorView) }

    private fun defilement(v: View): android.widget.ScrollView? {
        if (v is android.widget.ScrollView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) defilement(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun capturer(nom: String) {
        val b: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(dossier, "$nom.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun dansDefilementHorizontal(v: View): Boolean {
        var p = v.parent
        while (p is View) { if (p is HorizontalScrollView) return true; p = p.parent }
        return false
    }

    private fun etiquetteIcone(tv: TextView): Boolean {
        if (tv.maxLines != 2) return false
        var p: Any? = tv.parent; var n = 0
        while (p is View && n < 3) { if (!p.contentDescription.isNullOrEmpty()) return true; p = p.parent; n++ }
        return false
    }

    /** L'aperçu d'un widget d'une autre appli (Agenda, Chrome…) : rendu par Android, ses textes ne sont pas ceux de Vision. */
    private fun apercuAutreAppli(v: View): Boolean {
        var p = v.parent
        while (p is View) { if (p is android.appwidget.AppWidgetHostView) return true; p = p.parent }
        return false
    }

    private fun verifier(v: View, ecran: String) {
        if (!v.isShown || apercuAutreAppli(v)) return
        if (v is TextView && v !is EditText && v.text.isNotEmpty() && v.width > 0) {
            val l = v.layout
            val nom = "$ecran · « ${v.text.toString().replace('\n', ' ').take(60)} »"
            if (l != null) {
                val coupe = (0 until l.lineCount).any { l.getEllipsisCount(it) > 0 }
                if (coupe && !etiquetteIcone(v) && !texteEntierDit(v)) problemes += "$nom : points de suspension"
                val visibles = minOf(l.lineCount, v.maxLines.takeIf { it > 0 } ?: Int.MAX_VALUE)
                val utile = v.height - v.totalPaddingTop - v.totalPaddingBottom
                if (!coupe && l.getLineBottom(visibles - 1) > utile + 2) problemes += "$nom : hauteur trop courte (${l.getLineBottom(visibles - 1)} > $utile px)"
                if (visibles < l.lineCount && !etiquetteIcone(v)) problemes += "$nom : lignes cachées (${l.lineCount} > ${v.maxLines})"
                val t = v.text
                for (i in 0 until l.lineCount - 1) {
                    val fin = l.getLineEnd(i)
                    // Un identifiant d'entité (sensor.humidite_…) n'a pas de point de coupure : ce n'est pas un texte traduit.
                    var g = fin; while (g > 0 && (t[g - 1].isLetterOrDigit() || t[g - 1] == '_' || t[g - 1] == '.')) g--
                    var d = fin; while (d < t.length && (t[d].isLetterOrDigit() || t[d] == '_' || t[d] == '.')) d++
                    val identifiant = t.subSequence(g, d).any { it == '_' || it == '.' }
                    if (!identifiant && fin in 1 until t.length && t[fin - 1].isLetter() && t[fin].isLetter() && t.getOrNull(fin - 1) != '-') {
                        problemes += "$nom : mot coupé en deux (ligne ${i + 1})"; break
                    }
                }
            }
            val parent = v.parent as? ViewGroup
            if (parent != null && !dansDefilementHorizontal(v) && (v.left < -2 || v.right > parent.width + 2))
                problemes += "$nom : dépasse son parent (${v.left}..${v.right} sur ${parent.width} px)"
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) verifier(v.getChildAt(i), ecran)
    }
}
