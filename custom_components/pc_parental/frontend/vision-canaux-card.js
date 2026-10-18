/*
 * Vision — carte « Mes notifications » (2.5.6, l'auteur, 15/10 : « c'est l'utilisateur qui choisit »).
 *
 * Pour chaque sujet (demandes, alertes de protection, résumé), les canaux de ce Décideur, en cases à cocher :
 * appli Vision, appli Compagnon de Home Assistant, Discord. Un administrateur choisit aussi pour les autres
 * Décideurs (liste déroulante). Chaque case s'enregistre tout de suite (API pc_parental/canaux, canaux.py).
 */
// ---- i18n:debut — les textes de cette carte, dans les six langues de Vision (fr source, en repli) ----
const TEXTES = /*i18n-json*/{
  "fr": {
    "carte_nom": "Vision — Mes notifications",
    "carte_description": "Comment chaque {decideur} reçoit les demandes, les alertes et le résumé.",
    "titre": "Mes notifications",
    "aide": "Coche un ou plusieurs canaux par sujet. Aucune case : rien ne part, tout reste dans Vision.",
    "pour": "Pour",
    "evt_demandes": "Demandes",
    "evt_demandes_aide": "Accès, temps en plus, déverrouillage, nouvel appareil",
    "evt_alertes": "Alertes de protection",
    "evt_alertes_aide": "Codes de déverrouillage faux répétés",
    "evt_resume": "Résumé de la semaine",
    "evt_resume_aide": "Le bilan du dimanche soir",
    "canal_vision": "Appli Vision",
    "canal_compagnon": "Appli Compagnon HA",
    "canal_discord": "Discord",
    "manque_vision": "Aucun appareil Vision à ce nom.",
    "manque_compagnon": "Aucun téléphone relié à Home Assistant.",
    "manque_discord": "Le salon Discord se règle dans Configurer.",
    "par_defaut": "Réglage par défaut : un seul canal, pour éviter les doublons.",
    "rien": "Rien ne part : tout reste dans Vision.",
    "fait": "Enregistré.",
    "aucun_decideur": "Ce compte n'est lié à aucun {decideur}.",
    "chargement": "Chargement…",
    "erreur_defaut": "Le réglage n'a pas pu être enregistré : réessaie.",
    "erreur_connexion": "Connexion perdue avec Home Assistant : réessaie dans un instant.",
    "erreur_admin": "Réservé aux administrateurs de Home Assistant."
  },
  "en": {
    "carte_nom": "Vision — My notifications",
    "carte_description": "How each {decideur} gets requests, alerts and the summary.",
    "titre": "My notifications",
    "aide": "Tick one or more channels per topic. No box ticked: nothing is sent, everything stays in Vision.",
    "pour": "For",
    "evt_demandes": "Requests",
    "evt_demandes_aide": "Access, extra time, unlock, new device",
    "evt_alertes": "Protection alerts",
    "evt_alertes_aide": "Repeated wrong unlock codes",
    "evt_resume": "Weekly summary",
    "evt_resume_aide": "The Sunday evening report",
    "canal_vision": "Vision app",
    "canal_compagnon": "HA Companion app",
    "canal_discord": "Discord",
    "manque_vision": "No Vision device under this name.",
    "manque_compagnon": "No phone linked to Home Assistant.",
    "manque_discord": "The Discord channel is set in Configure.",
    "par_defaut": "Default setting: a single channel, to avoid duplicates.",
    "rien": "Nothing is sent: everything stays in Vision.",
    "fait": "Saved.",
    "aucun_decideur": "This account isn't linked to any {decideur}.",
    "chargement": "Loading…",
    "erreur_defaut": "The setting couldn't be saved: try again.",
    "erreur_connexion": "Connection to Home Assistant lost: try again in a moment.",
    "erreur_admin": "Home Assistant administrators only."
  },
  "de": {
    "carte_nom": "Vision — Meine Benachrichtigungen",
    "carte_description": "Wie jeder {decideur} Anfragen, Warnungen und die Zusammenfassung erhält.",
    "titre": "Meine Benachrichtigungen",
    "aide": "Wähle pro Thema einen oder mehrere Kanäle. Kein Häkchen: Es wird nichts gesendet, alles bleibt in Vision.",
    "pour": "Für",
    "evt_demandes": "Anfragen",
    "evt_demandes_aide": "Zugriff, mehr Zeit, Entsperren, neues Gerät",
    "evt_alertes": "Schutzwarnungen",
    "evt_alertes_aide": "Wiederholt falsche Entsperrcodes",
    "evt_resume": "Wochenzusammenfassung",
    "evt_resume_aide": "Der Bericht am Sonntagabend",
    "canal_vision": "Vision-App",
    "canal_compagnon": "HA Companion-App",
    "canal_discord": "Discord",
    "manque_vision": "Kein Vision-Gerät unter diesem Namen.",
    "manque_compagnon": "Kein Telefon mit Home Assistant verbunden.",
    "manque_discord": "Der Discord-Kanal wird unter Konfigurieren eingestellt.",
    "par_defaut": "Standardeinstellung: ein einziger Kanal, um Doppelte zu vermeiden.",
    "rien": "Es wird nichts gesendet: Alles bleibt in Vision.",
    "fait": "Gespeichert.",
    "aucun_decideur": "Dieses Konto ist mit keinem {decideur} verknüpft.",
    "chargement": "Wird geladen…",
    "erreur_defaut": "Die Einstellung konnte nicht gespeichert werden: Versuche es erneut.",
    "erreur_connexion": "Verbindung zu Home Assistant verloren: Versuche es gleich noch einmal.",
    "erreur_admin": "Nur für Home Assistant-Administratoren."
  },
  "es": {
    "carte_nom": "Vision — Mis notificaciones",
    "carte_description": "Cómo recibe cada {decideur} las solicitudes, las alertas y el resumen.",
    "titre": "Mis notificaciones",
    "aide": "Marca uno o varios canales por tema. Ninguna casilla: no se envía nada, todo queda en Vision.",
    "pour": "Para",
    "evt_demandes": "Solicitudes",
    "evt_demandes_aide": "Acceso, tiempo extra, desbloqueo, nuevo dispositivo",
    "evt_alertes": "Alertas de protección",
    "evt_alertes_aide": "Códigos de desbloqueo erróneos repetidos",
    "evt_resume": "Resumen semanal",
    "evt_resume_aide": "El informe del domingo por la noche",
    "canal_vision": "App Vision",
    "canal_compagnon": "App Companion de HA",
    "canal_discord": "Discord",
    "manque_vision": "Ningún dispositivo Vision a este nombre.",
    "manque_compagnon": "Ningún teléfono vinculado a Home Assistant.",
    "manque_discord": "El canal de Discord se configura en Configurar.",
    "par_defaut": "Ajuste por defecto: un solo canal, para evitar duplicados.",
    "rien": "No se envía nada: todo queda en Vision.",
    "fait": "Guardado.",
    "aucun_decideur": "Esta cuenta no está vinculada a ningún {decideur}.",
    "chargement": "Cargando…",
    "erreur_defaut": "No se ha podido guardar el ajuste: inténtalo de nuevo.",
    "erreur_connexion": "Conexión perdida con Home Assistant: inténtalo de nuevo en un momento.",
    "erreur_admin": "Solo para administradores de Home Assistant."
  },
  "it": {
    "carte_nom": "Vision — Le mie notifiche",
    "carte_description": "Come ogni {decideur} riceve richieste, avvisi e riepilogo.",
    "titre": "Le mie notifiche",
    "aide": "Spunta uno o più canali per argomento. Nessuna casella: non parte nulla, tutto resta in Vision.",
    "pour": "Per",
    "evt_demandes": "Richieste",
    "evt_demandes_aide": "Accesso, tempo in più, sblocco, nuovo dispositivo",
    "evt_alertes": "Avvisi di protezione",
    "evt_alertes_aide": "Codici di sblocco errati ripetuti",
    "evt_resume": "Riepilogo settimanale",
    "evt_resume_aide": "Il resoconto della domenica sera",
    "canal_vision": "App Vision",
    "canal_compagnon": "App Companion di HA",
    "canal_discord": "Discord",
    "manque_vision": "Nessun dispositivo Vision a questo nome.",
    "manque_compagnon": "Nessun telefono collegato a Home Assistant.",
    "manque_discord": "Il canale Discord si imposta in Configura.",
    "par_defaut": "Impostazione predefinita: un solo canale, per evitare doppioni.",
    "rien": "Non parte nulla: tutto resta in Vision.",
    "fait": "Salvato.",
    "aucun_decideur": "Questo account non è collegato a nessun {decideur}.",
    "chargement": "Caricamento…",
    "erreur_defaut": "Non è stato possibile salvare l'impostazione: riprova.",
    "erreur_connexion": "Connessione con Home Assistant persa: riprova tra poco.",
    "erreur_admin": "Riservato agli amministratori di Home Assistant."
  },
  "nl": {
    "carte_nom": "Vision — Mijn meldingen",
    "carte_description": "Hoe elke {decideur} verzoeken, waarschuwingen en de samenvatting krijgt.",
    "titre": "Mijn meldingen",
    "aide": "Vink per onderwerp een of meer kanalen aan. Geen vinkje: er wordt niets verstuurd, alles blijft in Vision.",
    "pour": "Voor",
    "evt_demandes": "Verzoeken",
    "evt_demandes_aide": "Toegang, extra tijd, ontgrendelen, nieuw apparaat",
    "evt_alertes": "Beveiligingswaarschuwingen",
    "evt_alertes_aide": "Herhaaldelijk foute ontgrendelcodes",
    "evt_resume": "Weekoverzicht",
    "evt_resume_aide": "Het verslag van zondagavond",
    "canal_vision": "Vision-app",
    "canal_compagnon": "HA Companion-app",
    "canal_discord": "Discord",
    "manque_vision": "Geen Vision-apparaat op deze naam.",
    "manque_compagnon": "Geen telefoon gekoppeld aan Home Assistant.",
    "manque_discord": "Het Discord-kanaal stel je in bij Configureren.",
    "par_defaut": "Standaardinstelling: één kanaal, om dubbele meldingen te voorkomen.",
    "rien": "Er wordt niets verstuurd: alles blijft in Vision.",
    "fait": "Opgeslagen.",
    "aucun_decideur": "Dit account is aan geen enkele {decideur} gekoppeld.",
    "chargement": "Laden…",
    "erreur_defaut": "De instelling kon niet worden opgeslagen: probeer het opnieuw.",
    "erreur_connexion": "Verbinding met Home Assistant verbroken: probeer het zo opnieuw.",
    "erreur_admin": "Alleen voor Home Assistant-beheerders."
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
const API = "pc_parental/canaux";
const esc = (t) =>
  String(t ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const messageErreur = (e) => {
  const b = e && e.body;
  if (b && b.retour) return String(b.retour);
  if (e && (e.status_code === 403 || (b && b.error === "admin"))) return tr("erreur_admin");
  if (e && e.error === "connection lost") return tr("erreur_connexion");  // i18n-ignorer : code d'erreur du websocket de Home Assistant (comparé, jamais affiché)
  return tr("erreur_defaut");
};

class VisionCanauxCard extends HTMLElement {
  setConfig(config) { this._config = config || {}; }
  getCardSize() { return 6; }

  set hass(h) {
    const premier = !this._hass;
    this._hass = h;
    const langue = suivreLangue(h);
    if (premier) { this.attachShadow({ mode: "open" }); this._langue = langue; this._charger(); }
    else if (langue !== this._langue) { this._langue = langue; this._rendre(); }
  }

  async _charger() {
    try {
      this._d = await this._hass.callApi("GET", API);
      this._erreur = "";
      const ps = (this._d.decideurs || []).map((p) => p.personne);
      if (!ps.includes(this._personne)) this._personne = ps.includes(this._d.moi) ? this._d.moi : ps[0];
    } catch (e) { this._erreur = messageErreur(e); }
    this._rendre();
  }

  async _poser(evenement, canaux) {
    this._statut = ""; this._ko = false;
    try {
      const r = await this._hass.callApi("POST", API, { personne: this._personne, evenement, canaux });
      this._d.etats[this._personne] = r;
      this._statut = tr("fait");
    } catch (e) { this._statut = messageErreur(e); this._ko = true; }
    this._rendre();
  }

  _rendre() {
    if (!this.shadowRoot) return;
    const d = this._d;
    const r = this.shadowRoot;
    let corps;
    if (this._erreur) corps = `<div class="vide" role="alert">${esc(this._erreur)}</div>`;
    else if (!d) corps = `<div class="vide">${esc(tr("chargement"))}</div>`;
    else if (!(d.decideurs || []).length) corps = `<div class="vide">${esc(tr("aucun_decideur"))}</div>`;
    else corps = this._contenu(d);
    r.innerHTML = `<style>${this._style()}</style><ha-card><div class="titre">${esc(tr("titre"))}</div>${corps}</ha-card>`;
    this._brancher();
  }

  _contenu(d) {
    const e = d.etats[this._personne] || { choix: {}, possibles: {}, par_defaut: [] };
    const choixPersonne = d.decideurs.length > 1
      ? `<label class="pour">${esc(tr("pour"))} <select id="personne">${d.decideurs.map((p) =>
        `<option value="${esc(p.personne)}" ${p.personne === this._personne ? "selected" : ""}>${esc(p.nom)}</option>`).join("")}</select></label>`
      : "";
    const manques = d.canaux.filter((c) => !e.possibles[c]).map((c) => `<li>${esc(tr("canal_" + c))} : ${esc(tr("manque_" + c))}</li>`).join("");
    const sujets = d.evenements.map((ev) => {
      const coches = e.choix[ev] || [];
      const cases = d.canaux.map((c) => {
        const on = coches.includes(c);
        const off = !e.possibles[c] && !on;
        return `<label class="case ${off ? "off" : ""}"><input type="checkbox" data-ev="${esc(ev)}" data-canal="${esc(c)}" ${on ? "checked" : ""} ${off ? "disabled" : ""}> ${esc(tr("canal_" + c))}</label>`;
      }).join("");
      return `<fieldset><legend>${esc(tr("evt_" + ev))}</legend><div class="sous">${esc(tr("evt_" + ev + "_aide"))}</div>
        <div class="cases">${cases}</div>
        ${coches.length ? "" : `<div class="note">${esc(tr("rien"))}</div>`}
        ${(e.par_defaut || []).includes(ev) ? `<div class="note">${esc(tr("par_defaut"))}</div>` : ""}</fieldset>`;
    }).join("");
    return `<div class="aide">${esc(tr("aide"))}</div>${choixPersonne}${sujets}
      ${manques ? `<ul class="manques">${manques}</ul>` : ""}
      <div class="statut ${this._ko ? "ko" : ""}" role="status">${esc(this._statut || "")}</div>`;
  }

  _brancher() {
    const r = this.shadowRoot;
    const sel = r.getElementById("personne");
    if (sel) sel.addEventListener("change", () => { this._personne = sel.value; this._statut = ""; this._rendre(); });
    r.querySelectorAll("input[data-ev]").forEach((b) => b.addEventListener("change", () => {
      const ev = b.dataset.ev;
      const canaux = [...r.querySelectorAll(`input[data-ev="${ev}"]`)].filter((x) => x.checked).map((x) => x.dataset.canal);
      this._poser(ev, canaux);
    }));
  }

  _style() {
    return `
      ha-card { padding: 16px; }
      .titre { font-size: 20px; font-weight: 600; margin-bottom: 6px; }
      .aide, .sous, .note { color: var(--secondary-text-color); font-size: 13px; }
      .aide { margin-bottom: 10px; }
      .pour { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin: 6px 0 10px; }
      select { font: inherit; padding: 8px 10px; border-radius: 10px; border: 1px solid var(--divider-color); background: var(--card-background-color); color: var(--primary-text-color); min-height: 44px; max-width: 100%; }
      fieldset { border: 1px solid var(--divider-color); border-radius: 12px; margin: 10px 0 0; padding: 10px 12px; min-width: 0; }
      legend { font-weight: 600; padding: 0 4px; }
      .cases { display: flex; flex-wrap: wrap; gap: 6px 18px; margin-top: 8px; }
      .case { display: flex; gap: 8px; align-items: center; min-height: 36px; }
      .case.off { opacity: .5; }
      input[type="checkbox"] { width: 20px; height: 20px; margin: 0; flex: none; }
      .note { margin-top: 6px; }
      .manques { color: var(--secondary-text-color); font-size: 13px; margin: 10px 0 0; padding-left: 18px; }
      .statut { min-height: 1.3em; margin-top: 8px; } .statut.ko { color: var(--error-color); }
      .vide { color: var(--secondary-text-color); padding: 8px 0; }`;
  }
}

if (!customElements.get("vision-canaux-card")) customElements.define("vision-canaux-card", VisionCanauxCard);
window.customCards = window.customCards || [];
window.customCards.push({ type: "vision-canaux-card", name: tr("carte_nom"), description: tr("carte_description") });
