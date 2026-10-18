// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * La charte de Vision, sans bibliothèque : la même que l'écran de veille et
 * le lanceur (thème de l'appareil et couleurs à la carte), cartes arrondies,
 * polices Figtree et Outfit, du vert pour ce qui va et du rouge pour ce qui
 * ferme. Tout est construit en code pour rester une seule petite APK,
 * téléphone comme TV. Chaque bouton garde un état « focus » visible pour la
 * télécommande.
 */
object Ui {
    /**
     * 1.86.1 (retour d'essai, Party à la télé) : le focus de la télécommande, identique et très visible partout.
     * Un double anneau posé PAR-DESSUS la vue (encre du thème à l'extérieur, fond du thème à l'intérieur) : l'un des
     * deux tranche toujours à plus de 3:1 sur n'importe quel fond ou bouton. Il ne se confond jamais avec l'état
     * « sélectionné », qui se dit par le fond de la vue. [agrandir] : léger agrandissement (boutons, onglets ; pas les
     * lignes pleine largeur, qui déborderaient).
     */
    fun anneauFocus(v: android.view.View, theme: Theme, rayonDp: Float, agrandir: Boolean = true, apres: ((Boolean) -> Unit)? = null) {
        val ctx = v.context
        fun anneau(): android.graphics.drawable.Drawable {
            val r = dp(ctx, rayonDp).toFloat()
            val dehors = android.graphics.drawable.GradientDrawable().apply { cornerRadius = r; setColor(0); setStroke(dp(ctx, 3f), theme.encre or 0xFF000000.toInt()) }
            val dedans = android.graphics.drawable.GradientDrawable().apply { cornerRadius = (r - dp(ctx, 3f)).coerceAtLeast(0f); setColor(0); setStroke(dp(ctx, 2f), theme.fond or 0xFF000000.toInt()) }
            return android.graphics.drawable.LayerDrawable(arrayOf(dehors, dedans)).apply { setLayerInset(1, dp(ctx, 3f), dp(ctx, 3f), dp(ctx, 3f), dp(ctx, 3f)) }
        }
        v.isFocusable = true
        v.setOnFocusChangeListener { x, a ->
            // 1.86.2 (l'auteur : bordures coupées) : rien autour ne rogne l'anneau agrandi ; une liste défile avec de la marge.
            if (a) { sansCoupe(x); x.post { garderVisible(x, dp(ctx, 14f)) } }
            if (android.os.Build.VERSION.SDK_INT >= 23) x.foreground = if (a) anneau() else null
            x.animate().scaleX(if (a && agrandir) 1.05f else 1f).scaleY(if (a && agrandir) 1.05f else 1f).translationZ(if (a) dp(ctx, 6f).toFloat() else 0f).setDuration(110).start()
            apres?.invoke(a)
        }
    }

    /**
     * 1.86.2 (l'auteur : anneaux, cartes et pastilles coupés) : les parents d'une vue focalisable ne la rognent plus (jusqu'à
     * la liste qui défile, qui elle garde son cadre : c'est [garderVisible] qui laisse de la marge autour).
     */
    fun sansCoupe(v: View) {
        var p = v.parent
        while (p is android.view.ViewGroup) {
            if (defile(p)) break
            p.clipChildren = false; p.clipToPadding = false
            // 1.86.4 (photo de l'auteur : la file passait sous l'aide et sous le bord de l'écran) : une liste qui défile ne
            // compte plus sur son parent pour la rogner — elle garde toujours son propre cadre.
            for (i in 0 until p.childCount) p.getChildAt(i).let { if (defile(it)) cadre(it) }
            p = p.parent
        }
    }

    private fun defile(v: Any?) = v is android.widget.ScrollView || v is android.widget.HorizontalScrollView || v is android.widget.AbsListView

    /** La vue dessine son contenu dans son propre cadre, quoi que fasse son parent. */
    fun cadre(v: View) {
        v.outlineProvider = android.view.ViewOutlineProvider.BOUNDS
        v.clipToOutline = true
    }

    /** Dans une liste qui défile : la vue focalisée, agrandie et avec son anneau, reste entière à l'écran (+ [marge]). */
    fun garderVisible(v: View, marge: Int) {
        var p = v.parent
        while (p is View && p !is android.widget.ScrollView) p = p.parent
        val sv = p as? android.widget.ScrollView ?: return
        if (!v.isAttachedToWindow) return
        val r = android.graphics.Rect(); v.getDrawingRect(r)
        sv.offsetDescendantRectToMyCoords(v, r)
        val extra = (v.height * 0.03f).toInt() + marge
        val haut = sv.scrollY + sv.paddingTop; val bas = sv.scrollY + sv.height - sv.paddingBottom
        when {
            r.top - extra < haut -> sv.smoothScrollTo(0, (r.top - extra - sv.paddingTop).coerceAtLeast(0))
            r.bottom + extra > bas -> sv.smoothScrollTo(0, r.bottom + extra - sv.height + sv.paddingBottom)
        }
    }

    /**
     * 1.82.1 (glitchs, règle du projet : une mise à jour ne fait jamais clignoter l'écran) : ce qu'une vue montre, en
     * texte (classe, textes, descriptions, visibilité, enfants). Deux vues de même empreinte s'affichent pareil :
     * inutile de remplacer l'une par l'autre.
     */
    fun empreinteVue(v: View): String = StringBuilder().also { empreinte(v, it) }.toString()

    private fun empreinte(v: View, sb: StringBuilder) {
        sb.append(v.javaClass.simpleName).append('|').append(v.visibility).append('|').append(v.contentDescription ?: "")
        if (v is android.widget.TextView) sb.append('|').append(v.text)
        if (v is android.view.ViewGroup) { sb.append('['); for (i in 0 until v.childCount) { empreinte(v.getChildAt(i), sb); sb.append(';') }; sb.append(']') }
    }

    // Palette courante : dérivée du thème de l'appareil (Beige, Sombre, Bleu nuit, Sauge, Rose poudré)
    // et des couleurs à la carte, les mêmes que l'écran de veille et le lanceur.
    // Des `var` pour pouvoir changer de thème sans relancer l'application.
    /** Lot 1 (A7, APP-21) : la plus petite cible touchable ou focalisable (WCAG 2.5.5, Android : 48 dp). */
    const val CIBLE_MIN = 48f

    var FOND = 0xFFEFE6D6.toInt()
    var CARTE = 0xFFFBF6EC.toInt()
    var CARTE_HAUTE = 0xFFF3ECDF.toInt()
    var LIGNE = 0x558C2F4B
    var OR = 0xFF8F6110.toInt()
    var OR_SOMBRE = 0xFFFFFFFF.toInt()   // l'encre posée sur l'or (blanc sur or sombre, nuit sur or clair)
    var POURPRE = 0xFF8C2F4B.toInt()
    var VERT = 0xFF2F8A5E.toInt()
    var ROUGE = 0xFFC0392B.toInt()
    var BLEU = 0xFF3E7CC4.toInt()
    var TEXTE = 0xFF2A2026.toInt()
    var TEXTE_2 = 0xFF6E5A5E.toInt()
    var TEXTE_3 = 0xFF8F7D80.toInt()
    var SOMBRE = false
    private var police: Typeface? = null
    private var policeGras: Typeface? = null
    private var policeTitre: Typeface? = null

    /** Applique le thème de l'appareil (celui de l'écran de veille et du lanceur). À appeler au début de chaque écran. */
    fun charger(ctx: Context) {
        appliquer(Local.themeEffectif(ctx, Palette.APPLI))
        police = Polices.texte(ctx); policeGras = Polices.demiGras(ctx); policeTitre = Polices.outfit(ctx)
        val w = (ctx as? android.app.Activity)?.window ?: return
        w.statusBarColor = FOND; w.navigationBarColor = FOND
        if (Build.VERSION.SDK_INT >= 23) {
            var f = w.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            f = if (SOMBRE) f and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() else f or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (Build.VERSION.SDK_INT >= 26) f = if (SOMBRE) f and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv() else f or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            w.decorView.systemUiVisibility = f
        }
    }

    fun appliquer(t: Theme) {
        SOMBRE = t.sombre
        FOND = t.fond; CARTE = t.carte; TEXTE = t.encre; TEXTE_2 = t.encre2; TEXTE_3 = t.encre3
        OR = t.or; POURPRE = t.pourpre
        CARTE_HAUTE = Palette.melanger(CARTE, TEXTE, 0.07f)
        LIGNE = Palette.melanger(CARTE, TEXTE, 0.16f)
        OR_SOMBRE = if (Palette.luminance(OR) > 0.55f) Palette.melanger(FOND, 0xFF000000.toInt(), 0.75f) else 0xFFFFFFFF.toInt()
        if (t.sombre) { VERT = 0xFF4CC38A.toInt(); ROUGE = 0xFFFF6B6B.toInt(); BLEU = 0xFF7FB8FF.toInt() }
        else { VERT = 0xFF2F8A5E.toInt(); ROUGE = 0xFFB8322A.toInt(); BLEU = 0xFF3E7CC4.toInt() }
    }

    /** Aucun contour ni agrandissement de focus ne doit être coupé : tous les conteneurs laissent déborder. */
    fun laisserDeborder(v: View) {
        if (v is ViewGroup) {
            // 1.72.0 : sauf les conteneurs marqués « garder coupé » (la grille qui défile au-dessus du dock).
            if (v !in coupes) { v.clipChildren = false; v.clipToPadding = false }
            for (i in 0 until v.childCount) laisserDeborder(v.getChildAt(i))
        }
    }

    private val coupes = java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>())

    /** Ce conteneur coupe ce qui dépasse de ses bords, même sous [laisserDeborder]. */
    fun garderCoupe(v: ViewGroup): ViewGroup = v.apply { clipChildren = true; coupes.add(this) }

    fun dp(ctx: Context, v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()

    fun fond(couleur: Int, rayon: Float, bord: Int = 0, couleurBord: Int = LIGNE): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = rayon
            setColor(couleur)
            if (bord > 0) setStroke(bord, couleurBord)
        }

    /** Fond avec un état « focus » net pour la télécommande, et une ondulation au toucher. */
    fun fondInteractif(ctx: Context, normal: Int, focus: Int, rayon: Float, bordFocus: Int = OR, bord: Int = 0): android.graphics.drawable.Drawable {
        val etats = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), fond(focus, rayon, dp(ctx, 2f), bordFocus))
            addState(intArrayOf(android.R.attr.state_pressed), fond(focus, rayon, if (bord != 0) dp(ctx, 1f) else 0, bord))
            addState(intArrayOf(), fond(normal, rayon, if (bord != 0) dp(ctx, 1f) else 0, bord))
        }
        return if (Build.VERSION.SDK_INT >= 21)
            RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), etats, fond(Color.WHITE, rayon))
        else etats
    }

    /** La marque Vision, au milieu en haut, partout dans l'appli. */
    fun marque(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        gravity = Gravity.CENTER
        addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_vision) }, LinearLayout.LayoutParams(dp(ctx, 20f), dp(ctx, 20f)))
        addView(TextView(ctx).apply { text = "VISION"; textSize = 12f; setTextColor(OR); typeface = Polices.gras(ctx); letterSpacing = 0.28f; setPadding(dp(ctx, 8f), 0, 0, 0); sansCesure(this) })
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 16f) }
    }

    fun colonne(ctx: Context, pad: Int = 0): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad, pad, pad, pad)
    }

    fun rangee(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun carte(ctx: Context, couleur: Int = CARTE): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = fond(couleur, dp(ctx, 22f).toFloat(), dp(ctx, 1f), LIGNE)
        val p = dp(ctx, 18f)
        setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(ctx, 14f) }
    }

    fun texte(ctx: Context, t: CharSequence, taille: Float = 15f, couleur: Int = TEXTE,
              gras: Boolean = false, centre: Boolean = false): TextView = TextView(ctx).apply {
        text = if (t is String) coupable(t) else t; textSize = taille; setTextColor(couleur)
        typeface = if (gras) (policeGras ?: Typeface.DEFAULT_BOLD) else (police ?: Typeface.DEFAULT)
        if (centre) gravity = Gravity.CENTER
        setLineSpacing(0f, 1.15f)
        sansCesure(this)
    }

    fun titre(ctx: Context, t: String): TextView = texte(ctx, t, 26f, TEXTE, gras = true).apply { typeface = policeTitre ?: typeface }

    fun sousTitre(ctx: Context, t: String): TextView = texte(ctx, t, 14f, TEXTE_2)

    /** Intitulé de section, en petites capitales dorées. */
    fun section(ctx: Context, t: String): TextView = texte(ctx, t.uppercase(), 12f, OR, gras = true).apply {
        letterSpacing = 0.18f
        setTextColor(TEXTE_2)
        setPadding(dp(ctx, 4f), dp(ctx, 10f), 0, dp(ctx, 10f))
    }

    /** Petite pastille colorée avec un texte court : « Ouvert », « Fermé »… */
    fun chip(ctx: Context, t: String, couleur: Int): TextView = TextView(ctx).apply {
        text = t; textSize = 12f; setTextColor(couleur)
        typeface = policeGras ?: Typeface.DEFAULT_BOLD
        background = fond((couleur and 0x00FFFFFF) or 0x26000000, dp(ctx, 999f).toFloat(), dp(ctx, 1f), (couleur and 0x00FFFFFF) or 0x66000000)
        setPadding(dp(ctx, 12f), dp(ctx, 5f), dp(ctx, 12f), dp(ctx, 5f))
        sansCesure(this)
    }

    /** Rond d'état : vert coché, ou gris vide. */
    fun pastille(ctx: Context, ok: Boolean): TextView = TextView(ctx).apply {
        val d = dp(ctx, 28f)
        layoutParams = LinearLayout.LayoutParams(d, d).apply { rightMargin = dp(ctx, 14f) }
        gravity = Gravity.CENTER
        text = if (ok) "✓" else ""
        // La coche est un pictogramme dans un rond de taille fixe : elle ne suit pas la taille du texte.
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f); includeFontPadding = false
        // 17-10 (audit des couleurs, point 19) : la coche s'accorde au vert du thème (clair sur les thèmes sombres : une
        // coche blanche y était presque invisible), comme l'encre des boutons rouges.
        setTextColor(if (Palette.luminance(VERT) > 0.45f) Palette.melanger(VERT, 0xFF000000.toInt(), 0.82f) else Color.WHITE)
        typeface = policeGras ?: Typeface.DEFAULT_BOLD
        background = if (ok) fond(VERT, d / 2f) else fond(Color.TRANSPARENT, d / 2f, dp(ctx, 2f), TEXTE_3)
    }

    fun icone(ctx: Context, res: Int, taille: Float, teinte: Int = OR): ImageView = ImageView(ctx).apply {
        setImageResource(res)
        val d = dp(ctx, taille)
        layoutParams = LinearLayout.LayoutParams(d, d)
        if (Build.VERSION.SDK_INT >= 21) imageTintList = ColorStateList.valueOf(teinte)
    }

    fun boutonPrimaire(ctx: Context, t: String, action: () -> Unit): Button = Button(ctx).apply {
        text = coupable(t); isAllCaps = false; textSize = 15f; minWidth = 0; minimumWidth = 0
        setTextColor(OR_SOMBRE); typeface = policeGras ?: Typeface.DEFAULT_BOLD
        background = fondInteractif(ctx, OR, Palette.melanger(OR, TEXTE, 0.15f), dp(ctx, 999f).toFloat(), TEXTE)
        stateListAnimator = null
        minHeight = dp(ctx, CIBLE_MIN); minimumHeight = dp(ctx, CIBLE_MIN)
        setPadding(dp(ctx, 14f), dp(ctx, 8f), dp(ctx, 14f), dp(ctx, 8f))
        sansCesure(this)
        isFocusable = true
        setOnClickListener { Geste.retenir(this); action() }
    }

    fun boutonSecondaire(ctx: Context, t: String, action: () -> Unit): Button = Button(ctx).apply {
        text = coupable(t); isAllCaps = false; textSize = 14f; minWidth = 0; minimumWidth = 0
        setTextColor(TEXTE); typeface = policeGras ?: Typeface.DEFAULT_BOLD
        // Un liseré : posé sur une carte haute (même gris), le bouton reste un bouton et non du texte.
        background = fondInteractif(ctx, CARTE_HAUTE, Palette.melanger(CARTE_HAUTE, TEXTE, 0.1f), dp(ctx, 999f).toFloat(), bord = Palette.melanger(CARTE_HAUTE, TEXTE, 0.28f))
        stateListAnimator = null
        minHeight = dp(ctx, CIBLE_MIN); minimumHeight = dp(ctx, CIBLE_MIN)
        setPadding(dp(ctx, 14f), dp(ctx, 8f), dp(ctx, 14f), dp(ctx, 8f))
        sansCesure(this)
        isFocusable = true
        setOnClickListener { Geste.retenir(this); action() }
    }

    /**
     * 1.71.0 : un gros bouton de l'écran principal, sur deux lignes : l'action en gras, puis un détail plus petit
     * (« 2 à traiter », « 5 ouverts · 1 fermé »). Coins arrondis plutôt qu'une pilule : le texte peut
     * passer sur plusieurs lignes en grands caractères sans être coupé. Navigable à la télécommande.
     */
    fun tuile(ctx: Context, titre: String, detail: String, primaire: Boolean, action: () -> Unit): Button = Button(ctx).apply {
        val encre = if (primaire) OR_SOMBRE else TEXTE
        val t = android.text.SpannableStringBuilder(titre)
        if (detail.isNotEmpty()) {
            val debut = t.length
            t.append('\n').append(detail)
            t.setSpan(android.text.style.RelativeSizeSpan(0.82f), debut, t.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            t.setSpan(android.text.style.ForegroundColorSpan(if (primaire) encre else TEXTE_2), debut, t.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (Build.VERSION.SDK_INT >= 28) t.setSpan(android.text.style.TypefaceSpan(police ?: Typeface.DEFAULT), debut, t.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        text = t; isAllCaps = false; textSize = 16f; minWidth = 0; minimumWidth = 0
        setTextColor(encre); typeface = policeGras ?: Typeface.DEFAULT_BOLD
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setLineSpacing(0f, 1.1f)
        val fondTuile = if (primaire) OR else CARTE_HAUTE
        background = fondInteractif(ctx, fondTuile, Palette.melanger(fondTuile, TEXTE, if (primaire) 0.15f else 0.1f), dp(ctx, 18f).toFloat(),
            if (primaire) TEXTE else OR, if (primaire) 0 else Palette.melanger(CARTE_HAUTE, TEXTE, 0.28f))
        stateListAnimator = null
        minHeight = dp(ctx, 72f); minimumHeight = dp(ctx, 72f)
        setPadding(dp(ctx, 16f), dp(ctx, 12f), dp(ctx, 16f), dp(ctx, 12f))
        sansCesure(this)
        isFocusable = true
        setOnClickListener { Geste.retenir(this); action() }
    }

    /**
     * Bouton de refus ou de retrait : un vrai bouton plein, rouge du thème, jamais confondu avec du texte
     * ni avec sa carte (le gris des boutons secondaires est celui des cartes hautes). L'encre suit la
     * clarté du rouge : blanche sur le rouge sombre des thèmes clairs, foncée sur le rouge vif des sombres.
     */
    fun boutonDanger(ctx: Context, t: String, action: () -> Unit): Button = Button(ctx).apply {
        text = t; isAllCaps = false; textSize = 14f; minWidth = 0; minimumWidth = 0
        setTextColor(if (Palette.luminance(ROUGE) > 0.45f) Palette.melanger(ROUGE, 0xFF000000.toInt(), 0.82f) else 0xFFFFFFFF.toInt())
        typeface = policeGras ?: Typeface.DEFAULT_BOLD
        background = fondInteractif(ctx, ROUGE, Palette.melanger(ROUGE, TEXTE, 0.18f), dp(ctx, 999f).toFloat(), TEXTE)
        stateListAnimator = null
        minHeight = dp(ctx, CIBLE_MIN); minimumHeight = dp(ctx, CIBLE_MIN)
        setPadding(dp(ctx, 14f), dp(ctx, 8f), dp(ctx, 14f), dp(ctx, 8f))
        sansCesure(this)
        isFocusable = true
        setOnClickListener { Geste.retenir(this); action() }
    }

    fun champ(ctx: Context, indice: String, valeur: String, chiffres: Boolean = false): EditText = EditText(ctx).also { ClavierTele.brancher(it) }.apply {
        hint = indice; setText(valeur)
        setTextColor(TEXTE); setHintTextColor(TEXTE_3); textSize = 15f; typeface = police ?: typeface
        background = fondChamp(ctx, FOND, TEXTE, LIGNE)
        val p = dp(ctx, 14f); setPadding(p, p, p, p)
        inputType = if (chiffres)
            android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        else android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(ctx, 8f) }
    }

    /**
     * Lot 1 (A7, APP-13) : un champ de saisie montre son focus (télécommande, clavier) : bord épais à l'encre du thème
     * (contraste du texte sur le fond, bien au-delà de 3:1) au lieu du seul curseur de 2 px.
     */
    fun fondChamp(ctx: Context, fondCouleur: Int, encre: Int, bord: Int): android.graphics.drawable.Drawable = StateListDrawable().apply {
        val r = dp(ctx, 14f).toFloat()
        addState(intArrayOf(android.R.attr.state_focused), fond(fondCouleur, r, dp(ctx, 3f), encre))
        addState(intArrayOf(), fond(fondCouleur, r, dp(ctx, 1f), bord))
    }

    /**
     * Recette visuelle (lot 1) : un nom long sans espace (domaine, nom de paquet, adresse) passe à la ligne à ses
     * séparateurs (. - _ / : @) au lieu d'être coupé au milieu d'un mot : une espace sans chasse (U+200B, invisible,
     * ignorée par la lecture vocale) est posée après chacun. Les mots ordinaires ne changent pas.
     */
    fun coupable(s: String): String = Coupure.douce(s)


    /**
     * Règle de lecture de Vision : un texte passe à la ligne entre deux mots, jamais au milieu d'un
     * mot (pas de césure automatique), et n'est jamais coupé par des points de suspension.
     * Posée par tous les fabricants de textes et de boutons (Ui, lanceur, écrans TV, boîtes).
     */
    /** Lot 6 (B8 / APP-29) : une colonne de gestion centrée, de largeur de lecture limitée sur tablette. */
    fun largeurLecture(v: View) {
        val c = dp(v.context, Marges.coteLecture(v.resources.configuration.screenWidthDp))
        v.setPadding(c, v.paddingTop, c, v.paddingBottom)
    }

    fun <T : TextView> sansCesure(v: T): T = v.apply {
        if (Build.VERSION.SDK_INT >= 23) {
            hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
            breakStrategy = android.text.Layout.BREAK_STRATEGY_SIMPLE
        }
        // Lot 6 (B9 / APP-22) : les émojis décoratifs (🔒 ✅ ✉ ☕…) ne sont pas lus ; posé ici, chez tous les fabricants.
        setAccessibilityDelegate(LectureSansEmojis)
    }

    /** Lot 6 (APP-22) : le lecteur d'écran dit le texte sans ses émojis décoratifs (sauf un texte fait d'émojis seuls). */
    private object LectureSansEmojis : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            if (!host.contentDescription.isNullOrEmpty() || host !is TextView) return
            val t = host.text?.toString() ?: return
            val lu = Lisibilite.sansEmojis(t)
            if (lu != t && lu.isNotEmpty()) info.contentDescription = lu
        }
    }

    /**
     * Une rangée de boutons côte à côte qui s'empile d'elle-même (un bouton par ligne, pleine
     * largeur) quand leurs libellés, sur une ligne, ne tiennent pas dans la largeur : grands
     * caractères, petit écran, portrait. Elle se remet en rangée dès que la place revient.
     */
    fun rangeeBoutons(ctx: Context): LinearLayout = RangeeBoutons(ctx)

    private class RangeeBoutons(ctx: Context) : LinearLayout(ctx) {
        private class Origine(val largeur: Int, val poids: Float, val gauche: Int, val haut: Int)
        private val origines = java.util.WeakHashMap<View, Origine>()
        private val ecart = dp(ctx, 8f)
        // La marge haute commune des boutons, portée par la rangée elle-même quand ils sont côte à côte (voir onMeasure).
        private var hautPorte = 0
        init { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

        override fun onMeasure(spec: Int, hauteurSpec: Int) {
            var largeurSpec = spec
            if (MeasureSpec.getMode(largeurSpec) != MeasureSpec.UNSPECIFIED) {
                val dispo = MeasureSpec.getSize(largeurSpec) - paddingLeft - paddingRight
                val libre = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                var total = 0; var fixe = 0; var poidsTotal = 0f
                for (i in 0 until childCount) {
                    val c = getChildAt(i); if (c.visibility == GONE) continue
                    val lp = c.layoutParams as? LayoutParams ?: continue
                    val o = origines.getOrPut(c) { Origine(lp.width, lp.weight, lp.leftMargin, lp.topMargin) }
                    c.measure(libre, libre)
                    total += c.measuredWidth + o.gauche + lp.rightMargin
                    fixe += o.gauche + lp.rightMargin + if (o.poids > 0f && o.largeur == 0) 0 else c.measuredWidth
                    if (o.poids > 0f && o.largeur == 0) poidsTotal += o.poids
                }
                // Avec des poids, chaque bouton n'a que sa part : il faut que son libellé y tienne aussi.
                val partTropPetite = poidsTotal > 0f && (0 until childCount).any { i ->
                    val c = getChildAt(i); val o = origines[c]
                    c.visibility != GONE && o != null && o.poids > 0f && o.largeur == 0 &&
                        c.measuredWidth > (dispo - fixe) * o.poids / poidsTotal
                }
                val empiler = total > dispo || partTropPetite
                var premier = true
                // Côte à côte et centrés verticalement, des boutons qui ont une marge haute descendaient d'une
                // demi-marge de trop (LinearLayout centre dans une hauteur qui compte déjà la marge, puis l'ajoute) :
                // le bas des boutons dépassait de la rangée et ses coins arrondis étaient rognés. La marge haute
                // commune passe donc dans la marge intérieure de la rangée ; chaque bouton ne garde que son surplus.
                val commun = if (empiler) 0 else (0 until childCount).mapNotNull { i ->
                    val c = getChildAt(i); if (c.visibility == GONE) null else origines[c]?.haut
                }.minOrNull() ?: 0
                if (commun != hautPorte) { setPadding(paddingLeft, paddingTop - hautPorte + commun, paddingRight, paddingBottom); hautPorte = commun }
                for (i in 0 until childCount) {
                    val c = getChildAt(i); if (c.visibility == GONE) continue
                    val lp = c.layoutParams as? LayoutParams ?: continue
                    val o = origines[c] ?: continue
                    if (empiler) {
                        lp.width = ViewGroup.LayoutParams.MATCH_PARENT; lp.weight = 0f; lp.leftMargin = 0
                        lp.topMargin = o.haut + if (premier) 0 else maxOf(o.gauche, ecart)
                    } else {
                        lp.width = o.largeur; lp.weight = o.poids; lp.leftMargin = o.gauche; lp.topMargin = o.haut - commun
                    }
                    premier = false
                }
                orientation = if (empiler) VERTICAL else HORIZONTAL
                // Boutons à poids ou empilés : la rangée prend toute la largeur offerte (même posée en WRAP_CONTENT),
                // sinon les parts sont calculées sur une largeur trop petite.
                if (empiler || poidsTotal > 0f) largeurSpec = MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(spec), MeasureSpec.EXACTLY)
            }
            super.onMeasure(largeurSpec, hauteurSpec)
        }
    }

    /**
     * Vrai quand la largeur, ramenée à la taille du texte, manque pour un texte et un bouton
     * côte à côte (petit écran, portrait, grands caractères) : le bouton passe alors dessous.
     */
    fun etroit(ctx: Context): Boolean {
        val c = ctx.resources.configuration
        return c.screenWidthDp / c.fontScale.coerceAtLeast(1f) < 400f
    }

    /** Le thème actif de l'appareil, relu à chaque appel : une boîte ouverte après un changement de thème le suit. */
    fun themeActif(ctx: Context): Theme = Local.themeEffectif(ctx, Palette.APPLI)

    /**
     * La boîte de dialogue de Vision : à employer à la place de `AlertDialog.Builder(ctx)`.
     * Base claire ou sombre selon le thème actif, puis, à l'affichage, fond de carte arrondi,
     * encres, accent (boutons, coches, champs) et polices du thème ; titres, messages, lignes
     * de liste et boutons passent à la ligne au lieu d'être coupés.
     */
    fun dialogue(ctx: Context): android.app.AlertDialog.Builder = DialogueVision(ctx, themeActif(ctx))

    private class DialogueVision(ctx: Context, private val th: Theme) : android.app.AlertDialog.Builder(
        ctx, if (th.sombre) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert) {
        override fun create(): android.app.AlertDialog {
            val d = super.create()
            d.setOnShowListener { habiller(d, th) }
            return d
        }
    }

    /** Applique le thème `th` à une boîte déjà affichée (fond, textes, boutons, liste, champs). */
    fun habiller(d: android.app.AlertDialog, th: Theme) {
        val ctx = d.context
        d.window?.setBackgroundDrawable(android.graphics.drawable.InsetDrawable(
            fond(th.carte, dp(ctx, 22f).toFloat(), dp(ctx, 1f), th.carteBord), dp(ctx, 16f)))
        val decor = d.window?.decorView ?: return
        habillerVue(decor, th)
        d.listView?.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent: View?, child: View?) { if (child != null) habillerVue(child, th) }
            override fun onChildViewRemoved(parent: View?, child: View?) {}
        })
        for (b in intArrayOf(android.content.DialogInterface.BUTTON_POSITIVE, android.content.DialogInterface.BUTTON_NEGATIVE, android.content.DialogInterface.BUTTON_NEUTRAL))
            d.getButton(b)?.let { boutonDeDialogue(it, th) }
        // Lot 1 (A7, APP-14) : une ligne de liste au focus se voit (fond teinté, bord à l'encre) ; ▼ depuis la dernière
        // ligne va au bouton de validation (avant : « Annuler », à droite).
        d.listView?.let { lv ->
            lv.selector = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), fond(Palette.melanger(th.carte, th.or, 0.22f), dp(ctx, 10f).toFloat(), dp(ctx, 3f), th.encre))
                addState(intArrayOf(android.R.attr.state_pressed), fond(Palette.melanger(th.carte, th.or, 0.22f), dp(ctx, 10f).toFloat()))
                addState(intArrayOf(), android.graphics.drawable.ColorDrawable(0))
            }
            lv.isFocusable = true
            d.getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.takeIf { it.visibility == View.VISIBLE }?.let { lv.nextFocusDownId = it.id }
        }
        decor.requestLayout()
    }

    /**
     * Lot 1 (A7, APP-14) : bouton de boîte de dialogue. Au repos : texte à l'accent ; au focus : fond de carte plus
     * marqué, texte à l'encre et bord épais à l'encre (contraste de l'encre sur la carte, ≥ 4,5:1), comme le double
     * anneau de Party ; avant, le focus le peignait en blanc avec un texte doré (2,46:1). Cible de 48 dp.
     */
    fun boutonDeDialogue(b: Button, th: Theme) {
        val ctx = b.context
        b.isAllCaps = false; b.typeface = Polices.gras(ctx)
        b.setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(th.encre, th.or)))
        val r = dp(ctx, 999f).toFloat()
        b.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), fond(Palette.melanger(th.carte, th.encre, 0.12f), r, dp(ctx, 3f), th.encre))
            addState(intArrayOf(android.R.attr.state_pressed), fond(Palette.melanger(th.carte, th.encre, 0.12f), r))
            addState(intArrayOf(), android.graphics.drawable.ColorDrawable(0))
        }
        b.stateListAnimator = null
        b.minHeight = dp(ctx, CIBLE_MIN); b.minimumHeight = dp(ctx, CIBLE_MIN)
        b.setPadding(dp(ctx, 16f), b.paddingTop, dp(ctx, 16f), b.paddingBottom)
    }

    private fun habillerVue(v: View, th: Theme) {
        val ctx = v.context
        when (v) {
            is EditText -> {
                v.setTextColor(th.encre); v.setHintTextColor(th.encre3)
                if (v.background is GradientDrawable || v.background is StateListDrawable) v.background = fondChamp(ctx, th.fond, th.encre, th.carteBord)
                else if (Build.VERSION.SDK_INT >= 21) v.backgroundTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(th.encre, th.or))
            }
            is TextView -> {
                sansCesure(v)
                // Aucun texte de boîte coupé : titre (une seule ligne par défaut), message, lignes de liste, boutons.
                v.isSingleLine = false; v.maxLines = Int.MAX_VALUE; v.ellipsize = null
                if (v is Button) { if (v.background !is StateListDrawable) { v.isAllCaps = false; v.setTextColor(th.or); v.typeface = Polices.gras(ctx) } }
                else {
                    val c = v.currentTextColor
                    // Les textes de Vision gardent leur rôle (encre, encre secondaire, accent, alerte) dans le thème de la boîte.
                    v.setTextColor(when (c) {
                        TEXTE_2, th.encre2 -> th.encre2; TEXTE_3, th.encre3 -> th.encre3; OR, th.or -> th.or
                        ROUGE, VERT, BLEU, th.pourpre -> c
                        else -> th.encre
                    })
                    val idTitre = ctx.resources.getIdentifier("alertTitle", "id", "android")
                    if (v.id != 0 && v.id == idTitre) {
                        v.typeface = Polices.demiGras(ctx)
                        // Recette lot 1 : un mot plus large que la boîte (domaine, paquet) fait rapetisser le titre, jamais couper le mot.
                        val sp = v.textSize / ctx.resources.displayMetrics.scaledDensity
                        motsEntiers(v, sp, sp * 0.6f)
                    }
                    else if (v.typeface == null || v.typeface == Typeface.DEFAULT) v.typeface = Polices.texte(ctx)
                }
                if (Build.VERSION.SDK_INT >= 21) {
                    if (v is android.widget.CheckedTextView) v.checkMarkTintList = ColorStateList.valueOf(th.or)
                    if (v is android.widget.CompoundButton) v.buttonTintList = ColorStateList.valueOf(th.or)
                }
            }
            is android.widget.NumberPicker -> habillerSelecteur(v, th)
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) habillerVue(v.getChildAt(i), th)
    }

    // ------------------------------------------------------------------ Composants natifs aux couleurs du thème
    // Lot Accueil (point 6) : interrupteur, glissière, case à cocher et sélecteur de nombre ne gardent jamais le style
    // Android par défaut (vert ou bleu de la plateforme, séparateurs gris) : tout suit le thème de l'appareil, y compris
    // l'état désactivé et le focus de la télécommande. Ils ne se créent qu'ici (ComposantsThemesTest le vérifie).

    private val DESACTIVE = intArrayOf(-android.R.attr.state_enabled)
    private val COCHE = intArrayOf(android.R.attr.state_checked)

    /**
     * Un interrupteur (Switch) du thème [th] : pouce et piste à l'[accent] quand il est coché, à l'encre secondaire
     * sur une piste grise du thème sinon, pâlis quand il est désactivé ; anneau de focus de Vision à la télécommande.
     * [accent] : l'or du thème par défaut, le rouge pour un interrupteur qui ferme (catégories).
     */
    fun interrupteur(ctx: Context, coche: Boolean, th: Theme = themeActif(ctx), accent: Int = th.or,
                     description: String = "", change: ((Boolean) -> Unit)? = null): android.widget.Switch =
        android.widget.Switch(ctx).apply {
            text = null; showText = false; isChecked = coche
            val pouce = dp(ctx, 20f); val retrait = dp(ctx, 4f)
            val vide = Palette.melanger(th.encre2, th.carte, 0.10f)
            fun rond(c: Int): android.graphics.drawable.Drawable = android.graphics.drawable.InsetDrawable(GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(c); setStroke(dp(ctx, 1.5f), th.carte); setSize(pouce, pouce)
            }, retrait)
            fun piste(c: Int, bord: Int): android.graphics.drawable.Drawable = GradientDrawable().apply {
                cornerRadius = dp(ctx, 999f).toFloat(); setColor(c); setStroke(dp(ctx, 1.5f), bord); setSize(dp(ctx, 56f), pouce + 2 * retrait)
            }
            thumbDrawable = StateListDrawable().apply {
                addState(DESACTIVE, rond(Palette.melanger(vide, th.carte, 0.55f)))
                addState(COCHE, rond(accent))
                addState(intArrayOf(), rond(vide))
            }
            trackDrawable = StateListDrawable().apply {
                addState(DESACTIVE, piste(Palette.melanger(th.carte, th.encre, 0.08f), Palette.melanger(th.carte, th.encre, 0.16f)))
                addState(COCHE, piste(Palette.melanger(th.carte, accent, 0.45f), accent))
                addState(intArrayOf(), piste(Palette.melanger(th.carte, th.encre, 0.14f), Palette.melanger(th.carte, th.encre, 0.35f)))
            }
            // Les teintes Material posées par le thème Android recolorieraient nos dessins : on les retire.
            if (Build.VERSION.SDK_INT >= 23) { thumbTintList = null; trackTintList = null }
            switchMinWidth = dp(ctx, 56f)
            minHeight = dp(ctx, CIBLE_MIN); gravity = Gravity.CENTER_VERTICAL
            if (description.isNotEmpty()) contentDescription = description
            anneauFocus(this, th, 18f, agrandir = false)
            if (change != null) setOnCheckedChangeListener { _, v -> change(v) }
        }

    /**
     * Une glissière (SeekBar) du thème [th] : piste grise du thème, progression et pouce à l'or, pouce cerclé de la
     * carte (lisible sur la piste), pâlie quand elle est désactivée ; anneau de focus de Vision à la télécommande.
     */
    fun glissiere(ctx: Context, max: Int, valeur: Int, th: Theme = themeActif(ctx), change: ((Int) -> Unit)? = null): android.widget.SeekBar =
        android.widget.SeekBar(ctx).apply {
            this.max = max; progress = valeur
            val h = dp(ctx, 6f); val r = dp(ctx, 999f).toFloat(); val d = dp(ctx, 22f)
            val fondPiste = GradientDrawable().apply { cornerRadius = r; setColor(Palette.melanger(th.carte, th.encre, 0.18f)); setSize(0, h) }
            val plein = android.graphics.drawable.ClipDrawable(StateListDrawable().apply {
                addState(DESACTIVE, GradientDrawable().apply { cornerRadius = r; setColor(Palette.melanger(th.or, th.carte, 0.55f)); setSize(0, h) })
                addState(intArrayOf(), GradientDrawable().apply { cornerRadius = r; setColor(th.or); setSize(0, h) })
            }, Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL)
            progressDrawable = android.graphics.drawable.LayerDrawable(arrayOf(fondPiste, plein)).apply {
                setId(0, android.R.id.background); setId(1, android.R.id.progress)
            }
            thumb = StateListDrawable().apply {
                addState(DESACTIVE, GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Palette.melanger(th.or, th.carte, 0.55f)); setSize(d, d) })
                addState(intArrayOf(), GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(th.or); setStroke(dp(ctx, 2f), th.carte); setSize(d, d) })
            }
            progressTintList = null; progressBackgroundTintList = null; thumbTintList = null
            splitTrack = false
            if (Build.VERSION.SDK_INT >= 29) { minHeight = h; maxHeight = h }
            setPadding(d / 2 + dp(ctx, 4f), dp(ctx, 12f), d / 2 + dp(ctx, 4f), dp(ctx, 12f))
            anneauFocus(this, th, 14f, agrandir = false)
            if (change != null) setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, utilisateur: Boolean) { change(p) }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
        }

    /**
     * Une case à cocher (CheckBox) du thème [th] : case à l'or cochée, à l'encre secondaire vide, pâlie désactivée ;
     * libellé éventuel en encre du thème, sans césure ni coupure ; anneau de focus de Vision à la télécommande.
     */
    fun caseACocher(ctx: Context, texte: CharSequence = "", coche: Boolean = false, th: Theme = themeActif(ctx),
                    change: ((Boolean) -> Unit)? = null): android.widget.CheckBox = android.widget.CheckBox(ctx).apply {
        text = texte; isChecked = coche
        buttonTintList = ColorStateList(arrayOf(DESACTIVE, COCHE, intArrayOf()),
            intArrayOf(Palette.melanger(th.encre3, th.carte, 0.40f), th.or, th.encre2))
        if (texte.isNotEmpty()) {
            textSize = 15f; typeface = Polices.texte(ctx)
            setTextColor(ColorStateList(arrayOf(DESACTIVE, intArrayOf()), intArrayOf(th.encre3, th.encre)))
            sansCesure(this)
        }
        anneauFocus(this, th, 10f, agrandir = false)
        if (change != null) setOnCheckedChangeListener { _, v -> change(v) }
    }

    /**
     * Un sélecteur de nombre (NumberPicker) du thème [th] : chiffres à l'encre du thème, traits de sélection à l'or
     * (au lieu des séparateurs Android). [libelles] : les valeurs affichées à la place des nombres (« 00 », « 05 »…).
     */
    fun selecteurNombre(ctx: Context, min: Int, max: Int, valeur: Int, libelles: Array<String>? = null,
                        th: Theme = themeActif(ctx)): android.widget.NumberPicker = SelecteurVision(ctx, th).apply {
        minValue = min; maxValue = max
        if (libelles != null) displayedValues = libelles
        value = valeur
        habillerSelecteur(this, th)
        anneauFocus(this, th, 14f, agrandir = false)
    }

    /**
     * Les traits de sélection du sélecteur : à partir d'Android 10, ceux de la plateforme sont effacés
     * (selectionDividerHeight = 0) et redessinés ici à l'or, aux mêmes places (48 dp d'écart, celui du style Material) ;
     * avant Android 10, le dessin d'origine est remplacé par réflexion (protégée, voir [habillerSelecteur]).
     */
    private class SelecteurVision(ctx: Context, private val th: Theme) : android.widget.NumberPicker(ctx) {
        private val trait = android.graphics.Paint().apply { color = th.or }
        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            if (Build.VERSION.SDK_INT < 29) return
            val e = dp(context, 2f); val ecart = dp(context, 48f)
            val haut = (height - ecart) / 2 - e
            canvas.drawRect(0f, haut.toFloat(), width.toFloat(), (haut + e).toFloat(), trait)
            canvas.drawRect(0f, (haut + e + ecart).toFloat(), width.toFloat(), (haut + 2 * e + ecart).toFloat(), trait)
        }
    }

    /**
     * Habille un NumberPicker au thème [th] (couleur des chiffres, traits de sélection, champ de saisie). Android 10+ :
     * API publiques (textColor, selectionDividerHeight). Avant : champs privés mSelectorWheelPaint et
     * mSelectionDivider (accessibles jusqu'à Android 9) ; un refus de la plateforme laisse le rendu Android, sans planter.
     */
    fun habillerSelecteur(np: android.widget.NumberPicker, th: Theme) {
        val ctx = np.context
        if (Build.VERSION.SDK_INT >= 29) {
            np.textColor = th.encre
            np.selectionDividerHeight = if (np is SelecteurVision) 0 else dp(ctx, 2f)
        } else {
            try {
                val f = android.widget.NumberPicker::class.java.getDeclaredField("mSelectorWheelPaint").apply { isAccessible = true }
                (f.get(np) as? android.graphics.Paint)?.color = th.encre
            } catch (_: Throwable) {}
            try {
                android.widget.NumberPicker::class.java.getDeclaredField("mSelectionDivider").apply { isAccessible = true }
                    .set(np, android.graphics.drawable.ColorDrawable(th.or))
            } catch (_: Throwable) {}
        }
        for (i in 0 until np.childCount) (np.getChildAt(i) as? EditText)?.apply {
            setTextColor(th.encre); setHintTextColor(th.encre3); typeface = Polices.texte(ctx)
            backgroundTintList = ColorStateList.valueOf(th.or)
        }
        np.invalidate()
    }

    fun separateur(ctx: Context): View = View(ctx).apply {
        setBackgroundColor(LIGNE)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1f)).apply {
            topMargin = dp(ctx, 10f); bottomMargin = dp(ctx, 10f)
        }
    }

    fun espace(ctx: Context, h: Float): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, h))
    }

    fun poids(v: View, poids: Float = 1f): View = v.apply {
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, poids)
    }

    fun marge(ctx: Context, v: View, haut: Float = 0f, bas: Float = 0f, gauche: Float = 0f, droite: Float = 0f): View = v.apply {
        val lp = (layoutParams as? LinearLayout.LayoutParams) ?: LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(ctx, haut); lp.bottomMargin = dp(ctx, bas)
        lp.leftMargin = dp(ctx, gauche); lp.rightMargin = dp(ctx, droite)
        layoutParams = lp
    }
    private var dernierToast: android.widget.Toast? = null

    /** 1.86.15 : un retour court et visible (le suivant remplace le précédent). */
    fun toast(ctx: android.content.Context, texte: String, court: Boolean = false,
              genre: OeilModele.Genre = OeilModele.Genre.INFO) {
        if (texte.isBlank()) return
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            android.os.Handler(android.os.Looper.getMainLooper()).post { toast(ctx, texte, court, genre) }
            return
        }
        // 1.90.5 (l'auteur, variante B) : sur un écran de Vision, la réaction de l'œil remplace le bandeau gris (ReactionOeil) ;
        // hors d'un écran (service, récepteur), le bandeau reste.
        if (ctx is android.app.Activity && ReactionOeil.actif(ctx)) {
            val ok = try { ReactionOeil.montrer(ctx, genre, texte, court) } catch (_: Exception) { false }
            if (ok) { dernierToast?.cancel(); return }
        }
        try {
            dernierToast?.cancel()
            dernierToast = android.widget.Toast.makeText(ctx.applicationContext, texte,
                if (court) android.widget.Toast.LENGTH_SHORT else android.widget.Toast.LENGTH_LONG).also { it.show() }
        } catch (_: Exception) {}
    }

    const val TAG_CONFIRMATION = "confirmation_discrete"

    /**
     * 17-10 (l'auteur, point 14 : « au lancement, l'œil de Vision est TROP GROS ») : une confirmation discrète aux couleurs du
     * thème — une petite pastille posée à une place libre (comme l'œil, sans rien couvrir de ce qu'on lit ou touche ;
     * dans la zone sûre de la télé), qui paraît et s'efface en fondu. La suivante remplace la précédente. Les erreurs gardent [toast] (l'œil, qui se voit).
     */
    fun confirmation(a: android.app.Activity, texte: String, dureeMs: Long = 1800) {
        if (texte.isBlank()) return
        val racine = a.window?.decorView as? ViewGroup
        if (racine == null || a.isFinishing) { toast(a, texte, court = true); return }
        val th = Local.themeEffectif(a, Palette.APPLI)
        val tele = ReglagesTvActivity.estTele(a)
        racine.findViewWithTag<View>(TAG_CONFIRMATION)?.let { racine.removeView(it) }
        val v = TextView(a).apply {
            tag = TAG_CONFIRMATION; text = texte; setTextColor(th.encre); textSize = if (tele) 15f else 13f; typeface = Polices.texte(a)
            gravity = Gravity.CENTER  // jamais coupée : une phrase longue passe à la ligne (largeur bornée)
            val p = dp(a, if (tele) 14f else 12f); setPadding(p, p / 2, p, p / 2)
            maxWidth = (a.resources.displayMetrics.widthPixels * 0.6f).toInt()
            background = GradientDrawable().apply { cornerRadius = dp(a, 999f).toFloat(); setColor(th.carte); setStroke(dp(a, 1.5f), th.or) }
            alpha = 0f; isFocusable = false; isClickable = false
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            if (Build.VERSION.SDK_INT >= 21) translationZ = dp(a, 16f).toFloat()
        }
        val zx = if (tele) ZoneSure.x(a, 0.05f) else 0; val zy = if (tele) ZoneSure.y(a, 0.05f) else 0
        val utile = ReactionOeil.Boite(zx, zy, racine.width - zx, racine.height - zy)
        v.measure(View.MeasureSpec.makeMeasureSpec(utile.d - utile.g, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(utile.b - utile.h, View.MeasureSpec.AT_MOST))
        val ou = ReactionOeil.placer(utile, v.measuredWidth, v.measuredHeight, dp(a, if (tele) 8f else 16f),
            ReactionOeil.occupes(racine).map { ReactionOeil.Boite(it.left, it.top, it.right, it.bottom) }, racine.layoutDirection == View.LAYOUT_DIRECTION_RTL)
        racine.addView(v, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START).apply { leftMargin = ou.g; topMargin = ou.h })
        v.animate().alpha(1f).setDuration(180).start()
        v.postDelayed({ v.animate().alpha(0f).setDuration(260).withEndAction { racine.removeView(v) }.start() }, dureeMs)
    }

    /**
     * 16-10 (recette télé 1080p, 130 %) : la taille en pixels de [sp] telle qu'Android la pose vraiment. Depuis Android 14,
     * les grandes polices ne sont plus proportionnelles (13 sp à 130 % : 34,4 px, et non 13 × 2,6 = 33,8) ; mesurer avec
     * scaledDensity laissait croire qu'un nom tenait, et il prenait des « … » pour un demi-pixel.
     */
    private fun pxSp(tv: TextView, sp: Float): Float =
        android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, sp, tv.resources.displayMetrics)

    /**
     * 1.87.0 (recette visuelle) : un nom sur plusieurs lignes (nom d'appli du tiroir) ne se coupe jamais au milieu d'un mot :
     * sa taille descend de [maxSp] à [minSp] jusqu'à ce que le mot le plus long tienne sur une ligne.
     */
    fun motsEntiers(tv: TextView, maxSp: Float, minSp: Float) {
        // Un nom d'appli peut contenir une espace insécable (« Recherche vocale ») : on y autorise le passage à la ligne.
        val brut = tv.text.toString(); val propre = brut.replace(160.toChar(), ' ').replace(8239.toChar(), ' ')
        if (propre != brut) tv.text = propre
        fun ajuster() {
            val dispo = tv.width - tv.paddingLeft - tv.paddingRight
            if (dispo <= 0) return
            val mots = tv.text.toString().split(' ', 160.toChar(), 8239.toChar(), 10.toChar(), 8203.toChar()).filter { it.isNotEmpty() }  // 8203 : coupure posée par coupable()
            if (mots.isEmpty()) return
            val p = android.text.TextPaint(tv.paint)
            var sp = maxSp
            while (sp > minSp) { p.textSize = pxSp(tv, sp); if (mots.all { p.measureText(it) + 1f <= dispo }) break; sp -= 0.5f }
            if (kotlin.math.abs(tv.textSize - pxSp(tv, sp)) > 0.5f) tv.post { tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp) }
        }
        tv.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or2, _ -> if (r - l != or2 - ol) ajuster() }
    }

    /**
     * 1.87.0 (recette visuelle) : un texte court qui doit rester sur une ligne (l'heure « 12:06 PM ») : sa taille
     * descend de [maxSp] à [minSp] jusqu'à ce qu'il tienne dans sa largeur ; jamais de points de suspension.
     */
    fun surUneLigne(tv: TextView, maxSp: Float, minSp: Float) {
        tv.maxLines = 1; tv.setHorizontallyScrolling(false); tv.ellipsize = null
        fun ajuster() {
            val dispo = tv.width - tv.paddingLeft - tv.paddingRight
            if (dispo <= 0) return
            val p = android.text.TextPaint(tv.paint)
            var sp = maxSp
            while (sp > minSp) { p.textSize = pxSp(tv, sp); if (p.measureText(tv.text.toString()) + 1f <= dispo) break; sp -= 1f }
            if (kotlin.math.abs(tv.textSize - pxSp(tv, sp)) > 0.5f) tv.post { tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp) }
        }
        tv.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or2, _ -> if (r - l != or2 - ol) ajuster() }
        tv.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { ajuster() }
        })
    }

    /**
     * 17-10 (widget chauffage lisible) : une phrase sur deux lignes au plus ; sa taille descend de [maxSp] à [minSp]
     * jusqu'à ce qu'elle tienne, sans « … » (au plus petit, une troisième ligne plutôt qu'un texte coupé).
     */
    fun surDeuxLignes(tv: TextView, maxSp: Float, minSp: Float) {
        tv.ellipsize = null
        fun ajuster() {
            val dispo = tv.width - tv.paddingLeft - tv.paddingRight
            if (dispo <= 0) return
            val p = android.text.TextPaint(tv.paint)
            var sp = maxSp
            while (sp > minSp) {
                p.textSize = pxSp(tv, sp)
                @Suppress("DEPRECATION")
                val l = android.text.StaticLayout(tv.text, p, dispo, android.text.Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false)
                if (l.lineCount <= 2) break
                sp -= 0.5f
            }
            if (kotlin.math.abs(tv.textSize - pxSp(tv, sp)) > 0.5f) tv.post { tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp) }
        }
        tv.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or2, _ -> if (r - l != or2 - ol) ajuster() }
    }

    /**
     * 16-10 (l'auteur : « réduis-le proprement, aucun texte ne disparaît en silence ») : un nom sur une ligne qui rapetisse de
     * [maxSp] à [minSp] pour tenir, et ne prend « … » qu'au plus petit (le texte entier reste dit : contentDescription).
     */
    fun surUneLigneOuPoints(tv: TextView, maxSp: Float, minSp: Float) {
        tv.maxLines = 1; tv.ellipsize = android.text.TextUtils.TruncateAt.END
        if (tv.contentDescription.isNullOrEmpty()) tv.contentDescription = tv.text
        fun ajuster() {
            val dispo = tv.width - tv.paddingLeft - tv.paddingRight
            if (dispo <= 0) return
            val p = android.text.TextPaint(tv.paint)
            var sp = maxSp
            while (sp > minSp) { p.textSize = pxSp(tv, sp); if (p.measureText(tv.text.toString()) + 1f <= dispo) break; sp -= 0.5f }
            if (kotlin.math.abs(tv.textSize - pxSp(tv, sp)) > 0.5f) tv.post { tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp) }
        }
        tv.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or2, _ -> if (r - l != or2 - ol) ajuster() }
    }

    /**
     * 15-10 (règle UX de l'auteur) : un choix unique parmi des options — étiquettes si 3 ou moins tiennent sur une ligne, sinon
     * liste déroulante (ChoixAdaptatif). [change] reçoit la clé choisie.
     */
    fun choix(ctx: Context, titre: String, options: List<ChoixAdaptatif.Option>, choisie: String, theme: Theme = themeActif(ctx),
              tailleSp: Float = 14f, change: (String) -> Unit): ChoixAdaptatif =
        ChoixAdaptatif(ctx, titre, options, setOf(choisie), multiple = false, theme = theme, tailleSp = tailleSp) { l -> l.firstOrNull()?.let(change) }

    /** Le même, en choix multiple : [change] reçoit les clés choisies. */
    fun choixMultiple(ctx: Context, titre: String, options: List<ChoixAdaptatif.Option>, choisies: Set<String>, theme: Theme = themeActif(ctx),
                      tailleSp: Float = 14f, change: (Set<String>) -> Unit): ChoixAdaptatif =
        ChoixAdaptatif(ctx, titre, options, choisies, multiple = true, theme = theme, tailleSp = tailleSp, change = change)
}
