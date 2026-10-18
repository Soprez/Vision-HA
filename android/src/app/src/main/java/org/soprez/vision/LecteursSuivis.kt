// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context

/**
 * Les lecteurs que la carte « en cours de lecture » de l'accueil a le droit de montrer (l'auteur : « je ne joue pas de
 * musique sur un matériel à moi, donc pourquoi je vois cette musique ? »).
 *
 * Sans Party, Home Assistant renvoie le lecteur qui joue « par défaut » : n'importe quel lecteur Music Assistant du
 * Home Assistant, y compris celui d'une autre personne, d'un autre lieu ou d'un banc d'essai. La carte ne montre donc
 * que :
 * - le lecteur de CET appareil (« <nom de l'appareil> · Vision ») ;
 * - la Party lancée sur cet écran ;
 * - les lecteurs que l'utilisateur a choisis ou pilotés depuis cette appli (Party, choix de la musique), retenus ici.
 * Aucune lecture permise : pas de carte. Par défaut (rien de piloté), c'est « cet appareil uniquement ».
 */
object LecteursSuivis {
    private const val PREFS = "lecteurs_suivis"
    private const val CLE = "ids"
    private const val MAX = 12

    /** La règle, sans Android : vrai si la carte peut montrer la lecture de [lecteur]. */
    fun permis(lecteur: String, lecteurNom: String, actif: Boolean, ici: Boolean, nomPropre: String, suivis: Set<String>): Boolean {
        if (lecteur.isEmpty()) return false
        if (nomPropre.isNotEmpty() && lecteurNom.trim().equals(nomPropre.trim(), ignoreCase = true)) return true
        if (actif && ici) return true
        return lecteur in suivis
    }

    fun suivis(ctx: Context): Set<String> =
        ctx.applicationContext.getSharedPreferences(PREFS, 0).getStringSet(CLE, emptySet())?.toSet() ?: emptySet()

    /** Un lecteur choisi ou piloté depuis l'appli : la carte de l'accueil pourra le montrer. */
    fun noter(ctx: Context, lecteur: String?) {
        val id = lecteur?.trim().orEmpty()
        if (id.isEmpty()) return
        val p = ctx.applicationContext.getSharedPreferences(PREFS, 0)
        val l = (p.getStringSet(CLE, emptySet()) ?: emptySet()).toMutableSet()
        if (id in l) return
        l.add(id)
        p.edit().putStringSet(CLE, if (l.size > MAX) l.toList().takeLast(MAX).toSet() else l).apply()
    }

    fun permis(ctx: Context, e: Party.Etat): Boolean =
        permis(e.lecteur, e.lecteurNom, e.actif, e.ici, LecteurSlim.nom(ctx), suivis(ctx))
}
