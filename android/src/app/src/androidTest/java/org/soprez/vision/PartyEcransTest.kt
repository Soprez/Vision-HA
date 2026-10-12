// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket

/**
 * 1.81.0 (point 14 bis) : le Mode Party et le widget Lecteur, à l'écran. Un petit serveur local joue Home Assistant
 * (pc_parental/party.py). Téléphone : pilotage d'un décideur, « En cours… » au clic puis le retour du serveur,
 * l'interrupteur Mode Fête envoyé au lancement. Télé : le plein écran. Lecteur : les touches envoient la bonne action.
 * Captures dans /sdcard/Android/data/org.soprez.vision/files/captures.
 *
 *   adb shell am instrument -w -e class org.soprez.vision.PartyEcransTest org.soprez.vision.test/android.test.InstrumentationTestRunner
 */
class PartyEcransTest : InstrumentationTestCase() {
    private val ctx get() = instrumentation.targetContext
    private val dossier by lazy { File(ctx.getExternalFilesDir(null), "captures").apply { mkdirs() } }
    private lateinit var serveur: ServerSocket
    /** 1.87.1 : les pochettes demandées à Home Assistant (référence donnée par lui ; jamais l'adresse de Music Assistant). */
    /** Propre à chaque essai (le cache des vignettes de l'appli dure tout le passage) : une vraie demande à chaque fois. */
    private fun refDisney() = "ref-disney-${serveur.localPort}"
    private val imagesDemandees = java.util.Collections.synchronizedList(ArrayList<JSONObject>())
    @Volatile private var commandes = ArrayList<JSONObject>()
    @Volatile private var actif = false
    @Volatile private var rienNeJoue = false
    @Volatile private var finClin = -1
    /** 1.86.7 : l'état du faux lecteur, et le temps qu'il met à suivre une commande (enceinte lente). */
    @Volatile private var etatLecteur = "playing"
    @Volatile private var delaiLecteurMs = 0L
    @Volatile private var nomEnceinte = "Salon"
    /** 1.86.8 : un nombre de titres périmé (Music Assistant qui n'a pas encore compté), et un retrait refusé. */
    @Volatile private var totalFige: Int? = null
    @Volatile private var refuserRetrait = false
    @Volatile private var ici = true
    // 1.85.0 : Party ouverte à tous — un appareil qui n'est pas décideur, participant ou fermé.
    @Volatile private var piloterForce: Boolean? = null
    @Volatile private var participer = true
    @Volatile private var ferme = ""
    private val sauves = HashMap<String, Map<String, *>>()
    /** 1.86.2 : la file du faux serveur, modifiée par les commandes comme le ferait Music Assistant. */
    private val fileServeur = ArrayList<JSONObject>()
    private fun fileInitiale(n: Int = 4) {
        synchronized(fileServeur) {
            fileServeur.clear()
            val noms = listOf("Titre suivant", "Encore après", "Troisième titre", "Quatrième titre", "Cinquième titre", "Sixième titre", "Septième titre", "Huitième titre", "Neuvième titre")
            for (k in 0 until n) fileServeur.add(JSONObject().put("titre", noms[k]).put("artiste", if (k == 1) "" else "Artiste ${k + 2}").put("id", "q${k + 1}").put("invite", k == 1))
        }
    }
    private fun idsServeur(): List<String> = synchronized(fileServeur) { fileServeur.map { it.optString("id") } }
    private fun appliquer(c: JSONObject) = synchronized(fileServeur) {
        val i = fileServeur.indexOfFirst { it.optString("id") == c.optString("element") }
        if (i < 0) return@synchronized
        when (c.optString("action")) {
            "retirer" -> fileServeur.removeAt(i)
            "deplacer" -> { val x = fileServeur.removeAt(i); fileServeur.add((i + c.optInt("decalage")).coerceIn(0, fileServeur.size), x) }
            "ensuite_element" -> fileServeur.add(0, fileServeur.removeAt(i))
            "a_la_fin" -> fileServeur.add(fileServeur.removeAt(i))
        }
    }

    private fun etat(): JSONObject = JSONObject()
        .put("ok", true).put("actif", actif).put("ecran", if (actif) "t1" else "").put("ecran_nom", if (actif) "Téléviseur du salon" else "")
        .put("ici", actif && ici).put("lecteur", "media_player.salon").put("lecteur_nom", nomEnceinte)
        .put("ma", true).put("lecteur_ma", true).put("choisir", true).put("invites_mode", "direct")
        .put("qr", if (actif) "https://ha.exemple.org/api/pc_parental/party/invite/JETON_ESSAI_0123456789abcdef" else "")
        .put("propositions", JSONArray().put(JSONObject().put("id", "p1").put("titre", "Proposition").put("artiste", "Invité").put("ts", 1)))
        .put("maintenant", if (rienNeJoue) JSONObject().put("etat", "idle") else JSONObject().put("etat", etatLecteur).put("titre", "Le titre qui passe").put("artiste", "Un artiste")
            .put("album", "Un album").put("volume", 0.4).put("duree", 221).put("position", 84).put("position_ts", System.currentTimeMillis() / 1000.0)
            .put("pochette", "ab12"))
        .put("file", synchronized(fileServeur) { JSONArray().apply { fileServeur.forEach { put(it) } } })
        .put("file_total", totalFige ?: synchronized(fileServeur) { fileServeur.size + 140 }).put("similaires", false).put("fin_clin", finClin)
        .put("enchainement", JSONObject().put("flux", true).put("fondu", false).put("duree", 8).put("precharge", true))
        .put("piloter", piloterForce ?: !ReglagesTvActivity.estTele(ctx)).put("relire", 30)
        .put("participer", participer).put("ferme", ferme)
        .put("ecrans", JSONArray().put(JSONObject().put("id", "t1").put("nom", "Téléviseur du salon")).put(JSONObject().put("id", "t2").put("nom", "Télé des parents")))
        .put("lecteurs", JSONArray().put(JSONObject().put("entity_id", "media_player.salon").put("nom", "Salon").put("etat", "playing").put("groupe", false).put("ma", true))
            .put(JSONObject().put("entity_id", "media_player.maison").put("nom", "Toute la maison").put("etat", "idle").put("groupe", true))
            .put(JSONObject().put("entity_id", "media_player.cle_mi").put("nom", "Clé TV Mi").put("etat", "unavailable").put("groupe", false).put("ma", true)
                .put("dispo", false).put("raison", "Hors ligne : allume-la ou vérifie qu'elle est branchée.")))
        .put("aide_lecteurs", "Une enceinte ou une télé manque ? Allume-la, puis dans Music Assistant : Réglages → Fournisseurs de lecteurs.")


    private fun pochette(): ByteArray {
        val b = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        for (x in 0 until 64) for (y in 0 until 64) b.setPixel(x, y, Color.rgb(200 - x * 2, 60 + y, 120 + x))
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun servir(s: Socket): Unit = s.use {
        val entree = s.getInputStream().bufferedReader(Charsets.UTF_8)
        val premiere = entree.readLine() ?: return
        var longueur = 0
        while (true) { val l = entree.readLine() ?: break; if (l.isEmpty()) break; if (l.lowercase().startsWith("content-length:")) longueur = l.substringAfter(':').trim().toInt() }
        val buf = CharArray(longueur); var lu = 0
        while (lu < longueur) { val n = entree.read(buf, lu, longueur - lu); if (n < 0) break; lu += n }
        val corps = try { JSONObject(String(buf, 0, lu)) } catch (_: Exception) { JSONObject() }
        val (type, octets) = when {
            premiere.contains("/party/pochette") -> "image/png" to pochette()
            premiere.contains("/party/image") -> { imagesDemandees.add(corps); "image/png" to (if (corps.optString("ref") == refDisney()) pochette() else ByteArray(0)) }
            premiere.contains("/party/commande") -> {
                synchronized(commandes) { commandes.add(corps) }
                // 1.86.13 : une grosse playlist ajoutée à la suite demande confirmation (comme Home Assistant au-delà de 50).
                if (corps.optString("action") == "jouer" && corps.optString("type") == "playlist" && corps.optString("comment") in setOf("ajouter", "ensuite") && !corps.optBoolean("confirme")) {
                    val o = JSONObject().put("ok", false).put("confirmer", true).put("nb", 500).put("retour", "Ajouter 500 titres à la suite ?").toString().toByteArray()
                    val crlf = "\r\n"
                    s.getOutputStream().apply { write(("HTTP/1.1 409 Conflict" + crlf + "Content-Type: application/json" + crlf + "Content-Length: ${o.size}" + crlf + "Connection: close" + crlf + crlf).toByteArray()); write(o); flush() }
                    return@use
                }
                if (refuserRetrait && corps.optString("action") == "retirer") {
                    val o = JSONObject().put("ok", false).put("retour", "Ce titre n'a pas pu être retiré : réessaie.").toString().toByteArray()
                    val crlf = "\r\n"
                    s.getOutputStream().apply { write(("HTTP/1.1 400 Bad Request" + crlf + "Content-Type: application/json" + crlf + "Content-Length: ${o.size}" + crlf + "Connection: close" + crlf + crlf).toByteArray()); write(o); flush() }
                    return@use
                }
                appliquer(corps)
                if (corps.optString("action") == "lecture") {
                    val cible = if (corps.optString("voulu") == "pause") "paused" else "playing"
                    Thread { SystemClock.sleep(delaiLecteurMs); etatLecteur = cible }.start()
                }
                SystemClock.sleep(700)  // le serveur réfléchit : « En cours… » doit tenir
                if (corps.optString("action") == "lancer" || corps.optString("action") == "lancer_ici") actif = true
                if (corps.optString("action") == "arreter") actif = false
                if (corps.optString("action") == "jouer") rienNeJoue = false
                val retour = when { corps.optString("action") == "lancer" -> "Musique lancée."; corps.optString("comment") == "ajouter" -> "Ajouté — position 4."
                    corps.optString("comment") == "ensuite" -> "Ajouté — joue juste après."; else -> "" }
                "application/json" to JSONObject().put("ok", true).put("retour", retour).toString().toByteArray()
            }
            premiere.contains("/party/etat") -> "application/json" to etat().toString().toByteArray()
            // Le relevé de l'appli : comme Home Assistant, il dit que la tuile Musique est là (sinon elle s'efface).
            premiere.contains("/api/pc_parental/poll") -> "application/json" to JSONObject().put("party", JSONObject().put("tuile", true)).toString().toByteArray()
            premiere.contains("/party/musique") && corps.optString("texte") == "jenifer" -> "application/json" to JSONObject().put("ok", true).put("resultats", JSONObject().put("track", JSONArray()
                .put(JSONObject().put("uri", "library://track/180").put("nom", "L'air du vent").put("type", "track").put("artiste", "Jenifer"))
                .put(JSONObject().put("uri", "library://track/197").put("nom", "Medley électro (Live France / 2019)").put("type", "track").put("artiste", "Jenifer"))
                .put(JSONObject().put("uri", "ytmusic://track/x").put("nom", "Un titre vraiment très long pour voir comment la ligne se comporte sur un petit écran").put("type", "track").put("artiste", "Jenifer, Un invité au nom long").put("source", "YouTube Music").put("domaine", "ytmusic")))
                .put("album", JSONArray().put(JSONObject().put("uri", "library://album/175").put("nom", "L'Air Du Vent").put("type", "album").put("artiste", "Jenifer"))))
                .put("plateformes", JSONArray().put(JSONObject().put("domaine", "library").put("nom", "Bibliothèque")).put(JSONObject().put("domaine", "ytmusic").put("nom", "YouTube Music")))
                .put("conseil", "").toString().toByteArray()
            premiere.contains("/party/musique") -> "application/json" to (if (corps.optString("quoi") == "chercher")
                JSONObject().put("ok", true).put("resultats", JSONObject().put("track", JSONArray().put(JSONObject().put("uri", "library://track/145").put("nom", "Dancing Queen").put("type", "track").put("artiste", "ABBA"))))
                else JSONObject().put("ok", true).put("items", JSONArray().put(JSONObject().put("uri", "library://playlist/10").put("nom", "La Famille").put("type", "playlist"))
                    .put(JSONObject().put("uri", "library://playlist/17").put("nom", "Disney classique").put("type", "playlist")
                        // 1.87.1 : une pochette de la bibliothèque locale : adresse http de Music Assistant (injoignable ici) + référence.
                        .put("image", "http://192.0.2.1:8095/imageproxy?path=disney&provider=library").put("image_ref", refDisney())))).toString().toByteArray()
            else -> "application/json" to "{}".toByteArray()
        }
        s.getOutputStream().apply {
            write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${octets.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(octets); flush()
        }
        Unit
    }

    override fun setUp() {
        super.setUp()
        serveur = ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"))
        Thread {
            while (!serveur.isClosed) {
                val s = try { serveur.accept() } catch (_: Exception) { break }
                Thread { try { servir(s) } catch (_: Exception) {} }.start()
            }
        }.apply { isDaemon = true }.start()
        for (n in listOf("vision", "party")) sauves[n] = HashMap(ctx.getSharedPreferences(n, 0).all)
        fileInitiale()
        val cfg = Config(ctx)
        cfg.id = "essai_party"; cfg.secret = "s".repeat(32)
        val adresse = "http://127.0.0.1:${serveur.localPort}"
        cfg.urlInterne = adresse; cfg.urlExterne = ""; cfg.urlActive = adresse
    }

    override fun tearDown() {
        try { serveur.close() } catch (_: Exception) {}
        for ((n, v) in sauves) ctx.getSharedPreferences(n, 0).edit().clear().apply {
            v.forEach { (k, x) -> when (x) { is String -> putString(k, x); is Boolean -> putBoolean(k, x); is Int -> putInt(k, x); is Long -> putLong(k, x); is Float -> putFloat(k, x); is Set<*> -> @Suppress("UNCHECKED_CAST") putStringSet(k, x as Set<String>) } }
        }.commit()
        super.tearDown()
    }

    private fun resolution(): String { val d = ctx.resources.displayMetrics; return "${d.widthPixels}x${d.heightPixels}_police${ctx.resources.configuration.fontScale}" }

    private fun capturer(nom: String) {
        val b: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(dossier, "$nom.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun lancer(): Activity {
        val a = instrumentation.startActivitySync(Intent(ctx, PartyActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        instrumentation.waitForIdleSync()
        return a
    }

    private fun textes(v: View, sortie: ArrayList<TextView> = ArrayList()): ArrayList<TextView> {
        if (v is TextView && v.visibility == View.VISIBLE) sortie.add(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) textes(v.getChildAt(i), sortie)
        return sortie
    }

    private fun vues(v: View, sortie: ArrayList<View> = ArrayList()): ArrayList<View> {
        sortie.add(v); if (v is ViewGroup) for (i in 0 until v.childCount) vues(v.getChildAt(i), sortie)
        return sortie
    }

    private fun tout(a: Activity): String { var t = ""; instrumentation.runOnMainSync { t = textes(a.window.decorView).joinToString(" | ") { it.text } }; return t }

    private fun attendre(quoi: String, a: Activity, condition: (String) -> Boolean) {
        val fin = SystemClock.elapsedRealtime() + 8000
        while (SystemClock.elapsedRealtime() < fin) { if (condition(tout(a))) return; SystemClock.sleep(100) }
        throw AssertionError("délai dépassé : $quoi — " + tout(a))
    }

    fun testPilotageTelephone() {
        if (ReglagesTvActivity.estTele(ctx)) return
        val a = lancer()
        attendre("pilotage affiché", a) { "Lancer Musique sur Téléviseur du salon" in it }
        val t = tout(a)
        for (attendu in listOf("Musique", "Arrêté", "Télé des parents", "Salon", "Toute la maison (groupe)",
                "Le titre qui passe", "Un artiste", "Titre suivant", "Artiste 2")) assertTrue("« $attendu » absent : $t", attendu in t)
        SystemClock.sleep(1200)  // la pochette arrive
        capturer("party_telephone_arrete")
        // Lancer : « En cours… » tout de suite, puis le retour du serveur ; le Mode Fête part avec.
        instrumentation.runOnMainSync { textes(a.window.decorView).first { it.text.toString() == "Lancer Musique sur Téléviseur du salon" }.performClick() }
        SystemClock.sleep(150)
        assertTrue("pas de retour immédiat : " + tout(a), "En cours…" in tout(a))
        attendre("retour du lancement", a) { "Musique lancée." in it && "Arrêter Musique" in it }
        val c = synchronized(commandes) { commandes.last() }
        assertEquals("lancer", c.optString("action")); assertEquals("t1", c.optString("ecran"))
        assertEquals("media_player.salon", c.optString("lecteur")); assertFalse("Mode Fête envoyé", c.has("mode_fete"))
        assertFalse("Mode Fête encore visible : " + tout(a), "Mode Fête" in tout(a))
        capturer("party_telephone_lancee")
        // Volume + : la valeur suit le clic, et part bornée.
        instrumentation.runOnMainSync { vues(a.window.decorView).first { it.contentDescription == "Monter le volume" }.performClick() }
        SystemClock.sleep(150)
        assertTrue("volume pas mis à jour : " + tout(a), "Volume 45 %" in tout(a))
        attendre("volume envoyé", a) { synchronized(commandes) { commandes.any { x -> x.optString("action") == "volume" } } }
        assertEquals(0.45, synchronized(commandes) { commandes.last { it.optString("action") == "volume" } }.optDouble("volume"), 0.001)
        a.finish()
    }

    /** 1.85.0 : un enfant pendant la Party choisit la musique et pilote la lecture ; lancer, arrêter, l'enceinte, les invités et le Mode Fête restent aux décideurs. */
    fun testParticipantSansEtreDecideur() {
        if (ReglagesTvActivity.estTele(ctx)) return
        piloterForce = false; participer = true; actif = true; ferme = ""
        try {
            val a = lancer()
            attendre("participation affichée", a) { "Tout le monde choisit la musique" in it }
            val t = tout(a)
            for (attendu in listOf("Le titre qui passe", "Choisir la musique", "Titre suivant", "Retirer")) assertTrue("« $attendu » absent : $t", attendu in t)
            for (interdit in listOf("Lancer Musique", "Arrêter Musique", "Mode Fête", "Ajout direct", "Toute la maison")) assertFalse("« $interdit » visible : $t", interdit in t)
            var lecture = false
            instrumentation.runOnMainSync { lecture = vues(a.window.decorView).any { it.contentDescription == "Pause" || it.contentDescription == "Lecture" } }
            assertTrue("pas de bouton lecture/pause", lecture)
            capturer("party_participant")
            a.finish()
            // Fermé par ses règles (la nuit) : il le voit, et ne pilote rien.
            participer = false; ferme = "Fermé pour l'instant : tu pourras participer quand cet appareil rouvrira."
            val b = lancer()
            attendre("fermeture affichée", b) { "Fermé pour l'instant" in it }
            val u = tout(b)
            assertFalse("« Choisir la musique » visible alors que fermé : $u", "Choisir la musique" in u)
            assertFalse("« Retirer » visible alors que fermé : $u", "Retirer" in u)
            capturer("party_ferme")
            b.finish()
        } finally { piloterForce = null; participer = true; ferme = ""; actif = false }
    }

    /** 1.86.0 : la zone sûre de la télé (5 %), aucun texte coupé, focus sans changement de géométrie. */
    private fun verifierZoneSure(a: Activity, nom: String) {
        val defauts = ArrayList<String>()
        instrumentation.runOnMainSync {
            val w = a.window.decorView.width; val h = a.window.decorView.height
            val mx = (w * 0.05f).toInt() - 2; val my = (h * 0.05f).toInt() - 2
            val p = IntArray(2)
            for (v in vues(a.window.decorView)) {
                if (!v.isShown || v.width == 0 || v.height == 0 || v === a.window.decorView || v is ViewGroup && (v.background == null || v.width >= w - 2)) continue
                v.getLocationOnScreen(p)
                val r = android.graphics.Rect(p[0], p[1], p[0] + v.width, p[1] + v.height)
                // Une vue dans une liste qui défile peut sortir de son cadre : on ne juge que ce qui est à l'écran.
                var parent = v.parent; var defile = false
                while (parent is View) { if (parent is ScrollView) defile = true; parent = parent.parent }
                if (defile) continue
                if (r.left < mx || r.top < my || r.right > w - mx || r.bottom > h - my) defauts.add("hors zone sûre : " + ((v as? TextView)?.text ?: v.javaClass.simpleName) + " " + r)
            }
            for (t in textes(a.window.decorView)) {
                val l = t.layout ?: continue
                for (i in 0 until l.lineCount) if (l.getEllipsisCount(i) > 0) defauts.add("texte coupé : ${t.text}")
                // Un mot coupé en fin de ligne (« SUIVR / E ») : une ligne qui finit au milieu d'un mot.
                for (i in 0 until l.lineCount - 1) {
                    val fin = l.getLineEnd(i)
                    if (fin in 1 until t.text.length && t.text[fin - 1].isLetter() && t.text[fin].isLetter()) defauts.add("mot coupé : ${t.text}")
                }
            }
        }
        capturer(nom)
        assertTrue("$nom :\n" + defauts.joinToString("\n"), defauts.isEmpty())
    }

    fun testPlusDeModeFeteNiAuLancement() {
        if (ReglagesTvActivity.estTele(ctx)) return
        val a = lancer()
        attendre("pilotage affiché", a) { "Lancer Musique sur Téléviseur du salon" in it }
        assertFalse(tout(a), "Mode Fête" in tout(a))
        instrumentation.runOnMainSync { textes(a.window.decorView).first { it.text.toString() == "Lancer Musique sur Téléviseur du salon" }.performClick() }
        attendre("lancement envoyé", a) { synchronized(commandes) { commandes.isNotEmpty() } }
        assertFalse(synchronized(commandes) { commandes.last() }.has("mode_fete"))
        a.finish()
    }

    fun testPleinEcranTele() {
        if (!ReglagesTvActivity.estTele(ctx)) return
        actif = true
        val a = lancer()
        attendre("plein écran", a) { "Le titre qui passe" in it }
        val t = tout(a)
        // 1.86.2 (retour d'essai) : à droite, toute la file (titre en cours mis en évidence), l'enceinte et l'ajout en tête.
        for (attendu in listOf("File d'attente", "Enceinte : Salon", "En cours", "Un artiste · Un album", "Titre suivant", "Quatrième titre", "Ajouter un titre", "Proposer un titre"))
            assertTrue("« $attendu » absent : $t", attendu in t)
        assertFalse("Mode Fête encore affiché : $t", "Mode Fête" in t)
        SystemClock.sleep(1500)
        verifierZoneSure(a, "party_tele_" + resolution())
        a.finish()
    }

    fun testWidgetLecteur() {
        if (ReglagesTvActivity.estTele(ctx)) return
        val a = lancer()
        val recus = ArrayList<Pair<String, JSONObject>>()
        instrumentation.runOnMainSync {
            val th = Local.themeEffectif(ctx, Palette.APPLI)
            val r = WidgetsHa.Reglage("essai", listOf("media_player.exemple"), WidgetsHa.LECTEUR, 4, 2)
            val v = VueWidgetHa(a, th, r, lectureSeule = false, exemple = true, agir = { _, s -> recus.add(s to JSONObject()) }, agirAvec = { _, s, d -> recus.add(s to d) })
            val cadre = FrameLayout(a).apply { setBackgroundColor(th.fond); setPadding(Ui.dp(a, 16f), Ui.dp(a, 80f), Ui.dp(a, 16f), 0) }
            cadre.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 190f)))
            a.setContentView(cadre)
        }
        instrumentation.waitForIdleSync(); SystemClock.sleep(500)
        val t = tout(a)
        assertTrue("titre absent : $t", "Titre du morceau" in t && "Artiste" in t && "40 %" in t)
        capturer("widget_lecteur")
        instrumentation.runOnMainSync { vues(a.window.decorView).first { it.contentDescription == "Pause" }.performClick() }
        instrumentation.runOnMainSync { vues(a.window.decorView).first { it.contentDescription == "Monter le volume" }.performClick() }
        assertEquals("media_play_pause", recus[0].first)
        assertEquals("volume_set", recus[1].first); assertEquals(0.45, recus[1].second.optDouble("volume_level"), 0.001)
        a.finish()
    }

    private fun cliquer(a: Activity, texte: String) {
        instrumentation.runOnMainSync { textes(a.window.decorView).first { it.text.toString() == texte && it.isClickable }.performClick() }
    }

    fun testChoisirLaMusiqueTelephone() {
        if (ReglagesTvActivity.estTele(ctx)) return
        val a = instrumentation.startActivitySync(Intent(ctx, ChoixMusiqueActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("lecteur", "media_player.salon").putExtra("lecteur_nom", "Salon"))
        attendre("playlists", a) { "La Famille" in it && "Disney classique" in it }
        for (x in listOf("Choisir la musique", "Sur : Salon", "Playlists", "Favoris", "Radios")) assertTrue("« $x » absent : " + tout(a), x in tout(a))
        capturer("choisir_musique_telephone")
        // Rien ne joue : une seule action, « Lire », qui démarre la playlist (remplace la file).
        instrumentation.runOnMainSync { vues(a.window.decorView).first { it.contentDescription == "Lire : Disney classique" }.performClick() }
        attendre("jouer envoyé", a) { synchronized(commandes) { commandes.isNotEmpty() } }
        assertEquals("remplacer", synchronized(commandes) { commandes.last() }.optString("comment"))
        a.finish()
        // La Party joue : un titre s'AJOUTE à la suite (le morceau continue), avec sa position ; « Jouer ensuite » existe.
        val b = instrumentation.startActivitySync(Intent(ctx, ChoixMusiqueActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("lecteur", "media_player.salon").putExtra("lecteur_nom", "Salon").putExtra("joue", true))
        attendre("playlists", b) { "La Famille" in it }
        instrumentation.runOnMainSync {
            val champ = vues(b.window.decorView).first { it is android.widget.EditText } as android.widget.EditText
            champ.setText("dancing"); champ.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH)
        }
        attendre("résultat", b) { "Dancing Queen" in it && "Ensuite" in it }
        assertTrue("« ⋮ » absent", vues(b.window.decorView).any { it.contentDescription == "Plus de choix : Dancing Queen" })
        instrumentation.runOnMainSync { vues(b.window.decorView).first { it.contentDescription == "Jouer ensuite : Dancing Queen" }.performClick() }
        attendre("ensuite", b) { "joue juste après" in it }
        assertEquals("ensuite", synchronized(commandes) { commandes.last() }.optString("comment"))
        assertFalse("un titre a remplacé la file", synchronized(commandes) { commandes.any { it.optString("comment") == "remplacer" && it.optString("type") == "track" } })
        b.finish()
    }

    /** 1.86.15 (l'auteur : « je clique sur Disney classique, il ne se passe rien ») : toucher la carte elle-même joue la playlist. */
    fun testToucherLaCarteJoueLaPlaylist() {
        if (ReglagesTvActivity.estTele(ctx)) return
        val a = instrumentation.startActivitySync(Intent(ctx, ChoixMusiqueActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("lecteur", "media_player.salon").putExtra("lecteur_nom", "Salon"))
        attendre("playlists", a) { "Disney classique" in it }
        val avant = synchronized(commandes) { commandes.size }
        instrumentation.runOnMainSync {
            val carte = vues(a.window.decorView).first { v -> v.tag == "carte" && textes(v).any { it.text.toString() == "Disney classique" } }
            assertTrue("la carte ne se touche pas", carte.hasOnClickListeners())
            carte.performClick()
        }
        attendre("playlist envoyée", a) { synchronized(commandes) { commandes.drop(avant).any { it.optString("uri") == "library://playlist/17" } } }
        val c = synchronized(commandes) { commandes.drop(avant).first { it.optString("uri") == "library://playlist/17" } }
        assertEquals("playlist", c.optString("type")); assertEquals("remplacer", c.optString("comment"))
        if (!a.isFinishing) a.finish()
    }

    fun testFileEtInvitesTelephone() {
        if (ReglagesTvActivity.estTele(ctx)) return
        actif = true
        val a = lancer()
        attendre("pilotage", a) { "Invités" in it && "Proposition" in it }
        val t = tout(a)
        for (x in listOf("proposé par un invité", "Ajout direct (retirer possible)", "Je valide d'abord", "Nouveau code", "Choisir la musique"))
            assertTrue("« $x » absent : $t", x in t)
        capturer("party_telephone_invites")
        instrumentation.runOnMainSync { vues(a.window.decorView).first { it.contentDescription == "Retirer Titre suivant de la file" }.performClick() }
        attendre("retirer", a) { synchronized(commandes) { commandes.any { x -> x.optString("action") == "retirer" } } }
        assertEquals("q1", synchronized(commandes) { commandes.last { it.optString("action") == "retirer" } }.optString("element"))
        attendre("prêt", a) { "En cours…" !in it }
        cliquer(a, "Je valide d'abord")
        attendre("invites", a) { synchronized(commandes) { commandes.any { x -> x.optString("action") == "invites" } } }
        assertEquals("validation", synchronized(commandes) { commandes.last { it.optString("action") == "invites" } }.optString("mode"))
        attendre("prêt", a) { "En cours…" !in it }
        instrumentation.runOnMainSync { vues(a.window.decorView).first { it.contentDescription == "Ajouter Proposition à la file" }.performClick() }
        attendre("accepter", a) { synchronized(commandes) { commandes.any { x -> x.optString("action") == "accepter" } } }
        a.finish()
    }

    fun testTeleRienNeJoueGuide() {
        if (!ReglagesTvActivity.estTele(ctx)) return
        actif = false; rienNeJoue = true
        val a = lancer()
        attendre("guide", a) { "Qu'est-ce qu'on écoute ?" in it && "La Famille" in it }
        assertTrue(tout(a), "Rechercher dans tout le catalogue" in tout(a) && "Bibliothèque" in tout(a))
        SystemClock.sleep(400)
        verifierZoneSure(a, "party_tele_guide_" + resolution())
        // Le focus ne change pas la taille d'une ligne (avant : elle grossissait et débordait à gauche).
        var avant = 0; var apres = 0
        instrumentation.runOnMainSync {
            val v = vues(a.window.decorView).first { it.contentDescription == "Jouer la playlist Disney classique" }
            avant = v.width; v.requestFocus()
        }
        SystemClock.sleep(300)
        instrumentation.runOnMainSync { val v = vues(a.window.decorView).first { it.contentDescription == "Jouer la playlist Disney classique" }; apres = (v.width * v.scaleX).toInt() }
        assertEquals("le focus change la largeur", avant, apres)
        // OK sur une playlist : la Party démarre ici, puis la playlist joue.
        instrumentation.runOnMainSync { vues(a.window.decorView).first { it.contentDescription == "Jouer la playlist La Famille" }.performClick() }
        attendre("lancer_ici puis jouer", a) { synchronized(commandes) { commandes.map { x -> x.optString("action") } == listOf("lancer_ici", "jouer") } }
        a.finish()
    }

    /** 1.86.2 (l'auteur : bordures coupées) : ce que dessine la vue (agrandie) est entièrement visible ; sinon, pourquoi. */
    private fun coupe(f: View): String {
        val p = f.parent as? View ?: return ""
        val loc = IntArray(2); p.getLocationInWindow(loc)
        val r = android.graphics.RectF(0f, 0f, f.width.toFloat(), f.height.toFloat()); f.matrix.mapRect(r)
        r.offset(loc[0] + f.left - p.scrollX.toFloat(), loc[1] + f.top - p.scrollY.toFloat())
        val vis = android.graphics.Rect()
        if (!f.getGlobalVisibleRect(vis)) return "invisible"
        val racine = f.rootView
        val nom = (f as? TextView)?.text ?: f.contentDescription ?: f.javaClass.simpleName
        return when {
            r.left < -0.5f || r.top < -0.5f || r.right > racine.width + 0.5f || r.bottom > racine.height + 0.5f -> "« $nom » sort de l'écran : $r"
            vis.left > r.left + 1.5f || vis.top > r.top + 1.5f || vis.right < r.right - 1.5f || vis.bottom < r.bottom - 1.5f -> "« $nom » coupé : dessiné $r, visible $vis"
            else -> ""
        }
    }

    /** Le focus de la télécommande : une seule vue focalisée, qui porte l'anneau (foreground) ; aucune autre ne le porte. */
    private fun focusUnique(a: Activity, etape: String, attendu: (View) -> Boolean) {
        SystemClock.sleep(350)
        var defaut = ""
        instrumentation.runOnMainSync {
            val f = a.currentFocus
            val avecAnneau = vues(a.window.decorView).filter { android.os.Build.VERSION.SDK_INT >= 23 && it.foreground != null && it.isShown }
            defaut = when {
                f == null -> "aucun focus"
                !attendu(f) -> "focus sur ${(f as? TextView)?.text ?: f.contentDescription ?: f.javaClass.simpleName}"
                android.os.Build.VERSION.SDK_INT >= 23 && f.foreground == null -> "focus sans anneau visible"
                avecAnneau.size != 1 -> "${avecAnneau.size} anneaux à l'écran"
                else -> coupe(f)
            }
        }
        capturer("parcours_$etape")
        assertTrue("$etape : $defaut", defaut.isEmpty())
    }

    private fun touche(code: Int) { instrumentation.sendKeyDownUpSync(code); instrumentation.waitForIdleSync() }

    /** 1.86.1 (retour d'essai) : le parcours complet à la télécommande, focus visible et unique à chaque étape. */
    fun testParcoursTelecommande() {
        if (!ReglagesTvActivity.estTele(ctx)) return
        actif = false; rienNeJoue = true
        val party = lancer()
        attendre("guide", party) { "Rechercher dans tout le catalogue" in it }
        focusUnique(party, "1_party") { (it as? TextView)?.text == "Rechercher dans tout le catalogue" }
        val moniteur = instrumentation.addMonitor(ChoixMusiqueActivity::class.java.name, null, false)
        touche(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        val choix = instrumentation.waitForMonitorWithTimeout(moniteur, 5000)
        assertNotNull("« Choisir la musique » ne s'ouvre pas", choix)
        attendre("playlists", choix) { "La Famille" in it }
        // 1. À l'ouverture : le focus est sur « Rechercher ».
        focusUnique(choix, "2_rechercher") { it is android.widget.EditText }
        instrumentation.runOnMainSync { (choix.currentFocus as android.widget.EditText).setText("dancing") }
        touche(android.view.KeyEvent.KEYCODE_ENTER)
        attendre("résultats", choix) { "Dancing Queen" in it }
        touche(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        val apresRecherche = ArrayList<String>()
        // On descend jusqu'à « Lire » (rien ne joue : une seule action) — « Chercher », les onglets et les filtres d'abord.
        repeat(16) {
            var t = ""; instrumentation.runOnMainSync { t = (choix.currentFocus as? TextView)?.text?.toString() ?: "" }
            apresRecherche.add(t)
            if (t == "Lire") return@repeat
            touche(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        }
        focusUnique(choix, "3_ajouter") { (it as? TextView)?.text == "Lire" }
        val avantLire = synchronized(commandes) { commandes.size }
        touche(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        // 1.86.10 : « Lire » lance la musique (la file est remplacée) et ramène à l'écran Musique.
        attendre("lire envoyé", choix) { synchronized(commandes) { commandes.drop(avantLire).any { x -> x.optString("comment") == "remplacer" && x.optString("uri") == "library://track/145" } } }
        val finLire = SystemClock.elapsedRealtime() + 5000
        while (!(choix.isFinishing || choix.isDestroyed) && SystemClock.elapsedRealtime() < finLire) SystemClock.sleep(100)
        assertTrue("« Choisir la musique » reste ouvert après « Lire »", choix.isFinishing || choix.isDestroyed)
        val moniteur2 = instrumentation.addMonitor(ChoixMusiqueActivity::class.java.name, null, false)
        instrumentation.runOnMainSync { ChoixMusiqueActivity.ouvrir(party, "media_player.salon", "Salon") }
        val choix2 = instrumentation.waitForMonitorWithTimeout(moniteur2, 5000)
        assertNotNull(choix2)
        parcoursOnglets(choix2)
        choix2.finish(); party.finish()
    }

    private fun parcoursOnglets(choix: Activity) {
        attendre("playlists", choix) { "La Famille" in it }
        // Les onglets : Playlists, Favoris, Radios, chacun visible au focus (même anneau partout).
        for ((nom, etape) in listOf("Playlists" to "5_playlists", "Favoris" to "6_favoris", "Radios" to "7_radios")) {
            instrumentation.runOnMainSync { vues(choix.window.decorView).first { (it as? TextView)?.text == nom }.requestFocus() }
            focusUnique(choix, etape) { (it as? TextView)?.text == nom }
        }
        instrumentation.runOnMainSync { vues(choix.window.decorView).first { (it as? TextView)?.text == "Playlists" }.performClick() }
        attendre("playlists", choix) { "La Famille" in it }
        instrumentation.runOnMainSync { vues(choix.window.decorView).first { it.contentDescription?.toString()?.endsWith(" : La Famille") == true }.requestFocus() }
        focusUnique(choix, "8_jouer") { it.contentDescription?.toString()?.endsWith(" : La Famille") == true }
    }

    /** L'anneau tranche à au moins 3:1 sur le fond, la carte et le bouton doré, sur chaque thème clair et sombre. */
    fun testAnneauContrasteTousThemes() {
        fun l(c: Int): Double { fun f(x: Int): Double { val v = x / 255.0; return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4) }; return 0.2126 * f(Color.red(c)) + 0.7152 * f(Color.green(c)) + 0.0722 * f(Color.blue(c)) }
        fun k(a: Int, b: Int): Double { val x = l(a); val y = l(b); return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05) }
        val defauts = ArrayList<String>()
        for (t in Themes.liste) for (sombre in listOf(Themes.SOMBRE_JAMAIS, Themes.SOMBRE_TOUJOURS)) {
            Local.poserTheme(ctx, t.nom); Local.poserModeSombre(ctx, sombre)
            val th = Local.themeEffectif(ctx, Palette.APPLI)
            for ((nom, fond) in listOf("fond" to th.fond, "carte" to Palette.melanger(th.fond, th.carte or 0xFF000000.toInt(), 1f), "bouton" to th.or)) {
                val opaque = fond or 0xFF000000.toInt()
                val meilleur = Math.max(k(th.encre or 0xFF000000.toInt(), opaque), k(th.fond or 0xFF000000.toInt(), opaque))
                if (meilleur < 3.0) defauts.add("${t.nom} ${if (sombre == Themes.SOMBRE_TOUJOURS) "sombre" else "clair"} sur $nom : ${"%.1f".format(meilleur)}")
            }
        }
        assertTrue("anneau peu visible :\n" + defauts.joinToString("\n"), defauts.isEmpty())
    }

    /** 1.86.2 (test réel de l'auteur) : la télé pilote la file, les touches média marchent, l'enceinte se choisit, pas de veille. */
    fun testTelePiloteLaFile() {
        if (!ReglagesTvActivity.estTele(ctx)) return
        actif = true; rienNeJoue = false
        val a = lancer()
        attendre("plein écran", a) { "File d'attente" in it && "Troisième titre" in it }
        // OK sur un titre de la file : jouer maintenant, ensuite, retirer.
        instrumentation.runOnMainSync { vues(a.window.decorView).first { it.contentDescription == "Titre en file : Titre suivant" }.performClick() }
        SystemClock.sleep(800)
        capturer("party_tele_menu_titre")
        // Le menu (sa propre fenêtre) : « Jouer maintenant », puis « Jouer ensuite ».
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        attendre("ensuite envoyé", a) { synchronized(commandes) { commandes.any { x -> x.optString("action") in setOf("ensuite_element", "jouer_element", "retirer") } } }
        val c = synchronized(commandes) { commandes.last { it.optString("action") in setOf("ensuite_element", "jouer_element", "retirer") } }
        assertEquals("ensuite_element", c.optString("action")); assertEquals("q1", c.optString("element"))
        SystemClock.sleep(900)
        // La touche « suivant » de la télécommande.
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
        attendre("suivant", a) { synchronized(commandes) { commandes.any { x -> x.optString("action") == "suivant" } } }
        // Pendant la Party, l'écran de veille de Vision ne s'ouvre pas.
        instrumentation.runOnMainSync { Veille.ouvrir(ctx) }
        SystemClock.sleep(600)
        assertFalse("la veille s'est ouverte pendant la Party", Veille.ouverte)
        a.finish()
    }

    private fun focusSur(a: Activity): String { var t = ""; instrumentation.runOnMainSync { t = a.currentFocus?.contentDescription?.toString() ?: (a.currentFocus as? TextView)?.text?.toString() ?: "" }; return t }
    private fun constructions(a: Activity): Int { var n = 0; instrumentation.runOnMainSync { n = (a as PartyActivity).constructions }; return n }
    private fun derniere(action: String): JSONObject? = synchronized(commandes) { commandes.lastOrNull { it.optString("action") == action } }

    /** Le menu d'un titre à la télé : OK, puis la ligne [rang] (0 : Jouer maintenant, 1 : ensuite, 2 : Déplacer, 3 : à la fin, 4 : Retirer). */
    private fun menu(rang: Int) {
        touche(android.view.KeyEvent.KEYCODE_DPAD_CENTER); SystemClock.sleep(500)
        repeat(rang) { touche(android.view.KeyEvent.KEYCODE_DPAD_DOWN) }
        touche(android.view.KeyEvent.KEYCODE_DPAD_CENTER); SystemClock.sleep(150)
    }

    /**
     * 1.86.2 (l'auteur : « Retirer saccade », déplacer un titre) : 3 retraits et 2 déplacements à la télécommande. La ligne se
     * replie sans recharger l'écran, le focus passe à la suivante, la lecture n'est jamais touchée, l'ordre final est
     * celui de Music Assistant.
     */
    fun testTeleRetirerEtDeplacer() {
        if (!ReglagesTvActivity.estTele(ctx)) return
        actif = true; rienNeJoue = false; fileInitiale(8)
        val a = lancer()
        attendre("file", a) { "Huitième titre" in it && "148 titres à suivre" in it }
        instrumentation.runOnMainSync { vues(a.window.decorView).first { it.contentDescription == "Titre en file : Titre suivant" }.requestFocus() }
        focusUnique(a, "file_0") { it.contentDescription == "Titre en file : Titre suivant" }
        val c0 = constructions(a)
        // Trois retraits d'affilée : à chaque fois la ligne suivante prend le focus.
        for ((k, suivant) in listOf("Encore après", "Troisième titre", "Quatrième titre").withIndex()) {
            menu(4)
            focusUnique(a, "file_retrait_${k + 1}") { it.contentDescription == "Titre en file : $suivant" }
            SystemClock.sleep(250)
        }
        attendre("trois retraits envoyés", a) { idsServeur().take(1) == listOf("q4") }
        assertFalse("ligne retirée encore là", "Troisième titre" in tout(a))
        assertTrue("compte faux : ${tout(a)}", "145 titres à suivre" in tout(a))
        // Déplacer « Quatrième titre » de deux rangs vers le bas : haut / bas, OK valide.
        menu(2)
        touche(android.view.KeyEvent.KEYCODE_DPAD_DOWN); touche(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        focusUnique(a, "file_deplacer") { it.contentDescription == "Titre en file : Quatrième titre" }
        touche(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        attendre("déplacé", a) { derniere("deplacer")?.optInt("decalage") == 2 }
        assertEquals(listOf("q5", "q6", "q4", "q7", "q8"), idsServeur())
        // Retour annule : rien n'est envoyé.
        val avant = synchronized(commandes) { commandes.size }
        menu(2); touche(android.view.KeyEvent.KEYCODE_DPAD_UP); touche(android.view.KeyEvent.KEYCODE_BACK)
        SystemClock.sleep(400)
        assertEquals("Retour n'a pas annulé", avant, synchronized(commandes) { commandes.size })
        assertFalse("Retour a quitté l'écran", a.isFinishing)
        // Second déplacement : d'un rang vers le haut.
        menu(2); touche(android.view.KeyEvent.KEYCODE_DPAD_UP); touche(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        attendre("déplacé vers le haut", a) { derniere("deplacer")?.optInt("decalage") == -1 }
        assertEquals(listOf("q5", "q4", "q6", "q7", "q8"), idsServeur())
        focusUnique(a, "file_fin") { it.contentDescription == "Titre en file : Quatrième titre" }
        SystemClock.sleep(2500)  // la relecture qui suit : même file, rien à rebâtir
        assertEquals("l'écran a été rebâti pendant les retraits / déplacements", c0, constructions(a))
        assertNull("la lecture a été touchée", synchronized(commandes) { commandes.firstOrNull { it.optString("action") in setOf("jouer", "jouer_element", "lecture", "suivant") } })
        a.finish()
    }

    /** 1.86.2 : au téléphone, 3 retraits (« Retirer ») et 2 déplacements à la poignée, sans rien recharger. */
    fun testTelephoneRetirerEtGlisser() {
        if (ReglagesTvActivity.estTele(ctx)) return
        actif = true; rienNeJoue = false; fileInitiale(8)
        val a = lancer()
        attendre("file", a) { "Huitième titre" in it && "148 titres à suivre" in it }
        val c0 = constructions(a)
        for (t in listOf("Titre suivant", "Encore après", "Troisième titre")) {
            instrumentation.runOnMainSync { vues(a.window.decorView).first { it.contentDescription == "Retirer $t de la file" }.performClick() }
            SystemClock.sleep(320)
        }
        attendre("trois retraits", a) { idsServeur().first() == "q4" }
        assertTrue("compte faux : ${tout(a)}", "145 titres à suivre" in tout(a))
        fun glisser(titre: String, rangs: Float) {
            var x = 0f; var y = 0f; var h = 0
            // La ligne d'abord au milieu de l'écran, comme sous un doigt.
            instrumentation.runOnMainSync {
                val r = vues(a.window.decorView).first { it.contentDescription == "Glisser pour déplacer $titre" }.parent as View
                var sv: android.view.ViewParent? = r.parent
                while (sv != null && sv !is ScrollView) sv = sv.parent
                (sv as? ScrollView)?.let { s ->
                    val rr = android.graphics.Rect(); r.getDrawingRect(rr); s.offsetDescendantRectToMyCoords(r, rr)
                    s.scrollTo(0, (rr.centerY() - s.height / 2).coerceAtLeast(0))
                }
            }
            SystemClock.sleep(300)
            instrumentation.runOnMainSync {
                val v = vues(a.window.decorView).first { it.contentDescription == "Glisser pour déplacer $titre" }
                (v.parent as View).let { r -> h = r.height }
                val p = IntArray(2); v.getLocationOnScreen(p); x = p[0] + v.width / 2f; y = p[1] + v.height / 2f
            }
            val t0 = SystemClock.uptimeMillis()
            fun ev(action: Int, yy: Float, dt: Long) = android.view.MotionEvent.obtain(t0, t0 + dt, action, x, yy, 0)
            instrumentation.sendPointerSync(ev(android.view.MotionEvent.ACTION_DOWN, y, 0))
            val pas = 12
            for (k in 1..pas) instrumentation.sendPointerSync(ev(android.view.MotionEvent.ACTION_MOVE, y + h * rangs * k / pas, 20L * k))
            instrumentation.sendPointerSync(ev(android.view.MotionEvent.ACTION_UP, y + h * rangs, 20L * pas + 10))
            SystemClock.sleep(300)
        }
        glisser("Quatrième titre", 2.1f)
        attendre("glissé vers le bas", a) { derniere("deplacer")?.optInt("decalage") == 2 }
        capturer("telephone_file_glissee")
        glisser("Septième titre", -1.1f)
        attendre("glissé vers le haut", a) { derniere("deplacer")?.optInt("decalage") == -1 }
        assertEquals(listOf("q5", "q6", "q7", "q4", "q8"), idsServeur())
        SystemClock.sleep(2500)
        assertEquals("l'écran a été rebâti pendant les retraits / déplacements", c0, constructions(a))
        assertNull("la lecture a été touchée", synchronized(commandes) { commandes.firstOrNull { it.optString("action") in setOf("jouer", "jouer_element", "lecture", "suivant") } })
        a.finish()
    }

    /** 1.86.2 (retour d'essai) : file vide et musique arrêtée → un clin d'œil (titre de chanson + émoji) et l'action, télé et téléphone. */
    fun testClinDOeilFileVide() {
        assertEquals(8, ctx.resources.getStringArray(R.array.musique_fin_clins).size)  // même liste que Home Assistant
        actif = true; rienNeJoue = true; fileInitiale(0); finClin = 7
        try {
            val a = lancer()
            attendre("clin d'œil", a) { "Don't Stop Me Now 🎤" in it && "Ajoute un titre pour relancer la fête" in it }
            if (ReglagesTvActivity.estTele(ctx)) {
                focusUnique(a, "clin_tele") { (it as? TextView)?.text == "Rechercher dans tout le catalogue" }
                verifierZoneSure(a, "clin_tele_" + resolution())
            } else capturer("clin_telephone")
            a.finish()
        } finally { finClin = -1 }
    }

    /** 1.86.3 (l'auteur : « je n'ai pas toutes les télés ») : tout est listé, l'indisponible est grisé avec sa raison, l'aide est là. */
    fun testToutesLesEnceintes() {
        actif = true; rienNeJoue = false
        val a = lancer()
        if (ReglagesTvActivity.estTele(ctx)) {
            attendre("plein écran", a) { "Enceinte : Salon" in it }
            instrumentation.runOnMainSync { vues(a.window.decorView).first { it.contentDescription == "Changer d'enceinte" }.performClick() }
            SystemClock.sleep(800)
            capturer("enceintes_tele_" + resolution())
            touche(android.view.KeyEvent.KEYCODE_BACK)
        } else {
            attendre("enceintes", a) { "Clé TV Mi · hors ligne" in it && "Fournisseurs de lecteurs" in it }
            val avant = synchronized(commandes) { commandes.size }
            instrumentation.runOnMainSync { textes(a.window.decorView).first { it.text.toString() == "Clé TV Mi · hors ligne" }.performClick() }
            attendre("raison", a) { "Clé TV Mi : Hors ligne" in it }
            assertEquals("une enceinte hors ligne a été choisie", avant, synchronized(commandes) { commandes.size })
            capturer("enceintes_telephone")
        }
        a.finish()
    }

    /**
     * 1.86.4 (photo de l'auteur, télé TCL) : une longue file aux titres longs. Même avec le focus sur l'en-tête, la file reste
     * dans son cadre (rien sous l'aide ni sous le bord de l'écran), l'aide tient sur une ligne dans la zone sûre, les
     * titres longs finissent par « … ».
     */
    fun testTeleFileLongueDansSonCadre() {
        if (!ReglagesTvActivity.estTele(ctx)) return
        actif = true; rienNeJoue = false
        synchronized(fileServeur) {
            fileServeur.clear()
            for (k in 1..14) fileServeur.add(JSONObject().put("id", "q$k").put("artiste", if (k % 2 == 0) "Lennon" else "Artiste au nom vraiment très long numéro $k")
                .put("titre", if (k == 14) "ESSEL" else "Ninja Story — la version très longue enregistrée en public numéro $k"))
        }
        val a = lancer()
        attendre("file", a) { "ESSEL" in it }
        for ((etape, focus) in listOf("entete" to "Ajouter un titre", "fin" to "Titre en file : ESSEL")) {
            instrumentation.runOnMainSync { vues(a.window.decorView).first { (it as? TextView)?.text == focus || it.contentDescription == focus }.requestFocus() }
            SystemClock.sleep(900)
            val defauts = ArrayList<String>()
            var zoneBas = android.graphics.Rect()
            instrumentation.runOnMainSync {
                val racine = a.window.decorView
                val w = racine.width; val h = racine.height
                val aide = vues(racine).first { it.contentDescription == "Aide" } as TextView
                val sv = vues(racine).first { it is ScrollView && vues(it).any { x -> x.tag?.toString()?.startsWith("file:") == true } } as ScrollView
                val ra = android.graphics.Rect(); aide.getGlobalVisibleRect(ra)
                val rs = android.graphics.Rect(); sv.getGlobalVisibleRect(rs)
                if (aide.layout.lineCount != 1 || aide.layout.getEllipsisCount(0) > 0) defauts.add("aide sur plusieurs lignes ou coupée : ${aide.text}")
                if (android.graphics.Rect.intersects(ra, rs)) defauts.add("l'aide chevauche la file : aide $ra, file $rs")
                if (ra.bottom > h - (h * 0.05f).toInt() + 2 || ra.right > w - (w * 0.05f).toInt() + 2) defauts.add("aide hors zone sûre : $ra")
                if (!sv.clipToOutline && !(sv.parent as ViewGroup).clipChildren) defauts.add("la file n'a plus de cadre")
                for (t in textes(sv)) {
                    if (!t.isShown) continue
                    if (t.maxLines != 1) defauts.add("titre sur plusieurs lignes : ${t.text}")
                    val r = android.graphics.Rect(); if (t.getGlobalVisibleRect(r) && r.right > rs.right) defauts.add("texte hors du cadre : ${t.text}")
                }
                zoneBas = android.graphics.Rect(rs.left, ra.bottom + 2, rs.right, h - 2)
            }
            // Au pixel : sous l'aide, jusqu'au bas de l'écran, rien de la file n'est dessiné (fond uni).
            val b = instrumentation.uiAutomation.takeScreenshot()
            if (b != null && zoneBas.height() > 4) {
                val ref = b.getPixel(zoneBas.left + 2, zoneBas.bottom - 2)
                var autres = 0
                for (y in zoneBas.top until zoneBas.bottom step 3) for (x in zoneBas.left until zoneBas.right step 7) {
                    val c = b.getPixel(x, y)
                    if (Math.abs(Color.red(c) - Color.red(ref)) + Math.abs(Color.green(c) - Color.green(ref)) + Math.abs(Color.blue(c) - Color.blue(ref)) > 30) autres++
                }
                if (autres > 0) defauts.add("$autres points de la file dessinés sous l'aide / sous le bord de l'écran")
            }
            capturer("file_longue_${etape}_" + resolution())
            assertTrue("$etape : " + defauts.joinToString(" | "), defauts.isEmpty())
        }
        a.finish()
    }

    private fun champ(o: Any, nom: String): Any? {
        var c: Class<*>? = o.javaClass
        while (c != null) { try { return c.getDeclaredField(nom).apply { isAccessible = true }.get(o) } catch (_: NoSuchFieldException) { c = c.superclass } }
        throw AssertionError("champ $nom")
    }

    /**
     * 1.86.5 (retour d'essai) : sur l'accueil, le mini-lecteur quand la musique joue (pochette, titre, lecture/pause, suivant) ;
     * il ne prend pas le focus, ne rebâtit pas l'accueil, et ramène à Musique. Option : la pochette floutée en fond.
     */
    fun testMiniLecteurAccueil() {
        actif = true; rienNeJoue = false
        ctx.getSharedPreferences("party", 0).edit().putBoolean("tuile", true).putBoolean("fond_floute", false).commit()
        val a = instrumentation.startActivitySync(Intent(ctx, LanceurActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        attendre("mini-lecteur", a) { "Le titre qui passe" in it }
        SystemClock.sleep(1500)
        val c0 = champ(a, "constructions") as Int
        val g0 = Paquets.generation
        var mini: View? = null
        instrumentation.runOnMainSync { mini = vues(a.window.decorView).first { it.tag == "mini_lecteur" } }
        assertEquals(View.VISIBLE, mini!!.visibility)
        if (ReglagesTvActivity.estTele(ctx)) {
            var f: View? = null
            instrumentation.runOnMainSync { f = a.currentFocus }
            var p: Any? = f
            while (p is View) { assertNotSame("le mini-lecteur a pris le focus", mini, p); p = p.parent }
        }
        capturer("accueil_mini_lecteur_" + resolution())
        // Suivant : la commande part, sur le lecteur de la soirée.
        instrumentation.runOnMainSync { vues(mini!!).first { it.contentDescription == "Titre suivant" }.performClick() }
        attendre("suivant", a) { synchronized(commandes) { commandes.any { x -> x.optString("action") == "suivant" && x.optString("lecteur") == "media_player.salon" } } }
        // L'option : la pochette floutée en fond.
        ctx.getSharedPreferences("party", 0).edit().putBoolean("fond_floute", true).commit()
        attendre("fond flouté", a) { var vu = false; instrumentation.runOnMainSync { vu = vues(a.window.decorView).any { it is android.widget.ImageView && it.alpha in 0.3f..0.34f && it.visibility == View.VISIBLE } }; vu }
        SystemClock.sleep(800)
        capturer("accueil_fond_floute_" + resolution())
        SystemClock.sleep(6000)  // une relecture de plus : même musique, rien à rebâtir
        // Une appli installée ou mise à jour pendant le test rebâtit l'accueil (normal) : on ne juge que sans cela.
        if (Paquets.generation == g0) assertEquals("l'accueil a été rebâti par le mini-lecteur", c0, champ(a, "constructions") as Int)
        // Toucher la carte ramène à Musique.
        val moniteur = instrumentation.addMonitor(PartyActivity::class.java.name, null, false)
        instrumentation.runOnMainSync { mini!!.performClick() }
        val party = instrumentation.waitForMonitorWithTimeout(moniteur, 5000)
        assertNotNull("le mini-lecteur n'ouvre pas Musique", party)
        party?.finish()
        ctx.getSharedPreferences("party", 0).edit().putBoolean("fond_floute", false).commit()
        a.finish()
    }

    /** 1.86.5 (retour d'essai) : la musique joue mais la soirée a été oubliée (redémarrage de HA) : la télé arrive sur l'écran Lecture. */
    fun testTeleRevientSurLaLecture() {
        if (!ReglagesTvActivity.estTele(ctx)) return
        actif = false; rienNeJoue = false
        val a = lancer()
        attendre("relance", a) { synchronized(commandes) { commandes.any { x -> x.optString("action") == "lancer_ici" } } }
        attendre("écran Lecture", a) { "File d'attente" in it && "Le titre qui passe" in it }
        assertFalse("la recherche s'affiche au lieu de la lecture", "Rechercher dans tout le catalogue" in tout(a))
        focusUnique(a, "retour_lecture") { it.contentDescription == "Pause" }
        a.finish()
    }

    /** 1.86.5 (retour d'essai) : quitter Musique (Retour, accueil, autre appli) n'envoie rien : ni pause, ni arrêt, ni vidage. */
    fun testQuitterNeCoupePasLaMusique() {
        actif = true; rienNeJoue = false
        val a = lancer()
        attendre("écran", a) { "Le titre qui passe" in it }
        val avant = synchronized(commandes) { commandes.size }
        instrumentation.runOnMainSync { a.onBackPressed() }
        SystemClock.sleep(600)
        ctx.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        SystemClock.sleep(1500)
        val apres = synchronized(commandes) { commandes.drop(avant).map { it.optString("action") } }
        assertTrue("quitter a envoyé : $apres", apres.isEmpty())
    }

    /** 1.86.6 (retour d'essai) : « Enchaînement : sans blanc / fondu de X s », envoyé au lecteur de la soirée. */
    fun testEnchainement() {
        actif = true; rienNeJoue = false
        val a = lancer()
        attendre("enchaînement", a) { "sans blanc" in it.lowercase() }
        if (ReglagesTvActivity.estTele(ctx)) {
            instrumentation.runOnMainSync { textes(a.window.decorView).first { it.text.toString() == "Enchaînement : sans blanc" }.performClick() }
            attendre("fondu 4 s", a) { synchronized(commandes) { commandes.any { x -> x.optString("action") == "enchainement" && x.optString("mode") == "fondu" && x.optInt("duree") == 4 } } }
        } else {
            instrumentation.runOnMainSync { textes(a.window.decorView).first { it.text.toString() == "Fondu 8 s" }.performClick() }
            attendre("fondu 8 s", a) { synchronized(commandes) { commandes.any { x -> x.optString("action") == "enchainement" && x.optInt("duree") == 8 } } }
            capturer("enchainement_telephone")
        }
        a.finish()
    }

    /**
     * 1.86.7 (l'auteur : « j'appuie sur pause, il affiche play, puis revient sur pause ») : avec une enceinte lente (2,5 s),
     * le bouton bascule tout de suite et ne revient jamais en arrière ; la barre se fige ; HA reçoit l'état VOULU.
     */
    fun testLecturePauseSansVaEtVient() {
        actif = true; rienNeJoue = false; etatLecteur = "playing"; delaiLecteurMs = 2500
        try {
            val a = lancer()
            attendre("lecture", a) { "Le titre qui passe" in it }
            val avant = synchronized(commandes) { commandes.size }
            instrumentation.runOnMainSync { vues(a.window.decorView).first { it.contentDescription == "Pause" }.performClick() }
            SystemClock.sleep(300)
            val vus = ArrayList<String>(); val temps = ArrayList<String>()
            repeat(14) {
                instrumentation.runOnMainSync {
                    val d = vues(a.window.decorView)
                    vus.add(if (d.any { it.contentDescription == "Pause" && it.isShown }) "Pause" else if (d.any { it.contentDescription == "Lecture" && it.isShown }) "Lecture" else "?")
                    temps.add(textes(a.window.decorView).firstOrNull { it.text.contains(" / 3:41") }?.text?.toString()?.substringBefore(" ·")?.trim() ?: "")
                }
                SystemClock.sleep(250)
            }
            assertTrue("le bouton est revenu en arrière : $vus", vus.all { it == "Lecture" })
            assertEquals("la barre n'est pas figée à la pause : $temps", 1, temps.filter { it.isNotEmpty() }.toSet().size)
            val envoye = synchronized(commandes) { commandes.drop(avant).first { it.optString("action") == "lecture" } }
            assertEquals("pause", envoye.optString("voulu"))
            a.finish()
        } finally { delaiLecteurMs = 0; etatLecteur = "playing" }
    }

    /** 1.86.7 (photo de l'auteur) : « File d'attente » sur une ligne, enceinte au nom long dans la zone sûre, pochette de la ligne en cours. */
    fun testTeleEnteteEtPochetteEnCours() {
        if (!ReglagesTvActivity.estTele(ctx)) return
        actif = true; rienNeJoue = false; nomEnceinte = "Téléviseur du salon"
        try {
            val a = lancer()
            attendre("file", a) { "File d'attente" in it }
            SystemClock.sleep(1500)
            instrumentation.runOnMainSync {
                val titre = textes(a.window.decorView).first { it.text.toString() == "File d'attente" }
                assertEquals("« File d'attente » coupé", 1, titre.layout.lineCount)
                val compte = textes(a.window.decorView).first { it.text.toString().endsWith("titres à suivre") }
                assertEquals("le nombre sur plusieurs lignes", 1, compte.layout.lineCount)
                // La ligne « En cours » montre la même pochette que le grand lecteur.
                val ligne = vues(a.window.decorView).first { v -> v is ViewGroup && textes(v).any { it.text.toString().startsWith("En cours") } } as ViewGroup
                val img = vues(ligne).first { it is android.widget.ImageView } as android.widget.ImageView
                assertNotNull("pas de pochette sur la ligne en cours", img.drawable)
            }
            verifierZoneSure(a, "entete_enceinte_longue_" + resolution())
            a.finish()
        } finally { nomEnceinte = "Salon" }
    }

    /**
     * 1.86.7 (l'auteur : « l'icône musique n'est pas comme les autres ») : même gabarit que les applis — un disque plein, au
     * bord, la note contrastée (3:1 au moins), dans chaque thème clair et sombre ; capture de l'accueil.
     */
    fun testTuileMusiqueCommeLesApplis() {
        fun l(c: Int): Double { fun f(x: Int): Double { val v = x / 255.0; return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4) }; return 0.2126 * f(Color.red(c)) + 0.7152 * f(Color.green(c)) + 0.0722 * f(Color.blue(c)) }
        val themeAvant = Local.theme(ctx); val modeAvant = Local.modeSombre(ctx)
        val pr = ctx.getSharedPreferences("party", 0)
        try {
            for ((theme, mode) in listOf("Beige" to Themes.SOMBRE_JAMAIS, "Beige" to Themes.SOMBRE_TOUJOURS, "Bleu nuit" to Themes.SOMBRE_JAMAIS, "Sauge" to Themes.SOMBRE_TOUJOURS, "Rose poudré" to Themes.SOMBRE_JAMAIS)) {
                Local.poserTheme(ctx, theme); Local.poserModeSombre(ctx, mode)
                val d = Accueil.iconeMusique(ctx)
                val b = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
                d.setBounds(0, 0, 192, 192); d.draw(android.graphics.Canvas(b))
                assertEquals("$theme : le disque ne touche pas le bord", 255, Color.alpha(b.getPixel(96, 1)))
                assertEquals("$theme : coin non transparent (pas un disque)", 0, Color.alpha(b.getPixel(2, 2)))
                val th = Local.themeEffectif(ctx, Palette.LANCEUR)
                val fond = b.getPixel(96, 20)
                // La note : le pixel le plus éloigné du fond dans la zone centrale.
                var note = fond; var ecart = -1.0
                for (x in 60 until 132 step 3) for (y in 60 until 132 step 3) { val c = b.getPixel(x, y); val e = Math.abs(l(c) - l(fond)); if (e > ecart) { ecart = e; note = c } }
                val k = (Math.max(l(fond), l(note)) + 0.05) / (Math.min(l(fond), l(note)) + 0.05)
                assertTrue("$theme ($mode) : note peu lisible ($k:1)", k >= 3.0)
                assertEquals("$theme : le fond n'est pas la couleur du thème", th.or or 0xFF000000.toInt(), fond or 0xFF000000.toInt())
            }
            // L'accueil, dans deux thèmes : la tuile Musique à côté des applis.
            pr.edit().putBoolean("tuile", false).putBoolean("proposee", false).commit()
            Party.releve(ctx, JSONObject().put("tuile", true))
            Accueil.cacher(ctx, Party.CLE, false)
            for ((theme, mode, nom) in listOf(Triple("Beige", Themes.SOMBRE_JAMAIS, "clair"), Triple("Beige", Themes.SOMBRE_TOUJOURS, "sombre"))) {
                Local.poserTheme(ctx, theme); Local.poserModeSombre(ctx, mode)
                val a = instrumentation.startActivitySync(Intent(ctx, LanceurActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                SystemClock.sleep(2500)
                capturer("accueil_tuile_musique_${nom}_" + resolution())
                a.finish()
            }
        } finally { Local.poserTheme(ctx, themeAvant); Local.poserModeSombre(ctx, modeAvant) }
    }

    /** 1.86.8 (l'auteur : « retirer revient en flash, le compteur revient au nombre initial ») ; et un refus dit pourquoi. */
    fun testRetirerSansRetourEnArriere() {
        actif = true; rienNeJoue = false; fileInitiale(6)
        try {
            val a = lancer()
            attendre("file", a) { "146 titres à suivre" in it }
            totalFige = 146  // Music Assistant n'a pas encore compté le retrait
            if (ReglagesTvActivity.estTele(ctx)) {
                instrumentation.runOnMainSync { vues(a.window.decorView).first { it.contentDescription == "Titre en file : Encore après" }.requestFocus() }
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER); SystemClock.sleep(500)
                repeat(4) { instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN) }
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
            } else instrumentation.runOnMainSync { vues(a.window.decorView).first { it.contentDescription == "Retirer Encore après de la file" }.performClick() }
            val vus = ArrayList<String>()
            repeat(16) {
                val t = tout(a)
                vus.add(Regex("([0-9]+) titres à suivre").find(t)?.groupValues?.get(1) ?: "?")
                assertFalse("la ligne retirée est revenue", "Encore après" in t && it > 2)
                SystemClock.sleep(250)
            }
            val i = vus.indexOf("145")
            assertTrue("le compteur n'est pas descendu : $vus", i >= 0)
            assertFalse("le compteur est remonté : $vus", vus.drop(i).contains("146"))
            totalFige = null
            // Retrait refusé : la ligne revient et un message dit pourquoi.
            refuserRetrait = true
            if (ReglagesTvActivity.estTele(ctx)) {
                instrumentation.runOnMainSync { vues(a.window.decorView).first { it.contentDescription == "Titre en file : Troisième titre" }.requestFocus() }
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER); SystemClock.sleep(500)
                repeat(4) { instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN) }
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
            } else instrumentation.runOnMainSync { vues(a.window.decorView).first { it.contentDescription == "Retirer Troisième titre de la file" }.performClick() }
            attendre("message de refus", a) { "Pas fait" in it && "Troisième titre" in it }
            a.finish()
        } finally { totalFige = null; refuserRetrait = false }
    }

    private fun shell(cmd: String) {
        val pfd = instrumentation.uiAutomation.executeShellCommand(cmd)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }

    /**
     * 1.86.9 (capture de l'auteur, Pixel) : « Choisir la musique » au téléphone, 360 et 412 dp, police 100 % et 130 % — le
     * titre a toute la largeur (2 lignes au plus, « … »), jamais un mot coupé en colonnes ; les actions en dessous.
     */
    fun testChoixMusiqueTelephoneMiseEnPage() {
        if (ReglagesTvActivity.estTele(ctx)) return
        try {
            for ((densite, largeur) in listOf(480 to 360, 420 to 412)) for (police in listOf("1.0", "1.3")) {
                shell("wm density $densite"); shell("settings put system font_scale $police"); SystemClock.sleep(1500)
                val b = instrumentation.startActivitySync(Intent(ctx, ChoixMusiqueActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("lecteur", "media_player.salon").putExtra("lecteur_nom", "Téléviseur du salon").putExtra("joue", true))
                attendre("playlists", b) { "La Famille" in it }
                instrumentation.runOnMainSync {
                    val champ = vues(b.window.decorView).first { it is android.widget.EditText } as android.widget.EditText
                    champ.setText("jenifer"); champ.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH)
                }
                attendre("résultats", b) { "Medley électro" in it }
                SystemClock.sleep(600)
                val defauts = ArrayList<String>()
                instrumentation.runOnMainSync {
                    val w = b.window.decorView.width
                    for (titre in listOf("L'air du vent", "Medley électro (Live France / 2019)")) {
                        val t = textes(b.window.decorView).first { it.text.toString() == titre }
                        if (ctx.resources.configuration.screenWidthDp / ctx.resources.configuration.fontScale >= 300f && t.width < w * 0.3f) defauts.add("« $titre » trop étroit (${t.width} px sur $w)")
                        if (t.layout.lineCount > 2) defauts.add("« $titre » sur ${t.layout.lineCount} lignes")
                        // 1.86.12 (retour d'essai) : carte compacte — l'action sur la ligne du titre, à droite, centrée.
                        val bouton = vues(b.window.decorView).first { it.contentDescription == "Jouer ensuite : $titre" }
                        val pt = IntArray(2); t.getLocationOnScreen(pt); val pb = IntArray(2); bouton.getLocationOnScreen(pb)
                        val compacte = ctx.resources.configuration.screenWidthDp / ctx.resources.configuration.fontScale >= 300f
                        if (compacte && pb[0] < pt[0] + t.width - 2) defauts.add("« Jouer ensuite » pas à droite du titre")
                        if (compacte && pb[1] > pt[1] + t.height) defauts.add("« Jouer ensuite » sous le titre (carte haute)")
                        if (!compacte && t.width < w * 0.5f) defauts.add("« $titre » trop étroit sous grande police")
                    }
                    for (t in textes(b.window.decorView)) {
                        val l = t.layout ?: continue
                        for (i in 0 until l.lineCount - 1) {
                            val fin = l.getLineEnd(i)
                            if (fin in 1 until t.text.length && t.text[fin - 1].isLetter() && t.text[fin].isLetter()) defauts.add("mot coupé : ${t.text}")
                        }
                    }
                }
                if ("rien trouvé" in tout(b)) defauts.add("message technique affiché")
                capturer("choix_musique_${largeur}dp_police$police")
                b.finish()
                assertTrue("$largeur dp, police $police : $defauts", defauts.isEmpty())
            }
        } finally { shell("wm density reset"); shell("settings put system font_scale 1.0") }
    }

    /**
     * 1.86.10 (photos de l'auteur) : un ALBUM se lit (« Lire » quand rien ne joue, puis « Ajouter à la suite ») ; les mêmes
     * actions pour tous les types ; filtres type / plateforme envoyés et mémorisés ; à la télé, le premier résultat
     * focalisé est entier, sous la marge haute.
     */
    fun testAlbumEtFiltres() {
        Party.poserFiltres(ctx, emptySet(), emptySet())
        fun ouvrir(joue: Boolean): Activity {
            val b = instrumentation.startActivitySync(Intent(ctx, ChoixMusiqueActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("lecteur", "media_player.salon").putExtra("lecteur_nom", "Téléviseur du salon").putExtra("joue", joue))
            attendre("playlists", b) { "La Famille" in it }
            instrumentation.runOnMainSync {
                val champ = vues(b.window.decorView).first { it is android.widget.EditText } as android.widget.EditText
                champ.setText("jenifer"); champ.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH)
            }
            attendre("résultats", b) { "L'Air Du Vent" in it && "Albums" in it }
            return b
        }
        val a = ouvrir(false)
        // À la télé : le premier résultat focalisé est entier, sous la marge haute (5 %).
        if (ReglagesTvActivity.estTele(ctx)) {
            instrumentation.runOnMainSync { vues(a.window.decorView).first { it.contentDescription == "Lire : L'air du vent" }.requestFocus() }
            SystemClock.sleep(700)
            var defaut = ""
            instrumentation.runOnMainSync {
                val f = a.currentFocus!!; val ligne = f.parent.parent as View
                val r = android.graphics.Rect(); ligne.getGlobalVisibleRect(r)
                val h = a.window.decorView.height
                if (r.height() < ligne.height - 2) defaut = "résultat coupé (${r.height()} / ${ligne.height})"
                else if (r.top < (h * 0.05f).toInt() - 2) defaut = "résultat dans la marge haute (${r.top})"
            }
            capturer("choix_tele_premier_resultat")
            assertTrue(defaut, defaut.isEmpty())
        }
        val avant = synchronized(commandes) { commandes.size }
        instrumentation.runOnMainSync { vues(a.window.decorView).first { it.contentDescription == "Lire : L'Air Du Vent" }.performClick() }
        attendre("album lu", a) { synchronized(commandes) { commandes.drop(avant).any { x -> x.optString("type") == "album" } } }
        val c = synchronized(commandes) { commandes.drop(avant).first { it.optString("type") == "album" } }
        assertEquals("library://album/175", c.optString("uri")); assertEquals("remplacer", c.optString("comment"))
        if (!a.isFinishing) a.finish()
        // La musique joue : un album s'ajoute à la suite, comme un titre (mêmes actions pour tous les types).
        val b = ouvrir(true)
        val avant2 = synchronized(commandes) { commandes.size }
        instrumentation.runOnMainSync {
            // Mêmes actions pour un titre et un album (télé : « Ajouter à la suite » en principal ; téléphone : « Jouer ensuite »).
            val principal = if (ReglagesTvActivity.estTele(ctx)) "Ajouter à la suite" else "Jouer ensuite"
            for (d in listOf("$principal : L'Air Du Vent", "Plus de choix : L'Air Du Vent", "$principal : L'air du vent", "Plus de choix : L'air du vent"))
                assertTrue("« $d » absent", vues(b.window.decorView).any { it.contentDescription == d })
            vues(b.window.decorView).first { it.contentDescription == "$principal : L'Air Du Vent" }.performClick()
        }
        attendre("album ajouté", b) { synchronized(commandes) { commandes.drop(avant2).any { x -> x.optString("type") == "album" && x.optString("comment") in setOf("ajouter", "ensuite") } } }
        SystemClock.sleep(900)
        // Les filtres : « Albums » puis la plateforme « YouTube Music » partent avec la recherche, et restent choisis.
        instrumentation.runOnMainSync { textes(b.window.decorView).first { it.text.toString() == "Albums" && it.isClickable }.performClick() }
        SystemClock.sleep(1200)
        instrumentation.runOnMainSync { textes(b.window.decorView).first { it.text.toString() == "YouTube Music" && it.isClickable }.performClick() }
        SystemClock.sleep(1200)
        assertEquals(setOf("album"), Party.filtresTypes(ctx)); assertEquals(setOf("ytmusic"), Party.filtresPlateformes(ctx))
        capturer("choix_filtres_" + resolution())
        b.finish()
        Party.poserFiltres(ctx, emptySet(), emptySet())
    }

    /** 1.86.13 (l'auteur : « 500 titres ajoutés que je n'ai pas choisis ») : une grosse playlist à la suite demande « Ajouter 500 titres ? ». */
    fun testGrossePlaylistDemandeConfirmation() {
        val b = instrumentation.startActivitySync(Intent(ctx, ChoixMusiqueActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("lecteur", "media_player.salon").putExtra("lecteur_nom", "Salon").putExtra("joue", true))
        attendre("playlists", b) { "La Famille" in it }
        val avant = synchronized(commandes) { commandes.size }
        val principal = if (ReglagesTvActivity.estTele(ctx)) "Ajouter à la suite" else "Jouer ensuite"
        instrumentation.runOnMainSync { vues(b.window.decorView).first { it.contentDescription == "$principal : La Famille" }.performClick() }
        attendre("demande envoyée", b) { synchronized(commandes) { commandes.drop(avant).any { x -> x.optString("uri") == "library://playlist/10" } } }
        SystemClock.sleep(1500)
        capturer("confirmation_500_titres_" + resolution())
        val envoyees = synchronized(commandes) { commandes.drop(avant).filter { it.optString("uri") == "library://playlist/10" } }
        assertEquals("partie sans attendre le « oui » : $envoyees", 1, envoyees.size)
        assertFalse(envoyees[0].optBoolean("confirme"))
        assertEquals("La Famille", envoyees[0].optString("titre"))  // le titre part dans le journal Musique
        b.finish()
    }

    fun testTeleQrInvites() {
        if (!ReglagesTvActivity.estTele(ctx)) return
        actif = true
        val a = lancer()
        attendre("plein écran", a) { "Le titre qui passe" in it && "Proposer un titre" in it }
        var bmp = false
        instrumentation.runOnMainSync {
            val v = vues(a.window.decorView).first { it.contentDescription == "Code à flasher pour proposer un titre" } as android.widget.ImageView
            bmp = v.drawable != null
        }
        assertTrue("QR absent", bmp)
        assertTrue(tout(a), "Encore après" in tout(a) && "proposé par un invité" in tout(a))
        SystemClock.sleep(800)
        verifierZoneSure(a, "party_tele_qr_" + resolution())
        a.finish()
    }

    /**
     * 1.87.1 (l'auteur : « dans la playlist les images ne se chargent pas ») : la pochette d'une playlist de la bibliothèque
     * locale arrive par Home Assistant (/party/image, référence donnée par lui, identifiant de l'appareil) ; l'adresse
     * http de Music Assistant (ici injoignable) n'est jamais lue par l'appareil.
     */
    fun testPochetteRelayeeParHomeAssistant() {
        val a = instrumentation.startActivitySync(Intent(ctx, ChoixMusiqueActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("lecteur", "media_player.salon").putExtra("lecteur_nom", "Salon"))
        attendre("playlists", a) { "Disney classique" in it }
        val fin = SystemClock.elapsedRealtime() + 8000
        var vue = false
        while (SystemClock.elapsedRealtime() < fin && !vue) {
            instrumentation.runOnMainSync { vue = vues(a.window.decorView).filterIsInstance<android.widget.ImageView>().any { it.tag == Vignettes.RELAIS + refDisney() && it.drawable != null } }
            if (!vue) SystemClock.sleep(150)
        }
        assertTrue("pochette relayée affichée", vue)
        val d = synchronized(imagesDemandees) { imagesDemandees.toList() }
        assertTrue("demande à Home Assistant : $d", d.any { it.optString("ref") == refDisney() && it.optString("id") == "essai_party" && it.optString("secret").isNotEmpty() })
        assertTrue("jamais d'adresse dans la demande : $d", d.none { it.toString().contains("192.0.2.1") })
        a.finish()
    }
}
