// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.content.Intent

/**
 * Les demandes d'un appareil de Demandeur (du temps, un déverrouillage), communes à l'écran « Moi » du téléphone
 * (SetupActivity) et, depuis la 1.93.05 (l'auteur, retour 33), aux réglages de la télé — avant, sur la télé, on ne les
 * trouvait que dans l'ancien menu de style téléphone.
 */
object DemandesAppareil {
    /** Demander du temps : les mêmes durées que l'écran de blocage et l'accueil ; [apres] une fois la demande partie. */
    fun demanderTemps(act: Activity, apres: () -> Unit = {}) {
        val cfg = Config(act)
        val choix = Durees.DEMANDE.zip(Durees.libelles(Durees.DEMANDE))
        Ui.dialogue(act).setTitle(R.string.setup_demander_temps)
            .setItems(choix.map { it.second }.toTypedArray()) { _, k ->
                cfg.demandeTemps = choix[k].first
                SetupActivity.demandeTempsA = System.currentTimeMillis()
                AgentService.demarrer(act)
                Ui.dialogue(act).setTitle(R.string.deva_envoyee)
                    .setMessage(act.getString(R.string.setup_demande_temps_envoyee, Vocabulaire.decideur(act)))
                    .setPositiveButton(R.string.commun_ok, null).show()
                apres()
            }.setNegativeButton(R.string.commun_annuler, null).show()
    }

    /** Demander un déverrouillage : une appli ou un site fermé, ou tout l'appareil (si le module le permet). */
    fun demanderDeverrouillage(act: Activity) {
        val nb = Etat.recentsBloques().size
        if (!Modules.actifDans(Modules.actifs(act), Modules.DEVERROUILLAGE)) { act.startActivity(Intent(act, FermesActivity::class.java)); return }
        Ui.dialogue(act).setTitle(R.string.deva_titre)
            .setItems(arrayOf(if (nb > 0) act.getString(R.string.setup_appli_site_ferme_n, nb) else act.getString(R.string.setup_appli_site_ferme),
                act.getString(R.string.setup_tout_appareil))) { _, k ->
                if (k == 0) act.startActivity(Intent(act, FermesActivity::class.java)) else DeverrouillageActivity.ouvrir(act, "parent")
            }.setNegativeButton(R.string.commun_annuler, null).show()
    }

    /** Ces demandes ont-elles un sens ici ? (pas sur un appareil de Décideur ; module « demandes » actif.) */
    fun proposees(act: Activity): Boolean = !Config(act).modeParent && Modules.actif(act, Modules.DEMANDES)
}
