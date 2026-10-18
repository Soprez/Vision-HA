// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 1.87.0 (exigence de l'auteur : « il faut faire attention à ces petites choses ») : la recette visuelle des écrans modifiés
 * par la livraison intermédiaire. Lancée une fois par configuration d'écran (téléphone 360 et 412 dp, tablette,
 * télé 720p et 1080p ; police 100 % et 130 %, réglées de l'extérieur par adb : wm size, wm density, font_scale).
 *
 * Échoue si un texte est tronqué sans ellipse (lignes cachées, hauteur trop courte, mot coupé en deux), s'il porte
 * des points de suspension (hors étiquettes d'icônes), si un élément (texte, bouton, image cliquable) est rogné par
 * l'écran ou par un parent hors d'une zone qui défile, ou s'il dépasse de son parent. Une capture par écran dans
 * files/captures/recette/<configuration>/ ; le rapport dans problemes.txt.
 */
class RecetteVisuelleTest : InstrumentationTestCase() {
    override fun setUp() {
        super.setUp()
        CodeEssai.poser(instrumentation.targetContext)  // faille du 16-10 : un Décideur a toujours un code
    }

    override fun tearDown() {
        CodeEssai.rendre(instrumentation.targetContext)
        super.tearDown()
    }

    private val ctx get() = instrumentation.targetContext
    private val problemes = ArrayList<String>()
    private val config: String by lazy {
        val c = ctx.resources.configuration
        val type = if (ReglagesTvActivity.estTele(ctx)) "tele" else if (c.smallestScreenWidthDp >= 600) "tablette" else "telephone"
        "${type}_${c.screenWidthDp}x${c.screenHeightDp}dp_police${(c.fontScale * 100).toInt()}"
    }
    private val dossier by lazy { File(ctx.getExternalFilesDir(null), "captures/recette/$config").apply { mkdirs() } }

    private fun shell(cmd: String) {
        val pfd = instrumentation.uiAutomation.executeShellCommand(cmd)
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }

    fun testRecette() = parcours()

    // Hors de la méthode « test… » : JUnit 3 prendrait les lambdas Kotlin pour des tests.
    private fun parcours() {
        shell("input keyevent KEYCODE_WAKEUP")
        val tele = ReglagesTvActivity.estTele(ctx)
        // Accueil (lot Accueil : désinstallation, préavis, composants) et tiroir.
        ecran("accueil", Intent(ctx, LanceurActivity::class.java))
        ecran("accueil_tiroir", Intent(ctx, LanceurActivity::class.java), preparer = { a -> appeler(a, "tiroirApplis") })
        // Écran de blocage (a34bb00 : marges, carte 560 dp centrée) : appareil fermé, puis appli fermée.
        avecBlocage {
            ecran("blocage_appareil", Intent(ctx, BlockActivity::class.java)
                .putExtra("raison", "Fermé par le planning du soir : l'écran se rallume demain à sept heures").putExtra("pkg", ""))
            Etat.verrouille = false; Etat.prochainChangement = 0
            val pkg = "com.android.chrome"
            Etat.apps = setOf(pkg); Etat.raisons = mapOf(pkg to "plage|Fermée en dehors des plages de l'agenda du soir")
            Usage.dernierPaquet = pkg
            ecran("blocage_appli", Intent(ctx, BlockActivity::class.java).putExtra("raison", "Cette appli est bloquée").putExtra("pkg", pkg))
        }
        // Réglages de l'appareil : chaque page (télé : chaque section du menu), sans puis avec le badge Soutien.
        for (soutien in listOf(false, true)) avecSoutien(soutien) {
            val badge = if (soutien) "_badge" else ""
            if (tele) {
                // Ouverture telle quelle (le menu trié : en anglais, « Deciders » vient en premier et prend le focus).
                ecran("reglages_tv_ouverture$badge", Intent(ctx, ReglagesTvActivity::class.java))
                for (cle in listOf("affichage", "veille", "accueil", "son", "parametres", "decideurs")) {
                    ecran("reglages_tv_$cle$badge", Intent(ctx, ReglagesTvActivity::class.java), preparer = { a ->
                        champ(a, "sectionTv", cle); champ(a, "sousPage", null); appeler(a, "majRail"); appeler(a, "construirePage")
                    })
                }
            } else {
                // 15-10 : la page est répartie ; les Réglages de l'appli (☕ en tête) et la page Musique.
                ecran("reglages_appareil$badge", EcransReglages.reglages(ctx), defiler = true)
                ecran("reglages_musique$badge", EcransReglages.hote(ctx), defiler = true)
            }
        }
        ecran("soutien_explication", EcransReglages.hote(ctx), boite = { a -> Soutien.expliquer(a, tele); null })
        // Écran principal (décideur, tout déplié), gestion du lieu : vocabulaire, accueil d'origine, composants thémés.
        avecAppareilRelie {
            Etat.parent = true
            ecran("principal_decideur", Intent(ctx, SetupActivity::class.java), defiler = true, preparer = { a ->
                champ(a, "tempsDeplie", true); champ(a, "gestionDepliee", true); champ(a, "autorisationsDepliees", true); appeler(a, "dessiner")
            })
            ecran("gestion_lieu", Intent(ctx, ParentsActivity::class.java), defiler = true)
        }
        // Messages (lot Accueil, point 10 : animés) : plein écran avec réponses, puis l'envoi.
        val demande = JSONObject().put("id", "m1").put("reponses", JSONArray().put("J'arrive").put("Dans 5 minutes").put("Je ne peux pas maintenant"))
        ecran("message_plein_ecran", MessageActivity.intention(ctx, "Message important de la part d'un Décideur",
            "À table dans dix minutes ! Range ta chambre avant, s'il te plaît, et éteins la console.", true, demande))
        ecran("message_envoi", Intent(ctx, MessageEnvoiActivity::class.java), defiler = true)
        // Une boîte de Vision (thème de l'appareil) : titre long, liste, trois boutons.
        ecran("dialogue", Intent(ctx, SetupActivity::class.java), boite = { a ->
            Ui.dialogue(a).setTitle("Pendant la veille, ces applis continuent en fond")
                .setItems(arrayOf("Jusqu'à la prochaine plage du planning (19:30)", "Autre durée…", "Toute la catégorie : jeux vidéo et divertissement")) { _, _ -> }
                .setPositiveButton("Désinstaller l'ancienne", null).setNeutralButton("Continuer quand même", null)
                .setNegativeButton("Annuler", null).show()
        })
        File(dossier, "problemes.txt").writeText(problemes.joinToString("\n").ifEmpty { "Aucun problème." })
        assertTrue("Recette $config :\n" + problemes.joinToString("\n"), problemes.isEmpty())
    }

    // ------------------------------------------------------------------ États d'essai

    private fun avecBlocage(bloc: () -> Unit) {
        val verrouAvant = Etat.verrouille; val msgAvant = Etat.message; val prochainAvant = Etat.prochainChangement
        val appsAvant = Etat.apps; val raisonsAvant = Etat.raisons; val paquetAvant = Usage.dernierPaquet
        try {
            Etat.verrouille = true; Etat.message = "Temps d'écran de la journée écoulé"
            Etat.prochainChangement = System.currentTimeMillis() + 10 * 3600_000L
            bloc()
        } finally {
            Etat.verrouille = verrouAvant; Etat.message = msgAvant; Etat.prochainChangement = prochainAvant
            Etat.apps = appsAvant; Etat.raisons = raisonsAvant; Usage.dernierPaquet = paquetAvant
        }
    }

    private fun avecSoutien(actif: Boolean, bloc: () -> Unit) {
        val avant = Etat.soutien
        Etat.soutien = if (actif) Soutien.Statut(true, "code") else Soutien.INACTIF
        try { bloc() } finally { Etat.soutien = avant }
    }

    private val appareil = JSONObject("""{
        "id":"essai_tablette","nom":"Tablette du salon de Maximilien","prenom":"Maximilien-Alexandre","role":"Demandeur",
        "en_ligne":true,"android":true,"verrouille":false,"partage_tous":false,"protections":{"accessibilite":false,"usage":true,"accueil":false},
        "apps":[{"libelle":"Jeux vidéo en ligne avec discussion"}],"sites":[{"nom":"exemple.fr"}],"plages":[],
        "exceptions":{"autorises":[],"bloques":[],"toujours":[]},
        "temps":{"actif":95,"ouvert":120},"usage":{"focus":"org.exemple","focus_libelle":"Application de dessin extraordinaire"}
    }""")

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
                .put(JSONObject().put("nom", "Tablette du salon de Maximilien").put("actif", 95))).toString())
                .putLong("a", System.currentTimeMillis()).putStringSet("fonctions", setOf("gerer", "pause_generale")).apply()
            SetupActivity.resumeGestion = JSONObject().put("demandes", JSONArray()).put("appareils", JSONArray().put(appareil))
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

    // ------------------------------------------------------------------ Parcours d'un écran

    private fun ecran(nom: String, intent: Intent, preparer: ((Activity) -> Unit)? = null, boite: ((Activity) -> Dialog?)? = null, defiler: Boolean = false) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val a = instrumentation.startActivitySync(intent)
        instrumentation.waitForIdleSync()
        SystemClock.sleep(1500)  // animations d'entrée finies (messages animés : 240 ms)
        if (preparer != null) { instrumentation.runOnMainSync { preparer(a) }; instrumentation.waitForIdleSync(); SystemClock.sleep(700) }
        var d: Dialog? = null
        var haut: View? = null
        if (boite != null) {
            instrumentation.runOnMainSync { d = boite(a) }
            instrumentation.waitForIdleSync(); SystemClock.sleep(900)
            if (d == null) instrumentation.runOnMainSync { haut = fenetreDuDessus(a) }
        }
        instrumentation.runOnMainSync {
            val racine = d?.window?.decorView ?: haut ?: a.window.decorView
            parcourir(racine, nom, Rect(0, 0, racine.rootView.width, racine.rootView.height))
            titresEnDouble(racine, nom)
        }
        capturer(nom)
        if (defiler) for (page in 2..6) {
            var bouge = false
            instrumentation.runOnMainSync {
                val sv = trouverDefilement(a.window.decorView)
                if (sv != null) { val avant = sv.scrollY; sv.scrollBy(0, (sv.height * 0.85f).toInt()); bouge = sv.scrollY != avant }
            }
            if (!bouge) break
            instrumentation.waitForIdleSync(); SystemClock.sleep(500)
            instrumentation.runOnMainSync { parcourir(a.window.decorView, "${nom}_$page", Rect(0, 0, a.window.decorView.width, a.window.decorView.height)) }
            capturer("${nom}_$page")
        }
        if (haut != null) shell("input keyevent KEYCODE_BACK")
        instrumentation.runOnMainSync { try { d?.dismiss() } catch (_: Exception) {}; a.finish() }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(400)
    }

    /** La fenêtre ouverte par-dessus l'écran (une boîte que la fonction appelée ne rend pas). */
    private fun fenetreDuDessus(a: Activity): View? = try {
        val wmg = Class.forName("android.view.WindowManagerGlobal")
        val inst = wmg.getMethod("getInstance").invoke(null)
        @Suppress("UNCHECKED_CAST")
        val vues = wmg.getDeclaredField("mViews").apply { isAccessible = true }.get(inst) as List<View>
        vues.lastOrNull { it.isShown && it !== a.window.decorView }
    } catch (_: Exception) { null }

    private fun trouverDefilement(v: View): ScrollView? {
        if (v is ScrollView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) trouverDefilement(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun capturer(nom: String) {
        val b: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(dossier, "$nom.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // ------------------------------------------------------------------ Vérification

    /** Un même titre (grand texte) affiché deux fois sur l'écran : un bloc construit en double. */
    private fun titresEnDouble(racine: View, nom: String) {
        val vus = HashMap<String, Int>()
        fun tous(v: View) {
            if (!v.isShown) return
            if (v is TextView && v.textSize / v.resources.displayMetrics.scaledDensity >= 20f && v.text.length >= 6) vus.merge(v.text.toString(), 1, Int::plus)
            if (v is ViewGroup) for (i in 0 until v.childCount) tous(v.getChildAt(i))
        }
        tous(racine)
        vus.filterValues { it > 1 }.keys.forEach { problemes += "$nom · « $it » : titre affiché ${vus[it]} fois" }
    }

    private fun dansRangeeLaterale(v: View): Boolean {
        var p = v.parent
        while (p is View) {
            if (p is HorizontalScrollView) return p.javaClass == HorizontalScrollView::class.java
            if (p is ScrollView) return false
            p = p.parent
        }
        return false
    }

    private fun dansDefilement(v: View): Boolean {
        var p = v.parent
        while (p is View) {
            if (p is ScrollView || p is HorizontalScrollView || p is AbsListView || p.javaClass.name.contains("RecyclerView") ||
                p.javaClass.name.contains("NestedScrollView")) return true
            p = p.parent
        }
        return false
    }

    private fun etiquetteIcone(tv: TextView): Boolean {
        if (tv.maxLines != 2) return false
        var p: Any? = tv.parent; var n = 0
        while (p is View && n < 3) { if (!p.contentDescription.isNullOrEmpty()) return true; p = p.parent; n++ }
        return false
    }

    /** « … » admis quand le texte entier reste dit (description du texte ou d'un parent proche) : noms venus de HA. */
    private fun libelle(v: View): String = when {
        v is TextView && v.text.isNotEmpty() -> "« ${v.text.toString().replace('\n', ' ').take(50)} »"
        !v.contentDescription.isNullOrEmpty() -> "[${v.contentDescription.toString().take(50)}]"
        else -> v.javaClass.simpleName
    }

    private fun parcourir(v: View, ecran: String, ecranRect: Rect) {
        if (!v.isShown || v.width <= 0 || v.height <= 0) return
        val nom = "$ecran · ${libelle(v)}"
        if (v is TextView && v !is EditText && v.text.isNotEmpty()) {
            val l = v.layout
            if (l != null) {
                val coupe = (0 until l.lineCount).any { l.getEllipsisCount(it) > 0 }
                if (coupe && !etiquetteIcone(v) && !texteEntierDit(v)) problemes += "$nom : points de suspension (texte entier introuvable)"
                // Un texte court (une heure, un nombre) ne passe jamais sur deux lignes (« 12:06 » puis « PM »).
                if (l.lineCount > 1 && v.text.length <= 10 && v.text.any { it.isDigit() }) problemes += "$nom : texte court sur ${l.lineCount} lignes"
                val visibles = minOf(l.lineCount, v.maxLines.takeIf { it > 0 } ?: Int.MAX_VALUE)
                val utile = v.height - v.totalPaddingTop - v.totalPaddingBottom
                if (!coupe && l.getLineBottom(visibles - 1) > utile + 2) problemes += "$nom : tronqué (hauteur ${l.getLineBottom(visibles - 1)} > $utile px)"
                if (visibles < l.lineCount && !coupe && !etiquetteIcone(v)) problemes += "$nom : tronqué sans ellipse (${l.lineCount} lignes, ${v.maxLines} montrées)"
                val t = v.text
                for (i in 0 until l.lineCount - 1) {
                    val fin = l.getLineEnd(i)
                    if (fin in 1 until t.length && t[fin - 1].isLetter() && t[fin].isLetter()) { problemes += "$nom : mot coupé en deux (ligne ${i + 1})"; break }
                }
            }
        }
        // Rogné : un texte, un bouton ou un élément cliquable doit être visible en entier (sauf dans une zone qui défile).
        val porteur = v is TextView || v.isClickable || v.isFocusable && v !is ViewGroup
        // Un bouton coupé au bord d'une rangée qui défile de côté (hors pages et carrousels de cartes) : à faire passer à la ligne.
        if (v is TextView && v.isClickable && dansRangeeLaterale(v)) {
            val r = Rect()
            if (v.getGlobalVisibleRect(r) && r.width() in 1 until v.width - 3) problemes += "$nom : coupé au bord d'une rangée qui défile (visible ${r.width()} sur ${v.width} px)"
        }
        if (porteur && !dansDefilement(v)) {
            val r = Rect()
            val vu = v.getGlobalVisibleRect(r)
            if (!vu) problemes += "$nom : hors de l'écran"
            else if (r.width() < v.width - 3 || r.height() < v.height - 3) problemes += "$nom : rogné (visible ${r.width()}×${r.height()} sur ${v.width}×${v.height} px)"
            else if (!ecranRect.contains(r)) problemes += "$nom : déborde de l'écran"
        }
        val parent = v.parent as? ViewGroup
        if (v is TextView && parent != null && !dansDefilement(v) && (v.left < -2 || v.right > parent.width + 2))
            problemes += "$nom : dépasse son parent (${v.left}..${v.right} sur ${parent.width} px)"
        if (v is ViewGroup) for (i in 0 until v.childCount) parcourir(v.getChildAt(i), ecran, ecranRect)
    }

    // ------------------------------------------------------------------ Réflexion

    private fun champ(o: Any, nom: String, valeur: Any?) {
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
