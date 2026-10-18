// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.TextView

/**
 * Essai du client Sendspin natif (debug seulement, absent de l'APK publié) : un lecteur nommé clairement pour l'essai,
 * relié à Music Assistant ; l'état et les mesures de synchro passent au journal (tag VisionSendspin) chaque seconde.
 *
 *   adb shell am start -n org.soprez.vision/.EssaiSendspinNatifActivity --es hote 10.1.1.x [--es port 8927]
 *   adb shell am start -n org.soprez.vision/.EssaiSendspinNatifActivity --es commande pause|play|next
 */
class EssaiSendspinNatifActivity : Activity() {
    companion object { const val NOM_ESSAI = "Essai Sendspin natif · Vision" }
    private val main = Handler(Looper.getMainLooper())
    private var client: SendspinClient? = null
    private lateinit var texte: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        texte = TextView(this).apply { textSize = 15f; setTextColor(0xFFE8ECF4.toInt()); setBackgroundColor(0xFF101828.toInt()); setPadding(48, 32, 48, 32) }
        setContentView(texte)
        val hote = intent.getStringExtra("hote") ?: LecteurSlim.serveur(this)
        if (hote == null) { texte.text = "--es hote <adresse de Music Assistant>"; return }
        val codecs = (intent.getStringExtra("codecs") ?: if (SendspinSortie.flacDecodable()) "flac,pcm" else "pcm").split(",")
        val c = SendspinClient(SendspinIdentite.de(this), SendspinClient.Config("ws://$hote:${intent.getStringExtra("port") ?: "8927"}/sendspin",
            intent.getStringExtra("nom") ?: NOM_ESSAI, BuildConfigCompat.version(this), codecs), object : SendspinClient.Ecouteur {
            override fun etat(e: SendspinClient.Etat) {}
        })
        client = c
        c.demarrer()
        main.post(object : Runnable {
            override fun run() {
                val e = c.etat; val m = c.sortie.mesures
                val ligne = "{\"connecte\":${e.connecte},\"disponible\":${e.disponible},\"lit\":${e.lit},\"codec\":\"${e.codec}\",\"titre\":${Json.ecrire(e.titre)}," +
                    "\"position_ms\":${c.positionMs()},\"duree_ms\":${e.dureeMs},\"pochette_o\":${e.pochette?.size},\"ecart_us\":${m.ecartUs},\"tampon_ms\":${m.tamponMs}," +
                    "\"recalages\":${m.recalages},\"trous\":${m.trous},\"trou_max_ms\":${m.trouMaxMs},\"corrections\":${m.corrections},\"blocs_en_retard\":${m.blocsEnRetard},\"horloge_err_us\":${c.horloge.erreur}," +
                    "\"commandes\":${Json.ecrire(e.commandes)},\"confiance\":\"${e.confiance}\",\"pin\":${Json.ecrire(e.pin)},\"appairage\":${Json.ecrire(e.appairage)}}"
                Log.i("VisionSendspin", ligne)
                texte.text = "$NOM_ESSAI\n\n" + ligne.replace(",\"", "\n\"")
                main.postDelayed(this, 1000)
            }
        })
    }

    override fun onNewIntent(i: android.content.Intent) {
        super.onNewIntent(i)
        i.getStringExtra("commande")?.let { client?.commande(it) }
    }

    override fun onDestroy() { client?.liberer(); super.onDestroy() }
}
