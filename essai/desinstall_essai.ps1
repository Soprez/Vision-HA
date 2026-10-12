<#
    Essai de la desinstallation par l'agent, en mode strict (comme l'agent,
    qui tourne sous Set-StrictMode -Version Latest), sans droits
    d'administrateur : le « logiciel » est une fausse entree posee dans
    HKCU, et son desinstalleur se contente de retirer cette entree.

    powershell -NoProfile -ExecutionPolicy Bypass -File desinstall_essai.ps1 -Agent <ha-parental-agent.ps1>
#>
param([Parameter(Mandatory)][string]$Agent)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$bac = Join-Path $env:TEMP ('vision-desinstall-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Path $bac | Out-Null
$script:Racine = Join-Path $bac 'HAParental'
New-Item -ItemType Directory -Path $script:Racine | Out-Null
$script:Journal = @()
$script:DernierInventaire = Get-Date

$erreurs = $null
$arbre = [System.Management.Automation.Language.Parser]::ParseFile($Agent, [ref]$null, [ref]$erreurs)
if ($erreurs.Count) { throw "L'agent ne se lit pas : $($erreurs[0].Message)" }
$voulues = 'Get-PropOrDefault', 'Get-SignatureFichier', 'Test-Protege', 'Get-CheminCommande',
    'Get-MaintenantUnix', 'Read-JournalDesinstall', 'Save-JournalDesinstall', 'Get-ProduitsInstalles',
    'Get-DossierSur', 'Find-ProduitPour', 'Get-RaisonIntouchable', 'Test-Nsis',
    'Get-CommandeDesinstallation', 'Start-Desinstallation', 'Update-Desinstallation',
    'Invoke-Desinstallations', 'Get-ComptesRendusDesinstall'
foreach ($f in $arbre.FindAll({ param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst] }, $false)) {
    if ($voulues -contains $f.Name) { . ([scriptblock]::Create($f.Extent.Text)) }
}
$variables = 'Desinstall', 'ClesDesinstall', 'EditeursPilotes', 'MotsAntiTriche', 'ProcessusProteges', 'SigParChemin'
foreach ($a in $arbre.FindAll({ param($n) $n -is [System.Management.Automation.Language.AssignmentStatementAst] }, $false)) {
    $gauche = $a.Left.Extent.Text
    foreach ($v in $variables) {
        if ($gauche -like ('$script:' + $v + '*')) { . ([scriptblock]::Create($a.Extent.Text)); break }
    }
}
function Write-Journal { param([string]$Message, [string]$Niveau = 'INFO') $script:Journal += "[$Niveau] $Message" }

$script:Rates = 0
function Verifier {
    param([string]$Quoi, $Obtenu, $Attendu)
    if ("$Obtenu" -eq "$Attendu") { Write-Output "ok    $Quoi" }
    else { $script:Rates++; Write-Output "RATE  $Quoi : obtenu « $Obtenu », attendu « $Attendu »" }
}

# --- 1. Dossiers trop larges
Verifier 'dossier : Program Files seul'  (Get-DossierSur 'C:\Program Files') ''
Verifier 'dossier : Program Files\Truc'  (Get-DossierSur 'C:\Program Files\Truc\') 'c:\program files\truc\'
Verifier 'dossier : C:\ seul'            (Get-DossierSur 'C:\') ''
Verifier 'dossier : Windows\System32'    (Get-DossierSur (Join-Path $env:windir 'System32')) ''
Verifier 'dossier : profil seul'         (Get-DossierSur 'C:\Users\bob') ''
Verifier 'dossier : AppData\Local'       (Get-DossierSur 'C:\Users\bob\AppData\Local') ''
Verifier 'dossier : dans le profil'      (Get-DossierSur 'C:\Users\bob\AppData\Local\Truc') 'c:\users\bob\appdata\local\truc\'
Verifier 'dossier : D:\Roblox'           (Get-DossierSur 'D:\Roblox') 'd:\roblox\'
Verifier 'dossier : relatif'             (Get-DossierSur 'Truc\Bidule') ''
Verifier 'dossier : vide'                (Get-DossierSur '') ''

# --- 2. Retrouver le logiciel d'un fichier
$faux = @(
    @{ cle = 'k1'; code = 'Suite'; nom = 'Suite'; editeur = 'Acme'; dossier = 'C:\Program Files\Acme'; commande = ''; silencieux = ''; icone = ''; composant = $false; msi = $false },
    @{ cle = 'k2'; code = 'Outil'; nom = 'Outil Acme'; editeur = 'Acme'; dossier = 'C:\Program Files\Acme\Outil'; commande = ''; silencieux = ''; icone = ''; composant = $false; msi = $false },
    @{ cle = 'k3'; code = 'Large'; nom = 'Mal declare'; editeur = 'X'; dossier = 'C:\Program Files'; commande = ''; silencieux = ''; icone = ''; composant = $false; msi = $false },
    @{ cle = 'k4'; code = 'SansDossier'; nom = 'Sans dossier'; editeur = 'Y'; dossier = ''; commande = '"C:\Program Files\Ygrec\unins000.exe" /x'; silencieux = ''; icone = ''; composant = $false; msi = $false }
)
Verifier 'produit : le plus precis'   (Find-ProduitPour 'C:\Program Files\Acme\Outil\bin\o.exe' $faux).nom 'Outil Acme'
Verifier 'produit : la suite'         (Find-ProduitPour 'C:\Program Files\Acme\autre.exe' $faux).nom 'Suite'
Verifier 'produit : dossier large'    ($null -eq (Find-ProduitPour 'C:\Program Files\Inconnu\x.exe' $faux)) 'True'
Verifier 'produit : par desinstalleur' (Find-ProduitPour 'C:\Program Files\Ygrec\y.exe' $faux).nom 'Sans dossier'
$suite = @(
    @{ cle = 's1'; code = 'A'; nom = 'Suite Photo'; editeur = 'Ed'; dossier = 'C:\Program Files\Ed'; commande = ''; silencieux = ''; icone = ''; composant = $false; msi = $false },
    @{ cle = 's2'; code = 'B'; nom = 'Suite Video'; editeur = 'Ed'; dossier = 'C:\Program Files\Ed'; commande = ''; silencieux = ''; icone = ''; composant = $false; msi = $false },
    @{ cle = 's3'; code = 'C'; nom = 'Suite Son'; editeur = 'Ed'; dossier = 'C:\Program Files\Ed\Son'; commande = ''; silencieux = ''; icone = ''; composant = $false; msi = $false }
)
Verifier 'produit : dossier partage, on ne choisit pas' ($null -eq (Find-ProduitPour 'C:\Program Files\Ed\Video\v.exe' $suite)) 'True'
Verifier 'produit : dossier propre dans la suite' (Find-ProduitPour 'C:\Program Files\Ed\Son\s.exe' $suite).nom 'Suite Son'
Verifier 'produit : voisin de nom'    ($null -eq (Find-ProduitPour 'C:\Program Files\AcmeBis\x.exe' $faux)) 'True'

# --- 3. Ce que l'agent refuse
$vide = Join-Path $bac 'vide.exe'; Set-Content -LiteralPath $vide -Value 'x'
function Faux { param($Nom, $Editeur, $Dossier = 'C:\Program Files\Z', [bool]$Composant = $false)
    return @{ cle = 'k'; code = 'c'; nom = $Nom; editeur = $Editeur; dossier = $Dossier; commande = ''; silencieux = ''; icone = ''; composant = $Composant; msi = $false } }
Verifier 'refus : logiciel ordinaire' (Get-RaisonIntouchable (Faux 'Norton 360' 'Gen Digital Inc.') $vide 'nortonui') ''
Verifier 'refus : Microsoft'   ((Get-RaisonIntouchable (Faux 'Microsoft Edge' 'Microsoft Corporation') $vide 'msedge') -ne '') 'True'
Verifier 'refus : pilote'      ((Get-RaisonIntouchable (Faux 'NVIDIA Pilote graphique' 'NVIDIA Corporation') $vide 'nv') -ne '') 'True'
Verifier 'refus : anti-triche' ((Get-RaisonIntouchable (Faux 'Riot Vanguard' 'Riot Games, Inc.') $vide 'vgc') -ne '') 'True'
Verifier 'refus : composant'   ((Get-RaisonIntouchable (Faux 'Truc Helper' 'Truc' 'C:\Program Files\Z' $true) $vide 'h') -ne '') 'True'
Verifier 'refus : agent Vision' ((Get-RaisonIntouchable (Faux 'Vision' 'Vision') $vide 'v') -ne '') 'True'
Verifier 'refus : Windows'     ((Get-RaisonIntouchable (Faux 'Truc' 'Truc') (Join-Path $env:windir 'System32\notepad.exe') 'notepad') -ne '') 'True'
Verifier 'refus : processus protege' ((Get-RaisonIntouchable (Faux 'Truc' 'Truc') $vide 'explorer') -ne '') 'True'

# --- 4. La commande silencieuse
$inno = Join-Path $bac 'unins000.exe'; Set-Content -LiteralPath $inno -Value 'x'
$nsis = Join-Path $bac 'uninstall.exe'; Set-Content -LiteralPath $nsis -Value 'MZ....Nullsoft Install System'
$autre = Join-Path $bac 'remove.exe'; Set-Content -LiteralPath $autre -Value 'MZ....'
function Cmd { param($Commande, $Silencieux = '', $Code = 'Truc', [bool]$Msi = $false)
    return @{ cle = 'k'; code = $Code; nom = 'T'; editeur = 'T'; dossier = ''; commande = $Commande; silencieux = $Silencieux; icone = ''; composant = $false; msi = $Msi } }
$c = Get-CommandeDesinstallation (Cmd 'MsiExec.exe /I{11111111-2222-3333-4444-555555555555}' '' '{11111111-2222-3333-4444-555555555555}' $true)
Verifier 'commande : MSI' $c.arguments '/x {11111111-2222-3333-4444-555555555555} /qn /norestart'
$c = Get-CommandeDesinstallation (Cmd "`"$inno`"")
Verifier 'commande : Inno' "$($c.mode) $($c.arguments)" 'inno /VERYSILENT /SUPPRESSMSGBOXES /NORESTART'
$c = Get-CommandeDesinstallation (Cmd "`"$nsis`"")
Verifier 'commande : NSIS' "$($c.mode) $($c.arguments)" 'nsis /S'
Verifier 'commande : inconnue, on ne devine pas' ($null -eq (Get-CommandeDesinstallation (Cmd "`"$autre`" /remove"))) 'True'
Verifier 'commande : fichier absent' ($null -eq (Get-CommandeDesinstallation (Cmd '"C:\nexistepas\unins000.exe"'))) 'True'
$c = Get-CommandeDesinstallation (Cmd "`"$autre`"" "`"$autre`" /quiet /x")
Verifier 'commande : declaree silencieuse' "$($c.mode) $($c.arguments)" 'declare /quiet /x'

# --- 5. La vraie liste de Windows se lit
$vrais = @(Get-ProduitsInstalles)
Verifier 'liste de Windows lue' ($vrais.Count -gt 0) 'True'

# --- 6. De bout en bout, sur une fausse entree dans HKCU
$base = 'HKCU:\Software\VisionEssai\Uninstall'
$script:ClesDesinstall = @($base)
$reg = Join-Path $env:windir 'System32\reg.exe'
$cmd = Join-Path $env:windir 'System32\cmd.exe'
function Poser { param($Code, $Nom, $Editeur, $Dossier, $Silencieux)
    $k = Join-Path $base $Code
    New-Item -Path $k -Force | Out-Null
    Set-ItemProperty -LiteralPath $k -Name DisplayName -Value $Nom
    Set-ItemProperty -LiteralPath $k -Name Publisher -Value $Editeur
    Set-ItemProperty -LiteralPath $k -Name InstallLocation -Value $Dossier
    Set-ItemProperty -LiteralPath $k -Name UninstallString -Value $Silencieux
    Set-ItemProperty -LiteralPath $k -Name QuietUninstallString -Value $Silencieux
}
function Consignes { param($Liste) return ([pscustomobject]@{ desinstaller = @($Liste) }) }
function Demande { param($Jeton, $Chemin, $Exe)
    return [pscustomobject]@{ cle = "processus|$Exe"; jeton = $Jeton; genre = 'processus'; id = $Exe; nom = $Exe; exe = $Exe; chemin = $Chemin; editeur = '' } }
try {
    $d1 = Join-Path $bac 'Faux'; New-Item -ItemType Directory -Path $d1 | Out-Null
    $e1 = Join-Path $d1 'faux.exe'; Set-Content -LiteralPath $e1 -Value 'x'
    Poser 'Faux' 'Faux Logiciel' 'Editeur Faux' $d1 "`"$reg`" delete `"HKCU\Software\VisionEssai\Uninstall\Faux`" /f"
    $d2 = Join-Path $bac 'Tetu'; New-Item -ItemType Directory -Path $d2 | Out-Null
    $e2 = Join-Path $d2 'tetu.exe'; Set-Content -LiteralPath $e2 -Value 'x'
    Poser 'Tetu' 'Logiciel Tetu' 'Editeur Tetu' $d2 "`"$cmd`" /c exit 3"
    $d3 = Join-Path $bac 'Pilote'; New-Item -ItemType Directory -Path $d3 | Out-Null
    $e3 = Join-Path $d3 'p.exe'; Set-Content -LiteralPath $e3 -Value 'x'
    Poser 'Pilote' 'Carte graphique' 'NVIDIA Corporation' $d3 "`"$reg`" delete `"HKCU\Software\VisionEssai\Uninstall\Pilote`" /f"

    # Rien de demande, rien de journalise : aucun fichier cree.
    Invoke-Desinstallations (Consignes @())
    Verifier 'bout : sans consigne, pas de journal' (Test-Path -LiteralPath $script:DesinstallJournal) 'False'
    Verifier 'bout : sans journal, pas de compte rendu' @(Get-ComptesRendusDesinstall).Count 0

    $tout = Consignes @((Demande 'aaa111' $e1 'faux'), (Demande 'bbb222' $e3 'p'), (Demande 'ccc333' (Join-Path $bac 'Nulle\part.exe') 'part'))
    Invoke-Desinstallations $tout
    $j = Read-JournalDesinstall
    Verifier 'bout : lance' $j['aaa111'].etat 'en_cours'
    Verifier 'bout : une seule a la fois' $j.ContainsKey('bbb222') 'False'
    Start-Sleep -Seconds 3
    $script:DernierInventaire = Get-Date
    Invoke-Desinstallations $tout
    $j = Read-JournalDesinstall
    Verifier 'bout : fait' $j['aaa111'].etat 'fait'
    Verifier 'bout : entree retiree de Windows' (Test-Path -LiteralPath (Join-Path $base 'Faux')) 'False'
    Verifier 'bout : inventaire redemande' ($script:DernierInventaire -eq [datetime]::MinValue) 'True'
    Verifier 'bout : pilote refuse' $j['bbb222'].etat 'impossible'
    Verifier 'bout : pilote toujours la' (Test-Path -LiteralPath (Join-Path $base 'Pilote')) 'True'
    Verifier 'bout : introuvable' $j['ccc333'].etat 'impossible'
    $r = @(Get-ComptesRendusDesinstall)
    Verifier 'bout : trois comptes rendus' $r.Count 3
    Verifier 'bout : compte rendu serialisable' (($r | ConvertTo-Json -Depth 4 -Compress).Length -gt 10) 'True'

    # Home Assistant renvoie la meme consigne : rien n'est relance.
    Poser 'Faux' 'Faux Logiciel' 'Editeur Faux' $d1 "`"$reg`" delete `"HKCU\Software\VisionEssai\Uninstall\Faux`" /f"
    Invoke-Desinstallations $tout
    Start-Sleep -Seconds 2
    Verifier 'bout : pas de seconde fois' (Test-Path -LiteralPath (Join-Path $base 'Faux')) 'True'

    # Un desinstalleur qui sort sans rien retirer : echec, apres le delai.
    $script:DesinstallGraceSecondes = 2
    $tetu = Consignes @((Demande 'ddd444' $e2 'tetu'))
    Invoke-Desinstallations $tetu
    Start-Sleep -Seconds 1
    Invoke-Desinstallations $tetu
    Verifier 'bout : tetu attend le delai' (Read-JournalDesinstall)['ddd444'].etat 'en_cours'
    Start-Sleep -Seconds 3
    Invoke-Desinstallations $tetu
    Verifier 'bout : tetu en echec' (Read-JournalDesinstall)['ddd444'].etat 'echec'

    # Un desinstalleur qui ne finit jamais : coupe au bout du temps permis.
    Poser 'Long' 'Logiciel Long' 'Editeur Long' $d2 "`"$cmd`" /c ping -n 60 127.0.0.1"
    $script:DesinstallMaxSecondes = 2
    $long = Consignes @((Demande 'eee555' $e2 'tetu'))
    Remove-Item -LiteralPath (Join-Path $base 'Tetu') -Recurse -Force
    Invoke-Desinstallations $long
    $pidLong = [int](Read-JournalDesinstall)['eee555'].processus
    Start-Sleep -Seconds 3
    Invoke-Desinstallations $long
    Verifier 'bout : trop long, echec' (Read-JournalDesinstall)['eee555'].etat 'echec'
    Start-Sleep -Milliseconds 500
    Verifier 'bout : trop long, coupe' ($null -eq (Get-Process -Id $pidLong -ErrorAction SilentlyContinue)) 'True'

    # Un logiciel designe par son nom, depuis la liste des logiciels installes.
    $script:DesinstallMaxSecondes = 900; $script:DesinstallGraceSecondes = 180
    Remove-Item -LiteralPath $script:DesinstallJournal -Force -ErrorAction SilentlyContinue
    Poser 'ParNom' 'Jeu Par Nom' 'Petit Studio' '' "`"$reg`" delete `"HKCU\Software\VisionEssai\Uninstall\ParNom`" /f"
    $parNom = Consignes @(
        [pscustomobject]@{ cle = 'logiciel|jeu par nom'; jeton = 'fff666'; genre = 'logiciel'; id = 'jeu par nom'; nom = 'Jeu Par Nom'; exe = ''; chemin = ''; editeur = 'Petit Studio' },
        [pscustomobject]@{ cle = 'logiciel|inconnu au bataillon'; jeton = 'ggg777'; genre = 'logiciel'; id = 'inconnu au bataillon'; nom = 'Inconnu'; exe = ''; chemin = ''; editeur = '' },
        [pscustomobject]@{ cle = 'logiciel|carte graphique'; jeton = 'hhh888'; genre = 'logiciel'; id = 'carte graphique'; nom = 'Carte graphique'; exe = ''; chemin = ''; editeur = 'NVIDIA Corporation' })
    Invoke-Desinstallations $parNom
    Start-Sleep -Seconds 3
    Invoke-Desinstallations $parNom
    Invoke-Desinstallations $parNom
    $j = Read-JournalDesinstall
    Verifier 'par nom : fait' $j['fff666'].etat 'fait'
    Verifier 'par nom : inconnu' $j['ggg777'].etat 'impossible'
    Verifier 'par nom : pilote refuse' $j['hhh888'].etat 'impossible'
    Verifier 'par nom : pilote toujours la' (Test-Path -LiteralPath (Join-Path $base 'Pilote')) 'True'

    # Journal abime : on repart de rien, sans lever.
    Set-Content -LiteralPath $script:DesinstallJournal -Value '{pas du json'
    Verifier 'bout : journal abime' (Read-JournalDesinstall).Count 0
} finally {
    Remove-Item -Path 'HKCU:\Software\VisionEssai' -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $bac -Recurse -Force -ErrorAction SilentlyContinue
}
Write-Output ("{0} rate(s)" -f $script:Rates)
Write-Output '--- journal'
$script:Journal | ForEach-Object { Write-Output $_ }
