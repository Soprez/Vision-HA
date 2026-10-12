// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

/**
 * Voit quelle appli passe au premier plan et la recouvre si elle est bloquée.
 * Protège aussi les écrans de réglages qui permettraient de couper Vision.
 */
class VisionAccessibility : AccessibilityService() {

    override fun onServiceConnected() {
        actif = true
        AgentService.demarrer(this)
    }

    override fun onDestroy() {
        actif = false
        super.onDestroy()
    }

    override fun onInterrupt() {}

    // ---- Vision à la place de l'accueil du système (Google TV impose le sien : on ne peut que passer devant) ----

    private var accueilVerifieA = 0L
    private var accueilAutre = false
    private val redirections = ArrayDeque<Long>()

    /**
     * Vrai sur une télé inscrite dont l'accueil système n'est pas Vision, option active. `fenetre` : l'accueil du
     * système vient de passer devant ; lot accueil (point 8) : un décideur l'a ouvert avec le code
     * (« Accueil d'origine ») : on le laisse un moment. Sans code, Vision repasse devant.
     */
    private fun prendAccueil(fenetre: Boolean = false): Boolean {
        if (fenetre && AccueilDefaut.origineLaissee()) return false
        val now = SystemClock.uptimeMillis()
        if (accueilVerifieA == 0L || now - accueilVerifieA > 60_000) {
            accueilVerifieA = now
            accueilAutre = try {
                val r = packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                ReglagesTvActivity.estTele(this) && r?.activityInfo?.packageName != packageName
            } catch (_: Exception) { false }
        }
        return accueilAutre && Local.accueilForce(this) && Config(this).inscrit && Modules.actif(this, Modules.ACCUEIL)
    }

    private fun ouvrirVision(): Boolean {
        // Garde-fou : si Vision ne tient pas devant (plantage), on rend l'accueil du système plutôt que de boucler.
        val now = SystemClock.uptimeMillis()
        while (redirections.isNotEmpty() && now - redirections.first() > 30_000) redirections.removeFirst()
        if (redirections.size >= 5) return false
        redirections.addLast(now)
        return try {
            startActivity(Intent(this, LanceurActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)); true
        } catch (_: Exception) { false }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        Flux.mainHumaine()
        // La touche Accueil met fin à l'accueil d'origine laissé au décideur : elle ramène Vision.
        if (event.keyCode == KeyEvent.KEYCODE_HOME && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) AccueilDefaut.finOrigine()
        if (event.keyCode != KeyEvent.KEYCODE_HOME || !prendAccueil()) return false
        // Appui long laissé au système (tableau de bord Google TV).
        if (event.action == KeyEvent.ACTION_DOWN) return event.repeatCount == 0
        if (event.action == KeyEvent.ACTION_UP) { Usage.derniereInteraction = System.currentTimeMillis(); return ouvrirVision() }
        return false
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        e ?: return
        val pkg = e.packageName?.toString() ?: return
        // Seul un geste compte comme de l'activité : un clic, un défilement, une
        // saisie, un changement d'écran. Le contenu qui bouge tout seul (vidéo,
        // messages qui arrivent, animations) n'est pas une personne devant l'écran.
        // 1.78.1 : page d'une appli ouverte sans décideur (Forcer l'arrêt, Vider le cache) : les boutons dangereux
        // (désinstaller, effacer les données, désactiver, autorisations) sont annulés aussitôt touchés.
        // Lot accueil (point 7) : enfin activé par « Vider le cache » / « Forcer l'arrêt » sans code.
        if (ActionsAppli.restreint()) {
            if (e.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && pkg in reglages && ActionsAppli.interdit(texteClic(e))) {
                refuserRestreint(); return
            }
            if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || (e.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && pkg in reglages)) {
                if (ActionsAppli.ecranInterdit(pkg) || (pkg in reglages && boiteInterdite())) { refuserRestreint(); return }
                // On a quitté les réglages (autre appli, ou retour à Vision) : la restriction s'arrête là.
                if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    && ActionsAppli.finRestreint(pkg, pkg in reglages, packageName, ActionsAppli.depuisOuverture())) ActionsAppli.quitteReglages()
            }
        }
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (e.eventType in GESTES) Usage.derniereInteraction = System.currentTimeMillis()
            if (e.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && pkg in reglages) {
                proteger(pkg, e.className?.toString() ?: "")
                viserPuisRevoir(0)
            }
            return
        }
        Usage.derniereInteraction = System.currentTimeMillis()
        dernierEcran = e.className?.toString() ?: ""
        Migration.ecranVu(this, pkg, e.className?.toString() ?: "")
        Assistant.ecranVu(this, pkg)
        // Les claviers et la barre système ne sont pas « l'appli utilisée ».
        if (pkg != "com.android.systemui" && !pkg.contains("inputmethod") && !pkg.contains("keyboard")) {
            if (pkg != Usage.dernierPaquet && Usage.dernierPaquet.isNotEmpty() && Usage.dernierPaquet != packageName) Usage.paquetQuitte = Usage.dernierPaquet
            Usage.dernierPaquet = pkg
        }
        // L'accueil du système vient de passer devant (démarrage, sortie d'une appli) : Vision prend sa place.
        if (pkg != packageName && pkg.contains("launcher") && (e.className?.toString() ?: "").contains("home", ignoreCase = true)
            && pkg in Usage.lanceurs(this) && prendAccueil(fenetre = true)) { ouvrirVision(); return }
        if (pkg == packageName && AccueilDefaut.depuisOrigine() > 2_000L) AccueilDefaut.finOrigine()
        if (pkg in reglages && proteger(pkg, e.className?.toString() ?: "")) return
        // 1.78.0 : « Forcer l'arrêt » / « Vider le cache » depuis l'accueil : le focus va sur le bon bouton.
        if (pkg in reglages) viserPuisRevoir()
        Etat.doitBloquer(this, pkg)?.let { raison ->
            BlockActivity.afficher(this, raison, pkg)
        }
    }

    /**
     * Le texte d'un clic : à la télécommande, l'évènement vient souvent de la ligne entière, sans texte propre ;
     * on lit alors les libellés qu'elle contient (« Désinstaller », « Effacer les données »…).
     */
    private fun texteClic(e: AccessibilityEvent): String {
        val sb = StringBuilder(e.text.joinToString(" ")).append(' ').append(e.contentDescription ?: "")
        if (sb.isBlank()) try { collecter(e.source, sb, intArrayOf(12)) } catch (_: Exception) {}
        return sb.toString()
    }

    /** Une confirmation dangereuse (effacer les données, désactiver…) dans les réglages, en mode restreint. */
    private fun boiteInterdite(): Boolean {
        val racine = rootInActiveWindow ?: return false
        val texte = StringBuilder()
        collecter(racine, texte, intArrayOf(120))
        return ActionsAppli.pageInterdite(texte.toString())
    }

    /** Le focus sur le bouton visé, revérifié un instant plus tard (la page le reprend parfois). */
    private fun viserPuisRevoir(essais: Int = 8) {
        val revoir = try { ActionsAppli.viser(rootInActiveWindow) } catch (_: Exception) { false }
        if (revoir && essais > 0) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ viserPuisRevoir(essais - 1) }, 450)
    }

    private var refuseA = 0L

    private fun refuserRestreint() {
        // Une seule fois par confirmation : plusieurs retours d'affilée refermeraient aussi la page de l'appli.
        val maintenant = android.os.SystemClock.elapsedRealtime()
        if (maintenant - refuseA < 1500L) return
        refuseA = maintenant
        performGlobalAction(GLOBAL_ACTION_BACK)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(this, getString(R.string.accessibilite_reserve_decideurs, Vocabulaire.decideurs(this)), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun proteger(pkg: String, classe: String = ""): Boolean {
        val cfg = Config(this)
        if (!cfg.inscrit || cfg.modeParent) return false
        // Module « protections » coupé dans Home Assistant : plus aucun réglage n'est fermé (il reprend à la réactivation).
        if (!Modules.actif(this, Modules.PROTECTIONS)) return false
        // Redonner l'administrateur de l'appareil depuis les réglages de Vision (protections reprises) : l'écran passe.
        if (AdminReceiver.demandeEnCours() && dernierEcran.contains("DeviceAdminAdd")) return false
        // Module « accueil » coupé : rendre l'accueil d'origine n'est jamais bloqué.
        if (!Modules.actif(this, Modules.ACCUEIL) && pkg.endsWith(".permissioncontroller") && dernierEcran.contains(".role.ui.")) return false
        // Désinstallation de l'ancienne Vision, lancée depuis la nouvelle : elle passe, elle seule.
        if (Migration.ecranAutorise(this, pkg, classe)) return false
        // L'assistant de premier lancement vient d'ouvrir cet écran pour une de ses étapes.
        if (Assistant.autorise(this, pkg)) return false
        if (Deverrouillage.reglagesOuverts(this)) return false
        // Le choix de l'accueil (boîte d'Android ou « Appli d'accueil ») vient d'être demandé depuis Vision.
        if (AccueilDefaut.demandeEnCours() && (pkg.endsWith(".permissioncontroller") || pkg.endsWith(".settings"))) return false
        // Les écrans d'Android pour choisir l'accueil passent toujours tant que Vision ne l'est pas : CHOISIR Vision
        // n'est jamais bloqué. Une fois Vision l'accueil, en changer reste protégé.
        if (pkg.endsWith(".permissioncontroller") && dernierEcran.contains(".role.ui.") && !AccueilDefaut.estAccueil(this)) return false
        val racine = rootInActiveWindow ?: return false
        val texte = StringBuilder()
        collecter(racine, texte, intArrayOf(400))
        val t = texte.toString().lowercase(Locale.ROOT)
        // Garde-fou : la confirmation d'une désinstallation demandée depuis le menu de Vision passe,
        // si elle nomme bien l'appli visée. Celle de Vision reste protégée (voir NettoyageAccueil.ecranLaisse).
        if (Paquets.desinstallationLaissee(this, pkg, t)) return false
        // Vision se met à jour : l'écran d'installation du système passe, sauf s'il s'agit de
        // la désinstaller.
        if (pkg in MiseAJour.INSTALLATEURS && MiseAJour.installationPropreEnCours()
            && DESINSTALLATION.none { t.contains(it) }) return false
        val nom = getString(R.string.app_name).lowercase(Locale.ROOT)
        if (!t.contains(nom)) return false
        if (motsSensibles.none { t.contains(it) }) return false
        performGlobalAction(GLOBAL_ACTION_BACK)
        performGlobalAction(GLOBAL_ACTION_HOME)
        AgentService.tentatives++
        BlockActivity.afficher(this, BlockActivity.RAISON_REGLAGE, pkg)
        return true
    }

    private fun collecter(n: AccessibilityNodeInfo?, sb: StringBuilder, reste: IntArray) {
        if (n == null || reste[0] <= 0) return
        reste[0]--
        n.text?.let { sb.append(it).append('\n') }
        n.contentDescription?.let { sb.append(it).append('\n') }
        for (i in 0 until n.childCount) collecter(n.getChild(i), sb, reste)
    }

    companion object {
        private val GESTES = setOf(
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START,
            AccessibilityEvent.TYPE_GESTURE_DETECTION_START,
            // Télécommande : passer d'une case à l'autre, c'est quelqu'un devant l'écran.
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_SELECTED,
        )
        @Volatile var actif = false
        /** La dernière fenêtre ouverte (sa classe) : les changements de contenu n'en donnent pas toujours. */
        @Volatile var dernierEcran = ""

        private val reglages = setOf(
            "com.android.settings", "com.android.tv.settings", "com.google.android.tv.settings",
            "com.amazon.tv.settings", "com.amazon.tv.settings.v2", "com.samsung.android.settings",
            "com.android.packageinstaller", "com.google.android.packageinstaller",
            "com.google.android.permissioncontroller", "com.android.permissioncontroller",
            "com.miui.securitycenter", "com.coloros.safecenter", "com.huawei.systemmanager",
        )

        /*
         * Motifs de reconnaissance (pas des textes affichés) : les mots des écrans de réglages d'Android, lus
         * dans la langue du téléphone. Un jeu par langue de Vision (fr, en, de, es, it, nl), en minuscules.
         */
        private val DESINSTALLATION = listOf(
            "désinstaller", "desinstaller", "uninstall",          // fr, en
            "deinstallieren", "desinstalar", "disinstalla",       // de, es, it
            "verwijderen",                                        // nl (« Verwijderen » désinstalle)
        )

        private val motsSensibles = listOf(
            "désinstaller", "desinstaller", "uninstall",
            "forcer l'arrêt", "forcer l’arrêt", "force stop",
            "désactiver", "deactivate", "disable",
            "utiliser vision", "use vision", "raccourci", "shortcut",
            "arrêter", "stop", "administrat", "vpn", "effacer", "clear storage", "clear data",
            // de
            "deinstallieren", "beenden erzwingen", "stoppen erzwingen", "deaktivieren", "vision verwenden",
            "verknüpfung", "beenden", "speicher leeren", "daten löschen", "löschen",
            // es
            "desinstalar", "forzar detención", "forzar cierre", "forzar la detención", "desactivar", "usar vision",
            "acceso directo", "detener", "borrar almacenamiento", "borrar datos", "borrar",
            // it
            "disinstalla", "arresto forzato", "termina", "disattiva", "usa vision", "scorciatoia",
            "interrompi", "amministrat", "cancella spazio", "cancella dati", "cancella",
            // nl
            "verwijderen", "geforceerd stoppen", "gedwongen stoppen", "stoppen forceren", "uitschakelen",
            "deactiveren", "vision gebruiken", "snelkoppeling", "beheerder", "opslag wissen", "gegevens wissen", "wissen",
        )

        fun estActive(ctx: Context): Boolean {
            if (actif) return true
            val attendu = ComponentName(ctx, VisionAccessibility::class.java).flattenToString()
            val actives = Settings.Secure.getString(
                ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return actives.split(':').any { it.equals(attendu, ignoreCase = true) }
        }
    }
}
