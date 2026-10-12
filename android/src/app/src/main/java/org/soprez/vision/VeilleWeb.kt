// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.http.SslError
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONObject
import java.net.URLEncoder

/**
 * La page de veille de Home Assistant, gardée chargée pour toute l'appli.
 *
 * Charger Home Assistant prend plusieurs secondes sur une télé. La page est donc préparée à l'avance
 * (peu après l'ouverture de l'accueil), puis gardée entre deux veilles : elle dort hors de l'écran, et
 * quand la veille s'ouvre elle est posée telle quelle, tout de suite. Elle n'est rechargée que si ses
 * réglages changent (thème, tableaux, cartes) ou si elle tombe en panne.
 */
object PageVeille {
    private val main = Handler(Looper.getMainLooper())
    var web: WebView? = null
        private set
    /** Vrai quand la page chargée a dit « je suis là » (l'heure y est affichée). */
    @Volatile var prete = false
        private set
    private var chargee = ""
    private var jeton = ""
    private var base = ""
    @Volatile private var enCours = false
    private var dernierEchec = 0L
    /** La veille ouverte en ce moment, s'il y en a une : elle est prévenue quand la page arrive ou tombe. */
    var surCreation: (() -> Unit)? = null
    var surPret: (() -> Unit)? = null
    var surPanne: (() -> Unit)? = null

    fun possible(ctx: Context): Boolean = Build.VERSION.SDK_INT >= 24 && Local.veilleWeb(ctx) && Config(ctx).inscrit && Modules.actif(ctx, Modules.VEILLE_TABLEAUX)

    /** Vrai si la page vient d'échouer : inutile de la faire attendre à l'écran. */
    val enPanne: Boolean get() = dernierEchec > 0 && SystemClock.uptimeMillis() - dernierEchec < 180_000

    /** S'assure que la page existe et porte les réglages du moment. Ne bloque jamais. */
    fun preparer(contexte: Context) {
        val ctx = contexte.applicationContext
        if (!possible(ctx)) { jeter(); return }
        val w = web
        if (w != null) {
            val a = adresse(ctx)
            if (a != chargee) { chargee = a; prete = false; try { w.loadUrl(a) } catch (_: Exception) { panne() } }
            return
        }
        if (enCours || enPanne) return
        enCours = true
        Thread {
            var ok = false
            try {
                val cfg = Config(ctx)
                val ecran = if (ReglagesTvActivity.estTele(ctx)) "tele" else "telephone"
                val r = Net.post(ctx, cfg, "/api/pc_parental/veille/acces", JSONObject().put("id", cfg.id).put("secret", cfg.secret).put("ecran", ecran), 15_000)
                val j = r.optString("jeton")
                if (j.isNotEmpty() && cfg.urlActive.isNotEmpty()) {
                    Local.retenirListeWeb(ctx, r.optJSONArray("tableaux"))
                    jeton = j; base = cfg.urlActive; ok = true
                }
            } catch (_: Exception) { /* signalé plus bas */ }
            main.post { enCours = false; if (ok) creer(ctx) else panne() }
        }.also { it.isDaemon = true }.start()
    }

    /** À l'ouverture de l'accueil : la page se charge en coulisse, pour être là à la première veille. */
    fun prechauffer(contexte: Context) {
        val ctx = contexte.applicationContext
        main.postDelayed({ try { if (web == null) preparer(ctx) } catch (_: Throwable) {} }, 12_000)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun creer(ctx: Context) {
        if (web != null) return
        val theme = Local.themeEffectif(ctx, Palette.APPLI)
        val w = try { WebView(ctx) } catch (_: Throwable) { panne(); return }
        try {
            w.settings.javaScriptEnabled = true
            w.settings.domStorageEnabled = true
            w.settings.mediaPlaybackRequiresUserGesture = false
            w.settings.loadWithOverviewMode = false
            w.settings.useWideViewPort = false
            w.settings.textZoom = 100
            w.setBackgroundColor(theme.fond or 0xFF000000.toInt())
            w.isFocusable = false; w.isFocusableInTouchMode = false
            w.isVerticalScrollBarEnabled = false; w.isHorizontalScrollBarEnabled = false
            w.overScrollMode = View.OVER_SCROLL_NEVER
            // Hors de l'écran, la page se dessine quand même à la taille de l'appareil : sa mise en page est déjà la bonne.
            val dm = ctx.resources.displayMetrics
            w.layout(0, 0, dm.widthPixels, dm.heightPixels)
            w.addJavascriptInterface(Pont, "VisionAndroid")
            w.webViewClient = object : WebViewClient() {
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) { if (request.isForMainFrame) panne() }
                // Un certificat que l'appareil ne reconnaît pas : on ne passe jamais outre, le dessin de l'appli prend le relais.
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) { handler.cancel(); panne() }
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean { panne(); return true }
            }
            web = w
            prete = false
            chargee = adresse(ctx)
            w.loadUrl(chargee)
            surCreation?.invoke()
        } catch (_: Throwable) { web = w; panne() }
    }

    object Pont {
        /** Appelée par la page dès que l'heure y est affichée. */
        @JavascriptInterface fun pret() {
            main.post {
                val w = web ?: return@post
                prete = true
                dernierEchec = 0L
                // Personne ne la regarde : elle s'endort jusqu'à la prochaine veille.
                if (w.parent == null) endormir() else surPret?.invoke()
            }
        }
    }

    fun js(code: String) { try { web?.evaluateJavascript(code, null) } catch (_: Exception) {} }

    private fun endormir() {
        js("window.__visionVeille&&window.__visionVeille.dormir()")
        try { web?.onPause() } catch (_: Exception) {}
    }

    /** Pose la page dans la veille qui s'ouvre. */
    fun poser(cadre: FrameLayout): WebView? {
        val w = web ?: return null
        try {
            (w.parent as? ViewGroup)?.removeView(w)
            cadre.addView(w, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            w.onResume()
        } catch (_: Throwable) { panne(); return null }
        return w
    }

    /** La veille se ferme : la page quitte l'écran et s'endort, prête pour la prochaine fois. */
    fun reprendreLaPage() {
        val w = web ?: return
        try { (w.parent as? ViewGroup)?.removeView(w) } catch (_: Exception) {}
        try { w.animate().cancel(); w.alpha = 1f } catch (_: Exception) {}
        if (prete) endormir()
    }

    private fun panne() {
        dernierEchec = SystemClock.uptimeMillis()
        jeter()
        surPanne?.invoke()
    }

    fun jeter() {
        val w = web ?: return
        web = null; prete = false; chargee = ""
        try { (w.parent as? ViewGroup)?.removeView(w) } catch (_: Exception) {}
        try { w.stopLoading(); w.loadUrl("about:blank"); w.destroy() } catch (_: Exception) {}
    }

    // ------------------------------------------------------------ l'adresse

    private fun hex(c: Int): String = String.format("%06X", c and 0xFFFFFF)

    /** Une couleur translucide posée sur le fond : la teinte réellement vue. */
    private fun surFond(c: Int, fond: Int): Int = Palette.melanger(fond, c, Color.alpha(c) / 255f)

    private fun adresse(ctx: Context): String {
        val cfg = Config(ctx)
        val t = Local.themeEffectif(ctx, Palette.APPLI)
        val carte = surFond(t.carte, t.fond)
        // L'accent : l'or de Vision, ou la couleur choisie à la main dans « Couleurs à la carte ».
        val accent = if (Palette.lire(ctx, Palette.APPLI)?.has("accent") == true && !Local.estNuit(ctx)) t.pourpre else t.or
        val masques = ArrayList<String>()
        val cartes = ArrayList<String>()
        val durees = ArrayList<String>()
        val figees = ArrayList<String>()
        if (!Local.actif(ctx, "horloge", true)) masques.add("horloge")
        Local.duree(ctx, "horloge", 0).let { if (it > 0) durees.add("horloge:$it") }
        val liste = Local.listeWeb(ctx)
        for (i in 0 until liste.length()) {
            val id = liste.getJSONObject(i).optString("id")
            val cle = Local.cleTableau("dash", id)
            if (!Local.actif(ctx, cle, true)) masques.add(id)
            Local.cartesMasquees(ctx, cle).forEach { cartes.add("$id|$it") }
            Local.cartesFigees(ctx, cle).forEach { figees.add("$id|$it") }
            Local.duree(ctx, cle, 0).let { if (it > 0) durees.add("$id:$it") }
        }
        fun e(s: String) = URLEncoder.encode(s, "UTF-8")
        val ecran = if (ReglagesTvActivity.estTele(ctx)) "tele" else "telephone"
        val q = "id=${e(cfg.id)}&ecran=$ecran&dec=0" +
            "&fond=${hex(t.fond)}&carte=${hex(carte)}&texte=${hex(t.encre)}&texte2=${hex(t.encre2)}&accent=${hex(accent)}&ligne=${hex(Palette.melanger(carte, t.encre, 0.18f))}" +
            "&masques=${e(masques.joinToString(","))}&cartes=${e(cartes.joinToString(","))}&durees=${e(durees.joinToString(","))}" +
            "&horloge=${Themes.cle(t.nom)}&style=${Local.styleCartes(ctx)}&cfond=${if (Local.fondCartes(ctx)) 1 else 0}&ccontour=${if (Local.contourCartes(ctx)) 1 else 0}&anim=${if (Local.animerCartes(ctx)) 1 else 0}&figees=${e(figees.joinToString(","))}"
        return "$base/api/pc_parental/veille/entree#t=$jeton&q=${e(q).replace("+", "%20")}"
    }
}

/**
 * La page de veille dans une veille ouverte (couche, rêve du système ou activité).
 *
 * Page déjà prête : elle est posée et montrée aussitôt. Sinon le dessin de l'appli montre son horloge en
 * attendant, puis s'efface d'un coup au moment où la page arrive : jamais deux horloges l'une sur l'autre,
 * jamais d'écran vide. Si la page ne vient pas, le dessin de l'appli continue seul.
 */
class VeilleWeb(private val cadre: FrameLayout, private val native: VeilleView) {
    private val ctx = cadre.context
    private val main = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    @Volatile private var actif = false
    @Volatile private var abandonnee = false
    private var annonce = ""

    /** Vrai tant que la page est attendue : le dessin de l'appli ne montre que son horloge. */
    val enAttente: Boolean get() = actif && !abandonnee && !montree

    /** Vrai quand la page est à l'écran : les gestes vont alors à elle. */
    @Volatile var montree = false
        private set

    fun demarrer() {
        if (actif) return
        if (!PageVeille.possible(ctx) || PageVeille.enPanne) { abandonnee = true; return }
        actif = true; abandonnee = false
        PageVeille.surCreation = { if (actif) poser() }
        PageVeille.surPret = { if (actif) montrer(fondu = true) }
        PageVeille.surPanne = { abandonner() }
        PageVeille.preparer(ctx)
        if (PageVeille.web != null) poser()
        // Passé ce délai sans page à l'écran, le dessin de l'appli reprend la main pour cette veille.
        if (!montree) main.postDelayed(tropLong, 30_000)
        main.postDelayed(veilleur, 20_000)
    }

    fun arreter() {
        actif = false
        main.removeCallbacks(veilleur); main.removeCallbacks(tropLong); main.removeCallbacks(fonduFini)
        PageVeille.surCreation = null; PageVeille.surPret = null; PageVeille.surPanne = null
        if (web != null) PageVeille.reprendreLaPage()
        web = null
        montree = false
        native.couverte = false
    }

    fun naviguer(delta: Int) { PageVeille.js("window.__visionVeille&&window.__visionVeille.aller(${if (delta < 0) -1 else 1})") }
    fun basculerPause() { PageVeille.js("window.__visionVeille&&window.__visionVeille.figer()") }

    /** Un mot à montrer en bas à droite de la page (une demande d'accès en attente) ; vide pour l'effacer. */
    fun annoncer(texte: String) {
        annonce = texte
        if (montree) PageVeille.js("window.__visionVeille&&window.__visionVeille.annoncer(${JSONObject.quote(texte)})")
    }

    private fun poser() {
        if (web != null) return
        val w = PageVeille.poser(cadre) ?: return
        web = w
        if (PageVeille.prete) montrer(fondu = false) else w.alpha = 0f
    }

    private fun montrer(fondu: Boolean) {
        val w = web ?: return
        if (montree) return
        montree = true
        main.removeCallbacks(tropLong)
        PageVeille.js("window.__visionVeille&&window.__visionVeille.reprendre()")
        if (fondu) {
            // L'horloge de l'appli s'efface d'abord (fond uni), la page apparaît ensuite : jamais les deux à la fois.
            w.alpha = 0f
            w.animate().alpha(1f).setStartDelay(60).setDuration(450).start()
            main.postDelayed(fonduFini, 600)
        } else {
            w.animate().cancel(); w.alpha = 1f
            native.couverte = true
        }
        if (annonce.isNotEmpty()) main.postDelayed({ annoncer(annonce) }, 1500)
    }

    private val tropLong = Runnable { if (!montree) abandonner() }
    private val fonduFini = Runnable { if (montree) native.couverte = true }

    /** Le thème a changé (la nuit tombe, un réglage) : la page se recharge avec les nouvelles couleurs. */
    private val veilleur = object : Runnable {
        override fun run() {
            if (!actif) return
            try { PageVeille.preparer(ctx) } catch (_: Throwable) {}
            main.postDelayed(this, 20_000)
        }
    }

    private fun abandonner() {
        abandonnee = true
        main.removeCallbacks(tropLong); main.removeCallbacks(fonduFini)
        if (web != null) PageVeille.reprendreLaPage()
        web = null
        montree = false
        native.couverte = false
    }

    companion object {
        /** Les trois endroits où vit l'écran de veille posent la page de la même façon. */
        fun attacher(cadre: FrameLayout, vue: VeilleView) {
            if (Build.VERSION.SDK_INT < 24) return
            vue.web = VeilleWeb(cadre, vue)
        }
    }
}
