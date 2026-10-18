// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * 1.90.4 / HA 2.4.7 (décision du projet : liaison d'une télé SANS code à l'écran) : sur l'appareil d'un {décideur}, la
 * demande « Nouvel appareil ». On voit son nom ; on choisit à qui il est (un {demandeur}, ou l'écran partagé du
 * {lieu}) et on accepte, ou on refuse. Rien à recopier : l'appareil, qui attend, reçoit son accès tout seul.
 */
class NouvelAppareilActivity : Activity() {
    companion object {
        fun intention(ctx: Context): Intent = Intent(ctx, NouvelAppareilActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    private lateinit var racine: LinearLayout
    private val cfg by lazy { Config(this) }
    private var fini = false
    /** Le choix de chaque demande affichée : id de la demande → « person.x » ou « partage ». */
    private val choix = HashMap<String, String>()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Ui.charger(this)
        if (Build.VERSION.SDK_INT >= 21) { window.statusBarColor = Ui.FOND; window.navigationBarColor = Ui.FOND }
        racine = Ui.colonne(this, Ui.dp(this, 20f)).apply { setBackgroundColor(Ui.FOND) }
        setContentView(android.widget.ScrollView(this).apply { setBackgroundColor(Ui.FOND); isFillViewport = true; addView(racine) })
        charger()
    }

    override fun onDestroy() { fini = true; super.onDestroy() }

    private fun entete(sous: String) {
        racine.removeAllViews()
        racine.addView(Ui.texte(this, getString(R.string.nouvel_appareil_titre), 22f, Ui.TEXTE, gras = true).apply { tag = "titre" })
        if (sous.isNotEmpty()) racine.addView(Ui.marge(this, Ui.texte(this, sous, 14f, Ui.TEXTE_2).apply { tag = "sous" }, haut = 6f))
    }

    private fun charger() {
        entete(getString(R.string.commun_chargement))
        Thread {
            val r = try { Result.success(Net.post(this, cfg, "/api/pc_parental/appairage/decideur/liste", JSONObject().put("id", cfg.id).put("secret", cfg.secret))) }
                    catch (e: Exception) { Result.failure(e) }
            runOnUiThread { if (!fini) r.onSuccess { liste(it) }.onFailure { e ->
                entete(if (e is Net.Echec && e.code == 403) getString(R.string.nouvel_appareil_pas_de_droits, Vocabulaire.decideur(this))
                       else getString(R.string.dpa_injoignable))
                racine.addView(Ui.marge(this, Ui.boutonSecondaire(this, getString(R.string.commun_reessayer)) { charger() }, haut = 16f))
            } }
        }.start()
    }

    private fun liste(r: JSONObject) {
        val a = r.optJSONArray("demandes") ?: JSONArray()
        entete(if (a.length() == 0) getString(R.string.nouvel_appareil_aucune) else "")
        for (i in 0 until a.length()) a.optJSONObject(i)?.let { carte(it) }
        if (a.length() == 0) racine.addView(Ui.marge(this, Ui.boutonSecondaire(this, getString(R.string.commun_fermer)) { finish() }, haut = 16f))
    }

    private fun carte(d: JSONObject) {
        val id = d.optString("demande")
        val c = Ui.carte(this).apply { tag = "demande_$id" }
        c.addView(Ui.texte(this, d.optString("nom"), 20f, Ui.TEXTE, gras = true).apply { tag = "nom" })
        c.addView(Ui.marge(this, Ui.texte(this, getString(R.string.nouvel_appareil_demande, NouvelAppareil.heure(d.optLong("depuis"))), 13f, Ui.TEXTE_2), haut = 2f))
        d.optString("existant").takeIf { it.isNotEmpty() && it != "null" }?.let {
            c.addView(Ui.marge(this, Ui.texte(this, getString(R.string.nouvel_appareil_reprise, it), 13f, Ui.TEXTE_2), haut = 4f))
        }
        c.addView(Ui.marge(this, Ui.section(this, getString(R.string.nouvel_appareil_a_qui)), haut = 14f))
        val options = NouvelAppareil.options(NouvelAppareil.personnes(d), getString(R.string.nouvel_appareil_partage, d.optString("lieu")))
        NouvelAppareil.parDefaut(d.optString("genre"))?.let { choix.putIfAbsent(id, it) }
        val lignes = ArrayList<TextView>()
        fun peindre() {
            for (l in lignes) {
                val oui = l.tag == "choix_${choix[id]}"
                l.background = Ui.fond(if (oui) Ui.OR else Ui.CARTE, Ui.dp(this, 12f).toFloat(), Ui.dp(this, 1f), if (oui) Ui.OR else Ui.LIGNE)
                l.setTextColor(if (oui) Ui.OR_SOMBRE else Ui.TEXTE)
            }
        }
        for ((valeur, libelle) in options) {
            val l = Ui.texte(this, libelle, 16f, Ui.TEXTE).apply {
                tag = "choix_$valeur"; isClickable = true; isFocusable = true; minHeight = Ui.dp(this@NouvelAppareilActivity, 48f)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(Ui.dp(this@NouvelAppareilActivity, 14f), Ui.dp(this@NouvelAppareilActivity, 10f), Ui.dp(this@NouvelAppareilActivity, 14f), Ui.dp(this@NouvelAppareilActivity, 10f))
                setOnClickListener { choix[id] = valeur; peindre() }
            }
            lignes.add(l)
            c.addView(Ui.marge(this, l, haut = 6f))
        }
        peindre()
        val rangee = Ui.rangeeBoutons(this)
        rangee.addView(Ui.boutonPrimaire(this, getString(R.string.nouvel_appareil_accepter)) {
            val qui = choix[id]
            // FAILLE (l'auteur, 16-10) : valider un appareil l'admet dans le Lieu — empreinte ou code à chaque fois (avant : rien).
            if (qui == null) Ui.toast(this, getString(R.string.nouvel_appareil_choisir))
            else Garde.exigerOctroi(this, getString(R.string.nouvel_appareil_accepter)) { decider(id, "accepter", qui) }
        }.apply { tag = "accepter" })
        rangee.addView(Ui.marge(this, Ui.boutonSecondaire(this, getString(R.string.nouvel_appareil_refuser)) { decider(id, "refuser", "") }.apply { tag = "refuser" }, gauche = 10f))
        c.addView(Ui.marge(this, rangee, haut = 16f))
        racine.addView(Ui.marge(this, c, haut = 14f))
    }

    private fun decider(id: String, action: String, personne: String) {
        val corps = JSONObject().put("id", cfg.id).put("secret", cfg.secret).put("demande", id).put("action", action).put("personne", personne)
        Thread {
            val r = try { Net.post(this, cfg, "/api/pc_parental/appairage/decideur/decision", corps) } catch (_: Exception) { null }
            runOnUiThread {
                if (fini) return@runOnUiThread
                val texte = when {
                    r == null -> getString(R.string.dpa_injoignable)
                    r.optString("etat") == "validee" -> getString(R.string.nouvel_appareil_accepte, r.optString("nom"))
                    r.optString("etat") == "refusee" && r.optBoolean("ok") -> getString(R.string.nouvel_appareil_refuse)
                    else -> getString(R.string.nouvel_appareil_deja)
                }
                Ui.toast(this, texte)
                charger()
            }
        }.start()
    }
}

/** La logique pure de l'écran (testée en JVM). */
object NouvelAppareil {
    const val PARTAGE = "partage"

    /** Les choix « à qui est cet appareil » : chaque {demandeur} (« person.x », nom), puis l'écran partagé du {lieu}. */
    fun options(personnes: List<Pair<String, String>>, libellePartage: String): List<Pair<String, String>> =
        personnes.filter { it.first.startsWith("person.") }.map { it.first to it.second.ifEmpty { it.first } } + (PARTAGE to libellePartage)

    fun personnes(d: JSONObject): List<Pair<String, String>> {
        val p = d.optJSONArray("personnes") ?: JSONArray()
        return (0 until p.length()).mapNotNull { i -> p.optJSONObject(i)?.let { it.optString("id") to it.optString("nom") } }
    }

    /** Une télé est d'abord un écran partagé ; pour les autres, on laisse choisir. */
    fun parDefaut(genre: String): String? = if (genre == "tele") PARTAGE else null

    fun heure(secondes: Long): String = if (secondes <= 0) "" else
        java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(secondes * 1000))
}
