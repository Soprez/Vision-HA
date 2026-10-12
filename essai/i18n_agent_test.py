"""Agent PC en six langues (lot D de l'internationalisation).

- dictionnaires pc_parental/client/langues/<langue>.json : mêmes clés et mêmes champs {…} dans les 6 langues,
  pluriels complets (_one / _other), aucune clé appelée par un script qui manquerait ;
- vocabulaire neutre : aucun mot interdit (famille, maison, parent, enfant… dans les 6 langues) dans les textes ;
- les fonctions de langue sont les mêmes dans l'agent, l'installateur et le désinstalleur ;
- détecteur de français écrit en dur dans les trois scripts (hors journaux, commentaires, code) ;
- l'agent tel que Home Assistant le sert : dictionnaires glissés à la place du marqueur, empreinte de ce qui est
  servi, script valide pour le parseur PowerShell ;
- banc PowerShell (sans lancer l'agent) : les seules fonctions de langue, en allemand simulé, et la fenêtre
  (TraySource) qui reste un script valide une fois les langues glissées.

    python -m pytest essai/i18n_agent_test.py -q
"""
from __future__ import annotations

import hashlib
import json
import pathlib
import re
import shutil
import subprocess
import tempfile

import pytest

import deverrouillage_test  # noqa: F401  (banc : l'intégration copiée sous custom_components)

CLIENT = pathlib.Path(__file__).parent.parent / "custom_components" / "pc_parental" / "client"
LANGUES = ("fr", "en", "de", "es", "it", "nl")
SCRIPTS = ("ha-parental-agent.ps1", "install.ps1", "uninstall.ps1")
CHAMP = re.compile(r"\{[A-Za-z_]+\}")
# Vides permis : le format d'heure, vide = l'heure courte de la culture.
VIDES_PERMIS = {"format_heure"}
FONCTIONS = ("ConvertTo-Dico", "Initialize-Langues", "Set-Langue", "T", "Tn", "Format-Heure")


def _dicos() -> dict[str, dict[str, str]]:
    return {l: json.loads((CLIENT / "langues" / f"{l}.json").read_text(encoding="utf-8")) for l in LANGUES}


def _script(nom: str) -> str:
    return (CLIENT / nom).read_text(encoding="utf-8-sig")


def test_parite_des_cles_et_des_champs():
    d = _dicos()
    fr = d["fr"]
    assert len(fr) > 400
    for l in LANGUES:
        assert set(d[l]) == set(fr), (l, sorted(set(fr) ^ set(d[l]))[:10])
        for k, v in d[l].items():
            # La casse d'un champ du vocabulaire ({decideurs} / {Decideurs}) suit la phrase de chaque langue.
            assert sorted(c.lower() for c in CHAMP.findall(v)) == sorted(c.lower() for c in CHAMP.findall(fr[k])), (l, k, v)
            assert v.count("\n") == fr[k].count("\n"), (l, k)
            assert v.strip() or k in VIDES_PERMIS, (l, k)
    for k in fr:
        if k.endswith("_one"):
            assert k[:-4] + "_other" in fr, k
        if k.endswith("_other"):
            assert k[:-6] + "_one" in fr, k
    # Le vocabulaire par défaut de chaque langue : celui de pc_parental/vocabulaire.py.
    from custom_components.pc_parental import vocabulaire

    for l in LANGUES:
        for c in ("decideur", "decideurs", "demandeur", "demandeurs"):
            assert d[l]["vocab." + c] == vocabulaire.DEFAUTS[l][c], (l, c)


def test_cles_appelees_presentes():
    fr = _dicos()["fr"]
    for nom in SCRIPTS:
        texte = _script(nom)
        for m in re.finditer(r"\(Tn? '([a-z0-9_.]+)'", texte):
            cle = m.group(1)
            assert cle in fr or cle + "_one" in fr, (nom, cle)
    # Clés construites : thèmes (la météo de la veille des PC est partie avec elle, 1.91.0).
    agent = _script("ha-parental-agent.ps1")
    for horloge in re.findall(r"Horloge = '([a-z0-9-]+)'", agent):
        assert "theme." + horloge.replace("-", "_") in fr, horloge


MOTS_INTERDITS = re.compile(
    r"famil|maison|\bparent|enfant|\bkids?\b|child|\bhome\b|house|zuhause|\bhaus\b|eltern|\bkind(er)?\b|\bcasa\b|"
    r"hogar|padres|\bhij[oa]s?\b|\bniñ|famiglia|genitori|\bfigli|bambin|gezin|\bthuis\b|\bhuis\b|ouders",
    re.I,
)


def _sans_noms_propres(texte: str) -> str:
    texte = re.sub(r"\s+", " ", texte)
    return texte.replace("Home Assistant", "").replace("HA Controle Parental", "").replace("HAParental", "")


def test_vocabulaire_neutre():
    for l, d in _dicos().items():
        for k, v in d.items():
            assert not MOTS_INTERDITS.search(_sans_noms_propres(v)), (l, k, v)
    for nom in ("LISEZ-MOI.txt", "README.txt", "LIESMICH.txt", "LEEME.txt", "LEGGIMI.txt", "LEESMIJ.txt"):
        texte = _sans_noms_propres((CLIENT / nom).read_text(encoding="utf-8-sig"))
        # « pc_parental » est le nom du domaine Home Assistant (identifiant), pas un mot affiché.
        texte = texte.replace("pc_parental", "")
        assert not MOTS_INTERDITS.search(texte), (nom, MOTS_INTERDITS.search(texte))


def _fonction(texte: str, nom: str) -> str:
    # Les fonctions de langue commencent et finissent en colonne 0.
    debut = texte.index(f"\nfunction {nom} {{\n") + 1
    return texte[debut:texte.index("\n}\n", debut) + 2]


def test_fonctions_identiques_et_marqueurs():
    agent = _script("ha-parental-agent.ps1")
    for nom in ("install.ps1", "uninstall.ps1"):
        autre = _script(nom)
        for f in FONCTIONS:
            assert _fonction(agent, f) == _fonction(autre, f), (nom, f)
    for nom in SCRIPTS:
        assert _script(nom).count("@@LANGUES@@") == 1, nom
    tray = agent[agent.index("$script:TraySource = @'"):]
    assert tray.index("# @@LANGUE@@") < tray.index("\n'@\n")
    assert "$script:TraySource = $script:TraySource.Replace('# @@LANGUE@@', (Get-LanguePreambule))" in agent
    # Le contrat de langue : le relevé porte « langue ».
    assert re.search(r"^\s+langue\s+= \$\(if \(\$script:LangueSession\)", agent, re.M)


# --- Détecteur de français en dur ---------------------------------------------------------------------------
# Une chaîne entre guillemets qui ressemble à du texte (accent, ou deux mots de lettres séparés par une espace),
# hors commentaires, journaux (Write-Journal, Write-Verbose, SvNoter) et lignes de code connues ci-dessous.
CHAINE = re.compile(r"'(?:[^'\n]|'')*'|\"(?:[^\"`\n]|`.)*\"")
TEXTE = re.compile(r"[A-Za-zÀ-ÿ]{2,}[ ’'][A-Za-zÀ-ÿ]{2,}|[À-ÿ]")
JOURNAUX = ("Write-Journal", "Write-Verbose", "throw ")
EXCEPTIONS = (
    # Marqueurs du fichier hosts et nom de la tâche planifiée : identifiants, cherchés tels quels.
    "Controle parental Home Assistant", "HA Controle Parental",
    # Formats, commandes, requêtes, registre, chemins, polices, XAML : du code, pas du texte.
    "yyyy-MM-dd", "powershell.exe", "-ExecutionPolicy", "SELECT * FROM", "WHERE TargetInstance", "URL:Vision",
    "URL Protocol", "<audio", "<image", "DllImport", "using System", "StaticResource", "TemplateBinding",
    "Segoe UI", "Georgia, Times", "Bahnschrift", "Consolas, Courier", "Arial", "HKCU:", "Registry::",
    "Microsoft\\Windows", "AppData\\Roaming", "Program Files", "program files", "Unregister-ScheduledTask",
    "Remove-Item -LiteralPath", "haparental|ha parental", "microsoft windows",
    "advanced micro devices", "killer networking", "rivet networks", "easy anti-cheat",
    # Valeurs envoyées à Home Assistant (codes), jamais affichées telles quelles par l'agent.
    "'liste noire'", "'filtre DNS'", "'Auto (différé)'",
    # Noms des thèmes : clés enregistrées dans veille.json ; l'affichage passe par NomTheme (theme.*).
    "'Bleu nuit'", "'Rose poudré'", "'Écrin'", "'Rétro 85'", "'Féerie'", "'Étincelle'",
    # Texte d'un Home Assistant plus ancien, reconnu pour la compatibilité (Test-RaisonSecurite).
    "'Bloqué pour ta sécurité*'",
    # Exemples de saisie de l'installateur (adresses) : remplacés par la purge de l'arbre public.
    "0123456789abcdef",
)


def _lignes_de_code(texte: str):
    """Les lignes hors commentaires (#, <# … #>) et hors appels de journal (sur une ou plusieurs lignes)."""
    bloc = False
    journal = 0
    for n, ligne in enumerate(texte.split("\n"), 1):
        s = ligne.strip()
        if bloc:
            if "#>" in s:
                bloc = False
            continue
        if s.startswith("<#"):
            bloc = "#>" not in s[2:]
            continue
        if s.startswith("#") or s.startswith("///") or not s:
            continue
        if journal > 0 or any(j in s for j in JOURNAUX):
            journal = max(0, journal + s.count("(") - s.count(")")) if journal or "Write-Journal (" in s else 0
            continue
        yield n, ligne.split(" # ")[0] if "'" not in ligne.split(" # ")[-1] else ligne


def test_pas_de_francais_en_dur():
    restes = []
    for nom in SCRIPTS:
        for n, ligne in _lignes_de_code(_script(nom)):
            if any(e in ligne for e in EXCEPTIONS):
                continue
            for m in CHAINE.finditer(ligne):
                corps = m.group(0)[1:-1]
                if TEXTE.search(corps) and not corps.startswith("$("):
                    restes.append(f"{nom}:{n}: {corps[:90]}")
    assert not restes, "\n".join(restes)


# --- L'agent tel que Home Assistant le sert ------------------------------------------------------------------
def test_agent_servi_porte_ses_dictionnaires():
    from custom_components.pc_parental import client_files

    client_files._charger("agent")
    servi = client_files.contenu("agent")
    assert "@@LANGUES@@" not in servi
    assert client_files.empreinte("agent") == hashlib.sha256(servi.encode("utf-8")).hexdigest()
    json_servi = servi.split("$script:LanguesJson = @'\n", 1)[1].split("\n'@\n", 1)[0]
    tout = json.loads(json_servi)
    assert list(tout)[0] == "fr" and set(tout) == set(LANGUES)
    assert json_servi.isascii()
    # Aucune ligne du JSON ne peut fermer le here-string PowerShell.
    assert not any(l.startswith("'@") for l in json_servi.split("\n"))
    # Même empreinte d'un démarrage à l'autre.
    avant = client_files.empreinte("agent")
    client_files._charger("agent")
    assert client_files.empreinte("agent") == avant
    for nom in ("install", "uninstall"):
        client_files._charger(nom)
        assert "@@LANGUES@@" not in client_files.contenu(nom)


BANC = r"""
param([string]$Client, [string]$Servi, [string]$Sortie)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$res = [ordered]@{}
foreach ($f in @($Servi, (Join-Path $Client 'install.ps1'), (Join-Path $Client 'uninstall.ps1'))) {
    $e = $null; $t = $null
    [void][System.Management.Automation.Language.Parser]::ParseFile($f, [ref]$t, [ref]$e)
    $res[(Split-Path $f -Leaf)] = @($e).Count
}
$tok = $null; $err = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile($Servi, [ref]$tok, [ref]$err)
$noms = @('ConvertTo-Dico', 'Initialize-Langues', 'Set-Langue', 'T', 'Tn', 'Format-Heure', 'Get-LanguePreambule')
foreach ($f in $ast.FindAll({ param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst] }, $false)) {
    if ($noms -contains $f.Name) { . ([ScriptBlock]::Create($f.Extent.Text)) }
}
$json = $null; $tray = $null
foreach ($a in $ast.FindAll({ param($n) $n -is [System.Management.Automation.Language.AssignmentStatementAst] }, $false)) {
    if ($a.Left.Extent.Text -eq '$script:LanguesJson') { $json = $a.Right.Expression.Value }
    if ($a.Left.Extent.Text -eq '$script:TraySource' -and $null -eq $tray) { $tray = $a.Right.Expression.Value }
}
$script:LangueFonctions = @('ConvertTo-Dico', 'Initialize-Langues', 'Set-Langue', 'T', 'Tn', 'Format-Heure')
$script:Lieu = ''
Initialize-Langues -Json $json
$res.langues = (@($script:Dicos.Keys | Sort-Object) -join ',')
Set-Langue -Langue 'de-DE' -Formats 'de-DE'
$res.de = @($script:Langue, (T 'etat.ouvert'), (Tn 'maison.appareils' 1), (Tn 'maison.appareils' 3),
    (T 'maison.demande_de' @{ prenom = 'Alex'; libelle = 'YouTube' }), (Format-Heure ([datetime]'2026-10-10T18:05:00')),
    (T 'fermes.titre_regle'), (T 'tray.supervision' @{ lieu = 'Schule' }), (T 'cle.inconnue')) -join '|'
$script:Lieu = 'Club'
$res.lieu = T 'tray.supervision'
$script:VocabPerso = @{ decideur = 'Trainer' }
$res.perso = T 'moi.ferme_ici_aide'
$script:VocabPerso = $null
Set-Langue -Langue 'fr-FR' -Formats 'fr-FR'
$res.fr = @((Format-Heure ([datetime]'2026-10-10T18:05:00')), (Tn 'maison.appareils' 1), (Tn 'maison.appareils' 2), (T 'maison.demandes')) -join '|'
Set-Langue -Langue 'pt-BR'
$res.pt = $script:Langue
$f = Join-Path $Sortie 'tray-essai.ps1'
[IO.File]::WriteAllText($f, $tray.Replace('# @@LANGUE@@', (Get-LanguePreambule)), (New-Object Text.UTF8Encoding($true)))
$e2 = $null; $t2 = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($f, [ref]$t2, [ref]$e2)
$res.tray = @($e2).Count
$p = Join-Path $Sortie 'preambule.ps1'
[IO.File]::WriteAllText($p, (Get-LanguePreambule) + "`nSet-Langue -Langue 'it-IT'`nWrite-Output ((T 'tray.onglet_reglages') + '|' + (Tn 'message.non_lus' 2))", (New-Object Text.UTF8Encoding($true)))
$res.preambule = [string](& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $p)
[IO.File]::WriteAllText((Join-Path $Sortie 'banc.json'), ($res | ConvertTo-Json), (New-Object Text.UTF8Encoding($false)))
"""


@pytest.mark.skipif(not shutil.which("powershell"), reason="Windows PowerShell absent")
def test_banc_powershell_sans_lancer_l_agent():
    from custom_components.pc_parental import client_files

    client_files._charger("agent")
    with tempfile.TemporaryDirectory(prefix="vision-banc-i18n-") as dossier:
        d = pathlib.Path(dossier)
        servi = d / "ha-parental-agent.ps1"
        servi.write_bytes(client_files.contenu("agent").encode("utf-8"))
        banc = d / "banc.ps1"
        banc.write_text(BANC, encoding="utf-8-sig")
        r = subprocess.run(
            ["powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", str(banc),
             "-Client", str(CLIENT), "-Servi", str(servi), "-Sortie", str(d)],
            capture_output=True, text=True, timeout=300,
        )
        assert (d / "banc.json").exists(), r.stdout + r.stderr
        res = json.loads((d / "banc.json").read_text(encoding="utf-8"))
    assert res["ha-parental-agent.ps1"] == 0 and res["install.ps1"] == 0 and res["uninstall.ps1"] == 0
    assert res["langues"] == "de,en,es,fr,it,nl"
    assert res["de"] == ("de|FREIGEGEBEN|1 Gerät|3 Geräte|Alex fragt nach YouTube|18:05|Entscheidung: Entscheider|"
                         "Aufsicht · Schule|cle.inconnue")
    assert res["lieu"] == "Aufsicht · Club"
    assert "ein Trainer antwortet" in res["perso"]
    # Le français, mot pour mot comme avant (18h05, « 1 appareil »).
    assert res["fr"] == "18h05|1 appareil|2 appareils|Demandes d'accès"
    assert res["pt"] == "en"
    assert res["tray"] == 0
    assert res["preambule"] == "Impostazioni|2 messaggi non letti"


RACINE = pathlib.Path(__file__).parent.parent


def test_readme_et_notes_de_version():
    """README public en anglais et en français (liens croisés), notes de version multilingues, vocabulaire neutre."""
    en = (RACINE / "README.md").read_text(encoding="utf-8")
    fr = (RACINE / "README.fr.md").read_text(encoding="utf-8")
    assert "[Français](README.fr.md)" in en and "[English](README.md)" in fr
    for texte in (en, fr):
        assert "vision_party_started" in texte and "vision_party_stopped" in texte
        propre = _sans_noms_propres(texte).replace("pc_parental", "")
        assert not MOTS_INTERDITS.search(propre), MOTS_INTERDITS.search(propre)
    notes = RACINE / "docs" / "notes-de-version"
    modele = json.loads((notes / "modele.json").read_text(encoding="utf-8"))
    assert set(modele["titre"]) == set(LANGUES)
    versions = [f for f in notes.glob("*.json") if f.name != "modele.json"]
    assert versions
    for f in versions:
        n = json.loads(f.read_text(encoding="utf-8"))
        assert n["version"] == f.stem and {"fr", "en"} <= set(n["titre"])
        for p in n["points"]:
            assert p["public"] in ("tous", "decideurs", "demandeurs", "administrateurs")
            assert set(p["appareils"]) <= {"telephone", "tele", "pc", "ha"}
            assert p["textes"]["fr"] and p["textes"]["en"]
            assert set(p["textes"]) <= set(LANGUES)
            for t in p["textes"].values():
                assert not MOTS_INTERDITS.search(_sans_noms_propres(t)), t
