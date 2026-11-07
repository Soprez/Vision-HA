// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

/**
 * 1.94 : l'interface web de Music Assistant dans une fenêtre de Vision (la télé n'a pas toujours de navigateur, et celui des
 * télés ne se pilote pas à la télécommande ; le téléphone y passe seulement quand il n'a pas de navigateur). Retour revient
 * en arrière dans la page, puis ferme. Aucune donnée de Vision n'y est mise : c'est la page de Music Assistant, rien de plus.
 */
class MaWebActivity : Activity() {
    private var web: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val url = intent.getStringExtra("url").orEmpty()
        val th = Local.themeEffectif(this, Palette.APPLI)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(th.fond))
        if (!url.startsWith("http://") && !url.startsWith("https://")) { finish(); return }
        val w = try { WebView(this) } catch (_: Throwable) { Ui.toast(this, getString(R.string.ma_indisponible), genre = OeilModele.Genre.ERREUR); finish(); return }
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.setSupportZoom(false)
        w.setBackgroundColor(th.fond or 0xFF000000.toInt())
        w.isFocusable = true; w.isFocusableInTouchMode = true
        w.webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) { Ui.toast(this@MaWebActivity, getString(R.string.ma_indisponible), genre = OeilModele.Genre.ERREUR); finish() }
            }
        }
        val cadre = FrameLayout(this)
        cadre.addView(w, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(cadre)
        web = w
        w.loadUrl(url)
        w.requestFocus()
    }

    override fun onKeyDown(code: Int, e: KeyEvent?): Boolean {
        if (code == KeyEvent.KEYCODE_BACK) {
            val w = web
            if (w != null && w.canGoBack()) { w.goBack(); return true }
            finish(); return true
        }
        return super.onKeyDown(code, e)
    }

    override fun onDestroy() { (web?.parent as? ViewGroup)?.removeView(web); web?.destroy(); web = null; super.onDestroy() }
}
