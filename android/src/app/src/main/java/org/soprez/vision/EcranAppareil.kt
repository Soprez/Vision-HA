// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import org.json.JSONObject

/**
 * 17-10 (l'auteur : « désactiver l'écran de veille par appareil ») : l'écran de CET appareil, réglé depuis Home Assistant
 * (service pc_parental.ecran_regler, bloc « ecran » du relevé). Deux réglages, aux défauts sûrs qui ne changent rien
 * aux installations existantes :
 * - [veilleVision] : l'écran de veille de Vision (horloge, photos, playlist) peut venir sur cet appareil (oui) ;
 * - [garderAllume] : l'accueil de Vision empêche la mise en veille de l'écran Android (non).
 * Bloc absent (Home Assistant plus ancien) : rien ne change. Distinct des réglages locaux (Local) : c'est HA qui décide.
 */
object EcranAppareil {
    private const val PREFS = "ecran_ha"

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun veilleVision(ctx: Context): Boolean = p(ctx).getBoolean("veille_vision", true)
    fun garderAllume(ctx: Context): Boolean = p(ctx).getBoolean("garder_allume", false)

    /** Le bloc « ecran » du relevé ; rend vrai si un réglage a changé (l'accueil se met à jour). */
    fun releve(ctx: Context, o: JSONObject?): Boolean {
        if (o == null) return false
        val veille = o.optBoolean("veille_vision", true)
        val allume = o.optBoolean("garder_allume", false)
        if (veille == veilleVision(ctx) && allume == garderAllume(ctx)) return false
        p(ctx).edit().putBoolean("veille_vision", veille).putBoolean("garder_allume", allume).apply()
        Local.toucher()
        return true
    }
}
