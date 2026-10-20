# Vision

**English** · [Français](README.fr.md) · Deutsch · Español · Italiano · Nederlands

**Website and documentation: https://soprez.org/vision**

> Vision itself speaks six languages: English, French, German, Spanish, Italian and Dutch (Home Assistant
> integration, dashboard cards, Windows agent; each device follows its own language). This README exists in
> English and French; the German, Spanish, Italian and Dutch versions (`README.de.md`, `README.es.md`,
> `README.it.md`, `README.nl.md`) are not written yet — contributions are welcome. Release notes:
> [docs/notes-de-version](docs/notes-de-version/).

Screen-time rules for Home Assistant: Vision looks after the Windows computers, the Android phones and the Android
TVs of a place — a school, a club, an office, any shared space — with time slots, categories of apps and sites,
and time requests that the Deciders approve or decline.

Everything stays with you: Home Assistant decides, the devices apply. No outside service is needed.

## Requirements 

- Home Assistant **2026.9** or newer.
- [HACS](https://hacs.xyz) for installation and updates.
- Devices that Home Assistant can reach: PCs running Windows 10/11, Android phones or TVs.

## Install

Vision has three parts: the Home Assistant **integration** (installed with HACS), the **Android app** and the
**Windows agent**. The last two are served by the integration itself: nothing else to download.

### 1. The integration (HACS, custom repository)

1. In HACS: **⋮ → Custom repositories**, add `https://github.com/Soprez/Vision-HA`,
   category **Integration**.
2. Search for **Vision** in HACS, install it, then restart Home Assistant.
3. **Settings → Devices & services → Add integration → Vision.**

HACS installs the `vision.zip` file of the latest release (*Releases* tab): it contains the integration **and**
the signed APK of the Android app.

When installed, Vision **locks no one in**: no device, no time slot, no blocked category. Only the list of
dangerous domains (malware, phishing) is active; it can be turned off in the settings.

The **place name** (“School”, “Club”, “Office”…) is set in **Vision → Configure → General settings**: it titles the
first tab of the dashboard and the Vision window on the PCs.

### 2. The Android app (phones, tablets and TVs)

1. In the Vision dashboard, the **Install Vision on a device** section gives the APK link (served by your Home
   Assistant), its QR code, and a short address to type on a TV:
   `http://<ha-address>:8123/vision` (for example with the *Downloader* app).
2. On the device, open that link, download the APK and allow installs from the browser (“Unknown sources”).
3. Open Vision and enroll the device: **Vision → Configure → Add a device** in Home Assistant shows a pairing QR
   code to scan from the app (valid for a few minutes, approved by an administrator).

Updates then arrive by themselves with the integration's. The APK is also attached to each release
(*Releases* tab); check its signature (fingerprint below).

### 3. The Windows agent (PCs)

1. In Home Assistant, **Vision → Configure → Add a device** (choose a PC) gives you a line to paste into PowerShell,
   with the address and a one-time pairing token already filled in.
2. Paste it on the PC; the installer asks for administrator permission, then does the rest.
3. The account of the supervised person must be a **standard** account: an administrator can stop the agent.

The agent, the installer and the uninstaller speak the language of Windows.

An enrolled device shows up in Home Assistant **allowed and with no time slot**. Once all devices are set up,
**close enrollment** (switch “New device enrollment”): it is Vision's front door.

## Link devices to people

Vision reads Home Assistant's people (**Settings → People**): nothing to enter twice. A device belongs to a
person; their rules follow them on all their devices.

The role is given **to a device** (setting “Device role”, by an administrator): a device with the “Decider” role
can answer requests from its phone. The words Decider, Requester and the place name can be adapted to your place.

## Set the first rule

In the Vision dashboard, pick a device or a person, then add a time slot (for example “Night, 22:00 → 07:00,
every day”). Categories (“Games”, “Videos”…) are Home Assistant labels: tick the ones you use in
**Vision → Permissions → Labels**. Their names are shown in each person's language.

For a rule that depends on something other than the time (a school average, a chore), Vision provides two
actions, `pc_parental.fermer_categorie` and `pc_parental.ouvrir_categorie`, to call from an automation. A full
example is in [docs/regle-de-moyenne-par-automatisation.md](docs/regle-de-moyenne-par-automatisation.md)
(in French).

## Party mode and automations

During a Party, Vision fires two Home Assistant events: `vision_party_started` when it starts and
`vision_party_stopped` when it stops, with the screen, the speaker, the device and the person that triggered it
(`ecran`, `lecteur`, `appareil`, `personne`).
Hook your own automations onto them (lights, camera alerts, a celebration scene…): Vision itself touches none of
your entities.
Example trigger: `trigger: event` with `event_type: vision_party_started`.

## Android app signature

The app is signed with a single key; Android only installs an update that carries the same signature. SHA-256
fingerprint of the certificate:

```
A7:7C:D3:E3:A2:A9:1E:DA:5E:8E:D3:B4:58:D9:90:D0:47:37:CB:A7:A5:84:69:D3:96:8E:BC:3B:10:FE:C1:B7
```

## ☕ Buy me a coffee

Vision is free. If it helps you, you can buy me a coffee: https://buymeacoffee.com/soprez
It is optional: nothing gets unlocked, a “♥ Supporter” badge simply shows on your devices.

## Contributing

See [CONTRIBUTING.en.md](CONTRIBUTING.en.md) (in French: [CONTRIBUTING.md](CONTRIBUTING.md)). Security issues:
[SECURITY.md](SECURITY.md), never a public issue.

## License

Vision is free software, released under the **MIT license**: see [LICENSE](LICENSE).
© 2026 Soprez. This covers the integration, the Windows agent and the Android app, whose source code
is published.

Bundled third-party components, under their own licenses (all compatible with the MIT license): PyQRCode (BSD),
ZXing and AndroidX (Apache-2.0) in the Android app, and the Figtree and Outfit fonts (SIL Open Font License 1.1).
