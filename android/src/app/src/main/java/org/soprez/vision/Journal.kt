// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.util.Log

/**
 * Le journal de l'appli : chaque ligne va dans logcat ET dans un tampon circulaire en mémoire (2 000 lignes, 3 Mo),
 * lu par « Signaler un problème ». Rien n'est écrit sur disque ; rien ne sort sans l'envoi explicite de l'utilisateur.
 *
 * Les codes (code responsable, code de déverrouillage) ne sont jamais journalisés : la règle est dans l'essai
 * `JournalTest`, et `Anonymisation` remplace toute valeur qui suit « code= », « secret », « jeton »… en dernier recours.
 */
object Journal {
    const val MAX_LIGNES = 2000
    const val MAX_OCTETS = 3 * 1024 * 1024

    class Ligne(val ts: Long, val niveau: Char, val tag: String, val texte: String) {
        val octets: Int get() = texte.length + tag.length + 40
    }

    private val tampon = ArrayDeque<Ligne>()
    private var octets = 0

    /** Où part chaque ligne en plus du tampon (logcat) ; remplaçable pour les essais JVM. */
    @Volatile internal var sortie: (Char, String, String) -> Unit = { niveau, tag, texte ->
        when (niveau) {
            'W' -> Log.w(tag, texte)
            'E' -> Log.e(tag, texte)
            'D' -> Log.d(tag, texte)
            else -> Log.i(tag, texte)
        }
    }
    @Volatile internal var horloge: () -> Long = { System.currentTimeMillis() }

    fun d(tag: String, texte: String, erreur: Throwable? = null) = noter('D', tag, avec(texte, erreur))
    fun i(tag: String, texte: String, erreur: Throwable? = null) = noter('I', tag, avec(texte, erreur))
    fun w(tag: String, texte: String, erreur: Throwable? = null) = noter('W', tag, avec(texte, erreur))
    fun e(tag: String, texte: String, erreur: Throwable? = null) = noter('E', tag, avec(texte, erreur))

    private fun avec(texte: String, erreur: Throwable?) = if (erreur == null) texte else texte + " : " + pile(erreur)

    private fun noter(niveau: Char, tag: String, texte: String) {
        sortie(niveau, tag, texte)
        val l = Ligne(horloge(), niveau, tag, texte.take(2000))
        synchronized(tampon) {
            tampon.addLast(l)
            octets += l.octets
            while (tampon.size > MAX_LIGNES || octets > MAX_OCTETS) octets -= tampon.removeFirst().octets
        }
    }

    /** Les lignes de [depuis, jusqua] (millisecondes), de la plus ancienne à la plus récente. */
    fun periode(depuis: Long, jusqua: Long = Long.MAX_VALUE): List<Ligne> =
        synchronized(tampon) { tampon.filter { it.ts in depuis..jusqua } }

    fun vider() = synchronized(tampon) { tampon.clear(); octets = 0 }

    fun pile(erreur: Throwable): String =
        erreur.javaClass.name + ": " + (erreur.message ?: "") + "\n" +
            erreur.stackTrace.take(25).joinToString("\n") { "    at $it" }

    /** Le plantage d'un fil laisse sa pile dans le tampon (puis passe la main au gestionnaire précédent). */
    fun surveillerPlantages() {
        val avant = Thread.getDefaultUncaughtExceptionHandler()
        if (avant is Surveillant) return
        Thread.setDefaultUncaughtExceptionHandler(Surveillant(avant))
    }

    private class Surveillant(private val suivant: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            try { Journal.e("Plantage", "fil ${t.name}", e) } catch (_: Throwable) {}
            suivant?.uncaughtException(t, e)
        }
    }
}
