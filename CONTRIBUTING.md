# Contribuer à Vision · Contributing

*English below.*

Merci de votre intérêt ! Vision se compose de trois parties, toutes dans ce dépôt :

| Partie | Dossier | Essais |
|---|---|---|
| Intégration Home Assistant | `custom_components/pc_parental/` | `pytest` dans `essai/` |
| Application Android | `android/src/` | `gradle testDebugUnitTest` (Gradle 8.9, JDK 17+) |
| Agent Windows | `custom_components/pc_parental/client/` | `pytest` dans `essai/`, `essai/desinstall_essai.ps1` |

**Avant d'ouvrir une demande de fusion (pull request)**

1. Une seule chose par demande, avec une phrase qui dit pourquoi.
2. Les essais passent, et un essai couvre ce qui change.
3. Les textes affichés existent dans les six langues (fr, en, de, es, it, nl) ; s'il manque une traduction,
   dites-le dans la demande.
4. Vocabulaire neutre : Vision sert un lieu (école, club, bureau…). Les rôles s'appellent **Décideur** et
   **Demandeur** ; voir `docs/i18n/vocabulaire.md`.
5. Aucune donnée réelle : ni nom, ni adresse, ni IP, ni jeton, ni clé. Exemples : `ha.exemple.org`,
   `192.0.2.10`, `person.demandeur_1`.
6. Jamais de clé de signature ni d'APK signé : la signature des versions publiées se fait hors du dépôt.

**Failles de sécurité** : ne pas ouvrir d'issue publique, voir [SECURITY.md](SECURITY.md).

---

Thanks for your interest! Vision has three parts, all in this repository: the Home Assistant integration
(`custom_components/pc_parental/`, tests with `pytest` in `essai/`), the Android app (`android/src/`,
`gradle testDebugUnitTest` with Gradle 8.9 and JDK 17+) and the Windows agent (`custom_components/pc_parental/client/`).

Before opening a pull request: one change per request with a sentence explaining why; tests pass and cover the
change; displayed texts exist in the six languages (fr, en, de, es, it, nl), or say which are missing; neutral
vocabulary (roles are **Decider** and **Requester**, see `docs/i18n/vocabulaire.md`); no real data (names,
addresses, IPs, tokens, keys); never a signing key or a signed APK.

Security issues: do not open a public issue, see [SECURITY.md](SECURITY.md).
