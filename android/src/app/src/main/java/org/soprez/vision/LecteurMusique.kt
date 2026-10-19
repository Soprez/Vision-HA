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

    /**
     * 1.93.06.1 (TV du salon, 19/10) : avant de lancer la soirée depuis cet appareil, son lecteur doit être relié à Music
     * Assistant (Home Assistant l'attend 8 s, puis part sur une autre enceinte). Une liaison muette est refermée et
     * rouverte ; on attend au plus [maxMs]. Hors du fil principal. Moteur Sendspin : il se reconnecte seul, on ne fait
     * que dire s'il est relié.
     */
    fun assurerLiaison(ctx: Context, maxMs: Long = 8_000L): Boolean {
        if (moteur(ctx) == SENDSPIN) return LecteurSendspin.connecte
        if (!LecteurVision.actif(ctx) || !ReglagesTvActivity.estTele(ctx)) return false
        if (!LecteurSlim.enMarche) LecteurSlim.demarrer(ctx)
        if (LecteurSlim.verifierLiaison("lancement de la soirée")) return true
        return LecteurSlim.attendreLiaison(maxMs)
    }

    /** « lecture », « pause » ou « arrêt » (celui du moteur qui tourne). */
    val etat: String get() = if (LecteurSendspin.actif) LecteurSendspin.etat else LecteurSlim.etat

    val connecte: Boolean get() = if (LecteurSendspin.actif) LecteurSendspin.connecte else LecteurSlim.connecte

    /** La position (s) du titre que joue ce lecteur quand il est celui de la Party ; null sinon. */
    fun positionLocale(ctx: Context, ici: Boolean, lecteurNom: String): Double? =
        if (LecteurSendspin.actif) LecteurSendspin.positionLocale(ctx, ici, lecteurNom) else LecteurSlim.positionLocale(ctx, ici, lecteurNom)

    /**
     * 1.91.0 : un saut dans le titre, à [quand] (elapsedRealtime). Seul le lecteur Classique en a besoin (il repart de
     * zéro à chaque flux) ; Sendspin reçoit la nouvelle position de Music Assistant. Rien si cet appareil n'est pas le
     * lecteur de la Party.
     */
    fun saut(ctx: Context, ici: Boolean, lecteurNom: String, positionS: Double, quand: Long) {
        if (LecteurSendspin.actif) return
        LecteurSlim.saut(ctx, ici, lecteurNom, positionS, quand)
    }

    /** Le nom affiché dans Music Assistant : celui de l'appareil, avec « · Vision » (les deux moteurs le partagent). */
    fun nom(ctx: Context): String = LecteurSlim.nom(ctx)
}
