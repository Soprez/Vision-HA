// SPDX-License-Identifier: MIT
package org.soprez.vision

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.test.InstrumentationTestRunner
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.ServerSocket
import java.net.Socket

/**
 * Recette visuelle (règle permanente de l'auteur, 2026-10-11) : rien de rogné, tronqué ou collé aux bords, sur chaque écran
 * clé de l'appli, à la taille d'écran et à la police du moment.
 *
 * Pour chaque vue visible de chaque fenêtre de l'appli (l'écran et ses boîtes de dialogue), un DÉFAUT si :
 *  - un TextView est ellipsé (Layout.getEllipsisCount > 0) — sauf étiquette d'icône d'appli admise (2 lignes, nom
 *    complet en contentDescription, règle de EcransGrandsCaracteresTest) ;
 *  - des lignes sont coupées : lignes nécessaires > lignes affichées (maxLines), ou bas de la dernière ligne affichée
 *    plus bas que la hauteur utile (hauteur − marges intérieures) ; un mot coupé en deux en fin de ligne ;
 *  - la vue sort de son parent (sauf dans un parent qui défile dans ce sens) ou de l'écran utile (cadre visible de la
 *    fenêtre, barres système exclues ; sauf dans un bloc qui défile) ;
 *  - un texte (l'étendue réelle de ses lignes) ou une vue interactive (cliquable, focalisable, champ) est à moins de
 *    8 dp d'un bord de l'écran utile (bords haut et bas : seulement hors d'un bloc qui défile verticalement).
 * Les longues pages défilent (jusqu'à 6 écrans) et sont revérifiées à chaque cran (listes recyclées comprises).
 *
 * Paramètres (arguments d'instrumentation, -e) :
 *  - taille : nom du cas dans les fichiers (ex. « 360dp », « tablette-paysage », « tv-1080p ») ; défaut : la taille
 *    lue (« 411x914dp ») — la taille et la densité sont posées AVANT par le lanceur (wm size / wm density) ;
 *  - police : « 1.0 », « 1.3 » ou « 1.0,1.3 » (défaut) ; le test pose lui-même settings system font_scale ;
 *  - langue : ex. « de » (cmd locale set-app-locales, Android 13+) ; défaut : celle du système ;
 *  - ecrans : filtre, ex. « accueil,blocage ».
 * Une capture par cas (et par cran de défilement) : /sdcard/Android/data/org.soprez.vision/files/recette/
 * recette-lot1-android-<écran>-<taille>-<police>.png (réduite à 1280 px au plus grand côté, pour le disque), et le
 * rapport recette-lot1-android-rapport-<taille>-<police>.txt. Le lanceur les rapatrie dans etude-ux/captures.
 *
 * Données d'essai longues ; un petit serveur local (127.0.0.1) joue Home Assistant (Party, messages, relevé) et refuse
 * toute inscription : rien ne part sur le réseau (mode avion conseillé, posé par le lanceur).
 *
 *   outils/recette-visuelle.sh -s emulator-5554            (toute la matrice)
 *   adb shell am instrument -w -e class org.soprez.vision.AntiDebordementTest -e taille 360dp -e police 1.3 \
 *       org.soprez.vision.test/android.test.InstrumentationTestRunner
 *
 */
class AntiDebordementTest : InstrumentationTestCase() {
    companion object {
        /** HA 2.5.1 : une catégorie détaillée aux noms longs (logiciel déplié, services, sites liés et en attente). */
        const val DETAIL_CATEGORIE = """{"ok":true,"etiquette":"Réseaux sociaux","fermee":true,"autres":197,
            "logiciels":[
              {"genre":"apps","nom":"com.discord.application.extraordinaire","libelle":"Discord : discussions vocales et communautés de joueurs","ouvert":true,"mode":"temps","fin":1893456000,
               "services":[{"nom":"com.discord.service.notifications.arriere.plan","suit":true,"ouvert":true},{"nom":"discordupdater-automatique","suit":true,"ouvert":true}],
               "sites":[{"genre":"sites","nom":"discord.com","ouvert":true,"suit":true,"par_logiciel":true},
                        {"genre":"sites","nom":"discordcdn-medias-utilisateurs.com","ouvert":true,"mode":"exception","auto":true},
                        {"genre":"sites","nom":"discord-activites-integrees-tierces.gg","ouvert":false,"ferme_par":"categorie","auto":true,"attente":true,"raison":"Fermé par la catégorie Réseaux sociaux, sur tous ses appareils."}]},
              {"genre":"apps","nom":"com.instagram.android","libelle":"Instagram","ouvert":false,"ferme_par":"plage","raison":"Fermé par la plage « Devoirs et temps d'étude du soir » jusqu'à 20:00.","services":[],"sites":[]}],
            "sites":[{"genre":"sites","nom":"reseau-social-des-collegiens-et-lyceens-europeens.eu","ouvert":false,"ferme_par":"nomme","mode":"bloquer"},
                     {"genre":"sites","nom":"mastodon.social","ouvert":false,"ferme_par":"categorie","logiciel":"mastodon"},
                     {"genre":"sites","nom":"exemple-adulte.org","ouvert":false,"ferme_par":"securite"},
                     {"genre":"sites","nom":"pixelfed.social","ouvert":true,"mode":"toujours"}]}"""
        const val RESULTATS_RECHERCHE = """[
            {"genre":"categorie","nom":"Réseaux sociaux","fermee":true},
            {"genre":"apps","nom":"com.discord.application.extraordinaire","libelle":"Discord : discussions vocales et communautés de joueurs","ouvert":false,"ferme_par":"categorie","service":"discordupdater-automatique","categories":["Réseaux sociaux","Jeux"],"raison":"Fermé par la catégorie Réseaux sociaux."},
            {"genre":"sites","nom":"discordcdn-medias-utilisateurs.com","ouvert":false,"ferme_par":"temps","logiciel":"discord","categories":["Réseaux sociaux"],"raison":"Le temps du jour est épuisé (2 h 30)."}]"""
        const val RECENTS = """[
            {"nom":"roblox-contenus-telechargeables-de-la-communaute.com","quand":1760450000,"fois":12,"appareil":"w1","appareil_nom":"PC de la chambre de Maximilian-Alexander","logiciel":"com.roblox.client","ouvert":false,"ferme_par":"categorie","raison":"Fermé par la catégorie Jeux.","demandable":true},
            {"nom":"rbxcdn.com","quand":1760449000,"fois":1,"appareil":"t1","appareil_nom":"Téléphone","ouvert":false,"ferme_par":"plage","demandable":true}]"""

        /** 1.88.0 : des entités Home Assistant d'essai (noms longs, détail de 25 éléments, réglage, liste, confirmation). */
        val CATALOGUE_WIDGETS = """{"version": 1, "entites": [
          {"entity_id": "sensor.liste_longue", "nom": "Liste longue d'exemple (attribut liste, toutes les lignes)", "domaine": "sensor",
           "etat": "27", "attributs_utiles": {}, "actions": [], "styles": ["tuile"], "role": "lecture", "affichage": "detail",
           "detail": [
                      {"texte": "Élément 1 d'une longue liste, avec une précision qui peut être longue", "valeur": ""},
                      {"texte": "Élément 2 d'une longue liste, avec une précision qui peut être longue", "valeur": ""},
                      {"texte": "Élément 3 d'une longue liste, avec une précision qui peut être longue", "valeur": ""},
                      {"texte": "Élément 4 d'une longue liste, avec une précision qui peut être longue", "valeur": ""},
                      {"texte": "Élément 5 d'une longue liste, avec une précision qui peut être longue", "valeur": ""},
                      {"texte": "Élément 6 d'une longue liste, avec une précision qui peut être longue", "valeur": ""},
                      {"texte": "Élément 7 d'une longue liste, avec une précision qui peut être longue", "valeur": ""},
                      {"texte": "Élément 8 d'une longue liste, avec une précision qui peut être longue", "valeur": ""},
                      {"texte": "Élément 9 d'une longue liste, avec une précision qui peut être longue", "valeur": ""},
                      {"texte": "Élément 10 d'une longue liste, avec une précision qui peut être longue", "valeur": ""},
                      {"texte": "Élément 11 d'une longue liste, avec une précision qui peut être longue", "valeur": ""},
                      {"texte": "Élément 12 d'une longue liste, avec une précision qui peut être longue", "valeur": ""},
                      {"texte": "Élément 13 d'une longue liste, avec une précision qui peut être longue", "valeur": ""},
                      {"texte": "Élément 14 d'une longue liste, avec une précision qui peut être longue", "valeur": ""},
                      {"texte": "Élément 15 d'une longue liste, avec une précision qui peut être longue", "valeur": ""},
                      {"texte": "Élément 16 d'une longue liste, avec une précision qui peut être longue", "valeur": ""},
                      {"texte": "Élément 17 d'une longue liste, avec une précision qui peut être longue", "valeur": ""},
                      {"texte": "Élément 18 d'une longue liste, avec une précision qui peut être longue", "valeur": ""},
                      {"texte": "Élément 19 d'une longue liste, avec une précision qui peut être longue", "valeur": ""},
                      {"texte": "Élément 20 d'une longue liste, avec une précision qui peut être longue", "valeur": ""},
                      {"texte": "Élément 21 d'une longue liste, avec une précision qui peut être longue", "valeur": ""},
                      {"texte": "Élément 22 d'une longue liste, avec une précision qui peut être longue", "valeur": ""},
                      {"texte": "Élément 23 d'une longue liste, avec une précision qui peut être longue", "valeur": ""},
                      {"texte": "Élément 24 d'une longue liste, avec une précision qui peut être longue", "valeur": ""},
                      {"texte": "Élément 25 d'une longue liste, avec une précision qui peut être longue", "valeur": ""},
                      {"texte": "Élément 26 d'une longue liste, avec une précision qui peut être longue", "valeur": ""},
                      {"texte": "Élément 27 d'une longue liste, avec une précision qui peut être longue", "valeur": ""}], "detail_total": 27},
          {"entity_id": "todo.devoirs", "nom": "Devoirs de la semaine (mathématiques, français, histoire-géographie)", "domaine": "todo",
           "icone": "mdi:school", "etat": "25", "attributs_utiles": {}, "actions": [], "styles": ["tuile"], "role": "lecture", "affichage": "valeur_detail", "ouvrir": true,
           "detail": [{"texte": "Exercices 12 à 18 page 154 du manuel de mathématiques", "valeur": "2026-10-13"},
                      {"texte": "Rédaction : raconter une journée extraordinaire", "valeur": ""},
                      {"texte": "Apprendre la leçon sur la Révolution française", "valeur": "2026-10-15"},
                      {"texte": "Fiche de vocabulaire", "valeur": ""}], "detail_total": 25},
          {"entity_id": "input_number.consigne", "nom": "Chauffage — température cible confort du séjour", "domaine": "input_number",
           "etat": "20.5", "unite": "°C", "attributs_utiles": {"min": 15, "max": 25, "step": 0.5, "unit_of_measurement": "°C"},
           "actions": ["set_value"], "styles": ["reglage"], "role": "reglage", "confirmer": true},
          {"entity_id": "select.mode", "nom": "Mode de fonctionnement de la ventilation double flux", "domaine": "select", "etat": "Automatique (selon l'humidité)",
           "attributs_utiles": {"options": ["Automatique (selon l'humidité)", "Éco", "Confort"]}, "actions": ["select_option"], "styles": ["reglage"], "role": "reglage"},
          {"entity_id": "script.nuit", "nom": "Bonne nuit : tout éteindre et fermer les volets", "domaine": "script", "etat": "off",
           "attributs_utiles": {}, "actions": ["turn_on"], "styles": ["bouton"], "role": "bouton", "confirmer": true}
        ], "vues_ha": [{"chemin": "/tableau-maison/energie-et-chauffage-du-sejour", "titre": "Tableau de la maison › Énergie et chauffage du séjour"},
                       {"chemin": "/lovelace/0", "titre": "Aperçu › Accueil"}]}"""
    }

    private val ctx get() = instrumentation.targetContext
    private val defauts = ArrayList<String>()
    private val dossier by lazy { File(ctx.getExternalFilesDir(null), "recette").apply { mkdirs() } }
    private lateinit var serveur: ServerSocket
    private val sauves = HashMap<String, Map<String, *>>()
    private var police = "100"
    private var etatSauve: () -> Unit = {}

    private val args: Bundle by lazy { (instrumentation as? InstrumentationTestRunner)?.arguments ?: Bundle() }
    private val taille: String by lazy {
        args.getString("taille")?.takeIf { it.isNotBlank() } ?: ctx.resources.displayMetrics.let { d ->
            "${(d.widthPixels / d.density).toInt()}x${(d.heightPixels / d.density).toInt()}dp"
        }
    }
    private val filtre: Set<String> by lazy { args.getString("ecrans").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet() }
    private val estTele get() = ReglagesTvActivity.estTele(ctx)

    private fun shell(cmd: String) {
        val pfd = instrumentation.uiAutomation.executeShellCommand(cmd)
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }

    // ------------------------------------------------------------------ Faux Home Assistant (127.0.0.1)

    private val LOU = "Maximilian-Alexander von Hohenzollern"
    private val TELE = "Fernsehgerät im Wohnzimmer (Erdgeschoss)"

    @Volatile private var partyGroupe = false

    private fun etatParty(): JSONObject = JSONObject()
        .put("ok", true).put("actif", true).put("ecran", "t1").put("ecran_nom", TELE).put("ici", true)
        .put("lecteur", "media_player.salon").put("lecteur_nom", "Wohnzimmer-Lautsprecher (Sonos Era 300, Südseite)")
        .put("ma", true).put("lecteur_ma", true).put("choisir", true).put("invites_mode", "direct")
        .put("qr", "https://exemple.invalid/api/pc_parental/party/invite/JETON_ESSAI_0123456789abcdef")
        .put("propositions", JSONArray().put(JSONObject().put("id", "p1").put("titre", "Ein außergewöhnlich langer Titelvorschlag eines Gastes").put("artiste", "Bartholomäus Langenscheidt").put("ts", 1)))
        .put("maintenant", JSONObject().put("etat", "playing").put("titre", "Medley électro (Live France / 2019) — version longue remasterisée")
            .put("artiste", "Jenifer, Un invité au nom vraiment très long").put("album", "Un album au titre interminable (édition deluxe)")
            .put("volume", 0.4).put("duree", 221).put("position", 84).put("position_ts", System.currentTimeMillis() / 1000.0).put("pochette", "")
            // Client Sendspin natif : un groupe (volume du groupe, détail par enceinte, la télé « · Vision » sans réglage).
            .apply { if (partyGroupe) put("groupe", true).put("membres", JSONArray()
                .put(JSONObject().put("id", "media_player.tele").put("nom", "$TELE · Vision").put("volume", JSONObject.NULL))
                .put(JSONObject().put("id", "media_player.salon").put("nom", "Wohnzimmer-Lautsprecher (Sonos Era 300, Südseite)").put("volume", 0.35))
                .put(JSONObject().put("id", "media_player.kueche").put("nom", "Küchenlautsprecher neben dem Kühlschrank").put("volume", 0.5))) })
        .put("file", JSONArray().apply {
            for (k in 1..6) put(JSONObject().put("id", "q$k").put("titre", "Titel Nummer $k mit einem ziemlich langen Namen").put("artiste", "Interpret $k").put("invite", k == 2))
        })
        .put("file_total", 146).put("similaires", false).put("fin_clin", -1)
        .put("enchainement", JSONObject().put("flux", true).put("fondu", false).put("duree", 8).put("precharge", true))
        .put("piloter", true).put("relire", 30).put("participer", true).put("ferme", "")
        .put("ecrans", JSONArray().put(JSONObject().put("id", "t1").put("nom", TELE)).put(JSONObject().put("id", "t2").put("nom", "Fernseher im Elternschlafzimmer")))
        .put("lecteurs", JSONArray()
            .put(JSONObject().put("entity_id", "media_player.salon").put("nom", "Wohnzimmer-Lautsprecher").put("etat", "playing").put("groupe", false).put("ma", true))
            .put(JSONObject().put("entity_id", "media_player.maison").put("nom", "Alle Lautsprecher im ganzen Haus").put("etat", "idle").put("groupe", true))
            .put(JSONObject().put("entity_id", "media_player.cle").put("nom", "Chromecast im Arbeitszimmer").put("etat", "unavailable").put("groupe", false).put("ma", true)
                .put("dispo", false).put("raison", "Hors ligne : allume-la ou vérifie qu'elle est branchée.")))
        .put("aide_lecteurs", "Une enceinte ou une télé manque ? Allume-la, puis dans Music Assistant : Réglages → Fournisseurs de lecteurs.")

    private fun musique(): JSONObject = JSONObject().put("ok", true)
        .put("items", JSONArray()
            .put(JSONObject().put("uri", "library://playlist/10").put("nom", "Die große Familien-Playlist für lange Autofahrten").put("type", "playlist"))
            .put(JSONObject().put("uri", "library://playlist/17").put("nom", "Disney classique").put("type", "playlist")))
        .put("resultats", JSONObject().put("track", JSONArray()
            .put(JSONObject().put("uri", "ytmusic://track/x").put("nom", "Un titre vraiment très long pour voir comment la ligne se comporte sur un petit écran")
                .put("type", "track").put("artiste", "Jenifer, Un invité au nom long").put("source", "YouTube Music").put("domaine", "ytmusic"))))
        .put("plateformes", JSONArray().put(JSONObject().put("domaine", "library").put("nom", "Bibliothèque")).put(JSONObject().put("domaine", "ytmusic").put("nom", "YouTube Music")))
        .put("conseil", "")

    private fun messages(): JSONObject = JSONObject().put("ok", true)
        .put("destinataires", JSONObject()
            .put("personnes", JSONArray().put(JSONObject().put("id", "person.lou").put("nom", LOU)).put(JSONObject().put("id", "person.anne").put("nom", "Anne-Charlotte de Montmorency-Laval")))
            .put("appareils", JSONArray().put(JSONObject().put("id", "t1").put("nom", TELE)).put(JSONObject().put("id", "e1").put("nom", "TELEFON-MAXIMILIAN-KINDERZIMMER"))))
        .put("types", JSONArray()
            .put(JSONObject().put("id", "message").put("nom", "Message").put("priorite", "normale"))
            .put(JSONObject().put("id", "important").put("nom", "Wichtige Mitteilung der Entscheider").put("priorite", "importante"))
            .put(JSONObject().put("id", "devoirs").put("nom", "Hausaufgaben-Erinnerung").put("priorite", "normale")))
        .put("reponses_proposees", JSONArray().put("Ich komme sofort").put("Noch fünf Minuten bitte").put("Einverstanden"))
        .put("envois", JSONArray().put(JSONObject().put("texte", "Das Abendessen steht in zehn Minuten auf dem Tisch – bitte jetzt das Spiel speichern!")
            .put("appareils", JSONArray().put(JSONObject().put("nom", TELE).put("etat", "repondu").put("etat_texte", "a répondu").put("reponse", "Noch fünf Minuten bitte")))))

    private fun servir(s: Socket): Unit = s.use {
        val entree = s.getInputStream().bufferedReader(Charsets.UTF_8)
        val premiere = entree.readLine() ?: return
        var longueur = 0
        while (true) { val l = entree.readLine() ?: break; if (l.isEmpty()) break; if (l.lowercase().startsWith("content-length:")) longueur = l.substringAfter(':').trim().toIntOrNull() ?: 0 }
        val buf = CharArray(longueur); var lu = 0
        while (lu < longueur) { val n = entree.read(buf, lu, longueur - lu); if (n < 0) break; lu += n }
        val (code, corps) = when {
            premiere.contains("/enroll") -> "403 Forbidden" to ""  // jamais d'inscription réelle
            premiere.contains("/party/etat") -> "200 OK" to etatParty().toString()
            premiere.contains("/party/musique") -> "200 OK" to musique().toString()
            premiere.contains("/party/pochette") -> "404 Not Found" to ""
            // 1.88.0 : les widgets HA d'essai (l'éditeur relit la liste en s'ouvrant) ; les états : ceux du catalogue.
            premiere.contains("/widgets/entites") -> "200 OK" to CATALOGUE_WIDGETS
            premiere.contains("/messages/app") -> "200 OK" to messages().toString()
            premiere.contains("/poll") -> "200 OK" to JSONObject().put("ok", true).put("action", "none").put("poll", 60).put("party", JSONObject().put("tuile", true)).toString()
            else -> "200 OK" to "{\"ok\":true}"
        }
        val octets = corps.toByteArray()
        s.getOutputStream().apply {
            write("HTTP/1.1 $code\r\nContent-Type: application/json\r\nContent-Length: ${octets.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(octets); flush()
        }
        Unit
    }

    /** La veille de Vision, coupée pendant la recette puis remise (16-10 : sur une machine lente, la veille de repli de
     *  la télé — 10 min sans touche sur l'accueil — recouvrait les écrans et bloquait la passe). */
    private var repliAvant = ReglesVeille.REPLI_DEFAUT
    private var veilleAvant = 0

    override fun setUp() {
        super.setUp()
        CodeEssai.poser(instrumentation.targetContext)  // faille du 16-10 : un Décideur a toujours un code
        repliAvant = Local.delaiRepli(ctx); veilleAvant = Local.delaiVeille(ctx)
        Local.poserDelaiRepli(ctx, 0); Local.poserDelaiVeille(ctx, 0)
        serveur = ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"))
        Thread {
            while (!serveur.isClosed) {
                val s = try { serveur.accept() } catch (_: Exception) { break }
                Thread { try { servir(s) } catch (_: Exception) {} }.start()
            }
        }.apply { isDaemon = true }.start()
        for (n in listOf("vision", "profil", "party")) sauves[n] = HashMap(ctx.getSharedPreferences(n, 0).all)
        if (android.os.Build.VERSION.SDK_INT >= 33) try { instrumentation.uiAutomation.grantRuntimePermission(ctx.packageName, "android.permission.POST_NOTIFICATIONS") } catch (_: Exception) {}
        args.getString("langue")?.takeIf { it.isNotBlank() }?.let { shell("cmd locale set-app-locales org.soprez.vision --locales $it"); SystemClock.sleep(1500) }
        val parent = Etat.parent; val verrou = Etat.verrouille; val msg = Etat.message; val prochain = Etat.prochainChangement
        val apps = Etat.apps; val raisons = Etat.raisons; val paquet = Usage.dernierPaquet; val reponse = Etat.derniereReponse
        etatSauve = {
            Etat.parent = parent; Etat.verrouille = verrou; Etat.message = msg; Etat.prochainChangement = prochain
            Etat.apps = apps; Etat.raisons = raisons; Usage.dernierPaquet = paquet; Etat.derniereReponse = reponse
        }
    }

    override fun tearDown() {
        try { serveur.close() } catch (_: Exception) {}
        Local.poserDelaiRepli(ctx, repliAvant); Local.poserDelaiVeille(ctx, veilleAvant)
        etatSauve()
        SetupActivity.resumeGestion = null; SetupActivity.resumeA = 0L
        Decouverte.trouve = null
        for ((n, v) in sauves) ctx.getSharedPreferences(n, 0).edit().clear().apply { v.forEach { (k, x) -> remettre(this, k, x) } }.commit()
        shell("settings put system font_scale 1.0")
        if (!args.getString("langue").isNullOrBlank()) shell("cmd locale set-app-locales org.soprez.vision --locales \"\"")
        CodeEssai.rendre(instrumentation.targetContext)
        super.tearDown()
    }

    @Suppress("UNCHECKED_CAST")
    private fun remettre(e: android.content.SharedPreferences.Editor, k: String, v: Any?) {
        when (v) {
            is String -> e.putString(k, v); is Boolean -> e.putBoolean(k, v); is Int -> e.putInt(k, v)
            is Long -> e.putLong(k, v); is Float -> e.putFloat(k, v); is Set<*> -> e.putStringSet(k, v as Set<String>)
        }
    }

    // ------------------------------------------------------------------ Données longues

    private val appareil = JSONObject("""{
        "id":"essai_tablette","nom":"Tablette du salon de Maximilian-Alexander","prenom":"Maximilian-Alexander","role":"Demandeur",
        "en_ligne":true,"android":true,"verrouille":false,"partage_tous":false,
        "protections":{"accessibilite":false,"usage":true,"vpn":false,"admin":false,"accueil":false},
        "apps":[{"libelle":"Jeux vidéo en ligne avec discussion"},{"libelle":"Réseaux sociaux"}],
        "sites":[{"nom":"unterrichtsmaterialien-grundschule-nordrhein-westfalen.de"},{"nom":"autre-exemple.com"}],
        "etiquettes":["Réseaux sociaux","Actu & médias","Pub & traçage"],
        "plages":[{"id":1,"nom":"Hausaufgaben und Lernzeit am Abend","debut":"18:30","fin":"20:00","actif":true,"portee":"etiquettes","etiquettes":["Jeux","Vidéos"]}],
        "exceptions":{"autorises":[{"libelle":"Encyclopédie collaborative","nom":"wiki"}],"bloques":[{"libelle":"Vidéos en continu","nom":"video"}],"toujours":[]},
        "temps":{"actif":95,"ouvert":120},"usage":{"actif":95,"ouvert":120,"focus":"org.exemple","focus_libelle":"Application de dessin extraordinaire"},
        "focus":"org.exemple","focus_libelle":"Application de dessin extraordinaire","inactif_s":900,
        "derogation":{"mode":"locked","fin":0}
    }""")
    /** Refonte lot 3 : une réponse /parent d'essai pour la fiche d'une personne (noms longs, 3 appareils, 2 réveils). */
    private val FICHE_ESSAI = """{"ok":true,"categories":[],
        "appareils":[
          {"id":"f1","nom":"Tablette de Maximilian-Alexander (salle de séjour, étage)","en_ligne":true,"verrouille":false,"prochain_verrou":"2030-01-01T21:30:00+01:00"},
          {"id":"f2","nom":"Téléphone portable de Maximilian-Alexander","en_ligne":true,"verrouille":true},
          {"id":"f3","nom":"Ordinateur de bureau de Maximilian-Alexander (bibliothèque)","en_ligne":false}],
        "personnes":[
          {"entite":"person.maximilian","prenom":"Maximilian-Alexander von Hohenzollern","role":"Demandeur","appareils":["f1","f2","f3"],"temps":{"actif":135},
           "fiche":{"modules":{"plages":true,"reveil":true,"temps_gagne":true,"regles_lieu":true,"entites":true},
             "role_nom":"Demandeur (accès encadré, demandes soumises à validation)",
             "reveils":[{"nom":"École primaire Jean-Baptiste-Poquelin (lundi au vendredi)","actif":true,"prochain":"2030-01-07T07:00:00+01:00"},
                        {"nom":"Entraînement de natation du samedi matin","actif":false,"prochain":"2030-01-05T08:30:00+01:00"}],
             "recompenses":{"liste":"Tâches ménagères hebdomadaires de Maximilian-Alexander","minutes":15,"max":60,"accorde":30},
             "regles_lieu":[{"zone":"Collège international Sainte-Geneviève-des-Bois","categories":["Jeux","Réseaux sociaux","Vidéo"]},
                            {"zone":"Bibliothèque","categories":["Jeux"]}],
             "widgets":3}},
          {"entite":"person.lou","prenom":"Lou","appareils":[]}]}"""

    private val categories = JSONArray("""[
        {"id":"jeux","nom":"Jeux vidéo et divertissement en ligne","apps":[{"libelle":"Jeu de construction"}],"sites":["jeux.exemple"],"coupe":true,"raison":"Fermé par le planning du soir"},
        {"id":"pub","nom":"Pub & traçage","apps":[],"sites":["pub.exemple","trace.exemple"],"maison":true},
        {"id":"etude","nom":"Études","apps":[],"sites":[],"coupe":false}
    ]""")
    private val demandes = JSONArray("""[
        {"pc":"essai_tablette","id":"d1","prenom":"Maximilian-Alexander","libelle":"Application de dessin extraordinaire","genre":"apps","cle":"org.exemple","duree":"","motif":"Pour finir mon exposé d'histoire-géographie avec Noa"},
        {"pc":"essai_tablette","id":"d2","prenom":"Maximilian-Alexander","genre":"sites","cle":"unterrichtsmaterialien-grundschule-nordrhein-westfalen.de","libelle":"","motif":""},
        {"pc":"essai_tablette","id":"d3","prenom":"Maximilian-Alexander","genre":"temps","minutes":45}
    ]""")

    /** Un appareil relié au faux Home Assistant local, avec un relevé de gestion et un temps du jour aux noms longs. */
    private fun relier() {
        val cfg = Config(ctx)
        val adresse = "http://127.0.0.1:${serveur.localPort}"
        cfg.id = "essai_ecran"; cfg.secret = "s".repeat(32); cfg.nom = "Tablette du salon de Maximilian-Alexander"
        cfg.utilisateur = "Maximilian-Alexander"; cfg.lieu = "Résidence secondaire des Grands Chênes"; cfg.personne = "person.essai"
        cfg.urlInterne = adresse; cfg.urlExterne = ""; cfg.urlActive = adresse; cfg.parentJusqua = 0L
        Etat.derniereReponse = System.currentTimeMillis(); Etat.prochainChangement = System.currentTimeMillis() + 80 * 60_000L
        ctx.getSharedPreferences("profil", 0).edit().putString("temps", JSONObject().put("actif", 125).put("appareils", JSONArray()
            .put(JSONObject().put("nom", "Tablette du salon de Maximilian-Alexander").put("actif", 95))
            .put(JSONObject().put("nom", "Téléphone portable personnel").put("actif", 30))).toString())
            .putLong("a", System.currentTimeMillis()).putStringSet("fonctions", setOf("gerer", "pause_generale")).commit()
        SetupActivity.resumeGestion = JSONObject().put("demandes", demandes).put("appareils", JSONArray().put(appareil)
            .put(JSONObject(appareil.toString()).put("id", "essai_2").put("verrouille", true)))
        SetupActivity.resumeA = System.currentTimeMillis() + 3600_000L
    }

    private fun delier() {
        val cfg = Config(ctx)
        cfg.oublierInscription(); cfg.urlInterne = ""; cfg.urlExterne = ""; cfg.urlActive = ""; cfg.cleInscription = ""; cfg.sansCle = false
        Liaison.oublierEcartes()
        SetupActivity.resumeGestion = null; SetupActivity.resumeA = 0L
    }

    // ------------------------------------------------------------------ Parcours

    fun testRecetteAntiDebordement() {
        val polices = (args.getString("police") ?: "1.0,1.3").split(',').mapNotNull { it.trim().toFloatOrNull() }
        val echecs = ArrayList<String>()
        for (echelle in polices) {
            shell("settings put system font_scale $echelle")
            SystemClock.sleep(1500)
            police = "${(echelle * 100).toInt()}"
            defauts.clear()
            imposerRotation()
            orientation()
            parcourir()
            val rapport = File(dossier, "recette-lot1-android-rapport-$taille-$police.txt")
            rapport.writeText("Recette visuelle Android — $taille, police $police %, ${if (estTele) "TV" else "téléphone/tablette"}\n" +
                defauts.joinToString("\n").ifEmpty { "Aucun défaut." } + "\n")
            // Les deux polices passent toujours (un rapport chacune) ; l'échec, s'il y en a, vient à la fin.
            if (defauts.isNotEmpty()) echecs += "Débordements ($taille, police $police %) :\n" + defauts.joinToString("\n")
        }
        assertTrue(echecs.joinToString("\n\n"), echecs.isEmpty())
    }

    /** Le sens voulu pour cette taille : paysage (tablette paysage, TV), portrait (téléphone, tablette portrait), ou null. */
    private fun paysageVoulu(): Boolean? = when {
        taille.contains("paysage") || taille.startsWith("tv-") -> true
        taille.contains("portrait") || taille.endsWith("dp") -> false
        else -> null
    }

    /**
     * 1.88.0 (recette des widgets) : le système remettait parfois l'écran en paysage au lancement de l'instrumentation,
     * après le réglage du lanceur de recette ; la série entière passait alors en paysage sans le dire. La rotation est
     * donc imposée ici (télé : son sens naturel), et vérifiée à chaque capture (voir verifierEtCapturer).
     */
    private fun imposerRotation() {
        val voulu = paysageVoulu() ?: return
        if (estTele) return
        instrumentation.uiAutomation.setRotation(if (voulu) android.app.UiAutomation.ROTATION_FREEZE_90 else android.app.UiAutomation.ROTATION_FREEZE_0)
        SystemClock.sleep(1500)
    }

    /** La taille demandée est-elle bien celle de l'écran (une rotation non appliquée faussait toute la série) ? */
    private fun orientation() {
        // 1.90.1 : la taille courante de la fenêtre (les ressources de l'appli peuvent garder l'orientation de son
        // démarrage) ; avant Android 11, les métriques de l'écran.
        val d = ctx.resources.displayMetrics
        // La rotation demandée par le script peut finir de s'appliquer après le lancement : jusqu'à 10 s d'attente.
        val attendu = taille.contains("paysage") || taille.startsWith("tv-")
        val wm = ctx.getSystemService(android.view.WindowManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 30) for (i in 0 until 20) {
            val b = wm.maximumWindowMetrics.bounds
            if ((b.width() > b.height()) == attendu) break
            SystemClock.sleep(500)
        }
        val (largeur, hauteur) = if (android.os.Build.VERSION.SDK_INT >= 30)
            (ctx.getSystemService(android.view.WindowManager::class.java)).maximumWindowMetrics.bounds.let { it.width() to it.height() }
        else d.widthPixels to d.heightPixels
        val paysage = largeur > hauteur
        val voulu = when {
            taille.contains("paysage") || taille.startsWith("tv-") -> true
            taille.contains("portrait") || taille.endsWith("dp") -> false
            else -> return
        }
        if (paysage != voulu) defauts += "Taille « $taille » : l'écran est en ${if (paysage) "paysage" else "portrait"} " +
            "(${largeur}x${hauteur}) — rotation non appliquée, résultats à refaire"
    }

    private fun voulu(nom: String) = filtre.isEmpty() || filtre.any { nom.startsWith(it) }

    /** 1.88.0 : la planche des widgets interactifs, à la taille des cases de l'accueil (lecture seule sur la télé). */
    /** 1.90.1 : le widget d'écran d'accueil à liste défilante, monté comme le fait le lanceur (4 × 3 et 2 × 2). */
    private fun plancheListe(a: Activity): View {
        val th = Local.themeEffectif(a, Palette.LANCEUR)
        val d = a.resources.displayMetrics.density
        val col = android.widget.LinearLayout(a).apply { orientation = android.widget.LinearLayout.VERTICAL; setBackgroundColor(th.fond); setPadding(Ui.dp(a, 16f), Ui.dp(a, 16f), Ui.dp(a, 16f), Ui.dp(a, 16f)) }
        val largeur = ((a.resources.displayMetrics.widthPixels - 2 * Ui.dp(a, 16f)) / d).toInt()
        for ((wDp, hDp) in listOf(largeur to 330, (largeur / 2).coerceAtLeast(150) to 180)) {
            val r = WidgetsHa.Reglage("liste-$wDp", listOf("sensor.liste_longue"), WidgetsHa.TUILE, 4, 3, affichage = WidgetsHa.AFF_DETAIL)
            val (image, y) = WidgetsSysteme.imageSansDetail(a, r, wDp, hDp, exemple = false) ?: continue
            val bloc = android.widget.LinearLayout(a).apply { orientation = android.widget.LinearLayout.VERTICAL }
            bloc.addView(android.widget.ImageView(a).apply { setImageBitmap(android.graphics.Bitmap.createBitmap(image, 0, 0, image.width, y)); scaleType = android.widget.ImageView.ScaleType.FIT_XY },
                android.widget.LinearLayout.LayoutParams(image.width, y))
            val bas = android.widget.FrameLayout(a)
            bas.addView(android.widget.ImageView(a).apply { setImageBitmap(android.graphics.Bitmap.createBitmap(image, 0, y, image.width, image.height - y)); scaleType = android.widget.ImageView.ScaleType.FIT_XY })
            val lignes = WidgetsHa.detail(WidgetsHaStock.entite(a, "sensor.liste_longue")!!, WidgetsHa.EtatEntite("27")).first
            // Comme le lanceur : une ListView (elle défile), chaque ligne tirée de la vraie mise en page du widget.
            val liste = android.widget.ListView(a).apply {
                setPadding(Ui.dp(a, 12f), 0, Ui.dp(a, 12f), Ui.dp(a, 10f)); clipToPadding = true; divider = null; dividerHeight = 0
                adapter = object : android.widget.BaseAdapter() {
                    override fun getCount() = lignes.size
                    override fun getItem(i: Int) = lignes[i]
                    override fun getItemId(i: Int) = i.toLong()
                    override fun getView(i: Int, convertView: View?, parent: android.view.ViewGroup): View {
                        val rv = android.widget.RemoteViews(a.packageName, R.layout.widget_ha_ligne)
                        rv.setTextViewText(R.id.widget_ligne, "• " + WidgetsHa.texteLigne(lignes[i])); rv.setTextColor(R.id.widget_ligne, th.encre2)
                        return rv.apply(a, parent)
                    }
                }
            }
            bas.addView(liste)
            bloc.addView(bas, android.widget.LinearLayout.LayoutParams(image.width, image.height - y))
            col.addView(bloc, android.widget.LinearLayout.LayoutParams(image.width, image.height).apply { bottomMargin = Ui.dp(a, 12f) })
        }
        // 1.90.5 (recette 412 dp paysage) : la planche (330 dp + 180 dp) défile, comme la grille de l'accueil, au lieu de passer
        // sous le bas de l'écran.
        return android.widget.ScrollView(a).apply { setBackgroundColor(th.fond); addView(col) }
    }

    /** 16-10 (retour d'essai) : les tailles d'accueil de la recette des widgets chauffage, de 1 × 1 à 4 × 2. */
    private val TAILLES_CHAUFFAGE = listOf(1 to 1, 2 to 1, 3 to 1, 4 to 1, 2 to 2, 4 to 2)

    /**
     * 16-10 (l'auteur, finitions) : le thermostat avec son libellé « Consigne » activé, et la courbe de température dont
     * l'historique ne couvre que la fin de la période (capteur qui n'envoie qu'aux changements), à chaque taille, à la
     * taille des cases de l'accueil ; ils agissent sur la télé aussi (parité).
     */
    private fun plancheChauffage(a: Activity): View {
        val th = Local.themeEffectif(a, Palette.LANCEUR)
        val dm = a.resources.displayMetrics
        val marge = Ui.dp(a, 16f)
        val l = ((dm.widthPixels - 2 * marge) / Accueil.COLONNES).let { if (dm.widthPixels > dm.heightPixels) minOf(it, Ui.dp(a, 112f)) else it }
        val h = (l * 1.14f).toInt()
        val sv = android.widget.ScrollView(a).apply { setBackgroundColor(th.fond) }
        val col = android.widget.LinearLayout(a).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(marge, marge, marge, marge) }
        sv.addView(col)
        val fin = System.currentTimeMillis()
        // Les 9 dernières heures seulement, une mesure toutes les 30 min, la dernière il y a 2 h.
        val points = (0 until 14).map { k -> WidgetsHa.Point(fin - 9 * 3_600_000L + k * 1_800_000L, 19.0 + (k % 5) * 0.4) }
        for ((w, hh) in TAILLES_CHAUFFAGE) {
            val consigne = WidgetsHa.Reglage("ch-$w-$hh", listOf("input_number.consigne"), WidgetsHa.REGLAGE, w, hh, libelle = "Consigne")
            col.addView(VueWidgetHa(a, th, consigne, lectureSeule = false, agir = { _, _ -> }, details = { _, _ -> }, agirAvec = { _, _, _ -> }).apply { tag = "essai_consigne_${w}x$hh" },
                android.widget.LinearLayout.LayoutParams(l * w, h * hh).apply { bottomMargin = Ui.dp(a, 10f) })
            val courbe = WidgetsHa.Reglage("co-$w-$hh", listOf("input_number.consigne"), WidgetsHa.GRAPHIQUE, w, hh, libelle = "Température ambiante")
            col.addView(VueWidgetHa(a, th, courbe, lectureSeule = false, historiqueInitial = WidgetsHa.Historique.Nombres("°C", points)).apply { tag = "essai_courbe_${w}x$hh" },
                android.widget.LinearLayout.LayoutParams(l * w, h * hh).apply { bottomMargin = Ui.dp(a, 10f) })
        }
        return sv
    }

    /** Les vérifications de la planche chauffage : le libellé « Consigne » se voit en entier, la courbe va d'un bord à l'autre. */
    private fun verifierChauffage(a: Activity) {
        val th = Local.themeEffectif(a, Palette.LANCEUR)
        fun vues(v: View, l: ArrayList<View> = ArrayList()): ArrayList<View> { l.add(v); if (v is android.view.ViewGroup) for (i in 0 until v.childCount) vues(v.getChildAt(i), l); return l }
        for ((w, hh) in TAILLES_CHAUFFAGE) {
            val t = a.window.decorView.findViewWithTag<View>("essai_consigne_${w}x$hh") ?: continue
            val lib = vues(t).filterIsInstance<android.widget.TextView>().firstOrNull { it.text.toString() == "Consigne" }
            if (lib == null || lib.width <= 0 || lib.visibility != View.VISIBLE) defauts += "chauffage ${w}×$hh : le libellé « Consigne » ne s'affiche pas"
            else if ((0 until (lib.layout?.lineCount ?: 0)).any { lib.layout!!.getEllipsisCount(it) > 0 }) defauts += "chauffage ${w}×$hh : « Consigne » coupé"
            // 16-10 (recette 360 dp) : les boutons − / + entiers (ils sortaient par le bas en 2 × 1) et ronds (« + » écrasé en 1 × 1).
            for (b in listOf("widget_moins", "widget_plus")) t.findViewWithTag<View>(b)?.let { bt ->
                // Dans chacun de ses parents jusqu'au widget (marges intérieures comprises) : sinon il est rogné.
                var v: View = bt; var x = 0; var y = 0; var coupe = false
                while (v !== t) {
                    x += v.left; y += v.top
                    val p = v.parent as? android.view.ViewGroup ?: break
                    if (x < p.paddingLeft - 1 || y < p.paddingTop - 1 || x + bt.width > p.width - p.paddingRight + 1 || y + bt.height > p.height - p.paddingBottom + 1) coupe = true
                    v = p
                }
                if (coupe) defauts += "chauffage ${w}×$hh : bouton $b coupé"
                if (Math.abs(bt.width - bt.height) > 2) defauts += "chauffage ${w}×$hh : bouton $b déformé (${bt.width}×${bt.height} px)"
            }
            val c = a.window.decorView.findViewWithTag<View>("essai_courbe_${w}x$hh") ?: continue
            // Le titre de la courbe : en entier (rapetissé s'il le faut), jamais « … » pour « Température ambiante » en 2 × 1 et plus.
            if (w >= 2) vues(c).filterIsInstance<android.widget.TextView>().firstOrNull { it.text.toString() == "Température ambiante" }?.let { tt ->
                if ((0 until (tt.layout?.lineCount ?: 0)).any { tt.layout!!.getEllipsisCount(it) > 0 }) defauts += "courbe ${w}×$hh : « Température ambiante » coupé" }
            val dessin = vues(c).firstOrNull { it.javaClass.simpleName == "Courbe" }
            if (dessin == null) { defauts += "courbe ${w}×$hh : pas de courbe"; continue }
            val parent = dessin.parent as View
            val utile = parent.width - parent.paddingLeft - parent.paddingRight
            if (dessin.width < utile * 0.95f) defauts += "courbe ${w}×$hh : ${dessin.width} px sur $utile de large"
            if (dessin.width <= 0 || dessin.height <= 0) continue
            val img = android.graphics.Bitmap.createBitmap(dessin.width, dessin.height, android.graphics.Bitmap.Config.ARGB_8888)
            dessin.draw(android.graphics.Canvas(img))
            fun encre(x: Int) = (0 until img.height).any { y -> val p = img.getPixel(x, y); android.graphics.Color.alpha(p) > 200 &&
                Math.abs(android.graphics.Color.red(p) - android.graphics.Color.red(th.or)) < 30 && Math.abs(android.graphics.Color.green(p) - android.graphics.Color.green(th.or)) < 30 &&
                Math.abs(android.graphics.Color.blue(p) - android.graphics.Color.blue(th.or)) < 30 }
            val bord = (img.width * 0.04f).toInt().coerceAtLeast(2)
            if (!(0 until bord).any { encre(it) }) defauts += "courbe ${w}×$hh : rien de tracé au bord gauche"
            if (!((img.width - bord) until img.width).any { encre(it) }) defauts += "courbe ${w}×$hh : rien de tracé au bord droit"
        }
    }

    private fun plancheWidgets(a: Activity): View {
        val tele = ReglagesTvActivity.estTele(a)
        val th = Local.themeEffectif(a, Palette.LANCEUR)
        val dm = a.resources.displayMetrics
        val marge = Ui.dp(a, 16f)
        // 1.90.1 : comme l'accueil — la largeur sans les barres du système sur les côtés (Android 15+), et en paysage
        // des cases à leur taille utile (112 dp au plus) plutôt qu'un quart de la largeur.
        val cotes = if (android.os.Build.VERSION.SDK_INT >= 35) a.window.decorView.rootWindowInsets?.let { Marges.cotesSysteme(it) } else null
        val largeur = dm.widthPixels - (cotes?.first ?: 0) - (cotes?.second ?: 0)
        val paysage = largeur > dm.heightPixels
        val l = ((largeur - 2 * marge) / Accueil.COLONNES).let { if (paysage) minOf(it, Ui.dp(a, 112f)) else it }
        val h = (l * 1.14f).toInt()
        val sv = android.widget.ScrollView(a).apply { setBackgroundColor(th.fond) }
        val col = android.widget.LinearLayout(a).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(marge, marge, marge, marge) }
        sv.addView(col)
        listOf(
            WidgetsHa.Reglage("p1", listOf("todo.devoirs"), WidgetsHa.TUILE, 2, 2, affichage = WidgetsHa.AFF_VALEUR_DETAIL),
            WidgetsHa.Reglage("p2", listOf("todo.devoirs"), WidgetsHa.TUILE, 4, 1, affichage = WidgetsHa.AFF_DETAIL),
            WidgetsHa.Reglage("p3", listOf("input_number.consigne"), WidgetsHa.REGLAGE, 2, 1),
            WidgetsHa.Reglage("p4", listOf("select.mode"), WidgetsHa.REGLAGE, 2, 1),
            WidgetsHa.Reglage("p5", listOf("script.nuit"), WidgetsHa.BOUTON, 1, 1),
            // 1.90.1 : une longue liste — autant de lignes que la place réelle en permet, puis « +N ».
            WidgetsHa.Reglage("p6", listOf("sensor.liste_longue"), WidgetsHa.TUILE, 2, 2, affichage = WidgetsHa.AFF_DETAIL),
            WidgetsHa.Reglage("p7", listOf("sensor.liste_longue"), WidgetsHa.TUILE, 4, 3, affichage = WidgetsHa.AFF_VALEUR_DETAIL),
        ).forEach { r ->
            val v = VueWidgetHa(a, th, r, lectureSeule = false, agir = { _, _ -> }, details = { _, _ -> }, agirAvec = { _, _, _ -> })  // 16-10 : la télé agit aussi
            col.addView(v, android.widget.LinearLayout.LayoutParams(l * r.w, h * r.h).apply { bottomMargin = Ui.dp(a, 10f) })
        }
        return sv
    }

    private fun parcourir() {
        // --- Non relié : accueil (bandeau « Non relié »), Bienvenue, liaison proposée, assistant.
        delier()
        ecran("accueil-non-relie", Intent(ctx, LanceurActivity::class.java))
        ecran("bienvenue", Intent(ctx, SetupActivity::class.java), preparer = { a ->
            // « Autres méthodes » dépliées : toutes les voies de liaison à l'écran.
            a.window.decorView.findViewWithTag<View>("autres_methodes")?.visibility = View.VISIBLE
        })
        if (voulu("liaison")) {
            val a = ouvrir(Intent(ctx, SetupActivity::class.java))
            val fin = SystemClock.elapsedRealtime() + Decouverte.DELAI_MS + 3000
            while (Decouverte.enCours && SystemClock.elapsedRealtime() < fin) SystemClock.sleep(300)
            Decouverte.trouve = Liaison.Serveur("Home Assistant der Familie Hohenzollern-Sigmaringen", "", "http://127.0.0.1:${serveur.localPort}")
            instrumentation.runOnMainSync {
                @Suppress("UNCHECKED_CAST")
                (a.javaClass.getDeclaredField("peindreDecouverte").apply { isAccessible = true }.get(a) as? () -> Unit)?.invoke()
            }
            instrumentation.waitForIdleSync(); SystemClock.sleep(500)
            verifierEtCapturer(a, "liaison", null, defiler = true)
            fermer(a)
            Decouverte.trouve = null
        }
        ecran("assistant", Intent(ctx, SetupActivity::class.java), preparer = { a -> champ(a, "assistant", true); appeler(a, "dessiner") })

        // --- Relié : accueil (TV ou téléphone), tiroir, écran principal Décideur et Demandeur.
        relier()
        Etat.parent = false
        // 1.90.1 : une grille remplie (une douzaine d'applis), pour que l'essai de chevauchement ait de quoi mordre ;
        // la disposition de l'appareil est remise ensuite.
        val dispositionAvant = if (estTele) null else Accueil.disposition(ctx).map { Accueil.Place(it.cle, it.col, it.row, it.w, it.h) }
        if (!estTele) Accueil.applicationsInstallees(ctx).map { it.activityInfo.packageName }.distinct().take(12)
            .forEach { pkg -> if (Accueil.disposition(ctx).none { it.cle == pkg }) Accueil.ajouterALaGrille(ctx, pkg) }
        // 16-10 : la disposition par défaut, posée ici — une passe interrompue laissait la liste alphabétique (téléphone) ou
        // « Vertical » sans fond (télé) sur l'émulateur, et l'accueil par défaut n'était plus jamais vérifié.
        if (estTele) { DispositionAccueil.poserDisposTele(ctx, DispositionAccueil.TELE_RANGEES); DispositionAccueil.poserFondApplis(ctx, true); DispositionAccueil.poserCadreWidgets(ctx, true) }
        else { Accueil.poserMode(ctx, Accueil.MODE_DEFILEMENT); DispositionAccueil.poserPositionZone(ctx, DispositionAccueil.ZONE_DESSUS); DispositionAccueil.poserCoteIndex(ctx, DispositionAccueil.INDEX_DROITE) }
        ecran(if (estTele) "accueil-tv" else "accueil-telephone", Intent(ctx, LanceurActivity::class.java))
        // 1.90.5 (retour d'essai) : la zone du haut avec le remerciement et l'œil joyeux, puis les réactions de l'œil (info, erreur :
        // l'œil et sa bulle de texte, posés dans un coin libre, hors des boutons et des textes).
        val zoneAvant = ZoneHaut.widgets(ctx)
        ZoneHaut.poser(ctx, listOf(ZoneHaut.REMERCIEMENT))
        ecran("accueil-remerciement", Intent(ctx, LanceurActivity::class.java))
        // 1.91.x (retour d'essai) : l'arrière-plan de l'accueil — une photo très claire (voile), le fond animé, un dégradé.
        val fondAvant = FondAccueil.sorte(ctx)
        FondAccueil.garderPhoto(ctx, FondAccueil.imageClaire(540, 960), 960)
        FondAccueil.poser(ctx, FondAccueil.Sorte.PHOTO); ecran("accueil-fond-photo", Intent(ctx, LanceurActivity::class.java))
        FondAccueil.poser(ctx, FondAccueil.Sorte.ANIME); ecran("accueil-fond-anime", Intent(ctx, LanceurActivity::class.java))
        FondAccueil.poser(ctx, FondAccueil.Sorte.DEGRADE, 1); ecran("accueil-fond-degrade", Intent(ctx, LanceurActivity::class.java))
        FondAccueil.poser(ctx, fondAvant)
        if (!estTele) { ZoneHaut.poser(ctx, listOf(ZoneHaut.HORLOGE, ZoneHaut.METEO)); ecran("accueil-horloge-meteo", Intent(ctx, LanceurActivity::class.java)) }
        ecran("reaction-info", Intent(ctx, LanceurActivity::class.java), preparer = { a ->
            Ui.toast(a, ctx.getString(R.string.reglagestv_seulement_point, "Home Assistant"))
        })
        ecran("reaction-erreur", Intent(ctx, LanceurActivity::class.java), preparer = { a ->
            Ui.toast(a, ctx.getString(R.string.tableauediteur_injoignable), genre = OeilModele.Genre.ERREUR)
        })
        // Lanceur (l'auteur, 13 octobre) : liste alphabétique (index à droite, puis à gauche, saut à « M »), icônes en haut
        // quand la zone du haut est vide ou placée en dessous ; télé « Vertical », colonne à gauche, au centre, à droite,
        // avec des zones de widgets activées. Les préférences du lanceur sont remises ensuite.
        val lanceurAvant = ctx.getSharedPreferences("lanceur", android.content.Context.MODE_PRIVATE).all.toMap()
        if (!estTele) {
            ZoneHaut.poser(ctx, emptyList())
            ecran("accueil-zone-vide", Intent(ctx, LanceurActivity::class.java))
            ZoneHaut.poser(ctx, listOf(ZoneHaut.HORLOGE, ZoneHaut.METEO))
            DispositionAccueil.poserPositionZone(ctx, DispositionAccueil.ZONE_DESSOUS)
            ecran("accueil-zone-dessous", Intent(ctx, LanceurActivity::class.java))
            DispositionAccueil.poserPositionZone(ctx, DispositionAccueil.ZONE_DESSUS)
            Accueil.poserMode(ctx, Accueil.MODE_ALPHA)
            ecran("accueil-alpha", Intent(ctx, LanceurActivity::class.java))
            ecran("accueil-alpha-saut", Intent(ctx, LanceurActivity::class.java), preparer = { a ->
                (a.window.decorView.findViewWithTag<View>("index_alpha") as? IndexAlpha)?.aller('M')
            })
            ZoneHaut.poser(ctx, emptyList())
            DispositionAccueil.poserCoteIndex(ctx, DispositionAccueil.INDEX_GAUCHE)
            ecran("accueil-alpha-gaucher", Intent(ctx, LanceurActivity::class.java))
        } else {
            DispositionAccueil.poserDisposTele(ctx, DispositionAccueil.TELE_VERTICAL)
            DispositionAccueil.poserWidgetsZone(ctx, DispositionAccueil.ZONE_G, listOf(ZoneHaut.HORLOGE))
            DispositionAccueil.poserWidgetsZone(ctx, DispositionAccueil.ZONE_D, listOf(ZoneHaut.HORLOGE, ZoneHaut.METEO))
            DispositionAccueil.poserZoneActivee(ctx, DispositionAccueil.ZONE_G, true)
            DispositionAccueil.poserZoneActivee(ctx, DispositionAccueil.ZONE_D, true)
            for (c in listOf(DispositionAccueil.COLONNE_GAUCHE, DispositionAccueil.COLONNE_CENTRE, DispositionAccueil.COLONNE_DROITE)) {
                DispositionAccueil.poserColonne(ctx, c)
                ecran("accueil-tv-vertical-$c", Intent(ctx, LanceurActivity::class.java))
            }
            // 16-10 (l'auteur, télé du salon) : rangées de widgets en haut et en bas, applis sans fond, widgets sans cadre.
            DispositionAccueil.poserColonne(ctx, DispositionAccueil.COLONNE_CENTRE)
            DispositionAccueil.poserWidgetsZone(ctx, DispositionAccueil.ZONE_H, listOf(ZoneHaut.METEO, ZoneHaut.REMERCIEMENT))
            DispositionAccueil.poserWidgetsZone(ctx, DispositionAccueil.ZONE_B, listOf(ZoneHaut.HORLOGE))
            DispositionAccueil.poserZoneActivee(ctx, DispositionAccueil.ZONE_H, true)
            DispositionAccueil.poserZoneActivee(ctx, DispositionAccueil.ZONE_B, true)
            ecran("accueil-tv-vertical-rangees", Intent(ctx, LanceurActivity::class.java))
            DispositionAccueil.poserFondApplis(ctx, false)
            DispositionAccueil.poserCadreWidgets(ctx, false)
            ecran("accueil-tv-vertical-sans-fond-ni-cadre", Intent(ctx, LanceurActivity::class.java))
            // Le clavier de Vision à la place du clavier Android (fenêtre de saisie d'un champ).
            ecran("clavier-tele", Intent(ctx, LanceurActivity::class.java), boite = { a -> ClavierTele.saisir(Ui.champ(a, a.getString(R.string.zonehaut_merci), "Merci")); null })
        }
        ctx.getSharedPreferences("lanceur", android.content.Context.MODE_PRIVATE).edit().apply {
            clear()
            for ((k, v) in lanceurAvant) when (v) {
                is String -> putString(k, v); is Boolean -> putBoolean(k, v); is Int -> putInt(k, v)
                is Long -> putLong(k, v); is Float -> putFloat(k, v)
                is Set<*> -> putStringSet(k, v.filterIsInstance<String>().toSet())
            }
        }.commit()
        ZoneHaut.poser(ctx, zoneAvant)
        ecran("accueil-tiroir", Intent(ctx, LanceurActivity::class.java), preparer = { a -> appeler(a, "tiroirApplis") })
        ecran("accueil-demande-temps", Intent(ctx, LanceurActivity::class.java), boite = { a -> appeler(a, "demanderTemps"); null })
        if (dispositionAvant != null) Accueil.ecrireDisposition(ctx, dispositionAvant)
        for (decideur in listOf(true, false)) {
            Etat.parent = decideur
            ecran("principal-${if (decideur) "decideur" else "demandeur"}", Intent(ctx, SetupActivity::class.java), defiler = true, preparer = { a ->
                champ(a, "tempsDeplie", true); champ(a, "gestionDepliee", true); champ(a, "autorisationsDepliees", true); appeler(a, "dessiner")
            })
        }
        Etat.parent = true

        // --- Refonte lot 5 : les espaces de l'écran principal (barre en bas, rail, ou rail de la télé), avec un relevé
        // de gestion d'essai (noms longs : personnes, appareils, demandes) ; Réglages : « Avancé » et l'espace gestion dépliés.
        val resumeAvant = SetupActivity.resumeGestion
        SetupActivity.resumeGestion = JSONObject(FICHE_ESSAI).put("demandes", demandes).apply { getJSONArray("appareils").put(appareil) }
        SetupActivity.resumeA = System.currentTimeMillis() + 3600_000L
        for (e in listOf(Espaces.AUJOURDHUI, Espaces.PERSONNES, Espaces.APPAREILS, Espaces.REGLAGES)) {
            ecran("espace-$e", Intent(ctx, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_ESPACE, e), defiler = true, preparer = { a ->
                champ(a, "espace", e); champ(a, "avanceDeplie", true); champ(a, "autorisationsDepliees", true); champ(a, "gestionDepliee", true)
                appeler(a, "dessiner")
            })
        }
        Etat.parent = false
        ecran("espace-${Espaces.MOI}", Intent(ctx, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_ESPACE, Espaces.MOI), defiler = true, preparer = { a ->
            champ(a, "espace", Espaces.MOI); champ(a, "tempsDeplie", true); appeler(a, "dessiner")
        })
        Etat.parent = true
        SetupActivity.resumeGestion = resumeAvant

        // --- Gestion (ParentsActivity), Catégories, Autorisations, GestionAppareil.
        ecran("gestion", Intent(ctx, ParentsActivity::class.java), defiler = true, preparer = { a ->
            val conc = JSONObject(appareil.toString()).put("id", "essai_conc").put("nom", "Ordinateur portable de Maximilian-Alexander")
                .put("concentration", JSONObject().put("fin", System.currentTimeMillis() / 1000 + 5400).put("apps", JSONArray().put("org.exemple")).put("proposees", JSONArray()))
            champ(a, "appareils", JSONArray().put(appareil).put(conc)); champ(a, "demandes", demandes); champ(a, "categories", categories)
            // Refonte UX, lot 2 : les lots « À décider » (textes longs, comme les sert HA en allemand).
            champ(a, "lots", JSONArray()
                .put(JSONObject().put("genre", "appairage").put("nombre", 12).put("libelle", "12 Geräte warten auf Bestätigung durch einen Entscheider").put("ou", "/vision-appairage"))
                .put(JSONObject().put("genre", "logiciels").put("nombre", 3).put("libelle", "3 Programme zu bestätigen").put("ou", "/lovelace-vision/logiciels")))
            champ(a, "deplies", hashSetOf("p:autres", "essai_tablette:plages")); champ(a, "erreur", ""); appeler(a, "dessiner")
        })
        // HA 2.5.0 (autorisations_v2) : les réponses 15 min / 1 h / Refuser, puis Exception et Toujours disponible.
        ecran("gestion-trois-modes", Intent(ctx, ParentsActivity::class.java), defiler = true, preparer = { a ->
            champ(a, "appareils", JSONArray().put(appareil)); champ(a, "demandes", demandes); champ(a, "categories", categories)
            champ(a, "troisModes", true); champ(a, "erreur", ""); appeler(a, "dessiner")
        })
        // HA 2.5.1 : une demande groupée (un logiciel et ses sites bloqués), éléments à cocher.
        ecran("gestion-demande-groupee", Intent(ctx, ParentsActivity::class.java), defiler = true, preparer = { a ->
            champ(a, "appareils", JSONArray().put(appareil)); champ(a, "categories", categories)
            champ(a, "demandes", JSONArray().put(JSONObject().put("pc", "essai_tablette").put("id", "d9").put("prenom", "Maximilian-Alexander")
                .put("genre", "apps").put("cle", "com.roblox.client").put("libelle", "Roblox : jeux de construction en ligne")
                .put("sites", JSONArray().put("rbxcdn.com").put("roblox-contenus-telechargeables-de-la-communaute.com").put("rbxinfra.com"))))
            champ(a, "troisModes", true); champ(a, "elements", true); champ(a, "erreur", ""); appeler(a, "dessiner")
        })
        // Lot 2 (A8) : « Tout fermer », la limite dite avant le choix de la durée.
        ecran("gestion-tout-fermer", Intent(ctx, ParentsActivity::class.java), boite = { a -> appeler(a, "poserPauseGenerale"); null })
        CategoriesActivity.fiche = appareil; CategoriesActivity.cats = categories
        ecran("categories", Intent(ctx, CategoriesActivity::class.java), defiler = true)
        // HA 2.5.1 : une catégorie élément par élément (accordéon déplié, sites en attente, sites sans logiciel), la
        // recherche, le choix du mode et les blocages récents ; puis l'écran « Fermés » du Demandeur (demande groupée).
        CategoriesActivity.elements = true
        ecran("categories-elements", Intent(ctx, CategoriesActivity::class.java), defiler = true)
        CategoriesActivity.elements = false
        CategorieActivity.pc = "essai_tablette"; CategorieActivity.prenom = "Maximilian-Alexander"; CategorieActivity.horsLigne = true
        CategorieActivity.etiquette = "Réseaux sociaux"
        ecran("categorie-elements", Intent(ctx, CategorieActivity::class.java), defiler = true, preparer = { a ->
            champ(a, "detail", JSONObject(DETAIL_CATEGORIE)); champ(a, "erreur", "")
            (lire(a, "deplies") as java.util.HashSet<String>).add("com.discord.application.extraordinaire")
            appeler(a, "dessiner")
        })
        ecran("categorie-recherche", Intent(ctx, CategorieActivity::class.java), defiler = true, preparer = { a ->
            champ(a, "recherche", "discord"); champ(a, "resultats", JSONArray(RESULTATS_RECHERCHE)); champ(a, "erreur", "")
            appeler(a, "dessiner")
        })
        ecran("categorie-choix", Intent(ctx, CategorieActivity::class.java), preparer = { a ->
            champ(a, "detail", JSONObject(DETAIL_CATEGORIE)); champ(a, "erreur", ""); appeler(a, "dessiner")
        }, boite = { a ->
            val m = a.javaClass.declaredMethods.first { it.name == "choixMode" }
            m.isAccessible = true
            m.invoke(a, JSONObject().put("nom", "discordcdn-medias-utilisateurs.com").put("mode", "exception"), "sites", "discordcdn-medias-utilisateurs.com")
            null
        })
        CategorieActivity.etiquette = ""
        ecran("categorie-recents", Intent(ctx, CategorieActivity::class.java), defiler = true, preparer = { a ->
            champ(a, "recents", JSONArray(RECENTS)); champ(a, "erreur", ""); appeler(a, "dessiner")
        })
        CategorieActivity.horsLigne = false
        Acces.recents = JSONArray(RECENTS)
        Acces.groupes = JSONArray("""[{"logiciel":"com.roblox.client","libelle":"Roblox : jeux de construction en ligne","ouvert":false,
            "sites":["rbxcdn.com","rbxinfra.com","roblox-contenus-telechargeables-de-la-communaute.com"]}]""")
        ecran("fermes-recents", Intent(ctx, FermesActivity::class.java), defiler = true)
        Acces.recents = JSONArray(); Acces.groupes = JSONArray()
        ExceptionsActivity.fiche = appareil; ExceptionsActivity.cats = categories
        ecran("autorisations", Intent(ctx, ExceptionsActivity::class.java), defiler = true)
        // Refonte lot 3 : la fiche d'une personne (réponse d'essai : le faux HA ne répond que {"ok":true} à /parent).
        FichePersonneActivity.donneesEssai = FICHE_ESSAI
        try {
            ecran("fiche-personne", FichePersonneActivity.intention(ctx, "person.maximilian",
                listOf("person.maximilian" to "Maximilian-Alexander von Hohenzollern", "person.lou" to "Lou")), defiler = true)
        } finally { FichePersonneActivity.donneesEssai = null }
        ecran("gestion-appareil-menu", Intent(ctx, SetupActivity::class.java), boite = { a -> GestionAppareil.menu(a, appareil, JSONArray()) {}; null })
        ecran("gestion-appareil-plage", Intent(ctx, SetupActivity::class.java), boite = { a -> GestionAppareil.nouvellePlage(a, "essai_tablette") {}; null })

        // --- Niveau sonore (module coupé par défaut) : allumé le temps de ces écrans — la tuile de l'appareil mesuré
        // (téléphone), l'explication de vie privée, puis la ligne des Paramètres de la télé (reglages-tv ci-dessous).
        val modulesAvantSon = Config(ctx).modules
        Modules.memoriser(ctx, JSONObject().put("modules", JSONArray((Modules.effectifs(Modules.actifs(ctx)) + Modules.SON).toList())))
        NiveauSonore.releve(ctx, JSONObject().put("actif", true).put("seuil_db", 75).put("sensibilite_s", 5).put("plages", JSONArray())
            .put("calibration_db", 0).put("alertes", 0).put("alertes_avant_coupure", 3))
        if (!estTele) {
            Etat.parent = false
            ecran("niveau-sonore-demandeur", Intent(ctx, SetupActivity::class.java), defiler = true)
            Etat.parent = true
        }
        ecran("niveau-sonore-vie-privee", Intent(ctx, SetupActivity::class.java), boite = { a -> NiveauSonore.ouvrir(a); null })
        // --- Réglages TV et chacune de ses sections ; Couleurs.
        if (voulu("reglages-tv")) {
            val a = ouvrir(EcransReglages.reglages(ctx))
            verifierEtCapturer(a, "reglages-tv", null, defiler = true)
            val rail = try { lire(a, "rail") as? ViewGroup } catch (_: Throwable) { null }
            val sections = ArrayList<String>()
            if (rail != null) instrumentation.runOnMainSync {
                for (i in 0 until rail.childCount) (rail.getChildAt(i).tag as? String)?.takeIf { it.startsWith("r_") }?.let { sections.add(it) }
            }
            // Client Sendspin natif : la page Décideurs ouverte (interrupteur « Lecteur de musique »), sans saisir le code ;
            // avant les autres sections (la dernière, Soutien, laisse sa boîte ouverte), puis la garde est refermée.
            if (rail != null && sections.contains("r_decideurs")) {
                champ(a, "decideursJusqua", SystemClock.elapsedRealtime() + 300_000L)
                instrumentation.runOnMainSync { rail.findViewWithTag<View>("r_decideurs")?.let { v -> v.requestFocus(); v.performClick() } }
                instrumentation.waitForIdleSync(); SystemClock.sleep(600)
                verifierEtCapturer(a, "reglages-tv-decideurs-ouvert", null, defiler = true)
                champ(a, "decideursJusqua", 0L)
            }
            for (s in sections) {
                instrumentation.runOnMainSync { rail!!.findViewWithTag<View>(s)?.let { v -> v.requestFocus(); v.performClick() } }
                instrumentation.waitForIdleSync(); SystemClock.sleep(600)
                verifierEtCapturer(a, "reglages-tv-${s.removePrefix("r_")}", null, defiler = true)
            }
            fermer(a)
        }
        NiveauSonore.releve(ctx, JSONObject().put("actif", false))
        Config(ctx).modules = modulesAvantSon
        // Rien de lu avant (null : tout actif) : pas de champ « modules », jamais une liste vide (tout coupé).
        Modules.memoriser(ctx, if (modulesAvantSon == null) JSONObject() else JSONObject().put("modules", JSONArray(modulesAvantSon.split(',').filter { it.isNotEmpty() })))
        ecran("couleurs", Intent(ctx, CouleursActivity::class.java), defiler = true)
        // Client Sendspin natif : l'appairage avec Music Assistant (PIN à l'écran ; autorisation à la télécommande).
        ecran("appairage-pin", Intent(ctx, AppairageSendspinActivity::class.java).putExtra("pin", "483920"))
        ecran("appairage-geste", Intent(ctx, AppairageSendspinActivity::class.java).putExtra("geste", true))

        // --- Écran de blocage : appareil fermé, puis appli fermée ; et la demande d'accès qu'il ouvre.
        Etat.verrouille = true; Etat.message = "Fermé par le planning du soir : l'écran se rallume demain à sept heures"
        Etat.prochainChangement = System.currentTimeMillis() + 10 * 3600_000L
        ecran("blocage-appareil", Intent(ctx, BlockActivity::class.java).putExtra("raison", Etat.message).putExtra("pkg", ""))
        // Lot 6 (APP-33) : le relevé ne dit rien, la raison passée à l'écran est gardée (« Fermé par : …, jusqu'à … »).
        Etat.message = ""
        ecran("blocage-raison", Intent(ctx, BlockActivity::class.java).putExtra("raison", "Fermé par le planning du soir : l'écran se rallume demain à sept heures").putExtra("pkg", ""))
        Etat.verrouille = false; Etat.prochainChangement = 0
        val pkg = "com.exemple.application.de.dessin.extraordinaire"
        Etat.apps = setOf(pkg); Etat.raisons = mapOf(pkg to "plage|Fermée en dehors des plages de l'agenda du soir")
        Usage.dernierPaquet = pkg
        ecran("blocage-appli", Intent(ctx, BlockActivity::class.java).putExtra("raison", "Cette appli est bloquée").putExtra("pkg", pkg))
        ecran("demande-acces-appli", Intent(ctx, SetupActivity::class.java), boite = { a ->
            Acces.dialogue(a, "apps", pkg, "Application de dessin extraordinaire (édition scolaire)").also { if (!it.isShowing) it.show() }
        })
        ecran("demande-acces-site", Intent(ctx, SetupActivity::class.java), boite = { a ->
            Acces.dialogue(a, "sites", "unterrichtsmaterialien-grundschule-nordrhein-westfalen.de", "unterrichtsmaterialien-grundschule-nordrhein-westfalen.de",
                "https://unterrichtsmaterialien-grundschule-nordrhein-westfalen.de/arbeitsblaetter").also { if (!it.isShowing) it.show() }
        })
        ecran("demande-temps-decideur", Intent(ctx, SetupActivity::class.java), boite = { a ->
            Temps.choisir(a, a.getString(R.string.garde_accorder_du_temps), System.currentTimeMillis() + 3 * 3600_000L) {}; null
        })
        ecran("deverrouillage", Intent(ctx, DeverrouillageActivity::class.java))
        ecran("deverrouillage-decideur", Intent(ctx, DeverrouillageParentActivity::class.java))
        Etat.apps = emptySet(); Etat.raisons = emptyMap()

        // --- Party, choix de la musique, envoi d'un message (le faux HA local répond).
        ecran("party", Intent(ctx, PartyActivity::class.java), attente = 2500, defiler = true)
        partyGroupe = true
        ecran("party-groupe", Intent(ctx, PartyActivity::class.java), attente = 2500, defiler = true)
        ecran("party-volumes-enceintes", Intent(ctx, PartyActivity::class.java), attente = 2500, boite = { a ->
            val m = Party.analyser(etatParty()).maintenant
            a.javaClass.getDeclaredMethod("volumesDesEnceintes", Party.Maintenant::class.java).apply { isAccessible = true }.invoke(a, m); null
        })
        partyGroupe = false
        ecran("choix-musique", Intent(ctx, ChoixMusiqueActivity::class.java).putExtra("lecteur", "media_player.salon")
            .putExtra("lecteur_nom", "Wohnzimmer-Lautsprecher (Sonos Era 300, Südseite)").putExtra("joue", true), attente = 2500, defiler = true)
        ecran("message-envoi", Intent(ctx, MessageEnvoiActivity::class.java), attente = 2500, defiler = true)

        // --- 1.88.0 : widgets Home Assistant interactifs (détail et « +N », réglage, liste, bouton à confirmer), et
        // l'éditeur (section « Affichage »). Un catalogue d'essai, aux noms longs, sans Home Assistant.
        WidgetsHaStock.garderCatalogue(ctx, CATALOGUE_WIDGETS)
        if (WidgetsHaStock.catalogue(ctx).size < 4) defauts += "widgets : catalogue d'essai illisible (JSON)"
        ecran("widgets-ha", Intent(ctx, EditeurWidgetHaActivity::class.java), defiler = true, preparer = { a -> a.setContentView(plancheWidgets(a)) })
        // 16-10 (l'auteur, finitions) : thermostat « Consigne » et courbe de température, de 1 × 1 à 4 × 2.
        ecran("widgets-chauffage", Intent(ctx, EditeurWidgetHaActivity::class.java), defiler = true, preparer = { a -> a.setContentView(plancheChauffage(a)) },
            boite = { a -> verifierChauffage(a); null })
        // 1.90.1 : le widget d'écran d'accueil dont le détail défile (haut de tuile en image, lignes en liste).
        // (pas sur la télé : Android TV n'a pas de widgets d'écran d'accueil)
        if (!ReglagesTvActivity.estTele(ctx))
            ecran("widget-accueil-liste", Intent(ctx, EditeurWidgetHaActivity::class.java), preparer = { a -> a.setContentView(plancheListe(a)) })
        WidgetsHaStock.ecrire(ctx, WidgetsHa.Reglage("essai-detail", listOf("todo.devoirs"), WidgetsHa.TUILE, 2, 2, affichage = WidgetsHa.AFF_VALEUR_DETAIL))
        ecran("editeur-widget", Intent(ctx, EditeurWidgetHaActivity::class.java).putExtra(EditeurWidgetHaActivity.EXTRA_ID, "essai-detail"), attente = 2500, defiler = true)
        // 1.90.0 : « Au toucher » › tableau de bord, avec un chemin long et la liste des vues.
        WidgetsHaStock.ecrire(ctx, WidgetsHa.Reglage("essai-vue", listOf("todo.devoirs"), WidgetsHa.TUILE, 2, 2, toucher = WidgetsHa.TOUCHER_VUE,
            chemin = "/tableau-maison/energie-et-chauffage-du-sejour"))
        ecran("editeur-widget-toucher", Intent(ctx, EditeurWidgetHaActivity::class.java).putExtra(EditeurWidgetHaActivity.EXTRA_ID, "essai-vue"), attente = 2500, defiler = true)
        // Réveil (lots 4 et 5) : l'écran qui sonne, la liste « Réveils » (Décideur) et l'édition, noms longs.
        ecran("reveil-sonnerie", Intent(ctx, ReveilActivity::class.java).putExtra("essai", true)
            .putExtra("nom", "Schulwecker für $LOU (Montag bis Freitag)").putExtra("rep_min", 9).putExtra("repeter", true))
        ReveilsActivity.essai = reveilsEssai()
        try {
            ecran("reveils", Intent(ctx, ReveilsActivity::class.java), defiler = true)
            ReveilsActivity.forcerAlertes = true
            try { ecran("reveils-alertes", Intent(ctx, ReveilsActivity::class.java), defiler = true) } finally { ReveilsActivity.forcerAlertes = false }
            ecran("reveil-edition", Intent(ctx, ReveilsActivity::class.java), defiler = true, preparer = { a ->
                champ(a, "edition", reveilsEssai().getJSONArray("reveils").getJSONObject(0)); appeler(a, "dessiner")
            })
            ecran("reveil-nouveau", Intent(ctx, ReveilsActivity::class.java), defiler = true, preparer = { a ->
                champ(a, "edition", JSONObject()); appeler(a, "dessiner")
            })
        } finally { ReveilsActivity.essai = null }
    }

    /** La réponse « liste » d'essai de /api/pc_parental/reveils : deux réveils aux noms longs, droits de Décideur. */
    private fun reveilsEssai(): JSONObject {
        val peut = JSONObject().put("voir", true).put("modifier", true).put("activer", true).put("desactiver", true)
            .put("sauter", true).put("reprendre", true).put("supprimer", true).put("sonner", true).put("reporter", true).put("arreter", true)
        val appareils = JSONArray().put(JSONObject().put("id", "e1").put("nom", "TELEFON-MAXIMILIAN-KINDERZIMMER")).put(JSONObject().put("id", "t1").put("nom", TELE))
        return JSONObject().put("ok", true).put("moi", "person.anne")
            .put("droits", JSONObject().put("gerer", true).put("propres", false))
            .put("noms", JSONObject().put("person.lou", LOU).put("person.anne", "Anne-Charlotte de Montmorency-Laval"))
            .put("demandeurs", JSONArray().put(JSONObject().put("personne", "person.lou").put("appareils", appareils))
                .put(JSONObject().put("personne", "person.anne").put("appareils", JSONArray())))
            .put("reveils", JSONArray()
                .put(JSONObject().put("id", "rv1").put("nom", "Schulwecker mit Radio für die ganze Woche").put("demandeur", "person.lou").put("actif", true)
                    .put("prochain", "2026-10-12T06:55:00+02:00").put("sautees", JSONArray()).put("automatisation", true).put("impose", true)
                    .put("declenchement", JSONObject().put("type", "hebdo").put("heure", "06:55").put("jours", JSONArray(listOf(0, 1, 2, 3, 4))))
                    .put("source", JSONObject().put("type", "url").put("uri", "https://radio.example/ein-sehr-langer-stream-name.mp3"))
                    .put("appareils_choisis", JSONArray().put("e1")).put("peut", peut))
                .put(JSONObject().put("id", "rv2").put("nom", "Pronote").put("demandeur", "person.lou").put("actif", false)
                    .put("prochain", JSONObject.NULL).put("sautees", JSONArray().put("2026-10-13")).put("automatisation", false)
                    .put("declenchement", JSONObject().put("type", "entite")).put("source", JSONObject().put("type", "sonnerie"))
                    .put("appareils_choisis", JSONArray()).put("peut", peut)))
    }

    // ------------------------------------------------------------------ Un écran

    private fun ouvrir(intent: Intent, attente: Long = 1200): Activity {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val a = instrumentation.startActivitySync(intent)
        instrumentation.waitForIdleSync(); SystemClock.sleep(attente)
        return a
    }

    private fun fermer(a: Activity) {
        instrumentation.runOnMainSync { a.finish() }
        racinesCapture = emptyList()   // ne garde pas l'écran fermé (et ses images) en mémoire jusqu'au suivant
        instrumentation.waitForIdleSync(); SystemClock.sleep(400)
    }

    private fun ecran(nom: String, intent: Intent, preparer: ((Activity) -> Unit)? = null, boite: ((Activity) -> Dialog?)? = null,
                      defiler: Boolean = false, attente: Long = 1200) {
        if (!voulu(nom)) return
        val a = try { ouvrir(intent, attente) } catch (e: Throwable) { defauts += "$nom : l'écran ne s'ouvre pas (${e.javaClass.simpleName} : ${e.message})"; return }
        try {
            if (preparer != null) { instrumentation.runOnMainSync { preparer(a) }; instrumentation.waitForIdleSync(); SystemClock.sleep(600) }
            var d: Dialog? = null
            if (boite != null) { instrumentation.runOnMainSync { d = boite(a) }; instrumentation.waitForIdleSync(); SystemClock.sleep(800) }
            verifierEtCapturer(a, nom, d, defiler)
            instrumentation.runOnMainSync { try { d?.dismiss() } catch (_: Exception) {} }
        } catch (e: Throwable) {
            defauts += "$nom : erreur du parcours (${e.javaClass.simpleName} : ${e.message})"
        } finally { fermer(a) }
    }

    private fun verifierEtCapturer(a: Activity, nom: String, d: Dialog?, defiler: Boolean) {
        paysageVoulu()?.let { voulu ->
            val m = a.resources.displayMetrics
            if ((m.widthPixels > m.heightPixels) != voulu) defauts += "$nom : capture dans le mauvais sens (${m.widthPixels}x${m.heightPixels}) — rotation non appliquée"
        }
        instrumentation.runOnMainSync { racines(a, d).forEach { verifierFenetre(it, nom); verifierChevauchements(it, nom) }; racinesCapture = racines(a, d) }
        capturer("$nom")
        if (!defiler) return
        for (page in 2..6) {
            var bouge = false
            instrumentation.runOnMainSync {
                val sv = defileur(a.window.decorView)
                if (sv != null) { val avant = sv.scrollY; sv.scrollBy(0, (sv.height * 0.85f).toInt()); bouge = sv.scrollY != avant }
            }
            if (!bouge) break
            instrumentation.waitForIdleSync(); SystemClock.sleep(400)
            instrumentation.runOnMainSync { racines(a, d).forEach { verifierFenetre(it, "$nom (cran $page)") } }
            capturer("$nom-$page")
        }
    }

    /** Toutes les fenêtres de l'appli posées sur cette activité (l'écran et ses boîtes), sinon l'écran et la boîte connue. */
    @Suppress("UNCHECKED_CAST")
    private fun racines(a: Activity, d: Dialog?): List<View> {
        val connues = listOfNotNull(a.window.decorView, d?.window?.decorView)
        return try {
            val wmg = Class.forName("android.view.WindowManagerGlobal")
            val g = wmg.getMethod("getInstance").invoke(null)
            val vues = wmg.getDeclaredField("mViews").apply { isAccessible = true }.get(g) as List<View>
            (connues + vues.filter { v -> v.context.let { c -> c === a || (c as? android.content.ContextWrapper)?.baseContext === a } }).distinct()
        } catch (_: Throwable) { connues }
    }

    /** Le premier bloc qui défile verticalement (ScrollView, liste…), pour descendre dans les longues pages. */
    private fun defileur(v: View): View? {
        if (v.isShown && (v.canScrollVertically(1) || v.canScrollVertically(-1)) && v.height > 0) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) defileur(v.getChildAt(i))?.let { return it }
        return null
    }

    /** Les fenêtres de l'écran en cours (pour le repli de [capturer]). */
    private var racinesCapture: List<View> = emptyList()

    /** Une image toute noire (l'émulateur ne compose plus l'affichage, par exemple quand l'écran du PC dort). */
    private fun noire(b: Bitmap): Boolean {
        for (y in 0 until b.height step maxOf(1, b.height / 24)) for (x in 0 until b.width step maxOf(1, b.width / 24))
            if (b.getPixel(x, y) and 0x00FFFFFF != 0) return false
        return true
    }

    /** 1.90.5 : repli — l'appli dessine elle-même ses fenêtres (rendu logiciel), à leur place sur l'écran. */
    private fun dessinerFenetres(): Bitmap? {
        val m = ctx.resources.displayMetrics
        var b: Bitmap? = null
        instrumentation.runOnMainSync {
            if (racinesCapture.isEmpty()) return@runOnMainSync
            val img = Bitmap.createBitmap(m.widthPixels, m.heightPixels, Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(img)
            c.drawColor(android.graphics.Color.BLACK)
            for (r in racinesCapture) {
                val pos = IntArray(2); r.getLocationOnScreen(pos)
                c.save(); c.translate(pos[0].toFloat(), pos[1].toFloat()); r.draw(c); c.restore()
            }
            b = img
        }
        return b
    }

    private fun capturer(nom: String) {
        var b: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        if (noire(b)) b = dessinerFenetres() ?: b
        // Au plus 1280 px au plus grand côté : lisible, et léger pour un disque presque plein.
        val k = 1280f / maxOf(b.width, b.height)
        val img = if (k < 1f) Bitmap.createScaledBitmap(b, (b.width * k).toInt(), (b.height * k).toInt(), true) else b
        File(dossier, "recette-lot1-android-$nom-$taille-$police.png".replace(' ', '_').replace("(", "").replace(")", ""))
            .outputStream().use { img.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // 16-10 : en 1080p, une capture pèse 8 Mo ; sans les rendre tout de suite, une longue passe finissait par tuer
        // le processus de l'appli (« Process crashed »).
        if (img !== b) img.recycle()
        b.recycle()
    }

    // ------------------------------------------------------------------ Vérification

    private fun dp(x: Float) = x * ctx.resources.displayMetrics.density

    private fun defileEnLargeur(v: View): Boolean {
        var p = v.parent
        while (p is View) { if (p.canScrollHorizontally(1) || p.canScrollHorizontally(-1) || p is android.widget.HorizontalScrollView) return true; p = p.parent }
        return false
    }

    private fun defileEnHauteur(v: View): Boolean {
        var p = v.parent
        while (p is View) { if (p.canScrollVertically(1) || p.canScrollVertically(-1) || p is android.widget.ScrollView || p is android.widget.AbsListView) return true; p = p.parent }
        return false
    }

    /** Étiquette d'icône admise : au plus 2 lignes, nom complet sur la case (contentDescription). */
    private fun etiquetteIcone(tv: TextView): Boolean {
        if (tv.maxLines != 2) return false
        var p: Any? = tv.parent; var n = 0
        while (p is View && n < 3) { if (!p.contentDescription.isNullOrEmpty()) return true; p = p.parent; n++ }
        return false
    }

    /**
     * Écrans Musique (décision du projet, 1.86.4 à 1.86.13, exception de NonTroncatureTest) : titres, artistes et enceintes
     * sur une ou deux lignes avec « … » pour garder la file lisible à la télécommande ; aussi dans le mini-lecteur de
     * l'accueil (exception « MiniLecteur.kt » de NonTroncatureTest).
     */
    private fun ligneMusique(tv: TextView, ecran: String): Boolean {
        if (tv.maxLines !in 1..2) return false
        if (!(ecran.startsWith("party") || ecran.startsWith("choix-musique") || dansMiniLecteur(tv))) return false
        // TalkBack lit le texte entier d'un TextView ellipsé (l'ellipse n'est que visuelle) ; une description posée suffit aussi.
        return true
    }

    /**
     * « … » admis quand le texte entier est dit en description (règle de la recette 1.87.0) : nom venu de Home
     * Assistant, ligne du détail d'un widget (« • » en tête), choix d'une liste (« ▾ » au bout).
     */
    private fun texteEntierDit(tv: TextView): Boolean {
        val d = tv.contentDescription?.toString().orEmpty()
        val t = tv.text.toString().removePrefix("• ").removeSuffix("▾").trim()
        return d.isNotEmpty() && t.isNotEmpty() && d.contains(t)
    }

    private fun dansMiniLecteur(v: View): Boolean {
        var p: Any? = v.parent
        while (p is View) { if (p.tag == "mini_lecteur") return true; p = p.parent }
        return false
    }


    private fun interactive(v: View): Boolean =
        (v.isClickable || v.isLongClickable || v is EditText || v is android.widget.CompoundButton || v is android.widget.SeekBar || v is android.widget.Spinner
            || (v.isFocusable && v !is ViewGroup)) && v.isEnabled

    /**
     * 1.90.1 (l'auteur, accueil d'un Pixel en paysage : trois icônes empilées par case) : dans chaque grille de l'accueil,
     * deux cases ne se recouvrent jamais, et le contenu d'une case (icône, nom) tient dans sa hauteur. Une case
     * plus basse que son contenu déborde sur la suivante : c'était l'empilement.
     */
    private fun verifierChevauchements(racine: View, ecran: String) {
        val grilles = ArrayList<GrilleAccueil>()
        fun chercher(v: View) { if (v is GrilleAccueil) grilles += v; if (v is ViewGroup) for (i in 0 until v.childCount) chercher(v.getChildAt(i)) }
        chercher(racine)
        for (g in grilles) {
            val cases = (0 until g.childCount).map { g.getChildAt(it) }.filter { it.visibility == View.VISIBLE && it.width > 0 && it.height > 0 }
            for (i in cases.indices) for (j in i + 1 until cases.size) {
                val a = Rect(cases[i].left, cases[i].top, cases[i].right, cases[i].bottom)
                val b = Rect(cases[j].left, cases[j].top, cases[j].right, cases[j].bottom)
                if (Rect.intersects(a, b) && Rect(a).apply { intersect(b) }.let { it.width() > 2 && it.height() > 2 })
                    defauts += "$ecran · ${decrire(cases[i])} et ${decrire(cases[j])} : cases qui se chevauchent"
            }
            for (c in cases) {
                val contenu = c as? ViewGroup ?: continue
                val bas = (0 until contenu.childCount).map { contenu.getChildAt(it) }.filter { it.visibility != View.GONE }.maxOfOrNull { it.bottom } ?: continue
                c.measure(View.MeasureSpec.makeMeasureSpec(c.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                val voulu = c.measuredHeight
                c.measure(View.MeasureSpec.makeMeasureSpec(c.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(c.height, View.MeasureSpec.EXACTLY))
                if (bas > c.height + 2 || voulu > c.height + 2)
                    defauts += "$ecran · ${decrire(c)} : contenu plus haut que sa case (${maxOf(bas, voulu)} px pour ${c.height} px), il déborde sur la case suivante"
            }
        }
    }

    private fun verifierFenetre(racine: View, ecran: String) {
        val cadre = Rect()
        racine.getWindowVisibleDisplayFrame(cadre)  // l'écran utile : sans les barres système
        if (cadre.isEmpty) cadre.set(0, 0, ctx.resources.displayMetrics.widthPixels, ctx.resources.displayMetrics.heightPixels)
        // 1.90.5 (retour d'essai) : l'encoche de la caméra (« rien sous l'encoche ») ; vide sans encoche ou avant Android 9.
        encoches = if (android.os.Build.VERSION.SDK_INT >= 28) racine.rootWindowInsets?.displayCutout?.boundingRects.orEmpty() else emptyList()
        verifier(racine, ecran, cadre)
    }

    private fun decrire(v: View): String {
        val nom = (v.tag as? String)?.let { "[$it]" } ?: try { if (v.id != View.NO_ID) "#" + v.resources.getResourceEntryName(v.id) else "" } catch (_: Exception) { "" }
        val texte = (v as? TextView)?.text?.toString()?.replace('\n', ' ')?.take(60) ?: v.contentDescription?.toString()?.take(60).orEmpty()
        return "${v.javaClass.simpleName}$nom" + if (texte.isNotEmpty()) " « $texte »" else ""
    }

    private var encoches: List<Rect> = emptyList()

    private fun verifier(v: View, ecran: String, cadre: Rect) {
        if (!v.isShown || v.alpha == 0f || v.width <= 0 || v.height <= 0) return
        // Les fonds des barres du système (navigation à trois boutons) appartiennent à Android, pas à l'écran.
        if (v.id == android.R.id.navigationBarBackground || v.id == android.R.id.statusBarBackground) return
        val ou = "$ecran · ${decrire(v)}"
        val pos = IntArray(2).also { v.getLocationOnScreen(it) }
        val r = Rect(pos[0], pos[1], pos[0] + v.width, pos[1] + v.height)
        val enLargeur = defileEnLargeur(v)
        val enHauteur = defileEnHauteur(v)
        val marge = dp(8f)
        // Un fond ou un voile plein écran (voile du tiroir, cliquable pour fermer) n'a pas de marge à respecter.
        val plein = v.width >= cadre.width() * 0.9f && v.height >= cadre.height() * 0.9f

        // 1. Texte : ellipse, lignes cachées, hauteur trop courte, mot coupé en deux.
        if (v is TextView && v !is EditText && v.text.isNotEmpty()) {
            val l = v.layout
            if (l != null) {
                val ellipse = (0 until l.lineCount).any { l.getEllipsisCount(it) > 0 }
                if (ellipse && !etiquetteIcone(v) && !ligneMusique(v, ecran) && !texteEntierDit(v)) defauts += "$ou : texte ellipsé (…)"
                val visibles = minOf(l.lineCount, v.maxLines.takeIf { it > 0 } ?: Int.MAX_VALUE)
                if (visibles < l.lineCount && !etiquetteIcone(v)) defauts += "$ou : lignes coupées (${l.lineCount} nécessaires, $visibles affichées)"
                val utile = v.height - v.totalPaddingTop - v.totalPaddingBottom
                if (!ellipse && visibles > 0 && l.getLineBottom(visibles - 1) > utile + 2)
                    defauts += "$ou : texte coupé en bas (${l.getLineBottom(visibles - 1)} px de lignes pour $utile px)"
                val t = v.text
                for (i in 0 until l.lineCount - 1) {
                    val fin = l.getLineEnd(i)
                    if (fin in 1 until t.length && t[fin - 1].isLetter() && t[fin].isLetter()) { defauts += "$ou : mot coupé en deux (ligne ${i + 1})"; break }
                }
                // 5a. Marge : l'étendue réelle des lignes (un texte centré dans une vue pleine largeur n'est pas collé au bord).
                if (!interactive(v) && l.lineCount > 0) {
                    val g = r.left + v.totalPaddingLeft - v.scrollX + (0 until visibles).minOf { l.getLineLeft(it) }.toInt()
                    val d = r.left + v.totalPaddingLeft - v.scrollX + (0 until visibles).maxOf { l.getLineRight(it) }.toInt()
                    if (!enLargeur && (g - cadre.left < marge - 0.5f || cadre.right - d < marge - 0.5f))
                        defauts += "$ou : texte à ${minOf(g - cadre.left, cadre.right - d)} px (< 8 dp = ${marge.toInt()} px) du bord de l'écran"
                    if (!enHauteur && (r.top - cadre.top < marge - 0.5f || cadre.bottom - r.bottom < marge - 0.5f) && !plein)
                        defauts += "$ou : texte à moins de 8 dp du haut ou du bas de l'écran"
                }
            }
        }
        // 2. Hors du parent (sauf dans un parent qui défile dans ce sens ; vues posées en absolu : FrameLayout compris).
        val parent = v.parent as? ViewGroup
        if (parent != null && !plein) {
            // 1.91.0 : une rangée bord à bord (BordABord) sort exprès de sa colonne jusqu'aux bords de l'écran ; le point 3
            // vérifie qu'elle reste dans l'écran.
            if (!enLargeur && v.tag != BordABord.TAG && (v.left < -2 || v.right > parent.width + 2))
                defauts += "$ou : sort de son parent ${parent.javaClass.simpleName} (${v.left}..${v.right} sur ${parent.width} px)"
            if (!enHauteur && (v.top < -2 || v.bottom > parent.height + 2))
                defauts += "$ou : sort de son parent ${parent.javaClass.simpleName} en hauteur (${v.top}..${v.bottom} sur ${parent.height} px)"
        }
        // 3 bis. Sous l'encoche de la caméra : un contenu (texte, image, contrôle), pas un fond ; à l'écran au repos (un
        // contenu qui défile passe normalement sous le haut de l'écran).
        val contenu = v !is ViewGroup || interactive(v)
        val auRepos = !enHauteur && "(cran " !in ecran
        if (!plein && contenu && auRepos && encoches.any { Rect.intersects(it, r) }) defauts += "$ou : sous l'encoche de la caméra ($r)"
        // 3. Hors de l'écran utile.
        if (!plein) {
            if (!enLargeur && (r.left < cadre.left - 1 || r.right > cadre.right + 1)) defauts += "$ou : sort de l'écran (${r.left}..${r.right}, écran ${cadre.left}..${cadre.right})"
            if (!enHauteur && (r.top < cadre.top - 1 || r.bottom > cadre.bottom + 1)) defauts += "$ou : sort de l'écran en hauteur (${r.top}..${r.bottom}, écran ${cadre.top}..${cadre.bottom})"
        }
        // 5b. Marge des vues interactives : leur boîte entière.
        if (interactive(v) && !plein) {
            if (!enLargeur && (r.left - cadre.left < marge - 0.5f || cadre.right - r.right < marge - 0.5f))
                defauts += "$ou : contrôle à ${minOf(r.left - cadre.left, cadre.right - r.right)} px (< 8 dp = ${marge.toInt()} px) du bord de l'écran"
            if (!enHauteur && (r.top - cadre.top < marge - 0.5f || cadre.bottom - r.bottom < marge - 0.5f))
                defauts += "$ou : contrôle à moins de 8 dp du haut ou du bas de l'écran"
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) verifier(v.getChildAt(i), ecran, cadre)
    }

    // ------------------------------------------------------------------ Réflexion (états privés des écrans)

    private fun champ(o: Any, nom: String, valeur: Any) {
        var c: Class<*>? = o.javaClass
        while (c != null) {
            try { c.getDeclaredField(nom).apply { isAccessible = true }.set(o, valeur); return } catch (_: NoSuchFieldException) { c = c.superclass }
        }
        throw AssertionError("Champ $nom introuvable sur ${o.javaClass.simpleName}")
    }

    private fun lire(o: Any, nom: String): Any? {
        var c: Class<*>? = o.javaClass
        while (c != null) {
            try { return c.getDeclaredField(nom).apply { isAccessible = true }.get(o) } catch (_: NoSuchFieldException) { c = c.superclass }
        }
        throw AssertionError("Champ $nom introuvable sur ${o.javaClass.simpleName}")
    }

    private fun appeler(o: Any, nom: String) {
        val m = o.javaClass.declaredMethods.firstOrNull { it.name == nom && it.parameterTypes.isEmpty() }
            ?: throw AssertionError("Méthode $nom introuvable sur ${o.javaClass.simpleName}")
        m.isAccessible = true; m.invoke(o)
    }
}
