// Banc des gestes Vision (1.97.3) — chaque action de chaque carte, dans un vrai navigateur (Edge ou Chrome sans
// affichage) : au clic, un retour visible tout de suite (« En cours… ») ; à la réponse du serveur, l'affichage suit
// (la demande disparaît, le message du résultat) ; en cas d'échec, un message clair en français et rien de cassé.
//
//   node clics.mjs <dossier servi> <navigateur>   (le dossier est servi par ce script, sur un port libre)
// Sortie : une ligne JSON par scénario, puis {"fin": true, "echecs": n}. Code de sortie 1 si un scénario échoue.
import { spawn, spawnSync } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import { join } from "node:path";

const [, , DOSSIER, NAVIGATEUR] = process.argv;
let PORT_HTTP = 0;
const TYPES = { ".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8", ".json": "application/json; charset=utf-8" };
const PORT_CDP = 9400 + Math.floor(Math.random() * 400);
const pause = (ms) => new Promise((r) => setTimeout(r, ms));

// Dans la page : raccourcis communs aux scénarios.
const OUTILS = `
  const R = () => window.__carte && window.__carte.shadowRoot;
  const tous = (sel) => [...(R() ? R().querySelectorAll(sel) : [])];
  const un = (sel) => tous(sel)[0];
  const boutonEnCours = () => tous("button").some((b) => b.textContent.trim() === "En cours…" || /Enregistrement…/.test(b.textContent));
  const statut = () => { const z = un(".vision-retour") || un(".fait") || un(".erreur"); return z ? z.textContent.trim() : ""; };
  const statutKo = () => { const z = un('.vision-retour[role="alert"]') || un(".erreur"); return z ? z.textContent.trim() : ""; };
  const dernier = () => window.__appels[window.__appels.length - 1];
  // 15-10 : choisir une option d'un <vision-choix>, qu'il montre des étiquettes ou une liste déroulante.
  const choisir = (c, cle) => { const s = c.shadowRoot; const b = [...s.querySelectorAll("[data-cible][data-cle]")].find((x) => x.dataset.cle === cle);
    if (b) { b.click(); return; } const l = s.querySelector("select"); l.value = cle; l.dispatchEvent(new Event("change")); };
  const libelles = (c) => [...c.shadowRoot.querySelectorAll("[data-cible][data-cle]")].map((x) => x.textContent.trim());
  const D = window.__donnees;
  // 1.93.2 : la page défile (la carte est loin en bas d'une page à peine plus haute qu'elle, comme dans Home Assistant)
  // et le geste ne doit ni la faire remonter ni perdre le bouton qui a le focus.
  const hautPage = () => (document.scrollingElement || document.documentElement).scrollTop;
  const preparerDefilement = async (sel) => {
    const z = document.getElementById("zone"); z.style.marginTop = "3000px"; document.body.style.paddingBottom = "0";
    await new Promise((r) => setTimeout(r, 150));
    const b = un(sel); if (!b) throw new Error("introuvable : " + sel);
    // Le plus bas possible tant que l'élément reste à l'écran : là, le moindre raccourcissement fait sauter la page.
    const doc = document.scrollingElement || document.documentElement;
    const haut = b.getBoundingClientRect().top + hautPage();
    const max = doc.scrollHeight - window.innerHeight;
    doc.scrollTop = (haut - max >= 0 && haut - max < window.innerHeight - 60) ? max : haut - 200;
    await new Promise((r) => setTimeout(r, 50));
    window.__y0 = hautPage(); window.__sel = sel; window.__yMin = window.__y0; window.__hMin = window.__carte.offsetHeight;
    window.addEventListener("scroll", () => { window.__yMin = Math.min(window.__yMin, hautPage()); });
    new ResizeObserver(() => { window.__hMin = Math.min(window.__hMin, window.__carte.offsetHeight); }).observe(window.__carte);
    if (window.__y0 < 1000) throw new Error("la page ne défile pas assez pour l'essai : " + window.__y0);
    b.focus(); b.click();
  };
  // Ni à la fin, ni en passant : la page ne doit pas avoir remonté, même un instant.
  const placeGardee = () => Math.abs(hautPage() - window.__y0) <= 2 && window.__y0 - window.__yMin <= 2 && R().activeElement === un(window.__sel);
  window.__mesure = () => ({ y0: window.__y0, y: hautPage(), yMin: window.__yMin, hMin: window.__hMin, h: window.__carte.offsetHeight,
    focus: R().activeElement ? R().activeElement.outerHTML.slice(0, 80) : null });
`;

// Un message d'échec lisible : en français, pas un code ni un message technique anglais.
const FRANCAIS = `(t) => t.length > 8 && /[éèàù]|n'|réessaie|pas/.test(t) && !/^(error|undefined|null|\\[object)/i.test(t)`;

const SCENARIOS = [
  // ---------------------------------------------------------------- carte Maison
  {
    nom: "Maison — accepter une demande d'accès (1 h)", carte: "vision-maison-card", donnees: "maison", config: { mode: "maison", chemin: "/vision-maison" },
    agir: `window.__id = D["pc_parental/maison"].demandes.find((d) => d.genre !== "temps").id;
           un('button[data-acces="' + window.__id + '"][data-dec="temporaire"]').click(); true`,
    enCours: `boutonEnCours() && tous("button[data-acces]").every((b) => b.disabled)`,
    repondre: `(() => { const n = JSON.parse(JSON.stringify(D["pc_parental/maison"])); n.demandes = n.demandes.filter((d) => d.id !== window.__id);
               n.retour = "Nolan a autorisé Roblox pour 60 min."; return n; })()`,
    apres: `!un('button[data-acces="' + window.__id + '"]') && /autorisé/.test(statut()) && !boutonEnCours()`,
  },
  {
    nom: "Maison — accorder une demande de temps", carte: "vision-maison-card", donnees: "maison", config: { mode: "maison", chemin: "/vision-maison" },
    agir: `const t = D["pc_parental/maison"].demandes.find((d) => d.genre === "temps"); window.__id = t.id;
           const b = un('button[data-acces="' + t.id + '"][data-dec="temporaire"]');
           if (!/Accorder 15 min/.test(b.textContent) || b.dataset.min !== "15") throw new Error("bouton : " + b.textContent);
           b.click(); true`,
    enCours: `boutonEnCours()`,
    verifierAppel: `dernier().corps.action === "acces" && dernier().corps.demande === window.__id && dernier().corps.decision === "temporaire"`,
    repondre: `(() => { const n = JSON.parse(JSON.stringify(D["pc_parental/maison"])); n.demandes = n.demandes.filter((d) => d.id !== window.__id);
               n.retour = "Nolan a ajouté 15 min sur TEL-LOU (jusqu'à 19h00)."; return n; })()`,
    apres: `!un('button[data-acces="' + window.__id + '"]') && /15 min/.test(statut())`,
  },
  {
    nom: "Maison — refus qui échoue (Home Assistant injoignable)", carte: "vision-maison-card", donnees: "maison", config: { mode: "maison", chemin: "/vision-maison" },
    agir: `window.__id = D["pc_parental/maison"].demandes[0].id; un('button[data-acces="' + window.__id + '"][data-dec="non"]').click(); true`,
    enCours: `boutonEnCours()`,
    echouer: `Object.assign(new Error("connection lost"), { error: "connection lost" })`,
    apres: `(${FRANCAIS})(statutKo()) && !!un('button[data-acces="' + window.__id + '"][data-dec="non"]') && !un('button[data-acces="' + window.__id + '"]').disabled`,
  },
  {
    nom: "Maison — demande déjà traitée ailleurs", carte: "vision-maison-card", donnees: "maison", config: { mode: "maison", chemin: "/vision-maison" },
    agir: `window.__id = D["pc_parental/maison"].demandes[0].id; un('button[data-acces="' + window.__id + '"][data-dec="non"]').click(); true`,
    enCours: `boutonEnCours()`,
    repondre: `(() => { const n = JSON.parse(JSON.stringify(D["pc_parental/maison"])); n.demandes = n.demandes.filter((d) => d.id !== window.__id);
               n.retour = "Cette demande a déjà été traitée."; return n; })()`,
    apres: `!un('button[data-acces="' + window.__id + '"]') && /déjà été traitée/.test(statut())`,
  },
  {
    nom: "Maison — refus de l'administrateur (403) en clair", carte: "vision-maison-card", donnees: "maison", config: { mode: "maison", chemin: "/vision-maison" },
    agir: `un("button[data-acces]").click(); true`,
    enCours: `boutonEnCours()`,
    echouer: `Object.assign(new Error("Forbidden"), { status_code: 403, body: { ok: false, error: "admin" } })`,
    apres: `/administrateurs/.test(statutKo())`,
  },
  // Lot 1 (HA-01) : retirer un appareil = « Retirer… », un dialogue qui dit ce qui sera perdu, et le nom à taper.
  // Aucun appel tant que le nom ne correspond pas (bouton inactif, et même un envoi forcé du formulaire est refusé).
  {
    nom: "Personne — retirer un appareil seulement après avoir tapé son nom", carte: "vision-maison-card", donnees: "maison",
    config: { mode: "personne", personne: "person.lou", chemin: "/vision-maison" },
    agir: `const a = D["pc_parental/maison"].appareils.find((x) => x.nom === "TEL-LOU");
           const attendre = () => new Promise((r) => setTimeout(r, 60));
           if (un("dialog.retrait")) throw new Error("dialogue ouvert d'avance");
           if (tous("[data-retrait-ok]").length) throw new Error("bouton de retrait visible d'avance");
           const ouvrir = async () => { un('button[data-retirer="' + a.id + '"]').click(); await attendre();
             const d = un("dialog.retrait"); if (!d || !d.open) throw new Error("pas de dialogue"); return d; };
           const taper = (t) => { const c = un("[data-retrait-saisie]"); c.value = t; c.dispatchEvent(new Event("input")); };
           // Annuler, même avec le bon nom tapé : rien ne part, et le champ repart vide.
           await ouvrir(); taper("TEL-LOU"); un("[data-retrait-annuler]").click(); await attendre();
           if (un("dialog.retrait") || window.__appels.length) throw new Error("Annuler a retiré ou laissé le dialogue");
           const d = await ouvrir();
           if (!/TEL-LOU/.test(d.querySelector("h2").textContent) || !/historique/.test(d.textContent)) throw new Error("résumé : " + d.textContent);
           if (un("[data-retrait-saisie]").value !== "") throw new Error("nom prérempli");
           if (!un("[data-retrait-ok]").disabled) throw new Error("Retirer actif sans nom");
           un("[data-retrait-ok]").click(); un("[data-retrait-form]").requestSubmit(); await attendre();
           if (window.__appels.length) throw new Error("appel sans nom saisi");
           taper("TEL-LO");
           if (!un("[data-retrait-ok]").disabled) throw new Error("actif avec un nom incomplet");
           un("[data-retrait-form]").requestSubmit(); await attendre();
           if (window.__appels.length) throw new Error("appel avec un nom faux");
           taper(" tel-lou ");
           if (un("[data-retrait-ok]").disabled) throw new Error("inactif avec le bon nom");
           un("[data-retrait-ok]").click(); true`,
    enCours: `boutonEnCours()`,
    verifierAppel: `dernier().genre === "service" && dernier().chemin === "pc_parental.delete_pc" && dernier().corps.pc === "TEL-LOU" && window.__appels.length === 1`,
    repondre: `({})`,
    apres: `/retiré/.test(statut()) && !un("dialog.retrait") && !boutonEnCours()`,
  },
  // 2.5.0 (refonte des autorisations) : les cinq réponses à une demande, et l'onglet Autorisations de la fiche.
  {
    nom: "Maison — demande : 15 min, 1 h, Exception, Toujours disponible, Refuser", carte: "vision-maison-card", donnees: "maison",
    config: { mode: "maison", chemin: "/vision-maison" },
    agir: `window.__id = D["pc_parental/maison"].demandes.find((d) => d.genre !== "temps").id;
           const textes = tous('button[data-acces="' + window.__id + '"]').map((b) => b.textContent.trim());
           if (textes.join("|") !== "15 min|1 h|Exception|Toujours disponible|Refuser") throw new Error("boutons : " + textes.join("|"));
           un('button[data-acces="' + window.__id + '"][data-mode="toujours"]').click(); true`,
    enCours: `boutonEnCours()`,
    verifierAppel: `dernier().corps.action === "acces" && dernier().corps.decision === "accorder" && dernier().corps.mode === "toujours" && dernier().corps.demande === window.__id`,
    repondre: `(() => { const n = JSON.parse(JSON.stringify(D["pc_parental/maison"])); n.demandes = n.demandes.filter((d) => d.id !== window.__id);
               n.retour = "Nolan : Roblox est toujours disponible, sur toutes ses machines."; return n; })()`,
    apres: `!un('button[data-acces="' + window.__id + '"]') && /toujours disponible/.test(statut())`,
  },
  {
    // 2.5.1 : l'interrupteur d'un élément ouvre le choix du mode ; « Suivre la catégorie » part avec le bon mode.
    nom: "Personne — par élément : Suivre la catégorie", carte: "vision-maison-card", donnees: "maison",
    config: { mode: "personne", personne: "person.lou", chemin: "/vision-maison" },
    agir: `const attendre = () => new Promise((r) => setTimeout(r, 120));
           un('button.ouvrir[data-cat="Jeux"]').click(); await attendre();
           const inter = un('button[data-el-mode="sites:twitch.tv"]'); if (!inter) throw new Error("pas d'interrupteur pour twitch.tv");
           if (window.__appels.length) throw new Error("une lecture comptée comme un geste");
           inter.click(); await attendre();
           const choix = un('button[data-el-choix="sites:twitch.tv"][data-el-m="categorie"]'); if (!choix) throw new Error("pas de choix du mode");
           if (choix.getAttribute("aria-pressed") !== "true") throw new Error("le mode par défaut n'est pas coché");
           un('button[data-el-choix="sites:twitch.tv"][data-el-m="exception"]').click(); true`,
    enCours: `boutonEnCours()`,
    verifierAppel: `dernier().corps.action === "accorder" && dernier().corps.mode === "exception" && dernier().corps.nom === "twitch.tv" && dernier().corps.genre === "sites" && !!dernier().corps.pc`,
    repondre: `(() => { const n = JSON.parse(JSON.stringify(D["pc_parental/maison"])); n.retour = "Nolan : twitch.tv est une exception, sur toutes ses machines."; return n; })()`,
    apres: `/exception/.test(statut())`,
  },
  {
    // 2.5.1 : une demande groupée ; un site décoché reste fermé (la réponse porte la liste des éléments cochés).
    nom: "Maison — demande groupée : décocher un site", carte: "vision-maison-card", donnees: "maison", config: { mode: "maison", chemin: "/vision-maison" },
    agir: `const c = window.__carte; const d = c._donnees.demandes.find((x) => x.genre === "apps"); window.__id = d.id;
           d.sites = ["rbxcdn.com", "rbxinfra.com"]; c._rendre(); await new Promise((r) => setTimeout(r, 60));
           const cases = tous('[data-el-case="' + d.id + '"]'); if (cases.length !== 3) throw new Error(cases.length + " cases au lieu de 3");
           cases.find((x) => x.value === "rbxinfra.com").click();
           un('button[data-acces="' + d.id + '"][data-mode="exception"]').click(); true`,
    enCours: `boutonEnCours()`,
    verifierAppel: `dernier().corps.action === "acces" && dernier().corps.mode === "exception" && JSON.stringify(dernier().corps.elements) === JSON.stringify(["app:com.roblox.client", "rbxcdn.com"])`,
    repondre: `(() => { const n = JSON.parse(JSON.stringify(D["pc_parental/maison"])); n.demandes = n.demandes.filter((d) => d.id !== window.__id);
               n.retour = "Nolan : Roblox est une exception. 1 site lié s'ouvre avec lui."; return n; })()`,
    apres: `!un('button[data-acces="' + window.__id + '"]') && /exception/.test(statut())`,
  },
  {
    nom: "Personne — Autorisations : accorder en exception", carte: "vision-maison-card", donnees: "maison",
    config: { mode: "personne", personne: "person.lou", chemin: "/vision-maison" },
    agir: `if (!tous("h3").some((h) => /Autorisations/.test(h.textContent))) throw new Error("pas de section Autorisations");
           const f = un("[data-aut-ajout]"); f.querySelector("[data-aut-nom]").value = "roblox";
           f.querySelector("[data-aut-choix]").value = "exception"; f.querySelector("button[type=submit]").click(); true`,
    enCours: `boutonEnCours()`,
    verifierAppel: `dernier().corps.action === "accorder" && dernier().corps.mode === "exception" && dernier().corps.nom === "roblox" && !!dernier().corps.pc`,
    repondre: `(() => { const n = JSON.parse(JSON.stringify(D["pc_parental/maison"]));
               const lou = n.personnes.find((p) => p.entite === "person.lou");
               lou.autorisations = [...(lou.autorisations || []), { genre: "apps", nom: "roblox", mode: "exception", lies: ["roblox.com", "rbxcdn.com"], lies_valides: [], proposes: [] }];
               n.retour = "Nolan a accordé roblox en exception, sur toutes ses machines."; return n; })()`,
    apres: `!!un('[data-aut-retirer="roblox"][data-aut-mode="exception"]') && tous(".aut-groupe").some((g) => /Exceptions/.test(g.textContent))
            && /rbxcdn\.com/.test(R().textContent) && /exception/.test(statut())`,
  },
  {
    nom: "Personne — Autorisations : retirer un « toujours disponible »", carte: "vision-maison-card", donnees: "maison",
    config: { mode: "personne", personne: "person.lou", chemin: "/vision-maison" },
    agir: `if (!tous(".aut-groupe").some((g) => /Toujours disponible/.test(g.textContent))) throw new Error("groupe Toujours disponible absent");
           un('[data-aut-retirer="minecraft"]').click(); true`,
    enCours: `boutonEnCours()`,
    verifierAppel: `dernier().corps.action === "retirer_autorisation" && dernier().corps.nom === "minecraft" && dernier().corps.mode === "toujours"`,
    repondre: `(() => { const n = JSON.parse(JSON.stringify(D["pc_parental/maison"]));
               const lou = n.personnes.find((p) => p.entite === "person.lou"); lou.autorisations = [];
               n.retour = "Nolan a retiré l'autorisation de minecraft."; return n; })()`,
    apres: `!un('[data-aut-retirer="minecraft"]') && /retiré/.test(statut())`,
  },
  {
    nom: "Personne — reprise des autorisations : voir ce qui change", carte: "vision-maison-card", donnees: "maison",
    config: { mode: "personne", personne: "person.lou", chemin: "/vision-maison" },
    agir: `un('[data-reprise="simuler"]').click(); true`,
    enCours: `boutonEnCours()`,
    verifierAppel: `dernier().corps.action === "reprise_autorisations" && dernier().corps.etape === "simuler"`,
    repondre: `(() => { const n = JSON.parse(JSON.stringify(D["pc_parental/maison"]));
               n.retour = "Reprise des autorisations : ce qui change — Lou : minecraft restera ouvert aussi pendant les plages."; return n; })()`,
    apres: `/Reprise des autorisations/.test(statut()) && !!un('[data-reprise="appliquer"]')`,
  },
  // ---------------------------------------------------------------- carte Widgets HA
  {
    nom: "Widgets HA — retirer une entité", carte: "vision-widgets-card", donnees: "widgets",
    agir: `un("button.retirer").click(); true`,
    enCours: `boutonEnCours() || tous("button.retirer").every((b) => b.disabled)`,
    repondre: `D["pc_parental/widgets/admin"]`,
    apres: `/Enregistré/.test(statut()) && !boutonEnCours()`,
  },
  {
    nom: "Widgets HA — échec en clair", carte: "vision-widgets-card", donnees: "widgets",
    agir: `un("button.retirer").click(); true`,
    enCours: `boutonEnCours() || tous("button.retirer").every((b) => b.disabled)`,
    echouer: `Object.assign(new Error("Bad Request"), { status_code: 400, body: { ok: false, error: "json" } })`,
    apres: `(${FRANCAIS})(statutKo()) && !tous("button.retirer").every((b) => b.disabled)`,
  },
  // ---------------------------------------------------------------- carte Apparence
  {
    nom: "Apparence — choisir une palette", carte: "vision-apparence-card", donnees: "apparence",
    agir: `tous("[data-palette]")[1].click(); true`,
    enCours: `boutonEnCours()`,
    repondre: `D["pc_parental/apparence"]`,
    apres: `/Appliqué/.test(statut())`,
  },
  {
    nom: "Apparence — échec en clair", carte: "vision-apparence-card", donnees: "apparence",
    agir: `choisir(un("vision-choix[data-mode]"), "sombre"); true`,
    enCours: `window.__appels.length === 1`,
    echouer: `Object.assign(new Error("x"), { status_code: 500, body: {} })`,
    apres: `(${FRANCAIS})(statutKo())`,
  },
  // ---------------------------------------------------------------- carte Écran de veille
  {
    nom: "Écran de veille — enregistrer", carte: "vision-veille-card", donnees: "veille",
    agir: `window.__carte._modifie = true; window.__carte._rendre(); un('[data-action="enregistrer"]').click(); true`,
    enCours: `boutonEnCours()`,
    repondre: `D["pc_parental/veille/tableaux"]`,
    apres: `/Enregistré/.test(statut()) && !boutonEnCours()`,
  },
  {
    nom: "Écran de veille — échec en clair", carte: "vision-veille-card", donnees: "veille",
    agir: `window.__carte._modifie = true; window.__carte._rendre(); un('[data-action="enregistrer"]').click(); true`,
    enCours: `boutonEnCours()`,
    echouer: `Object.assign(new Error("Internal Server Error"), { status_code: 500 })`,
    apres: `(${FRANCAIS})(statutKo()) && !/Internal/.test(statutKo())`,
  },
  // ---------------------------------------------------------------- carte Tableaux (veille)
  {
    nom: "Tableaux de veille — enregistrer", carte: "vision-dashboards-card", donnees: "dashboards",
    agir: `window.__carte._modifie = true; window.__carte._rendre(); un('[data-a="enregistrer"]').click(); true`,
    enCours: `boutonEnCours()`,
    repondre: `D["pc_parental/veille/dashboards"]`,
    apres: `/Enregistré/.test(statut())`,
  },
  {
    nom: "Tableaux de veille — échec en clair", carte: "vision-dashboards-card", donnees: "dashboards",
    agir: `window.__carte._modifie = true; window.__carte._rendre(); un('[data-a="enregistrer"]').click(); true`,
    enCours: `boutonEnCours()`,
    echouer: `Object.assign(new Error("Unauthorized"), { status_code: 401 })`,
    apres: `(${FRANCAIS})(statutKo()) && !/Unauthorized/.test(statutKo())`,
  },
  // 1.93.2 (retour d'essai) : ouvrir une catégorie (vue par élément : lecture lente au serveur, puis second dessin) ne fait plus
  // remonter la page, et le bouton garde le focus ; pareil pour la fermer.
  {
    nom: "Défilement — ouvrir puis fermer une catégorie (par élément)", carte: "vision-maison-card", donnees: "maison",
    config: { mode: "personne", personne: "person.lou", chemin: "/vision-maison" }, defilement: true,
    agir: `const h = window.__carte._hass; const lire = h.callApi;
           h.callApi = async (...a) => { await new Promise((r) => setTimeout(r, 250)); return lire(...a); };
           await preparerDefilement('button.ouvrir[data-cat="Jeux"]');
           await new Promise((r) => setTimeout(r, 700));
           if (!placeGardee()) throw new Error("à l'ouverture : " + JSON.stringify(window.__mesure()));
           un('button.ouvrir[data-cat="Jeux"]').focus(); un('button.ouvrir[data-cat="Jeux"]').click(); true`,
    apres: `placeGardee() && un('button.ouvrir[data-cat="Jeux"]').getAttribute("aria-expanded") === "false"`,
  },
  {
    // Le cas de l'auteur : une catégorie ouverte, puis on coupe une catégorie (un geste) : l'ancienne carte vidait toutes
    // les catégories ouvertes (« Chargement… »), la page raccourcissait et repartait en haut.
    nom: "Défilement — couper une catégorie, une autre étant ouverte (par élément)", carte: "vision-maison-card", donnees: "maison",
    config: { mode: "personne", personne: "person.lou", chemin: "/vision-maison" }, defilement: true,
    agir: `const h = window.__carte._hass; const lire = h.callApi;
           h.callApi = async (m, c, corps) => { if (corps && corps.action === "categorie") await new Promise((r) => setTimeout(r, 250)); return lire(m, c, corps); };
           const cats = tous("button.ouvrir[data-cat]").map((b) => b.dataset.cat);
           for (const n of cats) { un('button.ouvrir[data-cat="' + CSS.escape(n) + '"]').click(); await new Promise((r) => setTimeout(r, 350)); }
           const inter = tous("input[data-etq]").filter((c) => !c.disabled).pop(); if (!inter) throw new Error("aucun interrupteur de catégorie");
           window.__etq = inter.dataset.etq;
           await preparerDefilement('input[data-etq="' + CSS.escape(window.__etq) + '"]'); true`,
    repondre: `D["pc_parental/maison"]`,
    apres: `placeGardee()`,
  },
  {
    nom: "Défilement — cocher une catégorie (Autorisations)", carte: "vision-etiquettes-card", donnees: "catalogue", defilement: true,
    agir: `un('button[data-vue="etiquettes"]').click(); await new Promise((r) => setTimeout(r, 150));
           const cases = tous("input[data-categorie]"); if (!cases.length) throw new Error("aucune catégorie");
           const c = cases[cases.length - 1]; window.__cat = c.dataset.categorie;
           await preparerDefilement('input[data-categorie="' + CSS.escape(window.__cat) + '"]'); true`,
    repondre: `D["pc_parental/catalogue"]`,
    apres: `placeGardee()`,
  },
  // ---------------------------------------------------------------- carte Étiquettes
  {
    nom: "Étiquettes — cocher une catégorie", carte: "vision-etiquettes-card", donnees: "catalogue",
    agir: `window.__p = window.__carte._cocherCategorie("Jeux", true); true`,
    enCours: `window.__appels.length === 1`,
    repondre: `D["pc_parental/catalogue"]`,
    apres: `/Catégorie ajoutée/.test(statut())`,
  },
  // Lot 1 (HA-02) : sur un téléphone (carte de 304 px, soit un écran de 320), rien ne déborde à droite, les décisions
  // sont des boutons texte (« Fermer », « Autoriser », « Plus tard ») d'au moins 44 px, et on peut trancher.
  {
    nom: "Autorisations — trancher sur un écran de 320 px", carte: "vision-etiquettes-card", donnees: "catalogue",
    agir: `document.getElementById("zone").style.width = "304px";
           await new Promise((r) => setTimeout(r, 200));
           const zone = document.getElementById("zone").getBoundingClientRect();
           const deborde = tous("*").filter((e) => { const r = e.getBoundingClientRect(); return r.width && (r.right > zone.right + 0.5 || r.left < zone.left - 0.5); });
           if (deborde.length) throw new Error("déborde : " + deborde.slice(0, 4).map((e) => e.className || e.tagName).join(", "));
           // 15-10 : la décision est un <vision-choix> (3 options : des étiquettes quand elles tiennent, sinon une liste).
           const choix = tous("vision-choix[data-decider]");
           if (!choix.length) throw new Error("aucune ligne à trancher");
           for (const c of choix) {
             if (c.getBoundingClientRect().right > zone.right + 0.5) throw new Error("décision qui déborde");
             for (const b of c.shadowRoot.querySelectorAll("[data-cible]")) if (b.getBoundingClientRect().height < 44) throw new Error("cible de " + b.getBoundingClientRect().height + " px : " + b.textContent);
           }
           const montrees = choix.filter((c) => c.montreEtiquettes);
           if (montrees.length && libelles(montrees[0]).join("|") !== "Fermer|Autoriser|Plus tard") throw new Error("décisions sans texte : " + libelles(montrees[0]).join("|"));
           const c = choix.find((x) => x.valeur !== "bloquer");
           window.__nom = c.dataset.decider;
           choisir(c, "bloquer"); true`,
    enCours: `window.__appels.length === 1`,
    verifierAppel: `dernier().chemin === "pc_parental.decider_elements" && dernier().corps.decision === "bloquer" && dernier().corps.elements[0].nom === window.__nom.split("|").slice(1).join("|")`,
    repondre: `undefined`,
    apres: `/fait/.test(statut())`,
  },
  {
    nom: "Étiquettes — service refusé en clair", carte: "vision-etiquettes-card", donnees: "catalogue",
    agir: `window.__p = window.__carte._appeler("create_tag", { etiquette: "Essai" }); true`,
    enCours: `window.__appels.length === 1`,
    echouer: `new Error("Cette étiquette existe déjà.")`,
    apres: `/existe déjà/.test(statutKo())`,
  },
  // ---------------------------------------------------------------- carte Logiciels (processus)
  {
    nom: "Logiciels — valider un élément", carte: "vision-processus-card", donnees: "processus",
    agir: `window.__p = window.__carte._decider(["cle-essai"], "valider"); true`,
    enCours: `window.__appels.length === 1 && dernier().chemin === "pc_parental.processus_decider"`,
    repondre: `null`,
    apres: `/C'est fait/.test(statut())`,
  },
  {
    nom: "Logiciels — échec en clair", carte: "vision-processus-card", donnees: "processus",
    agir: `window.__p = window.__carte._decider(["cle-essai"], "garder"); true`,
    enCours: `window.__appels.length === 1`,
    echouer: `new Error("Élément inconnu de Vision.")`,
    apres: `/inconnu/.test(statutKo())`,
  },
  // ---------------------------------------------------------------- carte Planning
  {
    nom: "Planning — enregistrer une plage", carte: "vision-planning-card", donnees: "planning", config: { entity: "__auto" },
    agir: `window.__p = window.__carte._svc("update_schedule", { id: "x" }); true`,
    enCours: `window.__appels.length === 1 && dernier().chemin === "pc_parental.update_schedule"`,
    repondre: `null`,
    apres: `/Enregistré/.test(statut())`,
  },
  {
    nom: "Planning — échec en clair", carte: "vision-planning-card", donnees: "planning", config: { entity: "__auto" },
    agir: `window.__p = window.__carte._svc("update_schedule", { id: "x" }); true`,
    enCours: `window.__appels.length === 1`,
    echouer: `new Error("Cette plage n'existe plus.")`,
    apres: `/n'existe plus/.test(statutKo())`,
  },
];

async function main() {
  const serveur = createServer((req, res) => {
    const chemin = decodeURIComponent(new URL(req.url, "http://x").pathname).replace(/^\/+/, "");
    if (chemin.includes("..")) { res.writeHead(403); res.end(); return; }
    let contenu;
    try { contenu = readFileSync(join(DOSSIER, chemin)); } catch { res.writeHead(404); res.end(); return; }
    const ext = chemin.slice(chemin.lastIndexOf("."));
    res.writeHead(200, { "Content-Type": TYPES[ext] || "application/octet-stream" });
    res.end(contenu);
  });
  await new Promise((ok) => serveur.listen(0, "127.0.0.1", ok));
  PORT_HTTP = serveur.address().port;
  const profil = mkdtempSync(join(tmpdir(), "vision-clics-"));
  const nav = spawn(NAVIGATEUR, ["--headless=new", "--disable-gpu", `--user-data-dir=${profil}`, `--remote-debugging-port=${PORT_CDP}`,
    "--no-first-run", "--window-size=1280,1600", "about:blank"], { stdio: "ignore" });
  let echecs = 0;
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
    const evaluer = async (js, bloc = false) => {
      const corps = bloc ? js : `return (${js});`;
      const r = await cmd("Runtime.evaluate", { expression: `(async () => { ${OUTILS}; ${corps} })()`, awaitPromise: true, returnByValue: true });
      if (r.result?.exceptionDetails) throw new Error(String(r.result.exceptionDetails.exception?.description || r.result.exceptionDetails.text).slice(0, 300));
      return r.result?.result?.value;
    };
    await cmd("Runtime.enable"); await cmd("Page.enable");
    for (const s of SCENARIOS) {
      const res = { nom: s.nom, ok: false };
      try {
        exceptions.length = 0;
        const url = `http://127.0.0.1:${PORT_HTTP}/banc.html?carte=${s.carte}&donnees=${s.donnees}&config=${encodeURIComponent(JSON.stringify(s.config || {}))}`;
        await cmd("Page.navigate", { url });
        let pret = false;
        for (let i = 0; i < 60 && !pret; i++) { await pause(100); pret = await evaluer(`!!(window.__pret && R() && R().querySelector("button"))`).catch(() => false); }
        if (!pret) throw new Error("carte non affichée");
        await pause(150);
        if (s.defilement) {
          // 1.93.2 : pas de « En cours… » à guetter ; un geste éventuel reçoit sa réponse, puis la place est vérifiée.
          await evaluer(s.agir, true);
          await pause(250);
          if (s.repondre !== undefined) {
            if (!(await evaluer(`window.__appels.length >= 1`))) throw new Error("aucun appel au serveur");
            await evaluer(`(dernier().ok(${s.repondre}), true)`);
          }
          await pause(700);
          if (!(await evaluer(s.apres))) throw new Error("défilement : " + JSON.stringify(await evaluer(`window.__mesure()`)));
          if (exceptions.length) throw new Error("exception : " + exceptions[0]);
          res.ok = true;
          console.log(JSON.stringify(res));
          continue;
        }
        await evaluer(s.agir, true);
        await pause(80);
        if (!(await evaluer(s.enCours))) throw new Error("pas de retour immédiat au clic : " + (await evaluer(`R().textContent.replace(/\\s+/g, " ").slice(0, 200)`)));
        if (!(await evaluer(`window.__appels.length >= 1`))) throw new Error("aucun appel au serveur");
        if (s.verifierAppel && !(await evaluer(s.verifierAppel))) throw new Error("appel inattendu : " + JSON.stringify(await evaluer(`dernier().corps`)));
        await pause(300);  // le serveur « réfléchit » : l'état en cours doit tenir
        if (!(await evaluer(s.enCours))) throw new Error("l'état « en cours » a disparu avant la réponse");
        if (s.echouer) await evaluer(`(dernier().ko(${s.echouer}), true)`);
        else await evaluer(`(dernier().ok(${s.repondre}), true)`);
        await pause(400);
        if (!(await evaluer(s.apres))) throw new Error("affichage après réponse : statut « " + (await evaluer(`statut()`)) + " »");
        if (exceptions.length) throw new Error("exception : " + exceptions[0]);
        res.ok = true;
        res.statut = await evaluer(`statut()`);
      } catch (e) {
        res.erreur = String(e.message || e);
        echecs++;
      }
      console.log(JSON.stringify(res));
    }
    ws.close();
  } finally {
    serveur.close();
    // Tout l'arbre : sous Windows, kill() ne ferme que le parent et laisse les dizaines d'Edge enfants.
    if (process.platform === "win32") {
      spawnSync("taskkill", ["/PID", String(nav.pid), "/T", "/F"], { stdio: "ignore" });
      // Edge détache une partie de ses processus : on ferme aussi tous ceux qui portent ce profil.
      spawnSync("powershell", ["-NoProfile", "-NonInteractive", "-Command",
        `Get-CimInstance Win32_Process -Filter "Name = 'msedge.exe'" | Where-Object { $_.CommandLine -like '*${String(profil).split(/[\/]/).pop()}*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }`], { stdio: "ignore" });
    } else nav.kill();
    await pause(300);
    try { rmSync(profil, { recursive: true, force: true }); } catch { /* le profil du navigateur peut rester verrouillé un instant */ }
  }
  console.log(JSON.stringify({ fin: true, echecs }));
  process.exit(echecs ? 1 : 0);
}

main().catch((e) => { console.log(JSON.stringify({ fin: true, echecs: 1, erreur: String(e) })); process.exit(1); });
