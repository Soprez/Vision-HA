"""Constantes de l'intégration Contrôle parental PC."""

from __future__ import annotations

from datetime import timedelta

DOMAIN = "pc_parental"

VERSION = "2.5.12.8"

# Version du client PowerShell hébergé par cette intégration. Les agents
# installés sur les PC se mettent à jour tout seuls quand elle change.
CLIENT_VERSION = "1.93.6"
CLIENT_DIR = "client"

# Nom du produit. Le domaine technique reste « pc_parental » : le changer
# ferait perdre les PC, leurs plages et leurs entités.
PRODUIT = "Vision"
PRODUIT_AFFICHE = "Vision"
# L'identifiant de l'appli Android. Ne jamais le changer : une autre valeur serait une autre appli, et plus
# aucun appareil ne recevrait les mises à jour.
PAQUET = "org.soprez.vision"
PAQUETS_VISION = (PAQUET,)

# Un agent supprimé garde ce délai pour repasser chercher son congé.
RETRAIT_TTL = 90 * 24 * 3600

# --- Ce que Vision observe -------------------------------------------
# Jours d'historique de temps d'écran conservés par PC.
USAGE_JOURS = 14
# Au-delà, un écart entre deux relevés est un trou (PC éteint, réseau
# coupé) : on ne le compte pas comme du temps passé devant l'écran.
USAGE_ECART_MAX = 120
# Un logiciel ou un site plus revu depuis ce délai sort du catalogue.
CATALOGUE_TTL = 30 * 24 * 3600
# Garde-fous : un PC ne peut pas faire grossir le catalogue sans limite.
CATALOGUE_MAX_APPS = 200
CATALOGUE_MAX_SITES = 400
# Libellés des listes déroulantes alimentées par ce qui a été constaté.
AUCUN_CONSTAT = "Rien de constaté"
# Un élément inscrit à la main n'a pas de relevé : il n'a pas été constaté,
# il a été **prévu**. La colonne le dit plutôt que d'afficher un zéro.
MENTION_AJOUT = "ajouté"
# D'où vient une ligne : la machine l'a vue passer, ou quelqu'un l'a écrite.
# La distinction compte au moment de relire ses propres décisions — une règle
# sur un nom que personne n'a jamais vu est souvent une faute de frappe.
ORIGINE_CONSTATE = "constaté"
ORIGINE_INSCRIT = "inscrit à la main"
ORIGINE_JAMAIS = "jamais constaté"

# Portée d'une décision : cette machine, ou toute la maison. Au niveau de la
# maison il n'existe qu'un bannissement — ce qui n'est pas banni est
# autorisé, comme partout ailleurs dans Vision.
PORTEE_PC = "Cet appareil"
PORTEE_MAISON = "Tous les appareils"
# Ancien libellé (avant 1.81.0) : toujours compris s'il revient d'un état enregistré.
PORTEES_DECISION_ANCIENNES = {"Toute la maison": PORTEE_MAISON}
# Nommée à part : « PORTEES » désigne plus bas ce qu'une plage ferme, et
# deux listes du même nom dans un même module sont un piège — une
# réorganisation d'imports suffirait à proposer « Ce PC / Toute la maison »
# dans le sélecteur des plages.
PORTEES_DECISION = [PORTEE_PC, PORTEE_MAISON]

# Le travail réel n'est pas de consulter une liste, c'est de trancher ce qui
# est nouveau. Le filtre par défaut ne montre donc que ça.
FILTRE_A_CLASSER = "À classer"
FILTRE_BLOQUES = "Bloqués"
FILTRE_AUTORISES = "Autorisés"
FILTRE_TOUT = "Tout"
FILTRES = [FILTRE_A_CLASSER, FILTRE_BLOQUES, FILTRE_AUTORISES, FILTRE_TOUT]
# Ce qui n'est ni bloqué ni autorisé passe : on n'enferme pas par défaut.
AUTORISE = "autorise"
BLOQUE = "bloque"
# Délai minimal entre deux enregistrements des relevés d'observation.
ENREGISTREMENT_MIN = 300

# --- Ce qu'est un « site » ----------------------------------------------
# Un site, c'est un domaine : « youtube.com ». Ses sous-domaines
# (« m. », « www. », « r3---sn-xxx.googlevideo.com ») sont du bruit pour
# qui décide, et ils se comptent par centaines. On les regroupe donc sous
# leur domaine principal, et on ne les fait réapparaître que là où ils
# servent vraiment : pour écrire une exception.
#
# Reconnaître ce domaine principal demande de savoir que « co.uk » n'est
# pas un site. Plutôt qu'une liste de mille suffixes, ces quelques
# préfixes génériques suivis d'un pays couvrent ce qu'une maison rencontre.
SECONDS_GENERIQUES = {
    "co", "com", "net", "org", "gov", "edu", "ac", "asso", "gouv",
    "or", "ne", "tm", "nom", "pro", "sch", "police", "mod",
}
# Sous-domaines retenus par site : de quoi écrire une exception, pas de
# quoi refaire la liste qu'on vient justement de replier.
MAX_ADRESSES = 12
AUCUNE_ADRESSE = "Aucune adresse constatée"

# --- Exceptions ----------------------------------------------------------
# Deux façons d'ouvrir une porte dans un mur qu'on a soi-même monté :
#   * l'exception d'adresse, permanente, qui vise un sous-domaine précis ;
#   * le laissez-passer, minuté, qui rouvre un domaine entier et se referme
#     tout seul — la ressource pédagogique d'un soir, sans démonter la règle.
LAISSEZ_PASSER_MIN = 5
LAISSEZ_PASSER_MAX = 480
LAISSEZ_PASSER_DEFAUT = 60

# --- Ce qui n'est jamais une décision -------------------------------------
# Deux choses encombrent une liste : ce qui ne se décide pas, et ce qui se
# décide plusieurs fois. On traite les deux séparément.
#
# 1. Le **bruit** : de l'infrastructure au service d'autres sites — réseaux
#    de diffusion, certificats, télémétrie, plomberie de Windows. Personne ne
#    dira jamais « j'autorise fastly.net ». Et surtout : ces noms reviennent
#    sans cesse sous d'autres formes, donc les trancher une fois ne suffirait
#    même pas. Ils n'entrent pas au catalogue.
#
#    Attention à ne pas confondre avec « un site qui ne m'intéresse pas » :
#    celui-là se tranche une fois et disparaît de la file tout seul. Le filtre
#    ne sert qu'à ce qui reviendrait indéfiniment.
BRUIT_SITES = {
    # Réseaux de diffusion et hébergement génériques
    "cloudfront.net", "akamai.net", "akamaiedge.net", "akamaized.net",
    "edgekey.net", "edgesuite.net", "fastly.net", "fastlylb.net",
    "amazonaws.com", "azureedge.net", "azurefd.net", "trafficmanager.net",
    "cloudflare.com", "cloudflare.net", "cdn77.org", "llnwd.net",
    "cachefly.net", "jsdelivr.net", "unpkg.com", "gstatic.com",
    "googleapis.com", "googleusercontent.com", "gvt1.com", "gvt2.com",
    "ggpht.com",
    # Certificats et révocation
    "lencr.org", "letsencrypt.org", "digicert.com", "sectigo.com",
    "globalsign.com", "usertrust.com", "comodoca.com", "amazontrust.com",
    # Mesure, télémétrie, expérimentation
    "google-analytics.com", "googletagmanager.com", "doubleclick.net",
    "scorecardresearch.com", "app-measurement.com", "crashlytics.com",
    "sentry.io", "bugsnag.com", "branch.io", "exp-tas.com",
    "omtrdc.net", "demdex.net",
    # Anti-robot
    "hcaptcha.com", "recaptcha.net", "arkoselabs.com", "perimeterx.net",
    "datadome.co",
    # Plomberie du système
    "windowsupdate.com", "msftconnecttest.com", "msftncsi.com",
    "msedge.net", "windows.net", "windowsazure.com", "msocsp.com",
    "aaplimg.com", "apple-dns.net", "ntp.org", "root-servers.net",
    "in-addr.arpa", "ip6.arpa", "mshome.net",
}

# 2. Les **familles** : plusieurs domaines pour un seul service. Décider sur
#    « roblox.com » puis à nouveau sur « rbxcdn.com » n'a aucun sens — c'est
#    le même endroit. Une famille, une ligne, une décision ; et quand elle
#    bloque, l'agent reçoit **tous** les membres, sans quoi le jeu
#    continuerait de tourner sur les serveurs de contenu.
# Les serveurs de contenu que YouTube partage avec YouTube Music (vidéos et morceaux sur googlevideo.com,
# images sur ytimg.com). Ils restent dans la famille de youtube.com, mais ne sont envoyés à un appareil que
# s'il ne sait pas fermer l'appli YouTube elle-même : un Android sans accessibilité active. Ailleurs, fermer
# l'appli et youtube.com suffit, et YouTube Music garde ses morceaux (1.60.0).
SERVEURS_PARTAGES = frozenset({"googlevideo.com", "ytimg.com"})

FAMILLES_SITES = {
    "rbxcdn.com": "roblox.com",
    "robloxlabs.com": "roblox.com",
    "riotcdn.net": "riotgames.com",
    "pvp.net": "riotgames.com",
    "leagueoflegends.com": "riotgames.com",
    "valorant.com": "riotgames.com",
    "youtu.be": "youtube.com",
    "ytimg.com": "youtube.com",
    "googlevideo.com": "youtube.com",
    "youtube-nocookie.com": "youtube.com",
    "discordapp.com": "discord.com",
    "discordapp.net": "discord.com",
    "discordcdn.com": "discord.com",
    "discord.gg": "discord.com",
    "ttvnw.net": "twitch.tv",
    "jtvnw.net": "twitch.tv",
    "twitchcdn.net": "twitch.tv",
    "steamcommunity.com": "steampowered.com",
    "steamstatic.com": "steampowered.com",
    "steamcontent.com": "steampowered.com",
    "unrealengine.com": "epicgames.com",
    "fortnite.com": "epicgames.com",
    "xboxlive.com": "xbox.com",
    "gamepass.com": "xbox.com",
    "xboxservices.com": "xbox.com",
    "tiktokcdn.com": "tiktok.com",
    "tiktokv.com": "tiktok.com",
    "byteoversea.com": "tiktok.com",
    "cdninstagram.com": "instagram.com",
    "fbcdn.net": "facebook.com",
    "messenger.com": "facebook.com",
    "sc-cdn.net": "snapchat.com",
    "snap.com": "snapchat.com",
    "capcutapi.com": "capcut.com",
    "nflxvideo.net": "netflix.com",
    "nflximg.net": "netflix.com",
    "nflxext.com": "netflix.com",
    "mojang.com": "minecraft.net",
    "minecraftservices.com": "minecraft.net",
    "openai.com": "chatgpt.com",
    "oaistatic.com": "chatgpt.com",
    "oaiusercontent.com": "chatgpt.com",
    "anthropic.com": "claude.ai",
    "claudeusercontent.com": "claude.ai",
}

# Même chose côté logiciels. Le bruit, ici, ce sont les programmes de Windows
# lui-même et les utilitaires de barre des tâches : ils tournent tout le
# temps, personne ne les choisit, et les voir masque ce qui compte.
BRUIT_APPS = {
    "explorer", "dwm", "sihost", "taskhostw", "runtimebroker", "svchost",
    "csrss", "winlogon", "wininit", "services", "lsass", "fontdrvhost",
    "ctfmon", "conhost", "dllhost", "backgroundtaskhost", "wmiprvse",
    "applicationframehost", "textinputhost", "shellexperiencehost",
    "searchhost", "searchapp", "searchui", "startmenuexperiencehost",
    "systemsettings", "lockapp", "widgets", "widgetboard",
    "phoneexperiencehost", "useroobebroker", "smartscreen", "audiodg",
    "spoolsv", "sppsvc", "msmpeng", "securityhealthsystray",
    "securityhealthservice", "nissrv", "wmiapsrv", "sedsvc", "waasmedic",
    "rtkuwp", "rtkauduservice64", "nahimic3", "nahimicservice",
    "razerappengine", "razercentralservice", "razersynapseservice",
    "armourycrate", "asussoftwaremanager", "logioptionsplus_agent",
    "nvcontainer", "nvsphelper64", "nvidia web helper",
}

# 2.0.3 (Roblox sans avatars ni textures sur un PC) : les sites sans lesquels une appli autorisée ne
# marche pas. Autoriser l'appli sur un appareil les épargne aussi quand une catégorie (« Jeux » fermé)
# les couperait : sans quoi le jeu se lance, mais sans images. Un site bloqué nommément reste bloqué.
# Refonte des autorisations, lot 1 (CONCEPTION-autorisations.md) : les trois modes d'une autorisation.
# « temps » : une machine, une durée ; « exception » : toutes les machines du Demandeur, passe la catégorie fermée
# mais suit les plages et le temps ; « toujours » : toutes ses machines, rien ne le ferme sauf un blocage explicite.
MODES_AUTORISATION = ("temps", "exception", "toujours")
MODES_DURABLES = ("exception", "toujours")
# Catégories de sécurité et d'âge : fermées, aucune autorisation ne les passe (CONCEPTION § 2).
ETIQUETTES_SECURITE = frozenset({"adulte", "dangereux", "contournement", "pegi 12", "pegi 16", "pegi 18"})
# La catégorie dédiée à l'école (son nom suit la langue : i18n/categories.json, clé « ecole »).
ETIQUETTE_ECOLE = "École"
# 2.5.1 (décision du projet du 14-10) : un site vu pendant qu'un logiciel tourne lui est lié tout seul, jamais s'il porte
# l'une de ces catégories, ni s'il relève de l'infrastructure commune (BRUIT_SITES), ni par un navigateur (qui parle à
# tous les sites). Un lien automatique ne débloque rien : un site fermé au moment du lien attend un geste du Décideur.
ETIQUETTES_SANS_LIEN_AUTO = frozenset({"adulte", "dangereux", "contournement", "pub & traçage"})
NAVIGATEURS = frozenset({
    "chrome", "msedge", "firefox", "brave", "opera", "opera_gx", "vivaldi", "iexplore", "safari", "chromium", "arc",
    "com.android.chrome", "org.mozilla.firefox", "com.microsoft.emmx", "com.brave.browser", "com.opera.browser",
    "com.sec.android.app.sbrowser", "com.duckduckgo.mobile.android",
})
LIENS_AUTO_MAX = 40
# Le choix du mode d'un élément : « categorie » (suivre la catégorie, le défaut) retire tout réglage propre.
MODE_SUIVRE = "categorie"

# 2.5.12.4 (l'auteur, 19/10 : « l'appli doit découvrir SEULE quels sites appartiennent à quelle appli ») : les sites des
# grandes applis mobiles. Sur le téléphone d'un membre, Snapchat, Instagram et WhatsApp étaient autorisés, mais la
# catégorie fermée « Réseaux sociaux » bloquait leurs CDN : plus d'images. Une même liste sert au paquet Android et au
# nom du programme sur PC. JAMAIS de domaine de publicité ou de traçage ici (facebook.net, appsflyer…) : il serait
# ouvert avec l'appli ; la catégorie bannie doit primer (le store l'écarte aussi, par sécurité).
_ROBLOX = ("roblox.com", "rbx.com", "rbxinfra.com", "rbxcdn.com", "robloxlabs.com")
_DISCORD = ("discord.com", "discordapp.com", "discordapp.net", "discordcdn.com", "discord.gg", "discord.media")
_SNAPCHAT = ("snapchat.com", "sc-cdn.net", "snap.com", "sc-static.net", "sc-prod.net", "bitmoji.com")
_INSTAGRAM = ("instagram.com", "cdninstagram.com", "fbcdn.net")
_WHATSAPP = ("whatsapp.com", "whatsapp.net")
_TIKTOK = ("tiktok.com", "tiktokv.com", "tiktokcdn.com", "tiktokcdn-eu.com", "tiktokcdn-us.com", "ibytedtos.com")
_YOUTUBE = ("youtube.com", "ytimg.com", "googlevideo.com", "ggpht.com", "youtu.be", "youtube-nocookie.com")
_NETFLIX = ("netflix.com", "nflxvideo.net", "nflximg.net", "nflxext.com", "nflxso.net")
_SPOTIFY = ("spotify.com", "scdn.co", "spotifycdn.com", "spotifycdn.net")
_PINTEREST = ("pinterest.com", "pinterest.fr", "pinimg.com")
_BEREAL = ("bereal.com", "bere.al")
_RETRO = ("retro-cdn.com",)

DEPENDANCES_APPS = {
    "roblox": _ROBLOX,
    "steam": ("steampowered.com", "steamcommunity.com", "steamstatic.com", "steamcontent.com", "steamserver.net"),
    "discord": _DISCORD,
    "epicgameslauncher": ("epicgames.com", "unrealengine.com", "epicgames.dev"),
    "riotclient": ("riotgames.com", "riotcdn.net", "pvp.net", "leagueoflegends.com", "valorant.com"),
    "minecraft": ("minecraft.net", "mojang.com", "minecraftservices.com"),
    # 2.5.12.4 : applis mobiles (paquet Android) et leur programme sur PC quand il existe.
    "com.snapchat.android": _SNAPCHAT,
    "com.instagram.android": _INSTAGRAM,
    "com.whatsapp": _WHATSAPP,
    "com.whatsapp.w4b": _WHATSAPP,
    "whatsapp.root": _WHATSAPP,
    "whatsapp": _WHATSAPP,
    "com.zhiliaoapp.musically": _TIKTOK,
    "com.ss.android.ugc.trill": _TIKTOK,
    "tiktok": _TIKTOK,
    "com.google.android.youtube": _YOUTUBE,
    "com.google.android.youtube.tv": _YOUTUBE,
    "com.google.android.apps.youtube.kids": _YOUTUBE,
    "com.netflix.mediaclient": _NETFLIX,
    "com.netflix.ninja": _NETFLIX,
    "netflix": _NETFLIX,
    "com.spotify.music": _SPOTIFY,
    "com.spotify.tv.android": _SPOTIFY,
    "spotify": _SPOTIFY,
    "com.discord": _DISCORD,
    "com.roblox.client": _ROBLOX,
    "com.pinterest": _PINTEREST,
    "com.bereal.ft": _BEREAL,
    "io.lonepalm.retro": _RETRO,
}

# 2.5.12.4 : la racine d'un nom de paquet Android, pour reconnaître les sites qui portent le nom de l'appli
# (com.snapchat.android → « snapchat »). Ces segments ne disent rien de l'appli : on les saute. Les grandes marques
# parapluies aussi (com.google.android.youtube ne doit pas lier tout « google.* » à YouTube).
SEGMENTS_GENERIQUES = frozenset({
    "com", "org", "net", "io", "co", "app", "apps", "android", "mobile", "client", "tv", "ft", "w4b",
    "google", "microsoft", "samsung", "sec", "amazon", "facebook", "meta",
})

FAMILLES_APPS = {
    "chrome_proxy": "chrome",
    "msedgewebview2": "msedge",
    "robloxplayerbeta": "roblox",
    "robloxplayerlauncher": "roblox",
    "robloxcrashhandler": "roblox",
    "steamwebhelper": "steam",
    "steamservice": "steam",
    "gameoverlayui": "steam",
    "epicwebhelper": "epicgameslauncher",
    "riotclientservices": "riotclient",
    "riotclientux": "riotclient",
    "riotclientuxrender": "riotclient",
    "leagueclient": "riotclient",
    "leagueclientux": "riotclient",
    "leagueclientuxrender": "riotclient",
    "obs32": "obs64",
    "discordcanary": "discord",
    "discordptb": "discord",
    # 2.3.3 : Minecraft Bedrock tourne sous « Minecraft.Windows.exe » ; un accord ou un blocage « minecraft » le vise.
    "minecraft.windows": "minecraft",
    "minecraftlauncher": "minecraft",
}

# --- Liste noire : ce qui est dangereux, pas ce qui est discutable --------
# À ne pas confondre avec les règles de la maison. Une règle est une
# décision de parent ; la liste noire est un constat extérieur : ce domaine
# héberge un logiciel malveillant ou une page d'hameçonnage. Elle ne passe
# donc pas par la file à classer, elle s'applique à tous les PC, et aucun
# laissez-passer ne l'ouvre.
STORAGE_KEY_NOIRE = "pc_parental_liste_noire"
LISTE_NOIRE_SOURCES = {
    "urlhaus": {
        "nom": "URLhaus (abuse.ch)",
        "url": "https://urlhaus.abuse.ch/downloads/hostfile/",
        "quoi": "logiciels malveillants en cours de diffusion",
    },
    "phishing_army": {
        "nom": "Phishing Army",
        "url": "https://phishing.army/download/phishing_army_blocklist.txt",
        "quoi": "hameçonnage (PhishTank, OpenPhish, Cert.pl…)",
    },
    "cert_pl": {
        "nom": "CERT Polska",
        "url": "https://hole.cert.pl/domains/domains.txt",
        "quoi": "hameçonnage signalé par un CERT national",
    },
}
# Rafraîchissement automatique : une fois par jour. Les sources bougent
# plus souvent, mais une liste vieille de quelques heures protège encore ;
# tirer dessus toutes les heures ne protégerait pas mieux et userait des
# serveurs qui rendent service gratuitement. Réglable, parce que
# « automatique » ne veut rien dire si l'on ne voit ni le rythme, ni la
# dernière fois que ça a marché.
# --- Notoriété des sites -------------------------------------------------
# Rien ne dit gratuitement « ce site convient à un enfant ». Ce qui se
# télécharge, c'est le classement du million de domaines les plus visités.
# Il faut donc le lire pour ce qu'il est : **fréquenté n'est pas
# convenable** — pornhub.com est dans les cent premiers. Le signal utile
# est l'inverse : un domaine que personne au monde ne visite, contacté par
# un programme qu'on ne connaît pas, mérite un regard.
STORAGE_KEY_NOTORIETE = "pc_parental_notoriete"
NOTORIETE_SOURCE = {
    "nom": "Tranco",
    "url": "https://tranco-list.eu/top-1m.csv.zip",
    "quoi": "classement du million de domaines les plus visités",
}
# Combien de rangs on garde. Au-delà de cent mille, un domaine n'est plus
# « connu » de personne au sens qui nous intéresse, et le fichier enfle.
NOTORIETE_PROFONDEUR = 100000
NOTORIETE_HEURES = 24
# Les paliers, et ce qu'on en dit à l'écran.
NOTORIETE_PALIERS = (
    (1000, "très fréquenté"),
    (10000, "fréquenté"),
    (100000, "peu fréquenté"),
)
NOTORIETE_INCONNU = "inconnu"

# --- VirusTotal ----------------------------------------------------------
# On n'envoie jamais un fichier : seulement son empreinte SHA-256. C'est
# suffisant pour interroger, et cela ne fait pas sortir un octet du contenu
# de la machine. Mais il faut le dire : une empreinte apprend quand même à
# un tiers **quels programmes tournent chez soi**, et ce n'est pas rien.
STORAGE_KEY_VT = "pc_parental_virustotal"
VT_URL = "https://www.virustotal.com/api/v3/files/"
# Le palier gratuit : 4 requêtes par minute, 500 par jour. On reste en
# dessous — se faire couper l'accès pour avoir été trop pressé serait
# perdre l'information pour de bon.
VT_PAR_MINUTE = 4
VT_PAR_JOUR = 450
# Un verdict ne change pas tous les jours ; on le regarde à nouveau au bout
# d'un mois, et tout de suite s'il était introuvable.
VT_FRAICHEUR_JOURS = 30
# Au-delà, on ne calcule pas d'empreinte : lire deux giga-octets pour un
# jeu bloquerait le disque sans rien apprendre d'utile.
VT_TAILLE_MAX_MO = 400

LISTE_NOIRE_HEURES = 24
LISTE_NOIRE_HEURES_MIN = 1
LISTE_NOIRE_HEURES_MAX = 72
LISTE_NOIRE_TTL = LISTE_NOIRE_HEURES * 3600
# Domaines montrés à l'écran : on ne lit pas cinquante mille lignes, on
# cherche dedans.
NOIRE_EXTRAIT = 40
# Garde-fou : le service DNS de Windows relit le fichier hosts sans arrêt.
# Quelques milliers de lignes passent, cent mille non. Au-delà, les domaines
# restent **connus** de l'agent — pour la détection et l'avertissement —
# mais c'est le résolveur filtrant qui les bloque. Rien n'est perdu en
# silence : les deux nombres sont affichés.
MAX_HOSTS_LISTE_NOIRE = 4000
# Alertes conservées par PC.
MAX_ALERTES = 50
# Jours d'historique des blocages, pour la courbe. Un mois : assez pour voir
# une habitude s'installer, assez court pour qu'un mauvais jour s'efface.
BLOCAGES_JOURS = 30
# Barres du graphe en texte : elles servent quand l'historique de Home
# Assistant a déjà été purgé, ce qui arrive vite.
SPARK = " ▁▂▃▄▅▆▇█"
# Deux alertes sur le même domaine à moins de ça d'intervalle : une seule.
ALERTE_REPOS = 1800

# Résolveur DNS filtrant, posé sur les cartes réseau du PC. C'est lui qui
# encaisse la masse — des millions de domaines, aucun entretien. Vision
# désactive déjà le DNS chiffré des navigateurs, sans quoi rien de tout ceci
# ne servirait.
DNS_AUCUN = "Aucun (DNS de la box)"
DNS_FILTRANTS: dict[str, list[str]] = {
    DNS_AUCUN: [],
    "Cloudflare — malware": ["1.1.1.2", "1.0.0.2"],
    "Cloudflare — malware et contenu adulte": ["1.1.1.3", "1.0.0.3"],
    "Quad9 — malware": ["9.9.9.9", "149.112.112.112"],
}

# Marqueur remplace a la volee par l'adresse reelle de Home Assistant quand
# l'installateur est telecharge depuis /api/pc_parental/client/install.
MARQUEUR_URL = "@@HA_URL@@"
CLIENT_FILES = {
    "agent": "ha-parental-agent.ps1",
    "install": "install.ps1",
    "uninstall": "uninstall.ps1",
}

STORAGE_KEY = "pc_parental"
STORAGE_VERSION = 1

# Génération de la clé d'inscription. Passée à 2 en 1.61.0 : la clé d'avant,
# lisible par tout compte Home Assistant, est remplacée au premier démarrage.
ENROLL_KEY_V = 3
# 2.2.12 : la génération 3 remplace une fois une clé connue ou faible (exemple
# publié, trop courte, trop peu de caractères différents). Les appareils déjà
# inscrits ont leur propre secret ; l'ancienne clé ne vaut plus que pour qu'un
# appareil déjà connu redemande une réinscription, pendant ce délai (jours).
CLE_GRACE_JOURS = 30
# Longueur minimale d'une clé choisie, et exemples déjà publiés (anciennes
# aides de l'installateur) : ces clés-là sont connues de tous, jamais acceptées.
# Rangés par empreinte SHA-256 (en minuscules) pour que l'exemple lui-même ne
# reparaisse nulle part dans le source.
CLE_MIN = 12
CLES_PUBLIEES_SHA256 = frozenset({
    "d8dc5871a3273005102c43902dbd89d0cdac377961d11279b2d1f2084a42c55d",
    "79090713c0374016caf07cc1655c6922f728963b25f5d49c15ff2023b48ada73",
    "9ea7f8406dea8a97eef5ba3ffb3d181c617168b3ff5c40f9d712166cf0453e68",
})
# Minutes pendant lesquelles un appareil déjà inscrit peut se réinscrire, une fois
# qu'un administrateur l'a permis. Il reçoit un secret neuf, jamais l'ancien.
REINSCRIPTION_MINUTES = 15

# Rythme d'interrogation conseillé à l'agent, en secondes.
POLL_SECONDS = 20
# Au-delà de ce délai sans nouvelle, l'agent est considéré hors ligne.
AGENT_TIMEOUT = timedelta(minutes=2)
# Délai d'avertissement avant une fermeture programmée, en minutes.
WARN_MINUTES = 5
# Sans frappe ni souris pendant ce temps, la machine est dite inactive.
IDLE_MINUTES = 5
# Après un refus, combien de minutes avant de pouvoir redemander la même chose (0 : tout de suite).
DELAI_REFUS = 60
# Durée d'affichage d'un message envoyé sur un PC, en secondes.
MESSAGE_SECONDS = 30

# Présence de la machine, à la façon d'un statut de messagerie.
PRESENCE_ACTIF = "actif"
PRESENCE_INACTIF = "inactif"
PRESENCE_VERROUILLE = "verrouille"
PRESENCE_HORS_LIGNE = "hors_ligne"
PRESENCES = [
    PRESENCE_ACTIF,
    PRESENCE_INACTIF,
    PRESENCE_VERROUILLE,
    PRESENCE_HORS_LIGNE,
]
PRESENCE_ICONES = {
    PRESENCE_ACTIF: "mdi:monitor-shimmer",
    PRESENCE_INACTIF: "mdi:monitor-dashboard",
    PRESENCE_VERROUILLE: "mdi:monitor-lock",
    PRESENCE_HORS_LIGNE: "mdi:monitor-off",
}
PRESENCE_PASTILLES = {
    PRESENCE_ACTIF: "🟢",
    PRESENCE_INACTIF: "🟡",
    PRESENCE_VERROUILLE: "🔴",
    PRESENCE_HORS_LIGNE: "⚫",
}
PRESENCE_LIBELLES = {
    PRESENCE_ACTIF: "en cours d'utilisation",
    PRESENCE_INACTIF: "allumé, mais inactif",
    PRESENCE_VERROUILLE: "session verrouillée",
    PRESENCE_HORS_LIGNE: "éteint ou injoignable",
}

# Sons de notification fournis par Windows. Le second champ indique un son
# répété : il exige une notification de durée longue, sans quoi Windows le
# coupe au bout de quelques secondes.
SOUNDS: dict[str, tuple[str, bool]] = {
    "Par défaut": ("Notification.Default", False),
    "Message": ("Notification.IM", False),
    "Courrier": ("Notification.Mail", False),
    "Rappel": ("Notification.Reminder", False),
    "SMS": ("Notification.SMS", False),
    "Alarme (insistant)": ("Notification.Looping.Alarm", True),
    "Appel (insistant)": ("Notification.Looping.Call", True),
    "Silencieux": ("", False),
}
DEFAULT_SOUND = "Par défaut"

# Destinataire d'un message : tous, une étiquette, ou un PC précis.
TOUS_LES_PC = "Tous les appareils"
PREFIXE_TAG = "Étiquette : "

# Nombre de messages en attente conservés par PC.
MAX_MESSAGES = 20
# Un message non délivré se périme : « sors la poubelle » affiché six heures
# plus tard n'a plus aucun sens.
MESSAGE_TTL = 900

UPDATE_INTERVAL = timedelta(seconds=15)

STATE_OPEN = "ouvert"
STATE_LOCKED = "ferme"

MODE_OPEN = "unlocked"
MODE_LOCKED = "locked"

SOURCE_PLANNING = "planning"
SOURCE_OVERRIDE = "dérogation"

WEEKDAYS = ["lun", "mar", "mer", "jeu", "ven", "sam", "dim"]
WEEKDAY_NAMES = [
    "lundi",
    "mardi",
    "mercredi",
    "jeudi",
    "vendredi",
    "samedi",
    "dimanche",
]

DAY_PRESETS: dict[str, list[bool]] = {
    "Tous les jours": [True] * 7,
    "Du lundi au vendredi": [True] * 5 + [False, False],
    "Week-end": [False] * 5 + [True, True],
    "Nuits d'école (dim→jeu)": [True, True, True, True, False, False, True],
}
DEFAULT_DAYS = "Tous les jours"

# Les jours proposés pour une plage déjà posée. Écrits comme `days_label`
# les rend, pour que relire puis réécrire ne change jamais le sens — c'est
# `jours_depuis_texte` qui les relit, et l'aller-retour est éprouvé.
# Une plage dont la combinaison n'est dans aucune de ces lignes garde la
# sienne : elle s'ajoute en tête plutôt que d'être écrasée en silence.
JOURS_CHOIX = [
    "tous les jours",
    "du lundi au vendredi",
    "le week-end",
    "lundi, mercredi et vendredi",
    "mardi et jeudi",
    "du lundi au jeudi",
    "du vendredi au dimanche",
    "le lundi",
    "le mardi",
    "le mercredi",
    "le jeudi",
    "le vendredi",
    "le samedi",
    "le dimanche",
]

# « Tous les PC » n'est pas l'absence de choix : c'est la maison. Le
# catalogue lui appartient, et une décision prise là vaut partout.
NO_PC = "Tous les appareils"

# Ce qu'on regarde : des sites, ou des logiciels. Un seul jeu de contrôles à
# l'écran plutôt que deux colonnes qu'on lit en diagonale.
TYPE_SITES = "Sites"
TYPE_LOGICIELS = "Logiciels"
TYPES = [TYPE_SITES, TYPE_LOGICIELS]

# Combien de lignes à l'écran. Une liste qui déborde ne se lit pas ; une
# liste qu'on paginate se parcourt sans jamais perdre sa place.
TAILLES_PAGE = ["10", "20", "50", "100"]
TAILLE_PAGE_DEFAUT = "20"
# Combien de programmes on retient par site. Au-delà, c'est du bruit : un
# domaine parlé par huit programmes différents ne dit plus rien de personne.
MAX_ORIGINES = 5
AUCUNE_ORIGINE = "Aucun programme connu"

# Ce qu'on refuse de bloquer, quoi qu'on demande. Un contrôle parental qui
# laisse couper svchost ne protège plus un enfant : il casse sa machine, et
# personne ne fera le lien. La liste vise Windows lui-même et Vision.
APPS_INTOUCHABLES_MORTE = {}
APPS_INTOUCHABLES = {
    "system", "registry", "idle", "smss", "csrss", "wininit", "winlogon",
    "services", "lsass", "lsaiso", "svchost", "fontdrvhost", "dwm",
    "explorer", "sihost", "taskhostw", "ctfmon", "runtimebroker",
    "shellexperiencehost", "startmenuexperiencehost", "searchhost",
    "searchindexer", "audiodg", "spoolsv", "wudfhost", "conhost",
    "dllhost", "wmiprvse", "securityhealthservice", "msmpeng",
    "powershell", "pwsh", "powershell_ise", "cmd", "wsmprovhost",
}

NO_RULE = "Aucune plage"

# Ce qu'une plage ferme. Une seule liste de plages, deux façons de s'en
# servir : couper la session, ou ne couper que des étiquettes. Deux notions
# séparées auraient voulu dire deux formulaires, deux listes, et la question
# « laquelle des deux ai-je posée ? » à chaque fois.
PORTEE_SESSION = "Verrouiller la session"
PORTEE_ETIQUETTES = "Fermer des étiquettes"
PORTEES = [PORTEE_SESSION, PORTEE_ETIQUETTES]
NO_USER = "Personne"

SERVICE_LOCK = "lock"
SERVICE_UNLOCK = "unlock"
SERVICE_RESET = "reset"
SERVICE_CREATE_PC = "create_pc"
SERVICE_DELETE_PC = "delete_pc"
SERVICE_ADD_SCHEDULE = "add_schedule"
SERVICE_REMOVE_SCHEDULE = "remove_schedule"
SERVICE_UPDATE_SCHEDULE = "update_schedule"
SERVICE_SET_APPS = "set_apps"
SERVICE_SET_SITES = "set_sites"
SERVICE_BUILD_DASHBOARD = "build_dashboard"
SERVICE_UPDATE_AGENT = "update_agent"
SERVICE_NOTIFY = "notify"
SERVICE_RENAME_PC = "rename_pc"
# Étiquetage : ce que la carte appelle, et ce qu'une automatisation peut
# appeler aussi. Tout passe par des étiquettes Home Assistant.
SERVICE_SET_TAGS = "set_tags"
SERVICE_TAG_ITEMS = "tag_items"
SERVICE_CREATE_TAG = "create_tag"
SERVICE_DELETE_TAG = "delete_tag"
SERVICE_SET_PC_TAG = "set_pc_tag"
SERVICE_SET_HOUSE_TAG = "set_house_tag"
SERVICE_SET_PC_LABELS = "set_pc_labels"
# Trancher pour la maison depuis la carte, sans passer par une liste
# déroulante et trois boutons : la carte sait déjà de quoi elle parle.
SERVICE_DECIDER_ELEMENTS = "decider_elements"


