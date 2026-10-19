// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context

/**
 * 1.93.06 (l'auteur : « un catalogue unique pour le mobile et la télé ») : les widgets de Vision qu'on peut poser sur CET
 * appareil, et la règle qui les choisit, en un seul endroit. Avant, la même règle était écrite trois fois (panneau du
 * téléphone, rangée de la télé, zones de la disposition « Vertical » : ETUDE-widgets-cartes-ha, § 1) et pouvait dériver.
 *
 * La règle : le widget est pour ce profil (ou pour tous), son module est actif, et le lieu a ses données (liste envoyée
 * par Home Assistant ; inconnue : on le propose). Les widgets de Home Assistant, eux, viennent de Home Assistant
 * (partagés avec l'appareil : WidgetsHaStock), les mêmes sur le téléphone et sur la télé.
 */
object CatalogueWidgets {
    /** Pur : ce widget est-il posable ? */
    fun permet(code: String, publics: Set<String>, moduleVisible: Boolean, profil: String, donnees: Set<String>?): Boolean =
        moduleVisible && (publics.isEmpty() || profil in publics) && Accueil.disponible(code, donnees)

    /** Pur : les widgets posables parmi [widgets], dans l'ordre du catalogue. */
    fun permis(widgets: List<Accueil.Widget>, profil: String, moduleVisible: (String) -> Boolean, donnees: Set<String>?): List<Accueil.Widget> =
        widgets.filter { permet(it.code, it.publics, moduleVisible(it.code), profil, donnees) }

    /** Les widgets de Vision posables ici (panneau du téléphone, « + » de la télé, zones de la télé). */
    fun vision(ctx: Context, profil: String): List<Accueil.Widget> {
        val actifs = Modules.actifs(ctx)
        return permis(Accueil.WIDGETS, profil, { Modules.widgetVisible(actifs, it) }, Accueil.disponibles(ctx))
    }
}
