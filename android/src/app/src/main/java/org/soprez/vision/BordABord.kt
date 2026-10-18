// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout

/**
 * 1.91.0 (photos de l'auteur : sur l'accueil de la télé, la rangée « Applications » avait une marge de chaque côté et les
 * icônes étaient coupées net au bord de cette marge) : une rangée télé qui défile d'un bord de l'écran à l'autre.
 *
 * - La rangée (sa zone de défilement) va jusqu'aux bords de l'écran : elle sort de la marge de sa colonne.
 * - Au repos, le premier élément est aligné sur la zone sûre de la télé (overscan, [Marges.ZONE_SURE]) ; le dernier
 *   aussi, en bout de défilement.
 * - Ce qui dépasse dans la zone d'overscan s'efface en fondu (jamais de coupure dure), et le focus de la télécommande
 *   ne s'arrête jamais dans ce fondu : la rangée défile pour garder l'élément choisi dans la zone sûre.
 * Les valeurs sont calculées par [calculer] (essayé en JUnit, BordABordTest).
 */
object BordABord {
    /** [sortie] : de combien la rangée sort de sa colonne de chaque côté ; [debut] / [fin] : ses marges intérieures. */
    data class Valeurs(val sortieGauche: Int, val sortieDroite: Int, val debut: Int, val fin: Int, val fondu: Int)

    /**
     * La colonne a [margeGauche] / [margeDroite] de marge (zone sûre comprise) ; [gauche] : la rangée commence au bord
     * gauche de l'écran (sinon elle commence dans la colonne, après une étiquette par exemple) ; [interieur] : la
     * petite marge d'origine de la rangée (anneau de focus).
     */
    fun calculer(margeGauche: Int, margeDroite: Int, interieur: Int, gauche: Boolean = true): Valeurs {
        val sg = if (gauche) margeGauche else 0
        return Valeurs(sg, margeDroite, sg + interieur, margeDroite + interieur, maxOf(margeGauche, margeDroite))
    }

    /**
     * Pose la rangée [defile] (qui contient [rangee]) bord à bord, dans une colonne [LinearLayout] verticale ou
     * horizontale. À appeler une fois la rangée ajoutée à sa colonne.
     */
    fun poser(defile: HorizontalScrollView, rangee: ViewGroup, margeGauche: Int, margeDroite: Int, interieur: Int, gauche: Boolean = true) {
        val v = calculer(margeGauche, margeDroite, interieur, gauche)
        val lp = (defile.layoutParams as? LinearLayout.LayoutParams)
            ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.leftMargin = -v.sortieGauche; lp.rightMargin = -v.sortieDroite
        defile.layoutParams = lp
        rangee.setPadding(v.debut, rangee.paddingTop, v.fin, rangee.paddingBottom)
        defile.isHorizontalFadingEdgeEnabled = true
        defile.setFadingEdgeLength(v.fondu)
        defile.overScrollMode = View.OVER_SCROLL_NEVER
        // La rangée garde son propre cadre (le bord de l'écran) ; le fondu fait le reste.
        Ui.cadre(defile)
        defile.tag = TAG
    }

    /** Repère les rangées posées bord à bord (essais instrumentés). */
    const val TAG = "rangee_bord_a_bord"
}
