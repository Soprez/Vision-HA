// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context

/**
 * 17-10 (l'auteur, point 12, PRIORITÉ) : « Ajuster l'écran », la zone sûre de la télé, comme sur les consoles.
 *
 * Beaucoup de téléviseurs rognent le bord de l'image (overscan) : la TCL du salon coupait la rangée de widgets du haut,
 * celle du bas et les applis au focus (points 10, 11, 21). La marge se règle PAR APPAREIL, en pour cent de la largeur
 * (côtés) et de la hauteur (haut et bas), dans Réglages › Affichage › Ajuster l'écran (AjusterEcranActivity : un cadre
 * à flèches à aligner sur les bords visibles). Toute l'interface de la télé la respecte : accueil, veille, lecteur,
 * menus et réglages.
 *
 * Défaut : 5 % (la valeur pour laquelle les écrans de Vision ont été dessinés, et la recommandation de Google TV). Les
 * écrans gardent leur propre dessin : chacun demande sa marge habituelle (« pour 5 % ») et [x] / [y] la décalent de
 * l'écart entre le réglage et 5 %. Au défaut, rien ne bouge ; une télé qui rogne davantage écarte tout d'autant.
 * Téléphone et tablette : sans objet (pas d'overscan), leurs marges ne changent pas.
 */
object ZoneSure {
    const val DEFAUT = 5
    const val MIN = 0
    const val MAX = 12
    private const val PREFS = "local"
    private const val CLE_X = "zone_sure_x"
    private const val CLE_Y = "zone_sure_y"

    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Le réglage des côtés, en pour cent de la largeur (borné : une valeur d'une autre version reste sûre). */
    fun pourcentX(ctx: Context): Int = borner(p(ctx).getInt(CLE_X, DEFAUT))
    /** Le réglage du haut et du bas, en pour cent de la hauteur. */
    fun pourcentY(ctx: Context): Int = borner(p(ctx).getInt(CLE_Y, DEFAUT))

    fun poser(ctx: Context, x: Int, y: Int) {
        p(ctx).edit().putInt(CLE_X, borner(x)).putInt(CLE_Y, borner(y)).apply()
        Local.toucher()
    }

    fun borner(v: Int): Int = v.coerceIn(MIN, MAX)

    /**
     * La marge d'un écran, logique pure : [dessin] est la fraction prévue par l'écran pour une zone sûre de 5 % ; elle
     * est décalée de l'écart entre [reglage] (en %) et le défaut. Jamais négative.
     */
    fun marge(taille: Int, dessin: Float, reglage: Int): Int =
        (taille * maxOf(0f, dessin + (reglage - DEFAUT) / 100f)).toInt()

    /** La marge des côtés d'un écran de télé (en px) ; [dessin] : sa fraction habituelle de la largeur (5 % le plus souvent). */
    fun x(ctx: Context, dessin: Float = DEFAUT / 100f): Int =
        marge(ctx.resources.displayMetrics.widthPixels, dessin, if (tele(ctx)) pourcentX(ctx) else DEFAUT)

    /** La marge du haut et du bas d'un écran de télé (en px). */
    fun y(ctx: Context, dessin: Float = DEFAUT / 100f): Int =
        marge(ctx.resources.displayMetrics.heightPixels, dessin, if (tele(ctx)) pourcentY(ctx) else DEFAUT)

    /**
     * L'échelle du contenu d'un écran dessiné en fractions de l'écran (la veille) : 1 au défaut ; plus petite quand la
     * télé rogne davantage (les deux côtés et le haut et le bas : la plus serrée des deux, sans déformer).
     */
    fun echelle(ctx: Context): Float = if (!tele(ctx)) 1f else echelle(pourcentX(ctx), pourcentY(ctx))

    fun echelle(x: Int, y: Int): Float = minOf(1f - 2f * (x - DEFAUT) / 100f, 1f - 2f * (y - DEFAUT) / 100f).coerceIn(0.75f, 1.1f)

    private fun tele(ctx: Context) = ReglagesTvActivity.estTele(ctx)
}
