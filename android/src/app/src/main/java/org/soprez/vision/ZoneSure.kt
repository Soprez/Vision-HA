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
 * 1.93.06 (l'auteur, photos du salon : réglé au ras des bords, le logo de la veille était coupé, la pochette, le QR et la file
 * du lecteur touchaient les bords) : le réglage dit SEULEMENT où sont les bords visibles (0 % : tout l'écran est
 * visible). Vision garde TOUJOURS, en plus, une marge de confort fixe à l'intérieur : [CONFORT_X_DP] sur les côtés et
 * [CONFORT_Y_DP] en haut et en bas (les marges de sécurité d'Android TV, soit 5 % d'un écran de télé de 960 × 540 dp).
 * La marge d'un écran = le bord réglé + au moins le confort ([marge]) ; c'est la seule source, partagée par tous les
 * écrans pleins (accueil, veille, lecteur, recherche, réglages, dialogues).
 *
 * Défaut : 0 % (avant : 5 %, qui jouait le rôle du confort). Sans réglage, la mise en page ne bouge donc pas ; un
 * réglage d'avant est ramené au bord rogné ([migrer]) : il ne bouge pas non plus, sauf sous 5 % (au ras des bords).
 * Téléphone et tablette : sans objet (pas d'overscan), leurs marges ne changent pas.
 */
object ZoneSure {
    const val DEFAUT = 0
    /** La marge de confort, toujours gardée en plus du bord réglé (dp). */
    const val CONFORT_X_DP = 48f
    const val CONFORT_Y_DP = 27f
    const val MIN = 0
    const val MAX = 12
    private const val PREFS = "local"
    private const val CLE_X = "zone_sure_x"
    private const val CLE_Y = "zone_sure_y"

    private const val CLE_VERSION = "zone_sure_v"
    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 1.93.06 : avant, le réglage comptait TOUTE la marge (5 % au défaut, sans confort à part) ; il ne compte plus que le
     * bord rogné. Une valeur enregistrée avant (ou rendue par Home Assistant depuis une ancienne sauvegarde) est ramenée
     * à « valeur − 5 » (jamais sous 0) : toute mise en page réglée reste la même, sauf sous 5 % — les réglages au ras des
     * bords, qui gagnent la marge de confort. Une seule fois.
     */
    fun migrer(ctx: Context) {
        val p = p(ctx)
        if (p.getInt(CLE_VERSION, 1) >= 2) return
        val e = p.edit().putInt(CLE_VERSION, 2)
        if (p.contains(CLE_X)) e.putInt(CLE_X, migre(p.getInt(CLE_X, 5)))
        if (p.contains(CLE_Y)) e.putInt(CLE_Y, migre(p.getInt(CLE_Y, 5)))
        e.apply()
    }

    /** Pur : une valeur d'avant 1.93.06 (marge entière) vers le bord rogné. */
    fun migre(ancienne: Int): Int = borner(ancienne - 5)

    /** Le réglage des côtés, en pour cent de la largeur (borné : une valeur d'une autre version reste sûre). */
    fun pourcentX(ctx: Context): Int { migrer(ctx); return borner(p(ctx).getInt(CLE_X, DEFAUT)) }
    /** Le réglage du haut et du bas, en pour cent de la hauteur. */
    fun pourcentY(ctx: Context): Int { migrer(ctx); return borner(p(ctx).getInt(CLE_Y, DEFAUT)) }

    fun poser(ctx: Context, x: Int, y: Int) {
        p(ctx).edit().putInt(CLE_X, borner(x)).putInt(CLE_Y, borner(y)).putInt(CLE_VERSION, 2).apply()
        Local.toucher()
    }

    fun borner(v: Int): Int = v.coerceIn(MIN, MAX)

    /**
     * La marge d'un écran, logique pure : le bord réglé ([reglage] %, rien de visible au-delà) plus la plus grande de la
     * marge de confort ([confortPx]) et de la fraction prévue par l'écran ([dessin], 5 % le plus souvent).
     */
    fun marge(taille: Int, dessin: Float, reglage: Int, confortPx: Int = 0): Int =
        (taille * reglage / 100f).toInt() + maxOf(confortPx, (taille * maxOf(0f, dessin)).toInt())

    /** La marge des côtés d'un écran de télé (en px) ; [dessin] : sa fraction habituelle de la largeur (5 % le plus souvent). */
    fun x(ctx: Context, dessin: Float = 0.05f): Int {
        val l = ctx.resources.displayMetrics.widthPixels
        return if (tele(ctx)) marge(l, dessin, pourcentX(ctx), confortX(ctx)) else (l * maxOf(0f, dessin)).toInt()
    }

    /** La marge du haut et du bas d'un écran de télé (en px). */
    fun y(ctx: Context, dessin: Float = 0.05f): Int {
        val h = ctx.resources.displayMetrics.heightPixels
        return if (tele(ctx)) marge(h, dessin, pourcentY(ctx), confortY(ctx)) else (h * maxOf(0f, dessin)).toInt()
    }

    /**
     * La marge de confort en px (télé ; 0 ailleurs), à la densité RÉELLE de l'écran : un panneau dessiné en densité
     * réduite (la recherche de la musique, à 70 %) garde la même marge que les autres écrans.
     */
    fun confortX(ctx: Context): Int = if (tele(ctx)) Math.round(CONFORT_X_DP * densite(ctx)) else 0
    fun confortY(ctx: Context): Int = if (tele(ctx)) Math.round(CONFORT_Y_DP * densite(ctx)) else 0
    private fun densite(ctx: Context): Float = ctx.applicationContext.resources.displayMetrics.density

    /**
     * La zone où tout doit tenir (px depuis chaque bord) : le bord réglé plus le confort. La recette calculée vérifie
     * qu'aucun élément d'un écran plein n'en sort (captures à 0 % et à 5 %).
     */
    fun zone(ctx: Context): android.graphics.Rect = android.graphics.Rect(x(ctx, 0f), y(ctx, 0f), x(ctx, 0f), y(ctx, 0f))

    /**
     * L'échelle du contenu d'un écran dessiné en fractions de l'écran (la veille, dessinée pour une marge de 5 % qui est
     * le confort) : 1 sans bord réglé ; plus petite quand la télé rogne, pour que la marge de 5 % du dessin tombe au bord
     * réglé plus le confort (la plus serrée des deux directions, sans déformer).
     */
    fun echelle(ctx: Context): Float = if (!tele(ctx)) 1f else echelle(pourcentX(ctx), pourcentY(ctx))

    fun echelle(x: Int, y: Int): Float = minOf(1f - x / 45f, 1f - y / 45f).coerceIn(0.7f, 1f)

    /**
     * 1.93.06 : un écran plein qui place LUI-MÊME son contenu avec [x] / [y] (son fond va bord à bord : accueil, veille,
     * lecteur, recherche, réglages…). Tous les autres écrans pleins de la télé reçoivent la zone d'un seul coup, sur
     * leur conteneur racine ([poserRacine], appelé par VisionApp pour chaque écran) : personne n'a sa marge à part.
     */
    interface Geree

    /** Ce que [poserRacine] ajoute au conteneur racine de [a] (px) : la zone sur la télé, pour un écran plein non [Geree]. */
    fun ajoutRacine(a: android.app.Activity): android.graphics.Rect =
        if (!tele(a) || a is Geree || a.window?.isFloating != false) android.graphics.Rect() else zone(a)

    /**
     * Pose la zone sur le conteneur racine de [a] (android.R.id.content), en plus de [base] (les barres du système) ; le
     * fond de l'écran passe à la fenêtre, pour qu'il aille toujours bord à bord.
     */
    fun poserRacine(a: android.app.Activity, base: android.graphics.Rect = android.graphics.Rect()) {
        val contenu = a.findViewById<android.view.ViewGroup>(android.R.id.content) ?: return
        val z = ajoutRacine(a)
        contenu.setPadding(base.left + z.left, base.top + z.top, base.right + z.right, base.bottom + z.bottom)
        if (z.left == 0 && z.top == 0) return
        contenu.getChildAt(0)?.background?.constantState?.newDrawable()?.let { a.window.setBackgroundDrawable(it) }
    }

    private fun tele(ctx: Context) = ReglagesTvActivity.estTele(ctx)
}
