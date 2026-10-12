// SPDX-License-Identifier: MIT
package org.soprez.vision

/**
 * Le glisser-déposer de l'accueil (Vision 1.63.1) : ce qui se décide, sans Android, pour être essayé.
 *
 * Bug l'auteur (Pixel 9, widget VLC) : après un déplacement, la zone « Glisser ici pour retirer » restait
 * affichée et le widget restait grisé. Le nettoyage dépendait de la case glissée elle-même ; quand
 * elle n'était pas reconstruite (dépôt sans vrai déplacement) ou ne recevait pas la fin du glisser,
 * rien ne se remettait. Désormais, TOUTE fin de glisser passe par [EtatGlisser.fin], une seule fois.
 */
object Glisser {
    /** Où l'on a saisi la case (décalage dans la case, en cellules) : le point sous le doigt au départ. */
    fun prise(c: Int, r: Int, col: Int, row: Int, w: Int, h: Int): Pair<Int, Int> =
        (c - col).coerceIn(0, w - 1) to (r - row).coerceIn(0, h - 1)

    /**
     * Où tombe une case w × h quand le doigt est sur la cellule (c, r) : là où on l'a saisie reste sous le
     * doigt (sans prise connue, un widget neuf tiré du panneau : centré).
     */
    fun cible(c: Int, r: Int, w: Int, h: Int, colonnes: Int, prise: Pair<Int, Int>? = null): Pair<Int, Int> {
        val (dc, dr) = prise ?: ((w - 1) / 2 to (h - 1) / 2)
        return (c - dc).coerceIn(0, (colonnes - w).coerceAtLeast(0)) to (r - dr).coerceAtLeast(0)
    }

    /** A-t-elle vraiment bougé ? Pour un grand widget, glisser d'une case suffit (le doigt reste sur lui). */
    fun aBouge(col: Int, row: Int, cible: Pair<Int, Int>): Boolean = cible.first != col || cible.second != row

    /**
     * La case libre la plus proche de (c0, r0) pour une case w × h, sans chevauchement : (c0, r0) elle-même si
     * elle est libre, sinon la plus proche (un écart de ligne compte double ; à égalité, la première en lisant
     * la grille de gauche à droite et de haut en bas). La recherche s'arrête à `lignes` ; null si rien n'est libre.
     */
    fun caseLibre(c0: Int, r0: Int, w: Int, h: Int, colonnes: Int, lignes: Int, libre: (Int, Int) -> Boolean): Pair<Int, Int>? {
        val c = c0.coerceIn(0, (colonnes - w).coerceAtLeast(0)); val r = r0.coerceAtLeast(0)
        if (libre(c, r)) return c to r
        var meilleur: Pair<Int, Int>? = null; var dist = Int.MAX_VALUE
        for (rr in 0 until lignes) for (cc in 0..(colonnes - w)) {
            if (libre(cc, rr)) { val d = Math.abs(cc - c) + Math.abs(rr - r) * 2; if (d < dist) { dist = d; meilleur = cc to rr } }
        }
        return meilleur
    }

    /** Ce que fait un dépôt sur la zone du haut : retirer une case, défaire un dossier, ou annuler l'ajout d'une appli. */
    enum class Retrait { RETIRER, DEFAIRE_DOSSIER, ANNULER_AJOUT }

    /** `ajout` : l'appli vient du tiroir ou d'un dossier (elle n'était pas encore posée là) ; rien n'est retiré. */
    fun surRetrait(cle: String, ajout: Boolean): Retrait = when {
        ajout -> Retrait.ANNULER_AJOUT
        cle.startsWith(Accueil.PREFIXE_DOSSIER) -> Retrait.DEFAIRE_DOSSIER
        else -> Retrait.RETIRER
    }
}

/**
 * Appui long puis glisser, depuis le tiroir ou un dossier, comme un lanceur Android : l'appui long ouvre le menu ;
 * si le doigt part ensuite au-delà du seuil (un peu plus que scaledTouchSlop), le menu s'efface et le glisser
 * commence, une seule fois par geste. Relâché sans bouger : le menu reste.
 */
class AppuiPuisGlisser(private val seuil: Float) {
    enum class Etape { APPUI, MENU, GLISSER }
    var etape = Etape.APPUI; private set
    private var x0 = 0f; private var y0 = 0f

    fun debut(x: Float, y: Float) { x0 = x; y0 = y; etape = Etape.APPUI }

    /** L'appui long vient d'ouvrir le menu : la distance se compte depuis là où le doigt se trouve alors. */
    fun appuiLong(x: Float, y: Float) { if (etape == Etape.APPUI) { x0 = x; y0 = y; etape = Etape.MENU } }

    /** Vrai une seule fois : le moment de fermer le menu et de commencer le glisser. */
    fun bouge(x: Float, y: Float): Boolean {
        if (etape != Etape.MENU) return false
        if (Math.hypot((x - x0).toDouble(), (y - y0).toDouble()) <= seuil) return false
        etape = Etape.GLISSER
        return true
    }

    fun fin() { etape = Etape.APPUI }
}

/** L'état d'un glisser en cours, et sa fin, quelle qu'elle soit (dépôt, dépôt hors zone, annulation). */
class EtatGlisser<V>(
    private val montrerZone: (Boolean) -> Unit,
    private val opacite: (V, Float) -> Unit,
) {
    var cle: String? = null; private set
    var sorti = false; private set
    private var vue: V? = null
    private var menu: (() -> Unit)? = null

    fun debut(cle: String, vue: V, menu: (() -> Unit)?) {
        this.cle = cle; this.vue = vue; this.menu = menu; sorti = false
        opacite(vue, 0.25f)
        montrerZone(true)
    }

    /** Le doigt a quitté la place d'origine (ou est entré dans la zone de retrait). */
    fun sortir() { sorti = true }

    /**
     * Fin du glisser : la zone se cache, la case retrouve son opacité. Rend le menu à ouvrir
     * (appui long sans bouger ni dépôt réussi), ou null. Appelée plusieurs fois, elle n'agit qu'une.
     */
    fun fin(depotReussi: Boolean): (() -> Unit)? {
        montrerZone(false)
        vue?.let { opacite(it, 1f) }
        val m = if (cle != null && !sorti && !depotReussi) menu else null
        cle = null; vue = null; menu = null; sorti = false
        return m
    }
}
