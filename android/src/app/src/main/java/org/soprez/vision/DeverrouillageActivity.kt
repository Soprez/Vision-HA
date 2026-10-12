// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * « Demander à un parent » (Vision 1.68.0, docs/deverrouillage.md) : l'enfant demande une action
 * pour quelques minutes, puis attend. Le parent répond depuis son téléphone (Vision) ou Home
 * Assistant : déverrouillage direct, code à 6 chiffres à taper ici, ou QR à scanner. Le code
 * parent local reste toujours possible, à côté.
 */
class DeverrouillageActivity : Activity() {
    companion object {
        const val EXTRA_ACTION = "action"

        fun ouvrir(ctx: Context, action: String) {
            ctx.startActivity(Intent(ctx, DeverrouillageActivity::class.java).putExtra(EXTRA_ACTION, action)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
    }

    private val principal = Handler(Looper.getMainLooper())
    private lateinit var racine: LinearLayout
    private lateinit var etat: TextView
    private var action = "parent"
    private var fini = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Ui.charger(this)
        if (Build.VERSION.SDK_INT >= 21) { window.statusBarColor = Ui.FOND; window.navigationBarColor = Ui.FOND }
        // Module « déverrouillage » coupé dans Home Assistant : cet écran n'a plus lieu d'être.
        if (!Modules.actif(this, Modules.DEVERROUILLAGE)) { finish(); return }
        action = intent.getStringExtra(EXTRA_ACTION)?.takeIf { Deverrouillage.connue(it) } ?: "parent"
        racine = Ui.colonne(this, Ui.dp(this, 24f)).apply { gravity = Gravity.CENTER_HORIZONTAL; setBackgroundColor(Ui.FOND) }
        setContentView(android.widget.ScrollView(this).apply { setBackgroundColor(Ui.FOND); isFillViewport = true; addView(racine) })
        val d = Deverrouillage.enCours
        if (d != null && d.action == action && System.currentTimeMillis() < d.expire) attendre() else choisir()
    }

    override fun onResume() {
        super.onResume()
        // Retour du scanner : la demande a peut-être été déverrouillée.
        if (Deverrouillage.enCours == null && ::etat.isInitialized && etat.tag == "attente") finish()
    }

    override fun onDestroy() { fini = true; principal.removeCallbacksAndMessages(null); super.onDestroy() }

    private fun titre(t: String, sous: String) {
        racine.removeAllViews()
        racine.addView(Ui.texte(this, t, 24f, Ui.TEXTE, gras = true, centre = true))
        racine.addView(Ui.marge(this, Ui.texte(this, sous, 15f, Ui.TEXTE_2, centre = true), haut = 8f))
        etat = Ui.texte(this, "", 15f, Ui.OR, centre = true)
    }

    // ------------------------------------------------------------------ Choisir la durée

    private fun choisir() {
        titre(getString(R.string.deva_titre), getString(R.string.deva_pour, Deverrouillage.libelle(this, action), Vocabulaire.decideur(this)))
        val motif = EditText(this).apply {
            hint = getString(R.string.deva_pourquoi); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setTextColor(Ui.TEXTE); setHintTextColor(Ui.TEXTE_2)
        }
        racine.addView(Ui.marge(this, motif, haut = 20f))
        racine.addView(Ui.marge(this, Ui.texte(this, getString(R.string.deva_combien), 14f, Ui.TEXTE_2, centre = true), haut = 16f))
        val r = Ui.rangeeBoutons(this).apply { gravity = Gravity.CENTER }
        val durees = when (action) { "reglages" -> listOf(10, 30); "parent" -> listOf(15, 30, 60); else -> listOf(15, 30, 60) }
        durees.forEachIndexed { k, m ->
            val b = Ui.boutonPrimaire(this, Tr.duree(m)) { envoyer(m, motif.text.toString()) }
            r.addView(Ui.marge(this, b, gauche = if (k == 0) 0f else 10f))
            if (k == 0) b.requestFocus()
        }
        racine.addView(Ui.marge(this, r, haut = 10f))
        racine.addView(Ui.marge(this, etat, haut = 14f))
        racine.addView(Ui.marge(this, Ui.boutonSecondaire(this, getString(R.string.commun_annuler)) { finish() }, haut = 20f))
    }

    private fun envoyer(minutes: Int, motif: String) {
        etat.text = getString(R.string.deva_envoi)
        Thread {
            try {
                Deverrouillage.demander(this, action, minutes, motif)
                runOnUiThread { if (!fini) attendre() }
            } catch (e: Deverrouillage.Echec) {
                runOnUiThread { if (!fini) etat.text = e.raison }
            }
        }.start()
    }

    // ------------------------------------------------------------------ Attendre la réponse

    private fun attendre() {
        val d = Deverrouillage.enCours ?: return choisir()
        titre(getString(R.string.deva_envoyee), getString(R.string.deva_envoyee_texte,
            Deverrouillage.libelle(this, d.action).replaceFirstChar { it.uppercase() }, d.minutes))
        etat.tag = "attente"
        etat.text = getString(R.string.deva_attente)
        racine.addView(Ui.marge(this, etat, haut = 18f))
        val code = EditText(this).apply {
            hint = getString(R.string.deva_code_6); inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(Ui.TEXTE); setHintTextColor(Ui.TEXTE_2); textSize = 22f; gravity = Gravity.CENTER
        }
        racine.addView(Ui.marge(this, code, haut = 20f))
        racine.addView(Ui.marge(this, Ui.boutonPrimaire(this, getString(R.string.deva_valider_code)) { saisirCode(code.text.toString()) }, haut = 8f))
        if (Appairage.aUneCamera(this)) racine.addView(Ui.marge(this, Ui.boutonSecondaire(this, getString(R.string.deva_scanner)) {
            startActivity(Intent(this, ScanActivity::class.java).putExtra(ScanActivity.EXTRA_MODE, ScanActivity.MODE_DEVERROUILLAGE))
        }, haut = 10f))
        racine.addView(Ui.marge(this, Ui.boutonSecondaire(this, getString(R.string.deva_annuler_demande)) { Deverrouillage.oublier(); finish() }, haut = 10f))
        sonder()
    }

    /** Toutes les 3 s : la réponse du parent (approbation directe, refus, expiration). */
    private fun sonder() {
        if (fini) return
        val d = Deverrouillage.enCours ?: return
        if (System.currentTimeMillis() > d.expire) { Deverrouillage.oublier(); etat.text = getString(R.string.deva_pas_de_reponse); return }
        Thread {
            val r = Deverrouillage.etat(this)
            runOnUiThread {
                if (fini) return@runOnUiThread
                when (r?.optString("etat")) {
                    "approuvee" -> r.optJSONObject("approbation")?.let { a ->
                        try { reussi(Deverrouillage.accepter(this, a)); return@runOnUiThread } catch (e: Deverrouillage.Echec) { etat.text = e.raison }
                    } ?: run { etat.text = getString(R.string.deva_approuve) }
                    // Validation à plusieurs (Vision 1.72.0) : « 1/2 validé, en attente de Mia ».
                    "partielle" -> {
                        val avance = if (Modules.actif(this, Modules.DOUBLE_VALIDATION)) getString(R.string.deva_valide_n, r.optInt("voix"), r.optInt("requis"))
                            else getString(R.string.deva_validation_en_cours)
                        etat.text = Deverrouillage.enAttenteDe(r).let { if (it.isEmpty()) getString(R.string.deva_partielle, avance)
                            else getString(R.string.deva_partielle_attente, avance, it) }
                    }
                    "refusee" -> { Deverrouillage.oublier(); etat.text = getString(R.string.deva_refusee); return@runOnUiThread }
                    "annulee", "expiree", "bloquee" -> { Deverrouillage.oublier(); etat.text = getString(R.string.deva_terminee); return@runOnUiThread }
                }
                principal.postDelayed({ sonder() }, 3000)
            }
        }.start()
    }

    private fun saisirCode(code: String) {
        if (code.filter { it.isDigit() }.length != 6) { etat.text = getString(R.string.deva_code_a_6); return }
        etat.text = getString(R.string.deva_verification)
        Thread {
            val msg = try { Result.success(Deverrouillage.accepter(this, Deverrouillage.code(this, code))) }
                      catch (e: Deverrouillage.Echec) { Result.failure(e) }
            runOnUiThread {
                if (fini) return@runOnUiThread
                msg.onSuccess { reussi(it) }.onFailure { etat.text = (it as? Deverrouillage.Echec)?.raison ?: getString(R.string.deva_erreur) }
            }
        }.start()
    }

    private fun reussi(message: String) {
        principal.removeCallbacksAndMessages(null)
        titre(getString(R.string.deva_deverrouille), message)
        racine.addView(Ui.marge(this, Ui.boutonPrimaire(this, getString(R.string.deva_continuer)) { finish() }, haut = 24f))
        principal.postDelayed({ if (!fini) finish() }, 3000)
    }
}
