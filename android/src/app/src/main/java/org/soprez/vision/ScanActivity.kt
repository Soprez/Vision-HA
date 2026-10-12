// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Scanner le QR d'appairage affiché par Home Assistant (Vision 1.65.0, docs/appairage.md,
 * « Dans l'autre sens »). Le QR porte l'adresse et un jeton à usage unique valable 5 minutes ;
 * l'appareil l'échange contre son identifiant et son secret, sans clé ni attente.
 *
 * La caméra n'est demandée qu'ici, au moment de scanner. Sans caméra (télé) ou sans
 * autorisation, l'écran renvoie vers les autres façons de relier l'appareil.
 * Le jeton n'est jamais affiché, écrit ni journalisé.
 */
class ScanActivity : Activity(), LifecycleOwner {
    companion object {
        private const val DEMANDE_CAMERA = 41
        const val EXTRA_MODE = "mode"
        const val MODE_DEVERROUILLAGE = "deverrouillage"
    }

    private val cycle = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = cycle

    private val principal = Handler(Looper.getMainLooper())
    private val analyse = Executors.newSingleThreadExecutor()
    private val lu = AtomicBoolean(false)
    private val lecteur = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true))
    }
    private var fournisseur: ProcessCameraProvider? = null
    private var dernierAvis = ""
    private var fini = false

    private lateinit var apercu: PreviewView
    private lateinit var etat: TextView
    private lateinit var boutons: LinearLayout

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        cycle.currentState = Lifecycle.State.CREATED
        Ui.charger(this)
        if (Build.VERSION.SDK_INT >= 21) { window.statusBarColor = Ui.FOND; window.navigationBarColor = Ui.FOND }
        val racine = Ui.colonne(this, Ui.dp(this, 20f)).apply { gravity = Gravity.CENTER_HORIZONTAL; setBackgroundColor(Ui.FOND) }
        racine.addView(Ui.texte(this, getString(R.string.scan_titre), 20f, Ui.TEXTE, gras = true).apply { gravity = Gravity.CENTER })
        racine.addView(Ui.marge(this, Ui.texte(this,
            getString(R.string.scan_explication),
            14f, Ui.TEXTE_2).apply { gravity = Gravity.CENTER }, haut = 6f))
        val m = resources.displayMetrics
        val cote = (minOf(m.widthPixels, m.heightPixels) * 0.8f).toInt()
        apercu = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        racine.addView(FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(cote, cote).apply { topMargin = Ui.dp(this@ScanActivity, 12f) }
            addView(apercu, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        })
        etat = Ui.texte(this, "", 14f, Ui.TEXTE_2).apply { gravity = Gravity.CENTER }
        racine.addView(Ui.marge(this, etat, haut = 10f))
        boutons = Ui.rangeeBoutons(this).apply { gravity = Gravity.CENTER }
        racine.addView(Ui.marge(this, boutons, haut = 14f))
        setContentView(android.widget.ScrollView(this).apply { setBackgroundColor(Ui.FOND); isFillViewport = true; addView(racine) })

        if (!Appairage.aUneCamera(this)) {
            echec(getString(R.string.scan_pas_de_camera))
            return
        }
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            etat.text = getString(R.string.scan_besoin_camera)
            requestPermissions(arrayOf(Manifest.permission.CAMERA), DEMANDE_CAMERA)
        } else demarrerCamera()
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, resultats: IntArray) {
        super.onRequestPermissionsResult(code, permissions, resultats)
        if (code != DEMANDE_CAMERA) return
        if (resultats.firstOrNull() == PackageManager.PERMISSION_GRANTED) demarrerCamera()
        else echec(getString(R.string.scan_sans_camera))
    }

    override fun onStart() { super.onStart(); cycle.currentState = Lifecycle.State.STARTED }
    override fun onResume() { super.onResume(); cycle.currentState = Lifecycle.State.RESUMED }
    override fun onPause() { cycle.currentState = Lifecycle.State.STARTED; super.onPause() }
    override fun onStop() { cycle.currentState = Lifecycle.State.CREATED; super.onStop() }
    override fun onDestroy() {
        fini = true
        principal.removeCallbacksAndMessages(null)
        fournisseur?.unbindAll()
        analyse.shutdown()
        cycle.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    private fun boutons(vararg b: Pair<String, () -> Unit>) {
        boutons.removeAllViews()
        b.forEachIndexed { k, (t, a) ->
            val v = if (k == 0) Ui.boutonPrimaire(this, t, a) else Ui.boutonSecondaire(this, t, a)
            boutons.addView(Ui.marge(this, v, gauche = if (k == 0) 0f else 10f))
        }
    }

    // -------------------------------------------------------- La caméra

    private fun demarrerCamera() {
        etat.text = getString(R.string.scan_vise_qr)
        boutons(getString(R.string.commun_lot2_annuler) to { finish() })
        lu.set(false)
        val futur = ProcessCameraProvider.getInstance(this)
        futur.addListener({
            if (fini) return@addListener
            val f = try { futur.get() } catch (_: Exception) { echec(getString(R.string.scan_camera_ne_repond_pas)); return@addListener }
            fournisseur = f
            val vue = Preview.Builder().build().also { it.setSurfaceProvider(apercu.surfaceProvider) }
            val lecture = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also { it.setAnalyzer(analyse) { image -> lire(image) } }
            try {
                f.unbindAll()
                val selecteur = if (f.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                f.bindToLifecycle(this, selecteur, vue, lecture)
            } catch (_: Exception) {
                echec(getString(R.string.scan_camera_impossible))
            }
        }, mainExecutor())
    }

    private fun mainExecutor() = java.util.concurrent.Executor { principal.post(it) }

    /** Une image de la caméra : on n'en lit que la luminance (plan Y), le QR suffit. */
    private fun lire(image: ImageProxy) {
        try {
            if (lu.get()) return
            val plan = image.planes[0]
            val tampon = plan.buffer
            val octets = ByteArray(tampon.remaining()).also { tampon.get(it) }
            val source = PlanarYUVLuminanceSource(octets, plan.rowStride, image.height, 0, 0, image.width, image.height, false)
            val texte = try {
                lecteur.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
            } catch (_: NotFoundException) { null } finally { lecteur.reset() }
            if (texte != null) principal.post { lu(texte) }
        } catch (_: Exception) {
        } finally {
            image.close()
        }
    }

    private fun lu(texte: String) {
        if (fini || lu.get()) return
        if (Deverrouillage.estQr(texte)) {
            lu.set(true); fournisseur?.unbindAll()
            // Réussi = accepter() a rendu son message sans lever (le texte, traduit, ne sert plus de test).
            var reussi = false
            val msg = try { Deverrouillage.depuisQr(texte)?.let { Deverrouillage.accepter(this, it).also { reussi = true } } ?: getString(R.string.scan_qr_illisible) }
                      catch (e: Deverrouillage.Echec) { e.raison }
            etat.text = msg
            if (reussi) { boutons(getString(R.string.appairageecran_continuer) to { finish() }); principal.postDelayed({ if (!fini) finish() }, 2500) }
            else boutons(getString(R.string.editeurwidget_reessayer) to { lu.set(false); demarrerCamera() }, getString(R.string.commun_lot2_fermer) to { finish() })
            return
        }
        if (intent.getStringExtra(EXTRA_MODE) == MODE_DEVERROUILLAGE) { avis(getString(R.string.scan_vise_qr_deverrouillage, Vocabulaire.decideur(this))); return }
        when (val l = Appairage.lire(texte)) {
            is Appairage.Lecture.Valide -> { lu.set(true); fournisseur?.unbindAll(); relier(l.invitation, 0) }
            Appairage.Lecture.PasUnQrVision -> avis(getString(R.string.scan_pas_qr_vision))
            Appairage.Lecture.VersionInconnue -> { lu.set(true); fournisseur?.unbindAll(); echec(getString(R.string.scan_version_recente)) }
            Appairage.Lecture.Incomplet -> avis(getString(R.string.scan_qr_incomplet))
        }
    }

    /** Un avis tant qu'on scanne, sans s'arrêter (on peut viser un autre QR). */
    private fun avis(message: String) {
        if (message == dernierAvis) return
        dernierAvis = message
        etat.text = message
    }

    // -------------------------------------------------------- L'échange du jeton

    /** L'adresse locale d'abord (Wi-Fi de la maison), puis l'adresse à retenir. */
    private fun relier(inv: Appairage.Invitation, essai: Int) {
        etat.text = getString(R.string.scan_connexion_a, inv.adresse)
        boutons(getString(R.string.commun_lot2_annuler) to { finish() })
        val corps = Appairage.identite(this).put("jeton", inv.jeton)
        val adresses = listOf(inv.locale, inv.adresse).filter { it.isNotEmpty() }.distinct()
        Thread {
            var resultat: () -> Unit = { echec(getString(R.string.appairageecran_injoignable)) }
            for ((k, base) in adresses.withIndex()) {
                try {
                    val r = Net.postUne(this, "$base/api/pc_parental/appairage/jeton", corps, if (k < adresses.size - 1) 4000 else 8000)
                    resultat = { relie(inv, r) }
                    break
                } catch (e: Net.Echec) {
                    resultat = when (e.code) {
                        404 -> { { echec(getString(R.string.scan_qr_plus_valable)) } }
                        409 -> { { echec(getString(R.string.scan_deja_relie)) } }
                        503 -> { { if (essai < 3) { etat.text = getString(R.string.appairageecran_demarre); principal.postDelayed({ if (!fini) relier(inv, essai + 1) }, 10_000) } else echec(getString(R.string.scan_ne_repond_pas)) } }
                        else -> { { echec(getString(R.string.scan_refuse_code, e.code)) } }
                    }
                    break  // le serveur a répondu : inutile d'essayer l'autre adresse
                } catch (_: Exception) {
                    // Injoignable par cette adresse : on essaie la suivante.
                }
            }
            runOnUiThread { if (!fini) resultat() }
        }.start()
    }

    private fun relie(inv: Appairage.Invitation, r: org.json.JSONObject) {
        if (!Appairage.enregistrer(this, inv.adresse, inv.locale, r)) { echec(getString(R.string.scan_reponse_incomplete)); return }
        etat.text = getString(R.string.scan_relie_en_tant_que, r.optString("name").ifEmpty { getString(R.string.messageecran_cet_appareil) })
        boutons(getString(R.string.appairageecran_continuer) to { finish() })
        principal.postDelayed({ if (!fini) finish() }, 2500)
    }

    private fun echec(message: String) {
        fournisseur?.unbindAll()
        etat.text = message
        boutons(getString(R.string.scan_autres_facons) to { finish() }, getString(R.string.editeurwidget_reessayer) to { if (Appairage.aUneCamera(this)) recommencer() })
    }

    private fun recommencer() {
        dernierAvis = ""
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.CAMERA), DEMANDE_CAMERA)
        else demarrerCamera()
    }
}
