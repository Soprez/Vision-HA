// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/**
 * Refonte lot 3 : la fiche allégée d'une personne, pour un Décideur (ouverte depuis l'espace gestion,
 * ParentsActivity). Ce qui se passe aujourd'hui, ses horaires, ses contenus, ses réveils, ses récompenses, ses
 * écrans et son rôle ; ‹ › passent à la personne précédente ou suivante. Seul geste ici : « +30 min » sur tous ses
 * appareils (derrière Garde) ; le reste se règle dans Home Assistant, comme le dit la note du bas.
 *
 * Relit l'espace gestion toutes les 20 s tant qu'elle est visible (même appel que ParentsActivity, en lecture).
 * L'analyse de la fiche est dans FichePersonne (sans Android, essayée par FichePersonneTest).
 */
class FichePersonneActivity : Activity() {
    private lateinit var racine: LinearLayout
    private val cfg by lazy { Config(this) }
    private val principal = Handler(Looper.getMainLooper())
    private var entite = ""
    /** Les personnes à parcourir (entité, prénom), dans l'ordre de l'espace gestion. */
    private val liste = ArrayList<Pair<String, String>>()
    private var reponse: JSONObject? = null
    private var erreur = ""
    private var chargement = false
    private val tele by lazy { ReglagesTvActivity.estTele(this) }
    private val rafraichir = object : Runnable {
        override fun run() { charger(); principal.postDelayed(this, 20_000) }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Ui.charger(this)
        if (android.os.Build.VERSION.SDK_INT >= 21) { window.statusBarColor = Ui.FOND; window.navigationBarColor = Ui.FOND }
        entite = b?.getString(EXTRA_ENTITE) ?: intent?.getStringExtra(EXTRA_ENTITE).orEmpty()
        val entites = intent?.getStringArrayListExtra(EXTRA_ENTITES).orEmpty()
        val prenoms = intent?.getStringArrayListExtra(EXTRA_PRENOMS).orEmpty()
        entites.forEachIndexed { i, e -> liste += e to prenoms.getOrElse(i) { "" } }
        val scroll = ScrollView(this).apply { setBackgroundColor(Ui.FOND); isFillViewport = true }
        val cadre = FrameLayout(this)
        racine = Ui.colonne(this, Ui.dp(this, 20f))
        // Tablette : une colonne centrée, 720 dp au plus ; téléphone et télé : toute la largeur.
        val largeur = if (resources.configuration.screenWidthDp >= 600) minOf(Ui.dp(this, 720f), resources.displayMetrics.widthPixels)
            else ViewGroup.LayoutParams.MATCH_PARENT
        cadre.addView(racine, FrameLayout.LayoutParams(largeur, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        scroll.addView(cadre)
        setContentView(scroll)
        donneesEssai?.let { try { reponse = JSONObject(it) } catch (_: Exception) {} }
        dessiner()
    }

    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); out.putString(EXTRA_ENTITE, entite) }
    override fun onResume() { super.onResume(); principal.post(rafraichir) }
    override fun onPause() { super.onPause(); principal.removeCallbacks(rafraichir) }

    // ------------------------------------------------------------ Réseau

    private fun charger() {
        if (donneesEssai != null) return  // essais : la réponse est déjà là, rien à relire
        if (chargement) return
        chargement = true
        Thread {
            try {
                val r = ParentsActivity.relire(this, cfg)
                Modules.memoriser(this, r)
                reponse = r; erreur = ""
                principal.post { dessiner() }
            } catch (e: Net.Echec) {
                erreur = getString(if (e.code == 403) R.string.geste_echec_droits else R.string.parents_err_reponse)
                principal.post { dessiner() }
            } catch (_: Exception) {
                erreur = getString(R.string.parents_err_injoignable)
                principal.post { dessiner() }
            } finally { chargement = false }
        }.start()
    }

    /** « +30 min » sur tous ses appareils : le même geste que l'espace gestion (action « temps », « appareils »). */
    private fun ajouterTemps(appareils: List<String>) {
        if (appareils.isEmpty()) return
        Garde.exigerOctroi(this, ParentsActivity.RAISON) {  // FAILLE (l'auteur, 16-10) : à chaque fois, sans grâce
            Geste.enCours()
            Thread {
                try {
                    val corps = ParentsActivity.corpsLecture(cfg).put("action", "temps").put("pc", appareils[0]).put("minutes", 30)
                        .put("appareils", JSONArray(appareils))
                    val r = Net.post(this, cfg, ParentsActivity.CHEMIN, corps)
                    Modules.memoriser(this, r)
                    reponse = r; erreur = ""
                    val retour = r.optString("retour", "")
                    principal.post { if (retour.isNotEmpty()) toast(retour); dessiner() }
                } catch (e: Net.Echec) {
                    val phrase = e.corps?.optString("retour").orEmpty()
                    principal.post { toast(phrase.ifEmpty { getString(if (e.code == 403) R.string.geste_echec_droits else R.string.geste_echec_refuse) }); Geste.relacher() }
                } catch (_: Exception) {
                    principal.post { toast(getString(R.string.parents_err_injoignable)); Geste.relacher() }
                }
            }.start()
        }
    }

    // ------------------------------------------------------------ Écran

    private fun dessiner() {
        racine.removeAllViews()
        racine.addView(Ui.marque(this))
        val r = reponse
        val personnes = FichePersonne.personnes(r?.toString())
        // Sans liste reçue (ouverture directe) : les personnes de la réponse, sauf les Décideurs.
        if (liste.isEmpty()) personnes.filter { !it.decideur }.forEach { liste += it.entite to it.prenom }
        val p = personnes.firstOrNull { it.entite == entite }
        val prenom = p?.prenom?.ifEmpty { null } ?: liste.firstOrNull { it.first == entite }?.second.orEmpty()
        entete(prenom)

        if (erreur.isNotEmpty()) {
            val c = Ui.carte(this, Ui.CARTE_HAUTE)
            c.addView(Ui.texte(this, erreur, 14f, Ui.ROUGE))
            racine.addView(c)
        }
        if (p == null) {
            if (erreur.isEmpty()) racine.addView(Ui.carte(this).apply { addView(Ui.texte(this@FichePersonneActivity, getString(R.string.commun_chargement), 14f, Ui.TEXTE_2)) })
            premierFocus(); return
        }
        val m = Modules.actifs(this)
        val fiche = p.fiche
        val tousAppareils = r?.optJSONArray("appareils") ?: JSONArray()
        val parId = HashMap<String, JSONObject>()
        for (i in 0 until tousAppareils.length()) tousAppareils.optJSONObject(i)?.let { parId[it.optString("id")] = it }
        val siens = p.appareils.mapNotNull { parId[it] }
        val principalAppareil = siens.firstOrNull()

        // --- Résumé : le temps du jour, ses appareils et leur état, « +30 min ».
        val resume = section(getString(R.string.fiche_resume))
        if (p.tempsActif != null && Modules.actifDans(m, Modules.TEMPS))
            resume.addView(Ui.texte(this, getString(R.string.fiche_temps_aujourdhui, Profil.duree(p.tempsActif)), 18f, Ui.OR, gras = true))
        val n = if (siens.isNotEmpty()) siens.size else p.appareils.size
        resume.addView(Ui.marge(this, Ui.texte(this, resources.getQuantityString(R.plurals.parents_n_appareils, n, n), 14f, Ui.TEXTE_2), haut = 4f))
        for (a in siens) resume.addView(ligneAppareil(a))
        if (p.appareils.isNotEmpty() && Modules.horaires(m))
            resume.addView(Ui.marge(this, bouton(Ui.boutonPrimaire(this, getString(R.string.parents_30_min_ses_appareils)) { ajouterTemps(p.appareils) }), haut = 12f))

        // --- Horaires : le prochain changement de son appareil principal.
        if (fiche?.module(FichePersonne.M_PLAGES) == true) {
            val h = section(getString(R.string.fiche_horaires))
            val maintenant = System.currentTimeMillis()
            val prochain = Etat.lireDate(principalAppareil?.optString("prochain").orEmpty())
            val prochainVerrou = Etat.lireDate(principalAppareil?.optString("prochain_verrou").orEmpty())
            val ferme = principalAppareil?.optBoolean("verrouille") == true
            val quand = when {
                ferme && prochain > maintenant -> getString(R.string.parents_reouverture_a, Etat.heure(prochain))
                prochainVerrou > maintenant -> getString(R.string.parents_fermeture_prevue, Etat.heure(prochainVerrou))
                else -> null
            }
            if (quand != null) {
                h.addView(Ui.texte(this, getString(R.string.fiche_prochain_changement), 13f, Ui.TEXTE_2))
                h.addView(Ui.texte(this, quand, 15f, Ui.TEXTE, gras = true))
            } else h.addView(Ui.texte(this, getString(R.string.fiche_aucun_horaire), 14f, Ui.TEXTE_2))
            if (siens.isNotEmpty()) h.addView(Ui.marge(this, bouton(Ui.boutonSecondaire(this, getString(R.string.fiche_voir_appareils)) { voirAppareils() }), haut = 12f))
        }

        // --- Contenus : les catégories de son appareil principal, puis les règles selon le lieu.
        val avecCategories = principalAppareil != null && Modules.actifDans(m, Modules.BLOCAGE)
        val avecRegles = fiche?.module(FichePersonne.M_REGLES_LIEU) == true
        if (avecCategories || avecRegles) {
            val c = section(getString(R.string.fiche_contenus))
            if (avecCategories) c.addView(bouton(Ui.boutonSecondaire(this, getString(R.string.cat_titre)) {
                CategoriesActivity.fiche = principalAppareil; CategoriesActivity.cats = r?.optJSONArray("categories") ?: JSONArray()
                startActivity(Intent(this, CategoriesActivity::class.java))
            }))
            if (avecRegles) {
                c.addView(Ui.marge(this, Ui.texte(this, getString(R.string.fiche_selon_lieu), 13f, Ui.TEXTE_2), haut = if (avecCategories) 12f else 0f))
                if (fiche!!.reglesLieu.isEmpty()) c.addView(Ui.texte(this, getString(R.string.fiche_aucune_regle_lieu), 14f, Ui.TEXTE))
                for (regle in fiche.reglesLieu)
                    c.addView(Ui.marge(this, Ui.texte(this, FichePersonne.texteRegle(regle) { Categories.libelle(this, it) }, 14f, Ui.TEXTE), haut = 4f))
            }
        }

        // --- Réveils.
        if (fiche?.module(FichePersonne.M_REVEIL) == true) {
            val c = section(getString(R.string.fiche_reveils))
            if (fiche.reveils.isEmpty()) c.addView(Ui.texte(this, getString(R.string.fiche_aucun_reveil), 14f, Ui.TEXTE_2))
            fiche.reveils.forEachIndexed { i, rv ->
                if (i > 0) c.addView(Ui.separateur(this))
                val nom = Ui.texte(this, rv.nom, 15f, Ui.TEXTE, gras = true)
                val pastille = Ui.chip(this, FichePersonne.etatReveil(rv), if (rv.actif) Ui.VERT else Ui.TEXTE_2)
                val haut = if (Ui.etroit(this)) Ui.colonne(this).apply {
                        addView(nom, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                        addView(Ui.marge(this@FichePersonneActivity, pastille, haut = 4f))
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT) }
                    else Ui.rangee(this).apply { addView(Ui.poids(nom)); addView(Ui.marge(this@FichePersonneActivity, pastille, gauche = 8f)) }
                c.addView(Ui.marge(this, haut, haut = 6f))
                if (rv.prochain > 0) c.addView(Ui.marge(this, Ui.texte(this, getString(R.string.fiche_reveil_prochain,
                    Tr.date(rv.prochain, "EEE") + " " + Tr.heure(rv.prochain)), 13f, Ui.TEXTE_2), haut = 2f, bas = 6f))
            }
        }

        // --- Récompenses.
        if (fiche?.module(FichePersonne.M_TEMPS_GAGNE) == true) {
            val c = section(getString(R.string.fiche_recompenses))
            c.addView(Ui.texte(this, FichePersonne.texteRecompense(fiche.recompenses), 14f, if (fiche.recompenses == null) Ui.TEXTE_2 else Ui.TEXTE))
        }

        // --- Sur ses écrans : les widgets de Home Assistant.
        if (fiche?.module(FichePersonne.M_ENTITES) == true) {
            val c = section(getString(R.string.fiche_ecrans))
            c.addView(Ui.texte(this, FichePersonne.texteWidgets(fiche.widgets), 14f, Ui.TEXTE))
        }

        // --- Rôle.
        val role = fiche?.roleNom?.ifEmpty { null } ?: p.role
        if (role.isNotEmpty()) section(getString(R.string.fiche_role)).addView(Ui.texte(this, role, 15f, Ui.TEXTE, gras = true))

        racine.addView(Ui.marge(this, Ui.texte(this, getString(R.string.fiche_note, p.prenom), 13f, Ui.TEXTE_3), haut = 6f))
        racine.addView(Ui.espace(this, 24f))
        premierFocus()
    }

    /** L'avatar (initiale), le prénom, et ‹ › pour passer à la personne précédente ou suivante. */
    private fun entete(prenom: String) {
        val r = Ui.rangee(this)
        val plusieurs = liste.size > 1
        if (plusieurs) r.addView(fleche("‹", R.string.fiche_precedente, -1))
        val d = Ui.dp(this, 52f)
        val avatar = TextView(this).apply {
            text = FichePersonne.initiale(prenom); textSize = 22f; gravity = Gravity.CENTER; includeFontPadding = false
            setTextColor(Ui.OR_SOMBRE); typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = Ui.fond(Ui.OR, d / 2f)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO  // le prénom est dit juste après
        }
        r.addView(avatar, LinearLayout.LayoutParams(d, d).apply { leftMargin = if (plusieurs) Ui.dp(this@FichePersonneActivity, 10f) else 0 })
        val titre = Ui.titre(this, prenom)
        // Écran étroit (grands caractères) : le prénom passe sous l'avatar et les flèches, sur toute la largeur.
        if (Ui.etroit(this)) {
            r.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            if (plusieurs) r.addView(fleche("›", R.string.fiche_suivante, 1))
            racine.addView(r, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            racine.addView(titre, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Ui.dp(this@FichePersonneActivity, 8f); bottomMargin = Ui.dp(this@FichePersonneActivity, 8f) })
            return
        }
        r.addView(Ui.marge(this, Ui.poids(titre), gauche = 12f, droite = 8f))
        if (plusieurs) r.addView(fleche("›", R.string.fiche_suivante, 1))
        racine.addView(Ui.marge(this, r, bas = 8f))
    }

    private fun fleche(signe: String, description: Int, pas: Int): View = bouton(Ui.boutonSecondaire(this, signe) { changer(pas) }.apply {
        contentDescription = getString(description)
        textSize = 20f
        minWidth = Ui.dp(this@FichePersonneActivity, Ui.CIBLE_MIN); minimumWidth = minWidth
    })

    private fun changer(pas: Int) {
        val suivante = FichePersonne.voisine(liste.map { it.first }, entite, pas) ?: return
        if (suivante == entite) return
        entite = suivante
        dessiner()
        if (donneesEssai == null && reponse == null) charger()
    }

    /** « Voir les appareils » : retour à l'espace gestion, cette personne dépliée. */
    private fun voirAppareils() {
        startActivity(Intent(this, ParentsActivity::class.java).putExtra(ParentsActivity.EXTRA_DEPLIER, entite)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    /** Une section : son titre, puis une carte où l'on range son contenu (renvoyée). */
    private fun section(titre: String): LinearLayout {
        racine.addView(Ui.section(this, titre))
        val c = Ui.carte(this, Ui.CARTE_HAUTE)
        racine.addView(c)
        return c
    }

    /** Un appareil : son nom et son état (« Ouvert », « Fermé », « Hors ligne »), comme dans l'espace gestion. */
    private fun ligneAppareil(a: JSONObject): View {
        val (lib, coul) = when {
            !a.optBoolean("en_ligne") -> getString(R.string.commun_hors_ligne) to Ui.TEXTE_2
            a.optBoolean("verrouille") -> getString(R.string.statut_ferme) to Ui.ROUGE
            else -> getString(R.string.statut_ouvert) to Ui.VERT
        }
        val nom = Ui.texte(this, a.optString("nom"), 15f, Ui.TEXTE)
        val chip = Ui.chip(this, lib, coul)
        // Écran étroit (grands caractères) : la pastille passe sous le nom, qui garde toute la largeur.
        if (Ui.etroit(this)) {
            val col = Ui.colonne(this)
            col.addView(nom, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            col.addView(Ui.marge(this, chip, haut = 4f))
            col.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            return Ui.marge(this, col, haut = 8f)
        }
        val r = Ui.rangee(this)
        r.addView(Ui.poids(nom))
        r.addView(Ui.marge(this, chip, gauche = 8f))
        return Ui.marge(this, r, haut = 8f)
    }

    /** À la télécommande : l'anneau de focus de Vision sur chaque bouton. */
    private fun <T : View> bouton(v: T): T {
        if (tele) Ui.anneauFocus(v, Ui.themeActif(this), 999f, agrandir = false)
        return v
    }

    private fun premierFocus() {
        if (!tele) return
        racine.post { if (currentFocus == null || !currentFocus!!.isAttachedToWindow) racine.focusSearch(View.FOCUS_DOWN)?.requestFocus() ?: racine.requestFocus() }
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    companion object {
        const val EXTRA_ENTITE = "entite"
        const val EXTRA_ENTITES = "entites"
        const val EXTRA_PRENOMS = "prenoms"
        /** Essais (AntiDebordementTest) : une réponse /parent toute faite ; non nulle, rien n'est lu sur le réseau. */
        @Volatile @JvmStatic var donneesEssai: String? = null

        fun ouvrir(ctx: Context, entite: String, personnes: List<Pair<String, String>>) = ctx.startActivity(intention(ctx, entite, personnes))

        fun intention(ctx: Context, entite: String, personnes: List<Pair<String, String>>): Intent =
            Intent(ctx, FichePersonneActivity::class.java).putExtra(EXTRA_ENTITE, entite)
                .putStringArrayListExtra(EXTRA_ENTITES, ArrayList(personnes.map { it.first }))
                .putStringArrayListExtra(EXTRA_PRENOMS, ArrayList(personnes.map { it.second }))
    }
}
