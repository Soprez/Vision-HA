// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.test.InstrumentationTestCase
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * 1.82.1 — règle du projet : une mise à jour de widget ou de données ne fait JAMAIS clignoter l'écran.
 * Plusieurs relevés aux données identiques (l'accueil relit toutes les minutes, l'écran Party toutes les 5 s, les
 * widgets Home Assistant à chaque événement) : rien n'est rebâti ni remplacé. Une donnée qui change : seul le widget
 * concerné est remplacé. Un petit serveur local (dans le test) joue Home Assistant.
 *
 *   adb shell am instrument -w -e class org.soprez.vision.GlitchsTest org.soprez.vision.test/android.test.InstrumentationTestRunner
 */
class GlitchsTest : InstrumentationTestCase() {
    private val ctx get() = instrumentation.targetContext
    private lateinit var serveur: ServerSocket
    @Volatile private var batterie = 80
    @Volatile private var positionTs = 1000.0
    private val sauves = HashMap<String, Map<String, *>>()

    private fun image(): ByteArray {
        val b = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888); b.eraseColor(Color.rgb(90, 120, 200))
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun veille(): JSONObject = JSONObject()
        .put("meteo", JSONObject().put("temperature", 18.0).put("etat", "sunny"))
        .put("batteries", JSONArray().put(JSONObject().put("nom", "Téléphone de Alex").put("niveau", batterie)))
        .put("cameras", JSONArray().put(JSONObject().put("id", "camera.entree").put("nom", "Entrée")))
        .put("demandes", JSONArray())

    private fun party(): JSONObject = JSONObject().put("ok", true).put("actif", true).put("ecran", "t1").put("ici", true)
        .put("lecteur", "media_player.salon").put("lecteur_nom", "Salon").put("piloter", true).put("relire", 30).put("ma", true)
        .put("maintenant", JSONObject().put("etat", "playing").put("titre", "Titre").put("artiste", "Artiste").put("duree", 200)
            .put("position", 20).put("position_ts", positionTs))
        .put("ecrans", JSONArray().put(JSONObject().put("id", "t1").put("nom", "Salon")))
        .put("lecteurs", JSONArray().put(JSONObject().put("entity_id", "media_player.salon").put("nom", "Salon").put("etat", "playing").put("ma", true)))

    private fun servir(s: Socket): Unit = s.use {
        val entree = s.getInputStream().bufferedReader(Charsets.UTF_8)
        val premiere = entree.readLine() ?: return
        var longueur = 0
        while (true) { val l = entree.readLine() ?: break; if (l.isEmpty()) break; if (l.lowercase().startsWith("content-length:")) longueur = l.substringAfter(':').trim().toInt() }
        val buf = CharArray(longueur); var lu = 0
        while (lu < longueur) { val n = entree.read(buf, lu, longueur - lu); if (n < 0) break; lu += n }
        val (type, octets) = when {
            premiere.contains("/veille/image") -> "image/png" to image()
            premiere.contains("/api/pc_parental/veille") -> "application/json" to veille().toString().toByteArray()
            premiere.contains("/party/etat") -> "application/json" to party().toString().toByteArray()
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
        for (n in listOf("vision", "lanceur", "party", "widgets_ha")) sauves[n] = HashMap(ctx.getSharedPreferences(n, 0).all)
        val cfg = Config(ctx)
        cfg.id = "essai_glitchs"; cfg.secret = "s".repeat(32)
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

    private fun champ(o: Any, nom: String): Any? {
        var c: Class<*>? = o.javaClass
        while (c != null) { try { return c.getDeclaredField(nom).apply { isAccessible = true }.get(o) } catch (_: NoSuchFieldException) { c = c.superclass } }
        throw AssertionError("champ $nom")
    }

    /** Les vues à l'écran (identités) : une vue remplacée change d'identité, même si elle a l'air pareille. */
    private fun identites(a: Activity): List<Int> {
        val l = ArrayList<Int>()
        fun parcourir(v: android.view.View) { l.add(System.identityHashCode(v)); if (v is android.view.ViewGroup) for (i in 0 until v.childCount) parcourir(v.getChildAt(i)) }
        instrumentation.runOnMainSync { parcourir(a.window.decorView) }
        return l
    }

    private fun remplacees(avant: List<Int>, apres: List<Int>): Int = apres.count { it !in avant.toSet() }

    /** Pour comprendre un échec : la classe, le texte et le parent des vues recréées. */
    private fun decrireNouvelles(a: Activity, avant: List<Int>): String {
        val l = ArrayList<String>(); val vus = avant.toSet()
        fun parcourir(v: android.view.View) {
            if (System.identityHashCode(v) !in vus) l.add(v.javaClass.simpleName + (if (v is android.widget.TextView) "«" + v.text + "»" else "") + "<" + (v.parent?.javaClass?.simpleName ?: "") + " tag=" + v.tag)
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) parcourir(v.getChildAt(i))
        }
        instrumentation.runOnMainSync { parcourir(a.window.decorView) }
        return l.take(20).joinToString(" | ")
    }

    private fun appeler(o: Any, nom: String) {
        instrumentation.runOnMainSync { o.javaClass.declaredMethods.first { it.name == nom && it.parameterTypes.isEmpty() }.apply { isAccessible = true }.invoke(o) }
    }

    fun testAccueilSansClignotement() {
        if (ReglagesTvActivity.estTele(ctx)) return
        for (w in listOf("batteries" to (2 to 2), "camera" to (2 to 2))) Accueil.ajouterALaGrille(ctx, Accueil.PREFIXE_WIDGET + w.first, w.second.first, w.second.second)
        val a = instrumentation.startActivitySync(Intent(ctx, LanceurActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        instrumentation.waitForIdleSync()
        // Les données arrivent (premier relevé), puis on laisse l'accueil se poser.
        val fin = SystemClock.elapsedRealtime() + 8000
        while (SystemClock.elapsedRealtime() < fin && champ(a, "donnees") == null) SystemClock.sleep(100)
        assertNotNull("pas de données", champ(a, "donnees"))
        // L'accueil se pose (images, historiques, flux) avant la mesure.
        SystemClock.sleep(1500); appeler(a, "appliquerDonnees"); SystemClock.sleep(3000)
        val i0 = identites(a)
        // Cinq relevés identiques (cinq minutes d'accueil) : aucune vue ne doit être recréée.
        var recreees = 0
        var prec = i0
        repeat(5) {
            appeler(a, "appliquerDonnees"); SystemClock.sleep(200); val i = identites(a); val n = remplacees(prec, i)
            if (n > 0) android.util.Log.i("GlitchsTest", "recréées : " + decrireNouvelles(a, prec))
            recreees += n; prec = i
        }
        android.util.Log.i("GlitchsTest", "accueil : $recreees vues recréées en 5 relevés identiques")
        assertEquals("vues de l'accueil recréées sans changement de données (clignotement)", 0, recreees)
        // Une donnée change : seul son widget est remplacé, pas tout l'accueil.
        batterie = 35
        (champ(a, "donnees") as JSONObject).put("batteries", JSONArray().put(JSONObject().put("nom", "Téléphone de Alex").put("niveau", 35)))
        appeler(a, "appliquerDonnees")
        val n = remplacees(prec, identites(a))
        assertTrue("le widget Batteries doit changer, et lui seul ($n vues)", n in 1..30)
        a.finish()
    }

    fun testWidgetHaSansRedessin() {
        if (ReglagesTvActivity.estTele(ctx)) return
        var v: VueWidgetHa? = null
        instrumentation.runOnMainSync {
            v = VueWidgetHa(ctx, Local.themeEffectif(ctx, Palette.APPLI), WidgetsHa.Reglage("g1", listOf("sensor.exemple"), WidgetsHa.TUILE, 2, 1), lectureSeule = true, exemple = true)
        }
        fun enfant(): Int { var x = 0; instrumentation.runOnMainSync { x = System.identityHashCode(v!!.getChildAt(0)) }; return x }
        val e0 = enfant()
        // Dix événements du flux sans changement d'état : le contenu reste le même objet (aucun redessin).
        var redessins = 0
        var prec = e0
        repeat(10) { instrumentation.runOnMainSync { v!!.maj() }; val x = enfant(); if (x != prec) redessins++; prec = x }
        android.util.Log.i("GlitchsTest", "widget HA : $redessins redessins en 10 événements identiques")
        assertEquals("widget HA redessiné sans changement", 0, redessins)
        // L'état change : il se redessine.
        instrumentation.runOnMainSync { FluxWidgets.etats["sensor.exemple"] = WidgetsHa.EtatEntite("22.5"); v!!.maj() }
        assertTrue(enfant() != e0)
        FluxWidgets.etats.remove("sensor.exemple")
    }

    fun testPartySansClignotement() {
        val a: Activity = instrumentation.startActivitySync(Intent(ctx, PartyActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val fin = SystemClock.elapsedRealtime() + 8000
        while (SystemClock.elapsedRealtime() < fin && champ(a, "etat") == null) SystemClock.sleep(100)
        SystemClock.sleep(800)
        val i0 = identites(a)
        // Cinq relectures (25 s de Party) : seule la position du morceau avance → pas de reconstruction.
        repeat(5) {
            positionTs += 5.0
            appeler(a, "charger")
            val f = SystemClock.elapsedRealtime() + 4000
            while (SystemClock.elapsedRealtime() < f && champ(a, "chargement") == true) SystemClock.sleep(50)
            SystemClock.sleep(200)
        }
        val n = remplacees(i0, identites(a))
        android.util.Log.i("GlitchsTest", "Party : $n vues recréées en 5 relectures identiques")
        assertEquals("écran Party rebâti sans changement (clignotement)", 0, n)
        a.finish()
    }
}
