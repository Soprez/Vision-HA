<#
    1.93.2 (perte d'images par seconde chez un Demandeur) : non-régression du blocage par le fichier hosts, avec les
    vraies fonctions de l'agent (Sync-Hosts, Sync-HostsSiNecessaire, Test-SousDomaineNouveau, Get-EmpreinteHosts,
    Invoke-FlushDnsGroupe), tirées du script. Le fichier hosts est un fichier temporaire ; ipconfig est compté, le
    journal neutralisé.

    Vérifie :
      - un site bloqué l'est, avec www et ses sous-domaines vus ; une exception l'épargne ; la liste noire est bloquée ;
      - même version de Home Assistant, rien de nouveau : aucune reconstruction, le fichier n'est pas réécrit ;
      - un sous-domaine nouveau d'un site bloqué : reconstruction, et il est bloqué ;
      - un nom vu sans rapport : aucune reconstruction ;
      - version changée (site débloqué, exception posée) : reconstruction et effet ;
      - le fichier touché par quelqu'un d'autre : le bloc est remis ;
      - la reconstruction de sécurité après 10 minutes ;
      - sans version (Home Assistant ancien, hors ligne) : empreinte locale, même comportement ;
      - vidage du cache DNS regroupé (aucun appel tant que l'appelant ne le demande) ;
      - temps : un tour sans changement < 0,2 s, une reconstruction (717 sites, 4 000 noms noirs) < 2 s.

        powershell -NoProfile -ExecutionPolicy Bypass -File essai\hosts_version_essai.ps1
    Code de sortie : nombre d'échecs (0 si tout passe).
#>
$ErrorActionPreference = 'Stop'
$agent = Join-Path $PSScriptRoot '..\custom_components\pc_parental\client\ha-parental-agent.ps1'
$ast = [System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path $agent), [ref]$null, [ref]$null)
foreach ($a in $ast.FindAll({ param($n) $n -is [System.Management.Automation.Language.AssignmentStatementAst] -and
        $n.Left.Extent.Text -in @('$script:MarqueDebut', '$script:MarqueFin', '$script:MaxSousDomaines',
            '$script:HostsSecuriteSecondes', '$script:FlushDnsDemande') }, $false)) {
    Invoke-Expression $a.Extent.Text
}
foreach ($nom in @('Sync-Hosts', 'Sync-HostsSiNecessaire', 'Test-SousDomaineNouveau', 'Get-EmpreinteHosts', 'Invoke-FlushDnsGroupe')) {
    $f = @($ast.FindAll({ param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq $nom }, $true))
    if ($f.Count -ne 1) { throw "$nom introuvable dans l'agent" }
    Invoke-Expression $f[0].Extent.Text
}
function Write-Journal { param($m, $n) }
$script:Vidages = 0
function ipconfig { $script:Vidages++ }

$script:HostsPath = Join-Path ([IO.Path]::GetTempPath()) ('vision-hostsv-{0}.txt' -f [guid]::NewGuid())
Set-Content -Path $script:HostsPath -Value @('# hosts de l''essai', '127.0.0.1 nas-local.lan') -Encoding UTF8
$script:echecs = 0
$script:etape = ''
function Verifier([string]$quoi, [bool]$ok) {
    if ($ok) { Write-Host ("ok    {0} : {1}" -f $script:etape, $quoi) }
    else { Write-Host ("ÉCHEC {0} : {1}" -f $script:etape, $quoi); $script:echecs++ }
}
function Lignes { @(Get-Content $script:HostsPath -Encoding UTF8) }
function Bloque([string]$nom) { return [bool]((Lignes) -contains "0.0.0.0 $nom") }
function Ecrit { (Get-Item $script:HostsPath).LastWriteTimeUtc.Ticks }
function Tour([string]$version, [string[]]$sites, [string[]]$exc = @(), [string[]]$vus = @(), [string[]]$noire = @()) {
    return [bool](Sync-HostsSiNecessaire -Version $version -Sites $sites -Exceptions $exc -Vus $vus -Dangereux $noire -Flush $false)
}

$sites = @('roblox.com', 'youtube.com', 'tiktok.com')
$vus = @('c0.roblox.com', 'm.youtube.com', 'meteo.example.org')
$noire = @('mal1.test', 'mal2.test')

$etape = '1 premier tour'
Verifier 'reconstruit' (Tour 'v1' $sites @('m.youtube.com') $vus $noire)
foreach ($n in @('roblox.com', 'www.roblox.com', 'c0.roblox.com', 'youtube.com', 'tiktok.com', 'mal1.test', 'mal2.test')) { Verifier "$n bloqué" (Bloque $n) }
Verifier 'exception m.youtube.com épargnée' (-not (Bloque 'm.youtube.com'))
Verifier 'nom sans rapport non bloqué' (-not (Bloque 'meteo.example.org'))
Verifier 'IPv6 aussi' ((Lignes) -contains ':: roblox.com')
Verifier 'ligne posée à la main intacte' ((Lignes) -contains '127.0.0.1 nas-local.lan')
Verifier 'aucun vidage DNS sans demande de l''appelant' ($script:Vidages -eq 0)
Invoke-FlushDnsGroupe
Verifier 'un seul vidage DNS au regroupement' ($script:Vidages -eq 1)
Invoke-FlushDnsGroupe
Verifier 'pas de second vidage' ($script:Vidages -eq 1)

$etape = '2 rien n''a changé'
$t = Ecrit
Start-Sleep -Milliseconds 30
Verifier 'pas de reconstruction' (-not (Tour 'v1' $sites @('m.youtube.com') $vus $noire))
Verifier 'fichier non réécrit' ((Ecrit) -eq $t)

$etape = '3 sous-domaine nouveau'
Verifier 'reconstruit' (Tour 'v1' $sites @('m.youtube.com') ($vus + 'cdn.tiktok.com') $noire)
Verifier 'cdn.tiktok.com bloqué' (Bloque 'cdn.tiktok.com')
Verifier 'il reste bloqué ensuite (mémoire du bloc)' ((-not (Tour 'v1' $sites @('m.youtube.com') $vus $noire)) -and (Bloque 'cdn.tiktok.com'))

$etape = '4 nom vu sans rapport'
Verifier 'pas de reconstruction' (-not (Tour 'v1' $sites @('m.youtube.com') ($vus + 'nouveau.ailleurs.net') $noire))
Verifier 'exception vue : pas de reconstruction' (-not (Tour 'v1' $sites @('m.youtube.com') ($vus + 'm.youtube.com') $noire))

$etape = '5 version changée : roblox débloqué, exception sur tiktok'
Verifier 'reconstruit' (Tour 'v2' @('youtube.com', 'tiktok.com') @('m.youtube.com', 'tiktok.com') $vus $noire)
Verifier 'roblox.com ouvert' (-not (Bloque 'roblox.com'))
Verifier 'c0.roblox.com ouvert' (-not (Bloque 'c0.roblox.com'))
Verifier 'tiktok.com épargné' (-not (Bloque 'tiktok.com'))
Verifier 'youtube.com toujours bloqué' (Bloque 'youtube.com')

$etape = '6 liste noire changée'
Verifier 'reconstruit' (Tour 'v3' @('youtube.com') @() $vus @('mal1.test', 'mal3.test'))
Verifier 'mal3.test bloqué' (Bloque 'mal3.test')
Verifier 'mal2.test retiré' (-not (Bloque 'mal2.test'))

$etape = '7 fichier touché par un autre'
$texte = @(Lignes | Where-Object { $_ -ne '0.0.0.0 youtube.com' })
Set-Content -Path $script:HostsPath -Value $texte -Encoding UTF8
Verifier 'reconstruit' (Tour 'v3' @('youtube.com') @() $vus @('mal1.test', 'mal3.test'))
Verifier 'youtube.com rebloqué' (Bloque 'youtube.com')

$etape = '8 sécurité 10 min'
$script:HostsEtat.Depuis = (Get-Date).AddSeconds(-($script:HostsSecuriteSecondes + 1))
Verifier 'reconstruit' (Tour 'v3' @('youtube.com') @() $vus @('mal1.test', 'mal3.test'))
Verifier 'puis plus rien' (-not (Tour 'v3' @('youtube.com') @() $vus @('mal1.test', 'mal3.test')))

$etape = '9 sans version (HA ancien, hors ligne)'
Verifier 'reconstruit (empreinte locale)' (Tour '' @('youtube.com', 'roblox.com') @() $vus @('mal1.test'))
Verifier 'roblox.com bloqué' (Bloque 'roblox.com')
Verifier 'même entrée : rien' (-not (Tour '' @('youtube.com', 'roblox.com') @() $vus @('mal1.test')))
Verifier 'entrée changée : reconstruit' (Tour '' @('youtube.com') @() $vus @('mal1.test'))
Verifier 'roblox.com rouvert' (-not (Bloque 'roblox.com'))

$etape = '10 temps (717 sites, 4 000 noms noirs)'
$grand = 0..716 | ForEach-Object { 'site{0}.{1}' -f $_, @('com', 'net', 'fr')[$_ % 3] }
$memoire = 0..1999 | ForEach-Object { 'm{0}.site{1}.com' -f $_, (3 * ($_ % 239)) }
$gn = 0..3999 | ForEach-Object { 'mal{0}.test' -f $_ }
[void](Tour 'g0' $grand @() $memoire $gn)
$sw = [Diagnostics.Stopwatch]::StartNew(); [void](Tour 'g1' $grand @() $vus $gn); $sw.Stop()
Verifier ("reconstruction en {0:N2} s (< 2 s)" -f $sw.Elapsed.TotalSeconds) ($sw.Elapsed.TotalSeconds -lt 2)
$sw = [Diagnostics.Stopwatch]::StartNew(); [void](Tour 'g1' $grand @() $vus $gn); $sw.Stop()
Verifier ("tour sans changement en {0:N3} s (< 0,2 s)" -f $sw.Elapsed.TotalSeconds) ($sw.Elapsed.TotalSeconds -lt 0.2)
Verifier 'sous-domaines de la mémoire gardés' (Bloque 'm3.site9.com')

Remove-Item $script:HostsPath -Force
Write-Host "échecs : $script:echecs"
exit $script:echecs
