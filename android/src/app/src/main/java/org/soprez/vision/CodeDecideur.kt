// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/**
 * Décision de l'auteur (16-10) : UN SEUL code de déverrouillage par Décideur — « un superviseur est un superviseur ».
 * Home Assistant en garde l'empreinte (par personne, octroi.py) ; l'appli ET le tableau la vérifient. L'empreinte
 * digitale reste une alternative locale (Biometrie). L'empreinte locale (Config, « pin ») reste une copie de secours
 * hors ligne, remise à jour à chaque code juste.
 *
 * Migration : un Décideur qui avait déjà un code dans l'appli l'envoie (son empreinte) à HA au premier relevé, sans
 * rien lui redemander ; HA la convertit en PBKDF2 la première fois que le code est tapé. Sans code nulle part, il le
 * choisit une fois. Les appareils des Demandeurs gardent leur code local (mode gestion sur place).
 */
object CodeDecideur {
    private const val PREFS = "garde"
    private const val CLE_HA = "code_ha" // "oui" | "non" | absent (HA ne l'a pas dit : appareil non Décideur, ancien HA)
    private val principal = Handler(Looper.getMainLooper())

    /** L'appareil d'un Décideur (son rôle gère ou approuve), relié à Home Assistant. */
    fun estDecideur(ctx: Context): Boolean = Config(ctx).inscrit && Profil.fonctions(ctx).let { "gerer" in it || "approuver" in it }

    /** HA a-t-il le code de ce Décideur ? null : HA ne l'a pas dit. */
    fun codeHa(ctx: Context): Boolean? = when (ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CLE_HA, null)) {
        "oui" -> true
        "non" -> false
        else -> null
    }

    /** Au relevé : ce que HA dit du code ; et la migration de l'empreinte locale, si HA n'a encore rien. */
    fun releve(ctx: Context, r: JSONObject) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (!r.has("code_deverrouillage") || r.isNull("code_deverrouillage")) { p.remove(CLE_HA).apply(); return }
        val pose = r.optBoolean("code_deverrouillage")
        p.putString(CLE_HA, if (pose) "oui" else "non").apply()
        val cfg = Config(ctx)
        if (!pose && cfg.aUnCode && estDecideur(ctx)) Thread { envoyer(ctx, JSONObject().put("op", "migrer").put("empreinte", cfg.empreinteLocale())) }.start()
    }

    /** Le résultat d'une vérification ou d'un changement : juste, ou la phrase à dire. */
    class Resultat(val juste: Boolean, val message: String = "")

    /**
     * Le code tapé est-il le bon ? Décideur : Home Assistant décide (essais faux limités là-bas) ; injoignable : la
     * copie locale. Un code juste remet la copie locale à jour. Demandeur : le code local, comme avant.
     */
    fun verifier(act: Activity, code: String, fini: (Resultat) -> Unit) {
        val cfg = Config(act)
        if (!estDecideur(act)) { fini(if (cfg.verifierCode(code)) Resultat(true) else Resultat(false, act.getString(R.string.garde_code_incorrect))); return }
        Thread {
            val r = try { Reponse.de(envoyer(act, JSONObject().put("op", "verifier").put("code", code))) } catch (e: Net.Echec) { Reponse.de(e) } catch (_: Exception) { null }
            val res = when {
                r == null -> if (cfg.verifierCode(code)) Resultat(true) else Resultat(false, act.getString(R.string.garde_code_incorrect)) // hors ligne
                r.ok -> { cfg.definirCode(code); Resultat(true) }
                r.erreur == "code_octroi_absent" -> {
                    // HA n'a pas encore de code pour ce Décideur : le code local juste devient celui de HA.
                    if (cfg.verifierCode(code)) { envoyerSansAttendre(act, JSONObject().put("op", "definir").put("code", code)); Resultat(true) }
                    else Resultat(false, act.getString(R.string.garde_code_incorrect))
                }
                r.erreur == "code_octroi_suspendu" -> Resultat(false, act.getString(R.string.garde_code_suspendu))
                r.erreur == "code_octroi_verrouille" -> Resultat(false, act.getString(R.string.garde_code_verrouille))
                else -> Resultat(false, act.getString(R.string.garde_code_incorrect))
            }
            principal.post { fini(res) }
        }.start()
    }

    /** Un code neuf : dans HA pour un Décideur (l'ancien, s'il y en a un), puis la copie locale. */
    fun definir(act: Activity, code: String, ancien: String?, fini: (Resultat) -> Unit) {
        val cfg = Config(act)
        if (!estDecideur(act)) { cfg.definirCode(code); fini(Resultat(true)); return }
        Thread {
            val corps = JSONObject().put("op", "definir").put("code", code).apply { if (!ancien.isNullOrEmpty()) put("ancien", ancien) }
            val r = try { Reponse.de(envoyer(act, corps)) } catch (e: Net.Echec) { Reponse.de(e) } catch (_: Exception) { null }
            val res = when {
                r == null -> Resultat(false, act.getString(R.string.garde_code_ha_injoignable))
                r.ok -> { cfg.definirCode(code); act.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(CLE_HA, "oui").apply(); Resultat(true) }
                r.erreur == "code_octroi_faux" || r.erreur == "code_octroi" -> Resultat(false, act.getString(R.string.garde_code_incorrect))
                r.erreur == "code_octroi_suspendu" -> Resultat(false, act.getString(R.string.garde_code_suspendu))
                r.erreur == "code_octroi_verrouille" -> Resultat(false, act.getString(R.string.garde_code_verrouille))
                else -> Resultat(false, act.getString(R.string.garde_code_ha_injoignable))
            }
            principal.post { fini(res) }
        }.start()
    }

    private class Reponse(val ok: Boolean, val erreur: String) {
        companion object {
            fun de(j: JSONObject) = Reponse(j.optBoolean("ok"), j.optString("error"))
            fun de(e: Net.Echec) = Reponse(false, e.corps?.optString("error").orEmpty().ifEmpty { e.message.orEmpty() })
        }
    }

    private fun envoyer(ctx: Context, corps: JSONObject): JSONObject {
        val cfg = Config(ctx)
        return Net.post(ctx, cfg, "/api/pc_parental/parent", corps.put("id", cfg.id).put("secret", cfg.secret).put("action", "code_deverrouillage"))
    }

    private fun envoyerSansAttendre(ctx: Context, corps: JSONObject) = Thread { try { envoyer(ctx, corps) } catch (_: Exception) {} }.start()
}
