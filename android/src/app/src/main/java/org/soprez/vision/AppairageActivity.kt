// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONObject

/**
 * Appairage par QR code (Vision 1.63.0 côté Home Assistant, docs/appairage.md).
 *
 * L'appareil demande à être inscrit, affiche un QR et un code court, un administrateur de
 * Home Assistant scanne le QR (ou tape le code) et valide depuis son téléphone ; l'appareil
 * reçoit alors son identifiant et son secret. Aucune clé à saisir, et la clé d'inscription
 * n'apparaît nulle part. Lisible sur une télé : grand QR, code en très grand.
 *
 * `demande` et `attente` ne vivent qu'en mémoire : jamais sur le disque, jamais dans un journal.
 */
class AppairageActivity : Activity() {
    companion object {
        const val EXTRA_ADRESSE = "adresse"
    }

    private val principal = Handler(Looper.getMainLooper())
    private lateinit var racine: LinearLayout
    private lateinit var image: ImageView
    private lateinit var code: TextView
    private lateinit var etat: TextView
    private lateinit var lien: TextView
    private lateinit var boutons: LinearLayout

    private var base = ""
    private var demande = ""
    private var attente = ""
    private var expireA = 0L
    private var intervalle = 3
    private var fini = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Ui.charger(this)
        if (Build.VERSION.SDK_INT >= 21) { window.statusBarColor = Ui.FOND; window.navigationBarColor = Ui.FOND }
        base = (intent.getStringExtra(EXTRA_ADRESSE) ?: "").trimEnd('/')
        val tele = ReglagesTvActivity.estTele(this)
        racine = Ui.colonne(this, Ui.dp(this, if (tele) 32f else 20f)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Ui.FOND)
        }
        racine.addView(Ui.texte(this, getString(R.string.appairageecran_titre), if (tele) 26f else 20f, Ui.TEXTE, gras = true).apply { gravity = Gravity.CENTER })
        racine.addView(Ui.marge(this, Ui.texte(this,
            getString(R.string.appairageecran_explication, Vocabulaire.decideur(this)),
            if (tele) 18f else 14f, Ui.TEXTE_2).apply { gravity = Gravity.CENTER }, haut = 6f))
        // Le QR : au moins 40 % de la hauteur sur une télé, pour se lire à trois mètres.
        val m = resources.displayMetrics
        val cote = if (tele) (m.heightPixels * 0.45f).toInt() else (minOf(m.widthPixels, m.heightPixels) * 0.7f).toInt()
        image = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(cote, cote).apply { topMargin = Ui.dp(this@AppairageActivity, 12f) }
            setBackgroundColor(Color.WHITE)
        }
        racine.addView(image)
        code = Ui.texte(this, "", if (tele) 56f else 40f, Ui.TEXTE, gras = true).apply {
            gravity = Gravity.CENTER; letterSpacing = 0.08f
        }
        racine.addView(Ui.marge(this, code, haut = 8f))
        etat = Ui.texte(this, getString(R.string.appairageecran_connexion), if (tele) 18f else 14f, Ui.TEXTE_2).apply { gravity = Gravity.CENTER }
        racine.addView(etat)
        lien = Ui.texte(this, "", 12f, Ui.TEXTE_3).apply { gravity = Gravity.CENTER }
        racine.addView(Ui.marge(this, lien, haut = 6f))
        boutons = Ui.rangeeBoutons(this).apply { gravity = Gravity.CENTER }
        racine.addView(Ui.marge(this, boutons, haut = 14f))
        setContentView(android.widget.ScrollView(this).apply {
            setBackgroundColor(Ui.FOND); isFillViewport = true; addView(racine)
        })
        if (base.isEmpty()) { echec(getString(R.string.appairageecran_adresse_inconnue)); return }
        demander()
    }

    override fun onDestroy() { fini = true; principal.removeCallbacksAndMessages(null); super.onDestroy() }

    private fun boutons(vararg b: Pair<String, () -> Unit>) {
        boutons.removeAllViews()
        b.forEachIndexed { k, (t, a) ->
            val v = if (k == 0) Ui.boutonPrimaire(this, t, a) else Ui.boutonSecondaire(this, t, a)
            boutons.addView(Ui.marge(this, v, gauche = if (k == 0) 0f else 10f))
            if (k == 0) v.requestFocus()  // télécommande : le bouton utile a le focus
        }
    }

    private fun enFond(travail: () -> Unit) = Thread { try { travail() } catch (_: Exception) {} }.start()

    // -------------------------------------------------------- 1. La demande

    private fun demander() {
        principal.removeCallbacksAndMessages(null)
        demande = ""; attente = ""
        image.setImageBitmap(null); code.text = ""; lien.text = ""
        etat.text = getString(R.string.appairageecran_connexion)
        boutons(getString(R.string.commun_lot2_annuler) to { finish() })
        val corps = Appairage.identite(this)
        enFond {
            try {
                val r = Net.postUne(this, "$base/api/pc_parental/appairage/demande", corps)
                runOnUiThread { if (!fini) afficher(r) }
            } catch (e: Net.Echec) {
                runOnUiThread {
                    when (e.code) {
                        429 -> echec(getString(R.string.appairageecran_trop_demandes))
                        503 -> { etat.text = getString(R.string.appairageecran_demarre); principal.postDelayed({ demander() }, 10_000) }
                        404 -> echec(getString(R.string.appairageecran_pas_qr))
                        else -> echec(getString(R.string.appairageecran_refusee_code, e.code))
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { echec(getString(R.string.appairageecran_injoignable)) }
            }
        }
    }

    private fun afficher(r: JSONObject) {
        demande = r.optString("demande"); attente = r.optString("attente")
        val qr = r.optString("qr"); val c = r.optString("code")
        if (demande.isEmpty() || attente.isEmpty() || qr.isEmpty() || c.isEmpty()) { echec(getString(R.string.appairageecran_reponse_inattendue)); return }
        intervalle = r.optInt("intervalle", 3).coerceIn(2, 10)
        expireA = System.currentTimeMillis() + r.optInt("expire_dans", 300) * 1000L
        image.setImageBitmap(dessinerQr(qr, image.layoutParams.width))
        code.text = if (c.length == 6) c.substring(0, 3) + " " + c.substring(3) else c
        lien.text = r.optString("lien")
        boutons(getString(R.string.commun_lot2_annuler) to { finish() }, getString(R.string.appairageecran_nouveau_code) to { demander() })
        compteARebours()
        principal.postDelayed({ attendre() }, intervalle * 1000L)
    }

    private fun compteARebours() {
        if (fini || demande.isEmpty()) return
        val reste = ((expireA - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
        etat.text = getString(R.string.appairageecran_attente_validation, reste / 60, reste % 60)
        if (reste > 0) principal.postDelayed({ compteARebours() }, 1000)
    }

    // -------------------------------------------------------- 2. L'attente

    private fun attendre() {
        if (fini || demande.isEmpty()) return
        val corps = JSONObject().put("demande", demande).put("attente", attente)
        enFond {
            try {
                val r = Net.postUne(this, "$base/api/pc_parental/appairage/attente", corps)
                runOnUiThread { if (!fini) reponse(r) }
            } catch (e: Net.Echec) {
                // 404 : demande oubliée (redémarrage de Home Assistant, réponse déjà lue) : on recommence.
                runOnUiThread { if (!fini) { if (e.code == 404) termine(getString(R.string.appairageecran_demande_perdue)) else principal.postDelayed({ attendre() }, intervalle * 1000L) } }
            } catch (_: Exception) {
                // Coupure passagère : on réessaie au prochain tour.
                runOnUiThread { if (!fini) principal.postDelayed({ attendre() }, intervalle * 1000L) }
            }
        }
    }

    private fun reponse(r: JSONObject) {
        when (r.optString("etat")) {
            "en_attente" -> {
                r.optInt("expire_dans", -1).takeIf { it >= 0 }?.let { expireA = System.currentTimeMillis() + it * 1000L }
                principal.postDelayed({ attendre() }, intervalle * 1000L)
            }
            "validee" -> valide(r)
            "refusee" -> termine(getString(R.string.appairageecran_demande_refusee))
            "expiree" -> termine(getString(R.string.appairageecran_code_expire))
            else -> principal.postDelayed({ attendre() }, intervalle * 1000L)
        }
    }

    private fun valide(r: JSONObject) {
        demande = ""; attente = ""
        // Exactement comme après une inscription par clé.
        if (!Appairage.enregistrer(this, base, "", r)) { termine(getString(R.string.appairageecran_reponse_incomplete)); return }
        principal.removeCallbacksAndMessages(null)
        image.setImageBitmap(null); code.text = ""; lien.text = ""
        etat.text = getString(R.string.appairageecran_connecte, r.optString("name").ifEmpty { getString(R.string.messageecran_cet_appareil) })
        boutons(getString(R.string.appairageecran_continuer) to { finish() })
        principal.postDelayed({ if (!fini) finish() }, 2500)
    }

    private fun termine(message: String) {
        principal.removeCallbacksAndMessages(null)
        demande = ""; attente = ""
        image.setImageBitmap(null); code.text = ""; lien.text = ""
        etat.text = message
        boutons(getString(R.string.appairageecran_nouveau_code) to { demander() }, getString(R.string.commun_lot2_annuler) to { finish() })
    }

    private fun echec(message: String) {
        principal.removeCallbacksAndMessages(null)
        etat.text = message
        boutons(getString(R.string.editeurwidget_reessayer) to { demander() }, getString(R.string.commun_lot2_annuler) to { finish() })
    }

    /** Le QR du contenu donné par Home Assistant, en noir sur blanc, avec une marge d'un module. */
    private fun dessinerQr(contenu: String, cote: Int): Bitmap? = try {
        val taille = cote.coerceAtLeast(200)
        val m = QRCodeWriter().encode(contenu, BarcodeFormat.QR_CODE, taille, taille, mapOf(EncodeHintType.MARGIN to 1))
        val px = IntArray(taille * taille)
        for (y in 0 until taille) for (x in 0 until taille) px[y * taille + x] = if (m[x, y]) Color.BLACK else Color.WHITE
        Bitmap.createBitmap(px, taille, taille, Bitmap.Config.RGB_565)
    } catch (_: Exception) { null }
}
