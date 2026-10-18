// SPDX-License-Identifier: MIT
package org.soprez.vision

/**
 * 1.91.0 (l'auteur : « pouvoir choisir le moment de la musique, avancer et reculer avec un petit bouton qu'on fait glisser
 * dans la barre ») : la logique du saut dans le titre, sans Android (essayée en JUnit, SautTest).
 *
 * - Téléphone et tablette : le curseur se glisse au doigt ; l'aperçu du temps suit le doigt ; le saut part au lâcher.
 * - Télé : la barre prend le focus ; gauche / droite avancent ou reculent de [PAS_S] s, l'appui long accélère
 *   ([pas]) ; le saut part quand la touche se calme ([CALME_MS]) ou à OK.
 * - Home Assistant demande `media_seek` au lecteur de Music Assistant (action « position », pc_parental/party.py),
 *   pour le lecteur Sendspin comme pour le Classique. L'écran montre tout de suite la position voulue ([attendue]),
 *   jusqu'à ce que le lecteur la confirme ou [TENIR_MS] au plus.
 */
object Saut {
    const val PAS_S = 10.0
    /** Le saut part quand la télécommande se tait depuis ce délai (plusieurs appuis courts = un seul saut). */
    const val CALME_MS = 650L
    /** La position voulue tient à l'écran au plus ce temps, sans confirmation du lecteur. */
    const val TENIR_MS = 8_000L
    /** Écart toléré entre la position voulue et celle que le lecteur annonce, pour dire « c'est fait ». */
    const val ECART_S = 3.0

    /** Le dernier saut annoncé par Home Assistant : sa position et son âge (s). */
    data class Info(val position: Double, val ilYa: Double)

    /** Le pas d'une touche gauche / droite : 10 s, puis 30 s, puis 60 s quand la touche reste enfoncée. */
    fun pas(repetitions: Int): Double = when {
        repetitions < 6 -> PAS_S
        repetitions < 16 -> 30.0
        else -> 60.0
    }

    /** Une position dans le titre : jamais avant le début, jamais sur la toute fin (le titre suivant partirait). */
    fun borner(p: Double, duree: Double): Double = if (duree <= 0) 0.0 else p.coerceIn(0.0, maxOf(0.0, duree - 1.0))

    /** La position sous le doigt : [part] de la barre (0 à 1). */
    fun depuisPart(part: Float, duree: Double): Double = borner(part.coerceIn(0f, 1f) * duree, duree)

    fun part(p: Double?, duree: Double?): Float =
        if (p == null || duree == null || duree <= 0) 0f else (p / duree).toFloat().coerceIn(0f, 1f)

    /**
     * La géométrie de la barre (l'auteur : la fin de la barre est la fin du titre) : la piste, la part jouée, le curseur et le
     * doigt partagent la même course, de [marge] à largeur − [marge]. À la part 1, le curseur est au bout de la piste,
     * exactement où finit le remplissage ; un doigt posé sur le curseur vise la position qu'il montre.
     */
    fun xDePart(part: Float, largeur: Float, marge: Float): Float {
        val m = marge.coerceIn(0f, largeur / 2f)
        return m + (largeur - 2 * m) * part.coerceIn(0f, 1f)
    }

    fun partDeX(x: Float, largeur: Float, marge: Float): Float {
        val m = marge.coerceIn(0f, largeur / 2f)
        val course = largeur - 2 * m
        return if (course <= 0f) 0f else ((x - m) / course).coerceIn(0f, 1f)
    }

    /** On peut sauter : quelqu'un qui pilote la musique, un titre dont la durée est connue (pas une radio). */
    fun permis(e: Party.Etat): Boolean {
        val m = e.maintenant
        val d = m.duree ?: return false
        val pilote = e.piloter || e.ici || (e.participer && e.actif)
        return pilote && e.lecteur.isNotEmpty() && d > 1.0 && m.titre.isNotEmpty() && (m.etat == "playing" || m.etat == "paused")
    }

    /** Ce que l'écran montre après un saut vers [cible] demandé à [depuisMs] : la cible, qui avance si ça joue. */
    fun attendue(cible: Double, depuisMs: Long, maintenantMs: Long, joue: Boolean, duree: Double?): Double {
        val v = cible + if (joue) (maintenantMs - depuisMs).coerceAtLeast(0L) / 1000.0 else 0.0
        return duree?.let { v.coerceAtMost(it) } ?: v
    }

    /** Le saut est tenu (le lecteur l'annonce) ou abandonné (trop vieux) : l'écran revient à la position du lecteur. */
    fun fini(annoncee: Double?, attendue: Double, depuisMs: Long, maintenantMs: Long): Boolean =
        maintenantMs - depuisMs > TENIR_MS || (annoncee != null && Math.abs(annoncee - attendue) <= ECART_S)

    fun analyser(o: org.json.JSONObject?): Info? {
        if (o == null) return null
        val p = o.optDouble("position"); val a = o.optDouble("il_y_a")
        if (p.isNaN() || a.isNaN() || p < 0 || a < 0) return null
        return Info(p, a)
    }

    /**
     * Le lecteur Classique (SlimProto) repart de zéro à chaque flux, et Music Assistant ne publie aucune position pour
     * un lecteur « · Vision » : après un saut, l'horloge locale doit savoir où le nouveau flux commence. Le saut vaut
     * pour le PREMIER flux ouvert après lui (un saut connu en retard ne décale jamais le titre suivant). Temps en ms
     * d'une horloge monotone (elapsedRealtime).
     */
    /**
     * l'auteur (16/10, « un petit cliquetis, et la lecture est revenue à zéro ») : un seul saut à la fois vers Music Assistant.
     * Chaque saut coupe et relance le flux ; des sauts qui se chevauchaient (appuis rapides à la télécommande) faisaient
     * repartir un flux sans son décalage ([Recalage] n'en garde qu'un en attente) : la position revenait à zéro, et le
     * saut suivant partait de là. Les demandes faites pendant un saut se fondent en une seule (la dernière), envoyée
     * quand le précédent a pris (son flux est reparti) ou au plus tard après [ATTENTE_MAX_MS]. Pur (testé en JVM).
     */
    class EnSerie {
        private var enVolDepuis: Long? = null
        private var suivant: Double? = null

        /** Une demande : rend la position à envoyer tout de suite, ou null (gardée pour après le saut en cours). */
        @Synchronized fun demander(positionS: Double, maintenant: Long): Double? {
            val depuis = enVolDepuis
            if (depuis != null && maintenant - depuis < ATTENTE_MAX_MS) { suivant = positionS; return null }
            enVolDepuis = maintenant; suivant = null
            return positionS
        }

        /** Le saut en cours a pris (ou a échoué) : rend la dernière demande gardée, à envoyer maintenant, ou null. */
        @Synchronized fun pris(maintenant: Long): Double? {
            val s = suivant
            suivant = null
            enVolDepuis = if (s != null) maintenant else null
            return s
        }

        val enVol: Boolean @Synchronized get() = enVolDepuis != null

        companion object {
            const val ATTENTE_MAX_MS = 4_000L
        }
    }

    class Recalage {
        private var debut = Long.MIN_VALUE / 2
        private var debutAvant = Long.MIN_VALUE / 2
        /** Le flux en cours a déjà reçu son saut : un autre saut attend le sien. */
        private var possede = false
        private var attente: Pair<Long, Long>? = null
        private var vuPosition = -1L
        private var vuQuand = Long.MIN_VALUE / 2
        /** Ce qu'il faut ajouter à la position du flux en cours (ms). */
        @Volatile var decalageMs = 0L; private set

        /** Un flux part (nouveau titre ou saut) : il commence à zéro, sauf s'il est celui d'un saut en attente. */
        @Synchronized fun nouveauFlux(t: Long) {
            debutAvant = debut; debut = t; decalageMs = 0L; possede = false
            attente?.let { (p, q) -> if (t >= q - TOLERANCE_MS && t - q <= ATTENTE_MS) { decalageMs = p; possede = true } }
            attente = null
        }

        /** Un saut vers [positionMs], demandé à [quand] (ici, ou annoncé par Home Assistant avec son âge). */
        @Synchronized fun saut(positionMs: Long, quand: Long, maintenant: Long) {
            // Le même saut revient à chaque relecture de l'état (30 s), et une fois de plus quand il a été demandé ici.
            if (Math.abs(positionMs - vuPosition) < 150 && Math.abs(quand - vuQuand) < MEME_SAUT_MS) return
            vuPosition = positionMs; vuQuand = quand
            val fluxApres = debut >= quand - TOLERANCE_MS
            when {
                // Le flux du saut est déjà reparti (premier flux depuis le saut, sans autre saut) : il commence là.
                !possede && fluxApres && debutAvant < quand - TOLERANCE_MS -> { decalageMs = positionMs; possede = true }
                // Pas encore reparti (ou le flux en cours est celui d'un autre saut) : au prochain flux.
                maintenant - quand <= ATTENTE_MS && (possede || !fluxApres) -> attente = positionMs to quand
                // Sinon, le flux du saut est passé (titre suivant déjà là) : oublié.
            }
        }

        companion object {
            /** Le flux peut repartir un peu avant que Home Assistant note l'heure du saut (pendant l'appel du service). */
            const val TOLERANCE_MS = 3_000L
            /** Un saut dont le flux n'est pas reparti dans ce délai est oublié. */
            const val ATTENTE_MS = 15_000L
            const val MEME_SAUT_MS = 10_000L
        }
    }
}
