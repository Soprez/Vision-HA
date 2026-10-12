// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import java.text.Normalizer

/**
 * Les catégories sont des étiquettes de Home Assistant, enregistrées avec leur nom français (« Jeux »,
 * « Réseaux sociaux ») : les données ne changent jamais. Seul l'affichage les traduit, par leur clé
 * (même règle que `pc_parental/langues.py` : sans accents ni casse, `_` pour le reste). Une étiquette
 * inconnue de la table s'affiche telle qu'elle a été écrite.
 */
object Categories {
    private val TABLE = mapOf(
        "jeux" to R.string.categorie_jeux,
        "videos" to R.string.categorie_videos,
        "reseaux_sociaux" to R.string.categorie_reseaux_sociaux,
        "tv" to R.string.categorie_tv,
        "vod" to R.string.categorie_vod,
        "musique" to R.string.categorie_musique,
        "ia" to R.string.categorie_ia,
        "internet" to R.string.categorie_internet,
        "travail" to R.string.categorie_travail,
        "creation" to R.string.categorie_creation,
        "actu_medias" to R.string.categorie_actu_medias,
        "pub_tracage" to R.string.categorie_pub_tracage,
        "contournement" to R.string.categorie_contournement,
        "dangereux" to R.string.categorie_dangereux,
        "adulte" to R.string.categorie_adulte,
        "pegi_12" to R.string.categorie_pegi_12,
        "pegi_16" to R.string.categorie_pegi_16,
        "pegi_18" to R.string.categorie_pegi_18,
        "systeme" to R.string.categorie_systeme,
        "google" to R.string.categorie_google,
        "discord" to R.string.categorie_discord,
        "annuaires_discord" to R.string.categorie_annuaires_discord,
        "toujours_autorise" to R.string.categorie_toujours_autorise,
    )

    /** « Réseaux sociaux » → « reseaux_sociaux » ; « Pub & traçage » → « pub_tracage ». */
    fun cle(nom: String): String =
        Normalizer.normalize(nom, Normalizer.Form.NFKD).replace(Regex("[^\\p{ASCII}]"), "")
            .lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

    /** Le nom affiché d'une catégorie, dans la langue de l'appareil ; inconnue : telle quelle. */
    fun libelle(context: Context, nom: String): String =
        TABLE[cle(nom)]?.let { context.getString(it) } ?: nom

    /** Même chose sans contexte (logique pure, notifications). */
    fun libelle(nom: String): String = TABLE[cle(nom)]?.let { Tr.s(it) }?.ifEmpty { null } ?: nom
}
