# Validation à plusieurs (quorum) — avenant proposé à docs/deverrouillage.md (Vision 1.72.0)

État : **implémenté en 1.72.0** (essai/quorum_test.py), à relire par la session « Correctif sécurité » ; code dans
`pc_parental/deverrouillage.py`. Le cœur signé ne change pas : format de la signature, message de
8 lignes, défi, QR, code, consommation. Ce qui change : **quand** `decider()` signe.

## Le réglage

Sur le **rôle du demandeur** (surchargeable par appareil), objet `validation` :

| champ | valeurs | défaut |
|---|---|---|
| `mode` | `une` · `toutes` (toutes les personnes habilitées) · `n` | `une` |
| `n` | 2 … | 2 |
| `actions` | liste parmi `parent`, `reglages`, `app` (vide = toutes) | toutes |
| `au_dela_de` | minutes : en dessous, une seule validation suffit (0 = toujours) | 0 |
| `repli_minutes` | après ce délai sans quorum, une seule validation suffit (0 = jamais : la demande expire) | 0 |

**Personnes habilitées** : les personnes dont **un** appareil a un rôle avec ★ « Approuver ». On compte
des **personnes distinctes** (un décideur avec deux appareils = une voix) ; un administrateur HA qui
décide depuis Home Assistant compte pour la personne liée à son compte (`person.user_id`), sinon pour
lui-même. `toutes` = toutes les personnes habilitées au moment de la demande (figé dans la demande).

## Le déroulé

1. `POST …/decision` (appareil habilité) ou décision admin : c'est une **voix**, journalisée
   (avertissement : « Voix de {Prénom} pour la demande de {Nom} : approuve (1/2) »).
2. **Refus** d'une personne : la demande est refusée tout de suite (comme aujourd'hui).
3. **Approbation** : si le quorum n'est pas atteint → état `partielle`, réponse
   `{"ok":true,"etat":"partielle","voix":1,"requis":2,"en_attente_de":["Claire"]}`. Rien n'est signé.
   Une même personne qui revote (autre appareil) : `409 {"error":"deja_vote"}`.
4. **Quorum atteint** : `decider()` actuel s'exécute avec la remise et la durée de la **dernière**
   voix, plafonnée par la plus courte durée proposée parmi les voix (personne ne peut allonger ce qu'un
   autre a raccourci). Signature et remise inchangées.
5. Expiration : comme aujourd'hui (10 min) ; si `repli_minutes` est atteint avec au moins une voix,
   la demande passe au quorum à 1 (journalisé « repli : une seule validation »).

## Ce que voient les uns et les autres

- `…/etat` (demandeur) : `etat: "partielle"`, `voix`, `requis`, `en_attente_de` (prénoms).
  Écran du demandeur : « 1/2 validé, en attente de Claire ».
- `…/liste` (approbateurs) : chaque demande porte `voix`, `requis`, `deja_vote` (pour cet appareil),
  `en_attente_de`. Écran : « Marc a approuvé — ta validation est nécessaire ».
- Notification renvoyée aux approbateurs restants à la première voix.

## Le code de gestion local

Saisi sur l'appareil, il reste un **passe-partout** (un adulte présent) : il ne passe pas par le quorum.
Il est journalisé (pause : `pc_parental_pause`, méthode `code`). Proposition : une fonction ★ de rôle
« Code de gestion valable seul » (cochée par défaut) pour qui voudrait l'interdire sur certains rôles.

## Essais d'attaque à ajouter (session sécurité)

Même personne via deux appareils ; voix d'un appareil non habilité ; approbation après un refus ;
quorum atteint puis rejeu de la décision ; repli avant l'heure ; durée allongée par la seconde voix ;
`toutes` quand une personne habilitée est ajoutée après la demande.

## Relecture sécurité (Vision 1.75.0 → correctifs)

Relu par la session « Correctif sécurité » ; essais : `essai/quorum_attaques_test.py`. Conforme pour :
voix comptées par personne (`deja_vote`), un refus suffit, signature émise seulement au quorum (format,
message et remise inchangés), durée la plus courte, expiration à 10 min, rejeu d'une décision (`404`),
approbateur sans ★ « Approuver » (`403`), voix journalisées. Quatre contournements corrigés :

1. **Un adulte, deux voix.** Un administrateur HA dont le compte n'est lié à aucune personne
   (`admin:<id>`), ou un appareil « Parent » sans personne (`appareil:<id>`), votait en plus du
   téléphone de la même personne. Désormais, **quand il faut plusieurs voix**, seule une personne vote :
   sinon `403 {"error":"personne"}` (lier le compte HA à sa personne, ou donner une personne à
   l'appareil). Les appareils sans personne ne figurent plus parmi les habilités. Avec une seule voix
   requise, rien ne change.
2. **Une voix non prévue.** Une personne devenue approbatrice **après** la demande comptait à la place
   d'une personne habilitée (en `toutes` comme en `n`). Désormais `403 {"error":"non_habilite"}` :
   seules votent les personnes figées dans la demande.
3. **Le demandeur.** Une personne ne valide pas sa propre demande, même depuis un autre de ses
   appareils qui aurait (par erreur) un rôle qui approuve : `403 {"error":"parent"}`.
4. **Le repli et la remise.** Le repli « une seule validation suffit » transformait une voix « code »
   ou « QR » (le décideur voulait la remettre en personne) en remise **directe**. Il ne s'applique plus
   qu'à une voix qui a choisi la remise directe ; sinon la demande attend une autre voix, ou expire.

Restent par conception : le code de gestion local est un passe-partout (adulte présent), journalisé
seulement quand l'appareil rapporte sa pause ; une voix déjà donnée reste comptée si son auteur perd
la ★ avant le quorum ; une fois le quorum atteint, un refus tardif n'annule plus l'approbation.
