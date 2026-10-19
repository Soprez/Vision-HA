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
{"actif": true, "seuil_db": 75, "nuit": false, "sensibilite_s": 5, "plages": [{"debut": "08:00", "fin": "20:00"}],
 "calibration_db": 0, "alertes": 1, "alertes_avant_coupure": 3}
```

- `seuil_db` : niveau relatif de 0 (silence numérique) à 100 (pleine échelle du micro, 0 dBFS), calibration comprise.
  **2.5.12.3** : c'est le seuil **du moment** : le seuil de jour, ou le seuil de nuit pendant la nuit de l'appareil (ses
  plages « Verrouiller la session », sinon la plage de repli des réglages). `nuit` le dit ; l'appareil n'a pas à
  connaître le planning.
- `sensibilite_s` : secondes passées au-dessus du seuil avant un dépassement (un pic plus court est ignoré).
- `plages` : vide = toute la journée ; une plage peut passer minuit. Hors plage, l'appareil ne signale rien.
- `alertes` / `alertes_avant_coupure` : pour l'affichage « alerte 2 sur 3 » (0 = alertes seules, jamais de coupure).

## Corps du relevé envoyé par l'appareil : bloc `son`

```json
{"niveau": 61.2, "maximum": 83.0, "pic10": 79.4, "instant": 58.0, "micros": 2,
 "depassements": [{"ts": 1792000000000, "db": 82.5}]}
```

- `niveau` : moyenne de la dernière minute ; `maximum` : le plus haut de la dernière minute (dB relatifs, calibrés).
- **2.5.12.3**, facultatifs : `pic10` (le plus haut des 10 dernières secondes), `instant` (la dernière seconde),
  `micros` (nombre de micros écoutés). Le PC (agent 1.93.4) les envoie ; l'appli Android peut les ignorer.
- **2.5.12.3** (précision de l'auteur), facultatifs : `micros_actifs` (micros pris en compte dans la moyenne) et
  `micros_liste` (leurs noms, 16 au plus, 80 caractères chacun). `micros_actifs: 0` = aucun micro allumé : Home
  Assistant oublie le niveau et le capteur « Niveau sonore » passe à indisponible (jamais 0). Absent (l'appli) :
  comportement d'avant.
- **Agent 1.93.5**, facultatif : `micros_detail`, chaque micro ouvert
  `{"nom": "...", "etat": "actif|muet|silence|sous_plancher|fige|sans_donnees", "niveau": 55.0}` (dB relatifs sur les 5 dernières
  secondes ; absent pour un micro muet, silencieux ou sans données). Home Assistant en garde 16, noms de 80
  caractères, état inconnu = `sans_donnees`, et les montre dans l'attribut `par_micro` (`niveau_dbfs`) des capteurs
  « Niveau sonore » et « Micros actifs ».
- `depassements` : ceux qui n'ont pas encore été remis (`ts` en millisecondes). Home Assistant ignore un
  dépassement déjà reçu : l'appareil peut renvoyer la liste tant qu'un relevé n'a pas abouti.
- Tout autre champ est ignoré par Home Assistant.

## Ce que fait Home Assistant

- **2.5.12.3, réaction graduée** : chaque dépassement compté dépose un message à l'écran de l'appareil (« Moins fort,
  s'il te plaît 🙂 ») ; un autre dans les `ferme_apres_min` suivantes : message ferme ; un troisième : les responsables
  sont prévenus par leurs canaux « alertes » (`prevenir_responsable`). Deux dépassements à moins de `repos_min` l'un de
  l'autre ne comptent qu'une fois. Le compteur du jour (`aujourd_hui`) est dans le capteur et l'événement.
- Un PC n'est jamais coupé par défaut (`alertes_avant_coupure` 0) ; sa sensibilité par défaut est de 3 s.
- **2.5.12.7, coupure graduée** : avec `alertes_avant_coupure` = N (réglable par appareil, entité « Bruit :
  avertissements avant coupure »), les N−1 premiers avertissements sont des messages (doux, puis ferme) et le N-ième
  ferme l'appareil `coupure_min` minutes (entité « Bruit : durée de la coupure ») : même dérogation que le service
  `lock` (écran de blocage, réouverture seule à l'échéance), message « Trop de bruit (N avertissements) : l'appareil
  est fermé … jusqu'à … », responsables prévenus (`son.responsable_coupure`, si `prevenir_responsable`), événement
  `vision_son_coupure`, puis la gradation repart de zéro. Le compteur revient à zéro après `calme_min` sans
  dépassement ; deux dépassements à moins de `repos_min` comptent une fois.
- **2.5.12.7, « Réagir au bruit »** (`reagir`, interrupteur par appareil, allumé par défaut) : éteint, la mesure
  continue, les dépassements sont comptés et journalisés, sans message, sans responsables, sans coupure.
- **2.5.12.7, journal** : chaque avertissement, coupure ou dépassement sans réaction est gardé (les 20 derniers :
  heure, type, rang, niveau et seuil en dBFS, durée) dans l'attribut `journal` du capteur « Niveau sonore », et écrit
  au journal de Home Assistant (WARNING pour les avertissements et coupures).
- **2.5.12.7, micros ignorés** (`micros_exclus`, service `son_regler`) : des morceaux de noms (sans tenir compte de la
  casse) ; un micro dont le nom en contient un est laissé hors du niveau (état `exclu`, son niveau reste visible dans
  `par_micro`). Utile pour un casque collé à la bouche, qui mesure la voix et non la pièce.

- Chaque dépassement (dans les plages) compte une alerte et lance `vision_son_seuil_depasse`
  (`appareil`, `nom`, `niveau`, `seuil`, `alerte`, `alertes_avant_coupure`).
- À la N-ième alerte : coupure de l'appareil pendant `coupure_min` (même dérogation que le service `lock`),
  événement `vision_son_coupure` (`appareil`, `nom`, `minutes`, `alertes`), compteur remis à zéro.
- Sans dépassement pendant `calme_min`, le compteur revient à zéro.
- Capteur « Niveau sonore » par appareil, **en dBFS** depuis la 2.5.12.3 (niveau relatif − 100 ; attributs `maximum`,
  `pic_10s`, `instant`, `niveau_relatif`, `micros`, `seuil`, `seuil_jour`, `seuil_nuit`, `nuit`, `alertes`,
  `alertes_avant_coupure`, `aujourd_hui`, `gradation`, `micros_actifs`, `micros_pris_en_compte`) ; indisponible si la
  mesure est éteinte, sans relevé depuis cinq minutes, ou sans aucun micro actif. Capteur « Micros actifs » (le
  nombre ; attributs `liste`, `micros_ecoutes`) : il reste disponible quand le niveau ne l'est plus (Home Assistant
  n'affiche pas les attributs d'un capteur indisponible), et dit donc « 0 » avec une liste vide. Réglages par appareil en entités quand le module est allumé : interrupteur « Mesurer le niveau
  sonore », nombres « Seuil sonore le jour » et « la nuit » (dBFS), « Bruit : durée avant alerte », « Bruit : délai
  entre deux alertes ».
- Réglages : service `pc_parental.son_regler` (administrateurs et automatisations).

## Ce que fait l'appareil

- Il mesure seulement pendant que `actif` est vrai, et s'arrête dès qu'un relevé dit `{"actif": false}`.
- À chaque dépassement, il affiche aussitôt une alerte (« Trop de bruit · alerte 2 sur 3 ») et le remet au relevé
  suivant ; la coupure arrive par l'état habituel de l'appareil (fermé jusqu'à…), comme pour `lock`.

## Le PC (agent Windows 1.93.4)

- Une sonde `son.ps1` tourne dans la session de l'utilisateur, lancée par l'agent seulement quand le relevé dit
  `actif: true`, arrêtée (et ses fichiers effacés) dès `actif: false`. Elle ouvre **tous les micros actifs** en
  capture **partagée** (WASAPI : les autres programmes gardent le micro), calcule un RMS toutes les 100 ms, fait la
  **moyenne (en énergie) des seuls micros allumés**, et n'écrit que des nombres dans `niveau-son.json` (moyenne et
  maximum de la minute, pic des 10 s, instant, nombre de micros, micros pris en compte et leurs noms, dépassements).
  Le tampon d'échantillons est remis à zéro après chaque calcul.
- **1.93.5** : la **composante continue** (DC) de chaque canal est retirée avant le RMS (sa moyenne sur la lecture
  est soustraite) : un micro décalé ne gonfle plus le plancher d'une pièce vide (référence l'auteur du 19/10 : −45 dBFS
  pièce vide sur le PC de Charlie avec l'agent 1.93.4). Un signal purement continu compte comme un silence
  numérique. Le niveau et l'état de chaque micro remontent dans `micros_detail`.
- Exclus de la moyenne (ils ne la tirent jamais vers le bas) : un micro **muet** dans Windows, un **silence
  numérique** (paquets marqués SILENT ou échantillons tous exactement à 0, soit −inf dBFS), un micro au niveau
  **figé** depuis plus de `micro_fige_s` secondes (10 par défaut, réglable par appareil, il revient dès qu'il bouge).
  **1.93.5 (retour d'essai)** : un micro sous `micro_plancher_dbfs` (−90 dBFS par défaut, avant calibration, réglable de −120 à
  −40 par appareil, service `son_regler`) ne capte rien et compte comme éteint (`sous_plancher`) ; constaté le 19/10
  sur PC-SAM (deux micros à −99 dBFS). Aucun micro au-dessus : capteur indisponible.
  Désactivé ou débranché : il n'est plus énuméré (réouverture toutes les 60 s, ou aussitôt s'il tombe en panne).
  Aucun micro actif pendant 5 s : la minute est oubliée et aucun niveau n'est écrit (`micros_actifs: 0`).
- Les réglages (seuil du moment, sensibilité, calibration, micro figé, plancher, micros ignorés, plages) lui sont déposés par l'agent dans
  `son-reglages.json`, relu toutes les 10 s. Les micros sont réouverts toutes les 60 s (branchements).
- Session **verrouillée** : la sonde continue (la session reste active pour Windows). Session **déconnectée** (plage
  « Verrouiller la session ») : les processus survivent, mais l'agent ne peut pas y lancer une sonde ; une sonde déjà
  lancée continue tant que Windows laisse l'audio à cette session (à vérifier sur le PC).
- Il n'y a pas d'alerte locale sur le PC : c'est le message déposé par Home Assistant (notification Windows de Vision)
  qui s'affiche. Le module « Messages » doit être actif pour cet appareil.
