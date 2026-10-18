// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone

/**
 * Réveil (lot 4) : ce que l'appareil garde et arme (contrat docs/reveil-contrat-appareils.md, dépôt HA).
 *
 * - Le relevé apporte le bloc `reveils` (AgentService → [releve]) ; il est gardé en préférences et l'appareil
 *   réarme quand sa `version` change. Sans HA ni réseau, la dernière liste connue continue de sonner.
 * - Une seule alarme système à la fois : l'occurrence suivante, par `AlarmManager.setAlarmClock` (la seule
 *   API qui traverse Doze, avec l'icône de réveil) ; elle est réarmée à chaque sonnerie, au démarrage, à la
 *   mise à jour de l'appli et à chaque changement d'heure ou de fuseau ([rearmer]).
 * - Une alarme de synchronisation à H − synchro_avant_min force un relevé (modification de dernière minute).
 * - Les événements (sonne, reporte, arrete, echec) attendent en file jusqu'à l'accusé du relevé suivant.
 */
object Reveils {
    private const val PREFS = "reveils"
    private const val RC_SONNER = 7401
    private const val RC_SYNCHRO = 7402
    private const val RC_REPORT = 7403
    private const val RC_RATTRAPAGE = 7404
    /** Délai d'une alarme « tout de suite » posée depuis le démarrage. */
    private const val BIENTOT_MS = 3_000L

    @Volatile private var armeDansCeProcessus = false

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ Ce qui est gardé

    fun bloc(ctx: Context): ReveilsModele.Bloc? =
        prefs(ctx).getString("bloc", null)?.let { try { ReveilsModele.lire(JSONObject(it)) } catch (_: Exception) { null } }

    fun reveil(ctx: Context, id: String): ReveilsModele.Reveil? = bloc(ctx)?.reveils?.firstOrNull { it.id == id }

    fun sonnees(ctx: Context): Set<String> = prefs(ctx).getStringSet("sonnees", emptySet()) ?: emptySet()

    fun noterSonnee(ctx: Context, occ: String) {
        if (occ.isEmpty()) return
        val l = (prefs(ctx).getString("sonnees_ordre", "") ?: "").split('\n').filter { it.isNotEmpty() } + occ
        val garde = l.takeLast(40)
        prefs(ctx).edit().putString("sonnees_ordre", garde.joinToString("\n")).putStringSet("sonnees", garde.toSet()).apply()
    }

    private fun file(ctx: Context): JSONArray = try { JSONArray(prefs(ctx).getString("evenements", "[]")) } catch (_: Exception) { JSONArray() }

    // ------------------------------------------------------------------ Relevé : HA → appareil

    /** Appelé à chaque relevé réussi, hors du fil principal. */
    fun releve(ctx: Context, r: JSONObject) {
        val modules = Modules.actifs(ctx)
        val brut = r.optJSONObject("reveils")
        val bloc = ReveilsModele.lire(brut)
        val avant = bloc(ctx)
        if (ReveilsModele.desarmerTout(modules, bloc, avant?.reveils?.isNotEmpty() == true)) {
            if (avant != null) { prefs(ctx).edit().remove("bloc").apply(); rearmer(ctx) }
            if (!Modules.actifDans(modules, Modules.REVEIL)) ReveilService.commande(ctx, ReveilService.ACTION_ARRETER_TOUT, "service")
            return
        }
        if (bloc == null || brut == null) return
        if (bloc.version != avant?.version) {
            prefs(ctx).edit().putString("bloc", brut.apply { remove("ordres") }.toString()).apply()
            rearmer(ctx)
            armeDansCeProcessus = true
        } else if (!armeDansCeProcessus) {
            // Premier relevé de ce processus : un « Forcer l'arrêt » a pu effacer les alarmes système.
            rearmer(ctx)
            armeDansCeProcessus = true
        }
        // Les ordres : exécutés une fois chacun (un id déjà vu est ignoré).
        val vus = (prefs(ctx).getString("ordres_vus", "") ?: "").split('\n').filter { it.isNotEmpty() }.toMutableList()
        for (o in bloc.ordres) {
            if (o.id.isEmpty() || o.id in vus) continue
            vus.add(o.id)
            ReveilService.ordre(ctx, o.brut)
        }
        prefs(ctx).edit().putString("ordres_vus", vus.takeLast(60).joinToString("\n")).apply()
    }

    // ------------------------------------------------------------------ Relevé : appareil → HA

    /** Ce que le relevé remonte : configurations armées, occurrences armées, capacités, événements en attente. */
    fun aEnvoyer(ctx: Context): JSONObject {
        val tz = TimeZone.getDefault()
        val maintenant = System.currentTimeMillis()
        val b = bloc(ctx)
        return JSONObject()
            .put("recu", ReveilsModele.recu(b))
            .put("armees", ReveilsModele.armees(b, maintenant, tz, sonnees(ctx)))
            .put("capacites", capacites(ctx))
            .put("evenements", file(ctx))
    }

    /** Après un relevé accepté (200) : les événements envoyés sont retirés de la file. */
    fun acquitter(ctx: Context, envoye: JSONObject?) {
        val a = envoye?.optJSONArray("evenements") ?: return
        val eids = (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("eid") }.toSet()
        if (eids.isEmpty()) return
        synchronized(this) { prefs(ctx).edit().putString("evenements", ReveilsModele.acquitter(file(ctx), eids).toString()).apply() }
    }

    /** Un événement en file, puis un relevé tout de suite (l'appareil est éveillé à ce moment-là). */
    fun noter(ctx: Context, ev: JSONObject) {
        synchronized(this) { prefs(ctx).edit().putString("evenements", ReveilsModele.ajouter(file(ctx), ev).toString()).apply() }
        demanderReleve(ctx)
    }

    fun demanderReleve(ctx: Context) {
        if (Config(ctx).inscrit) try { AgentService.demarrer(ctx) } catch (_: Exception) {}
    }

    fun capacites(ctx: Context): JSONObject = JSONObject()
        .put("alarme_exacte", alarmeExacte(ctx))
        .put("plein_ecran", pleinEcran(ctx))
        .put("sources", JSONArray().put("sonnerie").put("url"))

    fun alarmeExacte(ctx: Context): Boolean = if (Build.VERSION.SDK_INT >= 31)
        (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms() else true

    /** L'écran de réveil peut-il s'ouvrir par-dessus le verrouillage ? Il faut les notifications de Vision et,
     *  dès Android 14, la permission « plein écran » (retirable par l'utilisateur). */
    fun pleinEcran(ctx: Context): Boolean {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (Build.VERSION.SDK_INT >= 24 && !nm.areNotificationsEnabled()) return false
        return if (Build.VERSION.SDK_INT >= 34) nm.canUseFullScreenIntent() else true
    }

    // ------------------------------------------------------------------ Armement

    private fun intention(ctx: Context, action: String, rc: Int, plus: (Intent) -> Unit = {}): PendingIntent =
        PendingIntent.getBroadcast(ctx, rc, Intent(ctx, ReveilReceiver::class.java).setAction(action).also(plus),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun ecranReveils(ctx: Context): PendingIntent =
        PendingIntent.getActivity(ctx, RC_SONNER, Intent(ctx, ReveilsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** Arme l'occurrence suivante et l'alarme de synchronisation ; sans occurrence, désarme. */
    fun rearmer(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val tz = TimeZone.getDefault()
        val maintenant = System.currentTimeMillis()
        val b = bloc(ctx)
        val suivante = ReveilsModele.prochaine(b, maintenant, tz, sonnees(ctx))
        val sonner = intention(ctx, ReveilReceiver.ACTION_SONNER, RC_SONNER)
        val synchro = intention(ctx, ReveilReceiver.ACTION_SYNCHRO, RC_SYNCHRO)
        am.cancel(sonner); am.cancel(synchro)
        if (suivante == null) { prefs(ctx).edit().remove("armee").apply(); return }
        val (occ, t) = suivante
        val pi = intention(ctx, ReveilReceiver.ACTION_SONNER, RC_SONNER) {
            it.putExtra(ReveilReceiver.EXTRA_REVEIL, occ.reveil).putExtra(ReveilReceiver.EXTRA_OCC, occ.id).putExtra(ReveilReceiver.EXTRA_PREVU, t)
        }
        poser(ctx, am, t, pi)
        ReveilsModele.synchro(t, b?.synchroAvantMin ?: ReveilsModele.SYNCHRO_DEFAUT, maintenant)?.let { ts ->
            try {
                if (Build.VERSION.SDK_INT >= 23 && alarmeExacte(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ts, synchro)
                else if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ts, synchro)
                else am.set(AlarmManager.RTC_WAKEUP, ts, synchro)
            } catch (_: SecurityException) {}
        }
        prefs(ctx).edit().putString("armee", occ.id).putLong("armee_a", t).apply()
    }

    /** L'heure de la prochaine alarme armée (0 = aucune) : pour l'écran « Réveils » et les essais. */
    fun prochaineArmee(ctx: Context): Long = prefs(ctx).getLong("armee_a", 0L).takeIf { prefs(ctx).contains("armee") } ?: 0L

    private fun poser(ctx: Context, am: AlarmManager, t: Long, pi: PendingIntent) {
        try {
            if (alarmeExacte(ctx)) am.setAlarmClock(AlarmManager.AlarmClockInfo(t, ecranReveils(ctx)), pi)
            else if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)  // permission retirée : au mieux
            else am.set(AlarmManager.RTC_WAKEUP, t, pi)
        } catch (_: SecurityException) {
            if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
        }
    }

    /** Le report d'une sonnerie : une alarme à part, réarmée en local, qui rejoue la même occurrence. */
    fun armerReport(ctx: Context, reveil: String, occ: String, prevu: Long, t: Long, nombre: Int, ordre: String?) {
        // Gardé : un redémarrage efface les alarmes système, le report doit survivre (reprendreReport).
        prefs(ctx).edit().putString("report", JSONObject().put("reveil", reveil).put("occ", occ).put("prevu", prevu)
            .put("t", t).put("nombre", nombre).apply { if (ordre != null) put("ordre", ordre) }.toString()).apply()
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        poser(ctx, am, t, intention(ctx, ReveilReceiver.ACTION_SONNER, RC_REPORT) {
            it.putExtra(ReveilReceiver.EXTRA_REVEIL, reveil).putExtra(ReveilReceiver.EXTRA_OCC, occ).putExtra(ReveilReceiver.EXTRA_PREVU, prevu)
                .putExtra(ReveilReceiver.EXTRA_REPORTS, nombre)
            if (ordre != null) it.putExtra(ReveilReceiver.EXTRA_ORDRE, ordre)
        })
    }

    fun annulerReport(ctx: Context) {
        prefs(ctx).edit().remove("report").apply()
        (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(intention(ctx, ReveilReceiver.ACTION_SONNER, RC_REPORT))
    }

    /** Pour les essais : l'alarme du report disparaît (comme au redémarrage), sa trace reste. */
    internal fun annulerReportAlarmeSeulement(ctx: Context) {
        (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(intention(ctx, ReveilReceiver.ACTION_SONNER, RC_REPORT))
    }

    /** Le report a sonné : il n'est plus à reprendre. */
    fun reportJoue(ctx: Context) { prefs(ctx).edit().remove("report").apply() }

    /** Après un redémarrage ou un changement d'heure : le report gardé est réarmé, ou sonne s'il vient de passer. */
    fun reprendreReport(ctx: Context) {
        val o = prefs(ctx).getString("report", null)?.let { try { JSONObject(it) } catch (_: Exception) { null } } ?: return
        val t = o.optLong("t")
        when (ReveilsModele.sortReport(t, System.currentTimeMillis())) {
            "armer" -> armerReport(ctx, o.optString("reveil"), o.optString("occ"), o.optLong("prevu"), t, o.optInt("nombre"), o.optString("ordre").ifEmpty { null })
            // Au démarrage, un service « mediaPlayback » ne peut pas partir du récepteur (Android 15) : une alarme
            // dans quelques secondes le lance, elle, en règle.
            "sonner" -> armerReport(ctx, o.optString("reveil"), o.optString("occ"), o.optLong("prevu"), System.currentTimeMillis() + BIENTOT_MS,
                o.optInt("nombre"), o.optString("ordre").ifEmpty { null })
            else -> reportJoue(ctx)
        }
    }

    /** Une occurrence manquée pendant que l'appareil était éteint (ou l'heure changée) sonne tout de suite. */
    fun rattraper(ctx: Context) {
        val (occ, t) = ReveilsModele.manquee(bloc(ctx), System.currentTimeMillis(), TimeZone.getDefault(), sonnees(ctx)) ?: return
        noterSonnee(ctx, occ.id)
        // Même raison que pour le report : une alarme dans quelques secondes, jamais le service depuis le démarrage.
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        poser(ctx, am, System.currentTimeMillis() + BIENTOT_MS, intention(ctx, ReveilReceiver.ACTION_SONNER, RC_RATTRAPAGE) {
            it.putExtra(ReveilReceiver.EXTRA_REVEIL, occ.reveil).putExtra(ReveilReceiver.EXTRA_OCC, occ.id).putExtra(ReveilReceiver.EXTRA_PREVU, t)
        })
    }
}
