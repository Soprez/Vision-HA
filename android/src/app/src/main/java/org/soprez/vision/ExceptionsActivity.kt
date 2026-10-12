// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pour un parent : les exceptions d'une personne, c'est-à-dire ce qui est décidé
 * nom par nom en plus des catégories. « Snapchat : toujours autorisé pour Alex. »
 * On les voit, on les inverse, on les retire, on en ajoute. Elles valent sur tous
 * les appareils de la personne ; le planning et les interdits de toute la maison
 * restent plus forts.
 */
class ExceptionsActivity : Activity() {
    private class Element(val genre: String, val nom: String, val libelle: String, val maison: Boolean = false)

    private lateinit var liste: LinearLayout
    private lateinit var resultats: LinearLayout
    private lateinit var a: JSONObject
    private val autorises = ArrayList<Element>()
    private val bloques = ArrayList<Element>()
    private val disponibles = ArrayList<Element>()
    private val connus = ArrayList<Element>()
    private var recherche = ""

    companion object {
        @Volatile var fiche: JSONObject? = null
        @Volatile var cats: JSONArray? = null
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Ui.charger(this)
        a = fiche ?: run { finish(); return }
        val ex = a.optJSONObject("exceptions")
        lire(ex?.optJSONArray("autorises"), autorises)
        lire(ex?.optJSONArray("bloques"), bloques)
        lire(ex?.optJSONArray("toujours"), disponibles)
        // Tout ce que Vision connaît déjà : les applis et les sites classés dans une catégorie.
        val vus = HashSet<String>()
        val categories = cats ?: JSONArray()
        for (i in 0 until categories.length()) {
            val c = categories.optJSONObject(i) ?: continue
            val apps = c.optJSONArray("apps") ?: JSONArray()
            for (k in 0 until apps.length()) { val o = apps.optJSONObject(k) ?: continue; val n = o.optString("nom"); if (n.isNotEmpty() && vus.add("a:" + n.lowercase())) connus.add(Element("apps", n, o.optString("libelle").ifEmpty { n })) }
            val sites = c.optJSONArray("sites") ?: JSONArray()
            for (k in 0 until sites.length()) { val n = sites.optString(k); if (n.isNotEmpty() && vus.add("s:" + n.lowercase())) connus.add(Element("sites", n, n)) }
        }
        connus.sortBy { it.libelle.lowercase() }

        if (android.os.Build.VERSION.SDK_INT >= 21) { window.statusBarColor = Ui.FOND; window.navigationBarColor = Ui.FOND }
        val scroll = ScrollView(this).apply { setBackgroundColor(Ui.FOND); isFillViewport = true }
        val racine = Ui.colonne(this, Ui.dp(this, 20f))
        scroll.addView(racine); setContentView(scroll)
        racine.addView(Ui.marque(this))
        val prenom = a.optString("prenom").ifEmpty { a.optString("nom") }
        val col = Ui.colonne(this)
        col.addView(Ui.titre(this, getString(R.string.exceptions_titre)))
        col.addView(Ui.sousTitre(this, getString(R.string.exceptions_sous_titre, prenom)))
        racine.addView(Ui.marge(this, col, bas = 14f))
        racine.addView(Ui.texte(this, getString(R.string.exceptions_explication), 13f, Ui.TEXTE_2))
        if (ex == null) racine.addView(Ui.marge(this, Ui.texte(this, getString(R.string.exceptions_prochaine_maj), 13f, Ui.OR), haut = 10f))
        liste = Ui.colonne(this)
        racine.addView(Ui.marge(this, liste, haut = 6f))

        racine.addView(Ui.section(this, getString(R.string.exceptions_ajouter)))
        val carte = Ui.carte(this)
        val champ = Ui.champ(this, getString(R.string.exceptions_champ), "")
        champ.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { recherche = s?.toString()?.trim()?.lowercase() ?: ""; chercher() }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, bf: Int, c: Int) {}
        })
        carte.addView(champ)
        resultats = Ui.colonne(this)
        carte.addView(Ui.marge(this, resultats, haut = 6f))
        racine.addView(carte)
        racine.addView(Ui.espace(this, 24f))
        remplir(); chercher()
    }

    private fun lire(source: JSONArray?, vers: ArrayList<Element>) {
        if (source == null) return
        for (i in 0 until source.length()) {
            val o = source.optJSONObject(i) ?: continue
            vers.add(Element(o.optString("genre", "apps"), o.optString("nom"), o.optString("libelle").ifEmpty { o.optString("nom") }, o.optBoolean("maison")))
        }
    }

    /** Les noms déjà « toujours disponibles » : ils ne s'affichent pas une seconde fois dans « Suit l'agenda ». */
    private fun dispo(e: Element) = disponibles.any { it.nom.equals(e.nom, true) }

    private fun remplir() {
        liste.removeAllViews()
        // 1. Toujours disponible : jamais bloquée, même hors plages, en pause ou appareil fermé.
        liste.addView(Ui.section(this, getString(R.string.exceptions_toujours_disponible)))
        liste.addView(Ui.texte(this, getString(R.string.exceptions_toujours_disponible_texte), 13f, Ui.TEXTE_2))
        val cd = Ui.carte(this)
        // Le téléphone et les appels d'urgence : toujours, et on ne peut pas les retirer.
        cd.addView(ligne(Element("apps", "", getString(R.string.exceptions_telephone_urgence)), getString(R.string.exceptions_ne_peut_pas_retirer)) {
            Toast.makeText(this, getString(R.string.exceptions_telephone_toast), Toast.LENGTH_LONG).show()
        })
        disponibles.sortedBy { it.libelle.lowercase() }.forEach { e ->
            cd.addView(Ui.separateur(this))
            cd.addView(ligne(e, if (e.maison) getString(R.string.exceptions_pour_tout_le_monde) else "") {
                if (e.maison) Toast.makeText(this, getString(R.string.exceptions_pour_tout_le_monde_toast), Toast.LENGTH_LONG).show()
                else Ui.dialogue(this).setTitle(e.libelle)
                    .setItems(arrayOf(getString(R.string.exceptions_suit_agenda_plutot), getString(R.string.exceptions_retirer_autorisation))) { _, k ->
                        if (k == 0) decider(e, "indisponible") else { decider(e, "indisponible"); decider(e, "oublier") }
                    }.setNegativeButton(getString(R.string.commun_lot2_annuler), null).show()
            })
        }
        liste.addView(Ui.marge(this, cd, haut = 6f))
        // 2. Suit l'agenda : autorisée malgré sa catégorie, pendant les plages ouvertes.
        val suit = autorises.filter { !dispo(it) }
        liste.addView(Ui.section(this, getString(R.string.exceptions_suit_agenda_n, suit.size)))
        liste.addView(Ui.texte(this, getString(R.string.exceptions_suit_agenda_texte), 13f, Ui.TEXTE_2))
        val ca = Ui.carte(this)
        if (suit.isEmpty()) ca.addView(Ui.texte(this, getString(R.string.exceptions_aucune), 14f, Ui.TEXTE_3))
        suit.sortedBy { it.libelle.lowercase() }.forEachIndexed { k, e ->
            if (k > 0) ca.addView(Ui.separateur(this))
            ca.addView(ligne(e, if (e.maison) getString(R.string.exceptions_interdit_tous) else "") { choisir(e, true) })
        }
        liste.addView(Ui.marge(this, ca, haut = 6f))
        // 3. Toujours fermée.
        liste.addView(Ui.section(this, getString(R.string.exceptions_toujours_fermee_n, bloques.size)))
        val cb = Ui.carte(this)
        if (bloques.isEmpty()) cb.addView(Ui.texte(this, getString(R.string.exceptions_rien_a_part), 14f, Ui.TEXTE_3))
        bloques.sortedBy { it.libelle.lowercase() }.forEachIndexed { k, e ->
            if (k > 0) cb.addView(Ui.separateur(this))
            cb.addView(ligne(e, if (e.maison) getString(R.string.exceptions_interdit_tous) else "") { choisir(e, false) })
        }
        liste.addView(cb)
    }

    private data class Quatre(val titre: String, val vide: String, val elements: ArrayList<Element>, val autorise: Boolean)

    private fun ligne(e: Element, note: String, action: () -> Unit): View {
        val col = Ui.colonne(this)
        col.addView(Ui.texte(this, e.libelle, 15f, Ui.TEXTE, gras = true))
        val sous = listOf(if (e.genre == "apps") getString(R.string.detailfermes_appli) else getString(R.string.detailfermes_site), if (e.libelle != e.nom) e.nom else "", note).filter { it.isNotEmpty() }.joinToString(" · ")
        col.addView(Ui.texte(this, sous, 12f, Ui.TEXTE_2))
        col.setPadding(0, Ui.dp(this, 9f), 0, Ui.dp(this, 9f))
        col.isClickable = true; col.isFocusable = true
        col.setOnClickListener { action() }
        return col
    }

    /** Une autorisation (ou une fermeture) existante : changer de type, ou la retirer. */
    private fun choisir(e: Element, autorise: Boolean) {
        val choix = ArrayList<Pair<String, String>>()
        if (autorise && e.genre == "apps") choix.add(getString(R.string.exceptions_choix_disponible) to "disponible")
        choix.add((if (autorise) getString(R.string.exceptions_choix_fermee_plutot) else getString(R.string.exceptions_choix_suit_agenda)) to (if (autorise) "bloquer" else "toujours"))
        choix.add(getString(R.string.exceptions_choix_retirer) to "oublier")
        Ui.dialogue(this).setTitle(e.libelle)
            .setItems(choix.map { it.first }.toTypedArray()) { _, k -> decider(e, choix[k].second) }
            .setNegativeButton(getString(R.string.commun_lot2_annuler), null).show()
    }

    private fun chercher() {
        resultats.removeAllViews()
        if (recherche.length < 2) { resultats.addView(Ui.texte(this, getString(R.string.exceptions_deux_lettres), 13f, Ui.TEXTE_3)); return }
        val trouves = ArrayList(connus.filter { it.libelle.lowercase().contains(recherche) || it.nom.lowercase().contains(recherche) }.take(10))
        // Une adresse écrite en entier et que Vision ne connaît pas encore : on la propose telle quelle.
        if (Regex("^[a-z0-9-]+(\\.[a-z0-9-]+)+$").matches(recherche) && trouves.none { it.nom.lowercase() == recherche }) trouves.add(0, Element("sites", recherche, recherche))
        if (trouves.isEmpty()) { resultats.addView(Ui.texte(this, getString(R.string.exceptions_rien_de_ce_nom), 13f, Ui.TEXTE_3)); return }
        trouves.forEachIndexed { k, e ->
            if (k > 0) resultats.addView(Ui.separateur(this))
            resultats.addView(ligne(e, "") {
                val choix = ArrayList(listOf(getString(R.string.exceptions_choix_suit_agenda) to "toujours"))
                if (e.genre == "apps") choix.add(getString(R.string.exceptions_choix_disponible) to "disponible")
                choix.add(getString(R.string.exceptions_choix_toujours_fermee) to "bloquer")
                Ui.dialogue(this).setTitle(e.libelle)
                    .setItems(choix.map { it.first }.toTypedArray()) { _, i -> decider(e, choix[i].second) }
                    .setNegativeButton(getString(R.string.commun_lot2_annuler), null).show()
            })
        }
    }

    /** Envoie la décision, et met la liste à jour dès que Home Assistant l'a acceptée. */
    private fun decider(e: Element, decision: String) = Garde.exiger(this, ParentsActivity.RAISON) { deciderVraiment(e, decision) }

    private fun deciderVraiment(e: Element, decision: String) {
        Geste.enCours()
        val cfg = Config(this)
        Thread {
            var ok = false
            var x404 = false
            val texte = try {
                val r = Net.post(this, cfg, "/api/pc_parental/parent", JSONObject()
                    .put("id", cfg.id).put("secret", cfg.secret).put("action", "autoriser")
                    .put("pc", a.optString("id")).put("genre", e.genre).put("nom", e.nom).put("decision", decision))
                ok = true
                r.optString("retour").ifEmpty { getString(R.string.commun_lot2_c_est_fait) }
            } catch (x: Net.Echec) {
                when (x.code) { 404 -> { x404 = true; getString(R.string.exceptions_plus_dans_liste) }; else -> Geste.messageEchec(x.code) }
            } catch (_: Exception) { getString(R.string.commun_lot2_injoignable) }
            runOnUiThread {
                Toast.makeText(this, texte, Toast.LENGTH_SHORT).show()
                if (!ok && !x404) Geste.relacher()
                if (ok || x404) {
                    when (decision) {
                        "disponible" -> {
                            if (disponibles.none { it.nom.equals(e.nom, true) }) disponibles.add(e)
                            bloques.removeAll { it.nom.equals(e.nom, true) }
                            if (autorises.none { it.nom.equals(e.nom, true) }) autorises.add(e)
                        }
                        "indisponible" -> disponibles.removeAll { it.nom.equals(e.nom, true) }
                        else -> {
                            autorises.removeAll { it.nom.equals(e.nom, true) }; bloques.removeAll { it.nom.equals(e.nom, true) }
                            if (decision == "toujours") autorises.add(e) else if (decision == "bloquer") bloques.add(e)
                        }
                    }
                    remplir()
                }
            }
        }.start()
    }
}
