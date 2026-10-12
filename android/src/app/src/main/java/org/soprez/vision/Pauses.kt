// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * La pause de la protection (Vision 1.56.0, intégration 1.70.0) : durée choisie au moment de la
 * pause (5, 15, 30, 60 min, jusqu'à demain matin), jamais plus que la « Durée max de pause » de
 * l'appareil, réglée dans Home Assistant (relevé : `pause_max`). Chaque pause est envoyée au relevé
 * (`pauses`) : Home Assistant la journalise.
 */
object Pauses {
    private const val PREFS = "pauses"

    fun max(ctx: Context): Int = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("max", 60)

    fun releve(ctx: Context, r: JSONObject) {
        if (r.has("pause_max")) ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("max", r.optInt("pause_max", 60)).apply()
    }

    /** Minutes jusqu'à demain 7 h. */
    fun jusquADemainMatin(): Int {
        val c = Calendar.getInstance()
        val maintenant = c.timeInMillis
        c.add(Calendar.DAY_OF_YEAR, if (c.get(Calendar.HOUR_OF_DAY) < 7) 0 else 1)
        c.set(Calendar.HOUR_OF_DAY, 7); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0)
        return ((c.timeInMillis - maintenant) / 60_000L).toInt().coerceAtLeast(1)
    }

    /** Demain 7 h (ou ce matin 7 h avant 7 h), à l'heure locale du téléphone (« 07:00 », « 7:00 AM »). */
    private fun demainMatin(): String = Tr.heure(System.currentTimeMillis() + jusquADemainMatin() * 60_000L)

    /** Les choix proposés : (libellé, minutes), seulement ceux sous le maximum. */
    fun choix(ctx: Context): List<Pair<String, Int>> {
        val m = max(ctx)
        val l = listOf(5, 15, 30, 60).filter { it <= m }.map { Tr.duree(it) to it }.toMutableList()
        if (l.isEmpty()) l.add(Tr.duree(m) to m)
        val nuit = jusquADemainMatin()
        if (nuit <= m) l.add(Tr.s(R.string.pauses_jusqua_demain, demainMatin()) to nuit)
        return l
    }

    /** Met en pause et le note pour Home Assistant. */
    fun mettre(ctx: Context, minutes: Int, methode: String) {
        val m = minutes.coerceIn(1, max(ctx))
        val cfg = Config(ctx)
        cfg.parentJusqua = System.currentTimeMillis() + m * 60_000L
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val a = try { JSONArray(p.getString("liste", "[]")) } catch (_: Exception) { JSONArray() }
        val garde = JSONArray()
        val limite = System.currentTimeMillis() / 1000.0 - 2 * 86400
        for (i in 0 until a.length()) a.optJSONObject(i)?.takeIf { it.optDouble("debut") > limite }?.let { garde.put(it) }
        garde.put(JSONObject().put("debut", System.currentTimeMillis() / 1000.0).put("minutes", m).put("methode", methode))
        p.edit().putString("liste", garde.toString()).apply()
    }

    /** Les pauses récentes, pour le relevé (Home Assistant ignore celles déjà journalisées). */
    fun aEnvoyer(ctx: Context): JSONArray = try {
        JSONArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("liste", "[]"))
    } catch (_: Exception) { JSONArray() }
}
