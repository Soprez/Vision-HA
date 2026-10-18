// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import java.util.WeakHashMap

/**
 * Lot Accueil (point 10) : une seule famille d'animations pour tout ce qui montre un message Vision
 * (plein écran, fenêtre par-dessus l'appli en cours, message dans la veille, bulle). l'auteur : « elles apparaissent trop
 * sèchement ».
 *
 * - Entrée : fondu 0 → 1, léger glissement (16 dp, depuis le bord où le message est posé), léger agrandissement
 *   0,96 → 1, en [DUREE_ENTREE_MS], courbe qui ralentit à l'arrivée ([COURBE_ENTREE]).
 * - Sortie : le même chemin à l'envers, plus courte ([DUREE_SORTIE_MS]), courbe qui accélère ([COURBE_SORTIE]) ;
 *   la vue n'est retirée (ou l'activité finie) qu'à la fin, par `fin`.
 * - « Supprimer les animations » / échelle d'animation à 0 : tout est immédiat, sans état intermédiaire.
 * - Seuls alpha, translation et échelle bougent (ViewPropertyAnimator), sous couche matérielle (withLayer) :
 *   ni relayout ni ombre recalculée à chaque image, fluide sur les télés modestes.
 *
 * La logique (durées, courbes, sens selon la position, état de départ) est pure et testée sur la JVM (AnimTest).
 */
object Anim {
    /** D'où vient le message : il glisse depuis ce bord. [SUR_PLACE] : fondu et agrandissement seuls (petite fenêtre). */
    enum class Sens { BAS, HAUT, GAUCHE, DROITE, CENTRE, SUR_PLACE }

    const val DUREE_ENTREE_MS = 240L
    const val DUREE_SORTIE_MS = 160L
    const val GLISSEMENT_DP = 16f
    const val ECHELLE_DEPART = 0.96f

    /**
     * Courbes de Bézier cubiques (x1, y1, x2, y2), toutes deux « ease-out ». 1.93.05 (l'auteur, règles d'Emil Kowalski) : jamais
     * d'« ease-in » — une sortie lente au départ paraît en retard sur l'appui ; elle part vite et se pose (courbe forte
     * 0.23, 1, 0.32, 1), en 160 ms, plus vite que l'entrée.
     */
    val COURBE_ENTREE = floatArrayOf(0f, 0f, 0.2f, 1f)
    val COURBE_SORTIE = floatArrayOf(0.23f, 1f, 0.32f, 1f)

    /** L'état de départ (et d'arrivée de la sortie) : décalage en px, échelle, alpha. */
    data class Depart(val dx: Float, val dy: Float, val echelle: Float, val alpha: Float = 0f)

    // ------------------------------------------------------------------ Logique pure

    /** Animer ? Non si l'échelle système vaut 0 ou si Android dit les animateurs coupés. */
    fun actifSelon(echelleSysteme: Float, animateursActifs: Boolean): Boolean = animateursActifs && echelleSysteme > 0f

    /** La durée réellement demandée : 0 quand les animations sont supprimées (affichage immédiat). */
    fun duree(base: Long, actif: Boolean): Long = if (actif) base else 0L

    /** Le sens d'un message selon sa position (`position` des messages de Home Assistant). */
    fun sensPourPosition(position: String, pleinEcran: Boolean): Sens = when {
        pleinEcran -> Sens.CENTRE
        position.startsWith("haut") -> Sens.HAUT
        position.startsWith("bas") -> Sens.BAS
        position == "gauche" -> Sens.GAUCHE
        position == "droite" -> Sens.DROITE
        else -> Sens.CENTRE
    }

    /** D'où part la vue : un message du haut descend, un message du bas monte, un message centré monte à peine. */
    fun depart(sens: Sens, glissementPx: Float): Depart = when (sens) {
        Sens.BAS -> Depart(0f, glissementPx, ECHELLE_DEPART)
        Sens.HAUT -> Depart(0f, -glissementPx, ECHELLE_DEPART)
        Sens.GAUCHE -> Depart(-glissementPx, 0f, ECHELLE_DEPART)
        Sens.DROITE -> Depart(glissementPx, 0f, ECHELLE_DEPART)
        Sens.CENTRE -> Depart(0f, glissementPx, ECHELLE_DEPART)
        Sens.SUR_PLACE -> Depart(0f, 0f, ECHELLE_DEPART)
    }

    /** La valeur d'une courbe de Bézier cubique (0,0)-(x1,y1)-(x2,y2)-(1,1) au temps x ∈ [0, 1] (comme PathInterpolator). */
    fun courbe(c: FloatArray, x: Float): Float {
        if (x <= 0f) return 0f
        if (x >= 1f) return 1f
        fun b(t: Float, p1: Float, p2: Float): Float { val u = 1 - t; return 3 * u * u * t * p1 + 3 * u * t * t * p2 + t * t * t }
        var bas = 0f; var haut = 1f; var t = x
        repeat(40) {
            t = (bas + haut) / 2
            if (b(t, c[0], c[2]) < x) bas = t else haut = t
        }
        return b(t, c[1], c[3])
    }

    // ------------------------------------------------------------------ Android

    private val interEntree: Interpolator by lazy { PathInterpolator(COURBE_ENTREE[0], COURBE_ENTREE[1], COURBE_ENTREE[2], COURBE_ENTREE[3]) }
    private val interSortie: Interpolator by lazy { PathInterpolator(COURBE_SORTIE[0], COURBE_SORTIE[1], COURBE_SORTIE[2], COURBE_SORTIE[3]) }

    /** Les vues en train de sortir : un second appel ne relance rien et `fin` ne passe qu'une fois. */
    private val enSortie = WeakHashMap<View, Boolean>()

    /** L'échelle « Durée des animations » des options pour les développeurs (1 par défaut). */
    fun echelleSysteme(ctx: Context): Float =
        try { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) } catch (_: Exception) { 1f }

    /** Les animations sont-elles permises sur cet appareil ? (réglage système « supprimer les animations ») */
    fun actif(ctx: Context): Boolean =
        actifSelon(echelleSysteme(ctx), if (Build.VERSION.SDK_INT >= 26) ValueAnimator.areAnimatorsEnabled() else true)

    /**
     * À appeler AVANT que la vue soit attachée ou dessinée : elle part invisible, à l'état de départ, pour qu'aucune
     * image ne la montre à pleine opacité avant l'animation. Animations supprimées : elle est posée à son état final.
     */
    fun preparer(v: View, sens: Sens = Sens.CENTRE, alphaFinal: Float = 1f) {
        enSortie.remove(v)
        v.animate().cancel()
        if (!actif(v.context)) { poserFinal(v, alphaFinal); return }
        val d = depart(sens, Ui.dp(v.context, GLISSEMENT_DP).toFloat())
        v.alpha = d.alpha; v.translationX = d.dx; v.translationY = d.dy; v.scaleX = d.echelle; v.scaleY = d.echelle
    }

    /** L'entrée douce (la vue doit avoir été préparée, sinon elle l'est ici). */
    fun entrer(v: View, sens: Sens = Sens.CENTRE, alphaFinal: Float = 1f, fin: (() -> Unit)? = null) {
        if (!actif(v.context)) { poserFinal(v, alphaFinal); fin?.invoke(); return }
        if (v.alpha >= alphaFinal && v.translationX == 0f && v.translationY == 0f && v.scaleX == 1f) preparer(v, sens, alphaFinal)
        v.animate().alpha(alphaFinal).translationX(0f).translationY(0f).scaleX(1f).scaleY(1f)
            .setDuration(DUREE_ENTREE_MS).setStartDelay(0).setInterpolator(interEntree).withLayer()
            .withEndAction { fin?.invoke() }.start()
    }

    /** Le fond d'un plein écran, avant son premier rendu : transparent (ou opaque tout de suite sans animations). */
    fun preparerFond(v: View) {
        v.animate().cancel()
        v.alpha = if (actif(v.context)) 0f else 1f
    }

    /** Le fond d'un plein écran : fondu seul (il couvre l'écran, il ne glisse pas). */
    fun entrerFond(v: View) {
        v.animate().cancel()
        if (!actif(v.context)) { v.alpha = 1f; return }
        if (v.alpha >= 1f) v.alpha = 0f
        v.animate().alpha(1f).setDuration(DUREE_ENTREE_MS).setStartDelay(0).setInterpolator(interEntree).withLayer().start()
    }

    /** La sortie : le chemin d'entrée à l'envers, plus courte ; `fin` (retirer la vue, finir l'activité) à la fin seulement. */
    fun sortir(v: View, sens: Sens = Sens.CENTRE, fin: () -> Unit) {
        if (enSortie[v] == true) return
        if (!actif(v.context) || !v.isAttachedToWindow || v.alpha == 0f) { fin(); return }
        enSortie[v] = true
        val d = depart(sens, Ui.dp(v.context, GLISSEMENT_DP).toFloat())
        v.animate().cancel()
        v.animate().alpha(0f).translationX(d.dx).translationY(d.dy).scaleX(d.echelle).scaleY(d.echelle)
            .setDuration(DUREE_SORTIE_MS).setStartDelay(0).setInterpolator(interSortie).withLayer()
            .withEndAction { if (enSortie.remove(v) == true) fin() }.start()
    }

    /** Le fond d'un plein écran qui s'efface (sans `fin` : c'est la carte qui la porte). */
    fun sortirFond(v: View) {
        if (!actif(v.context) || !v.isAttachedToWindow) return
        v.animate().cancel()
        v.animate().alpha(0f).setDuration(DUREE_SORTIE_MS).setStartDelay(0).setInterpolator(interSortie).withLayer().start()
    }

    private fun poserFinal(v: View, alphaFinal: Float) {
        v.alpha = alphaFinal; v.translationX = 0f; v.translationY = 0f; v.scaleX = 1f; v.scaleY = 1f
    }
}
