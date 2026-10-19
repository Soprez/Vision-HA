// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Le dessin de l'écran de veille : un fond beige clair où flottent de douces
 * lueurs et quelques poussières de lumière, et des tableaux qui entrent en
 * scène les uns après les autres. Chaque chose s'anime : les chiffres
 * comptent, les cartes se posent, le soleil tourne, la pluie tombe, la
 * courbe se trace. Tout est dessiné à la main sur un Canvas : pas de
 * bibliothèque, pas d'image, rien à charger sur une vieille télé.
 *
 * La cadence ne dépend pas d'`onDraw` : une horloge interne redessine
 * trente fois par seconde tant que l'écran de veille vit, et reprend
 * d'elle-même si la vue a été cachée un moment. C'est ce qui empêche
 * l'heure de rester figée.
 */
class VeilleView(ctx: Context) : View(ctx) {

    // ------------------------------------------------------------ données

    private class Tuile(val titre: String, val valeur: String, val unite: String, val detail: String, val alerte: Boolean, val theme: String)
    private class Prevision(val jour: String, val etat: String, val max: Double?, val min: Double?, val pluie: Double?)
    private class Personne(val prenom: String, val minutes: Int, val enLigne: Int, val verrouilles: Int, val total: Int)
    private class Serie(val nom: String, val temps: LongArray, val valeurs: FloatArray)
    private class Camera(val id: String, val nom: String)
    private class Resultat(val id: String, val e1: String, val e2: String, val s1: Int?, val s2: Int?, val v: Int,
                           val l1: String, val l2: String, val evenement: String, val serie: String, val ts: Long)
    private class Jeu(val nom: String, val resultats: List<Resultat>)
    private class Place(val pos: Int, val pilote: String, val equipe: String, val logo: String)
    private class Epreuve(val id: String, val nom: String, val lieu: String, val manche: Int, val ts: Long, val podium: List<Place>)
    private class Sport(val nom: String, val epreuves: List<Epreuve>)
    private class Cours(val debut: String, val fin: String, val matiere: String, val salle: String, val annule: Boolean, val couleur: Int)
    private class Ecole(val prenom: String, val jour: String, val demain: Boolean, val debut: String, val fin: String,
                        val cours: List<Cours>, val devoirs: List<Pair<String, String>>, val controles: List<Pair<String, String>>)
    private class Rdv(val titre: String, val calendrier: String, val ts: Long, val journee: Boolean, val demain: Boolean, val heure: String, val lieu: String)
    private class Avenir(val id: String, val sport: String, val titre: String, val detail: String, val ts: Long, val l1: String, val l2: String, val direct: Boolean)
    private class Chauffage(val consigne: Double?, val action: String, val dedans: Double?, val dehors: Double?, val bruleur: String, val bruleurOn: Boolean,
                            val mois: Double?, val saison: Double?, val jours: List<Pair<String, Float>>)
    private class Pluie(val points: List<Pair<Int, Int>>, val dans: Int?, val pluie: Boolean)
    private class Batterie(val id: String, val nom: String, val niveau: Int, val charge: Boolean?, val telephone: Boolean, val pente: Double?)
    private class Case(val entite: String, val nom: String, val rendu: String, val valeur: Double?, val unite: String, val texte: String, val on: Boolean?,
                       val classe: String, val min: Double, val max: Double, val serie: List<Pair<Long, Float>>, val consigne: Double?,
                       /** Tableaux de bord Home Assistant : largeur de la carte (sur 12) et hauteur (en rangées). */
                       val largeur: Int = 6, val hauteur: Int = 1,
                       /** La carte du tableau de bord d'où vient la case (pour la masquer sur cet appareil). */
                       val carte: String = "",
                       /** Veille-lot : une case typée (météo, agenda, image, radar, caméra, iframe, derniers passages). */
                       val typee: CasesVeille.Typee? = null,
                       /** l'auteur (15/10) : une ouverture (porte, fenêtre, portail, store, garage, serrure, vanne) et son type. */
                       val ouverture: Boolean = false, val typeOuverture: String = "")
    private class Section(val titre: String, val cases: List<Case>)
    private class Def(val code: String, val dureeMs: Long, val id: String, val titre: String, val cases: List<Case>,
                      val sections: List<Section> = emptyList(), val colonnes: Int = 3)
    /** Un écran d'un tableau de bord : des colonnes de blocs (un titre, des rangées de cases). */
    private class BlocDash(val titre: String, val rangees: List<List<Case>>, val unites: Float)
    private class PageDash(val def: Def, val colonnes: List<List<BlocDash>>, val unitesMax: Int, val numero: Int, val total: Int) {
        /** Veille-lot : les cases typées de cet écran, dans l'ordre (médias à charger, caméra à montrer en direct). */
        val typees: List<CasesVeille.Typee> = colonnes.flatMap { col -> col.flatMap { b -> b.rangees.flatMap { r -> r.mapNotNull { it.typee } } } }
    }
    private class Demande(val prenom: String, val libelle: String, val genre: String, val ts: Long)

    private class Donnees(j: JSONObject) {
        val meteo = j.optJSONObject("meteo") ?: JSONObject()
        val etatMeteo = meteo.optString("etat", "")
        val temperature = meteo.optDoubleOrNull("temperature")
        val ressenti = meteo.optDoubleOrNull("ressenti")
        val humidite = meteo.optDoubleOrNull("humidite")
        val vent = meteo.optDoubleOrNull("vent")
        val previsions: List<Prevision> = meteo.optJSONArray("previsions").let { a ->
            (0 until (a?.length() ?: 0)).map { i ->
                val p = a!!.getJSONObject(i)
                Prevision(p.optString("jour"), p.optString("etat"), p.optDoubleOrNull("max"), p.optDoubleOrNull("min"), p.optDoubleOrNull("pluie"))
            }
        }
        val vigilance = j.optJSONObject("vigilance") ?: JSONObject()
        val niveau = vigilance.optString("niveau", "")
        val rang = vigilance.optInt("rang", 0)
        val phenomenes: List<Pair<String, String>> = vigilance.optJSONObject("details").let { d ->
            d?.keys()?.asSequence()?.map { it to d.optString(it) }?.toList() ?: emptyList()
        }
        val tuiles: List<Tuile> = j.optJSONArray("tuiles").let { a ->
            (0 until (a?.length() ?: 0)).map { i ->
                val t = a!!.getJSONObject(i)
                Tuile(t.optString("titre"), t.optString("valeur"), CasesVeille.unite(t.optString("unite")), t.optString("detail"), t.optBoolean("alerte"), t.optString("theme", ""))
            }
        }
        val courbeHeures = (j.optJSONObject("courbe") ?: JSONObject()).optInt("heures", 24)
        val series: List<Serie> = (j.optJSONObject("courbe") ?: JSONObject()).optJSONArray("series").let { a ->
            (0 until (a?.length() ?: 0)).map { i ->
                val s = a!!.getJSONObject(i)
                val pts = s.optJSONArray("points")
                val n = pts?.length() ?: 0
                val temps = LongArray(n); val valeurs = FloatArray(n)
                for (k in 0 until n) {
                    val pt = pts!!.getJSONArray(k)
                    temps[k] = pt.getLong(0); valeurs[k] = pt.getDouble(1).toFloat()
                }
                Serie(s.optString("nom"), temps, valeurs)
            }.filter { it.temps.size >= 2 }
        }
        val cameras: List<Camera> = j.optJSONArray("cameras").let { a ->
            (0 until (a?.length() ?: 0)).map { i -> val c = a!!.getJSONObject(i); Camera(c.optString("id"), c.optString("nom")) }
        }
        val esports: List<Jeu> = j.optJSONArray("esports").let { a ->
            (0 until (a?.length() ?: 0)).map { i ->
                val g = a!!.getJSONObject(i)
                val rs = g.optJSONArray("resultats")
                Jeu(g.optString("jeu"), (0 until (rs?.length() ?: 0)).map { k ->
                    val r = rs!!.getJSONObject(k)
                    Resultat(r.optString("id"), r.optString("e1"), r.optString("e2"),
                        if (r.isNull("s1")) null else r.optInt("s1"), if (r.isNull("s2")) null else r.optInt("s2"),
                        r.optInt("v"), r.optString("l1"), r.optString("l2"), r.optString("evenement"), r.optString("serie"), r.optLong("ts"))
                })
            }.filter { it.resultats.isNotEmpty() }
        }
        val courses: List<Sport> = j.optJSONArray("courses").let { a ->
            (0 until (a?.length() ?: 0)).map { i ->
                val g = a!!.getJSONObject(i)
                val es = g.optJSONArray("epreuves")
                Sport(g.optString("sport"), (0 until (es?.length() ?: 0)).map { k ->
                    val e = es!!.getJSONObject(k)
                    val ps = e.optJSONArray("podium")
                    Epreuve(e.optString("id"), e.optString("nom"), e.optString("lieu"), e.optInt("manche"), e.optLong("ts"),
                        (0 until (ps?.length() ?: 0)).map { q -> val pl = ps!!.getJSONObject(q); Place(pl.optInt("pos"), pl.optString("pilote"), pl.optString("equipe"), pl.optString("logo")) })
                }.filter { it.podium.isNotEmpty() })
            }.filter { it.epreuves.isNotEmpty() }
        }
        val musique = j.optString("musique", "")
        val reglages = j.optJSONObject("reglages") ?: JSONObject()
        val theme = reglages.optString("theme", "Beige")
        val nuit = reglages.optString("nuit", "")
        val ecole: List<Ecole> = j.optJSONArray("ecole").let { a ->
            (0 until (a?.length() ?: 0)).map { i ->
                val e = a!!.getJSONObject(i)
                fun paires(cle: String, k1: String, k2: String): List<Pair<String, String>> {
                    val arr = e.optJSONArray(cle)
                    return (0 until (arr?.length() ?: 0)).map { q -> val o = arr!!.getJSONObject(q); o.optString(k1) to o.optString(k2) }
                }
                val cs = e.optJSONArray("cours")
                Ecole(e.optString("prenom"), e.optString("jour"), e.optBoolean("demain"), e.optString("debut"), e.optString("fin"),
                    (0 until (cs?.length() ?: 0)).map { q ->
                        val o = cs!!.getJSONObject(q)
                        Cours(o.optString("debut"), o.optString("fin"), o.optString("matiere"), o.optString("salle"), o.optBoolean("annule"),
                            try { Color.parseColor(o.optString("couleur", "#999999")) } catch (_: Exception) { 0xFF999999.toInt() })
                    }, paires("devoirs", "matiere", "texte"), paires("controles", "matiere", "nom"))
            }
        }
        val agenda: List<Rdv> = j.optJSONArray("agenda").let { a ->
            (0 until (a?.length() ?: 0)).map { i -> val o = a!!.getJSONObject(i); Rdv(o.optString("titre"), o.optString("calendrier"), o.optLong("ts"), o.optBoolean("journee"), o.optBoolean("demain"), o.optString("heure"), o.optString("lieu")) }
        }
        val avenir: List<Avenir> = j.optJSONArray("avenir").let { a ->
            (0 until (a?.length() ?: 0)).map { i -> val o = a!!.getJSONObject(i); Avenir(o.optString("id"), o.optString("sport"), o.optString("titre"), o.optString("detail"), o.optLong("ts"), o.optString("l1"), o.optString("l2"), o.optBoolean("direct")) }
        }
        val chauffage: Chauffage? = j.optJSONObject("chauffage")?.let { o ->
            val js = o.optJSONArray("jours")
            Chauffage(o.optDoubleOrNull("consigne"), o.optString("action"), o.optDoubleOrNull("dedans"), o.optDoubleOrNull("dehors"), o.optString("bruleur"), o.optBoolean("bruleur_on"),
                o.optDoubleOrNull("mois"), o.optDoubleOrNull("saison"), (0 until (js?.length() ?: 0)).map { q -> val x = js!!.getJSONObject(q); x.optString("jour") to x.optDouble("litres", 0.0).toFloat() })
        }
        val pluie: Pluie? = j.optJSONObject("pluie")?.let { o ->
            val ps = o.optJSONArray("points")
            Pluie((0 until (ps?.length() ?: 0)).map { q -> val x = ps!!.getJSONObject(q); x.optInt("min") to x.optInt("niveau") }, if (o.isNull("dans")) null else o.optInt("dans"), o.optBoolean("pluie"))
        }
        val demandes: List<Demande> = j.optJSONArray("demandes").let { a ->
            (0 until (a?.length() ?: 0)).map { i -> val o = a!!.getJSONObject(i); Demande(o.optString("prenom"), o.optString("libelle"), o.optString("genre"), o.optLong("ts")) }
        }
        val photos: List<String> = j.optJSONArray("photos").let { a -> (0 until (a?.length() ?: 0)).map { i -> a!!.optString(i) } }
        // L'ordre, la durée et le contenu des tableaux, décidés dans Home Assistant (absent = ordre d'origine).
        val defs: List<Def>? = j.optJSONObject("tableaux")?.optJSONArray("liste")?.let { a ->
            // Les tableaux de Home Assistant (déjà filtrés pour cet écran et ce public), puis ceux composés ici.
            val tous = ArrayList<JSONObject>()
            for (i in 0 until a.length()) tous.add(a.getJSONObject(i))
            // Veille-lot : boutons, scripts, scènes et champs de saisie ne sont jamais montrés (un Home Assistant ancien les
            // envoie encore) ; les unités d'un Home Assistant ancien (« d ») passent dans la langue de l'appareil.
            fun casesDe(cs: org.json.JSONArray?): List<Case> = (0 until (cs?.length() ?: 0)).mapNotNull { q ->
                val c = cs!!.getJSONObject(q)
                if (CasesVeille.exclue(c.optString("entite"))) return@mapNotNull null
                val sr = c.optJSONArray("serie")
                // 1.93.06 : un débit réseau en Mbit/s, quelle que soit l'unité de l'entité (Debit) — valeur, bornes et courbe.
                val u = c.optString("unite"); val f = Debit.facteur(u) ?: 1.0
                Case(c.optString("entite"), c.optString("nom"), c.optString("rendu", "texte"), c.optDoubleOrNull("valeur")?.times(f), CasesVeille.unite(Debit.unite(u)), c.optString("texte"),
                    if (c.isNull("on")) null else c.optBoolean("on"), c.optString("classe"), c.optDouble("min", 0.0) * f, c.optDouble("max", 100.0) * f,
                    (0 until (sr?.length() ?: 0)).map { k -> val pt = sr!!.getJSONArray(k); pt.getLong(0) to (pt.getDouble(1) * f).toFloat() }, c.optDoubleOrNull("consigne")?.times(f),
                    c.optInt("largeur", 6).coerceIn(1, 12), c.optInt("hauteur", 1).coerceIn(1, 3), c.optString("carte"), CasesVeille.lire(c),
                    c.optBoolean("ouverture"), c.optString("type_ouverture"))
            }
            tous.map { o ->
                val ss = o.optJSONArray("sections")
                Def(o.optString("code"), o.optInt("duree", 20) * 1000L, o.optString("id"), o.optString("titre"), casesDe(o.optJSONArray("cases")),
                    (0 until (ss?.length() ?: 0)).map { q -> val s = ss!!.getJSONObject(q); Section(s.optString("titre"), casesDe(s.optJSONArray("cases"))) }.filter { it.cases.isNotEmpty() },
                    o.optInt("colonnes", 3))
            }
        }
        val batteries: List<Batterie> = j.optJSONArray("batteries").let { a ->
            (0 until (a?.length() ?: 0)).map { i -> val o = a!!.getJSONObject(i); Batterie(o.optString("id"), o.optString("nom"), o.optInt("niveau"), if (o.isNull("charge")) null else o.optBoolean("charge"), o.optBoolean("telephone"), o.optDoubleOrNull("pente")) }
        }
        val maison: List<Personne> = j.optJSONArray("maison").let { a ->
            (0 until (a?.length() ?: 0)).map { i ->
                val p = a!!.getJSONObject(i)
                val apps = p.optJSONArray("appareils")
                var enLigne = 0; var verrouilles = 0
                for (k in 0 until (apps?.length() ?: 0)) {
                    val x = apps!!.getJSONObject(k)
                    if (x.optBoolean("en_ligne")) enLigne++
                    if (x.optBoolean("verrouille")) verrouilles++
                }
                Personne(p.optString("prenom"), p.optInt("minutes"), enLigne, verrouilles, apps?.length() ?: 0)
            }
        }
    }

    /** La couche vidéo posée au-dessus, quand la télé sait lire les flux. */
    var cameras: CamerasCouche? = null
    /** La page de veille de Home Assistant (les vraies cartes), posée par-dessus ce dessin quand elle est disponible. */
    var web: VeilleWeb? = null
    /** Vrai quand la page recouvre ce dessin : inutile de le repeindre trente fois par seconde dessous. */
    @Volatile var couverte = false
    private var derive = floatArrayOf(0f, 0f)

    @Volatile private var donnees: Donnees? = null
    @Volatile private var erreur = ""
    /** 1.93.06 : la cause est connue (réseau de l'appareil coupé) : on la dit, plutôt que « Home Assistant injoignable ». */
    @Volatile private var reseauCoupe = false
    @Volatile private var derniereReussite = 0L
    @Volatile private var derniereLectureMs = 0L
    private var fil: Thread? = null
    @Volatile private var actif = false

    // ------------------------------------------------------------ tableaux

    private enum class Genre { HORLOGE, METEO, VIGILANCE, MAISON, TUILES, COURBE, CAMERAS, ESPORTS, COURSES, ECOLE, AGENDA, AVENIR, CHAUFFAGE, BATTERIES, PHOTOS, ENTITES, DASH }
    private class Tableau(val genre: Genre, val tuiles: List<Tuile> = emptyList(), val cameras: List<Camera> = emptyList(), val jeu: Jeu? = null,
                          val sport: Sport? = null, val ecole: Ecole? = null, val photos: List<String> = emptyList(), val def: Def? = null, val dureeMs: Long = 0L, val page: PageDash? = null)

    // eSport : les logos, et les résultats déjà montrés (pour fêter seulement les nouveaux).
    private val logos = Logos(ctx)
    private val musique = Musique(ctx)
    private val prefs = ctx.getSharedPreferences("veille", Context.MODE_PRIVATE)
    private var nouveaux: Set<String> = emptySet()
    private var nouveauxPour = -1L

    // Images de caméras : la dernière reçue par caméra, et l'instant de réception.
    private class Image(val bitmap: Bitmap, val recue: Long)
    private val images = java.util.concurrent.ConcurrentHashMap<String, Image>()
    @Volatile private var camerasVoulues: List<Camera> = emptyList()
    private var filCameras: Thread? = null

    private var tableaux: List<Tableau> = listOf(Tableau(Genre.HORLOGE))
    private var indice = 0
    private var changeA = 0L
    private val dureeMs = 15_000L
    private val dureeCourbeMs = 26_000L
    private val dureeCamerasMs = 40_000L
    private val dureeEsportMs = 22_000L
    private val dureePhotosMs = 30_000L
    private var tourPhotos = 0
    // Interaction : tableau figé par l'utilisateur, et instant du dernier geste (pour l'indicateur).
    private var pause = false
    private var interactionA = 0L
    private val traceMs = 12_000L
    private val fonduMs = 900L
    private val depart = SystemClock.uptimeMillis()

    // ------------------------------------------------------------ cadence

    private val main = Handler(Looper.getMainLooper())
    private var dernierControleNuit = 0L
    /**
     * Veille-lot : la cadence suit l'affichage (Choreographer) : 60 images/s pendant les transitions et les entrées en
     * scène, 30 au repos, 30 toujours si l'appareil peine ; une par seconde sous la page de Home Assistant.
     */
    private var intervalle = CadenceVeille.REPOS_MS
    private val peine = SuiviPeine()
    private var derniereImage = 0L
    /** La dérive anti-marquage du moment (pixels entiers), appliquée au dessin comme à la page de Home Assistant. */
    private val deriveXY = IntArray(2)
    private val deriveAmplitude = DeriveVeille.amplitude(resources.displayMetrics.density)
    private val tic = object : Runnable {
        override fun run() {
            if (!actif) return
            val now = SystemClock.uptimeMillis()
            if (now - dernierControleNuit > 60_000 || Local.version != versionReglages) { dernierControleNuit = now; rafraichirTheme() }
            DeriveVeille.decalage(now, deriveAmplitude, deriveXY)
            web?.deriver(deriveXY[0], deriveXY[1])
            if (!couverte) invalidate()
            when {
                couverte -> postDelayed(this, 1000)
                intervalle <= CadenceVeille.FLUIDE_MS -> postOnAnimation(this)
                else -> postOnAnimationDelayed(this, intervalle - CadenceVeille.FLUIDE_MS)
            }
        }
    }
    /** L'appareil a-t-il du mal à suivre ? (caméras : retour aux images en mode Auto) */
    fun peine(): Boolean = peine.peine(SystemClock.uptimeMillis())

    // ------------------------------------------------------------ pinceaux

    internal val fin = Typeface.create("sans-serif-light", Typeface.NORMAL)
    internal val normal = Typeface.create("sans-serif", Typeface.NORMAL)
    internal val gras = Typeface.create("sans-serif-medium", Typeface.BOLD)
    private val pTexte = Paint(Paint.ANTI_ALIAS_FLAG)
    internal val pForme = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pFond = Paint()
    private val chemin = Path()

    // Charte : beige chaud par défaut, pourpre et or de Vision en accents ; d'autres thèmes au choix.
    internal var fond = 0xFFF4ECDE.toInt()
    internal var encre = 0xFF2B1B1E.toInt()
    internal var encre2 = 0xFF6E5A5E.toInt()
    internal var encre3 = 0xFF9E8C8E.toInt()
    internal var carte = 0xB3FFFFFF.toInt()
    internal var carteBord = 0x33B3282D
    internal var pourpre = 0xFFB3282D.toInt()
    internal var or = 0xFFC48A1E.toInt()
    private var halos = intArrayOf(0xF2C56A, 0xE39A9A, 0x9DBBE3)
    private var sombre = false
    private var themeNom = ""
    private var nuitActive = false

    /** Applique un thème par son nom ; « Sombre » sert aussi de mode nuit. */
    private fun appliquerTheme(t: Theme) {
        val signature = "${t.nom}|${t.fond}|${t.carte}|${t.encre}|${t.pourpre}"
        if (signature == themeNom) return
        themeNom = signature
        fond = t.fond; encre = t.encre; encre2 = t.encre2; encre3 = t.encre3
        carte = t.carte; carteBord = t.carteBord; pourpre = t.pourpre; or = t.or
        halos = t.halos; sombre = t.sombre
    }
    private val vert = 0xFF2F8F5B.toInt()
    private val rouge = 0xFFD13B3B.toInt()
    private val bleu = 0xFF3B6FB6.toInt()

    private val dateLongue = SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM"), Locale.getDefault())
    private val heureFmt = android.text.format.DateFormat.getTimeFormat(ctx)
    private val jourCourt = SimpleDateFormat("EEE", Locale.getDefault())
    private val jourIso = SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE)
    private val heureCourte = SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), if (android.text.format.DateFormat.is24HourFormat(ctx)) "H" else "h"), Locale.getDefault())

    // Poussières de lumière : positions et vitesses figées au départ.
    private class Poussiere(val x: Float, val y: Float, val r: Float, val vx: Float, val vy: Float, val phase: Float)
    private val poussieres: List<Poussiere> = (0 until 28).map { i ->
        val g = java.util.Random(7L * i + 3)
        Poussiere(g.nextFloat(), g.nextFloat(), 0.0015f + g.nextFloat() * 0.004f,
            (g.nextFloat() - 0.5f) * 0.004f, -0.002f - g.nextFloat() * 0.004f, g.nextFloat() * 6.28f)
    }

    // ------------------------------------------------------------ cycle de vie

    /** Le clin d'œil du soutien, de temps en temps, dans une vue à part au-dessus de la veille. */
    private val soutien = SoutienVeille(this)

    fun demarrer() {
        if (actif) return
        actif = true
        changeA = SystemClock.uptimeMillis()
        demarreeA = changeA
        // Les couleurs du thème dès la première image : pas d'éclair d'une autre teinte avant la page.
        try { rafraichirTheme() } catch (_: Exception) {}
        fil = Thread {
            val cfg = Config(context)
            while (actif) {
                try {
                    val r = Net.post(context, cfg, "/api/pc_parental/veille",
                        JSONObject().put("id", cfg.id).put("secret", cfg.secret).put("ecran", if (ReglagesTvActivity.estTele(context)) "tele" else "telephone").put("locaux", Local.locaux(context)), 25_000)
                    Local.amorcer(context, r.optJSONObject("reglages"))
                    donnees = Donnees(r)
                    erreur = ""
                    derniereReussite = SystemClock.uptimeMillis()
                    if (premiereReussiteA == 0L) premiereReussiteA = derniereReussite
                    derniereLectureMs = System.currentTimeMillis()
                } catch (e: Exception) {
                    erreur = (e.message ?: e.javaClass.simpleName).take(90)
                    reseauCoupe = ReseauLocal.cause(context, e) == ReseauLocal.Cause.RESEAU_COUPE
                }
                main.post { reconstruire() }
                try { Thread.sleep(60_000) } catch (_: InterruptedException) { break }
            }
        }.also { it.isDaemon = true; it.start() }
        removeCallbacks(tic)
        post(tic)
        web?.demarrer()
        soutien.demarrer()
    }

    /** Tableau précédent (-1) ou suivant (+1), à la demande de l'utilisateur. */
    fun naviguer(delta: Int) {
        val now = SystemClock.uptimeMillis()
        interactionA = now
        web?.takeIf { it.montree }?.let { it.naviguer(delta); return }
        if (tableaux.size <= 1) return
        val n = tableaux.size
        indice = ((indice + delta) % n + n) % n
        changeA = now
        invalidate()
    }

    /** Figer le tableau en cours, ou reprendre le défilement. */
    fun basculerPause() {
        web?.takeIf { it.montree }?.let { it.basculerPause(); return }
        pause = !pause
        interactionA = SystemClock.uptimeMillis()
        if (!pause) changeA = interactionA
        invalidate()
    }

    /** Un flux vient de démarrer ou de s'arrêter derrière la veille : la radio de Vision se tait ou revient. */
    fun sonChange() { try { rafraichirTheme() } catch (_: Exception) {} }

    /** Montre l'indicateur sans rien changer (touche sans effet). */
    fun signaler() { interactionA = SystemClock.uptimeMillis(); invalidate() }

    fun arreter() {
        soutien.arreter()
        web?.arreter()
        couverte = false
        musique.arreter()
        // 1.91.0 (retour d'essai) : la musique lancée par la veille s'arrête TOUJOURS en sortie de veille.
        VeilleAudio.fin(context)
        cameras?.cacher()
        actif = false
        removeCallbacks(tic)
        fil?.interrupt()
        fil = null
        filCameras?.interrupt()
        filCameras = null
        typees.arreter()
        images.values.forEach { it.bitmap.recycle() }
        images.clear()
    }

    /** Un fil qui tourne tant qu'on a des caméras à montrer : une image par caméra toutes les trois secondes. */
    private fun demarrerCameras() {
        filCameras = Thread {
            val cfg = Config(context)
            while (actif && camerasVoulues.isNotEmpty()) {
                val debut = SystemClock.uptimeMillis()
                val fils = camerasVoulues.map { cam ->
                    Thread {
                        val octets = Net.postBytes(context, cfg, "/api/pc_parental/veille/image",
                            JSONObject().put("id", cfg.id).put("secret", cfg.secret).put("entite", cam.id), 10_000)
                        if (octets != null) {
                            val bornes = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(octets, 0, octets.size, bornes)
                            val cible = (width / 2).coerceAtLeast(320)
                            var echelle = 1
                            while (bornes.outWidth / (echelle * 2) >= cible) echelle *= 2
                            val opts = BitmapFactory.Options().apply { inSampleSize = echelle; inPreferredConfig = Bitmap.Config.RGB_565 }
                            BitmapFactory.decodeByteArray(octets, 0, octets.size, opts)?.let { bm ->
                                images.put(cam.id, Image(bm, SystemClock.uptimeMillis()))?.bitmap?.recycle()
                            }
                        }
                    }.also { it.isDaemon = true; it.start() }
                }
                fils.forEach { try { it.join(12_000) } catch (_: InterruptedException) { return@Thread } }
                val reste = 3000 - (SystemClock.uptimeMillis() - debut)
                if (reste > 0) try { Thread.sleep(reste) } catch (_: InterruptedException) { break }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        // Cachée puis remontrée (veille profonde, changement de source) : on relance la cadence.
        if (visibility == VISIBLE && actif) { removeCallbacks(tic); post(tic); derniereImage = 0L }
    }

    /** Vrai si l'heure courante est dans la plage « HH:MM-HH:MM » (qui peut passer minuit). */
    private fun dansPlage(plage: String): Boolean {
        val m = Regex("(\\d\\d):(\\d\\d)-(\\d\\d):(\\d\\d)").matchEntire(plage.trim()) ?: return false
        val (h1, m1, h2, m2) = m.destructured
        val debut = h1.toInt() * 60 + m1.toInt(); val fin = h2.toInt() * 60 + m2.toInt()
        val cal = Calendar.getInstance()
        val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return if (debut <= fin) now in debut until fin else now >= debut || now < fin
    }

    /** Thème du moment : celui choisi, ou « Sombre » pendant la nuit (musique coupée aussi). */
    private var versionReglages = -1
    private fun rafraichirTheme() {
        // Tout vient de l'appareil : thème, couleurs à la carte, plage de nuit, radio.
        val nuit = Local.estNuit(context)
        nuitActive = nuit
        appliquerTheme(Local.themeEffectif(context, Palette.APPLI))
        pastille = Local.pastilleVeille(context)
        indicateur = IndicateurOuverture.reglages(context)
        // Une autre appli joue déjà (Spotify, YouTube Music…) : l'écran de veille la laisse, il ne joue pas par-dessus.
        val autre = try { Flux.paquets(context)?.isNotEmpty() == true || ((context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager).isMusicActive && !Musique.nous) } catch (_: Exception) { false }
        // 1.91.0 (retour d'essai) : une playlist favorite choisie pour la veille remplace la radio (lecteur « · Vision », via HA).
        if (VeilleAudio.choisie(context) != null) {
            musique.jouer("")
            if (nuit) VeilleAudio.fin(context) else VeilleAudio.debut(context, nuit, autre)
        } else musique.jouer(if (nuit || donnees == null || autre) "" else Local.musique(context))
        versionReglages = Local.version
    }

    private fun reconstruire() {
        val d = donnees
        rafraichirTheme()
        // La page de veille montre la demande d'accès en attente, comme ce dessin le fait.
        web?.annoncer(d?.demandes?.firstOrNull()?.let { x -> context.getString(R.string.lanceur_demande_quoi, x.prenom, x.libelle) + (if (d.demandes.size > 1) "  +${d.demandes.size - 1}" else "") + "  ·  " + ilYA(x.ts) } ?: "")
        // Les flux des caméras s'ouvrent dès le départ et restent ouverts : toujours prêts.
        if (d != null && d.cameras.isNotEmpty()) cameras?.prechauffer(d.cameras.map { it.id })
        val liste = ArrayList<Tableau>()
        val defs = d?.defs
        if (d != null && defs != null) {
            // Home Assistant dicte l'ordre, la durée et le public ; on fabrique chaque tableau à sa place.
            defs.forEach { def -> liste.addAll(tableauxPour(d, def)) }
            if (liste.isEmpty()) liste.add(Tableau(Genre.HORLOGE))
            tableaux = liste
            if (indice >= tableaux.size) indice = 0
            return
        }
        // Sans liste venue de Home Assistant : l'horloge, seul tableau propre à l'appli.
        liste.add(Tableau(Genre.HORLOGE))
        tableaux = liste
        if (indice >= tableaux.size) indice = 0
    }

    /** Les tableaux que produit une définition venue de Home Assistant (zéro, un ou plusieurs). */
    private fun tableauxPour(d: Donnees, def: Def): List<Tableau> {
        // L'appareil a le dernier mot : affiché ou non, et combien de temps.
        // Seuls restent l'horloge (propre à l'appli) et les tableaux de bord de Home Assistant.
        if (def.code != "horloge" && def.code != "dash") return emptyList()
        // Module « tableaux de veille » coupé : l'horloge seule.
        if (!Modules.tableauVeille(Modules.actifs(context), def.code)) return emptyList()
        val cle = Local.cleTableau(def.code, def.id)
        if (!Local.actif(context, cle, true)) return emptyList()
        val ms = Local.duree(context, cle, (def.dureeMs / 1000).toInt()) * 1000L
        return when (def.code) {
            "horloge" -> listOf(Tableau(Genre.HORLOGE, dureeMs = ms))
            "vigilance" -> if (d.rang >= 1) listOf(Tableau(Genre.VIGILANCE, dureeMs = ms)) else emptyList()
            "meteo" -> if (d.etatMeteo.isNotEmpty()) listOf(Tableau(Genre.METEO, dureeMs = ms)) else emptyList()
            "courbe" -> if (d.series.size >= 2) listOf(Tableau(Genre.COURBE, dureeMs = ms)) else emptyList()
            "maison" -> if (d.maison.isNotEmpty()) listOf(Tableau(Genre.MAISON, dureeMs = ms)) else emptyList()
            "tuiles" -> {
                val themes = LinkedHashMap<String, MutableList<Tuile>>()
                d.tuiles.forEach { themes.getOrPut(it.theme) { ArrayList() }.add(it) }
                themes.values.flatMap { groupe -> groupe.chunked(4).map { Tableau(Genre.TUILES, it, dureeMs = ms) } }
            }
            "cameras" -> d.cameras.chunked(4).map { Tableau(Genre.CAMERAS, cameras = it, dureeMs = ms) }
            "esports" -> d.esports.map { Tableau(Genre.ESPORTS, jeu = it, dureeMs = ms) }
            "courses" -> d.courses.map { Tableau(Genre.COURSES, sport = it, dureeMs = ms) }
            "avenir" -> if (d.avenir.isNotEmpty()) listOf(Tableau(Genre.AVENIR, dureeMs = ms)) else emptyList()
            "ecole" -> d.ecole.map { Tableau(Genre.ECOLE, ecole = it, dureeMs = ms) }
            "agenda" -> if (d.agenda.isNotEmpty()) listOf(Tableau(Genre.AGENDA, dureeMs = ms)) else emptyList()
            "chauffage" -> if (d.chauffage != null) listOf(Tableau(Genre.CHAUFFAGE, dureeMs = ms)) else emptyList()
            "batteries" -> if (d.batteries.isNotEmpty()) listOf(Tableau(Genre.BATTERIES, dureeMs = ms)) else emptyList()
            "photos" -> if (d.photos.isNotEmpty()) {
                val depart = (tourPhotos * 3) % d.photos.size
                listOf(Tableau(Genre.PHOTOS, photos = (0 until minOf(3, d.photos.size)).map { d.photos[(depart + it) % d.photos.size] }, dureeMs = ms))
            } else emptyList()
            "entites" -> if (def.cases.isNotEmpty()) listOf(Tableau(Genre.ENTITES, def = def, dureeMs = ms)) else emptyList()
            // Un tableau de bord Home Assistant : autant d'écrans qu'il en faut, enchaînés en fondu.
            "dash" -> {
                // Home Assistant rend les cartes disponibles ; l'appareil choisit celles qu'il montre.
                val masquees = Local.cartesMasquees(context, cle)
                val vu = if (masquees.isEmpty()) def else Def(def.code, def.dureeMs, def.id, def.titre, def.cases,
                    def.sections.map { s -> Section(s.titre, s.cases.filter { it.carte !in masquees }) }.filter { it.cases.isNotEmpty() }, def.colonnes)
                paginer(vu).map { Tableau(Genre.DASH, def = vu, dureeMs = ms, page = it) }
            }
            else -> emptyList()
        }
    }

    // ------------------------------------------------------------ outils d'animation

    private fun lisser(x: Float): Float { val c = x.coerceIn(0f, 1f); return c * c * (3 - 2 * c) }

    /** Progression 0..1 d'une étape qui commence à `debut` ms et dure `duree` ms. */
    internal fun etape(ecoule: Long, debut: Long, duree: Long): Float = lisser((ecoule - debut) / duree.toFloat())

    /** Un rebond doux à l'arrivée : dépasse un peu puis se pose. */
    private fun rebond(x: Float): Float {
        val c = x.coerceIn(0f, 1f)
        val base = 1f - (cos(c * Math.PI).toFloat() * 0.5f + 0.5f)
        return base + sin(c * Math.PI).toFloat() * 0.08f * (1f - c)
    }

    /** Un nombre qui compte depuis zéro : « 17,3 » apparaît en montant. */
    private val nombre = Regex("-?\\d+(?:[.,]\\d+)?")
    private fun compter(texte: String, p: Float): String {
        if (p >= 1f) return texte
        val m = nombre.find(texte) ?: return texte
        val brut = m.value.replace(',', '.').toFloatOrNull() ?: return texte
        val decimales = if (m.value.contains(',')) m.value.substringAfter(',').length else if (m.value.contains('.')) m.value.substringAfter('.').length else 0
        val v = brut * lisser(p)
        val s = if (decimales == 0) "${Math.round(v)}" else String.format(Locale.getDefault(), "%.${decimales}f", v)
        return texte.replaceRange(m.range, s)
    }

    // ------------------------------------------------------------ dessin

    override fun onDraw(c: Canvas) {
        val maintenant = SystemClock.uptimeMillis()
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val t = (maintenant - depart) / 1000f
        // La page de veille est là : un fond uni, de sa couleur, et rien d'autre dessous.
        if (couverte || web?.montree == true) { c.drawColor(fond or 0xFF000000.toInt()); return }
        // Elle arrive : l'heure reste à l'écran en l'attendant, seule sur le même fond uni. Elle s'efface
        // d'un coup quand la page se montre ; deux horloges l'une sur l'autre ne doivent jamais se voir.
        if (web?.enAttente == true) { c.drawColor(fond or 0xFF000000.toInt()); dessinerHorloge(c, w, h, 1f, maintenant - changeA); return }
        dessinerFond(c, w, h, t)
        // 17-10 (l'auteur, point 12) : « Ajuster l'écran » — le contenu (pas le fond) se resserre au centre quand la télé rogne
        // plus que les 5 % prévus par le dessin de la veille (ZoneSure), ou s'étend un peu quand elle rogne moins.
        val echelleZone = ZoneSure.echelle(context)
        val niveauZone = c.save()
        if (echelleZone != 1f) c.scale(echelleZone, echelleZone, w / 2, h / 2)

        var ecoule = maintenant - changeA
        val courant = tableaux.getOrNull(indice)
        val dureeActuelle = if (courant != null && courant.dureeMs > 0) courant.dureeMs else when (courant?.genre) { Genre.COURBE -> dureeCourbeMs; Genre.CAMERAS -> dureeCamerasMs; Genre.ESPORTS, Genre.COURSES, Genre.ECOLE, Genre.AVENIR, Genre.CHAUFFAGE, Genre.BATTERIES -> dureeEsportMs; Genre.PHOTOS -> dureePhotosMs; else -> dureeMs }
        if (!pause && ecoule >= dureeActuelle && tableaux.size > 1) {
            if (tableaux.getOrNull(indice)?.genre == Genre.PHOTOS) tourPhotos++
            indice = (indice + 1) % tableaux.size
            changeA = maintenant; ecoule = 0
            if (indice == 0) reconstruire()
        }

        val entree = min(1f, ecoule / fonduMs.toFloat())
        val reste = dureeActuelle - ecoule
        if (derniereImage > 0) {
            peine.mesurer(maintenant - derniereImage, intervalle, maintenant)
            imagesDessinees++
            if (maintenant - derniereImage > intervalle * 2 + 8) imagesLentes++
        }
        derniereImage = maintenant
        intervalle = CadenceVeille.intervalle(ecoule, if (pause || tableaux.size <= 1) -1 else reste, fonduMs, 1500, peine.peine(maintenant))
        // Un seul tableau (l'horloge seule) : il reste affiché, sans fondu de sortie vers un écran vide.
        val sortie = if (!pause && tableaux.size > 1 && reste < fonduMs) (reste / fonduMs.toFloat()).coerceIn(0f, 1f) else 1f
        // Les caméras se rafraîchissent pendant leur tableau, et juste avant qu'il n'arrive.
        val suivant = tableaux.getOrNull((indice + 1) % tableaux.size)
        val actuel = tableaux.getOrNull(indice)
        camerasVoulues = when {
            actuel?.genre == Genre.CAMERAS -> actuel.cameras
            suivant?.genre == Genre.CAMERAS && reste < 6000 -> suivant.cameras
            else -> emptyList()
        }
        if (camerasVoulues.isNotEmpty() && (filCameras?.isAlive != true)) demarrerCameras()
        val alpha = lisser(min(entree, sortie))
        // Veille-lot (pages de cartes) : les écrans d'un même tableau de bord défilent de droite à gauche, comme sur le
        // PC ; un autre tableau arrive en fondu, en montant un peu.
        val precedent = tableaux.getOrNull(((indice - 1) % tableaux.size + tableaux.size) % tableaux.size)
        val pageSuivie = actuel?.genre == Genre.DASH && precedent !== actuel && precedent?.page?.def === actuel.page?.def
        val versSuivante = actuel?.genre == Genre.DASH && suivant !== actuel && suivant?.page?.def === actuel.page?.def
        val glisseX = when {
            pageSuivie && entree < 1f -> (1f - lisser(entree)) * w * 0.05f
            versSuivante && sortie < 1f -> -(1f - lisser(sortie)) * w * 0.05f
            else -> 0f
        }
        val glisse = if (pageSuivie || glisseX != 0f) 0f else (1f - lisser(entree)) * h * 0.03f

        // Anti-marquage (veille-lot) : tout dérive de quelques pixels entiers, si lentement que l'œil ne le voit pas.
        DeriveVeille.decalage(maintenant, deriveAmplitude, deriveXY)
        val dx = deriveXY[0].toFloat()
        val dy = deriveXY[1].toFloat()

        c.save()
        c.translate(dx + glisseX, dy + glisse)
        derive[0] = dx + glisseX; derive[1] = dy + glisse
        val tableau = tableaux.getOrNull(indice) ?: Tableau(Genre.HORLOGE)
        // Les flux vidéo s'ouvrent vingt secondes avant le tableau des caméras (Home Assistant met
        // une dizaine de secondes à servir un flux) ; le reste du temps, la couche est vide.
        val chauffe = suivant?.genre == Genre.CAMERAS && tableau.genre != Genre.CAMERAS && reste < 20_000
        if (tableau.genre != Genre.CAMERAS) cameras?.let {
            if (chauffe) it.prechauffer(suivant!!.cameras.map { cam -> cam.id })
            it.masquer()
        }
        if (tableau.genre != Genre.CAMERAS && casesCameras.isNotEmpty()) casesCameras.clear()
        // Veille-lot : les médias de cette page, et ceux de la suivante dès qu'elle approche (préchargement).
        typees.debutImage(tableau.page?.let { casesTypees(it) }, if (reste < 6000) suivant?.page?.let { casesTypees(it) } else null)
        when (tableau.genre) {
            Genre.HORLOGE -> dessinerHorloge(c, w, h, alpha, ecoule)
            Genre.METEO -> dessinerMeteo(c, w, h, alpha, ecoule, t)
            Genre.VIGILANCE -> dessinerVigilance(c, w, h, alpha, ecoule)
            Genre.MAISON -> dessinerMaison(c, w, h, alpha, ecoule, t)
            Genre.TUILES -> dessinerTuiles(c, w, h, alpha, ecoule, t, tableau.tuiles)
            Genre.COURBE -> dessinerCourbe(c, w, h, alpha, ecoule, t)
            Genre.CAMERAS -> dessinerCameras(c, w, h, alpha, ecoule, t, tableau.cameras)
            Genre.ESPORTS -> tableau.jeu?.let { dessinerEsports(c, w, h, alpha, ecoule, t, it) }
            Genre.COURSES -> tableau.sport?.let { dessinerCourses(c, w, h, alpha, ecoule, t, it) }
            Genre.ECOLE -> tableau.ecole?.let { dessinerEcole(c, w, h, alpha, ecoule, t, it) }
            Genre.AGENDA -> dessinerAgenda(c, w, h, alpha, ecoule, t)
            Genre.AVENIR -> dessinerAvenir(c, w, h, alpha, ecoule, t)
            Genre.CHAUFFAGE -> dessinerChauffage(c, w, h, alpha, ecoule, t)
            Genre.BATTERIES -> dessinerBatteries(c, w, h, alpha, ecoule, t)
            Genre.ENTITES -> tableau.def?.let { dessinerEntites(c, w, h, alpha, ecoule, t, it) }
            Genre.DASH -> tableau.page?.let { dessinerDash(c, w, h, alpha, ecoule, t, it) }
            Genre.PHOTOS -> dessinerPhotos(c, w, h, alpha, ecoule, t, tableau.photos)
        }
        // 1.93.05 (retour 35) : plus de pluie sur la page horloge ; seulement sur la page météo.
        if (tableau.genre == Genre.METEO) dessinerPluie(c, w, h, alpha, ecoule, t, false)
        dessinerDemandes(c, w, h, t)
        c.restore()
        typees.finImage()
        // L'heure et l'œil ne glissent pas avec les tableaux : ils ne suivent que la lente dérive anti-marquage.
        c.save()
        c.translate(dx, dy)
        if (tableau.genre != Genre.HORLOGE) dessinerPetiteHorloge(c, w, h)
        dessinerPied(c, w, h, t)
        dessinerIndicateur(c, w, h, maintenant)
        c.restore()
        c.restoreToCount(niveauZone)
    }

    /** Les points des tableaux et l'état figé, quelques secondes après un geste (en permanence quand c'est figé). */
    private fun dessinerIndicateur(c: Canvas, w: Float, h: Float, maintenant: Long) {
        val depuis = maintenant - interactionA
        val alpha = when {
            pause -> 1f
            interactionA == 0L || depuis > 3200 -> return
            depuis > 2400 -> 1f - (depuis - 2400) / 800f
            else -> min(1f, depuis / 200f)
        }
        val n = min(tableaux.size, 14)
        val r = h * 0.0055f; val pas = h * 0.022f
        // 1.93.06 : dans la marge de 5 % (avant : 95,5 %, sous le bord réglé une fois au ras de l'écran).
        val cy = h * 0.95f - r * 1.35f
        val largeurPoints = (n - 1) * pas
        var x = w / 2f - largeurPoints / 2f
        pForme.style = Paint.Style.FILL
        for (i in 0 until n) {
            val actuel = i == indice % n
            pForme.color = if (actuel) pourpre else encre3
            pForme.alpha = ((if (actuel) 0.95f else 0.45f) * alpha * 255).toInt()
            c.drawCircle(x, cy, if (actuel) r * 1.35f else r, pForme)
            x += pas
        }
        if (pause) {
            // Deux barres : le défilement est figé.
            val bx = w / 2f - largeurPoints / 2f - pas * 1.6f
            pForme.color = or; pForme.alpha = (0.95f * alpha * 255).toInt()
            val bh = h * 0.012f; val bw = h * 0.0035f
            c.drawRoundRect(RectF(bx - bw * 2.2f, cy - bh, bx - bw * 0.2f, cy + bh), bw, bw, pForme)
            c.drawRoundRect(RectF(bx + bw * 0.2f, cy - bh, bx + bw * 2.2f, cy + bh), bw, bw, pForme)
        }
        val aide = if (ReglagesTvActivity.estTele(context)) (if (pause) context.getString(R.string.veilleview_aide_tele_pause) else context.getString(R.string.veilleview_aide_tele))
                   else (if (pause) context.getString(R.string.veilleview_aide_pause) else context.getString(R.string.veilleview_aide))
        ecrire(c, aide, w / 2f, cy - h * 0.022f, h * 0.02f, encre3, 0.85f * alpha, normal, Paint.Align.CENTER)
    }

    // --- Fond ------------------------------------------------------------------

    private fun dessinerFond(c: Canvas, w: Float, h: Float, t: Float) {
        c.drawColor(fond)
        halosN = 0
        // Trois lueurs qui dérivent lentement : or pâle, rose poudré, ciel.
        val force = if (sombre) 0.5f else 1f
        halo(c, w * (0.22f + 0.12f * sin(t / 23f)), h * (0.30f + 0.15f * cos(t / 29f)), h * 0.95f, halos[0], 0.28f * force)
        halo(c, w * (0.80f + 0.10f * cos(t / 31f)), h * (0.72f + 0.12f * sin(t / 19f)), h * 0.85f, halos[1], 0.22f * force)
        halo(c, w * (0.62f + 0.14f * sin(t / 41f)), h * (0.12f + 0.10f * cos(t / 37f)), h * 0.70f, halos[2], 0.20f * force)
        // Les poussières de lumière montent doucement et scintillent.
        pForme.style = Paint.Style.FILL
        poussieres.forEach { p ->
            val x = ((p.x + p.vx * t) % 1f + 1f) % 1f
            val y = ((p.y + p.vy * t) % 1f + 1f) % 1f
            val scintille = 0.5f + 0.5f * sin(t * 1.3f + p.phase)
            // Discrètes : de l'or très pâle, à peine plus clair que le fond.
            pForme.color = if (sombre) 0xFFE8C877.toInt() else 0xFFE8C877.toInt(); pForme.alpha = ((if (sombre) 18 else 25) + 55 * scintille).toInt()
            c.drawCircle(x * w, y * h, p.r * h * (0.8f + 0.4f * scintille), pForme)
        }
        // Un léger voile clair en bas, pour poser le pied de page.
        if (voile == null || voileH != h) { voileH = h; voile = LinearGradient(0f, h * 0.7f, 0f, h, 0x00FFFFFF, 0x55FFFFFF, Shader.TileMode.CLAMP) }
        pFond.shader = voile
        c.drawRect(0f, 0f, w, h, pFond)
        pFond.shader = null
    }

    // Veille-lot (fluidité) : les dégradés du fond sont créés une fois (par taille et par thème) puis seulement déplacés.
    private var voile: LinearGradient? = null
    private var voileH = 0f
    private var halosN = 0
    private val halosDegrades = arrayOfNulls<RadialGradient>(3)
    private val halosCles = LongArray(3)
    private val halosMatrice = android.graphics.Matrix()

    private fun halo(c: Canvas, x: Float, y: Float, r: Float, rgb: Int, force: Float) {
        val i = halosN++ % 3
        val centre = Color.argb((force * 255).toInt().coerceIn(0, 255), Color.red(rgb), Color.green(rgb), Color.blue(rgb))
        val cle = (java.lang.Float.floatToIntBits(r).toLong() shl 32) xor (centre.toLong() and 0xFFFFFFFFL)
        var s = halosDegrades[i]
        if (s == null || halosCles[i] != cle) {
            s = RadialGradient(0f, 0f, r, intArrayOf(centre, Color.TRANSPARENT), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
            halosDegrades[i] = s; halosCles[i] = cle
        }
        halosMatrice.setTranslate(x, y)
        s.setLocalMatrix(halosMatrice)
        pFond.shader = s
        c.drawCircle(x, y, r, pFond)
        pFond.shader = null
    }

    internal fun ecrire(c: Canvas, s: String, x: Float, y: Float, taille: Float, couleur: Int, alpha: Float, police: Typeface = normal, align: Paint.Align = Paint.Align.LEFT, espacement: Float = 0f) {
        pTexte.typeface = police
        pTexte.textSize = taille
        pTexte.color = couleur
        pTexte.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        pTexte.textAlign = align
        pTexte.letterSpacing = espacement
        // Veille-lot : sur le pixel entier (texte net, grille alignée) ; en mouvement, un pas d'un pixel par image.
        c.drawText(s, Math.round(x).toFloat(), Math.round(y).toFloat(), pTexte)
    }

    internal fun largeur(s: String, taille: Float, police: Typeface): Float {
        pTexte.typeface = police; pTexte.textSize = taille; pTexte.letterSpacing = 0f
        return pTexte.measureText(s)
    }

    private fun titre(c: Canvas, s: String, w: Float, h: Float, alpha: Float, ecoule: Long) {
        val p = etape(ecoule, 0, 700)
        ecrire(c, s.uppercase(Locale.getDefault()), w * 0.08f - (1f - p) * h * 0.03f, h * 0.17f, h * 0.034f, pourpre, alpha * p, gras, espacement = 0.14f)
        pForme.style = Paint.Style.FILL
        pForme.color = or; pForme.alpha = (alpha * 220).toInt()
        c.drawRoundRect(RectF(w * 0.08f, h * 0.19f, w * 0.08f + h * 0.08f * p, h * 0.19f + h * 0.005f), h * 0.003f, h * 0.003f, pForme)
    }

    internal fun cartePosee(c: Canvas, rect: RectF, h: Float, alpha: Float, bord: Int = carteBord, epaisseur: Float = 1f, trait: Float = 0f,
                           rayon: Float = 0f) {
        // 1.90.x : [rayon] (px) donne le même arrondi à toutes les cartes d'un tableau (sinon : selon l'échelle).
        val r = if (rayon > 0f) rayon else h * 0.03f
        pForme.style = Paint.Style.FILL
        pForme.color = Color.BLACK; pForme.alpha = (alpha * 0x18).toInt()
        c.drawRoundRect(RectF(rect.left, rect.top + h * 0.008f, rect.right, rect.bottom + h * 0.008f), r, r, pForme)
        pForme.color = carte; pForme.alpha = (alpha * Color.alpha(carte)).toInt()
        c.drawRoundRect(rect, r, r, pForme)
        // 1.90.x : [trait] (px) fixe l'épaisseur pour qu'elle soit la même sur toutes les cartes d'un tableau.
        pForme.style = Paint.Style.STROKE; pForme.strokeWidth = if (trait > 0f) trait else h * 0.003f * epaisseur
        pForme.color = bord; pForme.alpha = (alpha * Color.alpha(bord)).toInt()
        c.drawRoundRect(rect, r, r, pForme)
        pForme.style = Paint.Style.FILL
    }

    // --- Horloge -----------------------------------------------------------

    // 1.91.2 : le style de l'horloge (StylesHorloge), relu toutes les cinq secondes au plus (jamais à chaque image).
    private val pinceauxStyle = HorlogesVeille.Pinceaux().also { it.h24 = android.text.format.DateFormat.is24HourFormat(ctx) }
    private var styleHorloge = HorlogesVeille.ANNEAU
    private var styleLuA = 0L

    /** Le style que la veille dessine (relu au plus toutes les cinq secondes). */
    internal fun styleHorloge(): String {
        val t = SystemClock.uptimeMillis()
        if (styleLuA == 0L || t - styleLuA > 5_000L) {
            styleLuA = t
            // Une horloge de thème n'existe que sur la page de veille : le dessin de l'appli garde alors l'anneau.
            styleHorloge = Local.styleHorlogeVeilleChoisi(context)?.takeUnless { StylesHorloge.estWeb(it) } ?: HorlogesVeille.ANNEAU
        }
        return styleHorloge
    }

    private fun dessinerHorloge(c: Canvas, w: Float, h: Float, alpha: Float, ecoule: Long) {
        val style = styleHorloge()
        if (style != HorlogesVeille.ANNEAU) { dessinerStyle(c, style, w, h, alpha, ecoule); return }
        val maintenant = Date()
        val cal = Calendar.getInstance()
        val heure = heureFmt.format(maintenant)
        val date = dateLongue.format(maintenant).replaceFirstChar { it.uppercase() }
        val p0 = etape(ecoule, 0, 900)
        // L'horloge stylisée de Vision (HorlogeVision, même dessin que l'accueil du téléphone) : l'anneau des secondes et
        // l'heure dont les deux points battent la seconde.
        val sec = cal.get(Calendar.SECOND) + cal.get(Calendar.MILLISECOND) / 1000f
        HorlogeVision.dessiner(c, w / 2, h * 0.5f, h * 0.42f, h * 0.30f, heure, sec, HorlogeVision.Couleurs(encre, pourpre, or),
            fin, pForme, pTexte, alpha = alpha, apparition = p0, echelle = 0.9f + 0.1f * rebond(p0))

        val p1 = etape(ecoule, 300, 800)
        ecrire(c, date, w / 2, h * 0.66f + (1f - p1) * h * 0.02f, h * 0.05f, encre2, alpha * p1, normal, Paint.Align.CENTER, 0.04f)
        // 1.93.05 (l'auteur, retour 35) : la page horloge ne montre QUE l'horloge stylisée (et sa date) : ni température, ni
        // ciel, ni vigilance, ni pluie — quelle que soit l'horloge choisie (remplace le point 7 du 17-10).
        val d = donnees
        val p2 = etape(ecoule, 600, 800)
        if (erreur.isNotEmpty() && (d == null || SystemClock.uptimeMillis() - derniereReussite > 300_000L)) {
            // Lot 6 (APP-24) : ce qu'il faut faire, plus le texte de l'exception.
            val texte = if (reseauCoupe) ReseauLocal.messageCoupe(context) else context.getString(R.string.lot6_veille_injoignable)
            ecrire(c, texte, w / 2, h * 0.94f, h * 0.024f, encre3, alpha * p2, normal, Paint.Align.CENTER)
        }
    }

    /** Un style de Vision ([HorlogesVeille]) : l'horloge, puis la date et la météo aux places que le style laisse. */
    private fun dessinerStyle(c: Canvas, style: String, w: Float, h: Float, alpha: Float, ecoule: Long) {
        val p0 = etape(ecoule, 0, 900)
        HorlogesVeille.dessiner(c, style, w, h, pinceauxStyle, HorlogesVeille.Couleurs(encre, encre2, pourpre, or, carte or 0xFF000000.toInt(), fond or 0xFF000000.toInt()), alpha, p0)
        val lignes = HorlogesVeille.lignesSous(style)
        val date = dateLongue.format(Date()).replaceFirstChar { it.uppercase() }
        val p1 = etape(ecoule, 300, 800)
        ecrire(c, date, w / 2, h * lignes[0] + (1f - p1) * h * 0.02f, h * 0.05f, encre2, alpha * p1, normal, Paint.Align.CENTER, 0.04f)
        // 1.93.05 (retour 35) : plus de température ni de ciel sous l'horloge.
    }

    private fun dessinerPetiteHorloge(c: Canvas, w: Float, h: Float) {
        val cal = Calendar.getInstance()
        val batt = 0.4f + 0.6f * (0.5f + 0.5f * cos((cal.get(Calendar.MILLISECOND) / 1000f) * 2 * Math.PI).toFloat())
        val taille = h * 0.06f
        // Les chiffres de l'horloge suivent le 12/24 h du téléphone (en 12 h, sans zéro devant l'heure).
        val h24 = android.text.format.DateFormat.is24HourFormat(context)
        val hh = if (h24) String.format(Locale.getDefault(), "%02d", cal.get(Calendar.HOUR_OF_DAY)) else String.format(Locale.getDefault(), "%d", (cal.get(Calendar.HOUR) + 11) % 12 + 1); val mm = String.format(Locale.getDefault(), "%02d", cal.get(Calendar.MINUTE))
        val lMin = largeur(mm, taille, fin); val lDeux = largeur(":", taille, fin)
        // 1.93.05 (retour 39) : décalée vers la gauche quand le coin du haut à droite est pris (QR, logo, bandeau).
        val droite = CoinsVeille.Coins.bordDroit(OptionsVeille.HD, OptionsVeille.qr(context), OptionsVeille.logo(context), OptionsVeille.lecteur(context), w, h, w * 0.92f, CoinsVeille.coteQr(context, h), CoinsVeille.largeurLegende(context, h))
        ecrire(c, mm, droite, h * 0.17f, taille, encre, 0.9f, fin, Paint.Align.RIGHT)
        ecrire(c, ":", droite - lMin, h * 0.17f, taille, pourpre, 0.9f * batt, fin, Paint.Align.RIGHT)
        ecrire(c, hh, droite - lMin - lDeux, h * 0.17f, taille, encre, 0.9f, fin, Paint.Align.RIGHT)
    }

    // L'œil de Vision, en bas à gauche : il regarde autour de lui et cligne de temps en temps.
    private var prochainClin = 3f
    private var clinDebut = -10f
    private var regardX = 0f
    private var regardY = 0f
    private var cibleX = 0f
    private var cibleY = 0f
    private var prochainRegard = 0f

    private fun dessinerPied(c: Canvas, w: Float, h: Float, t: Float) {
        // 1.93.05 (l'auteur, retour 39) : le logo a son coin (OptionsVeille.logo), ou il est masqué.
        val coin = OptionsVeille.logo(context)
        if (coin == OptionsVeille.CACHE) return
        // Les paupières : fermeture vive (0,1 s), réouverture plus douce (0,16 s), parfois deux fois de suite.
        if (t >= prochainClin) {
            clinDebut = t
            val g = java.util.Random((t * 1000).toLong())
            prochainClin = t + if (g.nextFloat() < 0.18f) 0.4f else 2.5f + g.nextFloat() * 5f
        }
        val tt = t - clinDebut
        val fermeture = when {
            tt < 0f -> 0f
            tt < 0.10f -> tt / 0.10f
            tt < 0.26f -> 1f - (tt - 0.10f) / 0.16f
            else -> 0f
        }
        // Le regard : il se pose quelque part, y reste, puis file ailleurs d'un coup.
        if (t >= prochainRegard) {
            val g = java.util.Random((t * 7919).toLong())
            cibleX = (g.nextFloat() - 0.5f) * 1.6f; cibleY = (g.nextFloat() - 0.5f) * 0.9f
            prochainRegard = t + 1.5f + g.nextFloat() * 4f
        }
        regardX += (cibleX - regardX) * 0.18f; regardY += (cibleY - regardY) * 0.18f

        // En bas à gauche (sa place d'avant), sous la zone des cartes (qui s'arrête à 89 %) ; ailleurs, en miroir.
        val (cx, cy) = CoinsVeille.Coins.oeil(coin, w, h)
        val demiL = h * 0.03f; val demiH = h * 0.017f
        val ouverture = demiH * (1f - fermeture * 0.96f)
        // L'amande de l'œil : deux arcs, la paupière du haut descend quand il cligne.
        chemin.reset()
        chemin.moveTo(cx - demiL, cy)
        chemin.quadTo(cx, cy - ouverture * 2f, cx + demiL, cy)
        chemin.quadTo(cx, cy + demiH * 2f * (1f - fermeture * 0.5f), cx - demiL, cy)
        chemin.close()
        pForme.style = Paint.Style.FILL; pForme.alpha = 255
        pForme.color = if (sombre) 0xFFE9E2D6.toInt() else 0xFFFFFBF2.toInt(); c.drawPath(chemin, pForme)
        c.save()
        c.clipPath(chemin)
        val ix = cx + regardX * demiL * 0.45f; val iy = cy + regardY * demiH * 0.5f
        val ri = demiH * 0.95f
        pForme.color = pourpre; c.drawCircle(ix, iy, ri, pForme)
        pForme.color = or; c.drawCircle(ix, iy, ri * 0.62f, pForme)
        pForme.color = encre; c.drawCircle(ix, iy, ri * 0.34f, pForme)
        pForme.color = Color.WHITE; pForme.alpha = 200; c.drawCircle(ix - ri * 0.28f, iy - ri * 0.3f, ri * 0.14f, pForme)
        pForme.alpha = 255
        c.restore()
        // Le trait de la paupière, plus marqué en haut, comme un cil.
        pForme.style = Paint.Style.STROKE; pForme.strokeCap = Paint.Cap.ROUND
        pForme.color = pourpre; pForme.strokeWidth = h * 0.004f
        chemin.reset(); chemin.moveTo(cx - demiL, cy); chemin.quadTo(cx, cy - ouverture * 2f, cx + demiL, cy)
        c.drawPath(chemin, pForme)
        pForme.strokeWidth = h * 0.002f; pForme.alpha = 150
        chemin.reset(); chemin.moveTo(cx - demiL, cy); chemin.quadTo(cx, cy + demiH * 2f * (1f - fermeture * 0.5f), cx + demiL, cy)
        c.drawPath(chemin, pForme)
        pForme.alpha = 255; pForme.style = Paint.Style.FILL
        if (CoinsVeille.Coins.gauche(coin)) ecrire(c, "Vision", cx + demiL + h * 0.016f, cy + h * 0.009f, h * 0.026f, encre3, 0.9f, gras, espacement = 0.12f)
        else ecrire(c, "Vision", cx - demiL - h * 0.016f, cy + h * 0.009f, h * 0.026f, encre3, 0.9f, gras, Paint.Align.RIGHT, 0.12f)
    }

    // --- Météo -------------------------------------------------------------

    private fun dessinerMeteo(c: Canvas, w: Float, h: Float, alpha: Float, ecoule: Long, t: Float) {
        val d = donnees ?: return
        titre(c, context.getString(R.string.tableauediteur_dom_meteo), w, h, alpha, ecoule)
        val gauche = w * 0.08f
        val p0 = etape(ecoule, 200, 900)
        dessinerIcone(c, d.etatMeteo, gauche + h * 0.10f, h * 0.42f, h * 0.09f * (0.6f + 0.4f * rebond(p0)), alpha * p0, t)
        ecrire(c, compter(deg(d.temperature), etape(ecoule, 300, 1400)), gauche + h * 0.24f, h * 0.48f, h * 0.20f, encre, alpha * p0, fin)
        val p1 = etape(ecoule, 700, 700)
        ecrire(c, libelle(d.etatMeteo), gauche, h * 0.60f + (1f - p1) * h * 0.02f, h * 0.05f, encre2, alpha * p1, normal)
        val details = ArrayList<String>()
        d.ressenti?.let { details.add(context.getString(R.string.veilleview_ressenti, deg(it))) }
        d.humidite?.let { details.add(context.getString(R.string.veilleview_humidite, it.toInt())) }
        d.vent?.let { details.add(context.getString(R.string.veilleview_vent, it.toInt())) }
        val p2 = etape(ecoule, 900, 700)
        ecrire(c, details.joinToString("   ·   "), gauche, h * 0.67f + (1f - p2) * h * 0.02f, h * 0.032f, encre3, alpha * p2, normal)
        if (d.rang >= 1) ecrire(c, context.getString(R.string.veilleview_vigilance, d.niveau.lowercase(Locale.getDefault())), gauche, h * 0.76f, h * 0.034f, couleurNiveau(d.niveau), alpha * p2, gras, espacement = 0.08f)
        else if (d.niveau.isNotEmpty()) ecrire(c, context.getString(R.string.veilleview_vigilance_verte), gauche, h * 0.76f, h * 0.03f, vert, alpha * p2 * 0.9f, normal, espacement = 0.08f)

        val prev = d.previsions.take(5)
        if (prev.isEmpty()) return
        val colonne = w * 0.088f
        val x0 = w * 0.92f - colonne * (prev.size - 0.5f)
        val aujourdHui = jourIso.format(Date())
        prev.forEachIndexed { i, p ->
            val pi = etape(ecoule, 500 + 150L * i, 700)
            val x = x0 + colonne * i
            val monte = (1f - pi) * h * 0.06f
            val nom = if (p.jour == aujourdHui) context.getString(R.string.veilleview_auj) else try { jourCourt.format(jourIso.parse(p.jour)!!).replace(".", "").replaceFirstChar { it.uppercase() } } catch (_: Exception) { p.jour.takeLast(2) }
            ecrire(c, nom, x, h * 0.36f + monte, h * 0.03f, encre2, alpha * pi, gras, Paint.Align.CENTER, 0.06f)
            dessinerIcone(c, p.etat, x, h * 0.46f + monte, h * 0.045f, alpha * pi, t + i)
            ecrire(c, deg(p.max), x, h * 0.60f + monte, h * 0.045f, encre, alpha * pi, normal, Paint.Align.CENTER)
            ecrire(c, deg(p.min), x, h * 0.655f + monte, h * 0.032f, encre3, alpha * pi, normal, Paint.Align.CENTER)
            if (p.pluie != null && p.pluie >= 20) ecrire(c, "${p.pluie.toInt()} %", x, h * 0.71f + monte, h * 0.028f, bleu, alpha * pi, normal, Paint.Align.CENTER)
        }
    }

    internal fun deg(v: Double?): String = if (v == null) "—" else "${Math.round(v)}°"

    internal fun libelle(etat: String): String = when (etat) {
        "sunny" -> context.getString(R.string.veilleview_meteo_ensoleille)
        "clear-night" -> context.getString(R.string.veilleview_meteo_nuit_claire)
        "partlycloudy" -> context.getString(R.string.veilleview_meteo_eclaircies)
        "cloudy" -> context.getString(R.string.veilleview_meteo_nuageux)
        "fog" -> context.getString(R.string.veilleview_meteo_brouillard)
        "rainy" -> context.getString(R.string.veilleview_meteo_pluie)
        "pouring" -> context.getString(R.string.veilleview_meteo_fortes_pluies)
        "lightning", "lightning-rainy" -> context.getString(R.string.veilleview_meteo_orages)
        "snowy" -> context.getString(R.string.veilleview_meteo_neige)
        "snowy-rainy" -> context.getString(R.string.veilleview_meteo_neige_pluie)
        "hail" -> context.getString(R.string.veilleview_meteo_grele)
        "windy", "windy-variant" -> context.getString(R.string.veilleview_meteo_venteux)
        "exceptional" -> context.getString(R.string.veilleview_meteo_exceptionnel)
        else -> etat.replaceFirstChar { it.uppercase() }
    }

    /** Icônes météo tracées au compas et animées : rayons qui tournent, pluie qui tombe, éclair qui flashe. */
    internal fun dessinerIcone(c: Canvas, etat: String, x: Float, y: Float, r: Float, alpha: Float, t: Float) {
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        val gris = 0xFF8C8F99.toInt()
        val grisClair = 0xFFB9BCC6.toInt()
        fun soleil(cx: Float, cy: Float, rr: Float) {
            pForme.color = or; pForme.alpha = a; pForme.style = Paint.Style.FILL
            c.drawCircle(cx, cy, rr * 0.55f, pForme)
            pForme.strokeWidth = rr * 0.12f; pForme.style = Paint.Style.STROKE; pForme.strokeCap = Paint.Cap.ROUND
            val rot = t * 0.25f
            for (i in 0 until 8) {
                val ang = (i * Math.PI / 4).toFloat() + rot
                val ca = cos(ang); val sa = sin(ang)
                val l = 1f + 0.08f * sin(t * 2f + i)
                c.drawLine(cx + rr * 0.75f * ca, cy + rr * 0.75f * sa, cx + rr * l * ca, cy + rr * l * sa, pForme)
            }
            pForme.style = Paint.Style.FILL
        }
        fun lune(cx: Float, cy: Float, rr: Float) {
            pForme.color = or; pForme.alpha = a
            c.drawCircle(cx, cy, rr * 0.7f, pForme)
            pForme.color = fond; pForme.alpha = 255
            c.drawCircle(cx + rr * 0.35f, cy - rr * 0.3f, rr * 0.6f, pForme)
        }
        fun nuage(cx: Float, cy: Float, rr: Float, couleur: Int, derive: Float) {
            val ox = cx + rr * 0.06f * sin(t * 0.7f + derive)
            pForme.color = couleur; pForme.alpha = a; pForme.style = Paint.Style.FILL
            c.drawCircle(ox - rr * 0.35f, cy + rr * 0.05f, rr * 0.45f, pForme)
            c.drawCircle(ox + rr * 0.05f, cy - rr * 0.2f, rr * 0.55f, pForme)
            c.drawCircle(ox + rr * 0.45f, cy + rr * 0.1f, rr * 0.4f, pForme)
            c.drawRoundRect(RectF(ox - rr * 0.6f, cy + rr * 0.05f, ox + rr * 0.7f, cy + rr * 0.5f), rr * 0.25f, rr * 0.25f, pForme)
        }
        fun gouttes(cx: Float, cy: Float, rr: Float, n: Int) {
            pForme.color = bleu; pForme.strokeWidth = rr * 0.14f; pForme.style = Paint.Style.STROKE; pForme.strokeCap = Paint.Cap.ROUND
            for (i in 0 until n) {
                val gx = cx - rr * 0.35f + i * rr * 0.35f
                val chute = ((t * 1.4f + i * 0.33f) % 1f)
                pForme.alpha = (a * (1f - chute)).toInt()
                val gy = cy + rr * 0.6f + chute * rr * 0.5f
                c.drawLine(gx, gy, gx - rr * 0.1f, gy + rr * 0.3f, pForme)
            }
            pForme.style = Paint.Style.FILL
        }
        fun flocons(cx: Float, cy: Float, rr: Float) {
            for (i in 0 until 3) {
                val chute = ((t * 0.5f + i * 0.33f) % 1f)
                val fx = cx - rr * 0.35f + i * rr * 0.35f + rr * 0.06f * sin(t * 2f + i)
                val fy = cy + rr * 0.65f + chute * rr * 0.5f
                pForme.style = Paint.Style.FILL; pForme.color = Color.WHITE; pForme.alpha = (a * (1f - chute * 0.7f)).toInt()
                c.drawCircle(fx, fy, rr * 0.1f, pForme)
                pForme.style = Paint.Style.STROKE; pForme.strokeWidth = rr * 0.03f; pForme.color = grisClair; pForme.alpha = a
                c.drawCircle(fx, fy, rr * 0.1f, pForme)
            }
            pForme.style = Paint.Style.FILL
        }
        fun eclair(cx: Float, cy: Float, rr: Float) {
            val flash = if ((t % 2.6f) < 0.18f) 1f else 0.55f
            pForme.color = or; pForme.alpha = (a * flash).toInt(); pForme.style = Paint.Style.FILL
            chemin.reset()
            chemin.moveTo(cx + rr * 0.1f, cy + rr * 0.45f)
            chemin.lineTo(cx - rr * 0.2f, cy + rr * 0.95f)
            chemin.lineTo(cx + rr * 0.02f, cy + rr * 0.95f)
            chemin.lineTo(cx - rr * 0.12f, cy + rr * 1.35f)
            chemin.lineTo(cx + rr * 0.3f, cy + rr * 0.8f)
            chemin.lineTo(cx + rr * 0.08f, cy + rr * 0.8f)
            chemin.close()
            c.drawPath(chemin, pForme)
        }
        fun brume(cx: Float, cy: Float, rr: Float) {
            pForme.color = gris; pForme.alpha = a; pForme.strokeWidth = rr * 0.14f; pForme.style = Paint.Style.STROKE; pForme.strokeCap = Paint.Cap.ROUND
            for (i in 0 until 3) {
                val dx = rr * 0.12f * sin(t * 0.9f + i * 1.3f)
                c.drawLine(cx - rr * 0.8f + dx, cy - rr * 0.3f + i * rr * 0.35f, cx + rr * 0.8f + dx, cy - rr * 0.3f + i * rr * 0.35f, pForme)
            }
            pForme.style = Paint.Style.FILL
        }
        when (etat) {
            "sunny" -> soleil(x, y, r)
            "clear-night" -> lune(x, y, r)
            "partlycloudy" -> { soleil(x + r * 0.35f, y - r * 0.35f, r * 0.7f); nuage(x - r * 0.1f, y + r * 0.2f, r * 0.9f, grisClair, 0f) }
            "cloudy" -> { nuage(x + r * 0.25f, y - r * 0.15f, r * 0.7f, grisClair, 2f); nuage(x - r * 0.1f, y + r * 0.15f, r * 0.9f, gris, 0f) }
            "fog" -> brume(x, y, r)
            "rainy" -> { nuage(x, y - r * 0.2f, r * 0.9f, gris, 0f); gouttes(x, y - r * 0.2f, r * 0.9f, 3) }
            "pouring" -> { nuage(x, y - r * 0.2f, r * 0.9f, gris, 0f); gouttes(x, y - r * 0.2f, r * 0.9f, 4) }
            "lightning", "lightning-rainy" -> { nuage(x, y - r * 0.3f, r * 0.9f, gris, 0f); eclair(x, y - r * 0.3f, r * 0.9f) }
            "snowy" -> { nuage(x, y - r * 0.2f, r * 0.9f, grisClair, 0f); flocons(x, y - r * 0.2f, r * 0.9f) }
            "snowy-rainy", "hail" -> { nuage(x, y - r * 0.2f, r * 0.9f, gris, 0f); gouttes(x - r * 0.2f, y - r * 0.2f, r * 0.9f, 2); flocons(x + r * 0.5f, y - r * 0.2f, r * 0.5f) }
            "windy", "windy-variant" -> brume(x, y, r)
            else -> nuage(x, y, r, grisClair, 0f)
        }
        pForme.alpha = 255
    }

    // --- Vigilance ---------------------------------------------------------

    private fun couleurNiveau(niveau: String): Int = when (niveau.lowercase(Locale.FRANCE)) {
        "jaune" -> 0xFFD9B21A.toInt()
        "orange" -> 0xFFE8842A.toInt()
        "rouge" -> 0xFFD13B3B.toInt()
        else -> vert
    }

    private fun dessinerVigilance(c: Canvas, w: Float, h: Float, alpha: Float, ecoule: Long) {
        val d = donnees ?: return
        val couleur = couleurNiveau(d.niveau)
        titre(c, context.getString(R.string.veilleview_vigilance_meteo_france), w, h, alpha, ecoule)
        val p0 = etape(ecoule, 100, 900)
        val pulse = 0.8f + 0.2f * sin(SystemClock.uptimeMillis() / 500.0).toFloat()
        pForme.style = Paint.Style.FILL
        pForme.color = couleur; pForme.alpha = (alpha * 255 * pulse * p0).toInt().coerceIn(0, 255)
        c.drawRoundRect(RectF(w * 0.08f, h * 0.27f, w * 0.08f + h * 0.03f, h * 0.27f + (h * 0.57f) * p0), h * 0.015f, h * 0.015f, pForme)
        ecrire(c, context.getString(R.string.veilleview_vigilance, d.niveau.lowercase(Locale.getDefault())), w * 0.08f + h * 0.08f, h * 0.40f, h * 0.11f, couleur, alpha * p0, fin)
        ecrire(c, context.getString(R.string.veilleview_departement), w * 0.08f + h * 0.08f, h * 0.47f, h * 0.034f, encre2, alpha * p0, normal)
        val actifs = d.phenomenes.filter { it.second.lowercase(Locale.FRANCE) != "vert" }
        val liste = if (actifs.isEmpty()) d.phenomenes else actifs
        var y = h * 0.58f
        liste.take(6).forEachIndexed { i, (nom, niv) ->
            val pi = etape(ecoule, 500 + 120L * i, 600)
            pForme.color = couleurNiveau(niv); pForme.alpha = (alpha * 255 * pi).toInt()
            c.drawCircle(w * 0.08f + h * 0.10f, y - h * 0.013f, h * 0.012f * rebond(pi), pForme)
            ecrire(c, nom, w * 0.08f + h * 0.14f + (1f - pi) * h * 0.03f, y, h * 0.04f, encre, alpha * pi, normal)
            ecrire(c, niv, w * 0.08f + h * 0.14f + largeur(nom, h * 0.04f, normal) + h * 0.03f, y, h * 0.03f, couleurNiveau(niv), alpha * pi, gras, espacement = 0.08f)
            y += h * 0.058f
        }
    }

    // --- École ------------------------------------------------------------------

    private fun heureJolie(hm: String): String = if (hm.length >= 5) hm.substring(0, 2).trimStart('0').ifEmpty { "0" } + " h " + hm.substring(3, 5) else hm

    private fun dessinerEcole(c: Canvas, w: Float, h: Float, alpha: Float, ecoule: Long, t: Float, e: Ecole) {
        titre(c, context.getString(if (e.demain) R.string.veilleview_demain_ecole else R.string.veilleview_ecole, e.prenom), w, h, alpha, ecoule)
        val gauche = w * 0.08f; val droite = w * 0.92f
        val haut = h * 0.24f; val bas = h * 0.89f
        val p0 = etape(ecoule, 100, 700)
        // Bandeau : le jour, et les horaires en grand.
        ecrire(c, e.jour.replaceFirstChar { it.uppercase() }, gauche, haut + h * 0.01f, h * 0.03f, encre2, alpha * p0, normal)
        val horaires = "${heureJolie(e.debut)}  →  ${heureJolie(e.fin)}"
        ecrire(c, horaires, droite, haut + h * 0.015f, h * 0.045f, encre, alpha * p0, fin, Paint.Align.RIGHT)
        // Colonne de gauche : l'emploi du temps, une ligne par cours, avec sa couleur Pronote.
        val colG = RectF(gauche, haut + h * 0.05f, gauche + (droite - gauche) * 0.5f, bas)
        cartePosee(c, colG, h, alpha * p0)
        val n = e.cours.size.coerceAtLeast(1)
        val hl = min(h * 0.062f, (colG.height() - h * 0.04f) / n)
        e.cours.forEachIndexed { i, co ->
            val pi = etape(ecoule, 250 + 90L * i, 600)
            val y = colG.top + h * 0.02f + i * hl + (1f - pi) * h * 0.02f
            val a = alpha * pi
            pForme.style = Paint.Style.FILL; pForme.color = co.couleur; pForme.alpha = (a * (if (co.annule) 90 else 255)).toInt()
            c.drawRoundRect(RectF(colG.left + h * 0.025f, y + hl * 0.2f, colG.left + h * 0.025f + h * 0.008f, y + hl * 0.8f), h * 0.004f, h * 0.004f, pForme)
            ecrire(c, co.debut, colG.left + h * 0.05f, y + hl * 0.62f, hl * 0.42f, if (co.annule) encre3 else encre2, a, normal)
            val xm = colG.left + h * 0.05f + largeur("00:00", hl * 0.42f, normal) + h * 0.03f
            ecrire(c, tronquer(co.matiere, hl * 0.48f, gras, colG.width() * 0.55f), xm, y + hl * 0.64f, hl * 0.48f, if (co.annule) encre3 else encre, a, gras)
            val droiteTexte = if (co.annule) context.getString(R.string.veilleview_annule) else co.salle
            ecrire(c, droiteTexte, colG.right - h * 0.025f, y + hl * 0.62f, hl * 0.38f, if (co.annule) pourpre else encre3, a, normal, Paint.Align.RIGHT)
            if (co.annule) {
                pForme.color = pourpre; pForme.alpha = (a * 160).toInt()
                c.drawRect(xm, y + hl * 0.5f, xm + largeur(co.matiere, hl * 0.48f, gras), y + hl * 0.5f + h * 0.003f, pForme)
            }
        }
        // Colonne de droite : contrôles (alerte) puis devoirs.
        val colD = RectF(colG.right + h * 0.02f, colG.top, droite, bas)
        var y = colD.top
        if (e.controles.isNotEmpty()) {
            val hc = h * 0.05f + e.controles.size * h * 0.055f
            val rc = RectF(colD.left, y, colD.right, y + hc)
            val lueur = 0.6f + 0.4f * sin(t * 2.5f)
            cartePosee(c, rc, h, alpha * p0, Color.argb((255 * lueur).toInt(), Color.red(or), Color.green(or), Color.blue(or)), 2f)
            ecrire(c, context.resources.getQuantityString(R.plurals.veilleview_controles, e.controles.size), rc.left + h * 0.025f, rc.top + h * 0.04f, h * 0.024f, or, alpha * p0, gras, espacement = 0.12f)
            e.controles.forEachIndexed { i, (m, nom) ->
                val pi = etape(ecoule, 400 + 120L * i, 600)
                val yy = rc.top + h * 0.05f + i * h * 0.055f
                ecrire(c, m, rc.left + h * 0.025f, yy + h * 0.035f, h * 0.03f, encre, alpha * pi, gras)
                ecrire(c, tronquer(nom, h * 0.024f, normal, rc.width() * 0.5f), rc.right - h * 0.025f, yy + h * 0.035f, h * 0.024f, encre2, alpha * pi, normal, Paint.Align.RIGHT)
            }
            y = rc.bottom + h * 0.02f
        }
        val rd = RectF(colD.left, y, colD.right, bas)
        cartePosee(c, rd, h, alpha * p0)
        ecrire(c, if (e.devoirs.isEmpty()) context.getString(R.string.lanceur_pas_de_devoirs) else context.getString(R.string.veilleview_devoirs_maj), rd.left + h * 0.025f, rd.top + h * 0.04f, h * 0.024f, encre3, alpha * p0, gras, espacement = 0.12f)
        val hd = min(h * 0.09f, (rd.height() - h * 0.06f) / e.devoirs.size.coerceAtLeast(1))
        e.devoirs.forEachIndexed { i, (m, texte) ->
            val pi = etape(ecoule, 500 + 120L * i, 600)
            val yy = rd.top + h * 0.06f + i * hd + (1f - pi) * h * 0.02f
            ecrire(c, m, rd.left + h * 0.025f, yy + h * 0.028f, h * 0.026f, encre, alpha * pi, gras)
            val lignes = decouper(texte, h * 0.022f, normal, rd.width() - h * 0.05f, 2)
            lignes.forEachIndexed { k, l -> ecrire(c, l, rd.left + h * 0.025f, yy + h * 0.056f + k * h * 0.027f, h * 0.022f, encre2, alpha * pi, normal) }
        }
    }

    /** Coupe un texte en lignes qui tiennent dans la largeur ; la dernière finit par « … » si besoin. */
    /** Veille-lot (fluidité) : un texte se découpe une fois par taille et par largeur, puis se relit à chaque image. */
    private class Decoupe(val taille: Float, val police: Typeface, val max: Float, val maxLignes: Int, val lignes: List<String>)
    private val decoupes = HashMap<String, ArrayList<Decoupe>>()

    internal fun decouper(texte: String, taille: Float, police: Typeface, max: Float, maxLignes: Int): List<String> {
        val vus = decoupes[texte]
        if (vus != null) for (i in vus.indices) { val d = vus[i]; if (d.taille == taille && d.police === police && d.max == max && d.maxLignes == maxLignes) return d.lignes }
        val lignes = decouperCalcul(texte, taille, police, max, maxLignes)
        if (decoupes.size > 600) decoupes.clear()
        val l = decoupes.getOrPut(texte) { ArrayList(2) }
        if (l.size >= 4) l.removeAt(0)
        l.add(Decoupe(taille, police, max, maxLignes, lignes))
        return lignes
    }

    private fun decouperCalcul(texte: String, taille: Float, police: Typeface, max: Float, maxLignes: Int): List<String> {
        val mots = texte.split(" ").filter { it.isNotEmpty() }
        val lignes = ArrayList<String>()
        var courante = ""
        for (mot in mots) {
            val essai = if (courante.isEmpty()) mot else "$courante $mot"
            if (largeur(essai, taille, police) <= max) courante = essai
            else {
                if (courante.isNotEmpty()) lignes.add(courante)
                courante = mot
                if (lignes.size == maxLignes) break
            }
        }
        if (courante.isNotEmpty() && lignes.size < maxLignes) lignes.add(courante)
        if (lignes.size > maxLignes) { while (lignes.size > maxLignes) lignes.removeAt(lignes.size - 1) }
        val reste = mots.joinToString(" ").length > lignes.joinToString(" ").length
        if (reste && lignes.isNotEmpty()) lignes[lignes.size - 1] = tronquer(lignes.last() + " …", taille, police, max)
        return lignes
    }

    // --- Agenda ------------------------------------------------------------------

    private fun dessinerAgenda(c: Canvas, w: Float, h: Float, alpha: Float, ecoule: Long, t: Float) {
        val d = donnees ?: return
        titre(c, context.getString(R.string.veilleview_agenda), w, h, alpha, ecoule)
        val gauche = w * 0.08f; val droite = w * 0.92f
        val haut = h * 0.24f; val bas = h * 0.89f
        val rdv = d.agenda.take(8)
        val hl = min(h * 0.075f, (bas - haut) / rdv.size.coerceAtLeast(1))
        var jourCourant = ""
        rdv.forEachIndexed { i, r ->
            val pi = etape(ecoule, 120 + 100L * i, 600)
            val y = haut + i * hl + (1f - pi) * h * 0.03f
            val rect = RectF(gauche, y, droite, y + hl - h * 0.01f)
            cartePosee(c, rect, h, alpha * pi)
            val etiquette = if (r.demain) context.getString(R.string.lanceur_demain_maj) else context.getString(R.string.veilleview_aujourdhui_maj)
            val neuf = etiquette != jourCourant
            jourCourant = etiquette
            val cy = rect.centerY()
            if (neuf) {
                pForme.style = Paint.Style.FILL; pForme.color = if (r.demain) or else pourpre; pForme.alpha = (alpha * pi * 255).toInt()
                val lp = largeur(etiquette.uppercase(), hl * 0.26f, gras) * 1.1f + hl * 0.4f
                c.drawRoundRect(RectF(rect.left + h * 0.02f, cy - hl * 0.2f, rect.left + h * 0.02f + lp, cy + hl * 0.2f), hl * 0.2f, hl * 0.2f, pForme)
                ecrire(c, etiquette.uppercase(), rect.left + h * 0.02f + lp / 2, cy + hl * 0.1f, hl * 0.26f, Color.WHITE, alpha * pi, gras, Paint.Align.CENTER, 0.1f)
            }
            val xh = rect.left + h * 0.02f + h * 0.2f
            ecrire(c, if (r.journee) context.getString(R.string.veilleview_journee) else r.heure, xh, cy + hl * 0.14f, hl * 0.4f, if (r.journee) encre3 else encre, alpha * pi, if (r.journee) normal else gras)
            val xt = xh + h * 0.12f
            ecrire(c, tronquer(r.titre, hl * 0.42f, normal, rect.right - xt - h * 0.25f), xt, cy + hl * 0.15f, hl * 0.42f, encre, alpha * pi, normal)
            ecrire(c, tronquer(listOf(r.lieu, r.calendrier).filter { it.isNotEmpty() }.joinToString(" · "), hl * 0.28f, normal, h * 0.3f), rect.right - h * 0.025f, cy + hl * 0.12f, hl * 0.28f, encre3, alpha * pi, normal, Paint.Align.RIGHT)
        }
    }

    // --- À venir ------------------------------------------------------------------

    private fun dansCombien(ts: Long): String {
        val ecart = ts - System.currentTimeMillis() / 1000
        val cal = Calendar.getInstance().apply { timeInMillis = ts * 1000 }
        val hm = Tr.heure(cal.timeInMillis)
        val aujourdhui = Calendar.getInstance()
        val memeJour = cal.get(Calendar.DAY_OF_YEAR) == aujourdhui.get(Calendar.DAY_OF_YEAR) && cal.get(Calendar.YEAR) == aujourdhui.get(Calendar.YEAR)
        aujourdhui.add(Calendar.DAY_OF_YEAR, 1)
        val demain = cal.get(Calendar.DAY_OF_YEAR) == aujourdhui.get(Calendar.DAY_OF_YEAR) && cal.get(Calendar.YEAR) == aujourdhui.get(Calendar.YEAR)
        return when {
            ecart < 0 -> context.getString(R.string.veilleview_en_cours)
            ecart < 3600 -> context.getString(R.string.veilleview_dans_min, ecart / 60)
            memeJour -> context.getString(R.string.veilleview_aujourdhui_hm, hm)
            demain -> context.getString(R.string.veilleview_demain_hm, hm)
            else -> context.getString(R.string.veilleview_jour_heure, Tr.date(cal.timeInMillis, "EEEd"), hm)
        }
    }

    private fun dessinerAvenir(c: Canvas, w: Float, h: Float, alpha: Float, ecoule: Long, t: Float) {
        val d = donnees ?: return
        titre(c, context.getString(R.string.veilleview_a_venir), w, h, alpha, ecoule)
        val gauche = w * 0.08f; val droite = w * 0.92f
        val haut = h * 0.24f; val bas = h * 0.89f
        val liste = d.avenir.take(8)
        val hl = min(h * 0.078f, (bas - haut) / liste.size.coerceAtLeast(1))
        liste.forEachIndexed { i, a ->
            val pi = etape(ecoule, 120 + 100L * i, 600)
            val y = haut + i * hl + (1f - pi) * h * 0.03f
            val rect = RectF(gauche, y, droite, y + hl - h * 0.012f)
            val al = alpha * pi
            cartePosee(c, rect, h, al, if (a.direct) Color.argb((160 + 95 * sin(t * 5f)).toInt().coerceIn(0, 255), Color.red(rouge), Color.green(rouge), Color.blue(rouge)) else carteBord, if (a.direct) 2f else 1f)
            val cy = rect.centerY()
            // Pastille du sport.
            val couleur = when (a.sport) { "Valorant" -> pourpre; "Rocket League" -> bleu; "Formule 1" -> rouge; "WRC" -> vert; else -> or }
            val lp = largeur(a.sport.uppercase(), hl * 0.24f, gras) * 1.1f + hl * 0.4f
            pForme.style = Paint.Style.FILL; pForme.color = couleur; pForme.alpha = (al * 255).toInt()
            c.drawRoundRect(RectF(rect.left + h * 0.02f, cy - hl * 0.19f, rect.left + h * 0.02f + lp, cy + hl * 0.19f), hl * 0.19f, hl * 0.19f, pForme)
            ecrire(c, a.sport.uppercase(), rect.left + h * 0.02f + lp / 2, cy + hl * 0.09f, hl * 0.24f, Color.WHITE, al, gras, Paint.Align.CENTER, 0.08f)
            var x = rect.left + h * 0.02f + h * 0.19f
            if (a.l1.isNotEmpty() || a.l2.isNotEmpty()) {
                val r = hl * 0.3f
                dessinerLogo(c, a.l1, a.titre.substringBefore("·").trim(), x + r, cy, r, al)
                dessinerLogo(c, a.l2, a.titre.substringAfter("·").trim(), x + r * 3.1f, cy, r, al)
                x += r * 4.6f
            }
            ecrire(c, tronquer(a.titre, hl * 0.4f, gras, rect.right - x - h * 0.3f), x, cy + hl * 0.02f, hl * 0.4f, encre, al, gras)
            ecrire(c, tronquer(a.detail, hl * 0.26f, normal, rect.right - x - h * 0.3f), x, cy + hl * 0.32f, hl * 0.26f, encre3, al, normal)
            val quand = if (a.direct) context.getString(R.string.veilleview_en_direct_maj) else dansCombien(a.ts)
            ecrire(c, quand, rect.right - h * 0.025f, cy + hl * 0.14f, hl * 0.36f, if (a.direct) rouge else if (quand.startsWith("dans")) pourpre else encre2, al, if (a.direct || quand.startsWith("dans")) gras else normal, Paint.Align.RIGHT)
        }
    }

    // --- Chauffage et fioul ---------------------------------------------------------

    private fun dessinerChauffage(c: Canvas, w: Float, h: Float, alpha: Float, ecoule: Long, t: Float) {
        val ch = donnees?.chauffage ?: return
        titre(c, context.getString(R.string.veilleview_chauffage_fioul), w, h, alpha, ecoule)
        val gauche = w * 0.08f; val droite = w * 0.92f
        val haut = h * 0.24f; val bas = h * 0.89f
        val p0 = etape(ecoule, 100, 800)
        val colG = RectF(gauche, haut, gauche + (droite - gauche) * 0.42f, bas)
        cartePosee(c, colG, h, alpha * p0)
        // Dedans en grand, consigne et dehors dessous, la flamme du brûleur qui danse si elle est allumée.
        val pad = h * 0.035f
        ecrire(c, context.getString(R.string.veilleview_dedans_maj), colG.left + pad, colG.top + pad + h * 0.02f, h * 0.024f, encre3, alpha * p0, gras, espacement = 0.12f)
        val dedans = ch.dedans?.let { compter(String.format(Locale.getDefault(), "%.1f°", it), etape(ecoule, 200, 1200)) } ?: "–"
        ecrire(c, dedans, colG.left + pad, colG.top + pad + h * 0.17f, h * 0.15f, encre, alpha * p0, fin)
        val p1 = etape(ecoule, 600, 700)
        var yy = colG.top + pad + h * 0.25f
        ch.consigne?.let { ecrire(c, context.getString(R.string.lanceur_consigne, String.format(Locale.getDefault(), "%.1f°", it)), colG.left + pad, yy, h * 0.032f, encre2, alpha * p1, normal); yy += h * 0.05f }
        ch.dehors?.let { ecrire(c, context.getString(R.string.lanceur_dehors, String.format(Locale.getDefault(), "%.1f°", it)), colG.left + pad, yy, h * 0.032f, encre2, alpha * p1, normal); yy += h * 0.05f }
        // La flamme.
        val fx = colG.right - pad - h * 0.05f; val fy = colG.bottom - pad - h * 0.06f
        val vie = if (ch.bruleurOn) 1f else 0.25f
        for (k in 0 until 3) {
            val osc = if (ch.bruleurOn) sin(t * (6f + k) + k) * h * 0.006f else 0f
            val rayon = h * (0.045f - k * 0.012f)
            pForme.style = Paint.Style.FILL
            pForme.color = when (k) { 0 -> pourpre; 1 -> or; else -> 0xFFFFF1C2.toInt() }
            pForme.alpha = (alpha * p1 * 255 * vie * (if (k == 2) 0.9f else 0.8f)).toInt()
            chemin.reset()
            chemin.moveTo(fx + osc, fy - rayon * 2.1f)
            chemin.quadTo(fx + rayon * 1.3f, fy - rayon * 0.4f, fx, fy + rayon * 0.9f)
            chemin.quadTo(fx - rayon * 1.3f, fy - rayon * 0.4f, fx + osc, fy - rayon * 2.1f)
            c.drawPath(chemin, pForme)
        }
        ecrire(c, if (ch.bruleurOn) context.getString(R.string.veilleview_bruleur_allume) else context.getString(R.string.veilleview_bruleur_etat, ch.bruleur.lowercase(Locale.getDefault()).ifEmpty { context.getString(R.string.veilleview_au_repos) }), fx, fy + h * 0.07f, h * 0.024f, encre3, alpha * p1, normal, Paint.Align.CENTER)
        // Colonne droite : les 24 h dehors / dedans en haut (le tableau « Courbe » n'a plus besoin d'être à part),
        // les litres de fioul par jour en bas, puis mois et saison.
        val series = donnees?.series?.take(2).orEmpty()
        val avecCourbe = series.size >= 2
        val colD = if (avecCourbe) RectF(colG.right + h * 0.02f, haut + (bas - haut) * 0.5f + h * 0.01f, droite, bas) else RectF(colG.right + h * 0.02f, haut, droite, bas)
        if (avecCourbe) tracerCourbes(c, RectF(colG.right + h * 0.02f, haut, droite, haut + (bas - haut) * 0.5f - h * 0.01f), h, alpha * p0, ecoule, t, series, compact = true)
        cartePosee(c, colD, h, alpha * p0)
        ecrire(c, context.getString(R.string.veilleview_fioul_litres_maj), colD.left + pad, colD.top + pad + h * 0.02f, h * 0.024f, encre3, alpha * p0, gras, espacement = 0.12f)
        val jours = ch.jours.takeLast(14)
        val zone = RectF(colD.left + pad, colD.top + pad + h * 0.06f, colD.right - pad, colD.bottom - pad - h * (if (avecCourbe) 0.09f else 0.12f))
        val maxL = (jours.maxOfOrNull { it.second } ?: 1f).coerceAtLeast(1f)
        val lb = zone.width() / jours.size.coerceAtLeast(1)
        jours.forEachIndexed { i, (jour, litres) ->
            val pi = etape(ecoule, 300 + 60L * i, 700)
            val hb = zone.height() * (litres / maxL) * pi
            val x0 = zone.left + i * lb + lb * 0.15f; val x1 = zone.left + (i + 1) * lb - lb * 0.15f
            pForme.style = Paint.Style.FILL; pForme.color = if (i == jours.size - 1) pourpre else or; pForme.alpha = (alpha * 230).toInt()
            c.drawRoundRect(RectF(x0, zone.bottom - hb, x1, zone.bottom), lb * 0.15f, lb * 0.15f, pForme)
            if (litres > 0f && pi > 0.9f && !avecCourbe) ecrire(c, String.format(Locale.getDefault(), "%.0f", litres), (x0 + x1) / 2, zone.bottom - hb - h * 0.01f, h * 0.02f, encre2, alpha, normal, Paint.Align.CENTER)
            if (i % 2 == jours.size % 2) ecrire(c, jour.takeLast(2).trimStart('0'), (x0 + x1) / 2, zone.bottom + h * 0.03f, h * 0.02f, encre3, alpha, normal, Paint.Align.CENTER)
        }
        if (jours.isEmpty()) ecrire(c, context.getString(R.string.veilleview_pas_encore_historique), zone.centerX(), zone.centerY(), h * 0.028f, encre3, alpha, normal, Paint.Align.CENTER)
        val p2 = etape(ecoule, 900, 700)
        val yb = colD.bottom - pad
        ch.mois?.let { ecrire(c, context.getString(R.string.veilleview_litres_mois, String.format(Locale.getDefault(), "%.0f", it)), colD.left + pad, yb, h * 0.034f, encre, alpha * p2, gras) }
        ch.saison?.let { ecrire(c, context.getString(R.string.veilleview_litres_saison, String.format(Locale.getDefault(), "%.0f", it)), colD.right - pad, yb, h * 0.034f, encre2, alpha * p2, normal, Paint.Align.RIGHT) }
    }

    // --- Batteries des appareils ------------------------------------------------------

    private fun dessinerBatteries(c: Canvas, w: Float, h: Float, alpha: Float, ecoule: Long, t: Float) {
        val liste = donnees?.batteries?.take(8) ?: return
        if (liste.isEmpty()) return
        titre(c, context.getString(R.string.lanceur_batteries), w, h, alpha, ecoule)
        val gauche = w * 0.08f; val droite = w * 0.92f
        val haut = h * 0.24f; val bas = h * 0.89f
        // Jusqu'à quatre par rangée, une ou deux rangées.
        val colonnes = if (liste.size <= 4) liste.size else (liste.size + 1) / 2
        val rangees = if (liste.size <= 4) 1 else 2
        val ecart = h * 0.025f
        val lc = (droite - gauche - ecart * (colonnes - 1)) / colonnes
        val hc = (bas - haut - ecart * (rangees - 1)) / rangees
        liste.forEachIndexed { i, b ->
            val col = i % colonnes; val rang = i / colonnes
            val pi = etape(ecoule, 100 + 110L * i, 700)
            val x = gauche + col * (lc + ecart); val y = haut + rang * (hc + ecart) + (1f - pi) * h * 0.03f
            val carteR = RectF(x, y, x + lc, y + hc)
            val faible = b.niveau <= 20 && b.charge != true
            val couleur = when { b.charge == true -> vert; b.niveau <= 20 -> pourpre; b.niveau <= 50 -> or; else -> vert }
            // Une batterie faible respire en rouge.
            val bord = if (faible) pourpre else carteBord
            cartePosee(c, carteR, h, alpha * pi, bord, if (faible) 1.5f + 1f * (0.5f + 0.5f * sin(t * 3f)) else 1f)
            val pad = h * 0.03f
            val taillePetit = min(h * 0.026f, lc * 0.09f)
            ecrire(c, b.nom.uppercase(Locale.getDefault()), carteR.left + pad, carteR.top + pad + taillePetit, taillePetit, encre3, alpha * pi, gras, espacement = 0.1f)
            // La pile : couchée si la carte est large, debout si elle est étroite.
            val pf = etape(ecoule, 350 + 110L * i, 1100)
            val remplissage = (b.niveau / 100f) * pf
            val debout = rangees == 2 || colonnes >= 4
            val pile: RectF
            if (debout) {
                val pw = min(lc * 0.28f, hc * 0.22f); val ph = pw * 2f
                pile = RectF(carteR.right - pad - pw, carteR.centerY() - ph * 0.45f, carteR.right - pad, carteR.centerY() + ph * 0.55f)
            } else {
                val ph = min(hc * 0.14f, lc * 0.16f); val pw = ph * 2.1f
                pile = RectF(carteR.left + pad, carteR.bottom - pad - ph, carteR.left + pad + pw, carteR.bottom - pad)
            }
            val r = h * 0.008f
            pForme.style = Paint.Style.STROKE; pForme.strokeWidth = h * 0.004f; pForme.color = encre2; pForme.alpha = (alpha * pi * 200).toInt()
            c.drawRoundRect(pile, r, r, pForme)
            pForme.style = Paint.Style.FILL
            if (debout) c.drawRoundRect(RectF(pile.centerX() - pile.width() * 0.22f, pile.top - h * 0.012f, pile.centerX() + pile.width() * 0.22f, pile.top), r / 2, r / 2, pForme)
            else c.drawRoundRect(RectF(pile.right, pile.centerY() - pile.height() * 0.22f, pile.right + h * 0.012f, pile.centerY() + pile.height() * 0.22f), r / 2, r / 2, pForme)
            val marge = h * 0.006f
            pForme.color = couleur; pForme.alpha = (alpha * pi * 230).toInt()
            val interieur = if (debout) RectF(pile.left + marge, pile.bottom - marge - (pile.height() - 2 * marge) * remplissage, pile.right - marge, pile.bottom - marge)
                            else RectF(pile.left + marge, pile.top + marge, pile.left + marge + (pile.width() - 2 * marge) * remplissage, pile.bottom - marge)
            if (remplissage > 0.01f) c.drawRoundRect(interieur, r * 0.6f, r * 0.6f, pForme)
            if (b.charge == true) {
                // L'éclair, qui scintille doucement.
                val ex = pile.centerX(); val ey = pile.centerY(); val eh = pile.height() * (if (debout) 0.3f else 0.4f)
                chemin.reset()
                chemin.moveTo(ex + eh * 0.15f, ey - eh); chemin.lineTo(ex - eh * 0.35f, ey + eh * 0.1f); chemin.lineTo(ex, ey + eh * 0.1f)
                chemin.lineTo(ex - eh * 0.15f, ey + eh); chemin.lineTo(ex + eh * 0.35f, ey - eh * 0.1f); chemin.lineTo(ex, ey - eh * 0.1f); chemin.close()
                pForme.color = if (sombre) 0xFFFFF1C2.toInt() else 0xFF2B1B1E.toInt(); pForme.alpha = (alpha * pi * (170 + 80 * sin(t * 4f))).toInt().coerceIn(0, 255)
                c.drawPath(chemin, pForme)
            }
            // Le pourcentage en grand, qui compte.
            val tailleGrand = if (debout) min(hc * 0.30f, lc * 0.42f) else min(hc * 0.42f, lc * 0.30f)
            val yGrand = if (debout) carteR.centerY() + tailleGrand * 0.35f else carteR.top + pad + taillePetit + h * 0.02f + tailleGrand
            ecrire(c, compter("${b.niveau} %", pf), carteR.left + pad, yGrand, tailleGrand, if (faible) pourpre else encre, alpha * pi, fin)
            // La ligne du dessous : en charge, ou le rythme et l'autonomie.
            val p2 = etape(ecoule, 700 + 110L * i, 700)
            val ligne = when {
                b.charge == true -> if (b.pente != null && b.pente > 0.2) context.getString(R.string.veilleview_en_charge_pente, String.format(Locale.getDefault(), "%.0f", b.pente)) else context.getString(R.string.veilleview_en_charge)
                b.pente != null && b.pente < -0.2 -> {
                    val heures = b.niveau / -b.pente
                    context.getString(R.string.veilleview_pente_reste, String.format(Locale.getDefault(), "%.0f", b.pente), if (heures >= 48) context.getString(R.string.veilleview_jours_court, String.format(Locale.getDefault(), "%.0f", heures / 24)) else context.getString(R.string.veilleview_heures_court, String.format(Locale.getDefault(), "%.0f", heures)))
                }
                b.pente != null -> context.getString(R.string.veilleview_stable)
                b.niveau <= 20 -> context.getString(R.string.veilleview_a_recharger)
                else -> ""
            }
            if (ligne.isNotEmpty()) {
                val yl = if (debout) carteR.bottom - pad else yGrand + h * 0.05f
                ecrire(c, ligne, carteR.left + pad, yl, taillePetit * 0.95f, if (faible) pourpre else encre2, alpha * p2, normal)
            }
        }
    }

    // --- Tableaux composés depuis Home Assistant --------------------------------------

    private fun dessinerEntites(c: Canvas, w: Float, h: Float, alpha: Float, ecoule: Long, t: Float, def: Def) {
        val cases = def.cases.take(8)
        if (cases.isEmpty()) return
        titre(c, def.titre, w, h, alpha, ecoule)
        val gauche = w * 0.08f; val droite = w * 0.92f
        val haut = h * 0.24f; val bas = h * 0.89f
        // La grille s'adapte à l'écran : on retient le nombre de colonnes qui donne les cases les mieux
        // proportionnées (télé couchée : 4 x 2 ; téléphone debout : 2 x 4 ou une seule colonne).
        val ecart = min(h, w) * 0.025f
        var colonnes = 1; var meilleur = Float.MAX_VALUE
        for (essai in 1..min(4, cases.size)) {
            val rg = (cases.size + essai - 1) / essai
            val forme = ((droite - gauche - ecart * (essai - 1)) / essai) / ((bas - haut - ecart * (rg - 1)) / rg)
            val ecartForme = kotlin.math.abs(kotlin.math.ln(forme / 1.45f))
            if (ecartForme < meilleur) { meilleur = ecartForme; colonnes = essai }
        }
        val rangees = (cases.size + colonnes - 1) / colonnes
        val hc = (bas - haut - ecart * (rangees - 1)) / rangees
        cases.forEachIndexed { i, k ->
            val col = i % colonnes; val rang = i / colonnes
            // Une dernière rangée incomplète s'étire sur toute la largeur.
            val dansRangee = if (rang == rangees - 1) cases.size - rang * colonnes else colonnes
            val lc = (droite - gauche - ecart * (dansRangee - 1)) / dansRangee
            val pi = etape(ecoule, 100 + 110L * i, 700)
            val x = gauche + col * (lc + ecart); val y = haut + rang * (hc + ecart) + (1f - pi) * h * 0.03f
            val r = RectF(x, y, x + lc, y + hc)
            cartePosee(c, r, h, alpha * pi)
            val pad = h * 0.03f
            val petit = min(h * 0.026f, lc * 0.07f)
            ecrire(c, k.nom.uppercase(Locale.getDefault()), r.left + pad, r.top + pad + petit, petit, encre3, alpha * pi, gras, espacement = 0.1f)
            val pf = etape(ecoule, 350 + 110L * i, 1100)
            when (k.rendu) {
                "jauge" -> dessinerCaseJauge(c, r, h, pad, petit, k, alpha * pi, pf)
                "courbe" -> dessinerCaseCourbe(c, r, h, pad, petit, k, alpha * pi, pf)
                "etat" -> dessinerCaseEtat(c, r, h, pad, petit, k, alpha * pi, pf, t)
                "valeur" -> dessinerCaseValeur(c, r, h, pad, petit, k, alpha * pi, pf)
                else -> {
                    // Du texte : l'état tel quel, en grand s'il est court.
                    val taille = if (k.texte.length <= 12) min(hc * 0.28f, lc * 0.14f) else min(hc * 0.16f, lc * 0.08f)
                    // Jamais de débordement : le texte passe à la ligne dans la case, et se coupe s'il reste trop long.
                    val interligne = taille * 1.25f
                    val place = ((r.bottom - pad) - (r.top + pad + petit + h * 0.02f)) / interligne
                    val lignes = decouper(k.texte, taille, fin, lc - pad * 2, max(1, place.toInt()))
                    lignes.forEachIndexed { n, ligne -> ecrire(c, ligne, r.left + pad, r.top + pad + petit + h * 0.02f + taille + n * interligne, taille, encre, alpha * pi, fin) }
                }
            }
        }
    }

    // ------------------------------------------------ tableaux de bord Home Assistant

    private var tailleVue = 0L
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        // La mise en page des tableaux de bord dépend de l'écran : on la refait s'il change (rotation).
        val cle = w.toLong() * 100_000L + h
        if (cle != tailleVue) { tailleVue = cle; if (actif && donnees != null) main.post { reconstruire() } }
    }

    /**
     * Répartit les sections d'un tableau de bord sur l'écran : colonnes selon la largeur (une seule sur un
     * téléphone debout), cartes à leur largeur d'origine (sur 12) et à leur hauteur, et autant d'écrans
     * qu'il en faut quand tout ne tient pas.
     */
    private fun paginer(def: Def): List<PageDash> {
        if (def.sections.isEmpty()) return emptyList()
        val w = (if (width > 0) width else resources.displayMetrics.widthPixels).toFloat()
        val h = (if (height > 0) height else resources.displayMetrics.heightPixels).toFloat()
        val debout = w < h * 0.9f
        val cols = when {
            debout -> 1
            w / h < 1.5f -> min(2, max(1, def.sections.size))
            else -> min(def.colonnes.coerceIn(1, 4), max(1, def.sections.size)).coerceAtMost(3)
        }
        val unitesMax = if (debout) 9 else 5
        // Une carte trop étroite serait illisible de loin : largeur minimale selon le nombre de colonnes.
        val largeurMin = if (cols >= 3 || debout) 6 else if (cols == 2) 4 else 3
        val blocs = ArrayList<BlocDash>()
        for (s in def.sections) {
            val rangees = ArrayList<List<Case>>()
            var courante = ArrayList<Case>(); var somme = 0
            for (k in s.cases) {
                val l = max(largeurMin, k.largeur).coerceAtMost(12)
                if (somme + l > 12 && courante.isNotEmpty()) { rangees.add(courante); courante = ArrayList(); somme = 0 }
                courante.add(k); somme += l
            }
            if (courante.isNotEmpty()) rangees.add(courante)
            // Une section plus haute que l'écran se poursuit dans un bloc suivant, sous le même titre.
            var lot = ArrayList<List<Case>>(); var u = 0f; var premier = true
            fun entete() = if (s.titre.isNotEmpty()) 0.55f else 0f
            for (r in rangees) {
                val hr = r.maxOf { it.hauteur }.toFloat()
                if (lot.isNotEmpty() && entete() + u + hr > unitesMax) {
                    blocs.add(BlocDash(if (premier) s.titre else s.titre + context.getString(R.string.veilleview_suite).takeIf { s.titre.isNotEmpty() }.orEmpty(), lot, entete() + u)); premier = false
                    lot = ArrayList(); u = 0f
                }
                lot.add(r); u += hr
            }
            if (lot.isNotEmpty()) blocs.add(BlocDash(if (premier) s.titre else s.titre + context.getString(R.string.veilleview_suite).takeIf { s.titre.isNotEmpty() }.orEmpty(), lot, entete() + u))
        }
        // Chaque bloc va dans la colonne la moins remplie où il tient ; sinon on ouvre un écran de plus.
        val pages = ArrayList<List<List<BlocDash>>>()
        var colonnes = List(cols) { ArrayList<BlocDash>() }; var hauteurs = FloatArray(cols)
        for (b in blocs) {
            val ecart = 0.25f
            // 1.90.x : un bloc sans titre en tête de colonne compte la bande de titre réservée (DispositionDash).
            fun cout(i: Int) = b.unites + if (hauteurs[i] > 0f) ecart
                else if (b.titre.isEmpty() && DispositionDash.ALIGNER_TITRES) DispositionDash.TITRE else 0f
            val choix = (0 until cols).filter { hauteurs[it] + cout(it) <= unitesMax + 0.01f }.minByOrNull { hauteurs[it] }
            if (choix == null) {
                pages.add(colonnes); colonnes = List(cols) { ArrayList<BlocDash>() }; hauteurs = FloatArray(cols)
                colonnes[0].add(b); hauteurs[0] = cout(0)
            } else {
                hauteurs[choix] += cout(choix); colonnes[choix].add(b)
            }
        }
        if (colonnes.any { it.isNotEmpty() }) pages.add(colonnes)
        return pages.take(8).mapIndexed { i, p -> PageDash(def, p.filter { it.isNotEmpty() }, unitesMax, i + 1, min(pages.size, 8)) }
    }

    /**
     * Veille-lot : la grille d'un écran de tableau de bord tombe au pixel physique. Colonnes et cartes sont réparties
     * en pixels entiers (GrilleVeille) avec des écarts tous égaux ; la hauteur d'unité est entière, donc chaque rangée
     * aussi. Seule l'entrée en scène (décalage vertical qui s'éteint) passe entre deux pixels, le temps d'arriver.
     */
    private var pastille = false
    private val rCarte = RectF()

    // Pour les essais (VeillePerfTest) : images dessinées, images en retard, et les cartes posées (seulement sur demande).
    @Volatile internal var imagesDessinees = 0
    /** Pour les essais : quand la première réponse de Home Assistant est arrivée (0 : pas encore), et l'état de la veille. */
    @Volatile internal var premiereReussiteA = 0L
    @Volatile internal var demarreeA = 0L
    internal fun etatEssai(): String = listOf("reponse" to (premiereReussiteA > 0), "erreur" to erreur, "tableaux" to tableaux.map { it.genre },
        "indice" to indice, "web" to web?.montree, "actif" to actif, "couverte" to couverte, "verrouille" to Etat.verrouilleEffectif())
        .joinToString(" ") { (cle, valeur) -> "$cle=$valeur" }
    @Volatile internal var imagesLentes = 0
    @Volatile internal var noterRects = false
    /**
     * Les cartes posées par la dernière image complète (colonne, boîte), publiées d'un seul coup en fin d'image.
     * Avant : une liste vidée au début de chaque image puis remplie carte par carte ; l'essai, qui lit depuis un autre
     * fil, pouvait tomber entre les deux et trouver « aucune carte posée » (VeillePerfTest instable sur télé, 15/10).
     */
    @Volatile internal var dernieresCartes: List<Pair<Int, RectF>> = emptyList()
    /** Pour les essais : le nom et la valeur affichée de chaque case vue (sur demande seulement). */
    internal val casesVues = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Veille-lot : le dessin et les médias des cases typées (images, caméras, iframe, derniers passages). */
    internal val typees = VeilleTypees(this)
    internal val pastilleActive: Boolean get() = pastille

    /** Les cases typées d'un écran de tableau de bord (calculé à la pagination, gardé avec la page). */
    private fun casesTypees(p: PageDash): List<CasesVeille.Typee> = p.typees

    private fun dessinerDash(c: Canvas, w: Float, h: Float, alpha: Float, ecoule: Long, t: Float, page: PageDash) {
        titre(c, if (page.total > 1) "${page.def.titre}  ·  ${page.numero}/${page.total}" else page.def.titre, w, h, alpha, ecoule)
        // 1.90.x : la mise en place est dans DispositionDash (haut des cartes aligné entre colonnes, essayé en JVM).
        val modele = page.colonnes.map { blocs -> blocs.map { b -> DispositionDash.Bloc(b.titre, b.rangees.map { r -> r.map { DispositionDash.Carte(it.largeur, it.hauteur) } }) } }
        val plan = DispositionDash.placer(modele, page.unitesMax, w, h, min(w, h) * 0.03f)
        val hu = plan.hu
        var rang = 0
        val notees = if (noterRects) ArrayList<Pair<Int, RectF>>() else null
        for (ti in plan.titres) {
            val bloc = page.colonnes[ti.colonne][ti.bloc]
            val pt = etape(ecoule, 80 + 60L * rang, 600)
            val taille = hu * 0.22f
            ecrire(c, decouper(bloc.titre.uppercase(Locale.getDefault()), taille, gras, ti.largeur, 1).firstOrNull() ?: "", ti.x, ti.ligne, taille, encre2, alpha * pt, gras, espacement = 0.08f)
        }
        for (pl in plan.cartes) {
            val k = page.colonnes[pl.colonne][pl.bloc].rangees[pl.rangee][pl.index]
            val pi = etape(ecoule, 120 + 55L * rang, 650); rang++
            val d = (1f - pi) * hu * 0.18f
            // Veille-lot (grille au pixel) : bords en pixels entiers, écarts tous égaux ; seule l'entrée en scène passe entre deux pixels.
            val b = DispositionDash.auPixel(pl.boite, hu * 0.1f)
            rCarte.set(b[0].toFloat(), b[1] + d, b[2].toFloat(), b[3] + d)
            dessinerCarteDash(c, rCarte, h, hu, k, alpha * pi, etape(ecoule, 300 + 55L * rang, 1000), t)
            if (notees != null && pi >= 1f) { notees.add(pl.colonne to RectF(rCarte)); casesVues.add(k.nom + "|" + formatValeur(k.valeur, k.unite)) }
        }
        // Une image complète, publiée d'un seul coup (jamais une liste à moitié remplie pour un autre fil).
        if (notees != null) dernieresCartes = notees
    }

    /** Veille-lot : la pastille, petit fond arrondi derrière une valeur ou une icône (réglage « avec pastille »). */
    private val rPastille = RectF()
    private fun pastilleDerriere(c: Canvas, gauche: Float, haut: Float, droite: Float, bas: Float, a: Float) {
        if (!pastille) return
        rPastille.set(Math.round(gauche).toFloat(), Math.round(haut).toFloat(), Math.round(droite).toFloat(), Math.round(bas).toFloat())
        pForme.style = Paint.Style.FILL
        pForme.color = Palette.melanger(carte or 0xFF000000.toInt(), encre, 0.07f); pForme.alpha = (a * 255).toInt().coerceIn(0, 255)
        val rayon = rPastille.height() * 0.32f
        c.drawRoundRect(rPastille, rayon, rayon, pForme)
    }

    /** l'auteur (15/10) : comment les ouvertures se montrent sur cet appareil (style, couleurs, seulement ouvertes). */
    private var indicateur = IndicateurOuverture.Reglages()

    /**
     * Le voyant d'une case « état », à gauche ([x], centré en [cy]) ; rend la largeur prise. Une ouverture suit les
     * réglages de l'appareil (pastille, icône, liseré, aucun ; couleurs ; seulement ouverte) ; un autre état garde la
     * pastille d'avant. Un état inconnu : indicateur neutre (or), jamais la couleur « fermé ».
     */
    private fun indicateurEtat(c: Canvas, k: Case, carte: RectF, x: Float, cy: Float, rayon: Float, a: Float, pf: Float, t: Float): Float {
        val on = k.on
        if (!k.ouverture) {
            val couleur = when (on) { true -> if (k.classe in setOf("smoke", "problem", "moisture")) pourpre else vert; false -> encre3; null -> or }
            pastilleEtat(c, x + rayon, cy, rayon, couleur, on == true, a, pf, t)
            return rayon * 2
        }
        val r = indicateur
        if (!IndicateurOuverture.montrer(r, on)) return 0f
        val couleur = IndicateurOuverture.argb(IndicateurOuverture.couleurEtat(r, on), or, encre3, or)
        when (r.style) {
            IndicateurOuverture.ICONE -> {
                pForme.color = couleur; pForme.alpha = (a * 240 * pf).toInt().coerceIn(0, 255)
                IndicateurOuverture.dessinerIcone(c, k.typeOuverture, on, x + rayon * 1.2f, cy, rayon * 1.2f, pForme)
                return rayon * 2.4f
            }
            IndicateurOuverture.LISERE -> {
                // Un liseré de couleur au bord gauche de la case, sur toute sa hauteur (lisible de loin).
                pForme.style = Paint.Style.FILL; pForme.color = couleur; pForme.alpha = (a * 230 * pf).toInt().coerceIn(0, 255)
                // Une épaisseur fixe à l'échelle de l'écran (une grande case n'a pas un liseré plus gros), le texte décalé.
                val e = max(3f, height * 0.0045f)
                c.drawRoundRect(RectF(carte.left + e, carte.top + carte.height() * 0.18f, carte.left + e * 2.2f, carte.bottom - carte.height() * 0.18f), e, e, pForme)
                return max(0f, carte.left + e * 3.5f - x)
            }
            else -> { pastilleEtat(c, x + rayon, cy, rayon, couleur, on == true, a, pf, t); return rayon * 2 }
        }
    }

    private fun pastilleEtat(c: Canvas, cx: Float, cy: Float, rayon: Float, couleur: Int, halo: Boolean, a: Float, pf: Float, t: Float) {
        pForme.style = Paint.Style.FILL
        if (halo) { pForme.color = couleur; pForme.alpha = (a * (40 + 30 * sin(t * 2f))).toInt().coerceIn(0, 255); c.drawCircle(cx, cy, rayon * (1.5f + 0.15f * sin(t * 2f)) * pf, pForme) }
        pForme.color = couleur; pForme.alpha = (a * 230).toInt()
        c.drawCircle(cx, cy, rayon * pf, pForme)
    }

    /** Une carte d'un tableau de bord, redessinée dans le thème : tuile basse sur une rangée, grande case au-delà. */
    private fun dessinerCarteDash(c: Canvas, r: RectF, h: Float, hu: Float, k: Case, a: Float, pf: Float, t: Float) {
        // Veille-lot : une case typée (météo, agenda, image, radar, caméra, iframe, derniers passages) a son propre dessin.
        k.typee?.let { ty -> typees.dessiner(c, r, h, hu, k.nom, k.texte, ty, a, pf, t, derive[0], derive[1]); return }
        if (r.height() > hu * 1.5f) {
            // Assez haute : le dessin des cases composées (jauge en arc, courbe, valeur en grand), à l'échelle de la carte.
            val echelle = min(h, r.height() / 0.31f)
            cartePosee(c, r, echelle, a, trait = DispositionDash.bordPourCarte(h, r.height(), hu), rayon = DispositionDash.rayon(h, hu))
            val pad = echelle * 0.03f
            val petit = min(echelle * 0.026f, r.width() * 0.07f)
            ecrire(c, decouper(k.nom.uppercase(Locale.getDefault()), petit, gras, r.width() - pad * 2, 1).firstOrNull() ?: "", r.left + pad, r.top + pad + petit, petit, encre3, a, gras, espacement = 0.1f)
            when (k.rendu) {
                "jauge" -> dessinerCaseJauge(c, r, echelle, pad, petit, k, a, pf)
                "courbe" -> dessinerCaseCourbe(c, r, echelle, pad, petit, k, a, pf)
                "etat" -> dessinerCaseEtat(c, r, echelle, pad, petit, k, a, pf, t)
                "valeur" -> dessinerCaseValeur(c, r, echelle, pad, petit, k, a, pf)
                else -> {
                    val taille = min(r.height() * 0.16f, r.width() * 0.07f)
                    val interligne = taille * 1.25f
                    val place = ((r.bottom - pad) - (r.top + pad + petit + echelle * 0.02f)) / interligne
                    decouper(k.texte, taille, fin, r.width() - pad * 2, max(1, place.toInt())).forEachIndexed { n, ligne ->
                        ecrire(c, ligne, r.left + pad, r.top + pad + petit + echelle * 0.02f + taille + n * interligne, taille, encre, a, fin)
                    }
                }
            }
            return
        }
        // Tuile : le nom au-dessus, la valeur ou l'état en dessous ; à gauche la pastille d'état, à droite la jauge ou la courbe.
        cartePosee(c, r, r.height() / 0.42f, a, trait = DispositionDash.bordPourCarte(h, r.height(), hu), rayon = DispositionDash.rayon(h, hu))
        val pad = r.height() * 0.2f
        val tNom = r.height() * 0.2f; val tVal = r.height() * 0.32f
        var x = r.left + pad
        var droite = r.right - pad
        if (k.rendu == "etat") {
            val rayon = r.height() * 0.13f
            val montre = !k.ouverture || IndicateurOuverture.montrer(indicateur, k.on)
            if (montre && !(k.ouverture && indicateur.style == IndicateurOuverture.LISERE)) {
                pastilleDerriere(c, x - rayon * 0.6f, r.centerY() - rayon * 1.6f, x + rayon * 2.6f, r.centerY() + rayon * 1.6f, a)
                if (pastille) x += rayon * 0.4f
            }
            val pris = indicateurEtat(c, k, r, x, r.centerY(), rayon, a, pf, t)
            if (pris > 0f) x += pris + pad * 0.7f
        }
        if (k.rendu == "courbe" && k.serie.size >= 2 && r.width() > r.height() * 2.6f) {
            // Une petite courbe 24 h sur la moitié droite.
            val zone = RectF(r.left + r.width() * 0.52f, r.top + pad * 0.9f, r.right - pad, r.bottom - pad * 0.9f)
            val vmin = k.serie.minOf { it.second }; val vmax = k.serie.maxOf { it.second }
            val etendue = max(0.001f, vmax - vmin)
            val visibles = (k.serie.size * pf).toInt().coerceIn(2, k.serie.size)
            chemin.reset()
            for (i in 0 until visibles) {
                val px = zone.left + zone.width() * i / (k.serie.size - 1f)
                val py = zone.bottom - zone.height() * ((k.serie[i].second - vmin) / etendue)
                if (i == 0) chemin.moveTo(px, py) else chemin.lineTo(px, py)
            }
            pForme.style = Paint.Style.STROKE; pForme.strokeWidth = r.height() * 0.035f; pForme.strokeCap = Paint.Cap.ROUND; pForme.strokeJoin = Paint.Join.ROUND
            pForme.color = or; pForme.alpha = (a * 230).toInt()
            c.drawPath(chemin, pForme)
            pForme.style = Paint.Style.FILL
            droite = zone.left - pad * 0.5f
        }
        val largeurTexte = max(1f, droite - x)
        ecrire(c, decouper(k.nom, tNom, normal, largeurTexte, 1).firstOrNull() ?: "", x, r.top + pad + tNom * 0.85f, tNom, encre2, a, normal)
        val valeur = when (k.rendu) {
            "etat", "texte" -> k.texte.replaceFirstChar { it.uppercase() }
            else -> if (k.valeur != null) compter(formatValeur(k.valeur, k.unite), pf) + (k.consigne?.let { "  \u2192 ${formatValeur(it, k.unite)}" } ?: "") else k.texte
        }
        val montre = decouper(valeur, tVal, fin, largeurTexte - (if (pastille) tVal * 0.5f else 0f), 1).firstOrNull() ?: ""
        if (pastille && montre.isNotEmpty()) {
            // La pastille épouse la valeur : même marge partout, même hauteur sur toute la rangée.
            val bas = r.bottom - pad * 0.95f
            pastilleDerriere(c, x, bas - tVal * 0.95f, x + largeur(montre, tVal, fin) + tVal * 0.5f, bas + tVal * 0.28f, a)
            ecrire(c, montre, x + tVal * 0.25f, bas, tVal, encre, a, fin)
        } else ecrire(c, montre, x, r.bottom - pad * 0.95f, tVal, encre, a, fin)
        if (k.rendu == "jauge" && k.valeur != null) {
            // La jauge devient un trait de progression au bas de la tuile.
            val part = (((k.valeur - k.min) / (k.max - k.min)).toFloat().coerceIn(0f, 1f)) * pf
            val y = r.bottom - r.height() * 0.09f; val e = r.height() * 0.035f
            pForme.style = Paint.Style.FILL
            pForme.color = encre3; pForme.alpha = (a * 50).toInt()
            c.drawRoundRect(RectF(r.left + pad, y - e, r.right - pad, y + e), e, e, pForme)
            pForme.color = if (k.classe == "battery" && part < 0.2f) pourpre else or; pForme.alpha = (a * 230).toInt()
            if (part > 0.005f) c.drawRoundRect(RectF(r.left + pad, y - e, r.left + pad + (r.width() - pad * 2) * part, y + e), e, e, pForme)
        }
    }

    private fun formatValeur(v: Double?, unite: String): String {
        if (v == null) return "–"
        val n = if (kotlin.math.abs(v) >= 100 || v == Math.rint(v)) String.format(Locale.getDefault(), "%.0f", v) else String.format(Locale.getDefault(), "%.1f", v)
        return if (unite == "°" || unite.startsWith("°")) "$n°" else if (unite.isEmpty()) n else "$n $unite"
    }

    private fun dessinerCaseValeur(c: Canvas, r: RectF, h: Float, pad: Float, petit: Float, k: Case, a: Float, pf: Float) {
        val taille = min(r.height() * 0.40f, r.width() * 0.22f)
        val texte = if (k.valeur != null) compter(formatValeur(k.valeur, k.unite), pf) else k.texte
        ecrire(c, texte, r.left + pad, r.top + pad + petit + h * 0.02f + taille, taille, encre, a, fin)
        k.consigne?.let { ecrire(c, context.getString(R.string.lanceur_consigne, formatValeur(it, k.unite)), r.left + pad, r.bottom - pad, petit, encre2, a * pf, normal) }
    }

    private fun dessinerCaseJauge(c: Canvas, r: RectF, h: Float, pad: Float, petit: Float, k: Case, a: Float, pf: Float) {
        val v = k.valeur
        val part = if (v == null) 0f else (((v - k.min) / (k.max - k.min)).toFloat().coerceIn(0f, 1f)) * pf
        val rayon = min(r.height() * 0.32f, r.width() * 0.26f)
        val cx = r.centerX(); val cy = r.top + pad + petit + h * 0.03f + rayon
        val cadre = RectF(cx - rayon, cy - rayon, cx + rayon, cy + rayon)
        pForme.style = Paint.Style.STROKE; pForme.strokeWidth = rayon * 0.16f; pForme.strokeCap = Paint.Cap.ROUND
        pForme.color = encre3; pForme.alpha = (a * 50).toInt()
        c.drawArc(cadre, 135f, 270f, false, pForme)
        val couleur = when { k.classe == "battery" && part < 0.2f -> pourpre; k.classe == "battery" && part < 0.5f -> or; else -> or }
        pForme.color = couleur; pForme.alpha = (a * 230).toInt()
        if (part > 0.005f) c.drawArc(cadre, 135f, 270f * part, false, pForme)
        pForme.style = Paint.Style.FILL
        val taille = rayon * 0.62f
        ecrire(c, if (v == null) "–" else compter(formatValeur(v, k.unite), pf), cx, cy + taille * 0.35f, taille, encre, a, fin, Paint.Align.CENTER)
    }

    private fun dessinerCaseEtat(c: Canvas, r: RectF, h: Float, pad: Float, petit: Float, k: Case, a: Float, pf: Float, t: Float) {
        val rayon = min(r.height() * 0.11f, r.width() * 0.1f)
        val cy = r.centerY()
        // Le voyant (un halo qui respire autour d'un état actif), l'icône ou le liseré, selon les réglages des ouvertures.
        val pris = indicateurEtat(c, k, r, r.left + pad, cy, rayon, a, pf, t)
        val taille = min(r.height() * 0.2f, r.width() * 0.11f)
        ecrire(c, k.texte.replaceFirstChar { it.uppercase() }, r.left + pad + if (pris > 0f) pris + rayon * 0.8f else 0f, cy + taille * 0.35f, taille, encre, a * pf, normal)
    }

    private fun dessinerCaseCourbe(c: Canvas, r: RectF, h: Float, pad: Float, petit: Float, k: Case, a: Float, pf: Float) {
        val taille = min(r.height() * 0.26f, r.width() * 0.16f)
        ecrire(c, if (k.valeur != null) compter(formatValeur(k.valeur, k.unite), pf) else k.texte, r.left + pad, r.top + pad + petit + h * 0.015f + taille, taille, encre, a, fin)
        val pts = k.serie
        if (pts.size < 2) { ecrire(c, context.getString(R.string.veilleview_pas_historique), r.left + pad, r.bottom - pad, petit, encre3, a, normal); return }
        // 1.93.06 (photo de l'auteur, graphiques de la box : « 0,0 Mbit/s … 24 h … 322 Mbit/s » sous la courbe, peu lisible) : le
        // format des widgets — la valeur actuelle en haut, l'échelle des valeurs à gauche (2 ou 3 graduations rondes, l'unité
        // sur la plus haute, traits légers), les heures dessous.
        val vmin = pts.minOf { it.second }; val vmax = pts.maxOf { it.second }
        val marge = max(if (vmax - vmin < 1e-6f) max(abs(vmax) * 0.1f, 0.5f) else 0f, (vmax - vmin) * 0.1f)
        val grads = WidgetsHa.graduations((vmin - marge).toDouble().let { if (vmin >= 0f) max(0.0, it) else it }, (vmax + marge).toDouble(), 3)
        val bas = min(vmin - marge, grads.firstOrNull()?.toFloat() ?: vmin - marge)
        val hautV = max(vmax + marge, grads.lastOrNull()?.toFloat() ?: vmax + marge)
        val tEchelle = petit * 0.8f
        val etiquettes = grads.mapIndexed { i, g -> if (i == grads.size - 1) formatValeur(g, k.unite) else WidgetsHa.nombre(g) }
        val gouttiere = (etiquettes.maxOfOrNull { largeur(it, tEchelle, normal) } ?: 0f) + pad * 0.4f
        val zone = RectF(r.left + pad + gouttiere, r.top + pad + petit + h * 0.03f + taille, r.right - pad, r.bottom - pad - tEchelle * 1.5f)
        for ((i, g) in grads.withIndex()) {
            val y = zone.bottom - zone.height() * ((g.toFloat() - bas) / (hautV - bas))
            pForme.style = Paint.Style.STROKE; pForme.strokeWidth = h * 0.0012f; pForme.color = encre3; pForme.alpha = (a * 50).toInt()
            c.drawLine(zone.left, y, zone.right, y, pForme)
            ecrire(c, etiquettes[i], zone.left - pad * 0.4f, y + tEchelle * 0.35f, tEchelle, encre3, a, normal, Paint.Align.RIGHT)
        }
        // L'axe du temps : les points à leur heure (horodatages en s ou en ms), et un repère toutes les 6 h.
        fun ms(ts: Long) = if (ts < 100_000_000_000L) ts * 1000L else ts
        val t0 = ms(pts.first().first); val t1 = ms(pts.last().first)
        val parTemps = t1 > t0
        val n = pts.size
        fun xDe(i: Int) = if (parTemps) zone.left + zone.width() * (ms(pts[i].first) - t0) / (t1 - t0).toFloat() else zone.left + zone.width() * i / (n - 1)
        if (parTemps) {
            val tz = java.util.TimeZone.getDefault()
            val format = android.text.format.DateFormat.getTimeFormat(context)
            for (tm in WidgetsHa.reperesTemps(t0, t1, "24h") { tz.getOffset(it).toLong() }) {
                val x = zone.left + zone.width() * (tm - t0) / (t1 - t0).toFloat()
                ecrire(c, format.format(java.util.Date(tm)), x, r.bottom - pad * 0.4f, tEchelle, encre3, a * 0.9f, normal, Paint.Align.CENTER)
            }
        }
        val visibles = (n * pf).toInt().coerceIn(2, n)
        chemin.reset()
        for (i in 0 until visibles) {
            val x = xDe(i)
            val y = zone.bottom - zone.height() * (pts[i].second - bas) / (hautV - bas)
            if (i == 0) chemin.moveTo(x, y) else chemin.lineTo(x, y)
        }
        pForme.style = Paint.Style.STROKE; pForme.strokeWidth = h * 0.005f; pForme.strokeCap = Paint.Cap.ROUND; pForme.strokeJoin = Paint.Join.ROUND
        pForme.color = or; pForme.alpha = (a * 230).toInt()
        c.drawPath(chemin, pForme)
        // Le remplissage léger sous la courbe.
        val remplissage = Path(chemin)
        val xFin = xDe(visibles - 1)
        remplissage.lineTo(xFin, zone.bottom); remplissage.lineTo(xDe(0), zone.bottom); remplissage.close()
        pForme.style = Paint.Style.FILL; pForme.alpha = (a * 35).toInt()
        c.drawPath(remplissage, pForme)
    }

    // --- Photos -----------------------------------------------------------------------

    private fun dessinerPhotos(c: Canvas, w: Float, h: Float, alpha: Float, ecoule: Long, t: Float, noms: List<String>) {
        if (noms.isEmpty()) return
        // Une photo toutes les dix secondes, fondu enchaîné, lent zoom (Ken Burns).
        val parPhoto = dureePhotosMs / noms.size
        val k = ((ecoule / parPhoto).toInt()).coerceIn(0, noms.size - 1)
        val dansPhoto = (ecoule - k * parPhoto) / parPhoto.toFloat()
        noms.getOrNull(k + 1)?.let { logos.photo(it) }  // la suivante se charge pendant celle-ci
        val bm = logos.photo(noms[k])
        val fondu = lisser(min(1f, (ecoule - k * parPhoto) / 900f))
        val cadre = RectF(w * 0.08f, h * 0.1f, w * 0.92f, h * 0.9f)
        cartePosee(c, cadre, h, alpha)
        if (bm == null || bm.isRecycled) {
            ecrire(c, "…", cadre.centerX(), cadre.centerY(), h * 0.08f, encre3, alpha, fin, Paint.Align.CENTER)
            return
        }
        c.save()
        chemin.reset(); chemin.addRoundRect(RectF(cadre.left + h * 0.01f, cadre.top + h * 0.01f, cadre.right - h * 0.01f, cadre.bottom - h * 0.01f), h * 0.025f, h * 0.025f, Path.Direction.CW)
        c.clipPath(chemin)
        val zone = RectF(cadre.left + h * 0.01f, cadre.top + h * 0.01f, cadre.right - h * 0.01f, cadre.bottom - h * 0.01f)
        val ratio = maxOf(zone.width() / bm.width, zone.height() / bm.height)
        val zoom = 1.04f + 0.06f * dansPhoto
        val lw = bm.width * ratio * zoom; val lh = bm.height * ratio * zoom
        val dx = (lw - zone.width()) * (0.5f - 0.5f * dansPhoto * (if (k % 2 == 0) 1f else -1f)) * 0.6f
        val dest = RectF(zone.centerX() - lw / 2 + (if (k % 2 == 0) -dx else dx) * 0.3f, zone.centerY() - lh / 2, zone.centerX() + lw / 2 + (if (k % 2 == 0) -dx else dx) * 0.3f, zone.centerY() + lh / 2)
        pForme.alpha = (alpha * 255 * fondu).toInt()
        c.drawBitmap(bm, null, dest, pForme)
        pForme.alpha = 255
        c.restore()
        // Petits points : où on en est.
        noms.indices.forEach { i ->
            pForme.style = Paint.Style.FILL; pForme.color = if (i == k) pourpre else encre3; pForme.alpha = (alpha * (if (i == k) 230 else 120)).toInt()
            c.drawCircle(cadre.centerX() + (i - (noms.size - 1) / 2f) * h * 0.025f, cadre.bottom - h * 0.03f, h * 0.006f, pForme)
        }
    }

    // --- Pluie dans l'heure ----------------------------------------------------------------

    private fun dessinerPluie(c: Canvas, w: Float, h: Float, alpha: Float, ecoule: Long, t: Float, surHorloge: Boolean) {
        val pl = donnees?.pluie ?: return
        // 17-10 (l'auteur, point 7) : « Pas de pluie » est inutile — rien n'est dessiné (ni texte ni barre) sans pluie prévue.
        if (!PluieVeille.aMontrer(pl.dans, pl.pluie, pl.points.map { it.second })) return
        val p0 = etape(ecoule, 900, 700)
        // Sur l'horloge, la barre reste dans l'anneau ; sur la météo, sous la vigilance.
        val largeurBarre = if (surHorloge) w * 0.16f else w * 0.26f
        val x0 = if (surHorloge) w / 2 - largeurBarre / 2 else w * 0.08f
        val y = if (surHorloge) h * 0.865f else h * 0.86f
        val texte = when {
            pl.dans != null && pl.dans <= 0 -> context.getString(R.string.lanceur_il_pleut)
            pl.dans != null -> context.getString(R.string.lanceur_pluie_dans, pl.dans)
            pl.pluie -> context.getString(R.string.lanceur_pluie_possible)
            else -> context.getString(R.string.lanceur_pas_de_pluie)
        }
        if (surHorloge) ecrire(c, texte, w / 2, y - h * 0.014f, h * 0.024f, if (pl.pluie) bleu else encre3, alpha * p0, if (pl.pluie) gras else normal, Paint.Align.CENTER)
        else ecrire(c, texte, x0, y - h * 0.014f, h * 0.024f, if (pl.pluie) bleu else encre3, alpha * p0, if (pl.pluie) gras else normal)
        // La barre : une heure, un segment par tranche, bleu plus ou moins dense selon l'intensité.
        val hb = h * 0.012f
        pForme.style = Paint.Style.FILL
        pForme.color = encre3; pForme.alpha = (alpha * p0 * 40).toInt()
        c.drawRoundRect(RectF(x0, y, x0 + largeurBarre, y + hb), hb / 2, hb / 2, pForme)
        val pts = pl.points.sortedBy { it.first }
        pts.forEachIndexed { i, (minute, niveau) ->
            if (niveau <= 0) return@forEachIndexed
            val suivant = pts.getOrNull(i + 1)?.first ?: 60
            val xa = x0 + largeurBarre * minute / 60f; val xb = x0 + largeurBarre * suivant / 60f
            pForme.color = bleu; pForme.alpha = (alpha * p0 * (90 + 55 * niveau)).toInt().coerceIn(0, 255)
            c.drawRoundRect(RectF(xa, y, xb, y + hb), hb / 2, hb / 2, pForme)
        }
        // Le curseur « maintenant », qui respire.
        pForme.color = if (pl.pluie) bleu else encre3; pForme.alpha = (alpha * p0 * (150 + 100 * sin(t * 2f))).toInt().coerceIn(0, 255)
        c.drawCircle(x0, y + hb / 2, hb * 0.9f, pForme)
        ecrire(c, "+1 h", x0 + largeurBarre, y + hb + h * 0.03f, h * 0.02f, encre3, alpha * p0, normal, Paint.Align.RIGHT)
    }

    // --- Demandes des enfants ---------------------------------------------------------------

    private fun dessinerDemandes(c: Canvas, w: Float, h: Float, t: Float) {
        val liste = donnees?.demandes ?: return
        if (liste.isEmpty()) return
        // Un bandeau discret en bas à droite ; la plus récente d'abord, les autres en compte.
        val d = liste[0]
        val texte = context.getString(R.string.lanceur_demande_quoi, d.prenom, d.libelle) + (if (liste.size > 1) "  +${liste.size - 1}" else "") + "  ·  " + ilYA(d.ts)
        val taille = h * 0.024f
        val l = largeur(texte, taille, normal) + h * 0.07f
        // 1.93.05 (retour 39) : à gauche du QR, du logo ou du bandeau posé en bas à droite (jamais dessus).
        val droite = CoinsVeille.Coins.bordDroit(OptionsVeille.BD, OptionsVeille.qr(context), OptionsVeille.logo(context), OptionsVeille.lecteur(context), w, h, w * 0.92f, CoinsVeille.coteQr(context, h), CoinsVeille.largeurLegende(context, h))
        val rect = RectF(droite - l, h * 0.905f, droite, h * 0.95f)  // 1.93.06 : dans la marge (avant : jusqu'à 97,5 %)
        pForme.style = Paint.Style.FILL; pForme.color = carte; pForme.alpha = (Color.alpha(carte) * 0.9f).toInt()
        c.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, pForme)
        pForme.color = pourpre; pForme.alpha = (150 + 100 * sin(t * 4f)).toInt().coerceIn(0, 255)
        c.drawCircle(rect.left + h * 0.022f, rect.centerY(), h * 0.007f, pForme)
        ecrire(c, texte, rect.left + h * 0.04f, rect.centerY() + taille * 0.35f, taille, encre2, 0.95f, normal)
    }

    // --- Maison --------------------------------------------------------------

    private fun dessinerMaison(c: Canvas, w: Float, h: Float, alpha: Float, ecoule: Long, t: Float) {
        val d = donnees ?: return
        titre(c, Config(context).lieu, w, h, alpha, ecoule)
        val personnes = d.maison.take(5)
        val colonnes = personnes.size.coerceAtLeast(1)
        val largeurCol = (w * 0.84f) / colonnes
        personnes.forEachIndexed { i, p ->
            val pi = etape(ecoule, 200 + 180L * i, 800)
            val x = w * 0.08f + largeurCol * i + largeurCol / 2
            val teinte = when {
                p.enLigne > 0 && p.verrouilles >= p.total -> rouge
                p.enLigne > 0 -> vert
                else -> encre3
            }
            val rb = rebond(pi)
            if (p.enLigne > 0) {
                pForme.color = teinte; pForme.alpha = (alpha * 50 * pi).toInt()
                c.drawCircle(x, h * 0.40f, h * (0.085f + 0.01f * sin(t * 2f + i)) * rb, pForme)
            }
            pForme.color = teinte; pForme.alpha = (alpha * 255 * pi).toInt()
            c.drawCircle(x, h * 0.40f, h * 0.07f * rb, pForme)
            ecrire(c, p.prenom.take(1).uppercase(Locale.getDefault()), x, h * 0.40f + h * 0.025f * rb, h * 0.075f * rb, Color.WHITE, alpha * pi, gras, Paint.Align.CENTER)
            ecrire(c, p.prenom, x, h * 0.56f, h * 0.045f, encre, alpha * pi, normal, Paint.Align.CENTER)
            val etat = when {
                p.total == 0 -> context.getString(R.string.veilleview_aucun_appareil)
                p.enLigne == 0 -> context.getString(R.string.veilleview_hors_ligne)
                p.verrouilles >= p.total -> context.getString(R.string.veilleview_ecrans_fermes)
                p.verrouilles > 0 -> context.getString(R.string.veilleview_en_ligne_fermes, p.enLigne, context.resources.getQuantityString(R.plurals.veilleview_fermes, p.verrouilles, p.verrouilles))
                else -> context.getString(R.string.veilleview_en_ligne, p.enLigne)
            }
            ecrire(c, etat, x, h * 0.61f, h * 0.028f, teinte, alpha * pi, normal, Paint.Align.CENTER)
            val pc = etape(ecoule, 400 + 180L * i, 1400)
            ecrire(c, duree((p.minutes * pc).toInt()), x, h * 0.73f, h * 0.085f, encre, alpha * pi, fin, Paint.Align.CENTER)
            ecrire(c, context.getString(R.string.veilleview_ecran_aujourdhui), x, h * 0.78f, h * 0.026f, encre3, alpha * pi, normal, Paint.Align.CENTER, 0.06f)
        }
    }

    /** « 45 min », « 2 h », « 1 h 05 » : les unités de la langue de l'appareil (Tr.duree). */
    private fun duree(min: Int): String = Tr.duree(min)

    // --- Courbe dehors / dedans ---------------------------------------------

    private fun dessinerCourbe(c: Canvas, w: Float, h: Float, alpha: Float, ecoule: Long, t: Float) {
        val d = donnees ?: return
        val series = d.series.take(2)
        if (series.size < 2) return
        titre(c, context.getString(R.string.veilleview_dehors_dedans, Tr.duree(d.courbeHeures * 60)), w, h, alpha, ecoule)
        // La carte occupe la même largeur que les autres écrans (8 % de chaque côté).
        tracerCourbes(c, RectF(w * 0.08f, h * 0.265f, w * 0.92f, h * 0.87f), h, alpha, ecoule, t, series, legendeY = h * 0.235f, legendeX = w * 0.08f)
    }

    /**
     * Les courbes de température dans une carte donnée : le tracé garde de la place à gauche pour les
     * degrés, à droite pour les étiquettes et « maintenant ». `compact` : polices plus petites, pour
     * une carte partagée (le tableau Chauffage) ; la légende se dessine à `legendeY` si donné.
     */
    private fun tracerCourbes(c: Canvas, carte: RectF, h: Float, alpha: Float, ecoule: Long, t: Float, series: List<Serie>, legendeY: Float? = null, legendeX: Float = carte.left, compact: Boolean = false) {
        val avancement = lisser(((ecoule - fonduMs) / traceMs.toFloat()).coerceIn(0f, 1f))
        val pGrille = etape(ecoule, 200, 800)
        val couleurs = intArrayOf(bleu, pourpre)
        val k = if (compact) 0.72f else 1f
        val gauche = carte.left + h * 0.09f * k; val droite = carte.right - h * 0.15f * k
        val haut = carte.top + carte.height() * (if (compact) 0.16f else 0.11f); val bas = carte.bottom - carte.height() * (if (compact) 0.2f else 0.13f)
        val a255 = (alpha * 255).toInt().coerceIn(0, 255)

        var vMin = Float.MAX_VALUE; var vMax = -Float.MAX_VALUE
        var tMin = Long.MAX_VALUE; var tMax = Long.MIN_VALUE
        series.forEach { s ->
            s.valeurs.forEach { v -> vMin = min(vMin, v); vMax = maxOf(vMax, v) }
            tMin = min(tMin, s.temps.first()); tMax = maxOf(tMax, s.temps.last())
        }
        if (tMax <= tMin) return
        var bMin = floor((vMin - 1f) / 2f) * 2f
        var bMax = kotlin.math.ceil((vMax + 1f) / 2f) * 2f
        if (bMax - bMin < 6f) { val m = (bMin + bMax) / 2f; bMin = m - 3f; bMax = m + 3f }
        fun px(tt: Long) = gauche + (droite - gauche) * ((tt - tMin).toFloat() / (tMax - tMin).toFloat())
        fun py(v: Float) = bas - (bas - haut) * ((v - bMin) / (bMax - bMin))

        cartePosee(c, carte, h, alpha * pGrille)
        pForme.style = Paint.Style.STROKE; pForme.strokeWidth = h * 0.0015f
        pForme.color = encre3; pForme.alpha = (a255 * 0.35f * pGrille).toInt()
        val pas = if (bMax - bMin > 16f || (compact && bMax - bMin > 8f)) 4f else 2f
        var v = bMin
        while (v <= bMax + 0.01f) {
            val y = py(v)
            c.drawLine(gauche, y, gauche + (droite - gauche) * pGrille, y, pForme)
            ecrire(c, "${v.toInt()}°", gauche - h * 0.015f, y + h * 0.011f * k, h * 0.026f * k, encre3, alpha * pGrille, normal, Paint.Align.RIGHT)
            v += pas
        }
        pForme.style = Paint.Style.FILL
        val cal = Calendar.getInstance()
        var tick = tMin - (tMin % 3600L) + 3600L
        while (tick <= tMax) {
            cal.timeInMillis = tick * 1000L
            if (cal.get(Calendar.HOUR_OF_DAY) % 6 == 0) {
                val x = px(tick)
                pForme.color = encre3; pForme.alpha = (a255 * 0.5f * pGrille).toInt()
                c.drawRect(x, bas, x + h * 0.002f, bas + h * 0.012f, pForme)
                ecrire(c, heureCourte.format(Date(tick * 1000L)), x, bas + h * 0.045f * k, h * 0.026f * k, encre3, alpha * pGrille, normal, Paint.Align.CENTER)
            }
            tick += 3600L
        }

        val tLimite = tMin + ((tMax - tMin) * avancement).toLong()
        series.forEachIndexed { i, s ->
            val couleur = couleurs[i % couleurs.size]
            chemin.reset()
            var dernierX = gauche; var dernierY = py(s.valeurs[0]); var derniereV = s.valeurs[0]
            var premier = true
            for (kk in s.temps.indices) {
                if (s.temps[kk] > tLimite) {
                    if (kk > 0) {
                        val t0 = s.temps[kk - 1]; val t1 = s.temps[kk]
                        val f = ((tLimite - t0).toFloat() / (t1 - t0).toFloat()).coerceIn(0f, 1f)
                        derniereV = s.valeurs[kk - 1] + (s.valeurs[kk] - s.valeurs[kk - 1]) * f
                        dernierX = px(tLimite); dernierY = py(derniereV)
                        chemin.lineTo(dernierX, dernierY)
                    }
                    break
                }
                val x = px(s.temps[kk]); val y = py(s.valeurs[kk])
                if (premier) { chemin.moveTo(x, y); premier = false } else chemin.lineTo(x, y)
                dernierX = x; dernierY = y; derniereV = s.valeurs[kk]
            }
            val zone = Path(chemin)
            zone.lineTo(dernierX, bas); zone.lineTo(gauche, bas); zone.close()
            pForme.style = Paint.Style.FILL; pForme.color = couleur; pForme.alpha = (a255 * 0.10f).toInt()
            c.drawPath(zone, pForme)
            pForme.style = Paint.Style.STROKE; pForme.strokeWidth = h * 0.006f * k
            pForme.strokeCap = Paint.Cap.ROUND; pForme.strokeJoin = Paint.Join.ROUND
            pForme.color = couleur; pForme.alpha = a255
            c.drawPath(chemin, pForme)
            pForme.style = Paint.Style.FILL
            val pulse = 1f + 0.25f * sin(t * 4f)
            pForme.alpha = (a255 * 0.25f).toInt(); c.drawCircle(dernierX, dernierY, h * 0.02f * k * pulse, pForme)
            pForme.alpha = a255; c.drawCircle(dernierX, dernierY, h * 0.009f * k, pForme)
            val etiquette = "${String.format(Locale.getDefault(), "%.1f", derniereV)}°"
            // La carte réserve la place à droite : l'étiquette reste à droite de la tête, jamais sur la courbe.
            ecrire(c, etiquette, dernierX + h * 0.03f * k, dernierY + h * 0.012f * k, h * 0.034f * k, couleur, alpha, gras, Paint.Align.LEFT)
            // Légende en ligne : sous le titre (plein écran) ou dans la carte (compact).
            val xLeg = (if (compact) carte.left + h * 0.03f else legendeX) + i * h * 0.22f * k
            val yLeg = legendeY ?: (carte.top + h * 0.045f)
            pForme.color = couleur; pForme.alpha = a255
            c.drawRoundRect(RectF(xLeg, yLeg - h * 0.012f * k, xLeg + h * 0.03f * k, yLeg - h * 0.004f * k), h * 0.004f, h * 0.004f, pForme)
            ecrire(c, s.nom, xLeg + h * 0.045f * k, yLeg, h * 0.03f * k, encre2, alpha, normal)
        }
        if (avancement >= 1f) {
            pForme.color = encre3; pForme.alpha = (a255 * 0.6f).toInt()
            c.drawRect(droite - h * 0.001f, haut, droite + h * 0.001f, bas, pForme)
            ecrire(c, "maintenant", droite, haut - h * 0.012f, h * 0.024f * k, encre3, alpha, normal, Paint.Align.RIGHT, 0.06f)
        }
    }

    // --- Caméras -------------------------------------------------------------

    /** Les cases des caméras, lissées d'une image à l'autre : la mise en page bouge sans sauter. */
    private val casesCameras = HashMap<String, RectF>()

    /**
     * Agence les vidéos selon leur format. Deux dispositions sont essayées : la grille 2x2 et les
     * colonnes (les caméras à l'italienne empilées, chaque caméra à la française sur toute la
     * hauteur) ; on garde celle qui montre le plus d'image.
     */
    private fun agencerCameras(cameras: List<Camera>, formats: List<Float>, zone: RectF, marge: Float, bandeau: Float, pad: Float): List<RectF> {
        val n = cameras.size
        fun grille(): List<RectF> {
            val cols = if (n <= 1) 1 else 2
            val lignes = (n + cols - 1) / cols
            val lc = (zone.width() - marge * (cols - 1)) / cols
            val hc = (zone.height() - marge * (lignes - 1)) / lignes
            return formats.mapIndexed { i, f ->
                val col = i % cols; val lig = i / cols
                val cx = zone.left + col * (lc + marge) + lc / 2; val cy = zone.top + lig * (hc + marge) + hc / 2
                val hv = min(hc - bandeau - 2 * pad, (lc - 2 * pad) / f); val lv = hv * f
                RectF(cx - lv / 2, cy - (hv + bandeau) / 2, cx + lv / 2, cy + (hv + bandeau) / 2 - bandeau)
            }
        }
        fun colonnes(): List<RectF>? {
            val larges = formats.indices.filter { formats[it] >= 1f }
            val hautes = formats.indices.filter { formats[it] < 1f }
            if (hautes.isEmpty() || larges.isEmpty()) return null
            // Les portraits se répartissent de part et d'autre de la colonne des paysages.
            val ordre = ArrayList<List<Int>>()
            val moitie = (hautes.size + 1) / 2
            hautes.take(moitie).forEach { ordre.add(listOf(it)) }
            ordre.add(larges)
            hautes.drop(moitie).forEach { ordre.add(listOf(it)) }
            fun largeurs(hz: Float): List<Float> = ordre.map { col ->
                val hCase = (hz - marge * (col.size - 1)) / col.size
                col.maxOf { (hCase - bandeau - 2 * pad) * formats[it] } + 2 * pad
            }
            var hz = zone.height()
            for (k in 0 until 24) {
                val total = largeurs(hz).sum() + marge * (ordre.size - 1)
                if (total <= zone.width()) break
                hz *= 0.97f
            }
            val ls = largeurs(hz)
            val total = ls.sum() + marge * (ordre.size - 1)
            val res = arrayOfNulls<RectF>(n)
            var x = zone.left + (zone.width() - total) / 2
            val y0 = zone.top + (zone.height() - hz) / 2
            ordre.forEachIndexed { ci, col ->
                val hCase = (hz - marge * (col.size - 1)) / col.size
                col.forEachIndexed { li, i ->
                    val hv = hCase - bandeau - 2 * pad; val lv = hv * formats[i]
                    val cx = x + ls[ci] / 2; val top = y0 + li * (hCase + marge) + pad
                    res[i] = RectF(cx - lv / 2, top, cx + lv / 2, top + hv)
                }
                x += ls[ci] + marge
            }
            return res.map { it!! }
        }
        val a = grille(); val b = colonnes() ?: return a
        fun aire(l: List<RectF>) = l.sumOf { (it.width() * it.height()).toDouble() }
        return if (aire(b) > aire(a) * 1.05) b else a
    }

    private fun dessinerCameras(c: Canvas, w: Float, h: Float, alpha: Float, ecoule: Long, t: Float, cameras: List<Camera>) {
        titre(c, context.getString(R.string.veilleview_dehors_direct), w, h, alpha, ecoule)
        val couche = this.cameras
        val zone = RectF(w * 0.08f, h * 0.23f, w * 0.92f, h * 0.88f)
        val marge = h * 0.02f
        val bandeau = h * 0.05f
        val pad = h * 0.008f
        // Le format de chaque caméra : la vidéo si elle tourne, sinon l'instantané, sinon 16/9 en attendant.
        val formats = cameras.map { cam ->
            couche?.format(cam.id) ?: images[cam.id]?.bitmap?.let { if (!it.isRecycled && it.height > 0) it.width.toFloat() / it.height else null } ?: (16f / 9f)
        }
        val cibles = agencerCameras(cameras, formats, zone, marge, bandeau, pad)
        val rects = ArrayList<Pair<String, RectF>>()
        cameras.forEachIndexed { i, cam ->
            val pi = etape(ecoule, 100 + 140L * i, 700)
            val cible = cibles[i]
            // Glissement doux vers la nouvelle case quand un format se précise.
            val video = casesCameras.getOrPut(cam.id) { RectF(cible) }
            val k = 0.16f
            video.left += (cible.left - video.left) * k; video.top += (cible.top - video.top) * k
            video.right += (cible.right - video.right) * k; video.bottom += (cible.bottom - video.bottom) * k
            val dy = (1f - pi) * h * 0.04f
            val rect = RectF(video.left - pad, video.top - pad + dy, video.right + pad, video.bottom + bandeau + dy)
            val videoDessin = RectF(video.left, video.top + dy, video.right, video.bottom + dy)
            val a = alpha * pi
            cartePosee(c, rect, h, a)
            val direct = couche?.enDirect(cam.id) == true
            c.save()
            chemin.reset(); chemin.addRoundRect(videoDessin, h * 0.022f, h * 0.022f, Path.Direction.CW)
            c.clipPath(chemin)
            pForme.style = Paint.Style.FILL; pForme.color = 0xFF1A1416.toInt(); pForme.alpha = (a * 255).toInt()
            c.drawRect(videoDessin, pForme)
            val image = images[cam.id]
            if (!direct && image != null && !image.bitmap.isRecycled) {
                val bm = image.bitmap
                val ratio = min(videoDessin.width() / bm.width, videoDessin.height() / bm.height)
                val lw = bm.width * ratio; val lh = bm.height * ratio
                val dest = RectF(videoDessin.centerX() - lw / 2, videoDessin.centerY() - lh / 2, videoDessin.centerX() + lw / 2, videoDessin.centerY() + lh / 2)
                val apparition = lisser((SystemClock.uptimeMillis() - image.recue) / 600f)
                pForme.alpha = (a * 255 * (0.3f + 0.7f * apparition)).toInt()
                c.drawBitmap(bm, null, dest, pForme)
                pForme.alpha = 255
            } else if (!direct) {
                for (k2 in 0 until 3) {
                    val b = 0.4f + 0.6f * (0.5f + 0.5f * sin(t * 3f - k2 * 0.8f))
                    pForme.color = or; pForme.alpha = (a * 255 * b).toInt()
                    c.drawCircle(videoDessin.centerX() + (k2 - 1) * h * 0.03f, videoDessin.centerY(), h * 0.008f, pForme)
                }
            }
            c.restore()
            // Le nom dans le bandeau, et le point « direct » : rouge et battant quand la vidéo tourne, or en instantané.
            val etroit = rect.width() < h * 0.42f
            ecrire(c, cam.nom, rect.left + h * 0.02f, rect.bottom - bandeau * 0.34f, if (etroit) h * 0.026f else h * 0.03f, encre, a, gras)
            val vivant = direct || (image != null && SystemClock.uptimeMillis() - image.recue < 15_000)
            pForme.style = Paint.Style.FILL
            pForme.color = if (direct) rouge else if (vivant) or else encre3
            pForme.alpha = (a * 255 * (if (vivant) 0.55f + 0.45f * sin(t * 4f) else 0.6f)).toInt()
            c.drawCircle(rect.right - h * 0.025f, rect.bottom - bandeau * 0.5f, h * 0.009f, pForme)
            if (!etroit) ecrire(c, if (direct) context.getString(R.string.veilleview_direct) else context.getString(R.string.veilleview_instantane), rect.right - h * 0.045f, rect.bottom - bandeau * 0.34f, h * 0.024f, encre3, a, normal, Paint.Align.RIGHT, 0.06f)
            pForme.alpha = 255
            // La vidéo, dans l'espace écran (le dessin dérive de quelques pixels, la couche suit).
            rects.add(cam.id to RectF(videoDessin.left + derive[0], videoDessin.top + derive[1], videoDessin.right + derive[0], videoDessin.bottom + derive[1]))
        }
        couche?.montrer(rects, alpha, h * 0.022f)
    }

    // --- eSport --------------------------------------------------------------

    /** Les résultats jamais montrés sur cette télé, figés à l'entrée du tableau, puis notés comme vus. */
    private fun nouveauxDe(ids: List<String>): Set<String> {
        if (nouveauxPour == changeA) return nouveaux
        nouveauxPour = changeA
        val vus = prefs.getStringSet("esport_vus", null)
        nouveaux = if (vus == null) emptySet() else ids.filter { it !in vus }.toSet()
        val garde = LinkedHashSet<String>(vus ?: emptySet()).also { it.addAll(ids) }
        while (garde.size > 300) garde.remove(garde.first())
        prefs.edit().putStringSet("esport_vus", garde).apply()
        return nouveaux
    }

    private fun ilYA(ts: Long): String {
        val ecart = System.currentTimeMillis() / 1000 - ts
        return when {
            ts <= 0 -> ""
            ecart < 3600 -> context.getString(R.string.veilleview_a_linstant)
            ecart < 86400 -> context.getString(R.string.veilleview_il_y_a_h, ecart / 3600)
            ecart < 172800 -> context.getString(R.string.veilleview_hier)
            else -> context.getString(R.string.veilleview_il_y_a_j, ecart / 86400)
        }
    }

    internal fun tronquer(s: String, taille: Float, police: Typeface, max: Float): String {
        if (largeur(s, taille, police) <= max) return s
        var t = s
        while (t.length > 1 && largeur(t + "…", taille, police) > max) t = t.dropLast(1)
        return t.trimEnd() + "…"
    }

    private fun initiales(nom: String): String =
        nom.split(" ").filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1).uppercase(Locale.getDefault()) }

    /** Un logo d'équipe dans un cercle ; les initiales en attendant l'image. */
    private fun dessinerLogo(c: Canvas, url: String, nom: String, cx: Float, cy: Float, r: Float, alpha: Float, echelle: Float = 1f) {
        val bm = logos.obtenir(url)
        val rr = r * echelle
        pForme.style = Paint.Style.FILL
        pForme.color = Color.WHITE; pForme.alpha = (alpha * 230).toInt()
        c.drawCircle(cx, cy, rr, pForme)
        pForme.style = Paint.Style.STROKE; pForme.strokeWidth = rr * 0.06f
        pForme.color = carteBord; pForme.alpha = (alpha * Color.alpha(carteBord)).toInt()
        c.drawCircle(cx, cy, rr, pForme)
        pForme.style = Paint.Style.FILL
        if (bm != null && !bm.isRecycled) {
            val cote = rr * 1.5f
            val ratio = min(cote / bm.width, cote / bm.height)
            val lw = bm.width * ratio; val lh = bm.height * ratio
            pForme.alpha = (alpha * 255).toInt()
            c.drawBitmap(bm, null, RectF(cx - lw / 2, cy - lh / 2, cx + lw / 2, cy + lh / 2), pForme)
            pForme.alpha = 255
        } else {
            ecrire(c, initiales(nom), cx, cy + rr * 0.32f, rr * 0.9f, encre2, alpha, gras, Paint.Align.CENTER)
        }
    }

    /** Une pluie d'étincelles or et pourpre, déterministe (pas d'état) : `tt` secondes après l'éclat. */
    private fun etincelles(c: Canvas, x: Float, y: Float, h: Float, tt: Float, graine: Int, alpha: Float) {
        if (tt < 0f || tt > 2.2f) return
        val g = java.util.Random(graine.toLong())
        for (k in 0 until 16) {
            val angle = g.nextFloat() * 6.2832f
            val vitesse = (0.12f + g.nextFloat() * 0.22f) * h
            val taille = (0.003f + g.nextFloat() * 0.005f) * h
            val couleur = if (g.nextBoolean()) or else pourpre
            val px = x + cos(angle) * vitesse * tt
            val py = y + sin(angle) * vitesse * tt * 0.6f + 0.25f * h * tt * tt
            val vie = (1f - tt / 2.2f)
            pForme.style = Paint.Style.FILL
            pForme.color = couleur; pForme.alpha = (alpha * 255 * vie * vie).toInt().coerceIn(0, 255)
            c.drawCircle(px, py, taille * (0.5f + vie), pForme)
        }
        pForme.alpha = 255
    }

    private fun dessinerEsports(c: Canvas, w: Float, h: Float, alpha: Float, ecoule: Long, t: Float, jeu: Jeu) {
        titre(c, context.getString(R.string.veilleview_esport, jeu.nom), w, h, alpha, ecoule)
        val frais = nouveauxDe(jeu.resultats.map { it.id })
        val resultats = jeu.resultats.take(8)
        val n = resultats.size
        val gauche = w * 0.08f; val droite = w * 0.92f
        val hautZone = h * 0.24f; val basZone = h * 0.89f
        val marge = h * 0.014f
        val hc = min(h * 0.078f, (basZone - hautZone - marge * (n - 1)) / n)
        val haut = hautZone
        val tailleNom = hc * 0.36f; val tailleScore = hc * 0.5f; val tailleInfo = hc * 0.22f
        val rayon = hc * 0.34f
        val centre = (gauche + droite) / 2
        resultats.forEachIndexed { i, r ->
            val nouveau = r.id in frais
            val debut = 120L + 90L * i
            val pi = if (nouveau) rebond(((ecoule - debut) / 900f).coerceIn(0f, 1f)) else etape(ecoule, debut, 650)
            val y = haut + i * (hc + marge)
            val decal = (1f - pi) * w * (if (nouveau) 0.25f else 0.06f)
            val rect = RectF(gauche + decal, y, droite + decal, y + hc)
            val a = alpha * min(1f, pi * 1.4f)
            // Les nouveaux : un liseré d'or qui respire, tout le temps du tableau.
            val bord = if (nouveau) Color.argb((160 + 95 * sin(t * 5f + i)).toInt().coerceIn(0, 255), Color.red(or), Color.green(or), Color.blue(or)) else carteBord
            cartePosee(c, rect, h, a, bord, if (nouveau) 2.2f else 1f)
            val cy = rect.centerY()
            val gagne1 = r.v == 1; val gagne2 = r.v == 2
            // Logos : celui du vainqueur un peu plus grand, et il salue à l'arrivée.
            val salut1 = if (gagne1 && pi >= 1f) 1f + 0.06f * sin(t * 3f) * (if (nouveau) 1f else 0.3f) else 1f
            val salut2 = if (gagne2 && pi >= 1f) 1f + 0.06f * sin(t * 3f) * (if (nouveau) 1f else 0.3f) else 1f
            dessinerLogo(c, r.l1, r.e1, rect.left + hc * 0.62f, cy, rayon, a, (if (gagne1) 1.08f else 0.92f) * salut1)
            dessinerLogo(c, r.l2, r.e2, rect.right - hc * 0.62f, cy, rayon, a, (if (gagne2) 1.08f else 0.92f) * salut2)
            // Noms, vers le centre ; le vainqueur en encre pleine.
            val maxNom = centre - h * 0.1f - (rect.left + hc * 1.15f)
            ecrire(c, tronquer(r.e1, tailleNom, if (gagne1) gras else normal, maxNom), rect.left + hc * 1.15f, cy + tailleNom * 0.36f, tailleNom, if (gagne1) encre else encre2, a, if (gagne1) gras else normal)
            ecrire(c, tronquer(r.e2, tailleNom, if (gagne2) gras else normal, maxNom), rect.right - hc * 1.15f, cy + tailleNom * 0.36f, tailleNom, if (gagne2) encre else encre2, a, if (gagne2) gras else normal, Paint.Align.RIGHT)
            // Le score, qui compte à l'arrivée ; l'événement dessous, en petit.
            val pScore = etape(ecoule, debut + 300, if (nouveau) 1400 else 700)
            val s1 = r.s1?.let { compter(it.toString(), pScore) } ?: "–"
            val s2 = r.s2?.let { compter(it.toString(), pScore) } ?: "–"
            val yScore = cy - hc * 0.02f
            val lDeux = largeur(" : ", tailleScore, fin)
            ecrire(c, s1, centre - lDeux / 2, yScore + tailleScore * 0.3f, tailleScore, if (gagne1) pourpre else encre3, a, gras, Paint.Align.RIGHT)
            ecrire(c, " : ", centre, yScore + tailleScore * 0.3f, tailleScore, encre3, a * 0.7f, fin, Paint.Align.CENTER)
            ecrire(c, s2, centre + lDeux / 2, yScore + tailleScore * 0.3f, tailleScore, if (gagne2) pourpre else encre3, a, gras)
            val info = listOf(r.evenement, r.serie, ilYA(r.ts)).filter { it.isNotEmpty() }.joinToString("  ·  ")
            ecrire(c, tronquer(info, tailleInfo, normal, (centre - h * 0.1f - (rect.left + hc * 1.15f)) * 1.1f), centre, rect.bottom - hc * 0.14f, tailleInfo, encre3, a, normal, Paint.Align.CENTER)
            if (nouveau) {
                // Étiquette « nouveau », balayage de lumière, étincelles sur le score du vainqueur.
                val pe = etape(ecoule, debut + 500, 400)
                val lPill = largeur(context.getString(R.string.veilleview_nouveau_maj), hc * 0.2f, gras) * 1.08f + hc * 0.3f
                val pill = RectF(rect.left + hc * 0.2f, rect.top - hc * 0.16f, rect.left + hc * 0.2f + lPill * pe, rect.top + hc * 0.16f)
                pForme.style = Paint.Style.FILL; pForme.color = pourpre; pForme.alpha = (a * 255).toInt()
                c.drawRoundRect(pill, hc * 0.16f, hc * 0.16f, pForme)
                if (pe > 0.9f) ecrire(c, context.getString(R.string.veilleview_nouveau_maj), pill.centerX(), pill.centerY() + hc * 0.07f, hc * 0.2f, Color.WHITE, a, gras, Paint.Align.CENTER, 0.08f)
                val balayage = ((ecoule - debut - 600) / 1100f)
                if (balayage in 0f..1f) {
                    c.save()
                    chemin.reset(); chemin.addRoundRect(rect, h * 0.03f, h * 0.03f, Path.Direction.CW); c.clipPath(chemin)
                    val bx = rect.left + (rect.width() + hc * 3) * balayage - hc * 1.5f
                    pFond.shader = LinearGradient(bx - hc, 0f, bx + hc, 0f, intArrayOf(0x00FFFFFF, 0x99FFFFFF.toInt(), 0x00FFFFFF), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
                    c.drawRect(bx - hc * 1.5f, rect.top, bx + hc * 1.5f, rect.bottom, pFond)
                    pFond.shader = null
                    c.restore()
                }
                val xGagnant = if (gagne2) centre + lDeux / 2 + tailleScore * 0.3f else centre - lDeux / 2 - tailleScore * 0.3f
                etincelles(c, xGagnant, yScore, h, (ecoule - debut - 1500) / 1000f, r.id.hashCode(), a)
            }
        }
        if (n == 0) ecrire(c, context.getString(R.string.veilleview_pas_resultat), gauche, h * 0.4f, h * 0.034f, encre2, alpha, normal)
    }

    // --- Sport auto -----------------------------------------------------------

    private fun dessinerCourses(c: Canvas, w: Float, h: Float, alpha: Float, ecoule: Long, t: Float, sport: Sport) {
        titre(c, context.getString(R.string.veilleview_sport_auto, sport.nom), w, h, alpha, ecoule)
        val epreuves = sport.epreuves.take(4)
        val frais = nouveauxDe(epreuves.map { it.id })
        val n = epreuves.size
        val gauche = w * 0.08f; val droite = w * 0.92f
        val hautZone = h * 0.24f; val basZone = h * 0.89f
        val marge = h * 0.02f
        // La dernière épreuve prend la moitié gauche, en grand ; les précédentes s'empilent à droite.
        val lGrand = if (n > 1) (droite - gauche) * 0.5f else droite - gauche
        val lPetit = (droite - gauche) - lGrand - marge
        val hPetit = if (n > 1) (basZone - hautZone - marge * (n - 2)) / (n - 1) else 0f
        epreuves.forEachIndexed { i, e ->
            val nouveau = e.id in frais
            val debut = 120L + 140L * i
            val pi = if (nouveau) rebond(((ecoule - debut) / 900f).coerceIn(0f, 1f)) else etape(ecoule, debut, 650)
            val grand = i == 0
            val base = if (grand) RectF(gauche, hautZone, gauche + lGrand, basZone)
                       else RectF(droite - lPetit, hautZone + (i - 1) * (hPetit + marge), droite, hautZone + (i - 1) * (hPetit + marge) + hPetit)
            val decal = (1f - pi) * h * (if (nouveau) 0.12f else 0.04f)
            val rect = RectF(base.left, base.top + decal, base.right, base.bottom + decal)
            val a = alpha * min(1f, pi * 1.4f)
            val bord = if (nouveau) Color.argb((160 + 95 * sin(t * 5f + i)).toInt().coerceIn(0, 255), Color.red(or), Color.green(or), Color.blue(or)) else carteBord
            cartePosee(c, rect, h, a, bord, if (nouveau) 2.2f else 1f)
            val pad = h * 0.025f
            val tailleTitre = if (grand) h * 0.04f else h * 0.027f
            val x = rect.left + pad
            var y = rect.top + pad + tailleTitre * 0.9f
            ecrire(c, tronquer(e.nom, tailleTitre, gras, rect.width() - 2 * pad), x, y, tailleTitre, encre, a, gras)
            val sous = listOf(if (e.manche > 0) context.getString(R.string.veilleview_manche, e.manche) else "", e.lieu, ilYA(e.ts)).filter { it.isNotEmpty() }.joinToString("  ·  ")
            y += tailleTitre * 0.85f
            ecrire(c, sous, x, y, tailleTitre * 0.55f, encre3, a, normal)
            // Le podium : P1 en grand, puis P2 et P3 ; dans les petites cartes, le vainqueur seulement.
            val places = if (grand) e.podium.take(3) else e.podium.take(1)
            val hLigne = if (grand) (rect.bottom - pad - y - h * 0.02f) / 3f else rect.bottom - pad - y
            places.forEachIndexed { k, pl ->
                val pk = etape(ecoule, debut + 250 + 180L * k, 600)
                val hl = if (grand && k == 0) hLigne * 1.25f else if (grand) hLigne * 0.875f else hLigne
                val yl = (if (grand && k > 0) y + h * 0.02f + hLigne * 1.25f + (k - 1) * hLigne * 0.875f else y + h * 0.02f) + (1f - pk) * h * 0.02f
                val cy = yl + hl / 2
                val rayon = min(hl * 0.36f, h * 0.045f) * (if (k == 0) 1.1f else 0.85f)
                val tailleNom = (if (k == 0) rayon * 0.95f else rayon * 0.85f)
                val ak = a * pk
                // Médaille : or, argent, bronze.
                val medaille = when (pl.pos) { 1 -> or; 2 -> 0xFF9EA3AD.toInt(); else -> 0xFFB2743C.toInt() }
                pForme.style = Paint.Style.FILL; pForme.color = medaille; pForme.alpha = (ak * 255).toInt()
                c.drawCircle(x + rayon * 0.45f, cy, rayon * 0.42f, pForme)
                ecrire(c, "${pl.pos}", x + rayon * 0.45f, cy + rayon * 0.15f, rayon * 0.5f, Color.WHITE, ak, gras, Paint.Align.CENTER)
                val salut = if (pl.pos == 1 && pk >= 1f && nouveau) 1f + 0.06f * sin(t * 3f) else 1f
                dessinerLogo(c, pl.logo, pl.equipe, x + rayon * 1.1f + rayon, cy, rayon, ak, salut)
                val xTexte = x + rayon * 1.1f + rayon * 2.3f
                val maxTexte = rect.right - pad - xTexte
                ecrire(c, tronquer(pl.pilote, tailleNom, gras, maxTexte), xTexte, cy - tailleNom * 0.05f, tailleNom, if (pl.pos == 1) encre else encre2, ak, gras)
                ecrire(c, tronquer(pl.equipe, tailleNom * 0.7f, normal, maxTexte), xTexte, cy + tailleNom * 0.75f, tailleNom * 0.7f, encre3, ak, normal)
                if (nouveau && pl.pos == 1) etincelles(c, x + rayon * 1.1f + rayon, cy, h, (ecoule - debut - 1500) / 1000f, e.id.hashCode(), a)
            }
            if (nouveau) {
                val pe = etape(ecoule, debut + 500, 400)
                val hp = h * 0.03f
                val lPill = largeur(context.getString(R.string.veilleview_nouveau_maj), hp * 0.6f, gras) * 1.08f + hp
                val pill = RectF(rect.right - pad - lPill, rect.top - hp * 0.5f, rect.right - pad - lPill + lPill * pe, rect.top + hp * 0.5f)
                pForme.style = Paint.Style.FILL; pForme.color = pourpre; pForme.alpha = (a * 255).toInt()
                c.drawRoundRect(pill, hp * 0.5f, hp * 0.5f, pForme)
                if (pe > 0.9f) ecrire(c, context.getString(R.string.veilleview_nouveau_maj), pill.centerX(), pill.centerY() + hp * 0.22f, hp * 0.6f, Color.WHITE, a, gras, Paint.Align.CENTER, 0.08f)
                val balayage = ((ecoule - debut - 600) / 1100f)
                if (balayage in 0f..1f) {
                    c.save()
                    chemin.reset(); chemin.addRoundRect(rect, h * 0.03f, h * 0.03f, Path.Direction.CW); c.clipPath(chemin)
                    val bx = rect.left + (rect.width() + h * 0.3f) * balayage - h * 0.15f
                    pFond.shader = LinearGradient(bx - h * 0.1f, 0f, bx + h * 0.1f, 0f, intArrayOf(0x00FFFFFF, 0x99FFFFFF.toInt(), 0x00FFFFFF), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
                    c.drawRect(bx - h * 0.15f, rect.top, bx + h * 0.15f, rect.bottom, pFond)
                    pFond.shader = null
                    c.restore()
                }
            }
        }
    }

    // --- Tuiles --------------------------------------------------------------

    private fun dessinerTuiles(c: Canvas, w: Float, h: Float, alpha: Float, ecoule: Long, t: Float, tuiles: List<Tuile>) {
        titre(c, tuiles.firstOrNull()?.theme?.ifEmpty { null } ?: Vocabulaire.lieu(context), w, h, alpha, ecoule)
        val n = tuiles.size
        val cols = if (n <= 2) n else 2
        val lignes = if (n <= 2) 1 else 2
        val gauche = w * 0.08f; val droite = w * 0.92f
        val haut = h * 0.25f; val bas = h * 0.84f
        val marge = h * 0.025f
        val lc = (droite - gauche - marge * (cols - 1)) / cols
        val hc = (bas - haut - marge * (lignes - 1)) / lignes
        tuiles.forEachIndexed { i, tu ->
            val pi = etape(ecoule, 150 + 160L * i, 800)
            val col = i % cols; val lig = i / cols
            val x = gauche + col * (lc + marge); val y = haut + lig * (hc + marge) + (1f - pi) * h * 0.05f
            val rect = RectF(x, y, x + lc, y + hc)
            val lueur = 0.6f + 0.4f * sin(t * 2.5f + i)
            val bord = if (tu.alerte) Color.argb((255 * lueur).toInt(), Color.red(or), Color.green(or), Color.blue(or)) else carteBord
            cartePosee(c, rect, h, alpha * pi, bord, if (tu.alerte) 2f else 1f)
            val pad = h * 0.035f
            ecrire(c, tu.titre.uppercase(Locale.getDefault()), x + pad, y + pad + h * 0.028f, h * 0.026f, if (tu.alerte) or else encre3, alpha * pi, gras, espacement = 0.12f)
            val tailleVal = if (lignes == 1) h * 0.17f else h * 0.11f
            val valeur = compter(tu.valeur, etape(ecoule, 300 + 160L * i, 1300))
            val lv = largeur(valeur, tailleVal, fin)
            ecrire(c, valeur, x + pad, y + hc * 0.62f, tailleVal, encre, alpha * pi, fin)
            if (tu.unite.isNotEmpty()) ecrire(c, tu.unite, x + pad + lv + h * 0.012f, y + hc * 0.62f, tailleVal * 0.4f, encre2, alpha * pi, normal)
            if (tu.detail.isNotEmpty()) ecrire(c, tu.detail, x + pad, y + hc - pad, h * 0.028f, encre2, alpha * pi, normal)
            if (tu.alerte) {
                pForme.style = Paint.Style.FILL; pForme.color = rouge; pForme.alpha = (alpha * 255 * pi * lueur).toInt()
                c.drawCircle(x + lc - pad, y + pad + h * 0.012f, h * 0.012f, pForme)
            }
        }
    }
}

private fun JSONObject.optDoubleOrNull(cle: String): Double? = if (has(cle) && !isNull(cle)) optDouble(cle) else null

/** 17-10 (l'auteur, point 7) : la ligne de pluie de la veille n'annonce que de la pluie ; jamais « pas de pluie ». */
object PluieVeille {
    /** Vrai s'il pleut, s'il va pleuvoir dans l'heure, ou si une tranche de la prochaine heure a de la pluie. */
    fun aMontrer(dans: Int?, pluie: Boolean, niveaux: List<Int>): Boolean = pluie || dans != null || niveaux.any { it > 0 }
}
