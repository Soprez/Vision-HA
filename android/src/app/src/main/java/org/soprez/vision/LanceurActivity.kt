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
 * Télé : la marque au milieu en haut, l'heure, la météo et la pluie, un mot
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
        /** 1.80.0 : appelé par le service quand une demande arrive ; posé seulement pendant que l'accueil est affiché. */
        @Volatile var rafraichirMaintenant: (() -> Unit)? = null
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
        auPremierPlan = true
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
            if (!am.isMusicActive || Musique.enCours || Veille.ouverte || pkg in Local.fond(this)) return
            for (code in intArrayOf(KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP)) {
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            }
            main.postDelayed({
                try {
                    if (!isFinishing && hasWindowFocus() && am.isMusicActive && !Musique.enCours && !Veille.ouverte)
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
        auPremierPlan = false
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
        val min = ReglesVeille.delaiAccueil(Local.delaiVeille(this), EconomiseurTv.estVision(this), Local.delaiRepli(this))
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
                KeyEvent.KEYCODE_DPAD_LEFT -> { glisserTele(d, -1); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { glisserTele(d, +1); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK -> { deplacement = null; construire(); return true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> return true
            }
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) { dossierOuvert?.dismiss(); cacherPanneau(); return true }
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
    private val dm get() = resources.displayMetrics
    private fun empreinte(): String = "g" + Paquets.generation + "|" + cfg.inscrit + "|" + Accueil.masquees(this).sorted().joinToString(",") + "|" + Accueil.ordre(this).joinToString(",") + "|" + theme.nom + theme.fond + "|" + Accueil.dossiers(this).joinToString { it.cle + it.nom + it.pkgs.size } + "|" + profilConnu + "|" + Modules.signature(Modules.actifs(this)) + "|" + WidgetsHaStock.generation + "|" + Etat.verrouilleEffectif() + Etat.apps.hashCode() + Etat.toujours.hashCode() + "|" + (if (tele) "" else Accueil.disposition(this).joinToString { it.cle + it.col + "," + it.row + "," + it.w + "x" + it.h } + "|" + Accueil.dock(this).joinToString(",") + "|" + Accueil.mode(this))

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

    /** La marque, au milieu en haut, partout. */
    private fun marque(tailleLogoDp: Float, tailleTexte: Float): View {
        val m = LinearLayout(this).apply { gravity = Gravity.CENTER }
        m.addView(icone(R.drawable.ic_vision, tailleLogoDp, theme.or))
        m.addView(texte("VISION", tailleTexte, theme.or, Polices.gras(this), 0.28f).apply { setPadding(px(8f), 0, 0, 0) })
        return m
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
        racine.addView(FondAnime(this) { theme }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (tele) construireTele() else { construireTelephone(); poserTiroir() }
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
        val margeX = (dm.widthPixels * 0.05f).toInt(); val margeY = (dm.heightPixels * 0.04f).toInt()
        val colonne = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(margeX, margeY, margeX, margeY) }
        // Recette lot 1 (TV 720p, grande police, bandeau « non reliée ») : quand tout ne tient pas en hauteur, l'accueil
        // défile (le focus de la télécommande l'emmène) au lieu d'écraser la rangée d'applis et la roue sous le bas de l'écran.
        val defileAccueil = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; clipToPadding = false; tag = "defile_accueil" }
        defileAccueil.addView(colonne, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        racine.addView(defileAccueil, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        colonne.addView(marque(tv(40f), tv(22f)), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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
        // 1.86.5 (retour d'essai) : le mini-lecteur quand de la musique joue (et, en option, la pochette floutée en fond).
        val fondPochette = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0.32f; visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        racine.addView(fondPochette, 1, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        colonne.addView(MiniLecteur(this, theme, true, fondPochette).apply { tag = "mini_lecteur" },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(14f) })
        // Les messages de Home Assistant pas encore lus : on les rouvre d'ici.
        val messages = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, px(14f), 0, 0) }
        rangeeMessages = messages
        colonne.addView(messages)
        remplirMessagesTele()

        colonne.addView(View(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Les applications, en rangée (qui défile si elle déborde), et le « + » au bout.
        colonne.addView(etiquetteSection(getString(R.string.lanceur_applications), tv(20f)))
        val rangee = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(px(6f), px(10f), px(6f), px(6f)); clipToPadding = false; clipChildren = false }
        rangeeApps = rangee
        val defile = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false }
        defile.addView(rangee)
        colonne.addView(defile)
        remplirRangeeTele()

        // Les widgets Home Assistant posés sur la télé (lecture seule) ; masqués quand le module « entites » est coupé.
        if (Modules.actif(this, Modules.ENTITES)) {
            val has = Accueil.disposition(this).filter { it.estWidget && WidgetsHa.estHa(it.code) }.sortedWith(compareBy({ it.row }, { it.col }))
                .mapNotNull { pl -> WidgetsHaStock.reglage(this, WidgetsHa.id(pl.code))?.let { pl to it } }
            if (has.isNotEmpty()) {
                colonne.addView(etiquetteSection("Home Assistant", tv(20f)))
                val rangeeW = LinearLayout(this).apply { setPadding(px(6f), px(10f), px(6f), px(6f)); clipToPadding = false; clipChildren = false }
                val defileW = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false }
                defileW.addView(rangeeW)
                has.forEach { (pl, r) ->
                    val lp = LinearLayout.LayoutParams(px(tv(200f)) * pl.w, px(tv(124f)) * pl.h).apply { rightMargin = px(12f) }
                    rangeeW.addView(widgetHaTele(pl, r), lp)
                }
                colonne.addView(defileW)
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
        contenu.setOnClickListener {
            if (!Modules.actif(this, Modules.ENTITES)) { choisirApplis(); return@setOnClickListener }
            Ui.dialogue(this).setTitle(getString(R.string.exceptions_ajouter)).setItems(arrayOf(getString(R.string.lanceur_des_applications), getString(R.string.lanceur_un_widget_ha))) { _, k -> if (k == 0) choisirApplis() else ajouterWidgetHa() }.show()
        }
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
        val colonne = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(16f), px(40f), px(16f), if (pages) 0 else px(12f)); clipChildren = false; clipToPadding = false }
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

        colonne.addView(marque(24f, 13f), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        bandeauNonRelie(colonne, 15f)

        // L'en-tête : heure et date à gauche, météo à droite.
        val entete = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM; setPadding(px(4f), px(6f), px(4f), 0) }
        val blocHeure = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // 1.83.1 : l'heure et la date écrites dès la création (avant : vides jusqu'au tic suivant, d'où le clignotement).
        heure = texte(heureFmt.format(Date()), 64f, theme.encre, Polices.outfitFin(this), -0.02f).apply { includeFontPadding = false }
        val ligneDate = LinearLayout(this)
        date = texte(dateFmt.format(Date()), 14f, theme.encre2, Polices.moyen(this))
        ligneDate.addView(date)
        val prenom = donnees?.optJSONObject("moi")?.optString("prenom").orEmpty()
        val qui = when (profil) { "parent" -> getString(R.string.lanceur_gestion); "enfant" -> prenom; else -> "" }
        if (qui.isNotEmpty()) ligneDate.addView(texte(" · $qui", 14f, theme.or, Polices.gras(this)))
        blocHeure.addView(heure); blocHeure.addView(ligneDate)
        entete.addView(blocHeure, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val droite = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        val ligneMeteo = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        ligneMeteo.addView(IconeMeteo(this, theme.encre2).apply { tag = "icone_meteo"; layoutParams = ViewGroup.LayoutParams(px(24f), px(24f)) })
        ligneMeteo.addView(texte("", 28f, theme.encre, Polices.outfitLeger(this)).apply { tag = "temp"; setPadding(px(8f), 0, 0, 0); includeFontPadding = false })
        droite.addView(ligneMeteo)
        droite.addView(texte("", 12f, theme.encre2).apply { tag = "cond"; gravity = Gravity.END })
        entete.addView(droite)
        colonne.addView(entete)
        // 1.86.5 (retour d'essai) : le mini-lecteur quand de la musique joue (et, en option, la pochette floutée en fond).
        val fondPochette = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0.32f; visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        racine.addView(fondPochette, 1, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        colonne.addView(MiniLecteur(this, theme, false, fondPochette).apply { tag = "mini_lecteur" },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(10f) })

        // La grille, comme Android : tout est posé où on l'a mis. Une longue grille qui défile, ou des pages.
        val lc = ((dm.widthPixels - px(32f)) / Accueil.COLONNES)
        val hc = (lc * 1.14f).toInt()
        // 1.72.0 : la hauteur réelle du haut (marque, heure, date, météo : elle grandit avec la taille du texte),
        // mesurée plutôt qu'estimée ; sinon la dernière rangée d'une page passait sous le dock.
        val largeurColonne = View.MeasureSpec.makeMeasureSpec(dm.widthPixels - px(32f), View.MeasureSpec.EXACTLY)
        val libre = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        var hautMesure = 0
        for (i in 0 until colonne.childCount) { val v = colonne.getChildAt(i); v.measure(largeurColonne, libre); hautMesure += v.measuredHeight }
        // Ce qui reste sous le haut : marge du haut (elle couvre la barre d'état), grille (son retrait), points des pages, et une petite marge.
        val dispo = dm.heightPixels - px(40f) - hautMesure - px(14f) - (if (pages) px(24f) else 0) - hauteurBas - px(8f)
        val lignesVisibles = (dispo / hc).coerceAtLeast(3)
        Accueil.lignesParPage = if (pages) lignesVisibles else 0
        // Les widgets d'un module coupé dans Home Assistant ne s'affichent plus ; ils restent rangés pour la réactivation.
        val actifs = Modules.actifs(this)
        val places = Accueil.synchroniser(this, profil).filter { !it.estWidget || Modules.widgetVisible(actifs, it.code) }
        celluleL = lc; celluleH = hc
        WidgetsAndroid.nettoyer(this)
        // Accueil vide (nouvel appareil) : on dit comment le remplir.
        if (places.isEmpty()) colonne.addView(texte(getString(R.string.lanceur_accueil_vide),
            14f, theme.encre2).apply { setPadding(px(6f), px(18f), px(6f), 0) })
        fun grilleNeuve(page: Int) = GrilleAccueil(this).apply { setPadding(0, px(14f), 0, 0); couleurCible = theme.or; hauteurCellule = hc; lignesMin = lignesVisibles; hauteurFixe = pages; decalageLignes = page * lignesVisibles }
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
            colonne.addView(pg, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            val pts = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, px(4f), 0, 0) }
            points = pts
            pg.surPage = { pageMemo = it; peindrePoints() }
            colonne.addView(pts)
            pg.post { pg.aller(pageMemo.coerceAtMost(n - 1), false); peindrePoints() }
            brancherGestes(pg)
        } else {
            pageur = null; points = null
            val g = grilleNeuve(0)
            grille = g
            places.forEach { pl -> g.addView(vuePlace(pl)) }
            brancherGrille(g, 0)
            colonne.addView(g, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            colonne.addView(View(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
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
                DragEvent.ACTION_DRAG_STARTED -> true
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
        val lc = g.largeurCellule; val cx = g.paddingLeft + (pl.col + 0.5f) * lc; val cy = g.paddingTop + pl.row * g.hauteurCellule + lc * 0.45f
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
        raccourci(R.drawable.ic_reglages, getString(R.string.lanceur_reglages)) { startActivity(Intent(this, ReglagesTvActivity::class.java)) }
        raccourci(R.drawable.ic_grille, if (pages) getString(R.string.lanceur_mode_defilement) else getString(R.string.lanceur_mode_pages)) { Accueil.poserMode(this, if (pages) Accueil.MODE_DEFILEMENT else Accueil.MODE_PAGES); construire() }
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
        val dispo = Accueil.WIDGETS.filter { (it.publics.isEmpty() || profil in it.publics) && Modules.widgetVisible(actifs, it.code) }
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
                else Toast.makeText(this, getString(R.string.lanceur_code_incorrect), Toast.LENGTH_SHORT).show()
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

    private fun ajouterWidgetHa() = autoriserAccueil { ouvrirEditeurHa(null) }

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
        if (catalogueEnCours || vuesHa.isEmpty() || !Modules.actif(this, Modules.ENTITES)) return
        val maintenant = SystemClock.elapsedRealtime()
        if (maintenant - dernierEssaiCatalogue < 60_000L || !WidgetsHaStock.catalogueAPerime(this)) return
        dernierEssaiCatalogue = maintenant; catalogueEnCours = true
        Thread {
            try { WidgetsHaStock.recharger(this); main.post { vuesHa.forEach { it.maj() } } } catch (_: Exception) {}
            catalogueEnCours = false
        }.also { it.isDaemon = true }.start()
    }

    /** Télé : les widgets Home Assistant posés, en lecture seule ; OK ouvre leur menu. */
    private fun widgetHaTele(pl: Accueil.Place, r: WidgetsHa.Reglage): View {
        val cadre = FrameLayout(this).apply { setPadding(px(6f), px(6f), px(6f), px(6f)); isFocusable = true; isClickable = true; tag = pl.cle; clipChildren = false }
        val v = VueWidgetHa(this, theme, r.copy(w = pl.w, h = pl.h), lectureSeule = true, details = { t, m -> detailsHa(t, m) }).also { vuesHa.add(it) }
        cadre.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        cadre.contentDescription = v.contentDescription
        cadre.setOnFocusChangeListener { x, a ->
            x.background = if (a) fondCarte(28f, couleur = 0, bord = theme.or, epaisseurDp = 2f) else null
            x.animate().scaleX(if (a) 1.05f else 1f).scaleY(if (a) 1.05f else 1f).setDuration(140).start()
        }
        val menu = {
            val choix = arrayOf(getString(R.string.lanceur_voir_detail), getString(R.string.lanceur_modifier_points), getString(R.string.lanceur_retirer_accueil))
            Ui.dialogue(this).setTitle(titreHa(r)).setItems(choix) { _, k ->
                when (k) { 0 -> v.performClick(); 1 -> autoriserAccueil { ouvrirEditeurHa(r.id) }; else -> autoriserAccueil { Accueil.retirerDeLaGrille(this, pl.cle); construire() } }
            }.show()
        }
        cadre.setOnClickListener { menu() }
        cadre.setOnLongClickListener { menu(); true }
        return cadre
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
        val ox = (posG[0] - posR[0] + grilleW.paddingLeft).toFloat(); val oy = (posG[1] - posR[1] + grilleW.paddingTop).toFloat()
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
        val champ = EditText(this).apply { setText(d.nom); setSelectAllOnFocus(true) }
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
        val crayon = FrameLayout(this).apply { background = fondCarte(22f, couleur = 0); isClickable = true; isFocusable = true; setOnClickListener { renommer(d) } }
        crayon.addView(icone(R.drawable.ic_crayon, 22f, theme.or), FrameLayout.LayoutParams(px(22f), px(22f), Gravity.CENTER))
        tete.addView(crayon, LinearLayout.LayoutParams(px(44f), px(44f)))
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
        val c: View = when (pl.code) {
            "horloge" -> widgetHorloge()
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
            else -> carte().also { it.addView(texte(pl.code, 13f, theme.encre3)) }
        }
        cadre.addView(c, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        cadre.isClickable = true
        cadre.setOnLongClickListener { v -> soulever(v, c, pl.cle) }
        // Un widget qui réagit au toucher (caméra, demandes…) garde l'appui pour lui : sans ceci, l'appui long
        // n'arrivait jamais au cadre et le widget ne pouvait plus être déplacé ni retiré.
        c.setOnLongClickListener { soulever(cadre, c, pl.cle) }
        cadre.setOnDragListener { v, e -> if (e.action == DragEvent.ACTION_DRAG_ENDED && enDrag == pl.cle) finDrag(v, e) { ouvrirRedim(pl.cle) }; false }
        if (d == null && pl.code != "horloge") c.alpha = 0.6f
        return cadre
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
        val choix = arrayOf(resources.getQuantityString(R.plurals.commun_lot2_minutes, 15, 15), resources.getQuantityString(R.plurals.commun_lot2_minutes, 30, 30), resources.getQuantityString(R.plurals.commun_lot2_heures, 1, 1))
        Ui.dialogue(this).setTitle(getString(R.string.block_demander_du_temps)).setItems(choix) { _, k ->
            cfg.demandeTemps = listOf(15, 30, 60)[k]
            android.widget.Toast.makeText(this, this.getString(R.string.acces_demande_envoyee), android.widget.Toast.LENGTH_LONG).show()
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
        Garde.exiger(this, ParentsActivity.RAISON) {
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
        val demandes = donnees?.optJSONArray("demandes")
        if (demandes == null || demandes.length() == 0) return w(getString(R.string.lanceur_demandes)).also { noteDemandesVue(it); it.addView(texte(getString(R.string.lanceur_aucune_demande), 13f, theme.encre3).apply { setPadding(0, px(6f), 0, 0) }) }
        val dem = demandes.getJSONObject(0)
        val c = w(if (demandes.length() > 1) getString(R.string.lanceur_demande_attente_n, demandes.length()) else getString(R.string.lanceur_demande_attente), bordOr = true)
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
                    donnees = r
                    val p = r.optJSONObject("moi")?.optString("public").orEmpty()
                    val profilChange = p.isNotEmpty() && p != profilConnu
                    if (profilChange) { profilConnu = p; getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("profil", p).apply() }
                    main.post { if (profilChange && !tele) demanderReconstruction() else appliquerDonnees() }
                } catch (_: Exception) {}
                try { Thread.sleep(60_000) } catch (_: InterruptedException) { break }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    /** 1.82.1 : compteurs pour le test des glitchs (accueil rebâti en entier, widgets remplacés). */
    @Volatile var constructions = 0
    @Volatile var remplacementsWidgets = 0

    private fun appliquerDonnees() {
        if (empreinteApps != empreinte() && enDrag == null && enDragNouveau == null && enDragAppli == null && !tiroirOuvert) { construire(); return }
        val d = donnees ?: return
        val m = d.optJSONObject("meteo")
        racine.findViewWithTag<TextView>("temp")?.text = if (m == null || m.isNull("temperature")) "" else String.format(Locale.getDefault(), "%.0f°", m.optDouble("temperature"))
        val cond = ArrayList<String>()
        m?.optString("etat")?.takeIf { it.isNotEmpty() }?.let { cond.add(Meteo.libelle(it)) }
        // L'icône suit l'état réel (avant : toujours la pluie).
        racine.findViewWithTag<IconeMeteo>("icone_meteo")?.etat = m?.optString("etat").orEmpty()
        val vig = d.optJSONObject("vigilance")
        if (vig != null && vig.optInt("rang", 0) >= 2) cond.add(getString(R.string.lanceur_vigilance, vig.optString("niveau")))
        racine.findViewWithTag<TextView>("cond")?.text = cond.joinToString(" · ")
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
            val n = if (Modules.actif(this, Modules.DEMANDES)) dem?.length() ?: 0 else 0
            v.visibility = if (n > 0) View.VISIBLE else View.GONE
            racine.findViewWithTag<TextView>("demandes_texte")?.text = if (n == 1) getString(R.string.lanceur_demande_quoi, dem!!.getJSONObject(0).optString("prenom"), Demandes.quoi(dem.getJSONObject(0))) else resources.getQuantityString(R.plurals.lanceur_demandes_attente, n, n)
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
    }
}
