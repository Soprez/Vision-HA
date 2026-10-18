// SPDX-License-Identifier: MIT
package org.soprez.vision

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Veille-lot : les règles de mise en page et de cadence de la veille dessinée par l'appli, sans Android (testées).
 *
 * - **Grille au pixel** : colonnes et cartes tombent sur des pixels entiers de l'écran (1080p comme 4K), avec des
 *   espacements tous égaux ; le pixel de reste va aux plus grosses parts, jamais à un espacement.
 * - **Dérive anti-marquage** : tout le contenu se déplace de quelques pixels, très lentement (moins d'un pixel toutes
 *   les vingt secondes), par pas entiers : imperceptible, et le texte reste net.
 * - **Cadence** : 60 images/s pendant les transitions et les entrées en scène, 30 au repos ; 30 toujours sur un
 *   appareil qui peine.
 * - **Direct des caméras** : une seule caméra en direct à la fois (le flux léger « _sub ») ; en mode Auto, retour aux
 *   images dès que l'appareil peine ou que le flux échoue.
 */
object GrilleVeille {
    /**
     * Répartit `longueur` pixels (à partir de `debut`) entre des parts de poids donnés, séparées par `ecart` pixels.
     * Rend [x0, l0, x1, l1, …] en pixels entiers : la somme des parts et des écarts vaut exactement `longueur`.
     */
    fun repartir(debut: Int, longueur: Int, poids: IntArray, ecart: Int): IntArray {
        val n = poids.size
        val sortie = IntArray(n * 2)
        if (n == 0) return sortie
        val dispo = (longueur - ecart * (n - 1)).coerceAtLeast(0)
        val somme = poids.sumOf { it.coerceAtLeast(1).toLong() }.coerceAtLeast(1)
        val largeurs = IntArray(n)
        val restes = DoubleArray(n)
        var pris = 0
        for (i in 0 until n) {
            val exact = dispo.toDouble() * poids[i].coerceAtLeast(1) / somme
            largeurs[i] = exact.toInt(); restes[i] = exact - largeurs[i]; pris += largeurs[i]
        }
        // Les pixels qui restent vont aux plus grands restes (à égalité, aux premières parts) : l'écart ne bouge jamais.
        var reste = dispo - pris
        while (reste > 0) {
            var k = 0
            for (i in 1 until n) if (restes[i] > restes[k] + 1e-9) k = i
            largeurs[k]++; restes[k] = -1.0; reste--
        }
        var x = debut
        for (i in 0 until n) { sortie[2 * i] = x; sortie[2 * i + 1] = largeurs[i]; x += largeurs[i] + ecart }
        return sortie
    }

    /** Arrondi au pixel physique (les coordonnées de dessin sont déjà en pixels). */
    fun px(v: Float): Int = v.roundToInt()
}

object DeriveVeille {
    /** Périodes (secondes) des deux mouvements, premières entre elles : le trajet ne repasse jamais au même rythme. */
    private const val PERIODE_X = 1427.0
    private const val PERIODE_Y = 1913.0

    /** L'amplitude en pixels selon la densité de l'écran : 2 à 8 px (« quelques pixels », 1080p comme 4K). */
    fun amplitude(densite: Float): Int = (3f * densite).roundToInt().coerceIn(2, 8)

    /** Le décalage (pixels entiers) à l'instant `tMs` ; écrit dans `sortie` (aucune allocation pendant le dessin). */
    fun decalage(tMs: Long, amplitude: Int, sortie: IntArray) {
        val t = tMs / 1000.0
        sortie[0] = (amplitude * sin(2 * PI * t / PERIODE_X)).roundToInt()
        sortie[1] = (amplitude * cos(2 * PI * t / PERIODE_Y)).roundToInt()
    }
}

object CadenceVeille {
    const val FLUIDE_MS = 16L
    const val REPOS_MS = 33L

    /**
     * L'intervalle entre deux images : fluide pendant le fondu d'entrée et de sortie d'un tableau et pendant
     * l'entrée en scène de ses cartes (`animMs`), repos ailleurs. Un appareil qui peine reste au repos.
     */
    fun intervalle(ecoule: Long, reste: Long, fonduMs: Long, animMs: Long, peine: Boolean): Long = when {
        peine -> REPOS_MS
        ecoule < fonduMs + animMs -> FLUIDE_MS
        reste in 0 until fonduMs -> FLUIDE_MS
        else -> REPOS_MS
    }
}

/**
 * L'appareil peine-t-il ? On suit les intervalles réels entre deux images : si plus d'un tiers dépassent nettement la
 * cadence voulue pendant quelques secondes, il peine — et le reste un moment (pas de va-et-vient).
 */
class SuiviPeine(private val fenetre: Int = 90, private val tenueMs: Long = 10 * 60_000L) {
    private val lents = BooleanArray(fenetre)
    private var n = 0
    private var i = 0
    private var nbLents = 0
    private var peineDepuis = -1L

    /** Une image vient d'être dessinée, `intervalleMs` après la précédente, pour une cadence voulue `vouluMs`. */
    fun mesurer(intervalleMs: Long, vouluMs: Long, maintenant: Long) {
        if (intervalleMs <= 0 || intervalleMs > 1000) return   // la vue était cachée : ce n'est pas une mesure
        val lent = intervalleMs > vouluMs * 2 + 8
        if (n == fenetre) { if (lents[i]) nbLents-- } else n++
        lents[i] = lent; if (lent) nbLents++
        i = (i + 1) % fenetre
        if (n >= fenetre / 2 && nbLents * 3 > n) peineDepuis = maintenant
    }

    fun peine(maintenant: Long): Boolean = peineDepuis >= 0 && maintenant - peineDepuis < tenueMs

    fun oublier() { n = 0; i = 0; nbLents = 0; peineDepuis = -1L; lents.fill(false) }
}

object DirectVeille {
    const val AUTO = "auto"
    const val IMAGES = "images"
    val MODES = listOf(AUTO, IMAGES)

    /**
     * La caméra à montrer en direct parmi celles d'une page (entité « direct » du flux léger, vide sans « _sub ») :
     * une seule, la première qui en a un ; aucune en mode Images, quand l'appareil peine, ou après deux échecs.
     */
    fun choisir(mode: String, directs: List<String>, peine: Boolean, echecs: Int): String? {
        if (mode != AUTO || peine || echecs >= 2) return null
        return directs.firstOrNull { it.isNotEmpty() }
    }
}
