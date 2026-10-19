// Banc anti-débordement Vision (recette visuelle, règle permanente de l'auteur du 2026-10-11) — chaque carte Vision, dans
// un vrai navigateur (Edge ou Chrome sans affichage), à chaque taille d'écran, en thème clair et sombre, à police
// 100 % et 130 %, avec des données longues (noms longs, allemand). Pour chaque élément visible, un défaut si :
//   - une ellipse est réellement appliquée (text-overflow: ellipsis et scrollWidth > clientWidth) ;
//   - du texte est coupé (overflow caché et scrollWidth/scrollHeight > client, ou -webkit-line-clamp qui rogne) ;
//   - l'élément sort de la carte, de son parent ou de la fenêtre ; la page déborde à l'horizontale ;
//   - un texte ou un contrôle est à moins de 8 px d'un bord de la carte ;
//   - une liste déroulante n'affiche pas son choix en entier.
// Une carte qui ne lit rien par l'API (Planning : l'état de l'entité ; Processus) est prête après 2 s si elle a du texte.
// Un défilement horizontal voulu (overflow: auto) est noté en « avertissement », sans faire échouer.
//
// Police à 130 % : la WebView de l'appli Compagnon applique le réglage « taille du texte » d'Android par
// WebSettings.setTextZoom, qui multiplie la taille des TEXTES (pas des boîtes en px). On l'émule ainsi : une fois la
// carte dessinée, on lit la taille de police calculée de CHAQUE élément (document et racines fantômes), on remet à
// zéro toute mise à l'échelle précédente, puis on pose en ligne (!important) taille × 1,3 — et la hauteur de ligne
// × 1,3 quand elle est en px. Tout est calculé avant d'être appliqué (pas d'effet « em » cumulé), et refait après
// chaque changement d'onglet (les cartes redessinent leur contenu). Limite : les ::before/::after ne sont pas agrandis.
//
//   node debordement.mjs <dossier servi> <navigateur> <dossier des captures> [options JSON]
//   options : { "cartes": ["maison", …], "rapide": true, "budget_mo": 140 }
// Sortie : une ligne JSON par cas (carte, taille, police, thème, langue, défauts, avertissements), puis
// {"fin": true, "defauts": n, "cas": n, "captures_mo": x}. Code de sortie 1 s'il y a un défaut.
import { spawn, spawnSync } from "node:child_process";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import { join } from "node:path";
// Linux (CI) : Chromium refuse de démarrer sans bac à sable utilisable (conteneur, runner Ubuntu à espaces de noms
// restreints) ; /dev/shm y est petit. Sous Windows, rien ne change.
const OPTIONS_LINUX = process.platform === "linux" ? ["--no-sandbox", "--disable-dev-shm-usage"] : [];

const [, , DOSSIER, NAVIGATEUR, CAPTURES, OPTIONS_BRUTES] = process.argv;
const OPTIONS = JSON.parse(OPTIONS_BRUTES || "{}");
const TYPES = { ".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8", ".json": "application/json; charset=utf-8" };
const PORT_CDP = 9800 + Math.floor(Math.random() * 150);
const pause = (ms) => new Promise((r) => setTimeout(r, ms));
const BUDGET = (OPTIONS.budget_mo ?? 140) * 1024 * 1024;

// Les cartes (et le panneau d'appairage), avec leur configuration.
const CARTES = [
  { id: "maison", fichier: "vision-maison-card", el: "vision-maison-card", donnees: "maison", config: { mode: "maison", chemin: "/vision-maison" } },
  { id: "personne", fichier: "vision-maison-card", el: "vision-maison-card", donnees: "maison", config: { mode: "personne", personne: "person.lou", chemin: "/vision-maison" } },
  // 2.5.12 (Déverrouillage simplifié) : l'onglet Protection et sa ligne « Déverrouiller : 15 · 30 · 60 min » par appareil.
  { id: "protections", fichier: "vision-maison-card", el: "vision-maison-card", donnees: "maison", config: { mode: "protections", chemin: "/vision-maison" } },
  { id: "etiquettes", fichier: "vision-etiquettes-card", el: "vision-etiquettes-card", donnees: "catalogue", config: {} },
  { id: "messages", fichier: "vision-messages-card", el: "vision-messages-card", donnees: "messages", config: {} },
  { id: "planning", fichier: "vision-planning-card", el: "vision-planning-card", donnees: "planning", config: { entity: "__auto" } },
  { id: "processus", fichier: "vision-processus-card", el: "vision-processus-card", donnees: "processus", config: {} },
  { id: "apparence", fichier: "vision-apparence-card", el: "vision-apparence-card", donnees: "apparence", config: {} },
  { id: "dashboards", fichier: "vision-dashboards-card", el: "vision-dashboards-card", donnees: "dashboards", config: {} },
  { id: "widgets", fichier: "vision-widgets-card", el: "vision-widgets-card", donnees: "widgets", config: {} },
  { id: "veille", fichier: "vision-veille-card", el: "vision-veille-card", donnees: "veille", config: {} },
  // Refonte lot 3 : la fiche d'une personne, un cas par onglet (les cartes intégrées viennent avec leurs données).
  ...["resume", "horaires", "contenus", "reveils", "recompenses", "ecrans", "role"].map((o) => ({
    id: `fiche-${o}`, fichier: "vision-personne-card", el: "vision-personne-card", donnees: "fiche",
    config: { personne: "person.lou", chemin: "/vision-maison", onglet: o }, avec: ["vision-maison-card", "vision-planning-card", "vision-widgets-card"] })),
  // Refonte lot 5 : Réglages › Réservé (les trois parties dépliées).
  { id: "reglages", fichier: "vision-reglages-card", el: "vision-reglages-card", donnees: "reglages", config: { ouvertes: ["lieu", "fonctions", "droits"] } },
  // 2.5.6 : Réglages › Mes notifications (un administrateur : la liste déroulante des Décideurs, trois sujets × trois cases).
  { id: "canaux", fichier: "vision-canaux-card", el: "vision-canaux-card", donnees: "canaux", config: {} },
  // Refonte lot 4 : les écrans de l'assistant (maquette du dialogue de Home Assistant avec les vrais textes).
  ...["user", "personnes", "objectifs", "objectifs_erreur", "appareil", "pret", "menu", "reparer", "autres",
      "r_user", "r_personnes", "r_objectifs", "r_pret"].map((e) => ({
    id: `assistant-${e}`, fichier: "vision-flux-maquette", el: "vision-flux-maquette", donnees: "flux", config: { etape: e } })),
  { id: "appairage", fichier: "vision-appairage-panel", el: "vision-appairage-panel", donnees: "appairage", config: {}, panneau: true },
];
// Les tailles en px CSS (= dp : densité 1, pour des captures légères ; le rendu ne dépend que des px CSS).
const TAILLES = [
  { id: "360", l: 360, h: 780, mobile: true },
  { id: "412", l: 412, h: 915, mobile: true },
  { id: "800", l: 800, h: 1280, mobile: true },          // tablette portrait
  { id: "1280", l: 1280, h: 800, mobile: true },         // tablette paysage
  { id: "1280x720", l: 1280, h: 720, mobile: false },    // TV 720p
  { id: "1920x1080", l: 1920, h: 1080, mobile: false },  // TV 1080p
];
const POLICES = [100, 130];
const THEMES = ["clair", "sombre"];
// La matrice complète en allemand (la langue la plus longue) ; les cinq autres au cas le plus serré.
const LANGUE_PRINCIPALE = "de";
const AUTRES_LANGUES = ["fr", "en", "es", "it", "nl"];

function matrice() {
  const cartes = CARTES.filter((c) => !OPTIONS.cartes || OPTIONS.cartes.includes(c.id));
  const cas = [];
  for (const c of cartes) {
    const tailles = OPTIONS.rapide ? TAILLES.filter((t) => ["360", "1920x1080"].includes(t.id)) : TAILLES;
    const polices = OPTIONS.rapide ? [130] : POLICES;
    const themes = OPTIONS.rapide ? ["clair"] : THEMES;
    for (const t of tailles) for (const p of polices) for (const th of themes) cas.push({ carte: c, taille: t, police: p, theme: th, langue: LANGUE_PRINCIPALE });
    if (!OPTIONS.rapide) for (const lg of AUTRES_LANGUES) cas.push({ carte: c, taille: TAILLES[0], police: 130, theme: "clair", langue: lg });
  }
  return cas;
}

// ---------------------------------------------------------------------------------------------- dans la page
// Tout ce qui suit est évalué dans la page (pas de dépendance) : la racine, la police, les mesures.
const OUTILS = String.raw`
  const RACINE = () => { const c = window.__carte; return c && (c.shadowRoot || c); };
  // Tous les éléments, en descendant dans les racines fantômes (et les emplacements <slot>).
  const tousLesElements = (racine) => {
    const out = [];
    const visiter = (r) => {
      for (const e of r.querySelectorAll("*")) { out.push(e); if (e.shadowRoot) visiter(e.shadowRoot); }
    };
    visiter(racine);
    return out;
  };
  // Chaque élément une seule fois : <html> est aussi dans querySelectorAll("*"), et deux mesures le grandissaient deux fois
  // (l'ancienne taille, déjà agrandie, était alors gardée comme taille d'origine : la police enflait d'état en état).
  const toutLeDocument = () => [...new Set([document.documentElement, ...tousLesElements(document)])];
  // Police à 130 % (voir l'en-tête) : remise à zéro, mesure de tout, puis application.
  window.__police = (facteur) => {
    const els = toutLeDocument();
    for (const e of els) if (e.__fsInit) {
      e.style.setProperty("font-size", e.__fsInit.fs, e.__fsInit.fsp); e.style.setProperty("line-height", e.__fsInit.lh, e.__fsInit.lhp);
      if (!e.__fsInit.fs) e.style.removeProperty("font-size"); if (!e.__fsInit.lh) e.style.removeProperty("line-height");
      delete e.__fsInit;
    }
    if (facteur === 1) return 0;
    const mesures = els.map((e) => { const s = getComputedStyle(e); return [e, parseFloat(s.fontSize), s.lineHeight]; });
    for (const [e, fs, lh] of mesures) {
      if (!e.style || !(fs > 0)) continue;
      if (!e.__fsInit) e.__fsInit = { fs: e.style.getPropertyValue("font-size"), fsp: e.style.getPropertyPriority("font-size"),
                     lh: e.style.getPropertyValue("line-height"), lhp: e.style.getPropertyPriority("line-height") };
      e.style.setProperty("font-size", (fs * facteur).toFixed(2) + "px", "important");
      if (/px$/.test(lh)) e.style.setProperty("line-height", (parseFloat(lh) * facteur).toFixed(2) + "px", "important");
    }
    return mesures.length;
  };
  // Un sélecteur lisible : jusqu'à 3 ancêtres, balise, #id, deux classes, un attribut data-*.
  const nomDe = (e) => {
    if (!e || !e.tagName) return "?";
    let s = e.tagName.toLowerCase();
    if (e.id) s += "#" + e.id;
    const cl = [...(e.classList || [])].slice(0, 2); if (cl.length) s += "." + cl.join(".");
    const d = [...(e.attributes || [])].find((a) => a.name.startsWith("data-"));
    if (d) s += "[" + d.name + (d.value && d.value.length < 30 ? '="' + d.value + '"' : "") + "]";
    return s;
  };
  const parentDe = (e) => e.parentElement || (e.getRootNode() && e.getRootNode().host) || null;
  const selecteur = (e) => {
    const l = []; let x = e;
    for (let i = 0; i < 4 && x && x !== window.__carte; i++) { l.unshift(nomDe(x)); x = parentDe(x); }
    return l.join(" > ");
  };
  const texteDe = (e) => (e.innerText || e.value || e.getAttribute?.("aria-label") || "").replace(/\s+/g, " ").trim().slice(0, 60);
  const CONTROLES = 'button, input:not([type="hidden"]), select, textarea, a[href], summary, [role="button"], [role="switch"], [role="checkbox"], [tabindex="0"]';
  const visible = (e) => {
    if (!e.getClientRects().length) return false;
    const s = getComputedStyle(e);
    if (s.visibility !== "visible" || parseFloat(s.opacity) === 0) return false;
    const r = e.getBoundingClientRect();
    return r.width > 1 && r.height > 1;  // les « visually-hidden » (1 px) ne comptent pas
  };
  const aDuTexte = (e) => [...e.childNodes].some((n) => n.nodeType === 3 && n.textContent.trim());
  const aDuTexteDessous = (e) => !!(e.textContent || "").trim();
  // L'ancêtre qui défile à l'horizontale (dans la carte), s'il y en a un.
  const defileur = (e, carte) => {
    for (let x = parentDe(e); x && x !== carte && x !== document.body; x = parentDe(x)) {
      const s = getComputedStyle(x);
      if (/(auto|scroll)/.test(s.overflowX) && x.scrollWidth > x.clientWidth + 1) return x;
    }
    return null;
  };
  // La largeur du texte affiché par une liste déroulante.
  const mesureTexte = (txt, s) => {
    const c = window.__toile || (window.__toile = document.createElement("canvas").getContext("2d"));
    c.font = s.fontStyle + " " + s.fontWeight + " " + s.fontSize + " " + s.fontFamily;
    return c.measureText(txt).width;
  };

  window.__verifier = (panneau) => {
    const defauts = [], avertissements = [];
    const racine = RACINE();
    if (!racine) return { defauts: [{ selecteur: "(carte)", defaut: "carte absente" }], avertissements };
    const carteEl = panneau ? window.__carte : (racine.querySelector("ha-card") || window.__carte);
    const C = carteEl.getBoundingClientRect();
    const W = document.documentElement.clientWidth;
    const ajouter = (liste, e, defaut) => liste.push({ selecteur: selecteur(e), defaut, texte: texteDe(e) });
    if (document.documentElement.scrollWidth > W + 1) defauts.push({ selecteur: "(page)", defaut: "la page défile à l'horizontale : " + document.documentElement.scrollWidth + " px pour " + W, texte: "" });
    if (C.right > W + 0.5 || C.left < -0.5) defauts.push({ selecteur: nomDe(carteEl), defaut: "la carte sort de la fenêtre", texte: "" });
    for (const e of tousLesElements(racine)) {
      if (/^(STYLE|SCRIPT|TEMPLATE|LINK|BR|svg|path|g|circle|rect|line|polyline|polygon|use|defs|text|tspan)$/i.test(e.tagName)) continue;
      if (!visible(e)) continue;
      if (e.closest && e.closest("dialog:not([open])")) continue;
      const s = getComputedStyle(e);
      const r = e.getBoundingClientRect();
      const texte = aDuTexteDessous(e);
      // 1. Ellipse réellement appliquée.
      // Texte entier dit ailleurs (attribut data-texte-complet, et aria-label de l'option) : ellipse ou 2 lignes admises.
      const complet = e.hasAttribute && e.hasAttribute("data-texte-complet") && e.getAttribute("data-texte-complet") === (e.textContent || "").trim();
      if (complet) { /* admis */ }
      else if (s.textOverflow === "ellipsis" && e.scrollWidth > e.clientWidth + 1) ajouter(defauts, e, "ellipse appliquée (" + e.scrollWidth + " px de texte pour " + e.clientWidth + ")");
      // 2. Texte rogné : à droite, en bas, ou par line-clamp.
      else if (texte && /(hidden|clip)/.test(s.overflowX) && e.scrollWidth > e.clientWidth + 1 && e.tagName !== "INPUT" && e.tagName !== "TEXTAREA")
        ajouter(defauts, e, "texte coupé à droite (" + e.scrollWidth + " px pour " + e.clientWidth + ")");
      const clamp = s.webkitLineClamp || s.getPropertyValue("-webkit-line-clamp");
      if (complet) { /* admis */ }
      else if (texte && clamp && clamp !== "none" && e.scrollHeight > e.clientHeight + 1) ajouter(defauts, e, "lignes coupées (line-clamp " + clamp + ")");
      else if (texte && /(hidden|clip)/.test(s.overflowY) && e.scrollHeight > e.clientHeight + 2 && e.tagName !== "TEXTAREA")
        ajouter(defauts, e, "texte coupé en bas (" + e.scrollHeight + " px de contenu pour " + e.clientHeight + ")");
      // 3. Défilement horizontal voulu : noté, sans échec.
      if (/(auto|scroll)/.test(s.overflowX) && e.scrollWidth > e.clientWidth + 1 && e.tagName !== "TEXTAREA" && e.tagName !== "INPUT")
        ajouter(avertissements, e, "défile à l'horizontale (" + e.scrollWidth + " px pour " + e.clientWidth + ")");
      // 4. Hors de la carte, de la fenêtre, du parent (sauf dans un bloc qui défile).
      const defile = defileur(e, carteEl);
      if (!defile) {
        if (r.right > W + 0.5 || r.left < -0.5) ajouter(defauts, e, "sort de la fenêtre (" + Math.round(r.left) + "→" + Math.round(r.right) + " px, fenêtre " + W + ")");
        else if (e !== carteEl && (r.right > C.right + 0.5 || r.left < C.left - 0.5)) ajouter(defauts, e, "sort de la carte de " + Math.round(Math.max(r.right - C.right, C.left - r.left)) + " px");
        else {
          const p = parentDe(e);
          if (p && p !== carteEl && p.getBoundingClientRect && !/^(absolute|fixed)$/.test(s.position)) {
            const P = p.getBoundingClientRect();
            const sp = getComputedStyle(p);
            if (sp.display !== "contents" && P.width > 0 && (r.right > P.right + 1 || r.left < P.left - 1))
              ajouter(defauts, e, "sort de son parent " + nomDe(p) + " de " + Math.round(Math.max(r.right - P.right, P.left - r.left)) + " px");
          }
        }
      }
      // 5. Marge de 8 px : les contrôles (leur boîte), les textes (la boîte du texte lui-même).
      // Une liste flottante (role=listbox) peut dépasser le bas de la carte : seules les marges gauche et droite comptent.
      const flottant = e.closest && e.closest('[role="listbox"]');
      const bords = (q) => flottant ? Math.min(q.left - C.left, C.right - q.right) : Math.min(q.left - C.left, C.right - q.right, q.top - C.top, C.bottom - q.bottom);
      if (!defile && e.matches(CONTROLES)) {
        const m = bords(r);
        if (m < 8 - 0.5) ajouter(defauts, e, "contrôle à " + Math.max(0, Math.round(m)) + " px du bord de la carte");
      }
      // Texte coupé exprès (texte complet dit) : la marge se mesure sur sa boîte visible, pas sur le texte masqué.
      if (!defile && complet) { const m = bords(r); if (m < 8 - 0.5) ajouter(defauts, e, "texte à " + Math.max(0, Math.round(m)) + " px du bord de la carte"); }
      else if (!defile && aDuTexte(e) && !e.matches(CONTROLES)) {
        for (const n of e.childNodes) {
          if (n.nodeType !== 3 || !n.textContent.trim()) continue;
          const rg = document.createRange(); rg.selectNodeContents(n);
          const m = Math.min(...[...rg.getClientRects()].filter((q) => q.width > 0).map(bords));
          if (m < 8 - 0.5) { ajouter(defauts, e, "texte à " + Math.max(0, Math.round(m)) + " px du bord de la carte"); break; }
        }
      }
      // 6. Liste déroulante : le choix affiché tient-il (flèche comptée 24 px) ?
      if (e.tagName === "SELECT" && e.selectedOptions && e.selectedOptions[0]) {
        const t = e.selectedOptions[0].text;
        const place = e.clientWidth - parseFloat(s.paddingLeft) - parseFloat(s.paddingRight) - 24;
        if (mesureTexte(t, s) > place + 1) ajouter(defauts, e, "liste déroulante : « " + t.slice(0, 40) + " » ne tient pas (" + Math.round(mesureTexte(t, s)) + " px pour " + Math.round(place) + ")");
      }
      if ((e.tagName === "INPUT" || e.tagName === "TEXTAREA") && e.placeholder && !e.value && mesureTexte(e.placeholder, s) > e.clientWidth - parseFloat(s.paddingLeft) - parseFloat(s.paddingRight) + 1)
        ajouter(avertissements, e, "indication du champ coupée : « " + e.placeholder.slice(0, 40) + " »");
    }
    // Un défaut par élément et par genre (les doublons d'une même cause s'additionnent sinon).
    const uniques = (l) => { const vus = new Set(); return l.filter((d) => { const k = d.selecteur + "|" + d.defaut.replace(/[0-9→]+/g, "#"); if (vus.has(k)) return false; vus.add(k); return true; }); };
    return { defauts: uniques(defauts).slice(0, 60), avertissements: uniques(avertissements).slice(0, 20) };
  };
  // Les états à parcourir : les <details> ouverts, puis chaque onglet (data-vue, data-onglet, .onglet).
  // 2.3.8 : une liste de suggestions (champ role=combobox) ouverte, comme si l'on tapait une lettre.
  window.__ouvrirListe = () => {
    const i = tousLesElements(RACINE()).find((x) => x.tagName === "INPUT" && x.getAttribute("role") === "combobox");
    if (!i) return false;
    i.focus(); i.value = "a"; i.dispatchEvent(new Event("input", { bubbles: true }));
    return true;
  };
  window.__ouvrirReglage = () => {
    const b = tousLesElements(RACINE()).find((x) => x.tagName === "BUTTON" && x.hasAttribute("data-regler"));
    if (!b) return false;
    b.click();
    return true;
  };
  // 2.5.1 : l'écran par élément : la première catégorie dépliée, son premier logiciel déplié, le choix du mode de
  // sa première ligne ouvert ; puis la recherche.
  window.__ouvrirElements = (etape) => {
    const r = RACINE();
    const sel = ["button.ouvrir[data-cat]", "button[data-el-deplier]", "button[data-el-mode]"][etape];
    const b = sel && tousLesElements(r).find((x) => x.matches && x.matches(sel) && !x.disabled);
    if (!b) return false;
    b.click();
    return true;
  };
  window.__chercherElements = () => {
    const i = tousLesElements(RACINE()).find((x) => x.tagName === "INPUT" && x.hasAttribute("data-el-recherche"));
    if (!i) return false;
    i.value = "minecraft"; i.dispatchEvent(new Event("input", { bubbles: true }));
    return true;
  };
  window.__ouvrirDetails = () => { let n = 0; for (const d of tousLesElements(RACINE())) if (d.tagName === "DETAILS" && !d.open) { d.open = true; n++; } return n; };
  window.__onglets = () => tousLesElements(RACINE()).filter((b) => b.matches("button[data-vue], button[data-onglet], button.onglet, [role=tab]") && visible(b)).map((b) => nomDe(b));
  window.__cliquerOnglet = (i) => { const l = tousLesElements(RACINE()).filter((b) => b.matches("button[data-vue], button[data-onglet], button.onglet, [role=tab]") && visible(b)); if (l[i]) { l[i].click(); return nomDe(l[i]); } return null; };
`;

async function main() {
  const serveur = createServer((req, res) => {
    const chemin = decodeURIComponent(new URL(req.url, "http://x").pathname).replace(/^\/+/, "");
    if (chemin.includes("..")) { res.writeHead(403); res.end(); return; }
    let contenu;
    try { contenu = readFileSync(join(DOSSIER, chemin)); } catch { res.writeHead(404); res.end(); return; }
    res.writeHead(200, { "Content-Type": TYPES[chemin.slice(chemin.lastIndexOf("."))] || "application/octet-stream" });
    res.end(contenu);
  });
  await new Promise((ok) => serveur.listen(0, "127.0.0.1", ok));
  const PORT_HTTP = serveur.address().port;
  if (CAPTURES && !existsSync(CAPTURES)) mkdirSync(CAPTURES, { recursive: true });
  const profil = mkdtempSync(join(tmpdir(), "vision-debordement-"));
  // Un seul navigateur, sobre : pas d'extensions, pas de GPU, un seul processus de rendu.
  const nav = spawn(NAVIGATEUR, ["--headless=new", "--disable-gpu", ...OPTIONS_LINUX, "--disable-extensions", "--renderer-process-limit=1",
    "--disable-features=Translate,OptimizationHints,MediaRouter", "--no-first-run", "--mute-audio", "--hide-scrollbars",
    `--user-data-dir=${profil}`, `--remote-debugging-port=${PORT_CDP}`, "--window-size=1920,1080", "about:blank"], { stdio: "ignore" });
  let totalDefauts = 0, nCas = 0, octets = 0, capturesSautees = 0;
  try {
    let page = null;
    for (let i = 0; i < 100 && !page; i++) {
      try { page = (await (await fetch(`http://127.0.0.1:${PORT_CDP}/json`)).json()).find((c) => c.type === "page"); } catch { await pause(200); }
    }
    if (!page) throw new Error("navigateur injoignable");
    const ws = new WebSocket(page.webSocketDebuggerUrl);
    await new Promise((ok, ko) => { ws.onopen = ok; ws.onerror = ko; });
    let n = 0; const attente = new Map(); const exceptions = [];
    ws.onmessage = (m) => {
      const r = JSON.parse(m.data);
      if (r.method === "Runtime.exceptionThrown") exceptions.push(String(r.params.exceptionDetails?.exception?.description || r.params.exceptionDetails?.text).slice(0, 200));
      if (r.id && attente.has(r.id)) { attente.get(r.id)(r); attente.delete(r.id); }
    };
    const cmd = (method, params = {}) => new Promise((ok) => { const id = ++n; attente.set(id, ok); ws.send(JSON.stringify({ id, method, params })); });
    const evaluer = async (js) => {
      const r = await cmd("Runtime.evaluate", { expression: `(async () => { ${OUTILS}; return (${js}); })()`, awaitPromise: true, returnByValue: true });
      if (r.result?.exceptionDetails) throw new Error(String(r.result.exceptionDetails.exception?.description || r.result.exceptionDetails.text).slice(0, 300));
      return r.result?.result?.value;
    };
    const stable = async () => { await evaluer(`new Promise((ok) => requestAnimationFrame(() => requestAnimationFrame(ok)))`); await pause(120); };
    await cmd("Runtime.enable"); await cmd("Page.enable");
    for (const cas of matrice()) {
      const { carte: c, taille: t, police, theme, langue } = cas;
      const res = { carte: c.id, taille: t.id, police, theme, langue, defauts: [], avertissements: [], etats: [] };
      try {
        exceptions.length = 0;
        await cmd("Emulation.setDeviceMetricsOverride", { width: t.l, height: t.h, deviceScaleFactor: 1, mobile: t.mobile });
        const config = { ...c.config, ...(c.panneau ? { narrow: t.l < 870 } : {}) };
        const url = `http://127.0.0.1:${PORT_HTTP}/debordement.html?carte=${c.el}&fichier=${c.fichier}&donnees=${c.donnees}`
          + `&langue=${langue}&theme=${theme}&config=${encodeURIComponent(JSON.stringify(config))}`
          + (c.avec ? `&avec=${c.avec.join(",")}` : "");
        await cmd("Page.navigate", { url });
        let pret = false;
        for (let i = 0; i < 80 && !pret; i++) {
          await pause(100);
          pret = await evaluer(`!!(window.__pret && RACINE() && window.__lectures.en_cours === 0 && (window.__lectures.faites > 0 || ${i} > 20)
            && tousLesElements(RACINE()).some((e) => e.tagName !== "STYLE" && (e.innerText || "").trim()))`).catch(() => false);
        }
        if (!pret) throw new Error("carte non affichée");
        await stable();
        // État 1 : la carte telle qu'elle s'ouvre, <details> compris (ouverts).
        const verifierEtat = async (nom) => {
          await evaluer(`window.__police(${police / 100})`);
          await stable();
          const v = await evaluer(`window.__verifier(${!!c.panneau})`);
          for (const d of v.defauts) res.defauts.push({ etat: nom, ...d });
          for (const d of v.avertissements) res.avertissements.push({ etat: nom, ...d });
          res.etats.push(nom);
        };
        await evaluer(`window.__ouvrirDetails()`);
        await verifierEtat("ouverture");
        // Capture : la page entière (la carte et ses 8 px), PNG, plafonnée à 4000 px de haut et au budget.
        if (CAPTURES) {
          const haut = Math.min(4000, await evaluer(`Math.ceil(document.documentElement.scrollHeight)`));
          const nomFichier = `recette-lot1-ha-${c.id}-${t.id}-${police}-${theme}${langue === LANGUE_PRINCIPALE ? "" : "-" + langue}.png`;
          if (octets < BUDGET) {
            const r = await cmd("Page.captureScreenshot", { format: "png", captureBeyondViewport: true, clip: { x: 0, y: 0, width: t.l, height: haut, scale: 1 } });
            if (r.result?.data) {
              const b = Buffer.from(r.result.data, "base64");
              writeFileSync(join(CAPTURES, nomFichier), b);
              octets += b.length;
              res.capture = nomFichier;
            }
          } else capturesSautees++;
        }
        // États suivants : chaque onglet.
        if (await evaluer(`window.__ouvrirListe()`)) { await stable(); await verifierEtat("suggestions"); }
        // 2.4.0 : le panneau « Régler » d'une entité (carte Widgets HA), ouvert.
        if (await evaluer(`window.__ouvrirReglage()`)) { await stable(); await evaluer(`window.__ouvrirDetails()`); await verifierEtat("reglage"); }
        // 2.5.1 : l'écran par élément (carte d'une personne).
        let elements = false;
        for (let etape = 0; etape < 3; etape++) {
          if (!(await evaluer(`window.__ouvrirElements(${etape})`))) break;
          await pause(400); await stable(); elements = true;
        }
        if (elements) {
          await verifierEtat("par élément");
          // Capture de cet état aussi (recette de l'auteur : l'écran par élément se regarde, pas seulement se mesure).
          if (CAPTURES && octets < BUDGET) {
            const haut = Math.min(4000, await evaluer(`Math.ceil(document.documentElement.scrollHeight)`));
            const r = await cmd("Page.captureScreenshot", { format: "png", captureBeyondViewport: true, clip: { x: 0, y: 0, width: t.l, height: haut, scale: 1 } });
            if (r.result?.data) {
              const b = Buffer.from(r.result.data, "base64");
              writeFileSync(join(CAPTURES, `recette-lot1-ha-${c.id}-elements-${t.id}-${police}-${theme}${langue === LANGUE_PRINCIPALE ? "" : "-" + langue}.png`), b);
              octets += b.length;
            }
          }
        }
        if (await evaluer(`window.__chercherElements()`)) { await pause(900); await stable(); await verifierEtat("recherche"); }
        const onglets = await evaluer(`window.__onglets()`);
        for (let i = 0; i < onglets.length && i < 8; i++) {
          const nom = await evaluer(`window.__cliquerOnglet(${i})`);
          if (!nom) continue;
          await stable();
          await evaluer(`window.__ouvrirDetails()`);
          await verifierEtat("onglet " + nom);
        }
        if (exceptions.length) res.defauts.push({ etat: "-", selecteur: "(script)", defaut: "exception : " + exceptions[0], texte: "" });
      } catch (e) {
        res.defauts.push({ etat: "-", selecteur: "(banc)", defaut: String(e.message || e), texte: "" });
      }
      // Un même défaut vu dans plusieurs états : une seule fois.
      const vus = new Set();
      res.defauts = res.defauts.filter((d) => { const k = d.selecteur + "|" + d.defaut; if (vus.has(k)) return false; vus.add(k); return true; });
      totalDefauts += res.defauts.length; nCas++;
      console.log(JSON.stringify(res));
    }
    ws.close();
  } finally {
    serveur.close();
    // Tout l'arbre du navigateur (le nôtre seulement : repéré par son profil).
    if (process.platform === "win32") {
      spawnSync("taskkill", ["/PID", String(nav.pid), "/T", "/F"], { stdio: "ignore" });
      spawnSync("powershell", ["-NoProfile", "-NonInteractive", "-Command",
        `Get-CimInstance Win32_Process -Filter "Name = 'msedge.exe' OR Name = 'chrome.exe'" | Where-Object { $_.CommandLine -like '*${String(profil).split(/[\\/]/).pop()}*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }`], { stdio: "ignore" });
    } else nav.kill();
    await pause(300);
    try { rmSync(profil, { recursive: true, force: true }); } catch { /* profil encore verrouillé un instant */ }
  }
  console.log(JSON.stringify({ fin: true, defauts: totalDefauts, cas: nCas, captures_mo: +(octets / 1048576).toFixed(1), captures_sautees: capturesSautees }));
  process.exit(totalDefauts ? 1 : 0);
}

main().catch((e) => { console.log(JSON.stringify({ fin: true, defauts: 1, erreur: String(e) })); process.exit(1); });
