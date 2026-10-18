/*
 * Vision — carte « Écran de veille » : les tableaux que la télé fait défiler.
 *
 * Une ligne par tableau, dans l'ordre du tour. Les tableaux intégrés
 * (horloge, météo, caméras…) s'activent, se règlent en durée et en public.
 * Les tableaux composés se créent ici : un titre, des cases (une entité
 * Home Assistant chacune, avec son rendu), un public. Tout s'enregistre
 * dans l'intégration ; la télé le reçoit dans la minute.
 */
// ---- i18n:debut — les textes de cette carte, dans les six langues de Vision (fr source, en repli) ----
const TEXTES = /*i18n-json*/{
  "fr": {
    "erreur_admin": "Réservé aux administrateurs de Home Assistant.",
    "erreur_loading": "Vision démarre : réessaie dans un instant.",
    "erreur_pc": "Cet appareil n'existe plus dans Vision.",
    "erreur_json": "La demande n'a pas pu être lue : réessaie.",
    "erreur_module": "Cette fonction est désactivée dans Vision.",
    "erreur_minutes": "Indique une durée.",
    "erreur_defaut": "Ce n'est pas passé : rien n'a changé, réessaie dans un instant.",
    "geste_en_cours": "En cours…",
    "geste_fait": "C'est fait.",
    "erreur_lecture": "Impossible de lire les tableaux.",
    "enregistre": "Enregistré : les écrans de veille suivent au prochain affichage.",
    "echec_enregistrement": "L'enregistrement n'est pas passé ; réessaie.",
    "nouveau_tableau": "Nouveau tableau",
    "duree_min_s": "{m} min {ss} s", "duree_s": "{s} s",
    "titre_court": "Écran de veille", "chargement": "Chargement…", "choix_ecrans": "Écrans", "choix_publics": "Publics",
    "cases_one": "{n} case", "cases_other": "{n} cases",
    "titre_tableau": "Titre du tableau", "entite_exemple": "entité (sensor.…)", "libelle_optionnel": "libellé (optionnel)",
    "retirer": "Retirer", "ajouter_case": "+ une case", "apercu": "Aperçu", "supprimer_tableau": "Supprimer ce tableau",
    "aucune_case": "Aucune case lisible.",
    "personnes_precises": "Personnes précises (vide = selon le public ci-dessus) :",
    "afficher_tableau": "Afficher ce tableau", "monter": "Monter", "descendre": "Descendre",
    "titre": "Écran de veille : les tableaux",
    "tour_complet": "Un tour complet dure {duree}.",
    "aide": "Coche pour afficher, règle la durée, choisis les écrans et les publics. Clique un nom pour le détail. Un tableau composé prend n'importe quelles entités.",
    "enregistrement": "Enregistrement…", "enregistrer": "Enregistrer", "ajouter_compose": "+ Tableau composé", "annuler": "Annuler",
    "carte_nom": "Vision : écran de veille",
    "carte_description": "Les tableaux que la télé fait défiler, à composer depuis les entités."
  },
  "en": {
    "erreur_admin": "Only Home Assistant administrators can do this.",
    "erreur_loading": "Vision is starting: try again in a moment.",
    "erreur_pc": "This device no longer exists in Vision.",
    "erreur_json": "The request could not be read: try again.",
    "erreur_module": "This feature is turned off in Vision.",
    "erreur_minutes": "Enter a duration.",
    "erreur_defaut": "That didn't work: nothing changed, try again in a moment.",
    "geste_en_cours": "Working…",
    "geste_fait": "Done.",
    "erreur_lecture": "Couldn't read the boards.",
    "enregistre": "Saved: screensavers will follow the next time they show.",
    "echec_enregistrement": "Saving didn't work; try again.",
    "nouveau_tableau": "New board",
    "duree_min_s": "{m} min {ss} s", "duree_s": "{s} s",
    "titre_court": "Screensaver", "chargement": "Loading…", "choix_ecrans": "Screens", "choix_publics": "Audiences",
    "cases_one": "{n} tile", "cases_other": "{n} tiles",
    "titre_tableau": "Board title", "entite_exemple": "entity (sensor.…)", "libelle_optionnel": "label (optional)",
    "retirer": "Remove", "ajouter_case": "+ a tile", "apercu": "Preview", "supprimer_tableau": "Delete this board",
    "aucune_case": "No readable tile.",
    "personnes_precises": "Specific people (empty = based on the audience above):",
    "afficher_tableau": "Show this board", "monter": "Move up", "descendre": "Move down",
    "titre": "Screensaver: the boards",
    "tour_complet": "A full round takes {duree}.",
    "aide": "Tick to show, set the duration, choose the screens and audiences. Click a name for details. A custom board takes any entities.",
    "enregistrement": "Saving…", "enregistrer": "Save", "ajouter_compose": "+ Custom board", "annuler": "Cancel",
    "carte_nom": "Vision: screensaver",
    "carte_description": "The boards the TV cycles through, built from your entities."
  },
  "de": {
    "erreur_admin": "Nur für Home Assistant-Administratoren.",
    "erreur_loading": "Vision startet gerade: versuch es gleich noch einmal.",
    "erreur_pc": "Dieses Gerät gibt es in Vision nicht mehr.",
    "erreur_json": "Die Anfrage konnte nicht gelesen werden: versuch es noch einmal.",
    "erreur_module": "Diese Funktion ist in Vision ausgeschaltet.",
    "erreur_minutes": "Gib eine Dauer an.",
    "erreur_defaut": "Das hat nicht geklappt: nichts wurde geändert, versuch es gleich noch einmal.",
    "geste_en_cours": "Wird ausgeführt…",
    "geste_fait": "Erledigt.",
    "erreur_lecture": "Die Tafeln konnten nicht gelesen werden.",
    "enregistre": "Gespeichert: die Bildschirmschoner übernehmen es bei der nächsten Anzeige.",
    "echec_enregistrement": "Das Speichern hat nicht geklappt; versuch es noch einmal.",
    "nouveau_tableau": "Neue Tafel",
    "duree_min_s": "{m} Min. {ss} s", "duree_s": "{s} s",
    "titre_court": "Bildschirmschoner", "chargement": "Wird geladen…", "choix_ecrans": "Bildschirme", "choix_publics": "Zielgruppen",
    "cases_one": "{n} Feld", "cases_other": "{n} Felder",
    "titre_tableau": "Titel der Tafel", "entite_exemple": "Entität (sensor.…)", "libelle_optionnel": "Bezeichnung (optional)",
    "retirer": "Entfernen", "ajouter_case": "+ ein Feld", "apercu": "Vorschau", "supprimer_tableau": "Diese Tafel löschen",
    "aucune_case": "Kein lesbares Feld.",
    "personnes_precises": "Bestimmte Personen (leer = nach Zielgruppe oben):",
    "afficher_tableau": "Diese Tafel anzeigen", "monter": "Nach oben", "descendre": "Nach unten",
    "titre": "Bildschirmschoner: die Tafeln",
    "tour_complet": "Eine ganze Runde dauert {duree}.",
    "aide": "Haken setzen zum Anzeigen, Dauer einstellen, Bildschirme und Zielgruppen wählen. Klick auf einen Namen für Details. Eine eigene Tafel nimmt beliebige Entitäten.",
    "enregistrement": "Wird gespeichert…", "enregistrer": "Speichern", "ajouter_compose": "+ Eigene Tafel", "annuler": "Abbrechen",
    "carte_nom": "Vision: Bildschirmschoner",
    "carte_description": "Die Tafeln, die der Fernseher nacheinander zeigt, aus Entitäten zusammengestellt."
  },
  "es": {
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
    "enregistre": "Guardado: los salvapantallas lo aplicarán la próxima vez que se muestren.",
    "echec_enregistrement": "No se ha podido guardar; inténtalo de nuevo.",
    "nouveau_tableau": "Nuevo panel",
    "duree_min_s": "{m} min {ss} s", "duree_s": "{s} s",
    "titre_court": "Salvapantallas", "chargement": "Cargando…", "choix_ecrans": "Pantallas", "choix_publics": "Públicos",
    "cases_one": "{n} casilla", "cases_other": "{n} casillas",
    "titre_tableau": "Título del panel", "entite_exemple": "entidad (sensor.…)", "libelle_optionnel": "etiqueta (opcional)",
    "retirer": "Quitar", "ajouter_case": "+ una casilla", "apercu": "Vista previa", "supprimer_tableau": "Eliminar este panel",
    "aucune_case": "Ninguna casilla legible.",
    "personnes_precises": "Personas concretas (vacío = según el público de arriba):",
    "afficher_tableau": "Mostrar este panel", "monter": "Subir", "descendre": "Bajar",
    "titre": "Salvapantallas: los paneles",
    "tour_complet": "Una vuelta completa dura {duree}.",
    "aide": "Marca para mostrar, ajusta la duración, elige las pantallas y los públicos. Haz clic en un nombre para ver el detalle. Un panel personalizado admite cualquier entidad.",
    "enregistrement": "Guardando…", "enregistrer": "Guardar", "ajouter_compose": "+ Panel personalizado", "annuler": "Cancelar",
    "carte_nom": "Vision: salvapantallas",
    "carte_description": "Los paneles que la tele va pasando, creados a partir de las entidades."
  },
  "it": {
    "erreur_admin": "Riservato agli amministratori di Home Assistant.",
    "erreur_loading": "Vision si sta avviando: riprova tra un momento.",
    "erreur_pc": "Questo dispositivo non esiste più in Vision.",
    "erreur_json": "Non è stato possibile leggere la richiesta: riprova.",
    "erreur_module": "Questa funzione è disattivata in Vision.",
    "erreur_minutes": "Indica una durata.",
    "erreur_defaut": "Non è andata: non è cambiato nulla, riprova tra un momento.",
    "geste_en_cours": "In corso…",
    "geste_fait": "Fatto.",
    "erreur_lecture": "Impossibile leggere i pannelli.",
    "enregistre": "Salvato: i salvaschermi si aggiorneranno alla prossima visualizzazione.",
    "echec_enregistrement": "Il salvataggio non è andato; riprova.",
    "nouveau_tableau": "Nuovo pannello",
    "duree_min_s": "{m} min {ss} s", "duree_s": "{s} s",
    "titre_court": "Salvaschermo", "chargement": "Caricamento…", "choix_ecrans": "Schermi", "choix_publics": "Pubblici",
    "cases_one": "{n} casella", "cases_other": "{n} caselle",
    "titre_tableau": "Titolo del pannello", "entite_exemple": "entità (sensor.…)", "libelle_optionnel": "etichetta (facoltativa)",
    "retirer": "Rimuovi", "ajouter_case": "+ una casella", "apercu": "Anteprima", "supprimer_tableau": "Elimina questo pannello",
    "aucune_case": "Nessuna casella leggibile.",
    "personnes_precises": "Persone specifiche (vuoto = in base al pubblico qui sopra):",
    "afficher_tableau": "Mostra questo pannello", "monter": "Sposta su", "descendre": "Sposta giù",
    "titre": "Salvaschermo: i pannelli",
    "tour_complet": "Un giro completo dura {duree}.",
    "aide": "Spunta per mostrare, regola la durata, scegli gli schermi e i pubblici. Clicca un nome per i dettagli. Un pannello personalizzato accetta qualsiasi entità.",
    "enregistrement": "Salvataggio…", "enregistrer": "Salva", "ajouter_compose": "+ Pannello personalizzato", "annuler": "Annulla",
    "carte_nom": "Vision: salvaschermo",
    "carte_description": "I pannelli che la TV fa scorrere, da comporre a partire dalle entità."
  },
  "nl": {
    "erreur_admin": "Alleen voor beheerders van Home Assistant.",
    "erreur_loading": "Vision start op: probeer het zo opnieuw.",
    "erreur_pc": "Dit apparaat bestaat niet meer in Vision.",
    "erreur_json": "Het verzoek kon niet worden gelezen: probeer het opnieuw.",
    "erreur_module": "Deze functie staat uit in Vision.",
    "erreur_minutes": "Geef een duur op.",
    "erreur_defaut": "Dat is niet gelukt: er is niets veranderd, probeer het zo opnieuw.",
    "geste_en_cours": "Bezig…",
    "geste_fait": "Klaar.",
    "erreur_lecture": "De borden konden niet worden gelezen.",
    "enregistre": "Opgeslagen: de schermbeveiligingen volgen bij de volgende weergave.",
    "echec_enregistrement": "Opslaan is niet gelukt; probeer het opnieuw.",
    "nouveau_tableau": "Nieuw bord",
    "duree_min_s": "{m} min {ss} s", "duree_s": "{s} s",
    "titre_court": "Schermbeveiliging", "chargement": "Laden…", "choix_ecrans": "Schermen", "choix_publics": "Doelgroepen",
    "cases_one": "{n} vak", "cases_other": "{n} vakken",
    "titre_tableau": "Titel van het bord", "entite_exemple": "entiteit (sensor.…)", "libelle_optionnel": "label (optioneel)",
    "retirer": "Verwijderen", "ajouter_case": "+ een vak", "apercu": "Voorbeeld", "supprimer_tableau": "Dit bord verwijderen",
    "aucune_case": "Geen leesbaar vak.",
    "personnes_precises": "Specifieke personen (leeg = volgens het publiek hierboven):",
    "afficher_tableau": "Dit bord tonen", "monter": "Omhoog", "descendre": "Omlaag",
    "titre": "Schermbeveiliging: de borden",
    "tour_complet": "Een volledige ronde duurt {duree}.",
    "aide": "Vink aan om te tonen, stel de duur in, kies de schermen en het publiek. Klik op een naam voor details. Een eigen bord neemt willekeurige entiteiten.",
    "enregistrement": "Opslaan…", "enregistrer": "Opslaan", "ajouter_compose": "+ Eigen bord", "annuler": "Annuleren",
    "carte_nom": "Vision: schermbeveiliging",
    "carte_description": "De borden die de tv na elkaar toont, samengesteld uit entiteiten."
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
const API = "pc_parental/veille/tableaux";

const esc = (t) =>
  String(t ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

const ICONES = {
  horloge: "mdi:clock-outline", vigilance: "mdi:alert-outline", meteo: "mdi:weather-partly-cloudy", courbe: "mdi:chart-line",
  maison: "mdi:home-heart", tuiles: "mdi:view-grid-outline", cameras: "mdi:cctv", esports: "mdi:controller", courses: "mdi:flag-checkered",
  avenir: "mdi:calendar-clock", ecole: "mdi:school-outline", agenda: "mdi:calendar", chauffage: "mdi:fire", batteries: "mdi:battery-70",
  photos: "mdi:image-multiple-outline", entites: "mdi:shape-plus",
};

const nouvelId = () => "t" + Math.random().toString(36).slice(2, 9);

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

class VisionVeilleCard extends HTMLElement {
  constructor() {
    super();
    this.attachShadow({ mode: "open" });
    this._geste = retourGeste(this);
    this._donnees = null;
    this._erreur = "";
    this._occupe = false;
    this._ouverts = new Set();
    this._modifie = false;
    this._apercus = {};
  }

  setConfig(config) { this._config = Object.assign({}, config || {}); }
  getCardSize() { return 12; }
  getGridOptions() { return { columns: "full" }; }

  set hass(hass) {
    const premier = !this._hass;
    this._hass = hass;
    const langue = suivreLangue(hass);
    if (premier) { this._langue = langue; this._charger(); }
    else if (langue !== this._langue) { this._langue = langue; this._rendre(); }
  }

  async _charger() {
    if (!this._hass || this._modifie) return;
    try {
      this._donnees = await this._hass.callApi("GET", API);
      this._erreur = "";
    } catch (e) {
      this._erreur = (e && (e.message || e.body?.message)) || tr("erreur_lecture");
    }
    this._rendre();
  }

  async _enregistrer() {
    if (!this._donnees || this._occupe) return;
    this._geste.debut();
    this._occupe = true; this._rendre();
    try {
      this._donnees = await this._hass.callApi("POST", API, { tableaux: this._donnees.tableaux });
      this._modifie = false; this._erreur = "";
      this._geste.ok(tr("enregistre"));
    } catch (e) {
      this._erreur = "";
      this._geste.echec(e, tr("echec_enregistrement"));
    }
    this._occupe = false; this._rendre();
  }

  async _apercu(id) {
    try {
      const r = await this._hass.callApi("GET", `${API}?apercu=${encodeURIComponent(id)}`);
      this._apercus[id] = r.apercu || [];
    } catch (_) { this._apercus[id] = []; }
    this._rendre();
  }

  _toucher() { this._modifie = true; this._rendre(); }

  _cle(t) { return t.code === "entites" ? "entites:" + t.id : t.code; }

  _deplacer(i, delta) {
    const l = this._donnees.tableaux; const j = i + delta;
    if (j < 0 || j >= l.length) return;
    [l[i], l[j]] = [l[j], l[i]];
    this._toucher();
  }

  _ajouter() {
    const t = { code: "entites", id: nouvelId(), titre: tr("nouveau_tableau"), actif: true, duree: 22, ecrans: ["tele", "telephone"], publics: ["affichage", "parent", "enfant"], personnes: [], cases: [{ entite: "", libelle: "", rendu: "auto" }] };
    this._donnees.tableaux.push(t);
    this._ouverts.add(this._cle(t));
    this._toucher();
  }

  _entitesTriees() {
    const s = this._hass ? this._hass.states : {};
    return Object.keys(s).filter((e) => !e.startsWith("update.") && !e.startsWith("automation.") && !e.startsWith("script.") && !e.startsWith("scene.")).sort();
  }

  _tour(liste) {
    const total = liste.filter((t) => t.actif).reduce((a, t) => a + (t.duree || 0), 0);
    const m = Math.floor(total / 60), s = total % 60;
    return m ? tr("duree_min_s", { m, ss: String(s).padStart(2, "0") }) : tr("duree_s", { s });
  }

  // 15-10 (règle UX de l'auteur) : un groupe de choix multiple → <vision-choix> (étiquettes si 3 au plus tiennent, sinon liste).
  _groupe(i, champ, titre, options, valeurs) {
    if (!options.length) return "";
    return `<vision-choix class="choix" multiple data-i="${i}" data-groupe="${champ}" titre="${esc(titre)}" options="${esc(JSON.stringify(options))}" valeurs="${esc(JSON.stringify(valeurs))}"></vision-choix>`;
  }

  _rendre() {
    const d = this._donnees;
    const style = `
      :host { display: block; }
      ha-card { padding: 16px; }
      .entete { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 8px; }
      .entete h2 { margin: 0; font-size: 1.15em; font-weight: 500; flex: 1; }
      .sous { color: var(--secondary-text-color); font-size: 0.9em; }
      .ligne { display: grid; grid-template-columns: 28px 36px minmax(0, 1fr) minmax(0, 1.4fr) auto auto; gap: 8px; align-items: center; padding: 8px 6px; border-top: 1px solid var(--divider-color); }
      .ligne.inactif .nom { opacity: 0.5; }
      .nom { font-weight: 500; }
      .nom small { display: block; font-weight: 400; color: var(--secondary-text-color); }
      .pub { display: flex; gap: 8px; min-width: 0; }
      .choix { flex: 1 1 0; min-width: 0; }
      .duree { display: inline-flex; align-items: center; gap: 2px; }
      .duree button, .fleches button, .btn { background: none; border: 1px solid var(--divider-color); color: var(--primary-text-color); border-radius: 8px; width: 28px; height: 28px; cursor: pointer; font-size: 1em; }
      .duree span { min-width: 42px; text-align: center; font-variant-numeric: tabular-nums; }
      .fleches { display: inline-flex; gap: 2px; }
      .detail { grid-column: 1 / -1; padding: 6px 0 10px 64px; display: flex; flex-direction: column; gap: 8px; }
      .case { display: grid; grid-template-columns: 1fr 160px 120px 28px; gap: 8px; align-items: center; }
      input, select { background: var(--card-background-color); color: var(--primary-text-color); border: 1px solid var(--divider-color); border-radius: 8px; padding: 6px 8px; font: inherit; width: 100%; box-sizing: border-box; }
      .titre input { font-weight: 500; }
      .actions { display: flex; gap: 8px; align-items: center; margin-top: 12px; flex-wrap: wrap; }
      .primaire { background: var(--primary-color); color: var(--text-primary-color, #fff); border: none; border-radius: 10px; padding: 8px 16px; cursor: pointer; font: inherit; }
      .primaire[disabled] { opacity: 0.5; cursor: default; }
      .secondaire { background: none; border: 1px solid var(--divider-color); color: var(--primary-text-color); border-radius: 10px; padding: 8px 14px; cursor: pointer; font: inherit; }
      .erreur { color: var(--error-color); margin-top: 8px; }
      .apercu { display: flex; gap: 8px; flex-wrap: wrap; }
      .apercu .c { border: 1px solid var(--divider-color); border-radius: 10px; padding: 6px 10px; min-width: 110px; }
      .apercu .c b { display: block; font-size: 1.2em; }
      .apercu .c small { color: var(--secondary-text-color); }
      ha-icon { --mdc-icon-size: 22px; color: var(--secondary-text-color); }
      .supp { color: var(--error-color); }
      @media (max-width: 700px) { .ligne { grid-template-columns: 28px 30px minmax(0, 1fr) auto; } .pub { grid-column: 3 / -1; } .duree { grid-column: 3; justify-self: start; } .fleches { grid-column: 4; } .detail { padding-left: 0; } .case { grid-template-columns: 1fr 1fr; } }
    `;
    if (!d) {
      this.shadowRoot.innerHTML = `<style>${style}</style><ha-card><div class="entete"><h2>${esc(tr("titre_court"))}</h2></div><div class="sous">${this._erreur ? esc(this._erreur) : esc(tr("chargement"))}</div></ha-card>`;
      return;
    }
    const noms = {}; d.integres.forEach((i) => { noms[i.code] = i; });
    const lignes = d.tableaux.map((t, i) => {
      const cle = this._cle(t);
      const ouvert = this._ouverts.has(cle);
      const integre = t.code !== "entites";
      const nom = integre ? noms[t.code]?.nom || t.code : t.titre;
      const expl = integre ? noms[t.code]?.explication || "" : trn("cases", t.cases.length);
      const puces = this._groupe(i, "ecrans", tr("choix_ecrans"), d.ecrans.map((e) => ({ cle: e.code, libelle: e.nom })), t.ecrans) +
        this._groupe(i, "publics", tr("choix_publics"), d.publics.map((p) => ({ cle: p.code, libelle: p.nom })), t.publics);
      let detail = "";
      if (ouvert) {
        const personnes = this._groupe(i, "personnes", tr("personnes_precises"), d.personnes.map((p) => ({ cle: p.id, libelle: p.nom })), t.personnes);
        let cases = "";
        if (!integre) {
          const options = this._entitesTriees();
          cases = `<div class="titre"><input data-i="${i}" data-champ="titre" value="${esc(t.titre)}" placeholder="${esc(tr("titre_tableau"))}"></div>
            <datalist id="ents-${i}">${options.map((e) => `<option value="${esc(e)}">${esc(this._hass.states[e]?.attributes?.friendly_name || "")}</option>`).join("")}</datalist>` +
            t.cases.map((c, k) => `<div class="case">
              <input list="ents-${i}" data-i="${i}" data-k="${k}" data-champ="entite" value="${esc(c.entite)}" placeholder="${esc(tr("entite_exemple"))}">
              <input data-i="${i}" data-k="${k}" data-champ="libelle" value="${esc(c.libelle)}" placeholder="${esc(tr("libelle_optionnel"))}">
              <select data-i="${i}" data-k="${k}" data-champ="rendu">${d.rendus.map((r) => `<option value="${r.code}" ${c.rendu === r.code ? "selected" : ""}>${esc(r.nom)}</option>`).join("")}</select>
              <button class="btn supp" data-i="${i}" data-k="${k}" data-action="supprimer-case" title="${esc(tr("retirer"))}">×</button>
            </div>`).join("") +
            `<div class="actions"><button class="secondaire" data-i="${i}" data-action="ajouter-case" ${t.cases.length >= 8 ? "disabled" : ""}>${esc(tr("ajouter_case"))}</button>
             <button class="secondaire" data-i="${i}" data-action="apercu">${esc(tr("apercu"))}</button>
             <button class="secondaire supp" data-i="${i}" data-action="supprimer">${esc(tr("supprimer_tableau"))}</button></div>` +
            (this._apercus[t.id] ? `<div class="apercu">${this._apercus[t.id].map((c) => `<div class="c"><small>${esc(c.nom)}</small><b>${c.rendu === "etat" || c.rendu === "texte" || c.valeur == null ? esc(c.texte) : esc(c.valeur) + " " + esc(c.unite)}</b><small>${esc(c.rendu)}</small></div>`).join("") || `<small>${esc(tr("aucune_case"))}</small>`}</div>` : "");
        }
        detail = `<div class="detail">
          ${cases}
          <div class="sous">${esc(tr("personnes_precises"))}</div>
          ${personnes}
        </div>`;
      }
      return `<div class="ligne ${t.actif ? "" : "inactif"}">
        <input type="checkbox" data-i="${i}" data-champ="actif" ${t.actif ? "checked" : ""} title="${esc(tr("afficher_tableau"))}">
        <ha-icon icon="${ICONES[t.code] || "mdi:television"}"></ha-icon>
        <div class="nom" data-i="${i}" data-action="ouvrir" style="cursor:pointer">${esc(nom)}<small>${esc(expl)}</small></div>
        <div class="pub">${puces}</div>
        <div class="duree"><button data-i="${i}" data-action="moins">−</button><span>${esc(tr("duree_s", { s: t.duree }))}</span><button data-i="${i}" data-action="plus">+</button></div>
        <div class="fleches"><button data-i="${i}" data-action="haut" title="${esc(tr("monter"))}">↑</button><button data-i="${i}" data-action="bas" title="${esc(tr("descendre"))}">↓</button></div>
        ${detail}
      </div>`;
    }).join("");

    this.shadowRoot.innerHTML = `<style>${style}</style><ha-card>
      <div class="entete"><h2>${esc(tr("titre"))}</h2><span class="sous">${esc(tr("tour_complet", { duree: this._tour(d.tableaux) }))}</span></div>
      <div class="sous">${esc(tr("aide"))}</div>
      ${lignes}
      <div class="actions">
        <button class="primaire" data-action="enregistrer" ${this._modifie && !this._occupe ? "" : "disabled"}>${esc(this._occupe ? tr("enregistrement") : tr("enregistrer"))}</button>
        <button class="secondaire" data-action="ajouter">${esc(tr("ajouter_compose"))}</button>
        <button class="secondaire" data-action="annuler" ${this._modifie ? "" : "disabled"}>${esc(tr("annuler"))}</button>
      </div>
      ${this._erreur ? `<div class="erreur">${esc(this._erreur)}</div>` : ""}
    </ha-card>`;
    this._brancher();
  }

  _brancher() {
    const root = this.shadowRoot;
    const l = () => this._donnees.tableaux;
    root.querySelectorAll("[data-action]").forEach((el) => {
      el.addEventListener("click", (ev) => {
        const a = el.dataset.action; const i = Number(el.dataset.i); const k = Number(el.dataset.k);
        if (a === "enregistrer") return this._enregistrer();
        if (a === "annuler") { this._modifie = false; return this._charger(); }
        if (a === "ajouter") return this._ajouter();
        if (a === "ouvrir") { const c = this._cle(l()[i]); this._ouverts.has(c) ? this._ouverts.delete(c) : this._ouverts.add(c); return this._rendre(); }
        if (a === "moins") { l()[i].duree = Math.max(this._donnees.duree_min, l()[i].duree - 5); return this._toucher(); }
        if (a === "plus") { l()[i].duree = Math.min(this._donnees.duree_max, l()[i].duree + 5); return this._toucher(); }
        if (a === "haut") return this._deplacer(i, -1);
        if (a === "bas") return this._deplacer(i, 1);
        if (a === "ajouter-case") { l()[i].cases.push({ entite: "", libelle: "", rendu: "auto" }); return this._toucher(); }
        if (a === "supprimer-case") { l()[i].cases.splice(k, 1); return this._toucher(); }
        if (a === "supprimer") { l().splice(i, 1); return this._toucher(); }
        if (a === "apercu") return this._apercu(l()[i].id);
      });
    });
    // Écrans, publics, personnes : <vision-choix> rend la nouvelle liste ; on retrouve l'option touchée et on garde la
    // même règle (au moins un écran et un public ; les personnes peuvent rester vides).
    root.querySelectorAll("vision-choix[data-groupe]").forEach((el) => {
      el.addEventListener("vision-choix", (ev) => {
        const t = l()[Number(el.dataset.i)];
        const champ = el.dataset.groupe;
        const apres = ev.detail.valeurs;
        const cles = el.options.map((o) => String(o.cle));
        const v = apres.find((x) => !t[champ].includes(x)) ?? t[champ].find((x) => cles.includes(x) && !apres.includes(x));
        if (v === undefined) return;
        const idx = t[champ].indexOf(v);
        if (idx >= 0) { if (champ === "personnes" || t[champ].length > 1) t[champ].splice(idx, 1); } else t[champ].push(v);
        this._toucher();
      });
    });
    root.querySelectorAll("input[data-champ], select[data-champ]").forEach((el) => {
      const ev = el.type === "checkbox" || el.tagName === "SELECT" ? "change" : "input";
      el.addEventListener(ev, () => {
        const t = l()[Number(el.dataset.i)];
        if (el.dataset.champ === "actif") t.actif = el.checked;
        else if (el.dataset.k !== undefined) t.cases[Number(el.dataset.k)][el.dataset.champ] = el.value;
        else t[el.dataset.champ] = el.value;
        this._modifie = true;
        // Pas de re-rendu pendant la frappe : seul le bouton Enregistrer change.
        const b = root.querySelector('[data-action="enregistrer"]'); if (b) b.disabled = false;
        if (el.type === "checkbox") this._rendre();
      });
    });
  }
}

const _dejaVisionVeilleCard = !!customElements.get("vision-veille-card");
if (!_dejaVisionVeilleCard) customElements.define("vision-veille-card", VisionVeilleCard);
window.customCards = window.customCards || [];
if (!_dejaVisionVeilleCard) window.customCards.push({ type: "vision-veille-card", name: tr("carte_nom"), description: tr("carte_description") });
