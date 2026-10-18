// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONObject

/**
 * Les demandes de déverrouillage des enfants, sur l'appareil d'un parent (étiquette « Parents »,
 * docs/deverrouillage.md §2). Approuver (en raccourcissant si besoin) par remise directe, code à
 * 6 chiffres à lire à l'enfant, ou QR qu'il scanne ; ou refuser. Le code et le QR ne sont donnés
 * qu'une fois : on les montre tout de suite, en grand.
 */
class DeverrouillageParentActivity : Activity() {
    companion object {
        fun ouvrir(ctx: Context) = ctx.startActivity(Intent(ctx, DeverrouillageParentActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        fun intention(ctx: Context): Intent = Intent(ctx, DeverrouillageParentActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    private val principal = Handler(Looper.getMainLooper())
    private lateinit var racine: LinearLayout
    private val cfg by lazy { Config(this) }
    private var fini = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        // Module « déverrouillage » coupé dans Home Assistant : plus de demandes à traiter ici.
        if (!Modules.actif(this, Modules.DEVERROUILLAGE)) { finish(); return }
        Ui.charger(this)
        if (Build.VERSION.SDK_INT >= 21) { window.statusBarColor = Ui.FOND; window.navigationBarColor = Ui.FOND }
        racine = Ui.colonne(this, Ui.dp(this, 20f)).apply { setBackgroundColor(Ui.FOND) }
        setContentView(android.widget.ScrollView(this).apply { setBackgroundColor(Ui.FOND); isFillViewport = true; addView(racine) })
        charger()
    }

    override fun onDestroy() { fini = true; principal.removeCallbacksAndMessages(null); super.onDestroy() }

    private fun entete(sous: String) {
        racine.removeAllViews()
        racine.addView(Ui.texte(this, getString(R.string.dpa_titre), 22f, Ui.TEXTE, gras = true))
        racine.addView(Ui.marge(this, Ui.texte(this, sous, 14f, Ui.TEXTE_2), haut = 6f))
    }

    private fun charger() {
        entete(getString(R.string.commun_chargement))
        Thread {
            val r = try { Result.success(Net.post(this, cfg, "/api/pc_parental/deverrouillage/liste", JSONObject().put("id", cfg.id).put("secret", cfg.secret))) }
                    catch (e: Exception) { Result.failure(e) }
            runOnUiThread { if (!fini) r.onSuccess { liste(it) }.onFailure { e ->
                entete(if (e is Net.Echec && e.code == 403) getString(R.string.dpa_pas_de_droits)
                       else getString(R.string.dpa_injoignable))
                racine.addView(Ui.marge(this, Ui.boutonSecondaire(this, getString(R.string.commun_reessayer)) { charger() }, haut = 16f))
            } }
        }.start()
    }

    private fun liste(r: JSONObject) {
        val a = r.optJSONArray("demandes")
        val n = a?.length() ?: 0
        entete(if (n == 0) getString(R.string.dpa_aucune) else getString(R.string.dpa_choisis))
        for (i in 0 until n) {
            val d = a!!.optJSONObject(i) ?: continue
            val c = Ui.carte(this)
            val qui = d.optString("personne").ifEmpty { d.optString("nom") }
            c.addView(Ui.texte(this, "$qui · ${d.optString("nom")}", 17f, Ui.TEXTE, gras = true))
            c.addView(Ui.marge(this, Ui.texte(this, getString(R.string.dpa_demandees, Deverrouillage.libelle(this, d.optString("action")).replaceFirstChar { it.uppercase() }, d.optInt("minutes")), 14f, Ui.TEXTE_2), haut = 4f))
            d.optString("motif").takeIf { it.isNotEmpty() }?.let { c.addView(Ui.marge(this, Ui.texte(this, getString(R.string.dpa_motif, it), 14f, Ui.OR), haut = 4f)) }
            // Validation à plusieurs (Vision 1.72.0) : qui a déjà validé, qui manque.
            val dejaVote = d.optBoolean("deja_vote")
            // Module « double validation » coupé : pas de « 1/2 ».
            if (d.has("requis") && Modules.actif(this, Modules.DOUBLE_VALIDATION)) {
                val par = d.optJSONArray("approuve_par")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
                val attente = Deverrouillage.enAttenteDe(d)
                val t = when {
                    dejaVote -> if (attente.isNotEmpty()) getString(R.string.dpa_tu_as_valide_attente, d.optInt("voix"), d.optInt("requis"), attente)
                        else getString(R.string.dpa_tu_as_valide, d.optInt("voix"), d.optInt("requis"))
                    par.isNotEmpty() -> getString(R.string.dpa_a_valide, par.reduce { x, y -> getString(R.string.dpa_et, x, y) }, d.optInt("voix"), d.optInt("requis"))
                    else -> resources.getQuantityString(R.plurals.dpa_il_faut, d.optInt("requis"), d.optInt("requis"))
                }
                c.addView(Ui.marge(this, Ui.texte(this, t, 14f, Ui.OR), haut = 6f))
            }
            // Durée : celle demandée, ou moins (jamais plus).
            val demandees = d.optInt("minutes")
            var minutes = demandees
            val options = listOf(demandees, 30, 15, 5).filter { it in 1..demandees }.distinct().sortedDescending()
            val etiquette = Ui.texte(this, "", 14f, Ui.TEXTE_2)
            fun majEtiquette() { etiquette.text = getString(R.string.dpa_duree_accordee, minutes) }
            // 15-10 (règle UX de l'auteur) : la durée choisie se voit (étiquette pleine, ou liste au-delà de 3 durées).
            val choixDuree = Ui.choix(this, getString(R.string.dpa_duree_accordee, minutes).substringBefore(":").trim().ifEmpty { getString(R.string.dpa_deverrouiller) },
                options.map { m -> ChoixAdaptatif.Option(m.toString(), getString(R.string.tr_duree_min, m)) }, minutes.toString()) {
                minutes = it.toIntOrNull() ?: minutes; majEtiquette()
            }.apply { tag = "dpa_duree" }
            majEtiquette()
            if (!dejaVote) {
                c.addView(Ui.marge(this, choixDuree, haut = 10f)); c.addView(Ui.marge(this, etiquette, haut = 4f))
                val actions = Ui.rangeeBoutons(this)
                actions.addView(Ui.poids(Ui.boutonPrimaire(this, getString(R.string.dpa_deverrouiller)) { decider(d, "approuver", minutes, "directe") }))
                actions.addView(Ui.marge(this, Ui.poids(Ui.boutonSecondaire(this, getString(R.string.dpa_code)) { decider(d, "approuver", minutes, "code") }), gauche = 8f))
                actions.addView(Ui.marge(this, Ui.poids(Ui.boutonSecondaire(this, getString(R.string.dpa_qr)) { decider(d, "approuver", minutes, "qr") }), gauche = 8f))
                c.addView(Ui.marge(this, actions, haut = 12f))
            }
            c.addView(Ui.marge(this, Ui.boutonSecondaire(this, getString(R.string.dpa_refuser)) { decider(d, "refuser", minutes, "directe") }, haut = 8f))
            racine.addView(Ui.marge(this, c, haut = 14f))
        }
        racine.addView(Ui.marge(this, Ui.boutonSecondaire(this, getString(R.string.dpa_actualiser)) { charger() }, haut = 16f))
    }

    private fun decider(d: JSONObject, decision: String, minutes: Int, remise: String) =
        Garde.exigerPour(this, getString(R.string.dpa_raison), "/api/pc_parental/deverrouillage/decision",
            org.json.JSONObject().put("decision", decision)) { deciderVraiment(d, decision, minutes, remise) }

    private fun deciderVraiment(d: JSONObject, decision: String, minutes: Int, remise: String) {
        val corps = JSONObject().put("id", cfg.id).put("secret", cfg.secret).put("demande", d.optString("demande"))
            .put("decision", decision).put("minutes", minutes).put("remise", remise)
        Thread {
            val r = try { Result.success(Net.post(this, cfg, "/api/pc_parental/deverrouillage/decision", corps)) } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                if (fini) return@runOnUiThread
                r.onSuccess { rep -> resultat(d, rep) }.onFailure { e ->
                    Ui.toast(this, when {
                        e is Net.Echec && e.code == 404 -> getString(R.string.dpa_plus_en_attente)
                        e is Net.Echec && e.code == 409 -> getString(R.string.dpa_deja_valide)
                        else -> getString(R.string.dpa_refuse_ha)
                    }, genre = OeilModele.Genre.ERREUR)
                    charger()
                }
            }
        }.start()
    }

    private fun resultat(d: JSONObject, r: JSONObject) {
        val qui = d.optString("personne").ifEmpty { d.optString("nom") }
        when {
            r.optString("etat") == "partielle" -> {
                val attente = Deverrouillage.enAttenteDe(r)
                val enregistree = if (Modules.actif(this, Modules.DOUBLE_VALIDATION)) getString(R.string.dpa_enregistree_compte, r.optInt("voix"), r.optInt("requis"))
                    else getString(R.string.dpa_enregistree)
                entete(if (attente.isNotEmpty()) getString(R.string.dpa_partielle_attente, enregistree, attente) else getString(R.string.dpa_partielle, enregistree))
                racine.addView(Ui.marge(this, Ui.boutonPrimaire(this, getString(R.string.dpa_autres)) { charger() }, haut = 16f))
            }
            r.optString("etat") == "refusee" -> { entete(getString(R.string.dpa_refuse_qui, qui)); racine.addView(Ui.marge(this, Ui.boutonPrimaire(this, getString(R.string.dpa_autres)) { charger() }, haut = 16f)) }
            r.optString("remise") == "code" -> {
                val c = r.optString("code")
                entete(getString(R.string.dpa_lis_code, qui))
                racine.addView(Ui.marge(this, Ui.texte(this, "${c.take(3)} ${c.drop(3)}", 64f, Ui.OR, gras = true, centre = true), haut = 30f))
                racine.addView(Ui.marge(this, Ui.boutonPrimaire(this, getString(R.string.commun_terminer)) { charger() }, haut = 30f))
            }
            r.optString("remise") == "qr" -> {
                entete(getString(R.string.dpa_scanne_qr, qui))
                val cote = (minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * 0.8f).toInt()
                val img = ImageView(this).apply { setBackgroundColor(Color.WHITE); setImageBitmap(qr(r.optString("qr"), cote)); contentDescription = getString(R.string.a11y_qr_deverrouillage) }
                racine.addView(img, LinearLayout.LayoutParams(cote, cote).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = Ui.dp(this@DeverrouillageParentActivity, 16f) })
                val reste = Ui.texte(this, "", 16f, Ui.TEXTE_2, centre = true)
                racine.addView(Ui.marge(this, reste, haut = 10f))
                val fin = r.optLong("expire") * 1000L
                val tic = object : Runnable { override fun run() {
                    val s = ((fin - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                    reste.text = if (s > 0) getString(R.string.dpa_valable, s) else getString(R.string.dpa_qr_expire)
                    if (s > 0 && !fini) principal.postDelayed(this, 1000) else img.setImageBitmap(null)
                } }
                tic.run()
                racine.addView(Ui.marge(this, Ui.boutonPrimaire(this, getString(R.string.commun_terminer)) { principal.removeCallbacksAndMessages(null); charger() }, haut = 16f))
            }
            else -> { entete(getString(R.string.dpa_deverrouille, qui, r.optInt("minutes"))); racine.addView(Ui.marge(this, Ui.boutonPrimaire(this, getString(R.string.dpa_autres)) { charger() }, haut = 16f)) }
        }
    }

    private fun qr(contenu: String, cote: Int): Bitmap? = try {
        val m = QRCodeWriter().encode(contenu, BarcodeFormat.QR_CODE, cote, cote, mapOf(EncodeHintType.MARGIN to 1))
        val px = IntArray(cote * cote)
        for (y in 0 until cote) for (x in 0 until cote) px[y * cote + x] = if (m[x, y]) Color.BLACK else Color.WHITE
        Bitmap.createBitmap(px, cote, cote, Bitmap.Config.RGB_565)
    } catch (_: Exception) { null }
}
