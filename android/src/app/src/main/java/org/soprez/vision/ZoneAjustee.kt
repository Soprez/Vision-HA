// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.widget.FrameLayout

/**
 * 1.93.06.1 (l'auteur, télé du salon en disposition verticale : la consigne posée sous l'horloge et le chauffage avait ses
 * − et + sous le bord de l'écran au repos) : une zone de widgets de côté qui tient TOUJOURS dans sa hauteur.
 *
 * Son contenu (la colonne des cartes) est mesuré à sa taille naturelle. S'il dépasse la hauteur de la zone, il est réduit
 * d'un seul tenant (même échelle pour toutes les cartes, centré en largeur) : rien n'est coupé, rien ne défile. S'il
 * tient, il reste à sa taille, centré en hauteur, comme avant.
 */
class ZoneAjustee(ctx: Context) : FrameLayout(ctx) {
    /** L'échelle appliquée au contenu (1 : à sa taille). */
    var echelle = 1f
        private set

    init { clipChildren = false; clipToPadding = false }

    override fun onMeasure(largeurSpec: Int, hauteurSpec: Int) {
        val largeur = MeasureSpec.getSize(largeurSpec)
        val enfant = if (childCount > 0) getChildAt(0) else null
        if (enfant == null) { super.onMeasure(largeurSpec, hauteurSpec); return }
        enfant.measure(MeasureSpec.makeMeasureSpec(largeur, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val hauteur = if (MeasureSpec.getMode(hauteurSpec) == MeasureSpec.UNSPECIFIED) enfant.measuredHeight else MeasureSpec.getSize(hauteurSpec)
        echelle = facteur(enfant.measuredHeight, hauteur)
        setMeasuredDimension(largeur, hauteur)
    }

    override fun onLayout(change: Boolean, gauche: Int, haut: Int, droite: Int, bas: Int) {
        val enfant = if (childCount > 0) getChildAt(0) else return
        val l = droite - gauche; val h = bas - haut
        enfant.layout(0, 0, l, enfant.measuredHeight)
        enfant.pivotX = l / 2f; enfant.pivotY = 0f
        enfant.scaleX = echelle; enfant.scaleY = echelle
        enfant.translationY = position(enfant.measuredHeight, h, echelle)
    }

    companion object {
        /** Pur : l'échelle qui fait tenir [contenu] px dans [dispo] px (jamais d'agrandissement). */
        fun facteur(contenu: Int, dispo: Int): Float =
            if (contenu <= 0 || dispo <= 0 || contenu <= dispo) 1f else dispo.toFloat() / contenu

        /** Pur : le haut du contenu réduit, centré dans la zone. */
        fun position(contenu: Int, dispo: Int, echelle: Float): Float = ((dispo - contenu * echelle) / 2f).coerceAtLeast(0f)
    }
}
