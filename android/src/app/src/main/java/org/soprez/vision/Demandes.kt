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

    /** 15-10 : ce qui se décide par lots (« a_decider_lots » de /veille, Décideur seulement) : (libellé, nombre). */
    fun lots(d: JSONObject?): List<Pair<String, Int>> {
        val a = d?.optJSONArray("a_decider_lots") ?: return emptyList()
        return lotsValides((0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { it.optString("libelle") to it.optInt("nombre", 0) })
    }

    /** Les lots qui comptent : un libellé et au moins un élément. Pur. */
    fun lotsValides(l: List<Pair<String, Int>>): List<Pair<String, Int>> = l.filter { it.second > 0 && it.first.isNotBlank() }

    /** 16-10 : les appareils à valider (lot « appairage ») : une demande de liaison, la seule qui compte comme demande. */
    fun appareilsAValider(d: JSONObject?): Int {
        val a = d?.optJSONArray("a_decider_lots") ?: return 0
        return appareilsAValider((0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { it.optString("genre") to it.optInt("nombre", 0) })
    }

    /** Le même, pur : (genre, nombre) de chaque lot. */
    fun appareilsAValider(lots: List<Pair<String, Int>>): Int = lots.filter { it.first == "appairage" }.sumOf { it.second.coerceAtLeast(0) }

    /** 15-10 : combien de DEMANDES attendent un Décideur — le total annoncé (la liste est coupée à quatre).
     *  16-10 (l'auteur : « 1 demande en attente », et aucune demande à l'ouverture) : les lots ne sont pas des demandes.
     *  « 229 éléments à classer » ou « 3 logiciels à valider » gardent leur propre ligne, comme dans le tableau de
     *  Home Assistant ; seuls les appareils à valider s'ajoutent (ce sont des demandes de liaison). */
    fun total(d: JSONObject?): Int {
        if (d == null) return 0
        val liste = d.optJSONArray("demandes")?.length() ?: 0
        return total(liste, if (d.has("demandes_total")) d.optInt("demandes_total", liste) else null, appareilsAValider(d))
    }

    /** Le même compte, pur : [liste] reçues, [totalHa] annoncé (null : ancien Home Assistant), [appareils] à valider. */
    fun total(liste: Int, totalHa: Int?, appareils: Int): Int = maxOf(totalHa ?: liste, liste) + appareils.coerceAtLeast(0)

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
