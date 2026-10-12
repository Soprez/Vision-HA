// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.app.UiAutomation
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
 * Les écrans principaux en grands caractères (font_scale 2,0 puis 1,3, et 1,0 en référence), en portrait puis en
 * paysage : aucun TextView ne doit être coupé (points de suspension, hauteur trop courte,
 * débord du parent, mot coupé en deux). Exceptions : les étiquettes d'icônes d'applis (2 lignes,
 * nom complet en contentDescription). Les captures vont dans
 * /sdcard/Android/data/org.soprez.vision/files/captures (à récupérer avec adb pull).
 *
 * Lancement (émulateur seulement) :
 *   gradle :app:assembleDebug :app:assembleDebugAndroidTest
 *   adb install -r app-debug.apk && adb install -r app-debug-androidTest.apk
 *   adb shell am instrument -w org.soprez.vision.test/android.test.InstrumentationTestRunner
 */
class EcransGrandsCaracteresTest : InstrumentationTestCase() {
    private val ctx get() = instrumentation.targetContext
    private val problemes = ArrayList<String>()
    private val dossier by lazy { File(ctx.getExternalFilesDir(null), "captures").apply { mkdirs() } }

    private fun shell(cmd: String) {
        val pfd = instrumentation.uiAutomation.executeShellCommand(cmd)
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }

    override fun tearDown() {
        shell("settings put system font_scale 1.0")
        instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_UNFREEZE)
        super.tearDown()
    }

    fun testGrandsCaracteres200() = parcourir(2.0f)
    fun testGrandsCaracteres130() = parcourir(1.3f)
    /** Référence à la taille normale : rien ne doit avoir régressé. */
    fun testTailleNormale100() = parcourir(1.0f)

    /**
     * 1.76.0 (point 6 bis) : les widgets système « Vision · Home Assistant », chaque style, petit / moyen / grand,
     * en 1,0 et 2,0 : rien de coupé, une image non vide (données d'exemple : l'émulateur n'est pas relié).
     */
    fun testWidgetsSysteme() {
        for (echelle in listOf(1.0f, 2.0f)) {
            shell("settings put system font_scale $echelle")
            SystemClock.sleep(1500)
            val app = instrumentation.targetContext
            for (style in WidgetsHa.STYLES.filter { it != WidgetsHa.MINI_CARTE }) {
                for ((w, h) in listOf(110 to 40, 180 to 110, 250 to 250)) {
                    val nom = "widget_sys_${style}_${w}x${h}_${(echelle * 100).toInt()}"
                    instrumentation.runOnMainSync {
                        val r = WidgetsHa.Reglage("essai", listOf(WidgetsHa.exemple(style).entityId), style, 2, 1)
                        val img = WidgetsSysteme.image(app, r, w, h, exemple = true)
                        var differents = 0
                        val ref = img.getPixel(img.width / 2, 2)
                        for (y in 0 until img.height step 4) for (x in 0 until img.width step 4) if (img.getPixel(x, y) != ref) differents++
                        if (differents < 20) problemes += "$nom : image vide"
                        File(dossier, "$nom.png").outputStream().use { img.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                }
            }
        }
        shell("settings put system font_scale 1.0")
        File(dossier, "problemes_widgets.txt").writeText(problemes.joinToString("\n").ifEmpty { "Aucun problème." })
        assertTrue("Widgets système :\n" + problemes.joinToString("\n"), problemes.isEmpty())
    }

    /** 1.72.0 : le mode sombre sur des thèmes clairs, écran principal et accueil, en 1,3. */
    fun testModeSombre() {
        shell("settings put system font_scale 1.3")
        SystemClock.sleep(1500)
        instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
        val themeAvant = Local.theme(ctx); val modeAvant = Local.modeSombre(ctx); val nuitAvant = Local.nuit(ctx)
        Local.poserNuit(ctx, "")
        try {
            Local.poserModeSombre(ctx, Themes.SOMBRE_TOUJOURS)
            avecAppareilRelie {
                Etat.parent = true
                for (theme in listOf("Beige", "Sauge", "Rose poudré", "Briques")) {
                    Local.poserTheme(ctx, theme)
                    val nom = Themes.cle(theme)
                    ecran("sombre_${nom}_principal", Intent(ctx, SetupActivity::class.java))
                    ecran("sombre_${nom}_accueil", Intent(ctx, LanceurActivity::class.java))
                }
            }
        } finally {
            Local.poserTheme(ctx, themeAvant); Local.poserModeSombre(ctx, modeAvant); Local.poserNuit(ctx, nuitAvant)
        }
        File(dossier, "problemes_sombre.txt").writeText(problemes.joinToString("\n").ifEmpty { "Aucun texte coupé." })
        assertTrue("Textes coupés en mode sombre :\n" + problemes.joinToString("\n"), problemes.isEmpty())
    }

    /** « screensaver_components » se lit sans permission (assistant TV, veille de repli). */
    fun testEconomiseurLisible() {
        assertNotNull("Settings.Secure screensaver_components illisible", EconomiseurTv.estVision(ctx))
    }

    /**
     * L'écran de blocage aux couleurs de deux thèmes de l'appareil (un clair, un sombre), en grands
     * caractères : appareil fermé (raison et heure de réouverture), puis appli fermée (motif du serveur).
     */
    fun testBlocageDeuxThemes() {
        shell("settings put system font_scale 2.0")
        SystemClock.sleep(1500)
        instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
        val themeAvant = Local.theme(ctx); val nuitAvant = Local.nuit(ctx)
        val verrouAvant = Etat.verrouille; val msgAvant = Etat.message; val prochainAvant = Etat.prochainChangement
        val appsAvant = Etat.apps; val raisonsAvant = Etat.raisons; val paquetAvant = Usage.dernierPaquet
        Local.poserNuit(ctx, "")
        try {
            for (theme in listOf("Beige", "Bleu nuit")) {
                Local.poserTheme(ctx, theme)
                val nom = theme.lowercase().replace(' ', '_')
                Etat.verrouille = true; Etat.message = "Temps d'écran de la journée écoulé"
                Etat.prochainChangement = System.currentTimeMillis() + 10 * 3600_000L
                ecran("blocage_${nom}_appareil", Intent(ctx, BlockActivity::class.java).putExtra("raison", Etat.message).putExtra("pkg", ""))
                Etat.verrouille = false; Etat.prochainChangement = 0
                val pkg = "com.android.chrome"
                Etat.apps = setOf(pkg); Etat.raisons = mapOf(pkg to "plage|Fermée en dehors des plages de l'agenda du soir")
                Usage.dernierPaquet = pkg
                ecran("blocage_${nom}_appli", Intent(ctx, BlockActivity::class.java).putExtra("raison", "Cette appli est bloquée").putExtra("pkg", pkg))
            }
        } finally {
            Etat.verrouille = verrouAvant; Etat.message = msgAvant; Etat.prochainChangement = prochainAvant
            Etat.apps = appsAvant; Etat.raisons = raisonsAvant; Usage.dernierPaquet = paquetAvant
            Local.poserTheme(ctx, themeAvant); Local.poserNuit(ctx, nuitAvant)
        }
        val rapport = File(dossier, "problemes_blocage.txt")
        rapport.writeText(problemes.joinToString("\n").ifEmpty { "Aucun texte coupé." })
        assertTrue("Textes coupés sur l'écran de blocage :\n" + problemes.joinToString("\n"), problemes.isEmpty())
    }

    // ------------------------------------------------------------------ Données d'essai (noms longs exprès)

    private val appareil = JSONObject("""{
        "id":"essai_tablette","nom":"Tablette du salon de Maximilien","prenom":"Maximilien-Alexandre","role":"Enfant",
        "en_ligne":true,"android":true,"verrouille":false,"partage_tous":false,"protections":{"accessibilite":false,"usage":true,"accueil":false},
        "apps":[{"libelle":"Jeux vidéo en ligne avec discussion"},{"libelle":"Réseaux sociaux"}],
        "sites":[{"nom":"exemple.fr"},{"nom":"autre-exemple.com"}],
        "plages":[],
        "exceptions":{"autorises":[{"libelle":"Encyclopédie collaborative","nom":"wiki"}],"bloques":[{"libelle":"Vidéos en continu","nom":"video"}],"toujours":[]},
        "temps":{"actif":95,"ouvert":120},"usage":{"focus":"org.exemple","focus_libelle":"Application de dessin extraordinaire"}
    }""")
    private val categories = JSONArray("""[
        {"id":"jeux","nom":"Jeux vidéo et divertissement en ligne","apps":[{"libelle":"Jeu de construction"}],"sites":["jeux.exemple"],"coupe":true,"raison":"Fermé par le planning du soir"},
        {"id":"etude","nom":"Études","apps":[],"sites":[],"coupe":false}
    ]""")
    private val demandes = JSONArray("""[
        {"pc":"essai_tablette","id":"d1","prenom":"Maximilien-Alexandre","libelle":"Application de dessin extraordinaire","genre":"apps","cle":"org.exemple","duree":"","motif":"Pour finir mon exposé d'histoire-géographie"}
    ]""")

    /**
     * Un appareil relié pour la durée d'un essai (identifiants factices, aucune adresse : rien ne part sur le
     * réseau), avec un relevé de gestion et un temps du jour aux noms longs ; tout est remis comme avant.
     */
    private fun avecAppareilRelie(bloc: () -> Unit) {
        val vision = ctx.getSharedPreferences("vision", android.content.Context.MODE_PRIVATE)
        val profil = ctx.getSharedPreferences("profil", android.content.Context.MODE_PRIVATE)
        val sauveVision = HashMap(vision.all); val sauveProfil = HashMap(profil.all)
        val parentAvant = Etat.parent; val prochainAvant = Etat.prochainChangement; val reponseAvant = Etat.derniereReponse
        try {
            val cfg = Config(ctx)
            cfg.id = "essai_ecran"; cfg.secret = "s".repeat(32); cfg.nom = "Tablette du salon de Maximilien"
            cfg.utilisateur = "Maximilien-Alexandre"; cfg.lieu = "Résidence secondaire des Grands Chênes"; cfg.personne = "person.essai"
            cfg.urlExterne = ""; cfg.urlInterne = ""; cfg.parentJusqua = 0L
            Etat.derniereReponse = System.currentTimeMillis(); Etat.prochainChangement = System.currentTimeMillis() + 80 * 60_000L
            profil.edit().putString("temps", JSONObject().put("actif", 125).put("appareils", JSONArray()
                .put(JSONObject().put("nom", "Tablette du salon de Maximilien").put("actif", 95))
                .put(JSONObject().put("nom", "Téléphone portable personnel").put("actif", 30))).toString())
                .putLong("a", System.currentTimeMillis()).putStringSet("fonctions", setOf("gerer", "pause_generale")).apply()
            SetupActivity.resumeGestion = JSONObject().put("demandes", demandes).put("appareils", JSONArray().put(appareil)
                .put(JSONObject(appareil.toString()).put("id", "essai_2").put("verrouille", true)))
            SetupActivity.resumeA = System.currentTimeMillis() + 3600_000L
            bloc()
        } finally {
            vision.edit().clear().apply { sauveVision.forEach { (k, v) -> remettre(this, k, v) } }.commit()
            profil.edit().clear().apply { sauveProfil.forEach { (k, v) -> remettre(this, k, v) } }.commit()
            Etat.parent = parentAvant; Etat.prochainChangement = prochainAvant; Etat.derniereReponse = reponseAvant
            SetupActivity.resumeGestion = null; SetupActivity.resumeA = 0L
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun remettre(e: android.content.SharedPreferences.Editor, k: String, v: Any?) {
        when (v) {
            is String -> e.putString(k, v); is Boolean -> e.putBoolean(k, v); is Int -> e.putInt(k, v)
            is Long -> e.putLong(k, v); is Float -> e.putFloat(k, v); is Set<*> -> e.putStringSet(k, v as Set<String>)
        }
    }

    // ------------------------------------------------------------------ Parcours

    private fun parcourir(echelle: Float) {
        shell("settings put system font_scale $echelle")
        SystemClock.sleep(1500)
        for ((rotation, sens) in listOf(UiAutomation.ROTATION_FREEZE_0 to "portrait", UiAutomation.ROTATION_FREEZE_90 to "paysage")) {
            instrumentation.uiAutomation.setRotation(rotation)
            SystemClock.sleep(800)
            val suffixe = "${(echelle * 100).toInt()}_$sens"
            ecran("accueil_$suffixe", Intent(ctx, LanceurActivity::class.java))
            // Le tiroir de toutes les applis : les étiquettes d'icônes (exception à 2 lignes).
            ecran("accueil_tiroir_$suffixe", Intent(ctx, LanceurActivity::class.java), preparer = { a -> appeler(a, "tiroirApplis") })
            ecran("reglages_$suffixe", Intent(ctx, SetupActivity::class.java))
            // 1.71.0 : l'écran principal d'un appareil relié, décideur puis demandeur, tout déplié.
            avecAppareilRelie {
                for (decideur in listOf(true, false)) {
                    Etat.parent = decideur
                    ecran("principal_${if (decideur) "decideur" else "demandeur"}_$suffixe", Intent(ctx, SetupActivity::class.java), defiler = true, preparer = { a ->
                        champ(a, "tempsDeplie", true); champ(a, "gestionDepliee", true); champ(a, "autorisationsDepliees", true)
                        appeler(a, "dessiner")
                    })
                }
            }
            ecran("assistant_$suffixe", Intent(ctx, SetupActivity::class.java), preparer = { a ->
                champ(a, "assistant", true); appeler(a, "dessiner")
            })
            ecran("gestion_maison_$suffixe", Intent(ctx, ParentsActivity::class.java), defiler = true, preparer = { a ->
                // 1.73.0 : un appareil en concentration, l'autre avec le bouton ; les cartes dépliées.
                val enConcentration = JSONObject(appareil.toString()).put("id", "essai_conc").put("nom", "Ordinateur portable de Maximilien")
                    .put("concentration", JSONObject().put("fin", System.currentTimeMillis() / 1000 + 5400).put("apps", JSONArray().put("org.exemple").put("docs")).put("proposees", JSONArray()))
                val sansConcentration = JSONObject(appareil.toString()).put("concentration", JSONObject().put("fin", 0).put("apps", JSONArray())
                    .put("proposees", JSONArray().put(JSONObject().put("nom", "org.exemple").put("libelle", "Application de dessin extraordinaire"))))
                champ(a, "appareils", JSONArray().put(sansConcentration).put(enConcentration)); champ(a, "demandes", demandes)
                champ(a, "deplies", hashSetOf("p:autres"))
                champ(a, "categories", categories); champ(a, "erreur", ""); appeler(a, "dessiner")
            })
            ExceptionsActivity.fiche = appareil; ExceptionsActivity.cats = categories
            ecran("autorisations_$suffixe", Intent(ctx, ExceptionsActivity::class.java))
            CategoriesActivity.fiche = appareil; CategoriesActivity.cats = categories
            ecran("categories_$suffixe", Intent(ctx, CategoriesActivity::class.java))
            // L'écran de blocage ne reste affiché que si l'appareil est fermé : on le ferme le temps de la capture.
            val avant = Etat.verrouille
            Etat.verrouille = true
            try {
                ecran("blocage_$suffixe", Intent(ctx, BlockActivity::class.java)
                    .putExtra("raison", "Fermé par le planning du soir : l'écran se rallume demain à sept heures")
                    .putExtra("pkg", "com.android.settings"))
            } finally { Etat.verrouille = avant }
            ecran("reglages_tv_$suffixe", Intent(ctx, ReglagesTvActivity::class.java))
            ecran("couleurs_$suffixe", Intent(ctx, CouleursActivity::class.java))
            ecran("deverrouillage_$suffixe", Intent(ctx, DeverrouillageActivity::class.java))
            // Une boîte de Vision : titre long, liste, trois boutons.
            ecran("dialogue_$suffixe", Intent(ctx, SetupActivity::class.java), boite = { a ->
                Ui.dialogue(a).setTitle("Pendant la veille, ces applis continuent en fond")
                    .setItems(arrayOf("Jusqu'à la prochaine plage du planning (19:30)", "Autre durée…", "Toute la catégorie : jeux vidéo et divertissement")) { _, _ -> }
                    .setPositiveButton("Désinstaller l'ancienne", null).setNeutralButton("Continuer quand même", null)
                    .setNegativeButton("Annuler", null).show()
            })
        }
        val rapport = File(dossier, "problemes_${(echelle * 100).toInt()}.txt")
        rapport.writeText(problemes.joinToString("\n").ifEmpty { "Aucun texte coupé." })
        assertTrue("Textes coupés à font_scale $echelle :\n" + problemes.joinToString("\n"), problemes.isEmpty())
    }

    private fun ecran(nom: String, intent: Intent, preparer: ((Activity) -> Unit)? = null, boite: ((Activity) -> android.app.Dialog)? = null, defiler: Boolean = false) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val a = instrumentation.startActivitySync(intent)
        instrumentation.waitForIdleSync()
        SystemClock.sleep(1200)
        if (preparer != null) { instrumentation.runOnMainSync { preparer(a) }; instrumentation.waitForIdleSync(); SystemClock.sleep(600) }
        var d: android.app.Dialog? = null
        if (boite != null) { instrumentation.runOnMainSync { d = boite(a) }; instrumentation.waitForIdleSync(); SystemClock.sleep(800) }
        instrumentation.runOnMainSync {
            val racine = d?.window?.decorView ?: a.window.decorView
            verifier(racine, nom)
        }
        capturer(nom)
        // Une page longue : les écrans suivants, en faisant défiler, pour voir aussi le bas.
        if (defiler) for (page in 2..6) {
            var bouge = false
            instrumentation.runOnMainSync {
                val sv = trouverDefilement(a.window.decorView)
                if (sv != null) { val avant = sv.scrollY; sv.scrollBy(0, (sv.height * 0.85f).toInt()); bouge = sv.scrollY != avant }
            }
            if (!bouge) break
            instrumentation.waitForIdleSync(); SystemClock.sleep(400)
            capturer("${nom}_$page")
        }
        instrumentation.runOnMainSync { d?.dismiss(); a.finish() }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(400)
    }

    private fun trouverDefilement(v: View): android.widget.ScrollView? {
        if (v is android.widget.ScrollView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) trouverDefilement(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun capturer(nom: String) {
        val b: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(dossier, "$nom.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // ------------------------------------------------------------------ Vérification

    private fun dansDefilementHorizontal(v: View): Boolean {
        var p = v.parent
        while (p is View) { if (p is HorizontalScrollView) return true; p = p.parent }
        return false
    }

    /** Étiquette d'icône admise : au plus 2 lignes, nom complet sur la case (contentDescription). */
    private fun etiquetteIcone(tv: TextView): Boolean {
        if (tv.maxLines != 2) return false
        var p: Any? = tv.parent; var n = 0
        while (p is View && n < 3) { if (!p.contentDescription.isNullOrEmpty()) return true; p = p.parent; n++ }
        return false
    }

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
                    if (fin in 1 until t.length && t[fin - 1].isLetter() && t[fin].isLetter()) { problemes += "$nom : mot coupé en deux (ligne ${i + 1})"; break }
                }
            }
            val parent = v.parent as? ViewGroup
            if (parent != null && !dansDefilementHorizontal(v) && (v.left < -2 || v.right > parent.width + 2))
                problemes += "$nom : dépasse son parent (${v.left}..${v.right} sur ${parent.width} px)"
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) verifier(v.getChildAt(i), ecran)
    }

    // ------------------------------------------------------------------ Réflexion (états privés des écrans)

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
