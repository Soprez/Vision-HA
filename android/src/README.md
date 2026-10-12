# Vision Android

Agent Vision pour Android, relié à l'intégration Home Assistant
`pc_parental` (marque « Vision »). Un seul APK pour téléphones, tablettes,
Android TV, Google TV et Fire TV (Android 5.0 / API 21 et plus).

L'appli ne se cache pas : icône visible, notification permanente, nom « Vision ».

## Ce qu'elle fait

- **Blocage d'applis** par accessibilité, avec repli sans accessibilité (lecture
  du premier plan via les statistiques d'usage, utile sur TV).
- **Filtre DNS local** (VPN, uniquement le DNS passe par le tunnel) : sites
  bloqués, liste noire, **SafeSearch forcé** (Google, YouTube restreint, Bing,
  DuckDuckGo) et redirection vers le résolveur DNS filtrant choisi dans HA.
- **Verrouillage** et **plages horaires** décidés dans HA, appliqués même hors
  ligne avec le dernier état reçu.
- **Bouton « Demander +30 min »** sur l'écran de blocage : la demande remonte à
  HA pour validation par un Décideur.
- **Notification permanente** : état et prochaine fermeture.
- **Mode Décideur** par code : pause de 15 min ou 1 h, désinstallation protégée.
- **Anti-contournement** : écrans de réglages sensibles refermés, et l'état des
  protections est remonté à HA (alerte si une protection est coupée).

## Protocole

Identique à l'agent PC, sans jeton HA sur l'appareil :
- `POST /api/pc_parental/enroll` (clé d'inscription) → `id` + `secret`
- `POST /api/pc_parental/poll` toutes les ~20 s → état à appliquer
- `POST /api/pc_parental/liste-noire` quand la version change

Champs ajoutés par l'agent Android : `platform: "android"`, `protections{}`,
`demande_temps` (minutes).

## Compiler

```bash
gradle :app:assembleRelease -x lintVitalRelease
```

La clé de signature est `vision-release.jks`. Elle n'est pas dans ce dépôt, et son mot de passe
non plus : ils se fournissent par `keystore.properties`, ignoré par git.
Garder la même clé pour que les mises à jour s'installent par-dessus.

## Installer

- **Téléphone / tablette** : copier l'APK, l'ouvrir, suivre l'écran de config.
- **TV (TCL, Fire TV, Google TV)** : `outils/install-tv.sh <IP de la TV>`
  (débogage ADB activé sur la TV).

## Limites connues

- Android 13+ et APK hors Play Store : l'accessibilité peut être grisée
  (« paramètres restreints »), à débloquer une fois dans Infos de l'appli.
- Mode sans échec : désactive tout agent tiers (seul le mode Device Owner
  l'empêche, non couvert ici).
- Chromecast classique sans télécommande et Fire TV sous Vega OS : aucun APK
  installable, utiliser le blocage réseau Freebox pour ces appareils.
