// Banc anti-débordement, refonte UX lot 4 : une maquette du dialogue d'un assistant de Home Assistant (titre,
// description en Markdown simple, champs), avec les textes et les valeurs que Vision donne vraiment
// (translations/<langue>.json et les description_placeholders de chaque étape, préparés par debordement_test.py).
// Ce n'est pas le rendu de Home Assistant lui-même : il vérifie que nos textes passent à la ligne sans rien couper,
// dans une boîte de la largeur du dialogue (plein écran sur téléphone, 560 px au plus ailleurs).
const echapper = (t) => String(t ?? "").replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);

function markdown(texte) {
  const blocs = [];
  let liste = [];
  const fin = () => { if (liste.length) { blocs.push(`<ul>${liste.join("")}</ul>`); liste = []; } };
  const ligne = (t) => echapper(t).replace(/\*\*(.+?)\*\*/g, "<b>$1</b>").replace(/\*(.+?)\*/g, "<i>$1</i>")
    .replace(/`(.+?)`/g, "<code>$1</code>");
  for (const l of String(texte || "").split("\n")) {
    if (/^\s*-\s+/.test(l)) liste.push(`<li>${ligne(l.replace(/^\s*-\s+/, ""))}</li>`);
    else { fin(); if (l.trim()) blocs.push(`<p>${ligne(l)}</p>`); }
  }
  fin();
  return blocs.join("");
}

class VisionFluxMaquette extends HTMLElement {
  setConfig(config) { this._config = config; }
  set hass(hass) {
    if (this._hass) return;
    this._hass = hass;
    hass.callApi("GET", "flux").then((d) => this._rendre(d.etapes[this._config.etape]));
  }
  _champ(c) {
    const aide = c.aide ? `<div class="aide">${echapper(c.aide)}</div>` : "";
    if (c.type === "texte") return `<label class="texte"><span>${echapper(c.label)}</span><input value="${echapper(c.valeur || "")}"></label>${aide}`;
    if (c.type === "section") {
      return `<details open class="section"><summary>${echapper(c.label)}</summary>${markdown(c.description)}
        ${c.champs.map((s) => this._champ(s)).join("")}</details>`;
    }
    if (c.type === "personnes") {
      return `<div class="groupe"><span class="titre">${echapper(c.label)}</span><div class="puces">${
        c.valeurs.map((v) => `<span class="puce">${echapper(v)}</span>`).join("")}</div></div>${aide}`;
    }
    if (c.type === "menu") return `<div class="menu">${c.options.map((o) => `<div class="ligne"><span>${echapper(o)}</span><span class="chevron">›</span></div>`).join("")}</div>`;
    const genre = c.type === "cases" ? "checkbox" : "radio";
    return `<div class="groupe"><span class="titre">${echapper(c.label)}</span>${c.options.map((o, i) =>
      `<label class="choix"><input type="${genre}" name="${echapper(c.label)}" ${i === 0 || c.type === "cases" ? "checked" : ""}><span>${echapper(o)}</span></label>`).join("")}</div>${aide}`;
  }
  _rendre(e) {
    this.innerHTML = `<style>
      vision-flux-maquette { display:block; max-width:560px; margin:0 auto; }
      vision-flux-maquette .dialogue { padding:24px; overflow-wrap:break-word; }
      vision-flux-maquette h2 { font-size:22px; font-weight:400; margin:0 0 16px; }
      vision-flux-maquette p, vision-flux-maquette li { margin:0 0 8px; line-height:1.45; }
      vision-flux-maquette ul { margin:0 0 8px; padding-left:20px; }
      vision-flux-maquette .erreur { background:rgba(219,68,55,.12); color:var(--error-color); padding:8px 12px; border-radius:4px; margin-bottom:12px; }
      vision-flux-maquette label.texte { display:flex; flex-direction:column; gap:4px; margin:12px 0 4px; }
      vision-flux-maquette label.texte span { font-size:12px; color:var(--secondary-text-color); }
      vision-flux-maquette input:not([type]) { font:inherit; padding:12px; border:1px solid var(--divider-color); border-radius:4px;
        background:transparent; color:inherit; min-width:0; }
      vision-flux-maquette .aide { font-size:12px; color:var(--secondary-text-color); margin:0 0 8px 0; }
      vision-flux-maquette .section { border:1px solid var(--divider-color); border-radius:8px; padding:8px 12px; margin:12px 0; }
      vision-flux-maquette summary { font-weight:500; padding:4px 0 8px; }
      vision-flux-maquette .groupe { margin:12px 0 4px; }
      vision-flux-maquette .titre { display:block; font-weight:500; margin-bottom:4px; }
      vision-flux-maquette .choix { display:flex; align-items:flex-start; gap:8px; padding:8px 0; min-height:24px; }
      vision-flux-maquette .choix input { flex:none; margin:2px 0 0; width:20px; height:20px; }
      vision-flux-maquette .choix span { min-width:0; }
      vision-flux-maquette .puces { display:flex; flex-wrap:wrap; gap:8px; }
      vision-flux-maquette .puce { border:1px solid var(--divider-color); border-radius:16px; padding:4px 12px; max-width:100%; box-sizing:border-box; }
      vision-flux-maquette .menu .ligne { display:flex; align-items:center; gap:12px; padding:12px 0; min-height:24px;
        border-bottom:1px solid var(--divider-color); }
      vision-flux-maquette .menu .ligne span:first-child { flex:1; min-width:0; }
      vision-flux-maquette .chevron { flex:none; }
      vision-flux-maquette .boutons { display:flex; justify-content:flex-end; gap:8px; margin-top:16px; }
      vision-flux-maquette button { font:inherit; color:var(--primary-color); background:none; border:none; padding:8px 12px; min-height:36px; }
    </style><ha-card><div class="dialogue">
      <h2>${echapper(e.titre)}</h2>
      ${e.erreur ? `<div class="erreur">${echapper(e.erreur)}</div>` : ""}
      ${markdown(e.description)}
      ${(e.champs || []).map((c) => this._champ(c)).join("")}
      <div class="boutons"><button>${echapper(e.bouton)}</button></div>
    </div></ha-card>`;
  }
}
customElements.define("vision-flux-maquette", VisionFluxMaquette);
