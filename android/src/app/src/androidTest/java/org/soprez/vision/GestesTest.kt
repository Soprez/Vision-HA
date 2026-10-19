// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 1.77.0 — règle du projet : un clic met l'affichage à jour tout de suite. Pour chaque écran qui répond à une demande :
 * « En cours… » au clic, la demande qui disparaît dès la réponse, le résultat dit ; en cas d'échec, les boutons
 * reviennent et le refus s'explique en clair. Un petit serveur local (dans le test) tient la réponse le temps de
 * vérifier l'état « en cours », puis répond oui ou non.
 *
 *   adb shell am instrument -w -e class org.soprez.vision.GestesTest org.soprez.vision.test/android.test.InstrumentationTestRunner
 */
class GestesTest : InstrumentationTestCase() {
    private val ctx get() = instrumentation.targetContext

    // ------------------------------------------------------------------ le serveur d'essai
    private lateinit var serveur: ServerSocket
    @Volatile private var porte = CountDownLatch(1)
    @Volatile private var reponse: Pair<Int, String> = 200 to "{}"
    @Volatile private var dernierCorps: JSONObject? = null

    private fun demarrerServeur() {
        serveur = ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"))
        Thread {
            while (!serveur.isClosed) {
                val s = try { serveur.accept() } catch (_: Exception) { break }
                // Une réponse arrivée après l'abandon du client (« broken pipe ») ne fait pas tomber le test.
                Thread { try { servir(s) } catch (_: Exception) {} }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun servir(s: Socket): Unit = s.use {
        val entree = s.getInputStream().bufferedReader(Charsets.UTF_8)
        val premiere = entree.readLine() ?: return
        var longueur = 0
        while (true) { val l = entree.readLine() ?: break; if (l.isEmpty()) break; if (l.lowercase().startsWith("content-length:")) longueur = l.substringAfter(':').trim().toInt() }
        val buf = CharArray(longueur); var lu = 0
        while (lu < longueur) { val n = entree.read(buf, lu, longueur - lu); if (n < 0) break; lu += n }
        val corps = try { JSONObject(String(buf, 0, lu)) } catch (_: Exception) { JSONObject() }
        // Seuls les gestes attendent la porte ; les relectures (accueil, gestion) échouent tout de suite : l'écran
        // garde ce que le test lui a donné.
        val (code, texte) = if (corps.has("action") || premiere.contains("/reponse")) {
            dernierCorps = corps
            porte.await(15, TimeUnit.SECONDS)
            reponse
        } else 503 to "{}"
        val octets = texte.toByteArray(Charsets.UTF_8)
        s.getOutputStream().apply {
            write("HTTP/1.1 $code X\r\nContent-Type: application/json\r\nContent-Length: ${octets.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(octets); flush()
        }
        Unit
    }

    // ------------------------------------------------------------------ le décor
    private val sauves = HashMap<String, Map<String, *>>()

    override fun setUp() {
        super.setUp()
        CodeEssai.poser(instrumentation.targetContext)  // faille du 16-10 : un Décideur a toujours un code
        demarrerServeur()
        for (n in listOf("vision", "profil")) sauves[n] = HashMap(ctx.getSharedPreferences(n, 0).all)
        val cfg = Config(ctx)
        cfg.id = "essai_gestes"; cfg.secret = "s".repeat(32); cfg.nom = "Téléphone d'essai"
        val adresse = "http://127.0.0.1:${serveur.localPort}"
        cfg.urlInterne = adresse; cfg.urlExterne = ""; cfg.urlActive = adresse
        // L'empreinte vient d'être donnée : les gestes partent sans boîte de dialogue.
        Garde::class.java.getDeclaredField("jusqua").apply { isAccessible = true }.setLong(Garde, SystemClock.elapsedRealtime() + 600_000L)
        // 16-10 : accorder exige l'empreinte à chaque fois ; ici, l'empreinte réussie est simulée.
        Garde::class.java.getDeclaredField("preuveEssai").apply { isAccessible = true }.setBoolean(Garde, true)
    }

    override fun tearDown() {
        porte.countDown()
        try { serveur.close() } catch (_: Exception) {}
        for ((n, v) in sauves) ctx.getSharedPreferences(n, 0).edit().clear().apply {
            v.forEach { (k, x) -> when (x) { is String -> putString(k, x); is Boolean -> putBoolean(k, x); is Int -> putInt(k, x); is Long -> putLong(k, x); is Float -> putFloat(k, x); is Set<*> -> @Suppress("UNCHECKED_CAST") putStringSet(k, x as Set<String>) } }
        }.commit()
        Garde.fermer()
        Garde::class.java.getDeclaredField("preuveEssai").apply { isAccessible = true }.setBoolean(Garde, false)
        CodeEssai.rendre(instrumentation.targetContext)
        super.tearDown()
    }

    private fun demande(id: String, genre: String = "apps") = JSONObject().put("prenom", "Alex").put("libelle", if (genre == "temps") "15 min de plus" else "Roblox")
        .put("genre", genre).put("pc", "e1").put("id", id).put("minutes", 15).put("ts", System.currentTimeMillis() / 1000)

    private fun lancer(intent: Intent): Activity {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val a = instrumentation.startActivitySync(intent)
        instrumentation.waitForIdleSync(); SystemClock.sleep(800)
        return a
    }

    private fun textes(v: View, sortie: ArrayList<TextView> = ArrayList()): ArrayList<TextView> {
        if (v is TextView) sortie.add(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) textes(v.getChildAt(i), sortie)
        return sortie
    }

    /** Le texte tel qu'on le lit : sans les espaces nulles de la coupure des noms longs (Ui.coupable). */
    private fun lisible(v: TextView) = v.text.toString().replace(Coupure.ESPACE_NULLE.toString(), "")

    private fun bouton(racine: View, texte: String): TextView =
        textes(racine).firstOrNull { lisible(it) == texte && it.isClickable } ?: throw AssertionError("bouton « $texte » absent : " + textes(racine).joinToString(" | ") { it.text })

    private fun champ(o: Any, nom: String): Any? {
        var c: Class<*>? = o.javaClass
        while (c != null) { try { return c.getDeclaredField(nom).apply { isAccessible = true }.get(o) } catch (_: NoSuchFieldException) { c = c.superclass } }
        throw AssertionError("champ $nom")
    }

    private fun poser(o: Any, nom: String, v: Any?) {
        var c: Class<*>? = o.javaClass
        while (c != null) { try { c.getDeclaredField(nom).apply { isAccessible = true }.set(o, v); return } catch (_: NoSuchFieldException) { c = c.superclass } }
        throw AssertionError("champ $nom")
    }

    private fun appeler(o: Any, nom: String): Any? =
        o.javaClass.declaredMethods.first { it.name == nom && it.parameterTypes.isEmpty() }.apply { isAccessible = true }.invoke(o)

    private fun attendre(quoi: String, condition: () -> Boolean) {
        val fin = SystemClock.elapsedRealtime() + 8000
        while (SystemClock.elapsedRealtime() < fin) { var ok = false; instrumentation.runOnMainSync { ok = condition() }; if (ok) return; SystemClock.sleep(100) }
        throw AssertionError("délai dépassé : $quoi")
    }

    // ------------------------------------------------------------------ l'accueil : « Demande en attente »

    /** Le widget de l'accueil d'un décideur, construit avec ces demandes. */
    private fun widgetAccueil(a: Activity): View {
        var v: View? = null
        instrumentation.runOnMainSync { v = appeler(a, "widgetDemandes") as View }
        return v!!
    }

    fun testAccueilAccepterUneDemande() {
        val a = lancer(Intent(ctx, LanceurActivity::class.java))
        instrumentation.runOnMainSync { poser(a, "donnees", JSONObject().put("demandes", JSONArray().put(demande("d1")).put(demande("t1", "temps")))) }
        val w = widgetAccueil(a)
        porte = CountDownLatch(1); reponse = 200 to """{"ok":true,"retour":"Nolan a autorisé Roblox pour 1 h."}"""
        val accepter = bouton(w, "Accepter")
        instrumentation.runOnMainSync { accepter.performClick() }
        // Tout de suite : « En cours… », bouton désactivé ; le serveur a bien reçu la réponse.
        attendre("« En cours… » au clic") { accepter.text.toString() == Geste.EN_COURS && !accepter.isEnabled }
        attendre("geste reçu par le serveur") { dernierCorps?.optString("decision") == "temporaire" && dernierCorps?.optString("demande") == "d1" }
        SystemClock.sleep(400)
        attendre("toujours en cours pendant que le serveur réfléchit") { accepter.text.toString() == Geste.EN_COURS }
        porte.countDown()
        // La réponse : la demande disparaît, la suivante prend sa place, le résultat est dit.
        attendre("demande retirée") { (champ(a, "donnees") as JSONObject).optJSONArray("demandes")?.length() == 1 }
        val apres = widgetAccueil(a)
        val t = textes(apres).joinToString(" | ") { it.text }
        assertTrue(t, t.contains("Nolan a autorisé Roblox") && t.contains("15 min de plus") && !t.contains("Roblox |"))
        assertTrue(bouton(apres, "Accepter").isEnabled)
    }

    fun testAccueilEchecLesBoutonsReviennent() {
        val a = lancer(Intent(ctx, LanceurActivity::class.java))
        instrumentation.runOnMainSync { poser(a, "donnees", JSONObject().put("demandes", JSONArray().put(demande("d1")))) }
        val w = widgetAccueil(a)
        porte = CountDownLatch(1); reponse = 500 to "{}"
        val refuser = bouton(w, "Refuser")
        instrumentation.runOnMainSync { refuser.performClick() }
        attendre("« En cours… » au clic") { textes(w).any { it.text.toString() == Geste.EN_COURS } || !refuser.isEnabled }
        porte.countDown()
        attendre("fin du geste") { champ(a, "demandeEnCours") == "" }
        assertEquals(1, (champ(a, "donnees") as JSONObject).optJSONArray("demandes")!!.length())
        val apres = widgetAccueil(a)
        val t = textes(apres).joinToString(" | ") { it.text }
        assertTrue(t, t.contains("rien n'a changé") && !Regex("\\(\\d{3}\\)").containsMatchIn(t))
        assertTrue(bouton(apres, "Refuser").isEnabled && bouton(apres, "Accepter").isEnabled)
    }

    fun testAccueilDemandeDeTempsAccordee() {
        val a = lancer(Intent(ctx, LanceurActivity::class.java))
        instrumentation.runOnMainSync { poser(a, "donnees", JSONObject().put("demandes", JSONArray().put(demande("t1", "temps")))) }
        val w = widgetAccueil(a)
        porte = CountDownLatch(1); reponse = 200 to """{"ok":true}"""
        val accepter = bouton(w, "Accepter")
        instrumentation.runOnMainSync { accepter.performClick() }
        attendre("« En cours… »") { accepter.text.toString() == Geste.EN_COURS }
        attendre("réponse de temps envoyée") { dernierCorps?.optInt("minutes") == 15 && dernierCorps?.optString("demande") == "t1" }
        porte.countDown()
        attendre("demande retirée") { (champ(a, "donnees") as JSONObject).optJSONArray("demandes")?.length() == 0 }
        val t = textes(widgetAccueil(a)).joinToString(" | ") { it.text }
        assertTrue(t, t.contains("15 min de plus") && t.contains("Aucune demande en attente"))
    }

    // ------------------------------------------------------------------ la gestion : liste des demandes

    fun testGestionRepondreRetireLaDemande() {
        val a = lancer(Intent(ctx, ParentsActivity::class.java))
        instrumentation.runOnMainSync {
            poser(a, "demandes", JSONArray().put(demande("d1").put("cle", "com.roblox.client").put("nom", "com.roblox.client")))
            poser(a, "erreur", ""); appeler(a, "dessiner")
        }
        porte = CountDownLatch(1); reponse = 200 to """{"ok":true,"retour":"Nolan a autorisé Roblox pour 1 h."}"""
        var b: TextView? = null
        instrumentation.runOnMainSync { b = bouton(a.window.decorView, "1 h"); b!!.performClick() }
        attendre("« En cours… » au clic") { b!!.text.toString() == Geste.EN_COURS && !b!!.isEnabled }
        porte.countDown()
        attendre("demande retirée de l'écran") { (champ(a, "demandes") as JSONArray).length() == 0 }
    }

    fun testGestionEchecBoutonRelache() {
        val a = lancer(Intent(ctx, ParentsActivity::class.java))
        instrumentation.runOnMainSync {
            poser(a, "demandes", JSONArray().put(demande("d1").put("cle", "com.roblox.client").put("nom", "com.roblox.client")))
            poser(a, "erreur", ""); appeler(a, "dessiner")
        }
        porte = CountDownLatch(1); reponse = 403 to """{"ok":false,"error":"gerer"}"""
        var b: TextView? = null
        instrumentation.runOnMainSync { b = bouton(a.window.decorView, "Refuser"); b!!.performClick() }
        attendre("« En cours… » au clic") { b!!.text.toString() == Geste.EN_COURS }
        porte.countDown()
        attendre("bouton rendu") { b!!.text.toString() == "Refuser" && b!!.isEnabled }
        assertEquals(1, (champ(a, "demandes") as JSONArray).length())
    }

    // ------------------------------------------------------------------ la notification ouverte (écran Message)

    fun testMessageAttendLaReponseAvantDeFermer() {
        val acces = demande("d1").put("cle", "com.roblox.client")
        val a = lancer(MessageActivity.intention(ctx, "Demande d'accès", "Alex demande Roblox.", false, null, acces))
        porte = CountDownLatch(1); reponse = 200 to """{"ok":true,"retour":"C'est fait."}"""
        var b: TextView? = null
        instrumentation.runOnMainSync { b = bouton(a.window.decorView, "1 h"); b!!.performClick() }
        attendre("« En cours… » et l'écran reste") { b!!.text.toString() == Geste.EN_COURS && !a.isFinishing }
        porte.countDown()
        attendre("écran fermé après la réponse") { a.isFinishing }
    }

    fun testMessageEchecResteOuvert() {
        val acces = demande("d1").put("cle", "com.roblox.client")
        val a = lancer(MessageActivity.intention(ctx, "Demande d'accès", "Alex demande Roblox.", false, null, acces))
        porte = CountDownLatch(1); reponse = 502 to "{}"
        var b: TextView? = null
        instrumentation.runOnMainSync { b = bouton(a.window.decorView, "Refuser"); b!!.performClick() }
        attendre("« En cours… »") { b!!.text.toString() == Geste.EN_COURS }
        porte.countDown()
        attendre("bouton rendu, écran ouvert") { b!!.text.toString() == "Refuser" && b!!.isEnabled && !a.isFinishing }
    }

    // ------------------------------------------------------------------ 1.77.0 : le domaine d'un site, partout

    private val siteVide = Regex("""^Site\s*:\s*$""")

    private fun site() = JSONObject().put("prenom", "Charlie").put("genre", "sites").put("pc", "e1").put("id", "s1")
        .put("cle", "exemple-devoirs.fr").put("nom", "https://www.exemple-devoirs.fr/cours").put("libelle", "").put("ts", System.currentTimeMillis() / 1000)

    /** Les textes de l'écran (lus sur le fil principal), vérifiés hors de lui : un échec se rapporte, sans planter. */
    private fun sansSiteVide(racine: View): String {
        var t: List<String> = emptyList()
        instrumentation.runOnMainSync { t = textes(racine).map { lisible(it).trim() } }
        assertFalse("« Site : » sans domaine : $t", t.any { siteVide.matches(it) })
        return t.joinToString(" | ")
    }

    fun testGestionSiteEtTempsBienPresentes() {
        val a = lancer(Intent(ctx, ParentsActivity::class.java))
        instrumentation.runOnMainSync {
            poser(a, "demandes", JSONArray().put(site()).put(demande("t1", "temps").put("prenom", "Charlie")))
            poser(a, "erreur", ""); appeler(a, "dessiner")
        }
        instrumentation.waitForIdleSync()
        val t = sansSiteVide(a.window.decorView)
        assertTrue(t, t.contains("Site : exemple-devoirs.fr") && t.contains("Charlie demande exemple-devoirs.fr"))
        assertTrue(t, t.contains("Du temps en plus") && t.contains("Accorder 15 min"))
    }

    fun testMessageSiteMontreLeDomaine() {
        val a = lancer(MessageActivity.intention(ctx, "Demande d'accès", "Charlie demande l'accès.", false, null, site()))
        val t = sansSiteVide(a.window.decorView)
        assertTrue(t, t.contains("exemple-devoirs.fr"))
    }

    fun testAccueilSiteMontreLeDomaine() {
        val a = lancer(Intent(ctx, LanceurActivity::class.java))
        instrumentation.runOnMainSync { poser(a, "donnees", JSONObject().put("demandes", JSONArray().put(site()))) }
        val t = sansSiteVide(widgetAccueil(a))
        assertTrue(t, t.contains("exemple-devoirs.fr"))
    }

    // ------------------------------------------------------------------ 1.80.0 : déverrouillage dans le widget, rafraîchi tout de suite

    fun testAccueilDeverrouillageAccorde() {
        val a = lancer(Intent(ctx, LanceurActivity::class.java))
        val dev = JSONObject().put("prenom", "Alex").put("genre", "deverrouillage").put("libelle", "l'appli com.roblox.client (20 min)")
            .put("pc", "e1").put("id", "dv1").put("minutes", 20).put("ts", System.currentTimeMillis() / 1000)
        instrumentation.runOnMainSync { poser(a, "donnees", JSONObject().put("demandes", JSONArray().put(dev))) }
        val w = widgetAccueil(a)
        val t = textes(w).joinToString(" | ") { lisible(it) }
        assertTrue(t, t.contains("Alex demande l'appli com.roblox.client (20 min)"))
        porte = CountDownLatch(1); reponse = 200 to """{"ok":true,"retour":"Nolan a accordé l'appli com.roblox.client sur TEL-ALEX pour 20 min."}"""
        val accepter = bouton(w, "Accepter")
        instrumentation.runOnMainSync { accepter.performClick() }
        attendre("« En cours… »") { accepter.text.toString() == Geste.EN_COURS }
        attendre("réponse envoyée comme une demande d'accès") { dernierCorps?.optString("action") == "acces" && dernierCorps?.optString("demande") == "dv1" }
        porte.countDown()
        attendre("demande retirée") { (champ(a, "donnees") as JSONObject).optJSONArray("demandes")?.length() == 0 }
    }

    fun testAccueilRafraichiDesQuUneDemandeArrive() {
        lancer(Intent(ctx, LanceurActivity::class.java))
        attendre("crochet posé à l'affichage") { LanceurActivity.rafraichirMaintenant != null }
        val lot = JSONArray().put(JSONObject().put("title", "Demande de déverrouillage").put("deverrouillage", JSONObject().put("demande", "x")))
        assertTrue(Demandes.contientDemande(lot))
        assertFalse(Demandes.contientDemande(JSONArray().put(JSONObject().put("title", "Vision").put("text", "Bonne soirée"))))
    }

    // ------------------------------------------------------------------ 1.80.1 : le chemin exact de l'auteur (widget en page 2)

    /** Le widget « Demandes » posé sur une page suivante de l'accueil, comme en essai ; rend la disposition d'avant. */
    private fun avecWidgetDemandesEnPage2(bloc: (Activity) -> Unit) {
        if (ReglagesTvActivity.estTele(ctx)) return  // pages de widgets : téléphone seulement
        val avant = Accueil.disposition(ctx)
        val modeAvant = ctx.getSharedPreferences("lanceur", 0).getString("mode", null)
        try {
            Accueil.poserMode(ctx, Accueil.MODE_PAGES)  // comme sur le Pixel d'essai : l'accueil en pages
            val l = Accueil.disposition(ctx).filter { it.cle != Accueil.PREFIXE_WIDGET + "demandes" }.toMutableList()
            // Une ligne bien au-delà de la première page (5 pages de marge, quelle que soit la hauteur de l'écran).
            l.add(Accueil.Place(Accueil.PREFIXE_WIDGET + "demandes", 0, 60, 4, 2))
            Accueil.ecrireDisposition(ctx, l)
            val a = lancer(Intent(ctx, LanceurActivity::class.java))
            // Le widget est bien sur une page suivante (sinon le test ne prouverait rien).
            var page = -1
            instrumentation.runOnMainSync {
                @Suppress("UNCHECKED_CAST") val gs = a.javaClass.getDeclaredMethod("grilles").apply { isAccessible = true }.invoke(a) as List<ViewGroup>
                page = gs.indexOfFirst { g -> (0 until g.childCount).any { (g.getChildAt(it).tag as? Accueil.Place)?.code == "demandes" } }
            }
            assertTrue("widget « Demandes » en page ${page + 1} au lieu d'une page suivante", page >= 1)
            bloc(a)
        } finally {
            Accueil.ecrireDisposition(ctx, avant)
            if (modeAvant == null) ctx.getSharedPreferences("lanceur", 0).edit().remove("mode").apply() else Accueil.poserMode(ctx, modeAvant)
        }
    }

    private fun instagram() = JSONObject().put("prenom", "Alex").put("genre", "apps").put("libelle", "Instagram").put("cle", "com.instagram.android")
        .put("pc", "e1").put("id", "ig1").put("ts", System.currentTimeMillis() / 1000)

    fun testWidgetPage2AccepterAppliMisAJour() = avecWidgetDemandesEnPage2 { a ->
        instrumentation.runOnMainSync { poser(a, "donnees", JSONObject().put("demandes", JSONArray().put(instagram()))); appeler(a, "appliquerDonnees") }
        instrumentation.waitForIdleSync()
        var b: TextView? = null
        attendre("widget « Demandes » sur une page suivante") { b = textes(a.window.decorView).firstOrNull { it.text.toString() == "Accepter" }; b != null }
        porte = CountDownLatch(1); reponse = 200 to """{"ok":true,"retour":"Nolan a autorisé Instagram pour 60 min."}"""
        instrumentation.runOnMainSync { b!!.performClick() }
        attendre("« En cours… »") { textes(a.window.decorView).any { it.text.toString() == Geste.EN_COURS } }
        attendre("geste reçu") { dernierCorps?.optString("action") == "acces" && dernierCorps?.optString("demande") == "ig1" && dernierCorps?.optString("decision") == "temporaire" }
        porte.countDown()
        attendre("widget redessiné : demande partie, résultat dit, plus de « En cours… »") {
            val t = textes(a.window.decorView).map { it.text.toString() }
            t.none { it == Geste.EN_COURS } && t.none { it.contains("demande Instagram") } && t.any { it.contains("autorisé Instagram") }
        }
    }

    fun testWidgetSansReponseLeBoutonRevientEn10s() = avecWidgetDemandesEnPage2 { a ->
        instrumentation.runOnMainSync { poser(a, "donnees", JSONObject().put("demandes", JSONArray().put(instagram()))); appeler(a, "appliquerDonnees") }
        var b: TextView? = null
        attendre("widget") { b = textes(a.window.decorView).firstOrNull { it.text.toString() == "Accepter" }; b != null }
        porte = CountDownLatch(1); reponse = 200 to """{"ok":true}"""  // le serveur ne répondra qu'après 15 s
        instrumentation.runOnMainSync { b!!.performClick() }
        attendre("« En cours… »") { textes(a.window.decorView).any { it.text.toString() == Geste.EN_COURS } }
        val debut = SystemClock.elapsedRealtime()
        val fin = debut + 10_800
        var ok = false
        while (SystemClock.elapsedRealtime() < fin && !ok) {
            instrumentation.runOnMainSync {
                val t = textes(a.window.decorView).map { it.text.toString() }
                // Fini en clair : délai de Vision (10 s) ou abandon de la connexion (8 s), le bouton revenu.
                ok = t.none { it == Geste.EN_COURS } && t.contains("Accepter") &&
                    t.any { it.contains("Pas de réponse de Home Assistant") || it.contains("rien n'a changé") || it.contains("injoignable") ||
                        // 1.93.06 : la cause est dite (ReseauLocal) — HA muet ou réseau de l'appareil coupé.
                        it.contains("ne répond pas") || it.contains("est coupé") }
            }
            SystemClock.sleep(200)
        }
        porte.countDown()
        assertTrue("« En cours… » ne s'est pas terminé en 10 s", ok)
    }

    // ------------------------------------------------------------------ outil

    fun testSansRetireLaBonneDemande() {
        val l = JSONArray().put(JSONObject().put("id", "a")).put(JSONObject().put("id", "b"))
        assertEquals("b", Geste.sans(l, "a").getJSONObject(0).getString("id"))
        assertEquals(2, Geste.sans(l, "z").length())
        assertEquals(0, Geste.sans(null, "a").length())
    }
}
