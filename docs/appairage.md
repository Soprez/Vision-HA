# Appairage par QR (Vision 1.62.0 et 1.65.0) — ce que l'appareil doit faire

Plus aucune clé à saisir sur l'appareil. L'appareil demande à être inscrit, affiche un QR et
un code court, un administrateur de Home Assistant valide depuis son téléphone, et l'appareil
reçoit son identifiant et son secret. La clé d'inscription permanente n'apparaît nulle part
(ni dans le QR, ni dans les réponses).

Ce document décrit l'API côté intégration (`pc_parental/appairage.py`), telle que l'appli
Android (et plus tard l'installateur PC) doit l'appeler. Vérifiée par `essai/securite_test.py`.

Deux sens, au choix de l'appareil :
- **l'appareil affiche, le gestionnaire scanne** (1.62.0, sections 1 et 2 ci-dessous) : pour tout
  appareil, même sans caméra (télé, PC) ;
- **Home Assistant affiche, l'appareil scanne** (1.65.0, section « Dans l'autre sens ») : pour
  un appareil qui a une caméra (téléphone, tablette).

## Déroulé

```
Appareil                          Home Assistant                       Gestionnaire (admin)
   | POST /appairage/demande  ------>  demande en mémoire (5 min)
   | <------ code, attente, qr          notification « Demande d'appairage »
   | affiche QR + code                                                   scanne le QR
   |                                                                    → appli HA : /vision-appairage?code=…
   | POST /appairage/attente (3 s)                                       vérifie le code, choisit la personne,
   | <------ en_attente                                                  « Valider »
   | POST /appairage/attente  ------>  validee : id, secret (une fois)
   | enregistre id + secret, passe au relevé normal (/api/pc_parental/poll)
```

## Avant : l'adresse de Home Assistant

L'appareil doit déjà connaître l'adresse de Home Assistant (`base`, par ex.
`https://ha.exemple.org:8123` ou `http://homeassistant.local:8123`) : la saisir, ou la découvrir
sur le réseau local (zeroconf `_home-assistant._tcp`). L'appairage ne la fournit pas. Les deux
appels ci-dessous sont **sans authentification** Home Assistant (pas d'en-tête `Authorization`).
Corps et réponses en JSON UTF-8.

## 1. Demander : `POST {base}/api/pc_parental/appairage/demande`

Corps :

| champ | obligatoire | contenu |
|---|---|---|
| `host` | oui | nom de machine (≤ 64) ; Android : `Build.MODEL` ou équivalent, comme aujourd'hui pour `/enroll` |
| `nom` | non | nom proposé à l'écran (≤ 64), ex. « Télé du salon » |
| `uid` | recommandé | identifiant propre à l'appareil (≤ 64), **le même que celui envoyé au relevé** (`uid`) : c'est lui qui reconnaît un appareil déjà inscrit (appli réinstallée) |
| `platform` | recommandé | `android` (ou `windows`) |
| `user` | non | compte de session (PC) ; sert seulement à **proposer** une personne |

Ne pas envoyer de `person` : la personne est choisie par l'administrateur à la validation.

Réponse `200` :

```json
{
  "ok": true,
  "demande": "9f2c4b1a7d3e5f60",
  "attente": "3b8e…(32 caractères hexadécimaux)",
  "code": "K7F2QM",
  "qr": "homeassistant://navigate/vision-appairage?code=K7F2QM",
  "lien": "https://ha.exemple.org:8123/vision-appairage?code=K7F2QM",
  "expire_dans": 300,
  "intervalle": 3
}
```

- `qr` : **le contenu exact à encoder dans le QR** (lien profond de l'appli Home Assistant).
  Ne pas fabriquer le QR soi-même à partir d'autre chose : le serveur décide du format.
- `code` : à afficher en grand, lisible (6 caractères parmi `ABCDEFGHJKMNPQRSTUVWXYZ23456789`,
  sans 0/O/1/I/L). Le gestionnaire compare ce code à celui de son écran avant de valider.
- `lien` : à afficher en petit, en secours (si le téléphone du gestionnaire n'a pas l'appli HA, il
  l'ouvre dans un navigateur connecté à HA). Le gestionnaire peut aussi taper le code dans
  *Paramètres → Appareils et services → Vision → Configurer → Demandes en attente*.
- `demande` + `attente` : **à garder en mémoire seulement** (pas sur disque, jamais dans un
  journal) : c'est ce qui permet de récupérer le secret. `attente` n'est jamais montré.
- `expire_dans` (secondes) : afficher un compte à rebours ; à zéro, proposer « Nouveau code ».

Erreurs : `400 {"error":"json"|"host"}` ; `429 {"error":"trop"}` (déjà 2 demandes en cours depuis
cette adresse, ou 10 en tout : attendre l'expiration ou annuler) ; `503 {"error":"loading"}`
(Vision démarre : réessayer dans 10 s).

## 2. Attendre : `POST {base}/api/pc_parental/appairage/attente`

Corps : `{"demande": "<demande>", "attente": "<attente>"}`. À appeler toutes les `intervalle`
secondes (3 s) tant que l'écran d'appairage est affiché.

Réponses `200` selon `etat` :

| `etat` | réponse | ce que fait l'appareil |
|---|---|---|
| `en_attente` | `{"ok":true,"etat":"en_attente","expire_dans":184}` | continuer ; mettre à jour le compte à rebours |
| `validee` | `{"ok":true,"etat":"validee","id":"a1b2c3d4e5f6","secret":"…32 hex…","name":"Télé du salon","poll":20,"created":true}` | **enregistrer `id` et `secret` exactement comme après `/enroll`**, oublier `demande`/`attente`, passer au relevé |
| `refusee` | `{"ok":true,"etat":"refusee"}` | « Demande refusée » ; bouton « Nouveau code » |
| `expiree` | `{"ok":true,"etat":"expiree"}` | « Code expiré » ; bouton « Nouveau code » |

`404 {"error":"inconnue"}` : demande inconnue, mauvais `attente`, ou réponse finale déjà lue
(chaque réponse finale ne se lit **qu'une fois**), ou Home Assistant a redémarré (les demandes
vivent en mémoire). Dans tous ces cas : recommencer à l'étape 1.

**Usage unique.** Le secret n'est donné qu'une fois. Si la réponse `validee` se perd (coupure
réseau pendant l'appel), l'appareil n'a pas de secret : il redemande (étape 1) et le gestionnaire
valide à nouveau ; la fiche est alors reprise avec un secret neuf (`created: false`).

`created` : `true` = nouvel appareil (Home Assistant recharge Vision : le premier relevé peut
répondre `503` pendant quelques secondes, réessayer) ; `false` = appareil déjà connu (même `uid`,
ou même `host` sans `uid`), repris avec un **secret neuf** — l'ancien ne vaut plus.

## Après

- Relevé normal : `POST /api/pc_parental/poll` avec `{"id","secret","uid",…}` comme aujourd'hui.
- La personne de l'appareil est celle choisie par l'administrateur à la validation (ou aucune,
  à choisir plus tard dans Home Assistant). Ne plus envoyer `person` au relevé : il n'est plus
  qu'une proposition, ignorée pour les droits.
- L'ancien parcours (`/api/pc_parental/enroll` avec la clé) existe toujours ; l'appairage ne
  dépend pas de « Inscription de nouveaux appareils » (il marche inscriptions fermées).

## Écran conseillé (appli)

1. « Connecter à Home Assistant » : adresse (saisie ou trouvée), puis appel 1.
2. Grand QR (`qr`), code en très grand (`K7F 2QM`, groupé par 3 pour la lecture), compte à
   rebours, petit texte « Scanne avec le téléphone d'un gestionnaire (appli Home Assistant) »,
   `lien` en petit, bouton « Annuler ».
3. Sur `validee` : « Connecté en tant que {name} » ; sur `refusee`/`expiree` : message et
   « Nouveau code ».
Sur une télé (Leanback), même écran ; le QR doit rester lisible à 3 m (≥ 40 % de la hauteur).

## Côté gestionnaire

- Le QR ouvre l'appli Home Assistant sur le panneau caché `/vision-appairage?code=…`, réservé
  aux administrateurs (un compte non administrateur y est refusé). Il n'affiche **que** la demande de ce code ;
  s'il y en a d'autres en attente, il le dit sans les montrer.
- Ailleurs : *Vision → Configurer → Demandes en attente* (saisie du code), ou les services
  `pc_parental.valider_appairage` / `pc_parental.refuser_appairage` (administrateurs).
- Chaque demande crée une notification « Demande d'appairage » avec le lien, et l'événement
  `pc_parental_appairage_demande` (`code`, `nom`, `plateforme`, `chemin`), pour une notification
  actionnable vers le téléphone d'un gestionnaire, par exemple :

```yaml
triggers:
  - trigger: event
    event_type: pc_parental_appairage_demande
actions:
  - action: notify.mobile_app_telephone_gestionnaire
    data:
      title: "Vision : {{ trigger.event.data.nom }}"
      message: "Code {{ trigger.event.data.code }}"
      data:
        actions:
          - action: URI
            title: Vérifier et valider
            uri: "{{ trigger.event.data.chemin }}"
```

  Le bouton ouvre seulement le panneau : la validation reste un geste d'administrateur.

## Limites connues

- Le lien profond `homeassistant://navigate/…` ouvre l'appli Home Assistant ; avec plusieurs
  serveurs configurés dans l'appli, c'est elle qui fait choisir (comportement de l'appli, non
  vérifié ici). Sur un autre Home Assistant, le panneau dit « code pas en attente ».
- Sans appli Home Assistant sur le téléphone du gestionnaire, le QR ne mène nulle part : le code et
  `lien` restent.

## Dans l'autre sens : l'appareil scanne le QR de Home Assistant (1.65.0)

Un administrateur génère un **QR d'appairage** dans *Paramètres → Appareils et services →
Vision → Configurer → Avancé → Générer un QR d'appairage à la main*, ou dans le panneau `/vision-appairage`
(bouton « Générer le QR », lien depuis la section « Installer Vision sur un appareil »).
Il peut choisir la personne de l'appareil, et, pour une appli réinstallée, l'appareil à
reprendre. L'appareil scanne ce QR et se relie seul : pas de code à lire, pas d'attente.

### Le QR (format exact)

Texte encodé (un seul QR, correction M) :

```
vision://appairage?v=1&adresse=<adresse>&locale=<locale>&jeton=<jeton>
```

| paramètre | toujours | contenu |
|---|---|---|
| `v` | oui | version du format : `1`. Refuser une autre valeur (« mets l'appli à jour ») |
| `adresse` | oui | adresse de Home Assistant à retenir, encodée URL (`%3A`, `%2F`) : l'adresse externe si elle est réglée, sinon l'interne, sinon celle par laquelle l'administrateur est connecté. Ex. `https%3A%2F%2Fha.exemple.org` |
| `locale` | non | adresse sur le réseau local (adresse interne de HA), si elle diffère de `adresse`. Ex. `http%3A%2F%2Fhomeassistant.local%3A8123` — même rôle que la ligne `locale:` du bloc de connexion |
| `jeton` | oui | jeton d'appairage (base64 URL, 32 caractères aujourd'hui ; ne pas supposer de longueur) |

Jamais la clé d'inscription. Lecture côté appli : `Uri.parse(texte)`, vérifier
`scheme == "vision"` et `host == "appairage"`, puis `getQueryParameter(...)` (qui décode).
Tout autre QR (ex. le lien de téléchargement de l'APK, en `https://…`) n'est pas un QR
d'appairage : le dire, sans rien appeler. L'appli peut aussi déclarer le schéma `vision://`
(filtre d'intention `vision` / `appairage`) pour qu'un QR scanné avec l'appareil photo l'ouvre.

### L'appel : `POST {adresse}/api/pc_parental/appairage/jeton`

Sans authentification Home Assistant. Essayer `locale` d'abord si l'appareil est sur le Wi-Fi
local et qu'elle est présente, sinon `adresse` (même logique qu'aujourd'hui pour
`urlActive`). Corps :

```json
{"jeton": "<jeton du QR>", "host": "Pixel 8a", "nom": "Téléphone de Lou", "uid": "<uid>", "platform": "android"}
```

Mêmes champs que pour la demande (section 1) : `host` obligatoire ; `uid` le même que celui
du relevé ; `user` facultatif ; pas de `person`.

| statut | corps | ce que fait l'appareil |
|---|---|---|
| `200` | `{"ok":true,"id":"a1b2c3d4e5f6","secret":"…32 hex…","name":"Téléphone de Lou","poll":20,"created":true}` | enregistrer `adresse`, `locale`, `id`, `secret` comme après `/enroll`, puis relevé normal |
| `404` | `{"ok":false,"error":"jeton"}` | QR inconnu, **déjà utilisé** ou **expiré** (5 min) : « Ce QR n'est plus valable, demandes-en un nouveau » |
| `409` | `{"ok":false,"error":"deja_inscrit"}` | cet appareil est **déjà inscrit** (même `uid`, ou même `host` sans `uid`) : « Cet appareil est déjà relié. Pour le relier à nouveau, un administrateur doit générer un QR en choisissant cet appareil » (le QR reste valable pour un autre appareil) |
| `400` | `{"ok":false,"error":"json"\|"host"}` | erreur de l'appli |
| `503` | `{"ok":false,"error":"loading"}` | Vision démarre : réessayer dans 10 s |

**Usage unique.** Le jeton est consommé par le premier appel qui réussit (`200`) : la réponse
ne se relit pas. Si elle se perd (coupure pendant l'appel), il faut un nouveau QR ; avec
« reprendre cet appareil », la fiche est reprise avec un secret neuf. `created: false` = fiche
reprise (secret neuf, l'ancien ne vaut plus) ; `created: true` = nouvel appareil (Vision se
recharge : le premier relevé peut répondre `503` quelques secondes).

**Personne.** Celle choisie par l'administrateur en générant le QR, sinon aucune (à choisir
dans Home Assistant). L'appareil n'envoie jamais `person`.

### Écran conseillé (appli)

« Relier à Home Assistant » → **Scanner le QR de Home Assistant** (caméra, cadre de visée) ou
**Afficher un QR** (sens 1.62.0, pour qui n'a pas de caméra ou préfère). Après le scan :
« Connexion à {adresse}… », puis « Relié en tant que {name} ». Ne jamais afficher ni journaliser
le jeton. Le QR n'est valable que 5 minutes : si l'administrateur l'a généré trop tôt, le `404`
le dit.

### Côté administrateur

- Le QR s'affiche dans Vision → Configurer (image servie par un chemin signé, valable 5 minutes,
  qui ne s'ouvre qu'avec sa session) ou dans le panneau `/vision-appairage` (réservé aux
  administrateurs). Le tableau de bord, lisible par tout compte, n'affiche qu'un lien : un QR
  visible d'une personne suivie lui permettrait d'inscrire un appareil vierge.
- `POST /api/pc_parental/appairage/invitation` (administrateur) : `{"personne": "person.x",
  "appareil": "<id>"}` (les deux facultatifs) → `{"id","qr","svg","expire_dans"}` ; au plus 5 QR
  en cours. `GET /api/pc_parental/appairage/invitation/<id>/qr.svg` : l'image (administrateur).
- Quand un appareil s'en sert : notification « Appareil appairé ».

