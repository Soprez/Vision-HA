/*
 * Vision — carte « Tableaux de bord sur l'écran de veille ».
 *
 * La liste des tableaux de bord de Home Assistant. On coche ceux que l'écran
 * de veille reprend ; pour chacun, la durée, les écrans, le public, puis ses
 * vues et ses cartes. Tout s'enregistre dans l'intégration ; les appareils le
 * reçoivent dans la minute et redessinent les cartes dans leur thème.
 */
// ---- i18n:debut — les textes de cette carte, dans les six langues de Vision (fr source, en repli) ----
const TEXTES = /*i18n-json*/{
  "fr": {
    "ecran_tele": "Télé", "ecran_telephone": "Téléphone", "choix_ecrans": "Écrans", "choix_publics": "Publics",
    "public_affichage": "Affichage commun", "public_parent": "{Decideurs}", "public_enfant": "{Demandeurs}",
    "erreur_admin": "Réservé aux administrateurs de Home Assistant.",
    "erreur_loading": "Vision démarre : réessaie dans un instant.",
    "erreur_pc": "Cet appareil n'existe plus dans Vision.",
    "erreur_json": "La demande n'a pas pu être lue : réessaie.",
    "erreur_module": "Cette fonction est désactivée dans Vision.",
    "erreur_minutes": "Indique une durée.",
    "erreur_defaut": "Ce n'est pas passé : rien n'a changé, réessaie dans un instant.",
    "geste_en_cours": "En cours…",
    "geste_fait": "C'est fait.",
    "erreur_lecture": "Impossible de lire les tableaux de bord.",
    "enregistre": "Enregistré.",
    "echec_enregistrement": "L'enregistrement n'est pas passé ; réessaie.",
    "sans_titre": "Sans titre",
    "cases": "{n} cases",
    "non_reprise": "non reprise",
    "compte_cartes": "{visibles} / {possibles} cartes",
    "aucune_carte": "aucune carte reprise",
    "duree": "Durée",
    "vues_one": "{n} vue", "vues_other": "{n} vues",
    "vues_reprises": "{vues}, repris le {date}",
    "genere": "généré automatiquement, sans cartes à reprendre",
    "vide": "vide",
    "chargement": "Chargement…",
    "aucun_tableau": "Aucun tableau de bord.",
    "titre": "Tableaux de bord sur l'écran de veille",
    "aide_1": "Coche les tableaux de bord à reprendre. Leurs sections, leurs colonnes et la taille de leurs cartes sont gardées, puis redessinées dans le thème de chaque appareil. S'il y a trop de cartes pour un écran, la suite arrive en fondu.",
    "aide_2": "La mise en page est reprise au moment où tu enregistres, puis gardée : les appareils ne relisent ensuite que les valeurs. Après avoir retouché un tableau de bord, reviens ici et choisis « {bouton} ».",
    "enregistrement": "Enregistrement…", "enregistrer": "Enregistrer", "annuler": "Annuler",
    "reprendre": "Reprendre les cartes",
    "carte_nom": "Vision : tableaux de bord sur l'écran de veille",
    "carte_description": "Choisir les tableaux de bord, vues et cartes repris par l'écran de veille."
  },
  "en": {
    "ecran_tele": "TV", "ecran_telephone": "Phone", "choix_ecrans": "Screens", "choix_publics": "Audiences",
    "public_affichage": "Shared screen", "public_parent": "{Decideurs}", "public_enfant": "{Demandeurs}",
    "erreur_admin": "Only Home Assistant administrators can do this.",
    "erreur_loading": "Vision is starting: try again in a moment.",
    "erreur_pc": "This device no longer exists in Vision.",
    "erreur_json": "The request could not be read: try again.",
    "erreur_module": "This feature is turned off in Vision.",
    "erreur_minutes": "Enter a duration.",
    "erreur_defaut": "That didn't work: nothing changed, try again in a moment.",
    "geste_en_cours": "Working…",
    "geste_fait": "Done.",
    "erreur_lecture": "Couldn't read the dashboards.",
    "enregistre": "Saved.",
    "echec_enregistrement": "Saving didn't work; try again.",
    "sans_titre": "Untitled",
    "cases": "{n} cells",
    "non_reprise": "not shown",
    "compte_cartes": "{visibles} / {possibles} cards",
    "aucune_carte": "no card shown",
    "duree": "Duration",
    "vues_one": "{n} view", "vues_other": "{n} views",
    "vues_reprises": "{vues}, captured on {date}",
    "genere": "generated automatically, no cards to show",
    "vide": "empty",
    "chargement": "Loading…",
    "aucun_tableau": "No dashboard.",
    "titre": "Dashboards on the screensaver",
    "aide_1": "Tick the dashboards to show. Their sections, columns and card sizes are kept, then redrawn in each device's theme. If there are too many cards for one screen, the rest fades in afterwards.",
    "aide_2": "The layout is captured when you save, then kept: devices then only read the values. After changing a dashboard, come back here and choose “{bouton}”.",
    "enregistrement": "Saving…", "enregistrer": "Save", "annuler": "Cancel",
    "reprendre": "Capture the cards again",
    "carte_nom": "Vision: dashboards on the screensaver",
    "carte_description": "Choose the dashboards, views and cards shown on the screensaver."
  },
  "de": {
    "ecran_tele": "Fernseher", "ecran_telephone": "Handy", "choix_ecrans": "Bildschirme", "choix_publics": "Zielgruppen",
    "public_affichage": "Gemeinsamer Bildschirm", "public_parent": "{Decideurs}", "public_enfant": "{Demandeurs}",
    "erreur_admin": "Nur für Home Assistant-Administratoren.",
    "erreur_loading": "Vision startet gerade: versuch es gleich noch einmal.",
    "erreur_pc": "Dieses Gerät gibt es in Vision nicht mehr.",
    "erreur_json": "Die Anfrage konnte nicht gelesen werden: versuch es noch einmal.",
    "erreur_module": "Diese Funktion ist in Vision ausgeschaltet.",
    "erreur_minutes": "Gib eine Dauer an.",
    "erreur_defaut": "Das hat nicht geklappt: nichts wurde geändert, versuch es gleich noch einmal.",
    "geste_en_cours": "Wird ausgeführt…",
    "geste_fait": "Erledigt.",
    "erreur_lecture": "Die Dashboards konnten nicht gelesen werden.",
    "enregistre": "Gespeichert.",
    "echec_enregistrement": "Das Speichern hat nicht geklappt; versuch es noch einmal.",
    "sans_titre": "Ohne Titel",
    "cases": "{n} Felder",
    "non_reprise": "nicht übernommen",
    "compte_cartes": "{visibles} / {possibles} Karten",
    "aucune_carte": "keine Karte übernommen",
    "duree": "Dauer",
    "vues_one": "{n} Ansicht", "vues_other": "{n} Ansichten",
    "vues_reprises": "{vues}, übernommen am {date}",
    "genere": "automatisch erstellt, keine Karten zu übernehmen",
    "vide": "leer",
    "chargement": "Wird geladen…",
    "aucun_tableau": "Kein Dashboard.",
    "titre": "Dashboards auf dem Bildschirmschoner",
    "aide_1": "Hake die Dashboards an, die übernommen werden sollen. Ihre Abschnitte, Spalten und Kartengrößen bleiben erhalten und werden im Design jedes Geräts neu gezeichnet. Sind es zu viele Karten für einen Bildschirm, wird der Rest danach eingeblendet.",
    "aide_2": "Das Layout wird beim Speichern übernommen und dann behalten: Die Geräte lesen danach nur noch die Werte. Wenn du ein Dashboard geändert hast, komm hierher zurück und wähle „{bouton}“.",
    "enregistrement": "Wird gespeichert…", "enregistrer": "Speichern", "annuler": "Abbrechen",
    "reprendre": "Karten neu übernehmen",
    "carte_nom": "Vision: Dashboards auf dem Bildschirmschoner",
    "carte_description": "Die Dashboards, Ansichten und Karten wählen, die der Bildschirmschoner übernimmt."
  },
  "es": {
    "ecran_tele": "Tele", "ecran_telephone": "Teléfono", "choix_ecrans": "Pantallas", "choix_publics": "Públicos",
    "public_affichage": "Pantalla compartida", "public_parent": "{Decideurs}", "public_enfant": "{Demandeurs}",
    "erreur_admin": "Solo para administradores de Home Assistant.",
    "erreur_loading": "Vision se está iniciando: inténtalo de nuevo en un momento.",
    "erreur_pc": "Este dispositivo ya no existe en Vision.",
    "erreur_json": "No se pudo leer la solicitud: inténtalo de nuevo.",
    "erreur_module": "Esta función está desactivada en Vision.",
    "erreur_minutes": "Indica una duración.",
    "erreur_defaut": "No ha funcionado: no ha cambiado nada, inténtalo de nuevo en un momento.",
    "geste_en_cours": "En curso…",
    "geste_fait": "Hecho.",
    "erreur_lecture": "No se pudieron leer los paneles.",
    "enregistre": "Guardado.",
    "echec_enregistrement": "No se ha podido guardar; inténtalo de nuevo.",
    "sans_titre": "Sin título",
    "cases": "{n} celdas",
    "non_reprise": "no incluida",
    "compte_cartes": "{visibles} / {possibles} tarjetas",
    "aucune_carte": "ninguna tarjeta incluida",
    "duree": "Duración",
    "vues_one": "{n} vista", "vues_other": "{n} vistas",
    "vues_reprises": "{vues}, tomado el {date}",
    "genere": "generado automáticamente, sin tarjetas que incluir",
    "vide": "vacío",
    "chargement": "Cargando…",
    "aucun_tableau": "Ningún panel.",
    "titre": "Paneles en el salvapantallas",
    "aide_1": "Marca los paneles que quieres incluir. Se mantienen sus secciones, sus columnas y el tamaño de sus tarjetas, y se vuelven a dibujar con el tema de cada dispositivo. Si hay demasiadas tarjetas para una pantalla, el resto aparece después con un fundido.",
    "aide_2": "El diseño se toma en el momento en que guardas y luego se conserva: después, los dispositivos solo vuelven a leer los valores. Tras retocar un panel, vuelve aquí y elige «{bouton}».",
    "enregistrement": "Guardando…", "enregistrer": "Guardar", "annuler": "Cancelar",
    "reprendre": "Volver a tomar las tarjetas",
    "carte_nom": "Vision: paneles en el salvapantallas",
    "carte_description": "Elegir los paneles, vistas y tarjetas que muestra el salvapantallas."
  },
  "it": {
    "ecran_tele": "TV", "ecran_telephone": "Telefono", "choix_ecrans": "Schermi", "choix_publics": "Pubblici",
    "public_affichage": "Schermo condiviso", "public_parent": "{Decideurs}", "public_enfant": "{Demandeurs}",
    "erreur_admin": "Riservato agli amministratori di Home Assistant.",
    "erreur_loading": "Vision si sta avviando: riprova tra un momento.",
    "erreur_pc": "Questo dispositivo non esiste più in Vision.",
    "erreur_json": "Non è stato possibile leggere la richiesta: riprova.",
    "erreur_module": "Questa funzione è disattivata in Vision.",
    "erreur_minutes": "Indica una durata.",
    "erreur_defaut": "Non è andata: non è cambiato nulla, riprova tra un momento.",
    "geste_en_cours": "In corso…",
    "geste_fait": "Fatto.",
    "erreur_lecture": "Impossibile leggere le dashboard.",
    "enregistre": "Salvato.",
    "echec_enregistrement": "Il salvataggio non è riuscito; riprova.",
    "sans_titre": "Senza titolo",
    "cases": "{n} celle",
    "non_reprise": "non ripresa",
    "compte_cartes": "{visibles} / {possibles} schede",
    "aucune_carte": "nessuna scheda ripresa",
    "duree": "Durata",
    "vues_one": "{n} vista", "vues_other": "{n} viste",
    "vues_reprises": "{vues}, ripresa il {date}",
    "genere": "generata automaticamente, nessuna scheda da riprendere",
    "vide": "vuota",
    "chargement": "Caricamento…",
    "aucun_tableau": "Nessuna dashboard.",
    "titre": "Dashboard sul salvaschermo",
    "aide_1": "Spunta le dashboard da riprendere. Le loro sezioni, colonne e dimensioni delle schede vengono mantenute, poi ridisegnate nel tema di ogni dispositivo. Se ci sono troppe schede per uno schermo, il resto arriva in dissolvenza.",
    "aide_2": "Il layout viene ripreso nel momento in cui salvi, poi mantenuto: i dispositivi rileggono solo i valori. Dopo aver ritoccato una dashboard, torna qui e scegli «{bouton}».",
    "enregistrement": "Salvataggio…", "enregistrer": "Salvare", "annuler": "Annullare",
    "reprendre": "Riprendere le schede",
    "carte_nom": "Vision: dashboard sul salvaschermo",
    "carte_description": "Scegliere le dashboard, le viste e le schede riprese dal salvaschermo."
  },
  "nl": {
    "ecran_tele": "Tv", "ecran_telephone": "Telefoon", "choix_ecrans": "Schermen", "choix_publics": "Doelgroepen",
    "public_affichage": "Gedeeld scherm", "public_parent": "{Decideurs}", "public_enfant": "{Demandeurs}",
    "erreur_admin": "Alleen voor beheerders van Home Assistant.",
    "erreur_loading": "Vision start op: probeer het zo opnieuw.",
    "erreur_pc": "Dit apparaat bestaat niet meer in Vision.",
    "erreur_json": "Het verzoek kon niet worden gelezen: probeer het opnieuw.",
    "erreur_module": "Deze functie staat uit in Vision.",
    "erreur_minutes": "Geef een duur op.",
    "erreur_defaut": "Dat is niet gelukt: er is niets veranderd, probeer het zo opnieuw.",
    "geste_en_cours": "Bezig…",
    "geste_fait": "Klaar.",
    "erreur_lecture": "De dashboards konden niet worden gelezen.",
    "enregistre": "Opgeslagen.",
    "echec_enregistrement": "Opslaan is niet gelukt; probeer het opnieuw.",
    "sans_titre": "Zonder titel",
    "cases": "{n} vakken",
    "non_reprise": "niet overgenomen",
    "compte_cartes": "{visibles} / {possibles} kaarten",
    "aucune_carte": "geen kaart overgenomen",
    "duree": "Duur",
    "vues_one": "{n} weergave", "vues_other": "{n} weergaven",
    "vues_reprises": "{vues}, overgenomen op {date}",
    "genere": "automatisch gemaakt, geen kaarten om over te nemen",
    "vide": "leeg",
    "chargement": "Laden…",
    "aucun_tableau": "Geen dashboard.",
    "titre": "Dashboards op de schermbeveiliging",
    "aide_1": "Vink de dashboards aan die je wilt overnemen. Hun secties, kolommen en kaartgroottes blijven behouden en worden opnieuw getekend in het thema van elk apparaat. Zijn er te veel kaarten voor één scherm, dan faden de volgende erna in.",
    "aide_2": "De opmaak wordt overgenomen op het moment dat je opslaat en daarna bewaard: de apparaten lezen daarna alleen nog de waarden. Heb je een dashboard aangepast, kom dan hier terug en kies „{bouton}”.",
    "enregistrement": "Opslaan…", "enregistrer": "Opslaan", "annuler": "Annuleren",
    "reprendre": "Kaarten opnieuw overnemen",
    "carte_nom": "Vision: dashboards op de schermbeveiliging",
    "carte_description": "De dashboards, weergaven en kaarten kiezen die de schermbeveiliging overneemt."
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
const API_DASH = "pc_parental/veille/dashboards";
const echap = (t) =>
  String(t ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const NOMS_ECRANS = { tele: "ecran_tele", telephone: "ecran_telephone" };
const NOMS_PUBLICS = { affichage: "public_affichage", parent: "public_parent", enfant: "public_enfant" };

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

class VisionDashboardsCard extends HTMLElement {
  constructor() {
    super();
    this.attachShadow({ mode: "open" });
    this._geste = retourGeste(this);
    this._tableaux = null;
    this._erreur = "";
    this._occupe = false;
    this._modifie = false;
    this._ouverts = new Set();
  }

  setConfig(config) { this._config = Object.assign({}, config || {}); }
  getCardSize() { return 8; }
  getGridOptions() { return { columns: "full" }; }

  set hass(hass) {
    const premier = !this._hass;
    this._hass = hass;
    const langue = suivreLangue(hass);
    if (premier) { this._langue = langue; this._charger(); }
    else if (langue !== this._langue) { this._langue = langue; this._rendre(); }
  }

  async _charger() {
    if (!this._hass) return;
    try {
      const r = await this._hass.callApi("GET", API_DASH);
      this._tableaux = r.tableaux || [];
      this._erreur = "";
      this._modifie = false;
    } catch (e) {
      this._erreur = (e && (e.message || e.body?.message)) || tr("erreur_lecture");
    }
    this._rendre();
  }

  _choix() {
    const choix = {};
    for (const t of this._tableaux || []) {
      const vues = {}, cartes = {};
      for (const v of t.vues) {
        if (!v.actif) vues[v.cle] = false;
        for (const c of v.cartes) if (!c.actif) cartes[`${v.cle}/${c.cle}`] = false;
      }
      if (t.actif || Object.keys(vues).length || Object.keys(cartes).length) {
        choix[t.adresse] = { actif: t.actif, duree: t.duree, ecrans: t.ecrans, publics: t.publics, vues, cartes };
      }
    }
    return choix;
  }

  async _enregistrer() {
    if (!this._tableaux || this._occupe) return;
    this._geste.debut();
    this._occupe = true; this._rendre();
    try {
      const r = await this._hass.callApi("POST", API_DASH, { choix: this._choix() });
      this._tableaux = r.tableaux || [];
      this._erreur = "";
      this._modifie = false;
      this._geste.ok(tr("enregistre"));
    } catch (e) {
      this._erreur = "";
      this._geste.echec(e, tr("echec_enregistrement"));
    }
    this._occupe = false;
    this._rendre();
  }

  // 15-10 (règle UX de l'auteur) : un groupe de choix multiple → <vision-choix> (étiquettes si 3 au plus tiennent, sinon liste).
  _coches(t, i, cle, noms, titre) {
    const options = Object.keys(noms).map((k) => ({ cle: k, libelle: tr(noms[k]) }));
    return `<vision-choix class="choix" multiple data-groupe="${cle}" data-i="${i}" titre="${echap(titre)}" options="${echap(JSON.stringify(options))}" valeurs="${echap(JSON.stringify(t[cle]))}"></vision-choix>`;
  }

  _vue(t, i, v, j) {
    const cle = `${t.adresse}/${v.cle}`;
    const ouverte = this._ouverts.has(cle);
    const visibles = v.cartes.filter((c) => c.comprise && c.actif).length;
    const possibles = v.cartes.filter((c) => c.comprise).length;
    let cartes = "";
    if (ouverte) {
      let section = null;
      cartes = `<div class="cartes">` + v.cartes.map((c, k) => {
        const tete = c.section !== section ? `<div class="section">${echap(c.section || tr("sans_titre"))}</div>` : "";
        section = c.section;
        return tete + (c.comprise
          ? `<label class="carte"><input type="checkbox" data-a="carte" data-i="${i}" data-j="${j}" data-k="${k}" ${c.actif ? "checked" : ""}>
               <span>${echap(c.libelle)}</span><em>${echap(c.type)}${c.cases > 1 ? ` · ${echap(tr("cases", { n: c.cases }))}` : ""}</em></label>`
          : `<div class="carte inconnue"><span>${echap(c.libelle)}</span><em>${echap(c.type)} · ${echap(tr("non_reprise"))}</em></div>`);
      }).join("") + `</div>`;
    }
    return `<div class="vue">
      <div class="ligne">
        <input type="checkbox" data-a="vue" data-i="${i}" data-j="${j}" ${v.actif ? "checked" : ""} ${possibles ? "" : "disabled"}>
        <button class="titre" data-a="ouvrir" data-c="${echap(cle)}">${echap(v.titre)}</button>
        <span class="compte">${echap(possibles ? tr("compte_cartes", { visibles, possibles }) : tr("aucune_carte"))}</span>
        <button class="fleche" data-a="ouvrir" data-c="${echap(cle)}">${ouverte ? "▾" : "▸"}</button>
      </div>${cartes}</div>`;
  }

  _tableau(t, i) {
    const detail = !t.actif ? "" : `
      <div class="reglages">
        <label>${echap(tr("duree"))} <input type="number" min="5" max="120" value="${t.duree}" data-a="duree" data-i="${i}"> s</label>
        ${this._coches(t, i, "ecrans", NOMS_ECRANS, tr("choix_ecrans"))}
        ${this._coches(t, i, "publics", NOMS_PUBLICS, tr("choix_publics"))}
      </div>
      <div class="vues">${t.vues.map((v, j) => this._vue(t, i, v, j)).join("")}</div>`;
    const vues = trn("vues", t.vues.length);
    const note = t.lisible
      ? (t.actif && t.repris_le
        ? tr("vues_reprises", { vues, date: trDate(new Date(t.repris_le * 1000), { day: "numeric", month: "long", hour: "2-digit", minute: "2-digit" }) })
        : vues)
      : tr(t.genere ? "genere" : "vide");
    return `<div class="tableau ${t.actif ? "actif" : ""}">
      <div class="ligne tete">
        <label class="bascule"><input type="checkbox" data-a="actif" data-i="${i}" ${t.actif ? "checked" : ""} ${t.lisible ? "" : "disabled"}><b>${echap(t.titre)}</b></label>
        <span class="compte">${echap(note)}</span>
      </div>${detail}</div>`;
  }

  _rendre() {
    const corps = this._tableaux === null
      ? `<p class="info">${this._erreur ? echap(this._erreur) : echap(tr("chargement"))}</p>`
      : this._tableaux.map((t, i) => this._tableau(t, i)).join("") || `<p class="info">${echap(tr("aucun_tableau"))}</p>`;
    this.shadowRoot.innerHTML = `
      <style>
        ha-card { padding: 16px; }
        input[type="checkbox"] { width:20px; height:20px; margin:2px 4px 2px 0; flex:none; }
        h2 { margin: 0 0 4px; font-size: 1.15rem; font-weight: 600; }
        p.aide { margin: 0 0 14px; color: var(--secondary-text-color); font-size: .9rem; line-height: 1.4; }
        .info { color: var(--secondary-text-color); }
        .erreur { color: var(--error-color); margin: 8px 0; }
        .tableau { border: 1px solid var(--divider-color); border-radius: 12px; padding: 10px 12px; margin-bottom: 8px; }
        .tableau.actif { border-color: var(--primary-color); }
        .ligne { display: flex; align-items: center; gap: 8px; min-height: 36px; }
        .tete b { font-weight: 600; }
        .bascule { display: flex; align-items: center; gap: 8px; flex: 1; cursor: pointer; }
        .compte { color: var(--secondary-text-color); font-size: .82rem; white-space: nowrap; }
        .reglages { display: flex; flex-wrap: wrap; gap: 8px 18px; align-items: center; padding: 6px 0 8px 28px; font-size: .9rem; }
        .reglages input[type=number] { width: 62px; padding: 4px 6px; border: 1px solid var(--divider-color); border-radius: 6px; background: var(--card-background-color); color: var(--primary-text-color); }
        .choix { flex: 1 1 200px; min-width: 0; }
        .vues { padding-left: 28px; }
        .vue { border-top: 1px solid var(--divider-color); }
        button.titre, button.fleche { background: none; border: 0; color: var(--primary-text-color); font: inherit; cursor: pointer; padding: 6px 2px; text-align: left; }
        button.titre { flex: 1; }
        button.fleche { color: var(--secondary-text-color); }
        .cartes { display: grid; grid-template-columns: repeat(auto-fill, minmax(230px, 1fr)); gap: 2px 14px; padding: 2px 0 10px 28px; }
        .section { grid-column: 1 / -1; margin-top: 6px; font-size: .75rem; letter-spacing: .06em; text-transform: uppercase; color: var(--secondary-text-color); }
        .carte { display: flex; align-items: center; gap: 6px; min-height: 30px; cursor: pointer; min-width: 0; }
        .carte span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
        .carte em { color: var(--secondary-text-color); font-size: .78rem; font-style: normal; white-space: nowrap; }
        .carte.inconnue { opacity: .5; cursor: default; padding-left: 24px; }
        .pied { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; margin-top: 12px; }
        .pied button { max-width: 100%; white-space: normal; text-align: start; }
        .pied button { padding: 8px 16px; border-radius: 8px; border: 0; font: inherit; cursor: pointer; }
        .pied .principal { background: var(--primary-color); color: var(--text-primary-color, #fff); }
        .pied .second { background: none; color: var(--primary-color); }
        .pied button[disabled] { opacity: .5; cursor: default; }
      </style>
      <ha-card>
        <h2>${echap(tr("titre"))}</h2>
        <p class="aide">${echap(tr("aide_1"))}</p>
        <p class="aide">${echap(tr("aide_2", { bouton: tr("reprendre") }))}</p>
        ${this._erreur && this._tableaux !== null ? `<div class="erreur">${echap(this._erreur)}</div>` : ""}
        ${corps}
        <div class="pied">
          <button class="principal" data-a="enregistrer" ${this._modifie && !this._occupe ? "" : "disabled"}>${echap(tr(this._occupe ? "enregistrement" : "enregistrer"))}</button>
          <button class="second" data-a="annuler" ${this._modifie ? "" : "disabled"}>${echap(tr("annuler"))}</button>
          <button class="second" data-a="reprendre" ${this._occupe ? "disabled" : ""}>${echap(tr("reprendre"))}</button>
        </div>
      </ha-card>`;
    this.shadowRoot.querySelectorAll("[data-a]").forEach((el) => {
      const a = el.dataset.a;
      if (el.tagName === "BUTTON") {
        el.addEventListener("click", () => {
          if (a === "enregistrer") return this._enregistrer();
          if (a === "annuler") return this._charger();
          if (a === "reprendre") return this._enregistrer();
          if (a === "ouvrir") { const c = el.dataset.c; this._ouverts.has(c) ? this._ouverts.delete(c) : this._ouverts.add(c); this._rendre(); }
        });
        return;
      }
      el.addEventListener("change", () => {
        const t = this._tableaux[+el.dataset.i];
        if (!t) return;
        if (a === "actif") t.actif = el.checked;
        else if (a === "duree") t.duree = Math.max(5, Math.min(120, parseInt(el.value, 10) || 25));
        else if (a === "vue") t.vues[+el.dataset.j].actif = el.checked;
        else if (a === "carte") t.vues[+el.dataset.j].cartes[+el.dataset.k].actif = el.checked;
        this._modifie = true;
        this._rendre();
      });
    });
    // Écrans et publics : <vision-choix> rend la nouvelle liste ; on retrouve l'option touchée et on garde la même
    // règle (jamais vide : la dernière décochée reste).
    this.shadowRoot.querySelectorAll("vision-choix[data-groupe]").forEach((el) => {
      el.addEventListener("vision-choix", (ev) => {
        const t = this._tableaux[+el.dataset.i];
        if (!t) return;
        const a = el.dataset.groupe;
        const apres = ev.detail.valeurs;
        const cles = el.options.map((o) => String(o.cle));
        const k = apres.find((x) => !t[a].includes(x)) ?? t[a].find((x) => cles.includes(x) && !apres.includes(x));
        if (k === undefined) return;
        t[a] = apres.includes(k) ? Array.from(new Set([...t[a], k])) : t[a].filter((x) => x !== k);
        if (!t[a].length) t[a] = [k];
        this._modifie = true;
        this._rendre();
      });
    });
  }
}

const _dejaVisionDashboardsCard = !!customElements.get("vision-dashboards-card");
if (!_dejaVisionDashboardsCard) customElements.define("vision-dashboards-card", VisionDashboardsCard);
window.customCards = window.customCards || [];
if (!_dejaVisionDashboardsCard) window.customCards.push({ type: "vision-dashboards-card", name: tr("carte_nom"), description: tr("carte_description") });
