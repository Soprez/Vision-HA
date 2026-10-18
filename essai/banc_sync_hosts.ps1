<#
    Banc de temps de Sync-Hosts (1.93.02, perte de FPS sur un PC de Demandeur).

    Données synthétiques, de la taille mesurée en essai le 16/10 : 717 sites bloqués, environ 5 700 noms candidats
    (400 vus dans le cache DNS + les noms déjà dans le bloc de Vision), 4 000 domaines de la liste noire. Aucune donnée
    réelle de navigation : les noms sont fabriqués (graine fixe).

    Mesure trois tours : premier tour (écriture), tour identique (rien n'a changé), tour avec un sous-domaine nouveau.
    Le fichier hosts est un fichier temporaire ; ipconfig et le journal sont neutralisés.

        powershell -NoProfile -ExecutionPolicy Bypass -File essai\banc_sync_hosts.ps1 [-Agent <chemin de l'agent>]
#>
param([string]$Agent = (Join-Path $PSScriptRoot '..\custom_components\pc_parental\client\ha-parental-agent.ps1'))
$ErrorActionPreference = 'Stop'
$ast = [System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path $Agent), [ref]$null, [ref]$null)
foreach ($a in $ast.FindAll({ param($n) $n -is [System.Management.Automation.Language.AssignmentStatementAst] -and
        $n.Left.Extent.Text -in @('$script:MarqueDebut', '$script:MarqueFin', '$script:MaxSousDomaines', '$script:HostsSecuriteSecondes') }, $false)) {
    Invoke-Expression $a.Extent.Text
}
foreach ($nom in @('Sync-Hosts', 'Sync-HostsSiNecessaire', 'Test-SousDomaineNouveau', 'Get-EmpreinteHosts')) {
    $f = @($ast.FindAll({ param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq $nom }, $true))
    if ($f.Count -eq 1) { Invoke-Expression $f[0].Extent.Text }
}
function Write-Journal { param($m, $n) }
function ipconfig { }
$nouvelle = [bool](Get-Command Sync-HostsSiNecessaire -ErrorAction SilentlyContinue)

$alea = New-Object System.Random 1932
$tlds = @('com', 'net', 'org', 'fr', 'io', 'tv')
$sites = New-Object System.Collections.Generic.List[string]
for ($i = 0; $i -lt 717; $i++) { $sites.Add(('site{0}-{1}.{2}' -f $i, $alea.Next(1000, 9999), $tlds[$i % $tlds.Count])) }
$noire = New-Object System.Collections.Generic.List[string]
for ($i = 0; $i -lt 4000; $i++) { $noire.Add(('mal{0}x{1}.{2}' -f $i, $alea.Next(100000, 999999), $tlds[$i % $tlds.Count])) }
# Sous-domaines déjà bloqués (la « mémoire » du bloc) : ~1 300 noms sur 300 sites, plus 400 noms vus dans le cache.
$vus = New-Object System.Collections.Generic.List[string]
for ($i = 0; $i -lt 400; $i++) {
    $s = $sites[$alea.Next(0, $sites.Count)]
    if ($i % 3 -eq 0) { $vus.Add(('cdn{0}.autre{1}.com' -f $i, $alea.Next(0, 99999))) } else { $vus.Add(('v{0}.{1}' -f $i, $s)) }
}
$memoire = New-Object System.Collections.Generic.List[string]
for ($i = 0; $i -lt 1300; $i++) { $memoire.Add(('m{0}.{1}' -f $i, $sites[$alea.Next(0, 300)])) }

$script:HostsPath = Join-Path ([IO.Path]::GetTempPath()) ('vision-banc-{0}.txt' -f [guid]::NewGuid())
Set-Content -Path $script:HostsPath -Value @('# hosts du banc', '127.0.0.1 nas-local.lan') -Encoding UTF8
# On amorce le bloc avec la mémoire, comme après des semaines de relevés.
Sync-Hosts -Sites $sites -Vus (@($vus) + @($memoire)) -Dangereux $noire
$lignesBloc = @(Get-Content $script:HostsPath -Encoding UTF8).Count
$candidats = @(@($vus) + @(@(Get-Content $script:HostsPath -Encoding UTF8) | ForEach-Object { ($_ -split '\s+')[1] }) | Where-Object { $_ } | Sort-Object -Unique).Count

function Tour([string]$nomTour, [string[]]$v, [string]$version) {
    $sw = [Diagnostics.Stopwatch]::StartNew()
    if ($nouvelle) {
        [void](Sync-HostsSiNecessaire -Version $version -Sites $sites -Vus $v -Dangereux $noire)
    } else {
        Sync-Hosts -Sites $sites -Vus $v -Dangereux $noire
    }
    $sw.Stop()
    '{0,-34} {1,8:N3} s' -f $nomTour, $sw.Elapsed.TotalSeconds
}
"Agent : $Agent"
"Sites : $($sites.Count) ; liste noire : $($noire.Count) ; candidats : $candidats ; lignes du fichier hosts : $lignesBloc"
"Mode : $(if ($nouvelle) { 'nouveau (version de HA)' } else { 'ancien (reconstruction à chaque tour)' })"
Tour 'Tour 1 (version v1)' $vus 'v1'
Tour 'Tour 2 identique (rien n''a changé)' $vus 'v1'
Tour 'Tour 3 identique' $vus 'v1'
$plus = @($vus) + @("neuf.$($sites[5])")
Tour 'Tour 4 (sous-domaine nouveau)' $plus 'v1'
$autre = @($vus) + @('rien.inconnu-total.com')
Tour 'Tour 5 (nom vu sans rapport)' $autre 'v1'
Tour 'Tour 6 (version v2 : règle changée)' $autre 'v2'
$ok = [bool](@(Get-Content $script:HostsPath -Encoding UTF8) -contains "0.0.0.0 neuf.$($sites[5])")
"Sous-domaine nouveau bloqué : $ok"
Remove-Item $script:HostsPath -Force
