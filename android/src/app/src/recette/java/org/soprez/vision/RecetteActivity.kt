// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * COPIE DE RECETTE SEULEMENT : montre, par adb, les écrans de la 1.94 qui demandent un état difficile à monter sur un téléphone de
 * test (un Décideur qui répond, un appareil fermé) : `--es ecran temps` (le choix de durée d'« Accorder du temps », le vrai
 * composant) ou `--es ecran attente` (la carte « demande envoyée » et les boutons grisés de l'attente avant de redemander,
 * construits avec le même Ui.boutonEnAttente) ; `--es theme Sombre` pose le thème de la copie. Aucun envoi, aucun serveur.
 */
class RecetteActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        intent.getStringExtra("theme")?.let { Local.poserTheme(this, it) }
        val th = Ui.themeActif(this)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(th.fond))
        val racine = Ui.colonne(this, Ui.dp(this, 24f))
        setContentView(ScrollView(this).apply { addView(racine) })
        when (intent.getStringExtra("ecran")) {
            "temps" -> {
                val resultat = Ui.texte(this, "", 15f, Ui.TEXTE_2)
                racine.addView(resultat)
                Temps.choisir(this, getString(R.string.messageecran_accorder_du_temps), System.currentTimeMillis() + 3 * 3_600_000L,
                    annule = { resultat.text = "annulé" }) { ch -> resultat.text = ch.libelle(System.currentTimeMillis() + 3 * 3_600_000L) }
            }
            "attente" -> {
                val fin = System.currentTimeMillis() + 4 * 60_000L + 30_000L
                val carte = Ui.carte(this, Ui.CARTE); carte.gravity = Gravity.CENTER
                carte.addView(Ui.texte(this, getString(R.string.block_demande_envoyee), 15f, Ui.OR, gras = true, centre = true))
                carte.addView(Ui.marge(this, Ui.texte(this, getString(R.string.block_prevenu_reponse, "Décideur"), 13f, Ui.TEXTE_2, centre = true), haut = 6f))
                racine.addView(carte)
                racine.addView(Ui.marge(this, Ui.boutonEnAttente(this, getString(R.string.acces_demander_acces, "Roblox"), fin), haut = 10f),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                racine.addView(Ui.texte(this, Acces.attenteTexte(this, fin), 13f, Ui.TEXTE_2, centre = true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(this@RecetteActivity, 6f) })
                racine.addView(Ui.texte(this, getString(R.string.block_demander_du_temps), 15f, Ui.TEXTE, gras = true, centre = true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(this@RecetteActivity, 24f) })
                val rangee = Ui.rangeeBoutons(this).apply { gravity = Gravity.CENTER }
                Durees.DEMANDE.zip(Durees.libelles(Durees.DEMANDE)).forEachIndexed { k, (_, lbl) ->
                    rangee.addView(Ui.marge(this, Ui.boutonEnAttente(this, lbl, fin, primaire = false), haut = 10f, gauche = if (k == 0) 0f else 10f))
                }
                racine.addView(rangee)
                racine.addView(Ui.texte(this, Acces.attenteTexte(this, fin), 13f, Ui.TEXTE_2, centre = true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(this@RecetteActivity, 6f) })
            }
        }
    }
}
