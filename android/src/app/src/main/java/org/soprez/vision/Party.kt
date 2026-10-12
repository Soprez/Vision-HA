// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

/**
 * 1.81.0 (point 14 bis) : le Mode Party, la partie sans Android (essayée en JUnit, PartyTest).
 * Sur la télé, une « appli » de l'accueil Vision (clé CLE) ouvre l'écran plein écran ; quand un décideur lance la
 * Party sur cette télé, le relevé le dit (`party.ecran`) et l'écran s'ouvre de lui-même. Sur le téléphone d'un
 * décideur, la même tuile ouvre le pilotage. Contrat : pc_parental/party.py (Home Assistant).
 */
object Party {
    /** La clé de la tuile sur l'accueil (pas un vrai paquet Android). */
    const val CLE = "vision:party"
    val NOM: String get() = Tr.s(R.string.party_nom)  // 1.86.2 (retour d'essai) : le seul endroit du nom affiché
    private const val PREFS = "party"

    data class Ecran(val id: String, val nom: String)
    /** 1.82.0 : `ma` = enceinte de Music Assistant (on peut y choisir quoi jouer). */
    /** flux : enchaînement sans blanc (un seul flux) ; fondu : fondu enchaîné de [duree] s ; precharge : suivant déjà chargé. */
    data class Enchainement(val flux: Boolean, val fondu: Boolean, val duree: Int?, val precharge: Boolean)

    /** Les choix proposés, dans l'ordre : sans blanc, puis fondus de 4, 8 et 12 s. */
    val FONDUS = listOf(0, 4, 8, 12)

    fun libelleEnchainement(x: Enchainement?): String = when {
        x == null -> Tr.s(R.string.mus_ench_inconnu)
        x.fondu -> Tr.s(R.string.mus_ench_fondu, x.duree ?: 8)
        x.flux -> Tr.s(R.string.mus_ench_sans_blanc)
        else -> Tr.s(R.string.mus_ench_titre)
    }

    data class Lecteur(val id: String, val nom: String, val etat: String, val groupe: Boolean, val ma: Boolean = false,
                       // 1.86.3 (l'auteur : « je n'ai pas toutes les télés ») : tout est montré ; ce qui ne sert pas est grisé avec
                       // la raison ; `ecran` : la télé de Musique elle-même (le lecteur de Music Assistant prendra l'écran).
                       val dispo: Boolean = true, val raison: String = "", val ecran: Boolean = false)
    /** Un titre de la file : `id` pour le retirer, `invite` s'il a été proposé par un invité. */
    data class Titre(val titre: String, val artiste: String, val id: String = "", val invite: Boolean = false, val image: String = "")
    /** Une proposition d'invité en attente (mode « je valide d'abord »). */
    data class Proposition(val id: String, val titre: String, val artiste: String)
    /** Un choix de musique (Music Assistant) : playlist, radio, album, artiste ou titre. */
    data class Choix(val uri: String, val nom: String, val type: String, val artiste: String, val image: String, val source: String = "", val domaine: String = "",
                     /** 1.86.13 (retour d'essai) : le nombre de titres (playlist), et « playlist fabriquée par Music Assistant ». */
                     val nb: Int = -1, val auto: Boolean = false)
    /** 1.86.10 (retour d'essai) : une plateforme de musique reliée à Music Assistant (« library » : la bibliothèque). */
    data class Plateforme(val domaine: String, val nom: String)

    fun analyserPlateformes(o: JSONObject): List<Plateforme> =
        objets(o.optJSONArray("plateformes")).map { Plateforme(it.optString("domaine"), it.optString("nom")) }.filter { it.domaine.isNotEmpty() && it.nom.isNotEmpty() }

    /** Les filtres de recherche, mémorisés sur l'appareil (vides : tout). */
    fun filtresTypes(ctx: Context): Set<String> = prefs(ctx).getStringSet("filtre_types", emptySet())?.toSet() ?: emptySet()
    fun filtresPlateformes(ctx: Context): Set<String> = prefs(ctx).getStringSet("filtre_plateformes", emptySet())?.toSet() ?: emptySet()
    fun poserFiltres(ctx: Context, types: Set<String>, plateformes: Set<String>) =
        prefs(ctx).edit().putStringSet("filtre_types", types).putStringSet("filtre_plateformes", plateformes).apply()
    fun plateformesConnues(ctx: Context): List<Plateforme> = try {
        analyserPlateformes(JSONObject().put("plateformes", org.json.JSONArray(prefs(ctx).getString("plateformes", "[]"))))
    } catch (_: Exception) { emptyList() }
    fun garderPlateformes(ctx: Context, l: List<Plateforme>) =
        prefs(ctx).edit().putString("plateformes", org.json.JSONArray().apply { l.forEach { put(JSONObject().put("domaine", it.domaine).put("nom", it.nom)) } }.toString()).apply()
    data class Maintenant(
        val etat: String, val titre: String, val artiste: String, val album: String, val appli: String,
        val volume: Double?, val duree: Double?, val position: Double?, val positionTs: Double?, val pochette: String,
        // Client Sendspin natif (règles du projet) : un groupe se règle par son volume de groupe ; ses enceintes en détail.
        val groupe: Boolean = false, val membres: List<Membre> = emptyList(),
    )
    /** Une enceinte d'un groupe ; `volume` null : une enceinte « · Vision », réglée sur la télé. */
    data class Membre(val id: String, val nom: String, val volume: Double?)
    data class Etat(
        val actif: Boolean, val ecran: String, val ecranNom: String, val ici: Boolean, val lecteur: String,
        val lecteurNom: String, val maintenant: Maintenant, val file: List<Titre>,
        val piloter: Boolean, val ecrans: List<Ecran>, val lecteurs: List<Lecteur>,
        val relire: Int,
        // 1.82.0 : choisir la musique, invités.
        val ma: Boolean = false, val lecteurMa: Boolean = false, val choisir: Boolean = false, val qr: String = "",
        val invitesMode: String = "direct", val propositions: List<Proposition> = emptyList(),
        // 1.85.0 : Party ouverte à tous — cet appareil pilote la musique ; sinon, pourquoi (fermé par ses règles).
        val participer: Boolean = false, val ferme: String = "",
        // 1.86.2 (l'auteur : « une file infinie ») : le vrai nombre de titres à suivre ; « continuer avec des titres similaires ».
        val fileTotal: Int = -1, val similaires: Boolean = false,
        val aideLecteurs: String = "",
        // 1.86.6 (l'auteur : « trop d'attente entre deux musiques ») : comment Music Assistant enchaîne sur ce lecteur.
        val enchainement: Enchainement? = null,
        // 1.86.2 (retour d'essai) : file vide et musique arrêtée → le numéro du clin d'œil tiré par Home Assistant (-1 : rien).
        val finClin: Int = -1,
    )

    private fun JSONObject.nombre(k: String): Double? = if (isNull(k) || !has(k)) null else optDouble(k).takeIf { !it.isNaN() }
    private fun objets(a: JSONArray?): List<JSONObject> = (0 until (a?.length() ?: 0)).mapNotNull { a?.optJSONObject(it) }

    /** « 12 titres à suivre » : le vrai total quand Home Assistant le donne, sinon ce qui est montré. */
    fun aSuivre(e: Etat): String {
        val n = if (e.fileTotal >= 0) maxOf(e.fileTotal, e.file.size) else e.file.size
        return aSuivreTexte(n)
    }

    fun aSuivreTexte(n: Int): String = if (n == 0) Tr.s(R.string.party_rien_a_suivre) else Tr.p(R.plurals.party_a_suivre, n, n)

    /** Le clin d'œil et l'action (fichiers de traduction), ou null si la musique joue encore. */
    fun clinDOeil(ctx: android.content.Context, e: Etat): Pair<String, String>? {
        if (!e.actif || e.finClin < 0 || e.file.isNotEmpty()) return null
        val l = ctx.resources.getStringArray(R.array.musique_fin_clins)
        if (l.isEmpty()) return null
        return l[e.finClin % l.size] to ctx.getString(R.string.musique_fin_action)
    }

    fun analyser(o: JSONObject): Etat {
        val m = o.optJSONObject("maintenant") ?: JSONObject()
        return Etat(
            actif = o.optBoolean("actif"), ecran = o.optString("ecran"), ecranNom = o.optString("ecran_nom"),
            ici = o.optBoolean("ici"), lecteur = o.optString("lecteur"), lecteurNom = o.optString("lecteur_nom"),
            maintenant = Maintenant(m.optString("etat", "unavailable"), m.optString("titre"), m.optString("artiste"),
                m.optString("album"), m.optString("appli"), m.nombre("volume"), m.nombre("duree"), m.nombre("position"),
                m.nombre("position_ts"), m.optString("pochette"), m.optBoolean("groupe"),
                objets(m.optJSONArray("membres")).map { Membre(it.optString("id"), it.optString("nom"), it.nombre("volume")) }.filter { it.id.startsWith("media_player.") }),
            file = objets(o.optJSONArray("file")).map { Titre(it.optString("titre"), it.optString("artiste"), it.optString("id"), it.optBoolean("invite"), image(it)) }.filter { it.titre.isNotEmpty() },
            piloter = o.optBoolean("piloter"),
            ecrans = objets(o.optJSONArray("ecrans")).map { Ecran(it.optString("id"), it.optString("nom")) }.filter { it.id.isNotEmpty() },
            lecteurs = objets(o.optJSONArray("lecteurs")).map { Lecteur(it.optString("entity_id"), it.optString("nom"), it.optString("etat"), it.optBoolean("groupe"), it.optBoolean("ma"),
                it.optBoolean("dispo", it.optString("etat") != "unavailable"), it.optString("raison"), it.optBoolean("ecran")) }
                .filter { it.id.startsWith("media_player.") },
            relire = o.optInt("relire", 5).coerceIn(2, 60),
            ma = o.optBoolean("ma"), lecteurMa = o.optBoolean("lecteur_ma"), choisir = o.optBoolean("choisir"),
            qr = o.optString("qr").takeIf { it.startsWith("https://") }.orEmpty(),
            invitesMode = if (o.optString("invites_mode") == "validation") "validation" else "direct",
            propositions = objets(o.optJSONArray("propositions")).map { Proposition(it.optString("id"), it.optString("titre"), it.optString("artiste")) }.filter { it.id.isNotEmpty() },
            participer = o.optBoolean("participer", o.optBoolean("piloter")), ferme = o.optString("ferme"),
            fileTotal = if (o.has("file_total") && !o.isNull("file_total")) o.optInt("file_total", -1) else -1,
            similaires = o.optBoolean("similaires"),
            aideLecteurs = o.optString("aide_lecteurs"),
            enchainement = o.optJSONObject("enchainement")?.let { x ->
                Enchainement(x.optBoolean("flux"), x.optBoolean("fondu"), if (x.isNull("duree")) null else x.optInt("duree"), x.optBoolean("precharge"))
            },
            finClin = o.optInt("fin_clin", -1),
        )
    }

    /**
     * 1.87.1 (l'auteur : « dans la playlist les images ne se chargent pas ») : la pochette d'un titre ou d'un résultat passe
     * par Home Assistant, avec la référence qu'il a lui-même donnée (`image_ref`, lue par Vignettes sur
     * /api/pc_parental/party/image) : jamais l'adresse de Music Assistant (http, réseau local) lue par l'appareil.
     * Sans référence (Home Assistant plus ancien), seulement une image https (publique) ; jamais http.
     */
    fun image(x: JSONObject): String = imagePour(x.optString("image_ref"), x.optString("image"))

    fun imagePour(refBrute: String, image: String): String {
        val ref = refBrute.trim()
        if (ref.isNotEmpty() && ref.length <= 64 && ref.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }) return Vignettes.RELAIS + ref
        return image.takeIf { it.startsWith("https://") }.orEmpty()
    }

    /** Les choix d'une réponse /party/musique : `items` (bibliothèque) ou `resultats` groupés (recherche). */
    fun analyserChoix(o: JSONObject): List<Choix> {
        fun choix(x: JSONObject) = Choix(x.optString("uri"), x.optString("nom"), x.optString("type"), x.optString("artiste"),
            image(x), x.optString("source"), x.optString("domaine"),
            if (x.has("nb") && !x.isNull("nb")) x.optInt("nb", -1) else -1, x.optBoolean("auto"))
        val sortie = ArrayList<Choix>()
        objets(o.optJSONArray("items")).forEach { sortie.add(choix(it)) }
        o.optJSONObject("resultats")?.let { r -> for (t in ORDRE_TYPES) objets(r.optJSONArray(t)).forEach { sortie.add(choix(it)) } }
        return sortie.filter { it.uri.isNotEmpty() && it.nom.isNotEmpty() }
    }

    /** 1.85.0 : ce que dit Home Assistant d'une recherche — un fournisseur qui n'a rien trouvé, ou quoi faire. */
    fun conseilRecherche(o: JSONObject): String = o.optString("conseil")

    val ORDRE_TYPES = listOf("playlist", "track", "album", "artist", "radio")
    /** L'ordre des sections d'une recherche « Tout ». */
    val ORDRE_RECHERCHE = listOf("track", "album", "playlist", "artist", "radio")

    fun nomSection(t: String): String = when (t) {
        "track" -> Tr.s(R.string.mus_titres); "album" -> Tr.s(R.string.mus_albums); "playlist" -> Tr.s(R.string.mus_playlists); "artist" -> Tr.s(R.string.mus_artistes); "radio" -> Tr.s(R.string.mus_radios); else -> Tr.s(R.string.mus_autres)
    }

    /** La couleur repère d'une plateforme (sa couleur connue ; sinon l'accent du thème). */
    fun couleurPlateforme(domaine: String, defaut: Int): Int = when (domaine) {
        "ytmusic" -> 0xFFFF0033.toInt(); "spotify" -> 0xFF1DB954.toInt(); "tidal" -> 0xFF00FFFF.toInt(); "deezer" -> 0xFFA238FF.toInt()
        "apple_music" -> 0xFFFA243C.toInt(); "qobuz" -> 0xFF0170CC.toInt(); "soundcloud" -> 0xFFFF5500.toInt()
        else -> defaut
    }

    fun nomType(t: String): String = when (t) {
        "playlist" -> Tr.s(R.string.party_type_playlist); "track" -> Tr.s(R.string.party_type_titre); "album" -> Tr.s(R.string.party_type_album)
        "artist" -> Tr.s(R.string.party_type_artiste); "radio" -> Tr.s(R.string.party_type_radio); else -> ""
    }

    /** Rien ne joue : l'écran guide vers le choix d'une playlist, jamais un écran muet. */
    fun rienNeJoue(m: Maintenant): Boolean = m.titre.isEmpty() && m.etat != "playing"

    /** La ligne d'un titre de la file, avec « proposé par un invité ». */
    fun ligneFile(t: Titre): String = listOf(t.artiste, if (t.invite) Tr.s(R.string.party_propose_invite) else "").filter { it.isNotEmpty() }.joinToString(" · ")

    /** La position du morceau maintenant (il avance tout seul entre deux relectures), bornée à sa durée. */
    fun position(m: Maintenant, maintenantS: Double): Double? {
        val p = m.position ?: return null
        val avance = if (m.etat == "playing" && m.positionTs != null) (maintenantS - m.positionTs).coerceAtLeast(0.0) else 0.0
        val v = p + avance
        return m.duree?.let { v.coerceAtMost(it) } ?: v
    }

    /**
     * 1.87.1 (l'auteur : « l'état de lecture n'avance pas ») : Music Assistant ne publie aucune position pour un lecteur
     * « · Vision » (Squeezelite de Vision). Quand cet appareil EST le lecteur de la Party, la position vient de son propre
     * lecteur ; sinon, celle que donne Home Assistant.
     */
    fun positionAffichee(ctx: android.content.Context, e: Etat, maintenantS: Double): Double? {
        // 1.88.0 (l'auteur : 0:00 sur le Chromecast) : quand cet appareil EST le lecteur (Classique ou Sendspin), sa position
        // passe avant celle de HA.
        LecteurMusique.positionLocale(ctx, e.ici, e.lecteurNom)?.let { locale ->
            return e.maintenant.duree?.let { locale.coerceAtMost(it) } ?: locale
        }
        return position(e.maintenant, maintenantS)
    }

    /**
     * 1.87.2 (l'auteur : « la gestion du volume n'a rien à faire ici car elle est gérée par la TV ») : un lecteur « · Vision »
     * (une télé qui joue elle-même) n'a pas de volume dans Vision — Home Assistant le tient à 100 %, la télécommande règle
     * le son. Le volume reste réglable pour une autre enceinte (pilotée depuis le téléphone, par exemple).
     */
    fun volumeReglable(lecteurNom: String): Boolean = !lecteurNom.trim().endsWith("· Vision")

    /**
     * Client Sendspin natif (règles du projet, 2026-10-12) : un groupe d'enceintes a toujours son volume de groupe (même mené
     * par une télé « · Vision », qui elle reste à 100 %) ; sinon la règle du lecteur seul ci-dessus.
     */
    fun volumeReglable(lecteurNom: String, m: Maintenant): Boolean = m.groupe || volumeReglable(lecteurNom)

    /** Le détail par enceinte : seulement pour un groupe dont au moins une enceinte se règle (pas une « · Vision »). */
    fun detailVolumes(m: Maintenant): Boolean = m.groupe && m.membres.any { it.volume != null }

    /** 84 → « 1:24 » ; 3725 → « 1:02:05 ». */
    fun temps(s: Double?): String {
        if (s == null || s.isNaN() || s < 0) return ""
        val t = s.toLong(); val h = t / 3600; val m = (t % 3600) / 60; val sec = t % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    fun nomLecteur(l: Lecteur): String = if (l.groupe) Tr.s(R.string.party_groupe, l.nom) else l.nom

    /** Ce qu'affiche l'écran quand rien ne passe. */
    fun titreAffiche(m: Maintenant): String = m.titre.ifEmpty {
        when (m.etat) {
            "unavailable", "unknown" -> Tr.s(R.string.party_enceinte_indisponible)
            "off" -> Tr.s(R.string.party_enceinte_eteinte)
            "paused" -> Tr.s(R.string.party_en_pause)
            else -> Tr.s(R.string.party_rien_en_lecture)
        }
    }

    /** Un échec, dans la langue de l'appareil, jamais un code. */
    fun messageErreur(code: Int, corps: JSONObject?): String {
        corps?.optString("retour")?.takeIf { it.isNotEmpty() }?.let { return it }
        return when (code) {
            401 -> Tr.s(R.string.party_err_inconnu)
            403 -> Tr.s(R.string.party_err_decideurs, Vocabulaire.decideurs())
            429 -> Tr.s(R.string.party_err_doucement)
            0 -> Tr.s(R.string.party_err_reseau)
            else -> Tr.s(R.string.party_err_autre)
        }
    }

    /** Le volume après − ou + (pas de 5 %), borné. */
    fun volumeApres(actuel: Double?, sens: Int): Double = Math.round(((actuel ?: 0.3) + 0.05 * sens).coerceIn(0.0, 1.0) * 20) / 20.0

    // ------------------------------------------------------------------ relevé

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** La tuile « Mode Party » apparaît sur l'accueil (télé de la maison, ou décideur) ; le serveur le dit. */
    fun tuile(ctx: Context): Boolean = prefs(ctx).getBoolean("tuile", false)

    /** 1.86.5 (retour d'essai) : la pochette floutée en fond d'accueil pendant la musique (réglage de l'appareil, non par défaut). */
    fun fondFloute(ctx: Context): Boolean = prefs(ctx).getBoolean("fond_floute", false)
    fun fondFloute(ctx: Context, oui: Boolean) = prefs(ctx).edit().putBoolean("fond_floute", oui).apply()

    /** Le Mode Fête au lancement (interrupteur du pilotage, mémorisé ; allumé d'office). */

    /** Le relevé : tuile à montrer ou non ; la télé choisie ouvre l'écran Party une fois par lancement. */
    fun releve(ctx: Context, p: JSONObject?) {
        val pr = prefs(ctx)
        val tuile = p?.optBoolean("tuile") == true
        if (tuile != pr.getBoolean("tuile", false)) {
            pr.edit().putBoolean("tuile", tuile).apply()
            // Accueil télé « choisies » : la tuile se propose une fois d'elle-même.
            if (tuile && !pr.getBoolean("proposee", false)) {
                pr.edit().putBoolean("proposee", true).apply()
                if (Accueil.teleChoisies(ctx)) Accueil.cacher(ctx, CLE, false)
            }
            Paquets.tuileInterne(CLE, retiree = !tuile)
        }
        if (p == null || !ReglagesTvActivity.estTele(ctx)) return
        // 1.86.14 (retour d'essai) : le lecteur Music Assistant de cette télé joue-t-il ? (aucune veille tant que oui)
        VeilleMusique.releve(ctx, p.optBoolean("musique_joue"))
        val depuis = p.optLong("depuis")
        if (p.optBoolean("ecran") && depuis != pr.getLong("ouverte", 0L)) {
            pr.edit().putLong("ouverte", depuis).apply()
            ouvrir(ctx, auto = true)
        }
    }

    fun ouvrir(ctx: Context, auto: Boolean = false) {
        try {
            ctx.startActivity(Intent(ctx, PartyActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("auto", auto))
        } catch (_: Exception) {}
    }
}
