// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import java.util.Locale

/**
 * Ce qui est fermé sur cet appareil, avec le motif, et d'où l'on demande
 * l'accès. Les blocages de sécurité (sites dangereux, traçage) n'y figurent
 * pas : on ne les montre pas, on ne les discute pas.
 */
class FermesActivity : Activity() {
    private lateinit var racine: LinearLayout
    private lateinit var liste: LinearLayout
    private var filtre = ""

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Ui.charger(this)
        if (android.os.Build.VERSION.SDK_INT >= 21) { window.statusBarColor = Ui.FOND; window.navigationBarColor = Ui.FOND }
        val scroll = ScrollView(this).apply { setBackgroundColor(Ui.FOND); isFillViewport = true }
        racine = Ui.colonne(this, Ui.dp(this, 20f))
        scroll.addView(racine)
        setContentView(scroll)

        racine.addView(Ui.marque(this))
        val r = Ui.rangee(this)
        val col = Ui.colonne(this)
        col.addView(Ui.titre(this, getString(R.string.fermes_titre)))
        col.addView(Ui.sousTitre(this, getString(R.string.fermes_sous_titre)))
        r.addView(Ui.marge(this, col, gauche = 2f))
        racine.addView(Ui.marge(this, r, bas = 14f))

        val champ = Ui.champ(this, getString(R.string.fermes_chercher), "")
        champ.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { filtre = s?.toString()?.trim()?.lowercase(Locale.ROOT) ?: ""; remplir() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        racine.addView(champ)
        racine.addView(Ui.marge(this, Ui.boutonSecondaire(this, getString(R.string.fermes_autre_site)) {
            Acces.dialogue(this, "sites", "", getString(R.string.fermes_un_site)) { remplir() }
        }, haut = 10f))
        liste = Ui.colonne(this)
        racine.addView(Ui.marge(this, liste, haut = 6f))
        remplir()
    }

    override fun onResume() { super.onResume(); remplir() }

    private fun remplir() {
        liste.removeAllViews()
        if (filtre.isEmpty()) recentsEtGroupes()
        // Applis : celles installées ici et fermées.
        val pm = packageManager
        val apps = Etat.apps.filter { Etat.raisonDe(it).isNotEmpty() && !securite(it) }
            .mapNotNull { pkg ->
                try { pm.getApplicationInfo(pkg, 0); pkg to Usage.libelle(this, pkg) } catch (_: PackageManager.NameNotFoundException) { null }
            }.filter { filtre.isEmpty() || it.second.lowercase(Locale.ROOT).contains(filtre) || it.first.contains(filtre) }
            .sortedBy { it.second.lowercase(Locale.ROOT) }
        if (apps.isNotEmpty()) {
            liste.addView(Ui.section(this, getString(R.string.fermes_applis)))
            val c = Ui.carte(this)
            apps.forEachIndexed { k, (pkg, lib) ->
                if (k > 0) c.addView(Ui.separateur(this))
                c.addView(ligne(lib, Etat.raisonDe(pkg), "apps", pkg, lib))
            }
            liste.addView(c)
        }
        // Sites : un domaine par site, les récents d'abord.
        val recents = Etat.recentsBloques()
        val sites = Etat.sites.filter { it.count { ch -> ch == '.' } == 1 && Etat.raisonDe(it).isNotEmpty() && !securite(it) }
            .filter { filtre.isEmpty() || it.contains(filtre) }
            .sortedWith(compareBy({ it !in recents }, { it }))
        if (sites.isNotEmpty()) {
            liste.addView(Ui.section(this, getString(R.string.fermes_sites)))
            val c = Ui.carte(this)
            sites.take(150).forEachIndexed { k, d ->
                if (k > 0) c.addView(Ui.separateur(this))
                c.addView(ligne(d, Etat.raisonDe(d), "sites", d, d, recent = d in recents))
            }
            liste.addView(c)
        }
        if (apps.isEmpty() && sites.isEmpty()) {
            liste.addView(Ui.carte(this).apply { addView(Ui.texte(this@FermesActivity, getString(R.string.fermes_rien), 14f, Ui.TEXTE_2)) })
        }
        liste.addView(Ui.espace(this, 24f))
    }

    /**
     * HA 2.5.1 (ajouts 5 et 6 de l'auteur) : ce qui a posé problème récemment (heure, appareil, logiciel, tentatives),
     * et une seule demande par logiciel pour ses sites bloqués.
     */
    private fun recentsEtGroupes() {
        val groupes = Acces.groupes
        val recents = Acces.recents
        if (groupes.length() == 0 && recents.length() == 0) return
        liste.addView(Ui.section(this, getString(R.string.el_recents_titre)))
        for (i in 0 until groupes.length()) {
            val g = groupes.optJSONObject(i) ?: continue
            val sites = g.optJSONArray("sites")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            if (sites.isEmpty()) continue
            val app = g.optString("logiciel"); val lib = g.optString("libelle").ifEmpty { app }
            val c = Ui.carte(this)
            c.addView(Ui.texte(this, lib, 15f, Ui.TEXTE, gras = true))
            c.addView(Ui.texte(this, resources.getQuantityString(R.plurals.el_groupe_sites, sites.size, sites.size) + " : " + sites.joinToString(", "), 12f, Ui.TEXTE_2))
            if (Acces.dejaDemande("apps", app)) c.addView(Ui.marge(this, Ui.chip(this, getString(R.string.fermes_demande), Ui.OR), haut = 8f))
            else c.addView(Ui.marge(this, Ui.boutonPrimaire(this, getString(R.string.el_demander_pour, lib)) {
                Acces.demanderGroupe(this, app, lib, sites) { remplir() }
            }.apply { tag = "el_groupe:$app" }, haut = 8f))
            liste.addView(Ui.marge(this, c, bas = 8f))
        }
        if (recents.length() > 0) {
            val c = Ui.carte(this)
            for (i in 0 until minOf(recents.length(), 8)) {
                val o = recents.optJSONObject(i) ?: continue
                if (i > 0) c.addView(Ui.separateur(this))
                val nom = o.optString("nom")
                c.addView(ligne(nom, listOf(o.optString("raison"), detailRecent(this, o)).filter { it.isNotEmpty() }.joinToString(" · "),
                    "sites", nom, nom, recent = true))
            }
            liste.addView(Ui.marge(this, c, bas = 8f))
        }
    }

    private fun securite(nom: String): Boolean {
        var d = nom.lowercase(Locale.ROOT)
        while (true) {
            Etat.raisons[d]?.let { return it.substringBefore('|') == "securite" }
            if (!d.contains('.')) return false
            d = d.substringAfter('.')
        }
    }

    private fun ligne(titre: String, raison: String, genre: String, nom: String, libelle: String, recent: Boolean = false): View {
        val r = Ui.rangee(this)
        val col = Ui.colonne(this)
        col.addView(Ui.texte(this, titre, 15f, if (recent) Ui.OR else Ui.TEXTE, gras = true))
        col.addView(Ui.texte(this, raison, 12f, Ui.TEXTE_2))
        r.addView(Ui.poids(col))
        if (Acces.dejaDemande(genre, nom)) r.addView(Ui.chip(this, getString(R.string.fermes_demande), Ui.OR))
        else r.addView(Ui.boutonSecondaire(this, getString(R.string.fermes_demander)) { Acces.dialogue(this, genre, nom, libelle) { remplir() } })
        r.setPadding(0, Ui.dp(this, 6f), 0, Ui.dp(this, 6f))
        return r
    }
}
