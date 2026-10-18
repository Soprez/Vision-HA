<#
    1.93.06 (sécurité) : maison.json (l'état de la maison, avec la liste des logiciels de chaque PC) n'est lisible que
    par SYSTEM, les administrateurs et le compte de la session s'il administre le PC ; ailleurs, il n'existe pas.

    Le dossier d'essai imite celui de la sonde : le groupe Utilisateurs y a « Modifier » sur le contenu (hérité). Avant
    le correctif, maison.json en héritait : tout compte du PC le lisait. L'essai tourne sans droits d'administrateur.

    powershell -NoProfile -ExecutionPolicy Bypass -File maison_acl_essai.ps1 -Agent <ha-parental-agent.ps1>
#>
param([Parameter(Mandatory)][string]$Agent)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$bac = Join-Path $env:TEMP ('vision-maison-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Path $bac | Out-Null
$acl = Get-Acl $bac
$utilisateurs = New-Object Security.Principal.SecurityIdentifier('S-1-5-32-545')
$acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($utilisateurs, 'Modify', 'ContainerInherit,ObjectInherit', 'InheritOnly', 'Allow')))
Set-Acl -Path $bac -AclObject $acl

$erreurs = $null
$arbre = [System.Management.Automation.Language.Parser]::ParseFile($Agent, [ref]$null, [ref]$erreurs)
if ($erreurs.Count) { throw "L'agent ne se lit pas : $($erreurs[0].Message)" }
$voulues = 'Get-PropOrDefault', 'Module-Actif', 'Sync-Maison', 'Write-FichierLecteur', 'Get-LecteurMaison'
foreach ($f in $arbre.FindAll({ param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst] }, $false)) {
    if ($voulues -contains $f.Name) { . ([scriptblock]::Create($f.Extent.Text)) }
}
$script:Journal = @()
function Write-Journal { param([string]$Message, [string]$Niveau = 'INFO') $script:Journal += "[$Niveau] $Message" }

# Home Assistant, la session et les administrateurs, simulés.
$script:MaisonPath = Join-Path $bac 'maison.json'
$script:DerniereMaison = [datetime]::MinValue
$script:LecteurMaison = ''
$script:Modules = $null
$script:EstParent = $true
$moi = [Security.Principal.WindowsIdentity]::GetCurrent()
$script:Session = [pscustomobject]@{ User = $moi.Name.Split('\')[-1] }
$script:Admins = @($moi.User.Value)
function Get-SessionConsole { return $script:Session }
function Get-SidsAdministrateurs { return $script:Admins }
function Invoke-Ha {
    param($Chemin, $Corps, $Config)
    return ('{"ok": true, "appareils": [{"id": "pc1", "nom": "PC-LOU", "logiciels": [{"nom": "Discord"}]}]}' | ConvertFrom-Json)
}
$config = [pscustomobject]@{ id = 'pc-parent'; secret = 's' }

$script:Rates = 0
function Verifier {
    param([string]$Quoi, $Obtenu, $Attendu)
    if ("$Obtenu" -eq "$Attendu") { Write-Output "ok    $Quoi" }
    else { $script:Rates++; Write-Output "RATE  $Quoi : obtenu « $Obtenu », attendu « $Attendu »" }
}
function Droits([string]$chemin) {
    $a = Get-Acl -LiteralPath $chemin
    $regles = @($a.Access | ForEach-Object { $_.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value + ':' + $_.AccessControlType } | Sort-Object -Unique)
    return [pscustomobject]@{ Protege = $a.AreAccessRulesProtected; Sids = $regles }
}
function Lecteurs([string]$chemin) {
    return ((Droits $chemin).Sids | Where-Object { $_ -like '*:Allow' } | ForEach-Object { $_.Split(':')[0] } | Sort-Object) -join ','
}
$attendus = (@('S-1-5-18', 'S-1-5-32-544', $moi.User.Value) | Sort-Object) -join ','

try {
    # 1. Le PC d'un parent, session d'un administrateur : le fichier n'est lisible que par SYSTEM, admins et lui.
    Sync-Maison -Config $config -Force $true
    Verifier 'parent admin : fichier écrit' (Test-Path $script:MaisonPath) 'True'
    if (Test-Path $script:MaisonPath) {
        $d = Droits $script:MaisonPath
        Verifier 'parent admin : droits non hérités du dossier' $d.Protege 'True'
        Verifier 'parent admin : pas le groupe Utilisateurs' (@($d.Sids) -contains ($utilisateurs.Value + ':Allow')) 'False'
        Verifier 'parent admin : lecteurs = SYSTEM, admins, le compte de la session' (Lecteurs $script:MaisonPath) $attendus
        Verifier 'parent admin : contenu lisible par lui' ((Get-Content $script:MaisonPath -Raw -Encoding UTF8 | ConvertFrom-Json).appareils[0].nom) 'PC-LOU'
    }

    # 2. Un fichier posé avant par un utilisateur du PC (avec ses droits à lui) : remplacé, pas réécrit en place.
    [System.IO.File]::Delete($script:MaisonPath)
    Set-Content -Path $script:MaisonPath -Value '{"pose": true}' -Encoding UTF8   # hérite « Utilisateurs : Modifier »
    Sync-Maison -Config $config -Force $true
    Verifier 'fichier posé : remplacé par un fichier protégé' (Droits $script:MaisonPath).Protege 'True'
    Verifier 'fichier posé : lecteurs' (Lecteurs $script:MaisonPath) $attendus

    # 3. Sans module qui s'en sert (blocage, optimisations) : pas de liste des logiciels.
    $script:Modules = @('filtrage')
    Sync-Maison -Config $config -Force $true
    Verifier 'modules coupés : pas de logiciels copiés' ((Get-Content $script:MaisonPath -Raw -Encoding UTF8) -match 'Discord') 'False'
    $script:Modules = $null

    # 4. Un compte ordinaire ouvre sa session sur le PC du parent : plus de fichier.
    $script:Admins = @('S-1-5-21-0-0-0-500')
    Sync-Maison -Config $config
    Verifier 'compte ordinaire : fichier retiré' (Test-Path $script:MaisonPath) 'False'

    # 5. Un PC qui n'est pas celui d'un parent : jamais de fichier.
    $script:Admins = @($moi.User.Value); $script:EstParent = $false
    Sync-Maison -Config $config -Force $true
    Verifier 'pas un PC de parent : pas de fichier' (Test-Path $script:MaisonPath) 'False'

    # 6. Personne en session : pas de fichier.
    $script:EstParent = $true; $script:Session = $null
    Sync-Maison -Config $config -Force $true
    Verifier 'pas de session : pas de fichier' (Test-Path $script:MaisonPath) 'False'
} finally {
    Remove-Item -LiteralPath $bac -Recurse -Force -ErrorAction SilentlyContinue
}

if ($script:Rates) { Write-Output "$($script:Rates) RATE(S)"; exit 1 }
Write-Output 'Tout est bon.'
