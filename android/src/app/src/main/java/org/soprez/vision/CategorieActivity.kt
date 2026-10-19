// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONArray
import org.json.JSONObject

/**
 * HA 2.5.1 (maquette du 13-10, validée en essai le 14-10) : une catégorie d'un Demandeur, élément par élément.
 *
 * Chaque appli, logiciel ou site a son propre interrupteur ; le toucher ouvre le choix du mode (Suivre la
 * catégorie, le défaut ; Pour un temps ; Exception ; Toujours disponible ; Bloquer) et c'est ce choix qui agit.
 * Les logiciels sont des accordéons (repliés) : dépliés, ils montrent leurs services (qui suivent toujours le
 * logiciel) et leurs sites (qui le suivent tant qu'ils n'ont pas de réglage propre). Les sites sans logiciel
 * viennent ensuite, les fermés d'abord. La recherche retrouve un élément où qu'il soit rangé, et dit pourquoi il
 * est fermé. Sans catégorie ([etiquette] vide), l'écran montre les blocages récents de la personne.
 * À la télé, tout se lit et rien ne se change.
 */
class CategorieActivity : Activity() {
    private lateinit var racine: LinearLayout
    private lateinit var liste: LinearLayout
    private val principal = Handler(Looper.getMainLooper())
    private var detail: JSONObject? = null
    private var recents: JSONArray? = null
    private var resultats: JSONArray? = null
    private var recherche = ""
    private var sitesMax = 8
    private var erreur = ""
    private var lecture = false
    private val deplies = HashSet<String>()
    private val chercher = Runnable { lancerRecherche() }

    companion object {
        @Volatile var pc = ""
        @Volatile var etiquette = ""
        @Volatile var prenom = ""
        /** Essais (recette visuelle) : les données sont posées par le test, rien n'est lu chez Home Assistant. */
        @Volatile var horsLigne = false
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Ui.charger(this)
        if (pc.isEmpty()) { finish(); return }
        lecture = ReglagesTvActivity.estTele(this)
        if (android.os.Build.VERSION.SDK_INT >= 21) { window.statusBarColor = Ui.FOND; window.navigationBarColor = Ui.FOND }
        val scroll = ScrollView(this).apply { setBackgroundColor(Ui.FOND); isFillViewport = true }
        racine = Ui.colonne(this, Ui.dp(this, 20f))
        scroll.addView(racine); setContentView(scroll)
        racine.addView(Ui.marque(this))
        val titre = if (etiquette.isEmpty()) getString(R.string.el_recents_titre) else Categories.libelle(this, etiquette)
        racine.addView(Ui.marge(this, Ui.titre(this, titre), gauche = 2f))
        if (prenom.isNotEmpty()) racine.addView(Ui.marge(this, Ui.sousTitre(this, prenom), gauche = 2f, bas = 10f))
        if (lecture) racine.addView(Ui.marge(this, Ui.texte(this, getString(R.string.el_tele_lecture, Vocabulaire.decideur(this)), 13f, Ui.TEXTE_2), bas = 10f))
        if (etiquette.isNotEmpty()) {
            // L'indication courte tient à 360 dp et police 130 % ; la phrase entière est lue par TalkBack.
            val champ = Ui.champ(this, getString(R.string.el_rechercher_court), "").apply {
                tag = "el_recherche"; contentDescription = getString(R.string.el_rechercher_indice)
            }
            champ.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    recherche = s?.toString()?.trim().orEmpty()
                    principal.removeCallbacks(chercher)
                    if (recherche.length < 2) { resultats = null; dessiner() } else principal.postDelayed(chercher, 350)
                }
            })
            racine.addView(champ)
        }
        // Toute la largeur : sans quoi, sur tablette, la liste et ses cartes se réduisent à leur contenu.
        liste = Ui.colonne(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT) }
        racine.addView(Ui.marge(this, liste, haut = 10f))
        dessiner()
        charger()
    }

    // ------------------------------------------------------------------ échanges avec Home Assistant

    private fun lire(corps: JSONObject, ok: (JSONObject) -> Unit) {
        val cfg = Config(this)
        Thread {
            try {
                val r = Net.post(this, cfg, "/api/pc_parental/parent", corps.put("id", cfg.id).put("secret", cfg.secret).put("pc", pc))
                principal.post { erreur = ""; ok(r); dessiner() }
            } catch (_: Exception) {
                principal.post { erreur = getString(R.string.el_chargement_echec); dessiner() }
            }
        }.start()
    }

    private fun charger() {
        if (horsLigne) return
        if (etiquette.isEmpty()) lire(JSONObject().put("action", "recents")) { recents = it.optJSONArray("recents") ?: JSONArray() }
        else lire(JSONObject().put("action", "categorie").put("etiquette", etiquette).put("sites_max", sitesMax)) { detail = it }
        if (recherche.length >= 2) lancerRecherche()
    }

    private fun lancerRecherche() {
        val texte = recherche
        if (texte.length < 2) return
        lire(JSONObject().put("action", "recherche").put("texte", texte)) { r ->
            if (texte == recherche) resultats = r.optJSONArray("resultats") ?: JSONArray()
        }
    }

    /** Un geste du Décideur (choix du mode, lien d'un site) : empreinte ou code, puis tout est relu. */
    private fun agir(geste: JSONObject) = Garde.exigerPour(this, ParentsActivity.RAISON, "/api/pc_parental/parent", geste) {
        val cfg = Config(this)
        Thread {
            var ok = false
            val texte = try {
                val r = Net.post(this, cfg, "/api/pc_parental/parent", geste.put("id", cfg.id).put("secret", cfg.secret).put("pc", pc))
                ok = true
                r.optString("retour").ifEmpty { getString(R.string.cat_c_est_fait) }
            } catch (e: Net.Echec) {
                e.corps?.optString("retour").orEmpty().ifEmpty { Geste.messageEchec(e.code) }
            } catch (e: Exception) { ReseauLocal.message(this@CategorieActivity, e) }
            principal.post {
                Ui.toast(this, texte, court = true, genre = if (ok) OeilModele.Genre.SUCCES else OeilModele.Genre.ERREUR)
                charger()
            }
        }.start()
    }

    // ------------------------------------------------------------------ dessin

    private fun dessiner() {
        liste.removeAllViews()
        if (erreur.isNotEmpty()) liste.addView(Ui.marge(this, Ui.texte(this, erreur, 14f, Ui.ROUGE), bas = 8f))
        val res = resultats
        when {
            res != null && recherche.length >= 2 -> dessinerResultats(res)
            etiquette.isEmpty() -> dessinerRecents()
            else -> dessinerCategorie()
        }
        liste.addView(Ui.espace(this, 24f))
    }

    private fun dessinerCategorie() {
        val d = detail ?: run { liste.addView(Ui.texte(this, getString(R.string.commun_chargement), 14f, Ui.TEXTE_2)); return }
        val logiciels = d.optJSONArray("logiciels") ?: JSONArray()
        if (logiciels.length() > 0) {
            liste.addView(Ui.section(this, getString(R.string.el_section_logiciels)))
            val c = plein(Ui.carte(this))
            for (i in 0 until logiciels.length()) {
                val lg = logiciels.optJSONObject(i) ?: continue
                if (i > 0) c.addView(Ui.separateur(this))
                dessinerLogiciel(c, lg)
            }
            liste.addView(c)
        }
        val sites = d.optJSONArray("sites") ?: JSONArray()
        val autres = d.optInt("autres")
        if (sites.length() > 0) {
            liste.addView(Ui.section(this, getString(R.string.el_section_sites_seuls, sites.length() + autres)))
            val c = plein(Ui.carte(this))
            for (i in 0 until sites.length()) {
                val s = sites.optJSONObject(i) ?: continue
                if (i > 0) c.addView(Ui.separateur(this))
                c.addView(ligne(s, "sites", logiciel = s.optString("logiciel")))
            }
            if (autres > 0) c.addView(Ui.marge(this, Ui.boutonSecondaire(this, resources.getQuantityString(R.plurals.el_voir_autres, autres, autres)) {
                sitesMax = 400; charger()
            }, haut = 8f))
            liste.addView(c)
        }
        if (logiciels.length() == 0 && sites.length() == 0) liste.addView(Ui.texte(this, getString(R.string.cat_rien_de_classe), 14f, Ui.TEXTE_2))
    }

    /** Un logiciel : sa ligne (accordéon), puis, déplié, ses services et ses sites. */
    private fun dessinerLogiciel(c: LinearLayout, lg: JSONObject) {
        val nom = lg.optString("nom")
        val libelle = lg.optString("libelle").ifEmpty { nom }
        val ouvert = nom in deplies
        c.addView(ligne(lg, "apps", accordeon = { if (!deplies.remove(nom)) deplies.add(nom); dessiner() }, deplie = ouvert))
        if (!ouvert) return
        val services = lg.optJSONArray("services") ?: JSONArray()
        val dedans = plein(Ui.colonne(this)).apply { setPadding(Ui.dp(this@CategorieActivity, 16f), 0, 0, Ui.dp(this@CategorieActivity, 8f)) }
        if (services.length() > 0) {
            dedans.addView(Ui.section(this, getString(R.string.el_services)))
            // Un service suit toujours son logiciel (une règle porte sur la famille) : son nom, sans interrupteur.
            for (i in 0 until services.length()) {
                val s = services.optJSONObject(i) ?: continue
                dedans.addView(Ui.marge(this, Ui.texte(this, s.optString("nom"), 14f, Ui.TEXTE), bas = 4f))
            }
            dedans.addView(Ui.texte(this, getString(R.string.el_services_suivent), 12f, Ui.TEXTE_3))
        }
        val sites = lg.optJSONArray("sites") ?: JSONArray()
        if (sites.length() > 0) {
            dedans.addView(Ui.section(this, getString(R.string.el_sites)))
            for (i in 0 until sites.length()) {
                val s = sites.optJSONObject(i) ?: continue
                if (i > 0) dedans.addView(Ui.separateur(this))
                dedans.addView(ligne(s, "sites", logiciel = libelle, nomLogiciel = nom))
            }
        }
        c.addView(dedans)
    }

    /**
     * Une ligne : le nom, son état en toutes lettres (badge), la raison s'il est fermé, et l'interrupteur (vert
     * ouvert, sinon vide) qui ouvre le choix du mode. [accordeon] : toucher le nom déplie le logiciel.
     */
    private fun ligne(o: JSONObject, genre: String, logiciel: String = "", nomLogiciel: String = "",
                      accordeon: (() -> Unit)? = null, deplie: Boolean = false): View {
        val nom = o.optString("nom")
        val libelle = o.optString("libelle").ifEmpty { nom }
        val ouvertEl = o.optBoolean("ouvert")
        val bloc = plein(Ui.colonne(this))
        val r = plein(Ui.rangee(this))
        val col = Ui.colonne(this)
        val tete = if (accordeon != null) (if (deplie) "▾ " else "▸ ") + libelle else libelle
        col.addView(Ui.texte(this, tete, 15f, Ui.TEXTE, gras = accordeon != null))
        val badges = Pastilles(this, Ui.dp(this, 6f))
        badges.addView(Ui.chip(this, badge(o, logiciel), if (ouvertEl) Ui.VERT else Ui.ROUGE))
        if (o.optBoolean("suit") && !o.optBoolean("par_logiciel") && logiciel.isNotEmpty()) badges.addView(Ui.chip(this, getString(R.string.el_suit, logiciel), Ui.TEXTE_3))
        if (o.optBoolean("attente")) badges.addView(Ui.chip(this, getString(R.string.el_attente), Ui.OR))
        else if (o.optBoolean("auto")) badges.addView(Ui.chip(this, getString(R.string.el_auto), Ui.TEXTE_3))
        col.addView(Ui.marge(this, badges, haut = 4f))
        o.optString("raison").takeIf { it.isNotEmpty() && !ouvertEl }?.let { col.addView(Ui.marge(this, Ui.texte(this, it, 12f, Ui.TEXTE_2), haut = 2f)) }
        if (accordeon != null) {
            col.isClickable = true; col.isFocusable = true
            col.setOnClickListener { accordeon() }
        }
        r.addView(Ui.poids(col))
        val verrou = o.optString("ferme_par") in setOf("lieu", "securite", "liste_noire")
        if (!lecture) {
            val sw = Ui.interrupteur(this, ouvertEl, accent = Ui.VERT,
                description = getString(R.string.el_mode_titre, libelle) + " · " + badge(o, logiciel))
            sw.tag = "el_inter:$nom"
            if (verrou) sw.isEnabled = false
            sw.setOnClickListener { sw.isChecked = ouvertEl; choixMode(o, genre, libelle) }
            r.addView(Ui.marge(this, sw, gauche = 8f))
        }
        r.setPadding(0, Ui.dp(this, 8f), 0, Ui.dp(this, 8f))
        bloc.addView(r)
        // Sous un logiciel : faire suivre un site lié en attente, ou délier un site (décision du projet du 14-10).
        if (!lecture && nomLogiciel.isNotEmpty() && genre == "sites") {
            val rb = Pastilles(this, Ui.dp(this, 6f))
            if (o.optBoolean("attente")) rb.addView(Ui.boutonSecondaire(this, getString(R.string.el_suivre_logiciel, logiciel)) {
                agir(JSONObject().put("action", "lier_domaine").put("nom", nomLogiciel).put("domaine", nom).put("lier", true))
            })
            // Délier : un petit lien (geste rare), cible tactile de 48 dp quand même.
            rb.addView(Ui.texte(this, "✕ " + getString(R.string.el_delier), 13f, Ui.TEXTE_2).apply {
                minHeight = Ui.dp(this@CategorieActivity, 48f); gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(Ui.dp(this@CategorieActivity, 4f), 0, Ui.dp(this@CategorieActivity, 12f), 0)
                isClickable = true; isFocusable = true; tag = "el_delier:$nom"
                setOnClickListener { agir(JSONObject().put("action", "lier_domaine").put("nom", nomLogiciel).put("domaine", nom).put("lier", false)) }
            })
            bloc.addView(Ui.marge(this, rb, bas = 6f))
        }
        return bloc
    }

    /** Toute la largeur du parent (tablette, télé) : une colonne ajoutée sans paramètres se réduirait à son contenu. */
    private fun <T : View> plein(v: T): T = v.apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    /** L'état d'une ligne en toutes lettres. */
    private fun badge(o: JSONObject, logiciel: String): String {
        if (o.optBoolean("ouvert")) return when (o.optString("mode")) {
            "temps" -> getString(R.string.el_badge_temps, Tr.heure((o.optDouble("fin") * 1000).toLong()))
            "exception" -> getString(R.string.el_badge_exception)
            "toujours" -> getString(R.string.el_badge_toujours)
            else -> if (o.optBoolean("par_logiciel") && logiciel.isNotEmpty()) getString(R.string.el_badge_par_logiciel, logiciel) else getString(R.string.el_badge_ouvert)
        }
        return getString(when (o.optString("ferme_par")) {
            "nomme" -> R.string.el_badge_nomme
            "plage" -> R.string.el_badge_plage
            "temps" -> R.string.el_badge_temps_epuise
            "securite" -> R.string.el_badge_securite
            "lieu" -> R.string.el_badge_lieu
            "liste_noire" -> R.string.el_badge_liste_noire
            else -> R.string.el_badge_categorie
        })
    }

    /** Le choix du mode : Suivre la catégorie (le défaut), Pour un temps, Exception, Toujours disponible, Bloquer. */
    private fun choixMode(o: JSONObject, genre: String, libelle: String) {
        if (lecture) return
        val actuel = o.optString("mode").ifEmpty { "categorie" }
        val modes = listOf("categorie", "temps", "exception", "toujours", "bloquer")
        val noms = listOf(R.string.el_mode_categorie, R.string.el_mode_temps, R.string.el_mode_exception, R.string.el_mode_toujours, R.string.el_mode_bloquer)
            .mapIndexed { k, id -> getString(id).let { if (modes[k] == actuel) getString(R.string.el_mode_actuel, it) else it } }
        Ui.dialogue(this).setTitle(Ui.coupable(getString(R.string.el_mode_titre, libelle)))
            .setItems(noms.toTypedArray()) { _, k ->
                val mode = modes[k]
                if (mode == "temps") dureeMode(o, genre, libelle)
                else agir(JSONObject().put("action", "accorder").put("genre", genre).put("nom", o.optString("nom")).put("mode", mode))
            }
            .setNegativeButton(R.string.commun_annuler, null).show()
    }

    private fun dureeMode(o: JSONObject, genre: String, libelle: String) {
        val minutes = listOf(15, 30, 60, 120)
        Ui.dialogue(this).setTitle(Ui.coupable(getString(R.string.parents_ouvrir_pour, libelle)))
            .setItems(minutes.map { Tr.duree(it) }.toTypedArray()) { _, k ->
                agir(JSONObject().put("action", "accorder").put("genre", genre).put("nom", o.optString("nom"))
                    .put("mode", "temps").put("minutes", minutes[k]))
            }
            .setNegativeButton(R.string.commun_annuler, null).show()
    }

    /** La recherche : où que soit rangé l'élément, avec son logiciel, ses catégories et pourquoi il est fermé. */
    private fun dessinerResultats(res: JSONArray) {
        if (res.length() == 0) { liste.addView(Ui.texte(this, getString(R.string.el_recherche_rien), 14f, Ui.TEXTE_2)); return }
        val c = plein(Ui.carte(this))
        for (i in 0 until res.length()) {
            val o = res.optJSONObject(i) ?: continue
            if (i > 0) c.addView(Ui.separateur(this))
            if (o.optString("genre") == "categorie") {
                val r = Ui.rangee(this)
                r.addView(Ui.poids(Ui.texte(this, getString(R.string.el_categorie) + " · " + Categories.libelle(this, o.optString("nom")), 15f, Ui.TEXTE, gras = true)))
                val f = o.optBoolean("fermee")
                r.addView(Ui.chip(this, getString(if (f) R.string.cat_etat_fermee else R.string.cat_etat_ouverte), if (f) Ui.ROUGE else Ui.VERT))
                r.setPadding(0, Ui.dp(this, 8f), 0, Ui.dp(this, 8f))
                c.addView(r)
                continue
            }
            val logiciel = o.optString("logiciel")
            c.addView(ligne(o, o.optString("genre"), logiciel = logiciel))
            val infos = ArrayList<String>()
            o.optString("service").takeIf { it.isNotEmpty() }?.let { infos.add(getString(R.string.el_service_de, o.optString("libelle").ifEmpty { o.optString("nom") }) + " : " + it) }
            if (logiciel.isNotEmpty()) infos.add(getString(R.string.el_logiciel_de, logiciel))
            val cats = o.optJSONArray("categories")?.let { a -> (0 until a.length()).map { Categories.libelle(this, a.optString(it)) } }.orEmpty()
            if (cats.isNotEmpty()) infos.add(getString(R.string.el_categories_de, cats.joinToString(", ")))
            if (infos.isNotEmpty()) c.addView(Ui.marge(this, Ui.texte(this, infos.joinToString("\n"), 12f, Ui.TEXTE_3), bas = 6f))
        }
        liste.addView(c)
    }

    /** Les derniers sites qui ont posé problème sur les appareils de la personne (ajout 6 de l'auteur). */
    private fun dessinerRecents() {
        val rec = recents ?: run { liste.addView(Ui.texte(this, getString(R.string.commun_chargement), 14f, Ui.TEXTE_2)); return }
        if (rec.length() == 0) { liste.addView(Ui.texte(this, getString(R.string.el_recents_vide), 14f, Ui.TEXTE_2)); return }
        val c = plein(Ui.carte(this))
        for (i in 0 until rec.length()) {
            val o = rec.optJSONObject(i) ?: continue
            if (i > 0) c.addView(Ui.separateur(this))
            c.addView(ligne(o, "sites", logiciel = o.optString("logiciel")))
            c.addView(Ui.marge(this, Ui.texte(this, detailRecent(this, o), 12f, Ui.TEXTE_3), bas = 6f))
        }
        liste.addView(c)
    }
}

/** « 18:42 · PC-LOU · Roblox · 3 tentatives » : l'heure, l'appareil, le logiciel s'il est connu, les tentatives. */
fun detailRecent(ctx: android.content.Context, o: JSONObject): String {
    val fois = o.optInt("fois", 1)
    return listOfNotNull(Tr.heure((o.optDouble("quand") * 1000).toLong()), o.optString("appareil_nom").ifEmpty { null },
        o.optString("logiciel").ifEmpty { null }, ctx.resources.getQuantityString(R.plurals.el_tentatives, fois, fois)).joinToString(" · ")
}
