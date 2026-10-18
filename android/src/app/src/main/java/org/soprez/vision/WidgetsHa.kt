// SPDX-License-Identifier: MIT
package org.soprez.vision

import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow

/**
 * Les widgets Home Assistant de l'accueil (contrat docs/widgets-ha.md, côté intégration) : la partie sans Android.
 * Analyse des réponses /widgets/entites, /etats, /historique, /action et des événements du flux, styles proposés
 * par domaine, tailles permises par style, réduction de l'historique pour le graphique, textes affichés, actions
 * permises et délai de reconnexion. Essayée en JUnit (WidgetsHaTest).
 *
 * Une case de la grille porte la clé « widget:ha:<id> » ; le réglage du widget (entités, style, taille, libellé,
 * icône) est rangé à part, sous <id> (WidgetsHaStock).
 */
object WidgetsHa {
    const val PREFIXE = "ha:"

    const val TUILE = "tuile"
    const val BOUTON = "bouton"
    const val INTERRUPTEUR = "interrupteur"
    const val JAUGE = "jauge"
    const val GRAPHIQUE = "graphique"
    const val METEO = "meteo"
    const val OUVERTURE = "ouverture"
    const val MINI_CARTE = "mini_carte"
    /** 1.81.0 (point 14 bis) : un lecteur de musique (media_player), une ou plusieurs enceintes. */
    const val LECTEUR = "lecteur"
    /** 1.88.0 (widgets interactifs) : régler une valeur (− / +) ou choisir dans une liste (input_number, select…). */
    const val REGLAGE = "reglage"
    val STYLES = listOf(TUILE, BOUTON, INTERRUPTEUR, JAUGE, GRAPHIQUE, METEO, OUVERTURE, MINI_CARTE, LECTEUR, REGLAGE)

    /** 1.88.0 : l'affichage d'un widget — comme réglé dans Home Assistant (""), la valeur, le détail, ou les deux. */
    const val AFF_HA = ""
    /** 1.90.0 (retour d'essai) : ce que fait un toucher. "" (réglage d'un widget) : comme réglé dans Home Assistant. */
    const val TOUCHER_HA = ""
    const val TOUCHER_ROLE = "role"
    const val TOUCHER_DETAILS = "details"
    const val TOUCHER_VUE = "vue"
    const val TOUCHER_RIEN = "rien"
    val TOUCHERS = listOf(TOUCHER_ROLE, TOUCHER_DETAILS, TOUCHER_VUE, TOUCHER_RIEN)
    private const val CHEMIN_MAX = 200
    private const val CHEMIN_PERMIS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789/_-.?=&%#"
    const val AFF_VALEUR = "valeur"
    const val AFF_DETAIL = "detail"
    const val AFF_VALEUR_DETAIL = "valeur_detail"
    val AFFICHAGES = listOf(AFF_HA, AFF_VALEUR, AFF_DETAIL, AFF_VALEUR_DETAIL)

    fun nomStyle(s: String): String = when (s) {
        TUILE -> Tr.s(R.string.wha_style_tuile); BOUTON -> Tr.s(R.string.wha_style_bouton); INTERRUPTEUR -> Tr.s(R.string.wha_style_interrupteur)
        JAUGE -> Tr.s(R.string.wha_style_jauge); GRAPHIQUE -> Tr.s(R.string.wha_style_graphique)
        METEO -> Tr.s(R.string.wha_style_meteo); OUVERTURE -> Tr.s(R.string.wha_style_ouverture); MINI_CARTE -> Tr.s(R.string.wha_style_mini_carte)
        LECTEUR -> Tr.s(R.string.wha_style_lecteur); REGLAGE -> Tr.s(R.string.wha_style_reglage); else -> s
    }

    fun estHa(code: String) = code.startsWith(PREFIXE)
    fun id(code: String): String = code.removePrefix(PREFIXE)
    fun cle(id: String) = Accueil.PREFIXE_WIDGET + PREFIXE + id

    // ------------------------------------------------------------------ données

    data class Entite(
        val entityId: String, val nom: String, val domaine: String, val icone: String, val unite: String, val deviceClass: String,
        val etat: String, val attributs: Map<String, Any?>, val actions: List<String>, val styles: List<String>,
        /** 1.88.0 : rôle donné dans Home Assistant (lecture, interrupteur, bouton, reglage), confirmation demandée, affichage. */
        val role: String = "", val confirmer: Boolean = false, val affichage: String = AFF_VALEUR,
        val detail: List<Ligne> = emptyList(), val detailTotal: Int = 0,
        /** 1.90.0 : le toucher réglé dans Home Assistant pour CET appareil (droits compris), son chemin, et le droit
         *  d'ouvrir les détails ou une vue (ce que l'éditeur peut proposer de plus). */
        val toucher: String = TOUCHER_ROLE, val chemin: String = "", val ouvrir: Boolean = false,
        /** 1.91.3 : la mise en page réglée dans Home Assistant (une seule configuration par entité). */
        val miseEnPage: MiseEnPage = MiseEnPage(),
        /** 17-10 (l'auteur, « widget chauffage lisible ») : la ligne sous la valeur (entité « texte » choisie dans HA) ; null : aucune. */
        val texte: String? = null,
    )

    /** Une ligne du détail : un élément d'une liste (todo), ou un attribut et sa valeur. */
    data class Ligne(val texte: String, val valeur: String = "")

    /** `detail` null : le serveur n'en a pas donné cette fois (on garde le précédent). */
    data class EtatEntite(val etat: String, val attributs: Map<String, Any?> = emptyMap(), val depuis: String = "",
                          val detail: List<Ligne>? = null, val detailTotal: Int = 0,
                          /** 17-10 : la ligne sous la valeur ; null : le serveur n'en a pas donné (ancienne version, ou aucune). */
                          val texte: String? = null)

    data class Point(val ts: Long, val v: Double)
    data class Segment(val debut: Long, val fin: Long, val etat: String)

    sealed class Historique {
        data class Nombres(val unite: String, val points: List<Point>) : Historique()
        data class Etats(val segments: List<Segment>) : Historique()
    }

    private fun Map<String, Any?>.t(k: String): String = when (val v = this[k]) { null -> ""; is Double -> nombreTexte(v); else -> v.toString() }
    @Suppress("UNCHECKED_CAST") private fun Map<String, Any?>.m(k: String): Map<String, Any?> = this[k] as? Map<String, Any?> ?: emptyMap()
    private fun Map<String, Any?>.l(k: String): List<Any?> = this[k] as? List<Any?> ?: emptyList()
    private fun nombreTexte(d: Double): String = if (d == Math.floor(d) && abs(d) < 1e15) d.toLong().toString() else d.toString()

    /** Une entité de /widgets/entites (ou du cache). */
    @Suppress("UNCHECKED_CAST")
    fun entiteDe(o: Map<String, Any?>): Entite? {
        val id = o.t("entity_id").ifEmpty { o.t("id") }
        if (id.isEmpty() || !id.contains('.')) return null
        val attr = o.m("attributs_utiles")
        val domaine = o.t("domaine").ifEmpty { id.substringBefore('.') }
        return Entite(id, o.t("nom").ifEmpty { attr.t("friendly_name").ifEmpty { id } }, domaine, o.t("icone"),
            o.t("unite").ifEmpty { attr.t("unit_of_measurement") }, o.t("device_class").ifEmpty { attr.t("device_class") },
            o.t("etat"), attr, o.l("actions").mapNotNull { it?.toString() }, o.l("styles").mapNotNull { it?.toString() },
            o.t("role"), o["confirmer"] == true, o.t("affichage").takeIf { it in AFFICHAGES && it.isNotEmpty() } ?: AFF_VALEUR,
            lignes(o) ?: emptyList(), total(o),
            toucher = o.t("toucher").takeIf { it in TOUCHERS } ?: TOUCHER_ROLE, chemin = o.t("chemin"), ouvrir = o["ouvrir"] == true,
            miseEnPage = miseEnPageDe(o.m("mise_en_page")), texte = o["texte"] as? String)
    }

    /**
     * 17-10 (retour d'essai) : la ligne sous la valeur en une case de large (1 × 1) — le mode et l'heure seulement : « Réduit jusqu'à
     * 12:00, puis Confort 20 °C » → « Réduit → 12:00 ». Sans heure : le premier mot. Pur.
     */
    fun texteCourt(t: String): String {
        val mot = t.trim().split(' ', ',', '·').firstOrNull { it.isNotBlank() }.orEmpty()
        val heure = Regex("""\b\d{1,2}[:h]\d{2}\b""").find(t)?.value
        return if (heure != null && mot.isNotEmpty() && !mot.contains(heure)) "$mot → $heure" else mot
    }

    // ------------------------------------------------------------------ 1.91.3 : la mise en page (réglée dans HA)

    const val GAUCHE = "gauche"; const val CENTRE = "centre"; const val DROITE = "droite"
    const val HAUT = "haut"; const val BAS = "bas"
    const val REMPLIR = "remplir"
    val ALIGNS_H = listOf(GAUCHE, CENTRE, DROITE)
    val ALIGNS_V = listOf(HAUT, CENTRE, BAS)
    val TAILLES = listOf(REMPLIR, "s", "m", "l", "xl")
    /** Les styles qui suivent la mise en page (un titre et une valeur) ; bouton, graphique, météo, lecteur… gardent la leur. */
    val STYLES_MISE_EN_PAGE = listOf(TUILE, REGLAGE, INTERRUPTEUR, JAUGE, OUVERTURE)

    /**
     * La mise en page d'un widget : alignement horizontal et vertical du titre et de la valeur, taille de la valeur
     * (« remplir » : la plus grande qui tient ; ou S, M, L, XL), titre et icône montrés ou non. Une seule configuration
     * par entité, dans Home Assistant (onglet Widgets HA, ou l'éditeur de l'appli d'un Décideur). Défaut (retour d'essai) : valeur
     * grande et centrée, titre et icône montrés.
     */
    data class MiseEnPage(val alignH: String = CENTRE, val alignV: String = CENTRE, val taille: String = REMPLIR,
                          val titre: Boolean = true, val icone: Boolean = true)

    /** D'après ce qu'envoie HA (« mise_en_page ») ; une valeur inconnue → le défaut. Pur. */
    fun miseEnPageDe(o: Map<String, Any?>): MiseEnPage {
        val d = MiseEnPage()
        return MiseEnPage(
            (o["align_h"] as? String)?.takeIf { it in ALIGNS_H } ?: d.alignH,
            (o["align_v"] as? String)?.takeIf { it in ALIGNS_V } ?: d.alignV,
            (o["taille"] as? String)?.takeIf { it in TAILLES } ?: d.taille,
            o["titre"] as? Boolean ?: d.titre,
            o["icone"] as? Boolean ?: d.icone)
    }

    /** Ce qu'on envoie à HA (/widgets/mise_en_page). Pur. */
    fun versHa(m: MiseEnPage): Map<String, Any> = mapOf("align_h" to m.alignH, "align_v" to m.alignV, "taille" to m.taille, "titre" to m.titre, "icone" to m.icone)

    /** La mise en page d'un widget : l'aperçu de l'éditeur s'il y en a un, sinon celle de HA. */
    fun miseEnPage(r: Reglage, e: Entite): MiseEnPage = r.apercu ?: e.miseEnPage

    /** Taille (sp) de la valeur pour S, M, L, XL ; un widget petit (moins de 110 dp) un cran plus bas. Pur. */
    fun tailleSp(taille: String, petit: Boolean): Float {
        val base = when (taille) { "s" -> 16f; "m" -> 22f; "l" -> 30f; "xl" -> 40f; else -> 28f }
        return if (petit) base * 0.72f else base
    }

    /**
     * « Remplir » : la taille (px) qui fait tenir un texte d'une ligne dans [largeur] × [hauteur], sachant sa largeur
     * [largeurA100] et sa hauteur de ligne [hauteurA100] à 100 px ; bornée à [min]..[max]. Pur.
     */
    fun tailleQuiTient(largeurA100: Float, hauteurA100: Float, largeur: Float, hauteur: Float, min: Float, max: Float): Float {
        if (largeur <= 0f || hauteur <= 0f) return min
        val parLargeur = if (largeurA100 > 0f) largeur * 100f / largeurA100 else max
        val parHauteur = if (hauteurA100 > 0f) hauteur * 100f / hauteurA100 else max
        return minOf(parLargeur, parHauteur, max).coerceAtLeast(min)
    }

    /** 1.90.0 : un chemin de Home Assistant (« / » en tête, jamais une autre adresse ni « // ») ; null sinon. */
    fun cheminSur(c: String?): String? {
        val s = c?.trim().orEmpty()
        if (s.isEmpty() || s.length > CHEMIN_MAX || !s.startsWith("/") || s.startsWith("//") || s.contains("://")) return null
        return s.takeIf { x -> x.all { it in CHEMIN_PERMIS } }
    }

    /**
     * 1.90.0 : ce que fait un toucher sur ce widget, et le chemin à ouvrir. Le choix du widget ([Reglage.toucher]) passe
     * avant celui de Home Assistant, sans jamais dépasser les droits : détails et vues seulement si Home Assistant le
     * permet à cet appareil ([Entite.ouvrir]) ; sinon, l'action du rôle.
     */
    fun toucher(r: Reglage?, e: Entite): Pair<String, String> {
        val local = r?.toucher.orEmpty()
        val (t, c) = if (local in TOUCHERS) local to (if (local == TOUCHER_VUE) r?.chemin.orEmpty() else "") else e.toucher to e.chemin
        return when (t) {
            TOUCHER_RIEN -> TOUCHER_RIEN to ""
            TOUCHER_DETAILS -> if (e.ouvrir || local.isEmpty()) TOUCHER_DETAILS to "" else TOUCHER_ROLE to ""
            TOUCHER_VUE -> cheminSur(c)?.takeIf { e.ouvrir || local.isEmpty() }?.let { TOUCHER_VUE to it } ?: (TOUCHER_ROLE to "")
            else -> TOUCHER_ROLE to ""
        }
    }

    /** 1.90.0 : les tableaux de bord et vues de Home Assistant (titre, chemin), donnés à l'éditeur d'un Décideur. */
    @Suppress("UNCHECKED_CAST")
    fun vuesHa(texte: String): List<Pair<String, String>> {
        val o = Json.objet(texte) ?: return emptyList()
        return o.l("vues_ha").mapNotNull { v -> (v as? Map<String, Any?>)?.let { m -> cheminSur(m.t("chemin"))?.let { m.t("titre").ifEmpty { it } to it } } }
    }

    /** Le détail donné par le serveur (`detail` : [{texte, valeur}]) ; null s'il n'y en a pas dans la réponse. */
    @Suppress("UNCHECKED_CAST")
    private fun lignes(o: Map<String, Any?>): List<Ligne>? {
        val l = o["detail"] as? List<Any?> ?: return null
        return l.mapNotNull { (it as? Map<String, Any?>)?.let { x -> Ligne(x.t("texte"), x.t("valeur")).takeIf { y -> y.texte.isNotEmpty() } } }.take(50)
    }

    private fun total(o: Map<String, Any?>): Int = (o["detail_total"] as? Double)?.toInt() ?: (lignes(o)?.size ?: 0)

    /** /widgets/entites → la liste des entités permises, triée par nom. */
    fun analyserEntites(texte: String): List<Entite> {
        val o = Json.objet(texte) ?: return emptyList()
        @Suppress("UNCHECKED_CAST")
        return o.l("entites").mapNotNull { (it as? Map<String, Any?>)?.let(::entiteDe) }
            .sortedBy { it.nom.lowercase(Locale.FRANCE) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun etatDe(o: Map<String, Any?>): EtatEntite = EtatEntite(o.t("etat"), o.m("attributs_utiles"), o.t("depuis"), lignes(o), total(o), o["texte"] as? String)

    /** /widgets/etats → {entity_id: état}. */
    @Suppress("UNCHECKED_CAST")
    fun analyserEtats(texte: String): Map<String, EtatEntite> {
        val o = Json.objet(texte) ?: return emptyMap()
        return o.m("etats").mapNotNull { (k, v) -> (v as? Map<String, Any?>)?.let { k to etatDe(it) } }.toMap()
    }

    /** Une ligne `data:` du flux → (entity_id, état) ; null pour un battement ou une ligne illisible. */
    fun analyserEvenement(data: String): Pair<String, EtatEntite>? {
        val o = Json.objet(data) ?: return null
        val id = o.t("entity_id")
        if (id.isEmpty()) return null
        return id to etatDe(o)
    }

    /** Horodatage du serveur (secondes ou millisecondes, nombre ou texte ISO) → millisecondes. */
    fun horodatage(v: Any?): Long? = when (v) {
        is Double -> if (v > 1e11) v.toLong() else (v * 1000).toLong()
        is String -> v.toDoubleOrNull()?.let { horodatage(it) } ?: iso(v)
        else -> null
    }

    private val ISO = Regex("""(\d{4})-(\d\d)-(\d\d)[T ](\d\d):(\d\d)(?::(\d\d)(?:\.(\d+))?)?(Z|[+-]\d\d:?\d\d)?""")

    /** « 2026-10-08T12:34:56.789+02:00 » → millisecondes (sans java.time : Android 5 n'en a pas). */
    fun iso(s: String): Long? {
        val m = ISO.matchEntire(s.trim()) ?: return null
        val g = m.groupValues
        val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        c.clear()
        c.set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].ifEmpty { "0" }.toInt())
        var ms = c.timeInMillis + (g[7].take(3).padEnd(3, '0').ifEmpty { "000" }.toInt())
        val z = g[8]
        if (z.isNotEmpty() && z != "Z") {
            val signe = if (z[0] == '-') -1 else 1
            val chiffres = z.substring(1).replace(":", "")
            ms -= signe * (chiffres.substring(0, 2).toInt() * 3_600_000L + chiffres.substring(2, 4).toInt() * 60_000L)
        }
        return ms
    }

    /** /widgets/historique → points (nombres) ou segments (états). */
    fun analyserHistorique(texte: String): Historique? {
        val o = Json.objet(texte) ?: return null
        return when (o.t("type")) {
            "nombre" -> Historique.Nombres(o.t("unite"), o.l("points").mapNotNull { p ->
                val a = p as? List<*> ?: return@mapNotNull null
                val ts = horodatage(a.getOrNull(0)); val v = (a.getOrNull(1) as? Double) ?: (a.getOrNull(1) as? String)?.toDoubleOrNull()
                if (ts == null || v == null || v.isNaN()) null else Point(ts, v)
            }.sortedBy { it.ts })
            "etats" -> Historique.Etats(o.l("segments").mapNotNull { s ->
                val a = s as? List<*> ?: return@mapNotNull null
                val d = horodatage(a.getOrNull(0)); val f = horodatage(a.getOrNull(1))
                if (d == null || f == null) null else Segment(d, f, a.getOrNull(2)?.toString() ?: "")
            }.sortedBy { it.debut })
            else -> null
        }
    }

    /** Le motif d'un refus du serveur (403 {"error": "module" | "piloter" | "action" | "entite"}). */
    fun motif(corps: String?): String = Json.objet(corps)?.t("error").orEmpty()

    /** Un message clair pour un échec d'appel /widgets/…. */
    fun messageErreur(code: Int, motif: String): String = when {
        code == 403 && motif == "module" -> Tr.s(R.string.wha_err_module)
        code == 403 && motif == "piloter" -> Tr.s(R.string.wha_err_piloter)
        code == 403 && motif == "action" -> Tr.s(R.string.wha_err_action)
        code == 403 && motif == "entite" -> Tr.s(R.string.wha_err_entite)
        code == 403 -> Tr.s(R.string.wha_err_403)
        code == 404 -> Tr.s(R.string.wha_err_404)
        code == 429 -> Tr.s(R.string.wha_err_429)
        code == 502 -> Tr.s(R.string.wha_err_502)
        code == 400 && motif == "periode" -> Tr.s(R.string.wha_err_periode)
        code == 400 && motif == "data" -> Tr.s(R.string.wha_err_data)
        code == 400 -> Tr.s(R.string.wha_err_400)
        code == 401 -> Tr.s(R.string.wha_err_401)
        code <= 0 -> Tr.s(R.string.wha_err_injoignable)
        else -> Tr.s(R.string.lot6_err_widget)
    }

    // ------------------------------------------------------------------ styles

    private val DOMAINES_INTERRUPTEUR = setOf("light", "switch", "fan", "input_boolean", "automation", "siren", "humidifier")
    private val DOMAINES_BOUTON = setOf("script", "scene", "button", "input_button")
    private val CLASSES_OUVERTURE = setOf("door", "window", "garage_door", "opening", "garage", "lock")

    fun estNombre(etat: String): Boolean = etat.trim().replace(',', '.').toDoubleOrNull()?.isNaN() == false

    /** Les styles proposés selon le domaine (repli quand le serveur ne donne pas `styles`). Mini-carte : partout. */
    fun stylesParDomaine(domaine: String, deviceClass: String, etat: String): List<String> {
        val l = ArrayList<String>()
        when {
            domaine in DOMAINES_BOUTON -> { l.add(BOUTON); l.add(TUILE) }
            domaine in DOMAINES_INTERRUPTEUR -> { l.add(INTERRUPTEUR); l.add(TUILE); l.add(GRAPHIQUE) }
            domaine == "weather" -> { l.add(METEO); l.add(TUILE) }
            domaine == "media_player" -> { l.add(LECTEUR); l.add(TUILE) }
            domaine == "binary_sensor" && deviceClass in CLASSES_OUVERTURE -> { l.add(OUVERTURE); l.add(TUILE); l.add(GRAPHIQUE) }
            domaine == "cover" -> { l.add(OUVERTURE); l.add(TUILE) }
            domaine == "binary_sensor" -> { l.add(TUILE); l.add(GRAPHIQUE) }
            domaine in setOf("input_number", "number") -> { l.add(REGLAGE); l.add(TUILE); l.add(JAUGE); l.add(GRAPHIQUE) }
            domaine in setOf("input_select", "select") -> { l.add(REGLAGE); l.add(TUILE) }
            domaine in setOf("sensor", "counter") && estNombre(etat) -> { l.add(TUILE); l.add(JAUGE); l.add(GRAPHIQUE) }
            domaine == "climate" || domaine == "water_heater" -> { l.add(TUILE); l.add(GRAPHIQUE) }
            else -> { l.add(TUILE); l.add(GRAPHIQUE) }
        }
        l.add(MINI_CARTE)
        return l
    }

    /** Ceux du serveur (s'il en donne) dans l'ordre reçu, connus de l'appli ; mini-carte toujours possible. */
    fun stylesProposes(e: Entite): List<String> {
        val serveur = e.styles.filter { it in STYLES }
        val base = if (serveur.isNotEmpty()) serveur else stylesParDomaine(e.domaine, e.deviceClass, e.etat)
        return if (MINI_CARTE in base) base else base + MINI_CARTE
    }

    /** Les tailles proposées par style (colonnes × lignes), la première étant celle de départ. */
    fun taillesPermises(style: String): List<Pair<Int, Int>> = when (style) {
        TUILE -> listOf(2 to 1, 1 to 1, 2 to 2, 4 to 1, 4 to 2)
        BOUTON -> listOf(1 to 1, 2 to 1, 2 to 2)
        INTERRUPTEUR -> listOf(2 to 1, 1 to 1, 2 to 2, 4 to 1)
        JAUGE -> listOf(2 to 2, 1 to 1, 2 to 1, 3 to 3, 4 to 2)
        GRAPHIQUE -> listOf(4 to 2, 2 to 2, 4 to 1, 4 to 3, 4 to 4)
        METEO -> listOf(4 to 2, 2 to 1, 2 to 2, 4 to 1, 4 to 3)
        OUVERTURE -> listOf(1 to 1, 2 to 1, 2 to 2)
        MINI_CARTE -> listOf(4 to 2, 2 to 2, 4 to 1, 4 to 3)
        LECTEUR -> listOf(4 to 2, 2 to 2, 4 to 1, 4 to 3)
        REGLAGE -> listOf(2 to 1, 2 to 2, 4 to 1, 4 to 2)
        else -> listOf(2 to 1)
    }

    /** Les bornes de redimensionnement d'un style (cellules). */
    fun bornes(style: String, colonnes: Int = Accueil.COLONNES): Redim.Bornes {
        val t = taillesPermises(style)
        return Redim.Bornes(t.minOf { it.first }, t.minOf { it.second }, min(colonnes, t.maxOf { it.first }), t.maxOf { it.second })
    }

    // ------------------------------------------------------------------ réglage d'un widget

    data class Reglage(
        val id: String, val entites: List<String>, val style: String, val w: Int, val h: Int,
        val libelle: String = "", val icone: String = "", val variante: String = "ligne", val periode: String = "24h",
        /** 1.88.0 : l'affichage choisi pour CE widget ("" : comme réglé dans Home Assistant). */
        val affichage: String = AFF_HA,
        /** 1.90.0 : le toucher choisi pour CE widget ("" : comme réglé dans Home Assistant) et le chemin d'une vue. */
        val toucher: String = TOUCHER_HA, val chemin: String = "",
        /** 1.91.2 (retour d'essai) : le contenu (détail) défile dans le widget ; sinon ce qui tient, puis « Voir tout ». */
        val defilement: Boolean = true,
        /** 1.91.2 (retour d'essai) : la hauteur maximale du détail ([HAUTEUR_COMPACTE], [HAUTEUR_MOYENNE], [HAUTEUR_GRANDE]). */
        val hauteur: String = HAUTEUR_MOYENNE,
        /** 1.91.3 : la mise en page en cours de choix dans l'éditeur (aperçu) ; jamais enregistrée ici : elle vit dans HA. */
        val apercu: MiseEnPage? = null,
    )

    const val HAUTEUR_COMPACTE = "compacte"
    const val HAUTEUR_MOYENNE = "moyenne"
    const val HAUTEUR_GRANDE = "grande"
    val HAUTEURS = listOf(HAUTEUR_COMPACTE, HAUTEUR_MOYENNE, HAUTEUR_GRANDE)

    /** Le nombre de lignes de détail visibles au plus, selon la hauteur maximale choisie. */
    fun lignesMax(hauteur: String): Int = when (hauteur) { HAUTEUR_COMPACTE -> 3; HAUTEUR_GRANDE -> 10; else -> 6 }

    fun encoder(r: Reglage): String = Json.ecrire(linkedMapOf("id" to r.id, "entites" to r.entites, "style" to r.style, "w" to r.w, "h" to r.h,
        "libelle" to r.libelle, "icone" to r.icone, "variante" to r.variante, "periode" to r.periode, "affichage" to r.affichage,
        "toucher" to r.toucher, "chemin" to r.chemin, "defilement" to r.defilement, "hauteur" to r.hauteur))

    fun decoder(s: String?): Reglage? {
        val o = Json.objet(s) ?: return null
        val entites = o.l("entites").mapNotNull { it?.toString() }.filter { it.contains('.') }
        val style = o.t("style").takeIf { it in STYLES } ?: return null
        if (entites.isEmpty() || o.t("id").isEmpty()) return null
        return Reglage(o.t("id"), entites, style, (o["w"] as? Double)?.toInt() ?: 2, (o["h"] as? Double)?.toInt() ?: 1,
            o.t("libelle"), o.t("icone"), o.t("variante").ifEmpty { "ligne" }, o.t("periode").ifEmpty { "24h" },
            o.t("affichage").takeIf { it in AFFICHAGES } ?: AFF_HA,
            o.t("toucher").takeIf { it in TOUCHERS } ?: TOUCHER_HA, o.t("chemin"),
            // 1.91.2 : absent (widget d'avant) → défilement activé, hauteur moyenne.
            o["defilement"] as? Boolean ?: true, o.t("hauteur").takeIf { it in HAUTEURS } ?: HAUTEUR_MOYENNE)
    }

    // ------------------------------------------------------------------ affichage et détail (1.88.0)

    /** L'affichage d'un widget : son choix, sinon celui de Home Assistant. */
    fun affichage(r: Reglage, e: Entite): String = r.affichage.ifEmpty { e.affichage }.takeIf { it in AFFICHAGES && it.isNotEmpty() } ?: AFF_VALEUR

    fun montreValeur(aff: String) = aff != AFF_DETAIL
    fun montreDetail(aff: String) = aff == AFF_DETAIL || aff == AFF_VALEUR_DETAIL

    /** Le détail le plus frais (état du flux, sinon catalogue) et son total. */
    fun detail(e: Entite, st: EtatEntite): Pair<List<Ligne>, Int> =
        if (st.detail != null) st.detail to maxOf(st.detailTotal, st.detail.size) else e.detail to maxOf(e.detailTotal, e.detail.size)

    /** Les lignes montrées dans le widget (au plus [place]) et le « +N » des autres (0 : rien à ajouter). */
    fun detailTronque(lignes: List<Ligne>, total: Int, place: Int): Pair<List<Ligne>, Int> {
        val n = place.coerceAtLeast(1)
        val montrees = if (total > n) lignes.take(n - 1).ifEmpty { lignes.take(1) } else lignes.take(n)
        return montrees to (total - montrees.size).coerceAtLeast(0)
    }

    fun texteLigne(l: Ligne): String = if (l.valeur.isEmpty()) l.texte else Tr.s(R.string.wha_ligne_detail, l.texte, l.valeur)

    // ------------------------------------------------------------------ réglage d'une valeur ou d'une liste (1.88.0)

    private fun nombreAttr(a: Map<String, Any?>, k: String): Double? = (a[k] as? Double) ?: (a[k] as? String)?.toDoubleOrNull()

    /** La valeur après − ou + (pas de l'entité, 1 sinon), bornée à min / max ; null si on ne peut pas régler. */
    fun valeurApres(e: Entite, st: EtatEntite, sens: Int): Double? {
        if ("set_value" !in e.actions) return null
        val v = st.etat.replace(',', '.').toDoubleOrNull() ?: return null
        val pas = nombreAttr(st.attributs, "step")?.takeIf { it > 0 } ?: 1.0
        var n = v + pas * sens
        nombreAttr(st.attributs, "min")?.let { n = maxOf(n, it) }
        nombreAttr(st.attributs, "max")?.let { n = minOf(n, it) }
        return Math.round(n / pas) * pas
    }

    /** Les choix d'une liste (attribut « options » de l'entité). */
    fun options(st: EtatEntite): List<String> = (st.attributs["options"] as? List<*>)?.mapNotNull { it?.toString() }.orEmpty()

    /**
     * L'icône : celle du widget dans l'appli, sinon celle donnée par Home Assistant (« mdi:… », rapprochée d'un picto de
     * Vision par ses mots), sinon celle du domaine.
     */
    fun picto(r: Reglage, e: Entite): String? = when {
        r.icone == "aucune" -> null
        r.icone.isNotEmpty() -> r.icone
        e.icone.startsWith("mdi:") -> pictoDepuisMdi(e.icone) ?: pictoAuto(e.domaine, e.deviceClass)
        else -> pictoAuto(e.domaine, e.deviceClass)
    }

    private val MOTS_PICTOS = listOf(
        listOf("bulb", "lamp", "light", "ceiling", "led") to "ampoule", listOf("power", "socket", "plug", "toggle") to "prise",
        listOf("thermo", "temperature", "radiator", "heat", "fire") to "thermometre", listOf("water", "drop", "humid", "pool") to "goutte",
        listOf("flash", "lightning", "bolt", "battery", "flash") to "eclair", listOf("door", "gate", "garage") to "porte",
        listOf("window", "blind", "curtain", "shutter") to "fenetre", listOf("sun", "weather", "cloud") to "soleil",
        listOf("fan") to "ventilateur", listOf("play", "music", "movie", "television", "speaker", "spotify") to "lecture",
        listOf("lock", "key", "shield") to "cadenas", listOf("home", "house", "office", "school", "domain") to "batiment",
        listOf("account", "human", "person", "face", "baby") to "personne", listOf("bell", "alarm", "siren", "alert") to "cloche",
    )

    fun pictoDepuisMdi(mdi: String): String? {
        val m = mdi.removePrefix("mdi:").lowercase(Locale.ROOT)
        return MOTS_PICTOS.firstOrNull { (mots, _) -> mots.any { it in m } }?.second
    }

    // ------------------------------------------------------------------ actions

    /** Une entité peut-elle être pilotée d'ici (pas sur la télé, et seulement ce que le serveur permet) ? */
    fun pilotable(e: Entite?, tele: Boolean): Boolean = !tele && e != null && e.actions.isNotEmpty()

    fun estAllume(etat: String): Boolean = etat in setOf("on", "open", "opening", "unlocked", "playing", "home", "heat", "cool", "auto")

    /** Le service à appeler pour basculer un interrupteur : toggle s'il est permis, sinon l'inverse de l'état. */
    fun serviceBascule(e: Entite, etat: String): String? = when {
        "toggle" in e.actions -> "toggle"
        estAllume(etat) && "turn_off" in e.actions -> "turn_off"
        !estAllume(etat) && "turn_on" in e.actions -> "turn_on"
        else -> null
    }

    /** Le service d'un bouton : press (button), turn_on (script, scène), sinon la première action permise. */
    fun serviceBouton(e: Entite): String? = when {
        e.domaine in setOf("button", "input_button") && "press" in e.actions -> "press"
        "turn_on" in e.actions -> "turn_on"
        "press" in e.actions -> "press"
        "toggle" in e.actions -> "toggle"
        else -> e.actions.firstOrNull()
    }

    // ------------------------------------------------------------------ lecteur (point 14 bis)

    /** Ce qui passe sur une enceinte, tiré des attributs utiles (media_title, media_artist, volume_level). */
    data class Lecture(val titre: String, val artiste: String, val enLecture: Boolean, val volume: Double?, val pochette: String)

    fun lecture(st: EtatEntite): Lecture = Lecture(
        st.attributs.t("media_title"), st.attributs.t("media_artist").ifEmpty { st.attributs.t("app_name") },
        st.etat == "playing", (st.attributs["volume_level"] as? Double)?.coerceIn(0.0, 1.0), st.attributs.t("pochette"),
    )

    /** Le service d'une touche du lecteur (lecture, suivant, precedent), s'il est permis pour cette entité. */
    fun serviceLecteur(e: Entite, touche: String): String? = when (touche) {
        "lecture" -> "media_play_pause"; "suivant" -> "media_next_track"; "precedent" -> "media_previous_track"
        else -> null
    }?.takeIf { it in e.actions }

    /** Le volume après un appui sur − ou + (pas de 5 %), borné à 0–1 ; null si le volume n'est pas pilotable. */
    fun volumeApres(e: Entite, actuel: Double?, sens: Int): Double? {
        if ("volume_set" !in e.actions) return null
        val v = ((actuel ?: 0.3) + 0.05 * sens).coerceIn(0.0, 1.0)
        return Math.round(v * 20) / 20.0
    }

    /** L'état attendu tout de suite après une touche (règle du projet : l'écran suit le clic avant la réponse). */
    fun etatApres(st: EtatEntite, service: String, volume: Double? = null): EtatEntite = when (service) {
        "media_play_pause" -> st.copy(etat = if (st.etat == "playing") "paused" else "playing")
        "volume_set" -> if (volume == null) st else st.copy(attributs = st.attributs + ("volume_level" to volume))
        else -> st
    }

    // ------------------------------------------------------------------ textes

    fun nombre(v: Double, decimales: Int = -1): String {
        val d = if (decimales >= 0) decimales else when { abs(v) >= 100 -> 0; abs(v) >= 10 -> 1; v == Math.floor(v) -> 0; else -> 1 }
        // Les chiffres selon la langue de l'appareil (« 21,5 » en français, « 21.5 » en anglais).
        val l = Tr.locale()
        val virgule = java.text.DecimalFormatSymbols.getInstance(l).decimalSeparator
        val s = String.format(l, "%.${d}f", v)
        return if (s.contains(virgule)) s.trimEnd('0').trimEnd(virgule) else s
    }

    /** La valeur à montrer : nombre à la française avec son unité, ou l'état traduit. */
    fun valeurAffichee(etat: String, unite: String, domaine: String, deviceClass: String): String {
        if (etat.isEmpty() || etat == "unavailable" || etat == "unknown") return "–"
        val n = etat.trim().replace(',', '.').toDoubleOrNull()
        if (n != null && domaine !in setOf("binary_sensor", "light", "switch")) return (nombre(n) + if (unite.isNotEmpty()) (if (unite == "%" || unite.startsWith("°")) unite else " $unite") else "").trim()
        return libelleEtat(domaine, deviceClass, etat)
    }

    fun libelleEtat(domaine: String, deviceClass: String, etat: String): String = when (etat) {
        "on" -> when {
            domaine == "binary_sensor" && deviceClass in CLASSES_OUVERTURE -> Tr.s(R.string.wha_etat_ouvert)
            domaine == "binary_sensor" && deviceClass in setOf("motion", "occupancy", "presence") -> Tr.s(R.string.wha_etat_detecte)
            domaine == "binary_sensor" -> Tr.s(R.string.wha_etat_actif)
            else -> Tr.s(R.string.wha_etat_allume)
        }
        "off" -> when {
            domaine == "binary_sensor" && deviceClass in CLASSES_OUVERTURE -> Tr.s(R.string.wha_etat_ferme)
            domaine == "binary_sensor" && deviceClass in setOf("motion", "occupancy", "presence") -> Tr.s(R.string.wha_etat_rien)
            domaine == "binary_sensor" -> Tr.s(R.string.wha_etat_inactif)
            else -> Tr.s(R.string.wha_etat_eteint)
        }
        "open" -> Tr.s(R.string.wha_etat_ouvert); "closed" -> Tr.s(R.string.wha_etat_ferme)
        "opening" -> Tr.s(R.string.wha_etat_s_ouvre); "closing" -> Tr.s(R.string.wha_etat_se_ferme)
        "locked" -> Tr.s(R.string.wha_etat_verrouille); "unlocked" -> Tr.s(R.string.wha_etat_deverrouille)
        "home" -> Tr.s(R.string.wha_etat_present); "not_home" -> Tr.s(R.string.wha_etat_absent)
        "playing" -> Tr.s(R.string.wha_etat_lecture); "paused" -> Tr.s(R.string.wha_etat_pause)
        "idle" -> Tr.s(R.string.wha_etat_repos); "standby" -> Tr.s(R.string.wha_etat_veille)
        "heat" -> Tr.s(R.string.wha_etat_chauffe); "cool" -> Tr.s(R.string.wha_etat_refroidit); "auto" -> Tr.s(R.string.wha_etat_auto)
        "unavailable", "unknown", "" -> Tr.s(R.string.wha_etat_indisponible)
        else -> if (domaine == "weather") Meteo.libelle(etat) else etat.replaceFirstChar { it.titlecase(Tr.locale()) }
    }

    /** Le pictogramme choisi d'office d'après le domaine et la classe. */
    fun pictoAuto(domaine: String, deviceClass: String): String = when {
        domaine == "light" -> "ampoule"
        domaine in setOf("switch", "input_boolean", "automation") -> "prise"
        domaine == "fan" -> "ventilateur"
        domaine in setOf("script", "scene", "button", "input_button", "media_player") -> "lecture"
        domaine == "lock" -> "cadenas"
        domaine in setOf("person", "device_tracker") -> "personne"
        domaine == "alarm_control_panel" || domaine == "siren" -> "cloche"
        domaine == "weather" -> "soleil"
        domaine == "cover" -> "fenetre"
        domaine == "climate" || domaine == "water_heater" -> "thermometre"
        deviceClass in setOf("door", "garage_door", "opening", "garage") -> "porte"
        deviceClass == "window" -> "fenetre"
        deviceClass == "temperature" -> "thermometre"
        deviceClass in setOf("humidity", "moisture") -> "goutte"
        deviceClass in setOf("power", "energy", "current", "voltage", "battery") -> "eclair"
        deviceClass in setOf("motion", "occupancy", "presence") -> "personne"
        else -> "etoile"
    }

    val PICTOS = listOf("ampoule", "prise", "thermometre", "goutte", "eclair", "porte", "fenetre", "soleil", "ventilateur", "lecture", "cadenas", "batiment", "personne", "cloche", "etoile")

    fun nomPicto(p: String): String = when (p) {
        "ampoule" -> Tr.s(R.string.wha_picto_ampoule); "prise" -> Tr.s(R.string.wha_picto_prise)
        "thermometre" -> Tr.s(R.string.wha_picto_thermometre); "goutte" -> Tr.s(R.string.wha_picto_goutte); "eclair" -> Tr.s(R.string.wha_picto_eclair)
        "porte" -> Tr.s(R.string.wha_picto_porte); "fenetre" -> Tr.s(R.string.wha_picto_fenetre); "soleil" -> Tr.s(R.string.wha_picto_soleil)
        "ventilateur" -> Tr.s(R.string.wha_picto_ventilateur); "lecture" -> Tr.s(R.string.wha_picto_lecture)
        "cadenas" -> Tr.s(R.string.wha_picto_cadenas); "batiment" -> Tr.s(R.string.wha_picto_batiment); "personne" -> Tr.s(R.string.wha_picto_personne)
        "cloche" -> Tr.s(R.string.wha_picto_cloche); "etoile" -> Tr.s(R.string.wha_picto_etoile)
        "" -> Tr.s(R.string.wha_picto_auto); "aucune" -> Tr.s(R.string.wha_picto_aucune); else -> p
    }

    // ------------------------------------------------------------------ jauge et historique

    /** Le minimum et le maximum de la jauge : attributs min/max, sinon 0–100 pour un pourcentage, sinon d'après la valeur. */
    fun minMax(attributs: Map<String, Any?>, unite: String, valeur: Double?): Pair<Double, Double> {
        val mi = (attributs["min"] as? Double) ?: (attributs["min"] as? String)?.toDoubleOrNull()
        val ma = (attributs["max"] as? Double) ?: (attributs["max"] as? String)?.toDoubleOrNull()
        if (mi != null && ma != null && ma > mi) return mi to ma
        if (unite == "%") return 0.0 to 100.0
        if (unite == "°C") return (mi ?: 0.0) to (ma ?: 40.0)
        val v = valeur ?: return 0.0 to 100.0
        if (v >= 0) { val p = 10.0.pow(Math.ceil(Math.log10(v.coerceAtLeast(1.0))).coerceAtLeast(1.0)); return (mi ?: 0.0) to (ma ?: p) }
        return (mi ?: v * 2) to (ma ?: 0.0)
    }

    fun fraction(v: Double, mi: Double, ma: Double): Float = if (ma <= mi) 0f else ((v - mi) / (ma - mi)).toFloat().coerceIn(0f, 1f)

    /** Au plus `max` points : moyenne par intervalle de temps égal (les intervalles vides disparaissent). */
    fun reduire(points: List<Point>, max: Int): List<Point> {
        if (points.size <= max || max < 2) return points
        val debut = points.first().ts; val fin = points.last().ts
        val pasMs = ((fin - debut).toDouble() / max).coerceAtLeast(1.0)
        val sommes = DoubleArray(max); val n = IntArray(max); val ts = LongArray(max)
        for (p in points) {
            val k = ((p.ts - debut) / pasMs).toInt().coerceIn(0, max - 1)
            sommes[k] += p.v; ts[k] += (p.ts - debut); n[k]++
        }
        return (0 until max).filter { n[it] > 0 }.map { Point(debut + ts[it] / n[it], sommes[it] / n[it]) }
    }

    /**
     * 16-10 (l'auteur : « la courbe ne prend pas toute la largeur, parfois seule la moitié est dessinée ») : ce que trace une
     * courbe, de [debut] à [fin]. Un capteur n'envoie qu'à chaque changement : sa dernière valeur tient jusqu'à
     * maintenant (prolongée jusqu'à [fin]), et la valeur d'avant la période tient depuis [debut]. Sans valeur d'avant
     * (historique plus court : capteur récent, Home Assistant redémarré), la courbe commence à la première mesure.
     * Rend la fenêtre de temps (début, fin) et les points : la courbe occupe toujours toute la largeur.
     */
    /** Le côté (px) des boutons − / + d'un widget de [largeur] × [hauteur] px ; [unDp] : 1 dp en px. */
    fun coteBouton(largeur: Float, hauteur: Float, unDp: Float): Float =
        minOf(hauteur * 0.42f, largeur * 0.25f).coerceIn(30f * unDp, 48f * unDp)

    fun courbe(points: List<Point>, debut: Long, fin: Long): Pair<Pair<Long, Long>, List<Point>> {
        val tries = points.sortedBy { it.ts }
        val avant = tries.lastOrNull { it.ts <= debut }
        val dedans = tries.filter { it.ts > debut && it.ts <= fin }
        val l = ArrayList<Point>()
        if (avant != null) l.add(Point(debut, avant.v))
        l.addAll(dedans)
        if (l.isEmpty()) return (debut to fin) to emptyList()
        if (l.last().ts < fin) l.add(Point(fin, l.last().v))
        val depart = l.first().ts
        // Une seule mesure, juste maintenant : un trait plat sur toute la largeur plutôt qu'un point.
        return (if (depart >= fin) (debut to fin) else (depart to fin)) to (if (l.size == 1) listOf(Point(debut, l[0].v), Point(fin, l[0].v)) else l)
    }

    /** Les barres : une par heure (24 h) ou par jour (7 j), moyenne des points ; null là où il n'y a rien. */
    fun barres(points: List<Point>, periode: String, finMs: Long): List<Double?> {
        val n = if (periode == "7j") 7 else 24
        val duree = if (periode == "7j") 86_400_000L else 3_600_000L
        val debut = finMs - n * duree
        val s = DoubleArray(n); val c = IntArray(n)
        for (p in points) {
            if (p.ts < debut || p.ts > finMs) continue
            val k = ((p.ts - debut) / duree).toInt().coerceIn(0, n - 1)
            s[k] += p.v; c[k]++
        }
        return (0 until n).map { if (c[it] == 0) null else s[it] / c[it] }
    }

    /** L'échelle verticale d'un graphique : min et max des valeurs, avec un peu d'air (jamais plate). */
    fun echelle(valeurs: List<Double>): Pair<Double, Double> {
        if (valeurs.isEmpty()) return 0.0 to 1.0
        val mi = valeurs.min(); val ma = valeurs.max()
        if (ma - mi < 1e-9) return (mi - 1) to (ma + 1)
        val air = (ma - mi) * 0.08
        return (mi - air) to (ma + air)
    }

    /** Les segments d'états ramenés à la période (fractions 0–1), pour une bande colorée. */
    fun bandes(segments: List<Segment>, debutMs: Long, finMs: Long): List<Triple<Float, Float, String>> {
        val total = (finMs - debutMs).toFloat().coerceAtLeast(1f)
        return segments.mapNotNull { s ->
            val d = s.debut.coerceIn(debutMs, finMs); val f = s.fin.coerceIn(debutMs, finMs)
            if (f <= d) null else Triple((d - debutMs) / total, (f - debutMs) / total, s.etat)
        }
    }

    fun dureePeriode(periode: String): Long = if (periode == "7j") 7 * 86_400_000L else 86_400_000L

    // ------------------------------------------------------------------ flux

    /** Délai avant de se reconnecter au flux : 2 s, 4 s, 8 s… jusqu'à 2 min. */
    fun delaiReconnexion(essai: Int): Long = min(120_000L, 2_000L * (1L shl essai.coerceIn(0, 10)))

    /** Un événement du flux : son type (`message` par défaut, `fin` quand le serveur ferme) et ses données. */
    data class EvenementSse(val type: String, val donnees: String)

    /**
     * Lit un flux SSE ligne à ligne : rend l'événement (champ `event:`, lignes `data:` jointes) à la ligne vide qui
     * le termine. Les commentaires (`: battement`, toutes les 25 s) et les champs id / retry sont ignorés.
     * `event: fin` : le serveur ferme parce que les droits ont changé (module, rôle, secret, appareil retiré).
     */
    class LecteurSse {
        private val donnees = StringBuilder()
        private var type = ""
        fun ligne(l: String): EvenementSse? {
            val s = l.trimEnd('\r')
            if (s.isEmpty()) {
                if (donnees.isEmpty() && type.isEmpty()) return null
                val e = EvenementSse(type.ifEmpty { "message" }, donnees.toString())
                donnees.setLength(0); type = ""
                return e
            }
            if (s.startsWith(":")) return null
            if (s.startsWith("event:")) type = s.removePrefix("event:").trim()
            else if (s.startsWith("data:")) { if (donnees.isNotEmpty()) donnees.append('\n'); donnees.append(s.removePrefix("data:").removePrefix(" ")) }
            return null
        }
    }

    // ------------------------------------------------------------------ relevé

    /** La version des widgets annoncée par le relevé : {"widgets": {"version": …}} ou "widgets_version". Vide si absente. */
    fun versionReleve(widgets: Any?, aPlat: Any?): String = when {
        widgets is Map<*, *> && widgets["version"] != null -> widgets["version"].let { if (it is Double) nombreTexte(it) else it.toString() }
        aPlat != null && aPlat.toString().isNotEmpty() -> aPlat.toString()
        else -> ""
    }

    // ------------------------------------------------------------------ données d'exemple (aperçu hors ligne)

    /** Une entité d'exemple pour un style (aperçu sans Home Assistant). */
    fun exemple(style: String): Entite = when (style) {
        BOUTON -> Entite("script.exemple", Tr.s(R.string.wha_ex_scene), "script", "", "", "", "off", emptyMap(), listOf("turn_on"), emptyList())
        INTERRUPTEUR -> Entite("light.exemple", Tr.s(R.string.wha_ex_lampe), "light", "", "", "", "on", emptyMap(), listOf("toggle"), emptyList())
        JAUGE -> Entite("sensor.exemple", Tr.s(R.string.wha_ex_humidite), "sensor", "", "%", "humidity", "57", emptyMap(), emptyList(), emptyList())
        METEO -> Entite("weather.exemple", Tr.s(R.string.wha_style_meteo), "weather", "", "°C", "", "partlycloudy", mapOf("temperature" to 18.0, "forecast" to listOf(
            mapOf("jour" to Tr.s(R.string.wha_ex_demain), "etat" to "rainy", "max" to 16.0, "min" to 9.0))), emptyList(), emptyList())
        LECTEUR -> Entite("media_player.exemple", Tr.s(R.string.theme_salon), "media_player", "", "", "", "playing", mapOf("media_title" to Tr.s(R.string.wha_ex_titre),
            "media_artist" to Tr.s(R.string.party_type_artiste), "volume_level" to 0.4), listOf("media_play_pause", "media_next_track", "media_previous_track", "volume_set"), emptyList())
        OUVERTURE -> Entite("binary_sensor.exemple", Tr.s(R.string.wha_ex_porte), "binary_sensor", "", "", "door", "off", emptyMap(), emptyList(), emptyList())
        else -> Entite("sensor.exemple", Tr.s(R.string.wha_ex_temperature), "sensor", "", "°C", "temperature", "21.5", emptyMap(), emptyList(), emptyList())
    }

    /** Un historique d'exemple (une journée douce), pour l'aperçu hors ligne. */
    fun historiqueExemple(finMs: Long, periode: String): Historique.Nombres {
        val n = 48; val duree = dureePeriode(periode)
        return Historique.Nombres("°C", (0 until n).map { k ->
            val t = finMs - duree + duree * k / (n - 1)
            Point(t, 20.0 + 2.5 * Math.sin(k / (n - 1.0) * Math.PI * 2 - 1.2) + (k % 5) * 0.08)
        })
    }
}
