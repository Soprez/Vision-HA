// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/**
 * Vision comme appli d'accueil par défaut (point 7 du backlog, Vision 1.63.0), téléphones et tablettes.
 *
 * Android 10+ : la boîte officielle du système (RoleManager, rôle HOME) ne nomme que Vision, sans
 * confusion possible avec une autre appli du même nom (l'ancienne Vision, encore installée, apparaît
 * elle aussi « Vision » dans la liste des Réglages). Avant Android 10 : l'écran « Appli d'accueil ».
 * Pendant ce choix, la protection des réglages laisse passer l'écran du système (fenêtre de 60 s).
 */
object AccueilDefaut {
    const val REQ = 4201
    @Volatile private var demandeA = 0L

    /** Vrai si Vision est l'accueil choisi (et pas seulement l'un des candidats). */
    fun estAccueil(ctx: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = ctx.getSystemService(RoleManager::class.java)
            rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && rm.isRoleHeld(RoleManager.ROLE_HOME)
        } else {
            ctx.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                ?.activityInfo?.packageName == ctx.packageName
        }
    } catch (_: Exception) { false }

    fun possible(ctx: Context): Boolean = !ReglagesTvActivity.estTele(ctx) && (Build.VERSION.SDK_INT >= 29 ||
        Intent(Settings.ACTION_HOME_SETTINGS).resolveActivity(ctx.packageManager) != null)

    /** La protection des réglages laisse passer l'écran de choix pendant une minute. */
    fun demandeEnCours(): Boolean = System.currentTimeMillis() - demandeA < 60_000

    /** L'ancienne Vision (ancien identifiant, Migration.ANCIEN) : Android la montre aussi « Vision » dans le choix de l'accueil. */
    fun ancienPresent(ctx: Context): Boolean = try { ctx.packageManager.getPackageInfo(Migration.ANCIEN, 0); true } catch (_: Exception) { false }

    /** Avec l'ancienne Vision encore là, deux « Vision » identiques dans la liste d'Android : la choisir ne marche pas. */
    fun demander(act: Activity) {
        if (!ancienPresent(act)) return lancer(act)
        Ui.dialogue(act)
            .setTitle(act.getString(R.string.accueildefaut_ancienne_titre))
            .setMessage(act.getString(R.string.accueildefaut_ancienne_texte))
            .setPositiveButton(act.getString(R.string.accueildefaut_desinstaller_ancienne)) { _, _ ->
                // Un message long : une boîte qu'on ferme soi-même (un toast serait coupé en grands caractères).
                if (!Migration.supprimerAncienne(act))
                    Ui.dialogue(act).setTitle(act.getString(R.string.accueildefaut_a_la_main_titre))
                        .setMessage(act.getString(R.string.accueildefaut_a_la_main_texte))
                        .setPositiveButton(act.getString(R.string.commun_lot2_ok), null).show()
            }
            .setNeutralButton(act.getString(R.string.accueildefaut_continuer)) { _, _ -> lancer(act) }
            .setNegativeButton(act.getString(R.string.commun_lot2_annuler), null).show()
    }

    /**
     * Module « accueil » coupé alors que Vision est l'accueil : l'écran du système pour en choisir un autre
     * (l'accueil d'origine). Android ne laisse pas une appli rendre ce rôle d'elle-même.
     */
    fun rendre(act: Activity) {
        demandeA = System.currentTimeMillis()
        for (action in listOf(Settings.ACTION_HOME_SETTINGS, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, Settings.ACTION_SETTINGS)) {
            try { act.startActivity(Intent(action)); return } catch (_: Exception) {}
        }
    }

    // ---------------------------------------------------------------- lot accueil, point 8 : l'accueil d'origine

    /** Textes affichés (regroupés pour l'extraction i18n). */
    val RAISON_ORIGINE: String get() = Tr.s(R.string.accueil_raison_origine)
    val RAISON_RENDRE: String get() = Tr.s(R.string.accueil_raison_rendre)
    val RAISON_FORCE: String get() = Tr.s(R.string.accueil_raison_force)

    /**
     * Logique pure. Ouvrir, rendre ou laisser l'accueil d'origine demande le code d'un décideur tant que Vision
     * tient l'accueil (télé : « Vision à la place de l'accueil » ; téléphone : Vision est l'accueil choisi) ET que
     * le module « accueil » est actif dans Home Assistant. Exceptions, sans code :
     *  - module « accueil » coupé dans HA : la décision est déjà prise par un décideur (Vision ne doit plus être
     *    l'accueil) ; exiger un code enfermerait l'appareil dans Vision contre cette décision ;
     *  - Vision ne tient pas l'accueil : la touche Accueil mène déjà à l'accueil d'origine, un code n'y
     *    protégerait rien (et cesser de le tenir, lui, demande le code : voir [basculerForce]).
     */
    fun codePourOrigine(moduleAccueil: Boolean, visionTientAccueil: Boolean): Boolean = moduleAccueil && visionTientAccueil

    private fun visionTientAccueil(ctx: Context): Boolean =
        if (ReglagesTvActivity.estTele(ctx)) Local.accueilForce(ctx) else estAccueil(ctx)

    private fun protege(act: Activity, raison: String, ok: () -> Unit) =
        if (codePourOrigine(Modules.actif(act, Modules.ACCUEIL), visionTientAccueil(act))) Garde.exiger(act, raison) { ok() } else ok()

    /**
     * Après le code, l'accueil d'origine reste devant : le service d'accessibilité ne ramène pas Vision quand il
     * paraît (10 min au plus, fini dès que Vision repasse devant ou à la touche Accueil). Sans code, il la ramène.
     */
    @Volatile private var origineJusqua = 0L
    fun origineLaissee(): Boolean = android.os.SystemClock.elapsedRealtime() < origineJusqua
    fun finOrigine() { origineJusqua = 0L }
    @Volatile private var origineA = 0L
    fun depuisOrigine(): Long = android.os.SystemClock.elapsedRealtime() - origineA

    /** « Accueil d'origine » (réglages de la télé) : l'ouvrir, code d'abord (voir [codePourOrigine]). */
    fun ouvrirOrigine(act: Activity) = protege(act, RAISON_ORIGINE) {
        val i = Accueil.accueilSysteme(act) ?: return@protege
        origineA = android.os.SystemClock.elapsedRealtime(); origineJusqua = origineA + 10 * 60_000L
        try { act.startActivity(i) } catch (_: Exception) { finOrigine() }
    }

    /** « Rendre l'accueil d'origine » (téléphone, module coupé) : passe par la même règle (libre si le module est coupé). */
    fun rendreOrigine(act: Activity) = protege(act, RAISON_RENDRE) { rendre(act) }

    /** « Vision à la place de l'accueil » (télé) : l'activer est libre, la couper laisse l'accueil d'origine : code. */
    fun basculerForce(act: Activity, apres: () -> Unit) {
        if (!Local.accueilForce(act)) { Local.poserAccueilForce(act, true); apres(); return }
        protege(act, RAISON_FORCE) { Local.poserAccueilForce(act, false); apres() }
    }

    private fun lancer(act: Activity) {
        demandeA = System.currentTimeMillis()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val rm = act.getSystemService(RoleManager::class.java)
                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                    @Suppress("DEPRECATION") act.startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), REQ)
                    return
                }
            }
            act.startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (_: Exception) {
            try { act.startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) } catch (_: Exception) {}
        }
    }
}
