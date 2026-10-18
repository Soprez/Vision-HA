// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

/**
 * Soutenir le projet : un badge décoratif, rien ne se débloque.
 *
 * Home Assistant dit dans le relevé si le lieu soutient Vision (`"soutien": {"actif": true, "mode": "code"}`) ;
 * le code se saisit dans Home Assistant, jamais ici. L'appli montre une carte « Payez-moi un café » (QR, badge si actif) et, de temps
 * en temps, un clin d'œil discret sur la veille. Rien du tout quand le soutien est inactif.
 */
object Soutien {
    const val LIEN = "https://buymeacoffee.com/soprez"

    /** Ce que Home Assistant a dit ; [mode] vaut « code » ou « honneur » (vide si inactif). */
    class Statut(val actif: Boolean, val mode: String)

    val INACTIF = Statut(false, "")

    /** La clé `soutien` du relevé ; absente (ancien Home Assistant) ou mal formée : inactif. */
    fun lire(o: JSONObject?): Statut =
        if (o != null && o.optBoolean("actif", false)) Statut(true, o.optString("mode", "")) else INACTIF

    fun actif(): Boolean = Etat.soutien.actif

    /** « Réduire les animations » du système : durée des animations à zéro. */
    fun animationsReduites(ctx: Context): Boolean =
        try { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f } catch (_: Exception) { false }

    /** Le QR de la page de don (sombre sur clair, marge d'un module), [cote] en pixels. */
    fun qr(cote: Int): android.graphics.Bitmap? = try {
        val m = com.google.zxing.qrcode.QRCodeWriter().encode(LIEN, com.google.zxing.BarcodeFormat.QR_CODE, cote, cote,
            mapOf(com.google.zxing.EncodeHintType.MARGIN to 1))
        val pts = IntArray(cote * cote)
        for (y in 0 until cote) for (x in 0 until cote) pts[y * cote + x] = if (m[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        android.graphics.Bitmap.createBitmap(pts, cote, cote, android.graphics.Bitmap.Config.RGB_565)
    } catch (_: Exception) { null }

    /** La petite boîte : ce qu'est le badge, comment l'activer ; sur le téléphone d'un Décideur, la page de don s'ouvre. */
    fun expliquer(act: Activity, tele: Boolean) {
        val texte = StringBuilder()
        if (actif()) texte.append(act.getString(R.string.soutien_merci)).append("\n\n")
        texte.append(act.getString(R.string.soutien_explication)).append("\n\n").append(act.getString(R.string.soutien_comment))
        if (tele || !Etat.parent) texte.append("\n\n").append(act.getString(R.string.soutien_adresse, act.getString(R.string.soutien_lien)))
        val d = Ui.dialogue(act).setTitle(act.getString(R.string.soutien_titre)).setMessage(texte.toString())
        // Le navigateur ne s'ouvre que sur un appareil Décideur (comme sur le PC) ; ailleurs, l'adresse suffit.
        if (tele || !Etat.parent) d.setPositiveButton(act.getString(R.string.soutien_fermer), null)
        else d.setPositiveButton(act.getString(R.string.soutien_ouvrir)) { _, _ ->
            try { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(LIEN))) }
            catch (_: Exception) { Ui.toast(act, act.getString(R.string.soutien_lien)) }
        }.setNegativeButton(act.getString(R.string.soutien_fermer), null)
        d.show()
    }
}

/**
 * L'œil de Vision qui cligne (~400 ms), puis un petit cœur qui monte et s'efface (~1,5 s). Aucun son.
 * Animations réduites : pas de mouvement, le cœur fixe 2 s. [auRepos] : l'œil reste dessiné entre deux clins
 * d'œil (ligne de réglage) ; sinon la vue n'affiche rien hors animation (veille).
 */
class VueSoutien(ctx: Context, private val auRepos: Boolean) : View(ctx) {
    private var debut = 0L
    private var reduit = false
    private var theme: Theme = Ui.themeActif(ctx)
    private val pinceau = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oeil = Path()
    private val coeur = Path()

    private val entree get() = if (auRepos) 0L else 250L
    private val sortie get() = if (auRepos) 0L else 300L
    private val duree get() = if (reduit) 2000L else entree + 1800L + sortie

    init { isFocusable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun jouer() {
        reduit = Soutien.animationsReduites(context)
        theme = Ui.themeActif(context)
        debut = SystemClock.uptimeMillis()
        invalidate()
    }

    fun arreter() { debut = 0L; invalidate() }

    override fun onDetachedFromWindow() { debut = 0L; super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val t = if (debut == 0L) -1L else SystemClock.uptimeMillis() - debut
        val enCours = t >= 0 && t < duree
        if (!enCours) debut = 0L
        if (!enCours && !auRepos) return
        val th = theme
        val w = width.toFloat(); val h = height.toFloat()
        val l = w * 0.92f
        val u = l / 36f
        val cx = w / 2f
        val cy = h - 10f * u - w * 0.04f

        // Apparition et disparition de l'œil (veille seulement).
        val alphaOeil = when {
            !enCours || auRepos || reduit -> 1f
            t < entree -> t / entree.toFloat()
            t > duree - sortie -> (duree - t) / sortie.toFloat()
            else -> 1f
        }
        // Le clin d'œil : la paupière se ferme puis se rouvre.
        val tc = t - entree
        val k = if (enCours && !reduit && tc in 0..400) 1f - 0.92f * sin(PI * tc / 400.0).toFloat() else 1f

        oeil.reset()
        oeil.moveTo(cx, cy - 10f * u)
        oeil.cubicTo(cx - 9f * u, cy - 10f * u, cx - 15.5f * u, cy - 4f * u, cx - 18f * u, cy)
        oeil.cubicTo(cx - 15.5f * u, cy + 4f * u, cx - 9f * u, cy + 10f * u, cx, cy + 10f * u)
        oeil.cubicTo(cx + 9f * u, cy + 10f * u, cx + 15.5f * u, cy + 4f * u, cx + 18f * u, cy)
        oeil.cubicTo(cx + 15.5f * u, cy - 4f * u, cx + 9f * u, cy - 10f * u, cx, cy - 10f * u)
        oeil.close()

        c.save()
        c.scale(1f, k, cx, cy)
        pinceau.style = Paint.Style.FILL
        pinceau.color = 0xFFFBF3EE.toInt(); pinceau.alpha = (255 * alphaOeil).toInt()
        c.drawPath(oeil, pinceau)
        c.save()
        c.clipPath(oeil)
        pinceau.color = th.or; pinceau.alpha = (255 * alphaOeil).toInt()
        c.drawCircle(cx, cy, 6.5f * u, pinceau)
        pinceau.color = th.pourpre; pinceau.alpha = (255 * alphaOeil).toInt()
        c.drawCircle(cx, cy, 3f * u, pinceau)
        c.restore()
        pinceau.style = Paint.Style.STROKE; pinceau.strokeWidth = 1.6f * u
        pinceau.color = th.or; pinceau.alpha = (255 * alphaOeil).toInt()
        c.drawPath(oeil, pinceau)
        c.restore()

        // Le cœur : monte depuis l'œil et s'efface ; fixe 2 s si les animations sont réduites.
        if (enCours) {
            val p = if (reduit) 0.35f else ((t - entree - 300L) / 1500f)
            if (p in 0f..1f) {
                val taille = w * (if (reduit) 0.34f else 0.22f + 0.12f * p)
                val haut = taille * 0.6f
                val bas = cy - 10f * u
                val y = bas - (bas - haut) * p
                val a = if (reduit) 1f else if (p < 0.1f) p / 0.1f else 1f - (p - 0.1f) / 0.9f
                coeur.reset()
                coeur.moveTo(cx, y + taille * 0.35f)
                coeur.cubicTo(cx - taille * 0.9f, y - taille * 0.2f, cx - taille * 0.35f, y - taille * 0.85f, cx, y - taille * 0.35f)
                coeur.cubicTo(cx + taille * 0.35f, y - taille * 0.85f, cx + taille * 0.9f, y - taille * 0.2f, cx, y + taille * 0.35f)
                coeur.close()
                pinceau.style = Paint.Style.FILL
                pinceau.color = 0xFFE0566A.toInt(); pinceau.alpha = (255 * a.coerceIn(0f, 1f)).toInt()
                c.drawPath(coeur, pinceau)
            }
            postInvalidateOnAnimation()
        }
    }
}

/**
 * Le clin d'œil sur la veille : toutes les 8 à 15 min (jamais dans les 2 premières), dans le coin bas droit,
 * un peu déplacé à chaque fois (pas de marquage d'écran). Une vue à part, posée au-dessus de la veille
 * dans son cadre : l'image figée de la page n'est pas touchée.
 */
class SoutienVeille(private val hote: View) {
    private val main = Handler(Looper.getMainLooper())
    private val hasard = Random()
    private var vue: VueSoutien? = null
    private var enMarche = false

    private val tour = Runnable { montrer(); planifier() }

    fun demarrer() {
        arreter()
        enMarche = true
        planifier()
    }

    fun arreter() {
        enMarche = false
        main.removeCallbacks(tour)
        vue?.let { it.arreter(); (it.parent as? ViewGroup)?.removeView(it) }
        vue = null
    }

    private fun planifier() {
        if (!enMarche) return
        val delai = (8 * 60_000L + (hasard.nextDouble() * 7 * 60_000L).toLong()).coerceAtLeast(2 * 60_000L)
        main.postDelayed(tour, delai)
    }

    private fun montrer() {
        if (!enMarche || !Soutien.actif() || !hote.isShown) return
        val cadre = hote.parent as? FrameLayout ?: return
        val cote = (minOf(cadre.width, cadre.height) * 0.065f).toInt().coerceAtLeast(Ui.dp(hote.context, 28f))
        val marge = (minOf(cadre.width, cadre.height) * 0.035f).toInt()
        val lp = FrameLayout.LayoutParams(cote, (cote * 1.6f).toInt(), Gravity.BOTTOM or Gravity.END).apply {
            marginEnd = marge + hasard.nextInt(marge + 1)
            bottomMargin = marge + hasard.nextInt(marge + 1)
        }
        val v = vue ?: VueSoutien(hote.context, auRepos = false).also { vue = it }
        if (v.parent == null) cadre.addView(v, lp) else { v.layoutParams = lp; v.bringToFront() }
        v.jouer()
    }
}
