// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONObject

/**
 * La liaison de cet appareil à Home Assistant (Vision 1.63.0 côté Home Assistant, docs/appairage.md).
 *
 * 1.90.4 / HA 2.4.7 (décision du projet : « aucun code à l'écran ») : l'appareil demande à être relié et affiche
 * seulement « En attente de validation… », l'œil de Vision et son nom. La demande arrive sur le téléphone d'un
 * {décideur} (« Nouvel appareil » : Accepter / Refuser, à qui est l'appareil). Le QR et le code ne sont plus qu'un
 * secours, derrière « Autre méthode » (un {décideur} sans l'appli, ou aucun téléphone de {décideur}).
 *
 * `demande` et `attente` ne vivent qu'en mémoire : jamais sur le disque, jamais dans un journal.
 */
class AppairageActivity : Activity() {
    companion object {
        const val EXTRA_ADRESSE = "adresse"
    }

    private val principal = Handler(Looper.getMainLooper())
    private lateinit var racine: LinearLayout
    private lateinit var oeil: ImageView
    private lateinit var titre: TextView
    private lateinit var nom: TextView
    private lateinit var explication: TextView
    private lateinit var secours: LinearLayout
    private lateinit var image: ImageView
    private lateinit var code: TextView
    private lateinit var lien: TextView
    private lateinit var etat: TextView
    private lateinit var boutons: LinearLayout
    private var respire: ValueAnimator? = null

    private var base = ""
    private var demande = ""
    private var attente = ""
    private var expireA = 0L
    private var intervalle = 3
    private var fini = false
    /** La réponse de la dernière demande (le QR et le code, montrés seulement par « Autre méthode »). */
    private var derniere: JSONObject? = null
    /** Le secours (QR et code) est-il montré ? */
    var autreMethode = false; private set

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Ui.charger(this)
        if (Build.VERSION.SDK_INT >= 21) { window.statusBarColor = Ui.FOND; window.navigationBarColor = Ui.FOND }
        base = (intent.getStringExtra(EXTRA_ADRESSE) ?: "").trimEnd('/')
        val tele = ReglagesTvActivity.estTele(this)
        racine = Ui.colonne(this, Ui.dp(this, if (tele) 24f else 20f)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Ui.FOND)
        }
        // L'œil de Vision, qui « respire » et cligne de temps en temps pendant l'attente.
        val d = Ui.dp(this, if (tele) 80f else 84f)
        oeil = ImageView(this).apply { setImageResource(R.drawable.ic_vision); contentDescription = null; tag = "oeil" }
        racine.addView(oeil, LinearLayout.LayoutParams(d, d).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = Ui.dp(this@AppairageActivity, 10f) })
        titre = Ui.texte(this, getString(R.string.appairageecran_connexion), if (tele) 26f else 20f, Ui.TEXTE, gras = true).apply { gravity = Gravity.CENTER; tag = "titre" }
        racine.addView(titre)
        nom = Ui.texte(this, Appairage.identite(this).optString("nom"), if (tele) 20f else 16f, Ui.TEXTE).apply { gravity = Gravity.CENTER; tag = "nom" }
        racine.addView(Ui.marge(this, nom, haut = 6f))
        explication = Ui.texte(this, "", if (tele) 18f else 14f, Ui.TEXTE_2).apply { gravity = Gravity.CENTER; tag = "explication" }
        racine.addView(Ui.marge(this, explication, haut = 10f))
        // Le secours (« Autre méthode ») : QR et code, cachés tant qu'on ne les demande pas. Sur une télé (paysage), le
        // QR à gauche et le texte à droite, pour que tout tienne sans défiler (720p, police 130 %).
        secours = (if (tele) Ui.rangee(this) else Ui.colonne(this)).apply { gravity = Gravity.CENTER; visibility = View.GONE; tag = "secours" }
        val texteSecours = Ui.colonne(this).apply { gravity = Gravity.CENTER_HORIZONTAL }
        texteSecours.addView(Ui.texte(this, getString(R.string.appairageecran_explication, Vocabulaire.decideur(this)),
            if (tele) 18f else 14f, Ui.TEXTE_2).apply { gravity = Gravity.CENTER })
        // Le QR : au moins 40 % de la hauteur sur une télé, pour se lire à trois mètres.
        val m = resources.displayMetrics
        val cote = if (tele) (m.heightPixels * 0.42f).toInt() else (minOf(m.widthPixels, m.heightPixels) * 0.7f).toInt()
        image = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(cote, cote).apply { topMargin = Ui.dp(this@AppairageActivity, if (tele) 0f else 12f) }
            setBackgroundColor(Color.WHITE)
        }
        code = Ui.texte(this, "", if (tele) 48f else 40f, Ui.TEXTE, gras = true).apply { gravity = Gravity.CENTER; letterSpacing = 0.08f; tag = "code" }
        lien = Ui.texte(this, "", 12f, Ui.TEXTE_3).apply { gravity = Gravity.CENTER }
        if (tele) {
            secours.addView(image)
            texteSecours.addView(Ui.marge(this, code, haut = 8f))
            texteSecours.addView(Ui.marge(this, lien, haut = 6f))
            secours.addView(texteSecours, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = Ui.dp(this@AppairageActivity, 24f) })
        } else {
            secours.addView(texteSecours)
            secours.addView(image)
            secours.addView(Ui.marge(this, code, haut = 8f))
            secours.addView(Ui.marge(this, lien, haut = 6f))
        }
        racine.addView(Ui.marge(this, secours, haut = 8f))
        etat = Ui.texte(this, "", if (tele) 16f else 13f, Ui.TEXTE_3).apply { gravity = Gravity.CENTER; tag = "etat" }
        racine.addView(Ui.marge(this, etat, haut = 10f))
        boutons = Ui.rangeeBoutons(this).apply { gravity = Gravity.CENTER }
        racine.addView(Ui.marge(this, boutons, haut = 14f))
        setContentView(android.widget.ScrollView(this).apply {
            setBackgroundColor(Ui.FOND); isFillViewport = true; addView(racine)
        })
        animerOeil()
        if (base.isEmpty()) { echec(getString(R.string.appairageecran_adresse_inconnue)); return }
        demander()
    }

    override fun onDestroy() {
        fini = true; principal.removeCallbacksAndMessages(null)
        respire?.cancel(); respire = null
        super.onDestroy()
    }

    private val clin = object : Runnable {
        override fun run() {
            if (fini) return
            ObjectAnimator.ofFloat(oeil, "scaleY", 1f, 0.12f, 1f).apply { duration = 260 }.start()
            oeil.postDelayed(this, 4200)
        }
    }

    private fun animerOeil() {
        respire = ObjectAnimator.ofFloat(oeil, "alpha", 1f, 0.72f).apply {
            duration = 1600; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE; start()
        }
        oeil.postDelayed(clin, 2500)
    }

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
        demande = ""; attente = ""; derniere = null
        montrerSecours(false)
        titre.text = getString(R.string.appairageecran_connexion); explication.text = ""; etat.text = ""
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
                        503 -> { titre.text = getString(R.string.appairageecran_demarre); principal.postDelayed({ demander() }, 10_000) }
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
        if (demande.isEmpty() || attente.isEmpty()) { echec(getString(R.string.appairageecran_reponse_inattendue)); return }
        derniere = r
        intervalle = r.optInt("intervalle", 3).coerceIn(2, 10)
        expireA = System.currentTimeMillis() + r.optInt("expire_dans", 600) * 1000L
        r.optString("nom").takeIf { it.isNotEmpty() }?.let { nom.text = it }
        titre.text = getString(R.string.liaison_sans_code_attente)
        // Une intégration d'avant 2.4.7 ne dit pas « decideurs » : son seul moyen est le code (on le montre alors).
        val decideurs = if (r.has("decideurs")) r.optInt("decideurs") else -1
        explication.text = if (decideurs > 0) getString(R.string.liaison_sans_code_explication, Vocabulaire.decideur(this))
            else getString(R.string.liaison_sans_code_aucun, Vocabulaire.decideur(this))
        if (decideurs < 0) montrerSecours(true) else boutonsAttente()
        compteARebours()
        principal.postDelayed({ attendre() }, intervalle * 1000L)
    }

    private fun boutonsAttente() {
        if (autreMethode) boutons(getString(R.string.commun_lot2_annuler) to { finish() }, getString(R.string.appairageecran_nouveau_code) to { demander() })
        else boutons(getString(R.string.commun_lot2_annuler) to { finish() }, getString(R.string.liaison_sans_code_autre_methode) to { montrerSecours(true) })
    }

    /** « Autre méthode » : le QR et le code de la demande en cours (secours). */
    private fun montrerSecours(oui: Boolean) {
        val r = derniere
        autreMethode = oui && r != null
        if (!autreMethode) {
            secours.visibility = View.GONE; image.setImageBitmap(null); code.text = ""; lien.text = ""
            oeil.visibility = View.VISIBLE; nom.visibility = View.VISIBLE; explication.visibility = View.VISIBLE
            return
        }
        val qr = r!!.optString("qr"); val c = r.optString("code")
        image.setImageBitmap(if (qr.isNotEmpty()) dessinerQr(qr, image.layoutParams.width) else null)
        code.text = if (c.length == 6) c.substring(0, 3) + " " + c.substring(3) else c
        lien.text = r.optString("lien")
        secours.visibility = View.VISIBLE
        // La place pour le QR : l'œil, le nom et l'explication s'effacent (le secours a son propre texte).
        oeil.visibility = View.GONE; nom.visibility = View.GONE; explication.visibility = View.GONE
        if (demande.isNotEmpty()) boutonsAttente()
    }

    private fun compteARebours() {
        if (fini || demande.isEmpty()) return
        val reste = ((expireA - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
        etat.text = getString(R.string.liaison_sans_code_valable, reste / 60, reste % 60)
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
            "expiree" -> termine(getString(R.string.liaison_sans_code_expiree))
            "remplacee" -> termine(getString(R.string.liaison_sans_code_remplacee))
            else -> principal.postDelayed({ attendre() }, intervalle * 1000L)
        }
    }

    private fun valide(r: JSONObject) {
        demande = ""; attente = ""
        // Exactement comme après une inscription par clé.
        if (!Appairage.enregistrer(this, base, "", r)) { termine(getString(R.string.appairageecran_reponse_incomplete)); return }
        principal.removeCallbacksAndMessages(null)
        montrerSecours(false)
        titre.text = getString(R.string.appairageecran_connecte, r.optString("name").ifEmpty { getString(R.string.messageecran_cet_appareil) })
        explication.text = ""; etat.text = ""
        boutons(getString(R.string.appairageecran_continuer) to { finish() })
        principal.postDelayed({ if (!fini) finish() }, 2500)
    }

    private fun termine(message: String) {
        principal.removeCallbacksAndMessages(null)
        demande = ""; attente = ""
        montrerSecours(false)
        titre.text = message; explication.text = ""; etat.text = ""
        boutons(getString(R.string.liaison_sans_code_redemander) to { demander() }, getString(R.string.commun_lot2_annuler) to { finish() })
    }

    private fun echec(message: String) {
        principal.removeCallbacksAndMessages(null)
        montrerSecours(false)
        titre.text = message; explication.text = ""; etat.text = ""
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
