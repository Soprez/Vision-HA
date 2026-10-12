"""Fabrique le banc d'essai de la fenêtre PC : le script de la fenêtre, sorti de l'agent,
qui se photographie vue par vue hors de l'écran puis sort. À lancer sur un PC Windows :
powershell -STA -File tray_banc.ps1 -Dossier <dossier avec maison.json, etat.json, vision.png>"""
import sys
s = open('custom_components/pc_parental/client/ha-parental-agent.ps1', encoding='utf-8-sig').read()
a = s.index("$script:TraySource = @'\n") + len("$script:TraySource = @'\n")
t = s[a:s.index("\n'@\n", a)].replace("'Local\\VisionTray'", "'Local\\VisionTrayEssai'")
fin = "if ($Depart) { $script:Page = $Depart; Montrer }\n\n[System.Windows.Forms.Application]::Run()"
assert t.count(fin) == 1
banc = r'''
# ---- Banc d'essai : la fenêtre hors de l'écran, chaque vue photographiée, puis on sort.
$tray.Visible = $false; $script:Deverrouille = $true
$script:W.WindowStartupLocation = 'Manual'; $script:W.Left = -4000; $script:W.Top = 0; $script:W.ShowActivated = $false
$script:Page = 'maison'; Rafraichir; $script:W.Show()
function Photo([string]$nom) {
    $script:W.Dispatcher.Invoke([action]{}, [System.Windows.Threading.DispatcherPriority]::ContextIdle)
    $rtb = New-Object System.Windows.Media.Imaging.RenderTargetBitmap([int]$script:W.ActualWidth, [int]$script:W.ActualHeight, 96, 96, [System.Windows.Media.PixelFormats]::Pbgra32)
    $rtb.Render($script:W.Content)
    $enc = New-Object System.Windows.Media.Imaging.PngBitmapEncoder
    $enc.Frames.Add([System.Windows.Media.Imaging.BitmapFrame]::Create($rtb))
    $fs = [System.IO.File]::Create((Join-Path $script:Dossier ("photo-$nom.png"))); $enc.Save($fs); $fs.Close()
}
$journal = Join-Path $script:Dossier 'essai.log'
'debut' | Set-Content $journal
try {
    Photo '1-actifs'
    $script:Oeil.rx = 0.7; $script:Oeil.ry = -0.4; OeilDessiner 0.55; Photo '1b-clin'
    $maison = Lire 'maison.json'
    $gr = @(GroupesMaison $maison)
    ('groupes: ' + (($gr | ForEach-Object { $_.prenom + '=' + $_.appareils.Count }) -join ', ')) | Add-Content $journal
    $enfant = $gr | Where-Object { $_.appareils.Count -gt 1 -and $_.personne -and -not [bool](Prop $_.personne 'parent' $false) } | Select-Object -First 1
    $script:Vue = @{ type = 'personne'; personne = [string]$enfant.entite }; Rafraichir; Photo '2-fiche'
    ChoisirDuree 30 $false; Photo '3-materiel'
    ChoisirDuree 0 $true; Photo '4-autre'
    ('choix: ' + (($script:Vue.temps.choix.Keys) -join ',')) | Add-Content $journal
    $par = $gr | Where-Object { $_.personne -and [bool](Prop $_.personne 'parent' $false) } | Select-Object -First 1
    $script:Vue = @{ type = 'personne'; personne = [string]$par.entite }; Rafraichir; Photo '5-parent'
    $tv = @($par.appareils | Where-Object { [bool](Prop $_ 'partage_tous' $false) })[0]
    $script:Vue = @{ type = 'partage'; appareil = $tv; retour = @{ type = 'personne'; personne = [string]$par.entite } }; Rafraichir; Photo '6-partage'
    $script:Vue = $script:Vue.retour; Rafraichir; Photo '7-retour'
    # Un refus en cours sur la première ligne fermée : le bouton doit se fermer et dire jusqu'à quand.
    $f = Lire 'fermes.json'
    $lignes = @(Prop $f 'apps' @()) + @(Prop $f 'sites' @())
    if ($lignes.Count -gt 0) {
        $genre = if (@(Prop $f 'apps' @()).Count -gt 0) { 'apps' } else { 'sites' }
        $f | Add-Member -NotePropertyName refus -NotePropertyValue @(@{ genre = $genre; nom = [string](Prop $lignes[0] 'nom'); jusqua = ([DateTimeOffset]::UtcNow.ToUnixTimeSeconds() + 1800) }) -Force
        ($f | ConvertTo-Json -Depth 5 -Compress) | Set-Content (Join-Path $script:Dossier 'fermes.json') -Encoding UTF8
        ('refus posé sur ' + [string](Prop $lignes[0] 'nom') + ' : ' + (RefusJusqua (Lire 'fermes.json') $genre ([string](Prop $lignes[0] 'nom')))) | Add-Content $journal
    }
    $script:Vue = $null; $script:Page = 'moi'; Rafraichir; Photo '8-moi'
    $script:Page = 'maison'; $script:Vue = @{ type = 'ajout'; personne = [string]$par.entite; retour = @{ type = 'personne'; personne = [string]$par.entite } }; Rafraichir; Photo '9-ajout'
    $script:Vue = $null; $script:Page = 'reglages'; Rafraichir; Photo '10-reglages'
    # L'œil tourne-t-il ? On laisse passer deux secondes de boucle et on compte les redessins.
    $n = 0; $fin = (Get-Date).AddSeconds(2)
    while ((Get-Date) -lt $fin) { $script:W.Dispatcher.Invoke([action]{}, [System.Windows.Threading.DispatcherPriority]::Background); Start-Sleep -Milliseconds 20; $n++ }
    ('oeil: minuteur actif=' + $script:OeilTic.IsEnabled + ' regard=' + [Math]::Round($script:Oeil.rx, 2)) | Add-Content $journal
    ('icone: ' + $tray.Icon.Width + 'x' + $tray.Icon.Height) | Add-Content $journal
    # ---- 1.57 : le bouton « Ce qui est fermé », le planning modifiable, les limites, les logiciels.
    function Boutons($racine) {
        $trouves = @()
        if ($racine -is [System.Windows.Controls.Button]) { $trouves += $racine }
        elseif ($racine -is [System.Windows.Controls.Panel]) { foreach ($e in $racine.Children) { $trouves += @(Boutons $e) } }
        elseif ($racine -is [System.Windows.Controls.Border]) { $trouves += @(Boutons $racine.Child) }
        elseif ($racine -is [System.Windows.Controls.ContentControl]) { $trouves += @(Boutons $racine.Content) }
        return $trouves
    }
    function Cliquer([string]$debut) {
        $b = @(Boutons $Contenu | Where-Object { ([string]$_.Content).StartsWith($debut) })[0]
        if ($null -eq $b) { ("  bouton introuvable : $debut") | Add-Content $journal; return $false }
        $b.RaiseEvent((New-Object System.Windows.RoutedEventArgs([System.Windows.Controls.Primitives.ButtonBase]::ClickEvent))); return $true
    }
    function Deposees() {
        $sortie = @()
        foreach ($f in @(Get-ChildItem -Path $script:Dossier -Filter 'action-*.json' | Sort-Object Name)) { $sortie += (Get-Content $f.FullName -Raw -Encoding UTF8).Trim(); Remove-Item $f.FullName -Force }
        return $sortie
    }
    [void](Deposees)
    $script:Page = 'maison'; $script:Vue = @{ type = 'personne'; personne = [string]$enfant.entite }; Rafraichir
    ('boutons de la fiche : ' + ((Boutons $Contenu | ForEach-Object { [string]$_.Content } | Select-Object -Unique) -join ' | ')) | Add-Content $journal
    Photo '11-fiche'
    $propre = @($enfant.appareils | Where-Object { [string](Prop $_ 'personne') -eq [string]$enfant.entite -and @(Prop $_ 'plages' @()).Count -gt 0 })[0]
    $ici = @{ type = 'personne'; personne = [string]$enfant.entite }
    $script:Vue = @{ type = 'planning'; appareil = $propre; retour = $ici }; Rafraichir; Photo '12-planning'
    [void](Cliquer 'Supprimer'); Photo '12b-supprimer-question'
    ('après un clic sur Supprimer : ' + @(Deposees).Count + ' action (attendu 0)') | Add-Content $journal
    [void](Cliquer 'Modifier'); Photo '13-plage-modifier'
    [void](Cliquer 'Enregistrer')
    ('modifier sans rien changer : ' + ((Deposees) -join ' ;; ')) | Add-Content $journal
    $script:Vue = @{ type = 'plage'; appareil = $propre; plage = $null; retour = @{ type = 'planning'; appareil = $propre; retour = $ici } }; Rafraichir; Photo '14-plage-neuve'
    [void](Cliquer 'Ajouter la plage'); Photo '14b-plage-faute'
    ('plage sans nom : ' + @(Deposees).Count + ' action (attendu 0), message : ' + [string]$script:Vue.dit) | Add-Content $journal
    $script:PlageEdit.nom.Text = 'Devoirs'; $script:PlageEdit.debut.Text = '17h30'; $script:PlageEdit.fin.Text = '19:00'
    [void](Cliquer 'Tout l'); [void](Cliquer 'Sam'); [void](Cliquer 'Dim'); [void](Cliquer 'Jeux')
    Photo '14c-plage-remplie'
    [void](Cliquer 'Ajouter la plage')
    ('plage créée : ' + ((Deposees) -join ' ;; ')) | Add-Content $journal
    ('retour au planning : ' + [string]$script:Vue.type + ' / ' + [string]$script:Vue.dit) | Add-Content $journal
    $script:Vue = @{ type = 'temps'; appareil = $propre; retour = $ici }; Rafraichir; Photo '15-temps-limites'
    [void](Cliquer '+15 min')
    ('limite +15 : ' + ((Deposees) -join ' ;; ')) | Add-Content $journal
    $candidats = @(Boutons $Contenu | ForEach-Object { [string]$_.Content })
    ('applis proposées à limiter : ' + (($candidats | Select-Object -Last 6) -join ' | ')) | Add-Content $journal
    $pcEnfant = @($enfant.appareils | Where-Object { $null -ne (Prop $_ 'logiciels') })[0]
    $script:Vue = @{ type = 'logiciels'; appareil = $pcEnfant; retour = $ici }; Rafraichir; Photo '16-logiciels'
    [void](Cliquer 'Désinstaller'); Photo '16b-logiciels-question'
    ('après un clic sur Désinstaller : ' + @(Deposees).Count + ' action (attendu 0)') | Add-Content $journal
    [void](Cliquer 'Désinstaller définitivement')
    ('désinstaller confirmé : ' + ((Deposees) -join ' ;; ')) | Add-Content $journal
    [void](Cliquer 'Bloquer')
    ('bloquer : ' + ((Deposees) -join ' ;; ')) | Add-Content $journal
    'fin sans erreur' | Add-Content $journal
} catch { ('ERREUR: ' + $_ + ' @ ' + $_.ScriptStackTrace) | Add-Content $journal }
$script:W.Hide(); $tray.Dispose()
exit 0
'''
open(sys.argv[1], 'w', encoding='utf-8-sig', newline='\r\n').write(t.replace(fin, banc))
