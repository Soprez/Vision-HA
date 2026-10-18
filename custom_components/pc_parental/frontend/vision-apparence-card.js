/*
 * Vision — carte « Apparence » (1.95.0, point 16.2), réservée aux administrateurs.
 *
 * La palette du tableau de bord Vision parmi les thèmes de l'appli, le mode (automatique, clair, sombre) et une
 * couleur d'accent facultative. Vision en fait le thème Home Assistant « Vision », que portent toutes les vues du
 * tableau et ses cartes ; le choix est gardé.
 */
// ---- i18n:debut — les textes de cette carte, dans les six langues de Vision (fr source, en repli) ----
const TEXTES = /*i18n-json*/{
  "fr": {
    "mode_auto": "Automatique", "mode_clair": "Clair", "mode_sombre": "Sombre",
    "erreur_admin": "Réservé aux administrateurs de Home Assistant.",
    "erreur_loading": "Vision démarre : réessaie dans un instant.",
    "erreur_pc": "Cet appareil n'existe plus dans Vision.",
    "erreur_json": "La demande n'a pas pu être lue : réessaie.",
    "erreur_module": "Cette fonction est désactivée dans Vision.",
    "erreur_minutes": "Indique une durée.",
    "erreur_defaut": "Ce n'est pas passé : rien n'a changé, réessaie dans un instant.",
    "geste_en_cours": "En cours…",
    "geste_fait": "C'est fait.",
    "erreur_injoignable": "Home Assistant ne répond pas ; réessaie dans un instant.",
    "applique": "Appliqué à tout le tableau Vision.",
    "echec": "L'apparence n'a pas été changée ; réessaie.",
    "texte_principal": "Texte principal", "texte_secondaire": "Texte secondaire", "bouton": "Bouton",
    "chargement": "Chargement…",
    "palette": "Palette", "mode": "Mode", "couleur_accent": "Couleur d'accent",
    "accent_choisi": "Accent choisi : {couleur}", "accent_palette": "Celle de la palette",
    "revenir_palette": "Revenir à celle de la palette",
    "titre": "Apparence",
    "aide": "Les couleurs de tout le tableau Vision : une palette de l'appli, clair, sombre ou automatique.",
    "en_savoir_plus": "En savoir plus",
    "details": "Automatique suit le réglage clair ou sombre de chaque utilisateur de Home Assistant. Le thème s'appelle « Vision » : les autres tableaux de bord ne changent pas. La couleur d'accent remplace celle de la palette pour les boutons et les titres.",
    "carte_nom": "Vision — Apparence",
    "carte_description": "Palette, mode clair / sombre / automatique et accent du tableau Vision."
  },
  "en": {
    "mode_auto": "Automatic", "mode_clair": "Light", "mode_sombre": "Dark",
    "erreur_admin": "Only Home Assistant administrators can do this.",
    "erreur_loading": "Vision is starting: try again in a moment.",
    "erreur_pc": "This device no longer exists in Vision.",
    "erreur_json": "The request could not be read: try again.",
    "erreur_module": "This feature is turned off in Vision.",
    "erreur_minutes": "Enter a duration.",
    "erreur_defaut": "That didn't work: nothing changed, try again in a moment.",
    "geste_en_cours": "Working…",
    "geste_fait": "Done.",
    "erreur_injoignable": "Home Assistant isn't responding; try again in a moment.",
    "applique": "Applied to the whole Vision dashboard.",
    "echec": "The appearance wasn't changed; try again.",
    "texte_principal": "Main text", "texte_secondaire": "Secondary text", "bouton": "Button",
    "chargement": "Loading…",
    "palette": "Palette", "mode": "Mode", "couleur_accent": "Accent colour",
    "accent_choisi": "Chosen accent: {couleur}", "accent_palette": "The palette's own",
    "revenir_palette": "Go back to the palette's colour",
    "titre": "Appearance",
    "aide": "The colours of the whole Vision dashboard: a palette from the app, light, dark or automatic.",
    "en_savoir_plus": "Learn more",
    "details": "Automatic follows each Home Assistant user's light or dark setting. The theme is called “Vision”: other dashboards don't change. The accent colour replaces the palette's for buttons and titles.",
    "carte_nom": "Vision — Appearance",
    "carte_description": "Palette, light / dark / automatic mode and accent of the Vision dashboard."
  },
  "de": {
    "mode_auto": "Automatisch", "mode_clair": "Hell", "mode_sombre": "Dunkel",
    "erreur_admin": "Nur für Home Assistant-Administratoren.",
    "erreur_loading": "Vision startet gerade: versuch es gleich noch einmal.",
    "erreur_pc": "Dieses Gerät gibt es in Vision nicht mehr.",
    "erreur_json": "Die Anfrage konnte nicht gelesen werden: versuch es noch einmal.",
    "erreur_module": "Diese Funktion ist in Vision ausgeschaltet.",
    "erreur_minutes": "Gib eine Dauer an.",
    "erreur_defaut": "Das hat nicht geklappt: nichts wurde geändert, versuch es gleich noch einmal.",
    "geste_en_cours": "Wird ausgeführt…",
    "geste_fait": "Erledigt.",
    "erreur_injoignable": "Home Assistant antwortet nicht; versuch es gleich noch einmal.",
    "applique": "Für das ganze Vision-Dashboard übernommen.",
    "echec": "Das Aussehen wurde nicht geändert; versuch es noch einmal.",
    "texte_principal": "Haupttext", "texte_secondaire": "Nebentext", "bouton": "Schaltfläche",
    "chargement": "Wird geladen…",
    "palette": "Palette", "mode": "Modus", "couleur_accent": "Akzentfarbe",
    "accent_choisi": "Gewählter Akzent: {couleur}", "accent_palette": "Die der Palette",
    "revenir_palette": "Zurück zur Farbe der Palette",
    "titre": "Aussehen",
    "aide": "Die Farben des ganzen Vision-Dashboards: eine Palette aus der App, hell, dunkel oder automatisch.",
    "en_savoir_plus": "Mehr erfahren",
    "details": "Automatisch folgt der Hell- oder Dunkel-Einstellung jedes Home Assistant-Benutzers. Das Design heißt „Vision“: andere Dashboards ändern sich nicht. Die Akzentfarbe ersetzt die der Palette für Schaltflächen und Überschriften.",
    "carte_nom": "Vision — Aussehen",
    "carte_description": "Palette, heller / dunkler / automatischer Modus und Akzent des Vision-Dashboards."
  },
  "es": {
    "mode_auto": "Automático", "mode_clair": "Claro", "mode_sombre": "Oscuro",
    "erreur_admin": "Solo para administradores de Home Assistant.",
    "erreur_loading": "Vision se está iniciando: inténtalo de nuevo en un momento.",
    "erreur_pc": "Este dispositivo ya no existe en Vision.",
    "erreur_json": "No se pudo leer la solicitud: inténtalo de nuevo.",
    "erreur_module": "Esta función está desactivada en Vision.",
    "erreur_minutes": "Indica una duración.",
    "erreur_defaut": "No ha funcionado: no ha cambiado nada, inténtalo de nuevo en un momento.",
    "geste_en_cours": "En curso…",
    "geste_fait": "Hecho.",
    "erreur_injoignable": "Home Assistant no responde; inténtalo de nuevo en un momento.",
    "applique": "Aplicado a todo el panel de Vision.",
    "echec": "No se ha cambiado la apariencia; inténtalo de nuevo.",
    "texte_principal": "Texto principal", "texte_secondaire": "Texto secundario", "bouton": "Botón",
    "chargement": "Cargando…",
    "palette": "Paleta", "mode": "Modo", "couleur_accent": "Color de acento",
    "accent_choisi": "Acento elegido: {couleur}", "accent_palette": "El de la paleta",
    "revenir_palette": "Volver al de la paleta",
    "titre": "Apariencia",
    "aide": "Los colores de todo el panel de Vision: una paleta de la app, claro, oscuro o automático.",
    "en_savoir_plus": "Más información",
    "details": "Automático sigue el ajuste claro u oscuro de cada usuario de Home Assistant. El tema se llama «Vision»: los demás paneles no cambian. El color de acento sustituye al de la paleta en botones y títulos.",
    "carte_nom": "Vision — Apariencia",
    "carte_description": "Paleta, modo claro / oscuro / automático y acento del panel de Vision."
  },
  "it": {
    "mode_auto": "Automatico", "mode_clair": "Chiaro", "mode_sombre": "Scuro",
    "erreur_admin": "Riservato agli amministratori di Home Assistant.",
    "erreur_loading": "Vision si sta avviando: riprova tra un momento.",
    "erreur_pc": "Questo dispositivo non esiste più in Vision.",
    "erreur_json": "Non è stato possibile leggere la richiesta: riprova.",
    "erreur_module": "Questa funzione è disattivata in Vision.",
    "erreur_minutes": "Indica una durata.",
    "erreur_defaut": "Non è andata: non è cambiato nulla, riprova tra un momento.",
    "geste_en_cours": "In corso…",
    "geste_fait": "Fatto.",
    "erreur_injoignable": "Home Assistant non risponde; riprova tra un momento.",
    "applique": "Applicato a tutta la dashboard di Vision.",
    "echec": "L'aspetto non è stato cambiato; riprova.",
    "texte_principal": "Testo principale", "texte_secondaire": "Testo secondario", "bouton": "Pulsante",
    "chargement": "Caricamento…",
    "palette": "Tavolozza", "mode": "Modalità", "couleur_accent": "Colore d'accento",
    "accent_choisi": "Accento scelto: {couleur}", "accent_palette": "Quello della tavolozza",
    "revenir_palette": "Torna a quello della tavolozza",
    "titre": "Aspetto",
    "aide": "I colori di tutta la dashboard di Vision: una tavolozza dell'app, chiaro, scuro o automatico.",
    "en_savoir_plus": "Scopri di più",
    "details": "Automatico segue l'impostazione chiara o scura di ogni utente di Home Assistant. Il tema si chiama «Vision»: le altre dashboard non cambiano. Il colore d'accento sostituisce quello della tavolozza per pulsanti e titoli.",
    "carte_nom": "Vision — Aspetto",
    "carte_description": "Tavolozza, modalità chiara / scura / automatica e accento della dashboard di Vision."
  },
  "nl": {
    "mode_auto": "Automatisch", "mode_clair": "Licht", "mode_sombre": "Donker",
    "erreur_admin": "Alleen voor beheerders van Home Assistant.",
    "erreur_loading": "Vision start op: probeer het zo opnieuw.",
    "erreur_pc": "Dit apparaat bestaat niet meer in Vision.",
    "erreur_json": "Het verzoek kon niet worden gelezen: probeer het opnieuw.",
    "erreur_module": "Deze functie staat uit in Vision.",
    "erreur_minutes": "Geef een duur op.",
    "erreur_defaut": "Dat is niet gelukt: er is niets veranderd, probeer het zo opnieuw.",
    "geste_en_cours": "Bezig…",
    "geste_fait": "Klaar.",
    "erreur_injoignable": "Home Assistant reageert niet; probeer het zo opnieuw.",
    "applique": "Toegepast op het hele Vision-dashboard.",
    "echec": "Het uiterlijk is niet gewijzigd; probeer het opnieuw.",
    "texte_principal": "Hoofdtekst", "texte_secondaire": "Bijtekst", "bouton": "Knop",
    "chargement": "Laden…",
    "palette": "Palet", "mode": "Modus", "couleur_accent": "Accentkleur",
    "accent_choisi": "Gekozen accent: {couleur}", "accent_palette": "Die van het palet",
    "revenir_palette": "Terug naar die van het palet",
    "titre": "Uiterlijk",
    "aide": "De kleuren van het hele Vision-dashboard: een palet uit de app, licht, donker of automatisch.",
    "en_savoir_plus": "Meer weten",
    "details": "Automatisch volgt de lichte of donkere instelling van elke Home Assistant-gebruiker. Het thema heet „Vision”: andere dashboards veranderen niet. De accentkleur vervangt die van het palet voor knoppen en titels.",
    "carte_nom": "Vision — Uiterlijk",
    "carte_description": "Palet, lichte / donkere / automatische modus en accent van het Vision-dashboard."
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
const API_APPARENCE = "pc_parental/apparence";
const echA = (t) =>
  String(t ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const MODES_A = [["auto", "mode_auto"], ["clair", "mode_clair"], ["sombre", "mode_sombre"]];

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

class VisionApparenceCard extends HTMLElement {
  constructor() {
    super();
    this.attachShadow({ mode: "open" });
    this._geste = retourGeste(this);
    this._d = null;
    this._erreur = "";
    this._occupe = false;
    this._fait = "";
  }

  setConfig(config) { this._config = Object.assign({}, config || {}); }
  getCardSize() { return 6; }

  set hass(hass) {
    const premier = !this._hass;
    this._hass = hass;
    const langue = suivreLangue(hass);
    if (premier) { this._langue = langue; this._charger(); }
    else if (langue !== this._langue) { this._langue = langue; this._dessiner(); }
  }

  async _charger() {
    try {
      this._d = await this._hass.callApi("GET", API_APPARENCE);
      this._erreur = "";
    } catch (e) {
      this._erreur = (e && e.body && e.body.retour) || (e && e.status_code === 403 ? tr("erreur_admin") : tr("erreur_injoignable"));
    }
    this._dessiner();
  }

  async _appliquer(changement) {
    if (this._occupe || !this._d) return;
    const r = Object.assign({}, this._d.reglage, changement);
    this._geste.debut();
    this._occupe = true; this._fait = ""; this._dessiner();
    try {
      this._d = await this._hass.callApi("POST", API_APPARENCE, r);
      this._erreur = "";
      this._geste.ok(tr("applique"));
    } catch (e) {
      this._erreur = "";
      this._geste.echec(e, tr("echec"));
    }
    this._occupe = false;
    this._dessiner();
  }

  _apercu(c, titre) {
    return `<div class="apercu" style="background:${c.fond};color:${c.encre}">
        <div class="ap-titre">${echA(titre)}</div>
        <div class="ap-carte" style="background:${c.carte};border-color:${c.ligne}">
          <div style="color:${c.encre}">${echA(tr("texte_principal"))}</div>
          <div style="color:${c.encre2}">${echA(tr("texte_secondaire"))}</div>
          <span class="ap-bouton" style="background:${c.principale};color:${c.sur_principale}">${echA(tr("bouton"))}</span>
        </div>
      </div>`;
  }

  _dessiner() {
    const d = this._d;
    let corps;
    if (!d) {
      corps = this._erreur ? `<div class="erreur">${echA(this._erreur)}</div>` : `<div class="vide">${echA(tr("chargement"))}</div>`;
    } else {
      const r = d.reglage;
      const pastilles = d.palettes.map((p) => `
        <button class="palette ${p.nom === r.palette ? "choisie" : ""}" data-palette="${echA(p.nom)}" ${this._occupe ? "disabled" : ""}
          aria-pressed="${p.nom === r.palette}" title="${echA(p.nom)}">
          <span class="rond" style="background:${p.fond};border-color:${p.accent}"><span style="background:${p.or}"></span></span>
          <span class="pnom">${echA(p.nom)}</span>
        </button>`).join("");
      // Le mode : un choix (vision-choix, étiquettes ou liste selon la place).
      const modes = `<vision-choix data-mode titre="${echA(tr("mode"))}" valeur="${echA(r.mode)}"
        options="${echA(JSON.stringify(MODES_A.map(([m, lib]) => ({ cle: m, libelle: tr(lib) }))))}"></vision-choix>`;
      corps = `
        <div class="ligne-titre">${echA(tr("palette"))}</div>
        <div class="palettes">${pastilles}</div>
        <div class="ligne-titre">${echA(tr("mode"))}</div>
        ${modes}
        <div class="ligne-titre">${echA(tr("couleur_accent"))}</div>
        <div class="accent">
          <input type="color" value="${echA(r.accent || "#B3282D")}" data-accent ${this._occupe ? "disabled" : ""} aria-label="${echA(tr("couleur_accent"))}">
          <span>${r.accent ? echA(tr("accent_choisi", { couleur: r.accent })) : echA(tr("accent_palette"))}</span>
          ${r.accent ? `<button class="lien" data-sans-accent ${this._occupe ? "disabled" : ""}>${echA(tr("revenir_palette"))}</button>` : ""}
        </div>
        <div class="apercus">${this._apercu(d.apercu.clair, tr("mode_clair"))}${this._apercu(d.apercu.sombre, tr("mode_sombre"))}</div>
        ${this._erreur ? `<div class="erreur">${echA(this._erreur)}</div>` : ""}
        ${this._fait ? `<div class="fait">${echA(this._fait)}</div>` : ""}`;
    }
    this.shadowRoot.innerHTML = `
      <style>
        ha-card { padding:16px; }
        h2 { margin:0 0 4px; font-size:1.2em; }
        .aide { color:var(--secondary-text-color); margin:0 0 6px; }
        details { color:var(--secondary-text-color); margin:0 0 10px; font-size:.92em; }
        .ligne-titre { font-weight:600; margin:12px 0 6px; }
        .palettes { display:flex; flex-wrap:wrap; gap:8px; }
        .palette { display:flex; flex-direction:column; align-items:center; gap:4px; width:78px; padding:6px 2px; border-radius:12px;
          border:1px solid transparent; background:none; cursor:pointer; color:var(--primary-text-color); font:inherit; font-size:.82em; }
        .palette.choisie { border-color:var(--primary-color); background:var(--secondary-background-color); }
        .rond { width:34px; height:34px; border-radius:50%; border:3px solid; display:flex; align-items:center; justify-content:center; box-sizing:border-box; }
        .rond span { width:10px; height:10px; border-radius:50%; }
        .pnom { text-align:center; overflow-wrap:anywhere; }
        .modes { display:flex; flex-wrap:wrap; gap:6px; }
        .mode { padding:8px 14px; border-radius:18px; border:1px solid var(--divider-color); background:none; cursor:pointer; color:var(--primary-text-color); font:inherit; }
        .mode.choisi { background:var(--primary-color); color:var(--text-primary-color); border-color:var(--primary-color); }
        .accent { display:flex; flex-wrap:wrap; align-items:center; gap:10px; }
        .accent input { width:44px; height:32px; border:none; background:none; padding:0; }
        .lien { border:none; background:none; color:var(--primary-color); cursor:pointer; font:inherit; padding:0; }
        .apercus { display:flex; flex-wrap:wrap; gap:10px; margin-top:14px; }
        .apercu { flex:1 1 180px; border-radius:12px; padding:10px; border:1px solid var(--divider-color); }
        .ap-titre { font-size:.85em; opacity:.8; margin-bottom:6px; }
        .ap-carte { border:1px solid; border-radius:12px; padding:10px; display:flex; flex-direction:column; gap:4px; }
        .ap-bouton { align-self:flex-start; margin-top:6px; padding:5px 12px; border-radius:14px; font-weight:600; }
        .erreur { color:var(--error-color, #c62828); margin-top:10px; }
        .fait { color:var(--success-color, #2e7d32); margin-top:10px; }
        .vide { color:var(--secondary-text-color); }
      </style>
      <ha-card>
        <h2>${echA(tr("titre"))}</h2>
        <p class="aide">${echA(tr("aide"))}</p>
        <details><summary>${echA(tr("en_savoir_plus"))}</summary>
          ${echA(tr("details"))}
        </details>
        ${corps}
      </ha-card>`;
    const r = this.shadowRoot;
    r.querySelectorAll("[data-palette]").forEach((b) => b.addEventListener("click", () => this._appliquer({ palette: b.dataset.palette })));
    r.querySelectorAll("[data-mode]").forEach((c) => c.addEventListener("vision-choix", (ev) => {
      if (!this._occupe) this._appliquer({ mode: ev.detail.valeur });
    }));
    const acc = r.querySelector("[data-accent]");
    if (acc) acc.addEventListener("change", () => this._appliquer({ accent: acc.value }));
    const sans = r.querySelector("[data-sans-accent]");
    if (sans) sans.addEventListener("click", () => this._appliquer({ accent: "" }));
  }
}

if (!customElements.get("vision-apparence-card")) customElements.define("vision-apparence-card", VisionApparenceCard);
window.customCards = window.customCards || [];
if (!window.customCards.some((c) => c.type === "vision-apparence-card"))
  window.customCards.push({ type: "vision-apparence-card", name: tr("carte_nom"), description: tr("carte_description") });
