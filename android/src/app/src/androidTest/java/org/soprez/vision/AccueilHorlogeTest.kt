// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.SystemClock
import android.provider.Settings
import android.test.InstrumentationTestCase
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.ServerSocket
import java.net.Socket

/**
 * 1.83.1 — glitchs de l'accueil signalés en essai : l'heure et la date disparaissaient (« · Gestion » seul) par
 * intervalles et au retour sur l'accueil, et ne marchaient pas sur certains thèmes. Règles : l'en-tête reste
 * toujours visible ; revenir d'une appli ne rebâtit pas l'accueil ; sur chaque thème, l'heure est lisible.
 *
 *   adb shell am instrument -w -e class org.soprez.vision.AccueilHorlogeTest org.soprez.vision.test/android.test.InstrumentationTestRunner
 */
class AccueilHorlogeTest : InstrumentationTestCase() {
    private val ctx get() = instrumentation.targetContext
    private lateinit var serveur: ServerSocket
    @Volatile private var temperature = 12.0
    private val sauves = HashMap<String, Map<String, *>>()

    private fun veille() = JSONObject().put("ok", true)
        .put("meteo", JSONObject().put("temperature", temperature).put("etat", "partlycloudy"))
        .put("moi", JSONObject().put("prenom", "Nolan").put("public", "parent"))
        .put("demandes", JSONArray())

    private fun servir(s: Socket): Unit = s.use {
        val entree = s.getInputStream().bufferedReader(Charsets.UTF_8)
        val premiere = entree.readLine() ?: return
        var longueur = 0
        while (true) { val l = entree.readLine() ?: break; if (l.isEmpty()) break; if (l.lowercase().startsWith("content-length:")) longueur = l.substringAfter(':').trim().toInt() }
        val buf = CharArray(longueur); var lu = 0
        while (lu < longueur && (lu == 0 || entree.ready())) { val n = entree.read(buf, lu, longueur - lu); if (n < 0) break; lu += n }
        if (premiere.contains("/enroll")) { s.getOutputStream().apply { write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); flush() }; return }
        val octets = (if (premiere.contains("/api/pc_parental/veille")) veille() else JSONObject().put("ok", true)).toString().toByteArray()
        s.getOutputStream().apply {
            write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${octets.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(octets); flush()
        }
        Unit
    }

    override fun setUp() {
        super.setUp()
        CodeEssai.poser(instrumentation.targetContext)  // faille du 16-10 : un Décideur a toujours un code
        serveur = ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"))
        Thread {
            while (!serveur.isClosed) {
                val s = try { serveur.accept() } catch (_: Exception) { break }
                Thread { try { servir(s) } catch (_: Exception) {} }.start()
            }
        }.apply { isDaemon = true }.start()
        for (n in listOf("vision", "lanceur", "local")) sauves[n] = HashMap(ctx.getSharedPreferences(n, 0).all)
        val cfg = Config(ctx)
        cfg.id = "essai_horloge"; cfg.secret = "s".repeat(32)
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

    private fun champ(o: Any, nom: String): Any? {
        var c: Class<*>? = o.javaClass
        while (c != null) { try { return c.getDeclaredField(nom).apply { isAccessible = true }.get(o) } catch (_: NoSuchFieldException) { c = c.superclass } }
        throw AssertionError("champ $nom")
    }

    private fun appeler(o: Any, nom: String): Any? {
        var r: Any? = null
        instrumentation.runOnMainSync { r = o.javaClass.declaredMethods.first { it.name == nom && it.parameterTypes.isEmpty() }.apply { isAccessible = true }.invoke(o) }
        return r
    }

    private fun accueil(): Activity {
        val a = instrumentation.startActivitySync(Intent(ctx, LanceurActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        instrumentation.waitForIdleSync()
        return a
    }

    /** Ce que montre l'en-tête : « » si l'heure ou la date manque (vide, cachée, transparente, hors de l'écran). */
    private fun entete(a: Activity): String {
        var t = ""
        instrumentation.runOnMainSync {
            val h = champ(a, "heure") as? TextView; val d = champ(a, "date") as? TextView
            // Une vue pas encore mesurée n'est pas à l'écran : l'image précédente y est encore.
            t = if (h != null && d != null && (!h.isLaidOut || !d.isLaidOut)) "(en cours de mise en page)"
            else if (h == null || d == null || !h.isShown || !d.isShown || h.alpha < 0.99f || h.text.isBlank() || d.text.isBlank() || h.width == 0) "" else h.text.toString() + " " + d.text
        }
        return t
    }

    /** Échantillonne l'en-tête toutes les 100 ms pendant [bloc] : rend les instants où il manquait. */
    private fun surveiller(a: Activity, seulementDevant: Boolean = false, bloc: () -> Unit): List<Long> {
        val trous = ArrayList<Long>()
        val fin = java.util.concurrent.atomic.AtomicBoolean(false)
        val t0 = SystemClock.elapsedRealtime()
        val fil = Thread { while (!fin.get()) { if ((!seulementDevant || a.hasWindowFocus()) && entete(a).isEmpty()) synchronized(trous) { trous.add(SystemClock.elapsedRealtime() - t0) }; SystemClock.sleep(100) } }
        fil.start()
        try { bloc() } finally { fin.set(true); fil.join(2000) }
        return trous
    }

    private fun allerRetour(a: Activity) {
        ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        SystemClock.sleep(1500)
        ctx.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setClass(ctx, LanceurActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        SystemClock.sleep(1500)
    }

    fun testRetourDUneAppliSansReconstruction() {
        if (ReglagesTvActivity.estTele(ctx)) return
        val a = accueil()
        val fin = SystemClock.elapsedRealtime() + 8000
        while (SystemClock.elapsedRealtime() < fin && champ(a, "donnees") == null) SystemClock.sleep(100)
        SystemClock.sleep(1500)
        val c0 = champ(a, "constructions") as Int
        val e0 = appeler(a, "empreinte") as String
        val trous = surveiller(a, seulementDevant = true) { repeat(5) { allerRetour(a) } }
        val c1 = champ(a, "constructions") as Int
        val e1 = appeler(a, "empreinte") as String
        assertEquals("accueil rebâti au retour d'une appli (empreinte avant « $e0 », après « $e1 »)", c0, c1)
        // Seuls comptent les instants où l'accueil est devant (pendant Réglages, il est caché).
        assertTrue("en-tête vide au retour sur l'accueil (ms) : $trous", trous.isEmpty())
        a.finish()
    }

    private fun shell(cmd: String) {
        instrumentation.uiAutomation.executeShellCommand(cmd).close()
        SystemClock.sleep(1500)
    }

    /** 1.83.2 : le chemin de l'auteur — une appli (Claude) change le thème ou la police, retour à l'accueil : pas de recréation. */
    fun testChangementDeConfigurationSansRecreation() {
        if (ReglagesTvActivity.estTele(ctx)) return
        val a = accueil()
        SystemClock.sleep(1500)
        try {
            val trous = surveiller(a, seulementDevant = true) {
                ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); SystemClock.sleep(1500)
                shell("cmd uimode night yes"); shell("settings put system font_scale 1.15")
                ctx.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setClass(ctx, LanceurActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                SystemClock.sleep(2000)
                shell("cmd uimode night no"); shell("settings put system font_scale 1.0")  // cette fois l'accueil est devant
            }
            assertFalse("l'accueil a été détruit et recréé", a.isDestroyed)
            assertTrue("en-tête vide pendant le changement de configuration (ms) : $trous", trous.isEmpty())
        } finally {
            shell("cmd uimode night no"); shell("settings put system font_scale 1.0")
        }
        a.finish()
    }

    fun testEnteteTouteLaDureeAvecReleves() {
        if (ReglagesTvActivity.estTele(ctx)) return
        val a = accueil()
        val fin = SystemClock.elapsedRealtime() + 8000
        while (SystemClock.elapsedRealtime() < fin && champ(a, "donnees") == null) SystemClock.sleep(100)
        SystemClock.sleep(1000)
        // 2 minutes : relevés (toutes les 10 s), météo qui change, minute qui tourne.
        val trous = surveiller(a) {
            repeat(12) { k ->
                temperature = 12.0 + k
                (champ(a, "donnees") as JSONObject).put("meteo", JSONObject().put("temperature", temperature).put("etat", if (k % 2 == 0) "sunny" else "rainy"))
                appeler(a, "appliquerDonnees")
                SystemClock.sleep(10_000)
            }
        }
        assertTrue("l'en-tête (heure, date) a disparu ${trous.size} fois (ms : ${trous.take(10)})", trous.isEmpty())
        a.finish()
    }

    private fun contraste(a: Int, b: Int): Double {
        fun l(c: Int): Double { fun f(x: Int): Double { val v = x / 255.0; return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4) }; return 0.2126 * f(Color.red(c)) + 0.7152 * f(Color.green(c)) + 0.0722 * f(Color.blue(c)) }
        val x = l(a); val y = l(b)
        return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05)
    }

    private fun melange(fond: Int, halo: Int, part: Float): Int = Color.rgb(
        (Color.red(fond) * (1 - part) + Color.red(halo) * part).toInt(), (Color.green(fond) * (1 - part) + Color.green(halo) * part).toInt(),
        (Color.blue(fond) * (1 - part) + Color.blue(halo) * part).toInt())

    fun testHeureVisibleEtLisibleSurChaqueTheme() {
        if (ReglagesTvActivity.estTele(ctx)) return
        val rapport = ArrayList<String>()
        val echecs = ArrayList<String>()
        for (t in Themes.liste) for (sombre in listOf(Themes.SOMBRE_JAMAIS, Themes.SOMBRE_TOUJOURS)) {
            Local.poserTheme(ctx, t.nom); Local.poserModeSombre(ctx, sombre)
            val a = accueil()
            SystemClock.sleep(400)
            val vu = entete(a)
            val th = Local.themeEffectif(ctx, Palette.LANCEUR)
            var couleur = 0
            instrumentation.runOnMainSync { couleur = (champ(a, "heure") as TextView).currentTextColor }
            // Le fond derrière l'heure : la couleur du thème, sous le halo le plus fort du fond animé.
            val pire = listOf(th.fond, melange(th.fond, th.halos[0], 0.22f * (if (th.sombre) 0.5f else 1f))).minOf { contraste(couleur, it) }
            val ligne = "${t.nom}${if (sombre == Themes.SOMBRE_TOUJOURS) " (sombre)" else ""} : ${if (vu.isEmpty()) "HEURE ABSENTE" else "heure visible"}, contraste ${"%.1f".format(pire)}"
            rapport.add(ligne)
            if (vu.isEmpty() || pire < 4.5) echecs.add(ligne)
            a.finish(); SystemClock.sleep(200)
        }
        android.util.Log.i("AccueilHorlogeTest", "THEMES\n" + rapport.joinToString("\n"))
        assertTrue("thèmes en défaut :\n" + echecs.joinToString("\n"), echecs.isEmpty())
    }
}
