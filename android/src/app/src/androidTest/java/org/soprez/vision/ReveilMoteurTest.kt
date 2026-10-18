// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.AlarmManager
import android.content.Context
import android.os.SystemClock
import android.test.InstrumentationTestCase
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone

/**
 * Réveil (lot 4), sur un vrai Android : lecture du bloc du relevé, armement réel (`AlarmManager.getNextAlarmClock`),
 * accusé (`recu`, `armees`), désarmement, sonnerie et événements, arrêt partagé venu de HA, report local, repli
 * de source (flux injoignable → sonnerie embarquée et `echec`). Doze ne se vérifie que sur un vrai appareil.
 */
class ReveilMoteurTest : InstrumentationTestCase() {
    private val ctx: Context get() = instrumentation.targetContext
    private val am get() = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val tz get() = TimeZone.getDefault()
    private var sauve: Map<String, *> = emptyMap<String, Any>()

    override fun setUp() {
        super.setUp()
        sauve = HashMap(ctx.getSharedPreferences("reveils", 0).all)
        ctx.getSharedPreferences("reveils", 0).edit().clear().commit()
        if (android.os.Build.VERSION.SDK_INT >= 33) try {
            instrumentation.uiAutomation.grantRuntimePermission(ctx.packageName, "android.permission.POST_NOTIFICATIONS")
        } catch (_: Exception) {}
    }

    override fun tearDown() {
        ReveilService.commande(ctx, ReveilService.ACTION_ARRETER_TOUT, "essai")
        SystemClock.sleep(500)
        ctx.getSharedPreferences("reveils", 0).edit().clear().commit()
        Reveils.rearmer(ctx); Reveils.annulerReport(ctx)
        super.tearDown()
    }

    private fun occ(id: String, t: Long): JSONObject {
        val iso = ReveilsModele.iso(t, tz)
        return JSONObject().put("id", "$id@${iso.substring(0, 10)}").put("date", iso.substring(0, 10))
            .put("heure_locale", iso.substring(11, 16)).put("heure", iso)
    }

    private fun bloc(version: String, actif: Boolean = true, ordres: JSONArray = JSONArray(), vararg occs: Pair<String, Long>): JSONObject {
        val liste = JSONArray()
        occs.groupBy { it.first }.forEach { (id, l) ->
            liste.put(JSONObject().put("id", id).put("nom", "Collège").put("signature", "sig-$id")
                .put("occurrences", JSONArray(l.map { occ(id, it.second) }))
                .put("source", JSONObject().put("type", "sonnerie")).put("repli", "sonnerie")
                .put("volume", JSONObject().put("debut", 10).put("fin", 20).put("montee_s", 30))
                .put("repetition", JSONObject().put("minutes", 9).put("max", 3)).put("arret_auto_min", 30).put("allumer_tv", true))
        }
        return JSONObject().put("actif", actif).put("version", version).put("synchro_avant_min", 10).put("liste", liste).put("ordres", ordres)
    }

    private fun releve(b: JSONObject) = Reveils.releve(ctx, JSONObject().put("ok", true).put("reveils", b))

    private fun evenements(): List<JSONObject> = Reveils.aEnvoyer(ctx).getJSONArray("evenements").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    private fun attendre(cond: () -> Boolean, ms: Long = 8000): Boolean {
        val fin = SystemClock.uptimeMillis() + ms
        while (SystemClock.uptimeMillis() < fin) { if (cond()) return true; SystemClock.sleep(200) }
        return cond()
    }

    // Une minute pile, dans 3 h (l'heure locale porte des minutes : l'occurrence tombe à hh:mm:00).
    private fun dans(minutes: Long): Long = ((System.currentTimeMillis() + minutes * 60_000L) / 60_000L) * 60_000L

    fun testLectureDuBloc() {
        val t = dans(180)
        val b = ReveilsModele.lire(bloc("v1", true, JSONArray().put(JSONObject().put("id", "o1").put("type", "arreter").put("reveil", "rv1").put("par", "partage")), "rv1" to t))!!
        assertTrue(b.actif); assertEquals("v1", b.version); assertEquals(10, b.synchroAvantMin)
        assertEquals(1, b.reveils.size); assertEquals("sig-rv1", b.reveils[0].signature)
        assertEquals(t, ReveilsModele.instant(b.reveils[0].occs[0].date, b.reveils[0].occs[0].heure, tz))
        assertEquals("partage", b.ordres[0].par)
        assertNull(ReveilsModele.lire(null))
        assertTrue(ReveilsModele.lire(bloc("v2", false, JSONArray(), "rv1" to t))!!.reveils.isEmpty())
        val ev = ReveilsModele.evenement("sonne", "rv1", "rv1@x", t, tz, "heure_prevue" to "a", "vide" to "", "rien" to null)
        assertEquals(36, ev.getString("eid").length); assertFalse(ev.has("vide")); assertFalse(ev.has("rien"))
        val file = ReveilsModele.ajouter(JSONArray(), ev)
        assertEquals(0, ReveilsModele.acquitter(file, setOf(ev.getString("eid"))).length())
    }

    fun testArmementAccuseEtDesarmement() {
        val t1 = dans(180); val t2 = dans(24 * 60 + 180)
        releve(bloc("v1", true, JSONArray(), "rv1" to t1, "rv1" to t2))
        assertEquals(t1, am.nextAlarmClock?.triggerTime)
        assertEquals(t1, Reveils.prochaineArmee(ctx))
        val envoi = Reveils.aEnvoyer(ctx)
        assertEquals("sig-rv1", envoi.getJSONObject("recu").getString("rv1"))
        assertEquals(2, envoi.getJSONArray("armees").length())
        assertTrue(envoi.getJSONObject("capacites").has("alarme_exacte"))
        // Même version : rien ne bouge ; bloc coupé : tout désarmé.
        releve(bloc("v0", false))
        assertNull(am.nextAlarmClock?.takeIf { it.triggerTime == t1 })
        assertEquals(0L, Reveils.prochaineArmee(ctx))
        // Changement de fuseau ou d'heure : réarmé tel quel (heure locale recalculée).
        releve(bloc("v3", true, JSONArray(), "rv1" to t1))
        Reveils.rearmer(ctx)
        assertEquals(t1, am.nextAlarmClock?.triggerTime)
    }

    fun testSonnerieEtArretPartage() {
        val t = dans(180)
        releve(bloc("v1", true, JSONArray(), "rv1" to t))
        ReveilService.sonner(ctx, "rv1", "rv1@essai", System.currentTimeMillis(), 0, null)
        assertTrue("la sonnerie démarre", attendre({ ReveilService.courant != null }))
        assertEquals("Collège", ReveilService.courant!!.r.nom)
        assertTrue(attendre({ evenements().any { it.getString("type") == "sonne" } }))
        // Un autre appareil a arrêté : HA transmet l'ordre « arreter » (par partage).
        releve(bloc("v1", true, JSONArray().put(JSONObject().put("id", "o-arret").put("type", "arreter").put("reveil", "rv1").put("par", "partage")), "rv1" to t))
        assertTrue("l'arrêt partagé coupe", attendre({ ReveilService.courant == null }))
        val arret = evenements().last { it.getString("type") == "arrete" }
        assertEquals("partage", arret.getString("par"))
        // Le même ordre renvoyé n'est pas rejoué.
        val n = evenements().size
        releve(bloc("v1", true, JSONArray().put(JSONObject().put("id", "o-arret").put("type", "arreter").put("reveil", "rv1").put("par", "partage")), "rv1" to t))
        SystemClock.sleep(600)
        assertEquals(n, evenements().size)
        // Acquitté par le relevé suivant : la file se vide.
        Reveils.acquitter(ctx, Reveils.aEnvoyer(ctx))
        assertEquals(0, evenements().size)
    }

    fun testReporterArmeUneAlarmeLocale() {
        releve(bloc("v1", true, JSONArray(), "rv1" to dans(600)))
        ReveilService.sonner(ctx, "rv1", "rv1@essai", System.currentTimeMillis(), 0, null)
        assertTrue(attendre({ ReveilService.courant != null }))
        val avant = System.currentTimeMillis()
        ReveilService.commande(ctx, ReveilService.ACTION_REPORTER, "appareil")
        assertTrue(attendre({ ReveilService.courant == null }))
        val rep = evenements().last { it.getString("type") == "reporte" }
        assertEquals(1, rep.getInt("nombre"))
        val prochaine = am.nextAlarmClock?.triggerTime ?: 0L
        assertTrue("report dans 9 min ($prochaine)", prochaine in (avant + 8 * 60_000L)..(avant + 10 * 60_000L))
    }

    /** De bout en bout : une vraie alarme système, écran éteint et verrouillé ; l'appareil sonne seul et montre l'écran. */
    fun testAlarmeReelleEcranEteint() {
        val t = ((System.currentTimeMillis() + 90_000L) / 60_000L) * 60_000L + 60_000L  // la minute pleine dans 1 à 2 min
        releve(bloc("v-reel", true, JSONArray(), "rv1" to t))
        assertEquals(t, am.nextAlarmClock?.triggerTime)
        shell("input keyevent KEYCODE_SLEEP")
        assertTrue("l'alarme sonne à l'heure", attendre({ ReveilService.courant != null }, t - System.currentTimeMillis() + 20_000))
        val ecart = System.currentTimeMillis() - t
        assertTrue("écart $ecart ms", ecart in -1_000L..15_000L)
        assertTrue("l'écran s'est rallumé", attendre({ (ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive }, 10_000))
        SystemClock.sleep(2500)
        val image = instrumentation.uiAutomation.takeScreenshot()
        java.io.File(ctx.getExternalFilesDir(null), "reveil-ecran-verrouille.png").outputStream().use { image?.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(evenements().any { it.getString("type") == "sonne" && it.getString("reveil") == "rv1" })
        ReveilService.commande(ctx, ReveilService.ACTION_ARRETER, "appareil")
        assertTrue(attendre({ ReveilService.courant == null }))
    }

    private fun shell(cmd: String) {
        val pfd = instrumentation.uiAutomation.executeShellCommand(cmd)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }

    /** Redémarrage : un report en attente est réarmé (les alarmes système sont effacées au démarrage). */
    fun testRedemarrageReprendLeReport() {
        releve(bloc("v1", true, JSONArray(), "rv1" to dans(600)))
        val t = dans(20)
        Reveils.armerReport(ctx, "rv1", "rv1@essai", t - 60_000L, t, 1, null)
        Reveils.annulerReportAlarmeSeulement(ctx)  // ce que fait un redémarrage : l'alarme disparaît, la trace reste
        ReveilReceiver().onReceive(ctx, android.content.Intent(android.content.Intent.ACTION_BOOT_COMPLETED))
        assertEquals(t, am.nextAlarmClock?.triggerTime)
    }

    /** Redémarrage pendant l'heure : l'occurrence manquée sonne tout de suite, une seule fois. */
    fun testRedemarrageRattrapeUnReveilManque() {
        val passe = ((System.currentTimeMillis() - 120_000L) / 60_000L) * 60_000L
        releve(bloc("v1", true, JSONArray(), "rv1" to passe, "rv1" to dans(24 * 60)))
        ReveilReceiver().onReceive(ctx, android.content.Intent(android.content.Intent.ACTION_BOOT_COMPLETED))
        assertTrue("rattrapé", attendre({ ReveilService.courant != null }))
        ReveilService.commande(ctx, ReveilService.ACTION_ARRETER, "appareil")
        assertTrue(attendre({ ReveilService.courant == null }))
        ReveilReceiver().onReceive(ctx, android.content.Intent(android.content.Intent.ACTION_BOOT_COMPLETED))
        SystemClock.sleep(1500)
        assertNull("pas deux fois", ReveilService.courant)
    }

    fun testFluxInjoignableRepliSurLaSonnerie() {
        val o = JSONObject().put("id", "o-flux").put("type", "sonner").put("reveil", "").put("occurrence", "ordre:essai")
            .put("nom", "Radio").put("source", JSONObject().put("type", "url").put("uri", "http://127.0.0.1:9/flux-absent.mp3"))
            .put("volume", JSONObject().put("debut", 5).put("fin", 10).put("montee_s", 10)).put("repetition", JSONObject().put("minutes", 9).put("max", 3))
            .put("arret_auto_min", 30)
        ReveilService.ordre(ctx, o)
        assertTrue(attendre({ ReveilService.courant != null }))
        assertTrue("repli joué", attendre({ ReveilService.courant?.repli == true }, 25_000))
        val echec = evenements().last { it.getString("type") == "echec" }
        assertEquals("source", echec.getString("raison")); assertTrue(echec.getBoolean("repli_joue"))
    }
}
