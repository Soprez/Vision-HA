# Messages sur les appareils (Vision 1.67.0) — le contrat

Home Assistant envoie à un appareil Vision (télé, PC, téléphone) un message : texte, image, son
ou vidéo, placé où l'expéditeur le veut, éventuellement **bloquant**. L'appareil le montre par
dessus ce qui est à l'écran, le garde sur l'accueil Vision tant qu'il n'est pas lu, et renvoie un
**accusé** à Home Assistant.

Téléphones : rien de nouveau, la notification native existante (titre et texte) ; les champs
nouveaux sont ignorés.

## 1. Envoyer : le service `pc_parental.envoyer` (administrateurs, automatisations)

| champ | défaut | valeurs |
|---|---|---|
| `pc` / `tag` | tous | un appareil (nom ou id), ou une étiquette Home Assistant |
| `type` | d'après `media`, sinon `texte` | `texte`, `image`, `audio`, `video` |
| `titre` | `Vision` | 60 caractères |
| `message` | — | 1000 caractères ; obligatoire pour `texte`, légende sinon |
| `media` | — | `media-source://media_source/<dossier>/<chemin>`, ou un chemin sous un dossier média de Home Assistant (`/media/…`). Rien d'autre (pas d'URL extérieure en 1.67) |
| `position` | réglage de l'appareil | `plein_ecran`, `centre`, `haut`, `bas`, `gauche`, `droite`, `haut_gauche`, `haut_droite`, `bas_gauche`, `bas_droite` |
| `taille` | réglage de l'appareil | `petite`, `moyenne`, `grande` (ignorée en plein écran) |
| `duree` | 30 s (`0` = jusqu'à fermeture) | secondes, 0 à 3600 ; la fenêtre se ferme seule ensuite (le message reste sur l'accueil s'il n'est pas lu) |
| `son` | `Par défaut` | les sons de `pc_parental.notify` |
| `bloquant` | `non` | `non` ; `lu` : plein écran jusqu'à « J'ai lu » ; `parent` : plein écran jusqu'à libération par un gestionnaire |
| `limite` | 30 min | `bloquant: parent` seulement : au-delà, le message se lève seul (journalisé). 1 à 240 min |
| `expire` | 15 min | le message non délivré, ou non lu, se périme ensuite. 1 min à 7 jours |

Un message bloquant est toujours en plein écran. Réponse du service (`response_variable`) :
`{"messages": [{"pc": "<id>", "nom": "Télé du salon", "id": 12, "uid": "<uid>"}]}`.

Médias : jpg, png, webp, gif ; mp3, aac/m4a, ogg, wav ; mp4 (H.264/AAC), webm. 25 Mo pour une
image, 100 Mo pour un son, 500 Mo pour une vidéo.

`pc_parental.notify` et les entités `notify.*` restent (texte).

## 2. Réglages par appareil

Deux entités `select` par appareil : **Position des messages** (défaut `centre`) et **Taille des
messages** (défaut `moyenne`), utilisées quand l'envoi ne précise rien.

## 3. Ce que reçoit l'appareil : le relevé (`/api/pc_parental/poll`)

Comme avant, `"notify": [ … ]`, avec des champs en plus (un ancien agent les ignore et affiche le
texte) :

```json
{"id": 12, "uid": "9f2c…", "title": "Vision", "text": "À table !", "urgent": true,
 "sound": "…", "loop": false, "ts": 1791280000.0,
 "type": "image", "position": "haut_droite", "taille": "moyenne", "duree": 30,
 "bloquant": "non", "limite": 0, "expire": 1791280900.0,
 "media": {"url": "/api/pc_parental/media/9f2c…/<jeton>", "mime": "image/jpeg", "octets": 182733}}
```

- `urgent` vaut `true` pour un message plein écran ou bloquant (les anciens agents l'affichent
  alors en grand).
- `media.url` est relative à l'adresse de Home Assistant, propre à cet appareil et à ce message,
  valable jusqu'à `expire` (+ `limite` pour un bloquant). Ne pas la journaliser.
- **Au moins une fois** : un message peut, rarement, être redonné (rechargement de Vision au mauvais
  moment). L'appareil ignore un `uid` déjà reçu.
- `"liberer": [12, …]` : messages bloquants (`parent`) libérés depuis Home Assistant ; `"*"` = tous.

## 4. Le média : `GET /api/pc_parental/media/<uid>/<jeton>`

Sans authentification Home Assistant : le jeton (aléatoire, 192 bits) vaut droit, pour cet
appareil et ce message seulement, jusqu'à l'expiration. Les requêtes partielles (`Range`) sont
servies (lecture en continu). `404` : inconnu ou expiré.

## 5. L'accusé : `POST /api/pc_parental/message`

```json
{"id": "<id de l'appareil>", "secret": "<secret>", "message": 12, "etat": "lu", "detail": ""}
```

| `etat` | quand |
|---|---|
| `affiche` | le message est à l'écran |
| `lu` | « J'ai lu » (ou fermé par la personne) |
| `ferme` | la fenêtre s'est fermée seule (`duree`), sans lecture : le message reste sur l'accueil |
| `libere_parent` | bloquant `parent` levé par un gestionnaire ; `detail` : la méthode (`code_parent`, `ha`, …). Sur un PC : seulement après une levée par Home Assistant (service, code de gestion vérifié par lui), sinon `409` |
| `libere_auto` | bloquant `parent` levé seul à la `limite`. Sur un PC : seulement si la `limite` est passée depuis le premier `affiche` reçu, sinon `409` |
| `expire` | périmé sans avoir été lu |
| `erreur` | impossible à montrer ; `detail` : la raison (`media`, `format`, `lecteur`…) |

Réponse `{"ok": true}`. Home Assistant émet l'événement **`pc_parental_message`**
(`pc`, `nom`, `id`, `uid`, `etat`, `detail`, `titre`) — une automatisation peut attendre `lu`.
Les levées d'un bloquant (`libere_parent`, `libere_auto`) et `erreur` sont aussi écrites en
**avertissement** dans le journal. `401` : appareil inconnu ; `404` : message inconnu ; `409` : levée
refusée (PC : la fenêtre tourne dans la session du Demandeur, qui peut déposer ses propres accusés ;
l'agent remet alors le message à l'écran).

## 6. Libérer un bloquant depuis Home Assistant : `pc_parental.liberer_message`

Administrateurs : `pc` (obligatoire), `id` (facultatif : sinon tous ses bloquants `parent`). Part
au relevé suivant dans `liberer`. Les autres méthodes du déverrouillage (code, notification, QR —
voir `docs/deverrouillage.md`) s'y brancheront avec l'action `liberer_message`. Sur Android et la télé,
le **code de gestion** local libère toujours, sans réseau.

## 6 bis. Le code de gestion des PC (faille 2.2-1) : `POST /api/pc_parental/code`

Sur un PC, le code qui libère un bloquant `parent` ne se règle plus dans la fenêtre Vision (onglet
« Réglages », ouvert à tous : un Demandeur y posait le sien). Il se règle dans les options de
l'intégration, **Sécurité › Code de gestion des PC** (administrateurs ; jamais par un service, dont les
données partiraient sur le bus d'événements). Home Assistant n'en garde que l'empreinte (PBKDF2-HMAC-SHA256,
sel aléatoire) ; le PC n'en garde rien.

« Libérer » sur le PC : la fenêtre dépose le code tapé dans un fichier que son auteur ne peut pas relire
(ACL : SYSTEM et Administrateurs) ; l'agent (SYSTEM) l'efface aussitôt lu et l'envoie :

```json
{"id": "<appareil>", "secret": "<secret>", "message": 12, "code": "…"}
```

`200 {"ok": true, "liberer": [12]}` : Home Assistant lève le message (comme `liberer_message`, méthode
`code_gestion`, aussi au relevé suivant). `403 {"error":"code","essais_restants":n}` (notification aux
administrateurs dès le premier) ; `429 {"error":"trop","attente":s}` après 5 codes faux en 15 minutes sur
cet appareil ; `423 {"error":"verrouille"}` après 15 codes faux en 24 h dans tout le Lieu (compteur gardé
dans le magasin) : le code reste verrouillé jusqu'à ce qu'un administrateur le change ; `409
{"error":"aucun_code"}` ; `404` : pas un bloquant `parent` de cet appareil ; `401` : appareil inconnu. De 6
à 32 caractères. Le code n'est jamais journalisé.

Sur un PC, un bloquant `parent` n'accepte que les accusés `affiche`, `repondu`, `libere_parent` après une
levée par Home Assistant, et `libere_auto` quand la limite est passée depuis le premier `affiche` (à défaut,
depuis l'envoi, 30 min pour un message d'avant ce suivi). Tout autre accusé (`lu`, `ferme`, `expire`,
`erreur`, levée forgée) : `409`. L'agent n'accepte d'accusé que pour un message de sa file (uid et numéro),
et un refus y laisse le message, qui revient à l'écran.

Limites assumées : la fenêtre tourne dans la session du Demandeur, qui peut la fermer de force ou modifier
`messages.json` ; Home Assistant, lui, garde l'état vrai (non levé). Le code tapé sur le PC passe par cette
session : le taper hors de la vue du Demandeur, ou libérer depuis Home Assistant.

## 7. Sur l'appareil (télé Android, PC)

- **Veille inactive** : fenêtre par-dessus l'appli en cours, à la position et à la taille
  demandées (plein écran : tout l'écran). Fermeture : « J'ai lu », bouton retour / Échap, ou
  `duree` écoulée.
- **Veille active** : plein écran dans la veille, quelle que soit la position ; la veille
  reprend ensuite. Un message ne compte pas comme une activité.
- **Accueil Vision** : le message reste (carte « Messages » sur la télé, compteur dans l'icône
  Vision du PC) jusqu'à « J'ai lu » ou l'expiration.
- **Bloquant `lu`** : plein écran, impossible à fermer autrement que par « J'ai lu ».
- **Bloquant `parent`** : plein écran, « En attente d'un gestionnaire » ; se lève par le code de gestion,
  par `liberer`, ou seul à la `limite` (comptée depuis l'affichage, même après un redémarrage de
  l'appareil).
- **PC** : le thème Vision du PC (celui de la veille), sinon clair/sombre de Windows ; la fenêtre
  s'ouvre dans la session de l'utilisateur connecté. Son et vidéo par le lecteur de WPF ; sur une
  édition « N » de Windows sans lecteur multimédia : texte et image seulement, accusé `erreur`
  `lecteur` pour un son ou une vidéo.
- **PC, limites assumées** : la fenêtre tourne dans la session de l'utilisateur ; un bloquant n'y
  est donc pas infranchissable (un utilisateur averti peut arrêter le processus de la fenêtre). Le
  code qui libère un bloquant `parent` est le code de gestion des PC, réglé et vérifié par Home
  Assistant (§6 bis) ; sans code posé, seuls Home Assistant et la limite de sécurité le lèvent. Le
  verrou de la fenêtre Vision ne demande plus que Windows Hello.

## 8. Notifications v2 (2.0.0, point 8, décisions du 2026-10-10)

Envoi : carte « Messages » du tableau Vision (`GET/POST /api/pc_parental/messages`, administrateurs) ou appli d'un
décideur (`POST /api/pc_parental/messages/app`, fonction « gerer ») :
`{"action": "envoyer", "destinataires": ["person.lou", "<id appareil>"], "type": "message", "texte": "…",
"reponses": ["J'arrive", "Dans 5 min"], "important": false, "pause_tele": false, "voix": false}`.
Types : `{"action": "type", "id"?, "nom", "son", "priorite": "normale|importante", "bloquant": "non|lu|parent"}`,
`{"action": "supprimer_type", "id"}` (types système : modifiables, pas supprimables).

Champs en plus dans `notify` (un ancien appareil les ignore) : `v: 2`, `de`, `reponses` (≤ 3), `important`,
`pause_tele`, `voix`, `type_message`, `type_nom`, `envoi`, `libre`. « Important » n'est jamais bloquant ni plein
écran. Réponse : accusé `etat: "repondu"`, `detail` = la réponse (≤ 200 caractères) ; l'expéditeur la reçoit (son
appli, ou une notification de Home Assistant) et le suivi l'affiche. Son : réglage de l'appareil (selon le type,
discret, silencieux). PC : réponses à venir avec la prochaine version de l'agent (le message s'affiche déjà).
