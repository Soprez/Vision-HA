/*
 * Vision — porte du panneau de l'écran de veille. Elle existe tout de suite pour
 * Home Assistant, puis charge le panneau lui-même (vision-veille-coeur.js, à côté
 * d'elle dans les fichiers de l'intégration) sous
 * une adresse qui change toutes les dix minutes : une correction arrive ainsi sur
 * les appareils sans redémarrer Home Assistant ni vider le cache des navigateurs.
 * 2.2.13 : l'adresse du cœur porte aussi la version de la porte (son empreinte couvre le cœur,
 * cartes.py) : un cœur mis à jour est repris tout de suite, pas au plus tard dix minutes après.
 */
const VERSION_PORTE = new URL(import.meta.url).searchParams.get("v") || "";
class VisionVeillePorte extends HTMLElement {
  set hass(hass) {
    this._hass = hass;
    if (this._coeur) { this._coeur.hass = hass; return; }
    if (this._charge) return;
    this._charge = true;
    import(new URL(`vision-veille-coeur.js?v=${VERSION_PORTE}.${Math.floor(Date.now() / 600000)}`, import.meta.url).href).then(() => {
      this._coeur = document.createElement("vision-veille-coeur");
      this.appendChild(this._coeur);
      this._coeur.hass = this._hass;
    }).catch(() => { this._charge = false; });  // 2.2.13 : chargement raté (réseau, redémarrage) : repris au prochain hass
  }
  set narrow(v) {} set route(v) {} set panel(v) {}
}
if (!customElements.get("vision-veille-panel")) customElements.define("vision-veille-panel", VisionVeillePorte);
