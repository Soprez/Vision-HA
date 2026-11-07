// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * « Signaler un problème » : Réglages › À propos › Signaler un problème. Réservé aux responsables (preuve : empreinte
 * ou code, [Garde.exiger]). Trois écrans dans une seule activité :
 *  - **intro** : ce qui est enregistré, où ça part, « rien ne part sans toi » ; Commencer (ou Terminer si un
 *    enregistrement est déjà en cours) ; les signalements déjà envoyés, avec « Supprimer » ;
 *  - **aperçu** : fiche, journaux anonymisés, captures retirables, description libre, Envoyer · Jeter ;
 *  - **référence** : le numéro du signalement après l'envoi.
 * Rien n'est écrit hors de l'appareil avant « Envoyer ».
 */
class SignalerActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val tele by lazy { ReglagesTvActivity.estTele(this) }
    private var brouillon: Signalement.Brouillon? = null
    private var captures = mutableListOf<ByteArray>()
    private var enEnvoi = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.decorView.setBackgroundColor(Ui.themeActif(this).fond)
        if (intent.getStringExtra(MODE) == APERCU && Signalement.debut > 0L) preparer() else intro()
    }

    override fun onResume() {
        super.onResume()
        // Retour depuis Réglages ou l'accueil pendant un enregistrement : l'intro propose Terminer.
        if (brouillon == null && !enEnvoi && intent.getStringExtra(MODE) != APERCU) intro()
    }

    private fun page(contenu: LinearLayout.() -> Unit) {
        val c = Ui.colonne(this, Ui.dp(this, if (tele) 48f else 20f))
        c.contenu()
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(c) })
    }

    private fun estResponsable(): Boolean = Profil.fonctions(this).let { "gerer" in it || "approuver" in it }

    // ------------------------------------------------------------------ intro

    private fun intro() {
        page {
            addView(Ui.titre(this@SignalerActivity, getString(R.string.signaler_titre)))
            if (!estResponsable()) {
                addView(Ui.marge(this@SignalerActivity, Ui.texte(this@SignalerActivity, getString(R.string.signaler_reserve, Vocabulaire.decideurs(this@SignalerActivity)), 15f, Ui.TEXTE_2), haut = 12f))
                addView(Ui.marge(this@SignalerActivity, Ui.boutonSecondaire(this@SignalerActivity, getString(R.string.signaler_fermer)) { finish() }, haut = 16f))
                return@page
            }
            if (Signalement.enCours) {
                addView(Ui.marge(this@SignalerActivity, Ui.texte(this@SignalerActivity, getString(R.string.signaler_en_cours), 17f, Ui.TEXTE, gras = true), haut = 12f))
                addView(Ui.marge(this@SignalerActivity, Ui.texte(this@SignalerActivity, getString(R.string.signaler_en_cours_aide), 14f, Ui.TEXTE_2), haut = 4f))
                addView(Ui.marge(this@SignalerActivity, Ui.boutonPrimaire(this@SignalerActivity, getString(R.string.signaler_terminer)) { terminer(this@SignalerActivity) }, haut = 16f))
            } else {
                addView(Ui.marge(this@SignalerActivity, Ui.texte(this@SignalerActivity, getString(R.string.signaler_intro), 14f, Ui.TEXTE_2), haut = 12f))
                addView(Ui.marge(this@SignalerActivity, Ui.boutonPrimaire(this@SignalerActivity, getString(R.string.signaler_commencer)) {
                    Garde.exiger(this@SignalerActivity, getString(R.string.signaler_raison)) { demanderCapture() }
                }, haut = 16f))
            }
            for ((ref, _) in Signalement.references(this@SignalerActivity)) {
                addView(Ui.marge(this@SignalerActivity, Ui.boutonSecondaire(this@SignalerActivity, getString(R.string.signaler_effacer, ref)) { effacer(ref) }, haut = 10f))
            }
            addView(Ui.marge(this@SignalerActivity, Ui.boutonSecondaire(this@SignalerActivity, getString(R.string.signaler_fermer)) { finish() }, haut = 10f))
        }
    }

    private fun demanderCapture() {
        try { @Suppress("DEPRECATION") startActivityForResult(CaptureEcran.demande(this), REQ_CAPTURE) }
        catch (e: Exception) { Journal.w("Signalement", "boîte de capture indisponible", e); lancer(false) }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode == RESULT_OK && data != null) { CaptureEcran.demarrer(this, resultCode, data); lancer(true) } else lancer(false)
    }

    private fun lancer(avecCaptures: Boolean) {
        Signalement.commencer()
        Journal.i("Signalement", "début de l'enregistrement (captures : $avecCaptures)")
        PastilleSignalement.poser(this, tele)
        if (!avecCaptures) Ui.toast(this, getString(R.string.signaler_sans_captures))
        finish()
    }

    // ------------------------------------------------------------------ aperçu

    private fun preparer() {
        page { addView(Ui.texte(this@SignalerActivity, getString(R.string.signaler_preparation), 16f, Ui.TEXTE_2)) }
        val app = applicationContext
        val debut = Signalement.debut
        val fin = System.currentTimeMillis()
        Thread {
            val cfg = Config(app)
            var ha = ""
            var remHa = 0
            var noms = JSONObject()
            try {
                val r = Net.post(app, cfg, "/api/pc_parental/diag/periode", JSONObject().put("id", cfg.id).put("secret", cfg.secret)
                    .put("depuis", (debut - 120_000L) / 1000.0).put("jusqua", fin / 1000.0), delaiMs = 20_000)
                val l = r.optJSONArray("lignes") ?: JSONArray()
                ha = (0 until l.length()).joinToString("\n") { l.optString(it) }
                remHa = r.optInt("remplacements")
                noms = r.optJSONObject("noms") ?: JSONObject()
            } catch (e: Exception) { Journal.w("Signalement", "journal de Home Assistant indisponible", e) }
            fun liste(a: JSONArray?) = if (a == null) emptyList() else List(a.length()) { a.optString(it) }
            val anon = Anonymisation(liste(noms.optJSONArray("personnes")), liste(noms.optJSONArray("appareils")))
            val appli = Signalement.journalAppli(debut, fin, anon)
            val octets = Signalement.fichiersCaptures().mapNotNull { try { it.readBytes() } catch (_: Exception) { null } }
            val b = Signalement.Brouillon(debut, fin, appli, ha, Signalement.fiche(app), anon.remplacements + remHa, octets)
            main.post { if (!isFinishing) { brouillon = b; captures = octets.toMutableList(); apercu() } }
        }.start()
    }

    private fun apercu() {
        val b = brouillon ?: return
        val champ = EditText(this)
        page {
            addView(Ui.titre(this@SignalerActivity, getString(R.string.signaler_titre)))
            addView(Ui.marge(this@SignalerActivity, Ui.texte(this@SignalerActivity,
                b.fiche.optString("modele") + " · Android " + b.fiche.optInt("android") + " · Vision " + b.fiche.optString("appli"), 13f, Ui.TEXTE_2), haut = 8f))
            val lignes = (if (b.journalAppli.isEmpty()) 0 else b.journalAppli.count { it == '\n' } + 1) +
                (if (b.journalHa.isEmpty()) 0 else b.journalHa.count { it == '\n' } + 1)
            addView(Ui.marge(this@SignalerActivity, Ui.texte(this@SignalerActivity, getString(R.string.signaler_apercu_journaux, lignes, b.remplacements), 14f, Ui.TEXTE), haut = 8f))
            if (captures.isNotEmpty()) addView(Ui.marge(this@SignalerActivity, Ui.texte(this@SignalerActivity, getString(R.string.signaler_apercu_captures), 13f, Ui.TEXTE_2), haut = 12f))
            captures.forEachIndexed { i, octets ->
                val ligne = Ui.rangee(this@SignalerActivity)
                val vignette = ImageView(this@SignalerActivity).apply {
                    val o = BitmapFactory.Options().apply { inSampleSize = 8 }
                    setImageBitmap(BitmapFactory.decodeByteArray(octets, 0, octets.size, o)); adjustViewBounds = true
                }
                ligne.addView(vignette, LinearLayout.LayoutParams(Ui.dp(this@SignalerActivity, 120f), Ui.dp(this@SignalerActivity, 70f)))
                ligne.addView(Ui.boutonSecondaire(this@SignalerActivity, getString(R.string.signaler_retirer)) { captures.removeAt(i); b.description = champ.text.toString(); apercu() })
                addView(Ui.marge(this@SignalerActivity, ligne, haut = 6f))
            }
            champ.apply {
                hint = getString(R.string.signaler_description_indice); setText(b.description); setTextColor(Ui.TEXTE)
                filters = arrayOf(android.text.InputFilter.LengthFilter(Signalement.MAX_DESCRIPTION)); minLines = 2
            }
            ClavierTele.brancher(champ)
            addView(Ui.marge(this@SignalerActivity, champ, haut = 12f))
            addView(Ui.marge(this@SignalerActivity, Ui.boutonSecondaire(this@SignalerActivity, getString(R.string.signaler_voir)) { voir(b) }, haut = 12f))
            val rangee = Ui.rangeeBoutons(this@SignalerActivity)
            rangee.addView(Ui.boutonPrimaire(this@SignalerActivity, getString(R.string.signaler_envoyer)) { b.description = champ.text.toString(); envoyer(b) })
            rangee.addView(Ui.boutonDanger(this@SignalerActivity, getString(R.string.signaler_jeter)) { jeter() })
            addView(Ui.marge(this@SignalerActivity, rangee, haut = 12f))
        }
    }

    /** Le texte anonymisé, tel qu'il partira (les 300 dernières lignes de chaque journal, pour rester lisible). */
    private fun voir(b: Signalement.Brouillon) {
        fun fin(t: String) = t.lines().takeLast(300).joinToString("\n")
        val texte = "journal_appli.txt\n" + fin(b.journalAppli) + (if (b.journalHa.isEmpty()) "" else "\n\njournal_ha.txt\n" + fin(b.journalHa))
        Ui.dialogue(this).setTitle(R.string.signaler_voir).setMessage(texte).setPositiveButton(R.string.commun_ok, null).show()
    }

    private fun jeter() { Signalement.oublier(); brouillon = null; finish() }

    // ------------------------------------------------------------------ envoi

    private fun envoyer(b: Signalement.Brouillon) {
        enEnvoi = true
        page { addView(Ui.texte(this@SignalerActivity, getString(R.string.signaler_envoi), 16f, Ui.TEXTE_2)) }
        val app = applicationContext
        val complet = Signalement.Brouillon(b.debut, b.fin, b.journalAppli, b.journalHa, b.fiche, b.remplacements, captures.toList(), b.description)
        Thread {
            val resultat = try {
                val zip = File(app.cacheDir, "signalement.zip")
                Signalement.assembler(complet, zip)
                try { Signalement.envoyer(app, complet, zip) } finally { zip.delete() }
            } catch (e: Exception) { Journal.w("Signalement", "envoi impossible", e); null }
            main.post {
                enEnvoi = false
                if (isFinishing) return@post
                if (resultat == null) { Ui.toast(this, getString(R.string.signaler_echec)); apercu() }
                else { Signalement.oublier(); brouillon = null; reference(resultat) }
            }
        }.start()
    }

    private fun reference(r: Signalement.Recu) {
        page {
            addView(Ui.titre(this@SignalerActivity, getString(R.string.signaler_titre)))
            addView(Ui.marge(this@SignalerActivity, Ui.texte(this@SignalerActivity, getString(R.string.signaler_envoye, r.reference, r.conservationJours), 16f, Ui.TEXTE), haut = 12f))
            addView(Ui.marge(this@SignalerActivity, Ui.boutonSecondaire(this@SignalerActivity, getString(R.string.signaler_effacer, r.reference)) { effacer(r.reference) }, haut = 16f))
            addView(Ui.marge(this@SignalerActivity, Ui.boutonPrimaire(this@SignalerActivity, getString(R.string.signaler_fermer)) { finish() }, haut = 10f))
        }
    }

    private fun effacer(ref: String) {
        val app = applicationContext
        Thread {
            val ok = Signalement.effacer(app, ref)
            main.post { if (!isFinishing) { Ui.toast(this, getString(if (ok) R.string.signaler_efface else R.string.signaler_efface_echec)); intro() } }
        }.start()
    }

    companion object {
        const val MODE = "mode"
        const val APERCU = "apercu"
        private const val REQ_CAPTURE = 4211

        fun ouvrir(ctx: Context) { ctx.startActivity(Intent(ctx, SignalerActivity::class.java)) }

        /**
         * Fin de l'enregistrement (pastille, bouton, appui long sur OK) : la pastille part, une dernière capture est prise
         * (sans la pastille dessus), puis l'aperçu s'ouvre. Idempotent.
         */
        fun terminer(ctx: Context) {
            if (!Signalement.enCours) return
            val app = ctx.applicationContext
            PastilleSignalement.retirer()
            Thread {
                try { Thread.sleep(400) } catch (_: InterruptedException) {}
                try { CaptureEcran.capturer(app) } catch (e: Exception) { Journal.w("Signalement", "dernière capture impossible", e) }
                Signalement.terminer()
                CaptureEcran.arreter(app)
                Handler(Looper.getMainLooper()).post {
                    app.startActivity(Intent(app, SignalerActivity::class.java).putExtra(MODE, APERCU)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                }
            }.start()
        }
    }
}
