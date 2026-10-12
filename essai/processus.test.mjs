// Essai de la carte du démarrage : le verdict « Désinstaller » et sa confirmation.
// node essai/processus.test.mjs   (node_modules -> /opt/npm-tools/node_modules)
import { chromium } from "playwright";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

const ici = path.dirname(fileURLToPath(import.meta.url));
const source = readFileSync(path.join(ici, "..", "pc_parental", "frontend", "vision-processus-card.js"), "utf8");
const sortie = process.argv[2] || path.join(ici, "processus.png");

const fiche = (cle, nom, extra = {}) => ({
  cle, genre: cle.split("|")[0], nom, exe: nom.toLowerCase(), chemin: "C:\\Program Files\\" + nom + "\\" + nom + ".exe",
  commande: "", signataire: "Éditeur", statut: "Valid", editeur: "Éditeur", description: "", ou: "Program Files",
  protege: false, etat: "decides", proposition: null, decision: null, non_desinstallable: "",
  pcs: [{ pc: "PC-SALON", actif: true, ram_mo: 120, applique: false }], ...extra,
});
const donnees = (permis) => ({
  desinstallation: permis,
  compte: { propositions: 0, a_classer: 0, decides: 4 },
  elements: [
    fiche("processus|nortonui", "NortonUI", { decision: { verdict: "nuisible" } }),
    fiche("processus|vgc", "Vanguard", { decision: { verdict: "garder" }, non_desinstallable: "est l'anti-triche d'un jeu" }),
    fiche("service|vieux", "VieuxLogiciel", { decision: { verdict: "desinstaller" },
      pcs: [{ pc: "PC-SALON", actif: true, ram_mo: 0, applique: false, desinstall: { etat: "fait", detail: "" } },
            { pc: "PC-BUREAU", actif: true, ram_mo: 0, applique: false, desinstall: { etat: "impossible", detail: "pas de désinstallation silencieuse connue : à retirer à la main" } },
            { pc: "PC-EXEMPLE", actif: true, ram_mo: 0, applique: false }] }),
    fiche("processus|svchost", "svchost", { protege: true, decision: { verdict: "garder" } }),
  ],
});

let rates = 0;
const verifier = (quoi, ok) => { console.log((ok ? "ok    " : "RATE  ") + quoi); if (!ok) rates++; };

const nav = await chromium.launch();
const page = await nav.newPage({ viewport: { width: 1100, height: 900 } });
await page.setContent(`<html><body style="font-family:sans-serif;--divider-color:#ddd;--secondary-background-color:#f3f3f3;--primary-color:#8a1c1c;--secondary-text-color:#666"><div id="z"></div></body></html>`);
await page.addScriptTag({ content: "customElements.define('ha-card', class extends HTMLElement {});" + source });

const monter = async (permis) => page.evaluate(async (d) => {
  document.getElementById("z").innerHTML = "";
  window.appels = [];
  const c = document.createElement("vision-processus-card");
  c.setConfig({ onglet: "decides" });
  document.getElementById("z").appendChild(c);
  c.hass = {
    states: { "sensor.vision_processus_a_valider": { state: "0", attributes: { a_classer: 0, decides: 4, desinstallations: 0 } } },
    callApi: async () => d,
    callService: async (dom, srv, corps) => { window.appels.push([dom, srv, corps]); },
  };
  await new Promise((r) => setTimeout(r, 50));
  window.carte = c;
}, donnees(permis));
const q = (sel) => page.evaluate((s) => window.carte.shadowRoot.querySelectorAll(s).length, sel);

await monter(false);
verifier("interrupteur éteint : aucun bouton Désinstaller", (await q('button[data-a="desinstaller"]')) === 0);

await monter(true);
verifier("allumé : un seul bouton (ni l'anti-triche, ni Windows, ni le déjà décidé)", (await q('button[data-a="desinstaller"]')) === 1);
verifier("compte rendu « désinstallé » affiché", await page.evaluate(() => window.carte.shadowRoot.innerHTML.includes("✔ désinstallé")));
verifier("compte rendu « impossible » avec son détail", await page.evaluate(() => window.carte.shadowRoot.innerHTML.includes("à retirer à la main")));
verifier("« demandée » là où l'agent n'a pas répondu", await page.evaluate(() => window.carte.shadowRoot.innerHTML.includes("désinstallation demandée")));

await page.evaluate(() => window.carte.shadowRoot.querySelector('button[data-a="desinstaller"]').click());
verifier("un clic ne désinstalle pas", await page.evaluate(() => window.appels.length === 0));
verifier("la question s'ouvre", (await q(".confirmer")) === 1);
verifier("la question nomme l'appareil", await page.evaluate(() => window.carte.shadowRoot.querySelector(".confirmer").textContent.includes("PC-SALON")));
await page.screenshot({ path: sortie });
await page.evaluate(() => window.carte.shadowRoot.querySelector("[data-non]").click());
verifier("Annuler referme sans rien envoyer", (await q(".confirmer")) === 0 && await page.evaluate(() => window.appels.length === 0));

await page.evaluate(() => window.carte.shadowRoot.querySelector('button[data-a="desinstaller"]').click());
await page.evaluate(() => window.carte.shadowRoot.querySelector("[data-oui]").click());
await page.waitForTimeout(100);
const appels = await page.evaluate(() => window.appels);
verifier("confirmé : un seul appel, une seule clé, confirmation", appels.length === 1 && appels[0][1] === "processus_decider"
  && appels[0][2].decision === "desinstaller" && appels[0][2].cles.length === 1 && appels[0][2].cles[0] === "processus|nortonui" && appels[0][2].confirmation === true);

await monter(true);
await page.evaluate(() => window.carte.shadowRoot.querySelector('button[data-a="garder"]').click());
await page.waitForTimeout(100);
const autres = await page.evaluate(() => window.appels);
verifier("les autres verdicts ne portent pas de confirmation", autres.length === 1 && !("confirmation" in autres[0][2]));

await nav.close();
console.log(rates + " raté(s)");
process.exit(rates ? 1 : 0);
