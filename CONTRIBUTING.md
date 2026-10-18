# Contribuer à Vision

[English](CONTRIBUTING.en.md) · **Français**

Merci de votre intérêt ! Vision se compose de trois parties, toutes dans ce dépôt :

| Partie | Dossier | Essais |
|---|---|---|
| Intégration Home Assistant | `custom_components/pc_parental/` | `pytest` dans `essai/` |
| Application Android | `android/src/` | `gradle testDebugUnitTest` (Gradle 8.9, JDK 17+) |
| Agent Windows | `custom_components/pc_parental/client/` | `pytest` dans `essai/`, `essai/desinstall_essai.ps1` |

Site et documentation : https://soprez.org/vision

## Lancer les essais

Intégration et agent (Linux ou macOS, Python 3.14) :

```sh
python -m venv .venv && . .venv/bin/activate
pip install -r essai/requirements.txt
cd essai && python -m pytest -q -p no:cacheprovider -o asyncio_mode=auto
```

Sous Windows, le plus simple est l'image Docker du banc : `essai/banc/lancer.sh` (voir `essai/banc/Dockerfile`).
Lisez toujours la **dernière** ligne en entier : « 1 failed, 8 passed » est un échec.

Application Android : `gradle -p android/src testDebugUnitTest`. Les essais sur émulateur (`androidTest`) ne sont
pas lancés par l'intégration continue.

Chaque demande de fusion passe aussi par **hassfest** (validation officielle de Home Assistant) et par la
**validation HACS** : voir `.github/workflows/`.

## Avant d'ouvrir une demande de fusion (pull request)

1. Une seule chose par demande, avec une phrase qui dit pourquoi.
2. Les essais passent, et un essai couvre ce qui change.
3. Les textes affichés existent dans les six langues (fr, en, de, es, it, nl) ; s'il manque une traduction,
   dites-le dans la demande.
4. Vocabulaire neutre : Vision sert un lieu (école, club, bureau…). Les rôles s'appellent **Décideur** et
   **Demandeur** ; voir `docs/i18n/vocabulaire.md`.
5. Aucune donnée réelle : ni nom, ni adresse, ni IP, ni jeton, ni clé. Exemples : `ha.exemple.org`,
   `192.0.2.10`, `person.demandeur_1`.
6. Jamais de clé de signature ni d'APK signé : la signature des versions publiées se fait hors du dépôt.
7. Les dépendances suivent Home Assistant : une exigence du `manifest.json` s'écrit avec un minimum
   (`pyqrcode>=1.2.1`), jamais épinglée.

## Failles de sécurité

Ne pas ouvrir d'issue publique : voir [SECURITY.md](SECURITY.md).

## Licence

En contribuant, vous acceptez que votre contribution soit publiée sous la [licence MIT](LICENSE) du projet.
