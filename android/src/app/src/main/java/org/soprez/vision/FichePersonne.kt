// SPDX-License-Identifier: MIT
package org.soprez.vision

import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Refonte lot 3 : la fiche allégée d'une personne, vue par un Décideur (FichePersonneActivity). Sans Android :
 * l'analyse passe par [Json] (org.json n'existe pas dans les essais JUnit, FichePersonneTest).
 *
 * Contrat (réponse de /api/pc_parental/parent, chaque élément de « personnes ») : tout est facultatif ; un champ
 * absent ou d'un autre type donne la valeur vide, jamais une erreur.
 *   "fiche": {"modules": {"plages":…, "reveil":…, "temps_gagne":…, "regles_lieu":…, "entites":…}, "role_nom": "…",
 *             "reveils": [{"nom","actif","prochain"}], "recompenses": {"liste","minutes","max","accorde"} | null,
 *             "regles_lieu": [{"zone","categories":[…]}], "widgets": 3}
 */
object FichePersonne {
    const val M_PLAGES = "plages"
    const val M_REVEIL = "reveil"
    const val M_TEMPS_GAGNE = "temps_gagne"
    const val M_REGLES_LIEU = "regles_lieu"
    const val M_ENTITES = "entites"

    data class Reveil(val nom: String, val actif: Boolean, val prochain: Long)
    data class Recompense(val liste: String, val minutes: Int, val max: Int, val accorde: Int)
    data class RegleLieu(val zone: String, val categories: List<String>)

    data class Fiche(
        val modules: Map<String, Boolean> = emptyMap(),
        val roleNom: String = "",
        val reveils: List<Reveil> = emptyList(),
        val recompenses: Recompense? = null,
        val reglesLieu: List<RegleLieu> = emptyList(),
        val widgets: Int = 0,
    ) {
        /** Un module absent (ou faux) masque sa section. */
        fun module(cle: String): Boolean = modules[cle] == true
    }

    data class Personne(
        val entite: String,
        val prenom: String,
        val role: String,
        val appareils: List<String>,
        /** Le temps d'écran du jour (minutes), null si Home Assistant ne le donne pas. */
        val tempsActif: Int?,
        val decideur: Boolean,
        val fiche: Fiche?,
    )

    // ------------------------------------------------------------------ analyse

    private fun texte(v: Any?): String = (v as? String)?.trim().orEmpty()
    private fun entier(v: Any?): Int? = (v as? Number)?.toInt()
    @Suppress("UNCHECKED_CAST") private fun objet(v: Any?): Map<String, Any?>? = v as? Map<String, Any?>
    private fun liste(v: Any?): List<Any?> = v as? List<Any?> ?: emptyList()

    /** La fiche d'après son texte JSON ; null si illisible. */
    fun analyser(json: String?): Fiche? = Json.objet(json)?.let { analyser(it) }

    fun analyser(m: Map<String, Any?>): Fiche {
        val modules = objet(m["modules"])?.mapNotNull { (k, v) -> (v as? Boolean)?.let { k to it } }?.toMap().orEmpty()
        val reveils = liste(m["reveils"]).mapNotNull { objet(it) }.map {
            Reveil(texte(it["nom"]), it["actif"] == true, dateIso(texte(it["prochain"])))
        }.filter { it.nom.isNotEmpty() }
        val recompenses = objet(m["recompenses"])?.let {
            Recompense(texte(it["liste"]), (entier(it["minutes"]) ?: 0).coerceAtLeast(0), (entier(it["max"]) ?: 0).coerceAtLeast(0),
                (entier(it["accorde"]) ?: 0).coerceAtLeast(0))
        }
        val regles = liste(m["regles_lieu"]).mapNotNull { objet(it) }.map { r ->
            RegleLieu(texte(r["zone"]), liste(r["categories"]).map { texte(it) }.filter { it.isNotEmpty() })
        }.filter { it.zone.isNotEmpty() }
        return Fiche(modules, texte(m["role_nom"]), reveils, recompenses, regles, (entier(m["widgets"]) ?: 0).coerceAtLeast(0))
    }

    /** Une personne de la réponse (sa fiche si Home Assistant l'envoie). */
    fun personne(m: Map<String, Any?>): Personne = Personne(
        entite = texte(m["entite"]),
        prenom = texte(m["prenom"]),
        role = texte(m["role"]),
        appareils = liste(m["appareils"]).map { texte(it) }.filter { it.isNotEmpty() },
        tempsActif = entier(objet(m["temps"])?.get("actif")),
        decideur = m["parent"] == true,
        fiche = objet(m["fiche"])?.let { analyser(it) },
    )

    /** Les personnes d'une réponse /parent entière (texte JSON) ; vide si illisible. */
    fun personnes(reponse: String?): List<Personne> = personnes(Json.objet(reponse))

    fun personnes(reponse: Map<String, Any?>?): List<Personne> =
        liste(reponse?.get("personnes")).mapNotNull { objet(it) }.map { personne(it) }.filter { it.entite.isNotEmpty() }

    /** « 2026-10-13T07:00:00+02:00 » (avec ou sans fractions de seconde) → ms ; 0 si vide ou illisible. */
    fun dateIso(iso: String): Long {
        if (iso.isEmpty()) return 0
        val propre = iso.replace(Regex("\\.\\d+"), "").replace(Regex("Z$"), "+0000").replace(Regex("([+-]\\d{2}):(\\d{2})$"), "$1$2")
        return try { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ROOT).parse(propre)?.time ?: 0 } catch (_: Exception) { 0 }
    }

    // ------------------------------------------------------------------ navigation

    /** La personne d'avant (pas = -1) ou d'après (pas = 1), en boucle ; null si la liste est vide. */
    fun voisine(entites: List<String>, actuelle: String, pas: Int): String? {
        if (entites.isEmpty()) return null
        val i = entites.indexOf(actuelle)
        if (i < 0) return entites[if (pas < 0) entites.size - 1 else 0]
        return entites[Math.floorMod(i + pas, entites.size)]
    }

    /** L'initiale de l'avatar (« L » pour « lou »), « ? » sans prénom. */
    fun initiale(prenom: String): String =
        prenom.trim().firstOrNull { it.isLetterOrDigit() }?.toString()?.uppercase(Tr.locale()) ?: "?"

    // ------------------------------------------------------------------ textes

    fun texteRecompense(r: Recompense?): String =
        if (r == null) Tr.s(R.string.fiche_pas_recompense) else Tr.s(R.string.fiche_recompense, r.minutes, r.liste, r.max, r.accorde)

    fun texteWidgets(n: Int): String = Tr.p(R.plurals.fiche_n_widgets, n, n)

    /** « Collège : Jeux, Réseaux sociaux » ; [libelle] traduit chaque catégorie (Categories.libelle à l'écran). */
    fun texteRegle(r: RegleLieu, libelle: (String) -> String = { it }): String =
        Tr.s(R.string.fiche_regle_lieu, r.zone, r.categories.joinToString(", ") { libelle(it) })

    fun etatReveil(r: Reveil): String = Tr.s(if (r.actif) R.string.fiche_reveil_active else R.string.fiche_reveil_desactive)
}
