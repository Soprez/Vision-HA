// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * 1.78.0 (demande d'essai) : depuis une tuile d'appli, « Forcer l'arrêt », « Vider le cache », « Infos de l'appli ».
 *
 * Ce qu'Android laisse faire à Vision (ni appli système, ni propriétaire de l'appareil) :
 *  - arrêter les processus d'une appli passée en arrière-plan (killBackgroundProcesses) — pas un vrai « forcer
 *    l'arrêt » (réservé au système) ;
 *  - aucun vidage de cache d'une autre appli (réservé au système) ;
 *  - effacer TOUTES les données d'une appli seulement en propriétaire de l'appareil (clearApplicationUserData) —
 *    jamais sur les télés de la maison ; proposé, alors, derrière une confirmation qui le dit en toutes lettres.
 * Pour le reste : la page « Infos de l'appli » du système, où les deux boutons existent, le focus de la
 * télécommande posé directement sur le bon bouton par le service d'accessibilité de Vision.
 * 1.78.1 (retour d'essai) : « Forcer l'arrêt » et « Vider le cache » sont libres, sans code. La page système qu'ils ouvrent porte
 * aussi Désinstaller, Effacer les données, Désactiver, Autorisations : sans décideur, elle s'ouvre en mode restreint
 * et le service d'accessibilité de Vision annule ces boutons-là (retour, et un mot pour dire pourquoi). « Infos de
 * l'appli » (la page entière) et « Effacer toutes les données » restent aux décideurs (code ou empreinte).
 *
 * Lot accueil (point 7) : la règle ci-dessus est enfin appliquée (le mode restreint n'était jamais activé) :
 * « Vider le cache » et « Forcer l'arrêt » (Android 14+) ouvrent la page en mode restreint SANS code, « Infos »
 * (menus de l'accueil et du tiroir) garde le code. Décision : [voie] (logique pure, testée).
 *
 * LIMITE (pour l'auteur, en une phrase) : Android ne laisse aucune appli vider le cache d'une autre ni, depuis Android 14,
 * la forcer à s'arrêter, donc Vision ouvre la page système de l'appli où seuls « Vider le cache » et « Forcer
 * l'arrêt » restent libres grâce au service d'accessibilité de Vision — et si ce service est coupé, le code est demandé.
 */
object ActionsAppli {
    enum class Viser { ARRET, CACHE, AUCUN }

    /** Comment mener une action sur une appli : tout de suite, page système restreinte sans code, ou code d'abord. */
    enum class Voie { DIRECT, RESTREINT, CODE }

    /**
     * Logique pure. `arretDirect` : Vision peut arrêter l'appli elle-même (avant Android 14, « Forcer l'arrêt »
     * seulement). `accessibilite` : le service de Vision tourne (sans lui, rien ne filtre la page système : code).
     * `decideur` : un décideur vient de prouver qui il est (fenêtre du code encore ouverte) : la page entière.
     */
    fun voie(arretDirect: Boolean, accessibilite: Boolean, decideur: Boolean): Voie = when {
        arretDirect -> Voie.DIRECT
        decideur -> Voie.CODE          // la fenêtre est ouverte : Garde.exiger passe aussitôt, page entière
        accessibilite -> Voie.RESTREINT
        else -> Voie.CODE
    }

    /** Textes affichés (regroupés pour l'extraction i18n). */
    val RAISON_INFOS: String get() = Tr.s(R.string.actions_raison_infos)

    /*
     * i18n-exception (tout ce bloc, jusqu'à INTERDITS) : ce ne sont pas des textes de Vision mais ceux des boutons
     * de la page système d'Android, à reconnaître dans les six langues de Vision (données, jamais affichées).
     */
    /** Les libellés du bouton à viser dans la page système (télé, téléphone ; six langues). */
    fun libelles(v: Viser): List<String> = when (v) {
        Viser.ARRET -> listOf("Forcer l'arrêt", "Forcer l’arrêt", "Arrêt forcé", "Force stop",  // i18n-exception
            "Beenden erzwingen", "Stopp erzwingen", "Forzar detención", "Forzar cierre", "Forza interruzione",  // i18n-exception
            "Arresto forzato", "Termina", "Gedwongen stoppen", "Geforceerd stoppen")  // i18n-exception
        // Télé : « Vider le cache » est sur la page ; téléphone : dans « Stockage et cache ».
        Viser.CACHE -> listOf("Vider le cache", "Effacer le cache", "Clear cache", "Stockage et cache", "Stockage", "Storage & cache", "Storage",  // i18n-exception
            "Cache leeren", "Speicher und Cache", "Speicher", "Borrar caché", "Almacenamiento y caché", "Almacenamiento",  // i18n-exception
            "Svuota cache", "Spazio di archiviazione e cache", "Spazio di archiviazione", "Cache wissen", "Opslag en cache", "Opslag")  // i18n-exception
        Viser.AUCUN -> emptyList()
    }

    @Volatile private var cible: Viser = Viser.AUCUN
    @Volatile private var jusqua = 0L
    /** Mode restreint : jusqu'à quand (les réglages ouverts par Vision sans décideur), 0 sinon. */
    @Volatile var restreintJusqua = 0L
        private set

    fun restreint(): Boolean = SystemClock.elapsedRealtime() < restreintJusqua

    /** Un bouton de la page système interdit en mode restreint (télé, téléphone ; français et anglais). Logique pure. */
    fun interdit(texte: String): Boolean {
        val t = texte.lowercase(java.util.Locale.ROOT).replace('’', '\'')
        if (t.isBlank()) return false
        // « Vider le cache » et « Forcer l'arrêt » passent, toujours.
        if (AUTORISES.any { t.contains(it) } && INTERDITS.none { t.contains(it) }) return false
        return INTERDITS.any { t.contains(it) }
    }

    private val AUTORISES = listOf("cache", "caché", "forcer l'arrêt", "arrêt forcé", "force stop",  // i18n-exception
        "beenden erzwingen", "stopp erzwingen", "forzar detención", "forzar cierre", "forza interruzione", "arresto forzato",  // i18n-exception
        "gedwongen stoppen", "geforceerd stoppen")  // i18n-exception
    private val INTERDITS = listOf(
        "désinstaller", "desinstaller", "uninstall",  // i18n-exception
        "effacer les données", "effacer toutes les données", "vider les données", "supprimer les données", "clear data",  // i18n-exception
        "clear storage", "effacer le stockage", "vider le stockage", "supprimer définitivement", "deleted permanently", "permanently",  // i18n-exception
        "désactiver", "disable", "autorisations", "permissions",  // i18n-exception
        // Allemand, espagnol, italien, néerlandais (mêmes boutons du système).
        "deinstallieren", "daten löschen", "speicherinhalt löschen", "speicher löschen", "deaktivieren", "berechtigungen",  // i18n-exception
        "desinstalar", "borrar datos", "borrar almacenamiento", "desactivar", "inhabilitar", "permisos",  // i18n-exception
        "disinstalla", "cancella dati", "cancella spazio di archiviazione", "disattiva", "autorizzazioni",  // i18n-exception
        "verwijderen", "gegevens wissen", "opslag wissen", "uitschakelen", "uitzetten", "rechten", "machtigingen",  // i18n-exception
        "vider l'espace de stockage", "effacer l'espace de stockage", "gérer l'espace", "manage space", "définitivement",  // i18n-exception
        "désactivez",  // i18n-exception
        // Accès spéciaux de la page de l'appli (téléphone) : à réserver aux décideurs eux aussi.
        "afficher par-dessus", "superposition", "display over other apps", "appear on top",  // i18n-exception
        "modifier les paramètres système", "modify system settings",  // i18n-exception
        "applis inconnues", "applications inconnues", "sources inconnues", "unknown apps", "unknown sources",  // i18n-exception
    )

    /**
     * Un écran des réglages à refermer en mode restreint : il propose une action dangereuse (effacer les données,
     * désactiver, désinstaller…) sans « Vider le cache » ni « Forcer l'arrêt » — donc une confirmation, pas la page
     * de l'appli (qui porte tout). Logique pure.
     */
    fun pageInterdite(texte: String): Boolean {
        val t = texte.lowercase(java.util.Locale.ROOT).replace('’', '\'')
        return INTERDITS.any { t.contains(it) } && AUTORISES.none { t.contains(it) }
    }

    /** Un écran à refermer en mode restreint : installateur (désinstallation), gestion des autorisations. */
    fun ecranInterdit(pkg: String): Boolean = restreint() && (pkg.contains("packageinstaller") || pkg.contains("permissioncontroller"))

    /** Vision elle-même n'a pas ce menu (on ne vide ni n'arrête l'accueil en cours). */
    fun concerne(ctx: Context, pkg: String?): Boolean = !pkg.isNullOrEmpty() && pkg != ctx.packageName && pkg != Party.CLE

    fun proprietaire(ctx: Context): Boolean = android.os.Build.VERSION.SDK_INT >= 28 && try {
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        dpm.isDeviceOwnerApp(ctx.packageName) || dpm.isProfileOwnerApp(ctx.packageName)
    } catch (_: Exception) { false }

    /**
     * 1.78.2 (retour d'essai) : sans code et sans page système. Vision arrête elle-même les processus de l'appli (permis tant
     * qu'elle n'est pas au premier plan, ce qui est le cas depuis l'accueil). Ce n'est pas le « Forcer l'arrêt »
     * du système (réservé à Android), mais l'appli repart de zéro à sa prochaine ouverture.
     */
    /** Android 14 et plus : une appli ne peut plus arrêter les processus d'une autre (seulement les siens). */
    fun arretDirectPossible(): Boolean = android.os.Build.VERSION.SDK_INT < 34

    fun forcerArret(act: Activity, pkg: String, nom: String) {
        when (voie(arretDirectPossible(), VisionAccessibility.actif, Garde.ouverte())) {
            Voie.DIRECT -> {
                val am = act.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                try { am.killBackgroundProcesses(pkg) } catch (_: Exception) {}
                // Une deuxième fois un instant plus tard : un processus de service redémarré tout de suite s'arrête aussi.
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ try { am.killBackgroundProcesses(pkg) } catch (_: Exception) {} }, 800)
                Toast.makeText(act, act.getString(R.string.actions_arretee, nom), Toast.LENGTH_LONG).show()
            }
            // Android 14+ : seule la page système peut l'arrêter ; sans code, en mode restreint.
            Voie.RESTREINT -> { Toast.makeText(act, act.getString(R.string.actions_arret_reglages, nom), Toast.LENGTH_LONG).show(); infos(act, pkg, Viser.ARRET, restreint = true) }
            // Service d'accessibilité coupé : rien ne filtrerait Désinstaller, Effacer les données… code d'abord.
            Voie.CODE -> Garde.exiger(act, ParentsActivity.RAISON) {
                Toast.makeText(act, act.getString(R.string.actions_arret_reglages, nom), Toast.LENGTH_LONG).show()
                infos(act, pkg, Viser.ARRET, restreint = false)
            }
        }
    }

    /**
     * Android ne laisse aucune appli vider le cache d'une autre : seule la page système « Infos de l'appli » le
     * fait. Sans code, en mode restreint (seuls « Vider le cache » et « Forcer l'arrêt » y restent permis) ; si le
     * service d'accessibilité de Vision ne tourne pas, le code d'un décideur d'abord (repli sûr).
     */
    fun viderCache(act: Activity, pkg: String, nom: String) {
        when (voie(arretDirect = false, accessibilite = VisionAccessibility.actif, decideur = Garde.ouverte())) {
            Voie.RESTREINT -> { Toast.makeText(act, act.getString(R.string.actions_cache_reglages, nom), Toast.LENGTH_LONG).show(); infos(act, pkg, Viser.CACHE, restreint = true) }
            else -> Garde.exiger(act, ParentsActivity.RAISON) {
                Toast.makeText(act, act.getString(R.string.actions_cache_reglages, nom), Toast.LENGTH_LONG).show()
                infos(act, pkg, Viser.CACHE, restreint = false)
            }
        }
    }

    /** La page entière (désinstaller, autorisations…) : décideurs seulement (menus de l'accueil et du tiroir). */
    fun infosProtegees(act: Activity, pkg: String) = Garde.exiger(act, RAISON_INFOS) { infos(act, pkg, Viser.AUCUN, restreint = false) }

    /** Propriétaire de l'appareil seulement : TOUTES les données, après une confirmation qui le dit. */
    fun effacerDonnees(act: Activity, pkg: String, nom: String) = Garde.exiger(act, ParentsActivity.RAISON) {
        Ui.dialogue(act).setTitle(act.getString(R.string.actions_effacer_titre, nom))
            .setMessage(act.getString(R.string.actions_effacer_texte, nom))
            .setNegativeButton(R.string.commun_annuler, null)
            .setPositiveButton(R.string.actions_tout_effacer) { _, _ ->
                val dpm = act.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                if (android.os.Build.VERSION.SDK_INT < 28) return@setPositiveButton
                try {
                    dpm.clearApplicationUserData(AdminReceiver.composant(act), pkg, act.mainExecutor) { _, ok ->
                        act.runOnUiThread { Toast.makeText(act, if (ok) act.getString(R.string.actions_donnees_effacees, nom) else act.getString(R.string.actions_effacement_refuse, nom), Toast.LENGTH_LONG).show() }
                    }
                } catch (_: Exception) { Toast.makeText(act, R.string.actions_effacement_impossible, Toast.LENGTH_LONG).show() }
            }.show()
    }

    /** La page « Infos de l'appli » du système ; le service d'accessibilité y pose le focus sur le bouton visé. */
    fun infos(ctx: Context, pkg: String, viser: Viser, restreint: Boolean) {
        cible = viser; jusqua = SystemClock.elapsedRealtime() + 15_000L
        // Restreint : 5 min au plus (et fini dès qu'on quitte les réglages) ; décideur : la page entière.
        restreintJusqua = if (restreint) SystemClock.elapsedRealtime() + 5 * 60_000L else 0L
        ouvertA = SystemClock.elapsedRealtime()
        if (!restreint) Deverrouillage.ouvrirReglagesUnInstant(ctx, 120_000L)
        try {
            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            try { ctx.startActivity(Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        }
    }

    /** On a quitté les réglages : le mode restreint s'arrête (il ne survit pas à un autre usage des réglages). */
    fun quitteReglages() { restreintJusqua = 0L }

    @Volatile private var ouvertA = 0L
    fun depuisOuverture(): Long = SystemClock.elapsedRealtime() - ouvertA

    /**
     * Logique pure : l'écran `pkg` qui passe devant met-il fin au mode restreint ? Oui pour toute autre appli, et
     * pour Vision elle-même (retour à l'accueil) passé les 2 premières secondes (c'est elle qui vient d'ouvrir la
     * page) ; non pour les réglages, l'installateur, les autorisations (`reglages`), le clavier et la barre système.
     */
    fun finRestreint(pkg: String, reglages: Boolean, vision: String, depuisOuvertureMs: Long): Boolean = when {
        reglages -> false
        pkg.contains("inputmethod") || pkg.contains("keyboard") || pkg == "com.android.systemui" -> false
        pkg == vision -> depuisOuvertureMs > 2_000L
        else -> true
    }

    /**
     * Appelé par le service d'accessibilité sur les écrans des réglages : focus sur le bouton visé. La page remet
     * parfois le focus sur sa première ligne juste après : on revient tant que le focus n'y est pas resté
     * (rend vrai s'il faut revérifier un peu plus tard).
     */
    fun viser(racine: AccessibilityNodeInfo?): Boolean {
        if (cible == Viser.AUCUN || racine == null) return false
        if (SystemClock.elapsedRealtime() > jusqua) { cible = Viser.AUCUN; return false }
        for (l in libelles(cible)) {
            val n = racine.findAccessibilityNodeInfosByText(l).firstOrNull() ?: continue
            var v: AccessibilityNodeInfo? = n
            while (v != null && !v.isFocusable && !v.isClickable) v = v.parent
            val cibleNoeud = v ?: n
            if (cibleNoeud.isFocused) {
                // Resté en place depuis au moins une vérification : c'est fini.
                if (posee) { cible = Viser.AUCUN; posee = false; return false }
                posee = true
                return true
            }
            posee = false
            cibleNoeud.performAction(AccessibilityNodeInfo.ACTION_FOCUS) || cibleNoeud.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            return true
        }
        return true  // la page n'a pas encore ses lignes : on revient
    }

    @Volatile private var posee = false
}
