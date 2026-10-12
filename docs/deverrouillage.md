# Déverrouillage à distance (Vision 1.66.0) — contrat entre Home Assistant et l'appareil

Une personne suivie demande, depuis son appareil, une action précise (« ouvrir l'appli X 30 min »,
« ouvrir les réglages protégés ») ou le « mode gestion N min ». Un décideur approuve — depuis un
appareil dont le rôle peut approuver (Décideur par défaut), ou en administrateur de Home Assistant. Home Assistant
**signe** l'approbation ; l'appareil vérifie la signature avec un **défi** qu'il est seul à
connaître, applique l'effet (celui du code de gestion, borné à l'action et à la durée approuvées),
puis fait **consommer** l'approbation. Le code de gestion local reste toujours disponible.

Ce document est le contrat : endpoints, corps, statuts, format de la signature, du QR et du
code, durées et limites. Code : `pc_parental/deverrouillage.py` ; essais d'attaque :
`essai/deverrouillage_test.py`.

## Vue d'ensemble

```
Demandeur (appareil)              Home Assistant                         Décideur
 tire un défi (16 octets)
 POST /deverrouillage/demande ──>  demande en attente (10 min)  ──> message + notification
                                                                    POST /deverrouillage/decision
                                                                    (appareil Décideur)
                                                                    ou écran / service admin
                                   signe l'approbation, selon la remise :
   directe : POST /deverrouillage/etat (ou relevé) <── approbation
   code    : le demandeur tape le code ── POST /deverrouillage/code ──> approbation
   qr      : le demandeur scanne le QR du décideur (approbation sans le défi)
 vérifie la signature avec SON défi, applique, efface le défi
 POST /deverrouillage/consommer ──>  journal (WARNING), logbook, événement
```

Tous les appels de l'appareil passent **sans authentification Home Assistant**, avec
l'identifiant et le secret de l'appareil dans le corps (comme le relevé `/api/pc_parental/poll`).
Corps et réponses en JSON UTF-8. Statuts communs : `400 {"error":"json"}` corps illisible ;
`401 {"error":"auth"}` identifiant/secret refusés ; `503 {"error":"loading"}` Vision démarre
(réessayer dans 10 s).

## Les actions

| `action` | effet sur l'appareil | `minutes` |
|---|---|---|
| `app:<paquet>` | ouvrir cette appli seule (ex. `app:com.roblox.client`) ; `<paquet>` : `[A-Za-z0-9._]{1,100}` | 1 à 240 |
| `reglages` | ouvrir les réglages protégés (Vision, Android) | 1 à 30 |
| `parent` | mode gestion : tout ce que le code de gestion ouvre | 1 à 60 |

Toute autre valeur : `400 {"error":"action"}`. La liste pourra s'allonger (nouvelle valeur =
nouvelle version de l'appli ; une appli qui ne connaît pas une action la refuse).

## 1. Demander — `POST /api/pc_parental/deverrouillage/demande` (appareil demandeur)

```json
{"id": "<appareil>", "secret": "<secret>", "action": "app:com.roblox.client", "minutes": 30,
 "defi": "<16 octets aléatoires, base64url sans =, 22 caractères>", "motif": "texte libre, facultatif (≤ 140)"}
```

Le **défi** est tiré par l'appareil (générateur cryptographique, `SecureRandom`), gardé **en
mémoire seulement** (jamais sur disque, jamais dans un journal), et effacé dès qu'il a servi, que
la demande est remplacée, ou 10 minutes après. Une **nouvelle demande annule la précédente** du
même appareil (son approbation, son code et son QR ne valent plus).

Réponse `200` :

```json
{"ok": true, "demande": "9f2c4b1a7d3e5f60", "expire": 1791300000,
 "cle": {"k": "3b8e5c0a1d2f4e6b", "pub": "<clé publique, SubjectPublicKeyInfo DER, base64>"}}
```

`expire` (secondes Unix) : sans décision d'ici là, la demande expire (10 minutes).

Erreurs : `400 {"error":"action"|"minutes"|"defi"|"motif"}` ; `429 {"error":"trop"}` (plus de
10 demandes dans l'heure pour cet appareil).

Home Assistant prévient les décideurs : message sur chaque appareil Décideur (avec ce qu'il faut
pour décider), notification « Demande de déverrouillage », événement
`pc_parental_deverrouillage_demande` (`demande`, `appareil`, `nom`, `personne`, `action`,
`minutes`, `motif`).

## 2. Décider

### Depuis un appareil Décideur — `POST /api/pc_parental/deverrouillage/decision`

```json
{"id": "<appareil décideur>", "secret": "<secret>", "demande": "<demande>",
 "decision": "approuver", "minutes": 20, "remise": "code"}
```

- `decision` : `approuver` ou `refuser`.
- `minutes` : facultatif ; **au plus** celles demandées (le décideur peut raccourcir, jamais
  allonger), et dans les bornes de l'action. Absent : celles demandées.
- `remise` : `directe` (l'appareil la reçoit tout seul), `code` (le décideur lit un code au demandeur)
  ou `qr` (le demandeur scanne l'écran du décideur). Absent : `directe`.

Réponses `200` :

| remise | réponse |
|---|---|
| `directe` | `{"ok":true,"etat":"approuvee","remise":"directe","minutes":20,"expire":…}` |
| `code` | `{"ok":true,"etat":"approuvee","remise":"code","minutes":20,"expire":…,"code":"482913"}` |
| `qr` | `{"ok":true,"etat":"approuvee","remise":"qr","minutes":20,"expire":…,"qr":"vision://deverrouillage?…"}` |
| refus | `{"ok":true,"etat":"refusee"}` |

Le `code` et le `qr` ne sont donnés **qu'une fois**, à l'auteur de la décision : les afficher
tout de suite (code en grand, groupé 3+3 ; QR plein écran avec son compte à rebours).

Erreurs : `401` ; `403 {"error":"parent"}` (l'appareil n'a pas de rôle qui approuve, ou
décide pour lui-même) ; `404 {"error":"demande"}` (inconnue, expirée, annulée, **déjà décidée**) ;
`400 {"error":"decision"|"minutes"|"remise"}`.

Liste des demandes en attente, pour l'écran du décideur : `POST /api/pc_parental/deverrouillage/liste`
`{"id","secret"}` (appareil Décideur, sinon `403`) →
`{"ok":true,"demandes":[{"demande","appareil","nom","personne","action","minutes","motif","depuis","expire"}]}`.

### En administrateur de Home Assistant

- `GET /api/pc_parental/deverrouillage` (authentification HA, administrateur, sinon `403`) : même
  liste que ci-dessus.
- `POST /api/pc_parental/deverrouillage` `{"demande","decision","minutes","remise"}` : même
  réponse que depuis un appareil Décideur.
- Services `pc_parental.approuver_deverrouillage` (`demande`, `minutes`, `remise`) et
  `pc_parental.refuser_deverrouillage` (`demande`), réservés aux administrateurs ; l'approbation
  renvoie `code` / `qr` en réponse de service.

Personne d'autre ne décide : ni un compte HA non administrateur, ni un appareil sans rôle qui
approuve (même si l'appli s'y croit décideur), ni une automatisation sans utilisateur.

## 3. Recevoir l'approbation (appareil demandeur)

### Remise `directe` — `POST /api/pc_parental/deverrouillage/etat`, et le relevé

`{"id","secret","demande"}` → `{"ok":true,"etat":"en_attente"|"approuvee"|"refusee"|"annulee"|"expiree"|"consommee"|"bloquee"}`.
Avec `etat: approuvee` et une remise `directe`, la réponse porte aussi `"approbation": {…}`
(ci-dessous). Avec une remise `code` ou `qr`, **jamais** : l'appareil attend le code ou le QR.
À appeler toutes les 3 s tant que l'écran d'attente est affiché ; sinon le relevé suffit : tant
qu'une approbation `directe` n'est pas consommée ni expirée, la réponse du relevé porte
`"deverrouillage": {…}` (la même approbation). `404 {"error":"demande"}` : demande inconnue.

### Remise `code` — `POST /api/pc_parental/deverrouillage/code`

`{"id","secret","demande","code":"482913"}` → `200 {"ok":true,"approbation":{…}}`.
Le code ne vaut que pour cette demande, cet appareil, une fois, 5 minutes. Erreurs :
`403 {"error":"code","essais_restants":3}` ; `423 {"error":"bloquee"}` au **5e** code faux (la
demande est annulée, les décideurs prévenus ; il faut une nouvelle demande) ; `404 {"error":"demande"}`
(inconnue, pas approuvée par code, expirée) ; `429 {"error":"trop"}` (plus de 10 codes faux dans
l'heure pour cet appareil, toutes demandes confondues). **Hors ligne, pas de code** (décision de
conception) : l'écran le dit et propose le QR ou le code de gestion local.

### Remise `qr` — le QR du décideur

```
vision://deverrouillage?v=1&k=<k>&p=<appareil>&d=<demande>&a=<action>&m=<minutes>&x=<expire>&s=<signature>
```

Toutes les valeurs encodées URL (`a` contient `:` → `%3A`) ; lire avec `Uri.getQueryParameter`.
`v=1` seulement. **Le défi n'est pas dans le QR.** `p` sert à dire « ce QR est pour un autre
appareil » ; la signature, elle, lie de toute façon le QR à l'appareil. L'appareil vérifie
(ci-dessous) **hors ligne**, applique, puis consomme dès qu'il est en ligne.

### L'approbation (objet JSON des remises `directe` et `code`)

```json
{"k": "3b8e5c0a1d2f4e6b", "appareil": "a1b2c3d4e5f6", "demande": "9f2c4b1a7d3e5f60",
 "action": "app:com.roblox.client", "minutes": 20, "expire": 1791300090, "signature": "<base64url>",
 "par": "Claire", "remise": "code"}
```

## 4. La signature

- Algorithme : **ECDSA P-256 (secp256r1), SHA-256**. Signature **DER**, encodée **base64url sans
  `=`**. Côté Android : `Signature.getInstance("SHA256withECDSA")`, clé publique par
  `KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.decode(pub)))`.
- Message signé : les octets UTF-8 de ces 8 lignes, séparées par `\n` (0x0A), **sans** `\n` final :

```
vision-deverrouillage
1
<appareil>
<demande>
<action>
<minutes>
<expire>
<defi>
```

  `<minutes>` et `<expire>` en décimal sans zéro devant ; `<defi>` exactement la chaîne base64url
  envoyée à la demande. Aucun champ ne peut contenir `\n` (refusé à la demande).
- L'appareil **accepte** l'approbation seulement si, dans cet ordre :
  1. `k` est l'identifiant de la clé publique qu'il connaît (sinon : faire un relevé pour recevoir
     la clé, puis réessayer ; « clé inconnue » si elle ne change pas) ;
  2. `appareil` est son propre identifiant ;
  3. `demande` est **sa demande en cours**, et il a encore son défi ;
  4. la signature est valide sur le message ci-dessus, recomposé avec **ses** valeurs
     (identifiant, défi) et celles de l'approbation (demande, action, minutes, expire) ;
  5. `expire` n'est pas dépassé (horloge de l'appareil) ;
  6. `action` est une action qu'il sait appliquer.

  Puis il **efface le défi**, applique l'effet pendant `minutes` à partir de maintenant, et
  consomme (section 5). Un QR photographié, rejoué plus tard ou sur un autre appareil, échoue en 2
  ou 3 ; une action ou une durée modifiée échoue en 4.
- Clé publique : `{"k","pub"}` dans la réponse de la demande et dans **chaque relevé**
  (`"deverrouillage_cle"`). `k` = 16 premiers caractères hexadécimaux du SHA-256 de `pub` décodé.
  L'appareil garde la dernière reçue par le relevé. Une rotation (service admin
  `pc_parental.nouvelle_cle_deverrouillage`) invalide les approbations en cours.
- Expiration `expire` (secondes Unix) d'une approbation : `qr` 90 s, `code` 5 min, `directe`
  10 min après la décision. Elle borne le moment où l'on **commence** ; la durée (`minutes`)
  court ensuite.

## 5. Consommer — `POST /api/pc_parental/deverrouillage/consommer` (appareil demandeur)

`{"id","secret","demande","applique":1791300012}` (`applique` : heure de l'appareil où l'effet a
commencé). → `200 {"ok":true,"fin":1791301212}`. C'est cet appel qui écrit le journal.

- `409 {"error":"deja"}` : déjà consommée (ne rien réappliquer) ;
- `404 {"error":"demande"}` : inconnue (Home Assistant a redémarré, ou demande annulée entre-temps).
  Si l'appareil a **déjà appliqué** (QR hors ligne), il le dit quand même : `{"hors_ligne": true}`
  dans le corps ; Home Assistant journalise sans rien autoriser de plus.
- Hors ligne (QR) : l'appareil garde la consommation à envoyer (demande, heure) et l'envoie au
  premier contact, avant le relevé.

## 6. Biométrie — `"biometrie"` dans le relevé

Vrai seulement si la case « Biométrie autorisée » est cochée **sur cet appareil** (décochée par
défaut ; administrateurs) **et** que l'appareil est de rôle **Décideur (admin)** (réglage « Rôle de
l'appareil », administrateurs, journalisé) **et** que ce n'est pas un appareil d'affichage. Depuis la
1.69.0, l'étiquette « Parents » et l'interrupteur global ne comptent plus (migrés une fois : appareils
portant cette étiquette → rôle Décideur ; biométrie cochée sur eux si l'interrupteur global était allumé).
Sinon `false`. L'appli éteint la biométrie si le champ manque, vaut `false`, ou si le dernier
relevé réussi a plus de 24 h.

## 7. Journal

À chaque consommation : une ligne **WARNING** dans le journal de Home Assistant
(`Vision : déverrouillage de « Téléphone de Lou » (Lou) : app:com.roblox.client 20 min,
approuvé par Claire (code)`), une entrée du journal de bord (logbook), et l'événement
`pc_parental_deverrouillage` : `demande`, `appareil`, `nom`, `personne`, `action`, `minutes`,
`par`, `remise`, `hors_ligne`. Les refus, les blocages (5 codes faux) et les décisions tentées par un
appareil sans rôle qui approuve ont aussi leur ligne WARNING. Une signature refusée se voit
sur l'appareil seulement (Home Assistant n'en sait rien) : l'appli peut le signaler au relevé.

## Durées et limites (récapitulatif)

| quoi | valeur |
|---|---|
| défi | 16 octets aléatoires, base64url (22 caractères) |
| demande sans décision | 10 min, une seule en cours par appareil |
| demandes par appareil | 10 par heure |
| approbation `qr` / `code` / `directe` | 90 s / 5 min / 10 min pour commencer |
| code | 6 chiffres, haché en mémoire, 1 usage, 5 essais par demande, 10 codes faux par heure et par appareil |
| `minutes` | `app:` 1–240, `reglages` 1–30, `parent` 1–60 ; jamais plus que demandé |
| clé de signature | ECDSA P-256, stockée par Home Assistant (`.storage/pc_parental.deverrouillage`), rotation par service admin |

Les demandes vivent en mémoire : un redémarrage de Home Assistant les efface (le demandeur redemande).

## Choix de cette version (à rediscuter au besoin)

- **La remise est choisie par le décideur** : avec `code` ou `qr`, l'approbation ne passe jamais
  par le relevé — sinon le code et le QR ne protégeraient rien.
- `k` et `p` s'ajoutent au QR du brief (rotation de clé ; message « autre appareil »).
- Hors ligne, l'expiration du QR se lit sur l'horloge de l'appareil, et Home Assistant n'apprend
  la consommation qu'au retour en ligne : le défi effacé au premier usage et l'effet borné à
  l'action et à la durée limitent ce que gagne un demandeur qui dérègle l'horloge.
- Biométrie : pas de « rôle parents » d'une personne (la notion n'existe pas dans Vision) ;
  étiquette « Parents » ou personne liée à un administrateur HA, tous deux posés par un
  administrateur.
