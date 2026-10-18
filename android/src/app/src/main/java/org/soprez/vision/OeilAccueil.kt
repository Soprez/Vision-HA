// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * 1.90.5, PROTOTYPE (retour d'essai) : l'œil Vision animé posé sur le bouton Accueil de la barre de navigation d'Android.
 *
 * On ne remplace pas le bouton du système : on pose PAR-DESSUS une petite fenêtre du service d'accessibilité
 * (TYPE_ACCESSIBILITY_OVERLAY, qui peut dessiner sur la barre de navigation), qui ne prend ni le toucher ni le focus :
 * les appuis passent au vrai bouton dessous.
 *
 * Position : pas de calcul d'après la taille de l'écran (fragile selon les constructeurs) ; le service lit la fenêtre
 * de la barre de navigation et y trouve LE bouton Accueil (trio « back », « home », « recent_apps » d'un même paquet :
 * SystemUI, ou le lanceur du système qui dessine la barre sur Android 14+), avec ses limites exactes à l'écran. Pas de
 * trio trouvé → rien d'affiché (navigation par gestes, barre cachée en plein écran, constructeur qui nomme autrement ses
 * boutons) : jamais un œil décalé.
 *
 * Masqué aussi pendant le clavier et quand la barre est cachée (plein écran). Animation : l'œil joyeux (clignements,
 * regard, sourire), 30 images/s au plus, rien quand l'écran est éteint (plus de vsync) ; un appui sur Accueil
 * (touche reçue par le service, jamais consommée) le fait réagir.
 *
 * Réglage par appareil, DÉSACTIVÉ par défaut (« Œil sur le bouton Accueil »).
 */
object OeilAccueil {
    private const val PREFS = "local"
    private const val CLE = "oeil_accueil"
    const val ID_ACCUEIL = "com.android.systemui:id/home"

    fun actif(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(CLE, false)
    fun actif(ctx: Context, oui: Boolean) { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(CLE, oui).apply() }

    /** Le réglage changé à chaud : le service (s'il tourne) pose ou retire l'œil tout de suite. */
    fun activer(ctx: Context, oui: Boolean) {
        actif(ctx, oui)
        VisionAccessibility.instance?.get()?.let { s -> if (oui) connecter(s) else retirer(s) }
    }

    /** Le mode de navigation d'Android : 0 = 3 boutons, 1 = 2 boutons, 2 = gestes (-1 : inconnu). */
    fun modeNavigation(ctx: Context): Int = try { Settings.Secure.getInt(ctx.contentResolver, "navigation_mode", -1) } catch (_: Exception) { -1 }

    /**
     * Où poser l'œil sur le bouton [bouton] (limites à l'écran) : un carré centré sur lui, de 62 % de son petit côté
     * (le rond blanc du bouton est recouvert, sans déborder sur les boutons voisins). Pur, pour les essais.
     */
    fun carre(bouton: Rect): Rect = carre(bornes(bouton)).let { Rect(it[0], it[1], it[2], it[3]) }

    private var vue: OeilVue? = null
    private var place: Rect? = null

    /** À la connexion du service : si l'option est active, il lui faut voir les fenêtres (barre de navigation). */
    fun connecter(s: AccessibilityService) {
        if (!actif(s)) return
        try {
            s.serviceInfo = s.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                eventTypes = eventTypes or AccessibilityEvent.TYPE_WINDOWS_CHANGED
            }
        } catch (_: Exception) {}
        mettreAJour(s)
    }

    /** Le paquet qui dessine la barre trouvée (SystemUI ou le lanceur du système). */
    @Volatile private var paquetBarre: String? = null

    /**
     * À chaque changement de fenêtres : l'œil suit le bouton, ou disparaît. Un clic sur LE bouton Accueil de la barre
     * trouvée (même paquet, « :id/home ») le fait réagir : c'est ainsi qu'on le voit quand le lanceur du système dessine
     * la barre (la touche Accueil n'arrive alors pas au service).
     */
    fun evenement(s: AccessibilityService, e: AccessibilityEvent) {
        if (vue == null && !actif(s)) return
        if (e.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED || e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) mettreAJour(s)
        if (e.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && vue != null && e.packageName?.toString() == paquetBarre) {
            val id = try { e.source?.viewIdResourceName } catch (_: Exception) { null }
            if (id == "$paquetBarre:id/home") appuiAccueil()
        }
    }

    /** La touche Accueil (reçue, jamais consommée) : une petite réaction de joie. */
    /** Pour les essais : le nombre de réactions de joie (appuis sur Accueil vus avec l'œil posé). */
    @Volatile var joies = 0; private set

    private var derniereJoie = 0L

    /** La touche ou le bouton Accueil (vus, jamais consommés) : une petite réaction de joie (pas deux à la fois). */
    fun appuiAccueil() {
        val v = vue ?: return
        val maintenant = android.os.SystemClock.uptimeMillis()
        if (maintenant - derniereJoie < 600) return
        derniereJoie = maintenant
        joies++
        v.fin = { v.joyeux() }
        v.reagir(OeilModele.Variante.B, OeilModele.Genre.JOIE)
    }

    /**
     * Le bouton Accueil d'une barre de navigation à 3 boutons, dans la fenêtre [racine] : le trio « back », « home »,
     * « recent_apps » d'un même paquet (SystemUI, ou le lanceur du système qui dessine la barre sur Android 14+ : par
     * exemple com.google.android.apps.nexuslauncher), côte à côte sur une même ligne, en bas de l'écran ([hauteurEcran]).
     * Sans le trio complet, rien : on ne prend jamais le bouton « home » d'une appli.
     */
    fun accueilDansFenetre(racine: AccessibilityNodeInfo, largeurEcran: Int, hauteurEcran: Int): Rect? {
        val pkg = racine.packageName?.toString() ?: return null
        fun un(id: String): Rect? = try {
            racine.findAccessibilityNodeInfosByViewId("$pkg:id/$id").firstOrNull { it.isVisibleToUser }?.let { n -> Rect().also { n.getBoundsInScreen(it) } }
        } catch (_: Exception) { null }
        val home = un("home") ?: return null
        val back = un("back") ?: return null
        val recents = un("recent_apps") ?: un("recents") ?: return null
        if (!trioValide(back, home, recents, largeurEcran, hauteurEcran)) return null
        paquetBarre = pkg
        return home
    }

    /**
     * Le trio d'une barre de navigation. Portrait : une ligne en bas de l'écran, retour à gauche, aperçu à droite.
     * Paysage (téléphone) : une colonne collée au bord droit ou gauche, l'accueil entre les deux autres. Pur.
     */
    fun trioValide(back: Rect, home: Rect, recents: Rect, largeurEcran: Int, hauteurEcran: Int): Boolean =
        trioValide(bornes(back), bornes(home), bornes(recents), largeurEcran, hauteurEcran)

    /** Les limites d'un Rect en [gauche, haut, droite, bas]. */
    fun bornes(r: Rect): IntArray = intArrayOf(r.left, r.top, r.right, r.bottom)

    /**
     * Le même contrôle sur des limites [gauche, haut, droite, bas] (pur, essais JVM). 1.91.3 (l'auteur, paysage et
     * rotations) : la barre en ligne peut aussi être celle d'une tablette en paysage (barre des tâches : les trois
     * boutons à droite, en bas) ; la colonne d'un téléphone en paysage est collée au bord droit (rotation 90°) ou gauche
     * (270°), l'accueil entre les deux autres, dans un sens ou dans l'autre.
     */
    fun trioValide(back: IntArray, home: IntArray, recents: IntArray, largeurEcran: Int, hauteurEcran: Int): Boolean {
        fun l(b: IntArray) = b[2] - b[0]
        fun h(b: IntArray) = b[3] - b[1]
        fun cx(b: IntArray) = (b[0] + b[2]) / 2
        fun cy(b: IntArray) = (b[1] + b[3]) / 2
        if (listOf(back, home, recents).any { l(it) <= 0 || h(it) <= 0 }) return false
        val ligne = Math.abs(cy(back) - cy(home)) <= h(home) / 3 && Math.abs(cy(recents) - cy(home)) <= h(home) / 3
        if (ligne) {
            val ordre = cx(back) < cx(home) && cx(home) < cx(recents)
            val enBas = hauteurEcran <= 0 || home[1] >= hauteurEcran * 0.8f
            return ordre && enBas
        }
        val colonne = Math.abs(cx(back) - cx(home)) <= l(home) / 3 && Math.abs(cx(recents) - cx(home)) <= l(home) / 3
        if (!colonne || largeurEcran <= 0) return false
        val entre = (cy(back) - cy(home)).toLong() * (cy(recents) - cy(home)) < 0
        val auBord = home[0] >= largeurEcran * 0.8f || home[2] <= largeurEcran * 0.2f
        return entre && auBord
    }

    /** Le carré de l'œil sur le bouton [b] = [gauche, haut, droite, bas] (pur) : 62 % de son petit côté, centré. */
    fun carre(b: IntArray): IntArray {
        val c = (minOf(b[2] - b[0], b[3] - b[1]) * 0.62f).toInt()
        val cx = (b[0] + b[2]) / 2; val cy = (b[1] + b[3]) / 2
        return intArrayOf(cx - c / 2, cy - c / 2, cx - c / 2 + c, cy - c / 2 + c)
    }

    /** Les limites du bouton Accueil à l'écran, ou null (pas sûr : on n'affiche rien). */
    fun boutonAccueil(s: AccessibilityService): Rect? {
        if (Build.VERSION.SDK_INT < 21) return null
        val fenetres = try { s.windows } catch (_: Exception) { return null }
        // Le clavier est là : l'œil se retire.
        if (fenetres.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }) return null
        val ecran = if (Build.VERSION.SDK_INT >= 30) (s.getSystemService(Context.WINDOW_SERVICE) as WindowManager).maximumWindowMetrics.bounds
            else Rect(0, 0, s.resources.displayMetrics.widthPixels, s.resources.displayMetrics.heightPixels)
        for (w in fenetres) {
            val racine: AccessibilityNodeInfo = w.root ?: continue
            accueilDansFenetre(racine, ecran.width(), ecran.height())?.let { return it }
        }
        return null
    }

    fun mettreAJour(s: AccessibilityService) {
        val r = if (actif(s)) boutonAccueil(s) else null
        if (r == null) { retirer(s); return }
        val c = carre(r)
        if (c == place && vue != null) return
        val wm = s.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val lp = WindowManager.LayoutParams(c.width(), c.height(), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START; x = c.left; y = c.top
            title = "Vision : œil sur le bouton Accueil"
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        try {
            val v = vue
            if (v == null) {
                val n = OeilVue(s).apply { cadenceMax = 30; importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO }
                wm.addView(n, lp); vue = n
                n.post { n.joyeux() }
            } else wm.updateViewLayout(v, lp)
            place = c
        } catch (_: Exception) { retirer(s) }
    }

    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private val remesures = ArrayList<Runnable>()

    /** Les délais (ms) des nouvelles mesures après une rotation : la barre de navigation se redessine en plusieurs temps. */
    val DELAIS_ROTATION = longArrayOf(250L, 700L, 1500L, 3000L)

    /**
     * 1.91.3 (l'auteur : l'œil n'est plus sur le bouton en paysage et après une rotation) : la fenêtre de l'œil est posée en
     * coordonnées absolues ; à la rotation elle resterait à l'ancienne place, et l'événement des fenêtres arrive parfois
     * avant que la barre ait bougé. L'œil se retire donc tout de suite, puis le bouton est recherché à plusieurs reprises.
     */
    fun rotation(s: AccessibilityService) {
        if (!actif(s)) return
        retirer(s)
        remesures.forEach { main.removeCallbacks(it) }; remesures.clear()
        for (d in DELAIS_ROTATION) Runnable { mettreAJour(s) }.also { remesures.add(it); main.postDelayed(it, d) }
    }

    fun retirer(s: Context) {
        val v = vue ?: return
        try { (s.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (_: Exception) {}
        vue = null; place = null
    }

    /** Pour les essais : où l'œil est posé (null : pas affiché). */
    fun placeActuelle(): Rect? = place
}
