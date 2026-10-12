# Notes de version — Release notes

[Français](#français) · [English](#english)

## Français

Un fichier par version, `<version>.json` (ex. [2.2.0.json](2.2.0.json)), qui porte **toutes les langues** de chaque
point côte à côte. Pourquoi un seul fichier plutôt qu'un `CHANGELOG.<langue>.md` par langue :

- une traduction manquante se voit à côté du français, au lieu de manquer en silence dans un autre fichier ;
- le même fichier alimente la fenêtre « Quoi de neuf » de l'appli et de la télé, la notification de Home Assistant
  et le journal des versions du dépôt public (voir la maquette `docs/maquettes/notes-de-version.html`) ;
- chaque point garde son **public** et ses **appareils**, ce qu'un fichier Markdown par langue ne sait pas porter.

Structure (modèle : [modele.json](modele.json)) :

| Champ | Contenu |
|---|---|
| `version`, `date` | la version publiée et sa date (AAAA-MM-JJ) |
| `titre` | le titre de la fenêtre, par langue |
| `points[].public` | `tous`, `decideurs`, `demandeurs` ou `administrateurs` (réglages de Home Assistant) |
| `points[].appareils` | parmi `telephone`, `tele`, `pc`, `ha` |
| `points[].textes` | le texte par langue : `fr` (source), `en` (repli), puis `de`, `es`, `it`, `nl` |

Règles d'écriture : 5 points au plus, une phrase chacun, sans jargon ; les correctifs internes ne sont pas listés ;
vocabulaire neutre (jamais « famille », « maison », « parent », « enfant » : on dit Décideur, Demandeur, lieu —
voir `docs/i18n/glossaire.md`). Une langue absente ou vide s'affiche en anglais.

État des langues : 2.2.0 en français et en anglais ; allemand, espagnol, italien et néerlandais à venir (champs à
ajouter dans le même fichier).

## English

One file per version, `<version>.json` (e.g. [2.2.0.json](2.2.0.json)), holding **every language** of each item
side by side, so that a missing translation shows next to the French source, and the same file feeds the in-app
“What's new” window, the Home Assistant notification and the public changelog. Each item has an audience
(`public`: `tous` = everyone, `decideurs` = deciders, `demandeurs` = requesters, `administrateurs` = Home Assistant
admins) and devices (`appareils`: `telephone`, `tele`, `pc`, `ha`). Template: [modele.json](modele.json).
French is the source, English the fallback; German, Spanish, Italian and Dutch fields are to be added in the same
file.
