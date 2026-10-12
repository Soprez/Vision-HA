// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Accorder du temps (demande d'essai du 2026-10-08) : en plus des durées toutes faites, une durée libre
 * (heures et minutes) et « Jusqu'à la prochaine plage » (jusqu'au prochain changement de l'agenda,
 * l'heure affichée). Le même choix partout : espace gestion, accueil, réponses aux demandes.
 * La ré-authentification (Garde) et les droits du rôle restent ceux de l'appelant et du serveur.
 */
object Temps {
    /** Ce qui a été choisi : des minutes, ou « jusqu'à la prochaine plage » (minutes = 0). */
    class Choix(val minutes: Int, val jusquaPlage: Boolean) {
        fun libelle(prochain: Long): String = if (jusquaPlage) Tr.s(R.string.temps_jusqua, Etat.heure(prochain)) else duree(minutes)
    }

    fun duree(min: Int): String = Tr.duree(min)

    /**
     * Le choix de la durée. `prochain` : l'heure du prochain changement de l'agenda (ms), 0 si aucun ;
     * l'option « Jusqu'à la prochaine plage » n'apparaît que s'il est à venir.
     */
    fun choisir(act: Activity, titre: String, prochain: Long, annule: () -> Unit = {}, ok: (Choix) -> Unit) {
        val libelles = arrayListOf(act.resources.getQuantityString(R.plurals.temps_minutes, 15, 15),
            act.resources.getQuantityString(R.plurals.temps_minutes, 30, 30),
            act.resources.getQuantityString(R.plurals.temps_heures, 1, 1),
            act.resources.getQuantityString(R.plurals.temps_heures, 2, 2), act.getString(R.string.temps_autre_duree))
        val minutes = listOf(15, 30, 60, 120)
        val avecPlage = prochain > System.currentTimeMillis() + 60_000
        if (avecPlage) libelles.add(act.getString(R.string.temps_jusqua_plage, Etat.heure(prochain)))
        Ui.dialogue(act).setTitle(titre)
            .setItems(libelles.toTypedArray()) { _, k ->
                when {
                    k < minutes.size -> ok(Choix(minutes[k], false))
                    k == minutes.size -> dureeLibre(act, titre, annule, ok)
                    else -> ok(Choix(0, true))
                }
            }
            .setNegativeButton(R.string.commun_annuler) { _, _ -> annule() }
            .setOnCancelListener { annule() }
            .show()
    }

    /** Les minutes d'ici la prochaine plage (au moins 5) : envoyées avec le drapeau, pour un serveur plus ancien. */
    fun minutesJusqua(prochain: Long): Int =
        ((prochain - System.currentTimeMillis() + 59_999) / 60_000).toInt().coerceAtLeast(5)

    /** Heures et minutes, au pas de 5 minutes ; au moins 5 minutes, au plus 12 heures. */
    fun dureeLibre(act: Activity, titre: String, annule: () -> Unit = {}, ok: (Choix) -> Unit) {
        val heures = Ui.selecteurNombre(act, 0, 12, 0)
        val pas = (0 until 60 step 5).map { "%02d".format(it) }.toTypedArray()
        val mins = Ui.selecteurNombre(act, 0, pas.size - 1, 6, pas)
        fun legende(t: String) = Ui.sansCesure(TextView(act)).apply { text = t; textSize = 16f; setTextColor(Ui.themeActif(act).encre); setPadding(Ui.dp(act, 8f), 0, Ui.dp(act, 16f), 0) }
        val rangee = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(0, Ui.dp(act, 12f), 0, 0)
            addView(heures); addView(legende(act.getString(R.string.temps_unite_h))); addView(mins); addView(legende(act.getString(R.string.temps_unite_min)))
        }
        Ui.dialogue(act).setTitle(titre).setView(rangee)
            .setPositiveButton(R.string.temps_accorder) { _, _ ->
                val total = heures.value * 60 + mins.value * 5
                if (total >= 5) ok(Choix(total, false))
                else { android.widget.Toast.makeText(act, act.getString(R.string.temps_au_moins_5), android.widget.Toast.LENGTH_SHORT).show(); annule() }
            }
            .setNegativeButton(R.string.commun_annuler) { _, _ -> annule() }
            .setOnCancelListener { annule() }
            .show()
    }
}
