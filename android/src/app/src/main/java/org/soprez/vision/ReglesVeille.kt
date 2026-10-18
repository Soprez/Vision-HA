// SPDX-License-Identifier: MIT
package org.soprez.vision

/**
 * Règles pures (testées) de la veille sur télé quand l'économiseur d'écran du système
 * n'est pas Vision (TCL, Google TV masquent souvent ce choix).
 */
object ReglesVeille {
    /** Le composant du rêve de Vision, tel qu'Android l'écrit dans « screensaver_components ». */
    const val COMPOSANT_COURT = "org.soprez.vision/.VeilleService"
    const val COMPOSANT_LONG = "org.soprez.vision/org.soprez.vision.VeilleService"

    /** Délais proposés pour la veille de repli sur l'accueil (minutes ; 0 = jamais). Défaut : 10. */
    val DELAIS_REPLI = listOf(5, 10, 15, 30, 60, 0)
    const val REPLI_DEFAUT = 10

    /**
     * Vrai si la valeur de Settings.Secure « screensaver_components » (liste séparée par des virgules)
     * désigne la veille de Vision ; faux si c'est une autre ; null si la valeur est illisible.
     */
    fun economiseurEstVision(composants: String?, lisible: Boolean = true): Boolean? {
        if (!lisible) return null
        if (composants.isNullOrBlank()) return false
        return composants.split(',').map { it.trim() }.any { it.equals(COMPOSANT_COURT, true) || it.equals(COMPOSANT_LONG, true) }
    }

    /**
     * Minutes sans touche sur l'accueil de la télé avant que Vision lance sa veille, 0 = jamais.
     * Le délai choisi dans Vision l'emporte ; sinon, si l'économiseur du système n'est pas Vision
     * (ou illisible), le repli s'applique ; si c'est Vision, le système s'en charge.
     */
    fun delaiAccueil(delaiChoisi: Int, economiseurVision: Boolean?, delaiRepli: Int): Int = when {
        delaiChoisi > 0 -> delaiChoisi
        economiseurVision == true -> 0
        else -> delaiRepli.coerceAtLeast(0)
    }

    /** Le délai suivant dans la liste (pour un réglage qu'on fait tourner à la télécommande). */
    fun repliSuivant(actuel: Int): Int {
        val i = DELAIS_REPLI.indexOf(actuel)
        return DELAIS_REPLI[(if (i < 0) 0 else i + 1) % DELAIS_REPLI.size]
    }

    fun libelleRepli(min: Int): String = if (min <= 0) Tr.s(R.string.veille_repli_jamais) else Tr.s(R.string.veille_repli_apres, min)

    // ------------------------------------------------------------------ Veille-lot (point 5) : mettre la veille en place

    /**
     * Les accueils qui cachent le choix de l'économiseur : sur Google TV (Chromecast avec Google TV, TCL, Sony…),
     * Réglages → Système → Mode Ambiant ne propose que l'ambiant de Google, même si Vision est installé. Ce sont des
     * indices sur la plateforme, pas sur la maison : une télé qui n'en a aucun garde le chemin des réglages.
     */
    val ACCUEILS_SANS_CHOIX = listOf("com.google.android.apps.tv.launcherx", "com.google.android.apps.tv.dreamx")

    /** Ce que la télé dit d'elle-même (un fait inconnu vaut null : on reste prudent). */
    data class Faits(
        /** L'économiseur choisi (« screensaver_components », ou à défaut celui par défaut) est Vision ; null : illisible. */
        val economiseurVision: Boolean?,
        /** « screensaver_enabled » : la télé lance-t-elle un économiseur ? null : inconnu (on suppose oui). */
        val reveActif: Boolean?,
        /** Vision a reçu WRITE_SECURE_SETTINGS (une seule commande ADB) : il règle l'économiseur lui-même. */
        val ecritureDirecte: Boolean,
        /** Le réglage de l'économiseur s'ouvre sur cette télé (ACTION_DREAM_SETTINGS). */
        val reglageSysteme: Boolean,
        /** La plateforme masque la liste des économiseurs (Google TV) : le réglage n'y proposera pas Vision. */
        val choixMasque: Boolean,
        /** L'accessibilité de Vision est en marche : l'inactivité se mesure partout, pas seulement sur l'accueil de Vision. */
        val surveillanceGlobale: Boolean,
        /** Vision est (ou prend) l'accueil de la télé. */
        val accueilVision: Boolean,
    )

    /** Comment faire de Vision l'économiseur. */
    enum class Chemin {
        /** Rien à faire : la télé lance déjà la veille de Vision. */
        DEJA_VISION,
        /** Vision a le droit d'écrire le réglage : un appui suffit. */
        ECRIRE,
        /** Le réglage de la télé propose Vision : l'ouvrir et guider à la télécommande. */
        REGLAGE,
        /** Seule une commande ADB le peut sur ce modèle : l'afficher telle quelle pour le Décideur. */
        ADB,
    }

    /** Le filet tant que l'économiseur n'est pas Vision : où la veille de Vision vient d'elle-même après inactivité. */
    enum class Repli {
        /** L'économiseur est Vision : pas de repli. */
        INUTILE,
        /** Sur l'accueil de la télé, quel qu'il soit (Vision ou celui du fabricant), jamais devant une autre appli. */
        ACCUEILS,
        /** Seulement sur l'accueil de Vision (sans accessibilité, Vision ne voit pas les touches ailleurs). */
        ACCUEIL_VISION,
        /** Aucun : ni accessibilité, ni accueil Vision. Il faut l'un des deux (l'écran le dit). */
        AUCUN,
    }

    data class Plan(val chemin: Chemin, val repli: Repli)

    /** Le choix, sans Android (testé) : le chemin le plus simple qui marche sur CETTE télé, et le repli qui tient d'ici là. */
    fun plan(f: Faits): Plan {
        val dejaVision = f.economiseurVision == true && f.reveActif != false
        val chemin = when {
            dejaVision -> Chemin.DEJA_VISION
            f.ecritureDirecte -> Chemin.ECRIRE
            f.reglageSysteme && !f.choixMasque -> Chemin.REGLAGE
            else -> Chemin.ADB
        }
        val repli = when {
            dejaVision -> Repli.INUTILE
            f.surveillanceGlobale -> Repli.ACCUEILS
            f.accueilVision -> Repli.ACCUEIL_VISION
            else -> Repli.AUCUN
        }
        return Plan(chemin, repli)
    }

    /** Lit « screensaver_components » (ou, vide, « screensaver_default_component ») : est-ce Vision ? */
    fun economiseurEffectifEstVision(composants: String?, parDefaut: String?, lisible: Boolean = true): Boolean? {
        if (!lisible) return null
        return if (composants.isNullOrBlank()) economiseurEstVision(parDefaut) else economiseurEstVision(composants)
    }

    /**
     * Les commandes ADB à taper depuis un ordinateur, telles quelles (affichées à l'écran pour le Décideur).
     * La première donne à Vision le droit de régler l'économiseur lui-même (et de le remettre après une mise à jour) ;
     * les deux suivantes le règlent directement.
     */
    fun commandesAdb(paquet: String, composant: String): List<String> = listOf(
        "adb shell pm grant $paquet android.permission.WRITE_SECURE_SETTINGS",  // i18n-exception : commande ADB, tapée telle quelle
        "adb shell settings put secure screensaver_components $composant",  // i18n-exception : commande ADB, tapée telle quelle
        "adb shell settings put secure screensaver_enabled 1",  // i18n-exception : commande ADB, tapée telle quelle
    )

    /** Le délai de repli qui s'applique hors de l'accueil de Vision (accueil du fabricant), 0 = jamais. */
    fun delaiAccueilsTiers(delaiChoisi: Int, plan: Plan, delaiRepli: Int): Int = when {
        plan.repli != Repli.ACCUEILS -> 0
        delaiChoisi > 0 -> delaiChoisi
        else -> delaiRepli.coerceAtLeast(0)
    }
}
