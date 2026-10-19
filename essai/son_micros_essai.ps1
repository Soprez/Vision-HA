# Niveau sonore (2.5.12.3, précision de l'auteur) : seuls les micros vraiment allumés comptent dans la moyenne.
# Lit la VRAIE sonde dans l'agent (bloc $script:SonSource), en charge Mesurer-Tranche et Micros-Pris, et les fait
# tourner sur de faux micros : muet, silence numérique, niveau figé, débranché, aucun micro actif.
# Lancé par essai/son_pc_test.py ; sort avec le code 1 au premier écart.
$ErrorActionPreference = 'Stop'
$agent = Join-Path $PSScriptRoot '..\custom_components\pc_parental\client\ha-parental-agent.ps1'
$tok = $null; $err = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path $agent), [ref]$tok, [ref]$err)
if ($err.Count) { Write-Output "Agent illisible : $($err[0].Message)"; exit 1 }
$aff = $ast.FindAll({ param($n) $n -is [System.Management.Automation.Language.AssignmentStatementAst] -and
                     $n.Left.Extent.Text -eq '$script:SonSource' }, $true) | Select-Object -First 1
$sonde = [System.Management.Automation.Language.Parser]::ParseInput($aff.Right.Expression.Value, [ref]$tok, [ref]$err)
foreach ($nom in @('Mesurer-Tranche', 'Micros-Pris')) {
    $f = $sonde.FindAll({ param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq $nom }, $true) |
        Select-Object -First 1
    if ($null -eq $f) { Write-Output "Fonction $nom absente de la sonde."; exit 1 }
    . ([scriptblock]::Create($f.Extent.Text))
}

function Faux-Micro {
    # Un micro qui rend ses valeurs RMS l'une après l'autre (-1 : rien cette tranche ; 0 : silence numérique).
    param([string]$Id, [double[]]$Valeurs, [bool]$Muet = $false)
    $m = [pscustomobject]@{ Id = $Id; Nom = "Micro $Id"; File = [System.Collections.Queue]::new([object[]]$Valeurs); EstMuet = $Muet }
    $m | Add-Member ScriptMethod LireRms { if ($this.File.Count) { [double]$this.File.Dequeue() } else { -1.0 } }
    $m | Add-Member ScriptMethod Muet { $this.EstMuet }
    return $m
}

$ecarts = 0
function Verifier([string]$Quoi, $Obtenu, $Attendu) {
    if ([math]::Abs([double]$Obtenu - [double]$Attendu) -gt 1e-9) {
        Write-Output "ÉCART $Quoi : obtenu $Obtenu, attendu $Attendu"; $script:ecarts++
    } else { Write-Output "ok   $Quoi" }
}

$t0 = Get-Date '2026-10-19T23:00:00'

# 1. Deux micros allumés : la moyenne en énergie des deux.
$figes = @{}; $vus = @{}
$r = Mesurer-Tranche -Micros @((Faux-Micro 'a' @(0.1)), (Faux-Micro 'b' @(0.3))) -Figes $figes -VusActifs $vus -Maintenant $t0 -Fige 10
Verifier 'moyenne de deux micros allumés' $r ([math]::Sqrt((0.01 + 0.09) / 2))

# 2. Un micro muet dans Windows ne compte pas, même s'il rend du signal.
$figes = @{}; $vus = @{}
$r = Mesurer-Tranche -Micros @((Faux-Micro 'a' @(0.1)), (Faux-Micro 'm' @(0.5) $true)) -Figes $figes -VusActifs $vus -Maintenant $t0 -Fige 10
Verifier 'micro muet exclu' $r 0.1
Verifier 'micro muet absent de la liste' $vus.Count 1

# 3. Un silence numérique (0 exact = -inf dBFS) ne tire pas la moyenne vers le bas.
$figes = @{}; $vus = @{}
$r = Mesurer-Tranche -Micros @((Faux-Micro 'a' @(0.1)), (Faux-Micro 'z' @(0.0))) -Figes $figes -VusActifs $vus -Maintenant $t0 -Fige 10
Verifier 'silence numérique exclu' $r 0.1

# 4. Un micro au niveau figé compte jusqu'à Fige secondes, puis il est exclu ; il revient dès qu'il bouge.
$figes = @{}; $vus = @{}
$fige = Faux-Micro 'f' (@(0.2) * 200 + @(0.25))
$vivant = Faux-Micro 'v' @(1..201 | ForEach-Object { 0.1 + ($_ % 7) / 1000.0 })
$r9 = $null; $r11 = $null
for ($k = 0; $k -le 110; $k++) {
    $r = Mesurer-Tranche -Micros @($fige, $vivant) -Figes $figes -VusActifs $vus -Maintenant $t0.AddMilliseconds(100 * $k) -Fige 10
    if ($k -eq 90) { $r9 = $r; $v9 = 0.1 + (($k + 1) % 7) / 1000.0 }
    if ($k -eq 110) { $r11 = $r; $v11 = 0.1 + (($k + 1) % 7) / 1000.0 }
}
Verifier 'micro figé encore compté à 9 s' $r9 ([math]::Sqrt((0.04 + $v9 * $v9) / 2))
Verifier 'micro figé exclu après 10 s' $r11 $v11

# 5. Aucun micro actif (muet, silence, rien, figé seul) : -1, et aucun micro pris en compte (capteur indisponible).
$figes = @{}; $vus = @{}
$micros = @((Faux-Micro 'm' @(0.4) $true), (Faux-Micro 'z' @(0.0)), (Faux-Micro 'r' @()))
$r = Mesurer-Tranche -Micros $micros -Figes $figes -VusActifs $vus -Maintenant $t0 -Fige 10
Verifier 'aucun micro actif : pas de niveau' $r -1
$pris = @(Micros-Pris -Micros $micros -Figes $figes -VusActifs $vus -Maintenant $t0)
Verifier 'aucun micro actif : liste vide' $pris.Count 0
$figes = @{}; $vus = @{}
$seul = Faux-Micro 's' (@(0.3) * 200)
for ($k = 0; $k -le 120; $k++) {
    $r = Mesurer-Tranche -Micros @($seul) -Figes $figes -VusActifs $vus -Maintenant $t0.AddMilliseconds(100 * $k) -Fige 10
}
Verifier 'un seul micro, figé : pas de niveau' $r -1
$pris = @(Micros-Pris -Micros @($seul) -Figes $figes -VusActifs $vus -Maintenant $t0.AddSeconds(18))
Verifier 'un seul micro, figé : plus pris en compte 5 s après' $pris.Count 0

# 6. Un micro débranché (plus énuméré) disparaît de la liste aussitôt.
$figes = @{}; $vus = @{}
$a = Faux-Micro 'a' @(0.1); $b = Faux-Micro 'b' @(0.2)
[void](Mesurer-Tranche -Micros @($a, $b) -Figes $figes -VusActifs $vus -Maintenant $t0 -Fige 10)
$pris = @(Micros-Pris -Micros @($a) -Figes $figes -VusActifs $vus -Maintenant $t0.AddSeconds(1))
Verifier 'micro débranché retiré' $pris.Count 1
if ($pris[0] -ne 'a' -or $vus['a'].nom -ne 'Micro a') { Write-Output "ÉCART liste : $($pris -join ',')"; $ecarts++ }

if ($ecarts) { Write-Output "$ecarts écart(s)."; exit 1 }
Write-Output 'Tout est conforme.'
exit 0
