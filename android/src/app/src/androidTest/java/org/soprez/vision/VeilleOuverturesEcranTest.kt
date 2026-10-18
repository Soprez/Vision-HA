// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.test.InstrumentationTestCase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.ServerSocket
import java.net.Socket

/**
 * l'auteur (15/10) : le bloc « Ouvertures » de la veille, tel que Home Assistant l'envoie (capteurs texte « Ouvert / Fermé »
 * et binary_sensor, tous en rendu « état »), dans chaque style d'indicateur (pastille, icône, liseré, aucun) et en
 * « seulement quand c'est ouvert ». Une capture par style dans les fichiers de l'appli (ouvertures-<style>.png, récupérée
 * par adb pour la recette à l'œil) ; l'essai vérifie que la veille se dessine sans erreur dans chaque cas.
 *
 *   adb shell am instrument -w -e class org.soprez.vision.VeilleOuverturesEcranTest org.soprez.vision.test/android.test.InstrumentationTestRunner
 */
class VeilleOuverturesEcranTest : InstrumentationTestCase() {
    private val ctx get() = instrumentation.targetContext
    private lateinit var serveur: ServerSocket
    private val sauves = HashMap<String, Map<String, *>>()

    private fun etat(entite: String, nom: String, on: Boolean?, texte: String, type: String, ouverture: Boolean = true, largeur: Int = 6, hauteur: Int = 1) =
        JSONObject().put("entite", entite).put("nom", nom).put("rendu", "etat").put("on", on ?: JSONObject.NULL).put("texte", texte)
            .put("classe", "").put("ouverture", ouverture).put("type_ouverture", type).put("largeur", largeur).put("hauteur", hauteur)

    private fun veille(): JSONObject {
        val ouvertures = JSONArray()
            .put(etat("sensor.terrasse_portillon", "Portillon", true, "Ouvert", "portail"))
            .put(etat("sensor.terrasse_store_terrasse_classification", "Store terrasse", false, "Fermé", "store"))
            .put(etat("binary_sensor.portail_avant_ouvert", "Portail avant", false, "Fermé", "portail"))
            .put(etat("binary_sensor.portail_arriere_ouvert", "Portail arrière", false, "Fermé", "portail"))
            .put(etat("sensor.jardin_linge_etendu_classification", "Linge accroché", false, "Non", "", ouverture = false, largeur = 12))
        val autres = JSONArray()
            .put(etat("binary_sensor.fenetre_salon", "Fenêtre salon", null, "Indisponible", "fenetre", largeur = 6, hauteur = 2))
            .put(etat("cover.garage", "Garage", true, "Ouvert", "garage", largeur = 6, hauteur = 2))
            .put(etat("lock.entree", "Serrure entrée", false, "Verrouillé", "cadenas"))
            .put(etat("binary_sensor.porte_cave", "Porte de la cave", true, "Ouverte", "porte"))
        val sections = JSONArray().put(JSONObject().put("titre", "Ouvertures").put("cases", ouvertures))
            .put(JSONObject().put("titre", "Autres ouvertures").put("cases", autres))
        return JSONObject().put("ok", true).put("reglages", JSONObject().put("theme", "Beige"))
            .put("tableaux", JSONObject().put("liste", JSONArray().put(JSONObject().put("code", "dash").put("id", "dash_ouvertures").put("titre", "Accueil")
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
        for (n in listOf("vision", "local", "veille_ouvertures")) sauves[n] = HashMap(ctx.getSharedPreferences(n, 0).all)
        val cfg = Config(ctx)
        cfg.id = "essai_veille"; cfg.secret = "s".repeat(32)
        val adresse = "http://127.0.0.1:${serveur.localPort}"
        cfg.urlInterne = adresse; cfg.urlExterne = ""; cfg.urlActive = adresse
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

    private fun capturer(nom: String) {
        val a: Activity = instrumentation.startActivitySync(Intent(ctx, VeilleActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        SystemClock.sleep(4500)  // le relevé arrive, les cartes entrent en scène
        val image: Bitmap? = instrumentation.uiAutomation.takeScreenshot()
        assertNotNull("pas de capture ($nom)", image)
        val dossier = File(ctx.getExternalFilesDir(null), "recette").apply { mkdirs() }
        File(dossier, "ouvertures-$nom.png").outputStream().use { image!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertFalse("la veille s'est fermée ($nom)", a.isFinishing)
        a.finish()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(500)
    }

    fun testChaqueStyle() {
        val I = IndicateurOuverture
        for (style in I.STYLES) {
            I.poserStyle(ctx, style); I.poserSeulementOuvert(ctx, false)
            capturer(style)
        }
        I.poserStyle(ctx, I.ICONE); I.poserSeulementOuvert(ctx, true); I.poserCouleurOuvert(ctx, I.ORANGE)
        capturer("icone-seulement-ouvert-orange")
    }
}
