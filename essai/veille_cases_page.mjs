// Vision (veille-lot) — la page de veille avec les cases typées, rendue sans écran (Edge) avec des données fictives.
//
//   node essai/veille_cases_page.mjs <dossier de sortie>
//
// Un petit serveur local (dans ce processus) sert une page d'essai qui monte le vrai panneau (vision-veille-coeur.js)
// avec un faux Home Assistant : un tableau qui a chaque genre de case typée (météo, agenda, image, radar, caméra,
// derniers passages, iframe ×2) et deux cartes ordinaires ; et le même tableau sans « typees » (Home Assistant
// ancien : rendu d'avant). Pour chaque cas (sombre / beige, avec / sans pastille) : une capture, les vérifications
// (grille au pixel, images en POST avec le ticket seulement, une seule iframe, bac à sable), et le temps de script par
// seconde au repos. Une planche réunit les captures. Un seul Edge, fermé à la fin (arbre entier).
import { spawn, spawnSync } from "node:child_process";
import { createServer } from "node:http";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync, existsSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const ICI = dirname(fileURLToPath(import.meta.url));
const COEUR = join(ICI, "..", "pc_parental", "frontend", "vision-veille-coeur.js");
const SORTIE = process.argv[2] || join(tmpdir(), "vision-veille-cases");
mkdirSync(SORTIE, { recursive: true });
const pause = (ms) => new Promise((r) => setTimeout(r, ms));
const edge = ["C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe", "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe"].find((n) => existsSync(n));
const TICKET = "vv1.tele1." + (Math.floor(Date.now() / 1000) + 86000) + "." + "ab".repeat(20);
const CLE = (n) => n.toString(16).padStart(24, "0");
const MEDIA = (n) => `/api/pc_parental/veille/media/${CLE(n)}`;

// --- Les images fictives (SVG), une par clé ---------------------------------------------------------------------
const svg = (l, h, corps) => `<svg xmlns="http://www.w3.org/2000/svg" width="${l}" height="${h}" viewBox="0 0 ${l} ${h}">${corps}</svg>`;
const IMAGES = {
  1: svg(800, 500, `<rect width="800" height="500" fill="#e9e4da"/><g fill="none" stroke="#6b6358" stroke-width="8"><rect x="20" y="20" width="760" height="460"/><path d="M 340 20 V 260 H 20 M 340 330 V 480 M 560 20 V 480 M 560 250 H 780"/></g><g font-family="Segoe UI" font-size="22" fill="#8a8173"><text x="60" y="60">Salon</text><text x="380" y="60">Cuisine</text><text x="600" y="60">Chambre</text></g>`),
  2: svg(600, 600, `<rect width="600" height="600" fill="#cfd8c4"/><path d="M 0 380 C 150 330 260 420 600 300" stroke="#8fb0d0" stroke-width="26" fill="none"/><g stroke="#a19c8e" stroke-width="3" fill="none"><path d="M 80 0 L 220 600 M 0 200 L 600 140 M 400 0 L 330 600"/></g><circle cx="300" cy="300" r="7" fill="#c03"/>`),
  3: svg(600, 600, `<g opacity=".72"><ellipse cx="200" cy="230" rx="140" ry="80" fill="#3a8fe0"/><ellipse cx="230" cy="240" rx="70" ry="38" fill="#1bb24a"/><ellipse cx="420" cy="420" rx="110" ry="60" fill="#3a8fe0"/><ellipse cx="430" cy="420" rx="40" ry="22" fill="#e8c21a"/></g>`),
  4: svg(1280, 720, `<defs><linearGradient id="c" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#8fb3d9"/><stop offset=".55" stop-color="#cfdbe6"/><stop offset=".56" stop-color="#6f8f5a"/><stop offset="1" stop-color="#4b6b3c"/></linearGradient></defs><rect width="1280" height="720" fill="url(#c)"/><rect x="760" y="250" width="360" height="260" fill="#b9a58c"/><path d="M 740 260 L 940 120 L 1140 260 Z" fill="#8a4b3c"/><rect x="900" y="380" width="70" height="130" fill="#5b3a2c"/><text x="30" y="60" font-family="Consolas" font-size="34" fill="#fff">JARDIN 2026-10-11 09:41:07</text>`),
  5: svg(640, 360, `<rect width="640" height="360" fill="#55606b"/><rect x="0" y="230" width="640" height="130" fill="#3d4650"/><circle cx="320" cy="150" r="36" fill="#d8b89a"/><rect x="280" y="190" width="80" height="140" rx="20" fill="#a33"/>`),
  6: svg(640, 360, `<rect width="640" height="360" fill="#6b7a5a"/><rect x="140" y="170" width="360" height="120" rx="30" fill="#2c4a7a"/><circle cx="220" cy="300" r="34" fill="#222"/><circle cx="430" cy="300" r="34" fill="#222"/>`),
  7: svg(640, 360, `<rect width="640" height="360" fill="#7d8a6a"/><ellipse cx="320" cy="230" rx="120" ry="60" fill="#c8a070"/><circle cx="430" cy="190" r="40" fill="#c8a070"/>`),
  8: svg(640, 360, `<rect width="640" height="360" fill="#4a5560"/><rect x="250" y="120" width="140" height="120" fill="#c9a36b"/><path d="M 250 160 H 390" stroke="#8a6a3a" stroke-width="10"/>`),
  9: svg(320, 180, `<rect width="320" height="180" fill="#20262c"/><circle cx="160" cy="90" r="40" fill="#5d7fa3"/><text x="10" y="24" font-family="Consolas" font-size="18" fill="#fff">ENTRÉE</text>`),
};
const requetes = [];
const serveur = createServer((req, res) => {
  const u = new URL(req.url, "http://x");
  if (u.pathname === "/essai.html") { res.writeHead(200, { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store" }); res.end(PAGE); return; }
  if (u.pathname === "/coeur.js") { res.writeHead(200, { "Content-Type": "text/javascript; charset=utf-8", "Cache-Control": "no-store" }); res.end(readFileSync(COEUR)); return; }
  if (u.pathname === "/planche.html") { res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" }); res.end(PLANCHE); return; }
  if (u.pathname.startsWith("/png/")) { res.writeHead(200, { "Content-Type": "image/png" }); res.end(readFileSync(join(SORTIE, u.pathname.slice(5)))); return; }
  const m = /^\/api\/pc_parental\/veille\/media\/([0-9a-f]{24})$/.exec(u.pathname);
  if (m) {
    let corps = "";
    req.on("data", (d) => { corps += d; });
    req.on("end", () => {
      let ticket = ""; try { ticket = JSON.parse(corps).ticket || ""; } catch { /* corps illisible */ }
      requetes.push({ methode: req.method, cle: m[1], ticket: ticket === TICKET, autorisation: !!req.headers.authorization, cookie: !!req.headers.cookie });
      const img = IMAGES[parseInt(m[1], 16)];
      if (req.method !== "POST" || ticket !== TICKET || !img) { res.writeHead(req.method !== "POST" ? 405 : 401); res.end(); return; }
      res.writeHead(200, { "Content-Type": "image/svg+xml", "Cache-Control": "no-store" }); res.end(img);
    });
    return;
  }
  requetes.push({ methode: req.method, autre: u.pathname });
  res.writeHead(404); res.end();
});
await new Promise((ok) => serveur.listen(0, "127.0.0.1", ok));
const PORT = serveur.address().port;

// --- La page d'essai : le vrai panneau, un faux Home Assistant ---------------------------------------------------
const PAGE = `<!doctype html><meta charset="utf-8"><title>essai</title><style>html,body{margin:0;background:#000}</style><body>
<script>
// Le ticket rangé par la page d'entrée (forme vv1), comme sur un appareil.
localStorage.clear();
localStorage.setItem("hassTokens", JSON.stringify({ access_token: ${JSON.stringify(TICKET)} }));
// Une carte ordinaire de Home Assistant (factice) : une tuile avec son nom et sa valeur.
class CarteEssai extends HTMLElement {
  setConfig(c) { this._c = c; }
  set hass(h) { if (this._fait) return; this._fait = true; const r = this.attachShadow({ mode: "open" });
    r.innerHTML = '<style>:host{display:block}.c{box-sizing:border-box;background:var(--ha-card-background);border-radius:var(--ha-card-border-radius);padding:14px 16px;color:var(--primary-text-color);font:16px Segoe UI}.n{color:var(--secondary-text-color);font-size:14px}.v{font-size:26px;margin-top:4px}</style><div class="c"><div class="n"></div><div class="v"></div></div>';
    r.querySelector(".n").textContent = this._c.name || this._c.type; r.querySelector(".v").textContent = this._c.valeur || "21,5 °C"; }
  getGridOptions() { return { columns: 6 }; }
}
customElements.define("carte-essai", CarteEssai);
window.loadCardHelpers = async () => ({ createCardElement: (c) => { const e = document.createElement("carte-essai"); e.setConfig(c); return e; } });
const q = new URLSearchParams(location.search);
// « android=1 » : comme dans l'appli de la télé (le pont VisionAndroid existe).
if (q.get("android") === "1") window.VisionAndroid = { pret() {} };
const jour = (n, h, m) => { const d = new Date(); d.setDate(d.getDate() + n); d.setHours(h, m, 0, 0); return d.toISOString(); };
const date = (n) => { const d = new Date(); d.setDate(d.getDate() + n); return d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0") + "-" + String(d.getDate()).padStart(2, "0"); };
const M = (n) => "/api/pc_parental/veille/media/" + n.toString(16).padStart(24, "0");
const carte = (cle, config) => ({ cle, cles: [], config });
const sections = [
  { titre: "Dehors", span: 1, cartes: [carte("m1", { type: "weather-forecast", entity: "weather.maison" }), carte("a1", { type: "calendar", entities: ["calendar.famille"] })] },
  { titre: "Maison", span: 1, cartes: [carte("p1", { type: "picture-elements", image: "/local/plan.png" }), carte("r1", { type: "picture-elements", image: "/local/fond.png", title: "Radar" })] },
  { titre: "Caméras", span: 1, cartes: [carte("c1", { type: "picture-entity", entity: "camera.jardin" }), carte("f1", { type: "custom:advanced-camera-card" })] },
  { titre: "Ailleurs", span: 1, cartes: [carte("i1", { type: "iframe", url: "https://trafic.invalid/carte", aspect_ratio: "16:9" }), carte("i2", { type: "iframe", url: "https://autre.invalid/", aspect_ratio: "16:9" }), carte("v1", { type: "tile", entity: "sensor.salon", name: "Salon" }), carte("v2", { type: "tile", entity: "sensor.eau", name: "Eau (L/j)", valeur: "312 L/j" })] },
];
const typees = {
  m1: { genre: "meteo", entite: "weather.maison", nom: "Météo", etat: "partlycloudy", etat_texte: "Éclaircies", temperature: 18.4, unite: "°C",
        previsions: [0, 1, 2, 3, 4].map((n) => ({ quand: date(n), etat: ["partlycloudy", "rainy", "pouring", "sunny", "lightning-rainy"][n], max: [18, 15, 13, 21, 19][n], min: [9, 8, 7, 10, 12][n], pluie: 20 })) },
  a1: { genre: "agenda", entites: ["calendar.famille"], nom: "Agenda", evenements: [
        { debut: jour(0, 0, 0).slice(0, 0) + date(0), fin: date(1), titre: "Journée pédagogique", journee: true },
        { debut: jour(0, 17, 30), fin: jour(0, 18, 30), titre: "Piscine", journee: false },
        { debut: jour(1, 8, 15), fin: jour(1, 9, 0), titre: "Dentiste — rendez-vous de contrôle annuel", journee: false },
        { debut: jour(3, 14, 0), fin: jour(3, 15, 0), titre: "Anniversaire de Léa", journee: false },
        { debut: jour(-1, 9, 0), fin: jour(-1, 10, 0), titre: "Fini (ne doit pas paraître)", journee: false }] },
  p1: { genre: "image", nom: "Plan", url: M(1), elements: [{ x: 22, y: 55, texte: "21,5 °C" }, { x: 64, y: 40, texte: "Lumière allumée" }, { x: 84, y: 70, texte: "19 °C" }],
        calques: [{ x: 84, y: 26, largeur: 22, url: M(9), entite: "camera.entree" }] },
  r1: { genre: "radar", nom: "Radar de pluie", url: M(2), calque: { url: M(3), entite: "camera.radar" } },
  c1: { genre: "camera", entite: "camera.jardin", nom: "Jardin", direct: "camera.jardin_sub", url: M(4) },
  f1: { genre: "clips", nom: "Derniers passages", liste: [
        { camera: "Jardin", entite: "camera.jardin", quand: new Date(Date.now() - 4 * 60000).toISOString(), objet: "person", objet_nom: "Personne", vignette: M(5) },
        { camera: "Allée", entite: "camera.allee", quand: new Date(Date.now() - 2 * 3600000).toISOString(), objet: "car", objet_nom: "Voiture", vignette: M(6) },
        { camera: "Jardin", entite: "camera.jardin", quand: new Date(Date.now() - 26 * 3600000).toISOString(), objet: "dog", objet_nom: "Chien", vignette: M(7) },
        { camera: "Entrée", entite: "camera.entree", quand: new Date(Date.now() - 3 * 86400000).toISOString(), objet: "package", objet_nom: "Colis", vignette: M(8) }] },
  i1: { genre: "iframe", url: "https://trafic.invalid/carte", nom: "Trafic" },
  i2: { genre: "iframe", url: "https://autre.invalid/", nom: "Deuxième page (une seule à la fois)" },
};
const tableau = { id: "dash_essai_0", titre: "Maison", duree: 600, colonnes: 4, sections };
if (q.get("typees") !== "0") tableau.typees = typees;
const page = { ok: true, horloge: { actif: false, duree: 15 }, meteo: "weather.maison", tableaux: [tableau] };
const hass = {
  states: { "weather.maison": { state: "partlycloudy", attributes: { temperature: 17.6 } } },
  themes: {}, auth: { data: { access_token: ${JSON.stringify(TICKET)} } },
  callWS: async (m) => (m.type === "pc_parental/veille_page" ? JSON.parse(JSON.stringify(page)) : []),
  callApi: async () => { throw new Error("pas d'API"); },
};
const s = document.createElement("script"); s.src = "/coeur.js";
s.onload = () => { const el = document.createElement("vision-veille-coeur"); document.body.appendChild(el); el.hass = hass; };
document.head.appendChild(s);
</script></body>`;

// --- Les vérifications faites dans la page ------------------------------------------------------------------------
const VERIFIER = `(() => {
  const h = document.querySelector("vision-veille-coeur"), r = h && h.shadowRoot, dpr = devicePixelRatio;
  if (!r) return { erreur: "pas de panneau" };
  const vue = r.querySelector(".scene.vue");
  const fr = (v) => Math.abs(v * dpr - Math.round(v * dpr));
  const cartes = [...vue.querySelectorAll(".section > *")].filter((x) => x.closest(".section").style.visibility !== "hidden" && !x.classList.contains("sec"));
  let pire = 0, quoi = ""; const bords = [];
  for (const x of cartes) { const b = x.getBoundingClientRect(), f = Math.max(fr(b.left), fr(b.top), fr(b.width), fr(b.height)); if (f > pire) { pire = f; quoi = (x.className || x.localName) + " " + [b.left, b.top, b.width, b.height].map((v) => Math.round(v * dpr * 100) / 100).join(","); } bords.push(b); }
  // Les écarts entre deux cartes voisines d'une même rangée (même haut), en pixels de l'écran.
  const ecarts = new Set();
  for (const a of bords) for (const b of bords) if (Math.abs(a.top - b.top) < 0.5 && b.left > a.right && b.left - a.right < 40) {
    if (!bords.some((c) => Math.abs(c.top - a.top) < 0.5 && c.left > a.right && c.left < b.left)) ecarts.add(Math.round((b.left - a.right) * dpr * 100) / 100);
  }
  const genres = [...vue.querySelectorAll(".vt")].map((x) => x.className.replace("vt vt-", ""));
  const imgs = [...vue.querySelectorAll("img[data-media]")];
  const cadres = [...vue.querySelectorAll(".web iframe")];
  return { dpr, cartes: cartes.length, pireFraction: Math.round(pire * 1000) / 1000, pire: quoi, ecarts: [...ecarts], genres,
    images: imgs.length, imagesVues: imgs.filter((i) => i.classList.contains("vu") && i.naturalWidth > 0).length,
    iframes: cadres.length, sandbox: cadres.map((c) => c.getAttribute("sandbox")), mentions: [...vue.querySelectorAll(".web-mention")].map((m) => m.textContent).filter(Boolean),
    agenda: [...vue.querySelectorAll(".vt-titre")].map((x) => x.textContent), meteo: (vue.querySelector(".vt-temp") || {}).textContent,
    pastille: h.getAttribute("data-pastille"), derive: h.style.transform, animations: document.getAnimations ? r.getAnimations().length : -1,
    titre: (vue.querySelector(".titre") || {}).textContent };
})()`;

// --- Edge, une seule fois ----------------------------------------------------------------------------------------
const port = 9600 + Math.floor(Math.random() * 300);
const profil = mkdtempSync(join(tmpdir(), "vision-veille-cases-"));
const L = 1280, H = 720, ECHELLE = Number(process.env.ECHELLE || 1.5);
const nav = spawn(edge, ["--headless=new", "--disable-gpu", `--user-data-dir=${profil}`, `--remote-debugging-port=${port}`, `--window-size=${L},${H}`,
  "--no-first-run", "--disable-extensions", "--host-resolver-rules=MAP * ~NOTFOUND, EXCLUDE 127.0.0.1", "about:blank"], { stdio: "ignore" });
const bilan = { cas: [], erreurs: [] };
let PLANCHE = "";
try {
  let cible = null;
  for (let i = 0; i < 100 && !cible; i++) { try { cible = (await (await fetch(`http://127.0.0.1:${port}/json`)).json()).find((c) => c.type === "page"); } catch { await pause(200); } }
  const ws = new WebSocket(cible.webSocketDebuggerUrl);
  await new Promise((ok, ko) => { ws.onopen = ok; ws.onerror = ko; });
  let n = 0; const attente = new Map();
  ws.onmessage = (m) => {
    const r = JSON.parse(m.data);
    if (r.id && attente.has(r.id)) { attente.get(r.id)(r); attente.delete(r.id); return; }
    const p = r.params || {};
    if (r.method === "Runtime.exceptionThrown") bilan.erreurs.push(String(p.exceptionDetails?.exception?.description || p.exceptionDetails?.text).split("\n")[0].slice(0, 200));
    if (r.method === "Runtime.consoleAPICalled" && p.type === "error") bilan.erreurs.push(p.args.map((x) => x.value ?? x.description ?? "").join(" ").slice(0, 200));
  };
  const cmd = (method, params = {}) => new Promise((ok) => { const id = ++n; attente.set(id, ok); ws.send(JSON.stringify({ id, method, params })); });
  const evaluer = async (e) => (await cmd("Runtime.evaluate", { expression: e, returnByValue: true })).result?.result?.value;
  await cmd("Runtime.enable"); await cmd("Page.enable"); await cmd("Performance.enable");
  await cmd("Emulation.setDeviceMetricsOverride", { width: Math.round(L / ECHELLE), height: Math.round(H / ECHELLE), deviceScaleFactor: ECHELLE, mobile: false });
  const THEMES = {
    sombre: "fond=17121C&carte=251C2D&texte=F4EDE1&texte2=BDB1C4&accent=E2B24A&ligne=3A2D45",
    beige: "fond=EFE6D6&carte=FBF6EC&texte=2A2026&texte2=6E5A5E&accent=8C2F4B&ligne=D9CDB8",
  };
  const CAS = [
    ["typees-sombre-sans-pastille", "sombre", "pastille=0"], ["typees-sombre-pastille", "sombre", "pastille=1"],
    ["typees-beige-sans-pastille", "beige", "pastille=0"], ["typees-beige-pastille", "beige", "pastille=1"],
    ["ancien-sombre", "sombre", "typees=0"], ["ancien-beige", "beige", "typees=0"],
    // Une télé récente (elle envoie « pastille » et fait la dérive sur toute la page) : la page ne la double pas.
    ["tele-recente-beige", "beige", "pastille=1&android=1"],
  ];
  for (const [nom, theme, plus] of CAS.filter(([x]) => !process.env.SEUL || x === process.env.SEUL)) {
    const avant = requetes.length;
    await cmd("Page.navigate", { url: `http://127.0.0.1:${PORT}/essai.html?id=tele1&ecran=tele&masques=horloge&anim=1&${THEMES[theme]}&${plus}` });
    await pause(5000);
    const m0 = (await cmd("Performance.getMetrics")).result.metrics;
    await pause(10000);
    const m1 = (await cmd("Performance.getMetrics")).result.metrics;
    const v = (m, k) => (m.find((x) => x.name === k) || {}).value || 0;
    const parSeconde = (k) => Math.round(((v(m1, k) - v(m0, k)) / ((v(m1, "Timestamp") - v(m0, "Timestamp")) || 1)) * 1000) / 1000;
    const verif = await evaluer(VERIFIER);
    if (process.env.DETAIL) console.log(JSON.stringify(await evaluer(process.env.DETAIL)));
    const cap = await cmd("Page.captureScreenshot", { format: "png" });
    writeFileSync(join(SORTIE, `${nom}.png`), Buffer.from(cap.result.data, "base64"));
    const reqs = requetes.slice(avant);
    bilan.cas.push({ nom, ...verif, scriptMsParS: Math.round(parSeconde("ScriptDuration") * 1000), styleMsParS: Math.round(parSeconde("RecalcStyleDuration") * 1000),
      miseEnPageParS: parSeconde("LayoutCount"), medias: reqs.filter((x) => x.cle).length, mediasSansTicket: reqs.filter((x) => x.cle && (!x.ticket || x.methode !== "POST")).length,
      mediasAvecEntete: reqs.filter((x) => x.autorisation || x.cookie).length, autres: reqs.filter((x) => x.autre).map((x) => x.autre) });
  }
  // Ce que la page fait d'une adresse arbitraire (jamais demandée) et d'un jeton qui n'est pas un ticket (jamais envoyé).
  bilan.garde = await evaluer(`(() => { const h = document.querySelector("vision-veille-coeur");
    return { autreAdresse: h._img("https://ailleurs.example/x.png", "fond") === "" && h._img("/api/camera_proxy/camera.x", "fond") === "",
             jetonHA: ticketVeille({ auth: { data: { access_token: "eyJhbGciOi.eyJpc3Mi.c2lnbmF0dXJl" } } }) === "" || "envoyé" }; })()`);
  // La planche : toutes les captures, avec leur nom.
  if (!process.env.SEUL) PLANCHE = `<!doctype html><meta charset="utf-8"><style>body{margin:0;background:#222;font:16px Segoe UI;color:#eee}.g{display:grid;grid-template-columns:1fr 1fr;gap:10px;padding:10px}figure{margin:0}img{width:100%;display:block}figcaption{padding:4px 0}</style><div class="g">${CAS.map(([nom]) => `<figure><img src="/png/${nom}.png"><figcaption>${nom}</figcaption></figure>`).join("")}</div>`;
  if (PLANCHE) { await cmd("Emulation.setDeviceMetricsOverride", { width: 1300, height: 1300, deviceScaleFactor: 1, mobile: false });
  await cmd("Page.navigate", { url: `http://127.0.0.1:${PORT}/planche.html` });
  await pause(1500);
  const p = await cmd("Page.captureScreenshot", { format: "png", captureBeyondViewport: true });
  writeFileSync(join(SORTIE, "planche-cases.png"), Buffer.from(p.result.data, "base64")); }
  ws.close();
} catch (e) { bilan.erreurs.push("banc : " + e.message); }
finally {
  if (process.platform === "win32") {
    spawnSync("taskkill", ["/PID", String(nav.pid), "/T", "/F"], { stdio: "ignore" });
    spawnSync("powershell", ["-NoProfile", "-NonInteractive", "-Command",
      `Get-CimInstance Win32_Process -Filter "Name = 'msedge.exe'" | Where-Object { $_.CommandLine -like '*${profil.split(/[\\/]/).pop()}*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }`], { stdio: "ignore" });
  } else nav.kill();
  await pause(400);
  try { rmSync(profil, { recursive: true, force: true }); } catch { /* verrou d'Edge */ }
  serveur.close();
}
bilan.erreurs = [...new Set(bilan.erreurs)];
for (const c of bilan.cas) console.log(JSON.stringify(c));
console.log(JSON.stringify({ garde: bilan.garde, erreurs: bilan.erreurs }));
const mauvais = bilan.erreurs.length || bilan.cas.some((c) => (c.nom.startsWith("tele-recente") ? c.derive !== "" : !c.derive)) || bilan.cas.some((c) => c.pireFraction > 0.05 || c.mediasSansTicket || c.mediasAvecEntete || (c.nom.startsWith("typees") && (c.iframes > 1 || c.imagesVues < c.images)));
process.exit(mauvais ? 1 : 0);
