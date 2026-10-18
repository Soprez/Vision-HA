/*
 * Vision — carte « Processus »
 *
 * Ce qui tourne sur les PC et ce qui s'y lance tout seul. Le classeur (la
 * tâche planifiée de Claude) propose ; ici, on valide ou on corrige. Rien
 * ne change sur une machine avant un clic dans cette carte.
 */
// ---- i18n:debut — les textes de cette carte, dans les six langues de Vision (fr source, en repli) ----
const TEXTES = /*i18n-json*/{
  "fr": {
    "verdict_garder": "Garder", "verdict_hors_demarrage": "Retirer du démarrage", "verdict_nuisible": "Nuisible",
    "verdict_desinstaller": "Désinstaller",
    "retour_fait": "✔ désinstallé", "retour_echec": "✖ échec", "retour_impossible": "✖ impossible",
    "retour_detail": "{retour} : {detail}",
    "onglet_propositions": "À valider", "onglet_a_classer": "À classer", "onglet_decides": "Décidés",
    "onglet_logiciels": "Logiciels installés", "onglet_tous": "Tout",
    "genre_demarrage": "Démarrage", "genre_tache": "Tâche", "genre_service": "Service", "genre_processus": "Processus",
    "genre_logiciel": "Logiciel", "tous_genres": "Tous genres",
    "erreur_admin": "Réservé aux administrateurs de Home Assistant.",
    "erreur_loading": "Vision démarre : réessaie dans un instant.",
    "erreur_pc": "Cet appareil n'existe plus dans Vision.",
    "erreur_json": "La demande n'a pas pu être lue : réessaie.",
    "erreur_module": "Cette fonction est désactivée dans Vision.",
    "erreur_minutes": "Indique une durée.",
    "erreur_defaut": "Ce n'est pas passé : rien n'a changé, réessaie dans un instant.",
    "geste_en_cours": "En cours…",
    "geste_fait": "C'est fait.",
    "catalogue_indisponible": "Catalogue indisponible : {erreur}",
    "fait_elements": "C'est fait pour {n} éléments.",
    "echec_decider": "Ce n'est pas passé : rien n'a changé, réessaie.",
    "version": "version {version}",
    "signature": "Signature : {statut}", "aucune_signature": "Aucune signature", "non_signe": "non signé",
    "ram_mo": "{n} Mo", "applique": "appliqué", "inactif": "inactif",
    "desinstallation_demandee": "désinstallation demandée",
    "decide": "Décidé :",
    "valider": "Valider",
    "defaire": "Défaire : remettre à classer et restaurer",
    "confirmer_titre": "Désinstaller {nom} ?",
    "confirmer_texte": "Sur : {ou}. Vision lance le désinstalleur officiel du logiciel, sans rien demander à l'écran. <b>Cela ne se défait pas</b> : pour le retrouver, il faudra le réinstaller.",
    "aucun_appareil": "aucun appareil",
    "desinstaller_definitivement": "Désinstaller définitivement", "annuler": "Annuler",
    "intouchable": "Windows — intouchable",
    "titre": "Processus et démarrage",
    "chercher": "Chercher un nom, un éditeur…",
    "valider_selection": "Valider la sélection ({n})", "garder_selection": "Garder la sélection",
    "valider_surs": "Valider tout ≥ 80 % ({n})",
    "rien_ici": "Rien ici.", "chargement": "Chargement…",
    "carte_nom": "Vision — Processus",
    "carte_description": "Valider ce que le classeur propose pour les processus, le démarrage, les tâches et les services."
  },
  "en": {
    "verdict_garder": "Keep", "verdict_hors_demarrage": "Remove from startup", "verdict_nuisible": "Harmful",
    "verdict_desinstaller": "Uninstall",
    "retour_fait": "✔ uninstalled", "retour_echec": "✖ failed", "retour_impossible": "✖ not possible",
    "retour_detail": "{retour}: {detail}",
    "onglet_propositions": "To approve", "onglet_a_classer": "To sort", "onglet_decides": "Decided",
    "onglet_logiciels": "Installed programs", "onglet_tous": "All",
    "genre_demarrage": "Startup", "genre_tache": "Task", "genre_service": "Service", "genre_processus": "Process",
    "genre_logiciel": "Program", "tous_genres": "All kinds",
    "erreur_admin": "Only Home Assistant administrators can do this.",
    "erreur_loading": "Vision is starting: try again in a moment.",
    "erreur_pc": "This device no longer exists in Vision.",
    "erreur_json": "The request could not be read: try again.",
    "erreur_module": "This feature is turned off in Vision.",
    "erreur_minutes": "Enter a duration.",
    "erreur_defaut": "That didn't work: nothing changed, try again in a moment.",
    "geste_en_cours": "Working…",
    "geste_fait": "Done.",
    "catalogue_indisponible": "Catalogue unavailable: {erreur}",
    "fait_elements": "Done for {n} items.",
    "echec_decider": "That didn't work: nothing changed, try again.",
    "version": "version {version}",
    "signature": "Signature: {statut}", "aucune_signature": "No signature", "non_signe": "unsigned",
    "ram_mo": "{n} MB", "applique": "applied", "inactif": "inactive",
    "desinstallation_demandee": "uninstall requested",
    "decide": "Decided:",
    "valider": "Approve",
    "defaire": "Undo: put back to sort and restore",
    "confirmer_titre": "Uninstall {nom}?",
    "confirmer_texte": "On: {ou}. Vision runs the program's official uninstaller, without asking anything on screen. <b>This can't be undone</b>: to get it back, it will have to be reinstalled.",
    "aucun_appareil": "no device",
    "desinstaller_definitivement": "Uninstall for good", "annuler": "Cancel",
    "intouchable": "Windows — untouchable",
    "titre": "Processes and startup",
    "chercher": "Search for a name, a publisher…",
    "valider_selection": "Approve selection ({n})", "garder_selection": "Keep selection",
    "valider_surs": "Approve all ≥ 80% ({n})",
    "rien_ici": "Nothing here.", "chargement": "Loading…",
    "carte_nom": "Vision — Processes",
    "carte_description": "Approve what the sorter suggests for processes, startup, tasks and services."
  },
  "de": {
    "verdict_garder": "Behalten", "verdict_hors_demarrage": "Aus dem Autostart entfernen", "verdict_nuisible": "Schädlich",
    "verdict_desinstaller": "Deinstallieren",
    "retour_fait": "✔ deinstalliert", "retour_echec": "✖ fehlgeschlagen", "retour_impossible": "✖ nicht möglich",
    "retour_detail": "{retour}: {detail}",
    "onglet_propositions": "Zu bestätigen", "onglet_a_classer": "Einzuordnen", "onglet_decides": "Entschieden",
    "onglet_logiciels": "Installierte Programme", "onglet_tous": "Alle",
    "genre_demarrage": "Autostart", "genre_tache": "Aufgabe", "genre_service": "Dienst", "genre_processus": "Prozess",
    "genre_logiciel": "Programm", "tous_genres": "Alle Arten",
    "erreur_admin": "Nur für Home Assistant-Administratoren.",
    "erreur_loading": "Vision startet gerade: versuch es gleich noch einmal.",
    "erreur_pc": "Dieses Gerät gibt es in Vision nicht mehr.",
    "erreur_json": "Die Anfrage konnte nicht gelesen werden: versuch es noch einmal.",
    "erreur_module": "Diese Funktion ist in Vision ausgeschaltet.",
    "erreur_minutes": "Gib eine Dauer an.",
    "erreur_defaut": "Das hat nicht geklappt: nichts wurde geändert, versuch es gleich noch einmal.",
    "geste_en_cours": "Wird ausgeführt…",
    "geste_fait": "Erledigt.",
    "catalogue_indisponible": "Katalog nicht verfügbar: {erreur}",
    "fait_elements": "Erledigt für {n} Einträge.",
    "echec_decider": "Das hat nicht geklappt: nichts wurde geändert, versuch es noch einmal.",
    "version": "Version {version}",
    "signature": "Signatur: {statut}", "aucune_signature": "Keine Signatur", "non_signe": "nicht signiert",
    "ram_mo": "{n} MB", "applique": "angewendet", "inactif": "inaktiv",
    "desinstallation_demandee": "Deinstallation angefordert",
    "decide": "Entschieden:",
    "valider": "Bestätigen",
    "defaire": "Rückgängig: wieder einzuordnen und wiederherstellen",
    "confirmer_titre": "{nom} deinstallieren?",
    "confirmer_texte": "Auf: {ou}. Vision startet das offizielle Deinstallationsprogramm, ohne auf dem Bildschirm nachzufragen. <b>Das lässt sich nicht rückgängig machen</b>: um es wiederzubekommen, muss es neu installiert werden.",
    "aucun_appareil": "keinem Gerät",
    "desinstaller_definitivement": "Endgültig deinstallieren", "annuler": "Abbrechen",
    "intouchable": "Windows — unantastbar",
    "titre": "Prozesse und Autostart",
    "chercher": "Nach Name oder Hersteller suchen…",
    "valider_selection": "Auswahl bestätigen ({n})", "garder_selection": "Auswahl behalten",
    "valider_surs": "Alle ≥ 80 % bestätigen ({n})",
    "rien_ici": "Hier ist nichts.", "chargement": "Wird geladen…",
    "carte_nom": "Vision — Prozesse",
    "carte_description": "Bestätigen, was der Sortierer für Prozesse, Autostart, Aufgaben und Dienste vorschlägt."
  },
  "es": {
    "verdict_garder": "Mantener", "verdict_hors_demarrage": "Quitar del inicio", "verdict_nuisible": "Dañino",
    "verdict_desinstaller": "Desinstalar",
    "retour_fait": "✔ desinstalado", "retour_echec": "✖ error", "retour_impossible": "✖ imposible",
    "retour_detail": "{retour}: {detail}",
    "onglet_propositions": "Por validar", "onglet_a_classer": "Por clasificar", "onglet_decides": "Decididos",
    "onglet_logiciels": "Programas instalados", "onglet_tous": "Todo",
    "genre_demarrage": "Inicio", "genre_tache": "Tarea", "genre_service": "Servicio", "genre_processus": "Proceso",
    "genre_logiciel": "Programa", "tous_genres": "Todos los tipos",
    "erreur_admin": "Solo para administradores de Home Assistant.",
    "erreur_loading": "Vision se está iniciando: inténtalo de nuevo en un momento.",
    "erreur_pc": "Este dispositivo ya no existe en Vision.",
    "erreur_json": "No se pudo leer la solicitud: inténtalo de nuevo.",
    "erreur_module": "Esta función está desactivada en Vision.",
    "erreur_minutes": "Indica una duración.",
    "erreur_defaut": "No ha funcionado: no ha cambiado nada, inténtalo de nuevo en un momento.",
    "geste_en_cours": "En curso…",
    "geste_fait": "Hecho.",
    "catalogue_indisponible": "Catálogo no disponible: {erreur}",
    "fait_elements": "Hecho para {n} elementos.",
    "echec_decider": "No ha funcionado: no ha cambiado nada, inténtalo de nuevo.",
    "version": "versión {version}",
    "signature": "Firma: {statut}", "aucune_signature": "Sin firma", "non_signe": "sin firmar",
    "ram_mo": "{n} MB", "applique": "aplicado", "inactif": "inactivo",
    "desinstallation_demandee": "desinstalación solicitada",
    "decide": "Decidido:",
    "valider": "Validar",
    "defaire": "Deshacer: volver a clasificar y restaurar",
    "confirmer_titre": "¿Desinstalar {nom}?",
    "confirmer_texte": "En: {ou}. Vision ejecuta el desinstalador oficial del programa, sin preguntar nada en pantalla. <b>No se puede deshacer</b>: para recuperarlo, habrá que reinstalarlo.",
    "aucun_appareil": "ningún dispositivo",
    "desinstaller_definitivement": "Desinstalar definitivamente", "annuler": "Cancelar",
    "intouchable": "Windows — intocable",
    "titre": "Procesos e inicio",
    "chercher": "Buscar un nombre, un editor…",
    "valider_selection": "Validar la selección ({n})", "garder_selection": "Mantener la selección",
    "valider_surs": "Validar todo ≥ 80 % ({n})",
    "rien_ici": "Nada por aquí.", "chargement": "Cargando…",
    "carte_nom": "Vision — Procesos",
    "carte_description": "Validar lo que el clasificador propone para los procesos, el inicio, las tareas y los servicios."
  },
  "it": {
    "verdict_garder": "Mantenere", "verdict_hors_demarrage": "Togliere dall'avvio", "verdict_nuisible": "Dannoso",
    "verdict_desinstaller": "Disinstallare",
    "retour_fait": "✔ disinstallato", "retour_echec": "✖ non riuscito", "retour_impossible": "✖ impossibile",
    "retour_detail": "{retour}: {detail}",
    "onglet_propositions": "Da convalidare", "onglet_a_classer": "Da classificare", "onglet_decides": "Decisi",
    "onglet_logiciels": "Programmi installati", "onglet_tous": "Tutto",
    "genre_demarrage": "Avvio", "genre_tache": "Attività", "genre_service": "Servizio", "genre_processus": "Processo",
    "genre_logiciel": "Programma", "tous_genres": "Tutti i tipi",
    "erreur_admin": "Riservato agli amministratori di Home Assistant.",
    "erreur_loading": "Vision si sta avviando: riprova tra un momento.",
    "erreur_pc": "Questo dispositivo non esiste più in Vision.",
    "erreur_json": "Non è stato possibile leggere la richiesta: riprova.",
    "erreur_module": "Questa funzione è disattivata in Vision.",
    "erreur_minutes": "Indica una durata.",
    "erreur_defaut": "Non è andata: non è cambiato nulla, riprova tra un momento.",
    "geste_en_cours": "In corso…",
    "geste_fait": "Fatto.",
    "catalogue_indisponible": "Catalogo non disponibile: {erreur}",
    "fait_elements": "Fatto per {n} elementi.",
    "echec_decider": "Non è andata: non è cambiato nulla, riprova.",
    "version": "versione {version}",
    "signature": "Firma: {statut}", "aucune_signature": "Nessuna firma", "non_signe": "non firmato",
    "ram_mo": "{n} MB", "applique": "applicato", "inactif": "inattivo",
    "desinstallation_demandee": "disinstallazione richiesta",
    "decide": "Deciso:",
    "valider": "Convalidare",
    "defaire": "Annullare: rimettere da classificare e ripristinare",
    "confirmer_titre": "Disinstallare {nom}?",
    "confirmer_texte": "Su: {ou}. Vision avvia il programma di disinstallazione ufficiale, senza chiedere nulla sullo schermo. <b>Non si può annullare</b>: per riaverlo, bisognerà reinstallarlo.",
    "aucun_appareil": "nessun dispositivo",
    "desinstaller_definitivement": "Disinstallare definitivamente", "annuler": "Annullare",
    "intouchable": "Windows — intoccabile",
    "titre": "Processi e avvio",
    "chercher": "Cerca un nome, un editore…",
    "valider_selection": "Convalidare la selezione ({n})", "garder_selection": "Mantenere la selezione",
    "valider_surs": "Convalidare tutto ≥ 80% ({n})",
    "rien_ici": "Niente qui.", "chargement": "Caricamento…",
    "carte_nom": "Vision — Processi",
    "carte_description": "Convalidare ciò che il classificatore propone per processi, avvio, attività e servizi."
  },
  "nl": {
    "verdict_garder": "Behouden", "verdict_hors_demarrage": "Uit opstarten halen", "verdict_nuisible": "Schadelijk",
    "verdict_desinstaller": "Verwijderen",
    "retour_fait": "✔ verwijderd", "retour_echec": "✖ mislukt", "retour_impossible": "✖ niet mogelijk",
    "retour_detail": "{retour}: {detail}",
    "onglet_propositions": "Te bevestigen", "onglet_a_classer": "Te sorteren", "onglet_decides": "Beslist",
    "onglet_logiciels": "Geïnstalleerde programma's", "onglet_tous": "Alles",
    "genre_demarrage": "Opstarten", "genre_tache": "Taak", "genre_service": "Service", "genre_processus": "Proces",
    "genre_logiciel": "Programma", "tous_genres": "Alle soorten",
    "erreur_admin": "Alleen voor beheerders van Home Assistant.",
    "erreur_loading": "Vision start op: probeer het zo opnieuw.",
    "erreur_pc": "Dit apparaat bestaat niet meer in Vision.",
    "erreur_json": "Het verzoek kon niet worden gelezen: probeer het opnieuw.",
    "erreur_module": "Deze functie staat uit in Vision.",
    "erreur_minutes": "Geef een duur op.",
    "erreur_defaut": "Dat is niet gelukt: er is niets veranderd, probeer het zo opnieuw.",
    "geste_en_cours": "Bezig…",
    "geste_fait": "Klaar.",
    "catalogue_indisponible": "Catalogus niet beschikbaar: {erreur}",
    "fait_elements": "Klaar voor {n} items.",
    "echec_decider": "Dat is niet gelukt: er is niets veranderd, probeer het opnieuw.",
    "version": "versie {version}",
    "signature": "Handtekening: {statut}", "aucune_signature": "Geen handtekening", "non_signe": "niet ondertekend",
    "ram_mo": "{n} MB", "applique": "toegepast", "inactif": "inactief",
    "desinstallation_demandee": "verwijdering gevraagd",
    "decide": "Beslist:",
    "valider": "Bevestigen",
    "defaire": "Ongedaan maken: terug naar te sorteren en herstellen",
    "confirmer_titre": "{nom} verwijderen?",
    "confirmer_texte": "Op: {ou}. Vision start het officiële verwijderprogramma, zonder iets op het scherm te vragen. <b>Dit kan niet ongedaan worden gemaakt</b>: om het terug te krijgen, moet het opnieuw worden geïnstalleerd.",
    "aucun_appareil": "geen apparaat",
    "desinstaller_definitivement": "Definitief verwijderen", "annuler": "Annuleren",
    "intouchable": "Windows — onaantastbaar",
    "titre": "Processen en opstarten",
    "chercher": "Zoek een naam, een uitgever…",
    "valider_selection": "Selectie bevestigen ({n})", "garder_selection": "Selectie behouden",
    "valider_surs": "Alles ≥ 80% bevestigen ({n})",
    "rien_ici": "Niets hier.", "chargement": "Laden…",
    "carte_nom": "Vision — Processen",
    "carte_description": "Bevestigen wat de sorteerder voorstelt voor processen, opstarten, taken en services."
  }
}/*i18n-json:fin*/;
// Les catégories (étiquettes de la maison) : cette carte n'en affiche pas.
const CATEGORIES_I18N = /*i18n-categories*/{"jeux": {"fr": "Jeux", "en": "Games", "de": "Spiele", "es": "Juegos", "it": "Giochi", "nl": "Games"}, "videos": {"fr": "Vidéos", "en": "Videos", "de": "Videos", "es": "Vídeos", "it": "Video", "nl": "Video's"}, "reseaux_sociaux": {"fr": "Réseaux sociaux", "en": "Social media", "de": "Soziale Medien", "es": "Redes sociales", "it": "Social network", "nl": "Sociale media"}, "tv": {"fr": "TV", "en": "TV", "de": "TV", "es": "TV", "it": "TV", "nl": "tv"}, "vod": {"fr": "VOD", "en": "Streaming", "de": "Streaming", "es": "Streaming", "it": "Streaming", "nl": "Streaming"}, "musique": {"fr": "Musique", "en": "Music", "de": "Musik", "es": "Música", "it": "Musica", "nl": "Muziek"}, "ia": {"fr": "IA", "en": "AI", "de": "KI", "es": "IA", "it": "IA", "nl": "AI"}, "internet": {"fr": "Internet", "en": "Internet", "de": "Internet", "es": "Internet", "it": "Internet", "nl": "Internet"}, "travail": {"fr": "Travail", "en": "Work", "de": "Arbeit", "es": "Trabajo", "it": "Lavoro", "nl": "Werk"}, "creation": {"fr": "Création", "en": "Creativity", "de": "Kreativität", "es": "Creatividad", "it": "Creatività", "nl": "Creativiteit"}, "actu_medias": {"fr": "Actu & médias", "en": "News & media", "de": "Nachrichten & Medien", "es": "Noticias y medios", "it": "Notizie e media", "nl": "Nieuws & media"}, "pub_tracage": {"fr": "Pub & traçage", "en": "Ads & tracking", "de": "Werbung & Tracking", "es": "Anuncios y rastreo", "it": "Pubblicità e tracciamento", "nl": "Advertenties & tracking"}, "contournement": {"fr": "Contournement", "en": "Bypass tools", "de": "Umgehung", "es": "Elusión", "it": "Aggiramento", "nl": "Omzeiling"}, "dangereux": {"fr": "Dangereux", "en": "Dangerous", "de": "Gefährlich", "es": "Peligroso", "it": "Pericoloso", "nl": "Gevaarlijk"}, "adulte": {"fr": "Adulte", "en": "Adult", "de": "Erwachsene", "es": "Adultos", "it": "Adulti", "nl": "Volwassenen"}, "pegi_12": {"fr": "PEGI 12", "en": "PEGI 12", "de": "PEGI 12", "es": "PEGI 12", "it": "PEGI 12", "nl": "PEGI 12"}, "pegi_16": {"fr": "PEGI 16", "en": "PEGI 16", "de": "PEGI 16", "es": "PEGI 16", "it": "PEGI 16", "nl": "PEGI 16"}, "pegi_18": {"fr": "PEGI 18", "en": "PEGI 18", "de": "PEGI 18", "es": "PEGI 18", "it": "PEGI 18", "nl": "PEGI 18"}, "systeme": {"fr": "Système", "en": "System", "de": "System", "es": "Sistema", "it": "Sistema", "nl": "Systeem"}, "google": {"fr": "Google", "en": "Google", "de": "Google", "es": "Google", "it": "Google", "nl": "Google"}, "discord": {"fr": "Discord", "en": "Discord", "de": "Discord", "es": "Discord", "it": "Discord", "nl": "Discord"}, "annuaires_discord": {"fr": "Annuaires Discord", "en": "Discord directories", "de": "Discord-Verzeichnisse", "es": "Directorios de Discord", "it": "Directory Discord", "nl": "Discord-gidsen"}, "toujours_autorise": {"fr": "Toujours autorisé", "en": "Always allowed", "de": "Immer erlaubt", "es": "Siempre permitido", "it": "Sempre consentito", "nl": "Altijd toegestaan"}, "ecole": {"fr": "École", "en": "School", "de": "Schule", "es": "Escuela", "it": "Scuola", "nl": "School"}}/*i18n-categories:fin*/;
// ---- i18n:code — le même dans toutes les cartes de Vision (essai/i18n_cartes_test.py le vérifie) ----
// Langue : celle de l'utilisateur Home Assistant (hass.locale.language, sinon hass.language), sur deux lettres ;
// une langue que Vision ne parle pas → l'anglais. Un texte absent → l'anglais, puis le français, puis la clé.
const brutLangue = (hass) =>
  String((hass && ((hass.locale && hass.locale.language) || hass.language)) ||
    (typeof document !== "undefined" && document.documentElement && document.documentElement.lang) || "");
const langueDe = (hass) => {
  const base = brutLangue(hass).toLowerCase().split(/[-_]/)[0];
  return TEXTES[base] ? base : "en";
};
// La locale des dates, heures et nombres : l'étiquette complète (« de-CH ») si sa langue est parlée, sinon la langue.
const localeDe = (hass) => {
  const brut = brutLangue(hass).replace(/_/g, "-");
  const lg = langueDe(hass);
  return brut.toLowerCase().split("-")[0] === lg ? brut : lg;
};
let LANGUE = langueDe(null);
let LOCALE = localeDe(null);
// À l'arrivée de hass : rend la langue (une carte se redessine quand elle change).
const suivreLangue = (hass) => { LANGUE = langueDe(hass); LOCALE = localeDe(hass); return LANGUE; };
// Vocabulaire : Décideur, Demandeur, Lieu — jamais « maison », « parent », « enfant » écrits en dur. Les textes portent
// des champs {decideur} {decideurs} {demandeur} {demandeurs} {lieu} (et {Decideur}… avec majuscule initiale), remplis
// par tr(). Mots par défaut : ceux de pc_parental/vocabulaire.py (DEFAUTS, vérifié par le test) ; {lieu} : le nom du
// lieu configuré quand la carte le connaît, sinon un mot neutre par langue (lieu.DEFAUTS : « Lieu », « Place »…).
const VOCABULAIRE = /*i18n-vocabulaire*/{
  "fr": {"decideur": "décideur", "decideurs": "décideurs", "demandeur": "demandeur", "demandeurs": "demandeurs"},
  "en": {"decideur": "decider", "decideurs": "deciders", "demandeur": "requester", "demandeurs": "requesters"},
  "de": {"decideur": "Entscheider", "decideurs": "Entscheider", "demandeur": "Anfragender", "demandeurs": "Anfragende"},
  "es": {"decideur": "responsable", "decideurs": "responsables", "demandeur": "solicitante", "demandeurs": "solicitantes"},
  "it": {"decideur": "decisore", "decideurs": "decisori", "demandeur": "richiedente", "demandeurs": "richiedenti"},
  "nl": {"decideur": "beslisser", "decideurs": "beslissers", "demandeur": "aanvrager", "demandeurs": "aanvragers"}
}/*i18n-vocabulaire:fin*/;
const LIEU_DEFAUT = {"fr": "Lieu", "en": "Place", "de": "Ort", "es": "Lugar", "it": "Luogo", "nl": "Plek"};  // i18n-ignorer : lieu par défaut de chaque langue, lieu.DEFAUTS (vérifié par le test)
const CHAMPS_VOCABULAIRE = ["decideur", "decideurs", "demandeur", "demandeurs", "lieu"];
// Point unique de surcharge (nom du lieu reçu par la carte, futur réglage « Vocabulaire ») :
// poserVocabulaire({ lieu: "École", mots: { fr: { decideur: "responsable" } } }) — même forme que store.mots_vocabulaire.
const VOCABULAIRE_PERSO = { lieu: "", mots: {} };
const poserVocabulaire = (v) => {
  if (!v) return;
  if (typeof v.lieu === "string") VOCABULAIRE_PERSO.lieu = v.lieu.trim();
  if (v.mots && typeof v.mots === "object") VOCABULAIRE_PERSO.mots = v.mots;
};
const majuscule = (m) => (m ? m[0].toUpperCase() + m.slice(1) : m);
const vocabulaire = () => {
  const mots = Object.assign({}, VOCABULAIRE[LANGUE] || VOCABULAIRE.en);
  const perso = (VOCABULAIRE_PERSO.mots && VOCABULAIRE_PERSO.mots[LANGUE]) || {};
  for (const c of CHAMPS_VOCABULAIRE) if (typeof perso[c] === "string" && perso[c].trim()) mots[c] = perso[c].trim();
  if (!mots.lieu) mots.lieu = VOCABULAIRE_PERSO.lieu || LIEU_DEFAUT[LANGUE] || LIEU_DEFAUT.en;
  const sortie = {};
  for (const [c, v] of Object.entries(mots)) { sortie[c] = v; sortie[majuscule(c)] = majuscule(v); }
  return sortie;
};
const tr = (cle, champs) => {
  let texte = cle;
  for (const lg of [LANGUE, "en", "fr"]) {
    const x = TEXTES[lg] && TEXTES[lg][cle];
    if (typeof x === "string" && x !== "") { texte = x; break; }
  }
  if (!texte.includes("{")) return texte;
  const tous = Object.assign(vocabulaire(), champs || {});
  return texte.replace(/\{(\w+)\}/g, (m, k) => (tous[k] === undefined || tous[k] === null ? m : String(tous[k])));
};
// Pluriels : <clé>_one / <clé>_other, choisis par Intl.PluralRules (en français, 0 et 1 sont au singulier).
const trn = (cle, n, champs) => {
  let forme;
  try { forme = new Intl.PluralRules(LOCALE).select(n); } catch (e) { forme = n === 1 ? "one" : "other"; }
  return tr(`${cle}_${forme === "one" ? "one" : "other"}`, Object.assign({ n }, champs));
};
// Heures, dates et nombres : toujours par la locale, jamais écrits à la main.
const trHeure = (d) => d.toLocaleTimeString(LOCALE, { hour: "2-digit", minute: "2-digit" });
const trDate = (d, options) => new Intl.DateTimeFormat(LOCALE, options).format(d);
const trNombre = (n, options) => new Intl.NumberFormat(LOCALE, options).format(n);
// Les catégories gardent leur nom français dans les données : seul l'affichage les traduit (clé sans accents ni
// casse, « _ » pour le reste ; une catégorie inconnue, ou une maison en français, garde le nom tel qu'il est écrit).
const cleCategorie = (nom) => String(nom ?? "").normalize("NFKD").replace(/[^\x00-\x7f]/g, "").toLowerCase()
  .replace(/[^a-z0-9]+/g, "_").replace(/^_+|_+$/g, "");
const trCat = (nom) => {
  if (LANGUE === "fr") return nom;
  const e = CATEGORIES_I18N[cleCategorie(nom)];
  return (e && (e[LANGUE] || e.en)) || nom;
};
// ---- i18n:fin ----
const VERDICTS = {
  garder: { libelle: "verdict_garder", icone: "✓", teinte: "var(--success-color, #43a047)" },
  hors_demarrage: { libelle: "verdict_hors_demarrage", icone: "⏻", teinte: "var(--warning-color, #fb8c00)" },
  nuisible: { libelle: "verdict_nuisible", icone: "⛔", teinte: "var(--error-color, #e53935)" },
  desinstaller: { libelle: "verdict_desinstaller", icone: "🗑", teinte: "var(--error-color, #e53935)" },
};
// Ce que l'agent répond après une désinstallation demandée.
const RETOURS = {
  fait: ["retour_fait", "var(--success-color, #43a047)"],
  echec: ["retour_echec", "var(--error-color, #e53935)"],
  impossible: ["retour_impossible", "var(--warning-color, #fb8c00)"],
};
const ONGLETS = [
  ["propositions", "onglet_propositions"],
  ["a_classer", "onglet_a_classer"],
  ["decides", "onglet_decides"],
  ["logiciels", "onglet_logiciels"],
  ["tous", "onglet_tous"],
];
const GENRES = {
  demarrage: "genre_demarrage",
  tache: "genre_tache",
  service: "genre_service",
  processus: "genre_processus",
  logiciel: "genre_logiciel",
};

const esc = (t) =>
  String(t ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

// 1.97.3 : le retour visible de chaque geste, le même dans toutes les cartes Vision. Au clic, le bouton touché dit
// « En cours… » ; puis une ligne en tête de carte dit le résultat (la réponse du serveur) ou l'échec, en clair.
// Elle survit aux redessins de la carte (observateur) et le résultat s'efface seul après 6 s.
const ERREURS_GESTE = {
  admin: "erreur_admin", loading: "erreur_loading", pc: "erreur_pc", json: "erreur_json",
  module: "erreur_module", minutes: "erreur_minutes",
};
const messageGeste = (e, defaut) => {
  const b = e && e.body;
  if (b && b.retour) return String(b.retour);
  if (b && ERREURS_GESTE[b.error]) return tr(ERREURS_GESTE[b.error]);
  if (e && e.status_code === 403) return tr(ERREURS_GESTE.admin);
  return defaut || tr("erreur_defaut");
};
const retourGeste = (hote) => {
  const r = hote.shadowRoot;
  let clic = "", enCours = "", texte = "", ko = false, minute = 0, pose = false;
  const cle = (b) => JSON.stringify(Object.assign({}, b.dataset)) + "|" + b.textContent.trim();
  r.addEventListener("click", (ev) => {
    const b = ev.composedPath().find((x) => x && x.tagName === "BUTTON");
    if (b) clic = cle(b);
  }, true);
  const appliquer = () => {
    if (pose) return;
    pose = true;
    try {
      if (enCours) {
        for (const b of r.querySelectorAll("button")) {
          if (cle(b) === enCours) { b.textContent = tr("geste_en_cours"); b.disabled = true; b.setAttribute("aria-busy", "true"); }
        }
      }
      let z = r.querySelector(".vision-retour");
      if (!texte) { if (z) z.remove(); return; }
      if (!z) {
        z = document.createElement("div");
        z.className = "vision-retour";
        const c = r.querySelector("ha-card") || r;
        c.insertBefore(z, c.firstChild);
      }
      const couleur = ko ? "var(--error-color, #c62828)" : "var(--success-color, #2e7d32)";
      z.setAttribute("role", ko ? "alert" : "status");
      const style = `position:sticky;top:0;z-index:2;margin:8px 12px;padding:8px 12px;border-radius:10px;font-weight:500;color:${couleur};` +
        `background:var(--card-background-color, #fff);border:1px solid ${couleur};`;
      if (z.getAttribute("style") !== style) z.setAttribute("style", style);
      if (z.textContent !== texte) z.textContent = texte;
    } finally {
      pose = false;
    }
  };
  new MutationObserver(appliquer).observe(r, { childList: true, subtree: true });
  return {
    debut() { enCours = clic; clic = ""; texte = ""; clearTimeout(minute); appliquer(); },
    ok(t) {
      enCours = ""; ko = false; texte = t || tr("geste_fait"); appliquer();
      clearTimeout(minute); minute = setTimeout(() => { texte = ""; appliquer(); }, 6000);
    },
    echec(e, defaut) { enCours = ""; ko = true; texte = typeof e === "string" ? e : messageGeste(e, defaut); clearTimeout(minute); appliquer(); },
  };
};

class VisionProcessusCard extends HTMLElement {
  constructor() {
    super();
    this.attachShadow({ mode: "open" });
    this._geste = retourGeste(this);
    this._onglet = "propositions";
    this._genre = "";
    this._cherche = "";
    this._choix = new Set();
    this._donnees = null;
    this._occupe = false;
    this._erreur = "";
    this._aConfirmer = "";
  }

  setConfig(config) {
    this._config = config || {};
    if (this._config.onglet) this._onglet = this._config.onglet;
  }

  getCardSize() {
    return 8;
  }

  set hass(hass) {
    const premier = !this._hass;
    this._hass = hass;
    const langue = suivreLangue(hass);
    // Le capteur bouge quand une proposition arrive : on relit alors.
    const capteur = hass.states["sensor.vision_processus_a_valider"] || hass.states["sensor.comvision_processus_a_valider"];
    const signe = capteur
      ? `${capteur.state}/${capteur.attributes.a_classer}/${capteur.attributes.decides}/${capteur.attributes.desinstallations}`
      : "";
    if (premier || signe !== this._signe) {
      this._langue = langue;
      this._signe = signe;
      this._charger();
    } else if (langue !== this._langue) {
      this._langue = langue;
      this._rendre();
    }
  }

  async _charger() {
    try {
      this._donnees = await this._hass.callApi("GET", "pc_parental/processus");
      this._erreur = "";
    } catch (e) {
      // 1.93.06 : le catalogue est réservé aux administrateurs de Home Assistant (403 « admin »).
      const admin = e && (e.status_code === 403 || e.error === "admin" || (e.body && e.body.error === "admin"));
      this._donnees = admin ? null : this._donnees;
      this._erreur = admin ? tr("erreur_admin") : tr("catalogue_indisponible", { erreur: (e && (e.message || e.error)) || e });
    }
    this._rendre();
  }

  async _decider(cles, decision) {
    if (!cles.length || this._occupe) return;
    this._geste.debut();
    this._occupe = true;
    this._rendre();
    try {
      const corps = { cles, decision };
      // Seul le panneau « es-tu sûr ? » arrive ici avec ce verdict.
      if (decision === "desinstaller") corps.confirmation = true;
      await this._hass.callService("pc_parental", "processus_decider", corps);
      cles.forEach((c) => this._choix.delete(c));
      this._geste.ok(cles.length > 1 ? tr("fait_elements", { n: cles.length }) : tr("geste_fait"));
    } catch (e) {
      this._geste.echec((e && e.message) || tr("echec_decider"));
    }
    this._occupe = false;
    await this._charger();
  }

  _visibles() {
    const el = (this._donnees && this._donnees.elements) || [];
    const q = this._cherche.trim().toLowerCase();
    return el.filter((e) => {
      if (this._onglet !== "tous" && e.etat !== this._onglet) return false;
      if (this._onglet !== "tous" && e.protege && e.etat !== "decides") return false;
      if (this._genre && e.genre !== this._genre) return false;
      if (!q) return true;
      return [e.nom, e.exe, e.editeur, e.signataire, e.description, e.chemin]
        .some((v) => String(v || "").toLowerCase().includes(q));
    });
  }

  _ligne(e) {
    const p = e.proposition;
    const d = e.decision;
    const sig = e.genre === "logiciel"
      ? (e.version ? `<span class="ou">${esc(tr("version", { version: e.version }))}</span>` : "")
      : e.signataire
      ? `<span class="sig ${e.statut === "Valid" ? "ok" : "ko"}" title="${esc(tr("signature", { statut: e.statut }))}">${e.statut === "Valid" ? "🔏" : "⚠"} ${esc(e.signataire)}</span>`  // i18n-ignorer : « Valid » est le statut de signature envoyé par l'agent (donnée)
      : `<span class="sig ko" title="${esc(tr("aucune_signature"))}">⚠ ${esc(tr("non_signe"))}</span>`;
    const pcs = (e.pcs || [])
      .map((x) => {
        const ram = x.ram_mo ? ` · ${esc(tr("ram_mo", { n: Math.round(x.ram_mo) }))}` : "";
        const etat = x.applique ? ` ✔ ${esc(tr("applique"))}` : e.genre === "processus" ? "" : x.actif ? "" : ` · ${esc(tr("inactif"))}`;
        const ret = x.desinstall && RETOURS[x.desinstall.etat];
        const retour = ret
          ? ` <span style="color:${ret[1]}" title="${esc(x.desinstall.detail || "")}">${esc(x.desinstall.detail ? tr("retour_detail", { retour: tr(ret[0]), detail: x.desinstall.detail }) : tr(ret[0]))}</span>`
          : d && d.verdict === "desinstaller" && x.vise !== false ? ` · ${esc(tr("desinstallation_demandee"))}` : "";
        return `<span class="pc">${esc(x.pc)}${ram}${etat}${retour}</span>`;
      })
      .join("");
    const verdictBadge = (v, texte) =>
      v ? `<span class="badge" style="--t:${VERDICTS[v].teinte}">${VERDICTS[v].icone} ${esc(texte || tr(VERDICTS[v].libelle))}</span>` : "";
    const prop = p
      ? `<div class="prop">${verdictBadge(p.verdict)} <span class="conf">${p.confiance}%</span> <span class="raison">${esc(p.raison)}</span></div>`
      : "";
    const dec = d ? `<div class="dec">${esc(tr("decide"))} ${verdictBadge(d.verdict)}</div>` : "";
    // Deux rangées plutôt qu'un enroulement : « Valider » en haut, les
    // trois verdicts dessous, toujours au même endroit d'une ligne à
    // l'autre. Laissés à `flex-wrap`, ils se replaçaient selon la
    // longueur du bouton — et l'œil ne retrouvait plus le même geste au
    // même endroit d'une ligne à la suivante.
    let valider = "";
    const icones = [];
    if (!e.protege) {
      if (p && !d) valider = `<button class="valider" data-a="valider" data-c="${esc(e.cle)}">✓ ${esc(tr("valider"))}</button>`;
      for (const v of Object.keys(VERDICTS)) {
        if (v === "hors_demarrage" && e.genre === "processus") continue;
        if (e.genre === "logiciel" && v !== "garder" && v !== "desinstaller") continue;
        if (d && d.verdict === v) continue;
        // Désinstaller : seulement si la maison l'a permis, et jamais sur
        // ce que Vision protège (Windows, pilotes, anti-triche, l'agent).
        if (v === "desinstaller" && (!this._donnees?.desinstallation || e.non_desinstallable)) continue;
        icones.push(`<button data-a="${v}" data-c="${esc(e.cle)}" title="${esc(tr(VERDICTS[v].libelle))}">${VERDICTS[v].icone}</button>`);
      }
      if (d) icones.push(`<button data-a="neutre" data-c="${esc(e.cle)}" title="${esc(tr("defaire"))}">↺</button>`);
    }
    const boutons = [valider, icones.length ? `<div class="icones">${icones.join("")}</div>` : ""];
    const ou = (e.pcs || []).map((x) => x.pc).join(", ");
    const confirmer = this._aConfirmer === e.cle
      ? `<div class="confirmer">
           <b>${esc(tr("confirmer_titre", { nom: e.nom || e.exe }))}</b>
           <div>${tr("confirmer_texte", { ou: esc(ou || tr("aucun_appareil")) })}</div>
           <div class="choix"><button data-oui="${esc(e.cle)}" class="danger">${esc(tr("desinstaller_definitivement"))}</button>
           <button data-non>${esc(tr("annuler"))}</button></div>
         </div>`
      : "";
    const coche = e.protege ? "" : `<input type="checkbox" data-coche="${esc(e.cle)}" ${this._choix.has(e.cle) ? "checked" : ""}>`;
    return `
      <div class="ligne ${e.protege ? "protege" : ""}">
        <div class="c">${coche}</div>
        <div class="corps">
          <div class="titre"><span class="genre g-${e.genre}">${GENRES[e.genre] ? esc(tr(GENRES[e.genre])) : e.genre}</span>
            <b>${esc(e.nom || e.exe)}</b> ${e.editeur ? `<span class="ed">${esc(e.editeur)}</span>` : ""}
            ${e.protege ? `<span class="ed">🛡 ${esc(tr("intouchable"))}</span>` : ""}</div>
          <div class="meta">${sig} <span class="ou">${esc(e.ou)}</span> <span class="chemin" title="${esc(e.commande || e.chemin)}">${esc(e.chemin)}</span></div>
          <div class="pcs">${pcs}</div>
          ${prop}${dec}${confirmer}
        </div>
        <div class="actions">${boutons.join("")}</div>
      </div>`;
  }

  _rendre() {
    const d = this._donnees;
    const compte = (d && d.compte) || { propositions: 0, a_classer: 0, decides: 0, logiciels: 0 };
    const liste = this._visibles();
    const choisis = [...this._choix].filter((c) => liste.some((e) => e.cle === c));
    const surs = liste.filter((e) => e.proposition && !e.decision && e.proposition.confiance >= 80).map((e) => e.cle);
    const onglets = ONGLETS.map(([k, t]) => {
      const n = k === "tous" ? "" : ` <span class="n">${compte[k] ?? 0}</span>`;
      return `<button class="onglet ${this._onglet === k ? "actif" : ""}" data-onglet="${k}">${esc(tr(t))}${n}</button>`;
    }).join("");
    const genres = [["", "tous_genres"], ...Object.entries(GENRES)]
      .map(([k, t]) => `<option value="${k}" ${this._genre === k ? "selected" : ""}>${esc(tr(t))}</option>`)
      .join("");
    this.shadowRoot.innerHTML = `
      <style>
        :host { display:block; }
        ha-card { padding: 12px 14px; }
        input[type="checkbox"] { width:20px; height:20px; margin:2px 4px 2px 0; flex:none; }
        h2 { margin: 0 0 8px; font-size: 1.2em; font-weight: 500; }
        .onglets { display:flex; gap:6px; flex-wrap:wrap; margin-bottom:8px; }
        .onglet { border:1px solid var(--divider-color); background:none; color:var(--primary-text-color); border-radius:16px; padding:4px 12px; cursor:pointer; }
        .onglet.actif { background: var(--primary-color); color: var(--text-primary-color, #fff); border-color: var(--primary-color); }
        .n { opacity:.8; font-size:.85em; }
        .outils { display:flex; gap:8px; flex-wrap:wrap; align-items:center; margin-bottom:8px; }
        input[type=search], select { padding:6px 8px; border:1px solid var(--divider-color); border-radius:6px; background: var(--card-background-color); color: var(--primary-text-color); }
        input[type=search] { flex:1; min-width:140px; }
        .outils select { min-width:9.5em; }  /* recette : le choix affiché garde sa marge, même à 130 % */
        .lot button { max-width:100%; white-space:normal; text-align:start; }
        .lot button, .actions button { border:1px solid var(--divider-color); background: var(--secondary-background-color); color: var(--primary-text-color); border-radius:6px; padding:4px 9px; cursor:pointer; font-size:.95em; }
        button:disabled { opacity:.5; cursor:default; }
        .valider { background: var(--primary-color) !important; color: var(--text-primary-color, #fff) !important; border-color: var(--primary-color) !important; }
        .ligne { display:grid; grid-template-columns: 22px minmax(0,1fr) auto; gap:8px 10px; padding:8px 0; border-top:1px solid var(--divider-color); align-items:start; }
        .c { padding-top:3px; }
        .ligne.protege { opacity:.6; }
        .titre { display:flex; gap:6px; align-items:baseline; flex-wrap:wrap; }
        .genre { font-size:.75em; padding:1px 7px; border-radius:10px; background: var(--secondary-background-color); }
        .ed, .ou { color: var(--secondary-text-color); font-size:.85em; }
        .meta { font-size:.82em; color: var(--secondary-text-color); display:flex; gap:8px; flex-wrap:wrap; margin-top:2px; }
        .chemin { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; max-width: 420px; }
        .sig.ko { color: var(--warning-color, #fb8c00); }
        .pcs { display:flex; gap:6px; flex-wrap:wrap; margin-top:4px; }
        .pc { font-size:.78em; border:1px solid var(--divider-color); border-radius:10px; padding:0 7px; }
        .prop, .dec { margin-top:5px; font-size:.9em; }
        .badge { color: var(--t); border:1px solid var(--t); border-radius:10px; padding:0 7px; font-size:.85em; white-space:nowrap; }
        .conf { font-weight:600; }
        .raison { color: var(--primary-text-color); }
        .actions { display:flex; flex-direction:column; gap:4px; align-items:flex-end; }
        .icones { display:flex; gap:4px; }
        .actions button { min-width:36px; }
        .actions .valider { white-space:nowrap; }
        @media (max-width: 600px) {
          .ligne { grid-template-columns: 22px minmax(0,1fr); }
          .actions { grid-column: 2; flex-direction:row; align-items:center; justify-content:flex-start; flex-wrap:wrap; }
        }
        .confirmer { margin-top:8px; padding:10px 12px; border:1px solid var(--error-color, #e53935); border-radius:8px; font-size:.92em; display:grid; gap:6px; }
        .confirmer .choix { display:flex; gap:8px; flex-wrap:wrap; }
        .confirmer button { border:1px solid var(--divider-color); background: var(--secondary-background-color); color: var(--primary-text-color); border-radius:6px; padding:5px 10px; cursor:pointer; }
        .confirmer .danger { background: var(--error-color, #e53935); border-color: var(--error-color, #e53935); color:#fff; }
        .vide { padding: 16px 0; color: var(--secondary-text-color); text-align:center; }
        .erreur { color: var(--error-color); margin: 6px 0; }
      </style>
      <ha-card>
        <h2>${esc(this._config?.title || tr("titre"))}</h2>
        <div class="onglets">${onglets}</div>
        <div class="outils">
          <input type="search" placeholder="${esc(tr("chercher"))}" value="${esc(this._cherche)}">
          <select>${genres}</select>
        </div>
        <div class="outils lot">
          <button data-lot="valider" ${choisis.length && !this._occupe ? "" : "disabled"}>✓ ${esc(tr("valider_selection", { n: choisis.length }))}</button>
          <button data-lot="garder" ${choisis.length && !this._occupe ? "" : "disabled"}>${esc(tr("garder_selection"))}</button>
          <button data-surs ${surs.length && !this._occupe ? "" : "disabled"}>${esc(tr("valider_surs", { n: surs.length }))}</button>
        </div>
        ${this._erreur ? `<div class="erreur">${esc(this._erreur)}</div>` : ""}
        ${d ? (liste.length ? liste.map((e) => this._ligne(e)).join("") : `<div class="vide">${esc(tr("rien_ici"))}</div>`) : `<div class="vide">${esc(tr("chargement"))}</div>`}
      </ha-card>`;
    this._brancher(liste, surs);
  }

  _brancher(liste, surs) {
    const r = this.shadowRoot;
    r.querySelectorAll("[data-onglet]").forEach((b) =>
      b.addEventListener("click", () => { this._onglet = b.dataset.onglet; this._choix.clear(); this._rendre(); }));
    const q = r.querySelector("input[type=search]");
    q.addEventListener("input", () => {
      this._cherche = q.value;
      const pos = q.selectionStart;
      this._rendre();
      const n = this.shadowRoot.querySelector("input[type=search]");
      n.focus();
      n.setSelectionRange(pos, pos);
    });
    r.querySelector("select").addEventListener("change", (ev) => { this._genre = ev.target.value; this._rendre(); });
    r.querySelectorAll("[data-coche]").forEach((c) =>
      c.addEventListener("change", () => {
        c.checked ? this._choix.add(c.dataset.coche) : this._choix.delete(c.dataset.coche);
        this._rendre();
      }));
    r.querySelectorAll(".actions button").forEach((b) =>
      b.addEventListener("click", () => {
        // Jamais de désinstallation en un clic : on ouvre la question.
        if (b.dataset.a === "desinstaller") { this._aConfirmer = b.dataset.c; this._rendre(); return; }
        this._decider([b.dataset.c], b.dataset.a);
      }));
    r.querySelectorAll("[data-oui]").forEach((b) =>
      b.addEventListener("click", () => { this._aConfirmer = ""; this._decider([b.dataset.oui], "desinstaller"); }));
    r.querySelectorAll("[data-non]").forEach((b) =>
      b.addEventListener("click", () => { this._aConfirmer = ""; this._rendre(); }));
    r.querySelectorAll("[data-lot]").forEach((b) =>
      b.addEventListener("click", () => {
        const cles = [...this._choix].filter((c) => liste.some((e) => e.cle === c));
        // « Valider » n'a de sens que là où il y a une proposition.
        const cibles = b.dataset.lot === "valider"
          ? cles.filter((c) => liste.find((e) => e.cle === c)?.proposition)
          : cles;
        this._decider(cibles, b.dataset.lot);
      }));
    const s = r.querySelector("[data-surs]");
    if (s) s.addEventListener("click", () => this._decider(surs, "valider"));
  }
}

// Un seul exemplaire, même si une ancienne copie (/local) est encore chargée à côté.
const _dejaVisionProcessusCard = !!customElements.get("vision-processus-card");
if (!_dejaVisionProcessusCard) customElements.define("vision-processus-card", VisionProcessusCard);
// Ancien nom, pour les tableaux de bord écrits avant : la même carte.
if (!customElements.get("comvision-processus-card")) customElements.define("comvision-processus-card", class extends VisionProcessusCard {});
window.customCards = window.customCards || [];
if (!_dejaVisionProcessusCard) window.customCards.push({
  type: "vision-processus-card",
  name: tr("carte_nom"),
  description: tr("carte_description"),
});
