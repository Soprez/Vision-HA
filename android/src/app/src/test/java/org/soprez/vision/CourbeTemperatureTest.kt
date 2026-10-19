// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.93.06 (l'auteur, 19/10 à 07:45, capture du téléphone) : le widget « T. Ambiante » (courbe 24 h) montrait une bosse dans
 * le premier tiers, puis une ligne plate jusqu'à maintenant. L'historique de Home Assistant était juste : 105 états,
 * dont 5 couples « unavailable » / « unknown ». La vue n'avait chargé l'historique qu'une fois ; la fin de la fenêtre
 * avançait et la dernière valeur connue était prolongée jusqu'à maintenant.
 *
 * Les 105 états réels de la chaudière, horodatages anonymisés : secondes depuis le début de la fenêtre de 24 h.
 */
class CourbeTemperatureTest {
    private val etats = listOf(
        0 to "19.6",
        67 to "19.5",
        217 to "19.6",
        368 to "19.5",
        1351 to "unavailable",
        1352 to "unknown",
        1352 to "19.5",
        2316 to "19.4",
        8467 to "19.5",
        8916 to "19.6",
        9066 to "19.7",
        9366 to "19.8",
        9516 to "19.9",
        9818 to "20.0",
        10867 to "20.1",
        11916 to "20.2",
        12218 to "20.3",
        12366 to "20.4",
        12816 to "20.5",
        13266 to "20.6",
        14616 to "20.7",
        14766 to "20.6",
        14813 to "unavailable",
        14813 to "unknown",
        14813 to "20.6",
        15216 to "20.7",
        15517 to "20.6",
        16076 to "unavailable",
        16076 to "unknown",
        16076 to "20.6",
        16416 to "20.5",
        16896 to "unavailable",
        16897 to "unknown",
        16897 to "20.5",
        18218 to "20.4",
        18516 to "20.5",
        19718 to "20.6",
        20167 to "20.7",
        20767 to "20.8",
        21367 to "20.9",
        21518 to "20.8",
        21668 to "20.9",
        23617 to "20.8",
        26469 to "20.7",
        27967 to "20.6",
        28117 to "20.7",
        28267 to "20.6",
        29167 to "20.5",
        30367 to "20.4",
        31568 to "20.3",
        31717 to "20.4",
        31869 to "20.3",
        32768 to "20.2",
        34118 to "20.1",
        34267 to "20.2",
        34867 to "20.1",
        35317 to "20.2",
        35467 to "20.1",
        48067 to "20.2",
        48367 to "20.3",
        48517 to "20.4",
        48967 to "20.5",
        49267 to "20.6",
        49719 to "20.7",
        50768 to "20.8",
        51968 to "20.7",
        52867 to "20.6",
        54368 to "20.5",
        55270 to "20.4",
        56115 to "unavailable",
        56116 to "unknown",
        56117 to "20.4",
        56768 to "20.3",
        57818 to "20.2",
        57968 to "20.3",
        58118 to "20.2",
        59318 to "20.1",
        62918 to "20.0",
        64418 to "19.9",
        64718 to "20.0",
        64868 to "19.9",
        66068 to "19.8",
        66218 to "19.9",
        66518 to "19.8",
        69968 to "19.7",
        70118 to "19.8",
        70418 to "19.7",
        72818 to "19.6",
        72968 to "19.7",
        73118 to "19.6",
        75068 to "19.5",
        76118 to "19.6",
        76568 to "19.7",
        76868 to "19.8",
        77018 to "19.9",
        77318 to "20.0",
        77768 to "20.1",
        78368 to "20.2",
        78968 to "20.3",
        79568 to "20.4",
        80318 to "20.5",
        81068 to "20.6",
        82118 to "20.5",
        82268 to "20.6",
        83618 to "20.7",
    )
    private val t0 = 1_000_000_000_000L
    private val jour = 24 * 3_600_000L

    /** Ce que Home Assistant envoie (widgets.historique) : les nombres seulement, « unavailable » et « unknown » sautés. */
    private fun points(jusqua: Long = Long.MAX_VALUE) = etats.mapNotNull { (s, v) -> v.toDoubleOrNull()?.let { WidgetsHa.Point(t0 + s * 1000L, it) } }
        .filter { it.ts <= jusqua }

    @Test fun les_105_etats_reels() {
        assertEquals(105, etats.size)
        assertEquals(5, etats.count { it.second == "unavailable" }); assertEquals(5, etats.count { it.second == "unknown" })
        assertEquals(95, points().size)
    }

    @Test fun la_ligne_plate_de_ja() {
        // Historique chargé au premier tiers (8 h après le début), écran regardé 16 h plus tard : la courbe d'avant.
        val charge = t0 + 8 * 3_600_000L
        val fin = t0 + jour
        val vieux = WidgetsHa.courbe(points(jusqua = charge), t0, fin).second
        val apres = vieux.filter { it.ts > charge }
        assertTrue("le défaut : plat sur les 16 dernières heures", apres.map { it.v }.distinct().size == 1)
        // Désormais : un historique de 16 h est périmé, la vue le recharge.
        assertTrue(WidgetsHa.historiquePerime(charge, fin, "24h"))
        assertFalse(WidgetsHa.historiquePerime(fin - 60_000L, fin, "24h"))
        assertFalse(WidgetsHa.historiquePerime(fin - 10 * 60_000L, fin, "7j"))
    }

    @Test fun apres_rechargement_toute_la_journee_est_tracee() {
        val fin = t0 + jour
        val (fenetre, traces) = WidgetsHa.courbe(points(), t0, fin)
        // Toute la largeur, du début de la fenêtre à maintenant.
        assertEquals(t0, fenetre.first); assertEquals(fin, fenetre.second)
        assertEquals(t0, traces.first().ts); assertEquals(fin, traces.last().ts)
        // La journée réelle : montée l'après-midi, baisse la nuit, remontée le matin, plus de plat de 16 h.
        val secondeMoitie = traces.filter { it.ts > t0 + 12 * 3_600_000L }
        assertTrue(secondeMoitie.map { it.v }.distinct().size >= 10)
        for (i in 1 until traces.size) assertTrue("points triés", traces[i].ts >= traces[i - 1].ts)
        // Repères min et max, échelle automatique avec une petite marge (jamais depuis 0).
        assertEquals(19.4 to 20.9, WidgetsHa.minMaxFenetre(points(), t0, fin))
        val (mi, ma) = WidgetsHa.echelle(traces.map { it.v })
        assertTrue("échelle : $mi .. $ma", mi in 19.0..19.4 && ma in 20.9..21.3)
    }
}
