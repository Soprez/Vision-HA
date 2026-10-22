/*
 * Vision — carte « Vue d'ensemble » (2.5.12.14, retour de l'auteur : « je vois seulement le PC de chacun, je dois tout
 * voir et filtrer, idem pour les limites »).
 *
 * Deux vues (config.mode) : « logiciels » (logiciels et sites de tous les appareils : état, ce qui ferme, catégories) et
 * « limites » (limites de temps par logiciel de toutes les personnes). Filtres : personne, appareil, catégorie, état
 * (bloqué, ouvert, limité) et texte, « Tous » par défaut, retenus dans le navigateur. Le filtrage et la pagination se font
 * côté serveur (API pc_parental/maison, action « ensemble ») : la carte ne reçoit que 150 lignes à la fois.
 */
// ---- i18n:debut — les textes de cette carte, dans les six langues de Vision (fr source, en repli) ----
const TEXTES = /*i18n-json*/{
  "fr": {
    "carte_nom": "Vision — Vue d'ensemble",
    "carte_description": "Tous les appareils et toutes les personnes dans une liste, avec des filtres.",
    "titre_logiciels": "Logiciels et sites, tous appareils",
    "titre_limites": "Limites de temps, toutes personnes",
    "filtre_personne": "Personne",
    "filtre_appareil": "Appareil",
    "filtre_categorie": "Catégorie",
    "filtre_etat": "État",
    "toutes_personnes": "Toutes les personnes",
    "tous_appareils": "Tous les appareils",
    "toutes_categories": "Toutes les catégories",
    "etat_tous": "Tous",
    "etat_bloque": "Bloqué",
    "etat_ouvert": "Ouvert",
    "etat_limite": "Limité",
    "chercher": "Chercher un logiciel, un site, une personne, un appareil…",
    "reinitialiser": "Tout afficher",
    "total_one": "{n} ligne",
    "total_other": "{n} lignes",
    "afficher_suite": "Afficher les {n} suivantes",
    "aucune_ligne": "Rien ne correspond à ces filtres.",
    "chargement": "Chargement…",
    "logiciel": "Logiciel",
    "site": "Site",
    "limite_passe": "{passe} sur {total} aujourd’hui",
    "limite_atteinte": "Fermé jusqu’à demain : {passe} sur {total} aujourd’hui",
    "limite_min_jour_titre": "Minutes par jour",
    "limite_retirer_titre": "Retirer la limite",
    "unite_min_jour": "min/jour",
    "erreur_admin": "Cette vue est réservée aux administrateurs de Home Assistant.",
    "erreur_defaut": "La liste n'a pas pu être lue.",
    "geste_fait": "C'est fait."
  },
  "en": {
    "carte_nom": "Vision — Overview",
    "carte_description": "All devices and all people in one list, with filters.",
    "titre_logiciels": "Apps and sites, all devices",
    "titre_limites": "Time limits, all people",
    "filtre_personne": "Person",
    "filtre_appareil": "Device",
    "filtre_categorie": "Category",
    "filtre_etat": "Status",
    "toutes_personnes": "All people",
    "tous_appareils": "All devices",
    "toutes_categories": "All categories",
    "etat_tous": "All",
    "etat_bloque": "Blocked",
    "etat_ouvert": "Open",
    "etat_limite": "Limited",
    "chercher": "Search an app, a site, a person, a device…",
    "reinitialiser": "Show everything",
    "total_one": "{n} row",
    "total_other": "{n} rows",
    "afficher_suite": "Show the next {n}",
    "aucune_ligne": "Nothing matches these filters.",
    "chargement": "Loading…",
    "logiciel": "App",
    "site": "Site",
    "limite_passe": "{passe} of {total} today",
    "limite_atteinte": "Blocked until tomorrow: {passe} of {total} today",
    "limite_min_jour_titre": "Minutes per day",
    "limite_retirer_titre": "Remove the limit",
    "unite_min_jour": "min/day",
    "erreur_admin": "This view is reserved for Home Assistant administrators.",
    "erreur_defaut": "The list could not be read.",
    "geste_fait": "Done."
  },
  "de": {
    "carte_nom": "Vision — Übersicht",
    "carte_description": "Alle Geräte und alle Personen in einer Liste, mit Filtern.",
    "titre_logiciels": "Apps und Websites, alle Geräte",
    "titre_limites": "Zeitlimits, alle Personen",
    "filtre_personne": "Person",
    "filtre_appareil": "Gerät",
    "filtre_categorie": "Kategorie",
    "filtre_etat": "Status",
    "toutes_personnes": "Alle Personen",
    "tous_appareils": "Alle Geräte",
    "toutes_categories": "Alle Kategorien",
    "etat_tous": "Alle",
    "etat_bloque": "Gesperrt",
    "etat_ouvert": "Offen",
    "etat_limite": "Begrenzt",
    "chercher": "App, Website, Person oder Gerät suchen…",
    "reinitialiser": "Alles anzeigen",
    "total_one": "{n} Zeile",
    "total_other": "{n} Zeilen",
    "afficher_suite": "Die nächsten {n} anzeigen",
    "aucune_ligne": "Nichts passt zu diesen Filtern.",
    "chargement": "Wird geladen…",
    "logiciel": "App",
    "site": "Website",
    "limite_passe": "{passe} von {total} heute",
    "limite_atteinte": "Gesperrt bis morgen: {passe} von {total} heute",
    "limite_min_jour_titre": "Minuten pro Tag",
    "limite_retirer_titre": "Limit entfernen",
    "unite_min_jour": "Min./Tag",
    "erreur_admin": "Diese Ansicht ist Home-Assistant-Administratoren vorbehalten.",
    "erreur_defaut": "Die Liste konnte nicht gelesen werden.",
    "geste_fait": "Erledigt."
  },
  "es": {
    "carte_nom": "Vision — Vista general",
    "carte_description": "Todos los dispositivos y todas las personas en una lista, con filtros.",
    "titre_logiciels": "Apps y sitios, todos los dispositivos",
    "titre_limites": "Límites de tiempo, todas las personas",
    "filtre_personne": "Persona",
    "filtre_appareil": "Dispositivo",
    "filtre_categorie": "Categoría",
    "filtre_etat": "Estado",
    "toutes_personnes": "Todas las personas",
    "tous_appareils": "Todos los dispositivos",
    "toutes_categories": "Todas las categorías",
    "etat_tous": "Todos",
    "etat_bloque": "Bloqueado",
    "etat_ouvert": "Abierto",
    "etat_limite": "Limitado",
    "chercher": "Buscar una app, un sitio, una persona, un dispositivo…",
    "reinitialiser": "Mostrar todo",
    "total_one": "{n} fila",
    "total_other": "{n} filas",
    "afficher_suite": "Mostrar las {n} siguientes",
    "aucune_ligne": "Nada coincide con estos filtros.",
    "chargement": "Cargando…",
    "logiciel": "App",
    "site": "Sitio",
    "limite_passe": "{passe} de {total} hoy",
    "limite_atteinte": "Bloqueado hasta mañana: {passe} de {total} hoy",
    "limite_min_jour_titre": "Minutos al día",
    "limite_retirer_titre": "Quitar el límite",
    "unite_min_jour": "min/día",
    "erreur_admin": "Esta vista está reservada a los administradores de Home Assistant.",
    "erreur_defaut": "No se pudo leer la lista.",
    "geste_fait": "Hecho."
  },
  "it": {
    "carte_nom": "Vision — Panoramica",
    "carte_description": "Tutti i dispositivi e tutte le persone in un elenco, con filtri.",
    "titre_logiciels": "App e siti, tutti i dispositivi",
    "titre_limites": "Limiti di tempo, tutte le persone",
    "filtre_personne": "Persona",
    "filtre_appareil": "Dispositivo",
    "filtre_categorie": "Categoria",
    "filtre_etat": "Stato",
    "toutes_personnes": "Tutte le persone",
    "tous_appareils": "Tutti i dispositivi",
    "toutes_categories": "Tutte le categorie",
    "etat_tous": "Tutti",
    "etat_bloque": "Bloccato",
    "etat_ouvert": "Aperto",
    "etat_limite": "Limitato",
    "chercher": "Cerca un'app, un sito, una persona, un dispositivo…",
    "reinitialiser": "Mostra tutto",
    "total_one": "{n} riga",
    "total_other": "{n} righe",
    "afficher_suite": "Mostra le {n} successive",
    "aucune_ligne": "Nessun risultato con questi filtri.",
    "chargement": "Caricamento…",
    "logiciel": "App",
    "site": "Sito",
    "limite_passe": "{passe} su {total} oggi",
    "limite_atteinte": "Bloccato fino a domani: {passe} su {total} oggi",
    "limite_min_jour_titre": "Minuti al giorno",
    "limite_retirer_titre": "Rimuovi il limite",
    "unite_min_jour": "min/giorno",
    "erreur_admin": "Questa vista è riservata agli amministratori di Home Assistant.",
    "erreur_defaut": "Impossibile leggere l'elenco.",
    "geste_fait": "Fatto."
  },
  "nl": {
    "carte_nom": "Vision — Overzicht",
    "carte_description": "Alle apparaten en alle personen in één lijst, met filters.",
    "titre_logiciels": "Apps en sites, alle apparaten",
    "titre_limites": "Tijdslimieten, alle personen",
    "filtre_personne": "Persoon",
    "filtre_appareil": "Apparaat",
    "filtre_categorie": "Categorie",
    "filtre_etat": "Status",
    "toutes_personnes": "Alle personen",
    "tous_appareils": "Alle apparaten",
    "toutes_categories": "Alle categorieën",
    "etat_tous": "Alle",
    "etat_bloque": "Geblokkeerd",
    "etat_ouvert": "Open",
    "etat_limite": "Beperkt",
    "chercher": "Zoek een app, een site, een persoon, een apparaat…",
    "reinitialiser": "Alles tonen",
    "total_one": "{n} rij",
    "total_other": "{n} rijen",
    "afficher_suite": "De volgende {n} tonen",
    "aucune_ligne": "Niets komt overeen met deze filters.",
    "chargement": "Laden…",
    "logiciel": "App",
    "site": "Site",
    "limite_passe": "{passe} van {total} vandaag",
    "limite_atteinte": "Geblokkeerd tot morgen: {passe} van {total} vandaag",
    "limite_min_jour_titre": "Minuten per dag",
    "limite_retirer_titre": "Limiet verwijderen",
    "unite_min_jour": "min/dag",
    "erreur_admin": "Deze weergave is voorbehouden aan beheerders van Home Assistant.",
    "erreur_defaut": "De lijst kon niet worden gelezen.",
    "geste_fait": "Klaar."
  }
}/*i18n-json:fin*/;
// Les catégories (étiquettes) : leur nom affiché dans la langue de l'utilisateur.
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
const API = "pc_parental/maison";
const PAS = 150;
const ETATS = ["bloque", "limite", "ouvert"];
const VIDE = { personne: "", appareil: "", categorie: "", etat: "", q: "" };
const esc = (t) =>
  String(t ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const duree = (m) => (m >= 60 ? `${Math.floor(m / 60)} h${m % 60 ? " " + String(m % 60).padStart(2, "0") : ""}` : `${m} min`);
const messageErreur = (e) => {
  const b = e && e.body;
  if (e && (e.status_code === 403 || (b && b.error === "admin"))) return tr("erreur_admin");
  return tr("erreur_defaut");
};

class VisionEnsembleCard extends HTMLElement {
  setConfig(config) {
    this._config = config || {};
    this._mode = this._config.mode === "limites" ? "limites" : "logiciels";
    this._cle = "vision-ensemble-" + this._mode;
    this._f = Object.assign({}, VIDE, this._relire());
    this._lignes = [];
    this._total = 0;
    this._compte = { bloque: 0, limite: 0, ouvert: 0 };
    this._facettes = { personnes: [], appareils: [], categories: [] };
    this._charge = false;
    this._erreur = "";
  }

  getCardSize() { return 8; }

  // Le filtre choisi est retenu dans le navigateur (jamais bloquant : un navigateur sans stockage s'en passe).
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
      this._charger(0);
      this._minuteur = setInterval(() => this._charger(0, true), 30000);
    } else if (langue !== this._langue) {
      this._langue = langue;
      this._rendre();
    }
  }

  disconnectedCallback() { clearInterval(this._minuteur); }

  // `debut` 0 : première tranche (ou relecture de tout ce qui est déjà affiché) ; sinon la suite, ajoutée.
  async _charger(debut, silencieux = false) {
    if (!this._hass) return;
    const dejaAffichees = this._lignes.length;
    const corps = { action: "ensemble", vue: this._mode, filtres: this._f };
    if (debut) Object.assign(corps, { debut, n: PAS });
    else Object.assign(corps, { debut: 0, n: Math.max(PAS, Math.min(400, dejaAffichees)) });
    const filtres = JSON.stringify(this._f);
    try {
      const r = await this._hass.callApi("POST", API, corps);
      if (filtres !== JSON.stringify(this._f)) return;  // le filtre a changé pendant la lecture : une autre lecture est partie
      this._lignes = debut ? this._lignes.concat(r.lignes || []) : (r.lignes || []);
      this._total = r.total || 0;
      this._compte = r.compte || this._compte;
      this._facettes = r.facettes || this._facettes;
      this._erreur = "";
    } catch (e) {
      if (silencieux && this._charge) return;
      this._erreur = messageErreur(e);
    }
    this._charge = true;
    if (silencieux && this.shadowRoot.activeElement && this.shadowRoot.activeElement.id === "q") return;  // pas sous les doigts
    this._rendre();
  }

  _filtrer(champ, valeur) {
    this._f[champ] = valeur;
    if (champ === "personne" && this._f.appareil) {
      const a = this._facettes.appareils.find((x) => x.id === this._f.appareil);
      if (a && valeur && a.personne !== valeur) this._f.appareil = "";
    }
    this._retenir();
    this._lignes = [];
    this._charge = false;
    this._rendre();
    this._charger(0);
  }

  async _limite(pc, nom, minutes) {
    try {
      await this._hass.callApi("POST", API, { action: "limite", pc, nom, minutes });
    } catch (e) {
      this._erreur = messageErreur(e);
    }
    this._charger(0);
  }

  _options(liste, valeur, tous) {
    return `<option value="">${esc(tous)}</option>` + liste.map(([cle, libelle]) =>
      `<option value="${esc(cle)}" ${cle === valeur ? "selected" : ""}>${esc(libelle)}</option>`).join("");
  }

  _barre() {
    const f = this._f;
    const fa = this._facettes;
    const appareils = fa.appareils.filter((a) => !f.personne || a.personne === f.personne);
    const etat = (cle, libelle, n) =>
      `<button class="etat ${cle}" data-etat="${cle}" aria-pressed="${f.etat === cle}">${esc(libelle)}${n === undefined ? "" : ` <span class="n">${n}</span>`}</button>`;
    const sale = Object.keys(VIDE).some((k) => f[k]);
    return `<div class="barre">
      <input type="search" id="q" value="${esc(f.q)}" placeholder="${esc(tr("chercher"))}" aria-label="${esc(tr("chercher"))}">
      <label>${esc(tr("filtre_personne"))}<select data-filtre="personne">${this._options(fa.personnes.map((p) => [p.id, p.nom]), f.personne, tr("toutes_personnes"))}</select></label>
      <label>${esc(tr("filtre_appareil"))}<select data-filtre="appareil">${this._options(appareils.map((a) => [a.id, a.nom]), f.appareil, tr("tous_appareils"))}</select></label>
      <label>${esc(tr("filtre_categorie"))}<select data-filtre="categorie">${this._options(fa.categories.map((c) => [c, trCat(c)]), f.categorie, tr("toutes_categories"))}</select></label>
    </div>
    <div class="etats" role="group" aria-label="${esc(tr("filtre_etat"))}">
      ${etat("", tr("etat_tous"))}${etat("bloque", tr("etat_bloque"), this._compte.bloque)}${etat("limite", tr("etat_limite"), this._compte.limite)}${this._mode === "limites" ? "" : etat("ouvert", tr("etat_ouvert"), this._compte.ouvert)}
      <span class="total">${esc(trn("total", this._total))}</span>
      ${sale ? `<button class="lien" data-raz>${esc(tr("reinitialiser"))}</button>` : ""}
    </div>`;
  }

  _ligneLogiciel(l) {
    const motif = l.motif ? `<span class="motif">${esc(l.motif)}</span>` : "";
    const lim = l.etat === "limite" ? ` · ${esc(duree(l.minutes))}` : "";
    const cats = (l.categories || []).map((c) => `<span class="cat">${esc(trCat(c))}</span>`).join("");
    return `<div class="ligne">
      <span class="pastille ${l.etat}" role="img" aria-label="${esc(tr("etat_" + l.etat))}"></span>
      <div class="corps"><b>${esc(l.libelle)}</b>
        <span class="sous">${esc(tr(l.genre === "apps" ? "logiciel" : "site"))}${l.libelle !== l.nom ? " · " + esc(l.nom) : ""}</span>
        <span class="sous">${esc(l.prenom)} · ${esc(l.appareil)}</span>${cats ? `<span class="cats">${cats}</span>` : ""}</div>
      <div class="etatl ${l.etat}">${esc(tr("etat_" + l.etat))}${lim}${motif}</div>
    </div>`;
  }

  _ligneLimite(l) {
    const passe = Math.floor((l.passe || 0) / 60);
    const part = Math.min(100, Math.round(((l.passe || 0) / (l.minutes * 60)) * 100));
    const cats = (l.categories || []).map((c) => `<span class="cat">${esc(trCat(c))}</span>`).join("");
    return `<div class="ligne ${l.etat === "bloque" ? "atteinte" : ""}">
      <span class="pastille ${l.etat}" role="img" aria-label="${esc(tr("etat_" + l.etat))}"></span>
      <div class="corps"><b>${esc(l.libelle)}</b><span class="sous">${esc(l.prenom)}</span>${cats ? `<span class="cats">${cats}</span>` : ""}
        <span class="sous">${esc(tr(l.etat === "bloque" ? "limite_atteinte" : "limite_passe", { passe: duree(passe), total: duree(l.minutes) }))}</span>
        <div class="jauge"><i style="width:${part}%"></i></div></div>
      <div class="edition">
        <input type="number" min="5" max="1440" step="5" value="${l.minutes}" data-min="${esc(l.nom)}" data-pc="${esc(l.pc)}" title="${esc(tr("limite_min_jour_titre"))}">
        <span class="sous">${esc(tr("unite_min_jour"))}</span>
        <button data-retirer="${esc(l.nom)}" data-pc="${esc(l.pc)}" title="${esc(tr("limite_retirer_titre"))}" aria-label="${esc(tr("limite_retirer_titre"))}">✕</button>
      </div>
    </div>`;
  }

  _rendre() {
    if (!this.shadowRoot) return;
    const titre = this._config.title || tr(this._mode === "limites" ? "titre_limites" : "titre_logiciels");
    let corps;
    if (this._erreur) corps = `<div class="vide" role="alert">${esc(this._erreur)}</div>`;
    else if (!this._charge) corps = `<div class="vide">${esc(tr("chargement"))}</div>`;
    else if (!this._lignes.length) corps = `<div class="vide">${esc(tr("aucune_ligne"))}</div>`;
    else {
      const une = this._mode === "limites" ? (l) => this._ligneLimite(l) : (l) => this._ligneLogiciel(l);
      const reste = this._total - this._lignes.length;
      corps = `<div class="lignes">${this._lignes.map(une).join("")}</div>` +
        (reste > 0 ? `<button class="suite" data-suite>${esc(tr("afficher_suite", { n: Math.min(PAS, reste) }))}</button>` : "");
    }
    const q = this.shadowRoot.getElementById("q");
    const garde = q && this.shadowRoot.activeElement === q ? [q.selectionStart, q.selectionEnd] : null;
    this.shadowRoot.innerHTML = `<style>${this._style()}</style><ha-card><div class="titre">${esc(titre)}</div>${this._barre()}${corps}</ha-card>`;
    this._brancher();
    if (garde) {
      const neuf = this.shadowRoot.getElementById("q");
      if (neuf) { neuf.focus(); neuf.setSelectionRange(garde[0], garde[1]); }
    }
  }

  _brancher() {
    const r = this.shadowRoot;
    const q = r.getElementById("q");
    if (q) q.addEventListener("input", () => {
      clearTimeout(this._attente);
      this._attente = setTimeout(() => this._filtrer("q", q.value), 250);
    });
    r.querySelectorAll("select[data-filtre]").forEach((s) => s.addEventListener("change", () => this._filtrer(s.dataset.filtre, s.value)));
    r.querySelectorAll("button[data-etat]").forEach((b) => b.addEventListener("click", () =>
      this._filtrer("etat", this._f.etat === b.dataset.etat ? "" : b.dataset.etat)));
    const raz = r.querySelector("[data-raz]");
    if (raz) raz.addEventListener("click", () => { this._f = Object.assign({}, VIDE); this._retenir(); this._lignes = []; this._charge = false; this._rendre(); this._charger(0); });
    const suite = r.querySelector("[data-suite]");
    if (suite) suite.addEventListener("click", () => this._charger(this._lignes.length));
    r.querySelectorAll("input[data-min]").forEach((c) => c.addEventListener("change", () => {
      const m = Math.round(Number(c.value) || 0);
      if (m < 5 || m > 1440) { this._rendre(); return; }
      this._limite(c.dataset.pc, c.dataset.min, m);
    }));
    r.querySelectorAll("button[data-retirer]").forEach((b) => b.addEventListener("click", () => this._limite(b.dataset.pc, b.dataset.retirer, 0)));
  }

  _style() {
    return `
      :host { display:block; }
      ha-card { padding:12px 16px 16px; }
      .titre { font-size:1.15rem; font-weight:500; margin-bottom:10px; }
      .barre { display:flex; flex-wrap:wrap; gap:8px; align-items:flex-end; margin-bottom:8px; }
      .barre #q { flex:1 1 100%; min-width:0; box-sizing:border-box; min-height:44px; padding:8px 12px; border:1px solid var(--divider-color); border-radius:8px; background:var(--card-background-color); color:var(--primary-text-color); font:inherit; }
      .barre label { display:flex; flex-direction:column; gap:2px; flex:1 1 160px; min-width:0; font-size:.8rem; color:var(--secondary-text-color); }
      .barre select { min-height:44px; min-width:0; max-width:100%; padding:6px 8px; border:1px solid var(--divider-color); border-radius:8px; background:var(--card-background-color); color:var(--primary-text-color); font:inherit; }
      .etats { display:flex; flex-wrap:wrap; gap:6px; align-items:center; margin-bottom:8px; }
      .etat { min-height:44px; padding:6px 14px; border:1px solid var(--divider-color); border-radius:22px; background:none; color:var(--primary-text-color); font:inherit; cursor:pointer; }
      .etat[aria-pressed="true"] { background:var(--primary-color); color:var(--text-primary-color,#fff); border-color:var(--primary-color); }
      .etat .n { opacity:.75; font-size:.85em; }
      .total { margin-left:auto; color:var(--secondary-text-color); font-size:.85rem; }
      .lien { background:none; border:none; color:var(--primary-color); cursor:pointer; font:inherit; min-height:44px; padding:0 8px; }
      .lignes { border-top:1px solid var(--divider-color); }
      .ligne { display:flex; gap:12px; align-items:center; padding:10px 4px; border-bottom:1px solid var(--divider-color); }
      .corps { flex:1; min-width:0; display:flex; flex-direction:column; gap:2px; overflow-wrap:anywhere; }
      .sous { font-size:.8rem; color:var(--secondary-text-color); }
      .cats { display:flex; flex-wrap:wrap; gap:4px; }
      .cat { font-size:.72rem; padding:1px 8px; border-radius:10px; border:1px solid var(--divider-color); color:var(--secondary-text-color); }
      .pastille { flex:none; width:12px; height:12px; border-radius:50%; background:var(--success-color,#2e7d32); }
      .pastille.bloque { background:var(--error-color,#c62828); }
      .pastille.limite { background:var(--warning-color,#ef6c00); }
      .etatl { flex:none; text-align:right; font-weight:500; display:flex; flex-direction:column; align-items:flex-end; max-width:40%; overflow-wrap:anywhere; }
      .etatl.bloque { color:var(--error-color,#c62828); }
      .etatl.ouvert { color:var(--success-color,#2e7d32); }
      .etatl.limite { color:var(--warning-color,#ef6c00); }
      .motif { font-weight:400; font-size:.78rem; color:var(--secondary-text-color); }
      .jauge { height:6px; border-radius:3px; background:var(--divider-color); overflow:hidden; margin-top:4px; }
      .jauge i { display:block; height:100%; background:var(--warning-color,#ef6c00); }
      .atteinte .jauge i { background:var(--error-color,#c62828); }
      .edition { flex:none; display:flex; align-items:center; gap:6px; }
      .edition input { width:5em; min-height:44px; box-sizing:border-box; padding:4px 8px; border:1px solid var(--divider-color); border-radius:8px; background:var(--card-background-color); color:var(--primary-text-color); font:inherit; }
      .edition button { min-width:44px; min-height:44px; border:1px solid var(--divider-color); border-radius:8px; background:none; color:var(--primary-text-color); cursor:pointer; }
      .suite { display:block; width:100%; min-height:44px; margin-top:10px; border:1px solid var(--divider-color); border-radius:8px; background:none; color:var(--primary-color); font:inherit; cursor:pointer; }
      .vide { padding:16px 4px; color:var(--secondary-text-color); }
      @container (max-width: 600px) { .ligne { flex-wrap:wrap; } .etatl { max-width:none; align-items:flex-start; text-align:left; } .edition { width:100%; } }
      ha-card { container-type:inline-size; }
    `;
  }
}

// Un seul exemplaire, même si une ancienne copie est encore chargée à côté.
const _dejaVisionEnsembleCard = !!customElements.get("vision-ensemble-card");
if (!_dejaVisionEnsembleCard) customElements.define("vision-ensemble-card", VisionEnsembleCard);
window.customCards = window.customCards || [];
if (!_dejaVisionEnsembleCard) window.customCards.push({
  type: "vision-ensemble-card",
  name: tr("carte_nom"),
  description: tr("carte_description"),
});
