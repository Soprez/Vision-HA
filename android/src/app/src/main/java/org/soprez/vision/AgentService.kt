// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import org.json.JSONArray
import org.json.JSONObject

/**
 * Le battement de l'agent : il se présente à Home Assistant, dit ce qu'il voit,
 * reçoit ce qu'il doit appliquer. Tourne en service de premier plan, avec une
 * notification visible en permanence (exigée par Android, et de toute façon
 * voulue : Vision ne se cache pas).
 */
class AgentService : Service() {

    private lateinit var fil: HandlerThread
    private lateinit var h: Handler
    private val principal = Handler(android.os.Looper.getMainLooper())
    /** Dernier envoi de l'inventaire des applis installées (0 = jamais depuis le démarrage). */
    @Volatile private var dernierInventaire = 0L

    private val battement = object : Runnable {
        override fun run() {
            val attente = try { interroger() } catch (_: Exception) { 60 }
            surveillerPremierPlan()
            h.postDelayed(this, attente.toLong() * 1000)
        }
    }

    /** Télé : une appli autorisée (YouTube…) joue au premier plan et personne ne touche la télécommande
     *  depuis le délai choisi : la veille vient en couche, sans l'interrompre (le rêve du système l'aurait mise en pause). */
    private val veilleSurAppli = object : Runnable {
        override fun run() {
            try {
                val ctx = this@AgentService
                VeilleMusique.maintenir(ctx)
                val min = Local.delaiVeille(ctx)
                if (min > 0 && Modules.actif(ctx, Modules.VEILLE) && ReglagesTvActivity.estTele(ctx) && !Veille.ouverte && Usage.ecranAllume(ctx) && Veille.permise(ctx)) {
                    val pk = Local.appEnFond(ctx)
                    val repos = System.currentTimeMillis() - maxOf(Usage.derniereInteraction, Veille.fermeeA)
                    if (pk != null && !Local.seRegarde(pk) && repos >= min * 60_000L) Veille.ouvrir(ctx)
                }
            } catch (_: Exception) {}
            principal.postDelayed(this, 20_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        principal.postDelayed(veilleSurAppli, 20_000)
        fil = HandlerThread("vision-agent").apply { start() }
        h = Handler(fil.looper)
        demarrerPremierPlan()
        Bulle.verifier(this)
        // 1.86.11 (retour d'essai) : sur une télé, Vision est le lecteur de Music Assistant (le son sort de Vision, sans récepteur Cast).
        if (ReglagesTvActivity.estTele(this) && LecteurVision.actif(this)) LecteurMusique.demarrer(this)
    }

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        h.removeCallbacks(battement)
        h.post(battement)
        return START_STICKY
    }

    override fun onDestroy() {
        LecteurMusique.arreter()
        h.removeCallbacks(battement)
        principal.removeCallbacks(veilleSurAppli)
        fil.quitSafely()
        // Android a tué le service : on demande à être relancé.
        if (Config(this).inscrit) demarrer(this)
        super.onDestroy()
    }

    override fun onBind(i: Intent?): IBinder? = null

    // --------------------------------------------------------- Le cycle

    private fun interroger(): Int {
        val cfg = Config(this)
        if (!cfg.inscrit) {
            if (!inscrire(cfg)) return 60
        }
        // Déverrouillages appliqués (QR hors ligne compris) : Home Assistant les journalise d'abord.
        Deverrouillage.envoyerConsommations(this)
        val corps = rapport(cfg)
        val r = try {
            Net.post(this, cfg, "/api/pc_parental/poll", corps)
        } catch (e: Net.Echec) {
            // Secret refusé : l'appareil a été retiré de HA, on repart de zéro.
            if (e.code == 401) { cfg.oublierInscription() }
            majNotification()
            return 30
        } catch (_: Exception) {
            majNotification()
            return 30
        }

        // Home Assistant nous donne une fiche à nous (un autre appareil du même modèle tenait celle-ci).
        r.optJSONObject("reinscription")?.let { n ->
            if (n.optString("id").isNotEmpty() && n.optString("secret").isNotEmpty()) {
                cfg.id = n.optString("id"); cfg.secret = n.optString("secret"); cfg.nom = n.optString("name")
                cfg.personneAEnvoyer = true
                return 5
            }
        }
        when (r.optString("action")) {
            "uninstall" -> { seDesinscrire(cfg); return 300 }
        }
        Modules.memoriser(this, r)
        // Widgets Home Assistant : une nouvelle « widgets.version » fait recharger la liste des entités à l'accueil.
        WidgetsHaStock.noterVersion(this, r)
        // 1.76.0 (point 6 bis) : les widgets Home Assistant posés sur un autre lanceur (widgets système).
        try { WidgetsSysteme.auReleve(this) } catch (_: Exception) {}
        Etat.appliquer(r, cfg)
        cfg.lieu = r.optString("lieu", "")
        // Lot 1 (A6) : les mots choisis dans Configurer → Vocabulaire (Décideur, Demandeur…), dans la langue de l'appareil.
        Vocabulaire.recevoir(this, r.optJSONObject("vocabulaire"))
        if (cfg.personneAEnvoyer) cfg.personneAEnvoyer = false
        Deverrouillage.releve(this, r)
        Acces.poserRefus(r.optJSONArray("refus"))
        Pauses.releve(this, r)
        Profil.releve(this, r)
        // 1.81.0 (point 14 bis) : la tuile Mode Party, et l'écran Party qui s'ouvre sur la télé choisie.
        try { Party.releve(this, r.optJSONObject("party")) } catch (_: Exception) {}
        Deverrouillage.directe(this, r.optJSONObject("deverrouillage"))?.let { Messages.info(this, "Vision", it) }
        synchroniserListeNoire(cfg, r.optString("liste_noire", ""))
        appliquerConsignesVpn()
        // Messages riches (télé) d'abord ; le reste, comme avant, en notification.
        Messages.afficher(this, MessagesRiches.recevoir(this, r.optJSONArray("notify")))
        // 1.80.0 : une demande vient d'arriver (accès, temps, déverrouillage) : l'accueil la montre tout de suite,
        // sans attendre son propre rafraîchissement (une minute).
        if (Demandes.contientDemande(r.optJSONArray("notify"))) LanceurActivity.rafraichirMaintenant?.invoke()
        MessagesRiches.liberer(this, r.optJSONArray("liberer"))
        MessagesRiches.entretenir(this)
        // Préavis : « le PC se verrouille à 21h00 » et plages d'étiquettes.
        // 1.69.0 : le décompte X / 5 / 1 min (module « Préavis » ; X réglé dans HA) remplace alors l'avertissement unique.
        val decompte = Preavis.releve(this, r)
        Messages.preavis(this,
            if (r.optInt("warn", 0) > 0 && !decompte) r.optString("message", "") else "",
            r.optJSONArray("avertissements"))
        traiterMiseAJour(cfg, r.optJSONObject("update"))
        majNotification()

        // Si une appli bloquée est déjà au premier plan, on la recouvre tout de suite.
        Usage.dernierPaquet.takeIf { it.isNotEmpty() }?.let { pkg ->
            Etat.doitBloquer(this, pkg)?.let { raison ->
                principal.post { BlockActivity.afficher(this, raison, pkg) }
            }
        }
        return Etat.poll
    }

    private fun inscrire(cfg: Config): Boolean {
        // APP-03 : jamais d'inscription sans confirmation (clé donnée par la personne, ou « Se relier » touché).
        if (!Liaison.peutInscrire(cfg.cleInscription, cfg.sansCle)) return false
        val corps = JSONObject().apply {
            put("key", cfg.cleInscription)
            put("host", appareil())
            put("user", cfg.utilisateur)
            put("platform", "android")
            put("type", Identite.type(this@AgentService))
            put("uid", uid()); nomAppareil().takeIf { it.isNotEmpty() }?.let { put("nom", it) }
            // Contrat de langue : Home Assistant écrit à cet appareil dans sa langue (étiquette BCP 47, « de-DE »).
            put("langue", java.util.Locale.getDefault().toLanguageTag())
        }
        return try {
            val r = Net.post(this, cfg, "/api/pc_parental/enroll", corps)
            if (r.optBoolean("ok")) {
                cfg.id = r.optString("id")
                cfg.secret = r.optString("secret")
                cfg.nom = r.optString("name")
                cfg.host = appareil()
                r.optJSONArray("personnes")?.let { cfg.personnes = it.toString() }
                Decouverte.etat = ""
                true
            } else false
        } catch (e: Net.Echec) {
            if (cfg.sansCle) Decouverte.etat = if (e.code == 403) getString(R.string.agent_inscriptions_fermees)
                else getString(R.string.agent_vision_ne_repond_pas, e.code)
            // Refus net (inscriptions fermées) : on ne réessaie pas en boucle ; « Se relier » le redemandera.
            if (e.code == 403 && cfg.sansCle) cfg.sansCle = false
            false
        } catch (e: Exception) {
            if (cfg.sansCle) Decouverte.etat = getString(R.string.agent_injoignable, (e.message ?: e.javaClass.simpleName).take(70))
            false
        }
    }

    /** Ce que l'agent remonte : qui, état, ce qu'il a vu, et l'état de ses protections. */
    private fun rapport(cfg: Config): JSONObject {
        val sites = JSONArray().also { a -> synchronized(Etat.sitesVus) {
            Etat.sitesVus.forEach { a.put(it) }; Etat.sitesVus.clear() } }
        val alertes = JSONArray().also { a -> synchronized(Etat.alertes) {
            Etat.alertes.forEach { a.put(JSONObject().put("domaine", it).put("source", "vpn")) }
            Etat.alertes.clear() } }
        val listeOuvertes = Usage.ouvertes(this, 90_000)
        val ouvertes = JSONArray().also { a -> listeOuvertes.forEach { a.put(it) } }
        val maintenant = System.currentTimeMillis()

        return JSONObject().apply {
            put("id", cfg.id)
            put("secret", cfg.secret)
            put("platform", "android")
            put("type", Identite.type(this@AgentService))
            put("version", BuildConfigCompat.version(this@AgentService))
            // Contrat de langue : la langue de l'appareil à chaque relevé (elle peut changer dans les réglages d'Android).
            put("langue", java.util.Locale.getDefault().toLanguageTag())
            put("host", cfg.host.ifEmpty { appareil() })
            // Deux appareils du même modèle ne doivent pas partager une fiche : identifiant propre, et nom réel sur télé.
            put("uid", uid()); nomAppareil().takeIf { it.isNotEmpty() }?.let { put("nom", it) }
            put("user", cfg.utilisateur)
            put("locked", Etat.verrouilleEffectif())
            put("focus", Usage.dernierPaquet)
            put("idle_seconds", Usage.inactifSecondes(this@AgentService))
            // 1.88.0 (retour d'essai) : l'écran de l'appareil (DisplayManager, sans permission) ; éteint → HA met la musique en pause.
            put("ecran", ecranEteint()?.let { if (it) "eteint" else "allume" } ?: "")
            put("apps_ouverts", ouvertes)
            put("pauses", Pauses.aEnvoyer(this@AgentService))
            put("sites_vus", sites)
            put("alertes", alertes)
            // Noms affichés des applis citées (le catalogue montre « YouTube »).
            put("apps_libelles", JSONObject(Usage.libelles(this@AgentService,
                listeOuvertes.plus(Usage.dernierPaquet))))
            // Inventaire complet au démarrage, puis une fois par heure.
            if (inventaireDemande || maintenant - dernierInventaire > 3_600_000L) {
                inventaireDemande = false
                val inv = JSONArray()
                Usage.inventaire(this@AgentService).forEach { (p, l) ->
                    inv.put(JSONObject().put("pkg", p).put("label", l))
                }
                put("apps_installees", inv)
                dernierInventaire = maintenant
            }
            // L'état des protections : HA peut ainsi afficher « Agent injoignable »
            // au sens fort, et prévenir si une protection a été coupée.
            put("protections", protections())
            // Demande de temps faite par l'enfant depuis l'écran de blocage.
            val mins = cfg.demandeTemps
            if (mins > 0) { if (Modules.actif(this@AgentService, Modules.DEMANDES)) put("demande_temps", mins); cfg.demandeTemps = 0 }
            // Personne choisie dans l'app, à faire connaître à HA une fois.
            if (cfg.personneAEnvoyer && cfg.personne.isNotEmpty()) put("person", cfg.personne)
        }
    }

    /** Vrai pour chaque protection en place ; une protection tombée se voit dans HA. */
    private fun protections(): JSONObject = JSONObject().apply {
        put("accessibilite", VisionAccessibility.estActive(this@AgentService))
        // Un module coupé n'attend plus sa protection : elle n'est pas signalée comme tombée.
        if (Modules.actif(this@AgentService, Modules.FILTRAGE)) put("vpn", DnsVpnService.actif)
        put("usage", Usage.accesUsage(this@AgentService))
        if (Modules.actif(this@AgentService, Modules.PROTECTIONS)) put("admin", AdminReceiver.estActif(this@AgentService))
        put("batterie", batterieIgnoree())
        if (!ReglagesTvActivity.estTele(this@AgentService) && Modules.actif(this@AgentService, Modules.ACCUEIL)) put("accueil", AccueilDefaut.estAccueil(this@AgentService))
        // 1.74.0 (point 7) : sur une télé, Vision tient l'accueil par « Accueil forcé », qui passe par l'accessibilité.
        else if (Modules.actif(this@AgentService, Modules.ACCUEIL)) put("accueil", Local.accueilForce(this@AgentService) && VisionAccessibility.estActive(this@AgentService))
    }

    private fun synchroniserListeNoire(cfg: Config, version: String) {
        if (version.isEmpty() || version == cfg.versionListeNoire) return
        try {
            val r = Net.post(this, cfg, "/api/pc_parental/liste-noire",
                JSONObject().put("id", cfg.id).put("secret", cfg.secret))
            if (r.optBoolean("ok")) {
                Etat.majListeNoire(this, r.optString("version"),
                    r.optJSONArray("connus") ?: JSONArray())
            }
        } catch (_: Exception) {}
    }

    private fun appliquerConsignesVpn() {
        // Filtrage coupé dans Home Assistant : le VPN reste arrêté (Modules l'a arrêté au changement).
        if (!Modules.actif(this, Modules.FILTRAGE)) { if (DnsVpnService.actif) DnsVpnService.arreter(this); return }
        val besoin = Etat.sites.isNotEmpty() || Etat.listeNoire.isNotEmpty() || Etat.dns.isNotEmpty()
        if (besoin && !DnsVpnService.actif && DnsVpnService.autorise(this)) {
            DnsVpnService.demarrer(this)
        }
    }

    /**
     * Mise à jour annoncée par HA pour Android : {version, path, sha256}.
     * Le payload Windows (scripts) n'a pas de `path` et est ignoré.
     */
    private fun traiterMiseAJour(cfg: Config, u: JSONObject?) {
        if (u == null) { MiseAJour.disponible = ""; return }
        val path = u.optString("path", "")
        if (path.isEmpty()) return
        MiseAJour.traiter(this, cfg, u.optString("version", ""), path, u.optString("sha256", ""))
    }

    /** Repli quand l'accessibilité est coupée : on lit le premier plan via UsageStats. */
    private fun surveillerPremierPlan() {
        if (VisionAccessibility.estActive(this)) return
        val pkg = Usage.premierPlan(this) ?: return
        Usage.dernierPaquet = pkg
        Etat.doitBloquer(this, pkg)?.let { raison ->
            principal.post { BlockActivity.afficher(this, raison, pkg) }
        }
    }

    private fun seDesinscrire(cfg: Config) {
        try {
            Net.post(this, cfg, "/api/pc_parental/poll",
                JSONObject().put("id", cfg.id).put("secret", cfg.secret)
                    .put("uninstalled", true).put("platform", "android"))
        } catch (_: Exception) {}
        cfg.oublierInscription()
        DnsVpnService.arreter(this)
        majNotification()
    }

    // ----------------------------------------------------- Notification

    private fun demarrerPremierPlan() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            // Importance minimale : pas d'icône dans la barre d'état, rangée tout
            // en bas du volet. Android impose une notification à un service de
            // premier plan ; on la rend la plus discrète possible.
            try { nm.deleteNotificationChannel("vision_etat") } catch (_: Exception) {}
            nm.createNotificationChannel(NotificationChannel(
                CANAL, getString(R.string.agent_canal_fond), NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
                description = getString(R.string.agent_canal_fond_desc)
            })
            // 1.76.0 (point 6 bis) : le statut lisible (« Ouvert · encore 1 h 20 »), sans son ni vibration.
            nm.createNotificationChannel(NotificationChannel(
                StatutNotif.CANAL, getString(R.string.agent_canal_statut), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false); setSound(null, null); enableVibration(false)
                description = getString(R.string.agent_canal_statut_desc)
            })
        }
        // Android 14+ exige un type de service de premier plan (ici « usage particulier » : contrôle parental).
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF, construireNotif(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIF, construireNotif())
        } catch (_: Exception) {
            try { startForeground(NOTIF, construireNotif()) } catch (_: Exception) {}
        }
    }

    private fun majNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF, construireNotif())
        // 1.79.0 (point 13 bis) : le fond de l'écran verrouillé suit l'état (redessiné seulement s'il change).
        if (EcranVerrou.voulu(this)) Thread { try { EcranVerrou.maj(this) } catch (_: Exception) {} }.start()
    }

    private fun construireNotif(): Notification {
        val ouvrir = PendingIntent.getActivity(this, 0,
            Intent(this, SetupActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        // 1.76.0 : statut lisible (module « Temps d'écran » et interrupteur de l'appareil), sinon présence minimale.
        val statut = StatutNotif.actif(this)
        val b = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, if (statut) StatutNotif.CANAL else CANAL) else @Suppress("DEPRECATION") Notification.Builder(this)
        val (titre, texte) = if (statut) StatutNotif.textes(Etat.horsLigne(), Etat.verrouilleEffectif(), Config(this).parentJusqua,
            Etat.prochainChangement, Etat.message, System.currentTimeMillis()) { Etat.heure(it) } else titreNotif() to texteNotif()
        return b.setSmallIcon(R.drawable.ic_vision)
            .setContentTitle(titre)
            .setContentText(texte)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(ouvrir)
            // 1.79.0 (point 13 bis) : sur l'écran verrouillé, le titre du statut (« Ouvert · encore 1 h 20 ») ;
            // le détail (message de fermeture) seulement si l'appareil le permet (réglage).
            .setVisibility(when { !statut -> Notification.VISIBILITY_SECRET; EcranVerrou.detailVisible(this) -> Notification.VISIBILITY_PUBLIC; else -> Notification.VISIBILITY_PRIVATE })
            .apply {
                if (statut && !EcranVerrou.detailVisible(this@AgentService)) setPublicVersion(
                    (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this@AgentService, StatutNotif.CANAL) else @Suppress("DEPRECATION") Notification.Builder(this@AgentService))
                        .setSmallIcon(R.drawable.ic_vision).setContentTitle(titre).setContentText("Vision").build())
            }
            .apply { if (Build.VERSION.SDK_INT < 26) @Suppress("DEPRECATION") setPriority(if (statut) Notification.PRIORITY_LOW else Notification.PRIORITY_MIN) }
            .build()
    }

    private fun titreNotif(): String = when {
        !Config(this).inscrit -> getString(R.string.agent_titre_a_configurer)
        Config(this).modeParent -> getString(R.string.agent_titre_pause)
        Etat.verrouilleEffectif() -> Etat.message.ifEmpty { getString(R.string.agent_titre_ferme) }
        else -> getString(R.string.agent_titre_ouvert)
    }

    private fun texteNotif(): String {
        if (Etat.horsLigne() && Config(this).inscrit)
            return getString(R.string.agent_texte_hors_ligne)
        if (!Etat.verrouilleEffectif() && Etat.prochainChangement > System.currentTimeMillis())
            return getString(R.string.agent_texte_fermeture, Etat.heure(Etat.prochainChangement))
        if (Etat.verrouilleEffectif() && Etat.prochainChangement > System.currentTimeMillis())
            return getString(R.string.agent_texte_reouverture, Etat.heure(Etat.prochainChangement))
        return if (manque().isNotEmpty()) getString(R.string.agent_texte_action_requise, manque()) else getString(R.string.agent_texte_protections)
    }

    /** Liste courte des protections attendues mais absentes, pour la notification. */
    private fun manque(): String {
        val m = mutableListOf<String>()
        if (!VisionAccessibility.estActive(this)) m.add(getString(R.string.agent_manque_accessibilite))
        if (!Usage.accesUsage(this)) m.add(getString(R.string.agent_manque_usage))
        // Protections reprises après une coupure : l'administrateur de l'appareil est à redonner à l'écran.
        if (Modules.actif(this, Modules.PROTECTIONS) && Config(this).assistantTermine && !AdminReceiver.estActif(this)) m.add(getString(R.string.agent_manque_desinstallation))
        return m.joinToString(", ")
    }

    private fun batterieIgnoree(): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun uid(): String = Identite.uid(this)

    /** Le nom donné à la télé (« Chambre De Alex ») ; vide sur téléphone. */
    private fun nomAppareil(): String = Identite.nom(this)

    private fun appareil(): String = Identite.appareil()

    companion object {
        @Volatile var tentatives = 0
        /** Une appli installée ou supprimée : l'inventaire part au prochain relevé. */
        @Volatile private var inventaireDemande = false

        /** Force l'envoi de la liste des applis installées et lance le relevé sans attendre. */
        fun envoyerInventaire(ctx: Context) {
            inventaireDemande = true
            if (Config(ctx).inscrit) demarrer(ctx)
        }
        private const val CANAL = "vision_fond"
        private const val NOTIF = 1

        fun demarrer(ctx: Context) {
            val i = Intent(ctx, AgentService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i)
                else ctx.startService(i)
            } catch (_: Exception) {}
        }
    }

    /** 1.88.0 : l'écran principal est-il éteint (veille du boîtier, souvent la télé éteinte par HDMI-CEC) ? null : inconnu. */
    private fun ecranEteint(): Boolean? = try {
        val dm = getSystemService(android.content.Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.state?.let { it == android.view.Display.STATE_OFF }
    } catch (_: Exception) { null }
}
