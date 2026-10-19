// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.net.ServerSocket
import java.net.Socket

/**
 * 1.93.06 (retour d'essai) : l'accueil de la télé a deux modes, à la télécommande.
 * - UTILISATION : le cadre d'un widget qui a des commandes ne prend pas le focus (on va droit sur − et +).
 * - MODIFICATION : appui long sur OK ; on va de cadre en cadre ; OK saisit, ► déplace, Retour annule ; OK, OK puis ►
 *   agrandit, OK valide ; Retour quitte. Le bandeau le dit.
 * Faux Home Assistant local (rien n'est envoyé ailleurs). Sur un téléphone, l'essai ne fait rien.
 *
 *   adb shell am instrument -w -e class org.soprez.vision.AccueilModesEcranTest org.soprez.vision.test/android.test.InstrumentationTestRunner
 */
class AccueilModesEcranTest : InstrumentationTestCase() {
    private val ctx get() = instrumentation.targetContext
    private lateinit var serveur: ServerSocket
    private var lanceurAvant: Map<String, *> = emptyMap<String, Any>()
    private var cfgAvant = arrayOf("", "", "", "", "")
    private val consigne = WidgetsHa.cle("modes_consigne")
    private val chauffage = Accueil.PREFIXE_WIDGET + "chauffage"

    private fun servir(s: Socket): Unit = s.use {
        val entree = s.getInputStream().bufferedReader(Charsets.UTF_8)
        val premiere = entree.readLine() ?: return
        var longueur = 0
        while (true) { val l = entree.readLine() ?: break; if (l.isEmpty()) break; if (l.lowercase().startsWith("content-length:")) longueur = l.substringAfter(':').trim().toIntOrNull() ?: 0 }
        val buf = CharArray(longueur); var lu = 0
        while (lu < longueur) { val n = entree.read(buf, lu, longueur - lu); if (n < 0) break; lu += n }
        val refus = premiere.contains("/enroll")
        val octets = (if (refus) "" else "{\"ok\":true}").toByteArray()
        s.getOutputStream().apply {
            write("HTTP/1.1 ${if (refus) "403 Forbidden" else "200 OK"}\r\nContent-Type: application/json\r\nContent-Length: ${octets.size}\r\nConnection: close\r\n\r\n".toByteArray())
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
        cfg.id = "essai_modes"; cfg.secret = "s".repeat(32); cfg.urlInterne = adresse; cfg.urlExterne = ""; cfg.urlActive = adresse
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

    private fun touche(code: Int) { instrumentation.sendKeyDownUpSync(code); instrumentation.waitForIdleSync(); SystemClock.sleep(150) }

    /** OK tenu 900 ms, avec la répétition de la touche comme une vraie télécommande. */
    private fun appuiLongOk() {
        val bas = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(bas, bas, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        for (n in 1..8) { SystemClock.sleep(110); instrumentation.sendKeySync(KeyEvent(bas, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, n)) }
        instrumentation.sendKeySync(KeyEvent(bas, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        instrumentation.waitForIdleSync(); SystemClock.sleep(300)
    }

    private fun bandeau(a: LanceurActivity): String? {
        var t: String? = null
        instrumentation.runOnMainSync { t = (a.window.decorView.findViewWithTag<View>("bandeau_modification") as? TextView)?.text?.toString() }
        return t
    }

    private fun ordreWidgets(): List<String> = Accueil.disposition(ctx).filter { it.estWidget }.sortedWith(compareBy({ it.row }, { it.col })).map { it.cle }

    fun testUtilisationPuisModification() {
        if (!ReglagesTvActivity.estTele(ctx)) return
        DispositionAccueil.poserDisposTele(ctx, DispositionAccueil.TELE_RANGEES)
        WidgetsHaStock.ecrire(ctx, WidgetsHa.Reglage("modes_consigne", listOf("input_number.consigne"), WidgetsHa.REGLAGE, 2, 1))
        Accueil.ajouterALaGrille(ctx, consigne, 2, 1)
        Accueil.ajouterALaGrille(ctx, chauffage, 2, 2)
        val a = instrumentation.startActivitySync(Intent(ctx, LanceurActivity::class.java)
            // 1.93.06 : sur la télé, Vision EST l'accueil (déjà ouvert, singleTask) : sans CLEAR_TASK, Android passe par
            // onNewIntent et startActivitySync attend pour toujours une activité qui ne vient pas.
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as LanceurActivity
        instrumentation.setInTouchMode(false)
        try {
            val nom = "Chauffage — température cible confort du séjour"
            var moins: View? = null; var cadre: View? = null
            attendre("le widget de consigne et son −") {
                instrumentation.runOnMainSync {
                    val tout = vues(a.window.decorView)
                    moins = tout.firstOrNull { it.contentDescription == ctx.getString(R.string.wha_diminuer, nom) && it.isShown }
                    cadre = a.window.decorView.findViewWithTag<View>(consigne)
                }
                moins != null && cadre != null
            }
            // UTILISATION : depuis les applis, ▼ à la télécommande arrive sur une commande du widget (− ou +), jamais
            // sur son cadre.
            assertNull("pas de bandeau en Utilisation", bandeau(a))
            assertTrue(moins!!.isFocusable)
            instrumentation.runOnMainSync { a.window.decorView.findViewWithTag<View>("dock:0")?.requestFocus()
                ?: vues(a.window.decorView).firstOrNull { it.tag is String && Accueil.cases(ctx).any { c -> c.cle == it.tag } }?.requestFocus() }
            instrumentation.waitForIdleSync()
            var dedans: View? = null
            val chemin = ArrayList<String>()
            fun decrire(v: View?): String = if (v == null) "rien" else "${v.javaClass.simpleName}[${v.tag}]" +
                (generateSequence(v.parent as? View) { it.parent as? View }.firstOrNull { it.tag is String || it.tag is Accueil.Place }?.let { " dans ${it.tag}" } ?: "")
            instrumentation.runOnMainSync { chemin += "départ " + decrire(a.currentFocus) }
            // ▼ jusqu'à la rangée des widgets, puis ► de widget en widget : on s'arrête dès qu'on est dans la consigne.
            for (k in 0 until 10) {
                touche(if (k < 1 || chemin.none { "carte_widget_" in it || "widget:" in it }) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_RIGHT)
                var f: View? = null; var c: View? = null
                instrumentation.runOnMainSync { f = a.currentFocus; c = a.window.decorView.findViewWithTag(consigne); chemin += decrire(f) }
                if (f != null && c != null && generateSequence(f) { it.parent as? View }.any { it === c }) { dedans = f; cadre = c; break }
            }
            assertNotNull("▼ n'atteint pas le widget de consigne : ${chemin.joinToString(" ; ")}", dedans)
            assertTrue("le focus est sur une commande (${dedans!!.tag}), pas sur le cadre", dedans !== cadre && dedans!!.tag in setOf("widget_moins", "widget_plus"))
            // (Le focus y reste : l'appui long part de là. Pas de requestFocus sur une vue d'avant un redessin.)
            // Appui long sur OK : MODIFICATION, et c'est le cadre (plus −) qui a le focus.
            appuiLongOk()
            assertEquals(ctx.getString(R.string.accueil_bandeau_choisir), bandeau(a))
            instrumentation.runOnMainSync { cadre = a.window.decorView.findViewWithTag<View>(consigne) }
            attendre("le cadre de la consigne prend le focus") { var f = false; instrumentation.runOnMainSync { f = cadre!!.isFocused || cadre!!.hasFocus() }; f }
            // OK : saisi ; ► : il passe après le chauffage ; Retour : il revient.
            val avant = ordreWidgets()
            touche(KeyEvent.KEYCODE_DPAD_CENTER)
            assertEquals(ctx.getString(R.string.accueil_bandeau_deplacer_redim), bandeau(a))
            val sens = if (avant.indexOf(consigne) < avant.indexOf(chauffage)) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
            touche(sens)
            assertFalse("► déplace le cadre saisi", ordreWidgets() == avant)
            touche(KeyEvent.KEYCODE_BACK)
            assertEquals("Retour annule le déplacement", avant, ordreWidgets())
            assertEquals(ctx.getString(R.string.accueil_bandeau_choisir), bandeau(a))
            // OK, OK : la taille ; ► : plus large ; OK : validé.
            instrumentation.runOnMainSync { a.window.decorView.findViewWithTag<View>(consigne)?.requestFocus() }
            instrumentation.waitForIdleSync()
            touche(KeyEvent.KEYCODE_DPAD_CENTER); touche(KeyEvent.KEYCODE_DPAD_CENTER)
            assertEquals(ctx.getString(R.string.accueil_bandeau_taille), bandeau(a))
            touche(KeyEvent.KEYCODE_DPAD_RIGHT)
            touche(KeyEvent.KEYCODE_DPAD_CENTER)
            assertEquals(3, Accueil.disposition(ctx).first { it.cle == consigne }.w)
            // Retour : on quitte la Modification ; le changement validé reste.
            touche(KeyEvent.KEYCODE_BACK)
            assertNull("Retour sans cadre saisi : retour en Utilisation", bandeau(a))
            assertEquals(3, Accueil.disposition(ctx).first { it.cle == consigne }.w)
            assertFalse("l'accueil reste ouvert", a.isFinishing)
        } finally {
            a.finish(); instrumentation.waitForIdleSync()
            Accueil.retirerDeLaGrille(ctx, consigne); Accueil.retirerDeLaGrille(ctx, chauffage)
        }
    }
}
