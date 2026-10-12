// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import org.json.JSONObject

/**
 * L'écran principal de l'appli : connexion à Home Assistant, puis (1.71.0, maquette validée) l'état de
 * l'appareil, les alertes s'il y en a, les gros boutons du rôle, le temps du jour, les réglages repliés,
 * une ligne pour les autorisations et une pour la version. Tout est navigable à la télécommande. Les
 * réglages sensibles sont derrière le code de gestion.
 */
class SetupActivity : Activity() {

    private lateinit var racine: LinearLayout
    private val cfg by lazy { Config(this) }

    /** Une protection à accorder : son état et le réglage système qui l'active. */
    private inner class Protection(val titre: String, val detail: String, val ok: () -> Boolean,
                                   /** Ce qu'on perd sans elle. */ val sans: String = "",
                                   /** Faux quand cet appareil n'a pas l'écran de réglage qui l'accorde (souvent sur télé). */ val possible: () -> Boolean = { true },
                                   val ouvrir: () -> Unit)

    private fun existe(i: Intent): Boolean = try { i.resolveActivity(packageManager) != null } catch (_: Exception) { false }
    private val pkgUri get() = Uri.parse("package:$packageName")

    private fun protections(): List<Protection> {
        val l = ArrayList<Protection>()
        val tele = ReglagesTvActivity.estTele(this)
        l.add(Protection(getString(R.string.setup_p_usage), getString(R.string.setup_p_usage_detail), { Usage.accesUsage(this) },
            getString(R.string.setup_p_usage_sans),
            { existe(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { ouvrir(Settings.ACTION_USAGE_ACCESS_SETTINGS) })
        l.add(Protection(getString(R.string.setup_p_access), getString(R.string.setup_p_access_detail), { VisionAccessibility.estActive(this) },
            getString(if (tele) R.string.setup_p_access_sans_tele else R.string.setup_p_access_sans),
            { existe(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { ouvrir(Settings.ACTION_ACCESSIBILITY_SETTINGS) })
        l.add(Protection(getString(R.string.setup_p_overlay), getString(R.string.setup_p_overlay_detail), { peutRecouvrir() },
            getString(if (tele) R.string.setup_p_overlay_sans_tele else R.string.setup_p_overlay_sans),
            { Build.VERSION.SDK_INT < 23 || existe(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri)) }) { demanderOverlay() })
        if (tele) l.add(Protection(getString(R.string.setup_p_lecture), getString(R.string.setup_p_lecture_detail), { Flux.droit(this) },
            getString(R.string.setup_p_lecture_sans),
            { existe(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { ouvrir(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) })
        // Modules coupés dans Home Assistant : leur étape disparaît (filtrage, protections, accueil, veille).
        if (Modules.actif(this, Modules.FILTRAGE)) l.add(Protection(getString(R.string.setup_p_filtre), getString(R.string.setup_p_filtre_detail), { DnsVpnService.actif || DnsVpnService.autorise(this) },
            getString(R.string.setup_p_filtre_sans),
            { try { VpnService.prepare(this)?.let { existe(it) } ?: true } catch (_: Exception) { false } }) { demanderVpn() })
        if (Modules.actif(this, Modules.PROTECTIONS)) l.add(Protection(getString(R.string.setup_p_admin), getString(R.string.setup_p_admin_detail), { AdminReceiver.estActif(this) },
            getString(R.string.setup_p_admin_sans),
            { existe(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)) }) { demanderAdmin() })
        if (Build.VERSION.SDK_INT >= 23)
            l.add(Protection(getString(R.string.setup_p_batterie), getString(R.string.setup_p_batterie_detail), { batterieIgnoree() },
                getString(R.string.setup_p_batterie_sans),
                { existe(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri)) || existe(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) { demanderBatterie() })
        // Point 7 : Vision comme accueil, par la boîte officielle d'Android (sans confusion avec l'ancienne Vision).
        if (!tele && Modules.actif(this, Modules.ACCUEIL)) l.add(Protection(getString(R.string.setup_p_accueil), getString(R.string.setup_p_accueil_detail), { AccueilDefaut.estAccueil(this) },
            getString(if (Migration.ancienneInstallee(this)) R.string.setup_p_accueil_sans_ancienne else R.string.setup_p_accueil_sans),
            { AccueilDefaut.possible(this) }) { AccueilDefaut.demander(this) })
        l.add(Protection(getString(R.string.setup_p_maj), getString(R.string.setup_p_maj_detail), { MiseAJour.peutInstaller(this) },
            getString(R.string.setup_p_maj_sans),
            { Build.VERSION.SDK_INT < 26 || existe(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, pkgUri)) }) { demanderInstallation() })
        // Télé : l'économiseur d'écran du système est-il Vision ? Sinon, son réglage, ou la veille de repli sur l'accueil.
        val veille = Modules.actif(this, Modules.VEILLE)
        if (tele && veille) l.add(Protection(getString(R.string.eco_titre), getString(R.string.setup_p_veille_detail), { EconomiseurTv.regle(this) },
            getString(R.string.setup_p_veille_sans))
            { EconomiseurTv.proposer(this) { if (assistant) avancerAssistant() else dessiner() } })
        if (tele && veille && Build.VERSION.SDK_INT >= 23)
            l.add(Protection(getString(R.string.setup_p_systeme), getString(R.string.setup_p_systeme_detail), { Local.peutEcrireSysteme(this) },
                getString(R.string.setup_p_systeme_sans),
                { existe(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkgUri)) }) { try { startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkgUri)) } catch (_: Exception) {} })
        return l
    }

    /** Assistant : on enchaîne les réglages manquants, un par un, au retour de chaque écran. */
    private var assistant = false
    /**
     * Étape dédiée « Autoriser les paramètres restreints » : le titre de la permission qu'Android vient de
     * refuser (vide sinon). Au retour d'Infos de l'appli, l'assistant redemande cette permission tout seul.
     */
    private var restreintsPour: String = ""
    private var retourInfos = false
    /** Les permissions qu'Android peut retenir pour une appli installée depuis un fichier. */
    private val restreignables get() = setOf(getString(R.string.setup_p_usage), getString(R.string.setup_p_access),
        getString(R.string.setup_p_lecture), getString(R.string.setup_p_admin))
    private var assistantDerniere: String = ""
    private var rechercheFaite = false
    /** La carte de recherche affichée (APP-03/05) : repeinte quand la recherche aboutit. */
    private var peindreDecouverte: (() -> Unit)? = null
    /** Module « pause » coupé : les réglages de gestion ouverts par le code (en mémoire, le temps de cet écran). */
    private var gestionOuverte = false
    /** 1.71.0 : ce qui est déplié sur l'écran principal (le temps par appareil, l'espace gestion, la liste des autorisations). */
    private var tempsDeplie = false
    private var gestionDepliee = false
    private var autorisationsDepliees = false
    /** APP-02 : les voies de liaison secondaires (bloc collé, adresse + clé…) dépliées sous « Autres méthodes ». */
    private var autresDepliees = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Ui.charger(this)
        // 1.84.0 : ouvert depuis la section « Décideurs » des réglages télé : l'espace gestion déjà déplié.
        if (intent?.getBooleanExtra(EXTRA_GESTION, false) == true) gestionDepliee = true
        if (Build.VERSION.SDK_INT >= 21) {
            window.statusBarColor = Ui.FOND
            window.navigationBarColor = Ui.FOND
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Ui.FOND)
            isFillViewport = true
        }
        racine = Ui.colonne(this, Ui.dp(this, 20f))
        // 1.71.0 : sur un grand écran (télé, tablette en paysage), une colonne centrée d'au plus 760 dp, lisible de loin.
        val largeur = resources.configuration.screenWidthDp
        if (largeur > 800) { val cote = Ui.dp(this, (largeur - 760) / 2f); racine.setPadding(cote, racine.paddingTop, cote, racine.paddingBottom) }
        scroll.addView(racine)
        setContentView(scroll)
        if (Build.VERSION.SDK_INT >= 33) {
            try { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 9) } catch (_: Exception) {}
        }
        traiterIntent(intent)
    }

    override fun onNewIntent(i: Intent?) {
        super.onNewIntent(i)
        traiterIntent(i)
    }

    /** Toucher de la notification « nouvelle version » : on ouvre l'installation. Ou une page partagée vers Vision. */
    private fun traiterIntent(i: Intent?) {
        // Relier sans clavier (télé, box) : « adb shell am start -n …/.SetupActivity --es bloc "adresse: … cle: …" ».
        // Accepté seulement tant que l'appareil n'est pas inscrit : ensuite, un bloc n'a plus rien à faire ici.
        val bloc = i?.getStringExtra(EXTRA_BLOC)
        if (!bloc.isNullOrBlank() && !cfg.inscrit) {
            i.removeExtra(EXTRA_BLOC)
            val (externe, interne, cle) = analyserBloc(bloc)
            if ((externe.isNotEmpty() || interne.isNotEmpty()) && cle.isNotEmpty()) {
                cfg.urlExterne = externe.ifEmpty { interne }
                cfg.urlInterne = interne
                cfg.cleInscription = cle
                i.getStringExtra(EXTRA_UTILISATEUR)?.let { cfg.utilisateur = it.trim() }
                toast(getString(R.string.setup_connexion))
                AgentService.demarrer(this)
                racine.postDelayed({ dessiner() }, 2500)
            } else toast(getString(R.string.setup_bloc_incomplet))
            return
        }
        if (i?.action == Intent.ACTION_SEND && cfg.inscrit) {
            val texte = i.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            val lien = Regex("https?://\\S+").find(texte)?.value ?: texte.trim()
            val hote = Acces.hoteDe(lien)
            i.action = ""
            if (!Modules.actif(this, Modules.DEMANDES)) { dessiner(); return }
            if (hote.isNotEmpty()) Acces.dialogue(this, "sites", hote, hote, lien) { dessiner() }
            else toast(getString(R.string.setup_aucun_lien))
            return
        }
        val nom = i?.getStringExtra(MiseAJour.EXTRA_INSTALLER) ?: return
        i.removeExtra(MiseAJour.EXTRA_INSTALLER)
        val f = java.io.File(MiseAJour.dossier(this), nom)
        if (f.isFile) MiseAJour.installer(this, f) else toast(getString(R.string.setup_fichier_maj_introuvable))
    }

    override fun onResume() {
        super.onResume(); auPremierPlan = true
        Assistant.retour()
        if (assistant) avancerAssistant() else dessiner()
    }

    /** Ouvre l'écran système d'une étape ; pendant le premier lancement, Vision ne le bloque pas. */
    private fun ouvrirEtape(p: Protection) { Assistant.ouvrir(this); p.ouvrir() }

    private fun avancerAssistant() {
        val manquantes = protections().filter { !it.ok() && it.possible() }
        if (manquantes.isEmpty()) {
            assistant = false; assistantDerniere = ""
            Assistant.terminer(this)
            toast(getString(R.string.setup_tout_en_place)); dessiner(); return
        }
        val suivante = manquantes.first()
        if (retourInfos) {
            // Retour d'Infos de l'appli : on redemande aussitôt la permission refusée.
            retourInfos = false
            if (restreintsPour == suivante.titre) {
                restreintsPour = ""
                assistantDerniere = suivante.titre
                dessiner()
                racine.postDelayed({ if (assistant && !suivante.ok()) ouvrirEtape(suivante) }, 450)
                return
            }
        }
        // Revenu sans l'avoir obtenue : si Android a pu la retenir (APK venu d'un fichier), l'étape dédiée.
        if (suivante.titre == assistantDerniere && suivante.titre in restreignables && Migration.refusPeutVenirDesRestreints(this)) {
            restreintsPour = suivante.titre
            dessiner()
            return
        }
        if (suivante.titre != restreintsPour) restreintsPour = ""
        dessiner()
        // On n'ouvre automatiquement que si l'étape précédente a été accordée :
        // sinon l'utilisateur a refusé ou s'est perdu, on lui laisse la main.
        if (suivante.titre != assistantDerniere) {
            assistantDerniere = suivante.titre
            racine.postDelayed({ if (assistant && !suivante.ok()) ouvrirEtape(suivante) }, 450)
        }
    }
    override fun onPause() { auPremierPlan = false; gestionOuverte = false; MiseAJour.surEtat = null; super.onPause() }

    // ------------------------------------------------------------- Écran

    private fun dessiner() {
        racine.removeAllViews()
        entete()
        if (!cfg.inscrit) { carteNonRelie(); ecranConnexion(); return }

        // 1.71.0 (maquette validée en essai) : l'état, les alertes s'il y en a, les actions du rôle, le temps,
        // les réglages repliés, puis une ligne pour les autorisations et une pour la version.
        carteEtat()

        if (cfg.personne.isEmpty() && cfg.personnes.isNotEmpty()) { choixPersonne(); return }

        val toutes = protections()
        val manquantes = toutes.filter { !it.ok() && it.possible() }
        val impossibles = toutes.filter { !it.ok() && !it.possible() }

        bandeauMiseAJour()
        if (manquantes.isNotEmpty()) alerteAutorisations(toutes, manquantes, impossibles)
        alertesAccueil()

        if (Etat.parent) actionsDecideur() else actionsDemandeur()
        monTemps()
        reglagesReplies()
        ligneAutorisations(toutes, manquantes, impossibles)
        ligneVersion()
        racine.addView(Ui.espace(this, 24f))
    }

    private fun entete() {
        racine.addView(Ui.marque(this))
        val col = Ui.colonne(this)
        col.addView(Ui.titre(this, if (cfg.inscrit) cfg.nom.ifEmpty { cfg.host.ifEmpty { "Vision" } } else getString(R.string.setup_bienvenue)))
        col.addView(Ui.sousTitre(this, if (cfg.inscrit) listOf(cfg.utilisateur.ifEmpty { getString(R.string.setup_ecran_partage) }, Vocabulaire.lieu(this)).filter { it.isNotEmpty() }.joinToString(" · ")
            else getString(R.string.setup_relier)))
        racine.addView(Ui.marge(this, col, bas = 18f))
    }

    /** La grande carte : ouvert, fermé ou en pause, ce qu'il reste aujourd'hui, et ce qui vient. */
    private fun carteEtat() {
        val maintenant = System.currentTimeMillis()
        val horsLigne = Etat.horsLigne()
        val ferme = Etat.verrouilleEffectif()
        val pause = cfg.modeParent
        val prochain = Etat.prochainChangement.takeIf { it > maintenant } ?: 0L
        val couleur = when { horsLigne -> Ui.TEXTE_2; pause -> Ui.OR; ferme -> Ui.ROUGE; else -> Ui.VERT }
        val libelle = when {
            horsLigne -> getString(R.string.commun_hors_ligne)
            pause -> if (memeJour(cfg.parentJusqua)) getString(R.string.statut_pause_jusqua, Tr.heure(cfg.parentJusqua)) else getString(R.string.statut_suspendue)
            ferme -> getString(R.string.statut_ferme)
            else -> getString(R.string.statut_ouvert)
        }
        val c = Ui.carte(this, Ui.CARTE_HAUTE)
        val haut = Ui.rangee(this)
        haut.addView(Ui.marge(this, android.view.View(this).apply {
            val d = Ui.dp(this@SetupActivity, 14f)
            layoutParams = LinearLayout.LayoutParams(d, d)
            background = Ui.fond(couleur, d / 2f)
        }, droite = 12f))
        haut.addView(Ui.poids(Ui.texte(this, libelle, 24f, couleur, gras = true)))
        c.addView(haut)
        val lignes = ArrayList<String>()
        when {
            horsLigne -> lignes.add(getString(R.string.statut_hors_ligne_texte))
            pause -> lignes.add(getString(R.string.setup_reprend_seule, Etat.quand(cfg.parentJusqua)))
            ferme -> {
                lignes.add(Etat.message.ifEmpty { getString(R.string.setup_acces_ferme_regles) })
                if (prochain > 0) lignes.add(if (prochain - maintenant < 24 * 3600_000L)
                    getString(R.string.setup_reouverture_dans, Etat.quand(prochain), Temps.duree(minutesJusqua(prochain)))
                    else getString(R.string.setup_reouverture, Etat.quand(prochain)))
            }
            prochain > 0 && prochain - maintenant < 24 * 3600_000L ->
                lignes.add(getString(R.string.setup_encore, Temps.duree(minutesJusqua(prochain))))
            else -> lignes.add(getString(R.string.statut_aucune_fermeture))
        }
        if (!horsLigne && !pause && !ferme && prochain > 0 && prochain - maintenant < 24 * 3600_000L)
            lignes.add(getString(R.string.setup_ensuite_ferme, Etat.heure(prochain)))
        lignes.forEachIndexed { k, t ->
            c.addView(Ui.marge(this, Ui.texte(this, t, if (k == 0) 16f else 14f, if (k == 0) Ui.TEXTE else Ui.TEXTE_2, gras = k == 0), haut = if (k == 0) 10f else 4f))
        }
        racine.addView(c)
    }

    /** APP-01 : la carte d'état d'un appareil pas encore relié (téléphone, tablette, TV) : rien n'y est protégé. */
    private fun carteNonRelie() {
        val c = Ui.carte(this, Ui.CARTE_HAUTE).apply { tag = "carte_non_relie" }
        val haut = Ui.rangee(this)
        haut.addView(Ui.marge(this, android.view.View(this).apply {
            val d = Ui.dp(this@SetupActivity, 14f)
            layoutParams = LinearLayout.LayoutParams(d, d)
            background = Ui.fond(0, d / 2f, Ui.dp(this@SetupActivity, 2f), Ui.TEXTE_2)
        }, droite = 12f))
        haut.addView(Ui.poids(Ui.texte(this, getString(R.string.nonrelie_titre), 24f, Ui.TEXTE, gras = true)))
        c.addView(haut)
        c.addView(Ui.marge(this, Ui.texte(this, getString(R.string.nonrelie_carte_texte), 15f, Ui.TEXTE_2), haut = 8f))
        racine.addView(c)
    }

    /** Vrai si l'instant tombe aujourd'hui (pour dire « jusqu'à 18:30 » sans date). */
    private fun memeJour(ms: Long): Boolean {
        val a = java.util.Calendar.getInstance(); val b = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        return a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) && a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR)
    }

    private fun minutesJusqua(t: Long): Int = ((t - System.currentTimeMillis() + 59_999) / 60_000).toInt().coerceAtLeast(1)

    /** Une mise à jour prête : un bandeau or tout en haut, avec « Installer ». */
    private fun bandeauMiseAJour() {
        val dispo = MiseAJour.disponible
        if (dispo.isEmpty() && !MiseAJour.apkPret()) return
        val c = Ui.carte(this, Ui.CARTE_HAUTE)
        c.addView(Ui.texte(this, if (dispo.isNotEmpty()) getString(R.string.setup_maj_prete_version, dispo) else getString(R.string.setup_maj_prete), 16f, Ui.OR, gras = true))
        val etat = Ui.texte(this, MiseAJour.etat.ifEmpty { getString(R.string.setup_maj_un_geste) }, 13f, Ui.TEXTE_2)
        c.addView(Ui.marge(this, etat, haut = 4f))
        MiseAJour.surEtat = { t -> etat.text = t }
        val r = Ui.rangeeBoutons(this)
        r.addView(Ui.boutonPrimaire(this, getString(R.string.setup_installer)) {
            etat.text = getString(if (dispo.isNotEmpty() && !MiseAJour.apkPret()) R.string.setup_telechargement else R.string.setup_installation)
            cfg.majProposee = ""
            MiseAJour.installerAvecConfirmation(this, cfg)
        })
        if (MiseAJour.detail.isNotEmpty()) r.addView(Ui.marge(this, Ui.boutonSecondaire(this, getString(R.string.setup_details)) {
            Ui.dialogue(this).setTitle(R.string.setup_details).setMessage(MiseAJour.detail).setPositiveButton(R.string.commun_ok, null).show()
        }, gauche = 8f))
        c.addView(Ui.marge(this, r, haut = 12f))
        racine.addView(c)
    }

    /** Il manque une autorisation : en haut, avec l'assistant et l'action directe sur chacune. */
    private fun alerteAutorisations(toutes: List<Protection>, manquantes: List<Protection>, impossibles: List<Protection>) {
        val c = Ui.carte(this, Ui.CARTE_HAUTE)
        val etape = manquantes.first()
        if (assistant) {
            val total = toutes.size - impossibles.size
            val faites = total - manquantes.size
            if (restreintsPour == etape.titre) {
                c.addView(Ui.texte(this, getString(R.string.setup_etape_restreints), 16f, Ui.OR, gras = true))
                c.addView(Ui.marge(this, Ui.texte(this, getString(R.string.setup_restreints_texte, etape.titre), 14f, Ui.TEXTE), haut = 6f))
                c.addView(Ui.marge(this, Ui.texte(this, getString(R.string.setup_restreints_pas_de_menu), 13f, Ui.TEXTE_2), haut = 8f))
                c.addView(Ui.marge(this, Ui.boutonPrimaire(this, getString(R.string.setup_ouvrir_infos)) {
                    retourInfos = true
                    // Infos de l'appli porte « Désinstaller » : pendant l'assistant, Vision ne le ferme pas.
                    Assistant.ouvrir(this)
                    Migration.ouvrirInfosAppli(this)
                }, haut = 14f))
                val r = Ui.rangeeBoutons(this)
                r.addView(Ui.poids(Ui.boutonSecondaire(this, getString(R.string.commun_reessayer)) {
                    restreintsPour = ""; assistantDerniere = etape.titre; ouvrirEtape(etape)
                }))
                r.addView(Ui.marge(this, Ui.boutonSecondaire(this, getString(R.string.setup_arreter)) { assistant = false; restreintsPour = ""; dessiner() }, gauche = 8f))
                c.addView(Ui.marge(this, r, haut = 8f))
            } else {
                c.addView(Ui.texte(this, getString(R.string.setup_etape_n, faites.plus(1), total, etape.titre), 16f, Ui.OR, gras = true))
                c.addView(Ui.marge(this, Ui.texte(this, etape.detail, 14f, Ui.TEXTE_2), haut = 6f))
                val r = Ui.rangeeBoutons(this)
                r.addView(Ui.poids(Ui.boutonPrimaire(this, getString(R.string.setup_ouvrir_reglage)) { ouvrirEtape(etape) }))
                r.addView(Ui.marge(this, Ui.boutonSecondaire(this, getString(R.string.setup_arreter)) { assistant = false; dessiner() }, gauche = 8f))
                c.addView(Ui.marge(this, r, haut = 14f))
            }
            racine.addView(c)
            return
        }
        val n = manquantes.size
        c.addView(Ui.texte(this, resources.getQuantityString(R.plurals.setup_n_manquent, n, n), 16f, Ui.OR, gras = true))
        c.addView(Ui.marge(this, Ui.texte(this, getString(R.string.setup_sans_elles), 13f, Ui.TEXTE_2), haut = 4f))
        c.addView(Ui.marge(this, Ui.boutonPrimaire(this, getString(R.string.setup_corriger)) {
            assistant = true; assistantDerniere = ""; avancerAssistant()
        }, haut = 12f))
        // Trois au plus ici, pour que les gros boutons restent en vue ; les autres sont dans « Toutes les autorisations ».
        manquantes.take(3).forEach { pr ->
            c.addView(Ui.marge(this, large(Ui.separateur(this)), haut = 12f))
            c.addView(Ui.marge(this, large(ligneProtection(pr.titre, pr.detail, false, { ouvrirEtape(pr) }, pr.sans, true)), haut = 12f))
        }
        if (n > 3) c.addView(Ui.marge(this, Ui.texte(this, resources.getQuantityString(R.plurals.setup_et_autres, n - 3, n - 3), 13f, Ui.TEXTE_2), haut = 10f))
        // L'aide « paramètres restreints » : seulement quand une autorisation qu'Android peut retenir est refusée.
        if (Build.VERSION.SDK_INT >= 31 && manquantes.any { it.titre in restreignables }) {
            c.addView(Ui.marge(this, Ui.texte(this, getString(R.string.setup_acces_refuse_aide), 13f, Ui.TEXTE_3), haut = 12f))
            c.addView(Ui.marge(this, Ui.boutonSecondaire(this, getString(R.string.setup_ouvrir_infos)) { Migration.ouvrirInfosAppli(this) }, haut = 8f))
        }
        racine.addView(c)
    }

    /** L'ancienne Vision encore là, ou Vision encore accueil alors que la fonction est coupée : sous l'état. */
    private fun alertesAccueil() {
        if (Migration.ancienneInstallee(this)) {
            val a = Ui.carte(this, Ui.CARTE_HAUTE)
            a.addView(Ui.texte(this, getString(R.string.setup_ancienne_titre), 16f, Ui.TEXTE, gras = true))
            a.addView(Ui.marge(this, Ui.texte(this, getString(R.string.setup_ancienne_texte), 13f, Ui.TEXTE_2), haut = 4f))
            a.addView(Ui.marge(this, Ui.boutonPrimaire(this, getString(R.string.setup_ancienne_supprimer)) {
                if (!Migration.supprimerAncienne(this)) toast(getString(R.string.setup_desinstallation_impossible))
            }, haut = 12f))
            racine.addView(a)
        }
        if (!Modules.actif(this, Modules.ACCUEIL) && AccueilDefaut.possible(this) && AccueilDefaut.estAccueil(this)) {
            val ad = Ui.carte(this, Ui.CARTE_HAUTE)
            ad.addView(Ui.texte(this, getString(R.string.setup_encore_accueil), 16f, Ui.TEXTE, gras = true))
            ad.addView(Ui.marge(this, Ui.texte(this, getString(R.string.setup_encore_accueil_texte), 14f, Ui.TEXTE_2), haut = 6f))
            ad.addView(Ui.marge(this, Ui.boutonPrimaire(this, getString(R.string.setup_rendre_accueil)) { AccueilDefaut.rendreOrigine(this) }, haut = 12f))
            racine.addView(ad)
        }
    }

    // ------------------------------------------------- Actions selon le rôle

    /** Deux gros boutons côte à côte ; empilés quand la place manque (petit écran, grands caractères). */
    private fun paire(a: View, b: View?) {
        val r = Ui.rangeeBoutons(this)
        r.addView(Ui.poids(a))
        if (b != null) r.addView(Ui.marge(this, Ui.poids(b), gauche = 10f))
        racine.addView(Ui.marge(this, r, bas = 10f))
    }

    private fun grille(tuiles: List<View>) {
        tuiles.chunked(2).forEach { paire(it[0], it.getOrNull(1)) }
        if (tuiles.isNotEmpty()) racine.addView(Ui.espace(this, 6f))
    }

    /** Décideur : demandes, tous les appareils, pause générale, ajouter du temps (selon les modules et le rôle). */
    private fun actionsDecideur() {
        chargerResume()
        val r = resumeGestion
        val m = Modules.actifs(this)
        val tuiles = ArrayList<View>()
        if (Modules.actifDans(m, Modules.DEMANDES) || Modules.actifDans(m, Modules.DEVERROUILLAGE)) {
            val n = r?.optJSONArray("demandes")?.length() ?: -1
            tuiles.add(Ui.tuile(this, if (n > 0) getString(R.string.setup_demandes_n, n) else getString(R.string.setup_demandes),
                when { n < 0 -> getString(R.string.setup_demandes_detail); n == 0 -> getString(R.string.setup_demandes_aucune)
                    else -> resources.getQuantityString(R.plurals.setup_a_traiter, n, n) }, n > 0) {
                ParentsActivity.ouvrir(this)
            })
        }
        tuiles.add(Ui.tuile(this, getString(R.string.setup_tous_appareils), resumeAppareils(r) ?: getString(R.string.setup_tous_appareils_detail), false) { ParentsActivity.ouvrir(this) })
        if (PauseGenerale.visible(Modules.actifDans(m, Modules.PAUSE_GENERALE), Profil.fonctions(this))) {
            val fin = r?.let { PauseGenerale.lire(it) }?.takeIf { it > System.currentTimeMillis() }
            tuiles.add(Ui.tuile(this, getString(R.string.parents_pause_generale), if (fin != null) getString(R.string.setup_en_cours_jusqua, PauseGenerale.heure(fin)) else getString(R.string.setup_fermer_tous), fin != null) {
                ParentsActivity.ouvrir(this, if (fin != null) null else ParentsActivity.A_PAUSE)
            })
        }
        if (Modules.horaires(m)) tuiles.add(Ui.tuile(this, getString(R.string.setup_ajouter_temps), getString(R.string.setup_choisir_appareil), false) {
            ParentsActivity.ouvrir(this, ParentsActivity.A_TEMPS)
        })
        // 2.0.0 (notifications v2) : écrire à la maison, et voir qui a répondu.
        if (Modules.actif(this, Modules.MESSAGES)) tuiles.add(Ui.tuile(this, getString(R.string.msgenv_titre), getString(R.string.setup_message_detail), false) {
            startActivity(Intent(this, MessageEnvoiActivity::class.java))
        })
        grille(tuiles)
    }

    /** « 5 ouverts · 1 fermé » sur les appareils suivis (hors ceux des gestionnaires), ou null sans relevé. */
    private fun resumeAppareils(r: JSONObject?): String? {
        val a = r?.optJSONArray("appareils") ?: return null
        val suivis = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { !it.optBoolean("parent") }
        if (suivis.isEmpty()) return getString(R.string.setup_aucun_suivi)
        val horsLigne = suivis.count { !it.optBoolean("en_ligne") }
        val fermes = suivis.count { it.optBoolean("en_ligne") && it.optBoolean("verrouille") }
        val ouverts = suivis.size - horsLigne - fermes
        return listOfNotNull(
            if (ouverts > 0) resources.getQuantityString(R.plurals.parents_n_ouverts, ouverts, ouverts) else null,
            if (fermes > 0) resources.getQuantityString(R.plurals.parents_n_fermes, fermes, fermes) else null,
            if (horsLigne > 0) resources.getQuantityString(R.plurals.setup_n_hors_ligne, horsLigne, horsLigne) else null,
        ).joinToString(" · ")
    }

    /** Le relevé de gestion (lecture seule, comme l'écran « Tous les appareils ») : au plus toutes les 20 s. */
    private fun chargerResume() {
        val maintenant = System.currentTimeMillis()
        if (maintenant - resumeA < 20_000) return
        resumeA = maintenant
        Thread {
            try {
                val r = Net.post(this, cfg, "/api/pc_parental/parent", JSONObject().put("id", cfg.id).put("secret", cfg.secret))
                resumeGestion = r
                runOnUiThread { if (!isFinishing && auPremierPlan && !assistant) dessiner() }
            } catch (_: Exception) {}
        }.start()
    }

    /** Demandeur : demander du temps, demander un déverrouillage (module « Demandes » actif). */
    private fun actionsDemandeur() {
        if (cfg.modeParent || !Modules.actif(this, Modules.DEMANDES)) return
        val m = Modules.actifs(this)
        val tuiles = ArrayList<View>()
        val envoyee = cfg.demandeTemps > 0 || System.currentTimeMillis() - demandeTempsA < 10 * 60_000L
        if (Modules.horaires(m)) tuiles.add(Ui.tuile(this, getString(R.string.setup_demander_temps),
            if (envoyee) getString(R.string.setup_demande_envoyee_detail, Vocabulaire.decideur(this)) else getString(R.string.setup_durees_exemple, Tr.duree(15), Tr.duree(30), Tr.duree(60)), false) { demanderTemps() })
        val nb = Etat.recentsBloques().size
        val deverrouiller = Modules.actifDans(m, Modules.DEVERROUILLAGE)
        tuiles.add(Ui.tuile(this, getString(R.string.deva_titre),
            if (nb > 0) resources.getQuantityString(R.plurals.setup_fermes_recemment, nb, nb) else getString(R.string.setup_deverrouillage_detail), false) {
            if (!deverrouiller) startActivity(Intent(this, FermesActivity::class.java))
            else Ui.dialogue(this).setTitle(R.string.deva_titre)
                .setItems(arrayOf(if (nb > 0) getString(R.string.setup_appli_site_ferme_n, nb) else getString(R.string.setup_appli_site_ferme), getString(R.string.setup_tout_appareil))) { _, k ->
                    if (k == 0) startActivity(Intent(this, FermesActivity::class.java)) else DeverrouillageActivity.ouvrir(this, "parent")
                }.setNegativeButton(R.string.commun_annuler, null).show()
        })
        // Une colonne : deux gros boutons l'un sous l'autre, lisibles de loin sur une télé.
        tuiles.forEach { racine.addView(Ui.marge(this, it.apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }, bas = 10f)) }
        racine.addView(Ui.espace(this, 6f))
    }

    private fun demanderTemps() {
        val choix = listOf(15 to resources.getQuantityString(R.plurals.temps_minutes, 15, 15), 30 to resources.getQuantityString(R.plurals.temps_minutes, 30, 30),
            60 to resources.getQuantityString(R.plurals.temps_heures, 1, 1), 120 to resources.getQuantityString(R.plurals.temps_heures, 2, 2))
        Ui.dialogue(this).setTitle(R.string.setup_demander_temps)
            .setItems(choix.map { it.second }.toTypedArray()) { _, k ->
                cfg.demandeTemps = choix[k].first
                demandeTempsA = System.currentTimeMillis()
                AgentService.demarrer(this)
                Ui.dialogue(this).setTitle(R.string.deva_envoyee)
                    .setMessage(getString(R.string.setup_demande_temps_envoyee, Vocabulaire.decideur(this)))
                    .setPositiveButton(R.string.commun_ok, null).show()
                dessiner()
            }.setNegativeButton(R.string.commun_annuler, null).show()
    }

    // ------------------------------------------------- Temps, réglages, autorisations, version

    /** « Mon temps aujourd'hui » en une ligne ; le détail par appareil se déplie. Absent sur un écran partagé. */
    private fun monTemps() {
        val t = Profil.temps(this)?.takeIf { Modules.actif(this, Modules.TEMPS) } ?: return
        val a = t.optJSONArray("appareils")
        val plusieurs = a != null && a.length() > 1
        val c = Ui.carte(this)
        val r = Ui.rangee(this)
        r.addView(Ui.poids(Ui.texte(this, getString(R.string.setup_mon_temps), 15f, Ui.TEXTE, gras = true)))
        r.addView(Ui.marge(this, Ui.texte(this, Profil.duree(t.optInt("actif")), 20f, Ui.OR, gras = true), gauche = 12f))
        if (plusieurs) r.addView(Ui.marge(this, Ui.texte(this, if (tempsDeplie) "▾" else "▸", 18f, Ui.TEXTE_2), gauche = 10f))
        c.addView(r)
        if (plusieurs) {
            c.isFocusable = true; c.isClickable = true
            c.contentDescription = getString(if (tempsDeplie) R.string.parents_replier else R.string.setup_voir_detail, getString(R.string.setup_mon_temps))
            c.setOnClickListener { tempsDeplie = !tempsDeplie; dessiner() }
            if (tempsDeplie) {
                for (i in 0 until a!!.length()) {
                    val x = a.optJSONObject(i) ?: continue
                    c.addView(Ui.marge(this, Ui.texte(this, getString(R.string.msgenv_etat, x.optString("nom"), Profil.duree(x.optInt("actif"))), 14f, Ui.TEXTE_2), haut = 6f))
                }
                c.addView(Ui.marge(this, Ui.texte(this, getString(R.string.setup_tes_appareils), 12f, Ui.TEXTE_3), haut = 8f))
            }
        }
        racine.addView(c)
    }

    /** Une ligne de réglage : titre, état en clair, et « › » (ou ▸ / ▾ quand elle se déplie sur place). */
    private fun ligneReglage(titre: String, detail: String, signe: String = "›", action: () -> Unit): View =
        Ui.tuile(this, "$titre  $signe", detail, false, action).apply {
            minHeight = Ui.dp(this@SetupActivity, 60f); minimumHeight = minHeight
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = Ui.dp(this@SetupActivity, 8f) }
        }

    private fun reglagesReplies() {
        racine.addView(Ui.section(this, getString(R.string.setup_reglages)))
        val tele = ReglagesTvActivity.estTele(this)
        racine.addView(ligneReglage(getString(R.string.setup_apparence), getString(R.string.setup_apparence_detail)) { ReglagesTvActivity.ouvrir(this, "s_apparence") })
        val accueilActif = Modules.actif(this, Modules.ACCUEIL)
        val detailAccueil = when {
            tele -> getString(R.string.setup_accueil_detail_tele)
            accueilActif && AccueilDefaut.possible(this) -> getString(if (AccueilDefaut.estAccueil(this)) R.string.setup_accueil_detail_oui else R.string.setup_accueil_detail_non)
            else -> getString(R.string.setup_accueil_detail)
        }
        racine.addView(ligneReglage(getString(if (tele) R.string.setup_accueil else R.string.setup_accueil_widgets), detailAccueil) { ReglagesTvActivity.ouvrir(this, "s_accueil") })
        if (!Identite.telephone(this) && Modules.actif(this, Modules.VEILLE))
            racine.addView(ligneReglage(getString(R.string.eco_titre), getString(if (Modules.actif(this, Modules.VEILLE_TABLEAUX)) R.string.setup_veille_detail_tableaux else R.string.setup_veille_detail)) {
                ReglagesTvActivity.ouvrir(this, "s_veille")
            })
        // 1.76.0 (point 6 bis) : le statut permanent dans les notifications, quel que soit le lanceur.
        if (Modules.actif(this, Modules.TEMPS)) racine.addView(ligneReglage(getString(R.string.setup_statut_notif),
            getString(if (StatutNotif.voulu(this)) R.string.setup_statut_notif_affiche else R.string.setup_masque)) {
            StatutNotif.poser(this, !StatutNotif.voulu(this)); AgentService.demarrer(this); dessiner()
        })
        // 2.0.0 (notifications v2, décision du projet) : le son des messages, choisi par la personne sur son appareil.
        if (Modules.actif(this, Modules.MESSAGES)) racine.addView(ligneReglage(getString(R.string.setup_son_messages), MessagesV2.nomSon(MessagesV2.sonAppareil(this))) {
            val s = MessagesV2.SONS
            Ui.dialogue(this).setTitle(R.string.setup_son_messages)
                .setSingleChoiceItems(s.map { MessagesV2.nomSon(it) }.toTypedArray(), s.indexOf(MessagesV2.sonAppareil(this))) { d, k ->
                    MessagesV2.noterSon(this, s[k]); d.dismiss(); dessiner()
                }.setNegativeButton(R.string.commun_annuler, null).show()
        })
        // 1.79.0 (point 13 bis) : l'écran verrouillé aux couleurs de Vision, et ce qu'il montre du statut.
        if (EcranVerrou.possible(this) && cfg.inscrit) {
            racine.addView(ligneReglage(getString(R.string.setup_verrou),
                getString(if (EcranVerrou.voulu(this)) R.string.setup_verrou_fond_vision else R.string.setup_verrou_fond_origine)) {
                if (EcranVerrou.voulu(this)) { EcranVerrou.activer(this, false); dessiner() }
                else Ui.dialogue(this).setTitle(R.string.setup_verrou_question)
                    .setMessage(R.string.setup_verrou_texte)
                    .setNegativeButton(R.string.commun_annuler, null)
                    .setPositiveButton(R.string.setup_verrou_habiller) { _, _ -> EcranVerrou.activer(this, true); dessiner() }.show()
            })
            if (Modules.actif(this, Modules.TEMPS)) racine.addView(ligneReglage(getString(R.string.setup_verrou_detail),
                getString(if (EcranVerrou.detailVisible(this)) R.string.setup_verrou_detail_affiche else R.string.setup_verrou_detail_masque)) {
                EcranVerrou.poserDetail(this, !EcranVerrou.detailVisible(this)); AgentService.demarrer(this); dessiner()
            })
        }
        racine.addView(ligneReglage(getString(R.string.setup_ouvrir_accueil), getString(R.string.setup_ouvrir_accueil_detail)) {
            startActivity(Intent(this, LanceurActivity::class.java))
        })
        // L'espace gestion se déplie sur place : code, empreinte, pause, désinstallation.
        val deplie = gestionDepliee || !cfg.aUnCode || cfg.modeParent || gestionOuverte
        val detail = when {
            !cfg.aUnCode -> getString(R.string.garde_choisir_code)
            cfg.modeParent -> getString(R.string.setup_en_pause_reprise, Etat.quand(cfg.parentJusqua))
            else -> if (Modules.actif(this, Modules.PAUSE)) getString(R.string.setup_gestion_detail_max, Pauses.max(this)) else getString(R.string.setup_gestion_detail)
        }
        racine.addView(ligneReglage(getString(R.string.setup_espace_gestion), detail, if (deplie) "▾" else "▸") { gestionDepliee = !deplie; if (deplie) gestionOuverte = false; dessiner() })
        if (deplie) {
            val parent = Ui.carte(this)
            zoneParent(parent)
            racine.addView(parent)
        }
    }

    /** Tout est accordé : une ligne « ✅ Tout est en ordre », qui déplie la liste. Sinon, la liste complète, repliée. */
    private fun ligneAutorisations(toutes: List<Protection>, manquantes: List<Protection>, impossibles: List<Protection>) {
        val titre = getString(if (manquantes.isEmpty()) R.string.setup_tout_en_ordre else R.string.setup_toutes_autorisations)
        val detail = if (manquantes.isEmpty()) resources.getQuantityString(R.plurals.setup_n_accordees, toutes.size - impossibles.size, toutes.size - impossibles.size)
            else getString(R.string.setup_n_sur_m_accordees, toutes.size - manquantes.size - impossibles.size, toutes.size - impossibles.size)
        racine.addView(Ui.marge(this, ligneReglage(titre, detail, if (autorisationsDepliees) "▾" else "▸") { autorisationsDepliees = !autorisationsDepliees; dessiner() }, haut = 10f, bas = 8f))
        if (!autorisationsDepliees) return
        val protections = Ui.carte(this)
        toutes.forEachIndexed { k, pr ->
            if (k > 0) protections.addView(Ui.separateur(this))
            protections.addView(ligneProtection(pr.titre, pr.detail, pr.ok(), { ouvrirEtape(pr) }, pr.sans, pr.possible()))
        }
        racine.addView(protections)
        if (impossibles.isNotEmpty()) {
            val c = Ui.carte(this, Ui.FOND)
            c.addView(Ui.texte(this, resources.getQuantityString(R.plurals.setup_n_impossibles, impossibles.size, impossibles.size), 14f, Ui.TEXTE, gras = true))
            c.addView(Ui.marge(this, Ui.texte(this, getString(R.string.setup_impossibles_texte), 13f, Ui.TEXTE_2), haut = 4f))
            racine.addView(c)
        }
    }

    /** La version, en une ligne discrète ; touchée, le détail des mises à jour. */
    private fun ligneVersion() {
        val prete = MiseAJour.disponible.isNotEmpty() || MiseAJour.apkPret()
        val v = Ui.texte(this, getString(if (prete) R.string.setup_version_maj_prete else R.string.setup_version_a_jour, BuildConfigCompat.version(this)), 13f, Ui.TEXTE_3, centre = true).apply {
            isFocusable = true; isClickable = true
            val p = Ui.dp(this@SetupActivity, 10f); setPadding(p, p, p, p)
            contentDescription = getString(R.string.setup_version_description)
            setOnClickListener {
                val corps = listOf(MiseAJour.etatAuto(this@SetupActivity), MiseAJour.etat).filter { it.isNotEmpty() }.joinToString("\n\n")
                val d = Ui.dialogue(this@SetupActivity).setTitle("Vision ${BuildConfigCompat.version(this@SetupActivity)}").setMessage(corps).setPositiveButton(R.string.commun_ok, null)
                if (MiseAJour.detail.isNotEmpty()) d.setNeutralButton(R.string.setup_details) { _, _ ->
                    Ui.dialogue(this@SetupActivity).setTitle(R.string.setup_details).setMessage(MiseAJour.detail).setPositiveButton(R.string.commun_ok, null).show()
                }
                d.show()
            }
        }
        racine.addView(Ui.marge(this, large(v), haut = 12f))
    }

    /** Toute la largeur : à poser avant [Ui.marge], qui sinon donne à une vue sans dimensions la largeur de son contenu. */
    private fun large(v: View): View = v.apply {
        val h = (layoutParams as? LinearLayout.LayoutParams)?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)
    }

    private fun ligneProtection(titre: String, detail: String, ok: Boolean, action: () -> Unit, sans: String = "", possible: Boolean = true): View {
        val r = Ui.rangee(this)
        r.addView(Ui.pastille(this, ok))
        val col = Ui.colonne(this)
        col.addView(Ui.texte(this, titre, 15f, Ui.TEXTE, gras = true))
        col.addView(Ui.texte(this, detail, 12.5f, Ui.TEXTE_2))
        // Ce qu'on perd tant qu'elle manque, et si l'appareil sait l'accorder.
        if (!ok && sans.isNotEmpty()) col.addView(Ui.texte(this, sans, 12.5f, Ui.OR))
        if (!ok && !possible) col.addView(Ui.texte(this, getString(R.string.setup_pas_ce_reglage), 12.5f, Ui.TEXTE_3))
        r.addView(Ui.poids(col))
        // Largeur juste (grands caractères, petit écran) : « Activer » passe sous les textes, qui gardent toute la ligne.
        if (!ok && possible) {
            if (Ui.etroit(this)) col.addView(Ui.marge(this, Ui.boutonSecondaire(this, getString(R.string.setup_activer), action), haut = 8f))
            else r.addView(Ui.marge(this, Ui.boutonSecondaire(this, getString(R.string.setup_activer), action), gauche = 10f))
        }
        return r
    }

    private fun ligneInfo(cle: String, valeur: String): View {
        val r = Ui.rangee(this)
        // Le libellé garde sa largeur (jamais coupé en deux lignes) ; la valeur prend le reste.
        r.addView(Ui.texte(this, cle, 14f, Ui.TEXTE_2).apply { setPadding(0, 0, Ui.dp(this@SetupActivity, 12f), 0) })
        r.addView(Ui.texte(this, valeur, 14f, Ui.TEXTE, gras = true).apply {
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        return Ui.marge(this, r, haut = 4f, bas = 4f)
    }

    // ------------------------------------------------- Connexion initiale

    private fun ecranConnexion() {
        // APP-02 : une voie principale en premier (TV, ou sans caméra : le QR que cet appareil affiche et qu'un
        // décideur scanne ; avec une caméra : scanner le QR de Home Assistant), puis la recherche sur le réseau
        // (APP-03 : trouver n'engage à rien), puis les autres voies, repliées sous « Autres méthodes ».
        val camera = Appairage.aUneCamera(this) && !ReglagesTvActivity.estTele(this)
        racine.addView(cartePrincipale(camera))
        racine.addView(carteDecouverte())

        val autres = Ui.colonne(this).apply { tag = "autres_methodes"; visibility = if (autresDepliees) View.VISIBLE else View.GONE }
        racine.addView(Ui.marge(this, Ui.boutonSecondaire(this, getString(if (autresDepliees) R.string.liaison_autres_masquer else R.string.liaison_autres_methodes_plus)) {
            if (autresDepliees) { autresDepliees = false; dessiner(); racine.findViewWithTag<View>("autres_bascule")?.requestFocus() } else montrerAutresMethodes()
        }.apply { tag = "autres_bascule" }, bas = 14f))
        racine.addView(autres)
        peindreDecouverte?.invoke()

        val c = Ui.carte(this)
        c.addView(Ui.texte(this, getString(R.string.setup_a_la_main), 18f, Ui.TEXTE, gras = true))
        c.addView(Ui.marge(this, Ui.sousTitre(this, getString(R.string.setup_a_la_main_texte)), bas = 8f))
        val bloc = Ui.champ(this, getString(R.string.setup_colle_bloc), "").apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3; gravity = Gravity.TOP
        }
        c.addView(bloc)
        val user = Ui.champ(this, getString(R.string.setup_prenom), cfg.utilisateur)
        c.addView(user)
        c.addView(Ui.marge(this, Ui.boutonPrimaire(this, getString(R.string.setup_connecter)) {
            val (externe, interne, cle) = analyserBloc(bloc.text.toString())
            if (externe.isEmpty() && interne.isEmpty() || cle.isEmpty()) {
                toast(getString(R.string.setup_pas_trouve)); return@boutonPrimaire
            }
            connecter(externe, interne, cle, user.text.toString())
        }, haut = 16f))
        autres.addView(c)
        autres.addView(carteChamps())
        if (camera) autres.addView(carteQrAffiche(false))

        // Télécommande : le premier focus est visible, sur l'action principale (avant : sur la zone qui défile).
        if (ReglagesTvActivity.estTele(this)) racine.post { if (currentFocus == null || currentFocus is ScrollView) racine.findViewWithTag<View>("bienvenue_principal")?.requestFocus() }
    }

    /** APP-02 / APP-05 : « Autres méthodes » : les voies repliées s'ouvrent et la première vient à l'écran, avec le focus. */
    private fun montrerAutresMethodes() {
        autresDepliees = true
        racine.findViewWithTag<View>("autres_methodes")?.visibility = View.VISIBLE
        (racine.findViewWithTag<View>("autres_bascule") as? android.widget.Button)?.apply { text = getString(R.string.liaison_autres_masquer); visibility = View.VISIBLE }
        val cible = racine.findViewWithTag<View>("autres_methodes") ?: return
        cible.post {
            (racine.parent as? ScrollView)?.smoothScrollTo(0, cible.top)
            cible.requestFocus(View.FOCUS_DOWN)
        }
    }

    /**
     * APP-03 : la recherche sur le réseau, puis la question « Se relier à « {nom} » ? » [Se relier] / [Pas celui-ci].
     * Aucune requête n'est envoyée au serveur trouvé avant « Se relier ».
     */
    private fun carteDecouverte(): LinearLayout {
        val auto = Ui.carte(this, Ui.CARTE_HAUTE)
        auto.addView(Ui.texte(this, getString(R.string.setup_connexion_auto), 18f, Ui.TEXTE, gras = true))
        val suivi = Ui.texte(this, Decouverte.etat.ifEmpty { getString(R.string.setup_cherche) }, 14f, Ui.TEXTE_2)
        auto.addView(Ui.marge(this, suivi, haut = 6f))
        val question = Ui.colonne(this).apply { tag = "liaison_question" }
        auto.addView(question)
        val actions = Ui.rangeeBoutons(this)
        auto.addView(Ui.marge(this, actions, haut = 12f))

        fun suivreInscription() {
            // L'inscription suit dans le service : on regarde quelques secondes si elle a abouti.
            var essais = 0
            val voir = object : Runnable { override fun run() {
                if (isFinishing) return
                if (cfg.inscrit) { dessiner(); return }
                suivi.text = Decouverte.etat
                if (++essais < 8) racine.postDelayed(this, 1500)
            } }
            racine.postDelayed(voir, 1500)
        }
        lateinit var peindre: () -> Unit
        fun chercher() {
            // L'écran a pu être redessiné pendant la recherche : on peint la carte affichée à ce moment-là.
            Decouverte.chercher(this) { if (!isFinishing) peindreDecouverte?.invoke() }
            peindre()
        }
        peindre = {
            suivi.text = Decouverte.etat.ifEmpty { getString(R.string.setup_cherche) }
            question.removeAllViews(); actions.removeAllViews()
            // Un seul « Autres méthodes » à l'écran : celui de la carte quand la recherche a échoué.
            val echec = Decouverte.trouve == null && !Decouverte.enCours
            racine.findViewWithTag<View>("autres_bascule")?.visibility = if (echec && !autresDepliees) View.GONE else View.VISIBLE
            val s = Decouverte.trouve
            // La question remplace la ligne d'état (elle dit déjà ce qui a été trouvé).
            suivi.visibility = if (s != null) View.GONE else View.VISIBLE
            if (s != null) {
                // L'adresse trouvée est proposée dans le champ du QR (affichage seul : rien n'est envoyé).
                (racine.findViewWithTag<View>("bienvenue_adresse") as? EditText)?.let { if (it.text.isBlank()) it.setText(s.adresse) }
                question.addView(Ui.marge(this, Ui.texte(this, getString(R.string.liaison_question, s.affiche()), 17f, Ui.TEXTE, gras = true), haut = 10f))
                question.addView(Ui.marge(this, Ui.texte(this, getString(R.string.liaison_question_texte, s.adresse), 13f, Ui.TEXTE_2), haut = 4f))
                actions.addView(Ui.poids(Ui.boutonPrimaire(this, getString(R.string.liaison_se_relier)) {
                    Decouverte.relier(this, s); peindre(); suivreInscription()
                }.apply { tag = "liaison_oui" }))
                actions.addView(Ui.marge(this, Ui.poids(Ui.boutonSecondaire(this, getString(R.string.liaison_pas_celui_ci)) {
                    Decouverte.ecarter(s); peindre()
                }.apply { tag = "liaison_non" }), gauche = 8f))
            } else if (!Decouverte.enCours) {
                // APP-05 : la recherche a une fin (15 s) ; on le dit, et on propose la suite.
                actions.addView(Ui.poids(Ui.boutonSecondaire(this, getString(R.string.commun_reessayer)) { Liaison.oublierEcartes(); chercher() }.apply { tag = "liaison_reessayer" }))
                actions.addView(Ui.marge(this, Ui.poids(Ui.boutonSecondaire(this, getString(R.string.liaison_autres_methodes)) { montrerAutresMethodes() }.apply { tag = "liaison_autres" }), gauche = 8f))
            }
        }
        peindreDecouverte = peindre
        if (!rechercheFaite) { rechercheFaite = true; chercher() } else peindre()
        return auto
    }

    /**
     * APP-02 : la voie principale. Avec une caméra (téléphone, tablette) : scanner le QR que Home Assistant affiche
     * (Vision 1.65.0). Sans caméra ou sur une TV : cet appareil affiche un QR et un code, un décideur le scanne avec
     * l'appli Home Assistant de son téléphone et valide (Vision 1.62.0).
     */
    private fun cartePrincipale(camera: Boolean): LinearLayout {
        if (!camera) return carteQrAffiche(true)
        val c = Ui.carte(this, Ui.CARTE_HAUTE)
        c.addView(Ui.texte(this, getString(R.string.bienvenue_qr_titre), 20f, Ui.TEXTE, gras = true))
        c.addView(Ui.marge(this, Ui.sousTitre(this, getString(R.string.setup_qr_camera_texte)), haut = 4f, bas = 8f))
        c.addView(Ui.marge(this, Ui.boutonPrimaire(this, getString(R.string.setup_scanner_qr_ha)) {
            startActivity(Intent(this, ScanActivity::class.java))
        }.apply { tag = "bienvenue_principal" }, haut = 8f))
        return c
    }

    /**
     * Cet appareil affiche un QR (AppairageActivity) ; seule l'adresse est demandée (proposée si un serveur a été
     * trouvé : rien ne lui est envoyé avant « Afficher le QR »). [principale] : la voie principale (TV, sans caméra),
     * sinon une voie de repli sous « Autres méthodes ».
     */
    private fun carteQrAffiche(principale: Boolean): LinearLayout {
        val c = Ui.carte(this, if (principale) Ui.CARTE_HAUTE else Ui.CARTE)
        c.addView(Ui.texte(this, getString(if (principale) R.string.bienvenue_qr_titre else R.string.setup_par_qr), if (principale) 20f else 18f, Ui.TEXTE, gras = true))
        c.addView(Ui.marge(this, Ui.sousTitre(this, getString(if (principale) R.string.setup_qr_sans_camera else R.string.setup_qr_inverse, Vocabulaire.decideur(this))), haut = 4f, bas = 8f))
        val adresse = Ui.champ(this, getString(R.string.setup_adresse_ha),
            cfg.urlInterne.ifEmpty { cfg.urlExterne }.ifEmpty { Decouverte.trouve?.adresse.orEmpty() }).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            tag = if (principale) "bienvenue_adresse" else null
        }
        c.addView(adresse)
        val afficher: () -> Unit = {
            val url = normaliserAdresse(adresse.text.toString())
            if (url.isEmpty()) { toast(getString(R.string.setup_ecris_adresse_ha)); adresse.requestFocus() }
            else startActivity(Intent(this, AppairageActivity::class.java).putExtra(AppairageActivity.EXTRA_ADRESSE, url))
        }
        val bouton = if (principale) Ui.boutonPrimaire(this, getString(R.string.setup_afficher_qr), afficher).apply { tag = "bienvenue_principal" }
            else Ui.boutonSecondaire(this, getString(R.string.setup_afficher_qr), afficher)
        c.addView(Ui.marge(this, bouton, haut = 16f))
        return c
    }

    /** Relie l'appareil avec une adresse et une clé, d'où qu'elles viennent (collage ou saisie). */
    private fun connecter(externe: String, interne: String, cle: String, utilisateur: String) {
        cfg.urlExterne = externe.ifEmpty { interne }
        cfg.urlInterne = interne
        cfg.cleInscription = cle
        cfg.sansCle = false
        cfg.utilisateur = utilisateur.trim()
        toast(getString(R.string.setup_connexion))
        AgentService.demarrer(this)
        racine.postDelayed({ dessiner() }, 2500)
    }

    /**
     * Troisième façon : l'adresse du serveur, puis la clé, chacune dans son champ. Chaque champ
     * accepte un collage ; si on y colle le bloc entier (adresse et clé), les deux se remplissent.
     */
    private fun carteChamps(): LinearLayout {
        val c = Ui.carte(this)
        c.addView(Ui.texte(this, getString(R.string.setup_saisir), 18f, Ui.TEXTE, gras = true))
        c.addView(Ui.marge(this, Ui.sousTitre(this, getString(R.string.setup_saisir_texte)), bas = 8f))
        val adresse = Ui.champ(this, getString(R.string.setup_adresse_serveur), cfg.urlExterne).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val cle = Ui.champ(this, getString(R.string.setup_cle), "").apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        // Un bloc entier collé dans l'un des deux champs : on répartit adresse et clé.
        // Seulement sur un collage (plusieurs caractères d'un coup) : en tapant « clé: » à la main,
        // le champ ne doit pas se découper au milieu de la saisie.
        var repartition = false
        fun surveiller(champ: android.widget.EditText) = champ.addTextChangedListener(object : android.text.TextWatcher {
            var colle = false
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) { colle = n >= 8 }
            override fun afterTextChanged(s: android.text.Editable?) {
                if (repartition || !colle) return
                val texte = s?.toString() ?: return
                val (externe, interne, k) = analyserBloc(texte)
                // Un bloc, c'est une adresse et une clé ; sinon on a collé l'une des deux seulement.
                if (k.isEmpty() || (externe.isEmpty() && interne.isEmpty())) return
                repartition = true
                if (externe.isNotEmpty() || interne.isNotEmpty()) adresse.setText(externe.ifEmpty { interne })
                if (k.isNotEmpty()) cle.setText(k)
                if (champ === adresse && k.isEmpty()) adresse.setSelection(adresse.text.length)
                repartition = false
            }
        })
        surveiller(adresse); surveiller(cle)
        c.addView(adresse)
        c.addView(Ui.marge(this, cle, haut = 8f))
        c.addView(Ui.marge(this, Ui.boutonPrimaire(this, getString(R.string.setup_connecter)) {
            val url = normaliserAdresse(adresse.text.toString())
            val k = cle.text.toString().trim()
            if (url.isEmpty()) { toast(getString(R.string.setup_ecris_adresse_serveur)); return@boutonPrimaire }
            if (k.length < 6 || k.length > 64 || k.any { it.isWhitespace() }) {
                toast(getString(R.string.setup_cle_longueur)); return@boutonPrimaire
            }
            val prive = adressePrivee(url)
            connecter(if (prive) "" else url, if (prive) url else "", k, cfg.utilisateur)
        }, haut = 16f))
        return c
    }

    /** « maison.exemple.org » ou « https://ha.exemple.org/lovelace/0 » → « https://ha.exemple.org ». */
    private fun normaliserAdresse(brut: String): String {
        var a = brut.trim().trim('`', '"', '\'')
        if (a.isEmpty()) return ""
        if (!a.contains("://")) a = "https://$a"
        val schema = a.substringBefore("://").lowercase()
        if (schema != "http" && schema != "https") return ""
        val hote = a.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        return if (hote.isEmpty()) "" else "$schema://$hote"
    }

    private fun adressePrivee(url: String): Boolean = Reseau.estPrivee(url)

    /**
     * Lit un texte collé : une ou deux adresses http(s) et une clé. Tolère le
     * bloc de Home Assistant (« adresse: … / cle: … / locale: … ») comme une
     * simple paire « url clé » tapée à la main.
     */
    private fun analyserBloc(t: String): Triple<String, String, String> {
        var externe = ""; var interne = ""
        for (m in Regex("https?://[^\\s\"'<>`]+").findAll(t)) {
            val url = m.value.trimEnd('.', ',', ';', ')')
            if (Reseau.estPrivee(url)) { if (interne.isEmpty()) interne = url } else if (externe.isEmpty()) externe = url
        }
        val cle = Regex("(?i)cl[ée]\\s*(?:d'inscription)?\\s*[:=]\\s*`?([A-Za-z0-9]{8,64})").find(t)?.groupValues?.get(1)  // i18n-exception : clé du bloc de Home Assistant (données)
            ?: Regex("\\b[0-9a-f]{16,64}\\b").find(t)?.value ?: ""
        return Triple(externe, interne, cle)
    }

    /** Après l'inscription : qui tient cet appareil ? (liste des personnes de Home Assistant) */
    private fun choixPersonne() {
        val c = Ui.carte(this)
        c.addView(Ui.texte(this, getString(R.string.setup_qui_utilise), 18f, Ui.TEXTE, gras = true))
        c.addView(Ui.marge(this, Ui.sousTitre(this, getString(R.string.setup_qui_utilise_texte)), bas = 6f))
        val liste = try { org.json.JSONArray(cfg.personnes) } catch (_: Exception) { org.json.JSONArray() }
        for (i in 0 until liste.length()) {
            val p = liste.optJSONObject(i) ?: continue
            val nom = p.optString("nom"); val id = p.optString("entity_id")
            if (nom.isEmpty() || id.isEmpty()) continue
            c.addView(Ui.marge(this, Ui.boutonSecondaire(this, nom) {
                cfg.personne = id; cfg.utilisateur = nom; cfg.personneAEnvoyer = true
                AgentService.demarrer(this)
                toast(getString(R.string.setup_appareil_de, nom))
                assistant = true; assistantDerniere = ""; avancerAssistant()
            }.apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }, haut = 8f))
        }
        c.addView(Ui.marge(this, Ui.texte(this, getString(R.string.setup_personne_autre), 12f, Ui.TEXTE_3), haut = 10f))
        c.addView(Ui.marge(this, Ui.boutonSecondaire(this, getString(R.string.setup_passer)) { cfg.personne = "-"; dessiner() }, haut = 6f))
        racine.addView(c)
    }

    // ---------------------------------------------------- Espace parent

    private fun zoneParent(c: LinearLayout) {
        if (!cfg.aUnCode) {
            c.addView(Ui.sousTitre(this, getString(R.string.setup_choisis_code)))
            val pin = Ui.champ(this, getString(R.string.garde_nouveau_code), "", chiffres = true)
            c.addView(pin)
            c.addView(Ui.marge(this, Ui.boutonPrimaire(this, getString(R.string.setup_definir_code)) {
                val code = pin.text.toString()
                if (code.length < 4) { toast(getString(R.string.setup_au_moins_4)); return@boutonPrimaire }
                cfg.definirCode(code); toast(getString(R.string.setup_code_enregistre)); dessiner()
            }, haut = 12f))
            return
        }
        if (cfg.modeParent) {
            c.addView(ligneInfo(getString(R.string.setup_pause), getString(R.string.temps_jusqua, Etat.heure(cfg.parentJusqua))))
            c.addView(Ui.marge(this, Ui.boutonPrimaire(this, getString(R.string.setup_reprendre_protection)) { cfg.parentJusqua = 0; dessiner() }, haut = 12f))
            // Le code (ou l'empreinte) vient d'être donné : on peut changer le code et la façon de déverrouiller.
            reglagesProtection(c)
            if (Etat.parent && Modules.actif(this, Modules.DEVERROUILLAGE)) c.addView(Ui.marge(this, Ui.boutonSecondaire(this, getString(R.string.dpa_titre)) {
                DeverrouillageParentActivity.ouvrir(this) }, haut = 10f))
            c.addView(Ui.marge(this, Ui.boutonDanger(this, getString(R.string.setup_desinstaller_vision)) { desinstaller() }, haut = 10f))
            return
        }
        // Module « pause » coupé dans Home Assistant : plus de pause ; le code ouvre seulement les réglages de gestion.
        if (!Modules.actif(this, Modules.PAUSE)) {
            if (!gestionOuverte) {
                c.addView(Ui.sousTitre(this, getString(R.string.setup_code_ouvre)))
                c.addView(Ui.marge(this, Ui.boutonSecondaire(this, getString(R.string.setup_ouvrir_code)) {
                    Garde.exiger(this, getString(R.string.setup_reglages_gestion)) { gestionOuverte = true; dessiner() }
                }, haut = 12f))
                return
            }
            c.addView(Ui.boutonPrimaire(this, getString(R.string.setup_fermer_reglages_gestion)) { gestionOuverte = false; dessiner() })
            reglagesProtection(c)
            if (Etat.parent && Modules.actif(this, Modules.DEVERROUILLAGE)) c.addView(Ui.marge(this, Ui.boutonSecondaire(this, getString(R.string.dpa_titre)) {
                DeverrouillageParentActivity.ouvrir(this) }, haut = 10f))
            c.addView(Ui.marge(this, Ui.boutonDanger(this, getString(R.string.setup_desinstaller_vision)) { desinstaller() }, haut = 10f))
            return
        }
        c.addView(Ui.sousTitre(this, getString(R.string.setup_pause_texte)))
        // La durée, choisie maintenant ; au plus la « Durée max de pause » de cet appareil (Home Assistant).
        val choix = Pauses.choix(this)
        var minutes = choix.firstOrNull { it.second == 15 }?.second ?: choix.first().second
        // 1.87.0 (recette visuelle) : les durées passent à la ligne au lieu de défiler hors de l'écran (« 1 h » coupé à 360 dp, police 130 %).
        val duree = Pastilles(this, Ui.dp(this, 6f))
        val boutonsDuree = ArrayList<android.widget.Button>()
        fun peindre() { boutonsDuree.forEachIndexed { i, b -> val on = choix[i].second == minutes; b.alpha = if (on) 1f else 0.5f } }
        choix.forEach { (lbl, m) ->
            val b = Ui.boutonSecondaire(this, lbl) { minutes = m; peindre() }
            boutonsDuree.add(b)
            duree.addView(b)
        }
        peindre()
        c.addView(Ui.marge(this, duree, haut = 8f))
        c.addView(Ui.marge(this, Ui.texte(this, getString(R.string.setup_pause_max, Pauses.max(this)), 12f, Ui.TEXTE_3), haut = 4f))
        val pin = Ui.champ(this, getString(R.string.garde_code_gestion), "", chiffres = true)
        c.addView(pin)
        c.addView(Ui.marge(this, Ui.boutonSecondaire(this, getString(R.string.setup_pause_code)) {
            if (!cfg.verifierCode(pin.text.toString())) { toast(getString(R.string.setup_code_incorrect)); return@boutonSecondaire }
            // Code juste sur l'appareil d'un parent : la biométrie (si HA la permet) se (ré)arme.
            Biometrie.armerApresCode(this)
            Pauses.mettre(this, minutes, "code")
            toast(getString(R.string.setup_pause_n, minutes)); dessiner()
        }, haut = 10f))
        // Appareil d'un parent (décidé par Home Assistant) : empreinte ou visage à la place du code.
        if (Biometrie.disponible(this)) c.addView(Ui.marge(this, Ui.boutonPrimaire(this, getString(R.string.setup_pause_empreinte)) {
            val m = minutes
            Biometrie.demander(this, getString(R.string.setup_mettre_pause, m), ok = {
                Pauses.mettre(this, m, "biometrie")
                toast(getString(R.string.setup_pause_n, m)); dessiner()
            }, code = { pin.requestFocus() })
        }, haut = 10f))
    }

    /** Changer le code parent ; code seul, ou code + empreinte / visage (appareil d'un parent, permis par HA). */
    private fun reglagesProtection(c: LinearLayout) {
        c.addView(Ui.marge(this, Ui.texte(this, getString(R.string.setup_changer_code), 15f, Ui.TEXTE, gras = true), haut = 18f))
        val nouveau = Ui.champ(this, getString(R.string.garde_nouveau_code), "", chiffres = true)
        val encore = Ui.champ(this, getString(R.string.garde_code_encore), "", chiffres = true)
        c.addView(nouveau); c.addView(encore)
        c.addView(Ui.marge(this, Ui.boutonSecondaire(this, getString(R.string.setup_enregistrer_code)) {
            val a = nouveau.text.toString(); val b = encore.text.toString()
            when {
                a.length < 4 -> toast(getString(R.string.setup_au_moins_4))
                a != b -> toast(getString(R.string.setup_codes_differents))
                else -> { cfg.definirCode(a); Biometrie.armerApresCode(this); toast(getString(R.string.setup_code_change)); dessiner() }
            }
        }, haut = 8f))
        c.addView(Ui.marge(this, Ui.texte(this, getString(R.string.setup_deverrouiller_avec), 15f, Ui.TEXTE, gras = true), haut = 18f))
        val etat = Biometrie.etat(this)
        val texte = when (etat) {
            Biometrie.Etat.ACTIVEE -> getString(R.string.setup_bio_activee)
            Biometrie.Etat.A_ACTIVER -> getString(R.string.setup_bio_a_activer)
            Biometrie.Etat.NON_AUTORISEE_PAR_HA -> getString(R.string.setup_bio_non_autorisee)
            Biometrie.Etat.MATERIEL_ABSENT -> getString(R.string.setup_bio_materiel)
            Biometrie.Etat.ANDROID_TROP_ANCIEN -> getString(R.string.setup_bio_android)
        }
        c.addView(Ui.marge(this, Ui.texte(this, texte, 13f, Ui.TEXTE_2), haut = 4f))
        when (etat) {
            Biometrie.Etat.A_ACTIVER -> c.addView(Ui.marge(this, Ui.boutonPrimaire(this, getString(R.string.setup_bio_activer)) {
                Biometrie.armerApresCode(this)
                Biometrie.demander(this, getString(R.string.setup_bio_activer_raison),
                    ok = { toast(getString(R.string.setup_bio_actives)); dessiner() },
                    code = { Biometrie.couper(this); toast(getString(R.string.setup_bio_non_active)); dessiner() })
            }, haut = 8f))
            Biometrie.Etat.ACTIVEE -> c.addView(Ui.marge(this, Ui.boutonSecondaire(this, getString(R.string.setup_bio_ne_plus)) {
                Biometrie.couper(this); toast(getString(R.string.setup_bio_code_seul)); dessiner() }, haut = 8f))
            else -> {}
        }
        // 1.65.0 : gérer depuis ce téléphone redemande l'empreinte ou le code ; après une réussite, pendant combien de temps on ne redemande pas.
        c.addView(Ui.marge(this, Ui.texte(this, getString(R.string.setup_gerer_telephone), 15f, Ui.TEXTE, gras = true), haut = 18f))
        c.addView(Ui.marge(this, Ui.texte(this, getString(R.string.setup_gerer_telephone_texte), 13f, Ui.TEXTE_2), haut = 4f))
        val actuel = Garde.minutes(this)
        val choix = Pastilles(this, Ui.dp(this, 6f))
        Garde.CHOIX_MINUTES.forEach { m ->
            val lbl = if (m == 0) getString(R.string.veille_repli_jamais) else getString(R.string.tr_duree_min, m)
            val b = if (m == actuel) Ui.boutonPrimaire(this, lbl) {} else Ui.boutonSecondaire(this, lbl) { Garde.regler(this, m); Garde.fermer(); dessiner() }
            choix.addView(b)
        }
        c.addView(Ui.marge(this, choix, haut = 8f))
    }

    private fun desinstaller() {
        // 1.79.0 (point 13 bis) : le fond de l'écran verrouillé posé par Vision s'efface avant de partir.
        if (EcranVerrou.voulu(this)) EcranVerrou.restaurer(this)
        // Sans pause (module coupé), la protection des réglages laisserait l'écran de désinstallation se fermer :
        // le code vient d'être donné, on ouvre une courte fenêtre technique de deux minutes.
        if (!cfg.modeParent && Modules.actif(this, Modules.PROTECTIONS)) cfg.parentJusqua = System.currentTimeMillis() + 2 * 60_000L
        // On retire d'abord l'administrateur, sinon le système refuse la désinstallation.
        if (AdminReceiver.estActif(this)) {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            try { dpm.removeActiveAdmin(AdminReceiver.composant(this)) } catch (_: Exception) {}
        }
        try {
            startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
        } catch (_: Exception) { toast(getString(R.string.setup_ouvre_reglages_desinstaller)) }
    }

    // ---------------------------------------------------- Actions système

    private fun demanderOverlay() {
        if (Build.VERSION.SDK_INT >= 23)
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun demanderVpn() {
        val i = VpnService.prepare(this)
        if (i != null) startActivityForResult(i, 1) else DnsVpnService.demarrer(this)
    }

    private fun demanderAdmin() {
        AdminReceiver.noterDemande()
        val i = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, AdminReceiver.composant(this))
            .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.admin_desc))
        try { startActivity(i) } catch (_: Exception) { toast(getString(R.string.setup_indisponible)) }
    }

    private fun demanderBatterie() {
        if (Build.VERSION.SDK_INT >= 23) try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")))
        } catch (_: Exception) { ouvrir(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) }
    }

    /** Réglage Android « Installer des applis inconnues », à accorder une fois à Vision. */
    private fun demanderInstallation() {
        if (Build.VERSION.SDK_INT >= 26) try {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName")))
        } catch (_: Exception) { toast(getString(R.string.eco_introuvable_titre)) }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 1 && res == RESULT_OK) DnsVpnService.demarrer(this)
    }

    private fun peutRecouvrir(): Boolean =
        Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)

    private fun batterieIgnoree(): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun ouvrir(action: String) = try { startActivity(Intent(action)) }
        catch (_: Exception) { toast(getString(R.string.eco_introuvable_titre)) }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    companion object {
        /** 1.84.0 : ouvrir avec l'espace gestion déplié (section « Décideurs » des réglages télé). */
        const val EXTRA_GESTION = "gestion"
        /** Vrai quand l'écran de Vision est devant : l'écran du système peut alors s'ouvrir directement. */
        @Volatile var auPremierPlan = false
        /** Le dernier relevé de gestion (décideur) : nombre de demandes, état des appareils, pause générale. */
        @Volatile var resumeGestion: JSONObject? = null
        @Volatile var resumeA = 0L
        /** Quand une demande de temps est partie de l'écran principal (pour dire « Demande envoyée »). */
        @Volatile var demandeTempsA = 0L
        const val EXTRA_BLOC = "bloc"
        const val EXTRA_UTILISATEUR = "utilisateur"
    }

}
