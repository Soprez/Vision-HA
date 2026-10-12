// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Les réglages de cet appareil, rangés sur l'appareil : le thème, le mode
 * nuit, la radio, les tableaux de l'écran de veille (lesquels, combien de
 * temps) et les tableaux composés ici même. Home Assistant reste la source
 * des données (entités, météo, école…), jamais le maître des réglages.
 *
 * `version` monte à chaque changement : les écrans ouverts s'en aperçoivent
 * et se repeignent tout de suite.
 */
object Local {
    private const val PREFS = "local"
    @Volatile var version = 0
        private set

    /** Les radios de la veille : (libellé affiché, adresse). Noms propres des stations ; le genre, lui, se traduit. */
    val RADIOS: List<Pair<String, String>> get() = listOf(
        Tr.s(R.string.local_radio_fluid) to "https://ice1.somafm.com/fluid-128-mp3",
        Tr.s(R.string.local_radio_groove_salad) to "https://ice1.somafm.com/groovesalad-128-mp3",
        Tr.s(R.string.local_radio_lush) to "https://ice1.somafm.com/lush-128-mp3",
        Tr.s(R.string.local_radio_beat_blender) to "https://ice1.somafm.com/beatblender-128-mp3",
        Tr.s(R.string.local_radio_drone_zone) to "https://ice1.somafm.com/dronezone-128-mp3",
        Tr.s(R.string.local_radio_secret_agent) to "https://ice1.somafm.com/secretagent-128-mp3",
        "Radio Paradise Mellow" to "https://stream.radioparadise.com/mellow-128",  // i18n-exception : nom propre de station
        "Radio Paradise" to "https://stream.radioparadise.com/mp3-128",  // i18n-exception : nom propre de station
        "FIP" to "https://icecast.radiofrance.fr/fip-midfi.mp3",  // i18n-exception : nom propre de station
        "FIP Jazz" to "https://icecast.radiofrance.fr/fipjazz-midfi.mp3",  // i18n-exception : nom propre de station
        "FIP Électro" to "https://icecast.radiofrance.fr/fipelectro-midfi.mp3",  // i18n-exception : nom propre de station
        "FIP Groove" to "https://icecast.radiofrance.fr/fipgroove-midfi.mp3",  // i18n-exception : nom propre de station
        "FIP Pop" to "https://icecast.radiofrance.fr/fippop-midfi.mp3",  // i18n-exception : nom propre de station
    )
    private const val RADIO_DEFAUT = "https://ice1.somafm.com/fluid-128-mp3"
    val PLAGES_NUIT = listOf("23:00-07:00", "22:00-07:00", "00:00-06:30", "")

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun toucher() { version++ }

    // ------------------------------------------------------------ ambiance

    fun theme(ctx: Context): String = p(ctx).getString("theme", "Beige") ?: "Beige"
    /** Choisir un thème, c'est repartir de lui : les couleurs à la carte (y compris celles d'avant 1.27) s'effacent. */
    fun poserTheme(ctx: Context, nom: String) { p(ctx).edit().putString("theme", nom).apply(); Palette.ecrire(ctx, Palette.APPLI, null); toucher() }

    fun nuit(ctx: Context): String = p(ctx).getString("nuit", "23:00-07:00") ?: "23:00-07:00"
    fun poserNuit(ctx: Context, plage: String) { p(ctx).edit().putString("nuit", plage).apply(); toucher() }

    /** L'adresse du flux joué en veille ; vide = silence. */
    fun musique(ctx: Context): String = p(ctx).getString("musique", RADIO_DEFAUT) ?: ""
    fun poserMusique(ctx: Context, url: String) { p(ctx).edit().putString("musique", url).apply(); toucher() }
    fun nomRadio(url: String): String = if (url.isEmpty()) Tr.s(R.string.local_silence) else RADIOS.firstOrNull { it.second == url }?.first?.substringBefore(" ·") ?: Tr.s(R.string.local_personnalisee)

    /** Vrai si l'heure courante est dans la plage « HH:MM-HH:MM » (qui peut passer minuit). */
    fun dansPlage(plage: String): Boolean {
        val m = Regex("(\\d\\d):(\\d\\d)-(\\d\\d):(\\d\\d)").matchEntire(plage.trim()) ?: return false
        val (h1, m1, h2, m2) = m.destructured
        val debut = h1.toInt() * 60 + m1.toInt(); val fin = h2.toInt() * 60 + m2.toInt()
        val cal = java.util.Calendar.getInstance()
        val now = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        return if (debut <= fin) now in debut until fin else now >= debut || now < fin
    }

    fun estNuit(ctx: Context): Boolean = dansPlage(nuit(ctx))

    /** Télé : minutes sans toucher la télécommande avant l'écran de veille (0 = le réglage de la télé). */
    val DELAIS_VEILLE = listOf(0, 1, 2, 5, 10, 15, 30)
    fun delaiVeille(ctx: Context): Int = p(ctx).getInt("delai_veille", 0)
    fun poserDelaiVeille(ctx: Context, min: Int) {
        p(ctx).edit().putInt("delai_veille", min).apply(); toucher()
        // Le réglage système « écran de veille après » (celui des réglages de la télé), quand Vision a le droit de l'écrire.
        if (min > 0) try {
            if (android.os.Build.VERSION.SDK_INT < 23 || android.provider.Settings.System.canWrite(ctx))
                android.provider.Settings.System.putInt(ctx.contentResolver, android.provider.Settings.System.SCREEN_OFF_TIMEOUT, min * 60_000)
        } catch (_: Exception) {}
    }
    /** Télé : minutes sans télécommande sur l'accueil Vision avant sa propre veille, quand l'économiseur du système n'est pas Vision (0 = jamais). */
    fun delaiRepli(ctx: Context): Int = p(ctx).getInt("delai_repli", ReglesVeille.REPLI_DEFAUT)
    fun poserDelaiRepli(ctx: Context, min: Int) { p(ctx).edit().putInt("delai_repli", min).apply(); toucher() }
    /** Le repli a été choisi à l'assistant (télé où l'économiseur ne peut pas être Vision). */
    fun repliChoisi(ctx: Context): Boolean = p(ctx).getBoolean("repli_choisi", false)
    fun poserRepliChoisi(ctx: Context, on: Boolean) { p(ctx).edit().putBoolean("repli_choisi", on).apply(); toucher() }
    fun peutEcrireSysteme(ctx: Context): Boolean = android.os.Build.VERSION.SDK_INT < 23 || android.provider.Settings.System.canWrite(ctx)

    /** Les applis qui ont le droit de continuer en fond pendant l'écran de veille (Spotify, YouTube Music…). */
    private val FOND_DEFAUT = listOf("spotify", "music", "deezer", "radio", "tunein", "soundcloud", "qobuz", "tidal")
    /** YouTube et le récepteur de cast : on peut y envoyer un flux depuis un téléphone pendant que la veille est affichée. */
    val FOND_FLUX = listOf("com.google.android.youtube.tv", "com.google.android.youtube", "com.google.android.apps.mediashell")
    @Volatile private var fondDefaut: Set<String>? = null
    @Volatile private var fondDefautA = 0L
    private fun installe(ctx: Context, pk: String): Boolean = try { ctx.packageManager.getPackageInfo(pk, 0); true } catch (_: Exception) { false }
    /** Ceux de `FOND_FLUX` présents sur l'appareil (le récepteur de cast n'a pas d'icône : il n'est pas dans la liste des applis). */
    /** Une appli installée ou supprimée : la liste par défaut sera recalculée. */
    fun oublierFond() { fondDefaut = null; fondDefautA = 0L }
    fun fondFlux(ctx: Context): List<String> = FOND_FLUX.filter { installe(ctx, it) }
    fun fond(ctx: Context): Set<String> {
        val brut = p(ctx).getStringSet("fond", null)
        if (brut == null) {
            // Calculée à partir des applis installées : on la garde une minute, la veille la consulte souvent.
            val now = android.os.SystemClock.uptimeMillis()
            fondDefaut?.let { if (now - fondDefautA < 60_000) return it }
            val mots = try { Accueil.applicationsInstallees(ctx).map { it.activityInfo.packageName }.filter { pk -> FOND_DEFAUT.any { pk.contains(it, ignoreCase = true) } } } catch (_: Exception) { emptyList() }
            return (mots + fondFlux(ctx)).toSet().also { fondDefaut = it; fondDefautA = now }
        }
        // La liste a déjà été réglée ici : YouTube et le cast la rejoignent une seule fois, sans rien retirer du choix ;
        // les décocher ensuite reste possible, ils ne reviendront pas.
        if (!p(ctx).getBoolean("fond_flux_ajoute", false)) {
            val plus = HashSet(brut).apply { addAll(fondFlux(ctx)) }
            p(ctx).edit().putStringSet("fond", plus).putBoolean("fond_flux_ajoute", true).apply()
            return plus
        }
        return brut
    }
    fun poserFond(ctx: Context, s: Set<String>) { p(ctx).edit().putStringSet("fond", HashSet(s)).putBoolean("fond_flux_ajoute", true).apply(); toucher() }
    /** L'appli qui joue en ce moment, si elle a le droit de rester en fond ; null sinon (ou si rien ne joue). */
    fun appEnFond(ctx: Context): String? {
        val permis = fond(ctx)
        // D'abord les sessions média : elles disent qui joue même quand personne n'a rien ouvert ici (cast lancé d'un téléphone).
        val vus = Flux.paquets(ctx)
        if (vus != null && vus.isNotEmpty()) return vus.firstOrNull { it in permis }
        // Sinon l'ancien indice : du son qui n'est pas le nôtre, et la dernière appli passée devant.
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        if (!am.isMusicActive || Musique.enCours) return null
        val pk = Usage.dernierPaquet
        return if (pk.isNotEmpty() && pk != ctx.packageName && pk in permis) pk else null
    }
    /** Une appli vidéo (YouTube, cast) regardée au premier plan : la veille ne vient pas la recouvrir d'elle-même. */
    fun seRegarde(pk: String): Boolean = pk in FOND_FLUX
    /** Vrai si une appli joue et n'a PAS le droit d'être recouverte (Netflix, un jeu…). */
    fun appAProteger(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        return am.isMusicActive && !Musique.enCours && appEnFond(ctx) == null
    }

    /** Télé où le système impose son propre accueil (Google TV) : Vision prend la touche Accueil à sa place. */
    fun accueilForce(ctx: Context): Boolean = p(ctx).getBoolean("accueil_force", true)
    fun poserAccueilForce(ctx: Context, on: Boolean) { p(ctx).edit().putBoolean("accueil_force", on).apply(); toucher() }


    /**
     * Le thème effectif du moment, le même pour tout l'appareil. 1.72.0 : en mode sombre (toujours, ou comme le
     * système) et pendant la plage de nuit, la version sombre du thème choisi (et non plus « Sombre » pour tous) ;
     * sinon le thème choisi et ses couleurs à la carte.
     */
    fun themeEffectif(ctx: Context, cible: String): Theme {
        val base = Themes.parNom(theme(ctx))
        if (!base.sombre && Themes.sombreVoulu(modeSombre(ctx), systemeSombre(ctx), estNuit(ctx))) return Themes.versionSombre(base)
        return Palette.appliquer(ctx, cible, base)
    }

    fun modeSombre(ctx: Context): String = (p(ctx).getString("mode_sombre", Themes.SOMBRE_JAMAIS) ?: Themes.SOMBRE_JAMAIS)
        .let { m -> if (Themes.MODES_SOMBRE.any { it.first == m }) m else Themes.SOMBRE_JAMAIS }
    fun poserModeSombre(ctx: Context, m: String) { p(ctx).edit().putString("mode_sombre", m).apply(); toucher() }
    fun systemeSombre(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

    /** Au premier lancement seulement : on part des réglages que Home Assistant avait (thème, nuit, radio). */
    fun amorcer(ctx: Context, reglages: JSONObject?) {
        if (reglages == null || p(ctx).contains("theme")) return
        p(ctx).edit()
            .putString("theme", reglages.optString("theme", "Beige").ifEmpty { "Beige" })
            .putString("nuit", reglages.optString("nuit", "23:00-07:00"))
            .putString("musique", reglages.optString("musique", RADIO_DEFAUT))
            .apply()
        toucher()
    }

    // ------------------------------------------------------------ cartes de l'écran de veille

    /** Les vraies cartes de Home Assistant (la même page que sur les PC), ou le dessin simplifié de l'appli. */
    fun veilleWeb(ctx: Context): Boolean = p(ctx).getBoolean("veille_web", true)
    fun poserVeilleWeb(ctx: Context, on: Boolean) { p(ctx).edit().putBoolean("veille_web", on).apply(); toucher() }

    /** Le style graphique des cartes, distinct des couleurs : formes, lettres, angles. */
    val STYLES: List<Pair<String, String>> get() = listOf("doux" to Tr.s(R.string.local_style_doux), "neoretro" to Tr.s(R.string.local_style_neoretro))
    fun styleCartes(ctx: Context): String = (p(ctx).getString("style_cartes", "doux") ?: "doux").let { s -> if (STYLES.any { it.first == s }) s else "doux" }
    fun poserStyleCartes(ctx: Context, s: String) { p(ctx).edit().putString("style_cartes", s).apply(); toucher() }
    fun fondCartes(ctx: Context): Boolean = p(ctx).getBoolean("fond_cartes", true)
    fun poserFondCartes(ctx: Context, on: Boolean) { p(ctx).edit().putBoolean("fond_cartes", on).apply(); toucher() }
    fun contourCartes(ctx: Context): Boolean = p(ctx).getBoolean("contour_cartes", false)
    fun poserContourCartes(ctx: Context, on: Boolean) { p(ctx).edit().putBoolean("contour_cartes", on).apply(); toucher() }
    fun animerCartes(ctx: Context): Boolean = p(ctx).getBoolean("animer_cartes", true)
    fun poserAnimerCartes(ctx: Context, on: Boolean) { p(ctx).edit().putBoolean("animer_cartes", on).apply(); toucher() }

    /** Les tableaux et leurs cartes tels que la page de veille les montre : [{id, titre, cartes: [{cle, cles, nom, section}]}]. */
    fun listeWeb(ctx: Context): JSONArray = try { JSONArray(p(ctx).getString("liste_web", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
    fun retenirListeWeb(ctx: Context, a: JSONArray?) {
        if (a == null) return
        val s = a.toString()
        if (s != p(ctx).getString("liste_web", "")) { p(ctx).edit().putString("liste_web", s).putLong("liste_web_a", System.currentTimeMillis()).apply(); toucher() }
        else p(ctx).edit().putLong("liste_web_a", System.currentTimeMillis()).apply()
    }
    fun listeWebA(ctx: Context): Long = p(ctx).getLong("liste_web_a", 0L)

    // ------------------------------------------------------------ tableaux de veille

    /** Les choix par tableau : {"horloge": {"actif": true, "duree": 15}, "entites:abc": {...}}. */
    private fun choix(ctx: Context): JSONObject = try { JSONObject(p(ctx).getString("tableaux", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }

    fun cleTableau(code: String, id: String): String = if (code == "entites" || code == "dash") "$code:$id" else code

    /** Les cartes d'un tableau de bord que cet appareil ne montre pas. */
    fun cartesMasquees(ctx: Context, cle: String): Set<String> {
        val a = choix(ctx).optJSONObject(cle)?.optJSONArray("masquees") ?: return emptySet()
        return (0 until a.length()).map { a.optString(it) }.toSet()
    }

    /** Les cartes d'un tableau de bord qui arrivent sans s'animer. */
    fun cartesFigees(ctx: Context, cle: String): Set<String> {
        val a = choix(ctx).optJSONObject(cle)?.optJSONArray("figees") ?: return emptySet()
        return (0 until a.length()).map { a.optString(it) }.toSet()
    }

    fun poserCartesFigees(ctx: Context, cle: String, figees: Collection<String>) {
        val c = choix(ctx)
        val o = c.optJSONObject(cle) ?: JSONObject()
        o.put("figees", JSONArray(figees.toList()))
        c.put(cle, o)
        p(ctx).edit().putString("tableaux", c.toString()).apply(); toucher()
    }

    fun poserCartesMasquees(ctx: Context, cle: String, masquees: Collection<String>) {
        val c = choix(ctx)
        val o = c.optJSONObject(cle) ?: JSONObject()
        o.put("masquees", JSONArray(masquees.toList()))
        c.put(cle, o)
        p(ctx).edit().putString("tableaux", c.toString()).apply(); toucher()
    }

    fun actif(ctx: Context, cle: String, defaut: Boolean): Boolean = choix(ctx).optJSONObject(cle)?.optBoolean("actif", defaut) ?: defaut
    fun duree(ctx: Context, cle: String, defaut: Int): Int = choix(ctx).optJSONObject(cle)?.optInt("duree", defaut) ?: defaut

    fun poserTableau(ctx: Context, cle: String, actif: Boolean? = null, duree: Int? = null) {
        val c = choix(ctx)
        val o = c.optJSONObject(cle) ?: JSONObject()
        if (actif != null) o.put("actif", actif)
        if (duree != null) o.put("duree", duree.coerceIn(5, 120))
        c.put(cle, o)
        p(ctx).edit().putString("tableaux", c.toString()).apply(); toucher()
    }

    /** Les tableaux composés sur cet appareil : [{id, titre, duree, cases: [{entite, libelle, rendu}]}]. */
    fun locaux(ctx: Context): JSONArray = try { JSONArray(p(ctx).getString("locaux", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }

    fun ecrireLocaux(ctx: Context, a: JSONArray) { p(ctx).edit().putString("locaux", a.toString()).apply(); toucher() }

    fun nouveauLocal(ctx: Context, titre: String): JSONObject {
        val t = JSONObject().put("id", java.lang.Long.toHexString(System.currentTimeMillis()).takeLast(7)).put("titre", titre).put("duree", 22).put("cases", JSONArray())
        val a = locaux(ctx); a.put(t); ecrireLocaux(ctx, a)
        return t
    }

    fun local(ctx: Context, id: String): JSONObject? {
        val a = locaux(ctx)
        for (i in 0 until a.length()) if (a.getJSONObject(i).optString("id") == id) return a.getJSONObject(i)
        return null
    }

    fun remplacerLocal(ctx: Context, t: JSONObject) {
        val a = locaux(ctx); val n = JSONArray()
        var vu = false
        for (i in 0 until a.length()) { val x = a.getJSONObject(i); if (x.optString("id") == t.optString("id")) { n.put(t); vu = true } else n.put(x) }
        if (!vu) n.put(t)
        ecrireLocaux(ctx, n)
    }

    fun supprimerLocal(ctx: Context, id: String) {
        val a = locaux(ctx); val n = JSONArray()
        for (i in 0 until a.length()) { val x = a.getJSONObject(i); if (x.optString("id") != id) n.put(x) }
        ecrireLocaux(ctx, n)
    }
}
