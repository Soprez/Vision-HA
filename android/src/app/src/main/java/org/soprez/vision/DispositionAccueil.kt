// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import org.json.JSONArray

/**
 * Les réglages de disposition de l'accueil (l'auteur, 13 octobre), à côté du mode Grille / Pages d'[Accueil] (préférences
 * « lanceur ») :
 * - téléphone : la place de la zone du haut (au-dessus ou en dessous des icônes ; vide, elle ne prend aucune place) ;
 * - téléphone, mode « Liste alphabétique » ([Accueil.MODE_ALPHA]) : côté de l'index (droite par défaut, gauche pour
 *   les gauchers) et favoris épinglés en haut (les applis du dock, option activée par défaut) ;
 * - télé : disposition « Rangées » (celle d'avant, par défaut) ou « Vertical » (les applis en colonne), la place de la
 *   colonne (gauche, centre, droite) et les zones de widgets de part et d'autre : une zone n'existe que si le Décideur
 *   y a mis des widgets ET l'a activée.
 * La partie pure (valeurs permises, zones selon la colonne) est essayée en JUnit (DispositionAccueilTest).
 */
object DispositionAccueil {
    private const val PREFS = "lanceur"

    const val ZONE_DESSUS = "dessus"
    const val ZONE_DESSOUS = "dessous"
    const val INDEX_DROITE = "droite"
    const val INDEX_GAUCHE = "gauche"
    const val TELE_RANGEES = "rangees"
    const val TELE_VERTICAL = "vertical"
    const val COLONNE_GAUCHE = "gauche"
    const val COLONNE_CENTRE = "centre"
    const val COLONNE_DROITE = "droite"
    /** Les zones de widgets possibles autour de la colonne de la télé : gauche, droite (selon la colonne), et (16-10,
     *  l'auteur) une rangée en haut et une rangée en bas, sur toute la largeur. */
    const val ZONE_G = "g"
    const val ZONE_D = "d"
    const val ZONE_H = "h"
    const val ZONE_B = "b"
    val RANGEES = listOf(ZONE_H, ZONE_B)

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun lire(ctx: Context, cle: String, defaut: String, permis: List<String>): String =
        (p(ctx).getString(cle, defaut) ?: defaut).takeIf { it in permis } ?: defaut
    private fun ecrire(ctx: Context, cle: String, v: String) { p(ctx).edit().putString(cle, v).apply(); Local.toucher() }

    // ------------------------------------------------------------------ téléphone

    fun positionZone(ctx: Context): String = lire(ctx, "zone_position", ZONE_DESSUS, listOf(ZONE_DESSUS, ZONE_DESSOUS))
    fun poserPositionZone(ctx: Context, v: String) = ecrire(ctx, "zone_position", v)

    fun coteIndex(ctx: Context): String = lire(ctx, "alpha_index", INDEX_DROITE, listOf(INDEX_DROITE, INDEX_GAUCHE))
    fun poserCoteIndex(ctx: Context, v: String) = ecrire(ctx, "alpha_index", v)

    fun favorisEnHaut(ctx: Context): Boolean = p(ctx).getBoolean("alpha_favoris", true)
    fun poserFavorisEnHaut(ctx: Context, v: Boolean) { p(ctx).edit().putBoolean("alpha_favoris", v).apply(); Local.toucher() }

    // ------------------------------------------------------------------ télé

    fun disposTele(ctx: Context): String = lire(ctx, "tele_disposition", TELE_RANGEES, listOf(TELE_RANGEES, TELE_VERTICAL))
    fun poserDisposTele(ctx: Context, v: String) = ecrire(ctx, "tele_disposition", v)

    fun colonne(ctx: Context): String = lire(ctx, "tele_colonne", COLONNE_GAUCHE, listOf(COLONNE_GAUCHE, COLONNE_CENTRE, COLONNE_DROITE))
    fun poserColonne(ctx: Context, v: String) = ecrire(ctx, "tele_colonne", v)

    /** Les zones que laisse la colonne : à sa droite (colonne à gauche), des deux côtés (centre), à sa gauche (droite). */
    fun zonesPossibles(colonne: String): List<String> = when (colonne) {
        COLONNE_GAUCHE -> listOf(ZONE_D)
        COLONNE_DROITE -> listOf(ZONE_G)
        else -> listOf(ZONE_G, ZONE_D)
    }

    /** Toutes les zones que la colonne permet (réglages) : les côtés libres, puis les rangées du haut et du bas. */
    fun zonesTele(colonne: String): List<String> = zonesPossibles(colonne) + RANGEES

    /**
     * Les widgets d'une zone (codes de [ZoneHaut], sans l'accueil d'avant qui occupe toute une bande), et (16-10, l'auteur :
     * « température, consigne, planning… apparaissent dans la liste mais ne sont pas posables ») les widgets que Home
     * Assistant partage avec cet appareil ([ha], codes « ha:<entité> ») — avant, seuls les quatre widgets de Vision.
     */
    fun catalogueZone(ha: List<String> = emptyList()): List<String> = ZoneHaut.catalogue(true).filter { it != ZoneHaut.CLASSIQUE } + ha

    /**
     * Le catalogue d'une zone sur CET appareil : les widgets de la zone du haut, ceux que Home Assistant partage, et
     * (16-10, l'auteur : parité avec le téléphone) les widgets de Vision du téléphone (chauffage, fioul, école, caméra…)
     * permis ici — mêmes filtres que le panneau du téléphone (public, modules, données du lieu).
     */
    fun catalogueZone(ctx: Context): List<String> = catalogueZone(ZoneHaut.partagesHa(ctx)) + widgetsVision(ctx).map { Accueil.PREFIXE_WIDGET + it }

    /** Un widget de Vision du téléphone dans une zone : « widget:<code> » (comme sur la grille du téléphone). */
    fun estWidgetVision(code: String): Boolean = code.startsWith(Accueil.PREFIXE_WIDGET) && Accueil.widget(codeWidgetVision(code)) != null
    fun codeWidgetVision(code: String): String = code.removePrefix(Accueil.PREFIXE_WIDGET)

    /** Les widgets de Vision du téléphone posables ici (sans l'horloge stylisée, déjà au catalogue des zones). */
    fun widgetsVision(ctx: Context): List<String> {
        val actifs = Modules.actifs(ctx)
        val profil = p(ctx).getString("profil", "").orEmpty()
        val donnees = Accueil.disponibles(ctx)
        return widgetsVisionPermis(Accueil.WIDGETS.map { Triple(it.code, it.publics, Modules.widgetVisible(actifs, it.code)) }, profil, donnees)
    }

    /** Pur (essais) : (code, publics, module visible) → les codes permis pour [profil] et les données du lieu [donnees]. */
    fun widgetsVisionPermis(widgets: List<Triple<String, Set<String>, Boolean>>, profil: String, donnees: Set<String>?): List<String> =
        // 1.93.06 : la règle du catalogue commun (CatalogueWidgets), sans l'horloge stylisée (déjà au catalogue des zones).
        widgets.filter { (code, publics, visible) -> code != ZoneHaut.HORLOGE && CatalogueWidgets.permet(code, publics, visible, profil, donnees) }
            .map { it.first }

    /**
     * Ce qu'une zone garde : les widgets de la zone du haut, ceux de Home Assistant (même si le catalogue n'est pas encore
     * là : simplement pas montrés) et ceux de Vision du téléphone connus de cette version.
     */
    fun nettoyerZone(l: List<String>): List<String> = l.filter { it in catalogueZone() || ZoneHaut.estHa(it) || estWidgetVision(it) }.distinct()

    fun widgetsZone(ctx: Context, zone: String): List<String> = try {
        JSONArray(p(ctx).getString("tele_zone_$zone", "[]")).let { a -> nettoyerZone((0 until a.length()).map { a.getString(it) }) }
    } catch (_: Exception) { emptyList() }

    fun poserWidgetsZone(ctx: Context, zone: String, l: List<String>) {
        p(ctx).edit().putString("tele_zone_$zone", JSONArray(nettoyerZone(l)).toString()).apply(); Local.toucher()
    }

    fun zoneActivee(ctx: Context, zone: String): Boolean = p(ctx).getBoolean("tele_zone_${zone}_active", false)

    /**
     * 16-10 (l'auteur, télé du salon) : en disposition « Vertical », chaque appli est sur une carte (fond et bord). Sans fond :
     * le disque et le nom seuls ; au focus, l'anneau d'or reste. Par défaut, comme avant (avec fond).
     */
    fun fondApplis(ctx: Context): Boolean = p(ctx).getBoolean("tele_fond_applis", true)
    fun poserFondApplis(ctx: Context, v: Boolean) { p(ctx).edit().putBoolean("tele_fond_applis", v).apply(); Local.toucher() }

    /** 16-10 (retour d'essai) : le cadre des widgets des zones (horloge stylisée…), pour tous ; par défaut, comme avant (avec). */
    fun cadreWidgets(ctx: Context): Boolean = p(ctx).getBoolean("tele_cadre_widgets", true)
    fun poserCadreWidgets(ctx: Context, v: Boolean) { p(ctx).edit().putBoolean("tele_cadre_widgets", v).apply(); Local.toucher() }
    fun poserZoneActivee(ctx: Context, zone: String, v: Boolean) { p(ctx).edit().putBoolean("tele_zone_${zone}_active", v).apply(); Local.toucher() }

    /** Une zone se montre seulement si elle a des widgets ET si le Décideur l'a activée. */
    fun zoneVisible(widgets: List<String>, activee: Boolean): Boolean = activee && widgets.isNotEmpty()

    /** L'empreinte de ces réglages (le lanceur se rebâtit quand elle change). */
    fun empreinte(ctx: Context): String = listOf(positionZone(ctx), coteIndex(ctx), favorisEnHaut(ctx), disposTele(ctx), colonne(ctx),
        widgetsZone(ctx, ZONE_G), zoneActivee(ctx, ZONE_G), widgetsZone(ctx, ZONE_D), zoneActivee(ctx, ZONE_D),
        widgetsZone(ctx, ZONE_H), zoneActivee(ctx, ZONE_H), widgetsZone(ctx, ZONE_B), zoneActivee(ctx, ZONE_B),
        fondApplis(ctx), cadreWidgets(ctx), ZoneHaut.widgets(ctx),
        // 17-10 (point 12) : la zone sûre réglée (« Ajuster l'écran ») rebâtit l'accueil.
        ZoneSure.pourcentX(ctx), ZoneSure.pourcentY(ctx)).joinToString("|")
}

// ---------------------------------------------------------------------- réglages à l'écran

/** Le nom d'une disposition du téléphone, pour les réglages. */
fun DispositionAccueil.nomMode(ctx: Context, mode: String): String = ctx.getString(when (mode) {
    Accueil.MODE_PAGES -> R.string.reglagestv_pages
    Accueil.MODE_ALPHA -> R.string.lanceur_liste_alpha
    else -> R.string.reglagestv_grille
})

/** Le choix de la disposition du téléphone : Grille, Pages ou Liste alphabétique. */
fun DispositionAccueil.choisirDispositionTelephone(a: android.app.Activity, apres: () -> Unit) {
    val modes = listOf(Accueil.MODE_DEFILEMENT, Accueil.MODE_PAGES, Accueil.MODE_ALPHA)
    Ui.dialogue(a).setTitle(a.getString(R.string.reglagestv_disposition))
        .setSingleChoiceItems(modes.map { nomMode(a, it) }.toTypedArray(), modes.indexOf(Accueil.mode(a)).coerceAtLeast(0)) { d, i ->
            d.dismiss(); Accueil.poserMode(a, modes[i]); Local.toucher(); apres()
        }.show()
}

fun DispositionAccueil.nomZone(ctx: Context, z: String): String = ctx.getString(when (z) {
    DispositionAccueil.ZONE_G -> R.string.lanceur_zone_gauche
    DispositionAccueil.ZONE_H -> R.string.lanceur_zone_haut
    DispositionAccueil.ZONE_B -> R.string.lanceur_zone_bas
    else -> R.string.lanceur_zone_droite
})

/** « Horloge stylisée, Météo · activée », « Vide », « Météo · désactivée ». */
fun DispositionAccueil.resumeZone(ctx: Context, z: String): String {
    val w = widgetsZone(ctx, z)
    if (w.isEmpty()) return ctx.getString(R.string.zonehaut_mode_vide)
    return w.joinToString(", ") { ZoneHaut.nom(ctx, it) } + " · " +
        ctx.getString(if (zoneActivee(ctx, z)) R.string.lanceur_zone_activee else R.string.lanceur_zone_desactivee)
}

/**
 * Une zone de widgets de la télé : choisir et ordonner ses widgets, l'activer ou la désactiver.
 * 16-10 (retour d'essai) :
 * - plus de code des Décideurs : on ne pose ici que des widgets de Vision ou ceux que Home Assistant a déjà partagés
 *   avec cet appareil (décidé dans le panneau) — rien à protéger de plus, le code n'était qu'une gêne ;
 * - une zone vide s'ouvre directement sur le choix de ses widgets (avant : « Activer cette zone » sur une zone vide
 *   n'affichait rien — il n'y avait rien à choisir) ; des widgets posés allument la zone.
 */
fun DispositionAccueil.personnaliserZone(a: android.app.Activity, z: String, apres: () -> Unit) {
    val composer = {
        ZoneHaut.composerListe(a, nomZone(a, z), widgetsZone(a, z), catalogueZone(a)) { l ->
            poserWidgetsZone(a, z, l)
            if (l.isNotEmpty() && !zoneActivee(a, z)) poserZoneActivee(a, z, true)
            apres()
        }
    }
    if (widgetsZone(a, z).isEmpty()) { composer(); return }
    val active = zoneActivee(a, z)
    val choix = arrayOf(a.getString(R.string.lanceur_zone_widgets),
        a.getString(if (active) R.string.lanceur_zone_desactiver else R.string.lanceur_zone_activer))
    Ui.dialogue(a).setTitle(nomZone(a, z)).setItems(choix) { _, k ->
        if (k == 0) composer() else { poserZoneActivee(a, z, !active); apres() }
    }.setNegativeButton(a.getString(R.string.commun_lot2_annuler), null).show()
}
