<#
.SYNOPSIS
    Retire l'agent Vision de ce PC.

.DESCRIPTION
    Arrête et supprime la tâche planifiée, nettoie le fichier hosts des
    entrées posées par l'agent, et efface la configuration. Le PC reste
    visible dans Home Assistant : supprime-le depuis le tableau de bord
    si tu ne comptes plus l'utiliser.
#>

[CmdletBinding()]
param([switch]$GarderPolitiquesDns)

$ErrorActionPreference = 'Stop'

$Dossier    = "$env:ProgramData\HAParental"
$DossierSonde = "$env:ProgramData\HAParental-session"
$NomTache   = 'HA Controle Parental'
$HostsPath  = "$env:SystemRoot\System32\drivers\etc\hosts"
$MarqueDebut = '# >>> Controle parental Home Assistant >>>'
$MarqueFin   = '# <<< Controle parental Home Assistant <<<'

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

$identite  = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($identite)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Host (T 'commun.relance_admin') -ForegroundColor Yellow
    Start-Process -FilePath 'powershell.exe' -Verb RunAs -ArgumentList @(
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$PSCommandPath`""
    )
    return
}

Write-Host ''
Write-Host ('  ' + (T 'desinstallation.titre')) -ForegroundColor Cyan
Write-Host ''

Stop-ScheduledTask   -TaskName $NomTache -ErrorAction SilentlyContinue
Unregister-ScheduledTask -TaskName $NomTache -Confirm:$false -ErrorAction SilentlyContinue
Write-Host ('  ' + (T 'desinstallation.tache'))

if (Test-Path $HostsPath) {
    $lignes = @(Get-Content -Path $HostsPath -Encoding UTF8)
    $debut = [array]::IndexOf($lignes, $MarqueDebut)
    $fin   = [array]::IndexOf($lignes, $MarqueFin)
    if ($debut -ge 0 -and $fin -gt $debut) {
        $conserve = @()
        if ($debut -gt 0) { $conserve += $lignes[0..($debut - 1)] }
        if ($fin -lt $lignes.Count - 1) { $conserve += $lignes[($fin + 1)..($lignes.Count - 1)] }
        Set-Content -Path $HostsPath -Value $conserve -Encoding UTF8 -Force
        ipconfig /flushdns | Out-Null
        Write-Host ('  ' + (T 'desinstallation.hosts'))
    }
}

if (-not $GarderPolitiquesDns) {
    foreach ($p in @(
        @{ Chemin = 'HKLM:\SOFTWARE\Policies\Google\Chrome';  Nom = 'DnsOverHttpsMode' },
        @{ Chemin = 'HKLM:\SOFTWARE\Policies\Microsoft\Edge'; Nom = 'DnsOverHttpsMode' },
        @{ Chemin = 'HKLM:\SOFTWARE\Policies\Mozilla\Firefox\DNSOverHTTPS'; Nom = 'Enabled' },
        @{ Chemin = 'HKLM:\SOFTWARE\Policies\Mozilla\Firefox\DNSOverHTTPS'; Nom = 'Locked' }
    )) {
        Remove-ItemProperty -Path $p.Chemin -Name $p.Nom -ErrorAction SilentlyContinue
    }
    Write-Host ('  ' + (T 'desinstallation.dns'))
}

# La sonde d'inactivité tourne dans la session de l'utilisateur : elle
# survivrait à la suppression du dossier.
Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -like '*HAParental*sonde.ps1*' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }

foreach ($d in @($Dossier, $DossierSonde)) {
    if (Test-Path $d) {
        Remove-Item -Path $d -Recurse -Force -ErrorAction SilentlyContinue
    }
}
Write-Host ('  ' + (T 'desinstallation.configuration'))

Write-Host ''
Write-Host ('  ' + (T 'desinstallation.termine')) -ForegroundColor Green
Write-Host ''
Read-Host ('  ' + (T 'commun.entree_pour_fermer'))
