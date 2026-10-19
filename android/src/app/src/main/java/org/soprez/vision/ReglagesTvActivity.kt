// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.app.UiModeManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Les réglages Vision de l'appareil : tout se décide ici, sur l'appareil.
 *
 * Tableaux de l'écran de veille : ceux de l'appli, ceux composés dans Home
 * Assistant et ceux composés ici (bouton « + Tableau »), chacun avec son
 * interrupteur et sa durée. Source du son, mode nuit, thème, couleurs à la
 * carte, bulle, applications visibles.
 *
 * Au pavé : chaque interrupteur, chaque − et chaque + est une case à part,
 * gauche et droite passent de l'une à l'autre, puis à la colonne de droite.
 * Sur un téléphone, une seule colonne.
 */
class ReglagesTvActivity : Activity(), ZoneSure.Geree {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var cfg: Config
    private var theme: Theme = Themes.liste[0]
    private var reglages: JSONObject? = null
    private var tableauxHA: JSONArray? = null
    private lateinit var racine: ScrollView
    private lateinit var contenu: LinearLayout
    private var focusTag: String? = null
    /** Intégration du 15/10 : rubrique demandée à l'ouverture (télé) ; tant qu'aucune touche n'est pressée, un focus donné
     *  par Android (premier élément du menu) ne la remplace pas. */
    private var sectionImposee = false
    private var enCharge = false
    private val tele by lazy { estTele(this) }

    // 1.84.0 (point 21, maquette docs/maquettes/tv-reglages.html) : sur la télé, un menu de sections à gauche et
    // une page à droite ; une action principale en haut de chaque page ; les réglages des décideurs derrière le code.
    private lateinit var cadreTv: LinearLayout
    private lateinit var rail: LinearLayout
    private var sectionTv = "veille"
    private var sousPage: String? = null
    private var decideursJusqua = 0L
    /**
     * 15-10 (l'auteur : page presque obsolète) : sur le téléphone et la tablette, cette page ne montre plus qu'une section, celle
     * demandée à l'ouverture — l'écran de veille (tablette) ou la musique ; ses autres réglages ont rejoint les menus de
     * l'appli (Couleurs, Disposition, Réglages : ReglagesPartages). Sans section : on va aux Réglages de l'appli.
     */
    private var sectionTelephone: String? = null
    /** Pendant qu'on rebâtit, les changements de focus (vues retirées) ne doivent pas relancer la construction. */
    private var enConstruction = false

    companion object {
        /** La section à montrer à l'ouverture : « s_apparence », « s_accueil », « s_veille » ou « s_musique ». */
        const val EXTRA_SECTION = "section"
        fun ouvrir(ctx: Context, section: String) = ctx.startActivity(Intent(ctx, ReglagesTvActivity::class.java).putExtra(EXTRA_SECTION, section))

        fun estTele(ctx: Context): Boolean {
            val ui = ctx.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
            return ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION || ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Config(this)
        if (tele) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        racine = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        contenu = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        racine.addView(contenu)
        if (tele) {
            cadreTv = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rail = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            // Le menu prend la largeur de son plus long nom (jamais un mot coupé), au moins un quart de l'écran.
            // 1.93.06 : plus le bord réglé (« Ajuster l'écran ») qu'il prend à gauche : les noms gardent la même place.
            rail.minimumWidth = (dm.widthPixels * 0.22f).toInt() + ZoneSure.x(this, 0f) - ZoneSure.confortX(this)
            cadreTv.addView(rail, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
            cadreTv.addView(racine, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            setContentView(cadreTv)
            if (!Modules.actif(this, Modules.VEILLE)) sectionTv = "affichage"
        } else { setContentView(racine); sectionTelephone = intent?.getStringExtra(EXTRA_SECTION) }
        charger()
    }

    override fun onResume() {
        super.onResume(); appliquerTheme()
        // Intégration du 15/10 : sur la télé, la section demandée est posée AVANT la première construction. Construite
        // d'abord, la page donnait le focus au premier élément du menu (« Accueil »), dont l'écouteur changeait la
        // rubrique ; selon le moment, ce changement arrivait après la section demandée et l'emportait (application
        // déjà lancée : Paramètres › Veille ou Musique s'ouvraient sur Accueil).
        val demandee = if (tele) intent?.getStringExtra(EXTRA_SECTION) else null
        if (demandee != null) {
            intent.removeExtra(EXTRA_SECTION)
            sectionTv = when (demandee) { "s_apparence" -> "affichage"; "s_accueil" -> "accueil"; "s_veille" -> "veille"; ReglagesMusique.SECTION -> "musique"; "parametres" -> "parametres"; else -> sectionTv }
            focusTag = "r_$sectionTv"; sousPage = null; sectionImposee = true
        }
        construire()
        NiveauSonore.assurer(this)  // le micro ne se lance qu'au premier plan (Android)
        if (demandee != null) { racine.post { trouver(rail, "r_$sectionTv")?.requestFocus() }; return }
        // 1.71.0 : ouvert depuis l'écran principal sur une section (Apparence, Accueil, Veille) : on y va directement.
        intent?.getStringExtra(EXTRA_SECTION)?.let { tag ->
            intent.removeExtra(EXTRA_SECTION); focusTag = tag
            racine.post { trouver(contenu, tag)?.let { v ->
                racine.smoothScrollTo(0, maxOf(0, haut(v) - px(24f)))
                // Le titre de section ne prend pas le focus : la première ligne dessous le prend.
                if (v.isFocusable) v.requestFocus() else v.focusSearch(View.FOCUS_DOWN)?.requestFocus()
            } }
        }
    }

    /** La position d'une vue dans le contenu défilant. */
    private fun haut(v: View): Int { var y = 0; var x: View? = v; while (x != null && x !== contenu) { y += x.top; x = x.parent as? View }; return y }

    private fun px(dp: Float) = Ui.dp(this, dp)
    private val dm get() = resources.displayMetrics
    private fun t(pxMaquette: Float): Float = if (tele) pxMaquette * (dm.heightPixels / dm.density) / 1080f else pxMaquette * 0.62f
    /** Lot 6 (APP-15) : sur télé, un texte de lecture (note, aide, valeur, titre de ligne) ne descend jamais sous [min] sp. */
    private fun tl(pxMaquette: Float, min: Float = 14f): Float = if (tele) maxOf(t(pxMaquette), min) else t(pxMaquette)

    private fun texte(s: String, taille: Float, couleur: Int, police: android.graphics.Typeface = Polices.texte(this), espacement: Float = 0f): TextView =
        Ui.sansCesure(TextView(this).apply { text = s; textSize = taille; setTextColor(couleur); typeface = police; letterSpacing = espacement })

    private fun fondCarte(rayonDp: Float, couleur: Int = theme.carte, bord: Int = theme.carteBord, epaisseurDp: Float = 1f): GradientDrawable =
        GradientDrawable().apply { cornerRadius = px(rayonDp).toFloat(); setColor(couleur); setStroke(px(epaisseurDp), bord) }

    private fun section(s: String): TextView = texte(s.uppercase(Locale.getDefault()), tl(18f, 13f), theme.encre2, Polices.gras(this), 0.22f).apply { setPadding(0, px(16f), 0, px(8f)) }

    private fun focusable(v: View, tag: String, rayon: Float = 18f) {
        v.isFocusable = true; v.isClickable = true; v.tag = tag
        v.background = fondCarte(rayon)
        v.setOnFocusChangeListener { x, a -> x.background = if (a) fondCarte(rayon, bord = theme.or, epaisseurDp = 1.5f) else fondCarte(rayon); if (a) focusTag = tag }
    }

    private fun trouver(v: View, tag: String): View? {
        if (v.tag == tag) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) trouver(v.getChildAt(i), tag)?.let { return it }
        return null
    }

    private fun repeindre() { construire(); focusTag?.let { tag -> racine.post { (trouver(contenu, tag) ?: if (tele) trouver(rail, tag) else null)?.requestFocus() } } }

    /** Retour à la télécommande : d'une sous-page à sa section, de la page au menu, puis on ferme. */
    @Deprecated("Deprecated in Java")
    /**
     * 1.86.7 (l'auteur : « quand ça descend, le focus entoure Accueil ») : en bas (ou en haut) d'une page, la croix ne fait
     * plus sortir le focus vers le menu de gauche (il y sautait de section en section) ; la page défile seulement.
     * Gauche ramène au menu, comme avant.
     */
    override fun dispatchKeyEvent(ev: android.view.KeyEvent): Boolean {
        val code = ev.keyCode
        if (ev.action == android.view.KeyEvent.ACTION_DOWN) sectionImposee = false
        if (::cadreTv.isInitialized && (code == android.view.KeyEvent.KEYCODE_DPAD_DOWN || code == android.view.KeyEvent.KEYCODE_DPAD_UP)) {
            val f = currentFocus
            var p: android.view.ViewParent? = f?.parent
            while (p != null && p !== racine) p = p.parent
            if (f != null && p === racine) {
                val sens = if (code == android.view.KeyEvent.KEYCODE_DPAD_DOWN) View.FOCUS_DOWN else View.FOCUS_UP
                if (android.view.FocusFinder.getInstance().findNextFocus(racine, f, sens) == null) {
                    if (ev.action == android.view.KeyEvent.ACTION_DOWN) racine.smoothScrollBy(0, (if (sens == View.FOCUS_DOWN) 1 else -1) * racine.height / 3)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(ev)
    }

    override fun onBackPressed() {
        if (tele && sousPage != null) { val p = sousPage; sousPage = null; focusTag = "sp_$p"; repeindre(); return }
        val f = currentFocus
        if (tele && f != null && !estDans(f, rail)) { trouver(rail, "r_$sectionTv")?.requestFocus(); return }
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun estDans(v: View, parent: View): Boolean { var x: View? = v; while (x != null) { if (x === parent) return true; x = x.parent as? View }; return false }

    // ------------------------------------------------------------------ construction

    private fun construire() {
        if (tele) { construireTele(); return }
        val sansVeillePage = Identite.telephone(this) || !Modules.actif(this, Modules.VEILLE)
        val seule = sectionTelephone.takeIf { it == ReglagesMusique.SECTION || (it == "s_veille" && !sansVeillePage) }
        if (seule == null) { SetupActivity.ouvrir(this, Espaces.REGLAGES); finish(); return }
        contenu.removeAllViews()
        racine.setBackgroundColor(theme.fond)
        if (!tele) { window.navigationBarColor = theme.fond; window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or (if (!theme.sombre) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0) }
        val mx = if (tele) ZoneSure.x(this@ReglagesTvActivity, 0.05f) else px(18f); val my = if (tele) ZoneSure.y(this@ReglagesTvActivity, 0.05f) else px(40f)
        contenu.setPadding(mx, my, mx, my)

        // La marque au milieu en haut, puis le titre et la durée d'un tour.
        val marque = LinearLayout(this).apply { gravity = Gravity.CENTER }
        marque.addView(ImageView(this).apply { setImageResource(R.drawable.ic_vision) }, LinearLayout.LayoutParams(px(t(40f)), px(t(40f))))
        marque.addView(texte("VISION", t(22f), theme.or, Polices.gras(this), 0.28f).apply { setPadding(px(8f), 0, 0, 0) })
        contenu.addView(marque, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val tete = LinearLayout(this).apply { gravity = Gravity.BOTTOM; setPadding(0, px(10f), 0, 0) }
        tete.addView(texte(getString(if (seule == ReglagesMusique.SECTION) R.string.musrech_section else R.string.reglages_section_veille), t(44f), theme.encre, Polices.outfit(this)).apply { includeFontPadding = false }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val liste = listeTableaux()
        var total = 0; var actifs = 0
        liste.forEach { if (it.actif) { total += it.duree; actifs++ } }
        // Modules coupés dans Home Assistant : la veille (tous ses réglages), ou seulement ses tableaux.
        val sansVeille = Identite.telephone(this) || !Modules.actif(this, Modules.VEILLE)
        val tableaux = Modules.actif(this, Modules.VEILLE_TABLEAUX)
        if (!sansVeille && tableaux) tete.addView(texte(if (liste.isEmpty()) "…" else resources.getQuantityString(R.plurals.reglagestv_un_tour, actifs, total / 60, String.format(Locale.getDefault(), "%02d", total % 60), actifs), t(22f), theme.encre2))
        contenu.addView(tete)

        val colonnes = LinearLayout(this).apply { orientation = if (tele) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL; setPadding(0, px(10f), 0, 0) }
        contenu.addView(colonnes)
        val gauche = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val droite = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (tele) {
            // Sans veille, la colonne de gauche resterait vide : les réglages prennent toute la largeur.
            if (!sansVeille) colonnes.addView(gauche, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.25f).apply { rightMargin = px(24f) })
            colonnes.addView(droite, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        } else { colonnes.addView(gauche); colonnes.addView(droite) }

        // --- Écran de veille : ce qu'il montre, comment, quand -------------------------------
        // Sans objet sur un téléphone : masqué (matrice réglages × type d'appareil, Vision 1.72.0).
        if (!sansVeille && seule == "s_veille") {
        // l'auteur (15/10) : UNE section « Veille » pour tous les réglages de la veille — ce qu'elle montre, sa musique
        // (son, playlist de Music Assistant et ses favoris), les cartes et le voyant des ouvertures.
        gauche.addView(section(getString(R.string.reglages_section_veille)).apply { tag = "s_veille" })
        if (tableaux) {
        if (liste.isEmpty()) gauche.addView(texte(getString(R.string.choixmusique_chargement), t(20f), theme.encre3))
        liste.forEach { gauche.addView(rangTableau(it), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = px(8f) }) }
        gauche.addView(texte(getString(R.string.reglagestv_tableaux_explication), tl(17f), theme.encre3).apply { setPadding(px(4f), px(2f), 0, px(10f)) })
        val releve = Local.listeWebA(this)
        gauche.addView(ligne(getString(R.string.reglagestv_actualiser), if (enCharge) getString(R.string.reglagestv_releve_en_cours) else if (releve > 0) getString(R.string.reglagestv_releve_a, Tr.heure(releve)) else getString(R.string.reglagestv_maintenant), "actualiser") { charger() })
        // Refonte lot 3 (APP-18) : les cartes de Home Assistant n'ont pas d'objet sur un appareil non relié.
        if (cfg.inscrit) {
        val web = Local.veilleWeb(this)
        gauche.addView(ligne(getString(R.string.reglagestv_cartes_affichees), if (web) getString(R.string.reglagestv_celles_ha) else getString(R.string.reglagestv_dessin_simplifie), "veille_web") { Local.poserVeilleWeb(this, !web); repeindre() })
        gauche.addView(ligne(getString(R.string.horloge_style_veille), StylesHorloge.nom(this, Local.styleHorlogeVeille(this)), "horloge_veille") { choisirHorlogeVeille() })
        // Dépend d'un réglage qui change : grisé avec la raison plutôt que masqué.
        val sansWeb = if (web) null else getString(R.string.reglagestv_avec_cartes_ha)
        val styles = Local.STYLES
        val style = Local.styleCartes(this)
        gauche.addView(ligne(getString(R.string.reglagestv_style_cartes), styles.first { it.first == style }.second, "style", sansWeb) { Local.poserStyleCartes(this, styles[(styles.indexOfFirst { it.first == style } + 1) % styles.size].first); repeindre() })
        gauche.addView(ligne(getString(R.string.reglagestv_fond_cartes), if (Local.fondCartes(this)) getString(R.string.commun_lot2_oui) else getString(R.string.messagesnotif_non), "fond_cartes", sansWeb) { Local.poserFondCartes(this, !Local.fondCartes(this)); repeindre() })
        gauche.addView(ligne(getString(R.string.reglagestv_contour_cartes), if (Local.contourCartes(this)) getString(R.string.commun_lot2_oui) else getString(R.string.messagesnotif_non), "contour_cartes", sansWeb) { Local.poserContourCartes(this, !Local.contourCartes(this)); repeindre() })
        gauche.addView(ligne(getString(R.string.reglagestv_cartes_animees), if (Local.animerCartes(this)) getString(R.string.commun_lot2_oui) else getString(R.string.messagesnotif_non), "animer_cartes", sansWeb) { Local.poserAnimerCartes(this, !Local.animerCartes(this)); repeindre() })
        }
        }
        if (tele) {
            val delai = Local.delaiVeille(this)
            val libelle = libelleDelai(delai)
            gauche.addView(ligne(getString(R.string.reglagestv_demarre_apres), libelle, "delai") { choisirDelai() })
        }
        if (tele) {
            val vision = EconomiseurTv.plan(this).chemin == ReglesVeille.Chemin.DEJA_VISION
            gauche.addView(ligne(getString(R.string.reglagestv_economiseur_tele), EconomiseurTv.libelle(this), "economiseur") {
                EconomiseurTv.proposer(this) { repeindre() }
            })
            val repli = Local.delaiRepli(this)
            // « Seulement … » : la condition où la ligne s'applique.
            val sansObjet = when { vision -> getString(R.string.reglagestv_si_economiseur_pas_vision); Local.delaiVeille(this) > 0 -> getString(R.string.reglagestv_sans_delai); else -> null }
            gauche.addView(ligne(getString(R.string.reglagestv_veille_sur_accueil), ReglesVeille.libelleRepli(repli), "repli", sansObjet) { choisirRepli() })
        }
        gauche.addView(ligne(getString(R.string.reglagestv_applis_fond), resources.getQuantityString(R.plurals.reglagestv_choisies, Local.fond(this).size, Local.fond(this).size), "fond") { choisirFond() })
        gauche.addView(ligne(getString(R.string.reglagestv_voir_veille), getString(R.string.reglagestv_maintenant), "veille") { Veille.ouvrir(this, demande = true) })
        sousTitreSection(gauche, getString(R.string.reglages_veille_musique))
        lignesMusiqueVeille({ gauche.addView(it) }) { t -> gauche.addView(texte(t, tl(17f), theme.encre3).apply { setPadding(px(4f), 0, 0, px(6f)) }) }
        sousTitreSection(gauche, getString(R.string.reglages_veille_cartes))
        lignesCartesVeille({ gauche.addView(it) }) { t -> gauche.addView(texte(t, tl(17f), theme.encre3).apply { setPadding(px(4f), 0, 0, px(6f)) }) }
        }

        // 15-10 : Apparence, Son, Accueil, Vision et À propos ont rejoint les menus de l'appli (Couleurs, Disposition,
        // Réglages) ; ici ne reste que la musique, ouverte seule (depuis l'appli Musique ou les Réglages).
        if (seule == ReglagesMusique.SECTION) {
            droite.addView(section(getString(R.string.musrech_section)).apply { tag = ReglagesMusique.SECTION })
            ReglagesMusique.lignes(this, { titre, valeur, tag, action -> droite.addView(ligne(titre, valeur, tag) { action() }) }, { repeindre() }, { v -> droite.addView(v) }, theme, tl(18f, 14f))
        }

        contenu.addView(texte(if (tele && !sansVeille) getString(R.string.reglagestv_aide_tele) else if (tele) getString(R.string.reglagestv_retour_fermer) else "", maxOf(t(18f), 14f), theme.encre3).apply { setPadding(0, px(18f), 0, 0) })
            Ui.laisserDeborder(racine)
}

    // ------------------------------------------------------------------ télé : sections et pages

    private class SectionTv(val cle: String, val nom: String)

    /** 1.86.0 (retour d'essai) : les sections par ordre alphabétique, selon la langue de l'appareil (juste une fois traduit). */
    private val collator by lazy { java.text.Collator.getInstance(Locale.getDefault()).apply { strength = java.text.Collator.PRIMARY } }
    private fun <T> trier(l: List<T>, cle: (T) -> String): List<T> = l.sortedWith { a, b -> collator.compare(cle(a), cle(b)) }

    private fun sectionsTv(): List<SectionTv> {
        val l = ArrayList<SectionTv>()
        l.add(SectionTv("affichage", getString(R.string.reglagestv_affichage)))
        if (Modules.actif(this, Modules.VEILLE)) l.add(SectionTv("veille", getString(R.string.reglages_section_veille)))
        l.add(SectionTv("accueil", getString(R.string.reglagestv_accueil)))
        // La page Son ne garde que le son des messages (l'auteur, 15/10 : la musique de la veille est dans « Veille ») : sans messages, elle n'a plus d'objet.
        if (Modules.actif(this, Modules.MESSAGES)) l.add(SectionTv("son", getString(R.string.reglagestv_son_messages)))
        l.add(SectionTv("musique", getString(R.string.musrech_section)))
        // 1.86.0 (retour d'essai) : « Paramètres » regroupe l'appareil, la personne, la connexion et la version.
        l.add(SectionTv("parametres", getString(R.string.reglagestv_parametres)))
        l.add(SectionTv("decideurs", getString(R.string.reglagestv_decideurs_cadenas, Vocabulaire.Decideurs())))
        return trier(l) { it.nom.replace("🔒", "").trim() }
    }

    private fun decideursOuverts() = android.os.SystemClock.elapsedRealtime() < decideursJusqua

    private fun construireTele() {
        if (enConstruction) return
        enConstruction = true
        try { construireTeleSansGarde() } finally { enConstruction = false }
    }

    private fun construireTeleSansGarde() {
        cadreTv.setBackgroundColor(theme.fond)
        racine.setBackgroundColor(theme.fond)
        val sections = sectionsTv()
        if (sections.none { it.cle == sectionTv }) sectionTv = sections.first().cle
        // Le menu : ▲▼ choisit la section (la page suit), ► ou OK entre dans la page.
        rail.removeAllViews()
        rail.setBackgroundColor(Palette.melanger(theme.fond, theme.encre, 0.04f))
        rail.setPadding(px(16f) + ZoneSure.x(this@ReglagesTvActivity, 0f), ZoneSure.y(this@ReglagesTvActivity, 0.06f), px(12f), ZoneSure.y(this@ReglagesTvActivity, 0.05f))
        val marque = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(px(10f), 0, 0, px(14f)) }
        marque.addView(ImageView(this).apply { setImageResource(R.drawable.ic_vision) }, LinearLayout.LayoutParams(px(t(34f)), px(t(34f))))
        marque.addView(texte(getString(R.string.reglagestv_reglages_maj), t(18f), theme.encre3, Polices.gras(this), 0.18f).apply { setPadding(px(8f), 0, 0, 0) })
        rail.addView(marque)
        sections.forEach { sec ->
            val item = texte(sec.nom, t(24f), theme.encre2, Polices.moyen(this)).apply {
                setPadding(px(14f), px(12f), px(14f), px(12f))
            }
            focusable(item, "r_" + sec.cle, 14f)
            styleRail(item, false)
            item.setOnFocusChangeListener { x, a ->
                styleRail(x as TextView, a)
                if (a && !enConstruction && sectionImposee && sec.cle != sectionTv) {
                    // Focus donné par Android, pas par l'utilisateur : on rend le focus à la rubrique demandée.
                    x.post { trouver(rail, "r_$sectionTv")?.requestFocus() }
                } else if (a && !enConstruction) {
                    focusTag = "r_" + sec.cle
                    // 1.87.0 (« sursaut » vu par l'auteur) : changer de section ne rebâtit plus le menu. Avant, le menu était
                    // retiré puis refait : l'élément qui avait le focus disparaissait, Android le donnait un instant au
                    // premier élément (« Accueil »), puis on le rendait. Seule la page de droite change maintenant.
                    if (sectionTv != sec.cle) { sectionTv = sec.cle; sousPage = null; majRail(); construirePage() }
                }
            }
            item.setOnClickListener { premierDeLaPage()?.requestFocus() }
            rail.addView(item, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = px(4f) })
        }
        val espace = View(this)
        rail.addView(espace, LinearLayout.LayoutParams(1, 0, 1f))
        // Soutien (retour d'essai, photo de la télé) : dans l'espace vide du menu, en dernier, sans code.
        val carte = carteSoutien(t(84f))
        rail.addView(carte, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(8f) })
        // Lot 1 (A7, APP-15) : l'aide du rail lisible à 3 m (14 sp au moins ; avant : 7,5 sp).
        rail.addView(texte(getString(R.string.reglagestv_aide_rail), maxOf(t(15f), 14f), theme.encre3).apply {
            setPadding(px(10f), px(14f), px(6f), 0)
            // L'aide passe à la ligne dans la largeur du menu, sans l'élargir (sa hauteur suit ses lignes).
            maxWidth = maxOf(rail.minimumWidth - rail.paddingLeft - rail.paddingRight, px(120f))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // Plus de place (très grande police) : le QR s'efface, la carte reste.
        // 1.87.0 : toujours pas de place (télé 1080p, menu traduit) : la carte quitte le menu, les rubriques gardent
        // leur hauteur ; elle reste dans « À propos ».
        rail.post {
            if (espace.height > 0) return@post
            carte.findViewWithTag<View>("qr_soutien")?.visibility = View.GONE
            rail.post { if (espace.height <= 0) carte.visibility = View.GONE }
        }
        construirePage()
    }

    /** L'élément du menu : la section affichée est marquée, celle qui a le focus est entourée d'or. */
    private fun styleRail(v: TextView, focus: Boolean) {
        val choisie = v.tag == "r_$sectionTv"
        v.setTextColor(if (choisie || focus) theme.encre else theme.encre2)
        v.background = when {
            focus -> fondCarte(14f, couleur = theme.carte, bord = theme.or, epaisseurDp = 2f)
            choisie -> fondCarte(14f, couleur = theme.carte, bord = 0)
            else -> fondCarte(14f, couleur = 0, bord = 0)
        }
    }

    private fun majRail() {
        for (i in 0 until rail.childCount) { val v = rail.getChildAt(i); if (v is TextView && (v.tag as? String)?.startsWith("r_") == true) styleRail(v, v.isFocused) }
    }

    /** La page de la section (à droite) ; le menu n'est pas touché. */
    private fun construirePage() {
        // 1.87.0 (recette visuelle) : vider la page peut faire tomber le focus sur le menu (« Deciders », premier en
        // anglais), dont l'écouteur rebâtissait la page au milieu de celle-ci : le bloc « réservé » sortait en double.
        val avant = enConstruction
        enConstruction = true
        try { construirePageSansGarde() } finally { enConstruction = avant }
    }

    private fun construirePageSansGarde() {
        contenu.removeAllViews()
        if (tele) {
            // 1.93.06 (retour d'essai) : la page défile DANS la zone (bord réglé + confort) et s'y efface en fondu ; avant, ses lignes
            // passaient sous la marge du bas jusqu'au bord de l'écran.
            contenu.setPadding(ZoneSure.x(this@ReglagesTvActivity, 0.035f), px(6f), ZoneSure.x(this@ReglagesTvActivity, 0.05f), px(10f))
            racine.setPadding(0, ZoneSure.y(this@ReglagesTvActivity, 0.06f) - px(6f), 0, ZoneSure.y(this@ReglagesTvActivity, 0.06f))
            // Gardée découpée (Ui.laisserDeborder ne la touche plus) ; 6 et 10 dp de marge intérieure gardent les anneaux entiers.
            Ui.garderCoupe(racine); racine.clipToPadding = true
            racine.isVerticalFadingEdgeEnabled = true; racine.setFadingEdgeLength(px(24f))
        } else contenu.setPadding(ZoneSure.x(this@ReglagesTvActivity, 0.035f), ZoneSure.y(this@ReglagesTvActivity, 0.06f), ZoneSure.x(this@ReglagesTvActivity, 0.05f), ZoneSure.y(this@ReglagesTvActivity, 0.06f))
        when (sousPage) {
            "cartes" -> pageCartes()
            "tableaux" -> pageTableaux()
            else -> when (sectionTv) {
                "affichage" -> pageAffichage()
                "veille" -> pageVeille()
                "accueil" -> pageAccueil()
                "son" -> pageSon()
                "musique" -> pageMusique()
                "parametres" -> pageParametres()
                "decideurs" -> pageDecideurs()
            }
        }
        Ui.laisserDeborder(racine)
    }

    private fun premierDeLaPage(): View? {
        fun cherche(v: View): View? {
            if (v !== contenu && v.isFocusable && v.visibility == View.VISIBLE) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) cherche(v.getChildAt(i))?.let { return it }
            return null
        }
        return cherche(contenu)
    }

    private fun ouvrirSousPage(p: String) { sousPage = p; focusTag = null; construireTele(); racine.post { racine.scrollTo(0, 0); premierDeLaPage()?.requestFocus() } }

    /** Le titre de la page, sa phrase, et l'action principale (bouton doré). */
    private fun entetePage(titre: String, phrase: String, action: String? = null, tag: String = "principal", faire: (() -> Unit)? = null) {
        contenu.addView(texte(titre, t(40f), theme.encre, Polices.outfit(this)).apply { includeFontPadding = false })
        contenu.addView(texte(phrase, tl(19f), theme.encre2).apply { setPadding(0, px(6f), 0, px(14f)) })
        if (action != null && faire != null) {
            val b = texte(action, tl(22f), theme.fond, Polices.gras(this)).apply { setPadding(px(24f), px(12f), px(24f), px(12f)) }
            b.isFocusable = true; b.isClickable = true; b.tag = tag
            val fond = { a: Boolean -> GradientDrawable().apply { cornerRadius = px(40f).toFloat(); setColor(theme.or); if (a) setStroke(px(3f), theme.encre) } }
            b.background = fond(false)
            b.setOnFocusChangeListener { x, a -> x.background = fond(a); x.scaleX = if (a) 1.04f else 1f; x.scaleY = x.scaleX; if (a) focusTag = tag }
            b.setOnClickListener { faire() }
            contenu.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = px(16f) })
        }
    }

    private fun note(s: String) = contenu.addView(texte(s, tl(16f), theme.encre3).apply { setPadding(px(4f), px(2f), 0, px(10f)) })

    private fun pageAffichage() {
        entetePage(getString(R.string.reglagestv_affichage), getString(R.string.reglagestv_affichage_phrase), getString(R.string.reglagestv_theme_couleurs_n, Themes.libelle(Local.theme(this))), "theme") { choisirTheme() }
        val modeSombre = Local.modeSombre(this)
        val nuit = Local.nuit(this)
        val lignes = listOf<Pair<String, () -> View>>(
            getString(R.string.reglagestv_mode_sombre) to { ligne(getString(R.string.reglagestv_mode_sombre), Themes.MODES_SOMBRE.first { it.first == modeSombre }.second, "sombre") { choisirModeSombre() } },
            getString(R.string.reglagestv_couleurs_carte) to { ligne(getString(R.string.reglagestv_couleurs_carte), if (Palette.personnalisee(this, Palette.LANCEUR) || Palette.personnalisee(this, Palette.APPLI)) getString(R.string.reglagestv_personnalisees) else getString(R.string.reglagestv_celles_theme), "couleurs") { startActivity(Intent(this, CouleursActivity::class.java)) } },
            getString(R.string.reglagestv_mode_nuit) to { ligne(getString(R.string.reglagestv_mode_nuit), if (nuit.isEmpty()) getString(R.string.reglagestv_jamais) else libelleNuit(nuit), "nuit") { choisirNuit() } },
        ) + (if (!ReglagesTvActivity.estTele(this)) emptyList() else listOf<Pair<String, () -> View>>(
            // 17-10 (l'auteur, point 12) : la zone sûre de CETTE télé (les bords que l'écran rogne), réglée au cadre à flèches.
            getString(R.string.zonesure_titre) to { ligne(getString(R.string.zonesure_titre), getString(R.string.zonesure_court, ZoneSure.pourcentX(this), ZoneSure.pourcentY(this)) + "  ›", "zone_sure") { AjusterEcranActivity.ouvrir(this) } },
        ))
        trier(lignes) { it.first }.forEach { contenu.addView(it.second()) }
    }

    private fun pageVeille() {
        entetePage(getString(R.string.reglages_section_veille), getString(R.string.reglagestv_veille_phrase), getString(R.string.reglagestv_voir_veille), "veille") { Veille.ouvrir(this, demande = true) }
        val delai = Local.delaiVeille(this)
        val libelle = libelleDelai(delai)
        contenu.addView(ligne(getString(R.string.reglagestv_demarre_apres), libelle, "delai") { choisirDelai() })
        contenu.addView(ligne(getString(R.string.horloge_style_veille), StylesHorloge.nom(this, Local.styleHorlogeVeille(this)) + "  ›", "horloge_veille") { choisirHorlogeVeille() })
        // 1.93.05 (l'auteur, retour 36) : le bandeau « Lecteur musique » dans la veille, pour cet appareil (non par défaut).
        contenu.addView(ligne(getString(R.string.veille_option_lecteur), getString(if (OptionsVeille.lecteur(this)) R.string.mus_oui else R.string.mus_non), "veille_lecteur") {
            OptionsVeille.poserLecteur(this, !OptionsVeille.lecteur(this)); repeindre()
        })
        // 1.93.05 (l'auteur, retour 39) : le coin du QR des invités et celui du logo (ou masqués) ; un coin pris est refusé.
        contenu.addView(ligne(getString(R.string.veille_option_qr), nomCoin(OptionsVeille.qr(this)), "veille_qr") { choisirCoin(qr = true) })
        // 1.93.06 (retour d'essai) : la taille du QR des invités (veille et lecteur), avec la distance de scan.
        contenu.addView(ligne(getString(R.string.qr_taille_titre), QrInvites.libelle(this, OptionsVeille.qrTaille(this)), "veille_qr_taille") { choisirTailleQr() })
        contenu.addView(ligne(getString(R.string.veille_option_logo), nomCoin(OptionsVeille.logo(this)), "veille_logo") { choisirCoin(qr = false) })
        // Veille-lot (point 5) : la ligne dit le chemin de CETTE télé (déjà Vision, un appui, le réglage, ou la commande ADB).
        val vision = EconomiseurTv.plan(this).chemin == ReglesVeille.Chemin.DEJA_VISION
        contenu.addView(ligne(getString(R.string.reglagestv_economiseur_tele), EconomiseurTv.libelle(this), "economiseur") { EconomiseurTv.proposer(this) { repeindre() } })
        val repli = Local.delaiRepli(this)
        val sansObjet = when { vision -> getString(R.string.reglagestv_si_economiseur_pas_vision); Local.delaiVeille(this) > 0 -> getString(R.string.reglagestv_sans_delai); else -> null }
        contenu.addView(ligne(getString(R.string.reglagestv_veille_sur_accueil), ReglesVeille.libelleRepli(repli), "repli", sansObjet) { choisirRepli() })
        if (Modules.actif(this, Modules.VEILLE_TABLEAUX)) {
            val liste = listeTableaux()
            var total = 0; var actifs = 0
            liste.forEach { if (it.actif) { total += it.duree; actifs++ } }
            contenu.addView(ligne(getString(R.string.reglagestv_tableaux_affiches), if (liste.isEmpty()) getString(R.string.choixmusique_chargement) else resources.getQuantityString(R.plurals.reglagestv_tableaux_tour, actifs, actifs, if (total >= 60) getString(R.string.reglagestv_duree_min_s, total / 60, String.format(Locale.getDefault(), "%02d", total % 60)) else getString(R.string.tableauediteur_secondes, total)) + "  ›", "sp_tableaux") { ouvrirSousPage("tableaux") })
            val web = Local.veilleWeb(this)
            val style = Local.STYLES.first { it.first == Local.styleCartes(this) }.second.let { if (Locale.getDefault().language == "de") it else it.lowercase(Locale.getDefault()) }  // l'allemand garde la majuscule des noms
            // Refonte lot 3 (APP-18) : pas de cartes de Home Assistant sur un appareil non relié.
            if (cfg.inscrit) contenu.addView(ligne(getString(R.string.reglagestv_cartes_ha), (if (web) getString(R.string.reglagestv_affichees_style, style) else getString(R.string.reglagestv_dessin_simplifie)) + "  ›", "sp_cartes") { ouvrirSousPage("cartes") })
        }
        contenu.addView(ligne(getString(R.string.reglagestv_applis_fond), resources.getQuantityString(R.plurals.reglagestv_choisies, Local.fond(this).size, Local.fond(this).size) + "  ›", "fond") { choisirFond() })
        sousTitre(getString(R.string.reglages_veille_musique))
        lignesMusiqueVeille({ contenu.addView(it) }) { note(it) }
        sousTitre(getString(R.string.reglages_veille_cartes))
        lignesCartesVeille({ contenu.addView(it) }) { note(it) }
    }

    private fun nomCoin(coin: String): String = getString(when (coin) {
        OptionsVeille.HG -> R.string.veille_coin_haut_gauche
        OptionsVeille.HD -> R.string.veille_coin_haut_droite
        OptionsVeille.BG -> R.string.veille_coin_bas_gauche
        OptionsVeille.BD -> R.string.veille_coin_bas_droite
        else -> R.string.veille_coin_cache
    })

    /** 1.93.06 (retour d'essai) : Petit (par défaut), Moyen ou Grand ; [choix] pour les essais. */
    internal fun choisirTailleQr(choix: Int? = null) {
        val tailles = QrInvites.TAILLES
        fun poser(k: Int) { OptionsVeille.poserQrTaille(this, tailles[k]); repeindre() }
        if (choix != null) { poser(choix); return }
        Ui.dialogue(this).setTitle(getString(R.string.qr_taille_titre))
            .setSingleChoiceItems(tailles.map { QrInvites.libelle(this, it) }.toTypedArray(), tailles.indexOf(OptionsVeille.qrTaille(this))) { d, k -> d.dismiss(); poser(k) }
            .setNegativeButton(getString(R.string.commun_lot2_annuler), null).show()
    }

    /** 1.93.05 (retour 39) : le coin du QR ([qr]) ou du logo ; le coin de l'autre est marqué, et refusé s'il est choisi. */
    internal fun choisirCoin(qr: Boolean, choix: Int? = null) {
        val actuel = if (qr) OptionsVeille.qr(this) else OptionsVeille.logo(this)
        val autre = if (qr) OptionsVeille.logo(this) else OptionsVeille.qr(this)
        val nomAutre = getString(if (qr) R.string.veille_option_logo else R.string.veille_option_qr)
        val coins = OptionsVeille.COINS + OptionsVeille.CACHE
        val libelles = coins.map { c -> nomCoin(c) + if (c == autre && c != OptionsVeille.CACHE) " ($nomAutre)" else "" }
        fun poser(k: Int) {
            val ok = if (qr) OptionsVeille.poserQr(this, coins[k]) else OptionsVeille.poserLogo(this, coins[k])
            if (!ok) Ui.toast(this, getString(R.string.veille_coin_pris, nomAutre)) else repeindre()
        }
        if (choix != null) { poser(choix); return }
        Ui.dialogue(this).setTitle(getString(if (qr) R.string.veille_option_qr else R.string.veille_option_logo))
            .setSingleChoiceItems(libelles.toTypedArray(), coins.indexOf(actuel)) { d, k -> d.dismiss(); poser(k) }
            .setNegativeButton(getString(R.string.commun_lot2_annuler), null).show()
    }

    /** 1.91.2 : le style de l'horloge de la veille de cet appareil, ou « comme l'accueil » (aperçu en grand). */
    private fun choisirHorlogeVeille() {
        StylesHorloge.choisir(this, Local.styleHorlogeVeille(this), veille = true) { s -> Local.poserStyleHorlogeVeille(this, s); repeindre() }
    }

    private fun pageTableaux() {
        entetePage(getString(R.string.reglagestv_tableaux_veille), getString(R.string.reglagestv_tableaux_phrase), getString(R.string.reglagestv_actualiser), "actualiser") { charger() }
        val liste = listeTableaux()
        if (liste.isEmpty()) note(getString(R.string.choixmusique_chargement))
        liste.forEach { contenu.addView(rangTableau(it), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = px(8f) }) }
        val releve = Local.listeWebA(this)
        note((if (enCharge) getString(R.string.reglagestv_releve_en_cours) + " " else if (releve > 0) getString(R.string.reglagestv_releve_a, Tr.heure(releve)) + ". " else "") + getString(R.string.reglagestv_retour_veille))
    }

    private fun pageCartes() {
        val web = Local.veilleWeb(this)
        entetePage(getString(R.string.reglagestv_cartes_ha), getString(R.string.reglagestv_cartes_phrase), if (web) getString(R.string.reglagestv_passer_simplifie) else getString(R.string.reglagestv_afficher_cartes_ha), "veille_web") { Local.poserVeilleWeb(this, !web); repeindre() }
        val sansWeb = if (web) null else getString(R.string.reglagestv_avec_cartes_ha)
        val styles = Local.STYLES
        val style = Local.styleCartes(this)
        contenu.addView(ligne(getString(R.string.reglagestv_style_cartes), styles.first { it.first == style }.second, "style", sansWeb) { Local.poserStyleCartes(this, styles[(styles.indexOfFirst { it.first == style } + 1) % styles.size].first); repeindre() })
        contenu.addView(ligne(getString(R.string.reglagestv_fond_cartes), if (Local.fondCartes(this)) getString(R.string.commun_lot2_oui) else getString(R.string.messagesnotif_non), "fond_cartes", sansWeb) { Local.poserFondCartes(this, !Local.fondCartes(this)); repeindre() })
        contenu.addView(ligne(getString(R.string.reglagestv_contour_cartes), if (Local.contourCartes(this)) getString(R.string.commun_lot2_oui) else getString(R.string.messagesnotif_non), "contour_cartes", sansWeb) { Local.poserContourCartes(this, !Local.contourCartes(this)); repeindre() })
        contenu.addView(ligne(getString(R.string.reglagestv_cartes_animees), if (Local.animerCartes(this)) getString(R.string.commun_lot2_oui) else getString(R.string.messagesnotif_non), "animer_cartes", sansWeb) { Local.poserAnimerCartes(this, !Local.animerCartes(this)); repeindre() })
        note(getString(R.string.reglagestv_retour_veille))
    }

    private fun pageAccueil() {
        val toutes = Accueil.applicationsInstallees(this).size
        entetePage(getString(R.string.reglagestv_accueil), getString(R.string.reglagestv_accueil_phrase), getString(R.string.reglagestv_choisir_applis_n, toutes - Accueil.masquees(this).size, toutes), "applis") { choisirApplis() }
        if (Modules.actif(this, Modules.VEILLE)) contenu.addView(ligne(getString(R.string.reglagestv_bulle), if (!Bulle.activee(this)) getString(R.string.reglagestv_rangee) else if (Bulle.permise(this)) getString(R.string.reglagestv_active) else getString(R.string.reglagestv_permission), "bulle") {
            if (!Bulle.permise(this)) { try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) } catch (_: Exception) {} }
            else { Bulle.poser(this, !Bulle.activee(this)); repeindre() }
        })
        if (Modules.actif(this, Modules.ACCUEIL)) contenu.addView(ligne(getString(R.string.reglagestv_vision_place_accueil), if (Local.accueilForce(this)) getString(R.string.reglagestv_oui_touche_accueil) else getString(R.string.reglagestv_non_accueil_tele), "accueil_force") {
            AccueilDefaut.basculerForce(this) { repeindre() }
        })
        if (Modules.actif(this, Modules.VEILLE)) note(getString(R.string.reglagestv_deux_appuis))
        // 1.93.06 (retour d'essai) : l'accueil s'ouvre en mode Modification (déplacer, redimensionner ; OK valide, Retour annule).
        if (tele) contenu.addView(ligne(getString(R.string.accueil_modifier), getString(R.string.accueil_modifier_aide), "modifier_accueil") {
            startActivity(Intent(this, LanceurActivity::class.java).putExtra(LanceurActivity.EXTRA_MODIFIER, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
        })
        // 1.93.06.1 (retour d'essai) : « Afficher la liste des applications » ; désactivée, l'accueil ne montre que les widgets.
        if (Modules.actif(this, Modules.ACCUEIL)) {
            val liste = AffichageApplis.liste(this)
            contenu.addView(ligne(getString(R.string.listeapplis_titre), getString(if (liste) R.string.listeapplis_affichee else R.string.listeapplis_masquee), "liste_applis") {
                AffichageApplis.changer(this, tele) { repeindre() }
            })
            note(getString(if (tele) R.string.listeapplis_note_tele else R.string.listeapplis_note))
        }
        // l'auteur (13 octobre) : la disposition de l'accueil — rangées (d'avant) ou « Vertical » : une colonne d'applis à
        // gauche, au centre ou à droite, et de part et d'autre des zones de widgets, choisies et activées par un Décideur.
        val vertical = DispositionAccueil.disposTele(this) == DispositionAccueil.TELE_VERTICAL
        contenu.addView(ligne(getString(R.string.reglagestv_disposition), getString(if (vertical) R.string.lanceur_tele_vertical else R.string.lanceur_tele_rangees), "tele_disposition") {
            DispositionAccueil.poserDisposTele(this, if (vertical) DispositionAccueil.TELE_RANGEES else DispositionAccueil.TELE_VERTICAL); repeindre()
        })
        if (vertical) {
            val pos = DispositionAccueil.colonne(this)
            val ordre = listOf(DispositionAccueil.COLONNE_GAUCHE, DispositionAccueil.COLONNE_CENTRE, DispositionAccueil.COLONNE_DROITE)
            val noms = mapOf(DispositionAccueil.COLONNE_GAUCHE to R.string.lanceur_colonne_gauche, DispositionAccueil.COLONNE_CENTRE to R.string.lanceur_colonne_centre, DispositionAccueil.COLONNE_DROITE to R.string.lanceur_colonne_droite)
            contenu.addView(ligne(getString(R.string.lanceur_colonne), getString(noms.getValue(pos)), "tele_colonne") {
                DispositionAccueil.poserColonne(this, ordre[(ordre.indexOf(pos) + 1) % ordre.size]); repeindre()
            })
            // 16-10 (retour d'essai) : les côtés libres, puis une rangée en haut et une en bas ; plus de code pour y poser des widgets.
            for (z in DispositionAccueil.zonesTele(pos)) contenu.addView(ligne(DispositionAccueil.nomZone(this, z), DispositionAccueil.resumeZone(this, z), "tele_zone_$z") {
                DispositionAccueil.personnaliserZone(this, z) { repeindre() }
            })
            // 16-10 (l'auteur, télé du salon) : les applis sans carte (logo et nom), les widgets sans cadre — deux choix chacun.
            val fond = DispositionAccueil.fondApplis(this)
            contenu.addView(ligne(getString(R.string.lanceur_fond_applis), getString(if (fond) R.string.lanceur_avec else R.string.lanceur_sans), "tele_fond_applis") {
                DispositionAccueil.poserFondApplis(this, !fond); repeindre()
            })
            val cadre = DispositionAccueil.cadreWidgets(this)
            contenu.addView(ligne(getString(R.string.lanceur_cadre_widgets), getString(if (cadre) R.string.lanceur_avec else R.string.lanceur_sans), "tele_cadre_widgets") {
                DispositionAccueil.poserCadreWidgets(this, !cadre); repeindre()
            })
            note(getString(R.string.lanceur_zones_note))
        }
    }

    /** Le son des messages de cet appareil (MessagesV2) : page Son de la télé, et section Son du téléphone (Refonte lot 3). */
    private fun choisirSonMessages() = ReglagesPartages.choisirSonMessages(this) { repeindre() }

    private fun pageSon() {
        if (Modules.actif(this, Modules.MESSAGES)) entetePage(getString(R.string.reglagestv_son_messages), getString(R.string.reglagestv_son_phrase), getString(R.string.reglagestv_son_messages_n, MessagesV2.nomSon(MessagesV2.sonAppareil(this))), "son_messages") {
            choisirSonMessages()
        } else entetePage(getString(R.string.reglagestv_son_messages), getString(R.string.reglagestv_son_phrase))
    }

    /** Retours de l'auteur (15-10) : la page Musique de la télé (contenu : ReglagesMusique). */
    private fun pageMusique() {
        entetePage(getString(R.string.musrech_section), getString(R.string.musrech_section_phrase))
        ReglagesMusique.lignes(this, { titre, valeur, tag, action -> contenu.addView(ligne(titre, valeur, tag) { action() }) }, { repeindre() }, { v -> contenu.addView(v) }, theme, tl(18f, 14f))
        // l'auteur (16/10) : choisir la sortie (le moteur du lecteur) ne demande plus le code — avant (15-10), réservé aux
        // Décideurs. La soirée suit d'elle-même le nouveau lecteur (Home Assistant 2.5.8).
        lignesMoteur()
    }

    /** Client Sendspin natif (GO de l'auteur, 2026-10-12) : l'interrupteur entre l'ancien lecteur et Sendspin, et l'appairage. */
    private fun lignesMoteur() {
        val sendspin = LecteurMusique.moteur(this) == LecteurMusique.SENDSPIN
        contenu.addView(ligne(getString(R.string.reglagestv_lecteur_musique), getString(if (sendspin) R.string.reglagestv_lecteur_sendspin else R.string.reglagestv_lecteur_classique), "lecteur_moteur") {
            LecteurMusique.poserMoteur(this, if (sendspin) LecteurMusique.SLIM else LecteurMusique.SENDSPIN); repeindre()
        })
        note(getString(R.string.reglagestv_lecteur_note))
        // 1.90.2 (l'auteur : « jamais le code à l'écran ») : l'état de l'appairage, sans code ; à la main seulement ici.
        if (sendspin) {
            val st = LecteurSendspin.statut()
            contenu.addView(ligne(getString(R.string.reglagestv_appairage_musique), getString(when (st) {
                SendspinAppairageAuto.Statut.APPAIRE -> R.string.reglagestv_appairage_ok
                SendspinAppairageAuto.Statut.CONNEXION -> R.string.appairage_connexion
                SendspinAppairageAuto.Statut.ECHEC -> R.string.reglagestv_appairage_echec
            }), "appairage_etat") { repeindre() })
            if (st != SendspinAppairageAuto.Statut.APPAIRE) {
                if (st == SendspinAppairageAuto.Statut.ECHEC) note(getString(R.string.reglagestv_appairage_echec_note))
                contenu.addView(ligne(getString(R.string.reglagestv_appairer_main), getString(R.string.reglagestv_ouvrir_fleche), "appairage_main") {
                    LecteurSendspin.appairerALaMain(this)
                })
            }
        }
    }

    private fun sousTitre(t: String) = contenu.addView(texte(t.uppercase(Locale.getDefault()), tl(15f, 13f), theme.encre3, Polices.gras(this), 0.12f).apply { setPadding(px(4f), px(14f), 0, px(6f)) })

    /** Le nom affiché d'une personne (« person.alex » → son prénom, d'après la liste reçue à l'inscription). */
    private fun nomPersonne(id: String): String {
        if (id.isEmpty()) return getString(R.string.reglagestv_aucune_personne)
        try {
            val l = JSONArray(cfg.personnes)
            for (i in 0 until l.length()) { val o = l.optJSONObject(i) ?: continue; if (o.optString("id") == id || o.optString("entity_id") == id) return o.optString("nom", o.optString("name", id)) }
        } catch (_: Exception) {}
        return id.substringAfter("person.").replace('_', ' ').replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }

    private fun pageParametres() {
        // 1.93.05 (l'auteur, retour 33) : plus de « Modifier » vers l'ancien menu de style téléphone ; ce qu'il offrait d'utile
        // sur la télé est ici (version, mise à jour, demandes) ou dans Affichage (apparence).
        entetePage(getString(R.string.reglagestv_parametres), getString(R.string.reglagestv_parametres_phrase))
        sousTitre(getString(R.string.reglagestv_st_appareil))
        contenu.addView(ligne(getString(R.string.reglagestv_nom), cfg.nom.ifEmpty { Identite.nom(this) }.ifEmpty { getString(R.string.reglagestv_sans_nom) }, "nom") { })
        contenu.addView(ligne(getString(R.string.reglagestv_accueil_origine_court), getString(R.string.reglagestv_ouvrir_fleche), "origine") { AccueilDefaut.ouvrirOrigine(this) })
        sousTitre(getString(R.string.reglagestv_st_personne))
        contenu.addView(ligne(getString(R.string.reglagestv_personne_choisie), nomPersonne(cfg.personne), "personne") { })
        // Réveil : la prochaine alarme armée sur cet appareil, et la liste « Réveils » (télécommande comprise).
        if (Modules.actif(this, Modules.REVEIL)) {
            val armee = Reveils.prochaineArmee(this)
            contenu.addView(ligne(getString(R.string.reveils_titre),
                if (armee > 0) Tr.date(armee, "EEEdMMM") + " · " + Tr.heure(armee) else getString(R.string.reglagestv_ouvrir_fleche), "reveils") {
                ReveilsActivity.ouvrir(this)
            })
        }
        // Niveau sonore : l'état de la mesure sur cet appareil ; OK explique et demande le micro.
        if (NiveauSonore.demandee(this)) contenu.addView(ligne(getString(R.string.son_titre), NiveauSonore.libelle(this), "niveau_sonore") { NiveauSonore.ouvrir(this) })
        // 1.93.05 (retour 33) : les demandes d'un Demandeur, que la télé n'offrait que dans l'ancien menu.
        if (DemandesAppareil.proposees(this)) {
            sousTitre(getString(R.string.reglagestv_st_demandes))
            if (Modules.horaires(Modules.actifs(this))) contenu.addView(ligne(getString(R.string.setup_demander_temps), getString(R.string.reglagestv_ouvrir_fleche), "demander_temps") {
                DemandesAppareil.demanderTemps(this) { construirePage() } })
            contenu.addView(ligne(getString(R.string.deva_titre), getString(R.string.reglagestv_ouvrir_fleche), "demander_deverrouillage") { DemandesAppareil.demanderDeverrouillage(this) })
        }
        sousTitre(getString(R.string.reglagestv_st_connexion))
        val hote = Reseau.hote(cfg.urlActive)
        contenu.addView(ligne("Home Assistant", if (hote.isEmpty()) getString(R.string.reglagestv_pas_relie) else if (Etat.horsLigne()) getString(R.string.reglagestv_hote_hors_ligne, hote) else getString(R.string.reglagestv_hote_connecte, hote), "connexion") { })
        sousTitre(getString(R.string.reglagestv_st_a_propos))
        // 1.93.05 (l'auteur, retour 33 : « pour la mise à jour, j'ai dû passer par Modifier », un ancien menu de téléphone) :
        // la version, la mise à jour prête (« Installer ») et « Rechercher une mise à jour », ici, à la télécommande.
        val prete = MiseAJour.disponible.isNotEmpty() || MiseAJour.apkPret()
        contenu.addView(ligne(getString(R.string.reglagestv_version_court),
            getString(if (prete) R.string.setup_version_maj_prete else R.string.setup_version_a_jour, BuildConfigCompat.version(this)), "version") { detailVersion() })
        if (prete) contenu.addView(ligne(
            if (MiseAJour.disponible.isNotEmpty()) getString(R.string.setup_maj_prete_version, MiseAJour.disponible) else getString(R.string.setup_maj_prete),
            MiseAJour.etat.ifEmpty { getString(R.string.setup_maj_un_geste) }, "maj_installer") {
            cfg.majProposee = ""
            MiseAJour.installerAvecConfirmation(this, cfg)
        })
        else contenu.addView(ligne(getString(R.string.reglagestv_maj_rechercher), getString(R.string.reglagestv_ouvrir_fleche), "maj_rechercher") {
            // Le relevé part tout de suite ; Home Assistant y dit s'il y a une version plus récente (comme toutes les heures).
            AgentService.demarrer(this)
            Ui.toast(this, getString(R.string.reglagestv_maj_recherche_en_cours), court = true)
            main.postDelayed({ if (!isFinishing && sectionTv == "parametres") construirePage() }, 6_000)
        })
    }

    /** La version et l'état des mises à jour (comme sur le téléphone), avec le détail si Home Assistant en a donné. */
    private fun detailVersion() {
        val corps = listOf(MiseAJour.etatAuto(this), MiseAJour.etat).filter { it.isNotEmpty() }.joinToString("\n\n")
        val d = Ui.dialogue(this).setTitle("Vision ${BuildConfigCompat.version(this)}").setMessage(corps.ifEmpty { getString(R.string.reglagestv_maj_aucune) })
            .setPositiveButton(R.string.commun_ok, null)
        if (MiseAJour.detail.isNotEmpty()) d.setNeutralButton(R.string.setup_details) { _, _ ->
            Ui.dialogue(this).setTitle(R.string.setup_details).setMessage(MiseAJour.detail).setPositiveButton(R.string.commun_ok, null).show()
        }
        d.show()
    }

    /**
     * « ☕ Payez-moi un café » : un petit QR de la page de don, le badge s'il est actif. Discret, focalisable,
     * rien à saisir ; OK ouvre l'explication (et la page de don sur le téléphone d'un Décideur).
     */
    private fun carteSoutien(coteQr: Float): View {
        val c = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(px(10f), px(10f), px(10f), px(10f)) }
        val cote = px(coteQr)
        Soutien.qr(cote)?.let { b ->
            c.addView(ImageView(this).apply {
                setImageBitmap(b); tag = "qr_soutien"; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                background = fondCarte(6f, couleur = android.graphics.Color.WHITE, bord = 0); setPadding(px(3f), px(3f), px(3f), px(3f))
            }, LinearLayout.LayoutParams(cote + px(6f), cote + px(6f)).apply { marginEnd = px(12f) })
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // Sur la télé, le menu prend la largeur de son contenu : un poids y donnerait 0 px. Le texte va à la ligne
        // dans la largeur du menu (au moins 22 % de l'écran) plutôt que de l'élargir.
        val largeur = if (tele) (dm.widthPixels * 0.22f).toInt() - cote - px(6f + 12f + 20f + 28f) else Int.MAX_VALUE
        col.addView(texte(getString(R.string.soutien_carte), t(18f), theme.encre2, Polices.moyen(this)).apply { if (tele) maxWidth = largeur })
        if (Soutien.actif()) col.addView(texte(getString(R.string.soutien_badge), t(16f), theme.or, Polices.demiGras(this)).apply { setPadding(0, px(4f), 0, 0); if (tele) maxWidth = largeur })
        c.addView(col, if (tele) LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                       else LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        c.contentDescription = getString(R.string.soutien_carte) + (if (Soutien.actif()) ", " + getString(R.string.soutien_badge) else "")
        focusable(c, if (tele) "r_soutien" else "soutien", 14f)
        // Intégration du 15/10 : dernier élément du menu, ▼ y reste (▲ revient à la dernière rubrique). Sans cela, ▼
        // prenait la ligne de la page la plus proche quand le menu est court ou la page longue (SoutienCarteTest).
        if (tele) { c.id = View.generateViewId(); c.nextFocusDownId = c.id }
        // Au repos, sans fond ni bord : la carte ne s'impose pas dans le menu.
        if (tele) { c.background = fondCarte(14f, couleur = 0, bord = Palette.melanger(theme.fond, theme.encre, 0.10f))
            c.setOnFocusChangeListener { x, a -> x.background = if (a) fondCarte(14f, couleur = theme.carte, bord = theme.or, epaisseurDp = 2f) else fondCarte(14f, couleur = 0, bord = Palette.melanger(theme.fond, theme.encre, 0.10f)); if (a) focusTag = "r_soutien" } }
        c.setOnClickListener { Soutien.expliquer(this, tele) }
        return c
    }

    private fun pageDecideurs() {
        if (!decideursOuverts()) {
            entetePage(getString(R.string.reglagestv_reserve_decideurs, Vocabulaire.decideurs(this)), getString(R.string.reglagestv_code_ouvre), getString(R.string.biometrie_entrer_code), "code") {
                Garde.exiger(this, getString(R.string.reglagestv_raison_decideurs, Vocabulaire.decideurs(this))) { decideursJusqua = android.os.SystemClock.elapsedRealtime() + 5 * 60_000L; focusTag = "gestion"; repeindre() }
            }
            note(getString(R.string.reglagestv_une_fois_ouvert))
            return
        }
        entetePage(Vocabulaire.Decideurs(), getString(R.string.reglagestv_ouvert_5_min), getString(R.string.reglagestv_pause_code_desinstallation), "gestion") {
            startActivity(Intent(this, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_GESTION, true))
        }
        contenu.addView(ligne(getString(R.string.reglagestv_protections_tele), getString(R.string.reglagestv_ouvrir_fleche), "protections") { startActivity(Intent(this, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_GESTION, true)) })
        contenu.addView(ligne(getString(R.string.reglagestv_refermer), "🔒", "refermer") { decideursJusqua = 0L; focusTag = "code"; repeindre() })
    }

    // ------------------------------------------------------------------ tableaux

    private class Ligne(val cle: String, val nom: String, val actif: Boolean, val duree: Int, val local: JSONObject?, val cartes: JSONArray? = null)

    /** Les tableaux connus : ceux de Home Assistant (ordre et défauts), puis ceux de l'appareil ; l'appareil a le dernier mot. */
    private fun listeTableaux(): List<Ligne> {
        val sortie = ArrayList<Ligne>()
        val ha = tableauxHA
        if (ha != null) for (i in 0 until ha.length()) {
            val o = ha.getJSONObject(i)
            val cle = Local.cleTableau(o.optString("code"), o.optString("id"))
            // Avec les vraies cartes de Home Assistant, la liste est celle de la page de veille (cartes de la communauté comprises).
            val cartes = (if (Local.veilleWeb(this) && o.optString("code") == "dash") cartesWeb(o.optString("id")) else null) ?: o.optJSONArray("cartes")
            val montrees = if (cartes == null) 0 else { val m = Local.cartesMasquees(this, cle); (0 until cartes.length()).count { !masquee(cartes.getJSONObject(it), m) } }
            val nom = if (cartes == null) o.optString("nom") else resources.getQuantityString(R.plurals.lot6_nom_cartes, cartes.length(), o.optString("nom"), montrees, cartes.length())
            sortie.add(Ligne(cle, nom, Local.actif(this, cle, o.optBoolean("actif", true)), Local.duree(this, cle, o.optInt("duree", 20)), null, cartes))
        } else {
            // Sans Home Assistant : les tableaux de l'appli, dans l'ordre d'origine.
            sortie.add(Ligne("horloge", getString(R.string.reglagestv_horloge), Local.actif(this, "horloge", true), Local.duree(this, "horloge", 20), null))
        }
        return sortie
    }

    private fun cartesWeb(id: String): JSONArray? {
        val l = Local.listeWeb(this)
        for (i in 0 until l.length()) if (l.getJSONObject(i).optString("id") == id) return l.getJSONObject(i).optJSONArray("cartes")
        return null
    }

    /** Une carte est masquée si sa clé l'est, ou si tous ses éléments le sont (choix faits avant les vraies cartes). */
    private fun masquee(carte: JSONObject, m: Set<String>): Boolean {
        if (carte.optString("cle") in m) return true
        val cles = carte.optJSONArray("cles") ?: return false
        return cles.length() > 0 && (0 until cles.length()).all { cles.optString(it) in m }
    }

    /** Une ligne de tableau : interrupteur, nom, − durée + ; trois cases focusables. */
    private fun rangTableau(l: Ligne): View {
        val rang = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(px(10f), px(6f), px(10f), px(6f)); background = fondCarte(22f) }
        val bascule = FrameLayout(this).apply { setPadding(px(8f), px(6f), px(8f), px(6f)) }
        val fondB = GradientDrawable().apply { cornerRadius = px(22f).toFloat(); setColor(if (l.actif) theme.or else Palette.melanger(theme.carte, theme.encre, 0.12f)) }
        val piste = FrameLayout(this).apply { background = fondB }
        piste.addView(View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (l.actif) theme.fond else theme.encre2) } },
            FrameLayout.LayoutParams(px(24f), px(24f), (if (l.actif) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL).apply { setMargins(px(4f), 0, px(4f), 0) })
        bascule.addView(piste, FrameLayout.LayoutParams(px(58f), px(32f)))
        focusable(bascule, "b_" + l.cle, 22f); bascule.background = fondCarte(22f, couleur = 0, bord = 0)
        bascule.setOnFocusChangeListener { x, a -> x.background = fondCarte(22f, couleur = 0, bord = if (a) theme.or else 0, epaisseurDp = 1.5f); if (a) focusTag = "b_" + l.cle }
        bascule.setOnClickListener { Local.poserTableau(this, l.cle, actif = !l.actif); repeindre() }
        rang.addView(bascule, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = px(8f) })
        val nom = texte(l.nom, t(26f), theme.encre, Polices.moyen(this)).apply { alpha = if (l.actif) 1f else 0.45f; setPadding(px(6f), px(8f), px(6f), px(8f)) }
        if (l.local != null) {
            nom.isClickable = true; nom.isLongClickable = true
            nom.setOnLongClickListener { menuLocal(l.local); true }
            nom.setOnClickListener { menuLocal(l.local) }
        }
        if (l.cartes != null) {
            focusable(nom, "c_" + l.cle, 18f)
            nom.setOnClickListener { choisirCartes(l) }
        }
        rang.addView(nom, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fun pas(signe: String, tag: String, action: () -> Unit): View = texte(signe, t(28f), theme.encre, Polices.outfit(this)).apply {
            gravity = Gravity.CENTER; minWidth = px(Ui.CIBLE_MIN); minHeight = px(Ui.CIBLE_MIN)
            contentDescription = getString(if (signe == "+") R.string.a11y_allonger_5_s else R.string.a11y_raccourcir_5_s, l.nom)
            focusable(this, tag, 24f); background = fondCarte(24f, couleur = 0)
            setOnFocusChangeListener { x, a -> x.background = fondCarte(24f, couleur = if (a) theme.carte else 0, bord = if (a) theme.or else theme.carteBord, epaisseurDp = if (a) 1.5f else 1f); if (a) focusTag = tag }
            setOnClickListener { action() }
        }
        rang.addView(pas("−", "m_" + l.cle) { Local.poserTableau(this, l.cle, duree = l.duree - 5); repeindre() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        rang.addView(texte(getString(R.string.tableauediteur_secondes, l.duree), tl(24f), theme.encre, Polices.demiGras(this)).apply { gravity = Gravity.CENTER; minWidth = px(64f); alpha = if (l.actif) 1f else 0.45f }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        rang.addView(pas("+", "p_" + l.cle) { Local.poserTableau(this, l.cle, duree = l.duree + 5); repeindre() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return rang
    }

    /** Les cartes d'un tableau de bord : cocher celles que cet appareil montre. */
    private fun choisirCartes(l: Ligne) {
        val cartes = l.cartes ?: return
        // Masquer une carte, c'est retenir sa clé et celles de ses éléments : la page de veille et le dessin simplifié s'y retrouvent.
        val cles = (0 until cartes.length()).map { i -> val o = cartes.getJSONObject(i); val a = o.optJSONArray("cles"); listOf(o.optString("cle")) + (0 until (a?.length() ?: 0)).map { a!!.optString(it) } }
        val noms = (0 until cartes.length()).map { val o = cartes.getJSONObject(it); val s = o.optString("section"); if (s.isEmpty()) o.optString("nom") else "$s · ${o.optString("nom")}" }
        val masquees = Local.cartesMasquees(this, l.cle)
        val coches = BooleanArray(cles.size) { !masquee(cartes.getJSONObject(it), masquees) }
        Ui.dialogue(this).setTitle(l.nom.substringBefore("  ·"))
            .setMultiChoiceItems(noms.toTypedArray(), coches) { _, i, c -> coches[i] = c }
            .setPositiveButton(getString(R.string.commun_lot2_valider)) { _, _ -> Local.poserCartesMasquees(this, l.cle, cles.filterIndexed { i, _ -> !coches[i] }.flatten().distinct()); repeindre() }
            .setNegativeButton(getString(R.string.commun_lot2_annuler), null)
            .apply { if (Local.veilleWeb(this@ReglagesTvActivity) && Local.animerCartes(this@ReglagesTvActivity)) setNeutralButton(getString(R.string.reglagestv_animations)) { _, _ -> choisirAnimations(l) } }
            .show()
    }

    /** Carte par carte : animée (elle arrive, compte, se trace) ou fixe (elle est là d'un coup). */
    private fun choisirAnimations(l: Ligne) {
        val cartes = l.cartes ?: return
        val cles = (0 until cartes.length()).map { i -> val o = cartes.getJSONObject(i); val a = o.optJSONArray("cles"); listOf(o.optString("cle")) + (0 until (a?.length() ?: 0)).map { a!!.optString(it) } }
        val noms = (0 until cartes.length()).map { val o = cartes.getJSONObject(it); val s = o.optString("section"); if (s.isEmpty()) o.optString("nom") else "$s · ${o.optString("nom")}" }
        val figees = Local.cartesFigees(this, l.cle)
        val coches = BooleanArray(cles.size) { !masquee(cartes.getJSONObject(it), figees) }
        Ui.dialogue(this).setTitle(getString(R.string.reglagestv_cartes_animees_titre, l.nom.substringBefore("  ·")))
            .setMultiChoiceItems(noms.toTypedArray(), coches) { _, i, c -> coches[i] = c }
            .setPositiveButton(getString(R.string.commun_lot2_valider)) { _, _ -> Local.poserCartesFigees(this, l.cle, cles.filterIndexed { i, _ -> !coches[i] }.flatten().distinct()); repeindre() }
            .setNegativeButton(getString(R.string.commun_lot2_annuler), null).show()
    }

    private fun menuLocal(t: JSONObject) {
        Ui.dialogue(this).setTitle(t.optString("titre")).setItems(arrayOf(getString(R.string.reglagestv_modifier), getString(R.string.reglagestv_supprimer))) { _, k ->
            if (k == 0) startActivity(Intent(this, TableauEditeurActivity::class.java).putExtra("id", t.optString("id")))
            else { Local.supprimerLocal(this, t.optString("id")); repeindre() }
        }.show()
    }

    /** Une ligne focusable : titre à gauche, valeur à droite, liseré d'or au focus. */
    /** Une ligne de réglage ; [grise] = la raison pour laquelle elle ne s'applique pas maintenant (grisée, inactive). */
    private fun ligne(titre: String, valeur: String, tag: String, grise: String? = null, action: () -> Unit): View {
        val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(px(18f), px(12f), px(18f), px(12f)) }
        val gauche = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        gauche.addView(texte(titre, tl(22f, 15f), if (grise == null) theme.encre else theme.encre3, Polices.moyen(this)))
        if (grise != null) gauche.addView(texte(getString(R.string.reglagestv_seulement, grise), tl(16f), theme.encre3))
        r.addView(gauche, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(texte(valeur, tl(20f), if (grise == null) theme.encre2 else theme.encre3, Polices.demiGras(this)).apply { maxWidth = (dm.widthPixels * (if (tele) 0.22f else 0.45f)).toInt() })
        focusable(r, tag, 18f)
        if (grise == null) r.setOnClickListener { action() } else { r.alpha = 0.6f; r.setOnClickListener { Ui.toast(this, getString(R.string.reglagestv_seulement_point, grise), court = true) } }
        r.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = px(8f) }
        return r
    }

    /**
     * Lot 6 (APP-17) : un réglage à plusieurs valeurs se choisit dans une liste (la valeur en cours cochée, Retour ou
     * « Annuler » ne change rien), plutôt qu'en appuyant jusqu'à cinq fois sur OK sans pouvoir revenir en arrière.
     */
    private fun choisirParmi(titre: String, libelles: List<String>, courant: Int, choisi: (Int) -> Unit) = ReglagesPartages.choisirParmi(this, titre, libelles, courant, choisi)

    private fun choisirDelai() {
        val l = Local.DELAIS_VEILLE
        choisirParmi(getString(R.string.reglagestv_demarre_apres), l.map { libelleDelai(it) }, l.indexOf(Local.delaiVeille(this))) { k ->
            Local.poserDelaiVeille(this, l[k]); repeindre()
        }
    }

    private fun choisirRepli() {
        val l = ReglesVeille.DELAIS_REPLI
        choisirParmi(getString(R.string.reglagestv_veille_sur_accueil), l.map { ReglesVeille.libelleRepli(it) }, l.indexOf(Local.delaiRepli(this))) { k ->
            Local.poserDelaiRepli(this, l[k]); repeindre()
        }
    }

    private fun choisirNuit() = ReglagesPartages.choisirNuit(this) { repeindre() }

    private fun choisirModeSombre() = ReglagesPartages.choisirModeSombre(this) { appliquerTheme(); repeindre() }

    /**
     * l'auteur (15/10) : la musique de la veille, dans la section « Veille » — le son (radio, flux, silence) et la playlist de
     * Music Assistant (favorites en tête, cœur pour les favoris, recherche : ChoixPlaylistVeille).
     */
    private fun lignesMusiqueVeille(ajouter: (View) -> Unit, note: (String) -> Unit) {
        val musique = Local.musique(this)
        val radioCourante = Local.RADIOS.firstOrNull { it.second == musique }
        val perso = getSharedPreferences("local", MODE_PRIVATE).getString("flux_perso", "") ?: ""
        if (musique.isNotEmpty() && radioCourante == null && musique != perso) getSharedPreferences("local", MODE_PRIVATE).edit().putString("flux_perso", musique).apply()
        ajouter(ligne(getString(R.string.reglagestv_son_veille), when { musique.isEmpty() -> getString(R.string.reglagestv_silence); radioCourante != null -> radioCourante.first.substringBefore(" ·"); else -> getString(R.string.reglagestv_flux_perso) }, "son") { choisirSon() })
        note(getString(R.string.reglagestv_autre_appli_joue))
        ajouter(ligne(getString(R.string.veilleaudio_playlist), VeilleAudio.choisie(this)?.nom ?: getString(R.string.veilleaudio_desactivee), "veille_playlist") { choisirPlaylistVeille() })
        note(getString(R.string.veilleaudio_note))
    }

    /** l'auteur (15/10) : les cartes de la veille et le voyant des ouvertures, dans la section « Veille ». */
    private fun lignesCartesVeille(ajouter: (View) -> Unit, note: (String) -> Unit) {
        ajouter(ligne(getString(R.string.reg_pastille), getString(if (Local.pastilleVeille(this)) R.string.oui else R.string.non), "pastille") { Local.poserPastilleVeille(this, !Local.pastilleVeille(this)); repeindre() })
        ajouter(ligne(getString(R.string.reg_direct), getString(if (Local.directCameras(this) == DirectVeille.AUTO) R.string.direct_auto else R.string.direct_images), "direct") {
            Local.poserDirectCameras(this, if (Local.directCameras(this) == DirectVeille.AUTO) DirectVeille.IMAGES else DirectVeille.AUTO); repeindre()
        })
        note(getString(R.string.direct_aide))
        lignesOuvertures(ajouter)
    }

    /** Un intertitre dans une section des réglages du téléphone (comme sousTitre sur la télé). */
    private fun sousTitreSection(parent: LinearLayout, t: String) =
        parent.addView(texte(t.uppercase(Locale.getDefault()), tl(15f, 13f), theme.encre3, Polices.gras(this), 0.12f).apply { setPadding(px(4f), px(12f), 0, px(4f)) })

    /**
     * l'auteur (15/10) : l'indicateur des ouvertures de la veille (portes, fenêtres, portails…) — style (pastille, icône,
     * liseré, aucun), couleurs ouvert / fermé, seulement quand c'est ouvert. Plus de trois choix : une liste.
     */
    private fun lignesOuvertures(ajouter: (View) -> Unit) {
        val I = IndicateurOuverture
        ajouter(ligne(getString(R.string.ouv_indicateur), I.nomStyle(this, I.style(this)) + "  ›", "ouv_style") {
            choisirParmi(getString(R.string.ouv_indicateur), I.STYLES.map { I.nomStyle(this, it) }, I.STYLES.indexOf(I.style(this))) { k -> I.poserStyle(this, I.STYLES[k]); repeindre() }
        })
        if (I.style(this) == I.AUCUN) return
        ajouter(ligne(getString(R.string.ouv_couleur_ouvert), I.nomCouleur(this, I.couleurOuvert(this)) + "  ›", "ouv_ouvert") {
            choisirParmi(getString(R.string.ouv_couleur_ouvert), I.COULEURS.map { I.nomCouleur(this, it) }, I.COULEURS.indexOf(I.couleurOuvert(this))) { k -> I.poserCouleurOuvert(this, I.COULEURS[k]); repeindre() }
        })
        ajouter(ligne(getString(R.string.ouv_couleur_ferme), I.nomCouleur(this, I.couleurFerme(this)) + "  ›", "ouv_ferme") {
            choisirParmi(getString(R.string.ouv_couleur_ferme), I.COULEURS.map { I.nomCouleur(this, it) }, I.COULEURS.indexOf(I.couleurFerme(this))) { k -> I.poserCouleurFerme(this, I.COULEURS[k]); repeindre() }
        })
        ajouter(ligne(getString(R.string.ouv_seulement_ouvert), getString(if (I.seulementOuvert(this)) R.string.oui else R.string.non), "ouv_seulement") {
            I.poserSeulementOuvert(this, !I.seulementOuvert(this)); repeindre()
        })
    }

    /** Le thème de couleurs : une liste où l'on choisit, plutôt qu'un bouton à presser dix-neuf fois. */
    private fun choisirTheme() {
        val noms = Themes.liste.map { it.nom }
        Ui.dialogue(this).setTitle(getString(R.string.reglagestv_theme_couleurs))
            .setSingleChoiceItems(noms.map { Themes.libelle(it) }.toTypedArray(), noms.indexOf(Local.theme(this)).coerceAtLeast(0)) { d, k -> Local.poserTheme(this, noms[k]); appliquerTheme(); d.dismiss(); repeindre() }
            .setNegativeButton(getString(R.string.commun_lot2_annuler), null).show()
    }

    /** Le son de la veille : une radio, un flux à soi, ou le silence. */
    private fun choisirSon() {
        val musique = Local.musique(this)
        val perso = getSharedPreferences("local", MODE_PRIVATE).getString("flux_perso", "") ?: ""
        val noms = listOf(getString(R.string.reglagestv_silence)) + Local.RADIOS.map { it.first } + listOf(if (perso.isEmpty()) getString(R.string.reglagestv_flux_perso_points) else getString(R.string.reglagestv_flux_perso_adresse, perso.take(40)))
        val courant = when { musique.isEmpty() -> 0; else -> Local.RADIOS.indexOfFirst { it.second == musique }.let { if (it >= 0) it + 1 else noms.size - 1 } }
        Ui.dialogue(this).setTitle(getString(R.string.reglagestv_son_veille))
            .setSingleChoiceItems(noms.toTypedArray(), courant) { d, k ->
                d.dismiss()
                when {
                    k == 0 -> { Local.poserMusique(this, ""); repeindre() }
                    k <= Local.RADIOS.size -> { Local.poserMusique(this, Local.RADIOS[k - 1].second); repeindre() }
                    else -> {
                        val champ = EditText(this).also { ClavierTele.brancher(it) }.apply { setText(perso); hint = "https://…" }
                        Ui.dialogue(this).setTitle(getString(R.string.reglagestv_adresse_flux)).setView(champ)
                            .setPositiveButton(getString(R.string.choixmusique_jouer)) { _, _ -> val u = champ.text.toString().trim(); if (u.startsWith("http")) { getSharedPreferences("local", MODE_PRIVATE).edit().putString("flux_perso", u).apply(); Local.poserMusique(this, u); repeindre() } }
                            .setNegativeButton(getString(R.string.commun_lot2_annuler), null).show()
                    }
                }
            }
            .setNegativeButton(getString(R.string.commun_lot2_annuler), null).show()
    }

    /**
     * La playlist de la veille (l'auteur, 15/10) : favorites de Music Assistant en tête, toute la bibliothèque en dessous, une
     * recherche chez tous les fournisseurs et un cœur sur chaque ligne (ChoixPlaylistVeille). « Désactivée » en tête.
     */
    private fun choisirPlaylistVeille() = ChoixPlaylistVeille.ouvrir(this) { repeindre() }

    /** Le mode de l'appareil, comme l'accueil le connaît (profil du relevé : parent, enfant, affichage). */

    // ------------------------------------------------------------------ 1.91.x (retour d'essai) : l'arrière-plan de l'accueil

    @Deprecated("Activité sans AndroidX : le résultat du choix de photo (fond de l'accueil) arrive ici.")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        ReglagesPartages.recevoirPhoto(this, requestCode, resultCode, data) { repeindre() }
    }


    /** Les applis que l'écran de veille peut recouvrir sans les couper (leur son continue, la radio de Vision se tait). */
    private fun choisirFond() {
        val pm = packageManager
        val applis = Accueil.applicationsInstallees(this).map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
        // Le récepteur de cast n'a pas d'icône d'appli : on l'ajoute à la main pour qu'il puisse être décoché.
        val toutes = applis + Local.fondFlux(this).filter { pk -> applis.none { it.first == pk } }.map { it to (if (it == "com.google.android.apps.mediashell") getString(R.string.reglagestv_cast) else Usage.libelle(this, it)) }
        val choisies = Local.fond(this).toMutableSet()
        val noms = toutes.map { it.second }.toTypedArray()
        val coches = toutes.map { it.first in choisies }.toBooleanArray()
        Ui.dialogue(this).setTitle(getString(R.string.reglagestv_fond_titre))
            .setMultiChoiceItems(noms, coches) { _, k, on -> val p = toutes[k].first; if (on) choisies.add(p) else choisies.remove(p) }
            .setPositiveButton(getString(R.string.commun_lot2_garder)) { _, _ -> Local.poserFond(this, choisies); repeindre() }
            .setNegativeButton(getString(R.string.commun_lot2_annuler), null).show()
    }

    private fun choisirApplis() = ReglagesPartages.choisirApplis(this, grille = !tele) { repeindre() }

    private fun charger() {
        if (enCharge) return
        enCharge = true
        main.post { if (::contenu.isInitialized && contenu.childCount > 0) repeindre() }
        Thread {
            // Les tableaux et leurs cartes tels que la page de veille les montre (cartes de la communauté comprises).
            try {
                val a = Net.post(this, cfg, "/api/pc_parental/veille/acces", JSONObject().put("id", cfg.id).put("secret", cfg.secret).put("ecran", if (tele) "tele" else "telephone"), 20_000)
                Local.retenirListeWeb(this, a.optJSONArray("tableaux"))
            } catch (_: Exception) {}
            try {
                val r = Net.post(this, cfg, "/api/pc_parental/veille", JSONObject().put("id", cfg.id).put("secret", cfg.secret).put("ecran", if (tele) "tele" else "telephone"), 20_000)
                Local.amorcer(this, r.optJSONObject("reglages"))
                reglages = r.optJSONObject("reglages")
                tableauxHA = reglages?.optJSONArray("tableaux")
            } catch (_: Exception) {}
            main.post { enCharge = false; appliquerTheme(); repeindre() }
        }.also { it.isDaemon = true }.start()
    }

    private fun appliquerTheme() { theme = Local.themeEffectif(this, Palette.APPLI) }

    /** « 5 min sans télécommande », « Réglage de la télé » ; « (accueil Vision seulement) » sans droit d'écrire les réglages. */
    private fun libelleDelai(delai: Int): String =
        if (delai == 0) getString(R.string.reglagestv_delai_tele)
        else getString(R.string.reglagestv_delai_min, delai) + (if (Local.peutEcrireSysteme(this)) "" else getString(R.string.reglagestv_delai_accueil_seulement))

    /** « Sombre et sans son de 23:00 à 07:00 » : la plage « HH:mm-HH:mm » du réglage, heures à la façon du téléphone (12/24 h). */
    private fun libelleNuit(nuit: String): String = ReglagesPartages.libelleNuit(this, nuit)
}
