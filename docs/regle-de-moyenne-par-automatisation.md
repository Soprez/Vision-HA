# Fermer des catégories selon les notes : une automatisation, pas une option de Vision

Jusqu'à la 1.59.0, Vision avait une « règle de moyenne » : sous un seuil de moyenne générale
Pronote, le divertissement d'un élève se fermait. Elle a quitté l'intégration en 1.60.0.
Pronote est propre à l'école française, et une règle de notes n'a pas à être câblée dans
Vision : chaque installation a sa source de notes, ses seuils, ses exceptions. Une
automatisation fait ce travail aussi bien, et s'adapte à tout.

Vision fournit les **primitives** ; l'automatisation décide.

## Les primitives

| Service | Champs | Effet |
|---|---|---|
| `pc_parental.fermer_categorie` | `personne` (entité `person.*` ou prénom), `categorie` (une ou plusieurs étiquettes), `raison`, `source` | Ferme ces catégories sur **tous** les appareils de la personne. La `raison` est ce que la personne lit sur son écran, et ce que le gestionnaire voit. |
| `pc_parental.ouvrir_categorie` | `personne`, `categorie` (vide = toutes), `source` | Rouvre ce que **cette source** a fermé. Les fermetures des autres sources restent. |

- **`source`** : le nom que l'automatisation se donne (`moyenne`, `devoirs`…). Une catégorie reste
  fermée tant qu'une source au moins la ferme. Sans `source`, tout se range sous la source par
  défaut, comme avant la 1.60.0 — c'est ce que font les automatisations écrites avant.
- **Rang** : une fermeture par automatisation passe derrière les exceptions et le « toujours
  autorisé » posé sur un nom ; le planning reste plus fort qu'elle.
- **Refus** : une personne ou une catégorie inconnue est refusée avec un message (avant la 1.60.0,
  elle était enregistrée sans rien fermer).
- **Lecture** : le capteur « Fermetures par automatisation <prénom> » (par exemple
  `sensor.vision_fermetures_par_automatisation_lou`) donne le nombre de
  catégories fermées, et en attributs chaque fermeture (catégorie, raison, source, depuis). Le
  capteur d'état de chaque appareil liste aussi ces fermetures dans `categories_coupees`.

## Le modèle : une règle de moyenne en service

Exemple pour un Demandeur (« Lou ») ; pour un autre Demandeur, la même en changeant les noms. Trois
assistants la règlent : la règle allumée ou non, « passer outre », et le seuil.

```yaml
- id: vision_moyenne_lou
  alias: 'Vision : divertissement de Lou selon sa moyenne'
  description: >-
    Sous le seuil, ferme le divertissement de Lou dans Vision en disant pourquoi ;
    au-dessus, le rouvre. Pronote muet : rien ne change, la dernière décision tient.
  triggers:
  - trigger: state
    entity_id:
    - input_boolean.vision_regle_moyenne_lou
    - input_boolean.vision_passer_outre_lou
    - input_number.vision_seuil_moyenne_lou
    - sensor.pronote_lou_overall_average
    not_to: [unavailable, unknown]
  - trigger: time_pattern
    minutes: 7
  - trigger: homeassistant
    event: start
  variables:
    moyenne: "{{ states('sensor.pronote_lou_overall_average') | replace(',', '.') | float(none) }}"
    seuil: "{{ states('input_number.vision_seuil_moyenne_lou') | float(12) }}"
    categories: [Jeux, Vidéos, Réseaux sociaux, TV]
  actions:
  - choose:
    - conditions:          # règle éteinte, ou un gestionnaire passe outre : on rouvre
      - condition: or
        conditions:
        - condition: state
          entity_id: input_boolean.vision_regle_moyenne_lou
          state: 'off'
        - condition: state
          entity_id: input_boolean.vision_passer_outre_lou
          state: 'on'
      sequence:
      - action: pc_parental.ouvrir_categorie
        data: {personne: person.lou, categorie: "{{ categories }}"}
    - conditions: "{{ moyenne is none }}"     # Pronote muet : on ne touche à rien
      sequence: []
    - conditions: "{{ moyenne < seuil }}"
      sequence:
      - action: pc_parental.fermer_categorie
        data:
          personne: person.lou
          categorie: "{{ categories }}"
          raison: >-
            Fermé tant que ta moyenne est sous {{ '%g' | format(seuil) | replace('.', ',') }}
            (elle est à {{ '%g' | format(moyenne) | replace('.', ',') }}). Ça rouvrira avec les notes.
    default:
    - action: pc_parental.ouvrir_categorie
      data: {personne: person.lou, categorie: "{{ categories }}"}
  mode: single
  max_exceeded: silent
```

## Les deux pièges de Pronote

1. **La virgule.** L'intégration Pronote donne la moyenne en texte français : `"9,47"`. Une
   condition `numeric_state` ne sait pas la lire et tombe toujours à faux — ce qui, ici, rouvrirait.
   D'où `replace(',', '.') | float(none)`.
2. **La panne.** Le capteur passe `unavailable` quand Pronote ne répond pas (parfois des
   heures). Une automatisation naïve rouvrirait pendant toute la panne. D'où le déclencheur
   `not_to: [unavailable, unknown]` et la branche « Pronote muet : on ne touche à rien » : les
   fermetures sont enregistrées dans Vision, elles tiennent d'elles-mêmes.

## L'adapter

- **Une autre source de notes** : remplacer le capteur Pronote dans `triggers` et dans `moyenne`.
  Garder la conversion en nombre et la branche « source muette ».
- **D'autres catégories** : changer la liste `categories` (des étiquettes cochées comme catégories
  dans Vision → Autorisations → Étiquettes).
- **Plusieurs règles sur les mêmes catégories** (les notes, les devoirs, le temps dehors) : donner à
  chacune sa `source` dans **les deux** services. Chacune ne rouvre alors que ce qu'elle a fermé.
- **Une autre condition que les notes** : seul le `choose` change ; les primitives sont les mêmes.
