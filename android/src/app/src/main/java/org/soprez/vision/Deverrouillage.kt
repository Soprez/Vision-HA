// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Déverrouillage à distance (Vision 1.68.0, docs/deverrouillage.md) : le côté de l'appareil enfant.
 *
 * L'enfant demande une action (« ouvrir l'appli X », « les réglages protégés », « le mode parent »)
 * pour quelques minutes, avec un défi tiré ici et gardé en mémoire seulement. Un parent approuve ;
 * Home Assistant signe l'approbation (ECDSA P-256) ; l'appareil la vérifie avec SON défi, applique
 * l'effet (celui du code parent, borné à l'action et à la durée), efface le défi et fait consommer
 * l'approbation. Un QR photographié, rejoué ou passé à un autre appareil échoue à la vérification.
 */
object Deverrouillage {
    /** « Mia et Nolan » : les personnes dont la validation manque encore (validation à plusieurs, 1.72.0). */
    fun enAttenteDe(r: org.json.JSONObject): String {
        val a = r.optJSONArray("en_attente_de") ?: return ""
        val noms = (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }
        return if (noms.size <= 1) noms.joinToString("") else noms.dropLast(1).joinToString(", ") + " et " + noms.last()
    }

    private const val PREFS = "deverrouillage"

    /** La demande en cours : en mémoire seulement (le défi ne touche jamais le disque). */
    class Demande(val id: String, val action: String, val minutes: Int, val defi: String, val expire: Long)
    @Volatile var enCours: Demande? = null
        private set

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ La clé publique et le relevé

    /** Ce que le relevé apporte : la clé publique de Home Assistant, et la biométrie permise ou non. */
    fun releve(ctx: Context, r: JSONObject) {
        r.optJSONObject("deverrouillage_cle")?.let { poserCle(ctx, it) }
        prefs(ctx).edit()
            .putBoolean("biometrie", r.optBoolean("biometrie", false))
            .putLong("releve_ok", System.currentTimeMillis())
            .apply()
    }

    private fun poserCle(ctx: Context, c: JSONObject) {
        val k = c.optString("k"); val pub = c.optString("pub")
        if (k.isNotEmpty() && pub.isNotEmpty()) prefs(ctx).edit().putString("k", k).putString("pub", pub).apply()
    }

    /** La biométrie vaut code parent ici : Home Assistant le dit, et son dernier mot a moins de 24 h. */
    fun biometriePermise(ctx: Context): Boolean {
        val p = prefs(ctx)
        return p.getBoolean("biometrie", false) && System.currentTimeMillis() - p.getLong("releve_ok", 0) < 24 * 3600_000L
    }

    // ------------------------------------------------------------------ Les actions

    fun libelle(ctx: Context, action: String): String = when {
        action == "parent" -> ctx.getString(R.string.dev_action_pause)
        action == "reglages" -> ctx.getString(R.string.dev_action_reglages)
        action.startsWith("app:") -> ctx.getString(R.string.dev_action_appli, Usage.libelle(ctx, action.removePrefix("app:")))
        else -> action
    }

    fun connue(action: String): Boolean =
        action == "parent" || action == "reglages" || (action.startsWith("app:") && Regex("[A-Za-z0-9._]{1,100}").matches(action.removePrefix("app:")))

    fun maxMinutes(action: String): Int = when (action) { "parent" -> 60; "reglages" -> 30; else -> 240 }

    // ------------------------------------------------------------------ 1. Demander

    class Echec(val raison: String) : Exception(raison)

    /** Envoie la demande (réseau : hors du fil principal). Une nouvelle demande remplace la précédente. */
    fun demander(ctx: Context, action: String, minutes: Int, motif: String): Demande {
        val cfg = Config(ctx)
        val octets = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val defi = Base64.encodeToString(octets, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val corps = JSONObject().put("id", cfg.id).put("secret", cfg.secret).put("action", action)
            .put("minutes", minutes).put("defi", defi)
        if (motif.isNotBlank()) corps.put("motif", motif.replace('\n', ' ').take(140))
        enCours = null
        val r = try { Net.post(ctx, cfg, "/api/pc_parental/deverrouillage/demande", corps) } catch (e: Net.Echec) {
            throw Echec(when (e.code) { 429 -> ctx.getString(R.string.dev_trop_de_demandes); else -> ctx.getString(R.string.lot6_err_demande_refusee) })
        } catch (_: Exception) { throw Echec(ctx.getString(R.string.dev_injoignable)) }
        r.optJSONObject("cle")?.let { poserCle(ctx, it) }
        val d = Demande(r.optString("demande"), action, minutes, defi, r.optLong("expire") * 1000L)
        if (d.id.isEmpty()) throw Echec(ctx.getString(R.string.dev_reponse_inattendue_ha))
        enCours = d
        return d
    }

    fun oublier() { enCours = null }

    /** L'état de la demande en cours (« en_attente », « approuvee »…), et l'approbation directe s'il y en a une. */
    fun etat(ctx: Context): JSONObject? {
        val d = enCours ?: return null
        val cfg = Config(ctx)
        return try {
            Net.post(ctx, cfg, "/api/pc_parental/deverrouillage/etat", JSONObject().put("id", cfg.id).put("secret", cfg.secret).put("demande", d.id))
        } catch (_: Exception) { null }
    }

    /** Le code à 6 chiffres lu par le parent. Renvoie l'approbation, ou lève Echec avec le message à montrer. */
    fun code(ctx: Context, code: String): JSONObject {
        val d = enCours ?: throw Echec(ctx.getString(R.string.dev_aucune_demande))
        val cfg = Config(ctx)
        try {
            val r = Net.post(ctx, cfg, "/api/pc_parental/deverrouillage/code",
                JSONObject().put("id", cfg.id).put("secret", cfg.secret).put("demande", d.id).put("code", code.filter { it.isDigit() }))
            return r.optJSONObject("approbation") ?: throw Echec(ctx.getString(R.string.dev_reponse_inattendue))
        } catch (e: Net.Echec) {
            throw Echec(when (e.code) {
                403 -> ctx.getString(R.string.garde_code_incorrect)
                423 -> { enCours = null; ctx.getString(R.string.dev_trop_d_essais) }
                404 -> ctx.getString(R.string.dev_code_perime)
                429 -> ctx.getString(R.string.dev_trop_de_codes)
                else -> ctx.getString(R.string.lot6_err_code_refuse)
            })
        } catch (e: Echec) { throw e } catch (_: Exception) {
            throw Echec(ctx.getString(R.string.dev_hors_ligne))
        }
    }

    // ------------------------------------------------------------------ 2. Vérifier (docs §4)

    /** Le QR d'un parent : `vision://deverrouillage?v=1&k=…&p=…&d=…&a=…&m=…&x=…&s=…`. */
    fun depuisQr(texte: String): JSONObject? {
        val u = try { Uri.parse(texte.trim()) } catch (_: Exception) { return null }
        if (!u.scheme.equals("vision", true) || !u.host.equals("deverrouillage", true)) return null
        if (u.getQueryParameter("v") != "1") throw Echec(Tr.s(R.string.dev_qr_trop_recent))
        return JSONObject().put("k", u.getQueryParameter("k") ?: "").put("appareil", u.getQueryParameter("p") ?: "")
            .put("demande", u.getQueryParameter("d") ?: "").put("action", u.getQueryParameter("a") ?: "")
            .put("minutes", (u.getQueryParameter("m") ?: "0").toIntOrNull() ?: 0)
            .put("expire", (u.getQueryParameter("x") ?: "0").toLongOrNull() ?: 0L)
            .put("signature", u.getQueryParameter("s") ?: "").put("remise", "qr").put("par", Tr.s(R.string.dev_un_gestionnaire, Vocabulaire.decideur()))
    }

    fun estQr(texte: String): Boolean = texte.trim().startsWith("vision://deverrouillage", ignoreCase = true)

    // ------------------------------------------------------------------ 2 bis. À l'initiative d'un responsable (1.93.06)

    /**
     * 1.93.06 (Déverrouillage simplifié) : un responsable déverrouille cet appareil depuis son mobile, la carte ou
     * un service de HA, sans demande d'ici. L'approbation arrive par le relevé, signée en version [VERSION_INITIATIVE]
     * (jamais confondue avec celle d'une demande, version 1), liée à CET appareil et à un identifiant « init-… » à usage
     * unique : il est retenu jusqu'à son expiration, une approbation rejouée est refusée.
     */
    const val VERSION_INITIATIVE = "2"
    const val DEFI_INITIATIVE = "initiative"
    val MINUTES_INITIATIVE = setOf(15, 30, 60)

    fun estInitiative(a: JSONObject?): Boolean = a != null && a.optString("demande").startsWith("init-")

    /** Pur : le message signé (8 lignes, sans retour final ; docs/deverrouillage.md §4). */
    fun message(version: String, appareil: String, demande: String, action: String, minutes: Int, expire: Long, defi: String): ByteArray =
        listOf("vision-deverrouillage", version, appareil, demande, action, minutes.toString(), expire.toString(), defi)
            .joinToString("\n").toByteArray(Charsets.UTF_8)

    private fun signatureBonne(pub: String, msg: ByteArray, signature: String): Boolean = try {
        val cle = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.decode(pub, Base64.DEFAULT)))
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(cle); update(msg)
            verify(Base64.decode(signature, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
        }
    } catch (_: Exception) { false }

    /** Les identifiants d'initiative déjà appliqués, avec leur expiration (secondes) : jamais deux fois le même. */
    private fun vus(ctx: Context): JSONObject = try { JSONObject(prefs(ctx).getString("init_vus", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }

    /** Vérifie une approbation à l'initiative d'un responsable ; null si elle est bonne, sinon pourquoi. */
    fun verifierInitiative(ctx: Context, a: JSONObject, maintenantS: Long = System.currentTimeMillis() / 1000): String? {
        val p = prefs(ctx)
        val k = p.getString("k", "") ?: ""; val pub = p.getString("pub", "") ?: ""
        if (k.isEmpty() || a.optString("k") != k) return ctx.getString(R.string.dev_cle_inconnue)
        val moi = Config(ctx).id
        if (a.optString("appareil") != moi) return ctx.getString(R.string.dev_autre_appareil)
        val id = a.optString("demande")
        if (!id.startsWith("init-") || vus(ctx).has(id)) return ctx.getString(R.string.dev_autre_demande)
        val minutes = a.optInt("minutes"); val expire = a.optLong("expire")
        val msg = message(VERSION_INITIATIVE, moi, id, a.optString("action"), minutes, expire, DEFI_INITIATIVE)
        if (!signatureBonne(pub, msg, a.optString("signature"))) return ctx.getString(R.string.dev_signature_refusee)
        if (maintenantS > expire) return ctx.getString(R.string.dev_expire)
        if (a.optString("action") != "parent" || minutes !in MINUTES_INITIATIVE) return ctx.getString(R.string.dev_action_inconnue)
        return null
    }

    /** Vérifie puis applique l'accès total ; renvoie le message à montrer. */
    fun accepterInitiative(ctx: Context, a: JSONObject): String {
        verifierInitiative(ctx, a)?.let { throw Echec(it) }
        val id = a.optString("demande")
        val o = vus(ctx)
        val maintenantS = System.currentTimeMillis() / 1000
        o.keys().asSequence().toList().forEach { if (o.optLong(it) < maintenantS) o.remove(it) }
        o.put(id, a.optLong("expire"))
        prefs(ctx).edit().putString("init_vus", o.toString()).commit()
        val maintenant = System.currentTimeMillis()
        appliquer(ctx, "parent", a.optInt("minutes"), maintenant)
        BandeauDeverrouille.maintenant()
        Log.i("VisionDeverrouillage", "accès total ${a.optInt("minutes")} min, à l'initiative de ${a.optString("par")}")
        consommerPlusTard(ctx, id, maintenant / 1000)
        Thread { envoyerConsommations(ctx) }.start()
        val qui = a.optString("par").ifEmpty { ctx.getString(R.string.dev_un_gestionnaire, Vocabulaire.decideur(ctx)) }
        return ctx.getString(R.string.dev_deverrouille_total, qui, a.optInt("minutes"))
    }

    // ------------------------------------------------------------------ 2 ter. Accès total, bandeau, reverrouiller

    /** 1.93.06 : l'accès total est-il ouvert (déverrouillage d'un responsable, ou pause par le code) ? */
    fun accesTotal(ctx: Context): Boolean = Config(ctx).modeParent

    /** Les minutes qui restent (arrondies au-dessus), 0 si verrouillé. */
    fun minutesRestantes(ctx: Context, maintenant: Long = System.currentTimeMillis()): Int {
        val fin = Config(ctx).parentJusqua
        return if (fin <= maintenant) 0 else (((fin - maintenant) + 59_999L) / 60_000L).toInt()
    }

    /** « Reverrouiller » (bandeau, réglages de Vision) et le redémarrage de l'appareil : tout se referme. */
    fun reverrouiller(ctx: Context, pourquoi: String) {
        val cfg = Config(ctx)
        val etait = cfg.modeParent || reglagesOuverts(ctx)
        cfg.parentJusqua = 0L
        prefs(ctx).edit().putLong("reglages_jusqua", 0L).apply()
        if (etait) Log.i("VisionDeverrouillage", "reverrouillé ($pourquoi)")
        BandeauDeverrouille.maintenant()
    }

    /** Vérifie l'approbation dans l'ordre du contrat ; renvoie null si elle est bonne, sinon pourquoi. */
    fun verifier(ctx: Context, a: JSONObject): String? {
        val p = prefs(ctx)
        val k = p.getString("k", "") ?: ""; val pub = p.getString("pub", "") ?: ""
        if (k.isEmpty() || a.optString("k") != k) return ctx.getString(R.string.dev_cle_inconnue)
        if (a.optString("appareil") != Config(ctx).id) return ctx.getString(R.string.dev_autre_appareil)
        val d = enCours
        if (d == null || a.optString("demande") != d.id) return ctx.getString(R.string.dev_autre_demande)
        val minutes = a.optInt("minutes"); val expire = a.optLong("expire")
        val msg = listOf("vision-deverrouillage", "1", Config(ctx).id, d.id, a.optString("action"), minutes.toString(), expire.toString(), d.defi)
            .joinToString("\n").toByteArray(Charsets.UTF_8)
        val ok = try {
            val cle = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.decode(pub, Base64.DEFAULT)))
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(cle); update(msg)
                verify(Base64.decode(a.optString("signature"), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
            }
        } catch (_: Exception) { false }
        if (!ok) return ctx.getString(R.string.dev_signature_refusee)
        if (System.currentTimeMillis() / 1000 > expire) return ctx.getString(R.string.dev_expire)
        if (!connue(a.optString("action")) || minutes < 1 || minutes > maxMinutes(a.optString("action"))) return ctx.getString(R.string.dev_action_inconnue)
        return null
    }

    /** Vérifie puis applique ; renvoie le message à montrer. */
    fun accepter(ctx: Context, a: JSONObject): String {
        verifier(ctx, a)?.let { throw Echec(it) }
        val d = enCours!!
        enCours = null // le défi est effacé : l'approbation ne resservira pas
        val maintenant = System.currentTimeMillis()
        appliquer(ctx, a.optString("action"), a.optInt("minutes"), maintenant)
        consommerPlusTard(ctx, d.id, maintenant / 1000)
        Thread { envoyerConsommations(ctx) }.start()
        val qui = a.optString("par").ifEmpty { ctx.getString(R.string.dev_un_gestionnaire, Vocabulaire.decideur(ctx)) }
        return ctx.getString(R.string.dev_deverrouille_par, qui, libelle(ctx, a.optString("action")), a.optInt("minutes"))
    }

    // ------------------------------------------------------------------ 3. Appliquer, borné

    private fun appliquer(ctx: Context, action: String, minutes: Int, debut: Long) {
        val fin = debut + minutes * 60_000L
        when {
            action == "parent" -> {
                val cfg = Config(ctx)
                cfg.parentJusqua = maxOf(cfg.parentJusqua, fin)
                // Le mode parent lève aussi les messages bloquants qui attendent un parent.
                MessagesRiches.leverParent(ctx, "deverrouillage")
            }
            // 1.93.06 (Déverrouillage simplifié) : déverrouillé par un responsable = accès TOTAL pour la durée.
            action == "reglages" -> {
                prefs(ctx).edit().putLong("reglages_jusqua", fin).apply()
                val cfg = Config(ctx)
                cfg.parentJusqua = maxOf(cfg.parentJusqua, fin)
            }
            action.startsWith("app:") -> {
                val o = permises(ctx); o.put(action.removePrefix("app:"), fin)
                prefs(ctx).edit().putString("apps", o.toString()).apply()
            }
        }
    }

    private fun permises(ctx: Context): JSONObject = try { JSONObject(prefs(ctx).getString("apps", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }

    /** Cette appli a-t-elle été ouverte par un parent, pour encore un moment ? */
    fun appPermise(ctx: Context, pkg: String): Boolean = permises(ctx).optLong(pkg) > System.currentTimeMillis()

    /** Les réglages protégés sont-ils ouverts par un parent ? */
    fun reglagesOuverts(ctx: Context): Boolean = prefs(ctx).getLong("reglages_jusqua", 0) > System.currentTimeMillis()

    /** 1.78.0 : un décideur vient de se ré-authentifier pour agir sur une appli : les réglages passent un instant. */
    fun ouvrirReglagesUnInstant(ctx: Context, ms: Long) {
        val fin = System.currentTimeMillis() + ms
        if (prefs(ctx).getLong("reglages_jusqua", 0) < fin) prefs(ctx).edit().putLong("reglages_jusqua", fin).apply()
    }

    // ------------------------------------------------------------------ 4. Consommer

    private fun consommerPlusTard(ctx: Context, demande: String, applique: Long) {
        val a = try { JSONArray(prefs(ctx).getString("a_consommer", "[]")) } catch (_: Exception) { JSONArray() }
        a.put(JSONObject().put("demande", demande).put("applique", applique))
        prefs(ctx).edit().putString("a_consommer", a.toString()).apply()
    }

    /** Avant chaque relevé (et tout de suite après un déverrouillage) : ce qui a été appliqué, pour le journal. */
    fun envoyerConsommations(ctx: Context) {
        val p = prefs(ctx)
        val a = try { JSONArray(p.getString("a_consommer", "[]")) } catch (_: Exception) { return }
        if (a.length() == 0) return
        val cfg = Config(ctx)
        val reste = JSONArray()
        for (i in 0 until a.length()) {
            val c = a.optJSONObject(i) ?: continue
            try {
                Net.post(ctx, cfg, "/api/pc_parental/deverrouillage/consommer",
                    JSONObject().put("id", cfg.id).put("secret", cfg.secret).put("demande", c.optString("demande")).put("applique", c.optLong("applique")))
            } catch (e: Net.Echec) {
                if (e.code == 404) try {
                    // Home Assistant a oublié la demande (redémarrage) : on dit quand même ce qui a été appliqué.
                    Net.post(ctx, cfg, "/api/pc_parental/deverrouillage/consommer",
                        JSONObject().put("id", cfg.id).put("secret", cfg.secret).put("demande", c.optString("demande"))
                            .put("applique", c.optLong("applique")).put("hors_ligne", true))
                } catch (_: Exception) {}
            } catch (_: Exception) {
                reste.put(c)
            }
        }
        p.edit().putString("a_consommer", reste.toString()).apply()
    }

    /** Approbation directe arrivée par le relevé. */
    fun directe(ctx: Context, a: JSONObject?): String? {
        a ?: return null
        // 1.93.06 : à l'initiative d'un responsable, sans demande d'ici.
        if (estInitiative(a)) return if (vus(ctx).has(a.optString("demande"))) null else try { accepterInitiative(ctx, a) } catch (e: Echec) { e.raison }
        val d = enCours ?: return null
        if (a.optString("demande") != d.id) return null
        return try { accepter(ctx, a) } catch (e: Echec) { e.raison }
    }

    @Suppress("unused")
    private fun empreinte(pub: String): String =
        MessageDigest.getInstance("SHA-256").digest(Base64.decode(pub, Base64.DEFAULT)).joinToString("") { "%02x".format(it) }.take(16)
}
