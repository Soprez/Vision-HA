package org.soprez.vision

import org.junit.Assert.assertEquals
import org.junit.Test
import org.soprez.vision.DispositionDash.Bloc
import org.soprez.vision.DispositionDash.Carte

/**
 * 1.90.x (l'auteur, TV TCL) : les tableaux de bord Home Assistant redessinés par la veille, en 1280×720 et 1920×1080.
 * 1. « Cuve & livraisons » : à gauche « CUVE FIOUL » puis Niveau estimé (jauge) et Autonomie, puis Conso moyenne ;
 *    à droite le graphique « Niveau 567 L » sans titre. Le haut des cartes s'aligne entre les colonnes ; les deux
 *    cartes d'une rangée ont la même hauteur ; la carte de la rangée suivante a leur largeur totale.
 * 2. Réseau (box) : graphiques hauts à gauche, tuiles valeur à droite : le même bord partout.
 */
class DispositionDashTest {
    private val ecrans = listOf(1280f to 720f, 1920f to 1080f)

    private fun cuve() = listOf(
        listOf(Bloc("Cuve fioul", listOf(listOf(Carte(6, 2), Carte(6, 2)), listOf(Carte(12, 1))))),
        listOf(Bloc("", listOf(listOf(Carte(12, 2))))),
    )

    @Test fun hautDesCartesAligneSansTitre() {
        for ((w, h) in ecrans) {
            val plan = DispositionDash.placer(cuve(), 5, w, h, minOf(w, h) * 0.03f)
            val gauche = plan.cartes.first { it.colonne == 0 }.boite.top
            val droite = plan.cartes.first { it.colonne == 1 }.boite.top
            assertEquals("${w.toInt()}×${h.toInt()} : haut de la carte sans titre", gauche, droite, 0.5f)
        }
    }

    @Test fun rangeeMemeHauteurEtLargeurTotale() {
        for ((w, h) in ecrans) {
            val plan = DispositionDash.placer(cuve(), 5, w, h, minOf(w, h) * 0.03f)
            val haut = plan.cartes.filter { it.colonne == 0 && it.rangee == 0 }
            assertEquals(2, haut.size)
            assertEquals("jauge et Autonomie : même hauteur", haut[0].boite.hauteur, haut[1].boite.hauteur, 0.5f)
            assertEquals("même haut", haut[0].boite.top, haut[1].boite.top, 0.5f)
            val conso = plan.cartes.first { it.colonne == 0 && it.rangee == 1 }.boite
            assertEquals("Conso moyenne : bord gauche", haut[0].boite.left, conso.left, 0.5f)
            assertEquals("Conso moyenne : bord droit", haut[1].boite.right, conso.right, 0.5f)
        }
    }

    @Test fun memeBordPourGraphiquesEtTuiles() {
        for ((w, h) in ecrans) {
            val colonnes = listOf(
                listOf(Bloc("", listOf(listOf(Carte(12, 2)), listOf(Carte(12, 2))))),        // graphiques (débit)
                listOf(Bloc("", listOf(listOf(Carte(12, 1)), listOf(Carte(12, 1)), listOf(Carte(12, 1))))),  // tuiles valeur
            )
            val plan = DispositionDash.placer(colonnes, 5, w, h, minOf(w, h) * 0.03f)
            val bords = plan.cartes.map { DispositionDash.bordPourCarte(h, it.boite.hauteur, plan.hu) }.distinct()
            assertEquals("${w.toInt()}×${h.toInt()} : une seule épaisseur de bord, trouvé $bords", 1, bords.size)
            assertEquals("même arrondi pour toutes les cartes", 1, plan.cartes.map { DispositionDash.rayon(h, plan.hu) }.distinct().size)
        }
    }

    /** Veille-lot (grille au pixel) : en 720p comme en 1080p, bords entiers et écarts tous égaux, entre cartes d'une rangée et entre rangées. */
    @Test fun grilleAuPixel() {
        val cols = listOf(
            listOf(Bloc("CUVE", listOf(listOf(Carte(6, 2), Carte(6, 2)), listOf(Carte(12, 1)), listOf(Carte(4, 1), Carte(4, 1), Carte(4, 1))))),
            listOf(Bloc("", listOf(listOf(Carte(12, 3)), listOf(Carte(5, 1), Carte(7, 1))))))
        for ((w, h) in listOf(1280f to 720f, 1920f to 1080f, 3840f to 2160f)) {
            val plan = DispositionDash.placer(cols, 6, w, h, minOf(w, h) * 0.03f)
            val e = Math.round(plan.hu * 0.1f)
            val boites = plan.cartes.map { it to DispositionDash.auPixel(it.boite, plan.hu * 0.1f) }
            val ecartsH = HashSet<Int>(); val ecartsV = HashSet<Int>()
            for (c in 0..1) {
                val col = boites.filter { it.first.colonne == c }
                for ((r, rangee) in col.groupBy { it.first.bloc to it.first.rangee }.values.withIndex()) {
                    val tri = rangee.sortedBy { it.second[0] }
                    for (k in 1 until tri.size) ecartsH += tri[k].second[0] - tri[k - 1].second[2]
                    assertEquals("une rangée a une seule hauteur", 1, tri.map { it.second[3] - it.second[1] }.distinct().size)
                }
                val rangees = col.groupBy { it.first.bloc to it.first.rangee }.values.map { it.first().second }.sortedBy { it[1] }
                for (k in 1 until rangees.size) ecartsV += rangees[k][1] - rangees[k - 1][3]
            }
            assertEquals("${w.toInt()} : écarts horizontaux égaux", setOf(e), ecartsH)
            assertEquals("${w.toInt()} : écarts verticaux égaux", setOf(e), ecartsV)
        }
    }
}
