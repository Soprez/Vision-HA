// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.content.Context
import org.json.JSONObject

/**
 * 1.93.06.1 (l'auteur, tablettes utilisées comme tableau de bord) : « Afficher la liste des applications », par appareil.
 *
 * - Activée par défaut. Désactivée : l'accueil ne montre que les widgets, qui prennent toute la place (téléphone et
 *   tablette : ni dock, ni poignée, ni tiroir ; télé : ni colonne ni rangée d'applis).
 * - Réglable sur l'appareil (Réglages) et depuis Home Assistant (`pc_parental.accueil_regler`, bloc « accueil_options »
 *   du relevé). Comme pour le focus audio : le dernier choix gagne ; un réglage de HA ne s'applique que s'il est plus
 *   récent que le dernier qu'il a envoyé (« depuis »).
 * - Jamais enfermé dehors : la liste n'est masquée que si l'accueil montre au moins un widget ([masquee]) ; les
 *   paramètres restent atteignables par un appui long sur l'heure (ou l'en-tête), derrière le code ou l'empreinte.
 */
object AffichageApplis {
    private const val PREFS = "accueil_options"
    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Le réglage : la liste des applications est-elle affichée ? (vrai par défaut) */
    fun liste(ctx: Context): Boolean = p(ctx).getBoolean("liste_applis", true)

    /** Le choix fait sur l'appareil. */
    fun poser(ctx: Context, afficher: Boolean) { p(ctx).edit().putBoolean("liste_applis", afficher).apply(); Local.toucher() }

    /**
     * 1.93.06.1 (retour d'essai) : masquer la liste ne s'applique qu'après une confirmation explicite, qui dit comment revenir aux
     * paramètres (« J'ai compris ») ; fermer la fenêtre annule. Réafficher la liste ne demande rien.
     */
    fun changer(act: Activity, tele: Boolean, fait: () -> Unit) {
        if (!liste(act)) { poser(act, true); fait(); return }
        Ui.dialogue(act).setTitle(R.string.listeapplis_titre)
            .setMessage(if (tele) R.string.listeapplis_confirmation_tele else R.string.listeapplis_confirmation)
            .setPositiveButton(R.string.listeapplis_compris) { _, _ -> poser(act, false); fait() }
            .setNegativeButton(R.string.commun_annuler, null)
            .show()
    }

    /** Pur : la liste est-elle masquée, compte tenu des widgets visibles ? Sans widget, elle reste : jamais un accueil vide. */
    fun masquee(liste: Boolean, widgetsVisibles: Int): Boolean = !liste && widgetsVisibles > 0

    /** Le bloc « accueil_options » du relevé ; rend vrai si le réglage a changé. Bloc absent (HA plus ancien) : rien. */
    fun releve(ctx: Context, o: JSONObject?): Boolean {
        if (o == null || !o.has("liste_applis")) return false
        val depuis = o.optLong("depuis", 0L)
        if (depuis <= 0L || depuis <= p(ctx).getLong("depuis_ha", 0L)) return false
        val avant = liste(ctx)
        p(ctx).edit().putLong("depuis_ha", depuis).putBoolean("liste_applis", o.optBoolean("liste_applis", true)).apply()
        Local.toucher()
        return avant != liste(ctx)
    }

    /** Pur : le nombre de lignes qu'occupent [widgets] une fois les lignes vides retirées. */
    fun lignes(widgets: List<Accueil.Place>): Int = sansLignesVides(widgets).maxOfOrNull { it.row + it.h } ?: 0

    /**
     * Pur : la grille du téléphone ou de la tablette sans les applis. Les widgets gardent leur colonne ; les lignes
     * restées vides (où il n'y avait que des applis) sont retirées, À L'ÉCRAN seulement : la disposition enregistrée ne
     * change pas (elle revient telle quelle quand la liste est réaffichée).
     */
    fun sansLignesVides(places: List<Accueil.Place>): List<Accueil.Place> {
        val occupees = places.flatMap { pl -> pl.row until pl.row + pl.h }.toSortedSet()
        if (occupees.isEmpty()) return places
        val nouvelle = HashMap<Int, Int>()
        occupees.forEachIndexed { i, r -> nouvelle[r] = i }
        return places.map { pl -> Accueil.Place(pl.cle, pl.col, nouvelle.getValue(pl.row), pl.w, pl.h) }
    }
}
