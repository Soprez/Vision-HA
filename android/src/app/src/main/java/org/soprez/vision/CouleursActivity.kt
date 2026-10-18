// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Les couleurs, d'après la maquette : un aperçu, le thème de départ, puis le
 * fond, les cartes, le texte et l'accent à la carte ; le « + » ouvre une roue
 * pour une couleur libre. « Revenir au thème » annule tout. Un seul réglage
 * pour tout l'appareil : lanceur, écran de veille et écrans de l'appli. À la
 * télécommande, ce qui a le focus grossit et prend un anneau doré.
 */
class CouleursActivity : Activity() {
    private val cible = Palette.APPLI
    private lateinit var racine: ScrollView
    private lateinit var colonne: LinearLayout
    private var reglage = JSONObject()
    private var focusTag: String? = null

    private val choix = linkedMapOf(
        "fond" to (R.string.couleurs_fond to listOf("#17121C", "#0F1830", "#10201A", "#EFE6D6", "#F0DEDD")),
        "carte" to (R.string.couleurs_cartes to listOf("#251C2D", "#1A2647", "#1B2E26", "#FBF6EC", "#FBF1F0")),
        "encre" to (R.string.couleurs_texte to listOf("#F4EDE1", "#FFFFFF", "#EAF0FA", "#2A2026", "#1F2A24")),
        "accent" to (R.string.couleurs_accent to listOf("#E2B24A", "#C2577B", "#6FA8E8", "#5FA58A", "#8C2F4B")),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        racine = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        colonne = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        racine.addView(colonne)
        setContentView(racine)
        charger()
        construire()
    }

    private fun px(dp: Float) = Ui.dp(this, dp)
    private fun charger() { reglage = Palette.lire(this, cible) ?: JSONObject() }
    private fun enregistrer() {
        focusTag = currentFocus?.tag as? String
        Palette.ecrire(this, cible, if (reglage.length() == 0) null else reglage)
        construire()
    }

    /** Le thème effectif, pour l'aperçu et pour peindre l'écran (sans le mode nuit, pour voir ce qu'on règle). */
    private fun theme(): Theme = Palette.appliquer(this, cible, Themes.parNom(Local.theme(this)))

    /** Rend le focus visible à la télécommande : anneau doré et léger grossissement. */
    private fun focalisable(v: View, cle: String, rond: Boolean = true, peint: View = v) {
        v.isClickable = true; v.isFocusable = true; v.tag = cle
        val th = theme()
        val anneau = GradientDrawable().apply { if (rond) shape = GradientDrawable.OVAL else cornerRadius = px(22f).toFloat(); setColor(0); setStroke(px(1.5f), th.or) }
        v.setOnFocusChangeListener { _, a ->
            if (android.os.Build.VERSION.SDK_INT >= 23) peint.foreground = if (a) anneau else null
            peint.animate().scaleX(if (a) 1.12f else 1f).scaleY(if (a) 1.12f else 1f).setDuration(120).start()
        }
    }

    private fun texte(t: String, taille: Float, couleur: Int, police: android.graphics.Typeface = Polices.texte(this), espacement: Float = 0f): TextView =
        Ui.sansCesure(TextView(this).apply { text = t; textSize = taille; setTextColor(couleur); typeface = police; letterSpacing = espacement })

    private fun construire() {
        val th = theme()
        val tele = ReglagesTvActivity.estTele(this)
        racine.setBackgroundColor(th.fond)
        window.navigationBarColor = th.fond
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or (if (!th.sombre) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0)
        colonne.removeAllViews()
        val dm = resources.displayMetrics
        colonne.setPadding(if (tele) ZoneSure.x(this@CouleursActivity, 0.06f) else px(20f), if (tele) ZoneSure.y(this@CouleursActivity, 0.035f) else px(36f), if (tele) ZoneSure.x(this@CouleursActivity, 0.06f) else px(20f), px(24f))

        // La marque, au milieu en haut, puis le titre et « Revenir au thème ».
        val marque = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, px(14f)) }
        marque.addView(ImageView(this).apply { setImageResource(R.drawable.ic_vision) }, LinearLayout.LayoutParams(px(20f), px(20f)))
        marque.addView(texte("VISION", 12f, th.or, Polices.gras(this), 0.28f).apply { setPadding(px(8f), 0, 0, 0) })
        colonne.addView(marque, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val tete = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val retour = FrameLayout(this).apply { setOnClickListener { finish() }; contentDescription = getString(R.string.a11y_retour) }
        focalisable(retour, "retour")
        retour.addView(ImageView(this).apply { setImageResource(R.drawable.ic_retour); setColorFilter(th.encre) }, FrameLayout.LayoutParams(px(24f), px(24f), Gravity.CENTER))
        tete.addView(retour, LinearLayout.LayoutParams(px(Ui.CIBLE_MIN), px(Ui.CIBLE_MIN)))
        tete.addView(texte(getString(R.string.manifeste_couleurs), 26f, th.encre, Polices.gras(this)).apply { setPadding(px(6f), 0, 0, 0) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val revenir = texte(getString(R.string.couleurs_revenir_theme), 14f, th.or, Polices.demiGras(this)).apply {
            setPadding(px(12f), px(10f), px(12f), px(10f)); minHeight = px(Ui.CIBLE_MIN); gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { cornerRadius = px(20f).toFloat(); setStroke(px(1f), th.carteBord) }
            setOnClickListener { reglage = JSONObject(); enregistrer() }
            focalisable(this, "revenir", rond = false)
        }
        colonne.addView(tete)
        // Largeur juste : « Revenir au thème » passe sous le titre au lieu de le serrer.
        if (Ui.etroit(this)) colonne.addView(revenir, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(8f) })
        else tete.addView(revenir)

        // L'aperçu : l'heure du lanceur et une carte de l'appli.
        val apercu = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = GradientDrawable().apply { cornerRadius = px(24f).toFloat(); setColor(th.fond); setStroke(px(1f), th.carteBord) }; setPadding(px(18f), px(16f), px(18f), px(16f)) }
        apercu.addView(texte("20:42", 44f, th.encre, Polices.outfitFin(this)).apply { includeFontPadding = false })
        val rang = LinearLayout(this).apply { setPadding(0, px(10f), 0, px(12f)) }
        for (l in listOf("Y", "S", "P")) {
            rang.addView(texte(l, 18f, th.encre, Polices.outfit(this)).apply { gravity = Gravity.CENTER; background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Palette.melanger(th.carte, th.encre, 0.08f)) } }, LinearLayout.LayoutParams(px(44f), px(44f)).apply { rightMargin = px(12f) })
        }
        apercu.addView(rang)
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = GradientDrawable().apply { cornerRadius = px(18f).toFloat(); setColor(th.carte); setStroke(px(1f), th.carteBord) }; setPadding(px(14f), px(12f), px(14f), px(12f)) }
        c.addView(texte(getString(R.string.couleurs_apercu_temps_ecran), 11f, th.encre2, Polices.gras(this), 0.2f))
        val l = Ui.rangeeBoutons(this).apply { setPadding(0, px(8f), 0, 0) }
        l.addView(texte(getString(R.string.couleurs_apercu_restantes), 15f, th.encre, Polices.demiGras(this)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        l.addView(texte(getString(R.string.block_demander_du_temps), 12f, th.fond, Polices.gras(this)).apply { setPadding(px(12f), px(8f), px(12f), px(8f)); background = GradientDrawable().apply { cornerRadius = px(20f).toFloat(); setColor(th.pourpre) } })
        c.addView(l)
        apercu.addView(c)
        colonne.addView(apercu, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(16f) })
        // Lot 1 (A7, APP-20) : couleurs à la carte : avertir quand le texte se lit mal (contraste < 4,5:1).
        Themes.contrasteTexte(th.encre, th.fond, th.carte).takeIf { it < Themes.CONTRASTE_TEXTE_MIN }?.let { c ->
            colonne.addView(texte(getString(R.string.couleurs_contraste_faible, String.format(java.util.Locale.getDefault(), "%.1f", c)), 14f, th.encre, Polices.demiGras(this)).apply {
                tag = "contraste_faible"; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                setPadding(px(14f), px(10f), px(14f), px(10f))
                background = GradientDrawable().apply { cornerRadius = px(14f).toFloat(); setColor(th.carte); setStroke(px(2f), th.encre) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(12f) })
        }

        // Le thème de départ.
        colonne.addView(texte(getString(R.string.couleurs_theme_depart), 12f, th.encre2, Polices.gras(this), 0.2f).apply { setPadding(0, px(20f), 0, px(10f)) })
        // Les thèmes en grille : autant par ligne que la largeur le permet à la taille du texte choisie
        // (un nom de thème n'est jamais serré au point d'être coupé au milieu d'un mot).
        val themes = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        // 1.90.1 : la largeur sans les barres du système (paysage, Android 15+), et une case assez large pour le nom
        // de thème le plus long, en gras (le thème choisi l'est).
        val mesure = android.text.TextPaint().apply { textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 11f, dm); typeface = Polices.gras(this@CouleursActivity) }
        val nomLePlusLong = Themes.liste.maxOfOrNull { mesure.measureText(Themes.libelle(it.nom)) } ?: 0f
        val case = maxOf(px(72f) * resources.configuration.fontScale.coerceAtLeast(1f), nomLePlusLong + px(12f))
        val parLigne = ((Marges.largeurUtile(this) - colonne.paddingLeft - colonne.paddingRight) / case).toInt().coerceIn(1, 8)
        var ligneThemes = LinearLayout(this)
        Themes.liste.forEachIndexed { k, t ->
            if (k % parLigne == 0) { ligneThemes = LinearLayout(this).apply { clipChildren = false; clipToPadding = false }; themes.addView(ligneThemes) }
            val on = Local.theme(this).equals(t.nom, ignoreCase = true)
            val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(px(4f), px(4f), px(4f), px(4f)); clipChildren = false; clipToPadding = false }
            val pastille = FrameLayout(this)
            focalisable(cell, "theme:" + t.nom, peint = pastille)
            pastille.addView(View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(t.fond); setStroke(px(2f), t.carteBord) } }, FrameLayout.LayoutParams(px(44f), px(44f), Gravity.CENTER))
            pastille.addView(View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(t.pourpre) } }, FrameLayout.LayoutParams(px(14f), px(14f), Gravity.CENTER))
            if (on) pastille.addView(View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0); setStroke(px(1.5f), th.or) } }, FrameLayout.LayoutParams(px(54f), px(54f), Gravity.CENTER))
            cell.addView(pastille, LinearLayout.LayoutParams(px(54f), px(54f)))
            cell.addView(texte(Themes.libelle(t.nom), 11f, if (on) th.encre else th.encre2, if (on) Polices.gras(this) else Polices.moyen(this)).apply { gravity = Gravity.CENTER; setPadding(0, px(6f), 0, 0) })
            cell.setOnClickListener { Local.poserTheme(this, t.nom); reglage = JSONObject(); enregistrer() }
            // Lot 6 (APP-22) : le thème choisi se dit « choisi », il ne se voit pas qu'à son anneau.
            cell.contentDescription = if (on) getString(R.string.msgenv_choisi, Themes.libelle(t.nom)) else Themes.libelle(t.nom)
            ligneThemes.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        // La dernière ligne garde les mêmes colonnes que les autres.
        repeat((parLigne - Themes.liste.size % parLigne) % parLigne) { ligneThemes.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f)) }
        colonne.addView(themes)

        // À la carte.
        colonne.addView(texte(getString(R.string.couleurs_a_la_carte), 12f, th.encre2, Polices.gras(this), 0.2f).apply { setPadding(0, px(20f), 0, px(6f)) })
        val valeurs = mapOf("fond" to th.fond, "carte" to th.carte, "encre" to th.encre, "accent" to th.pourpre)
        for ((cle, def) in choix) {
            val (nomId, liste) = def
            val nom = getString(nomId)
            val rang = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(8f), 0, px(8f)) }
            rang.addView(texte(nom, 15f, th.encre, Polices.moyen(this)).apply { minWidth = px(72f); setPadding(0, 0, px(6f), 0) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val actuel = valeurs[cle] ?: 0
            val pastilles = LinearLayout(this).apply { clipChildren = false; clipToPadding = false; setPadding(px(4f), 0, px(4f), 0) }
            liste.forEach { hex ->
                val c = Color.parseColor(hex)
                val on = (c and 0xFFFFFF) == (actuel and 0xFFFFFF)
                // Lot 6 (APP-22) : la pastille se lit par le nom de sa couleur (« bleu foncé »), plus par son code.
                val v = FrameLayout(this).apply { contentDescription = Lisibilite.nomCouleur(c).let { n -> if (on) getString(R.string.msgenv_choisi, n) else n } }
                focalisable(v, "$cle:$hex")
                v.addView(View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c); setStroke(px(2f), th.carteBord) } }, FrameLayout.LayoutParams(px(40f), px(40f), Gravity.CENTER))
                if (on) v.addView(View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0); setStroke(px(1.5f), th.or) } }, FrameLayout.LayoutParams(px(50f), px(50f), Gravity.CENTER))
                v.setOnClickListener { poser(cle, c) }
                pastilles.addView(v, LinearLayout.LayoutParams(px(50f), px(50f)))
            }
            val plus = FrameLayout(this).apply { contentDescription = getString(R.string.couleurs_autre_couleur) }
            focalisable(plus, "$cle:plus")
            plus.addView(View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0); setStroke(px(2f), th.encre2, px(5f).toFloat(), px(4f).toFloat()) } }, FrameLayout.LayoutParams(px(40f), px(40f), Gravity.CENTER))
            plus.addView(ImageView(this).apply { setImageResource(R.drawable.ic_plus); setColorFilter(th.encre2) }, FrameLayout.LayoutParams(px(20f), px(20f), Gravity.CENTER))
            plus.setOnClickListener { roue(nom, actuel) { poser(cle, it) } }
            pastilles.addView(plus, LinearLayout.LayoutParams(px(50f), px(50f)))
            val defilant = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; addView(pastilles) }
            rang.addView(defilant, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            colonne.addView(rang)
            // 1.91.0 : sur la télé, les pastilles défilent jusqu'au bord droit de l'écran (fondu dans l'overscan, jamais
            // coupées net au bord de la marge) ; à gauche, elles restent après le nom de la couleur.
            if (tele) BordABord.poser(defilant, pastilles, colonne.paddingLeft, colonne.paddingRight, px(4f), gauche = false)
        }
        colonne.addView(texte(getString(R.string.couleurs_explication), 13f, th.encre2).apply { setPadding(0, px(16f), 0, 0) })
        // 15-10 (l'auteur : « Réglages de l'appareil » presque obsolète) : sur le téléphone et la tablette, l'apparence (mode
        // sombre, mode nuit) et le fond de l'accueil ont rejoint ce menu ; la télé les garde dans ses réglages.
        if (!tele) {
            fun groupe(titre: String) = colonne.addView(texte(titre.uppercase(), 12f, th.encre2, Polices.gras(this), 0.2f).apply { setPadding(0, px(24f), 0, px(8f)) })
            // Aux couleurs de cette page (son thème d'aperçu), pas à celles de l'appli : sinon, des tuiles sombres sur un fond clair.
            fun rangee(titre: String, valeur: String, cle: String, action: () -> Unit) = colonne.addView(
                Ui.tuile(this, "$titre  ›", valeur, false, action).apply {
                    tag = cle; minHeight = px(60f); minimumHeight = minHeight
                    val t = android.text.SpannableStringBuilder("$titre  ›")
                    val debut = t.length
                    t.append('\n').append(valeur)
                    t.setSpan(android.text.style.RelativeSizeSpan(0.82f), debut, t.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    t.setSpan(android.text.style.ForegroundColorSpan(th.encre2), debut, t.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (android.os.Build.VERSION.SDK_INT >= 28) t.setSpan(android.text.style.TypefaceSpan(Polices.texte(this@CouleursActivity)), debut, t.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    text = t; setTextColor(th.encre)
                    background = Ui.fondInteractif(this@CouleursActivity, th.carte, Palette.melanger(th.carte, th.encre, 0.1f), px(18f).toFloat(),
                        th.or, Palette.melanger(th.carte, th.encre, 0.28f))
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = px(8f) })
            val apres = { construire() }
            groupe(getString(R.string.reglagestv_apparence))
            rangee(getString(R.string.reglagestv_mode_sombre), ReglagesPartages.nomModeSombre(this), "sombre") { ReglagesPartages.choisirModeSombre(this, apres) }
            rangee(getString(R.string.reglagestv_mode_nuit), ReglagesPartages.nomNuit(this), "nuit") { ReglagesPartages.choisirNuit(this, apres) }
            groupe(getString(R.string.fondacc_titre))
            rangee(getString(R.string.fondacc_titre), ReglagesPartages.nomFond(this), "fond_accueil") { ReglagesPartages.choisirFondAccueil(this, apres) }
            rangee(getString(R.string.fondacc_voile), ReglagesPartages.nomVoileFond(this), "fond_voile") { ReglagesPartages.choisirVoileFond(this, apres) }
            rangee(ReglagesPartages.titrePermisDemandeur(this), ReglagesPartages.nomPermisDemandeur(this), "fond_demandeur") { ReglagesPartages.basculerPermisDemandeur(this, apres) }
        }
        focusTag?.let { cle -> colonne.post { chercher(colonne, cle)?.requestFocus() } }
            Ui.laisserDeborder(racine)
}

    @Deprecated("Activité sans AndroidX : le résultat du choix de photo (fond de l'accueil) arrive ici.")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        ReglagesPartages.recevoirPhoto(this, requestCode, resultCode, data) { construire() }
    }

    private fun chercher(v: View, cle: String): View? {
        if (v.tag == cle) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) chercher(v.getChildAt(i), cle)?.let { return it }
        return null
    }

    /**
     * Lot 6 (B9 / APP-20) : une couleur à la carte qui rendrait le texte illisible (moins de 4,5:1 sur le fond ou les
     * cartes) ou l'accent invisible (moins de 3:1 sur le fond) est ajustée juste assez, et on dit pourquoi ; « Annuler »
     * revient aux couleurs d'avant.
     */
    private fun poser(cle: String, couleur: Int) {
        val avant = JSONObject(reglage.toString())
        reglage.put(cle, Palette.hex(couleur))
        Palette.ecrire(this, cible, reglage)
        val th = theme()
        val texte = Lisibilite.ajusterTexte(th.encre, th.fond, th.carte)
        val accent = Lisibilite.ajusterAccent(th.pourpre, th.fond)
        if (!texte.change && !accent.change) { enregistrer(); return }
        if (texte.change) reglage.put("encre", Palette.hex(texte.couleur))
        if (accent.change) reglage.put("accent", Palette.hex(accent.couleur))
        enregistrer()
        val l = java.util.Locale.getDefault()
        val lignes = ArrayList<String>()
        if (texte.change) lignes.add(getString(R.string.lot6_contraste_texte_ajuste, String.format(l, "%.1f", texte.contraste)))
        if (accent.change) lignes.add(getString(R.string.lot6_contraste_accent_ajuste, String.format(l, "%.1f", accent.contraste)))
        if (!texte.atteint || !accent.atteint) lignes.add(getString(R.string.lot6_contraste_impossible))
        lignes.add(getString(R.string.lot6_contraste_annuler))
        Ui.dialogue(this).setTitle(R.string.lot6_contraste_titre).setMessage(lignes.joinToString("\n\n"))
            .setPositiveButton(R.string.commun_ok, null)
            .setNegativeButton(R.string.commun_annuler) { _, _ -> reglage = avant; enregistrer() }
            .show()
    }

    /** La roue des couleurs : teinte et saturation au doigt, clarté à la glissière. */
    private fun roue(nom: String, depart: Int, apres: (Int) -> Unit) {
        val th = theme()
        val hsv = FloatArray(3); Color.colorToHSV(depart, hsv)
        val boite = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(20f), px(16f), px(20f), px(8f)); setBackgroundColor(th.carte) }
        val apercu = View(this).apply { background = GradientDrawable().apply { cornerRadius = px(14f).toFloat(); setColor(depart) } }
        val roue = RoueCouleur(this, hsv) { apercu.background = GradientDrawable().apply { cornerRadius = px(14f).toFloat(); setColor(Color.HSVToColor(hsv)) } }
        boite.addView(roue, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(240f)))
        val glissiere = Ui.glissiere(this, 100, (hsv[2] * 100).toInt(), th) { p ->
            hsv[2] = p / 100f; roue.invalidate(); apercu.background = GradientDrawable().apply { cornerRadius = px(14f).toFloat(); setColor(Color.HSVToColor(hsv)) }
        }
        boite.addView(texte(getString(R.string.couleurs_clarte), 12f, th.encre2).apply { setPadding(0, px(8f), 0, 0) })
        boite.addView(glissiere)
        boite.addView(apercu, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(40f)).apply { topMargin = px(8f) })
        Ui.dialogue(this).setTitle(nom).setView(boite)
            .setPositiveButton(getString(R.string.commun_lot2_garder)) { _, _ -> apres(Color.HSVToColor(hsv)) }
            .setNegativeButton(getString(R.string.commun_lot2_annuler), null).show()
    }

    /** Le disque teinte / saturation ; la clarté vient d'ailleurs. */
    private class RoueCouleur(ctx: Context, private val hsv: FloatArray, private val change: () -> Unit) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val curseur = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6f; color = Color.WHITE }
        override fun onDraw(c: Canvas) {
            val r = min(width, height) / 2f - 8f; val cx = width / 2f; val cy = height / 2f
            val teintes = IntArray(13) { Color.HSVToColor(floatArrayOf(it * 30f % 360f, 1f, hsv[2].coerceAtLeast(0.15f))) }
            p.shader = SweepGradient(cx, cy, teintes, null)
            c.drawCircle(cx, cy, r, p)
            p.shader = RadialGradient(cx, cy, r, Color.HSVToColor(floatArrayOf(0f, 0f, hsv[2].coerceAtLeast(0.15f))), 0x00FFFFFF, Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, r, p)
            p.shader = null
            val a = Math.toRadians(hsv[0].toDouble()); val d = hsv[1] * r
            c.drawCircle(cx + (d * cos(a)).toFloat(), cy + (d * sin(a)).toFloat(), 14f, curseur)
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val r = min(width, height) / 2f - 8f; val dx = e.x - width / 2f; val dy = e.y - height / 2f
            val dist = hypot(dx, dy)
            hsv[0] = ((Math.toDegrees(atan2(dy, dx).toDouble()) + 360) % 360).toFloat()
            hsv[1] = (dist / r).coerceIn(0f, 1f)
            parent?.requestDisallowInterceptTouchEvent(true)
            invalidate(); change()
            return true
        }
    }
}
