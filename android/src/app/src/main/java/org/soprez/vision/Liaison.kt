// SPDX-License-Identifier: MIT
package org.soprez.vision

/**
 * Refonte UX, lot 1 (APP-03) : les règles de la liaison à un serveur, sans Android (essais JVM).
 *
 * Règle : **jamais d'inscription sans confirmation explicite**. Une requête d'inscription ne part que si
 *  - une clé d'inscription a été saisie ou collée par la personne (bloc, adresse + clé, `--es bloc` d'adb), ou
 *  - la personne a touché « Se relier » sur le serveur trouvé ([Decouverte.relier] pose alors `sansCle`).
 * Trouver un serveur ne suffit jamais.
 */
object Liaison {
    /** Un Home Assistant trouvé sur le réseau : son nom annoncé (peut être vide) et ses adresses. */
    data class Serveur(val nom: String, val externe: String, val interne: String) {
        /** L'adresse par laquelle on le joint depuis ici. */
        val adresse: String get() = interne.ifEmpty { externe }
        /** Ce que l'écran montre : le nom annoncé, sinon l'hôte et le port. */
        fun affiche(): String = nom.trim().ifEmpty { adresse.substringAfter("://").substringBefore('/') }
    }

    /** Les adresses annoncées → le serveur (l'adresse publique d'abord, l'interne ensuite) ; rien trouvé : null. */
    fun serveur(nom: String, urls: Collection<String>): Serveur? {
        if (urls.isEmpty()) return null
        val externe = urls.firstOrNull { !Reseau.estPrivee(it) } ?: urls.first()
        val interne = urls.firstOrNull { Reseau.estPrivee(it) } ?: ""
        return Serveur(nom.trim(), externe, interne)
    }

    /** Peut-on envoyer une requête d'inscription ? Une clé donnée par la personne, ou « Se relier » touché. */
    fun peutInscrire(cle: String, relierConfirme: Boolean): Boolean = cle.isNotBlank() || relierConfirme

    private val ecartes = java.util.Collections.synchronizedSet(HashSet<String>())

    /** « Pas celui-ci » : le serveur n'est plus proposé (par ses adresses). */
    fun ecarter(s: Serveur) { listOf(s.externe, s.interne).filter { it.isNotEmpty() }.forEach { ecartes.add(it) } }
    fun estEcarte(s: Serveur): Boolean = listOf(s.externe, s.interne).any { it.isNotEmpty() && it in ecartes }
    fun oublierEcartes() = ecartes.clear()
}
