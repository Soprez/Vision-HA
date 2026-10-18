<#
.SYNOPSIS
    Agent Vision : supervision des PC pilotée par Home Assistant.

.DESCRIPTION
    Interroge Home Assistant en boucle et applique sa décision :
      - verrouillage de la session (réappliqué si elle est rouverte) ;
      - fermeture des logiciels interdits ;
      - blocage de sites via le fichier hosts.

    Prévu pour tourner en compte SYSTEM, lancé par une tâche planifiée au
    démarrage de la machine. Voir install.ps1.

    En cas de perte du réseau, la dernière décision connue est conservée
    pendant OfflineGraceMinutes, puis le PC est laissé ouvert : une panne
    de Home Assistant ne doit pas condamner l'ordinateur de la famille.
#>

[CmdletBinding()]
param(
    [string]$ConfigPath = "$env:ProgramData\HAParental\config.json"
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$script:Version           = '1.93.3'
$script:Racine            = Split-Path -Parent $ConfigPath
$script:JournalPath       = Join-Path $script:Racine 'agent.log'
$script:EtatPath          = Join-Path $script:Racine 'etat.json'
$script:NoirePath         = Join-Path $script:Racine 'liste-noire.json'
$script:NoireVersion      = ''
$script:NoireConnus       = @{}
$script:NoireHosts        = @()
$script:NoireSignalees    = @{}
$script:DnsPose           = ''
$script:Tentatives        = @()
$script:DerniersBlocages  = @()
$script:AppsInterdites    = @()
$script:BalayageApps      = 10
$script:VeilleApps        = 'Vision-Lancements'
$script:VeilleActive      = $false
$script:VeilleForme       = ''
$script:AlerteApp         = @{}
$script:AvertisDits       = @()
$script:PreavisDits       = @()   # 1.85.0 : seuils de préavis déjà annoncés
$script:AlerteAppRepos    = 30
$script:HostsPath         = "$env:SystemRoot\System32\drivers\etc\hosts"
# Garde-fou : un domaine bloque tres frequente ne doit pas faire enfler le
# fichier hosts sans limite.
$script:MaxSousDomaines   = 60
# 1.93.2 : reconstruction de sécurité du fichier hosts, même si Home Assistant n'a rien changé (Sync-HostsSiNecessaire).
$script:HostsSecuriteSecondes = 600
$script:HostsEtat         = $null
$script:HostsDureeMs      = 0
$script:FlushDnsDemande   = $false
$script:ChargePrecedente  = $null
$script:DernierVu         = @()
$script:Recents           = @()   # derniers refus (apps et sites), pour « Bloqué récemment »
# Quand chaque domaine a ete remonte pour la derniere fois. Sert a faire
# tourner le choix, pour qu'aucun ne reste invisible.
$script:VusEnvoyes        = @{}
# Signature et emplacement de chaque programme, vus une fois pour toutes :
# lire un certificat coute cher, et cela ne change pas entre deux tours.
$script:Signatures        = @{}
# Au-dela, pas d'empreinte : le calcul lit tout le fichier.
$script:EmpreinteMaxMo    = 400
$script:MarqueDebut       = '# >>> Controle parental Home Assistant >>>'
$script:MarqueFin         = '# <<< Controle parental Home Assistant <<<'
$script:JournalMaxOctets  = 1MB
$script:NomTache          = 'HA Controle Parental'
$script:MoiMeme           = $PSCommandPath

$script:Aumid             = 'HomeAssistant.ControleParental'
# La sonde tourne sous le compte de l'enfant, qui n'a aucun droit sur le
# dossier de configuration — c'est voulu, le secret y est. Son dossier est
# donc posé **à côté**, et non dedans : sans droit de traversée sur le
# parent, des droits sur un sous-dossier ne serviraient à rien.
$script:SondeDir          = Join-Path $env:ProgramData 'HAParental-session'
$script:SondePath         = Join-Path $script:SondeDir 'sonde.ps1'
$script:ToastPath         = Join-Path $script:SondeDir 'toast.ps1'
$script:DemandePath       = Join-Path $script:SondeDir 'demande.ps1'
$script:LogoPath          = Join-Path $script:SondeDir 'vision.png'
$script:TrayPath          = Join-Path $script:SondeDir 'tray.ps1'
$script:EtatSondePath     = Join-Path $script:SondeDir 'etat.json'
$script:FermesPath        = Join-Path $script:SondeDir 'fermes.json'
$script:MaisonPath        = Join-Path $script:SondeDir 'maison.json'
$script:EstParent         = $false
# Le nom du lieu (« Maison », « École »…), tel que le relevé le renvoie : il titre la fenêtre et les notifications.
$script:Lieu              = ''
# Les modules actifs de Vision (1.82.0), tels que le relevé les renvoie. $null : Home Assistant ne dit rien
# (serveur plus ancien), donc tout est actif. Gardés dans etat.json pour la grâce hors ligne.
$script:Modules           = $null
# Soutien du projet (« soutien.actif » du relevé) : décoratif seulement, déposé dans etat.json pour la fenêtre.
$script:Soutien           = $false
$script:DnsAncien         = ''
$script:DohPose           = $null
$script:ScrRetraitDit     = $false
$script:DerniereMaison    = [datetime]::MinValue
$script:VeilleRestesFaits = $false
$script:DemandeTemps      = 0
$script:Raisons           = @{}
$script:RaisonsCodes      = @{}
$script:TrayRenouvelee    = $false
# L'œil de Vision (PNG 96 px, le même dessin que sur la veille et dans la fenêtre), posé à côté de la sonde pour les notifications.
$script:LogoBase64        = 'iVBORw0KGgoAAAANSUhEUgAAAGAAAABgCAYAAADimHc4AAAbkUlEQVR42u18e5hdZXnv7/2+ddl77dtcMpMwuUwuJIFJCBcJCAgJSAXveupEQURtj7b0Ea23U7TqzrZUaT3WY6Xaelp7RHuEjH2eStESpYWAIFaakJgEciHJkGQmyWRu+7pu3/eeP9bek5nMnmQSkhA9+/ck/yRr7fWt9/e+7/fevgU00EADDTTQQAMNNNBAAw000EADDTTQQAMNNNBAAw000MBvM+h8XyCDCdm11LN9GbUd2UYAsHo1dL1rn3gCAgAG2pdxd9c2Rm4tE4gbBJyKwLNZ8QQgVm9fxmu7tnEul9Ov5Pey2axYu30ZPdG1jVYDml7h7/1WEsBgQnePQNc2Pl5AP+v+k4wpzI6kIecWw2ClYJrFxBcRSAJc1W4iBitielETH0oa5q+Kodof6KDvd3r+YvR4grF9GaGnW58P1vGqEZDNZsWy7dupu6trgtCfXpNdbkh6u6/DRVLQokDri4m5NWHFhCQBBqBZg+u8iCABAqBYo+S7mokGTSFeUJpfsoTxUqj4R9ety22t3bOuu1sC3dh2BiztN4aAmuDX9PSo2r/94j2fmSmFdV2g1Z0MvDFtxa2QNbTW8HUIpTU0syaq+X6aYt2RRTBDCCIhhYAlDAghYJBA3q/4BPybKeQDSvtPX/Pglw+PJ2NbV9c5J+JcEUDcvU6gZ40mRMr7VHd2npDqKgJ9KGS+NGXZMzUzyoEPzTqMREzEYEHRMk91rcxgEEgDzMyAIGE4pgVBhFLoHybwM0rhAceWG6/8Xu7lyB2C0L1OUM8a9VtBQDabFeO16hfvzs5XpL4giLpNIZOCBDwVINBKRYshcRbXxQBrBmAIKePShGJGoMOiZu6RLL94zUO5fWP7EoCzvU+cVQIeX5U1btyQC//uNR82ly9ufx+R+LDSalnSiiVLgQeltSZCpJxTupWztvEzAE0gCCKZMG0UfbcohdzGrL993YP3fmf8O/xGEZDNZsVaAJTL6Sfffc8SU1jfcUz7Ok8F8FUIxVoBJKj2fEGAPscBybhnctUyJAlpSQO2NFEMvHXM9PHXPZjry2azAgDOxv5wpgmgx1dlZU1jnn7P5z8niD5lCpkpBV4IQICICDjm1pmh3QAibgLnigMCdCWAiJnV/ZyP/WFmAnTCihmh0kcC1ve+7sEvfuNsWQOdSa2vaciT775niSmtv3YM+5aCX0GolRYkxNgTGRAxE9oLoV0fTdcugT+QR2XvAMgQZ48IAjjUiC9og9WWxsgzOyFiFoRtQLtBRAbXrIKVQVI6pg1X+esD5X/0hofu28lV6z5T1nBGCKhpxk9uvdue0dpyl2JeawkjU/TdEARJIIKOPD0IUAUX8QXtyFx9IczmBPKb9qGw5eXIKZ0L/68ZqRXzkL58PoLhEkZ/uRvlvUcgHRvCENVtOjIHAlTCjBmBVqOh5s+faWt4RW9cq9OM8/XfSpj2TaN+BZq1irLVKCA0Mg50JYByfbTefAna3nw5wnwZB/9xA0ov9kEmY8cS27Puggiq6CJxUQdmf3AVjFQcRx/djJFndsIfKkI6diSZY0QoSUImzNgka3il9abTJmBdd7esJVM/f88X7jYE/ZkhRKbke8e0HgBJgioHIFOg6bWLkX7NAjRddSFKL/Zhz1f+FbrsQyZssNLTWi1FkXqkmxNkWnPiNC0XRlJAlTwIx8LCT78VzuJZcA8OY/CnmzH4+HYAgIxbY+s6kTWMl8U5IaBmfv/ytk+nZibi/yth2r83SeurUYaq+IjPm4GWG7uQuXIhhGMjHC6h968fhXtgENI5ufCpqmA6FNChiOJWOVHKWkWUC0NDGFWhMZ2chLKH2NxWdN59K2QqBpICpRf6cORHz6G0sx8yYR8XMbESJGTGiqMUeN85XKr88Tse/krhdF3SqRJAnM0S5XL6P95zz9WOsL8VM8zLR93ycVovoCo+yJBou/VStL5hBWTMhCp7INPAy3/zUxS29MJIxU8s/OrqlCcAJsSaXTizynDaK3DaKiBRFYomlAfiKB+Jo3zIgTscA4ghbT2Wfp2IhLBQQWpFJ+Z95A3QbhApRRDi6PotGHh0MzhUk6wBDJWJOYYbBpvK2rvrpgfv+yVns4JyOcYphBF0ev7+c38mCP/DlqZVDryQiIwJWl/24CyehQvWXAPnwllQZQ8cKBgZB0d+9BwO/8uvTip8EgwdCGhFyCzIo7VrGJkFeZhOABKR0I+/njUQlE2M7k1jcHszRvemISRDmHrS9fVImPmOlWh/+5UI82WQISEdG+Xdh9C/7hco7zoU7Q3jrYE5dEzb8FTga8Zf3vDQvZ8/1X2Bpiv8nu41Yk1Pj3rq3Z/7dnMs8aEht8jMmqkaXpIgKC+EkAKtt6xA262XggwZWQIRhG2ivO8Iev/qJ2PR0FR6QsQIPQkrFWDO6n5uvXgYRAzlE3GtmkSTiwygiAhpKYAJg9ubsX/DbPgFE4atpnZJtbVoRucn3gRnQTu0G4CZI80PFQYe3YzB9VuglYa0jWOREmtNJKgllqRht/S/r3/o3g+v6+6W3T3rplXultOJ7wfavzkm/Iwd/9CQWwpALCYIv+zDnpnB3D94PZqvvxjaD8GBAslq+G9I9H3vKXiHhkGWMbWREqBcicyiApa8czea5g6QdhVxqAlkgohBhCn/AoAOJLQSSM4uoWXJCCqDMVQGHAiTT5gZa9dHOFxG09UXgpUGCQEOor01taITzoUzUdl9GMFwCaL6DkREDEYlCMKmmLPy9ouunf2mdX/78LLu7bKru5s2bNjAp01ALbnq2b6dn3rP5z+XtuOfGvVKIRGZY7UbQdAlD+mVizDvD2+GPasJquhWCw2RuYq4heL2gxj48SaImDVluEnEUK5E22VDWPy2XSDT1Dq5ekS2Xp2HjGt4fTaBwBBgZmiu/dTESlKNDO1LSFuh7ZIh+CUTxQNJSFPXN3wGyDTgHRqBs3Am7I7mSPjV3ES7PuyZTcisXAR/sAh33wDIrpIQpffSVX6YsZ2V7734OvWmdd/asGHDBs5ms+JEJNCJWoOUy+mnbr+n2dDWDyzDuKUUuCFQ9fc135mvoOXGLnTccT04CMGhHlt0LQcQtoneb6xHcdt+iLhVt+5DghG6EpkFBSzt3gmtk6Fc/Nles+3SAjQIAhz2/nNbsO87HSCDYraAIaPnhIrhehpKMaSk42rSBCKGtDR29CzC6N40jJiqvycIgq74SC6bi867b4H2gomtB80gQ4BMA33ffwpDj2+HkT5+L+MwYcYMPwzXh8K/7fr/e99wTZbTtoDaDY/cfk9zgq31Ccu+Ie9XQqoJnyYLX3sBxrLdcQsWtonK/qMYeGQjyJBTuh4OBcxkiMXv3AMpPIj5Hz1gXXDNqK6MWNCBQOhJzLi8YASDFkovONv3hrx1d5H2HHBRqii0NplIp034vobWDKoKblx1AZnOIoZ2NEN5Mip617MCQyIYLCC5bA6slhQ4VMdIqFo0K430FQsQFioo7eiHjFvjNjQSvg7DlB1fEirc9M4V1/xw6f1/XuFsVuTqWIJxIuG3sLU+ZpgrR9xSQETm+EUE+RJab74EHe99XSR85kmNKgaDDIn8c3ugKj6MZBysdf3imCbMXdWHWKaMwG9WseZLShx4koSlAYLSIcUAsbN0Sf7DH/3L1l37NVW8KOyO2xKLO+N41+vb8IG3zkIiLlEsqzFrIIpyCLvJw9xVB/HSj+dDGlxXGUgIhMUK8s/tgbNgJtjza5VDjGdUewE67rgeIMLgY7+GkYxHyscMAhkjbilI2fGVLSHWP3L7PbdQLlfXEsTJhF/wyuFE4WuQITD7zhvQcft1Uwo/ulxABwHKew6DDAmu88ZEDOUJZObn0do1hNA1QQgIrChaXnSPCqN1/3rXEP1yyyikJCQdA0nHABGwdXcJf/L1PXjHJ7Zi654SUkkDSvFEF1eRUTg7Pw/libEEr57SlPcchg4CUD1TGU/C7ddh9p03REVErcfkQERmwSuHMcNc2cLW+kduv6eZcjnN1dL2JAKymEr4wpj00Pe+DjPeeBmUG1TDvzr+VFc3tf4RuAeqkU+9mj8BYEJr13C1xCAhdEGowz9rgmkqpUFBGFDccTQA/uGDD7YkHAECWGuGrv6mExNobzHx3PYC/tsnt2Hr7iISjoQa90zmKPxt7RoGmOrvgJpBlgH3wDC8/hGQaaDuFBJF4bByA8x442V1PQGRMOqRkMUxEkQtzl+bBR6/LTujRRsnFv77bkDmqkUIhorjWyp1HSoZAl7fyFguUA86EIg1u8gsyEP7BBIammLQBx+c5e9d1x6Lx7WTSKvAD+gPf/9D8x79yfq04ySh9MRdVGsgCBnNaQNDowE++VcvwfU1pKBx3pmh/SixizW70IGYIhojqIoPr28k0uwpE5YoBA+GishctQgd77vhxCRoa/3P3nFP61qs5VrLM1pBNcOVOvzblO2szHuVoK7w77geLTd2QZW8sfj+BMkbSBD8o/nIbdV1UQwdEpxZZZhOOCEyCZQBZ/B7Hd/90u8uuWnVmxde85qrlvY81NOcTCWh9dQZdI2EZ7bk8cC/HkIqNdEVsSaYTghnVhk6pLpuiKqu1j+aBwmq6zrrFfYmBCSTSKgECcteGbONB8cnaHJd9zq57Jsf4Zu6//RTjml9LO9VQjHe5x8n/DBfOanwa56FDIHRZ3fD3X8UZE6OgEgAKpCYsWwY6XkFqECOeTMhCKGS+NRXnjOefe5FS4WejMXi4FMoWQ+OBHjXze3HRcUEaWm4Q3GM7ktDWho4PkMmAKGC1ZZBasU8INQ4acuaCNoNkLioA0baQX7T3khOx/YE6YZBkLRji2+/6FrzP7sGN6xb1i1E97o1+pk7sm2GEJ+1pBxrFka++fSEP96f+kfzUy6emSAkw2mrRNpPx9xJ3BLY0VvC7gMK7TOaWBrmCTX/eHcUswR2vlzBrt4y4pbA2K0UWYHTVoGQfILyRM16p0943dB8fIBCLMCAFHTXbHtbK9at0QLEuHYRjpIprioF/sMxYUrWWoOj7O+0hV9blDENaxE8yX1JSTg67KPiKQCa+BSbNUIQKp7GwLAPKSe7keOfebprPxkJquIDKoqkHcOWrvKfDFR49XXfzx0BMQSBGLkce/uxz/P8H3LK2h1LxIVyfT37A6vRsvr0hf//K6LqqouWGy7GvD96A2AILZlkAD0YhOEDN/bct5uzWUEgFgCwFlmy2/OmFNQUeMGzZmdrf8vyTmHNymgO1ZQRzLQqqeHJ3cak0jIISjHami3EbTEWap4KtGbEbYG2ZgtK8cRkqs4zT3ftJ9gSwKGC2ZrUTUtmC0pYh0LoRxk86+e/9+kUcjnm6hQacsjxtT1fqxhzZ/y9yrtbS3sO/zu1OofKv9wjijv6dDQychptT0GwZqRPWHzTKmqmkDiWmQoBVHyNxZ0OlsyLw/U1hDgV9wO4vsaSeXEs7nRQGX8/R+6nPBCvdtGmqo1wtPbTGRRghoiZKO7q16WndwlOWYdUED4GL9ySbLa/9rrvfKVA1e1IHLsFdO3XPllpWth6vypWtg7/6qXH/MA/5D6/XxRf7NMidmokMDNICJitSbCerIFjwiBG+UgMrCcKI1SMZELiXTe3oexG8fx0IQWh7Gq86+Y2JBMS4fiMmDjqoB2JjfWRJ0dwBNYMszUZlaRPRflqwn+xT7vP7xd+4B8a2bjnMVWsbE3HWv7mym/nytUcgCdkwgRwNpsVl33106Wmhe33s6u2jmzqfcz3vYkkTNMd1F4i0qL6L8FMEAajfMhBUDYmbIxSEgqFEHe+dRauXZHGcD6EaZycBNMgDOdDXLsijTvfOguFQjihQkqCEZQNlA85EEb9KCia5BWwZqSnVp4por4Jwve9QyObeh/jitratLD9/su+/+lStur769aCcrmcPkZC6/1cCSaTELemaQkEDjXsjqaoqzTFPcLUcIdjUfvQ4jHfTACUZsQsga9+YhFaMuYYCfXckRDHhN+SMfHVTyxCzBJQmsfEx5ogLMbo3jTc4RiEqae0Xhm3YHc0VfcBmp7mx606wg+2Ni1svf+yr0bCP36ga1I5utZEuO3Lf+r/8Zvf/rw7UJzvHS0UzfZkOx8tpZSEttszdFJLiCQIIxNHcdt+BEcL9ZOx6kSDDgVmdA1HNbhaMkYEz9eYOyuGG1c2YevuEnbsq0RlXEkQgkDV6d6KpzFaULh6eRr/sHYpLrkwiWJJTdR+RNMU+zd0wBuxq5MVNGnfYi9EfP4MzHjDimpTZhrqZhoo7jiovS0Hpi38KfsB9UgIjhaKRluqjY6W0kEY6FhHy0lJYNaQMRte/3A04mHV20ciN1QZjCHW7CE5uwzlT8yIXU9jTruNd93chrZmE0dHAwyOhCi7Cn6gYUiBixc6+Nhtc3DfRxeiY4Y9SfhRCUJhcFsL+n81M5qY4CkquK6PpqsuROqSTmg/OGkUSIZEfvM+HWzvF77v9+c39f67nobwT9qUr924/o6vJOzK4EfIEMszKzovjzenltEFad302sWCA1W/SX5cQ2bf/3zkhOk8K4LhhOh6704YTjA25zM+rBSCkEoZKBZD7Hq5goHhAADQ1mxi8bw4kkkDhUI4du047wAhGWHZxPZ/WoKwbIAkn9CdzP/UWxCfOyPKZkX9AAIEkCkx8uwuzf15URkubBvd0ruJQ73Vi7fef8v3Tyz8aU1FMDMREe99fzbWWwrWIAyvT1+x4LVOJrlczGuOSAj1hFr4JN94Si3JPJZ2vwTli7F24njU2o6vSktyAqMCZAiMPLtL65eHRXm0uDW/ce+zMIynOhPmugXfzbk12Z2BsZRa9Rt4svszd+hQr85cMf+qeCZ1Cc3OcOY1CyEsSRyoyQuuNeW3HUDvNx6FsM0T5gWha6DtsqNYcMvL0GG0N9QrG0QNeR6rXtZVUk3VSTnG3vXzMPD8DBix8IT1H+0F6Lz7ViSXzYGu+JO1nxlkSmhf8eh/7QEfHKXKaOHXoxv3/acwxBM39Hz5+8fL7BWNpUSJWrX5n82KD3zz3s0fXLE65fYPx5G2hPDULPfAEMlMnM2mBHGoqrZ57KU4VLAvaEJlzxF4fUMnGEshCJNRPJBEsd9BprMAMxVABxLgyZMPggiCJp+tibQ7cmlBwcTuhxdg6IUWyJiq6/ePab+HZNcctL/lismuJzr+B2Gb8A6N8OjPd5AaKFB5JL+lsGX/RjKM/1jV86V/ymaz4okNG6Y5oXo6o4nRwTfecNtnf5dLwWtjna1difkzVxuW4cQumasTF3UIhCqaFBgb1OHTHsyau+pgtVvGUL7EORnMmt8+kQDmqBZmSJRe7NPur/eL0A/LpX2Hn3B7B7dTwnx21Q++9M8MpmrOcOZHE+u5pMfuvHe2zI/8kdGcXJq5aM5rLSc2my5II3VpJ5upOGkvGLMG1hpGMv6bMZpYrICEmKD1QaHChc29xP15+GX34OiLB54Nh4s7VLrpmzc/8LmD03U5Z4QA4NhI9uPvz86SRfc2lmJ5anHHpWZL8lIzFTfsZR2cWDiToPQxa2CGsEz03r/+vBzO7fzILdB+MDb5QYYApEBpz2H2tvVRUKiEwVBxc2FX32ZSeqtKxn5w43dzh875ePq4uj0RiJmZNrz7C1fqSuVtsdnNlycXzLzCjNsXiI7MRGvgqMau3RD7vv4TuPvPo/H0j70JMmZEmS8RhG2Mab3uG0VQ8fqLew9vdA8ObxLx+MOrHvric0TENRmcdtX0lda+x5veU3d9uTnsG3w7xcwb0ks6VphNiUvNlBNZw4J2gEHaDyHjFtwDQ9j3tR9DlXxIx3p1DmiUfciEhfkffzNic1qgyj6EbQDMKO09or0X+0WQr4TBSHFzfmffFnaDJ42O1h9d/63PDJ9KpHNWCTh+pggAnlhzz0pdCceswUrELkA6jsTyORzraCbthxCWAffAEA5+dwMq+47CSMWmR8IZbJjE58/A7PevQmxOC2pr8oeKXHy+l/RAAYHr9xf3HN7o9g1vEnHj4dXr7vvV8e/6SiHP1EvlNmzgSDez4oPb/vzgf3/9W58P+oe0d2TUZ0YejFZ1aNRWzCwTNgOA3Z6h1GWd8PqG4b58FMIyolGXs3RUrPbbqlhBasU8zL3rd2DPaoKu+KzcgMt7j6CysZeCwULFHcxvyW/b/3Q4Uvo3c3br/1n13dxLWWTFE9gAOsnE86tiAVNZw9NrPrPcc/01VnNqaXLpBVcbTqzTiFmgpA1n+Wy22zMQtkkDj2zEwCOboLzohAoRxmbwz4TgmQFV9iBtE21vuRxtUazP3pFRlLceJC56CCseVNnrLe45vNE7nP8v4Rg/PRtaf9YJqPnHtdks5XI5/dRdX25Wh0e7Gbws3tFysd2eniti1pxY2kmSY4GaHSSWdujgaJ6O/nQLFbceAAcq8seiOhjFp/Nm0XyP9kKQKZFcPgcz3rCCzRlpLu3oEzxcBpd9uPlyUbv+Ae9Ifr/bP7QDmp70Frb+5JZqIW1tLsdnyy7P6cc6Hrvzs7PFaHgrES8xU87c+JzWpWbGmccGtVqmRbItCaMthbDocn7jXpRfOgLtBUSCorh8wkdTeIpXqX14QIOjBJCdRe1IX7EARjJG4UABaqAIP/CZQh4MRssvVw4M7ggK5f3MtFNnjEdvfuBLB49f+1nbj87FpjfeGgDgmd/PtrjDpYsR4mbTsTrNltQsqzU1WzjWbDJka7w1DZmKwRsqoLijH/5wkVXJY4Sqdh4iOhLB49p5zBxNJoNhSMiETVZzkpJLL4DdkoIquKgM5sGhGtRl/6A/WDgYDBUOBWW/FwYeizUnXrj2H3JD4wR/SoftzmsCxucN1WM9DADPfPyrcXXwyJLADZYTiSUibs21m1MzzbbUImkaGXLMlLDNmBRSshtAlTyEpaqvHjdvWutgGXEbRsKGTNigmAmlldJe4HI5KKggHA0GCi95w4XDuuLvZ9Y7zZi5Vc5u33nt1z5ZqVV+o1D33H3K7FX5ZFm1sEfjN7XnPvx3Tjm/78Kg4l8qIS4my7CFIdtE3EobyVhaJmNN0rFSIm4lSYo0AMETX0Kz0nld8Yuq7BdU0R0Ji25eV/y8DtUA+6GnoF8w49ZmJz1/95Xf/oPy+KABZ9HPn3cEjH9+NpultYg+bXOMjKzjDyNWQbkNnp7JSrcSaJaUsg2CmqRjt0FQ9cMmVYuKjscOQPOIUmqAwYdIikHY4nAczoDVDPfKb+cmCH1t1Ac/J67mfCVgEhm14YB6F+y8++t270h/0i6gVSlo0tFHtlhIlhLCS2Gws+mC4pJvfMybKiDAeSD085WA48rewNqqdUSZ3vQPP9cOlQPAWgDVMBLni9B/Ewg4KTH1/vN8FnQDDTTQQAMNNNBAAw000EADDTTQQAMNNNBAAw000EADDTTQwKuA/wfsJ9CT0p9IbwAAAABJRU5ErkJggg=='
$script:IdlePath          = Join-Path $script:SondeDir 'inactivite.json'
$script:LangueSession     = ''
$script:FormatsSession    = ''

# ---------------------------------------------------------------- Langues
# Les textes affichés (fenêtre, notifications, messages) viennent des dictionnaires de pc_parental/client/langues
# (fr, en, de, es, it, nl). Home Assistant les glisse à la place du marqueur ci-dessous quand il sert l'agent :
# un seul fichier, une seule empreinte, et la mise à jour automatique les apporte avec l'agent. Copié à la main,
# l'agent lit le dossier « langues » posé à côté de lui. Les journaux (Write-Journal) restent en français.
# Les mêmes fonctions sont recopiées dans install.ps1 et uninstall.ps1 (essai/i18n_agent_test.py vérifie
# qu'elles restent identiques), et glissées dans la fenêtre de la session (Get-LanguePreambule).
$script:LanguesJson = @'
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

Initialize-Langues -Json $script:LanguesJson -Dossier (Join-Path $PSScriptRoot 'langues')
$script:Lieu = T 'lieu_defaut'

# Les fonctions de langue, recopiées dans la fenêtre de la session avec tous les dictionnaires (en base64 :
# aucun caractère ne peut casser le script). La fenêtre choisit sa langue elle-même, celle de l'utilisateur.
$script:LangueFonctions = @('ConvertTo-Dico', 'Initialize-Langues', 'Set-Langue', 'T', 'Tn', 'Format-Heure')
function Get-LanguePreambule {
    $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes([string]$script:LanguesTout))
    $code = @(foreach ($f in $script:LangueFonctions) { "function $f {`n" + (Get-Item "function:$f").ScriptBlock.ToString() + "`n}" })
    return ("`$script:LanguesB64 = '$b64'`n" + ($code -join "`n") +
        "`nInitialize-Langues -Json ([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String(`$script:LanguesB64)))`n")
}

# ---------------------------------------------------------------- Journal

function Write-Journal {
    param([string]$Message, [string]$Niveau = 'INFO')
    $ligne = ('{0} [{1}] {2}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Niveau, $Message)
    try {
        if ((Test-Path $script:JournalPath) -and
            ((Get-Item $script:JournalPath).Length -gt $script:JournalMaxOctets)) {
            Move-Item $script:JournalPath "$($script:JournalPath).1" -Force
        }
        Add-Content -Path $script:JournalPath -Value $ligne -Encoding UTF8
    } catch { }
    Write-Verbose $ligne
}

# ------------------------------------------------- Appels Windows natifs

$signature = @'
using System;
using System.Runtime.InteropServices;

public static class Wts
{
    [DllImport("kernel32.dll")]
    public static extern uint WTSGetActiveConsoleSessionId();

    [DllImport("wtsapi32.dll", SetLastError = true)]
    public static extern bool WTSDisconnectSession(IntPtr hServer, uint sessionId, bool bWait);

    [DllImport("wtsapi32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    public static extern bool WTSSendMessageW(
        IntPtr hServer, uint sessionId,
        string pTitle, int titleLength,
        string pMessage, int messageLength,
        uint style, int timeout, out uint pResponse, bool bWait);

    [DllImport("wtsapi32.dll", SetLastError = true)]
    public static extern bool WTSQuerySessionInformationW(
        IntPtr hServer, uint sessionId, int infoClass,
        out IntPtr ppBuffer, out uint pBytesReturned);

    [DllImport("wtsapi32.dll")]
    public static extern void WTSFreeMemory(IntPtr pMemory);

    public const int WTSConnectState = 8;
    public const int WTSUserName = 5;
    public const int WTSSessionInfo = 24;

    // WTSINFOW : la variante Unicode, puisque l'on appelle la fonction W.
    // Les tailles viennent de wtsapi32.h (WINSTATIONNAME_LENGTH = 32,
    // DOMAIN_LENGTH = 17, USERNAME_LENGTH = 20). Une seule erreur ici
    // décale tout ce qui suit et rend LastInputTime absurde.
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    public struct WTSINFO
    {
        public int State;
        public int SessionId;
        public int IncomingBytes;
        public int OutgoingBytes;
        public int IncomingFrames;
        public int OutgoingFrames;
        public int IncomingCompressedBytes;
        public int OutgoingCompressedBytes;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)]
        public string WinStationName;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 17)]
        public string Domain;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 21)]
        public string UserName;
        public long ConnectTime;
        public long DisconnectTime;
        public long LastInputTime;
        public long LogonTime;
        public long CurrentTime;
    }

    /// <summary>
    /// Secondes écoulées depuis la dernière frappe ou action souris dans
    /// cette session, ou -1 si Windows ne le dit pas.
    /// </summary>
    public static double IdleSeconds(uint sessionId)
    {
        IntPtr buffer; uint taille;
        if (!WTSQuerySessionInformationW(IntPtr.Zero, sessionId, WTSSessionInfo, out buffer, out taille))
            return -1;
        try
        {
            WTSINFO infos = (WTSINFO)Marshal.PtrToStructure(buffer, typeof(WTSINFO));
            if (infos.LastInputTime <= 0 || infos.CurrentTime <= 0) return -1;
            double secondes = (infos.CurrentTime - infos.LastInputTime) / 10000000.0;
            // Garde-fou : une valeur aberrante signifie que la structure a
            // été mal lue. Mieux vaut ne rien dire que dire n'importe quoi.
            if (secondes < 0 || secondes > 2592000) return -1;
            return secondes;
        }
        catch { return -1; }
        finally { WTSFreeMemory(buffer); }
    }

    public static int SessionState(uint sessionId)
    {
        IntPtr buffer; uint taille;
        if (!WTSQuerySessionInformationW(IntPtr.Zero, sessionId, WTSConnectState, out buffer, out taille))
            return -1;
        int etat = Marshal.ReadInt32(buffer);
        WTSFreeMemory(buffer);
        return etat;
    }

    public static string SessionUser(uint sessionId)
    {
        IntPtr buffer; uint taille;
        if (!WTSQuerySessionInformationW(IntPtr.Zero, sessionId, WTSUserName, out buffer, out taille))
            return "";
        string nom = Marshal.PtrToStringUni(buffer);
        WTSFreeMemory(buffer);
        return nom == null ? "" : nom;
    }

    public static bool Disconnect(uint sessionId)
    {
        return WTSDisconnectSession(IntPtr.Zero, sessionId, false);
    }

    // --- Lancer un programme dans la session de l'utilisateur ---
    // Un service SYSTEM vit dans la session 0, qui n'a pas d'interface.
    // Pour afficher une vraie notification Windows il faut emprunter le
    // jeton de l'utilisateur connecté et démarrer le processus chez lui.

    [DllImport("wtsapi32.dll", SetLastError = true)]
    static extern bool WTSQueryUserToken(uint sessionId, out IntPtr phToken);

    [DllImport("advapi32.dll", SetLastError = true)]
    static extern bool DuplicateTokenEx(IntPtr hToken, uint access, IntPtr attrs,
        int impersonationLevel, int tokenType, out IntPtr phNewToken);

    [DllImport("userenv.dll", SetLastError = true)]
    static extern bool CreateEnvironmentBlock(out IntPtr lpEnvironment,
        IntPtr hToken, bool bInherit);

    [DllImport("userenv.dll", SetLastError = true)]
    static extern bool DestroyEnvironmentBlock(IntPtr lpEnvironment);

    [DllImport("kernel32.dll", SetLastError = true)]
    static extern bool CloseHandle(IntPtr hObject);

    [DllImport("advapi32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    static extern bool CreateProcessAsUserW(IntPtr hToken, string appName,
        string commandLine, IntPtr procAttrs, IntPtr threadAttrs,
        bool inherit, uint flags, IntPtr environment, string currentDir,
        ref STARTUPINFO si, out PROCESS_INFORMATION pi);

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    struct STARTUPINFO
    {
        public int cb;
        public string lpReserved, lpDesktop, lpTitle;
        public int dwX, dwY, dwXSize, dwYSize, dwXCountChars, dwYCountChars;
        public int dwFillAttribute, dwFlags;
        public short wShowWindow, cbReserved2;
        public IntPtr lpReserved2, hStdInput, hStdOutput, hStdError;
    }

    [StructLayout(LayoutKind.Sequential)]
    struct PROCESS_INFORMATION
    {
        public IntPtr hProcess, hThread;
        public int dwProcessId, dwThreadId;
    }

    const uint MAXIMUM_ALLOWED = 0x02000000;
    const uint CREATE_UNICODE_ENVIRONMENT = 0x00000400;
    const uint CREATE_NO_WINDOW = 0x08000000;

    /// <summary>
    /// Démarre une ligne de commande dans la session indiquée, sous le
    /// compte de l'utilisateur qui s'y trouve. Renvoie false si Windows
    /// refuse : à l'appelant de se rabattre sur autre chose.
    /// </summary>
    public static bool RunAsUser(uint sessionId, string commandLine)
    {
        IntPtr jeton = IntPtr.Zero, copie = IntPtr.Zero, env = IntPtr.Zero;
        try
        {
            if (!WTSQueryUserToken(sessionId, out jeton)) return false;
            // 2 = SecurityImpersonation, 1 = TokenPrimary
            if (!DuplicateTokenEx(jeton, MAXIMUM_ALLOWED, IntPtr.Zero, 2, 1, out copie))
                return false;
            if (!CreateEnvironmentBlock(out env, copie, false)) env = IntPtr.Zero;

            STARTUPINFO si = new STARTUPINFO();
            si.cb = Marshal.SizeOf(typeof(STARTUPINFO));
            si.lpDesktop = "winsta0\\default";

            PROCESS_INFORMATION pi;
            bool ok = CreateProcessAsUserW(copie, null, commandLine,
                IntPtr.Zero, IntPtr.Zero, false,
                CREATE_UNICODE_ENVIRONMENT | CREATE_NO_WINDOW,
                env, null, ref si, out pi);
            if (ok)
            {
                CloseHandle(pi.hProcess);
                CloseHandle(pi.hThread);
            }
            return ok;
        }
        catch { return false; }
        finally
        {
            if (env != IntPtr.Zero) DestroyEnvironmentBlock(env);
            if (copie != IntPtr.Zero) CloseHandle(copie);
            if (jeton != IntPtr.Zero) CloseHandle(jeton);
        }
    }

    public static void Message(uint sessionId, string titre, string texte, int secondes)
    {
        uint reponse;
        WTSSendMessageW(IntPtr.Zero, sessionId, titre, titre.Length * 2,
                        texte, texte.Length * 2, 0x40, secondes, out reponse, false);
    }
}
'@

if (-not ('Wts' -as [type])) {
    Add-Type -TypeDefinition $signature -Language CSharp
}

# WTSActive = 0 : quelqu'un utilise réellement la machine.
$script:WTS_ACTIVE = 0

function Get-SessionConsole {
    $id = [Wts]::WTSGetActiveConsoleSessionId()
    if ($id -eq 0xFFFFFFFF) { return $null }
    return [pscustomobject]@{
        Id    = $id
        Etat  = [Wts]::SessionState($id)
        User  = [Wts]::SessionUser($id)
        Idle  = [Wts]::IdleSeconds($id)
    }
}

# ----------------------------------------------------------- Configuration

function Read-Config {
    if (-not (Test-Path $ConfigPath)) {
        throw "Configuration introuvable : $ConfigPath"
    }
    $brut = Get-Content -Path $ConfigPath -Raw -Encoding UTF8
    return $brut | ConvertFrom-Json
}

function Save-Config {
    param($Config)
    $Config | ConvertTo-Json -Depth 6 |
        Set-Content -Path $ConfigPath -Encoding UTF8
}

function Get-PropOrDefault {
    param($Objet, [string]$Nom, $Defaut)
    if ($null -eq $Objet) { return $Defaut }
    $p = $Objet.PSObject.Properties[$Nom]
    if ($null -eq $p -or $null -eq $p.Value -or $p.Value -eq '') { return $Defaut }
    return $p.Value
}

# ------------------------------------------------------ Modules activables

function Module-Actif {
    <#
        Le module est-il actif ? Absent de la réponse (serveur ancien) :
        tout est actif. Une liste, même vide, fait foi.
    #>
    param([string]$Nom)
    if ($null -eq $script:Modules) { return $true }
    return (@($script:Modules) -contains $Nom.ToLowerInvariant())
}

function Read-Modules {
    <#
        Mémorise la liste « modules » d'un relevé (ou de l'état gardé pour
        la grâce hors ligne), et note dans le journal ce qui change.
    #>
    param($Decision)
    $avant = $script:Modules
    $liste = $null
    if ($null -ne $Decision -and $Decision.PSObject.Properties['modules'] -and $null -ne $Decision.modules) {
        $liste = @(@($Decision.modules) | ForEach-Object { ([string]$_).Trim().ToLowerInvariant() } | Where-Object { $_ })
    }
    $script:Modules = $liste
    $a = if ($null -eq $avant) { '*' } else { (@($avant) | Sort-Object) -join ',' }
    $b = if ($null -eq $liste) { '*' } else { (@($liste) | Sort-Object) -join ',' }
    if ($a -ne $b) {
        if ($null -eq $liste) { Write-Journal 'Modules : Home Assistant ne les précise pas, tout est actif.' }
        else { Write-Journal ("Modules actifs : " + $(if ($b) { $b } else { 'aucun' }) + '.') }
    }
}

# ------------------------------------------------------ Appels Home Assistant

function Invoke-Ha {
    param([string]$Chemin, [hashtable]$Corps, $Config)

    $url = ($Config.url.TrimEnd('/')) + $Chemin
    $parametres = @{
        Uri         = $url
        Method      = 'Post'
        Body        = ($Corps | ConvertTo-Json -Depth 5 -Compress)
        ContentType = 'application/json; charset=utf-8'
        TimeoutSec  = 15
    }
    if (Get-PropOrDefault $Config 'insecure' $false) {
        if ($PSVersionTable.PSVersion.Major -ge 6) {
            $parametres['SkipCertificateCheck'] = $true
        } else {
            [System.Net.ServicePointManager]::ServerCertificateValidationCallback = { $true }
        }
    }
    # On ne laisse pas Invoke-RestMethod deviner l'encodage : Home Assistant
    # renvoie « application/json » sans préciser le jeu de caractères, et
    # Windows PowerShell 5.1 se rabat alors sur ISO-8859-1. « Contrôle »
    # arrivait en « ContrÃ´le ». On lit donc les octets et on décode nous-même.
    $parametres['UseBasicParsing'] = $true
    $reponse = Invoke-WebRequest @parametres
    $texte = [Text.Encoding]::UTF8.GetString($reponse.RawContentStream.ToArray())
    if ([string]::IsNullOrWhiteSpace($texte)) { return $null }
    return ($texte | ConvertFrom-Json)
}

function Register-Agent {
    param($Config)

    $corps = @{
        key  = $Config.key
        host = $env:COMPUTERNAME
        user = (Get-PropOrDefault (Get-SessionConsole) 'User' '')
    }
    $reponse = Invoke-Ha -Chemin '/api/pc_parental/enroll' -Corps $corps -Config $Config
    if (-not $reponse.ok) { throw "Inscription refusée par Home Assistant." }

    $Config | Add-Member -NotePropertyName id     -NotePropertyValue $reponse.id     -Force
    $Config | Add-Member -NotePropertyName secret -NotePropertyValue $reponse.secret -Force
    Save-Config -Config $Config
    Write-Journal "Inscrit auprès de Home Assistant sous « $($reponse.name) »."
    return $Config
}

function Get-CodeHttp {
    param($Erreur)
    if ($Erreur.Exception.PSObject.Properties['Response'] -and $Erreur.Exception.Response) {
        try { return [int]$Erreur.Exception.Response.StatusCode } catch { return 0 }
    }
    return 0
}

# Le relevé des comptes est lent et ne change presque jamais : une fois par
# heure suffit largement.
$script:AdminCache = $null
$script:AdminVu    = [datetime]::MinValue

# 1.88.0 (point 10, diagnostic seulement) : qui administre ce PC. Lecture seule, rien n'est changé : les membres
# du groupe Administrateurs (SID S-1-5-32-544, quel que soit le nom localisé), local ou compte Microsoft, activé ou
# non, et l'état de l'UAC. Relevé au plus toutes les 6 heures ; Home Assistant le garde pour le diagnostic.
$script:DiagAdmin   = $null
$script:DiagAdminVu = [datetime]::MinValue
function Get-DiagAdmin {
    if (((Get-Date) - $script:DiagAdminVu).TotalHours -lt 6) { return $null }
    $script:DiagAdminVu = Get-Date
    $membres = @()
    try {
        foreach ($m in @(Get-LocalGroupMember -SID 'S-1-5-32-544' -ErrorAction Stop)) {
            $actif = $null
            # La classe est traduite (« Utilisateur ») : on essaie le compte local, un groupe échoue sans bruit.
            if ([string]$m.PrincipalSource -eq 'Local') {
                try { $actif = [bool](Get-LocalUser -SID $m.SID -ErrorAction Stop).Enabled } catch { }
            }
            $membres += @{ nom = ([string]$m.Name).Split('\')[-1]; type = [string]$m.ObjectClass; source = [string]$m.PrincipalSource; actif = $actif }
        }
    } catch {
        # Ancienne console sans le module des comptes locaux : la liste brute de « net localgroup ».
        try {
            $nomGroupe = (New-Object System.Security.Principal.SecurityIdentifier('S-1-5-32-544')).Translate([System.Security.Principal.NTAccount]).Value.Split('\')[-1]
            $lignes = @(& net localgroup $nomGroupe 2>$null)
            $dans = $false
            foreach ($l in $lignes) {
                if ($l -match '^-{5,}') { $dans = $true; continue }
                if ($dans -and $l -and $l -notmatch '\.$' -and $l -notmatch 'command') { $membres += @{ nom = $l.Trim(); type = ''; source = ''; actif = $null } }
            }
        } catch { }
    }
    $uac = $null; $invite = $null
    try {
        $cle = Get-ItemProperty 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Policies\System' -ErrorAction Stop
        $uac = [int]$cle.EnableLUA; $invite = [int]$cle.ConsentPromptBehaviorAdmin
    } catch { }
    return @{ membres = @($membres | Select-Object -First 20); uac = $uac; invite_admin = $invite }
}

function Get-Administrateur {
    if (((Get-Date) - $script:AdminVu).TotalMinutes -lt 60) {
        return $script:AdminCache
    }
    $script:AdminCache = Test-SessionAdministrateur
    $script:AdminVu    = Get-Date
    return $script:AdminCache
}

function Read-ListeNoire {
    <#
        Relit la liste noire gardee sur le disque. On la garde en table de
        hachage : verifier l'appartenance de soixante noms a chaque tour
        doit couter zero, meme avec cinquante mille entrees.
    #>
    if (-not (Test-Path $script:NoirePath)) { return }
    try {
        $donnees = Get-Content $script:NoirePath -Raw -Encoding UTF8 | ConvertFrom-Json
    } catch { return }
    $table = @{}
    foreach ($d in @(Get-PropOrDefault $donnees 'connus' @())) {
        if ($d) { $table[[string]$d] = $true }
    }
    $script:NoireConnus  = $table
    $script:NoireHosts   = @(Get-PropOrDefault $donnees 'hosts' @())
    $script:NoireVersion = [string](Get-PropOrDefault $donnees 'version' '')
}

function Sync-ListeNoire {
    <#
        Home Assistant n'envoie que la **version** a chaque tour : cinquante
        mille domaines toutes les vingt secondes n'auraient aucun sens. Quand
        elle change, on va chercher la liste une fois, et on la garde.
    #>
    param($Config, [string]$Version)

    if ([string]::IsNullOrWhiteSpace($Version)) {
        if ($script:NoireConnus.Count -gt 0) {
            $script:NoireConnus  = @{}
            $script:NoireHosts   = @()
            $script:NoireVersion = ''
            Remove-Item $script:NoirePath -Force -ErrorAction SilentlyContinue
            Write-Journal 'Liste noire desactivee.'
        }
        return
    }
    if ($Version -eq $script:NoireVersion) { return }

    try {
        $recu = Invoke-Ha -Chemin '/api/pc_parental/liste-noire' -Corps @{
            id = $Config.id; secret = $Config.secret
        } -Config $Config
    } catch {
        Write-Journal "Liste noire non recuperee : $_" 'WARN'
        return
    }
    if ($null -eq $recu -or -not (Get-PropOrDefault $recu 'ok' $false)) { return }

    try {
        @{
            version = [string](Get-PropOrDefault $recu 'version' '')
            connus  = @(Get-PropOrDefault $recu 'connus' @())
            hosts   = @(Get-PropOrDefault $recu 'hosts' @())
        } | ConvertTo-Json -Depth 3 -Compress |
            Set-Content -Path $script:NoirePath -Encoding UTF8
    } catch {
        Write-Journal "Liste noire non enregistree : $_" 'WARN'
    }
    Read-ListeNoire
    Write-Journal ("Liste noire : $($script:NoireConnus.Count) domaines connus, " +
        "$($script:NoireHosts.Count) dans le fichier hosts.")
}

function Set-DnsFiltrant {
    <#
        Pose le resolveur filtrant sur les cartes reseau actives, ou rend la
        main au DHCP de la box. C'est lui qui encaisse la masse : le fichier
        hosts ne peut pas porter des millions de noms.

        Ne marche que parce que le DNS chiffre des navigateurs est desactive
        par ailleurs : sans cela Chrome resoudrait lui-meme.
    #>
    param([string[]]$Serveurs, [bool]$Flush = $true)

    $voulu = (@($Serveurs) -join ',')
    if ($voulu -eq $script:DnsPose) { return }

    try {
        $cartes = Get-NetAdapter -Physical -ErrorAction Stop |
            Where-Object { $_.Status -eq 'Up' }
        foreach ($carte in $cartes) {
            if ($Serveurs.Count -gt 0) {
                Set-DnsClientServerAddress -InterfaceIndex $carte.ifIndex `
                    -ServerAddresses $Serveurs -ErrorAction Stop
            } else {
                Set-DnsClientServerAddress -InterfaceIndex $carte.ifIndex `
                    -ResetServerAddresses -ErrorAction Stop
            }
        }
        # 1.93.2 : -Flush $false laisse le vidage à Invoke-FlushDnsGroupe (un seul par tour avec le fichier hosts).
        if ($Flush) { ipconfig /flushdns | Out-Null } else { $script:FlushDnsDemande = $true }
        $script:DnsPose = $voulu
        if ($Serveurs.Count -gt 0) {
            Write-Journal "Filtre DNS pose : $voulu"
        } else {
            Write-Journal 'Filtre DNS retire : DNS de la box.'
        }
    } catch {
        Write-Journal "Filtre DNS non applique : $_" 'WARN'
    }
}

function Test-SousDomaineDe([string]$Nom, $Domaines) {
    # 1.89.1 : « c0.rbxcdn.com » bloqué parce que « rbxcdn.com » l'est par une règle de la maison. Avant, il
    # remontait comme « filtre DNS » et gonflait le compteur des sites dangereux.
    $bouts = $Nom.Split('.')
    for ($i = 1; $i -lt $bouts.Count - 1; $i++) {
        if ($Domaines.ContainsKey(($bouts[$i..($bouts.Count - 1)] -join '.'))) { return $true }
    }
    return $false
}

function Get-Tentatives {
    <#
        Les domaines dangereux qui viennent d'etre demandes.

        Deux traces possibles dans le cache DNS : le nom figure dans la liste
        noire, ou il a ete resolu en 0.0.0.0 par le resolveur filtrant sans
        etre l'un de nos blocages a nous. Dans les deux cas quelqu'un a
        essaye d'y aller, et c'est ca qui merite d'etre dit.

        Un meme domaine ne ressort pas a chaque tour : on previent une fois,
        Home Assistant decide du reste.
    #>
    param([string[]]$NotresBlocages, [bool]$FiltreActif)

    $tentatives = @()
    try {
        # Ici, contrairement au releve des sites consultes, on veut
        # justement les entrees resolues en 0.0.0.0 : ce sont les
        # tentatives vers ce qui est bloque.
        $entrees = Get-DnsClientCache -ErrorAction Stop |
            Where-Object { $_.Entry -and $_.Type -in 1, 5 }
    } catch {
        return @()
    }
    $nous = @{}
    foreach ($n in @($NotresBlocages)) { if ($n) { $nous[[string]$n] = $true } }

    foreach ($entree in $entrees) {
        $nom = $entree.Entry.ToLowerInvariant().TrimEnd('.')
        if (-not $nom -or $nom -notlike '*.*') { continue }
        if ($script:NoireSignalees.ContainsKey($nom)) { continue }
        $source = ''
        if ($script:NoireConnus.ContainsKey($nom)) {
            $source = 'liste noire'
        } elseif ($nous.ContainsKey($nom) -or (Test-SousDomaineDe $nom $nous)) {
            # Un site bloque par une decision de la maison. On le comptait
            # pas : seuls les domaines dangereux remontaient. Mais essayer
            # d'aller sur un site qu'on a soi-meme ferme est exactement ce
            # qu'un parent veut voir -- c'est meme la seule chose que la
            # courbe raconte vraiment.
            $source = 'regle'
        } elseif ($FiltreActif -and ([string]$entree.Data) -eq '0.0.0.0') {
            $source = 'filtre DNS'
        }
        if (-not $source) { continue }
        $script:NoireSignalees[$nom] = (Get-Date)
        $tentatives += @{ domaine = $nom; source = $source }
    }
    # On oublie au bout d'une heure : une tentative refaite le lendemain est
    # une nouvelle information.
    foreach ($cle in @($script:NoireSignalees.Keys)) {
        if (((Get-Date) - $script:NoireSignalees[$cle]).TotalMinutes -gt 60) {
            $script:NoireSignalees.Remove($cle)
        }
    }
    return @($tentatives | Select-Object -First 20)
}

function Get-Decision {
    param($Config)

    $session = Get-SessionConsole
    $releve  = Get-Releve
    # La langue de la personne devant l'écran (la sonde tourne dans sa session) ; l'agent, en SYSTEM, n'a que
    # celle de Windows. Gardée tant que la sonde se tait : un écran verrouillé ne change pas de langue.
    if ($releve.langue -and ($releve.langue -ne $script:LangueSession -or $releve.formats -ne $script:FormatsSession)) {
        $script:LangueSession = [string]$releve.langue; $script:FormatsSession = [string]$releve.formats
        Set-Langue -Langue $script:LangueSession -Formats $script:FormatsSession
        Write-Journal "Langue de la session : $($script:LangueSession) (textes en « $($script:Langue) »)."
    }
    # Les mêmes noms servent deux fois : à dire ce qui a été consulté, et à
    # étendre un domaine bloqué à ses sous-domaines. On ne lit donc le cache
    # DNS qu'une fois par tour.
    $script:DernierVu = @(Get-SitesConsultes)
    $script:Tentatives = @()
    # Filtrage web coupé : on ne guette plus les tentatives vers ce qui était bloqué.
    if (Module-Actif 'filtrage') {
        $script:Tentatives = @(Get-Tentatives -NotresBlocages $script:DerniersBlocages `
            -FiltreActif ($script:DnsPose -ne ''))
    }
    # Protections coupées : on ne surveille plus si le compte est administrateur.
    $admin = $null
    if (Module-Actif 'protections') { $admin = Get-Administrateur }
    $corps = @{
        id           = $Config.id
        secret       = $Config.secret
        host         = $env:COMPUTERNAME
        user         = (Get-PropOrDefault $session 'User' '')
        locked       = ($null -eq $session -or $session.Etat -ne $script:WTS_ACTIVE)
        version      = $script:Version
        langue       = $(if ($script:LangueSession) { $script:LangueSession } else { [string](Get-UICulture).Name })
        idle_seconds = $releve.idle
        focus        = $releve.premier
        apps_ouverts = @($releve.ouverts)
        sites_vus    = @(Select-ARemonter -Noms $script:DernierVu -Plafond 120)
        admin        = $admin
        alertes      = @($script:Tentatives)
        diag_admin   = $(if (Module-Actif 'protections') { Get-DiagAdmin } else { $null })
        origines     = (Get-Origines)
        signatures   = (Get-Signatures -Noms @($releve.ouverts + @($releve.premier)))
        agent_charge = (Get-ChargeAgent)
    }
    if ($script:DemandeTemps -gt 0) {
        # Module Demandes coupé entre le clic et le relevé : la demande ne part pas.
        if (Module-Actif 'demandes') { $corps.demande_temps = $script:DemandeTemps }
        $script:DemandeTemps = 0
    }
    # Ce qui tourne et ce qui se lance tout seul : lourd a relever, donc
    # toutes les dix minutes, pas a chaque tour. Ce que l'agent a deja
    # applique part a chaque fois : c'est court, et Home Assistant doit
    # pouvoir dire « appliqué » sans attendre le prochain inventaire.
    try {
        $corps.appliques = (Get-Appliques)
        $rendus = @(Get-ComptesRendusDesinstall)
        if ($rendus.Count -gt 0) { $corps.desinstallations = $rendus }
        if (((Get-Date) - $script:DernierInventaire).TotalSeconds -ge $script:InventaireSecondes) {
            $corps.inventaire = @(Get-Inventaire)
            $script:DernierInventaire = Get-Date
        }
    } catch { Write-Journal "Inventaire des processus impossible : $_" 'WARN' }
    return Invoke-Ha -Chemin '/api/pc_parental/poll' -Corps $corps -Config $Config
}

# ------------------------------------------------------------- Application

function Lock-Poste {
    $session = Get-SessionConsole
    if ($null -eq $session) { return $false }
    if ($session.Etat -ne $script:WTS_ACTIVE) { return $false }
    $ok = [Wts]::Disconnect($session.Id)
    if ($ok) {
        Write-Journal "Session de « $($session.User) » verrouillée."
    } else {
        Write-Journal "Échec du verrouillage de la session $($session.Id)." 'WARN'
    }
    return $ok
}

# Sonde d'inactivité, exécutée dans la session de l'utilisateur.
#
# WTSINFOW.LastInputTime n'est tenu à jour que pour les sessions distantes :
# sur la session console il reste figé à l'ouverture et donnerait des durées
# absurdes. GetLastInputInfo, lui, est fiable — mais ne renvoie que
# l'activité de la session qui l'appelle. D'où cette petite boucle lancée
# chez l'utilisateur, qui écrit son résultat là où le service peut le lire.
$script:SondeSource = @'
param([string]$Sortie)

# Une seule sonde par session. Le lanceur y veille deja ; c'est ici que la
# garantie tient pour de bon. Un verrou abandonne (sonde tuee) se reprend.
$verrou = New-Object Threading.Mutex($false, 'Local\VisionSonde')
try { $tenu = $verrou.WaitOne(0) } catch [Threading.AbandonedMutexException] { $tenu = $true }
if (-not $tenu) { exit 0 }

Add-Type -Namespace Sonde -Name Entree -MemberDefinition @"
[StructLayout(LayoutKind.Sequential)]
public struct LASTINPUTINFO { public uint cbSize; public uint dwTime; }

[DllImport("user32.dll")]
public static extern bool GetLastInputInfo(ref LASTINPUTINFO plii);

[DllImport("kernel32.dll")]
public static extern uint GetTickCount();

[DllImport("user32.dll")]
public static extern IntPtr GetForegroundWindow();

[DllImport("user32.dll")]
public static extern int GetWindowThreadProcessId(IntPtr fenetre, out int pid);

public static double Secondes()
{
    LASTINPUTINFO infos = new LASTINPUTINFO();
    infos.cbSize = (uint)Marshal.SizeOf(infos);
    if (!GetLastInputInfo(ref infos)) return -1;
    return (GetTickCount() - infos.dwTime) / 1000.0;
}

public static int PidAuPremierPlan()
{
    int pid = 0;
    IntPtr fenetre = GetForegroundWindow();
    if (fenetre == IntPtr.Zero) return 0;
    GetWindowThreadProcessId(fenetre, out pid);
    return pid;
}
"@

# Programmes qui ont une fenêtre mais que personne n'utilise : les compter
# reviendrait à dire que l'enfant « se sert de l'explorateur » en permanence.
# Programmes de Windows et utilitaires de barre des taches : ils tournent
# tout le temps, personne ne les choisit. Home Assistant ecarte les memes
# noms de son cote (BRUIT_APPS) : ici c'est pour ne pas les transmettre.
$script:Ignores = @(
    'applicationframehost', 'textinputhost', 'shellexperiencehost',
    'searchhost', 'searchapp', 'searchui', 'startmenuexperiencehost',
    'systemsettings', 'lockapp', 'explorer', 'dwm', 'sihost', 'taskhostw',
    'runtimebroker', 'dllhost', 'backgroundtaskhost', 'useroobebroker',
    'ctfmon', 'conhost', 'smartscreen', 'securityhealthsystray',
    'rtkuwp', 'rtkauduservice64', 'nahimic3', 'nahimicservice',
    'razerappengine', 'razercentralservice', 'razersynapseservice',
    'armourycrate', 'nvcontainer', 'nvsphelper64',
    'widgets', 'widgetboard', 'phoneexperiencehost'
)

function Get-NomProcessus {
    param([int]$Identifiant)
    if ($Identifiant -le 0) { return '' }
    $p = Get-Process -Id $Identifiant -ErrorAction SilentlyContinue
    if ($null -eq $p) { return '' }
    return $p.ProcessName.ToLowerInvariant()
}

while ($true) {
    try {
        $premier = Get-NomProcessus ([Sonde.Entree]::PidAuPremierPlan())
        if ($script:Ignores -contains $premier) { $premier = '' }

        $ouverts = @(
            Get-Process |
                Where-Object { $_.MainWindowHandle -ne 0 } |
                ForEach-Object { $_.ProcessName.ToLowerInvariant() } |
                Where-Object { $script:Ignores -notcontains $_ } |
                Sort-Object -Unique
        )

        # Pas de conversion de date ici : sur un Windows français,
        # [double]::Parse d'un horodatage à point décimal lève une
        # exception et la sonde n'écrirait jamais rien. La fraîcheur du
        # relevé se juge sur la date du fichier.
        # On ne remonte que des noms de programmes, jamais les titres de
        # fenêtres : ils en diraient beaucoup trop.
        @{
            idle    = [int][Sonde.Entree]::Secondes()
            pid     = $PID
            premier = $premier
            ouverts = $ouverts
            langue  = [string](Get-UICulture).Name
            formats = [string](Get-Culture).Name
        } | ConvertTo-Json -Compress | Set-Content -Path "$Sortie.tmp" -Encoding UTF8
        # Ecrire a cote puis remplacer : un lecteur ne tombe jamais sur un
        # fichier tronque a zero le temps d'une ecriture.
        Move-Item -LiteralPath "$Sortie.tmp" -Destination $Sortie -Force
    } catch { }
    Start-Sleep -Seconds 5
}
'@

# Script exécuté dans la session de l'utilisateur pour afficher une vraie
# notification Windows. Il doit tourner sous Windows PowerShell 5.1 :
# PowerShell 7 ne sait pas parler à WinRT.
$script:ToastSource = @'
param([string]$Payload)

$json = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($Payload)) |
    ConvertFrom-Json
$aumid = $json.aumid
$dossier = Split-Path -Parent $PSCommandPath

# Sans identité déclarée, Windows refuse d'afficher la notification ou
# l'attribue à « PowerShell ». On s'enregistre une fois, chez l'utilisateur,
# avec le nom et le logo de Vision.
$cle = "HKCU:\Software\Classes\AppUserModelId\$aumid"
if (-not (Test-Path $cle)) { New-Item -Path $cle -Force | Out-Null }
New-ItemProperty -Path $cle -Name 'DisplayName' -Value $json.appname `
    -PropertyType String -Force | Out-Null
New-ItemProperty -Path $cle -Name 'ShowInSettings' -Value 1 `
    -PropertyType DWord -Force | Out-Null
$logo = [string]$json.logo
if ($logo -and (Test-Path $logo)) {
    New-ItemProperty -Path $cle -Name 'IconUri' -Value $logo -PropertyType String -Force | Out-Null
    New-ItemProperty -Path $cle -Name 'IconBackgroundColor' -Value 'FF120609' -PropertyType String -Force | Out-Null
}

# Le bouton « Demander l'accès » passe par un protocole enregistré chez
# l'utilisateur : un clic lance demande.ps1, qui dépose la demande pour
# l'agent. Cela marche aussi depuis le centre de notifications.
$proto = 'HKCU:\Software\Classes\vision-demande'
$commande = 'powershell.exe -NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File "{0}" "%1"' -f (Join-Path $dossier 'demande.ps1')
if (-not (Test-Path "$proto\shell\open\command")) {
    New-Item -Path "$proto\shell\open\command" -Force | Out-Null
}
Set-ItemProperty -Path $proto -Name '(Default)' -Value 'URL:Vision demande' -Force
New-ItemProperty -Path $proto -Name 'URL Protocol' -Value '' -PropertyType String -Force | Out-Null
Set-ItemProperty -Path "$proto\shell\open\command" -Name '(Default)' -Value $commande -Force

[void][Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime]
[void][Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom, ContentType = WindowsRuntime]

$titre = [Security.SecurityElement]::Escape([string]$json.title)
$texte = [Security.SecurityElement]::Escape([string]$json.text)
$lieu = [Security.SecurityElement]::Escape([string]$json.lieu)

# Un son répété exige une notification de durée longue, sinon Windows le
# coupe au bout de quelques secondes.
$repete = [bool]$json.loop
$son = [string]$json.sound
$duree = if ($repete -or $json.long) { 'long' } else { 'short' }
if ([string]::IsNullOrEmpty($son)) {
    $audio = '<audio silent="true"/>'
} elseif ($repete) {
    $audio = '<audio src="ms-winsoundevent:' + $son + '" loop="true"/>'
} else {
    $audio = '<audio src="ms-winsoundevent:' + $son + '"/>'
}

$image = ''
if ($logo -and (Test-Path $logo)) {
    $image = '<image placement="appLogoOverride" hint-crop="circle" src="file:///' + ($logo -replace '\\', '/') + '"/>'
}

$actions = ''
if ($json.PSObject.Properties['demande'] -and $json.demande) {
    $brut = [Text.Encoding]::UTF8.GetBytes(($json.demande | ConvertTo-Json -Compress))
    $arg = [Convert]::ToBase64String($brut).TrimEnd('=').Replace('+', '-').Replace('/', '_')
    $bDemander = [Security.SecurityElement]::Escape([string]$json.bouton_demander)
    $bFermer = [Security.SecurityElement]::Escape([string]$json.bouton_fermer)
    $actions = @"
  <actions>
    <action content="$bDemander" activationType="protocol" arguments="vision-demande:$arg"/>
    <action content="$bFermer" activationType="system" arguments="dismiss"/>
  </actions>
"@
    if ($duree -eq 'short') { $duree = 'long' }
}

$xml = New-Object Windows.Data.Xml.Dom.XmlDocument
$xml.LoadXml(@"
<toast duration="$duree">
  <visual>
    <binding template="ToastGeneric">
      $image
      <text>$titre</text>
      <text>$texte</text>
      <text placement="attribution">Vision · $lieu</text>
    </binding>
  </visual>
  $audio
$actions
</toast>
"@)

$toast = New-Object Windows.UI.Notifications.ToastNotification $xml
[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier($aumid).Show($toast)
'@

# Déposé à côté : ce que le clic sur « Demander l'accès » exécute, dans la
# session de l'utilisateur. Il écrit la demande ; l'agent l'envoie.
$script:DemandeSource = @'
param([string]$Uri)
$dossier = Split-Path -Parent $PSCommandPath
$brut = ([string]$Uri) -replace '^vision-demande:', '' -replace '/+$', ''
$b64 = $brut.Replace('-', '+').Replace('_', '/')
switch ($b64.Length % 4) { 2 { $b64 += '==' } 3 { $b64 += '=' } }
try {
    $json = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($b64))
    [void]($json | ConvertFrom-Json)
    $nom = Join-Path $dossier ('demande-{0}.json' -f [DateTime]::UtcNow.Ticks)
    Set-Content -Path $nom -Value $json -Encoding UTF8
} catch { }
'@

# L'icône de Vision en bas à droite, dans la session de l'utilisateur : un
# clic droit pour demander l'accès à un site (avec le lien et un mot
# d'explication) ou un peu de temps. Les demandes sont déposées dans le
# dossier de la sonde ; l'agent les envoie. Rien ne se décide ici.
$script:TraySource = @'
param([string]$Dossier, [string]$Depart = '')
# @@LANGUE@@

# Vision, la fenêtre du PC : WPF, fenêtre sans bordure système, coins arrondis,
# charte Vision. Tourne dans la session de l'utilisateur, sous Windows
# PowerShell 5.1. Elle lit ce que l'agent dépose (etat.json, fermes.json,
# maison.json) et dépose ses demandes et actions ; l'agent fait le reste.

$verrou = New-Object Threading.Mutex($false, 'Local\VisionTray')
try { $tenu = $verrou.WaitOne(0) } catch [Threading.AbandonedMutexException] { $tenu = $true }
if (-not $tenu) { exit 0 }

Add-Type -AssemblyName PresentationFramework, PresentationCore, WindowsBase, System.Windows.Forms, System.Drawing
# La veille couvre chaque écran au pixel près (1.81.0) : la fenêtre est posée en pixels physiques, après son
# affichage, sans passer par les unités WPF (arrondies, et calées sur l'échelle de l'écran principal seulement).
Add-Type -Namespace Sonde -Name Ecran -MemberDefinition @"
[DllImport("user32.dll", SetLastError = true)]
public static extern bool SetWindowPos(IntPtr hWnd, IntPtr hWndInsertAfter, int X, int Y, int cx, int cy, uint uFlags);
"@
# Sa propre identité dans la barre des tâches : l'icône de Vision, pas celle de PowerShell.
try { Add-Type -Namespace VisionShell -Name Identite -MemberDefinition '[DllImport("shell32.dll", CharSet = CharSet.Unicode)] public static extern int SetCurrentProcessExplicitAppUserModelID(string id);'; [void][VisionShell.Identite]::SetCurrentProcessExplicitAppUserModelID('Vision.Agent') } catch { }

# ------------------------------------------------------------------ Données
$script:Dossier = $Dossier
function Lire([string]$fichier) {
    $f = Join-Path $script:Dossier $fichier
    if (-not (Test-Path $f)) { return $null }
    try { return (Get-Content $f -Raw -Encoding UTF8 | ConvertFrom-Json) } catch { return $null }
}
function Deposer($objet) {
    $nom = Join-Path $script:Dossier ('demande-{0}.json' -f [DateTime]::UtcNow.Ticks)
    ($objet | ConvertTo-Json -Compress) | Set-Content -Path $nom -Encoding UTF8
}
function Agir($objet) {
    $nom = Join-Path $script:Dossier ('action-{0}.json' -f [DateTime]::UtcNow.Ticks)
    ($objet | ConvertTo-Json -Compress -Depth 4) | Set-Content -Path $nom -Encoding UTF8
}
# Le nom du lieu, déposé par l'agent (etat.json, sinon maison.json) : « Maison » tant qu'il n'a rien dit.
function NomLieu() {
    $l = [string](Prop (Lire 'etat.json') 'lieu' '')
    if (-not $l) { $l = [string](Prop (Lire 'maison.json') 'lieu' '') }
    if ($l) { return $l } else { return (T 'lieu_defaut') }
}
# Les modules actifs de Vision (1.82.0), déposés par l'agent (etat.json, sinon maison.json). Absents : tout est
# actif (agent ou Home Assistant plus anciens). Relus au plus toutes les trois secondes : les minuteries s'en servent.
$script:ModListe = $null; $script:ModLu = [datetime]::MinValue
function ModuleActif([string]$m) {
    if (((Get-Date) - $script:ModLu).TotalSeconds -ge 3) {
        $l = $null
        $e = Lire 'etat.json'
        if ($null -ne $e -and $e.PSObject.Properties['modules']) { $l = $e.modules }
        else {
            $mj = Lire 'maison.json'
            if ($null -ne $mj -and $mj.PSObject.Properties['modules']) { $l = $mj.modules }
        }
        $script:ModListe = $null
        if ($null -ne $l) { $script:ModListe = @(@($l) | ForEach-Object { ([string]$_).ToLowerInvariant() }) }
        $script:ModLu = Get-Date
    }
    if ($null -eq $script:ModListe) { return $true }
    return (@($script:ModListe) -contains $m)
}
function Prop($o, [string]$n, $defaut = $null) {
    if ($null -eq $o) { return $defaut }
    $p = $o.PSObject.Properties[$n]
    if ($null -eq $p -or $null -eq $p.Value) { return $defaut }
    return $p.Value
}
# Soutien du projet : ses textes, prêts pour la traduction (langue de l'interface de Windows, l'anglais sinon).
# Soutien : textes du dictionnaire de l'agent (six langues, i18n).
function TexteSoutien([string]$cle) { return (T $cle) }
function Hote($lien) {
    $l = ([string]$lien).Trim()
    if (-not $l) { return '' }
    if ($l -notmatch '^[a-z][a-z0-9+.-]*://') { $l = 'https://' + $l }
    try { return ([Uri]$l).Host.ToLowerInvariant() -replace '^www\.', '' } catch { return '' }
}
function Heure([string]$iso) {
    if (-not $iso) { return '' }
    try { return (Format-Heure ([datetime]$iso)) } catch { return '' }
}
$script:Demandees = @{}
# Un parent a dit non : l'heure à partir de laquelle on peut redemander (« 18h05 »), ou rien.
function RefusJusqua($data, [string]$genre, [string]$nom) {
    $n = ($nom.ToLowerInvariant() -replace '\.exe$', '')
    $maintenant = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    foreach ($x in @(Prop $data 'refus' @())) {
        if ([string](Prop $x 'genre') -ne $genre) { continue }
        $c = ([string](Prop $x 'nom')).ToLowerInvariant() -replace '\.exe$', ''
        $fin = [long](Prop $x 'jusqua' 0)
        if ($fin -gt $maintenant -and ($c -eq $n -or $n.EndsWith('.' + $c))) { return (Format-Heure ([DateTimeOffset]::FromUnixTimeSeconds($fin))) }
    }
    return ''
}

# ------------------------------------------------------------------ Réglages de ce PC
# Propres à chaque PC et à chaque session : le thème de couleur, l'écran de veille,
# les tableaux et les cartes montrés. Home Assistant dit seulement ce qui est disponible.
$script:Themes = [ordered]@{
    'Vision'      = @{ Fond = '#FF120609'; Carte = '#FF27111A'; Haute = '#FF361724'; Ligne = '#FF4C2231'; Or = '#FFF2C14E'; OrSombre = '#FF1E1605'; Vert = '#FF4CC38A'; Rouge = '#FFFF6B6B'; Texte = '#FFFBF3EE'; Texte2 = '#FFCDA8A6'; Texte3 = '#FF8C666C'; Horloge = 'defaut' }
    'Sombre'      = @{ Fond = '#FF17121C'; Carte = '#FF251C2D'; Haute = '#FF30253A'; Ligne = '#FF3A2D45'; Or = '#FFE2B24A'; OrSombre = '#FF1E1605'; Vert = '#FF4CC38A'; Rouge = '#FFFF6B6B'; Texte = '#FFF4EDE1'; Texte2 = '#FFBDB1C4'; Texte3 = '#FF8E8396'; Horloge = 'sombre' }
    'Bleu nuit'   = @{ Fond = '#FF0F1830'; Carte = '#FF1A2647'; Haute = '#FF223158'; Ligne = '#FF2B3A64'; Or = '#FFE2B24A'; OrSombre = '#FF1E1605'; Vert = '#FF4CC38A'; Rouge = '#FFFF6B6B'; Texte = '#FFEAF0FA'; Texte2 = '#FFB6C1D4'; Texte3 = '#FF7F8CA3'; Horloge = 'bleu-nuit' }
    'Beige'       = @{ Fond = '#FFEFE6D6'; Carte = '#FFFBF6EC'; Haute = '#FFE6DBC6'; Ligne = '#FFD9CBB3'; Or = '#FF8F6110'; OrSombre = '#FFFFFFFF'; Vert = '#FF2E7D57'; Rouge = '#FFB3261E'; Texte = '#FF2A2026'; Texte2 = '#FF6E5A5E'; Texte3 = '#FF8F7D80'; Horloge = 'beige' }
    'Sauge'       = @{ Fond = '#FFDDE5DA'; Carte = '#FFF2F6EF'; Haute = '#FFD0DACB'; Ligne = '#FFC2CEBD'; Or = '#FF7F5C0E'; OrSombre = '#FFFFFFFF'; Vert = '#FF2E7D57'; Rouge = '#FFB3261E'; Texte = '#FF1F2A24'; Texte2 = '#FF5E6E64'; Texte3 = '#FF7F8F85'; Horloge = 'sauge' }
    'Salon'       = @{ Fond = '#FFD9D1C5'; Carte = '#FFF4F1EB'; Haute = '#FFCCC3B5'; Ligne = '#FFBDB3A4'; Or = '#FF8A6433'; OrSombre = '#FFFFFFFF'; Vert = '#FF2E7D57'; Rouge = '#FFB3261E'; Texte = '#FF2A2724'; Texte2 = '#FF6B645C'; Texte3 = '#FF8E867D'; Horloge = 'salon' }
    'Rose poudré' = @{ Fond = '#FFF0DEDD'; Carte = '#FFFBF1F0'; Haute = '#FFE6D0CF'; Ligne = '#FFD9BFBE'; Or = '#FF875A12'; OrSombre = '#FFFFFFFF'; Vert = '#FF2E7D57'; Rouge = '#FFB3261E'; Texte = '#FF2C1F24'; Texte2 = '#FF7A5A60'; Texte3 = '#FF998287'; Horloge = 'rose-poudre' }
    # Thèmes d'univers : jeux (Tactique, Briques, Corsaire, Royale), passions (Circuit, Cockpit, Bourse, Écrin), dehors (Prairie, Large, Sommet, Sous-bois).
    'Tactique' = @{ Fond = '#FF0F1923'; Carte = '#FF1B2733'; Haute = '#FF2E3843'; Ligne = '#FF2C3A47'; Or = '#FFFF4655'; OrSombre = '#FFFFFFFF'; Vert = '#FF4CC38A'; Rouge = '#FFFF6B6B'; Texte = '#FFECE8E1'; Texte2 = '#FF9AA7B1'; Texte3 = '#FF69757F'; Horloge = 'tactique' }
    'Briques' = @{ Fond = '#FFF2F4F5'; Carte = '#FFFFFFFF'; Haute = '#FFE1E3E4'; Ligne = '#FFD5DADE'; Or = '#FF0A84D6'; OrSombre = '#FFFFFFFF'; Vert = '#FF2E7D57'; Rouge = '#FFB3261E'; Texte = '#FF191B1D'; Texte2 = '#FF60666C'; Texte3 = '#FF93989C'; Horloge = 'briques' }
    'Corsaire' = @{ Fond = '#FF0B2A3A'; Carte = '#FF123C50'; Haute = '#FF264C5C'; Ligne = '#FF1F5166'; Or = '#FFF2A93B'; OrSombre = '#FF1A1408'; Vert = '#FF4CC38A'; Rouge = '#FFFF6B6B'; Texte = '#FFF4EBD0'; Texte2 = '#FFA9C3C9'; Texte3 = '#FF728D97'; Horloge = 'corsaire' }
    'Royale' = @{ Fond = '#FF1B1035'; Carte = '#FF2A1B55'; Haute = '#FF3C2E64'; Ligne = '#FF3D2A78'; Or = '#FFF8D21A'; OrSombre = '#FF1A1408'; Vert = '#FF4CC38A'; Rouge = '#FFFF6B6B'; Texte = '#FFF5F3FF'; Texte2 = '#FFB9AEE0'; Texte3 = '#FF8277A4'; Horloge = 'royale' }
    'Circuit' = @{ Fond = '#FF121212'; Carte = '#FF1E1E1E'; Haute = '#FF313131'; Ligne = '#FF363636'; Or = '#FFE5281B'; OrSombre = '#FFFFFFFF'; Vert = '#FF4CC38A'; Rouge = '#FFFF6B6B'; Texte = '#FFF2F2F2'; Texte2 = '#FFA0A0A0'; Texte3 = '#FF6E6E6E'; Horloge = 'circuit' }
    'Cockpit' = @{ Fond = '#FF0E1A24'; Carte = '#FF16283A'; Haute = '#FF293A4B'; Ligne = '#FF25405A'; Or = '#FFFFB000'; OrSombre = '#FF1A1408'; Vert = '#FF4CC38A'; Rouge = '#FFFF6B6B'; Texte = '#FFE8F1F8'; Texte2 = '#FF9DB2C4'; Texte3 = '#FF6B7D8C'; Horloge = 'cockpit' }
    'Bourse' = @{ Fond = '#FF0B0F0E'; Carte = '#FF141B19'; Haute = '#FF272E2C'; Ligne = '#FF24302C'; Or = '#FF21C77A'; OrSombre = '#FF1A1408'; Vert = '#FF4CC38A'; Rouge = '#FFFF6B6B'; Texte = '#FFE6F2EC'; Texte2 = '#FF8FA59B'; Texte3 = '#FF61706A'; Horloge = 'bourse' }
    'Écrin' = @{ Fond = '#FF0E0C0A'; Carte = '#FF1A1714'; Haute = '#FF2E2A26'; Ligne = '#FF322B22'; Or = '#FFC9A45C'; OrSombre = '#FF1A1408'; Vert = '#FF4CC38A'; Rouge = '#FFFF6B6B'; Texte = '#FFF3EBDD'; Texte2 = '#FFB4A68F'; Texte3 = '#FF7A7060'; Horloge = 'ecrin' }
    'Prairie' = @{ Fond = '#FFEEF3E6'; Carte = '#FFFAFCF5'; Haute = '#FFDEE3D6'; Ligne = '#FFCFDBC2'; Or = '#FF4F8A2B'; OrSombre = '#FFFFFFFF'; Vert = '#FF2E7D57'; Rouge = '#FFB3261E'; Texte = '#FF22301F'; Texte2 = '#FF5F7058'; Texte3 = '#FF919E8A'; Horloge = 'prairie' }
    'Large' = @{ Fond = '#FFE6F1F5'; Carte = '#FFF7FBFD'; Haute = '#FFD5E2E6'; Ligne = '#FFC3D9E1'; Or = '#FF0F7EA3'; OrSombre = '#FFFFFFFF'; Vert = '#FF2E7D57'; Rouge = '#FFB3261E'; Texte = '#FF12303D'; Texte2 = '#FF55737F'; Texte3 = '#FF889FA8'; Horloge = 'large' }
    'Sommet' = @{ Fond = '#FFE9EDF1'; Carte = '#FFF8FAFC'; Haute = '#FFD9DDE2'; Ligne = '#FFCBD3DB'; Or = '#FFC2553A'; OrSombre = '#FFFFFFFF'; Vert = '#FF2E7D57'; Rouge = '#FFB3261E'; Texte = '#FF1F2933'; Texte2 = '#FF5C6B7A'; Texte3 = '#FF8D98A4'; Horloge = 'sommet' }
    'Sous-bois' = @{ Fond = '#FF1A2119'; Carte = '#FF252E23'; Haute = '#FF373F33'; Ligne = '#FF36422F'; Or = '#FFC58B3B'; OrSombre = '#FF1A1408'; Vert = '#FF4CC38A'; Rouge = '#FFFF6B6B'; Texte = '#FFECE6D6'; Texte2 = '#FFA9B19C'; Texte3 = '#FF777F6E'; Horloge = 'sous-bois' }
    # Thèmes d'histoires : écran (Code, Plume, Galaxie, Rétro 85, Grimoire), contes (Féerie, Banquise, Lagon), créatures (Étincelle)
    'Code' = @{ Fond = '#FF030A05'; Carte = '#FF0A1A10'; Haute = '#FF1C2F23'; Ligne = '#FF13301F'; Or = '#FF2BEA6B'; OrSombre = '#FF1A1408'; Vert = '#FF4CC38A'; Rouge = '#FFFF6B6B'; Texte = '#FFD7FFE0'; Texte2 = '#FF6FBF87'; Texte3 = '#FF49805A'; Horloge = 'code' }
    'Plume' = @{ Fond = '#FFE8F1F7'; Carte = '#FFF8FBFD'; Haute = '#FFE5E9EC'; Ligne = '#FFC9D8E2'; Or = '#FFA8552F'; OrSombre = '#FFFFFFFF'; Vert = '#FF2E7D57'; Rouge = '#FFB3261E'; Texte = '#FF23313D'; Texte2 = '#FF5F7482'; Texte3 = '#FF8FA0AB'; Horloge = 'plume' }
    'Galaxie' = @{ Fond = '#FF05060A'; Carte = '#FF10131C'; Haute = '#FF25272E'; Ligne = '#FF23283A'; Or = '#FFFFD426'; OrSombre = '#FF1A1408'; Vert = '#FF4CC38A'; Rouge = '#FFFF6B6B'; Texte = '#FFF4F1E4'; Texte2 = '#FF9AA0B4'; Texte3 = '#FF666A78'; Horloge = 'galaxie' }
    'Rétro 85' = @{ Fond = '#FF14100E'; Carte = '#FF221B17'; Haute = '#FF352E29'; Ligne = '#FF3A2E27'; Or = '#FFFF7A1A'; OrSombre = '#FF1A1408'; Vert = '#FF4CC38A'; Rouge = '#FFFF6B6B'; Texte = '#FFF6EDE2'; Texte2 = '#FFB8A698'; Texte3 = '#FF7F7268'; Horloge = 'retro-85' }
    'Grimoire' = @{ Fond = '#FF1C1210'; Carte = '#FF2A1C18'; Haute = '#FF3C2E28'; Ligne = '#FF44302A'; Or = '#FFD4A843'; OrSombre = '#FF1A1408'; Vert = '#FF4CC38A'; Rouge = '#FFFF6B6B'; Texte = '#FFF1E6CF'; Texte2 = '#FFB9A583'; Texte3 = '#FF82725B'; Horloge = 'grimoire' }
    'Féerie' = @{ Fond = '#FF141A3C'; Carte = '#FF1F2757'; Haute = '#FF323966'; Ligne = '#FF303A78'; Or = '#FFFFD36E'; OrSombre = '#FF1A1408'; Vert = '#FF4CC38A'; Rouge = '#FFFF6B6B'; Texte = '#FFF6F3FF'; Texte2 = '#FFB4B8E0'; Texte3 = '#FF7C81A7'; Horloge = 'feerie' }
    'Banquise' = @{ Fond = '#FFE9F4FB'; Carte = '#FFF8FCFF'; Haute = '#FFE4EAEE'; Ligne = '#FFC5DDEC'; Or = '#FF2F8FCB'; OrSombre = '#FFFFFFFF'; Vert = '#FF2E7D57'; Rouge = '#FFB3261E'; Texte = '#FF173247'; Texte2 = '#FF5C7C92'; Texte3 = '#FF8DA6B7'; Horloge = 'banquise' }
    'Lagon' = @{ Fond = '#FF06343B'; Carte = '#FF0C4750'; Haute = '#FF21565D'; Ligne = '#FF1A5F69'; Or = '#FFFF8A5B'; OrSombre = '#FF1A1408'; Vert = '#FF4CC38A'; Rouge = '#FFFF6B6B'; Texte = '#FFF3F0DC'; Texte2 = '#FF9CC7C2'; Texte3 = '#FF689493'; Horloge = 'lagon' }
    'Étincelle' = @{ Fond = '#FFFFF4CC'; Carte = '#FFFFFBEA'; Haute = '#FFECE8D7'; Ligne = '#FFEBD98F'; Or = '#FFD93A2B'; OrSombre = '#FFFFFFFF'; Vert = '#FF2E7D57'; Rouge = '#FFB3261E'; Texte = '#FF2B2416'; Texte2 = '#FF75683F'; Texte3 = '#FFA59970'; Horloge = 'etincelle' }
}
function NomTheme([string]$nom) {
    $p = $script:Themes[$nom]
    if ($null -eq $p) { return $nom }
    $cle = 'theme.' + ([string]$p.Horloge -replace '-', '_')
    $t = T $cle
    if ($t -eq $cle) { return $nom }
    return $t
}
$script:SvFichier = Join-Path $env:APPDATA 'Vision\veille.json'
$script:SvPurger = $false
function SvReglages() {
    $r = @{ actif = $true; delai = 10; theme = 'Vision'; sombre = 'jamais'; style = 'doux'; fondCartes = $true; contourCartes = $false; animer = $true; masques = @(); cartes = @(); figees = @(); verrou = $false; windows = $null; cameras = 'auto' }
    try {
        if (Test-Path $script:SvFichier) {
            $lu = Get-Content $script:SvFichier -Raw -Encoding UTF8 | ConvertFrom-Json
            if ($null -ne (Prop $lu 'actif')) { $r.actif = [bool](Prop $lu 'actif') }
            if (Prop $lu 'delai') { $r.delai = [int](Prop $lu 'delai') }
            if ($script:Themes.Contains([string](Prop $lu 'theme'))) { $r.theme = [string](Prop $lu 'theme') }
            if (@('doux', 'neoretro') -contains [string](Prop $lu 'style')) { $r.style = [string](Prop $lu 'style') }
            if (@('jamais', 'systeme', 'toujours') -contains [string](Prop $lu 'sombre')) { $r.sombre = [string](Prop $lu 'sombre') }
            if ($null -ne (Prop $lu 'fondCartes')) { $r.fondCartes = [bool](Prop $lu 'fondCartes') }
            if ($null -ne (Prop $lu 'contourCartes')) { $r.contourCartes = [bool](Prop $lu 'contourCartes') }
            if ($null -ne (Prop $lu 'animer')) { $r.animer = [bool](Prop $lu 'animer') }
            if ($null -ne (Prop $lu 'verrou')) { $r.verrou = [bool](Prop $lu 'verrou') }
            if ($null -ne (Prop $lu 'windows')) { $r.windows = [bool](Prop $lu 'windows') }
            if (@('auto', 'direct', 'image') -contains [string](Prop $lu 'cameras')) { $r.cameras = [string](Prop $lu 'cameras') }
            # Faille 2.2-1 : le code de gestion ne vit plus sur le PC. Une ancienne empreinte, posée par
            # n'importe qui depuis l'onglet Réglages, ne vaut plus rien : on l'efface du profil.
            if ((Prop $lu 'codeHash' '') -or (Prop $lu 'codeSel' '')) { $script:SvPurger = $true }
            $r.masques = @(@(Prop $lu 'masques' @()) | ForEach-Object { [string]$_ })
            $r.cartes = @(@(Prop $lu 'cartes' @()) | ForEach-Object { [string]$_ })
            $r.figees = @(@(Prop $lu 'figees' @()) | ForEach-Object { [string]$_ })
        }
    } catch { }
    if ($script:SvPurger) { $script:SvPurger = $false; SvEcrire $r }
    return $r
}
function SvEcrire($r) {
    try {
        $d = Split-Path $script:SvFichier -Parent
        if (-not (Test-Path $d)) { New-Item -ItemType Directory -Path $d -Force | Out-Null }
        (@{ actif = [bool]$r.actif; delai = [int]$r.delai; theme = [string]$r.theme; sombre = [string]$r.sombre; style = [string]$r.style; fondCartes = [bool]$r.fondCartes; contourCartes = [bool]$r.contourCartes; animer = [bool]$r.animer; masques = @($r.masques); cartes = @($r.cartes); figees = @($r.figees); verrou = [bool]$r.verrou; windows = $r.windows } | ConvertTo-Json -Compress) | Set-Content -Path $script:SvFichier -Encoding UTF8
    } catch { }
}
# 1.86.0 (point 14.4) : la variante sombre de chaque thème clair, calculée comme dans l'appli Android :
# fond presque noir teinté de l'accent, encre claire teintée du fond d'origine, accent éclairci.
function CoulMix([string]$a, [string]$b, [double]$t) {
    # Des décimaux partout : [math]::Min(1, $t) prendrait la version entière et ramènerait $t à 0.
    $t = [math]::Max(0.0, [math]::Min(1.0, $t)); $o = '#FF'
    foreach ($i in 3, 5, 7) {
        $x = [Convert]::ToInt32($a.Substring($i, 2), 16); $y = [Convert]::ToInt32($b.Substring($i, 2), 16)
        $o += ([int][math]::Max(0.0, [math]::Min(255.0, [math]::Truncate($x + ($y - $x) * $t)))).ToString('X2')
    }
    return $o
}
function CoulClarte([string]$c) {
    return (0.2126 * [Convert]::ToInt32($c.Substring(3, 2), 16) + 0.7152 * [Convert]::ToInt32($c.Substring(5, 2), 16) + 0.0722 * [Convert]::ToInt32($c.Substring(7, 2), 16)) / 255
}
function PalSombre($p) {
    if ((CoulClarte ([string]$p.Fond)) -lt 0.45) { return $p }
    $fond = CoulMix '#FF141118' ([string]$p.Or) 0.07
    $carte = CoulMix '#FF221D27' ([string]$p.Or) 0.10
    $texte = CoulMix '#FFF5EFE6' ([string]$p.Fond) 0.15
    $or = [string]$p.Or; $n = 0
    while ((CoulClarte $or) -lt 0.55 -and $n -lt 12) { $or = CoulMix $or '#FFFFFFFF' 0.12; $n++ }
    return @{ Fond = $fond; Carte = $carte; Haute = (CoulMix $carte '#FFFFFFFF' 0.06); Ligne = (CoulMix $carte $or 0.22); Or = $or; OrSombre = '#FF1A1408'
              Vert = '#FF4CC38A'; Rouge = '#FFFF6B6B'; Texte = $texte; Texte2 = (CoulMix $texte $fond 0.30); Texte3 = (CoulMix $texte $fond 0.50); Horloge = 'sombre' }
}
# Windows en mode sombre pour les applis (Paramètres > Personnalisation > Couleurs) ?
function WindowsSombre() {
    try { return ((Get-ItemProperty -Path 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize' -Name AppsUseLightTheme -ErrorAction Stop).AppsUseLightTheme -eq 0) } catch { return $false }
}
function PalEffective($r) {
    $p = $script:Themes[[string]$r.theme]
    if ([string]$r.sombre -eq 'toujours' -or ([string]$r.sombre -eq 'systeme' -and (WindowsSombre))) { return (PalSombre $p) }
    return $p
}
$script:Pal = PalEffective (SvReglages)

# ------------------------------------------------------------------ L'œil de Vision
# Le même que sur la veille et sur les télés : une amande claire, l'iris pourpre et or qui regarde ici et
# là, la paupière qui cligne vite et se rouvre plus doucement. Mêmes proportions, mêmes couleurs, même rythme.
$script:Oeil = $null
$script:OeilHasard = New-Object System.Random
function OeilInstaller($toile) {
    $inv = [Globalization.CultureInfo]::InvariantCulture
    $conv = New-Object System.Windows.Media.BrushConverter
    $pourpre = $conv.ConvertFromString('#C2577B'); $encre = $conv.ConvertFromString('#1B1418'); $creme = $conv.ConvertFromString('#E9E2D6')
    $or = $conv.ConvertFromString([string]$script:Pal.Or)
    $toile.Children.Clear()
    $amande = New-Object System.Windows.Shapes.Path; $amande.Fill = $creme
    $iris = New-Object System.Windows.Controls.Canvas; $iris.Width = $toile.Width; $iris.Height = $toile.Height
    $disques = @()
    foreach ($def in @(@(1.0, $pourpre, 1.0), @(0.62, $or, 1.0), @(0.34, $encre, 1.0), @(0.14, [System.Windows.Media.Brushes]::White, 0.78))) {
        $e = New-Object System.Windows.Shapes.Ellipse; $e.Fill = $def[1]; $e.Opacity = $def[2]
        $iris.Children.Add($e) | Out-Null; $disques += , @{ forme = $e; part = [double]$def[0] }
    }
    $haut = New-Object System.Windows.Shapes.Path; $haut.Stroke = $pourpre; $haut.StrokeStartLineCap = 'Round'; $haut.StrokeEndLineCap = 'Round'
    $bas = New-Object System.Windows.Shapes.Path; $bas.Stroke = $pourpre; $bas.Opacity = 0.59; $bas.StrokeStartLineCap = 'Round'; $bas.StrokeEndLineCap = 'Round'
    foreach ($x in @($amande, $iris, $haut, $bas)) { $toile.Children.Add($x) | Out-Null }
    $demiL = ([double]$toile.Width - 6) / 2; $h = $demiL / 0.03
    $haut.StrokeThickness = $h * 0.004; $bas.StrokeThickness = $h * 0.002
    $script:Oeil = @{
        inv = $inv; amande = $amande; iris = $iris; disques = $disques; haut = $haut; bas = $bas
        cx = [double]$toile.Width / 2; cy = [double]$toile.Height / 2; demiL = $demiL; demiH = $h * 0.017
        clinDebut = -9.0; prochainClin = 0.0; prochainRegard = 0.0; cibleX = 0.0; cibleY = 0.0; rx = 0.0; ry = 0.0; repos = $false
    }
    OeilDessiner 0.0
}
function OeilDessiner([double]$fermeture) {
    $o = $script:Oeil; if (-not $o) { return }
    $cx = $o.cx; $cy = $o.cy; $dl = $o.demiL; $dh = $o.demiH
    $ouv = $dh * (1 - $fermeture * 0.96); $bas = $dh * 2 * (1 - $fermeture * 0.5)
    $x0 = $cx - $dl; $x1 = $cx + $dl; $yh = $cy - $ouv * 2; $yb = $cy + $bas
    $tout = [string]::Format($o.inv, 'M {0:0.##},{1:0.##} Q {2:0.##},{3:0.##} {4:0.##},{1:0.##} Q {2:0.##},{5:0.##} {0:0.##},{1:0.##} Z', $x0, $cy, $cx, $yh, $x1, $yb)
    $geo = [System.Windows.Media.Geometry]::Parse($tout)
    $o.amande.Data = $geo; $o.iris.Clip = $geo
    $o.haut.Data = [System.Windows.Media.Geometry]::Parse([string]::Format($o.inv, 'M {0:0.##},{1:0.##} Q {2:0.##},{3:0.##} {4:0.##},{1:0.##}', $x0, $cy, $cx, $yh, $x1))
    $o.bas.Data = [System.Windows.Media.Geometry]::Parse([string]::Format($o.inv, 'M {0:0.##},{1:0.##} Q {2:0.##},{3:0.##} {4:0.##},{1:0.##}', $x0, $cy, $cx, $yb, $x1))
    $ri = $dh * 0.95; $ix = $cx + $o.rx * $dl * 0.45; $iy = $cy + $o.ry * $dh * 0.5
    $k = 0
    foreach ($d in $o.disques) {
        $r = $ri * $d.part; $x = $ix; $y = $iy
        if ($k -eq 3) { $x = $ix - $ri * 0.28; $y = $iy - $ri * 0.3 }
        $d.forme.Width = $r * 2; $d.forme.Height = $r * 2
        [System.Windows.Controls.Canvas]::SetLeft($d.forme, $x - $r); [System.Windows.Controls.Canvas]::SetTop($d.forme, $y - $r)
        $k++
    }
}
$script:OeilChrono = [System.Diagnostics.Stopwatch]::StartNew()
$script:OeilTic = New-Object System.Windows.Threading.DispatcherTimer
$script:OeilTic.Interval = [TimeSpan]::FromMilliseconds(33)
$script:OeilTic.Add_Tick({
    try {
        $o = $script:Oeil
        if (-not $o -or -not $script:W -or -not $script:W.IsVisible) { $script:OeilTic.Stop(); return }
        $t = $script:OeilChrono.Elapsed.TotalSeconds; $h = $script:OeilHasard
        if ($t -ge $o.prochainClin) { $o.clinDebut = $t; $o.prochainClin = $t + $(if ($h.NextDouble() -lt 0.18) { 0.4 } else { 2.5 + $h.NextDouble() * 5 }) }
        $tt = $t - $o.clinDebut
        $fermeture = 0.0
        if ($tt -ge 0 -and $tt -lt 0.10) { $fermeture = $tt / 0.10 } elseif ($tt -ge 0.10 -and $tt -lt 0.26) { $fermeture = 1 - ($tt - 0.10) / 0.16 }
        if ($t -ge $o.prochainRegard) { $o.cibleX = ($h.NextDouble() - 0.5) * 1.6; $o.cibleY = ($h.NextDouble() - 0.5) * 0.9; $o.prochainRegard = $t + 1.5 + $h.NextDouble() * 4 }
        $bouge = ([Math]::Abs($o.cibleX - $o.rx) + [Math]::Abs($o.cibleY - $o.ry)) -gt 0.004
        # Rien ne bouge : on ne redessine pas.
        if ($fermeture -eq 0 -and -not $bouge) { if ($o.repos) { return }; $o.repos = $true } else { $o.repos = $false }
        $o.rx += ($o.cibleX - $o.rx) * 0.3; $o.ry += ($o.cibleY - $o.ry) * 0.3
        OeilDessiner $fermeture
    } catch { }
})

# ------------------------------------------------------------------ XAML
function CreerFenetre() {
[xml]$xaml = @"
<Window xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation"
        xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml"
        Title="Vision" Width="620" Height="760" WindowStartupLocation="CenterScreen"
        WindowStyle="None" AllowsTransparency="True" Background="Transparent" ResizeMode="NoResize"
        Topmost="False" ShowInTaskbar="True" FontFamily="Segoe UI">
  <Window.Resources>
    <SolidColorBrush x:Key="Fond" Color="$($script:Pal.Fond)"/>
    <SolidColorBrush x:Key="Carte" Color="$($script:Pal.Carte)"/>
    <SolidColorBrush x:Key="Haute" Color="$($script:Pal.Haute)"/>
    <SolidColorBrush x:Key="Ligne" Color="$($script:Pal.Ligne)"/>
    <SolidColorBrush x:Key="Or" Color="$($script:Pal.Or)"/>
    <SolidColorBrush x:Key="OrSombre" Color="$($script:Pal.OrSombre)"/>
    <SolidColorBrush x:Key="Vert" Color="$($script:Pal.Vert)"/>
    <SolidColorBrush x:Key="Rouge" Color="$($script:Pal.Rouge)"/>
    <SolidColorBrush x:Key="Texte" Color="$($script:Pal.Texte)"/>
    <SolidColorBrush x:Key="Texte2" Color="$($script:Pal.Texte2)"/>
    <SolidColorBrush x:Key="Texte3" Color="$($script:Pal.Texte3)"/>

    <Style x:Key="Primaire" TargetType="Button">
      <Setter Property="Background" Value="{StaticResource Or}"/>
      <Setter Property="Foreground" Value="{StaticResource OrSombre}"/>
      <Setter Property="FontWeight" Value="SemiBold"/>
      <Setter Property="FontSize" Value="13"/>
      <Setter Property="Padding" Value="16,8"/>
      <Setter Property="Cursor" Value="Hand"/>
      <Setter Property="Template">
        <Setter.Value>
          <ControlTemplate TargetType="Button">
            <Border x:Name="b" Background="{TemplateBinding Background}" CornerRadius="12" Padding="{TemplateBinding Padding}">
              <ContentPresenter HorizontalAlignment="Center" VerticalAlignment="Center"/>
            </Border>
            <ControlTemplate.Triggers>
              <Trigger Property="IsMouseOver" Value="True"><Setter TargetName="b" Property="Background" Value="#FFF5CC5A"/></Trigger>
              <Trigger Property="IsEnabled" Value="False"><Setter TargetName="b" Property="Opacity" Value="0.5"/></Trigger>
            </ControlTemplate.Triggers>
          </ControlTemplate>
        </Setter.Value>
      </Setter>
    </Style>
    <Style x:Key="Secondaire" TargetType="Button" BasedOn="{StaticResource Primaire}">
      <Setter Property="Background" Value="{StaticResource Haute}"/>
      <Setter Property="Foreground" Value="{StaticResource Texte}"/>
      <Setter Property="Template">
        <Setter.Value>
          <ControlTemplate TargetType="Button">
            <Border x:Name="b" Background="{TemplateBinding Background}" BorderBrush="{StaticResource Ligne}" BorderThickness="1" CornerRadius="12" Padding="{TemplateBinding Padding}">
              <ContentPresenter HorizontalAlignment="Center" VerticalAlignment="Center"/>
            </Border>
            <ControlTemplate.Triggers>
              <Trigger Property="IsMouseOver" Value="True"><Setter TargetName="b" Property="Background" Value="{StaticResource Ligne}"/></Trigger>
              <Trigger Property="IsEnabled" Value="False"><Setter TargetName="b" Property="Opacity" Value="0.5"/></Trigger>
            </ControlTemplate.Triggers>
          </ControlTemplate>
        </Setter.Value>
      </Setter>
    </Style>
    <Style x:Key="Danger" TargetType="Button" BasedOn="{StaticResource Secondaire}">
      <Setter Property="Foreground" Value="{StaticResource Rouge}"/>
    </Style>
    <Style x:Key="Onglet" TargetType="ToggleButton">
      <Setter Property="Foreground" Value="{StaticResource Texte2}"/>
      <Setter Property="FontWeight" Value="SemiBold"/>
      <Setter Property="FontSize" Value="13"/>
      <Setter Property="Padding" Value="16,8"/>
      <Setter Property="Cursor" Value="Hand"/>
      <Setter Property="Template">
        <Setter.Value>
          <ControlTemplate TargetType="ToggleButton">
            <Border x:Name="b" Background="Transparent" CornerRadius="10" Padding="{TemplateBinding Padding}" Margin="0,0,6,0">
              <ContentPresenter HorizontalAlignment="Center" VerticalAlignment="Center"/>
            </Border>
            <ControlTemplate.Triggers>
              <Trigger Property="IsChecked" Value="True">
                <Setter TargetName="b" Property="Background" Value="{StaticResource Or}"/>
                <Setter Property="Foreground" Value="{StaticResource OrSombre}"/>
              </Trigger>
              <Trigger Property="IsMouseOver" Value="True"><Setter TargetName="b" Property="Opacity" Value="0.85"/></Trigger>
            </ControlTemplate.Triggers>
          </ControlTemplate>
        </Setter.Value>
      </Setter>
    </Style>
    <Style x:Key="Interrupteur" TargetType="ToggleButton">
      <Setter Property="Cursor" Value="Hand"/>
      <Setter Property="Template">
        <Setter.Value>
          <ControlTemplate TargetType="ToggleButton">
            <Grid Width="46" Height="26">
              <Border x:Name="piste" CornerRadius="13" Background="{StaticResource Ligne}"/>
              <Border x:Name="pouce" Width="20" Height="20" CornerRadius="10" Background="{StaticResource Texte2}" HorizontalAlignment="Left" Margin="3,0,0,0"/>
            </Grid>
            <ControlTemplate.Triggers>
              <Trigger Property="IsChecked" Value="True">
                <Setter TargetName="piste" Property="Background" Value="#FF8A2B2B"/>
                <Setter TargetName="pouce" Property="Background" Value="{StaticResource Rouge}"/>
                <Setter TargetName="pouce" Property="HorizontalAlignment" Value="Right"/>
                <Setter TargetName="pouce" Property="Margin" Value="0,0,3,0"/>
              </Trigger>
              <Trigger Property="IsEnabled" Value="False"><Setter Property="Opacity" Value="0.4"/></Trigger>
            </ControlTemplate.Triggers>
          </ControlTemplate>
        </Setter.Value>
      </Setter>
    </Style>
    <Style x:Key="Coche" TargetType="CheckBox">
      <Setter Property="Foreground" Value="{StaticResource Texte}"/>
      <Setter Property="FontSize" Value="13"/>
      <Setter Property="Cursor" Value="Hand"/>
      <Setter Property="Template">
        <Setter.Value>
          <ControlTemplate TargetType="CheckBox">
            <StackPanel Orientation="Horizontal" Background="Transparent">
              <Border x:Name="boite" Width="22" Height="22" CornerRadius="7" Background="{StaticResource Haute}" BorderBrush="{StaticResource Ligne}" BorderThickness="1.5" VerticalAlignment="Center">
                <Path x:Name="trait" Data="M4.5,10 L8.5,14 L15,5.5" Stroke="{StaticResource OrSombre}" StrokeThickness="2.4" StrokeStartLineCap="Round" StrokeEndLineCap="Round" StrokeLineJoin="Round" Visibility="Collapsed"/>
              </Border>
              <ContentPresenter Margin="11,0,0,0" VerticalAlignment="Center"/>
            </StackPanel>
            <ControlTemplate.Triggers>
              <Trigger Property="IsChecked" Value="True">
                <Setter TargetName="boite" Property="Background" Value="{StaticResource Or}"/>
                <Setter TargetName="boite" Property="BorderBrush" Value="{StaticResource Or}"/>
                <Setter TargetName="trait" Property="Visibility" Value="Visible"/>
              </Trigger>
              <Trigger Property="IsMouseOver" Value="True"><Setter TargetName="boite" Property="BorderBrush" Value="{StaticResource Or}"/></Trigger>
            </ControlTemplate.Triggers>
          </ControlTemplate>
        </Setter.Value>
      </Setter>
    </Style>
    <Style x:Key="Champ" TargetType="TextBox">
      <Setter Property="Background" Value="{StaticResource Haute}"/>
      <Setter Property="Foreground" Value="{StaticResource Texte}"/>
      <Setter Property="CaretBrush" Value="{StaticResource Or}"/>
      <Setter Property="BorderBrush" Value="{StaticResource Ligne}"/>
      <Setter Property="BorderThickness" Value="1"/>
      <Setter Property="Padding" Value="12,9"/>
      <Setter Property="FontSize" Value="13"/>
      <Setter Property="Template">
        <Setter.Value>
          <ControlTemplate TargetType="TextBox">
            <Border Background="{TemplateBinding Background}" BorderBrush="{TemplateBinding BorderBrush}" BorderThickness="{TemplateBinding BorderThickness}" CornerRadius="12">
              <ScrollViewer x:Name="PART_ContentHost" Margin="{TemplateBinding Padding}" VerticalAlignment="Center"/>
            </Border>
            <ControlTemplate.Triggers>
              <Trigger Property="IsKeyboardFocused" Value="True"><Setter Property="BorderBrush" Value="{StaticResource Or}"/></Trigger>
            </ControlTemplate.Triggers>
          </ControlTemplate>
        </Setter.Value>
      </Setter>
    </Style>
    <Style TargetType="ScrollBar">
      <Setter Property="Width" Value="6"/>
      <Setter Property="Background" Value="Transparent"/>
      <Setter Property="Template">
        <Setter.Value>
          <ControlTemplate TargetType="ScrollBar">
            <Track x:Name="PART_Track" IsDirectionReversed="True">
              <Track.Thumb><Thumb><Thumb.Template><ControlTemplate><Border Background="{StaticResource Ligne}" CornerRadius="3" Width="6"/></ControlTemplate></Thumb.Template></Thumb></Track.Thumb>
            </Track>
          </ControlTemplate>
        </Setter.Value>
      </Setter>
    </Style>
  </Window.Resources>

  <Border CornerRadius="22" Background="{StaticResource Fond}" BorderBrush="{StaticResource Ligne}" BorderThickness="1" Margin="12">
    <Border.Effect><DropShadowEffect BlurRadius="24" ShadowDepth="0" Opacity="0.55"/></Border.Effect>
    <DockPanel>
      <!-- En-tête : la poignée pour déplacer la fenêtre -->
      <Grid x:Name="Entete" DockPanel.Dock="Top" Margin="24,20,20,8" Background="Transparent">
        <StackPanel Orientation="Horizontal">
          <Grid Width="52" Height="44" Margin="0,0,12,0">
            <Image x:Name="Logo" Visibility="Collapsed"/>
            <Canvas x:Name="Oeil" Width="52" Height="44"/>
          </Grid>
          <StackPanel VerticalAlignment="Center">
            <TextBlock Text="Vision" FontSize="24" FontWeight="SemiBold" Foreground="{StaticResource Texte}"/>
            <TextBlock x:Name="SousTitre" Text="$([Security.SecurityElement]::Escape((T 'tray.supervision' @{ lieu = (T 'lieu_defaut') })))" FontSize="12" Foreground="{StaticResource Texte2}"/>
          </StackPanel>
        </StackPanel>
        <StackPanel Orientation="Horizontal" HorizontalAlignment="Right" VerticalAlignment="Top">
          <Border x:Name="ChipEtat" CornerRadius="10" Background="{StaticResource Haute}" Padding="12,5" Margin="0,4,10,0">
            <TextBlock x:Name="ChipEtatTexte" Text="$([Security.SecurityElement]::Escape((T 'etat.ouvert')))" FontSize="11" FontWeight="Bold" Foreground="{StaticResource Vert}"/>
          </Border>
          <Button x:Name="Plein" Style="{StaticResource Secondaire}" Padding="10,4" Content="⛶" FontSize="12" Margin="0,0,6,0" ToolTip="$([Security.SecurityElement]::Escape((T 'tray.plein_ecran')))"/>
          <Button x:Name="Fermer" Style="{StaticResource Secondaire}" Padding="10,4" Content="✕" FontSize="12"/>
        </StackPanel>
      </Grid>
      <StackPanel x:Name="Onglets" DockPanel.Dock="Top" Orientation="Horizontal" Margin="24,8,24,10"/>
      <ScrollViewer VerticalScrollBarVisibility="Auto" Margin="12,0,6,16" Padding="12,0,10,0">
        <StackPanel x:Name="Contenu" MaxWidth="1100"/>
      </ScrollViewer>
    </DockPanel>
  </Border>
</Window>
"@

$lecteur = New-Object System.Xml.XmlNodeReader $xaml
$script:W = [Windows.Markup.XamlReader]::Load($lecteur)
$W.Add_MouseLeftButtonDown({ param($s, $e) if ($e.ButtonState -eq 'Pressed') { try { $script:W.DragMove() } catch { } } })
$W.FindName('Fermer').Add_Click({ $script:Deverrouille = $false; $script:W.Hide() })
$script:Contenu = $W.FindName('Contenu')
$script:Onglets = $W.FindName('Onglets')
$script:logoPath = Join-Path $script:Dossier 'vision.png'
if (Test-Path $script:logoPath) {
    try {
        $bmp = New-Object System.Windows.Media.Imaging.BitmapImage
        $bmp.BeginInit(); $bmp.UriSource = New-Object Uri($script:logoPath); $bmp.CacheOption = 'OnLoad'; $bmp.EndInit()
        $W.FindName('Logo').Source = $bmp
        $W.Icon = $bmp
    } catch { }
}
$W.Add_Closing({ param($s, $e) if ($s -ne $script:W) { return }; $e.Cancel = $true; $script:Deverrouille = $false; $script:W.Hide() })
# Sans cela, une fenêtre WPF ouverte depuis une boucle Windows Forms ne reçoit pas les frappes : impossible d'écrire un mot.
try { Add-Type -AssemblyName WindowsFormsIntegration; [System.Windows.Forms.Integration.ElementHost]::EnableModelessKeyboardInterop($W) } catch { }
$W.FindName('Plein').Add_Click({ $script:W.WindowState = $(if ($script:W.WindowState -eq 'Maximized') { 'Normal' } else { 'Maximized' }) })
try { OeilInstaller $W.FindName('Oeil') } catch { }
$W.Add_IsVisibleChanged({ param($s, $e) if ($s -eq $script:W -and $s.IsVisible) { $script:OeilTic.Start() } })
}
CreerFenetre

function Pinceau([string]$cle) { return $script:W.Resources[$cle] }
function Txt([string]$t, [double]$taille = 13, [string]$couleur = 'Texte', [bool]$gras = $false, [string]$marge = '0') {
    $tb = New-Object System.Windows.Controls.TextBlock
    $tb.Text = $t; $tb.FontSize = $taille; $tb.Foreground = (Pinceau $couleur); $tb.TextWrapping = 'Wrap'
    if ($gras) { $tb.FontWeight = 'SemiBold' }
    $m = @([string]$marge -split ',' | ForEach-Object { [double]$_ })
    if ($m.Count -ge 4) { $tb.Margin = [System.Windows.Thickness]::new($m[0], $m[1], $m[2], $m[3]) } else { $tb.Margin = [System.Windows.Thickness]::new($m[0]) }
    return $tb
}
function Section([string]$t) {
    $tb = Txt $t.ToUpper() 11 'Or' $true '4,14,0,8'
    $tb.FontWeight = 'Bold'
    return $tb
}
function Carte([string]$fond = 'Carte') {
    $b = New-Object System.Windows.Controls.Border
    $b.Background = (Pinceau $fond); $b.CornerRadius = [System.Windows.CornerRadius]::new(18)
    $b.Padding = [System.Windows.Thickness]::new(18, 16, 18, 16); $b.Margin = [System.Windows.Thickness]::new(0, 0, 0, 12)
    $sp = New-Object System.Windows.Controls.StackPanel
    $b.Child = $sp
    return $b
}
function Bouton([string]$t, [string]$style = 'Secondaire', $action = $null, $tag = $null) {
    $b = New-Object System.Windows.Controls.Button
    $b.Content = $t; $b.Style = $script:W.Resources[$style]; $b.Margin = [System.Windows.Thickness]::new(0, 0, 8, 0); $b.Tag = $tag
    if ($action) { $b.Add_Click($action) }
    return $b
}
function Chip([string]$t, [string]$couleur) {
    $b = New-Object System.Windows.Controls.Border
    $b.Background = (Pinceau 'Haute'); $b.CornerRadius = [System.Windows.CornerRadius]::new(10); $b.Padding = [System.Windows.Thickness]::new(10, 4, 10, 4)
    $b.VerticalAlignment = 'Top'
    $tb = Txt $t 11 $couleur $true; $tb.FontWeight = 'Bold'; $b.Child = $tb
    return $b
}
function Rangee() { $sp = New-Object System.Windows.Controls.StackPanel; $sp.Orientation = 'Horizontal'; return $sp }
function Champ([string]$indice) {
    $tb = New-Object System.Windows.Controls.TextBox
    $tb.Style = $script:W.Resources['Champ']; $tb.Tag = $indice; $tb.Text = $indice; $tb.Foreground = (Pinceau 'Texte3')
    $tb.Margin = [System.Windows.Thickness]::new(0, 0, 0, 10)
    $tb.Add_GotFocus({ param($s, $e) if ($s.Text -eq $s.Tag) { $s.Text = ''; $s.Foreground = (Pinceau 'Texte') } })
    $tb.Add_LostFocus({ param($s, $e) if (-not $s.Text) { $s.Text = $s.Tag; $s.Foreground = (Pinceau 'Texte3') } })
    return $tb
}
function Valeur($tb) { if ($tb.Text -eq $tb.Tag) { return '' } else { return $tb.Text.Trim() } }
function Separateur() {
    $b = New-Object System.Windows.Controls.Border; $b.Height = 1; $b.Background = (Pinceau 'Ligne'); $b.Margin = [System.Windows.Thickness]::new(0, 8, 0, 8); return $b
}
function Deux($gauche, $droite) {
    # Une ligne : contenu à gauche, quelque chose à droite.
    $g = New-Object System.Windows.Controls.Grid
    $c1 = New-Object System.Windows.Controls.ColumnDefinition; $c1.Width = [System.Windows.GridLength]::new(1, 'Star')
    $c2 = New-Object System.Windows.Controls.ColumnDefinition; $c2.Width = [System.Windows.GridLength]::Auto
    $g.ColumnDefinitions.Add($c1); $g.ColumnDefinitions.Add($c2)
    [System.Windows.Controls.Grid]::SetColumn($droite, 1)
    $droite.VerticalAlignment = 'Center'; $droite.Margin = [System.Windows.Thickness]::new(12, 0, 0, 0)
    $g.Children.Add($gauche) | Out-Null; $g.Children.Add($droite) | Out-Null
    return $g
}

# ------------------------------------------------------------------ Verrou de la fenêtre
# À l'ouverture, Vision peut demander Windows Hello (empreinte, visage, code Windows). Le verrou se remet dès
# que la fenêtre est fermée. Faille 2.2-1 : plus de code propre à Vision sur le PC. Il se choisissait
# dans l'onglet Réglages, ouvert à tous, et libérait aussi les messages bloquants : un Demandeur posait le sien.
# Le code de gestion se règle désormais dans Home Assistant, qui le vérifie (« Libérer » d'un message).
$script:Deverrouille = $false
$script:VerrouAffiche = $false
function HelloAttendre($operation, [Type]$type) {
    Add-Type -AssemblyName System.Runtime.WindowsRuntime
    $methode = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.IsGenericMethod -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -like 'IAsyncOperation*' } | Select-Object -First 1
    $tache = $methode.MakeGenericMethod($type).Invoke($null, @($operation))
    # La fenêtre continue de vivre pendant que Windows pose sa question.
    $fin = (Get-Date).AddSeconds(90)
    while (-not $tache.IsCompleted -and (Get-Date) -lt $fin) { [System.Windows.Forms.Application]::DoEvents(); Start-Sleep -Milliseconds 40 }
    if (-not $tache.IsCompleted -or $tache.IsFaulted) { return $null }
    return $tache.Result
}
function HelloDisponible() {
    if ($null -ne $script:HelloVu) { return $script:HelloVu }
    $script:HelloVu = $false
    try {
        [void][Windows.Security.Credentials.UI.UserConsentVerifier, Windows.Security.Credentials.UI, ContentType = WindowsRuntime]
        $r = HelloAttendre ([Windows.Security.Credentials.UI.UserConsentVerifier]::CheckAvailabilityAsync()) ([Windows.Security.Credentials.UI.UserConsentVerifierAvailability])
        $script:HelloVu = ([string]$r -eq 'Available')
    } catch { }
    return $script:HelloVu
}
function HelloDemander() {
    if ($script:HelloEnCours) { return $false }
    $script:HelloEnCours = $true
    try {
        $r = HelloAttendre ([Windows.Security.Credentials.UI.UserConsentVerifier]::RequestVerificationAsync((T 'tray.ouvrir_vision'))) ([Windows.Security.Credentials.UI.UserConsentVerificationResult])
        return ([string]$r -eq 'Verified')
    } catch { return $false } finally { $script:HelloEnCours = $false }
}
function Deverrouiller() { $script:Deverrouille = $true; $script:VerrouAffiche = $false; Rafraichir }
function PageVerrou() {
    $script:VerrouAffiche = $true
    $carte = Carte; $carte.MaxWidth = 460; $carte.Margin = [System.Windows.Thickness]::new(0, 30, 0, 0); $carte.HorizontalAlignment = 'Center'
    $carte.Child.Children.Add((Txt (T 'verrou.titre') 20 'Texte' $true '0,0,0,4')) | Out-Null
    $carte.Child.Children.Add((Txt (T 'verrou.hello') 12 'Texte2' $false '0,0,0,14')) | Out-Null
    $bh = Bouton (T 'verrou.bouton_hello') 'Primaire' { if (HelloDemander) { Deverrouiller } else { try { $script:W.Activate() | Out-Null } catch { } } }
    $bh.HorizontalAlignment = 'Left'; $bh.Margin = [System.Windows.Thickness]::new(0, 0, 0, 12)
    $carte.Child.Children.Add($bh) | Out-Null
    $Contenu.Children.Add($carte) | Out-Null
}

# ------------------------------------------------------------------ Pages
$script:Page = 'maison'
$script:Vue = $null          # une sous-vue ouverte (fermés, planning, catégories) : @{ type; appareil }

function Rafraichir() {
    $etat = Lire 'etat.json'
    $ferme = ($null -ne $etat -and [bool](Prop $etat 'ferme' $false))
    $W.FindName('ChipEtatTexte').Text = $(if ($ferme) { (T 'etat.ferme') } else { (T 'etat.ouvert') })
    $W.FindName('ChipEtatTexte').Foreground = (Pinceau $(if ($ferme) { 'Rouge' } else { 'Vert' }))
    $lieu = NomLieu
    $W.FindName('SousTitre').Text = (T 'tray.supervision' @{ lieu = $lieu })
    # Verrouillée : ni onglets ni contenu tant que Windows Hello n'a pas ouvert.
    $rv = SvReglages
    if ($rv.verrou -and -not $script:Deverrouille -and (HelloDisponible)) {
        if ($script:VerrouAffiche) { return }
        $Onglets.Children.Clear(); $Contenu.Children.Clear(); PageVerrou; return
    }
    $script:VerrouAffiche = $false
    $parent = Test-Path (Join-Path $script:Dossier 'maison.json')
    if (-not $parent -and $script:Page -eq 'maison') { $script:Page = 'moi' }
    $Onglets.Children.Clear()
    $defsOnglets = @()
    if ($parent) { $defsOnglets += , @('maison', $lieu) }
    $defsOnglets += , @('moi', (T 'tray.onglet_moi')); $defsOnglets += , @('reglages', (T 'tray.onglet_reglages'))
    if ($true) {
        foreach ($def in $defsOnglets) {
            $t = New-Object System.Windows.Controls.Primitives.ToggleButton
            $t.Content = $def[1]; $t.Style = $W.Resources['Onglet']; $t.Tag = $def[0]; $t.IsChecked = ($script:Page -eq $def[0])
            $t.Add_Click({ param($s, $e) $script:Page = [string]$s.Tag; $script:Vue = $null; Rafraichir })
            $Onglets.Children.Add($t) | Out-Null
        }
    }
    $Contenu.Children.Clear()
    if ($script:Vue) { PageSousVue; return }
    if ($script:Page -eq 'maison') { PageMaison } elseif ($script:Page -eq 'reglages') { PageReglages } else { PageMoi $etat $ferme }
}

function Retour([string]$titre) {
    $r = Rangee
    $r.Margin = [System.Windows.Thickness]::new(0, 0, 0, 10)
    $r.Children.Add((Bouton '←' 'Secondaire' { $script:Vue = $(if ($script:Vue -and $script:Vue.retour) { $script:Vue.retour } else { $null }); Rafraichir })) | Out-Null
    $t = Txt $titre 18 'Texte' $true; $t.VerticalAlignment = 'Center'
    $r.Children.Add($t) | Out-Null
    $Contenu.Children.Add($r) | Out-Null
}

# ---- La maison -------------------------------------------------------------
function ResumeAppareil($a) {
    $d = Prop $a 'derogation'
    if ($d) {
        $fin = [double](Prop $d 'fin' 0)
        $ferme = ([string](Prop $d 'mode') -eq 'locked')
        if ($fin -gt 0) {
            $h = Format-Heure ([DateTimeOffset]::FromUnixTimeSeconds([long]$fin))
            if ($ferme) { return (T 'resume.ferme_gestionnaire_jusqua' @{ heure = $h }) } else { return (T 'resume.ouvert_gestionnaire_jusqua' @{ heure = $h }) }
        }
        if ($ferme) { return (T 'resume.ferme_gestionnaire_creneau') } else { return (T 'resume.ouvert_gestionnaire_creneau') }
    }
    if ([bool](Prop $a 'verrouille' $false)) { $h = Heure ([string](Prop $a 'prochain')); if ($h) { return (T 'resume.reouverture' @{ heure = $h }) } else { return (T 'resume.ferme_planning') } }
    $h = Heure ([string](Prop $a 'prochain_verrou'))
    if ($h) { return (T 'resume.fermeture_prevue' @{ heure = $h }) } else { return (T 'resume.aucune_fermeture') }
}

function PageMaison() {
    $maison = Lire 'maison.json'
    if ($null -eq $maison) { $Contenu.Children.Add((Txt (T 'maison.chargement') 13 'Texte2')) | Out-Null; return }
    $demandes = @(Prop $maison 'demandes' @())
    # Module Demandes coupé : la section disparaît (Home Assistant garde ce qui attendait).
    if ($demandes.Count -gt 0 -and (ModuleActif 'demandes')) {
        $Contenu.Children.Add((Section (T 'maison.demandes'))) | Out-Null
        foreach ($d in $demandes) {
            $c = Carte 'Haute'; $sp = $c.Child
            $sp.Children.Add((Txt (T 'maison.demande_de' @{ prenom = [string](Prop $d 'prenom'); libelle = [string](Prop $d 'libelle') }) 15 'Texte' $true)) | Out-Null
            $det = @()
            if (Prop $d 'lien') { $det += [string](Prop $d 'lien') }
            if (Prop $d 'motif') { $det += (T 'maison.motif_cite' @{ motif = [string](Prop $d 'motif') }) }
            if (Prop $d 'raison') { $det += (T 'maison.ferme_car' @{ raison = [string](Prop $d 'raison') }) }
            $duree = [string](Prop $d 'duree'); $cats = @(Prop $d 'etiquettes' @())
            switch ($duree) {
                '1h' { $det += (T 'maison.souhait_1h') }
                'toujours' { $det += (T 'maison.souhait_toujours') }
                'categorie' { $det += $(if ($cats.Count) { (T 'maison.souhait_categorie_liste' @{ categories = ($cats -join ', ') }) } else { (T 'maison.souhait_categorie') }) }
            }
            if ($det.Count) { $sp.Children.Add((Txt ($det -join '   ') 12 'Texte2' $false '0,4,0,0')) | Out-Null }
            $r = Rangee; $r.Margin = [System.Windows.Thickness]::new(0, 12, 0, 0)
            # 1.92.0 (refonte des autorisations, HA 2.5.0) : 15 min, 1 h, Exception, Toujours disponible, (Catégorie), Refuser.
            # (texte, décision, minutes, style, mode)
            $choix = @(
                @((T 'maison.bouton_15'), 'temporaire', 15, 'Secondaire', ''),
                @((T 'maison.bouton_1h'), 'temporaire', 60, $(if ($duree -eq '1h' -or -not $duree) { 'Primaire' } else { 'Secondaire' }), ''),
                @((T 'maison.bouton_exception'), 'accorder', 0, $(if ($duree -eq 'toujours') { 'Primaire' } else { 'Secondaire' }), 'exception'),
                @((T 'maison.bouton_toujours_dispo'), 'accorder', 0, 'Secondaire', 'toujours'))
            if ($cats.Count) { $choix += ,@((T 'maison.bouton_categorie'), 'categorie', 0, $(if ($duree -eq 'categorie') { 'Primaire' } else { 'Secondaire' }), '') }
            $choix += ,@((T 'maison.bouton_refuser'), 'non', 0, 'Danger', '')
            foreach ($def in $choix) {
                $r.Children.Add((Bouton $def[0] $def[3] {
                    param($s, $e); $t = $s.Tag
                    Agir @{ action = 'acces'; pc = [string]$t.pc; demande = [string]$t.id; decision = [string]$t.decision; minutes = [int]$t.minutes; mode = [string]$t.mode }
                    $s.Parent.IsEnabled = $false
                    # Répondu : la demande quitte l'écran tout de suite, sans attendre le prochain relevé.
                    try { $s.Parent.Parent.Parent.Visibility = 'Collapsed' } catch { }
                } @{ pc = (Prop $d 'pc'); id = (Prop $d 'id'); decision = $def[1]; minutes = $def[2]; mode = $def[4] })) | Out-Null
            }
            $sp.Children.Add($r) | Out-Null
            $sp.Children.Add((Txt (T 'maison.aide_choix') 11 'Texte3' $false '0,8,0,0')) | Out-Null
            $Contenu.Children.Add($c) | Out-Null
        }
    }
    # Les actifs : une carte par personne, tous ses appareils dessus. Un clic ouvre sa fiche.
    $Contenu.Children.Add((Section (T 'maison.les_actifs'))) | Out-Null
    $groupes = @(GroupesMaison $maison)
    if ($groupes.Count -eq 0) { $Contenu.Children.Add((Txt (T 'maison.aucun_inscrit') 13 'Texte2')) | Out-Null }
    foreach ($g in $groupes) {
        $c = Carte; $sp = $c.Child
        $c.Cursor = [System.Windows.Input.Cursors]::Hand; $c.Tag = [string]$g.entite
        $c.Add_MouseLeftButtonUp({ param($s, $e) $script:Vue = @{ type = 'personne'; personne = [string]$s.Tag }; Rafraichir })
        $enLigne = @($g.appareils | Where-Object { [bool](Prop $_ 'en_ligne' $false) })
        $ouverts = @($enLigne | Where-Object { -not [bool](Prop $_ 'verrouille' $false) })
        $etatTxt = (T 'etat.hors_ligne'); $coul = 'Texte3'
        if ($ouverts.Count -gt 0) { $etatTxt = (T 'etat.ouvert'); $coul = 'Vert' } elseif ($enLigne.Count -gt 0) { $etatTxt = (T 'etat.ferme'); $coul = 'Rouge' }
        $gauche = New-Object System.Windows.Controls.StackPanel
        $gauche.Children.Add((Txt ([string]$g.prenom) 17 'Texte' $true)) | Out-Null
        $n = $g.appareils.Count
        $gauche.Children.Add((Txt $(if ($n -eq 0) { (T 'maison.aucun_appareil') } else { (Tn 'maison.appareils' $n) }) 12 'Texte2')) | Out-Null
        $droite = Rangee
        $droite.Children.Add((Chip $etatTxt $coul)) | Out-Null
        $fl = Txt '›' 20 'Texte3'; $fl.Margin = [System.Windows.Thickness]::new(12, -4, 0, 0); $droite.Children.Add($fl) | Out-Null
        $sp.Children.Add((Deux $gauche $droite)) | Out-Null
        foreach ($a in $g.appareils) {
            $et = EtatAppareil $a
            $ligne = (IconeAppareil $a) + [string](Prop $a 'nom') + '  ·  ' + $et[0].ToLower()
            $foc = [string](Prop $a 'focus_libelle'); if (-not $foc) { $foc = [string](Prop $a 'focus') }
            if ([bool](Prop $a 'en_ligne' $false) -and $foc -and -not [bool](Prop $a 'verrouille' $false)) { $ligne += '  ·  ' + $foc }
            $d = Prop $a 'derogation'
            if ($d -and [string](Prop $d 'mode') -eq 'open') { $ligne += '  ·  ' + (ResumeAppareil $a).ToLower() }
            $sp.Children.Add((Txt $ligne 12 'Texte2' $false '0,6,0,0')) | Out-Null
        }
        $Contenu.Children.Add($c) | Out-Null
    }
    $Contenu.Children.Add((Txt (T 'maison.actualise' @{ heure = (Format-Heure (Get-Date) -Locale) }) 11 'Texte3' $false '4,4,0,0')) | Out-Null
}

# ---- Les actifs : personnes et appareils -----------------------------------
function IconeAppareil($a) { if ([bool](Prop $a 'android' $false)) { return '📱 ' } else { return '💻 ' } }
function EtatAppareil($a) {
    if (-not [bool](Prop $a 'en_ligne' $false)) { return @((T 'etat.hors_ligne'), 'Texte3') }
    if ([bool](Prop $a 'verrouille' $false)) { return @((T 'etat.ferme'), 'Rouge') }
    return @((T 'etat.ouvert'), 'Vert')
}
# Les appareils d'une personne : la liste que donne Home Assistant (les siens, puis ceux qu'elle partage).
function AppareilsDe($maison, $p) {
    $tous = @(Prop $maison 'appareils' @())
    $ent = [string](Prop $p 'entite')
    $ids = @(Prop $p 'appareils' @())
    if ($ids.Count -gt 0) {
        $sortie = @()
        foreach ($i in $ids) { foreach ($a in $tous) { if ([string](Prop $a 'id') -eq [string]$i) { $sortie += $a } } }
        return $sortie
    }
    return @($tous | Where-Object { [string](Prop $_ 'personne') -eq $ent })
}
# Une entrée par personne, puis les appareils que personne ne revendique.
function GroupesMaison($maison) {
    $sortie = @(); $pris = @{}
    foreach ($p in @(Prop $maison 'personnes' @())) {
        $siens = @(AppareilsDe $maison $p)
        foreach ($a in $siens) { $pris[[string](Prop $a 'id')] = $true }
        $sortie += , @{ entite = [string](Prop $p 'entite'); prenom = [string](Prop $p 'prenom'); personne = $p; appareils = $siens }
    }
    # Une personne de Home Assistant qui n'a encore aucun appareil : c'est depuis sa fiche qu'on lui en donne un.
    foreach ($m in @(Prop $maison 'maison' @())) {
        $e = [string](Prop $m 'entite')
        if (-not @($sortie | Where-Object { $_.entite -eq $e }).Count) { $sortie += , @{ entite = $e; prenom = [string](Prop $m 'prenom'); personne = $m; appareils = @() } }
    }
    $seuls = @(@(Prop $maison 'appareils' @()) | Where-Object { -not $pris.ContainsKey([string](Prop $_ 'id')) })
    if ($seuls.Count -gt 0) { $sortie += , @{ entite = ''; prenom = (T 'maison.sans_personne'); personne = $null; appareils = $seuls } }
    return $sortie
}
function PrenomDe($maison, [string]$entite) {
    foreach ($m in @(Prop $maison 'maison' @())) { if ([string](Prop $m 'entite') -eq $entite) { return [string](Prop $m 'prenom') } }
    return ($entite -split '\.')[-1]
}
function PartageTexte($maison, $a) {
    if ([bool](Prop $a 'partage_tous' $false)) { return (T 'partage.a_tout_le_monde') }
    $qui = @(Prop $a 'proprietaires' @())
    if ($qui.Count -gt 1) { return (T 'partage.partage_avec' @{ noms = ((@($qui | ForEach-Object { PrenomDe $maison ([string]$_) })) -join ', ') }) }
    return ''
}
function DureeMin([int]$m) {
    if ($m -lt 60) { return (T 'duree.min' @{ m = $m }) }
    $h = [Math]::Floor($m / 60); $r = $m % 60
    if ($r) { return (T 'duree.h_min' @{ h = $h; m = ('{0:00}' -f $r) }) } else { return (T 'duree.h' @{ h = $h }) }
}

# Après un geste, l'agent envoie puis relit la maison : la fiche se redessine d'elle-même quelques secondes plus tard.
$script:ApresGeste = New-Object System.Windows.Threading.DispatcherTimer
$script:ApresGeste.Interval = [TimeSpan]::FromSeconds(4)
$script:ApresGesteReste = 0
$script:ApresGeste.Add_Tick({
    try {
        $script:ApresGesteReste = $script:ApresGesteReste - 1
        if ($script:ApresGesteReste -le 0) { $script:ApresGeste.Stop() }
        if ($script:W.IsVisible -and $script:Vue -and @('personne', 'partage', 'ajout') -contains [string]$script:Vue.type -and -not $script:Vue.temps) { Rafraichir }
    } catch { }
})
function RelireBientot() { $script:ApresGesteReste = 3; $script:ApresGeste.Stop(); $script:ApresGeste.Start() }

# Donner du temps : la durée d'abord, l'appareil ensuite. Un seul appareil : pas de seconde question.
function DonnerTemps([int]$minutes, $ids) {
    $ids = @($ids | ForEach-Object { [string]$_ })
    if ($ids.Count -eq 0 -or $minutes -lt 1) { return }
    Agir @{ action = 'temps'; pc = $ids[0]; appareils = $ids; minutes = $minutes }
    $script:Vue.temps = $null
    $script:Vue.dit = (T 'temps.donne' @{ duree = (DureeMin $minutes) })
    Rafraichir; RelireBientot
}
function ChoisirDuree([int]$minutes, [bool]$autre) {
    $maison = Lire 'maison.json'
    $g = @(GroupesMaison $maison) | Where-Object { $_.entite -eq [string]$script:Vue.personne } | Select-Object -First 1
    if (-not $g) { return }
    $siens = @($g.appareils)
    if (-not $autre -and $siens.Count -eq 1) { DonnerTemps $minutes @([string](Prop $siens[0] 'id')); return }
    # Cochés d'avance : ce qui sert en ce moment ; sinon tout.
    $choix = @{}
    if ($script:Vue.temps) { $choix = $script:Vue.temps.choix }
    else {
        $actifs = @($siens | Where-Object { [bool](Prop $_ 'en_ligne' $false) -and [int](Prop $_ 'inactif_s' 0) -le 300 })
        if ($actifs.Count -eq 0) { $actifs = $siens }
        foreach ($a in $actifs) { $choix[[string](Prop $a 'id')] = $true }
    }
    $script:Vue.temps = @{ minutes = $minutes; autre = $autre; choix = $choix }
    $script:Vue.dit = ''
    Rafraichir
}

function VuePersonne() {
    $maison = Lire 'maison.json'
    $g = @(GroupesMaison $maison) | Where-Object { $_.entite -eq [string]$script:Vue.personne } | Select-Object -First 1
    if (-not $g) { $Contenu.Children.Add((Txt (T 'personne.plus_aucun_appareil') 13 'Texte2')) | Out-Null; return }
    $siens = @($g.appareils); $prenom = [string]$g.prenom; $ent = [string]$g.entite
    $ici = @{ type = 'personne'; personne = $ent }

    # --- Ajouter du temps (sans plages ni temps d'écran, il n'y a rien à prolonger)
    if ($siens.Count -gt 0 -and ((ModuleActif 'plages') -or (ModuleActif 'temps'))) {
        $Contenu.Children.Add((Section (T 'personne.ajouter_temps'))) | Out-Null
        $c = Carte; $sp = $c.Child
        $t = $script:Vue.temps
        $r = Rangee
        foreach ($def in @(@((T 'personne.plus_30'), 30), @((T 'personne.plus_1h'), 60))) {
            $style = if ($t -and -not $t.autre -and [int]$t.minutes -eq [int]$def[1]) { 'Primaire' } else { 'Secondaire' }
            $r.Children.Add((Bouton $def[0] $style { param($s, $e) ChoisirDuree ([int]$s.Tag) $false } $def[1])) | Out-Null
        }
        $r.Children.Add((Bouton (T 'personne.autre') $(if ($t -and $t.autre) { 'Primaire' } else { 'Secondaire' }) { ChoisirDuree 0 $true })) | Out-Null
        $sp.Children.Add($r) | Out-Null
        if ($t) {
            $sp.Children.Add((Separateur)) | Out-Null
            $champ = $null
            if ($t.autre) {
                $sp.Children.Add((Txt (T 'personne.combien_minutes') 12 'Texte2' $false '0,4,0,6')) | Out-Null
                $champ = Champ (T 'personne.exemple_45')
                $champ.Width = 180; $champ.HorizontalAlignment = 'Left'
                $sp.Children.Add($champ) | Out-Null
            }
            if ($siens.Count -gt 1) {
                $sp.Children.Add((Txt (T 'personne.quel_appareil') 13 'Texte' $true '0,4,0,2')) | Out-Null
                foreach ($a in $siens) {
                    $id = [string](Prop $a 'id')
                    $lib = (IconeAppareil $a) + [string](Prop $a 'nom')
                    if (-not [bool](Prop $a 'en_ligne' $false)) { $lib += (T 'personne.hors_ligne_suffixe') }
                    $sp.Children.Add((Coche $lib ([bool]$t.choix[$id]) { param($s, $e) $script:Vue.temps.choix[[string]$s.Tag] = [bool]$s.IsChecked } $id)) | Out-Null
                }
            }
            $erreur = Txt '' 12 'Rouge' $false '0,6,0,0'; $erreur.Visibility = 'Collapsed'
            $r2 = Rangee; $r2.Margin = [System.Windows.Thickness]::new(0, 10, 0, 0)
            $libelle = if ($t.autre) { (T 'personne.donner_ce_temps') } else { (T 'personne.donner_duree' @{ duree = (DureeMin ([int]$t.minutes)) }) }
            $r2.Children.Add((Bouton $libelle 'Primaire' {
                param($s, $e); $x = $s.Tag
                $min = [int]$script:Vue.temps.minutes
                if ($x.champ) {
                    $min = 0; [void][int]::TryParse((Valeur $x.champ), [ref]$min)
                    if ($min -lt 5 -or $min -gt 720) { $x.erreur.Text = (T 'personne.duree_invalide'); $x.erreur.Visibility = 'Visible'; return }
                }
                $ids = @($script:Vue.temps.choix.GetEnumerator() | Where-Object { $_.Value } | ForEach-Object { [string]$_.Key })
                if ($x.seul) { $ids = @($x.seul) }
                if ($ids.Count -eq 0) { $x.erreur.Text = (T 'personne.coche_un_appareil'); $x.erreur.Visibility = 'Visible'; return }
                DonnerTemps $min $ids
            } @{ champ = $champ; erreur = $erreur; seul = $(if ($siens.Count -eq 1) { [string](Prop $siens[0] 'id') } else { $null }) })) | Out-Null
            $r2.Children.Add((Bouton (T 'commun.annuler') 'Secondaire' { $script:Vue.temps = $null; Rafraichir })) | Out-Null
            $sp.Children.Add($r2) | Out-Null
            $sp.Children.Add($erreur) | Out-Null
        }
        if ($script:Vue.dit) { $sp.Children.Add((Txt ([string]$script:Vue.dit) 12 'Vert' $false '0,10,0,0')) | Out-Null }
        # Le temps déjà accordé, qu'on peut reprendre.
        foreach ($a in $siens) {
            $d = Prop $a 'derogation'
            if (-not $d -or [string](Prop $d 'mode') -ne 'open') { continue }
            $sp.Children.Add((Separateur)) | Out-Null
            $ligne = Txt ((IconeAppareil $a) + (T 'commun.nom_valeur' @{ nom = [string](Prop $a 'nom'); valeur = (ResumeAppareil $a).ToLower() })) 12 'Texte2'
            $ligne.VerticalAlignment = 'Center'
            $sp.Children.Add((Deux $ligne (Bouton (T 'commun.retirer') 'Secondaire' {
                param($s, $e)
                Agir @{ action = 'annuler'; pc = [string]$s.Tag; minutes = 0 }
                $s.Content = '✓'; $s.IsEnabled = $false; RelireBientot
            } ([string](Prop $a 'id'))))) | Out-Null
        }
        $Contenu.Children.Add($c) | Out-Null
    }

    # --- Ses appareils
    $Contenu.Children.Add((Section $(if ($ent) { (T 'personne.ses_appareils') } else { (T 'personne.appareils_sans_personne') }))) | Out-Null
    if ($siens.Count -eq 0) { $Contenu.Children.Add((Txt (T 'personne.aucun_appareil') 13 'Texte2' $false '4,0,0,10')) | Out-Null }
    foreach ($a in $siens) {
        $c = Carte; $sp = $c.Child
        $et = EtatAppareil $a
        $gauche = New-Object System.Windows.Controls.StackPanel
        $gauche.Children.Add((Txt ((IconeAppareil $a) + [string](Prop $a 'nom')) 15 'Texte' $true)) | Out-Null
        $part = PartageTexte $maison $a
        if ($part) { $gauche.Children.Add((Txt $part 11 'Texte3' $false '0,2,0,0')) | Out-Null }
        $sp.Children.Add((Deux $gauche (Chip $et[0] $et[1]))) | Out-Null
        $l2 = ResumeAppareil $a
        $u = Prop $a 'usage'
        if ($u) { $l2 = $l2 + (T 'personne.ecran_aujourdhui' @{ min = [int](Prop $u 'actif' 0) }) }
        $foc = [string](Prop $a 'focus_libelle'); if (-not $foc) { $foc = [string](Prop $a 'focus') }
        if ([bool](Prop $a 'en_ligne' $false) -and $foc) { $l2 = $l2 + (T 'personne.en_ce_moment' @{ appli = $foc }) }
        $sp.Children.Add((Txt $l2 12 'Texte2' $false '0,10,0,0')) | Out-Null
        $r = New-Object System.Windows.Controls.WrapPanel; $r.Margin = [System.Windows.Thickness]::new(0, 12, 0, 0)
        $gestes = @()
        if (-not [bool](Prop $a 'verrouille' $false)) { $gestes += , @((T 'personne.fermer'), 'fermer', 'Danger') }
        if (Prop $a 'derogation') { $gestes += , @((T 'personne.revenir_planning'), 'annuler', 'Secondaire') }
        foreach ($def in $gestes) {
            $b = Bouton $def[0] $def[2] {
                param($s, $e); $x = $s.Tag
                Agir @{ action = [string]$x.action; pc = [string]$x.pc; minutes = 0 }
                $s.Content = '✓'; $s.IsEnabled = $false; RelireBientot
            } @{ action = $def[1]; pc = (Prop $a 'id') }
            $b.Margin = [System.Windows.Thickness]::new(0, 0, 8, 8); $r.Children.Add($b) | Out-Null
        }
        # Le planning est celui de la personne : il s'ouvre plus bas, avec ses règles. Un appareil qui n'est pas
        # le sien (partagé, ou sans personne) garde le sien, ici.
        # La virgule compte : sans elle PowerShell aplatit la liste, et le bouton se découpait en « C » et « f ».
        # Seulement ce que les modules actifs permettent (VueAutorisee) ; « À qui est-il ? » est du socle.
        $toutes = @(, @((T 'personne.vue_fermes'), 'fermes'))
        if ($null -ne (Prop $a 'logiciels')) { $toutes += , @((T 'personne.vue_logiciels'), 'logiciels') }
        if (-not $ent -or [string](Prop $a 'personne') -ne $ent) { $toutes += , @((T 'personne.vue_son_planning'), 'planning') }
        $toutes += , @((T 'personne.vue_mot'), 'mot'); $toutes += , @((T 'personne.vue_partage'), 'partage')
        $vues = @()
        foreach ($def in $toutes) { if (VueAutorisee ([string]$def[1])) { $vues += , $def } }
        foreach ($def in $vues) {
            $b = Bouton $def[0] 'Secondaire' {
                param($s, $e); $x = $s.Tag
                $script:Vue = @{ type = $x.type; appareil = $x.appareil; retour = $x.retour }; Rafraichir
            } @{ type = $def[1]; appareil = $a; retour = $ici }
            $b.Margin = [System.Windows.Thickness]::new(0, 0, 8, 8); $r.Children.Add($b) | Out-Null
        }
        $sp.Children.Add($r) | Out-Null
        $Contenu.Children.Add($c) | Out-Null
    }

    if ($ent) {
        $plus = Bouton (T 'personne.ajouter_appareil') 'Secondaire' {
            param($s, $e); $script:Vue = @{ type = 'ajout'; personne = [string]$s.Tag; retour = @{ type = 'personne'; personne = [string]$s.Tag } }; Rafraichir
        } $ent
        $plus.HorizontalAlignment = 'Left'; $plus.Margin = [System.Windows.Thickness]::new(0, 0, 0, 12)
        $Contenu.Children.Add($plus) | Out-Null
    }

    # --- Ce qui vaut pour la personne, sur tous ses appareils à elle
    if ($ent) {
        $propre = $null
        $principal = [string](Prop $g.personne 'principal')
        foreach ($a in $siens) { if ($principal -and [string](Prop $a 'id') -eq $principal) { $propre = $a } }
        if (-not $propre) { $propre = $siens | Where-Object { [string](Prop $_ 'personne') -eq $ent } | Select-Object -First 1 }
        $Contenu.Children.Add((Section (T 'personne.regles_de' @{ prenom = $prenom }))) | Out-Null
        $c = Carte; $sp = $c.Child
        # Seulement les règles des modules actifs.
        $regles = @()
        foreach ($def in @(@((T 'personne.regle_planning'), 'planning'), @((T 'personne.regle_categories'), 'categories'), @((T 'personne.regle_autorisations'), 'exceptions'), @((T 'personne.regle_temps'), 'temps'))) { if (VueAutorisee ([string]$def[1])) { $regles += , $def } }
        if ($propre -and $regles.Count -eq 0) {
            $sp.Children.Add((Txt (T 'personne.aucune_regle') 12 'Texte2')) | Out-Null
        } elseif ($propre) {
            $sp.Children.Add((Txt (T 'personne.regles_valent' @{ prenom = $prenom }) 12 'Texte2' $false '0,0,0,10')) | Out-Null
            $r = New-Object System.Windows.Controls.WrapPanel
            foreach ($def in $regles) {
                $b = Bouton $def[0] 'Secondaire' {
                    param($s, $e); $x = $s.Tag
                    $script:Vue = @{ type = $x.type; appareil = $x.appareil; retour = $x.retour }; Rafraichir
                } @{ type = $def[1]; appareil = $propre; retour = $ici }
                $b.Margin = [System.Windows.Thickness]::new(0, 0, 8, 8); $r.Children.Add($b) | Out-Null
            }
            $sp.Children.Add($r) | Out-Null
        } else {
            $sp.Children.Add((Txt (T 'personne.pas_en_propre' @{ prenom = $prenom }) 12 'Texte2')) | Out-Null
        }
        $Contenu.Children.Add($c) | Out-Null
    }
    $Contenu.Children.Add((Txt (T 'maison.actualise' @{ heure = (Format-Heure (Get-Date) -Locale) }) 11 'Texte3' $false '4,4,0,0')) | Out-Null
}

# Donner un appareil à une personne : le sien (il suit ses règles et son planning) ou partagé (il garde les siennes).
function VueAjout() {
    $maison = Lire 'maison.json'
    $ent = [string]$script:Vue.personne
    $g = @(GroupesMaison $maison) | Where-Object { $_.entite -eq $ent } | Select-Object -First 1
    if (-not $g) { return }
    $prenom = [string]$g.prenom
    $deja = @{}; foreach ($a in @($g.appareils)) { $deja[[string](Prop $a 'id')] = $true }
    $autres = @(@(Prop $maison 'appareils' @()) | Where-Object { -not $deja.ContainsKey([string](Prop $_ 'id')) })
    $Contenu.Children.Add((Txt (T 'ajout.aide' @{ prenom = $prenom }) 12 'Texte2' $false '0,0,0,10')) | Out-Null
    if ($script:Vue.dit) { $Contenu.Children.Add((Txt ([string]$script:Vue.dit) 12 'Vert' $false '0,0,0,10')) | Out-Null }
    if ($autres.Count -eq 0) {
        $Contenu.Children.Add((Txt (T 'ajout.tous_deja') 13 'Texte2')) | Out-Null
        return
    }
    $c = Carte; $sp = $c.Child; $k = 0
    foreach ($a in $autres) {
        if ($k++ -gt 0) { $sp.Children.Add((Separateur)) | Out-Null }
        $gche = New-Object System.Windows.Controls.StackPanel
        $gche.Children.Add((Txt ((IconeAppareil $a) + [string](Prop $a 'nom')) 14 'Texte' $true)) | Out-Null
        $qui = (T 'ajout.aujourdhui_personne')
        if ([bool](Prop $a 'partage_tous' $false)) { $qui = (T 'ajout.aujourdhui_tous') } elseif ([string](Prop $a 'personne')) { $qui = (T 'ajout.aujourdhui_a' @{ prenom = (PrenomDe $maison ([string](Prop $a 'personne'))) }) }
        $gche.Children.Add((Txt $qui 11 'Texte2')) | Out-Null
        $r = Rangee
        foreach ($def in @(@((T 'ajout.le_sien'), 'principal'), @((T 'ajout.partage'), 'partage'))) {
            $r.Children.Add((Bouton $def[0] 'Secondaire' {
                param($s, $e); $x = $s.Tag
                Agir @{ action = 'attribuer'; pc = [string]$x.pc; personne = [string]$x.personne; mode = [string]$x.mode }
                $s.Content = '✓'; $s.Parent.IsEnabled = $false
                $script:Vue.dit = (T 'ajout.demande')
                RelireBientot
            } @{ pc = (Prop $a 'id'); personne = $ent; mode = $def[1] })) | Out-Null
        }
        $sp.Children.Add((Deux $gche $r)) | Out-Null
    }
    $Contenu.Children.Add($c) | Out-Null
}

# À qui est un appareil : sa personne principale (ses règles s'y appliquent), et ceux qui le partagent.
function VuePartage($a) {
    $maison = Lire 'maison.json'
    $idPc = [string](Prop $a 'id')
    foreach ($x in @(Prop $maison 'appareils' @())) { if ([string](Prop $x 'id') -eq $idPc) { $a = $x } }
    $principale = [string](Prop $a 'personne'); $tous = [bool](Prop $a 'partage_tous' $false)
    $qui = @(Prop $a 'proprietaires' @() | ForEach-Object { [string]$_ })
    $Contenu.Children.Add((Txt (T 'partage.aide') 12 'Texte2' $false '0,0,0,10')) | Out-Null
    $c = Carte; $sp = $c.Child
    $script:VP = @{ pc = $idPc; coches = @(); tous = $null }
    $envoyer = {
        $t = [bool]$script:VP.tous.IsChecked
        $p = @($script:VP.coches | Where-Object { $_.IsChecked -and $_.IsEnabled } | ForEach-Object { [string]$_.Tag })
        if ($t) { $p = @() }
        foreach ($k in $script:VP.coches) { if ([string]$k.Tag -ne $script:VP.principale) { $k.IsEnabled = -not $t } }
        Agir @{ action = 'partage'; pc = [string]$script:VP.pc; personnes = $p; tous = $t }
        RelireBientot
    }
    $script:VP.principale = $principale
    $ct = Coche (T 'partage.tout_le_monde') $tous $envoyer
    $script:VP.tous = $ct
    $sp.Children.Add($ct) | Out-Null
    $sp.Children.Add((Separateur)) | Out-Null
    foreach ($m in @(Prop $maison 'maison' @())) {
        $e = [string](Prop $m 'entite'); $lib = [string](Prop $m 'prenom')
        $estPrincipale = ($e -eq $principale)
        if ($estPrincipale) { $lib += (T 'partage.ses_regles') }
        $k = Coche $lib ($estPrincipale -or (-not $tous -and ($qui -contains $e))) $envoyer $e
        if ($estPrincipale -or $tous) { $k.IsEnabled = $false }
        $script:VP.coches += $k
        $sp.Children.Add($k) | Out-Null
    }
    $Contenu.Children.Add($c) | Out-Null
}

function EnvoyerMot($a) { $script:Vue = @{ type = 'mot'; appareil = $a }; Rafraichir }

function ChampLong([string]$indice) {
    $tb = Champ $indice
    $tb.AcceptsReturn = $true; $tb.TextWrapping = 'Wrap'; $tb.Height = 72; $tb.VerticalContentAlignment = 'Top'
    return $tb
}

# Tout se passe dans la fenêtre : pas de boîte de dialogue à côté.
function VueMot($a) {
    $prenom = [string](Prop $a 'prenom'); if (-not $prenom) { $prenom = [string](Prop $a 'nom') }
    $c = Carte; $sp = $c.Child
    $sp.Children.Add((Txt (T 'mot.aide' @{ prenom = $prenom }) 12 'Texte2' $false '0,0,0,10')) | Out-Null
    $texte = ChampLong (T 'mot.indice')
    $sp.Children.Add($texte) | Out-Null
    $ok = Bouton (T 'mot.envoyer') 'Primaire' {
        param($s, $e); $t = $s.Tag
        $m = Valeur $t.texte
        if (-not $m) { return }
        Agir @{ action = 'message'; pc = [string](Prop $t.appareil 'id'); texte = [string]$m }
        $s.Content = (T 'mot.envoye'); $s.IsEnabled = $false
    } @{ texte = $texte; appareil = $a }
    $ok.HorizontalAlignment = 'Right'
    $sp.Children.Add($ok) | Out-Null
    $Contenu.Children.Add($c) | Out-Null
}

# La demande d'accès de l'enfant : pour qui, pourquoi, et pour combien de temps.
function VueDemande($item) {
    $c = Carte 'Haute'; $sp = $c.Child
    $sp.Children.Add((Txt ([string]$item.nom) 17 'Texte' $true)) | Out-Null
    $sousTitre = [string]$item.type
    if ($item.raison) { $sousTitre += (T 'demande.ferme_car' @{ raison = [string]$item.raison }) }
    $sp.Children.Add((Txt $sousTitre 12 'Texte2' $false '0,2,0,0')) | Out-Null
    $Contenu.Children.Add($c) | Out-Null

    $c2 = Carte; $sp2 = $c2.Child
    $lien = $null
    if ($item.genre -eq 'sites' -and $item.saisie) {
        $sp2.Children.Add((Txt (T 'demande.le_site') 12 'Texte2' $false '0,0,0,6')) | Out-Null
        $lien = Champ (T 'demande.indice_lien')
        if ($item.lien) { $lien.Text = [string]$item.lien; $lien.Foreground = (Pinceau 'Texte') }
        $sp2.Children.Add($lien) | Out-Null
    }
    $sp2.Children.Add((Txt (T 'demande.pourquoi') 12 'Texte2' $false '0,0,0,6')) | Out-Null
    $motif = ChampLong (T 'demande.indice_motif')
    $sp2.Children.Add($motif) | Out-Null
    $sp2.Children.Add((Txt (T 'demande.combien_temps') 12 'Texte2' $false '0,4,0,6')) | Out-Null
    $script:DureeChoisie = '1h'
    $rd = Rangee
    $boutons = @()
    foreach ($def in @(@('1h', (T 'demande.pour_1h')), @('toujours', (T 'demande.en_permanence')), @('categorie', (T 'demande.toute_categorie')))) {
        $t = New-Object System.Windows.Controls.Primitives.ToggleButton
        $t.Content = $def[1]; $t.Style = $W.Resources['Onglet']; $t.Tag = $def[0]; $t.IsChecked = ($def[0] -eq '1h'); $t.Margin = [System.Windows.Thickness]::new(0, 0, 8, 0)
        $t.Add_Click({ param($s, $e) $script:DureeChoisie = [string]$s.Tag; foreach ($x in $s.Parent.Children) { $x.IsChecked = ($x.Tag -eq $s.Tag) } })
        $rd.Children.Add($t) | Out-Null
    }
    $sp2.Children.Add($rd) | Out-Null
    $sp2.Children.Add((Txt (T 'demande.aide_duree') 11 'Texte3' $false '0,8,0,12')) | Out-Null
    $ok = Bouton (T 'demande.demander_acces') 'Primaire' {
        param($s, $e); $t = $s.Tag
        $nom = [string]$t.item.nom; $l = ''
        if ($t.lien) {
            $l = Valeur $t.lien; $h = Hote $l
            if (-not $h -or $h -notlike '*.*') { $t.erreur.Text = (T 'demande.lien_invalide'); $t.erreur.Visibility = 'Visible'; return }
            $nom = $h
        }
        Deposer @{ genre = [string]$t.item.genre; nom = $nom; libelle = $nom; lien = $l; motif = (Valeur $t.motif); duree = [string]$script:DureeChoisie }
        $script:Demandees[[string]$t.item.genre + ':' + $nom] = $true
        $s.Content = (T 'demande.envoyee'); $s.IsEnabled = $false
        $t.erreur.Text = (T 'demande.tu_seras_prevenu'); $t.erreur.Foreground = (Pinceau 'Vert'); $t.erreur.Visibility = 'Visible'
    } @{ item = $item; motif = $motif; lien = $lien; erreur = $null }
    $erreur = Txt '' 12 'Rouge' $false '0,8,0,0'; $erreur.Visibility = 'Collapsed'
    $ok.Tag.erreur = $erreur
    $ok.HorizontalAlignment = 'Right'
    $sp2.Children.Add($ok) | Out-Null
    $sp2.Children.Add($erreur) | Out-Null
    $Contenu.Children.Add($c2) | Out-Null
}

# Chaque sous-vue et le module qui la porte : coupé, elle disparaît (et une vue déjà ouverte le dit).
function VueAutorisee([string]$type) {
    switch ($type) {
        'planning' { return (ModuleActif 'plages') }
        'plage' { return (ModuleActif 'plages') }
        'temps' { return (ModuleActif 'temps') }
        'fermes' { return ((ModuleActif 'blocage') -or (ModuleActif 'filtrage')) }
        'categories' { return ((ModuleActif 'blocage') -or (ModuleActif 'filtrage')) }
        'exceptions' { return ((ModuleActif 'blocage') -or (ModuleActif 'filtrage')) }
        'logiciels' { return ((ModuleActif 'blocage') -or (ModuleActif 'optimisations')) }
        'mot' { return (ModuleActif 'messages') }
        'demande' { return (ModuleActif 'demandes') }
    }
    return $true
}

function PageSousVue() {
    if (-not (VueAutorisee ([string]$script:Vue.type))) {
        $r = Rangee; $r.Margin = [System.Windows.Thickness]::new(0, 0, 0, 10)
        $r.Children.Add((Bouton '←' 'Secondaire' { $script:Vue = $(if ($script:Vue -and $script:Vue.retour) { $script:Vue.retour } else { $null }); Rafraichir })) | Out-Null
        $Contenu.Children.Add($r) | Out-Null
        $Contenu.Children.Add((Txt (T 'vue.coupee') 13 'Texte2')) | Out-Null
        return
    }
    $a = $script:Vue.appareil
    $prenom = ''
    if ($a) { $prenom = [string](Prop $a 'prenom'); if (-not $prenom) { $prenom = [string](Prop $a 'nom') } }
    switch ($script:Vue.type) {
        'personne' {
            $titre = (T 'maison.sans_personne')
            $mj = Lire 'maison.json'
            foreach ($x in (@(Prop $mj 'maison' @()) + @(Prop $mj 'personnes' @()))) { if ([string](Prop $x 'entite') -eq [string]$script:Vue.personne) { $titre = [string](Prop $x 'prenom') } }
            Retour $titre; VuePersonne
        }
        'partage' { Retour (T 'vue.titre_partage' @{ nom = [string](Prop $a 'nom') }); VuePartage $a }
        'ajout' {
            $pour = ''
            foreach ($x in @(Prop (Lire 'maison.json') 'maison' @())) { if ([string](Prop $x 'entite') -eq [string]$script:Vue.personne) { $pour = [string](Prop $x 'prenom') } }
            Retour (T 'vue.titre_ajout' @{ prenom = $pour }); VueAjout
        }
        'fermes' { Retour (T 'vue.titre_fermes' @{ prenom = $prenom }); VueFermes $a }
        'planning' { Retour (T 'vue.titre_planning' @{ prenom = $prenom }); VuePlanning $a }
        'plage' { Retour $(if ($script:Vue.plage) { (T 'vue.titre_plage_modifier') } else { (T 'vue.titre_plage_nouvelle') }); VuePlage $a $script:Vue.plage }
        'logiciels' { Retour (T 'vue.titre_logiciels' @{ nom = [string](Prop $a 'nom') }); VueLogiciels $a }
        'categories' { Retour (T 'vue.titre_categories' @{ prenom = $prenom }); VueCategories $a }
        'exceptions' { Retour (T 'vue.titre_exceptions' @{ prenom = $prenom }); VueExceptions $a }
        'temps' { Retour (T 'vue.titre_temps' @{ prenom = $prenom }); VueTemps $a }
        'mot' { Retour (T 'vue.titre_mot' @{ prenom = $prenom }); VueMot $a }
        'demande' { Retour (T 'demande.demander_acces'); VueDemande $script:Vue.item }
    }
}

function MotifDe($a, [string]$nom) {
    $motifs = @(Prop $a 'motifs' @()); $raisons = Prop $a 'raisons'
    $n = $nom.ToLowerInvariant()
    while ($true) {
        if ($null -ne $raisons -and $raisons.PSObject.Properties[$n]) {
            $i = [int]$raisons.$n
            if ($i -ge 0 -and $i -lt $motifs.Count) { return [string]$motifs[$i] }
            return ''
        }
        if ($n -notlike '*.*') { return '' }
        $n = $n.Substring($n.IndexOf('.') + 1)
    }
}

function VueFermes($a) {
    $titres = [ordered]@{ plage = (T 'fermes.titre_plage'); moyenne = (T 'fermes.titre_moyenne'); regle = (T 'fermes.titre_regle'); age = (T 'fermes.titre_age'); maison = (T 'fermes.titre_maison'); securite = (T 'fermes.titre_securite') }
    $filtre = Champ (T 'fermes.chercher')
    $Contenu.Children.Add($filtre) | Out-Null
    $zone = New-Object System.Windows.Controls.StackPanel
    $Contenu.Children.Add($zone) | Out-Null
    $script:ListeFermes = @()
    foreach ($x in @(Prop $a 'apps' @())) { $script:ListeFermes += @{ genre = 'apps'; nom = [string](Prop $x 'nom'); lib = [string](Prop $x 'libelle'); type = (T 'commun.appli') } }
    foreach ($x in @(Prop $a 'sites' @())) {
        $n = [string](Prop $x 'nom'); if (($n.Split('.')).Count -ne 2) { continue }
        $script:ListeFermes += @{ genre = 'sites'; nom = $n; lib = $n; type = (T 'commun.site') }
    }
    foreach ($e in $script:ListeFermes) {
        $m = MotifDe $a $e.nom
        $e.code = 'regle'; $e.texte = (T 'fermes.coupe_par_vision')
        if ($m) { $e.code = $m.Substring(0, $m.IndexOf('|')); $e.texte = $m.Substring($m.IndexOf('|') + 1) }
    }
    $script:VF = @{ zone = $zone; filtre = $filtre; titres = $titres; a = $a }
    $script:RemplirFermes = {
        $zone = $script:VF.zone; $filtre = $script:VF.filtre; $titres = $script:VF.titres; $a = $script:VF.a
        $zone.Children.Clear()
        $q = (Valeur $filtre).ToLowerInvariant()
        foreach ($code in $titres.Keys) {
            $groupe = @($script:ListeFermes | Where-Object { $_.code -eq $code -and (-not $q -or $_.lib.ToLowerInvariant() -like "*$q*" -or $_.nom -like "*$q*") })
            if ($groupe.Count -eq 0) { continue }
            $zone.Children.Add((Section (T 'commun.titre_compte' @{ titre = $titres[$code]; n = $groupe.Count }))) | Out-Null
            $c = Carte; $sp = $c.Child
            $textes = @($groupe | ForEach-Object { $_.texte } | Select-Object -Unique)
            if ($textes.Count -eq 1) { $sp.Children.Add((Txt $textes[0] 12 'Texte2' $false '0,0,0,6')) | Out-Null }
            if ($code -eq 'securite') {
                $sp.Children.Add((Txt (Tn 'fermes.securite_elements' $groupe.Count) 12 'Texte3')) | Out-Null
                $zone.Children.Add($c) | Out-Null; continue
            }
            $k = 0
            foreach ($e in ($groupe | Sort-Object { $_.type }, { $_.lib.ToLowerInvariant() })) {
                if ($k++ -gt 0) { $sp.Children.Add((Separateur)) | Out-Null }
                $g = New-Object System.Windows.Controls.StackPanel
                $g.Children.Add((Txt $e.lib 14 'Texte' $true)) | Out-Null
                $sous = $e.type; if ($textes.Count -gt 1) { $sous = $sous + ' · ' + $e.texte }
                $g.Children.Add((Txt $sous 11 'Texte2')) | Out-Null
                $r = Rangee
                foreach ($def in @(@((T 'maison.bouton_1h'), 'temporaire', 60), @((T 'maison.bouton_autoriser'), 'toujours', 0))) {
                    $r.Children.Add((Bouton $def[0] $(if ($def[1] -eq 'toujours') { 'Primaire' } else { 'Secondaire' }) {
                        param($s, $e2); $t = $s.Tag
                        Agir @{ action = 'autoriser'; pc = [string]$t.pc; genre = $t.genre; nom = $t.nom; decision = $t.decision; minutes = [int]$t.minutes }
                        $s.Content = '✓'; $s.IsEnabled = $false
                    } @{ pc = (Prop $a 'id'); genre = $e.genre; nom = $e.nom; decision = $def[1]; minutes = $def[2] })) | Out-Null
                }
                $sp.Children.Add((Deux $g $r)) | Out-Null
            }
            $zone.Children.Add($c) | Out-Null
        }
    }
    & $script:RemplirFermes
    $filtre.Add_TextChanged({ & $script:RemplirFermes })
}

# Les exceptions d'une personne : ce qui est décidé nom par nom, en plus des catégories. On les voit, on les
# inverse, on les retire, on en ajoute. Elles valent sur tous les appareils de la personne.
function ExAgir([string]$pc, [string]$genre, [string]$nom, [string]$decision) {
    Agir @{ action = 'autoriser'; pc = $pc; genre = $genre; nom = $nom; decision = $decision; minutes = 0 }
}
function VueExceptions($a) {
    # La fiche gardée par l'écran date du clic : on relit celle du moment, pour voir l'effet de ses gestes.
    $maison = Lire 'maison.json'
    $idPc = [string](Prop $a 'id')
    foreach ($x in @(Prop $maison 'appareils' @())) { if ([string](Prop $x 'id') -eq $idPc) { $a = $x } }
    $ex = Prop $a 'exceptions'
    $Contenu.Children.Add((Txt (T 'exceptions.aide') 12 'Texte2' $false '0,0,0,0')) | Out-Null
    if ($null -eq $ex) {
        $Contenu.Children.Add((Txt (T 'exceptions.a_venir') 12 'Texte3')) | Out-Null
        return
    }
    # Toujours disponibles : les applis qui restent ouvertes même quand le planning ferme l'appareil.
    $dispo = @(Prop $ex 'toujours' @())
    $Contenu.Children.Add((Section (T 'commun.titre_compte' @{ titre = (T 'maison.bouton_toujours_dispo'); n = $dispo.Count }))) | Out-Null
    $c = Carte; $sp = $c.Child
    $sp.Children.Add((Txt (T 'exceptions.aide_toujours') 12 'Texte2' $false '0,0,0,6')) | Out-Null
    $k = 0
    foreach ($e in $dispo) {
        if ($k++ -gt 0) { $sp.Children.Add((Separateur)) | Out-Null }
        $g = New-Object System.Windows.Controls.StackPanel
        $lib = [string](Prop $e 'libelle'); $nom = [string](Prop $e 'nom')
        $g.Children.Add((Txt $lib 14 'Texte' $true)) | Out-Null
        $deMaison = [bool](Prop $e 'maison' $false)
        $g.Children.Add((Txt $(if ($deMaison) { (T 'exceptions.appli_tous') } else { (T 'commun.appli') }) 11 'Texte2')) | Out-Null
        if ($deMaison) { $sp.Children.Add($g) | Out-Null; continue }
        $r = Rangee
        $r.Children.Add((Bouton (T 'commun.retirer') 'Secondaire' {
            param($s, $e2); $t = $s.Tag
            ExAgir $t.pc 'apps' $t.nom 'indisponible'
            $s.Content = '✓'; $s.IsEnabled = $false
        } @{ pc = $idPc; nom = $nom })) | Out-Null
        $sp.Children.Add((Deux $g $r)) | Out-Null
    }
    $Contenu.Children.Add($c) | Out-Null
    foreach ($def in @(@('autorises', (T 'exceptions.titre_autorises'), (T 'exceptions.aucune'), (T 'personne.fermer'), 'bloquer'), @('bloques', (T 'exceptions.titre_bloques'), (T 'exceptions.rien_ferme'), (T 'maison.bouton_autoriser'), 'toujours'))) {
        $liste = @(Prop $ex $def[0] @())
        $Contenu.Children.Add((Section (T 'commun.titre_compte' @{ titre = $def[1]; n = $liste.Count }))) | Out-Null
        $c = Carte; $sp = $c.Child
        if ($liste.Count -eq 0) { $sp.Children.Add((Txt $def[2] 12 'Texte3')) | Out-Null }
        $k = 0
        foreach ($e in $liste) {
            if ($k++ -gt 0) { $sp.Children.Add((Separateur)) | Out-Null }
            $g = New-Object System.Windows.Controls.StackPanel
            $lib = [string](Prop $e 'libelle'); $nom = [string](Prop $e 'nom'); $genre = [string](Prop $e 'genre')
            $g.Children.Add((Txt $lib 14 'Texte' $true)) | Out-Null
            $sous = $(if ($genre -eq 'apps') { (T 'commun.appli') } else { (T 'commun.site') }); if ($lib -ne $nom) { $sous = $sous + ' · ' + $nom }
            if ([bool](Prop $e 'maison' $false)) { $sous = $sous + (T 'exceptions.interdit_tous') }
            $g.Children.Add((Txt $sous 11 'Texte2')) | Out-Null
            $r = Rangee
            foreach ($b in @(@($def[3], $def[4], 'Secondaire'), @((T 'commun.retirer'), 'oublier', 'Secondaire'))) {
                $r.Children.Add((Bouton $b[0] $b[2] {
                    param($s, $e2); $t = $s.Tag
                    ExAgir $t.pc $t.genre $t.nom $t.decision
                    $s.Content = '✓'; $s.IsEnabled = $false
                } @{ pc = $idPc; genre = $genre; nom = $nom; decision = $b[1] })) | Out-Null
            }
            $sp.Children.Add((Deux $g $r)) | Out-Null
        }
        $Contenu.Children.Add($c) | Out-Null
    }
    # Ajouter : on cherche dans tout ce que Vision connaît (applis et sites classés), ou on écrit l'adresse d'un site.
    $Contenu.Children.Add((Section (T 'exceptions.ajouter'))) | Out-Null
    $c = Carte; $sp = $c.Child
    $champ = Champ (T 'exceptions.indice')
    $sp.Children.Add($champ) | Out-Null
    $zone = New-Object System.Windows.Controls.StackPanel
    $sp.Children.Add($zone) | Out-Null
    $Contenu.Children.Add($c) | Out-Null
    $connus = @{}
    foreach ($cat in @(Prop $maison 'categories' @())) {
        foreach ($x in @(Prop $cat 'apps' @())) { $n = [string](Prop $x 'nom'); if ($n -and -not $connus.ContainsKey('a:' + $n.ToLowerInvariant())) { $connus['a:' + $n.ToLowerInvariant()] = @{ genre = 'apps'; nom = $n; lib = [string](Prop $x 'libelle') } } }
        foreach ($x in @(Prop $cat 'sites' @())) { $n = [string]$x; if ($n -and -not $connus.ContainsKey('s:' + $n.ToLowerInvariant())) { $connus['s:' + $n.ToLowerInvariant()] = @{ genre = 'sites'; nom = $n; lib = $n } } }
    }
    $script:VE = @{ zone = $zone; champ = $champ; connus = @($connus.Values); pc = $idPc }
    $script:RemplirExceptions = {
        $zone = $script:VE.zone; $zone.Children.Clear()
        $q = (Valeur $script:VE.champ).ToLowerInvariant()
        if ($q.Length -lt 2) { $zone.Children.Add((Txt (T 'exceptions.deux_lettres') 12 'Texte3')) | Out-Null; return }
        $trouves = @($script:VE.connus | Where-Object { $_.lib.ToLowerInvariant() -like "*$q*" -or $_.nom.ToLowerInvariant() -like "*$q*" } | Sort-Object { $_.lib.ToLowerInvariant() } | Select-Object -First 8)
        # Une adresse écrite en entier et que Vision ne connaît pas encore : on la propose telle quelle.
        if ($q -match '^[a-z0-9-]+(\.[a-z0-9-]+)+$' -and -not @($trouves | Where-Object { $_.nom.ToLowerInvariant() -eq $q }).Count) { $trouves = @(@{ genre = 'sites'; nom = $q; lib = $q }) + $trouves }
        if ($trouves.Count -eq 0) { $zone.Children.Add((Txt (T 'exceptions.rien_de_ce_nom') 12 'Texte3')) | Out-Null; return }
        $k = 0
        foreach ($e in $trouves) {
            if ($k++ -gt 0) { $zone.Children.Add((Separateur)) | Out-Null }
            $g = New-Object System.Windows.Controls.StackPanel
            $g.Children.Add((Txt $e.lib 14 'Texte' $true)) | Out-Null
            $g.Children.Add((Txt $(if ($e.genre -eq 'apps') { (T 'commun.appli') } else { (T 'commun.site') }) 11 'Texte2')) | Out-Null
            $r = Rangee
            $choix = @(@((T 'maison.bouton_autoriser'), 'toujours', 'Primaire'), @((T 'personne.fermer'), 'bloquer', 'Secondaire'))
            if ($e.genre -eq 'apps') { $choix += , @((T 'maison.bouton_toujours_dispo'), 'disponible', 'Secondaire') }
            foreach ($b in $choix) {
                $r.Children.Add((Bouton $b[0] $b[2] {
                    param($s, $e2); $t = $s.Tag
                    ExAgir $t.pc $t.genre $t.nom $t.decision
                    $s.Content = '✓'; $s.IsEnabled = $false
                } @{ pc = $script:VE.pc; genre = $e.genre; nom = $e.nom; decision = $b[1] })) | Out-Null
            }
            $zone.Children.Add((Deux $g $r)) | Out-Null
        }
    }
    & $script:RemplirExceptions
    $champ.Add_TextChanged({ & $script:RemplirExceptions })
}

# Le temps d'écran d'une personne, tous ses appareils réunis : aujourd'hui heure par heure, les applis et
# logiciels du jour, les sept derniers jours, et le cumul depuis le premier relevé.
function Duree([double]$secondes) {
    $s = [int]$secondes
    if ($s -ge 3600) { return (T 'duree.h_min' @{ h = [int][Math]::Floor($s / 3600); m = ('{0:00}' -f [int][Math]::Floor(($s % 3600) / 60)) }) }
    if ($s -lt 60) { return (T 'duree.moins_minute') }
    return (T 'duree.min' @{ m = [int][Math]::Round($s / 60) })
}
function BarreTemps([string]$nom, [string]$sous, [double]$valeur, [double]$maxi) {
    $g = New-Object System.Windows.Controls.StackPanel; $g.Margin = [System.Windows.Thickness]::new(0, 5, 0, 5)
    $tete = Deux (Txt $nom 13 'Texte' $true) (Txt (Duree $valeur) 12 'Texte2')
    $g.Children.Add($tete) | Out-Null
    if ($sous) { $g.Children.Add((Txt $sous 11 'Texte3')) | Out-Null }
    $piste = New-Object System.Windows.Controls.Grid; $piste.Height = 6; $piste.Margin = [System.Windows.Thickness]::new(0, 4, 0, 0)
    $part = 0.0; if ($maxi -gt 0) { $part = [Math]::Max(0.01, [Math]::Min(1.0, $valeur / $maxi)) }
    $c1 = New-Object System.Windows.Controls.ColumnDefinition; $c1.Width = [System.Windows.GridLength]::new($part, 'Star')
    $c2 = New-Object System.Windows.Controls.ColumnDefinition; $c2.Width = [System.Windows.GridLength]::new((1.0 - $part), 'Star')
    $piste.ColumnDefinitions.Add($c1); $piste.ColumnDefinitions.Add($c2)
    $fond = New-Object System.Windows.Controls.Border; $fond.Background = (Pinceau 'Haute'); $fond.CornerRadius = [System.Windows.CornerRadius]::new(3)
    [System.Windows.Controls.Grid]::SetColumnSpan($fond, 2)
    $plein = New-Object System.Windows.Controls.Border; $plein.Background = (Pinceau 'Or'); $plein.CornerRadius = [System.Windows.CornerRadius]::new(3)
    $piste.Children.Add($fond) | Out-Null; $piste.Children.Add($plein) | Out-Null
    $g.Children.Add($piste) | Out-Null
    return $g
}
function VueTemps($a) {
    $a = AppareilFrais $a
    BlocLimites $a
    $maison = Lire 'maison.json'
    $personne = [string](Prop $a 'personne'); $idPc = [string](Prop $a 'id')
    $siens = @(@(Prop $maison 'appareils' @()) | Where-Object { [string](Prop $_ 'id') -eq $idPc -or ($personne -and [string](Prop $_ 'personne') -eq $personne) })
    if ($siens.Count -eq 0) { $siens = @($a) }
    if ($null -eq (Prop $siens[0] 'temps')) {
        $Contenu.Children.Add((Txt (T 'temps.a_venir') 12 'Texte3')) | Out-Null
        return
    }
    $aujourdhui = (Get-Date).ToString('yyyy-MM-dd')
    # --- Aujourd'hui : le total, les heures, les applis.
    $heures = New-Object 'double[]' 24; $applisJour = @{}; $totalJour = 0.0; $parAppareil = @()
    $jours = [ordered]@{}; $cumul = @{}; $depuis = 0
    foreach ($x in $siens) {
        $t = Prop $x 'temps'
        foreach ($j in @(Prop $t 'jours' @())) {
            $cle = [string](Prop $j 'jour'); $actif = [double](Prop $j 'actif' 0)
            if (-not $jours.Contains($cle)) { $jours[$cle] = 0.0 }
            $jours[$cle] = [double]$jours[$cle] + $actif
            if ($cle -ne $aujourdhui) { continue }
            $totalJour += $actif
            if ($actif -ge 60) { $parAppareil += (T 'commun.nom_valeur' @{ nom = [string](Prop $x 'nom'); valeur = (Duree $actif) }) }
            $h = @(Prop $j 'heures' @()); for ($k = 0; $k -lt 24 -and $k -lt $h.Count; $k++) { $heures[$k] += [double]$h[$k] }
            foreach ($ap in @(Prop $j 'apps' @())) { $lib = [string](Prop $ap 'libelle'); if (-not $applisJour.ContainsKey($lib)) { $applisJour[$lib] = 0.0 }; $applisJour[$lib] += [double](Prop $ap 'secondes' 0) }
        }
        foreach ($ap in @(Prop $t 'total' @())) {
            $lib = [string](Prop $ap 'libelle'); if (-not $cumul.ContainsKey($lib)) { $cumul[$lib] = 0.0 }; $cumul[$lib] += [double](Prop $ap 'secondes' 0)
            $d = [long](Prop $ap 'depuis' 0); if ($d -gt 0 -and ($depuis -eq 0 -or $d -lt $depuis)) { $depuis = $d }
        }
    }
    $Contenu.Children.Add((Section (T 'temps.aujourdhui'))) | Out-Null
    $c = Carte; $sp = $c.Child
    $sp.Children.Add((Txt (Duree $totalJour) 26 'Texte' $true)) | Out-Null
    $sp.Children.Add((Txt $(if ($parAppareil.Count) { (T 'temps.ecran_actif') + '   ·   ' + ($parAppareil -join '   ·   ') } else { (T 'temps.ecran_actif') }) 12 'Texte2' $false '0,0,0,12')) | Out-Null
    # Les vingt-quatre heures : une case par heure, d'autant plus pleine que l'heure a été utilisée.
    $bande = New-Object System.Windows.Controls.Primitives.UniformGrid; $bande.Rows = 1; $bande.Columns = 24; $bande.Height = 34
    for ($k = 0; $k -lt 24; $k++) {
        $case = New-Object System.Windows.Controls.Grid; $case.Margin = [System.Windows.Thickness]::new(1, 0, 1, 0)
        $fondCase = New-Object System.Windows.Controls.Border; $fondCase.Background = (Pinceau 'Haute'); $fondCase.CornerRadius = [System.Windows.CornerRadius]::new(3)
        $pleinCase = New-Object System.Windows.Controls.Border; $pleinCase.Background = (Pinceau 'Or'); $pleinCase.CornerRadius = [System.Windows.CornerRadius]::new(3)
        $pleinCase.VerticalAlignment = 'Bottom'; $pleinCase.Height = [Math]::Round(34 * [Math]::Min(1.0, $heures[$k] / 3600.0))
        $case.ToolTip = (T 'temps.heure_info' @{ h = $k; duree = (Duree $heures[$k]) })
        $case.Children.Add($fondCase) | Out-Null; if ($heures[$k] -ge 30) { $case.Children.Add($pleinCase) | Out-Null }
        $bande.Children.Add($case) | Out-Null
    }
    $sp.Children.Add($bande) | Out-Null
    $reperes = New-Object System.Windows.Controls.Primitives.UniformGrid; $reperes.Rows = 1; $reperes.Columns = 4; $reperes.Margin = [System.Windows.Thickness]::new(0, 3, 0, 8)
    foreach ($hr in @(0, 6, 12, 18)) { $reperes.Children.Add((Txt (T 'temps.repere' @{ h = $hr }) 10 'Texte3')) | Out-Null }
    $sp.Children.Add($reperes) | Out-Null
    $triJour = @($applisJour.GetEnumerator() | Sort-Object Value -Descending | Select-Object -First 10)
    if ($triJour.Count -eq 0) { $sp.Children.Add((Txt (T 'temps.detail_vide') 12 'Texte3')) | Out-Null }
    else { $maxi = [double]$triJour[0].Value; foreach ($e in $triJour) { $sp.Children.Add((BarreTemps ([string]$e.Key) '' ([double]$e.Value) $maxi)) | Out-Null } }
    $Contenu.Children.Add($c) | Out-Null
    # --- Les sept derniers jours.
    $Contenu.Children.Add((Section (T 'temps.sept_jours'))) | Out-Null
    $c = Carte; $sp = $c.Child
    $maxiJ = 1.0; foreach ($v in $jours.Values) { if ([double]$v -gt $maxiJ) { $maxiJ = [double]$v } }
    # Noms des jours et des mois : ceux de la culture ; l'ordre des morceaux vient du dictionnaire (format_jour).
    $fr = $script:Culture
    foreach ($cle in @($jours.Keys | Sort-Object -Descending | Select-Object -First 7)) {
        $nomJour = $cle; try { $dj = [datetime]::ParseExact($cle, 'yyyy-MM-dd', $null); $nomJour = $dj.ToString((T 'format_jour'), $fr); $nomJour = $nomJour.Substring(0, 1).ToUpper() + $nomJour.Substring(1) } catch { }
        $sp.Children.Add((BarreTemps $nomJour '' ([double]$jours[$cle]) $maxiJ)) | Out-Null
    }
    if ($jours.Count -eq 0) { $sp.Children.Add((Txt (T 'temps.aucun_releve') 12 'Texte3')) | Out-Null }
    $Contenu.Children.Add($c) | Out-Null
    # --- Le cumul.
    $titreCumul = (T 'temps.depuis_debut')
    if ($depuis -gt 0) { try { $titreCumul = (T 'temps.depuis_le' @{ date = ([DateTimeOffset]::FromUnixTimeSeconds($depuis).LocalDateTime.ToString((T 'format_date'), $fr)) }) } catch { } }
    $Contenu.Children.Add((Section $titreCumul)) | Out-Null
    $c = Carte; $sp = $c.Child
    $triCumul = @($cumul.GetEnumerator() | Sort-Object Value -Descending | Select-Object -First 15)
    if ($triCumul.Count -eq 0) { $sp.Children.Add((Txt (T 'temps.rien_releve') 12 'Texte3')) | Out-Null }
    else { $maxi = [double]$triCumul[0].Value; foreach ($e in $triCumul) { $sp.Children.Add((BarreTemps ([string]$e.Key) '' ([double]$e.Value) $maxi)) | Out-Null } }
    $Contenu.Children.Add($c) | Out-Null
}

# La fiche gardée par l'écran date du clic : celle du moment, pour voir l'effet de ses gestes.
function AppareilFrais($a) {
    $idPc = [string](Prop $a 'id')
    foreach ($x in @(Prop (Lire 'maison.json') 'appareils' @())) { if ([string](Prop $x 'id') -eq $idPc) { return $x } }
    return $a
}
# Un bouton qui reste enfoncé : jour de la semaine, catégorie, appareil épargné. Son état est dans Tag.on.
function Bascule([string]$texte, [bool]$on, $valeur = $null) {
    $b = Bouton $texte $(if ($on) { 'Primaire' } else { 'Secondaire' }) {
        param($s, $e)
        $s.Tag.on = -not [bool]$s.Tag.on
        $s.Style = $script:W.Resources[$(if ($s.Tag.on) { 'Primaire' } else { 'Secondaire' })]
    } @{ on = $on; valeur = $valeur }
    $b.Margin = [System.Windows.Thickness]::new(0, 0, 6, 6)
    return $b
}

function VuePlanning($a) {
    $a = AppareilFrais $a
    $Contenu.Children.Add((Txt (T 'planning.aide') 12 'Texte2' $false '0,0,0,10')) | Out-Null
    if ($script:Vue.dit) { $Contenu.Children.Add((Txt ([string]$script:Vue.dit) 12 'Vert' $false '0,0,0,10')) | Out-Null }
    $ici = $script:Vue
    $c = Carte; $sp = $c.Child
    $jours = @((T 'jours_initiales') -split ',')
    $k = 0
    foreach ($p in @(Prop $a 'plages' @())) {
        if ($k++ -gt 0) { $sp.Children.Add((Separateur)) | Out-Null }
        $g = New-Object System.Windows.Controls.StackPanel
        $actif = [bool](Prop $p 'actif' $false); $active = [bool](Prop $p 'active' $true)
        $g.Children.Add((Txt (T 'planning.plage_ligne' @{ nom = [string](Prop $p 'nom'); debut = [string](Prop $p 'debut'); fin = [string](Prop $p 'fin') }) 14 $(if (-not $active) { 'Texte3' } elseif ($actif) { 'Or' } else { 'Texte' }) $true)) | Out-Null
        $js = @(Prop $p 'jours' @()); $lettres = @()
        for ($i = 0; $i -lt 7; $i++) { if ($i -lt $js.Count -and [bool]$js[$i]) { $lettres += $jours[$i] } else { $lettres += '·' } }
        $quoi = switch ([string](Prop $p 'portee')) { 'etiquettes' { (T 'planning.coupe' @{ categories = ((@(Prop $p 'etiquettes' @())) -join ', ') }) } 'elements' { (T 'planning.elements') } default { (T 'planning.tout_appareil') } }
        $etat = if ([bool](Prop $p 'epargne' $false)) { (T 'planning.pas_ici') } elseif ($actif) { (T 'planning.en_cours') } elseif (-not $active) { (T 'planning.desactivee') } else { '' }
        $g.Children.Add((Txt ($quoi + '     ' + ($lettres -join ' ') + $etat) 11 'Texte2')) | Out-Null
        $r = New-Object System.Windows.Controls.WrapPanel; $r.Margin = [System.Windows.Thickness]::new(0, 8, 0, 0)
        $b1 = Bouton (T 'planning.modifier') 'Secondaire' {
            param($s, $e); $x = $s.Tag
            $script:Vue = @{ type = 'plage'; appareil = $x.appareil; plage = $x.plage; retour = $x.retour }; Rafraichir
        } @{ appareil = $a; plage = $p; retour = $ici }
        $b2 = Bouton $(if ($active) { (T 'planning.desactiver') } else { (T 'planning.reactiver') }) 'Secondaire' {
            param($s, $e); $x = $s.Tag
            Agir @{ action = 'plage'; op = 'modifier'; pc = [string]$x.pc; id = [int]$x.id; active = [bool]$x.vers }
            $s.Content = '✓'; $s.IsEnabled = $false; RelireBientot
        } @{ pc = (Prop $a 'id'); id = (Prop $p 'id'); vers = (-not $active) }
        # Supprimer demande un second clic : une plage effacée ne se retrouve pas.
        $b3 = Bouton (T 'planning.supprimer') 'Secondaire' {
            param($s, $e); $x = $s.Tag
            if (-not $x.sur) { $x.sur = $true; $s.Content = (T 'planning.supprimer_vraiment'); $s.Style = $script:W.Resources['Danger']; return }
            Agir @{ action = 'plage'; op = 'supprimer'; pc = [string]$x.pc; id = [int]$x.id }
            $s.Content = '✓'; $s.IsEnabled = $false; RelireBientot
        } @{ pc = (Prop $a 'id'); id = (Prop $p 'id'); sur = $false }
        foreach ($b in @($b1, $b2, $b3)) { $b.Margin = [System.Windows.Thickness]::new(0, 0, 8, 6); $r.Children.Add($b) | Out-Null }
        $g.Children.Add($r) | Out-Null
        $sp.Children.Add($g) | Out-Null
    }
    if ($k -eq 0) { $sp.Children.Add((Txt (T 'planning.aucune_plage') 13 'Texte2')) | Out-Null }
    $Contenu.Children.Add($c) | Out-Null
    $plus = Bouton (T 'planning.ajouter_plage') 'Secondaire' {
        param($s, $e); $x = $s.Tag
        $script:Vue = @{ type = 'plage'; appareil = $x.appareil; plage = $null; retour = $x.retour }; Rafraichir
    } @{ appareil = $a; retour = $ici }
    $plus.HorizontalAlignment = 'Left'
    $Contenu.Children.Add($plus) | Out-Null
}

# Une plage du planning : la créer ou la modifier. Les réglages restent dans les contrôles jusqu'à « Enregistrer ».
function VuePlage($a, $p) {
    $a = AppareilFrais $a
    $maison = Lire 'maison.json'
    $neuve = ($null -eq $p)
    if ($script:Vue.dit) { $Contenu.Children.Add((Txt ([string]$script:Vue.dit) 12 'Rouge' $false '0,0,0,10')) | Out-Null }
    $c = Carte; $sp = $c.Child
    $sp.Children.Add((Txt (T 'plage.nom') 12 'Texte2' $false '0,0,0,4')) | Out-Null
    $nom = Champ (T 'plage.exemple_nom')
    if (-not $neuve) { $nom.Text = [string](Prop $p 'nom'); $nom.Foreground = (Pinceau 'Texte') }
    $sp.Children.Add($nom) | Out-Null
    $sp.Children.Add((Txt (T 'plage.heures_aide') 12 'Texte2' $false '0,0,0,4')) | Out-Null
    $heures = Rangee
    $debut = Champ '18:00'; $debut.Width = 110; $debut.Margin = [System.Windows.Thickness]::new(0, 0, 10, 10)
    $fin = Champ '20:00'; $fin.Width = 110
    if (-not $neuve) { $debut.Text = [string](Prop $p 'debut'); $fin.Text = [string](Prop $p 'fin'); $debut.Foreground = (Pinceau 'Texte'); $fin.Foreground = (Pinceau 'Texte') }
    $heures.Children.Add($debut) | Out-Null; $heures.Children.Add($fin) | Out-Null
    $sp.Children.Add($heures) | Out-Null
    $sp.Children.Add((Txt (T 'plage.les_jours') 12 'Texte2' $false '0,0,0,4')) | Out-Null
    $rj = New-Object System.Windows.Controls.WrapPanel; $boutonsJours = @()
    $js = @(Prop $p 'jours' @()); $noms = @((T 'jours_courts') -split ',')
    for ($i = 0; $i -lt 7; $i++) {
        $on = if ($neuve) { $true } else { ($i -lt $js.Count -and [bool]$js[$i]) }
        $b = Bascule $noms[$i] $on $i; $boutonsJours += $b; $rj.Children.Add($b) | Out-Null
    }
    $sp.Children.Add($rj) | Out-Null
    $Contenu.Children.Add($c) | Out-Null

    $Contenu.Children.Add((Section (T 'plage.ce_qui_ferme'))) | Out-Null
    $c = Carte; $sp = $c.Child
    $portee = if ($neuve) { 'session' } else { [string](Prop $p 'portee' 'session') }
    if ($portee -eq 'elements') {
        $sp.Children.Add((Txt (T 'plage.elements_aide') 12 'Texte2')) | Out-Null
        $tout = $null; $boutonsCats = @()
    } else {
        $tout = Bascule (T 'planning.tout_appareil') ($portee -eq 'session')
        $sp.Children.Add($tout) | Out-Null
        $sp.Children.Add((Txt (T 'plage.ou_categories') 12 'Texte2' $false '0,4,0,6')) | Out-Null
        $rc = New-Object System.Windows.Controls.WrapPanel; $boutonsCats = @()
        $deja = @(Prop $p 'etiquettes' @() | ForEach-Object { ([string]$_).ToLowerInvariant() })
        $vus = @{}
        foreach ($cat in @(Prop $maison 'categories' @())) {
            if ([bool](Prop $cat 'maison' $false) -or [bool](Prop $cat 'securite' $false)) { continue }
            $n = [string](Prop $cat 'nom'); if (-not $n) { continue }
            # Seulement ce qui classe vraiment quelque chose : les autres étiquettes de la maison
            # (Chauffage, Caméras…) ne fermeraient rien, et les proposer ici prête à confusion.
            if ((@(Prop $cat 'apps' @()).Count + @(Prop $cat 'sites' @()).Count) -eq 0 -and -not [bool](Prop $cat 'age' $false) -and $deja -notcontains $n.ToLowerInvariant()) { continue }
            $vus[$n.ToLowerInvariant()] = $true
            $b = Bascule ([string](Prop $cat 'libelle' $n)) ($deja -contains $n.ToLowerInvariant()) $n; $boutonsCats += $b; $rc.Children.Add($b) | Out-Null
        }
        # Une étiquette déjà posée sur la plage et qui n'est pas une catégorie de Vision : on la montre pour pouvoir l'enlever.
        foreach ($n in @(Prop $p 'etiquettes' @())) {
            if ($vus.ContainsKey(([string]$n).ToLowerInvariant())) { continue }
            $b = Bascule ([string]$n) $true ([string]$n); $boutonsCats += $b; $rc.Children.Add($b) | Out-Null
        }
        $sp.Children.Add($rc) | Out-Null
    }
    $Contenu.Children.Add($c) | Out-Null

    # Les exceptions par appareil : la nuit ferme le téléphone, pas la liseuse.
    $personne = [string](Prop $a 'personne'); $boutonsSauf = @()
    $siens = @(@(Prop $maison 'appareils' @()) | Where-Object { $personne -and [string](Prop $_ 'personne') -eq $personne })
    if ($siens.Count -gt 1) {
        $Contenu.Children.Add((Section (T 'plage.pas_sur'))) | Out-Null
        $c = Carte; $sp = $c.Child
        $sp.Children.Add((Txt (T 'plage.pas_sur_aide') 12 'Texte2' $false '0,0,0,6')) | Out-Null
        $rs = New-Object System.Windows.Controls.WrapPanel
        $epargnes = @{}
        if (-not $neuve) {
            # Chaque appareil de la personne porte la même plage et dit s'il en est épargné.
            foreach ($x in $siens) { foreach ($q in @(Prop $x 'plages' @())) { if ([string](Prop $q 'id') -eq [string](Prop $p 'id') -and [bool](Prop $q 'epargne' $false)) { $epargnes[[string](Prop $x 'id')] = $true } } }
        }
        foreach ($x in $siens) {
            $idx = [string](Prop $x 'id')
            $b = Bascule ([string](Prop $x 'nom')) ($epargnes.ContainsKey($idx)) $idx; $boutonsSauf += $b; $rs.Children.Add($b) | Out-Null
        }
        $sp.Children.Add($rs) | Out-Null
        $Contenu.Children.Add($c) | Out-Null
    }

    $script:PlageEdit = @{ nom = $nom; debut = $debut; fin = $fin; jours = $boutonsJours; tout = $tout; cats = $boutonsCats; sauf = $boutonsSauf
        pc = [string](Prop $a 'id'); id = $(if ($neuve) { 0 } else { [int](Prop $p 'id') }); elements = ($portee -eq 'elements') }
    $r = Rangee
    $r.Children.Add((Bouton $(if ($neuve) { (T 'plage.ajouter') } else { (T 'plage.enregistrer') }) 'Primaire' {
        $x = $script:PlageEdit
        $n = Valeur $x.nom; $d = Valeur $x.debut; $f = Valeur $x.fin
        $motif = '^\s*([01]?\d|2[0-3])\s*[:hH]\s*([0-5]\d)?\s*$'
        $faute = ''
        if (-not $n) { $faute = (T 'plage.faute_nom') }
        elseif ($d -notmatch $motif -or $f -notmatch $motif) { $faute = (T 'plage.faute_heures') }
        $jours = @($x.jours | ForEach-Object { [bool]$_.Tag.on })
        if (-not $faute -and -not ($jours -contains $true)) { $faute = (T 'plage.faute_jour') }
        $corps = @{ action = 'plage'; op = $(if ($x.id) { 'modifier' } else { 'creer' }); pc = $x.pc; id = $x.id; nom = $n; debut = $d; fin = $f; jours = $jours }
        if (-not $x.elements) {
            $cats = @($x.cats | Where-Object { [bool]$_.Tag.on } | ForEach-Object { [string]$_.Tag.valeur })
            if ([bool]$x.tout.Tag.on) { $corps.portee = 'session'; $corps.etiquettes = @() }
            elseif ($cats.Count -eq 0) { if (-not $faute) { $faute = (T 'plage.faute_portee') } }
            else { $corps.portee = 'etiquettes'; $corps.etiquettes = $cats }
        }
        if (@($x.sauf).Count -gt 0) { $corps.sauf = @($x.sauf | Where-Object { [bool]$_.Tag.on } | ForEach-Object { [string]$_.Tag.valeur }) }
        if ($faute) { $script:Vue.dit = $faute; Rafraichir; return }
        Agir $corps
        $retour = $script:Vue.retour
        if ($retour) { $retour.dit = (T 'plage.enregistree') }
        $script:Vue = $retour; Rafraichir; RelireBientot
    })) | Out-Null
    $r.Children.Add((Bouton (T 'commun.annuler') 'Secondaire' { $script:Vue = $script:Vue.retour; Rafraichir })) | Out-Null
    $Contenu.Children.Add($r) | Out-Null
}

# Les limites de temps par appli : tant de minutes par jour, tous appareils de la personne confondus.
function BlocLimites($a) {
    $limites = @(Prop $a 'limites' @())
    if ($null -eq (Prop $a 'limites')) { return }
    $Contenu.Children.Add((Section (T 'commun.titre_compte' @{ titre = (T 'limites.titre'); n = $limites.Count }))) | Out-Null
    $c = Carte; $sp = $c.Child
    $sp.Children.Add((Txt (T 'limites.aide') 12 'Texte2' $false '0,0,0,6')) | Out-Null
    if ($script:Vue.dit) { $sp.Children.Add((Txt ([string]$script:Vue.dit) 12 'Vert' $false '0,0,0,6')) | Out-Null }
    $idPc = [string](Prop $a 'id'); $k = 0; $deja = @{}
    foreach ($l in $limites) {
        if ($k++ -gt 0) { $sp.Children.Add((Separateur)) | Out-Null }
        $nom = [string](Prop $l 'nom'); $deja[$nom] = $true
        $min = [int](Prop $l 'minutes' 0); $passe = [double](Prop $l 'passe' 0)
        $atteint = ($passe -ge $min * 60)
        $g = New-Object System.Windows.Controls.StackPanel
        $g.Children.Add((Txt ([string](Prop $l 'libelle')) 14 'Texte' $true)) | Out-Null
        $g.Children.Add((Txt $(if ($atteint) { (T 'limites.atteinte' @{ passe = (Duree $passe); limite = (DureeMin $min) }) } else { (T 'limites.passe' @{ passe = (Duree $passe); limite = (DureeMin $min) }) }) 11 $(if ($atteint) { 'Rouge' } else { 'Texte2' }))) | Out-Null
        $r = New-Object System.Windows.Controls.WrapPanel; $r.Margin = [System.Windows.Thickness]::new(0, 8, 0, 0)
        foreach ($def in @(@((T 'limites.moins_15'), -15), @((T 'limites.plus_15'), 15), @((T 'commun.retirer'), 0))) {
            $vers = if ($def[1] -eq 0) { 0 } else { [Math]::Max(5, [Math]::Min(1440, $min + [int]$def[1])) }
            if ($def[1] -ne 0 -and $vers -eq $min) { continue }
            $b = Bouton $def[0] 'Secondaire' {
                param($s, $e); $x = $s.Tag
                Agir @{ action = 'limite'; pc = [string]$x.pc; nom = [string]$x.nom; minutes = [int]$x.minutes }
                $s.Content = '✓'; $s.IsEnabled = $false; RelireBientot
            } @{ pc = $idPc; nom = $nom; minutes = $vers }
            $b.Margin = [System.Windows.Thickness]::new(0, 0, 8, 6); $r.Children.Add($b) | Out-Null
        }
        $g.Children.Add($r) | Out-Null
        $sp.Children.Add($g) | Out-Null
    }
    if ($k -eq 0) { $sp.Children.Add((Txt (T 'limites.aucune') 12 'Texte3' $false '0,0,0,4')) | Out-Null }
    # En poser une : l'appli d'abord (les plus utilisées), la durée ensuite.
    $choisie = $script:Vue.limite
    $sp.Children.Add((Separateur)) | Out-Null
    if (-not $choisie) {
        $sp.Children.Add((Txt (T 'limites.limiter') 12 'Texte2' $false '0,0,0,6')) | Out-Null
        $connues = [ordered]@{}
        $personne = [string](Prop $a 'personne')
        $siens = @(@(Prop (Lire 'maison.json') 'appareils' @()) | Where-Object { [string](Prop $_ 'id') -eq $idPc -or ($personne -and [string](Prop $_ 'personne') -eq $personne) })
        $cumul = @{}
        foreach ($x in $siens) { foreach ($ap in @(Prop (Prop $x 'temps') 'total' @())) {
            $n = [string](Prop $ap 'nom'); if (-not $n -or $deja.ContainsKey($n)) { continue }
            if (-not $cumul.ContainsKey($n)) { $cumul[$n] = @{ s = 0.0; libelle = [string](Prop $ap 'libelle') } }
            $cumul[$n].s += [double](Prop $ap 'secondes' 0)
        } }
        $rc = New-Object System.Windows.Controls.WrapPanel
        foreach ($e in @($cumul.GetEnumerator() | Sort-Object { -$_.Value.s } | Select-Object -First 14)) {
            $b = Bouton ([string]$e.Value.libelle) 'Secondaire' {
                param($s, $ev); $script:Vue.limite = $s.Tag; $script:Vue.dit = ''; Rafraichir
            } @{ nom = [string]$e.Key; libelle = [string]$e.Value.libelle }
            $b.Margin = [System.Windows.Thickness]::new(0, 0, 6, 6); $rc.Children.Add($b) | Out-Null
        }
        if ($rc.Children.Count -eq 0) { $sp.Children.Add((Txt (T 'limites.aucune_appli') 12 'Texte3')) | Out-Null }
        $sp.Children.Add($rc) | Out-Null
    } else {
        $sp.Children.Add((Txt (T 'limites.combien' @{ appli = [string]$choisie.libelle }) 13 'Texte' $true '0,0,0,8')) | Out-Null
        $rd = New-Object System.Windows.Controls.WrapPanel
        foreach ($def in @(@((DureeMin 30), 30), @((DureeMin 45), 45), @((DureeMin 60), 60), @((DureeMin 90), 90), @((DureeMin 120), 120), @((DureeMin 180), 180))) {
            $b = Bouton $def[0] 'Secondaire' {
                param($s, $e); $x = $s.Tag
                Agir @{ action = 'limite'; pc = [string]$x.pc; nom = [string]$x.nom; minutes = [int]$x.minutes }
                $script:Vue.limite = $null; $script:Vue.dit = (T 'limites.posee' @{ appli = [string]$x.libelle; duree = (DureeMin ([int]$x.minutes)) })
                Rafraichir; RelireBientot
            } @{ pc = $idPc; nom = [string]$choisie.nom; libelle = [string]$choisie.libelle; minutes = $def[1] }
            $b.Margin = [System.Windows.Thickness]::new(0, 0, 6, 6); $rd.Children.Add($b) | Out-Null
        }
        $ba = Bouton (T 'commun.annuler') 'Secondaire' { $script:Vue.limite = $null; Rafraichir }
        $ba.Margin = [System.Windows.Thickness]::new(0, 0, 6, 6); $rd.Children.Add($ba) | Out-Null
        $sp.Children.Add($rd) | Out-Null
    }
    $Contenu.Children.Add($c) | Out-Null
}

# Les logiciels installés sur un appareil : les bloquer pour sa personne, ou les désinstaller.
function VueLogiciels($a) {
    $a = AppareilFrais $a
    $maison = Lire 'maison.json'
    # La désinstallation passe par les consignes « processus » (module Optimisations PC) ; « Bloquer » par le module Blocage.
    $permis = [bool](Prop $maison 'desinstallation' $false) -and (ModuleActif 'optimisations')
    $bloquerPermis = ModuleActif 'blocage'
    $liste = @(Prop $a 'logiciels' @())
    $intro = (T 'logiciels.intro')
    $Contenu.Children.Add((Txt $intro 12 'Texte2' $false '0,0,0,8')) | Out-Null
    if (-not (ModuleActif 'optimisations')) { $Contenu.Children.Add((Txt (T 'logiciels.module_coupe') 12 'Or' $false '0,0,0,8')) | Out-Null }
    elseif (-not $permis) { $Contenu.Children.Add((Txt (T 'logiciels.eteinte') 12 'Or' $false '0,0,0,8')) | Out-Null }
    if ($script:Vue.dit) { $Contenu.Children.Add((Txt ([string]$script:Vue.dit) 12 'Vert' $false '0,0,0,8')) | Out-Null }
    if ($liste.Count -eq 0) {
        $Contenu.Children.Add((Txt (T 'logiciels.liste_a_venir') 12 'Texte3')) | Out-Null
        return
    }
    $filtre = Champ (T 'logiciels.chercher')
    $Contenu.Children.Add($filtre) | Out-Null
    $c = Carte; $sp = $c.Child
    $idPc = [string](Prop $a 'id'); $bloques = @{}
    foreach ($b in @(Prop (Prop $a 'exceptions') 'bloques' @())) { $bloques[([string](Prop $b 'nom')).ToLowerInvariant()] = $true }
    $lignes = @()
    foreach ($l in $liste) {
        $nom = [string](Prop $l 'nom'); $cle = [string](Prop $l 'cle')
        $ligne = New-Object System.Windows.Controls.StackPanel; $ligne.Margin = [System.Windows.Thickness]::new(0, 6, 0, 6); $ligne.Tag = $nom.ToLowerInvariant() + ' ' + ([string](Prop $l 'editeur')).ToLowerInvariant()
        $ligne.Children.Add((Txt $nom 14 'Texte' $true)) | Out-Null
        $sous = @(); if ([string](Prop $l 'editeur')) { $sous += [string](Prop $l 'editeur') }; if ([string](Prop $l 'version')) { $sous += (T 'logiciels.version' @{ version = [string](Prop $l 'version') }) }
        if ($sous.Count) { $ligne.Children.Add((Txt ($sous -join '   ·   ') 11 'Texte2')) | Out-Null }
        $retour = Prop $l 'retour'; $decision = [string](Prop $l 'decision'); $refus = [string](Prop $l 'refus')
        if ($retour) {
            $etat = [string](Prop $retour 'etat'); $detail = [string](Prop $retour 'detail')
            $texte = switch ($etat) { 'fait' { (T 'logiciels.desinstalle') } 'echec' { (T 'logiciels.echec') } default { (T 'logiciels.impossible') } }
            if ($detail) { $texte = (T 'commun.nom_valeur' @{ nom = $texte; valeur = $detail }) }
            $ligne.Children.Add((Txt $texte 12 $(if ($etat -eq 'fait') { 'Vert' } else { 'Or' }) $false '0,4,0,0')) | Out-Null
        } elseif ($decision -eq 'desinstaller') {
            $ligne.Children.Add((Txt (T 'logiciels.demandee') 12 'Or' $false '0,4,0,0')) | Out-Null
        }
        $r = New-Object System.Windows.Controls.WrapPanel; $r.Margin = [System.Windows.Thickness]::new(0, 6, 0, 0)
        $exes = @(Prop $l 'exes' @() | ForEach-Object { [string]$_ } | Where-Object { $_ })
        if ($exes.Count -gt 0 -and $bloquerPermis) {
            $tous = $true; foreach ($x in $exes) { if (-not $bloques.ContainsKey($x.ToLowerInvariant())) { $tous = $false } }
            if ($tous) { $r.Children.Add((Txt (T 'logiciels.bloque') 12 'Rouge' $false '0,6,10,0')) | Out-Null }
            else {
                $bb = Bouton (T 'logiciels.bloquer') 'Secondaire' {
                    param($s, $e); $x = $s.Tag
                    foreach ($n in @($x.exes)) { Agir @{ action = 'autoriser'; pc = [string]$x.pc; genre = 'apps'; nom = [string]$n; decision = 'bloquer' } }
                    $s.Content = (T 'logiciels.bloque_ok'); $s.IsEnabled = $false; RelireBientot
                } @{ pc = $idPc; exes = $exes }
                $bb.Margin = [System.Windows.Thickness]::new(0, 0, 8, 6); $r.Children.Add($bb) | Out-Null
            }
        }
        if ($decision -eq 'desinstaller' -and -not $retour) {
            $ba = Bouton (T 'logiciels.annuler_demande') 'Secondaire' {
                param($s, $e); $x = $s.Tag
                Agir @{ action = 'logiciel'; pc = [string]$x.pc; cle = [string]$x.cle; geste = 'annuler' }
                $s.Content = '✓'; $s.IsEnabled = $false; RelireBientot
            } @{ pc = $idPc; cle = $cle }
            $ba.Margin = [System.Windows.Thickness]::new(0, 0, 8, 6); $r.Children.Add($ba) | Out-Null
        } elseif ($refus) {
            $r.Children.Add((Txt (T 'logiciels.refus' @{ raison = $refus }) 11 'Texte3' $false '0,6,0,0')) | Out-Null
        } elseif ($permis -and -not ($retour -and [string](Prop $retour 'etat') -eq 'fait')) {
            # Deux temps, toujours : le premier clic pose la question, le second seulement désinstalle.
            $bd = Bouton $(if ($retour) { (T 'logiciels.reessayer') } else { (T 'logiciels.desinstaller') }) 'Secondaire' {
                param($s, $e); $x = $s.Tag
                if (-not $x.sur) {
                    $x.sur = $true; $s.Style = $script:W.Resources['Danger']
                    $s.Content = (T 'logiciels.confirmer' @{ appareil = [string]$x.appareil })
                    return
                }
                Agir @{ action = 'logiciel'; pc = [string]$x.pc; cle = [string]$x.cle; geste = 'desinstaller'; confirmation = $true }
                $s.Content = (T 'logiciels.demande_ok'); $s.IsEnabled = $false; RelireBientot
            } @{ pc = $idPc; cle = $cle; sur = $false; appareil = [string](Prop $a 'nom') }
            $bd.Margin = [System.Windows.Thickness]::new(0, 0, 8, 6); $r.Children.Add($bd) | Out-Null
        }
        if ($r.Children.Count -gt 0) { $ligne.Children.Add($r) | Out-Null }
        $ligne.Children.Add((Separateur)) | Out-Null
        $sp.Children.Add($ligne) | Out-Null; $lignes += $ligne
    }
    $script:LignesLogiciels = $lignes
    $filtre.Add_TextChanged({ param($s, $e)
        $q = ''; if ($s.Text -ne $s.Tag) { $q = $s.Text.Trim().ToLowerInvariant() }
        foreach ($l in @($script:LignesLogiciels)) { $l.Visibility = $(if (-not $q -or ([string]$l.Tag).Contains($q)) { 'Visible' } else { 'Collapsed' }) }
    })
    $Contenu.Children.Add($c) | Out-Null
}

function VueCategories($a) {
    $maison = Lire 'maison.json'
    $cats = @(Prop $maison 'categories' @())
    $etats = Prop $a 'etiquettes_etat'
    $Contenu.Children.Add((Txt (T 'categories.aide') 12 'Texte2' $false '0,0,0,6')) | Out-Null
    $groupes = [ordered]@{ age = @(); categories = @(); tous = @() }
    $titresGroupes = @{ age = (T 'categories.groupe_age'); categories = (T 'categories.groupe_categories'); tous = (T 'categories.groupe_tous') }
    foreach ($c in $cats) {
        if ([bool](Prop $c 'maison' $false) -or [bool](Prop $c 'securite' $false)) { $groupes['tous'] += $c }
        elseif ([bool](Prop $c 'age' $false)) { $groupes['age'] += $c } else { $groupes['categories'] += $c }
    }
    foreach ($groupe in $groupes.Keys) {
        $liste = $groupes[$groupe]; if ($liste.Count -eq 0) { continue }
        $Contenu.Children.Add((Section $titresGroupes[$groupe])) | Out-Null
        $card = Carte; $sp = $card.Child; $k = 0
        $verrou = ($groupe -eq 'tous')
        foreach ($c in $liste) {
            if ($k++ -gt 0) { $sp.Children.Add((Separateur)) | Out-Null }
            $nom = [string](Prop $c 'nom')
            $nA = @(Prop $c 'apps' @()).Count; $nS = @(Prop $c 'sites' @()).Count
            $e = Prop $etats $nom
            $choix = 'neutre'; $coupe = $false; $parRegle = $false; $raison = ''
            if ($e -is [string]) { $choix = $e; $coupe = ($e -eq 'bloquer') }
            elseif ($null -ne $e) {
                $choix = [string](Prop $e 'choix' 'neutre'); $coupe = [bool](Prop $e 'coupe' $false)
                $parRegle = ([bool](Prop $e 'verrou' $false)) -and -not $verrou
                $raison = [string](Prop $e 'raison' '')
            }
            $g = New-Object System.Windows.Controls.StackPanel; $g.Cursor = 'Hand'; $g.Tag = $c; $g.Background = [System.Windows.Media.Brushes]::Transparent
            $g.Children.Add((Txt ([string](Prop $c 'libelle' $nom)) 14 $(if ($verrou) { 'Texte2' } else { 'Texte' }) $true)) | Out-Null
            $det = @(); if ($nA) { $det += (Tn 'categories.applis' $nA) }; if ($nS) { $det += (Tn 'categories.sites' $nS) }
            $d = if ($det.Count) { $det -join ', ' } else { (T 'categories.vide') }
            if ($choix -eq 'autoriser') { $d += (T 'categories.autorise_explicitement') }
            $g.Children.Add((Txt $d 11 'Texte2')) | Out-Null
            if ($raison -and ($parRegle -or $verrou)) { $g.Children.Add((Txt $raison 11 $(if ($parRegle) { 'Or' } else { 'Texte3' }))) | Out-Null }
            $detailCat = New-Object System.Windows.Controls.StackPanel; $detailCat.Visibility = 'Collapsed'; $detailCat.Margin = [System.Windows.Thickness]::new(0, 6, 0, 0)
            foreach ($x in @(Prop $c 'apps' @())) { $detailCat.Children.Add((Txt ('•  ' + [string](Prop $x 'libelle')) 12 'Texte')) | Out-Null }
            foreach ($x in @(Prop $c 'sites' @())) { $detailCat.Children.Add((Txt ('◦  ' + [string]$x) 12 'Texte2')) | Out-Null }
            if ($detailCat.Children.Count -eq 0) { $detailCat.Children.Add((Txt (T 'categories.rien_classe') 12 'Texte3')) | Out-Null }
            $g.Children.Add($detailCat) | Out-Null
            $g.Add_MouseLeftButtonUp({ param($s, $e) $cc = $s.Children[$s.Children.Count - 1]; $cc.Visibility = $(if ($cc.Visibility -eq 'Visible') { 'Collapsed' } else { 'Visible' }); $e.Handled = $true })
            if ($verrou) { $droite = Chip $((NomLieu).ToUpper()) 'Texte3' }
            else {
                $droite = New-Object System.Windows.Controls.Primitives.ToggleButton
                $droite.Style = $W.Resources['Interrupteur']; $droite.IsChecked = $coupe
                if ($parRegle) { $droite.IsEnabled = $false; $droite.ToolTip = $raison }
                $droite.Tag = @{ pc = (Prop $a 'id'); etiquette = $nom }
                $droite.Add_Click({ param($s, $e); $t = $s.Tag
                    Agir @{ action = 'etiquette'; pc = [string]$t.pc; etiquette = [string]$t.etiquette; etat = $(if ($s.IsChecked) { 'bloquer' } else { 'neutre' }) }
                })
            }
            $sp.Children.Add((Deux $g $droite)) | Out-Null
        }
        $Contenu.Children.Add($card) | Out-Null
    }
}

# ---- Cet ordinateur --------------------------------------------------------
function PageMoi($etat, [bool]$ferme) {
    $c = Carte 'Haute'; $sp = $c.Child
    $g = New-Object System.Windows.Controls.StackPanel
    $g.Children.Add((Txt $env:USERNAME 17 'Texte' $true)) | Out-Null
    $texte = (T 'etat.acces_ouvert'); if ($etat -and (Prop $etat 'texte')) { $texte = [string](Prop $etat 'texte') }
    $g.Children.Add((Txt $texte 12 'Texte2')) | Out-Null
    $sp.Children.Add((Deux $g (Chip $(if ($ferme) { (T 'etat.ferme') } else { (T 'etat.ouvert') }) $(if ($ferme) { 'Rouge' } else { 'Vert' })))) | Out-Null
    # Modules : demander suppose le module Demandes ; du temps, des plages ou un temps d'écran à prolonger.
    $peutDemander = ModuleActif 'demandes'
    $fermeAppsSites = (ModuleActif 'blocage') -or (ModuleActif 'filtrage')
    if ($ferme -and $peutDemander -and ((ModuleActif 'plages') -or (ModuleActif 'temps'))) {
        $r = Rangee; $r.Margin = [System.Windows.Thickness]::new(0, 12, 0, 0)
        $r.Children.Add((Txt (T 'moi.demander_temps') 12 'Texte2' $false '0,0,10,0')) | Out-Null
        foreach ($m in 15, 30, 60) {
            $r.Children.Add((Bouton (T 'moi.plus_min' @{ m = $m }) 'Secondaire' { param($s, $e) Deposer @{ genre = 'temps'; minutes = [int]$s.Tag }; $s.Content = (T 'moi.envoye'); $s.IsEnabled = $false } $m)) | Out-Null
        }
        $sp.Children.Add($r) | Out-Null
    }
    $Contenu.Children.Add($c) | Out-Null

    $data = Lire 'fermes.json'

    # HA 2.5.1 (ajout 5 de l'auteur) : « Roblox ne marche pas : 6 sites bloqués » : une seule demande pour le logiciel et ses
    # sites, que le {decideur} accepte en bloc ou élément par élément.
    $groupes = @(Prop $data 'groupes' @() | Where-Object { @(Prop $_ 'sites' @()).Count -gt 0 })
    if ($groupes.Count -gt 0 -and $fermeAppsSites -and $peutDemander) {
        $Contenu.Children.Add((Section (T 'moi.bloque_par_logiciel'))) | Out-Null
        foreach ($gr in ($groupes | Select-Object -First 4)) {
            $app = [string](Prop $gr 'logiciel'); $lib = [string](Prop $gr 'libelle' $app); if (-not $lib) { $lib = $app }
            $sitesG = @(Prop $gr 'sites' @() | ForEach-Object { [string]$_ })
            $cg = Carte 'Haute'; $spg = $cg.Child
            $spg.Children.Add((Txt $lib 14 'Or' $true)) | Out-Null
            $spg.Children.Add((Txt ((Tn 'moi.groupe_sites' $sitesG.Count) + ' ' + ($sitesG -join ', ')) 11 'Texte2' $false '0,2,0,8')) | Out-Null
            $dejaG = $script:Demandees.ContainsKey('apps:' + $app)
            $bg = Bouton $(if ($dejaG) { (T 'moi.demande_ok') } else { (T 'moi.demander_pour' @{ nom = $lib }) }) 'Primaire' {
                param($s, $e); $t = $s.Tag
                Deposer @{ genre = 'apps'; nom = [string]$t.app; libelle = [string]$t.lib; sites = @($t.sites); duree = '' }
                $script:Demandees['apps:' + [string]$t.app] = $true
                $s.Content = (T 'moi.demande_ok'); $s.IsEnabled = $false
            } @{ app = $app; lib = $lib; sites = $sitesG }
            if ($dejaG) { $bg.IsEnabled = $false }
            $bg.HorizontalAlignment = 'Left'
            $spg.Children.Add($bg) | Out-Null
            $Contenu.Children.Add($cg) | Out-Null
        }
    }

    # Ce qui vient d'être bloqué : les derniers refus, pour demander tout de suite. HA 2.5.1 : aussi ceux de ses autres
    # appareils (heure, appareil, logiciel, tentatives), que Home Assistant a vus.
    $recents = @(Prop $data 'recents' @())
    $dejaVus = @{}; foreach ($x in $recents) { $dejaVus[[string](Prop $x 'nom')] = $true }
    foreach ($x in @(Prop $data 'ha_recents' @())) {
        $n = [string](Prop $x 'nom'); if (-not $n -or $dejaVus.ContainsKey($n)) { continue }
        $detail = @((Format-Heure ([DateTimeOffset]::FromUnixTimeSeconds([long][double](Prop $x 'quand' 0)).LocalDateTime) -Locale),
            [string](Prop $x 'appareil_nom'), [string](Prop $x 'logiciel'), (Tn 'moi.tentatives' ([int](Prop $x 'fois' 1)))) | Where-Object { $_ }
        $recents += [pscustomobject]@{ genre = 'sites'; nom = $n; raison = [string](Prop $x 'raison'); quand = ($detail -join ' · ') }
        $dejaVus[$n] = $true
    }
    if ($recents.Count -gt 0 -and $fermeAppsSites) {
        $Contenu.Children.Add((Section (T 'moi.bloque_recemment'))) | Out-Null
        $cr = Carte 'Haute'; $spr = $cr.Child
        $k = 0
        foreach ($x in ($recents | Select-Object -First 8)) {
            if ($k++ -gt 0) { $spr.Children.Add((Separateur)) | Out-Null }
            $genre = [string](Prop $x 'genre' 'apps'); $nom = [string](Prop $x 'nom'); $raison = [string](Prop $x 'raison')
            $type = $(if ($genre -eq 'sites') { (T 'commun.site') } else { (T 'commun.appli') })
            $g = New-Object System.Windows.Controls.StackPanel
            $g.Children.Add((Txt $nom 14 'Or' $true)) | Out-Null
            $g.Children.Add((Txt (([string](Prop $x 'quand')) + ' · ' + $type + $(if ($raison) { ' · ' + $raison } else { '' })) 11 'Texte2')) | Out-Null
            $deja = $script:Demandees.ContainsKey($genre + ':' + $nom)
            $attente = RefusJusqua $data $genre $nom
            if ($attente) { $g.Children.Add((Txt (T 'moi.refusee' @{ heure = $attente }) 11 'Rouge' $false '0,2,0,0')) | Out-Null }
            if (-not $peutDemander) { $spr.Children.Add($g) | Out-Null; continue }
            $b = Bouton $(if ($attente) { (T 'moi.refuse') } elseif ($deja) { (T 'moi.demande_ok') } else { (T 'moi.demander') }) 'Primaire' {
                param($s, $e); $script:Vue = @{ type = 'demande'; item = $s.Tag }; Rafraichir
            } @{ genre = $genre; nom = $nom; raison = $raison; type = $type; saisie = $false }
            if ($deja -or $attente) { $b.IsEnabled = $false }
            $spr.Children.Add((Deux $g $b)) | Out-Null
        }
        $Contenu.Children.Add($cr) | Out-Null
    }

    # Ni blocage d'applis ni filtrage web : rien ne peut être fermé ici, la section disparaît.
    if (-not $fermeAppsSites) { return }
    $Contenu.Children.Add((Section (T 'moi.ferme_ici'))) | Out-Null
    if ($peutDemander) { $Contenu.Children.Add((Txt (T 'moi.ferme_ici_aide') 12 'Texte2' $false '0,0,0,8')) | Out-Null }
    $filtre = Champ (T 'moi.chercher')
    $Contenu.Children.Add($filtre) | Out-Null
    $card = Carte; $sp2 = $card.Child
    $script:VM = @{ sp2 = $sp2; filtre = $filtre; data = $data; demander = $peutDemander }
    $script:RemplirMoi = {
        $sp2 = $script:VM.sp2; $filtre = $script:VM.filtre; $data = $script:VM.data
        $sp2.Children.Clear()
        $q = (Valeur $filtre).ToLowerInvariant()
        $lignes = @()
        foreach ($x in @(Prop $data 'apps' @())) { $lignes += @{ genre = 'apps'; nom = [string](Prop $x 'nom'); raison = [string](Prop $x 'raison'); type = (T 'commun.appli'); recent = [bool](Prop $x 'recent' $false) } }
        foreach ($x in @(Prop $data 'sites' @())) { $lignes += @{ genre = 'sites'; nom = [string](Prop $x 'nom'); raison = [string](Prop $x 'raison'); type = (T 'commun.site'); recent = [bool](Prop $x 'recent' $false) } }
        $lignes = @($lignes | Where-Object { -not $q -or $_.nom.ToLowerInvariant() -like "*$q*" } | Sort-Object { -not $_.recent }, { $_.type }, { $_.nom })
        if ($lignes.Count -eq 0) { $sp2.Children.Add((Txt (T 'moi.rien_ferme') 13 'Texte2')) | Out-Null }
        $k = 0
        foreach ($l in ($lignes | Select-Object -First 120)) {
            if ($k++ -gt 0) { $sp2.Children.Add((Separateur)) | Out-Null }
            $g = New-Object System.Windows.Controls.StackPanel
            $g.Children.Add((Txt $l.nom 14 $(if ($l.recent) { 'Or' } else { 'Texte' }) $true)) | Out-Null
            $g.Children.Add((Txt ($l.type + ' · ' + $l.raison) 11 'Texte2')) | Out-Null
            $deja = $script:Demandees.ContainsKey($l.genre + ':' + $l.nom)
            $attente = RefusJusqua $data $l.genre $l.nom
            if ($attente) { $g.Children.Add((Txt (T 'moi.refusee' @{ heure = $attente }) 11 'Rouge' $false '0,2,0,0')) | Out-Null }
            if (-not $script:VM.demander) { $sp2.Children.Add($g) | Out-Null; continue }
            $b = Bouton $(if ($attente) { (T 'moi.refuse') } elseif ($deja) { (T 'moi.demande_ok') } else { (T 'moi.demander') }) 'Secondaire' {
                param($s, $e); $script:Vue = @{ type = 'demande'; item = $s.Tag }; Rafraichir
            } @{ genre = $l.genre; nom = $l.nom; raison = $l.raison; type = $l.type; saisie = $false }
            if ($deja -or $attente) { $b.IsEnabled = $false }
            $sp2.Children.Add((Deux $g $b)) | Out-Null
        }
    }
    & $script:RemplirMoi
    $filtre.Add_TextChanged({ & $script:RemplirMoi })
    $Contenu.Children.Add($card) | Out-Null

    # Demander un site qui n'est pas dans la liste : modules Demandes et Filtrage web.
    if (-not ($peutDemander -and (ModuleActif 'filtrage'))) { return }
    $Contenu.Children.Add((Section (T 'moi.autre_site'))) | Out-Null
    $c3 = Carte; $sp3 = $c3.Child
    $presse = ''
    try { $pp = [System.Windows.Clipboard]::GetText(); if ($pp -match '^https?://\S+$') { $presse = $pp.Trim() } } catch { }
    $sp3.Children.Add((Txt $(if ($presse) { (T 'moi.lien_presse_papiers' @{ lien = $presse }) } else { (T 'moi.site_hors_liste') }) 12 'Texte2' $false '0,0,0,10')) | Out-Null
    $ok = Bouton (T 'moi.demander_site') 'Secondaire' {
        param($s, $e); $script:Vue = @{ type = 'demande'; item = @{ genre = 'sites'; nom = $(if ($s.Tag) { Hote $s.Tag } else { (T 'moi.autre_site') }); raison = ''; type = (T 'commun.site'); saisie = $true; lien = [string]$s.Tag } }; Rafraichir
    } $presse
    $ok.HorizontalAlignment = 'Right'
    $sp3.Children.Add($ok) | Out-Null
    $Contenu.Children.Add($c3) | Out-Null
}

# ------------------------------------------------------------------ L'icône
Add-Type -AssemblyName Microsoft.VisualBasic
$iconeTray = [System.Drawing.SystemIcons]::Shield
if (Test-Path $logoPath) { try { $iconeTray = [System.Drawing.Icon]::FromHandle(([System.Drawing.Bitmap]::new($logoPath)).GetHicon()) } catch { } }
$tray = New-Object System.Windows.Forms.NotifyIcon
$tray.Icon = $iconeTray; $tray.Text = 'Vision'; $tray.Visible = $true
$menu = New-Object System.Windows.Forms.ContextMenuStrip
$ouvrir = $menu.Items.Add((T 'tray.ouvrir_vision')); $ouvrir.Font = New-Object System.Drawing.Font('Segoe UI', 10, [System.Drawing.FontStyle]::Bold)
$ouvrir.Add_Click({ Montrer })
$menu.Items.Add('-') | Out-Null
$etatItem = $menu.Items.Add('Vision'); $etatItem.Enabled = $false
$menu.Add_Opening({ $e = Lire 'etat.json'; $etatItem.Text = $(if ($e -and (Prop $e 'texte')) { [string](Prop $e 'texte') } else { (T 'etat.acces_ouvert') }) })
# Soutien : un merci quand Home Assistant le dit actif ; sinon, sur un appareil Décideur seulement, le lien de soutien.
$soutienItem = $menu.Items.Add((TexteSoutien 'soutien.soutenir')); $soutienItem.Enabled = $false
$soutienItem.Add_Click({ param($s, $e) if ($s.Tag) { try { Start-Process 'https://buymeacoffee.com/soprez' } catch { } } })
$menu.Add_Opening({ $e = Lire 'etat.json'; $actif = [bool](Prop $e 'soutien' $false); $soutienItem.Text = (TexteSoutien $(if ($actif) { 'soutien.merci' } else { 'soutien.soutenir' })); $soutienItem.Tag = (-not $actif -and [bool](Prop $e 'decideur' $false)); $soutienItem.Enabled = [bool]$soutienItem.Tag })
$tray.ContextMenuStrip = $menu
$tray.Add_MouseClick({ param($s, $e) if ($e.Button -eq 'Left') { Montrer } })

function Montrer() {
    $script:VerrouAffiche = $false
    Rafraichir
    $script:W.Show()
    if ($script:W.WindowState -eq 'Minimized') { $script:W.WindowState = 'Normal' }
    # Elle vient devant quand on l'appelle, puis se comporte comme toute fenêtre : un clic ailleurs la laisse derrière.
    $script:W.Topmost = $true; $script:W.Activate() | Out-Null; $script:W.Topmost = $false
    # Verrouillée : Windows Hello se propose de lui-même.
    if ($script:VerrouAffiche) {
        if (HelloDisponible) { if (HelloDemander) { Deverrouiller }; try { $script:W.Activate() | Out-Null } catch { } }
    }
}

$horloge = New-Object System.Windows.Forms.Timer; $horloge.Interval = 30000
$horloge.Add_Tick({
    $e = Lire 'etat.json'; $txt = 'Vision'
    if ($e -and (Prop $e 'texte')) { $txt = [string](Prop $e 'texte') }
    if ($txt.Length -gt 63) { $txt = $txt.Substring(0, 63) }
    $tray.Text = $txt
    if ($script:W.IsVisible -and $script:Page -eq 'maison' -and (-not $script:Vue -or ([string]$script:Vue.type -eq 'personne' -and -not $script:Vue.temps))) { Rafraichir }
})
$horloge.Start()

# ------------------------------------------------------------------ Ancien écran de veille (retiré en 1.91.0)
# 1.91.0 (retour d'essai) : l'écran de veille des PC est retiré. Dans la session, une fois au démarrage de la fenêtre :
# Windows ne désigne plus Vision.scr comme écran de veille (le choix d'un autre écran de veille est gardé), et les
# restes de la veille (profils Edge de l'ancienne veille web, images des caméras) sont effacés.
try {
    $cleWin = 'HKCU:\Control Panel\Desktop'
    $scr = [string](Get-ItemProperty -Path $cleWin -Name 'SCRNSAVE.EXE' -ErrorAction SilentlyContinue).'SCRNSAVE.EXE'
    if ($scr -and ([System.IO.Path]::GetFileName($scr) -ieq 'Vision.scr')) {
        Remove-ItemProperty -Path $cleWin -Name 'SCRNSAVE.EXE' -ErrorAction SilentlyContinue
        Set-ItemProperty -Path $cleWin -Name 'ScreenSaveActive' -Value '0' -ErrorAction SilentlyContinue
    }
} catch { }
try { Get-ChildItem -Path (Join-Path $env:LOCALAPPDATA 'Vision') -Directory -Filter 'veille-edge-*' -ErrorAction SilentlyContinue | Remove-Item -Recurse -Force -ErrorAction SilentlyContinue } catch { }
try { Get-ChildItem -Path $env:TEMP -Filter 'vision-camera*' -ErrorAction SilentlyContinue | Remove-Item -Force -ErrorAction SilentlyContinue } catch { }

# ------------------------------------------------------------------ Messages de Home Assistant (1.67.0, docs/messages.md)
# L'agent dépose messages.json (et les médias, dans messages\) ; on les montre aux couleurs de ce PC,
# par-dessus tout, à la position et à la taille demandées, ou en plein écran (et toujours en plein écran
# pendant l'écran de veille). On dépose les accusés (accuse-*.json), que l'agent envoie à Home Assistant.
# Ce qu'on sait de chaque message (affiché à, vu, fini) vit dans le profil : messages-etat.json.
$script:MsgEtatFichier = Join-Path $env:APPDATA 'Vision\messages-etat.json'
$script:MsgCourant = $null; $script:MsgFenetre = $null; $script:MsgLecteur = $null
$script:MsgPlein = $false; $script:MsgPeutFermer = $false; $script:MsgMinuteur = $null
function MsgEtat() {
    $h = @{}
    try {
        if (Test-Path $script:MsgEtatFichier) {
            $o = Get-Content $script:MsgEtatFichier -Raw -Encoding UTF8 | ConvertFrom-Json
            foreach ($p in $o.PSObject.Properties) { $e = @{}; foreach ($q in $p.Value.PSObject.Properties) { $e[$q.Name] = $q.Value }; $h[$p.Name] = $e }
        }
    } catch { }
    return $h
}
function MsgNote([string]$uid, [string]$cle, $valeur) {
    $h = MsgEtat
    if (-not $h.ContainsKey($uid)) { $h[$uid] = @{} }
    $h[$uid][$cle] = $valeur
    # On n'oublie que les vieux messages finis : 200 au plus.
    if ($h.Count -gt 200) { foreach ($k in @($h.Keys | Select-Object -First ($h.Count - 200))) { $h.Remove($k) } }
    try {
        $d = Split-Path $script:MsgEtatFichier
        if (-not (Test-Path $d)) { New-Item -ItemType Directory -Path $d -Force | Out-Null }
        ($h | ConvertTo-Json -Compress -Depth 3) | Set-Content -Path $script:MsgEtatFichier -Encoding UTF8
    } catch { }
}
function MsgVal([string]$uid, [string]$cle) { $h = MsgEtat; if ($h.ContainsKey($uid)) { return $h[$uid][$cle] }; return $null }
function MsgListe() {
    $l = Lire 'messages.json'
    if ($null -eq $l) { return @() }
    return @(@($l) | Where-Object { $_ -and -not (MsgVal ([string]$_.uid) 'fini') })
}
function MsgAccuser($m, [string]$etat, [string]$detail = '') {
    $nom = Join-Path $script:Dossier ('accuse-{0}.json' -f [DateTime]::UtcNow.Ticks)
    try { (@{ uid = [string]$m.uid; message = [int]$m.id; etat = $etat; detail = $detail } | ConvertTo-Json -Compress) | Set-Content -Path $nom -Encoding UTF8 } catch { }
    if ($etat -in @('lu', 'libere_parent', 'libere_auto', 'expire')) { MsgNote ([string]$m.uid) 'fini' $true }
}
function MsgPinceau([string]$cle, [int]$alpha = 255) {
    $c = [System.Windows.Media.ColorConverter]::ConvertFromString([string]$script:Pal[$cle])
    $c = [System.Windows.Media.Color]::FromArgb([byte]$alpha, $c.R, $c.G, $c.B)
    return (New-Object System.Windows.Media.SolidColorBrush $c)
}
function MsgFermerFenetre() {
    try { if ($script:MsgMinuteur) { $script:MsgMinuteur.Stop() } } catch { }
    try { if ($script:MsgLecteur) { $script:MsgLecteur.Stop(); $script:MsgLecteur.Close() } } catch { }
    $script:MsgLecteur = $null; $script:MsgMinuteur = $null; $script:MsgCodeNote = $null; $script:MsgCode = $null
    $script:MsgPeutFermer = $true
    try { if ($script:MsgFenetre) { $script:MsgFenetre.Close() } } catch { }
    $script:MsgFenetre = $null; $script:MsgCourant = $null
}
function MsgFermer($m, [string]$etat) { MsgAccuser $m $etat; MsgFermerFenetre }
function MsgLever($m, [string]$etat, [string]$detail) {
    MsgAccuser $m $etat $detail
    if ($script:MsgCourant -and [string]$script:MsgCourant.uid -eq [string]$m.uid) { MsgFermerFenetre }
}
function MsgBouton([string]$texte, [bool]$principal, [scriptblock]$action) {
    $b = New-Object System.Windows.Controls.Button
    $b.Content = $texte; $b.Padding = '18,8,18,8'; $b.Margin = '10,0,0,0'; $b.FontSize = 15; $b.Cursor = 'Hand'
    $b.Background = $(if ($principal) { MsgPinceau 'Or' } else { MsgPinceau 'Haute' })
    $b.Foreground = $(if ($principal) { MsgPinceau 'OrSombre' } else { MsgPinceau 'Texte' })
    $b.BorderBrush = MsgPinceau 'Ligne'
    $b.Add_Click($action)
    return $b
}
# Faille 2.2-1 : le code tapé sur un bloquant « parent » part à Home Assistant par l'agent (SYSTEM),
# dans un fichier que l'utilisateur qui l'écrit ne peut ni relire ni rouvrir. Rien n'est vérifié ici : Home
# Assistant lève le message (`libere` dans messages.json) ou dit pourquoi non (`codeRetour`).
$script:MsgCodeNote = $null; $script:MsgCode = $null; $script:MsgCodeEnvoiA = [datetime]::MinValue; $script:MsgCodeVu = ''
function MsgCodeFichier([string]$json) {
    $nom = Join-Path $script:Dossier ('code-{0}.json' -f [DateTime]::UtcNow.Ticks)
    $sec = New-Object System.Security.AccessControl.FileSecurity
    $sec.SetAccessRuleProtection($true, $false)
    foreach ($sid in @('S-1-5-18', 'S-1-5-32-544')) {
        $sec.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule((New-Object System.Security.Principal.SecurityIdentifier($sid)), 'FullControl', 'Allow')))
    }
    # « Droits du propriétaire » : l'auteur du fichier n'en garde que la lecture des attributs (ni lecture, ni ACL).
    $sec.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule((New-Object System.Security.Principal.SecurityIdentifier('S-1-3-4')), 'ReadAttributes', 'Allow')))
    $fs = New-Object System.IO.FileStream($nom, [System.IO.FileMode]::CreateNew, [System.Security.AccessControl.FileSystemRights]::Write, [System.IO.FileShare]::None, 4096, [System.IO.FileOptions]::None, $sec)
    try { $o = [System.Text.Encoding]::UTF8.GetBytes($json); $fs.Write($o, 0, $o.Length) } finally { $fs.Dispose() }
}
function MsgCodeDire([string]$texte, [string]$couleur = 'Texte2') {
    if (-not $script:MsgCodeNote) { return }
    $script:MsgCodeNote.Text = $texte; $script:MsgCodeNote.Foreground = MsgPinceau $couleur
    $script:MsgCodeNote.Visibility = $(if ($texte) { 'Visible' } else { 'Collapsed' })
}
function MsgCodeEnvoyer() {
    $m = $script:MsgCourant; $pb = $script:MsgCode
    if (-not $m -or -not $pb) { return }
    $code = $pb.Password; $pb.Clear()
    if (-not $code) { return }
    if (((Get-Date) - $script:MsgCodeEnvoiA).TotalSeconds -lt 20) { MsgCodeDire (T 'code.en_cours'); return }
    try {
        MsgCodeFichier ((@{ message = [int]$m.id; uid = [string]$m.uid; code = $code } | ConvertTo-Json -Compress))
        $script:MsgCodeEnvoiA = Get-Date
        MsgCodeDire (T 'code.verification')
    } catch { MsgCodeDire (T 'code.envoi_echec') 'Rouge' }
    $code = $null
}
function MsgCodeRetour($m) {
    # La réponse de Home Assistant, déposée par l'agent dans messages.json.
    $r = Prop $m 'codeRetour' $null
    if ($null -eq $r) { return }
    $t = [string](Prop $r 't' '')
    if (-not $t -or $t -eq $script:MsgCodeVu) { return }
    $script:MsgCodeVu = $t; $script:MsgCodeEnvoiA = [datetime]::MinValue
    switch ([string](Prop $r 'etat' '')) {
        'ok' { MsgCodeDire (T 'code.ok') 'Vert' }
        'faux' { MsgCodeDire (T 'code.faux' @{ n = [int](Prop $r 'restants' 0) }) 'Rouge' }
        'trop' { MsgCodeDire (T 'code.trop' @{ min = [int][Math]::Ceiling([int](Prop $r 'attente' 60) / 60) }) 'Rouge' }
        'aucun' { MsgCodeDire (T 'code.aucun') 'Rouge' }
        'inconnu' { MsgCodeDire (T 'code.inconnu') 'Rouge' }
        'verrouille' { MsgCodeDire (T 'code.verrouille') 'Rouge' }
        'ancien' { MsgCodeDire (T 'code.ancien') 'Rouge' }
        default { MsgCodeDire (T 'code.injoignable') 'Rouge' }
    }
}
function MsgMontrer($m) {
    $uid = [string]$m.uid
    $bloquant = [string]$m.bloquant
    $plein = ([string]$m.position -eq 'plein_ecran' -or $bloquant -ne 'non')
    $script:MsgCourant = $m; $script:MsgPlein = $plein; $script:MsgPeutFermer = ($bloquant -eq 'non')
    $ecranL = [System.Windows.SystemParameters]::PrimaryScreenWidth; $ecranH = [System.Windows.SystemParameters]::PrimaryScreenHeight
    $f = New-Object System.Windows.Window
    $f.WindowStyle = 'None'; $f.AllowsTransparency = $true; $f.Background = [System.Windows.Media.Brushes]::Transparent
    $f.Topmost = $true; $f.ShowInTaskbar = $false; $f.ResizeMode = 'NoResize'; $f.WindowStartupLocation = 'Manual'
    $f.FontFamily = (New-Object System.Windows.Media.FontFamily('Segoe UI'))
    # La carte, aux couleurs de Vision sur ce PC.
    $carte = New-Object System.Windows.Controls.Border
    $carte.Background = MsgPinceau 'Carte'; $carte.BorderBrush = MsgPinceau 'Or'; $carte.BorderThickness = 1
    $carte.CornerRadius = 14; $carte.Padding = '24,20,24,18'
    $pile = New-Object System.Windows.Controls.StackPanel
    $titre = New-Object System.Windows.Controls.TextBlock
    $titre.Text = $(if ([string]$m.title) { [string]$m.title } else { 'Vision' }); $titre.FontSize = $(if ($plein) { 32 } else { 19 })
    $titre.FontWeight = 'Bold'; $titre.Foreground = MsgPinceau 'Or'; $titre.TextWrapping = 'Wrap'
    $pile.Children.Add($titre) | Out-Null
    if ([string]$m.text) {
        $t = New-Object System.Windows.Controls.TextBlock
        $t.Text = [string]$m.text; $t.FontSize = $(if ($plein) { 22 } else { 15 }); $t.Foreground = MsgPinceau 'Texte'
        $t.TextWrapping = 'Wrap'; $t.Margin = '0,8,0,0'
        $pile.Children.Add($t) | Out-Null
    }
    $fichier = [string](Prop $m 'fichier' '')
    $hauteurMedia = $ecranH * $(if ($plein) { 0.55 } else { 0.3 })
    if ([string]$m.type -ne 'texte') {
        if (-not $fichier -or -not (Test-Path $fichier)) {
            MsgAccuser $m 'erreur' 'media'
        } elseif ([string]$m.type -eq 'image') {
            try {
                $bmp = New-Object System.Windows.Media.Imaging.BitmapImage
                $bmp.BeginInit(); $bmp.CacheOption = 'OnLoad'; $bmp.UriSource = New-Object Uri($fichier); $bmp.EndInit()
                $img = New-Object System.Windows.Controls.Image
                $img.Source = $bmp; $img.MaxHeight = $hauteurMedia; $img.Margin = '0,12,0,0'; $img.Stretch = 'Uniform'
                $pile.Children.Add($img) | Out-Null
            } catch { MsgAccuser $m 'erreur' 'format' }
        } else {
            # Son et vidéo : le lecteur de WPF (absent des éditions « N » de Windows sans lecteur multimédia).
            try {
                $lect = New-Object System.Windows.Controls.MediaElement
                $lect.LoadedBehavior = 'Manual'; $lect.UnloadedBehavior = 'Stop'; $lect.Source = New-Object Uri($fichier)
                $lect.Margin = '0,12,0,0'
                if ([string]$m.type -eq 'video') { $lect.MaxHeight = $hauteurMedia } else { $lect.Height = 0 }
                $lect.Add_MediaFailed({ param($s, $e) if ($script:MsgCourant) { MsgAccuser $script:MsgCourant 'erreur' 'lecteur' } })
                $pile.Children.Add($lect) | Out-Null
                if ([string]$m.type -eq 'audio') {
                    $note = New-Object System.Windows.Controls.TextBlock
                    $note.Text = (T 'message.sonore'); $note.Foreground = MsgPinceau 'Texte2'; $note.Margin = '0,8,0,0'
                    $pile.Children.Add($note) | Out-Null
                }
                $script:MsgLecteur = $lect
                $f.Add_ContentRendered({ try { if ($script:MsgLecteur) { $script:MsgLecteur.Play() } } catch { } })
            } catch { MsgAccuser $m 'erreur' 'lecteur' }
        }
    }
    # Les boutons : « J'ai lu », ou le code parent pour un bloquant « parent ».
    $boutons = New-Object System.Windows.Controls.StackPanel
    $boutons.Orientation = 'Horizontal'; $boutons.HorizontalAlignment = 'Right'; $boutons.Margin = '0,18,0,0'
    if ($bloquant -eq 'parent') {
        $attente = New-Object System.Windows.Controls.TextBlock
        $attente.Text = (T 'message.en_attente'); $attente.Foreground = MsgPinceau 'Texte2'; $attente.FontSize = 16; $attente.Margin = '0,14,0,0'
        $pile.Children.Add($attente) | Out-Null
        # Faille 2.2-1 : le code de gestion est vérifié par Home Assistant, qui lève le message lui-même.
        $noteCode = New-Object System.Windows.Controls.TextBlock
        $noteCode.Foreground = MsgPinceau 'Texte2'; $noteCode.FontSize = 14; $noteCode.Margin = '0,6,0,0'; $noteCode.TextWrapping = 'Wrap'
        $noteCode.Visibility = 'Collapsed'
        $pile.Children.Add($noteCode) | Out-Null
        $script:MsgCodeNote = $noteCode
        $code = New-Object System.Windows.Controls.PasswordBox
        $code.Width = 160; $code.FontSize = 16; $code.Padding = '6,4,6,4'; $code.VerticalContentAlignment = 'Center'; $code.MaxLength = 32
        $code.Add_KeyDown({ param($s, $e) if ($e.Key -eq 'Return') { MsgCodeEnvoyer } })
        $boutons.Children.Add($code) | Out-Null
        $script:MsgCode = $code
        $boutons.Children.Add((MsgBouton (T 'message.liberer') $true { MsgCodeEnvoyer })) | Out-Null
    } elseif ($bloquant -eq 'lu') {
        $boutons.Children.Add((MsgBouton (T 'message.jai_lu') $true { MsgFermer $script:MsgCourant 'lu' })) | Out-Null
    } else {
        $boutons.Children.Add((MsgBouton (T 'message.plus_tard') $false { MsgFermer $script:MsgCourant 'ferme' })) | Out-Null
        $boutons.Children.Add((MsgBouton (T 'message.jai_lu') $true { MsgFermer $script:MsgCourant 'lu' })) | Out-Null
    }
    $pile.Children.Add($boutons) | Out-Null
    $carte.Child = $pile
    if ($plein) {
        $fond = New-Object System.Windows.Controls.Grid
        $fond.Background = MsgPinceau 'Fond' 235
        $carte.Width = $ecranL * 0.6; $carte.HorizontalAlignment = 'Center'; $carte.VerticalAlignment = 'Center'
        $fond.Children.Add($carte) | Out-Null
        $f.Content = $fond
        $f.Left = 0; $f.Top = 0; $f.Width = $ecranL; $f.Height = $ecranH
    } else {
        $part = switch ([string]$m.taille) { 'petite' { 0.3 } 'grande' { 0.65 } default { 0.45 } }
        $zone = [System.Windows.SystemParameters]::WorkArea
        $f.Width = [Math]::Max(320, $zone.Width * $part); $f.SizeToContent = 'Height'; $f.Content = $carte
        $position = [string]$m.position
        $f.Add_SizeChanged({ param($s, $e)
            $z = [System.Windows.SystemParameters]::WorkArea; $marge = 18; $p = [string]$script:MsgCourant.position
            $x = $z.Left + ($z.Width - $s.ActualWidth) / 2; $y = $z.Top + ($z.Height - $s.ActualHeight) / 2
            if ($p -like '*gauche*') { $x = $z.Left + $marge } elseif ($p -like '*droite*') { $x = $z.Right - $s.ActualWidth - $marge }
            if ($p -like 'haut*') { $y = $z.Top + $marge } elseif ($p -like 'bas*') { $y = $z.Bottom - $s.ActualHeight - $marge }
            $s.Left = $x; $s.Top = $y })
    }
    $f.Add_PreviewKeyDown({ param($s, $e) if ($e.Key -eq 'Escape' -and $script:MsgPeutFermer -and $script:MsgCourant) { MsgFermer $script:MsgCourant 'ferme'; $e.Handled = $true } })
    # Un bloquant ne se ferme ni par Alt+F4 ni autrement que par ses boutons.
    $f.Add_Closing({ param($s, $e) if (-not $script:MsgPeutFermer) { $e.Cancel = $true } })
    $script:MsgFenetre = $f
    try { [System.Windows.Forms.Integration.ElementHost]::EnableModelessKeyboardInterop($f) } catch { }
    $f.Show(); $f.Activate() | Out-Null
    if (-not (MsgVal $uid 'afficheA')) { MsgNote $uid 'afficheA' ([DateTimeOffset]::UtcNow.ToUnixTimeSeconds()); MsgAccuser $m 'affiche' }
    MsgNote $uid 'vuA' ([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())
    $son = [string](Prop $m 'sound' '')
    if ([string]$m.type -in @('texte', 'image') -and $son -and $son -notmatch 'Silent|silencieux') { try { [System.Media.SystemSounds]::Asterisk.Play() } catch { } }
    $duree = [int](Prop $m 'duree' 0)
    if ($bloquant -eq 'non' -and $duree -gt 0) {
        $script:MsgMinuteur = New-Object System.Windows.Threading.DispatcherTimer
        $script:MsgMinuteur.Interval = [TimeSpan]::FromSeconds($duree)
        $script:MsgMinuteur.Add_Tick({ if ($script:MsgCourant) { MsgFermer $script:MsgCourant 'ferme' } })
        $script:MsgMinuteur.Start()
    }
}
# Les messages non lus restent dans le menu de l'icône Vision ; un clic rouvre le premier.
$script:MsgItem = $menu.Items.Add((T 'message.menu'))
$script:MsgItem.Visible = $false
$script:MsgItem.Add_Click({
    if (-not (ModuleActif 'messages')) { return }
    $m = @(MsgListe) | Select-Object -First 1
    if ($m -and -not $script:MsgCourant) { $c = $m.PSObject.Copy(); $c.duree = 0; MsgMontrer $c }
})
$script:MsgTic = New-Object System.Windows.Threading.DispatcherTimer
$script:MsgTic.Interval = [TimeSpan]::FromSeconds(2)
$script:MsgTic.Add_Tick({
    try {
        # Module Messages coupé : rien ne s'affiche. Un message à l'écran se ferme sans accusé (ni « lu » ni
        # « fermé ») : il reste dans la file et revient, bloquant compris, si le module est réactivé.
        if (-not (ModuleActif 'messages')) {
            if ($script:MsgCourant -or $script:MsgFenetre) { MsgFermerFenetre }
            $script:MsgItem.Visible = $false
            return
        }
        $maintenant = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
        # Faille 2.2-1 : l'agent marque `relance` un bloquant dont Home Assistant a refusé la levée (accusé déposé à la
        # main, limite pas encore passée) : il redevient à afficher, et sa limite repart de maintenant.
        $listeLue = Lire 'messages.json'
        foreach ($r in @(@($listeLue) | Where-Object { $_ -and [string](Prop $_ 'bloquant' '') -eq 'parent' -and (Prop $_ 'relance' '') })) {
            if ([string](MsgVal ([string]$r.uid) 'relanceVue') -ne [string]$r.relance) {
                MsgNote ([string]$r.uid) 'relanceVue' ([string]$r.relance); MsgNote ([string]$r.uid) 'fini' $false
                MsgNote ([string]$r.uid) 'afficheA' ([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())
            }
        }
        if ($script:MsgCourant) {
            $courant = @(@($listeLue) | Where-Object { $_ -and [string]$_.uid -eq [string]$script:MsgCourant.uid }) | Select-Object -First 1
            if ($courant) { MsgCodeRetour $courant }
        }
        foreach ($m in @(MsgListe)) {
            $aff = [double](MsgVal ([string]$m.uid) 'afficheA')
            if ([string]$m.bloquant -eq 'parent' -and (Prop $m 'libere' $false)) { MsgLever $m 'libere_parent' 'ha' }
            elseif ([string]$m.bloquant -eq 'parent' -and $aff -gt 0 -and ($maintenant - $aff) -gt ([int]$m.limite * 60)) { MsgLever $m 'libere_auto' '' }
            elseif ([string]$m.bloquant -eq 'non' -and $maintenant -gt [double]$m.expire) {
                MsgAccuser $m 'expire'
                if ($script:MsgCourant -and [string]$script:MsgCourant.uid -eq [string]$m.uid) { MsgFermerFenetre }
            }
        }
        $liste = @(MsgListe)
        $script:MsgItem.Visible = ($liste.Count -gt 0)
        $script:MsgItem.Text = (Tn 'message.non_lus' $liste.Count)
        if ($script:MsgCourant) {
            return
        }
        $a = @($liste | Where-Object { -not (MsgVal ([string]$_.uid) 'vuA') -or [string]$_.bloquant -ne 'non' })
        $m = @($a | Where-Object { [string]$_.bloquant -ne 'non' }) | Select-Object -First 1
        if (-not $m) { $m = $a | Select-Object -First 1 }
        if ($m) { MsgMontrer $m }
    } catch { }
})
$script:MsgTic.Start()

# Les réglages de ce PC, dans la fenêtre (onglet Réglages) : thème de la fenêtre et accès.
function Coche([string]$t, [bool]$etat, $action, $tag = $null) {
    $c = New-Object System.Windows.Controls.CheckBox
    $c.Content = $t; $c.IsChecked = $etat; $c.Style = $script:W.Resources['Coche']; $c.Tag = $tag
    $c.Margin = [System.Windows.Thickness]::new(0, 6, 0, 6)
    if ($action) { $c.Add_Click($action) }
    return $c
}

# Changer de thème : les couleurs sont posées à la création de la fenêtre, on la recrée donc
# au même endroit et dans le même état, sans que rien ne se ferme.
function ChangerTheme([string]$nom) {
    $rr = SvReglages; $rr.theme = $nom; SvEcrire $rr
    $script:Pal = PalEffective $rr
    $ancienne = $script:W
    $gauche = $ancienne.Left; $haut = $ancienne.Top; $etatF = $ancienne.WindowState
    CreerFenetre
    $script:W.WindowStartupLocation = 'Manual'; $script:W.Left = $gauche; $script:W.Top = $haut
    Rafraichir
    $script:W.Show(); $script:W.WindowState = $etatF; $script:W.Activate() | Out-Null
    try { $ancienne.Close() } catch { }
}

function PageReglages() {
    $r = SvReglages
    $Contenu.Children.Add((Section (T 'reglages.theme'))) | Out-Null
    # Vingt-huit thèmes en boutons prenaient la moitié de la page : un menu qui se déroule, fermé par défaut,
    # avec la pastille de chaque thème (son fond, son accent) devant son nom.
    $carteT = Carte
    $pastille = {
        param([string]$nom)
        $pal = $script:Themes[$nom]
        $rond = New-Object System.Windows.Shapes.Ellipse
        $rond.Width = 16; $rond.Height = 16; $rond.StrokeThickness = 4; $rond.VerticalAlignment = 'Center'
        $rond.Fill = New-Object System.Windows.Media.SolidColorBrush ([System.Windows.Media.ColorConverter]::ConvertFromString([string]$pal.Or))
        $rond.Stroke = New-Object System.Windows.Media.SolidColorBrush ([System.Windows.Media.ColorConverter]::ConvertFromString([string]$pal.Fond))
        $cadre = New-Object System.Windows.Controls.Border
        $cadre.CornerRadius = [System.Windows.CornerRadius]::new(10); $cadre.BorderThickness = [System.Windows.Thickness]::new(1); $cadre.BorderBrush = (Pinceau 'Ligne')
        $cadre.Margin = [System.Windows.Thickness]::new(0, 0, 8, 0); $cadre.Child = $rond; $cadre.VerticalAlignment = 'Center'
        return $cadre
    }
    $tete = New-Object System.Windows.Controls.Button
    $tete.Style = $W.Resources['Secondaire']; $tete.HorizontalAlignment = 'Left'; $tete.Margin = [System.Windows.Thickness]::new(0, 0, 0, 8)
    $lt = Rangee
    $lt.Children.Add((& $pastille ([string]$r.theme))) | Out-Null
    $lt.Children.Add((Txt (NomTheme ([string]$r.theme)) 13 'Texte' $true)) | Out-Null
    $lt.Children.Add((Txt '▾' 13 'Texte2' $false '10,0,0,0')) | Out-Null
    $tete.Content = $lt
    $carteT.Child.Children.Add($tete) | Out-Null
    $script:ListeThemes = New-Object System.Windows.Controls.WrapPanel
    $script:ListeThemes.Visibility = 'Collapsed'; $script:ListeThemes.Margin = [System.Windows.Thickness]::new(0, 0, 0, 8)
    $tete.Add_Click({ if ($script:ListeThemes.Visibility -eq 'Visible') { $script:ListeThemes.Visibility = 'Collapsed' } else { $script:ListeThemes.Visibility = 'Visible' } })
    foreach ($nomTheme in $script:Themes.Keys) {
        $bt = New-Object System.Windows.Controls.Button
        $bt.Style = $W.Resources['Secondaire']; $bt.Tag = $nomTheme
        $bt.Margin = [System.Windows.Thickness]::new(0, 0, 6, 6)
        $li = Rangee
        $li.Children.Add((& $pastille ([string]$nomTheme))) | Out-Null
        $li.Children.Add((Txt (NomTheme ([string]$nomTheme)) 13 'Texte' ($r.theme -eq $nomTheme))) | Out-Null
        if ($r.theme -eq $nomTheme) { $li.Children.Add((Txt '✓' 13 'Or' $true '10,0,0,0')) | Out-Null }
        $bt.Content = $li
        $bt.Add_Click({ param($s, $e) if ((SvReglages).theme -eq [string]$s.Tag) { $script:ListeThemes.Visibility = 'Collapsed'; return }; ChangerTheme ([string]$s.Tag) })
        $script:ListeThemes.Children.Add($bt) | Out-Null
    }
    $carteT.Child.Children.Add($script:ListeThemes) | Out-Null
    # 1.86.0 : le mode sombre, la version sombre du thème choisi.
    $carteT.Child.Children.Add((Txt (T 'reglages.mode_sombre') 12 'Texte2' $false '0,4,0,6')) | Out-Null
    $modes = New-Object System.Windows.Controls.WrapPanel
    foreach ($defMode in @(@('jamais', (T 'reglages.sombre_jamais')), @('systeme', (T 'reglages.sombre_windows')), @('toujours', (T 'reglages.sombre_toujours')))) {
        $bm = New-Object System.Windows.Controls.Primitives.ToggleButton
        $bm.Content = $defMode[1]; $bm.Style = $W.Resources['Onglet']; $bm.Tag = $defMode[0]; $bm.IsChecked = ([string]$r.sombre -eq $defMode[0])
        $bm.Margin = [System.Windows.Thickness]::new(0, 0, 6, 6)
        $bm.Add_Click({ param($s, $e) $rr = SvReglages; $rr.sombre = [string]$s.Tag; SvEcrire $rr; ChangerTheme ([string]$rr.theme) })
        $modes.Children.Add($bm) | Out-Null
    }
    $carteT.Child.Children.Add($modes) | Out-Null
    $carteT.Child.Children.Add((Txt (T 'reglages.theme_pour_fenetre') 11 'Texte2')) | Out-Null
    $Contenu.Children.Add($carteT) | Out-Null

    $Contenu.Children.Add((Section (T 'reglages.acces'))) | Out-Null
    $carteA = Carte
    $hello = HelloDisponible
    if ($hello) {
        $carteA.Child.Children.Add((Coche (T 'reglages.hello_demander') ([bool]$r.verrou) {
            param($s, $e)
            $rr = SvReglages; $rr.verrou = [bool]$s.IsChecked; SvEcrire $rr; Rafraichir
        })) | Out-Null
        $carteA.Child.Children.Add((Txt (T 'reglages.hello_aide') 11 'Texte2' $false '0,2,0,10')) | Out-Null
    } else {
        $carteA.Child.Children.Add((Txt (T 'reglages.hello_impossible') 11 'Texte2' $false '0,2,0,10')) | Out-Null
    }
    # Le code qui libère un message bloquant ne se choisit plus ici : il se règle dans Home Assistant.
    $carteA.Child.Children.Add((Txt (T 'reglages.code_ha') 11 'Texte2' $false '0,8,0,0')) | Out-Null
    $Contenu.Children.Add($carteA) | Out-Null

}

if ($Depart) { $script:Page = $Depart; Montrer }

[System.Windows.Forms.Application]::Run()
'@
$script:TraySource = $script:TraySource.Replace('# @@LANGUE@@', (Get-LanguePreambule))

function Remove-EcranDeVeille {
    <#
        1.91.0 (retour d'essai) : l'écran de veille des PC est retiré. Vision.scr et son
        marqueur quittent Windows (réessayé à chaque tour tant que Windows le
        tient ouvert) ; une fois par démarrage, les données de l'ancienne veille
        (veille.json et veille.log de la sonde) sont effacées.
    #>
    param([string]$Cible = (Join-Path $env:SystemRoot 'System32\Vision.scr'))
    if (-not $script:VeilleRestesFaits) {
        $script:VeilleRestesFaits = $true
        foreach ($nom in @('veille.json', 'veille.log')) {
            Remove-Item -LiteralPath (Join-Path $script:SondeDir $nom) -Force -ErrorAction SilentlyContinue
        }
    }
    $marque = Join-Path (Split-Path $ConfigPath -Parent) 'vision-scr.txt'
    if (-not (Test-Path -LiteralPath $Cible) -and -not (Test-Path -LiteralPath $marque)) { return }
    try {
        if (Test-Path -LiteralPath $Cible) { Remove-Item -LiteralPath $Cible -Force -ErrorAction Stop }
        Remove-Item -LiteralPath $marque -Force -ErrorAction SilentlyContinue
        $script:ScrRetraitDit = $false
        Write-Journal "Ecran de veille Vision retire de Windows (retire des PC en 1.91.0)."
    } catch {
        if (-not $script:ScrRetraitDit) {
            $script:ScrRetraitDit = $true
            Write-Journal "Ecran de veille Vision pas encore retire de Windows : $_" 'WARN'
        }
    }
}

function Write-ToastScript {
    # Dépose (ou rafraîchit) les scripts d'affichage et le logo à côté de la sonde.
    try {
        if ((-not (Test-Path $script:ToastPath)) -or
            ((Get-Content $script:ToastPath -Raw -ErrorAction SilentlyContinue) -ne $script:ToastSource)) {
            Set-Content -Path $script:ToastPath -Value $script:ToastSource -Encoding UTF8
        }
        if ((-not (Test-Path $script:DemandePath)) -or
            ((Get-Content $script:DemandePath -Raw -ErrorAction SilentlyContinue) -ne $script:DemandeSource)) {
            Set-Content -Path $script:DemandePath -Value $script:DemandeSource -Encoding UTF8
        }
        if ($script:LogoBase64) {
            # Le logo suit l'agent : un ancien dessin resté sur le disque est remplacé.
            $octetsLogo = [Convert]::FromBase64String($script:LogoBase64)
            if ((-not (Test-Path $script:LogoPath)) -or ((Get-Item $script:LogoPath).Length -ne $octetsLogo.Length)) {
                [IO.File]::WriteAllBytes($script:LogoPath, $octetsLogo)
            }
        }
        # Set-Content ajoute une fin de ligne : on compare les textes normalises,
        # sinon le script passerait pour change a chaque tour et l'icone serait
        # relancee (donc la fenetre fermee) toutes les vingt secondes.
        $norme = { param($x) (([string]$x) -replace "`r`n", "`n").Trim() }
        $surDisque = ''
        if (Test-Path $script:TrayPath) {
            $surDisque = [string](Get-Content $script:TrayPath -Raw -Encoding UTF8 -ErrorAction SilentlyContinue)
        }
        if ((Invoke-Command $norme -ArgumentList $surDisque) -ne (Invoke-Command $norme -ArgumentList $script:TraySource)) {
            Set-Content -Path $script:TrayPath -Value $script:TraySource -Encoding UTF8
            $script:TrayRenouvelee = $true
        }
        return $true
    } catch {
        Write-Journal "Script de notification non écrit : $_" 'WARN'
        return $false
    }
}

function Test-PasDeJonction {
    <#
        Faille 2.2-1 (relecture) : un dossier où le groupe Utilisateurs écrit ne doit pas être une jonction
        (ou un lien) : SYSTEM y écrirait ou y effacerait ailleurs. Une jonction trouvée est retirée, le lien
        seul (sa cible n'est pas touchée). Rend $true quand le dossier est un vrai dossier, ou absent.
    #>
    param([string]$Chemin)
    if (-not (Test-Path -LiteralPath $Chemin)) { return $true }
    $it = Get-Item -LiteralPath $Chemin -Force
    if (-not ($it.Attributes -band [IO.FileAttributes]::ReparsePoint)) { return $true }
    Write-Journal "Jonction trouvée à la place de $Chemin : retirée." 'WARN'
    try { [IO.Directory]::Delete($Chemin, $false) } catch { Write-Journal "Jonction non retirée : $_" 'WARN'; return $false }
    return -not (Test-Path -LiteralPath $Chemin)
}

function Grant-DossierSonde {
    <#
        Crée le dossier de la sonde et y autorise le groupe Utilisateurs.
        Le dossier parent, lui, reste fermé : c'est là qu'est le secret.
        Faille 2.2-1 (relecture) : sur le dossier lui-même, Utilisateurs lit et crée, sans pouvoir le supprimer
        (ni donc le remplacer par une jonction) ; ce qu'il contient reste modifiable comme avant.
    #>
    try {
        if (-not (Test-PasDeJonction $script:SondeDir)) { return $false }
        if (-not (Test-Path $script:SondeDir)) {
            New-Item -ItemType Directory -Path $script:SondeDir -Force | Out-Null
        }
        if (-not (Test-PasDeJonction (Join-Path $script:SondeDir 'messages'))) { return $false }
        $acl = Get-Acl $script:SondeDir
        $utilisateurs = New-Object Security.Principal.SecurityIdentifier('S-1-5-32-545')
        $siens = @($acl.Access | Where-Object {
            -not $_.IsInherited -and $_.IdentityReference.Translate([Security.Principal.SecurityIdentifier]) -eq $utilisateurs })
        $surDossier = @($siens | Where-Object { $_.InheritanceFlags -eq 'None' -and $_.AccessControlType -eq 'Allow' -and
            -not ($_.FileSystemRights -band [Security.AccessControl.FileSystemRights]::Delete) -and
            ($_.FileSystemRights -band [Security.AccessControl.FileSystemRights]::CreateFiles) })
        $surContenu = @($siens | Where-Object { $_.PropagationFlags -eq 'InheritOnly' -and $_.AccessControlType -eq 'Allow' -and
            ($_.FileSystemRights -band [Security.AccessControl.FileSystemRights]::Modify) -eq [Security.AccessControl.FileSystemRights]::Modify })
        if ($siens.Count -ne 2 -or $surDossier.Count -ne 1 -or $surContenu.Count -ne 1) {
            $acl.PurgeAccessRules($utilisateurs)
            $acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule(
                $utilisateurs, 'ReadAndExecute, Write', 'None', 'None', 'Allow')))
            $acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule(
                $utilisateurs, 'Modify', 'ContainerInherit,ObjectInherit', 'InheritOnly', 'Allow')))
            Set-Acl -Path $script:SondeDir -AclObject $acl
            Write-Journal "Dossier de la sonde ouvert au groupe Utilisateurs (sans droit de le supprimer)."
        }
        return $true
    } catch {
        Write-Journal "Droits de la sonde non appliqués : $_" 'WARN'
        return $false
    }
}

function Get-Sondes {
    <#
        Les sondes en vie dans une session, reconnues à leur ligne de
        commande, de la plus ancienne à la plus récente. SYSTEM lit cette
        ligne pour tous les comptes : c'est la seule source qui ne ment pas
        quand le fichier de relevé, lui, est vide ou périmé.
    #>
    param([int]$Session)
    return @(
        Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" -ErrorAction SilentlyContinue |
            Where-Object {
                $_.SessionId -eq $Session -and
                $_.CommandLine -like '*HAParental-session*sonde.ps1*'
            } |
            Sort-Object CreationDate
    )
}

function Stop-Sondes {
    param([object[]]$Liste)
    foreach ($s in @($Liste)) {
        try { Stop-Process -Id $s.ProcessId -Force -ErrorAction Stop }
        catch { Write-Journal "Sonde $($s.ProcessId) non arrêtée : $_" 'WARN' }
    }
}

function Start-Sonde {
    <#
        S'assure qu'UNE sonde d'inactivité tourne dans la session en cours.

        Jusqu'à l'agent 1.43.0, le script de la sonde était réécrit à chaque
        tour : Set-Content ajoute un retour à la ligne final que la source
        n'a pas, la comparaison échouait donc toujours. Chaque tour
        « renouvelait » la sonde, arrêtait l'ancienne par le numéro lu dans
        son relevé — et en lançait une autre même quand ce numéro manquait.
        Un relevé illisible un seul instant, et les sondes s'empilaient :
        320 en une journée, 9 Go de mémoire, et un fichier que plus personne
        n'arrivait à écrire tant elles se le disputaient.

        Désormais la comparaison ignore l'enveloppe du fichier, les sondes
        se reconnaissent à leur ligne de commande, et on n'en lance une que
        s'il n'y en a aucune. La sonde tient en plus un verrou nommé : même
        lancée deux fois, elle ne tourne qu'une fois.
    #>
    $session = Get-SessionConsole
    if ($null -eq $session -or $session.Etat -ne $script:WTS_ACTIVE) { return }

    if (-not (Grant-DossierSonde)) { return }

    # Le script d'abord, la relance ensuite : une sonde d'une ancienne
    # version ne doit pas tourner jusqu'à la fermeture de session.
    $renouvelee = $false
    try {
        $surDisque = ''
        if (Test-Path $script:SondePath) {
            $surDisque = [string](Get-Content $script:SondePath -Raw -Encoding UTF8 -ErrorAction SilentlyContinue)
        }
        $norme = { param($t) ([string]$t -replace "`r`n", "`n").Trim() }
        if ((& $norme $surDisque) -ne (& $norme $script:SondeSource)) {
            Set-Content -Path $script:SondePath -Value $script:SondeSource -Encoding UTF8
            $renouvelee = $true
        }
    } catch {
        Write-Journal "Sonde non écrite : $_" 'WARN'
        return
    }

    # @( ) indispensable : une fonction deroule le tableau qu'elle rend, et
    # sous StrictMode .Count sur un objet seul ou sur $null leve. C'est ce
    # qui a tue l'agent 1.44.0 au premier tour.
    $vivantes = @(Get-Sondes -Session $session.Id)
    if ($renouvelee -and $vivantes.Count -gt 0) {
        # Elles exécutent l'ancien script : toutes dehors.
        Stop-Sondes $vivantes
        Write-Journal "Sonde renouvelée : $($vivantes.Count) ancienne(s) arrêtée(s)."
        $vivantes = @()
    }
    if ($vivantes.Count -gt 1) {
        # On garde la plus récente ; les autres n'ont rien à faire là.
        Stop-Sondes $vivantes[0..($vivantes.Count - 2)]
        Write-Journal "Sondes en double : $($vivantes.Count - 1) arrêtée(s)." 'WARN'
        $vivantes = @($vivantes[-1])
    }
    if ($vivantes.Count -eq 1) {
        # Vivante mais muette depuis deux minutes : bloquée, on la remplace.
        $age = 9999
        if (Test-Path $script:IdlePath) {
            $age = [int]((Get-Date) - (Get-Item $script:IdlePath).LastWriteTime).TotalSeconds
        }
        if ($age -lt 120) { return }
        Stop-Sondes $vivantes
        Write-Journal "Sonde muette depuis $age s : relancée." 'WARN'
    }

    $ligne = ('powershell.exe -NoProfile -NonInteractive -WindowStyle Hidden ' +
              '-ExecutionPolicy Bypass -File "{0}" -Sortie "{1}"') -f `
              $script:SondePath, $script:IdlePath
    if ([Wts]::RunAsUser($session.Id, $ligne)) {
        Write-Journal "Sonde d'inactivité lancée dans la session $($session.Id)."
    } else {
        Write-Journal "Sonde d'inactivité impossible à lancer." 'WARN'
    }
}

function Get-Releve {
    <#
        Ce que la sonde a constaté dans la session de l'utilisateur :
        inactivité, programme au premier plan, programmes ouverts.
        La sonde fait foi ; WTSINFO ne sert que de repli très approximatif.
    #>
    $vide = @{ idle = $null; premier = ''; ouverts = @(); langue = ''; formats = '' }
    if (-not (Test-Path $script:IdlePath)) { return $vide }
    try {
        $age = ((Get-Date) - (Get-Item $script:IdlePath).LastWriteTime).TotalSeconds
        if ($age -ge 60) { return $vide }
        $lu = Get-Content $script:IdlePath -Raw -Encoding UTF8 | ConvertFrom-Json
        $inactif = $null
        if ($null -ne $lu.idle -and $lu.idle -ge 0) {
            $inactif = [int]([double]$lu.idle + $age)
        }
        $premier = ''
        if ($lu.PSObject.Properties['premier']) { $premier = [string]$lu.premier }
        $ouverts = @()
        if ($lu.PSObject.Properties['ouverts']) { $ouverts = @($lu.ouverts) }
        $langue = ''; $formats = ''
        if ($lu.PSObject.Properties['langue']) { $langue = [string]$lu.langue }
        if ($lu.PSObject.Properties['formats']) { $formats = [string]$lu.formats }
        return @{ idle = $inactif; premier = $premier; ouverts = $ouverts; langue = $langue; formats = $formats }
    } catch {
        return $vide
    }
}

function Get-Inactivite {
    # Gardée pour la lisibilité des appels qui ne veulent que l'inactivité.
    return (Get-Releve).idle
}

# Infrastructure au service d'autres sites : elle ne se decide jamais et
# reviendrait indefiniment sous d'autres noms. Home Assistant tient la liste
# de reference (BRUIT_SITES) ; celle-ci evite simplement de la transmettre.
$script:BruitDns = @(
    'in-addr\.arpa', 'ip6\.arpa', '\.local$', '^wpad', '^_', 'mshome\.net$',
    'msftconnecttest', 'msftncsi', 'windowsupdate', 'update\.microsoft',
    '\.windows\.net$', '\.windowsazure\.', 'edge\.microsoft', 'msedge\.net$',
    'telemetry', 'crashpad', 'ocsp\.', 'crl\.', 'pki\.', 'msocsp\.com$',
    'lencr\.org$', 'letsencrypt\.org$', 'digicert\.com$', 'sectigo\.com$',
    'globalsign\.com$', 'usertrust\.com$', 'amazontrust\.com$',
    'akamaiedge', 'akamaized', 'akamai\.net$', 'edgekey\.net$',
    'edgesuite\.net$', 'cloudfront\.net$', 'fastly\.net$', 'fastlylb\.net$',
    'azureedge\.net$', 'azurefd\.net$', 'trafficmanager\.net$',
    'amazonaws\.com$', 'cdn77\.org$', 'llnwd\.net$', 'cachefly\.net$',
    'jsdelivr\.net$', 'unpkg\.com$', 'gvt1\.com$', 'gvt2\.com$',
    'gstatic\.com$', 'googleapis\.com$', 'googleusercontent\.com$',
    'google-analytics\.com$', 'googletagmanager\.com$', 'doubleclick\.net$',
    'scorecardresearch\.com$', 'app-measurement\.com$', 'crashlytics\.com$',
    'exp-tas\.com$', 'omtrdc\.net$', 'demdex\.net$',
    'hcaptcha\.com$', 'recaptcha\.net$', 'arkoselabs\.com$',
    'perimeterx\.net$', 'datadome\.co$',
    'ntp\.', 'time\.windows', 'root-servers\.net$'
) -join '|'

function Get-SitesConsultes {
    <#
        Les domaines que Windows a résolus récemment. C'est la trace la plus
        simple et la moins intrusive de ce qui a été consulté : on obtient
        des **domaines**, jamais des pages.

        Cela suppose que le DNS chiffré des navigateurs reste désactivé —
        c'est déjà ce que fait Disable-DnsChiffre pour que le fichier hosts
        serve à quelque chose. Sans cela, Chrome résout lui-même et Windows
        ne voit rien passer.
    #>
    try {
        # Une entree resolue en 0.0.0.0, c'est **nous** : Windows charge le
        # fichier hosts dans son cache DNS, et nos milliers de blocages en
        # ressortaient comme autant de sites « consultes ». La liste de la
        # maison s'est retrouvee noyee sous des domaines que personne n'a
        # jamais ouverts. On ne garde donc que ce qui a vraiment resolu.
        $noms = Get-DnsClientCache -ErrorAction Stop |
            Where-Object {
                $_.Type -in 1, 5 -and $_.Entry -and
                ([string]$_.Data) -ne '0.0.0.0' -and ([string]$_.Data) -ne '::'
            } |
            ForEach-Object { $_.Entry.ToLowerInvariant().TrimEnd('.') } |
            Where-Object { $_ -like '*.*' -and $_ -notmatch $script:BruitDns } |
            Sort-Object -Unique
        # Pas de troncature ici. Elle etait faite apres un tri alphabetique,
        # donc les memes soixante noms passaient a chaque tour et tout ce qui
        # venait apres « g » etait invisible pour toujours -- y compris pour
        # Sync-Hosts, qui s'en sert pour etendre un blocage aux
        # sous-domaines. Le choix de ce qu'on remonte se fait ailleurs, et
        # il tourne.
        return @($noms | Select-Object -First 400)
    } catch {
        return @()
    }
}

function Select-ARemonter {
    <#
        Ce qu'on envoie a Home Assistant ce tour-ci.

        Le cache peut contenir plus de noms qu'il n'est raisonnable d'envoyer
        toutes les vingt secondes. On choisit donc -- mais on choisit en
        tournant, jamais par ordre alphabetique : ce qu'on n'a jamais remonte
        passe en premier, puis ce qu'on a remonte il y a le plus longtemps.

        Un domaine nouveau part donc au tour suivant, quelle que soit sa
        premiere lettre, et aucun ne reste invisible.
    #>
    param([string[]]$Noms, [int]$Plafond = 120)

    $maintenant = (Get-Date).Ticks
    $classes = foreach ($nom in $Noms) {
        [pscustomobject]@{
            Nom = $nom
            Vu  = if ($script:VusEnvoyes.ContainsKey($nom)) {
                      $script:VusEnvoyes[$nom]
                  } else { 0 }
        }
    }
    $choisis = @($classes | Sort-Object Vu | Select-Object -First $Plafond)
    foreach ($c in $choisis) { $script:VusEnvoyes[$c.Nom] = $maintenant }

    # La memoire ne doit pas enfler indefiniment : on oublie ce qui n'est
    # plus dans le cache, il repartira comme neuf s'il revient.
    $vivants = @{}
    foreach ($nom in $Noms) {
        if ($script:VusEnvoyes.ContainsKey($nom)) {
            $vivants[$nom] = $script:VusEnvoyes[$nom]
        }
    }
    $script:VusEnvoyes = $vivants

    return @($choisis | ForEach-Object { $_.Nom })
}

function Get-Signatures {
    <#
        Qui a signe ce programme, et d'ou il tourne.

        Il n'existe aucune liste telechargeable qui dise si un logiciel est
        de confiance. Ce qui existe, et que Windows porte deja, c'est la
        signature Authenticode : un editeur a engage son certificat sur ce
        fichier, ou personne ne l'a fait. C'est le premier filtre qu'utilise
        n'importe quel antivirus, et il ne coute rien.

        L'emplacement compte autant. Un programme installe vit dans Program
        Files ; un programme telecharge et lance sans installation vit dans
        Telechargements, %TEMP% ou AppData. Le second n'est pas coupable,
        mais c'est exactement la forme de ce qu'on lance sans y penser.

        On ne verifie qu'une fois par programme : lire un certificat coute
        cher, et une signature ne change pas entre deux tours.
    #>
    param([string[]]$Noms)

    $sortie = @{}
    foreach ($nom in ($Noms | Sort-Object -Unique)) {
        if (-not $nom) { continue }
        if ($script:Signatures.ContainsKey($nom)) {
            $sortie[$nom] = $script:Signatures[$nom]
            continue
        }
        $fiche = @{ signataire = ''; statut = 'inconnu'; ou = ''; empreinte = '' }
        try {
            $processus = Get-Process -Name $nom -ErrorAction SilentlyContinue |
                Select-Object -First 1
            $chemin = if ($processus) { $processus.Path } else { '' }
            if ($chemin) {
                $fiche.ou = Get-Emplacement -Chemin $chemin
                $sig = Get-AuthenticodeSignature -FilePath $chemin -ErrorAction Stop
                $fiche.statut = [string]$sig.Status
                if ($sig.SignerCertificate) {
                    # « CN=Valve Corp., O=Valve Corp., L=Bellevue... » : on
                    # ne garde que le nom commun, le reste est du bruit.
                    $sujet = [string]$sig.SignerCertificate.Subject
                    $cn = ($sujet -split ',' | Where-Object { $_.Trim() -like 'CN=*' } |
                        Select-Object -First 1)
                    if ($cn) { $fiche.signataire = ($cn -replace '^\s*CN=', '').Trim('"', ' ') }
                }
                # L'empreinte, pour qui veut demander a VirusTotal. Le
                # fichier lui-meme ne sort jamais de la machine ; et
                # au-dela d'une certaine taille on ne le lit meme pas,
                # lire deux giga-octets bloquerait le disque pour rien.
                $info = Get-Item -LiteralPath $chemin -ErrorAction SilentlyContinue
                if ($info -and $info.Length -lt ($script:EmpreinteMaxMo * 1MB)) {
                    $h = Get-FileHash -LiteralPath $chemin -Algorithm SHA256 `
                        -ErrorAction SilentlyContinue
                    if ($h) { $fiche.empreinte = $h.Hash.ToLowerInvariant() }
                }
            }
        } catch {
            $fiche.statut = 'illisible'
        }
        $script:Signatures[$nom] = $fiche
        $sortie[$nom] = $fiche
    }
    return $sortie
}

function Get-Emplacement {
    <#
        Ou vit ce fichier, dit en un mot. Program Files, Windows, ou l'un
        des endroits d'ou l'on lance sans installer.
    #>
    param([string]$Chemin)
    $bas = $Chemin.ToLowerInvariant()
    foreach ($cas in @(
        @{ Motif = '\\windows\\';        Mot = 'Windows' },
        @{ Motif = '\\program files';    Mot = 'Program Files' },
        @{ Motif = '\\programdata\\';    Mot = 'ProgramData' },
        @{ Motif = '\\downloads\\';      Mot = 'Telechargements' },
        @{ Motif = '\\telechargements\\'; Mot = 'Telechargements' },
        @{ Motif = '\\temp\\';           Mot = 'TEMP' },
        @{ Motif = '\\appdata\\';        Mot = 'AppData' },
        @{ Motif = '\\users\\public\\';  Mot = 'Public' }
    )) {
        if ($bas -match $cas.Motif) { return $cas.Mot }
    }
    return 'ailleurs'
}

function Get-Origines {
    <#
        Quel programme parlait a ce domaine.

        Le cache DNS ne porte aucune trace du demandeur : il dit qu'un nom a
        ete resolu, pas par qui. On croise donc deux vues de la meme
        seconde -- le cache donne nom -> adresse, les connexions TCP
        donnent adresse -> processus.

        Ce que cela ne voit pas, et il faut le savoir : une connexion plus
        courte que l'intervalle entre deux tours, et tout ce qui passe en
        QUIC, ou Windows ne publie pas l'adresse distante. On ne pretend
        donc pas dire l'origine de tout -- on dit celle qu'on a vue.

        Neuf fois sur dix la reponse sera le navigateur, et c'est sans
        interet. La dixieme -- un programme qu'on ne connait pas qui parle
        a un domaine qu'on ne connait pas -- est exactement ce qu'on
        cherchait.
    #>
    $origines = @{}
    try {
        $parIp = @{}
        foreach ($entree in (Get-DnsClientCache -ErrorAction Stop)) {
            if ($entree.Type -ne 1 -and $entree.Type -ne 28) { continue }
            $adresse = [string]$entree.Data
            if (-not $adresse -or $adresse -eq '0.0.0.0' -or $adresse -eq '::') {
                continue
            }
            $nom = ([string]$entree.Entry).ToLowerInvariant().TrimEnd('.')
            if (-not $nom -or $nom -notlike '*.*') { continue }
            if ($nom -match $script:BruitDns) { continue }
            $parIp[$adresse] = $nom
        }
        if ($parIp.Count -eq 0) { return $origines }

        $vus = @{}
        foreach ($lien in (Get-NetTCPConnection -State Established -ErrorAction Stop)) {
            $distante = [string]$lien.RemoteAddress
            if (-not $parIp.ContainsKey($distante)) { continue }
            $identifiant = [int]$lien.OwningProcess
            if (-not $vus.ContainsKey($identifiant)) {
                $processus = Get-Process -Id $identifiant -ErrorAction SilentlyContinue
                $vus[$identifiant] = if ($processus) {
                    $processus.ProcessName.ToLowerInvariant()
                } else { '' }
            }
            $programme = $vus[$identifiant]
            if (-not $programme) { continue }
            $domaine = $parIp[$distante]
            if (-not $origines.ContainsKey($domaine)) { $origines[$domaine] = @() }
            if ($origines[$domaine] -notcontains $programme) {
                $origines[$domaine] += $programme
            }
        }
    } catch {
        Write-Journal "Origines indisponibles : $_" 'WARN'
    }
    return $origines
}

function Test-SessionAdministrateur {
    <#
        Un enfant administrateur peut arrêter la tâche planifiée et se
        libérer : tout le reste ne tient que si le compte est standard.
        On le signale à Home Assistant plutôt que de faire semblant.

        Le nom du groupe est localisé (« Administrateurs », « Administrators »)
        d'où le passage par le SID.
    #>
    $session = Get-SessionConsole
    if ($null -eq $session) { return $null }
    $nom = [string](Get-PropOrDefault $session 'User' '')
    if (-not $nom) { return $null }
    try {
        $groupe = Get-LocalGroup -SID 'S-1-5-32-544' -ErrorAction Stop
        foreach ($membre in Get-LocalGroupMember -Group $groupe.Name -ErrorAction Stop) {
            if ((($membre.Name -split '\\')[-1]) -ieq $nom) { return $true }
        }
        return $false
    } catch {
        return $null
    }
}

function Show-Toast {
    <#
        Affiche une notification Windows dans la session de l'utilisateur.
        Renvoie $false si Windows n'a pas voulu : l'appelant se rabat alors
        sur la boîte de dialogue.
    #>
    param(
        [string]$Texte,
        [string]$Titre,
        [bool]$Long = $false,
        [string]$Son = 'Notification.Default',
        [bool]$Repete = $false,
        $Demande = $null
    )

    $session = Get-SessionConsole
    if ($null -eq $session -or $session.Etat -ne $script:WTS_ACTIVE) { return $false }
    if (-not (Grant-DossierSonde)) { return $false }
    if (-not (Write-ToastScript)) { return $false }

    $charge = @{
        aumid   = $script:Aumid
        appname = 'Vision'
        lieu    = $script:Lieu
        title   = $Titre
        text    = $Texte
        long    = $Long
        sound   = $Son
        loop    = $Repete
        logo    = $script:LogoPath
        bouton_demander = (T 'toast.demander_acces')
        bouton_fermer   = (T 'toast.fermer')
    }
    if ($Demande) { $charge.demande = $Demande }
    $charge = $charge | ConvertTo-Json -Compress -Depth 4
    $encode = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($charge))

    # powershell.exe et non pwsh : seul Windows PowerShell 5.1 sait parler
    # à WinRT, où vivent les notifications.
    $ligne = ('powershell.exe -NoProfile -NonInteractive -WindowStyle Hidden ' +
              '-ExecutionPolicy Bypass -File "{0}" -Payload {1}') -f $script:ToastPath, $encode

    try {
        return [Wts]::RunAsUser($session.Id, $ligne)
    } catch {
        Write-Journal "Notification impossible : $_" 'WARN'
        return $false
    }
}

function Send-Message {
    <#
        Affiche un message par-dessus ce que fait l'utilisateur. Il se ferme
        de lui-même : personne ne reste bloqué devant une boîte de dialogue.
    #>
    param(
        [string]$Texte,
        [string]$Titre = 'Vision',
        [int]$Secondes = 30,
        [bool]$Urgent = $false,
        [string]$Son = 'Notification.Default',
        [bool]$Repete = $false,
        $Demande = $null
    )

    $session = Get-SessionConsole
    if ($null -eq $session -or $session.Etat -ne $script:WTS_ACTIVE) {
        Write-Journal "Message non affiché (aucune session active) : $Texte" 'WARN'
        return $false
    }

    # Par défaut une notification Windows, discrète et conservée dans le
    # centre de notifications. Un message urgent passe par-dessus tout.
    if (-not $Urgent) {
        if (Show-Toast -Texte $Texte -Titre $Titre -Son $Son -Repete $Repete -Demande $Demande) {
            Write-Journal "Notification affichée : $Texte"
            return $true
        }
        Write-Journal "Notification refusée par Windows, repli sur la boîte." 'WARN'
    }

    [Wts]::Message($session.Id, $Titre, $Texte, $Secondes)
    Write-Journal "Message affiché : $Texte"
    return $true
}

function Send-Avertissement {
    param([string]$Texte)
    [void](Send-Message -Texte $Texte -Titre 'Vision')
}

function Stop-LogicielsInterdits {
    param([string[]]$Noms)
    if (-not $Noms -or $Noms.Count -eq 0) { return }
    $cibles = $Noms | ForEach-Object {
        ($_ -replace '\.exe$', '').Trim().ToLowerInvariant()
    } | Where-Object { $_ }
    if (-not $cibles) { return }

    foreach ($processus in (Get-Process -ErrorAction SilentlyContinue)) {
        if ($cibles -contains $processus.ProcessName.ToLowerInvariant()) {
            try {
                Stop-Process -Id $processus.Id -Force -ErrorAction Stop
                Write-Journal "Logiciel interdit fermé : $($processus.ProcessName)."
            } catch {
                Write-Journal "Impossible de fermer $($processus.ProcessName) : $_" 'WARN'
            }
        }
    }
}

function Test-Interdit {
    # Ce nom est-il dans la liste du moment ?
    param([string]$Nom)
    $nom = ($Nom -replace '\.exe$', '').Trim().ToLowerInvariant()
    if (-not $nom) { return $false }
    foreach ($interdit in $script:AppsInterdites) {
        if (($interdit -replace '\.exe$', '').Trim().ToLowerInvariant() -eq $nom) {
            return $true
        }
    }
    return $false
}

function Deny-Lancement {
    <#
        Un logiciel interdit vient de démarrer : on le referme tout de suite
        et on le dit.

        Le dire est la moitié du travail. Une fenêtre qui disparaît sans un
        mot passe pour une panne — l'enfant relance, croit à un bug, appelle
        au secours. Une phrase, et il sait que ce n'est pas cassé : c'est
        fermé. Un blocage compris se discute ; un blocage muet s'use.

        Un même logiciel ouvre souvent quatre processus d'un coup. On ne
        prévient qu'une fois par logiciel et par période : la règle se dit,
        elle ne se martèle pas.
    #>
    param([string]$Nom, [int]$Identifiant)

    if (-not (Test-Interdit -Nom $Nom)) { return }

    $court = ($Nom -replace '\.exe$', '').Trim()
    if ($Identifiant -gt 0) {
        try {
            Stop-Process -Id $Identifiant -Force -ErrorAction Stop
            Write-Journal "Lancement refusé : $court (PID $Identifiant)."
        } catch {
            Write-Journal "Lancement de $court non refusé : $_" 'WARN'
        }
    }
    # Ses compagnons partis en même temps, sous un autre nom de la liste.
    Stop-LogicielsInterdits -Noms $script:AppsInterdites

    $cle = $court.ToLowerInvariant()
    $depuis = $script:AlerteAppRepos + 1
    if ($script:AlerteApp.ContainsKey($cle)) {
        $depuis = ((Get-Date) - $script:AlerteApp[$cle]).TotalSeconds
    }
    if ($depuis -gt $script:AlerteAppRepos) {
        $script:AlerteApp[$cle] = Get-Date
        $joli = $court.Substring(0, 1).ToUpperInvariant() + $court.Substring(1)
        $pourquoi = Get-Raison -Nom $court
        Add-Recent -Genre 'apps' -Nom $joli -Raison $pourquoi
        $phrase = (T 'agent.pas_accessible' @{ nom = $joli })
        if ($pourquoi) { $phrase = (T 'commun.nom_valeur' @{ nom = $joli; valeur = $pourquoi }) }
        # Module Demandes coupé : ni bouton « Demander l'accès » ni invitation à le faire.
        $demande = $null; $suite = ''
        if (Module-Actif 'demandes') {
            $demande = @{ genre = 'apps'; nom = $court; libelle = $joli }
            $suite = (T 'agent.tu_peux_demander')
        }
        [void](Send-Message -Texte "$phrase$suite" `
            -Titre 'Vision' -Secondes 8 -Son '' -Demande $demande)
    }
}

function Start-VeilleApps {
    <#
        Windows annonce chaque démarrage de processus. On écoute cette
        annonce au lieu de faire le tour des processus : le logiciel
        interdit est refermé dans la seconde de son lancement, pas au
        prochain balayage.

        Deux abonnements possibles, essayés dans cet ordre. Le premier,
        Win32_ProcessStartTrace, est immédiat mais exige les droits
        d'administrateur — l'agent les a, un test lancé à la main ne les a
        pas, et c'est la seule raison pour laquelle le second existe :
        __InstanceCreationEvent est ouvert à tous et arrive avec au plus une
        seconde de retard. Mieux vaut une seconde qu'un balayage.

        Si les deux échouent, on le dit et on retombe sur le balayage, qui
        se resserre alors pour compenser.
    #>
    if ($script:VeilleActive) { return }
    $essais = @(
        @{ Forme = 'trace'
           Query = 'SELECT * FROM Win32_ProcessStartTrace' },
        @{ Forme = 'instance'
           Query = ("SELECT * FROM __InstanceCreationEvent WITHIN 1 " +
                    "WHERE TargetInstance ISA 'Win32_Process'") }
    )
    foreach ($essai in $essais) {
        try {
            Register-CimIndicationEvent -Query $essai.Query `
                -SourceIdentifier $script:VeilleApps -ErrorAction Stop | Out-Null
            $script:VeilleActive = $true
            $script:VeilleForme = $essai.Forme
            Write-Journal "Veille des lancements active ($($essai.Forme))."
            return
        } catch {
            Write-Journal "Veille des lancements ($($essai.Forme)) refusée : $_" 'WARN'
        }
    }
    $script:VeilleActive = $false
    Write-Journal "Veille des lancements indisponible : balayage seul." 'WARN'
}

function Read-Lancement {
    # Les deux abonnements ne rangent pas le nom au même endroit.
    param($Annonce)
    $depart = $Annonce.SourceEventArgs.NewEvent
    if ($script:VeilleForme -eq 'instance') {
        $cible = $depart.TargetInstance
        return @{ Nom = [string]$cible.Name; Id = [int]$cible.ProcessId }
    }
    return @{ Nom = [string]$depart.ProcessName; Id = [int]$depart.ProcessID }
}

function Start-Tray {
    <#
        Lance l'icône de zone de notification dans la session de l'utilisateur.
        Le verrou du script garantit une seule instance ; quand le script a
        changé, on arrête l'ancienne pour que la nouvelle prenne la place.
    #>
    $session = Get-SessionConsole
    if ($null -eq $session -or $session.Etat -ne $script:WTS_ACTIVE) { return }
    if (-not (Grant-DossierSonde)) { return }
    if (-not (Write-ToastScript)) { return }
    # Un confort : quoi qu'il arrive ici, la fenêtre de l'utilisateur doit démarrer.
    if ($script:TrayRenouvelee) {
        $script:TrayRenouvelee = $false
        Get-CimInstance Win32_Process -Filter "Name = 'powershell.exe'" -ErrorAction SilentlyContinue |
            Where-Object { $_.CommandLine -and $_.CommandLine -like '*tray.ps1*' } |
            ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    }
    $ligne = ('powershell.exe -NoProfile -NonInteractive -WindowStyle Hidden ' +
              '-ExecutionPolicy Bypass -File "{0}" -Dossier "{1}"') -f $script:TrayPath, $script:SondeDir
    try { [void][Wts]::RunAsUser($session.Id, $ligne) } catch { }
}

function Write-EtatSonde {
    # Ce que l'icône affiche dans son menu : ouvert ou fermé, et jusqu'à quand.
    param($Decision)
    try {
        $ferme = ($null -ne $Decision -and $Decision.action -eq 'lock')
        $texte = (T 'etat.acces_ouvert')
        if ($ferme) {
            $texte = [string](Get-PropOrDefault $Decision 'message' (T 'etat.acces_ferme'))
        } elseif ($null -ne $Decision -and (Get-PropOrDefault $Decision 'warn' 0) -gt 0) {
            $texte = [string](Get-PropOrDefault $Decision 'message' (T 'etat.acces_ouvert'))
        }
        # « modules » : la fenêtre masque ce qui est coupé ($null = tout actif, comme le serveur ancien).
        # « soutien », « decideur » : le merci ou le lien de soutien dans le menu de l'icône, le clin d'œil de la veille.
        @{ ferme = $ferme; texte = $texte; lieu = $script:Lieu; modules = $script:Modules; soutien = $script:Soutien; decideur = $script:EstParent } | ConvertTo-Json -Compress |
            Set-Content -Path $script:EtatSondePath -Encoding UTF8
    } catch { }
}

function Read-Raisons {
    # Home Assistant dit pourquoi chaque nom est fermé : `motifs` (textes) et
    # `raisons` (nom → index). On garde une table nom → texte.
    param($Decision)
    $table = @{}; $codes = @{}
    try {
        if ($null -ne $Decision -and $Decision.PSObject.Properties['raisons'] -and $Decision.raisons) {
            $motifs = @(Get-PropOrDefault $Decision 'motifs' @())
            foreach ($p in $Decision.raisons.PSObject.Properties) {
                $i = [int]$p.Value
                if ($i -ge 0 -and $i -lt $motifs.Count) {
                    $texte = [string]$motifs[$i]
                    $table[$p.Name.ToLowerInvariant()] = $texte.Substring($texte.IndexOf('|') + 1)
                    if ($texte.IndexOf('|') -gt 0) { $codes[$p.Name.ToLowerInvariant()] = $texte.Substring(0, $texte.IndexOf('|')) }
                }
            }
        }
    } catch { }
    $script:Raisons = $table
    $script:RaisonsCodes = $codes
}

function Get-Raison {
    # Le motif pour un programme ou un domaine (en remontant les sous-domaines).
    param([string]$Nom)
    $n = ([string]$Nom).ToLowerInvariant().Trim()
    $n = $n -replace '\.exe$', ''
    while ($true) {
        if ($script:Raisons.ContainsKey($n)) { return [string]$script:Raisons[$n] }
        if ($n -notlike '*.*') { return '' }
        $n = $n.Substring($n.IndexOf('.') + 1)
    }
}

function Test-RaisonSecurite {
    # Un blocage de sécurité (site dangereux, traçage) : par son code, quelle que soit la langue du texte.
    param([string]$Nom, [string]$Texte)
    $n = ([string]$Nom).ToLowerInvariant().Trim() -replace '\.exe$', ''
    while ($n) {
        if ($script:RaisonsCodes.ContainsKey($n)) { return ([string]$script:RaisonsCodes[$n] -eq 'securite') }
        if ($n -notlike '*.*') { break }
        $n = $n.Substring($n.IndexOf('.') + 1)
    }
    # Home Assistant plus ancien, sans code : son texte français.
    return ($Texte -like 'Bloqué pour ta sécurité*')
}

function Add-Recent {
    <#
        Un refus vient d'avoir lieu (logiciel refermé, site vu bloqué) : on le
        note pour la fenêtre, où l'enfant demande l'accès d'un geste. Un même
        nom n'est noté qu'une fois par dix minutes ; on garde 24 heures, 30 au plus.
    #>
    param([string]$Genre, [string]$Nom, [string]$Raison = '')
    if (-not $Nom) { return }
    $maintenant = Get-Date
    $cle = ($Genre + ':' + $Nom).ToLowerInvariant()
    $garde = @()
    foreach ($r in @($script:Recents)) {
        if ($null -eq $r) { continue }
        if (($maintenant - [datetime]$r.ts).TotalHours -gt 24) { continue }
        if ($r.cle -eq $cle -and ($maintenant - [datetime]$r.ts).TotalSeconds -lt 600) { return }
        if ($r.cle -eq $cle) { continue }
        $garde += $r
    }
    $garde += @{ cle = $cle; genre = $Genre; nom = $Nom; raison = $Raison; ts = $maintenant }
    if ($garde.Count -gt 30) { $garde = @($garde | Select-Object -Last 30) }
    $script:Recents = $garde
}

function Get-Recents {
    $sortie = @()
    foreach ($r in @($script:Recents | Sort-Object { $_.ts } -Descending)) {
        if ($null -eq $r) { continue }
        # Un refus d'un module coupé depuis ne se montre plus (il revient s'il est récent à la réactivation).
        if ($r.genre -eq 'apps' -and -not (Module-Actif 'blocage')) { continue }
        if ($r.genre -eq 'sites' -and -not (Module-Actif 'filtrage')) { continue }
        $sortie += @{ genre = $r.genre; nom = $r.nom; raison = $r.raison; quand = (Format-Heure ([datetime]$r.ts)) }
    }
    return $sortie
}

function Write-Fermes {
    <#
        Ce qui est fermé sur ce PC, avec le motif, pour la fenêtre de l'icône.
        Les blocages de sécurité (sites dangereux, traçage) n'y figurent pas :
        on ne les montre pas, on ne les discute pas.
    #>
    param($Decision)
    try {
        $apps = @(); $sites = @()
        $vus = @{}
        foreach ($n in @($script:DernierVu)) { if ($n) { $vus[[string]$n] = $true } }
        # Modules coupés : rien de fermé à leur titre (le serveur n'envoie déjà plus rien ; ceinture).
        $listeApps = @(); if (Module-Actif 'blocage') { $listeApps = @(Get-PropOrDefault $Decision 'apps' @()) }
        $listeSites = @(); if (Module-Actif 'filtrage') { $listeSites = @(Get-PropOrDefault $Decision 'sites' @()) }
        foreach ($a in $listeApps) {
            $a = [string]$a
            if (-not $a -or $a -like '*.*') { continue }   # paquets Android : pas pour ce PC
            $r = Get-Raison -Nom $a
            if (Test-RaisonSecurite $a $r) { continue }
            $recA = $false
            try { $k = $a.ToLowerInvariant(); if ($script:AlerteApp.ContainsKey($k)) { $recA = (((Get-Date) - $script:AlerteApp[$k]).TotalMinutes -lt 10) } } catch { }
            $apps += @{ nom = $a; raison = $r; recent = [bool]$recA }
        }
        foreach ($s in $listeSites) {
            $s = [string]$s
            if (-not $s) { continue }
            # Un domaine par site : les sous-domaines et CDN n'apportent rien.
            if (($s.Split('.')).Count -gt 2) { continue }
            $r = Get-Raison -Nom $s
            if (-not $r -or (Test-RaisonSecurite $s $r)) { continue }
            if ($vus.ContainsKey($s)) { Add-Recent -Genre 'sites' -Nom $s -Raison $r }
            $sites += @{ nom = $s; raison = $r; recent = [bool]$vus.ContainsKey($s) }
        }
        # HA 2.5.1 : les blocages récents de la personne (tous ses appareils) et les mêmes regroupés par logiciel.
        @{ apps = $apps; sites = $sites; recents = @(Get-Recents); refus = @(Get-PropOrDefault $Decision 'refus' @()); quand = (Format-Heure (Get-Date) -Locale)
           ha_recents = @(Get-PropOrDefault $Decision 'bloques_recents' @()); groupes = @(Get-PropOrDefault $Decision 'groupes' @()) } |
            ConvertTo-Json -Compress -Depth 4 | Set-Content -Path $script:FermesPath -Encoding UTF8
    } catch { }
}

# 1.93.06 (sécurité) : maison.json contient l'état de toute la maison, dont la liste des logiciels de chaque PC. Le
# dossier de la sonde est ouvert au groupe Utilisateurs (la fenêtre de Vision y dépose ses demandes) : un fichier qui y
# hérite ses droits était lisible par tout compte Windows du PC, un enfant compris. Il n'est plus lisible que par
# SYSTEM, les administrateurs et le compte de la session SI celui-ci administre le PC (un administrateur lit déjà tout
# sur ce PC, la configuration de l'agent comprise : rien de nouveau ne lui est ouvert). Un compte ordinaire n'a pas de
# maison.json : la fenêtre reste en mode ordinaire.
$script:AdminsSids    = @()
$script:AdminsSidsVu  = [datetime]::MinValue
$script:LecteurMaison = ''

function Get-SidsAdministrateurs {
    <# Les SID des membres du groupe Administrateurs (S-1-5-32-544), relus au plus toutes les 10 min. Illisible : aucun. #>
    if (((Get-Date) - $script:AdminsSidsVu).TotalMinutes -lt 10) { return $script:AdminsSids }
    $sids = @()
    try { $sids = @(Get-LocalGroupMember -SID 'S-1-5-32-544' -ErrorAction Stop | ForEach-Object { [string]$_.SID }) } catch { $sids = @() }
    $script:AdminsSids = $sids; $script:AdminsSidsVu = Get-Date
    return $sids
}

function Get-LecteurMaison {
    <# Le SID du compte de la session ouverte s'il administre ce PC ; sinon '' (pas de maison.json). #>
    param($Session = (Get-SessionConsole))
    if ($null -eq $Session -or -not [string]$Session.User) { return '' }
    try { $sid = (New-Object Security.Principal.NTAccount([string]$Session.User)).Translate([Security.Principal.SecurityIdentifier]).Value }
    catch { return '' }
    if (@(Get-SidsAdministrateurs) -contains $sid) { return $sid }
    return ''
}

function Write-FichierLecteur {
    <#
        Écrit $Texte (UTF-8) dans $Chemin, lisible seulement par SYSTEM, les administrateurs et le SID $Lecteur ; ni
        héritage du dossier, ni droits d'un fichier posé avant par un utilisateur du PC : l'ancien est retiré, le nouveau
        créé avec ses droits (CreateNew : si quelqu'un en recrée un entre-temps, rien n'est écrit).
    #>
    param([Parameter(Mandatory)][string]$Chemin, [Parameter(Mandatory)][string]$Texte, [Parameter(Mandatory)][string]$Lecteur)
    if ([System.IO.File]::Exists($Chemin)) { [System.IO.File]::Delete($Chemin) }
    $sec = New-Object System.Security.AccessControl.FileSecurity
    $sec.SetAccessRuleProtection($true, $false)
    foreach ($sid in @('S-1-5-18', 'S-1-5-32-544')) {
        $sec.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule((New-Object System.Security.Principal.SecurityIdentifier($sid)), 'FullControl', 'Allow')))
    }
    $sec.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule((New-Object System.Security.Principal.SecurityIdentifier($Lecteur)), 'Read', 'Allow')))
    $fs = New-Object System.IO.FileStream($Chemin, [System.IO.FileMode]::CreateNew, [System.Security.AccessControl.FileSystemRights]::Write, [System.IO.FileShare]::None, 4096, [System.IO.FileOptions]::None, $sec)
    try {
        $o = [System.Text.Encoding]::UTF8.GetPreamble() + [System.Text.Encoding]::UTF8.GetBytes($Texte)
        $fs.Write($o, 0, $o.Length)
    } finally { $fs.Dispose() }
}

function Sync-Maison {
    <#
        Sur le PC d'un parent (étiquette « Parents » dans Vision), l'agent va
        chercher l'état des appareils de la maison et le dépose pour la
        fenêtre. Le secret ne quitte jamais le dossier de configuration : la
        fenêtre, qui tourne chez l'utilisateur, ne lit que le résultat.
        1.93.06 : seulement pour un compte qui administre ce PC, dans un fichier qu'il est seul (avec SYSTEM et les
        administrateurs) à pouvoir lire ; sans module qui s'en sert, sans la liste des logiciels.
    #>
    param($Config, [bool]$Force = $false)
    $lecteur = if ($script:EstParent) { Get-LecteurMaison } else { '' }
    if (-not $lecteur) {
        try { if ([System.IO.File]::Exists($script:MaisonPath)) { [System.IO.File]::Delete($script:MaisonPath) } }
        catch { Write-Journal "maison.json non retiré : $_" 'WARN' }
        $script:LecteurMaison = ''
        return
    }
    # Un autre compte a ouvert sa session : le fichier est refait pour lui tout de suite (ou retiré, plus haut).
    if ($lecteur -ne $script:LecteurMaison) { $Force = $true }
    if (-not $Force -and ((Get-Date) - $script:DerniereMaison).TotalSeconds -lt 60) { return }
    try {
        $r = Invoke-Ha -Chemin '/api/pc_parental/parent' -Corps @{ id = $Config.id; secret = $Config.secret } -Config $Config
        if ($null -ne $r -and (Get-PropOrDefault $r 'ok' $false)) {
            if (-not (Module-Actif 'blocage') -and -not (Module-Actif 'optimisations')) {
                foreach ($a in @(Get-PropOrDefault $r 'appareils' @())) {
                    if ($null -ne $a -and $a.PSObject.Properties['logiciels']) { $a.PSObject.Properties.Remove('logiciels') }
                }
            }
            Write-FichierLecteur -Chemin $script:MaisonPath -Texte ($r | ConvertTo-Json -Depth 12 -Compress) -Lecteur $lecteur
            $script:LecteurMaison = $lecteur
            $script:DerniereMaison = Get-Date
        }
    } catch {
        Write-Journal "Espace gestion non rafraîchi : $_" 'WARN'
    }
}

# ------------------------------------------------------------------ Messages riches (1.67.0, docs/messages.md)
# Home Assistant envoie texte, image, son ou vidéo. L'agent télécharge le média dans le dossier de la
# sonde et dépose messages.json ; la fenêtre de Vision, dans la session de l'utilisateur, les montre et
# dépose ses accusés (accuse-*.json), que l'agent envoie à Home Assistant.
$script:MessagesPath  = Join-Path $script:SondeDir 'messages.json'
$script:MediasDir     = Join-Path $script:SondeDir 'messages'

function Read-MessagesRiches {
    if (-not (Test-Path $script:MessagesPath)) { return @() }
    try { return @(Get-Content $script:MessagesPath -Raw -Encoding UTF8 | ConvertFrom-Json) } catch { return @() }
}

function Write-MessagesRiches {
    param($Liste)
    if (-not (Grant-DossierSonde)) { return }
    (ConvertTo-Json -InputObject @($Liste) -Depth 4 -Compress) | Set-Content -Path $script:MessagesPath -Encoding UTF8
}

function Save-MessagesRiches {
    <# Les messages riches du relevé : média téléchargé, puis file déposée pour la fenêtre. #>
    param($Messages, $Config)
    $liste = @(Read-MessagesRiches)
    $maintenant = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    # Les vieux messages jamais vus (aucune session ouverte) s'en vont un jour après leur expiration.
    $liste = @($liste | Where-Object { $_ -and ([string]$_.bloquant -ne 'non' -or [double]$_.expire + 86400 -gt $maintenant) })
    foreach ($m in @($Messages)) {
        $uid = [string](Get-PropOrDefault $m 'uid' '')
        if (-not $uid -or @($liste | Where-Object { [string]$_.uid -eq $uid }).Count) { continue }
        $copie = [ordered]@{}
        foreach ($p in $m.PSObject.Properties) { if ($p.Name -ne 'media') { $copie[$p.Name] = $p.Value } }
        $media = Get-PropOrDefault $m 'media' $null
        if ($media) {
            $ext = switch ([string]$media.mime) {
                'image/jpeg' { '.jpg' } 'image/png' { '.png' } 'image/webp' { '.webp' } 'image/gif' { '.gif' }
                'audio/mpeg' { '.mp3' } 'audio/mp4' { '.m4a' } 'audio/aac' { '.aac' } 'audio/ogg' { '.ogg' } 'audio/wav' { '.wav' }
                'video/mp4' { '.mp4' } 'video/webm' { '.webm' } default { '.bin' }
            }
            $copie['mime'] = [string]$media.mime
            $copie['fichier'] = ''
            try {
                if (-not (Test-PasDeJonction $script:MediasDir)) { throw 'dossier des médias remplacé par une jonction' }
                if (-not (Test-Path $script:MediasDir)) { New-Item -ItemType Directory -Path $script:MediasDir -Force | Out-Null }
                $cible = Join-Path $script:MediasDir (($uid -replace '[^0-9a-f]', '') + $ext)
                $parametres = @{ Uri = ($Config.url.TrimEnd('/')) + [string]$media.url; OutFile = $cible; UseBasicParsing = $true; TimeoutSec = 600 }
                if (Get-PropOrDefault $Config 'insecure' $false) {
                    if ($PSVersionTable.PSVersion.Major -ge 6) { $parametres['SkipCertificateCheck'] = $true }
                    else { [System.Net.ServicePointManager]::ServerCertificateValidationCallback = { $true } }
                }
                Invoke-WebRequest @parametres
                $copie['fichier'] = $cible
            } catch {
                Write-Journal "Média d'un message non téléchargé : $_" 'WARN'
            }
        }
        $liste += [pscustomobject]$copie
        Write-Journal ("Message « {0} » ({1}) reçu pour la fenêtre." -f [string]$m.title, [string]$m.type)
    }
    Write-MessagesRiches -Liste $liste
}

function Set-MessagesLiberes {
    <# `liberer` du relevé : bloquants « parent » levés depuis Home Assistant. #>
    param($Ids)
    $tous = @($Ids | Where-Object { [string]$_ -eq '*' }).Count -gt 0
    $liste = @(Read-MessagesRiches)
    $change = $false
    foreach ($m in @($liste | Where-Object { $_ })) {
        if ([string](Get-PropOrDefault $m 'bloquant' '') -eq 'parent' -and ($tous -or @($Ids | Where-Object { [string]$_ -eq [string](Get-PropOrDefault $m 'id' '') }).Count)) {
            $m | Add-Member -NotePropertyName 'libere' -NotePropertyValue $true -Force
            $change = $true
        }
    }
    if ($change) { Write-MessagesRiches -Liste $liste }
}

function Set-MessageChamp {
    <# Un champ d'un message de la file (relance, réponse au code), pour la fenêtre. #>
    param([string]$Uid, [string]$Nom, $Valeur, [switch]$SansLibere)
    $liste = @(Read-MessagesRiches)
    $change = $false
    foreach ($m in @($liste | Where-Object { $_ -and [string](Get-PropOrDefault $_ 'uid' '') -eq $Uid })) {
        $m | Add-Member -NotePropertyName $Nom -NotePropertyValue $Valeur -Force; $change = $true
        # Un `libere` que Home Assistant n'a pas confirmé (posé à la main dans messages.json) s'en va.
        if ($SansLibere -and $m.PSObject.Properties['libere']) { $m.PSObject.Properties.Remove('libere') }
    }
    if ($change) { Write-MessagesRiches -Liste $liste }
}

function Send-Codes {
    <#
        Faille 2.2-1 : le code de gestion tapé sur un message bloquant « parent ». Home Assistant le
        vérifie (empreinte gardée chez lui seul), limite les essais faux et lève le message. Le fichier, que
        son auteur ne peut pas relire, est effacé dès qu'il est lu ; le code n'est jamais journalisé.
    #>
    param($Config)
    if ($null -eq $Config -or -not (Test-Path $script:SondeDir)) { return }
    $fichiers = @(Get-ChildItem -Path $script:SondeDir -Filter 'code-*.json' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime | Select-Object -First 3)
    foreach ($f in $fichiers) {
        $a = $null
        try { $a = Get-Content -LiteralPath $f.FullName -Raw -Encoding UTF8 | ConvertFrom-Json } catch { }
        Remove-Item -LiteralPath $f.FullName -Force -ErrorAction SilentlyContinue
        if ($null -eq $a) { continue }
        $uid = [string](Get-PropOrDefault $a 'uid' '')
        $retour = [ordered]@{ t = [string][DateTime]::UtcNow.Ticks; etat = 'reseau' }
        $corps = @{ id = $Config.id; secret = $Config.secret; message = [int](Get-PropOrDefault $a 'message' 0); code = [string](Get-PropOrDefault $a 'code' '') }
        $a = $null
        try {
            $r = Invoke-Ha -Chemin '/api/pc_parental/code' -Corps $corps -Config $Config
            $retour.etat = 'ok'
            $ids = @(Get-PropOrDefault $r 'liberer' @())
            if ($ids.Count) { Set-MessagesLiberes -Ids $ids }
            Write-Journal 'Message bloquant levé par le code de gestion (vérifié par Home Assistant).'
        } catch {
            $statut = 0
            try { $statut = [int]$_.Exception.Response.StatusCode } catch { }
            $refus = $null
            try { if ($_.ErrorDetails -and $_.ErrorDetails.Message) { $refus = [string]$_.ErrorDetails.Message | ConvertFrom-Json } } catch { }
            $retour.etat = switch ($statut) { 403 { 'faux' } 429 { 'trop' } 423 { 'verrouille' } 409 { 'aucun' } 404 { $(if ($refus) { 'inconnu' } else { 'ancien' }) } default { 'reseau' } }
            if ($refus) {
                $retour['restants'] = [int](Get-PropOrDefault $refus 'essais_restants' 0)
                $retour['attente'] = [int](Get-PropOrDefault $refus 'attente' 0)
            }
            Write-Journal ("Code de gestion refusé par Home Assistant ({0})." -f $retour.etat) 'WARN'
        }
        $corps = $null
        if ($uid) { Set-MessageChamp -Uid $uid -Nom 'codeRetour' -Valeur ([pscustomobject]$retour) }
    }
}

function Send-Accuses {
    <# Les accusés déposés par la fenêtre partent à Home Assistant ; un message fini quitte la file. #>
    param($Config)
    if ($null -eq $Config -or -not (Test-Path $script:SondeDir)) { return }
    $fichiers = @(Get-ChildItem -Path $script:SondeDir -Filter 'accuse-*.json' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime | Select-Object -First 20)
    if (-not $fichiers.Count) { return }
    $finis = @()
    $file = @(Read-MessagesRiches)
    foreach ($f in $fichiers) {
        $a = $null
        try { $a = Get-Content -LiteralPath $f.FullName -Raw -Encoding UTF8 | ConvertFrom-Json } catch { }
        if ($null -eq $a) { Remove-Item -LiteralPath $f.FullName -Force -ErrorAction SilentlyContinue; continue }
        # Faille 2.2-1 (relecture) : l'accusé doit nommer un message de la file par son uid ET son numéro.
        $leSien = @($file | Where-Object { $_ -and [string](Get-PropOrDefault $_ 'uid' '') -eq [string](Get-PropOrDefault $a 'uid' '') })
        if (-not $leSien.Count -or [string](Get-PropOrDefault $leSien[0] 'id' '') -ne [string](Get-PropOrDefault $a 'message' '')) {
            Write-Journal 'Accusé sans message correspondant dans la file : ignoré.' 'WARN'
            Remove-Item -LiteralPath $f.FullName -Force -ErrorAction SilentlyContinue; continue
        }
        $corps = @{ id = $Config.id; secret = $Config.secret; message = [int]$a.message; etat = [string]$a.etat; detail = [string](Get-PropOrDefault $a 'detail' '') }
        $refuse = $false
        try {
            [void](Invoke-Ha -Chemin '/api/pc_parental/message' -Corps $corps -Config $Config)
        } catch {
            $code = 0
            try { $code = [int]$_.Exception.Response.StatusCode } catch { }
            # Réseau absent : on réessaiera ; refus de Home Assistant (message inconnu…) : on n'insiste pas.
            if ($code -eq 0 -or $code -ge 500) { break }
            # Faille 2.2-1 : tout refus (409 : Home Assistant n'a pas levé ce bloquant, limite pas encore passée ;
            # 404 : message inconnu…) laisse le message dans la file ; un bloquant revient à l'écran.
            $refuse = $true
        }
        Remove-Item -LiteralPath $f.FullName -Force -ErrorAction SilentlyContinue
        if ($refuse) {
            if ([string](Get-PropOrDefault $leSien[0] 'bloquant' '') -eq 'parent' -and [string]$a.etat -ne 'affiche') {
                Write-Journal ("Accusé « {0} » refusé par Home Assistant : le message bloquant revient." -f [string]$a.etat) 'WARN'
                Set-MessageChamp -Uid ([string]$a.uid) -Nom 'relance' -Valeur ([string][DateTime]::UtcNow.Ticks) -SansLibere
            }
            continue
        }
        if ([string]$a.etat -in @('lu', 'libere_parent', 'libere_auto', 'expire')) { $finis += [string]$a.uid }
    }
    if ($finis.Count) {
        $liste = @(Read-MessagesRiches)
        foreach ($m in @($liste | Where-Object { $_ -and [string](Get-PropOrDefault $_ 'uid' '') -in $finis })) {
            # Faille 2.2-1 (relecture) : messages.json est modifiable par l'utilisateur ; on n'efface qu'un fichier
            # posé directement dans le dossier des médias, au nom que l'agent lui donne, jamais un joker.
            $fichier = [string](Get-PropOrDefault $m 'fichier' '')
            if (-not $fichier) { continue }
            try {
                $plein = [IO.Path]::GetFullPath($fichier)
                if ([IO.Path]::GetDirectoryName($plein) -eq [IO.Path]::GetFullPath($script:MediasDir) -and
                    [IO.Path]::GetFileName($plein) -match '^[0-9a-f]{1,32}\.[a-z0-9]{2,4}$' -and (Test-PasDeJonction $script:MediasDir)) {
                    Remove-Item -LiteralPath $plein -Force -ErrorAction SilentlyContinue
                }
            } catch { }
        }
        Write-MessagesRiches -Liste @($liste | Where-Object { $_ -and [string](Get-PropOrDefault $_ 'uid' '') -notin $finis })
    }
}

function Send-Actions {
    # Les actions du parent déposées par la fenêtre (action-*.json) partent à
    # Home Assistant avec le secret de ce PC ; puis l'espace est rafraîchi.
    param($Config)
    if ($null -eq $Config -or -not $script:EstParent -or -not (Test-Path $script:SondeDir)) { return }
    $fichiers = @(Get-ChildItem -Path $script:SondeDir -Filter 'action-*.json' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime | Select-Object -First 5)
    $fait = $false
    foreach ($f in $fichiers) {
        $a = $null
        try { $a = Get-Content $f.FullName -Raw -Encoding UTF8 | ConvertFrom-Json } catch { }
        Remove-Item $f.FullName -Force -ErrorAction SilentlyContinue
        if ($null -eq $a -or -not (Get-PropOrDefault $a 'action' '')) { continue }
        $corps = @{ id = $Config.id; secret = $Config.secret }
        foreach ($p in $a.PSObject.Properties) { $corps[$p.Name] = $p.Value }
        try {
            $r = Invoke-Ha -Chemin '/api/pc_parental/parent' -Corps $corps -Config $Config
            $retour = [string](Get-PropOrDefault $r 'retour' '')
            if ($retour) { [void](Send-Message -Texte $retour -Titre 'Vision' -Secondes 6 -Son '') }
            $fait = $true
        } catch {
            Write-Journal "Action de gestion refusée : $_" 'WARN'
            # Home Assistant dit pourquoi (« Écris les heures comme 18:30 ») : on le répète plutôt qu'un refus muet.
            $pourquoi = ''
            try {
                $brut = ''
                if ($_.ErrorDetails -and $_.ErrorDetails.Message) { $brut = [string]$_.ErrorDetails.Message }
                if ($brut) {
                    $refus = $brut | ConvertFrom-Json
                    $pourquoi = [string](Get-PropOrDefault $refus 'retour' '')
                    # 403 {"error":"module"} : la fonction appartient à un module coupé dans Vision.
                    if (-not $pourquoi -and [string](Get-PropOrDefault $refus 'error' '') -eq 'module') { $pourquoi = (T 'agent.fonction_coupee') }
                }
            } catch { }
            if (-not $pourquoi) { $pourquoi = (T 'agent.action_refusee') }
            [void](Send-Message -Texte $pourquoi -Titre 'Vision' -Secondes 8 -Son '')
            $fait = $true
        }
    }
    if ($fait) { Sync-Maison -Config $Config -Force $true }
}

function Send-Demandes {
    <#
        Le clic sur « Demander l'accès » a déposé un fichier dans le dossier
        de la sonde. On l'envoie à Home Assistant, qui prévient les parents,
        et on le dit : la demande ne part pas dans le vide.
    #>
    param($Config)
    if ($null -eq $Config -or -not (Test-Path $script:SondeDir)) { return }
    if (-not (Module-Actif 'demandes')) {
        # Module Demandes coupé : la fenêtre ne propose plus de demander ; ce qui serait resté en
        # route ne part pas (Home Assistant le refuserait), et ne repartira pas des jours plus tard.
        foreach ($f in @(Get-ChildItem -Path $script:SondeDir -Filter 'demande-*.json' -ErrorAction SilentlyContinue)) {
            Remove-Item $f.FullName -Force -ErrorAction SilentlyContinue
            Write-Journal "Demande non envoyée (module Demandes coupé) : $($f.Name)."
        }
        return
    }
    $fichiers = @(Get-ChildItem -Path $script:SondeDir -Filter 'demande-*.json' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime | Select-Object -First 5)
    foreach ($f in $fichiers) {
        $contenu = $null
        try { $contenu = Get-Content $f.FullName -Raw -Encoding UTF8 | ConvertFrom-Json } catch { }
        if ($null -eq $contenu -or (-not (Get-PropOrDefault $contenu 'nom' '') -and (Get-PropOrDefault $contenu 'genre' '') -ne 'temps')) {
            Remove-Item $f.FullName -Force -ErrorAction SilentlyContinue
            continue
        }
        $genre = [string](Get-PropOrDefault $contenu 'genre' 'apps')
        if ($genre -eq 'temps') {
            # Une demande de temps part avec le prochain relevé, comme sur Android.
            $script:DemandeTemps = [int](Get-PropOrDefault $contenu 'minutes' 30)
            Remove-Item $f.FullName -Force -ErrorAction SilentlyContinue
            Write-Journal "Demande de temps : $($script:DemandeTemps) min."
            [void](Send-Message -Texte (T 'agent.demande_temps_envoyee' @{ min = $script:DemandeTemps }) -Titre 'Vision' -Secondes 8 -Son '')
            continue
        }
        $libelle = [string](Get-PropOrDefault $contenu 'libelle' $contenu.nom)
        try {
            $r = Invoke-Ha -Chemin '/api/pc_parental/demande' -Corps @{
                id      = $Config.id
                secret  = $Config.secret
                genre   = $genre
                nom     = [string]$contenu.nom
                libelle = $libelle
                lien    = [string](Get-PropOrDefault $contenu 'lien' '')
                motif   = [string](Get-PropOrDefault $contenu 'motif' '')
                duree   = [string](Get-PropOrDefault $contenu 'duree' '')
                # HA 2.5.1 : une demande pour un logiciel emporte ses sites bloqués (une seule demande).
                sites   = @(Get-PropOrDefault $contenu 'sites' @() | ForEach-Object { [string]$_ })
            } -Config $Config
            Remove-Item $f.FullName -Force -ErrorAction SilentlyContinue
            if ([string](Get-PropOrDefault $r 'error' '') -eq 'refus') {
                # Un parent a dit non il y a peu : la demande n'est pas repartie, et on dit jusqu'à quand.
                $attente = [string](Get-PropOrDefault $r 'retour' '')
                if (-not $attente) { $attente = (T 'agent.pas_redemander' @{ nom = $libelle }) }
                Write-Journal "Demande d'accès en attente après un refus : $libelle."
                [void](Send-Message -Texte $attente -Titre 'Vision' -Secondes 8 -Son '')
                continue
            }
            Write-Journal "Demande d'accès envoyée : $libelle."
            [void](Send-Message -Texte (T 'agent.demande_envoyee' @{ nom = $libelle }) `
                -Titre 'Vision' -Secondes 8 -Son '')
        } catch {
            # Home Assistant injoignable : le fichier reste, on réessaiera.
            if ((Get-CodeHttp $_) -ge 400) { Remove-Item $f.FullName -Force -ErrorAction SilentlyContinue }
            Write-Journal "Demande d'accès non envoyée : $_" 'WARN'
            break
        }
    }
}

function Wait-Tour {
    <#
        L'attente entre deux appels à Home Assistant n'est pas du repos.

        On n'y dort pas : on y écoute. Windows annonce chaque lancement de
        processus, et l'attente se réveille sur cette annonce — le logiciel
        interdit est refermé dans la seconde, pas au tour suivant. Deux
        secondes de sursis suffisaient déjà à ouvrir une bibliothèque de
        jeux ; vingt en faisaient une soirée.

        Le balayage complet reste, en filet. Espacé quand la veille écoute,
        resserré quand elle est muette : le filet ne se relâche que si le
        fil tient.
    #>
    param([int]$Secondes)

    $filet = if ($script:VeilleActive) { $script:BalayageApps } else { 2 }
    $fin = (Get-Date).AddSeconds($Secondes)
    $dernierBalayage = Get-Date
    while ((Get-Date) -lt $fin) {
        $restant = [int][Math]::Ceiling(($fin - (Get-Date)).TotalSeconds)
        if ($restant -lt 1) { break }
        if ($script:VeilleActive) {
            $annonce = Wait-Event -SourceIdentifier $script:VeilleApps -Timeout $restant
            if ($annonce) {
                $lancement = Read-Lancement -Annonce $annonce
                Remove-Event -EventIdentifier $annonce.EventIdentifier -ErrorAction SilentlyContinue
                Deny-Lancement -Nom $lancement.Nom -Identifiant $lancement.Id
            }
        } else {
            Start-Sleep -Seconds ([Math]::Min(2, $restant))
        }
        if (((Get-Date) - $dernierBalayage).TotalSeconds -ge $filet) {
            $dernierBalayage = Get-Date
            Stop-LogicielsInterdits -Noms $script:AppsInterdites
            try { Send-Demandes -Config $script:ConfigCourante } catch { }
            try { Send-Actions -Config $script:ConfigCourante } catch { }
            try { Send-Codes -Config $script:ConfigCourante } catch { }
            try { Send-Accuses -Config $script:ConfigCourante } catch { }
        }
    }
}

$script:SafeSearchTable = [ordered]@{
    'google.com' = 'forcesafesearch.google.com'; 'www.google.com' = 'forcesafesearch.google.com'
    'google.fr' = 'forcesafesearch.google.com'; 'www.google.fr' = 'forcesafesearch.google.com'
    'youtube.com' = 'restrict.youtube.com'; 'www.youtube.com' = 'restrict.youtube.com'; 'm.youtube.com' = 'restrict.youtube.com'
    'youtubei.googleapis.com' = 'restrict.youtube.com'; 'youtube.googleapis.com' = 'restrict.youtube.com'
    'www.youtube-nocookie.com' = 'restrict.youtube.com'
    'bing.com' = 'strict.bing.com'; 'www.bing.com' = 'strict.bing.com'
    'duckduckgo.com' = 'safe.duckduckgo.com'; 'www.duckduckgo.com' = 'safe.duckduckgo.com'
}
# Adresses de secours si la résolution échoue (publiées par Google, Microsoft, DuckDuckGo).
$script:SafeSearchSecours = @{
    'forcesafesearch.google.com' = '216.239.38.120'; 'restrict.youtube.com' = '216.239.38.120'
    'strict.bing.com' = '204.79.197.220'; 'safe.duckduckgo.com' = '52.142.124.215'
}
$script:SafeSearchIps = @{}
$script:SafeSearchDepuis = [datetime]::MinValue

function Get-CiblesSafeSearch {
    <# Chaque nom vers l'adresse IPv4 de sa version sûre, résolue au plus une fois par jour. #>
    if (((Get-Date) - $script:SafeSearchDepuis).TotalHours -ge 24 -or $script:SafeSearchIps.Count -eq 0) {
        foreach ($cible in @($script:SafeSearchTable.Values | Sort-Object -Unique)) {
            $ip = $null
            try { $ip = ([System.Net.Dns]::GetHostAddresses($cible) | Where-Object { $_.AddressFamily -eq 'InterNetwork' } | Select-Object -First 1).IPAddressToString } catch { }
            if (-not $ip) { $ip = $script:SafeSearchSecours[$cible] }
            $script:SafeSearchIps[$cible] = $ip
        }
        $script:SafeSearchDepuis = Get-Date
    }
    foreach ($nom in $script:SafeSearchTable.Keys) {
        $ip = $script:SafeSearchIps[$script:SafeSearchTable[$nom]]
        if ($ip) { [pscustomobject]@{ Nom = $nom; Ip = $ip } }
    }
}

function Sync-Hosts {
    <#
        Home Assistant décide par **site** — « youtube.com » — parce que
        personne ne veut trancher sur « m.youtube.com » puis sur
        « r3---sn-4g5e6ne7.googlevideo.com ». Le fichier hosts, lui, ne
        connaît pas les jokers : il faut donc nommer chaque sous-domaine.

        On les prend là où ils passent — le cache DNS de la machine — et on
        garde ceux qu'on bloquait déjà, sinon un sous-domaine sorti du cache
        redeviendrait joignable le temps d'une résolution.

        Les adresses épargnées traversent tout cela sans être touchées :
        c'est exactement ce qu'on leur demande.

        1.93.2 (perte d'images par seconde chez un Demandeur) : même entrée, même bloc, mais plus de comparaison de
        chaque site à chaque nom (717 × 7 000 « -like », ~15 s par tour) ni d'ajouts dans un tableau. Les noms
        candidats sont rangés une fois sous chacun de leurs domaines parents : un site retrouve les siens en une
        recherche. L'ordre et le plafond par site restent ceux d'avant (essai/banc_sync_hosts.ps1, hosts_*_essai.ps1).
        -Flush $false : le vidage du cache DNS est laissé à l'appelant, qui le regroupe (Invoke-FlushDnsGroupe).
    #>
    param([string[]]$Sites, [string[]]$Exceptions = @(), [string[]]$Vus = @(),
          [string[]]$Dangereux = @(), [bool]$SafeSearch = $false, [bool]$Flush = $true)

    $epargnees = New-Object 'System.Collections.Generic.HashSet[string]'
    foreach ($e in $Exceptions) {
        if ($e) { [void]$epargnees.Add($e.Trim().ToLowerInvariant()) }
    }

    # Lecture directe (UTF-8, marque d'ordre retirée comme le faisait Get-Content -Encoding UTF8) : ~15 000 lignes.
    $actuel = [string[]]@()
    if (Test-Path $script:HostsPath) {
        $actuel = [IO.File]::ReadAllLines($script:HostsPath, [Text.Encoding]::UTF8)
    }

    $debut = [array]::IndexOf($actuel, $script:MarqueDebut)
    $fin   = [array]::IndexOf($actuel, $script:MarqueFin)

    # Les candidats : les noms vus ce tour-ci et ce que nous bloquions déjà (notre mémoire des sous-domaines),
    # sans doublon (à la casse près) et dans l'ordre alphabétique de Sort-Object, qui décide lesquels entrent
    # quand un site atteint son plafond.
    $uniques = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
    $liste = New-Object 'System.Collections.Generic.List[string]'
    foreach ($v in $Vus) { if ($v -and $uniques.Add($v)) { $liste.Add($v) } }
    if ($debut -ge 0 -and $fin -gt ($debut + 1)) {
        for ($i = $debut + 1; $i -lt $fin; $i++) {
            $bouts = $actuel[$i].Split([char[]]@(' ', "`t"), [StringSplitOptions]::RemoveEmptyEntries)
            if ($bouts.Count -ge 2) {
                $n = $bouts[1].ToLowerInvariant()
                if ($uniques.Add($n)) { $liste.Add($n) }
            }
        }
    }
    $candidats = $liste.ToArray()
    [array]::Sort($candidats, [StringComparer]::CurrentCultureIgnoreCase)

    # Chaque candidat sous chacun de ses domaines parents : « a.b.site.com » sous « b.site.com », « site.com »
    # et « com ». C'est exactement ce que « -like "*.$domaine" » reconnaissait, sans tout comparer à tout.
    $parents = New-Object 'System.Collections.Generic.Dictionary[string,System.Collections.Generic.List[string]]' ([StringComparer]::OrdinalIgnoreCase)
    foreach ($c in $candidats) {
        $p = $c.IndexOf('.')
        while ($p -ge 0 -and $p -lt $c.Length - 1) {
            $parent = $c.Substring($p + 1)
            $sous = $null
            if (-not $parents.TryGetValue($parent, [ref]$sous)) {
                $sous = New-Object 'System.Collections.Generic.List[string]'
                $parents[$parent] = $sous
            }
            $sous.Add($c)
            $p = $c.IndexOf('.', $p + 1)
        }
    }

    $lignes = New-Object 'System.Collections.Generic.List[string]'
    foreach ($site in $Sites) {
        if (-not $site) { continue }
        $domaine = $site.Trim().ToLowerInvariant() -replace '^https?://', '' -replace '/.*$', ''
        if (-not $domaine) { continue }
        $noms = New-Object System.Collections.Generic.List[string]
        foreach ($n in @($domaine, "www.$domaine")) {
            if (-not $noms.Contains($n)) { $noms.Add($n) }
        }
        $sous = $null
        if ($parents.TryGetValue($domaine, [ref]$sous)) {
            foreach ($c in $sous) {
                if ($noms.Count -ge $script:MaxSousDomaines) { break }
                if (-not $noms.Contains($c)) { $noms.Add($c) }
            }
        }
        foreach ($n in $noms) {
            if ($epargnees.Contains($n)) { continue }
            $lignes.Add("0.0.0.0 $n")
            $lignes.Add(":: $n")
        }
    }

    # Les domaines signales dangereux : pas de sous-domaines a deviner, la
    # liste donne des noms exacts. Une exception les epargne comme le reste :
    # cent cinquante mille domaines signales par des tiers, c'est cent
    # cinquante mille occasions de se tromper, et il faut pouvoir dire
    # « celui-la, je le connais ».
    foreach ($d in $Dangereux) {
        if (-not $d) { continue }
        $nom = $d.Trim().ToLowerInvariant()
        if (-not $nom) { continue }
        if ($epargnees.Contains($nom)) { continue }
        $lignes.Add("0.0.0.0 $nom")
        $lignes.Add(":: $nom")
    }

    # SafeSearch et YouTube restreint (1.79.0), comme l'appli Android : les moteurs de recherche
    # pointent vers leur version sûre. Seulement là où Home Assistant le demande (catégorie Adulte
    # coupée) ; le DNS chiffré des navigateurs étant coupé, le fichier hosts fait foi.
    if ($SafeSearch) {
        foreach ($paire in (Get-CiblesSafeSearch)) {
            if ($epargnees.Contains($paire.Nom)) { continue }
            $lignes.Add(('{0} {1}' -f $paire.Ip, $paire.Nom))
        }
    }

    $voulu = New-Object 'System.Collections.Generic.List[string]'
    if ($debut -ge 0 -and $fin -gt $debut) {
        for ($i = 0; $i -lt $debut; $i++) { $voulu.Add($actuel[$i]) }
        for ($i = $fin + 1; $i -lt $actuel.Count; $i++) { $voulu.Add($actuel[$i]) }
    } else {
        foreach ($l in $actuel) { $voulu.Add($l) }
    }
    if ($lignes.Count -gt 0) {
        $voulu.Add($script:MarqueDebut)
        $voulu.AddRange($lignes)
        $voulu.Add($script:MarqueFin)
    }

    $avant = ($actuel -join "`n")
    $apres = ($voulu.ToArray() -join "`n")
    if ($avant -eq $apres) { return }

    try {
        Set-Content -Path $script:HostsPath -Value $voulu.ToArray() -Encoding UTF8 -Force
        if ($Flush) { ipconfig /flushdns | Out-Null } else { $script:FlushDnsDemande = $true }
        Write-Journal "Fichier hosts mis à jour ($($lignes.Count) entrées)."
    } catch {
        Write-Journal "Écriture du fichier hosts impossible : $_" 'WARN'
    }
}

function Get-EmpreinteHosts {
    <# Ce qui dit, sans le relire, si le fichier hosts a bougé depuis notre dernier passage : date et taille. #>
    try {
        $f = Get-Item -LiteralPath $script:HostsPath -ErrorAction Stop
        return ('{0}|{1}' -f $f.LastWriteTimeUtc.Ticks, $f.Length)
    } catch {
        return ''
    }
}

function Test-SousDomaineNouveau {
    <#
        Un nom vu pour la première fois depuis la dernière reconstruction est-il un sous-domaine d'un site bloqué ?
        Seuls les noms nouveaux sont examinés : en régime établi, une poignée de recherches par tour.
    #>
    param([string[]]$Vus)
    $etat = $script:HostsEtat
    $trouve = $false
    foreach ($v in $Vus) {
        if (-not $v -or -not $etat.Vus.Add($v)) { continue }
        $n = $v.ToLowerInvariant()
        if ($etat.Epargnees.Contains($n)) { continue }
        $p = $n.IndexOf('.')
        while ($p -ge 0 -and $p -lt $n.Length - 1) {
            if ($etat.Sites.Contains($n.Substring($p + 1))) { $trouve = $true; break }
            $p = $n.IndexOf('.', $p + 1)
        }
    }
    return $trouve
}

function Sync-HostsSiNecessaire {
    <#
        1.93.2 (décision du projet) : c'est Home Assistant qui dit quand le fichier hosts doit changer. Chaque réponse au
        relevé porte « hosts_version », l'empreinte de ce que l'agent met dans le bloc (sites, exceptions, version de
        la liste noire, SafeSearch). Tant qu'elle ne bouge pas, l'agent ne reconstruit rien, sauf :
          - un sous-domaine nouveau d'un site bloqué apparaît dans le cache DNS (il doit rejoindre le blocage) ;
          - le fichier hosts a été modifié par quelqu'un d'autre (date ou taille) : on remet notre bloc ;
          - par sécurité, toutes les 10 minutes.
        Sans version (Home Assistant plus ancien, décision gardée hors ligne), l'empreinte est calculée ici sur les
        mêmes entrées : le comportement reste sûr.
    #>
    param([string]$Version = '', [string[]]$Sites, [string[]]$Exceptions = @(), [string[]]$Vus = @(),
          [string[]]$Dangereux = @(), [bool]$SafeSearch = $false, [bool]$Flush = $true)

    $Sites = @($Sites | Where-Object { $_ })
    $Exceptions = @($Exceptions | Where-Object { $_ })
    $Dangereux = @($Dangereux)
    if (-not $Version) {
        $texte = (($Sites -join ',') + '|' + ($Exceptions -join ',') + '|' + ($Dangereux -join ','))
        $sha = [Security.Cryptography.SHA256]::Create()
        try { $Version = 'local-' + [BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($texte))).Replace('-', '') }
        finally { $sha.Dispose() }
    }
    # Les tailles en plus de la version : une décision incohérente avec sa version se reconstruit quand même.
    $cle = '{0}|{1}|{2}|{3}|{4}' -f $Version, $Sites.Count, $Exceptions.Count, $Dangereux.Count, $SafeSearch
    $etat = $script:HostsEtat
    if ($etat -and $etat.Cle -eq $cle -and ((Get-Date) - $etat.Depuis).TotalSeconds -lt $script:HostsSecuriteSecondes -and
            $etat.Fichier -eq (Get-EmpreinteHosts)) {
        if (-not (Test-SousDomaineNouveau -Vus $Vus)) { return $false }
    }

    $chrono = [Diagnostics.Stopwatch]::StartNew()
    Sync-Hosts -Sites $Sites -Exceptions $Exceptions -Vus $Vus -Dangereux $Dangereux -SafeSearch $SafeSearch -Flush $Flush
    $chrono.Stop()
    $script:HostsDureeMs = [int]$chrono.Elapsed.TotalMilliseconds

    $sitesConnus = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
    foreach ($s in $Sites) {
        $d = $s.Trim().ToLowerInvariant() -replace '^https?://', '' -replace '/.*$', ''
        if ($d) { [void]$sitesConnus.Add($d) }
    }
    $epargnees = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
    foreach ($e in $Exceptions) { [void]$epargnees.Add($e.Trim().ToLowerInvariant()) }
    $vusConnus = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
    foreach ($v in $Vus) { if ($v) { [void]$vusConnus.Add($v) } }
    $script:HostsEtat = @{
        Cle = $cle; Depuis = (Get-Date); Fichier = (Get-EmpreinteHosts)
        Sites = $sitesConnus; Epargnees = $epargnees; Vus = $vusConnus
    }
    return $true
}

function Get-ChargeAgent {
    <#
        1.93.2 : ce que l'agent coûte à la machine, pour Home Assistant. Processeur en % d'**une** machine entière
        (tous cœurs confondus) depuis le relevé précédent, mémoire de travail en Mo, durée de la dernière
        reconstruction du fichier hosts en ms.
    #>
    try {
        $proc = [Diagnostics.Process]::GetCurrentProcess()
        $proc.Refresh()
        $maintenant = Get-Date
        $cpu = $proc.TotalProcessorTime
        $pct = $null
        if ($script:ChargePrecedente) {
            $mur = ($maintenant - $script:ChargePrecedente.Quand).TotalMilliseconds
            if ($mur -gt 0) {
                $pct = [math]::Round(100.0 * ($cpu - $script:ChargePrecedente.Cpu).TotalMilliseconds / $mur / [Environment]::ProcessorCount, 1)
            }
        }
        $script:ChargePrecedente = @{ Quand = $maintenant; Cpu = $cpu }
        return @{ cpu_pct = $pct; mem_mo = [math]::Round($proc.WorkingSet64 / 1MB, 1); hosts_ms = $script:HostsDureeMs }
    } catch {
        return $null
    }
}

function Invoke-FlushDnsGroupe {
    <# Un seul vidage du cache DNS par tour, même si le filtre DNS et le fichier hosts ont changé tous les deux. #>
    if ($script:FlushDnsDemande) {
        $script:FlushDnsDemande = $false
        ipconfig /flushdns | Out-Null
    }
}

function Disable-DnsChiffre {
    <#
        Sans cela, Chrome, Edge et Firefox résolvent les noms eux-mêmes en
        DNS-over-HTTPS et ignorent le fichier hosts.
    #>
    $politiques = @(
        @{ Chemin = 'HKLM:\SOFTWARE\Policies\Google\Chrome';    Nom = 'DnsOverHttpsMode'; Valeur = 'off' },
        @{ Chemin = 'HKLM:\SOFTWARE\Policies\Microsoft\Edge';   Nom = 'DnsOverHttpsMode'; Valeur = 'off' },
        @{ Chemin = 'HKLM:\SOFTWARE\Policies\Mozilla\Firefox\DNSOverHTTPS'; Nom = 'Enabled'; Valeur = 0 },
        @{ Chemin = 'HKLM:\SOFTWARE\Policies\Mozilla\Firefox\DNSOverHTTPS'; Nom = 'Locked';  Valeur = 1 }
    )
    foreach ($p in $politiques) {
        try {
            if (-not (Test-Path $p.Chemin)) {
                New-Item -Path $p.Chemin -Force | Out-Null
            }
            $type = if ($p.Valeur -is [int]) { 'DWord' } else { 'String' }
            New-ItemProperty -Path $p.Chemin -Name $p.Nom -Value $p.Valeur `
                -PropertyType $type -Force | Out-Null
        } catch {
            Write-Journal "Politique DNS « $($p.Nom) » non appliquée : $_" 'WARN'
        }
    }
}

function Enable-DnsChiffre {
    <#
        L'inverse, comme à la désinstallation : les navigateurs reprennent
        leur réglage. Sert quand le module « Filtrage web » est coupé.
    #>
    foreach ($p in @(
        @{ Chemin = 'HKLM:\SOFTWARE\Policies\Google\Chrome';  Nom = 'DnsOverHttpsMode' },
        @{ Chemin = 'HKLM:\SOFTWARE\Policies\Microsoft\Edge'; Nom = 'DnsOverHttpsMode' },
        @{ Chemin = 'HKLM:\SOFTWARE\Policies\Mozilla\Firefox\DNSOverHTTPS'; Nom = 'Enabled' },
        @{ Chemin = 'HKLM:\SOFTWARE\Policies\Mozilla\Firefox\DNSOverHTTPS'; Nom = 'Locked' }
    )) {
        Remove-ItemProperty -Path $p.Chemin -Name $p.Nom -ErrorAction SilentlyContinue
    }
}

function Sync-DnsChiffre {
    <# Pose ou retire les politiques DNS des navigateurs selon le module « Filtrage web », une fois par changement. #>
    param([bool]$Actif)
    if ($null -ne $script:DohPose -and [bool]$script:DohPose -eq $Actif) { return }
    if ($Actif) {
        Disable-DnsChiffre
    } else {
        Enable-DnsChiffre
        Write-Journal 'Filtrage web coupé : politiques DNS des navigateurs rendues.'
    }
    $script:DohPose = $Actif
}

# --------------------------------------------------------- Mise à jour

function Invoke-MiseAJour {
    <#
        Home Assistant héberge le client. Quand il annonce une version
        différente de celle qui tourne, on la récupère, on vérifie son
        empreinte, on remplace le script, puis on redémarre la tâche.
    #>
    param($Config, $Maj)

    $version = [string]$Maj.version
    $attendu = ([string]$Maj.sha256).ToLowerInvariant()
    if (-not $version -or -not $attendu) { return }

    $url = ($Config.url.TrimEnd('/')) + [string]$Maj.url
    Write-Journal "Mise à jour proposée : $($script:Version) → $version."

    $temporaire = Join-Path $script:Racine 'agent.nouveau.ps1'
    try {
        # 1.90.1 : sans -UseBasicParsing, Windows PowerShell 5.1 passe par le moteur d'Internet Explorer, absent
        # pour le compte SYSTEM qui fait tourner l'agent : le téléchargement échouait à chaque relevé.
        $parametres = @{
            Uri             = $url
            OutFile         = $temporaire
            TimeoutSec      = 60
            UseBasicParsing = $true
        }
        if (Get-PropOrDefault $Config 'insecure' $false) {
            if ($PSVersionTable.PSVersion.Major -ge 6) {
                $parametres['SkipCertificateCheck'] = $true
            } else {
                [System.Net.ServicePointManager]::ServerCertificateValidationCallback = { $true }
            }
        }
        Invoke-WebRequest @parametres | Out-Null
    } catch {
        Write-Journal "Téléchargement de la mise à jour impossible : $_" 'WARN'
        return
    }

    $obtenu = (Get-FileHash -Path $temporaire -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($obtenu -ne $attendu) {
        Write-Journal ("Empreinte incorrecte, mise à jour abandonnée " +
            "(attendu $attendu, obtenu $obtenu).") 'WARN'
        Remove-Item $temporaire -Force -ErrorAction SilentlyContinue
        return
    }

    # Un script vide ou tronqué laisserait la machine sans agent.
    $err = $null
    [void][System.Management.Automation.Language.Parser]::ParseFile(
        $temporaire, [ref]$null, [ref]$err)
    if ($err -and $err.Count -gt 0) {
        Write-Journal "Le script reçu est invalide, mise à jour abandonnée." 'WARN'
        Remove-Item $temporaire -Force -ErrorAction SilentlyContinue
        return
    }

    try {
        Copy-Item -Path $script:MoiMeme -Destination (
            Join-Path $script:Racine 'agent.precedent.ps1') -Force -ErrorAction SilentlyContinue
        Move-Item -Path $temporaire -Destination $script:MoiMeme -Force
    } catch {
        Write-Journal "Remplacement du script impossible : $_" 'WARN'
        return
    }

    Write-Journal "Mise à jour en $version installée, redémarrage de l'agent."

    # On relance la tâche depuis un processus détaché : celui-ci doit
    # d'abord laisser le temps à l'instance actuelle de se terminer.
    $relance = "Start-Sleep -Seconds 8; Start-ScheduledTask -TaskName '$($script:NomTache)'"
    Start-Process -FilePath 'powershell.exe' -WindowStyle Hidden -ArgumentList @(
        '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
        '-Command', $relance
    ) | Out-Null

    exit 0
}

# ------------------------------------------------------------ Mémoire locale

function Read-Etat {
    if (Test-Path $script:EtatPath) {
        try { return (Get-Content $script:EtatPath -Raw -Encoding UTF8 | ConvertFrom-Json) } catch { }
    }
    return $null
}

function Save-Etat {
    param($Decision)
    try {
        # « modules » : la grâce hors ligne applique la dernière décision avec les modules de ce moment-là.
        # « dns_pose » : un filtre DNS posé avant un redémarrage de l'agent se retire même s'il n'est plus voulu.
        @{
            action     = $Decision.action
            apps       = @($Decision.apps)
            sites      = @($Decision.sites)
            exceptions = @(Get-PropOrDefault $Decision 'exceptions' @())
            modules    = $script:Modules
            dns_pose   = $script:DnsPose
            quand      = (Get-Date).ToString('o')
        } | ConvertTo-Json -Depth 5 | Set-Content -Path $script:EtatPath -Encoding UTF8
    } catch { }
}

# -------------------------------------------------------------- Boucle

function Invoke-AutoDesinstallation {
    <#
        Le PC a ete supprime dans Home Assistant : l'agent n'a plus rien a
        faire ici. Il remet la machine dans l'etat ou il l'a trouvee, previent
        Home Assistant que c'est fait, puis s'efface lui-meme.

        L'ordre compte : on rend d'abord la machine libre (hosts, DNS, sonde),
        on confirme ensuite, et on ne supprime la tache et les dossiers qu'en
        dernier — un echec en cours de route laisse donc un PC utilisable et
        un agent qui reessaiera au prochain tour.
    #>
    param($Config)

    Write-Journal "Ce PC a ete retire de Home Assistant : desinstallation."

    try { Sync-Hosts -Sites @() } catch {
        Write-Journal "Nettoyage du fichier hosts : $_" 'WARN'
    }
    try { $script:DnsPose = 'a-remettre'; Set-DnsFiltrant -Serveurs @() } catch {
        Write-Journal "Retrait du filtre DNS : $_" 'WARN'
    }
    foreach ($p in @(
        @{ Chemin = 'HKLM:\SOFTWARE\Policies\Google\Chrome';  Nom = 'DnsOverHttpsMode' },
        @{ Chemin = 'HKLM:\SOFTWARE\Policies\Microsoft\Edge'; Nom = 'DnsOverHttpsMode' },
        @{ Chemin = 'HKLM:\SOFTWARE\Policies\Mozilla\Firefox\DNSOverHTTPS'; Nom = 'Enabled' },
        @{ Chemin = 'HKLM:\SOFTWARE\Policies\Mozilla\Firefox\DNSOverHTTPS'; Nom = 'Locked' }
    )) {
        Remove-ItemProperty -Path $p.Chemin -Name $p.Nom -ErrorAction SilentlyContinue
    }

    # La sonde tourne dans la session de l'utilisateur : elle survivrait.
    try {
        Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" -ErrorAction SilentlyContinue |
            Where-Object { $_.CommandLine -like '*HAParental*sonde.ps1*' } |
            ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    } catch { }

    # Confirmation : sans elle, Home Assistant garderait la consigne.
    try {
        $corps = @{
            id          = $Config.id
            secret      = $Config.secret
            host        = $env:COMPUTERNAME
            version     = $script:Version
            uninstalled = $true
        }
        [void](Invoke-Ha -Chemin '/api/pc_parental/poll' -Corps $corps -Config $Config)
    } catch {
        Write-Journal "Confirmation non transmise : $_" 'WARN'
    }

    # Le menage final se fait apres notre sortie : on ne peut pas supprimer
    # le dossier depuis lequel on s'execute, ni la tache qui nous porte.
    $menage = @(
        "Start-Sleep -Seconds 5",
        "Unregister-ScheduledTask -TaskName '$($script:NomTache)' -Confirm:`$false -ErrorAction SilentlyContinue",
        "Remove-Item -LiteralPath '$($script:Racine)' -Recurse -Force -ErrorAction SilentlyContinue",
        "Remove-Item -LiteralPath '$($script:SondeDir)' -Recurse -Force -ErrorAction SilentlyContinue"
    ) -join '; '
    Start-Process -FilePath 'powershell.exe' -WindowStyle Hidden -ArgumentList @(
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', $menage
    )
    Write-Journal "Desinstallation terminee. L'agent s'arrete."
}

# ------------------------------------------------ Processus et demarrage
#
# Ce qui tourne sur la machine, et ce qui s'y lance tout seul : cles Run et
# dossier Demarrage, taches planifiees et services d'editeurs tiers,
# processus en cours. L'agent le releve toutes les dix minutes ; Home
# Assistant le fait classer, un adulte tranche, et l'agent applique.
#
# Deux regles ne souffrent aucune exception :
#   * rien de ce qui fait Windows n'est touche -- C:\Windows, signature
#     « Microsoft Windows », services signes Microsoft, la liste ci-dessous ;
#   * tout ce qui est change se defait. Le registre de ce qu'on a modifie
#     vit dans processus.json, avec l'etat d'origine, et l'agent restaure
#     tout ce que Home Assistant ne demande plus.

$script:InventaireSecondes = 600
$script:DernierInventaire  = [datetime]::MinValue
$script:SigParChemin       = @{}
$script:ProcessusJournal   = Join-Path $script:Racine 'processus.json'
$script:DernieresConsignes = ''
$script:DerniereSyncProcessus = [datetime]::MinValue
$script:ProcessusProteges  = @(
    'system', 'registry', 'idle', 'smss', 'csrss', 'wininit', 'winlogon',
    'services', 'lsass', 'svchost', 'dwm', 'explorer', 'sihost',
    'taskhostw', 'fontdrvhost', 'conhost', 'ctfmon', 'audiodg', 'spoolsv',
    'msmpeng', 'nissrv', 'securityhealthservice', 'securityhealthsystray',
    'mpdefendercoreservice', 'powershell', 'pwsh'
)
$script:CleRun      = 'Software\Microsoft\Windows\CurrentVersion\Run'
$script:CleRun32    = 'Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Run'
$script:CleApprouve = 'Software\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved'

function Get-ValeurRegistre {
    <# Une valeur du registre, ou $null -- sans lever sous StrictMode. #>
    param([string]$Cle, [string]$Nom)
    try {
        $k = Get-Item -LiteralPath $Cle -ErrorAction Stop
        return $k.GetValue($Nom, $null)
    } catch { return $null }
}

function Get-SignatureFichier {
    param([string]$Chemin)
    $vide = @{ signataire = ''; statut = 'inconnu'; editeur = ''; description = '' }
    if (-not $Chemin -or -not (Test-Path -LiteralPath $Chemin -PathType Leaf)) { return $vide }
    if ($script:SigParChemin.ContainsKey($Chemin)) { return $script:SigParChemin[$Chemin] }
    $fiche = @{ signataire = ''; statut = 'inconnu'; editeur = ''; description = '' }
    try {
        $sig = Get-AuthenticodeSignature -FilePath $Chemin -ErrorAction Stop
        $fiche.statut = [string]$sig.Status
        if ($sig.SignerCertificate) {
            $cn = ([string]$sig.SignerCertificate.Subject -split ',' |
                Where-Object { $_.Trim() -like 'CN=*' } | Select-Object -First 1)
            if ($cn) { $fiche.signataire = ($cn -replace '^\s*CN=', '').Trim('"', ' ') }
        }
    } catch { $fiche.statut = 'illisible' }
    try {
        $v = (Get-Item -LiteralPath $Chemin -ErrorAction Stop).VersionInfo
        $fiche.editeur = [string]$v.CompanyName
        $fiche.description = [string]$v.FileDescription
    } catch { }
    $script:SigParChemin[$Chemin] = $fiche
    return $fiche
}

function Test-Protege {
    param([string]$Chemin, [string]$Signataire, [string]$Exe, [string]$Genre)
    if ($script:ProcessusProteges -contains $Exe.ToLowerInvariant()) { return $true }
    $bas = $Chemin.ToLowerInvariant()
    if ($bas.StartsWith(($env:windir + '\').ToLowerInvariant())) { return $true }
    $s = $Signataire.ToLowerInvariant()
    if ($s.StartsWith('microsoft windows')) { return $true }
    if ($Genre -eq 'service' -and $s.StartsWith('microsoft')) { return $true }
    return $false
}

function Get-CheminCommande {
    <# L'executable d'une ligne de commande : « "C:\a b\x.exe" -min », « C:\x.exe /s ». #>
    param([string]$Commande)
    if (-not $Commande) { return '' }
    $c = [Environment]::ExpandEnvironmentVariables($Commande.Trim())
    if ($c.StartsWith('"')) {
        $fin = $c.IndexOf('"', 1)
        if ($fin -gt 1) { return $c.Substring(1, $fin - 1) }
    }
    $m = [regex]::Match($c, '^(.+?\.(exe|com|bat|cmd|lnk))(\s|$)', 'IgnoreCase')
    if ($m.Success) { return $m.Groups[1].Value }
    return ($c -split '\s+')[0]
}

function Get-CibleRaccourci {
    param([string]$Lnk)
    try {
        $shell = New-Object -ComObject WScript.Shell
        return [string]$shell.CreateShortcut($Lnk).TargetPath
    } catch { return '' }
}

function Get-RacinesUtilisateurs {
    <# Les ruches chargees des vrais comptes (S-1-5-21-...), pas celles du systeme. #>
    return @(
        Get-ChildItem 'Registry::HKEY_USERS' -ErrorAction SilentlyContinue |
            Where-Object { $_.PSChildName -match '^S-1-5-21-[\d-]+$' } |
            ForEach-Object { 'Registry::HKEY_USERS\' + $_.PSChildName }
    )
}

function Get-EmplacementsDemarrage {
    <#
        Chaque endroit d'ou Windows lance quelque chose a l'ouverture de
        session, avec la cle StartupApproved qui dit s'il est active.
        L'identifiant ne porte pas le SID : la meme entree sur quatre PC doit
        etre la meme ligne a Home Assistant.
    #>
    $sortie = @()
    $racines = @(@{ Racine = 'Registry::HKEY_LOCAL_MACHINE'; Portee = 'M' }) +
               @(Get-RacinesUtilisateurs | ForEach-Object { @{ Racine = $_; Portee = 'U' } })
    foreach ($r in $racines) {
        foreach ($variante in @(
            @{ Cle = $script:CleRun;   Approuve = 'Run';   Suffixe = '' },
            @{ Cle = $script:CleRun32; Approuve = 'Run32'; Suffixe = '32' }
        )) {
            $chemin = Join-Path $r.Racine $variante.Cle
            $cle = Get-Item -LiteralPath $chemin -ErrorAction SilentlyContinue
            if ($null -eq $cle) { continue }
            foreach ($nom in @($cle.GetValueNames())) {
                if (-not $nom) { continue }
                $sortie += @{
                    Id       = ('RUN-{0}{1}|{2}' -f $r.Portee, $variante.Suffixe, $nom)
                    Nom      = $nom
                    Commande = [string]$cle.GetValue($nom)
                    Approuve = (Join-Path $r.Racine ($script:CleApprouve + '\' + $variante.Approuve))
                    Valeur   = $nom
                }
            }
        }
    }
    # Dossiers Demarrage : celui de chaque profil, et le commun.
    $dossiers = @(@{ Dossier = (Join-Path $env:ProgramData 'Microsoft\Windows\Start Menu\Programs\StartUp');
                     Portee = 'M'; Racine = 'Registry::HKEY_LOCAL_MACHINE' })
    foreach ($racine in (Get-RacinesUtilisateurs)) {
        $sid = ($racine -split '\\')[-1]
        $profil = [string](Get-ValeurRegistre ("Registry::HKEY_LOCAL_MACHINE\SOFTWARE\Microsoft\Windows NT\CurrentVersion\ProfileList\$sid") 'ProfileImagePath')
        if ($profil) {
            $dossiers += @{ Dossier = (Join-Path $profil 'AppData\Roaming\Microsoft\Windows\Start Menu\Programs\Startup');
                            Portee = 'U'; Racine = $racine }
        }
    }
    foreach ($d in $dossiers) {
        foreach ($f in @(Get-ChildItem -LiteralPath $d.Dossier -File -ErrorAction SilentlyContinue)) {
            if ($f.Name -ieq 'desktop.ini') { continue }
            $cible = if ($f.Extension -ieq '.lnk') { Get-CibleRaccourci $f.FullName } else { $f.FullName }
            $sortie += @{
                Id       = ('DOSSIER-{0}|{1}' -f $d.Portee, $f.Name)
                Nom      = [IO.Path]::GetFileNameWithoutExtension($f.Name)
                Commande = $cible
                Approuve = (Join-Path $d.Racine ($script:CleApprouve + '\StartupFolder'))
                Valeur   = $f.Name
            }
        }
    }
    return $sortie
}

function Test-DemarrageActif {
    param([string]$Approuve, [string]$Valeur)
    $octets = Get-ValeurRegistre $Approuve $Valeur
    if ($null -eq $octets -or @($octets).Count -eq 0) { return $true }
    # Premier octet pair : active (02, 06) ; impair : desactive (03, 07).
    return ((@($octets)[0] -band 1) -eq 0)
}

function Get-Inventaire {
    $items = @()

    foreach ($e in (Get-EmplacementsDemarrage)) {
        $chemin = Get-CheminCommande $e.Commande
        $sig = Get-SignatureFichier $chemin
        $exe = [IO.Path]::GetFileNameWithoutExtension($chemin).ToLowerInvariant()
        if (Test-Protege $chemin $sig.signataire $exe 'demarrage') { continue }
        $items += @{
            g = 'demarrage'; id = $e.Id; nom = $e.Nom; exe = $exe; chemin = $chemin
            commande = $e.Commande; signataire = $sig.signataire; statut = $sig.statut
            editeur = $sig.editeur; description = $sig.description
            ou = (Get-Emplacement -Chemin $chemin)
            actif = (Test-DemarrageActif $e.Approuve $e.Valeur)
        }
    }

    foreach ($t in @(Get-ScheduledTask -ErrorAction SilentlyContinue |
            Where-Object { $_.TaskPath -notlike '\Microsoft\*' -and $_.TaskName -ne $script:NomTache })) {
        $action = @($t.Actions | Where-Object { $_.PSObject.Properties['Execute'] -and $_.Execute }) | Select-Object -First 1
        if ($null -eq $action) { continue }
        $chemin = Get-CheminCommande ([string]$action.Execute)
        $sig = Get-SignatureFichier $chemin
        $exe = [IO.Path]::GetFileNameWithoutExtension($chemin).ToLowerInvariant()
        if (Test-Protege $chemin $sig.signataire $exe 'tache') { continue }
        $items += @{
            g = 'tache'; id = ($t.TaskPath + $t.TaskName); nom = $t.TaskName; exe = $exe
            chemin = $chemin; commande = (([string]$action.Execute + ' ' + [string]$action.Arguments).Trim())
            signataire = $sig.signataire; statut = $sig.statut; editeur = $sig.editeur
            description = $sig.description; ou = (Get-Emplacement -Chemin $chemin)
            actif = ([string]$t.State -ne 'Disabled'); mode = [string]$t.State
        }
    }

    foreach ($s in @(Get-CimInstance Win32_Service -ErrorAction SilentlyContinue)) {
        $chemin = Get-CheminCommande ([string]$s.PathName)
        if (-not $chemin -or $chemin.ToLowerInvariant().StartsWith(($env:windir + '\').ToLowerInvariant())) { continue }
        $sig = Get-SignatureFichier $chemin
        $exe = [IO.Path]::GetFileNameWithoutExtension($chemin).ToLowerInvariant()
        if (Test-Protege $chemin $sig.signataire $exe 'service') { continue }
        $mode = [string]$s.StartMode
        if ($mode -eq 'Auto' -and $s.PSObject.Properties['DelayedAutoStart'] -and $s.DelayedAutoStart) { $mode = 'Auto (différé)' }
        $items += @{
            g = 'service'; id = [string]$s.Name; nom = [string]$s.DisplayName; exe = $exe
            chemin = $chemin; commande = [string]$s.PathName; signataire = $sig.signataire
            statut = $sig.statut; editeur = $sig.editeur; description = [string]$s.Description
            ou = (Get-Emplacement -Chemin $chemin); actif = ([string]$s.State -eq 'Running'); mode = $mode
        }
    }

    $groupes = @(Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.Path } | Group-Object ProcessName)
    $proc = @()
    foreach ($gr in $groupes) {
        $p0 = $gr.Group[0]
        $chemin = [string]$p0.Path
        $exe = ([string]$gr.Name).ToLowerInvariant()
        $sig = Get-SignatureFichier $chemin
        if (Test-Protege $chemin $sig.signataire $exe 'processus') { continue }
        $ram = ($gr.Group | Measure-Object WorkingSet64 -Sum).Sum / 1MB
        $cpu = ($gr.Group | Where-Object { $_.CPU } | Measure-Object CPU -Sum).Sum
        $proc += @{
            g = 'processus'; id = $exe; nom = $(if ($sig.description) { $sig.description } else { $exe })
            exe = $exe; chemin = $chemin; signataire = $sig.signataire; statut = $sig.statut
            editeur = $sig.editeur; description = $sig.description; ou = (Get-Emplacement -Chemin $chemin)
            actif = $true; ram_mo = [math]::Round($ram, 1); cpu_s = [math]::Round([double]$cpu, 1)
            instances = @($gr.Group).Count
        }
    }
    $items += @($proc | Sort-Object { -$_.ram_mo } | Select-Object -First 150)

    # Les logiciels installés, tels que Windows les liste : c'est ce qu'un parent parcourt pour bloquer ou
    # désinstaller. Chacun avec ses programmes vus en marche (par son dossier), pour pouvoir les bloquer par leur nom.
    try {
        $vus = @{}
        $installes = @(Get-ProduitsInstalles)
        # Un dossier que plusieurs logiciels declarent (toute une suite dans « Adobe ») ne dit pas a qui
        # appartient un programme : on ne rattache alors aucun programme, plutot que de les bloquer tous.
        $parDossier = @{}
        foreach ($p in $installes) {
            if ($p.composant) { continue }
            $d0 = Get-DossierSur $p.dossier
            if ($d0) { if ($parDossier.ContainsKey($d0)) { $parDossier[$d0]++ } else { $parDossier[$d0] = 1 } }
        }
        foreach ($p in $installes) {
            if ($p.composant) { continue }
            $cleNom = ([string]$p.nom).Trim().ToLowerInvariant()
            if (-not $cleNom -or $vus.ContainsKey($cleNom)) { continue }
            $vus[$cleNom] = $true
            $dossier = Get-DossierSur $p.dossier
            $exes = @()
            if ($dossier -and $parDossier[$dossier] -eq 1) {
                foreach ($x in $proc) { if (([string]$x.chemin).ToLowerInvariant().StartsWith($dossier) -and $exes -notcontains $x.exe) { $exes += $x.exe } }
            }
            $items += @{
                g = 'logiciel'; id = $cleNom; nom = ([string]$p.nom).Trim(); editeur = [string]$p.editeur
                chemin = [string]$p.dossier; exes = (@($exes | Select-Object -First 12) -join ','); version = [string]$p.version
                actif = $true
            }
            if ($vus.Count -ge 400) { break }
        }
    } catch { Write-Journal "Liste des logiciels installés impossible : $_" 'WARN' }
    return $items
}

# ----------------------------------------------------------- Appliquer

function Read-JournalProcessus {
    $vide = @{ demarrage = @{}; taches = @{}; services = @{} }
    if (-not (Test-Path $script:ProcessusJournal)) { return $vide }
    try {
        $lu = Get-Content $script:ProcessusJournal -Raw -Encoding UTF8 | ConvertFrom-Json
        $j = @{ demarrage = @{}; taches = @{}; services = @{} }
        foreach ($genre in 'demarrage', 'taches', 'services') {
            $bloc = Get-PropOrDefault $lu $genre $null
            if ($null -eq $bloc) { continue }
            foreach ($p in $bloc.PSObject.Properties) { $j[$genre][$p.Name] = $p.Value }
        }
        return $j
    } catch { return $vide }
}

function Save-JournalProcessus {
    param($Journal)
    $Journal | ConvertTo-Json -Depth 6 -Compress | Set-Content -Path $script:ProcessusJournal -Encoding UTF8
}

function Get-Appliques {
    $j = Read-JournalProcessus
    return @{
        demarrage = @($j.demarrage.Keys)
        taches    = @($j.taches.Keys)
        services  = @($j.services.Keys)
    }
}

function Set-DemarrageActif {
    <#
        Comme le Gestionnaire des taches : on ne supprime rien, on pose la
        marque « desactive » dans StartupApproved. Rend ce qu'il y avait
        avant (base64, ou '' si la valeur n'existait pas) pour le restaurer.
    #>
    param([string]$Approuve, [string]$Valeur, [bool]$Actif, [string]$Avant = $null)
    $precedent = ''
    $lu = Get-ValeurRegistre $Approuve $Valeur
    if ($null -ne $lu) { $precedent = [Convert]::ToBase64String([byte[]]$lu) }
    # -ErrorAction Stop partout : un refus du registre est une erreur, pas un
    # detail. Sans cela l'ecriture echouait en affichant son message, et
    # l'appelant notait quand meme le changement comme fait -- l'agent aurait
    # cru avoir desactive ce qui demarrait toujours.
    if (-not $Actif) {
        if (-not (Test-Path -LiteralPath $Approuve)) {
            New-Item -Path $Approuve -Force -ErrorAction Stop | Out-Null
        }
        $octets = [byte[]](3, 0, 0, 0) + [BitConverter]::GetBytes([DateTime]::Now.ToFileTime())
        Set-ItemProperty -LiteralPath $Approuve -Name $Valeur -Value $octets -Type Binary -ErrorAction Stop
    } elseif ($Avant) {
        Set-ItemProperty -LiteralPath $Approuve -Name $Valeur -Value ([Convert]::FromBase64String($Avant)) -Type Binary -ErrorAction Stop
    } else {
        Remove-ItemProperty -LiteralPath $Approuve -Name $Valeur -ErrorAction SilentlyContinue
    }
    return $precedent
}

function Sync-Processus {
    <#
        Tient les consignes de Home Assistant, et defait ce qu'il ne demande
        plus. Chaque changement est note avant d'etre fait : un agent arrete
        au milieu sait encore quoi restaurer.
    #>
    param($Consignes)
    if ($null -eq $Consignes) { return }
    $voulus = @{
        demarrage = @(Get-PropOrDefault $Consignes 'demarrage' @())
        taches    = @(Get-PropOrDefault $Consignes 'taches' @())
        services  = @(Get-PropOrDefault $Consignes 'services' @())
    }
    $j = Read-JournalProcessus
    $change = $false

    # --- Demarrage
    $emplacements = @(Get-EmplacementsDemarrage)
    foreach ($id in $voulus.demarrage) {
        if ($j.demarrage.ContainsKey($id)) { continue }
        $ici = @($emplacements | Where-Object { $_.Id -eq $id })
        if ($ici.Count -eq 0) { continue }
        $traces = @()
        foreach ($e in $ici) {
            $chemin = Get-CheminCommande $e.Commande
            $sig = Get-SignatureFichier $chemin
            $exe = [IO.Path]::GetFileNameWithoutExtension($chemin).ToLowerInvariant()
            if (Test-Protege $chemin $sig.signataire $exe 'demarrage') { continue }
            try {
                $avant = Set-DemarrageActif $e.Approuve $e.Valeur $false
                $traces += @{ approuve = $e.Approuve; valeur = $e.Valeur; avant = $avant }
            } catch { Write-Journal "Demarrage $id non desactive : $_" 'WARN' }
        }
        if ($traces.Count -gt 0) {
            $j.demarrage[$id] = $traces
            $change = $true
            Save-JournalProcessus $j
            Write-Journal "Retire du demarrage : $id"
        }
    }
    foreach ($id in @($j.demarrage.Keys)) {
        if ($voulus.demarrage -contains $id) { continue }
        foreach ($t in @($j.demarrage[$id])) {
            try { [void](Set-DemarrageActif ([string]$t.approuve) ([string]$t.valeur) $true ([string]$t.avant)) }
            catch { Write-Journal "Demarrage $id non restaure : $_" 'WARN' }
        }
        $j.demarrage.Remove($id)
        $change = $true
        Write-Journal "Rendu au demarrage : $id"
    }

    # --- Taches planifiees
    foreach ($id in $voulus.taches) {
        if ($j.taches.ContainsKey($id)) { continue }
        $pos = $id.LastIndexOf('\')
        $t = Get-ScheduledTask -TaskPath $id.Substring(0, $pos + 1) -TaskName $id.Substring($pos + 1) -ErrorAction SilentlyContinue
        if ($null -eq $t -or $t.TaskPath -like '\Microsoft\*') { continue }
        $etaitActive = ([string]$t.State -ne 'Disabled')
        try {
            if ($etaitActive) { $t | Disable-ScheduledTask -ErrorAction Stop | Out-Null }
            $j.taches[$id] = $etaitActive
            $change = $true
            Save-JournalProcessus $j
            Write-Journal "Tache desactivee : $id"
        } catch { Write-Journal "Tache $id non desactivee : $_" 'WARN' }
    }
    foreach ($id in @($j.taches.Keys)) {
        if ($voulus.taches -contains $id) { continue }
        if ([bool]$j.taches[$id]) {
            $pos = $id.LastIndexOf('\')
            try {
                Enable-ScheduledTask -TaskPath $id.Substring(0, $pos + 1) -TaskName $id.Substring($pos + 1) -ErrorAction Stop | Out-Null
            } catch { Write-Journal "Tache $id non reactivee : $_" 'WARN' }
        }
        $j.taches.Remove($id)
        $change = $true
        Write-Journal "Tache rendue : $id"
    }

    # --- Services : passes en manuel, jamais desactives
    foreach ($id in $voulus.services) {
        if ($j.services.ContainsKey($id)) { continue }
        $s = Get-CimInstance Win32_Service -Filter ("Name='{0}'" -f ($id -replace "'", "''")) -ErrorAction SilentlyContinue
        if ($null -eq $s) { continue }
        $chemin = Get-CheminCommande ([string]$s.PathName)
        $sig = Get-SignatureFichier $chemin
        $exe = [IO.Path]::GetFileNameWithoutExtension($chemin).ToLowerInvariant()
        if (Test-Protege $chemin $sig.signataire $exe 'service') { continue }
        $avant = switch ([string]$s.StartMode) {
            'Auto'     { if ($s.PSObject.Properties['DelayedAutoStart'] -and $s.DelayedAutoStart) { 'delayed-auto' } else { 'auto' } }
            'Manual'   { 'demand' }
            'Disabled' { 'disabled' }
            default    { 'demand' }
        }
        if ($avant -in 'demand', 'disabled') { $j.services[$id] = $avant; $change = $true; Save-JournalProcessus $j; continue }
        & sc.exe config "$id" start= demand | Out-Null
        if ($LASTEXITCODE -eq 0) {
            $j.services[$id] = $avant
            $change = $true
            Save-JournalProcessus $j
            Write-Journal "Service en manuel : $id (etait $avant)"
        } else { Write-Journal "Service $id non modifie (sc.exe $LASTEXITCODE)" 'WARN' }
    }
    foreach ($id in @($j.services.Keys)) {
        if ($voulus.services -contains $id) { continue }
        $avant = [string]$j.services[$id]
        if ($avant -in 'auto', 'delayed-auto') {
            & sc.exe config "$id" start= $avant | Out-Null
            if ($LASTEXITCODE -ne 0) { Write-Journal "Service $id non restaure (sc.exe $LASTEXITCODE)" 'WARN' }
        }
        $j.services.Remove($id)
        $change = $true
        Write-Journal "Service rendu : $id ($avant)"
    }

    if ($change) { Save-JournalProcessus $j }
}

function Stop-Nuisibles {
    <#
        Arretes a chaque tour, comme un logiciel interdit, mais sans message :
        ce n'est pas une punition, c'est de l'entretien.
    #>
    param($Consignes)
    if ($null -eq $Consignes) { return }
    foreach ($nom in @(Get-PropOrDefault $Consignes 'arreter' @())) {
        $n = ([string]$nom).ToLowerInvariant()
        if (-not $n -or $script:ProcessusProteges -contains $n) { continue }
        foreach ($p in @(Get-Process -Name $n -ErrorAction SilentlyContinue)) {
            if ($p.Path -and (Test-Protege ([string]$p.Path) '' $n 'processus')) { continue }
            try { Stop-Process -Id $p.Id -Force -ErrorAction Stop; Write-Journal "Processus nuisible arrete : $n ($($p.Id))" }
            catch { }
        }
    }
}

# -------------------------------------------------------- Desinstaller
#
# Le quatrieme verdict. Tout ce qui precede se defait ; ceci, non. D'ou
# les verrous : Home Assistant n'envoie la consigne que si la maison l'a
# permis et qu'un adulte a confirme, logiciel par logiciel ; et l'agent
# refuse de son cote Windows, les pilotes, l'anti-triche et lui-meme.
#
# L'agent ne supprime aucun fichier lui-meme : il lance le desinstalleur
# que le logiciel a declare a Windows, en mode silencieux, et seulement
# quand ce mode est connu (MSI, Inno Setup, NSIS, ou une commande
# silencieuse declaree). Sinon il repond « a retirer a la main ».
#
# Le desinstalleur tourne a part : l'agent continue ses tours, et
# regarde a chaque passage si le logiciel a disparu de la liste de
# Windows. C'est cela, et non le code de sortie, qui dit « fait » : la
# plupart des desinstalleurs se recopient ailleurs et rendent la main
# tout de suite.

$script:DesinstallJournal       = Join-Path $script:Racine 'desinstall.json'
$script:DesinstallMaxSecondes   = 900
$script:DesinstallGraceSecondes = 180
$script:DesinstallOubliJours    = 30
$script:ClesDesinstall = @(
    'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall',
    'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall'
)
$script:EditeursPilotes = @(
    'nvidia', 'intel', 'advanced micro devices', 'amd ', 'realtek', 'qualcomm',
    'mediatek', 'synaptics', 'elan ', 'broadcom', 'marvell', 'asmedia',
    'killer networking', 'rivet networks'
)
$script:MotsAntiTriche = @(
    'vanguard', 'easyanticheat', 'easy anti-cheat', 'battleye', 'faceit',
    'anticheat', 'anti-cheat', 'punkbuster', 'gameguard', 'xigncode', 'esea'
)

function Get-MaintenantUnix {
    return [int64]([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())
}

function Read-JournalDesinstall {
    <# jeton -> fiche (table). Vide si le fichier manque ou est abime. #>
    $j = @{}
    if (-not (Test-Path -LiteralPath $script:DesinstallJournal)) { return $j }
    try {
        $lu = Get-Content -LiteralPath $script:DesinstallJournal -Raw -Encoding UTF8 | ConvertFrom-Json
        if ($null -eq $lu) { return $j }
        foreach ($p in $lu.PSObject.Properties) {
            $fiche = @{}
            foreach ($q in $p.Value.PSObject.Properties) { $fiche[$q.Name] = $q.Value }
            $j[$p.Name] = $fiche
        }
    } catch { }
    return $j
}

function Save-JournalDesinstall {
    param([hashtable]$Journal)
    $Journal | ConvertTo-Json -Depth 5 -Compress |
        Set-Content -LiteralPath $script:DesinstallJournal -Encoding UTF8
}

function Get-ProduitsInstalles {
    <# Ce que Windows liste dans « Applications installees », pour toute la machine. #>
    $produits = @()
    foreach ($racine in $script:ClesDesinstall) {
        if (-not (Test-Path -LiteralPath $racine)) { continue }
        foreach ($k in @(Get-ChildItem -LiteralPath $racine -ErrorAction SilentlyContinue)) {
            try {
                $nom = [string]$k.GetValue('DisplayName', '')
                if (-not $nom) { continue }
                $produits += @{
                    cle        = [string]$k.PSPath
                    code       = [string]$k.PSChildName
                    nom        = $nom
                    editeur    = [string]$k.GetValue('Publisher', '')
                    dossier    = [string]$k.GetValue('InstallLocation', '')
                    commande   = [string]$k.GetValue('UninstallString', '')
                    silencieux = [string]$k.GetValue('QuietUninstallString', '')
                    version    = [string]$k.GetValue('DisplayVersion', '')
                    icone      = [string]$k.GetValue('DisplayIcon', '')
                    composant  = ([string]$k.GetValue('SystemComponent', '0') -eq '1')
                    msi        = ([string]$k.GetValue('WindowsInstaller', '0') -eq '1')
                }
            } catch { }
        }
    }
    return $produits
}

function Get-DossierSur {
    <#
        Un dossier d'installation exploitable : absolu, propre a un logiciel.
        Rend le chemin en minuscules termine par « \ », ou '' si le dossier
        est trop large pour designer un seul logiciel (C:\, Program Files,
        Windows, ProgramData, Users...). Sans ce garde-fou, un logiciel
        mal declare « installe dans C:\Program Files » passerait pour le
        proprietaire de tout ce qui s'y trouve.
    #>
    param([string]$Dossier)
    if (-not $Dossier) { return '' }
    $d = [Environment]::ExpandEnvironmentVariables($Dossier.Trim().Trim('"')).Replace('/', '\').TrimEnd('\').ToLowerInvariant()
    if ($d -notmatch '^[a-z]:\\') { return '' }
    $morceaux = @($d.Split('\') | Where-Object { $_ })
    # D:\Roblox vaut ; C:\Program Files seul, non ; et sous Users il faut
    # etre descendu dans le profil (C:\Users\nom\AppData\Local\Truc).
    if ($morceaux.Count -lt 2) { return '' }
    $communs = @('program files', 'program files (x86)', 'programdata', 'windows', 'users', 'utilisateurs')
    if ($morceaux.Count -lt 3 -and $communs -contains $morceaux[1]) { return '' }
    if (@('users', 'utilisateurs') -contains $morceaux[1]) {
        if ($morceaux.Count -lt 5) { return '' }
        $dans = ($morceaux[3..($morceaux.Count - 1)] -join '\')
        if (@('appdata\local', 'appdata\roaming', 'appdata\locallow', 'appdata\local\programs', 'appdata\local\temp') -contains $dans) { return '' }
    }
    $larges = @()
    foreach ($v in 'ProgramFiles', 'ProgramFiles(x86)', 'ProgramW6432', 'ProgramData', 'windir', 'CommonProgramFiles', 'CommonProgramFiles(x86)', 'PUBLIC') {
        $val = [Environment]::GetEnvironmentVariable($v)
        if ($val) { $larges += $val.TrimEnd('\').ToLowerInvariant() }
    }
    if ($larges -contains $d) { return '' }
    if ($d.StartsWith(($env:windir).TrimEnd('\').ToLowerInvariant() + '\')) { return '' }
    return $d + '\'
}

function Find-ProduitPour {
    <#
        Le logiciel installe auquel appartient ce fichier. On prend le
        dossier d'installation le plus precis qui contient le fichier ; a
        defaut, le dossier du desinstalleur ou de l'icone. $null si rien
        ne correspond sans ambiguite.
    #>
    param([string]$Chemin, $Produits)
    if (-not $Chemin) { return $null }
    $c = $Chemin.Replace('/', '\').ToLowerInvariant()
    $meilleur = $null
    $longueur = 0
    $rivaux = 0
    foreach ($p in @($Produits)) {
        $dossiers = @()
        $d = Get-DossierSur $p.dossier
        if ($d) { $dossiers += $d }
        else {
            foreach ($source in @($p.commande, $p.icone)) {
                if (-not $source) { continue }
                $exe = Get-CheminCommande (([string]$source) -replace ',\s*-?\d+\s*$', '')
                if (-not $exe -or $exe -match 'msiexec') { continue }
                $parent = ''
                try { $parent = [System.IO.Path]::GetDirectoryName($exe) } catch { }
                $d2 = Get-DossierSur $parent
                if ($d2) { $dossiers += $d2 }
            }
        }
        foreach ($d in $dossiers) {
            if ($c.StartsWith($d) -and $d.Length -gt $longueur) {
                $meilleur = $p
                $longueur = $d.Length
                $rivaux = 0
            } elseif ($c.StartsWith($d) -and $d.Length -eq $longueur -and $null -ne $meilleur -and $p.cle -ne $meilleur.cle) {
                if ($meilleur.composant -and -not $p.composant) {
                    # A dossier egal, le produit visible plutot que son composant cache.
                    $meilleur = $p
                    $rivaux = 0
                } elseif (-not $p.composant) {
                    # Deux logiciels declarent le meme dossier (« C:\Program Files\Adobe » pour toute la
                    # suite) : impossible de savoir auquel appartient ce fichier, et se tromper desinstallerait
                    # le mauvais. On ne choisit pas.
                    $rivaux++
                }
            }
        }
    }
    if ($rivaux -gt 0) { return $null }
    return $meilleur
}

function Get-RaisonIntouchable {
    <# Pourquoi l'agent refuse de desinstaller ce logiciel -- '' s'il veut bien. #>
    param($Produit, [string]$Chemin, [string]$Exe)
    $nom = ([string]$Produit.nom).ToLowerInvariant()
    $editeur = ([string]$Produit.editeur).ToLowerInvariant() + ' '
    $dossier = ([string]$Produit.dossier).ToLowerInvariant()
    $sig = Get-SignatureFichier -Chemin $Chemin
    if (Test-Protege $Chemin ([string]$sig.signataire) $Exe 'processus') { return (T 'desinstall.raison_windows') }
    if ($Produit.composant) { return (T 'desinstall.raison_composant') }
    if ($editeur.StartsWith('microsoft') -or $nom.StartsWith('microsoft ') -or ([string]$sig.signataire).ToLowerInvariant().StartsWith('microsoft')) { return (T 'desinstall.raison_microsoft') }
    if ($nom -match 'haparental|ha parental|^vision( |$)' -or $dossier -match '\\haparental') { return (T 'desinstall.raison_agent') }
    $ici = ($script:Racine).ToLowerInvariant()
    if ($Chemin.ToLowerInvariant().StartsWith($ici)) { return (T 'desinstall.raison_agent') }
    foreach ($mot in $script:MotsAntiTriche) {
        if ($nom.Contains($mot) -or $dossier.Contains($mot) -or $Chemin.ToLowerInvariant().Contains($mot)) { return (T 'desinstall.raison_antitriche') }
    }
    foreach ($e in $script:EditeursPilotes) {
        if ($editeur.Contains($e)) { return (T 'desinstall.raison_fabricant') }
    }
    if ($nom -match '\b(drivers?|pilotes?|chipset|firmware|bios)\b') { return (T 'desinstall.raison_pilote') }
    return ''
}

function Test-Nsis {
    <# Un desinstalleur NSIS porte la marque « Nullsoft » dans son en-tete. #>
    param([string]$Fichier)
    try {
        $flux = [System.IO.File]::Open($Fichier, 'Open', 'Read', 'ReadWrite')
        try {
            $taille = [int][Math]::Min($flux.Length, 1048576)
            $tampon = New-Object byte[] $taille
            [void]$flux.Read($tampon, 0, $taille)
        } finally { $flux.Dispose() }
        return ([System.Text.Encoding]::ASCII.GetString($tampon)).Contains('Nullsoft')
    } catch { return $false }
}

function Get-CommandeDesinstallation {
    <#
        La commande silencieuse, ou $null si l'on n'en connait pas. Jamais
        de devinette : un desinstalleur inconnu lance sans option ouvrirait
        une fenetre que personne ne voit, dans la session du systeme.
    #>
    param($Produit)
    $systeme = Join-Path $env:windir 'System32'
    $msiexec = Join-Path $systeme 'msiexec.exe'
    $guid = ''
    if ($Produit.code -match '^\{[0-9A-Fa-f\-]{36}\}$') { $guid = $Produit.code }
    $brut = [string]$Produit.commande
    if ($Produit.silencieux) {
        $s = [Environment]::ExpandEnvironmentVariables(([string]$Produit.silencieux).Trim())
        $f = Get-CheminCommande $s
        if ($f -and (Test-Path -LiteralPath $f -PathType Leaf)) {
            $reste = ''
            $i = $s.IndexOf($f, [StringComparison]::OrdinalIgnoreCase)
            if ($i -ge 0) { $reste = $s.Substring($i + $f.Length).TrimStart('"').Trim() }
            return @{ fichier = $f; arguments = $reste; mode = 'declare' }
        }
    }
    if ($Produit.msi -or $brut -match 'msiexec') {
        if (-not $guid) {
            $m = [regex]::Match($brut, '\{[0-9A-Fa-f\-]{36}\}')
            if ($m.Success) { $guid = $m.Value }
        }
        if ($guid) { return @{ fichier = $msiexec; arguments = "/x $guid /qn /norestart"; mode = 'msi' } }
        return $null
    }
    $f = Get-CheminCommande $brut
    if (-not $f -or -not (Test-Path -LiteralPath $f -PathType Leaf)) { return $null }
    $nom = [System.IO.Path]::GetFileName($f).ToLowerInvariant()
    if ($nom -match '^unins\d{3}\.exe$') {
        return @{ fichier = $f; arguments = '/VERYSILENT /SUPPRESSMSGBOXES /NORESTART'; mode = 'inno' }
    }
    if (Test-Nsis $f) {
        return @{ fichier = $f; arguments = '/S'; mode = 'nsis' }
    }
    return $null
}

function Start-Desinstallation {
    <# Lance le desinstalleur pour une consigne. Rend la fiche a journaliser. #>
    param($Consigne)
    $fiche = @{
        cle     = [string](Get-PropOrDefault $Consigne 'cle' '')
        jeton   = [string](Get-PropOrDefault $Consigne 'jeton' '')
        etat    = 'impossible'
        detail  = ''
        produit = ''
        registre = ''
        processus = 0
        debut   = (Get-MaintenantUnix)
        date    = (Get-MaintenantUnix)
        sorti   = 0
    }
    $chemin = [string](Get-PropOrDefault $Consigne 'chemin' '')
    $exe = [string](Get-PropOrDefault $Consigne 'exe' '')
    $genre = [string](Get-PropOrDefault $Consigne 'genre' '')
    try {
        $produit = $null
        if ($genre -eq 'logiciel') {
            # Désigné dans la liste des logiciels installés : on le retrouve par son nom exact, pas par un fichier.
            $voulu = ([string](Get-PropOrDefault $Consigne 'id' '')).Trim().ToLowerInvariant()
            foreach ($p in @(Get-ProduitsInstalles)) {
                if (-not $p.composant -and ([string]$p.nom).Trim().ToLowerInvariant() -eq $voulu) { $produit = $p; break }
            }
            $chemin = ''
            if ($null -ne $produit -and $produit.dossier) { $chemin = [string]$produit.dossier }
        } else {
            if (-not $chemin) { $fiche.detail = (T 'desinstall.emplacement_inconnu'); return $fiche }
            $produit = Find-ProduitPour -Chemin $chemin -Produits (Get-ProduitsInstalles)
        }
        if ($null -eq $produit) {
            $fiche.detail = (T 'desinstall.aucun_logiciel')
            return $fiche
        }
        $fiche.produit = [string]$produit.nom
        $fiche.registre = [string]$produit.cle
        $raison = Get-RaisonIntouchable -Produit $produit -Chemin $chemin -Exe $exe
        if ($raison) { $fiche.detail = (T 'desinstall.intouchable' @{ nom = [string]$produit.nom; raison = $raison }); return $fiche }
        $commande = Get-CommandeDesinstallation $produit
        if ($null -eq $commande) {
            $fiche.detail = (T 'desinstall.pas_silencieuse' @{ nom = [string]$produit.nom })
            return $fiche
        }
        Write-Journal "Desinstallation de « $($produit.nom) » ($($commande.mode)) : $($commande.fichier) $($commande.arguments)"
        $lancement = @{ FilePath = $commande.fichier; PassThru = $true; WindowStyle = 'Hidden'; ErrorAction = 'Stop' }
        if ($commande.arguments) { $lancement.ArgumentList = $commande.arguments }
        $p = Start-Process @lancement
        $fiche.processus = [int]$p.Id
        $fiche.etat = 'en_cours'
        $fiche.detail = (T 'desinstall.lance' @{ mode = [string]$commande.mode })
    } catch {
        $fiche.etat = 'echec'
        $fiche.detail = (T 'desinstall.lancement_impossible' @{ erreur = [string]$_.Exception.Message })
        Write-Journal "Desinstallation impossible : $_" 'WARN'
    }
    return $fiche
}

function Update-Desinstallation {
    <# Ou en est une desinstallation lancee ? Modifie la fiche ; rend $true si elle a change. #>
    param([hashtable]$Fiche)
    if ($Fiche.etat -ne 'en_cours') { return $false }
    $maintenant = Get-MaintenantUnix
    $parti = -not ($Fiche.registre -and (Test-Path -LiteralPath ([string]$Fiche.registre)))
    if ($parti) {
        $Fiche.etat = 'fait'
        $Fiche.detail = ''
        $Fiche.date = $maintenant
        Write-Journal "Desinstallation terminee : $($Fiche.produit)"
        # Le prochain inventaire doit le montrer disparu sans attendre.
        $script:DernierInventaire = [datetime]::MinValue
        return $true
    }
    $vivant = $false
    if ([int]$Fiche.processus -gt 0) {
        $vivant = $null -ne (Get-Process -Id ([int]$Fiche.processus) -ErrorAction SilentlyContinue)
    }
    if (-not $vivant -and [int64]$Fiche.sorti -eq 0) {
        # Sorti, mais peut-etre relaye par une copie : on laisse un delai.
        $Fiche.sorti = $maintenant
        return $true
    }
    $trop_long = ($maintenant - [int64]$Fiche.debut) -ge $script:DesinstallMaxSecondes
    $abandonne = (-not $vivant) -and ($maintenant - [int64]$Fiche.sorti) -ge $script:DesinstallGraceSecondes
    if ($trop_long -or $abandonne) {
        if ($vivant) {
            try { Stop-Process -Id ([int]$Fiche.processus) -Force -ErrorAction Stop } catch { }
        }
        $Fiche.etat = 'echec'
        $Fiche.detail = if ($trop_long) { (T 'desinstall.trop_long') }
                        else { (T 'desinstall.abandonne') }
        $Fiche.date = $maintenant
        Write-Journal "Desinstallation en echec : $($Fiche.produit) -- $($Fiche.detail)" 'WARN'
        return $true
    }
    return $false
}

function Invoke-Desinstallations {
    <#
        A chaque tour : suit ce qui est lance, puis prend la consigne
        suivante. Une seule desinstallation a la fois -- deux installeurs
        Windows en meme temps se bloquent l'un l'autre.
    #>
    param($Consignes)
    $demandes = @(Get-PropOrDefault $Consignes 'desinstaller' @())
    $j = Read-JournalDesinstall
    if ($demandes.Count -eq 0 -and $j.Count -eq 0) { return }
    $change = $false
    $voulus = @{}
    foreach ($d in $demandes) {
        $jeton = [string](Get-PropOrDefault $d 'jeton' '')
        if ($jeton) { $voulus[$jeton] = $d }
    }
    $maintenant = Get-MaintenantUnix
    $en_cours = $false
    foreach ($jeton in @($j.Keys)) {
        $fiche = $j[$jeton]
        if (Update-Desinstallation $fiche) { $change = $true }
        if ($fiche.etat -eq 'en_cours') { $en_cours = $true; continue }
        # Garde un mois : c'est ce qui empeche de relancer une consigne
        # deja traitee si Home Assistant la renvoie.
        if (-not $voulus.ContainsKey($jeton) -and ($maintenant - [int64]$fiche.date) -gt ($script:DesinstallOubliJours * 86400)) {
            $j.Remove($jeton); $change = $true
        }
    }
    if (-not $en_cours) {
        foreach ($jeton in @($voulus.Keys | Sort-Object)) {
            if ($j.ContainsKey($jeton)) { continue }
            $fiche = Start-Desinstallation $voulus[$jeton]
            $j[$jeton] = $fiche
            $change = $true
            if ($fiche.etat -eq 'en_cours') { break }
        }
    }
    if ($change) { Save-JournalDesinstall $j }
}

function Get-ComptesRendusDesinstall {
    <#
        Les comptes rendus des deux derniers jours. Repetes a chaque tour :
        c'est court, Home Assistant ignore ce qu'il sait deja, et un compte
        rendu perdu en route finit ainsi toujours par arriver.
    #>
    $sortie = @()
    if (-not (Test-Path -LiteralPath $script:DesinstallJournal)) { return $sortie }
    $j = Read-JournalDesinstall
    $limite = (Get-MaintenantUnix) - 172800
    foreach ($jeton in @($j.Keys)) {
        $f = $j[$jeton]
        if ($f.etat -eq 'en_cours' -or [int64]$f.date -lt $limite) { continue }
        $sortie += @{
            cle = [string]$f.cle; jeton = [string]$jeton; etat = [string]$f.etat
            detail = [string]$f.detail; produit = [string]$f.produit; date = [int64]$f.date
        }
    }
    return $sortie
}

function Invoke-TourProcessus {
    <#
        Appele a chaque tour apres la decision de Home Assistant. Les
        nuisibles a chaque fois ; le reste seulement quand les consignes
        changent, ou toutes les dix minutes pour reprendre ce qu'on aurait
        reactive a la main.
    #>
    param($Decision)
    if (-not (Module-Actif 'optimisations')) {
        # Module « Optimisations PC » coupé : plus rien de nouveau (ni nuisible arrêté, ni démarrage,
        # tâche ou service touché, ni désinstallation lancée). Ce qui avait été changé est rendu par
        # le mécanisme qui existe déjà (processus.json garde l'état d'origine) ; Home Assistant garde
        # les consignes, qui reviennent telles quelles à la réactivation. Une désinstallation déjà
        # lancée ne se défait pas : on suit seulement sa fin pour le compte rendu.
        try { Invoke-Desinstallations ([pscustomobject]@{}) }
        catch { Write-Journal "Désinstallation : $_" 'WARN' }
        $j = Read-JournalProcessus
        if (($j.demarrage.Count + $j.taches.Count + $j.services.Count) -gt 0) {
            Write-Journal 'Optimisations PC coupées : démarrage, tâches et services rendus.'
            Sync-Processus ([pscustomobject]@{ demarrage = @(); taches = @(); services = @() })
        }
        $script:DernieresConsignes = ''
        return
    }
    $consignes = Get-PropOrDefault $Decision 'processus' $null
    if ($null -eq $consignes) { return }
    Stop-Nuisibles $consignes
    try { Invoke-Desinstallations $consignes }
    catch { Write-Journal "Désinstallation : $_" 'WARN' }
    $empreinte = $consignes | ConvertTo-Json -Depth 4 -Compress
    $du = ((Get-Date) - $script:DerniereSyncProcessus).TotalSeconds
    if ($empreinte -ne $script:DernieresConsignes -or $du -ge $script:InventaireSecondes) {
        Sync-Processus $consignes
        $script:DernieresConsignes = $empreinte
        $script:DerniereSyncProcessus = Get-Date
    }
}


function Start-Agent {
    Write-Journal "Agent $($script:Version) démarré."
    # 1.93.2 (perte d'images par seconde chez un Demandeur) : l'agent passe après les jeux et les applis.
    try { [Diagnostics.Process]::GetCurrentProcess().PriorityClass = [Diagnostics.ProcessPriorityClass]::BelowNormal }
    catch { Write-Journal "Priorité basse impossible : $_" 'WARN' }
    $config = Read-Config

    if (-not (Get-PropOrDefault $config 'id' '')) {
        while ($true) {
            try { $config = Register-Agent -Config $config; break }
            catch {
                Write-Journal "Inscription impossible : $_" 'WARN'
                Start-Sleep -Seconds 60
                $config = Read-Config
            }
        }
    }

    # Les modules et le filtre DNS du dernier tour, avant même d'avoir parlé à Home Assistant.
    $garde = Read-Etat
    if ($null -ne $garde) {
        Read-Modules -Decision $garde
        $script:DnsAncien = [string](Get-PropOrDefault $garde 'dns_pose' '')
    }
    Sync-DnsChiffre -Actif (Module-Actif 'filtrage')
    # La liste noire du dernier tour sert tout de suite, avant meme d'avoir
    # parle a Home Assistant : une machine qui redemarre hors ligne reste
    # protegee.
    Read-ListeNoire

    $grace          = [int](Get-PropOrDefault $config 'offlineGraceMinutes' 120)
    $attente        = [int](Get-PropOrDefault $config 'poll' 20)
    $dernierContact = Get-Date
    $avertiPour     = ''
    $renie          = $false

    Start-VeilleApps

    while ($true) {
        # La sonde est un confort : une erreur chez elle ne doit jamais
        # arreter l'agent, donc le controle parental.
        try { Start-Sonde } catch { Write-Journal "Sonde : $_" 'WARN' }
        try { Start-Tray } catch { Write-Journal "Icône : $_" 'WARN' }
        $script:ConfigCourante = $config
        try { Send-Demandes -Config $config } catch { Write-Journal "Demandes : $_" 'WARN' }
        try { Send-Actions -Config $config } catch { Write-Journal "Actions : $_" 'WARN' }
        try { Send-Codes -Config $config } catch { Write-Journal "Codes : $_" 'WARN' }
        try { Send-Accuses -Config $config } catch { Write-Journal "Accusés : $_" 'WARN' }

        $decision = $null
        $horsLigne = $false
        try {
            $decision = Get-Decision -Config $config
            $dernierContact = Get-Date
            if ($renie) {
                Write-Journal "Ce PC est de nouveau reconnu par Home Assistant."
                $renie = $false
            }
            if ($decision.PSObject.Properties['poll']) { $attente = [int]$decision.poll }
            Read-Modules -Decision $decision
            Save-Etat -Decision $decision
            Read-Raisons -Decision $decision
            Write-Fermes -Decision $decision
            $script:EstParent = [bool](Get-PropOrDefault $decision 'parent' $false)
            $script:Lieu = [string](Get-PropOrDefault $decision 'lieu' $script:Lieu)
            $script:Soutien = [bool](Get-PropOrDefault (Get-PropOrDefault $decision 'soutien' $null) 'actif' $false)
            try { Sync-Maison -Config $config } catch { }
            Write-EtatSonde -Decision $decision
            if ($decision.PSObject.Properties['liberer'] -and $decision.liberer) {
                try { Set-MessagesLiberes -Ids @($decision.liberer) } catch { Write-Journal "Libération : $_" 'WARN' }
            }
            if ($decision.PSObject.Properties['notify'] -and $decision.notify) {
                $duree = [int](Get-PropOrDefault $decision 'message_seconds' 30)
                # Les messages riches (1.67.0) vont à la fenêtre de Vision ; les autres, comme avant.
                # Module Messages coupé : les riches reçus quand même restent dans la file de la fenêtre, qui ne
                # les montre pas tant qu'il est coupé ; les simples ne s'affichent pas.
                $riches = @(@($decision.notify) | Where-Object { $_.PSObject.Properties['uid'] -and $_.uid })
                if ($riches.Count) { try { Save-MessagesRiches -Messages $riches -Config $config } catch { Write-Journal "Messages : $_" 'WARN' } }
                $simples = @()
                if (Module-Actif 'messages') { $simples = @(@($decision.notify) | Where-Object { -not ($_.PSObject.Properties['uid'] -and $_.uid) }) }
                foreach ($mot in $simples) {
                    $urgent = $false
                    if ($mot.PSObject.Properties['urgent']) {
                        $urgent = [bool]$mot.urgent
                    }
                    $son = 'Notification.Default'
                    if ($mot.PSObject.Properties['sound']) {
                        $son = [string]$mot.sound
                    }
                    $repete = $false
                    if ($mot.PSObject.Properties['loop']) {
                        $repete = [bool]$mot.loop
                    }
                    [void](Send-Message -Texte ([string]$mot.text) `
                        -Titre ([string]$mot.title) -Secondes $duree `
                        -Urgent $urgent -Son $son -Repete $repete)
                }
            }
            if ($decision.action -eq 'uninstall') {
                Invoke-AutoDesinstallation -Config $config
                return
            }
            if ($decision.PSObject.Properties['update'] -and $decision.update) {
                Invoke-MiseAJour -Config $config -Maj $decision.update
            }
        } catch {
            if ((Get-CodeHttp $_) -eq 401) {
                # Le PC a été supprimé côté Home Assistant. On ne se
                # réinscrit pas de force : supprimé veut dire supprimé.
                # Le PC reste utilisable ; relancer install.ps1 pour le
                # remettre sous contrôle, uninstall.ps1 pour en finir.
                if (-not $renie) {
                    Write-Journal ("Ce PC n'est plus connu de Home Assistant " +
                        "(supprimé du tableau de bord). Aucune restriction " +
                        "n'est appliquée. Relance install.ps1 pour le " +
                        "réinscrire, ou uninstall.ps1 pour retirer l'agent.") 'WARN'
                    $renie = $true
                    $script:AppsInterdites = @()
                    $script:HostsEtat = $null
                    Sync-Hosts -Sites @()
                }
                Start-Sleep -Seconds 300
                continue
            }
            Write-Journal "Home Assistant injoignable : $_" 'WARN'
            $minutes = ((Get-Date) - $dernierContact).TotalMinutes
            if ($minutes -le $grace) {
                $decision = Read-Etat
                $horsLigne = $true
            } else {
                Write-Journal "Hors ligne depuis $([int]$minutes) min : PC laissé ouvert." 'WARN'
                $script:AppsInterdites = @()
                $decision = $null
            }
        }

        if ($null -ne $decision) {
            # Hors ligne, la décision gardée porte ses modules : on les reprend avec elle.
            if ($horsLigne) { Read-Modules -Decision $decision }

            # Module « Blocage d'applis » coupé : plus aucun logiciel fermé (ni au relevé, ni au lancement).
            $script:AppsInterdites = @()
            if (Module-Actif 'blocage') { $script:AppsInterdites = @($decision.apps) }
            Stop-LogicielsInterdits -Noms $script:AppsInterdites

            # 1.91.0 (retour d'essai) : l'écran de veille des PC est retiré ; Vision.scr et les restes quittent le PC.
            try { Remove-EcranDeVeille } catch { Write-Journal "Ecran de veille de Windows : $_" 'WARN' }

            # Demarrage, taches, services, nuisibles : ce que la maison a
            # valide. Comme la sonde, jamais au prix de l'agent.
            try { Invoke-TourProcessus $decision }
            catch { Write-Journal "Processus : $_" 'WARN' }

            $filtrer = Module-Actif 'filtrage'
            $serveurs = @()
            if ($filtrer) {
                # La liste noire et le filtre DNS d'abord : ce qui est dangereux
                # ne se discute pas et ne doit pas attendre le tour suivant.
                Sync-ListeNoire -Config $config `
                    -Version ([string](Get-PropOrDefault $decision 'liste_noire' ''))
                $serveurs = @(Get-PropOrDefault $decision 'dns' @())
            }
            # Un filtre posé avant un redémarrage de l'agent, que Home Assistant ne veut plus (réponse en ligne
            # seulement : la décision gardée hors ligne ne porte pas le DNS) : on le retire quand même.
            if (-not $horsLigne) {
                if ($serveurs.Count -eq 0 -and -not $script:DnsPose -and $script:DnsAncien) { $script:DnsPose = $script:DnsAncien }
                $script:DnsAncien = ''
            }
            Set-DnsFiltrant -Serveurs $serveurs -Flush $false
            Sync-DnsChiffre -Actif $filtrer

            if ($filtrer) {
                # 1.93.2 (retour d'essai) : Home Assistant envoie la version de ce qui doit être bloqué (hosts_version). Le
                # fichier hosts n'est reconstruit que si elle change, si un sous-domaine d'un site bloqué vient
                # d'apparaître, si quelqu'un a touché au fichier, ou toutes les 10 minutes : sinon rien à faire.
                $script:DerniersBlocages = @($decision.sites)
                [void](Sync-HostsSiNecessaire -Version ([string](Get-PropOrDefault $decision 'hosts_version' '')) `
                           -Sites @($decision.sites) `
                           -Exceptions @(Get-PropOrDefault $decision 'exceptions' @()) `
                           -Vus $script:DernierVu `
                           -Dangereux $script:NoireHosts `
                           -SafeSearch ([bool](Get-PropOrDefault $decision 'safesearch' $false)) `
                           -Flush $false)
            } else {
                # Module « Filtrage web » coupé : le bloc entre les marqueurs quitte le fichier hosts (sites,
                # liste noire, SafeSearch). La liste noire gardée sur le disque reste pour la réactivation.
                $script:DerniersBlocages = @()
                $script:HostsEtat = $null
                Sync-Hosts -Sites @() -Flush $false
            }
            Invoke-FlushDnsGroupe

            # Aucune notification de blocage sur le PC : un site coupe reste
            # simplement injoignable. La tentative remonte a Home Assistant,
            # ou elle est datee et lisible par les parents -- c'est la que
            # l'information sert, pas sur l'ecran de celui qui essaie.

            $avertissement = [string](Get-PropOrDefault $decision 'message' '')
            $prevenir      = [int](Get-PropOrDefault $decision 'warn' 0)

            if ($decision.action -eq 'lock') {
                $avertiPour = ''
                # Boucle serrée : si la session est rouverte, on reverrouille
                # tout de suite, sans attendre le prochain appel réseau.
                $fin = (Get-Date).AddSeconds($attente)
                while ((Get-Date) -lt $fin) {
                    Lock-Poste | Out-Null
                    Start-Sleep -Seconds 2
                    Stop-LogicielsInterdits -Noms $script:AppsInterdites
                }
                continue
            }

            # Home Assistant previent avant de fermer, et envoie la phrase
            # toute faite : l'agent ne sait pas ce qui ferme ni pourquoi, il
            # l'affiche. Une fois, pas a chaque tour -- et il oublie ce qui
            # n'est plus annonce, sinon un avertissement vu hier ne se
            # redirait jamais aujourd'hui.
            # Module Messages coupé (« mots, préavis et notifications ») : aucun préavis affiché.
            $annonces = @(Get-PropOrDefault $decision 'avertissements' @())
            if (-not (Module-Actif 'messages')) { $annonces = @(); $prevenir = 0 }
            foreach ($annonce in $annonces) {
                $texte = [string]$annonce
                if ($texte -and ($script:AvertisDits -notcontains $texte)) {
                    [void](Send-Message -Texte $texte -Titre 'Vision' -Secondes 10)
                }
            }
            $script:AvertisDits = @($annonces | ForEach-Object { [string]$_ })

            # 1.85.0 : préavis 10 / 5 / 1 min avant une fermeture (module « Préavis ») ; il remplace
            # l'ancien avertissement unique quand Home Assistant l'envoie. Chaque seuil n'est dit qu'une fois.
            $pre = Get-PropOrDefault $decision 'preavis' $null
            if ($null -ne $pre -and (Module-Actif 'preavis')) {
                $prevenir = 0
                $finPre = [double](Get-PropOrDefault $pre 'fin' 0)
                $reste = ($finPre - [DateTimeOffset]::Now.ToUnixTimeSeconds()) / 60
                foreach ($seuil in @(Get-PropOrDefault $pre 'seuils' @(10, 5, 1))) {
                    $cle = '{0}|{1}' -f [int64]$finPre, [int]$seuil
                    if ($reste -le [int]$seuil -and $reste -gt 0 -and $script:PreavisDits -notcontains $cle) {
                        $heure = Format-Heure ([DateTimeOffset]::FromUnixTimeSeconds([int64]$finPre))
                        $motif = [string](Get-PropOrDefault $pre 'motif' '')
                        $quand = (Tn 'agent.dans_minutes' ([Math]::Max(1, [int]$seuil)))
                        $texte = $(if ($motif) { (T 'agent.preavis_motif' @{ quand = $quand; heure = $heure; motif = $motif.ToLower() }) } else { (T 'agent.preavis' @{ quand = $quand; heure = $heure }) })
                        Send-Avertissement -Texte $texte
                        $script:PreavisDits = @($script:PreavisDits) + $cle
                        break
                    }
                }
            }
            foreach ($app in @(Get-PropOrDefault $decision 'preavis_apps' @())) {
                if (-not (Module-Actif 'preavis')) { break }
                $resteApp = [int](Get-PropOrDefault $app 'reste_s' 0) / 60
                foreach ($seuil in @(10, 5, 1)) {
                    $cle = 'app|{0}|{1}|{2}' -f [string](Get-PropOrDefault $app 'nom' ''), (Get-Date -Format 'yyyy-MM-dd'), $seuil
                    if ($resteApp -le $seuil -and $resteApp -gt 0 -and $script:PreavisDits -notcontains $cle) {
                        [void](Send-Message -Texte (T 'agent.plus_que' @{ appli = [string](Get-PropOrDefault $app 'libelle' ''); reste = (Tn 'agent.minutes' ([Math]::Max(1, [int]$seuil))) }) -Titre 'Vision' -Secondes 8 -Son '')
                        $script:PreavisDits = @($script:PreavisDits) + $cle
                        break
                    }
                }
            }
            if (@($script:PreavisDits).Count -gt 200) { $script:PreavisDits = @($script:PreavisDits | Select-Object -Last 50) }

            if ($prevenir -gt 0 -and $avertissement -and $avertiPour -ne $avertissement) {
                Send-Avertissement -Texte (T 'agent.avertissement' @{ message = $avertissement })
                $avertiPour = $avertissement
            } elseif ($prevenir -eq 0) {
                $avertiPour = ''
            }
        }

        Wait-Tour -Secondes $attente
    }
}

try {
    if (-not (Test-Path $script:Racine)) {
        New-Item -ItemType Directory -Path $script:Racine -Force | Out-Null
    }
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Start-Agent
} catch {
    Write-Journal "Arrêt sur erreur : $_" 'ERREUR'
    exit 1
}
