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
 * i18n, lot C1 : les écrans du lot en allemand (la langue la plus longue), à la taille normale puis en grands
 * caractères (1,3) : aucun TextView coupé (points de suspension, hauteur trop courte, lignes cachées, mot coupé
 * en deux, débord du parent). Captures dans /sdcard/Android/data/org.soprez.vision/files/captures_de.
 *
 * La langue de l'appli passe en allemand par « cmd locale set-app-locales » (Android 13 et plus), puis revient
 * à celle du système à la fin. Lancement (émulateur seulement, pas lancé par l'agent du lot) :
 *   gradle :app:assembleDebug :app:assembleDebugAndroidTest
 *   adb install -r app-debug.apk && adb install -r app-debug-androidTest.apk
 *   adb shell am instrument -w -e class org.soprez.vision.EcransAllemandLot1Test org.soprez.vision.test/android.test.InstrumentationTestRunner
 */
class EcransAllemandLot1Test : InstrumentationTestCase() {
    private val ctx get() = instrumentation.targetContext
    private val problemes = ArrayList<String>()
    private val dossier by lazy { File(ctx.getExternalFilesDir(null), "captures_de").apply { mkdirs() } }

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

    /** Les textes sans écran (blocage, statut, préavis) sont bien en allemand : la langue a pris. */
    fun testLaLangueAPris() {
        val a = instrumentation.startActivitySync(Intent(ctx, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val t = a.getString(R.string.blocage_appareil_titre)
        instrumentation.runOnMainSync { a.finish() }
        assertEquals("Dieses Gerät ist gesperrt", t)
    }

    private val appareil = JSONObject("""{
        "id":"essai_tablette","nom":"Tablette du salon de Maximilien","prenom":"Maximilien-Alexandre","role":"Anfragender",
        "en_ligne":true,"android":true,"verrouille":false,"partage_tous":false,"protections":{"accessibilite":false,"usage":false,"vpn":false,"admin":false,"accueil":false},
        "apps":[{"libelle":"Jeux vidéo en ligne avec discussion"}],"sites":[{"nom":"exemple.fr"}],
        "etiquettes":["Réseaux sociaux","Actu & médias","Pub & traçage"],
        "plages":[{"id":1,"nom":"Hausaufgaben","debut":"18:30","fin":"20:00","actif":true,"portee":"etiquettes","etiquettes":["Jeux","Vidéos"]}],
        "exceptions":{"autorises":[],"bloques":[],"toujours":[]},
        "usage":{"actif":95,"ouvert":120},"focus":"org.exemple","focus_libelle":"Application de dessin extraordinaire","inactif_s":900,
        "derogation":{"mode":"locked","fin":0}
    }""")
    private val categories = JSONArray("""[
        {"id":"jeux","nom":"Jeux","apps":[{"libelle":"Jeu de construction"}],"sites":["jeux.exemple"],"age":false},
        {"id":"pub","nom":"Pub & traçage","apps":[],"sites":["pub.exemple","trace.exemple"],"maison":true}
    ]""")
    private val demandes = JSONArray("""[
        {"pc":"essai_tablette","id":"d1","prenom":"Maximilien-Alexandre","libelle":"Application de dessin","genre":"apps","duree":"categorie","etiquettes":["Réseaux sociaux"],"motif":"Pour finir mon exposé"},
        {"pc":"essai_tablette","id":"d2","prenom":"Maximilien-Alexandre","genre":"temps","minutes":45}
    ]""")

    private fun parcourir(echelle: Float) {
        shell("settings put system font_scale $echelle")
        SystemClock.sleep(1500)
        val s = "${(echelle * 100).toInt()}"
        ecran("principal_$s", Intent(ctx, SetupActivity::class.java), defiler = true, preparer = { a ->
            champ(a, "tempsDeplie", true); champ(a, "gestionDepliee", true); champ(a, "autorisationsDepliees", true); appeler(a, "dessiner")
        })
        ecran("assistant_$s", Intent(ctx, SetupActivity::class.java), preparer = { a -> champ(a, "assistant", true); appeler(a, "dessiner") })
        ecran("gestion_$s", Intent(ctx, ParentsActivity::class.java), defiler = true, preparer = { a ->
            champ(a, "appareils", JSONArray().put(appareil)); champ(a, "demandes", demandes); champ(a, "categories", categories)
            champ(a, "deplies", hashSetOf("p:autres", "essai_tablette:plages")); champ(a, "erreur", ""); appeler(a, "dessiner")
        })
        CategoriesActivity.fiche = appareil; CategoriesActivity.cats = categories
        ecran("categories_$s", Intent(ctx, CategoriesActivity::class.java))
        ecran("fermes_$s", Intent(ctx, FermesActivity::class.java))
        ecran("deverrouillage_$s", Intent(ctx, DeverrouillageActivity::class.java))
        ecran("demandes_deverrouillage_$s", Intent(ctx, DeverrouillageParentActivity::class.java))
        ecran("message_$s", Intent(ctx, MessageEnvoiActivity::class.java))
        ecran("widget_ha_$s", Intent(ctx, WidgetHaConfigActivity::class.java))
        // Les boîtes du lot : durées, gestion d'un appareil, économiseur de la télé, effacement des données.
        ecran("temps_$s", Intent(ctx, SetupActivity::class.java), boite = { a ->
            Temps.choisir(a, a.getString(R.string.garde_accorder_du_temps), System.currentTimeMillis() + 3 * 3600_000L) {}; null
        })
        ecran("gestion_menu_$s", Intent(ctx, SetupActivity::class.java), boite = { a -> GestionAppareil.menu(a, appareil, JSONArray()) {}; null })
        ecran("economiseur_$s", Intent(ctx, SetupActivity::class.java), boite = { a -> EconomiseurTv.proposer(a) {}; null })
        ecran("effacer_$s", Intent(ctx, SetupActivity::class.java), boite = { a ->
            Ui.dialogue(a).setTitle(a.getString(R.string.actions_effacer_titre, "Application de dessin"))
                .setMessage(a.getString(R.string.actions_effacer_texte, "Application de dessin"))
                .setNegativeButton(R.string.commun_annuler, null).setPositiveButton(R.string.actions_tout_effacer, null).show(); null
        })
        File(dossier, "problemes_$s.txt").writeText(problemes.joinToString("\n").ifEmpty { "Aucun texte coupé." })
        assertTrue("Textes coupés en allemand (font_scale $echelle) :\n" + problemes.joinToString("\n"), problemes.isEmpty())
    }

    private fun ecran(nom: String, intent: Intent, preparer: ((Activity) -> Unit)? = null, boite: ((Activity) -> Any?)? = null, defiler: Boolean = false) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val a = instrumentation.startActivitySync(intent)
        instrumentation.waitForIdleSync(); SystemClock.sleep(1200)
        if (preparer != null) { instrumentation.runOnMainSync { preparer(a) }; instrumentation.waitForIdleSync(); SystemClock.sleep(600) }
        if (boite != null) { instrumentation.runOnMainSync { boite(a) }; instrumentation.waitForIdleSync(); SystemClock.sleep(800) }
        instrumentation.runOnMainSync {
            // Toutes les fenêtres de l'appli (l'écran et une boîte éventuelle).
            racines(a).forEach { verifier(it, nom) }
        }
        capturer(nom)
        if (defiler) for (page in 2..6) {
            var bouge = false
            instrumentation.runOnMainSync {
                val sv = defilement(a.window.decorView)
                if (sv != null) { val avant = sv.scrollY; sv.scrollBy(0, (sv.height * 0.85f).toInt()); bouge = sv.scrollY != avant }
            }
            if (!bouge) break
            instrumentation.waitForIdleSync(); SystemClock.sleep(400); capturer("${nom}_$page")
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

    /** Mêmes critères que EcransGrandsCaracteresTest. */
    private fun verifier(v: View, ecran: String) {
        if (!v.isShown) return
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
                    if (fin in 1 until t.length && t[fin - 1].isLetter() && t[fin].isLetter() && t.getOrNull(fin - 1) != '-') {
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

    private fun champ(o: Any, nom: String, valeur: Any) {
        var c: Class<*>? = o.javaClass
        while (c != null) {
            try { c.getDeclaredField(nom).apply { isAccessible = true }.set(o, valeur); return } catch (_: NoSuchFieldException) { c = c.superclass }
        }
        fail("Champ $nom introuvable sur ${o.javaClass.simpleName}")
    }

    private fun appeler(o: Any, nom: String) {
        val m = o.javaClass.declaredMethods.firstOrNull { it.name == nom && it.parameterTypes.isEmpty() }
            ?: return fail("Méthode $nom introuvable sur ${o.javaClass.simpleName}")
        m.isAccessible = true; m.invoke(o)
    }
}
