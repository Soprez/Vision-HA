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
 */
class ChoixMusiqueActivity : Activity() {
    companion object {
        /** [joue] : la Party joue déjà ; une playlist demande alors « Remplacer la file » ou « Ajouter à la suite ». */
        fun ouvrir(ctx: Context, lecteur: String, lecteurNom: String, lancerIci: Boolean = false, joue: Boolean = false) {
            try {
                ctx.startActivity(Intent(ctx, ChoixMusiqueActivity::class.java).putExtra("lecteur", lecteur)
                    .putExtra("lecteur_nom", lecteurNom).putExtra("lancer_ici", lancerIci).putExtra("joue", joue))
            } catch (_: Exception) {}
        }
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
        lancerIci = intent.getBooleanExtra("lancer_ici", false)
        joue = intent.getBooleanExtra("joue", false)
        filtreTypes = Party.filtresTypes(this); filtrePlateformes = Party.filtresPlateformes(this); plateformes = Party.plateformesConnues(this)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(theme.fond))
        construire()
        charger()
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

    private fun construire() {
        val sv = ScrollView(this).apply { setBackgroundColor(theme.fond); isFillViewport = true; isFocusable = false; descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS }
        // 1.86.0 : sur la télé, la zone sûre (5 % de chaque côté) ; rien ne touche les bords de l'écran.
        val dm = resources.displayMetrics
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (tele) setPadding((dm.widthPixels * 0.05f).toInt(), px(6f), (dm.widthPixels * 0.05f).toInt(), px(6f))
            else setPadding(px(20f), px(28f), px(20f), px(28f))
        }
        // 1.86.10 (photo de l'auteur : premier résultat coupé en haut) : sur la télé, la marge haute et basse (5 %) est celle
        // de la zone qui défile ; ce qui défile s'arrête à la marge, et le focus y reste entier (Ui.garderVisible).
        if (tele) sv.apply { setPadding(0, (dm.heightPixels * 0.05f).toInt(), 0, (dm.heightPixels * 0.05f).toInt()); clipToPadding = true }
        // Le focus sur un bouton d'un résultat : c'est toute la carte du résultat qui doit être visible.
        if (tele) sv.viewTreeObserver.addOnGlobalFocusChangeListener { _, n ->
            var p: android.view.ViewParent? = n?.parent
            while (p is View && p.tag != "carte") p = p.parent
            (p as? View)?.let { v -> v.postDelayed({ Ui.garderVisible(v, px(10f)) }, 30) }
        }
        sv.addView(c)
        c.addView(texte(getString(R.string.manifeste_choisir_musique), 26f, theme.encre, Polices.outfit(this)))
        val nom = intent.getStringExtra("lecteur_nom").orEmpty()
        if (nom.isNotEmpty()) c.addView(texte(getString(R.string.choixmusique_sur, nom), 14f, theme.encre2).apply { setPadding(0, px(4f), 0, 0) })

        val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(16f), 0, px(6f)) }
        val champ = EditText(this).apply {
            hint = getString(R.string.choixmusique_indice_catalogue); setText(recherche); textSize = if (tele) 18f else 16f
            setTextColor(theme.encre); setHintTextColor(theme.encre3); background = fond(theme.carte, 14f, theme.carteBord)
            setPadding(px(14f), px(12f), px(14f), px(12f)); inputType = android.text.InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            contentDescription = getString(R.string.choixmusique_rechercher)
            if (tele) { Ui.anneauFocus(this, theme, 14f, agrandir = false); textSize = 20f; setPadding(px(18f), px(16f), px(18f), px(16f)) }
            champRecherche = this
            setOnEditorActionListener { v, a, e ->
                if (a == EditorInfo.IME_ACTION_SEARCH || (e?.keyCode == KeyEvent.KEYCODE_ENTER && e.action == KeyEvent.ACTION_DOWN)) { chercher(v.text.toString()); true } else false
            }
        }
        r.addView(champ, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val b = texte(getString(R.string.detailfermes_chercher), 15f, theme.fond, Polices.gras(this)).apply {
            gravity = Gravity.CENTER; setPadding(px(16f), px(12f), px(16f), px(12f)); minHeight = px(48f)
            focusable(this, couleur = theme.or, bord = theme.or)
            setOnClickListener { chercher(champ.text.toString()) }
        }
        r.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = px(8f) })
        c.addView(r)

        val onglets = Pastilles(this, px(8f))
        for ((cle, t) in listOf("playlist" to getString(R.string.choixmusique_playlists), "favoris" to getString(R.string.choixmusique_favoris), "radio" to getString(R.string.choixmusique_radios))) {
            onglets.addView(texte(t, 15f, if (onglet == cle && recherche.isEmpty()) theme.encre else theme.encre2, Polices.demiGras(this)).apply {
                setPadding(px(16f), px(10f), px(16f), px(10f)); minHeight = px(44f); gravity = Gravity.CENTER_VERTICAL
                focusable(this, couleur = if (onglet == cle && recherche.isEmpty()) Palette.melanger(theme.carte, theme.or, 0.22f) else 0,
                    bord = if (onglet == cle && recherche.isEmpty()) theme.or else theme.carteBord)
                contentDescription = if (onglet == cle && recherche.isEmpty()) getString(R.string.choixmusique_onglet_choisi, t) else t
                setOnClickListener { onglet = cle; recherche = ""; construire(); charger() }
            })
        }
        c.addView(onglets)
        // Les filtres de la recherche (pendant une recherche) : type, puis plateforme (« Tout » / « Toutes » = aucun choisi).
        if (recherche.isNotEmpty()) {
        c.addView(texte(getString(R.string.mus_type), 13f, theme.encre3).apply { setPadding(px(2f), px(12f), 0, px(4f)) })
        val rt = Pastilles(this, px(8f))
        val types = listOf("" to getString(R.string.mus_tout), "track" to getString(R.string.mus_titres), "album" to getString(R.string.mus_albums), "playlist" to getString(R.string.mus_playlists), "artist" to getString(R.string.mus_artistes), "radio" to getString(R.string.mus_radios))
        types.forEach { (cle, t) -> rt.addView(puceFiltre(t, if (cle.isEmpty()) filtreTypes.isEmpty() else cle in filtreTypes) {
            filtreTypes = if (cle.isEmpty()) emptySet() else if (cle in filtreTypes) filtreTypes - cle else filtreTypes + cle
            filtresChanges()
        }) }
        c.addView(rt)
        if (plateformes.size > 1) {
            c.addView(texte(getString(R.string.mus_plateforme), 13f, theme.encre3).apply { setPadding(px(2f), px(10f), 0, px(4f)) })
            val rp = Pastilles(this, px(8f))
            rp.addView(puceFiltre(getString(R.string.mus_toutes), filtrePlateformes.isEmpty()) { filtrePlateformes = emptySet(); filtresChanges() })
            plateformes.forEach { pl -> rp.addView(puceFiltre(pl.nom, pl.domaine in filtrePlateformes) {
                filtrePlateformes = if (pl.domaine in filtrePlateformes) filtrePlateformes - pl.domaine else filtrePlateformes + pl.domaine
                filtresChanges()
            }) }
            c.addView(rp)
        }
        }
        statut = texte("", 14f, theme.encre2).apply { setPadding(0, px(10f), 0, px(4f)); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        c.addView(statut)
        liste = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.addView(liste)
        setContentView(sv)
        remplir()
    }

    private fun chercher(t: String) {
        recherche = t.trim()
        if (recherche.length < 2) { retour = getString(R.string.choixmusique_deux_lettres); remplir(); return }
        // 1.86.1 (télé) : le clavier se ferme et le focus ira sur le premier résultat dès qu'il arrive.
        if (tele) { try { (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(window.decorView.windowToken, 0) } catch (_: Exception) {} }
        construire()
        if (tele) focusResultat = true
        charger()
    }

    /** Une puce de filtre : choisie ou non, même focus télécommande que le reste. */
    private fun puceFiltre(t: String, choisie: Boolean, action: () -> Unit): TextView =
        texte(t, 14f, if (choisie) theme.encre else theme.encre2, if (choisie) Polices.demiGras(this) else Polices.texte(this)).apply {
            setPadding(px(14f), px(8f), px(14f), px(8f)); minHeight = px(40f); gravity = Gravity.CENTER_VERTICAL; maxLines = 1
            focusable(this, couleur = if (choisie) Palette.melanger(theme.carte, theme.or, 0.22f) else 0, bord = if (choisie) theme.or else theme.carteBord)
            contentDescription = t + if (choisie) ", choisi" else ""
            setOnClickListener { action() }
        }

    private fun filtresChanges() {
        Party.poserFiltres(this, filtreTypes, filtrePlateformes)
        val focus = currentFocus?.contentDescription?.toString()?.removeSuffix(", choisi")
        construire()
        if (focus != null) champRecherche?.rootView?.let { r -> trouverFiltre(r, focus)?.requestFocus() }
        if (recherche.isNotEmpty()) charger() else remplir()
    }

    private fun trouverFiltre(v: View, t: String): View? {
        if (v.isFocusable && v.contentDescription?.toString()?.removeSuffix(", choisi") == t) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) trouverFiltre(v.getChildAt(i), t)?.let { return it }
        return null
    }

    private fun charger() {
        choix = null; erreur = ""; remplir()
        val corps = if (recherche.isNotEmpty()) JSONObject().put("quoi", "chercher").put("texte", recherche)
            .put("types", org.json.JSONArray(filtreTypes.toList())).put("plateformes", org.json.JSONArray(filtrePlateformes.toList()))
            else JSONObject().put("quoi", onglet)
        val demande = recherche to onglet
        Thread {
            var l: List<Party.Choix>? = null; var err = ""; var cons = ""
            try {
                val cfg = Config(this)
                val r = Net.post(this, cfg, "/api/pc_parental/party/musique", corps.put("id", cfg.id).put("secret", cfg.secret), 15_000)
                l = Party.analyserChoix(r); cons = Party.conseilRecherche(r)
                Party.analyserPlateformes(r).takeIf { it.isNotEmpty() }?.let { pl -> Party.garderPlateformes(this, pl); main.post { if (pl != plateformes) { plateformes = pl; construire() } } }
            } catch (x: Net.Echec) { err = Party.messageErreur(x.code, x.corps) } catch (_: Exception) { err = Party.messageErreur(0, null) }
            main.post {
                if (isFinishing || demande != (recherche to onglet)) return@post
                choix = l; erreur = err; conseil = cons; remplir()
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun vide(): String = when {
        recherche.isNotEmpty() -> getString(R.string.choixmusique_rien_trouve, recherche)
        onglet == "radio" -> getString(R.string.choixmusique_aucune_radio)
        onglet == "favoris" -> getString(R.string.choixmusique_aucun_favori)
        else -> getString(R.string.choixmusique_aucune_playlist)
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
        statut.setTextColor(if (erreur.isNotEmpty() && retour.isEmpty()) theme.pourpre else theme.encre2)
        if (l == null) return
        var premier: View? = null
        // 1.86.0 (retour d'essai) : les résultats par service (YouTube Music, Bibliothèque…) ; un titre s'AJOUTE à la suite par
        // défaut (le morceau en cours continue), « Jouer ensuite » le place juste après.
        val groupes = if (recherche.isNotEmpty())
            l.groupBy { it.type }.toList().sortedBy { Party.ORDRE_RECHERCHE.indexOf(it.first).let { i -> if (i < 0) 99 else i } }
        else l.groupBy { it.source.ifEmpty { "Bibliothèque" } }.toList().sortedBy { if (it.first == "Bibliothèque") 0 else 1 }
        groupes.forEach { (source, items) ->
            val titreGroupe = if (recherche.isNotEmpty()) Party.nomSection(source) else if (source == "Bibliothèque") getString(R.string.choixmusique_dans_bibliotheque) else getString(R.string.choixmusique_sur_source, source)
            if (groupes.size > 1 || recherche.isNotEmpty()) liste.addView(texte(titreGroupe, 14f, theme.or, Polices.gras(this)).apply { setPadding(px(2f), px(14f), 0, px(2f)) })
            items.forEach { x ->
                // 1.86.9 (capture de l'auteur) : au téléphone, la pochette et le titre ont toute la largeur, les actions passent
                // dessous (avant : le titre écrasé en colonnes de 3 lettres par deux gros boutons) ; la télé garde une ligne.
                val carte = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = fond(theme.carte, 16f, theme.carteBord); setPadding(px(14f), px(10f), px(10f), px(10f)); tag = "carte" }
                val ligne = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
                carte.addView(ligne)
                // 1.86.12 (l'auteur : « le bouton Lire est trop bas ») : au téléphone aussi, l'action est sur la ligne du titre
                // (carte compacte) : une action principale et « ⋮ » pour le reste.
                // Très petit écran ou très grande police : les actions repassent sous le titre (il garde sa place).
                val compacte = tele || resources.configuration.screenWidthDp / resources.configuration.fontScale >= 300f
                val actions = if (compacte) ligne else LinearLayout(this).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL; setPadding(0, px(8f), 0, 0) }.also { carte.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
                ligne.addView(ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
                    background = fond(Palette.melanger(theme.carte, theme.encre, 0.08f), 8f)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    Vignettes.poser(this, x.image, px(if (tele) 56f else 48f) * 2)
                }, LinearLayout.LayoutParams(px(if (tele) 56f else 48f), px(if (tele) 56f else 48f)).apply { rightMargin = px(12f) })
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                col.addView(texte(x.nom, 16f, theme.encre, Polices.demiGras(this)).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; contentDescription = x.nom })
                col.addView(texte(listOf(Party.nomType(x.type), x.artiste, if (x.nb > 0) "${x.nb} titre" + (if (x.nb > 1) "s" else "") else "").filter { it.isNotEmpty() }.joinToString(" · "), 13f, theme.encre2).apply { contentDescription = text; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                if (x.auto) col.addView(texte(getString(R.string.mus_auto_ma), 11f, theme.encre2, Polices.demiGras(this)).apply {
                    maxLines = 1; isSingleLine = true; setPadding(px(8f), px(1f), px(8f), px(2f))
                    background = fond(0, 999f, theme.carteBord)
                    contentDescription = getString(R.string.mus_auto_ma_desc)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(4f) })
                // La plateforme du résultat, en pastille (couleur de la plateforme).
                col.addView(texte(x.source.ifEmpty { "Bibliothèque" }, 11f, theme.encre, Polices.demiGras(this)).apply {
                    maxLines = 1; isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(px(8f), px(1f), px(8f), px(2f))
                    background = fond(Palette.melanger(theme.carte, Party.couleurPlateforme(x.domaine, theme.or), 0.35f), 999f)
                    contentDescription = "Source : " + x.source.ifEmpty { "Bibliothèque" }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(4f) })
                ligne.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                fun bouton(t: String, comment: String, plein: Boolean, court: String = t): TextView {
                    val enCours = occupe == x.uri + "|" + comment
                    // Téléphone étroit ou grande police : le libellé court, toujours sur une ligne (la voix dit le libellé entier).
                    val etroit = !tele  // 1.86.12 : au téléphone, le libellé court (la carte reste compacte)
                    return texte(if (enCours) getString(R.string.choixmusique_en_cours) else if (etroit) court else t, 14f, if (plein) theme.fond else theme.encre, Polices.gras(this)).apply {
                        maxLines = 1
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
                // 1.86.15 (l'auteur : « je clique sur Disney classique, il ne se passe rien ») : toucher la carte (titre, pochette)
                // fait comme le bouton principal ; la musique joue déjà → le choix (ajouter, ensuite, remplacer).
                if (!tele) carte.setOnClickListener { if (joue) choisir(x, "menu") else choisir(x, "remplacer") }
                liste.addView(carte, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(8f) })
                if (premier == null) premier = principal
            }
        }
        // 1.86.1 (retour d'essai) : à l'ouverture, le focus va sur « Rechercher » ; ensuite on ne le déplace plus de soi-même.
        // « Lire » devient « Ajouter à la suite » une fois la musique lancée : le focus reste sur le même titre.
        if (gardeFocus != null) (trouverDescription(liste, gardeFocus) ?: memeTitre(liste, gardeFocus.substringAfter(" : ")))?.let { v -> v.requestFocus() }
        if (tele && !focusPose) { focusPose = true; champRecherche?.let { c -> c.post { c.requestFocus() } } }
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

    private fun jouer(x: Party.Choix, comment: String, confirme: Boolean = false) {
        if (occupe.isNotEmpty()) { Ui.toast(this, getString(R.string.mus_demande_en_cours)); return }
        Ui.toast(this, "« ${x.nom} » : envoi…", court = true)
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
                remplir()
                // 1.86.10 (l'auteur : « Jouer ne fait rien ») : un échec se voit toujours, même si la liste a défilé.
                Ui.toast(this, retour, court = ok)
                // Une file remplacée (ou une Party qui démarre) : retour à l'écran Party, qui montre ce qui passe.
                if (ok && comment == "remplacer") main.postDelayed({ if (!isFinishing) finish() }, 600)
            }
        }.also { it.isDaemon = true }.start()
    }
}
