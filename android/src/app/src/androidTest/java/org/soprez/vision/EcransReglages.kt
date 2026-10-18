// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.content.Intent

/**
 * 15-10 (l'auteur : « Réglages de l'appareil » presque obsolète hors télé) : sur le téléphone et la tablette, ReglagesTvActivity
 * ouverte sans section renvoie aux Réglages de l'appli et se ferme. Les essais passent par ici :
 * - [reglages] : l'écran des réglages de cet appareil (la page télé, sinon l'espace Réglages de l'appli) ;
 * - [hote] : un écran qui reste ouvert, pour les essais qui n'en regardent pas le contenu (fenêtres, réactions de l'œil).
 */
object EcransReglages {
    fun reglages(ctx: Context): Intent =
        if (ReglagesTvActivity.estTele(ctx)) Intent(ctx, ReglagesTvActivity::class.java)
        else Intent(ctx, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_ESPACE, Espaces.REGLAGES)

    fun hote(ctx: Context): Intent =
        if (ReglagesTvActivity.estTele(ctx)) Intent(ctx, ReglagesTvActivity::class.java)
        else Intent(ctx, ReglagesTvActivity::class.java).putExtra(ReglagesTvActivity.EXTRA_SECTION, ReglagesMusique.SECTION)
}
