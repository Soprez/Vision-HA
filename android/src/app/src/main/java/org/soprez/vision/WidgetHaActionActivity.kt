// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.json.JSONObject

/**
 * 1.88.0 (widgets interactifs) : la petite fenêtre qu'ouvre un widget « Vision · Home Assistant » de l'écran d'accueil
 * Android quand toucher ne suffit pas : confirmer une action réglée « avec confirmation » dans Home Assistant, régler
 * une valeur (− / +) ou choisir dans une liste. Le système n'affiche pas de boîte depuis un widget : Vision ouvre la
 * sienne, transparente, puis se referme. Home Assistant revérifie tout (droits, bornes, confirmation).
 */
class WidgetHaActionActivity : Activity() {
    companion object {
        private const val EXTRA_ID = "widget"

        fun ouvrir(ctx: Context, appWidgetId: Int) {
            try {
                ctx.startActivity(Intent(ctx, WidgetHaActionActivity::class.java).putExtra(EXTRA_ID, appWidgetId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
            } catch (_: Exception) {}
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var confirme = false
    private var idWidget = 0

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Ui.charger(this)
        idWidget = intent.getIntExtra(EXTRA_ID, 0)
        val r = WidgetsSysteme.reglage(this, idWidget)
        val e = r?.let { WidgetsHaStock.entite(this, it.entites.first()) }
        if (r == null || e == null || !WidgetsHa.pilotable(e, tele = false)) { finish(); return }
        val st = FluxWidgets.etats[e.entityId] ?: WidgetsHa.EtatEntite(e.etat, e.attributs, "")
        val nom = r.libelle.ifEmpty { e.nom }
        when {
            r.style == WidgetsHa.REGLAGE && "select_option" in e.actions -> choisir(e, st, nom)
            r.style == WidgetsHa.REGLAGE && "set_value" in e.actions -> regler(e, st, nom)
            else -> {
                val service = when (r.style) {
                    WidgetsHa.INTERRUPTEUR -> WidgetsHa.serviceBascule(e, st.etat)
                    else -> WidgetsHa.serviceBouton(e)
                } ?: run { finish(); return }
                avecConfirmation(e, nom, null) { agir(e, service, JSONObject(), fermer = true) }
            }
        }
    }

    private fun boite(): AlertDialog.Builder = Ui.dialogue(this).setOnCancelListener { finish() }

    /** Confirme d'abord si l'entité le demande (une fois par fenêtre), puis fait la suite. */
    private fun avecConfirmation(e: WidgetsHa.Entite, nom: String, question: String?, suite: () -> Unit) {
        if ((!e.confirmer && question == null) || confirme) { suite(); return }
        boite().setTitle(nom).setMessage(question?.takeIf { it.isNotEmpty() } ?: getString(R.string.wha_confirmer_question, nom))
            .setPositiveButton(getString(R.string.wha_confirmer_oui)) { _, _ -> confirme = true; suite() }
            .setNegativeButton(getString(R.string.commun_lot2_annuler)) { _, _ -> finish() }.show()
    }

    private fun choisir(e: WidgetsHa.Entite, st: WidgetsHa.EtatEntite, nom: String) {
        val options = WidgetsHa.options(st)
        if (options.isEmpty()) { finish(); return }
        boite().setTitle(getString(R.string.wha_choisir, nom))
            .setSingleChoiceItems(options.toTypedArray(), options.indexOf(st.etat)) { d, k ->
                d.dismiss()
                if (options[k] == st.etat) finish()
                else avecConfirmation(e, nom, null) { agir(e, "select_option", JSONObject().put("option", options[k]), fermer = true) }
            }
            .setNegativeButton(getString(R.string.commun_lot2_annuler)) { _, _ -> finish() }.show()
    }

    /** − et + gardent la fenêtre ouverte (la valeur suit chaque appui) ; Fermer la referme. */
    private fun regler(e: WidgetsHa.Entite, depart: WidgetsHa.EtatEntite, nom: String) {
        var st = depart
        fun texte() = WidgetsHa.valeurAffichee(st.etat, e.unite, e.domaine, e.deviceClass)
        val d = boite().setTitle(nom).setMessage(texte())
            .setNeutralButton("−", null).setPositiveButton("+", null)
            .setNegativeButton(getString(R.string.commun_fermer)) { _, _ -> finish() }.create()
        d.setOnShowListener {
            fun brancher(bouton: Int, sens: Int, desc: Int) {
                val b = d.getButton(bouton) ?: return
                b.contentDescription = getString(desc, nom)
                b.minWidth = Ui.dp(this, 48f); b.minHeight = Ui.dp(this, 48f)
                b.setOnClickListener {
                    val v = WidgetsHa.valeurApres(e, st, sens) ?: return@setOnClickListener
                    avecConfirmation(e, nom, null) {
                        st = st.copy(etat = WidgetsHa.nombre(v)); d.setMessage(texte())
                        agir(e, "set_value", JSONObject().put("value", v), fermer = false)
                    }
                }
            }
            brancher(AlertDialog.BUTTON_NEUTRAL, -1, R.string.wha_diminuer)
            brancher(AlertDialog.BUTTON_POSITIVE, 1, R.string.wha_augmenter)
        }
        d.show()
    }

    /** L'action part (hors du fil principal) ; un refus s'explique ; le widget se redessine ensuite. */
    private fun agir(e: WidgetsHa.Entite, service: String, donnees: JSONObject, fermer: Boolean) {
        val app = applicationContext
        val nom = e.nom
        Thread {
            val res = WidgetsHaStock.agir(app, e.entityId, service, donnees, confirme)
            main.post {
                when {
                    res.question != null && !confirme && !isFinishing -> avecConfirmation(e, nom, res.question) { agir(e, service, donnees, fermer) }
                    res.message != null -> Toast.makeText(app, res.message, Toast.LENGTH_LONG).show()
                }
                if (fermer && res.question == null && !isFinishing) finish()
            }
            Thread { WidgetsSysteme.rafraichir(app, force = true) }.also { it.isDaemon = true }.start()
        }.also { it.isDaemon = true }.start()
    }
}
