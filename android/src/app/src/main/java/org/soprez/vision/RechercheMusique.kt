// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import org.json.JSONArray

/**
 * Retours de l'auteur (15-10) : la recherche de musique se fait pendant qu'on tape, comme YouTube Music ou Deezer.
 *
 * - La requête part [ANTI_REBOND_MS] après la dernière frappe (Entrée : tout de suite), dès [MIN_LETTRES] lettres.
 * - Une requête devenue obsolète est coupée (Net.Annulation) et sa réponse jetée (on compare la clé).
 * - Les derniers textes cherchés gardent leurs résultats [Memoire.DUREE_MS] : effacer une lettre réaffiche aussitôt.
 * - Les recherches récentes restent sur l'appareil seulement (10, effaçables dans les réglages Musique).
 *
 * Tout ce qui se décide ici est pur (essais JVM : RechercheMusiqueTest) ; l'écran est ChoixMusiqueActivity.
 */
object RechercheMusique {
    const val ANTI_REBOND_MS = 300L
    const val MIN_LETTRES = 2
    const val RECENTS_MAX = 10
    const val SUGGESTIONS_MAX = 8
    /** Groupes de résultats, dans l'ordre de YouTube Music : titres, artistes, albums, playlists, radios. */
    val ORDRE = listOf("track", "artist", "album", "playlist", "radio")
    /** Avec « Tout », chaque groupe montre ses premiers résultats et « Tout voir ». */
    const val APERCU_PAR_GROUPE = 4

    /** Le texte tel qu'il part : espaces resserrés, sans espaces autour. */
    fun normaliser(t: String): String = t.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    fun assezLong(t: String): Boolean = normaliser(t).length >= MIN_LETTRES

    /** La clé d'une recherche : même texte (casse et espaces ignorés), mêmes filtres → mêmes résultats. */
    fun cle(texte: String, types: Set<String>, plateformes: Set<String>, bibliotheque: Boolean): String =
        normaliser(texte).lowercase() + "|" + types.sorted().joinToString(",") + "|" + plateformes.sorted().joinToString(",") + "|" + bibliotheque

    /** Les groupes à afficher, dans l'ordre [ORDRE] ; avec [apercu], chacun coupé à [APERCU_PAR_GROUPE]. */
    fun <T> grouper(l: List<T>, type: (T) -> String, apercu: Boolean): List<Triple<String, List<T>, Int>> =
        l.groupBy(type).toList().sortedBy { ORDRE.indexOf(it.first).let { i -> if (i < 0) 99 else i } }
            .map { (t, items) -> Triple(t, if (apercu) items.take(APERCU_PAR_GROUPE) else items, items.size) }

    /**
     * Les actions d'un résultat tiennent-elles sur la ligne du titre ? Il faut la pochette, les boutons et au moins
     * 30 % de la carte pour le titre ; sinon les actions passent sous le titre. (L'icône de source est sur la ligne du
     * sous-titre : elle ne prend rien à cette ligne.)
     */
    fun actionsSurLaLigne(largeurCarte: Int, largeursBoutons: List<Int>, pochette: Int): Boolean =
        largeurCarte > 0 && largeurCarte - pochette - largeursBoutons.sum() >= largeurCarte * 0.30f

    /** Un texte cherché passe en tête des récents (sans doublon, casse ignorée). */
    fun ajouterRecent(recents: List<String>, t: String, max: Int = RECENTS_MAX): List<String> {
        val n = normaliser(t)
        if (n.length < MIN_LETTRES) return recents
        return (listOf(n) + recents.filter { !it.equals(n, ignoreCase = true) }).take(max)
    }

    /**
     * Ce qu'on propose sous le champ : sans saisie, les récents puis ce que HA suggère (artistes et titres souvent
     * joués) ; avec une saisie, ceux qui la contiennent (début de mot d'abord). Jamais le texte déjà tapé lui-même.
     */
    fun suggestions(recents: List<String>, serveur: List<String>, saisie: String, max: Int = SUGGESTIONS_MAX): List<String> {
        val s = normaliser(saisie).lowercase()
        val tous = (recents + serveur).map { normaliser(it) }.filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }.filter { it.lowercase() != s }
        if (s.isEmpty()) return tous.take(max)
        val debut = tous.filter { x -> x.lowercase().split(" ").any { it.startsWith(s) } || x.lowercase().startsWith(s) }
        val dedans = tous.filter { it.lowercase().contains(s) && it !in debut }
        return (debut + dedans).take(max)
    }

    /** Les résultats récents en mémoire (le plus ancien sort le premier). */
    class Memoire<T>(private val max: Int = 20, private val dureeMs: Long = DUREE_MS) {
        private val l = LinkedHashMap<String, Pair<Long, T>>(16, 0.75f, true)
        @Synchronized fun lire(cle: String, maintenant: Long): T? {
            val x = l[cle] ?: return null
            if (maintenant - x.first > dureeMs) { l.remove(cle); return null }
            return x.second
        }
        @Synchronized fun garder(cle: String, v: T, maintenant: Long) {
            l[cle] = maintenant to v
            while (l.size > max) l.remove(l.keys.first())
        }
        companion object { const val DUREE_MS = 120_000L }
    }

    // ------------------------------------------------------------------ sur l'appareil

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("musique_recherche", Context.MODE_PRIVATE)

    fun recents(ctx: Context): List<String> = try {
        val a = JSONArray(prefs(ctx).getString("recents", "[]"))
        (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }
    } catch (_: Exception) { emptyList() }

    fun noterRecent(ctx: Context, t: String) {
        val l = ajouterRecent(recents(ctx), t)
        prefs(ctx).edit().putString("recents", JSONArray(l).toString()).apply()
    }

    fun effacerRecents(ctx: Context) = prefs(ctx).edit().remove("recents").apply()

    /** « Ma bibliothèque seulement » (filtre gardé d'une recherche à l'autre, comme le type et la source). */
    fun bibliothequeSeule(ctx: Context): Boolean = prefs(ctx).getBoolean("bibliotheque", false)
    fun bibliothequeSeule(ctx: Context, oui: Boolean) = prefs(ctx).edit().putBoolean("bibliotheque", oui).apply()
}
