// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 1.90.0 (l'auteur : « accéder directement à la carte ou au dashboard ») : ouvrir une page de Home Assistant depuis un widget.
 * L'appli Compagnon de Home Assistant si elle est installée (`homeassistant://navigate/<chemin>`) ; sinon le navigateur
 * du téléphone, sur l'adresse de Home Assistant que Vision utilise déjà (la dernière qui a répondu : l'interne à la
 * maison, l'externe dehors). Pas de vue web dans Vision. Le chemin est toujours une page de Home Assistant
 * ([WidgetsHa.cheminSur]) ; Home Assistant a déjà vérifié que cet appareil peut l'ouvrir.
 */
object OuvrirHa {
    /** Les détails d'une entité (more-info), par-dessus le tableau de bord principal. */
    fun cheminDetails(entite: String): String = "/lovelace?more-info-entity-id=" + java.net.URLEncoder.encode(entite, "UTF-8")

    /** L'adresse web de la page : la première adresse de Home Assistant connue de Vision ([Config.adresses] : la dernière
     *  qui a répondu, puis l'interne, puis l'externe), suivie du chemin ; null sans adresse. */
    fun adresseWeb(adresses: List<String>, chemin: String): String? {
        val c = WidgetsHa.cheminSur(chemin) ?: return null
        val base = adresses.firstOrNull()?.trimEnd('/')?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return null
        return base + c
    }

    fun ouvrir(ctx: Context, chemin: String) {
        val c = WidgetsHa.cheminSur(chemin) ?: return
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("homeassistant://navigate$c")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: Exception) {
            // Pas d'appli Compagnon : le navigateur du téléphone.
        }
        val web = adresseWeb(Config(ctx).adresses(), c) ?: return
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(web)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }
}
