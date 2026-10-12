// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Les installations, désinstallations et désactivations d'applis, vues aussitôt (rappel du lanceur,
 * diffusions du système et relevé des paquets changés, en place dès le démarrage de Vision) :
 *  - les listes d'applis en mémoire sont oubliées (accueils, applis toujours permises, applis de fond) ;
 *  - une appli supprimée quitte l'accueil enregistré (cases, dossiers, dock) et ses widgets
 *    Android sont libérés ;
 *  - l'accueil et le tiroir ouverts se rebâtissent tout de suite ;
 *  - la liste des applis installées repart vers Home Assistant au relevé suivant (lancé aussitôt).
 *
 * Et la désinstallation demandée depuis Vision ([desinstaller]) : l'écran de confirmation du
 * système passe, même appareil fermé, sauf s'il vise Vision elle-même.
 */
object Paquets {
    /** Change à chaque installation ou désinstallation : l'empreinte de l'accueil en dépend. */
    @Volatile var generation = 0
        private set

    /** Abonnés (l'accueil) : paquet, vrai si absent (désinstallé ou désactivé). Appelés sur le fil principal. */
    private val abonnes = CopyOnWriteArraySet<(String, Boolean) -> Unit>()
    fun abonner(f: (String, Boolean) -> Unit) { abonnes.add(f) }
    fun desabonner(f: (String, Boolean) -> Unit) { abonnes.remove(f) }

    @Volatile private var ecoute: Any? = null
    @Volatile private var recepteur: BroadcastReceiver? = null
    private val main = Handler(Looper.getMainLooper())

    /**
     * Les applis présentes (lançables) connues : un changement ne compte que s'il fait passer une appli de présente
     * à absente ou l'inverse. Rempli au démarrage avec la liste du tiroir (avant tout changement).
     */
    @Volatile private var suivi: NettoyageAccueil.SuiviPresence? = null
    private fun suivi(ctx: Context): NettoyageAccueil.SuiviPresence = suivi ?: NettoyageAccueil.SuiviPresence(
        try { Accueil.applicationsInstallees(ctx).map { it.activityInfo.packageName } } catch (_: Exception) { emptyList() }
    ).also { suivi = it }

    /** Numéro de séquence des paquets du système déjà vu (PackageManager.getChangedPackages, Android 8+). */
    @Volatile private var sequence = -1

    /**
     * À appeler une fois (VisionApp) : Vision suit les paquets tant que son processus vit, par trois voies
     * indépendantes (lot accueil, point 3 : le seul rappel du lanceur ne suffisait pas, une appli désinstallée
     * restait à l'écran jusqu'au retour suivant sur l'accueil) :
     *  1. LauncherApps.Callback ;
     *  2. les diffusions du système (paquet ajouté, retiré, retiré complètement, modifié, remplacé), reçues
     *     par un récepteur déclaré à l'exécution ;
     *  3. le relevé des paquets changés ([verifier]) : chaque seconde tant que l'accueil est devant, et à son retour.
     * Quelle que soit la voie, c'est l'état réel du paquet pour cet utilisateur (Accueil.etatPaquet) qui dit
     * s'il est parti : désinstallé (même « pour l'utilisateur 0 » seulement), désactivé ou sans lanceur.
     */
    fun ecouter(ctx: Context) {
        val app = ctx.applicationContext
        suivi(app)
        if (ecoute == null && Build.VERSION.SDK_INT >= 21) try {
            val la = app.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
            if (la != null) {
                val cb = object : LauncherApps.Callback() {
                    override fun onPackageRemoved(p: String?, u: UserHandle?) { p?.let { change(app, it, source = "lanceur:retiré") } }  // i18n-exception : identifiant de journal, jamais affiché
                    override fun onPackageAdded(p: String?, u: UserHandle?) { p?.let { change(app, it, source = "lanceur:ajouté") } }  // i18n-exception : identifiant de journal, jamais affiché
                    override fun onPackageChanged(p: String?, u: UserHandle?) { p?.let { change(app, it, source = "lanceur:modifié") } }  // i18n-exception : identifiant de journal, jamais affiché
                    override fun onPackagesAvailable(ps: Array<out String>?, u: UserHandle?, remplacement: Boolean) {
                        ps?.forEach { change(app, it, source = "lanceur:disponible") }
                    }
                    override fun onPackagesUnavailable(ps: Array<out String>?, u: UserHandle?, remplacement: Boolean) {
                        // Carte SD retirée, appli suspendue : elle n'est plus lançable, mais on garde sa place.
                        ps?.forEach { change(app, it, garderPlace = true, source = "lanceur:indisponible") }
                    }
                }
                la.registerCallback(cb, main)
                ecoute = cb
            }
        } catch (_: Exception) {}
        if (recepteur == null) try {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    val pkg = i?.data?.schemeSpecificPart ?: return
                    // Une mise à jour : « retiré (remplacement) » puis « remplacé ». Seul le second compte.
                    if (i.action == Intent.ACTION_PACKAGE_REMOVED && i.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
                    change(app, pkg, source = "diffusion:" + i.action?.substringAfterLast('.'))
                }
            }
            val f = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED); addAction(Intent.ACTION_PACKAGE_CHANGED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, f, null, main, Context.RECEIVER_EXPORTED)
            else app.registerReceiver(r, f, null, main)
            recepteur = r
        } catch (_: Exception) {}
        verifier(app)
    }

    /**
     * Le relevé des paquets changés depuis le passage précédent (Android 8+) : rattrape un rappel ou une
     * diffusion manqués (processus gelé en arrière-plan, rappel filtré par le système). Peu coûteux : un appel
     * qui ne renvoie rien tant que rien ne change. Le premier passage note seulement où en est le système.
     */
    fun verifier(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val app = ctx.applicationContext
        try {
            val pm = app.packageManager
            if (sequence < 0) { sequence = pm.getChangedPackages(0)?.sequenceNumber ?: 0; return }
            val c = pm.getChangedPackages(sequence) ?: return
            sequence = c.sequenceNumber
            c.packageNames.forEach { change(app, it, source = "relevé") }  // i18n-exception : identifiant de journal, jamais affiché
        } catch (_: Exception) {}
    }

    /**
     * Une appli est arrivée, partie ou a changé. L'état réel du paquet dit s'il est présent (installé, activé,
     * lançable pour cet utilisateur). On ne rebâtit (et ne prévient Home Assistant) que si l'appli passe de
     * présente à absente ou l'inverse, ou si le nettoyage a retiré quelque chose de l'accueil enregistré :
     * « modifié » répété sur un paquet sans lanceur (Services Google Play) ne fait plus rien.
     * `garderPlace` : indisponible pour un temps (carte SD, suspension), sa place sur l'accueil reste.
     */
    fun change(ctx: Context, pkg: String, garderPlace: Boolean = false, source: String = "") {
        if (pkg == ctx.packageName) return
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { change(ctx, pkg, garderPlace, source) }; return }
        val s = suivi(ctx)
        val presente = NettoyageAccueil.presente(try { Accueil.etatPaquet(ctx, pkg) } catch (_: Exception) { null })
        val accueilChange = !presente && !garderPlace && try { nettoyerAccueil(ctx, pkg, s.etaitPresente(pkg)) } catch (_: Exception) { false }
        if (!s.constat(pkg, presente, accueilChange)) return
        // Pour le contrôle sur appareil : quelle voie a vu le changement, et le constat (logcat -s VisionPaquets).
        android.util.Log.i("VisionPaquets", "$pkg ${if (presente) "présente" else "absente"} (vu par $source)")
        generation++
        oublierListes()
        // Home Assistant apprend la nouvelle liste au relevé suivant, qu'on lance tout de suite.
        try { AgentService.envoyerInventaire(ctx) } catch (_: Exception) {}
        abonnes.forEach { a -> try { a(pkg, !presente) } catch (_: Exception) {} }
    }

    /** 1.81.0 : une tuile propre à Vision (Mode Party) apparaît ou disparaît : l'accueil se refait, sans inventaire. */
    fun tuileInterne(cle: String, retiree: Boolean) {
        generation++
        oublierListes()
        val f = { abonnes.forEach { a -> try { a(cle, retiree) } catch (_: Exception) {} } }
        if (Looper.myLooper() == Looper.getMainLooper()) f() else main.post(f)
    }

    /** Les listes d'applis gardées en mémoire. */
    fun oublierListes() {
        Usage.oublierLanceurs()
        Etat.oublierPermises()
        Local.oublierFond()
        installateursCache = null
    }

    /**
     * Retire l'appli absente de l'accueil enregistré et libère ses widgets Android ; vrai si quelque chose a changé.
     * Rien n'est touché pour un paquet que l'accueil ne mentionne pas, qui n'était pas présent et ne fournit aucun
     * widget posé (sinon chaque « modifié » de Services Google Play libérait les widgets orphelins).
     */
    private fun nettoyerAccueil(ctx: Context, pkg: String, etaitPresente: Boolean): Boolean {
        val gestionnaire = WidgetsAndroid.gestionnaire(ctx)
        val avant = Accueil.etatEnregistre(ctx)
        val fournisseur = avant.places.any { p ->
            p.estWidget && p.code.startsWith(NettoyageAccueil.WIDGET_ANDROID) &&
                (p.code.removePrefix(NettoyageAccueil.WIDGET_ANDROID).toIntOrNull()?.let { id ->
                    try { gestionnaire.getAppWidgetInfo(id)?.provider?.packageName == pkg } catch (_: Exception) { false }
                } ?: false)
        }
        if (!etaitPresente && !fournisseur && !NettoyageAccueil.concerne(pkg, avant)) return false
        val r = NettoyageAccueil.retirer(pkg, avant) { id ->
            // Après la désinstallation, Android ne connaît plus le fournisseur : un widget sans fournisseur est orphelin.
            val info = try { gestionnaire.getAppWidgetInfo(id) } catch (_: Exception) { null }
            info == null || info.provider?.packageName == pkg
        }
        if (r.change) Accueil.ecrireEtat(ctx, r.etat)
        val hote = WidgetsAndroid.hote(ctx)
        r.widgetsALiberer.forEach { id -> try { hote.deleteAppWidgetId(id) } catch (_: Exception) {} }
        WidgetsAndroid.nettoyer(ctx)
        return r.change || r.widgetsALiberer.isNotEmpty()
    }

    // ------------------------------------------------------------------ désinstallation demandée depuis Vision

    @Volatile private var cible: String? = null
    @Volatile private var cibleLibelle: String? = null
    @Volatile private var demandeA = 0L

    @Volatile private var installateursCache: Set<String>? = null

    /** Les écrans de désinstallation du système : installateurs connus et ce qui répond à ACTION_DELETE ici. */
    fun installateurs(ctx: Context): Set<String> {
        installateursCache?.let { return it }
        val s = HashSet(MiseAJour.INSTALLATEURS)
        val pm = ctx.packageManager
        for (action in listOf(Intent.ACTION_DELETE, @Suppress("DEPRECATION") Intent.ACTION_UNINSTALL_PACKAGE)) try {
            pm.resolveActivity(Intent(action, Uri.parse("package:com.exemple")), 0)?.activityInfo?.packageName
                ?.takeIf { it != "android" && it != ctx.packageName }?.let { s.add(it) }
        } catch (_: Exception) {}
        installateursCache = s
        return s
    }

    /**
     * Le menu « Désinstaller » de Vision (tiroir, accueil, télé). Pour Vision elle-même, l'écran
     * du système s'ouvre sans passe-droit : la protection anti-désinstallation décide, comme avant.
     */
    fun desinstaller(act: Activity, pkg: String) {
        if (pkg.isEmpty()) return
        if (pkg == act.packageName) {
            cible = null
            try { act.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))) } catch (_: Exception) {}
            return
        }
        cible = pkg; cibleLibelle = Usage.libelle(act, pkg); demandeA = System.currentTimeMillis()
        try { act.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))) } catch (_: Exception) { cible = null }
    }

    /**
     * Garde-fou : vrai si `ecran` est la confirmation de la désinstallation demandée depuis Vision
     * (et pas celle de Vision). `texte` : le contenu de l'écran quand l'accessibilité le donne.
     */
    fun desinstallationLaissee(ctx: Context, ecran: String, texte: String? = null): Boolean {
        val c = cible ?: return false
        return NettoyageAccueil.ecranLaisse(ecran, installateurs(ctx), c, ctx.packageName,
            System.currentTimeMillis() - demandeA, texte, cibleLibelle, ctx.getString(R.string.app_name))
    }
}
