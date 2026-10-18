// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 1.90.1 (l'auteur : « j'avais pas toutes les infos, ça me mettait +24 ») : les lignes du détail d'une tuile, autant que la
 * place RÉELLE de la tuile en permet (plus de nombre fixe par hauteur), puis « +N » pour ce qui ne tient pas — jamais de
 * troncature silencieuse : l'appui sur la tuile ouvre le détail complet, qui défile. À la télécommande (TV), haut et bas
 * font défiler les lignes quand la tuile a le focus ([descendre], [monter]).
 *
 * [total] : le nombre de lignes du détail côté serveur (il peut dépasser celles reçues : elles comptent dans « +N »).
 */
class ListeDetail(
    ctx: Context,
    private val lignes: List<View>,
    private val plus: TextView,
    private val total: Int,
    private val textePlus: (Int) -> String,
    private val descriptionPlus: (Int) -> String,
    debutInitial: Int = 0,
) : LinearLayout(ctx) {
    /** L'index de la première ligne montrée (0 sauf après un défilement à la télécommande). */
    var debut = debutInitial
        private set
    /** Le nombre de lignes montrées à la dernière mesure. */
    var montrees = 0
        private set

    init {
        orientation = VERTICAL
        lignes.forEach { addView(it) }
        addView(plus)
    }

    /** Pure : combien de lignes montrer, à partir de [debut], dans [dispo] px, « +N » compris s'il le faut. */
    companion object {
        fun combien(hauteurs: List<Int>, debut: Int, total: Int, hauteurPlus: Int, dispo: Int): Int {
            var n = 0; var h = 0
            for (i in debut until hauteurs.size) {
                if (h + hauteurs[i] > dispo) break
                h += hauteurs[i]; n++
            }
            // Ce qui ne tient pas (ou n'a pas été reçu) se dit par « +N » : il lui faut sa place.
            while (n > 0 && debut + n < total && h + hauteurPlus > dispo) { n--; h -= hauteurs[debut + n] }
            return n
        }
    }

    override fun onMeasure(largeurSpec: Int, hauteurSpec: Int) {
        val dispo = if (MeasureSpec.getMode(hauteurSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE
            else MeasureSpec.getSize(hauteurSpec) - paddingTop - paddingBottom
        val libre = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        lignes.forEach { it.visibility = VISIBLE; measureChildWithMargins(it, largeurSpec, 0, libre, 0) }
        plus.visibility = VISIBLE
        plus.text = textePlus(maxOf(1, total))
        measureChildWithMargins(plus, largeurSpec, 0, libre, 0)
        fun haut(v: View) = v.measuredHeight + ((v.layoutParams as? MarginLayoutParams)?.let { it.topMargin + it.bottomMargin } ?: 0)
        debut = debut.coerceIn(0, maxOf(0, lignes.size - 1))
        val n = combien(lignes.map(::haut), debut, total, haut(plus), dispo)
        montrees = n
        lignes.forEachIndexed { i, v -> v.visibility = if (i >= debut && i < debut + n) VISIBLE else GONE }
        val autres = total - (debut + n)
        if (autres > 0) {
            plus.visibility = VISIBLE
            plus.text = textePlus(autres)
            plus.contentDescription = descriptionPlus(autres)
        } else plus.visibility = GONE
        super.onMeasure(largeurSpec, hauteurSpec)
    }

    fun peutDescendre(): Boolean = debut + montrees < lignes.size
    fun peutMonter(): Boolean = debut > 0
    fun descendre(): Boolean { if (!peutDescendre()) return false; debut++; requestLayout(); return true }
    fun monter(): Boolean { if (!peutMonter()) return false; debut--; requestLayout(); return true }
}
