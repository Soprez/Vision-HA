/*
 * Vision — carte « Fiche d'une personne » (refonte UX, lot 3 : B2, HA-05, HA-20, EX-05), réservée aux administrateurs.
 *
 * Tout ce qui concerne une personne au même endroit, en onglets avec texte : Résumé (temps, appareils, limites),
 * Horaires (planning), Contenus (catégories, règles selon le lieu), Réveils, Récompenses (temps gagné), Sur ses écrans
 * (widgets HA), Rôle ; ‹ › pour passer d'une personne à l'autre. Les cartes existantes y sont intégrées telles quelles
 * (personne, planning, widgets) ; les réglages qui n'existaient que dans Configurer se font ici (API personne/admin).
 */
// ---- i18n:debut — les textes de cette carte, dans les six langues de Vision (fr source, en repli) ----
const TEXTES = /*i18n-json*/{
  "fr": {
    "octroi_titre": "Code de déverrouillage",
    "octroi_champ": "Code",
    "octroi_valider": "Valider",
    "octroi_annuler": "Annuler",
    "onglet_resume": "Résumé",
    "onglet_horaires": "Horaires",
    "onglet_contenus": "Contenus",
    "onglet_reveils": "Réveils",
    "onglet_recompenses": "Récompenses",
    "onglet_ecrans": "Sur ses écrans",
    "onglet_role": "Rôle",
    "onglets": "Sections de la fiche de {prenom}",
    "precedente": "Personne précédente : {prenom}",
    "suivante": "Personne suivante : {prenom}",
    "retour": "Retour à Aujourd'hui",
    "chargement": "Chargement…",
    "erreur_defaut": "Action impossible pour le moment.",
    "erreur_admin": "Réservé aux administrateurs de Home Assistant.",
    "erreur_loading": "Vision démarre : réessayez dans un instant.",
    "erreur_personne": "Cette personne n'existe plus dans Home Assistant.",
    "geste_en_cours": "En cours…",
    "geste_fait": "C'est fait.",
    "role_titre": "Rôle",
    "role_aide": "Ce que {prenom} peut faire dans Vision. Le rôle vaut pour tous ses appareils, sauf exception posée sur un appareil.",
    "role_defaut": "Rôle par défaut ({role})",
    "role_actuel": "Rôle actuel : {role}",
    "enregistrer": "Enregistrer",
    "annuler": "Annuler",
    "modifier": "Modifier",
    "retirer": "Retirer",
    "aucun_planning": "Aucun appareil n'appartient encore à {prenom} : les horaires se règlent sur son appareil.",
    "regles_titre": "Règles selon le lieu",
    "regles_aide": "Tant que {prenom} est dans une zone de Home Assistant, ces catégories sont fermées sur ses appareils.",
    "regles_aucune": "Aucune règle selon le lieu.",
    "regle_ligne": "{zone} : {categories}",
    "regle_zone": "Zone",
    "regle_categories": "Catégories fermées dans cette zone",
    "regle_ajouter": "Ajouter une règle",
    "aucune_zone": "Aucune zone dans Home Assistant (Paramètres → Zones).",
    "aucune_categorie": "Aucune catégorie pour l'instant.",
    "reveils_aide": "Les réveils de {prenom}. Ils se créent depuis l'appli Vision ou par une automatisation.",
    "reveils_aucun": "Aucun réveil.",
    "reveil_sans_nom": "Réveil",
    "reveil_actif": "Activé",
    "reveil_inactif": "Désactivé",
    "reveil_prochain": "Prochaine sonnerie : {quand}",
    "reveil_aucun_prochain": "Pas de prochaine sonnerie.",
    "reveil_sur": "Sur : {appareils}",
    "reveil_activer": "Activer",
    "reveil_desactiver": "Désactiver",
    "reveil_sauter": "Sauter la prochaine sonnerie",
    "reveil_reprendre": "Reprendre le {jour}",
    "recompenses_aide": "Chaque tâche cochée dans une liste de tâches donne du temps en plus sur les appareils de {prenom}.",
    "recompense_liste": "Liste de tâches",
    "recompense_minutes": "Minutes par tâche",
    "recompense_max": "Au plus par jour (minutes)",
    "recompense_aujourdhui_one": "{n} minute gagnée aujourd'hui.",
    "recompense_aujourdhui_other": "{n} minutes gagnées aujourd'hui.",
    "recompense_retirer": "Retirer les récompenses",
    "recompense_aucune": "Aucune récompense réglée.",
    "recompense_aucune_liste": "Aucune liste de tâches dans Home Assistant : ajoutez l'intégration « Liste de tâches ».",
    "ecrans_aide": "Ce que {prenom} voit de Home Assistant dans Vision : accueil et widgets de ses appareils.",
    "carte_nom": "Vision — fiche d'une personne",
    "carte_description": "Résumé, horaires, contenus, réveils, récompenses, écrans et rôle d'une personne."
  },
  "en": {
    "octroi_titre": "Unlock code",
    "octroi_champ": "Code",
    "octroi_valider": "Confirm",
    "octroi_annuler": "Cancel",
    "onglet_resume": "Summary",
    "onglet_horaires": "Schedule",
    "onglet_contenus": "Content",
    "onglet_reveils": "Alarms",
    "onglet_recompenses": "Rewards",
    "onglet_ecrans": "On their screens",
    "onglet_role": "Role",
    "onglets": "Sections of {prenom}'s profile",
    "precedente": "Previous person: {prenom}",
    "suivante": "Next person: {prenom}",
    "retour": "Back to Today",
    "chargement": "Loading…",
    "erreur_defaut": "This action isn't possible right now.",
    "erreur_admin": "Reserved for Home Assistant administrators.",
    "erreur_loading": "Vision is starting: try again in a moment.",
    "erreur_personne": "This person no longer exists in Home Assistant.",
    "geste_en_cours": "In progress…",
    "geste_fait": "Done.",
    "role_titre": "Role",
    "role_aide": "What {prenom} can do in Vision. The role applies to all of their devices, unless an exception is set on a device.",
    "role_defaut": "Default role ({role})",
    "role_actuel": "Current role: {role}",
    "enregistrer": "Save",
    "annuler": "Cancel",
    "modifier": "Edit",
    "retirer": "Remove",
    "aucun_planning": "No device belongs to {prenom} yet: the schedule is set on their device.",
    "regles_titre": "Location rules",
    "regles_aide": "While {prenom} is in a Home Assistant zone, these categories are closed on their devices.",
    "regles_aucune": "No location rule.",
    "regle_ligne": "{zone}: {categories}",
    "regle_zone": "Zone",
    "regle_categories": "Categories closed in this zone",
    "regle_ajouter": "Add a rule",
    "aucune_zone": "No zone in Home Assistant (Settings → Zones).",
    "aucune_categorie": "No category yet.",
    "reveils_aide": "{prenom}'s alarms. They are created from the Vision app or by an automation.",
    "reveils_aucun": "No alarm.",
    "reveil_sans_nom": "Alarm",
    "reveil_actif": "On",
    "reveil_inactif": "Off",
    "reveil_prochain": "Next ring: {quand}",
    "reveil_aucun_prochain": "No upcoming ring.",
    "reveil_sur": "On: {appareils}",
    "reveil_activer": "Turn on",
    "reveil_desactiver": "Turn off",
    "reveil_sauter": "Skip the next ring",
    "reveil_reprendre": "Resume on {jour}",
    "recompenses_aide": "Each task checked in a to-do list gives extra time on {prenom}'s devices.",
    "recompense_liste": "To-do list",
    "recompense_minutes": "Minutes per task",
    "recompense_max": "At most per day (minutes)",
    "recompense_aujourdhui_one": "{n} minute earned today.",
    "recompense_aujourdhui_other": "{n} minutes earned today.",
    "recompense_retirer": "Remove rewards",
    "recompense_aucune": "No reward set.",
    "recompense_aucune_liste": "No to-do list in Home Assistant: add the “To-do list” integration.",
    "ecrans_aide": "What {prenom} sees of Home Assistant in Vision: home screen and widgets of their devices.",
    "carte_nom": "Vision — person profile",
    "carte_description": "Summary, schedule, content, alarms, rewards, screens and role of a person."
  },
  "de": {
    "octroi_titre": "Entsperrcode",
    "octroi_champ": "Code",
    "octroi_valider": "Bestätigen",
    "octroi_annuler": "Abbrechen",
    "onglet_resume": "Übersicht",
    "onglet_horaires": "Zeiten",
    "onglet_contenus": "Inhalte",
    "onglet_reveils": "Wecker",
    "onglet_recompenses": "Belohnungen",
    "onglet_ecrans": "Auf den Bildschirmen",
    "onglet_role": "Rolle",
    "onglets": "Bereiche des Profils von {prenom}",
    "precedente": "Vorherige Person: {prenom}",
    "suivante": "Nächste Person: {prenom}",
    "retour": "Zurück zu Heute",
    "chargement": "Wird geladen …",
    "erreur_defaut": "Diese Aktion ist gerade nicht möglich.",
    "erreur_admin": "Nur für Administratoren von Home Assistant.",
    "erreur_loading": "Vision startet: versuche es gleich noch einmal.",
    "erreur_personne": "Diese Person gibt es in Home Assistant nicht mehr.",
    "geste_en_cours": "Läuft …",
    "geste_fait": "Erledigt.",
    "role_titre": "Rolle",
    "role_aide": "Was {prenom} in Vision tun darf. Die Rolle gilt für alle Geräte, außer bei einer Ausnahme auf einem Gerät.",
    "role_defaut": "Standardrolle ({role})",
    "role_actuel": "Aktuelle Rolle: {role}",
    "enregistrer": "Speichern",
    "annuler": "Abbrechen",
    "modifier": "Ändern",
    "retirer": "Entfernen",
    "aucun_planning": "{prenom} gehört noch kein Gerät: Die Zeiten werden auf dem eigenen Gerät festgelegt.",
    "regles_titre": "Ortsabhängige Regeln",
    "regles_aide": "Solange {prenom} in einer Zone von Home Assistant ist, sind diese Kategorien auf den Geräten gesperrt.",
    "regles_aucune": "Keine ortsabhängige Regel.",
    "regle_ligne": "{zone}: {categories}",
    "regle_zone": "Zone",
    "regle_categories": "In dieser Zone gesperrte Kategorien",
    "regle_ajouter": "Regel hinzufügen",
    "aucune_zone": "Keine Zone in Home Assistant (Einstellungen → Zonen).",
    "aucune_categorie": "Noch keine Kategorie.",
    "reveils_aide": "Die Wecker von {prenom}. Sie werden in der Vision-App oder per Automatisierung erstellt.",
    "reveils_aucun": "Kein Wecker.",
    "reveil_sans_nom": "Wecker",
    "reveil_actif": "Ein",
    "reveil_inactif": "Aus",
    "reveil_prochain": "Nächstes Klingeln: {quand}",
    "reveil_aucun_prochain": "Kein nächstes Klingeln.",
    "reveil_sur": "Auf: {appareils}",
    "reveil_activer": "Einschalten",
    "reveil_desactiver": "Ausschalten",
    "reveil_sauter": "Nächstes Klingeln überspringen",
    "reveil_reprendre": "Ab {jour} fortsetzen",
    "recompenses_aide": "Jede abgehakte Aufgabe in einer Aufgabenliste gibt mehr Zeit auf den Geräten von {prenom}.",
    "recompense_liste": "Aufgabenliste",
    "recompense_minutes": "Minuten pro Aufgabe",
    "recompense_max": "Höchstens pro Tag (Minuten)",
    "recompense_aujourdhui_one": "Heute {n} Minute verdient.",
    "recompense_aujourdhui_other": "Heute {n} Minuten verdient.",
    "recompense_retirer": "Belohnungen entfernen",
    "recompense_aucune": "Keine Belohnung eingestellt.",
    "recompense_aucune_liste": "Keine Aufgabenliste in Home Assistant: Füge die Integration „To-do-Liste“ hinzu.",
    "ecrans_aide": "Was {prenom} in Vision von Home Assistant sieht: Startbildschirm und Widgets der Geräte.",
    "carte_nom": "Vision — Personenprofil",
    "carte_description": "Übersicht, Zeiten, Inhalte, Wecker, Belohnungen, Bildschirme und Rolle einer Person."
  },
  "es": {
    "octroi_titre": "Código de desbloqueo",
    "octroi_champ": "Código",
    "octroi_valider": "Confirmar",
    "octroi_annuler": "Cancelar",
    "onglet_resume": "Resumen",
    "onglet_horaires": "Horarios",
    "onglet_contenus": "Contenidos",
    "onglet_reveils": "Alarmas",
    "onglet_recompenses": "Recompensas",
    "onglet_ecrans": "En sus pantallas",
    "onglet_role": "Rol",
    "onglets": "Secciones de la ficha de {prenom}",
    "precedente": "Persona anterior: {prenom}",
    "suivante": "Persona siguiente: {prenom}",
    "retour": "Volver a Hoy",
    "chargement": "Cargando…",
    "erreur_defaut": "Esta acción no es posible ahora.",
    "erreur_admin": "Reservado a los administradores de Home Assistant.",
    "erreur_loading": "Vision se está iniciando: inténtalo de nuevo en un momento.",
    "erreur_personne": "Esta persona ya no existe en Home Assistant.",
    "geste_en_cours": "En curso…",
    "geste_fait": "Hecho.",
    "role_titre": "Rol",
    "role_aide": "Lo que {prenom} puede hacer en Vision. El rol vale para todos sus dispositivos, salvo excepción en un dispositivo.",
    "role_defaut": "Rol por defecto ({role})",
    "role_actuel": "Rol actual: {role}",
    "enregistrer": "Guardar",
    "annuler": "Cancelar",
    "modifier": "Modificar",
    "retirer": "Quitar",
    "aucun_planning": "Ningún dispositivo pertenece todavía a {prenom}: los horarios se ajustan en su dispositivo.",
    "regles_titre": "Reglas según el lugar",
    "regles_aide": "Mientras {prenom} esté en una zona de Home Assistant, estas categorías se cierran en sus dispositivos.",
    "regles_aucune": "Ninguna regla según el lugar.",
    "regle_ligne": "{zone}: {categories}",
    "regle_zone": "Zona",
    "regle_categories": "Categorías cerradas en esta zona",
    "regle_ajouter": "Añadir una regla",
    "aucune_zone": "Ninguna zona en Home Assistant (Ajustes → Zonas).",
    "aucune_categorie": "Todavía no hay categorías.",
    "reveils_aide": "Las alarmas de {prenom}. Se crean desde la app Vision o mediante una automatización.",
    "reveils_aucun": "Ninguna alarma.",
    "reveil_sans_nom": "Alarma",
    "reveil_actif": "Activada",
    "reveil_inactif": "Desactivada",
    "reveil_prochain": "Próximo sonido: {quand}",
    "reveil_aucun_prochain": "Sin próximo sonido.",
    "reveil_sur": "En: {appareils}",
    "reveil_activer": "Activar",
    "reveil_desactiver": "Desactivar",
    "reveil_sauter": "Saltar el próximo sonido",
    "reveil_reprendre": "Reanudar el {jour}",
    "recompenses_aide": "Cada tarea marcada en una lista de tareas da tiempo extra en los dispositivos de {prenom}.",
    "recompense_liste": "Lista de tareas",
    "recompense_minutes": "Minutos por tarea",
    "recompense_max": "Como máximo al día (minutos)",
    "recompense_aujourdhui_one": "{n} minuto ganado hoy.",
    "recompense_aujourdhui_other": "{n} minutos ganados hoy.",
    "recompense_retirer": "Quitar las recompensas",
    "recompense_aucune": "Ninguna recompensa ajustada.",
    "recompense_aucune_liste": "Ninguna lista de tareas en Home Assistant: añade la integración «Lista de tareas».",
    "ecrans_aide": "Lo que {prenom} ve de Home Assistant en Vision: inicio y widgets de sus dispositivos.",
    "carte_nom": "Vision — ficha de una persona",
    "carte_description": "Resumen, horarios, contenidos, alarmas, recompensas, pantallas y rol de una persona."
  },
  "it": {
    "octroi_titre": "Codice di sblocco",
    "octroi_champ": "Codice",
    "octroi_valider": "Conferma",
    "octroi_annuler": "Annulla",
    "onglet_resume": "Riepilogo",
    "onglet_horaires": "Orari",
    "onglet_contenus": "Contenuti",
    "onglet_reveils": "Sveglie",
    "onglet_recompenses": "Ricompense",
    "onglet_ecrans": "Sui suoi schermi",
    "onglet_role": "Ruolo",
    "onglets": "Sezioni della scheda di {prenom}",
    "precedente": "Persona precedente: {prenom}",
    "suivante": "Persona successiva: {prenom}",
    "retour": "Torna a Oggi",
    "chargement": "Caricamento…",
    "erreur_defaut": "Questa azione non è possibile ora.",
    "erreur_admin": "Riservato agli amministratori di Home Assistant.",
    "erreur_loading": "Vision si sta avviando: riprova tra un attimo.",
    "erreur_personne": "Questa persona non esiste più in Home Assistant.",
    "geste_en_cours": "In corso…",
    "geste_fait": "Fatto.",
    "role_titre": "Ruolo",
    "role_aide": "Cosa può fare {prenom} in Vision. Il ruolo vale per tutti i suoi dispositivi, salvo eccezione su un dispositivo.",
    "role_defaut": "Ruolo predefinito ({role})",
    "role_actuel": "Ruolo attuale: {role}",
    "enregistrer": "Salva",
    "annuler": "Annulla",
    "modifier": "Modifica",
    "retirer": "Rimuovi",
    "aucun_planning": "Nessun dispositivo appartiene ancora a {prenom}: gli orari si impostano sul suo dispositivo.",
    "regles_titre": "Regole in base al luogo",
    "regles_aide": "Finché {prenom} è in una zona di Home Assistant, queste categorie sono chiuse sui suoi dispositivi.",
    "regles_aucune": "Nessuna regola in base al luogo.",
    "regle_ligne": "{zone}: {categories}",
    "regle_zone": "Zona",
    "regle_categories": "Categorie chiuse in questa zona",
    "regle_ajouter": "Aggiungi una regola",
    "aucune_zone": "Nessuna zona in Home Assistant (Impostazioni → Zone).",
    "aucune_categorie": "Ancora nessuna categoria.",
    "reveils_aide": "Le sveglie di {prenom}. Si creano dall'app Vision o con un'automazione.",
    "reveils_aucun": "Nessuna sveglia.",
    "reveil_sans_nom": "Sveglia",
    "reveil_actif": "Attiva",
    "reveil_inactif": "Disattivata",
    "reveil_prochain": "Prossimo suono: {quand}",
    "reveil_aucun_prochain": "Nessun prossimo suono.",
    "reveil_sur": "Su: {appareils}",
    "reveil_activer": "Attiva",
    "reveil_desactiver": "Disattiva",
    "reveil_sauter": "Salta il prossimo suono",
    "reveil_reprendre": "Riprendi il {jour}",
    "recompenses_aide": "Ogni attività spuntata in una lista di attività dà tempo in più sui dispositivi di {prenom}.",
    "recompense_liste": "Lista di attività",
    "recompense_minutes": "Minuti per attività",
    "recompense_max": "Al massimo al giorno (minuti)",
    "recompense_aujourdhui_one": "{n} minuto guadagnato oggi.",
    "recompense_aujourdhui_other": "{n} minuti guadagnati oggi.",
    "recompense_retirer": "Rimuovi le ricompense",
    "recompense_aucune": "Nessuna ricompensa impostata.",
    "recompense_aucune_liste": "Nessuna lista di attività in Home Assistant: aggiungi l'integrazione «Lista di cose da fare».",
    "ecrans_aide": "Cosa vede {prenom} di Home Assistant in Vision: schermata iniziale e widget dei suoi dispositivi.",
    "carte_nom": "Vision — scheda di una persona",
    "carte_description": "Riepilogo, orari, contenuti, sveglie, ricompense, schermi e ruolo di una persona."
  },
  "nl": {
    "octroi_titre": "Ontgrendelcode",
    "octroi_champ": "Code",
    "octroi_valider": "Bevestigen",
    "octroi_annuler": "Annuleren",
    "onglet_resume": "Overzicht",
    "onglet_horaires": "Tijden",
    "onglet_contenus": "Inhoud",
    "onglet_reveils": "Wekkers",
    "onglet_recompenses": "Beloningen",
    "onglet_ecrans": "Op de schermen",
    "onglet_role": "Rol",
    "onglets": "Onderdelen van het profiel van {prenom}",
    "precedente": "Vorige persoon: {prenom}",
    "suivante": "Volgende persoon: {prenom}",
    "retour": "Terug naar Vandaag",
    "chargement": "Laden…",
    "erreur_defaut": "Deze actie is nu niet mogelijk.",
    "erreur_admin": "Alleen voor beheerders van Home Assistant.",
    "erreur_loading": "Vision start op: probeer het zo opnieuw.",
    "erreur_personne": "Deze persoon bestaat niet meer in Home Assistant.",
    "geste_en_cours": "Bezig…",
    "geste_fait": "Klaar.",
    "role_titre": "Rol",
    "role_aide": "Wat {prenom} in Vision mag doen. De rol geldt voor alle apparaten, behalve bij een uitzondering op een apparaat.",
    "role_defaut": "Standaardrol ({role})",
    "role_actuel": "Huidige rol: {role}",
    "enregistrer": "Opslaan",
    "annuler": "Annuleren",
    "modifier": "Wijzigen",
    "retirer": "Verwijderen",
    "aucun_planning": "Er hoort nog geen apparaat bij {prenom}: de tijden stel je in op het eigen apparaat.",
    "regles_titre": "Locatieregels",
    "regles_aide": "Zolang {prenom} in een zone van Home Assistant is, zijn deze categorieën op de apparaten gesloten.",
    "regles_aucune": "Geen locatieregel.",
    "regle_ligne": "{zone}: {categories}",
    "regle_zone": "Zone",
    "regle_categories": "Gesloten categorieën in deze zone",
    "regle_ajouter": "Regel toevoegen",
    "aucune_zone": "Geen zone in Home Assistant (Instellingen → Zones).",
    "aucune_categorie": "Nog geen categorie.",
    "reveils_aide": "De wekkers van {prenom}. Ze worden gemaakt in de Vision-app of met een automatisering.",
    "reveils_aucun": "Geen wekker.",
    "reveil_sans_nom": "Wekker",
    "reveil_actif": "Aan",
    "reveil_inactif": "Uit",
    "reveil_prochain": "Volgende keer: {quand}",
    "reveil_aucun_prochain": "Geen volgende keer.",
    "reveil_sur": "Op: {appareils}",
    "reveil_activer": "Aanzetten",
    "reveil_desactiver": "Uitzetten",
    "reveil_sauter": "Volgende keer overslaan",
    "reveil_reprendre": "Hervatten op {jour}",
    "recompenses_aide": "Elke afgevinkte taak in een takenlijst geeft extra tijd op de apparaten van {prenom}.",
    "recompense_liste": "Takenlijst",
    "recompense_minutes": "Minuten per taak",
    "recompense_max": "Hoogstens per dag (minuten)",
    "recompense_aujourdhui_one": "Vandaag {n} minuut verdiend.",
    "recompense_aujourdhui_other": "Vandaag {n} minuten verdiend.",
    "recompense_retirer": "Beloningen verwijderen",
    "recompense_aucune": "Geen beloning ingesteld.",
    "recompense_aucune_liste": "Geen takenlijst in Home Assistant: voeg de integratie ‘Takenlijst’ toe.",
    "ecrans_aide": "Wat {prenom} in Vision van Home Assistant ziet: startscherm en widgets van de apparaten.",
    "carte_nom": "Vision — profiel van een persoon",
    "carte_description": "Overzicht, tijden, inhoud, wekkers, beloningen, schermen en rol van een persoon."
  }
}/*i18n-json:fin*/;
// Les catégories : leur nom arrive déjà traduit du serveur (fiche) ; cette carte n'a pas de table à elle.
const CATEGORIES_I18N = /*i18n-categories*/{}/*i18n-categories:fin*/;
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
const API_FICHE = "pc_parental/personne/admin";
// 15-10 (règle UX de l'auteur) : un groupe de choix passe par <vision-choix> (étiquettes s'il y a 3 options ou moins et qu'elles
// tiennent sur une ligne, sinon liste déroulante qui montre le choix). « valeur » : une clé (choix unique) ou une liste
// de clés (plusieurs parmi la liste).
const choixHtml = (nom, titre, options, valeur) => Array.isArray(valeur)
  ? `<vision-choix data-choix="${esc(nom)}" multiple titre="${esc(titre)}" valeurs="${esc(JSON.stringify(valeur))}" options="${esc(JSON.stringify(options))}"></vision-choix>`
  : `<vision-choix data-choix="${esc(nom)}" titre="${esc(titre)}" valeur="${esc(valeur || "")}" options="${esc(JSON.stringify(options))}"></vision-choix>`;
const esc = (t) =>
  String(t ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const ERREURS_GESTE = { admin: "erreur_admin", loading: "erreur_loading", personne: "erreur_personne" };
const messageGeste = (e, defaut) => {
  const b = e && e.body;
  if (b && b.retour) return String(b.retour);
  if (b && ERREURS_GESTE[b.error]) return tr(ERREURS_GESTE[b.error]);
  if (e && e.status_code === 403) return tr(ERREURS_GESTE.admin);
  return defaut || tr("erreur_defaut");
};
// Les onglets, dans l'ordre de l'architecture cible (05-architecture-cible.md § 2).
const ONGLETS = ["resume", "horaires", "contenus", "reveils", "recompenses", "ecrans", "role"];

// FAILLE DE SÉCURITÉ (l'auteur, 16/10) : accorder (accepter, déverrouiller, donner du temps, ouvrir, autoriser, valider un
// appareil, donner un rôle) exige le code de déverrouillage du Décideur À CHAQUE geste. Home Assistant le réclame (403 « code_octroi »,
// octroi.py) : on le demande, puis on renvoie le même geste avec lui. Le code n'est jamais gardé.
const ERREURS_CODE_OCTROI = ["code_octroi", "code_octroi_faux"];
const demanderCodeOctroi = (racine, message) => new Promise((resoudre) => {
  const d = document.createElement("dialog");
  d.setAttribute("aria-label", tr("octroi_titre"));
  d.style.cssText = "border:0;border-radius:16px;padding:20px;max-width:min(360px,calc(100vw - 48px));box-sizing:border-box;"
    + "background:var(--card-background-color,#fff);color:var(--primary-text-color,#000);";
  d.innerHTML = `<form method="dialog" style="display:grid;gap:12px;margin:0">
      <b style="font-size:18px">${esc(tr("octroi_titre"))}</b>
      <div style="color:var(--secondary-text-color);overflow-wrap:anywhere">${esc(message || "")}</div>
      <input name="code" type="password" autocomplete="off" aria-label="${esc(tr("octroi_champ"))}" placeholder="${esc(tr("octroi_champ"))}"
        style="font:inherit;padding:10px;border-radius:10px;border:1px solid var(--divider-color);min-height:44px;box-sizing:border-box;width:100%;background:var(--card-background-color);color:var(--primary-text-color)">
      <div style="display:flex;gap:8px;justify-content:flex-end;flex-wrap:wrap">
        <button value="" type="button" data-annuler style="font:inherit;min-height:44px;padding:8px 14px;border-radius:999px;border:1px solid var(--divider-color);background:none;color:var(--primary-text-color)">${esc(tr("octroi_annuler"))}</button>
        <button value="ok" style="font:inherit;min-height:44px;padding:8px 14px;border-radius:999px;border:0;background:var(--primary-color);color:var(--text-primary-color)">${esc(tr("octroi_valider"))}</button>
      </div></form>`;
  const champ = d.querySelector("input");
  const fin = (v) => { d.remove(); resoudre(v); };
  d.querySelector("[data-annuler]").addEventListener("click", () => { d.close(); fin(""); });
  d.addEventListener("cancel", () => fin(""));
  d.querySelector("form").addEventListener("submit", () => fin(champ.value));
  racine.appendChild(d);
  d.showModal();
  champ.focus();
});
const avecCodeOctroi = async (hass, racine, chemin, corps) => {
  try { return await hass.callApi("POST", chemin, corps); } catch (e) {
    let erreur = e;
    for (let essai = 0; essai < 3; essai += 1) {
      const b = erreur && erreur.body;
      if (!b || !ERREURS_CODE_OCTROI.includes(b.error)) throw erreur;
      const code = await demanderCodeOctroi(racine, b.retour);
      if (!code) throw erreur;
      try { return await hass.callApi("POST", chemin, Object.assign({}, corps, { code_octroi: code })); } catch (e2) { erreur = e2; }
    }
    throw erreur;
  }
};

class VisionPersonneCard extends HTMLElement {
  constructor() {
    super();
    this.attachShadow({ mode: "open" });
    this._d = null;
    this._erreur = "";
    this._fait = "";
    this._occupe = false;
    this._onglet = "";
    this._regle = null;     // la règle en cours d'édition : {zone, etiquettes} ou null
    this._enfants = {};     // les cartes intégrées, gardées d'un rendu à l'autre (elles gardent leur état)
    this._lu = "";
  }

  setConfig(config) {
    if (!config || !config.personne) throw new Error("personne");
    this._config = Object.assign({ chemin: "/vision-maison" }, config);
  }

  set hass(hass) {
    const premier = !this._hass;
    this._hass = hass;
    const langue = LANGUE;
    suivreLangue(hass);
    for (const el of Object.values(this._enfants)) el.hass = hass;
    if (premier || this._lu !== this._config.personne) this._charger();
    else if (langue !== LANGUE) this._rendre();
  }

  getCardSize() { return 12; }

  _ongletVoulu() {
    const m = /(?:^|[#&])onglet=(\w+)/.exec(window.location.hash || "");
    return m ? m[1] : "";
  }

  async _charger() {
    this._lu = this._config.personne;
    try {
      this._d = await this._hass.callApi("GET", `${API_FICHE}?personne=${encodeURIComponent(this._config.personne)}`);
      this._erreur = "";
    } catch (e) {
      this._erreur = messageGeste(e);
    }
    this._rendre();
  }

  async _envoyer(corps) {
    if (this._occupe) return false;
    this._occupe = true; this._erreur = ""; this._fait = ""; this._rendre();
    try {
      const r = await avecCodeOctroi(this._hass, this.shadowRoot, API_FICHE, Object.assign({ personne: this._config.personne }, corps));
      this._d = r; this._fait = r.fait || tr("geste_fait");
      return true;
    } catch (e) {
      this._erreur = messageGeste(e);
      return false;
    } finally {
      this._occupe = false; this._rendre();
      clearTimeout(this._minute);
      this._minute = setTimeout(() => { this._fait = ""; this._rendre(); }, 6000);
    }
  }

  async _service(service, donnees) {
    if (this._occupe) return;
    this._occupe = true; this._erreur = ""; this._rendre();
    try {
      await this._hass.callService("pc_parental", service, donnees);
      this._fait = tr("geste_fait");
      this._occupe = false;
      await this._charger();
    } catch (e) {
      this._erreur = (e && e.message) || tr("erreur_defaut");
      this._occupe = false; this._rendre();
    }
  }

  // Retours de l'auteur (15/10) : ‹ › remplace la page dans l'historique : la flèche de retour de la fiche ramène à la page
  // d'où l'on est venu (Aujourd'hui, Personnes…), pas à la personne d'avant.
  _naviguer(chemin) {
    history.replaceState(null, "", chemin);
    window.dispatchEvent(new CustomEvent("location-changed", { detail: { replace: true } }));
  }

  // --- les cartes intégrées -----------------------------------------------

  _enfant(cle, type, config) {
    let el = this._enfants[cle];
    const voulu = JSON.stringify(config);
    if (!el || el._configVoulue !== voulu) {
      el = document.createElement(type);
      try { el.setConfig(config); } catch (e) { return null; }
      el._configVoulue = voulu;
      this._enfants[cle] = el;
    }
    if (this._hass) el.hass = this._hass;
    return el;
  }

  _poserEnfants() {
    this.shadowRoot.querySelectorAll("[data-enfant]").forEach((hote) => {
      const [cle, type, config] = JSON.parse(hote.dataset.enfant);
      const el = this._enfant(cle, type, config);
      if (el) hote.appendChild(el);
    });
  }

  _hoteEnfant(cle, type, config) {
    return `<div class="enfant" data-enfant="${esc(JSON.stringify([cle, type, config]))}"></div>`;
  }

  // --- rendu ----------------------------------------------------------------

  _rendre() {
    const d = this._d;
    if (!d) {
      this.shadowRoot.innerHTML = `${this._styles()}<ha-card><div class="vide">${esc(this._erreur || tr("chargement"))}</div></ha-card>`;
      return;
    }
    const p = d.personne;
    const permis = ONGLETS.filter((o) => (d.onglets || []).includes(o));
    const voulu = this._onglet || this._ongletVoulu() || this._config.onglet || "";
    const onglet = permis.includes(voulu) ? voulu : permis[0];
    this._onglet = onglet;
    const liste = d.personnes || [];
    const i = liste.findIndex((x) => x.entite === p.entite);
    const prec = liste.length > 1 && i >= 0 ? liste[(i - 1 + liste.length) % liste.length] : null;
    const suiv = liste.length > 1 && i >= 0 ? liste[(i + 1) % liste.length] : null;
    const initiale = esc((p.prenom || "?").trim().charAt(0).toUpperCase());
    this.shadowRoot.innerHTML = `${this._styles()}
      <ha-card>
        <div class="entete">
          ${p.photo ? `<img class="avatar" src="${esc(p.photo)}" alt="">` : `<div class="avatar" aria-hidden="true">${initiale}</div>`}
          <div class="qui"><h2>${esc(p.prenom)}</h2><div class="sous">${esc(tr("role_actuel", { role: d.role.nom }))}</div></div>
          <div class="nav">
            ${prec ? `<button class="rond" data-aller="${esc(prec.chemin)}" aria-label="${esc(tr("precedente", { prenom: prec.prenom }))}" title="${esc(tr("precedente", { prenom: prec.prenom }))}">‹</button>` : ""}
            ${suiv ? `<button class="rond" data-aller="${esc(suiv.chemin)}" aria-label="${esc(tr("suivante", { prenom: suiv.prenom }))}" title="${esc(tr("suivante", { prenom: suiv.prenom }))}">›</button>` : ""}
          </div>
        </div>
        <div class="onglets" role="tablist" aria-label="${esc(tr("onglets", { prenom: p.prenom }))}">
          ${permis.map((o) => `<button role="tab" id="t-${o}" aria-controls="p-${o}" aria-selected="${o === onglet}" tabindex="${o === onglet ? 0 : -1}" data-onglet="${o}">${esc(tr("onglet_" + o))}</button>`).join("")}
        </div>
        ${this._erreur ? `<div class="erreur" role="alert">${esc(this._erreur)}</div>` : ""}
        ${this._fait ? `<div class="fait" role="status">${esc(this._fait)}</div>` : ""}
        <div class="panneau" role="tabpanel" id="p-${onglet}" aria-labelledby="t-${onglet}">${this._panneau(onglet)}</div>
      </ha-card>`;
    this._poserEnfants();
    this._brancher();
  }

  _panneau(o) {
    const d = this._d;
    const p = d.personne;
    const chemin = this._config.chemin;
    if (o === "resume") {
      return this._hoteEnfant("resume", "vision-maison-card", { type: "custom:vision-maison-card", mode: "personne", personne: p.entite, chemin, parties: ["resume"], integre: true });
    }
    if (o === "horaires") {
      return d.planning
        ? this._hoteEnfant("horaires", "vision-planning-card", { type: "custom:vision-planning-card", entity: d.planning })
        : `<p class="vide">${esc(tr("aucun_planning", { prenom: p.prenom }))}</p>`;
    }
    if (o === "contenus") {
      return `${d.modules.categories ? this._hoteEnfant("contenus", "vision-maison-card", { type: "custom:vision-maison-card", mode: "personne", personne: p.entite, chemin, parties: ["categories"], integre: true }) : ""}
        ${d.modules.regles_lieu ? this._regles() : ""}`;
    }
    if (o === "reveils") return this._reveils();
    if (o === "recompenses") return this._recompenses();
    if (o === "ecrans") {
      return `<p class="aide">${esc(tr("ecrans_aide", { prenom: p.prenom }))}</p>
        ${this._hoteEnfant("ecrans", "vision-widgets-card", { type: "custom:vision-widgets-card", personne: p.entite, integre: true })}`;
    }
    if (o === "role") return this._role();
    return "";
  }

  _role() {
    const d = this._d;
    const actuel = d.role.actuel || "";
    const options = [{ id: "", nom: tr("role_defaut", { role: d.role.defaut }) }, ...d.role.options];
    return `<section>
      <p class="aide">${esc(tr("role_aide", { prenom: d.personne.prenom }))}</p>
      <fieldset class="choix-liste"><legend>${esc(tr("role_titre"))}</legend>
        ${choixHtml("role", tr("role_titre"), options.map((r) => ({ cle: r.id, libelle: r.nom })), actuel)}
      </fieldset>
      <div class="boutons"><button class="principal" data-action="role" ${this._occupe ? "disabled" : ""}>${esc(tr("enregistrer"))}</button></div>
    </section>`;
  }

  _recompenses() {
    const d = this._d;
    const r = d.recompense;
    const listes = d.listes || [];
    return `<section>
      <p class="aide">${esc(tr("recompenses_aide", { prenom: d.personne.prenom }))}</p>
      ${r ? `<p class="etat">${esc(trn("recompense_aujourdhui", r.accorde))}</p>` : `<p class="vide">${esc(tr("recompense_aucune"))}</p>`}
      ${listes.length ? `
      <div class="formulaire">
        <fieldset class="choix-liste"><legend>${esc(tr("recompense_liste"))}</legend>
          ${choixHtml("liste", tr("recompense_liste"), listes.map((l) => ({ cle: l.id, libelle: l.nom })), r ? r.liste : "")}
        </fieldset>
        <label class="champ"><span>${esc(tr("recompense_minutes"))}</span>
          <input type="number" inputmode="numeric" min="1" max="240" step="5" data-champ="minutes" value="${esc(r ? r.minutes : d.minutes_defaut)}"></label>
        <label class="champ"><span>${esc(tr("recompense_max"))}</span>
          <input type="number" inputmode="numeric" min="5" max="720" step="5" data-champ="max" value="${esc(r ? r.max : d.max_defaut)}"></label>
        <div class="boutons">
          <button class="principal" data-action="recompense" ${this._occupe ? "disabled" : ""}>${esc(tr("enregistrer"))}</button>
          ${r ? `<button class="secondaire" data-action="recompense_retirer" ${this._occupe ? "disabled" : ""}>${esc(tr("recompense_retirer"))}</button>` : ""}
        </div>
      </div>` : `<p class="vide">${esc(tr("recompense_aucune_liste"))}</p>`}
    </section>`;
  }

  _regles() {
    const d = this._d;
    const regles = d.regles || [];
    const e = this._regle;
    const zones = d.zones || [];
    const cats = d.categories || [];
    const edition = e ? `
      <div class="formulaire">
        <fieldset class="choix-liste"><legend>${esc(tr("regle_zone"))}</legend>
          ${zones.length ? choixHtml("zone", tr("regle_zone"),
            zones.filter((z) => !e.existe || z.id === e.zone).map((z) => ({ cle: z.id, libelle: z.nom })), e.zone)
          : `<span class="vide">${esc(tr("aucune_zone"))}</span>`}
        </fieldset>
        <fieldset class="choix-liste"><legend>${esc(tr("regle_categories"))}</legend>
          ${cats.length ? choixHtml("etiquettes", tr("regle_categories"), cats.map((c) => ({ cle: c.id, libelle: c.nom })), e.etiquettes)
            : `<span class="vide">${esc(tr("aucune_categorie"))}</span>`}
        </fieldset>
        <div class="boutons">
          <button class="principal" data-action="regle" ${this._occupe || !zones.length ? "disabled" : ""}>${esc(tr("enregistrer"))}</button>
          <button class="secondaire" data-action="regle_annuler">${esc(tr("annuler"))}</button>
        </div>
      </div>` : "";
    return `<section>
      <h3>${esc(tr("regles_titre"))}</h3>
      <p class="aide">${esc(tr("regles_aide", { prenom: d.personne.prenom }))}</p>
      ${regles.length ? `<ul class="lignes">${regles.map((r) => `<li>
          <span class="texte">${esc(tr("regle_ligne", { zone: r.zone_nom, categories: r.categories.join(", ") }))}</span>
          <span class="boutons">
            <button class="secondaire" data-regle-modifier="${esc(r.zone)}">${esc(tr("modifier"))}</button>
            <button class="secondaire" data-regle-retirer="${esc(r.zone)}" ${this._occupe ? "disabled" : ""}>${esc(tr("retirer"))}</button>
          </span></li>`).join("")}</ul>` : `<p class="vide">${esc(tr("regles_aucune"))}</p>`}
      ${edition || `<div class="boutons"><button class="secondaire" data-action="regle_ajouter">${esc(tr("regle_ajouter"))}</button></div>`}
    </section>`;
  }

  _reveils() {
    const d = this._d;
    const liste = d.reveils || [];
    const quand = (iso) => {
      const t = new Date(iso);
      return isNaN(t) ? iso : trDate(t, { weekday: "long", day: "numeric", month: "long", hour: "2-digit", minute: "2-digit" });
    };
    const jour = (iso) => { const t = new Date(iso + "T12:00:00"); return isNaN(t) ? iso : trDate(t, { weekday: "long", day: "numeric", month: "long" }); };
    return `<section>
      <p class="aide">${esc(tr("reveils_aide", { prenom: d.personne.prenom }))}</p>
      ${liste.length ? `<ul class="lignes">${liste.map((r) => `<li>
          <div class="texte">
            <strong>${esc(r.nom || tr("reveil_sans_nom"))}</strong>
            <span class="pastille ${r.actif ? "oui" : "non"}">${esc(tr(r.actif ? "reveil_actif" : "reveil_inactif"))}</span>
            <div class="sous">${esc(r.prochain ? tr("reveil_prochain", { quand: quand(r.prochain) }) : tr("reveil_aucun_prochain"))}</div>
            ${r.appareils && r.appareils.length ? `<div class="sous">${esc(tr("reveil_sur", { appareils: r.appareils.join(", ") }))}</div>` : ""}
          </div>
          <span class="boutons">
            <button class="secondaire" data-reveil="${esc(r.id)}" data-service="${r.actif ? "reveil_desactiver" : "reveil_activer"}" ${this._occupe ? "disabled" : ""}>${esc(tr(r.actif ? "reveil_desactiver" : "reveil_activer"))}</button>
            ${r.actif && r.prochain ? `<button class="secondaire" data-reveil="${esc(r.id)}" data-service="reveil_sauter" ${this._occupe ? "disabled" : ""}>${esc(tr("reveil_sauter"))}</button>` : ""}
            ${(r.sautees || []).slice(0, 1).map((j) => `<button class="secondaire" data-reveil="${esc(r.id)}" data-service="reveil_reprendre" data-date="${esc(j)}" ${this._occupe ? "disabled" : ""}>${esc(tr("reveil_reprendre", { jour: jour(j) }))}</button>`).join("")}
          </span></li>`).join("")}</ul>` : `<p class="vide">${esc(tr("reveils_aucun"))}</p>`}
    </section>`;
  }

  // --- gestes -----------------------------------------------------------------

  _brancher() {
    const r = this.shadowRoot;
    const onglets = [...r.querySelectorAll("[data-onglet]")];
    const aller = (o, focus) => {
      this._onglet = o;
      const h = (window.location.hash || "").replace(/(^#|&)?onglet=\w+/, "").replace(/^#&?/, "");
      history.replaceState(null, "", `${window.location.pathname}${window.location.search}#onglet=${o}${h ? "&" + h : ""}`);
      this._rendre();
      if (focus) { const b = this.shadowRoot.querySelector(`[data-onglet="${o}"]`); if (b) b.focus(); }
    };
    onglets.forEach((b, k) => {
      b.addEventListener("click", () => aller(b.dataset.onglet, false));
      b.addEventListener("keydown", (ev) => {
        const pas = { ArrowRight: 1, ArrowLeft: -1, Home: -k, End: onglets.length - 1 - k }[ev.key];
        if (pas === undefined) return;
        ev.preventDefault();
        aller(onglets[(k + pas + onglets.length) % onglets.length].dataset.onglet, true);
      });
    });
    r.querySelectorAll("[data-aller]").forEach((b) => b.addEventListener("click", () => {
      this._naviguer(`${this._config.chemin}/${b.dataset.aller}#onglet=${this._onglet}`);
    }));
    const champ = (n) => r.querySelector(`[data-champ="${n}"]`);
    const action = (n, f) => { const b = r.querySelector(`[data-action="${n}"]`); if (b) b.addEventListener("click", f); };
    // Les groupes de choix (<vision-choix>) : la valeur de l'élément (ou ses attributs s'il n'est pas encore défini).
    const groupe = (n) => r.querySelector(`[data-choix="${n}"]`);
    const radio = (n) => {
      const x = groupe(n);
      if (!x) return "";
      return typeof x.valeur === "string" ? x.valeur : (x.getAttribute("valeur") || "");
    };
    const plusieurs = (n) => {
      const x = groupe(n);
      if (!x) return [];
      if (Array.isArray(x.valeurs)) return x.valeurs;
      try { const l = JSON.parse(x.getAttribute("valeurs") || "[]"); return Array.isArray(l) ? l : []; } catch (e) { return []; }
    };
    action("role", () => this._envoyer({ op: "role", role: radio("role") }));
    action("recompense", () => this._envoyer({ op: "temps_gagne", liste: radio("liste"),
      minutes: Number(champ("minutes").value), max: Number(champ("max").value) }));
    action("recompense_retirer", () => this._envoyer({ op: "temps_gagne", liste: "", minutes: 0, max: 0 }));
    action("regle_ajouter", () => { this._regle = { zone: "", etiquettes: [], existe: false }; this._rendre(); });
    action("regle_annuler", () => { this._regle = null; this._rendre(); });
    action("regle", async () => {
      const zone = this._regle.existe ? this._regle.zone : radio("zone");
      const etiquettes = plusieurs("etiquettes");
      if (await this._envoyer({ op: "regle_lieu", zone, etiquettes })) { this._regle = null; this._rendre(); }
    });
    r.querySelectorAll("[data-regle-modifier]").forEach((b) => b.addEventListener("click", () => {
      const regle = (this._d.regles || []).find((x) => x.zone === b.dataset.regleModifier);
      this._regle = { zone: regle.zone, etiquettes: [...regle.etiquettes], existe: true };
      this._rendre();
    }));
    r.querySelectorAll("[data-regle-retirer]").forEach((b) => b.addEventListener("click", () =>
      this._envoyer({ op: "regle_lieu", zone: b.dataset.regleRetirer, etiquettes: [] })));
    r.querySelectorAll("[data-reveil]").forEach((b) => b.addEventListener("click", () => {
      const donnees = { reveil: b.dataset.reveil };
      if (b.dataset.date) donnees.date = b.dataset.date;
      this._service(b.dataset.service, donnees);
    }));
  }

  _styles() {
    return `<style>
      :host { display:block; }
      ha-card { padding:14px 16px; }
      .entete { display:flex; align-items:center; gap:14px; flex-wrap:wrap; }
      .avatar { width:52px; height:52px; border-radius:50%; flex:none; display:flex; align-items:center; justify-content:center;
        background:var(--primary-color); color:var(--text-primary-color, #fff); font-size:1.4em; object-fit:cover; }
      .qui { flex:1; min-width:140px; }
      h2 { margin:0; font-size:1.3em; font-weight:500; overflow-wrap:anywhere; }
      h3 { margin:16px 0 6px; font-size:.95em; font-weight:600; color:var(--secondary-text-color); }
      .sous { color:var(--secondary-text-color); font-size:.9em; margin-top:2px; overflow-wrap:anywhere; }
      .nav { display:flex; gap:8px; }
      .rond { width:44px; height:44px; border-radius:50%; border:1px solid var(--divider-color); background:none; cursor:pointer;
        color:var(--primary-text-color); font-size:1.5em; line-height:1; }
      .onglets { display:flex; flex-wrap:wrap; gap:6px; margin:14px 0 8px; border-bottom:1px solid var(--divider-color); padding-bottom:8px; }
      .onglets button { border:1px solid var(--divider-color); background:none; border-radius:18px; padding:8px 14px; min-height:40px;
        cursor:pointer; color:var(--primary-text-color); font:inherit; }
      .onglets button[aria-selected="true"] { background:var(--primary-color); color:var(--text-primary-color, #fff); border-color:var(--primary-color); font-weight:600; }
      button:focus-visible, input:focus-visible, select:focus-visible { outline:3px solid var(--primary-color); outline-offset:2px; }
      .aide { color:var(--secondary-text-color); margin:6px 0 10px; }
      .vide { color:var(--secondary-text-color); }
      .etat { font-weight:500; margin:6px 0; }
      .erreur { color:var(--error-color, #c62828); margin:8px 0; }
      .fait { color:color-mix(in srgb, var(--success-color, #2e7d32) 65%, var(--primary-text-color, #212121)); margin:8px 0; font-weight:500; }
      .enfant > * { display:block; }
      .lignes { list-style:none; margin:0; padding:0; }
      .lignes li { display:flex; flex-wrap:wrap; gap:8px 12px; align-items:center; justify-content:space-between; padding:10px 0; border-top:1px solid var(--divider-color); }
      .lignes .texte { flex:1 1 220px; min-width:0; overflow-wrap:anywhere; }
      .pastille { display:inline-block; margin-left:8px; padding:2px 10px; border-radius:12px; font-size:.85em; border:1px solid var(--divider-color); }
      .pastille.oui { color:color-mix(in srgb, var(--success-color, #2e7d32) 65%, var(--primary-text-color, #212121)); }
      .boutons { display:flex; flex-wrap:wrap; gap:8px; }
      .formulaire { display:grid; grid-template-columns:minmax(0, 1fr); gap:12px; margin:10px 0; max-width:640px; }
      .champ { display:grid; gap:4px; min-width:0; }
      .champ select, .champ input { padding:8px 10px; min-height:40px; border-radius:8px; border:1px solid var(--divider-color);
        background:var(--card-background-color); color:var(--primary-text-color); font:inherit; max-width:100%; box-sizing:border-box; }
      fieldset { border:none; margin:0; padding:0; display:flex; flex-wrap:wrap; gap:6px 16px; min-width:0; }
      legend { font-weight:600; padding:0 0 6px; }
      fieldset vision-choix { flex:1 1 100%; min-width:0; }
      .choix { display:inline-flex; align-items:center; gap:8px; min-height:40px; overflow-wrap:anywhere; max-width:100%; min-width:0; }
      .choix input { width:22px; height:22px; margin:0; flex:none; }
      .principal { background:var(--primary-color); color:var(--text-primary-color, #fff); border:none; border-radius:10px; padding:8px 16px; min-height:40px; cursor:pointer; font:inherit; }
      .secondaire { background:none; border:1px solid var(--divider-color); color:var(--primary-text-color); border-radius:10px; padding:8px 14px; min-height:40px; cursor:pointer; font:inherit; }
      button[disabled] { opacity:.6; cursor:default; }
    </style>`;
  }
}

if (!customElements.get("vision-personne-card")) customElements.define("vision-personne-card", VisionPersonneCard);
window.customCards = window.customCards || [];
if (!window.customCards.some((c) => c.type === "vision-personne-card")) {
  window.customCards.push({ type: "vision-personne-card", name: tr("carte_nom"), description: tr("carte_description") });
}
