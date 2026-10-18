// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/**
 * 1.82.0 (point 14 bis, retour d'essai : « je ne sais pas comment lancer une musique ») : choisir quoi jouer dans la
 * Party. Recherche (titre, artiste, album, playlist), playlists, favoris et radios de Music Assistant ; pour chaque
 * choix, « Jouer maintenant » ou « Ajouter à la file ». Téléphone d'un décideur et télé de la Party (télécommande).
 * Chaque touche répond tout de suite (« En cours… ») ; un échec s'explique en français, rien ne reste bloqué.
 *
 * Retours de l'auteur (15-10) : la recherche se fait pendant qu'on tape (RechercheMusique), la source est une icône
 * (IconeSource), les filtres sont derrière un bouton « Filtre », la télé a son clavier (ClavierTele), et la voix
 * remplace la saisie quand l'appareil la propose. Les réglages vivent dans les paramètres Vision (ReglagesMusique).
 */
open class ChoixMusiqueActivity : Activity() {
    /**
     * l'auteur (16/10) : sur la télé, « Ajouter un titre » depuis le lecteur n'en cache plus rien — la recherche s'ouvre dans
     * la moitié droite (ChoixMusiqueTeleActivity, fenêtre translucide) ; le lecteur et le code invités restent à gauche.
     */
    protected open val moitie = false
    private var clavierReplie = false
    companion object {
        /**
         * [joue] : la Party joue déjà ; une playlist demande alors « Remplacer la file » ou « Ajouter à la suite ».
         * l'auteur (16/10, lecteur vide) : [onglet] ouvre directement un onglet (« album » : la bibliothèque), [rechercher]
         * met le curseur dans la recherche.
         */
        fun ouvrir(ctx: Context, lecteur: String, lecteurNom: String, lancerIci: Boolean = false, joue: Boolean = false,
                   onglet: String = "", rechercher: Boolean = false, moitie: Boolean = false) {
            try {
                ctx.startActivity(Intent(ctx, if (moitie) ChoixMusiqueTeleActivity::class.java else ChoixMusiqueActivity::class.java).putExtra("lecteur", lecteur)
                    .putExtra("lecteur_nom", lecteurNom).putExtra("lancer_ici", lancerIci).putExtra("joue", joue)
                    .putExtra("onglet", onglet).putExtra("rechercher", rechercher))
            } catch (_: Exception) {}
        }

        /** Les onglets, dans l'ordre : la bibliothèque (albums, musique locale comprise) d'abord. */
        val ONGLETS = listOf("album", "playlist", "favoris", "radio")
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var theme: Theme
    private var tele = false
    private var onglet = "playlist"
    private var recherche = ""
    private var choix: List<Party.Choix>? = null
    private var erreur = ""
    private var retour = ""
    /** 1.85.0 : l'explication de Home Assistant (un fournisseur muet, ou seulement la bibliothèque). */
    private var conseil = ""
    private var occupe = ""  // uri|comment en cours
    private var lancerIci = false
    private var champRecherche: EditText? = null
    private var focusPose = false
    private var focusResultat = false
    /** 1.86.0 : la Party joue déjà (rien ne doit couper le morceau en cours). */
    private var joue = false
    // 1.86.10 (l'auteur : « il faut pouvoir choisir et filtrer ») : type et plateforme, cumulables, mémorisés sur l'appareil.
    private var filtreTypes: Set<String> = emptySet()
    private var filtrePlateformes: Set<String> = emptySet()
    private var plateformes: List<Party.Plateforme> = emptyList()
    private lateinit var liste: LinearLayout
    private lateinit var statut: TextView
    private val finOccupe = Runnable { if (occupe.isNotEmpty()) { occupe = ""; retour = getString(R.string.choixmusique_pas_de_reponse); remplir() } }

    private fun px(v: Float) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        theme = Local.themeEffectif(this, Palette.APPLI)
        tele = ReglagesTvActivity.estTele(this)
        // 1.91.0 (retour d'essai) : pas de veille dans l'écran Musique (comme l'écran du lecteur, PartyActivity).
        if (tele) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lancerIci = intent.getBooleanExtra("lancer_ici", false)
        joue = intent.getBooleanExtra("joue", false)
        intent.getStringExtra("onglet")?.takeIf { it in ONGLETS }?.let { onglet = it }
        // 17-10 (l'auteur, points 9 et 22) : plus de « Bibliothèque » sur la télé.
        if (tele && onglet == "album") onglet = "playlist"
        if (moitie) {
            // La moitié droite de l'écran (un peu plus : la colonne de la file) ; la gauche montre le lecteur dessous.
            window.setLayout(largeurFenetre(), android.view.WindowManager.LayoutParams.MATCH_PARENT)
            window.setGravity(Gravity.END)
            window.setDimAmount(0f)
        }
        filtreTypes = Party.filtresTypes(this); filtrePlateformes = Party.filtresPlateformes(this); plateformes = Party.plateformesConnues(this)
        // La moitié droite s'ouvre en fondu sur le lecteur (avecSeparation) : sa fenêtre est transparente, son cadre opaque.
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(if (moitie) android.graphics.Color.TRANSPARENT else theme.fond))
        Pochettes.ecranOuvert()
        construire()
        charger()
        chargerSuggestions()
        // « Rechercher » depuis le lecteur vide : le curseur dans le champ (et le clavier du téléphone).
        if (intent.getBooleanExtra("rechercher", false)) champRecherche?.let { c ->
            c.post {
                c.requestFocus()
                if (!tele && c.showSoftInputOnFocus) (getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)?.showSoftInput(c, 0)
            }
        }
    }

    override fun onDestroy() {
        programmee?.let { main.removeCallbacks(it) }
        annulation?.annuler()
        // Le choix de la musique ouvert seul (sans écran Party dessous) : fermé, ses vignettes sont déchargées.
        if (Pochettes.ecranFerme(isFinishing)) { Pochettes.vider(this); Vignettes.vider(this) }
        super.onDestroy()
    }

    private fun texte(t: String, taille: Float, couleur: Int, police: Typeface = Polices.texte(this)): TextView =
        Ui.sansCesure(TextView(this).apply { text = t; textSize = if (tele) taille * 1.25f else taille; setTextColor(couleur); typeface = police })

    private fun fond(couleur: Int, rayon: Float, bord: Int = 0) = GradientDrawable().apply {
        cornerRadius = px(rayon).toFloat(); setColor(couleur); if (bord != 0) setStroke(px(1.5f), bord)
    }

    /** Le fond dit l'état (sélectionné ou non) ; le focus de la télécommande est l'anneau commun (Ui.anneauFocus). */
    private fun focusable(v: View, rayon: Float = 999f, couleur: Int = 0, bord: Int = theme.carteBord, agrandir: Boolean = true) {
        v.isFocusable = true; v.isClickable = true
        v.background = fond(couleur, rayon, bord)
        Ui.anneauFocus(v, theme, if (rayon > 100f) 26f else rayon, agrandir)
    }

    // ------------------------------------------------------------------ l'écran (retours d'essai, 15-10)
    //
    // Recherche instantanée : la saisie ne rebâtit plus l'écran (le clavier reste ouvert) ; seuls les suggestions, le
    // statut et la liste changent. Télé avec le clavier de Vision : clavier à gauche, résultats à droite.

    private val memoire = RechercheMusique.Memoire<Triple<List<Party.Choix>, String, List<Party.Plateforme>>>()
    private var annulation: Net.Annulation? = null
    private var programmee: Runnable? = null
    private var cleCourante = ""
    private var bibliothequeSeule = false
    private var suggestionsServeur: List<String> = emptyList()
    private var clavierVision = false
    private lateinit var zoneSuggestions: Pastilles
    private lateinit var zoneOnglets: View
    private var boutonFiltre: TextView? = null
    private var defile: ScrollView? = null
    private var clavier: ClavierTele? = null
    private val REQ_VOIX = 41

    private fun construire() {
        clavierVision = tele && ReglagesMusique.clavier(this) == ReglagesMusique.CLAVIER_VISION
        bibliothequeSeule = RechercheMusique.bibliothequeSeule(this)
        val dm = resources.displayMetrics
        // La liste défile seule ; le champ, les suggestions et le bouton Filtre restent en place.
        val sv = ScrollView(this).apply { isFillViewport = true; isFocusable = false; descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS }
        defile = sv
        val corps = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sv.addView(corps)
        // 1.86.10 (photo de l'auteur : premier résultat coupé en haut) : le focus d'un résultat garde toute sa carte visible.
        if (tele) sv.viewTreeObserver.addOnGlobalFocusChangeListener { _, n ->
            var p: android.view.ViewParent? = n?.parent
            while (p is View && p.tag != "carte") p = p.parent
            (p as? View)?.let { v -> v.postDelayed({ Ui.garderVisible(v, px(10f)) }, 30) }
        }

        val haut = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // Moitié droite : le titre et l'enceinte sont déjà à gauche (le lecteur) ; la place va au clavier et aux résultats.
        if (!moitie) haut.addView(texte(getString(R.string.manifeste_choisir_musique), 26f, theme.encre, Polices.outfit(this)))
        val nom = intent.getStringExtra("lecteur_nom").orEmpty()
        if (nom.isNotEmpty() && !moitie) haut.addView(texte(getString(R.string.choixmusique_sur, nom), 14f, theme.encre2).apply { setPadding(0, px(4f), 0, 0) })

        val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(14f), 0, px(6f)) }
        val ancien = champRecherche?.text?.toString() ?: recherche
        val champ = EditText(this).apply {
            hint = getString(if (clavierVision) R.string.musrech_indice_court else R.string.choixmusique_indice_catalogue); setText(ancien); textSize = if (tele) 18f else 16f
            setTextColor(theme.encre); setHintTextColor(theme.encre3); background = fond(theme.carte, 14f, theme.carteBord)
            setPadding(px(14f), px(12f), px(14f), px(12f)); inputType = android.text.InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH; maxLines = 1; isSingleLine = true
            contentDescription = getString(R.string.choixmusique_rechercher)
            if (tele) { Ui.anneauFocus(this, theme, 14f, agrandir = false); textSize = 20f; setPadding(px(18f), px(16f), px(18f), px(16f)) }
            // Télé, clavier de Vision : le champ montre la saisie, le clavier d'Android ne s'ouvre jamais.
            if (clavierVision) { showSoftInputOnFocus = false; isFocusable = false; isCursorVisible = true }
            setOnEditorActionListener { v, a, e ->
                if (a == EditorInfo.IME_ACTION_SEARCH || (e?.keyCode == KeyEvent.KEYCODE_ENTER && e.action == KeyEvent.ACTION_DOWN)) { chercherMaintenant(v.text.toString()); true } else false
            }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { surSaisie(s?.toString().orEmpty()) }
            })
        }
        champRecherche = champ
        r.addView(champ, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (ReglagesMusique.voix(this) && ReglagesMusique.voixPossible(this)) r.addView(texte("🎤", 18f, theme.encre).apply {  // i18n-exception : pictogramme
            gravity = Gravity.CENTER; minWidth = px(48f); minHeight = px(48f)
            focusable(this, bord = theme.carteBord); contentDescription = getString(R.string.musrech_voix_bouton)
            setOnClickListener { ecouter() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = px(8f) })
        if (!clavierVision) r.addView(boutonFiltre(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = px(8f) })
        haut.addView(r)

        zoneSuggestions = Pastilles(this, px(8f)).apply { setPadding(0, px(4f), 0, px(4f)) }
        haut.addView(zoneSuggestions)

        // Moitié droite : une rangée qui défile (dans un HorizontalScrollView) ; sinon des pastilles qui passent à la ligne.
        // 17-10 (l'auteur, point 22 : « ses bordures sont mangées à gauche ») : la rangée garde la place de l'anneau du focus.
        val onglets: ViewGroup = if (moitie) LinearLayout(this).apply { setPadding(px(6f), px(8f), px(6f), px(4f)) } else Pastilles(this, px(8f)).apply { setPadding(0, px(6f), 0, 0) }
        val listeOnglets = listOf("album" to getString(R.string.musrech_onglet_bibliotheque), "playlist" to getString(R.string.choixmusique_playlists), "favoris" to getString(R.string.choixmusique_favoris), "radio" to getString(R.string.choixmusique_radios))
            .filter { !tele || it.first != "album" }  // 17-10 (l'auteur, points 9 et 22) : plus de « Bibliothèque » sur la télé
        for ((cle, t) in listeOnglets) {
            onglets.addView(texte(t, 15f, if (onglet == cle) theme.encre else theme.encre2, Polices.demiGras(this)).apply {
                setPadding(px(16f), px(10f), px(16f), px(10f)); minHeight = px(44f); gravity = Gravity.CENTER_VERTICAL
                focusable(this, couleur = if (onglet == cle) Palette.melanger(theme.carte, theme.or, 0.22f) else 0, bord = if (onglet == cle) theme.or else theme.carteBord)
                contentDescription = if (onglet == cle) getString(R.string.choixmusique_onglet_choisi, t) else t
                setOnClickListener { onglet = cle; champRecherche?.setText(""); construire(); charger() }
            }, if (moitie) LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = px(8f) }
               else ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        zoneOnglets = onglets

        statut = texte("", 14f, theme.encre2).apply { setPadding(0, px(10f), 0, px(4f)); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        liste = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        if (clavierVision && moitie) {
            // Moitié droite : le champ, le clavier, puis les résultats dessous ; le clavier se replie quand on descend
            // dans les résultats (ils prennent alors toute la hauteur) et revient avec ▲ ou Retour.
            val cadre = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setBackgroundColor(theme.fond)
                // À gauche, après la bande du fondu (avecSeparation).
                setPadding(px(40f), ZoneSure.y(this@ChoixMusiqueActivity, 0.05f), ZoneSure.x(this@ChoixMusiqueActivity, 0.05f), ZoneSure.y(this@ChoixMusiqueActivity, 0.05f))
            }
            cadre.addView(haut)
            clavier = ClavierTele(this, theme, 14f, { c -> champ.append(c) }, { champ.text?.let { if (it.isNotEmpty()) it.delete(it.length - 1, it.length) } }, { champ.setText("") },
                { premierDeLaListe() ?: zoneOnglets.takeIf { it.visibility == View.VISIBLE }?.let { premierFocusable(it) } ?: boutonFiltre })
            cadre.addView(clavier, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(6f) })
            clavier?.visibility = if (clavierReplie) View.GONE else View.VISIBLE
            val tete = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END; setPadding(0, px(8f), 0, 0) }
            // Les onglets sur une seule ligne, qui défile au focus (la moitié d'écran ne les tient pas tous).
            tete.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; isFocusable = false; clipToPadding = false; clipChildren = false; addView(zoneOnglets) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            tete.addView(boutonFiltre(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = px(8f) })
            cadre.addView(tete)
            cadre.addView(statut)
            corps.addView(liste)
            cadre.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            setContentView(avecSeparation(cadre))
            sv.viewTreeObserver.addOnGlobalFocusChangeListener { _, n -> if (n != null) replierClavier(dansResultats(n)) }
        } else if (clavierVision) {
            // Télé : à gauche le champ, les suggestions, le clavier et Filtre ; à droite, les résultats.
            val cadre = LinearLayout(this).apply {
                setBackgroundColor(theme.fond)
                setPadding(ZoneSure.x(this@ChoixMusiqueActivity, 0.05f), ZoneSure.y(this@ChoixMusiqueActivity, 0.05f), ZoneSure.x(this@ChoixMusiqueActivity, 0.05f), ZoneSure.y(this@ChoixMusiqueActivity, 0.05f))
            }
            val gauche = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            gauche.addView(haut)
            clavier = ClavierTele(this, theme, 15f, { c -> champ.append(c) }, { champ.text?.let { if (it.isNotEmpty()) it.delete(it.length - 1, it.length) } }, { champ.setText("") },
                { premierDeLaListe() ?: zoneOnglets.takeIf { it.visibility == View.VISIBLE }?.let { premierFocusable(it) } ?: boutonFiltre })
            gauche.addView(clavier, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(6f) })
            gauche.setPadding(0, 0, px(8f), px(8f))
            // Grande police : la colonne défile au focus (télécommande), sans barre par-dessus le micro.
            cadre.addView(ScrollView(this).apply { addView(gauche); isFocusable = false; isVerticalScrollBarEnabled = false },
                LinearLayout.LayoutParams((dm.widthPixels * 0.42f).toInt(), ViewGroup.LayoutParams.MATCH_PARENT))
            val droite = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(20f), 0, 0, 0) }
            // En tête des résultats : playlists, favoris, radios (ce sont eux qu'ils montrent), et Filtre à droite.
            val tete = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END }
            tete.addView(zoneOnglets, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            tete.addView(boutonFiltre(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = px(8f) })
            droite.addView(tete)
            droite.addView(statut)
            corps.addView(liste)
            droite.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            cadre.addView(droite, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            setContentView(cadre)
        } else {
            val cadre = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setBackgroundColor(theme.fond)
                if (tele) setPadding(ZoneSure.x(this@ChoixMusiqueActivity, 0.05f), ZoneSure.y(this@ChoixMusiqueActivity, 0.05f), ZoneSure.x(this@ChoixMusiqueActivity, 0.05f), ZoneSure.y(this@ChoixMusiqueActivity, 0.05f))
                else setPadding(px(20f), px(28f), px(20f), 0)
            }
            cadre.addView(haut)
            haut.addView(zoneOnglets)
            haut.addView(statut)
            corps.addView(liste)
            if (!tele) corps.setPadding(0, 0, 0, px(28f))
            cadre.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            setContentView(if (moitie) avecSeparation(cadre) else cadre)
        }
        majBoutonFiltre()
        majSuggestions()
        zoneOnglets.visibility = if (recherche.isEmpty()) View.VISIBLE else View.GONE
        remplir()
    }

    /** Télé : la cible de ► depuis le clavier : le premier bouton des résultats. */
    private fun premierDeLaListe(): View? = if (::liste.isInitialized) premierFocusable(liste) else null
    private fun premierFocusable(v: View): View? {
        if (v.isFocusable && v.isShown && v !is ViewGroup) return v
        if (v.isFocusable && v.isShown && v.tag != "carte") return v
        if (v is ViewGroup) for (i in 0 until v.childCount) premierFocusable(v.getChildAt(i))?.let { return it }
        return null
    }

    /** « Filtre » et le nombre de filtres choisis. */
    private fun boutonFiltre(): TextView = texte("", 15f, theme.encre, Polices.gras(this)).apply {
        gravity = Gravity.CENTER; setPadding(px(16f), px(12f), px(16f), px(12f)); minHeight = px(48f)
        focusable(this, bord = theme.carteBord)
        setOnClickListener { ouvrirFiltre() }; tag = "filtre"
        boutonFiltre = this
    }

    private fun nbFiltres() = (if (filtreTypes.isNotEmpty()) 1 else 0) + (if (filtrePlateformes.isNotEmpty()) 1 else 0) + (if (bibliothequeSeule) 1 else 0)

    private fun majBoutonFiltre() {
        val n = nbFiltres()
        boutonFiltre?.apply {
            text = if (n > 0) getString(R.string.musrech_filtre_n, n) else getString(R.string.musrech_filtre)
            contentDescription = if (n > 0) getString(R.string.musrech_filtre_n_desc, n) else getString(R.string.musrech_filtre)
            background = fond(if (n > 0) Palette.melanger(theme.carte, theme.or, 0.22f) else 0, 999f, if (n > 0) theme.or else theme.carteBord)
        }
    }

    private val typesFiltre get() = listOf("" to getString(R.string.mus_tout), "track" to getString(R.string.mus_titres), "artist" to getString(R.string.mus_artistes),
        "album" to getString(R.string.mus_albums), "playlist" to getString(R.string.mus_playlists), "radio" to getString(R.string.mus_radios))

    /**
     * Le panneau Filtre : type de résultat, source (plusieurs possibles, avec leur icône), « Ma bibliothèque seulement ».
     * Règle de l'auteur : ≤ 3 options qui tiennent sur une ligne → étiquettes, sinon liste déroulante — par le composant
     * partagé de la Refonte UX (ChoixAdaptatif, Ui.choix / Ui.choixMultiple), qui décide à la mesure.
     */
    /**
     * Le filtre (type, source, bibliothèque seule), par le sélecteur partagé (ChoixAdaptatif, règle UX de l'auteur du 15-10) :
     * étiquettes quand 3 options au plus tiennent sur la ligne, sinon une liste qui montre le choix. Chaque changement
     * relance la recherche ; la fenêtre reste ouverte.
     */
    private fun ouvrirFiltre() {
        val corps = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(20f), px(8f), px(20f), px(4f)) }
        val taille = if (tele) 17f else 14f
        fun intitule(t: String) = corps.addView(texte(t, 13f, theme.encre2, Polices.demiGras(this)).apply { setPadding(0, px(12f), 0, px(6f)) })
        intitule(getString(R.string.musrech_type))
        corps.addView(Ui.choix(this, getString(R.string.musrech_type), typesFiltre.map { ChoixAdaptatif.Option(it.first, it.second) },
            filtreTypes.singleOrNull() ?: "", theme, taille) { cle ->
            filtreTypes = if (cle.isEmpty()) emptySet() else setOf(cle); filtresChanges()
        })
        if (plateformes.isNotEmpty()) {
            intitule(getString(R.string.musrech_source))
            corps.addView(Ui.choixMultiple(this, getString(R.string.musrech_source),
                plateformes.map { ChoixAdaptatif.Option(it.domaine, it.nom, IconeSource.creer(it.domaine, theme.or)) }, filtrePlateformes, theme, taille) { cles ->
                filtrePlateformes = cles; filtresChanges()
            })
        }
        intitule(getString(R.string.musrech_bibliotheque_seule))
        corps.addView(Ui.choix(this, getString(R.string.musrech_bibliotheque_seule),
            listOf(ChoixAdaptatif.Option("non", getString(R.string.mus_non)), ChoixAdaptatif.Option("oui", getString(R.string.mus_oui))),
            if (bibliothequeSeule) "oui" else "non", theme, taille) { cle ->
            bibliothequeSeule = cle == "oui"; RechercheMusique.bibliothequeSeule(this, bibliothequeSeule); filtresChanges()
        })
        val d = Ui.dialogue(this).setTitle(getString(R.string.musrech_filtre)).setView(ScrollView(this).apply { addView(corps) })
            .setNeutralButton(getString(R.string.musrech_reinitialiser)) { _, _ ->
                filtreTypes = emptySet(); filtrePlateformes = emptySet(); bibliothequeSeule = false
                RechercheMusique.bibliothequeSeule(this, false); filtresChanges()
            }
            .setNegativeButton(getString(R.string.commun_fermer), null).show()
        d.window?.decorView?.post { corps.getChildAt(1)?.requestFocus() }
    }

    /** Une puce (suggestion, « Effacer ») : même focus télécommande que le reste. */
    private fun puceFiltre(t: String, choisie: Boolean, action: () -> Unit): TextView =
        texte(t, 14f, if (choisie) theme.encre else theme.encre2, if (choisie) Polices.demiGras(this) else Polices.texte(this)).apply {
            setPadding(px(14f), px(8f), px(14f), px(8f)); minHeight = px(40f); gravity = Gravity.CENTER_VERTICAL; maxLines = 1
            focusable(this, couleur = if (choisie) Palette.melanger(theme.carte, theme.or, 0.22f) else 0, bord = if (choisie) theme.or else theme.carteBord)
            contentDescription = t
            setOnClickListener { action() }
        }

    private fun filtresChanges() {
        Party.poserFiltres(this, filtreTypes, filtrePlateformes)
        majBoutonFiltre()
        if (recherche.isNotEmpty()) lancer(recherche) else remplir()
    }

    // ------------------------------------------------------------------ la saisie

    /** Chaque frappe : les suggestions suivent ; la recherche part après une pause ([RechercheMusique.ANTI_REBOND_MS]). */
    private fun surSaisie(t: String) {
        programmee?.let { main.removeCallbacks(it) }
        if (!::liste.isInitialized) return
        majSuggestions()
        if (!RechercheMusique.assezLong(t)) {
            annulation?.annuler(); annulation = null; cleCourante = ""
            if (recherche.isNotEmpty()) { recherche = ""; retour = ""; zoneOnglets.visibility = View.VISIBLE; charger() }
            return
        }
        val p = Runnable { lancer(t) }
        programmee = p
        main.postDelayed(p, RechercheMusique.ANTI_REBOND_MS)
    }

    /** Entrée, une suggestion ou la voix : tout de suite, et noté dans les recherches récentes. */
    private fun chercherMaintenant(t: String) {
        programmee?.let { main.removeCallbacks(it) }
        if (!RechercheMusique.assezLong(t)) { retour = getString(R.string.choixmusique_deux_lettres); remplir(); return }
        RechercheMusique.noterRecent(this, t)
        // 1.86.1 (télé) : le clavier se ferme et le focus ira sur le premier résultat dès qu'il arrive.
        if (tele && !clavierVision) { try { (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(window.decorView.windowToken, 0) } catch (_: Exception) {} }
        if (tele) focusResultat = true
        lancer(t)
    }

    private fun lancer(t: String) {
        recherche = RechercheMusique.normaliser(t)
        retour = ""
        zoneOnglets.visibility = View.GONE
        val cle = RechercheMusique.cle(recherche, filtreTypes, filtrePlateformes, bibliothequeSeule)
        if (cle == cleCourante && choix != null) return
        cleCourante = cle
        annulation?.annuler()
        memoire.lire(cle, android.os.SystemClock.elapsedRealtime())?.let { (l, cons, _) ->
            annulation = null; choix = l; erreur = ""; conseil = cons; remplir(); return
        }
        // Les résultats d'avant restent à l'écran jusqu'aux nouveaux (pas de clignotement) ; le statut le dit.
        erreur = ""; statut.text = getString(R.string.musrech_recherche_en_cours)
        val a = Net.Annulation(); annulation = a
        val corpsReq = JSONObject().put("quoi", "chercher").put("texte", recherche)
            .put("types", org.json.JSONArray(filtreTypes.toList())).put("plateformes", org.json.JSONArray(filtrePlateformes.toList()))
            .put("bibliotheque", bibliothequeSeule)
        Thread {
            var l: List<Party.Choix>? = null; var err = ""; var cons = ""; var pl: List<Party.Plateforme> = emptyList()
            try {
                val cfg = Config(this)
                val r = Net.post(this, cfg, "/api/pc_parental/party/musique", corpsReq.put("id", cfg.id).put("secret", cfg.secret), 15_000, a)
                l = Party.analyserChoix(r); cons = Party.conseilRecherche(r); pl = Party.analyserPlateformes(r)
            } catch (x: Net.Echec) { err = Party.messageErreur(x.code, x.corps) } catch (_: Exception) { if (!a.annulee) err = Party.messageErreur(0, null) }
            main.post {
                // Une réponse pour un texte qui n'est plus celui du champ : jetée.
                if (isFinishing || a.annulee || cle != cleCourante) return@post
                annulation = null
                if (l != null) memoire.garder(cle, Triple(l, cons, pl), android.os.SystemClock.elapsedRealtime())
                if (pl.isNotEmpty() && pl != plateformes) { plateformes = pl; Party.garderPlateformes(this, pl) }
                choix = l ?: choix; erreur = err; conseil = cons
                if (l == null) cleCourante = ""
                remplir()
            }
        }.also { it.isDaemon = true }.start()
    }

    /** Sans saisie : récents puis souvent joués (HA) ; avec une saisie, ceux qui la contiennent. Un appui cherche. */
    private fun majSuggestions() {
        if (!::zoneSuggestions.isInitialized) return
        val saisie = champRecherche?.text?.toString().orEmpty()
        val l = RechercheMusique.suggestions(RechercheMusique.recents(this), suggestionsServeur, saisie)
        zoneSuggestions.removeAllViews()
        l.forEach { s -> zoneSuggestions.addView(puceFiltre(s, false) { champRecherche?.setText(s); champRecherche?.setSelection(s.length); chercherMaintenant(s) }) }
        zoneSuggestions.visibility = if (l.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun chargerSuggestions() {
        Thread {
            val l = try {
                val cfg = Config(this)
                val r = Net.post(this, cfg, "/api/pc_parental/party/musique", JSONObject().put("quoi", "suggestions").put("id", cfg.id).put("secret", cfg.secret), 8_000)
                val a = r.optJSONArray("suggestions")
                if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }
            } catch (_: Exception) { emptyList() }  // ancien Home Assistant : les récents seuls
            main.post { if (!isFinishing && l.isNotEmpty()) { suggestionsServeur = l; majSuggestions() } }
        }.also { it.isDaemon = true }.start()
    }

    // ------------------------------------------------------------------ la voix

    private fun ecouter() {
        try { @Suppress("DEPRECATION") startActivityForResult(ReglagesMusique.intentionVoix(this), REQ_VOIX) }
        catch (_: Exception) { Ui.toast(this, getString(R.string.musrech_voix_indisponible), genre = OeilModele.Genre.ERREUR) }
    }

    @Deprecated("API Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_VOIX || resultCode != RESULT_OK) return
        val dit = data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
        if (dit.isEmpty()) return
        champRecherche?.setText(dit); champRecherche?.setSelection(dit.length)
        chercherMaintenant(dit)
    }

    /** La largeur de la fenêtre « moitié droite » : la colonne de la file du lecteur (la colonne du lecteur finit vers 47 %). */
    /**
     * 17-10 (l'auteur, point 22 : « la séparation entre le lecteur et la recherche est trop nette ») : le bord gauche du panneau
     * s'ouvre en fondu sur le lecteur, avec un filet doux aux couleurs du thème (plus fort au milieu, effacé aux bouts).
     */
    private fun avecSeparation(cadre: View): View {
        val fondu = px(28f)
        // Le fond plein commence après la bande du fondu ; la bande, elle, passe du transparent au fond.
        // Un fond avec retrait remplace les marges de la vue par les siennes : on garde celles du cadre (zone sûre).
        val g = cadre.paddingLeft; val h = cadre.paddingTop; val d = cadre.paddingRight; val b = cadre.paddingBottom
        cadre.background = android.graphics.drawable.InsetDrawable(android.graphics.drawable.ColorDrawable(theme.fond), fondu, 0, 0, 0)
        cadre.setPadding(g, h, d, b)
        val boite = android.widget.FrameLayout(this)
        boite.addView(View(this).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(theme.fond and 0x00FFFFFF, theme.fond))
        }, android.widget.FrameLayout.LayoutParams(fondu, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START))
        boite.addView(cadre, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        boite.addView(View(this).apply {
            tag = "separation"; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(theme.or and 0x00FFFFFF, Palette.melanger(theme.fond, theme.or, 0.55f), theme.or and 0x00FFFFFF))
        }, android.widget.FrameLayout.LayoutParams(maxOf(1, px(1.5f)), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START).apply { leftMargin = fondu / 2 })
        return boite
    }

    private fun largeurFenetre(): Int = (resources.displayMetrics.widthPixels * 0.52f).toInt()

    private fun dansResultats(v: View): Boolean =
        (::liste.isInitialized && isDescendant(v, liste)) || (::zoneOnglets.isInitialized && isDescendant(v, zoneOnglets)) || v.tag == "filtre"

    /** Moitié droite : le clavier se replie dans les résultats, revient dès que le focus le quitte. */
    private fun replierClavier(oui: Boolean) {
        if (!moitie || !clavierVision || clavierReplie == oui) return
        clavierReplie = oui
        clavier?.visibility = if (oui) View.GONE else View.VISIBLE
    }

    /** Télé : la touche Recherche de la télécommande lance la voix. */
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode == KeyEvent.KEYCODE_SEARCH && e.action == KeyEvent.ACTION_UP && ReglagesMusique.voix(this) && ReglagesMusique.voixPossible(this)) { ecouter(); return true }
        // Moitié droite, clavier replié : ▲ en haut des résultats, ou Retour, ramène au clavier (Retour ensuite ferme).
        if (moitie && clavierVision && clavierReplie && e.action == KeyEvent.ACTION_DOWN) {
            val f = currentFocus
            val enHaut = e.keyCode == KeyEvent.KEYCODE_DPAD_UP && f != null && f.focusSearch(View.FOCUS_UP).let { it == null || !dansResultats(it) }
            if (e.keyCode == KeyEvent.KEYCODE_BACK || enHaut) {
                replierClavier(false)
                clavier?.post { clavier?.premiere()?.requestFocus() }
                return true
            }
        }
        return super.dispatchKeyEvent(e)
    }

    // ------------------------------------------------------------------ playlists, favoris, radios

    private fun charger() {
        choix = null; erreur = ""; remplir()
        val demande = onglet
        Thread {
            var l: List<Party.Choix>? = null; var err = ""; var cons = ""
            try {
                val cfg = Config(this)
                val r = Net.post(this, cfg, "/api/pc_parental/party/musique", JSONObject().put("quoi", onglet).put("id", cfg.id).put("secret", cfg.secret), 15_000)
                l = Party.analyserChoix(r); cons = Party.conseilRecherche(r)
            } catch (x: Net.Echec) { err = Party.messageErreur(x.code, x.corps) } catch (_: Exception) { err = Party.messageErreur(0, null) }
            main.post {
                if (isFinishing || recherche.isNotEmpty() || demande != onglet) return@post
                choix = l; erreur = err; conseil = cons; remplir()
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun vide(): String = when {
        recherche.isNotEmpty() -> getString(R.string.choixmusique_rien_trouve, recherche)
        onglet == "radio" -> getString(R.string.choixmusique_aucune_radio)
        onglet == "favoris" -> getString(R.string.choixmusique_aucun_favori)
        onglet == "album" -> getString(R.string.musrech_aucun_album)
        else -> getString(R.string.choixmusique_aucune_playlist)
    }

    private fun bibliotheque() = getString(R.string.partyecran_bibliotheque)

    /** Les libellés d'action d'un résultat (ceux du bouton() de remplir()), et s'ils tiennent sur la ligne du titre. */
    private fun actionsTiennent(): Boolean {
        val libelles = when {
            !joue -> listOf(getString(R.string.mus_lire))
            tele -> listOf(getString(R.string.choixmusique_ajouter_suite), getString(R.string.choixmusique_jouer_ensuite), "⋯")  // i18n-exception : pictogramme
            else -> listOf(getString(R.string.mus_ensuite), "⋮")  // i18n-exception : pictogramme
        }
        val p = android.graphics.Paint().apply { textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, if (tele) 14f * 1.25f else 14f, resources.displayMetrics); typeface = Polices.gras(this@ChoixMusiqueActivity) }
        val boutons = libelles.map { p.measureText(it).toInt() + px(if (tele) 28f else 24f) + px(8f) }
        val dm = resources.displayMetrics
        // La carte : la colonne des résultats (à droite du clavier de la télé) ou tout l'écran, moins les marges.
        val carte = (if (::liste.isInitialized && liste.width > 0) liste.width
            else if (moitie) largeurFenetre() - px(40f) - ZoneSure.x(this@ChoixMusiqueActivity, 0.05f)
            else if (clavierVision) (dm.widthPixels * 0.9f - dm.widthPixels * 0.42f).toInt() - px(20f)
            else if (tele) (dm.widthPixels * 0.9f).toInt() else dm.widthPixels - px(40f)) - px(24f)
        // Téléphone en grands caractères sur un écran étroit : le titre garde toute la largeur (recette lot 1).
        val assezLarge = tele || resources.configuration.screenWidthDp / resources.configuration.fontScale >= 300f
        return assezLarge && RechercheMusique.actionsSurLaLigne(carte, boutons, px(if (tele) 56f else 48f) + px(12f))
    }

    private fun remplir() {
        if (!::liste.isInitialized) return
        // 1.86.1 (télé) : le bouton qui avait le focus le retrouve après le redessin (avant : il filait sur la recherche).
        val gardeFocus = currentFocus?.let { f -> if (f.parent != null && isDescendant(f, liste)) f.contentDescription?.toString()?.replace(getString(R.string.choixmusique_bouton_description, getString(R.string.choixmusique_en_cours), ""), "") else null }
        liste.removeAllViews()
        val l = choix
        statut.text = when {
            retour.isNotEmpty() -> retour
            erreur.isNotEmpty() -> erreur
            l == null -> getString(R.string.choixmusique_chargement)
            l.isEmpty() -> listOf(vide(), conseil).filter { it.isNotEmpty() }.joinToString("\n")
            else -> conseil
        }
        statut.visibility = if (statut.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        statut.setTextColor(if (erreur.isNotEmpty() && retour.isEmpty()) theme.pourpre else theme.encre2)
        if (l == null) return
        var premier: View? = null
        // Recherche : par type, dans l'ordre de YouTube Music ; avec « Tout », un aperçu de chaque groupe et « Tout voir ».
        // Playlists, favoris, radios : par source (la bibliothèque d'abord).
        val apercu = recherche.isNotEmpty() && filtreTypes.isEmpty()
        val groupes: List<Triple<String, List<Party.Choix>, Int>> = if (recherche.isNotEmpty()) RechercheMusique.grouper(l, { it.type }, apercu)
            else l.groupBy { it.source.ifEmpty { bibliotheque() } }.toList().sortedBy { if (it.first == bibliotheque()) 0 else 1 }.map { Triple(it.first, it.second, it.second.size) }
        // Retour de la session Réveil (15-10) : les actions sur la ligne du titre seulement si elles y tiennent avec le titre
        // (mesurées dans la langue de l'appareil) ; sinon dessous. Avant : à la télé (colonne des résultats, à côté du
        // clavier), « Als Nächstes spielen » et « ⋯ » sortaient de l'écran et le titre tombait à zéro.
        val compacte = actionsTiennent()
        groupes.forEach { (cleGroupe, items, total) ->
            val titreGroupe = if (recherche.isNotEmpty()) Party.nomSection(cleGroupe) else if (cleGroupe == bibliotheque()) getString(R.string.choixmusique_dans_bibliotheque) else getString(R.string.choixmusique_sur_source, cleGroupe)
            if (groupes.size > 1 || recherche.isNotEmpty()) {
                val tg = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(px(2f), px(14f), 0, px(2f)) }
                tg.addView(texte(titreGroupe, 14f, theme.or, Polices.gras(this)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (apercu && total > items.size) tg.addView(texte(getString(R.string.musrech_tout_voir), 13f, theme.encre2, Polices.demiGras(this)).apply {
                    setPadding(px(12f), px(6f), px(12f), px(6f)); minHeight = px(40f); gravity = Gravity.CENTER_VERTICAL
                    focusable(this, bord = 0); contentDescription = getString(R.string.musrech_tout_voir_desc, titreGroupe)
                    setOnClickListener { filtreTypes = setOf(cleGroupe); filtresChanges() }
                })
                liste.addView(tg)
            }
            items.forEach { x ->
                // 1.86.9 (capture de l'auteur) : au téléphone, la pochette et le titre ont toute la largeur, les actions passent
                // dessous si la place manque ; la télé garde une ligne.
                val carte = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = fond(theme.carte, 16f, theme.carteBord); setPadding(px(14f), px(10f), px(10f), px(10f)); tag = "carte" }
                val ligne = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
                carte.addView(ligne)
                val actions = if (compacte) ligne else LinearLayout(this).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL; setPadding(0, px(8f), 0, 0) }.also { carte.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
                ligne.addView(ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
                    background = fond(Palette.melanger(theme.carte, theme.encre, 0.08f), if (x.type == "artist") 999f else 8f)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    Vignettes.poser(this, x.image, px(if (tele) 56f else 48f) * 2)
                }, LinearLayout.LayoutParams(px(if (tele) 56f else 48f), px(if (tele) 56f else 48f)).apply { rightMargin = px(12f) })
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                col.addView(texte(x.nom, 16f, theme.encre, Polices.demiGras(this)).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; contentDescription = x.nom })
                // Retours de l'auteur (15-10) : la source en icône, devant le sous-titre (le titre garde toute sa largeur) ;
                // son nom reste lu par les lecteurs d'écran. l'auteur (15/10) : le cœur « favori » de Music Assistant au bout de la
                // ligne (même composant et même appel que le choix de la playlist de veille : CoeurFavori → /party/musique).
                val nomSource = x.source.ifEmpty { bibliotheque() }
                val sous = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
                sous.addView(ImageView(this).apply {
                    setImageDrawable(IconeSource.creer(x.domaine, theme.or))
                    contentDescription = getString(R.string.musrech_source_desc, nomSource)
                    tooltipText = nomSource
                }, LinearLayout.LayoutParams(px(if (tele) 18f else 15f), px(if (tele) 18f else 15f)).apply { rightMargin = px(6f) })
                sous.addView(texte(listOf(Party.nomType(x.type), x.artiste, if (x.nb > 0) resources.getQuantityString(R.plurals.musrech_titres_n, x.nb, x.nb) else "").filter { it.isNotEmpty() }.joinToString(" · "), 13f, theme.encre2).apply { contentDescription = text; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                // Intégration du 15/10 : sur la télé, le cœur montre l'état « favori » sans prendre le focus. Focalisable, il
                // prenait la flèche droite venue du clavier et la flèche bas ramenait au clavier : « Lire » n'était plus
                // atteignable à la télécommande (PartyEcransTest.testParcoursTelecommande) ; posé après les boutons, il
                // poussait « Hinten anstellen » hors de la ligne (recette 720p/1080p). Au toucher (téléphone) : inchangé.
                val coeur = CoeurFavori(this, theme, x.uri, x.favori, surErreur = { m -> Ui.toast(this, m) })
                if (tele) { coeur.isFocusable = false; coeur.isFocusableInTouchMode = false }
                sous.addView(coeur, LinearLayout.LayoutParams(px(40f), px(40f)))
                col.addView(sous, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                if (x.auto) col.addView(texte(getString(R.string.mus_auto_ma), 11f, theme.encre2, Polices.demiGras(this)).apply {
                    maxLines = 1; isSingleLine = true; setPadding(px(8f), px(1f), px(8f), px(2f))
                    background = fond(0, 999f, theme.carteBord)
                    contentDescription = getString(R.string.mus_auto_ma_desc)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(4f) })
                ligne.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                fun bouton(t: String, comment: String, plein: Boolean, court: String = t): TextView {
                    val enCours = occupe == x.uri + "|" + comment
                    // Téléphone : le libellé court, toujours sur une ligne (la voix dit le libellé entier).
                    return texte(if (enCours) getString(R.string.choixmusique_en_cours) else if (!tele) court else t, 14f, if (plein) theme.fond else theme.encre, Polices.gras(this)).apply {
                        // Intégration du 15/10 : sur la télé, deux lignes au plus (« Als Nächstes spielen » à 130 % en 720p et
                        // 1080p était coupé) ; le téléphone garde son libellé court sur une ligne.
                        maxLines = if (tele) 2 else 1
                        gravity = Gravity.CENTER; minHeight = px(44f)
                        if (tele) setPadding(px(14f), px(8f), px(14f), px(8f)) else setPadding(px(12f), px(8f), px(12f), px(8f))
                        focusable(this, couleur = if (plein) theme.or else 0, bord = if (plein) theme.or else theme.carteBord)
                        alpha = if (occupe.isNotEmpty() && !enCours) 0.6f else 1f
                        contentDescription = getString(R.string.choixmusique_bouton_description, t, x.nom)
                        setOnClickListener { choisir(x, comment) }
                    }
                }
                // 1.86.10 (retour d'essai) : les mêmes actions pour un titre, un album, une playlist, un artiste ou une radio.
                // Rien ne joue → « Lire » ; sinon « Ajouter à la suite », « Jouer ensuite », et « ⋯ » (Remplacer la file).
                val principal = when {
                    !joue -> bouton(getString(R.string.mus_lire), "remplacer", true)
                    tele -> bouton(getString(R.string.choixmusique_ajouter_suite), "ajouter", true)
                    else -> bouton(getString(R.string.choixmusique_jouer_ensuite), "ensuite", true, court = getString(R.string.mus_ensuite))
                }
                actions.addView(principal, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = px(8f) })
                if (joue && tele) actions.addView(bouton(getString(R.string.choixmusique_jouer_ensuite), "ensuite", false, court = getString(R.string.mus_ensuite)), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = px(6f) })
                if (joue) actions.addView(bouton(if (tele) "⋯" else "⋮", "menu", false).apply { contentDescription = getString(R.string.mus_plus_choix, x.nom) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = px(6f) })
                // 1.86.15 (retour d'essai) : toucher la carte (titre, pochette) fait comme le bouton principal.
                if (!tele) carte.setOnClickListener { if (joue) choisir(x, "menu") else choisir(x, "remplacer") }
                liste.addView(carte, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(8f) })
                if (premier == null) premier = principal
            }
        }
        if (gardeFocus != null) (trouverDescription(liste, gardeFocus) ?: memeTitre(liste, gardeFocus.substringAfter(" : ")))?.let { v -> v.requestFocus() }
        // Télé : à l'ouverture, le focus va sur la première touche du clavier (ou le champ) ; ensuite on ne le déplace
        // plus de soi-même, sauf après Entrée (premier résultat).
        if (tele && !focusPose) { focusPose = true; (clavier?.premiere() ?: champRecherche)?.let { c -> c.post { c.requestFocus() } } }
        else if (tele && focusResultat && l.isNotEmpty()) { focusResultat = false; premier?.let { v -> v.post { v.requestFocus() } } }
    }


    private fun memeTitre(v: View, titre: String): View? {
        if (v.isFocusable && v.contentDescription?.toString()?.endsWith(" : $titre") == true) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) memeTitre(v.getChildAt(i), titre)?.let { return it }
        return null
    }

    private fun isDescendant(v: View, parent: View): Boolean { var p: android.view.ViewParent? = v.parent; while (p != null) { if (p === parent) return true; p = p.parent }; return false }
    private fun trouverDescription(v: View, d: String): View? {
        if (v.isFocusable && v.contentDescription?.toString()?.endsWith(d.substringAfter(" : ", d)) == true && v.contentDescription.toString().startsWith(d.substringBefore(" : "))) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) trouverDescription(v.getChildAt(i), d)?.let { return it }
        return null
    }

    private fun choisir(x: Party.Choix, comment: String) {
        if (comment != "menu") { jouer(x, comment); return }
        val choix = listOf(getString(R.string.choixmusique_ajouter_suite) to "ajouter", getString(R.string.choixmusique_jouer_ensuite) to "ensuite", getString(R.string.choixmusique_remplacer_file) to "remplacer")
        Ui.dialogue(this).setTitle(x.nom).setItems(choix.map { it.first }.toTypedArray()) { _, k -> jouer(x, choix[k].second) }
            .setNegativeButton(getString(R.string.commun_lot2_annuler), null).show()
    }

    /** L'état de la Party (quel lecteur joue la musique qu'on vient de choisir). */
    private fun envoyerEtat(cfg: Config): JSONObject =
        Net.post(this, cfg, "/api/pc_parental/party/etat", JSONObject().put("id", cfg.id).put("secret", cfg.secret), 8_000)

    private fun jouer(x: Party.Choix, comment: String, confirme: Boolean = false) {
        if (occupe.isNotEmpty()) { Ui.toast(this, getString(R.string.mus_demande_en_cours)); return }
        // 17-10 (l'auteur, point 14) : une confirmation discrète aux couleurs du thème, plus le grand œil (et plus de texte en dur).
        // Un ajout à la file : la ligne dit « en cours », puis la confirmation dit où le titre est arrivé.
        if (comment == "remplacer") Ui.confirmation(this, getString(R.string.party_lancement_playlist, x.nom))
        occupe = x.uri + "|" + comment; retour = ""
        remplir()
        main.postDelayed(finOccupe, 12_000)
        Thread {
            var msg = ""; var ok = false
            try {
                val cfg = Config(this)
                fun envoyer(c: JSONObject): JSONObject = Net.post(this, cfg, "/api/pc_parental/party/commande", c.put("id", cfg.id).put("secret", cfg.secret), 10_000)
                if (lancerIci) { envoyer(JSONObject().put("action", "lancer_ici")); lancerIci = false }
                val c = JSONObject().put("action", "jouer").put("uri", x.uri).put("type", x.type).put("comment", comment).put("titre", x.nom)
                if (confirme) c.put("confirme", true)
                intent.getStringExtra("lecteur")?.takeIf { it.isNotEmpty() }?.let { c.put("lecteur", it) }
                val r = envoyer(c)
                ok = r.optBoolean("ok"); msg = r.optString("retour")
                // La musique choisie ici : son lecteur pourra paraître sur la carte de l'accueil.
                if (ok) LecteursSuivis.noter(this, c.optString("lecteur").ifEmpty {
                    try { Party.analyser(envoyerEtat(cfg)).takeIf { it.actif }?.lecteur } catch (_: Exception) { null }
                })
            } catch (e: Net.Echec) {
                // 1.86.13 (retour d'essai) : plus de 50 titres à ajouter → la question, avec le nombre.
                if (e.code == 409 && e.corps?.optBoolean("confirmer") == true) {
                    val nb = e.corps.optInt("nb")
                    main.post {
                        main.removeCallbacks(finOccupe); occupe = ""; remplir()
                        Ui.dialogue(this).setTitle(getString(R.string.mus_ajouter_n_titre, nb))
                            .setMessage(getString(R.string.mus_ajouter_n_message, x.nom, nb))
                            .setPositiveButton(getString(R.string.mus_ajouter_les_n, nb)) { _, _ -> jouer(x, comment, confirme = true) }
                            .setNegativeButton(getString(R.string.commun_lot2_annuler), null).show()
                    }
                    return@Thread
                }
                msg = Party.messageErreur(e.code, e.corps)
            } catch (_: Exception) { msg = Party.messageErreur(0, null) }
            main.post {
                main.removeCallbacks(finOccupe)
                if (isFinishing) return@post
                occupe = ""
                // Le retour de Home Assistant dit où le titre est arrivé (« Ajouté — position 4. »).
                retour = if (ok) (if (msg.isNotEmpty()) getString(R.string.choixmusique_titre_message, x.nom, msg) else getString(R.string.choixmusique_titre_ajoute, x.nom)) else msg.ifEmpty { Party.messageErreur(-1, null) }
                if (ok) joue = true
                // Retours de l'auteur (15-10) : un résultat choisi garde sa recherche dans les récents.
                if (ok && recherche.isNotEmpty()) { RechercheMusique.noterRecent(this, recherche); majSuggestions() }
                remplir()
                // 1.86.10 (l'auteur : « Jouer ne fait rien ») : un échec se voit toujours, même si la liste a défilé.
                if (ok) Ui.confirmation(this, retour) else Ui.toast(this, retour, genre = OeilModele.Genre.ERREUR)
                // Une file remplacée (ou une Party qui démarre) : retour à l'écran Party, qui montre ce qui passe.
                if (ok && comment == "remplacer") main.postDelayed({ if (!isFinishing) finish() }, 600)
            }
        }.also { it.isDaemon = true }.start()
    }
}
