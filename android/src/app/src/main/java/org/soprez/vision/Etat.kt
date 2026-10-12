// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

/**
 * Ce que l'appareil doit appliquer, tel que Home Assistant l'a résolu.
 * L'agent ne connaît ni étiquettes ni plages : il reçoit des noms et un ordre.
 */
object Etat {
    @Volatile var verrouille = false
    @Volatile var message = ""
    @Volatile var avertissement = 0
    @Volatile var prochainChangement: Long = 0
    @Volatile var poll = 20
    @Volatile var apps: Set<String> = emptySet()
    /** Applis que les parents laissent ouvertes même appareil fermé (étiquette « Toujours autorisé »). */
    @Volatile var toujours: Set<String> = emptySet()
    @Volatile var sites: Set<String> = emptySet()
    @Volatile var exceptions: Set<String> = emptySet()
    /** Pourquoi chaque nom est fermé : nom → « code|texte ». */
    @Volatile var raisons: Map<String, String> = emptyMap()
    @Volatile var dns: List<String> = emptyList()
    @Volatile var listeNoire: Set<String> = emptySet()
    /** SafeSearch et YouTube restreint : HA le demande pour un appareil d'enfant. */
    @Volatile var safeSearch = false
    /** Cet appareil porte l'étiquette « Parents » : il voit l'espace parents. */
    @Volatile var parent = false
    @Volatile var derniereReponse: Long = 0
    /** Soutien du projet (badge décoratif), tel que Home Assistant l'a dit ; gardé avec le reste du relevé. */
    @Volatile var soutien: Soutien.Statut = Soutien.INACTIF

    /** Ce que le VPN a vu passer depuis le dernier relevé. */
    val sitesVus: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
    val alertes: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
    /** Sites bloqués récemment (hors dangereux) : de quoi demander l'accès. domaine → heure. */
    val bloquesRecents: LinkedHashMap<String, Long> = LinkedHashMap()

    fun noterBlocage(hote: String) {
        val d = domainePrincipal(hote)
        if (d.isEmpty()) return
        synchronized(bloquesRecents) {
            bloquesRecents.remove(d); bloquesRecents[d] = System.currentTimeMillis()
            while (bloquesRecents.size > 12) bloquesRecents.remove(bloquesRecents.keys.first())
        }
    }

    fun recentsBloques(): List<String> = synchronized(bloquesRecents) {
        val limite = System.currentTimeMillis() - 2 * 3600_000L
        bloquesRecents.entries.filter { it.value > limite }.map { it.key }.reversed()
    }

    /** « m.youtube.com » → « youtube.com » (avec les suffixes doubles comme co.uk). */
    fun domainePrincipal(hote: String): String {
        val parts = hote.lowercase(Locale.ROOT).trimEnd('.').split('.').filter { it.isNotEmpty() }
        if (parts.size < 2) return ""
        val doubles = setOf("co", "com", "org", "net", "gov", "edu", "ac")
        val n = if (parts.size >= 3 && parts[parts.size - 1].length == 2 && parts[parts.size - 2] in doubles) 3 else 2
        return parts.takeLast(n).joinToString(".")
    }

    /** Paquets qui doivent rester utilisables même verrouillé (appels, système). */
    private val toujoursPermis = setOf(
        "android", "com.android.systemui", "com.android.phone", "com.android.server.telecom",
        "com.android.dialer", "com.google.android.dialer", "com.samsung.android.dialer",
        "com.android.emergency", "com.google.android.apps.safetyhub",
        "com.android.incallui", "com.samsung.android.incallui",
    )

    /** Les applis toujours ouvertes, même appareil fermé : téléphone, messages, réveil, Pronote (et le clavier). */
    class Permise(val pkg: String, val nom: String)
    @Volatile private var permisesCache: List<Permise> = emptyList()
    @Volatile private var permisesA = 0L
    @Volatile private var permisPaquets: Set<String> = emptySet()

    /** Une appli installée ou supprimée : la liste sera recalculée au prochain appel. */
    fun oublierPermises() { permisesA = 0L }

    fun toujoursPermises(ctx: Context): List<Permise> {
        val now = System.currentTimeMillis()
        if (permisesA != 0L && now - permisesA < 120_000) return permisesCache
        val pm = ctx.packageManager
        val l = ArrayList<Permise>()
        fun ajouter(pkg: String?, nom: String) {
            if (pkg.isNullOrEmpty() || pkg == "android" || l.any { it.pkg == pkg }) return
            if (pm.getLaunchIntentForPackage(pkg) != null) l.add(Permise(pkg, nom))
        }
        fun parIntent(i: android.content.Intent): String? = try { pm.resolveActivity(i, 0)?.activityInfo?.packageName } catch (_: Exception) { null }
        try { ajouter((ctx.getSystemService(Context.TELECOM_SERVICE) as? android.telecom.TelecomManager)?.defaultDialerPackage, ctx.getString(R.string.etat_appli_telephone)) } catch (_: Exception) {}
        ajouter(parIntent(android.content.Intent(android.content.Intent.ACTION_DIAL)), ctx.getString(R.string.etat_appli_telephone))
        try { ajouter(android.provider.Telephony.Sms.getDefaultSmsPackage(ctx), ctx.getString(R.string.etat_appli_messages)) } catch (_: Exception) {}
        ajouter(parIntent(android.content.Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)), ctx.getString(R.string.etat_appli_reveil))
        for (p in listOf("com.google.android.deskclock", "com.android.deskclock", "com.sec.android.app.clockpackage")) ajouter(p, ctx.getString(R.string.etat_appli_reveil))
        for (p in listOf("com.IndexEducation.Pronote", "com.indexeducation.pronote")) ajouter(p, "Pronote")
        // Celles que les parents ont choisies (musique pour s'endormir…), si elles ne sont pas coupées par ailleurs.
        try {
            for (i in Accueil.applicationsInstallees(ctx)) { val pk = i.activityInfo.packageName
                if (pk.lowercase(Locale.ROOT) in toujours && pk.lowercase(Locale.ROOT) !in apps) ajouter(pk, Usage.libelle(ctx, pk)) }
        } catch (_: Exception) {}
        val paquets = HashSet<String>(l.map { it.pkg })
        // Les claviers : sans eux, impossible d'écrire un message.
        try { (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).enabledInputMethodList.forEach { paquets.add(it.packageName) } } catch (_: Exception) {}
        paquets.addAll(listOf("com.android.contacts", "com.google.android.contacts", "com.samsung.android.app.contacts"))
        permisPaquets = paquets; permisesCache = l; permisesA = now
        return l
    }

    fun charger(ctx: Context) {
        val cfg = Config(ctx)
        if (cfg.etat.isNotEmpty()) try { appliquer(JSONObject(cfg.etat), sauver = null) } catch (_: Exception) {}
        chargerListeNoire(ctx)
    }

    fun appliquer(r: JSONObject, sauver: Config?) {
        verrouille = r.optString("action") == "lock"
        message = r.optString("message", "")
        avertissement = r.optInt("warn", 0)
        poll = r.optInt("poll", 20).coerceIn(5, 300)
        apps = ensemble(r.optJSONArray("apps"))
        ensemble(r.optJSONArray("toujours")).let { if (it != toujours) { toujours = it; permisesA = 0L } }
        sites = ensemble(r.optJSONArray("sites"))
        exceptions = ensemble(r.optJSONArray("exceptions"))
        raisons = run {
            val motifs = liste(r.optJSONArray("motifs"))
            val idx = r.optJSONObject("raisons") ?: return@run emptyMap()
            val m = HashMap<String, String>()
            for (k in idx.keys()) motifs.getOrNull(idx.optInt(k, -1))?.let { m[k.lowercase(Locale.ROOT)] = it }
            m
        }
        dns = liste(r.optJSONArray("dns"))
        safeSearch = r.optBoolean("safesearch", false)
        parent = r.optBoolean("parent", false)
        soutien = Soutien.lire(r.optJSONObject("soutien"))
        prochainChangement = r.optString("next_change", "").let { lireDate(it) }
        derniereReponse = System.currentTimeMillis()
        sauver?.etat = r.toString()
    }

    /** Le motif de fermeture d'une appli ou d'un site (texte pour l'enfant), ou "". */
    fun raisonDe(nom: String): String {
        var d = nom.lowercase(Locale.ROOT).trimEnd('.')
        while (true) {
            raisons[d]?.let { return it.substringAfter('|') }
            if (!d.contains('.')) return ""
            d = d.substringAfter('.')
        }
    }

    /** Le code du motif (plage, regle, age…) d'une appli ou d'un site fermé, ou "". */
    fun codeRaisonDe(nom: String): String {
        var d = nom.lowercase(Locale.ROOT).trimEnd('.')
        while (true) {
            raisons[d]?.let { return if (it.contains('|')) it.substringBefore('|') else "" }
            if (!d.contains('.')) return ""
            d = d.substringAfter('.')
        }
    }

    /** Hors ligne, un verrouillage dont l'échéance est passée se lève de lui-même. */
    fun verrouilleEffectif(): Boolean {
        if (!verrouille) return false
        if (!(Modules.actifEnMemoire(Modules.PLAGES) || Modules.actifEnMemoire(Modules.TEMPS) || Modules.actifEnMemoire(Modules.BLOCAGE))) return false
        val fin = prochainChangement
        return !(fin > 0 && System.currentTimeMillis() > fin && horsLigne())
    }

    fun horsLigne(): Boolean = System.currentTimeMillis() - derniereReponse > 120_000

    fun doitBloquer(ctx: Context, pkg: String?): String? {
        if (pkg.isNullOrEmpty() || pkg == ctx.packageName) return null
        if (Config(ctx).modeParent) return null
        if (pkg in toujoursPermis) return null
        // La désinstallation d'une appli demandée depuis le menu de Vision : l'écran de confirmation
        // du système passe, même appareil fermé. Jamais celle de Vision (Paquets.desinstaller la refuse).
        if (Paquets.desinstallationLaissee(ctx, pkg)) return null
        // Vision est en train de se mettre à jour : l'écran d'installation du système passe,
        // même appareil fermé. (Une désinstallation reste arrêtée par la protection des réglages.)
        if (pkg in MiseAJour.INSTALLATEURS && MiseAJour.installationPropreEnCours()) return null
        if (Migration.ecranAutorise(ctx, pkg, "")) return null
        if (Assistant.autorise(ctx, pkg)) return null
        if (Deverrouillage.appPermise(ctx, pkg)) return null
        toujoursPermises(ctx)
        if (pkg in permisPaquets) return null
        val p = pkg.lowercase(Locale.ROOT)
        // Planning, temps d'écran et blocage coupés dans Home Assistant : plus rien ne se ferme à ce titre,
        // même avec d'anciennes règles encore en mémoire (relevé hors ligne).
        if (!Modules.regles(Modules.actifs(ctx))) return null
        // Appareil fermé : l'accueil reste accessible (on y voit ce qui reste ouvert), seules les applis se ferment.
        if (verrouilleEffectif()) return if (pkg in Usage.lanceurs(ctx)) null else message.ifEmpty { ctx.getString(R.string.etat_acces_ferme) }
        if (p in apps) return ctx.getString(R.string.etat_appli_bloquee)
        return null
    }

    /** Domaine bloqué ? Le plus précis l'emporte : une exception sur un sous-domaine passe. */
    fun domaineBloque(hote: String): Pair<Boolean, Boolean> {
        // Filtrage coupé : le VPN s'arrête ; en attendant, rien n'est plus refusé. Sans planning, temps ni blocage,
        // les sites des règles ne comptent plus, la liste noire (filtrage) reste.
        if (!Modules.actifEnMemoire(Modules.FILTRAGE)) return false to false
        val regles = Modules.actifEnMemoire(Modules.PLAGES) || Modules.actifEnMemoire(Modules.TEMPS) || Modules.actifEnMemoire(Modules.BLOCAGE)
        var d = hote.lowercase(Locale.ROOT).trimEnd('.')
        while (d.contains('.')) {
            if (d in exceptions) return false to false
            if (regles && d in sites) return true to false
            if (d in listeNoire) return true to true
            d = d.substringAfter('.')
        }
        return false to false
    }

    fun majListeNoire(ctx: Context, version: String, domaines: JSONArray) {
        val s = HashSet<String>(domaines.length() * 2)
        for (i in 0 until domaines.length()) s.add(domaines.optString(i).lowercase(Locale.ROOT))
        listeNoire = s
        File(ctx.filesDir, "liste_noire.txt").writeText(s.joinToString("\n"))
        Config(ctx).versionListeNoire = version
    }

    private fun chargerListeNoire(ctx: Context) {
        val f = File(ctx.filesDir, "liste_noire.txt")
        if (f.exists()) listeNoire = f.readLines().filter { it.isNotBlank() }.toHashSet()
    }

    private fun ensemble(a: JSONArray?): Set<String> =
        liste(a).map { it.lowercase(Locale.ROOT) }.toHashSet()

    private fun liste(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        return (0 until a.length()).mapNotNull { a.optString(it).trim().ifEmpty { null } }
    }

    fun lireDate(iso: String): Long {
        if (iso.isEmpty()) return 0
        // Python : 2026-10-01T21:30:00+02:00 (ou avec microsecondes).
        val propre = iso.replace(Regex("\\.\\d+"), "").replace(Regex("([+-]\\d{2}):(\\d{2})$"), "$1$2")
        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ROOT).parse(propre)?.time ?: 0
        } catch (_: Exception) { 0 }
    }

    /** L'heure à la façon du téléphone : langue et réglage 12/24 h (« 21:30 », « 9:30 PM »). */
    fun heure(ms: Long): String = Tr.heure(ms)

    /** « à 21:30 », « demain à 07:00 », « le mardi 14 octobre à 07:00 » (formes de la langue de l'appareil). */
    fun quand(ms: Long): String {
        val c = java.util.Calendar.getInstance(); val auj = c.get(java.util.Calendar.DAY_OF_YEAR) to c.get(java.util.Calendar.YEAR)
        c.add(java.util.Calendar.DAY_OF_YEAR, 1); val dem = c.get(java.util.Calendar.DAY_OF_YEAR) to c.get(java.util.Calendar.YEAR)
        c.timeInMillis = ms; val jour = c.get(java.util.Calendar.DAY_OF_YEAR) to c.get(java.util.Calendar.YEAR)
        return when (jour) {
            auj -> Tr.s(R.string.etat_quand_aujourdhui, heure(ms))
            dem -> Tr.s(R.string.etat_quand_demain, heure(ms))
            else -> Tr.s(R.string.etat_quand_date, Tr.date(ms, "EEEEdMMMM"), heure(ms))
        }
    }
}
