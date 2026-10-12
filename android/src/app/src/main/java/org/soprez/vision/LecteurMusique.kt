// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context

/**
 * Le lecteur de Music Assistant de cet appareil, quel que soit son moteur (GO de l'auteur pour le client Sendspin natif,
 * 2026-10-12) : l'ancien [LecteurSlim] (imitation Squeezelite) ou le nouveau [LecteurSendspin] (protocole officiel de
 * MA). Un interrupteur choisit le moteur, rangé sous « Décideurs 🔒 » dans les réglages de la télé ; l'ancien reste le
 * défaut jusqu'à la bascule. Tout Vision passe par ici : service, veille, Party, et l'arrêt automatique après inactivité.
 */
object LecteurMusique {
    const val SLIM = "slim"
    const val SENDSPIN = "sendspin"

    /**
     * Le moteur d'un appareil qui n'a rien choisi. La bascule (après validation de l'auteur sur les vraies télés) tient en
     * cette ligne : SENDSPIN. Ensuite seulement : retrait de LecteurSlim et FluxUnique, puis, avec l'accord de l'auteur,
     * désactivation des « legacy clients » (Squeezelite) dans Music Assistant.
     */
    const val DEFAUT = SLIM

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("lecteur", 0)

    fun moteur(ctx: Context): String = when (prefs(ctx).getString("moteur", DEFAUT)) { SENDSPIN -> SENDSPIN; SLIM -> SLIM; else -> DEFAUT }

    /** Change de moteur : l'un s'arrête avant que l'autre parte (jamais deux lecteurs à la fois pour une télé). */
    fun poserMoteur(ctx: Context, m: String) {
        if (m == moteur(ctx)) return
        arreter()
        prefs(ctx).edit().putString("moteur", if (m == SENDSPIN) SENDSPIN else SLIM).apply()
        if (LecteurVision.actif(ctx) && ReglagesTvActivity.estTele(ctx)) demarrer(ctx)
    }

    fun demarrer(ctx: Context) {
        if (moteur(ctx) == SENDSPIN) { LecteurSlim.arreter(); LecteurSendspin.demarrer(ctx) }
        else { LecteurSendspin.arreter(); LecteurSlim.demarrer(ctx) }
    }

    fun arreter() { LecteurSlim.arreter(); LecteurSendspin.arreter() }

    /** « lecture », « pause » ou « arrêt » (celui du moteur qui tourne). */
    val etat: String get() = if (LecteurSendspin.actif) LecteurSendspin.etat else LecteurSlim.etat

    val connecte: Boolean get() = if (LecteurSendspin.actif) LecteurSendspin.connecte else LecteurSlim.connecte

    /** La position (s) du titre que joue ce lecteur quand il est celui de la Party ; null sinon. */
    fun positionLocale(ctx: Context, ici: Boolean, lecteurNom: String): Double? =
        if (LecteurSendspin.actif) LecteurSendspin.positionLocale(ctx, ici, lecteurNom) else LecteurSlim.positionLocale(ctx, ici, lecteurNom)

    /** Le nom affiché dans Music Assistant : celui de l'appareil, avec « · Vision » (les deux moteurs le partagent). */
    fun nom(ctx: Context): String = LecteurSlim.nom(ctx)
}
