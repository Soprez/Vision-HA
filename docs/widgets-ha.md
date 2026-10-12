# Widgets Home Assistant dans l'appli (point 6, conception validée le 2026-10-08)

Module : « Entités Home Assistant » (`entites`). Coupé : tout ce qui suit répond 403 `{"error":"module"}` et
l'appli masque les widgets HA (sans les supprimer).

## Droits (serveur)

- **Liste par personne** : `store.entites_personnes = {"person.x": ["light.salon", "sensor.temp", ...]}`.
- **Exceptions par appareil** : `store.entites_appareils = {pc_id: {"plus": [...], "moins": [...]}}`.
- Entités visibles par un appareil = (liste de sa personne ∪ plus) − moins ; un appareil sans personne : `plus` seulement.
- **Lecture seule par défaut.** Actions : liste blanche `store.entites_actions = {"light.salon": ["toggle", "turn_on", "turn_off"], "script.x": ["turn_on"], ...}`.
- Fonctions de rôle : `entites_voir` pour lire, `entites_piloter` pour agir (en plus de la liste blanche).
- Réglage par un administrateur dans Vision → Configurer → « Entités Home Assistant » (par personne, exceptions
  par appareil, actions permises par entité). Journalisé.
- **Jamais de jeton HA global** : tout passe par l'identifiant + secret de l'appareil.

### 2.4.0 — widgets interactifs (validé en essai le 2026-10-12)

- **Pour tout le monde** : `store.entites_tous = [...]`, vues par tous les appareils (avant leur personne et leur
  « plus » ; les « moins » s'appliquent).
- **Réglage par entité** : `store.entites_reglages = {eid: {"role", "qui", "confirmer", "nom", "icone",
  "affichage", "attributs"}}`.
  - `role` : `lecture` | `interrupteur` (light, switch, fan, input_boolean) | `bouton` (script, scene, button,
    input_button) | `reglage` (input_number, number, input_select, select, climate, cover, media_player). Le rôle
    fixe `entites_actions` (tous les services du rôle ; `lecture` : aucun). Sans réglage : rôle déduit de la liste
    blanche (comportement d'avant). Serrures et alarmes : `lecture` seulement, toujours.
  - `qui` : `decideurs` (fonction `entites_piloter`, par défaut) | `tous` (aussi les profils sans « piloter », pour
    cette entité seulement).
  - `confirmer` : l'action n'est faite qu'avec `"confirme": true` (sinon 409). Cochée d'office pour script, cover,
    climate, et quand `qui` passe à `tous`.
  - `nom` (60 car. au plus), `icone` (`mdi:…`) : affichés à la place de ceux de HA.
  - `affichage` : `valeur` | `detail` | `valeur_detail` ; `attributs` : les attributs montrés en détail (8 au plus ;
    aucun = tous les attributs simples). Une liste `todo` montre ses éléments à faire (`todo.get_items`).

## Points d'accès (POST JSON `{id, secret, ...}`, comme le relevé)

| Chemin | Corps | Réponse |
|---|---|---|
| `/api/pc_parental/widgets/entites` | – | `{"entites": [{"entity_id","nom","domaine","icone","unite","device_class","etat","attributs_utiles","actions":[...],"styles":[...]}]}` — seulement les entités permises ; `styles` = styles proposés selon le domaine |
| `/api/pc_parental/widgets/etats` | `{"entites": [...]}` | `{"etats": {entity_id: {"etat","attributs_utiles","depuis"}}}` (les non permises sont ignorées) |
| `/api/pc_parental/widgets/flux` | GET `?id=&secret=` ou POST | flux d'événements (SSE `text/event-stream`, comme `/api/pc_parental/veille/flux`) : `data: {"entity_id","etat","attributs_utiles"}` pour les seules entités permises ; battement toutes les 25 s |
| `/api/pc_parental/widgets/historique` | `{"entite","periode":"24h"|"7j"}` | numérique : `{"type":"nombre","unite","points":[[ts,valeur],...]}` (≤ 200 points, moyenne par intervalle) ; sinon `{"type":"etats","segments":[[debut,fin,etat],...]}` |
| `/api/pc_parental/widgets/action` | `{"entite","service","data":{},"confirme"?:true}` | `{"ok":true}` ; 403 `piloter` (rôle, sauf entité « tous »), 403 `action` (hors liste blanche), 403 `entite` (non permise), 409 `confirmer` (`"retour"` : la question) |

2.4.0 : chaque entité de `/entites` porte aussi `nom_ha`, `role`, `confirmer`, `affichage` ; `/entites` et `/etats`
ajoutent `detail` (`[{"texte","valeur"}]`, 20 au plus) et `detail_total` quand l'affichage le demande. Données sûres
en plus : `input_number`/`number` `set_value` `{"value"}` (bornes `min`/`max` de l'entité), `input_select`/`select`
`select_option` `{"option"}` (une des `options` de l'entité).

`attributs_utiles` : un petit sous-ensemble utile au rendu (unit_of_measurement, device_class, friendly_name,
brightness, temperature/current_temperature, hvac_action, forecast résumé pour la météo…), jamais tout l'état.

## Styles (appli)

Proposés selon le domaine : `tuile` (valeur), `bouton` (script/scene/button), `interrupteur` (light, switch, fan,
input_boolean), `jauge` (capteur numérique avec min/max), `graphique` (ligne ou barres ; 24 h / 7 j), `meteo`
(weather), `ouverture` (binary_sensor porte/fenêtre/garage…), `mini_carte` (2 à 4 entités).
`lecteur` (1.98.0, media_player, 1 à 4 enceintes) : titre, artiste, pochette (lue par `/api/pc_parental/party/pochette`,
jamais l'adresse `entity_picture` qui porte un jeton), touches lecture/pause, suivant, précédent et volume selon la liste blanche
(`media_play_pause`, `media_next_track`, `media_previous_track`, `volume_set` avec `volume_level` 0–1).
Taille : 1×1, 2×1, 2×2, 4×2… ; libellé et icône personnalisables ; couleurs du thème Vision ; mise à jour en
direct (flux) ; TV en lecture seule.

## Accueil (appli)

- Redimensionnement : appui long → cadre à poignées → glisser par pas de grille (vibration légère), sans
  chevauchement, en respectant min/max/resizeMode d'un widget Android ; nouvelle taille envoyée
  (`updateAppWidgetSize` / `OPTION_APPWIDGET_SIZES`) et enregistrée. Même comportement pour les widgets Vision/HA.
- Sélecteur : widgets Android groupés par appli, recherche, aperçu (`previewLayout` 12+, `previewImage`, sinon
  icône), nom de l'appli + du widget + taille ; widgets HA avec aperçu en direct dans l'éditeur.
