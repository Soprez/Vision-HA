// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pour un parent : les catégories (étiquettes) d'un enfant, chacune avec un
 * interrupteur « coupé », et ce qu'elle contient. Les catégories de la maison
 * (sécurité, interdits) sont verrouillées ici : elles se lèvent dans Vision.
 */
class CategoriesActivity : Activity() {
    private lateinit var racine: LinearLayout
    private lateinit var liste: LinearLayout
    private lateinit var a: JSONObject
    private var categories = JSONArray()
    private val etats = HashMap<String, JSONObject>()

    companion object {
        @Volatile var fiche: JSONObject? = null
        @Volatile var cats: JSONArray? = null
        /** HA 2.5.1 : chaque catégorie s'ouvre élément par élément (CategorieActivity), et les blocages récents. */
        @Volatile var elements = false
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Ui.charger(this)
        a = fiche ?: run { finish(); return }
        categories = cats ?: JSONArray()
        val e = a.optJSONObject("etiquettes_etat") ?: JSONObject()
        for (k in e.keys()) etats[k] = e.optJSONObject(k) ?: JSONObject().put("choix", e.optString(k)).put("coupe", e.optString(k) == "bloquer")
        if (android.os.Build.VERSION.SDK_INT >= 21) { window.statusBarColor = Ui.FOND; window.navigationBarColor = Ui.FOND }
        val scroll = ScrollView(this).apply { setBackgroundColor(Ui.FOND); isFillViewport = true }
        racine = Ui.colonne(this, Ui.dp(this, 20f))
        Ui.largeurLecture(racine)
        scroll.addView(racine); setContentView(scroll)

        racine.addView(Ui.marque(this))
        val r = Ui.rangee(this)
        val col = Ui.colonne(this)
        val prenom = a.optString("prenom").ifEmpty { a.optString("nom") }
        col.addView(Ui.titre(this, getString(R.string.cat_titre)))
        col.addView(Ui.sousTitre(this, getString(R.string.cat_sous_titre, prenom)))
        r.addView(Ui.marge(this, col, gauche = 2f))
        racine.addView(Ui.marge(this, r, bas = 14f))
        racine.addView(Ui.texte(this, getString(R.string.cat_aide), 13f, Ui.TEXTE_2))
        if (elements) racine.addView(Ui.marge(this, Ui.boutonSecondaire(this, getString(R.string.el_recents_titre)) { ouvrir("") }, haut = 10f))
        liste = Ui.colonne(this)
        racine.addView(Ui.marge(this, liste, haut = 10f))
        remplir()
    }

    private fun remplir() {
        liste.removeAllViews()
        val libres = ArrayList<JSONObject>(); val maison = ArrayList<JSONObject>()
        for (i in 0 until categories.length()) {
            val c = categories.optJSONObject(i) ?: continue
            if (c.optBoolean("maison") || c.optBoolean("securite")) maison.add(c) else libres.add(c)
        }
        // Les âges d'abord, puis le reste.
        val ages = libres.filter { it.optBoolean("age") }
        val autres = libres.filter { !it.optBoolean("age") }
        if (ages.isNotEmpty()) { liste.addView(Ui.section(this, getString(R.string.cat_section_age))); liste.addView(carte(ages)) }
        if (autres.isNotEmpty()) { liste.addView(Ui.section(this, getString(R.string.cat_titre))); liste.addView(carte(autres)) }
        if (maison.isNotEmpty()) {
            liste.addView(Ui.section(this, getString(R.string.cat_section_tous)))
            liste.addView(carte(maison, verrou = true))
        }
        liste.addView(Ui.espace(this, 24f))
    }

    private fun carte(cs: List<JSONObject>, verrou: Boolean = false): View {
        val c = Ui.carte(this)
        cs.forEachIndexed { k, cat ->
            if (k > 0) c.addView(Ui.separateur(this))
            c.addView(ligne(cat, verrou))
        }
        return c
    }

    private fun ligne(cat: JSONObject, verrou: Boolean): View {
        val nom = cat.optString("nom")
        val nA = cat.optJSONArray("apps")?.length() ?: 0
        val nS = cat.optJSONArray("sites")?.length() ?: 0
        val e = etats[nom] ?: JSONObject()
        val choix = e.optString("choix", "neutre")
        val coupe = e.optBoolean("coupe", choix == "bloquer")
        val bloqueParRegle = e.optBoolean("verrou") && !verrou   // moyenne ou planning : coché, mais pas à toi de le décocher
        val r = Ui.rangee(this)
        val col = Ui.colonne(this)
        col.addView(Ui.texte(this, Categories.libelle(this, nom), 15f, if (verrou) Ui.TEXTE_2 else Ui.TEXTE, gras = true))
        val detail = buildString {
            append(if (nA == 0 && nS == 0) getString(R.string.cat_vide) else listOfNotNull(
                if (nA > 0) resources.getQuantityString(R.plurals.cat_n_applis, nA, nA) else null,
                if (nS > 0) resources.getQuantityString(R.plurals.cat_n_sites, nS, nS) else null).joinToString(", "))
            if (choix == "autoriser") append(" · ").append(getString(R.string.cat_autorise_explicitement))
        }
        col.addView(Ui.texte(this, detail, 12f, Ui.TEXTE_2))
        val raison = e.optString("raison")
        if (raison.isNotEmpty() && (bloqueParRegle || verrou)) col.addView(Ui.texte(this, raison, 12f, if (bloqueParRegle) Ui.OR else Ui.TEXTE_3))
        col.setOnClickListener { if (elements) ouvrir(nom) else contenu(cat) }
        r.addView(Ui.poids(col))
        // Refonte lot 3 (mot coupé à 200 % : « divertissement ») : écran étroit (grands caractères), l'état et
        // l'interrupteur passent sous le nom, qui garde toute la largeur ; sinon, à droite comme avant.
        val etroit = Ui.etroit(this)
        val cible: android.view.ViewGroup = if (etroit) Ui.rangee(this).also { col.addView(Ui.marge(this, it, haut = 6f)) } else r
        if (verrou) {
            cible.addView(Ui.chip(this, getString(R.string.cat_tous_les_appareils), Ui.TEXTE_3))
        } else {
            // Coché = fermé : l'interrupteur prend le rouge du thème ; désactivé (règle) : pâli par le thème lui-même.
            // APP-27 : l'état se lit en toutes lettres à côté (« Ouverte » / « Fermée »), et TalkBack le dit.
            val libelle = Categories.libelle(this, nom)
            fun etat(f: Boolean) = getString(if (f) R.string.cat_etat_fermee else R.string.cat_etat_ouverte)
            val texteEtat = Ui.texte(this, etat(coupe), 14f, if (coupe) Ui.ROUGE else Ui.VERT, gras = true).apply {
                tag = "cat_etat"; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val sw = Ui.interrupteur(this, coupe, accent = Ui.ROUGE, description = getString(R.string.cat_etat_description, libelle, etat(coupe)))
            if (bloqueParRegle) sw.isEnabled = false
            sw.setOnCheckedChangeListener { _, coche ->
                texteEtat.text = etat(coche); texteEtat.setTextColor(if (coche) Ui.ROUGE else Ui.VERT)
                sw.contentDescription = getString(R.string.cat_etat_description, libelle, etat(coche))
                basculer(nom, if (coche) "bloquer" else "neutre")
            }
            cible.addView(Ui.marge(this, texteEtat, droite = 8f))
            cible.addView(sw)
        }
        r.setPadding(0, Ui.dp(this, 8f), 0, Ui.dp(this, 8f))
        return r
    }

    /** HA 2.5.1 : la catégorie élément par élément ; vide : les blocages récents de la personne. */
    private fun ouvrir(etiquette: String) {
        CategorieActivity.pc = a.optString("id")
        CategorieActivity.etiquette = etiquette
        CategorieActivity.prenom = a.optString("prenom").ifEmpty { a.optString("nom") }
        startActivity(android.content.Intent(this, CategorieActivity::class.java))
    }

    private fun contenu(cat: JSONObject) {
        val apps = cat.optJSONArray("apps") ?: JSONArray()
        val sites = cat.optJSONArray("sites") ?: JSONArray()
        val lignes = ArrayList<String>()
        for (i in 0 until apps.length()) lignes.add("• " + (apps.optJSONObject(i)?.optString("libelle") ?: ""))
        for (i in 0 until sites.length()) lignes.add("◦ " + sites.optString(i))
        val vue = ScrollView(this)
        val col = Ui.colonne(this, Ui.dp(this, 20f))
        col.addView(Ui.texte(this, if (lignes.isEmpty()) getString(R.string.cat_rien_de_classe) else lignes.joinToString("\n"), 14f, Ui.TEXTE))
        vue.addView(col)
        Ui.dialogue(this).setTitle(Categories.libelle(this, cat.optString("nom"))).setView(vue).setPositiveButton(R.string.commun_fermer, null).show()
    }

    private fun basculer(nom: String, etat: String) = Garde.exigerPour(this, ParentsActivity.RAISON, "/api/pc_parental/parent",
        JSONObject().put("action", "etiquette").put("etat", etat), annule = { remplir() }) { basculerVraiment(nom, etat) }

    // 1.77.0 : le choix s'affiche tout de suite ; un refus remet le choix d'avant (et le dit).
    private fun basculerVraiment(nom: String, etat: String) {
        val avant = etats[nom]?.let { JSONObject(it.toString()) }
        etats[nom] = (etats[nom]?.let { JSONObject(it.toString()) } ?: JSONObject()).put("choix", etat).put("coupe", etat == "bloquer")
        remplir()
        val cfg = Config(this)
        Thread {
            var ok = false
            val texte = try {
                val r = Net.post(this, cfg, "/api/pc_parental/parent", JSONObject()
                    .put("id", cfg.id).put("secret", cfg.secret).put("action", "etiquette")
                    .put("pc", a.optString("id")).put("etiquette", nom).put("etat", etat)).optString("retour").ifEmpty { getString(R.string.cat_c_est_fait) }
                ok = true
                r
            } catch (e: Net.Echec) { Geste.messageEchec(e.code) } catch (e: Exception) { ReseauLocal.message(this@CategoriesActivity, e) }
            runOnUiThread {
                if (!ok) { if (avant != null) etats[nom] = avant else etats.remove(nom); remplir() }
                Ui.toast(this, texte, court = true, genre = if (ok) OeilModele.Genre.SUCCES else OeilModele.Genre.ERREUR)
            }
        }.start()
    }
}
