// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.content.Intent
import android.graphics.RectF
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.ServerSocket
import java.net.Socket

/**
 * Veille-lot : la veille dessinée par Vision avec un tableau de bord de 5, 10 ou 20 cartes, servi par un faux Home
 * Assistant local. On vérifie :
 *  - la grille au pixel : bords entiers, dans une rangée mêmes haut et bas, espacements égaux (avec et sans pastille) ;
 *  - la cadence : des images dessinées en continu, et la part d'images en retard (journal « VeillePerf ») ;
 *  - les pages : 20 cartes tiennent sur plusieurs écrans.
 *
 *   adb shell am instrument -w -e class org.soprez.vision.VeillePerfTest org.soprez.vision.test/android.test.InstrumentationTestRunner
 */
class VeillePerfTest : InstrumentationTestCase() {
    private val ctx get() = instrumentation.targetContext
    private lateinit var serveur: ServerSocket
    private var nombre = 10
    private val sauves = HashMap<String, Map<String, *>>()

    private fun cas(i: Int): JSONObject {
        val maintenant = System.currentTimeMillis()
        return when (i % 6) {
            0 -> JSONObject().put("entite", "sensor.niveau_$i").put("nom", "Niveau estimé $i").put("rendu", "valeur").put("valeur", 1234 + i).put("unite", "L").put("largeur", 6).put("hauteur", 1)
            // Un Home Assistant ancien : « d » doit s'afficher « j » en français.
            1 -> JSONObject().put("entite", "sensor.autonomie_$i").put("nom", "Autonomie $i").put("rendu", "valeur").put("valeur", 176).put("unite", "d").put("largeur", 6).put("hauteur", 1)
            2 -> JSONObject().put("entite", "sensor.conso_$i").put("nom", "Consommation $i").put("rendu", "courbe").put("valeur", 1.9).put("unite", "L/d").put("largeur", 12).put("hauteur", 2)
                .put("serie", JSONArray().apply { for (k in 0 until 96) put(JSONArray().put(maintenant - (96 - k) * 900_000L).put(10.0 + 5 * Math.sin(k / 8.0))) })
            3 -> JSONObject().put("entite", "sensor.cuve_$i").put("nom", "Cuve $i").put("rendu", "jauge").put("valeur", 62).put("unite", "%").put("min", 0).put("max", 100).put("largeur", 4).put("hauteur", 2)
            4 -> JSONObject().put("entite", "binary_sensor.porte_$i").put("nom", "Porte $i").put("rendu", "etat").put("on", i % 2 == 0).put("texte", "ouverte").put("classe", "door").put("largeur", 4).put("hauteur", 1)
            else -> JSONObject().put("genre", "agenda").put("entite", "calendar.famille").put("nom", "Agenda").put("rendu", "texte").put("texte", "14:00 Piscine").put("largeur", 6).put("hauteur", 2)
                .put("evenements", JSONArray().put(JSONObject().put("debut", "2026-10-12T14:00:00+02:00").put("fin", "2026-10-12T15:00:00+02:00").put("titre", "Piscine").put("journee", false)))
        }
    }

    private fun veille(): JSONObject {
        val sections = JSONArray()
        var k = 0
        while (k < nombre) {
            val cs = JSONArray(); repeat(minOf(5, nombre - k)) { cs.put(cas(k)); k++ }
            // Un bouton et un champ que Home Assistant ancien enverrait encore : jamais montrés.
            cs.put(JSONObject().put("entite", "button.sonnette").put("nom", "Sonnette").put("rendu", "texte").put("texte", "—"))
            cs.put(JSONObject().put("entite", "input_number.cible").put("nom", "Cible").put("rendu", "valeur").put("valeur", 3))
            sections.put(JSONObject().put("titre", "Section ${sections.length() + 1}").put("cases", cs))
        }
        return JSONObject().put("ok", true).put("reglages", JSONObject().put("theme", "Beige"))
            .put("tableaux", JSONObject().put("liste", JSONArray().put(JSONObject().put("code", "dash").put("id", "dash_essai").put("titre", "Essai")
                .put("duree", 600).put("colonnes", 2).put("sections", sections))))
    }

    private fun servir(s: Socket): Unit = s.use {
        val entree = s.getInputStream().bufferedReader(Charsets.UTF_8)
        val premiere = entree.readLine() ?: return
        var longueur = 0
        while (true) { val l = entree.readLine() ?: break; if (l.isEmpty()) break; if (l.lowercase().startsWith("content-length:")) longueur = l.substringAfter(':').trim().toInt() }
        val buf = CharArray(longueur); var lu = 0
        while (lu < longueur && (lu == 0 || entree.ready())) { val n = entree.read(buf, lu, longueur - lu); if (n < 0) break; lu += n }
        val octets = (if (premiere.contains("/api/pc_parental/veille ") || premiere.endsWith("/api/pc_parental/veille HTTP/1.1")) veille() else JSONObject().put("ok", true)).toString().toByteArray()
        s.getOutputStream().apply {
            write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${octets.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(octets); flush()
        }
        Unit
    }

    override fun setUp() {
        super.setUp()
        serveur = ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"))
        Thread { while (!serveur.isClosed) { val s = try { serveur.accept() } catch (_: Exception) { break }; Thread { try { servir(s) } catch (_: Exception) {} }.start() } }.apply { isDaemon = true }.start()
        for (n in listOf("vision", "local")) sauves[n] = HashMap(ctx.getSharedPreferences(n, 0).all)
        val cfg = Config(ctx)
        cfg.id = "essai_veille"; cfg.secret = "s".repeat(32)
        val adresse = "http://127.0.0.1:${serveur.localPort}"
        cfg.urlInterne = adresse; cfg.urlExterne = ""; cfg.urlActive = adresse
        // La veille dessinée par l'appli (pas la page de Home Assistant), sans musique.
        Local.poserVeilleWeb(ctx, false)
        Local.poserMusique(ctx, "")
    }

    override fun tearDown() {
        try { serveur.close() } catch (_: Exception) {}
        for ((n, v) in sauves) ctx.getSharedPreferences(n, 0).edit().clear().apply {
            v.forEach { (k, x) -> when (x) { is String -> putString(k, x); is Boolean -> putBoolean(k, x); is Int -> putInt(k, x); is Long -> putLong(k, x); is Float -> putFloat(k, x); is Set<*> -> @Suppress("UNCHECKED_CAST") putStringSet(k, x as Set<String>) } }
        }.commit()
        super.tearDown()
    }

    private fun vue(a: Activity): VeilleView? {
        var r: VeilleView? = null
        instrumentation.runOnMainSync {
            fun cherche(v: android.view.View): VeilleView? = if (v is VeilleView) v else if (v is android.view.ViewGroup) (0 until v.childCount).firstNotNullOfOrNull { cherche(v.getChildAt(it)) } else null
            r = cherche(a.window.decorView)
        }
        return r
    }

    private fun essai(n: Int, pastille: Boolean) {
        nombre = n
        Local.poserPastilleVeille(ctx, pastille)
        val t0 = SystemClock.uptimeMillis()
        val a: Activity = instrumentation.startActivitySync(Intent(ctx, VeilleActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        val v = vue(a)
        assertNotNull("la veille n'est pas là", v)
        v!!.noterRects = true
        // Le relevé arrive, les cartes entrent en scène (≈ 2 s) : on mesure ensuite 6 s de repos.
        SystemClock.sleep(4000)
        val i0 = v.imagesDessinees; val l0 = v.imagesLentes
        SystemClock.sleep(6000)
        val images = v.imagesDessinees - i0; val lentes = v.imagesLentes - l0
        Log.i("VeillePerf", "cartes=$n pastille=$pastille premier_affichage_ms=${SystemClock.uptimeMillis() - t0 - 10_000} images_6s=$images en_retard=$lentes peine=${v.peine()}")
        // La dernière image complète, telle que la veille l'a publiée (lecture sans course avec le dessin).
        val rects: List<Pair<Int, RectF>> = v.dernieresCartes.map { it.first to RectF(it.second) }
        // Diagnostic : le délai de la première réponse de Home Assistant, et l'état de la veille s'il n'y a aucune carte.
        Log.i("VeillePerf", "cartes=$n reponse_ms=${if (v.premiereReussiteA > 0) v.premiereReussiteA - v.demarreeA else -1} cartes_posees=${rects.size} focus=${a.hasWindowFocus()} etat=${v.etatEssai()}")
        val vues = synchronized(v.casesVues) { v.casesVues.toList() }
        v.noterRects = false
        a.finish()
        instrumentation.waitForIdleSync()
        // Au repos, au moins 30 images en 6 s (la cadence ne s'est pas arrêtée).
        assertTrue("cadence arrêtée : $images images en 6 s", images >= 30)
        verifierAlignement(rects, n, pastille)
        // Boutons et champs de saisie jamais montrés ; « d » d'un Home Assistant ancien affiché « j » (appareil en français).
        assertFalse("bouton montré : $vues", vues.any { it.startsWith("Sonnette|") || it.startsWith("Cible|") })
        if (java.util.Locale.getDefault().language == "fr") assertFalse("unité « d » affichée : $vues", vues.any { it.startsWith("Autonomie") && it.endsWith(" d") })
    }

    /** Bords au pixel ; dans une rangée : mêmes haut et bas, espacements égaux. */
    private fun verifierAlignement(rects: List<Pair<Int, RectF>>, n: Int, pastille: Boolean) {
        assertTrue("aucune carte posée ($n cartes)", rects.isNotEmpty())
        val defauts = ArrayList<String>()
        for ((_, r) in rects) if (listOf(r.left, r.top, r.right, r.bottom).any { it != Math.round(it).toFloat() }) defauts.add("bord hors pixel $r")
        // Une rangée = une colonne et un même haut : mêmes bas, espacements tous égaux (au pixel près : 0).
        val rangees = rects.groupBy { it.first to it.second.top }.values.map { l -> l.map { it.second } }
        val tousEcarts = ArrayList<Float>()
        for (r in rangees) {
            val tries = r.sortedBy { it.left }
            if (tries.maxOf { it.bottom } != tries.minOf { it.bottom }) defauts.add("rangée au haut ${r[0].top} : bas différents")
            tousEcarts.addAll(tries.zipWithNext { x, y -> y.left - x.right })
        }
        if (tousEcarts.isNotEmpty() && tousEcarts.max() != tousEcarts.min()) defauts.add("espacements inégaux ${tousEcarts.distinct()}")
        Log.i("VeillePerf", "alignement $n cartes pastille=$pastille : ${rects.size} cartes, ${rangees.size} rangées, défauts=${defauts.size}")
        assertTrue("alignement ($n cartes, pastille=$pastille) : " + defauts.joinToString(" | "), defauts.isEmpty())
    }

    fun testCinq() = essai(5, false)
    fun testDix() = essai(10, false)
    fun testDixAvecPastille() = essai(10, true)
    fun testVingt() = essai(20, false)
}
