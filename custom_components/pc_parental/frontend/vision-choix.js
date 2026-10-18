/*
 * Vision — sélecteur adaptatif « étiquettes ou liste déroulante » (15-10, règle UX de l'auteur), partagé par toutes les
 * cartes et panneaux de Vision. Chargé partout comme les cartes (cartes.py) ; un élément <vision-choix> posé avant
 * son arrivée se met à jour tout seul quand il est défini.
 */
// ---- i18n:debut — les textes de cet élément, dans les six langues de Vision (fr source, en repli) ----
const TEXTES = /*i18n-json*/{
  "fr": {"aucun": "Aucun"},
  "en": {"aucun": "None"},
  "de": {"aucun": "Keine"},
  "es": {"aucun": "Ninguno"},
  "it": {"aucun": "Nessuno"},
  "nl": {"aucun": "Geen"}
}/*i18n-json:fin*/;
// Les catégories : cet élément n'en affiche pas.
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

// Règle UX de l'auteur (15-10), pour l'appli Android ET les panneaux Home Assistant : un choix parmi des options s'affiche
// - en ÉTIQUETTES quand il y a 3 options ou moins ET qu'elles tiennent sur une ligne (mesuré, pas seulement compté) ;
// - sinon en LISTE DÉROULANTE qui montre l'option choisie (un <select> natif en choix unique ; en choix multiple, un
//   champ « A, B +2 ▾ » qui déplie des cases).
// La décision est reprise à chaque changement de largeur (ResizeObserver) : rotation, panneau étroit, police agrandie.
// Le pendant Android est ChoixAdaptatif.kt.
//
//   <vision-choix titre="…" valeur="k" options='[{"cle":"k","libelle":"…"}]'></vision-choix>
//   <vision-choix multiple max-choix="3" valeurs='["a","b"]' options='…'></vision-choix>
// ou par propriétés : el.options = […], el.valeur = "k", el.valeurs = […].
// Événement « vision-choix » (bouillonne, traverse les ombres) : detail = { valeur, valeurs } ;
// « vision-choix-trop » quand on veut cocher au-delà de max-choix.
const MAX_ETIQUETTES = 3;

// Étiquettes ou liste ? (pur : essai/choix_adaptatif_test.py le vérifie avec Node)
const enEtiquettes = (nombre, largeurEtiquettes, dispo, max = MAX_ETIQUETTES) =>
  nombre >= 1 && nombre <= max && (dispo <= 0 || largeurEtiquettes <= dispo);
// Le texte du champ en choix multiple : « A, B +2 » ; [aucune] si rien.
const resume = (libelles, aucune, max = 2) =>
  !libelles.length ? aucune : libelles.length <= max ? libelles.join(", ") : `${libelles.slice(0, max).join(", ")} +${libelles.length - max}`;

const echC = (t) => String(t ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

const STYLE_CHOIX = `
  :host { display: block; min-width: 0; }
  .mesure { position: absolute; visibility: hidden; height: 0; overflow: hidden; white-space: nowrap; display: flex; gap: 8px; }
  .rangee { display: flex; gap: 8px; flex-wrap: nowrap; }
  .etq { font: inherit; font-size: 14px; font-weight: 500; min-height: 44px; padding: 6px 14px; border-radius: 999px; cursor: pointer;
    white-space: nowrap; display: inline-flex; align-items: center; gap: 6px;
    color: var(--primary-text-color); background: var(--card-background-color, transparent);
    border: 1px solid var(--divider-color, rgba(127,127,127,.4)); }
  .etq[aria-checked="true"] { background: var(--primary-color); border-color: var(--primary-color); color: var(--text-primary-color, #fff); }
  .etq:focus-visible, select:focus-visible, .champ:focus-visible { outline: 2px solid var(--primary-color); outline-offset: 2px; }
  select, .champ { font: inherit; font-size: 14px; font-weight: 500; width: 100%; min-height: 44px; box-sizing: border-box; padding: 6px 12px;
    border-radius: 12px; color: var(--primary-text-color); background: var(--card-background-color, transparent);
    border: 1px solid var(--divider-color, rgba(127,127,127,.4)); cursor: pointer; text-align: start; }
  .champ { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
  .champ span { overflow-wrap: anywhere; }
  .liste { margin-top: 6px; padding: 6px; border-radius: 12px; border: 1px solid var(--divider-color, rgba(127,127,127,.4));
    background: var(--card-background-color, transparent); display: grid; gap: 2px; }
  .liste label { display: flex; align-items: center; gap: 10px; min-height: 44px; padding: 0 8px; cursor: pointer; border-radius: 8px; }
  .liste label:hover { background: var(--secondary-background-color, rgba(127,127,127,.12)); }
  ha-icon { --mdc-icon-size: 18px; }
`;

class VisionChoix extends HTMLElement {
  static get observedAttributes() { return ["options", "valeur", "valeurs", "titre", "multiple", "max-choix"]; }

  constructor() {
    super();
    this.attachShadow({ mode: "open" });
    this._etiquettes = null;
    this._ouverte = false;
  }

  connectedCallback() {
    // Une propriété posée avant la définition de l'élément (carte dessinée avant ce fichier) : reprise ici.
    for (const p of ["options", "valeur", "valeurs"]) {
      if (Object.prototype.hasOwnProperty.call(this, p)) { const v = this[p]; delete this[p]; this[p] = v; }
    }
    this._rendre();
    if (!this._ro && typeof ResizeObserver !== "undefined") {
      this._ro = new ResizeObserver(() => this._decider());
      this._ro.observe(this);
    }
  }

  disconnectedCallback() { if (this._ro) { this._ro.disconnect(); this._ro = null; } }

  attributeChangedCallback() { if (this.isConnected) this._rendre(); }

  get options() {
    if (this._options) return this._options;
    try { const o = JSON.parse(this.getAttribute("options") || "[]"); return Array.isArray(o) ? o : []; } catch (e) { return []; }
  }
  set options(v) { this._options = Array.isArray(v) ? v : []; if (this.isConnected) this._rendre(); }

  get multiple() { return this.hasAttribute("multiple"); }
  get titre() { return this.getAttribute("titre") || ""; }
  get maxChoix() { const n = parseInt(this.getAttribute("max-choix") || "", 10); return n > 0 ? n : Infinity; }

  get valeurs() {
    let v = this._valeurs;
    if (!v) {
      const a = this.getAttribute("valeurs");
      if (a !== null) { try { v = JSON.parse(a); } catch (e) { v = []; } }
      else { const s = this.getAttribute("valeur"); v = s === null ? [] : [s]; }
    }
    const cles = new Set(this.options.map((o) => String(o.cle)));
    return (Array.isArray(v) ? v : []).map(String).filter((c) => cles.has(c));
  }
  set valeurs(v) { this._valeurs = (Array.isArray(v) ? v : []).map(String); if (this.isConnected) this._rendre(); }
  get valeur() { return this.valeurs[0] ?? ""; }
  set valeur(v) { this._valeurs = v === null || v === undefined ? [] : [String(v)]; if (this.isConnected) this._rendre(); }
  get value() { return this.multiple ? this.valeurs : this.valeur; }
  /** Vrai si l'élément montre des étiquettes (essais). */
  get montreEtiquettes() { return this._etiquettes === true; }

  _choisir(cle) {
    let v = this.valeurs;
    if (this.multiple) {
      if (v.includes(cle)) v = v.filter((c) => c !== cle);
      else if (v.length >= this.maxChoix) {
        this.dispatchEvent(new CustomEvent("vision-choix-trop", { bubbles: true, composed: true, detail: { max: this.maxChoix } }));
        this._dessiner(); return;
      } else v = [...v, cle];
    } else {
      if (v.length === 1 && v[0] === cle) return;
      v = [cle];
    }
    this._valeurs = v;
    this._dessiner();
    this.dispatchEvent(new CustomEvent("vision-choix", { bubbles: true, composed: true, detail: { valeur: v[0] ?? "", valeurs: v.slice() } }));
  }

  _icone(o) { return o.icone ? `<ha-icon icon="${echC(o.icone)}"></ha-icon>` : ""; }

  _etiquettesHtml(mesure) {
    const v = this.valeurs;
    const role = this.multiple ? "checkbox" : "radio";
    return this.options.map((o) => `<button type="button" class="etq" role="${role}" ${mesure ? 'tabindex="-1"' : "data-cible"}
      aria-checked="${v.includes(String(o.cle))}" data-cle="${echC(o.cle)}" title="${echC(o.description || o.libelle)}">${this._icone(o)}${echC(o.libelle)}</button>`).join("");
  }

  _rendre() {
    this.shadowRoot.innerHTML = `<style>${STYLE_CHOIX}</style><div class="mesure" aria-hidden="true">${this._etiquettesHtml(true)}</div><div class="vue"></div>`;
    this._etiquettes = null;
    this._decider();
  }

  _decider() {
    const mesure = this.shadowRoot.querySelector(".mesure");
    if (!mesure) return;
    const e = enEtiquettes(this.options.length, mesure.scrollWidth, this.clientWidth);
    if (e !== this._etiquettes) {
      const avaitFocus = this.shadowRoot.activeElement !== null;
      this._etiquettes = e;
      this._dessiner();
      if (avaitFocus) { const f = this.shadowRoot.querySelector("[data-cible]"); if (f) f.focus(); }
    }
  }

  _dessiner() {
    const vue = this.shadowRoot.querySelector(".vue");
    if (!vue) return;
    const v = this.valeurs;
    const titre = echC(this.titre);
    if (this._etiquettes) {
      vue.innerHTML = `<div class="rangee" role="${this.multiple ? "group" : "radiogroup"}" aria-label="${titre}">${this._etiquettesHtml(false)}</div>`;
      const boutons = [...vue.querySelectorAll(".etq")];
      boutons.forEach((b, i) => {
        b.addEventListener("click", () => this._choisir(b.dataset.cle));
        // Flèches gauche / droite (télécommande, clavier) : d'une étiquette à l'autre.
        b.addEventListener("keydown", (ev) => {
          const pas = ev.key === "ArrowRight" ? 1 : ev.key === "ArrowLeft" ? -1 : 0;
          if (!pas) return;
          const n = boutons[i + pas];
          if (n) { ev.preventDefault(); n.focus(); }
        });
      });
    } else if (!this.multiple) {
      vue.innerHTML = `<select data-cible aria-label="${titre}">${v.length ? "" : `<option value="" selected disabled>${echC(tr("aucun"))}</option>`}${this.options.map((o) =>
        `<option value="${echC(o.cle)}" ${v.includes(String(o.cle)) ? "selected" : ""}>${echC(o.libelle)}</option>`).join("")}</select>`;
      vue.querySelector("select").addEventListener("change", (ev) => this._choisir(ev.target.value));
    } else {
      const libelles = this.options.filter((o) => v.includes(String(o.cle))).map((o) => o.libelle);
      const valeur = resume(libelles, tr("aucun"));
      vue.innerHTML = `<button type="button" class="champ" data-cible aria-expanded="${this._ouverte}" aria-label="${titre} : ${echC(valeur)}"><span>${echC(valeur)}</span><span aria-hidden="true">▾</span></button>
        ${this._ouverte ? `<div class="liste" role="group" aria-label="${titre}">${this.options.map((o) => `<label>
          <input type="checkbox" data-cle="${echC(o.cle)}" ${v.includes(String(o.cle)) ? "checked" : ""}>${this._icone(o)}<span>${echC(o.libelle)}</span></label>`).join("")}</div>` : ""}`;
      vue.querySelector(".champ").addEventListener("click", () => {
        this._ouverte = !this._ouverte; this._dessiner();
        const f = this.shadowRoot.querySelector(this._ouverte ? ".liste input" : ".champ"); if (f) f.focus();
      });
      vue.querySelectorAll(".liste input").forEach((c) => c.addEventListener("change", () => {
        this._choisir(c.dataset.cle);
        const f = [...this.shadowRoot.querySelectorAll("input")].find((x) => x.dataset.cle === c.dataset.cle); if (f) f.focus();
      }));
      const liste = vue.querySelector(".liste");
      if (liste) liste.addEventListener("keydown", (ev) => {
        if (ev.key === "Escape") { ev.stopPropagation(); this._ouverte = false; this._dessiner(); const f = this.shadowRoot.querySelector(".champ"); if (f) f.focus(); }
      });
    }
  }
}

if (!customElements.get("vision-choix")) customElements.define("vision-choix", VisionChoix);
