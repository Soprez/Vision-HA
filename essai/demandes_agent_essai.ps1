# Demandes d'accès, côté agent : la vraie Send-Demandes face à Home Assistant qui démarre, refuse ou répond.
# Constat du 19/10 : une demande envoyée pendant un redémarrage (404 ou 503) était effacée sans rien dire, alors que
# l'enfant avait lu « envoyée ». Lancé par essai/demandes_livraison_test.py ; code 1 au premier écart.
$ErrorActionPreference = 'Stop'
$agent = Join-Path $PSScriptRoot '..\custom_components\pc_parental\client\ha-parental-agent.ps1'
$tok = $null; $err = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path $agent), [ref]$tok, [ref]$err)
if ($err.Count) { Write-Output "Agent illisible : $($err[0].Message)"; exit 1 }
foreach ($nom in @('Send-Demandes', 'Get-PropOrDefault')) {
    $f = $ast.FindAll({ param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq $nom }, $false) |
        Select-Object -First 1
    if ($null -eq $f) { Write-Output "Fonction $nom absente de l'agent."; exit 1 }
    . ([scriptblock]::Create($f.Extent.Text))
}

# Doublures : Home Assistant simulé, journal et messages gardés pour vérification.
$script:SondeDir = Join-Path ([IO.Path]::GetTempPath()) ('vision-demandes-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $script:SondeDir | Out-Null
$script:Reponse = 'ok'      # 'ok', 'refus' ou un code HTTP d'échec (0 = réseau)
$script:Envois = 0; $script:Messages = @(); $script:Journal = @()
function Module-Actif { param($Nom) $true }
function T { param($Cle, $Valeurs) $Cle }
function Write-Journal { param($Texte, $Niveau) $script:Journal += $Texte }
function Send-Message { param($Texte, $Titre, $Secondes, $Son) $script:Messages += $Texte; $true }
function Get-CodeHttp { param($Erreur) [int]$script:Reponse }
function Invoke-Ha {
    param($Chemin, $Corps, $Config)
    $script:Envois++
    if ($script:Reponse -eq 'ok') { return [pscustomobject]@{ ok = $true; demande = 'abcd' } }
    if ($script:Reponse -eq 'refus') { return [pscustomobject]@{ ok = $false; error = 'refus'; retour = 'pas maintenant' } }
    throw "HTTP $script:Reponse"
}
$config = [pscustomobject]@{ id = 'pc'; secret = 's' }
function Deposer-Demande([string]$Nom, [double]$AgeHeures = 0) {
    $p = Join-Path $script:SondeDir ('demande-{0}.json' -f [DateTime]::UtcNow.Ticks)
    (@{ genre = 'apps'; nom = $Nom; libelle = $Nom } | ConvertTo-Json -Compress) | Set-Content -Path $p -Encoding UTF8
    if ($AgeHeures) { (Get-Item $p).LastWriteTime = (Get-Date).AddHours(-$AgeHeures) }
    Start-Sleep -Milliseconds 20
}
function Fichiers { @(Get-ChildItem -Path $script:SondeDir -Filter 'demande-*.json').Count }
function Remettre { $script:Envois = 0; $script:Messages = @(); $script:Journal = @() }

$ecarts = 0
function Verifier([string]$Quoi, $Obtenu, $Attendu) {
    if ("$Obtenu" -ne "$Attendu") { Write-Output "ÉCART $Quoi : obtenu « $Obtenu », attendu « $Attendu »"; $script:ecarts++ }
    else { Write-Output "ok   $Quoi" }
}

try {
    # 1. Home Assistant démarre (503 « loading ») : la demande reste, l'enfant n'est pas trompé, nouvel essai ensuite.
    Remettre; Deposer-Demande 'Roblox'; $script:Reponse = '503'
    Send-Demandes -Config $config
    Verifier '503 : fichier gardé' (Fichiers) 1
    Verifier '503 : aucun message à l''enfant' $script:Messages.Count 0
    # 2. Intégration pas encore chargée (404) : gardé aussi.
    $script:Reponse = '404'; Send-Demandes -Config $config
    Verifier '404 : fichier gardé' (Fichiers) 1
    # 3. Réseau coupé (pas de code) : gardé.
    $script:Reponse = '0'; Send-Demandes -Config $config
    Verifier 'réseau : fichier gardé' (Fichiers) 1
    # 4. Home Assistant revenu : envoyée, effacée, et là seulement « envoyée ».
    Remettre; $script:Reponse = 'ok'; Send-Demandes -Config $config
    Verifier 'retour de HA : envoyée' $script:Envois 1
    Verifier 'retour de HA : fichier retiré' (Fichiers) 0
    Verifier 'retour de HA : message « envoyée »' ($script:Messages -join ',') 'agent.demande_envoyee'
    # 5. Refus définitif (400) : effacée, et l'enfant le sait.
    Remettre; Deposer-Demande 'Discord'; $script:Reponse = '400'; Send-Demandes -Config $config
    Verifier '400 : fichier retiré' (Fichiers) 0
    Verifier '400 : l''enfant est prévenu' ($script:Messages -join ',') 'agent.demande_non_envoyee'
    # 6. Restée plus de 24 h sans partir : abandonnée, l'enfant le sait, rien n'est envoyé.
    Remettre; Deposer-Demande 'Minecraft' 25; $script:Reponse = 'ok'; Send-Demandes -Config $config
    Verifier '24 h : rien envoyé' $script:Envois 0
    Verifier '24 h : fichier retiré' (Fichiers) 0
    Verifier '24 h : l''enfant est prévenu' ($script:Messages -join ',') 'agent.demande_non_envoyee'
    # 7. Plusieurs en attente pendant un redémarrage : toutes repartent au retour, dans l'ordre.
    Remettre; Deposer-Demande 'A'; Deposer-Demande 'B'; $script:Reponse = '502'; Send-Demandes -Config $config
    Verifier '502 : les deux gardées' (Fichiers) 2
    $script:Reponse = 'ok'; Send-Demandes -Config $config
    Verifier 'retour : les deux envoyées' $script:Envois 3
    Verifier 'retour : plus rien en attente' (Fichiers) 0
} finally {
    Remove-Item -Recurse -Force $script:SondeDir -ErrorAction SilentlyContinue
}
if ($ecarts) { Write-Output "$ecarts écart(s)."; exit 1 }
Write-Output 'Tout est conforme.'
exit 0
