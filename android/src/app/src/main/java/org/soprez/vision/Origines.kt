// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.json.JSONObject

/**
 * 1.94 · Découverte appli → sites : ce que l'appareil remonte à Home Assistant, domaine par domaine : quelle appli l'a
 * demandé, combien de fois depuis le dernier relevé. Format (même que celui lu par `decouverte.py`) :
 * `{domaine: {paquet: {"n", "incertain", "premier", "dernier", "bloque"}}}`, 200 domaines au plus (les plus demandés).
 * Jamais d'URL ni de chemin : seulement des hôtes, des comptes et des dates. Les requêtes sans appli connue sont gardées
 * pour l'appli au premier plan, comptées à part (`incertain`) : elles ne nourrissent jamais un lien automatique.
 */
object Origines {
    const val MAX_RELEVE = 200
    private const val MAX_TABLE = 400
    private const val MAX_PAQUETS_PAR_DOMAINE = 12

    class Ligne(var n: Int = 0, var incertain: Int = 0, var premier: Long = 0, var dernier: Long = 0, var bloque: Int = 0)

    private val table = LinkedHashMap<String, LinkedHashMap<String, Ligne>>()
    private val appBloquante = HashMap<String, String>()

    /** Un hôte lisible : lettres, chiffres, points et tirets ; jamais d'adresse complète, de chemin ni de port. */
    fun hoteValide(h: String): Boolean = h.length in 3..253 && '.' in h && h.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }

    /**
     * Note une requête. [paquets] : les applis attribuées (vide : incertaine, comptée pour [premierPlan] s'il y en a un).
     * [bloque] : la requête a été refusée par Vision (elle compte aussi pour le domaine et retient l'appli).
     */
    @Synchronized fun noter(hote: String, paquets: List<String>, premierPlan: String?, bloque: Boolean, maintenant: Long = System.currentTimeMillis()) {
        val h = hote.trim().lowercase().trimEnd('.')
        if (!hoteValide(h)) return
        val certains = paquets.isNotEmpty()
        val cibles = if (certains) paquets else listOfNotNull(premierPlan?.takeIf { it.isNotBlank() })
        if (cibles.isEmpty()) return
        if (h !in table && table.size >= MAX_TABLE) return
        val parApp = table.getOrPut(h) { LinkedHashMap() }
        for (p in cibles) {
            if (p !in parApp && parApp.size >= MAX_PAQUETS_PAR_DOMAINE) continue
            val l = parApp.getOrPut(p) { Ligne(premier = maintenant) }
            if (certains) l.n++ else l.incertain++
            if (bloque) { l.bloque++; if (certains) appBloquante[h] = p }
            l.dernier = maintenant
        }
    }

    /** L'appli à qui attribuer une tentative bloquée (`alertes[].app`), si l'attribution était certaine. */
    @Synchronized fun appBloquante(hote: String): String? = appBloquante[hote.trim().lowercase().trimEnd('.')]

    /** Le relevé : les 200 domaines les plus demandés depuis le précédent ; la table repart de zéro. */
    @Synchronized fun releve(): JSONObject {
        val sortie = JSONObject()
        val poids = table.mapValues { (_, apps) -> apps.values.sumOf { it.n + it.incertain } }
        for (h in table.keys.sortedByDescending { poids[it] ?: 0 }.take(MAX_RELEVE)) {
            val d = JSONObject()
            for ((p, l) in table.getValue(h)) {
                d.put(p, JSONObject().put("n", l.n).put("incertain", l.incertain).put("premier", l.premier).put("dernier", l.dernier).put("bloque", l.bloque))
            }
            sortie.put(h, d)
        }
        table.clear()
        appBloquante.clear()
        return sortie
    }

    @Synchronized fun vider() { table.clear(); appBloquante.clear() }
}
