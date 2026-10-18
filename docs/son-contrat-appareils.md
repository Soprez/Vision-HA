# Niveau sonore — contrat entre Home Assistant et les appareils

Module `son` (backlog n° 31, validé en essai le 11/10), **coupé par défaut**, puis allumé appareil par appareil.

## Vie privée (à dire tel quel à l'écran)

- L'appareil mesure **seulement le niveau** du bruit, sur place. Il **n'enregistre jamais** le son et **n'envoie
  jamais d'audio** : seuls des nombres partent vers Home Assistant (niveau, maximum, heure et niveau d'un dépassement).
- L'indicateur « micro utilisé » du système reste visible pendant la mesure : c'est voulu.
- Sans micro, ou sans l'autorisation du micro, la mesure ne démarre pas et l'écran le dit.

## Réponse au relevé (`POST /api/pc_parental/poll`) : bloc `son`

```json
{"actif": false}
```

ou, quand le module est allumé ET la mesure activée pour cet appareil :

```json
{"actif": true, "seuil_db": 75, "sensibilite_s": 5, "plages": [{"debut": "08:00", "fin": "20:00"}],
 "calibration_db": 0, "alertes": 1, "alertes_avant_coupure": 3}
```

- `seuil_db` : niveau relatif de 0 (silence numérique) à 100 (pleine échelle du micro), calibration comprise.
- `sensibilite_s` : secondes passées au-dessus du seuil avant un dépassement (un pic plus court est ignoré).
- `plages` : vide = toute la journée ; une plage peut passer minuit. Hors plage, l'appareil ne signale rien.
- `alertes` / `alertes_avant_coupure` : pour l'affichage « alerte 2 sur 3 » (0 = alertes seules, jamais de coupure).

## Corps du relevé envoyé par l'appareil : bloc `son`

```json
{"niveau": 61.2, "maximum": 83.0, "depassements": [{"ts": 1792000000000, "db": 82.5}]}
```

- `niveau` : moyenne de la dernière minute ; `maximum` : le plus haut de la dernière minute (dB relatifs, calibrés).
- `depassements` : ceux qui n'ont pas encore été remis (`ts` en millisecondes). Home Assistant ignore un
  dépassement déjà reçu : l'appareil peut renvoyer la liste tant qu'un relevé n'a pas abouti.
- Tout autre champ est ignoré par Home Assistant.

## Ce que fait Home Assistant

- Chaque dépassement (dans les plages) compte une alerte et lance `vision_son_seuil_depasse`
  (`appareil`, `nom`, `niveau`, `seuil`, `alerte`, `alertes_avant_coupure`).
- À la N-ième alerte : coupure de l'appareil pendant `coupure_min` (même dérogation que le service `lock`),
  événement `vision_son_coupure` (`appareil`, `nom`, `minutes`, `alertes`), compteur remis à zéro.
- Sans dépassement pendant `calme_min`, le compteur revient à zéro.
- Capteur « Niveau sonore » par appareil (dB relatifs ; attributs `maximum`, `alertes`, `alertes_avant_coupure`,
  `seuil`) ; indisponible si la mesure est éteinte ou sans relevé depuis cinq minutes.
- Réglages : service `pc_parental.son_regler` (administrateurs et automatisations).

## Ce que fait l'appareil

- Il mesure seulement pendant que `actif` est vrai, et s'arrête dès qu'un relevé dit `{"actif": false}`.
- À chaque dépassement, il affiche aussitôt une alerte (« Trop de bruit · alerte 2 sur 3 ») et le remet au relevé
  suivant ; la coupure arrive par l'état habituel de l'appareil (fermé jusqu'à…), comme pour `lock`.
