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
) : FrameLayout(ctx) {
    /** Lecteur à plusieurs enceintes : celle qu'on pilote (toucher son nom passe à la suivante). */
    private var enceinte = 0
    /** 1.82.1 : ce que montre le widget ; inchangé → aucun redessin. Déclaré AVANT init (qui appelle maj). */
    private var signature = ""
    @Volatile var redessins = 0
    private val main = Handler(Looper.getMainLooper())
    private var historique: WidgetsHa.Historique? = null
    private var historiqueCle = ""
    private var historiqueEchec = false

    init {
        if (historiqueInitial != null) { historique = historiqueInitial; historiqueCle = reglage.entites.first() + "|" + reglage.periode }
        background = GradientDrawable().apply { cornerRadius = dp(24f); setColor(theme.carte); setStroke(dp(1f).toInt(), theme.carteBord) }
        clipToPadding = false
        maj()
    }

    private fun dp(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    /** L'entité (catalogue, ou exemple), et son état le plus frais (flux, sinon catalogue). */
    private fun entite(id: String): WidgetsHa.Entite =
        WidgetsHaStock.entite(context, id) ?: if (exemple) WidgetsHa.exemple(reglage.style).copy(entityId = id)
        else WidgetsHa.Entite(id, id, id.substringBefore('.'), "", "", "", "", emptyMap(), emptyList(), emptyList())

    private fun etat(e: WidgetsHa.Entite): WidgetsHa.EtatEntite =
        FluxWidgets.etats[e.entityId] ?: WidgetsHa.EtatEntite(e.etat, e.attributs, "")

    private fun libelle(e: WidgetsHa.Entite) = reglage.libelle.ifEmpty { e.nom }


    private fun signatureCourante(): String = buildString {
        append(reglage).append('|').append(enceinte).append('|').append(lectureSeule).append('|').append(exemple).append('|')
        append(petit()).append('|')
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
        removeAllViews()
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
        contentDescription = if (reglage.style == WidgetsHa.LECTEUR) descriptionLecteur()
            else if (reglage.style == WidgetsHa.MINI_CARTE) reglage.entites.joinToString(" · ") { val x = entite(it); x.nom + " " + WidgetsHa.valeurAffichee(etat(x).etat, x.unite, x.domaine, x.deviceClass) } else context.getString(R.string.vuewidget_libelle_valeur, libelle(e), valeur)
        alpha = if (!exemple && FluxWidgets.statut == FluxWidgets.Statut.HORS_LIGNE) 0.75f else 1f
        val pilote = !lectureSeule && agir != null && WidgetsHa.pilotable(WidgetsHaStock.entite(context, e.entityId) ?: if (exemple) e else null, false)
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

    private fun entete(c: LinearLayout, e: WidgetsHa.Entite, couleurPicto: Int = theme.or) {
        val r = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        picto(e, couleurPicto)?.let { r.addView(it, LinearLayout.LayoutParams(dp(20f).toInt(), dp(20f).toInt()).apply { rightMargin = dp(8f).toInt() }) }
        val nom = nomHa(libelle(e), 13f, theme.encre2, Polices.demiGras(context))
        r.addView(nom, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        c.addView(r)
    }

    /** 1.87.0 (recette visuelle) : un nom venu de Home Assistant, d'une longueur quelconque : une ligne, « … » au bout,
     *  le nom entier en description (avant : coupé au milieu d'un mot en grands caractères). */
    private fun nomHa(t: String, taille: Float, couleur: Int, police: android.graphics.Typeface): TextView =
        texte(t, taille, couleur, police).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; contentDescription = t }

    private fun ressort(c: LinearLayout) = c.addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))

    // ------------------------------------------------------------------ styles

    private fun tuile(c: LinearLayout, e: WidgetsHa.Entite, st: WidgetsHa.EtatEntite, valeur: String) {
        entete(c, e)
        val aff = WidgetsHa.affichage(reglage, e)
        if (!WidgetsHa.montreDetail(aff)) {
            ressort(c)
            c.addView(texte(valeur, if (petit()) 20f else 28f, theme.encre, Polices.outfitLeger(context)))
            return
        }
        if (WidgetsHa.montreValeur(aff)) c.addView(texte(valeur, if (petit()) 18f else 24f, theme.encre, Polices.outfitLeger(context)).apply { setPadding(0, dp(2f).toInt(), 0, 0) })
        detailCourt(c, e, st, if (WidgetsHa.montreValeur(aff)) 0 else 1)
        ressort(c)
    }

    /**
     * 1.88.0 (l'auteur : « je vois la quantité et pas le détail ») : les premières lignes du détail (éléments d'une liste,
     * attributs), autant que la taille du widget en permet, puis « +N » ; le détail complet à l'appui.
     */
    private fun detailCourt(c: LinearLayout, e: WidgetsHa.Entite, st: WidgetsHa.EtatEntite, bonus: Int) {
        val (lignes, total) = WidgetsHa.detail(e, st)
        if (total == 0) { c.addView(texte(context.getString(R.string.wha_detail_vide), 13f, theme.encre3).apply { setPadding(0, dp(4f).toInt(), 0, 0) }); return }
        val place = (when (reglage.h) { 1 -> 1; 2 -> 3; 3 -> 5; else -> 7 } + bonus).coerceAtLeast(1)
        val (montrees, autres) = WidgetsHa.detailTronque(lignes, total, place)
        montrees.forEach { l ->
            c.addView(texte("• " + WidgetsHa.texteLigne(l), 13f, theme.encre2).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; contentDescription = WidgetsHa.texteLigne(l)
                setPadding(0, dp(3f).toInt(), 0, 0)
            })
        }
        if (autres > 0) c.addView(texte(context.getString(R.string.wha_plus_n, autres), 12f, theme.or, Polices.demiGras(context)).apply {
            setPadding(0, dp(3f).toInt(), 0, 0); contentDescription = context.getString(R.string.wha_detail_autres, autres)
        })
    }

    /** 1.88.0 : régler une valeur (− / +, pas et bornes de l'entité) ou choisir dans une liste (appui : les choix). */
    private fun reglerValeur(c: LinearLayout, e: WidgetsHa.Entite, st: WidgetsHa.EtatEntite, valeur: String) {
        val pilote = !lectureSeule && agirAvec != null && WidgetsHa.pilotable(WidgetsHaStock.entite(context, e.entityId) ?: if (exemple) e else null, false)
        val liste = e.domaine in setOf("select", "input_select")
        val boutons = !liste && pilote && "set_value" in e.actions
        // Étroit et bas (2 × 1) avec − / + : pas de place pour le nom ; il reste dit (description du widget).
        val sansNom = boutons && reglage.w < 4 && reglage.h < 2
        if (!sansNom) { entete(c, e); ressort(c) }
        if (liste) {
            c.addView(texte(if (pilote) "$valeur  ▾" else valeur, if (petit()) 17f else 22f, theme.encre, Polices.demiGras(context)).apply {
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; contentDescription = if (pilote) context.getString(R.string.wha_choisir, libelle(e)) + ", " + valeur else valeur
            })
            return
        }
        fun touche(signe: String, sens: Int, desc: Int) = texte(signe, 22f, theme.encre, Polices.outfit(context)).apply {
            gravity = Gravity.CENTER; minWidth = dp(48f).toInt(); minHeight = dp(48f).toInt()
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(1.5f).toInt(), theme.carteBord) }
            contentDescription = context.getString(desc, libelle(e)); isClickable = true; isFocusable = true
            setOnClickListener { WidgetsHa.valeurApres(e, st, sens)?.let { v -> agirAvec?.invoke(e, "set_value", org.json.JSONObject().put("value", v)) } }
        }
        // La valeur et son unité sur une ligne (« 20,5 °C » ne se coupe pas) : la police rapetisse au besoin.
        val v = texte(valeur, if (petit()) 18f else 24f, theme.encre, Polices.outfitLeger(context)).apply { gravity = Gravity.CENTER; Ui.surUneLigne(this, if (petit()) 18f else 24f, 10f) }
        if (!boutons) { c.addView(v); return }
        if (reglage.w >= 4) {
            // Large : − valeur + sur une ligne.
            val r = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            r.addView(touche("−", -1, R.string.wha_diminuer))
            r.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(touche("+", 1, R.string.wha_augmenter))
            c.addView(r)
        } else {
            // Étroit : la valeur sur toute la largeur, puis − et + (cibles de 48 dp).
            c.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val r = LinearLayout(context).apply { gravity = Gravity.CENTER }
            r.addView(touche("−", -1, R.string.wha_diminuer))
            r.addView(View(context), LinearLayout.LayoutParams(dp(12f).toInt(), 1))
            r.addView(touche("+", 1, R.string.wha_augmenter))
            c.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2f).toInt() })
        }
    }

    /** Les choix d'une liste (select) : la boîte de choix, puis l'action (avec confirmation si elle est demandée). */
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
        entete(c, e, if (allume) theme.or else theme.encre3)
        ressort(c)
        val r = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val et = texte(WidgetsHa.libelleEtat(e.domaine, e.deviceClass, st.etat), 17f, theme.encre, Polices.demiGras(context))
        r.addView(et, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(Bascule(context, allume, theme), LinearLayout.LayoutParams(dp(44f).toInt(), dp(26f).toInt()))
        c.addView(r)
    }

    private fun jauge(c: LinearLayout, e: WidgetsHa.Entite, st: WidgetsHa.EtatEntite, valeur: String) {
        entete(c, e)
        val v = st.etat.replace(',', '.').toDoubleOrNull()
        val (mi, ma) = WidgetsHa.minMax(st.attributs, e.unite, v)
        val cadre = FrameLayout(context)
        cadre.addView(Jauge(context, if (v == null) 0f else WidgetsHa.fraction(v, mi, ma), theme), LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        cadre.addView(texte(valeur, if (petit()) 15f else 22f, theme.encre, Polices.outfit(context)).apply { gravity = Gravity.CENTER }, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        c.addView(cadre, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(4f).toInt() })
    }

    private fun graphique(c: LinearLayout, e: WidgetsHa.Entite, valeur: String) {
        val haut = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        picto(e)?.let { haut.addView(it, LinearLayout.LayoutParams(dp(18f).toInt(), dp(18f).toInt()).apply { rightMargin = dp(8f).toInt() }) }
        haut.addView(nomHa(libelle(e), 13f, theme.encre2, Polices.demiGras(context)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // Large : la valeur à droite du nom ; étroit : dessous, pour que le nom garde la place de passer à la ligne entre les mots.
        val v = texte(valeur, 17f, theme.encre, Polices.outfit(context))
        if (reglage.w >= 4) { v.setPadding(dp(8f).toInt(), 0, 0, 0); haut.addView(v); c.addView(haut) } else { c.addView(haut); c.addView(v) }
        val fin = System.currentTimeMillis()
        val cle = e.entityId + "|" + reglage.periode
        if (cle != historiqueCle) { historiqueCle = cle; historique = null; historiqueEchec = false; chargerHistorique(e.entityId, reglage.periode) }
        val h = historique ?: if (exemple || historiqueEchec && exempleSiEchec) WidgetsHa.historiqueExemple(fin, reglage.periode) else null
        if (h == null) {
            ressort(c)
            c.addView(texte(if (historiqueEchec) context.getString(R.string.vuewidget_historique_indisponible) else context.getString(R.string.vuewidget_historique_chargement), 12f, theme.encre3))
            return
        }
        c.addView(Courbe(context, h, reglage.variante == "barres", reglage.periode, fin, theme), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(6f).toInt() })
        c.addView(texte(if (reglage.periode == "7j") context.getString(R.string.vuewidget_7_jours) else context.getString(R.string.vuewidget_24_heures), 10.5f, theme.encre3).apply { setPadding(0, dp(3f).toInt(), 0, 0) })
    }

    /** Dans l'éditeur, un historique introuvable laisse place à l'exemple (l'aperçu montre toujours la forme). */
    var exempleSiEchec = false

    private fun chargerHistorique(entite: String, periode: String) {
        if (exemple) return
        val cle = "$entite|$periode"
        Thread {
            val h = try { WidgetsHaStock.historique(context, entite, periode) } catch (_: Exception) { null }
            main.post {
                if (historiqueCle != cle) return@post
                historique = h; historiqueEchec = h == null
                maj()
            }
        }.also { it.isDaemon = true }.start()
    }

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
        entete(c, e, if (ouvert) theme.pourpre else theme.or)
        ressort(c)
        c.addView(texte(WidgetsHa.libelleEtat(e.domaine, e.deviceClass, st.etat), 18f, if (ouvert) theme.pourpre else theme.encre, Polices.demiGras(context)))
        val d = depuis(st.depuis)
        if (d.isNotEmpty()) c.addView(texte(d, 11f, theme.encre3))
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
            val l = ArrayList<TextView>(); textes(this, l)
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
    private class Courbe(ctx: Context, private val h: WidgetsHa.Historique, private val barres: Boolean, private val periode: String, private val finMs: Long, private val th: Theme) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val chemin = Path()
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val ht = height.toFloat()
            if (w <= 0 || ht <= 0) return
            val debut = finMs - WidgetsHa.dureePeriode(periode)
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
                        val (mi, ma) = WidgetsHa.echelle(vals.filterNotNull() + 0.0)
                        val n = vals.size; val pas = w / n
                        p.style = Paint.Style.FILL; p.color = th.or
                        vals.forEachIndexed { k, v ->
                            if (v == null) return@forEachIndexed
                            val y = ht - ((v - mi) / (ma - mi)).toFloat() * ht
                            c.drawRoundRect(RectF(k * pas + pas * 0.15f, y.coerceAtMost(ht - 2f), (k + 1) * pas - pas * 0.15f, ht), 3f, 3f, p)
                        }
                    } else {
                        val pts = WidgetsHa.reduire(h.points.filter { it.ts >= debut }, (w / 3f).toInt().coerceAtLeast(2))
                        if (pts.size < 2) return
                        val (mi, ma) = WidgetsHa.echelle(pts.map { it.v })
                        fun x(t: Long) = ((t - debut).toFloat() / (finMs - debut)) * w
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
