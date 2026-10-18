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
    // 1.91.0 : le saut dans le titre (action « position ») ; le faux lecteur suit après [delaiSautMs].
    @Volatile private var positionServeur = 84.0
    @Volatile private var dureeServeur: Int? = 221
    @Volatile private var sautServeur: Pair<Double, Long>? = null
    @Volatile private var delaiSautMs = 0L
    /** l'auteur (16/10) : Home Assistant lent à répondre à un saut (le saut suivant attend). */
    @Volatile private var delaiReponseSaut = 0L
    private val sauves = HashMap<String, Map<String, *>>()
    // Campagne Musique (15-10) : robustesse. Une réponse forcée aux commandes (429, 502…), un Home Assistant coupé
    // (la connexion se ferme sans réponse), le volume qui suit les commandes (et un autre appareil), les relevés d'état.
    @Volatile private var codeCommande: Int? = null
    /** Campagne (15-10) : la pochette relayée répond après ce délai (une image en cours de chargement). */
    @Volatile private var delaiImage = 0L
    @Volatile private var serveurCoupe = false
    /** l'auteur (16/10) : les derniers titres joués (Home Assistant 2.5.8, « file_passes »). */
    @Volatile private var passesServeur = 0
    /** l'auteur (16/10, 1.93.0 en production) : HA ne propose aucune enceinte et ne montre aucun lecteur (soirée oubliée). */
    @Volatile private var sansEnceinte = false
    /** Les demandes de musique (onglets, recherche) reçues par le faux HA. */
    private val musiqueDemandes = java.util.Collections.synchronizedList(ArrayList<JSONObject>())
    @Volatile private var volumeServeur = 0.4
    @Volatile private var relevesEtat = 0
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
        .put("ici", actif && ici).put("lecteur", if (sansEnceinte) "" else "media_player.salon").put("lecteur_nom", if (sansEnceinte) "" else nomEnceinte)
        .put("ma", true).put("lecteur_ma", true).put("choisir", true).put("invites_mode", "direct")
        .put("qr", if (actif) "https://ha.exemple.org/api/pc_parental/party/invite/JETON_ESSAI_0123456789abcdef" else "")
        .put("propositions", JSONArray().put(JSONObject().put("id", "p1").put("titre", "Proposition").put("artiste", "Invité").put("ts", 1)))
        .put("maintenant", if (rienNeJoue) JSONObject().put("etat", "idle") else JSONObject().put("etat", etatLecteur).put("titre", "Le titre qui passe").put("artiste", "Un artiste")
            .put("album", "Un album").put("volume", volumeServeur).put("duree", dureeServeur ?: JSONObject.NULL).put("position", positionServeur).put("position_ts", System.currentTimeMillis() / 1000.0)
            .put("pochette", "ab12"))
        .put("file", synchronized(fileServeur) { JSONArray().apply { fileServeur.forEach { put(it) } } })
        .put("file_passes", JSONArray().apply { for (k in 1..passesServeur) put(JSONObject().put("id", "p$k").put("titre", "Déjà joué $k").put("artiste", "Avant")) })
        .put("file_total", totalFige ?: synchronized(fileServeur) { fileServeur.size + 140 }).put("similaires", false).put("fin_clin", finClin)
        .put("enchainement", JSONObject().put("flux", true).put("fondu", false).put("duree", 8).put("precharge", true))
        .put("piloter", piloterForce ?: !ReglagesTvActivity.estTele(ctx)).put("relire", 30)
        .put("participer", participer).put("ferme", ferme)
        .put("ecrans", JSONArray().put(JSONObject().put("id", "t1").put("nom", "Téléviseur du salon")).put(JSONObject().put("id", "t2").put("nom", "Télé du bureau")))
        .put("lecteurs", if (sansEnceinte) JSONArray() else JSONArray().put(JSONObject().put("entity_id", "media_player.salon").put("nom", "Salon").put("etat", "playing").put("groupe", false).put("ma", true))
            .put(JSONObject().put("entity_id", "media_player.maison").put("nom", "Toute la maison").put("etat", "idle").put("groupe", true))
            .put(JSONObject().put("entity_id", "media_player.cle_mi").put("nom", "Clé TV Mi").put("etat", "unavailable").put("groupe", false).put("ma", true)
                .put("dispo", false).put("raison", "Hors ligne : allume-la ou vérifie qu'elle est branchée.")))
        .put("aide_lecteurs", "Une enceinte ou une télé manque ? Allume-la, puis dans Music Assistant : Réglages → Fournisseurs de lecteurs.")
        .put("saut", sautServeur?.let { (p, ts) -> JSONObject().put("position", p).put("il_y_a", (System.currentTimeMillis() - ts) / 1000.0) } ?: JSONObject.NULL)


    private fun pochette(): ByteArray {
        val b = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        for (x in 0 until 64) for (y in 0 until 64) b.setPixel(x, y, Color.rgb(200 - x * 2, 60 + y, 120 + x))
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun servir(s: Socket): Unit = s.use {
        if (serveurCoupe) return@use  // Home Assistant injoignable : la connexion se ferme sans réponse
        val entree = s.getInputStream().bufferedReader(Charsets.UTF_8)
        val premiere = entree.readLine() ?: return
        var longueur = 0
        while (true) { val l = entree.readLine() ?: break; if (l.isEmpty()) break; if (l.lowercase().startsWith("content-length:")) longueur = l.substringAfter(':').trim().toInt() }
        val buf = CharArray(longueur); var lu = 0
        while (lu < longueur) { val n = entree.read(buf, lu, longueur - lu); if (n < 0) break; lu += n }
        val corps = try { JSONObject(String(buf, 0, lu)) } catch (_: Exception) { JSONObject() }
        val (type, octets) = when {
            premiere.contains("/party/pochette") -> "image/png" to pochette()
            premiere.contains("/party/image") -> { imagesDemandees.add(corps); if (delaiImage > 0) SystemClock.sleep(delaiImage); "image/png" to (if (corps.optString("ref") == refDisney()) pochette() else ByteArray(0)) }
            premiere.contains("/party/commande") -> {
                synchronized(commandes) { commandes.add(corps) }
                codeCommande?.let { code ->
                    val o = JSONObject().put("ok", false).put("error", "force").put("retour", "Refus forcé $code : réessaie dans un instant.").toString().toByteArray()
                    s.getOutputStream().apply { write("HTTP/1.1 $code Erreur\r\nContent-Type: application/json\r\nContent-Length: ${o.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(o); flush() }
                    return@use
                }
                if (corps.optString("action") == "volume") volumeServeur = corps.optDouble("volume", volumeServeur)
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
                if (corps.optString("action") == "position") {
                    val p = corps.optDouble("position")
                    sautServeur = p to System.currentTimeMillis()
                    Thread { SystemClock.sleep(delaiSautMs); positionServeur = p }.start()
                    if (delaiReponseSaut > 0) SystemClock.sleep(delaiReponseSaut)
                }
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
            premiere.contains("/party/etat") -> { relevesEtat++; "application/json" to etat().toString().toByteArray() }
            // Le relevé de l'appli : comme Home Assistant, il dit que la tuile Musique est là (sinon elle s'efface).
            premiere.contains("/api/pc_parental/poll") -> "application/json" to JSONObject().put("party", JSONObject().put("tuile", true)).toString().toByteArray()
            premiere.contains("/party/musique") && corps.optString("texte") == "jenifer" -> "application/json" to JSONObject().put("ok", true).put("resultats", JSONObject().put("track", JSONArray()
                .put(JSONObject().put("uri", "library://track/180").put("nom", "L'air du vent").put("type", "track").put("artiste", "Jenifer"))
                .put(JSONObject().put("uri", "library://track/197").put("nom", "Medley électro (Live France / 2019)").put("type", "track").put("artiste", "Jenifer"))
                .put(JSONObject().put("uri", "ytmusic://track/x").put("nom", "Un titre vraiment très long pour voir comment la ligne se comporte sur un petit écran").put("type", "track").put("artiste", "Jenifer, Un invité au nom long").put("source", "YouTube Music").put("domaine", "ytmusic")))
                .put("album", JSONArray().put(JSONObject().put("uri", "library://album/175").put("nom", "L'Air Du Vent").put("type", "album").put("artiste", "Jenifer"))))
                .put("plateformes", JSONArray().put(JSONObject().put("domaine", "library").put("nom", "Bibliothèque")).put(JSONObject().put("domaine", "ytmusic").put("nom", "YouTube Music")))
                .put("conseil", "").toString().toByteArray()
            premiere.contains("/party/musique") -> "application/json" to (if (musiqueDemandes.add(corps) && corps.optString("quoi") == "chercher")
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

    companion object {
        /** Les écrans de l'appli créés et pas encore détruits (suivis pour tout le processus d'essai). */
        private val vivants = java.util.concurrent.atomic.AtomicInteger()
        @Volatile private var suiviPose = false
        private val suivi = object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: android.os.Bundle?) { vivants.incrementAndGet() }
            override fun onActivityDestroyed(a: Activity) { vivants.decrementAndGet() }
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: android.os.Bundle) {}
        }
    }

    /** Attend qu'aucun écran de l'appli ne soit encore en vie (5 s au plus), puis le repos du fil principal. */
    private fun attendreAucunEcran() {
        instrumentation.waitForIdleSync()
        val fin = SystemClock.elapsedRealtime() + 5000
        while (vivants.get() > 0 && SystemClock.elapsedRealtime() < fin) SystemClock.sleep(50)
        instrumentation.waitForIdleSync()
    }

    override fun setUp() {
        if (!suiviPose) synchronized(Companion) {
            if (!suiviPose) { (instrumentation.targetContext.applicationContext as android.app.Application).registerActivityLifecycleCallbacks(suivi); suiviPose = true }
        }
        super.setUp()
        CodeEssai.poser(instrumentation.targetContext)  // faille du 16-10 : un Décideur a toujours un code
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
        CodeEssai.rendre(instrumentation.targetContext)
        super.tearDown()
    }

    private fun resolution(): String { val d = ctx.resources.displayMetrics; return "${d.widthPixels}x${d.heightPixels}_police${ctx.resources.configuration.fontScale}" }

    private fun capturer(nom: String) {
        val b: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(dossier, "$nom.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun lancer(): Activity {
        // Intégration du 15/10 : l'essai précédent vient souvent de fermer un écran ; lancé avec CLEAR_TASK pendant sa
        // destruction, Android plantait dans sa propre transition (TopResumedActivityChangeItem, télé). On attend qu'il
        // ne reste plus aucun écran de l'appli en vie.
        attendreAucunEcran()
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
        // Règle de l'auteur (15-10) : le lecteur ne montre que l'essentiel.
        for (attendu in listOf("Musique", "Arrêté", "Le titre qui passe", "Un artiste")) assertTrue("« $attendu » absent : $t", attendu in t)
        for (interdit in listOf("Enceinte ou groupe", "Toute la maison", "Enchaînement")) assertFalse("« $interdit » encore sur le lecteur : $t", interdit in t)
        // 1.93.05 (l'auteur, 17-10, point 24) : la file est sous le lecteur du téléphone (avant : dans la page Réglages).
        for (attendu in listOf("À suivre", "Titre suivant", "Artiste 2")) assertTrue("« $attendu » absent de la file du lecteur : $t", attendu in t)
        SystemClock.sleep(1200)  // la pochette arrive
        capturer("party_telephone_arrete")
        // Tout le reste : la page Réglages (icône ⚙).
        reglages(a)
        attendre("page Réglages", a) { "Enceinte ou groupe" in it }
        val p = tout(a)
        for (attendu in listOf("Écran", "Enceinte ou groupe", "Salon", "Réglages de la musique")) assertTrue("« $attendu » absent des réglages : $p", attendu in p)
        capturer("party_telephone_reglages")
        touche(android.view.KeyEvent.KEYCODE_BACK)
        attendre("retour au lecteur", a) { "Lancer Musique sur Téléviseur du salon" in it }
        // Lancer : « En cours… » tout de suite, puis le retour du serveur ; le Mode Fête part avec.
        instrumentation.runOnMainSync { textes(a.window.decorView).first { it.text.toString() == "Lancer Musique sur Téléviseur du salon" }.performClick() }
        SystemClock.sleep(150)
        assertTrue("pas de retour immédiat : " + tout(a), "En cours…" in tout(a))
        attendre("retour du lancement", a) { "Musique lancée." in it }
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
            for (attendu in listOf("Le titre qui passe", "Choisir la musique")) assertTrue("« $attendu » absent : $t", attendu in t)
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
                // 1.91.0 : le fond animé couvre tout l'écran exprès (décor, sans texte ni focus).
                if (v is FondLecteurVue) continue
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
        for (attendu in listOf("File d'attente", "⚙ Réglages", "En cours", "Un artiste · Un album", "Titre suivant", "Quatrième titre", "Ajouter un titre", "Proposer un titre"))
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

    /** Téléphone : la page Réglages du lecteur (icône ⚙), où sont la file, l'écran, l'enceinte et la soirée. */
    private fun reglages(a: Activity) {
        attendreQue("icône réglages") { var ok = false; instrumentation.runOnMainSync { ok = vues(a.window.decorView).any { it.contentDescription?.startsWith("Réglages") == true && it.isShown } }; ok }
        instrumentation.runOnMainSync { vues(a.window.decorView).first { it.contentDescription?.startsWith("Réglages") == true && it.isShown }.performClick() }
        instrumentation.waitForIdleSync()
    }

    /** Touche la tuile (page Réglages) dont le texte commence par [debut]. */
    private fun tuile(a: Activity, debut: String) {
        instrumentation.runOnMainSync {
            val t = textes(a.window.decorView).first { it.text.toString().startsWith(debut) && it.isShown }
            (generateSequence(t as View) { it.parent as? View }.firstOrNull { it.isClickable } ?: t).performClick()
        }
    }

    /**
     * Choisit un élément d'un dialogue comme à la télécommande (▲▼ puis OK) : l'accessibilité ne déclenche pas le
     * choix d'une liste de dialogue. Le nœud doit être un élément (ou dans un élément) d'une liste.
     */
    private fun toucher(n: android.view.accessibility.AccessibilityNodeInfo) {
        fun liste(x: android.view.accessibility.AccessibilityNodeInfo) = generateSequence(x) { it.parent }.firstOrNull { it.className?.toString()?.contains("ListView") == true }
        fun element(x: android.view.accessibility.AccessibilityNodeInfo, l: android.view.accessibility.AccessibilityNodeInfo) = generateSequence(x) { it.parent }.first { it.parent == l }
        val l = liste(n) ?: run { n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK); return }
        val cible = (0 until l.childCount).indexOfFirst { l.getChild(it) == element(n, l) }
        fun focalise(): Int { l.refresh(); return (0 until l.childCount).indexOfFirst { l.getChild(it)?.let { c -> c.isFocused || c.isSelected } == true } }
        var ici = focalise()
        if (ici < 0) { instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN); SystemClock.sleep(200); ici = focalise().coerceAtLeast(0) }
        repeat(Math.abs(cible - ici)) { instrumentation.sendKeyDownUpSync(if (cible > ici) android.view.KeyEvent.KEYCODE_DPAD_DOWN else android.view.KeyEvent.KEYCODE_DPAD_UP); SystemClock.sleep(120) }
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
    }

    /** Un nœud de la fenêtre active (un dialogue) qui contient ce texte ; on attend qu'il paraisse (3 s au plus). */
    private fun noeud(t: String): android.view.accessibility.AccessibilityNodeInfo? {
        val ua = instrumentation.uiAutomation
        ua.serviceInfo = ua.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS }
        val fin = SystemClock.elapsedRealtime() + 3000
        while (SystemClock.elapsedRealtime() < fin) {
            instrumentation.uiAutomation.windows.mapNotNull { it.root }.plus(listOfNotNull(instrumentation.uiAutomation.rootInActiveWindow))
                .flatMap { it.findAccessibilityNodeInfosByText(t) ?: emptyList() }
                .firstOrNull { w -> generateSequence(w) { it.parent }.any { it.className?.toString()?.contains("ListView") == true || it.isClickable } }
                ?.let { return it }
            SystemClock.sleep(150)
        }
        fun textes(n: android.view.accessibility.AccessibilityNodeInfo?, l: ArrayList<String>): ArrayList<String> {
            n ?: return l; n.text?.let { l.add(it.toString()) }; for (i in 0 until n.childCount) textes(n.getChild(i), l); return l }
        android.util.Log.w("PartyEcransTest", "« $t » introuvable ; fenêtres : " + instrumentation.uiAutomation.windows.map { w -> textes(w.root, ArrayList()).take(12) })
        return null
    }

    fun testFileEtInvitesTelephone() {
        if (ReglagesTvActivity.estTele(ctx)) return
        actif = true
        val a = lancer()
        attendre("lecteur", a) { "Choisir la musique" in it }
        // Règle de l'auteur (15-10) : sur le lecteur, rien de la soirée ; l'icône ⚙ dit qu'une proposition attend.
        val t = tout(a)
        for (x in listOf("Proposition", "Je valide d'abord", "Nouveau code")) assertFalse("« $x » encore sur le lecteur : $t", x in t)
        // 1.93.05 (l'auteur, 17-10, point 24) : la file, avec le titre proposé par un invité, est sous le lecteur.
        assertTrue("« proposé par un invité » absent de la file du lecteur : $t", "proposé par un invité" in t)
        instrumentation.runOnMainSync { vues(a.window.decorView).first { it.contentDescription == "Retirer Titre suivant de la file" }.performClick() }
        attendre("retirer", a) { synchronized(commandes) { commandes.any { x -> x.optString("action") == "retirer" } } }
        assertEquals("q1", synchronized(commandes) { commandes.last { it.optString("action") == "retirer" } }.optString("element"))
        attendre("prêt", a) { "En cours…" !in it }
        reglages(a)
        attendre("page Réglages", a) { "Invités" in it && "Proposition" in it }
        val p = tout(a)
        for (x in listOf("Enchaînement", "Titres similaires à la fin", "Invités : Ajouter en fin de file", "Changer le code des invités", "Réglages de la musique"))
            assertTrue("« $x » absent : $p", x in p)
        capturer("party_telephone_invites")
        // l'auteur (16/10) : trois modes, choisis dans une liste (valider avant, jouer après, fin de file).
        tuile(a, "Invités : Ajouter en fin de file")
        toucher(noeud("Valider avant")!!)
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
        // 1. À l'ouverture (retours d'essai, 15-10) : le focus est sur la première touche du clavier de Vision.
        focusUnique(choix, "2_clavier") { (it as? TextView)?.text == "A" }
        instrumentation.runOnMainSync { (vues(choix.window.decorView).first { it is android.widget.EditText } as android.widget.EditText).setText("dancing") }
        attendre("résultats", choix) { "Dancing Queen" in it }
        // Du clavier aux résultats : la flèche droite depuis la dernière colonne de la grille.
        instrumentation.runOnMainSync { vues(choix.window.decorView).first { (it as? TextView)?.text == "G" }.requestFocus() }
        touche(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
        val apresRecherche = ArrayList<String>()
        // On descend jusqu'à « Lire » (rien ne joue : une seule action).
        repeat(16) {
            var t = ""; instrumentation.runOnMainSync { t = (choix.currentFocus as? TextView)?.text?.toString() ?: "" }
            apresRecherche.add(t)
            if (t == "Lire") return@repeat
            touche(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        }
        assertTrue("parcours à la télécommande : $apresRecherche", "Lire" in apresRecherche)
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
        val a = lancer()  // 1.93.05 (point 24) : la file est sous le lecteur, plus dans la page Réglages
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
                // l'auteur (16/10) : le lecteur dès l'accueil de Musique (soirée sur cette télé) ; « Ajouter un titre » a le focus.
                focusUnique(a, "clin_tele") { (it as? TextView)?.text == "Ajouter un titre" }
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
            attendre("plein écran", a) { "⚙ Réglages" in it }
            cliquer(a, "⚙ Réglages")
            toucher(noeud("Enceinte : Salon")!!)
            SystemClock.sleep(800)
            capturer("enceintes_tele_" + resolution())
            touche(android.view.KeyEvent.KEYCODE_BACK)
        } else {
            reglages(a)
            attendre("enceintes", a) { "Enceinte ou groupe" in it && "Fournisseurs de lecteurs" in it }
            val avant = synchronized(commandes) { commandes.size }
            tuile(a, "Enceinte ou groupe")
            toucher(noeud("Clé TV Mi · hors ligne")!!)
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
        val e0 = champ(a, "empreinteApps") as String
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
        // Campagne (15-10) : une fois en suite complète sur la télé (non reproduit en 35 essais) ; le message dit alors ce
        // qui a changé dans l'empreinte de l'accueil (« aucune » : rebâti sans changement, donc par une action à l'écran).
        if (Paquets.generation == g0) {
            val av = e0.split('|'); val ap = (champ(a, "empreinteApps") as String).split('|')
            val parts = ap.indices.filter { it >= av.size || av[it] != ap[it] }.ifEmpty { null }?.joinToString { "n° $it (« ${av.getOrNull(it)} » → « ${ap[it]} »)" } ?: "aucune"
            assertEquals("l'accueil a été rebâti par le mini-lecteur ; parties changées de l'empreinte : $parts", c0, champ(a, "constructions") as Int)
        }
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

    /** 1.86.6 (retour d'essai) : l'enchaînement, envoyé au lecteur de la soirée ; réponse de l'auteur (15-10) : derrière « ⚙ Réglages ». */
    fun testEnchainement() {
        actif = true; rienNeJoue = false
        val a = lancer()
        assertFalse("enchaînement encore affiché en permanence", "Fondu 8 s" in tout(a))
        if (ReglagesTvActivity.estTele(ctx)) {
            attendre("bouton Réglages", a) { "⚙ Réglages" in it }
            cliquer(a, "⚙ Réglages")
            val ligne = noeud("sans blanc")
            assertNotNull("ligne Enchaînement absente du panneau", ligne)
            toucher(ligne!!)
        } else {
            reglages(a)
            attendre("page Réglages", a) { "Enchaînement : sans blanc" in it }
            tuile(a, "Enchaînement : sans blanc")
        }
        SystemClock.sleep(600)
        capturer("enchainement_choix")
        val fondu = noeud("Fondu 8 s")
        assertNotNull("choix « Fondu 8 s » absent", fondu)
        toucher(fondu!!)
        attendre("fondu 8 s", a) { synchronized(commandes) { commandes.any { x -> x.optString("action") == "enchainement" && x.optString("mode") == "fondu" && x.optInt("duree") == 8 } } }
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
                // Intégration du 15/10 : attendre que l'accueil soit vraiment détruit avant de le relancer (CLEAR_TASK) ;
                // relancé pendant sa destruction, Android plantait dans sa propre transition (TopResumedActivityChangeItem).
                attendreAucunEcran()
            }
        } finally { Local.poserTheme(ctx, themeAvant); Local.poserModeSombre(ctx, modeAvant) }
    }

    /** 1.86.8 (l'auteur : « retirer revient en flash, le compteur revient au nombre initial ») ; et un refus dit pourquoi. */
    fun testRetirerSansRetourEnArriere() {
        actif = true; rienNeJoue = false; fileInitiale(6)
        try {
            val a = lancer()  // 1.93.05 (point 24) : sur le téléphone, la file est sous le lecteur
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
            var descriptions = ""
            instrumentation.runOnMainSync {
                val v = vues(a.window.decorView).firstOrNull { it.contentDescription == "Lire : L'air du vent" }
                if (v == null) descriptions = vues(a.window.decorView).mapNotNull { it.contentDescription?.toString() }.filter { "vent" in it.lowercase() }.joinToString(" | ")
                else v.requestFocus()
            }
            assertTrue("« Lire : L'air du vent » absent ; vu : $descriptions", descriptions.isEmpty())
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
        // Les filtres (retours d'essai, 15-10 : derrière « Filtre ») : type « Albums » puis source « YouTube Music » partent
        // avec la recherche, et restent choisis.
        fun appuyer(t: String) {
            val n = noeud(t); assertNotNull("« $t » absent", n)
            toucher(n!!)
            SystemClock.sleep(700)
        }
        cliquer(b, "Filtre")
        SystemClock.sleep(700)
        // Composant partagé (ChoixAdaptatif) : le type (6 options) en liste, les sources (2) en étiquettes cochables.
        appuyer("Type de résultat")
        appuyer("Albums")
        appuyer("YouTube Music")
        instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        SystemClock.sleep(1200)
        assertEquals(setOf("album"), Party.filtresTypes(ctx)); assertEquals(setOf("ytmusic"), Party.filtresPlateformes(ctx))
        capturer("choix_filtres_" + resolution())
        b.finish()
        Party.poserFiltres(ctx, emptySet(), emptySet())
    }

    /**
     * Règle de l'auteur (15-10) : le lecteur VRAIMENT très simple — pochette, titre, artiste, précédent / lecture-pause /
     * suivant, volume, recherche, icône réglages ; rien d'autre. Tout le reste dans la page Réglages. Captures en
     * 360 et 412 dp, police 100 et 130 %, et tablette ; aucun élément coupé.
     */
    fun testLecteurSimpleMultiTailles() {
        if (ReglagesTvActivity.estTele(ctx)) return
        actif = true; rienNeJoue = false
        try {
            for ((taille, densite, nom) in listOf(Triple("1080x2400", 480, "360dp"), Triple("1080x2400", 420, "412dp"), Triple("1600x2560", 320, "tablette")))
                for (police in listOf("1.0", "1.3")) {
                    shell("wm size $taille"); shell("wm density $densite"); shell("settings put system font_scale $police"); SystemClock.sleep(1500)
                    val a = lancer()
                    attendre("lecteur", a) { "Le titre qui passe" in it }
                    SystemClock.sleep(1200)
                    val t = tout(a)
                    for (interdit in listOf("Écran", "Enceinte ou groupe", "Toute la maison", "Enchaînement", "Invités", "Arrêter Musique", "Volume des enceintes"))
                        assertFalse("$nom $police : « $interdit » sur le lecteur : $t", interdit in t)
                    for (attendu in listOf("Le titre qui passe", "Un artiste", "Choisir la musique"))
                        assertTrue("$nom $police : « $attendu » absent : $t", attendu in t)
                    var desc = ""
                    instrumentation.runOnMainSync { desc = vues(a.window.decorView).mapNotNull { it.contentDescription?.toString() }.joinToString(" | ") }
                    for (d in listOf("Précédent", "Pause", "Suivant", "Monter le volume", "Baisser le volume")) assertTrue("$nom $police : « $d » absent : $desc", desc.contains(d))
                    assertTrue("$nom $police : icône réglages absente", desc.contains("Réglages"))
                    capturer("lecteur_simple_${nom}_police$police")
                    reglages(a)
                    attendre("page Réglages", a) { "Enceinte ou groupe" in it }
                    SystemClock.sleep(600)
                    capturer("lecteur_reglages_${nom}_police$police")
                    a.finish(); instrumentation.waitForIdleSync()
                }
        } finally { shell("wm size reset"); shell("wm density reset"); shell("settings put system font_scale 1.0") }
    }

    // ================================================================== campagne Musique (l'auteur, 15-10)

    private fun cliquerDesc(a: Activity, d: String) = instrumentation.runOnMainSync { vues(a.window.decorView).first { it.contentDescription == d && it.isShown }.performClick() }
    private fun nb(action: String) = synchronized(commandes) { commandes.count { it.optString("action") == action } }

    /** Rotation (téléphone) : le lecteur se reconstruit, sans plantage ni commande envoyée. */
    fun testCampagneRotation() {
        if (ReglagesTvActivity.estTele(ctx)) return
        actif = true; rienNeJoue = false
        val a = lancer()
        attendre("lecteur", a) { "Le titre qui passe" in it }
        val avant = synchronized(commandes) { commandes.size }
        var courante: Activity = a
        try {
            for (o in listOf(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)) {
                val m = instrumentation.addMonitor(PartyActivity::class.java.name, null, false)
                instrumentation.runOnMainSync { courante.requestedOrientation = o }
                courante = instrumentation.waitForMonitorWithTimeout(m, 6000) ?: courante
                instrumentation.removeMonitor(m)
                attendre("lecteur après rotation", courante) { "Le titre qui passe" in it && "Choisir la musique" in it }
                capturer("campagne_rotation_" + (if (o == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) "paysage" else "portrait"))
            }
            assertEquals("une rotation a envoyé une commande", avant, synchronized(commandes) { commandes.size })
        } finally {
            val c = courante
            instrumentation.runOnMainSync { c.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
            c.finish()
        }
    }

    /** Home Assistant injoignable puis de retour : une phrase, rien de figé ; au retour, le lecteur repart seul. */
    fun testCampagneHomeAssistantCoupePuisRevenu() {
        actif = true; rienNeJoue = false
        val a = lancer()
        attendre("lecteur", a) { "Le titre qui passe" in it }
        serveurCoupe = true
        instrumentation.runOnMainSync { instrumentation.callActivityOnPause(a); instrumentation.callActivityOnResume(a) }
        val erreur = Party.messageErreur(0, null)
        attendre("message de coupure", a) { erreur in it }
        capturer("campagne_ha_coupe")
        serveurCoupe = false
        instrumentation.runOnMainSync { instrumentation.callActivityOnPause(a); instrumentation.callActivityOnResume(a) }
        attendre("retour de Home Assistant", a) { erreur !in it && "Le titre qui passe" in it }
        a.finish()
    }

    /** Une commande refusée (429, 502) : la phrase s'affiche, rien ne reste « En cours… », l'ordre suivant passe. */
    fun testCampagneCommandeRefusee() {
        actif = true; rienNeJoue = false
        val a = lancer()
        attendre("lecteur", a) { "Le titre qui passe" in it }
        for (code in listOf(429, 502)) {
            codeCommande = code
            cliquerDesc(a, "Suivant")
            attendre("refus $code", a) { "Refus forcé $code" in it }
            attendre("plus en cours", a) { "En cours…" !in it }
        }
        codeCommande = null
        val avant = nb("suivant")
        cliquerDesc(a, "Suivant")
        attendre("suivant accepté", a) { nb("suivant") == avant + 1 }
        a.finish()
    }

    /** Appuis rapides répétés : un seul ordre en vol, jamais d'état bloqué ; l'appui d'après passe. */
    fun testCampagneAppuisRapides() {
        actif = true; rienNeJoue = false
        val a = lancer()
        attendre("lecteur", a) { "Le titre qui passe" in it }
        repeat(5) { cliquerDesc(a, "Suivant"); SystemClock.sleep(60) }
        SystemClock.sleep(300)
        assertEquals("plusieurs ordres en vol à la fois", 1, nb("suivant"))
        attendre("ordre fini", a) { "En cours…" !in it }
        SystemClock.sleep(400)
        cliquerDesc(a, "Suivant")
        attendre("appui suivant pris", a) { nb("suivant") == 2 }
        a.finish()
    }

    /** Volume : bornes 0 et 100 %, et le volume changé par un autre appareil s'affiche au relevé suivant. */
    fun testCampagneVolumeLimitesEtSynchro() {
        if (ReglagesTvActivity.estTele(ctx)) return
        actif = true; rienNeJoue = false
        volumeServeur = 0.98
        val a = lancer()
        attendre("volume 98", a) { "Volume 98 %" in it }
        cliquerDesc(a, "Monter le volume")
        attendre("volume envoyé", a) { nb("volume") == 1 }
        val haut = synchronized(commandes) { commandes.last { it.optString("action") == "volume" } }.optDouble("volume")
        assertTrue("volume au-delà de 100 % : $haut", haut <= 1.0)
        attendre("fini", a) { "En cours…" !in it }
        volumeServeur = 0.02
        instrumentation.runOnMainSync { instrumentation.callActivityOnPause(a); instrumentation.callActivityOnResume(a) }
        attendre("volume 2", a) { "Volume 2 %" in it }
        cliquerDesc(a, "Baisser le volume")
        attendre("volume envoyé", a) { nb("volume") == 2 }
        val bas = synchronized(commandes) { commandes.last { it.optString("action") == "volume" } }.optDouble("volume")
        assertTrue("volume sous 0 % : $bas", bas >= 0.0)
        // Un autre appareil règle 70 % : le lecteur l'affiche au relevé suivant.
        attendre("fini", a) { "En cours…" !in it }
        volumeServeur = 0.7
        instrumentation.runOnMainSync { instrumentation.callActivityOnPause(a); instrumentation.callActivityOnResume(a) }
        attendre("volume d'un autre appareil", a) { "Volume 70 %" in it }
        a.finish()
    }

    /** Précédent, puis changement d'enceinte en pleine lecture vers un groupe (réglages ⚙) ; une enceinte hors ligne n'envoie rien. */
    fun testCampagnePrecedentEtChangementDEnceinte() {
        actif = true; rienNeJoue = false
        val a = lancer()
        attendre("lecteur", a) { "Le titre qui passe" in it }
        cliquerDesc(a, ctx.getString(R.string.vuewidget_precedent))
        attendre("précédent envoyé", a) { nb("precedent") == 1 }  // le lecteur de la soirée : Home Assistant le connaît
        // La télé n'affiche pas « En cours… » : on attend la fin réelle de l'ordre (un seul ordre en vol à la fois).
        attendreQue("ordre terminé") { var o = "x"; instrumentation.runOnMainSync { o = champ(a, "occupe") as String }; o.isEmpty() }
        if (ReglagesTvActivity.estTele(ctx)) {
            cliquer(a, "⚙ Réglages")
            toucher(noeud("Enceinte : Salon")!!)
        } else {
            reglages(a)
            attendre("page Réglages", a) { "Enceinte ou groupe" in it }
            tuile(a, "Enceinte ou groupe")
        }
        toucher(noeud("Toute la maison")!!)
        attendre("enceinte changée pour le groupe", a) { derniere("enceinte")?.optString("lecteur") == "media_player.maison" }
        assertEquals("un seul changement d'enceinte", 1, nb("enceinte"))
        a.finish()
    }

    /**
     * l'auteur (16/10, 1.93.0 en production : « rien, Musique arrêtée ») : depuis un lecteur vide, trois entrées évidentes —
     * Bibliothèque (albums de Music Assistant, musique locale comprise), Playlists, Rechercher.
     */
    fun testLecteurVideEntreesEvidentes() {
        if (ReglagesTvActivity.estTele(ctx)) return
        actif = false; rienNeJoue = true
        val a = lancer()
        try {
            attendre("entrées", a) { "Bibliothèque" in it && "Playlists" in it && "Rechercher" in it }
            capturer("lecteur_vide_entrees_" + resolution())
            val m = instrumentation.addMonitor(ChoixMusiqueActivity::class.java.name, null, false)
            cliquer(a, "Bibliothèque")
            val choix = instrumentation.waitForMonitorWithTimeout(m, 6000)
            instrumentation.removeMonitor(m)
            assertNotNull("« Bibliothèque » n'ouvre pas le choix de la musique", choix)
            attendreQue("onglet Bibliothèque demandé") { synchronized(musiqueDemandes) { musiqueDemandes.any { it.optString("quoi") == "album" } } }
            choix!!.finish()
        } finally { a.finish() }
    }

    /** l'auteur (16/10) : aucune enceinte disponible — une phrase et le chemin vers les réglages, jamais un écran muet. */
    fun testAucuneEnceinteDisponible() {
        if (ReglagesTvActivity.estTele(ctx)) return
        actif = false; rienNeJoue = true; sansEnceinte = true
        try {
            val a = lancer()
            attendre("aucune enceinte", a) { "Aucune enceinte disponible pour l'instant." in it && "Réglages de la musique" in it && "Fournisseurs de lecteurs" in it }
            capturer("lecteur_sans_enceinte_" + resolution())
            a.finish()
        } finally { sansEnceinte = false }
    }

    // ================================================================== test réel de l'auteur (16/10)

    /** Téléphone (« super important ») : la sortie se lit et se change d'un appui ; la lecture est transférée. */
    fun testJaSortieDepuisLeTelephone() {
        if (ReglagesTvActivity.estTele(ctx)) return
        actif = true; rienNeJoue = false
        val a = lancer()
        try {
            attendre("sortie", a) { "🔊 Sortie : Salon ›" in it }
            capturer("ja_sortie_telephone_" + resolution())
            cliquer(a, "🔊 Sortie : Salon ›")
            toucher(noeud("Toute la maison")!!)
            attendre("transfert", a) { derniere("enceinte")?.optString("lecteur") == "media_player.maison" }
        } finally { a.finish() }
    }

    /** « Comme si j'appuyais, mais il ne se passe rien » : pendant un ordre, ⚙ Réglages répond toujours (télé). */
    fun testJaReglagesPendantUnOrdre() {
        if (!ReglagesTvActivity.estTele(ctx)) return
        actif = true; rienNeJoue = false; etatLecteur = "playing"; delaiLecteurMs = 4000
        val a = lancer()
        try {
            attendre("lecteur", a) { "⚙ Réglages" in it }
            var occ = ""
            instrumentation.runOnMainSync { vues(a.window.decorView).first { it.contentDescription == "Pause" }.performClick() }
            instrumentation.runOnMainSync { occ = champ(a, "occupe") as String }
            cliquer(a, "⚙ Réglages")
            assertNotNull("⚙ Réglages ignoré pendant l'ordre « $occ »", noeud("Enceinte : Salon"))
            touche(android.view.KeyEvent.KEYCODE_BACK)
        } finally { delaiLecteurMs = 0; a.finish() }
    }

    /** « Un petit cliquetis, et la lecture est revenue à zéro » : un seul saut à la fois, le dernier voulu gagne. */
    fun testJaSautsEnSerie() {
        if (ReglagesTvActivity.estTele(ctx)) return
        actif = true; rienNeJoue = false; delaiSautMs = 0
        val a = lancer()
        try {
            attendre("lecteur", a) { "Le titre qui passe" in it }
            // Le faux Home Assistant répond lentement aux sauts : trois glissés rapprochés pendant le premier.
            delaiReponseSaut = 1200
            for (q in listOf(0.3f, 0.6f, 0.9f)) { glisser(barre(a), listOf(q, q)); SystemClock.sleep(150) }
            SystemClock.sleep(300)
            assertEquals("un seul saut envoyé pendant le premier : ${sauts()}", 1, sauts().size)
            attendreQue("le dernier saut voulu part ensuite", 6000) { sauts().size >= 2 }
            SystemClock.sleep(1500)
            assertEquals("le saut du milieu a été fondu : ${sauts()}", 2, sauts().size)
            val d = 221.0
            assertEquals(0.3 * d, sauts()[0], 6.0); assertEquals(0.9 * d, sauts()[1], 6.0)
        } finally { delaiReponseSaut = 0; a.finish() }
    }

    /** Télé : le lecteur visible dès l'accueil de Musique ; « Ajouter un titre » ouvre la recherche dans la moitié droite. */
    fun testJaRechercheDansLaMoitieDroite() {
        if (!ReglagesTvActivity.estTele(ctx)) return
        actif = true; rienNeJoue = false
        val a = lancer()
        val m = instrumentation.addMonitor(ChoixMusiqueTeleActivity::class.java.name, null, false)
        try {
            attendre("lecteur", a) { "Ajouter un titre" in it && "Le titre qui passe" in it }
            cliquer(a, "Ajouter un titre")
            val r = instrumentation.waitForMonitorWithTimeout(m, 6000)
            assertNotNull("la recherche ne s'ouvre pas dans la moitié droite", r)
            attendre("recherche", r!!) { "Effacer" in it && "Bibliothèque" in it }
            var largeur = 0; var gauche = 0
            instrumentation.runOnMainSync { largeur = r.window.decorView.width; val p = IntArray(2); r.window.decorView.getLocationOnScreen(p); gauche = p[0] }
            val ecran = ctx.resources.displayMetrics.widthPixels
            assertTrue("fenêtre de $largeur px sur $ecran", largeur in (ecran * 0.5).toInt()..(ecran * 0.62).toInt())
            assertTrue("pas calée à droite : $gauche", gauche + largeur >= ecran - 2)
            // Le lecteur, dessous à gauche, reste vivant : son temps avance chaque seconde.
            fun temps(): String { var t = ""; instrumentation.runOnMainSync { t = vues(a.window.decorView).filterIsInstance<TextView>().firstOrNull { it.text.contains(" / ") && it.text.firstOrNull()?.isDigit() == true }?.text?.toString().orEmpty() }; return t }
            val t0 = temps()
            attendreQue("le lecteur reste vivant ($t0)", 6000) { temps().let { it.isNotEmpty() && it != t0 } }
            capturer("ja_recherche_moitie_" + resolution())
            r.finish()
        } finally { instrumentation.removeMonitor(m); a.finish() }
    }

    /** Télé : soirée ici mais rien ne joue — le lecteur reste affiché (code invités à gauche), la bibliothèque à droite. */
    fun testJaLecteurVideTele() {
        if (!ReglagesTvActivity.estTele(ctx)) return
        actif = true; rienNeJoue = true; fileInitiale(0)
        val a = lancer()
        try {
            attendre("lecteur vide", a) { "Ajouter un titre" in it && "Bibliothèque" in it && "La Famille" in it }
            var qr = false
            instrumentation.runOnMainSync { qr = vues(a.window.decorView).any { it.contentDescription == ctx.getString(R.string.partyecran_qr_description) } }
            assertTrue("le code invités n'est plus à gauche", qr)
            assertFalse("l'écran guide s'affiche encore", "Rechercher dans tout le catalogue" in tout(a))
            verifierZoneSure(a, "ja_lecteur_vide_tele_" + resolution())
        } finally { a.finish() }
    }

    /** La file défile et suit le titre en cours : les titres joués au-dessus, le titre en cours en vue. */
    fun testJaFileSuitLeTitreEnCours() {
        if (!ReglagesTvActivity.estTele(ctx)) return
        actif = true; rienNeJoue = false; passesServeur = 10
        val a = lancer()
        try {
            attendre("file", a) { "Déjà joué 10" in it && "Le titre qui passe" in it }
            SystemClock.sleep(600)
            var visible = false; var passeEnHaut = false
            instrumentation.runOnMainSync {
                val enCours = vues(a.window.decorView).filterIsInstance<TextView>().filter { it.text == "Le titre qui passe" }.minByOrNull { it.textSize }!!  // la ligne de la file
                val r = android.graphics.Rect(); visible = enCours.getGlobalVisibleRect(r)
                passeEnHaut = vues(a.window.decorView).filterIsInstance<TextView>().first { it.text == "Déjà joué 1" }.let { v -> val q = android.graphics.Rect(); !v.getGlobalVisibleRect(q) || q.top < r.top }
            }
            assertTrue("le titre en cours n'est pas en vue", visible)
            assertTrue("les titres joués ne sont pas au-dessus", passeEnHaut)
            capturer("ja_file_suit_" + resolution())
        } finally { passesServeur = 0; a.finish() }
    }

    /** Invités : trois modes (valider avant, jouer après, fin de file), dans les réglages de la soirée. */
    fun testJaModesInvites() {
        if (ReglagesTvActivity.estTele(ctx)) return
        actif = true; rienNeJoue = false
        val a = lancer()
        try {
            reglages(a)
            attendre("réglages", a) { "Invités : Ajouter en fin de file" in it }
            tuile(a, "Invités : ")
            toucher(noeud("Jouer après le titre en cours")!!)
            attendre("mode envoyé", a) { derniere("invites")?.optString("mode") == "ensuite" }
        } finally { a.finish() }
    }

    /** Arrière-plan puis reprise : l'état est relu (pause faite ailleurs pendant ce temps), sans commande envoyée. */
    fun testCampagneArrierePlanPuisReprise() {
        actif = true; rienNeJoue = false; etatLecteur = "playing"
        val a = lancer()
        attendre("lecteur", a) { "Le titre qui passe" in it }
        val releves = relevesEtat; val avant = synchronized(commandes) { commandes.size }
        instrumentation.runOnMainSync { instrumentation.callActivityOnPause(a); instrumentation.callActivityOnStop(a) }
        etatLecteur = "paused"
        SystemClock.sleep(1500)
        instrumentation.runOnMainSync { instrumentation.callActivityOnRestart(a); instrumentation.callActivityOnStart(a); instrumentation.callActivityOnResume(a) }
        attendreQue("état relu") { relevesEtat > releves }
        attendreQue("bouton Lecture (pause faite ailleurs)") { var ok = false; instrumentation.runOnMainSync { ok = vues(a.window.decorView).any { it.contentDescription == "Lecture" && it.isShown } }; ok }
        assertEquals("la reprise a envoyé une commande", avant, synchronized(commandes) { commandes.size })
        a.finish()
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
        if (!vue) {
            // Diagnostic (16/10, revenu une fois en suite complète) : ce que Vignettes et le faux HA savent à cet instant.
            fun champV(n: String) = try { Vignettes.javaClass.getDeclaredField(n).apply { isAccessible = true }.get(Vignettes)?.toString()?.take(300) } catch (x: Exception) { "?" }
            var vuesImg = ""
            instrumentation.runOnMainSync { vuesImg = vues(a.window.decorView).filterIsInstance<android.widget.ImageView>().filter { (it.tag as? String)?.startsWith(Vignettes.RELAIS) == true }.joinToString { "${it.tag}:${it.drawable != null}" } }
            val ecrans = try { Pochettes.javaClass.getDeclaredField("ecrans").apply { isAccessible = true }.get(Pochettes).toString() } catch (x: Exception) { "?" }
            fail("pochette relayée affichée — attendue ${Vignettes.RELAIS + refDisney()} ; vues : $vuesImg ; demandes HA : ${synchronized(imagesDemandees) { imagesDemandees.map { it.optString("ref") } }} ; " +
                "échecs : ${champV("echecs")} ; en cours : ${champV("enCours")} ; attentes : ${champV("attentes")} ; écrans Musique : $ecrans")
        }
        val d = synchronized(imagesDemandees) { imagesDemandees.toList() }
        assertTrue("demande à Home Assistant : $d", d.any { it.optString("ref") == refDisney() && it.optString("id") == "essai_party" && it.optString("secret").isNotEmpty() })
        assertTrue("jamais d'adresse dans la demande : $d", d.none { it.toString().contains("192.0.2.1") })
        a.finish()
    }

    /**
     * Campagne (15-10), cause de l'instabilité de testFondAnimeStylesTelephone : l'écran Musique fermé n'est détruit
     * qu'après l'ouverture du suivant, et sa fermeture vidait la pochette que le nouvel écran venait de charger (fond resté
     * aux couleurs du thème). Ici le premier écran est fermé exprès après le chargement du second.
     */
    fun testEcranPrecedentNeVidePasLesPochettes() {
        actif = true; rienNeJoue = false
        val a1 = lancer()
        attendre("premier écran", a1) { "Le titre qui passe" in it }
        // Le lecteur est « singleTop » : le second s'ouvre par-dessus un autre écran (le choix de la musique).
        val choix = instrumentation.startActivitySync(Intent(ctx, ChoixMusiqueActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("lecteur", "media_player.salon").putExtra("lecteur_nom", "Salon"))
        val m = instrumentation.addMonitor(PartyActivity::class.java.name, null, false)
        instrumentation.runOnMainSync { choix.startActivity(Intent(choix, PartyActivity::class.java)) }
        val a2 = instrumentation.waitForMonitorWithTimeout(m, 8000)
        instrumentation.removeMonitor(m)
        assertNotNull("le second lecteur ne s'est pas ouvert", a2)
        assertNotSame("le second lecteur n'est pas un nouvel écran", a1, a2)
        a2!!
        try {
            attendre("second écran", a2) { "Le titre qui passe" in it }
            attendreQue("pochette chargée") { Pochettes.garde("media_player.salon", "ab12") != null }
            instrumentation.runOnMainSync { a1.finish() }
            attendreQue("premier écran détruit") { a1.isDestroyed }
            assertNotNull("la fermeture du premier écran a vidé la pochette du second", Pochettes.garde("media_player.salon", "ab12"))
            // Retour sur l'écran : le fond reprend les couleurs de la pochette, pas celles du thème.
            instrumentation.runOnMainSync { instrumentation.callActivityOnPause(a2); instrumentation.callActivityOnResume(a2) }
            val th = Local.themeEffectif(ctx, Palette.APPLI)
            val duTheme = FondLecteur.couleurs(th.fond, th.halos, th.pourpre, th.sombre, null)
            val f = fond(a2)
            attendreQue("fond aux couleurs de la pochette") { var c = intArrayOf(); instrumentation.runOnMainSync { c = f.couleurs() }; !c.contentEquals(duTheme) }
        } finally { a2.finish(); choix.finish() }
    }

    /** Campagne (15-10), cause de l'instabilité de testPochetteRelayeeParHomeAssistant : vider les vignettes pendant un chargement oubliait la vue qui l'attendait. */
    fun testVignetteEnCoursArriveMalgreLeVidage() {
        Vignettes.vider(ctx)
        delaiImage = 1500
        val a = instrumentation.startActivitySync(Intent(ctx, ChoixMusiqueActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("lecteur", "media_player.salon").putExtra("lecteur_nom", "Salon"))
        try {
            attendre("playlists", a) { "Disney classique" in it }
            attendreQue("pochette demandée") { synchronized(imagesDemandees) { imagesDemandees.any { it.optString("ref") == refDisney() } } }
            // Un écran Musique se ferme pendant le chargement : tout est vidé.
            instrumentation.runOnMainSync { Vignettes.vider(ctx) }
            attendreQue("pochette posée après le vidage") { var vue = false; instrumentation.runOnMainSync { vue = vues(a.window.decorView).filterIsInstance<android.widget.ImageView>().any { it.tag == Vignettes.RELAIS + refDisney() && it.drawable != null } }; vue }
        } finally { delaiImage = 0; a.finish() }
    }

    // ------------------------------------------------------------------ 1.91.0 : saut dans le titre, fond animé

    private fun barre(a: Activity): BarreLecture {
        var b: BarreLecture? = null
        instrumentation.runOnMainSync { b = vues(a.window.decorView).filterIsInstance<BarreLecture>().firstOrNull() }
        return b ?: throw AssertionError("pas de barre de lecture : " + tout(a))
    }

    private fun fond(a: Activity): FondLecteurVue {
        var f: FondLecteurVue? = null
        instrumentation.runOnMainSync { f = vues(a.window.decorView).filterIsInstance<FondLecteurVue>().firstOrNull() }
        return f ?: throw AssertionError("pas de fond animé")
    }

    private fun sauts(): List<Double> = synchronized(commandes) { commandes.filter { it.optString("action") == "position" }.map { it.optDouble("position") } }

    private fun attendreQue(quoi: String, ms: Long = 8000, condition: () -> Boolean) {
        val fin = SystemClock.elapsedRealtime() + ms
        while (SystemClock.elapsedRealtime() < fin) { if (condition()) return; SystemClock.sleep(50) }
        throw AssertionError("délai dépassé : $quoi")
    }

    /** Un geste du doigt sur la barre, aux parts [parts] de sa longueur (la première : posé ; la dernière : levé). */
    private fun glisser(b: BarreLecture, parts: List<Float>, capture: String? = null) {
        val d = ctx.resources.displayMetrics.density
        val marge = b.retenue  // la course de la piste (Saut.xDePart) : la fin de la barre est la fin du titre
        val t0 = SystemClock.uptimeMillis()
        val p = IntArray(2)
        instrumentation.runOnMainSync { b.getLocationOnScreen(p) }
        parts.forEachIndexed { i, q ->
            val x = marge + q * (b.width - 2 * marge); val y = b.height / 2f
            val action = when (i) { 0 -> android.view.MotionEvent.ACTION_DOWN; parts.size - 1 -> android.view.MotionEvent.ACTION_UP; else -> android.view.MotionEvent.ACTION_MOVE }
            val ev = android.view.MotionEvent.obtain(t0, t0 + i * 60L, action, p[0] + x, p[1] + y, 0)
            instrumentation.sendPointerSync(ev)
            ev.recycle()
            if (capture != null && i == parts.size - 2) { SystemClock.sleep(150); capturer(capture) }
        }
    }

    /** 1.91.0 (retour d'essai) : au doigt, le curseur suit, la bulle montre le temps visé, le saut part au lâcher ; l'écran y reste. */
    fun testSautAuDoigtTelephone() {
        if (ReglagesTvActivity.estTele(ctx)) return
        actif = true; delaiSautMs = 2500
        val a = lancer()
        attendre("lecture affichée", a) { "Le titre qui passe" in it }
        val b = barre(a)
        assertTrue("la barre d'un décideur doit être un curseur", b.interactif)
        assertTrue("cible trop petite : ${b.height}", b.height >= (Ui.CIBLE_MIN * ctx.resources.displayMetrics.density).toInt() - 1)
        var classe = ""
        instrumentation.runOnMainSync { classe = b.accessibilityClassName.toString() }
        assertEquals("android.widget.SeekBar", classe)
        // Pendant le glisser : un aperçu (bulle), rien n'est encore envoyé.
        var pendant: Double? = null
        val d = ctx.resources.displayMetrics.density
        val t0 = SystemClock.uptimeMillis(); val p = IntArray(2)
        instrumentation.runOnMainSync { b.getLocationOnScreen(p) }
        fun ev(action: Int, q: Float, t: Long) = android.view.MotionEvent.obtain(t0, t, action, p[0] + Saut.xDePart(q, b.width.toFloat(), b.retenue), p[1] + b.height / 2f, 0)
        instrumentation.sendPointerSync(ev(android.view.MotionEvent.ACTION_DOWN, 0.2f, t0))
        instrumentation.sendPointerSync(ev(android.view.MotionEvent.ACTION_MOVE, 0.5f, t0 + 80))
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { pendant = b.apercu }
        assertEquals(110.5, pendant ?: -1.0, 1.0)
        assertTrue("saut envoyé avant le lâcher", sauts().isEmpty())
        // Le doigt au bout de la piste vise la fin du titre (sa dernière seconde), pas une position avant.
        instrumentation.sendPointerSync(ev(android.view.MotionEvent.ACTION_MOVE, 1f, t0 + 100))
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { pendant = b.apercu }
        assertEquals("bout de la piste = fin du titre", (b.duree ?: 0.0) - 1.0, pendant ?: -1.0, 0.6)
        instrumentation.sendPointerSync(ev(android.view.MotionEvent.ACTION_MOVE, 0.5f, t0 + 120))
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)
        capturer("saut_telephone_bulle_" + resolution())
        instrumentation.sendPointerSync(ev(android.view.MotionEvent.ACTION_UP, 0.5f, t0 + 160))
        attendreQue("saut envoyé") { sauts().isNotEmpty() }
        assertEquals(110.5, sauts().single(), 1.0)
        // L'écran montre tout de suite la position voulue, même si le lecteur annonce encore l'ancienne (84 s).
        SystemClock.sleep(1100)
        var part = 0f
        instrumentation.runOnMainSync { part = b.part }
        assertEquals(0.5f, part, 0.03f)
        assertTrue("le temps affiché doit suivre le saut : " + tout(a), Regex("1:5[0-3] / 3:41").containsMatchIn(tout(a)))
        capturer("saut_telephone_apres_" + resolution())
        a.finish()
    }

    /** Qui ne pilote pas (fermé par ses règles), ou une radio (sans durée) : la barre reste une simple barre. */
    fun testBarrePassiveQuandOnNePeutPasSauter() {
        if (ReglagesTvActivity.estTele(ctx)) return
        actif = true; ici = false; piloterForce = false; participer = false; ferme = "Fermé pour l'instant."
        try {
            val a = lancer()
            attendre("lecture affichée", a) { "Le titre qui passe" in it }
            val b = barre(a)
            assertFalse(b.interactif)
            glisser(b, listOf(0.1f, 0.6f, 0.6f))
            SystemClock.sleep(400)
            assertTrue("saut envoyé sans droit", sauts().isEmpty())
            a.finish()
            piloterForce = null; participer = true; ferme = ""; dureeServeur = null
            val r = lancer()
            attendre("radio affichée", r) { "Le titre qui passe" in it }
            assertFalse("une radio n'a pas de curseur", barre(r).interactif)
            r.finish()
        } finally { ici = true; piloterForce = null; participer = true; ferme = ""; dureeServeur = 221 }
    }

    /** Télé : la barre prend le focus ; gauche / droite par 10 s (un seul saut après plusieurs appuis), appui long plus vite. */
    fun testSautTelecommandeTele() {
        if (!ReglagesTvActivity.estTele(ctx)) return
        actif = true
        val a = lancer()
        attendre("plein écran", a) { "Le titre qui passe" in it }
        val b = barre(a)
        assertTrue(b.interactif)
        // Depuis lecture / pause, « haut » mène à la barre.
        attendreQue("focus initial") { var f = false; instrumentation.runOnMainSync { f = a.currentFocus != null }; f }
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_UP)
        instrumentation.waitForIdleSync()
        var focus = false
        instrumentation.runOnMainSync { focus = b.isFocused }
        if (!focus) { instrumentation.runOnMainSync { b.requestFocus() }; instrumentation.runOnMainSync { focus = b.isFocused } }
        assertTrue("la barre ne prend pas le focus", focus)
        capturer("saut_tele_focus_" + resolution())
        // Trois appuis courts : un seul saut, 30 s plus loin. Campagne Musique (15-10) : appuis injectés d'affilée, sans
        // attendre que l'écran soit au repos (sur un émulateur lent, cette attente dépassait le calme de 650 ms et le saut
        // partait entre deux appuis) ; « pas de saut avant le calme » n'est vérifié que si les appuis y ont tenu.
        val debut = SystemClock.uptimeMillis()
        repeat(3) { k ->
            val t = SystemClock.uptimeMillis()
            instrumentation.sendKeySync(android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_RIGHT, 0))
            instrumentation.sendKeySync(android.view.KeyEvent(t, t + 40, android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_RIGHT, 0))
            if (k < 2) SystemClock.sleep(120)
        }
        val duree = SystemClock.uptimeMillis() - debut
        // La cible affichée pendant l'aperçu : la position réelle au premier appui, plus 3 × 10 s.
        var vise: Double? = null
        val bb = barre(a); instrumentation.runOnMainSync { vise = bb.apercu }
        if (duree < Saut.CALME_MS - 150) assertTrue("saut parti avant le calme ($duree ms d'appuis)", sauts().isEmpty())
        capturer("saut_tele_apercu_" + resolution())
        attendreQue("un saut après les appuis") { sauts().isNotEmpty() }
        SystemClock.sleep(Saut.CALME_MS + 300)
        assertEquals("un seul saut pour trois appuis", 1, sauts().size)
        // Le saut envoyé est exactement la cible montrée (et non une valeur fixe : la lecture avance pendant l'essai).
        if (vise != null) assertEquals("saut différent de l'aperçu", vise!!, sauts()[0], 0.5)
        else assertTrue("saut à ${sauts()[0]} s (attendu ≈ 114 s)", sauts()[0] in 111.0..124.0)
        // Appui long vers la gauche : 6 × 10 s puis 30 s ; au relâché, un saut — jamais avant le début.
        val t0 = SystemClock.uptimeMillis()
        for (r in 0..14) {
            instrumentation.sendKeySync(android.view.KeyEvent(t0, t0 + r * 50L, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_LEFT, r))
        }
        instrumentation.sendKeySync(android.view.KeyEvent(t0, t0 + 800, android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_LEFT, 0))
        attendreQue("saut de l'appui long") { sauts().size >= 2 }
        assertEquals(0.0, sauts()[1], 0.01)
        // OK pendant l'aperçu envoie tout de suite. Après un saut, l'écran peut se rebâtir : on reprend la barre du moment,
        // et on s'assure qu'elle a le focus (sinon ► partait ailleurs et aucun saut ne venait).
        SystemClock.sleep(Saut.CALME_MS + 300)
        val b2 = barre(a)
        instrumentation.runOnMainSync { b2.requestFocus() }
        attendreQue("barre focalisée") { val bb = barre(a); var f = false; instrumentation.runOnMainSync { f = bb.isFocused }; f }
        val t1 = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(android.view.KeyEvent(t1, t1, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_RIGHT, 0))
        instrumentation.sendKeySync(android.view.KeyEvent(t1, t1 + 40, android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_RIGHT, 0))
        instrumentation.sendKeySync(android.view.KeyEvent(t1 + 60, t1 + 60, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_CENTER, 0))
        instrumentation.sendKeySync(android.view.KeyEvent(t1 + 60, t1 + 100, android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_CENTER, 0))
        attendreQue("saut validé par OK", 1500) { sauts().size >= 3 }
        // Lecteur d'écran : « avancer » fait un pas de 10 s.
        SystemClock.sleep(Saut.CALME_MS + 300)
        val b3 = barre(a)
        instrumentation.runOnMainSync { b3.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null) }
        attendreQue("saut par l'accessibilité") { sauts().size >= 4 }
        verifierZoneSure(a, "saut_tele_zone_sure_" + resolution())
        a.finish()
    }

    /** Le lecteur Classique de cet appareil (« · Vision ») recale son horloge sur le saut annoncé par Home Assistant. */
    fun testLEtatDonneLeSaut() {
        val o = JSONObject().put("ok", true).put("saut", JSONObject().put("position", 95.3).put("il_y_a", 1.25))
        val e = Party.analyser(o)
        assertEquals(95.3, e.saut!!.position, 0.0001)
        assertEquals(1.25, e.saut!!.ilYa, 0.0001)
        assertNull(Party.analyser(JSONObject().put("saut", JSONObject.NULL)).saut)
        assertNull(Party.analyser(JSONObject().put("saut", JSONObject().put("position", -2).put("il_y_a", 1))).saut)
    }

    /** 1.91.0 (retour d'essai) : le fond animé — chaque style, réglable dans l'écran Musique, aux couleurs du thème ou de la pochette. */
    fun testFondAnimeStylesTelephone() {
        if (ReglagesTvActivity.estTele(ctx)) return
        actif = true
        for (s in FondLecteur.Style.values()) {
            FondLecteur.poserStyle(ctx, s)
            val a = lancer()
            attendre("lecture affichée", a) { "Le titre qui passe" in it }
            val f = fond(a)
            assertEquals(s, f.style)
            SystemClock.sleep(1600)  // la pochette arrive, ses couleurs passent en fondu
            capturer("fond_telephone_${s.cle}_" + resolution())
            a.finish(); instrumentation.waitForIdleSync()
        }
        // Retours de l'auteur (15-10) : le fond se règle dans les paramètres Vision (section Musique) ; l'écran Musique n'a
        // plus que « ⚙ Réglages ». Au retour sur l'écran, le fond suit le réglage sans rebâtir l'écran.
        FondLecteur.poserStyle(ctx, FondLecteur.Style.ONDES)
        val a = lancer()
        attendre("lecteur", a) { "Le titre qui passe" in it }
        assertFalse("réglages du fond encore sur l'écran Musique : " + tout(a), "Particules" in tout(a) || "Réagit au son" in tout(a))
        FondLecteur.poserStyle(ctx, FondLecteur.Style.AURORE); FondLecteur.poserIntensite(ctx, FondLecteur.Intensite.VIVE)
        instrumentation.runOnMainSync { instrumentation.callActivityOnPause(a) }
        assertFalse(fond(a).actif)
        instrumentation.runOnMainSync { instrumentation.callActivityOnResume(a) }
        assertTrue(fond(a).actif)
        assertEquals(FondLecteur.Style.AURORE, fond(a).style)
        assertEquals(FondLecteur.Intensite.VIVE, fond(a).intensite)
        // Les couleurs de la pochette (dégradé rouge-violet de l'essai) remplacent celles du thème.
        val th = Local.themeEffectif(ctx, Palette.APPLI)
        val duTheme = FondLecteur.couleurs(th.fond, th.halos, th.pourpre, th.sombre, null)
        val f = fond(a)
        var vues2 = ""
        try { attendreQue("couleurs de la pochette") { var c = intArrayOf(); instrumentation.runOnMainSync { c = f.couleurs(); vues2 = c.joinToString { Integer.toHexString(it) } }; !c.contentEquals(duTheme) } }
        catch (x: AssertionError) { capturer("fond_echec_" + resolution()); throw AssertionError("couleurs du fond = $vues2 ; thème = ${duTheme.joinToString { Integer.toHexString(it) }} ; pochette = ${FondLecteur.pochette(ctx)} ; gardée = ${Pochettes.garde("media_player.salon", "ab12") != null} ; clés = ${(Pochettes.javaClass.getDeclaredField("cache").apply { isAccessible = true }.get(Pochettes) as Map<*, *>).keys} ; essais = ${(Pochettes.javaClass.getDeclaredField("essais").apply { isAccessible = true }.get(Pochettes) as Map<*, *>)} ; en cours = ${Pochettes.javaClass.getDeclaredField("enCours").apply { isAccessible = true }.get(Pochettes)}") }
        FondLecteur.poserIntensite(ctx, FondLecteur.Intensite.MOYENNE)
        a.finish()
    }

    /** « Supprimer les animations » du système : une image fixe, et l'écran le dit. */
    fun testFondFixeQuandLesAnimationsSontReduites() {
        if (ReglagesTvActivity.estTele(ctx)) return
        actif = true
        FondLecteur.poserStyle(ctx, FondLecteur.Style.PARTICULES)
        val avant = instrumentation.uiAutomation.executeShellCommand("settings get global animator_duration_scale").let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim() }
        instrumentation.uiAutomation.executeShellCommand("settings put global animator_duration_scale 0").close()
        try {
            attendreQue("animations réduites", 3000) { !Anim.actif(ctx) }
            val a = lancer()
            // L'aide « animations réduites » est dans le choix du fond (paramètres Vision) ; ici, le fond reste fixe.
            attendre("lecteur", a) { "Le titre qui passe" in it }
            val f = fond(a)
            SystemClock.sleep(600)
            val n0 = f.images; SystemClock.sleep(1500)
            assertTrue("le fond s'anime encore (${f.images - n0} images)", f.images - n0 <= 2)
            a.finish()
        } finally {
            instrumentation.uiAutomation.executeShellCommand("settings put global animator_duration_scale " + (if (avant == "null" || avant.isEmpty()) "1" else avant)).close()
        }
    }

    /** Télé : le fond animé de l'écran Musique (réglé dans les paramètres Vision), à une cadence de télé. */
    fun testFondAnimeTele() {
        if (!ReglagesTvActivity.estTele(ctx)) return
        actif = true
        FondLecteur.poserStyle(ctx, FondLecteur.Style.ONDES); FondLecteur.poserIntensite(ctx, FondLecteur.Intensite.MOYENNE)
        val a = lancer()
        // Retours de l'auteur (15-10) : le choix du fond est dans les paramètres Vision ; l'écran Musique a « ⚙ Réglages ».
        attendre("bouton Réglages", a) { "⚙ Réglages" in it }
        val f = fond(a)
        SystemClock.sleep(1200)
        val n0 = f.images; SystemClock.sleep(1000)
        val parSeconde = f.images - n0
        assertTrue("cadence trop haute pour une télé : $parSeconde", parSeconde <= FondLecteur.cadence(true, false) + 2)
        assertTrue("le fond ne s'anime pas : $parSeconde", parSeconde >= 3)
        capturer("fond_tele_ondes_" + resolution())
        for (s in listOf(FondLecteur.Style.PARTICULES, FondLecteur.Style.AURORE, FondLecteur.Style.DEGRADE)) {
            instrumentation.runOnMainSync { f.style = s }
            SystemClock.sleep(700)
            capturer("fond_tele_${s.cle}_" + resolution())
        }
        a.finish()
    }
}
