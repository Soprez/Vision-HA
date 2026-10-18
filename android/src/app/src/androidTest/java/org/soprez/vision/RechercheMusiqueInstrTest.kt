// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.ServerSocket
import java.net.Socket

/**
 * Retours de l'auteur (15-10) : la recherche de musique à l'écran, contre un faux Home Assistant local (rien ne part au
 * vrai, rien ne joue).
 * - Les résultats arrivent pendant qu'on tape, une seule requête après la pause (anti-rebond) ; groupés par type.
 * - Une requête devenue obsolète ne s'affiche jamais (la dernière saisie gagne).
 * - La source est une icône (plus de texte « YouTube Music ») ; « Filtre » remplace les rangées de pastilles.
 * - Télé : le clavier de Vision tape dans le champ. Paramètres : la section Musique, ouverte directement.
 * Les captures (dossier captures de l'appli) servent à la recette visuelle.
 *
 *   adb shell am instrument -w -e class org.soprez.vision.RechercheMusiqueInstrTest org.soprez.vision.test/android.test.InstrumentationTestRunner
 */
class RechercheMusiqueInstrTest : InstrumentationTestCase() {
    private val ctx get() = instrumentation.targetContext
    private val dossier by lazy { File(ctx.getExternalFilesDir(null), "captures").apply { mkdirs() } }
    private lateinit var serveur: ServerSocket
    private val recus = ArrayList<JSONObject>()
    private val sauves = HashMap<String, Map<String, *>>()
    private var suffixe = ""

    private fun element(nom: String, type: String, uri: String, artiste: String = "Daft Punk") =
        JSONObject().put("nom", nom).put("type", type).put("uri", uri).put("artiste", artiste).put("image", "")
            .put("source", if (uri.startsWith("library")) "" else "YouTube Music").put("domaine", if (uri.startsWith("library")) "library" else "ytmusic")

    private fun reponse(corps: JSONObject): JSONObject {
        val texte = corps.optString("texte")
        return when (corps.optString("quoi")) {
            "suggestions" -> JSONObject().put("ok", true).put("suggestions", JSONArray().put("Daft Punk").put("Angèle"))
            "chercher" -> {
                if (texte.startsWith("lent")) SystemClock.sleep(2500)
                if (texte.startsWith("rien")) return JSONObject().put("ok", true).put("resultats", JSONObject()).put("conseil", "")
                if (texte.startsWith("panne")) return JSONObject().put("ok", false).put("error", "musique").put("retour", "Music Assistant ne répond pas : réessaie dans un instant.").put("_code", 503)
                val pistes = JSONArray()
                for (i in 1..6) pistes.put(element("$texte titre $i", "track", if (i % 2 == 0) "library://track/$i" else "ytmusic--x://track/$i"))
                JSONObject().put("ok", true).put("resultats", JSONObject()
                    .put("track", pistes)
                    .put("artist", JSONArray().put(element("Daft Punk", "artist", "ytmusic--x://artist/1")))
                    .put("album", JSONArray().put(element("Discovery", "album", "ytmusic--x://album/1"))))
                    .put("plateformes", JSONArray().put(JSONObject().put("domaine", "library").put("nom", "Bibliothèque")).put(JSONObject().put("domaine", "ytmusic").put("nom", "YouTube Music")))
            }
            else -> JSONObject().put("ok", true).put("items", JSONArray().put(element("Soirée", "playlist", "library://playlist/1")))
        }
    }

    private fun servir(s: Socket): Unit = s.use {
        val entree = s.getInputStream().bufferedReader(Charsets.UTF_8)
        val premiere = entree.readLine() ?: return
        var longueur = 0
        while (true) { val l = entree.readLine() ?: break; if (l.isEmpty()) break; if (l.lowercase().startsWith("content-length:")) longueur = l.substringAfter(':').trim().toInt() }
        val buf = CharArray(longueur); var lu = 0
        while (lu < longueur && (lu == 0 || entree.ready())) { val n = entree.read(buf, lu, longueur - lu); if (n < 0) break; lu += n }
        val corps = try { JSONObject(String(buf, 0, lu)) } catch (_: Exception) { JSONObject() }
        val chemin = premiere.split(" ").getOrElse(1) { "" }
        val sortie = s.getOutputStream()
        if (!chemin.endsWith("/party/musique")) {
            sortie.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); sortie.flush(); return
        }
        synchronized(recus) { recus.add(corps) }
        val rep = reponse(corps)
        val code = rep.optInt("_code", 200)
        val o = rep.toString().toByteArray()
        try {
            sortie.write("HTTP/1.1 $code OK\r\nContent-Type: application/json\r\nContent-Length: ${o.size}\r\nConnection: close\r\n\r\n".toByteArray())
            sortie.write(o); sortie.flush()
        } catch (_: Exception) {}  // requête coupée par l'appli (obsolète)
    }

    override fun setUp() {
        super.setUp()
        serveur = ServerSocket(0, 20, java.net.InetAddress.getByName("127.0.0.1"))
        Thread {
            while (!serveur.isClosed) {
                val s = try { serveur.accept() } catch (_: Exception) { break }
                Thread { try { servir(s) } catch (_: Exception) {} }.start()
            }
        }.apply { isDaemon = true }.start()
        for (n in listOf("vision", "musique_recherche", "party")) sauves[n] = HashMap(ctx.getSharedPreferences(n, 0).all)
        ctx.getSharedPreferences("musique_recherche", 0).edit().clear().commit()
        ctx.getSharedPreferences("party", 0).edit().remove("filtre_types").remove("filtre_plateformes").commit()
        val cfg = Config(ctx)
        cfg.id = "essai_musique"; cfg.secret = "s".repeat(32)
        val adresse = "http://127.0.0.1:${serveur.localPort}"
        cfg.urlInterne = adresse; cfg.urlExterne = ""; cfg.urlActive = adresse
        val dm = ctx.resources.displayMetrics
        suffixe = "${(dm.widthPixels / dm.density).toInt()}dp_police${ctx.resources.configuration.fontScale}"
    }

    override fun tearDown() {
        try { serveur.close() } catch (_: Exception) {}
        for ((n, v) in sauves) ctx.getSharedPreferences(n, 0).edit().clear().apply {
            v.forEach { (k, x) -> when (x) { is String -> putString(k, x); is Boolean -> putBoolean(k, x); is Int -> putInt(k, x); is Long -> putLong(k, x); is Float -> putFloat(k, x); is Set<*> -> @Suppress("UNCHECKED_CAST") putStringSet(k, x as Set<String>) } }
        }.commit()
        super.tearDown()
    }

    private fun capturer(nom: String) {
        SystemClock.sleep(400)
        val b = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(dossier, "${nom}_$suffixe.png").outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun vues(v: View, sortie: ArrayList<View> = ArrayList()): ArrayList<View> {
        sortie.add(v); if (v is ViewGroup) for (i in 0 until v.childCount) vues(v.getChildAt(i), sortie)
        return sortie
    }

    private fun attendre(quoi: String, ms: Long = 8000, cond: () -> Boolean) {
        val fin = SystemClock.elapsedRealtime() + ms
        while (SystemClock.elapsedRealtime() < fin) { if (cond()) return; SystemClock.sleep(100) }
        throw AssertionError("délai dépassé : $quoi — reçu : " + synchronized(recus) { recus.map { it.toString() } })
    }

    private fun ouvrir(): Activity = instrumentation.startActivitySync(Intent(ctx, ChoixMusiqueActivity::class.java)
        .putExtra("lecteur", "").putExtra("lecteur_nom", "Banc").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private fun textes(a: Activity): String { var t = ""; instrumentation.runOnMainSync { t = vues(a.window.decorView).filterIsInstance<TextView>().filter { it.isShown }.joinToString(" | ") { it.text } }; return t }
    private fun champ(a: Activity): EditText { var e: EditText? = null; instrumentation.runOnMainSync { e = vues(a.window.decorView).filterIsInstance<EditText>().first() }; return e!! }

    /** Tape lettre par lettre, comme quelqu'un (60 ms entre deux frappes, sous l'anti-rebond). */
    private fun taper(a: Activity, t: String) {
        val e = champ(a)
        for (c in t) { instrumentation.runOnMainSync { e.append(c.toString()) }; SystemClock.sleep(60) }
    }

    private fun recherches() = synchronized(recus) { recus.filter { it.optString("quoi") == "chercher" }.map { it.optString("texte") } }

    fun testResultatsPendantQuOnTape() {
        val a = ouvrir()
        attendre("playlists") { "Soirée" in textes(a) }
        assertTrue("bouton Filtre", ctx.getString(R.string.musrech_filtre) in textes(a))
        capturer("musique_ouverture")
        taper(a, "daft")
        attendre("résultats") { "daft titre 1" in textes(a) }
        // Une seule requête, pour le texte entier (anti-rebond) ; rien pour « d », « da », « daf ».
        assertEquals(listOf("daft"), recherches())
        val t = textes(a)
        assertTrue(t, Party.nomSection("track") in t)
        // Aperçu par groupe (4 titres sur 6) et « Tout voir ».
        assertFalse(t, "daft titre 5" in t)
        assertTrue(t, ctx.getString(R.string.musrech_tout_voir) in t)
        // Plus de nom de source écrit : une icône, décrite pour les lecteurs d'écran.
        assertFalse("texte « YouTube Music » visible", "YouTube Music" in t)
        var icones = 0
        instrumentation.runOnMainSync { icones = vues(a.window.decorView).filterIsInstance<ImageView>().count { it.drawable is IconeSource && it.isShown } }
        assertTrue("icônes de source : $icones", icones >= 4)
        capturer("musique_resultats")
        // Effacer une lettre puis la remettre : la mémoire répond, sans nouvelle requête pour « daft ».
        champ(a).let { e -> instrumentation.runOnMainSync { e.text.delete(3, 4) } }
        attendre("daf") { "daf titre 1" in textes(a) }
        taper(a, "t")
        attendre("daft revenu") { "daft titre 1" in textes(a) }
        assertEquals(listOf("daft", "daf"), recherches())
        a.finish()
    }

    fun testLaDerniereSaisieGagne() {
        val a = ouvrir()
        attendre("playlists") { "Soirée" in textes(a) }
        taper(a, "lent")             // le faux HA met 2,5 s à répondre à « lent »
        attendre("requête « lent » partie") { "lent" in recherches() }
        taper(a, "e")                // « lente » : elle aussi lente
        SystemClock.sleep(200)
        champ(a).let { e -> instrumentation.runOnMainSync { e.setText("rapide") } }
        attendre("résultats de « rapide »") { "rapide titre 1" in textes(a) }
        SystemClock.sleep(3500)      // les réponses de « lent » et « lente » arrivent… et sont jetées
        val t = textes(a)
        assertTrue(t, "rapide titre 1" in t)
        assertFalse(t, "lent titre 1" in t || "lente titre 1" in t)
        a.finish()
    }

    fun testFiltreEtSuggestions() {
        val a = ouvrir()
        attendre("suggestions") { "Angèle" in textes(a) }
        instrumentation.runOnMainSync { vues(a.window.decorView).filterIsInstance<TextView>().first { it.text == "Angèle" && it.isShown }.performClick() }
        attendre("recherche suggérée") { "Angèle titre 1" in textes(a) }
        assertEquals("Angèle", recherches().last())
        // Le panneau Filtre s'ouvre ; ses trois lignes sont là.
        instrumentation.runOnMainSync { vues(a.window.decorView).filterIsInstance<TextView>().first { it.text.startsWith(ctx.getString(R.string.musrech_filtre)) && it.isShown }.performClick() }
        SystemClock.sleep(600)
        capturer("musique_filtre")
        instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        SystemClock.sleep(300)
        a.finish()
    }

    fun testClavierDeLaTele() {
        if (!ReglagesTvActivity.estTele(ctx)) return
        val a = ouvrir()
        attendre("clavier") { ctx.getString(R.string.musrech_espace) in textes(a) }
        for (l in listOf("D", "A", "F", "T")) instrumentation.runOnMainSync { vues(a.window.decorView).filterIsInstance<TextView>().first { it.text == l && it.isShown }.performClick() }
        assertEquals("daft", champ(a).text.toString())
        attendre("résultats") { "daft titre 1" in textes(a) }
        capturer("musique_tele_clavier")
        a.finish()
    }

    /** Campagne Musique (15-10) : aucun résultat, Music Assistant en panne puis de retour, champ entièrement effacé. */
    fun testCampagneRechercheSansResultatPanneEtEffacement() {
        val a = ouvrir()
        attendre("playlists") { "Soirée" in textes(a) }
        champ(a).let { e -> instrumentation.runOnMainSync { e.setText("rien du tout") } }
        attendre("aucun résultat") { ctx.getString(R.string.choixmusique_rien_trouve, "rien du tout") in textes(a) }
        champ(a).let { e -> instrumentation.runOnMainSync { e.setText("panne totale") } }
        attendre("Music Assistant en panne") { "Music Assistant ne répond pas" in textes(a) }
        capturer("campagne_recherche_panne")
        // Music Assistant revient : la recherche suivante répond.
        champ(a).let { e -> instrumentation.runOnMainSync { e.setText("daft") } }
        attendre("résultats après la panne") { "daft titre 1" in textes(a) && "Music Assistant ne répond pas" !in textes(a) }
        // Champ vidé d'un coup : retour aux playlists, plus aucune requête de recherche.
        val avant = recherches().size
        champ(a).let { e -> instrumentation.runOnMainSync { e.setText("") } }
        attendre("playlists revenues") { "Soirée" in textes(a) && "daft titre 1" !in textes(a) }
        SystemClock.sleep(600)
        assertEquals("une recherche est partie pour un champ vide", avant, recherches().size)
        a.finish()
    }

    private fun shell(cmd: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(cmd)).use { String(it.readBytes()) }.trim()

    /**
     * Retour de la session Réveil (15-10) : en allemand, à la télé, « Als Nächstes spielen » et « ⋯ » sortaient de
     * l'écran. Dans les six langues, la musique jouant déjà (trois actions par résultat) : aucun bouton ni titre ne
     * sort de sa carte ni de l'écran, et le titre garde de la place.
     */
    fun testAucunDebordementToutesLangues() {
        val avant = shell("cmd locale get-app-locales ${ctx.packageName} --user 0").substringAfter("are [").substringBefore("]")
        val defauts = ArrayList<String>()
        try {
            for (lg in listOf("fr-FR", "en-US", "de-DE", "es-ES", "it-IT", "nl-NL")) {
                shell("cmd locale set-app-locales ${ctx.packageName} --user 0 --locales $lg"); SystemClock.sleep(800)
                val a = instrumentation.startActivitySync(Intent(ctx, ChoixMusiqueActivity::class.java)
                    .putExtra("lecteur", "").putExtra("lecteur_nom", "Banc").putExtra("joue", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                attendre("playlists $lg") { "Soirée" in textes(a) }
                champ(a).let { e -> instrumentation.runOnMainSync { e.setText("daft") } }
                attendre("résultats $lg") { "daft titre 1" in textes(a) }
                SystemClock.sleep(700)
                instrumentation.runOnMainSync {
                    val ecran = a.window.decorView.width
                    for (carte in vues(a.window.decorView).filter { it.tag == "carte" && it.isShown }) {
                        val rc = IntArray(2); carte.getLocationOnScreen(rc)
                        for (v in vues(carte).filter { (it.isClickable || it is TextView) && it.isShown && it !== carte && it.width > 0 }) {
                            val r = IntArray(2); v.getLocationOnScreen(r)
                            val nom = (v as? TextView)?.text?.toString()?.take(24) ?: v.contentDescription?.toString()?.take(24) ?: v.javaClass.simpleName
                            if (r[0] + v.width > rc[0] + carte.width + 1 || r[0] + v.width > ecran) defauts.add("$lg : « $nom » sort (${r[0] + v.width} > ${minOf(rc[0] + carte.width, ecran)})")
                            if (v is TextView && v.layout != null && v.maxLines == 1 && v.layout.getEllipsisCount(0) > 0 && v.isClickable) defauts.add("$lg : bouton « $nom » coupé")
                        }
                        val titre = vues(carte).filterIsInstance<TextView>().firstOrNull { it.text.startsWith("daft titre") }
                        if (titre != null && titre.width < carte.width * 0.25f) defauts.add("$lg : titre trop étroit (${titre.width} / ${carte.width})")
                    }
                }
                capturer("recherche_${lg.substringBefore('-')}")
                a.finish(); instrumentation.waitForIdleSync()
            }
        } finally {
            shell(if (avant.isEmpty()) "cmd locale set-app-locales ${ctx.packageName} --user 0 --locales \"\"" else "cmd locale set-app-locales ${ctx.packageName} --user 0 --locales $avant")
        }
        assertTrue(defauts.distinct().joinToString("\n"), defauts.isEmpty())
    }

    fun testSectionMusiqueDesParametres() {
        val a = instrumentation.startActivitySync(Intent(ctx, ReglagesTvActivity::class.java).putExtra(ReglagesTvActivity.EXTRA_SECTION, ReglagesMusique.SECTION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        attendre("section Musique") { ctx.getString(R.string.musrech_clavier) in textes(a) }
        // Réponse de l'auteur (15-10) : sur la télé, le moteur du lecteur est ici, derrière le code des Décideurs.
        if (ReglagesTvActivity.estTele(ctx)) assertTrue(textes(a), ctx.getString(R.string.reglagestv_lecteur_musique) in textes(a))
        capturer("parametres_musique")
        // La musique de veille n'est pas dans la section Musique (sur tablette, la page entière montre aussi Veille).
        var dansMusique = emptyList<String>()
        instrumentation.runOnMainSync {
            val tete = vues(a.window.decorView).firstOrNull { it.tag == ReglagesMusique.SECTION }
            val parent = tete?.parent as? ViewGroup
            if (parent != null) {
                val apres = (parent.indexOfChild(tete) + 1 until parent.childCount).map { parent.getChildAt(it) }
                dansMusique = apres.takeWhile { (it.tag as? String)?.startsWith("s_") != true }.mapNotNull { it.tag as? String }
            }
        }
        assertFalse("musique de veille dans Musique : $dansMusique", "son" in dansMusique || "veille_playlist" in dansMusique)
        a.finish()
    }

    /** Confirmation de l'auteur (15-10) : la musique de veille (son, playlist) est dans la section Veille (télé). */
    fun testMusiqueDeVeilleDansVeille() {
        if (!ReglagesTvActivity.estTele(ctx) || !Modules.actif(ctx, Modules.VEILLE)) return
        val a = instrumentation.startActivitySync(Intent(ctx, ReglagesTvActivity::class.java).putExtra(ReglagesTvActivity.EXTRA_SECTION, "s_veille").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        attendre("musique de veille") { ctx.getString(R.string.reglagestv_son_veille) in textes(a) && ctx.getString(R.string.veilleaudio_playlist) in textes(a) }
        capturer("parametres_veille")
        a.finish()
    }
}
