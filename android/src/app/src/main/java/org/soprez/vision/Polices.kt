// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.graphics.Typeface

/**
 * Les deux polices de la maquette : Outfit pour l'heure et les chiffres,
 * Figtree pour le texte. Embarquées (licence OFL), chargées une fois.
 */
object Polices {
    private val cache = HashMap<String, Typeface>()

    private fun charger(ctx: Context, nom: String, secours: Typeface): Typeface =
        cache.getOrPut(nom) { try { Typeface.createFromAsset(ctx.assets, "fonts/$nom.ttf") } catch (_: Exception) { secours } }

    fun outfitFin(ctx: Context) = charger(ctx, "outfit_200", Typeface.create("sans-serif-thin", Typeface.NORMAL))
    fun outfitLeger(ctx: Context) = charger(ctx, "outfit_300", Typeface.create("sans-serif-light", Typeface.NORMAL))
    fun outfit(ctx: Context) = charger(ctx, "outfit_500", Typeface.create("sans-serif-medium", Typeface.NORMAL))
    fun texte(ctx: Context) = charger(ctx, "figtree_400", Typeface.DEFAULT)
    fun moyen(ctx: Context) = charger(ctx, "figtree_500", Typeface.create("sans-serif-medium", Typeface.NORMAL))
    fun demiGras(ctx: Context) = charger(ctx, "figtree_600", Typeface.create("sans-serif-medium", Typeface.BOLD))
    fun gras(ctx: Context) = charger(ctx, "figtree_700", Typeface.DEFAULT_BOLD)
}
