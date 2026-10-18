// SPDX-License-Identifier: MIT
package org.soprez.vision

import kotlin.math.max

/**
 * La mise en place d'un écran de tableau de bord Home Assistant redessiné par la veille (VeilleView), sans Android :
 * colonnes de blocs (un titre de section facultatif, des rangées de cartes), et les rectangles des cartes.
 *
 * 1.90.x (l'auteur, TV TCL) :
 * - une colonne sans titre de section commençait plus haut que sa voisine titrée : le haut des cartes est
 *   maintenant aligné, la hauteur d'un titre absent est réservée en tête de colonne ([ALIGNER_TITRES]) ;
 * - l'épaisseur du bord d'une carte dépendait de sa taille (graphiques épais, tuiles fines) : elle ne dépend
 *   plus que de la hauteur de l'écran ([epaisseurBord]), la même pour toutes les cartes au repos.
 */
object DispositionDash {
    /** La hauteur d'un titre de section, en unités de rangée. */
    const val TITRE = 0.55f
    /** L'écart entre deux blocs d'une même colonne, en unités de rangée. */
    const val ECART_BLOCS = 0.25f
    const val ALIGNER_TITRES = true

    /** Une carte : sa largeur sur 12 et sa hauteur en rangées. */
    class Carte(val largeur: Int, val hauteur: Int)
    class Bloc(val titre: String, val rangees: List<List<Carte>>) {
        val unitesCartes: Float get() = rangees.sumOf { r -> r.maxOf { it.hauteur }.toDouble() }.toFloat()
    }
    class Boite(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val hauteur get() = bottom - top
        val largeur get() = right - left
    }
    /** Où poser un titre (sa ligne de base) et chaque carte, colonne par colonne, bloc par bloc, rangée par rangée. */
    class Place(val colonne: Int, val bloc: Int, val rangee: Int, val index: Int, val boite: Boite)
    class Titre(val colonne: Int, val bloc: Int, val x: Float, val ligne: Float, val largeur: Float)
    class Plan(val titres: List<Titre>, val cartes: List<Place>, val hu: Float)

    /** La place qu'un bloc prend en unités, titre compris ; un bloc de tête sans titre réserve aussi la bande. */
    fun unites(bloc: Bloc, enTete: Boolean): Float =
        bloc.unitesCartes + if (bloc.titre.isNotEmpty() || (ALIGNER_TITRES && enTete)) TITRE else 0f

    /** Le bord d'une carte au repos : le même pour toutes, fonction de la hauteur de l'écran seulement. */
    fun epaisseurBord(hauteurEcran: Float, epaisseur: Float = 1f): Float = hauteurEcran * 0.003f * epaisseur

    /** Le bord d'une carte du tableau, quelle que soit sa taille (tuile basse ou graphique haut). */
    @Suppress("UNUSED_PARAMETER")
    fun bordPourCarte(hauteurEcran: Float, hauteurCarte: Float, hu: Float): Float = epaisseurBord(hauteurEcran)

    /** L'arrondi d'une carte du tableau : le même pour toutes, sans dépasser le quart d'une rangée. */
    fun rayon(hauteurEcran: Float, hu: Float): Float = minOf(hauteurEcran * 0.016f, hu * 0.25f)

    /**
     * Veille-lot (grille au pixel) : la boîte d'une carte en pixels entiers [gauche, haut, droite, bas]. La carte voisine
     * (à droite, ou en dessous dans la colonne) commence exactement à « bord + écart » : le bord droit et le bas sont donc
     * posés à arrondi(bord + écart) − arrondi(écart), et tous les écarts valent arrondi(écart), au pixel près.
     */
    fun auPixel(b: Boite, ecart: Float): IntArray {
        val e = Math.round(ecart)
        return intArrayOf(Math.round(b.left), Math.round(b.top), Math.round(b.right + ecart) - e, Math.round(b.bottom + ecart) - e)
    }

    fun placer(colonnes: List<List<Bloc>>, unitesMax: Int, w: Float, h: Float, ecartCol: Float): Plan {
        val gauche = w * 0.06f; val droite = w * 0.94f
        val haut = h * 0.235f; val bas = h * 0.9f
        val hu = (bas - haut) / unitesMax
        val ecart = hu * 0.1f
        val n = max(1, colonnes.size)
        val lc = (droite - gauche - ecartCol * (n - 1)) / n
        // Un titre en tête d'une colonne : les autres colonnes réservent sa hauteur, le haut des cartes s'aligne.
        val titreEnTete = ALIGNER_TITRES && colonnes.any { it.firstOrNull()?.titre?.isNotEmpty() == true }
        val titres = ArrayList<Titre>(); val cartes = ArrayList<Place>()
        colonnes.forEachIndexed { i, blocs ->
            val x0 = gauche + i * (lc + ecartCol)
            var y = haut
            blocs.forEachIndexed { b, bloc ->
                if (b > 0) y += hu * ECART_BLOCS
                if (bloc.titre.isNotEmpty()) {
                    titres.add(Titre(i, b, x0, y + hu * 0.36f, lc))
                    y += hu * TITRE
                } else if (b == 0 && titreEnTete) y += hu * TITRE
                bloc.rangees.forEachIndexed { ri, rangee ->
                    val hr = rangee.maxOf { it.hauteur } * hu
                    // Les cartes gardent leurs proportions (sur 12) et la rangée occupe toute la largeur.
                    val somme = rangee.sumOf { max(1, it.largeur) }.toFloat()
                    var x = x0
                    rangee.forEachIndexed { k, carte ->
                        val lk = (lc + ecart) * (max(1, carte.largeur) / somme) - ecart
                        cartes.add(Place(i, b, ri, k, Boite(x, y, x + lk, y + hr - ecart)))
                        x += lk + ecart
                    }
                    y += hr
                }
            }
        }
        return Plan(titres, cartes, hu)
    }
}
