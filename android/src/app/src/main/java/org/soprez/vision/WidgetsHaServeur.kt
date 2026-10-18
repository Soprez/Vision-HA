// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection

/**
 * Les réglages des widgets Home Assistant posés sur l'accueil, et le catalogue des entités permises
 * (/widgets/entites) gardé pour un rendu immédiat et hors ligne. Le catalogue se recharge quand le relevé
 * annonce une nouvelle « widgets.version » (AgentService → noterVersion).
 */
object WidgetsHaStock {
    private const val PREFS = "widgets_ha"

    /** Change à chaque réglage écrit ou catalogue rechargé : l'accueil se rebâtit. */
    @Volatile var generation = 0; private set

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun reglage(ctx: Context, id: String): WidgetsHa.Reglage? = WidgetsHa.decoder(prefs(ctx).getString("w:$id", null))

    fun ecrire(ctx: Context, r: WidgetsHa.Reglage) { prefs(ctx).edit().putString("w:${r.id}", WidgetsHa.encoder(r)).apply(); generation++ }

    fun supprimer(ctx: Context, id: String) { prefs(ctx).edit().remove("w:$id").apply(); generation++ }

    fun nouvelId(): String = java.lang.Long.toString(System.currentTimeMillis(), 36) + (100 + (Math.random() * 800).toInt())

    // ------------------------------------------------------------------ catalogue

    @Volatile private var catalogueMemo: Map<String, WidgetsHa.Entite>? = null

    fun catalogue(ctx: Context): Map<String, WidgetsHa.Entite> {
        catalogueMemo?.let { return it }
        val l = WidgetsHa.analyserEntites(prefs(ctx).getString("catalogue", "") ?: "")
        return l.associateBy { it.entityId }.also { catalogueMemo = it }
    }

    fun entite(ctx: Context, id: String): WidgetsHa.Entite? = catalogue(ctx)[id]

    /** 1.90.0 : les tableaux de bord et vues de Home Assistant du dernier catalogue (Décideur seulement). */
    fun vuesHa(ctx: Context): List<Pair<String, String>> = WidgetsHa.vuesHa(prefs(ctx).getString("catalogue", "") ?: "")

    /** La version annoncée par le dernier relevé, et celle du catalogue gardé. */
    fun versionServeur(ctx: Context): String = prefs(ctx).getString("version_serveur", "") ?: ""
    fun versionCatalogue(ctx: Context): String = prefs(ctx).getString("version_catalogue", "") ?: ""

    /** Appelé à chaque relevé : retient la version des widgets annoncée par le serveur. */
    fun noterVersion(ctx: Context, r: JSONObject) {
        val v = WidgetsHa.versionReleve(r.optJSONObject("widgets")?.let { o -> mapOf("version" to o.opt("version")?.toString()) }, r.opt("widgets_version"))
        if (v.isNotEmpty() && v != versionServeur(ctx)) prefs(ctx).edit().putString("version_serveur", v).apply()
    }

    /** Faut-il recharger le catalogue (jamais chargé, ou version annoncée différente) ? */
    fun catalogueAPerime(ctx: Context): Boolean {
        val p = prefs(ctx)
        if (!p.contains("catalogue")) return true
        val s = versionServeur(ctx)
        return s.isNotEmpty() && s != versionCatalogue(ctx)
    }

    /** Recharge /widgets/entites (hors du fil principal). Rend la liste, ou lève Net.Echec / une IOException. */
    fun recharger(ctx: Context): List<WidgetsHa.Entite> {
        val cfg = Config(ctx)
        val r = Net.post(ctx, cfg, "/api/pc_parental/widgets/entites", JSONObject().put("id", cfg.id).put("secret", cfg.secret), 20_000)
        val texte = r.toString()
        // La version renvoyée avec la liste (sinon celle du dernier relevé) : elle dit que la liste est à jour.
        val version = r.opt("version")?.toString()?.takeIf { it.isNotEmpty() && it != "null" } ?: versionServeur(ctx)
        return garderCatalogue(ctx, texte, version)
    }

    /** Garde une réponse /widgets/entites comme catalogue (aussi pour les essais, sans Home Assistant). */
    fun garderCatalogue(ctx: Context, texte: String, version: String = ""): List<WidgetsHa.Entite> {
        prefs(ctx).edit().putString("catalogue", texte).putString("version_catalogue", version).apply()
        val l = WidgetsHa.analyserEntites(texte)
        catalogueMemo = l.associateBy { it.entityId }
        generation++
        return l
    }

    // ------------------------------------------------------------------ appels

    /** États actuels (POST /widgets/etats) ; `details` : les entités dont un widget montre le détail (1.88.0). */
    fun etats(ctx: Context, entites: Collection<String>, details: Collection<String> = emptyList()): Map<String, WidgetsHa.EtatEntite> {
        if (entites.isEmpty()) return emptyMap()
        val cfg = Config(ctx)
        val corps = JSONObject().put("id", cfg.id).put("secret", cfg.secret).put("entites", JSONArray(entites.toList()))
        if (details.isNotEmpty()) corps.put("details", JSONArray(details.toList()))
        val r = Net.post(ctx, cfg, "/api/pc_parental/widgets/etats", corps, 15_000)
        return WidgetsHa.analyserEtats(r.toString())
    }

    private val historiques = ConcurrentHashMap<String, Pair<Long, WidgetsHa.Historique>>()

    /** Historique 24 h / 7 j, gardé cinq minutes. */
    fun historique(ctx: Context, entite: String, periode: String): WidgetsHa.Historique? {
        val cle = "$entite|$periode"
        historiques[cle]?.let { (quand, h) -> if (System.currentTimeMillis() - quand < 300_000L) return h }
        val cfg = Config(ctx)
        val r = Net.post(ctx, cfg, "/api/pc_parental/widgets/historique", JSONObject().put("id", cfg.id).put("secret", cfg.secret).put("entite", entite).put("periode", periode), 20_000)
        val h = WidgetsHa.analyserHistorique(r.toString()) ?: return null
        historiques[cle] = System.currentTimeMillis() to h
        return h
    }

    /** 1.76.0 : l'historique déjà gardé (sans appel réseau), pour dessiner un widget système d'un coup. */
    fun historiqueGarde(entite: String, periode: String): WidgetsHa.Historique? = historiques["$entite|$periode"]?.second

    /**
     * Le résultat d'une action : `message` null si c'est fait, sinon le refus en clair ; `question` non nulle quand
     * Home Assistant demande une confirmation (1.88.0 : entité réglée « avec confirmation », réponse 409).
     */
    data class Resultat(val message: String?, val question: String? = null)

    /** Une action (POST /widgets/action) ; `confirme` : l'utilisateur vient de confirmer (1.88.0). */
    fun agir(ctx: Context, entite: String, service: String, donnees: JSONObject = JSONObject(), confirme: Boolean = false): Resultat = try {
        val cfg = Config(ctx)
        val corps = JSONObject().put("id", cfg.id).put("secret", cfg.secret).put("entite", entite).put("service", service).put("data", donnees)
        if (confirme) corps.put("confirme", true)
        val r = Net.post(ctx, cfg, "/api/pc_parental/widgets/action", corps, 15_000)
        if (r.optBoolean("ok", false)) Resultat(null) else Resultat(WidgetsHa.messageErreur(500, ""))
    } catch (e: Net.Echec) {
        if (e.code == 409 && e.corps?.optString("error") == "confirmer") Resultat(null, e.corps.optString("retour"))
        else Resultat(WidgetsHa.messageErreur(e.code, e.corps?.optString("error").orEmpty()))
    } catch (_: Exception) { Resultat(WidgetsHa.messageErreur(0, "")) }

    /**
     * 1.91.3 : la mise en page d'une entité, réglée depuis l'appli d'un Décideur (POST /widgets/mise_en_page) — la même
     * configuration que l'onglet Widgets HA, pour tous les appareils. Rend null si c'est fait (le catalogue est alors
     * rechargé), sinon le refus en clair (« réservé aux Décideurs » si l'appareil ne pilote pas). Hors du fil principal.
     */
    fun reglerMiseEnPage(ctx: Context, entite: String, m: WidgetsHa.MiseEnPage): String? = try {
        val cfg = Config(ctx)
        val corps = JSONObject().put("id", cfg.id).put("secret", cfg.secret).put("entite", entite)
            .put("mise_en_page", JSONObject(WidgetsHa.versHa(m)))
        val r = Net.post(ctx, cfg, "/api/pc_parental/widgets/mise_en_page", corps, 15_000)
        if (r.optBoolean("ok", false)) { try { recharger(ctx) } catch (_: Exception) {}; null } else WidgetsHa.messageErreur(500, "")
    } catch (e: Net.Echec) {
        if (e.code == 403 && e.corps?.optString("error") == "piloter") ctx.getString(R.string.editeurwidget_mep_reserve, Vocabulaire.decideur(ctx))
        else WidgetsHa.messageErreur(e.code, e.corps?.optString("error").orEmpty())
    } catch (_: Exception) { WidgetsHa.messageErreur(0, "") }
}

/**
 * Le flux d'états des widgets Home Assistant (SSE /api/pc_parental/widgets/flux) : UNE connexion pour tous les
 * widgets à l'écran, reconnexion avec un délai croissant (WidgetsHa.delaiReconnexion), arrêt dès que l'accueil (ou
 * l'éditeur) n'est plus visible. Au départ et à chaque reconnexion, les états sont relus d'un coup (/widgets/etats).
 */
object FluxWidgets {
    enum class Statut { ARRET, CONNEXION, EN_DIRECT, HORS_LIGNE, MODULE_COUPE }

    private val main = Handler(Looper.getMainLooper())
    val etats = ConcurrentHashMap<String, WidgetsHa.EtatEntite>()
    @Volatile var statut = Statut.ARRET; private set

    @Volatile private var fil: Thread? = null
    @Volatile private var connexion: HttpURLConnection? = null
    @Volatile private var voulues: Set<String> = emptySet()
    @Volatile private var ecoute: ((String?) -> Unit)? = null
    /** 1.88.0 : les entités dont un widget montre le détail (relu après chaque changement signalé par le flux). */
    @Volatile var details: Set<String> = emptySet()
    @Volatile private var appCtx: Context? = null

    /**
     * Démarre (ou garde) le flux pour ces entités. `surChange` est appelé sur le fil principal avec l'entité
     * modifiée (null : tout a pu changer, ou le statut a changé).
     */
    @Synchronized
    fun demarrer(ctx: Context, entites: Set<String>, detailsVoulus: Set<String> = emptySet(), surChange: (String?) -> Unit) {
        ecoute = surChange
        details = detailsVoulus
        appCtx = ctx.applicationContext
        if (entites.isEmpty()) { arreter(); return }
        if (fil?.isAlive == true && entites == voulues) return
        arreter()
        voulues = entites
        val app = ctx.applicationContext
        val t = Thread { boucle(app, entites) }
        t.isDaemon = true
        fil = t
        t.start()
    }

    @Synchronized
    fun arreter() {
        fil?.interrupt(); fil = null
        try { connexion?.disconnect() } catch (_: Exception) {}
        connexion = null
        if (statut != Statut.MODULE_COUPE) statut = Statut.ARRET
    }

    private fun prevenir(id: String?) { val e = ecoute; main.post { e?.invoke(id) } }

    private fun poser(s: Statut) { if (statut != s) { statut = s; prevenir(null) } }

    private fun boucle(ctx: Context, entites: Set<String>) {
        var essai = 0
        val moi = Thread.currentThread()
        while (!moi.isInterrupted && fil === moi) {
            poser(Statut.CONNEXION)
            try {
                WidgetsHaStock.etats(ctx, entites, details.filter { it in entites }).forEach { (k, v) -> etats[k] = v }
                prevenir(null)
                if (ecouter(ctx, entites) { essai = 0 }) {
                    // `event: fin` : les droits ont changé. La liste permise d'abord (un 403 arrête tout), puis on se reconnecte.
                    WidgetsHaStock.recharger(ctx)
                    prevenir(null)
                    essai = 0
                    continue
                }
            } catch (e: Net.Echec) {
                if (e.code == 403 && e.corps?.optString("error") == "module") { poser(Statut.MODULE_COUPE); return }
            } catch (_: InterruptedException) { return
            } catch (_: Exception) {}
            if (moi.isInterrupted || fil !== moi) return
            poser(Statut.HORS_LIGNE)
            try { Thread.sleep(WidgetsHa.delaiReconnexion(essai++)) } catch (_: InterruptedException) { return }
        }
    }

    /**
     * Une connexion au flux, tenue jusqu'à sa coupure. `connecte` : la connexion vient d'aboutir.
     * Rend vrai si le serveur a fermé par `event: fin` (droits changés).
     */
    private fun ecouter(ctx: Context, entites: Set<String>, connecte: () -> Unit): Boolean {
        val cfg = Config(ctx)
        val base = cfg.urlActive.ifEmpty { cfg.adresses().firstOrNull() ?: throw java.io.IOException(ctx.getString(R.string.widgetsserveur_aucune_adresse)) }
        val c = URL(base + "/api/pc_parental/widgets/flux").openConnection() as HttpURLConnection
        if (c is HttpsURLConnection) c.sslSocketFactory = Net.fabriqueSsl(ctx)
        connexion = c
        c.connectTimeout = 15_000
        c.readTimeout = 70_000   // battement toutes les 25 s : 70 s sans rien = coupure
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        c.setRequestProperty("Accept", "text/event-stream")
        c.setRequestProperty("User-Agent", "Vision-Android/" + BuildConfigCompat.version(ctx))
        try {
            // Le secret part dans le corps (POST), jamais dans l'adresse.
            c.outputStream.use { it.write(JSONObject().put("id", cfg.id).put("secret", cfg.secret).toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code !in 200..299) {
                val corps = try { c.errorStream?.bufferedReader()?.use { it.readText() } } catch (_: Exception) { null }
                throw Net.Echec(code, "HTTP $code", try { JSONObject(corps ?: "{}") } catch (_: Exception) { null })
            }
            connecte()
            poser(Statut.EN_DIRECT)
            val sse = WidgetsHa.LecteurSse()
            c.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                while (!Thread.currentThread().isInterrupted) {
                    val l = r.readLine() ?: break
                    val ev = sse.ligne(l) ?: continue
                    if (ev.type == "fin") return true
                    val (id, etat) = WidgetsHa.analyserEvenement(ev.donnees) ?: continue
                    if (id !in entites) continue
                    // Le flux ne porte pas le détail : on garde le précédent, puis on le relit (une liste a pu changer).
                    val avant = etats[id]
                    etats[id] = if (etat.detail == null && avant?.detail != null) etat.copy(detail = avant.detail, detailTotal = avant.detailTotal) else etat
                    prevenir(id)
                    if (id in details) relireDetail(ctx, id)
                }
            }
        } finally {
            try { c.disconnect() } catch (_: Exception) {}
            if (connexion === c) connexion = null
        }
        return false
    }

    private val detailsEnCours = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** Le détail d'une entité relu (une fois à la fois par entité, hors du fil du flux). */
    private fun relireDetail(ctx: Context, id: String) {
        if (!detailsEnCours.add(id)) return
        Thread {
            try {
                Thread.sleep(400)
                WidgetsHaStock.etats(ctx, listOf(id), listOf(id))[id]?.let { etats[id] = it; prevenir(id) }
            } catch (_: Exception) {
            } finally { detailsEnCours.remove(id) }
        }.also { it.isDaemon = true }.start()
    }

    /** Après un module rouvert, ou une action : on peut repartir de zéro. */
    fun oublierCoupure() { if (statut == Statut.MODULE_COUPE) statut = Statut.ARRET }
}
