/*
 * Vision — carte « Messages » (2.0.0, notifications v2, décisions du 2026-10-10).
 *
 * Écrire : pour qui (personnes, appareils), le type, le message, jusqu'à 3 réponses proposées,
 * « Important » (mis en avant, ne bloque pas), « Mettre en pause la télé » (décoché par défaut),
 * « Lire à voix haute ». Suivi en direct : reçu, lu, répondu (la réponse s'affiche).
 * Types de messages : nom, son, priorité, bloquant ou non ; les types système se modifient.
 * Règle : un clic met l'affichage à jour tout de suite ; un échec s'explique en français.
 */
// ---- i18n:debut — les textes de cette carte, dans les six langues de Vision (fr source, en repli) ----
const TEXTES = /*i18n-json*/{
  "fr": {
    "erreur_admin": "Réservé aux administrateurs de Home Assistant.",
    "erreur_connexion": "Home Assistant ne répond pas : rien n'a changé, réessaie.",
    "erreur_defaut": "Ce n'est pas passé : rien n'a changé, réessaie dans un instant.",
    "bloquant_non": "Non bloquant", "bloquant_lu": "Bloque jusqu'à « J'ai lu »",
    "bloquant_parent": "Bloque jusqu'à un {decideur}",
    "geste_en_cours": "En cours…", "geste_fait": "C'est fait.",
    "pas_de_reponse": "Pas de réponse : réessaie dans un instant.",
    "chargement": "Chargement…",
    "important": "important", "normal": "normal", "systeme": "système",
    "reponse_citee": "« {reponse} »",
    "modifier": "Modifier", "nom": "Nom", "son": "Son", "priorite": "Priorité",
    "prio_normale": "Normale", "prio_importante": "Importante (mise en avant)", "bloquant": "Bloquant",
    "enregistrer": "Enregistrer", "annuler": "Annuler", "supprimer": "Supprimer",
    "titre": "Envoyer un message", "pour": "Pour", "type": "Type",
    "tous_ses_appareils": "{nom} · tous ses appareils",
    "ton_message": "Ton message", "message": "Message",
    "reponses_proposees": "Réponses proposées (3 au plus)",
    "ajouter": "+ Ajouter", "ajouter_reponse": "Ajouter une réponse",
    "case_important": "Important : mis en avant, ne bloque pas",
    "case_pause": "Mettre en pause la télé", "case_voix": "Lire à voix haute",
    "note_bloquant": "Ce type de message est bloquant : {bloquant}.",
    "note_son": "Le son est choisi par chaque personne sur son appareil (silencieux possible).",
    "envoyer": "Envoyer maintenant", "suivi": "Suivi, en direct",
    "aucun_envoi": "Aucun message envoyé pour l'instant.",
    "types_messages": "Types de messages", "nouveau_type": "+ Nouveau type",
    "trois_reponses": "Trois réponses au plus.",
    "carte_nom": "Vision — Messages",
    "carte_description": "Écrire un message, suivre les réponses, gérer les types."
  },
  "en": {
    "erreur_admin": "Only Home Assistant administrators can do this.",
    "erreur_connexion": "Home Assistant isn't responding: nothing changed, try again.",
    "erreur_defaut": "That didn't work: nothing changed, try again in a moment.",
    "bloquant_non": "Not blocking", "bloquant_lu": "Blocks until “I've read it”",
    "bloquant_parent": "Blocks until a {decideur} responds",
    "geste_en_cours": "Working…", "geste_fait": "Done.",
    "pas_de_reponse": "No response: try again in a moment.",
    "chargement": "Loading…",
    "important": "important", "normal": "normal", "systeme": "system",
    "reponse_citee": "“{reponse}”",
    "modifier": "Edit", "nom": "Name", "son": "Sound", "priorite": "Priority",
    "prio_normale": "Normal", "prio_importante": "Important (highlighted)", "bloquant": "Blocking",
    "enregistrer": "Save", "annuler": "Cancel", "supprimer": "Delete",
    "titre": "Send a message", "pour": "To", "type": "Type",
    "tous_ses_appareils": "{nom} · all their devices",
    "ton_message": "Your message", "message": "Message",
    "reponses_proposees": "Suggested replies (3 at most)",
    "ajouter": "+ Add", "ajouter_reponse": "Add a reply",
    "case_important": "Important: highlighted, doesn't block",
    "case_pause": "Pause the TV", "case_voix": "Read aloud",
    "note_bloquant": "This message type is blocking: {bloquant}.",
    "note_son": "Each person chooses the sound on their device (silent is possible).",
    "envoyer": "Send now", "suivi": "Live tracking",
    "aucun_envoi": "No message sent yet.",
    "types_messages": "Message types", "nouveau_type": "+ New type",
    "trois_reponses": "Three replies at most.",
    "carte_nom": "Vision — Messages",
    "carte_description": "Write a message, follow the replies, manage the types."
  },
  "de": {
    "erreur_admin": "Nur für Home Assistant-Administratoren.",
    "erreur_connexion": "Home Assistant antwortet nicht: nichts wurde geändert, versuch es noch einmal.",
    "erreur_defaut": "Das hat nicht geklappt: nichts wurde geändert, versuch es gleich noch einmal.",
    "bloquant_non": "Nicht blockierend", "bloquant_lu": "Blockiert bis „Gelesen“",
    "bloquant_parent": "Blockiert, bis ein {decideur} antwortet",
    "geste_en_cours": "Wird ausgeführt…", "geste_fait": "Erledigt.",
    "pas_de_reponse": "Keine Antwort: versuch es gleich noch einmal.",
    "chargement": "Wird geladen…",
    "important": "wichtig", "normal": "normal", "systeme": "System",
    "reponse_citee": "„{reponse}“",
    "modifier": "Bearbeiten", "nom": "Name", "son": "Ton", "priorite": "Priorität",
    "prio_normale": "Normal", "prio_importante": "Wichtig (hervorgehoben)", "bloquant": "Blockierend",
    "enregistrer": "Speichern", "annuler": "Abbrechen", "supprimer": "Löschen",
    "titre": "Eine Nachricht senden", "pour": "An", "type": "Art",
    "tous_ses_appareils": "{nom} · alle eigenen Geräte",
    "ton_message": "Deine Nachricht", "message": "Nachricht",
    "reponses_proposees": "Vorgeschlagene Antworten (höchstens 3)",
    "ajouter": "+ Hinzufügen", "ajouter_reponse": "Eine Antwort hinzufügen",
    "case_important": "Wichtig: hervorgehoben, blockiert nicht",
    "case_pause": "Fernseher pausieren", "case_voix": "Vorlesen",
    "note_bloquant": "Diese Nachrichtenart blockiert: {bloquant}.",
    "note_son": "Jede Person wählt den Ton auf ihrem Gerät (auch lautlos möglich).",
    "envoyer": "Jetzt senden", "suivi": "Live-Verfolgung",
    "aucun_envoi": "Noch keine Nachricht gesendet.",
    "types_messages": "Nachrichtenarten", "nouveau_type": "+ Neue Art",
    "trois_reponses": "Höchstens drei Antworten.",
    "carte_nom": "Vision — Nachrichten",
    "carte_description": "Eine Nachricht schreiben, die Antworten verfolgen, die Arten verwalten."
  },
  "es": {
    "erreur_admin": "Solo para administradores de Home Assistant.",
    "erreur_connexion": "Home Assistant no responde: no ha cambiado nada, inténtalo de nuevo.",
    "erreur_defaut": "No ha funcionado: no ha cambiado nada, inténtalo de nuevo en un momento.",
    "bloquant_non": "No bloqueante", "bloquant_lu": "Bloquea hasta «Lo he leído»",
    "bloquant_parent": "Bloquea hasta que responda un {decideur}",
    "geste_en_cours": "En curso…", "geste_fait": "Hecho.",
    "pas_de_reponse": "Sin respuesta: inténtalo de nuevo en un momento.",
    "chargement": "Cargando…",
    "important": "importante", "normal": "normal", "systeme": "sistema",
    "reponse_citee": "«{reponse}»",
    "modifier": "Modificar", "nom": "Nombre", "son": "Sonido", "priorite": "Prioridad",
    "prio_normale": "Normal", "prio_importante": "Importante (destacado)", "bloquant": "Bloqueante",
    "enregistrer": "Guardar", "annuler": "Cancelar", "supprimer": "Eliminar",
    "titre": "Enviar un mensaje", "pour": "Para", "type": "Tipo",
    "tous_ses_appareils": "{nom} · todos sus dispositivos",
    "ton_message": "Tu mensaje", "message": "Mensaje",
    "reponses_proposees": "Respuestas propuestas (3 como máximo)",
    "ajouter": "+ Añadir", "ajouter_reponse": "Añadir una respuesta",
    "case_important": "Importante: destacado, no bloquea",
    "case_pause": "Pausar la tele", "case_voix": "Leer en voz alta",
    "note_bloquant": "Este tipo de mensaje es bloqueante: {bloquant}.",
    "note_son": "Cada persona elige el sonido en su dispositivo (puede ser silencioso).",
    "envoyer": "Enviar ahora", "suivi": "Seguimiento en directo",
    "aucun_envoi": "Aún no se ha enviado ningún mensaje.",
    "types_messages": "Tipos de mensajes", "nouveau_type": "+ Nuevo tipo",
    "trois_reponses": "Tres respuestas como máximo.",
    "carte_nom": "Vision — Mensajes",
    "carte_description": "Escribir un mensaje, seguir las respuestas, gestionar los tipos."
  },
  "it": {
    "erreur_admin": "Riservato agli amministratori di Home Assistant.",
    "erreur_connexion": "Home Assistant non risponde: non è cambiato nulla, riprova.",
    "erreur_defaut": "Non è andata: non è cambiato nulla, riprova tra un momento.",
    "bloquant_non": "Non bloccante", "bloquant_lu": "Blocca fino a «Ho letto»",
    "bloquant_parent": "Blocca fino alla risposta di un {decideur}",
    "geste_en_cours": "In corso…", "geste_fait": "Fatto.",
    "pas_de_reponse": "Nessuna risposta: riprova tra un momento.",
    "chargement": "Caricamento…",
    "important": "importante", "normal": "normale", "systeme": "sistema",
    "reponse_citee": "«{reponse}»",
    "modifier": "Modificare", "nom": "Nome", "son": "Suono", "priorite": "Priorità",
    "prio_normale": "Normale", "prio_importante": "Importante (in evidenza)", "bloquant": "Bloccante",
    "enregistrer": "Salvare", "annuler": "Annullare", "supprimer": "Eliminare",
    "titre": "Inviare un messaggio", "pour": "Per", "type": "Tipo",
    "tous_ses_appareils": "{nom} · tutti i suoi dispositivi",
    "ton_message": "Il tuo messaggio", "message": "Messaggio",
    "reponses_proposees": "Risposte proposte (al massimo 3)",
    "ajouter": "+ Aggiungi", "ajouter_reponse": "Aggiungi una risposta",
    "case_important": "Importante: in evidenza, non blocca",
    "case_pause": "Mettere in pausa la TV", "case_voix": "Leggere ad alta voce",
    "note_bloquant": "Questo tipo di messaggio è bloccante: {bloquant}.",
    "note_son": "Il suono lo sceglie ogni persona sul proprio dispositivo (anche silenzioso).",
    "envoyer": "Inviare ora", "suivi": "Monitoraggio in diretta",
    "aucun_envoi": "Ancora nessun messaggio inviato.",
    "types_messages": "Tipi di messaggi", "nouveau_type": "+ Nuovo tipo",
    "trois_reponses": "Al massimo tre risposte.",
    "carte_nom": "Vision — Messaggi",
    "carte_description": "Scrivere un messaggio, seguire le risposte, gestire i tipi."
  },
  "nl": {
    "erreur_admin": "Alleen voor beheerders van Home Assistant.",
    "erreur_connexion": "Home Assistant reageert niet: er is niets veranderd, probeer het opnieuw.",
    "erreur_defaut": "Dat is niet gelukt: er is niets veranderd, probeer het zo opnieuw.",
    "bloquant_non": "Niet blokkerend", "bloquant_lu": "Blokkeert tot „Ik heb het gelezen”",
    "bloquant_parent": "Blokkeert tot een {decideur} reageert",
    "geste_en_cours": "Bezig…", "geste_fait": "Klaar.",
    "pas_de_reponse": "Geen antwoord: probeer het zo opnieuw.",
    "chargement": "Laden…",
    "important": "belangrijk", "normal": "normaal", "systeme": "systeem",
    "reponse_citee": "„{reponse}”",
    "modifier": "Bewerken", "nom": "Naam", "son": "Geluid", "priorite": "Prioriteit",
    "prio_normale": "Normaal", "prio_importante": "Belangrijk (uitgelicht)", "bloquant": "Blokkerend",
    "enregistrer": "Opslaan", "annuler": "Annuleren", "supprimer": "Verwijderen",
    "titre": "Een bericht sturen", "pour": "Aan", "type": "Soort",
    "tous_ses_appareils": "{nom} · al zijn of haar apparaten",
    "ton_message": "Je bericht", "message": "Bericht",
    "reponses_proposees": "Voorgestelde antwoorden (maximaal 3)",
    "ajouter": "+ Toevoegen", "ajouter_reponse": "Een antwoord toevoegen",
    "case_important": "Belangrijk: uitgelicht, blokkeert niet",
    "case_pause": "De tv pauzeren", "case_voix": "Voorlezen",
    "note_bloquant": "Deze berichtsoort blokkeert: {bloquant}.",
    "note_son": "Iedereen kiest het geluid op zijn eigen apparaat (stil kan ook).",
    "envoyer": "Nu versturen", "suivi": "Live volgen",
    "aucun_envoi": "Nog geen bericht verstuurd.",
    "types_messages": "Berichtsoorten", "nouveau_type": "+ Nieuwe soort",
    "trois_reponses": "Maximaal drie antwoorden.",
    "carte_nom": "Vision — Berichten",
    "carte_description": "Een bericht schrijven, de antwoorden volgen, de soorten beheren."
  }
}/*i18n-json:fin*/;
// Les catégories (étiquettes de la maison) : cette carte n'en affiche pas.
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
const API = "pc_parental/messages";
const esc = (t) =>
  String(t ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const messageErreur = (e) => {
  const b = e && e.body;
  if (b && b.retour) return String(b.retour);
  if (e && (e.status_code === 403 || (b && b.error === "admin"))) return tr("erreur_admin");
  if (e && e.error === "connection lost") return tr("erreur_connexion");  // i18n-ignorer : code d'erreur du websocket de Home Assistant (comparé, jamais affiché)
  return tr("erreur_defaut");
};
const BLOQUANTS = { non: "bloquant_non", lu: "bloquant_lu", parent: "bloquant_parent" };

class VisionMessagesCard extends HTMLElement {
  setConfig(config) { this._config = config || {}; }
  getCardSize() { return 8; }

  set hass(h) {
    const premier = !this._hass;
    this._hass = h;
    const langue = suivreLangue(h);
    if (premier) { this._langue = langue; this._init(); this._charger(); this._abonner(); }
    else if (langue !== this._langue) { this._langue = langue; this._rendre(); }
  }

  disconnectedCallback() { if (this._desab) { this._desab.then((f) => f && f()); this._desab = null; } }
  connectedCallback() { if (this._hass && !this._desab) { this._abonner(); this._charger(); } }

  _init() {
    this.attachShadow({ mode: "open" });
    this._choix = new Set();
    this._type = "message";
    this._texte = "";
    this._reponses = [];
    this._important = false;
    this._pause = false;
    this._voix = false;
    this._statut = "";
    this._ko = false;
    this._occupe = "";
    this._edition = null;
  }

  _abonner() {
    // Une réponse, un accusé : le suivi se met à jour tout de suite.
    if (!this._hass || !this._hass.connection) return;
    this._desab = this._hass.connection.subscribeEvents(() => this._charger(), "pc_parental_message").catch(() => null);
  }

  async _charger() {
    try {
      this._d = await this._hass.callApi("GET", API);
      this._erreur = "";
    } catch (e) { this._erreur = messageErreur(e); }
    this._rendre();
  }

  async _poster(corps, cle) {
    if (this._occupe) return false;
    this._occupe = cle; this._statut = tr("geste_en_cours"); this._ko = false; this._rendre();
    let ok = false;
    try {
      const r = await Promise.race([
        this._hass.callApi("POST", API, corps),
        new Promise((_, ko) => setTimeout(() => ko({ error: "delai" }), 10000)),
      ]);
      this._statut = r.retour || tr("geste_fait"); ok = true;
    } catch (e) { this._statut = e && e.error === "delai" ? tr("pas_de_reponse") : messageErreur(e); this._ko = true; }
    this._occupe = "";
    await this._charger();
    return ok;
  }

  async _envoyer() {
    const ok = await this._poster({
      action: "envoyer", destinataires: [...this._choix], type: this._type, texte: this._texte,
      reponses: this._reponses, important: this._important, pause_tele: this._pause, voix: this._voix,
    }, "envoyer");
    if (ok) { this._texte = ""; this._rendre(); }
  }

  _rendre() {
    if (!this.shadowRoot) return;
    const d = this._d;
    const r = this.shadowRoot;
    // On garde ce qui est en cours de saisie (le rendu reconstruit la carte).
    const actif = r.activeElement && r.activeElement.id;
    const pos = actif && r.activeElement.selectionStart;
    r.innerHTML = `<style>${this._style()}</style><ha-card>${d ? this._contenu(d) : `<div class="vide">${esc(this._erreur || tr("chargement"))}</div>`}</ha-card>`;
    this._brancher();
    if (actif) { const el = r.getElementById(actif); if (el) { el.focus(); try { el.setSelectionRange(pos, pos); } catch (_) {} } }
  }

  _puce(cle, texte, on, attr) {
    return `<button class="puce ${on ? "on" : ""}" ${attr}="${esc(cle)}" aria-pressed="${on}">${esc(texte)}</button>`;
  }

  _contenu(d) {
    const dest = d.destinataires || { personnes: [], appareils: [] };
    const t = (d.types || []).find((x) => x.id === this._type) || (d.types || [])[0] || {};
    const pret = this._choix.size > 0 && this._texte.trim() && !this._occupe;
    const envois = (d.envois || []).map((e) => `
      <div class="envoi"><div class="envoi-t"><b>${esc(e.texte)}</b>${e.important ? ` <span class="imp">${esc(tr("important"))}</span>` : ""}
        <span class="qui">${esc(e.de)} · ${esc(e.type)} · ${trHeure(new Date(e.ts * 1000))}</span></div>
        ${(e.appareils || []).map((a) => `<div class="ligne"><span>${esc(a.nom)}</span><span class="etat e-${esc(a.etat)}">${esc(a.etat_texte)}${a.reponse ? ` ${esc(tr("reponse_citee", { reponse: a.reponse }))}` : ""}</span></div>`).join("")}
      </div>`).join("");
    const types = (d.types || []).map((x) => `<div class="ligne"><span><b>${esc(x.nom)}</b> <span class="qui">${esc(x.son)} · ${esc(tr(x.priorite === "importante" ? "important" : "normal"))} · ${esc(BLOQUANTS[x.bloquant] ? tr(BLOQUANTS[x.bloquant]) : x.bloquant)}${x.systeme ? ` · ${esc(tr("systeme"))}` : ""}</span></span>
        <button class="lien" data-editer="${esc(x.id)}">${esc(tr("modifier"))}</button></div>`).join("");
    const ed = this._edition;
    const edition = ed ? `<div class="edition">
        <label>${esc(tr("nom"))} <input id="ed_nom" maxlength="30" value="${esc(ed.nom || "")}"></label>
        <label>${esc(tr("son"))} <select id="ed_son">${(d.sons || []).map((s) => `<option ${s === ed.son ? "selected" : ""}>${esc(s)}</option>`).join("")}</select></label>
        <label>${esc(tr("priorite"))} <select id="ed_prio"><option value="normale" ${ed.priorite !== "importante" ? "selected" : ""}>${esc(tr("prio_normale"))}</option><option value="importante" ${ed.priorite === "importante" ? "selected" : ""}>${esc(tr("prio_importante"))}</option></select></label>
        <label>${esc(tr("bloquant"))} <select id="ed_bloq">${Object.entries(BLOQUANTS).map(([k, v]) => `<option value="${k}" ${k === ed.bloquant ? "selected" : ""}>${esc(tr(v))}</option>`).join("")}</select></label>
        <div class="actions"><button class="primaire" id="ed_ok">${esc(tr(this._occupe === "type" ? "geste_en_cours" : "enregistrer"))}</button>
          <button class="lien" id="ed_annuler">${esc(tr("annuler"))}</button>
          ${ed.id && !ed.systeme ? `<button class="lien danger" id="ed_suppr">${esc(tr("supprimer"))}</button>` : ""}</div></div>` : "";
    return `
      <div class="titre">${esc(tr("titre"))}</div>
      <div class="sous">${esc(tr("pour"))}</div>
      <div class="puces">${dest.personnes.map((p) => this._puce(p.id, tr("tous_ses_appareils", { nom: p.nom }), this._choix.has(p.id), "data-dest")).join("")}
        ${dest.appareils.map((a) => this._puce(a.id, a.nom, this._choix.has(a.id), "data-dest")).join("")}</div>
      <div class="sous">${esc(tr("type"))}</div>
      <div class="puces">${(d.types || []).map((x) => this._puce(x.id, x.nom, x.id === this._type, "data-type")).join("")}</div>
      <textarea id="texte" maxlength="1000" placeholder="${esc(tr("ton_message"))}" aria-label="${esc(tr("message"))}">${esc(this._texte)}</textarea>
      <div class="sous">${esc(tr("reponses_proposees"))}</div>
      <div class="puces">${(d.reponses_proposees || []).map((x) => this._puce(x, x, this._reponses.includes(x), "data-rep")).join("")}
        ${this._reponses.filter((x) => !(d.reponses_proposees || []).includes(x)).map((x) => this._puce(x, x, true, "data-rep")).join("")}
        <input id="rep_libre" maxlength="30" placeholder="${esc(tr("ajouter"))}" aria-label="${esc(tr("ajouter_reponse"))}"></div>
      <label class="case"><input type="checkbox" id="important" ${this._important || t.priorite === "importante" ? "checked" : ""} ${t.priorite === "importante" ? "disabled" : ""}> ${esc(tr("case_important"))}</label>
      <label class="case"><input type="checkbox" id="pause" ${this._pause ? "checked" : ""}> ${esc(tr("case_pause"))}</label>
      <label class="case"><input type="checkbox" id="voix" ${this._voix ? "checked" : ""}> ${esc(tr("case_voix"))}</label>
      <div class="note">${t.bloquant && t.bloquant !== "non" ? esc(tr("note_bloquant", { bloquant: BLOQUANTS[t.bloquant] ? tr(BLOQUANTS[t.bloquant]) : "" })) : esc(tr("note_son"))}</div>
      <button class="primaire" id="envoyer" ${pret ? "" : "disabled"}>${esc(tr(this._occupe === "envoyer" ? "geste_en_cours" : "envoyer"))}</button>
      <div class="statut ${this._ko ? "ko" : ""}" role="status">${esc(this._statut)}</div>
      <div class="titre petit">${esc(tr("suivi"))}</div>
      ${envois || `<div class="vide">${esc(tr("aucun_envoi"))}</div>`}
      <details ${ed ? "open" : ""}><summary>${esc(tr("types_messages"))}</summary>${types}
        <button class="lien" id="nouveau_type">${esc(tr("nouveau_type"))}</button>${edition}</details>`;
  }

  _brancher() {
    const r = this.shadowRoot;
    const $ = (id) => r.getElementById(id);
    r.querySelectorAll("[data-dest]").forEach((b) => b.addEventListener("click", () => {
      const k = b.dataset.dest; this._choix.has(k) ? this._choix.delete(k) : this._choix.add(k); this._rendre();
    }));
    r.querySelectorAll("[data-type]").forEach((b) => b.addEventListener("click", () => { this._type = b.dataset.type; this._rendre(); }));
    r.querySelectorAll("[data-rep]").forEach((b) => b.addEventListener("click", () => {
      const k = b.dataset.rep;
      if (this._reponses.includes(k)) this._reponses = this._reponses.filter((x) => x !== k);
      else if (this._reponses.length < 3) this._reponses = [...this._reponses, k];
      else { this._statut = tr("trois_reponses"); this._ko = true; }
      this._rendre();
    }));
    const libre = $("rep_libre");
    if (libre) libre.addEventListener("keydown", (e) => {
      if (e.key !== "Enter") return;
      const v = libre.value.trim().slice(0, 30);
      if (v && !this._reponses.includes(v) && this._reponses.length < 3) this._reponses = [...this._reponses, v];
      this._rendre();
    });
    const t = $("texte");
    if (t) t.addEventListener("input", () => { this._texte = t.value; const b = $("envoyer"); if (b) b.disabled = !(this._choix.size && this._texte.trim() && !this._occupe); });
    for (const [id, cle] of [["important", "_important"], ["pause", "_pause"], ["voix", "_voix"]]) {
      const c = $(id); if (c) c.addEventListener("change", () => { this[cle] = c.checked; });
    }
    const env = $("envoyer"); if (env) env.addEventListener("click", () => this._envoyer());
    r.querySelectorAll("[data-editer]").forEach((b) => b.addEventListener("click", () => {
      this._edition = { ...((this._d.types || []).find((x) => x.id === b.dataset.editer) || {}) }; this._rendre();
    }));
    const nv = $("nouveau_type"); if (nv) nv.addEventListener("click", () => { this._edition = { nom: "", son: "Par défaut", priorite: "normale", bloquant: "non" }; this._rendre(); });  // i18n-ignorer : « Par défaut » est le nom d'un son, valeur envoyée à Home Assistant
    const ann = $("ed_annuler"); if (ann) ann.addEventListener("click", () => { this._edition = null; this._rendre(); });
    const ok = $("ed_ok");
    if (ok) ok.addEventListener("click", async () => {
      const e = { action: "type", id: this._edition.id || "", nom: $("ed_nom").value, son: $("ed_son").value, priorite: $("ed_prio").value, bloquant: $("ed_bloq").value };
      if (await this._poster(e, "type")) { this._edition = null; this._rendre(); }
    });
    const sup = $("ed_suppr");
    if (sup) sup.addEventListener("click", async () => { if (await this._poster({ action: "supprimer_type", id: this._edition.id }, "type")) { this._edition = null; this._rendre(); } });
  }

  _style() {
    return `
      ha-card { padding: 16px; }
      input[type="checkbox"] { width:20px; height:20px; margin:2px 4px 2px 0; flex:none; }
      .titre { font-size: 20px; font-weight: 600; margin-bottom: 8px; } .titre.petit { font-size: 16px; margin-top: 18px; }
      .sous { color: var(--secondary-text-color); font-size: 13px; margin: 10px 0 6px; }
      .puces { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; }
      .puce { border: 1px solid var(--divider-color); background: none; color: var(--primary-text-color); border-radius: 999px; padding: 8px 12px; min-height: 36px; font: inherit; cursor: pointer; }
      .puce.on { border-color: var(--primary-color); background: color-mix(in srgb, var(--primary-color) 18%, transparent); font-weight: 600; }
      textarea { width: 100%; box-sizing: border-box; min-height: 90px; margin-top: 10px; font: inherit; padding: 10px; border-radius: 10px; border: 1px solid var(--divider-color); background: var(--card-background-color); color: var(--primary-text-color); }
      input, select { font: inherit; padding: 8px 10px; border-radius: 10px; border: 1px solid var(--divider-color); background: var(--card-background-color); color: var(--primary-text-color); }
      #rep_libre { width: 140px; }
      .case { display: flex; gap: 8px; align-items: center; margin-top: 10px; }
      .note { color: var(--secondary-text-color); font-size: 13px; margin: 10px 0; }
      .primaire { background: var(--primary-color); color: var(--text-primary-color); border: 0; border-radius: 999px; padding: 10px 18px; font: inherit; font-weight: 600; cursor: pointer; min-height: 44px; }
      .primaire:disabled { opacity: .45; cursor: default; }
      .lien { background: none; border: 0; color: var(--primary-color); font: inherit; cursor: pointer; padding: 6px; } .danger { color: var(--error-color); }
      .statut { min-height: 1.3em; margin-top: 8px; } .statut.ko { color: var(--error-color); }
      .envoi { border: 1px solid var(--divider-color); border-radius: 12px; padding: 10px 12px; margin-top: 8px; }
      .envoi-t { margin-bottom: 6px; overflow-wrap: anywhere; } .qui { color: var(--secondary-text-color); font-size: 13px; display: block; }
      .imp { color: var(--warning-color); font-size: 12px; font-weight: 600; }
      .ligne { display: flex; justify-content: space-between; gap: 10px; padding: 4px 0; flex-wrap: wrap; }
      .etat { color: var(--secondary-text-color); overflow-wrap: anywhere; } .e-repondu { color: var(--success-color); font-weight: 600; } .e-lu { color: var(--primary-text-color); }
      details { margin-top: 18px; } summary { cursor: pointer; font-weight: 600; }
      .edition { display: grid; gap: 8px; margin-top: 8px; } .edition label { display: grid; gap: 4px; }
      .actions { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
      .vide { color: var(--secondary-text-color); padding: 8px 0; }`;
  }
}

if (!customElements.get("vision-messages-card")) customElements.define("vision-messages-card", VisionMessagesCard);
window.customCards = window.customCards || [];
window.customCards.push({ type: "vision-messages-card", name: tr("carte_nom"), description: tr("carte_description") });
