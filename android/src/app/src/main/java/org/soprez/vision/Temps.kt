// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.view.View
import android.view.ViewGroup
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

    /** La durée libre : de 5 minutes à 12 heures, de 5 en 5. */
    const val MIN = 5
    const val MAX = 720
    const val PAS = 5

    /** Ce que le dialogue propose d'emblée (celui de [Durees.PAR_DEFAUT]) et ce que valent les durées de départ du « Autre… ». Pur. */
    fun depart(): Int = Durees.PAR_DEFAUT

    /** Une durée libre ramenée aux bornes et au pas (une valeur venue d'ailleurs ne sort jamais du sélecteur). Pur. */
    fun libreValide(min: Int): Int = (((min.coerceIn(MIN, MAX)) + PAS / 2) / PAS * PAS).coerceIn(MIN, MAX)

    /**
     * Le choix de la durée, dans un seul dialogue du thème : des puces (15 min, 30 min, 1 h, 2 h, « Autre… », et « Jusqu'à la
     * prochaine plage » si elle vient), 30 min posées d'emblée, un sélecteur sobre (− / +) pour « Autre… », « Annuler » en
     * secondaire et « Accorder » en principal. `prochain` : l'heure du prochain changement de l'agenda (ms), 0 si aucun.
     */
    fun choisir(act: Activity, titre: String, prochain: Long, annule: () -> Unit = {}, ok: (Choix) -> Unit) {
        val th = Ui.themeActif(act)
        // Lot 6 (APP-08) : les mêmes durées de réponse partout (notification, plein écran, gestion).
        val rapides = Durees.REPONSE
        val avecPlage = prochain > System.currentTimeMillis() + 60_000
        val AUTRE = -1; val PLAGE = -2
        var sel = if (depart() in rapides) depart() else rapides.first()
        var libre = libreValide(depart())
        val puces = LinkedHashMap<Int, TextView>()
        val colonne = Ui.colonne(act)
        val dialogue = Ui.dialogue(act).setTitle(titre).create()
        var choisi = false

        val selecteur = SelecteurPas(act, MIN, MAX, libre, PAS, th = th, format = { Tr.duree(it) }) { libre = it }
        val zoneLibre = Ui.rangee(act).apply { gravity = android.view.Gravity.CENTER; visibility = View.GONE; addView(selecteur) }
        val pleineLargeur = { haut: Float -> LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(act, haut) } }

        fun peindre() {
            for ((cle, v) in puces) {
                val on = cle == sel
                v.background = Ui.fond(if (on) Palette.melanger(th.carte, th.or, 0.22f) else 0, Ui.dp(act, 999f).toFloat(), Ui.dp(act, if (on) 2f else 1f), if (on) th.or else th.carteBord)
                v.setTextColor(if (on) th.encre else th.encre2)
                v.isSelected = on
            }
            zoneLibre.visibility = if (sel == AUTRE) View.VISIBLE else View.GONE
        }
        fun puce(cle: Int, t: String): View = TextView(act).apply {
            text = Ui.coupable(t); textSize = 14f; typeface = Polices.demiGras(act); gravity = android.view.Gravity.CENTER
            minHeight = Ui.dp(act, Ui.CIBLE_MIN); setPadding(Ui.dp(act, 12f), Ui.dp(act, 8f), Ui.dp(act, 12f), Ui.dp(act, 8f))
            Ui.sansCesure(this)
            isClickable = true
            Ui.anneauFocus(this, th, 26f, agrandir = false)
            contentDescription = t
            setOnClickListener {
                // « Autre… » part de la dernière durée toute faite choisie : on affine, on ne repart pas de zéro.
                if (cle == AUTRE && sel > 0) { libre = libreValide(sel); selecteur.value = libre }
                sel = cle; peindre()
            }
            puces[cle] = this
        }
        val tout = rapides.map { puce(it, Tr.duree(it)) } + puce(AUTRE, act.getString(R.string.temps_autre_puce))
        // Trois puces par ligne au plus (règle du projet) : 15 min · 30 min · 1 h, puis 2 h · Autre…
        tout.chunked(3).forEachIndexed { i, ligne ->
            val r = Ui.rangee(act)
            ligne.forEachIndexed { k, v -> r.addView(Ui.marge(act, Ui.poids(v), gauche = if (k == 0) 0f else 8f)) }
            // Une vue vide sans hauteur (une vue simple en WRAP_CONTENT prendrait toute la place offerte, et la boîte serait pleine hauteur).
            repeat(3 - ligne.size) { r.addView(View(act), LinearLayout.LayoutParams(0, 0, 1f)) }
            colonne.addView(r, pleineLargeur(if (i == 0) 0f else 8f))
        }
        if (avecPlage) colonne.addView(Ui.marge(act, puce(PLAGE, act.getString(R.string.temps_jusqua_plage, Etat.heure(prochain))).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }, haut = 8f))
        colonne.addView(zoneLibre, pleineLargeur(16f))

        val boutons = Ui.rangeeBoutons(act).apply { gravity = android.view.Gravity.END }
        boutons.addView(Ui.marge(act, Ui.boutonSecondaire(act, act.getString(R.string.commun_annuler)) { dialogue.cancel() }, haut = 20f))
        boutons.addView(Ui.marge(act, Ui.boutonPrimaire(act, act.getString(R.string.temps_accorder)) {
            choisi = true; dialogue.dismiss()
            ok(when (sel) { PLAGE -> Choix(0, true); AUTRE -> Choix(libre, false); else -> Choix(sel, false) })
        }, haut = 20f, gauche = 8f))
        colonne.addView(boutons, pleineLargeur(0f))
        peindre()

        dialogue.setView(colonne, Ui.dp(act, 20f), Ui.dp(act, 8f), Ui.dp(act, 20f), Ui.dp(act, 4f))
        dialogue.setOnCancelListener { if (!choisi) annule() }
        dialogue.show()
    }

    /** Les minutes d'ici la prochaine plage (au moins 5) : envoyées avec le drapeau, pour un serveur plus ancien. */
    fun minutesJusqua(prochain: Long): Int =
        ((prochain - System.currentTimeMillis() + 59_999) / 60_000).toInt().coerceAtLeast(5)
}
