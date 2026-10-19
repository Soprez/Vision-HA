// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import org.json.JSONObject

/**
 * 1.93.05 (l'auteur, retour 36) : ce que l'écran de veille de CET appareil montre en plus de ses pages, réglé sur la télé
 * (Réglages › Écran de veille) ou depuis Home Assistant (service pc_parental.veille_regler, bloc « veille_options » du
 * relevé) :
 * - [lecteur] : le bandeau « Lecteur musique » (pochette, titre, artiste, lecture/pause), seulement pendant la lecture
 *   sur cet appareil ; non par défaut.
 * - 1.93.05 (retour 39) [qr] : le QR code des invités (mode soirée ouvert) dans un coin, en bas à droite par défaut, ou
 *   masqué ; [logo] : l'œil de Vision dans un coin (en bas à gauche, sa place d'avant), ou masqué. Deux éléments ne
 *   partagent jamais un coin : le choix qui en prendrait un déjà pris est refusé ([poserQr], [poserLogo]) ; des
 *   valeurs en conflit (anciennes) se résolvent par [effectifs] (le logo garde sa place, le QR prend un coin libre).
 * - 1.93.06 (retour d'essai) [qrTaille] : la taille du QR des invités, sur la veille et sur le lecteur ([QrInvites] : petit par
 *   défaut, moyen, grand).
 * Le dernier qui règle gagne : un réglage venu de Home Assistant s'applique quand il est plus récent que le dernier reçu
 * (« depuis ») ; un choix fait ensuite sur la télé reste jusqu'au prochain réglage de Home Assistant.
 */
object OptionsVeille {
    private const val PREFS = "veille_options"
    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    const val HG = "haut_gauche"
    const val HD = "haut_droite"
    const val BG = "bas_gauche"
    const val BD = "bas_droite"
    const val CACHE = "cache"
    val COINS = listOf(HG, HD, BG, BD)
    const val QR_DEFAUT = BD
    const val LOGO_DEFAUT = BG

    fun lecteur(ctx: Context): Boolean = p(ctx).getBoolean("lecteur", false)

    private fun valide(c: String?): Boolean = c != null && (c in COINS || c == CACHE)
    private fun qrBrut(ctx: Context): String = p(ctx).getString("qr", null)?.takeIf { valide(it) } ?: QR_DEFAUT
    private fun logoBrut(ctx: Context): String = p(ctx).getString("logo", null)?.takeIf { valide(it) } ?: LOGO_DEFAUT

    /** Le coin du QR et celui du logo, sans conflit. */
    fun qr(ctx: Context): String = effectifs(qrBrut(ctx), logoBrut(ctx)).first
    fun logo(ctx: Context): String = effectifs(qrBrut(ctx), logoBrut(ctx)).second

    /** Deux éléments dans le même coin : le logo garde sa place, le QR prend le premier coin libre. Pur. */
    fun effectifs(qr: String, logo: String): Pair<String, String> =
        if (qr == CACHE || qr != logo) qr to logo else (listOf(BD, BG, HD, HG).first { it != logo } to logo)

    /** Le coin [coin] est-il libre pour le QR (le logo ne l'occupe pas) ? Masqué : toujours. Pur. */
    fun libre(coin: String, autre: String): Boolean = coin == CACHE || coin != autre

    /** Pose le coin du QR ; faux (rien ne change) si le logo l'occupe déjà. */
    fun poserQr(ctx: Context, coin: String): Boolean {
        if (!valide(coin) || !libre(coin, logo(ctx))) return false
        p(ctx).edit().putString("qr", coin).apply(); Local.toucher(); return true
    }

    /** Pose le coin du logo ; faux (rien ne change) si le QR l'occupe déjà. */
    fun poserLogo(ctx: Context, coin: String): Boolean {
        if (!valide(coin) || !libre(coin, qr(ctx))) return false
        p(ctx).edit().putString("logo", coin).apply(); Local.toucher(); return true
    }
    fun poserLecteur(ctx: Context, oui: Boolean) { p(ctx).edit().putBoolean("lecteur", oui).apply(); Local.toucher() }

    /** 1.93.06 : la taille du QR des invités sur cet appareil (veille et lecteur). */
    fun qrTaille(ctx: Context): String = p(ctx).getString("qr_taille", null)?.takeIf { QrInvites.valide(it) } ?: QrInvites.DEFAUT
    fun poserQrTaille(ctx: Context, taille: String): Boolean {
        if (!QrInvites.valide(taille)) return false
        p(ctx).edit().putString("qr_taille", taille).apply(); Local.toucher(); return true
    }

    /** Le bloc « veille_options » du relevé ; rend vrai si un réglage a changé. Bloc absent (HA plus ancien) : rien. */
    fun releve(ctx: Context, o: JSONObject?): Boolean {
        if (o == null) return false
        val depuis = o.optLong("depuis", 0L)
        if (depuis <= 0L || depuis <= p(ctx).getLong("depuis_ha", 0L)) return false
        val e = p(ctx).edit().putLong("depuis_ha", depuis)
        if (o.has("lecteur")) e.putBoolean("lecteur", o.optBoolean("lecteur", false))
        o.optString("qr_taille").takeIf { QrInvites.valide(it) }?.let { e.putString("qr_taille", it) }
        // Home Assistant refuse déjà deux éléments dans un coin ; par prudence, un conflit garde le logo à sa place.
        val qr = o.optString("qr").takeIf { valide(it) }
        val logo = o.optString("logo").takeIf { valide(it) }
        if (qr != null || logo != null) {
            val (q, l) = effectifs(qr ?: qrBrut(ctx), logo ?: logoBrut(ctx))
            e.putString("qr", q).putString("logo", l)
        }
        e.apply()
        Local.toucher()
        return true
    }
}
