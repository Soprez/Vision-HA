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

    fun calculer(largeurPx: Int, hauteurPx: Int, densite: Float, tele: Boolean): Valeurs {
        fun dp(v: Float) = Math.round(v * densite)
        if (tele) {
            val zc = Math.round(largeurPx * ZONE_SURE); val zh = Math.round(hauteurPx * ZONE_SURE)
            val utile = largeurPx - 2 * zc
            val cote = maxOf(dp(16f), (utile - dp(LARGEUR_MAX_DP * 1.4f)) / 2)
            return Valeurs(cote, dp(12f), dp(12f), zc, zh)
        }
        val cote = maxOf(dp(COTE_DP), (largeurPx - dp(LARGEUR_MAX_DP)) / 2)
        return Valeurs(cote, dp(COTE_DP), dp(COTE_DP), 0, 0)
    }
}
