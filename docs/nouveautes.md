# « Quoi de neuf » — notes de version pour les appareils

Après une mise à jour, à la première ouverture, l'appli (téléphone, télé) ou l'agent PC montre une
fenêtre « Quoi de neuf dans Vision X » : 3 à 5 points simples, un bouton OK, une seule fois par version.
Home Assistant fournit le contenu ; ce document décrit les fichiers et l'API.

## Fichiers de notes

Un fichier par version, livré avec l'intégration : `pc_parental/notes_version/<version>.json`.

```json
{
  "version": "2.2.12",
  "date": "2026-10-10",
  "points": [
    {
      "cibles": ["ha", "telephone"],
      "texte": {"fr": "…", "en": "…", "de": "…", "es": "…", "it": "…", "nl": "…"}
    },
    {
      "cibles": ["tele"],
      "public": "decideurs",
      "texte": {"fr": "…", "en": "…", "de": "…", "es": "…", "it": "…", "nl": "…"}
    }
  ]
}
```

- `version` : la version de Vision (celle de l'intégration, qui livre aussi l'APK et l'agent PC) ;
  le nom du fichier est la même chaîne.
- `cibles` : où le point a un sens — `ha` (tableau et pages servies par Home Assistant),
  `telephone` (téléphones et tablettes), `tele`, `pc`.
- `public` (facultatif) : `tous` (défaut) ou `decideurs` (seulement les appareils dont le rôle a « gérer »).
- `module` (facultatif) : un module optionnel, par exemple `son`. Le point n'est servi que si ce module est allumé : un module
  coupé ne laisse aucune trace, notes de version comprises.
- `texte` : les 6 langues (fr, en, de, es, it, nl). Il en manque une : repli en anglais, puis en français.
- Règles (vérifiées par `essai/nouveautes_test.py`) : 3 à 5 points par fichier, une phrase courte chacun,
  sans jargon ; vocabulaire neutre (Décideur, Demandeur, Lieu — jamais les mots de la liste du test).
- Un fichier d'une version plus récente que l'intégration installée peut être préparé d'avance : il ne
  sort qu'une fois cette version installée.

## API pour les applis

### Indice dans le relevé (`POST /api/pc_parental/poll`)

- Champs facultatifs envoyés par l'appareil :
  - `langue` : langue de l'appareil (`fr`, `de-CH`…), retenue dans sa fiche ;
  - `nouveautes_vu` : « vu jusqu'à la version X ».
- Champ ajouté à la réponse, **seulement** s'il reste des notes à voir pour cet appareil :
  `"nouveautes": "2.2.12"` (la version la plus récente à montrer). Les anciens clients l'ignorent.

### Vue dédiée `POST /api/pc_parental/nouveautes`

Même porte que les autres vues d'appareil : `id` et `secret` dans le corps (POST et non GET, pour que
le secret ne passe jamais dans une adresse). 401 si l'appareil n'est pas reconnu.

Corps :

| champ | | |
|---|---|---|
| `id`, `secret` | obligatoires | l'appareil |
| `langue` | facultatif | sinon celle donnée au relevé, sinon celle de Home Assistant |
| `cible` | facultatif | `telephone`, `tele`, `pc`, `ha` ; sinon déduite du type de l'appareil |
| `version_appli` | facultatif | informatif (non utilisé : les notes suivent la version de Vision) |
| `vu` | facultatif | « vu jusqu'à X » — enregistré avant de répondre |

Réponse :

```json
{
  "ok": true,
  "version": "2.2.12",
  "cible": "telephone",
  "langue": "fr",
  "vu": "2.2.10",
  "nouveautes": [
    {"version": "2.2.12", "date": "2026-10-10", "points": [
      "Sur téléphone, la page invité montre les pochettes des titres.",
      "L'en-tête de la page invité reste en haut quand on fait défiler.",
      "La page invité suit le thème clair ou sombre."]},
    {"version": "2.2.11", "date": "2026-10-10", "points": [
      "Au-delà de 50 titres, Vision demande avant de tout ajouter.",
      "Les playlists automatiques affichent leur nombre de titres."]}
  ]
}
```

`nouveautes` : de la plus récente à la plus ancienne, trois versions au plus ; une version sans aucun
point pour cet appareil n'y figure pas. Liste vide : aucune fenêtre.

### Parcours conseillé

1. Le relevé contient `nouveautes` → à la prochaine ouverture de l'écran principal, appeler la vue.
2. Afficher la fenêtre (titre avec `nouveautes[0].version`, les points de chaque version).
3. Au clic sur OK : `{"id", "secret", "vu": nouveautes[0].version}` (vue dédiée ou champ
   `nouveautes_vu` du relevé suivant).

## Règle du « vu »

- Stocké par appareil dans sa fiche (`nouveautes_vu`), donc persistant.
- Ne recule jamais ; une version au-delà de celle installée est ramenée à celle-ci.
- **Appareil qui n'a encore rien vu** (neuf, ou appli d'avant cette fonction) : seulement la dernière
  version. L'historique entier ne lui apprendrait rien et ferait un mur de texte ; la dernière version
  permet en revanche aux appareils déjà en place de voir la fenêtre dès le déploiement de cette fonction.
