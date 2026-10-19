// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * Un widget Home Assistant de l'accueil (ou l'aperçu de l'éditeur), dans les couleurs du thème actif.
 * Les textes passent à la ligne et, si la case est trop petite, rapetissent tous ensemble (ContenuAjuste) :
 * rien n'est coupé. Toucher un widget en lecture seule montre tout son texte (`details`).
 * Graphique, jauge, bascule et pictogrammes sont dessinés sur un Canvas, sans bibliothèque.
 */
class VueWidgetHa(
    ctx: Context,
    private val theme: Theme,
    var reglage: WidgetsHa.Reglage,
    private val lectureSeule: Boolean,
    /** Aperçu : sans Home Assistant (ou en cas d'échec), des données d'exemple. */
    private val exemple: Boolean = false,
    private val agir: ((WidgetsHa.Entite, String) -> Unit)? = null,
    private val details: ((String, String) -> Unit)? = null,
    /** 1.76.0 : l'historique déjà chargé (widget système dessiné d'un coup, sans attendre le réseau). */
    historiqueInitial: WidgetsHa.Historique? = null,
    /** 1.81.0 (lecteur) : une action avec données (volume). */
    private val agirAvec: ((WidgetsHa.Entite, String, org.json.JSONObject) -> Unit)? = null,
    /** 1.90.1 : widget d'écran d'accueil à liste défilante — la zone du détail reste vide ([zoneDetail]), le système y
     *  pose sa propre liste. */
    private val detailExterne: Boolean = false,
    /** 16-10 (télé) : le menu du widget (détail, modifier, retirer) — touche Menu, ou appui long sur ses commandes. */
    private val menu: (() -> Unit)? = null,
) : FrameLayout(ctx) {
    /** 16-10 : vrai si le widget agit (interrupteur, bouton, réglage, liste) — sur la télé, OK le fait agir. */
    var pilote = false
        private set

    /** 16-10 (télé) : les commandes du widget qui prennent le focus de la télécommande (− et +). */
    fun commandes(): List<View> = listOfNotNull(findViewWithTag("widget_moins"), findViewWithTag("widget_plus"))
    /** 1.90.1 : la zone laissée au détail quand [detailExterne] (sa position sert à couper l'image du widget). */
    var zoneDetail: View? = null
        private set
    /** Lecteur à plusieurs enceintes : celle qu'on pilote (toucher son nom passe à la suivante). */
    private var enceinte = 0
    /** 1.82.1 : ce que montre le widget ; inchangé → aucun redessin. Déclaré AVANT init (qui appelle maj). */
    private var signature = ""
    @Volatile var redessins = 0
    private val main = Handler(Looper.getMainLooper())
    private var historique: WidgetsHa.Historique? = null
    private var historiqueCle = ""
    private var historiqueEchec = false
    /** 1.93.06 (l'auteur, 19/10) : l'heure du chargement de [historique], pour le recharger quand il est périmé. */
    private var historiqueChargeA = 0L
    @Volatile private var historiqueEnCours = false

    init {
        if (historiqueInitial != null) { historique = historiqueInitial; historiqueCle = reglage.entites.first() + "|" + reglage.periode; historiqueChargeA = System.currentTimeMillis() }
        background = GradientDrawable().apply { cornerRadius = dp(24f); setColor(theme.carte); setStroke(dp(1f).toInt(), theme.carteBord) }
        clipToPadding = false
        maj()
    }

    private fun dp(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    /**
     * 16-10 (l'auteur : « parfois seule la moitié du widget est dessinée ») : le widget était bâti avant de connaître sa taille
     * (0 × 0 au premier passage) et ne se rebâtissait qu'au prochain changement d'état ; il se refait maintenant dès
     * que sa taille change (la signature garde d'un redessin inutile).
     */
    override fun onSizeChanged(w: Int, h: Int, ancienW: Int, ancienH: Int) {
        super.onSizeChanged(w, h, ancienW, ancienH)
        if (w != ancienW || h != ancienH) main.post { maj() }
    }

    /** L'entité (catalogue, ou exemple), et son état le plus frais (flux, sinon catalogue). */
    private fun entite(id: String): WidgetsHa.Entite =
        WidgetsHaStock.entite(context, id) ?: if (exemple) WidgetsHa.exemple(reglage.style).copy(entityId = id)
        else WidgetsHa.Entite(id, id, id.substringBefore('.'), "", "", "", "", emptyMap(), emptyList(), emptyList())

    private fun etat(e: WidgetsHa.Entite): WidgetsHa.EtatEntite {
        val st = FluxWidgets.etats[e.entityId] ?: WidgetsHa.EtatEntite(e.etat, e.attributs, "")
        // 17-10 : la valeur demandée (− / +) reste montrée jusqu'à la confirmation de Home Assistant ; la ligne sous la
        // valeur vient du flux, sinon du catalogue.
        return AppuisReglage.affiche(e.entityId, st, android.os.SystemClock.elapsedRealtime()).let { if (it.texte == null) it.copy(texte = e.texte) else it }
    }

    /**
     * 17-10 (l'auteur, « widget chauffage lisible » : « 16 » seul faisait croire à une panne en mode Réduit) : la ligne sous la
     * valeur (« Réduit jusqu'à 12:00, puis Confort 20 °C »), aux couleurs du thème. Deux lignes au plus, la police
     * rapetisse pour tenir, jamais de « … » ; en une case de large, le mode et l'heure seulement.
     */
    /**
     * 17-10 (recette télé : en 2 × 2 dans une zone de l'accueil, la carte fait 112 dp de haut ; le nom, la valeur et − / +
     * la remplissent et la ligne tombait sous le bord, coupée) : la place qui reste sous [occupe] (dp, la hauteur des
     * autres éléments) décide — la phrase entière, sa forme courte sur une ligne, ou rien (elle reste dite). Hauteur
     * pas encore connue : la phrase (le widget se redessine dès qu'il connaît sa taille).
     */
    private fun ligneSelonPlace(c: LinearLayout, st: WidgetsHa.EtatEntite, occupe: Float, gravite: Int = Gravity.CENTER_HORIZONTAL) {
        if (height <= 0) { ligneTexte(c, st, gravite); return }
        val reste = height - dp(occupe)
        when {
            reste < dp(16f) -> return
            reste < dp(34f) -> ligneTexte(c, st, gravite, court = true)
            else -> ligneTexte(c, st, gravite)
        }
    }

    private fun ligneTexte(c: LinearLayout, st: WidgetsHa.EtatEntite, gravite: Int = Gravity.CENTER_HORIZONTAL, court: Boolean = false) {
        val t = st.texte?.trim().orEmpty()
        if (t.isEmpty()) return
        // 1 × 1 : pas la place pour le nom, la valeur, − / + ET une ligne (recette) ; le nom et − / + restent (l'auteur, 16-10),
        // la ligne se lit dans la description et le détail. Conseil : 2 × 1 au moins pour une consigne.
        if (reglage.w <= 1 && reglage.h < 2) return
        val montre = if (court || reglage.w <= 1) WidgetsHa.texteCourt(t) else t
        val max = if (petit()) 12f else 14f
        c.addView(texte(montre, max, theme.encre2).apply {
            tag = "widget_texte"; gravity = gravite; contentDescription = t; setTag(R.id.nom_ajuste, true)
            // La forme courte (une case de haut, entre − et +) tient sur UNE ligne (recette 360 dp à 130 % : sur deux lignes,
            // elle sortait du widget) ; la phrase entière, sur deux au plus.
            if (court) Ui.surUneLigne(this, max, 8f) else Ui.surDeuxLignes(this, max, 9f)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2f).toInt() })
    }

    private fun libelle(e: WidgetsHa.Entite) = reglage.libelle.ifEmpty { e.nom }


    private fun signatureCourante(): String = buildString {
        append(reglage).append('|').append(enceinte).append('|').append(lectureSeule).append('|').append(exemple).append('|')
        // 16-10 : la taille réelle (par pas de 8 dp) — la mise en page se refait quand le widget connaît enfin sa taille.
        append(petit()).append('|').append((width / dp(8f)).toInt()).append('x').append((height / dp(8f)).toInt()).append('|')
        reglage.entites.forEach { id -> val e = entite(id); append(e).append(etat(e)).append(';') }
        append(System.identityHashCode(historique)).append(historiqueEchec)
    }

    /** Relit les états et redessine (appelé par le flux, ou quand le réglage change) ; seulement si ça a changé. */
    fun maj() {
        // Hors ligne : seulement estompé, sans rien redessiner.
        alpha = if (!exemple && FluxWidgets.statut == FluxWidgets.Statut.HORS_LIGNE) 0.75f else 1f
        val sig = signatureCourante()
        if (sig == signature && childCount > 0) return
        signature = sig
        redessins++
        // 17-10 (l'auteur, retour 20, recette télé) : sur la télé, OK sur − redessine le widget (la valeur demandée s'affiche) ;
        // le − pris disparaissait avec l'ancien dessin et le focus partait ailleurs sur l'accueil — l'appui suivant ne
        // réglait plus la consigne. Le focus est gardé dans le widget le temps du redessin, puis rendu à la même commande.
        val focusAvant = findFocus()?.tag as? String
        if (focusAvant != null && isFocusable) requestFocus()
        removeAllViews()
        listeDetail = null
        defilement = null
        val e = entite(reglage.entites.first())
        val st = etat(e)
        val contenu = ContenuAjuste(context).apply { orientation = LinearLayout.VERTICAL; val p = dp(12f).toInt(); setPadding(p, p, p, p) }
        addView(contenu, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val valeur = WidgetsHa.valeurAffichee(st.etat, e.unite, e.domaine, e.deviceClass)
        when (reglage.style) {
            WidgetsHa.BOUTON -> bouton(contenu, e)
            WidgetsHa.INTERRUPTEUR -> interrupteur(contenu, e, st)
            WidgetsHa.JAUGE -> jauge(contenu, e, st, valeur)
            WidgetsHa.GRAPHIQUE -> graphique(contenu, e, valeur)
            WidgetsHa.METEO -> meteo(contenu, e, st)
            WidgetsHa.OUVERTURE -> ouverture(contenu, e, st)
            WidgetsHa.MINI_CARTE -> miniCarte(contenu)
            WidgetsHa.LECTEUR -> lecteur(contenu)
            WidgetsHa.REGLAGE -> reglerValeur(contenu, e, st, valeur)
            else -> tuile(contenu, e, st, valeur)
        }
        if (focusAvant != null) findViewWithTag<View>(focusAvant)?.takeIf { it.isFocusable }?.requestFocus()
        contentDescription = if (reglage.style == WidgetsHa.LECTEUR) descriptionLecteur()
            else if (reglage.style == WidgetsHa.MINI_CARTE) reglage.entites.joinToString(" · ") { val x = entite(it); x.nom + " " + WidgetsHa.valeurAffichee(etat(x).etat, x.unite, x.domaine, x.deviceClass) } else context.getString(R.string.vuewidget_libelle_valeur, libelle(e), valeur) +
                // 17-10 : la ligne sous la valeur est toujours dite (même en 1 × 1, où elle ne s'affiche pas).
                (st.texte?.trim()?.takeIf { it.isNotEmpty() }?.let { ", $it" } ?: "")
        alpha = if (!exemple && FluxWidgets.statut == FluxWidgets.Statut.HORS_LIGNE) 0.75f else 1f
        val pilote = !lectureSeule && (agir != null || agirAvec != null) && WidgetsHa.pilotable(WidgetsHaStock.entite(context, e.entityId) ?: if (exemple) e else null, false)
        this.pilote = pilote
        setOnClickListener {
            // 1.90.0 (retour d'essai) : « Au toucher » — les détails de l'entité ou une vue de Home Assistant, ou rien.
            if (!exemple && !lectureSeule) {
                val fraiche = WidgetsHaStock.entite(context, e.entityId) ?: e
                val (t, c) = WidgetsHa.toucher(reglage, fraiche)
                when (t) {
                    WidgetsHa.TOUCHER_RIEN -> return@setOnClickListener
                    WidgetsHa.TOUCHER_DETAILS -> { OuvrirHa.ouvrir(context, OuvrirHa.cheminDetails(e.entityId)); return@setOnClickListener }
                    WidgetsHa.TOUCHER_VUE -> { OuvrirHa.ouvrir(context, c); return@setOnClickListener }
                }
            }
            when {
                pilote && reglage.style == WidgetsHa.INTERRUPTEUR -> WidgetsHa.serviceBascule(e, st.etat)?.let { agir?.invoke(e, it) } ?: montrerDetails()
                pilote && reglage.style == WidgetsHa.BOUTON -> WidgetsHa.serviceBouton(e)?.let { agir?.invoke(e, it) } ?: montrerDetails()
                pilote && reglage.style == WidgetsHa.REGLAGE && "select_option" in e.actions -> choisirOption(e, st)
                else -> montrerDetails()
            }
        }
    }

    /** Les détails de l'entité (menu « Voir le détail » de la télé), sans faire agir le widget. */
    fun voirDetails() = montrerDetails()

    private fun montrerDetails() {
        val d = details ?: return
        val lignes = reglage.entites.map { id ->
            val x = entite(id); val s = etat(x)
            val depuis = depuis(s.depuis)
            "${x.nom} : ${WidgetsHa.valeurAffichee(s.etat, x.unite, x.domaine, x.deviceClass)}" + (if (depuis.isNotEmpty()) " ($depuis)" else "")
        }
        val pilotable = WidgetsHa.pilotable(WidgetsHaStock.entite(context, reglage.entites.first()), false)
        val note = when {
            lectureSeule -> context.getString(R.string.vuewidget_lecture_seule_ecran)
            reglage.style in setOf(WidgetsHa.INTERRUPTEUR, WidgetsHa.BOUTON) && !pilotable -> context.getString(R.string.vuewidget_lecture_seule_entite)
            else -> ""
        }
        // 1.88.0 : le détail complet (éléments d'une liste, attributs) de la première entité, s'il y en a.
        val premiere = entite(reglage.entites.first())
        val (detail, total) = WidgetsHa.detail(premiere, etat(premiere))
        val lignesDetail = detail.map { "• " + WidgetsHa.texteLigne(it) } +
            listOfNotNull(if (total > detail.size) context.getString(R.string.wha_detail_autres, total - detail.size) else null)
        d(reglage.libelle.ifEmpty { premiere.nom }, (lignes + lignesDetail + listOfNotNull(note.ifEmpty { null })).joinToString("\n"))
    }

    // ------------------------------------------------------------------ fabricants

    private fun texte(t: String, taille: Float, couleur: Int, police: android.graphics.Typeface = Polices.texte(context)): TextView =
        Ui.sansCesure(TextView(context).apply { text = t; textSize = taille; setTextColor(couleur); typeface = police; includeFontPadding = false; setLineSpacing(0f, 1.05f) })

    private fun picto(e: WidgetsHa.Entite, couleur: Int = theme.or): View? {
        val nom = WidgetsHa.picto(reglage, e) ?: return null
        return Picto(context, nom, couleur)
    }

    private fun petit(): Boolean = height in 1 until dp(110f).toInt() || width in 1 until dp(110f).toInt()

    /**
     * Le titre du widget : picto et nom. 1.91.3 (mise en page) : chacun peut être caché, et la ligne s'aligne à gauche, au
     * centre ou à droite ([graviteH]) ; par défaut (autres styles) : picto et nom, à gauche.
     */
    private fun entete(c: LinearLayout, e: WidgetsHa.Entite, couleurPicto: Int = theme.or, avecNom: Boolean = true, avecPicto: Boolean = true,
                       graviteH: Int = Gravity.START) {
        if (!avecNom && !avecPicto) return
        val r = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL or graviteH; tag = "widget_titre" }
        // 16-10 : un widget d'une case de large laisse sa place au nom (le picto, décor, s'efface quand le nom est là).
        if (avecPicto && !(avecNom && reglage.w <= 1)) picto(e, couleurPicto)?.let { r.addView(it, LinearLayout.LayoutParams(dp(20f).toInt(), dp(20f).toInt()).apply { if (avecNom) rightMargin = dp(8f).toInt() }) }
        if (avecNom) {
            // 16-10 : le nom rapetisse (jusqu'à 9 sp, voir nomMinSp) pour tenir avant d'en venir aux « … » (petit widget, police 130 %).
            // ContenuAjuste ne le touche pas (tag « nom_ajuste ») : il remettait la taille d'origine à chaque mesure.
            val nom = nomHa(libelle(e), 13f, theme.encre2, Polices.demiGras(context)).also { it.setTag(R.id.nom_ajuste, true); Ui.surUneLigneOuPoints(it, 13f, nomMinSp()) }
            // À gauche : le nom prend la place qui reste (« … » au bout) ; centré ou à droite : il garde sa largeur.
            if (graviteH == Gravity.START) r.addView(nom, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            else r.addView(nom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 0f })
        }
        c.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** 1.87.0 (recette visuelle) : un nom venu de Home Assistant, d'une longueur quelconque : une ligne, « … » au bout,
     *  le nom entier en description (avant : coupé au milieu d'un mot en grands caractères). */
    /**
     * Le plus petit corps du nom d'un widget : 9 sp, à la taille qu'il aurait avec la police à 100 %. 16-10 (recette 360 dp,
     * police 130 %) : en 2 × 1 sur un petit téléphone, « Température ambiante » ne tenait pas à 9 sp agrandis et prenait des « … ».
     */
    private fun nomMinSp(): Float = 9f / maxOf(1f, resources.configuration.fontScale)

    private fun nomHa(t: String, taille: Float, couleur: Int, police: android.graphics.Typeface): TextView =
        texte(t, taille, couleur, police).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; contentDescription = t }

    private fun ressort(c: LinearLayout) = c.addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))

    // ------------------------------------------------------------------ styles

    /** 1.91.3 : la gravité horizontale et verticale de la mise en page [m]. */
    private fun graviteH(m: WidgetsHa.MiseEnPage) = when (m.alignH) { WidgetsHa.GAUCHE -> Gravity.START; WidgetsHa.DROITE -> Gravity.END; else -> Gravity.CENTER_HORIZONTAL }
    private fun graviteV(m: WidgetsHa.MiseEnPage) = when (m.alignV) { WidgetsHa.HAUT -> Gravity.TOP; WidgetsHa.BAS -> Gravity.BOTTOM; else -> Gravity.CENTER_VERTICAL }

    /** 1.91.3 : l'en-tête selon la mise en page (titre, icône, alignement) — pour les styles qui ont un titre. */
    private fun enteteSelon(c: LinearLayout, e: WidgetsHa.Entite, couleurPicto: Int = theme.or) {
        val m = WidgetsHa.miseEnPage(reglage, e)
        entete(c, e, couleurPicto, avecNom = m.titre, avecPicto = m.icone, graviteH = graviteH(m))
    }

    /** 1.91.3 : la taille (sp) d'une valeur en texte : S..XL selon la mise en page, sinon [parDefaut] (« remplir »). */
    private fun tailleValeur(e: WidgetsHa.Entite, parDefaut: Float): Float {
        val m = WidgetsHa.miseEnPage(reglage, e)
        return if (m.taille == WidgetsHa.REMPLIR) parDefaut else WidgetsHa.tailleSp(m.taille, petit())
    }

    private fun tuile(c: LinearLayout, e: WidgetsHa.Entite, st: WidgetsHa.EtatEntite, valeur: String) {
        // 1.91.3 (retour d'essai) : la mise en page réglée dans Home Assistant (ou en cours de choix dans l'éditeur).
        val m = WidgetsHa.miseEnPage(reglage, e)
        val gh = graviteH(m)
        val gv = graviteV(m)
        entete(c, e, avecNom = m.titre, avecPicto = m.icone, graviteH = gh)
        val aff = WidgetsHa.affichage(reglage, e)
        if (!WidgetsHa.montreDetail(aff)) {
            // La valeur seule : toute la place sous le titre, alignée comme réglé ; « remplir » : la plus grande qui tient.
            val v: View = if (m.taille == WidgetsHa.REMPLIR) ValeurQuiRemplit(context, valeur, theme.encre, Polices.outfitLeger(context), gh or gv)
                else texte(valeur, WidgetsHa.tailleSp(m.taille, petit()), theme.encre, Polices.outfitLeger(context)).apply { gravity = gh or gv }
            v.tag = "widget_valeur"
            c.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            ligneTexte(c, st, gh)
            return
        }
        if (WidgetsHa.montreValeur(aff)) {
            val taille = if (m.taille == WidgetsHa.REMPLIR) (if (petit()) 18f else 24f) else WidgetsHa.tailleSp(m.taille, petit())
            c.addView(texte(valeur, taille, theme.encre, Polices.outfitLeger(context)).apply { setPadding(0, dp(2f).toInt(), 0, 0); gravity = gh; tag = "widget_valeur" },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        detailCourt(c, e, st)
    }

    /**
     * 1.91.3 : la valeur « remplir » — une ligne, la plus grande taille qui tient dans la place donnée (bornée de 12 à
     * 160 sp), alignée par [gravite]. Dessinée à part : la réduction d'ensemble des textes (ContenuAjuste) ne la touche
     * pas, et elle ne réclame que peu de hauteur à la mesure (elle prend ce qui reste).
     */
    private class ValeurQuiRemplit(ctx: Context, private val valeur: String, couleur: Int, police: android.graphics.Typeface,
                                   private val gravite: Int) : TextView(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = couleur; typeface = police; textSize = 100f }
        private val sp = ctx.resources.displayMetrics.scaledDensity
        private val largeurA100 = p.measureText(valeur)
        private val hauteurA100 = p.fontMetrics.let { it.descent - it.ascent }

        // Le texte est porté (lecteurs d'écran, essais), mais dessiné ici : la taille du TextView n'y joue pas.
        init { text = valeur; includeFontPadding = false }

        override fun onMeasure(l: Int, h: Int) {
            val min = (14f * sp * 1.3f).toInt()
            // La place qui reste (poids 1) arrive en EXACTLY ; sinon (mesure libre), juste une ligne de texte courant.
            setMeasuredDimension(MeasureSpec.getSize(l), if (MeasureSpec.getMode(h) == MeasureSpec.EXACTLY) MeasureSpec.getSize(h) else min)
        }

        /** La taille (px) utilisée au dernier dessin (essais). */
        var tailleDessinee = 0f; private set

        override fun onDraw(c: Canvas) {
            val w = (width - paddingLeft - paddingRight).toFloat(); val h = (height - paddingTop - paddingBottom).toFloat()
            val t = WidgetsHa.tailleQuiTient(largeurA100, hauteurA100, w, h, 12f * sp, 160f * sp)
            tailleDessinee = t
            p.textSize = t
            val fm = p.fontMetrics
            val lt = p.measureText(valeur)
            val x = when (gravite and Gravity.HORIZONTAL_GRAVITY_MASK) {
                Gravity.START, Gravity.LEFT -> paddingLeft.toFloat()
                Gravity.END, Gravity.RIGHT -> paddingLeft + w - lt
                else -> paddingLeft + (w - lt) / 2
            }
            val hl = fm.descent - fm.ascent
            val haut = when (gravite and Gravity.VERTICAL_GRAVITY_MASK) {
                Gravity.TOP -> paddingTop.toFloat()
                Gravity.BOTTOM -> paddingTop + h - hl
                else -> paddingTop + (h - hl) / 2
            }
            c.drawText(valeur, x, haut - fm.ascent, p)
        }
    }

    /**
     * 1.88.0 (l'auteur : « je vois la quantité et pas le détail ») : les premières lignes du détail (éléments d'une liste,
     * attributs), autant que la taille du widget en permet, puis « +N » ; le détail complet à l'appui.
     */
    private fun detailCourt(c: LinearLayout, e: WidgetsHa.Entite, st: WidgetsHa.EtatEntite) {
        val (lignes, total) = WidgetsHa.detail(e, st)
        if (detailExterne && total > 0) {
            val z = View(context)
            zoneDetail = z
            c.addView(z, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            return
        }
        if (total == 0) {
            c.addView(texte(context.getString(R.string.wha_detail_vide), 13f, theme.encre3).apply { setPadding(0, dp(4f).toInt(), 0, 0) })
            ressort(c); return
        }
        val max = WidgetsHa.lignesMax(reglage.hauteur)
        // Une ligne de détail : 13 sp et son petit écart (pour la hauteur maximale choisie).
        val hauteurLigne = (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, resources.displayMetrics) * 1.25f + dp(3f)).toInt()
        if (reglage.defilement) {
            // 1.91.2 (retour d'essai) : tout le détail reçu DÉFILE dans la tuile, sans « +N » ni texte coupé (une ligne longue passe
            // à la ligne) ; au plus [max] lignes de haut, avec un fondu discret quand il reste à lire.
            val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            lignes.forEach { l ->
                col.addView(texte("• " + WidgetsHa.texteLigne(l), 13f, theme.encre2).apply {
                    contentDescription = WidgetsHa.texteLigne(l); setPadding(0, dp(3f).toInt(), 0, 0)
                })
            }
            // Home Assistant n'a pas tout envoyé : on le dit, et « Voir tout » mène au détail complet.
            if (total > lignes.size) col.addView(voirTout(total - lignes.size))
            val defile = DefilementWidget(context, this, max * hauteurLigne).apply { addView(col); tag = "defilement_widget" }
            defilement = defile
            c.addView(defile, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            return
        }
        // Défilement coupé (retour d'essai) : ce qui tient (au plus [max] lignes), puis « Voir tout » vers le détail complet.
        val vues = lignes.take(max).map { l ->
            texte("• " + WidgetsHa.texteLigne(l), 13f, theme.encre2).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; contentDescription = WidgetsHa.texteLigne(l)
                setPadding(0, dp(3f).toInt(), 0, 0)
            }
        }
        val plus = texte("", 12f, theme.or, Polices.demiGras(context)).apply {
            setPadding(0, dp(3f).toInt(), 0, 0); isClickable = true; setOnClickListener { montrerDetails() }
        }
        val liste = ListeDetail(context, vues, plus, total, { context.getString(R.string.wha_voir_tout) },
            { n -> context.getString(R.string.wha_voir_tout_desc, n) }, debutDetail)
        listeDetail = liste
        c.addView(liste, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /** « Voir tout » (le détail complet), quand Home Assistant n'a pas envoyé tout le détail. */
    private fun voirTout(reste: Int): TextView = texte(context.getString(R.string.wha_voir_tout), 12f, theme.or, Polices.demiGras(context)).apply {
        setPadding(0, dp(6f).toInt(), 0, dp(2f).toInt()); contentDescription = context.getString(R.string.wha_voir_tout_desc, reste)
        isClickable = true; setOnClickListener { montrerDetails() }
    }

    /** 1.91.2 : la liste qui défile (défilement activé), pour la télécommande. */
    private var defilement: DefilementWidget? = null

    /** 1.90.1 : la liste du détail de cette tuile (défilement à la télécommande sur TV), et sa position, gardée quand
     *  la tuile se redessine (nouvel état). */
    private var listeDetail: ListeDetail? = null
    private var debutDetail = 0

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        // 16-10 (télé) : la touche Menu ouvre le menu du widget, même quand le focus est sur − ou +.
        if (menu != null && event.keyCode == android.view.KeyEvent.KEYCODE_MENU) {
            if (event.action == android.view.KeyEvent.ACTION_UP) menu.invoke()
            return true
        }
        // TV : la tuile a le focus et son détail ne tient pas en entier : haut et bas font défiler ses lignes ; aux
        // bouts, la touche repart vers la navigation habituelle (le focus passe à la tuile voisine).
        // 1.91.2 : défilement activé — haut et bas font défiler le contenu, puis la touche repart (focus voisin).
        val d = defilement
        if (d != null && event.action == android.view.KeyEvent.ACTION_DOWN && ReglagesTvActivity.estTele(context)) {
            when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> if (d.pas(true)) return true
                android.view.KeyEvent.KEYCODE_DPAD_UP -> if (d.pas(false)) return true
            }
        }
        val l = listeDetail
        if (l != null && event.action == android.view.KeyEvent.ACTION_DOWN && ReglagesTvActivity.estTele(context)) {
            when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> if (l.descendre()) { debutDetail = l.debut; return true }
                android.view.KeyEvent.KEYCODE_DPAD_UP -> if (l.monter()) { debutDetail = l.debut; return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** 1.88.0 : régler une valeur (− / +, pas et bornes de l'entité) ou choisir dans une liste (appui : les choix). */
    private fun reglerValeur(c: LinearLayout, e: WidgetsHa.Entite, st: WidgetsHa.EtatEntite, valeur: String) {
        val pilote = !lectureSeule && agirAvec != null && WidgetsHa.pilotable(WidgetsHaStock.entite(context, e.entityId) ?: if (exemple) e else null, false)
        val liste = e.domaine in setOf("select", "input_select")
        val boutons = !liste && pilote && "set_value" in e.actions
        // 16-10 (l'auteur : « le libellé Consigne activé ne s'affiche pas ») : le nom n'est plus jamais retiré en silence ; ce
        // sont les boutons − / + qui prennent la taille que le widget leur laisse ([coteBouton]).
        enteteSelon(c, e)
        ressort(c)
        if (liste) {
            c.addView(texte(if (pilote) "$valeur  ▾" else valeur, tailleValeur(e, if (petit()) 17f else 22f), theme.encre, Polices.demiGras(context)).apply {
                gravity = graviteH(WidgetsHa.miseEnPage(reglage, e)); tag = "widget_valeur"
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; contentDescription = if (pilote) context.getString(R.string.wha_choisir, libelle(e)) + ", " + valeur else valeur
            })
            return
        }
        // 16-10 (recette télé) : − valeur + sur une ligne seulement si la place réelle le permet (deux boutons et de la
        // place pour la valeur) ; sinon la valeur au-dessus, les boutons dessous (1 × 1, 2 × 1 étroit).
        // 16-10 (recette 360 dp) : un widget d'une case de haut n'a pas la hauteur d'empiler nom, valeur et boutons (en 2 × 1,
        // les boutons sortaient par le bas) : 48 dp suffisent à la valeur, qui rapetisse sur sa ligne. Empilés, les deux
        // boutons tiennent dans la largeur (en 1 × 1, le « + » s'écrasait en ovale).
        val largeurUtile = (if (width > 0) width.toFloat() else dp(80f) * reglage.w) - dp(24f)
        val cote0 = coteBouton()
        val enLigne = (reglage.w >= 4 || reglage.h < 2) && largeurUtile >= 2 * cote0 + if (reglage.h < 2) dp(48f) else dp(64f)
        val cote = if (enLigne) cote0 else minOf(cote0, ((largeurUtile - dp(4f)) / 2f).toInt()).coerceAtLeast(dp(20f).toInt())
        val ecart = (largeurUtile - 2 * cote).coerceIn(dp(4f), dp(12f)).toInt()
        val tailleEnLigne = (cote / resources.displayMetrics.density * 0.6f).coerceIn(14f, 24f)
        fun touche(signe: String, sens: Int, desc: Int) = texte(signe, cote / resources.displayMetrics.density * 0.46f, theme.encre, Polices.outfit(context)).apply {
            gravity = Gravity.CENTER; minWidth = cote; minHeight = cote; minimumWidth = cote; minimumHeight = cote; tag = if (sens < 0) "widget_moins" else "widget_plus"
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(1.5f).toInt(), theme.carteBord) }
            contentDescription = context.getString(desc, libelle(e)); isClickable = true; isFocusable = true
            // 17-10 (double envoi du 15/10) : la valeur part de la dernière demandée, les appuis rapprochés sont regroupés
            // (un seul envoi, la valeur finale), et la valeur demandée se montre tout de suite.
            setOnClickListener {
                val v = AppuisReglage.appui(e, st, sens, android.os.SystemClock.elapsedRealtime()) ?: return@setOnClickListener
                maj()
                AppuisReglage.programmer(e.entityId) { agirAvec?.invoke(e, "set_value", org.json.JSONObject().put("value", v)) }
            }
            Ui.anneauFocus(this, theme, 999f, agrandir = false)
            if (menu != null) setOnLongClickListener { menu.invoke(); true }
        }
        // La valeur et son unité sur une ligne (« 20,5 °C » ne se coupe pas) : la police rapetisse au besoin.
        val tv0 = if (boutons && enLigne) tailleEnLigne else if (petit()) 18f else 24f
        // 16-10 (recette 360 dp, 130 %) : ContenuAjuste ne la retaille pas (tag « nom_ajuste ») — il lui rendait sa taille
        // de départ à chaque mesure et « 20,5 °C » perdait son « C » sur une ligne cachée.
        val v = texte(valeur, tv0, theme.encre, Polices.outfitLeger(context)).apply { gravity = Gravity.CENTER; tag = "widget_valeur"; setTag(R.id.nom_ajuste, true); Ui.surUneLigne(this, tv0, 10f) }
        // Hauteurs des autres éléments (dp) : marges 24, nom 22, valeur 34, boutons (côté).
        val coteDp = cote / resources.displayMetrics.density
        if (!boutons) { c.addView(v); ligneSelonPlace(c, st, 24f + 22f + 34f); return }
        if (enLigne) {
            // Large, ou bas (2 × 1, 3 × 1) : − valeur + sur une ligne, sous le nom.
            val r = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            r.addView(touche("−", -1, R.string.wha_diminuer))
            // 17-10 (recette calculée, 360 dp à 130 %) : un écart de 4 dp de chaque côté — la valeur touchait le « + ».
            v.setPadding(dp(4f).toInt(), 0, dp(4f).toInt(), 0)
            // 17-10 (recette : en 4 × 1, la ligne sous la valeur était coupée en bas) : un widget d'une case de haut met
            // la ligne, en court (« Réduit → 12:00 »), sous la valeur, entre − et + ; plus haut, elle passe sous la rangée.
            // La forme courte n'y va que si elle tient entre − et + à 9 sp (360 dp à 130 % en 2 × 1 : non) ; sinon elle reste
            // dite (description).
            val court = st.texte?.trim()?.takeIf { it.isNotEmpty() }?.let { WidgetsHa.texteCourt(it) }
            val tientAuMilieu = court != null && android.graphics.Paint().apply {
                textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 9f, resources.displayMetrics); typeface = Polices.texte(context)
            }.measureText(court) <= largeurUtile - 2 * cote - dp(12f) &&
                // … et en hauteur : marges, nom, valeur et la ligne (recette télé 2 × 1 : elle dépassait de 11 à 16 px).
                (height <= 0 || height >= dp(24f + 22f + 34f + 16f))
            if (reglage.h < 2 && tientAuMilieu) {
                val milieu = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
                milieu.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                ligneTexte(milieu, st, court = true)
                r.addView(milieu, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                r.addView(touche("+", 1, R.string.wha_augmenter))
                c.addView(r)
                return
            }
            r.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(touche("+", 1, R.string.wha_augmenter))
            c.addView(r)
            if (reglage.h >= 2) ligneSelonPlace(c, st, 24f + 22f + maxOf(coteDp, 34f))  // une case de haut : pas de place sous la rangée
        } else {
            // Étroit : la valeur sur toute la largeur, puis − et + (cibles de 48 dp).
            c.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val r = LinearLayout(context).apply { gravity = Gravity.CENTER }
            r.addView(touche("−", -1, R.string.wha_diminuer))
            r.addView(View(context), LinearLayout.LayoutParams(ecart, 1))
            r.addView(touche("+", 1, R.string.wha_augmenter))
            c.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2f).toInt() })
            ligneSelonPlace(c, st, 24f + 22f + 34f + coteDp + 2f)
        }
    }

    /** Les choix d'une liste (select) : la boîte de choix, puis l'action (avec confirmation si elle est demandée). */
    /**
     * 16-10 : le côté des boutons − / + selon la place réelle du widget (avant : 48 dp fixes, qui chassaient le nom d'un
     * petit widget) : un peu moins de la moitié de la hauteur, au plus un quart de la largeur ; entre 30 et 48 dp.
     */
    private fun coteBouton(): Int {
        val l = if (width > 0) width.toFloat() else dp(80f) * reglage.w
        val h = if (height > 0) height.toFloat() else dp(80f) * reglage.h
        return WidgetsHa.coteBouton(l, h, dp(1f)).toInt()
    }

    private fun choisirOption(e: WidgetsHa.Entite, st: WidgetsHa.EtatEntite) {
        val options = WidgetsHa.options(st)
        if (options.isEmpty() || context !is android.app.Activity) { montrerDetails(); return }
        Ui.dialogue(context).setTitle(context.getString(R.string.wha_choisir, libelle(e)))
            .setSingleChoiceItems(options.toTypedArray(), options.indexOf(st.etat)) { d, k ->
                d.dismiss()
                if (options[k] != st.etat) agirAvec?.invoke(e, "select_option", org.json.JSONObject().put("option", options[k]))
            }
            .setNegativeButton(context.getString(R.string.commun_lot2_annuler), null).show()
    }

    private fun bouton(c: LinearLayout, e: WidgetsHa.Entite) {
        c.gravity = Gravity.CENTER
        val rond = FrameLayout(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Palette.melanger(theme.carte, theme.or, 0.18f)) } }
        picto(e)?.let { rond.addView(it, LayoutParams(dp(22f).toInt(), dp(22f).toInt(), Gravity.CENTER)) }
        c.addView(rond, LinearLayout.LayoutParams(dp(44f).toInt(), dp(44f).toInt()).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(6f).toInt() })
        c.addView(nomHa(libelle(e), 13f, theme.encre, Polices.demiGras(context)).apply { gravity = Gravity.CENTER })
    }

    private fun interrupteur(c: LinearLayout, e: WidgetsHa.Entite, st: WidgetsHa.EtatEntite) {
        val allume = WidgetsHa.estAllume(st.etat)
        enteteSelon(c, e, if (allume) theme.or else theme.encre3)
        ressort(c)
        val r = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val et = texte(WidgetsHa.libelleEtat(e.domaine, e.deviceClass, st.etat), tailleValeur(e, 17f), theme.encre, Polices.demiGras(context)).apply { tag = "widget_valeur" }
        r.addView(et, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(Bascule(context, allume, theme), LinearLayout.LayoutParams(dp(44f).toInt(), dp(26f).toInt()))
        c.addView(r)
    }

    private fun jauge(c: LinearLayout, e: WidgetsHa.Entite, st: WidgetsHa.EtatEntite, valeur: String) {
        enteteSelon(c, e)
        val v = st.etat.replace(',', '.').toDoubleOrNull()
        val (mi, ma) = WidgetsHa.minMax(st.attributs, e.unite, v)
        val cadre = FrameLayout(context)
        cadre.addView(Jauge(context, if (v == null) 0f else WidgetsHa.fraction(v, mi, ma), theme), LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        cadre.addView(texte(valeur, tailleValeur(e, if (petit()) 15f else 22f), theme.encre, Polices.outfit(context)).apply { gravity = Gravity.CENTER; tag = "widget_valeur" }, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        c.addView(cadre, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(4f).toInt() })
        // 1.93.06 (audit des widgets) : l'échelle de la jauge, ses deux bornes sous l'arc, quand le widget a la place.
        if (!petit()) {
            val bornes = LinearLayout(context).apply { tag = "widget_bornes" }
            bornes.addView(texte(WidgetsHa.nombre(mi), 10.5f, theme.encre3), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            bornes.addView(texte(WidgetsHa.valeurAffichee(WidgetsHa.nombre(ma).replace(',', '.'), e.unite, e.domaine, e.deviceClass), 10.5f, theme.encre3).apply { gravity = Gravity.END })
            c.addView(bornes, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun graphique(c: LinearLayout, e: WidgetsHa.Entite, valeur: String) {
        val haut = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        // 16-10 (recette, 1 × 1 à 130 %) : comme les autres titres — le picto laisse sa place sur une case de large, le nom
        // rapetisse avant les « … ».
        if (reglage.w > 1) picto(e)?.let { haut.addView(it, LinearLayout.LayoutParams(dp(18f).toInt(), dp(18f).toInt()).apply { rightMargin = dp(8f).toInt() }) }
        haut.addView(nomHa(libelle(e), 13f, theme.encre2, Polices.demiGras(context)).also { it.setTag(R.id.nom_ajuste, true); Ui.surUneLigneOuPoints(it, 13f, nomMinSp()) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // Large : la valeur à droite du nom ; étroit : dessous, pour que le nom garde la place de passer à la ligne entre les mots.
        val v = texte(valeur, 17f, theme.encre, Polices.outfit(context))
        if (reglage.w >= 4) { v.setPadding(dp(8f).toInt(), 0, 0, 0); haut.addView(v); c.addView(haut) } else { c.addView(haut); c.addView(v) }
        val fin = System.currentTimeMillis()
        val cle = e.entityId + "|" + reglage.periode
        if (cle != historiqueCle) { historiqueCle = cle; historique = null; historiqueEchec = false; chargerHistorique(e.entityId, reglage.periode) }
        // 1.93.06 (l'auteur, 19/10, « T. Ambiante » plate depuis 16 h) : un historique périmé se recharge ; l'ancien reste à
        // l'écran jusqu'à l'arrivée du nouveau.
        else if (historique != null && WidgetsHa.historiquePerime(historiqueChargeA, fin, reglage.periode)) chargerHistorique(e.entityId, reglage.periode)
        planifierCourbe()
        val h = historique ?: if (exemple || historiqueEchec && exempleSiEchec) WidgetsHa.historiqueExemple(fin, reglage.periode) else null
        if (h == null) {
            ressort(c)
            c.addView(texte(if (historiqueEchec) context.getString(R.string.vuewidget_historique_indisponible) else context.getString(R.string.vuewidget_historique_chargement), 12f, theme.encre3))
            return
        }
        c.addView(Courbe(context, Debit.historique(h), reglage.variante == "barres", reglage.periode, fin, theme, Debit.unite(e.unite)),LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(6f).toInt() })
        // 1.93.06 (retour d'essai) : les repères min et max de ce que montre la courbe, sous elle, sur téléphone comme sur télé.
        val periodeTexte = if (reglage.periode == "7j") context.getString(R.string.vuewidget_7_jours) else context.getString(R.string.vuewidget_24_heures)
        val bornes = (Debit.historique(h) as? WidgetsHa.Historique.Nombres)?.takeIf { reglage.variante != "barres" }
            ?.let { WidgetsHa.minMaxFenetre(it.points, fin - WidgetsHa.dureePeriode(reglage.periode), fin) }
        val u = Debit.unite(e.unite).let { if (it.isEmpty() || it == "%" || it.startsWith("°")) it else " $it" }
        // Étroit (2 colonnes ou moins) : la plage seule, sur une ligne, pour laisser la hauteur à la courbe (recette 360 dp, 130 %).
        val pied = when {
            bornes == null -> periodeTexte
            reglage.w <= 2 -> context.getString(R.string.vuewidget_plage, WidgetsHa.nombre(bornes.first), WidgetsHa.nombre(bornes.second) + u)
            else -> context.getString(R.string.vuewidget_min_max, periodeTexte, WidgetsHa.nombre(bornes.first) + u, WidgetsHa.nombre(bornes.second) + u)
        }
        c.addView(texte(pied, 10.5f, theme.encre3).apply { setPadding(0, dp(3f).toInt(), 0, 0) })
    }

    /** Dans l'éditeur, un historique introuvable laisse place à l'exemple (l'aperçu montre toujours la forme). */
    var exempleSiEchec = false

    private fun chargerHistorique(entite: String, periode: String) {
        if (exemple || historiqueEnCours) return
        historiqueEnCours = true
        val cle = "$entite|$periode"
        Thread {
            val h = try { WidgetsHaStock.historique(context, entite, periode) } catch (_: Exception) { null }
            main.post {
                historiqueEnCours = false
                if (historiqueCle != cle) return@post
                // Un rechargement raté garde la courbe d'avant (et réessaie au pas suivant) ; un premier chargement raté le dit.
                if (h != null) { historique = h; historiqueEchec = false; historiqueChargeA = System.currentTimeMillis() }
                else if (historique == null) historiqueEchec = true
                maj()
            }
        }.also { it.isDaemon = true }.start()
    }

    /** 1.93.06 (retour d'essai) : une courbe à l'écran se redessine (et se recharge si besoin) toutes les 5 min, même sans changement. */
    private val courbePas = Runnable { if (isAttachedToWindow && reglage.style == WidgetsHa.GRAPHIQUE) maj() }

    private fun planifierCourbe() {
        // Téléphone et télé (sur la télé, les widgets de l'accueil sont en lecture seule) ; jamais pour un widget système
        // (dessiné hors de l'écran : courbePas ne fait rien tant que la vue n'est pas attachée).
        if (exemple) return
        main.removeCallbacks(courbePas); main.postDelayed(courbePas, WidgetsHa.PERIME_24H_MS)
    }

    override fun onDetachedFromWindow() { main.removeCallbacks(courbePas); super.onDetachedFromWindow() }

    private fun meteo(c: LinearLayout, e: WidgetsHa.Entite, st: WidgetsHa.EtatEntite) {
        val r = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        r.addView(IconeMeteo(context, theme.or).apply { etat = st.etat }, LinearLayout.LayoutParams(dp(40f).toInt(), dp(40f).toInt()).apply { rightMargin = dp(10f).toInt() })
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val t = (st.attributs["temperature"] as? Double) ?: (st.attributs["current_temperature"] as? Double)
        col.addView(texte(if (t != null) WidgetsHa.nombre(t, 0) + "°" else Meteo.libelle(st.etat), 26f, theme.encre, Polices.outfitLeger(context)))
        col.addView(nomHa(libelle(e) + " · " + Meteo.libelle(st.etat), 12f, theme.encre2, Polices.texte(context)))
        r.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        c.addView(r)
        val prevision = (st.attributs["forecast"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
        if (prevision.isEmpty() || reglage.h < 2) return
        ressort(c)
        val bas = LinearLayout(context)
        prevision.take(if (reglage.w >= 4) 4 else 2).forEach { p ->
            val jour = (p["jour"] ?: p["datetime"])?.toString().orEmpty().let { j -> WidgetsHa.iso(j)?.let { Tr.date(it, "EEE") } ?: j }
            val max = (p["max"] ?: p["temperature"]) as? Double; val min = (p["min"] ?: p["templow"]) as? Double
            val col2 = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            col2.addView(texte(jour, 11f, theme.encre2).apply { gravity = Gravity.CENTER })
            col2.addView(IconeMeteo(context, theme.encre2).apply { etat = (p["etat"] ?: p["condition"])?.toString().orEmpty() }, LinearLayout.LayoutParams(dp(22f).toInt(), dp(22f).toInt()))
            col2.addView(texte(listOfNotNull(max?.let { WidgetsHa.nombre(it, 0) + "°" }, min?.let { WidgetsHa.nombre(it, 0) + "°" }).joinToString(" / "), 11f, theme.encre).apply { gravity = Gravity.CENTER })
            bas.addView(col2, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        c.addView(bas)
    }

    private fun ouverture(c: LinearLayout, e: WidgetsHa.Entite, st: WidgetsHa.EtatEntite) {
        val ouvert = WidgetsHa.estAllume(st.etat)
        enteteSelon(c, e, if (ouvert) theme.pourpre else theme.or)
        val m = WidgetsHa.miseEnPage(reglage, e)
        val etat = WidgetsHa.libelleEtat(e.domaine, e.deviceClass, st.etat)
        val couleur = if (ouvert) theme.pourpre else theme.encre
        val v: TextView = if (m.taille == WidgetsHa.REMPLIR) ValeurQuiRemplit(context, etat, couleur, Polices.demiGras(context), graviteH(m) or graviteV(m))
            else texte(etat, WidgetsHa.tailleSp(m.taille, petit()), couleur, Polices.demiGras(context)).apply { gravity = graviteH(m) or graviteV(m) }
        v.tag = "widget_valeur"
        c.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val d = depuis(st.depuis)
        if (d.isNotEmpty()) c.addView(texte(d, 11f, theme.encre3).apply { gravity = graviteH(m) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun miniCarte(c: LinearLayout) {
        if (reglage.libelle.isNotEmpty()) c.addView(texte(reglage.libelle, 12f, theme.encre2, Polices.gras(context)).apply { setPadding(0, 0, 0, dp(4f).toInt()) })
        reglage.entites.take(4).forEachIndexed { k, id ->
            val x = if (exemple && WidgetsHaStock.entite(context, id) == null) WidgetsHa.exemple(listOf(WidgetsHa.TUILE, WidgetsHa.INTERRUPTEUR, WidgetsHa.JAUGE, WidgetsHa.OUVERTURE)[k % 4]) else entite(id)
            val s = etat(x)
            val r = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(3f).toInt(), 0, dp(3f).toInt()) }
            r.addView(Picto(context, WidgetsHa.pictoAuto(x.domaine, x.deviceClass), theme.or), LinearLayout.LayoutParams(dp(16f).toInt(), dp(16f).toInt()).apply { rightMargin = dp(8f).toInt() })
            // 1.87.0 (recette visuelle) : un nom d'entité très long (donnée de Home Assistant) tient sur une ligne avec « … » ;
            // le nom complet reste dit par la description (avant : coupé au milieu d'un mot en grands caractères).
            r.addView(nomHa(x.nom, 12.5f, theme.encre2, Polices.texte(context)),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(texte(WidgetsHa.valeurAffichee(s.etat, x.unite, x.domaine, x.deviceClass), 13.5f, theme.encre, Polices.demiGras(context)).apply { gravity = Gravity.END; setPadding(dp(8f).toInt(), 0, 0, 0) })
            c.addView(r)
        }
    }

    private fun enceinteId(): String = reglage.entites.getOrElse(enceinte) { reglage.entites.first() }

    private fun descriptionLecteur(): String {
        val x = entite(enceinteId()); val st = etat(x); val l = WidgetsHa.lecture(st)
        val quoi = if (l.titre.isNotEmpty()) l.titre + (if (l.artiste.isNotEmpty()) ", " + l.artiste else "") else WidgetsHa.libelleEtat(x.domaine, "", st.etat)
        return context.getString(R.string.vuewidget_libelle_valeur, libelle(x), quoi)
    }

    /**
     * 1.81.0 (point 14 bis) : le lecteur. Pochette, titre, artiste, enceinte ; touches précédent, lecture/pause,
     * suivant et volume − / +, seulement si Vision les permet pour cette entité (liste blanche) et hors télé.
     * Plusieurs enceintes : toucher leur nom passe à la suivante.
     */
    private fun lecteur(c: LinearLayout) {
        val id = enceinteId()
        val e = entite(id); val st = etat(e); val l = WidgetsHa.lecture(st)
        val connue = WidgetsHaStock.entite(context, id) ?: if (exemple) e else null
        val pilote = !lectureSeule && (agir != null || agirAvec != null) && connue?.actions?.isNotEmpty() == true
        val haut = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val cote = if (petit()) 40f else 56f
        val img = android.widget.ImageView(context).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { cornerRadius = dp(10f); setColor(Palette.melanger(theme.carte, theme.or, 0.14f)) }
            clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        if (!exemple) Pochettes.poser(context, img, id, l.pochette)
        haut.addView(img, LinearLayout.LayoutParams(dp(cote).toInt(), dp(cote).toInt()).apply { rightMargin = dp(10f).toInt() })
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        col.addView(texte(l.titre.ifEmpty { WidgetsHa.libelleEtat(e.domaine, "", st.etat) }, 15f, theme.encre, Polices.demiGras(context)))
        if (l.artiste.isNotEmpty()) col.addView(texte(l.artiste, 13f, theme.encre2))
        val plusieurs = reglage.entites.size > 1
        val nom = nomHa(if (plusieurs) libelle(e) + "  ▸" else libelle(e), 11.5f, if (plusieurs) theme.or else theme.encre3, Polices.demiGras(context))
        if (plusieurs) nom.apply {
            isClickable = true; isFocusable = true
            contentDescription = context.getString(R.string.vuewidget_enceinte_suivante, libelle(e))
            setPadding(0, dp(4f).toInt(), 0, dp(4f).toInt())
            setOnClickListener { enceinte = (enceinte + 1) % reglage.entites.size; maj() }
        }
        col.addView(nom)
        haut.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        c.addView(haut)
        ressort(c)
        val touches = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val t = if (petit()) 34f else 42f
        fun touche(picto: String, desc: String, plein: Boolean, service: String?, donnees: org.json.JSONObject = org.json.JSONObject()) {
            val rond = FrameLayout(context).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; if (plein) setColor(theme.or) else { setColor(0); setStroke(dp(1f).toInt(), theme.carteBord) } }
                contentDescription = desc
                alpha = if (service != null || !pilote) 1f else 0.35f
            }
            rond.addView(Picto(context, picto, if (plein) theme.carte else theme.encre), LayoutParams(dp(t * 0.42f).toInt(), dp(t * 0.42f).toInt(), Gravity.CENTER))
            if (pilote && service != null) {
                rond.isClickable = true; rond.isFocusable = true
                rond.setOnClickListener {
                    val avec = agirAvec
                    if (avec != null) avec(e, service, donnees) else agir?.invoke(e, service)
                }
            }
            touches.addView(rond, LinearLayout.LayoutParams(dp(t).toInt(), dp(t).toInt()).apply { rightMargin = dp(8f).toInt() })
        }
        touche("precedent", context.getString(R.string.vuewidget_precedent), false, WidgetsHa.serviceLecteur(e, "precedent"))
        touche(if (l.enLecture) "pause" else "lecture", if (l.enLecture) context.getString(R.string.vuewidget_pause) else context.getString(R.string.vuewidget_lecture), true, WidgetsHa.serviceLecteur(e, "lecture"))
        touche("suivant", context.getString(R.string.vuewidget_suivant), false, WidgetsHa.serviceLecteur(e, "suivant"))
        // 1.88.0 (l'auteur : « l'appli ne gère pas le volume ») : jamais de volume pour un lecteur « · Vision » (la télé le règle).
        val lecteurVision = !Party.volumeReglable((e.attributs["friendly_name"] as? String) ?: e.nom) || !Party.volumeReglable(e.nom)
        if (!petit() && agirAvec != null && "volume_set" in e.actions && !lecteurVision) {
            touches.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            WidgetsHa.volumeApres(e, l.volume, -1)?.let { v -> touche("moins", context.getString(R.string.vuewidget_baisser_volume), false, "volume_set", org.json.JSONObject().put("volume_level", v)) }
            l.volume?.let { touches.addView(texte(context.getString(R.string.vuewidget_pourcent, Math.round(it * 100)), 12f, theme.encre2).apply { setPadding(0, 0, dp(8f).toInt(), 0) }) }
            WidgetsHa.volumeApres(e, l.volume, 1)?.let { v -> touche("plus", context.getString(R.string.vuewidget_monter_volume), false, "volume_set", org.json.JSONObject().put("volume_level", v)) }
        }
        c.addView(touches)
    }

    private fun depuis(s: String): String {
        val t = WidgetsHa.horodatage(s) ?: return ""
        // L'heure et la date à la façon du téléphone (langue, 12/24 h), plus de format écrit à la main.
        return if (System.currentTimeMillis() - t < 86_400_000L) context.getString(R.string.vuewidget_depuis_heure, Tr.heure(t))
            else context.getString(R.string.vuewidget_depuis_date, Tr.date(t, "dMMMM"))
    }

    // ------------------------------------------------------------------ dessins

    /**
     * Une colonne qui garde tout son texte : si ses enfants ne tiennent pas dans la hauteur, tous les textes
     * rapetissent ensemble (jusqu'à moitié) ; ils passent toujours à la ligne, jamais coupés.
     */
    private class ContenuAjuste(ctx: Context) : LinearLayout(ctx) {
        private val bases = java.util.WeakHashMap<TextView, Float>()
        private fun textes(v: View, l: MutableList<TextView>) { if (v is TextView) l.add(v) else if (v is ViewGroup) for (i in 0 until v.childCount) textes(v.getChildAt(i), l) }

        override fun onMeasure(largeurSpec: Int, hauteurSpec: Int) {
            val l = ArrayList<TextView>(); textes(this, l); l.removeAll { it.getTag(R.id.nom_ajuste) == true }
            l.forEach { t -> bases.getOrPut(t) { t.textSize / resources.displayMetrics.scaledDensity } }
            val dispo = MeasureSpec.getSize(hauteurSpec)
            var f = 1f
            for (essai in 0 until 9) {
                l.forEach { t -> val voulu = bases[t]!! * f; if (Math.abs(t.textSize / resources.displayMetrics.scaledDensity - voulu) > 0.05f) t.setTextSize(TypedValue.COMPLEX_UNIT_SP, voulu) }
                super.onMeasure(largeurSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                if (MeasureSpec.getMode(hauteurSpec) == MeasureSpec.UNSPECIFIED || measuredHeight <= dispo || f <= 0.5f) break
                f = (f * 0.88f).coerceAtLeast(0.5f)
            }
            super.onMeasure(largeurSpec, hauteurSpec)
        }
    }

    /** L'interrupteur dessiné : piste et bouton, dans l'accent du thème quand c'est allumé. */
    private class Bascule(ctx: Context, private val allume: Boolean, private val th: Theme) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); val r = h / 2f
            p.color = if (allume) th.or else Palette.melanger(th.carte, th.encre, 0.22f)
            c.drawRoundRect(RectF(0f, 0f, w, h), r, r, p)
            p.color = if (allume) th.carte else th.fond
            c.drawCircle(if (allume) w - r else r, r, r * 0.74f, p)
        }
    }

    /** La jauge : un arc de 240°, la part remplie dans l'accent du thème. */
    private class Jauge(ctx: Context, private val part: Float, private val th: Theme) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        override fun onDraw(c: Canvas) {
            val cote = minOf(width, height).toFloat()
            val ep = cote * 0.09f
            val r = RectF((width - cote) / 2f + ep, (height - cote) / 2f + ep, (width + cote) / 2f - ep, (height + cote) / 2f - ep)
            p.strokeWidth = ep
            p.color = Palette.melanger(th.carte, th.encre, 0.14f); c.drawArc(r, 150f, 240f, false, p)
            p.color = th.or; if (part > 0f) c.drawArc(r, 150f, 240f * part, false, p)
        }
    }

    /** Le graphique : ligne (avec un voile) ou barres pour les nombres, bande colorée pour les états. */
    private class Courbe(ctx: Context, private val h: WidgetsHa.Historique, private val barres: Boolean, private val periode: String, private val finMs: Long, private val th: Theme,
                         private val unite: String = "") : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val chemin = Path()
        private val dens = ctx.resources.displayMetrics.density
        private val echelleTexte = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9.5f * ctx.resources.displayMetrics.scaledDensity; color = Palette.melanger(th.carte, th.encre, 0.6f) }
        /** Les bornes de l'axe des valeurs (posées par [echelleDe]). */
        private var bornesY = 0.0 to 1.0

        override fun onDraw(c: Canvas) {
            val wTotal = width.toFloat(); val htTotal = height.toFloat()
            if (wTotal <= 0 || htTotal <= 0) return
            val debut = finMs - WidgetsHa.dureePeriode(periode)
            // 1.93.06 (audit, graphique de la box) : l'échelle des valeurs à gauche et des repères de temps dessous, quand le
            // widget a la place ; sinon la courbe seule, comme avant.
            val assezHaut = htTotal >= 56f * dens; val assezLarge = wTotal >= 140f * dens
            val hautTexte = echelleTexte.textSize
            val bas = if (assezHaut) hautTexte + 4f * dens else 0f
            val grads = if (assezHaut && assezLarge && h is WidgetsHa.Historique.Nombres) echelleDe(h) else emptyList()
            val etiquettes = grads.mapIndexed { k, g -> WidgetsHa.nombre(g) + if (k == grads.size - 1 && unite.isNotEmpty()) (if (unite == "%" || unite.startsWith("°")) unite else " $unite") else "" }
            val gauche = if (grads.isEmpty()) 0f else (etiquettes.maxOf { echelleTexte.measureText(it) } + 5f * dens).coerceAtMost(wTotal * 0.4f)
            val w = wTotal - gauche; val ht = (htTotal - bas).coerceAtLeast(htTotal * 0.5f)
            c.save(); c.translate(gauche, 0f)
            dessiner(c, w, ht, debut, if (grads.isEmpty()) null else bornesY)
            c.restore()
            if (grads.isNotEmpty()) {
                val (mi, ma) = bornesY
                p.style = Paint.Style.STROKE; p.strokeWidth = 1f * dens; p.color = Palette.melanger(th.carte, th.encre, 0.12f); p.alpha = 255
                echelleTexte.textAlign = Paint.Align.RIGHT
                grads.forEachIndexed { k, g ->
                    val y = ht - ((g - mi) / (ma - mi)).toFloat() * ht
                    c.drawLine(gauche, y, wTotal, y, p)
                    c.drawText(etiquettes[k], gauche - 4f * dens, (y + hautTexte / 3f).coerceIn(hautTexte, ht), echelleTexte)
                }
            }
            if (bas > 0f) {
                val fmt = java.text.SimpleDateFormat(if (periode == "7j") "EEE" else "HH:mm", Tr.locale())
                val tz = java.util.TimeZone.getDefault()
                echelleTexte.textAlign = Paint.Align.CENTER
                for (t in WidgetsHa.reperesTemps(debut, finMs, periode) { tz.getOffset(it).toLong() }) {
                    val x = gauche + ((t - debut).toFloat() / (finMs - debut).coerceAtLeast(1L)) * w
                    val l = fmt.format(java.util.Date(t)).trimEnd('.')
                    val demi = echelleTexte.measureText(l) / 2f
                    if (x - demi < gauche || x + demi > wTotal) continue
                    c.drawText(l, x, htTotal - 2f * dens, echelleTexte)
                }
            }
        }

        private fun echelleDe(n: WidgetsHa.Historique.Nombres): List<Double> {
            val vals = if (barres) WidgetsHa.barres(n.points, periode, finMs).filterNotNull() + 0.0
                else WidgetsHa.courbe(n.points, finMs - WidgetsHa.dureePeriode(periode), finMs).second.map { it.v }
            if (vals.isEmpty()) return emptyList()
            bornesY = WidgetsHa.echelle(vals)
            return WidgetsHa.graduations(bornesY.first, bornesY.second, 3)
        }

        /** La courbe ou les barres dans [w] × [ht] ; [bornes] : celles de l'échelle montrée (sinon celles des points). */
        private fun dessiner(c: Canvas, w: Float, ht: Float, debut: Long, bornes: Pair<Double, Double>?) {
            when (h) {
                is WidgetsHa.Historique.Etats -> {
                    val y0 = ht * 0.3f; val y1 = ht * 0.7f
                    p.style = Paint.Style.FILL; p.color = Palette.melanger(th.carte, th.encre, 0.1f)
                    c.drawRoundRect(RectF(0f, y0, w, y1), 6f, 6f, p)
                    for ((a, b, etat) in WidgetsHa.bandes(h.segments, debut, finMs)) {
                        p.color = if (WidgetsHa.estAllume(etat)) th.or else Palette.melanger(th.carte, th.encre, 0.25f)
                        c.drawRect(a * w, y0, b * w, y1, p)
                    }
                }
                is WidgetsHa.Historique.Nombres -> {
                    if (barres) {
                        val vals = WidgetsHa.barres(h.points, periode, finMs)
                        val (mi, ma) = bornes ?: WidgetsHa.echelle(vals.filterNotNull() + 0.0)
                        val n = vals.size; val pas = w / n
                        p.style = Paint.Style.FILL; p.color = th.or
                        vals.forEachIndexed { k, v ->
                            if (v == null) return@forEachIndexed
                            val y = ht - ((v - mi) / (ma - mi)).toFloat() * ht
                            c.drawRoundRect(RectF(k * pas + pas * 0.15f, y.coerceAtMost(ht - 2f), (k + 1) * pas - pas * 0.15f, ht), 3f, 3f, p)
                        }
                    } else {
                        // 16-10 : toute la largeur — la dernière valeur tient jusqu'à maintenant, la fenêtre suit les données.
                        val (fenetre, traces) = WidgetsHa.courbe(h.points, debut, finMs)
                        val pts = WidgetsHa.reduire(traces, (w / 3f).toInt().coerceAtLeast(2)).let { r ->
                            if (r.isEmpty()) r else (listOf(traces.first()) + r.drop(1).dropLast(1) + traces.last()).distinctBy { it.ts } }
                        if (pts.size < 2) return
                        val (mi, ma) = bornes ?: WidgetsHa.echelle(pts.map { it.v })
                        val (t0, t1) = fenetre
                        fun x(t: Long) = (((t - t0).toFloat() / (t1 - t0).coerceAtLeast(1L)) * w).coerceIn(0f, w)
                        fun y(v: Double) = ht - ((v - mi) / (ma - mi)).toFloat() * ht
                        chemin.reset(); chemin.moveTo(x(pts[0].ts), y(pts[0].v))
                        pts.drop(1).forEach { chemin.lineTo(x(it.ts), y(it.v)) }
                        val voile = Path(chemin).apply { lineTo(x(pts.last().ts), ht); lineTo(x(pts[0].ts), ht); close() }
                        p.style = Paint.Style.FILL; p.color = th.or; p.alpha = 40; c.drawPath(voile, p)
                        p.alpha = 255; p.style = Paint.Style.STROKE; p.strokeWidth = ht * 0.035f.coerceAtLeast(0.02f) + 2f; p.strokeJoin = Paint.Join.ROUND; p.strokeCap = Paint.Cap.ROUND
                        c.drawPath(chemin, p)
                    }
                }
            }
        }
    }
}

/** Les pictogrammes des widgets Home Assistant, tracés au trait dans une couleur du thème. */
class Picto(ctx: Context, var nom: String, private val couleur: Int) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val ch = Path()

    init { contentDescription = WidgetsHa.nomPicto(nom) }

    override fun onDraw(c: Canvas) {
        val s = minOf(width, height).toFloat()
        if (s <= 0f) return
        c.save(); c.translate((width - s) / 2f, (height - s) / 2f)
        p.color = couleur; p.strokeWidth = s * 0.09f; p.style = Paint.Style.STROKE
        fun X(f: Float) = f * s
        ch.reset()
        when (nom) {
            "ampoule" -> { c.drawCircle(X(.5f), X(.4f), X(.26f), p); c.drawLine(X(.38f), X(.78f), X(.62f), X(.78f), p); c.drawLine(X(.42f), X(.92f), X(.58f), X(.92f), p) }
            "prise" -> { c.drawCircle(X(.5f), X(.5f), X(.4f), p); p.style = Paint.Style.FILL; c.drawCircle(X(.36f), X(.5f), X(.06f), p); c.drawCircle(X(.64f), X(.5f), X(.06f), p) }
            "thermometre" -> { c.drawRoundRect(RectF(X(.4f), X(.08f), X(.6f), X(.62f)), X(.1f), X(.1f), p); c.drawCircle(X(.5f), X(.76f), X(.16f), p) }
            "goutte" -> { ch.moveTo(X(.5f), X(.08f)); ch.cubicTo(X(.2f), X(.45f), X(.18f), X(.62f), X(.22f), X(.7f)); ch.cubicTo(X(.32f), X(.95f), X(.68f), X(.95f), X(.78f), X(.7f)); ch.cubicTo(X(.82f), X(.62f), X(.8f), X(.45f), X(.5f), X(.08f)); c.drawPath(ch, p) }
            "eclair" -> { ch.moveTo(X(.58f), X(.06f)); ch.lineTo(X(.24f), X(.56f)); ch.lineTo(X(.5f), X(.56f)); ch.lineTo(X(.42f), X(.94f)); ch.lineTo(X(.76f), X(.44f)); ch.lineTo(X(.5f), X(.44f)); ch.close(); c.drawPath(ch, p) }
            "porte" -> { c.drawRoundRect(RectF(X(.24f), X(.08f), X(.76f), X(.92f)), X(.04f), X(.04f), p); p.style = Paint.Style.FILL; c.drawCircle(X(.64f), X(.52f), X(.05f), p) }
            "fenetre" -> { c.drawRoundRect(RectF(X(.14f), X(.14f), X(.86f), X(.86f)), X(.05f), X(.05f), p); c.drawLine(X(.5f), X(.14f), X(.5f), X(.86f), p); c.drawLine(X(.14f), X(.5f), X(.86f), X(.5f), p) }
            "soleil" -> { c.drawCircle(X(.5f), X(.5f), X(.2f), p); for (k in 0 until 8) { val a = Math.PI * k / 4; c.drawLine(X(.5f + .32f * cos(a).toFloat()), X(.5f + .32f * sin(a).toFloat()), X(.5f + .44f * cos(a).toFloat()), X(.5f + .44f * sin(a).toFloat()), p) } }
            "ventilateur" -> { for (k in 0 until 3) { c.save(); c.rotate(120f * k, X(.5f), X(.5f)); c.drawOval(RectF(X(.4f), X(.08f), X(.6f), X(.48f)), p); c.restore() }; p.style = Paint.Style.FILL; c.drawCircle(X(.5f), X(.5f), X(.06f), p) }
            "lecture" -> { ch.moveTo(X(.3f), X(.18f)); ch.lineTo(X(.82f), X(.5f)); ch.lineTo(X(.3f), X(.82f)); ch.close(); c.drawPath(ch, p) }
            // 1.81.0 : les touches du lecteur (pleines).
            "pause" -> { p.style = Paint.Style.FILL; c.drawRoundRect(RectF(X(.24f), X(.16f), X(.42f), X(.84f)), X(.04f), X(.04f), p); c.drawRoundRect(RectF(X(.58f), X(.16f), X(.76f), X(.84f)), X(.04f), X(.04f), p) }
            "suivant" -> { p.style = Paint.Style.FILL; ch.moveTo(X(.16f), X(.18f)); ch.lineTo(X(.66f), X(.5f)); ch.lineTo(X(.16f), X(.82f)); ch.close(); c.drawPath(ch, p); c.drawRect(X(.7f), X(.18f), X(.84f), X(.82f), p) }
            "precedent" -> { p.style = Paint.Style.FILL; ch.moveTo(X(.84f), X(.18f)); ch.lineTo(X(.34f), X(.5f)); ch.lineTo(X(.84f), X(.82f)); ch.close(); c.drawPath(ch, p); c.drawRect(X(.16f), X(.18f), X(.3f), X(.82f), p) }
            "moins" -> c.drawLine(X(.2f), X(.5f), X(.8f), X(.5f), p)
            "plus" -> { c.drawLine(X(.2f), X(.5f), X(.8f), X(.5f), p); c.drawLine(X(.5f), X(.2f), X(.5f), X(.8f), p) }
            "cadenas" -> { c.drawRoundRect(RectF(X(.2f), X(.44f), X(.8f), X(.9f)), X(.06f), X(.06f), p); c.drawArc(RectF(X(.32f), X(.12f), X(.68f), X(.6f)), 180f, 180f, false, p) }
            "batiment" -> { ch.moveTo(X(.12f), X(.48f)); ch.lineTo(X(.5f), X(.14f)); ch.lineTo(X(.88f), X(.48f)); c.drawPath(ch, p); c.drawRect(X(.24f), X(.44f), X(.76f), X(.88f), p) }
            "personne" -> { c.drawCircle(X(.5f), X(.32f), X(.17f), p); c.drawArc(RectF(X(.18f), X(.58f), X(.82f), X(1.2f)), 180f, 180f, false, p) }
            "cloche" -> { ch.moveTo(X(.2f), X(.74f)); ch.lineTo(X(.8f), X(.74f)); ch.cubicTo(X(.7f), X(.62f), X(.74f), X(.2f), X(.5f), X(.18f)); ch.cubicTo(X(.26f), X(.2f), X(.3f), X(.62f), X(.2f), X(.74f)); c.drawPath(ch, p); c.drawLine(X(.44f), X(.88f), X(.56f), X(.88f), p) }
            else -> { for (k in 0 until 10) { val a = -Math.PI / 2 + Math.PI * k / 5; val r = if (k % 2 == 0) .42f else .18f; val x = X(.5f + r * cos(a).toFloat()); val y = X(.54f + r * sin(a).toFloat()); if (k == 0) ch.moveTo(x, y) else ch.lineTo(x, y) }; ch.close(); c.drawPath(ch, p) }
        }
        c.restore()
    }
}
