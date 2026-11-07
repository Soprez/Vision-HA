// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.json.JSONObject

/**
 * Demander l'accès à une appli ou un site fermé : la demande part chez les
 * parents, qui répondent depuis leur téléphone (pour de bon, un moment, ou
 * non). La réponse revient ici comme un message ordinaire.
 */
object Acces {
    /** Demandes déjà parties (clé → heure), pour ne pas insister. */
    private val envoyees = HashMap<String, Long>()

    /** Refus récents des parents (relevé, `refus`) : « genre:nom » → fin (ms). Comme l'agent Windows. */
    @Volatile private var refus: Map<String, Long> = emptyMap()

    /**
     * 1.94 : ce qu'on vient de demander et qu'on ne peut pas refaire tout de suite (relevé, `redemande`) : « genre:nom » → fin (ms).
     * La règle est celle de Home Assistant (il la refuse aussi) ; ici, on la montre : bouton grisé et « tu pourras redemander dans X min ».
     */
    @Volatile private var redemande: Map<String, Long> = emptyMap()
    /** L'attente réglée (minutes) ; 0 : pas d'attente (ou Home Assistant plus ancien : l'ancien comportement). */
    @Volatile var delaiRedemandeMin = 0

    /** La demande de temps a, elle aussi, son attente. */
    const val TEMPS = "temps"
    const val TEMPS_NOM = "demande"

    fun poserRedemande(a: org.json.JSONArray?, delaiMin: Int) {
        val m = HashMap<String, Long>()
        if (a != null) for (i in 0 until a.length()) {
            val r = a.optJSONObject(i) ?: continue
            m[r.optString("genre") + ":" + r.optString("nom").lowercase()] = r.optLong("jusqua") * 1000L
        }
        redemande = m
        delaiRedemandeMin = delaiMin
    }

    /**
     * Jusqu'à quand cette demande ne peut pas être refaite (0 : on peut). Dès l'envoi, sans attendre le relevé suivant : l'heure
     * d'envoi plus l'attente réglée. Pur côté calcul : voir [finAttente].
     */
    fun redemandeJusqua(genre: String, nom: String): Long {
        val n = nom.lowercase(); val maintenant = System.currentTimeMillis()
        return finAttente(redemande, envoyees["$genre:$nom"], delaiRedemandeMin, genre, n, maintenant)
    }

    fun redemandeTempsJusqua(): Long = redemandeJusqua(TEMPS, TEMPS_NOM)
    fun marquerTemps() { envoyees["$TEMPS:$TEMPS_NOM"] = System.currentTimeMillis() }

    /** « Tu pourras redemander dans 5 min. » (minutes arrondies au-dessus : jamais « 0 min » alors qu'il reste des secondes). */
    fun attenteTexte(ctx: Context, fin: Long): String {
        val min = ((fin - System.currentTimeMillis() + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
        return ctx.getString(R.string.acces_redemander_dans, Tr.duree(min))
    }

    /** Pur (essais JVM) : la fin de l'attente d'après le relevé, l'heure d'envoi et le délai réglé. */
    fun finAttente(releve: Map<String, Long>, envoyeeA: Long?, delaiMin: Int, genre: String, nom: String, maintenant: Long): Long {
        var fin = 0L
        for ((cle, jusqua) in releve) {
            if (jusqua <= maintenant || !cle.startsWith("$genre:")) continue
            val c = cle.substringAfter(':')
            if (c == nom || (genre == "sites" && nom.endsWith(".$c"))) fin = maxOf(fin, jusqua)
        }
        if (delaiMin > 0 && envoyeeA != null) fin = maxOf(fin, envoyeeA + delaiMin * 60_000L)
        return if (fin > maintenant) fin else 0L
    }

    /** HA 2.5.1 (ajouts 5 et 6 de l'auteur) : les sites bloqués récemment qu'on peut demander, et les mêmes par logiciel. */
    @Volatile var recents: org.json.JSONArray = org.json.JSONArray()
    @Volatile var groupes: org.json.JSONArray = org.json.JSONArray()

    fun poserRecents(r: JSONObject) {
        recents = r.optJSONArray("bloques_recents") ?: org.json.JSONArray()
        groupes = r.optJSONArray("groupes") ?: org.json.JSONArray()
    }

    /** « Roblox ne marche pas : 6 sites bloqués » : une seule demande pour le logiciel et ses sites. */
    fun demanderGroupe(ctx: Context, app: String, libelle: String, sites: List<String>, apres: ((Boolean) -> Unit)? = null) {
        val cfg = Config(ctx.applicationContext)
        val principal = Handler(Looper.getMainLooper())
        if (redemandeJusqua("apps", app) > 0) { principal.post { Ui.toast(ctx, attenteTexte(ctx, redemandeJusqua("apps", app)), genre = OeilModele.Genre.ERREUR); apres?.invoke(false) }; return }
        envoyees["apps:$app"] = System.currentTimeMillis()
        Thread {
            var retour = ""
            val ok = try {
                val r = Net.post(ctx, cfg, "/api/pc_parental/demande", JSONObject()
                    .put("id", cfg.id).put("secret", cfg.secret).put("genre", "apps").put("nom", app)
                    .put("libelle", libelle).put("sites", org.json.JSONArray(sites)))
                retour = r.optString("retour"); r.optBoolean("ok")
            } catch (_: Exception) { false }
            if (!ok) envoyees.remove("apps:$app")
            principal.post {
                Ui.toast(ctx, if (ok) ctx.getString(R.string.acces_demande_envoyee) else retour.ifEmpty { ctx.getString(R.string.acces_envoi_impossible) }, genre = if (ok) OeilModele.Genre.SUCCES else OeilModele.Genre.ERREUR)
                apres?.invoke(ok)
            }
        }.start()
    }

    fun poserRefus(a: org.json.JSONArray?) {
        val m = HashMap<String, Long>()
        if (a != null) for (i in 0 until a.length()) {
            val r = a.optJSONObject(i) ?: continue
            m[r.optString("genre") + ":" + r.optString("nom").lowercase()] = r.optLong("jusqua") * 1000L
        }
        refus = m
    }

    /** Jusqu'à quand un parent a dit non pour ceci (0 : on peut demander). Un refus sur un site vaut pour ses sous-domaines. */
    fun refuseJusqua(genre: String, nom: String): Long {
        val n = nom.lowercase(); val maintenant = System.currentTimeMillis()
        var fin = 0L
        for ((cle, jusqua) in refus) {
            if (jusqua <= maintenant || !cle.startsWith("$genre:")) continue
            val c = cle.substringAfter(':')
            if (c == n || (genre == "sites" && n.endsWith(".$c"))) fin = maxOf(fin, jusqua)
        }
        return fin
    }

    fun dejaDemande(genre: String, nom: String): Boolean {
        val t = envoyees["$genre:$nom"] ?: return false
        return System.currentTimeMillis() - t < 15 * 60_000L
    }

    fun demander(ctx: Context, genre: String, nom: String, libelle: String, lien: String = "", motif: String = "", apres: ((Boolean) -> Unit)? = null, duree: String = "") {
        val cfg = Config(ctx.applicationContext)
        val principal = Handler(Looper.getMainLooper())
        if (refuseJusqua(genre, nom) > 0) { principal.post { apres?.invoke(false) }; return }
        if (redemandeJusqua(genre, nom) > 0) { principal.post { Ui.toast(ctx, attenteTexte(ctx, redemandeJusqua(genre, nom)), genre = OeilModele.Genre.ERREUR); apres?.invoke(false) }; return }
        envoyees["$genre:$nom"] = System.currentTimeMillis()
        Thread {
            var retour = ""
            val ok = try {
                val r = Net.post(ctx, cfg, "/api/pc_parental/demande", JSONObject()
                    .put("id", cfg.id).put("secret", cfg.secret)
                    .put("genre", genre).put("nom", nom).put("libelle", libelle)
                    .put("lien", lien).put("motif", motif).put("duree", duree))
                retour = r.optString("retour"); r.optBoolean("ok")
            } catch (_: Exception) { false }
            if (!ok) envoyees.remove("$genre:$nom")
            principal.post {
                Ui.toast(ctx, if (ok) ctx.getString(R.string.acces_demande_envoyee) else retour.ifEmpty { ctx.getString(R.string.acces_envoi_impossible) }, genre = if (ok) OeilModele.Genre.SUCCES else OeilModele.Genre.ERREUR)
                apres?.invoke(ok)
            }
        }.start()
    }

    /** « youtu.be/abc » ou « https://m.youtube.com/watch?v=… » → « youtube.com ». */
    fun hoteDe(lien: String): String {
        var l = lien.trim()
        if (l.isEmpty()) return ""
        if (!Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(l)) l = "https://$l"
        val h = try { java.net.URI(l).host ?: "" } catch (_: Exception) { "" }
        return Etat.domainePrincipal(h.removePrefix("www."))
    }

    /**
     * Petite boîte « lien ou motif » avant d'envoyer : les parents voient
     * exactement ce qui est demandé. Facultatif : vide, la demande part quand même.
     */
    fun dialogue(act: android.app.Activity, genre: String, nom: String, libelle: String, lienInitial: String = "", apres: ((Boolean) -> Unit)? = null): android.app.AlertDialog {
        val cadre = Ui.colonne(act, Ui.dp(act, 20f))
        val lien = if (genre == "sites") Ui.champ(act, act.getString(R.string.acces_lien_page), lienInitial) else null
        if (lien != null) cadre.addView(lien)
        val motif = Ui.champ(act, act.getString(R.string.acces_pourquoi), "")
        cadre.addView(Ui.marge(act, motif, haut = 8f))
        // Pour combien de temps : une heure, en permanence (quand l'écran est ouvert), ou toute la catégorie.
        cadre.addView(Ui.marge(act, Ui.texte(act, act.getString(R.string.acces_combien_de_temps), 13f, Ui.TEXTE_2), haut = 12f))
        val durees = listOf("1h" to act.getString(R.string.acces_une_heure), "toujours" to act.getString(R.string.acces_en_permanence), "categorie" to act.getString(R.string.acces_toute_categorie))
        var choisie = "1h"
        val rang = Ui.rangeeBoutons(act)
        val boutons = ArrayList<android.widget.Button>()
        fun peindre() { boutons.forEachIndexed { i, b -> val on = durees[i].first == choisie; b.alpha = if (on) 1f else 0.55f; b.scaleX = if (on) 1f else 0.96f; b.scaleY = b.scaleX } }
        durees.forEachIndexed { i, (code, nomD) ->
            val b = Ui.boutonSecondaire(act, nomD) { choisie = code; peindre() }
            boutons.add(b)
            rang.addView(Ui.marge(act, Ui.poids(b), gauche = if (i == 0) 0f else 6f))
        }
        peindre()
        cadre.addView(Ui.marge(act, rang, haut = 6f))
        cadre.addView(Ui.marge(act, Ui.texte(act, act.getString(R.string.acces_explication_durees), 11f, Ui.TEXTE_3), haut = 6f))
        return Ui.dialogue(act)
            .setTitle(Ui.coupable(act.getString(R.string.acces_demander_acces, libelle)))
            // Lot 1 (A7) : le contenu défile plutôt que d'être écrasé (grands caractères, télé, boutons de 48 dp).
            .setView(android.widget.ScrollView(act).apply { addView(cadre) })
            .setPositiveButton(act.getString(R.string.commun_lot2_envoyer)) { _, _ ->
                val l = lien?.text?.toString()?.trim() ?: ""
                val h = hoteDe(l)
                demander(act, genre, if (genre == "sites" && h.isNotEmpty()) h else nom,
                    if (genre == "sites" && h.isNotEmpty()) h else libelle, l, motif.text.toString().trim(), apres, choisie)
            }
            .setNegativeButton(act.getString(R.string.commun_lot2_annuler), null)
            .show()
    }

    /** Un parent répond à une demande d'accès. decision : toujours | temporaire | non. */
    // HA 2.5.0 : decision « accorder » avec un mode (« exception », « toujours ») ; vide pour les autres décisions.
    fun repondre(ctx: Context, pc: String, demande: String, decision: String, minutes: Int = Durees.ACCES_TEMPORAIRE, mode: String = "",
                 elements: org.json.JSONArray? = null, apres: ((Geste.Resultat) -> Unit)? = null) {
        val cfg = Config(ctx.applicationContext)
        val principal = Handler(Looper.getMainLooper())
        Thread {
            var ok = false
            val texte = try {
                val r = Net.post(ctx, cfg, "/api/pc_parental/parent", JSONObject()
                    .put("id", cfg.id).put("secret", cfg.secret)
                    .put("action", "acces").put("pc", pc).put("demande", demande)
                    .put("decision", decision).put("minutes", minutes).apply {
                        if (mode.isNotEmpty()) put("mode", mode)
                        // HA 2.5.1 : une demande groupée acceptée élément par élément (sans liste : tout).
                        if (elements != null) put("elements", elements)
                    })
                ok = r.optBoolean("deja") || r.optBoolean("ok")
                when {
                    r.optBoolean("deja") -> ctx.getString(R.string.acces_deja_traite, Vocabulaire.decideur(ctx))
                    !r.optBoolean("ok") -> ctx.getString(R.string.acces_ha_a_refuse)
                    else -> r.optString("retour").ifEmpty { ctx.getString(R.string.commun_lot2_c_est_fait) }
                }
            } catch (e: Net.Echec) {
                Geste.messageEchec(e.code)
            } catch (e: Exception) { ReseauLocal.message(ctx, e) }
            principal.post { Ui.toast(ctx, texte, genre = if (ok) OeilModele.Genre.SUCCES else OeilModele.Genre.ERREUR); apres?.invoke(Geste.Resultat(ok, texte)) }
        }.start()
    }
}
