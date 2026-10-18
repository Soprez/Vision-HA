// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.DragEvent
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.Window
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * L'accueil Vision.
 *
 * Télé (sans en-tête « VISION » depuis le 14 octobre, comme le téléphone) : l'heure, la météo et la pluie, un mot
 * de la maison, la rangée d'applications (anneau d'or au focus, « + » en bout
 * de rangée pour en ajouter) et une roue de réglages en bas à droite. Appui
 * long sur une appli : ouvrir, déplacer (gauche et droite déplacent, OK
 * pose, comme sur Android TV), retirer, infos, désinstaller. Deux appuis sur
 * Accueil : l'écran de veille par-dessus la musique en cours.
 *
 * Téléphone : comme l'accueil d'Android. Une grille à quatre colonnes où
 * l'on pose applis, dossiers et widgets Vision où l'on veut ; appui long
 * pour soulever et déplacer, déposer sur une appli pour faire un dossier,
 * zone « retirer » en haut ; appui long sur le vide : widgets, couleurs,
 * réglages ; glisser vers le haut : toutes les applications. En bas, un dock
 * de trois icônes au choix (appui long pour les changer), rien d'autre.
 *
 * Tous les réglages sont ceux de l'appareil (Local) ; Home Assistant fournit
 * les données.
 */
class LanceurActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var cfg: Config
    private var theme: Theme = Themes.liste[0]
    private var donnees: JSONObject? = null
    private lateinit var racine: FrameLayout
    private var heure: TextView? = null
    private var date: TextView? = null
    private var fil: Thread? = null
    private var empreinteApps = ""
    private val tele by lazy { ReglagesTvActivity.estTele(this) }
    // L'heure et la date à la façon du téléphone (langue, 12/24 h) ; lues après la création de l'écran.
    private val heureFmt by lazy { android.text.format.DateFormat.getTimeFormat(this) }
    private val dateFmt by lazy { SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM"), Locale.getDefault()) }
    private var versionLocale = -1
    private var dossierOuvert: Dialog? = null
    private var toucheEnfoncee = false
    private var reconstructionEnAttente = false
    private var profilConnu = ""
    private var dernierAccueilMs = 0L
    /** La liste des modules actifs vue au dernier dessin (Modules.signature). */
    private var modulesVus: String? = null

    private val tic = object : Runnable {
        override fun run() {
            // 1.83.1 (glitch de l'horloge) : réécrit seulement quand ça change (une fois par minute), jamais vidé.
            val maintenant = Date()
            val h = heureFmt.format(maintenant); val d = dateFmt.format(maintenant)
            heure?.let { if (it.text.toString() != h) it.text = h }
            date?.let { if (it.text.toString() != d) it.text = d }
            racine.findViewWithTag<TextView>("w_heure")?.let { if (it.text.toString() != h) it.text = h }
            if (Calendar.getInstance().get(Calendar.SECOND) == 0 || Local.version != versionLocale) rafraichirTheme()
            verifierCatalogueHa()
            // Lot accueil (point 3) : le relevé des paquets changés, au cas où le rappel du lanceur et la diffusion manquent.
            Paquets.verifier(this@LanceurActivity)
            // Un module activé ou coupé dans Home Assistant pendant que l'accueil est à l'écran : il se rebâtit.
            val modules = Modules.signature(Modules.actifs(this@LanceurActivity))
            if (modulesVus == null) modulesVus = modules
            else if (modules != modulesVus && enDrag == null && enDragNouveau == null && enDragAppli == null && deplacement == null && panneau == null) { modulesVus = modules; cacherPanneau(); reconstruireApplis() }
            main.postDelayed(this, 1000)
        }
    }

    companion object {
        private const val PREFS = "lanceur"
        /** 1.90.1 : en paysage, la part de la largeur pour l'en-tête (heure, date, météo, lecteur). */
        private const val PART_TETE_PAYSAGE = 0.36f
        /** 1.90.1 : en paysage, la taille utile d'une case (dp) : au-delà, la grille se centre. */
        private const val LARGEUR_CASE_MAX = 112f
        private const val HAUTEUR_CASE_MAX = 128f
        /** 1.80.0 : appelé par le service quand une demande arrive ; posé seulement pendant que l'accueil est affiché. */
        @Volatile var rafraichirMaintenant: (() -> Unit)? = null
        /** Veille-lot : l'accueil de Vision est à l'écran (il a sa propre minuterie de veille : l'agent ne double pas). */
        @Volatile var devant = false
        fun masquees(ctx: android.content.Context) = Accueil.masquees(ctx)
        fun cacher(ctx: android.content.Context, pkg: String, cachee: Boolean) = Accueil.cacher(ctx, pkg, cachee)
        fun applicationsInstallees(ctx: android.content.Context) = Accueil.applicationsInstallees(ctx)
        fun accueilSysteme(ctx: android.content.Context) = Accueil.accueilSysteme(ctx)
    }

    // ------------------------------------------------------------------ cycle de vie

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.util.Log.i("VisionAccueil", "onCreate (${if (savedInstanceState == null) "démarrage" else "recréation"})")
        cfg = Config(this)
        theme = Local.themeEffectif(this, Palette.LANCEUR)
        profilConnu = getSharedPreferences(PREFS, MODE_PRIVATE).getString("profil", "") ?: ""
        barres()
        racine = FrameLayout(this)
        // 1.90.1 (l'auteur, Pixel en paysage) : Android 15+ dessine l'appli sous les barres du système (bord à bord imposé).
        // La barre de navigation à gauche ou à droite en paysage et l'encoche sont laissées libres ; l'accueil se
        // rebâtit quand elles changent (rotation).
        // La marge elle-même est posée par VisionApp (Bords) ; ici on lit seulement la largeur prise sur les côtés,
        // pour calculer la grille sur la largeur qui reste.
        if (!tele) racine.setOnApplyWindowInsetsListener { v, ins ->
            // Avant Android 15, la fenêtre s'arrête déjà aux barres latérales : rien à retirer de la largeur.
            val (g, d) = if (Build.VERSION.SDK_INT >= 35) Marges.cotesSysteme(ins) else 0 to 0
            // 16-10 (recette, téléphone à caméra « poinçon ») : la hauteur prise en haut (heure, encoche), pour l'index alphabétique.
            val h = if (Build.VERSION.SDK_INT >= 35) ins.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout()).top else 0
            if (g != insetG || d != insetD || h != insetH) {
                insetG = g; insetD = d; insetH = h
                if (constructions > 0) v.post { construire() }
            }
            ins
        }
        // Filet de sécurité : la racine reçoit la fin de TOUT glisser (dépôt, hors de la grille, annulation, zone de
        // retrait), même quand la case glissée a été reconstruite ou n'a rien reçu.
        racine.setOnDragListener { _, e ->
            when (e.action) {
                DragEvent.ACTION_DRAG_STARTED -> true
                DragEvent.ACTION_DRAG_ENDED -> {
                    main.post {
                        etatGlisser.fin(depotReussi = true)
                        grilles().forEach { it.cacherCible() }; annulerFlip()
                        enDrag = null; enDragNouveau = null; enDragAppli = null; prise = null
                        // Une appli désinstallée pendant le glisser : l'accueil se met à jour maintenant.
                        if (paquetsChanges.isNotEmpty()) planifierMajPaquets(0)
                    }
                    true
                }
                else -> false
            }
        }
        setContentView(racine)
        construire()
        Paquets.abonner(surPaquet)
        Bulle.verifier(this)
        // APP-03 : l'accueil ne contacte plus le serveur trouvé ; « Relier » (bandeau) mène à la question « Se relier à … ? ».
    }

    override fun onDestroy() {
        android.util.Log.i("VisionAccueil", "onDestroy (changement de configuration : $isChangingConfigurations)")
        Paquets.desabonner(surPaquet)
        super.onDestroy()
    }

    /**
     * 1.83.2 (glitch au retour depuis l'appli Claude) : un changement de configuration fait pendant qu'une autre
     * appli est devant (thème sombre, taille de police, densité, orientation, taille de fenêtre) recréait
     * l'accueil en entier au retour. Il est maintenant absorbé ici : le thème est relu et l'accueil se rebâtit
     * sur place, heure comprise, dans la même image.
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        android.util.Log.i("VisionAccueil", "onConfigurationChanged (sans recréation)")
        theme = Local.themeEffectif(this, Palette.LANCEUR)
        barres()
        demanderReconstruction()
    }

    private var auPremierPlan = false

    /**
     * Une appli installée, supprimée ou modifiée (Paquets) : ses icône et nom en mémoire sont oubliés,
     * et l'accueil comme le tiroir se rebâtissent tout de suite s'ils sont à l'écran (sinon au retour,
     * l'empreinte ayant changé). Le tiroir ouvert le reste.
     */
    private val surPaquet: (String, Boolean) -> Unit = { pkg, _ ->
        iconesTiroir.remove(pkg); nomsTiroir.remove(pkg); tiroirCle = ""
        paquetsChanges.add(pkg)
        planifierMajPaquets(80)
    }

    /**
     * Lot accueil (point 3) : les applis changées en attente de rebâtissage. Avant, un changement arrivé pendant un
     * glisser (ou avec une touche crue tenue) était simplement perdu jusqu'au retour suivant sur l'accueil.
     */
    private val paquetsChanges = LinkedHashSet<String>()
    private val majPaquets = Runnable { appliquerMajPaquets() }
    private fun planifierMajPaquets(delai: Long) { main.removeCallbacks(majPaquets); main.postDelayed(majPaquets, delai) }

    /** Rebâtit accueil, dock, tiroir (ouvert ou non) et dossier ouvert ; caché, l'empreinte s'en chargera au retour. */
    private fun appliquerMajPaquets() {
        if (paquetsChanges.isEmpty() || isFinishing) return
        val glisser = enDrag != null || enDragNouveau != null || enDragAppli != null || deplacement != null
        val tenue = if (toucheEnfoncee) SystemClock.uptimeMillis() - toucheDepuis else null
        when (NettoyageAccueil.quandRebatir(auPremierPlan, glisser, tenue)) {
            NettoyageAccueil.Maj.AU_RETOUR -> { paquetsChanges.clear(); return }
            NettoyageAccueil.Maj.REESSAYER -> { planifierMajPaquets(250); return }
            NettoyageAccueil.Maj.MAINTENANT -> {}
        }
        paquetsChanges.clear()
        // Rien de changé dans ce qui est affiché (liste du tiroir, dock, cases, dossiers) : pas de rebâtissage.
        if (appsAffichees() == appsVues) return
        // Le dossier ouvert est une fenêtre à part : il se referme et se rouvre, à jour (ou pas, s'il a disparu).
        val dossier = dossierOuvertCle
        // (L'appli partie est déjà sortie du dossier enregistré : on ne peut plus savoir s'il la contenait, on le rouvre.)
        val rouvrir = dossier != null && dossierOuvert?.isShowing == true
        if (rouvrir) try { dossierOuvert?.dismiss() } catch (_: Exception) {}
        toucheEnfoncee = false; reconstructionEnAttente = false
        reconstruireApplis()
        if (rouvrir) {
            val d = Accueil.dossiers(this).firstOrNull { it.cle == dossier }
            if (d != null) { d.pkgs.retainAll { Accueil.presente(this, it) }; if (d.pkgs.isNotEmpty()) ouvrirDossier(d, Accueil.cases(this).map { it.cle }) }
        }
    }

    /** Ce que l'accueil montre des applis (liste du tiroir, dock, cases, dossiers) au dernier rebâtissage. */
    private var appsVues = ""
    private fun appsAffichees(): String = Accueil.applicationsInstallees(this).joinToString(",") { it.activityInfo.packageName } + "|" +
        Accueil.dock(this).joinToString(",") + "|" + (if (tele) Accueil.cases(this).joinToString(",") { it.cle } else Accueil.disposition(this).joinToString(",") { it.cle }) + "|" +
        Accueil.dossiers(this).joinToString(";") { it.cle + ":" + it.pkgs.joinToString(",") }

    /** Depuis quand la touche crue est tenue (voir [toucheEnfoncee]). */
    private var toucheDepuis = 0L
    /** La clé du dossier ouvert (dossierOuvert), pour le rouvrir à jour. */
    private var dossierOuvertCle: String? = null

    /** Rebâtit l'accueil sans refermer le tiroir s'il était ouvert. */
    private fun reconstruireApplis() {
        if (toucheEnfoncee) { reconstructionEnAttente = true; return }
        val ouvert = tiroirOuvert
        construire()
        if (ouvert && !tele) tirerTiroir(1f)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Deux appuis rapprochés sur Accueil : l'écran de veille vient devant, la musique de l'autre appli continue.
        // Sur téléphone, Accueil ramène à l'accueil : le tiroir, un dossier ou le panneau ouverts se referment.
        // Sur télé aussi : Accueil ramène toujours à l'accueil de Vision, dossier et tiroir refermés.
        tirerTiroir(0f); try { dossierOuvert?.dismiss() } catch (_: Exception) {}; cacherPanneau()
        if (!tele) pageur?.aller(0) else selectionnerPremiere()
        val now = SystemClock.uptimeMillis()
        if (tele && now - dernierAccueilMs < 1500) { Veille.ouvrir(this, demande = true); dernierAccueilMs = 0 } else dernierAccueilMs = now
    }

    private fun barres() {
        window.decorView.systemUiVisibility = if (tele)
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        else View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or (if (!theme.sombre) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0)
        if (!tele) { window.statusBarColor = 0; window.navigationBarColor = theme.fond }
    }

    override fun onResume() {
        super.onResume()
        Garde.exigerCodeDecideur(this)  // faille du 16-10 : un Décideur a toujours un code
        NiveauSonore.assurer(this)  // le micro ne se lance qu'au premier plan (Android)
        auPremierPlan = true; devant = true
        // 17-10 (retour d'essai) : « garder l'écran allumé » réglé dans Home Assistant pour cet appareil — l'accueil empêche la mise en
        // veille de l'écran Android tant qu'il est devant ; sinon, comme avant (rien d'imposé).
        if (EcranAppareil.garderAllume(this)) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ToucheMedia.maintenir(this)  // 1.93.05 (retour 37) : touches média vers la musique de cet appareil
        rafraichirMaintenant = { main.post { if (!isFinishing) demarrerDonnees() } }
        // 1.83.1 : un seul minuteur (avant : un de plus à chaque retour sur l'accueil).
        main.removeCallbacks(tic)
        main.post(tic)
        demarrerDonnees()
        // Lot accueil (point 3) : un changement d'appli manqué pendant l'absence (processus gelé) est rattrapé ici.
        toucheEnfoncee = false
        Paquets.verifier(this)
        if (empreinteApps != empreinte()) reconstruireApplis()
        brancherFluxHa()
        remplirMessagesTele()
        armerVeille()
        retourDAppli()
        // La page de veille se charge en coulisse : elle sera là, tout de suite, à la première veille.
        PageVeille.prechauffer(this)
    }

    /** On revient d'une appli : la sélection se remet sur elle, et si elle joue encore du son sans en avoir le droit, on l'arrête. */
    private fun retourDAppli() {
        val pkg = Accueil.dernierLance.ifEmpty { Usage.paquetQuitte }
        if (pkg.isEmpty() || pkg == packageName) { selectionnerPremiere(); return }
        Accueil.dernierLance = ""; Usage.paquetQuitte = ""
        if (tele && deplacement == null) racine.post {
            val cle = if (trouverParTag(racine, pkg) != null) pkg
                      else try { Accueil.cases(this).firstOrNull { it.dossier?.pkgs?.contains(pkg) == true }?.cle } catch (_: Exception) { null }
            if (cle != null) trouverParTag(racine, cle)?.let { v -> v.requestFocus(); v.parent?.requestChildFocus(v, v) }
        }
        main.postDelayed({ faireTaire(pkg) }, 1200)
    }

    /** Télé : de retour à l'accueil sans appli ouverte, la sélection se pose sur la première application. */
    private fun selectionnerPremiere() {
        if (!tele || deplacement != null) return
        racine.post {
            if (focusNonRelie()) return@post
            try {
                val premiere = Accueil.cases(this).firstOrNull()?.cle ?: return@post
                trouverParTag(racine, premiere)?.let { v -> v.requestFocus(); v.parent?.requestChildFocus(v, v) }
            } catch (_: Exception) {}
        }
    }

    /** Free TV et d'autres continuent leur son une fois quittées : pause, puis arrêt de l'appli si elle insiste. */
    private fun faireTaire(pkg: String) {
        try {
            if (isFinishing || !hasWindowFocus()) return
            val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            if (!am.isMusicActive || Musique.nous || Veille.ouverte || pkg in Local.fond(this)) return
            for (code in intArrayOf(KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP)) {
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            }
            main.postDelayed({
                try {
                    if (!isFinishing && hasWindowFocus() && am.isMusicActive && !Musique.nous && !Veille.ouverte)
                        (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager).killBackgroundProcesses(pkg)
                } catch (_: Exception) {}
            }, 1500)
        } catch (_: Exception) {}
    }

    override fun onStart() {
        super.onStart()
        if (!tele) try { WidgetsAndroid.hote(this).startListening() } catch (_: Exception) {}
    }

    override fun onStop() {
        super.onStop()
        if (!tele) try { WidgetsAndroid.hote(this).stopListening() } catch (_: Exception) {}
    }

    @Deprecated("startActivityForResult : le choix et le réglage d'un widget Android passent par là")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != WidgetsAndroid.REQ_LIER && requestCode != WidgetsAndroid.REQ_REGLER) return
        val id = data?.getIntExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, widgetEnCours) ?: widgetEnCours
        if (id < 0) return
        if (resultCode != RESULT_OK) { try { WidgetsAndroid.hote(this).deleteAppWidgetId(id) } catch (_: Exception) {}; widgetEnCours = -1; return }
        if (requestCode == WidgetsAndroid.REQ_LIER) reglerWidgetAndroid(id) else poserWidgetAndroid(id)
    }

    override fun onPause() {
        super.onPause()
        auPremierPlan = false; devant = false
        ToucheMedia.maintenir(this)
        rafraichirMaintenant = null
        main.removeCallbacks(tic)
        main.removeCallbacks(veilleAuto)
        fil?.interrupt(); fil = null
        // Accueil caché : le flux des widgets Home Assistant se coupe (il reprend au retour).
        FluxWidgets.arreter()
    }

    /**
     * Sur télé : sans touche pendant le délai choisi, l'écran de veille vient (en plus du réglage système, quand il a pu être écrit).
     * Repli : sans délai choisi et quand l'économiseur du système n'est pas Vision, Vision lance sa veille sur son accueil
     * (Local.delaiRepli, 10 min par défaut) et la retire au premier appui. Jamais devant une autre appli : la minuterie ne
     * court que tant que l'accueil est au premier plan, et rien ne part si une appli joue du son qu'on ne doit pas couvrir.
     */
    private val veilleAuto = Runnable {
        val proteger = try { Local.appAProteger(this) } catch (_: Exception) { false }
        if (tele && !isFinishing && auPremierPlan && hasWindowFocus() && !Veille.ouverte && !proteger && !VeilleMusique.bloque(this)) {
            val repli = Local.delaiVeille(this) <= 0
            startActivity(Intent(this, VeilleActivity::class.java).putExtra(VeilleActivity.PREMIER_APPUI, repli))
        }
    }
    private fun armerVeille() {
        main.removeCallbacks(veilleAuto)
        if (!tele || !Modules.actif(this, Modules.VEILLE)) return
        // Veille-lot : « Vision » ne compte que si la télé lance vraiment son économiseur (choisi ET activé).
        val vision = EconomiseurTv.planRecent(this).chemin == ReglesVeille.Chemin.DEJA_VISION
        val min = ReglesVeille.delaiAccueil(Local.delaiVeille(this), vision, Local.delaiRepli(this))
        if (min > 0) main.postDelayed(veilleAuto, min * 60_000L)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) { armerVeille(); Flux.mainHumaine() }
        if (event.action == KeyEvent.ACTION_DOWN && !toucheEnfoncee) toucheDepuis = SystemClock.uptimeMillis()
        toucheEnfoncee = event.action == KeyEvent.ACTION_DOWN
        if (event.action == KeyEvent.ACTION_UP && reconstructionEnAttente) { reconstructionEnAttente = false; main.postDelayed({ construire() }, 150) }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Le mode déplacement de la télé : gauche et droite déplacent la case, OK ou Retour la posent.
        val d = deplacement
        if (d != null) {
            when (keyCode) {
                // Disposition « Vertical » : la colonne se parcourt en haut et en bas.
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (!teleVerticale()) glisserTele(d, -1); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (!teleVerticale()) glisserTele(d, +1); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK -> { deplacement = null; construire(); return true }
                KeyEvent.KEYCODE_DPAD_UP -> { if (teleVerticale()) glisserTele(d, -1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { if (teleVerticale()) glisserTele(d, +1); return true }
            }
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) { dossierOuvert?.dismiss(); cacherPanneau(); return true }
        // 1.93.05 (l'auteur, retour 37) : les touches média pilotent la musique de Music Assistant qui joue sur cet appareil.
        if (event != null && ToucheMedia.touche(this, event)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onBackPressed() { if (tiroirOuvert) { animerTiroir(0f); return }; dossierOuvert?.dismiss(); cacherPanneau() }

    // ------------------------------------------------------------------ thème

    private fun rafraichirTheme() {
        versionLocale = Local.version
        val nouveau = Local.themeEffectif(this, Palette.LANCEUR)
        if (nouveau.nom != theme.nom || nouveau.fond != theme.fond || nouveau.carte != theme.carte || nouveau.encre != theme.encre || nouveau.pourpre != theme.pourpre) {
            theme = nouveau
            barres()
            demanderReconstruction()
        }
    }

    /** Jamais pendant qu'une touche est tenue : un écran rebâti sous le pouce clique n'importe où. */
    private fun demanderReconstruction() {
        if (toucheEnfoncee) { reconstructionEnAttente = true; return }
        construire()
    }

    // ------------------------------------------------------------------ outils

    private fun px(dp: Float) = Ui.dp(this, dp)
    // Les marges du système sur les côtés (px), gauche et droite. Voir onCreate (1.90.1).
    private var insetG = 0
    private var insetD = 0
    // La hauteur prise en haut par la barre d'état et l'encoche (px, Android 15+ : l'accueil est dessiné dessous).
    private var insetH = 0
    private val dm get() = resources.displayMetrics
    private fun empreinte(): String = "g" + Paquets.generation + "|" + cfg.inscrit + "|" + Accueil.masquees(this).sorted().joinToString(",") + "|" + Accueil.ordre(this).joinToString(",") + "|" + theme.nom + theme.fond + "|" + Accueil.dossiers(this).joinToString { it.cle + it.nom + it.pkgs.size } + "|" + profilConnu + "|" + Modules.signature(Modules.actifs(this)) + "|" + WidgetsHaStock.generation + "|" + Etat.verrouilleEffectif() + Etat.apps.hashCode() + Etat.toujours.hashCode() + "|" + (if (tele) "" else Accueil.disposition(this).joinToString { it.cle + it.col + "," + it.row + "," + it.w + "x" + it.h } + "|" + Accueil.dock(this).joinToString(",") + "|" + Accueil.mode(this)) + "|" + DispositionAccueil.empreinte(this)

    /** Ouvre une appli ; fermée par les règles, elle montre pourquoi (et comment demander) au lieu de s'ouvrir. */
    private fun ouvrir(pkg: String) {
        val raison = Etat.doitBloquer(this, pkg)
        if (raison != null) BlockActivity.afficher(this, raison, pkg) else Accueil.lancer(this, pkg)
    }

    /** Une appli fermée par les règles reste à sa place, en grisé. */
    private fun <V : View> griser(v: V, pkg: String?): V { if (pkg != null && Etat.doitBloquer(this, pkg) != null) v.alpha = 0.34f; return v }

    private fun texte(t: String, taille: Float, couleur: Int, police: Typeface = Polices.texte(this), espacement: Float = 0f): TextView = TextView(this).apply {
        text = Ui.coupable(t); textSize = taille; setTextColor(couleur); typeface = police; letterSpacing = espacement
        Ui.sansCesure(this)
    }

    /** Taille en sp qui suit la hauteur de l'écran de la télé (1080 p = 540 dp : 176 px de maquette = 88 sp). */
    private fun tv(pxMaquette: Float): Float = pxMaquette * (dm.heightPixels / dm.density) / 1080f

    private fun etiquetteSection(t: String, taille: Float): TextView = texte(t.uppercase(Locale.getDefault()), taille, theme.encre2, Polices.gras(this), 0.22f)

    private fun icone(res: Int, tailleDp: Float, teinte: Int): ImageView = ImageView(this).apply {
        setImageResource(res); if (res != R.drawable.ic_vision) setColorFilter(teinte); layoutParams = ViewGroup.LayoutParams(px(tailleDp), px(tailleDp))
    }

    private fun fondCarte(rayonDp: Float, couleur: Int = theme.carte, bord: Int = theme.carteBord, epaisseurDp: Float = 1f): GradientDrawable =
        GradientDrawable().apply { cornerRadius = px(rayonDp).toFloat(); setColor(couleur); setStroke(px(epaisseurDp), bord) }

    private fun carte(rayonDp: Float = 22f, padDp: Float = 16f, orientation: Int = LinearLayout.VERTICAL, bordOr: Boolean = false): LinearLayout = LinearLayout(this).apply {
        this.orientation = orientation
        background = if (bordOr) fondCarte(rayonDp, bord = theme.or, epaisseurDp = 1.5f) else fondCarte(rayonDp)
        setPadding(px(padDp), px(padDp - 2f), px(padDp), px(padDp - 2f))
    }

    private fun bouton(t: String, plein: Boolean, action: () -> Unit): TextView = texte(t, 14f, if (plein) theme.fond else theme.encre, Polices.gras(this)).apply {
        gravity = Gravity.CENTER; setPadding(px(16f), px(8f), px(16f), px(8f)); minHeight = px(Ui.CIBLE_MIN)
        background = if (plein) GradientDrawable().apply { cornerRadius = px(22f).toFloat(); setColor(theme.or) } else fondCarte(22f, couleur = 0, bord = theme.carteBord)
        isClickable = true; isFocusable = true
        setOnClickListener { Geste.retenir(this); action() }
    }

    private fun pastille(couleur: Int): View = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(couleur) } }

    private fun disque(dessin: Drawable?, tailleDp: Float): FrameLayout {
        val taille = px(tailleDp)
        val cadre = FrameLayout(this)
        val image = ImageView(this).apply {
            setImageDrawable(dessin); scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) { o.setOval(0, 0, v.width, v.height) }
            }
        }
        cadre.addView(image, FrameLayout.LayoutParams(taille, taille, Gravity.CENTER))
        return cadre
    }

    /** L'icône d'un dossier : quatre pastilles des applis qu'il contient, sur un disque. */
    private fun disqueDossier(d: Accueil.Dossier, tailleDp: Float): FrameLayout {
        val taille = px(tailleDp)
        val cadre = FrameLayout(this)
        cadre.addView(pastille(Palette.melanger(theme.carte, theme.encre, 0.08f)), FrameLayout.LayoutParams(taille, taille, Gravity.CENTER))
        val grille = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val mini = (taille * 0.30f).toInt(); val gap = (taille * 0.06f).toInt()
        for (r in 0 until 2) {
            val ligne = LinearLayout(this)
            for (c in 0 until 2) {
                val pkg = d.pkgs.getOrNull(r * 2 + c)
                val v = if (pkg != null) disque(Accueil.icone(this, pkg), mini / dm.density) else View(this)
                ligne.addView(v, LinearLayout.LayoutParams(mini, mini).apply { setMargins(gap / 2, gap / 2, gap / 2, gap / 2) })
            }
            grille.addView(ligne)
        }
        cadre.addView(grille, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        return cadre
    }

    private fun salutation(): String {
        // Le message d'accueil réglé par l'administrateur (Vision → Configurer → Accueil), déjà rendu par Home
        // Assistant pour cet appareil (variables, langue, heure). Ancien serveur : la salutation de l'appli.
        val accueil = donnees?.optJSONObject("moi")?.optString("accueil").orEmpty().trim()
        if (accueil.isNotEmpty()) return accueil
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val prenom = donnees?.optJSONObject("moi")?.optString("prenom").orEmpty()
        // Vocabulaire neutre : sans prénom (écran commun), la salutation reste seule.
        val qui = if (!tele && prenom.isNotEmpty() && profil() != "affichage") prenom else ""
        val (avec, seul) = when { h < 5 -> R.string.lanceur_salut_nuit to R.string.lanceur_salut_nuit_seul; h < 12 -> R.string.lanceur_salut_jour to R.string.lanceur_salut_jour_seul
            h < 18 -> R.string.lanceur_salut_apres_midi to R.string.lanceur_salut_apres_midi_seul; else -> R.string.lanceur_salut_soir to R.string.lanceur_salut_soir_seul }
        return if (qui.isEmpty()) getString(seul) else getString(avec, qui)
    }

    /** affichage (télé commune), parent, enfant : dit par Home Assistant, sinon le dernier connu, sinon déduit du poll. */
    private fun profil(): String {
        val p = donnees?.optJSONObject("moi")?.optString("public").orEmpty()
        if (p.isNotEmpty()) return p
        if (profilConnu.isNotEmpty()) return profilConnu
        return if (tele) "affichage" else if (Etat.parent) "parent" else if (cfg.personne.isNotEmpty()) "enfant" else "affichage"
    }

    /** La roue des réglages, en bas à droite. */
    private fun roueReglages(tailleDp: Float): View {
        val b = FrameLayout(this).apply { background = fondCarte(999f); isClickable = true; isFocusable = true; contentDescription = getString(R.string.lanceur_reglages_vision); tag = "roue" }
        b.addView(icone(R.drawable.ic_reglages, tailleDp * 0.5f, theme.or), FrameLayout.LayoutParams(px(tailleDp * 0.5f), px(tailleDp * 0.5f), Gravity.CENTER))
        Ui.anneauFocus(b, theme, 999f)
        b.setOnClickListener { startActivity(Intent(this, ReglagesTvActivity::class.java)) }
        return b
    }

    /**
     * APP-01 : appareil pas encore relié à Vision. Un bandeau permanent en haut de l'accueil (« … n'est pas encore
     * reliée à Vision — [Relier] »), qui reste tant que l'appareil n'est pas inscrit ; sur TV, le premier focus y est
     * posé. « Relier » ouvre l'écran Bienvenue (QR en premier, APP-02).
     */
    /**
     * 1.90.5 (retour d'essai) : la zone du haut de la télé, une liste de widgets (ZoneHaut). L'accueil d'avant (« classique ») reste
     * tel quel ; sinon l'heure et la météo laissent la place aux widgets choisis, côte à côte. Les demandes en attente
     * (colonne de droite) restent visibles dans tous les cas. L'invite « Personnaliser cette zone » (code des Décideurs)
     * est discrète sous la bande.
     */
    private fun appliquerZoneHaut(entete: LinearLayout, blocHeure: View, droite: LinearLayout, tailleTexte: Float, coteOeil: Int) {
        val l = ZoneHaut.widgets(this)
        if (l == listOf(ZoneHaut.CLASSIQUE)) return
        entete.removeView(blocHeure)
        for (t in listOf("icone_meteo", "temp", "cond", "pluie")) droite.findViewWithTag<View>(t)?.let { v ->
            // L'icône et la température sont dans une même ligne : la ligne entière part.
            val ligne = if (v.parent !== droite) v.parent as? View else v
            ligne?.let { droite.removeView(it) }
        }
        val zone = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = ZoneHaut.TAG }
        val rangee = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false }
        // 1.91.2 (tableau de bord) : avec des widgets Home Assistant, la bande défile de côté (télécommande gauche / droite).
        val tableau = l.any { ZoneHaut.estHa(it) }
        for (code in l) {
            val v = widgetZone(code, tele = true, tailleTexte = tailleTexte, coteOeil = coteOeil) ?: continue
            val poids = if (!tableau && (code == ZoneHaut.REMERCIEMENT || code == ZoneHaut.MESSAGE)) 1f else 0f
            val lp = if (ZoneHaut.estHa(code)) (v.layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                else LinearLayout.LayoutParams(if (poids > 0f) 0 else ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, poids)
            rangee.addView(v, lp.apply { marginEnd = px(tv(32f)) })
        }
        if (tableau) {
            val defile = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; tag = "zone_haut_tableau" }
            rangee.setPadding(px(6f), px(10f), px(6f), px(10f))
            defile.addView(rangee)
            zone.addView(defile)
        } else zone.addView(rangee)
        // 1.91.2 (retour d'essai) : plus d'invite visible en permanence ; la zone se personnalise dans Réglages › Accueil › Zone du haut.
        entete.addView(zone, 0, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    /**
     * Un widget de la zone du haut (télé et téléphone) : l'horloge stylisée (avec ses réglages : taille, 12 / 24 h,
     * date), la météo, le remerciement ou le message. Null pour l'accueil d'avant (il garde ses propres vues).
     */
    private fun widgetZone(code: String, tele: Boolean, tailleTexte: Float, coteOeil: Int, paysage: Boolean = false, lierDate: Boolean = true,
                           /** 16-10 : une rangée du haut ou du bas de la télé — horloge et date plus petites, pour tenir. */
                           rangee: Boolean = false,
                           /** 1.93.05 : une zone de côté de la télé — le lecteur prend la largeur de la zone. */
                           souple: Boolean = false): View? = when (code) {
        ZoneHaut.HORLOGE -> {
            val r = ZoneHaut.reglagesHorloge(this)
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; tag = "zone_horloge" }
            val base = if (tele && rangee) tv(120f) else if (tele) tv(220f) else if (paysage) 118f else 148f
            val cote = px(base * r.taille.facteur)
            // 1.91.2 : le style choisi pour cet appareil (StylesHorloge) ; l'anneau reste carré, les autres plus larges.
            col.addView(StylesHorloge.vue(this, theme, r.style, ZoneHaut.format24(this, r)), LinearLayout.LayoutParams(StylesHorloge.largeur(r.style, cote), cote))
            // La date du tic de l'appli (1.83.1) : montrée sous l'horloge si le réglage le veut.
            val d = texte(dateFmt.format(Date()), if (tele && rangee) tv(20f) else if (tele) tv(26f) else 14f, theme.encre2, Polices.moyen(this)).apply { gravity = Gravity.CENTER }
            if (lierDate) date = d
            if (r.date) col.addView(d, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(2f) })
            col
        }
        ZoneHaut.METEO -> {
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; tag = "zone_meteo" }
            val cote = px(if (tele) tv(56f) else 22f)
            // 16-10 (recette) : la ligne garde la hauteur de l'icône même sans température (avant l'arrivée des données).
            val ligne = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = cote }
            ligne.addView(IconeMeteo(this, theme.encre2).apply { tag = "icone_meteo" }, LinearLayout.LayoutParams(cote, cote))
            ligne.addView(texte("", if (tele) tv(60f) else 20f, theme.encre, Polices.outfitLeger(this)).apply { tag = "temp"; setPadding(px(6f), 0, 0, 0); includeFontPadding = false })
            col.addView(ligne)
            col.addView(texte("", if (tele) tv(22f) else 12f, theme.encre2).apply { tag = "cond"; gravity = Gravity.CENTER })
            col
        }
        ZoneHaut.REMERCIEMENT, ZoneHaut.MESSAGE -> ZoneHaut.vueRemerciement(this, code, theme, tailleTexte, coteOeil)
        // 1.93.05 (l'auteur, retour 34) : le widget « Lecteur musique » (pochette, titre, lecture/pause, suivant) ; visible même
        // quand rien ne joue (« Rien ne joue »), puisqu'on l'a ajouté. Dans une zone de côté, il prend la largeur de la zone.
        ZoneHaut.LECTEUR -> MiniLecteur(this, theme, tele, fondPochetteAccueil, souple = souple, toujours = true).apply {
            tag = "mini_lecteur"
            if (souple) layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        else -> if (tele && ZoneHaut.estHa(code)) widgetHaZone(code) else null
    }

    /** La pochette floutée en fond de l'accueil (option de Musique), que suit le widget « Lecteur musique ». */
    private var fondPochetteAccueil: ImageView? = null

    /** Un widget dans une zone de la colonne de la télé (gauche, droite, rangée du haut ou du bas). */
    private fun widgetZoneTele(z: String, code: String, tailleTexte: Float, coteOeil: Int, lierDate: Boolean): View? = when {
        ZoneHaut.estHa(code) -> widgetHaZone(code, z)
        // 16-10 (parité) : un widget de Vision du téléphone (chauffage, école…), à sa taille, agrandi pour la télé.
        DispositionAccueil.estWidgetVision(code) -> Accueil.widget(DispositionAccueil.codeWidgetVision(code))?.let { w ->
            widgetIntegreTele(w.code, w.w, w.h) { DispositionAccueil.poserWidgetsZone(this, z, DispositionAccueil.widgetsZone(this, z) - code); construire() }
                .apply { layoutParams = LinearLayout.LayoutParams(px(tv(200f)) * w.w, px(tv(124f)) * w.h) }
        }
        else -> widgetZone(code, tele = true, tailleTexte = tailleTexte, coteOeil = coteOeil, lierDate = lierDate, rangee = z in DispositionAccueil.RANGEES,
            souple = z !in DispositionAccueil.RANGEES)
    }

    /**
     * 1.91.2 (l'auteur, tableau de bord de la télé) : un widget Home Assistant partagé, dans la zone du haut. Lecture seule ;
     * haut / bas font défiler son contenu (selon son option « Défilement »), OK ouvre son menu (détail, modifier, retirer).
     * Réglage propre à la zone (« zone-<entité> »), créé à la première modification ; avant, celui de Home Assistant.
     */
    private fun widgetHaZone(code: String, zoneTele: String? = null): View? {
        val eid = ZoneHaut.entiteHa(code)
        val ent = WidgetsHaStock.entite(this, eid) ?: return null
        val id = "zone-$eid"
        // 17-10 (l'auteur, point 20 : « le widget consigne ne se règle pas à la télécommande ») : le style de départ est le premier
        // que propose Home Assistant (réglage pour une consigne), comme sur le téléphone ; avant, toujours une tuile, sans − ni +.
        val r = WidgetsHaStock.reglage(this, id) ?: WidgetsHa.Reglage(id, listOf(eid), WidgetsHa.stylesProposes(ent).first(), 2, 2)
        val v = widgetHaTele(Accueil.Place(code, 0, 0, r.w, r.h), r,
            modifier = { WidgetsHaStock.ecrire(this, r); ouvrirEditeurHa(id) },
            retirer = {
                if (zoneTele != null) DispositionAccueil.poserWidgetsZone(this, zoneTele, DispositionAccueil.widgetsZone(this, zoneTele) - code)
                else ZoneHaut.poser(this, ZoneHaut.widgets(this) - code)
                construire()
            })
        // 17-10 (l'auteur, retour 20, recette télé) : une rangée de zone fait 62 dp ; un réglage (nom, valeur, − et +) n'y tient
        // pas — en 1 × 1 et 2 × 1, − et + étaient coupés sous le bord (le focus s'y posait, invisible). Deux rangées au moins.
        // l'auteur (17-10) : « le même widget que sur le mobile » — avec la ligne de HA, la hauteur d'une case du téléphone
        // (environ 160 dp en 2 × 2), pour montrer nom, valeur, − / + ET la ligne, comme sur le mobile.
        val rangees = if (r.style == WidgetsHa.REGLAGE) maxOf(r.h.toFloat(), if (ent.texte.isNullOrBlank()) 2f else 2.6f) else r.h.toFloat()
        v.layoutParams = LinearLayout.LayoutParams(px(tv(200f)) * r.w, (px(tv(124f)) * rangees).toInt())
        // 16-10 (retour d'essai) : sans cadre des widgets, la tuile Home Assistant n'a plus sa carte (dans une zone de la colonne).
        if (zoneTele != null && !DispositionAccueil.cadreWidgets(this)) (v as? ViewGroup)?.getChildAt(0)?.background = null
        return v
    }

    /**
     * 1.90.5 (l'auteur, accueil du téléphone) : la zone du haut du téléphone, centrée, une liste de widgets (ZoneHaut) dans
     * l'ordre choisi par le Décideur. Par défaut, l'horloge stylisée de Vision (celle de la veille) ; la météo, le
     * remerciement ou un message en option. L'ancien gros bloc heure, date et météo et l'en-tête « VISION » ont disparu.
     * Rien sous la date (l'auteur, 1.91.2) : le mode du Décideur se voit dans les Réglages ; la zone se personnalise par un
     * appui long (Décideur) ou dans Réglages › Accueil › Zone du haut.
     */
    private fun enteteTelephone(profil: String, paysage: Boolean): View {
        val entete = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(px(4f), px(8f), px(4f), px(4f)); tag = "entete_telephone" }
        // L'heure et la date du tic de l'appli (1.83.1) : hors de l'écran sauf si l'horloge montre la date.
        heure = texte(heureFmt.format(Date()), 64f, theme.encre, Polices.outfitFin(this), -0.02f).apply { includeFontPadding = false }
        date = texte(dateFmt.format(Date()), 14f, theme.encre2, Polices.moyen(this))
        for (code in ZoneHaut.widgets(this)) {
            val v = widgetZone(code, tele = false, tailleTexte = 20f, coteOeil = px(64f), paysage = paysage) ?: continue
            val large = code == ZoneHaut.REMERCIEMENT || code == ZoneHaut.MESSAGE
            entete.addView(v, LinearLayout.LayoutParams(if (large) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(4f) })
        }
        // 1.91.2 (retour d'essai) : rien sous la date. Plus de pastille « Gestion » (le mode se voit dans les Réglages) ni de lien
        // « Personnaliser cette zone » : le Décideur personnalise la zone par un appui long (retour haptique), ou dans
        // Réglages › Accueil › Zone du haut.
        if (profil == "parent") ZoneHaut.appuiLongPourPersonnaliser(this, entete) { recreate() }
        return entete
    }

    private fun bandeauNonRelie(colonne: LinearLayout, taille: Float) {
        if (cfg.inscrit) return
        val b = carte(rayonDp = 18f, padDp = 14f, orientation = LinearLayout.HORIZONTAL, bordOr = true).apply { gravity = Gravity.CENTER_VERTICAL; tag = "non_relie" }
        b.addView(pastille(theme.encre2), LinearLayout.LayoutParams(px(10f), px(10f)).apply { rightMargin = px(12f) })
        val textes = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textes.addView(texte(getString(R.string.nonrelie_titre), taille, theme.encre, Polices.gras(this)))
        textes.addView(texte(getString(if (tele) R.string.nonrelie_tele else R.string.nonrelie_appareil), taille * 0.8f, theme.encre2))
        b.addView(textes, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = px(12f) })
        val relier = bouton(getString(R.string.nonrelie_relier), plein = true) { startActivity(Intent(this, SetupActivity::class.java)) }.apply {
            tag = "non_relie_relier"; textSize = taille; minHeight = px(48f); minWidth = px(96f)
        }
        Ui.anneauFocus(relier, theme, 22f)
        b.addView(relier)
        colonne.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(8f); bottomMargin = px(8f) })
    }

    /** APP-01 : TV pas encore reliée : le focus va sur « Relier » (bandeau), pas sur la première appli. */
    private fun focusNonRelie(): Boolean {
        if (!tele || cfg.inscrit) return false
        val v = racine.findViewWithTag<View>("non_relie_relier") ?: return false
        v.requestFocus(); return true
    }

    // ------------------------------------------------------------------ construction

    private fun construire() {
        constructions++
        val focusAvant = (currentFocus?.tag as? String) ?: (currentFocus?.tag as? Accueil.Place)?.cle
        // 1.83.1 : chaque reconstruction complète dit dans le journal ce qui a changé (diagnostic des glitchs).
        val nouvelle = empreinte()
        if (empreinteApps.isNotEmpty()) {
            val av = empreinteApps.split('|'); val ap = nouvelle.split('|')
            val parts = ap.indices.filter { it >= av.size || av[it] != ap[it] }
            android.util.Log.i("VisionAccueil", "accueil rebâti (n° $constructions) ; parties changées : ${if (parts.isEmpty()) "aucune (action de l'utilisateur)" else parts.joinToString()}")
        }
        empreinteApps = nouvelle
        panneau = null
        vuesHa.clear(); cadreRedim = null
        racine.removeAllViews()
        racine.setBackgroundColor(theme.fond)
        // 1.91.x (retour d'essai) : l'arrière-plan choisi pour cet appareil (FondAccueil), avec son voile de lisibilité.
        racine.addView(FondAccueil.vue(this, theme, tele), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // l'auteur (13 octobre) : télé en rangées ou en colonne (« Vertical ») ; téléphone en grille, pages ou liste alphabétique.
        heure = null; date = null  // reposées par la disposition (zone du haut, horloge) ; sinon d'anciennes vues
        when {
            tele -> if (teleVerticale()) construireTeleVertical() else construireTele()
            Accueil.mode(this) == Accueil.MODE_ALPHA -> { indexAlpha = null; construireAlpha() }
            else -> { construireTelephone(); poserTiroir() }
        }
        appsVues = try { appsAffichees() } catch (_: Exception) { "" }
        Ui.laisserDeborder(racine)
        appliquerDonnees()
        brancherFluxHa()
        if (focusAvant != null) racine.post { trouverParTag(racine, focusAvant)?.requestFocus() }
    }

    private fun trouverParTag(v: View, cle: String): View? {
        val t = v.tag
        if ((t is String && t == cle) || (t is Accueil.Place && t.cle == cle)) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) trouverParTag(v.getChildAt(i), cle)?.let { return it }
        return null
    }

    // ================================================================== TÉLÉ

    private var deplacement: String? = null
    private var rangeeApps: LinearLayout? = null

    private fun construireTele() {
        Accueil.initialiserTele(this)
        // 17-10 (l'auteur, points 10, 21) : 5 % en haut et en bas aussi (4 % : la roue sortait de la zone sûre) ; ZoneSure.
        val margeX = ZoneSure.x(this@LanceurActivity, 0.05f); val margeY = ZoneSure.y(this@LanceurActivity, 0.05f)
        val colonne = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(margeX, margeY, margeX, margeY) }
        // Recette lot 1 (TV 720p, grande police, bandeau « non reliée ») : quand tout ne tient pas en hauteur, l'accueil
        // défile (le focus de la télécommande l'emmène) au lieu d'écraser la rangée d'applis et la roue sous le bas de l'écran.
        val defileAccueil = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; clipToPadding = false; tag = "defile_accueil" }
        defileAccueil.addView(colonne, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        racine.addView(defileAccueil, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // l'auteur (14 octobre) : plus d'en-tête « VISION » sur l'accueil de la télé, comme sur le téléphone.
        bandeauNonRelie(colonne, tv(34f))

        // L'heure et la date à gauche ; la météo et la pluie à droite.
        val entete = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, px(6f), 0, 0) }
        val blocHeure = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // 1.83.1 : l'heure et la date écrites dès la création (avant : vides jusqu'au tic suivant, d'où le clignotement).
        heure = texte(heureFmt.format(Date()), tv(176f), theme.encre, Polices.outfitFin(this), -0.02f).apply { includeFontPadding = false }
        date = texte(dateFmt.format(Date()), tv(34f), theme.encre2, Polices.moyen(this))
        blocHeure.addView(heure); blocHeure.addView(date)
        blocHeure.addView(texte("", tv(38f), theme.encre, Polices.moyen(this)).apply { tag = "salut"; setPadding(0, px(10f), 0, 0)
            // Message d'accueil libre : sur télé, une ligne et des points de suspension ; le texte entier reste lu par
            // l'accessibilité (contentDescription, posé avec le texte).
            if (tele) { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END } })
        entete.addView(blocHeure, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val droite = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END; setPadding(0, px(14f), 0, 0) }
        val ligneMeteo = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        ligneMeteo.addView(IconeMeteo(this, theme.encre2).apply { tag = "icone_meteo"; layoutParams = ViewGroup.LayoutParams(px(tv(64f)), px(tv(64f))) })
        ligneMeteo.addView(texte("", tv(72f), theme.encre, Polices.outfitLeger(this)).apply { tag = "temp"; setPadding(px(10f), 0, 0, 0); includeFontPadding = false })
        droite.addView(ligneMeteo)
        droite.addView(texte("", tv(24f), theme.encre2).apply { tag = "cond"; gravity = Gravity.END })
        droite.addView(lignePluie(tv(18f), tv(28f)).apply { tag = "pluie" })
        val demandes = LinearLayout(this).apply {
            tag = "demandes"; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE
            background = fondCarte(999f); setPadding(px(14f), px(6f), px(16f), px(6f))
        }
        demandes.addView(pastille(theme.pourpre), LinearLayout.LayoutParams(px(8f), px(8f)).apply { rightMargin = px(10f) })
        demandes.addView(texte("", tv(20f), theme.encre, Polices.demiGras(this)).apply { tag = "demandes_texte" })
        droite.addView(demandes, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(12f) })
        entete.addView(droite)
        colonne.addView(entete)
        // 1.90.5 (retour d'essai) : la zone du haut (ZoneHaut) — l'heure et la météo, ou le remerciement avec l'œil joyeux.
        appliquerZoneHaut(entete, blocHeure, droite, tv(52f), px(tv(132f)))
        // 1.86.5 (retour d'essai) : le mini-lecteur quand de la musique joue (et, en option, la pochette floutée en fond).
        val fondPochette = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0.32f; visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        racine.addView(fondPochette, 1, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // 1.93.05 (l'auteur, retour 34) : plus de mini-lecteur posé d'office en haut de l'accueil de la télé ; c'est le widget
        // « Lecteur musique » de la zone du haut, seulement si on l'a ajouté (la pochette floutée le suit).
        colonne.findViewWithTag<MiniLecteur>("mini_lecteur")?.fond = fondPochette
        // Les messages de Home Assistant pas encore lus : on les rouvre d'ici.
        // 1.91.0 : les messages en rangée bord à bord, comme les applications (plusieurs messages ne sont jamais coupés).
        val messages = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, px(14f), 0, px(6f)); clipToPadding = false; clipChildren = false }
        rangeeMessages = messages
        val defileMessages = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false }
        defileMessages.addView(messages)
        colonne.addView(defileMessages)
        BordABord.poser(defileMessages, messages, margeX, margeX, 0)
        remplirMessagesTele()

        colonne.addView(View(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Les applications, en rangée (qui défile si elle déborde), et le « + » au bout.
        colonne.addView(etiquetteSection(getString(R.string.lanceur_applications), tv(20f)))
        // 1.91.0 (photos de l'auteur : icônes coupées net au bord de la marge) : la rangée défile d'un bord de l'écran à l'autre ;
        // au repos, la première appli est sur la zone sûre ; ce qui passe dans l'overscan s'efface en fondu. La marge haute
        // et basse laisse la place à l'appli agrandie au focus (×1,12), puisque la rangée garde son propre cadre.
        val rangee = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(px(6f), px(16f), px(6f), px(14f)); clipToPadding = false; clipChildren = false }
        rangeeApps = rangee
        val defile = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false }
        defile.addView(rangee)
        colonne.addView(defile)
        BordABord.poser(defile, rangee, margeX, margeX, px(6f))
        remplirRangeeTele()

        // Les widgets posés sur la télé : 16-10 (l'auteur, parité) ceux de Vision (chauffage, école…) comme sur le téléphone, et
        // ceux de Home Assistant, qui agissent (avant : lecture seule) ; ceux de HA masqués quand le module « entites » est coupé.
        val poses = Accueil.disposition(this).filter { it.estWidget && !WidgetsAndroid.estAndroid(it.code) }.sortedWith(compareBy({ it.row }, { it.col }))
        val integres = poses.filter { !WidgetsHa.estHa(it.code) && Accueil.widget(it.code) != null }
        val has = if (!Modules.actif(this, Modules.ENTITES)) emptyList() else poses.filter { WidgetsHa.estHa(it.code) }
            .mapNotNull { pl -> WidgetsHaStock.reglage(this, WidgetsHa.id(pl.code))?.let { pl to it } }
        run {
            if (has.isNotEmpty() || integres.isNotEmpty()) {
                colonne.addView(etiquetteSection(getString(R.string.lanceur_widgets_tele), tv(20f)))
                val rangeeW = LinearLayout(this).apply { setPadding(px(6f), px(14f), px(6f), px(12f)); clipToPadding = false; clipChildren = false }
                val defileW = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false }
                defileW.addView(rangeeW)
                integres.forEach { pl ->
                    val lp = LinearLayout.LayoutParams(px(tv(200f)) * pl.w, px(tv(124f)) * pl.h).apply { rightMargin = px(12f) }
                    rangeeW.addView(widgetIntegreTele(pl.code, pl.w, pl.h) { Accueil.retirerDeLaGrille(this, pl.cle); construire() }, lp)
                }
                has.forEach { (pl, r) ->
                    val lp = LinearLayout.LayoutParams(px(tv(200f)) * pl.w, px(tv(124f)) * pl.h).apply { rightMargin = px(12f) }
                    rangeeW.addView(widgetHaTele(pl, r), lp)
                }
                colonne.addView(defileW)
                // 1.91.0 : bord à bord, comme la rangée des applications.
                BordABord.poser(defileW, rangeeW, margeX, margeX, px(6f))
            }
        }

        // Le pied : l'aide du mode déplacement, et la roue des réglages à droite.
        val pied = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END; setPadding(0, px(14f), 0, 0) }
        pied.addView(texte(if (deplacement != null) getString(R.string.lanceur_aide_deplacement) else "", tv(20f), theme.encre2).apply { tag = "aide" }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // Lot 1 (A7, APP-21) : la roue fait au moins 48 dp (avant : 28 dp sur une télé 1080p).
        val roue = maxOf(tv(96f), Ui.CIBLE_MIN)
        pied.addView(roueReglages(roue), LinearLayout.LayoutParams(px(roue), px(roue)))
        colonne.addView(pied)
    }

    private var rangeeMessages: LinearLayout? = null

    /** Une pastille par message non lu ; OK le rouvre (le message reste là tant qu'il n'est pas lu). */
    private fun remplirMessagesTele() {
        val r = rangeeMessages ?: return
        r.removeAllViews()
        if (!Modules.actif(this, Modules.MESSAGES)) return
        for (m in MessagesRiches.enAttente(this)) {
            val p = texte("✉  " + m.optString("title").ifEmpty { getString(R.string.lanceur_message) }, tv(20f), theme.encre, Polices.demiGras(this)).apply {
                background = fondCarte(999f); setPadding(px(16f), px(8f), px(18f), px(8f))
                isFocusable = true; isClickable = true
                setOnFocusChangeListener { v, a -> v.animate().scaleX(if (a) 1.08f else 1f).scaleY(if (a) 1.08f else 1f).setDuration(120).start() }
                setOnClickListener { MessagesRiches.rouvrir(this@LanceurActivity, m.optString("uid")) }
            }
            r.addView(p, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = px(10f) })
        }
    }

    private fun remplirRangeeTele() {
        if (teleVerticale()) { remplirColonneTele(); return }
        val rangee = rangeeApps ?: return
        rangee.removeAllViews()
        val cases = Accueil.cases(this)
        val cles = cases.map { it.cle }
        cases.forEach { c -> rangee.addView(caseTele(c, cles), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = px(10f) }) }
        val plus = casePlusTele()
        rangee.addView(plus, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // 1.75.0 : accueil vide (télé installée à neuf) : on dit quoi faire, à la télécommande, et « Ajouter » a le focus.
        if (cases.isEmpty()) {
            rangee.addView(texte(getString(R.string.lanceur_accueil_vide_tele),
                tv(24f), theme.encre2, Polices.moyen(this)).apply {
                gravity = Gravity.CENTER_VERTICAL; maxWidth = (dm.widthPixels * 0.6f).toInt(); setPadding(px(18f), 0, 0, 0)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
            plus.post { if (currentFocus == null || currentFocus?.tag == "plus") plus.requestFocus() }
        }
    }

    /** Une appli ou un dossier sur la télé : disque, nom ; anneau d'or et grossissement au focus ; soulevée en mode déplacement. */
    private fun caseTele(c: Accueil.Case, cles: List<String>): View {
        val taille = tv(124f)
        val contenu = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(px(8f), px(10f), px(8f), px(8f)); tag = c.cle }
        val enDeplacement = deplacement == c.cle
        val anneau = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0); setStroke(px(2f), if (enDeplacement) theme.pourpre else theme.or) }; visibility = if (enDeplacement) View.VISIBLE else View.INVISIBLE }
        val cadre = FrameLayout(this)
        val d = if (c.dossier != null) disqueDossier(c.dossier, taille) else disque(Accueil.icone(this, c.pkg!!), taille)
        cadre.addView(d, FrameLayout.LayoutParams(px(taille), px(taille), Gravity.CENTER))
        cadre.addView(anneau, FrameLayout.LayoutParams(px(taille + 16f), px(taille + 16f), Gravity.CENTER))
        contenu.addView(cadre, LinearLayout.LayoutParams(px(taille + 16f), px(taille + 16f)))
        val nom = if (c.dossier != null) c.dossier.nom else Accueil.etiquette(this, c.pkg!!)
        val libelle = texte(nom, tv(22f), theme.encre2, Polices.moyen(this)).apply { maxLines = 2; minLines = 2 /* recette lot 1 : chaque case réserve ses deux lignes (« Google Play Store » perdait « Store » en 720p) */; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER; setPadding(0, px(8f), 0, 0); maxWidth = px(taille + 24f); Ui.motsEntiers(this, tv(22f), tv(22f) * 0.55f) }
        contenu.contentDescription = nom
        contenu.addView(libelle)
        contenu.isFocusable = true; contenu.isClickable = true
        if (enDeplacement) { contenu.scaleX = 1.18f; contenu.scaleY = 1.18f; contenu.translationY = -px(10f).toFloat() }
        contenu.setOnFocusChangeListener { v, a ->
            if (deplacement != null) return@setOnFocusChangeListener
            anneau.visibility = if (a) View.VISIBLE else View.INVISIBLE
            libelle.setTextColor(if (a) theme.encre else theme.encre2); libelle.typeface = if (a) Polices.gras(this) else Polices.moyen(this)
            v.animate().scaleX(if (a) 1.12f else 1f).scaleY(if (a) 1.12f else 1f).setDuration(140).start()
        }
        contenu.setOnClickListener { if (deplacement != null) { deplacement = null; construire() } else if (c.dossier != null) ouvrirDossier(c.dossier, cles) else ouvrir(c.pkg!!) }
        contenu.setOnLongClickListener { if (deplacement == null) menuTele(c, cles); true }
        // 1.78.0 : la touche Menu de la télécommande ouvre le même menu que l'appui long.
        contenu.setOnKeyListener { _, code, ev ->
            if (code == KeyEvent.KEYCODE_MENU && ev.action == KeyEvent.ACTION_UP && deplacement == null) { menuTele(c, cles); true } else false
        }
        return contenu
    }

    /** Le « + » en bout de rangée : choisir d'autres applications à montrer. */
    private fun casePlusTele(): View {
        val taille = tv(124f)
        val contenu = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(px(8f), px(10f), px(8f), px(8f)); tag = "plus" }
        val rond = FrameLayout(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0); setStroke(px(1.5f), theme.encre2, px(10f).toFloat(), px(8f).toFloat()) } }
        rond.addView(icone(R.drawable.ic_plus, taille * 0.4f, theme.encre2), FrameLayout.LayoutParams(px(taille * 0.4f), px(taille * 0.4f), Gravity.CENTER))
        val cadre = FrameLayout(this)
        cadre.addView(rond, FrameLayout.LayoutParams(px(taille), px(taille), Gravity.CENTER))
        contenu.addView(cadre, LinearLayout.LayoutParams(px(taille + 16f), px(taille + 16f)))
        contenu.addView(texte(getString(R.string.exceptions_ajouter), tv(22f), theme.encre2, Polices.moyen(this)).apply { gravity = Gravity.CENTER; setPadding(0, px(8f), 0, 0) })
        contenu.isFocusable = true; contenu.isClickable = true
        contenu.setOnFocusChangeListener { v, a -> rond.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (a) theme.carte else 0); setStroke(px(if (a) 2f else 1.5f), if (a) theme.or else theme.encre2, px(10f).toFloat(), px(8f).toFloat()) }; v.animate().scaleX(if (a) 1.12f else 1f).scaleY(if (a) 1.12f else 1f).setDuration(140).start() }
        contenu.setOnClickListener { ajouterTele() }
        return contenu
    }

    /** Afficher ou cacher des applications : la liste complète, à cocher. */
    private fun choisirApplis() {
        val pm = packageManager
        val toutes = Accueil.applicationsInstallees(this)
        val masquees = Accueil.masquees(this).toMutableSet()
        val noms = toutes.map { it.loadLabel(pm).toString() }.toTypedArray()
        val coches = toutes.map { it.activityInfo.packageName !in masquees }.toBooleanArray()
        Ui.dialogue(this).setTitle(getString(R.string.lanceur_applis_accueil))
            .setMultiChoiceItems(noms, coches) { _, k, on -> val p = toutes[k].activityInfo.packageName; if (on) masquees.remove(p) else masquees.add(p) }
            .setPositiveButton(getString(R.string.commun_lot2_garder)) { _, _ ->
                toutes.forEach { ri -> val p = ri.activityInfo.packageName; val cachee = p in masquees; if (cachee != (p in Accueil.masquees(this))) { Accueil.cacher(this, p, cachee); if (!tele && !cachee) Accueil.ajouterALaGrille(this, p) } }
                construire()
            }
            .setNegativeButton(getString(R.string.commun_lot2_annuler), null).show()
    }

    /** Le mode déplacement : la case suit gauche et droite. */
    private fun glisserTele(cle: String, sens: Int) {
        val cles = Accueil.cases(this).map { it.cle }
        val k = cles.indexOf(cle)
        if (k < 0) return
        val vers = (k + sens).coerceIn(0, cles.size - 1)
        if (vers == k) return
        Accueil.deplacer(this, cles, cle, vers)
        remplirRangeeTele()
        empreinteApps = empreinte()
        rangeeApps?.post { trouverParTag(racine, cle)?.let { v -> v.requestFocus(); v.parent?.requestChildFocus(v, v) } }
    }

    /** Le menu de la télé, comme celui d'Android TV : ouvrir, déplacer, retirer, infos, désinstaller. */
    private fun menuTele(c: Accueil.Case, cles: List<String>) {
        val nom = if (c.dossier != null) c.dossier.nom else Accueil.etiquette(this, c.pkg!!)
        val choix = ArrayList<Pair<String, () -> Unit>>()
        choix.add(getString(R.string.lanceur_ouvrir) to { if (c.dossier != null) ouvrirDossier(c.dossier, cles) else ouvrir(c.pkg!!) })
        choix.add(getString(R.string.partyecran_deplacer) to { deplacement = c.cle; construire(); racine.post { trouverParTag(racine, c.cle)?.requestFocus() }; Unit })
        if (c.pkg != null) {
            choix.add(getString(R.string.lanceur_retirer_accueil) to { Accueil.cacher(this, c.pkg, true); construire() })
            // 1.78.0 : pour une appli qui bugue. Lot accueil (point 7) : arrêt et cache sans code (page restreinte), Infos avec code.
            if (ActionsAppli.concerne(this, c.pkg)) {
                choix.add(getString(R.string.lanceur_forcer_arret) to { ActionsAppli.forcerArret(this, c.pkg, nom) })
                choix.add(getString(R.string.lanceur_vider_cache) to { ActionsAppli.viderCache(this, c.pkg, nom) })
                if (ActionsAppli.proprietaire(this)) choix.add(getString(R.string.lanceur_effacer_donnees) to { ActionsAppli.effacerDonnees(this, c.pkg, nom) })
            }
            // 1.81.0 : la tuile Mode Party n'est pas une appli installée (ni infos ni désinstallation).
            if (c.pkg != Party.CLE) {
                choix.add(getString(R.string.lanceur_infos_appli) to { ActionsAppli.infosProtegees(this, c.pkg) })
                choix.add(getString(R.string.lanceur_desinstaller) to { Paquets.desinstaller(this, c.pkg) })
            }
        } else {
            choix.add(getString(R.string.lanceur_renommer_dossier) to { renommer(c.dossier!!) })
            choix.add(getString(R.string.lanceur_defaire_dossier) to { dissoudre(c.cle); construire() })
        }
        Ui.dialogue(this).setTitle(nom).setItems(choix.map { it.first }.toTypedArray()) { _, i -> choix[i].second() }.show()
    }

    // ================================================================== TÉLÉPHONE

    private var zoneRetrait: View? = null
    // 1.63.1 : la fin de tout glisser remet la zone de retrait et la case glissée (bug d'essai, widget VLC).
    private var texteRetrait: TextView? = null
    private val etatGlisser = EtatGlisser<View>({ voir ->
        // Une appli tirée du tiroir ou d'un dossier n'est pas encore posée : la zone annule l'ajout.
        texteRetrait?.text = if (voir && enDragAppli != null) getString(R.string.lanceur_glisser_annuler) else getString(R.string.lanceur_glisser_retirer)
        zoneRetrait?.let { z -> z.visibility = if (voir) View.VISIBLE else View.GONE; z.alpha = 0.85f; z.scaleX = 1f; z.scaleY = 1f }
    }, { v, a -> v.alpha = a })
    private var prise: Pair<Int, Int>? = null
    private var enDrag: String? = null
    private var enDragNouveau: Accueil.Widget? = null
    /** L'appli tirée du tiroir ou d'un dossier vers l'accueil (appui long puis glisser). */
    private var enDragAppli: String? = null
    // Widgets des autres applis (1.62.0) : l'identifiant en cours d'ajout, et la taille d'une case (px).
    private var widgetEnCours = -1
    private var celluleL = 1
    private var celluleH = 1
    private var dragSorti = false
    private var grille: GrilleAccueil? = null
    private var pageur: Pageur? = null
    private var pagesUtiles = 1
    private var pageMemo = 0
    private var points: LinearLayout? = null
    private var panneau: View? = null
    private var flipEnAttente: Runnable? = null
    // Widgets Home Assistant à l'écran (mis à jour par le flux), cadre de redimensionnement ouvert.
    private val vuesHa = ArrayList<VueWidgetHa>()
    private var cadreRedim: CadreRedim? = null
    private var redimAutorise = false
    /** La grille qui défile (mode défilement) : sa position est gardée quand un redimensionnement rebâtit l'accueil. */
    private var defileAccueil: ScrollView? = null
    @Volatile private var catalogueEnCours = false
    private var dernierEssaiCatalogue = 0L

    private fun construireTelephone() {
        val profil = profil()
        val pages = Accueil.mode(this) == Accueil.MODE_PAGES
        // Le bas de l'écran (poignée du tiroir et dock) est fixe ; le reste défile ou se feuillette au-dessus.
        val hauteurBas = px(110f)
        // 1.90.1 : la largeur et la hauteur utiles (sans les barres du système), et le paysage.
        val largeurUtile = dm.widthPixels - insetG - insetD
        val hauteurUtile = dm.heightPixels
        val paysage = largeurUtile > hauteurUtile
        val colonne = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(16f), px(40f), px(16f), if (pages) 0 else px(12f)); clipChildren = false; clipToPadding = false }
        // En paysage, l'en-tête (marque, heure, météo, lecteur) occupe une colonne à gauche et la grille la droite :
        // la grille garde toute la hauteur au lieu de se serrer sous l'heure.
        val tete: LinearLayout
        val zoneGrille: LinearLayout
        if (paysage) {
            colonne.orientation = LinearLayout.HORIZONTAL
            tete = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
            zoneGrille = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
            colonne.addView(tete, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, PART_TETE_PAYSAGE))
            // Pages : la colonne prend toute la hauteur (les pages s'y partagent la place) ; défilement : sa hauteur
            // réelle, sinon elle serait coupée à celle de l'heure.
            colonne.addView(zoneGrille, LinearLayout.LayoutParams(0, if (pages) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT, 1f - PART_TETE_PAYSAGE).apply { leftMargin = px(16f) })
        } else { tete = colonne; zoneGrille = colonne }
        val defile: View = if (pages) colonne else ScrollView(this).apply {
            isVerticalScrollBarEnabled = false; isFillViewport = true; clipToPadding = false; clipChildren = false; addView(colonne) }
        defileAccueil = defile as? ScrollView
        // Glisser vers le haut n'importe où sur l'accueil, même en partant d'une icône, tire le tiroir sous le doigt
        // (en mode « grille qui défile », seulement une fois arrivé en bas de la grille).
        val zone = object : FrameLayout(this) {
            private val geste = GesteTiroir(ouvre = true, seuilFacteur = 1f) { pages || !defile.canScrollVertically(1) }
            override fun onInterceptTouchEvent(e: MotionEvent): Boolean = geste.suivre(e, this)
            override fun onTouchEvent(e: MotionEvent): Boolean = geste.suivre(e, this)
        }.apply { clipToPadding = false; Ui.garderCoupe(this) }
        // 1.72.0 : la zone coupe ce qui dépasse (rendu matériel : c'est le parent qui fixe le bord de ses enfants) ;
        // la grille, qu'elle défile ou se feuillette, s'arrête ainsi au-dessus du dock au lieu de passer dessous.
        zone.addView(defile, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        racine.addView(zone, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { bottomMargin = hauteurBas })
        // Tout le bas de l'écran ouvre le tiroir d'un glissement vers le haut, même en partant d'une icône du dock.
        val bas = object : LinearLayout(this) {
            private val geste = GesteTiroir(ouvre = true)
            override fun onInterceptTouchEvent(e: MotionEvent): Boolean = geste.suivre(e, this)
            override fun onTouchEvent(e: MotionEvent): Boolean { geste.suivre(e, this); return true }
        }.apply { orientation = LinearLayout.VERTICAL; setPadding(px(16f), 0, px(16f), px(8f)) }
        racine.addView(bas, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, hauteurBas, Gravity.BOTTOM))

        // 1.90.5 (l'auteur, accueil du téléphone, 12 octobre 23 h 17) : plus d'en-tête « VISION » collé à la caméra.
        // En paysage, le bandeau « non relié » prend la largeur de la grille (la colonne de l'heure est étroite).
        bandeauNonRelie(if (paysage) zoneGrille else tete, 15f)
        // l'auteur (13 octobre) : sans aucun widget, la zone du haut ne prend aucune place (les icônes remontent en haut de
        // l'écran) ; avec « En dessous des icônes » (Réglages › Accueil), elle passe après la grille (portrait).
        val enteteZone = if (ZoneHaut.widgets(this).isNotEmpty()) enteteTelephone(profil, paysage) else null
        val zoneDessous = !paysage && DispositionAccueil.positionZone(this) == DispositionAccueil.ZONE_DESSOUS
        if (enteteZone != null && !zoneDessous) tete.addView(enteteZone)
        // Zone posée sous la grille (mode Grille) : la grille ne garde que ses lignes occupées, la zone suit les icônes
        // (maquette validée en essai le 14 octobre) ; une ligne libre revient pendant un glisser, pour y déposer.
        grilleCourte = !pages && enteteZone != null && zoneDessous
        // 1.86.5 (retour d'essai) : le mini-lecteur quand de la musique joue (et, en option, la pochette floutée en fond).
        val fondPochette = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0.32f; visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        racine.addView(fondPochette, 1, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // 1.93.05 (retour 34) : le widget « Lecteur musique » dans la zone du haut remplace le mini-lecteur (pas de doublon).
        // (La zone peut être posée sous la grille, plus bas : on regarde la liste, pas les vues déjà posées.)
        if (ZoneHaut.LECTEUR in ZoneHaut.widgets(this)) { fondPochetteAccueil = fondPochette; tete.findViewWithTag<MiniLecteur>("mini_lecteur")?.fond = fondPochette }
        else tete.addView(MiniLecteur(this, theme, false, fondPochette).apply { tag = "mini_lecteur" },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(10f) })

        // La grille, comme Android : tout est posé où on l'a mis. Une longue grille qui défile, ou des pages.
        // 1.90.1 : la largeur de la grille est celle qui lui revient (toute la colonne en portrait, la partie droite en
        // paysage), sans les barres du système. Toujours 4 colonnes : les places enregistrées restent valables.
        val largeurGrille = if (paysage) ((largeurUtile - px(32f)) * (1f - PART_TETE_PAYSAGE)).toInt() - px(16f) else largeurUtile - px(32f)
        var lc = largeurGrille / Accueil.COLONNES
        var hc = (lc * 1.14f).toInt()
        // 1.90.5 (recette en français : « Appareil photo » sur deux lignes, 291 px pour une case de 280 px ; 315 px à
        // 130 %) : en portrait aussi, une case est au moins aussi haute que son contenu (icône et deux lignes de nom).
        if (!paysage) hc = maxOf(hc, hauteurMinCase())
        // 1.72.0 : la hauteur réelle du haut (marque, heure, date, météo : elle grandit avec la taille du texte),
        // mesurée plutôt qu'estimée ; sinon la dernière rangée d'une page passait sous le dock. En paysage, le haut
        // est à côté de la grille : il ne lui prend pas de hauteur.
        val largeurColonne = View.MeasureSpec.makeMeasureSpec(largeurUtile - px(32f), View.MeasureSpec.EXACTLY)
        val libre = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        var hautMesure = 0
        if (!paysage) for (i in 0 until colonne.childCount) { val v = colonne.getChildAt(i); v.measure(largeurColonne, libre); hautMesure += v.measuredHeight }
        // La zone posée sous la grille compte aussi dans la hauteur prise (elle partage l'écran avec les pages).
        if (enteteZone != null && zoneDessous) { enteteZone.measure(largeurColonne, libre); hautMesure += enteteZone.measuredHeight }
        // Ce qui reste sous le haut : marge du haut (elle couvre la barre d'état), grille (son retrait), points des pages, et une petite marge.
        val dispo = hauteurUtile - px(40f) - hautMesure - px(14f) - (if (pages) px(24f) else 0) - hauteurBas - px(8f)
        val lignesVisibles: Int
        if (paysage) {
            // 1.90.1 : une case n'est jamais plus basse que son contenu (icône et deux lignes de nom) : c'est ce qui
            // empilait trois icônes par case. Les cases restent à leur taille utile, la grille se centre.
            val mini = hauteurMinCase()
            hc = hc.coerceIn(mini, maxOf(mini, px(HAUTEUR_CASE_MAX)))
            lc = minOf(lc, maxOf(px(LARGEUR_CASE_MAX), (hc / 1.14f).toInt()))
            lignesVisibles = (dispo / hc).coerceAtLeast(1)
        } else lignesVisibles = (dispo / hc).coerceAtLeast(3)
        Accueil.lignesParPage = if (pages) lignesVisibles else 0
        // Les widgets d'un module coupé dans Home Assistant ne s'affichent plus ; ils restent rangés pour la réactivation.
        val actifs = Modules.actifs(this)
        val places = Accueil.synchroniser(this, profil).filter { !it.estWidget || Modules.widgetVisible(actifs, it.code) }
        celluleL = lc; celluleH = hc
        WidgetsAndroid.nettoyer(this)
        // Accueil vide (nouvel appareil) : on dit comment le remplir.
        // 1.90.1 : en paysage, le bandeau « non relié » passe avant l'aide (la hauteur ne suffit pas aux deux) ;
        // l'aide revient une fois l'appareil relié.
        if (places.isEmpty() && !(paysage && !cfg.inscrit)) zoneGrille.addView(texte(getString(R.string.lanceur_accueil_vide),
            14f, theme.encre2).apply { setPadding(px(6f), px(18f), px(6f), 0) })
        fun grilleNeuve(page: Int) = GrilleAccueil(this).apply {
            setPadding(0, px(14f), 0, 0); couleurCible = theme.or; hauteurCellule = hc; lignesMin = if (grilleCourte) 1 else lignesVisibles; hauteurFixe = pages; decalageLignes = page * lignesVisibles
            // 1.90.1 : en paysage, des cases à leur taille (jamais tassées sous leur contenu), la grille centrée.
            if (paysage) { largeurCelluleFixe = lc; hauteurMinCellule = hc }
            // En mode pages (hauteur fixe), la case n'est jamais tassée sous son contenu non plus.
            else hauteurMinCellule = hauteurMinCase()
        }
        if (pages) {
            val maxRow = places.maxOfOrNull { it.row + it.h } ?: 0
            val n = ((maxRow + lignesVisibles - 1) / lignesVisibles).coerceAtLeast(1)
            pagesUtiles = n
            val pg = Pageur(this).apply { pagesVisibles = n; clipChildren = false }
            pageur = pg
            for (p in 0..n) {   // une page de plus, vide : on y glisse une case pour l'ouvrir
                val g = grilleNeuve(p)
                places.filter { it.row / lignesVisibles == p }.forEach { g.addView(vuePlace(it)) }
                brancherGrille(g, p)
                pg.ajouterPage(g)
            }
            grille = pg.rangee.getChildAt(0) as GrilleAccueil
            zoneGrille.addView(pg, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            val pts = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, px(4f), 0, 0) }
            points = pts
            pg.surPage = { pageMemo = it; peindrePoints() }
            zoneGrille.addView(pts)
            if (enteteZone != null && zoneDessous) zoneGrille.addView(enteteZone)
            pg.post { pg.aller(pageMemo.coerceAtMost(n - 1), false); peindrePoints() }
            brancherGestes(pg)
        } else {
            pageur = null; points = null
            val g = grilleNeuve(0)
            grille = g
            places.forEach { pl -> g.addView(vuePlace(pl)) }
            brancherGrille(g, 0)
            zoneGrille.addView(g, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            if (enteteZone != null && zoneDessous) zoneGrille.addView(enteteZone, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(12f) })
            zoneGrille.addView(View(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            // La grille défile : glisser vers le haut sert à ça, le tiroir s'ouvre depuis la poignée ou le dock.
            // Mais arrivé en bas de la grille (ou si elle tient dans l'écran), le même geste ouvre le tiroir.
            brancherGestes(defile, si = { !defile.canScrollVertically(1) })
        }

        // La poignée du tiroir : un trait, comme Android ; toucher ou glisser vers le haut ouvre toutes les applications.
        val poignee = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, px(16f), 0, px(12f)); contentDescription = getString(R.string.lanceur_toutes_applis) }
        poignee.addView(View(this).apply { background = GradientDrawable().apply { cornerRadius = px(3f).toFloat(); setColor(theme.encre3) } }, LinearLayout.LayoutParams(px(56f), px(5f)))
        brancherGestes(poignee, consomme = true, toucher = { tiroirApplis() })
        bas.addView(poignee)

        // Le dock : trois applications au choix, sans autre bouton. Appui long sur une icône pour la changer.
        val dock = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(px(8f), px(2f), px(8f), px(6f)); tag = "dock" }
        val pkgs = Accueil.dock(this)
        pkgs.forEachIndexed { k, pkg ->
            val b = griser(disque(Accueil.icone(this, pkg), 56f), pkg).apply {
                isClickable = true; isFocusable = true; contentDescription = Accueil.etiquette(this@LanceurActivity, pkg); tag = "dock:$k"
                setOnClickListener { ouvrir(pkg) }
                setOnLongClickListener { menuDock(k); true }
            }
            dock.addView(b, LinearLayout.LayoutParams(px(56f), px(56f)).apply { leftMargin = px(14f); rightMargin = px(14f) })
        }
        if (pkgs.isEmpty()) dock.addView(texte(getString(R.string.lanceur_dock_vide), 12f, theme.encre3).apply { setPadding(px(8f), px(16f), px(8f), px(16f)) })
        dock.setOnLongClickListener { if (pkgs.size < Accueil.DOCK_MAX) choisirAppliDock(pkgs.size); true }
        dock.isLongClickable = true
        bas.addView(dock)
        brancherGestes(bas, consomme = true)

        // La zone « retirer », visible pendant un glisser.
        zoneRetrait = LinearLayout(this).apply {
            gravity = Gravity.CENTER; visibility = View.GONE
            background = GradientDrawable().apply { cornerRadius = px(22f).toFloat(); setColor(theme.carte); setStroke(px(2f), theme.pourpre, px(8f).toFloat(), px(6f).toFloat()) }
            addView(icone(R.drawable.ic_retirer, 20f, theme.pourpre))
            addView(texte(getString(R.string.lanceur_glisser_retirer), 14f, theme.pourpre, Polices.demiGras(this@LanceurActivity)).apply { setPadding(px(10f), 0, 0, 0); texteRetrait = this })
            setOnDragListener { v, e ->
                when (e.action) {
                    DragEvent.ACTION_DRAG_ENTERED -> { dragSorti = true; etatGlisser.sortir(); v.alpha = 1f; v.scaleX = 1.03f; v.scaleY = 1.03f; grilles().forEach { it.cacherCible() } }
                    DragEvent.ACTION_DRAG_EXITED -> { v.alpha = 0.85f; v.scaleX = 1f; v.scaleY = 1f }
                    DragEvent.ACTION_DROP -> {
                        val ajout = enDragAppli
                        (ajout ?: enDrag)?.let { cle ->
                            when (Glisser.surRetrait(cle, ajout = ajout != null)) {
                                Glisser.Retrait.ANNULER_AJOUT -> {}
                                Glisser.Retrait.DEFAIRE_DOSSIER -> Accueil.defaireSurGrille(this@LanceurActivity, cle)
                                Glisser.Retrait.RETIRER -> Accueil.retirerDeLaGrille(this@LanceurActivity, cle)
                            }
                        }
                        enDragAppli = null; enDragNouveau = null; enDrag = null; construire()
                    }
                }
                true
            }
        }
        racine.addView(zoneRetrait, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(44f), Gravity.TOP).apply { setMargins(px(20f), px(14f), px(20f), 0) })
    }

    private fun grilles(): List<GrilleAccueil> {
        val pg = pageur ?: return listOfNotNull(grille)
        return (0 until pg.pages).map { pg.rangee.getChildAt(it) as GrilleAccueil }
    }

    private fun peindrePoints() {
        val pts = points ?: return; val pg = pageur ?: return
        pts.removeAllViews()
        if (pg.pagesVisibles <= 1) return
        for (p in 0 until pg.pagesVisibles) {
            val on = p == pg.page
            pts.addView(View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (on) theme.or else theme.encre3); alpha = if (on) 255 else 120 } }, LinearLayout.LayoutParams(px(if (on) 8f else 6f), px(if (on) 8f else 6f)).apply { leftMargin = px(4f); rightMargin = px(4f) })
        }
    }

    /**
     * Les gestes sur le vide, comme Android : appui long ouvre le panneau
     * (widgets, couleurs, réglages), glisser vers le haut ouvre le tiroir.
     * Posés sur la vue qui reçoit les touchers du vide (le défilement ou les
     * pages), sans lui voler le défilement.
     */
    private fun brancherGestes(v: View, consomme: Boolean = false, glisser: Boolean = true, si: (() -> Boolean)? = null, toucher: (() -> Unit)? = null) {
        val gestes = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) { if (enDrag == null && enDragNouveau == null && enDragAppli == null && panneau == null) montrerPanneau() }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean { if (glisser && (si == null || si()) && e1 != null && e1.y - e2.y > px(40f) && vy < -350 && Math.abs(vy) > Math.abs(vx)) { tiroirApplis(); return true }; return false }
            override fun onSingleTapUp(e: MotionEvent): Boolean { toucher?.invoke(); return toucher != null }
            override fun onDown(e: MotionEvent) = true
        })
        v.setOnTouchListener { _, e -> gestes.onTouchEvent(e); consomme }
    }

    /** Le glisser dans une grille (une page, ou la grille unique) : une case existante, ou un widget neuf venu du panneau. */
    private fun brancherGrille(g: GrilleAccueil, page: Int) {
        g.setOnDragListener { _, e ->
            val source = enDrag; val nouveau = enDragNouveau; val appli = enDragAppli
            if (source == null && nouveau == null && appli == null) return@setOnDragListener false
            val pl = when {
                source != null -> Accueil.disposition(this).firstOrNull { it.cle == source } ?: return@setOnDragListener false
                nouveau != null -> Accueil.Place(Accueil.PREFIXE_WIDGET + nouveau.code, 0, 0, nouveau.w, nouveau.h)
                else -> Accueil.Place(appli!!, 0, 0, 1, 1)
            }
            when (e.action) {
                DragEvent.ACTION_DRAG_STARTED -> { if (grilleCourte) { g.lignesMin = g.lignes() + pl.h; g.requestLayout() }; true }
                DragEvent.ACTION_DRAG_LOCATION -> {
                    val (c, r) = g.cellule(e.x, e.y)
                    if (source != null && prise == null && pl.couvre(c, r)) prise = Glisser.prise(c, r, pl.col, pl.row, pl.w, pl.h)
                    val (col, rang) = Glisser.cible(c, r, pl.w, pl.h, Accueil.COLONNES, if (source != null) prise else null)
                    val sous = Accueil.placeA(Accueil.disposition(this).filter { it.cle != source }, c, r)
                    // Une appli venue du tiroir ou d'un dossier : la case visée, ou la libre la plus proche (jamais par-dessus).
                    if (appli != null) Accueil.caseAjout(Accueil.disposition(this), appli, col, rang).let { (ac, ar) -> g.montrerCible(ac, ar, 1, 1) }
                    else if (source != null && sous != null && !sous.estWidget && pl.w == 1 && pl.h == 1 && !pl.estDossier && surLeCentre(g, sous, e.x, e.y)) g.montrerCible(sous.col, sous.row, 1, 1) else g.montrerCible(col, rang, pl.w, pl.h)
                    // A-t-elle bougé : la place visée n'est plus la sienne (un grand widget peut bouger d'une case).
                    if (source == null || Glisser.aBouge(pl.col, pl.row, col to rang)) { dragSorti = true; etatGlisser.sortir() }
                    tournerPage(g, page, e.x)
                    true
                }
                DragEvent.ACTION_DRAG_EXITED -> { g.cacherCible(); annulerFlip(); true }
                DragEvent.ACTION_DROP -> {
                    g.cacherCible(); annulerFlip()
                    val (c, r) = g.cellule(e.x, e.y)
                    val (col, rang) = Glisser.cible(c, r, pl.w, pl.h, Accueil.COLONNES, if (source != null) prise else null)
                    // Lâchée à sa place : rien ne bouge ; la fin du glisser ouvre le menu si l'on n'a jamais bougé.
                    if (source != null && !Glisser.aBouge(pl.col, pl.row, col to rang)) return@setOnDragListener false
                    if (appli != null) {
                        val (ac, ar) = Accueil.caseAjout(Accueil.disposition(this), appli, col, rang)
                        Accueil.poserAppli(this, appli, ac, ar)
                        racine.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.KEYBOARD_TAP)
                        enDragAppli = null
                        construire(); return@setOnDragListener true
                    }
                    val sous =Accueil.placeA(Accueil.disposition(this).filter { it.cle != source }, c, r)
                    if (nouveau != null) Accueil.poserSurGrille(this, pl.cle, col, rang, pl.w, pl.h)
                    else if (sous != null && !sous.estWidget && pl.w == 1 && pl.h == 1 && !pl.estDossier && surLeCentre(g, sous, e.x, e.y)) Accueil.fusionnerSurGrille(this, sous.cle, source!!)
                    else Accueil.deplacerSurGrille(this, source!!, col, rang)
                    // La case d'origine est reconstruite : elle ne recevra pas la fin du glisser, on libère ici.
                    enDragNouveau = null; enDrag = null
                    construire(); true
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    g.cacherCible(); annulerFlip()
                    // Un widget soulevé puis lâché sans bouger : le cadre de redimensionnement (une seule fois, même avec
                    // plusieurs pages : enDrag est vidé ici).
                    val cle = enDrag
                    if (cle != null && cle.startsWith(Accueil.PREFIXE_WIDGET) && !dragSorti && !e.result) {
                        enDrag = null; prise = null; etatGlisser.fin(depotReussi = true)
                        main.post { ouvrirRedim(cle) }
                    }
                    if (enDragNouveau != null) { enDragNouveau = null; zoneRetrait?.visibility = View.GONE }
                    if (enDragAppli != null) { enDragAppli = null; zoneRetrait?.visibility = View.GONE }
                    if (grilleCourte) { g.lignesMin = 1; g.requestLayout() }
                    pageur?.let { pg -> pg.pagesVisibles = pagesUtiles; if (pg.page >= pagesUtiles) pg.aller(pagesUtiles - 1); peindrePoints() }
                    true
                }
                else -> true
            }
        }
    }

    /** Pendant un glisser, rester au bord d'une page la tourne, comme Android. */
    private fun tournerPage(g: GrilleAccueil, page: Int, x: Float) {
        val pg = pageur ?: return
        val bord = px(36f)
        val cible = when { x < bord && page > 0 -> page - 1; x > g.width - bord && page < pg.pages - 1 -> page + 1; else -> { annulerFlip(); return } }
        if (flipEnAttente != null) return
        val r = Runnable { flipEnAttente = null; pg.pagesVisibles = pg.pages; pg.aller(cible); peindrePoints() }
        flipEnAttente = r
        main.postDelayed(r, 550)
    }

    private fun annulerFlip() { flipEnAttente?.let { main.removeCallbacks(it) }; flipEnAttente = null }

    /** Appui long sur une icône du dock : la remplacer, la retirer, en ajouter une. */
    private fun menuDock(k: Int) {
        val pkgs = Accueil.dock(this).toMutableList()
        val choix = ArrayList<String>().apply { add(getString(R.string.lanceur_remplacer)); add(getString(R.string.lanceur_retirer_dock)); if (pkgs.size < Accueil.DOCK_MAX) add(getString(R.string.lanceur_ajouter_application)) }
        Ui.dialogue(this).setTitle(Accueil.etiquette(this, pkgs[k])).setItems(choix.toTypedArray()) { _, i ->
            when (i) {
                0 -> choisirAppliDock(k)
                1 -> { pkgs.removeAt(k); Accueil.ecrireDock(this, pkgs); construire() }
                2 -> choisirAppliDock(pkgs.size)
            }
        }.show()
    }

    /** Le choix d'une application pour une place du dock (k = taille actuelle pour en ajouter une). */
    private fun choisirAppliDock(k: Int) {
        val pm = packageManager
        val toutes = Accueil.applicationsInstallees(this)
        val noms = toutes.map { it.loadLabel(pm).toString() }.toTypedArray()
        Ui.dialogue(this).setTitle(getString(R.string.lanceur_application_dock)).setItems(noms) { _, i ->
            val pkg = toutes[i].activityInfo.packageName
            val pkgs = Accueil.dock(this).toMutableList()
            pkgs.remove(pkg)
            if (k < pkgs.size) pkgs[k] = pkg else pkgs.add(pkg)
            Accueil.ecrireDock(this, pkgs); construire()
        }.show()
    }

    private fun surLeCentre(g: GrilleAccueil, pl: Accueil.Place, x: Float, y: Float): Boolean {
        val lc = g.largeurCellule; val cx = g.retrait + (pl.col + 0.5f) * lc; val cy = g.paddingTop + pl.row * g.hauteurCellule + lc * 0.45f
        return Math.hypot((x - cx).toDouble(), (y - cy).toDouble()) < lc * 0.33
    }

    /**
     * Le panneau de l'appui long, comme sur Android : en haut les raccourcis
     * (couleurs, réglages, défilement ou pages, applications), dessous les
     * widgets disponibles. Appui long sur un widget puis glisser : il se
     * pose où on le lâche ; un simple toucher le pose à la première place
     * libre, ou le retire s'il y est déjà.
     */
    private fun montrerPanneau() {
        cacherPanneau()
        // Le fond s'efface franchement derrière la feuille (on ne voit plus le haut du tiroir ni l'accueil au travers).
        val voile = FrameLayout(this).apply { setBackgroundColor(0x99000000.toInt()); isClickable = true; setOnClickListener { cacherPanneau() } }
        // Le contenu est découpé à la forme de la feuille : en défilant, rien ne déborde au-dessus de son bord.
        val feuille = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = fondCarte(30f); setPadding(px(18f), px(10f), px(18f), px(18f)); isClickable = true; clipToOutline = true }
        feuille.addView(View(this).apply { background = GradientDrawable().apply { cornerRadius = px(3f).toFloat(); setColor(theme.encre3) } }, LinearLayout.LayoutParams(px(40f), px(4f)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = px(12f) })

        val pages = Accueil.mode(this) == Accueil.MODE_PAGES
        val raccourcis = LinearLayout(this).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL }
        fun raccourci(res: Int, t: String, action: () -> Unit) {
            // Icônes sur une même ligne : chaque libellé réserve deux lignes, et la rangée s'aligne en haut.
            val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP; isClickable = true; setPadding(px(4f), px(4f), px(4f), px(4f)) }
            val rond = FrameLayout(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Palette.melanger(theme.carte, theme.encre, 0.08f)) } }
            rond.addView(icone(res, 22f, theme.or), FrameLayout.LayoutParams(px(22f), px(22f), Gravity.CENTER))
            c.addView(rond, LinearLayout.LayoutParams(px(48f), px(48f)))
            c.addView(texte(t, 11f, theme.encre2, Polices.moyen(this)).apply { gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP; setPadding(0, px(5f), 0, 0); minLines = 2 })
            c.setOnClickListener { cacherPanneau(); action() }
            raccourcis.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        raccourci(R.drawable.ic_couleurs, getString(R.string.manifeste_couleurs)) { startActivity(Intent(this, CouleursActivity::class.java)) }
        // 15-10 (retour d'essai) : hors télé, « Réglages » mène aux réglages de l'appli (« Réglages de l'appareil » a été réparti).
        raccourci(R.drawable.ic_reglages, getString(R.string.lanceur_reglages)) {
            if (tele) startActivity(Intent(this, ReglagesTvActivity::class.java)) else SetupActivity.ouvrir(this, Espaces.REGLAGES)
        }
        // l'auteur (13 octobre) : trois dispositions (grille, pages, liste alphabétique) ; 15-10 : le menu Disposition entier
        // (disposition, index et favoris de la liste, zone du haut et sa place, applications visibles).
        raccourci(R.drawable.ic_grille, getString(R.string.reglagestv_disposition)) { ReglagesPartages.menuDisposition(this) { construire() } }
        raccourci(R.drawable.ic_widgets, getString(R.string.lanceur_applications)) { tiroirApplis() }
        if (pages && pageur != null) raccourci(R.drawable.ic_retirer, getString(R.string.lanceur_supprimer_page)) { supprimerPage() }
        feuille.addView(raccourcis)
        feuille.addView(View(this).apply { setBackgroundColor(theme.carteBord) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(1f)).apply { topMargin = px(12f); bottomMargin = px(10f) })

        feuille.addView(etiquetteSection(getString(R.string.lanceur_widgets), 11f))
        feuille.addView(texte(getString(R.string.lanceur_widgets_aide), 12f, theme.encre3).apply { setPadding(0, px(4f), 0, px(8f)) })
        val actifs = Modules.actifs(this)
        // Module « widgets » coupé : plus d'ajout de widget d'une autre appli.
        if (Modules.actifDans(actifs, Modules.WIDGETS)) feuille.addView(texte(getString(R.string.lanceur_widget_autre_appli), 14f, theme.encre, Polices.demiGras(this)).apply {
            background = fondCarte(18f, couleur = Palette.melanger(theme.carte, theme.or, 0.12f)); setPadding(px(14f), px(12f), px(14f), px(12f)); isClickable = true
            setOnClickListener { cacherPanneau(); ajouterWidgetAndroid() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = px(8f) })
        // Module « entites » coupé : plus d'ajout de widget Home Assistant (ceux posés sont masqués, pas supprimés).
        if (Modules.actifDans(actifs, Modules.ENTITES)) feuille.addView(texte(getString(R.string.lanceur_widget_ha_plus), 14f, theme.encre, Polices.demiGras(this)).apply {
            background = fondCarte(18f, couleur = Palette.melanger(theme.carte, theme.or, 0.12f)); setPadding(px(14f), px(12f), px(14f), px(12f)); isClickable = true
            setOnClickListener { cacherPanneau(); ajouterWidgetHa() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = px(8f) })
        val profil = profil()
        val poses = Accueil.disposition(this).map { it.cle }.toSet()
        // 15-10 (retour d'essai) : seulement les widgets dont le lieu a les données (liste envoyée par Home Assistant) ; ceux déjà posés restent.
        val donneesDuLieu = Accueil.disponibles(this)
        val dispo = Accueil.WIDGETS.filter { (it.publics.isEmpty() || profil in it.publics) && Modules.widgetVisible(actifs, it.code) && Accueil.disponible(it.code, donneesDuLieu) }
        val defilant = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val liste = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        defilant.addView(liste)
        var rang: LinearLayout? = null
        dispo.forEachIndexed { k, w ->
            if (k % 2 == 0) { rang = LinearLayout(this).apply { clipChildren = false }; liste.addView(rang) }
            val cle = Accueil.PREFIXE_WIDGET + w.code
            val pose = cle in poses
            val tuile = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = fondCarte(18f, couleur = Palette.melanger(theme.carte, theme.encre, 0.05f)); setPadding(px(12f), px(10f), px(12f), px(10f)); isClickable = true; alpha = if (pose) 0.55f else 1f }
            // Un petit aperçu : des cellules à la taille du widget.
            val apercu = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            apercu.addView(View(this).apply { background = GradientDrawable().apply { cornerRadius = px(6f).toFloat(); setColor(theme.or); alpha = 200 } }, LinearLayout.LayoutParams(px(10f * w.w), px(8f * w.h)))
            apercu.addView(texte("${w.w} × ${w.h}", 11f, theme.encre3, Polices.moyen(this)).apply { setPadding(px(8f), 0, 0, 0) })
            tuile.addView(apercu)
            tuile.addView(texte(w.nom, 13f, theme.encre, Polices.demiGras(this)).apply { setPadding(0, px(8f), 0, 0) })
            if (pose) tuile.addView(texte(getString(R.string.lanceur_pose_toucher), 11f, theme.encre3).apply { setPadding(0, px(4f), 0, 0) })
            tuile.setOnClickListener {
                if (pose) Accueil.retirerDeLaGrille(this, cle) else Accueil.ajouterALaGrille(this, cle, w.w, w.h)
                cacherPanneau(); construire()
            }
            tuile.setOnLongClickListener { v -> souleverNouveau(v, w); true }
            rang!!.addView(tuile, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(px(4f), px(4f), px(4f), px(4f)) })
        }
        if (dispo.size % 2 == 1) rang!!.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        feuille.addView(defilant, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0).apply { height = (dm.heightPixels * 0.38f).toInt() })

        voile.addView(feuille, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        racine.addView(voile, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        panneau = voile
        feuille.translationY = px(300f).toFloat(); feuille.alpha = 0f
        feuille.animate().translationY(0f).alpha(1f).setDuration(180).start()
    }

    private fun cacherPanneau() { panneau?.let { racine.removeView(it) }; panneau = null }

    /** Comme Android : la page courante disparaît avec tout ce qu'elle porte ; les applis retournent au tiroir. */
    private fun supprimerPage() {
        val pg = pageur ?: return
        val page = pg.page
        val lignes = Accueil.lignesParPage
        val dedans = Accueil.disposition(this).count { it.row / lignes == page }
        val texte = if (dedans == 0) getString(R.string.lanceur_page_vide) else resources.getQuantityString(R.plurals.lanceur_retirer_page, dedans, page + 1, dedans)
        Ui.dialogue(this).setTitle(getString(R.string.lanceur_supprimer_page)).setMessage(texte)
            .setPositiveButton(getString(R.string.reglagestv_supprimer)) { _, _ -> Accueil.supprimerPage(this, page, lignes); pageMemo = (page - 1).coerceAtLeast(0); construire() }
            .setNegativeButton(getString(R.string.commun_lot2_annuler), null).show()
    }

    /** Un widget neuf qu'on glisse depuis le panneau : le panneau s'efface, la grille montre où il tombera. */
    private fun souleverNouveau(v: View, w: Accueil.Widget): Boolean {
        enDrag = null; enDragNouveau = w; dragSorti = true
        val ok = if (Build.VERSION.SDK_INT >= 24) v.startDragAndDrop(ClipData.newPlainText("widget", w.code), View.DragShadowBuilder(v), w, 0) else @Suppress("DEPRECATION") v.startDrag(ClipData.newPlainText("widget", w.code), View.DragShadowBuilder(v), w, 0)
        if (ok) {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            cacherPanneau()
            pageur?.let { pg -> pg.pagesVisibles = pg.pages }
        } else enDragNouveau = null
        return ok
    }

    /**
     * Appui long sur une appli du tiroir ou d'un dossier, comme un lanceur Android : le menu s'ouvre ; si le doigt
     * part au-delà du seuil, le menu s'efface, l'icône suit le doigt vers l'accueil (`apres` : refermer le dossier).
     */
    private fun appuiLongPuisGlisser(cell: View, ombre: View, pkg: String, menu: () -> Dialog?, depuisDossier: Boolean = false, apres: () -> Unit = {}) {
        val suivi = AppuiPuisGlisser(ViewConfiguration.get(this).scaledTouchSlop * 1.5f)
        var ouvert: Dialog? = null
        var x = 0f; var y = 0f
        cell.setOnTouchListener { v, e ->
            x = e.rawX; y = e.rawY
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> suivi.debut(e.rawX, e.rawY)
                MotionEvent.ACTION_MOVE -> if (suivi.bouge(e.rawX, e.rawY)) {
                    try { ouvert?.dismiss() } catch (_: Exception) {}
                    ouvert = null
                    if (glisserAppli(v, ombre, pkg, depuisDossier)) apres()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> suivi.fin()
            }
            false
        }
        cell.setOnLongClickListener { v ->
            suivi.appuiLong(x, y)
            // La suite du geste reste à l'icône : ni le défilement ni la feuille du tiroir ne la prennent.
            v.parent?.requestDisallowInterceptTouchEvent(true)
            ouvert = menu(); true
        }
    }

    /**
     * L'appli soulevée hors du tiroir ou d'un dossier : le tiroir se range, l'accueil montre où elle tombera.
     * Un dossier ouvert est une fenêtre à part : sans DRAG_FLAG_GLOBAL, Android ne livre le glisser qu'à la
     * fenêtre de départ, et l'accueil (la fenêtre de l'activité) ne le verrait jamais.
     */
    private fun glisserAppli(v: View, ombre: View, pkg: String, autreFenetre: Boolean = false): Boolean {
        if (tele || zoneRetrait == null) return false
        enDrag = null; enDragNouveau = null; enDragAppli = pkg; dragSorti = true; prise = null
        val builder = View.DragShadowBuilder(ombre)
        val drapeaux = if (autreFenetre && Build.VERSION.SDK_INT >= 24) View.DRAG_FLAG_GLOBAL else 0
        val ok = if (Build.VERSION.SDK_INT >= 24) v.startDragAndDrop(ClipData.newPlainText("appli", pkg), builder, pkg, drapeaux) else @Suppress("DEPRECATION") v.startDrag(ClipData.newPlainText("appli", pkg), builder, pkg, 0)
        if (!ok) { enDragAppli = null; return false }
        etatGlisser.debut(pkg, v, null); etatGlisser.sortir()
        animerTiroir(0f)
        pageur?.let { pg -> pg.pagesVisibles = pg.pages; peindrePoints() }
        return true
    }

    /** Une case de la grille : appli, dossier ou widget. */
    private fun vuePlace(pl: Accueil.Place): View {
        val v: View = when {
            pl.estWidget -> vueWidget(pl)
            pl.estDossier -> { val d = Accueil.dossiers(this).firstOrNull { it.cle == pl.cle }; if (d != null) caseTelephone(Accueil.Case(pl.cle, dossier = d)) else View(this) }
            else -> caseTelephone(Accueil.Case(pl.cle, pkg = pl.cle))
        }
        v.tag = pl
        return v
    }

    private fun soulever(v: View, ombre: View, cle: String): Boolean {
        enDrag = cle; dragSorti = false
        val builder = View.DragShadowBuilder(ombre)
        val ok = if (Build.VERSION.SDK_INT >= 24) v.startDragAndDrop(ClipData.newPlainText("case", cle), builder, cle, 0) else @Suppress("DEPRECATION") v.startDrag(ClipData.newPlainText("case", cle), builder, cle, 0)
        if (ok) { prise = null; etatGlisser.debut(cle, v, null); pageur?.let { pg -> pg.pagesVisibles = pg.pages } }
        // Lot accueil (point 3) : un glisser refusé ne doit pas laisser l'accueil « en glisser » (il ne se rebâtirait plus).
        else enDrag = null
        v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        return ok
    }

    private fun finDrag(v: View, e: DragEvent, menu: () -> Unit) {
        val ouvrirMenu = !dragSorti && !e.result
        etatGlisser.fin(depotReussi = e.result)
        v.alpha = 1f
        enDrag = null; prise = null
        if (ouvrirMenu) main.post { menu() }
    }

    /** 1.90.1 : la hauteur que demande une case d'appli (caseTelephone), police du système comprise. */
    private fun hauteurMinCase(): Int {
        val p = android.text.TextPaint().apply { textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 11.5f, dm); typeface = Polices.moyen(this@LanceurActivity) }
        val ligne = p.fontMetricsInt.let { it.descent - it.ascent }
        return px(6f) + px(56f) + px(5f) + 2 * ligne + px(2f) + px(4f)
    }

    private fun caseTelephone(c: Accueil.Case): View {
        val taille = 56f
        val contenu = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(px(2f), px(6f), px(2f), px(2f)); clipChildren = false }
        val d = if (c.dossier != null) disqueDossier(c.dossier, taille) else griser(disque(Accueil.icone(this, c.pkg!!), taille), c.pkg)
        contenu.addView(d, LinearLayout.LayoutParams(px(taille), px(taille)))
        val nom = if (c.dossier != null) c.dossier.nom else Accueil.etiquette(this, c.pkg!!)
        contenu.addView(texte(nom, 11.5f, theme.encre2, Polices.moyen(this)).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER; setPadding(px(2f), px(5f), px(2f), 0); Ui.motsEntiers(this, 11.5f, 6.5f) })
        contenu.contentDescription = nom
        contenu.isClickable = true; contenu.isFocusable = true
        contenu.setOnClickListener { if (c.dossier != null) ouvrirDossier(c.dossier, emptyList()) else ouvrir(c.pkg!!) }
        contenu.setOnLongClickListener { v -> soulever(v, d, c.cle) }
        contenu.setOnDragListener { v, e -> if (e.action == DragEvent.ACTION_DRAG_ENDED && enDrag == c.cle) finDrag(v, e) { menuTelephone(c, null) }; false }
        return contenu
    }

    /** Le menu d'appui long du téléphone : une feuille en bas de l'écran. */
    private fun menuTelephone(c: Accueil.Case, dossierParent: Accueil.Dossier?): Dialog {
        val dlg = Dialog(this)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val feuille = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = fondCarte(30f); setPadding(px(18f), px(20f), px(18f), px(14f)) }
        val tete = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(px(6f), 0, px(6f), px(12f)) }
        val ic = FrameLayout(this)
        val dsq = if (c.dossier != null) disqueDossier(c.dossier, 48f) else disque(Accueil.icone(this, c.pkg!!), 48f)
        ic.addView(dsq, FrameLayout.LayoutParams(px(48f), px(48f), Gravity.CENTER))
        ic.addView(View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0); setStroke(px(1.5f), theme.or) } }, FrameLayout.LayoutParams(px(56f), px(56f), Gravity.CENTER))
        tete.addView(ic, LinearLayout.LayoutParams(px(56f), px(56f)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(14f), 0, 0, 0) }
        val nom = if (c.dossier != null) c.dossier.nom else Accueil.etiquette(this, c.pkg!!)
        col.addView(texte(nom, 18f, theme.encre, Polices.gras(this)))
        val sous = if (c.pkg != null) { val raison = Etat.doitBloquer(this, c.pkg); if (raison == null) getString(R.string.lanceur_autorisee) else getString(R.string.lanceur_fermee_raison, raison) } else resources.getQuantityString(R.plurals.lanceur_n_applis, c.dossier!!.pkgs.size, c.dossier.pkgs.size)
        col.addView(texte(sous, 13f, theme.encre2))
        tete.addView(col)
        feuille.addView(tete)
        feuille.addView(View(this).apply { setBackgroundColor(theme.carteBord) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(1f)))
        fun ligne(res: Int, t: String, danger: Boolean = false, action: () -> Unit) {
            val l = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = px(52f); setPadding(px(6f), 0, px(6f), 0); isClickable = true; isFocusable = true }
            val couleur = if (danger) theme.pourpre else theme.encre
            l.addView(icone(res, 24f, couleur))
            l.addView(texte(t, 17f, couleur, Polices.moyen(this)).apply { setPadding(px(16f), 0, 0, 0) })
            l.setOnClickListener { dlg.dismiss(); action() }
            feuille.addView(l)
        }
        if (dossierParent != null) ligne(R.drawable.ic_sortir, getString(R.string.lanceur_sortir_dossier)) { Accueil.sortirSurGrille(this, dossierParent.cle, c.pkg!!); dossierOuvert?.dismiss(); construire() }
        if (c.pkg != null) {
            if (dossierParent == null) ligne(R.drawable.ic_retirer, getString(R.string.lanceur_retirer_accueil)) { Accueil.retirerDeLaGrille(this, c.pkg); construire() }
            if (ActionsAppli.concerne(this, c.pkg)) {
                ligne(R.drawable.ic_retirer, getString(R.string.lanceur_forcer_arret)) { ActionsAppli.forcerArret(this, c.pkg, nom) }
                ligne(R.drawable.ic_systeme, getString(R.string.lanceur_vider_cache)) { ActionsAppli.viderCache(this, c.pkg, nom) }
                if (ActionsAppli.proprietaire(this)) ligne(R.drawable.ic_alerte, getString(R.string.lanceur_effacer_donnees), danger = true) { ActionsAppli.effacerDonnees(this, c.pkg, nom) }
            }
            if (c.pkg != Party.CLE) {
                ligne(R.drawable.ic_infos, getString(R.string.lanceur_infos_appli)) { ActionsAppli.infosProtegees(this, c.pkg) }
                ligne(R.drawable.ic_desinstaller, getString(R.string.lanceur_desinstaller), danger = true) { Paquets.desinstaller(this, c.pkg) }
            }
        } else {
            ligne(R.drawable.ic_crayon, getString(R.string.tableauediteur_renommer)) { renommer(c.dossier!!) }
            ligne(R.drawable.ic_retirer, getString(R.string.lanceur_defaire_dossier)) { Accueil.defaireSurGrille(this, c.cle); construire() }
        }
        feuille.addView(texte(getString(R.string.lanceur_pour_deplacer), 13f, theme.encre2).apply { setPadding(px(6f), px(10f), px(6f), px(4f)) })
        dlg.setContentView(feuille)
        dlg.window?.let { w ->
            w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            w.setGravity(Gravity.BOTTOM)
            w.attributes = w.attributes.apply { y = px(12f) }
            w.setDimAmount(0.45f)
        }
        dlg.show()
        return dlg
    }

    /** Le menu d'un widget : le retirer, ou en ajouter. */
    private fun menuWidget(pl: Accueil.Place) {
        if (WidgetsAndroid.estAndroid(pl.code)) return menuWidgetAndroid(pl)
        if (WidgetsHa.estHa(pl.code)) return menuWidgetHa(pl)
        val w = Accueil.widget(pl.code) ?: return
        Ui.dialogue(this).setTitle(w.nom).setItems(arrayOf(getString(R.string.lanceur_redimensionner), getString(R.string.lanceur_retirer_accueil), getString(R.string.lanceur_ajouter_widget))) { _, k ->
            when (k) { 0 -> ouvrirRedim(pl.cle); 1 -> { Accueil.retirerDeLaGrille(this, pl.cle); construire() }; else -> montrerPanneau() }
        }.show()
    }

    // ------------------------------------------------------------------ widgets des autres applis (1.62.0)

    /** Sans la fonction « accueil » du rôle (ni pause de gestion en cours), poser un widget demande le code. */
    private fun autoriserAccueil(ok: () -> Unit) {
        val fonctions = Profil.fonctions(this)
        val libre = cfg.modeParent || "accueil" in fonctions || (fonctions.isEmpty() && profil() == "parent")
        if (libre) { ok(); return }
        if (!cfg.aUnCode) {
            Ui.dialogue(this).setTitle(getString(R.string.biometrie_code_gestion)).setMessage(getString(R.string.lanceur_widget_pas_de_code, Vocabulaire.decideur(this)))
                .setPositiveButton(getString(R.string.commun_lot2_ok), null).show()
            return
        }
        val champ = Ui.champ(this, getString(R.string.biometrie_code_gestion), "", chiffres = true)
        val cadre = FrameLayout(this).apply { setPadding(px(20f), px(8f), px(20f), 0); addView(champ) }
        Ui.dialogue(this).setTitle(getString(R.string.biometrie_code_gestion)).setMessage(getString(R.string.lanceur_pour_poser_widget)).setView(cadre)
            .setPositiveButton(getString(R.string.commun_lot2_valider)) { _, _ ->
                if (cfg.verifierCode(champ.text.toString())) ok()
                else Ui.toast(this, getString(R.string.lanceur_code_incorrect), court = true, genre = OeilModele.Genre.ERREUR)
            }.setNegativeButton(getString(R.string.commun_lot2_annuler), null).show()
    }

    /** Le sélecteur (SelecteurWidgets) : groupés par appli, recherche, aperçu, taille en cases. */
    private fun ajouterWidgetAndroid() = autoriserAccueil {
        SelecteurWidgets.ouvrir(this, theme, celluleL / dm.density, celluleH / dm.density) { lierWidgetAndroid(it) }
    }

    private fun lierWidgetAndroid(info: android.appwidget.AppWidgetProviderInfo) {
        val hote = WidgetsAndroid.hote(this)
        val id = try { hote.allocateAppWidgetId() } catch (_: Exception) { return }
        widgetEnCours = id
        val lie = try { WidgetsAndroid.gestionnaire(this).bindAppWidgetIdIfAllowed(id, info.provider) } catch (_: Exception) { false }
        if (lie) { reglerWidgetAndroid(id); return }
        // Android demande une fois à l'utilisateur d'autoriser Vision à créer des widgets.
        val i = Intent(android.appwidget.AppWidgetManager.ACTION_APPWIDGET_BIND)
            .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
        try { @Suppress("DEPRECATION") startActivityForResult(i, WidgetsAndroid.REQ_LIER) }
        catch (_: Exception) { hote.deleteAppWidgetId(id); widgetEnCours = -1 }
    }

    /** Certains widgets se règlent avant d'être posés (une ville, un compte…) : leur écran s'ouvre. */
    private fun reglerWidgetAndroid(id: Int) {
        val info = WidgetsAndroid.gestionnaire(this).getAppWidgetInfo(id) ?: return poserWidgetAndroid(id)
        if (info.configure == null) return poserWidgetAndroid(id)
        widgetEnCours = id
        try {
            if (Build.VERSION.SDK_INT >= 21) WidgetsAndroid.hote(this).startAppWidgetConfigureActivityForResult(this, id, 0, WidgetsAndroid.REQ_REGLER, null)
            else @Suppress("DEPRECATION") startActivityForResult(Intent(android.appwidget.AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).setComponent(info.configure)
                .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, id), WidgetsAndroid.REQ_REGLER)
        } catch (_: Exception) { poserWidgetAndroid(id) }
    }

    private fun poserWidgetAndroid(id: Int) {
        widgetEnCours = -1
        val info = WidgetsAndroid.gestionnaire(this).getAppWidgetInfo(id)
        val (w, h) = if (info != null) WidgetsAndroid.cellulesDepart(info, celluleL / dm.density, celluleH / dm.density) else 2 to 1
        Accueil.ajouterALaGrille(this, WidgetsAndroid.cle(id), w, h)
        construire()
    }

    private fun vueWidgetAndroid(pl: Accueil.Place): View {
        val cadre = FrameLayout(this).apply { setPadding(px(4f), px(4f), px(4f), px(4f)); clipChildren = false }
        val id = WidgetsAndroid.id(pl.code)
        val info = id?.let { WidgetsAndroid.gestionnaire(this).getAppWidgetInfo(it) }
        val c: View = if (id != null && info != null) {
            (WidgetsAndroid.hote(this).createView(applicationContext, id, info) as android.appwidget.AppWidgetHostView).also { v ->
                (v as? WidgetsAndroid.VueWidget)?.appuiLong = { soulever(cadre, v, pl.cle) }
                WidgetsAndroid.annoncerTaille(this, id, v, (celluleL * pl.w / dm.density).toInt(), (celluleH * pl.h / dm.density).toInt())
            }
        } else carte().also { it.addView(texte(getString(R.string.lanceur_widget_indisponible), 12f, theme.encre3)) }
        cadre.addView(c, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        cadre.isClickable = true
        cadre.setOnLongClickListener { v -> soulever(v, c, pl.cle) }
        // Appui long sans bouger : le cadre de redimensionnement (son bouton « Options » ouvre le menu).
        cadre.setOnDragListener { v, e -> if (e.action == DragEvent.ACTION_DRAG_ENDED && enDrag == pl.cle) finDrag(v, e) { ouvrirRedim(pl.cle) }; false }
        return cadre
    }

    private fun menuWidgetAndroid(pl: Accueil.Place) {
        val info = WidgetsAndroid.id(pl.code)?.let { WidgetsAndroid.gestionnaire(this).getAppWidgetInfo(it) }
        val titre = info?.let { WidgetsAndroid.libelle(this, it) } ?: getString(R.string.lanceur_widget)
        Ui.dialogue(this).setTitle(titre).setItems(arrayOf(getString(R.string.lanceur_redimensionner), getString(R.string.lanceur_retirer_accueil))) { _, k ->
            if (k == 0) ouvrirRedim(pl.cle)
            else autoriserAccueil { Accueil.retirerDeLaGrille(this, pl.cle); construire() }
        }.show()
    }

    // ------------------------------------------------------------------ widgets Home Assistant (docs/widgets-ha.md)

    private fun ouvrirEditeurHa(id: String?) = EditeurWidgetHaActivity.ouvrir(this, id, if (tele) 0 else celluleL, if (tele) 0 else celluleH)

    private fun ajouterWidgetHa() = autoriserWidget { ouvrirEditeurHa(null) }

    private fun vueWidgetHa(pl: Accueil.Place): View {
        val cadre = FrameLayout(this).apply { setPadding(px(4f), px(4f), px(4f), px(4f)); clipChildren = false }
        val r = WidgetsHaStock.reglage(this, WidgetsHa.id(pl.code))
        val c: View = if (r != null) VueWidgetHa(this, theme, r.copy(w = pl.w, h = pl.h), lectureSeule = tele, agir = { e, s -> agirHa(e, s) }, details = { t, m -> detailsHa(t, m) }, agirAvec = { e, s, d -> agirHa(e, s, d) }).also { vuesHa.add(it) }
            else carte().also { it.addView(texte(getString(R.string.lanceur_widget_ha_sans_reglage), 12f, theme.encre3)) }
        cadre.addView(c, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        cadre.isClickable = true
        cadre.setOnLongClickListener { v -> soulever(v, c, pl.cle) }
        c.setOnLongClickListener { soulever(cadre, c, pl.cle) }
        cadre.setOnDragListener { v, e -> if (e.action == DragEvent.ACTION_DRAG_ENDED && enDrag == pl.cle) finDrag(v, e) { ouvrirRedim(pl.cle) }; false }
        return cadre
    }

    private fun titreHa(r: WidgetsHa.Reglage?): String = r?.let { it.libelle.ifEmpty { WidgetsHaStock.entite(this, it.entites.first())?.nom ?: it.entites.first() } } ?: getString(R.string.editeurwidget_nouveau)

    private fun menuWidgetHa(pl: Accueil.Place) {
        val r = WidgetsHaStock.reglage(this, WidgetsHa.id(pl.code))
        val choix = ArrayList<Pair<String, () -> Unit>>()
        if (r != null) choix.add(getString(R.string.lanceur_modifier_points) to { autoriserAccueil { ouvrirEditeurHa(r.id) } })
        if (!tele) choix.add(getString(R.string.lanceur_redimensionner) to { ouvrirRedim(pl.cle) })
        choix.add(getString(R.string.lanceur_retirer_accueil) to { autoriserAccueil { Accueil.retirerDeLaGrille(this, pl.cle); construire() } })
        Ui.dialogue(this).setTitle(titreHa(r)).setItems(choix.map { it.first }.toTypedArray()) { _, k -> choix[k].second() }.show()
    }

    /** Interrupteur, bouton : l'action part (seulement si l'entité la permet) ; un refus s'explique clairement. */
    // 1.77.0 (règle du projet) : l'interrupteur bascule à l'écran tout de suite (état attendu, estompé le temps de la
    // réponse) ; Home Assistant confirme par le flux, ou l'état d'avant revient et le refus s'explique.
    private fun agirHa(e: WidgetsHa.Entite, service: String, donnees: org.json.JSONObject = org.json.JSONObject(), confirme: Boolean = false) {
        // 1.88.0 : une entité réglée « avec confirmation » dans Home Assistant demande d'abord un oui (le serveur le vérifie).
        if (e.confirmer && !confirme) { confirmerHa(e, null) { agirHa(e, service, donnees, true) }; return }
        racine.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        val avant = FluxWidgets.etats[e.entityId]
        val base = avant ?: WidgetsHa.EtatEntite(e.etat, e.attributs, "")
        Geste.etatAttendu(base.etat, service)?.let { FluxWidgets.etats[e.entityId] = base.copy(etat = it) }
        // 1.81.0 (lecteur) : lecture/pause et volume suivent le clic tout de suite.
        if (e.domaine == "media_player") FluxWidgets.etats[e.entityId] = WidgetsHa.etatApres(base, service, donnees.optDouble("volume_level").takeIf { !it.isNaN() })
        // 1.88.0 : une valeur réglée (− / +) ou une option choisie s'affiche tout de suite.
        donnees.optString("option").takeIf { it.isNotEmpty() }?.let { FluxWidgets.etats[e.entityId] = base.copy(etat = it) }
        donnees.optDouble("value").takeIf { !it.isNaN() }?.let { FluxWidgets.etats[e.entityId] = base.copy(etat = WidgetsHa.nombre(it)) }
        val vues = vuesHa.filter { e.entityId in it.reglage.entites }
        vues.forEach { it.maj(); it.alpha = 0.6f }
        Thread {
            val res = WidgetsHaStock.agir(this, e.entityId, service, donnees, confirme)
            val message = res.message
            main.post {
                if (message != null || res.question != null) { if (avant != null) FluxWidgets.etats[e.entityId] = avant else FluxWidgets.etats.remove(e.entityId) }
                vues.forEach { it.alpha = 1f; it.maj() }
                // Home Assistant demande une confirmation que l'appli ne savait pas encore (réglage changé entre-temps).
                if (res.question != null && !confirme && !isFinishing) confirmerHa(e, res.question) { agirHa(e, service, donnees, true) }
                else if (message != null && !isFinishing) Ui.dialogue(this).setTitle(e.nom).setMessage(message).setPositiveButton(getString(R.string.commun_lot2_ok), null).show()
            }
        }.also { it.isDaemon = true }.start()
    }

    /** 1.88.0 : « Confirmer « Nuit » ? » — Confirmer / Annuler ; la question de Home Assistant si elle est donnée. */
    private fun confirmerHa(e: WidgetsHa.Entite, question: String?, suite: () -> Unit) {
        if (isFinishing) return
        Ui.dialogue(this).setTitle(e.nom).setMessage(question?.takeIf { it.isNotEmpty() } ?: getString(R.string.wha_confirmer_question, e.nom))
            .setPositiveButton(getString(R.string.wha_confirmer_oui)) { _, _ -> suite() }
            .setNegativeButton(getString(R.string.commun_lot2_annuler), null).show()
    }

    private fun detailsHa(titre: String, texte: String) {
        Ui.dialogue(this).setTitle(titre).setMessage(texte).setPositiveButton(getString(R.string.commun_lot2_ok), null).show()
    }

    /** Une seule connexion au flux pour tous les widgets Home Assistant à l'écran ; coupée sans eux ou accueil caché. */
    private fun brancherFluxHa() {
        if (!auPremierPlan || vuesHa.isEmpty() || !Modules.actif(this, Modules.ENTITES)) { FluxWidgets.arreter(); return }
        // 1.88.0 : les entités dont un widget montre le détail (relu à chaque changement).
        val details = vuesHa.mapNotNull { v -> val id = v.reglage.entites.first(); WidgetsHaStock.entite(this, id)?.takeIf { WidgetsHa.montreDetail(WidgetsHa.affichage(v.reglage, it)) }?.let { id } }.toSet()
        FluxWidgets.demarrer(this, vuesHa.flatMap { it.reglage.entites }.toSet(), details) { id ->
            vuesHa.forEach { v -> if (id == null || id in v.reglage.entites) v.maj() }
        }
    }

    /** Le relevé a annoncé une autre « widgets.version » (ou rien n'est encore chargé) : la liste des entités se recharge. */
    private fun verifierCatalogueHa() {
        if (catalogueEnCours || (vuesHa.isEmpty() && !ZoneHaut.attendCatalogueHa(this)) || !Modules.actif(this, Modules.ENTITES)) return
        val maintenant = SystemClock.elapsedRealtime()
        if (maintenant - dernierEssaiCatalogue < 60_000L || !WidgetsHaStock.catalogueAPerime(this)) return
        dernierEssaiCatalogue = maintenant; catalogueEnCours = true
        Thread {
            try {
                val avant = if (tele) ZoneHaut.widgets(this) else emptyList()
                WidgetsHaStock.recharger(this)
                main.post {
                    vuesHa.forEach { it.maj() }
                    // 1.91.2 : le tableau de bord de la télé suit le partage de Home Assistant.
                    if (tele && ZoneHaut.widgets(this) != avant) construire()
                }
            } catch (_: Exception) {}
            catalogueEnCours = false
        }.also { it.isDaemon = true }.start()
    }

    /** Télé : les widgets Home Assistant posés, en lecture seule ; OK ouvre leur menu. */
    /**
     * Un widget Home Assistant sur la télé (rangée des widgets, zones de la disposition « Vertical », zone du haut).
     * 16-10 (l'auteur : parité avec le téléphone) : il AGIT comme sur le téléphone (avant : lecture seule) — interrupteur et
     * bouton : OK ; réglage : le focus de la télécommande va sur − et + (anneau d'or) ; liste : OK ouvre les choix.
     * La touche Menu (ou un appui long) ouvre le menu : détail, modifier, retirer. Ce que la télé peut piloter, c'est
     * Home Assistant qui le dit (actions de l'entité, rôle de l'appareil), comme pour le téléphone.
     */
    private fun widgetHaTele(pl: Accueil.Place, r: WidgetsHa.Reglage, modifier: (() -> Unit)? = null, retirer: (() -> Unit)? = null): View {
        val cadre = FrameLayout(this).apply { setPadding(px(6f), px(6f), px(6f), px(6f)); isFocusable = true; isClickable = true; tag = pl.cle; clipChildren = false }
        var menuWidget: () -> Unit = {}
        val v = VueWidgetHa(this, theme, r.copy(w = pl.w, h = pl.h), lectureSeule = false, agir = { e, s -> agirHa(e, s) }, details = { t, m -> detailsHa(t, m) },
            agirAvec = { e, s, d -> agirHa(e, s, d) }, menu = { menuWidget() }).also { vuesHa.add(it) }
        cadre.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        cadre.contentDescription = v.contentDescription
        cadre.setOnFocusChangeListener { x, a ->
            // Un réglage (− / +) : le focus passe tout de suite sur ses commandes.
            if (a) v.commandes().firstOrNull()?.let { it.requestFocus(); return@setOnFocusChangeListener }
            x.background = if (a) fondCarte(28f, couleur = 0, bord = theme.or, epaisseurDp = 2f) else null
            x.animate().scaleX(if (a) 1.05f else 1f).scaleY(if (a) 1.05f else 1f).setDuration(140).start()
        }
        val menu = {
            val choix = arrayOf(getString(R.string.lanceur_voir_detail), getString(R.string.lanceur_modifier_points), getString(R.string.lanceur_retirer_accueil))
            Ui.dialogue(this).setTitle(titreHa(r)).setItems(choix) { _, k ->
                when (k) {
                    0 -> v.voirDetails()
                    1 -> autoriserWidget { (modifier ?: { ouvrirEditeurHa(r.id) })() }
                    else -> autoriserWidget { (retirer ?: { Accueil.retirerDeLaGrille(this, pl.cle); construire() })() }
                }
            }.show()
        }
        menuWidget = { menu() }
        // OK : le widget agit s'il le peut (comme un toucher sur le téléphone), sinon le menu.
        cadre.setOnClickListener { if (v.pilote) v.performClick() else menu() }
        cadre.setOnLongClickListener { menu(); true }
        // 1.91.2 : le focus est sur le cadre ; haut / bas vont au widget (son contenu défile), puis le focus repart.
        cadre.setOnKeyListener { _, code, ev ->
            if (code == KeyEvent.KEYCODE_MENU) { if (ev.action == KeyEvent.ACTION_UP) menu(); return@setOnKeyListener true }
            (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) && v.dispatchKeyEvent(ev)
        }
        return cadre
    }

    /**
     * 16-10 (l'auteur : « le code gestion pour installer des widgets, c'est nul ») : sur la télé, poser, modifier ou retirer un
     * widget ne demande plus de code — on n'y met que des widgets de Vision ou des entités que Home Assistant partage
     * déjà avec cet appareil. Le téléphone garde la règle d'avant (droit « accueil » ou code).
     */
    private fun autoriserWidget(ok: () -> Unit) = if (tele) ok() else autoriserAccueil(ok)

    /**
     * 16-10 (l'auteur : parité des widgets) : un widget de Vision (chauffage, fioul, école, caméra, batteries, personnes,
     * demandes…) sur la télé, dessiné EXACTEMENT comme sur le téléphone (même contenu, mêmes styles), agrandi pour la
     * télé ; [lp] sa place. OK : son action (caméra : la vue en direct), sinon le menu ; Menu ou appui long : retirer.
     */
    private fun widgetIntegreTele(code: String, w: Int, h: Int, retirer: () -> Unit): View {
        val base = Agrandi(this, px(92f) * w, px(84f) * h).apply { tag = "widget_tele:$code"; addView(contenuWidget(code)) }
        val cadre = FrameLayout(this).apply {
            setPadding(px(6f), px(6f), px(6f), px(6f)); isFocusable = true; isClickable = true; clipChildren = false
            contentDescription = Accueil.widget(code)?.nom ?: code; tag = "carte_widget_$code"
        }
        cadre.addView(base, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        cadre.setOnFocusChangeListener { x, a ->
            x.background = if (a) fondCarte(28f, couleur = 0, bord = theme.or, epaisseurDp = 2f) else null
            x.animate().scaleX(if (a) 1.05f else 1f).scaleY(if (a) 1.05f else 1f).setDuration(140).start()
        }
        val menu = {
            Ui.dialogue(this).setTitle(Accueil.widget(code)?.nom ?: code).setItems(arrayOf(getString(R.string.lanceur_retirer_accueil))) { _, _ -> autoriserWidget(retirer) }.show()
        }
        cadre.setOnClickListener { val c = base.getChildAt(0); if (c != null && c.hasOnClickListeners()) c.performClick() else menu() }
        cadre.setOnLongClickListener { menu(); true }
        cadre.setOnKeyListener { _, code2, ev -> if (code2 == KeyEvent.KEYCODE_MENU) { if (ev.action == KeyEvent.ACTION_UP) menu(); true } else false }
        return cadre
    }

    /**
     * Un contenu dessiné à la taille du téléphone ([largeur] × [hauteur] px), agrandi d'un bloc pour remplir sa place
     * (sans déformer : même facteur en largeur et en hauteur, centré) — les widgets de Vision gardent leurs proportions.
     */
    private class Agrandi(ctx: android.content.Context, private val largeur: Int, private val hauteur: Int) : FrameLayout(ctx) {
        override fun onMeasure(l: Int, h: Int) {
            getChildAt(0)?.measure(MeasureSpec.makeMeasureSpec(largeur, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(hauteur, MeasureSpec.EXACTLY))
            val w = if (MeasureSpec.getMode(l) == MeasureSpec.UNSPECIFIED) largeur else MeasureSpec.getSize(l)
            val ht = if (MeasureSpec.getMode(h) == MeasureSpec.UNSPECIFIED) hauteur else MeasureSpec.getSize(h)
            setMeasuredDimension(w, ht)
        }
        override fun onLayout(changed: Boolean, g: Int, t: Int, d: Int, b: Int) {
            val c = getChildAt(0) ?: return
            c.layout(0, 0, largeur, hauteur)
            val f = minOf((d - g).toFloat() / largeur, (b - t).toFloat() / hauteur).coerceAtLeast(0.1f)
            c.pivotX = 0f; c.pivotY = 0f; c.scaleX = f; c.scaleY = f
            c.translationX = ((d - g) - largeur * f) / 2f; c.translationY = ((b - t) - hauteur * f) / 2f
        }
    }

    /** 16-10 : les widgets de Vision qu'on peut poser ici (publics, modules, données du lieu), comme le panneau du téléphone. */
    private fun widgetsVisionPosables(): List<Accueil.Widget> {
        val actifs = Modules.actifs(this)
        val profil = profil()
        val donneesDuLieu = Accueil.disponibles(this)
        return Accueil.WIDGETS.filter { (it.publics.isEmpty() || profil in it.publics) && Modules.widgetVisible(actifs, it.code) && Accueil.disponible(it.code, donneesDuLieu) }
    }

    /** 16-10 (télé, disposition « Rangées ») : « + › Un widget de Vision » — le choix, puis le widget dans la rangée des widgets. */
    private fun ajouterWidgetVisionTele() {
        val poses = Accueil.disposition(this).map { it.cle }.toSet()
        val l = widgetsVisionPosables().filter { Accueil.PREFIXE_WIDGET + it.code !in poses }
        if (l.isEmpty()) { Ui.toast(this, getString(R.string.lanceur_widgets_tous_poses)); return }
        Ui.dialogue(this).setTitle(getString(R.string.lanceur_un_widget_vision)).setItems(l.map { it.nom }.toTypedArray()) { _, k ->
            autoriserWidget { Accueil.ajouterALaGrille(this, Accueil.PREFIXE_WIDGET + l[k].code, l[k].w, l[k].h); construire() }
        }.setNegativeButton(getString(R.string.commun_lot2_annuler), null).show()
    }

    /** Le « + » de la télé : des applications, un widget de Vision, un widget Home Assistant (module « entites »). */
    private fun ajouterTele() {
        val choix = ArrayList<Pair<String, () -> Unit>>()
        choix += getString(R.string.lanceur_des_applications) to { choisirApplis() }
        // Disposition « Vertical » : les widgets vont dans les zones autour de la colonne.
        if (teleVerticale()) choix += getString(R.string.lanceur_un_widget) to { choisirZoneTele() }
        else {
            choix += getString(R.string.lanceur_un_widget_vision) to { ajouterWidgetVisionTele() }
            if (Modules.actif(this, Modules.ENTITES)) choix += getString(R.string.lanceur_un_widget_ha) to { ajouterWidgetHa() }
        }
        Ui.dialogue(this).setTitle(getString(R.string.exceptions_ajouter)).setItems(choix.map { it.first }.toTypedArray()) { _, k -> choix[k].second() }.show()
    }

    /** Disposition « Vertical » : dans quelle zone poser un widget (les côtés libres, les rangées du haut et du bas). */
    private fun choisirZoneTele() {
        val zones = DispositionAccueil.zonesTele(DispositionAccueil.colonne(this))
        Ui.dialogue(this).setTitle(getString(R.string.lanceur_un_widget)).setItems(zones.map { DispositionAccueil.nomZone(this, it) }.toTypedArray()) { _, k ->
            DispositionAccueil.personnaliserZone(this, zones[k]) { construire() }
        }.setNegativeButton(getString(R.string.commun_lot2_annuler), null).show()
    }

    // ------------------------------------------------------------------ redimensionnement (appui long → cadre à poignées)

    /** Les tailles permises : celles de l'appli (Android), du style (Home Assistant), ou au moins la taille d'origine (Vision). */
    private fun bornesDe(pl: Accueil.Place): Redim.Bornes = when {
        WidgetsAndroid.estAndroid(pl.code) -> WidgetsAndroid.bornes(WidgetsAndroid.id(pl.code)?.let { WidgetsAndroid.gestionnaire(this).getAppWidgetInfo(it) },
            celluleL / dm.density, celluleH / dm.density, pl.w to pl.h)
        WidgetsHa.estHa(pl.code) -> WidgetsHaStock.reglage(this, WidgetsHa.id(pl.code))?.let { WidgetsHa.bornes(it.style) } ?: Redim.Bornes(pl.w, pl.h, pl.w, pl.h, false, false)
        else -> Accueil.widget(pl.code)?.let { Redim.Bornes(it.w, it.h, Accueil.COLONNES, 4) } ?: Redim.Bornes(pl.w, pl.h, pl.w, pl.h, false, false)
    }

    private fun fermerRedim() { cadreRedim?.let { racine.removeView(it) }; cadreRedim = null; redimAutorise = false }

    private fun ouvrirRedim(cle: String) { Accueil.disposition(this).firstOrNull { it.cle == cle }?.let { ouvrirRedim(it) } }

    private fun ouvrirRedim(pl: Accueil.Place) {
        cadreRedim?.let { racine.removeView(it) }; cadreRedim = null
        if (tele) return
        var vue: View? = null; var g: GrilleAccueil? = null
        for (gr in grilles()) for (i in 0 until gr.childCount) { val v = gr.getChildAt(i); if ((v.tag as? Accueil.Place)?.cle == pl.cle) { vue = v; g = gr } }
        val grilleW = g ?: return
        val bornes = bornesDe(pl)
        val posG = IntArray(2); grilleW.getLocationInWindow(posG); val posR = IntArray(2); racine.getLocationInWindow(posR)
        val ox = (posG[0] - posR[0] + grilleW.retrait).toFloat(); val oy = (posG[1] - posR[1] + grilleW.paddingTop).toFloat()
        val lc = grilleW.largeurCellule.toFloat(); val hc = grilleW.hauteurCellule.toFloat(); val dec = grilleW.decalageLignes
        fun versPx(r: Redim.Rect) = RectF(ox + r.col * lc + px(2f), oy + (r.row - dec) * hc + px(2f), ox + (r.col + r.w) * lc - px(2f), oy + (r.row - dec + r.h) * hc - px(2f))
        val depart = Redim.Rect(pl.col, pl.row, pl.w, pl.h)
        // Toutes les cases enregistrées, même masquées (module coupé) : elles reviendront à leur place.
        val autres = Accueil.disposition(this).map { Redim.Case(it.cle, Redim.Rect(it.col, it.row, it.w, it.h)) }
        var base = depart; var courant = depart
        val cadre = CadreRedim(this, theme, versPx(depart), bornes.horizontal && bornes.minW != bornes.maxW, bornes.vertical && bornes.minH != bornes.maxH, lc, hc,
            debut = { base = courant },
            pas = { bord, n -> courant = Redim.calculer(autres, pl.cle, base, bord, n, bornes, Accueil.COLONNES, Accueil.lignesParPage); versPx(courant) },
            fin = { if (courant != depart) appliquerTaille(pl, courant) },
            fermer = { fermerRedim() },
            menu = { fermerRedim(); menuWidget(pl) })
        racine.addView(cadre, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        cadreRedim = cadre
        vue?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    /** La nouvelle taille est gardée (et annoncée au widget Android) ; le cadre se rouvre sur le widget rebâti. */
    private fun appliquerTaille(pl: Accueil.Place, r: Redim.Rect) {
        val faire = {
            Accueil.poserPlace(this, pl.cle, r.col, r.row, r.w, r.h)
            if (WidgetsHa.estHa(pl.code)) WidgetsHaStock.reglage(this, WidgetsHa.id(pl.code))?.let { WidgetsHaStock.ecrire(this, it.copy(w = r.w, h = r.h)) }
            if (WidgetsAndroid.estAndroid(pl.code)) WidgetsAndroid.id(pl.code)?.let { id ->
                WidgetsAndroid.annoncerTaille(this, id, null, (celluleL * r.w / dm.density).toInt(), (celluleH * r.h / dm.density).toInt())
            }
            val y = defileAccueil?.scrollY ?: 0
            construire()
            defileAccueil?.let { d -> d.post { d.scrollTo(0, y); racine.post { ouvrirRedim(pl.cle); redimAutorise = true } } }
                ?: racine.post { ouvrirRedim(pl.cle); redimAutorise = true }
        }
        if (redimAutorise) { faire(); return }
        // Sans la fonction « accueil » : le code de gestion d'abord ; en attendant, le cadre revient à la taille posée.
        val fonctions = Profil.fonctions(this)
        val libre = cfg.modeParent || "accueil" in fonctions || (fonctions.isEmpty() && profil() == "parent")
        if (libre) { faire(); return }
        fermerRedim(); construire()
        autoriserAccueil { faire() }
    }

    private fun dissoudre(cle: String) {
        val ds = Accueil.dossiers(this)
        val d = ds.firstOrNull { it.cle == cle } ?: return
        val l = ArrayList(Accueil.ordre(this))
        val k = l.indexOf(cle).let { if (it < 0) l.size else it }
        l.remove(cle)
        d.pkgs.reversed().forEach { l.add(k.coerceIn(0, l.size), it) }
        ds.remove(d)
        Accueil.ecrireDossiers(this, ds); Accueil.ecrireOrdre(this, l)
    }

    private fun renommer(d: Accueil.Dossier) {
        val champ = EditText(this).also { ClavierTele.brancher(it) }.apply { setText(d.nom); setSelectAllOnFocus(true) }
        Ui.dialogue(this).setTitle(getString(R.string.lanceur_nom_dossier)).setView(champ)
            .setPositiveButton(getString(R.string.commun_lot2_garder)) { _, _ -> Accueil.renommerDossier(this, d.cle, champ.text.toString()); dossierOuvert?.dismiss(); construire() }
            .setNegativeButton(getString(R.string.commun_lot2_annuler), null).show()
    }

    /** Le dossier ouvert : ses applis en grille, un bouton pour renommer. */
    private fun ouvrirDossier(d: Accueil.Dossier, cles: List<String>) {
        val dlg = Dialog(this)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val boite = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = fondCarte(30f); setPadding(px(18f), px(20f), px(18f), px(16f)) }
        val tete = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        tete.addView(texte(d.nom, if (tele) tv(34f) else 22f, theme.encre, Polices.gras(this)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // Lot 6 (APP-21, APP-22) : le crayon se lit « Renommer » et fait 48 dp.
        val crayon = FrameLayout(this).apply { background = fondCarte(22f, couleur = 0); isClickable = true; isFocusable = true; setOnClickListener { renommer(d) }; contentDescription = getString(R.string.tableauediteur_renommer) }
        crayon.addView(icone(R.drawable.ic_crayon, 22f, theme.or), FrameLayout.LayoutParams(px(22f), px(22f), Gravity.CENTER))
        tete.addView(crayon, LinearLayout.LayoutParams(px(Ui.CIBLE_MIN), px(Ui.CIBLE_MIN)))
        boite.addView(tete)
        val grilleD = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, px(14f), 0, 0) }
        val parLigne = if (tele) 6 else 3
        var ligne: LinearLayout? = null
        d.pkgs.forEachIndexed { i, pkg ->
            if (i % parLigne == 0) { ligne = LinearLayout(this); grilleD.addView(ligne) }
            val taille = if (tele) tv(96f) else 56f
            val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(px(4f), px(8f), px(4f), px(8f)); isClickable = true; isFocusable = true }
            val dsq = disque(Accueil.icone(this, pkg), taille)
            val anneau = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0); setStroke(px(1.5f), theme.or) }; visibility = View.INVISIBLE }
            val cadre = FrameLayout(this)
            cadre.addView(dsq, FrameLayout.LayoutParams(px(taille), px(taille), Gravity.CENTER))
            cadre.addView(anneau, FrameLayout.LayoutParams(px(taille + 12f), px(taille + 12f), Gravity.CENTER))
            cell.addView(cadre, LinearLayout.LayoutParams(px(taille + 12f), px(taille + 12f)))
            cell.addView(texte(Accueil.etiquette(this, pkg), if (tele) tv(20f) else 12f, theme.encre2, Polices.moyen(this)).apply { maxLines = 2; gravity = Gravity.CENTER; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, px(5f), 0, 0); Ui.motsEntiers(this, if (tele) tv(20f) else 12f, if (tele) tv(14f) else 8f) })
            cell.contentDescription = Accueil.etiquette(this, pkg)
            cell.setOnFocusChangeListener { _, a -> anneau.visibility = if (a) View.VISIBLE else View.INVISIBLE }
            griser(cell, pkg)
            cell.setOnClickListener { dlg.dismiss(); ouvrir(pkg) }
            if (tele) cell.setOnLongClickListener {
                Ui.dialogue(this).setTitle(Accueil.etiquette(this, pkg)).setItems(arrayOf(getString(R.string.lanceur_sortir_dossier), getString(R.string.lanceur_retirer_accueil))) { _, k ->
                    if (k == 0) Accueil.sortirDuDossier(this, cles, d.cle, pkg) else Accueil.cacher(this, pkg, true)
                    dlg.dismiss(); construire()
                }.show()
                true
            }
            // Téléphone : le menu, ou, si le doigt part, l'appli sort du dossier en glissant vers l'accueil.
            else appuiLongPuisGlisser(cell, dsq, pkg, menu = { menuTelephone(Accueil.Case(pkg, pkg = pkg), d) }, depuisDossier = true, apres = { dlg.dismiss() })
            ligne!!.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val reste = d.pkgs.size % parLigne
        if (reste != 0) repeat(parLigne - reste) { ligne!!.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
        boite.addView(grilleD)
        dlg.setContentView(boite)
        dlg.window?.let { w ->
            w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
            w.setLayout(if (tele) (dm.widthPixels * 0.6f).toInt() else ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            w.attributes = w.attributes.apply { horizontalMargin = 0.06f }
            w.setDimAmount(0.55f)
        }
        dlg.setOnDismissListener { if (dossierOuvert === dlg) { dossierOuvert = null; dossierOuvertCle = null } }
        dossierOuvert = dlg; dossierOuvertCle = d.cle
        dlg.show()
    }

    // ---------------------------------------------------------------- tiroir

    /*
     * Le tiroir des applications est une feuille posée dans l'accueil, prête d'avance : elle suit le doigt dès
     * qu'il commence à monter, se pose ou se range selon l'élan au lâcher, et se referme en la tirant vers le bas,
     * en touchant au-dessus ou avec Retour. Les icônes sont lues à part, pour que rien ne bloque le geste.
     */
    private var tiroirCouche: FrameLayout? = null
    private var tiroirFeuille: FrameLayout? = null
    private var tiroirVoile: View? = null
    private var tiroirContenu: ScrollView? = null
    private var tiroirCle = ""
    private var tiroirP = 0f
    private var tiroirAnim: android.animation.ValueAnimator? = null
    private val tiroirOuvert get() = tiroirP > 0.01f
    private val iconesTiroir = java.util.concurrent.ConcurrentHashMap<String, Drawable>()
    private val nomsTiroir = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun courseTiroir(): Float = (tiroirFeuille?.height?.takeIf { it > 0 } ?: (dm.heightPixels * 0.86f).toInt()).toFloat()

    /** Suit un doigt : vers le haut depuis le bas de l'écran pour ouvrir, vers le bas depuis la feuille pour fermer. */
    private inner class GesteTiroir(private val ouvre: Boolean, private val seuilFacteur: Float = 0.6f, private val si: () -> Boolean = { true }) {
        private var y0 = 0f; private var x0 = 0f; private var tire = false
        private var yAvant = 0f; private var tAvant = 0L; private var vitesse = 0f
        private val seuil by lazy { ViewConfiguration.get(this@LanceurActivity).scaledTouchSlop * seuilFacteur }

        fun suivre(e: MotionEvent, v: View): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { y0 = e.rawY; x0 = e.rawX; tire = false; yAvant = e.rawY; tAvant = e.eventTime; vitesse = 0f }
                MotionEvent.ACTION_MOVE -> {
                    val dt = (e.eventTime - tAvant).coerceAtLeast(1)
                    vitesse = vitesse * 0.4f + ((e.rawY - yAvant) / dt * 1000f) * 0.6f
                    yAvant = e.rawY; tAvant = e.eventTime
                    val dy = e.rawY - y0; val dx = e.rawX - x0
                    val sens = if (ouvre) -dy else dy
                    if (!tire && sens > seuil && Math.abs(dy) > Math.abs(dx) && enDrag == null && enDragNouveau == null && enDragAppli == null && panneau == null
                        && (ouvre || (tiroirContenu?.scrollY ?: 0) <= 0) && si()) {
                        tire = true; y0 = e.rawY
                        tiroirAnim?.cancel()
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (tire) { val part = (e.rawY - y0) / courseTiroir(); tirerTiroir(if (ouvre) -part else 1f - part) }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (tire) {
                    tire = false
                    // L'élan décide ; sans élan, c'est la position.
                    val monte = if (Math.abs(vitesse) > 450f) vitesse < 0f else tiroirP > (if (ouvre) 0.3f else 0.7f)
                    animerTiroir(if (monte) 1f else 0f, vitesse)
                }
            }
            return tire
        }
    }

    /** Pose la feuille (fermée) par-dessus l'accueil et la remplit sans attendre le premier geste. */
    private fun poserTiroir() {
        tiroirAnim?.cancel()
        val couche = FrameLayout(this).apply { visibility = View.GONE }
        val voile = View(this).apply { setBackgroundColor(0xFF000000.toInt()); alpha = 0f; setOnClickListener { animerTiroir(0f) } }
        couche.addView(voile, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val feuille = object : FrameLayout(this) {
            private val geste = GesteTiroir(ouvre = false)
            override fun onInterceptTouchEvent(e: MotionEvent): Boolean = geste.suivre(e, this)
            override fun onTouchEvent(e: MotionEvent): Boolean { geste.suivre(e, this); return true }
        }
        feuille.background = fondCarte(30f)
        // Les icônes qui défilent restent dans la feuille, coins arrondis compris.
        feuille.clipToOutline = true; feuille.clipChildren = true
        couche.addView(feuille, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (dm.heightPixels * 0.86f).toInt(), Gravity.BOTTOM))
        racine.addView(couche, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        tiroirCouche = couche; tiroirFeuille = feuille; tiroirVoile = voile; tiroirP = 0f
        feuille.translationY = dm.heightPixels * 0.86f
        remplirTiroir()
    }

    private fun remplirTiroir() {
        val feuille = tiroirFeuille ?: return
        val cle = empreinte() + "|" + theme.carte + "|" + theme.encre
        val deja = tiroirContenu
        if (deja != null && tiroirCle == cle) { (deja.parent as? ViewGroup)?.removeView(deja); feuille.addView(deja); deja.scrollTo(0, 0); return }
        val toutes = Accueil.applicationsInstallees(this)
        val pm = packageManager
        Thread {
            toutes.forEach { ri ->
                val p = ri.activityInfo.packageName
                if (!iconesTiroir.containsKey(p)) try { iconesTiroir[p] = ri.loadIcon(pm) } catch (_: Exception) {}
                if (!nomsTiroir.containsKey(p)) try { nomsTiroir[p] = ri.loadLabel(pm).toString() } catch (_: Exception) {}
            }
            main.post { if (tiroirFeuille === feuille && !isFinishing) construireTiroir(feuille, toutes, cle) }
        }.also { it.isDaemon = true }.start()
    }

    /** Colonnes d'icônes d'une grille de liste (tiroir) : 4, ou moins si la taille du texte ne laisse pas la place à un nom. */
    private fun colonnesIcones(): Int {
        val largeurDp = resources.configuration.screenWidthDp - 36
        return (largeurDp / (60f * resources.configuration.fontScale.coerceAtLeast(1f))).toInt().coerceIn(2, 4)
    }

    private fun construireTiroir(feuille: FrameLayout, toutes: List<android.content.pm.ResolveInfo>, cle: String) {
        val defile = ScrollView(this).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; clipToPadding = true; clipChildren = true }
        val boite = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(18f), px(10f), px(18f), px(16f)) }
        defile.addView(boite)
        val prise = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, px(10f)) }
        prise.addView(View(this).apply { background = GradientDrawable().apply { cornerRadius = px(3f).toFloat(); setColor(theme.encre3) } }, LinearLayout.LayoutParams(px(44f), px(5f)))
        boite.addView(prise)
        boite.addView(texte(getString(R.string.lanceur_applications), 22f, theme.encre, Polices.gras(this)))
        boite.addView(texte(getString(R.string.lanceur_tiroir_aide), 12f, theme.encre2).apply { setPadding(0, px(2f), 0, px(8f)) })
        val pm = packageManager
        val surAccueil = Accueil.disposition(this).map { it.cle }.toSet() + Accueil.dossiers(this).flatMap { it.pkgs }
        var ligne: LinearLayout? = null
        // 4 colonnes, moins en grands caractères : un nom d'appli passe à la ligne entre deux mots, jamais au milieu.
        val colonnes = colonnesIcones()
        toutes.forEachIndexed { i, ri ->
            val pkg = ri.activityInfo.packageName
            val nom = nomsTiroir[pkg] ?: ri.loadLabel(pm).toString()
            if (i % colonnes == 0) { ligne = LinearLayout(this); boite.addView(ligne) }
            val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(px(2f), px(8f), px(2f), px(8f)); isClickable = true; isFocusable = true }
            val icTiroir = disque(iconesTiroir[pkg] ?: ri.loadIcon(pm), 52f)
            cell.addView(icTiroir, LinearLayout.LayoutParams(px(52f), px(52f)))
            cell.addView(texte(nom, 11.5f, theme.encre2, Polices.moyen(this)).apply { maxLines = 2; gravity = Gravity.CENTER; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, px(5f), 0, 0); alpha = if (pkg in surAccueil) 1f else 0.7f; Ui.motsEntiers(this, 11.5f, 6.5f) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            cell.contentDescription = nom
            griser(cell, pkg)
            cell.setOnClickListener { tirerTiroir(0f); ouvrir(pkg) }
            // Appui long : le menu ; si le doigt part, le menu s'efface, le tiroir se range et l'appli glisse sur l'accueil.
            appuiLongPuisGlisser(cell, icTiroir, pkg, menu = {
                val deja = pkg in surAccueil
                Ui.dialogue(this).setTitle(nom).setItems(arrayOf(if (deja) getString(R.string.lanceur_deja_accueil) else getString(R.string.lanceur_ajouter_accueil), getString(R.string.lanceur_infos), getString(R.string.lanceur_desinstaller))) { _, k ->
                    when (k) {
                        0 -> if (!deja) { Accueil.ajouterALaGrille(this, pkg); tirerTiroir(0f); construire() }
                        1 -> ActionsAppli.infosProtegees(this, pkg)  // lot accueil (point 7) : la page entière, code d'abord
                        2 -> Paquets.desinstaller(this, pkg)
                    }
                }.show()
            })
            ligne!!.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val reste = toutes.size % colonnes
        if (reste != 0) repeat(colonnes - reste) { ligne!!.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
        feuille.removeAllViews()
        feuille.addView(defile, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        tiroirContenu = defile; tiroirCle = cle
    }

    /** Place la feuille : 0 = rangée, 1 = ouverte. Le voile fonce à mesure qu'elle monte. */
    private fun tirerTiroir(p: Float) {
        val couche = tiroirCouche ?: return
        val feuille = tiroirFeuille ?: return
        val avant = tiroirP
        tiroirP = p.coerceIn(0f, 1f)
        if (tiroirP <= 0f) {
            if (avant > 0f) tiroirAnim?.cancel()
            couche.visibility = View.GONE; feuille.setLayerType(View.LAYER_TYPE_NONE, null); feuille.translationY = courseTiroir()
            return
        }
        if (couche.visibility != View.VISIBLE) {
            couche.visibility = View.VISIBLE; couche.bringToFront()
            tiroirContenu?.scrollTo(0, 0)
        }
        // Pendant le mouvement, la feuille est une image que la carte graphique déplace : pas une saccade.
        if (tiroirP < 1f && feuille.layerType != View.LAYER_TYPE_HARDWARE) feuille.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        if (tiroirP >= 1f && feuille.layerType != View.LAYER_TYPE_NONE) feuille.setLayerType(View.LAYER_TYPE_NONE, null)
        feuille.translationY = courseTiroir() * (1f - tiroirP)
        tiroirVoile?.alpha = 0.5f * tiroirP
    }

    /** Amène la feuille à sa place en douceur, à l'allure du geste qui l'a lancée. */
    private fun animerTiroir(cible: Float, vitesse: Float = 0f) {
        tiroirAnim?.cancel()
        val depart = tiroirP
        val distance = Math.abs(cible - depart)
        if (distance < 0.002f) { tirerTiroir(cible); return }
        val duree = if (Math.abs(vitesse) > 450f) (distance * courseTiroir() / Math.abs(vitesse) * 1000f * 1.8f).toLong().coerceIn(150L, 360L) else (200 + 180 * distance).toLong()
        tiroirAnim = android.animation.ValueAnimator.ofFloat(depart, cible).apply {
            duration = duree
            interpolator = android.view.animation.DecelerateInterpolator(1.9f)
            addUpdateListener { tirerTiroir(it.animatedValue as Float) }
            start()
        }
    }

    private fun tiroirApplis() { animerTiroir(1f) }

    // ---------------------------------------------------------------- widgets

    private fun vueWidget(pl: Accueil.Place): View {
        if (WidgetsAndroid.estAndroid(pl.code)) return vueWidgetAndroid(pl)
        if (WidgetsHa.estHa(pl.code)) return vueWidgetHa(pl)
        val d = donnees
        val cadre = FrameLayout(this).apply { setPadding(px(4f), px(4f), px(4f), px(4f)); clipChildren = false }
        val c = contenuWidget(pl.code)
        cadre.addView(c, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        cadre.isClickable = true
        cadre.setOnLongClickListener { v -> soulever(v, c, pl.cle) }
        // Un widget qui réagit au toucher (caméra, demandes…) garde l'appui pour lui : sans ceci, l'appui long
        // n'arrivait jamais au cadre et le widget ne pouvait plus être déplacé ni retiré.
        c.setOnLongClickListener { soulever(cadre, c, pl.cle) }
        cadre.setOnDragListener { v, e -> if (e.action == DragEvent.ACTION_DRAG_ENDED && enDrag == pl.cle) finDrag(v, e) { ouvrirRedim(pl.cle) }; false }
        if (d == null && pl.code != "horloge" && pl.code != ZoneHaut.HORLOGE) c.alpha = 0.6f
        return cadre
    }

    /** Le contenu d'un widget de Vision (le même sur le téléphone, la tablette et la télé — 16-10, parité). */
    private fun contenuWidget(code: String): View {
        val c: View = when (code) {
            "horloge" -> widgetHorloge()
            ZoneHaut.HORLOGE -> carte().also { c -> c.gravity = Gravity.CENTER; widgetZone(ZoneHaut.HORLOGE, tele = false, tailleTexte = 14f, coteOeil = 0, lierDate = false)?.let { c.addView(it) } }
            "infos" -> widgetInfos()
            "devoirs" -> widgetDevoirs()
            "temps" -> widgetTemps()
            "demandes" -> widgetDemandes()
            "enfants" -> widgetEnfants()
            "chauffage" -> widgetChauffage()
            "fioul" -> widgetFioul()
            "ecole" -> widgetEcole()
            "camera" -> widgetCamera()
            "batteries" -> widgetBatteries()
            "maison" -> widgetMaison()
            else -> carte().also { it.addView(texte(code, 13f, theme.encre3)) }
        }
        if (donnees == null && code != "horloge" && code != ZoneHaut.HORLOGE) c.alpha = 0.6f
        return c
    }

    private fun w(titre: String?, orientation: Int = LinearLayout.VERTICAL, bordOr: Boolean = false): LinearLayout = carte(24f, 14f, orientation, bordOr).also { c ->
        if (titre != null) c.addView(etiquetteSection(titre, 11f).apply { setPadding(0, 0, 0, px(4f)) })
    }

    private fun widgetHorloge(): View {
        val c = w(null, LinearLayout.HORIZONTAL).apply { gravity = Gravity.CENTER_VERTICAL }
        val g = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // 1.87.0 (recette visuelle) : l'heure sur une ligne (« 12:06 PM » passait sur deux lignes en grands caractères).
        g.addView(texte(heureFmt.format(Date()), 46f, theme.encre, Polices.outfitFin(this)).apply { includeFontPadding = false; tag = "w_heure"; Ui.surUneLigne(this, 46f, 24f) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        g.addView(texte(dateFmt.format(Date()), 13f, theme.encre2, Polices.moyen(this)))
        c.addView(g, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val dr = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        dr.addView(texte("", 30f, theme.encre, Polices.outfitLeger(this)).apply { tag = "temp"; includeFontPadding = false })
        dr.addView(lignePluie(0f, 20f).apply { tag = "pluie" })
        dr.addView(texte("", 11f, theme.encre2).apply { tag = "pluie_texte" })
        c.addView(dr)
        return c
    }

    private fun ecoleDe(prenom: String): JSONObject? {
        val a = donnees?.optJSONArray("ecole") ?: return null
        for (i in 0 until a.length()) { val e = a.getJSONObject(i); if (prenom.isNotEmpty() && e.optString("prenom").equals(prenom, true)) return e }
        return if (a.length() == 1) a.getJSONObject(0) else null
    }

    private fun widgetInfos(): View {
        val moi = donnees?.optJSONObject("moi")
        val ecole = ecoleDe(moi?.optString("prenom").orEmpty())
        val controles = ecole?.optJSONArray("controles")
        val moy = moi?.optJSONObject("moyenne")
        val titre = when {
            controles != null && controles.length() > 0 -> getString(R.string.lanceur_controle_de, minuscules(controles.getJSONObject(0).optString("matiere")), if (ecole!!.optBoolean("demain")) getString(R.string.lanceur_demain) else ecole.optString("jour"))
            ecole != null -> getString(R.string.lanceur_ecole_de_a, if (ecole.optBoolean("demain")) getString(R.string.lanceur_demain_maj) else ecole.optString("jour").replaceFirstChar { it.uppercase() }, heureJolie(ecole.optString("debut")), heureJolie(ecole.optString("fin")))
            else -> getString(R.string.lanceur_rien_de_particulier)
        }
        val sous = ArrayList<String>()
        if (moy != null && !moy.isNull("valeur")) sous.add(getString(R.string.lanceur_moyenne, String.format(Locale.getDefault(), "%.1f", moy.optDouble("valeur"))))
        moy?.optString("etat")?.let { if (it.contains("divertissement")) sous.add(it) }
        val c = w(null, LinearLayout.HORIZONTAL, bordOr = true).apply { gravity = Gravity.CENTER_VERTICAL }
        c.addView(icone(R.drawable.ic_alerte, 26f, theme.or))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(12f), 0, 0, 0) }
        col.addView(texte(titre, 14.5f, theme.encre, Polices.gras(this)))
        if (sous.isNotEmpty()) col.addView(texte(sous.joinToString(" · "), 12f, theme.encre2))
        c.addView(col)
        return c
    }

    private fun widgetDevoirs(): View {
        val moi = donnees?.optJSONObject("moi")
        val ecole = ecoleDe(moi?.optString("prenom").orEmpty())
        val devoirs = ecole?.optJSONArray("devoirs")
        val c = w(null).apply { setPadding(px(14f), px(10f), px(14f), px(6f)) }
        val jour = ecole?.optString("jour")?.split(" ")?.firstOrNull().orEmpty()
        val tete = LinearLayout(this)
        tete.addView(etiquetteSection(if (jour.isEmpty()) getString(R.string.lanceur_devoirs) else getString(R.string.lanceur_devoirs_pour, jour), 11f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val compte = texte("", 12f, theme.encre2)
        tete.addView(compte)
        c.addView(tete)
        if (devoirs == null || devoirs.length() == 0) { c.addView(texte(getString(R.string.lanceur_pas_devoirs_connus), 13f, theme.encre3).apply { setPadding(0, px(8f), 0, 0) }); return c }
        val faits = getSharedPreferences(PREFS, MODE_PRIVATE).getStringSet("devoirs_faits", emptySet()) ?: emptySet()
        val cases = ArrayList<CheckBox>()
        fun recompter() { val n = cases.count { it.isChecked }; compte.text = resources.getQuantityString(R.plurals.lanceur_devoirs_faits, n, n, cases.size) }
        for (i in 0 until minOf(4, devoirs.length())) {
            val dv = devoirs.getJSONObject(i)
            val cle = (ecole!!.optString("date") + "|" + dv.optString("matiere") + "|" + dv.optString("texte")).hashCode().toString()
            val ligne = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = px(32f) }
            val cb = Ui.caseACocher(this, coche = cle in faits, th = theme)
            val mat = texte(dv.optString("matiere"), 13f, theme.encre, Polices.gras(this)).apply { minWidth = px(60f) }
            val txt = texte(dv.optString("texte"), 13f, theme.encre2)
            fun peindre() { val a = if (cb.isChecked) 0.6f else 1f; mat.alpha = a; txt.alpha = a; mat.paintFlags = if (cb.isChecked) mat.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG else mat.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv() }
            cb.setOnCheckedChangeListener { _, coche ->
                val s = HashSet(getSharedPreferences(PREFS, MODE_PRIVATE).getStringSet("devoirs_faits", emptySet()) ?: emptySet())
                if (coche) s.add(cle) else s.remove(cle)
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putStringSet("devoirs_faits", s).apply()
                peindre(); recompter()
            }
            ligne.addView(cb); ligne.addView(mat); ligne.addView(txt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            ligne.setOnClickListener { cb.toggle() }
            cases.add(cb); peindre()
            c.addView(ligne)
        }
        recompter()
        return c
    }

    private fun widgetTemps(): View {
        val moi = donnees?.optJSONObject("moi")
        val c = w(null, LinearLayout.HORIZONTAL).apply { gravity = Gravity.CENTER_VERTICAL }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val verrou = moi?.optBoolean("verrouille") == true
        val prochain = moi?.optString("prochain_verrou").orEmpty().takeIf { it.length >= 16 }.orEmpty()
        val minutes = moi?.optInt("minutes_actif") ?: 0
        col.addView(texte(when { verrou -> getString(R.string.lanceur_ecran_ferme); prochain.isNotEmpty() -> getString(R.string.lanceur_ouvert_jusqua, heureJolie(prochain.substring(11, 16))); else -> getString(R.string.lanceur_ecran_aujourdhui, duree(minutes)) }, 14f, theme.encre, Polices.demiGras(this)))
        if (prochain.isNotEmpty() && !verrou) {
            val fin = try { Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, prochain.substring(11, 13).toInt()); set(Calendar.MINUTE, prochain.substring(14, 16).toInt()) }.timeInMillis } catch (_: Exception) { 0L }
            val reste = ((fin - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0L)
            val total = (reste + minutes).coerceAtLeast(1L)
            val part = (minutes / total.toFloat()).coerceIn(0f, 1f)
            val barre = FrameLayout(this).apply { background = GradientDrawable().apply { cornerRadius = px(3f).toFloat(); setColor(Palette.melanger(theme.carte, theme.encre, 0.08f)) } }
            barre.addView(View(this).apply { background = GradientDrawable().apply { cornerRadius = px(3f).toFloat(); setColor(theme.pourpre) } }, FrameLayout.LayoutParams((dm.widthPixels * 0.45f * part).toInt().coerceAtLeast(px(4f)), px(5f)))
            col.addView(barre, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(5f)).apply { topMargin = px(6f) })
            col.addView(texte(getString(R.string.lanceur_encore, duree(reste.toInt())), 11f, theme.encre2).apply { setPadding(0, px(3f), 0, 0) })
        }
        c.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (Modules.actif(this, Modules.DEMANDES)) c.addView(bouton(getString(R.string.lanceur_du_temps), true) { demanderTemps() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = px(8f) })
        return c
    }

    private fun demanderTemps() {
        // Lot 6 (APP-08) : les mêmes durées que l'écran de blocage et l'espace Moi.
        val choix = Durees.libelles(Durees.DEMANDE).toTypedArray()
        Ui.dialogue(this).setTitle(getString(R.string.block_demander_du_temps)).setItems(choix) { _, k ->
            cfg.demandeTemps = Durees.DEMANDE[k]
            Ui.toast(this, this.getString(R.string.acces_demande_envoyee), genre = OeilModele.Genre.SUCCES)
        }.setNegativeButton(getString(R.string.commun_lot2_annuler), null).show()
    }

    // 1.77.0 (règle du projet) : la réponse se voit tout de suite. « En cours… » au clic ; réussie, la demande disparaît
    // et une ligne dit ce qui a été fait (6 s) ; ratée, les boutons reviennent et la ligne dit pourquoi.
    private var demandeEnCours = ""
    private var geste = 0L
    private var noteDemandes: Geste.Resultat? = null
    private var noteDemandesJusqua = 0L

    private fun repondreDemande(dem: JSONObject, accepter: Boolean) {
        if (demandeEnCours.isNotEmpty()) return
        val pc = dem.optString("pc"); val id = dem.optString("id"); val temps = dem.optString("genre") == "temps"
        // FAILLE (l'auteur, 16-10) : accepter = empreinte ou code à chaque fois ; refuser garde la garde ordinaire.
        val garde: (() -> Unit) -> Unit = { f -> if (accepter) Garde.exigerOctroi(this, ParentsActivity.RAISON, ok = f) else Garde.exiger(this, ParentsActivity.RAISON, ok = f) }
        garde {
            demandeEnCours = id
            Geste.enCours()
            racine.findViewWithTag<View>("demandes_boutons")?.let { r -> for (i in 0 until (r as ViewGroup).childCount) r.getChildAt(i).isEnabled = false }
            // 1.80.1 : « En cours… » finit TOUJOURS : sans réponse en 10 s, le bouton revient et le widget le dit.
            val jeton = System.nanoTime(); geste = jeton
            main.postDelayed({
                if (geste == jeton && demandeEnCours == id) {
                    demandeEnCours = ""; geste = 0L
                    Geste.relacher()
                    noteDemandes = Geste.Resultat(false, getString(R.string.lanceur_pas_de_reponse_10s))
                    noteDemandesJusqua = System.currentTimeMillis() + 8_000L
                    appliquerDonnees()
                    demarrerDonnees()
                }
            }, 10_000L)
            val fini: (Geste.Resultat) -> Unit = { res ->
                if (geste == jeton) geste = 0L
                demandeEnCours = ""
                Geste.relacher()
                noteDemandes = res; noteDemandesJusqua = System.currentTimeMillis() + 6_000L
                if (res.ok) donnees?.let { d -> d.put("demandes", Geste.sans(d.optJSONArray("demandes"), id)) }
                appliquerDonnees()
                main.postDelayed({ if (System.currentTimeMillis() >= noteDemandesJusqua) appliquerDonnees() }, 6_100L)
                demarrerDonnees()
            }
            if (temps) ReponseReceiver.envoyer(this, pc, id, if (accepter) dem.optInt("minutes", 30).coerceAtLeast(5) else 0, fini)
            else Acces.repondre(this, pc, id, if (accepter) "temporaire" else "non", apres = fini)
        }
    }

    private fun noteDemandesVue(c: LinearLayout) {
        val n = noteDemandes ?: return
        if (System.currentTimeMillis() > noteDemandesJusqua) { noteDemandes = null; return }
        c.addView(texte(n.texte, 13f, if (n.ok) (if (theme.sombre) 0xFF4CC38A.toInt() else 0xFF2F7A54.toInt()) else (if (theme.sombre) 0xFFFF7B7B.toInt() else 0xFFB0302A.toInt()), Polices.demiGras(this)).apply { setPadding(0, px(4f), 0, px(4f)) })
    }

    private fun widgetDemandes(): View {
        val d = donnees
        // 15-10 (l'auteur : « Aucune demande en attente » alors qu'une demande attendait) : sans nouvelles de Home Assistant,
        // on ne dit pas « aucune » ; et ce qui se décide par lots (appareil à valider…) compte aussi.
        if (d == null) return w(getString(R.string.lanceur_demandes)).also { it.addView(texte(getString(R.string.lanceur_demandes_inconnues), 13f, theme.encre3).apply { setPadding(0, px(4f), 0, 0) }) }
        val demandes = d.optJSONArray("demandes")
        val lots = Demandes.lots(d)
        if ((demandes == null || demandes.length() == 0) && lots.isNotEmpty()) return widgetLots(lots)
        if (demandes == null || demandes.length() == 0) return w(getString(R.string.lanceur_demandes)).also { noteDemandesVue(it); it.addView(texte(getString(R.string.lanceur_aucune_demande), 13f, theme.encre3).apply { setPadding(0, px(6f), 0, 0) }) }
        val dem = demandes.getJSONObject(0)
        // Le nombre total (la liste reçue est coupée à quatre), plus ce qui se décide par lots.
        val n = Demandes.total(d)
        val c = w(if (n > 1) getString(R.string.lanceur_demande_attente_n, n) else getString(R.string.lanceur_demande_attente), bordOr = true)
        noteDemandesVue(c)
        c.addView(texte(getString(R.string.lanceur_demande_quoi, dem.optString("prenom"), Demandes.quoi(dem)), 16f, theme.encre, Polices.demiGras(this)).apply { setPadding(0, px(4f), 0, px(if (dem.optString("genre") == "sites") 2f else 8f)) })
        if (dem.optString("genre") == "sites") c.addView(texte(Demandes.description(dem), 12f, theme.encre2).apply { setPadding(0, 0, 0, px(8f)) })
        val boutons = Ui.rangeeBoutons(this).apply { tag = "demandes_boutons" }
        val enCours = demandeEnCours == dem.optString("id")
        boutons.addView(bouton(getString(R.string.lanceur_refuser), false) { repondreDemande(dem, false) }.apply { isEnabled = !enCours }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = px(8f) })
        boutons.addView(bouton(if (enCours) Geste.EN_COURS else getString(R.string.lanceur_accepter), true) { repondreDemande(dem, true) }.apply { isEnabled = !enCours }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        c.addView(boutons)
        return c
    }

    /** 15-10 : rien à trancher une à une, mais des choses à décider par lots (appareil à valider, à classer, logiciels).
     *  16-10 (retour d'essai) : le titre est « À décider » — « 229 éléments à classer » n'est pas « une demande en attente ». */
    private fun widgetLots(lots: List<Pair<String, Int>>): View {
        val c = w(getString(R.string.adec_titre), bordOr = true)
        noteDemandesVue(c)
        lots.forEach { (libelle, _) -> c.addView(texte(libelle, 15f, theme.encre, Polices.demiGras(this)).apply { setPadding(0, px(4f), 0, 0) }) }
        c.addView(bouton(getString(R.string.lanceur_voir_a_decider), true) { startActivity(Intent(this, ParentsActivity::class.java)) }.apply { tag = "demandes_lots" },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(10f) })
        return c
    }

    private fun widgetEnfants(): View {
        val maison = donnees?.optJSONArray("maison")
        val c = w(getString(R.string.lanceur_les_personnes))
        if (maison == null || maison.length() == 0) { c.addView(texte(getString(R.string.lanceur_personne_pour_linstant), 13f, theme.encre3)); return c }
        for (i in 0 until minOf(4, maison.length())) {
            val p = maison.getJSONObject(i)
            val apps = p.optJSONArray("appareils")
            var enLigne = 0; var verrou = 0
            for (k in 0 until (apps?.length() ?: 0)) { val a = apps!!.getJSONObject(k); if (a.optBoolean("en_ligne")) enLigne++; if (a.optBoolean("verrouille")) verrou++ }
            val etat = when { verrou > 0 && verrou == (apps?.length() ?: 0) -> getString(R.string.lanceur_ferme); enLigne > 0 -> getString(R.string.lanceur_en_ligne, duree(p.optInt("minutes"))); else -> duree(p.optInt("minutes")) }
            val ligne = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = px(30f) }
            ligne.addView(texte(p.optString("prenom"), 14f, theme.encre, Polices.demiGras(this)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            ligne.addView(texte(etat, 13f, theme.encre2))
            c.addView(ligne)
        }
        c.setOnClickListener { startActivity(Intent(this, ParentsActivity::class.java)) }
        return c
    }

    private fun widgetChauffage(): View {
        val ch = donnees?.optJSONObject("chauffage")
        val c = w(getString(R.string.lanceur_chauffage))
        if (ch == null) { c.addView(texte("–", 30f, theme.encre3)); return c }
        c.addView(texte(if (ch.isNull("dedans")) "–" else String.format(Locale.getDefault(), "%.1f°", ch.optDouble("dedans")), 30f, theme.encre, Polices.outfitLeger(this)).apply { includeFontPadding = false })
        val dets = ArrayList<String>()
        if (!ch.isNull("consigne")) dets.add(getString(R.string.lanceur_consigne, String.format(Locale.getDefault(), "%.0f°", ch.optDouble("consigne"))))
        if (!ch.isNull("dehors")) dets.add(getString(R.string.lanceur_dehors, String.format(Locale.getDefault(), "%.0f°", ch.optDouble("dehors"))))
        c.addView(texte(dets.joinToString(" · "), 11.5f, theme.encre2))
        val l = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(6f), 0, 0) }
        l.addView(pastille(if (ch.optBoolean("bruleur_on")) theme.pourpre else theme.encre3), LinearLayout.LayoutParams(px(8f), px(8f)).apply { rightMargin = px(8f) })
        l.addView(texte(if (ch.optBoolean("bruleur_on")) getString(R.string.lanceur_bruleur_marche) else getString(R.string.lanceur_bruleur_repos), 11.5f, theme.encre2))
        c.addView(l)
        return c
    }

    private fun widgetFioul(): View {
        val ch = donnees?.optJSONObject("chauffage")
        val c = w(getString(R.string.lanceur_fioul))
        val js = ch?.optJSONArray("jours"); val n = js?.length() ?: 0
        if (n == 0) { c.addView(texte("–", 30f, theme.encre3)); return c }
        var somme = 0.0; var max = 1.0
        for (i in 0 until n) { val v = js!!.getJSONObject(i).optDouble("litres", 0.0); somme += v; max = maxOf(max, v) }
        c.addView(texte(String.format(Locale.getDefault(), "%.1f L", somme / n), 30f, theme.encre, Polices.outfitLeger(this)).apply { includeFontPadding = false })
        c.addView(texte(resources.getQuantityString(R.plurals.lanceur_par_jour, n, n), 11.5f, theme.encre2))
        val barres = LinearLayout(this).apply { gravity = Gravity.BOTTOM; setPadding(0, px(8f), 0, 0) }
        for (i in maxOf(0, n - 7) until n) {
            val h = (px(26f) * js!!.getJSONObject(i).optDouble("litres", 0.0) / max).toInt().coerceAtLeast(px(3f))
            barres.addView(View(this).apply { background = GradientDrawable().apply { cornerRadius = px(3f).toFloat(); setColor(if (i == n - 1) theme.pourpre else theme.or) } }, LinearLayout.LayoutParams(0, h, 1f).apply { setMargins(px(2f), 0, px(2f), 0) })
        }
        c.addView(barres, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(26f)))
        return c
    }

    private fun widgetEcole(): View {
        val ecoles = donnees?.optJSONArray("ecole")
        val e = ecoles?.let { if (it.length() > 0) it.getJSONObject(0) else null }
        val c = w(if (e == null) getString(R.string.lanceur_ecole) else getString(R.string.lanceur_ecole_prenom, e.optString("prenom")))
        if (e == null) { c.addView(texte(getString(R.string.lanceur_pas_de_cours), 13f, theme.encre3)); return c }
        val controles = e.optJSONArray("controles")
        if (controles != null && controles.length() > 0) c.addView(texte(getString(R.string.lanceur_controle_de, minuscules(controles.getJSONObject(0).optString("matiere")), if (e.optBoolean("demain")) getString(R.string.lanceur_demain) else e.optString("jour")), 14f, theme.encre, Polices.demiGras(this)))
        val cours = e.optJSONArray("cours")
        val noms = ArrayList<String>(); for (k in 0 until minOf(4, cours?.length() ?: 0)) noms.add(cours!!.getJSONObject(k).optString("matiere"))
        if (noms.isNotEmpty()) c.addView(texte(getString(R.string.lanceur_cours, e.optString("jour").replaceFirstChar { it.uppercase() }, heureJolie(e.optString("debut")), noms.joinToString(", ")), 12.5f, theme.encre2))
        val nd = e.optJSONArray("devoirs")?.length() ?: 0
        c.addView(texte(if (nd == 0) getString(R.string.lanceur_pas_de_devoirs) else resources.getQuantityString(R.plurals.lanceur_devoirs_a_faire, nd, nd), 11.5f, theme.encre2))
        return c
    }

    private fun widgetCamera(): View {
        val cams = donnees?.optJSONArray("cameras")
        val c = w(null).apply { setPadding(px(8f), px(8f), px(8f), px(8f)) }
        if (cams == null || cams.length() == 0) { c.addView(texte(getString(R.string.lanceur_camera), 13f, theme.encre3)); return c }
        val img = ImageView(this).apply { tag = "cam_img"; scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true; outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, px(16f).toFloat()) } } }
        c.addView(img, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val cam = cams.getJSONObject(0)
        c.addView(texte(cam.optString("nom"), 11.5f, theme.encre2).apply { setPadding(px(4f), px(4f), 0, 0) })
        chargerImageCamera(cam.optString("id"), img)
        // Toucher la caméra ouvre sa vue en direct dans Home Assistant (l'appli, sinon le navigateur).
        c.setOnClickListener { ouvrirDansHA("/lovelace?more-info-entity-id=" + cam.optString("id")) }
        return c
    }

    private fun ouvrirDansHA(chemin: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("homeassistant://navigate$chemin")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Exception) { }
        try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(Config(this).urlActive.trimEnd('/') + chemin)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { Veille.ouvrir(this, demande = true) }
    }

    private fun widgetBatteries(): View {
        val bats = donnees?.optJSONArray("batteries")
        val c = w(getString(R.string.lanceur_batteries))
        if (bats == null || bats.length() == 0) { c.addView(texte("–", 13f, theme.encre3)); return c }
        for (i in 0 until minOf(3, bats.length())) {
            val b = bats.getJSONObject(i)
            val l = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            l.addView(texte(b.optString("nom"), 11.5f, theme.encre), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            l.addView(texte(getString(R.string.vuewidget_pourcent, b.optInt("niveau")), 11.5f, theme.encre2, Polices.demiGras(this)))
            c.addView(l)
            val niveau = b.optInt("niveau")
            val barre = FrameLayout(this).apply { background = GradientDrawable().apply { cornerRadius = px(2f).toFloat(); setColor(Palette.melanger(theme.carte, theme.encre, 0.08f)) } }
            barre.addView(View(this).apply { background = GradientDrawable().apply { cornerRadius = px(2f).toFloat(); setColor(if (niveau <= 20) theme.pourpre else theme.or) } }, FrameLayout.LayoutParams(0, px(4f)).also { lp -> barre.post { lp.width = (barre.width * niveau / 100f).toInt().coerceAtLeast(px(3f)); barre.requestLayout() } })
            c.addView(barre, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(4f)).apply { topMargin = px(3f); bottomMargin = px(5f) })
        }
        return c
    }

    private fun widgetMaison(): View {
        val maison = donnees?.optJSONArray("maison")
        val c = w(null, LinearLayout.HORIZONTAL).apply { gravity = Gravity.CENTER_VERTICAL }
        if (maison == null || maison.length() == 0) { c.addView(texte(Config(this).lieu, 13f, theme.encre3)); return c }
        for (i in 0 until minOf(4, maison.length())) {
            val p = maison.getJSONObject(i)
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            col.addView(texte(p.optString("prenom"), 12.5f, theme.encre, Polices.demiGras(this)))
            col.addView(texte(duree(p.optInt("minutes")), 11.5f, theme.encre2))
            c.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return c
    }

    private fun chargerImageCamera(entite: String, img: ImageView) {
        Thread {
            try {
                val octets = Net.postBytes(this, cfg, "/api/pc_parental/veille/image", JSONObject().put("id", cfg.id).put("secret", cfg.secret).put("entite", entite), 15_000)
                val bm: Bitmap? = octets?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                if (bm != null) main.post { img.setImageBitmap(bm) }
            } catch (_: Exception) {}
        }.also { it.isDaemon = true }.start()
    }

    /** « 45 min », « 1 h 05 » : la forme de la langue de l'appareil (Profil.duree). */
    private fun duree(min: Int): String = Profil.duree(min)
    /** « 08:30 » reçu de Home Assistant → l'heure à la façon du téléphone (langue, 12/24 h). */
    private fun heureJolie(h: String): String {
        val m = Regex("^(\\d{1,2}):(\\d\\d)").find(h) ?: return h
        val c = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, m.groupValues[1].toInt()); set(Calendar.MINUTE, m.groupValues[2].toInt()) }
        return Tr.heure(c.timeInMillis)
    }
    /** Une matière en minuscules au milieu d'une phrase ; l'allemand garde la majuscule de ses noms. */
    private fun minuscules(s: String): String = if (Locale.getDefault().language == "de") s else s.lowercase(Locale.getDefault())

    /** La barre « pluie dans l'heure » : douze segments, plus hauts quand il pleut. */
    private fun lignePluie(tailleTexte: Float, hauteurMax: Float): LinearLayout {
        val l = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END; setPadding(0, px(6f), 0, 0) }
        if (tailleTexte > 0) l.addView(texte(getString(R.string.lanceur_pluie_heure), tailleTexte, theme.encre2).apply { tag = "pluie_texte"; setPadding(0, 0, px(14f), 0) })
        val barres = LinearLayout(this).apply { gravity = Gravity.BOTTOM; tag = "pluie_barres" }
        repeat(12) { barres.addView(View(this).apply { background = GradientDrawable().apply { cornerRadius = px(4f).toFloat(); setColor(theme.pourpre) }; alpha = 0.35f }, LinearLayout.LayoutParams(px(if (tele) 12f else 7f), px(hauteurMax * 0.25f)).apply { setMargins(px(2.5f), 0, px(2.5f), 0) }) }
        l.addView(barres, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(hauteurMax)))
        return l
    }

    // ------------------------------------------------------------------ données

    private fun demarrerDonnees() {
        fil?.interrupt()
        fil = Thread {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    val r = Net.post(this, cfg, "/api/pc_parental/veille", JSONObject().put("id", cfg.id).put("secret", cfg.secret).put("ecran", if (tele) "tele" else "telephone").put("locaux", Local.locaux(this)), 20_000)
                    Local.amorcer(this, r.optJSONObject("reglages"))
                    Accueil.noterDisponibles(this, r)
                    donnees = r
                    val p = r.optJSONObject("moi")?.optString("public").orEmpty()
                    val profilChange = p.isNotEmpty() && p != profilConnu
                    if (profilChange) { profilConnu = p; getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("profil", p).apply() }
                    main.post { if (profilChange && !tele) demanderReconstruction() else appliquerDonnees() }
                } catch (e: Exception) { android.util.Log.w("Vision", "Accueil : relevé /veille raté (${e.javaClass.simpleName})") }
                try { Thread.sleep(60_000) } catch (_: InterruptedException) { break }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    /** 1.82.1 : compteurs pour le test des glitchs (accueil rebâti en entier, widgets remplacés). */
    @Volatile var constructions = 0
    @Volatile var remplacementsWidgets = 0

    /** Toutes les vues d'un tag (findViewWithTag n'en rend que la première). */
    private fun vuesParTag(v: View, tag: String, out: MutableList<View> = ArrayList()): List<View> {
        if (v.tag == tag) out += v
        if (v is ViewGroup) for (i in 0 until v.childCount) vuesParTag(v.getChildAt(i), tag, out)
        return out
    }

    private fun appliquerDonnees() {
        if (empreinteApps != empreinte() && enDrag == null && enDragNouveau == null && enDragAppli == null && !tiroirOuvert) { construire(); return }
        val d = donnees ?: return
        val m = d.optJSONObject("meteo")
        // Lanceur (l'auteur, 14 octobre) : la météo peut être dans les deux zones de la télé « Vertical » : toutes les vues.
        val temp = if (m == null || m.isNull("temperature")) "" else String.format(Locale.getDefault(), "%.0f°", m.optDouble("temperature"))
        vuesParTag(racine, "temp").forEach { (it as? TextView)?.text = temp }
        val cond = ArrayList<String>()
        m?.optString("etat")?.takeIf { it.isNotEmpty() }?.let { cond.add(Meteo.libelle(it)) }
        // L'icône suit l'état réel (avant : toujours la pluie).
        vuesParTag(racine, "icone_meteo").forEach { (it as? IconeMeteo)?.etat = m?.optString("etat").orEmpty() }
        val vig = d.optJSONObject("vigilance")
        if (vig != null && vig.optInt("rang", 0) >= 2) cond.add(getString(R.string.lanceur_vigilance, vig.optString("niveau")))
        vuesParTag(racine, "cond").forEach { (it as? TextView)?.text = cond.joinToString(" · ") }
        racine.findViewWithTag<TextView>("salut")?.let { val s = salutation(); it.text = s; it.contentDescription = s }
        // La pluie dans l'heure.
        val pl = d.optJSONObject("pluie")
        val barres = racine.findViewWithTag<LinearLayout>("pluie_barres")
        if (barres != null) {
            val niveaux = IntArray(12)
            pl?.optJSONArray("points")?.let { pts -> for (i in 0 until pts.length()) { val p = pts.getJSONObject(i); val k = (p.optInt("min") / 5).coerceIn(0, 11); niveaux[k] = maxOf(niveaux[k], p.optInt("niveau")) } }
            for (i in 0 until barres.childCount) {
                val v = barres.getChildAt(i); val lp = v.layoutParams
                val n = niveaux[i]
                v.alpha = if (n <= 0) 0.3f else 0.55f + 0.15f * n
                lp.height = ((barres.height.takeIf { it > 0 } ?: px(28f)) * (if (n <= 0) 0.25f else 0.45f + 0.18f * n)).toInt().coerceAtLeast(px(4f))
                v.layoutParams = lp
            }
            racine.findViewWithTag<TextView>("pluie_texte")?.text = when {
                pl == null -> getString(R.string.lanceur_pluie_pas_donnees)
                !pl.isNull("dans") && pl.optInt("dans") <= 0 -> getString(R.string.lanceur_il_pleut)
                !pl.isNull("dans") -> getString(R.string.lanceur_pluie_dans, pl.optInt("dans"))
                pl.optBoolean("pluie") -> getString(R.string.lanceur_pluie_possible)
                else -> getString(R.string.lanceur_pas_de_pluie)
            }
        }
        val dem = d.optJSONArray("demandes")
        racine.findViewWithTag<View>("demandes")?.let { v ->
            val n = if (Modules.actif(this, Modules.DEMANDES)) Demandes.total(d) else 0
            v.visibility = if (n > 0) View.VISIBLE else View.GONE
            // 16-10 : un seul appareil à valider et aucune demande une à une (liste vide) : le compte, pas la première
            // demande (avant : dem!!.getJSONObject(0) sur une liste vide arrêtait l'accueil).
            val premiere = dem?.optJSONObject(0)
            racine.findViewWithTag<TextView>("demandes_texte")?.text = if (n == 1 && premiere != null) getString(R.string.lanceur_demande_quoi, premiere.optString("prenom"), Demandes.quoi(premiere)) else resources.getQuantityString(R.plurals.lanceur_demandes_attente, n, n)
        }
        // Sur le téléphone, les widgets portent des données : on les rafraîchit sans toucher au reste.
        // 1.80.1 : sur TOUTES les pages (avant : la première seulement ; un widget « Demandes » posé en page 2
        // restait sur « En cours… » après la réponse — 23h43).
        if (!tele && cadreRedim == null) for (g in grilles()) {
            for (i in 0 until g.childCount) {
                val v = g.getChildAt(i); val p = v.tag as? Accueil.Place ?: continue
                // Les widgets Android se tiennent à jour eux-mêmes, ceux de Home Assistant par le flux.
                if (p.estWidget && p.code != "horloge" && !WidgetsAndroid.estAndroid(p.code) && !WidgetsHa.estHa(p.code) && enDrag == null) {
                    val n = vueWidget(p); n.tag = p
                    val ancienneImage = v.findViewWithTag<ImageView>("cam_img")
                    // 1.82.1 (glitchs) : même contenu → on garde la vue à l'écran (aucun clignotement) ; la caméra
                    // rafraîchit son image sur place, l'ancienne restant affichée jusqu'à l'arrivée de la nouvelle.
                    if (Ui.empreinteVue(n) == Ui.empreinteVue(v)) {
                        if (ancienneImage != null) donnees?.optJSONArray("cameras")?.optJSONObject(0)?.optString("id")?.takeIf { it.isNotEmpty() }?.let { chargerImageCamera(it, ancienneImage) }
                        continue
                    }
                    ancienneImage?.drawable?.let { d -> n.findViewWithTag<ImageView>("cam_img")?.setImageDrawable(d) }
                    remplacementsWidgets++
                    android.util.Log.d("Vision", "widget ${p.code} redessiné (contenu changé)")
                    g.removeViewAt(i); g.addView(n, i)
                }
            }
        }
        // 16-10 (parité) : sur la télé, les widgets de Vision (rangée des widgets, zones) suivent les données de même,
        // sans clignoter : remplacés seulement si leur contenu a changé.
        if (tele) for (v in vuesParPrefixe(racine, "widget_tele:")) {
            val hote = v as? ViewGroup ?: continue
            val code = (v.tag as String).removePrefix("widget_tele:")
            val ancien = hote.getChildAt(0)
            val n = contenuWidget(code)
            if (ancien != null && Ui.empreinteVue(n) == Ui.empreinteVue(ancien)) continue
            remplacementsWidgets++
            hote.removeAllViews(); hote.addView(n)
        }
    }

    /** Les vues dont le tag commence par [prefixe]. */
    private fun vuesParPrefixe(v: View, prefixe: String, out: MutableList<View> = ArrayList()): List<View> {
        if ((v.tag as? String)?.startsWith(prefixe) == true) out += v
        if (v is ViewGroup) for (i in 0 until v.childCount) vuesParPrefixe(v.getChildAt(i), prefixe, out)
        return out
    }

    // ================================================================== DISPOSITIONS (l'auteur, 13 octobre)
    //
    // Téléphone : « Liste alphabétique » (en plus de Grille et Pages). Télé : « Vertical » (en plus des Rangées).
    // Réglages : DispositionAccueil (préférences « lanceur ») ; la zone du haut et ses widgets restent ceux de ZoneHaut.

    /** La grille ne garde que ses lignes occupées (zone du haut posée en dessous, mode Grille). */
    private var grilleCourte = false
    private var indexAlpha: IndexAlpha? = null

    /**
     * Téléphone, mode « Liste alphabétique » (inspiré de Niagara Launcher) : toutes les applis autorisées en une liste
     * qui défile, par lettre ; l'index sur le côté (droite par défaut, gauche pour les gauchers) ; en glissant dessus,
     * la liste saute aux applis de la lettre visée et une bulle la montre en grand. Les favoris (les applis du dock),
     * en option, sont épinglés en haut. La zone du haut garde sa place (au-dessus ou en dessous) ; vide, rien.
     */
    private fun construireAlpha() {
        val profil = profil()
        grille = null; pageur = null; points = null; zoneRetrait = null
        val gaucher = DispositionAccueil.coteIndex(this) == DispositionAccueil.INDEX_GAUCHE
        val largeurIndex = px(36f)
        val bordIndex = px(8f)  // recette : un contrôle reste à 8 dp au moins du bord de l'écran
        val haut = px(40f)
        val colonne = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; clipToPadding = false
            setPadding(insetG + if (gaucher) bordIndex + largeurIndex + px(6f) else px(14f), haut, insetD + if (gaucher) px(14f) else bordIndex + largeurIndex + px(6f), px(28f))
        }
        val defile = ScrollView(this).apply { isVerticalScrollBarEnabled = false; isFillViewport = true; clipToPadding = false; tag = "liste_alpha"; addView(colonne) }
        defileAccueil = defile
        racine.addView(defile, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        bandeauNonRelie(colonne, 15f)
        val dessous = DispositionAccueil.positionZone(this) == DispositionAccueil.ZONE_DESSOUS
        val entete = if (ZoneHaut.widgets(this).isNotEmpty()) enteteTelephone(profil, paysage = false) else null
        if (entete != null && !dessous) colonne.addView(entete)
        val fondPochette = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0.32f; visibility = View.GONE; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        racine.addView(fondPochette, 1, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (ZoneHaut.LECTEUR in ZoneHaut.widgets(this)) colonne.findViewWithTag<MiniLecteur>("mini_lecteur")?.fond = fondPochette
        else colonne.addView(MiniLecteur(this, theme, false, fondPochette).apply { tag = "mini_lecteur" },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(8f) })

        // Toutes les applis autorisées (celles de l'accueil : sans les applis masquées).
        val masquees = Accueil.masquees(this)
        val infos = Accueil.applicationsInstallees(this).associateBy { it.activityInfo.packageName }
        val applis = infos.keys.filter { it !in masquees }
        val pm = packageManager
        fun nom(p: String) = nomsTiroir[p] ?: (Accueil.etiquette(this, p).also { nomsTiroir[p] = it })

        // Les favoris : les applis du dock, épinglées en haut (option, activée par défaut).
        val favoris = if (DispositionAccueil.favorisEnHaut(this)) Accueil.dock(this).filter { it in applis } else emptyList()
        if (favoris.isNotEmpty()) {
            colonne.addView(etiquetteSection(getString(R.string.lanceur_favoris), 11f).apply { setPadding(px(6f), px(14f), 0, px(6f)) })
            val rangee = LinearLayout(this).apply { gravity = Gravity.TOP; tag = "alpha_favoris" }
            favoris.forEach { p -> rangee.addView(caseFavori(p, nom(p)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
            // Moins de quatre favoris : des places vides gardent leur largeur (les noms ne s'étalent pas).
            repeat((4 - favoris.size).coerceAtLeast(0)) { rangee.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
            colonne.addView(rangee)
            colonne.addView(View(this).apply { setBackgroundColor(theme.carteBord) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(1f)).apply { topMargin = px(10f) })
        }

        val entrees = ListeAlpha.trier(applis.map { it to nom(it) }, Tr.locale())
        val debuts = HashMap<Char, View>()
        val icones = HashMap<String, ImageView>()
        var derniere: Char? = null
        for (e in entrees) {
            if (e.lettre != derniere) {
                val t = texte(e.lettre.toString(), 13f, theme.or, Polices.gras(this)).apply {
                    tag = "alpha_lettre_${e.lettre}"; setPadding(px(6f), px(14f), 0, px(4f))
                    if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
                }
                colonne.addView(t)
                debuts[e.lettre] = t; derniere = e.lettre
            }
            colonne.addView(ligneAlpha(e.cle, e.nom, icones))
        }
        if (entrees.isEmpty()) colonne.addView(texte(getString(R.string.lanceur_accueil_vide), 14f, theme.encre2).apply { setPadding(px(6f), px(18f), px(6f), 0) })
        if (entete != null && dessous) colonne.addView(entete, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(18f) })
        // L'appui long ailleurs que sur une appli : le panneau (réglages, disposition, widgets), comme sur la grille.
        colonne.isLongClickable = true
        colonne.setOnLongClickListener { montrerPanneau(); true }

        // Les icônes : celles déjà connues (tiroir) tout de suite, les autres chargées hors du fil principal.
        val manquantes = icones.keys.filter { !iconesTiroir.containsKey(it) }
        icones.forEach { (p, v) -> iconesTiroir[p]?.let { v.setImageDrawable(it) } }
        if (manquantes.isNotEmpty()) Thread {
            manquantes.forEach { p -> try { Accueil.icone(this, p)?.let { iconesTiroir[p] = it } } catch (_: Exception) {} }
            main.post { if (!isFinishing) icones.forEach { (p, v) -> if (v.drawable == null) iconesTiroir[p]?.let { v.setImageDrawable(it) } } }
        }.also { it.isDaemon = true }.start()

        // La bulle de la lettre visée, à côté du doigt.
        val bulle = texte("", 30f, theme.fond, Polices.gras(this)).apply {
            gravity = Gravity.CENTER; visibility = View.GONE; tag = "alpha_bulle"; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(theme.or) }; elevation = px(6f).toFloat()
        }
        val presentes = ListeAlpha.presentes(entrees)
        val index = IndexAlpha(this, theme, gaucher, surLettre = { l, y ->
            ListeAlpha.cible(l, presentes)?.let { c -> debuts[c]?.let { v -> defile.scrollTo(0, v.top) } }
            bulle.text = l.toString(); bulle.alpha = 1f; bulle.visibility = View.VISIBLE
            bulle.translationY = y - px(32f)
        }, surFin = { bulle.animate().alpha(0f).setDuration(180).withEndAction { bulle.visibility = View.GONE }.start() }).apply {
            this.presentes = presentes; tag = "index_alpha"
        }
        indexAlpha = index
        val cote = if (gaucher) Gravity.START else Gravity.END
        racine.addView(index, FrameLayout.LayoutParams(largeurIndex, ViewGroup.LayoutParams.MATCH_PARENT, cote).apply {
            // 16-10 (recette 360 dp, caméra « poinçon » en haut à gauche) : l'index commence sous l'encoche, pas dessous.
            topMargin = maxOf(haut, insetH) + px(8f); bottomMargin = px(28f); if (gaucher) leftMargin = insetG + bordIndex else rightMargin = insetD + bordIndex
        })
        racine.addView(bulle, FrameLayout.LayoutParams(px(64f), px(64f), Gravity.TOP or cote).apply {
            if (gaucher) leftMargin = insetG + bordIndex + largeurIndex + px(18f) else rightMargin = insetD + bordIndex + largeurIndex + px(18f)
        })
    }

    /** Une ligne de la liste alphabétique : l'icône, le nom ; une appli fermée par les règles reste grisée. */
    private fun ligneAlpha(pkg: String, nom: String, icones: MutableMap<String, ImageView>): View {
        val l = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; minimumHeight = px(Ui.CIBLE_MIN + 4f); setPadding(px(6f), px(4f), px(6f), px(4f))
            isClickable = true; isFocusable = true; contentDescription = nom; tag = "alpha:$pkg"
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Palette.melanger(theme.fond, theme.or, 0.25f)), null, GradientDrawable().apply { cornerRadius = px(12f).toFloat(); setColor(0xFFFFFFFF.toInt()) })
        }
        val cadre = disque(null, 40f)
        (cadre.getChildAt(0) as? ImageView)?.let { icones[pkg] = it }
        l.addView(cadre, LinearLayout.LayoutParams(px(40f), px(40f)))
        l.addView(texte(nom, 17f, theme.encre, Polices.moyen(this)).apply { setPadding(px(14f), 0, 0, 0); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        griser(l, pkg)
        l.setOnClickListener { ouvrir(pkg) }
        l.setOnLongClickListener { menuAlpha(pkg, nom); true }
        return l
    }

    /** Un favori épinglé en haut de la liste : l'icône et le nom, comme sur la grille. */
    private fun caseFavori(pkg: String, nom: String): View {
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(px(2f), px(4f), px(2f), px(4f)); isClickable = true; isFocusable = true; contentDescription = nom; tag = "favori:$pkg" }
        c.addView(disque(iconesTiroir[pkg] ?: Accueil.icone(this, pkg), 52f), LinearLayout.LayoutParams(px(52f), px(52f)))
        // 16-10 (recette 360 dp, 130 %) : comme les cases de la grille, le nom ne se coupe jamais au milieu d'un mot (« Téléphon / e »).
        c.addView(texte(nom, 12f, theme.encre2, Polices.moyen(this)).apply { gravity = Gravity.CENTER; setPadding(0, px(6f), 0, 0); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO; Ui.motsEntiers(this, 12f, 8f) })
        griser(c, pkg)
        c.setOnClickListener { ouvrir(pkg) }
        c.setOnLongClickListener { menuAlpha(pkg, nom); true }
        return c
    }

    /** L'appui long sur une appli de la liste : l'ouvrir, l'épingler en favori (ou l'en retirer), voir ses infos. */
    private fun menuAlpha(pkg: String, nom: String) {
        val dock = Accueil.dock(this).toMutableList()
        val favori = pkg in dock
        val choix = ArrayList<Pair<String, () -> Unit>>()
        choix.add(getString(R.string.lanceur_ouvrir) to { ouvrir(pkg) })
        if (favori) choix.add(getString(R.string.lanceur_retirer_favori) to { dock.remove(pkg); Accueil.ecrireDock(this, dock); construire() })
        else if (dock.size < Accueil.DOCK_MAX) choix.add(getString(R.string.lanceur_epingler_favori) to { dock.add(pkg); Accueil.ecrireDock(this, dock); construire() })
        choix.add(getString(R.string.lanceur_infos_appli) to {
            try { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$pkg"))) } catch (_: Exception) {}
        })
        Ui.dialogue(this).setTitle(nom).setItems(choix.map { it.first }.toTypedArray()) { _, k -> choix[k].second() }.show()
    }

    // ------------------------------------------------------------------ télé, disposition « Vertical »

    private var derniereLigneTele: View? = null
    private val dernieresCartes = HashMap<String, View>()
    private var zonesTele: Map<String, LinearLayout> = emptyMap()
    private fun teleVerticale() = tele && DispositionAccueil.disposTele(this) == DispositionAccueil.TELE_VERTICAL

    /**
     * Télé, disposition « Vertical » : les applis en colonne (à gauche, au centre ou à droite) ; les espaces de part et
     * d'autre sont des zones de widgets (ZoneHaut : horloge stylisée, météo, remerciement, message), qui n'existent
     * que si le Décideur y a mis des widgets ET les a activées. À la télécommande : haut et bas dans la colonne ;
     * gauche ou droite passe dans la zone voisine (sur la carte quittée en dernier), et revient sur l'appli quittée.
     */
    private fun construireTeleVertical() {
        Accueil.initialiserTele(this)
        // 17-10 (l'auteur, points 10, 21) : 5 % sur les côtés (4,5 % : les rangées de widgets et la colonne sortaient de la zone sûre).
        val margeX = ZoneSure.x(this@LanceurActivity, 0.05f); val margeY = ZoneSure.y(this@LanceurActivity, 0.05f)
        val pos = DispositionAccueil.colonne(this)
        // 16-10 (retour d'essai) : une rangée de widgets en haut et / ou en bas, sur toute la largeur, en plus des côtés.
        val cadreTele = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(margeX, margeY, margeX, margeY); clipChildren = false; clipToPadding = false; tag = "tele_vertical" }
        racine.addView(cadreTele, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val ligne = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false; tag = "tele_ligne" }

        // La colonne : le bandeau « non reliée », les demandes et les messages, puis les applis, la roue.
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; tag = "colonne_tele" }
        bandeauNonRelie(col, tv(26f))
        val demandes = LinearLayout(this).apply { tag = "demandes"; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE; background = fondCarte(999f); setPadding(px(14f), px(6f), px(16f), px(6f)) }
        demandes.addView(pastille(theme.pourpre), LinearLayout.LayoutParams(px(8f), px(8f)).apply { rightMargin = px(10f) })
        demandes.addView(texte("", tv(20f), theme.encre, Polices.demiGras(this)).apply { tag = "demandes_texte" })
        col.addView(demandes, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(8f) })
        val fondPochette = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0.32f; visibility = View.GONE; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        racine.addView(fondPochette, 1, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // 1.93.05 (l'auteur, retour 34) : plus de mini-lecteur posé d'office ; le widget « Lecteur musique », s'il est dans une zone.
        fondPochetteAccueil = fondPochette
        val messages = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, px(6f), 0, px(4f)); clipChildren = false; clipToPadding = false }
        rangeeMessages = messages
        col.addView(messages)
        remplirMessagesTele()
        val liste = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; setPadding(px(16f), px(10f), px(16f), px(10f)); tag = "colonne_applis" }
        rangeeApps = liste
        // 16-10 (recette, rangée du bas) : la liste défile DANS son cadre (avant : clipChildren = false, elle se dessinait
        // sous la rangée de widgets du bas) ; le zoom au focus tient dans la marge de la liste.
        val defile = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false; clipChildren = true; clipToPadding = true; addView(liste)
            // Le cadre de la colonne (clipChildren de son parent est faux, pour l'anneau du mini-lecteur) : on coupe au contour.
            outlineProvider = android.view.ViewOutlineProvider.BOUNDS; clipToOutline = true
        }
        col.addView(defile, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val pied = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END; setPadding(0, px(8f), 0, 0) }
        pied.addView(texte(if (deplacement != null) getString(R.string.lanceur_aide_deplacement_vertical) else "", tv(18f), theme.encre2).apply { tag = "aide" }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val roue = maxOf(tv(84f), Ui.CIBLE_MIN)
        pied.addView(roueReglages(roue), LinearLayout.LayoutParams(px(roue), px(roue)))
        col.addView(pied)

        // Les zones de widgets possibles, selon la place de la colonne ; une zone vide ou non activée ne montre rien.
        val zones = HashMap<String, LinearLayout>()
        var dateLiee = false
        fun zone(z: String): View {
            val w = DispositionAccueil.widgetsZone(this, z)
            // 1.93.02 (télé du salon de l'auteur, 17-10) : une rangée du haut ou du bas vide doit être GONE. Une simple View en
            // WRAP_CONTENT prend toute la hauteur offerte : la ligne (colonne des applis et zones de côté) tombait à 0 px
            // et l'accueil ne montrait que le fond. Une zone de côté vide garde sa place (poids 1) pour centrer la colonne.
            if (!DispositionAccueil.zoneVisible(w, DispositionAccueil.zoneActivee(this, z)))
                return View(this).apply { tag = "zone_tele_$z"; if (z in DispositionAccueil.RANGEES) visibility = View.GONE }
            val rangee = z in DispositionAccueil.RANGEES
            val conteneur = LinearLayout(this).apply {
                orientation = if (rangee) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL; gravity = Gravity.CENTER
                clipChildren = false; clipToPadding = false; tag = "zone_tele_$z"; setPadding(px(18f), px(10f), px(18f), px(10f))
            }
            w.forEach { code ->
                // 16-10 (recette télé, 130 %) : dans une rangée du haut ou du bas, l'horloge et le texte sont plus petits
                // (avant : la date de l'horloge du bas sortait de l'écran).
                val v = widgetZoneTele(z, code, tailleTexte = tv(if (rangee) 26f else 40f), coteOeil = px(tv(if (rangee) 64f else 110f)), lierDate = code == ZoneHaut.HORLOGE && !dateLiee) ?: return@forEach
                if (code == ZoneHaut.HORLOGE) dateLiee = true
                conteneur.addView(carteZoneTele(z, code, v),
                    if (rangee) LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = px(10f); rightMargin = px(10f) }
                    else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(10f); bottomMargin = px(10f) })
            }
            zones[z] = conteneur
            if (rangee) return conteneur
            // 16-10 (recette télé) : une zone de côté trop pleine pour sa hauteur défile (le focus de la télécommande
            // l'emmène) au lieu d'écraser sa dernière carte ; moins pleine, elle reste centrée.
            return ScrollView(this).apply { isVerticalScrollBarEnabled = false; isFillViewport = true; clipChildren = true; addView(conteneur, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        }
        val haut = zone(DispositionAccueil.ZONE_H)
        val largeurCol = (dm.widthPixels * if (pos == DispositionAccueil.COLONNE_CENTRE) 0.30f else 0.32f).toInt()
        val lpZone = { LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f) }
        when (pos) {
            DispositionAccueil.COLONNE_GAUCHE -> { ligne.addView(col, LinearLayout.LayoutParams(largeurCol, ViewGroup.LayoutParams.MATCH_PARENT)); ligne.addView(zone(DispositionAccueil.ZONE_D), lpZone()) }
            DispositionAccueil.COLONNE_DROITE -> { ligne.addView(zone(DispositionAccueil.ZONE_G), lpZone()); ligne.addView(col, LinearLayout.LayoutParams(largeurCol, ViewGroup.LayoutParams.MATCH_PARENT)) }
            else -> { ligne.addView(zone(DispositionAccueil.ZONE_G), lpZone()); ligne.addView(col, LinearLayout.LayoutParams(largeurCol, ViewGroup.LayoutParams.MATCH_PARENT)); ligne.addView(zone(DispositionAccueil.ZONE_D), lpZone()) }
        }
        val bas = zone(DispositionAccueil.ZONE_B)
        val lpRangee = { LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
        cadreTele.addView(haut, lpRangee())
        cadreTele.addView(ligne, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        cadreTele.addView(bas, lpRangee())
        zonesTele = zones
        dernieresCartes.clear()
        remplirColonneTele()
    }

    /** Les applis de la colonne (et « Ajouter » au bout). */
    private fun remplirColonneTele() {
        val liste = rangeeApps ?: return
        liste.removeAllViews()
        val cases = Accueil.cases(this)
        val cles = cases.map { it.cle }
        cases.forEach { c -> liste.addView(ligneTele(c, cles), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = px(8f) }) }
        val plus = ligneTele(null, cles)
        liste.addView(plus, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (cases.isEmpty()) {
            liste.addView(texte(getString(R.string.lanceur_accueil_vide_tele), tv(22f), theme.encre2, Polices.moyen(this)).apply { setPadding(px(8f), px(10f), px(8f), 0) })
            plus.post { if (currentFocus == null || currentFocus?.tag == "plus") plus.requestFocus() }
        }
    }

    /**
     * Une ligne de la colonne : le disque et le nom, sur une carte qui s'éclaire et grossit un peu au focus (anneau
     * d'or). c = null : « Ajouter » (choisir d'autres applications, ou un widget Home Assistant). Mêmes gestes que les
     * cases des rangées : OK ouvre, appui long ou Menu ouvre le menu, déplacement en haut et en bas.
     */
    private fun ligneTele(c: Accueil.Case?, cles: List<String>): View {
        val taille = tv(64f)
        val nom = when { c == null -> getString(R.string.exceptions_ajouter); c.dossier != null -> c.dossier.nom; else -> Accueil.etiquette(this, c.pkg!!) }
        val enDeplacement = c != null && deplacement == c.cle
        val l = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(px(12f), px(8f), px(14f), px(8f)); tag = c?.cle ?: "plus"
            isFocusable = true; isClickable = true; contentDescription = nom; minimumHeight = px(Ui.CIBLE_MIN)
            background = fondLigneTele(focus = false, enDeplacement = enDeplacement)
        }
        val icone: View = when {
            c == null -> FrameLayout(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0); setStroke(px(1.5f), theme.encre2, px(8f).toFloat(), px(6f).toFloat()) }
                addView(icone(R.drawable.ic_plus, taille * 0.4f, theme.encre2), FrameLayout.LayoutParams(px(taille * 0.4f), px(taille * 0.4f), Gravity.CENTER))
            }
            c.dossier != null -> disqueDossier(c.dossier, taille)
            else -> disque(Accueil.icone(this, c.pkg!!), taille)
        }
        l.addView(icone, LinearLayout.LayoutParams(px(taille), px(taille)))
        val libelle = texte(nom, tv(28f), theme.encre2, Polices.moyen(this)).apply { setPadding(px(14f), 0, 0, 0); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO; Ui.motsEntiers(this, tv(28f), tv(28f) * 0.6f) }
        l.addView(libelle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (c?.pkg != null) griser(l, c.pkg)
        l.setOnFocusChangeListener { v, a ->
            if (a) derniereLigneTele = v
            // 17-10 (l'auteur, point 11) : l'appli prise ne reste plus collée au bord coupé de la liste (elle paraissait rognée,
            // et l'overscan la mangeait) : une marge autour d'elle, comme pour les autres commandes (Ui.garderVisible).
            if (a) v.post { Ui.garderVisible(v, px(14f)) }
            if (deplacement != null) return@setOnFocusChangeListener
            v.background = fondLigneTele(focus = a, enDeplacement = false)
            libelle.setTextColor(if (a) theme.encre else theme.encre2); libelle.typeface = if (a) Polices.gras(this) else Polices.moyen(this)
            v.animate().scaleX(if (a) 1.04f else 1f).scaleY(if (a) 1.04f else 1f).setDuration(140).start()
        }
        l.setOnClickListener {
            when {
                deplacement != null -> { deplacement = null; construire() }
                c == null -> ajouterTele()
                c.dossier != null -> ouvrirDossier(c.dossier, cles)
                else -> ouvrir(c.pkg!!)
            }
        }
        if (c != null) l.setOnLongClickListener { if (deplacement == null) menuTele(c, cles); true }
        l.setOnKeyListener { _, code, ev ->
            // La touche Menu ouvre le même menu que l'appui long ; gauche et droite passent dans la zone voisine.
            if (c != null && code == KeyEvent.KEYCODE_MENU) {
                if (ev.action == KeyEvent.ACTION_UP && deplacement == null) menuTele(c, cles)
                return@setOnKeyListener true
            }
            if (ev.action != KeyEvent.ACTION_DOWN || deplacement != null) return@setOnKeyListener false
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> versZone(DispositionAccueil.ZONE_G)
                KeyEvent.KEYCODE_DPAD_RIGHT -> versZone(DispositionAccueil.ZONE_D)
                else -> false
            }
        }
        return l
    }

    /**
     * 16-10 (l'auteur, télé du salon : « juste le logo et le nom, sans fond ») : le fond d'une ligne de la colonne selon
     * « Fond des applis » ; sans fond, rien au repos, l'anneau d'or au focus (on voit toujours où l'on est).
     */
    private fun fondLigneTele(focus: Boolean, enDeplacement: Boolean): android.graphics.drawable.Drawable? = when {
        enDeplacement -> fondCarte(18f, couleur = if (DispositionAccueil.fondApplis(this)) theme.carte else 0, bord = theme.pourpre, epaisseurDp = 3f)
        DispositionAccueil.fondApplis(this) -> fondCarte(18f, bord = if (focus) theme.or else theme.carteBord, epaisseurDp = if (focus) 3f else 1f)
        focus -> fondCarte(18f, couleur = 0, bord = theme.or, epaisseurDp = 3f)
        else -> null
    }

    /** Le cadre d'un widget de zone selon « Cadre des widgets » : sans cadre, rien au repos, l'anneau d'or au focus. */
    private fun fondCarteZone(focus: Boolean): android.graphics.drawable.Drawable? = when {
        DispositionAccueil.cadreWidgets(this) -> fondCarte(24f, bord = if (focus) theme.or else theme.carteBord, epaisseurDp = if (focus) 3f else 1f)
        focus -> fondCarte(24f, couleur = 0, bord = theme.or, epaisseurDp = 3f)
        else -> null
    }

    private fun estDans(v: View, groupe: ViewGroup): Boolean { var p = v.parent; while (p != null) { if (p === groupe) return true; p = p.parent }; return false }

    private fun premierFocusable(v: View): View? {
        if (v.isFocusable && v.visibility == View.VISIBLE) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) premierFocusable(v.getChildAt(i))?.let { return it }
        return null
    }

    /** La télécommande quitte la colonne pour une zone de widgets : sur la carte quittée en dernier, sinon la première. */
    private fun versZone(z: String): Boolean {
        val zone = zonesTele[z] ?: return false
        val cible = dernieresCartes[z]?.takeIf { it.isAttachedToWindow && estDans(it, zone) } ?: premierFocusable(zone)
        return cible?.requestFocus() ?: false
    }

    /**
     * Une carte de widget dans une zone de la télé : focalisable (anneau d'or) ; vers la colonne, la télécommande
     * revient sur l'appli quittée ; OK ouvre l'éditeur de la zone (code des Décideurs).
     */
    private fun carteZoneTele(z: String, code: String, contenu: View): View {
        // 16-10 : un widget qui a son propre focus (Home Assistant, widget de Vision du téléphone) le garde : la carte ne
        // le prend pas (sinon deux focus l'un dans l'autre) ; sa taille est celle qu'il demande.
        val propre = contenu.isFocusable
        val carte = FrameLayout(this).apply {
            isFocusable = !propre; isClickable = !propre; tag = "carte_zone_${z}_$code"; contentDescription = ZoneHaut.nom(this@LanceurActivity, code)
            background = fondCarteZone(focus = false); setPadding(px(18f), px(16f), px(18f), px(16f)); clipChildren = false
        }
        val lpC = contenu.layoutParams
        carte.addView(contenu, FrameLayout.LayoutParams(lpC?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT, lpC?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        if (propre) {
            contenu.onFocusChangeListener.let { avant ->
                contenu.setOnFocusChangeListener { v, a -> avant?.onFocusChange(v, a); if (a) dernieresCartes[z] = v; carte.background = fondCarteZone(focus = a) }
            }
            return carte
        }
        carte.setOnFocusChangeListener { v, a ->
            if (a) dernieresCartes[z] = v
            v.background = fondCarteZone(focus = a)
            v.animate().scaleX(if (a) 1.03f else 1f).scaleY(if (a) 1.03f else 1f).setDuration(140).start()
        }
        // La colonne est à gauche de la zone de droite, et à droite de la zone de gauche.
        // Les rangées du haut et du bas : la recherche de focus d'Android (haut / bas) suffit.
        val versColonne = when (z) { DispositionAccueil.ZONE_D -> KeyEvent.KEYCODE_DPAD_LEFT; DispositionAccueil.ZONE_G -> KeyEvent.KEYCODE_DPAD_RIGHT; else -> -1 }
        carte.setOnKeyListener { _, k, ev ->
            if (ev.action == KeyEvent.ACTION_DOWN && k == versColonne) (derniereLigneTele?.takeIf { it.isAttachedToWindow } ?: rangeeApps?.getChildAt(0))?.requestFocus() ?: false
            else false
        }
        carte.setOnClickListener { personnaliserZoneTele(z) }
        return carte
    }

    /** L'éditeur d'une zone de widgets de la télé (code des Décideurs) : les widgets, leur ordre, et l'activation. */
    private fun personnaliserZoneTele(z: String) = DispositionAccueil.personnaliserZone(this, z) { construire() }
}
