// Vision : valider l'appairage d'un appareil. Panneau caché, réservé aux administrateurs
// (Home Assistant refuse les autres) ; on y arrive par le QR de l'appareil (?code=…).
// Sans code : générer un QR d'appairage, que l'appareil (avec caméra) scanne lui-même.
// ---- i18n:debut — les textes de ce panneau, dans les six langues de Vision (fr source, en repli) ----
const TEXTES = /*i18n-json*/{
  "fr": {
    "erreur_admin": "Réservé aux administrateurs de Home Assistant.",
    "erreur_qr": "Impossible de générer le QR (adresse de Home Assistant inconnue, ou trop de QR en cours).",
    "demande_refusee": "Demande {code} refusée.",
    "inscrit": "« {nom} » est inscrit. L'appareil va se connecter.",
    "inscrit_renouvele": "« {nom} » est inscrit (secret renouvelé). L'appareil va se connecter.",
    "code_inconnu": "Code inconnu ou expiré : relance la demande sur l'appareil.",
    "libelle_personne": "Personne", "plus_tard": "À choisir plus tard",
    "appareil": "appareil", "machine": "machine {host}",
    "depuis_expire": "depuis {adresse} · expire à {heure}",
    "deja_inscrit": "Déjà inscrit sous « {nom} » : valider lui donne un secret neuf.",
    "valider": "Valider", "refuser": "Refuser",
    "nouvel_appareil": "Nouvel appareil", "reprendre": "Reprendre « {nom} »",
    "generer_titre": "Générer un QR d'appairage",
    "generer_aide": "Pour un appareil avec caméra : dans l'appli Vision, « Scanner le QR de Home Assistant ». Une seule fois, 5 minutes ; jamais la clé d'inscription.",
    "generer_bouton": "Générer le QR",
    "qr_valable": "Valable jusqu'à {heure}, une seule fois. Ne le laisse pas affiché à la vue de tous.",
    "code_absent": "Le code {code} n'est pas (ou plus) en attente sur ce Home Assistant.",
    "titre": "Appairer un appareil",
    "verifier": "Vérifie que le code affiché sur l'appareil est bien celui-ci avant de valider.",
    "autres_demandes_one": "{n} autre demande en attente, non affichée ici : si tu n'en es pas l'auteur, refuse-la depuis Vision → Configurer.",
    "autres_demandes_other": "{n} autres demandes en attente, non affichées ici : si tu n'en es pas l'auteur, refuse-les depuis Vision → Configurer.",
    "aucune_demande": "Aucune demande en attente."
  },
  "en": {
    "erreur_admin": "Only Home Assistant administrators can do this.",
    "erreur_qr": "Couldn't create the QR code (Home Assistant address unknown, or too many QR codes in progress).",
    "demande_refusee": "Request {code} declined.",
    "inscrit": "“{nom}” is enrolled. The device will connect.",
    "inscrit_renouvele": "“{nom}” is enrolled (secret renewed). The device will connect.",
    "code_inconnu": "Unknown or expired code: start the request again on the device.",
    "libelle_personne": "Person", "plus_tard": "Choose later",
    "appareil": "device", "machine": "machine {host}",
    "depuis_expire": "from {adresse} · expires at {heure}",
    "deja_inscrit": "Already enrolled as “{nom}”: approving gives it a new secret.",
    "valider": "Approve", "refuser": "Decline",
    "nouvel_appareil": "New device", "reprendre": "Take over “{nom}”",
    "generer_titre": "Create a pairing QR code",
    "generer_aide": "For a device with a camera: in the Vision app, “Scan the Home Assistant QR code”. Single use, 5 minutes; never the enrollment key.",
    "generer_bouton": "Create the QR code",
    "qr_valable": "Valid until {heure}, single use. Don't leave it on screen where anyone can see it.",
    "code_absent": "Code {code} is not (or no longer) waiting on this Home Assistant.",
    "titre": "Pair a device",
    "verifier": "Check that the code shown on the device is this one before approving.",
    "autres_demandes_one": "{n} other request waiting, not shown here: if you didn't make it, decline it from Vision → Configure.",
    "autres_demandes_other": "{n} other requests waiting, not shown here: if you didn't make them, decline them from Vision → Configure.",
    "aucune_demande": "No requests waiting."
  },
  "de": {
    "erreur_admin": "Nur für Home Assistant-Administratoren.",
    "erreur_qr": "Der QR-Code konnte nicht erstellt werden (Adresse von Home Assistant unbekannt oder zu viele QR-Codes in Bearbeitung).",
    "demande_refusee": "Anfrage {code} abgelehnt.",
    "inscrit": "„{nom}“ ist registriert. Das Gerät verbindet sich gleich.",
    "inscrit_renouvele": "„{nom}“ ist registriert (Geheimnis erneuert). Das Gerät verbindet sich gleich.",
    "code_inconnu": "Unbekannter oder abgelaufener Code: starte die Anfrage auf dem Gerät neu.",
    "libelle_personne": "Person", "plus_tard": "Später auswählen",
    "appareil": "Gerät", "machine": "Rechner {host}",
    "depuis_expire": "von {adresse} · läuft um {heure} ab",
    "deja_inscrit": "Bereits registriert als „{nom}“: Genehmigen gibt ihm ein neues Geheimnis.",
    "valider": "Genehmigen", "refuser": "Ablehnen",
    "nouvel_appareil": "Neues Gerät", "reprendre": "„{nom}“ übernehmen",
    "generer_titre": "Kopplungs-QR-Code erstellen",
    "generer_aide": "Für ein Gerät mit Kamera: in der Vision-App „Home Assistant-QR-Code scannen“. Nur einmal, 5 Minuten; niemals der Registrierungsschlüssel.",
    "generer_bouton": "QR-Code erstellen",
    "qr_valable": "Gültig bis {heure}, nur einmal. Lass ihn nicht für alle sichtbar auf dem Bildschirm.",
    "code_absent": "Der Code {code} wartet nicht (oder nicht mehr) auf diesem Home Assistant.",
    "titre": "Gerät koppeln",
    "verifier": "Prüfe vor dem Genehmigen, ob der auf dem Gerät angezeigte Code genau dieser ist.",
    "autres_demandes_one": "{n} weitere Anfrage wartet, hier nicht angezeigt: wenn sie nicht von dir ist, lehne sie in Vision → Konfigurieren ab.",
    "autres_demandes_other": "{n} weitere Anfragen warten, hier nicht angezeigt: wenn sie nicht von dir sind, lehne sie in Vision → Konfigurieren ab.",
    "aucune_demande": "Keine wartenden Anfragen."
  },
  "es": {
    "erreur_admin": "Solo para administradores de Home Assistant.",
    "erreur_qr": "No se pudo generar el QR (dirección de Home Assistant desconocida, o demasiados QR en curso).",
    "demande_refusee": "Solicitud {code} rechazada.",
    "inscrit": "«{nom}» está registrado. El dispositivo se va a conectar.",
    "inscrit_renouvele": "«{nom}» está registrado (secreto renovado). El dispositivo se va a conectar.",
    "code_inconnu": "Código desconocido o caducado: vuelve a enviar la solicitud desde el dispositivo.",
    "libelle_personne": "Persona", "plus_tard": "Elegir más tarde",
    "appareil": "dispositivo", "machine": "equipo {host}",
    "depuis_expire": "desde {adresse} · caduca a las {heure}",
    "deja_inscrit": "Ya registrado como «{nom}»: aprobar le da un secreto nuevo.",
    "valider": "Aprobar", "refuser": "Rechazar",
    "nouvel_appareil": "Nuevo dispositivo", "reprendre": "Retomar «{nom}»",
    "generer_titre": "Generar un QR de vinculación",
    "generer_aide": "Para un dispositivo con cámara: en la app Vision, «Escanear el QR de Home Assistant». Una sola vez, 5 minutos; nunca la clave de registro.",
    "generer_bouton": "Generar el QR",
    "qr_valable": "Válido hasta las {heure}, una sola vez. No lo dejes en pantalla a la vista de todos.",
    "code_absent": "El código {code} no está (o ya no está) pendiente en este Home Assistant.",
    "titre": "Vincular un dispositivo",
    "verifier": "Comprueba que el código que muestra el dispositivo es este antes de aprobar.",
    "autres_demandes_one": "{n} solicitud más pendiente, no mostrada aquí: si no la has hecho tú, recházala desde Vision → Configurar.",
    "autres_demandes_other": "{n} solicitudes más pendientes, no mostradas aquí: si no las has hecho tú, recházalas desde Vision → Configurar.",
    "aucune_demande": "Ninguna solicitud pendiente."
  },
  "it": {
    "erreur_admin": "Riservato agli amministratori di Home Assistant.",
    "erreur_qr": "Impossibile generare il QR (indirizzo di Home Assistant sconosciuto, o troppi QR in corso).",
    "demande_refusee": "Richiesta {code} rifiutata.",
    "inscrit": "«{nom}» è registrato. Il dispositivo si collegherà.",
    "inscrit_renouvele": "«{nom}» è registrato (segreto rinnovato). Il dispositivo si collegherà.",
    "code_inconnu": "Codice sconosciuto o scaduto: rifai la richiesta sul dispositivo.",
    "libelle_personne": "Persona", "plus_tard": "Da scegliere più tardi",
    "appareil": "dispositivo", "machine": "computer {host}",
    "depuis_expire": "da {adresse} · scade alle {heure}",
    "deja_inscrit": "Già registrato come «{nom}»: approvare gli dà un segreto nuovo.",
    "valider": "Approva", "refuser": "Rifiuta",
    "nouvel_appareil": "Nuovo dispositivo", "reprendre": "Riprendi «{nom}»",
    "generer_titre": "Genera un QR di abbinamento",
    "generer_aide": "Per un dispositivo con fotocamera: nell'app Vision, «Scansiona il QR di Home Assistant». Una sola volta, 5 minuti; mai la chiave di registrazione.",
    "generer_bouton": "Genera il QR",
    "qr_valable": "Valido fino alle {heure}, una sola volta. Non lasciarlo sullo schermo in vista di tutti.",
    "code_absent": "Il codice {code} non è (o non è più) in attesa su questo Home Assistant.",
    "titre": "Abbina un dispositivo",
    "verifier": "Prima di approvare, verifica che il codice mostrato sul dispositivo sia proprio questo.",
    "autres_demandes_one": "{n} altra richiesta in attesa, non mostrata qui: se non l'hai fatta tu, rifiutala da Vision → Configura.",
    "autres_demandes_other": "{n} altre richieste in attesa, non mostrate qui: se non le hai fatte tu, rifiutale da Vision → Configura.",
    "aucune_demande": "Nessuna richiesta in attesa."
  },
  "nl": {
    "erreur_admin": "Alleen voor beheerders van Home Assistant.",
    "erreur_qr": "De QR-code kon niet worden gemaakt (adres van Home Assistant onbekend, of te veel QR-codes tegelijk).",
    "demande_refusee": "Verzoek {code} geweigerd.",
    "inscrit": "„{nom}” is geregistreerd. Het apparaat maakt zo verbinding.",
    "inscrit_renouvele": "„{nom}” is geregistreerd (geheim vernieuwd). Het apparaat maakt zo verbinding.",
    "code_inconnu": "Onbekende of verlopen code: start het verzoek opnieuw op het apparaat.",
    "libelle_personne": "Persoon", "plus_tard": "Later kiezen",
    "appareil": "apparaat", "machine": "computer {host}",
    "depuis_expire": "vanaf {adresse} · verloopt om {heure}",
    "deja_inscrit": "Al geregistreerd als „{nom}”: goedkeuren geeft het een nieuw geheim.",
    "valider": "Goedkeuren", "refuser": "Weigeren",
    "nouvel_appareil": "Nieuw apparaat", "reprendre": "„{nom}” overnemen",
    "generer_titre": "Koppel-QR-code maken",
    "generer_aide": "Voor een apparaat met camera: in de Vision-app, „QR-code van Home Assistant scannen”. Eenmalig, 5 minuten; nooit de registratiesleutel.",
    "generer_bouton": "QR-code maken",
    "qr_valable": "Geldig tot {heure}, eenmalig. Laat hem niet voor iedereen zichtbaar op het scherm staan.",
    "code_absent": "De code {code} staat niet (of niet meer) in de wachtrij op deze Home Assistant.",
    "titre": "Een apparaat koppelen",
    "verifier": "Controleer voordat je goedkeurt of de code op het apparaat precies deze is.",
    "autres_demandes_one": "{n} ander verzoek in de wachtrij, hier niet getoond: als het niet van jou is, weiger het via Vision → Configureren.",
    "autres_demandes_other": "{n} andere verzoeken in de wachtrij, hier niet getoond: als ze niet van jou zijn, weiger ze via Vision → Configureren.",
    "aucune_demande": "Geen verzoeken in de wachtrij."
  }
}/*i18n-json:fin*/;
// Les catégories (étiquettes de la maison) : ce panneau n'en affiche pas.
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
class VisionAppairagePanel extends HTMLElement {
  set hass(hass) {
    const premier = !this._hass;
    this._hass = hass;
    const langue = suivreLangue(hass);
    if (premier) { this._langue = langue; this._code = new URLSearchParams(location.search).get("code") || ""; this._charger(); }
    else if (langue !== this._langue) { this._langue = langue; this._dessiner(); }
  }

  async _charger(message) {
    try {
      this._donnees = await this._hass.callApi("GET", "pc_parental/appairage");
      this._message = message || "";
    } catch (e) {
      this._donnees = { demandes: [], personnes: [] };
      this._message = tr("erreur_admin");
    }
    this._dessiner();
  }

  async _generer() {
    const valeur = (nom) => (this.querySelector(`select[name="${nom}"]`) || {}).value || "";
    try {
      const r = await this._hass.callApi("POST", "pc_parental/appairage/invitation", {
        personne: valeur("qr-personne"), appareil: valeur("qr-appareil"),
      });
      this._qr = { svg: r.svg, expire: Date.now() + r.expire_dans * 1000 };
      clearTimeout(this._minuteur);
      this._minuteur = setTimeout(() => { this._qr = null; this._dessiner(); }, r.expire_dans * 1000);
      this._message = "";
    } catch (e) {
      this._qr = null;
      this._message = tr("erreur_qr");
    }
    this._dessiner();
  }

  async _decider(code, action) {
    const choix = this.querySelector(`select[data-code="${code}"]`);
    try {
      const r = await this._hass.callApi("POST", "pc_parental/appairage", {
        code, action, personne: choix ? choix.value : "",
      });
      const f = r.fait || {};
      this._traite = true;
      this._donnees = r;
      this._message = f.refusee ? tr("demande_refusee", { code })
        : tr(f.created ? "inscrit" : "inscrit_renouvele", { nom: f.name });
    } catch (e) {
      this._message = tr("code_inconnu");
    }
    this._dessiner();
  }

  _dessiner() {
    const d = this._donnees || { demandes: [], personnes: [] };
    const echap = (t) => String(t ?? "").replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
    const options = [`<option value="">${echap(tr("plus_tard"))}</option>`]
      .concat(d.personnes.map((p) => `<option value="${echap(p.id)}">${echap(p.nom)}</option>`)).join("");
    // Arrivé par le QR : seulement la demande de l'appareil qu'on a devant soi. Une autre demande
    // lancée au même moment (par qui ?) ne doit pas pouvoir être validée par erreur.
    const visee = d.demandes.filter((x) => x.code === this._code);
    const demandes = this._code ? visee : d.demandes;
    const autres = this._code ? d.demandes.length - visee.length : 0;
    const cartes = demandes.map((x) => `
      <div class="carte${x.code === this._code ? " visee" : ""}">
        <div class="code">${echap(x.code)}</div>
        <div><b>${echap(x.nom)}</b> · ${echap(x.plateforme || tr("appareil"))} · ${echap(tr("machine", { host: x.host }))}</div>
        <div class="petit">${echap(tr("depuis_expire", { adresse: x.adresse, heure: new Date(x.expire * 1000).toLocaleTimeString(LOCALE) }))}</div>
        ${x.existant ? `<div class="alerte">${echap(tr("deja_inscrit", { nom: x.existant }))}</div>` : ""}
        <label class="champ">${echap(tr("libelle_personne"))}<select data-code="${echap(x.code)}">${options}</select></label>
        <div class="boutons">
          <button data-code="${echap(x.code)}" data-action="valider">${echap(tr("valider"))}</button>
          <button data-code="${echap(x.code)}" data-action="refuser" class="non">${echap(tr("refuser"))}</button>
        </div>
      </div>`).join("");
    const appareils = [`<option value="">${echap(tr("nouvel_appareil"))}</option>`]
      .concat((d.appareils || []).map((a) => `<option value="${echap(a.id)}">${echap(tr("reprendre", { nom: a.nom }))}</option>`)).join("");
    // Le SVG vient de Home Assistant (fabriqué à partir du seul contenu du QR), pas d'un texte reçu.
    const generateur = this._code ? "" : `
      <div class="carte">
        <h2>${echap(tr("generer_titre"))}</h2>
        <p class="petit">${echap(tr("generer_aide"))}</p>
        <label class="champ">${echap(tr("libelle_personne"))}<select name="qr-personne">${options}</select></label>
        <select name="qr-appareil">${appareils}</select>
        <div class="boutons"><button data-generer="1">${echap(tr("generer_bouton"))}</button></div>
        ${this._qr ? `<div class="qr">${this._qr.svg}</div>
          <div class="petit">${echap(tr("qr_valable", { heure: new Date(this._qr.expire).toLocaleTimeString(LOCALE) }))}</div>` : ""}
      </div>`;
    const absent = this._code && !this._traite && !d.demandes.some((x) => x.code === this._code)
      ? `<p class="alerte">${echap(tr("code_absent", { code: this._code }))}</p>` : "";
    this.innerHTML = `
      <style>
        :host, vision-appairage-panel { display:block; padding:16px; max-width:640px; margin:auto;
          font-family: var(--paper-font-body1_-_font-family, sans-serif); color: var(--primary-text-color); }
        .carte { background: var(--card-background-color); border-radius:12px; padding:16px; margin:12px 0;
          border:1px solid var(--divider-color); overflow-wrap:anywhere; }
        .champ { display:block; margin-top:8px; color: var(--secondary-text-color); font-size:13px; }
        @media (max-width: 480px) { :host, vision-appairage-panel { padding:8px; } .carte { padding:12px; } }
        .carte select { display:block; width:100%; max-width:100%; box-sizing:border-box; margin:6px 0 0; }
        .visee { border:2px solid var(--primary-color); }
        .code { font-size:28px; letter-spacing:4px; font-weight:bold; }
        .petit { color: var(--secondary-text-color); font-size:13px; margin:4px 0 8px; }
        .alerte { color: var(--warning-color, #b26a00); margin:8px 0; }
        select, button { font-size:16px; padding:8px 12px; margin:6px 6px 0 0; border-radius:8px; }
        button { background: var(--primary-color); color: var(--text-primary-color, #fff); border:none; }
        button.non { background: var(--secondary-background-color); color: var(--primary-text-color); }
        .qr { background:#fff; display:inline-block; margin-top:12px; padding:8px; border-radius:8px; }
        .qr svg { width: min(320px, 80vw); height:auto; display:block; }
      </style>
      <h1>${echap(tr("titre"))}</h1>
      <p>${echap(tr("verifier"))}</p>
      ${this._message ? `<p><b>${echap(this._message)}</b></p>` : ""}
      ${absent}
      ${autres ? `<p class="alerte">${echap(trn("autres_demandes", autres))}</p>` : ""}
      ${generateur}
      ${cartes || `<p>${echap(tr("aucune_demande"))}</p>`}`;
    const bouton = this.querySelector("button[data-generer]");
    if (bouton) bouton.addEventListener("click", () => this._generer());
    this.querySelectorAll("button[data-action]").forEach((b) =>
      b.addEventListener("click", () => this._decider(b.dataset.code, b.dataset.action)));
  }
}
customElements.define("vision-appairage-panel", VisionAppairagePanel);
