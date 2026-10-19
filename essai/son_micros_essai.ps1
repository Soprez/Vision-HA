# Niveau sonore (2.5.12.3, précision de l'auteur ; agent 1.93.5 : composante continue retirée, détail par micro) : seuls les micros vraiment allumés comptent dans la moyenne.
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
foreach ($nom in @('Mesurer-Tranche', 'Micros-Pris', 'Detail-Micros', 'Niveau-Relatif')) {
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

# 7. (1.93.5) La composante continue est retirée, canal par canal, par la vraie fonction C# de la sonde.
$typeDef = $sonde.FindAll({ param($n) $n -is [System.Management.Automation.Language.CommandAst] -and
                           $n.GetCommandName() -eq 'Add-Type' }, $true) | Select-Object -First 1
$code = ($typeDef.CommandElements | Where-Object { $_ -is [System.Management.Automation.Language.StringConstantExpressionAst] -or
                                                   $_ -is [System.Management.Automation.Language.ExpandableStringExpressionAst] } |
         Select-Object -Last 1).Value
Add-Type -TypeDefinition $code
function Rms-De([double[][]]$Canaux) {
    # Des échantillons entrelacés comme WASAPI : somme et carrés par canal, puis RmsSansContinu.
    $k = $Canaux.Count; $s = New-Object double[] $k; $c = New-Object double[] $k; $n = 0
    for ($i = 0; $i -lt $Canaux[0].Count; $i++) { for ($j = 0; $j -lt $k; $j++) { $v = $Canaux[$j][$i]; $s[$j] += $v; $c[$j] += $v * $v; $n++ } }
    return [VisionSon.Micro]::RmsSansContinu($s, $c, $n)
}
$sinus = @(0..4799 | ForEach-Object { 0.01 * [math]::Sin(2 * [math]::PI * 440 * $_ / 48000) })
Verifier 'sinus seul : RMS = amplitude / racine de 2' (Rms-De @(,[double[]]$sinus)) (0.01 / [math]::Sqrt(2))
Verifier 'sinus + continu 0,2 : le continu ne compte pas' ([math]::Round((Rms-De @(,[double[]]@($sinus | ForEach-Object { $_ + 0.2 }))), 6)) ([math]::Round(0.01 / [math]::Sqrt(2), 6))
Verifier 'continu seul : silence numérique (0)' (Rms-De @(,[double[]]@(1..4800 | ForEach-Object { 0.3 }))) 0
Verifier 'zéros : silence numérique (0)' (Rms-De @(,[double[]]@(1..4800 | ForEach-Object { 0.0 }))) 0
$g = [double[]]@($sinus | ForEach-Object { $_ - 0.1 }); $d = [double[]]@($sinus | ForEach-Object { $_ + 0.25 })
Verifier 'deux canaux, continus différents : chacun retiré' ([math]::Round((Rms-De @($g, $d)), 6)) ([math]::Round(0.01 / [math]::Sqrt(2), 6))

# 8. (1.93.5) Chaque micro : son état et son niveau sur la fenêtre, y compris ceux exclus de la moyenne.
$figes = @{}; $vus = @{}; $par = @{}
$micros = @((Faux-Micro 'a' @(0.01, 0.01)), (Faux-Micro 'm' @(0.5, 0.5) $true), (Faux-Micro 'z' @(0.0, 0.0)), (Faux-Micro 'r' @()))
for ($k = 0; $k -lt 2; $k++) {
    [void](Mesurer-Tranche -Micros $micros -Figes $figes -VusActifs $vus -Maintenant $t0.AddMilliseconds(100 * $k) -Fige 10 -ParMicro $par)
}
$det = @(Detail-Micros -Micros $micros -ParMicro $par -Calibration 0)
$par_nom = @{}; foreach ($x in $det) { $par_nom[$x.nom] = $x }
Verifier 'détail : un élément par micro' $det.Count 4
if ($par_nom['Micro a'].etat -ne 'actif' -or $par_nom['Micro m'].etat -ne 'muet' -or $par_nom['Micro z'].etat -ne 'silence' -or $par_nom['Micro r'].etat -ne 'sans_donnees') {
    Write-Output "ÉCART états : $(($det | ForEach-Object { "$($_.nom)=$($_.etat)" }) -join ', ')"; $ecarts++
} else { Write-Output 'ok   détail : actif, muet, silence, sans_donnees' }
Verifier 'détail : niveau du micro actif (-40 dBFS = 60 relatif)' $par_nom['Micro a'].niveau 60
if ($par_nom['Micro m'].PSObject.Properties['niveau']) { Write-Output 'ÉCART : un micro muet a un niveau'; $ecarts++ } else { Write-Output 'ok   détail : pas de niveau pour un micro muet' }
$det2 = @(Detail-Micros -Micros @($micros[0]) -ParMicro $par -Calibration 0)
Verifier 'détail : la fenêtre repart de zéro et oublie les micros fermés' $det2.Count 1
if ($det2[0].PSObject.Properties['niveau']) { Write-Output 'ÉCART : niveau gardé d''une fenêtre à l''autre'; $ecarts++ } else { Write-Output 'ok   détail : fenêtre remise à zéro' }
# Un micro figé est exclu de la moyenne mais garde son niveau dans le détail.
$figes = @{}; $vus = @{}; $par = @{}
$fige = Faux-Micro 'f' (@(0.2) * 200)
for ($k = 0; $k -le 110; $k++) {
    [void](Mesurer-Tranche -Micros @($fige) -Figes $figes -VusActifs $vus -Maintenant $t0.AddMilliseconds(100 * $k) -Fige 10 -ParMicro $par)
}
$det = @(Detail-Micros -Micros @($fige) -ParMicro $par -Calibration 0)
if ($det[0].etat -ne 'fige') { Write-Output "ÉCART : état $($det[0].etat) au lieu de fige"; $ecarts++ } else { Write-Output 'ok   détail : micro figé signalé' }
Verifier 'détail : niveau du micro figé (-14 dBFS)' $det[0].niveau ([math]::Round(20 * [math]::Log10(0.2) + 100, 1))

# 9. (1.93.5) Un micro sous le plancher (-90 dBFS par défaut) ne capte rien : il compte comme éteint.
$figes = @{}; $vus = @{}; $par = @{}
$bas = Faux-Micro 'b' @(0.00001)     # -100 dBFS
$bon = Faux-Micro 'a' @(0.01)        # -40 dBFS
$r = Mesurer-Tranche -Micros @($bas, $bon) -Figes $figes -VusActifs $vus -Maintenant $t0 -Fige 10 -ParMicro $par
Verifier 'micro sous le plancher exclu de la moyenne' $r 0.01
Verifier 'micro sous le plancher absent de la liste' $vus.Count 1
$det = @(Detail-Micros -Micros @($bas, $bon) -ParMicro $par -Calibration 0)
$b = $det | Where-Object { $_.nom -eq 'Micro b' }
if ($b.etat -ne 'sous_plancher') { Write-Output "ÉCART : état $($b.etat) au lieu de sous_plancher"; $ecarts++ } else { Write-Output 'ok   détail : micro sous le plancher signalé' }
Verifier 'détail : son niveau reste visible (-100 dBFS = 0 relatif)' $b.niveau 0
# Seulement des micros sous le plancher (cas du PC d'Sam, -99 dBFS) : aucun niveau, capteur indisponible.
$figes = @{}; $vus = @{}
$r = Mesurer-Tranche -Micros @((Faux-Micro 'c' @(0.00001)), (Faux-Micro 'd' @(0.00002))) -Figes $figes -VusActifs $vus -Maintenant $t0 -Fige 10
Verifier 'tous sous le plancher : pas de niveau' $r -1
$pris = @(Micros-Pris -Micros @() -Figes $figes -VusActifs $vus -Maintenant $t0)
Verifier 'tous sous le plancher : aucun micro pris' $pris.Count 0
# Le plancher est réglable : à -110 dBFS, le même micro compte.
$figes = @{}; $vus = @{}
$r = Mesurer-Tranche -Micros @((Faux-Micro 'c' @(0.00001))) -Figes $figes -VusActifs $vus -Maintenant $t0 -Fige 10 -PlancherDbfs -110
Verifier 'plancher réglé à -110 : le micro à -100 compte' $r 0.00001
# Juste au-dessus du plancher : compte.
$figes = @{}; $vus = @{}
$r = Mesurer-Tranche -Micros @((Faux-Micro 'e' @(0.0000317))) -Figes $figes -VusActifs $vus -Maintenant $t0 -Fige 10
Verifier 'à -89,98 dBFS : compté' $r 0.0000317

# 10. (1.93.5) Un micro dont le nom contient un des micros exclus (ex. un casque collé à la bouche) est ignoré.
$figes = @{}; $vus = @{}; $par = @{}
$casque = Faux-Micro 'k' @(0.5); $casque.Nom = 'Microphone sur casque (3- CORSAIR VOID ELITE)'
$piece = Faux-Micro 'p' @(0.01)
$r = Mesurer-Tranche -Micros @($casque, $piece) -Figes $figes -VusActifs $vus -Maintenant $t0 -Fige 10 -ParMicro $par -Exclus @('corsair void')
Verifier 'micro exclu par son nom (sans tenir compte de la casse)' $r 0.01
$det = @(Detail-Micros -Micros @($casque, $piece) -ParMicro $par -Calibration 0)
$k = $det | Where-Object { $_.nom -like '*CORSAIR*' }
if ($k.etat -ne 'exclu') { Write-Output "ÉCART : état $($k.etat) au lieu de exclu"; $ecarts++ } else { Write-Output 'ok   détail : micro exclu signalé' }
Verifier 'détail : niveau du micro exclu visible (-6 dBFS)' $k.niveau ([math]::Round(20 * [math]::Log10(0.5) + 100, 1))
$figes = @{}; $vus = @{}
$r = Mesurer-Tranche -Micros @((Faux-Micro 'k2' @(0.5))) -Figes $figes -VusActifs $vus -Maintenant $t0 -Fige 10 -Exclus @('Micro k2')
Verifier 'seul micro exclu : pas de niveau' $r -1

if ($ecarts) { Write-Output "$ecarts écart(s)."; exit 1 }
Write-Output 'Tout est conforme.'
exit 0
