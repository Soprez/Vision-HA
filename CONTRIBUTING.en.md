# Contributing to Vision

**English** · [Français](CONTRIBUTING.md)

Thanks for your interest! Vision has three parts, all in this repository:

| Part | Folder | Tests |
|---|---|---|
| Home Assistant integration | `custom_components/pc_parental/` | `pytest` in `essai/` |
| Android app | `android/src/` | `gradle testDebugUnitTest` (Gradle 8.9, JDK 17+) |
| Windows agent | `custom_components/pc_parental/client/` | `pytest` in `essai/`, `essai/desinstall_essai.ps1` |

Website and documentation: https://soprez.org/vision

Most code comments and test names are in French: questions and pull requests in English are welcome.

## Running the tests

Integration and agent (Linux or macOS, Python 3.14):

```sh
python -m venv .venv && . .venv/bin/activate
pip install -r essai/requirements.txt
cd essai && python -m pytest -q -p no:cacheprovider -o asyncio_mode=auto
```

On Windows, the easiest way is the Docker test bench: `essai/banc/lancer.sh` (see `essai/banc/Dockerfile`).
Always read the **last** line in full: “1 failed, 8 passed” is a failure.

Android app: `gradle -p android/src testDebugUnitTest`. Emulator tests (`androidTest`) are not run by continuous
integration.

Every pull request also goes through **hassfest** (Home Assistant's official validation) and the **HACS
validation**: see `.github/workflows/`.

## Before opening a pull request

1. One change per request, with a sentence explaining why.
2. Tests pass, and a test covers the change.
3. Displayed texts exist in the six languages (fr, en, de, es, it, nl); if a translation is missing, say so in
   the request.
4. Neutral vocabulary: Vision serves a place (school, club, office…). The roles are **Decider** and
   **Requester**; see `docs/i18n/vocabulaire.md`.
5. No real data: no names, addresses, IPs, tokens or keys. Examples: `ha.example.org`, `192.0.2.10`,
   `person.requester_1`.
6. Never a signing key or a signed APK: released versions are signed outside the repository.
7. Dependencies follow Home Assistant: a `manifest.json` requirement uses a minimum version
   (`pyqrcode>=1.2.1`), never a pin.

## Security issues

Do not open a public issue: see [SECURITY.md](SECURITY.md).

## License

By contributing, you agree that your contribution is published under the project's [MIT license](LICENSE).
