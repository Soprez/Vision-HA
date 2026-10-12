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
class ReglagesTvActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var cfg: Config
    private var theme: Theme = Themes.liste[0]
    private var reglages: JSONObject? = null
    private var tableauxHA: JSONArray? = null
    private lateinit var racine: ScrollView
    private lateinit var contenu: LinearLayout
    private var focusTag: String? = null
    private var enCharge = false
    private val tele by lazy { estTele(this) }

    // 1.84.0 (point 21, maquette docs/maquettes/tv-reglages.html) : sur la télé, un menu de sections à gauche et
    // une page à droite ; une action principale en haut de chaque page ; les réglages des décideurs derrière le code.
    private lateinit var cadreTv: LinearLayout
    private lateinit var rail: LinearLayout
    private var sectionTv = "veille"
    private var sousPage: String? = null
    private var decideursJusqua = 0L
    /** Pendant qu'on rebâtit, les changements de focus (vues retirées) ne doivent pas relancer la construction. */
    private var enConstruction = false

    companion object {
        /** La section à montrer à l'ouverture : « s_apparence », « s_accueil » ou « s_veille ». */
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
            rail.minimumWidth = (dm.widthPixels * 0.22f).toInt()
            cadreTv.addView(rail, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
            cadreTv.addView(racine, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            setContentView(cadreTv)
            if (!Modules.actif(this, Modules.VEILLE)) sectionTv = "affichage"
        } else setContentView(racine)
        charger()
    }

    override fun onResume() {
        super.onResume(); appliquerTheme(); construire()
        // 1.71.0 : ouvert depuis l'écran principal sur une section (Apparence, Accueil, Veille) : on y va directement.
        intent?.getStringExtra(EXTRA_SECTION)?.let { tag ->
            intent.removeExtra(EXTRA_SECTION); focusTag = tag
            if (tele) {
                sectionTv = when (tag) { "s_apparence" -> "affichage"; "s_accueil" -> "accueil"; "s_veille" -> "veille"; else -> sectionTv }
                sousPage = null; construire()
                racine.post { trouver(rail, "r_$sectionTv")?.requestFocus() }
                return
            }
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

    private fun texte(s: String, taille: Float, couleur: Int, police: android.graphics.Typeface = Polices.texte(this), espacement: Float = 0f): TextView =
        Ui.sansCesure(TextView(this).apply { text = s; textSize = taille; setTextColor(couleur); typeface = police; letterSpacing = espacement })

    private fun fondCarte(rayonDp: Float, couleur: Int = theme.carte, bord: Int = theme.carteBord, epaisseurDp: Float = 1f): GradientDrawable =
        GradientDrawable().apply { cornerRadius = px(rayonDp).toFloat(); setColor(couleur); setStroke(px(epaisseurDp), bord) }

    private fun section(s: String): TextView = texte(s.uppercase(Locale.getDefault()), t(18f), theme.encre2, Polices.gras(this), 0.22f).apply { setPadding(0, px(16f), 0, px(8f)) }

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
        contenu.removeAllViews()
        racine.setBackgroundColor(theme.fond)
        if (!tele) { window.navigationBarColor = theme.fond; window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or (if (!theme.sombre) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0) }
        val mx = if (tele) (dm.widthPixels * 0.05f).toInt() else px(18f); val my = if (tele) (dm.heightPixels * 0.05f).toInt() else px(40f)
        contenu.setPadding(mx, my, mx, my)

        // La marque au milieu en haut, puis le titre et la durée d'un tour.
        val marque = LinearLayout(this).apply { gravity = Gravity.CENTER }
        marque.addView(ImageView(this).apply { setImageResource(R.drawable.ic_vision) }, LinearLayout.LayoutParams(px(t(40f)), px(t(40f))))
        marque.addView(texte("VISION", t(22f), theme.or, Polices.gras(this), 0.28f).apply { setPadding(px(8f), 0, 0, 0) })
        contenu.addView(marque, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val tete = LinearLayout(this).apply { gravity = Gravity.BOTTOM; setPadding(0, px(10f), 0, 0) }
        tete.addView(texte(getString(R.string.reglagestv_titre), t(44f), theme.encre, Polices.outfit(this)).apply { includeFontPadding = false }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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
        if (!sansVeille) {
        gauche.addView(section(getString(R.string.reglagestv_ecran_veille)).apply { tag = "s_veille" })
        if (tableaux) {
        if (liste.isEmpty()) gauche.addView(texte(getString(R.string.choixmusique_chargement), t(20f), theme.encre3))
        liste.forEach { gauche.addView(rangTableau(it), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = px(8f) }) }
        gauche.addView(texte(getString(R.string.reglagestv_tableaux_explication), t(17f), theme.encre3).apply { setPadding(px(4f), px(2f), 0, px(10f)) })
        val releve = Local.listeWebA(this)
        gauche.addView(ligne(getString(R.string.reglagestv_actualiser), if (enCharge) getString(R.string.reglagestv_releve_en_cours) else if (releve > 0) getString(R.string.reglagestv_releve_a, Tr.heure(releve)) else getString(R.string.reglagestv_maintenant), "actualiser") { charger() })
        val web = Local.veilleWeb(this)
        gauche.addView(ligne(getString(R.string.reglagestv_cartes_affichees), if (web) getString(R.string.reglagestv_celles_ha) else getString(R.string.reglagestv_dessin_simplifie), "veille_web") { Local.poserVeilleWeb(this, !web); repeindre() })
        // Dépend d'un réglage qui change : grisé avec la raison plutôt que masqué.
        val sansWeb = if (web) null else getString(R.string.reglagestv_avec_cartes_ha)
        val styles = Local.STYLES
        val style = Local.styleCartes(this)
        gauche.addView(ligne(getString(R.string.reglagestv_style_cartes), styles.first { it.first == style }.second, "style", sansWeb) { Local.poserStyleCartes(this, styles[(styles.indexOfFirst { it.first == style } + 1) % styles.size].first); repeindre() })
        gauche.addView(ligne(getString(R.string.reglagestv_fond_cartes), if (Local.fondCartes(this)) getString(R.string.commun_lot2_oui) else getString(R.string.messagesnotif_non), "fond_cartes", sansWeb) { Local.poserFondCartes(this, !Local.fondCartes(this)); repeindre() })
        gauche.addView(ligne(getString(R.string.reglagestv_contour_cartes), if (Local.contourCartes(this)) getString(R.string.commun_lot2_oui) else getString(R.string.messagesnotif_non), "contour_cartes", sansWeb) { Local.poserContourCartes(this, !Local.contourCartes(this)); repeindre() })
        gauche.addView(ligne(getString(R.string.reglagestv_cartes_animees), if (Local.animerCartes(this)) getString(R.string.commun_lot2_oui) else getString(R.string.messagesnotif_non), "animer_cartes", sansWeb) { Local.poserAnimerCartes(this, !Local.animerCartes(this)); repeindre() })
        }
        if (tele) {
            val delai = Local.delaiVeille(this)
            val libelle = libelleDelai(delai)
            gauche.addView(ligne(getString(R.string.reglagestv_demarre_apres), libelle, "delai") {
                val l = Local.DELAIS_VEILLE
                Local.poserDelaiVeille(this, l[(l.indexOf(delai) + 1) % l.size]); repeindre()
            })
        }
        if (tele) {
            val vision = EconomiseurTv.estVision(this)
            gauche.addView(ligne(getString(R.string.reglagestv_economiseur_tele), when (vision) { true -> "Vision"; false -> getString(R.string.reglagestv_un_autre_toucher); null -> getString(R.string.reglagestv_inconnu_toucher) }, "economiseur") {
                EconomiseurTv.proposer(this) { repeindre() }
            })
            val repli = Local.delaiRepli(this)
            // « Seulement … » : la condition où la ligne s'applique.
            val sansObjet = when { vision == true -> getString(R.string.reglagestv_si_economiseur_pas_vision); Local.delaiVeille(this) > 0 -> getString(R.string.reglagestv_sans_delai); else -> null }
            gauche.addView(ligne(getString(R.string.reglagestv_veille_sur_accueil), ReglesVeille.libelleRepli(repli), "repli", sansObjet) {
                Local.poserDelaiRepli(this, ReglesVeille.repliSuivant(repli)); repeindre()
            })
        }
        gauche.addView(ligne(getString(R.string.reglagestv_applis_fond), resources.getQuantityString(R.plurals.reglagestv_choisies, Local.fond(this).size, Local.fond(this).size), "fond") { choisirFond() })
        gauche.addView(ligne(getString(R.string.reglagestv_voir_veille), getString(R.string.reglagestv_maintenant), "veille") { Veille.ouvrir(this, demande = true) })
        }

        // --- Apparence : les couleurs de tout l'appareil ---------------------------------------
        droite.addView(section(getString(R.string.reglagestv_apparence)).apply { tag = "s_apparence" })
        droite.addView(ligne(getString(R.string.reglagestv_theme_couleurs), Themes.libelle(Local.theme(this)), "theme") { choisirTheme() })
        // 1.72.0 : la version sombre du thème choisi, toujours ou quand le système est en sombre.
        val modeSombre = Local.modeSombre(this)
        droite.addView(ligne(getString(R.string.reglagestv_mode_sombre), Themes.MODES_SOMBRE.first { it.first == modeSombre }.second, "sombre") {
            val i = Themes.MODES_SOMBRE.indexOfFirst { it.first == modeSombre }
            Local.poserModeSombre(this, Themes.MODES_SOMBRE[(i + 1) % Themes.MODES_SOMBRE.size].first)
            appliquerTheme(); repeindre()
        })
        droite.addView(ligne(getString(R.string.reglagestv_couleurs_carte), if (Palette.personnalisee(this, Palette.LANCEUR) || Palette.personnalisee(this, Palette.APPLI)) getString(R.string.reglagestv_personnalisees) else getString(R.string.reglagestv_celles_theme), "couleurs") { startActivity(Intent(this, CouleursActivity::class.java)) })
        val nuit = Local.nuit(this)
        droite.addView(ligne(getString(R.string.reglagestv_mode_nuit), if (nuit.isEmpty()) getString(R.string.reglagestv_jamais) else libelleNuit(nuit), "nuit") { Local.poserNuit(this, Local.PLAGES_NUIT[(Local.PLAGES_NUIT.indexOf(nuit) + 1) % Local.PLAGES_NUIT.size]); repeindre() })

        // --- Son de la veille -----------------------------------------------------------------
        if (!sansVeille) {
        droite.addView(section(getString(R.string.reglagestv_son_veille)))
        val musique = Local.musique(this)
        val radioCourante = Local.RADIOS.firstOrNull { it.second == musique }
        val perso = getSharedPreferences("local", MODE_PRIVATE).getString("flux_perso", "") ?: ""
        if (musique.isNotEmpty() && radioCourante == null && musique != perso) getSharedPreferences("local", MODE_PRIVATE).edit().putString("flux_perso", musique).apply()
        droite.addView(ligne(getString(R.string.reglagestv_source_son), when { musique.isEmpty() -> getString(R.string.reglagestv_silence); radioCourante != null -> radioCourante.first.substringBefore(" ·"); else -> getString(R.string.reglagestv_flux_perso) }, "son") { choisirSon() })
        droite.addView(texte(getString(R.string.reglagestv_autre_appli_joue), t(17f), theme.encre3).apply { setPadding(px(4f), 0, 0, px(6f)) })
        }

        // --- Accueil -------------------------------------------------------------------------
        droite.addView(section(getString(R.string.reglagestv_accueil)).apply { tag = "s_accueil" })
        val toutes = Accueil.applicationsInstallees(this).size
        droite.addView(ligne(getString(R.string.reglagestv_applications_visibles), getString(R.string.reglagestv_sur, toutes - Accueil.masquees(this).size, toutes), "applis") { choisirApplis() })
        // La bulle ouvre l'écran de veille : sans le module « veille », elle n'a plus d'objet.
        if (Modules.actif(this, Modules.VEILLE)) droite.addView(ligne(getString(R.string.reglagestv_bulle), if (!Bulle.activee(this)) getString(R.string.reglagestv_rangee) else if (Bulle.permise(this)) getString(R.string.reglagestv_active) else getString(R.string.reglagestv_permission), "bulle") {
            if (!Bulle.permise(this)) { try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) } catch (_: Exception) {} }
            else { Bulle.poser(this, !Bulle.activee(this)); repeindre() }
        })
        val accueilActif = Modules.actif(this, Modules.ACCUEIL)
        if (tele) {
            // Module « accueil » coupé : Vision ne prend plus la place de l'accueil de la télé.
            if (accueilActif) droite.addView(ligne(getString(R.string.reglagestv_vision_place_accueil), if (Local.accueilForce(this)) getString(R.string.reglagestv_oui_touche_accueil) else getString(R.string.reglagestv_non_accueil_tele), "accueil_force") {
                AccueilDefaut.basculerForce(this) { repeindre() }
            })
        } else {
            if (!accueilActif && AccueilDefaut.possible(this) && AccueilDefaut.estAccueil(this))
                droite.addView(ligne(getString(R.string.reglagestv_encore_accueil), getString(R.string.reglagestv_rendre_accueil), "accueil_rendre") { AccueilDefaut.rendreOrigine(this) })
            if (accueilActif && AccueilDefaut.possible(this)) droite.addView(ligne(getString(R.string.reglagestv_faire_accueil), if (AccueilDefaut.estAccueil(this)) getString(R.string.reglagestv_oui_fait) else getString(R.string.reglagestv_non_toucher), "accueil_defaut") {
                if (!AccueilDefaut.estAccueil(this)) AccueilDefaut.demander(this)
            })
            val pages = Accueil.mode(this) == Accueil.MODE_PAGES
            droite.addView(ligne(getString(R.string.reglagestv_disposition), if (pages) getString(R.string.reglagestv_pages) else getString(R.string.reglagestv_grille), "mode") { Accueil.poserMode(this, if (pages) Accueil.MODE_DEFILEMENT else Accueil.MODE_PAGES); repeindre() })
        }

        // --- Vision : le compte, la connexion, l'appareil ---------------------------------------
        droite.addView(section("Vision"))
        droite.addView(ligne(getString(R.string.reglagestv_connexion_personne), getString(R.string.reglagestv_ouvrir), "appli") { startActivity(Intent(this, SetupActivity::class.java)) })
        if (tele) droite.addView(ligne(getString(R.string.reglagestv_accueil_origine), getString(R.string.reglagestv_ouvrir), "origine") { AccueilDefaut.ouvrirOrigine(this) })
        droite.addView(section(getString(R.string.reglagestv_a_propos)))
        droite.addView(ligne(getString(R.string.reglagestv_version), BuildConfigCompat.version(this), "version") { })
        droite.addView(carteSoutien(56f), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = px(8f) })

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
        if (Modules.actif(this, Modules.VEILLE)) l.add(SectionTv("veille", getString(R.string.reglagestv_ecran_veille)))
        l.add(SectionTv("accueil", getString(R.string.reglagestv_accueil)))
        l.add(SectionTv("son", getString(R.string.reglagestv_son_messages)))
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
        rail.setPadding(px(16f), (dm.heightPixels * 0.06f).toInt(), px(12f), (dm.heightPixels * 0.05f).toInt())
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
                if (a && !enConstruction) {
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
        contenu.setPadding((dm.widthPixels * 0.035f).toInt(), (dm.heightPixels * 0.06f).toInt(), (dm.widthPixels * 0.05f).toInt(), (dm.heightPixels * 0.06f).toInt())
        when (sousPage) {
            "cartes" -> pageCartes()
            "tableaux" -> pageTableaux()
            else -> when (sectionTv) {
                "affichage" -> pageAffichage()
                "veille" -> pageVeille()
                "accueil" -> pageAccueil()
                "son" -> pageSon()
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
        contenu.addView(texte(phrase, t(19f), theme.encre2).apply { setPadding(0, px(6f), 0, px(14f)) })
        if (action != null && faire != null) {
            val b = texte(action, t(22f), theme.fond, Polices.gras(this)).apply { setPadding(px(24f), px(12f), px(24f), px(12f)) }
            b.isFocusable = true; b.isClickable = true; b.tag = tag
            val fond = { a: Boolean -> GradientDrawable().apply { cornerRadius = px(40f).toFloat(); setColor(theme.or); if (a) setStroke(px(3f), theme.encre) } }
            b.background = fond(false)
            b.setOnFocusChangeListener { x, a -> x.background = fond(a); x.scaleX = if (a) 1.04f else 1f; x.scaleY = x.scaleX; if (a) focusTag = tag }
            b.setOnClickListener { faire() }
            contenu.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = px(16f) })
        }
    }

    private fun note(s: String) = contenu.addView(texte(s, t(16f), theme.encre3).apply { setPadding(px(4f), px(2f), 0, px(10f)) })

    private fun pageAffichage() {
        entetePage(getString(R.string.reglagestv_affichage), getString(R.string.reglagestv_affichage_phrase), getString(R.string.reglagestv_theme_couleurs_n, Themes.libelle(Local.theme(this))), "theme") { choisirTheme() }
        val modeSombre = Local.modeSombre(this)
        val nuit = Local.nuit(this)
        val lignes = listOf<Pair<String, () -> View>>(
            getString(R.string.reglagestv_mode_sombre) to { ligne(getString(R.string.reglagestv_mode_sombre), Themes.MODES_SOMBRE.first { it.first == modeSombre }.second, "sombre") {
                val i = Themes.MODES_SOMBRE.indexOfFirst { it.first == modeSombre }
                Local.poserModeSombre(this, Themes.MODES_SOMBRE[(i + 1) % Themes.MODES_SOMBRE.size].first)
                appliquerTheme(); repeindre()
            } },
            getString(R.string.reglagestv_couleurs_carte) to { ligne(getString(R.string.reglagestv_couleurs_carte), if (Palette.personnalisee(this, Palette.LANCEUR) || Palette.personnalisee(this, Palette.APPLI)) getString(R.string.reglagestv_personnalisees) else getString(R.string.reglagestv_celles_theme), "couleurs") { startActivity(Intent(this, CouleursActivity::class.java)) } },
            getString(R.string.reglagestv_mode_nuit) to { ligne(getString(R.string.reglagestv_mode_nuit), if (nuit.isEmpty()) getString(R.string.reglagestv_jamais) else libelleNuit(nuit), "nuit") { Local.poserNuit(this, Local.PLAGES_NUIT[(Local.PLAGES_NUIT.indexOf(nuit) + 1) % Local.PLAGES_NUIT.size]); repeindre() } },
        )
        trier(lignes) { it.first }.forEach { contenu.addView(it.second()) }
    }

    private fun pageVeille() {
        entetePage(getString(R.string.reglagestv_ecran_veille), getString(R.string.reglagestv_veille_phrase), getString(R.string.reglagestv_voir_veille), "veille") { Veille.ouvrir(this, demande = true) }
        val delai = Local.delaiVeille(this)
        val libelle = libelleDelai(delai)
        contenu.addView(ligne(getString(R.string.reglagestv_demarre_apres), libelle, "delai") { val l = Local.DELAIS_VEILLE; Local.poserDelaiVeille(this, l[(l.indexOf(delai) + 1) % l.size]); repeindre() })
        val vision = EconomiseurTv.estVision(this)
        contenu.addView(ligne(getString(R.string.reglagestv_economiseur_tele), when (vision) { true -> "Vision"; false -> getString(R.string.reglagestv_un_autre_ok); null -> getString(R.string.reglagestv_inconnu_ok) }, "economiseur") { EconomiseurTv.proposer(this) { repeindre() } })
        val repli = Local.delaiRepli(this)
        val sansObjet = when { vision == true -> getString(R.string.reglagestv_si_economiseur_pas_vision); Local.delaiVeille(this) > 0 -> getString(R.string.reglagestv_sans_delai); else -> null }
        contenu.addView(ligne(getString(R.string.reglagestv_veille_sur_accueil), ReglesVeille.libelleRepli(repli), "repli", sansObjet) { Local.poserDelaiRepli(this, ReglesVeille.repliSuivant(repli)); repeindre() })
        if (Modules.actif(this, Modules.VEILLE_TABLEAUX)) {
            val liste = listeTableaux()
            var total = 0; var actifs = 0
            liste.forEach { if (it.actif) { total += it.duree; actifs++ } }
            contenu.addView(ligne(getString(R.string.reglagestv_tableaux_affiches), if (liste.isEmpty()) getString(R.string.choixmusique_chargement) else resources.getQuantityString(R.plurals.reglagestv_tableaux_tour, actifs, actifs, if (total >= 60) getString(R.string.reglagestv_duree_min_s, total / 60, String.format(Locale.getDefault(), "%02d", total % 60)) else getString(R.string.tableauediteur_secondes, total)) + "  ›", "sp_tableaux") { ouvrirSousPage("tableaux") })
            val web = Local.veilleWeb(this)
            val style = Local.STYLES.first { it.first == Local.styleCartes(this) }.second.let { if (Locale.getDefault().language == "de") it else it.lowercase(Locale.getDefault()) }  // l'allemand garde la majuscule des noms
            contenu.addView(ligne(getString(R.string.reglagestv_cartes_ha), (if (web) getString(R.string.reglagestv_affichees_style, style) else getString(R.string.reglagestv_dessin_simplifie)) + "  ›", "sp_cartes") { ouvrirSousPage("cartes") })
        }
        contenu.addView(ligne(getString(R.string.reglagestv_applis_fond), resources.getQuantityString(R.plurals.reglagestv_choisies, Local.fond(this).size, Local.fond(this).size) + "  ›", "fond") { choisirFond() })
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
    }

    private fun pageSon() {
        if (Modules.actif(this, Modules.MESSAGES)) entetePage(getString(R.string.reglagestv_son_messages), getString(R.string.reglagestv_son_phrase), getString(R.string.reglagestv_son_messages_n, MessagesV2.nomSon(MessagesV2.sonAppareil(this))), "son_messages") {
            val s = MessagesV2.SONS
            Ui.dialogue(this).setTitle(getString(R.string.reglagestv_son_des_messages))
                .setSingleChoiceItems(s.map { MessagesV2.nomSon(it) }.toTypedArray(), s.indexOf(MessagesV2.sonAppareil(this))) { d, k -> MessagesV2.noterSon(this, s[k]); d.dismiss(); repeindre() }
                .setNegativeButton(getString(R.string.commun_lot2_annuler), null).show()
        } else entetePage(getString(R.string.reglagestv_son_messages), getString(R.string.reglagestv_son_phrase))
        if (Modules.actif(this, Modules.VEILLE)) {
            val musique = Local.musique(this)
            val radioCourante = Local.RADIOS.firstOrNull { it.second == musique }
            val perso = getSharedPreferences("local", MODE_PRIVATE).getString("flux_perso", "") ?: ""
            if (musique.isNotEmpty() && radioCourante == null && musique != perso) getSharedPreferences("local", MODE_PRIVATE).edit().putString("flux_perso", musique).apply()
            contenu.addView(ligne(getString(R.string.reglagestv_son_veille), when { musique.isEmpty() -> getString(R.string.reglagestv_silence); radioCourante != null -> radioCourante.first.substringBefore(" ·"); else -> getString(R.string.reglagestv_flux_perso) }, "son") { choisirSon() })
            note(getString(R.string.reglagestv_autre_appli_joue))
        }
    }

    private fun sousTitre(t: String) = contenu.addView(texte(t.uppercase(Locale.getDefault()), t(15f), theme.encre3, Polices.gras(this), 0.12f).apply { setPadding(px(4f), px(14f), 0, px(6f)) })

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
        entetePage(getString(R.string.reglagestv_parametres), getString(R.string.reglagestv_parametres_phrase), getString(R.string.reglagestv_modifier), "modifier") {
            startActivity(Intent(this, SetupActivity::class.java))
        }
        sousTitre(getString(R.string.reglagestv_st_appareil))
        contenu.addView(ligne(getString(R.string.reglagestv_nom), cfg.nom.ifEmpty { Identite.nom(this) }.ifEmpty { getString(R.string.reglagestv_sans_nom) }, "nom") { startActivity(Intent(this, SetupActivity::class.java)) })
        contenu.addView(ligne(getString(R.string.reglagestv_accueil_origine_court), getString(R.string.reglagestv_ouvrir_fleche), "origine") { AccueilDefaut.ouvrirOrigine(this) })
        sousTitre(getString(R.string.reglagestv_st_personne))
        contenu.addView(ligne(getString(R.string.reglagestv_personne_choisie), nomPersonne(cfg.personne), "personne") { startActivity(Intent(this, SetupActivity::class.java)) })
        sousTitre(getString(R.string.reglagestv_st_connexion))
        val hote = Reseau.hote(cfg.urlActive)
        contenu.addView(ligne("Home Assistant", if (hote.isEmpty()) getString(R.string.reglagestv_pas_relie) else if (Etat.horsLigne()) getString(R.string.reglagestv_hote_hors_ligne, hote) else getString(R.string.reglagestv_hote_connecte, hote), "connexion") { startActivity(Intent(this, SetupActivity::class.java)) })
        sousTitre(getString(R.string.reglagestv_st_a_propos))
        contenu.addView(ligne(getString(R.string.reglagestv_version_court), BuildConfigCompat.version(this), "version") { })
    }

    /**
     * « ☕ Payez-nous un café » : un petit QR de la page de don, le badge s'il est actif. Discret, focalisable,
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
        // Client Sendspin natif (GO de l'auteur, 2026-10-12) : l'interrupteur entre l'ancien lecteur et Sendspin, jusqu'à la bascule.
        if (tele) {
            val sendspin = LecteurMusique.moteur(this) == LecteurMusique.SENDSPIN
            contenu.addView(ligne(getString(R.string.reglagestv_lecteur_musique), getString(if (sendspin) R.string.reglagestv_lecteur_sendspin else R.string.reglagestv_lecteur_classique), "lecteur_moteur") {
                LecteurMusique.poserMoteur(this, if (sendspin) LecteurMusique.SLIM else LecteurMusique.SENDSPIN); repeindre()
            })
            note(getString(R.string.reglagestv_lecteur_note))
        }
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
            val nom = if (cartes == null) o.optString("nom") else getString(R.string.reglagestv_nom_cartes, o.optString("nom"), montrees, cartes.length())
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
        rang.addView(texte(getString(R.string.tableauediteur_secondes, l.duree), t(24f), theme.encre, Polices.demiGras(this)).apply { gravity = Gravity.CENTER; minWidth = px(64f); alpha = if (l.actif) 1f else 0.45f }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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
        gauche.addView(texte(titre, t(22f), if (grise == null) theme.encre else theme.encre3, Polices.moyen(this)))
        if (grise != null) gauche.addView(texte(getString(R.string.reglagestv_seulement, grise), t(16f), theme.encre3))
        r.addView(gauche, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(texte(valeur, t(20f), if (grise == null) theme.encre2 else theme.encre3, Polices.demiGras(this)).apply { maxWidth = (dm.widthPixels * (if (tele) 0.22f else 0.45f)).toInt() })
        focusable(r, tag, 18f)
        if (grise == null) r.setOnClickListener { action() } else { r.alpha = 0.6f; r.setOnClickListener { android.widget.Toast.makeText(this, getString(R.string.reglagestv_seulement_point, grise), android.widget.Toast.LENGTH_SHORT).show() } }
        r.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = px(8f) }
        return r
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
                        val champ = EditText(this).apply { setText(perso); hint = "https://…" }
                        Ui.dialogue(this).setTitle(getString(R.string.reglagestv_adresse_flux)).setView(champ)
                            .setPositiveButton(getString(R.string.choixmusique_jouer)) { _, _ -> val u = champ.text.toString().trim(); if (u.startsWith("http")) { getSharedPreferences("local", MODE_PRIVATE).edit().putString("flux_perso", u).apply(); Local.poserMusique(this, u); repeindre() } }
                            .setNegativeButton(getString(R.string.commun_lot2_annuler), null).show()
                    }
                }
            }
            .setNegativeButton(getString(R.string.commun_lot2_annuler), null).show()
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

    private fun choisirApplis() {
        val pm = packageManager
        val toutes = Accueil.applicationsInstallees(this)
        val masquees = Accueil.masquees(this).toMutableSet()
        val noms = toutes.map { it.loadLabel(pm).toString() }.toTypedArray()
        val coches = toutes.map { it.activityInfo.packageName !in masquees }.toBooleanArray()
        Ui.dialogue(this).setTitle(getString(R.string.reglagestv_applis_accueil))
            .setMultiChoiceItems(noms, coches) { _, k, on -> val p = toutes[k].activityInfo.packageName; if (on) masquees.remove(p) else masquees.add(p) }
            .setPositiveButton(getString(R.string.commun_lot2_garder)) { _, _ -> toutes.forEach { ri -> val p = ri.activityInfo.packageName; val cachee = p in masquees; if (cachee != (p in Accueil.masquees(this))) { Accueil.cacher(this, p, cachee); if (!tele && !cachee) Accueil.ajouterALaGrille(this, p) } }; repeindre() }
            .setNegativeButton(getString(R.string.commun_lot2_annuler), null).show()
    }

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
    private fun libelleNuit(nuit: String): String = try {
        fun h(x: String): String {
            val p = x.split(":")
            val c = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, p[0].trim().toInt()); set(java.util.Calendar.MINUTE, p.getOrNull(1)?.trim()?.toIntOrNull() ?: 0)
            }
            return Tr.heure(c.timeInMillis)
        }
        val (debut, fin) = nuit.split("-").let { it[0] to it[1] }
        getString(R.string.reglagestv_nuit_plage, h(debut), h(fin))
    } catch (_: Exception) { getString(R.string.reglagestv_nuit_plage, nuit.substringBefore("-"), nuit.substringAfter("-")) }
}
