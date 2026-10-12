// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context

/**
 * Le vocabulaire neutre de Vision (docs/i18n/vocabulaire.md) : jamais « maison », « parent », « enfant » ;
 * on dit le rôle (Décideur, Demandeur) et le nom du lieu. Ces mots ne s'écrivent jamais en dur dans une
 * phrase : les ressources ont un champ positionnel (%1$s…) rempli par ces fonctions, seul endroit où ils sont
 * choisis.
 *
 * Défauts par langue : ressources `vocab_decideur`, `vocab_decideurs`, `vocab_demandeur`, `vocab_demandeurs`
 * (mêmes valeurs que `vocabulaire.DEFAUTS` côté Home Assistant). Le lieu : le champ « lieu » du relevé
 * (Config.lieu) ; rien reçu : « Lieu », « Place »… (ressource lieu_defaut).
 */
object Vocabulaire {
    /**
     * Le point d'injection d'un futur réglage « Vocabulaire » reçu de Home Assistant : champ → mot
     * (« decideur », « decideurs », « demandeur », « demandeurs »), dans la langue de l'appareil. Vide : les défauts.
     */
    @Volatile var surcharge: Map<String, String> = emptyMap()

    private fun mot(champ: String, id: Int): String = surcharge[champ]?.trim()?.ifEmpty { null } ?: Tr.s(id)

    /** Minuscule, singulier (« décideur ») ; l'allemand garde sa majuscule de nom (« Entscheider »). */
    fun decideur(): String = mot("decideur", R.string.vocab_decideur)
    fun decideurs(): String = mot("decideurs", R.string.vocab_decideurs)
    fun demandeur(): String = mot("demandeur", R.string.vocab_demandeur)
    fun demandeurs(): String = mot("demandeurs", R.string.vocab_demandeurs)

    /** Majuscule initiale, pour un début de phrase ou un titre (« Décideurs »). */
    fun majuscule(mot: String): String = mot.replaceFirstChar { it.titlecase(Tr.locale()) }
    fun Decideur(): String = majuscule(decideur())
    fun Decideurs(): String = majuscule(decideurs())
    fun Demandeur(): String = majuscule(demandeur())
    fun Demandeurs(): String = majuscule(demandeurs())

    /** Avec un contexte (écrans) : mêmes valeurs. */
    fun decideur(ctx: Context): String = surcharge["decideur"]?.trim()?.ifEmpty { null } ?: ctx.getString(R.string.vocab_decideur)
    fun decideurs(ctx: Context): String = surcharge["decideurs"]?.trim()?.ifEmpty { null } ?: ctx.getString(R.string.vocab_decideurs)
    fun demandeur(ctx: Context): String = surcharge["demandeur"]?.trim()?.ifEmpty { null } ?: ctx.getString(R.string.vocab_demandeur)
    fun demandeurs(ctx: Context): String = surcharge["demandeurs"]?.trim()?.ifEmpty { null } ?: ctx.getString(R.string.vocab_demandeurs)

    /** Les champs que l'appli substitue (le lieu a son propre champ, « lieu », dans le relevé). */
    val CHAMPS = listOf("decideur", "decideurs", "demandeur", "demandeurs")

    /**
     * Lot 1 (A6) : le champ « vocabulaire » du relevé (Home Assistant, Configurer → Vocabulaire, déjà dans la langue de
     * l'appareil) : champ → mot ; les clés en majuscule initiale sont ignorées (l'appli met elle-même la majuscule).
     * Gardé dans les réglages pour l'écran suivant et le prochain démarrage. Rien reçu (ancien serveur) : rien ne change.
     */
    fun recevoir(ctx: Context, v: org.json.JSONObject?) {
        if (v == null) return
        val mots = lire(v)
        if (mots == surcharge) return
        surcharge = mots
        ctx.getSharedPreferences("vision", Context.MODE_PRIVATE).edit().putString("vocabulaire", org.json.JSONObject(mots as Map<*, *>).toString()).apply()
    }

    /** Les mots gardés lors du dernier relevé (au démarrage de l'appli). */
    fun charger(ctx: Context) {
        val brut = ctx.getSharedPreferences("vision", Context.MODE_PRIVATE).getString("vocabulaire", "") ?: ""
        if (brut.isNotEmpty()) try { surcharge = lire(org.json.JSONObject(brut)) } catch (_: Exception) {}
    }

    /** Ne garde que les champs connus, nettoyés et bornés (30 caractères, comme côté Home Assistant). */
    fun lire(v: org.json.JSONObject): Map<String, String> = filtrer(CHAMPS.associateWith { v.opt(it) })

    /** Sans Android (essais JVM) : champ → valeur reçue ; seuls les champs connus, en texte non vide, 30 caractères au plus. */
    fun filtrer(m: Map<String, Any?>): Map<String, String> =
        CHAMPS.mapNotNull { c -> (m[c] as? String)?.trim()?.take(30)?.takeIf { it.isNotEmpty() }?.let { c to it } }.toMap()

    /** Le nom du lieu, tel que Home Assistant l'envoie dans le relevé (s'emploie seul, comme un titre). */
    fun lieu(ctx: Context): String = Config(ctx).lieu
}
