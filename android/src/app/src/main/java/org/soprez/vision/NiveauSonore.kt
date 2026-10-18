// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/**
 * Niveau sonore (backlog n° 31, contrat docs/son-contrat-appareils.md côté Home Assistant) : ce que l'appareil
 * reçoit au relevé (bloc `son`), ce qu'il renvoie (des nombres seulement), et le démarrage de la mesure.
 *
 * - Module « son » coupé, mesure éteinte pour l'appareil, pas de micro ou pas d'autorisation : rien ne démarre.
 * - Le micro ne s'ouvre qu'à travers [MesureSonService] (service de premier plan « microphone ») : Android ne permet
 *   de le lancer que pendant qu'un écran de Vision est visible ; l'accueil de Vision et les réglages appellent
 *   [assurer] en revenant au premier plan, le relevé seulement s'il le peut.
 * - Les dépassements pas encore remis sont gardés (préférences) jusqu'à un relevé réussi.
 */
object NiveauSonore {
    private const val PREFS = "niveau_sonore"
    @Volatile var reglage: NiveauSonoreCalcul.Reglage = NiveauSonoreCalcul.Reglage.ETEINT
        private set
    @Volatile var niveau: Float? = null
    @Volatile var maximum: Float? = null

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun micro(ctx: Context): Boolean = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
    fun autorise(ctx: Context): Boolean = Build.VERSION.SDK_INT < 23 ||
        ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** La mesure est demandée par Home Assistant pour cet appareil (module allumé, appareil activé). */
    fun demandee(ctx: Context): Boolean = Modules.actif(ctx, Modules.SON) && charger(ctx).actif

    /** Ce qui manque pour mesurer, pour l'écran des réglages : null = tout est prêt. */
    enum class Manque { MICRO, AUTORISATION }
    fun manque(ctx: Context): Manque? = when { !micro(ctx) -> Manque.MICRO; !autorise(ctx) -> Manque.AUTORISATION; else -> null }

    // ------------------------------------------------------------------ relevé

    fun charger(ctx: Context): NiveauSonoreCalcul.Reglage {
        val brut = p(ctx).getString("reglage", null) ?: return NiveauSonoreCalcul.Reglage.ETEINT
        return try { lire(JSONObject(brut)).also { reglage = it } } catch (_: Exception) { NiveauSonoreCalcul.Reglage.ETEINT }
    }

    private fun lire(o: JSONObject): NiveauSonoreCalcul.Reglage {
        if (!o.optBoolean("actif")) return NiveauSonoreCalcul.Reglage.ETEINT
        val plages = ArrayList<Pair<Int, Int>>()
        val a = o.optJSONArray("plages") ?: JSONArray()
        for (i in 0 until a.length()) {
            val pl = a.optJSONObject(i) ?: continue
            val d = NiveauSonoreCalcul.minutes(pl.optString("debut")) ?: continue
            val f = NiveauSonoreCalcul.minutes(pl.optString("fin")) ?: continue
            plages += d to f
        }
        return NiveauSonoreCalcul.Reglage(true, o.optInt("seuil_db", 75).coerceIn(30, 100), o.optInt("sensibilite_s", 5).coerceIn(1, 120),
            plages, o.optInt("calibration_db", 0).coerceIn(-30, 30), o.optInt("alertes", 0), o.optInt("alertes_avant_coupure", 3))
    }

    /** La réponse du relevé : garde les réglages, arrête la mesure si elle n'est plus voulue, la lance si possible. */
    fun releve(ctx: Context, bloc: JSONObject?) {
        val avant = reglage
        val actif = Modules.actif(ctx, Modules.SON) && bloc?.optBoolean("actif") == true
        val r = if (actif) lire(bloc!!) else NiveauSonoreCalcul.Reglage.ETEINT
        p(ctx).edit().putString("reglage", (if (actif) bloc else JSONObject().put("actif", false)).toString()).apply()
        reglage = r
        if (!r.actif) { MesureSonService.arreter(ctx); niveau = null; maximum = null; return }
        if (MesureSonService.enMarche) { if (avant.seuil != r.seuil || avant.sensibiliteS != r.sensibiliteS || avant.calibration != r.calibration) MesureSonService.reconfigurer() }
        else assurer(ctx)
    }

    /** Lance la mesure si elle est voulue et possible (appelé au premier plan : accueil, réglages). */
    fun assurer(ctx: Context) {
        if (!demandee(ctx)) { MesureSonService.arreter(ctx); return }
        if (manque(ctx) != null || MesureSonService.enMarche) return
        try {
            MesureSonService.lancer(ctx)
        } catch (_: Exception) {
            // Android refuse le micro hors du premier plan : l'accueil réessaiera au prochain retour.
        }
    }

    /** Un dépassement constaté par la mesure : gardé jusqu'à sa remise. */
    @Synchronized fun noterDepassement(ctx: Context, ts: Long, db: Float) {
        val a = try { JSONArray(p(ctx).getString("depassements", "[]")) } catch (_: Exception) { JSONArray() }
        a.put(JSONObject().put("ts", ts).put("db", NiveauSonoreCalcul.arrondi(db)))
        while (a.length() > 20) a.remove(0)
        p(ctx).edit().putString("depassements", a.toString()).apply()
    }

    /** Le bloc `son` du relevé : des nombres seulement ; null si la mesure n'est pas voulue. */
    @Synchronized fun aEnvoyer(ctx: Context): JSONObject? {
        if (!reglage.actif && !charger(ctx).actif) return null
        val o = JSONObject()
        niveau?.let { o.put("niveau", NiveauSonoreCalcul.arrondi(it)) }
        maximum?.let { o.put("maximum", NiveauSonoreCalcul.arrondi(it)) }
        val a = try { JSONArray(p(ctx).getString("depassements", "[]")) } catch (_: Exception) { JSONArray() }
        if (a.length() > 0) o.put("depassements", a)
        return o
    }

    /** Relevé réussi : les dépassements remis sont oubliés (ceux arrivés entre-temps restent). */
    @Synchronized fun acquitter(ctx: Context, envoye: JSONObject?) {
        val remis = envoye?.optJSONArray("depassements") ?: return
        val dernier = (0 until remis.length()).maxOfOrNull { remis.getJSONObject(it).optLong("ts") } ?: return
        val a = try { JSONArray(p(ctx).getString("depassements", "[]")) } catch (_: Exception) { JSONArray() }
        val reste = JSONArray()
        for (i in 0 until a.length()) a.optJSONObject(i)?.let { if (it.optLong("ts") > dernier) reste.put(it) }
        p(ctx).edit().putString("depassements", reste.toString()).apply()
    }

    // ------------------------------------------------------------------ écran

    /** L'état pour une ligne ou une tuile : mesure en cours, micro à autoriser, pas de micro, en attente. */
    fun libelle(ctx: Context): String = ctx.getString(when {
        !micro(ctx) -> R.string.son_etat_sans_micro
        !autorise(ctx) -> R.string.son_etat_autoriser
        MesureSonService.enMarche -> R.string.son_etat_en_cours
        else -> R.string.son_etat_attente
    })

    /** Ce qui est mesuré, ce qui ne l'est pas, ce qui part vers Home Assistant ; puis l'autorisation du micro. */
    fun ouvrir(act: android.app.Activity) {
        val b = Ui.dialogue(act).setTitle(act.getString(R.string.son_titre))
            .setMessage(act.getString(if (micro(act)) R.string.son_vie_privee else R.string.son_vie_privee_sans_micro))
        if (micro(act) && !autorise(act)) b.setPositiveButton(act.getString(R.string.son_autoriser)) { _, _ ->
            if (Build.VERSION.SDK_INT >= 23) act.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7503)
        }.setNegativeButton(act.getString(R.string.son_plus_tard), null)
        else b.setPositiveButton(act.getString(R.string.son_ok), null)
        b.show()
    }
}
