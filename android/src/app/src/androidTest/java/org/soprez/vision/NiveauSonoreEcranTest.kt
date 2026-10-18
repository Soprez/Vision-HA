// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.test.InstrumentationTestCase
import org.json.JSONArray
import org.json.JSONObject

/**
 * Niveau sonore, sur un vrai Android : le service micro démarre au premier plan quand Home Assistant le demande
 * (module allumé, appareil activé, micro autorisé), le relevé ne porte que des nombres, « actif: false » et la
 * coupure du module l'arrêtent ; les dépassements restent jusqu'à leur remise. L'émulateur est muet : le niveau
 * peut rester absent (micro silencieux ou indisponible), seul son format est vérifié.
 */
class NiveauSonoreEcranTest : InstrumentationTestCase() {
    private val ctx: Context get() = instrumentation.targetContext
    private var sauveSon: Map<String, *> = emptyMap<String, Any>()
    private var sauveModules: String? = null

    override fun setUp() {
        super.setUp()
        sauveSon = HashMap(ctx.getSharedPreferences("niveau_sonore", 0).all)
        sauveModules = Config(ctx).modules
        ctx.getSharedPreferences("niveau_sonore", 0).edit().clear().commit()
        for (p in listOf("android.permission.RECORD_AUDIO", "android.permission.POST_NOTIFICATIONS")) try {
            instrumentation.uiAutomation.grantRuntimePermission(ctx.packageName, p)
        } catch (_: Exception) {}
        modules("plages", "son")
    }

    override fun tearDown() {
        NiveauSonore.releve(ctx, JSONObject().put("actif", false))
        attendre { !MesureSonService.enMarche }
        ctx.getSharedPreferences("niveau_sonore", 0).edit().clear().apply {
            for ((k, v) in sauveSon) if (v is String) putString(k, v)
        }.commit()
        Config(ctx).modules = sauveModules
        // Rien de lu avant (null : tout actif) : pas de champ « modules », jamais une liste vide (tout coupé).
        Modules.memoriser(ctx, sauveModules?.let { m -> JSONObject().put("modules", JSONArray(m.split(',').filter { it.isNotEmpty() })) } ?: JSONObject())
        super.tearDown()
    }

    private fun modules(vararg ids: String) = Modules.memoriser(ctx, JSONObject().put("modules", JSONArray(ids.toList())))

    private fun bloc(actif: Boolean = true) = JSONObject().put("actif", actif).put("seuil_db", 70).put("sensibilite_s", 3)
        .put("plages", JSONArray()).put("calibration_db", 0).put("alertes", 1).put("alertes_avant_coupure", 3)

    private fun attendre(ms: Long = 8_000, condition: () -> Boolean): Boolean {
        val fin = SystemClock.uptimeMillis() + ms
        while (SystemClock.uptimeMillis() < fin) { if (condition()) return true; SystemClock.sleep(100) }
        return condition()
    }

    /** Un écran de Vision au premier plan (le micro ne se lance qu'ainsi) : les réglages appellent assurer(). */
    private fun premierPlan() = instrumentation.startActivitySync(EcransReglages.hote(ctx).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    fun testDemarreAuPremierPlanEtSArrete() {
        if (!NiveauSonore.micro(ctx)) return  // appareil sans micro : rien à démarrer (la ligne le dit)
        NiveauSonore.releve(ctx, bloc())
        assertTrue(NiveauSonore.demandee(ctx))
        val a = premierPlan()
        try {
            assertTrue("le service micro démarre au premier plan", attendre { MesureSonService.enMarche })
            SystemClock.sleep(6_000)  // une publication du niveau (toutes les 5 s)
            val r = NiveauSonore.aEnvoyer(ctx)!!
            for (k in r.keys()) assertTrue("champ inattendu : $k", k in setOf("niveau", "maximum", "depassements"))
            if (r.has("niveau")) { val v = r.getDouble("niveau"); assertTrue("niveau $v", v in 0.0..100.0) }
            // La ligne des Paramètres dit l'état de la mesure.
            assertEquals(ctx.getString(R.string.son_etat_en_cours), NiveauSonore.libelle(ctx))
            // « actif: false » au relevé : le micro s'arrête.
            NiveauSonore.releve(ctx, bloc(actif = false))
            assertTrue("le service s'arrête", attendre { !MesureSonService.enMarche })
            assertNull(NiveauSonore.aEnvoyer(ctx))
        } finally { a.finish() }
    }

    fun testModuleCoupeArreteLeMicro() {
        if (!NiveauSonore.micro(ctx)) return
        NiveauSonore.releve(ctx, bloc())
        val a = premierPlan()
        try {
            assertTrue(attendre { MesureSonService.enMarche })
            modules("plages")  // Home Assistant coupe le module
            assertTrue("coupure du module : le micro s'arrête", attendre { !MesureSonService.enMarche })
            // Même si un vieux bloc « actif » arrivait encore, sans le module rien ne repart.
            NiveauSonore.releve(ctx, bloc())
            NiveauSonore.assurer(ctx)
            SystemClock.sleep(1_500)
            assertFalse(MesureSonService.enMarche)
        } finally { a.finish() }
    }

    fun testDepassementsGardesJusquALaRemise() {
        NiveauSonore.releve(ctx, bloc())
        NiveauSonore.noterDepassement(ctx, 1_000L, 81.27f)
        NiveauSonore.noterDepassement(ctx, 2_000L, 90f)
        val envoye = NiveauSonore.aEnvoyer(ctx)!!
        val d = envoye.getJSONArray("depassements")
        assertEquals(2, d.length())
        assertEquals(81.3, d.getJSONObject(0).getDouble("db"), 0.0)
        assertEquals(setOf("ts", "db"), d.getJSONObject(0).keys().asSequence().toSet())
        // Un troisième arrive pendant le relevé : il reste après l'accusé.
        NiveauSonore.noterDepassement(ctx, 3_000L, 77f)
        NiveauSonore.acquitter(ctx, envoye)
        val reste = NiveauSonore.aEnvoyer(ctx)!!.getJSONArray("depassements")
        assertEquals(1, reste.length())
        assertEquals(3_000L, reste.getJSONObject(0).getLong("ts"))
    }
}
