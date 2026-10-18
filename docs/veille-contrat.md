# Veille — contrat entre Home Assistant, l'agent PC et la télé (branche `veille-lot`)

Ce que l'intégration envoie aux veilles (PC : `veille.json` écrit par `Sync-Veille` ; télé : relevé et
`/api/pc_parental/veille/...`). Tout champ nouveau est **facultatif** : une veille plus ancienne l'ignore, une
veille récente face à un Home Assistant ancien retombe sur l'affichage actuel.

## 1. Les cases d'un tableau

Chaque case garde ses champs actuels (`entite`, `nom`, `rendu`, `texte`, `valeur`, `unite`…) et peut porter
`genre` :

| `genre` | Champs | Source dans Home Assistant |
|---|---|---|
| absent / `valeur` | inchangés | toute entité lisible |
| `meteo` | `etat` (condition HA), `temperature`, `unite`, `previsions`: ≤ 5 × `{quand, etat, max, min}` | `weather.*` (`weather.get_forecasts`, quotidien) |
| `agenda` | `evenements`: ≤ 8 × `{debut, fin, titre, journee}` sur 7 jours | `calendar.*` (`calendar.get_events`) — emploi du temps |
| `image` | `url` (servie par l'intégration, jamais d'adresse interne brute), `elements`: ≤ 12 × `{x, y, texte}` (x, y en % de l'image) | carte `picture-elements` (fond + `state-label` / `state-icon` résolus en texte) |
| `camera` | `entite`, `nom`, `direct` : entité du flux léger `*_sub` si elle existe, sinon vide | `camera.*` (Frigate compris) |
| `iframe` | `url`, `nom` | carte `iframe` (télé seulement ; le PC montre le nom, sans charger la page) |
| `clips` | `liste`: ≤ 8 × `{camera, quand, objet, vignette}` | Frigate, « Derniers passages » |
| `radar` | `url` (image servie par l'intégration) | radar de pluie configuré dans HA |

Les dates sont en ISO 8601 avec fuseau. Les adresses d'images passent par l'intégration (même règle que les
caméras : ticket de l'appareil, jamais de jeton Home Assistant sur l'appareil).

## 2. Règles côté Home Assistant

- **Exclus de la veille** : les entités d'action et de saisie (`button`, `input_button`, `script`, `scene`,
  `automation`, `input_*` sauf `input_boolean`, `number`, `select`, `text`, `notify`, `tts`, `conversation`) ne sont
  jamais envoyées (liste exacte : §4.4).
- **Unités dans la langue de Home Assistant** : `d` → `j` en français (`L/d` → `L/j`), `w` → `sem.`, etc.
- **Dates lisibles** : une valeur qui est une date ISO part déjà formatée (« 9 oct., 08:00 »).
- **Défauts sûrs** : rien en dur (aucune entité, aucun lieu) ; ce qui n'est pas configuré n'est pas envoyé.

## 3. Musique de fond de la veille (télé)

Son seul, jamais d'image : la télé ne doit pas devenir son propre lecteur Cast (l'appli Music Assistant prendrait
l'écran). Le choix (rien par défaut) se fait dans les réglages de la veille de l'appareil ; Home Assistant fournit la
liste et l'adresse du flux audio à lire par le lecteur de Vision, ou dit « indisponible » si Music Assistant n'est
pas là.

## 4. Format exact (côté Home Assistant, branche `veille-lot`)

### 4.1 Où arrivent les cases

Une seule source pour le PC et la télé : `POST /api/pc_parental/veille` (`{id, secret, ecran}`) →
`tableaux.liste[]`. Un tableau tiré d'un tableau de bord (`code: "dash"`) porte `sections[].cases[]` ; un
tableau composé (`code: "entites"`) porte `cases[]`. Les cases sont fabriquées par le même code
(`veille_tableaux._case` pour les valeurs, `veille_cases.resoudre` pour les cases typées), donc le même format
partout. L'agent PC recopie `tableaux` tel quel dans `veille.json`.

**Compatibilité** : toute case typée garde aussi `rendu: "texte"` et un `texte` résumé (« Ensoleillé · 18° »,
« 14:00 Piscine »…). Une veille qui ignore `genre` montre donc ce texte, jamais un état brut.

Champs communs à toute case : `genre` (absent pour une valeur), `entite`, `nom`, `rendu`, `texte` ; dans un
tableau `dash` aussi `carte`, `largeur` (1–12), `hauteur` (1–3).

### 4.2 Cases typées

```jsonc
// meteo — carte weather-forecast (prévisions quotidiennes, gardées 10 min côté HA)
{"genre": "meteo", "entite": "weather.x", "nom": "Météo", "etat": "sunny", "etat_texte": "Ensoleillé",
 "temperature": 18.4, "unite": "°C",
 "previsions": [{"quand": "2026-10-12T00:00:00+02:00", "etat": "rainy", "max": 17, "min": 9, "pluie": 60}]}  // ≤ 5

// agenda — carte calendar (7 jours, gardé 5 min)
{"genre": "agenda", "entites": ["calendar.a"], "nom": "Agenda",
 "evenements": [{"debut": "2026-10-12T14:00:00+02:00", "fin": "2026-10-12T15:00:00+02:00", "titre": "Piscine",
                 "journee": false, "calendrier": "Agenda"}]}   // ≤ 8 ; journée entière : debut/fin = "AAAA-MM-JJ"

// image — carte picture-elements (ou picture) avec un fond /local/… ; éléments state-label / state-icon en texte
{"genre": "image", "nom": "Plan", "url": "/api/pc_parental/veille/media/<cle>",
 "elements": [{"x": 42.0, "y": 61.5, "texte": "21,5 °C"}],          // ≤ 12, x/y en % du fond (centre du texte)
 "calques": [{"x": 50, "y": 50, "largeur": 100, "url": "/api/pc_parental/veille/media/<cle>", "entite": "camera.x"}]}

// radar — picture-elements = fond + une seule image de caméra par-dessus, sans texte (recette docs/radar-de-pluie.md)
{"genre": "radar", "nom": "Radar", "url": "/api/pc_parental/veille/media/<cle>",
 "calque": {"url": "/api/pc_parental/veille/media/<cle>", "entite": "camera.radar"}}

// camera — picture-entity / picture-glance / tile… sur une camera.* (Frigate compris)
{"genre": "camera", "entite": "camera.x", "nom": "Jardin", "direct": "camera.x_sub",   // "" sans flux léger
 "url": "/api/pc_parental/veille/media/<cle>"}           // image fixe ; le flux : POST /veille/flux {entite}

// iframe — carte iframe : seulement une adresse http(s) absolue écrite dans la carte (sinon la case n'est pas envoyée)
{"genre": "iframe", "url": "https://…", "nom": "Trafic"}

// clips — carte custom:frigate-card / custom:advanced-camera-card, seulement si l'intégration Frigate est chargée
{"genre": "clips", "nom": "Derniers passages",
 "liste": [{"camera": "Jardin", "entite": "camera.x", "quand": "2026-10-11T05:24:39+02:00", "objet": "person",
            "objet_nom": "Personne", "vignette": "/api/pc_parental/veille/media/<cle>"}]}   // ≤ 8, du plus récent
```

« Derniers passages » = la dernière image de chaque objet par caméra que Frigate publie dans Home Assistant
(entités `image.*` de l'intégration Frigate, liées à l'appareil de la caméra). Ce n'est pas l'historique complet des
événements Frigate (aucune requête au serveur Frigate à chaque relevé).

### 4.3 Images : `/api/pc_parental/veille/media/<cle>`

Toute `url` / `vignette` ci-dessus. La `<cle>` est opaque, donnée **à cet appareil** par son dernier relevé
(valable 30 min, renouvelée à chaque relevé) ; un autre appareil, ou une clé inconnue, reçoit 404.

- `POST` avec `{"id", "secret"}` (télé) ou `{"ticket"}` (fenêtre de veille PC, `ticket_images` du relevé) ;

Réponse : l'image (`image/jpeg`, `image/png`…), `Cache-Control: no-store` pour une caméra, `max-age=600` pour un
fond. Sources possibles, toutes choisies par l'administrateur dans la carte : un fichier du dossier `www` de Home
Assistant (`/local/…`), l'image du moment d'une caméra, une image Frigate. Jamais une adresse externe ni interne
relayée.

### 4.4 Exclusions, unités, dates (valeurs)

- Domaines jamais envoyés (case retirée, pas « masquée ») : `button`, `input_button`, `script`, `scene`,
  `automation`, `input_number`, `input_text`, `input_select`, `input_datetime`, `number`, `select`, `text`,
  `notify`, `tts`, `conversation`. `input_boolean` reste (il se lit comme un interrupteur). Valable pour les
  tableaux composés, les tableaux de bord et les tuiles (`tuiles[]`).
- `unite` dans la langue de Home Assistant (`hass.config.language`) : en français `d` → `j`, `L/d` → `L/j`,
  `w` → `sem.`, `mo` → `mois`, `y` → `an` ; en anglais et autres langues, inchangée.
- Valeur date ISO (capteur `timestamp`, `input_button`…) : `rendu: "texte"`, `valeur: null`, `texte` lisible
  dans la langue (« 9 oct., 08:00 » ; « Oct 9, 08:00 » ; autres langues : « 2026-10-09 08:00 »), heure locale,
  année seulement si ce n'est pas l'année en cours.

### 4.5 Musique de fond (télé) : `POST /api/pc_parental/veille/musique`

Authentification : `{"id", "secret"}` de la télé (comme le relevé). Réservé aux télés (`type` télé).

| `action` | Corps en plus | Réponse |
|---|---|---|
| `choix` | — | `{ok, disponible, raison, lecteur, choix: [{uri, nom, type, source}]}` (playlists puis radios de la bibliothèque Music Assistant, ≤ 40, gardé 10 min) |
| `jouer` | `uri`, `type` (`playlist` / `radio`) | `{ok, lecteur}` ou `{ok: false, error: "indisponible" \| "lecteur" \| "choix"}` |
| `arreter` | — | `{ok}` (pause du lecteur, seulement si c'est la veille qui l'a lancé) |

**Comment le son arrive sans Cast** : Music Assistant ne donne pas d'adresse de flux audio sans lecteur. Home
Assistant joue donc le choix sur le lecteur Music Assistant **de Vision sur cette télé** (« <nom de la télé> ·
Vision », celui où l'appli joue déjà le son elle-même), jamais sur le lecteur Cast. `disponible: false` avec
`raison` (`music_assistant` absent, `lecteur` absent ou hors ligne) : l'appli retombe sur ses radios / flux
(`reglages.radios`, `reglages.musique`). Rien n'est joué tant que la télé ne demande pas `jouer` (rien par défaut).
Une lecture lancée par la veille ne compte pas comme « musique en cours » (elle n'empêche pas la veille) ;
`arreter` à la sortie de veille.

### 4.6 Info TV éphémère, styles d'horloge

Rien de nouveau côté Home Assistant : le style d'horloge et la petite info qui disparaît seule sont des réglages
locaux de la télé ; le thème reste `reglages.theme` (`Beige`, `Sombre`…).

### 4.7 Caméras des cases typées (relecture sécurité)

Une caméra posée dans une case typée n'est permise (`/veille/image`, `/veille/flux`, `/veille/media`) qu'à
l'appareil pour lequel la case a été résolue, avec son flux `_sub`, pendant 30 min renouvelées à chaque relevé.
Elle n'entre jamais dans la liste commune « Écran de veille : caméras ». Pas d'accès `GET` aux médias : le ticket
ne passe jamais dans une adresse.

## 5. Points ouverts

- Cases typées seulement dans les tableaux tirés des tableaux de bord (`code: "dash"`) ; un tableau composé
  (`code: "entites"`) reste fait de valeurs.
- `clips` sans historique : une ligne par (caméra, objet), la plus récente ; une image Frigate jamais mise à jour
  depuis le démarrage porte l'heure du démarrage.

- `direct` des caméras : seulement si une entité `<camera>_sub` existe dans Home Assistant ; sinon `""` (le
  suffixe de flux `veille_flux_suffixe` reste utilisé côté serveur par `/veille/flux`).
