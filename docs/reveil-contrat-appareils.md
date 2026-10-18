# Réveil — contrat entre Home Assistant et les appareils

Côté HA : `pc_parental/reveil*.py` (lot 1). Côté appareil : à faire (lot 4 Android, lot 9 agent PC).
Ce document est la référence. Les tests `essai/reveil_test.py` en rejouent chaque point.

## 1. Principe

- **L'appareil sonne seul.** HA calcule les occurrences et les **transmet à l'avance**, sur 48 h, dans le relevé (`POST /api/pc_parental/poll`). L'appareil arme ses propres alarmes et sonne même si HA ou le réseau sont tombés, avec sa dernière liste connue.
- **HA ne fait jamais sonner « à l'heure ».** Il synchronise la liste, il transmet des **ordres** (sonner maintenant, reporter, arrêter) au relevé suivant, et il reçoit les **événements** de l'appareil.
- **Champs inconnus.** Un appareil ignore les champs qu'il ne connaît pas. Une ancienne appli ignore donc tout le bloc.

## 2. HA → appareil : le bloc `reveils` du relevé

```json
"reveils": {
  "actif": true,
  "version": "3f2a9c01b7de",
  "genere": "2026-10-11T21:00:00+02:00",
  "horizon_h": 48,
  "synchro_avant_min": 10,
  "liste": [
    {
      "id": "rv1a2b3c4d",
      "nom": "Collège",
      "demandeur": "person.alex",
      "signature": "9d1e0c2f4a77",
      "occurrences": [
        {"id": "rv1a2b3c4d@2026-10-12", "date": "2026-10-12", "heure_locale": "07:00",
         "heure": "2026-10-12T07:00:00+02:00", "raison": "hebdo"}
      ],
      "source": {"type": "url", "uri": "https://radio.example/flux.mp3", "libelle": "Radio"},
      "repli": "sonnerie",
      "volume": {"debut": 10, "fin": 60, "montee_s": 180},
      "repetition": {"minutes": 9, "max": 3},
      "arret_auto_min": 30,
      "allumer_tv": true,
      "impose": false
    }
  ],
  "ordres": []
}
```

### Ce que l'appareil doit faire (obligatoire)

1. **Désarmer tout** si `actif` vaut faux, si `"reveil"` manque dans la liste `modules` du relevé, ou si le bloc a disparu alors que l'appareil avait des réveils. L'appareil désarme aussi chaque réveil dont l'`id` ne figure plus dans `liste`.
2. **Armer chaque occurrence** à **`date` + `heure_locale`, dans le fuseau de l'appareil**. Ce n'est jamais un instant UTC figé : un changement d'heure ou de fuseau se recalcule localement. `heure` (instant ISO) ne sert qu'à l'affichage et au contrôle. Sur Android, utiliser `AlarmManager.setAlarmClock`.
3. **Armer une seconde alarme** à H − `synchro_avant_min` minutes, qui force un relevé immédiat. Elle prend ainsi en compte une modification de dernière minute faite par une automatisation.
4. **Garder la dernière liste** en stockage persistant. La réarmer au démarrage, après une mise à jour de l'appli, et à chaque changement d'heure ou de fuseau. Ne réarmer qu'à un changement de `version` : elle change dès qu'un réveil change **ou** qu'une nouvelle occurrence entre dans la fenêtre de 48 h.
5. **Accuser réception** au relevé suivant, en renvoyant `recu` et `armees` (§4).

### Les ordres (`ordres`)

Chaque ordre est remis **une seule fois**. L'exécuter tout de suite et ignorer un `id` déjà vu.

| `type` | Champs | Effet |
|---|---|---|
| `sonner` | `reveil` (vide = sonnerie sans réveil enregistré), `occurrence` (`ordre:…`), `nom`, `source`, `repli`, `volume`, `repetition`, `arret_auto_min`, `allumer_tv`, `par` | Sonne maintenant, exactement comme une occurrence. HA ne remet plus un ordre `sonner` de plus de 15 min (réglable). |
| `reporter` | `reveil`, `occurrence`, `minutes` ou `jusqu_a`, `par` (`service`, `appli` ou `partage`) | Reporte la sonnerie en cours de ce réveil jusqu'à `jusqu_a`. |
| `arreter` | `reveil` (vide = tout ce qui sonne), `occurrence`, `par` | Arrête la sonnerie de ce réveil. |

## 3. Pendant la sonnerie (appareil)

- **Source.**
  - `url` ou `video` : lire `uri`.
  - `ma` : en V1, l'appareil joue sa sonnerie ; si `lecteur` est donné, HA lance aussi la lecture sur cette enceinte dès l'événement `sonne`, la met en pause au `reporte` et l'arrête à l'`arrete`. Si Music Assistant échoue : `vision_reveil_echec` (`detail: music_assistant`).
  - `sonnerie` : la sonnerie embarquée.
  - **Repli obligatoire** : si aucun son au bout de 20 s, ou si la source est en erreur, l'appareil joue la sonnerie locale au volume de fin et émet `echec` avec `raison: source` et `repli_joue: true`. Ce repli ne se désactive jamais.
- **Volume.** Monter de `volume.debut` à `volume.fin` en `volume.montee_s` secondes, sur le flux « alarme », en passant outre le mode silencieux.
- **Répéter.** Jusqu'à `repetition.max` fois, de `repetition.minutes` minutes chacune. L'appareil réarme localement. Au-delà, le bouton « Répéter » disparaît.
- **Arrêt automatique.** Au bout de `arret_auto_min` sans action, l'appareil émet `arrete` (`par: auto`) puis `echec` (`raison: non_acquitte`).
- **Arrêt partagé.**
  - Un arrêt ou un report sur un appareil est remonté à HA, qui transmet l'ordre aux autres appareils du réveil (`par: partage`).
  - Un appareil qui exécute un ordre `partage` le remonte avec `par: partage` : HA ne le renvoie pas plus loin et n'émet pas un second événement.
- **TV.** Si `allumer_tv` est vrai, rallumer l'écran du boîtier (le CEC « One Touch Play » suit, s'il est activé). Le test guidé et le repli HA viennent avec le lot 8.

## 4. Appareil → HA : le champ `reveils` du corps du relevé

```json
"reveils": {
  "recu": {"rv1a2b3c4d": "9d1e0c2f4a77"},
  "armees": ["rv1a2b3c4d@2026-10-12", "rv1a2b3c4d@2026-10-13"],
  "capacites": {"alarme_exacte": true, "plein_ecran": true, "sources": ["sonnerie", "url"]},
  "evenements": [
    {"eid": "8f0c…", "type": "sonne", "reveil": "rv1a2b3c4d", "occurrence": "rv1a2b3c4d@2026-10-12",
     "heure_prevue": "2026-10-12T07:00:00+02:00", "heure_reelle": "2026-10-12T07:00:02+02:00",
     "horodatage_appareil": "2026-10-12T07:00:02+02:00", "source": "url"}
  ]
}
```

- **`recu`** : pour chaque réveil armé, la `signature` reçue. Elle porte sur la configuration du réveil, pas sur la fenêtre d'occurrences : le temps qui passe ne la change pas.
- **`armees`** : les identifiants d'occurrences réellement armées. À H − 10 min, HA vérifie, pour chaque appareil visé, que `recu` correspond **et** que l'occurrence est dans `armees`. Sinon, HA passe l'état à `non_synchronise` et émet `vision_reveil_echec` (`raison: non_synchronise`). L'appareil sonnera quand même, sur sa version.
- **`capacites`** : `alarme_exacte: false` (permission retirée, batterie restreinte) fait émettre `vision_reveil_echec` (`raison: permission`).
- **`evenements`** :
  - à envoyer au relevé suivant, **et dès l'événement** (l'appareil est éveillé à ce moment-là) ;
  - à garder tant que le relevé n'a pas répondu 200 ;
  - `eid` est unique (UUID), et un `eid` renvoyé n'est jamais rejoué.

| `type` | Champs en plus |
|---|---|
| `sonne` | `heure_prevue`, `heure_reelle` |
| `reporte` | `nombre`, `jusqu_a`, `par` |
| `arrete` | `par` : `appareil` · `service` · `appli` · `auto` · `partage` |
| `echec` | `raison` : `source` · `tv_non_allumee` · `permission` · `non_acquitte`, `detail`, `repli_joue` |

Tous acceptent `horodatage_appareil`, qui marque un événement remonté en différé.

## 5. Les événements HA (pour les automatisations)

HA émet chaque événement **une fois par occurrence** : le premier appareil qui sonne, le premier arrêt, un report par numéro, un échec par appareil et par raison.

| Événement | Données |
|---|---|
| `vision_reveil_sonne` | `reveil`, `nom`, `demandeur`, `appareil`, `appareil_id`, `source`, `occurrence`, `heure_prevue`, `heure_reelle` |
| `vision_reveil_reporte` | id. + `nombre`, `jusqu_a`, `par` |
| `vision_reveil_arrete` | id. + `par` |
| `vision_reveil_echec` | id. + `raison`, `detail`, `repli_joue` |

HA émet aussi lui-même `vision_reveil_echec`, avec `raison: heure_invalide` (heure calculée invalide et sans repli, dès l'évaluation) ou `raison: non_synchronise` (H − 10 min). Ces événements portent `horodatage_appareil` quand l'appareil l'a fourni.

## 6. La porte de l'appli : `POST /api/pc_parental/reveils`

Elle sert aux écrans Réveils (lot 5).

- **Authentification** : `id` + `secret` de l'appareil.
- **Droits** :
  - les fonctions de rôle de l'appareil : `reveil_gerer` pour tous les Demandeurs, `reveil_propres` pour ses réveils ;
  - le Demandeur visé peut toujours voir, reporter et arrêter ;
  - un réveil `impose` ne se modifie qu'avec `reveil_gerer`.

| `action` | Corps en plus | Réponse |
|---|---|---|
| `liste` | — | `reveils` (vue + `peut`, `automatisation`), `droits {gerer, propres}`, `demandeurs` (personnes et leurs appareils), `moi`, `noms` (nom affiché de chaque personne citée) |
| `creer` | `reveil` : les champs du modèle (`nom`, `demandeur` (soi par défaut), `appareils`, `declenchement {type, heure, jours, date, entite, attribut, modele, decalage, bornes, repli}`, `source`, `volume`, `repetition`, `impose`, `allumer_tv`, `actif`) | `reveil` |
| `modifier` | `reveil_id`, `reveil` (partiel) | `reveil` |
| `activer`, `desactiver`, `supprimer` | `reveil_id` | `reveil` ou `supprime` |
| `sauter` / `reprendre` | `reveil_id`, `date` (facultatif) | `reveil` |
| `sonner`, `reporter` (`minutes`), `arreter` | `reveil_id` | `reveil` |

**Erreurs :**
- 400 `{"error": "invalide", "cle", "retour"}` : `cle` est aussi la clé de traduction `exceptions.reveil_<cle>` ;
- 401 `auth` ;
- 403 `droits` ou `module` ;
- 404 `introuvable` ;
- 503 `loading`.

## 7. Le modèle d'un réveil (vue renvoyée par `liste` et par `reveil_lister`)

`id`, `nom`, `demandeur`, `actif`, `etat` (`inactif` · `arme` · `sonne` · `reporte` · `arrete` · `echec` · `non_synchronise`), `prochain` (ISO ou null), `raison` (`hebdo` · `ponctuel` · `calculee` · `repli` · `passee` · `invalide` · `sautee`), `occurrence_suivante`, `sautees`, `declenchement`, `sauter`, `source`, `volume`, `repetition`, `impose`, `allumer_tv`, `appareils` (ids visés), `appareils_noms`, `appareils_choisis` (vide = téléphones et tablettes du Demandeur par défaut), `synchronises`, `origine` (`automatisation` · `home_assistant` · `appli`), `cree_par`, `version`, `calcul`.

## 8. Agent PC (lot 9)

Même bloc dans le relevé. L'agent garde la liste, crée une tâche planifiée `-WakeToRun` par occurrence, et remonte les mêmes champs (`recu`, `armees`, `evenements`).
