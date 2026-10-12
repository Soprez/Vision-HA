// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.annotation.SuppressLint
import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import org.json.JSONObject

/**
 * Prototype Sendspin (étape 1 de l'étude « lecteur natif », validée en essai le 2026-10-12), debug seulement.
 *
 * sendspin-js 5.0.0 tourne dans une WebView cachée (1 px, transparente) et se connecte à Music Assistant
 * (ws://<hôte>:8927/sendspin) : MA en fait un lecteur, nommé clairement pour l'essai. Tout ce que la page mesure
 * (format, position, pochette, trous entre deux titres, synchro, mémoire JS, PIN d'appairage) part dans le journal
 * (tag VisionSendspin) et s'affiche à l'écran, pour la télé comme pour adb :
 *
 *   adb shell am start -n org.soprez.vision/.PrototypeSendspinActivity --es hote 192.168.1.x [--es port 8927] [--es codecs flac]
 *   adb logcat -s VisionSendspin
 *   --es commande pause|play|next : commande de lecture ; --ez appairer true : ouvre la fenêtre d'appairage (PIN).
 *
 * Volume (règle du projet) : la sortie reste à 100 % ; MA ne peut ni baisser ni couper le son (--es volume aucune, défaut,
 * ou neutralise pour la variante d'essai) ; aucun contrôle de volume à l'écran.
 *
 * Aucun texte ici n'est montré à un utilisateur : c'est un outil d'essai, absent de l'APK publié.
 */
class PrototypeSendspinActivity : Activity() {
    companion object {
        const val TAG = "VisionSendspin"
        const val NOM_ESSAI = "Essai Sendspin · Vision (prototype)"
    }

    private val main = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private lateinit var texte: TextView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val hote = intent.getStringExtra("hote") ?: LecteurSlim.serveur(this)
        texte = TextView(this).apply { textSize = 15f; setTextColor(0xFFE8ECF4.toInt()); setPadding(48, 32, 48, 32) }
        val cadre = FrameLayout(this).apply { setBackgroundColor(0xFF101828.toInt()) }
        cadre.addView(texte, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(cadre)
        if (hote == null) { texte.text = "Aucun hôte : --es hote <adresse de Music Assistant>"; return }
        WebView.setWebContentsDebuggingEnabled(true)
        val w = WebView(this)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.mediaPlaybackRequiresUserGesture = false
        w.alpha = 0f
        w.addJavascriptInterface(Pont(), "Vision")
        w.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR || m.messageLevel() == ConsoleMessage.MessageLevel.WARNING)
                    Log.w(TAG, "console ${m.messageLevel()} ${m.message()}")
                return true
            }
        }
        w.webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                Log.e(TAG, "{\"ev\":\"moteur_perdu\",\"crash\":${detail.didCrash()}}"); web = null; return true
            }
        }
        cadre.addView(w, FrameLayout.LayoutParams(1, 1))
        web = w
        val url = Uri.parse("file:///android_asset/sendspin/proto.html").buildUpon()
            .appendQueryParameter("serveur", "http://$hote:${intent.getStringExtra("port") ?: "8927"}")
            .appendQueryParameter("nom", intent.getStringExtra("nom") ?: NOM_ESSAI)
            .appendQueryParameter("codecs", intent.getStringExtra("codecs") ?: "flac")
            .appendQueryParameter("volume", intent.getStringExtra("volume") ?: "aucune")
            .build().toString()
        Log.i(TAG, "{\"ev\":\"chargement\",\"url\":\"$url\"}")
        w.loadUrl(url)
        if (intent.getBooleanExtra("appairer", false)) main.postDelayed({ web?.evaluateJavascript("ouvrirAppairage()", null) }, 3000)
    }

    override fun onNewIntent(i: android.content.Intent) {
        super.onNewIntent(i)
        if (i.getBooleanExtra("appairer", false)) web?.evaluateJavascript("ouvrirAppairage()", null)
        i.getStringExtra("commande")?.let { c -> web?.evaluateJavascript("commande(${JSONObject.quote(c)})", null) }
    }

    override fun onDestroy() {
        web?.destroy(); web = null
        super.onDestroy()
    }

    private inner class Pont {
        @JavascriptInterface fun etat(json: String) {
            Log.i(TAG, json)
            main.post {
                val o = try { JSONObject(json) } catch (_: Exception) { return@post }
                texte.text = buildString {
                    appendLine(NOM_ESSAI)
                    o.optString("pin").takeIf { it.isNotEmpty() && it != "null" }?.let { appendLine("PIN : $it") }
                    for (k in o.keys()) appendLine("$k : ${o.opt(k)}")
                }
            }
        }
        @JavascriptInterface fun evenement(json: String) { Log.i(TAG, json) }
    }
}
