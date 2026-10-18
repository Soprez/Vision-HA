// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * 1.91.2 (l'auteur : « on avait créé plusieurs types d'horloges, il fut un temps ») : tous les styles d'horloge de Vision,
 * au choix pour le widget Horloge de l'accueil (téléphone et télé) et pour la veille, réglés sur chaque appareil.
 *
 * - les huit styles dessinés par l'appli ([HorlogesVeille] : anneau, fine, grasse, analogique, classique, mots,
 *   palettes, empilée ; lot veille du 11-10, jamais livré) ;
 * - les horloges des thèmes de la page de veille (vision-veille-coeur.js, HORLOGES : une par thème, 28), identifiées
 *   « t-<clé> » ; dans l'appli elles tournent dans une petite page (assets/horloges), sur la page de veille de Home
 *   Assistant elles sont son paramètre « horloge ».
 *
 * Chaque style prend les couleurs du thème de l'appareil (encre, encre secondaire, accent, or, carte, fond) : un style
 * ne fixe que la forme. Défaut : l'anneau (l'horloge stylisée d'avant, inchangée). La veille suit l'accueil, sauf si un
 * style lui est donné.
 */
object StylesHorloge {
    /** Réglage de la veille : le même style que le widget Horloge de l'accueil. */
    const val SUIVRE_ACCUEIL = "accueil"
    const val PREFIXE_WEB = "t-"

    /** Les horloges des thèmes, dans l'ordre de la page de veille (HORLOGES de vision-veille-coeur.js). */
    val WEB = listOf("defaut", "sombre", "bleu-nuit", "beige", "sauge", "salon", "rose-poudre", "tactique", "briques", "corsaire",
        "royale", "circuit", "cockpit", "bourse", "ecrin", "prairie", "large", "sommet", "sous-bois", "code", "plume", "galaxie",
        "retro-85", "grimoire", "feerie", "banquise", "lagon", "etincelle")

    val NATIFS: List<String> get() = HorlogesVeille.STYLES
    val TOUS: List<String> = HorlogesVeille.STYLES + WEB.map { PREFIXE_WEB + it }

    fun estWeb(style: String): Boolean = style.startsWith(PREFIXE_WEB)
    fun cleWeb(style: String): String = style.removePrefix(PREFIXE_WEB)

    /** Un style lu dans les réglages : connu, ou un ancien nom (« chiffres », « aiguilles »), sinon l'anneau. */
    fun normaliser(s: String?): String = if (s != null && s in TOUS) s else HorlogesVeille.normaliser(s)

    /** Le nom affiché : celui du style, ou « Horloge <thème> » pour une horloge de thème. */
    fun nom(ctx: Context, style: String): String = when {
        style == SUIVRE_ACCUEIL -> ctx.getString(R.string.horloge_comme_accueil)
        estWeb(style) -> {
            val cle = cleWeb(style)
            val t = Themes.liste.firstOrNull { Themes.cle(it.nom) == cle }
            if (t == null) ctx.getString(R.string.horloge_vision_defaut) else ctx.getString(R.string.horloge_du_theme, Themes.libelle(t.nom))
        }
        else -> HorlogesVeille.nom(ctx, style)
    }

    // ------------------------------------------------------------------ vues

    /**
     * Le widget Horloge dans le style [style] : l'anneau garde sa vue d'avant (carrée, [HorlogeVision.Vue]) ; les autres
     * styles sont plus larges que hauts (comme sur l'écran de veille). [cote] : la hauteur en px.
     */
    fun vue(a: Context, theme: Theme, style: String, h24: Boolean): View = when {
        style == HorlogesVeille.ANNEAU -> HorlogeVision.Vue(a, theme, h24)
        estWeb(style) -> VueWeb(a, theme, cleWeb(style), date = false)
        else -> VueNative(a, theme, style, h24)
    }

    /** Largeur du widget pour une hauteur [cote] : carré pour les horloges rondes (anneau, cadrans), 16:10 sinon. */
    fun largeur(style: String, cote: Int): Int =
        if (style == HorlogesVeille.ANNEAU || style == HorlogesVeille.ANALOGIQUE || style == HorlogesVeille.CLASSIQUE) cote else cote * 16 / 10

    /** Une des huit horloges dessinées, vivante (une image par seconde, plus pendant une bascule ou une trotteuse). */
    @SuppressLint("ViewConstructor")
    class VueNative(ctx: Context, theme: Theme, style: String, private val h24: Boolean) : View(ctx) {
        private val pinceaux = HorlogesVeille.Pinceaux().also { it.h24 = h24 }
        private val couleurs = HorlogesVeille.Couleurs(theme.encre, theme.encre2, theme.pourpre, theme.or, theme.carte or 0xFF000000.toInt(), theme.fond or 0xFF000000.toInt())
        private val sansAnimation = OeilVue.animationsCoupees(ctx)
        private var derniereLue = ""
        var style: String = style
            set(v) { field = v; invalidate() }

        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES; tag = "horloge_vision" }

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val murale = System.currentTimeMillis()
            // Le dessin pense en écran de veille (date dessous) : ici l'horloge occupe le haut ; on la recentre.
            c.save(); c.translate(0f, h * decalage(style))
            // Les cadrans sont pensés pour un écran (date dessous) : dans le widget, ils prennent toute la hauteur.
            val agrandi = if (style == HorlogesVeille.ANALOGIQUE || style == HorlogesVeille.CLASSIQUE) 1.45f else 1f
            c.scale(agrandi, agrandi, w / 2, h * (0.5f - decalage(style)))
            HorlogesVeille.dessiner(c, style, w, h, pinceaux, couleurs, 1f, 1f, murale)
            c.restore()
            val lue = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(murale))
            if (lue != derniereLue) { derniereLue = lue; contentDescription = lue }
            if (isShown) {
                val vite = !sansAnimation && HorlogesVeille.bougeEnContinu(style, pinceaux, murale) && style != HorlogesVeille.ANNEAU
                postInvalidateDelayed(if (vite) 33L else 1005L - murale % 1000L)
            }
        }

        override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); if (visibility == VISIBLE) invalidate() }

        /** Le centre de chaque dessin est au-dessus du milieu (place de la date) : le décalage qui le ramène au milieu. */
        private fun decalage(style: String): Float = when (style) {
            HorlogesVeille.ANALOGIQUE, HorlogesVeille.CLASSIQUE -> 0.10f
            HorlogesVeille.MOTS, HorlogesVeille.FLIP -> 0.08f
            HorlogesVeille.FINE -> 0.06f
            HorlogesVeille.GRASSE -> 0.05f
            else -> 0f
        }
    }

    /**
     * Une horloge de thème (page assets/horloges/horloge.html), aux couleurs du thème de l'appareil, fond transparent.
     * Rien n'y est touchable : les gestes passent à l'accueil (appui long de la zone, défilement) ; elle ne prend
     * jamais le focus de la télécommande.
     */
    @SuppressLint("ViewConstructor", "SetJavaScriptEnabled")
    class VueWeb(ctx: Context, private val theme: Theme, cle: String, private val date: Boolean) : FrameLayout(ctx) {
        private val web = WebView(ctx).apply {
            setBackgroundColor(0)
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            // Pages d'horloge embarquées seulement : aucune navigation hors de assets/horloges n'est suivie.
            webViewClient = object : android.webkit.WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean =
                    !request.url.toString().startsWith("file:///android_asset/horloges/")
            }
            isFocusable = false; isFocusableInTouchMode = false
            isVerticalScrollBarEnabled = false; isHorizontalScrollBarEnabled = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        var cle: String = cle
            set(v) { if (v == field) return; field = v; charger() }

        init {
            addView(web, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            descendantFocusability = FOCUS_BLOCK_DESCENDANTS
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            tag = "horloge_vision"
            contentDescription = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
            charger()
        }

        private fun charger() {
            fun hex(c: Int) = String.format(Locale.ROOT, "%06X", c and 0xFFFFFF)
            val carte = theme.carte or 0xFF000000.toInt()
            val adresse = Uri.parse("file:///android_asset/horloges/horloge.html").buildUpon()
                .appendQueryParameter("cle", cle).appendQueryParameter("lang", Locale.getDefault().toLanguageTag())
                .appendQueryParameter("fond", hex(theme.fond)).appendQueryParameter("texte", hex(theme.encre))
                .appendQueryParameter("texte2", hex(theme.encre2)).appendQueryParameter("accent", hex(theme.pourpre))
                .appendQueryParameter("carte", hex(carte)).appendQueryParameter("ligne", hex(Palette.melanger(carte, theme.encre, 0.18f)))
                .appendQueryParameter("date", if (date) "1" else "0").build()
            web.loadUrl(adresse.toString())
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean = false

        override fun onWindowVisibilityChanged(visibility: Int) {
            super.onWindowVisibilityChanged(visibility)
            // Hors de l'écran, la page se met en pause (aucun calcul pour rien).
            if (visibility == VISIBLE) web.onResume() else web.onPause()
        }

        override fun onDetachedFromWindow() { super.onDetachedFromWindow(); web.stopLoading() }
    }

    // ------------------------------------------------------------------ le choix, avec aperçu

    private val main by lazy { Handler(Looper.getMainLooper()) }

    /**
     * Le choix du style : un grand aperçu (le style qui a le focus, la souris ou le dernier toucher), les huit styles de
     * Vision en vignettes animées, puis les horloges des thèmes. Télécommande : gauche / droite parcourent, OK garde ;
     * toucher : un premier appui montre le style en grand, un second le garde. [veille] : « Comme l'accueil » en tête.
     */
    fun choisir(act: Activity, courant: String, veille: Boolean, garder: (String) -> Unit) {
        val theme = Local.themeEffectif(act, Palette.LANCEUR)
        val dp = { v: Float -> Ui.dp(act, v) }
        val conf = act.resources.configuration
        val largeurApercu = dp(minOf(400f, (conf.screenWidthDp - 96).toFloat(), (conf.screenHeightDp - 360) * 16f / 9f).coerceAtLeast(200f))
        val hauteurApercu = largeurApercu * 9 / 16
        val cadreApercu = FrameLayout(act).apply { clipToOutline = true; background = Ui.fond(theme.fond or 0xFF000000.toInt(), dp(14f).toFloat()); tag = "horloge_apercu" }
        val nomApercu = Ui.sansCesure(TextView(act)).apply { textSize = 17f; setTextColor(theme.encre); gravity = Gravity.CENTER; setPadding(dp(16f), dp(8f), dp(16f), dp(2f)); tag = "horloge_apercu_nom" }
        var montre = ""
        val styleAccueil = ZoneHaut.reglagesHorloge(act).style
        val h24 = ZoneHaut.format24(act)
        val montrer = { style: String ->
            if (style != montre) {
                montre = style
                val reel = if (style == SUIVRE_ACCUEIL) styleAccueil else style
                cadreApercu.removeAllViews()
                val v: View = when {
                    estWeb(reel) -> VueWeb(act, theme, cleWeb(reel), date = true)
                    else -> VignetteHorloge(act, reel, theme)
                }
                cadreApercu.addView(v, FrameLayout.LayoutParams(largeurApercu, hauteurApercu))
                nomApercu.text = nom(act, style)
            }
        }
        val haut = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(16f), dp(8f), dp(16f), 0) }
        haut.addView(cadreApercu, LinearLayout.LayoutParams(largeurApercu, hauteurApercu))
        haut.addView(nomApercu, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val colonne = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        colonne.addView(haut)
        val boite = Ui.dialogue(act).setTitle(act.getString(if (veille) R.string.horloge_style_veille else R.string.horloge_style))
        boite.setView(ScrollView(act).apply { addView(colonne) }).setNegativeButton(act.getString(R.string.commun_lot2_annuler), null)
        val d = boite.create()
        var choisie: View? = null
        var premiere: View? = null

        fun cellule(style: String, vignette: View?): View {
            val c = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                isFocusable = true; isClickable = true; setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
                contentDescription = nom(act, style); tag = "style_$style"
            }
            if (vignette != null) {
                val cadre = FrameLayout(act).apply { clipToOutline = true; background = Ui.fond(theme.fond or 0xFF000000.toInt(), dp(10f).toFloat()) }
                cadre.addView(vignette, FrameLayout.LayoutParams(dp(128f), dp(72f)))
                c.addView(cadre, LinearLayout.LayoutParams(dp(128f), dp(72f)))
            }
            c.addView(Ui.sansCesure(TextView(act)).apply {
                text = nom(act, style) + if (style == courant) "  ✓" else ""
                textSize = 14f; setTextColor(if (style == courant) theme.or else theme.encre); gravity = Gravity.CENTER; setPadding(0, dp(6f), 0, 0)
                maxWidth = dp(128f)
                if (vignette == null) { minWidth = dp(112f); minHeight = dp(Ui.CIBLE_MIN); setPadding(dp(10f), dp(6f), dp(10f), dp(6f)); background = Ui.fond(theme.carte, dp(10f).toFloat()) }
            })
            Ui.anneauFocus(c, theme, 14f, agrandir = false) { a -> if (a) montrer(style) }
            c.setOnHoverListener { _, e -> if (e.action == MotionEvent.ACTION_HOVER_ENTER) montrer(style); false }
            // Télécommande (le focus a déjà montré le style) et souris : OK garde. Toucher : d'abord l'aperçu, puis garder.
            c.setOnClickListener { if (c.isInTouchMode && montre != style) montrer(style) else { d.dismiss(); garder(style) } }
            if (style == courant) choisie = c
            if (premiere == null) premiere = c
            return c
        }

        fun rangee(titre: String, styles: List<String>, vignettes: Boolean) {
            colonne.addView(Ui.sansCesure(TextView(act)).apply { text = titre; textSize = 15f; setTextColor(theme.encre2); setPadding(dp(24f), dp(12f), dp(24f), 0) })
            val r = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(16f), dp(6f), dp(16f), dp(4f)); clipChildren = false; clipToPadding = false }
            styles.forEach { s ->
                r.addView(cellule(s, if (vignettes) VignetteHorloge(act, if (s == SUIVRE_ACCUEIL) styleAccueil.takeUnless { estWeb(it) } ?: HorlogesVeille.ANNEAU else s, theme) else null),
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6f) })
            }
            colonne.addView(HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; clipChildren = false; addView(r) })
        }
        if (veille) rangee(act.getString(R.string.horloge_comme_accueil_titre), listOf(SUIVRE_ACCUEIL), vignettes = true)
        rangee(act.getString(R.string.horloge_styles_vision), NATIFS, vignettes = true)
        rangee(act.getString(R.string.horloge_horloges_themes), WEB.map { PREFIXE_WEB + it }, vignettes = false)
        colonne.addView(Ui.sansCesure(TextView(act)).apply { text = act.getString(R.string.horloge_style_aide); textSize = 14f; setTextColor(theme.encre3); setPadding(dp(24f), dp(8f), dp(24f), dp(8f)) })
        montrer(courant)
        d.show()
        main.post { (choisie ?: premiere)?.requestFocus() }
    }
}
