// SPDX-License-Identifier: MIT
package org.soprez.vision

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Réveil (lot 4) : la partie pure du moteur, sans Android (essayée en JUnit, ReveilsModeleTest).
 *
 * Contrat : docs/reveil-contrat-appareils.md (dépôt HA). Home Assistant envoie dans le relevé un bloc `reveils`
 * (occurrences sur 48 h, ordres) ; l'appareil arme ses alarmes lui-même et sonne même sans HA ni réseau.
 * Une occurrence s'arme à `date` + `heure_locale` dans le fuseau DE L'APPAREIL, jamais à un instant UTC figé :
 * un changement d'heure ou de fuseau se recalcule ici.
 */
object ReveilsModele {
    data class Source(val type: String, val uri: String) {
        /** Ce que l'appareil sait jouer lui-même ; « ma » (Music Assistant) se joue en V1 sur la sonnerie locale. */
        val flux: Boolean get() = (type == "url" || type == "video") && uri.startsWith("http", ignoreCase = true)
    }

    data class Occ(val id: String, val reveil: String, val date: String, val heure: String, val iso: String)

    data class Reveil(
        val id: String, val nom: String, val signature: String, val occs: List<Occ>, val source: Source,
        val volDebut: Int, val volFin: Int, val monteeS: Int, val repMin: Int, val repMax: Int,
        val arretAutoMin: Int, val allumerTv: Boolean,
    )

    data class Ordre(val id: String, val type: String, val reveil: String, val occurrence: String, val minutes: Int,
                     val jusqua: String, val par: String, val brut: JSONObject)

    data class Bloc(val actif: Boolean, val version: String, val synchroAvantMin: Int, val reveils: List<Reveil>,
                    val ordres: List<Ordre>)

    const val SYNCHRO_DEFAUT = 10
    const val REPLI_APRES_MS = 20_000L

    // ------------------------------------------------------------------ Lecture du relevé

    fun lireReveil(o: JSONObject, occsAussi: Boolean = true): Reveil {
        val id = o.optString("id")
        val occs = ArrayList<Occ>()
        if (occsAussi) o.optJSONArray("occurrences")?.let { a ->
            for (i in 0 until a.length()) {
                val x = a.optJSONObject(i) ?: continue
                val date = x.optString("date"); val heure = x.optString("heure_locale")
                if (date.length == 10 && heure.length == 5) occs.add(Occ(x.optString("id", "$id@$date"), id, date, heure, x.optString("heure")))
            }
        }
        val s = o.optJSONObject("source")
        val v = o.optJSONObject("volume"); val r = o.optJSONObject("repetition")
        return Reveil(
            id = id, nom = o.optString("nom"), signature = o.optString("signature"), occs = occs,
            source = Source(s?.optString("type", "sonnerie") ?: "sonnerie", s?.optString("uri") ?: ""),
            volDebut = (v?.optInt("debut", 10) ?: 10).coerceIn(0, 100), volFin = (v?.optInt("fin", 60) ?: 60).coerceIn(0, 100),
            monteeS = (v?.optInt("montee_s", 180) ?: 180).coerceIn(0, 1800),
            repMin = (r?.optInt("minutes", 9) ?: 9).coerceIn(1, 60), repMax = (r?.optInt("max", 3) ?: 3).coerceIn(0, 10),
            arretAutoMin = o.optInt("arret_auto_min", 30).coerceIn(1, 120), allumerTv = o.optBoolean("allumer_tv", true),
        )
    }

    /** Le bloc du relevé ; null s'il est absent (HA sans Réveil, ou ancien). */
    fun lire(o: JSONObject?): Bloc? {
        if (o == null) return null
        val reveils = ArrayList<Reveil>()
        o.optJSONArray("liste")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { reveils.add(lireReveil(it)) } }
        val ordres = ArrayList<Ordre>()
        o.optJSONArray("ordres")?.let { a ->
            for (i in 0 until a.length()) {
                val x = a.optJSONObject(i) ?: continue
                ordres.add(Ordre(x.optString("id"), x.optString("type"), x.optString("reveil"), x.optString("occurrence"),
                    x.optInt("minutes", 0), x.optString("jusqu_a"), x.optString("par"), x))
            }
        }
        return Bloc(o.optBoolean("actif", false), o.optString("version"), o.optInt("synchro_avant_min", SYNCHRO_DEFAUT).coerceIn(1, 60),
            if (o.optBoolean("actif", false)) reveils else emptyList(), ordres)
    }

    /** Faut-il tout désarmer ? Module absent de `modules` (liste reçue), bloc coupé, ou bloc disparu alors qu'on avait des réveils. */
    fun desarmerTout(modules: Set<String>?, bloc: Bloc?, avaitDesReveils: Boolean): Boolean = when {
        !Modules.actifDans(modules, Modules.REVEIL) -> true
        bloc == null -> avaitDesReveils && modules != null
        else -> !bloc.actif
    }

    // ------------------------------------------------------------------ Heures

    /** L'instant d'une occurrence : date + heure locale dans ce fuseau. Une heure qui n'existe pas (passage à l'heure
     *  d'été, 02:30) devient l'heure qui suit (03:30) ; une heure double est la première. */
    fun instant(date: String, heure: String, tz: TimeZone): Long {
        val c = Calendar.getInstance(tz, Locale.ROOT)
        c.isLenient = true
        c.clear()
        c.set(date.substring(0, 4).toInt(), date.substring(5, 7).toInt() - 1, date.substring(8, 10).toInt(),
            heure.substring(0, 2).toInt(), heure.substring(3, 5).toInt(), 0)
        return c.timeInMillis
    }

    /** Toutes les occurrences à venir (après `maintenant`, sauf celles déjà sonnées), triées. */
    fun aVenir(bloc: Bloc?, maintenant: Long, tz: TimeZone, sonnees: Set<String> = emptySet()): List<Pair<Occ, Long>> =
        (bloc?.reveils ?: emptyList()).flatMap { r -> r.occs.map { it to instant(it.date, it.heure, tz) } }
            .filter { (o, t) -> t > maintenant && o.id !in sonnees }.sortedBy { it.second }

    fun prochaine(bloc: Bloc?, maintenant: Long, tz: TimeZone, sonnees: Set<String> = emptySet()): Pair<Occ, Long>? =
        aVenir(bloc, maintenant, tz, sonnees).firstOrNull()

    /** La dernière occurrence manquée (appareil éteint, redémarré, heure changée) encore à rattraper : passée depuis
     *  moins que l'arrêt automatique de son réveil, et pas encore sonnée. null : rien à rattraper. */
    fun manquee(bloc: Bloc?, maintenant: Long, tz: TimeZone, sonnees: Set<String>): Pair<Occ, Long>? =
        (bloc?.reveils ?: emptyList()).flatMap { r -> r.occs.map { Triple(it, instant(it.date, it.heure, tz), r.arretAutoMin) } }
            .filter { (o, t, auto) -> t <= maintenant && maintenant - t < auto * 60_000L && o.id !in sonnees }
            .maxByOrNull { it.second }?.let { it.first to it.second }

    /** Un report gardé : à réarmer s'il est à venir, à sonner tout de suite s'il vient de passer (moins de 30 min), sinon oublié. */
    fun sortReport(t: Long, maintenant: Long): String = when {
        t > maintenant -> "armer"
        maintenant - t < 30 * 60_000L -> "sonner"
        else -> "oublier"
    }

    /** L'alarme de synchronisation (relevé forcé) : H − synchro_avant_min, si elle est encore à venir. */
    fun synchro(prochaine: Long, synchroAvantMin: Int, maintenant: Long): Long? =
        (prochaine - synchroAvantMin * 60_000L).takeIf { it > maintenant }

    // ------------------------------------------------------------------ Son

    /** Le volume du lecteur (0..1) quand le flux « alarme » est réglé au volume de fin : de debut/fin à 1 en montee_s. */
    fun volume(r: Reveil, ecouleMs: Long): Float {
        if (r.volFin <= 0) return 0f
        val depart = (r.volDebut.toFloat() / r.volFin).coerceIn(0f, 1f)
        if (r.monteeS <= 0) return 1f
        val t = (ecouleMs / (r.monteeS * 1000f)).coerceIn(0f, 1f)
        return depart + (1f - depart) * t
    }

    /** Le cran du flux « alarme » pour le volume de fin (au moins 1 s'il n'est pas nul). */
    fun cranAlarme(volFin: Int, max: Int): Int = if (volFin <= 0) 0 else ((volFin / 100f) * max).toInt().coerceIn(1, max)

    fun peutRepeter(r: Reveil, reportsFaits: Int): Boolean = reportsFaits < r.repMax

    // ------------------------------------------------------------------ Remontée vers HA

    fun recu(bloc: Bloc?): JSONObject = JSONObject().also { o -> bloc?.reveils?.forEach { o.put(it.id, it.signature) } }

    fun armees(bloc: Bloc?, maintenant: Long, tz: TimeZone, sonnees: Set<String>): JSONArray =
        JSONArray().also { a -> aVenir(bloc, maintenant, tz, sonnees).forEach { a.put(it.first.id) } }

    /** Un événement (contrat §4) : eid unique, horodatage de l'appareil, champs vides retirés. */
    fun evenement(type: String, reveil: String, occurrence: String, maintenant: Long, tz: TimeZone,
                  vararg champs: Pair<String, Any?>): JSONObject = JSONObject().apply {
        put("eid", UUID.randomUUID().toString())
        put("type", type); put("reveil", reveil); put("occurrence", occurrence)
        put("horodatage_appareil", iso(maintenant, tz))
        champs.forEach { (k, v) -> if (v != null && v != "") put(k, v) }
    }

    /** « 2026-10-12T07:00:02+02:00 ». */
    fun iso(ms: Long, tz: TimeZone): String {
        val c = Calendar.getInstance(tz, Locale.ROOT).apply { timeInMillis = ms }
        val dec = tz.getOffset(ms) / 60_000
        val signe = if (dec < 0) "-" else "+"
        return String.format(Locale.ROOT, "%04d-%02d-%02dT%02d:%02d:%02d%s%02d:%02d",
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND), signe, Math.abs(dec) / 60, Math.abs(dec) % 60)
    }

    /** Un instant ISO (avec décalage) en millisecondes ; 0 s'il est illisible. */
    fun lireIso(s: String): Long = try {
        val m = Regex("""(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?(?:\.\d+)?(Z|[+-]\d{2}:\d{2})""").find(s) ?: return 0
        val g = m.groupValues
        val tz = TimeZone.getTimeZone("GMT" + (if (g[7] == "Z") "+00:00" else g[7]))
        Calendar.getInstance(tz, Locale.ROOT).apply {
            clear(); set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].ifEmpty { "0" }.toInt())
        }.timeInMillis
    } catch (_: Exception) { 0 }

    /** File d'événements : on ajoute, on garde les 50 derniers ; acquitter retire ceux que HA a reçus. */
    fun ajouter(file: JSONArray, ev: JSONObject): JSONArray {
        val l = (0 until file.length()).mapNotNull { file.optJSONObject(it) } + ev
        return JSONArray(l.takeLast(50))
    }

    fun acquitter(file: JSONArray, eids: Set<String>): JSONArray =
        JSONArray((0 until file.length()).mapNotNull { file.optJSONObject(it) }.filter { it.optString("eid") !in eids })

    // ------------------------------------------------------------------ Écrans « Réveils »

    /** Les jours cochés (0 = lundi … 6 = dimanche), lus d'un déclenchement hebdo ; défaut lundi → vendredi. */
    fun jours(decl: JSONObject?): Set<Int> {
        val a = decl?.optJSONArray("jours") ?: return setOf(0, 1, 2, 3, 4)
        return (0 until a.length()).map { a.optInt(it, -1) }.filter { it in 0..6 }.toSet()
    }

    /** « 07:05 » à partir d'heures et minutes. */
    fun hhmm(h: Int, m: Int): String = String.format(Locale.ROOT, "%02d:%02d", h.coerceIn(0, 23), m.coerceIn(0, 59))
}
