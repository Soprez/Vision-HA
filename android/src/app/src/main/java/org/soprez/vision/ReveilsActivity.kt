// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormatSymbols
import java.util.Calendar

/**
 * Réveil (lot 5) : « Mes réveils ». Porte de l'appli POST /api/pc_parental/reveils (contrat §6), mêmes droits que les
 * services HA : chacun voit ses réveils, les reporte et les arrête ; avec « reveil_propres » il crée et modifie les
 * siens ; avec « reveil_gerer » (Décideur) il gère ceux de tout le monde. Un réveil créé par une automatisation le dit.
 *
 * Deux vues dans la même activité (télécommande comprise) : la liste, puis l'édition (nom, pour qui, heure, jours,
 * son, appareils, actif). Un réveil dont l'heure vient d'une automatisation (entité, modèle, date) garde son heure :
 * l'édition n'en montre que le nom, le son et les appareils.
 */
class ReveilsActivity : Activity() {
    companion object {
        /** Réponse d'essai (recette visuelle) : remplace l'appel réseau. */
        @Volatile var essai: JSONObject? = null
        /** Recette visuelle : montre les deux avertissements comme si les réglages étaient refusés. */
        @Volatile var forcerAlertes = false

        fun ouvrir(ctx: Context) {
            try { ctx.startActivity(Intent(ctx, ReveilsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        }
    }

    private lateinit var racine: LinearLayout
    private var donnees: JSONObject? = null
    private var erreur: String? = null
    private var edition: JSONObject? = null  // null = la liste ; {} = nouveau réveil
    private var enCours = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Ui.charger(this)
        if (android.os.Build.VERSION.SDK_INT >= 21) { window.statusBarColor = Ui.FOND; window.navigationBarColor = Ui.FOND }
        val scroll = ScrollView(this).apply { setBackgroundColor(Ui.FOND); isFillViewport = true }
        racine = Ui.colonne(this, Ui.dp(this, 20f))
        scroll.addView(racine)
        setContentView(scroll)
        dessiner()
        charger()
    }

    @Deprecated("Retour : de l'édition à la liste")
    override fun onBackPressed() {
        if (edition != null) { edition = null; dessiner() } else @Suppress("DEPRECATION") super.onBackPressed()
    }

    // ------------------------------------------------------------------ Échanges avec HA

    private fun appeler(action: String, plus: JSONObject = JSONObject(), apres: (JSONObject?) -> Unit) {
        essai?.let { apres(it); return }
        val cfg = Config(this)
        enCours = true
        Thread {
            var rep: JSONObject? = null
            var err: String? = null
            try {
                val corps = JSONObject().put("id", cfg.id).put("secret", cfg.secret).put("action", action)
                plus.keys().forEach { corps.put(it, plus.get(it)) }
                rep = Net.post(this, cfg, "/api/pc_parental/reveils", corps)
            } catch (e: Net.Echec) {
                err = when (e.corps?.optString("error")) {
                    "module" -> getString(R.string.reveils_module_coupe)
                    "droits" -> getString(R.string.reveils_refuse)
                    "invalide" -> e.corps?.optString("retour")?.ifEmpty { null } ?: getString(R.string.reveils_invalide)
                    else -> getString(R.string.reveils_injoignable)
                }
            } catch (_: Exception) { err = getString(R.string.reveils_injoignable) }
            runOnUiThread {
                enCours = false
                if (isFinishing) return@runOnUiThread
                if (err != null) { Ui.toast(this, err!!); if (action == "liste") { erreur = err; dessiner() } }
                apres(rep)
            }
        }.start()
    }

    private fun charger() = appeler("liste") { r -> if (r != null) { donnees = r; erreur = null; dessiner() } }

    private fun agir(action: String, reveil: JSONObject, plus: JSONObject = JSONObject()) {
        if (enCours) return
        appeler(action, plus.put("reveil_id", reveil.optString("id"))) { r -> if (r != null) charger() }
    }

    // ------------------------------------------------------------------ Dessin

    private fun dessiner() {
        racine.removeAllViews()
        racine.addView(Ui.marque(this))
        val e = edition
        if (e != null) { dessinerEdition(e); return }
        val col = Ui.colonne(this)
        col.addView(Ui.titre(this, getString(R.string.reveils_titre)))
        col.addView(Ui.sousTitre(this, getString(R.string.reveils_sous_titre)))
        racine.addView(Ui.marge(this, col, bas = 14f))
        alertes()
        val d = donnees
        if (d == null) {
            val armee = Reveils.prochaineArmee(this)
            racine.addView(Ui.texte(this, erreur ?: getString(R.string.reveils_chargement), 14f, Ui.TEXTE_2))
            if (armee > 0) racine.addView(Ui.marge(this, Ui.texte(this, getString(R.string.reveils_prochain_local, quand(armee)), 14f, Ui.TEXTE), haut = 8f))
            return
        }
        val liste = d.optJSONArray("reveils") ?: JSONArray()
        val droits = d.optJSONObject("droits")
        val noms = d.optJSONObject("noms") ?: JSONObject()
        val moi = d.optString("moi")
        if (liste.length() == 0) racine.addView(Ui.texte(this, getString(R.string.reveils_aucun), 14f, Ui.TEXTE_2))
        for (i in 0 until liste.length()) {
            val r = liste.optJSONObject(i) ?: continue
            racine.addView(Ui.marge(this, carte(r, noms, moi), bas = 10f))
        }
        if (droits?.optBoolean("gerer") == true || droits?.optBoolean("propres") == true) {
            racine.addView(Ui.marge(this, Ui.boutonPrimaire(this, getString(R.string.reveils_nouveau)) {
                edition = JSONObject(); dessiner()
            }, haut = 6f))
        }
        racine.addView(Ui.espace(this, 24f))
    }

    /** Ce qui empêcherait un réveil de sonner à l'heure ou de s'afficher sur cet appareil, avec le réglage à ouvrir. */
    private fun alertes() {
        val l = ArrayList<Pair<String, Intent>>()
        if ((forcerAlertes || !Reveils.alarmeExacte(this)) && android.os.Build.VERSION.SDK_INT >= 31)
            l.add(getString(R.string.reveils_alerte_alarme) to Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:$packageName")))
        if (forcerAlertes || !Reveils.pleinEcran(this)) l.add(getString(R.string.reveils_alerte_ecran) to (
            if (android.os.Build.VERSION.SDK_INT >= 34 && notificationsPermises())
                Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, android.net.Uri.parse("package:$packageName"))
            else Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)))
        for ((texte, reglage) in l) {
            val c = Ui.carte(this)
            c.addView(Ui.texte(this, texte, 14f, Ui.ROUGE, gras = true))
            c.addView(Ui.marge(this, Ui.boutonSecondaire(this, getString(R.string.reveils_ouvrir_reglages)) {
                try { startActivity(reglage.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {
                    try { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))) } catch (_: Exception) {}
                }
            }, haut = 8f))
            racine.addView(Ui.marge(this, c, bas = 10f))
        }
    }

    /** Les notifications de Vision sont permises (sinon, c'est elles qu'il faut rouvrir d'abord). */
    private fun notificationsPermises(): Boolean = android.os.Build.VERSION.SDK_INT < 24 ||
        (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager).areNotificationsEnabled()

    override fun onResume() {
        super.onResume()
        if (::racine.isInitialized && edition == null) dessiner()  // retour des réglages : l'alerte disparaît si c'est réglé
    }

    private fun quand(ms: Long): String = Tr.date(ms, "EEEdMMM") + " · " + Tr.heure(ms)

    /** « mar. 13 oct. » pour une date « 2026-10-13 » (midi local : jamais la veille). */
    private fun jour(date: String): String = if (date.length == 10)
        Tr.date(ReveilsModele.instant(date, "12:00", java.util.TimeZone.getDefault()), "EEEdMMM") else date

    private fun peut(r: JSONObject, action: String) = r.optJSONObject("peut")?.optBoolean(action) == true

    private fun carte(r: JSONObject, noms: JSONObject, moi: String): View {
        val c = Ui.carte(this)
        val entete = Ui.rangee(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val titres = Ui.colonne(this)
        titres.addView(Ui.texte(this, r.optString("nom").ifEmpty { getString(R.string.reveil_nom_defaut) }, 17f, Ui.TEXTE, gras = true))
        val pour = r.optString("demandeur")
        if (pour.isNotEmpty() && pour != moi) titres.addView(Ui.texte(this, getString(R.string.reveils_pour, noms.optString(pour, pour.substringAfter('.'))), 13f, Ui.TEXTE_2))
        entete.addView(Ui.poids(titres))
        val actif = r.optBoolean("actif")
        val modifiable = peut(r, if (actif) "desactiver" else "activer")
        entete.addView(Ui.interrupteur(this, actif, description = getString(R.string.reveils_actif)) { v ->
            if (v != actif) agir(if (v) "activer" else "desactiver", r)
        }.apply { isEnabled = modifiable; contentDescription = getString(R.string.reveils_actif) })
        c.addView(entete)
        val prochain = ReveilsModele.lireIso(r.optString("prochain"))
        c.addView(Ui.marge(this, Ui.texte(this,
            if (prochain > 0) getString(R.string.reveils_prochain, quand(prochain)) else getString(R.string.reveils_rien_prevu), 15f, Ui.OR, gras = true), haut = 8f))
        val sautees = r.optJSONArray("sautees")
        if (sautees != null && sautees.length() > 0) c.addView(Ui.texte(this, getString(R.string.reveils_saute, jour(sautees.optString(0))), 13f, Ui.TEXTE_2))
        val details = ArrayList<String>()
        details.add(resume(r))
        if (r.optBoolean("automatisation")) details.add(getString(R.string.reveils_par_automatisation))
        if (r.optBoolean("impose")) details.add(getString(R.string.reveils_impose))
        c.addView(Ui.marge(this, Ui.texte(this, details.joinToString(" · "), 13f, Ui.TEXTE_2), haut = 4f))
        val boutons = Ui.rangeeBoutons(this)
        if (peut(r, "sauter") && actif) {
            if (sautees != null && sautees.length() > 0)
                boutons.addView(Ui.poids(Ui.boutonSecondaire(this, getString(R.string.reveils_reprendre)) { agir("reprendre", r, JSONObject().put("date", sautees.optString(0))) }))
            else if (prochain > 0)
                boutons.addView(Ui.poids(Ui.boutonSecondaire(this, getString(R.string.reveils_passer)) { agir("sauter", r) }))
        }
        if (peut(r, "modifier")) boutons.addView(Ui.marge(this, Ui.poids(Ui.boutonSecondaire(this, getString(R.string.reveils_modifier)) {
            edition = r; dessiner()
        }), gauche = if (boutons.childCount > 0) 8f else 0f))
        if (boutons.childCount > 0) c.addView(Ui.marge(this, boutons, haut = 12f))
        return c
    }

    /** « Lun, mar, mer · 07:00 · Radio » : le déclenchement et le son en une ligne. */
    private fun resume(r: JSONObject): String {
        val d = r.optJSONObject("declenchement")
        val quand = when (d?.optString("type")) {
            "hebdo", null, "" -> joursCourts(ReveilsModele.jours(d)) + " · " + (d?.optString("heure")?.ifEmpty { null } ?: "07:00")
            "ponctuel" -> getString(R.string.reveils_une_fois)
            "manuel" -> getString(R.string.reveils_sur_ordre)
            else -> getString(R.string.reveils_heure_calculee)
        }
        val son = if (r.optJSONObject("source")?.optString("type", "sonnerie") == "sonnerie") getString(R.string.reveils_son_sonnerie) else getString(R.string.reveils_son_flux)
        return "$quand · $son"
    }

    /** Les noms courts des jours, dans la langue de l'appli, lundi en premier (0 = lundi). */
    private fun nomsJours(): List<String> {
        val s = DateFormatSymbols.getInstance(Tr.locale()).shortWeekdays
        return listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY)
            .map { s[it].trimEnd('.').replaceFirstChar { c -> c.titlecase(Tr.locale()) } }
    }

    private fun joursCourts(j: Set<Int>): String = when (j) {
        (0..6).toSet() -> getString(R.string.reveils_tous_les_jours)
        setOf(0, 1, 2, 3, 4) -> getString(R.string.reveils_semaine)
        setOf(5, 6) -> getString(R.string.reveils_week_end)
        else -> nomsJours().filterIndexed { k, _ -> k in j }.joinToString(", ")
    }

    // ------------------------------------------------------------------ Édition

    private fun dessinerEdition(r: JSONObject) {
        val nouveau = !r.has("id")
        val d = donnees ?: JSONObject()
        val gerer = d.optJSONObject("droits")?.optBoolean("gerer") == true
        val decl = r.optJSONObject("declenchement")
        val hebdo = nouveau || decl == null || decl.optString("type", "hebdo") == "hebdo"
        val demandeurs = d.optJSONArray("demandeurs") ?: JSONArray()
        val noms = d.optJSONObject("noms") ?: JSONObject()
        var pour = r.optString("demandeur").ifEmpty { d.optString("moi").ifEmpty { demandeurs.optJSONObject(0)?.optString("personne") ?: "" } }

        racine.addView(Ui.marge(this, Ui.titre(this, getString(if (nouveau) R.string.reveils_nouveau else R.string.reveils_modifier)), bas = 12f))
        val carte = Ui.carte(this)
        carte.addView(Ui.section(this, getString(R.string.reveils_nom)))
        val champNom = Ui.champ(this, getString(R.string.reveil_nom_defaut), r.optString("nom"))
        carte.addView(champNom)

        // Pour qui (Décideur, plusieurs personnes possibles).
        val appareilsZone = Ui.colonne(this)
        val choisis = HashSet<String>().apply { r.optJSONArray("appareils_choisis")?.let { a -> for (i in 0 until a.length()) add(a.optString(i)) } }
        fun dessinerAppareils() {
            appareilsZone.removeAllViews()
            val p = (0 until demandeurs.length()).mapNotNull { demandeurs.optJSONObject(it) }.firstOrNull { it.optString("personne") == pour }
            val a = p?.optJSONArray("appareils") ?: JSONArray()
            if (a.length() == 0) { appareilsZone.addView(Ui.texte(this, getString(R.string.reveils_appareils_defaut), 13f, Ui.TEXTE_2)); return }
            appareilsZone.addView(Ui.texte(this, getString(R.string.reveils_appareils_aide), 13f, Ui.TEXTE_2))
            for (k in 0 until a.length()) {
                val ap = a.optJSONObject(k) ?: continue
                val id = ap.optString("id")
                // Case sans libellé + texte de Vision : un nom d'appareil long (« TELEFON-…-KINDERZIMMER ») passe à la
                // ligne à ses tirets ; le libellé d'une CheckBox, lui, se coupait au milieu d'un mot (recette 412 dp, 130 %).
                val ligne = Ui.rangee(this).apply { gravity = Gravity.CENTER_VERTICAL }
                val caseApp = Ui.caseACocher(this, "", id in choisis) { v -> if (v) choisis.add(id) else choisis.remove(id) }
                    .apply { contentDescription = ap.optString("nom", id) }
                ligne.addView(caseApp)
                ligne.addView(Ui.poids(Ui.texte(this, ap.optString("nom", id), 15f, Ui.TEXTE).apply {
                    setOnClickListener { caseApp.toggle() }
                }))
                appareilsZone.addView(ligne)
            }
        }
        if (gerer && demandeurs.length() > 1) {
            carte.addView(Ui.marge(this, Ui.section(this, getString(R.string.reveils_pour_qui)), haut = 14f))
            val boutonPour = Ui.boutonSecondaire(this, noms.optString(pour, pour.substringAfter('.'))) {}
            boutonPour.setOnClickListener {
                val ids = (0 until demandeurs.length()).mapNotNull { demandeurs.optJSONObject(it)?.optString("personne") }
                Ui.dialogue(this).setTitle(R.string.reveils_pour_qui)
                    .setItems(ids.map { noms.optString(it, it.substringAfter('.')) }.toTypedArray()) { _, k ->
                        if (ids[k] != pour) { pour = ids[k]; choisis.clear(); boutonPour.text = noms.optString(pour, pour.substringAfter('.')); dessinerAppareils() }
                    }.setNegativeButton(R.string.commun_annuler, null).show()
            }
            carte.addView(boutonPour)
        }

        // Heure et jours (réveils hebdo) ; sinon, l'heure vient d'une automatisation.
        val heures = (decl?.optString("heure") ?: "07:00").ifEmpty { "07:00" }
        val selH = Ui.selecteurNombre(this, 0, 23, heures.substring(0, 2).toIntOrNull() ?: 7, Array(24) { String.format(java.util.Locale.ROOT, "%02d", it) })
        val selM = Ui.selecteurNombre(this, 0, 59, heures.substring(3, 5).toIntOrNull() ?: 0, Array(60) { String.format(java.util.Locale.ROOT, "%02d", it) })
        val jours = ReveilsModele.jours(decl).toMutableSet()
        if (hebdo) {
            carte.addView(Ui.marge(this, Ui.section(this, getString(R.string.reveils_heure)), haut = 14f))
            val ligne = Ui.rangee(this).apply { gravity = Gravity.CENTER }
            ligne.addView(selH)
            ligne.addView(Ui.marge(this, Ui.texte(this, ":", 24f, Ui.TEXTE, gras = true), gauche = 8f, droite = 8f))
            ligne.addView(selM)
            carte.addView(ligne)
            carte.addView(Ui.marge(this, Ui.section(this, getString(R.string.reveils_jours)), haut = 14f))
            val noms7 = nomsJours()
            val grille = Ui.colonne(this)
            var rangee: LinearLayout? = null
            noms7.forEachIndexed { k, n ->
                if (k % 4 == 0) { rangee = Ui.rangee(this); grille.addView(rangee) }
                rangee!!.addView(Ui.poids(Ui.caseACocher(this, n, k in jours) { v -> if (v) jours.add(k) else jours.remove(k) }))
            }
            if (noms7.size % 4 != 0) repeat(4 - noms7.size % 4) { rangee!!.addView(Ui.poids(View(this))) }
            carte.addView(grille)
        } else {
            carte.addView(Ui.marge(this, Ui.texte(this, getString(R.string.reveils_heure_automatisation), 13f, Ui.TEXTE_2), haut = 14f))
        }

        // Son : sonnerie ou flux (radio, podcast, adresse).
        carte.addView(Ui.marge(this, Ui.section(this, getString(R.string.reveils_son)), haut = 14f))
        val source = r.optJSONObject("source")
        val flux = source?.optString("type") == "url" || source?.optString("type") == "video"
        val champUrl = Ui.champ(this, getString(R.string.reveils_adresse_flux), if (flux) source?.optString("uri").orEmpty() else "")
        champUrl.visibility = if (flux) View.VISIBLE else View.GONE
        // Le son n'est réécrit que si on y touche : une source posée ailleurs (Music Assistant) reste telle quelle.
        var sonTouche = false
        if (source?.optString("type") == "ma") carte.addView(Ui.texte(this, getString(R.string.reveils_source_gardee), 13f, Ui.TEXTE_2))
        carte.addView(Ui.caseACocher(this, getString(R.string.reveils_son_flux_choix), flux) { v ->
            sonTouche = true; champUrl.visibility = if (v) View.VISIBLE else View.GONE
        })
        champUrl.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { if (champUrl.hasFocus()) sonTouche = true }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        carte.addView(champUrl)
        carte.addView(Ui.texte(this, getString(R.string.reveils_son_repli), 12f, Ui.TEXTE_3))

        carte.addView(Ui.marge(this, Ui.section(this, getString(R.string.reveils_appareils)), haut = 14f))
        carte.addView(appareilsZone)
        dessinerAppareils()
        racine.addView(carte)

        val boutons = Ui.rangeeBoutons(this)
        boutons.addView(Ui.poids(Ui.boutonSecondaire(this, getString(R.string.commun_annuler)) { edition = null; dessiner() }))
        boutons.addView(Ui.marge(this, Ui.poids(Ui.boutonPrimaire(this, getString(R.string.reveils_enregistrer)) {
            if (hebdo && jours.isEmpty()) { Ui.toast(this, getString(R.string.reveils_un_jour)); return@boutonPrimaire }
            val url = champUrl.text.toString().trim()
            val estFlux = champUrl.visibility == View.VISIBLE && url.isNotEmpty()
            val corps = JSONObject()
                .put("nom", champNom.text.toString().trim())
                .put("appareils", JSONArray(choisis.toList()))
            if (nouveau || sonTouche) corps.put("source", if (estFlux) JSONObject().put("type", "url").put("uri", url) else JSONObject().put("type", "sonnerie"))
            if (pour.isNotEmpty() && (nouveau || pour != r.optString("demandeur"))) corps.put("demandeur", pour)
            if (hebdo) corps.put("declenchement", JSONObject().put("type", "hebdo").put("heure", ReveilsModele.hhmm(selH.value, selM.value))
                .put("jours", JSONArray(jours.sorted())))
            if (enCours) return@boutonPrimaire
            if (nouveau) appeler("creer", JSONObject().put("reveil", corps)) { rep -> if (rep != null) { edition = null; charger() } }
            else appeler("modifier", JSONObject().put("reveil_id", r.optString("id")).put("reveil", corps)) { rep -> if (rep != null) { edition = null; charger() } }
        }), gauche = 8f))
        racine.addView(Ui.marge(this, boutons, haut = 14f))
        if (!nouveau && peut(r, "supprimer")) racine.addView(Ui.marge(this, Ui.boutonDanger(this, getString(R.string.reveils_supprimer)) {
            Ui.dialogue(this).setTitle(r.optString("nom")).setMessage(R.string.reveils_supprimer_question)
                .setPositiveButton(R.string.reveils_supprimer) { _, _ -> appeler("supprimer", JSONObject().put("reveil_id", r.optString("id"))) { rep -> if (rep != null) { edition = null; charger() } } }
                .setNegativeButton(R.string.commun_annuler, null).show()
        }, haut = 10f))
        racine.addView(Ui.espace(this, 24f))
        champNom.requestFocus()
    }
}
