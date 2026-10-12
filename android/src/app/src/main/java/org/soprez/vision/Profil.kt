// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import org.json.JSONObject

/**
 * Le profil de la personne de cet appareil (Vision 1.73.0) : son temps d'écran du jour, cumulé sur
 * ses appareils (relevé : `temps_personne`, envoyé seulement si son rôle a « Voir son temps d'écran »).
 */
object Profil {
    private const val PREFS = "profil"

    fun releve(ctx: Context, r: JSONObject) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        val t = r.optJSONObject("temps_personne")
        if (t == null) p.remove("temps") else p.putString("temps", t.toString()).putLong("a", System.currentTimeMillis())
        // Les fonctions du rôle de cet appareil (1.71.0) : l'appli s'en sert pour ce qu'elle permet sans le code.
        r.optJSONArray("fonctions")?.let { a -> p.putStringSet("fonctions", (0 until a.length()).map { a.optString(it) }.toSet()) }
        p.apply()
    }

    /** Vide tant que Home Assistant ne les a pas envoyées (ancienne intégration, appareil pas encore relevé). */
    fun fonctions(ctx: Context): Set<String> = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet("fonctions", emptySet()) ?: emptySet()

    /** Le dernier temps reçu, s'il date d'aujourd'hui ; null sinon. */
    fun temps(ctx: Context): JSONObject? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val brut = p.getString("temps", null) ?: return null
        val a = java.util.Calendar.getInstance().apply { timeInMillis = p.getLong("a", 0) }
        val maintenant = java.util.Calendar.getInstance()
        if (a.get(java.util.Calendar.DAY_OF_YEAR) != maintenant.get(java.util.Calendar.DAY_OF_YEAR)
            || a.get(java.util.Calendar.YEAR) != maintenant.get(java.util.Calendar.YEAR)) return null
        return try { JSONObject(brut) } catch (_: Exception) { null }
    }

    /** « 45 min », « 1 h 05 » : la forme de la langue de l'appareil. */
    fun duree(min: Int): String =
        if (min < 60) Tr.s(R.string.profil_duree_min, min) else Tr.s(R.string.profil_duree_h, min / 60, min % 60)
}
