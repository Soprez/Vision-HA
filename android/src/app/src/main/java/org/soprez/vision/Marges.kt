package org.soprez.vision

/**
 * Les marges de l'écran de blocage (retour d'essai : « des erreurs de marge sur les contours »).
 * - Télé : une zone sûre de 5 % de chaque côté (les téléviseurs rognent parfois le bord de l'image), dans laquelle
 *   le contenu défile, plus une petite marge intérieure.
 * - Téléphone et tablette : 28 dp sur les côtés, et le contenu jamais plus large que 560 dp (centré) : sur une
 *   tablette ou un téléphone en paysage, la carte ne s'étire plus d'un bord à l'autre.
 * Les barres du système (heure, gestes, encoche) sont déjà écartées par VisionApp.
 */
object Marges {
    data class Valeurs(val cote: Int, val haut: Int, val bas: Int, val zoneCote: Int, val zoneHaut: Int)

    const val ZONE_SURE = 0.05f
    const val LARGEUR_MAX_DP = 560f
    const val COTE_DP = 28f

    /** [zoneX] / [zoneY] : la zone sûre réglée sur cet appareil, en % (17-10, « Ajuster l'écran », ZoneSure). */
    fun calculer(largeurPx: Int, hauteurPx: Int, densite: Float, tele: Boolean,
                 zoneX: Int = ZoneSure.DEFAUT, zoneY: Int = ZoneSure.DEFAUT): Valeurs {
        fun dp(v: Float) = Math.round(v * densite)
        if (tele) {
            val zc = ZoneSure.marge(largeurPx, ZONE_SURE, zoneX, dp(ZoneSure.CONFORT_X_DP))
            val zh = ZoneSure.marge(hauteurPx, ZONE_SURE, zoneY, dp(ZoneSure.CONFORT_Y_DP))
            val utile = largeurPx - 2 * zc
            val cote = maxOf(dp(16f), (utile - dp(LARGEUR_MAX_DP * 1.4f)) / 2)
            return Valeurs(cote, dp(12f), dp(12f), zc, zh)
        }
        val cote = maxOf(dp(COTE_DP), (largeurPx - dp(LARGEUR_MAX_DP)) / 2)
        return Valeurs(cote, dp(COTE_DP), dp(COTE_DP), 0, 0)
    }

    /**
     * 1.90.1 : la largeur où l'écran dessine vraiment. Android 15+ étend la fenêtre sous les barres du système et
     * VisionApp en écarte le contenu : la largeur de l'écran moins les barres sur les côtés (paysage).
     */
    fun largeurUtile(a: android.app.Activity): Int {
        val l = a.resources.displayMetrics.widthPixels
        if (android.os.Build.VERSION.SDK_INT < 35) return l
        val ins = a.window?.decorView?.rootWindowInsets ?: return l
        val (g, d) = cotesSysteme(ins)
        return l - g - d
    }

    /** 1.90.1 : la largeur prise à gauche et à droite par les barres du système et l'encoche (paysage). */
    fun cotesSysteme(ins: android.view.WindowInsets): Pair<Int, Int> =
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val b = ins.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            b.left to b.right
        } else {
            @Suppress("DEPRECATION")
            val coupe = if (android.os.Build.VERSION.SDK_INT >= 28) ins.displayCutout else null
            @Suppress("DEPRECATION")
            maxOf(ins.systemWindowInsetLeft, coupe?.safeInsetLeft ?: 0) to maxOf(ins.systemWindowInsetRight, coupe?.safeInsetRight ?: 0)
        }
    /** Lot 6 (B8 / APP-29) : la largeur de lecture des écrans de gestion sur tablette (comme les espaces du lot 5). */
    const val LECTURE_MAX_DP = 760f

    /**
     * La marge de côté (dp) d'un écran de gestion large de [largeurDp] : [base] tant que la colonne tient, sinon de quoi
     * centrer une colonne de [LECTURE_MAX_DP] (les lignes ne s'étirent plus sur toute une tablette en paysage).
     */
    fun coteLecture(largeurDp: Int, base: Float = 20f): Float =
        if (largeurDp - 2 * base > LECTURE_MAX_DP) (largeurDp - LECTURE_MAX_DP) / 2f else base
}
