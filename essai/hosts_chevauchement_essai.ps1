<#
    Double autorisation (2.4.4), côté agent Windows : la vraie fonction Sync-Hosts de l'agent, tirée du script, rejoue
    la suite de décisions que Home Assistant envoie quand des accords se chevauchent (Roblox pour un temps, Minecraft en
    exception, puis fin du temps). Le fichier hosts est un fichier temporaire ; ipconfig et le journal sont neutralisés.

    Vérifie : un site ouvert l'est pour tout le PC (plus aucune ligne dans le bloc de Vision), la fin d'un accord ne
    rouvre ni ne referme l'autre, les sous-domaines vus reviennent bloqués à l'échéance, les lignes posées à la main
    hors du bloc de Vision restent intactes, et rien ne reste ouvert quand tout est fini.

        powershell -NoProfile -ExecutionPolicy Bypass -File essai\hosts_chevauchement_essai.ps1
    Code de sortie 0 si tout passe.
#>
$ErrorActionPreference = 'Stop'
$agent = Join-Path $PSScriptRoot '..\custom_components\pc_parental\client\ha-parental-agent.ps1'
$ast = [System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path $agent), [ref]$null, [ref]$null)

# Les constantes du bloc de Vision et la fonction, telles que l'agent les définit.
foreach ($a in $ast.FindAll({ param($n) $n -is [System.Management.Automation.Language.AssignmentStatementAst] -and
        $n.Left.Extent.Text -in @('$script:MarqueDebut', '$script:MarqueFin', '$script:MaxSousDomaines') }, $false)) {
    Invoke-Expression $a.Extent.Text
}
$f = $ast.FindAll({ param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Sync-Hosts' }, $true)
if (@($f).Count -ne 1) { throw 'Sync-Hosts introuvable dans l''agent' }
Invoke-Expression $f[0].Extent.Text
function Write-Journal { param($m, $n) }
function ipconfig { }

$script:HostsPath = Join-Path ([IO.Path]::GetTempPath()) ('vision-hosts-{0}.txt' -f [guid]::NewGuid())
Set-Content -Path $script:HostsPath -Value @('# hosts de l''essai', '127.0.0.1 nas-local.lan') -Encoding UTF8

$roblox = @('roblox.com', 'rbx.com', 'rbxinfra.com', 'rbxcdn.com', 'robloxlabs.com')
$minecraft = @('minecraft.net', 'mojang.com', 'minecraftservices.com')
$vus = @('c0.rbxcdn.com', 'tr.rbxcdn.com', 'www.minecraft.net', 'm.youtube.com')
$echecs = 0
$etape = ''

function Bloque([string]$nom) { return [bool](@(Get-Content $script:HostsPath -Encoding UTF8) -contains "0.0.0.0 $nom") }
function Verifier([string]$quoi, [bool]$ok) {
    if ($ok) { Write-Host ("ok    {0} : {1}" -f $script:etape, $quoi) }
    else { Write-Host ("ÉCHEC {0} : {1}" -f $script:etape, $quoi); $script:echecs++ }
}
function Main-Intacte { Verifier 'ligne posée à la main intacte' ((@(Get-Content $script:HostsPath -Encoding UTF8) -contains '127.0.0.1 nas-local.lan')) }

# 0. « Jeux » fermé, aucun accord.
$etape = '0 aucun accord'
Sync-Hosts -Sites ($roblox + $minecraft + 'youtube.com') -Vus $vus
foreach ($n in $roblox + $minecraft + 'c0.rbxcdn.com' + 'www.minecraft.net') { Verifier "$n bloqué" (Bloque $n) }
Main-Intacte

# 1. Roblox pour un temps : Home Assistant retire ses sites des blocages et les met en exceptions.
$etape = '1 Roblox pour un temps'
Sync-Hosts -Sites ($minecraft + 'youtube.com') -Exceptions $roblox -Vus $vus
foreach ($n in $roblox + 'c0.rbxcdn.com' + 'tr.rbxcdn.com') { Verifier "$n ouvert" (-not (Bloque $n)) }
foreach ($n in $minecraft + 'youtube.com') { Verifier "$n toujours bloqué" (Bloque $n) }
Main-Intacte

# 2. + Minecraft en exception.
$etape = '2 + Minecraft en exception'
Sync-Hosts -Sites @('youtube.com') -Exceptions ($roblox + $minecraft) -Vus $vus
foreach ($n in $roblox + $minecraft + 'www.minecraft.net') { Verifier "$n ouvert" (-not (Bloque $n)) }
Verifier 'youtube.com toujours bloqué' (Bloque 'youtube.com')

# 3. Fin du temps de Roblox : Roblox et ses sous-domaines vus se referment, Minecraft reste ouvert.
$etape = '3 fin du temps de Roblox'
Sync-Hosts -Sites ($roblox + 'youtube.com') -Exceptions $minecraft -Vus $vus
foreach ($n in $roblox + 'c0.rbxcdn.com' + 'tr.rbxcdn.com') { Verifier "$n refermé" (Bloque $n) }
foreach ($n in $minecraft + 'www.minecraft.net') { Verifier "$n encore ouvert" (-not (Bloque $n)) }
Main-Intacte

# 4. Un sous-domaine épargné reste ouvert sous un domaine bloqué (exception d'adresse exacte).
$etape = '4 adresse exacte épargnée'
Sync-Hosts -Sites ($roblox + 'youtube.com') -Exceptions ($minecraft + 'tr.rbxcdn.com') -Vus $vus
Verifier 'tr.rbxcdn.com épargné' (-not (Bloque 'tr.rbxcdn.com'))
Verifier 'c0.rbxcdn.com bloqué' (Bloque 'c0.rbxcdn.com')

# 5. Plus aucun accord : tout est refermé, rien ne reste ouvert.
$etape = '5 plus aucun accord'
Sync-Hosts -Sites ($roblox + $minecraft + 'youtube.com') -Vus $vus
foreach ($n in $roblox + $minecraft + 'c0.rbxcdn.com' + 'tr.rbxcdn.com' + 'www.minecraft.net' + 'youtube.com' + 'm.youtube.com') {
    Verifier "$n bloqué" (Bloque $n)
}
Main-Intacte
$lignes = @(Get-Content $script:HostsPath -Encoding UTF8)
Verifier 'un seul bloc de Vision' ((@($lignes | Where-Object { $_ -eq $script:MarqueDebut }).Count) -eq 1)

Remove-Item $script:HostsPath -Force
if ($echecs) { Write-Host "$echecs échec(s)"; exit 1 }
Write-Host 'Tout passe.'
exit 0
