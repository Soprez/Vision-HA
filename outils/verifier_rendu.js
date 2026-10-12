/*
 * Vision — vérification du rendu réel d'un onglet du tableau de bord (1.97.1).
 *
 * À exécuter dans la page Home Assistant ouverte sur un onglet (navigateur connecté, après chaque mise en ligne),
 * une fois l'onglet chargé. Rend { vue, cartes, erreurs: [message…], ok } ; ok = false dès qu'une carte est en
 * erreur (« Erreur de configuration », élément personnalisé introuvable…) ou qu'aucune carte n'est affichée.
 * Léger : on s'arrête à chaque carte (hui-card) sans parcourir son contenu, pour ne pas figer les longues listes.
 */
(() => {
  const ERREURS = ["HUI-ERROR-CARD", "HUI-WARNING", "HUI-WARNING-ELEMENT"];
  const cartes = [];
  const erreurs = [];
  const enfants = (e) => [...e.children, ...(e.shadowRoot ? e.shadowRoot.children : [])];
  const marcher = (e) => {
    if (e.tagName === "HUI-CARD") {
      cartes.push(e);
      for (const c of enfants(e)) {
        if (ERREURS.includes(c.tagName)) erreurs.push(String((c._config && c._config.message) || c.textContent || "?").trim());
        // Piles et grilles : leurs cartes filles sont aussi des hui-card ou des cartes directes.
        if (/^HUI-(VERTICAL|HORIZONTAL)-STACK-CARD$|^HUI-GRID-CARD$|^HUI-CONDITIONAL-CARD$/.test(c.tagName)) marcher(c);
      }
      return;
    }
    if (ERREURS.includes(e.tagName)) {
      erreurs.push(String((e._config && e._config.message) || e.textContent || "?").trim());
      return;
    }
    for (const c of enfants(e)) marcher(c);
  };
  marcher(document.documentElement);
  return JSON.stringify({ vue: location.pathname, cartes: cartes.length, erreurs, ok: cartes.length > 0 && !erreurs.length });
})();
