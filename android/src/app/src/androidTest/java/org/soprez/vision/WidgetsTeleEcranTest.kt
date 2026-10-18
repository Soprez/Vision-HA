// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import org.json.JSONObject
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 16-10 (l'auteur : parité des widgets entre téléphone, tablette et télé) : sur la télé, un widget chauffage se pose sur
 * l'accueil et AGIT comme sur le téléphone — le focus de la télécommande va sur − et +, OK règle la consigne
 * (avant : lecture seule). Le widget « Chauffage » de Vision (celui du téléphone) se pose aussi. Faux Home Assistant
 * local : l'action envoyée (« set_value », 21) est relevée. Sur un téléphone, l'essai ne fait rien.
 *
 *   adb shell am instrument -w -e class org.soprez.vision.WidgetsTeleEcranTest org.soprez.vision.test/android.test.InstrumentationTestRunner
 */
class WidgetsTeleEcranTest : InstrumentationTestCase() {
    private val ctx get() = instrumentation.targetContext
    private lateinit var serveur: ServerSocket
    private val actions = CopyOnWriteArrayList<JSONObject>()
    private var lanceurAvant: Map<String, *> = emptyMap<String, Any>()
    private var cfgAvant = arrayOf("", "", "", "", "")

    private fun servir(s: Socket): Unit = s.use {
        val entree = s.getInputStream().bufferedReader(Charsets.UTF_8)
        val premiere = entree.readLine() ?: return
        var longueur = 0
        while (true) { val l = entree.readLine() ?: break; if (l.isEmpty()) break; if (l.lowercase().startsWith("content-length:")) longueur = l.substringAfter(':').trim().toIntOrNull() ?: 0 }
        val buf = CharArray(longueur); var lu = 0
        while (lu < longueur) { val n = entree.read(buf, lu, longueur - lu); if (n < 0) break; lu += n }
        if (premiere.contains("/widgets/action")) actions.add(JSONObject(String(buf)))
        val corps = if (premiere.contains("/enroll")) "" else "{\"ok\":true}"
        val octets = corps.toByteArray()
        s.getOutputStream().apply {
            write("HTTP/1.1 ${if (premiere.contains("/enroll")) "403 Forbidden" else "200 OK"}\r\nContent-Type: application/json\r\nContent-Length: ${octets.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(octets); flush()
        }
        Unit
    }

    override fun setUp() {
        super.setUp()
        serveur = ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"))
        Thread { while (!serveur.isClosed) { val s = try { serveur.accept() } catch (_: Exception) { break }; Thread { try { servir(s) } catch (_: Exception) {} }.start() } }.apply { isDaemon = true }.start()
        val cfg = Config(ctx)
        cfgAvant = arrayOf(cfg.id, cfg.secret, cfg.urlInterne, cfg.urlExterne, cfg.urlActive)
        val adresse = "http://127.0.0.1:${serveur.localPort}"
        cfg.id = "essai_tele"; cfg.secret = "s".repeat(32); cfg.urlInterne = adresse; cfg.urlExterne = ""; cfg.urlActive = adresse
        lanceurAvant = ctx.getSharedPreferences("lanceur", Context.MODE_PRIVATE).all
        WidgetsHaStock.garderCatalogue(ctx, AntiDebordementTest.CATALOGUE_WIDGETS)
        FluxWidgets.etats.clear()
    }

    override fun tearDown() {
        try { serveur.close() } catch (_: Exception) {}
        val cfg = Config(ctx)
        cfg.id = cfgAvant[0]; cfg.secret = cfgAvant[1]; cfg.urlInterne = cfgAvant[2]; cfg.urlExterne = cfgAvant[3]; cfg.urlActive = cfgAvant[4]
        ctx.getSharedPreferences("lanceur", Context.MODE_PRIVATE).edit().apply {
            clear()
            for ((k, v) in lanceurAvant) when (v) {
                is String -> putString(k, v); is Boolean -> putBoolean(k, v); is Int -> putInt(k, v)
                is Long -> putLong(k, v); is Float -> putFloat(k, v); is Set<*> -> putStringSet(k, v.filterIsInstance<String>().toSet())
            }
        }.commit()
        super.tearDown()
    }

    private fun vues(v: View, l: ArrayList<View> = ArrayList()): ArrayList<View> {
        l.add(v); if (v is ViewGroup) for (i in 0 until v.childCount) vues(v.getChildAt(i), l); return l
    }

    private fun attendre(quoi: String, max: Long = 8_000, ok: () -> Boolean) {
        val fin = SystemClock.elapsedRealtime() + max
        while (!ok()) { if (SystemClock.elapsedRealtime() > fin) fail("délai dépassé : $quoi"); SystemClock.sleep(200) }
    }

    fun testPoserUnChauffageEtReglerLaConsigne() {
        if (!ReglagesTvActivity.estTele(ctx)) return
        // Disposition « Rangées » ; le réglage de consigne (Home Assistant) et le widget « Chauffage » de Vision.
        DispositionAccueil.poserDisposTele(ctx, DispositionAccueil.TELE_RANGEES)
        WidgetsHaStock.ecrire(ctx, WidgetsHa.Reglage("tele_consigne", listOf("input_number.consigne"), WidgetsHa.REGLAGE, 2, 1))
        Accueil.ajouterALaGrille(ctx, WidgetsHa.cle("tele_consigne"), 2, 1)
        Accueil.ajouterALaGrille(ctx, Accueil.PREFIXE_WIDGET + "chauffage", 2, 2)
        val a = instrumentation.startActivitySync(Intent(ctx, LanceurActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.setInTouchMode(false)
        try {
            val nom = "Chauffage — température cible confort du séjour"
            var plus: View? = null; var moins: View? = null
            attendre("boutons − / + du widget chauffage") {
                instrumentation.runOnMainSync {
                    val tout = vues(a.window.decorView)
                    plus = tout.firstOrNull { it.contentDescription == ctx.getString(R.string.wha_augmenter, nom) && it.isShown }
                    moins = tout.firstOrNull { it.contentDescription == ctx.getString(R.string.wha_diminuer, nom) && it.isShown }
                }
                plus != null && moins != null
            }
            assertTrue("− doit prendre le focus de la télécommande", moins!!.isFocusable)
            assertTrue("+ doit prendre le focus de la télécommande", plus!!.isFocusable)
            // Le widget « Chauffage » de Vision est là aussi (même widget que sur le téléphone).
            var integre: View? = null
            instrumentation.runOnMainSync { integre = a.window.decorView.findViewWithTag("carte_widget_chauffage") }
            assertNotNull("widget Chauffage de Vision absent de l'accueil de la télé", integre)
            // Focus sur −, puis ► : le focus passe sur + ; OK règle la consigne.
            instrumentation.runOnMainSync { moins!!.requestFocus() }
            instrumentation.waitForIdleSync()
            assertTrue("− n'a pas le focus", moins!!.isFocused)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            instrumentation.waitForIdleSync()
            assertTrue("► depuis − doit mener à +", plus!!.isFocused)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            // La consigne est réglée « avec confirmation » dans Home Assistant (catalogue d'essai) : la question s'ouvre ;
            // on va sur « Oui » à la télécommande (◄ / ►), puis OK.
            val oui = ctx.getString(R.string.wha_confirmer_oui)
            var confirme = false
            for (essai in 0 until 8) {
                SystemClock.sleep(400)
                val n = instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(oui)
                    ?.firstOrNull { it.className?.toString()?.endsWith("Button") == true && it.text?.toString() == oui } ?: continue
                if (n.isFocused) { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER); confirme = true; break }
                instrumentation.sendKeyDownUpSync(if (essai % 4 < 2) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
            }
            if (!confirme) instrumentation.uiAutomation.takeScreenshot()?.let { b ->
                java.io.File(ctx.getExternalFilesDir(null), "widgets-tele-confirmation.png").outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
            assertTrue("la confirmation « $oui » n'est pas atteignable à la télécommande", confirme)
            attendre("action envoyée à Home Assistant") { actions.isNotEmpty() }
            val act = actions.first()
            assertEquals("input_number.consigne", act.getString("entite"))
            assertEquals("set_value", act.getString("service"))
            assertEquals(21.0, act.getJSONObject("data").getDouble("value"), 1e-9)  // 20,5 + pas de 0,5
        } finally {
            a.finish(); instrumentation.waitForIdleSync()
            Accueil.retirerDeLaGrille(ctx, WidgetsHa.cle("tele_consigne")); Accueil.retirerDeLaGrille(ctx, Accueil.PREFIXE_WIDGET + "chauffage")
        }
    }
}
