// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.json.JSONObject

/**
 * 1.77.0 : comment une demande se présente, le même texte partout (gestion, message, accueil). Un site montre
 * toujours son domaine : le nom de la demande, sinon sa clé, sinon l'hôte de son lien — jamais « Site : » vide.
 */
object Demandes {
    /** Le domaine (ou le nom) d'une demande de site, tiré de ce qui est là. */
    fun domaine(d: JSONObject): String {
        val brut = listOf("cle", "nom", "libelle").map { d.optString(it).trim() }.firstOrNull { it.isNotEmpty() }
            ?: Acces.hoteDe(d.optString("lien"))
        return brut.removePrefix("https://").removePrefix("http://").removePrefix("www.").substringBefore('/').trim()
    }

    /** Ce qui est demandé, pour le titre : « Roblox », « exemple.fr », « 15 min de plus ». */
    fun quoi(d: JSONObject): String = when (d.optString("genre")) {
        "temps" -> Tr.s(R.string.demandes_temps_de_plus, Temps.duree(d.optInt("minutes", 15).coerceAtLeast(1)))
        "deverrouillage" -> d.optString("libelle").ifEmpty { Tr.s(R.string.demandes_un_deverrouillage) }
        "sites" -> d.optString("libelle").trim().ifEmpty { domaine(d) }
        else -> d.optString("libelle").trim().ifEmpty { d.optString("nom").ifEmpty { d.optString("cle") } }
    }

    /** Un lot de messages reçus contient-il une demande à régler (accès, temps, déverrouillage) ? */
    fun contientDemande(messages: org.json.JSONArray?): Boolean {
        if (messages == null) return false
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            if (m.has("acces") || m.has("demande") || m.has("deverrouillage")) return true
        }
        return false
    }

    /** La ligne de détail : le type, avec le domaine pour un site. */
    fun description(d: JSONObject): String = when (d.optString("genre")) {
        "temps" -> Tr.s(R.string.demandes_desc_temps)
        "deverrouillage" -> d.optString("motif").takeIf { it.isNotEmpty() }?.let { Tr.s(R.string.demandes_desc_deverrouillage_motif, it) }
            ?: Tr.s(R.string.demandes_desc_deverrouillage)
        "apps" -> Tr.s(R.string.demandes_desc_appli)
        else -> domaine(d).let { if (it.isEmpty()) Tr.s(R.string.demandes_desc_site_inconnu) else Tr.s(R.string.demandes_desc_site, it) }
    }
}
