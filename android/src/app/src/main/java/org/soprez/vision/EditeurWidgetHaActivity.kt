// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/**
 * L'éditeur d'un widget Home Assistant (accueil Vision) : choisir l'entité (liste /widgets/entites, recherche),
 * le style parmi ceux proposés pour son domaine, la taille, le libellé et l'icône ; l'aperçu, en direct (vraies
 * données par le flux, ou données d'exemple hors ligne), suit chaque changement. « Poser » range le réglage et
 * place la case sur l'accueil. Même écran pour modifier un widget posé (extra `id`).
 */
class EditeurWidgetHaActivity : Activity() {
    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_CELLULE_L = "cellule_l"
        const val EXTRA_CELLULE_H = "cellule_h"

        fun ouvrir(act: Activity, id: String?, celluleL: Int, celluleH: Int) {
            act.startActivity(Intent(act, EditeurWidgetHaActivity::class.java).putExtra(EXTRA_ID, id).putExtra(EXTRA_CELLULE_L, celluleL).putExtra(EXTRA_CELLULE_H, celluleH))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var theme: Theme = Themes.liste[0]
    private val tele by lazy { ReglagesTvActivity.estTele(this) }
    private lateinit var contenu: LinearLayout
    private var catalogue: List<WidgetsHa.Entite>? = null
    private var erreur = ""
    private var hors = false
    private var idExistant: String? = null
    private var choisies = ArrayList<String>()
    private var style = WidgetsHa.TUILE
    private var taille = 2 to 1
    private var libelle = ""
    private var icone = ""
    private var variante = "ligne"
    private var periode = "24h"
    /** 1.88.0 : l'affichage de ce widget ("" : comme réglé dans Home Assistant). */
    private var affichage = WidgetsHa.AFF_HA
    /** 1.90.0 : le toucher de ce widget ("" : comme réglé dans Home Assistant) et le chemin d'une vue. */
    private var toucher = WidgetsHa.TOUCHER_HA
    /** 1.91.2 (retour d'essai) : le contenu défile dans le widget, et sa hauteur maximale. */
    private var defilement = true
    private var hauteur = WidgetsHa.HAUTEUR_MOYENNE
    /** 1.91.3 : la mise en page choisie ici (null : inchangée, celle de Home Assistant) ; envoyée à HA à l'enregistrement. */
    private var miseEnPage: WidgetsHa.MiseEnPage? = null
    private var cheminVue = ""
    private var recherche = ""
    private var listeOuverte = true
    private var celluleL = 0
    private var celluleH = 0
    private var apercu: LinearLayout? = null
    private var listeEntites: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.charger(this)
        theme = Local.themeEffectif(this, Palette.LANCEUR)
        val dm = resources.displayMetrics
        celluleL = intent.getIntExtra(EXTRA_CELLULE_L, 0).takeIf { it > 0 } ?: ((dm.widthPixels - px(32f)) / Accueil.COLONNES)
        celluleH = intent.getIntExtra(EXTRA_CELLULE_H, 0).takeIf { it > 0 } ?: (celluleL * 1.14f).toInt()
        idExistant = intent.getStringExtra(EXTRA_ID)
        idExistant?.let { WidgetsHaStock.reglage(this, it) }?.let { r ->
            choisies = ArrayList(r.entites); style = r.style; taille = r.w to r.h; libelle = r.libelle; icone = r.icone; variante = r.variante; periode = r.periode
            affichage = r.affichage
            toucher = r.toucher; cheminVue = r.chemin
            defilement = r.defilement; hauteur = r.hauteur
            listeOuverte = false
        }
        val racine = ScrollView(this).apply { isVerticalScrollBarEnabled = false; setBackgroundColor(theme.fond); isFillViewport = true }
        contenu = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        racine.addView(contenu)
        setContentView(racine)
        window.statusBarColor = theme.fond; window.navigationBarColor = theme.fond
        construire()
        charger()
    }

    override fun onResume() {
        super.onResume()
        brancherFlux()
    }

    override fun onPause() {
        super.onPause()
        FluxWidgets.arreter()
    }

    private fun px(dp: Float) = Ui.dp(this, dp)
    private fun t(s: Float) = if (tele) s * 1.35f else s

    private fun texte(s: String, taille: Float, couleur: Int, police: android.graphics.Typeface = Polices.texte(this)): TextView =
        Ui.sansCesure(TextView(this).apply { text = s; textSize = t(taille); setTextColor(couleur); typeface = police })

    private fun fondCarte(couleur: Int = theme.carte, bord: Int = theme.carteBord, epaisseur: Float = 1f, rayon: Float = 18f) =
        GradientDrawable().apply { cornerRadius = px(rayon).toFloat(); setColor(couleur); setStroke(px(epaisseur), bord) }

    /** Une pastille de choix (style, taille, icône…), à l'or quand elle est choisie, focalisable à la télécommande. */
    private fun pastilleChoix(titre: String, choisi: Boolean, actif: Boolean = true, action: () -> Unit): TextView =
        texte(titre, 14f, if (!actif) theme.encre3 else if (choisi) theme.fond else theme.encre, Polices.demiGras(this)).apply {
            background = if (choisi) fondCarte(theme.or, theme.or, rayon = 999f) else fondCarte(rayon = 999f)
            setPadding(px(14f), px(9f), px(14f), px(9f)); gravity = Gravity.CENTER
            isFocusable = actif; isClickable = actif
            setOnFocusChangeListener { v, a -> if (!choisi) v.background = if (a) fondCarte(bord = theme.or, epaisseur = 2f, rayon = 999f) else fondCarte(rayon = 999f) }
            if (actif) setOnClickListener { action() }
        }

    private fun section(titre: String) = contenu.addView(texte(titre.uppercase(Locale.FRANCE), 11f, theme.encre2, Polices.gras(this)).apply { letterSpacing = 0.2f; setPadding(px(4f), px(18f), 0, px(8f)) })

    /**
     * 15-10 (règle UX de l'auteur) : un choix parmi des options — en étiquettes si 3 au plus tiennent sur une ligne, sinon en
     * liste déroulante qui montre l'option choisie (ChoixAdaptatif, le composant de toute l'appli).
     */
    private fun choixUnique(titre: String, options: List<Pair<String, String>>, choisie: String, cle: String, icones: Map<String, android.graphics.drawable.Drawable> = emptyMap(), change: (String) -> Unit) {
        contenu.addView(Ui.choix(this, titre, options.map { (k, l) -> ChoixAdaptatif.Option(k, l, icones[k]) }, choisie, theme, t(14f), change).apply { tag = cle },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = px(6f) })
    }

    /** Un picto de Vision en image (pour les listes) : dessiné une fois à la taille d'une icône. */
    private fun dessinPicto(nom: String): android.graphics.drawable.Drawable {
        val c = px(20f)
        val v = Picto(this, nom, theme.or)
        v.measure(View.MeasureSpec.makeMeasureSpec(c, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(c, View.MeasureSpec.EXACTLY)); v.layout(0, 0, c, c)
        val bmp = android.graphics.Bitmap.createBitmap(c, c, android.graphics.Bitmap.Config.ARGB_8888)
        v.draw(android.graphics.Canvas(bmp))
        return android.graphics.drawable.BitmapDrawable(resources, bmp)
    }

    private fun entite(id: String): WidgetsHa.Entite? = catalogue?.firstOrNull { it.entityId == id } ?: WidgetsHaStock.entite(this, id)

    private fun construire() {
        contenu.removeAllViews()
        val mx = px(if (tele) 48f else 18f)
        contenu.setPadding(mx, px(if (tele) 28f else 36f), mx, px(28f))
        contenu.addView(texte(if (idExistant != null) getString(R.string.editeurwidget_modifier) else getString(R.string.editeurwidget_nouveau), 24f, theme.encre, Polices.outfit(this)))
        contenu.addView(texte(getString(R.string.editeurwidget_etapes), 13f, theme.encre2).apply { setPadding(0, px(4f), 0, 0) })

        // L'aperçu, en tête : on voit le widget changer.
        section(getString(R.string.editeurwidget_apercu))
        val cadre = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        apercu = cadre
        contenu.addView(cadre, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        peindreApercu()

        // 1. L'entité (ou les entités d'une mini-carte ; les enceintes d'un lecteur, 1.81.0).
        val mini = style == WidgetsHa.MINI_CARTE || style == WidgetsHa.LECTEUR
        section(if (style == WidgetsHa.LECTEUR) getString(R.string.editeurwidget_enceintes) else if (mini) getString(R.string.editeurwidget_entites) else getString(R.string.editeurwidget_entite))
        when {
            catalogue == null && erreur.isEmpty() -> contenu.addView(texte(getString(R.string.editeurwidget_chargement), 14f, theme.encre2))
            catalogue == null -> {
                contenu.addView(texte(erreur, 14f, theme.pourpre))
                contenu.addView(Ui.boutonSecondaire(this, getString(R.string.editeurwidget_reessayer)) { erreur = ""; construire(); charger() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(8f) })
            }
            catalogue!!.isEmpty() -> contenu.addView(texte(getString(R.string.editeurwidget_aucune_entite), 14f, theme.encre2))
            else -> entites(mini)
        }
        if (choisies.isEmpty()) { boutons(); return }

        // 2. Le style, parmi ceux du domaine.
        section(getString(R.string.editeurwidget_style))
        val premier = entite(choisies.first())
        val styles = premier?.let { WidgetsHa.stylesProposes(it) } ?: listOf(style)
        choixUnique(getString(R.string.editeurwidget_style), styles.map { it to WidgetsHa.nomStyle(it) }, style, "ew_style") { changerStyle(it) }
        if (style == WidgetsHa.GRAPHIQUE) {
            // Deux choix distincts (avant : quatre étiquettes mêlées) : la forme, puis la période.
            choixUnique(getString(R.string.editeurwidget_style), listOf("ligne" to getString(R.string.editeurwidget_ligne), "barres" to getString(R.string.editeurwidget_barres)),
                variante, "ew_variante") { variante = it; construire() }
            choixUnique(getString(R.string.whc_periode), listOf("24h" to Tr.duree(24 * 60), "7j" to resources.getQuantityString(R.plurals.commun_lot2_jours, 7, 7)),
                periode, "ew_periode") { periode = it; construire() }
        }

        // 1.88.0 (l'auteur : « je dois avoir le choix du détail ou pas ») : valeur seule, détail, ou les deux ; par défaut, comme
        // réglé dans Home Assistant. Le détail : éléments d'une liste (todo) ou attributs de l'entité.
        if (style == WidgetsHa.TUILE) {
            section(getString(R.string.editeurwidget_affichage))
            choixUnique(getString(R.string.editeurwidget_affichage), listOf(WidgetsHa.AFF_HA to R.string.editeurwidget_aff_ha, WidgetsHa.AFF_VALEUR to R.string.editeurwidget_aff_valeur,
                WidgetsHa.AFF_DETAIL to R.string.editeurwidget_aff_detail, WidgetsHa.AFF_VALEUR_DETAIL to R.string.editeurwidget_aff_valeur_detail
            ).map { (a, t) -> a to getString(t) }, affichage, "ew_affichage") { affichage = it; construire(); brancherFlux() }
            // 1.91.2 (retour d'essai) : le contenu (le détail) défile dans le widget, ou montre ce qui tient puis « Voir tout » ; et sa
            // hauteur maximale. Par défaut : défilement, hauteur moyenne.
            section(getString(R.string.editeurwidget_contenu))
            choixUnique(getString(R.string.editeurwidget_contenu), listOf("oui" to getString(R.string.editeurwidget_defilement_oui), "non" to getString(R.string.editeurwidget_defilement_non)),
                if (defilement) "oui" else "non", "ew_defilement") { defilement = it == "oui"; construire() }
            section(getString(R.string.editeurwidget_hauteur))
            choixUnique(getString(R.string.editeurwidget_hauteur), listOf(WidgetsHa.HAUTEUR_COMPACTE to R.string.editeurwidget_hauteur_compacte, WidgetsHa.HAUTEUR_MOYENNE to R.string.editeurwidget_hauteur_moyenne,
                WidgetsHa.HAUTEUR_GRANDE to R.string.editeurwidget_hauteur_grande
            ).map { (h, t) -> h to getString(t) }, hauteur, "ew_hauteur") { hauteur = it; construire() }
        }
        if (style in WidgetsHa.STYLES_MISE_EN_PAGE) {
            // 1.91.3 (retour d'essai) : la mise en page — la même que dans l'onglet Widgets HA, pour tous les appareils.
            val m = miseEnPage ?: premier?.miseEnPage ?: WidgetsHa.MiseEnPage()
            fun choisir(n: WidgetsHa.MiseEnPage) { miseEnPage = n; construire() }
            section(getString(R.string.editeurwidget_mep))
            contenu.addView(texte(getString(R.string.editeurwidget_mep_aide), 12f, theme.encre3).apply { setPadding(px(4f), 0, 0, px(4f)) })
            section(getString(R.string.editeurwidget_mep_h))
            choixUnique(getString(R.string.editeurwidget_mep_h), listOf(WidgetsHa.GAUCHE to R.string.editeurwidget_mep_gauche, WidgetsHa.CENTRE to R.string.editeurwidget_mep_centre,
                WidgetsHa.DROITE to R.string.editeurwidget_mep_droite).map { (a, t) -> a to getString(t) }, m.alignH, "ew_mep_h") { choisir(m.copy(alignH = it)) }
            section(getString(R.string.editeurwidget_mep_v))
            choixUnique(getString(R.string.editeurwidget_mep_v), listOf(WidgetsHa.HAUT to R.string.editeurwidget_mep_haut, WidgetsHa.CENTRE to R.string.editeurwidget_mep_milieu,
                WidgetsHa.BAS to R.string.editeurwidget_mep_bas).map { (a, t) -> a to getString(t) }, m.alignV, "ew_mep_v") { choisir(m.copy(alignV = it)) }
            section(getString(R.string.editeurwidget_mep_taille))
            choixUnique(getString(R.string.editeurwidget_mep_taille), listOf(WidgetsHa.REMPLIR to R.string.editeurwidget_mep_remplir, "s" to R.string.editeurwidget_mep_s,
                "m" to R.string.editeurwidget_mep_m, "l" to R.string.editeurwidget_mep_l, "xl" to R.string.editeurwidget_mep_xl).map { (a, t) -> a to getString(t) },
                m.taille, "ew_mep_taille") { choisir(m.copy(taille = it)) }
            // Titre et icône : deux cases indépendantes (choix multiple, 2 options).
            contenu.addView(Ui.choixMultiple(this, getString(R.string.editeurwidget_mep), listOf(ChoixAdaptatif.Option("titre", getString(R.string.editeurwidget_mep_titre)),
                ChoixAdaptatif.Option("icone", getString(R.string.editeurwidget_mep_icone))), setOfNotNull("titre".takeIf { m.titre }, "icone".takeIf { m.icone }), theme, t(14f)) { l ->
                choisir(m.copy(titre = "titre" in l, icone = "icone" in l))
            }.apply { tag = "ew_mep_montrer" })
        }

        // 1.90.0 (l'auteur : « accéder directement à la carte ou au dashboard ») : ce que fait un toucher. Détails et vues :
        // seulement si Home Assistant le permet à cet appareil (sinon le choix n'est pas proposé).
        section(getString(R.string.editeurwidget_toucher))
        val ouvrir = premier?.ouvrir == true
        choixUnique(getString(R.string.editeurwidget_toucher), listOf(WidgetsHa.TOUCHER_HA to R.string.editeurwidget_toucher_ha, WidgetsHa.TOUCHER_ROLE to R.string.editeurwidget_toucher_role,
            WidgetsHa.TOUCHER_DETAILS to R.string.editeurwidget_toucher_details, WidgetsHa.TOUCHER_VUE to R.string.editeurwidget_toucher_vue,
            WidgetsHa.TOUCHER_RIEN to R.string.editeurwidget_toucher_rien
        ).filter { (t, _) -> ouvrir || (t != WidgetsHa.TOUCHER_DETAILS && t != WidgetsHa.TOUCHER_VUE) || t == toucher }
            .map { (t, s) -> t to getString(s) }, toucher, "ew_toucher") { toucher = it; construire() }
        if (toucher == WidgetsHa.TOUCHER_VUE) {
            val vues = WidgetsHaStock.vuesHa(this)
            if (vues.isNotEmpty()) contenu.addView(pastilleChoix(getString(R.string.editeurwidget_toucher_choisir), false) {
                Ui.dialogue(this).setTitle(R.string.editeurwidget_toucher_choisir)
                    .setItems(vues.map { "${it.first}\n${it.second}" }.toTypedArray()) { _, k -> cheminVue = vues[k].second; construire() }
                    .setNegativeButton(R.string.commun_annuler, null).show()
            }.apply { (layoutParams as? LinearLayout.LayoutParams)?.topMargin = px(8f) })
            val champChemin = Ui.champ(this, getString(R.string.editeurwidget_toucher_chemin), cheminVue).apply {
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
                setTextColor(theme.encre); setHintTextColor(theme.encre3); background = fondCarte(theme.fond, rayon = 14f)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: Editable?) { cheminVue = s?.toString()?.trim().orEmpty() }
                })
            }
            contenu.addView(champChemin)
            if (cheminVue.isNotEmpty() && WidgetsHa.cheminSur(cheminVue) == null)
                contenu.addView(texte(getString(R.string.editeurwidget_toucher_chemin_faux), 12f, theme.pourpre).apply { setPadding(px(4f), px(4f), 0, 0) })
        }
        if (toucher == WidgetsHa.TOUCHER_DETAILS || toucher == WidgetsHa.TOUCHER_VUE)
            contenu.addView(texte(getString(R.string.editeurwidget_toucher_aide), 12f, theme.encre3).apply { setPadding(px(4f), px(4f), 0, 0) })

        // 3. La taille, parmi celles permises pour ce style.
        section(getString(R.string.editeurwidget_taille))
        val tailles = WidgetsHa.taillesPermises(style).sortedWith(compareBy({ it.first * it.second }, { it.first }))
        choixUnique(getString(R.string.editeurwidget_taille), tailles.map { "${it.first}x${it.second}" to "${it.first} × ${it.second}" }, "${taille.first}x${taille.second}", "ew_taille") { k ->
            tailles.firstOrNull { "${it.first}x${it.second}" == k }?.let { taille = it; construire() }
        }

        // 4. Libellé et icône.
        section(getString(R.string.tableauediteur_libelle))
        val champ = Ui.champ(this, premier?.nom ?: getString(R.string.tableauediteur_libelle), libelle).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setTextColor(theme.encre); setHintTextColor(theme.encre3); background = fondCarte(theme.fond, rayon = 14f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { libelle = s?.toString()?.trim().orEmpty(); peindreApercu() }
            })
        }
        contenu.addView(champ)
        contenu.addView(texte(getString(R.string.editeurwidget_vide_nom), 12f, theme.encre3).apply { setPadding(px(4f), px(4f), 0, 0) })
        section(getString(R.string.editeurwidget_icone))
        // 15-10 (règle UX de l'auteur) : les pictos (une trentaine) dans une liste avec leur dessin ; le champ montre celui choisi.
        // « Automatique » : l'icône donnée dans Home Assistant (rapprochée d'un picto de Vision), sinon celle du domaine.
        val pictos = listOf("", "aucune") + WidgetsHa.PICTOS
        val nomAuto = if (premier != null) WidgetsHa.picto(WidgetsHa.Reglage("", listOf(premier.entityId), style, 1, 1), premier) ?: "etoile" else "etoile"
        choixUnique(getString(R.string.editeurwidget_icone), pictos.map { it to WidgetsHa.nomPicto(it) }, icone, "ew_icone",
            pictos.filter { it != "aucune" }.associateWith { dessinPicto(if (it == "") nomAuto else it) }) { icone = it; construire() }
        boutons()
    }

    private fun boutons() {
        val r = Ui.rangeeBoutons(this).apply { setPadding(0, px(24f), 0, 0) }
        r.addView(Ui.boutonSecondaire(this, getString(R.string.commun_lot2_annuler)) { finish() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = px(8f) })
        val pret = choisies.isNotEmpty() && (style != WidgetsHa.MINI_CARTE || choisies.size >= 2)
        r.addView(Ui.boutonPrimaire(this, if (idExistant != null) getString(R.string.editeurwidget_enregistrer) else getString(R.string.editeurwidget_poser)) { poser() }.apply { isEnabled = pret; alpha = if (pret) 1f else 0.45f },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        contenu.addView(r)
        if (tele) contenu.addView(texte(getString(R.string.editeurwidget_tele_lecture_seule), 13f, theme.encre3).apply { setPadding(0, px(10f), 0, 0) })
    }

    /** La liste des entités (recherche par nom ou identifiant), repliée une fois le choix fait. */
    private fun entites(mini: Boolean) {
        val cat = catalogue ?: return
        if (choisies.isNotEmpty()) {
            choisies.forEach { id ->
                val e = entite(id)
                val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; background = fondCarte(); setPadding(px(14f), px(10f), px(10f), px(10f)) }
                if (e != null) r.addView(Picto(this, WidgetsHa.pictoAuto(e.domaine, e.deviceClass), theme.or), LinearLayout.LayoutParams(px(20f), px(20f)).apply { rightMargin = px(10f) })
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                col.addView(texte(e?.nom ?: id, 15f, theme.encre, Polices.demiGras(this)))
                col.addView(texte(id + (e?.let { "  ·  " + WidgetsHa.valeurAffichee(FluxWidgets.etats[id]?.etat ?: it.etat, it.unite, it.domaine, it.deviceClass) } ?: ""), 12f, theme.encre2))
                r.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (mini) r.addView(pastilleChoix(getString(R.string.editeurwidget_retirer), false) { choisies.remove(id); construire(); brancherFlux() })
                contenu.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = px(6f) })
            }
            if (!listeOuverte) {
                val l = if (mini) (if (choisies.size < 4) (if (style == WidgetsHa.LECTEUR) getString(R.string.editeurwidget_ajouter_enceinte) else getString(R.string.editeurwidget_ajouter_entite)) else "") else getString(R.string.editeurwidget_changer_entite)
                if (l.isNotEmpty()) contenu.addView(Ui.boutonSecondaire(this, l) { listeOuverte = true; construire() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                return
            }
        }
        val champ = Ui.champ(this, getString(R.string.editeurwidget_rechercher), recherche).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setTextColor(theme.encre); setHintTextColor(theme.encre3); background = fondCarte(theme.fond, rayon = 14f)
        }
        contenu.addView(champ)
        val liste = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, px(6f), 0, 0) }
        listeEntites = liste
        contenu.addView(liste)
        remplirListe(cat, mini)
        champ.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { recherche = s?.toString().orEmpty(); remplirListe(cat, mini) }
        })
    }

    private fun remplirListe(cat: List<WidgetsHa.Entite>, mini: Boolean) {
        val liste = listeEntites ?: return
        liste.removeAllViews()
        val q = recherche.trim().lowercase(Locale.FRANCE)
        val trouvees = cat.filter { q.isEmpty() || it.nom.lowercase(Locale.FRANCE).contains(q) || it.entityId.contains(q) }.filter { it.entityId !in choisies }
            .filter { style != WidgetsHa.LECTEUR || choisies.isEmpty() || it.domaine == "media_player" }
        if (trouvees.isEmpty()) { liste.addView(texte(getString(R.string.editeurwidget_aucune_correspond), 13f, theme.encre3)); return }
        trouvees.take(80).forEach { e ->
            val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(px(12f), px(10f), px(12f), px(10f)); isFocusable = true; isClickable = true; background = fondCarte() }
            r.setOnFocusChangeListener { v, a -> v.background = if (a) fondCarte(bord = theme.or, epaisseur = 2f) else fondCarte() }
            r.addView(Picto(this, WidgetsHa.pictoAuto(e.domaine, e.deviceClass), theme.or), LinearLayout.LayoutParams(px(20f), px(20f)).apply { rightMargin = px(10f) })
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(texte(e.nom, 15f, theme.encre, Polices.moyen(this)))
            col.addView(texte(e.entityId + "  ·  " + WidgetsHa.valeurAffichee(e.etat, e.unite, e.domaine, e.deviceClass) + (if (e.actions.isEmpty()) "  ·  " + getString(R.string.editeurwidget_lecture_seule) else ""), 12f, theme.encre2))
            r.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            r.setOnClickListener { choisir(e, mini) }
            liste.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = px(6f) })
        }
        if (trouvees.size > 80) liste.addView(texte(getString(R.string.editeurwidget_autres, trouvees.size - 80), 12f, theme.encre3))
    }

    private fun choisir(e: WidgetsHa.Entite, mini: Boolean) {
        if (mini) {
            if (choisies.size < 4) choisies.add(e.entityId)
            listeOuverte = style == WidgetsHa.MINI_CARTE && choisies.size < 2
        } else {
            choisies = arrayListOf(e.entityId)
            listeOuverte = false
            val styles = WidgetsHa.stylesProposes(e)
            // Nouvelle entité : le premier style proposé pour son domaine (interrupteur pour une lampe…) ; en modification,
            // le style choisi reste s'il convient encore.
            if (idExistant == null || style !in styles || style == WidgetsHa.MINI_CARTE || style == WidgetsHa.LECTEUR) changerStyle(styles.first(), reconstruire = false)
        }
        recherche = ""
        construire()
        brancherFlux()
    }

    private fun changerStyle(s: String, reconstruire: Boolean = true) {
        val avant = style
        style = s
        val permises = WidgetsHa.taillesPermises(s)
        if (taille !in permises) taille = permises.first()
        if (s == WidgetsHa.MINI_CARTE && avant != s) listeOuverte = choisies.size < 2
        if (avant == WidgetsHa.MINI_CARTE && s != avant && choisies.size > 1) choisies = arrayListOf(choisies.first())
        if (avant == WidgetsHa.LECTEUR && s != avant && choisies.size > 1) choisies = arrayListOf(choisies.first())
        if (reconstruire) { construire(); brancherFlux() }
    }

    private fun reglageCourant(): WidgetsHa.Reglage? {
        if (choisies.isEmpty()) return null
        return WidgetsHa.Reglage(idExistant ?: "apercu", choisies.toList(), style, taille.first, taille.second, libelle, icone, variante, periode, affichage,
            toucher, if (toucher == WidgetsHa.TOUCHER_VUE) cheminVue else "", defilement, hauteur, apercu = miseEnPage)
    }

    /** L'aperçu à la vraie taille de la case (réduit si l'écran est plus étroit). */
    private fun peindreApercu() {
        val cadre = apercu ?: return
        cadre.removeAllViews()
        val r = reglageCourant() ?: WidgetsHa.Reglage("apercu", listOf(WidgetsHa.exemple(style).entityId), style, taille.first, taille.second, libelle, icone, variante, periode, affichage)
        val exemple = hors || catalogue == null || choisies.isEmpty()
        val v = VueWidgetHa(this, theme, r, lectureSeule = true, exemple = exemple, details = null).apply { exempleSiEchec = true }
        val largeur = celluleL * r.w; val hauteur = celluleH * r.h
        val dispo = resources.displayMetrics.widthPixels - px(if (tele) 96f else 36f)
        val f = if (largeur > dispo) dispo.toFloat() / largeur else 1f
        val boite = FrameLayout(this)
        boite.addView(v, FrameLayout.LayoutParams(largeur, hauteur))
        v.pivotX = 0f; v.pivotY = 0f; v.scaleX = f; v.scaleY = f
        cadre.addView(boite, LinearLayout.LayoutParams((largeur * f).toInt(), (hauteur * f).toInt()))
        val note = when {
            exemple && choisies.isNotEmpty() -> getString(R.string.editeurwidget_exemple_injoignable)
            exemple -> getString(R.string.editeurwidget_exemple_choisir)
            FluxWidgets.statut == FluxWidgets.Statut.EN_DIRECT -> getString(R.string.editeurwidget_en_direct)
            else -> ""
        }
        if (note.isNotEmpty()) cadre.addView(texte(note, 12f, theme.encre3).apply { gravity = Gravity.CENTER; setPadding(0, px(6f), 0, 0) })
    }

    private fun brancherFlux() {
        if (hors || choisies.isEmpty()) return
        val r = reglageCourant()
        val details = r?.let { x -> entite(x.entites.first())?.takeIf { e -> WidgetsHa.montreDetail(WidgetsHa.affichage(x, e)) }?.let { setOf(it.entityId) } }.orEmpty()
        FluxWidgets.demarrer(this, choisies.toSet(), details) { peindreApercu() }
    }

    private fun charger() {
        Thread {
            try {
                val l = WidgetsHaStock.recharger(this)
                main.post { catalogue = l; hors = false; construire(); brancherFlux() }
            } catch (e: Net.Echec) {
                main.post { erreur = WidgetsHa.messageErreur(e.code, e.corps?.optString("error").orEmpty()); hors = true; repli() }
            } catch (e: Exception) {
                android.util.Log.w("Vision", "Widgets HA : liste des entités injoignable", e)
                main.post { erreur = WidgetsHa.messageErreur(0, ""); hors = true; repli() }
            }
        }.also { it.isDaemon = true }.start()
    }

    /** Hors ligne : la liste gardée lors du dernier chargement, s'il y en a une. */
    private fun repli() {
        val gardee = WidgetsHaStock.catalogue(this).values.sortedBy { it.nom.lowercase(Locale.FRANCE) }
        if (gardee.isNotEmpty()) catalogue = gardee
        construire()
    }

    private fun poser() {
        val r0 = reglageCourant() ?: return
        if (style == WidgetsHa.MINI_CARTE && choisies.size < 2) return
        val id = idExistant ?: WidgetsHaStock.nouvelId()
        val r = r0.copy(id = id)
        WidgetsHaStock.ecrire(this, r)
        val cle = WidgetsHa.cle(id)
        if (idExistant == null) Accueil.ajouterALaGrille(this, cle, r.w, r.h)
        else Accueil.disposition(this).firstOrNull { it.cle == cle }?.let { p -> if (p.w != r.w || p.h != r.h) Accueil.redimensionner(this, cle, r.w, r.h) }
        setResult(RESULT_OK)
        // 1.91.3 : une mise en page changée part dans Home Assistant (une seule configuration) ; un refus est dit.
        val m = miseEnPage
        val e = entite(choisies.first())
        if (m == null || e == null || m == e.miseEnPage) { finish(); return }
        Thread {
            val refus = WidgetsHaStock.reglerMiseEnPage(this, e.entityId, m)
            runOnUiThread {
                if (refus != null) Ui.toast(this, refus, genre = OeilModele.Genre.ERREUR)
                finish()
            }
        }.also { it.isDaemon = true }.start()
    }
}

/** Des enfants posés en rangées qui passent à la ligne quand la largeur manque (les pastilles de choix). */
class Pastilles(ctx: Context, private val ecart: Int) : ViewGroup(ctx) {
    override fun onMeasure(largeurSpec: Int, hauteurSpec: Int) {
        val largeur = MeasureSpec.getSize(largeurSpec) - paddingLeft - paddingRight
        var x = 0; var y = 0; var ligne = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i); if (c.visibility == View.GONE) continue
            c.measure(MeasureSpec.makeMeasureSpec(largeur, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + c.measuredWidth > largeur) { x = 0; y += ligne + ecart; ligne = 0 }
            x += c.measuredWidth + ecart; ligne = maxOf(ligne, c.measuredHeight)
        }
        setMeasuredDimension(MeasureSpec.getSize(largeurSpec), y + ligne + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val largeur = r - l - paddingLeft - paddingRight
        var x = 0; var y = 0; var ligne = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i); if (c.visibility == View.GONE) continue
            if (x > 0 && x + c.measuredWidth > largeur) { x = 0; y += ligne + ecart; ligne = 0 }
            c.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + c.measuredWidth, paddingTop + y + c.measuredHeight)
            x += c.measuredWidth + ecart; ligne = maxOf(ligne, c.measuredHeight)
        }
    }
}
