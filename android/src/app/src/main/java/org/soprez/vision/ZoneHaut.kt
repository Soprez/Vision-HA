// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray

/**
 * 1.90.5 (retour d'essai) : la zone du haut de l'accueil, une liste ORDONNÉE de widgets, réglée par appareil par le Décideur
 * (« Personnaliser cette zone », code des Décideurs) : ajout, retrait, ordre.
 *
 * Les widgets de la zone (télé et téléphone) :
 * - [HORLOGE] : l'horloge stylisée de Vision (HorlogeVision), avec ses réglages : taille, format 12 / 24 h, date ;
 * - [METEO] : la température, l'icône et le ciel ;
 * - [REMERCIEMENT] : l'œil joyeux et « Merci d'avoir installé Vision. » (six langues) ;
 * - [MESSAGE] : l'œil joyeux et un message choisi sur l'appareil ;
 * - [CLASSIQUE] (télé seulement) : l'accueil d'avant, l'heure et la date à gauche, la météo à droite.
 * - [LECTEUR] (1.93.05, l'auteur, retour 34) : le lecteur de musique (pochette, titre, lecture/pause, suivant), seulement
 *   si on l'ajoute : il ne vient jamais de lui-même sur l'accueil de la télé, et n'est mis dans aucun défaut.
 * Une liste vide : rien (ni cadre ni trou) ; le Décideur garde son invite pour revenir.
 *
 * - [PREFIXE_HA] + entité (télé, 1.91.2) : un widget Home Assistant partagé avec cet appareil (onglet Widgets HA).
 *
 * Par défaut ([initial]) :
 * - téléphone (l'auteur, 12 octobre 23 h 17) : l'horloge stylisée seule, la météo en option ;
 * - télé (1.91.2, l'auteur : « je ne vois pas les widgets sur la télé ») : un vrai tableau de bord — l'horloge stylisée puis
 *   les widgets partagés par Home Assistant, dans leur ordre ; avant d'être reliée, le remerciement et l'œil joyeux.
 *   Tant que le Décideur n'a rien choisi sur la télé ([CLE_CHOISIE]), ce défaut suit le partage de Home Assistant.
 * Le téléphone fige son choix au premier lancement. Les demandes en attente restent toujours visibles.
 */
object ZoneHaut {
    const val HORLOGE = "horloge_vision"
    const val METEO = "meteo"
    const val REMERCIEMENT = "remerciement"
    const val MESSAGE = "message"
    const val CLASSIQUE = "heure_meteo"
    const val LECTEUR = "lecteur_musique"

    private const val PREFS = "local"
    private const val CLE = "zone_haut_widgets"
    private const val CLE_ANCIENNE = "zone_haut"
    private const val CLE_MESSAGE = "zone_haut_message"
    private const val CLE_HORLOGE = "zone_haut_horloge"
    const val TAG = "zone_haut"
    const val TAG_INVITE = "zone_haut_personnaliser"

    /** 1.91.2 : un widget Home Assistant de la zone (télé) : « ha:<entité> ». */
    const val PREFIXE_HA = "ha:"
    /** Vrai quand le Décideur a composé la zone de cette télé (sinon le tableau de bord par défaut suit HA). */
    private const val CLE_CHOISIE = "zone_haut_choisie"
    /** Combien de widgets partagés le tableau de bord par défaut montre (le reste : au choix du Décideur). */
    const val HA_PAR_DEFAUT = 4

    fun estHa(code: String): Boolean = code.startsWith(PREFIXE_HA)
    fun entiteHa(code: String): String = code.removePrefix(PREFIXE_HA)

    /** Les widgets partagés par Home Assistant avec cet appareil (son catalogue, dans l'ordre de HA), en codes de zone. */
    fun partagesHa(ctx: Context): List<String> =
        if (!Modules.actif(ctx, Modules.ENTITES)) emptyList() else WidgetsHaStock.catalogue(ctx).keys.map { PREFIXE_HA + it }

    /** Les widgets que le Décideur peut mettre dans la zone, sur cet appareil ([ha] : les widgets partagés, télé). */
    fun catalogue(tele: Boolean, ha: List<String> = emptyList()): List<String> =
        if (tele) listOf(HORLOGE, CLASSIQUE, METEO, REMERCIEMENT, MESSAGE, LECTEUR) + ha else listOf(HORLOGE, METEO, REMERCIEMENT, MESSAGE, LECTEUR)

    /** La zone d'un appareil qui n'a encore rien choisi ([ha] : les widgets partagés par Home Assistant, télé). */
    fun initial(tele: Boolean, dejaRelie: Boolean, ha: List<String> = emptyList()): List<String> = when {
        !tele -> listOf(HORLOGE)
        dejaRelie -> listOf(HORLOGE) + ha.take(HA_PAR_DEFAUT)
        else -> listOf(REMERCIEMENT)
    }

    /** Une zone de télé enregistrée avant 1.91.2 et restée au défaut d'alors : elle n'a pas été choisie. */
    fun estAncienDefaut(l: List<String>): Boolean = l == listOf(CLASSIQUE) || l == listOf(REMERCIEMENT)

    /** Les anciens modes (premières versions de la 1.90.5) deviennent des listes. */
    fun depuisMode(mode: String, tele: Boolean): List<String>? = when (mode) {
        "heure_meteo" -> if (tele) listOf(CLASSIQUE) else listOf(HORLOGE, METEO)
        "horloge" -> listOf(HORLOGE)
        "horloge_meteo" -> listOf(HORLOGE, METEO)
        "remerciement" -> listOf(REMERCIEMENT)
        "message" -> listOf(MESSAGE)
        "vide" -> emptyList()
        else -> null
    }

    /**
     * Ce qui est réellement montré : seulement les widgets permis sur cet appareil, sans doublon ; l'accueil d'avant
     * ([CLASSIQUE]) ne se combine pas (il occupe toute la bande) : avec d'autres widgets, ce sont eux qui comptent.
     */
    fun nettoyer(l: List<String>, tele: Boolean): List<String> {
        val permis = catalogue(tele)
        // Un widget Home Assistant reste (télé) même si le catalogue n'est pas encore là : il n'est simplement pas montré.
        val sans = l.filter { it in permis || (tele && estHa(it)) }.distinct()
        return if (CLASSIQUE in sans && sans.size > 1) sans - CLASSIQUE else sans
    }

    fun widgets(ctx: Context): List<String> {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val tele = ReglagesTvActivity.estTele(ctx)
        // 1.91.2 : la télé dont le Décideur n'a pas composé la zone suit le tableau de bord par défaut (et le partage
        // de Home Assistant) ; une zone enregistrée avant et restée au défaut d'alors compte comme non choisie.
        if (tele) {
            val l = p.getString(CLE, null)?.let { s -> try { JSONArray(s).let { a -> (0 until a.length()).map { a.getString(it) } } } catch (_: Exception) { null } }
            val choisie = p.getBoolean(CLE_CHOISIE, false) || (l != null && !estAncienDefaut(l) && p.getString(CLE_ANCIENNE, null) == null)
            if (!choisie) return initial(true, Config(ctx).id.isNotEmpty(), partagesHa(ctx))
            if (!p.getBoolean(CLE_CHOISIE, false)) p.edit().putBoolean(CLE_CHOISIE, true).apply()
            return nettoyer(l ?: emptyList(), true)
        }
        p.getString(CLE, null)?.let { s ->
            return try { JSONArray(s).let { a -> nettoyer((0 until a.length()).map { a.getString(it) }, tele) } } catch (_: Exception) { initial(tele, true) }
        }
        // Premier lancement : l'ancien mode s'il y en a un, sinon le défaut ; figé tout de suite.
        val l = p.getString(CLE_ANCIENNE, null)?.let { depuisMode(it, tele) } ?: initial(tele, Config(ctx).id.isNotEmpty())
        p.edit().putString(CLE, JSONArray(l).toString()).apply()
        return l
    }

    /** Ce que le Décideur a composé : la zone est désormais « choisie » (sur la télé, le défaut ne la change plus). */
    fun poser(ctx: Context, l: List<String>, message: String? = null) {
        val e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(CLE, JSONArray(l.distinct()).toString())
            .putBoolean(CLE_CHOISIE, true)
        if (message != null) e.putString(CLE_MESSAGE, message.trim().take(160))
        e.apply()
        Local.toucher()
    }

    /** Revenir au tableau de bord par défaut (télé) : il suit de nouveau le partage de Home Assistant. */
    fun revenirAuDefaut(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(CLE).remove(CLE_CHOISIE).remove(CLE_ANCIENNE).apply()
        Local.toucher()
    }

    /** Télé : la zone attend-elle le catalogue de Home Assistant (défaut qui le suit, ou widgets HA choisis) ? */
    fun attendCatalogueHa(ctx: Context): Boolean =
        ReglagesTvActivity.estTele(ctx) && Modules.actif(ctx, Modules.ENTITES) && Config(ctx).id.isNotEmpty() &&
            (!ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(CLE_CHOISIE, false) || widgets(ctx).any { estHa(it) })

    fun message(ctx: Context): String = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CLE_MESSAGE, "") ?: ""

    fun texte(ctx: Context, code: String): String = when (code) {
        MESSAGE -> message(ctx).ifEmpty { ctx.getString(R.string.zonehaut_merci) }
        else -> ctx.getString(R.string.zonehaut_merci)
    }

    fun nom(ctx: Context, code: String): String = if (DispositionAccueil.estWidgetVision(code)) {
        Accueil.widget(DispositionAccueil.codeWidgetVision(code))?.nom ?: code
    } else if (estHa(code)) {
        // Le nom donné dans Home Assistant (onglet Widgets HA), sinon l'entité.
        WidgetsHaStock.entite(ctx, entiteHa(code))?.nom?.takeIf { it.isNotBlank() } ?: entiteHa(code)
    } else ctx.getString(when (code) {
        HORLOGE -> R.string.zonehaut_widget_horloge
        METEO -> R.string.zonehaut_widget_meteo
        REMERCIEMENT -> R.string.zonehaut_mode_remerciement
        MESSAGE -> R.string.zonehaut_mode_message
        LECTEUR -> R.string.zonehaut_widget_lecteur
        else -> R.string.zonehaut_widget_classique
    })

    /** Le résumé d'une ligne de réglages : « Horloge stylisée, Météo », ou « Vide ». */
    fun resume(ctx: Context): String = widgets(ctx).let { l -> if (l.isEmpty()) ctx.getString(R.string.zonehaut_mode_vide) else l.joinToString(", ") { nom(ctx, it) } }

    // ------------------------------------------------------------------ horloge stylisée : réglages

    enum class Taille(val cle: String, val facteur: Float, val nom: Int) {
        PETITE("petite", 0.75f, R.string.horloge_petite), MOYENNE("moyenne", 1f, R.string.horloge_moyenne), GRANDE("grande", 1.3f, R.string.horloge_grande)
    }
    enum class Format(val cle: String, val nom: Int) {
        APPAREIL("appareil", R.string.horloge_format_appareil), H12("12", R.string.horloge_12h), H24("24", R.string.horloge_24h)
    }
    /** 1.91.2 : [style], un style de [StylesHorloge] (l'anneau par défaut, l'horloge d'avant). Réglé sur chaque appareil. */
    data class ReglagesHorloge(val taille: Taille = Taille.MOYENNE, val format: Format = Format.APPAREIL, val date: Boolean = true,
                               val style: String = HorlogesVeille.ANNEAU)

    fun reglagesHorloge(ctx: Context): ReglagesHorloge {
        val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CLE_HORLOGE, null) ?: return ReglagesHorloge()
        val p = s.split("|")
        return ReglagesHorloge(Taille.values().firstOrNull { it.cle == p.getOrNull(0) } ?: Taille.MOYENNE,
            Format.values().firstOrNull { it.cle == p.getOrNull(1) } ?: Format.APPAREIL, p.getOrNull(2) != "0",
            StylesHorloge.normaliser(p.getOrNull(3)))
    }

    /** Le style du widget Horloge, s'il a été choisi sur cet appareil (null : jamais choisi, l'anneau par défaut). */
    fun styleChoisi(ctx: Context): String? = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CLE_HORLOGE, null)
        ?.split("|")?.getOrNull(3)?.takeIf { it.isNotEmpty() }?.let { StylesHorloge.normaliser(it) }

    /** [styleChoisi] : vrai quand le style vient du choix (il est alors gardé même s'il vaut le défaut). */
    fun poserReglagesHorloge(ctx: Context, r: ReglagesHorloge, styleChoisi: Boolean = false) {
        val style = if (styleChoisi || styleChoisi(ctx) != null) r.style else ""
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(CLE_HORLOGE, "${r.taille.cle}|${r.format.cle}|${if (r.date) 1 else 0}|$style").apply()
        Local.toucher()
    }

    /** 24 h ? Selon le réglage, ou celui de l'appareil. */
    fun format24(ctx: Context, r: ReglagesHorloge = reglagesHorloge(ctx)): Boolean = when (r.format) {
        Format.H12 -> false
        Format.H24 -> true
        Format.APPAREIL -> android.text.format.DateFormat.is24HourFormat(ctx)
    }

    // ------------------------------------------------------------------ vues

    /** Le remerciement ou le message : l'œil joyeux et le texte. [tailleTexte] en sp, [coteOeil] en px. */
    fun vueRemerciement(a: Activity, code: String, theme: Theme, tailleTexte: Float, coteOeil: Int): View {
        val zone = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; tag = TAG }
        val oeil = OeilVue(a).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        zone.addView(oeil, LinearLayout.LayoutParams(coteOeil, coteOeil))
        zone.addView(TextView(a).apply {
            text = texte(a, code); setTextColor(theme.encre); textSize = tailleTexte; typeface = Polices.moyen(a)
            Ui.sansCesure(this); tag = "zone_haut_texte"
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = Ui.dp(a, 16f) })
        // L'œil joyeux démarre une fois posé (clin d'œil d'apparition) ; il se met en pause hors de l'écran (OeilVue).
        oeil.post { oeil.joyeux() }
        return zone
    }

    /**
     * 1.91.2 (retour d'essai) : la zone se personnalise par un APPUI LONG (Décideur), avec un retour haptique, sans rien de visible
     * en permanence. L'accessibilité annonce l'action (« Personnaliser cette zone »).
     */
    fun appuiLongPourPersonnaliser(a: Activity, zone: View, apres: () -> Unit) {
        zone.isLongClickable = true
        zone.setOnLongClickListener { v ->
            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            personnaliser(a, apres); true
        }
        zone.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK, a.getString(R.string.zonehaut_personnaliser)))
            }
        }
    }

    // ------------------------------------------------------------------ « Personnaliser cette zone »

    /**
     * Le téléphone : réservé aux Décideurs (code, décision de 1.90.5), puis l'éditeur de la zone.
     * 16-10 (l'auteur, télé du salon : « le code pour installer des widgets, c'est nul ») : sur la télé, plus de code — on n'y
     * pose que des widgets de Vision ou ceux que Home Assistant partage déjà avec cet appareil.
     */
    fun personnaliser(a: Activity, apres: () -> Unit) {
        if (ReglagesTvActivity.estTele(a)) composer(a, apres)
        else Garde.exiger(a, a.getString(R.string.zonehaut_raison)) { composer(a, apres) }
    }

    /**
     * L'éditeur : chaque widget du catalogue, coché s'il est dans la zone, dans l'ordre de la zone (les autres après) ;
     * « Monter » / « Descendre » changent l'ordre ; « Réglages » pour l'horloge ; « Enregistrer » pose la liste.
     */
    fun composer(a: Activity, apres: () -> Unit) {
        val tele = ReglagesTvActivity.estTele(a)
        // 1.91.2 : sur la télé, les widgets partagés par Home Assistant s'ajoutent au choix (cochés, triés à la flèche),
        // et « Tableau par défaut » remet la zone au défaut qui suit le partage.
        composerListe(a, a.getString(R.string.zonehaut_titre), widgets(a), catalogue(tele, if (tele) partagesHa(a) else emptyList()),
            parDefaut = if (tele) ({ revenirAuDefaut(a); apres() }) else null) { l -> poser(a, l); apres() }
    }

    /**
     * L'éditeur d'une liste ordonnée de widgets, commun à la zone du haut et aux zones de widgets de la télé en
     * disposition « Vertical » (DispositionAccueil) : [titre], la liste [actuels] dans son ordre, puis le reste du
     * [catalogue] ; [enregistrer] reçoit les widgets cochés, dans l'ordre ; [parDefaut], s'il est donné, ajoute
     * « Tableau par défaut ».
     */
    fun composerListe(a: Activity, titre: String, actuels: List<String>, catalogue: List<String>,
                      parDefaut: (() -> Unit)? = null, enregistrer: (List<String>) -> Unit) {
        val ordre = (actuels + catalogue.filter { it !in actuels }).toMutableList()
        val coches = actuels.toMutableSet()
        val liste = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; val p = Ui.dp(a, 16f); setPadding(p, Ui.dp(a, 8f), p, 0) }
        fun bouton(t: String, action: () -> Unit) = Ui.boutonSecondaire(a, t) { action() }
        fun remplir() {
            liste.removeAllViews()
            ordre.forEachIndexed { i, code ->
                val ligne = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; tag = "zone_ligne_$code" }
                ligne.addView(Ui.caseACocher(a, nom(a, code), code in coches) { oui -> if (oui) coches.add(code) else coches.remove(code) }
                    .apply { minHeight = Ui.dp(a, Ui.CIBLE_MIN) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (i > 0) ligne.addView(bouton("↑") { ordre.add(i - 1, ordre.removeAt(i)); remplir() }.apply { contentDescription = a.getString(R.string.zonehaut_monter, nom(a, code)) })
                if (i < ordre.size - 1) ligne.addView(bouton("↓") { ordre.add(i + 1, ordre.removeAt(i)); remplir() }.apply { contentDescription = a.getString(R.string.zonehaut_descendre, nom(a, code)) })
                if (code == HORLOGE) ligne.addView(bouton(a.getString(R.string.zonehaut_reglages)) { reglerHorloge(a) {} })
                if (code == MESSAGE) ligne.addView(bouton(a.getString(R.string.zonehaut_reglages)) { ecrireMessage(a) {} })
                liste.addView(ligne)
            }
        }
        remplir()
        Ui.dialogue(a).setTitle(titre).setView(ScrollView(a).apply { addView(liste) })
            .setPositiveButton(a.getString(R.string.zonehaut_enregistrer)) { _, _ -> enregistrer(ordre.filter { it in coches }) }
            .setNegativeButton(a.getString(R.string.commun_lot2_annuler), null)
            .apply { if (parDefaut != null) setNeutralButton(a.getString(R.string.zonehaut_tableau_defaut)) { _, _ -> parDefaut() } }
            .show()
    }

    fun ecrireMessage(a: Activity, apres: () -> Unit) {
        val champ = EditText(a).also { ClavierTele.brancher(it) }.apply { setText(message(a)); hint = a.getString(R.string.zonehaut_merci) }
        Ui.dialogue(a).setTitle(a.getString(R.string.zonehaut_mode_message)).setView(champ)
            .setPositiveButton(a.getString(R.string.zonehaut_enregistrer)) { _, _ ->
                a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(CLE_MESSAGE, champ.text.toString().trim().take(160)).apply()
                Local.toucher(); apres()
            }
            .setNegativeButton(a.getString(R.string.commun_lot2_annuler), null).show()
    }

    /** Les réglages de l'horloge stylisée : style (avec aperçu), taille, format 12 / 24 h, date affichée ou non. */
    fun reglerHorloge(a: Activity, apres: () -> Unit) {
        var r = reglagesHorloge(a)
        val tailles = Taille.values(); val formats = Format.values()
        val choix = arrayOf(
            a.getString(R.string.horloge_style) + " : " + StylesHorloge.nom(a, r.style),
            a.getString(R.string.horloge_taille) + " : " + a.getString(r.taille.nom),
            a.getString(R.string.horloge_format) + " : " + a.getString(r.format.nom),
            a.getString(R.string.horloge_date) + " : " + a.getString(if (r.date) R.string.horloge_oui else R.string.horloge_non))
        Ui.dialogue(a).setTitle(a.getString(R.string.zonehaut_widget_horloge)).setItems(choix) { _, k ->
            when (k) {
                0 -> StylesHorloge.choisir(a, r.style, veille = false) { s -> r = r.copy(style = s); poserReglagesHorloge(a, r, styleChoisi = true); reglerHorloge(a, apres) }
                1 -> Ui.dialogue(a).setTitle(a.getString(R.string.horloge_taille))
                    .setSingleChoiceItems(tailles.map { a.getString(it.nom) }.toTypedArray(), tailles.indexOf(r.taille)) { d, i -> d.dismiss(); r = r.copy(taille = tailles[i]); poserReglagesHorloge(a, r); reglerHorloge(a, apres) }.show()
                2 -> Ui.dialogue(a).setTitle(a.getString(R.string.horloge_format))
                    .setSingleChoiceItems(formats.map { a.getString(it.nom) }.toTypedArray(), formats.indexOf(r.format)) { d, i -> d.dismiss(); r = r.copy(format = formats[i]); poserReglagesHorloge(a, r); reglerHorloge(a, apres) }.show()
                else -> { r = r.copy(date = !r.date); poserReglagesHorloge(a, r); reglerHorloge(a, apres) }
            }
        }.setPositiveButton(a.getString(R.string.zonehaut_enregistrer)) { _, _ -> apres() }.show()
    }
}
