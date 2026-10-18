// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.View
import org.json.JSONObject

/**
 * 1.93.05 (l'auteur, retour 36) : ce que la veille montre par-dessus ses pages, dans un coin, au-dessus de tout (la veille
 * dessinée par l'appli comme la page de veille de Home Assistant) — ici le bandeau « Lecteur musique » : pochette,
 * titre, artiste et l'état lecture/pause, discret, dans la zone sûre, aux couleurs du thème de la veille. L'horloge
 * reste seule au centre. Il ne vient que si l'option est mise sur cet appareil ([OptionsVeille.lecteur]) et seulement
 * pendant la lecture de la musique de cet appareil (encore une minute après une pause, pour la reprendre à la
 * télécommande sans quitter la veille).
 *
 * 1.93.05 (l'auteur, retour 39) : le QR code des invités, quand le mode soirée est ouvert — le même QR et le même jeton que
 * le lecteur (Party.qrAvecTheme) —, petit mais lisible à 2 ou 3 m (19 % de la hauteur), sous-titré « Ajoute ta
 * musique », dans le coin choisi ([OptionsVeille.qr], en bas à droite par défaut, ou masqué). Le logo (l'œil de Vision,
 * dessiné par la veille) a aussi son coin ; le bandeau prend le premier coin libre ([Coins]) : rien ne se chevauche.
 *
 * Rien ici ne prend le focus ni les touches : la vue est transparente aux gestes.
 */
@SuppressLint("ViewConstructor")
class CoinsVeille(ctx: Context, private val veille: VeilleView) : View(ctx) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var etat: Party.Etat? = null
    @Volatile private var pochette: Bitmap? = null
    @Volatile private var pauseDepuis = 0L
    @Volatile private var qr: Bitmap? = null
    @Volatile private var qrContenu = ""
    private var fil: Thread? = null
    private val pinceau = Paint(Paint.ANTI_ALIAS_FLAG)
    private val texte = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val chemin = Path()
    private val xy = IntArray(2)
    private val amplitude = DeriveVeille.amplitude(resources.displayMetrics.density)
    // 1.93.05 (l'auteur, règles d'Emil Kowalski) : le bandeau et le QR arrivent en fondu (180 ms) et s'en vont plus vite (120 ms),
    // en « ease-out », avec un léger 0,95 → 1 sauf animations réduites ; ce qui s'efface reste dessiné le temps du fondu.
    private val fonduBandeau = Fondu()
    private val fonduQr = Fondu()
    private var dernierEtat: Party.Etat? = null
    private var dernierQr: Bitmap? = null

    init {
        isFocusable = false; isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        translationZ = 100f  // au-dessus de la page de veille de Home Assistant, posée après
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); demarrer() }
    override fun onDetachedFromWindow() { arreter(); super.onDetachedFromWindow() }

    private fun demarrer() {
        if (fil != null) return
        val app = context.applicationContext
        fil = Thread {
            while (!Thread.currentThread().isInterrupted) {
                if (OptionsVeille.lecteur(app) || OptionsVeille.qr(app) != OptionsVeille.CACHE) try {
                    val cfg = Config(app)
                    val e = Party.analyser(Net.post(app, cfg, "/api/pc_parental/party/etat", JSONObject().put("id", cfg.id).put("secret", cfg.secret), 8_000))
                    val m = e.maintenant
                    if (m.etat == "playing") pauseDepuis = 0L else if (pauseDepuis == 0L) pauseDepuis = SystemClock.uptimeMillis()
                    pochette = if (OptionsVeille.lecteur(app) && m.pochette.isNotEmpty()) Pochettes.lire(app, e.lecteur, m.pochette) else null
                    poserQr(e)
                    etat = e
                } catch (_: Exception) {} else { etat = null; poserQr(null) }
                main.post { invalidate() }
                try { Thread.sleep(5_000) } catch (_: InterruptedException) { break }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun arreter() { fil?.interrupt(); fil = null }

    /** Le QR des invités de [e] (mode soirée ouvert), refait seulement quand son contenu change. */
    private fun poserQr(e: Party.Etat?) {
        val contenu = if (e != null && Coins.qrMontre(e.actif, e.invitesOuverts, e.qr)) Party.qrAvecTheme(e.qr, Local.themeEffectif(context, Palette.APPLI)) else ""
        if (contenu == qrContenu) return
        qrContenu = contenu
        qr = if (contenu.isEmpty()) null else try {
            val cote = 360
            val m = com.google.zxing.qrcode.QRCodeWriter().encode(contenu, com.google.zxing.BarcodeFormat.QR_CODE, cote, cote, mapOf(com.google.zxing.EncodeHintType.MARGIN to 1))
            val pts = IntArray(cote * cote)
            for (y in 0 until cote) for (x in 0 until cote) pts[y * cote + x] = if (m[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            Bitmap.createBitmap(pts, cote, cote, Bitmap.Config.RGB_565)
        } catch (_: Exception) { null }
    }

    /** Pour les essais : l'état de la musique comme s'il venait d'être relu. */
    internal fun poserEtat(e: Party.Etat?, image: Bitmap? = null) {
        poserQr(e)
        etat = e; pochette = image
        pauseDepuis = if (e == null || e.maintenant.etat == "playing") 0L else SystemClock.uptimeMillis()
        invalidate()
    }

    /** Pour les essais : la place du bandeau, du QR et le contenu du QR à la dernière image (vides s'ils ne sont pas montrés). */
    internal val boiteBandeau = RectF()
    internal val boiteQr = RectF()
    internal val contenuQr: String get() = qrContenu

    override fun onDraw(c: Canvas) {
        boiteBandeau.setEmpty(); boiteQr.setEmpty()
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val e = etat
        val montrer = Bandeau.visible(OptionsVeille.lecteur(context), e?.let { Bandeau.Joue(it.maintenant.etat, it.maintenant.titre, LecteursSuivis.permis(context, it)) },
            pauseDepuis, SystemClock.uptimeMillis())
        val coinQr = OptionsVeille.qr(context)
        val maintenant = SystemClock.uptimeMillis()
        if (montrer && e != null) dernierEtat = e
        qr?.let { dernierQr = it }
        val aBandeau = fonduBandeau.pas(montrer && e != null, maintenant)
        val aQr = fonduQr.pas(qr != null && coinQr != OptionsVeille.CACHE, maintenant)
        if (fonduBandeau.enCours() || fonduQr.enCours()) postInvalidateOnAnimation()
        val eb = dernierEtat; val image = dernierQr
        if ((aBandeau <= 0f || eb == null) && (aQr <= 0f || image == null || coinQr == OptionsVeille.CACHE)) return
        val bouger = Anim.actif(context)
        // Comme les pages de la veille : la lente dérive anti-marquage et le resserrement de « Ajuster l'écran ».
        DeriveVeille.decalage(SystemClock.uptimeMillis(), amplitude, xy)
        val n = c.save()
        val echelle = ZoneSure.echelle(context)
        if (echelle != 1f) c.scale(echelle, echelle, w / 2, h / 2)
        c.translate(xy[0].toFloat(), xy[1].toFloat())
        if (aBandeau > 0f && eb != null) {
            Coins.bandeau(Coins.coinBandeau(coinQr, OptionsVeille.logo(context)), w, h, r)
            avecFondu(c, r, aBandeau, bouger) { dessinerBandeau(c, r, h, eb) }
            boiteBandeau.set(r)
        }
        if (aQr > 0f && image != null && coinQr != OptionsVeille.CACHE) {
            Coins.qr(coinQr, w, h, r)
            avecFondu(c, r, aQr, bouger) { dessinerQr(c, r, h, image) }
            boiteQr.set(r)
        }
        c.restoreToCount(n)
    }

    /** Dessine [dessin] à l'opacité [a] (et de 0,95 à 1 autour de son centre si [bouger]). */
    private inline fun avecFondu(c: Canvas, b: RectF, a: Float, bouger: Boolean, dessin: () -> Unit) {
        if (a >= 1f) { dessin(); return }
        val n = c.saveLayerAlpha(b.left - 4, b.top - 4, b.right + 4, b.bottom + 4, (a * 255).toInt())
        if (bouger) { val e = 0.95f + 0.05f * a; c.scale(e, e, b.centerX(), b.centerY()) }
        dessin()
        c.restoreToCount(n)
    }

    /** Un fondu interruptible : repart de là où il en est quand la cible change (jamais de saut). Pur (horloge donnée). */
    internal class Fondu(private val entreeMs: Long = 180L, private val sortieMs: Long = 120L) {
        private var cible = false
        private var depart = 0f
        private var t0 = 0L
        private var actuel = 0f

        fun pas(voulu: Boolean, maintenant: Long): Float {
            if (voulu != cible) { depart = actuel; cible = voulu; t0 = maintenant }
            val p = ((maintenant - t0).toFloat() / (if (cible) entreeMs else sortieMs)).coerceIn(0f, 1f)
            val fin = if (cible) 1f else 0f
            actuel = if (p >= 1f) fin else depart + (fin - depart) * Anim.courbe(COURBE, p)
            return actuel
        }

        fun enCours(): Boolean = actuel != (if (cible) 1f else 0f)

        companion object { val COURBE = floatArrayOf(0.23f, 1f, 0.32f, 1f) }
    }

    private fun dessinerQr(c: Canvas, b: RectF, h: Float, image: Bitmap) {
        val rayon = h * 0.018f
        pinceau.style = Paint.Style.FILL; pinceau.color = veille.carte; pinceau.alpha = 240
        c.drawRoundRect(b, rayon, rayon, pinceau)
        pinceau.style = Paint.Style.STROKE; pinceau.strokeWidth = h * 0.0018f; pinceau.color = veille.carteBord; pinceau.alpha = 255
        c.drawRoundRect(b, rayon, rayon, pinceau)
        val pad = h * Coins.PAD; val cote = h * Coins.QR
        // Le code lui-même, noir sur blanc (lisible de loin, quel que soit le thème), sans lissage.
        pinceau.style = Paint.Style.FILL; pinceau.isFilterBitmap = false
        c.drawBitmap(image, null, RectF(b.left + pad, b.top + pad, b.left + pad + cote, b.top + pad + cote), pinceau)
        pinceau.isFilterBitmap = true
        texte.typeface = Polices.demiGras(context); texte.textSize = h * 0.022f; texte.color = veille.encre; texte.textAlign = Paint.Align.CENTER
        c.drawText(context.getString(R.string.veille_qr_ajoute), b.centerX(), b.bottom - pad - h * 0.008f, texte)
        texte.textAlign = Paint.Align.LEFT
    }

    private fun dessinerBandeau(c: Canvas, b: RectF, h: Float, e: Party.Etat) {
        val m = e.maintenant
        val rayon = b.height() * 0.28f
        pinceau.style = Paint.Style.FILL; pinceau.color = veille.carte; pinceau.alpha = 235
        c.drawRoundRect(b, rayon, rayon, pinceau)
        pinceau.style = Paint.Style.STROKE; pinceau.strokeWidth = h * 0.0018f; pinceau.color = veille.carteBord; pinceau.alpha = 255
        c.drawRoundRect(b, rayon, rayon, pinceau)
        val pad = b.height() * 0.14f
        val cote = b.height() - 2 * pad
        val img = RectF(b.left + pad, b.top + pad, b.left + pad + cote, b.top + pad + cote)
        pinceau.style = Paint.Style.FILL; pinceau.color = Palette.melanger(veille.carte, veille.or, 0.18f)
        c.drawRoundRect(img, cote * 0.16f, cote * 0.16f, pinceau)
        pochette?.takeIf { !it.isRecycled }?.let { bmp ->
            val s = c.save(); chemin.reset(); chemin.addRoundRect(img, cote * 0.16f, cote * 0.16f, Path.Direction.CW); c.clipPath(chemin)
            c.drawBitmap(bmp, null, img, pinceau); c.restoreToCount(s)
        }
        // Lecture / pause : le rond à droite, l'état qu'une touche lecture/pause changerait.
        val rb = cote * 0.42f
        val cx = b.right - pad - rb; val cy = b.centerY()
        pinceau.style = Paint.Style.STROKE; pinceau.strokeWidth = h * 0.0022f; pinceau.color = veille.carteBord
        c.drawCircle(cx, cy, rb, pinceau)
        pinceau.style = Paint.Style.FILL; pinceau.color = veille.encre
        if (m.etat == "playing") {
            val bw = rb * 0.18f; val bh = rb * 0.46f
            c.drawRect(cx - rb * 0.30f - bw / 2, cy - bh, cx - rb * 0.30f + bw / 2, cy + bh, pinceau)
            c.drawRect(cx + rb * 0.30f - bw / 2, cy - bh, cx + rb * 0.30f + bw / 2, cy + bh, pinceau)
        } else {
            chemin.reset(); chemin.moveTo(cx - rb * 0.28f, cy - rb * 0.42f); chemin.lineTo(cx + rb * 0.42f, cy); chemin.lineTo(cx - rb * 0.28f, cy + rb * 0.42f); chemin.close()
            c.drawPath(chemin, pinceau)
        }
        // Titre et artiste, coupés avant le bouton.
        val x = img.right + pad
        val max = (cx - rb - pad) - x
        texte.typeface = Polices.demiGras(context); texte.textSize = b.height() * 0.27f; texte.color = veille.encre
        c.drawText(TextUtils.ellipsize(m.titre, android.text.TextPaint(texte), max, TextUtils.TruncateAt.END).toString(), x, b.top + b.height() * 0.46f, texte)
        if (m.artiste.isNotEmpty()) {
            texte.typeface = Polices.texte(context); texte.textSize = b.height() * 0.21f; texte.color = veille.encre2
            c.drawText(TextUtils.ellipsize(m.artiste, android.text.TextPaint(texte), max, TextUtils.TruncateAt.END).toString(), x, b.top + b.height() * 0.76f, texte)
        }
    }

    /**
     * Les coins de la veille, en fractions de l'écran (w × h) et dans la zone sûre (5 %) : le QR des invités, le logo
     * (l'œil et « Vision »), le bandeau ; et ce qui se décale pour eux (la demande en bas à droite, la petite horloge en
     * haut à droite). Pur (essais).
     */
    object Coins {
        const val MARGE = 0.05f
        /** Côté du QR et marge intérieure de sa carte, en fraction de la hauteur ; la légende dessous. */
        const val QR = 0.19f
        const val PAD = 0.016f
        const val LEGENDE = 0.045f

        fun gauche(coin: String) = coin == OptionsVeille.HG || coin == OptionsVeille.BG
        fun haut(coin: String) = coin == OptionsVeille.HG || coin == OptionsVeille.HD

        /** Le QR est-il à montrer ? Mode soirée ouvert, avec son adresse. */
        fun qrMontre(actif: Boolean, invitesOuverts: Boolean, adresse: String): Boolean = actif && invitesOuverts && adresse.isNotEmpty()

        private fun poser(coin: String, w: Float, h: Float, lw: Float, lh: Float, sortie: RectF) {
            val l = if (gauche(coin)) w * MARGE else w * (1 - MARGE) - lw
            val t = if (haut(coin)) h * MARGE else h * (1 - MARGE) - lh
            sortie.set(l, t, l + lw, t + lh)
        }

        fun qr(coin: String, w: Float, h: Float, sortie: RectF) = poser(coin, w, h, h * (QR + 2 * PAD), h * (QR + 2 * PAD + LEGENDE), sortie)

        fun bandeau(coin: String, w: Float, h: Float, sortie: RectF) = poser(coin, w, h, w * 0.28f, h * 0.10f, sortie)

        /** Le centre de l'œil : en bas à gauche, sa place d'avant (un peu dans la marge) ; ailleurs, en miroir. */
        fun oeil(coin: String, w: Float, h: Float): Pair<Float, Float> {
            val dx = w * 0.035f + h * 0.03f
            return (if (gauche(coin)) dx else w - dx) to (if (haut(coin)) h * 0.05f else h * 0.95f)
        }

        /** La place du logo : l'œil et « Vision » à côté, vers l'intérieur de l'écran. */
        fun logo(coin: String, w: Float, h: Float, sortie: RectF) {
            val (cx, cy) = oeil(coin, w, h)
            val demiL = h * 0.03f; val texte = h * 0.13f
            if (gauche(coin)) sortie.set(cx - demiL, cy - h * 0.035f, cx + demiL + texte, cy + h * 0.035f)
            else sortie.set(cx - demiL - texte, cy - h * 0.035f, cx + demiL, cy + h * 0.035f)
        }

        /** Le bandeau prend le premier coin libre (ni le QR ni le logo), en haut à gauche d'abord. */
        fun coinBandeau(qr: String, logo: String): String =
            listOf(OptionsVeille.HG, OptionsVeille.HD, OptionsVeille.BG, OptionsVeille.BD).firstOrNull { it != qr && it != logo } ?: OptionsVeille.HG

        /** Ce qui occupe [coin] (rectangle à éviter), d'après les réglages ; null si rien. */
        fun occupant(coin: String, qr: String, logo: String, lecteur: Boolean, w: Float, h: Float): RectF? {
            val sortie = RectF()
            when {
                coin == qr -> qr(coin, w, h, sortie)
                coin == logo -> logo(coin, w, h, sortie)
                lecteur && coinBandeau(qr, logo) == coin -> bandeau(coin, w, h, sortie)
                else -> return null
            }
            return sortie
        }

        /** Le bord droit d'un élément posé à droite (demande en bas, petite horloge en haut), décalé s'il y a un occupant. */
        fun bordDroit(coin: String, qr: String, logo: String, lecteur: Boolean, w: Float, h: Float, defaut: Float): Float =
            occupant(coin, qr, logo, lecteur, w, h)?.let { minOf(defaut, it.left - w * 0.015f) } ?: defaut
    }

    /** Le bandeau : quand le montrer. Pur (essais JVM). */
    object Bandeau {
        /** Une minute après une pause, le bandeau reste (pour reprendre à la télécommande sans quitter la veille). */
        const val APRES_PAUSE_MS = 60_000L

        data class Joue(val etat: String, val titre: String, val permis: Boolean)

        fun visible(option: Boolean, j: Joue?, pauseDepuis: Long, maintenant: Long): Boolean {
            if (!option || j == null || !j.permis || j.titre.isEmpty()) return false
            return j.etat == "playing" || (j.etat == "paused" && pauseDepuis > 0L && maintenant - pauseDepuis < APRES_PAUSE_MS)
        }
    }
}
