// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/**
 * L'espace parents : ce que montre le tableau de bord de la maison, sur le
 * téléphone d'un parent. Chaque appareil d'enfant avec son état, ce qui est
 * coupé, le planning du jour, et de quoi agir d'un geste : du temps en plus,
 * fermer, revenir au planning, envoyer un mot. Home Assistant reste seul juge :
 * il ne répond qu'aux appareils portant l'étiquette « Parents ».
 */
class ParentsActivity : Activity() {
    private lateinit var racine: LinearLayout
    private val cfg by lazy { Config(this) }
    private val principal = Handler(Looper.getMainLooper())
    private var appareils: JSONArray = JSONArray()
    private var demandes: JSONArray = JSONArray()
    // HA 2.5.0 et après (refonte des autorisations) : l'état porte « autorisations_v2 ». Avant, une décision
    // « accorder » serait lue comme un refus : les trois modes ne s'affichent qu'avec une intégration qui les connaît.
    private var troisModes = false
    /** HA 2.5.1 : écran d'une catégorie par élément, recherche, blocages récents, demandes groupées. */
    private var elements = false
    /** Demande groupée : ses éléments (« app:<nom> », sites) et ceux que le Décideur a décochés. */
    private val groupe = HashMap<String, List<String>>()
    private val exclus = HashMap<String, MutableSet<String>>()
    /** Refonte UX, lot 2 : ce qui se traite par lots dans Home Assistant (appareils à valider, à classer, logiciels). */
    private var lots: JSONArray = JSONArray()
    private var categories: JSONArray = JSONArray()
    private var personnes: JSONArray = JSONArray()
    /** Fin de la pause générale en cours (ms), null sans pause. */
    private var pauseFin: Long? = null
    private var erreur = ""
    private var chargement = false
    private val deplies = HashSet<String>()
    /** Refonte lot 3 : les personnes montrées (entité, prénom), dans l'ordre, pour passer de fiche en fiche. */
    private val personnesFiche = ArrayList<Pair<String, String>>()
    /** 1.71.0 : le geste demandé depuis l'écran principal (pause générale, ajout de temps), fait au premier chargement. */
    private var actionDemandee: String? = null
    private val rafraichir = object : Runnable {
        override fun run() { charger(); principal.postDelayed(this, 20_000) }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Ui.charger(this)
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            window.statusBarColor = Ui.FOND; window.navigationBarColor = Ui.FOND
        }
        val scroll = ScrollView(this).apply { setBackgroundColor(Ui.FOND); isFillViewport = true }
        racine = Ui.colonne(this, Ui.dp(this, 20f))
        Ui.largeurLecture(racine)
        scroll.addView(racine)
        setContentView(scroll)
        actionDemandee = intent?.getStringExtra(EXTRA_ACTION)
        // Refonte lot 3 : retour depuis une fiche (« Voir les appareils ») : la personne arrive dépliée.
        intent?.getStringExtra(EXTRA_DEPLIER)?.takeIf { it.isNotEmpty() }?.let { deplies.add("p:$it") }
        dessiner()
    }

    override fun onResume() { super.onResume(); Garde.exigerCodeDecideur(this); principal.post(rafraichir) }
    override fun onPause() { super.onPause(); principal.removeCallbacks(rafraichir) }

    // ------------------------------------------------------------ Réseau

    /** Un geste complet (Vision 1.74.0, GestionAppareil) : le corps porte action, pc et ses champs. */
    private fun agir(geste: JSONObject) = charger(geste.optString("action"), geste.optString("pc"), geste.optInt("minutes"), "", geste)

    private fun charger(action: String = "", pc: String = "", minutes: Int = 0, texte: String = "", geste: JSONObject? = null) {
        // Consulter est libre (HA ne répond qu'aux rôles qui gèrent) ; agir demande l'empreinte ou le code.
        // FAILLE (l'auteur, 16-10) : un geste qui accorde demande l'empreinte ou le code à chaque fois (Garde.exigerOctroi).
        if (action.isNotEmpty()) {
            val apercu = JSONObject().put("action", action).put("minutes", minutes).put("decision", geste?.optString("decision") ?: "")
            Garde.exigerPour(this, RAISON, CHEMIN, apercu) { envoyer(action, pc, minutes, texte, geste) }; return
        }
        envoyer(action, pc, minutes, texte, geste)
    }

    private fun envoyer(action: String, pc: String, minutes: Int, texte: String, geste: JSONObject?) {
        if (chargement && action.isEmpty()) return
        chargement = true
        // 1.77.0 : le bouton touché dit « En cours… » jusqu'à la réponse ; l'écran se redessine depuis elle.
        if (action.isNotEmpty()) Geste.enCours()
        Thread {
            try {
                val corps = corpsLecture(cfg)
                if (action.isNotEmpty()) {
                    corps.put("action", action).put("pc", pc).put("minutes", minutes)
                    if (texte.isNotEmpty()) corps.put("texte", texte)
                    geste?.keys()?.forEach { k -> if (k != "id" && k != "secret") corps.put(k, geste.get(k)) }
                }
                val r = Net.post(this, cfg, CHEMIN, corps)
                Modules.memoriser(this, r)
                appareils = r.optJSONArray("appareils") ?: JSONArray()
                cfg.lieu = r.optString("lieu", "")
                demandes = r.optJSONArray("demandes") ?: JSONArray()
                troisModes = r.has("autorisations_v2")
                elements = r.has("elements")
                lots = r.optJSONArray("a_decider_lots") ?: JSONArray()
                categories = r.optJSONArray("categories") ?: JSONArray()
                personnes = r.optJSONArray("personnes") ?: JSONArray()
                pauseFin = PauseGenerale.lire(r)
                erreur = ""
                val retour = r.optString("retour", "")
                principal.post {
                    if (retour.isNotEmpty()) toast(retour); dessiner()
                    if (action.isEmpty()) actionDemandee?.let { a -> actionDemandee = null; faireActionDemandee(a) }
                }
            } catch (e: Net.Echec) {
                if (action.isNotEmpty() && e.code in 400..409) {
                    val phrase = e.corps?.optString("retour").orEmpty()
                    principal.post { toast(phrase.ifEmpty { getString(R.string.geste_echec_refuse) }, OeilModele.Genre.ERREUR); Geste.relacher(); dessiner() }
                } else {
                    erreur = if (e.code == 403) getString(R.string.geste_echec_droits)
                             else getString(R.string.parents_err_reponse)
                    principal.post { dessiner() }
                }
            } catch (_: Exception) {
                erreur = getString(R.string.parents_err_injoignable)
                principal.post { dessiner() }
            } finally { chargement = false }
        }.start()
    }

    // ------------------------------------------------------------ Écran

    private fun dessiner() {
        racine.removeAllViews()
        racine.addView(Ui.marque(this))
        val r = Ui.rangee(this)
        val col = Ui.colonne(this)
        col.addView(Ui.titre(this, Config(this).lieu))
        col.addView(Ui.sousTitre(this, getString(R.string.parents_sous_titre)))
        r.addView(Ui.marge(this, Ui.poids(col), gauche = 2f))
        r.addView(Ui.boutonSecondaire(this, "↻") { charger() }.apply { contentDescription = getString(R.string.a11y_actualiser) })
        racine.addView(Ui.marge(this, r, bas = 18f))
        // Les modules coupés dans Home Assistant disparaissent de l'espace gestion.
        val m = Modules.actifs(this)
        // Pause générale (point 14.2) : tout fermer d'un geste, ou la bannière « jusqu'à 19h45 » et « Reprendre ».
        if (PauseGenerale.visible(Modules.actif(this, Modules.PAUSE_GENERALE), Profil.fonctions(this))) blocPauseGenerale()
        if (Modules.actifDans(m, Modules.DEVERROUILLAGE))
            racine.addView(Ui.marge(this, Ui.boutonPrimaire(this, getString(R.string.dpa_titre)) { DeverrouillageParentActivity.ouvrir(this) }, bas = 14f))

        if (erreur.isNotEmpty()) {
            val c = Ui.carte(this, Ui.CARTE_HAUTE)
            c.addView(Ui.texte(this, erreur, 14f, Ui.ROUGE))
            racine.addView(c)
        }
        // Refonte UX, lot 2 : une seule boîte « À décider » — les demandes, puis ce qui se traite par lots dans HA.
        val avecDemandes = demandes.length() > 0 && Modules.actifDans(m, Modules.DEMANDES)
        if (avecDemandes || lots.length() > 0) racine.addView(Ui.section(this, getString(R.string.adec_titre)))
        for (i in 0 until lots.length()) {
            val l = lots.optJSONObject(i) ?: continue
            val c = Ui.carte(this, Ui.CARTE_HAUTE)
            c.addView(Ui.texte(this, l.optString("libelle"), 16f, Ui.TEXTE, gras = true))
            c.addView(Ui.marge(this, Ui.texte(this, getString(R.string.adec_dans_ha), 13f, Ui.TEXTE_2), haut = 4f))
            val ou = l.optString("ou")
            if (ou.startsWith("/")) {
                c.isClickable = true; c.isFocusable = true
                c.setOnClickListener {
                    try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(Config(this).urlActive.trimEnd('/') + ou))) } catch (_: Exception) {}
                }
            }
            racine.addView(Ui.marge(this, c, bas = 8f))
        }
        if (avecDemandes) {
            for (i in 0 until demandes.length()) {
                val d = demandes.optJSONObject(i) ?: continue
                val c = Ui.carte(this, Ui.CARTE_HAUTE)
                // 1.77.0 : une demande de temps a sa carte à elle (avant, elle passait pour un « Site : » vide).
                if (d.optString("genre") == "temps" || d.optString("genre") == "deverrouillage") {
                    // 1.80.0 : une demande de déverrouillage a la même carte simple : accorder ce qui est demandé, ou non.
                    val m = d.optInt("minutes", 15).coerceAtLeast(1)
                    c.addView(Ui.texte(this, getString(R.string.parents_demande, d.optString("prenom"),
                        if (d.optString("genre") == "temps") getString(R.string.demandes_temps_de_plus, Temps.duree(m)) else Demandes.quoi(d)), 16f, Ui.TEXTE, gras = true))
                    c.addView(Ui.marge(this, Ui.texte(this, Demandes.description(d), 13f, Ui.TEXTE_2), haut = 4f))
                    // Lot 6 (APP-08) : les mêmes réponses que la notification et le plein écran (accorder, autre durée, refuser).
                    val rt = Ui.rangeeBoutons(this)
                    val pct = d.optString("pc"); val idt = d.optString("id")
                    val (lblAccorder, lblAutre, lblRefuser) = Durees.reponsesTemps(m)
                    rt.addView(Ui.poids(Ui.boutonPrimaire(this, lblAccorder) { repondreAcces(pct, idt, "temporaire", m) }))
                    rt.addView(Ui.marge(this, Ui.poids(Ui.boutonDanger(this, lblRefuser) { repondreAcces(pct, idt, "non") }), gauche = 8f))
                    c.addView(Ui.marge(this, rt, haut = 14f))
                    if (d.optString("genre") == "temps") c.addView(Ui.marge(this, Ui.boutonSecondaire(this, lblAutre) {
                        Temps.choisir(this, getString(R.string.messageecran_accorder_du_temps), 0L) { ch -> repondreAcces(pct, idt, "temporaire", ch.minutes) }
                    }, haut = 8f))
                    racine.addView(c)
                    continue
                }
                c.addView(Ui.texte(this, getString(R.string.parents_demande, d.optString("prenom"), Demandes.quoi(d)), 16f, Ui.TEXTE, gras = true))
                c.addView(Ui.marge(this, Ui.texte(this, Demandes.description(d), 13f, Ui.TEXTE_2), haut = 4f))
                val lien = d.optString("lien"); val motif = d.optString("motif")
                if (lien.isNotEmpty()) c.addView(Ui.marge(this, Ui.texte(this, lien, 13f, Ui.OR).apply {
                    setOnClickListener { try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(if (lien.contains("://")) lien else "https://$lien"))) } catch (_: Exception) {} }
                }, haut = 4f))
                if (motif.isNotEmpty()) c.addView(Ui.marge(this, Ui.texte(this, getString(R.string.dpa_motif, motif), 13f, Ui.TEXTE_2), haut = 4f))
                val sitesG = d.optJSONArray("sites")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() } }.orEmpty()
                if (elements && sitesG.isNotEmpty() && d.optString("genre") == "apps") c.addView(Ui.marge(this, caseGroupe(d, sitesG), haut = 8f))
                d.optString("raison").takeIf { it.isNotEmpty() }?.let { c.addView(Ui.marge(this, Ui.texte(this, getString(R.string.parents_ferme_car, it), 13f, Ui.TEXTE_3), haut = 6f)) }
                val duree = d.optString("duree")
                val etiquettes = d.optJSONArray("etiquettes")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() } }.orEmpty()
                // Les catégories restent en français dans les données : seul l'affichage les traduit.
                val categoriesAffichees = etiquettes.joinToString(", ") { Categories.libelle(this, it) }
                val souhait = when (duree) {
                    "1h" -> resources.getQuantityString(R.plurals.temps_heures, 1, 1)
                    "toujours" -> getString(R.string.parents_souhait_toujours)
                    "categorie" -> if (etiquettes.isNotEmpty()) getString(R.string.parents_souhait_categorie_noms, categoriesAffichees) else getString(R.string.parents_souhait_categorie)
                    else -> ""
                }
                if (souhait.isNotEmpty()) c.addView(Ui.marge(this, Ui.texte(this, getString(R.string.parents_souhait, souhait), 13f, Ui.OR), haut = 6f))
                val r = Ui.rangeeBoutons(this)
                val pc = d.optString("pc"); val id = d.optString("id")
                fun choix(t: String, principal: Boolean, action: () -> Unit) = if (principal) Ui.boutonPrimaire(this, t, action) else Ui.boutonSecondaire(this, t, action)
                if (troisModes) {
                    // HA 2.5.0 : 15 min, 1 h, Refuser ; puis Exception et Toujours disponible (appli ou site).
                    r.addView(Ui.poids(choix(resources.getQuantityString(R.plurals.temps_minutes, 15, 15), false) { repondreAcces(pc, id, "temporaire", 15) }))
                    r.addView(Ui.marge(this, Ui.poids(choix(Tr.duree(60), duree == "1h" || duree.isEmpty()) { repondreAcces(pc, id, "temporaire", 60) }), gauche = 8f))
                    r.addView(Ui.marge(this, Ui.poids(Ui.boutonDanger(this, getString(R.string.parents_refuser)) { repondreAcces(pc, id, "non") }), gauche = 8f))
                    c.addView(Ui.marge(this, r, haut = 14f))
                    val r2 = Ui.rangeeBoutons(this)
                    r2.addView(Ui.poids(choix(getString(R.string.parents_exception), duree == "toujours") { repondreAcces(pc, id, "accorder", 0, "exception") }))
                    r2.addView(Ui.marge(this, Ui.poids(Ui.boutonSecondaire(this, getString(R.string.parents_toujours_disponible)) { repondreAcces(pc, id, "accorder", 0, "toujours") }), gauche = 8f))
                    c.addView(Ui.marge(this, r2, haut = 8f))
                    if (etiquettes.isNotEmpty()) c.addView(Ui.marge(this, choix(getString(R.string.parents_toute_categorie, categoriesAffichees), duree == "categorie") { repondreAcces(pc, id, "categorie") }, haut = 8f))
                    c.addView(Ui.marge(this, Ui.texte(this, getString(R.string.parents_aide_trois_modes), 12f, Ui.TEXTE_3), haut = 6f))
                } else {
                    // Lot 6 (APP-08) : les mêmes trois réponses que la notification et le plein écran.
                    val (lblPour, lblToujours, lblRefuser) = Durees.reponsesAcces()
                    r.addView(Ui.poids(choix(lblPour, duree == "1h" || duree.isEmpty()) { repondreAcces(pc, id, "temporaire", Durees.ACCES_TEMPORAIRE) }))
                    r.addView(Ui.marge(this, Ui.poids(choix(lblToujours, duree == "toujours" || duree.isEmpty()) { repondreAcces(pc, id, "toujours") }), gauche = 8f))
                    r.addView(Ui.marge(this, Ui.poids(Ui.boutonDanger(this, lblRefuser) { repondreAcces(pc, id, "non") }), gauche = 8f))
                    c.addView(Ui.marge(this, r, haut = 14f))
                    if (etiquettes.isNotEmpty()) c.addView(Ui.marge(this, choix(getString(R.string.parents_toute_categorie, categoriesAffichees), duree == "categorie") { repondreAcces(pc, id, "categorie") }, haut = 8f))
                    if (d.optString("genre") == "apps") c.addView(Ui.marge(this, Ui.boutonSecondaire(this, getString(R.string.parents_toujours_dispo)) { repondreAcces(pc, id, "exception") }, haut = 8f))
                    c.addView(Ui.marge(this, Ui.texte(this, getString(R.string.parents_aide_autoriser), 12f, Ui.TEXTE_3), haut = 6f))
                }
                c.addView(Ui.marge(this, Ui.boutonSecondaire(this, getString(R.string.temps_autre_duree)) {
                    Ui.dialogue(this).setTitle(getString(R.string.parents_ouvrir_pour, d.optString("libelle")))
                        .setItems((Durees.libelles(Durees.REPONSE) + getString(R.string.parents_jusqua_ce_soir)).toTypedArray()) { _, k ->
                            repondreAcces(pc, id, "temporaire", (Durees.REPONSE + 360)[k]) }
                        .setNegativeButton(R.string.commun_annuler, null).show()
                }, haut = 8f))
                racine.addView(c)
            }
        }
        // Profils (Vision 1.73.0) : un bloc par personne, ses appareils dessous, son temps cumulé.
        val parId = HashMap<String, JSONObject>()
        for (i in 0 until appareils.length()) appareils.optJSONObject(i)?.let { if (!it.optBoolean("parent")) parId[it.optString("id")] = it }
        val montres = HashSet<String>()
        var vus = 0
        personnesFiche.clear()
        for (i in 0 until personnes.length()) {
            val p = personnes.optJSONObject(i) ?: continue
            if (p.optBoolean("parent")) continue
            val ids = p.optJSONArray("appareils")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
                .filter { it in parId && it !in montres && parId[it]?.optBoolean("partage_tous") != true }
            if (ids.isEmpty()) continue
            // Accordéons (décision du projet) : une ligne repliable par personne, ses appareils et leurs gestes dessous.
            val siens = ids.map { parId.getValue(it) }
            montres += ids; vus += ids.size
            val entite = p.optString("entite")
            val cle = "p:$entite"
            if (entite.isNotEmpty()) personnesFiche += entite to p.optString("prenom")
            racine.addView(lignePersonne(p.optString("prenom"), p.optString("role"),
                p.optJSONObject("temps")?.optInt("actif")?.takeIf { Modules.actifDans(m, Modules.TEMPS) }, siens, cle, entite))
            if (cle in deplies) {
                val tous = p.optJSONArray("appareils") ?: JSONArray()
                // Refonte lot 3 : la fiche de la personne, en clair, en tête de son accordéon.
                if (entite.isNotEmpty()) racine.addView(Ui.marge(this, Ui.boutonSecondaire(this, getString(R.string.fiche_de, p.optString("prenom"))) {
                    ouvrirFiche(entite)
                }, haut = 4f, bas = 4f))
                if (Modules.horaires(m)) racine.addView(Ui.marge(this, Ui.boutonSecondaire(this, getString(R.string.parents_30_min_ses_appareils)) {
                    agir(JSONObject().put("action", "temps").put("pc", tous.optString(0)).put("minutes", 30).put("appareils", tous))
                }, haut = 4f, bas = 4f))
                for (a in siens) racine.addView(carteAppareil(a, dansProfil = true))
            }
        }
        val autres = parId.values.filter { it.optString("id") !in montres }
        if (autres.isNotEmpty()) {
            vus += autres.size
            val cle = "p:autres"
            racine.addView(lignePersonne(getString(if (montres.isEmpty()) R.string.parents_appareils else R.string.parents_ecrans_partages), "", null, autres, cle))
            if (cle in deplies) for (a in autres) racine.addView(carteAppareil(a))
        }
        if (vus == 0 && erreur.isEmpty()) {
            racine.addView(Ui.carte(this).apply {
                addView(Ui.texte(this@ParentsActivity, getString(if (appareils.length() == 0) R.string.commun_chargement else R.string.parents_aucun_appareil), 14f, Ui.TEXTE_2))
            })
        }
        racine.addView(Ui.espace(this, 24f))
    }

    /**
     * HA 2.5.1 (ajout 5 de l'auteur) : une demande pour un logiciel avec ses sites bloqués. Tout est coché : la réponse
     * vaut en bloc ; décocher un élément le laisse fermé (réponse élément par élément).
     */
    private fun caseGroupe(d: JSONObject, sites: List<String>): View {
        val id = d.optString("id")
        val app = "app:" + d.optString("cle")
        groupe[id] = listOf(app) + sites
        val sans = exclus.getOrPut(id) { HashSet() }
        val col = Ui.colonne(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
        col.addView(Ui.texte(this, resources.getQuantityString(R.plurals.el_groupe_sites, sites.size, sites.size), 13f, Ui.OR, gras = true))
        col.addView(Ui.marge(this, Ui.texte(this, getString(R.string.el_demande_inclus), 12f, Ui.TEXTE_2), haut = 4f))
        for (el in listOf(app) + sites) {
            val libelle = if (el == app) d.optString("libelle").ifEmpty { d.optString("cle") } else el
            col.addView(Ui.caseACocher(this, Ui.coupable(libelle), el !in sans) { coche -> if (coche) sans.remove(el) else sans.add(el) }.apply {
                tag = "el_case:$el"
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            })
        }
        return col
    }

    /** La ligne repliable d'une personne : nom, rôle, temps cumulé du jour, état de ses appareils. */
    private fun lignePersonne(titre: String, role: String, minutes: Int?, siens: List<JSONObject>, cle: String, entite: String = ""): View {
        val ouvert = cle in deplies
        val c = Ui.carte(this, Ui.CARTE_HAUTE)
        val r = Ui.rangee(this)
        val col = Ui.colonne(this)
        col.addView(Ui.texte(this, titre, 20f, Ui.TEXTE, gras = true))
        val n = siens.size
        col.addView(Ui.texte(this, listOf(role, resources.getQuantityString(R.plurals.parents_n_appareils, n, n)).filter { it.isNotEmpty() }.joinToString(" · "), 13f, Ui.TEXTE_2))
        r.addView(Ui.poids(col))
        val droite = Ui.colonne(this).apply { gravity = Gravity.END }
        if (minutes != null) droite.addView(Ui.texte(this, Profil.duree(minutes), 18f, Ui.OR, gras = true))
        val (etat, couleur) = etatGroupe(siens)
        droite.addView(Ui.marge(this, Ui.chip(this, etat, couleur), haut = 4f))
        r.addView(droite)
        r.addView(Ui.marge(this, Ui.texte(this, if (ouvert) "▾" else "▸", 18f, Ui.TEXTE_2), gauche = 12f))
        c.addView(r)
        // Refonte lot 3 : « Voir la fiche », un bouton à part : toucher la ligne déplie toujours l'accordéon.
        if (entite.isNotEmpty()) c.addView(Ui.marge(this, Ui.boutonSecondaire(this, getString(R.string.fiche_voir)) { ouvrirFiche(entite) }.apply {
            contentDescription = getString(R.string.fiche_de, titre)
        }, haut = 10f))
        c.setOnClickListener { if (ouvert) deplies.remove(cle) else deplies.add(cle); dessiner() }
        c.contentDescription = getString(if (ouvert) R.string.parents_replier else R.string.parents_deplier, titre)
        return Ui.marge(this, c, haut = 10f)
    }

    /** Refonte lot 3 : la fiche allégée d'une personne, avec la liste pour passer à la précédente ou à la suivante. */
    private fun ouvrirFiche(entite: String) = FichePersonneActivity.ouvrir(this, entite, personnesFiche)

    /** « Ouvert », « Fermé », « Hors ligne » ou « 1 ouvert · 1 fermé » pour un groupe d'appareils. */
    private fun etatGroupe(siens: List<JSONObject>): Pair<String, Int> {
        val horsLigne = siens.count { !it.optBoolean("en_ligne") }
        val fermes = siens.count { it.optBoolean("en_ligne") && it.optBoolean("verrouille") }
        val ouverts = siens.size - horsLigne - fermes
        return when {
            horsLigne == siens.size -> getString(R.string.commun_hors_ligne) to Ui.TEXTE_2
            ouverts == 0 -> getString(R.string.statut_ferme) to Ui.ROUGE
            fermes == 0 -> getString(R.string.statut_ouvert) to Ui.VERT
            else -> getString(R.string.parents_ouverts_fermes, resources.getQuantityString(R.plurals.parents_n_ouverts, ouverts, ouverts),
                resources.getQuantityString(R.plurals.parents_n_fermes, fermes, fermes)) to Ui.OR
        }
    }

    private fun carteAppareil(a: JSONObject, dansProfil: Boolean = false): View {
        val m = Modules.actifs(this)
        val horaires = Modules.horaires(m)
        val id = a.optString("id")
        val enLigne = a.optBoolean("en_ligne")
        val ferme = a.optBoolean("verrouille")
        val derog = a.optJSONObject("derogation")
        val maintenant = System.currentTimeMillis()
        val prochain = Etat.lireDate(a.optString("prochain"))
        val prochainVerrou = Etat.lireDate(a.optString("prochain_verrou"))

        val c = Ui.carte(this, Ui.CARTE_HAUTE)
        val haut = Ui.rangee(this)
        val col = Ui.colonne(this)
        val prenom = if (dansProfil) "" else a.optString("prenom")  // sous le bloc de la personne : le nom de l'appareil suffit
        col.addView(Ui.texte(this, prenom.ifEmpty { a.optString("nom") }, 20f, Ui.TEXTE, gras = true))
        col.addView(Ui.texte(this, if (prenom.isEmpty()) (if (a.optBoolean("android")) "Android" else "PC")  // i18n-exception : noms propres (glossaire)
                                   else a.optString("nom"), 13f, Ui.TEXTE_2))
        haut.addView(Ui.poids(col))
        val (lib, coul) = when {
            !enLigne -> getString(R.string.commun_hors_ligne) to Ui.TEXTE_2
            ferme -> getString(R.string.statut_ferme) to Ui.ROUGE
            else -> getString(R.string.statut_ouvert) to Ui.VERT
        }
        // 1.73.0 : écran étroit (grands caractères) : la pastille passe sous le nom, qui garde toute la largeur.
        if (Ui.etroit(this)) col.addView(Ui.marge(this, Ui.chip(this, lib, coul), haut = 6f)) else haut.addView(Ui.chip(this, lib, coul))
        c.addView(haut)

        // Ce qui vient.
        val detail = when {
            derog != null && derog.optLong("fin") > 0 ->
                getString(if (derog.optString("mode") == "locked") R.string.parents_ferme_gestionnaire_jusqua else R.string.parents_ouvert_gestionnaire_jusqua,
                    Etat.heure(derog.optLong("fin") * 1000), Vocabulaire.decideur(this))
            derog != null -> getString(if (derog.optString("mode") == "locked") R.string.parents_ferme_gestionnaire_creneau else R.string.parents_ouvert_gestionnaire_creneau, Vocabulaire.decideur(this))
            ferme && prochain > maintenant -> getString(R.string.parents_reouverture_a, Etat.heure(prochain))
            ferme -> getString(R.string.parents_ferme_planning)
            prochainVerrou > maintenant -> getString(R.string.parents_fermeture_prevue, Etat.heure(prochainVerrou))
            else -> getString(R.string.statut_aucune_fermeture)
        }
        if (horaires) c.addView(Ui.marge(this, Ui.texte(this, detail, 14f, Ui.TEXTE_2), haut = 10f))
        // 1.74.0 (point 7) : ce qui manque sur l'appareil, en clair (les protections qu'il dit tombées).
        a.optJSONObject("protections")?.takeIf { enLigne }?.let { p ->
            val manquent = listOf(
                "accessibilite" to getString(R.string.parents_manque_accessibilite),
                "usage" to getString(R.string.parents_manque_usage),
                "vpn" to getString(R.string.parents_manque_vpn),
                "admin" to getString(R.string.parents_manque_admin),
                "batterie" to getString(R.string.parents_manque_batterie),
                // 1.78.3 (point 7) : une télé n'a pas d'« écran d'accueil par défaut » : Vision s'y impose par l'accueil forcé.
                "accueil" to getString(if (a.optString("type") == "tv") R.string.parents_manque_accueil_tele else R.string.parents_manque_accueil),
            ).filter { (k, _) -> p.has(k) && !p.optBoolean(k, true) }.map { it.second }
            if (manquent.isNotEmpty()) c.addView(Ui.marge(this, Ui.texte(this, getString(R.string.parents_a_verifier, manquent.reduce { x, y -> getString(R.string.parents_liste, x, y) }), 13f, Ui.OR), haut = 8f))
        }

        // Activité et temps d'écran.
        val focus = a.optString("focus_libelle").ifEmpty { a.optString("focus") }
        val inactif = a.optInt("inactif_s")
        if (enLigne && focus.isNotEmpty()) {
            val act = if (inactif > 300) getString(R.string.parents_inactif_depuis, inactif / 60, focus) else getString(R.string.parents_en_ce_moment, focus)
            c.addView(ligneInfo(getString(R.string.parents_activite), act))
        }
        val u = a.optJSONObject("usage")
        if (u != null && Modules.actifDans(m, Modules.TEMPS)) c.addView(ligneInfo(getString(R.string.parents_ecran_aujourdhui), getString(R.string.parents_actif_sur_ouvert, duree(u.optInt("actif")), duree(u.optInt("ouvert")))))

        // Étiquettes coupées sur cet appareil.
        val et = a.optJSONArray("etiquettes")
        val blocage = Modules.actifDans(m, Modules.BLOCAGE)
        if (blocage && et != null && et.length() > 0) {
            // 1.87.0 (recette visuelle) : les étiquettes passent à la ligne au lieu de sortir de l'écran.
            val flux = Pastilles(this, Ui.dp(this, 6f))
            for (k in 0 until et.length()) flux.addView(Ui.chip(this, Categories.libelle(this, et.optString(k)), Ui.OR))
            c.addView(Ui.marge(this, flux, haut = 10f))
        }

        // Ce qui est fermé : un écran propre, rangé par motif.
        val nApps = a.optJSONArray("apps")?.length() ?: 0
        val nSites = (a.optJSONArray("sites")?.let { arr -> (0 until arr.length()).count { (arr.optJSONObject(it)?.optString("nom") ?: "").count { ch -> ch == '.' } == 1 } }) ?: 0
        if (blocage) {
        c.addView(Ui.separateur(this))
        val rB = Ui.rangeeBoutons(this)
        rB.addView(Ui.poids(Ui.boutonSecondaire(this, getString(R.string.cat_titre)) {
            CategoriesActivity.fiche = a; CategoriesActivity.cats = categories; CategoriesActivity.elements = elements
            startActivity(Intent(this, CategoriesActivity::class.java))
        }))
        rB.addView(Ui.marge(this, Ui.poids(Ui.boutonSecondaire(this, getString(R.string.parents_ferme_n, nApps, nSites)) {
            DetailFermesActivity.fiche = a
            startActivity(Intent(this, DetailFermesActivity::class.java))
        }), gauche = 8f))
        c.addView(Ui.marge(this, rB, haut = 8f))
        // Les exceptions de la personne : ce qui est autorisé ou fermé à part, nom par nom.
        val ex = a.optJSONObject("exceptions")
        val nEx = (ex?.optJSONArray("autorises")?.length() ?: 0) + (ex?.optJSONArray("bloques")?.length() ?: 0) + (ex?.optJSONArray("toujours")?.let { t -> (0 until t.length()).count { t.optJSONObject(it)?.optBoolean("maison") != true } } ?: 0)
        c.addView(Ui.marge(this, Ui.boutonSecondaire(this, if (nEx > 0) getString(R.string.parents_autorisations_n, nEx) else getString(R.string.parents_autorisations)) {
            ExceptionsActivity.fiche = a; ExceptionsActivity.cats = categories
            startActivity(Intent(this, ExceptionsActivity::class.java))
        }, haut = 8f))
        }
        if (Modules.actifDans(m, Modules.PLAGES)) c.addView(planning(id, a.optJSONArray("plages")))

        // Actions.
        if (horaires) {
        val r1 = Ui.rangeeBoutons(this)
        r1.addView(Ui.poids(Ui.boutonPrimaire(this, getString(R.string.parents_plus, Tr.duree(30))) { charger("ouvrir", id, 30) }))
        r1.addView(Ui.marge(this, Ui.poids(Ui.boutonPrimaire(this, getString(R.string.parents_plus, Tr.duree(60))) { charger("ouvrir", id, 60) }), gauche = 8f))
        r1.addView(Ui.marge(this, Ui.poids(Ui.boutonDanger(this, getString(R.string.parents_fermer)) { confirmerFermeture(id, prenom.ifEmpty { a.optString("nom") }) }), gauche = 8f))
        c.addView(Ui.marge(this, r1, haut = 14f))
        // Durée libre, ou jusqu'à la prochaine plage (seulement si l'appareil est fermé par l'agenda).
        c.addView(Ui.marge(this, Ui.boutonSecondaire(this, if (ferme && prochain > maintenant) getString(R.string.parents_autre_duree_jusqua, Etat.heure(prochain)) else getString(R.string.parents_autre_duree)) {
            Temps.choisir(this, getString(R.string.parents_ouvrir_pour, prenom.ifEmpty { a.optString("nom") }), if (ferme) prochain else 0L) { ch ->
                charger("ouvrir", id, ch.minutes)
            }
        }, haut = 8f))
        }
        val retour = horaires && derog != null
        val mot = Modules.actifDans(m, Modules.MESSAGES)
        if (retour || mot) {
            val r2 = Ui.rangeeBoutons(this)
            if (retour) r2.addView(Ui.poids(Ui.boutonSecondaire(this, getString(R.string.parents_retour_planning)) { charger("annuler", id) }))
            if (mot) r2.addView(Ui.marge(this, Ui.poids(Ui.boutonSecondaire(this, getString(R.string.parents_envoyer_mot)) { envoyerMot(id, prenom.ifEmpty { a.optString("nom") }) }),
                gauche = if (retour) 8f else 0f))
            c.addView(Ui.marge(this, r2, haut = 8f))
        }
        // 1.73.0 (point 14.5) : le mode concentration ; Home Assistant n'envoie « concentration » que si le module vit.
        a.optJSONObject("concentration")?.let { conc ->
            val finC = conc.optLong("fin") * 1000
            if (finC > System.currentTimeMillis()) {
                val n = conc.optJSONArray("apps")?.length() ?: 0
                val bloc = Ui.colonne(this)
                bloc.addView(Ui.texte(this, concentrationJusqua(finC), 15f, Ui.OR, gras = true))
                bloc.addView(Ui.marge(this, Ui.texte(this, if (n == 0) getString(R.string.parents_conc_aucune)
                    else resources.getQuantityString(R.plurals.parents_conc_n_apps, n, n), 13f, Ui.TEXTE_2), haut = 2f))
                bloc.addView(Ui.marge(this, Ui.boutonSecondaire(this, getString(R.string.parents_conc_arreter)) {
                    agir(JSONObject().put("action", "concentration").put("pc", id).put("fin", true))
                }, haut = 8f))
                c.addView(Ui.marge(this, bloc, haut = 12f))
            } else c.addView(Ui.marge(this, Ui.boutonSecondaire(this, getString(R.string.parents_conc_menu)) {
                choisirConcentration(id, prenom.ifEmpty { a.optString("nom") }, conc)
            }, haut = 8f))
        }
        // 1.93.06 (Déverrouillage simplifié) : déverrouiller cet appareil tout de suite, sans demande de sa part.
        if (Modules.actifDans(m, Modules.DEVERROUILLAGE) && a.optBoolean("android"))
            c.addView(Ui.marge(this, Ui.boutonSecondaire(this, getString(R.string.parents_deverrouiller)) {
                deverrouillerADistance(id, prenom.ifEmpty { a.optString("nom") })
            }, haut = 8f))
        val gestes = Modules.gestesGestion(m, a.optBoolean("android"))
        c.addView(Ui.marge(this, Ui.boutonSecondaire(this, getString(R.string.parents_gerer, gestes.filter { it != "pause" }.joinToString(", ") { nomGeste(it) })) { GestionAppareil.menu(this, a, personnes) { agir(it) } }, haut = 8f))
        return c
    }

    /**
     * 1.93.06 (Déverrouillage simplifié) : accès total pour 15, 30 ou 60 min (réglages d'Android, installations,
     * options pour les développeurs, aucun écran de blocage), puis l'appareil se reverrouille seul. L'empreinte ou le
     * code est demandé à chaque fois (Octroi) ; tous les responsables sont prévenus par Home Assistant.
     */
    private fun deverrouillerADistance(pc: String, nom: String) {
        val choix = Deverrouillage.MINUTES_INITIATIVE.sorted()
        Ui.dialogue(this).setTitle(getString(R.string.parents_deverrouiller_titre, nom))
            .setItems(choix.map { getString(R.string.parents_deverrouiller_min, it) }.toTypedArray()) { _, i ->
                val minutes = choix[i]
                Garde.exigerOctroi(this, getString(R.string.parents_deverrouiller_raison, nom, minutes)) {
                    Thread {
                        try {
                            Net.post(this, cfg, "/api/pc_parental/deverrouillage/initiative",
                                JSONObject().put("id", cfg.id).put("secret", cfg.secret).put("appareil", pc).put("minutes", minutes))
                            principal.post { toast(getString(R.string.parents_deverrouiller_fait, nom, minutes), OeilModele.Genre.SUCCES) }
                        } catch (e: Net.Echec) {
                            val phrase = e.corps?.optString("retour").orEmpty()
                            principal.post { toast(phrase.ifEmpty { getString(if (e.code == 403) R.string.geste_echec_droits else R.string.geste_echec_refuse) }, OeilModele.Genre.ERREUR) }
                        } catch (_: Exception) {
                            principal.post { toast(getString(R.string.parents_err_reponse), OeilModele.Genre.ERREUR) }
                        }
                    }.start()
                }
            }
            .setNegativeButton(R.string.commun_annuler, null).show()
    }

    /** Les gestes de gestion (codes de Modules.gestesGestion), tels qu'on les lit sur le bouton « Gérer ». */
    private fun nomGeste(code: String): String = when (code) {
        "planning" -> getString(R.string.parents_geste_planning)
        "limites" -> getString(R.string.parents_geste_limites)
        "à qui" -> getString(R.string.parents_geste_a_qui)  // i18n-exception : code de Modules.gestesGestion
        "pause" -> getString(R.string.parents_geste_pause)
        else -> code
    }

    /** « Concentration jusqu'à 21:30 », « … jusqu'à demain à 07:00 », « … jusqu'au mardi 14 octobre à 07:00 ». */
    private fun concentrationJusqua(ms: Long): String {
        val c = java.util.Calendar.getInstance(); val auj = c.get(java.util.Calendar.DAY_OF_YEAR) to c.get(java.util.Calendar.YEAR)
        c.add(java.util.Calendar.DAY_OF_YEAR, 1); val dem = c.get(java.util.Calendar.DAY_OF_YEAR) to c.get(java.util.Calendar.YEAR)
        c.timeInMillis = ms; val jour = c.get(java.util.Calendar.DAY_OF_YEAR) to c.get(java.util.Calendar.YEAR)
        return when (jour) {
            auj -> getString(R.string.parents_conc_jusqua, Tr.heure(ms))
            dem -> getString(R.string.parents_conc_jusqua_demain, Tr.heure(ms))
            else -> getString(R.string.parents_conc_jusqua_date, Tr.date(ms, "EEEEdMMMM"), Tr.heure(ms))
        }
    }

    private fun repliable(cle: String, titre: String, liste: JSONArray?, libelle: (JSONObject) -> String): View {
        val n = liste?.length() ?: 0
        val bloc = Ui.colonne(this)
        val ouvert = cle in deplies
        val entete = Ui.rangee(this)
        entete.addView(Ui.poids(Ui.texte(this, "$titre ($n)", 14f, if (n > 0) Ui.TEXTE else Ui.TEXTE_3, gras = true)))
        entete.addView(Ui.texte(this, if (ouvert) "▴" else "▾", 14f, Ui.TEXTE_2))
        entete.setPadding(0, Ui.dp(this, 8f), 0, Ui.dp(this, 8f))
        entete.isClickable = n > 0
        entete.setOnClickListener { if (ouvert) deplies.remove(cle) else deplies.add(cle); dessiner() }
        bloc.addView(Ui.separateur(this))
        bloc.addView(entete)
        if (ouvert && liste != null) {
            val noms = (0 until liste.length()).mapNotNull { liste.optJSONObject(it)?.let(libelle) }.filter { it.isNotEmpty() }
            bloc.addView(Ui.marge(this, Ui.texte(this, noms.joinToString(" · "), 13f, Ui.TEXTE_2), bas = 6f))
        }
        return bloc
    }

    private fun planning(id: String, plages: JSONArray?): View {
        val bloc = Ui.colonne(this)
        bloc.addView(Ui.separateur(this))
        val cle = "$id:plages"
        val ouvert = cle in deplies
        val n = plages?.length() ?: 0
        val entete = Ui.rangee(this)
        entete.addView(Ui.poids(Ui.texte(this, getString(R.string.parents_planning_n, n), 14f, if (n > 0) Ui.TEXTE else Ui.TEXTE_3, gras = true)))
        entete.addView(Ui.texte(this, if (ouvert) "▴" else "▾", 14f, Ui.TEXTE_2).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
        entete.setPadding(0, Ui.dp(this, 8f), 0, Ui.dp(this, 8f))
        // Lot 6 (APP-21) : l'accordéon fait 48 dp de haut, se prend à la télécommande et dit s'il est ouvert.
        entete.minimumHeight = Ui.dp(this, Ui.CIBLE_MIN); entete.isClickable = true
        Ui.anneauFocus(entete, Ui.themeActif(this), 12f, agrandir = false)
        entete.contentDescription = getString(if (ouvert) R.string.lot6_deplie else R.string.lot6_replie, getString(R.string.parents_planning_n, n))
        entete.setOnClickListener { if (ouvert) deplies.remove(cle) else deplies.add(cle); dessiner() }
        bloc.addView(entete)
        if (!ouvert || plages == null) return bloc
        // L'initiale de chaque jour, lundi d'abord, dans la langue de l'appareil (« L M M J V S D », « M T W T F S S »).
        val semaine = java.text.DateFormatSymbols.getInstance(Tr.locale()).weekdays
        val jours = listOf(java.util.Calendar.MONDAY, java.util.Calendar.TUESDAY, java.util.Calendar.WEDNESDAY, java.util.Calendar.THURSDAY,
            java.util.Calendar.FRIDAY, java.util.Calendar.SATURDAY, java.util.Calendar.SUNDAY).map { semaine[it].take(1).uppercase(Tr.locale()) }
        for (i in 0 until plages.length()) {
            val p = plages.optJSONObject(i) ?: continue
            val plage = p
            val actif = p.optBoolean("actif")
            val activee = p.optBoolean("active", true)
            val r = Ui.rangee(this)
            val col = Ui.colonne(this)
            val quoi = when (p.optString("portee")) {
                "etiquettes" -> {
                    val e = p.optJSONArray("etiquettes")
                    getString(R.string.parents_coupe, (0 until (e?.length() ?: 0)).joinToString(", ") { Categories.libelle(this, e!!.optString(it)) })
                }
                "elements" -> getString(R.string.parents_coupe_elements)
                else -> getString(R.string.parents_ferme_tout)
            }
            val js = p.optJSONArray("jours")
            val lettres = (0 until 7).joinToString(" ") { if (js?.optBoolean(it, true) != false) jours[it] else "·" }
            col.addView(Ui.texte(this, "${p.optString("nom").ifEmpty { getString(R.string.gestion_plage_defaut) }}  ${p.optString("debut")} → ${p.optString("fin")}",
                14f, if (!activee) Ui.TEXTE_3 else if (actif) Ui.OR else Ui.TEXTE, gras = actif))
            col.addView(Ui.texte(this, "$quoi   $lettres", 12f, Ui.TEXTE_2))
            r.addView(Ui.poids(col))
            if (actif) r.addView(Ui.chip(this, getString(R.string.parents_en_cours), Ui.OR))
            else if (!activee) r.addView(Ui.chip(this, getString(R.string.parents_desactivee), Ui.TEXTE_3))
            r.setOnClickListener { GestionAppareil.plage(this, id, plage) { agir(it) } }
            bloc.addView(Ui.marge(this, r, haut = 4f, bas = 6f))
        }
        bloc.addView(Ui.marge(this, Ui.boutonSecondaire(this, getString(R.string.parents_ajouter_plage)) { GestionAppareil.nouvellePlage(this, id) { agir(it) } }, haut = 4f))
        return bloc
    }

    private fun repondreAcces(pc: String, demande: String, decision: String, minutes: Int = Durees.ACCES_TEMPORAIRE, mode: String = "") {
        // HA 2.5.1 : les éléments décochés d'une demande groupée restent fermés ; tout coché, la réponse vaut pour tout.
        val decoches = exclus[demande].orEmpty()
        val elements = if (decoches.isEmpty() || decision == "non") null
            else JSONArray(groupe[demande].orEmpty().filter { it !in decoches })
        Garde.exigerPour(this, RAISON, CHEMIN, JSONObject().put("action", "acces").put("decision", decision)) {
            Geste.enCours()
            Acces.repondre(this, pc, demande, decision, minutes, mode, elements) { res ->
                // Réglée (ou déjà réglée ailleurs) : elle disparaît tout de suite, puis tout est relu.
                if (res.ok) { demandes = Geste.sans(demandes, demande); dessiner() } else Geste.relacher()
                charger()
            }
        }
    }

    private fun confirmerFermeture(id: String, nom: String) {
        Ui.dialogue(this)
            .setTitle(getString(R.string.parents_fermer_titre, nom))
            .setItems(arrayOf(resources.getQuantityString(R.plurals.temps_minutes, 30, 30), resources.getQuantityString(R.plurals.temps_heures, 1, 1),
                getString(R.string.parents_jusqua_creneau))) { _, k ->
                charger("fermer", id, listOf(30, 60, 0)[k])
            }
            .setNegativeButton(R.string.commun_annuler, null)
            .show()
    }

    private fun envoyerMot(id: String, nom: String) {
        val champ = Ui.champ(this, getString(R.string.parents_mot_exemple), "")
        val cadre = Ui.colonne(this, Ui.dp(this, 20f)).apply { addView(champ) }
        Ui.dialogue(this)
            .setTitle(getString(R.string.parents_mot_titre, nom))
            .setView(cadre)
            .setPositiveButton(R.string.parents_envoyer) { _, _ ->
                val t = champ.text.toString().trim()
                if (t.isNotEmpty()) charger("message", id, 0, t)
            }
            .setNegativeButton(R.string.commun_annuler, null)
            .show()
    }

    private fun ligneInfo(cle: String, valeur: String): View {
        // 1.73.0 : écran étroit : le libellé au-dessus, la valeur dessous, chacun sur toute la largeur.
        if (Ui.etroit(this)) {
            val col = Ui.colonne(this)
            col.addView(Ui.texte(this, cle, 13f, Ui.TEXTE_2))
            col.addView(Ui.texte(this, valeur, 13f, Ui.TEXTE, gras = true))
            return Ui.marge(this, col, haut = 4f, bas = 2f)
        }
        val r = Ui.rangee(this)
        r.addView(Ui.poids(Ui.texte(this, cle, 13f, Ui.TEXTE_2)))
        r.addView(Ui.texte(this, valeur, 13f, Ui.TEXTE, gras = true).apply {
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.8f)
        })
        return Ui.marge(this, r, haut = 4f, bas = 2f)
    }

    private fun duree(min: Int): String = if (min < 60) getString(R.string.tr_duree_min, min)
        else getString(R.string.tr_duree_h_min, min / 60, "%02d".format(java.util.Locale.ROOT, min % 60))
    private fun blocPauseGenerale() {
        val fin = pauseFin?.takeIf { it > System.currentTimeMillis() }
        if (fin == null) {
            racine.addView(Ui.marge(this, Ui.boutonSecondaire(this, getString(R.string.parents_pause_generale)) { poserPauseGenerale() }, bas = 14f))
            return
        }
        val c = Ui.carte(this, Ui.CARTE_HAUTE)
        c.addView(Ui.texte(this, PauseGenerale.banniere(fin), 16f, Ui.OR, gras = true))
        c.addView(Ui.marge(this, Ui.texte(this, getString(R.string.parents_pause_generale_texte), 13f, Ui.TEXTE_2), haut = 4f))
        c.addView(Ui.marge(this, Ui.boutonPrimaire(this, getString(R.string.parents_reprendre)) { charger(PauseGenerale.FIN) }, haut = 12f))
        racine.addView(Ui.marge(this, c, bas = 14f))
    }

    /** Refonte UX, lot 2 (A8) : la limite est dite avant le choix, les durées proposées la respectent, et la durée
     *  choisie est confirmée avant de tout fermer. */
    private fun poserPauseGenerale() {
        Temps.choisir(this, getString(R.string.parents_pause_generale_titre), 0L) { choix ->
            if (!PauseGenerale.dureeValide(choix.minutes)) toast(getString(R.string.parents_pause_generale_max), OeilModele.Genre.ERREUR)
            else charger(PauseGenerale.ACTION, "", choix.minutes)
        }
    }

    /** Les applis à garder (le dernier choix coché d'avance), puis la durée ; le reste de l'appareil se ferme. */
    private fun choisirConcentration(id: String, qui: String, conc: JSONObject) {
        val proposees = conc.optJSONArray("proposees") ?: JSONArray()
        val noms = (0 until proposees.length()).map { proposees.optJSONObject(it)?.optString("nom").orEmpty() }
        val libelles = (0 until proposees.length()).map { proposees.optJSONObject(it)?.optString("libelle").orEmpty().ifEmpty { noms[it] } }
        val retenues = conc.optJSONArray("apps")?.let { r -> (0 until r.length()).map { r.optString(it) }.toSet() } ?: emptySet()
        val coches = BooleanArray(noms.size) { noms[it] in retenues }
        val suite = {
            val gardees = JSONArray().apply { noms.forEachIndexed { k, n -> if (coches[k]) put(n) } }
            val durees = listOf(30 to resources.getQuantityString(R.plurals.temps_minutes, 30, 30), 45 to resources.getQuantityString(R.plurals.temps_minutes, 45, 45),
                60 to resources.getQuantityString(R.plurals.temps_heures, 1, 1), 90 to Tr.duree(90), 120 to resources.getQuantityString(R.plurals.temps_heures, 2, 2))
            Ui.dialogue(this).setTitle(R.string.parents_conc_pendant).setItems(durees.map { it.second }.toTypedArray()) { _, k ->
                agir(JSONObject().put("action", "concentration").put("pc", id).put("minutes", durees[k].first).put("apps", gardees))
            }.setNegativeButton(R.string.commun_annuler, null).show()
        }
        if (noms.isEmpty()) {
            Ui.dialogue(this).setTitle(getString(R.string.parents_conc_sur, qui))
                .setMessage(R.string.parents_conc_inconnues)
                .setPositiveButton(R.string.deva_continuer) { _, _ -> suite() }.setNegativeButton(R.string.commun_annuler, null).show()
            return
        }
        Ui.dialogue(this).setTitle(getString(R.string.parents_conc_gardees, qui))
            .setMultiChoiceItems(libelles.toTypedArray(), coches) { _, k, oui -> coches[k] = oui }
            .setPositiveButton(R.string.parents_suivant) { _, _ -> suite() }
            .setNegativeButton(R.string.commun_annuler, null).show()
    }

    private fun faireActionDemandee(a: String) {
        when (a) {
            A_PAUSE -> poserPauseGenerale()
            A_TEMPS -> choisirAppareilPourTemps()
        }
    }

    /** « Ajouter du temps » de l'écran principal : d'abord l'appareil, puis la durée habituelle. */
    private fun choisirAppareilPourTemps() {
        val liste = (0 until appareils.length()).mapNotNull { appareils.optJSONObject(it) }.filter { !it.optBoolean("parent") }
        if (liste.isEmpty()) { toast(getString(R.string.parents_aucun_pour_temps)); return }
        val noms = liste.map { a ->
            val prenom = a.optString("prenom"); val nom = a.optString("nom")
            val etat = getString(when { !a.optBoolean("en_ligne") -> R.string.parents_etat_hors_ligne; a.optBoolean("verrouille") -> R.string.parents_etat_ferme; else -> R.string.parents_etat_ouvert })
            getString(R.string.parents_appareil_etat, if (prenom.isNotEmpty() && prenom != nom) "$prenom · $nom" else nom, etat)
        }.toTypedArray()
        Ui.dialogue(this).setTitle(R.string.parents_ajouter_temps_a).setItems(noms) { _, k ->
            val a = liste[k]; val id = a.optString("id")
            val prochain = Etat.lireDate(a.optString("prochain"))
            Temps.choisir(this, getString(R.string.parents_ouvrir_pour, a.optString("prenom").ifEmpty { a.optString("nom") }), if (a.optBoolean("verrouille")) prochain else 0L) { ch ->
                charger("ouvrir", id, ch.minutes)
            }
        }.setNegativeButton(R.string.commun_annuler, null).show()
    }

    private fun toast(t: String, genre: OeilModele.Genre = OeilModele.Genre.INFO) = Ui.toast(this, t, genre = genre)

    companion object {
        /** La raison montrée par Garde (code ou empreinte), dans la langue de l'appareil. */
        val RAISON: String get() = Tr.s(R.string.parents_raison)
        /** Le geste à faire à l'ouverture : [A_PAUSE] ou [A_TEMPS]. */
        const val EXTRA_ACTION = "action"
        const val A_PAUSE = "pause"
        const val A_TEMPS = "temps"
        /** Refonte lot 3 : l'entité de la personne à déplier à l'ouverture (retour depuis sa fiche). */
        const val EXTRA_DEPLIER = "deplier"
        /** Le point d'entrée de l'espace gestion (lecture et gestes). */
        const val CHEMIN = "/api/pc_parental/parent"
        /** Le corps d'une simple lecture (aucune action) : l'appareil et son secret. */
        fun corpsLecture(cfg: Config): JSONObject = JSONObject().put("id", cfg.id).put("secret", cfg.secret)
        /** Refonte lot 3 : relit l'espace gestion sans rien changer (FichePersonneActivity). Hors du fil principal. */
        fun relire(ctx: android.content.Context, cfg: Config): JSONObject = Net.post(ctx, cfg, CHEMIN, corpsLecture(cfg))
        fun ouvrir(ctx: android.content.Context, action: String? = null) =
            ctx.startActivity(Intent(ctx, ParentsActivity::class.java).apply { if (action != null) putExtra(EXTRA_ACTION, action) })
    }
}
