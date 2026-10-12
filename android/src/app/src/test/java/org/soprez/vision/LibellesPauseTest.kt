// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.junit.Assert.fail
import org.junit.Test

/**
 * Refonte UX, lot 1 (APP-09) : « pause » ne désigne plus deux gestes opposés.
 *  - la pause générale (fermer tous les appareils) s'appelle « Tout fermer », et sa fin « Rouvrir » ;
 *  - la pause de Vision (lever la protection) s'appelle « Suspendre la protection » ;
 *  - la notification de déverrouillage ne parle plus de pause.
 * Dans les six langues. Les clés (et leurs noms en `pause`) ne changent pas.
 */
class LibellesPauseTest {
    private val langues = listOf("en", "fr", "de", "es", "it", "nl")
    private val pause = Regex("""(?i)\b(pause\w*|pausa\w*|pausier\w*|pauze\w*|pauzeer\w*|paused)\b""")

    /** Les mots attendus, par langue. */
    private val toutFermer = mapOf("en" to "Close everything", "fr" to "Tout fermer", "de" to "Alles sperren", "es" to "Cerrar todo", "it" to "Chiudi tutto", "nl" to "Alles sluiten")
    private val rouvrir = mapOf("en" to "Reopen", "fr" to "Rouvrir", "de" to "Wieder öffnen", "es" to "Reabrir", "it" to "Riapri", "nl" to "Heropenen")
    private val suspendre = mapOf("en" to "suspen", "fr" to "suspen", "de" to "ausgesetzt|aussetz", "es" to "suspen", "it" to "sospe", "nl" to "opschort|opgeschort")

    private fun textes(l: String) = TrJvm.dossier(TrJvm.dossierDe(l)).chaines

    @Test fun pause_generale_devient_tout_fermer_et_rouvrir() {
        val fautes = ArrayList<String>()
        for (l in langues) {
            val t = textes(l)
            if (t["parents_pause_generale"] != toutFermer[l]) fautes += "$l : parents_pause_generale = ${t["parents_pause_generale"]}"
            if (t["parents_reprendre"] != rouvrir[l]) fautes += "$l : parents_reprendre = ${t["parents_reprendre"]}"
            for (k in listOf("parents_pause_generale", "parents_pause_generale_texte", "parents_pause_generale_titre", "parents_pause_generale_max", "pausegenerale_banniere"))
                t[k]?.let { if (pause.containsMatchIn(it)) fautes += "$l : $k dit encore « pause » : $it" } ?: run { fautes += "$l : $k absente" }
        }
        if (fautes.isNotEmpty()) fail(fautes.joinToString("\n"))
    }

    @Test fun pause_de_vision_devient_suspendre_la_protection() {
        val fautes = ArrayList<String>()
        val cles = listOf("setup_pause", "setup_pause_texte", "setup_pause_code", "setup_pause_n", "setup_pause_empreinte", "setup_mettre_pause",
            "agent_titre_pause", "statut_pause_jusqua", "setup_en_pause_reprise", "setup_gestion_detail", "setup_gestion_detail_max",
            "gestion_pause_max", "gestion_pause_max_titre", "reglagestv_une_fois_ouvert", "reglagestv_pause_code_desinstallation", "statut_suspendue")
        for (l in langues) {
            val t = textes(l)
            val attendu = Regex("(?i)" + suspendre.getValue(l))
            for (k in cles) {
                val v = t[k] ?: run { fautes += "$l : $k absente"; null } ?: continue
                if (pause.containsMatchIn(v)) fautes += "$l : $k dit encore « pause » : $v"
                if (!attendu.containsMatchIn(v)) fautes += "$l : $k ne dit pas « suspendre » : $v"
            }
        }
        if (fautes.isNotEmpty()) fail(fautes.joinToString("\n"))
    }

    @Test fun la_notification_de_deverrouillage_ne_parle_plus_de_pause() {
        val fautes = ArrayList<String>()
        for (l in langues) {
            val t = textes(l)
            for (k in listOf("dev_action_pause", "messagesnotif_deverrouillage", "messagesnotif_deverrouillage_motif", "messagesnotif_deverrouillage_titre"))
                t[k]?.let { if (pause.containsMatchIn(it)) fautes += "$l : $k : $it" }
        }
        if (fautes.isNotEmpty()) fail(fautes.joinToString("\n"))
    }
}
