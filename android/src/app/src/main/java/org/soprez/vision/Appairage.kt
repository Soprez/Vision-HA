// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import org.json.JSONObject

/**
 * Ce que les deux sens de l'appairage par QR ont en commun (docs/appairage.md, côté intégration) :
 * - l'appareil affiche un QR, un parent le valide (AppairageActivity, Vision 1.62.0) ;
 * - Home Assistant affiche un QR, l'appareil le scanne (ScanActivity, Vision 1.65.0).
 * Dans les deux cas l'appareil reçoit son identifiant et son secret, et les range exactement
 * comme après une inscription par clé. Le jeton et la demande ne sont jamais écrits ni journalisés.
 */
object Appairage {
    /** Un QR d'appairage de Home Assistant, lu. */
    data class Invitation(val adresse: String, val locale: String, val jeton: String)

    /** Ce que le texte scanné veut dire. */
    sealed class Lecture {
        class Valide(val invitation: Invitation) : Lecture()
        object PasUnQrVision : Lecture()
        object VersionInconnue : Lecture()
        object Incomplet : Lecture()
    }

    /** `vision://appairage?v=1&adresse=…&locale=…&jeton=…` ; tout autre QR n'en est pas un. */
    fun lire(texte: String): Lecture {
        val u = try { Uri.parse(texte.trim()) } catch (_: Exception) { return Lecture.PasUnQrVision }
        if (!u.scheme.equals("vision", ignoreCase = true) || !u.host.equals("appairage", ignoreCase = true)) return Lecture.PasUnQrVision
        if (u.getQueryParameter("v") != "1") return Lecture.VersionInconnue
        val adresse = propre(u.getQueryParameter("adresse"))
        val jeton = (u.getQueryParameter("jeton") ?: "").trim()
        if (adresse.isEmpty() || jeton.isEmpty()) return Lecture.Incomplet
        return Lecture.Valide(Invitation(adresse, propre(u.getQueryParameter("locale")), jeton))
    }

    private fun propre(a: String?): String {
        val s = (a ?: "").trim().trimEnd('/')
        return if (s.startsWith("http://", true) || s.startsWith("https://", true)) s else ""
    }

    /** Cet appareil a-t-il une caméra (une télé, en général, non) ? */
    fun aUneCamera(ctx: Context): Boolean =
        ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    /**
     * Range l'accès reçu de Home Assistant, comme après une inscription par clé, et démarre l'agent.
     * `adresse` : celle à retenir (externe si possible) ; `locale` : celle du réseau de la maison.
     */
    fun enregistrer(ctx: Context, adresse: String, locale: String, r: JSONObject): Boolean {
        val id = r.optString("id"); val secret = r.optString("secret")
        if (id.isEmpty() || secret.isEmpty()) return false
        val cfg = Config(ctx)
        cfg.id = id
        cfg.secret = secret
        cfg.nom = r.optString("name")
        cfg.host = Identite.appareil()
        when {
            locale.isNotEmpty() -> { cfg.urlInterne = locale; cfg.urlExterne = adresse }
            Decouverte.estLocale(adresse) -> { cfg.urlInterne = adresse; if (cfg.urlExterne.isEmpty()) cfg.urlExterne = adresse }
            else -> cfg.urlExterne = adresse
        }
        cfg.cleInscription = ""
        cfg.sansCle = false
        AgentService.demarrer(ctx)
        return true
    }

    /** Le corps commun aux deux sens : qui est cet appareil. */
    fun identite(ctx: Context): JSONObject = JSONObject().apply {
        put("host", Identite.appareil())
        Identite.nom(ctx).takeIf { it.isNotEmpty() }?.let { put("nom", it) }
        put("uid", Identite.uid(ctx))
        put("platform", "android")
        // 1.90.4 : une télé est d'abord un écran partagé (le {décideur} le voit proposé par défaut).
        put("genre", if (ReglagesTvActivity.estTele(ctx)) "tele" else "telephone")
    }
}
