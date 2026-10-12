// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * 1.76.0 (point 6 bis) : le réglage d'un widget « Vision · Home Assistant » posé sur un lanceur. L'entité est choisie
 * parmi celles que Home Assistant permet sur cet appareil (la même liste blanche que l'accueil Vision), puis le style.
 * Annuler (retour) : le lanceur n'ajoute pas le widget.
 */
class WidgetHaConfigActivity : Activity() {
    private var idWidget = AppWidgetManager.INVALID_APPWIDGET_ID
    private lateinit var racine: LinearLayout
    private var filtre = ""

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setResult(RESULT_CANCELED)
        idWidget = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (idWidget == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        Ui.charger(this)
        if (android.os.Build.VERSION.SDK_INT >= 21) { window.statusBarColor = Ui.FOND; window.navigationBarColor = Ui.FOND }
        val scroll = ScrollView(this).apply { setBackgroundColor(Ui.FOND); isFillViewport = true }
        racine = Ui.colonne(this, Ui.dp(this, 20f))
        scroll.addView(racine)
        setContentView(scroll)
        dessiner()
        if (Config(this).inscrit && Modules.actif(this, Modules.ENTITES) && (WidgetsHaStock.catalogue(this).isEmpty() || WidgetsHaStock.catalogueAPerime(this))) {
            Thread {
                try { WidgetsHaStock.recharger(this) } catch (_: Exception) {}
                runOnUiThread { if (!isFinishing) dessiner() }
            }.start()
        }
    }

    private fun dessiner() {
        racine.removeAllViews()
        racine.addView(Ui.marque(this))
        racine.addView(Ui.titre(this, getString(R.string.whc_titre)))
        racine.addView(Ui.marge(this, Ui.sousTitre(this, getString(R.string.whc_sous_titre)), bas = 14f))
        val probleme = when {
            !Config(this).inscrit -> getString(R.string.whc_pas_relie)
            !Modules.actif(this, Modules.ENTITES) -> getString(R.string.whc_desactives)
            else -> null
        }
        if (probleme != null) {
            racine.addView(Ui.carte(this, Ui.CARTE_HAUTE).apply { addView(Ui.texte(this@WidgetHaConfigActivity, probleme, 15f, Ui.TEXTE)) })
            racine.addView(Ui.boutonSecondaire(this, getString(R.string.commun_fermer)) { finish() })
            return
        }
        val toutes = WidgetsHaStock.catalogue(this).values.sortedBy { it.nom.lowercase() }
        if (toutes.isEmpty()) {
            racine.addView(Ui.carte(this).apply { addView(Ui.texte(this@WidgetHaConfigActivity,
                getString(R.string.whc_chargement), 14f, Ui.TEXTE_2)) })
            return
        }
        if (toutes.size > 12) {
            val champ = Ui.champ(this, getString(R.string.whc_chercher), filtre)
            champ.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { filtre = s?.toString().orEmpty(); remplir(liste, toutes) }
            })
            racine.addView(Ui.marge(this, champ, bas = 10f))
        }
        liste = Ui.colonne(this)
        racine.addView(liste)
        remplir(liste, toutes)
    }

    private lateinit var liste: LinearLayout

    private fun remplir(l: LinearLayout, toutes: List<WidgetsHa.Entite>) {
        l.removeAllViews()
        val f = filtre.trim().lowercase()
        toutes.filter { f.isEmpty() || it.nom.lowercase().contains(f) || it.entityId.contains(f) }.forEach { e ->
            val valeur = WidgetsHa.valeurAffichee(e.etat, e.unite, e.domaine, e.deviceClass)
            l.addView(Ui.tuile(this, e.nom, if (e.actions.isNotEmpty()) getString(R.string.whc_pilotable, valeur) else valeur, false) { choisirStyle(e) }.apply {
                minHeight = Ui.dp(this@WidgetHaConfigActivity, 60f); minimumHeight = minHeight
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = Ui.dp(this@WidgetHaConfigActivity, 8f) }
            })
        }
    }

    private fun choisirStyle(e: WidgetsHa.Entite) {
        // La mini-carte (plusieurs entités) reste propre à l'accueil Vision ; les boutons n'ont de sens que pilotables.
        val styles = WidgetsHa.stylesProposes(e).filter { it != WidgetsHa.MINI_CARTE }
            .filter { (it != WidgetsHa.BOUTON && it != WidgetsHa.INTERRUPTEUR) || WidgetsHa.pilotable(e, tele = false) }
            .ifEmpty { listOf(WidgetsHa.TUILE) }
        if (styles.size == 1) { periode(e, styles[0]); return }
        Ui.dialogue(this).setTitle(e.nom).setItems(styles.map { WidgetsHa.nomStyle(it) }.toTypedArray()) { _, k -> periode(e, styles[k]) }
            .setNegativeButton(R.string.commun_annuler, null).show()
    }

    private fun periode(e: WidgetsHa.Entite, style: String) {
        if (style != WidgetsHa.GRAPHIQUE) { terminer(e, style, "24h"); return }
        Ui.dialogue(this).setTitle(R.string.whc_periode).setItems(arrayOf(getString(R.string.whc_24h), getString(R.string.whc_7j))) { _, k ->
            terminer(e, style, if (k == 0) "24h" else "7j")
        }.setNegativeButton(R.string.commun_annuler, null).show()
    }

    private fun terminer(e: WidgetsHa.Entite, style: String, periode: String) {
        val r = WidgetsHa.Reglage("sys$idWidget", listOf(e.entityId), style, 2, 1, periode = periode)
        WidgetsSysteme.ecrire(this, idWidget, r)
        val app = applicationContext
        val id = idWidget
        WidgetsSysteme.dessiner(app, id)
        Thread { try { WidgetsSysteme.rafraichir(app, force = true) } catch (_: Exception) {} }.start()
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        finish()
    }
}
