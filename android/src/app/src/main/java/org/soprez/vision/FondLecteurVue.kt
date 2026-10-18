// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View

/**
 * 1.91.0 : le fond animé de l'écran Musique (réglages et couleurs : [FondLecteur]). Tout est calculé à partir de
 * l'heure : une image ne dépend que de l'instant, l'écran peut se rebâtir sans que le fond saute.
 *
 * Léger : au plus [FondLecteur.cadence] images par seconde ; aucun dessin quand la vue n'est pas visible ou que
 * l'écran est en pause ([actif]) ; une seule image, fixe, quand les animations sont réduites ([Anim.actif]). Les
 * dégradés sont fabriqués une fois par jeu de couleurs (et pendant le fondu d'une pochette à l'autre), puis déplacés.
 */
class FondLecteurVue(ctx: Context, private val th: Theme, private val tele: Boolean) : View(ctx) {
    var style = FondLecteur.style(ctx)
        set(v) { field = v; invalidate() }
    var intensite = FondLecteur.intensite(ctx)
        set(v) { field = v; invalidate() }
    /** Réactif au son : le niveau du son que joue cet appareil (0 à 1), lu à chaque image ; null : mouvement régulier. */
    var niveau: (() -> Float)? = null
    /** L'écran est au premier plan (posé par l'activité en onResume / onPause). */
    var actif = true
        set(v) { field = v; if (v) invalidate() }

    /** Le nombre d'images dessinées (essais : cadence, image fixe sans animations). */
    @Volatile var images = 0; private set

    /** Les couleurs visées (fond puis trois teintes), pour les essais. */
    fun couleurs(): IntArray = cible.copyOf()

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pImage = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val chemin = Path()
    private val m = Matrix()
    private val rectSrc = Rect()
    private val rectDst = RectF()
    private val depart = 0L
    // 17-10 (l'auteur, point 17) : la cadence et le détail suivent le profil de l'appareil, mesuré en continu.
    private val profil = ProfilFond(tele, modeste(ctx), android.os.Build.VERSION.SDK_INT <= 28)
    /** Le niveau du profil (essais, journal). */
    val niveauProfil: Int get() = profil.niveau
    /** Le son lissé d'une image à l'autre : le mouvement suit la musique sans à-coups. */
    private var sonLisse = 0f
    private var programmee = false
    private val suivante = Runnable { invalidate() }
    private var visible = true
    private var dernier = 0L

    // Couleurs : la cible (thème ou pochette) et les couleurs montrées, qui la rejoignent en fondu.
    private var cible: IntArray = FondLecteur.couleurs(th.fond, th.halos, th.pourpre, th.sombre, null)
    private var montrees: IntArray = cible.copyOf()
    private var fonduDepuis = 0L
    private var avantFondu: IntArray = cible.copyOf()
    private var lw = 0; private var lh = 0
    private var pret = false
    private val degrades = arrayOfNulls<Shader>(4)
    private val taches = arrayOfNulls<Bitmap>(3)
    private val teintes = arrayOfNulls<android.graphics.ColorFilter>(3)

    /** Les couleurs de la pochette en cours (ou null : celles du thème). Le passage se fait en fondu (1,2 s). */
    fun pochette(c: IntArray?) {
        val n = FondLecteur.couleurs(th.fond, th.halos, th.pourpre, th.sombre, c)
        if (n.contentEquals(cible)) return
        avantFondu = montrees.copyOf(); cible = n; fonduDepuis = SystemClock.uptimeMillis()
        if (!Anim.actif(context)) { montrees = cible.copyOf(); fonduDepuis = 0L; pret = false }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); pret = false }

    @android.annotation.TargetApi(24)
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        visible = isVisible
        if (isVisible) invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(suivante)
        taches.forEachIndexed { i, b -> b?.recycle(); taches[i] = null }
        pret = false
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val anime = Anim.actif(context)
        val maintenant = SystemClock.uptimeMillis()
        // Une seule image fixe sans animations : un instant choisi, toujours le même. L'heure repart toutes les 6 h : un
        // Float garde ainsi une précision bien meilleure qu'une image (un saut discret, quatre fois par jour).
        val t = if (anime) ((maintenant - depart) % 21_600_000L) / 1000f else 42f
        majCouleurs(maintenant)
        c.drawColor(montrees[0])
        // L'écart réel avec l'image précédente, quand celle-ci en avait programmé une (pas après une pause).
        if (programmee && dernier > 0L && profil.noter(maintenant - dernier))
            android.util.Log.i("Vision", "Fond animé : profil ${profil.niveau} (${"%.0f".format(1000f / maxOf(1f, profil.moyenneMs))} images/s mesurées), ${profil.cadence()} images/s visées")
        val brut = if (anime) (niveau?.invoke() ?: 0f) else 0f
        sonLisse += (brut - sonLisse) * 0.25f
        val son = sonLisse
        when (style) {
            FondLecteur.Style.AUCUN -> {}
            FondLecteur.Style.ONDES -> ondes(c, w, h, t, son)
            FondLecteur.Style.PARTICULES -> particules(c, w, h, t, son)
            FondLecteur.Style.AURORE -> aurore(c, w, h, t, son)
            FondLecteur.Style.DEGRADE -> degrade(c, w, h, t, son)
        }
        dernier = maintenant
        images++
        programmee = anime && actif && visible && style != FondLecteur.Style.AUCUN && isAttachedToWindow
        if (programmee) {
            val intervalle = 1000L / profil.cadence()
            // 1.91.0 (recette TV : 40 images/s au lieu de 24) : une seule image suivante en attente. Avant, chaque dessin
            // (y compris ceux qu'une autre vue provoque, la barre de lecture par exemple) en programmait une de plus, et
            // les attentes s'empilaient.
            removeCallbacks(suivante)
            postDelayed(suivante, (intervalle - (SystemClock.uptimeMillis() - maintenant)).coerceAtLeast(4L))
        }
    }

    private fun majCouleurs(maintenant: Long) {
        if (fonduDepuis == 0L) { if (!pret) preparer(); return }
        val k = ((maintenant - fonduDepuis) / 1200f).coerceIn(0f, 1f)
        montrees = IntArray(cible.size) { i -> FondLecteur.melange(avantFondu.getOrElse(i) { cible[i] }, cible[i], k) }
        if (k >= 1f) fonduDepuis = 0L
        preparer()
    }

    /** Les dégradés et taches lumineuses du jeu de couleurs montré (et de la taille de l'écran). */
    private fun preparer() {
        val w = width; val h = height
        if (w <= 0 || h <= 0) return
        lw = w; lh = h
        val col = montrees
        val r = maxOf(w, h).toFloat()
        // Dégradé vivant : un fond en biais, deux grandes lueurs.
        degrades[0] = LinearGradient(0f, 0f, r, 0f, intArrayOf(FondLecteur.melange(col[0], col[1], 0.35f), col[0], FondLecteur.melange(col[0], col[2], 0.35f)), null, Shader.TileMode.MIRROR)
        degrades[1] = RadialGradient(0f, 0f, r * 0.55f, intArrayOf(col[2] and 0x00FFFFFF or (0xB0 shl 24), col[2] and 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        degrades[2] = RadialGradient(0f, 0f, r * 0.45f, intArrayOf(col[3] and 0x00FFFFFF or (0xB0 shl 24), col[3] and 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        // Aurore : un voile vertical, transparent aux deux bords.
        degrades[3] = LinearGradient(0f, 0f, 0f, h * 0.36f, intArrayOf(0x00FFFFFF, 0xFFFFFFFF.toInt(), 0x00FFFFFF), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        for (k in 0 until 3) teintes[k] = android.graphics.PorterDuffColorFilter(col[1 + k], android.graphics.PorterDuff.Mode.MULTIPLY)
        // Particules : une tache douce par couleur, petite (dessinée agrandie).
        for (i in 0 until 3) {
            val b = taches[i]?.takeIf { !it.isRecycled } ?: Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).also { taches[i] = it }
            b.eraseColor(0)
            val cc = Canvas(b)
            val pp = Paint(Paint.ANTI_ALIAS_FLAG)
            val c0 = col[i + 1]
            pp.shader = RadialGradient(24f, 24f, 24f, intArrayOf(c0 or 0xFF000000.toInt(), c0 and 0x00FFFFFF or (0x90 shl 24), c0 and 0x00FFFFFF), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
            cc.drawCircle(24f, 24f, 24f, pp)
        }
        pret = true
    }

    private fun alpha(base: Float, son: Float) = (FondLecteur.opacite(base, intensite, son) * 255).toInt()

    /** Ondes douces : quatre vagues superposées dans le bas de l'écran, qui ondulent lentement. */
    private fun ondes(c: Canvas, w: Float, h: Float, t: Float, son: Float) {
        val v = intensite.mouvement
        p.shader = null; p.style = Paint.Style.FILL
        val n = ((if (tele) 36 else 28) * profil.detail()).toInt().coerceAtLeast(12)
        for (k in 0 until 4) {
            val base = h * (0.58f + 0.1f * k)
            val amp = h * (0.035f + 0.012f * k) * (0.7f + 0.5f * v) * (1f + 0.8f * son)
            chemin.reset(); chemin.moveTo(0f, h)
            for (i in 0..n) {
                val x = w * i / n
                val y = base + amp * FondLecteur.bruit(i * 3f / n + k * 0.9f, t * 0.22f * v * (1f + 0.15f * k), k + 1)
                if (i == 0) chemin.lineTo(0f, y) else chemin.lineTo(x, y)
            }
            chemin.lineTo(w, h); chemin.close()
            p.color = montrees[1 + k % 3]; p.alpha = alpha(0.32f - 0.04f * k, son)
            c.drawPath(chemin, p)
        }
    }

    /** Particules : des taches de lumière qui montent en dérivant, comme des bulles. */
    private fun particules(c: Canvas, w: Float, h: Float, t: Float, son: Float) {
        val v = intensite.mouvement
        val nb = ((if (tele) 34 else 26) * profil.detail()).toInt().coerceAtLeast(10)
        val cote = minOf(w, h)
        for (i in 0 until nb) {
            fun hasard(k: Int) = FondLecteur.hasard(i, k)
            val taille = cote * (0.018f + 0.05f * hasard(1)) * (1f + 0.5f * son)
            val vitesse = (0.012f + 0.03f * hasard(2)) * v
            val y = h + taille - ((hasard(3) + t * vitesse) % 1f) * (h + 2 * taille)
            val x = w * hasard(4) + cote * 0.04f * FondLecteur.bruit(t * 0.1f * v, i.toFloat(), i)
            val b = taches[i % 3] ?: continue
            pImage.alpha = alpha(0.2f + 0.35f * hasard(5), son)
            rectSrc.set(0, 0, b.width, b.height)
            rectDst.set(x - taille, y - taille, x + taille, y + taille)
            c.drawBitmap(b, rectSrc, rectDst, pImage)
        }
    }

    /** Aurore : trois rubans de lumière qui ondulent en haut de l'écran. */
    private fun aurore(c: Canvas, w: Float, h: Float, t: Float, son: Float) {
        val v = intensite.mouvement
        val voile = degrades[3] ?: return
        val n = ((if (tele) 30 else 24) * profil.detail()).toInt().coerceAtLeast(12)
        for (k in 0 until 3) {
            val haut = h * (0.04f + 0.12f * k)
            val amp = h * 0.07f * (0.7f + 0.4f * v) * (1f + 0.6f * son)
            val epais = h * 0.36f
            chemin.reset()
            for (i in 0..n) {
                val x = w * i / n
                val y = haut + amp * FondLecteur.bruit(i * 2.2f / n + k, t * 0.12f * v, k + 7)
                if (i == 0) chemin.moveTo(x, y) else chemin.lineTo(x, y)
            }
            for (i in n downTo 0) {
                val x = w * i / n
                val y = haut + epais + amp * FondLecteur.bruit(i * 2.2f / n + k + 0.4f, t * 0.12f * v, k + 11)
                chemin.lineTo(x, y)
            }
            chemin.close()
            m.setTranslate(0f, haut); voile.setLocalMatrix(m)
            p.shader = voile
            // Le voile blanc est teinté par la couleur du ruban (filtre de couleur multiplicatif).
            p.colorFilter = teintes[k]
            p.alpha = alpha(0.42f - 0.08f * k, son)
            c.drawPath(chemin, p)
        }
        p.shader = null; p.colorFilter = null
    }

    /** Dégradé vivant : le fond glisse en biais, deux lueurs se promènent. */
    private fun degrade(c: Canvas, w: Float, h: Float, t: Float, son: Float) {
        val v = intensite.mouvement
        val fond = degrades[0] ?: return
        m.setRotate(25f + 20f * FondLecteur.bruit(0.3f, t * 0.05f * v, 3)); m.postTranslate(t * 18f * v, 0f)
        fond.setLocalMatrix(m)
        p.shader = fond; p.alpha = alpha(0.9f, son)
        c.drawRect(0f, 0f, w, h, p)
        // Très léger : une seule lueur (chacune repeint tout l'écran).
        for (k in 0 until if (profil.niveau >= 2) 1 else 2) {
            val s = degrades[1 + k] ?: continue
            val x = w * (0.3f + 0.4f * k + 0.22f * FondLecteur.bruit(k.toFloat(), t * 0.07f * v, 21 + k))
            val y = h * (0.35f + 0.3f * k + 0.2f * FondLecteur.bruit(k + 0.5f, t * 0.06f * v, 31 + k))
            val e = 1f + 0.25f * son
            m.setScale(e, e); m.postTranslate(x, y); s.setLocalMatrix(m)
            p.shader = s; p.alpha = alpha(0.5f, son)
            c.drawRect(0f, 0f, w, h, p)
        }
        p.shader = null
    }

    companion object {
        /** Un appareil modeste (peu de mémoire : Chromecast, vieilles télés) : moins d'images par seconde. */
        fun modeste(ctx: Context): Boolean = try {
            (ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).isLowRamDevice
        } catch (_: Exception) { false }
    }
}
