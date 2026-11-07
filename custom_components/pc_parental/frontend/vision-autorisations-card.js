/*
 * Vision — carte « Autorisations » (1.94, retours de l'auteur du 19/10 : « Règle de la maison » disparaît, les blocages pour
 * tous ont leur section et leur raison, les logiciels d'abord avec leurs sites, des couleurs qui disent l'état réel).
 *
 * Lecture : GET pc_parental/autorisations?personne=&appareil= (badge, raison et portée calculés par Home Assistant ; la
 * carte ne recalcule rien). Gestes : POST pc_parental/autorisations/geste, signés du compte Home Assistant ; un code n'est
 * demandé qu'à un compte marqué partagé ; chaque geste est consigné (« Qui a fait quoi »).
 * Portées, toujours écrites de la même façon : « Pour tout le monde », « Pour <prénom> », « Sur <appareil> ».
 */
// ---- i18n:debut — les textes de cette carte, dans les six langues de Vision (fr source, en repli) ----
const TEXTES = /*i18n-json*/{
  "fr": {
    "carte_nom": "Vision — Autorisations",
    "carte_description": "Apps, sites et blocages : l'état réel, la raison, et les gestes pour tout le monde, une personne ou un appareil.",
    "titre": "Autorisations",
    "regarder": "Regarder",
    "tout_le_monde": "Tout le monde",
    "toutes_personnes": "Toutes les personnes",
    "tous_appareils": "Tous les appareils",
    "filtre_personne": "Personne",
    "filtre_appareil": "Appareil",
    "resume": "Bloquées : {rouge} · partielles : {orange} · ouvertes : {vert}",
    "bloques_pour_tous": "Bloqué pour tout le monde",
    "rien_bloque": "Rien n'est bloqué pour tout le monde.",
    "lever": "Lever le blocage",
    "non_levable": "Géré par la sécurité de Vision",
    "confirmer_lever": "Lever le blocage de {nom} pour tout le monde ?",
    "par_le": "{par}, le {date}",
    "chercher": "Chercher une appli ou un site…",
    "toutes_categories": "Toutes",
    "filtre_categorie": "Catégorie",
    "applis": "Logiciels et applis",
    "sites_seuls": "Sites seuls",
    "categories": "Catégories",
    "cat_compte": "{applis} applis, {sites} sites",
    "sites_one": "{n} site",
    "sites_other": "{n} sites",
    "badge_vert": "Ouvert",
    "badge_orange": "Partiellement bloqué",
    "badge_rouge": "Bloqué",
    "lien_observe": "Observé · {n} requêtes · {jours} jours · {part} %",
    "lien_secours": "Secours (non observé)",
    "lien_incertain": "Incertain (appareil sans attribution)",
    "lien_humain": "Lié à la main",
    "a_verifier": "À vérifier",
    "en_attente": "En attente d'un geste",
    "gestes": "Gestes",
    "ouvrir": "Ouvrir",
    "bloquer": "Bloquer",
    "laisser_passer": "Laisser passer",
    "duree_30": "30 min",
    "duree_60": "1 h",
    "duree_180": "3 h",
    "portee_tous": "Pour tout le monde",
    "portee_personne": "Pour {qui}",
    "portee_appareil": "Sur {appareil}",
    "retirer_lien": "Retirer le lien",
    "rattacher": "Rattacher à…",
    "rattacher_choix": "Rattacher {site} à quelle appli ?",
    "ajouter_site": "Ajouter un site",
    "site_indice": "exemple.com",
    "lier_auto_oui": "Lier tout seul : oui",
    "lier_auto_non": "Lier tout seul : non",
    "effet_aussi": "Effet réel : {liste}",
    "effet_ouvert": "{appareil} ouvert",
    "effet_ferme": "{appareil} fermé",
    "code_titre": "Code {decideur}",
    "code_texte": "Ce compte est partagé : taper le code pour ouvrir.",
    "code_faux": "Code incorrect.",
    "valider": "Valider",
    "annuler": "Annuler",
    "fermer": "Fermer",
    "journal": "Qui a fait quoi",
    "journal_ligne": "{qui} : {action} {cible} ({portee})",
    "action_ouvrir": "a ouvert",
    "action_bloquer": "a bloqué",
    "action_lever": "a levé le blocage de",
    "action_laisser_passer": "a laissé passer",
    "action_rattacher": "a rattaché",
    "action_retirer_lien": "a retiré le lien de",
    "action_lier_auto": "a réglé le lien automatique de",
    "action_compte_partage": "a changé le statut partagé de",
    "aucune": "Rien ne correspond.",
    "chargement": "Chargement…",
    "erreur_admin": "Cet écran est réservé aux administrateurs et aux {decideurs}.",
    "erreur_defaut": "L'opération n'a pas pu être faite.",
    "geste_fait": "C'est fait."
  },
  "en": {
    "carte_nom": "Vision — Permissions",
    "carte_description": "Apps, sites and blocks: the real status, the reason, and actions for everyone, one person or one device.",
    "titre": "Permissions",
    "regarder": "View",
    "tout_le_monde": "Everyone",
    "toutes_personnes": "All people",
    "tous_appareils": "All devices",
    "filtre_personne": "Person",
    "filtre_appareil": "Device",
    "resume": "Blocked: {rouge} · partly: {orange} · open: {vert}",
    "bloques_pour_tous": "Blocked for everyone",
    "rien_bloque": "Nothing is blocked for everyone.",
    "lever": "Lift the block",
    "non_levable": "Managed by Vision's security",
    "confirmer_lever": "Lift the block on {nom} for everyone?",
    "par_le": "{par}, on {date}",
    "chercher": "Search an app or a site…",
    "toutes_categories": "All",
    "filtre_categorie": "Category",
    "applis": "Apps and programs",
    "sites_seuls": "Standalone sites",
    "categories": "Categories",
    "cat_compte": "{applis} apps, {sites} sites",
    "sites_one": "{n} site",
    "sites_other": "{n} sites",
    "badge_vert": "Open",
    "badge_orange": "Partly blocked",
    "badge_rouge": "Blocked",
    "lien_observe": "Observed · {n} requests · {jours} days · {part}%",
    "lien_secours": "Fallback (not observed)",
    "lien_incertain": "Uncertain (device without attribution)",
    "lien_humain": "Linked by hand",
    "a_verifier": "To check",
    "en_attente": "Waiting for a decision",
    "gestes": "Actions",
    "ouvrir": "Open",
    "bloquer": "Block",
    "laisser_passer": "Allow for a while",
    "duree_30": "30 min",
    "duree_60": "1 h",
    "duree_180": "3 h",
    "portee_tous": "For everyone",
    "portee_personne": "For {qui}",
    "portee_appareil": "On {appareil}",
    "retirer_lien": "Remove the link",
    "rattacher": "Attach to…",
    "rattacher_choix": "Attach {site} to which app?",
    "ajouter_site": "Add a site",
    "site_indice": "example.com",
    "lier_auto_oui": "Link automatically: yes",
    "lier_auto_non": "Link automatically: no",
    "effet_aussi": "Actual effect: {liste}",
    "effet_ouvert": "{appareil} open",
    "effet_ferme": "{appareil} closed",
    "code_titre": "Code ({decideur})",
    "code_texte": "This account is shared: enter the code to open.",
    "code_faux": "Wrong code.",
    "valider": "Confirm",
    "annuler": "Cancel",
    "fermer": "Close",
    "journal": "Who did what",
    "journal_ligne": "{qui}: {action} {cible} ({portee})",
    "action_ouvrir": "opened",
    "action_bloquer": "blocked",
    "action_lever": "lifted the block on",
    "action_laisser_passer": "allowed for a while",
    "action_rattacher": "attached",
    "action_retirer_lien": "removed the link of",
    "action_lier_auto": "set automatic linking for",
    "action_compte_partage": "changed the shared status of",
    "aucune": "Nothing matches.",
    "chargement": "Loading…",
    "erreur_admin": "This screen is reserved for administrators and {decideurs}.",
    "erreur_defaut": "The operation could not be done.",
    "geste_fait": "Done."
  },
  "de": {
    "carte_nom": "Vision — Berechtigungen",
    "carte_description": "Apps, Websites und Sperren: der echte Status, der Grund und Aktionen für alle, eine Person oder ein Gerät.",
    "titre": "Berechtigungen",
    "regarder": "Ansehen",
    "tout_le_monde": "Alle",
    "toutes_personnes": "Alle Personen",
    "tous_appareils": "Alle Geräte",
    "filtre_personne": "Person",
    "filtre_appareil": "Gerät",
    "resume": "Gesperrt: {rouge} · teilweise: {orange} · offen: {vert}",
    "bloques_pour_tous": "Für alle gesperrt",
    "rien_bloque": "Für alle ist nichts gesperrt.",
    "lever": "Sperre aufheben",
    "non_levable": "Von der Sicherheit von Vision verwaltet",
    "confirmer_lever": "Sperre von {nom} für alle aufheben?",
    "par_le": "{par}, am {date}",
    "chercher": "App oder Website suchen …",
    "toutes_categories": "Alle",
    "filtre_categorie": "Kategorie",
    "applis": "Programme und Apps",
    "sites_seuls": "Einzelne Websites",
    "categories": "Kategorien",
    "cat_compte": "{applis} Apps, {sites} Websites",
    "sites_one": "{n} Website",
    "sites_other": "{n} Websites",
    "badge_vert": "Offen",
    "badge_orange": "Teilweise gesperrt",
    "badge_rouge": "Gesperrt",
    "lien_observe": "Beobachtet · {n} Anfragen · {jours} Tage · {part} %",
    "lien_secours": "Ersatz (nicht beobachtet)",
    "lien_incertain": "Unsicher (Gerät ohne Zuordnung)",
    "lien_humain": "Von Hand verknüpft",
    "a_verifier": "Zu prüfen",
    "en_attente": "Wartet auf eine Entscheidung",
    "gestes": "Aktionen",
    "ouvrir": "Öffnen",
    "bloquer": "Sperren",
    "laisser_passer": "Vorübergehend erlauben",
    "duree_30": "30 Min.",
    "duree_60": "1 Std.",
    "duree_180": "3 Std.",
    "portee_tous": "Für alle",
    "portee_personne": "Für {qui}",
    "portee_appareil": "Auf {appareil}",
    "retirer_lien": "Verknüpfung entfernen",
    "rattacher": "Zuordnen zu …",
    "rattacher_choix": "{site} welcher App zuordnen?",
    "ajouter_site": "Website hinzufügen",
    "site_indice": "beispiel.de",
    "lier_auto_oui": "Selbst verknüpfen: ja",
    "lier_auto_non": "Selbst verknüpfen: nein",
    "effet_aussi": "Tatsächliche Wirkung: {liste}",
    "effet_ouvert": "{appareil} offen",
    "effet_ferme": "{appareil} gesperrt",
    "code_titre": "Code ({decideur})",
    "code_texte": "Dieses Konto ist geteilt: Code zum Öffnen eingeben.",
    "code_faux": "Falscher Code.",
    "valider": "Bestätigen",
    "annuler": "Abbrechen",
    "fermer": "Schließen",
    "journal": "Wer hat was getan",
    "journal_ligne": "{qui}: {action} {cible} ({portee})",
    "action_ouvrir": "hat geöffnet",
    "action_bloquer": "hat gesperrt",
    "action_lever": "hat die Sperre aufgehoben für",
    "action_laisser_passer": "hat vorübergehend erlaubt",
    "action_rattacher": "hat zugeordnet",
    "action_retirer_lien": "hat die Verknüpfung entfernt von",
    "action_lier_auto": "hat die automatische Verknüpfung eingestellt für",
    "action_compte_partage": "hat den Status „geteilt“ geändert für",
    "aucune": "Nichts passt.",
    "chargement": "Wird geladen …",
    "erreur_admin": "Dieser Bildschirm ist Administratoren und {decideurs} vorbehalten.",
    "erreur_defaut": "Der Vorgang konnte nicht ausgeführt werden.",
    "geste_fait": "Erledigt."
  },
  "es": {
    "carte_nom": "Vision — Permisos",
    "carte_description": "Apps, sitios y bloqueos: el estado real, el motivo y acciones para todos, una persona o un dispositivo.",
    "titre": "Permisos",
    "regarder": "Ver",
    "tout_le_monde": "Todos",
    "toutes_personnes": "Todas las personas",
    "tous_appareils": "Todos los dispositivos",
    "filtre_personne": "Persona",
    "filtre_appareil": "Dispositivo",
    "resume": "Bloqueadas: {rouge} · parciales: {orange} · abiertas: {vert}",
    "bloques_pour_tous": "Bloqueado para todos",
    "rien_bloque": "Nada está bloqueado para todos.",
    "lever": "Levantar el bloqueo",
    "non_levable": "Gestionado por la seguridad de Vision",
    "confirmer_lever": "¿Levantar el bloqueo de {nom} para todos?",
    "par_le": "{par}, el {date}",
    "chercher": "Buscar una app o un sitio…",
    "toutes_categories": "Todas",
    "filtre_categorie": "Categoría",
    "applis": "Programas y apps",
    "sites_seuls": "Sitios sueltos",
    "categories": "Categorías",
    "cat_compte": "{applis} apps, {sites} sitios",
    "sites_one": "{n} sitio",
    "sites_other": "{n} sitios",
    "badge_vert": "Abierto",
    "badge_orange": "Parcialmente bloqueado",
    "badge_rouge": "Bloqueado",
    "lien_observe": "Observado · {n} peticiones · {jours} días · {part} %",
    "lien_secours": "Respaldo (no observado)",
    "lien_incertain": "Incierto (dispositivo sin atribución)",
    "lien_humain": "Vinculado a mano",
    "a_verifier": "Por verificar",
    "en_attente": "A la espera de una decisión",
    "gestes": "Acciones",
    "ouvrir": "Abrir",
    "bloquer": "Bloquear",
    "laisser_passer": "Permitir un rato",
    "duree_30": "30 min",
    "duree_60": "1 h",
    "duree_180": "3 h",
    "portee_tous": "Para todos",
    "portee_personne": "Para {qui}",
    "portee_appareil": "En {appareil}",
    "retirer_lien": "Quitar el vínculo",
    "rattacher": "Vincular a…",
    "rattacher_choix": "¿A qué app vincular {site}?",
    "ajouter_site": "Añadir un sitio",
    "site_indice": "ejemplo.com",
    "lier_auto_oui": "Vincular solo: sí",
    "lier_auto_non": "Vincular solo: no",
    "effet_aussi": "Efecto real: {liste}",
    "effet_ouvert": "{appareil} abierto",
    "effet_ferme": "{appareil} cerrado",
    "code_titre": "Código ({decideur})",
    "code_texte": "Esta cuenta es compartida: escribir el código para abrir.",
    "code_faux": "Código incorrecto.",
    "valider": "Confirmar",
    "annuler": "Cancelar",
    "fermer": "Cerrar",
    "journal": "Quién hizo qué",
    "journal_ligne": "{qui}: {action} {cible} ({portee})",
    "action_ouvrir": "abrió",
    "action_bloquer": "bloqueó",
    "action_lever": "levantó el bloqueo de",
    "action_laisser_passer": "permitió un rato",
    "action_rattacher": "vinculó",
    "action_retirer_lien": "quitó el vínculo de",
    "action_lier_auto": "ajustó el vínculo automático de",
    "action_compte_partage": "cambió el estado compartido de",
    "aucune": "Nada coincide.",
    "chargement": "Cargando…",
    "erreur_admin": "Esta pantalla es solo para administradores y {decideurs}.",
    "erreur_defaut": "No se pudo realizar la operación.",
    "geste_fait": "Hecho."
  },
  "it": {
    "carte_nom": "Vision — Autorizzazioni",
    "carte_description": "App, siti e blocchi: lo stato reale, il motivo e le azioni per tutti, una persona o un dispositivo.",
    "titre": "Autorizzazioni",
    "regarder": "Guarda",
    "tout_le_monde": "Tutti",
    "toutes_personnes": "Tutte le persone",
    "tous_appareils": "Tutti i dispositivi",
    "filtre_personne": "Persona",
    "filtre_appareil": "Dispositivo",
    "resume": "Bloccate: {rouge} · parziali: {orange} · aperte: {vert}",
    "bloques_pour_tous": "Bloccato per tutti",
    "rien_bloque": "Niente è bloccato per tutti.",
    "lever": "Rimuovi il blocco",
    "non_levable": "Gestito dalla sicurezza di Vision",
    "confirmer_lever": "Rimuovere il blocco di {nom} per tutti?",
    "par_le": "{par}, il {date}",
    "chercher": "Cerca un'app o un sito…",
    "toutes_categories": "Tutte",
    "filtre_categorie": "Categoria",
    "applis": "Programmi e app",
    "sites_seuls": "Siti singoli",
    "categories": "Categorie",
    "cat_compte": "{applis} app, {sites} siti",
    "sites_one": "{n} sito",
    "sites_other": "{n} siti",
    "badge_vert": "Aperto",
    "badge_orange": "Parzialmente bloccato",
    "badge_rouge": "Bloccato",
    "lien_observe": "Osservato · {n} richieste · {jours} giorni · {part} %",
    "lien_secours": "Riserva (non osservato)",
    "lien_incertain": "Incerto (dispositivo senza attribuzione)",
    "lien_humain": "Collegato a mano",
    "a_verifier": "Da verificare",
    "en_attente": "In attesa di una decisione",
    "gestes": "Azioni",
    "ouvrir": "Apri",
    "bloquer": "Blocca",
    "laisser_passer": "Consenti per un po'",
    "duree_30": "30 min",
    "duree_60": "1 h",
    "duree_180": "3 h",
    "portee_tous": "Per tutti",
    "portee_personne": "Per {qui}",
    "portee_appareil": "Su {appareil}",
    "retirer_lien": "Rimuovi il collegamento",
    "rattacher": "Collega a…",
    "rattacher_choix": "A quale app collegare {site}?",
    "ajouter_site": "Aggiungi un sito",
    "site_indice": "esempio.it",
    "lier_auto_oui": "Collega da solo: sì",
    "lier_auto_non": "Collega da solo: no",
    "effet_aussi": "Effetto reale: {liste}",
    "effet_ouvert": "{appareil} aperto",
    "effet_ferme": "{appareil} chiuso",
    "code_titre": "Codice ({decideur})",
    "code_texte": "Questo account è condiviso: digitare il codice per aprire.",
    "code_faux": "Codice errato.",
    "valider": "Conferma",
    "annuler": "Annulla",
    "fermer": "Chiudi",
    "journal": "Chi ha fatto cosa",
    "journal_ligne": "{qui}: {action} {cible} ({portee})",
    "action_ouvrir": "ha aperto",
    "action_bloquer": "ha bloccato",
    "action_lever": "ha rimosso il blocco di",
    "action_laisser_passer": "ha consentito per un po'",
    "action_rattacher": "ha collegato",
    "action_retirer_lien": "ha rimosso il collegamento di",
    "action_lier_auto": "ha impostato il collegamento automatico di",
    "action_compte_partage": "ha cambiato lo stato condiviso di",
    "aucune": "Nessuna corrispondenza.",
    "chargement": "Caricamento…",
    "erreur_admin": "Questa schermata è riservata ad amministratori e {decideurs}.",
    "erreur_defaut": "Impossibile eseguire l'operazione.",
    "geste_fait": "Fatto."
  },
  "nl": {
    "carte_nom": "Vision — Toestemmingen",
    "carte_description": "Apps, sites en blokkades: de echte status, de reden en acties voor iedereen, één persoon of één apparaat.",
    "titre": "Toestemmingen",
    "regarder": "Bekijken",
    "tout_le_monde": "Iedereen",
    "toutes_personnes": "Alle personen",
    "tous_appareils": "Alle apparaten",
    "filtre_personne": "Persoon",
    "filtre_appareil": "Apparaat",
    "resume": "Geblokkeerd: {rouge} · gedeeltelijk: {orange} · open: {vert}",
    "bloques_pour_tous": "Geblokkeerd voor iedereen",
    "rien_bloque": "Voor iedereen is niets geblokkeerd.",
    "lever": "Blokkade opheffen",
    "non_levable": "Beheerd door de beveiliging van Vision",
    "confirmer_lever": "Blokkade van {nom} voor iedereen opheffen?",
    "par_le": "{par}, op {date}",
    "chercher": "Zoek een app of site…",
    "toutes_categories": "Alle",
    "filtre_categorie": "Categorie",
    "applis": "Programma's en apps",
    "sites_seuls": "Losse sites",
    "categories": "Categorieën",
    "cat_compte": "{applis} apps, {sites} sites",
    "sites_one": "{n} site",
    "sites_other": "{n} sites",
    "badge_vert": "Open",
    "badge_orange": "Gedeeltelijk geblokkeerd",
    "badge_rouge": "Geblokkeerd",
    "lien_observe": "Waargenomen · {n} verzoeken · {jours} dagen · {part} %",
    "lien_secours": "Noodoplossing (niet waargenomen)",
    "lien_incertain": "Onzeker (apparaat zonder toewijzing)",
    "lien_humain": "Handmatig gekoppeld",
    "a_verifier": "Te controleren",
    "en_attente": "Wacht op een beslissing",
    "gestes": "Acties",
    "ouvrir": "Openen",
    "bloquer": "Blokkeren",
    "laisser_passer": "Even toestaan",
    "duree_30": "30 min",
    "duree_60": "1 u",
    "duree_180": "3 u",
    "portee_tous": "Voor iedereen",
    "portee_personne": "Voor {qui}",
    "portee_appareil": "Op {appareil}",
    "retirer_lien": "Koppeling verwijderen",
    "rattacher": "Koppelen aan…",
    "rattacher_choix": "Aan welke app {site} koppelen?",
    "ajouter_site": "Site toevoegen",
    "site_indice": "voorbeeld.nl",
    "lier_auto_oui": "Zelf koppelen: ja",
    "lier_auto_non": "Zelf koppelen: nee",
    "effet_aussi": "Werkelijk effect: {liste}",
    "effet_ouvert": "{appareil} open",
    "effet_ferme": "{appareil} dicht",
    "code_titre": "Code ({decideur})",
    "code_texte": "Dit account is gedeeld: voer de code in om te openen.",
    "code_faux": "Onjuiste code.",
    "valider": "Bevestigen",
    "annuler": "Annuleren",
    "fermer": "Sluiten",
    "journal": "Wie deed wat",
    "journal_ligne": "{qui}: {action} {cible} ({portee})",
    "action_ouvrir": "opende",
    "action_bloquer": "blokkeerde",
    "action_lever": "hief de blokkade op van",
    "action_laisser_passer": "stond even toe",
    "action_rattacher": "koppelde",
    "action_retirer_lien": "verwijderde de koppeling van",
    "action_lier_auto": "stelde automatisch koppelen in voor",
    "action_compte_partage": "wijzigde de gedeelde status van",
    "aucune": "Niets komt overeen.",
    "chargement": "Laden…",
    "erreur_admin": "Dit scherm is voorbehouden aan beheerders en {decideurs}.",
    "erreur_defaut": "De bewerking kon niet worden uitgevoerd.",
    "geste_fait": "Gedaan."
  }
}/*i18n-json:fin*/;

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
const API = "pc_parental/autorisations";
const GESTE = "pc_parental/autorisations/geste";
const VIDE = { personne: "", appareil: "", categorie: "", q: "" };
const COULEURS = { rouge: 0, orange: 1, vert: 2 };
const esc = (t) =>
  String(t ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const messageErreur = (e) => {
  const b = e && e.body;
  if (e && (e.status_code === 403 || (b && b.error === "admin"))) return tr("erreur_admin");
  return (b && b.retour) || tr("erreur_defaut");
};
const dateCourte = (ts) => trDate(new Date(ts * 1000), { day: "2-digit", month: "2-digit" });

class VisionAutorisationsCard extends HTMLElement {
  setConfig(config) {
    this._config = config || {};
    this._cle = "vision-autorisations";
    this._f = Object.assign({}, VIDE, this._relire());
    this._d = null;
    this._ouvertes = new Set();
    this._modal = null;
    this._erreur = "";
    this._info = "";
    this._charge = false;
  }

  getCardSize() { return 10; }

  // Le choix de ce qu'on regarde est retenu dans le navigateur (jamais bloquant).
  _relire() {
    try {
      const brut = JSON.parse(localStorage.getItem(this._cle) || "{}");
      const f = {};
      for (const k of Object.keys(VIDE)) if (typeof brut[k] === "string") f[k] = brut[k];
      return f;
    } catch (e) { return {}; }
  }

  _retenir() {
    try { localStorage.setItem(this._cle, JSON.stringify(this._f)); } catch (e) { /* stockage refusé : tant pis */ }
  }

  set hass(h) {
    const premier = !this._hass;
    this._hass = h;
    const langue = suivreLangue(h);
    if (premier) {
      this.attachShadow({ mode: "open" });
      this._langue = langue;
      this._charger();
      this._minuteur = setInterval(() => { if (!this._modal) this._charger(true); }, 30000);
    } else if (langue !== this._langue) {
      this._langue = langue;
      this._rendre();
    }
  }

  disconnectedCallback() { clearInterval(this._minuteur); }

  async _charger(silencieux = false) {
    if (!this._hass) return;
    const q = new URLSearchParams();
    if (this._f.personne) q.set("personne", this._f.personne);
    if (this._f.appareil) q.set("appareil", this._f.appareil);
    const filtre = JSON.stringify([this._f.personne, this._f.appareil]);
    try {
      const r = await this._hass.callApi("GET", API + (q.toString() ? "?" + q.toString() : ""));
      if (filtre !== JSON.stringify([this._f.personne, this._f.appareil])) return;
      this._d = r;
      this._erreur = "";
    } catch (e) {
      if (silencieux && this._charge) return;
      // Un appareil ou une personne qui n'existe plus : on revient à « tout le monde ».
      if (e && e.status_code === 404 && (this._f.personne || this._f.appareil)) {
        this._f.personne = ""; this._f.appareil = ""; this._retenir(); return this._charger();
      }
      this._erreur = messageErreur(e);
    }
    this._charge = true;
    const q2 = this.shadowRoot.getElementById("q");
    if (silencieux && q2 && this.shadowRoot.activeElement === q2) return;  // pas sous les doigts
    this._rendre();
  }

  _regarder(champ, valeur) {
    this._f[champ] = valeur;
    if (champ === "personne") {
      const a = this._d && this._d.facettes.appareils.find((x) => x.id === this._f.appareil);
      if (this._f.appareil && (!valeur || (a && a.personne !== valeur))) this._f.appareil = "";
    }
    this._retenir();
    this._d = null; this._charge = false;
    this._rendre();
    this._charger();
  }

  // La portée d'un geste, à partir de ce qu'on regarde : « tous », la personne, l'appareil.
  _portees() {
    const d = this._d, f = this._f;
    const liste = [["tous", tr("portee_tous")]];
    if (f.personne && d) {
      const p = d.facettes.personnes.find((x) => x.id === f.personne);
      liste.push(["personne:" + f.personne, tr("portee_personne", { qui: p ? p.nom : f.personne })]);
    }
    if (f.appareil && d) {
      const a = d.facettes.appareils.find((x) => x.id === f.appareil);
      liste.push(["appareil:" + f.appareil, tr("portee_appareil", { appareil: a ? a.nom : f.appareil })]);
    }
    return liste;
  }

  // Un geste. Le serveur dit si un code est demandé (compte partagé) ou une confirmation (lever un blocage).
  async _geste(corps, code) {
    try {
      const r = await this._hass.callApi("POST", GESTE, code ? Object.assign({}, corps, { code_octroi: code }) : corps);
      this._modal = null;
      this._erreur = "";
      this._info = this._texteEffet(r) || tr("geste_fait");
      await this._charger();
    } catch (e) {
      const b = (e && e.body) || {};
      if (b.error === "confirmer") { this._modal = { type: "confirmer", corps, nom: corps.cible }; return this._rendre(); }
      if (b.error === "code_octroi" || b.error === "code_octroi_faux") {
        this._modal = { type: "code", corps, faux: b.error === "code_octroi_faux" };
        return this._rendre();
      }
      this._modal = null;
      this._erreur = messageErreur(e);
      this._rendre();
    }
  }

  // « Effet réel : TEL-LOU ouvert, PC-LOU fermé » : l'exception d'une personne vaut pour tous ses appareils.
  _texteEffet(r) {
    if (!r || !r.effet || !this._d) return "";
    const noms = Object.fromEntries(this._d.facettes.appareils.map((a) => [a.id, a.nom]));
    const liste = Object.entries(r.effet).map(([id, e]) => tr(e === "ouvert" ? "effet_ouvert" : "effet_ferme", { appareil: noms[id] || id }));
    return liste.length > 1 ? tr("effet_aussi", { liste: liste.join(", ") }) : "";
  }

  _pastille(b) { return `<span class="pastille ${esc(b)}" role="img" aria-label="${esc(tr("badge_" + b))}"></span>`; }

  _lienTexte(s) {
    const t = [];
    if (s.lien === "observe" && s.preuve) t.push(tr("lien_observe", { n: s.preuve.n, jours: s.preuve.jours, part: Math.round((s.preuve.part || 0) * 100) }));
    else if (s.lien === "secours") t.push(tr("lien_secours"));
    else if (s.lien === "incertain") t.push(tr("lien_incertain"));
    else if (s.lien === "humain") t.push(tr("lien_humain"));
    if (s.a_verifier) t.push(tr("a_verifier"));
    if (s.attente) t.push(tr("en_attente"));
    return t;
  }

  _bloquesPourTous() {
    const l = this._d.bloques_pour_tous || [];
    const lignes = l.map((b) => {
      const par = b.par ? " · " + tr("par_le", { par: b.par, date: dateCourte(b.quand) }) : "";
      const nom = b.etiquette ? trCat(b.site) : b.site;
      const bouton = b.levable
        ? `<button class="act" data-lever="${esc(b.site)}">${esc(tr("lever"))}</button>`
        : `<span class="sous">${esc(tr("non_levable"))}</span>`;
      return `<div class="ligne"><span class="pastille rouge" role="img" aria-label="${esc(tr("badge_rouge"))}"></span>
        <div class="corps"><b>${esc(nom)}</b><span class="sous">${esc(b.raison)}${esc(par)}</span></div>${bouton}</div>`;
    }).join("");
    return `<section class="bloc tous"><h3>${esc(tr("bloques_pour_tous"))} <span class="n">${l.length}</span></h3>${lignes || `<div class="vide">${esc(tr("rien_bloque"))}</div>`}</section>`;
  }

  _garde(texte, cats) {
    const f = this._f;
    if (f.categorie && !(cats || []).includes(f.categorie)) return false;
    return !f.q || texte.toLowerCase().includes(f.q.toLowerCase());
  }

  _ligneAppli(a) {
    const ouverte = this._ouvertes.has(a.nom);
    const cats = (a.categories || []).map((c) => `<span class="cat">${esc(trCat(c))}</span>`).join("");
    const sites = (a.sites || []).map((s) => {
      const infos = this._lienTexte(s).map((t) => `<span class="sous">${esc(t)}</span>`).join("");
      const raison = s.raison ? `<span class="sous">${esc(s.raison)}${s.portee ? " · " + esc(s.portee) : ""}</span>` : "";
      const retirer = ["observe", "secours", "incertain", "humain"].includes(s.lien)
        ? `<button class="act" data-retirer-lien="${esc(s.domaine)}" data-app="${esc(a.nom)}">${esc(tr("retirer_lien"))}</button>` : "";
      return `<div class="ligne site">${this._pastille(s.badge)}<div class="corps"><b>${esc(s.domaine)}</b>${infos}${raison}</div>
        <button class="act" data-feuille="site" data-cible="${esc(s.domaine)}" aria-label="${esc(tr("gestes"))} ${esc(s.domaine)}">⋯</button>${retirer}</div>`;
    }).join("");
    const bas = ouverte ? `<div class="sites">${sites}
      <div class="barre-site"><button class="act" data-ajouter="${esc(a.nom)}">${esc(tr("ajouter_site"))}</button></div></div>` : "";
    return `<div class="appli ${esc(a.badge)}">
      <div class="ligne">${this._pastille(a.badge)}
        <button class="nom" data-deplier="${esc(a.nom)}" aria-expanded="${ouverte}"><b>${ouverte ? "▾" : "▸"} ${esc(a.libelle)}</b></button>
        <div class="corps"><span class="etat ${esc(a.badge)}">${esc(tr("badge_" + a.badge))}${a.portee ? " · " + esc(a.portee) : ""}</span>
          ${a.raison && a.badge !== "vert" ? `<span class="sous">${esc(a.raison)}</span>` : ""}${cats ? `<span class="cats">${cats}</span>` : ""}</div>
        <span class="sous nbs">${esc(trn("sites", (a.sites || []).length))}</span>
        <button class="act" data-feuille="app" data-cible="${esc(a.nom)}" aria-label="${esc(tr("gestes"))} ${esc(a.libelle)}">⋯</button></div>${bas}</div>`;
  }

  _corps() {
    const d = this._d;
    const f = this._f;
    const fa = d.facettes;
    const appareils = fa.appareils.filter((a) => !f.personne || a.personne === f.personne);
    const opt = (liste, v, tous) => `<option value="">${esc(tous)}</option>` + liste.map(([c, l]) => `<option value="${esc(c)}" ${c === v ? "selected" : ""}>${esc(l)}</option>`).join("");
    const cats = d.categories.map((c) => c.nom);
    const chips = [["", tr("toutes_categories")]].concat(cats.map((c) => [c, trCat(c)])).map(([c, l]) =>
      `<button class="chip" data-cat="${esc(c)}" aria-pressed="${f.categorie === c}">${esc(l)}</button>`).join("");
    const applis = d.applis.filter((a) => this._garde(a.libelle + " " + a.nom + " " + a.sites.map((s) => s.domaine).join(" "), a.categories));
    const seuls = d.sites_seuls.filter((s) => this._garde(s.domaine, s.categories));
    const r = d.resume;
    const feuilleApps = d.applis.map((a) => [a.nom, a.libelle]);
    return `<div class="barre"><label>${esc(tr("filtre_personne"))}<select data-regarder="personne">${opt(fa.personnes.map((p) => [p.id, p.nom]), f.personne, tr("toutes_personnes"))}</select></label>
      <label>${esc(tr("filtre_appareil"))}<select data-regarder="appareil">${opt(appareils.map((a) => [a.id, a.nom]), f.appareil, tr("tous_appareils"))}</select></label></div>
      <div class="entete"><b>${esc(d.regarde)}</b><span class="sous">${esc(tr("resume", { rouge: r.rouge, orange: r.orange, vert: r.vert }))}</span></div>
      ${this._bloquesPourTous()}
      <div class="filtres"><input type="search" id="q" value="${esc(f.q)}" placeholder="${esc(tr("chercher"))}" aria-label="${esc(tr("chercher"))}">
        <div class="chips" role="group" aria-label="${esc(tr("filtre_categorie"))}">${chips}</div></div>
      <section class="bloc"><h3>${esc(tr("applis"))} <span class="n">${applis.length}</span></h3>${applis.map((a) => this._ligneAppli(a)).join("") || `<div class="vide">${esc(tr("aucune"))}</div>`}</section>
      <section class="bloc"><h3>${esc(tr("sites_seuls"))} <span class="n">${seuls.length}</span></h3>${seuls.map((s) => `<div class="ligne">${this._pastille(s.badge)}
        <div class="corps"><b>${esc(s.domaine)}</b>${s.raison ? `<span class="sous">${esc(s.raison)}</span>` : ""}</div>
        <button class="act" data-feuille="site" data-cible="${esc(s.domaine)}" aria-label="${esc(tr("gestes"))} ${esc(s.domaine)}">⋯</button>
        <button class="act" data-rattacher="${esc(s.domaine)}">${esc(tr("rattacher"))}</button></div>`).join("") || `<div class="vide">${esc(tr("aucune"))}</div>`}</section>
      <section class="bloc"><h3>${esc(tr("categories"))}</h3><div class="cats">${d.categories.map((c) => `<span class="cat">${esc(trCat(c.nom))} · ${esc(tr("cat_compte", { applis: c.applis, sites: c.sites }))}</span>`).join("")}</div></section>
      <section class="bloc"><h3>${esc(tr("journal"))}</h3>${(d.journal || []).slice(0, 8).map((j) => `<div class="sous">${esc(dateCourte(j.ts))} · ${esc(tr("journal_ligne", { qui: j.qui, action: tr("action_" + j.action), cible: j.cible, portee: j.portee }))}</div>`).join("") || `<div class="vide">${esc(tr("aucune"))}</div>`}</section>
      <datalist id="applis">${feuilleApps.map(([n, l]) => `<option value="${esc(n)}">${esc(l)}</option>`).join("")}</datalist>`;
  }

  _modale() {
    const m = this._modal;
    if (!m) return "";
    const portees = this._portees();
    const bouton = (cle, texte, extra = "") => `<button class="act" ${extra}>${esc(texte)}</button>`;
    let contenu = "";
    if (m.type === "feuille") {
      const nom = m.cible;
      const lignes = portees.map(([p, l]) => `<div class="rang"><span>${esc(l)}</span>
        <button class="act" data-g="ouvrir" data-p="${esc(p)}">${esc(tr("ouvrir"))}</button>
        <button class="act" data-g="bloquer" data-p="${esc(p)}">${esc(tr("bloquer"))}</button></div>`).join("");
      const passage = portees.slice(1).concat(portees.length === 1 ? portees : []).map(([p, l]) => `<div class="rang"><span>${esc(tr("laisser_passer"))} · ${esc(l)}</span>
        ${[30, 60, 180].map((mn) => `<button class="act" data-g="laisser_passer" data-p="${esc(p)}" data-d="${mn}">${esc(tr("duree_" + mn))}</button>`).join("")}</div>`).join("");
      const auto = m.genre === "app" ? (() => {
        const coupe = (this._d.liens_auto_coupes || []).includes(nom);
        return `<div class="rang"><button class="act" data-lier="${coupe ? "oui" : "non"}">${esc(tr(coupe ? "lier_auto_non" : "lier_auto_oui"))}</button></div>`;
      })() : "";
      contenu = `<h3>${esc(m.titre)}</h3>${lignes}${passage}${auto}<div class="rang fin"><button class="act" data-fermer>${esc(tr("fermer"))}</button></div>`;
    } else if (m.type === "code") {
      contenu = `<h3>${esc(tr("code_titre"))}</h3><p>${esc(tr("code_texte"))}</p>${m.faux ? `<p class="faux" role="alert">${esc(tr("code_faux"))}</p>` : ""}
        <input type="password" id="code" inputmode="numeric" autocomplete="off" aria-label="${esc(tr("code_titre"))}">
        <div class="rang fin"><button class="act" data-fermer>${esc(tr("annuler"))}</button><button class="act plein" data-valider-code>${esc(tr("valider"))}</button></div>`;
    } else if (m.type === "confirmer") {
      contenu = `<h3>${esc(tr("lever"))}</h3><p>${esc(tr("confirmer_lever", { nom: m.nom }))}</p>
        <div class="rang fin"><button class="act" data-fermer>${esc(tr("annuler"))}</button><button class="act plein" data-valider-confirmer>${esc(tr("valider"))}</button></div>`;
    } else if (m.type === "rattacher") {
      contenu = `<h3>${esc(tr("rattacher_choix", { site: m.cible }))}</h3>
        <input list="applis" id="appchoix" aria-label="${esc(tr("rattacher"))}" autocomplete="off">
        <div class="rang fin"><button class="act" data-fermer>${esc(tr("annuler"))}</button><button class="act plein" data-valider-rattacher>${esc(tr("valider"))}</button></div>`;
    } else if (m.type === "ajouter") {
      contenu = `<h3>${esc(tr("ajouter_site"))}</h3>
        <input type="text" id="siteajout" placeholder="${esc(tr("site_indice"))}" aria-label="${esc(tr("ajouter_site"))}" autocomplete="off">
        <div class="rang fin"><button class="act" data-fermer>${esc(tr("annuler"))}</button><button class="act plein" data-valider-ajouter>${esc(tr("valider"))}</button></div>`;
    }
    return `<div class="voile" data-voile><div class="boite" role="dialog" aria-modal="true">${contenu}</div></div>`;
  }

  _rendre() {
    if (!this.shadowRoot) return;
    let corps;
    if (this._erreur && !this._d) corps = `<div class="vide" role="alert">${esc(this._erreur)}</div>`;
    else if (!this._d) corps = `<div class="vide">${esc(tr("chargement"))}</div>`;
    else corps = (this._erreur ? `<div class="alerte" role="alert">${esc(this._erreur)}</div>` : "") +
      (this._info ? `<div class="info" role="status">${esc(this._info)}</div>` : "") + this._corps();
    const q = this.shadowRoot.getElementById("q");
    const garde = q && this.shadowRoot.activeElement === q ? [q.selectionStart, q.selectionEnd] : null;
    this.shadowRoot.innerHTML = `<style>${this._style()}</style><ha-card><div class="titre">${esc(this._config.title || tr("titre"))}</div>${corps}${this._d ? this._modale() : ""}</ha-card>`;
    this._brancher();
    if (garde) {
      const neuf = this.shadowRoot.getElementById("q");
      if (neuf) { neuf.focus(); neuf.setSelectionRange(garde[0], garde[1]); }
    }
    const c = this.shadowRoot.getElementById("code") || this.shadowRoot.getElementById("siteajout") || this.shadowRoot.getElementById("appchoix");
    if (c) c.focus();
  }

  _brancher() {
    const r = this.shadowRoot;
    const sur = (sel, ev, f) => r.querySelectorAll(sel).forEach((e) => e.addEventListener(ev, () => f(e)));
    const trouver = (nom) => (this._d.applis.find((a) => a.nom === nom) || {}).libelle || nom;
    r.querySelectorAll("select[data-regarder]").forEach((s) => s.addEventListener("change", () => this._regarder(s.dataset.regarder, s.value)));
    const q = r.getElementById("q");
    if (q) q.addEventListener("input", () => { clearTimeout(this._attente); this._attente = setTimeout(() => { this._f.q = q.value; this._retenir(); this._rendre(); }, 200); });
    sur("button[data-cat]", "click", (b) => { this._f.categorie = this._f.categorie === b.dataset.cat ? "" : b.dataset.cat; this._retenir(); this._rendre(); });
    sur("button[data-deplier]", "click", (b) => { const n = b.dataset.deplier; if (!this._ouvertes.delete(n)) this._ouvertes.add(n); this._rendre(); });
    sur("button[data-lever]", "click", (b) => this._geste({ action: "lever", cible: b.dataset.lever }));
    sur("button[data-feuille]", "click", (b) => {
      const genre = b.dataset.feuille;
      this._modal = { type: "feuille", genre, cible: b.dataset.cible, titre: genre === "app" ? trouver(b.dataset.cible) : b.dataset.cible };
      this._rendre();
    });
    sur("button[data-retirer-lien]", "click", (b) => this._geste({ action: "retirer_lien", app: b.dataset.app, cible: b.dataset.retirerLien }));
    sur("button[data-rattacher]", "click", (b) => { this._modal = { type: "rattacher", cible: b.dataset.rattacher }; this._rendre(); });
    sur("button[data-ajouter]", "click", (b) => { this._modal = { type: "ajouter", app: b.dataset.ajouter }; this._rendre(); });
    // La feuille et ses fenêtres.
    sur("button[data-fermer]", "click", () => { this._modal = null; this._rendre(); });
    const voile = r.querySelector("[data-voile]");
    if (voile) voile.addEventListener("click", (e) => { if (e.target === voile) { this._modal = null; this._rendre(); } });
    const m = this._modal;
    if (!m) return;
    sur("button[data-g]", "click", (b) => {
      const corps = { action: b.dataset.g, cible: m.genre === "app" ? "app:" + m.cible : m.cible, portee: b.dataset.p };
      if (b.dataset.d) corps.duree = Number(b.dataset.d);
      this._geste(corps);
    });
    sur("button[data-lier]", "click", (b) => this._geste({ action: "lier_auto", app: m.cible, oui: b.dataset.lier === "oui" }));
    sur("button[data-valider-code]", "click", () => { const v = (r.getElementById("code") || {}).value || ""; this._geste(m.corps, v); });
    sur("button[data-valider-confirmer]", "click", () => this._geste(Object.assign({}, m.corps, { confirmer: true })));
    sur("button[data-valider-rattacher]", "click", () => {
      const app = ((r.getElementById("appchoix") || {}).value || "").trim();
      if (app) this._geste({ action: "rattacher", app, cible: m.cible });
    });
    sur("button[data-valider-ajouter]", "click", () => {
      const site = ((r.getElementById("siteajout") || {}).value || "").trim();
      if (site) this._geste({ action: "rattacher", app: m.app, cible: site });
    });
    const champ = r.getElementById("code");
    if (champ) champ.addEventListener("keydown", (e) => { if (e.key === "Enter") this._geste(m.corps, champ.value); });
  }

  _style() {
    return `
      :host { display:block; }
      ha-card { padding:12px 16px 16px; position:relative; container-type:inline-size; }
      .titre { font-size:1.15rem; font-weight:500; margin-bottom:10px; }
      .barre { display:flex; flex-wrap:wrap; gap:8px; margin-bottom:8px; }
      .barre label { display:flex; flex-direction:column; gap:2px; flex:1 1 220px; min-width:0; font-size:.8rem; color:var(--secondary-text-color); }
      select, input[type=search], input[type=text], input[type=password], input[list] { min-height:44px; box-sizing:border-box; width:100%; min-width:0; padding:6px 10px; border:1px solid var(--divider-color); border-radius:8px; background:var(--card-background-color); color:var(--primary-text-color); font:inherit; }
      .entete { display:flex; flex-direction:column; gap:2px; margin:6px 0 10px; }
      .sous { font-size:.8rem; color:var(--secondary-text-color); overflow-wrap:anywhere; }
      .bloc { margin:12px 0; }
      .bloc h3 { font-size:1rem; font-weight:500; margin:0 0 6px; }
      .bloc.tous { border:1px solid var(--error-color,#c62828); border-radius:10px; padding:8px 10px; }
      .n { font-weight:400; color:var(--secondary-text-color); font-size:.85em; }
      .ligne { display:flex; gap:10px; align-items:center; padding:8px 2px; border-bottom:1px solid var(--divider-color); flex-wrap:wrap; }
      .ligne.site { padding-left:22px; }
      .corps { flex:1 1 140px; min-width:0; display:flex; flex-direction:column; gap:2px; overflow-wrap:anywhere; }
      .nom { flex:0 1 auto; background:none; border:none; color:var(--primary-text-color); font:inherit; cursor:pointer; min-height:44px; padding:0 4px; text-align:left; }
      .nbs { flex:none; }
      .pastille { flex:none; width:12px; height:12px; border-radius:50%; background:var(--success-color,#2e7d32); }
      .pastille.rouge { background:var(--error-color,#c62828); }
      .pastille.orange { background:var(--warning-color,#ef6c00); }
      .etat.rouge { color:var(--error-color,#c62828); font-weight:500; }
      .etat.orange { color:var(--warning-color,#ef6c00); font-weight:500; }
      .etat.vert { color:var(--success-color,#2e7d32); font-weight:500; }
      .cats { display:flex; flex-wrap:wrap; gap:4px; }
      .cat { font-size:.72rem; padding:1px 8px; border-radius:10px; border:1px solid var(--divider-color); color:var(--secondary-text-color); }
      .filtres { margin:12px 0 4px; display:flex; flex-direction:column; gap:8px; }
      .chips { display:flex; flex-wrap:wrap; gap:6px; }
      .chip, .act { min-height:44px; padding:6px 14px; border:1px solid var(--divider-color); border-radius:22px; background:none; color:var(--primary-text-color); font:inherit; cursor:pointer; }
      .chip[aria-pressed="true"], .act.plein { background:var(--primary-color); color:var(--text-primary-color,#fff); border-color:var(--primary-color); }
      .act { border-radius:8px; padding:6px 12px; }
      .vide { padding:12px 4px; color:var(--secondary-text-color); }
      .alerte { padding:8px 12px; margin-bottom:8px; border-radius:8px; border:1px solid var(--error-color,#c62828); color:var(--error-color,#c62828); }
      .info { padding:8px 12px; margin-bottom:8px; border-radius:8px; border:1px solid var(--divider-color); color:var(--secondary-text-color); }
      .barre-site { padding:8px 0 4px 22px; }
      .voile { position:fixed; inset:0; background:rgba(0,0,0,.45); display:flex; align-items:flex-end; justify-content:center; z-index:9; }
      .boite { background:var(--card-background-color); color:var(--primary-text-color); width:min(520px,100%); max-height:85vh; overflow:auto; box-sizing:border-box; padding:16px; border-radius:16px 16px 0 0; }
      .boite h3 { margin:0 0 8px; font-size:1.05rem; font-weight:500; overflow-wrap:anywhere; }
      .rang { display:flex; flex-wrap:wrap; gap:8px; align-items:center; padding:6px 0; }
      .rang > span { flex:1 1 100%; font-size:.85rem; color:var(--secondary-text-color); }
      .rang.fin { justify-content:flex-end; }
      .faux { color:var(--error-color,#c62828); }
      @container (min-width: 700px) { .voile { align-items:center; } .boite { border-radius:16px; } }
    `;
  }
}

// Un seul exemplaire, même si une ancienne copie est encore chargée à côté.
const _dejaVisionAutorisationsCard = !!customElements.get("vision-autorisations-card");
if (!_dejaVisionAutorisationsCard) customElements.define("vision-autorisations-card", VisionAutorisationsCard);
window.customCards = window.customCards || [];
if (!_dejaVisionAutorisationsCard) window.customCards.push({
  type: "vision-autorisations-card",
  name: tr("carte_nom"),
  description: tr("carte_description"),
});
