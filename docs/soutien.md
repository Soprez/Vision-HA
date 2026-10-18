# Soutien du projet — badge et code de soutien

Vision est gratuit et open source (MIT). Un foyer qui le souhaite peut me payer un café
(https://buymeacoffee.com/soprez). En retour, et seulement pour le plaisir, Vision affiche un badge
« Soutien » et une petite animation : l'œil Vision fait un clin d'œil et un cœur apparaît, de temps
en temps, sur la veille et dans « À propos ». **Rien ne se débloque d'autre** : aucune fonction n'est
réservée aux soutiens.

## Deux façons de l'activer

Dans Home Assistant : **Paramètres → Appareils et services → Vision → Configurer → ☕ Payez-moi un café**
(réservé aux administrateurs, c'est-à-dire aux Décideurs du lieu).

La page demande directement **le code reçu par e-mail** après un café. Vision vérifie sa signature
**hors ligne**, avec la clé publique embarquée dans l'intégration (`soutien.CLE_PUBLIQUE_B64`). Rien
n'est envoyé nulle part. Depuis le 15/10/2026, il n'y a plus de « café sur l'honneur » : un code est
envoyé à chaque café. Un badge déjà obtenu sur l'honneur reste affiché (mode `honneur`).

L'état est gardé par l'intégration (`.storage/pc_parental`, clé `soutien`) et transmis aux appareils
dans chaque relevé (`"soutien": {"actif": true, "mode": "code" | "honneur"}`), sans autre donnée.

## Format du code (version 1)

| Octets | Contenu |
|---|---|
| 0 | version : `0x01` |
| 1–6 | identifiant opaque, 6 octets tirés au hasard par le service (aucune donnée personnelle) |
| 7–8 | date d'émission : nombre de jours depuis le 2025-01-01 (UTC), gros-boutiste |
| 9–72 | signature Ed25519 de `b"vision-soutien-v1" + octets[0:9]` |

Les 73 octets sont écrits en **base32 de Crockford** (alphabet `0123456789ABCDEFGHJKMNPQRSTVWXYZ`,
bits de poids fort d'abord, 1 bit de bourrage à zéro) : **117 caractères**, groupés par 5 avec des
tirets. À la lecture : majuscules, espaces / tirets / retours à la ligne ignorés, `O` lu `0`, `I` et
`L` lus `1` ; un préfixe `VISION-` facultatif est accepté. Le bourrage doit être nul (un seul texte
possible par code).

La signature Ed25519 fait à elle seule 64 octets : un code plus court n'est pas possible sans changer
d'algorithme. Le code est fait pour être copié-collé depuis l'e-mail.

Le préfixe de domaine `vision-soutien-v1` empêche de réutiliser une signature faite pour autre chose
avec la même clé.

## Limites assumées

- Un code peut être partagé : il est valable partout. C'est un badge décoratif, pas une licence.
- Pas de liste de révocation pour l'instant. Si un jour il en faut une, l'identifiant opaque (octets
  1–6) permet d'en embarquer une dans l'intégration sans rien changer au format.
- La clé privée ne vit que sur le serveur du service (voir `vision-soutien-service/README.md`).
