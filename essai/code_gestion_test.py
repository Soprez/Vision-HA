"""Faille 2.2-1 : le code qui libère un message bloquant sur un PC n'est plus choisi par le Demandeur.

Avant, ce code se posait dans l'onglet « Réglages » de la fenêtre Vision du PC, ouvert à tous, et son
empreinte vivait dans le profil Windows de l'utilisateur (%APPDATA%\\Vision\\veille.json) : un Demandeur
posait le sien, puis se libérait. Et Home Assistant croyait tout accusé « libere_parent ».

Ce qu'on vérifie :
- le code se règle dans les options de l'intégration (administrateurs), jamais par un service ;
- Home Assistant n'en garde que l'empreinte (PBKDF2, sel), jamais le code ;
- `POST /api/pc_parental/code` : appareil authentifié, message bloquant « parent » à lui, code juste ;
  essais faux limités (429), décideurs prévenus ; la levée part au relevé (`liberer`) ;
- un accusé `libere_parent` d'un PC sans levée par Home Assistant est refusé (409), de même qu'un
  `libere_auto` avant la limite ; Android garde son code de gestion local ;
- l'agent du PC : plus de code ni d'empreinte locale, plus de champ dans « Réglages », « Libérer » passe
  par Home Assistant, le fichier du code est illisible pour son auteur, un accusé refusé fait revenir le message.

Banc : le même que essai/securite_test.py (Python 3.14, pytest-homeassistant-custom-component==0.13.367).
    python -m pytest essai/code_gestion_test.py -q -o asyncio_mode=auto
Les essais de l'agent qui lancent PowerShell sont sautés hors de Windows.
"""
from __future__ import annotations

import json
import logging
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile
import time

import pytest

racine = pathlib.Path(__file__).resolve().parent.parent
AGENT = racine / "custom_components" / "pc_parental" / "client" / "ha-parental-agent.ps1"

try:  # Le banc Home Assistant (Linux) ; sans lui, seuls les essais de l'agent tournent.
    from homeassistant.core import HomeAssistant
    from homeassistant.data_entry_flow import FlowResultType
    from homeassistant.setup import async_setup_component
    from pytest_homeassistant_custom_component.common import MockConfigEntry
    BANC = True
except ImportError:  # pragma: no cover
    BANC = False

if BANC:
    source = racine / "custom_components" / "pc_parental"
    _banc = pathlib.Path(tempfile.mkdtemp(prefix="vision-banc-code-gestion-"))
    shutil.copytree(source, (_banc / "custom_components").joinpath(source.name),
                    ignore=shutil.ignore_patterns("__pycache__", "android", "*.apk"))
    (_banc / "custom_components" / "__init__.py").write_text("")
    sys.path.insert(0, str(_banc))
    import custom_components  # noqa: E402

    if str(_banc / "custom_components") not in list(custom_components.__path__):
        custom_components.__path__.append(str(_banc / "custom_components"))

banc = pytest.mark.skipif(not BANC, reason="banc Home Assistant absent")
DOMAIN = "pc_parental"
PC = ("p1", "PC-ALEX", "b" * 32)
TEL = ("t1", "TEL-ALEX", "c" * 32)
AUTRE = ("p2", "PC-SAM", "d" * 32)
CODE = "Lune-4821"


def _fiche(pc_id: str, nom: str, secret: str, **plus) -> dict:
    return {"id": pc_id, "name": nom, "host": nom, "secret": secret, "enabled": True, "rules": [],
            "bloques": [], "autorises": [], "override": None, "last_seen": 0, "agent": {},
            "person": "", "update_asked": False, "tags": [], "messages": [], "next_message_id": 1, **plus}


if BANC:
    @pytest.fixture(autouse=True)
    async def _integrations(hass, enable_custom_integrations):
        for composant in ("http", "api", "websocket_api", "config"):
            assert await async_setup_component(hass, composant, {})
        yield

    @pytest.fixture
    def expected_lingering_timers() -> bool:
        return True

    @pytest.fixture
    def expected_lingering_tasks() -> bool:
        return True


async def _monter(hass, hass_storage, code: str | None = CODE):
    from custom_components.pc_parental import code_gestion

    donnees = {"pcs": {
        PC[0]: _fiche(*PC, platform="windows"),
        AUTRE[0]: _fiche(*AUTRE, platform="windows"),
        TEL[0]: _fiche(*TEL, platform="android"),
    }, "enroll_key": "cle-essai-1234", "enroll_key_v": 2, "enroll_open": False}
    if code:
        donnees["code_gestion"] = code_gestion.fabriquer(code)
    hass_storage[DOMAIN] = {"version": 1, "minor_version": 1, "key": DOMAIN, "data": donnees}
    entree = MockConfigEntry(domain=DOMAIN, data={}, unique_id=DOMAIN, title="Vision")
    entree.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entree.entry_id)
    await hass.async_block_till_done()
    return entree


def _coord(hass):
    return next(iter(hass.data[DOMAIN].values()))


async def _relever(client, appareil):
    r = await client.post("/api/pc_parental/poll", json={"id": appareil[0], "secret": appareil[2], "version": "1.89.2"})
    assert r.status == 200
    return await r.json()


async def _rodage(hass, client):
    """Un premier relevé de chaque appareil : sur un banc neuf, il crée les étiquettes de Vision, ce qui la
    recharge (503 le temps du rechargement) ; on attend qu'elle réponde à tous."""
    for _ in range(10):
        statuts = []
        for a in (PC, AUTRE, TEL):
            r = await client.post("/api/pc_parental/poll", json={"id": a[0], "secret": a[2], "version": "1.89.2"})
            statuts.append(r.status)
        await hass.async_block_till_done()
        if statuts == [200, 200, 200]:
            return
    raise AssertionError(statuts)


async def _bloquant(hass, client, appareil=PC, limite=30) -> int:
    await _rodage(hass, client)
    rep = await hass.services.async_call(DOMAIN, "envoyer", {"pc": appareil[1], "message": "Viens dîner",
                                                             "bloquant": "parent", "limite": limite},
                                         blocking=True, return_response=True)
    await hass.async_block_till_done()
    await _relever(client, appareil)
    return rep["messages"][0]["id"]


async def _code(client, mid, code, appareil=PC):
    r = await client.post("/api/pc_parental/code", json={"id": appareil[0], "secret": appareil[2], "message": mid, "code": code})
    return r.status, await r.json()


async def _accuse(client, mid, etat, appareil=PC, detail=""):
    r = await client.post("/api/pc_parental/message",
                          json={"id": appareil[0], "secret": appareil[2], "message": mid, "etat": etat, "detail": detail})
    return r.status


# ----------------------------------------------------------------------------- L'empreinte


@banc
def test_empreinte_jamais_le_code():
    from custom_components.pc_parental import code_gestion as cg

    fiche = cg.fabriquer(CODE)
    assert CODE not in json.dumps(fiche) and set(fiche) == {"sel", "empreinte", "iterations", "maj"}
    assert fiche["iterations"] >= 200_000
    assert cg.correspond(fiche, CODE) and cg.correspond(fiche, f"  {CODE} ")
    assert not cg.correspond(fiche, CODE.lower()) and not cg.correspond(fiche, "") and not cg.correspond({}, CODE)
    # Deux poses du même code : deux sels, deux empreintes.
    assert cg.fabriquer(CODE)["empreinte"] != fiche["empreinte"]
    for court in ("", "123", "12345", "x" * 33):
        with pytest.raises(ValueError):
            cg.fabriquer(court)


@banc
async def test_stocke_en_empreinte_seulement(hass, hass_storage):
    await _monter(hass, hass_storage, code=None)
    coord = _coord(hass)
    from custom_components.pc_parental import code_gestion as cg

    cg.poser(coord.store, CODE)
    await coord.store.async_save()
    brut = json.dumps(hass_storage[DOMAIN]["data"])
    assert CODE not in brut and hass_storage[DOMAIN]["data"]["code_gestion"]["empreinte"]
    # Jamais un service : les données d'un appel partent sur le bus (call_service), donc dans l'historique.
    assert not [s for s in hass.services.async_services().get(DOMAIN, {}) if "code" in s]


# ----------------------------------------------------------------------------- La vérification


@banc
async def test_code_juste_libere_par_home_assistant(hass, hass_storage, hass_client_no_auth, caplog):
    await _monter(hass, hass_storage)
    client = await hass_client_no_auth()
    mid = await _bloquant(hass, client)
    with caplog.at_level(logging.WARNING):
        statut, corps = await _code(client, mid, CODE)
    assert statut == 200 and corps == {"ok": True, "liberer": [mid]}
    assert CODE not in caplog.text
    suivi = _coord(hass).store.pcs[PC[0]]["messages_suivi"][str(mid)]
    assert suivi["libere_ha"] == "code_gestion"
    assert (await _relever(client, PC))["liberer"] == [mid]
    # L'accusé de la fenêtre, après la levée par Home Assistant, est cru.
    assert await _accuse(client, mid, "libere_parent", detail="ha") == 200
    assert suivi["etat"] == "libere_parent"


@banc
async def test_essais_faux_limites(hass, hass_storage, hass_client_no_auth):
    from custom_components.pc_parental import code_gestion as cg

    await _monter(hass, hass_storage)
    client = await hass_client_no_auth()
    mid = await _bloquant(hass, client)
    for i in range(cg.ESSAIS_FAUX_MAX):
        statut, corps = await _code(client, mid, f"faux-{i}")
        assert statut == 403 and corps["essais_restants"] == cg.ESSAIS_FAUX_MAX - i - 1
    # Bloqué, même avec le bon code ; les décideurs sont prévenus.
    statut, corps = await _code(client, mid, CODE)
    assert statut == 429 and corps["error"] == "trop" and corps["attente"] > 0
    from homeassistant.components import persistent_notification as pn

    assert f"{DOMAIN}_code_gestion_{PC[0]}" in pn._async_get_or_create_notifications(hass)
    assert "liberer" not in await _relever(client, PC)
    # Le compteur est propre à chaque appareil.
    mid2 = await _bloquant(hass, client, AUTRE)
    assert (await _code(client, mid2, CODE, AUTRE))[0] == 200


@banc
async def test_refus_de_la_vue(hass, hass_storage, hass_client_no_auth):
    await _monter(hass, hass_storage)
    client = await hass_client_no_auth()
    mid = await _bloquant(hass, client)
    # Secret faux, message d'un autre appareil, message qui n'est pas un bloquant « parent », corps illisible.
    r = await client.post("/api/pc_parental/code", json={"id": PC[0], "secret": "z" * 32, "message": mid, "code": CODE})
    assert r.status == 401
    assert (await _code(client, mid, CODE, AUTRE))[0] == 404
    simple = await hass.services.async_call(DOMAIN, "envoyer", {"pc": PC[1], "message": "Simple"},
                                            blocking=True, return_response=True)
    assert (await _code(client, simple["messages"][0]["id"], CODE))[0] == 404
    assert (await client.post("/api/pc_parental/code", data="pas du json")).status == 400
    assert (await _code(client, "x", CODE))[0] == 400


@banc
async def test_sans_code_pose(hass, hass_storage, hass_client_no_auth):
    await _monter(hass, hass_storage, code=None)
    client = await hass_client_no_auth()
    mid = await _bloquant(hass, client)
    statut, corps = await _code(client, mid, "1234")
    assert statut == 409 and corps["error"] == "aucun_code"


# ----------------------------------------------------------------------------- Les accusés


@banc
async def test_accuse_libere_force_refuse_sur_pc(hass, hass_storage, hass_client_no_auth, caplog):
    await _monter(hass, hass_storage)
    client = await hass_client_no_auth()
    mid = await _bloquant(hass, client)
    assert await _accuse(client, mid, "affiche") == 200
    suivi = _coord(hass).store.pcs[PC[0]]["messages_suivi"][str(mid)]
    # Le Demandeur dépose son propre accusé (accuse-*.json) : refusé, rien n'est noté comme levé.
    with caplog.at_level(logging.WARNING):
        assert await _accuse(client, mid, "libere_parent", detail="code_parent") == 409
        assert await _accuse(client, mid, "lu") == 409
        assert await _accuse(client, mid, "libere_auto") == 409
        assert await _accuse(client, mid, "expire") == 409
        assert await _accuse(client, mid, "erreur", detail="media") == 409
    assert suivi["etat"] == "affiche" and "refusé" in caplog.text
    # La limite vraiment passée depuis le premier affichage : la levée seule est crue.
    suivi["affiche_a"] = time.time() - 31 * 60
    assert await _accuse(client, mid, "libere_auto") == 200
    # Un second « affiche » ne repousse pas le premier affichage.
    premier = suivi["affiche_a"]
    assert await _accuse(client, mid, "affiche") == 200 and suivi["affiche_a"] == premier


@banc
async def test_libere_auto_d_un_message_d_avant_le_suivi(hass, hass_storage, hass_client_no_auth):
    """Un message envoyé avant ce correctif n'a ni `limite` ni `affiche_a` : l'envoi et 30 min font foi."""
    await _monter(hass, hass_storage)
    client = await hass_client_no_auth()
    mid = await _bloquant(hass, client)
    suivi = _coord(hass).store.pcs[PC[0]]["messages_suivi"][str(mid)]
    suivi.pop("limite"); suivi.pop("affiche_a", None)
    assert await _accuse(client, mid, "libere_auto") == 409
    suivi["ts"] = time.time() - 31 * 60
    assert await _accuse(client, mid, "libere_auto") == 200


@banc
async def test_verrou_de_la_maison(hass, hass_storage, hass_client_no_auth):
    """Plusieurs PC ne multiplient pas les essais : au-delà du plafond de la maison, le code est verrouillé
    (même juste) jusqu'à ce qu'un administrateur le change ; le compteur est gardé dans le magasin."""
    from custom_components.pc_parental import code_gestion as cg

    await _monter(hass, hass_storage)
    client = await hass_client_no_auth()
    mids = {a: await _bloquant(hass, client, a) for a in (PC, AUTRE, TEL)}
    statuts = []
    for i in range(cg.ECHECS_MAISON_MAX):
        a = (PC, AUTRE, TEL)[i // cg.ESSAIS_FAUX_MAX]
        statuts.append((await _code(client, mids[a], f"faux-{i:04d}", a))[0])
    assert statuts[:-1] == [403] * (cg.ECHECS_MAISON_MAX - 1) and statuts[-1] == 423
    assert len(hass_storage[DOMAIN]["data"]["code_gestion"]["echecs"]) == cg.ECHECS_MAISON_MAX
    statut, corps = await _code(client, mids[PC], CODE)
    assert statut == 423 and corps["error"] == "verrouille"
    assert "verrouillé" in cg.etat(_coord(hass).store)
    cg.poser(_coord(hass).store, CODE)
    hass.data.pop(cg.DONNEES, None)
    assert (await _code(client, mids[PC], CODE))[0] == 200


@banc
async def test_liberer_depuis_home_assistant_reste_valable(hass, hass_storage, hass_client_no_auth):
    await _monter(hass, hass_storage)
    client = await hass_client_no_auth()
    mid = await _bloquant(hass, client)
    await hass.services.async_call(DOMAIN, "liberer_message", {"pc": PC[1]}, blocking=True)
    assert (await _relever(client, PC))["liberer"] == ["*"]
    assert await _accuse(client, mid, "libere_parent", detail="ha") == 200


@banc
async def test_android_garde_son_code_local(hass, hass_storage, hass_client_no_auth):
    await _monter(hass, hass_storage)
    client = await hass_client_no_auth()
    mid = await _bloquant(hass, client, TEL)
    assert await _accuse(client, mid, "libere_parent", TEL, detail="code_parent") == 200


# ----------------------------------------------------------------------------- Les options


@banc
async def test_options_reglent_le_code(hass, hass_storage):
    entree = await _monter(hass, hass_storage, code=None)
    from custom_components.pc_parental import code_gestion as cg

    async def _etape():
        flux = await hass.config_entries.options.async_init(entree.entry_id)
        flux = await hass.config_entries.options.async_configure(flux["flow_id"], {"next_step_id": "autres"})
        flux = await hass.config_entries.options.async_configure(flux["flow_id"], {"next_step_id": "securite"})
        assert "code_gestion" in flux["menu_options"]
        return await hass.config_entries.options.async_configure(flux["flow_id"], {"next_step_id": "code_gestion"})

    flux = await _etape()
    assert flux["type"] == FlowResultType.FORM and flux["step_id"] == "code_gestion"
    r = await hass.config_entries.options.async_configure(flux["flow_id"], {"action": "changer", "code": "12345", "confirmation": "12345"})
    assert r["errors"] == {"code": "code_court"}
    r = await hass.config_entries.options.async_configure(flux["flow_id"], {"action": "changer", "code": CODE, "confirmation": "autre"})
    assert r["errors"] == {"confirmation": "code_different"}
    r = await hass.config_entries.options.async_configure(flux["flow_id"], {"action": "changer", "code": CODE, "confirmation": CODE})
    assert r["type"] == FlowResultType.CREATE_ENTRY
    store = _coord(hass).store
    assert cg.correspond(store.code_gestion, CODE) and CODE not in json.dumps(hass_storage[DOMAIN]["data"])
    flux = await _etape()
    r = await hass.config_entries.options.async_configure(flux["flow_id"], {"action": "garder"})
    assert cg.correspond(_coord(hass).store.code_gestion, CODE)
    flux = await _etape()
    await hass.config_entries.options.async_configure(flux["flow_id"], {"action": "retirer"})
    assert not cg.est_pose(_coord(hass).store)


@banc
async def test_options_reservees_aux_administrateurs(hass, hass_storage, hass_client, hass_admin_user):
    """Les options s'ouvrent par l'API config_entries, réservée par Home Assistant aux administrateurs."""
    entree = await _monter(hass, hass_storage, code=None)
    hass_admin_user.groups = []
    client = await hass_client()
    r = await client.post("/api/config/config_entries/options/flow", json={"handler": entree.entry_id})
    assert r.status == 401


# ----------------------------------------------------------------------------- L'agent du PC


def _agent() -> str:
    return AGENT.read_text(encoding="utf-8-sig")


def _source(nom: str) -> str:
    m = re.search(r"\$script:" + nom + r" = @'\r?\n([\s\S]*?)\r?\n'@", _agent())
    assert m, nom
    return m.group(1)


def test_agent_plus_de_code_local():
    tray = _source("TraySource")
    for absent in ("CodeJuste", "CodePoser", "CodeEnregistrer", "CodeEmpreinte", "Rfc2898DeriveBytes",
                   "Enregistrer le code", "Changer le code", "Retirer le code", "codeHash = [string]$r"):
        assert absent not in tray, absent
    # Le verrou de la fenêtre : Windows Hello seulement ; aucune case de code dans « Réglages ».
    # i18n : le libellé est dans le dictionnaire de l'agent (six langues).
    assert "(Coche (T 'reglages.hello_demander')" in tray
    fr = json.loads((racine / "custom_components" / "pc_parental" / "client" / "langues" / "fr.json").read_text(encoding="utf-8"))
    assert fr["reglages.hello_demander"] == "Demander Windows Hello pour ouvrir Vision"
    assert "PasswordBox" not in tray[tray.index("(Section (T 'reglages.acces'))"):tray.index("if ($Depart) { $script:Page = $Depart; Montrer }")]
    # Une ancienne empreinte du profil est effacée.
    assert "$script:SvPurger = $true" in tray


def test_agent_liberer_passe_par_home_assistant():
    agent = _agent()
    tray = _source("TraySource")
    bloc = tray[tray.index("if ($bloquant -eq 'parent') {"):tray.index("} elseif ($bloquant -eq 'lu') {")]
    assert "MsgCodeEnvoyer" in bloc and "MsgLever" not in bloc and "code_parent" not in bloc
    # Côté SYSTEM : le fichier part à /api/pc_parental/code et la réponse revient à la fenêtre.
    assert "Invoke-Ha -Chemin '/api/pc_parental/code'" in agent
    assert agent.count("Send-Codes -Config") == 2
    # Le code n'est jamais journalisé.
    send = agent[agent.index("function Send-Codes {"):agent.index("function Send-Accuses {")]
    assert not re.search(r"Write-Journal[^\n]*\$(corps|a)\.code", send)
    # Un accusé refusé (409) ne fait plus sortir le message de la file : il revient.
    accuses = agent[agent.index("function Send-Accuses {"):agent.index("function Send-Actions {")]
    assert "$refuse = $true" in accuses and "'relance'" in accuses


def test_agent_accuses_et_medias_surs():
    agent = _agent()
    accuses = agent[agent.index("function Send-Accuses {"):agent.index("function Send-Actions {")]
    # L'accusé doit nommer un message de la file (uid ET numéro) ; tout refus le laisse dans la file.
    assert "Accusé sans message correspondant" in accuses and "$refuse = $true" in accuses
    assert "-SansLibere" in accuses
    # Un média ne s'efface que dans son dossier, à son nom, sans joker.
    assert "Remove-Item $fichier" not in accuses and "GetFullPath($fichier)" in accuses
    assert "-LiteralPath $plein" in accuses
    # Le dossier de la sonde : Utilisateurs ne peut pas le supprimer (ni le remplacer par une jonction).
    grant = agent[agent.index("function Grant-DossierSonde {"):agent.index("function Get-Sondes {")]
    assert "'ReadAndExecute, Write', 'None', 'None'" in grant and "'InheritOnly'" in grant
    assert "Test-PasDeJonction" in grant


def _fonction_ps(texte: str, nom: str) -> str:
    debut = texte.index(f"function {nom} {{")
    niveau, i = 0, texte.index("{", debut)
    while True:
        if texte[i] == "{":
            niveau += 1
        elif texte[i] == "}":
            niveau -= 1
            if niveau == 0:
                return texte[debut:i + 1]
        i += 1


@pytest.mark.skipif(not shutil.which("powershell"), reason="Windows PowerShell absent")
def test_agent_dossier_de_la_sonde_sans_jonction(tmp_path):
    agent = _agent()
    script = (
        "function Write-Journal { param($m, $n = 'INFO') }\n"
        + _fonction_ps(agent, "Test-PasDeJonction") + "\n" + _fonction_ps(agent, "Grant-DossierSonde") + "\n"
        + f"""
$script:SondeDir = '{tmp_path / "sonde"}'
$cible = '{tmp_path / "cible"}'
New-Item -ItemType Directory $cible | Out-Null; Set-Content (Join-Path $cible 'garde.txt') 'x'
'cree=' + (Grant-DossierSonde)
$u = New-Object Security.Principal.SecurityIdentifier('S-1-5-32-545')
'aces=' + @((Get-Acl $script:SondeDir).Access | Where-Object {{ $_.IdentityReference.Translate([Security.Principal.SecurityIdentifier]) -eq $u }}).Count
'suppression=' + [bool](@((Get-Acl $script:SondeDir).Access | Where-Object {{ $_.IdentityReference.Translate([Security.Principal.SecurityIdentifier]) -eq $u -and $_.PropagationFlags -ne 'InheritOnly' -and ($_.FileSystemRights -band [Security.AccessControl.FileSystemRights]::Delete) }}).Count)
cmd /c mklink /J "$(Join-Path $script:SondeDir 'messages')" "$cible" | Out-Null
'medias=' + (Grant-DossierSonde) + ',' + (Test-Path (Join-Path $script:SondeDir 'messages'))
cmd /c rmdir /s /q "$script:SondeDir" | Out-Null
cmd /c mklink /J "$script:SondeDir" "$cible" | Out-Null
'sonde=' + (Grant-DossierSonde) + ',' + (-not ((Get-Item $script:SondeDir -Force).Attributes -band [IO.FileAttributes]::ReparsePoint))
'cible=' + (Test-Path (Join-Path $cible 'garde.txt'))
""")
    r = subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command", script],
                       capture_output=True, text=True, timeout=120)
    sortie = dict(l.strip().split("=", 1) for l in r.stdout.splitlines() if "=" in l)
    assert sortie == {"cree": "True", "aces": "2", "suppression": "False", "medias": "True,False",
                      "sonde": "True,True", "cible": "True"}, r.stdout + r.stderr


@pytest.mark.skipif(not shutil.which("powershell"), reason="Windows PowerShell absent")
def test_agent_fichier_du_code_illisible_pour_son_auteur(tmp_path):
    tray = _source("TraySource")
    fonction = tray[tray.index("function MsgCodeFichier("):tray.index("function MsgCodeDire(")]
    script = fonction + f"""
$script:Dossier = '{tmp_path}'
MsgCodeFichier '{{"message":1,"code":"1234"}}'
$f = Get-ChildItem $script:Dossier -Filter 'code-*.json' | Select-Object -First 1
'cree=' + [bool]$f
try {{ [IO.File]::ReadAllText($f.FullName) | Out-Null; 'lecture=permise' }} catch {{ 'lecture=refusee' }}
try {{ $s = [IO.File]::GetAccessControl($f.FullName); $s.SetAccessRuleProtection($false, $true); [IO.File]::SetAccessControl($f.FullName, $s); 'acl=permise' }} catch {{ 'acl=refusee' }}
'admin=' + ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
"""
    r = subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command", script],
                       capture_output=True, text=True, timeout=120)
    sortie = dict(l.strip().split("=") for l in r.stdout.splitlines() if "=" in l)
    if sortie.get("admin") == "True":
        pytest.skip("console élevée : les Administrateurs lisent le fichier, c'est voulu")
    assert sortie == {"cree": "True", "lecture": "refusee", "acl": "refusee", "admin": "False"}, r.stdout + r.stderr


@pytest.mark.skipif(not shutil.which("powershell"), reason="Windows PowerShell absent")
def test_agent_purge_l_ancienne_empreinte(tmp_path):
    tray = _source("TraySource")
    debut = tray.index("$script:SvFichier = ")
    fin = tray.index("# 1.86.0 (point 14.4)")
    vieux = tmp_path / "veille.json"
    vieux.write_text(json.dumps({"actif": True, "verrou": True, "codeSel": "c2Vs", "codeHash": "aGFzaA=="}), encoding="utf-8")
    script = (
        "function Prop($o, $n, $d = $null) { if ($null -ne $o -and $o.PSObject.Properties[$n]) { return $o.$n }; return $d }\n"
        "$script:Themes = [ordered]@{ 'Vision' = @{} }\n"
        + tray[debut:fin].replace("Join-Path $env:APPDATA 'Vision\\veille.json'", f"'{vieux}'")
        + "\n$r = SvReglages\n'cles=' + (($r.Keys | Sort-Object) -join ',')\n"
    )
    r = subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command", script],
                       capture_output=True, text=True, timeout=120)
    assert "codeHash" not in r.stdout and "cles=" in r.stdout, r.stdout + r.stderr
    relu = json.loads(vieux.read_text(encoding="utf-8-sig"))
    assert "codeHash" not in relu and "codeSel" not in relu and relu["verrou"] is True


async def test_mise_a_jour_proposee_au_pc(hass, hass_storage, hass_client_no_auth):
    """2.3.4 : un agent plus ancien que celui hébergé reçoit l'ordre de mise à jour, un agent à jour ne le reçoit plus."""
    from custom_components.pc_parental.const import CLIENT_VERSION
    await _monter(hass, hass_storage)
    client = await hass_client_no_auth()
    await _rodage(hass, client)
    _coord(hass).store.auto_update = True
    rep = await _relever(client, PC)
    assert rep.get("update", {}).get("version") == CLIENT_VERSION, rep.get("update")
    assert rep["update"]["url"] == "/api/pc_parental/client/agent"
    r = await client.post("/api/pc_parental/poll", json={"id": PC[0], "secret": PC[2], "version": CLIENT_VERSION})
    assert "update" not in (await r.json())


async def test_agent_servi_en_binaire_meme_empreinte(hass, hass_storage, hass_client_no_auth):
    """2.3.4 : l'agent part en application/octet-stream (Windows PowerShell 5.1 sous SYSTEM n'a pas à l'analyser),
    octet pour octet celui dont HA annonce l'empreinte ; l'installateur reste du texte."""
    import hashlib
    await _monter(hass, hass_storage)
    client = await hass_client_no_auth()
    r = await client.get("/api/pc_parental/client/agent")
    assert r.status == 200 and r.content_type == "application/octet-stream"
    corps = await r.read()
    v = await (await client.get("/api/pc_parental/client/version")).json()
    assert hashlib.sha256(corps).hexdigest() == v["sha256"]
    assert corps.startswith(b"\xef\xbb\xbf")
    r = await client.get("/api/pc_parental/client/install")
    assert r.status == 200 and r.content_type == "text/plain"


def _doublons_casse(o, chemin=""):
    """Clés qui ne diffèrent que par la casse : ConvertFrom-Json de Windows PowerShell 5.1 refuse tout le document."""
    sortie = []
    if isinstance(o, dict):
        vues: dict[str, str] = {}
        for k in o:
            if k.lower() in vues:
                sortie.append(f"{chemin}/{vues[k.lower()]}≈{k}")
            vues[k.lower()] = k
        for k, v in o.items():
            sortie += _doublons_casse(v, f"{chemin}/{k}")
    elif isinstance(o, list):
        for v in o:
            sortie += _doublons_casse(v, chemin + "[]")
    return sortie


async def test_reponses_agent_pc_lisibles_par_powershell_51(hass, hass_storage, hass_client_no_auth):
    """2.3.5 : en 2.3.1, « vocabulaire » portait « decideur » et « Decideur » : chaque relevé des agents PC échouait
    (clés en double pour ConvertFrom-Json), l'agent restait sur son dernier état et ne voyait jamais la mise à jour."""
    await _monter(hass, hass_storage)
    client = await hass_client_no_auth()
    await _rodage(hass, client)
    rep = await _relever(client, PC)
    assert "vocabulaire" in rep
    assert _doublons_casse(rep) == []
    for chemin, corps in (("/api/pc_parental/parent", {}), ("/api/pc_parental/veille", {"ecran": "tele"})):
        r = await client.post(chemin, json={"id": PC[0], "secret": PC[2], **corps})
        if r.status == 200:
            assert _doublons_casse(await r.json()) == [], chemin
