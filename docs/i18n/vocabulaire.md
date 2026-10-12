# Vocabulaire neutre — les champs des textes de Vision

Vision ne dit jamais « maison », « parent » ni « enfant », dans aucune langue (ni home/house, parent, child/kid,
Eltern, Kind, padres, niños…). Il dit le rôle — **Décideur**, **Demandeur** — et le **nom du lieu** choisi par
l'administrateur (« Lieu » par défaut, ou « École », « Bureau »…).

Ces trois libellés deviendront personnalisables (réglage « Vocabulaire », à venir). Dans les textes, ils ne
s'écrivent donc pas en dur : ce sont des champs, remplis au moment d'afficher.

## Les champs

| Champ | Contenu | Exemple (fr, défaut) |
|---|---|---|
| `{decideur}` | le terme, minuscule, singulier | décideur |
| `{decideurs}` | minuscule, pluriel | décideurs |
| `{Decideur}` / `{Decideurs}` | majuscule initiale (début de phrase, titre) | Décideur / Décideurs |
| `{demandeur}` / `{demandeurs}` | minuscule | demandeur / demandeurs |
| `{Demandeur}` / `{Demandeurs}` | majuscule initiale | Demandeur / Demandeurs |
| `{lieu}` | le nom du lieu, tel qu'il est écrit (s'emploie seul, comme un titre) | École |

Un texte n'emploie le lieu que seul, comme un titre ; une phrase qui aurait besoin d'un article est tournée
autrement (« tous les appareils », « pour tout le monde »).

## Les termes par défaut (glossaire)

| | fr | en | de | es | it | nl |
|---|---|---|---|---|---|---|
| décideur | décideur | decider | Entscheider | responsable | decisore | beslisser |
| décideurs | décideurs | deciders | Entscheider | responsables | decisori | beslissers |
| demandeur | demandeur | requester | Anfragender | solicitante | richiedente | aanvrager |
| demandeurs | demandeurs | requesters | Anfragende | solicitantes | richiedenti | aanvragers |

La majuscule initiale (`{Decideur}`, `{Lieu}`…) se déduit du terme.

## Côté code (intégration Home Assistant)

- `pc_parental/vocabulaire.py` (module partagé par les lots) : `termes(langue, store=None)` (alias `valeurs`) rend
  tous les champs dans une langue ; `{lieu}` = `lieu.nom(store)` quand on passe le magasin, sinon « Maison ».
  Aussi `remplir(texte, langue, store, **champs)` et une classe `Catalogue` dont `t()`/`n()` acceptent `store=`.
- `pc_parental/textes.py` (catalogue serveur) : `t()` / `n()` / `Phrase` / `Erreur` remplissent ces champs d'office
  dès qu'un texte en contient, avec le magasin de l'instance branchée ; un champ passé explicitement l'emporte.
- **Futur réglage** : `store.mots_vocabulaire[langue][champ]` (dictionnaire ; `store.vocabulaire` est la liste des
  étiquettes, sans rapport). Absent ou vide : le défaut ci-dessus. Il faudra aussi l'ajouter à `dashboard.signature()`
  pour que le tableau se refasse quand un mot change, et le transmettre aux cartes par `poserVocabulaire`.
- Les noms des rôles enregistrés (« Décideur », « Demandeur ») ne changent pas : à l'installation, ils prennent
  les termes du moment dans la langue de Home Assistant, puis ce sont des données comme les autres.
