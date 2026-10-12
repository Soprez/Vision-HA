<#
.SYNOPSIS
    Installe l'agent Vision, la supervision des PC pilotée par Home Assistant.

.DESCRIPTION
    À lancer une seule fois par PC, en administrateur. Demande l'adresse de
    Home Assistant et la clé d'inscription, vérifie que la machine s'inscrit
    bien, puis installe une tâche planifiée qui démarre l'agent en compte
    SYSTEM à chaque démarrage.

    Le PC apparaît alors tout seul dans Home Assistant, ouvert et sans
    aucune plage : c'est à toi de lui donner ses horaires ensuite.

.EXAMPLE
    Clic droit sur install.ps1 → Exécuter avec PowerShell (en administrateur)

.EXAMPLE
    .\install.ps1 -Url https://ha.example.org:8123 -Cle <clé affichée dans Home Assistant>

.EXAMPLE
    .\install.ps1 -Jeton xxxx
    Jeton d'appairage à usage unique, donné par Vision → Configurer → Ajouter un appareil :
    le PC se relie sans clé d'inscription.
#>

[CmdletBinding()]
param(
    [string]$Url,
    [string]$Cle,
    [string]$Jeton,
    [switch]$Insecure,
    [int]$OfflineGraceMinutes = 120
)

$ErrorActionPreference = 'Stop'

# Adresse injectee par Home Assistant au moment du telechargement.
# Reste telle quelle si le script est copie a la main : on demande alors.
$UrlIntegree = '@@HA_URL@@'

$Dossier      = "$env:ProgramData\HAParental"
$ConfigPath   = Join-Path $Dossier 'config.json'
$AgentPath    = Join-Path $Dossier 'ha-parental-agent.ps1'
$NomTache     = 'HA Controle Parental'
$Source       = if ($PSScriptRoot) { Join-Path $PSScriptRoot 'ha-parental-agent.ps1' } else { $null }

# ------------------------------------------------------------ Langues
# Textes dans la langue de Windows (fr, en, de, es, it, nl ; repli anglais, puis français). Home Assistant glisse
# les dictionnaires à la place du marqueur en servant ce script ; posé à côté des fichiers, il lit langues\.
# Fonctions identiques à celles de l'agent (essai/i18n_agent_test.py le vérifie).
$LanguesJson = @'
@@LANGUES@@
'@

function ConvertTo-Dico {
    param($Objet)
    $h = @{}
    if ($null -ne $Objet) { foreach ($p in $Objet.PSObject.Properties) { $h[$p.Name] = [string]$p.Value } }
    return $h
}

function Initialize-Langues {
    <#
        Charge les dictionnaires : le JSON intégré ({"fr": {...}, "en": {...}}), sinon les fichiers
        <langue>.json du dossier donné, lus explicitement en UTF-8 (Windows PowerShell 5.1 les lirait en ANSI).
    #>
    param([string]$Json = '', [string]$Dossier = '')
    $script:Dicos = @{}
    $script:LanguesTout = ''
    $tout = $null
    if ($Json -and $Json.Trim() -and -not $Json.Trim().StartsWith('@@')) {
        try { $tout = $Json | ConvertFrom-Json } catch { $tout = $null }
    }
    if ($null -ne $tout) {
        foreach ($p in $tout.PSObject.Properties) { $script:Dicos[$p.Name] = (ConvertTo-Dico $p.Value) }
        $script:LanguesTout = $Json
    } elseif ($Dossier -and (Test-Path -LiteralPath $Dossier)) {
        $morceaux = @()
        foreach ($f in @(Get-ChildItem -LiteralPath $Dossier -Filter '*.json' -ErrorAction SilentlyContinue)) {
            try {
                $brut = [IO.File]::ReadAllText($f.FullName, [Text.Encoding]::UTF8)
                $code = $f.BaseName.ToLowerInvariant()
                $script:Dicos[$code] = (ConvertTo-Dico ($brut | ConvertFrom-Json))
                $morceaux += ('"' + $code + '":' + $brut)
            } catch { }
        }
        if ($morceaux.Count -gt 0) { $script:LanguesTout = '{' + ($morceaux -join ',') + '}' }
    }
    Set-Langue
}

function Set-Langue {
    <#
        La langue des textes : celle de Windows ((Get-UICulture).Name) ou celle donnée, réduite à deux lettres ;
        une langue sans dictionnaire passe à l'anglais. Repli de chaque clé : la langue, l'anglais, le français,
        puis la clé elle-même. Les heures suivent la culture donnée (Get-Culture), sinon celle du processus.
    #>
    param([string]$Langue = '', [string]$Formats = '')
    if (-not $Langue) { try { $Langue = [string](Get-UICulture).Name } catch { $Langue = '' } }
    $code = (([string]$Langue) -split '[-_]')[0].ToLowerInvariant()
    if (-not $script:Dicos.ContainsKey($code)) { $code = 'en' }
    $script:Langue = $code
    $script:LangueOrdre = @(@($code, 'en', 'fr') | Select-Object -Unique)
    $script:Culture = [Globalization.CultureInfo]::CurrentCulture
    if ($Formats) { try { $script:Culture = [Globalization.CultureInfo]::GetCultureInfo($Formats) } catch { } }
}

function T {
    <# Le texte d'une clé, champs {nom} remplacés : T 'cle' @{ nom = 'x' }. #>
    param([string]$Cle, [hashtable]$Champs = $null)
    $texte = $null
    foreach ($l in @($script:LangueOrdre)) {
        $d = $script:Dicos[$l]
        if ($null -ne $d -and $d.ContainsKey($Cle)) { $texte = [string]$d[$Cle]; break }
    }
    if ($null -eq $texte) { $texte = $Cle }
    if ($null -ne $Champs) { foreach ($k in @($Champs.Keys)) { $texte = $texte.Replace('{' + $k + '}', [string]$Champs[$k]) } }
    # Vocabulaire : {decideur}, {Decideurs}, {demandeur}, {lieu}… remplis ici (un champ passé l'emporte). Un seul
    # point de surcharge, $script:VocabPerso (champ -> mot), que Home Assistant pourra remplir ; sinon le mot par
    # défaut de la langue (vocab.*), et pour {lieu} le nom reçu du relevé ($script:Lieu).
    if ($texte.IndexOf('{') -ge 0) {
        $perso = Get-Variable -Scope Script -Name 'VocabPerso' -ValueOnly -ErrorAction SilentlyContinue
        foreach ($c in @('decideurs', 'decideur', 'demandeurs', 'demandeur', 'lieu')) {
            if ($texte.IndexOf('{' + $c + '}', [StringComparison]::OrdinalIgnoreCase) -lt 0) { continue }
            $mot = ''
            if ($perso -is [hashtable] -and $perso[$c]) { $mot = [string]$perso[$c] }
            elseif ($c -eq 'lieu') {
                $mot = [string](Get-Variable -Scope Script -Name 'Lieu' -ValueOnly -ErrorAction SilentlyContinue)
                if (-not $mot) { $mot = T 'lieu_defaut' }
            } else { $mot = T ('vocab.' + $c) }
            $maj = $(if ($mot) { $mot.Substring(0, 1).ToUpper() + $mot.Substring(1) } else { $mot })
            $texte = $texte.Replace('{' + $c + '}', $mot).Replace('{' + $c.Substring(0, 1).ToUpper() + $c.Substring(1) + '}', $maj)
        }
    }
    return $texte
}

function Tn {
    <# Pluriel : clé_one ou clé_other selon le nombre (0 et 1 au singulier en français), champ {n} compris. #>
    param([string]$Cle, $Nombre, [hashtable]$Champs = $null)
    $x = [double]$Nombre
    $un = $(if ($script:Langue -eq 'fr') { [Math]::Abs($x) -lt 2 } else { $x -eq 1 })
    $h = @{ n = $Nombre }
    if ($null -ne $Champs) { foreach ($k in @($Champs.Keys)) { $h[$k] = $Champs[$k] } }
    return (T ($Cle + $(if ($un) { '_one' } else { '_other' })) $h)
}

function Format-Heure {
    <#
        Une heure pour l'écran. Par défaut le format « format_heure » du dictionnaire (en français « HH\hmm » :
        18h05, comme avant) ; vide, l'heure courte de la culture. -Locale : toujours l'heure courte de la culture
        (18:05 en français, 6:05 PM en anglais américain). -Secondes : l'heure longue de la culture.
    #>
    param($Date, [switch]$Locale, [switch]$Secondes)
    if ($Date -is [DateTimeOffset]) { $Date = $Date.LocalDateTime }
    $f = 't'
    if ($Secondes) { $f = 'T' }
    elseif (-not $Locale) { $f = T 'format_heure'; if (-not $f -or $f -eq 'format_heure') { $f = 't' } }
    return ([datetime]$Date).ToString($f, $script:Culture)
}

Initialize-Langues -Json $LanguesJson -Dossier $(if ($PSScriptRoot) { Join-Path $PSScriptRoot 'langues' } else { '' })

# ------------------------------------------------------------ Élévation

function Test-Admin {
    $identite = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identite)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

if (-not (Test-Admin)) {
    Write-Host (T 'commun.relance_admin') -ForegroundColor Yellow
    $arguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$PSCommandPath`"")
    if ($Url) { $arguments += @('-Url', $Url) }
    if ($Cle) { $arguments += @('-Cle', $Cle) }
    if ($Jeton) { $arguments += @('-Jeton', $Jeton) }
    if ($Insecure) { $arguments += '-Insecure' }
    Start-Process -FilePath 'powershell.exe' -ArgumentList $arguments -Verb RunAs
    return
}

Write-Host ''
Write-Host ('  ' + (T 'install.titre')) -ForegroundColor Cyan
Write-Host '  ----------------------------------' -ForegroundColor Cyan
Write-Host ''

# --------------------------------------------------------------- Saisie

function Read-Obligatoire {
    param([string]$Question, [string]$Exemple)
    while ($true) {
        $reponse = Read-Host ("$Question`n  " + (T 'install.exemple' @{ exemple = $Exemple }) + "`n")
        if ($reponse -and $reponse.Trim()) { return $reponse.Trim() }
        Write-Host ('  ' + (T 'install.obligatoire')) -ForegroundColor Yellow
    }
}

if (-not $Url -and $UrlIntegree -notmatch '^@@') {
    $Url = $UrlIntegree
    Write-Host ('  ' + (T 'install.adresse_connue' @{ url = $Url })) -ForegroundColor DarkGray
}
if (-not $Url) {
    $Url = Read-Obligatoire (T 'install.question_adresse') 'http://homeassistant.local:8123'
}
if ($Url -notmatch '^https?://') { $Url = "http://$Url" }
$Url = $Url.TrimEnd('/')

if (-not $Cle -and -not $Jeton) {
    # Aucune clé par défaut : l'exemple ne montre que le format, une réponse vide est refusée.
    $Cle = Read-Obligatoire (T 'install.question_cle') (T 'install.exemple_cle')
}

# ---------------------------------------------------------- Inscription

Write-Host ''
Write-Host ('  ' + (T 'install.contact' @{ url = $Url })) -NoNewline
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

if ($Jeton) {
    # Appairage (Vision → Configurer → Ajouter un appareil) : un jeton à usage unique, pas de clé.
    $corps = @{ jeton = $Jeton; host = $env:COMPUTERNAME; nom = $env:COMPUTERNAME; user = $env:USERNAME } |
        ConvertTo-Json -Compress
    $adresse = "$Url/api/pc_parental/appairage/jeton"
} else {
    $corps = @{ key = $Cle; host = $env:COMPUTERNAME; user = $env:USERNAME } |
        ConvertTo-Json -Compress
    $adresse = "$Url/api/pc_parental/enroll"
}

$parametres = @{
    Uri         = $adresse
    Method      = 'Post'
    Body        = $corps
    ContentType = 'application/json; charset=utf-8'
    TimeoutSec  = 20
}
if ($Insecure) {
    [System.Net.ServicePointManager]::ServerCertificateValidationCallback = { $true }
}

try {
    # Décodage explicite : sans jeu de caractères déclaré, Windows
    # PowerShell 5.1 lirait la réponse en ISO-8859-1.
    $parametres['UseBasicParsing'] = $true
    $brut = Invoke-WebRequest @parametres
    $reponse = [Text.Encoding]::UTF8.GetString($brut.RawContentStream.ToArray()) |
        ConvertFrom-Json
} catch {
    Write-Host (' ' + (T 'install.echec')) -ForegroundColor Red
    Write-Host ''
    $code = $null
    if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
    switch ($code) {
        404 {
            if ($Jeton) {
                Write-Host ('  ' + (T 'install.lien_expire')) -ForegroundColor Red
                Write-Host ('  ' + (T 'install.lien_expire_aide'))
            } else {
                Write-Host ('  ' + (T 'install.integration_absente')) -ForegroundColor Red
            }
        }
        409 {
            Write-Host ('  ' + (T 'install.deja_inscrit')) -ForegroundColor Red
            Write-Host ('  ' + (T 'install.deja_inscrit_aide'))
        }
        403 {
            Write-Host ('  ' + (T 'install.cle_refusee')) -ForegroundColor Red
            Write-Host ('  ' + (T 'install.cle_refusee_aide'))
        }
        default {
            Write-Host ('  ' + (T 'install.injoignable')) -ForegroundColor Red
            Write-Host ('  ' + (T 'install.detail' @{ erreur = $_.Exception.Message }))
            Write-Host ('  ' + (T 'install.auto_signe'))
        }
    }
    Write-Host ''
    Read-Host ('  ' + (T 'commun.entree_pour_fermer'))
    exit 1
}

Write-Host (' ' + (T 'install.inscrit')) -ForegroundColor Green
Write-Host ('  ' + (T 'install.nom_du_pc' @{ nom = $reponse.name })) -ForegroundColor Green

# ------------------------------------------------------------ Installation

if (-not (Test-Path $Dossier)) {
    New-Item -ItemType Directory -Path $Dossier -Force | Out-Null
}

# La clé d'inscription ne reste pas sur le PC (1.61.0) : l'enfant qui a une session
# administrateur la lirait. Le PC est inscrit, son secret suffit désormais.
@{
    url                 = $Url
    id                  = $reponse.id
    secret              = $reponse.secret
    poll                = [int]$reponse.poll
    insecure            = [bool]$Insecure
    offlineGraceMinutes = $OfflineGraceMinutes
} | ConvertTo-Json -Depth 5 | Set-Content -Path $ConfigPath -Encoding UTF8

# L'agent est récupéré depuis Home Assistant : une seule version fait foi,
# et les PC se mettront ensuite à jour tout seuls. On se rabat sur le
# fichier posé à côté de l'installateur s'il est là.
$recupere = $false
try {
    $tele = @{
        Uri        = "$Url/api/pc_parental/client/agent"
        OutFile    = $AgentPath
        TimeoutSec = 60
    }
    if ($Insecure) { $tele['SkipCertificateCheck'] = $true }
    Invoke-WebRequest @tele | Out-Null
    $recupere = $true
    Write-Host ('  ' + (T 'install.agent_telecharge')) -ForegroundColor Green
} catch {
    if ($Source -and (Test-Path $Source)) {
        Copy-Item -Path $Source -Destination $AgentPath -Force
        # Copié à la main, l'agent n'a pas ses dictionnaires intégrés : ses fichiers de langue le suivent.
        $languesSource = Join-Path $PSScriptRoot 'langues'
        if (Test-Path $languesSource) {
            Copy-Item -Path $languesSource -Destination (Join-Path $Dossier 'langues') -Recurse -Force
        }
        $recupere = $true
        Write-Host ('  ' + (T 'install.agent_copie')) -ForegroundColor Yellow
    }
}
if (-not $recupere) {
    Write-Host ''
    Write-Host ('  ' + (T 'install.agent_introuvable')) -ForegroundColor Red
    Write-Host ('  ' + (T 'install.agent_introuvable_aide' @{ url = $Url }))
    Write-Host ''
    Read-Host ('  ' + (T 'commun.entree_pour_fermer'))
    exit 1
}

# Le secret ne doit être lisible que par SYSTEM et les administrateurs :
# sans cela l'enfant pourrait s'en servir pour se débloquer lui-même.
$acl = Get-Acl $Dossier
$acl.SetAccessRuleProtection($true, $false)
foreach ($regle in @($acl.Access)) { $acl.RemoveAccessRule($regle) | Out-Null }
foreach ($sid in @('S-1-5-18', 'S-1-5-32-544')) {
    $compte = (New-Object Security.Principal.SecurityIdentifier($sid))
    $acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule(
        $compte, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')))
}
Set-Acl -Path $Dossier -AclObject $acl

# ---------------------------------------------------------- Tâche planifiée

Unregister-ScheduledTask -TaskName $NomTache -Confirm:$false -ErrorAction SilentlyContinue

$action = New-ScheduledTaskAction -Execute 'powershell.exe' `
    -Argument "-NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$AgentPath`""

$declencheurs = @(
    (New-ScheduledTaskTrigger -AtStartup),
    (New-ScheduledTaskTrigger -AtLogOn)
)

$principal = New-ScheduledTaskPrincipal -UserId 'S-1-5-18' `
    -LogonType ServiceAccount -RunLevel Highest

$reglages = New-ScheduledTaskSettingsSet `
    -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -StartWhenAvailable -MultipleInstances IgnoreNew `
    -ExecutionTimeLimit ([TimeSpan]::Zero) `
    -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1)

Register-ScheduledTask -TaskName $NomTache -Action $action `
    -Trigger $declencheurs -Principal $principal -Settings $reglages `
    -Description (T 'install.description_tache') | Out-Null

Start-ScheduledTask -TaskName $NomTache

Write-Host ''
Write-Host ('  ' + (T 'install.terminee')) -ForegroundColor Green
Write-Host ''
foreach ($ligne in ((T 'install.fin_visible') -split "`n")) { Write-Host ('  ' + $ligne) }
Write-Host ''
Write-Host ('  ' + (T 'install.journal' @{ chemin = "$Dossier\agent.log" }))
foreach ($ligne in ((T 'install.fin_mises_a_jour') -split "`n")) { Write-Host ('  ' + $ligne) }
Write-Host ''
Write-Host ('  ' + (T 'install.pour_desinstaller' @{ url = "$Url/api/pc_parental/client/uninstall" }))
Write-Host ''
Read-Host ('  ' + (T 'commun.entree_pour_fermer'))
