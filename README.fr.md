# Vision

[English](README.md) · **Français** · Deutsch · Español · Italiano · Nederlands

**Site et documentation : https://soprez.org/vision**

> Vision parle six langues : français, anglais, allemand, espagnol, italien et néerlandais (intégration
> Home Assistant, cartes du tableau de bord, agent Windows ; chaque appareil suit sa propre langue). Ce README
> existe en anglais et en français ; les versions allemande, espagnole, italienne et néerlandaise (`README.de.md`,
> `README.es.md`, `README.it.md`, `README.nl.md`) ne sont pas encore écrites — les contributions sont les
> bienvenues. Notes de version : [docs/notes-de-version](docs/notes-de-version/).

Des règles d'usage des écrans pour Home Assistant : Vision veille sur les ordinateurs Windows, les téléphones et
les télés Android d'un lieu — une école, un club, un bureau, tout espace partagé — avec des plages horaires, des
catégories d'applications et de sites, et des demandes de temps que les Décideurs acceptent ou refusent.

Tout reste chez vous : Home Assistant décide, les appareils appliquent. Aucun service extérieur n'est nécessaire.

## Ce qu'il faut

- Home Assistant **2026.9** ou plus récent (voir *Paramètres → À propos*). Sur une version plus ancienne, HACS refuse
  de télécharger Vision, qui n'apparaît alors jamais dans *Ajouter une intégration* : mettez d'abord Home Assistant à jour.
- [HACS](https://hacs.xyz) pour l'installation et les mises à jour.
- Des appareils joignables depuis Home Assistant : PC sous Windows 10/11, téléphones ou télés sous Android.

## Installer

Vision a trois parties : l'**intégration** Home Assistant (installée par HACS), l'**application Android** et
l'**agent Windows**. Les deux dernières sont fournies par l'intégration elle-même : rien d'autre à télécharger.

### 1. L'intégration (HACS, dépôt personnalisé)

1. Dans HACS : **⋮ → Dépôts personnalisés**, ajoutez `https://github.com/Soprez/Vision-HA`,
   catégorie **Intégration**.
2. Cherchez **Vision** dans HACS (son dossier s'appelle `pc_parental`), cliquez sur *Télécharger*, puis redémarrez
   Home Assistant.
3. **Paramètres → Appareils et services → Ajouter une intégration → Vision.**

HACS installe le fichier `vision.zip` de la dernière version publiée (onglet *Releases*) : il contient
l'intégration **et** l'APK signé de l'application Android.

À l'installation, Vision **n'enferme personne** : aucun appareil, aucune plage, aucune catégorie fermée. Seule la
liste des domaines dangereux (logiciels malveillants, hameçonnage) est active ; elle se coupe dans les réglages.

Le **nom du lieu** (« École », « Club », « Bureau »…) se règle dans **Vision → Configurer → Réglages généraux** :
il titre le premier onglet du tableau de bord et la fenêtre Vision des PC.

#### Si Vision n'apparaît pas

1. Vérifiez votre version de Home Assistant (**2026.9** minimum) et mettez HACS à jour.
2. Vérifiez que le dépôt personnalisé a été ajouté avec la catégorie **Intégration** (et non *Tableau de bord* ou
   *Appdaemon*), et que HACS est connecté à GitHub (*HACS → ⋮ → Paramètres*).
3. Cherchez **Vision** dans HACS (sans filtre), ouvrez-la et cliquez sur *Télécharger*. Si HACS affiche une erreur,
   notez son message exact pour le signaler.
4. Redémarrez Home Assistant, rechargez la page avec Ctrl+F5, puis **Paramètres → Appareils et services →
   Ajouter une intégration → Vision**.

#### Après une mise à jour : les nouvelles cartes du tableau

Le tableau Vision vous appartient : une mise à jour ne le refait jamais, donc une **nouvelle carte** (par exemple
*Vue d'ensemble*, ajoutée en 2.5.12.14 : tous les appareils et toutes les personnes dans une liste filtrable)
n'apparaît pas toute seule. Deux façons de l'obtenir :

1. **Refaire le tableau** (le plus simple) : appuyez sur le bouton **Régénérer le tableau de bord** (*Paramètres →
   Appareils et services → Vision*). Votre tableau actuel est d'abord sauvegardé (dossier `vision_outils`, puis `sauvegardes`, dans votre dossier
   de configuration) ; ce que vous aviez changé à la main n'est pas repris.
2. **Garder votre tableau** : *⋮ → Modifier le tableau de bord*, ajoutez une carte *Manuelle* et collez
   `type: custom:vision-ensemble-card` avec `mode: logiciels` (logiciels et sites de tous les appareils, onglet
   *Protection*) ou `mode: limites` (limites de temps de tous, onglet *Utilisateur*).

### 2. L'application Android (téléphones, tablettes et télés)

1. Dans le tableau de bord Vision, la rubrique **Installer Vision sur un appareil** donne le lien de l'APK (servi
   par votre Home Assistant), son code QR, et une adresse courte à taper sur une télé :
   `http://<adresse-ha>:8123/vision` (par exemple avec l'appli *Downloader*).
2. Sur l'appareil, ouvrez ce lien, téléchargez l'APK et autorisez l'installation depuis le navigateur
   (« Sources inconnues »).
3. Ouvrez Vision et inscrivez l'appareil : **Vision → Configurer → Ajouter un appareil** dans Home Assistant
   affiche un QR d'appairage à scanner depuis l'appli (valable quelques minutes, validé par un administrateur).

Les mises à jour arrivent ensuite d'elles-mêmes avec celles de l'intégration. L'APK est aussi joint à chaque
version publiée (onglet *Releases*) ; vérifiez sa signature (empreinte plus bas).

### 3. L'agent Windows (PC)

1. Dans Home Assistant, **Vision → Configurer → Ajouter un appareil** (choisir un PC) donne une ligne à coller
   dans PowerShell, adresse et jeton d'appairage déjà remplis.
2. Collez-la sur le PC ; l'installateur demande l'autorisation administrateur, puis fait le reste.
3. Le compte de la personne suivie doit être un compte **standard** : un administrateur peut arrêter l'agent.

L'agent, l'installateur et le désinstalleur parlent la langue de Windows.

Un appareil inscrit apparaît dans Home Assistant **ouvert et sans plage**. Quand tous les appareils sont équipés,
**fermez les inscriptions** (interrupteur « Inscription de nouveaux appareils ») : c'est la porte d'entrée de
Vision.

## Rattacher les appareils aux personnes

Vision lit les personnes de Home Assistant (**Paramètres → Personnes**) : rien à saisir en double. Un appareil
appartient à une personne ; ses règles le suivent sur tous ses appareils.

Le rôle se donne **à un appareil** (réglage « Rôle de l'appareil », par un administrateur) : un appareil de rôle
« Décideur » peut répondre aux demandes depuis son téléphone. Les mots Décideur, Demandeur et le nom du lieu
s'adaptent à votre lieu.

## Poser la première règle

Dans le tableau de bord Vision, choisissez un appareil ou une personne, puis ajoutez une plage horaire (par exemple
« Nuit, 22 h → 7 h, tous les jours »). Les catégories (« Jeux », « Vidéos »…) sont des étiquettes de Home
Assistant : cochez celles qui servent dans **Vision → Autorisations → Étiquettes**. Leur nom s'affiche dans la
langue de chacun.

Pour une règle qui dépend d'autre chose que l'heure (une moyenne scolaire, une corvée), Vision fournit deux
actions, `pc_parental.fermer_categorie` et `pc_parental.ouvrir_categorie`, à appeler depuis une automatisation. Un
modèle complet est dans [docs/regle-de-moyenne-par-automatisation.md](docs/regle-de-moyenne-par-automatisation.md).

## Mode Party et automatisations

Pendant une Party, Vision émet deux événements Home Assistant : `vision_party_started` au lancement et
`vision_party_stopped` à l'arrêt, avec l'écran, l'enceinte, l'appareil et la personne qui l'ont déclenché
(`ecran`, `lecteur`, `appareil`, `personne`).
Branchez-y vos propres automatisations (lumières, alertes de caméras, scène de fête…) : Vision ne touche lui-même
à aucune de vos entités.
Exemple de déclencheur : `trigger: event` avec `event_type: vision_party_started`.

## Signature de l'application Android

L'application est signée par une clé unique ; Android n'installe une mise à jour que si elle porte la même
signature. Empreinte SHA-256 du certificat :

```
A7:7C:D3:E3:A2:A9:1E:DA:5E:8E:D3:B4:58:D9:90:D0:47:37:CB:A7:A5:84:69:D3:96:8E:BC:3B:10:FE:C1:B7
```

## ☕ Payez-moi un café

Vision est gratuit. S'il vous rend service, vous pouvez me payer un café : https://buymeacoffee.com/soprez
C'est facultatif : rien ne se débloque, un badge « ♥ Soutien » s'affiche seulement sur vos appareils.

## Contribuer

Voir [CONTRIBUTING.md](CONTRIBUTING.md) (en anglais : [CONTRIBUTING.en.md](CONTRIBUTING.en.md)). Failles de
sécurité : [SECURITY.md](SECURITY.md), jamais dans une issue publique.

## Licence

Vision est un logiciel libre, distribué sous la **licence MIT** : voir [LICENSE](LICENSE).
© 2026 Soprez. Cela vaut pour l'intégration, l'agent Windows et l'application Android, dont le code
source est publié.

Composants tiers embarqués, sous leur propre licence (toutes compatibles avec la licence MIT) : PyQRCode (BSD),
ZXing et AndroidX (Apache-2.0) dans l'application Android, et les polices Figtree et Outfit (SIL Open Font
License 1.1).
