/*
 * Vision — le panneau de l'écran de veille.
 *
 * Il montre les vraies cartes de Home Assistant, dessinées par Home Assistant,
 * dans le thème de l'appareil (ses couleurs arrivent dans l'adresse). L'horloge
 * d'abord, puis chaque tableau de bord rendu disponible : ses sections sont
 * rangées en colonnes, et ce qui dépasse l'écran passe sur l'écran suivant, en fondu.
 * Rien n'est cliquable : c'est un affichage.
 */
// ---- i18n:debut — les textes du panneau de veille, dans les six langues de Vision (fr source, en repli) ----
const TEXTES = /*i18n-json*/{
  "fr": {
    "ciel_sunny": "Ensoleillé",
    "ciel_clear_night": "Nuit claire",
    "ciel_partlycloudy": "Éclaircies",
    "ciel_cloudy": "Nuageux",
    "ciel_rainy": "Pluie",
    "ciel_pouring": "Forte pluie",
    "ciel_fog": "Brouillard",
    "ciel_snowy": "Neige",
    "ciel_snowy_rainy": "Neige fondue",
    "ciel_orage": "Orage",
    "ciel_vent": "Vent",
    "ciel_hail": "Grêle",
    "ciel_exceptional": "Exceptionnel",
    "lettres_heures": "minuit,une heure,deux heures,trois heures,quatre heures,cinq heures,six heures,sept heures,huit heures,neuf heures,dix heures,onze heures,midi,treize heures,quatorze heures,quinze heures,seize heures,dix-sept heures,dix-huit heures,dix-neuf heures,vingt heures,vingt et une heures,vingt-deux heures,vingt-trois heures",
    "lettres_minutes": "zéro,une,deux,trois,quatre,cinq,six,sept,huit,neuf,dix,onze,douze,treize,quatorze,quinze,seize,dix-sept,dix-huit,dix-neuf,vingt,vingt et une,vingt-deux,vingt-trois,vingt-quatre,vingt-cinq,vingt-six,vingt-sept,vingt-huit,vingt-neuf,trente,trente et une,trente-deux,trente-trois,trente-quatre,trente-cinq,trente-six,trente-sept,trente-huit,trente-neuf,quarante,quarante et une,quarante-deux,quarante-trois,quarante-quatre,quarante-cinq,quarante-six,quarante-sept,quarante-huit,quarante-neuf,cinquante,cinquante et une,cinquante-deux,cinquante-trois,cinquante-quatre,cinquante-cinq,cinquante-six,cinquante-sept,cinquante-huit,cinquante-neuf",
    "lettres_pile": "pile",
    "lettres_minutes_motif": "{m}",
    "semaine": "SEMAINE {n}",
    "retro_mois": "MOIS",
    "retro_jour": "JOUR",
    "retro_annee": "ANNÉE",
    "retro_heure": "HEURE",
    "retro_min": "MIN",
    "cockpit_locale": "LOCALE",
    "rose_vents": "N,E,S,O",
    "chargement": "Chargement…",
    "web_lourde": "Cette page est trop lourde pour cet écran : la veille s'en passe aujourd'hui.",
    "web_injoignable": "Site injoignable pour l'instant : la veille continue sans lui.",
    "web_figeait": "Cette page figeait l'écran : la veille s'en passe aujourd'hui.",
    "fige": "Figé",
    "reprise": "Reprise",
    "vt_auj": "Aujourd'hui",
    "vt_demain": "Demain",
    "vt_journee": "Journée",
    "vt_vide": "Rien de prévu",
    "vt_passages": "Aucun passage récent",
    "vt_instant": "à l'instant",
    "vt_min": "il y a {n} min",
    "vt_h": "il y a {n} h",
    "vt_hier": "hier",
    "vt_j": "il y a {n} j"
  },
  "en": {
    "ciel_sunny": "Sunny",
    "ciel_clear_night": "Clear night",
    "ciel_partlycloudy": "Partly cloudy",
    "ciel_cloudy": "Cloudy",
    "ciel_rainy": "Rain",
    "ciel_pouring": "Heavy rain",
    "ciel_fog": "Fog",
    "ciel_snowy": "Snow",
    "ciel_snowy_rainy": "Sleet",
    "ciel_orage": "Thunderstorm",
    "ciel_vent": "Windy",
    "ciel_hail": "Hail",
    "ciel_exceptional": "Exceptional",
    "lettres_heures": "twelve,one,two,three,four,five,six,seven,eight,nine,ten,eleven,twelve,one,two,three,four,five,six,seven,eight,nine,ten,eleven",
    "lettres_minutes": "zero,oh one,oh two,oh three,oh four,oh five,oh six,oh seven,oh eight,oh nine,ten,eleven,twelve,thirteen,fourteen,fifteen,sixteen,seventeen,eighteen,nineteen,twenty,twenty-one,twenty-two,twenty-three,twenty-four,twenty-five,twenty-six,twenty-seven,twenty-eight,twenty-nine,thirty,thirty-one,thirty-two,thirty-three,thirty-four,thirty-five,thirty-six,thirty-seven,thirty-eight,thirty-nine,forty,forty-one,forty-two,forty-three,forty-four,forty-five,forty-six,forty-seven,forty-eight,forty-nine,fifty,fifty-one,fifty-two,fifty-three,fifty-four,fifty-five,fifty-six,fifty-seven,fifty-eight,fifty-nine",
    "lettres_pile": "o'clock",
    "lettres_minutes_motif": "{m}",
    "semaine": "WEEK {n}",
    "retro_mois": "MONTH",
    "retro_jour": "DAY",
    "retro_annee": "YEAR",
    "retro_heure": "HOUR",
    "retro_min": "MIN",
    "cockpit_locale": "LOCAL",
    "rose_vents": "N,E,S,W",
    "chargement": "Loading…",
    "web_lourde": "This page is too heavy for this screen: the screensaver is skipping it today.",
    "web_injoignable": "Site unreachable for now: the screensaver carries on without it.",
    "web_figeait": "This page was freezing the screen: the screensaver is skipping it today.",
    "fige": "Paused",
    "reprise": "Resumed",
    "vt_auj": "Today",
    "vt_demain": "Tomorrow",
    "vt_journee": "All day",
    "vt_vide": "Nothing planned",
    "vt_passages": "No recent activity",
    "vt_instant": "just now",
    "vt_min": "{n} min ago",
    "vt_h": "{n} h ago",
    "vt_hier": "yesterday",
    "vt_j": "{n} days ago"
  },
  "de": {
    "ciel_sunny": "Sonnig",
    "ciel_clear_night": "Klare Nacht",
    "ciel_partlycloudy": "Teilweise bewölkt",
    "ciel_cloudy": "Bewölkt",
    "ciel_rainy": "Regen",
    "ciel_pouring": "Starkregen",
    "ciel_fog": "Nebel",
    "ciel_snowy": "Schnee",
    "ciel_snowy_rainy": "Schneeregen",
    "ciel_orage": "Gewitter",
    "ciel_vent": "Windig",
    "ciel_hail": "Hagel",
    "ciel_exceptional": "Außergewöhnlich",
    "lettres_heures": "null Uhr,ein Uhr,zwei Uhr,drei Uhr,vier Uhr,fünf Uhr,sechs Uhr,sieben Uhr,acht Uhr,neun Uhr,zehn Uhr,elf Uhr,zwölf Uhr,dreizehn Uhr,vierzehn Uhr,fünfzehn Uhr,sechzehn Uhr,siebzehn Uhr,achtzehn Uhr,neunzehn Uhr,zwanzig Uhr,einundzwanzig Uhr,zweiundzwanzig Uhr,dreiundzwanzig Uhr",
    "lettres_minutes": "null,eins,zwei,drei,vier,fünf,sechs,sieben,acht,neun,zehn,elf,zwölf,dreizehn,vierzehn,fünfzehn,sechzehn,siebzehn,achtzehn,neunzehn,zwanzig,einundzwanzig,zweiundzwanzig,dreiundzwanzig,vierundzwanzig,fünfundzwanzig,sechsundzwanzig,siebenundzwanzig,achtundzwanzig,neunundzwanzig,dreißig,einunddreißig,zweiunddreißig,dreiunddreißig,vierunddreißig,fünfunddreißig,sechsunddreißig,siebenunddreißig,achtunddreißig,neununddreißig,vierzig,einundvierzig,zweiundvierzig,dreiundvierzig,vierundvierzig,fünfundvierzig,sechsundvierzig,siebenundvierzig,achtundvierzig,neunundvierzig,fünfzig,einundfünfzig,zweiundfünfzig,dreiundfünfzig,vierundfünfzig,fünfundfünfzig,sechsundfünfzig,siebenundfünfzig,achtundfünfzig,neunundfünfzig",
    "lettres_pile": "genau",
    "lettres_minutes_motif": "{m}",
    "semaine": "KW {n}",
    "retro_mois": "MONAT",
    "retro_jour": "TAG",
    "retro_annee": "JAHR",
    "retro_heure": "STUNDE",
    "retro_min": "MIN",
    "cockpit_locale": "LOKAL",
    "rose_vents": "N,O,S,W",
    "chargement": "Wird geladen…",
    "web_lourde": "Diese Seite ist zu schwer für diesen Bildschirm: der Bildschirmschoner lässt sie heute weg.",
    "web_injoignable": "Website gerade nicht erreichbar: der Bildschirmschoner läuft ohne sie weiter.",
    "web_figeait": "Diese Seite hat den Bildschirm eingefroren: der Bildschirmschoner lässt sie heute weg.",
    "fige": "Angehalten",
    "reprise": "Weiter",
    "vt_auj": "Heute",
    "vt_demain": "Morgen",
    "vt_journee": "Ganztägig",
    "vt_vide": "Nichts geplant",
    "vt_passages": "Keine neue Aktivität",
    "vt_instant": "gerade eben",
    "vt_min": "vor {n} Min.",
    "vt_h": "vor {n} Std.",
    "vt_hier": "gestern",
    "vt_j": "vor {n} Tagen"
  },
  "es": {
    "ciel_sunny": "Soleado",
    "ciel_clear_night": "Noche despejada",
    "ciel_partlycloudy": "Parcialmente nublado",
    "ciel_cloudy": "Nublado",
    "ciel_rainy": "Lluvia",
    "ciel_pouring": "Lluvia intensa",
    "ciel_fog": "Niebla",
    "ciel_snowy": "Nieve",
    "ciel_snowy_rainy": "Aguanieve",
    "ciel_orage": "Tormenta",
    "ciel_vent": "Viento",
    "ciel_hail": "Granizo",
    "ciel_exceptional": "Excepcional",
    "lettres_heures": "las doce,la una,las dos,las tres,las cuatro,las cinco,las seis,las siete,las ocho,las nueve,las diez,las once,las doce,la una,las dos,las tres,las cuatro,las cinco,las seis,las siete,las ocho,las nueve,las diez,las once",
    "lettres_minutes": "cero,uno,dos,tres,cuatro,cinco,seis,siete,ocho,nueve,diez,once,doce,trece,catorce,quince,dieciséis,diecisiete,dieciocho,diecinueve,veinte,veintiuno,veintidós,veintitrés,veinticuatro,veinticinco,veintiséis,veintisiete,veintiocho,veintinueve,treinta,treinta y uno,treinta y dos,treinta y tres,treinta y cuatro,treinta y cinco,treinta y seis,treinta y siete,treinta y ocho,treinta y nueve,cuarenta,cuarenta y uno,cuarenta y dos,cuarenta y tres,cuarenta y cuatro,cuarenta y cinco,cuarenta y seis,cuarenta y siete,cuarenta y ocho,cuarenta y nueve,cincuenta,cincuenta y uno,cincuenta y dos,cincuenta y tres,cincuenta y cuatro,cincuenta y cinco,cincuenta y seis,cincuenta y siete,cincuenta y ocho,cincuenta y nueve",
    "lettres_pile": "en punto",
    "lettres_minutes_motif": "y {m}",
    "semaine": "SEMANA {n}",
    "retro_mois": "MES",
    "retro_jour": "DÍA",
    "retro_annee": "AÑO",
    "retro_heure": "HORA",
    "retro_min": "MIN",
    "cockpit_locale": "LOCAL",
    "rose_vents": "N,E,S,O",
    "chargement": "Cargando…",
    "web_lourde": "Esta página es demasiado pesada para esta pantalla: el salvapantallas la omite hoy.",
    "web_injoignable": "Sitio inaccesible por ahora: el salvapantallas sigue sin él.",
    "web_figeait": "Esta página bloqueaba la pantalla: el salvapantallas la omite hoy.",
    "fige": "En pausa",
    "reprise": "Reanudado",
    "vt_auj": "Hoy",
    "vt_demain": "Mañana",
    "vt_journee": "Todo el día",
    "vt_vide": "Nada previsto",
    "vt_passages": "Sin actividad reciente",
    "vt_instant": "ahora mismo",
    "vt_min": "hace {n} min",
    "vt_h": "hace {n} h",
    "vt_hier": "ayer",
    "vt_j": "hace {n} días"
  },
  "it": {
    "ciel_sunny": "Soleggiato",
    "ciel_clear_night": "Notte serena",
    "ciel_partlycloudy": "Parzialmente nuvoloso",
    "ciel_cloudy": "Nuvoloso",
    "ciel_rainy": "Pioggia",
    "ciel_pouring": "Pioggia forte",
    "ciel_fog": "Nebbia",
    "ciel_snowy": "Neve",
    "ciel_snowy_rainy": "Nevischio",
    "ciel_orage": "Temporale",
    "ciel_vent": "Ventoso",
    "ciel_hail": "Grandine",
    "ciel_exceptional": "Eccezionale",
    "lettres_heures": "mezzanotte,l'una,le due,le tre,le quattro,le cinque,le sei,le sette,le otto,le nove,le dieci,le undici,mezzogiorno,le tredici,le quattordici,le quindici,le sedici,le diciassette,le diciotto,le diciannove,le venti,le ventuno,le ventidue,le ventitré",
    "lettres_minutes": "zero,uno,due,tre,quattro,cinque,sei,sette,otto,nove,dieci,undici,dodici,tredici,quattordici,quindici,sedici,diciassette,diciotto,diciannove,venti,ventuno,ventidue,ventitré,ventiquattro,venticinque,ventisei,ventisette,ventotto,ventinove,trenta,trentuno,trentadue,trentatré,trentaquattro,trentacinque,trentasei,trentasette,trentotto,trentanove,quaranta,quarantuno,quarantadue,quarantatré,quarantaquattro,quarantacinque,quarantasei,quarantasette,quarantotto,quarantanove,cinquanta,cinquantuno,cinquantadue,cinquantatré,cinquantaquattro,cinquantacinque,cinquantasei,cinquantasette,cinquantotto,cinquantanove",
    "lettres_pile": "in punto",
    "lettres_minutes_motif": "e {m}",
    "semaine": "SETTIMANA {n}",
    "retro_mois": "MESE",
    "retro_jour": "GIORNO",
    "retro_annee": "ANNO",
    "retro_heure": "ORA",
    "retro_min": "MIN",
    "cockpit_locale": "LOCALE",
    "rose_vents": "N,E,S,O",
    "chargement": "Caricamento…",
    "web_lourde": "Questa pagina è troppo pesante per questo schermo: il salvaschermo oggi la salta.",
    "web_injoignable": "Sito non raggiungibile per ora: il salvaschermo continua senza.",
    "web_figeait": "Questa pagina bloccava lo schermo: il salvaschermo oggi la salta.",
    "fige": "In pausa",
    "reprise": "Ripreso",
    "vt_auj": "Oggi",
    "vt_demain": "Domani",
    "vt_journee": "Tutto il giorno",
    "vt_vide": "Niente in programma",
    "vt_passages": "Nessuna attività recente",
    "vt_instant": "proprio ora",
    "vt_min": "{n} min fa",
    "vt_h": "{n} h fa",
    "vt_hier": "ieri",
    "vt_j": "{n} giorni fa"
  },
  "nl": {
    "ciel_sunny": "Zonnig",
    "ciel_clear_night": "Heldere nacht",
    "ciel_partlycloudy": "Gedeeltelijk bewolkt",
    "ciel_cloudy": "Bewolkt",
    "ciel_rainy": "Regen",
    "ciel_pouring": "Zware regen",
    "ciel_fog": "Mist",
    "ciel_snowy": "Sneeuw",
    "ciel_snowy_rainy": "Natte sneeuw",
    "ciel_orage": "Onweer",
    "ciel_vent": "Winderig",
    "ciel_hail": "Hagel",
    "ciel_exceptional": "Uitzonderlijk",
    "lettres_heures": "nul uur,één uur,twee uur,drie uur,vier uur,vijf uur,zes uur,zeven uur,acht uur,negen uur,tien uur,elf uur,twaalf uur,dertien uur,veertien uur,vijftien uur,zestien uur,zeventien uur,achttien uur,negentien uur,twintig uur,eenentwintig uur,tweeëntwintig uur,drieëntwintig uur",
    "lettres_minutes": "nul,één,twee,drie,vier,vijf,zes,zeven,acht,negen,tien,elf,twaalf,dertien,veertien,vijftien,zestien,zeventien,achttien,negentien,twintig,eenentwintig,tweeëntwintig,drieëntwintig,vierentwintig,vijfentwintig,zesentwintig,zevenentwintig,achtentwintig,negenentwintig,dertig,eenendertig,tweeëndertig,drieëndertig,vierendertig,vijfendertig,zesendertig,zevenendertig,achtendertig,negenendertig,veertig,eenenveertig,tweeënveertig,drieënveertig,vierenveertig,vijfenveertig,zesenveertig,zevenenveertig,achtenveertig,negenenveertig,vijftig,eenenvijftig,tweeënvijftig,drieënvijftig,vierenvijftig,vijfenvijftig,zesenvijftig,zevenenvijftig,achtenvijftig,negenenvijftig",
    "lettres_pile": "precies",
    "lettres_minutes_motif": "{m}",
    "semaine": "WEEK {n}",
    "retro_mois": "MAAND",
    "retro_jour": "DAG",
    "retro_annee": "JAAR",
    "retro_heure": "UUR",
    "retro_min": "MIN",
    "cockpit_locale": "LOKAAL",
    "rose_vents": "N,O,Z,W",
    "chargement": "Laden…",
    "web_lourde": "Deze pagina is te zwaar voor dit scherm: de schermbeveiliging slaat haar vandaag over.",
    "web_injoignable": "Site op dit moment onbereikbaar: de schermbeveiliging gaat zonder verder.",
    "web_figeait": "Deze pagina liet het scherm vastlopen: de schermbeveiliging slaat haar vandaag over.",
    "fige": "Gepauzeerd",
    "reprise": "Hervat",
    "vt_auj": "Vandaag",
    "vt_demain": "Morgen",
    "vt_journee": "Hele dag",
    "vt_vide": "Niets gepland",
    "vt_passages": "Geen recente activiteit",
    "vt_instant": "zojuist",
    "vt_min": "{n} min geleden",
    "vt_h": "{n} u geleden",
    "vt_hier": "gisteren",
    "vt_j": "{n} dagen geleden"
  }
}/*i18n-json:fin*/;
// Les catégories (étiquettes de la maison) : ce panneau n'en affiche pas.
const CATEGORIES_I18N = /*i18n-categories*/{"jeux": {"fr": "Jeux", "en": "Games", "de": "Spiele", "es": "Juegos", "it": "Giochi", "nl": "Games"}, "videos": {"fr": "Vidéos", "en": "Videos", "de": "Videos", "es": "Vídeos", "it": "Video", "nl": "Video's"}, "reseaux_sociaux": {"fr": "Réseaux sociaux", "en": "Social media", "de": "Soziale Medien", "es": "Redes sociales", "it": "Social network", "nl": "Sociale media"}, "tv": {"fr": "TV", "en": "TV", "de": "TV", "es": "TV", "it": "TV", "nl": "tv"}, "vod": {"fr": "VOD", "en": "Streaming", "de": "Streaming", "es": "Streaming", "it": "Streaming", "nl": "Streaming"}, "musique": {"fr": "Musique", "en": "Music", "de": "Musik", "es": "Música", "it": "Musica", "nl": "Muziek"}, "ia": {"fr": "IA", "en": "AI", "de": "KI", "es": "IA", "it": "IA", "nl": "AI"}, "internet": {"fr": "Internet", "en": "Internet", "de": "Internet", "es": "Internet", "it": "Internet", "nl": "Internet"}, "travail": {"fr": "Travail", "en": "Work", "de": "Arbeit", "es": "Trabajo", "it": "Lavoro", "nl": "Werk"}, "creation": {"fr": "Création", "en": "Creativity", "de": "Kreativität", "es": "Creatividad", "it": "Creatività", "nl": "Creativiteit"}, "actu_medias": {"fr": "Actu & médias", "en": "News & media", "de": "Nachrichten & Medien", "es": "Noticias y medios", "it": "Notizie e media", "nl": "Nieuws & media"}, "pub_tracage": {"fr": "Pub & traçage", "en": "Ads & tracking", "de": "Werbung & Tracking", "es": "Anuncios y rastreo", "it": "Pubblicità e tracciamento", "nl": "Advertenties & tracking"}, "contournement": {"fr": "Contournement", "en": "Bypass tools", "de": "Umgehung", "es": "Elusión", "it": "Aggiramento", "nl": "Omzeiling"}, "dangereux": {"fr": "Dangereux", "en": "Dangerous", "de": "Gefährlich", "es": "Peligroso", "it": "Pericoloso", "nl": "Gevaarlijk"}, "adulte": {"fr": "Adulte", "en": "Adult", "de": "Erwachsene", "es": "Adultos", "it": "Adulti", "nl": "Volwassenen"}, "pegi_12": {"fr": "PEGI 12", "en": "PEGI 12", "de": "PEGI 12", "es": "PEGI 12", "it": "PEGI 12", "nl": "PEGI 12"}, "pegi_16": {"fr": "PEGI 16", "en": "PEGI 16", "de": "PEGI 16", "es": "PEGI 16", "it": "PEGI 16", "nl": "PEGI 16"}, "pegi_18": {"fr": "PEGI 18", "en": "PEGI 18", "de": "PEGI 18", "es": "PEGI 18", "it": "PEGI 18", "nl": "PEGI 18"}, "systeme": {"fr": "Système", "en": "System", "de": "System", "es": "Sistema", "it": "Sistema", "nl": "Systeem"}, "google": {"fr": "Google", "en": "Google", "de": "Google", "es": "Google", "it": "Google", "nl": "Google"}, "discord": {"fr": "Discord", "en": "Discord", "de": "Discord", "es": "Discord", "it": "Discord", "nl": "Discord"}, "annuaires_discord": {"fr": "Annuaires Discord", "en": "Discord directories", "de": "Discord-Verzeichnisse", "es": "Directorios de Discord", "it": "Directory Discord", "nl": "Discord-gidsen"}, "toujours_autorise": {"fr": "Toujours autorisé", "en": "Always allowed", "de": "Immer erlaubt", "es": "Siempre permitido", "it": "Sempre consentito", "nl": "Altijd toegestaan"}, "ecole": {"fr": "École", "en": "School", "de": "Schule", "es": "Escuela", "it": "Scuola", "nl": "School"}}/*i18n-categories:fin*/;
// ---- i18n:code — le même dans toutes les cartes de Vision (essai/i18n_cartes_test.py le vérifie) ----
// Langue : celle de l'utilisateur Home Assistant (hass.locale.language, sinon hass.language), sur deux lettres ;
// une langue que Vision ne parle pas → l'anglais. Un texte absent → l'anglais, puis le français, puis la clé.
const brutLangue = (hass) =>
  String((hass && ((hass.locale && hass.locale.language) || hass.language)) ||
    (typeof document !== "undefined" && document.documentElement && document.documentElement.lang) || "");
const langueDe = (hass) => {
  const base = brutLangue(hass).toLowerCase().split(/[-_]/)[0];
  return TEXTES[base] ? base : "en";
};
// La locale des dates, heures et nombres : l'étiquette complète (« de-CH ») si sa langue est parlée, sinon la langue.
const localeDe = (hass) => {
  const brut = brutLangue(hass).replace(/_/g, "-");
  const lg = langueDe(hass);
  return brut.toLowerCase().split("-")[0] === lg ? brut : lg;
};
let LANGUE = langueDe(null);
let LOCALE = localeDe(null);
// À l'arrivée de hass : rend la langue (une carte se redessine quand elle change).
const suivreLangue = (hass) => { LANGUE = langueDe(hass); LOCALE = localeDe(hass); return LANGUE; };
// Vocabulaire : Décideur, Demandeur, Lieu — jamais « maison », « parent », « enfant » écrits en dur. Les textes portent
// des champs {decideur} {decideurs} {demandeur} {demandeurs} {lieu} (et {Decideur}… avec majuscule initiale), remplis
// par tr(). Mots par défaut : ceux de pc_parental/vocabulaire.py (DEFAUTS, vérifié par le test) ; {lieu} : le nom du
// lieu configuré quand la carte le connaît, sinon un mot neutre par langue (lieu.DEFAUTS : « Lieu », « Place »…).
const VOCABULAIRE = /*i18n-vocabulaire*/{
  "fr": {"decideur": "décideur", "decideurs": "décideurs", "demandeur": "demandeur", "demandeurs": "demandeurs"},
  "en": {"decideur": "decider", "decideurs": "deciders", "demandeur": "requester", "demandeurs": "requesters"},
  "de": {"decideur": "Entscheider", "decideurs": "Entscheider", "demandeur": "Anfragender", "demandeurs": "Anfragende"},
  "es": {"decideur": "responsable", "decideurs": "responsables", "demandeur": "solicitante", "demandeurs": "solicitantes"},
  "it": {"decideur": "decisore", "decideurs": "decisori", "demandeur": "richiedente", "demandeurs": "richiedenti"},
  "nl": {"decideur": "beslisser", "decideurs": "beslissers", "demandeur": "aanvrager", "demandeurs": "aanvragers"}
}/*i18n-vocabulaire:fin*/;
const LIEU_DEFAUT = {"fr": "Lieu", "en": "Place", "de": "Ort", "es": "Lugar", "it": "Luogo", "nl": "Plek"};  // i18n-ignorer : lieu par défaut de chaque langue, lieu.DEFAUTS (vérifié par le test)
const CHAMPS_VOCABULAIRE = ["decideur", "decideurs", "demandeur", "demandeurs", "lieu"];
// Point unique de surcharge (nom du lieu reçu par la carte, futur réglage « Vocabulaire ») :
// poserVocabulaire({ lieu: "École", mots: { fr: { decideur: "responsable" } } }) — même forme que store.mots_vocabulaire.
const VOCABULAIRE_PERSO = { lieu: "", mots: {} };
const poserVocabulaire = (v) => {
  if (!v) return;
  if (typeof v.lieu === "string") VOCABULAIRE_PERSO.lieu = v.lieu.trim();
  if (v.mots && typeof v.mots === "object") VOCABULAIRE_PERSO.mots = v.mots;
};
const majuscule = (m) => (m ? m[0].toUpperCase() + m.slice(1) : m);
const vocabulaire = () => {
  const mots = Object.assign({}, VOCABULAIRE[LANGUE] || VOCABULAIRE.en);
  const perso = (VOCABULAIRE_PERSO.mots && VOCABULAIRE_PERSO.mots[LANGUE]) || {};
  for (const c of CHAMPS_VOCABULAIRE) if (typeof perso[c] === "string" && perso[c].trim()) mots[c] = perso[c].trim();
  if (!mots.lieu) mots.lieu = VOCABULAIRE_PERSO.lieu || LIEU_DEFAUT[LANGUE] || LIEU_DEFAUT.en;
  const sortie = {};
  for (const [c, v] of Object.entries(mots)) { sortie[c] = v; sortie[majuscule(c)] = majuscule(v); }
  return sortie;
};
const tr = (cle, champs) => {
  let texte = cle;
  for (const lg of [LANGUE, "en", "fr"]) {
    const x = TEXTES[lg] && TEXTES[lg][cle];
    if (typeof x === "string" && x !== "") { texte = x; break; }
  }
  if (!texte.includes("{")) return texte;
  const tous = Object.assign(vocabulaire(), champs || {});
  return texte.replace(/\{(\w+)\}/g, (m, k) => (tous[k] === undefined || tous[k] === null ? m : String(tous[k])));
};
// Pluriels : <clé>_one / <clé>_other, choisis par Intl.PluralRules (en français, 0 et 1 sont au singulier).
const trn = (cle, n, champs) => {
  let forme;
  try { forme = new Intl.PluralRules(LOCALE).select(n); } catch (e) { forme = n === 1 ? "one" : "other"; }
  return tr(`${cle}_${forme === "one" ? "one" : "other"}`, Object.assign({ n }, champs));
};
// Heures, dates et nombres : toujours par la locale, jamais écrits à la main.
const trHeure = (d) => d.toLocaleTimeString(LOCALE, { hour: "2-digit", minute: "2-digit" });
const trDate = (d, options) => new Intl.DateTimeFormat(LOCALE, options).format(d);
const trNombre = (n, options) => new Intl.NumberFormat(LOCALE, options).format(n);
// Les catégories gardent leur nom français dans les données : seul l'affichage les traduit (clé sans accents ni
// casse, « _ » pour le reste ; une catégorie inconnue, ou une maison en français, garde le nom tel qu'il est écrit).
const cleCategorie = (nom) => String(nom ?? "").normalize("NFKD").replace(/[^\x00-\x7f]/g, "").toLowerCase()
  .replace(/[^a-z0-9]+/g, "_").replace(/^_+|_+$/g, "");
const trCat = (nom) => {
  if (LANGUE === "fr") return nom;
  const e = CATEGORIES_I18N[cleCategorie(nom)];
  return (e && (e[LANGUE] || e.en)) || nom;
};
// ---- i18n:fin ----
const CIEL = { sunny: "ciel_sunny", "clear-night": "ciel_clear_night", partlycloudy: "ciel_partlycloudy", cloudy: "ciel_cloudy", rainy: "ciel_rainy", pouring: "ciel_pouring", fog: "ciel_fog", snowy: "ciel_snowy", "snowy-rainy": "ciel_snowy_rainy", lightning: "ciel_orage", "lightning-rainy": "ciel_orage", windy: "ciel_vent", "windy-variant": "ciel_vent", hail: "ciel_hail", exceptional: "ciel_exceptional" };
const pause = (ms) => new Promise((r) => setTimeout(r, ms));

/*
 * Vision — les horloges de l'écran de veille : une par thème, chacune la sienne.
 *
 * Chaque horloge donne son dessin (html) et sa façon d'avancer (tic, appelée chaque seconde).
 * Les couleurs viennent du thème de l'appareil (--v-texte, --v-texte2, --v-accent, --v-carte, --v-ligne).
 */
const p2 = (n) => String(n).padStart(2, "0");
// L'heure « HH:MM » des cadrans : celle de la locale (trHeure) quand elle s'écrit ainsi, en chiffres ; sinon (anglais
// sur douze heures « 06:30 PM », « 18 h 30 »…) sur vingt-quatre heures : les briques, le cockpit et le compte-tours
// ne dessinent que des chiffres et deux-points.
const hm = (d) => { const t = trHeure(d); return /^\d\d:\d\d$/.test(t) ? t : `${p2(d.getHours())}:${p2(d.getMinutes())}`; };
const poser = (el, sel, texte) => { const x = el.querySelector(sel); if (x && x.textContent !== texte) x.textContent = texte; };
const tourner = (el, sel, deg) => { const x = el.querySelector(sel); if (x) x.setAttribute("transform", `rotate(${deg.toFixed(2)})`); };
const pt = (r, deg) => { const a = (deg * Math.PI) / 180; return [(Math.sin(a) * r).toFixed(2), (-Math.cos(a) * r).toFixed(2)]; };
// Un arc de cercle, de l'angle a0 à l'angle a1 (degrés, 0 en haut, sens des aiguilles).
const arc = (r, a0, a1) => { const [x0, y0] = pt(r, a0), [x1, y1] = pt(r, a1); return `M ${x0} ${y0} A ${r} ${r} 0 ${a1 - a0 > 180 ? 1 : 0} 1 ${x1} ${y1}`; };
const traits = (n, r1, r2, classe, saut = 0) => { let o = ""; for (let i = 0; i < n; i++) { if (saut && i % saut === 0) continue; const [x1, y1] = pt(r1, (i * 360) / n), [x2, y2] = pt(r2, (i * 360) / n); o += `<line class="${classe}" x1="${x1}" y1="${y1}" x2="${x2}" y2="${y2}"/>`; } return o; };
const autour = (liste, r, classe) => liste.map((t, i) => { if (!t) return ""; const [x, y] = pt(r, (i * 360) / liste.length); return `<text class="${classe}" x="${x}" y="${y}" text-anchor="middle" dominant-baseline="central">${t}</text>`; }).join("");
const aiguilles = (el, d) => { const s = d.getSeconds(), m = d.getMinutes() + s / 60, h = (d.getHours() % 12) + m / 60; tourner(el, ".ah", h * 30); tourner(el, ".am", m * 6); tourner(el, ".as", s * 6); };
const svg = (contenu, classe = "") => `<svg class="cadran ${classe}" viewBox="-100 -100 200 200">${contenu}</svg>`;

// L'heure en toutes lettres : « onze heures cinquante-deux ». Chaque langue a les siennes, écrites en entier dans
// TEXTES (lettres_heures : les 24 heures ; lettres_minutes : 0 à 59, séparées par des virgules) : en français
// « vingt et une heures », « midi », « pile » ; en anglais sur douze heures « six » / « twenty-five », « oh five ».
const listeTr = (cle) => tr(cle).split(",");
const heureEnLettres = (d) => { const h = d.getHours(), m = d.getMinutes(); const hh = listeTr("lettres_heures")[h] || p2(h); return [hh, m === 0 ? tr("lettres_pile") : tr("lettres_minutes_motif", { m: listeTr("lettres_minutes")[m] || p2(m) })]; };

// Des chiffres faits de briques (5 de large, 7 de haut).
const BRIQUES = { 0: "01110100011001110101110011000101110", 1: "00100011000010000100001000010001110", 2: "01110100010000100010001000100011111", 3: "11110000010000101110000010000111110", 4: "00010001100101010010111110001000010", 5: "11111100001111000001000011000101110", 6: "00110010001000011110100011000101110", 7: "11111000010001000100010000100001000", 8: "01110100011000101110100011000101110", 9: "01110100011000101111000010001001100", ":": "00000001000010000000001000010000000" };
const enBriques = (texte) => {
  let o = "", x0 = 0;
  for (const c of texte) {
    const g = BRIQUES[c] || BRIQUES[0], etroit = c === ":";
    for (let i = 0; i < 35; i++) {
      if (g[i] !== "1") continue;
      const col = i % 5, lig = Math.floor(i / 5);
      if (etroit && col !== 2) continue;
      const x = x0 + (etroit ? 0 : col) * 10, y = lig * 10;
      o += `<rect class="${etroit ? "b2" : "b1"}" x="${x}" y="${y}" width="9" height="9" rx="1.6"/><circle class="bp" cx="${x + 4.5}" cy="${y + 4.5}" r="2.1"/>`;
    }
    x0 += etroit ? 20 : 60;
  }
  return `<svg class="briques" viewBox="-4 -4 ${x0 - 2} 78">${o}</svg>`;
};

const semaine = (d) => { const t = new Date(Date.UTC(d.getFullYear(), d.getMonth(), d.getDate())); const j = t.getUTCDay() || 7; t.setUTCDate(t.getUTCDate() + 4 - j); return Math.ceil(((t - Date.UTC(t.getUTCFullYear(), 0, 1)) / 86400000 + 1) / 7); };
const ROMAINS = ["XII", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI"];

// La pluie de signes du thème Code : chaque colonne descend d'un signe à la fois, le dernier tombé est clair,
// les précédents verts puis s'éteignent. Vingt images par seconde, et plus rien dès que l'horloge quitte l'écran.
const pluie = (el) => {
  const toile = el.querySelector("canvas"), g = toile.getContext("2d"), st = getComputedStyle(el);
  const fond = st.getPropertyValue("--v-fond").trim() || "#030A05", vert = st.getPropertyValue("--v-accent").trim() || "#2BEA6B", clair = st.getPropertyValue("--v-texte").trim() || "#D7FFE0";
  const signes = "ｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾅﾆﾇﾈﾉﾊﾋﾌﾍﾎ0123456789";
  let cols = [], pas = 0, L = -1, H = -1, dernier = 0;
  const neuve = (haut) => ({ y: haut ? -Math.floor(Math.random() * 30) : Math.floor(Math.random() * 60) - 20, v: 0.45 + Math.random() * 0.75, s: "" });
  const mesurer = () => {
    const dpr = window.devicePixelRatio || 1;
    L = toile.clientWidth; H = toile.clientHeight; toile.width = Math.round(L * dpr); toile.height = Math.round(H * dpr);
    g.setTransform(dpr, 0, 0, dpr, 0, 0);
    pas = Math.max(14, Math.round(Math.min(H, L * 1.6) * 0.03));
    cols = Array.from({ length: Math.ceil(L / pas) }, () => neuve(false));
    g.fillStyle = fond; g.fillRect(0, 0, L, H);
  };
  const tour = (t) => {
    if (!el.isConnected) return;
    requestAnimationFrame(tour);
    if (t - dernier < 50) return;
    dernier = t;
    if (toile.clientWidth !== L || toile.clientHeight !== H) mesurer();
    if (!L || !H) return;
    g.globalAlpha = 0.085; g.fillStyle = fond; g.fillRect(0, 0, L, H); g.globalAlpha = 1;
    g.font = `${pas}px Consolas, "Roboto Mono", monospace`; g.textAlign = "center"; g.textBaseline = "top";
    cols.forEach((c, i) => {
      const avant = Math.floor(c.y); c.y += c.v;
      const y = Math.floor(c.y);
      if (y === avant || y < 0) return;
      const x = i * pas;
      if (c.s) { g.fillStyle = fond; g.fillRect(x, avant * pas, pas, pas); g.fillStyle = vert; g.fillText(c.s, x + pas / 2, avant * pas); }
      c.s = signes[Math.floor(Math.random() * signes.length)];
      g.fillStyle = clair; g.fillText(c.s, x + pas / 2, y * pas);
      if (y * pas > H && Math.random() > 0.9) cols[i] = neuve(true);
    });
  };
  requestAnimationFrame(tour);
  return true;
};

const HORLOGES = {
  // Vision : de grands chiffres fins, la date, le ciel.
  defaut: {
    html: (c) => `<div class="heure"></div><div class="date">${c.jour}</div><div class="ciel">${c.ciel}</div>`,
    tic: (el, d) => poser(el, ".heure", hm(d)),
  },
  // Sombre : les chiffres, et la minute qui se remplit en un trait.
  sombre: {
    html: (c) => `<div class="heure"></div><div class="fil"><i></i></div><div class="date">${c.jour}</div>`,
    tic: (el, d) => { poser(el, ".heure", hm(d)); const i = el.querySelector(".fil i"); if (i) i.style.width = `${((d.getSeconds() + 1) / 60) * 100}%`; },
  },
  // Bleu nuit : un cadran de points, des aiguilles fines, un croissant de lune.
  "bleu-nuit": {
    html: (c) => svg(`${traits(60, 92, 94, "t1", 5)}${Array.from({ length: 12 }, (_, i) => { const [x, y] = pt(92, i * 30); return `<circle class="pt${i % 3 === 0 ? " gros" : ""}" cx="${x}" cy="${y}" r="${i % 3 === 0 ? 2.6 : 1.5}"/>`; }).join("")}
      <path class="lune" d="M 0 -52 a 11 11 0 1 0 9 17 a 8.5 8.5 0 1 1 -9 -17"/>
      <line class="ah" x1="0" y1="8" x2="0" y2="-48"/><line class="am" x1="0" y1="10" x2="0" y2="-76"/><line class="as" x1="0" y1="14" x2="0" y2="-84"/><circle class="axe" r="3"/>`) + `<div class="date">${c.jour}</div>`,
    tic: aiguilles,
  },
  // Beige : l'heure écrite en toutes lettres, comme dans un livre.
  beige: {
    html: (c) => `<div class="mots"><span class="m1"></span><span class="m2"></span></div><div class="filet"></div><div class="date"><b class="hm"></b> · ${c.jour}</div>`,
    tic: (el, d) => { const [a, b] = heureEnLettres(d); poser(el, ".m1", a); poser(el, ".m2", b); poser(el, ".hm", hm(d)); },
  },
  // Sauge : l'heure sur les minutes, en deux étages.
  sauge: {
    html: (c) => `<div class="etages"><span class="hh"></span><span class="mm"></span></div><div class="date">${c.jour}</div>`,
    tic: (el, d) => { poser(el, ".hh", p2(d.getHours())); poser(el, ".mm", p2(d.getMinutes())); },
  },
  // Salon : la pendule murale, quatre grands chiffres et des aiguilles d'acier.
  salon: {
    html: (c) => svg(`<circle class="bord" r="97"/>${traits(12, 82, 92, "t2", 3)}${autour(["12", "", "", "3", "", "", "6", "", "", "9", "", ""], 74, "ch")}
      <path class="ah" d="M -3.2 10 L -2.2 -46 L 2.2 -46 L 3.2 10 Z"/><path class="am" d="M -2.4 12 L -1.5 -74 L 1.5 -74 L 2.4 12 Z"/><line class="as" x1="0" y1="16" x2="0" y2="-80"/><circle class="axe" r="4.5"/>`) + `<div class="date">${c.jour}</div>`,
    tic: aiguilles,
  },
  // Rose poudré : un anneau qui se ferme au fil de l'heure, les chiffres au centre.
  "rose-poudre": {
    html: (c) => `<div class="rond">${svg(`<circle class="piste" r="90"/><path class="tour" d=""/>`)}<div class="dedans"><div class="heure"></div><div class="date">${c.jourCourt}</div></div></div>`,
    tic: (el, d) => { poser(el, ".heure", hm(d)); const t = el.querySelector(".tour"); if (t) t.setAttribute("d", arc(90, 0, Math.max(0.5, Math.min(359.5, (d.getMinutes() * 60 + d.getSeconds()) / 10)))); },
  },
  // Tactique : des chiffres penchés, coupés au couteau, une barre d'accent.
  tactique: {
    html: (c) => `<div class="bloc"><div class="rep">// ${c.jourCourt.toUpperCase()}</div><div class="heure"></div><div class="barres"><i></i><i></i><i></i></div><div class="sec"></div></div>`,
    tic: (el, d) => { poser(el, ".heure", hm(d)); poser(el, ".sec", `${p2(d.getSeconds())} S`); },
  },
  // Briques : les chiffres montés brique par brique.
  briques: {
    html: (c) => `<div class="mur"></div><div class="date">${c.jour}</div>`,
    tic: (el, d) => { const m = el.querySelector(".mur"), t = hm(d); if (m && m.dataset.t !== t) { m.dataset.t = t; m.innerHTML = enBriques(t); } },
  },
  // Corsaire : une rose des vents pour cadran.
  corsaire: {
    html: (c) => svg(`<circle class="bord" r="96"/><circle class="bord fin" r="84"/>${traits(72, 84, 90, "t1")}${traits(12, 78, 90, "t2")}
      <path class="rose" d="M 0 -70 L 9 -9 L 70 0 L 9 9 L 0 70 L -9 9 L -70 0 L -9 -9 Z"/><path class="rose2" d="M 0 -70 L 0 0 L 9 -9 Z M 70 0 L 0 0 L 9 9 Z M 0 70 L 0 0 L -9 9 Z M -70 0 L 0 0 L -9 -9 Z"/>
      ${autour(listeTr("rose_vents"), 60, "ch")}
      <path class="ah" d="M 0 12 L -3.5 0 L 0 -44 L 3.5 0 Z"/><path class="am" d="M 0 14 L -2.6 0 L 0 -72 L 2.6 0 Z"/><line class="as" x1="0" y1="16" x2="0" y2="-80"/><circle class="axe" r="3.6"/>`) + `<div class="date"><b class="hm"></b> · ${c.jour}</div>`,
    tic: (el, d) => { aiguilles(el, d); poser(el, ".hm", hm(d)); },
  },
  // Royale : des chiffres épais et penchés, en dégradé, posés sur leur ombre.
  royale: {
    html: (c) => `<div class="pile"><div class="heure ombre"></div><div class="heure face"></div></div><div class="pastille">${c.jour}</div>`,
    tic: (el, d) => { const t = hm(d); el.querySelectorAll(".heure").forEach((x) => { if (x.textContent !== t) x.textContent = t; }); },
  },
  // Circuit : un compte-tours dont l'aiguille suit les minutes, zone rouge en fin d'heure.
  circuit: {
    html: (c) => svg(`<path class="piste" d="${arc(86, -120, 120)}"/><path class="rouge" d="${arc(86, 80, 120)}"/>
      ${Array.from({ length: 13 }, (_, i) => { const a = -120 + i * 20, [x1, y1] = pt(74, a), [x2, y2] = pt(86, a), [tx, ty] = pt(62, a); return `<line class="t2" x1="${x1}" y1="${y1}" x2="${x2}" y2="${y2}"/>${i % 2 === 0 ? `<text class="ch" x="${tx}" y="${ty}" text-anchor="middle" dominant-baseline="central">${i * 5}</text>` : ""}`; }).join("")}
      <g class="aig"><path d="M -2.4 10 L -1 -80 L 1 -80 L 2.4 10 Z"/></g><circle class="axe" r="7"/>
      <text class="num" x="0" y="60" text-anchor="middle"></text><text class="unite" x="0" y="74" text-anchor="middle">MIN</text>`) + `<div class="date">${c.jour}</div>`,
    tic: (el, d) => { tourner(el, ".aig", -120 + ((d.getMinutes() + d.getSeconds() / 60) / 60) * 240); poser(el, ".num", hm(d)); },
  },
  // Cockpit : un instrument de bord sur vingt-quatre heures, heure locale et heure universelle.
  cockpit: {
    html: (c) => svg(`<circle class="bord" r="97"/>${traits(120, 88, 92, "t1", 5)}${traits(24, 82, 92, "t2")}${autour(["24", "", "", "3", "", "", "6", "", "", "9", "", "", "12", "", "", "15", "", "", "18", "", "", "21", "", ""], 71, "ch")}
      <g class="a24"><path d="M 0 -80 L -4 -66 L 4 -66 Z"/></g>
      <text class="num" x="0" y="-8" text-anchor="middle"></text><text class="unite" x="0" y="10" text-anchor="middle">${tr("cockpit_locale")}</text>
      <text class="utc" x="0" y="34" text-anchor="middle"></text>`) + `<div class="date">${c.jour}</div>`,
    tic: (el, d) => { tourner(el, ".a24", ((d.getHours() * 60 + d.getMinutes()) / 1440) * 360); poser(el, ".num", `${hm(d)}:${p2(d.getSeconds())}`); poser(el, ".utc", `${p2(d.getUTCHours())}:${p2(d.getUTCMinutes())} UTC`); },
  },
  // Bourse : des chiffres de téléscripteur, et les soixante secondes de la minute en bâtons.
  bourse: {
    html: (c) => `<div class="tele"><span class="heure"></span><span class="sec"></span></div><div class="batons">${Array.from({ length: 60 }, (_, i) => `<i style="height:${(30 + 62 * Math.abs(Math.sin(i * 1.7) * Math.cos(i * 0.6))).toFixed(0)}%"></i>`).join("")}</div><div class="date">${c.jourCourt.toUpperCase()} · ${tr("semaine", { n: trNombre(semaine(new Date())) })}</div>`,
    tic: (el, d) => { poser(el, ".heure", hm(d)); poser(el, ".sec", `:${p2(d.getSeconds())}`); const s = d.getSeconds(), b = el.querySelector(".batons"); if (b && b.dataset.s !== String(s)) { b.dataset.s = String(s); [...b.children].forEach((x, i) => x.classList.toggle("fait", i <= s)); } },
  },
  // Écrin : chiffres romains, aiguilles fines, double filet.
  ecrin: {
    html: (c) => svg(`<circle class="bord" r="97"/><circle class="bord fin" r="92"/>${traits(60, 80, 84, "t1", 5)}${autour(ROMAINS, 70, "ch")}
      <text class="marque" x="0" y="-36" text-anchor="middle">VISION</text>
      <path class="ah" d="M 0 10 L -2.6 -8 L 0 -46 L 2.6 -8 Z"/><path class="am" d="M 0 12 L -1.9 -10 L 0 -74 L 1.9 -10 Z"/><line class="as" x1="0" y1="18" x2="0" y2="-80"/><circle class="axe" r="2.6"/>`) + `<div class="date">${c.jour}</div>`,
    tic: aiguilles,
  },
  // Prairie : la course du soleil sur la journée, les chiffres dessous.
  prairie: {
    html: (c) => `<svg class="ciel-arc" viewBox="-110 -100 220 112"><path class="piste" d="${arc(90, -90, 90)}"/><line class="sol" x1="-108" y1="0" x2="108" y2="0"/><g class="astre"><circle r="9"/></g></svg><div class="heure"></div><div class="date">${c.jour}</div>`,
    tic: (el, d) => {
      poser(el, ".heure", hm(d));
      // De 6 h à 22 h le soleil va d'un bord à l'autre ; la nuit, c'est la lune qui fait le trajet.
      const h = d.getHours() + d.getMinutes() / 60, jour = h >= 6 && h < 22, part = jour ? (h - 6) / 16 : ((h + 2) % 24) / 8;
      const [x, y] = pt(90, -90 + part * 180), a = el.querySelector(".astre");
      if (a) { a.setAttribute("transform", `translate(${x} ${y})`); a.classList.toggle("lune", !jour); }
    },
  },
  // Large : les chiffres au-dessus de la houle.
  large: {
    html: (c) => `<div class="heure"></div><div class="date">${c.jour}</div><svg class="houle" viewBox="0 0 400 40" preserveAspectRatio="none"><path class="v1" d="M 0 20 Q 25 6 50 20 T 100 20 T 150 20 T 200 20 T 250 20 T 300 20 T 350 20 T 400 20 T 450 20 T 500 20"/><path class="v2" d="M 0 28 Q 25 16 50 28 T 100 28 T 150 28 T 200 28 T 250 28 T 300 28 T 350 28 T 400 28 T 450 28 T 500 28"/></svg>`,
    tic: (el, d) => poser(el, ".heure", hm(d)),
  },
  // Sommet : une ligne de crête, l'heure au-dessus des cimes.
  sommet: {
    html: (c) => `<div class="heure"></div><div class="date">${c.jour}</div><svg class="crete" viewBox="0 0 400 90" preserveAspectRatio="none"><path class="loin" d="M 0 90 L 0 62 L 46 40 L 84 58 L 132 22 L 176 54 L 214 36 L 262 60 L 310 30 L 352 52 L 400 34 L 400 90 Z"/><path class="pres" d="M 0 90 L 0 76 L 60 54 L 104 72 L 168 38 L 190 50 L 206 42 L 252 74 L 300 56 L 344 76 L 400 58 L 400 90 Z"/><path class="neige" d="M 168 38 L 152 47 L 164 46 L 172 52 L 180 45 L 190 50 Z"/></svg>`,
    tic: (el, d) => poser(el, ".heure", hm(d)),
  },
  // Sous-bois : les cernes d'un arbre, un par aiguille (heures, minutes, secondes).
  "sous-bois": {
    html: (c) => `<div class="rond">${svg(`<circle class="piste" r="92"/><circle class="piste" r="78"/><circle class="piste" r="64"/><path class="c1" d=""/><path class="c2" d=""/><path class="c3" d=""/>`)}<div class="dedans"><div class="heure"></div><div class="date">${c.jourCourt}</div></div></div>`,
    tic: (el, d) => {
      poser(el, ".heure", hm(d));
      const borne = (v) => Math.max(0.5, Math.min(359.5, v));
      const s = d.getSeconds(), m = d.getMinutes() + s / 60, h = (d.getHours() % 12) + m / 60;
      const dessiner = (sel, r, a) => { const x = el.querySelector(sel); if (x) x.setAttribute("d", arc(r, 0, borne(a))); };
      dessiner(".c1", 92, h * 30); dessiner(".c2", 78, m * 6); dessiner(".c3", 64, (s + 1) * 6);
    },
  },
  // Code : une pluie de signes sur tout l'écran, signe après signe, la tête de chaque colonne plus claire.
  code: {
    html: (c) => `<canvas class="pluie"></canvas><div class="voile"></div><div class="heure"></div><div class="date">&gt; ${c.jourCourt.toUpperCase()} <b class="sec"></b><u>_</u></div>`,
    tic: (el, d) => { poser(el, ".heure", hm(d)); poser(el, ".sec", `:${p2(d.getSeconds())}`); if (!el._pluie) el._pluie = pluie(el); },
  },
  // Plume : une plume qui flotte au-dessus de l'heure, un banc dessous.
  plume: {
    html: (c) => `<svg class="vol" viewBox="0 0 60 30"><path d="M 2 26 C 14 20 30 6 56 3 C 50 14 34 24 12 26 Z"/><path class="tige" d="M 2 27 C 18 22 36 12 54 4"/></svg><div class="heure"></div><div class="banc"></div><div class="date">${c.jour}</div>`,
    tic: (el, d) => poser(el, ".heure", hm(d)),
  },
  // Galaxie : des chiffres en contour sur un champ d'étoiles.
  galaxie: {
    html: (c) => `<div class="etoiles">${Array.from({ length: 70 }, (_, i) => `<i style="left:${((Math.sin(i * 12.9898) * 43758.5453) % 1 * 50 + 50).toFixed(1)}%;top:${((Math.sin(i * 78.233) * 12543.123) % 1 * 50 + 50).toFixed(1)}%;opacity:${(0.25 + (i % 7) / 9).toFixed(2)};width:${1 + (i % 3)}px;height:${1 + (i % 3)}px"></i>`).join("")}</div><div class="heure"></div><div class="date">${c.jour}</div>`,
    tic: (el, d) => poser(el, ".heure", hm(d)),
  },
  // Rétro 85 : le tableau de bord d'une machine à voyager, case par case.
  "retro-85": {
    html: (c) => `<div class="tableau">${[["mois", "retro_mois"], ["jour", "retro_jour"], ["an", "retro_annee"], ["hh", "retro_heure"], ["mm", "retro_min"]].map(([k, t]) => `<div class="case ${k}"><b></b><span>${tr(t)}</span></div>`).join("")}</div><div class="date">${c.jour}</div>`,
    tic: (el, d) => {
      poser(el, ".mois b", trDate(d, { month: "short" }).replace(".", "").slice(0, 4).toUpperCase());
      poser(el, ".jour b", p2(d.getDate())); poser(el, ".an b", String(d.getFullYear())); poser(el, ".hh b", p2(d.getHours())); poser(el, ".mm b", p2(d.getMinutes()));
    },
  },
  // Grimoire : une heure de vieux livre, entre deux filets étoilés.
  grimoire: {
    html: (c) => `<div class="filet"><span>✦</span></div><div class="heure"></div><div class="filet"><span>✦</span></div><div class="date">${c.jour}</div>`,
    tic: (el, d) => poser(el, ".heure", hm(d)),
  },
  // Féerie : une voûte d'étoiles qui s'allument l'une après l'autre.
  feerie: {
    html: (c) => `<svg class="voute" viewBox="-110 -100 220 70">${Array.from({ length: 11 }, (_, i) => { const [x, y] = pt(92, -60 + i * 12), r = i % 2 ? 5 : 8; return `<path class="et" transform="translate(${x} ${y})" d="M 0 ${-r} Q 0 0 ${r} 0 Q 0 0 0 ${r} Q 0 0 ${-r} 0 Q 0 0 0 ${-r} Z"/>`; }).join("")}</svg><div class="heure"></div><div class="date">${c.jour}</div>`,
    tic: (el, d) => { poser(el, ".heure", hm(d)); const s = d.getSeconds() % 11; el.querySelectorAll(".et").forEach((x, i) => x.classList.toggle("vif", i === s || i === (s + 5) % 11)); },
  },
  // Banquise : un flocon qui tourne d'un cran par seconde, des chiffres de givre.
  banquise: {
    html: (c) => `<svg class="flocon" viewBox="-50 -50 100 100"><g class="tour">${Array.from({ length: 6 }, (_, i) => `<g transform="rotate(${i * 60})"><line x1="0" y1="0" x2="0" y2="-44"/><line x1="0" y1="-20" x2="-9" y2="-29"/><line x1="0" y1="-20" x2="9" y2="-29"/><line x1="0" y1="-32" x2="-6" y2="-38"/><line x1="0" y1="-32" x2="6" y2="-38"/></g>`).join("")}</g></svg><div class="heure"></div><div class="date">${c.jour}</div>`,
    tic: (el, d) => { poser(el, ".heure", hm(d)); tourner(el, ".tour", d.getSeconds() * 6); },
  },
  // Lagon : une spirale de vague, une perle qui en fait le tour en une heure.
  lagon: {
    html: (c) => `<div class="rond">${svg(`<path class="spire" d="M ${Array.from({ length: 90 }, (_, i) => { const a = i * 12, [x, y] = pt(16 + i * 0.88, a); return `${x} ${y}`; }).join(" L ")}"/><g class="perle"><circle r="5"/></g>`)}<div class="dedans"><div class="heure"></div><div class="date">${c.jourCourt}</div></div></div>`,
    tic: (el, d) => { poser(el, ".heure", hm(d)); const [x, y] = pt(95, (d.getMinutes() + d.getSeconds() / 60) * 6), p = el.querySelector(".perle"); if (p) p.setAttribute("transform", `translate(${x} ${y})`); },
  },
  // Étincelle : des chiffres ronds et un éclair à la place des deux-points.
  etincelle: {
    html: (c) => `<div class="duo"><span class="hh"></span><svg class="eclair" viewBox="0 0 24 40"><path d="M 14 0 L 2 22 H 11 L 8 40 L 22 15 H 13 Z"/></svg><span class="mm"></span></div><div class="pastille">${c.jour}</div>`,
    tic: (el, d) => { poser(el, ".hh", p2(d.getHours())); poser(el, ".mm", p2(d.getMinutes())); el.classList.toggle("flash", d.getSeconds() % 2 === 0); },
  },
};

/*
 * Les styles d'horloge choisis sur l'appareil (« hstyle » dans l'adresse) : les mêmes que la veille dessinée par
 * l'appli Android (HorlogesVeille.kt) et par la veille du PC, sous les mêmes noms. Un style ne fixe que la forme :
 * les couleurs restent celles du thème (--v-texte, --v-texte2, --v-accent, --v-carte, --v-ligne, --v-fond).
 * Au plus une mise à jour par seconde ; le balayage de la trotteuse, l'anneau des secondes et la bascule des
 * palettes sont des transitions CSS (le navigateur les anime seul, sans script à chaque image).
 */
const STYLES_HORLOGE = ["anneau", "fine", "grasse", "analogique", "classique", "mots", "flip", "empilee"];
// Les anciens noms gardent le choix de chacun.
const STYLES_ANCIENS = { chiffres: "fine", aiguilles: "analogique" };
const styleHorloge = (v) => { const s = Object.prototype.hasOwnProperty.call(STYLES_ANCIENS, v) ? STYLES_ANCIENS[v] : v; return STYLES_HORLOGE.includes(s) ? s : ""; };

// Le style « mots » écrit l'heure avec heureEnLettres (les TEXTES de la page, dans la langue de Home Assistant).

// Un angle qui ne fait qu'avancer (secondes du jour × 6°) : la transition CSS ne repart jamais à l'envers ; au
// passage de minuit, une seule mise à jour sans transition.
const avancer = (el, sel, deg) => {
  const x = el.querySelector(sel); if (!x) return;
  const avant = x._deg;
  x.style.transition = avant === undefined || deg < avant ? "none" : "";
  x._deg = deg; x.style.transform = `rotate(${deg}deg)`;
};
const secondesDuJour = (d) => d.getHours() * 3600 + d.getMinutes() * 60 + d.getSeconds();
const aiguillesStyle = (el, d, trotteuse) => {
  const s = d.getSeconds(), m = d.getMinutes() + s / 60, h = (d.getHours() % 12) + m / 60;
  tourner(el, ".ah", h * 30); tourner(el, ".am", m * 6);
  if (trotteuse) avancer(el, ".as", secondesDuJour(d) * 6);
};
// Une palette : la moitié haute de l'ancienne valeur se rabat, puis la moitié basse de la nouvelle descend.
const palette = () => `<div class="pal" data-v=""><div class="moi h"><span></span></div><div class="moi b"><span></span></div><div class="volet h"><span></span></div><div class="volet b"><span></span></div></div>`;
const basculer = (p, v) => {
  if (!p || p.dataset.v === v) return;
  const avant = p.dataset.v; p.dataset.v = v;
  const [hh, bb, vh, vb] = [...p.children].map((x) => x.firstChild);
  hh.textContent = v; vb.textContent = v;
  if (!avant) { bb.textContent = v; vh.textContent = v; return; }
  bb.textContent = avant; vh.textContent = avant;
  p.classList.remove("bascule"); void p.offsetWidth; p.classList.add("bascule");
  clearTimeout(p._fin); p._fin = setTimeout(() => { bb.textContent = v; p.classList.remove("bascule"); }, 700);
};

const HORLOGES_STYLES = {
  // L'anneau des secondes autour de l'heure.
  anneau: {
    html: (c) => `<div class="rond">${svg(`<circle class="piste" r="90"/><circle class="prog" r="90" pathLength="60" transform="rotate(-90)"/><g class="point"><circle cy="-90" r="3.4"/></g>`)}<div class="dedans"><div class="heure"><span class="hh"></span><b>:</b><span class="mm"></span></div></div></div><div class="date">${c.jour}</div><div class="ciel">${c.ciel}</div>`,
    tic: (el, d) => {
      poser(el, ".hh", p2(d.getHours())); poser(el, ".mm", p2(d.getMinutes()));
      const s = d.getSeconds(), p = el.querySelector(".prog");
      // Fin de minute : l'anneau se vide d'un coup, puis se remplit seconde après seconde.
      if (p) { p.style.transition = s === 0 ? "none" : ""; p.style.strokeDashoffset = String(60 - s); }
      avancer(el, ".point", secondesDuJour(d) * 6);
    },
  },
  // Numérique fine : grands chiffres fins, un trait d'accent qui se remplit avec les secondes.
  fine: {
    html: (c) => `<div class="heure"><span class="hh"></span><b>:</b><span class="mm"></span></div><div class="fil"><i></i></div><div class="date">${c.jour}</div><div class="ciel">${c.ciel}</div>`,
    tic: (el, d) => { poser(el, ".hh", p2(d.getHours())); poser(el, ".mm", p2(d.getMinutes())); const i = el.querySelector(".fil i"); if (i) { i.style.transition = d.getSeconds() === 0 ? "none" : ""; i.style.width = `${(d.getSeconds() / 60) * 100}%`; } },
  },
  // Numérique grasse : des chiffres très épais.
  grasse: {
    html: (c) => `<div class="heure"><span class="hh"></span><b>:</b><span class="mm"></span></div><div class="date">${c.jour}</div><div class="ciel">${c.ciel}</div>`,
    tic: (el, d) => { poser(el, ".hh", p2(d.getHours())); poser(el, ".mm", p2(d.getMinutes())); },
  },
  // Analogique minimaliste : un cadran nu, deux aiguilles fines.
  analogique: {
    html: (c) => svg(`<circle class="nu" r="92"/><circle class="midi" cx="0" cy="-83" r="1.6"/><line class="ah" x1="0" y1="0" x2="0" y2="-50"/><line class="am" x1="0" y1="0" x2="0" y2="-77"/><circle class="axe" r="2.4"/>`) + `<div class="date">${c.jour}</div><div class="ciel">${c.ciel}</div>`,
    tic: (el, d) => aiguillesStyle(el, d, false),
  },
  // Analogique classique : graduations, chiffres, trotteuse qui balaie.
  classique: {
    html: (c) => svg(`<circle class="bord" r="96"/>${traits(60, 86, 91, "t1", 5)}${traits(12, 80, 91, "t2")}${autour(["12", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11"], 68, "ch")}
      <path class="ah" d="M -3 8 L -2.2 -46 L 2.2 -46 L 3 8 Z"/><path class="am" d="M -2.2 8 L -1.4 -74 L 1.4 -74 L 2.2 8 Z"/><line class="as" x1="0" y1="16" x2="0" y2="-80"/><circle class="axe" r="3.4"/>`) + `<div class="date">${c.jour}</div><div class="ciel">${c.ciel}</div>`,
    tic: (el, d) => aiguillesStyle(el, d, true),
  },
  // L'heure en toutes lettres : l'heure à l'encre, les minutes à l'accent, le moment de la journée plus petit.
  mots: {
    html: (c) => `<div class="mots"><span class="m0"></span><span class="m1"></span><span class="m2"></span><span class="m3"></span></div><div class="date">${c.jour}</div><div class="ciel">${c.ciel}</div>`,
    tic: (el, d) => { const [h, m] = heureEnLettres(d); poser(el, ".m0", ""); poser(el, ".m1", h); poser(el, ".m2", m); poser(el, ".m3", ""); },
  },
  // Les palettes qui basculent quand la minute change.
  flip: {
    html: (c) => `<div class="palettes">${palette()}<b class="deux"><i></i><i></i></b>${palette()}</div><div class="date">${c.jour}</div><div class="ciel">${c.ciel}</div>`,
    tic: (el, d) => { const p = el.querySelectorAll(".pal"); basculer(p[0], p2(d.getHours())); basculer(p[1], p2(d.getMinutes())); },
  },
  // L'heure au-dessus des minutes, une barre de secondes sur le côté.
  empilee: {
    html: (c) => `<div class="pile"><div class="etages"><span class="hh"></span><span class="mm"></span></div><div class="barre"><i></i></div></div><div class="date">${c.jour}</div><div class="ciel">${c.ciel}</div>`,
    tic: (el, d) => { poser(el, ".hh", p2(d.getHours())); poser(el, ".mm", p2(d.getMinutes())); const i = el.querySelector(".barre i"); if (i) { i.style.transition = d.getSeconds() === 0 ? "none" : ""; i.style.height = `${(d.getSeconds() / 60) * 100}%`; } },
  },
};
for (const [nom, h] of Object.entries(HORLOGES_STYLES)) HORLOGES["s-" + nom] = h;

const CSS_STYLES_HORLOGE = `
  .h-s-anneau .rond { width: min(66vh, 80vw); height: min(66vh, 80vw); }
  .h-s-anneau .piste { stroke: var(--v-accent); opacity: .25; stroke-width: 1.4; }
  .h-s-anneau .prog { fill: none; stroke: var(--v-accent); stroke-width: 1.4; stroke-linecap: round; stroke-dasharray: 60 60; stroke-dashoffset: 60; transition: stroke-dashoffset 1s linear; }
  .h-s-anneau .point, .h-s-classique .as { transition: transform 1s linear; }
  .h-s-anneau .point circle { fill: var(--v-accent); }
  .h-s-anneau .rond .heure { font-size: min(19vh, 23vw); font-weight: 200; }
  .h-s-anneau .heure b, .h-s-fine .heure b { color: var(--v-accent); font-weight: inherit; animation: battre 1s ease-in-out infinite; }
  @keyframes battre { 50% { opacity: .35; } }
  .h-s-fine .heure { font-size: min(36vh, 32vw); font-weight: 200; letter-spacing: -.01em; }
  .h-s-fine .fil { width: min(46vh, 52vw); height: 4px; border-radius: 2px; background: var(--v-ligne); overflow: hidden; margin-top: 1vh; }
  .h-s-fine .fil i { display: block; height: 100%; width: 0; background: var(--v-accent); transition: width 1s linear; }
  .h-s-grasse .heure { font-size: min(38vh, 30vw); font-weight: 900; letter-spacing: -.02em; font-family: "Segoe UI Black", "Arial Black", Roboto, sans-serif; }
  .h-s-grasse .heure b { color: var(--v-accent); }
  .h-s-analogique .nu { fill: none; stroke: var(--v-texte2); stroke-width: .8; opacity: .3; }
  .h-s-analogique .midi { fill: var(--v-accent); }
  .h-s-analogique .ah { stroke-width: 2.6; } .h-s-analogique .am { stroke-width: 1.6; }
  .h-s-classique .bord { stroke: var(--v-accent); stroke-width: 1.2; opacity: .6; }
  .h-s-classique .ch { font-size: 14px; font-weight: 600; }
  .h-s-classique .t2 { stroke-width: 2.4; }
  .h-s-mots { font-family: Georgia, "Times New Roman", serif; }
  .h-s-mots .mots { display: flex; flex-direction: column; align-items: center; text-align: center; line-height: 1.08; max-width: 90vw; }
  .h-s-mots .m0, .h-s-mots .m2 { font-size: min(11vh, 9vw); color: var(--v-accent); font-style: italic; }
  .h-s-mots .m1 { font-size: min(13vh, 11vw); font-weight: 600; }
  .h-s-mots .m3 { font-size: min(4.6vh, 5vw); color: var(--v-texte2); margin-top: 1.6vh; }
  .h-s-mots .mots span:empty { display: none; }
  .h-s-mots .mots + .date { margin-top: 4vh; }
  .h-s-flip .palettes { display: flex; align-items: center; gap: min(2.4vh, 2vw); --ph: min(46vh, 34vw); }
  .h-s-flip .pal { position: relative; width: calc(var(--ph) * .86); height: var(--ph); perspective: calc(var(--ph) * 4); font-size: calc(var(--ph) * .74); font-weight: 500; }
  .h-s-flip .pal > div { position: absolute; left: 0; right: 0; height: 50%; overflow: hidden; background: var(--v-carte); backface-visibility: hidden; }
  .h-s-flip .pal span { display: block; height: var(--ph); line-height: var(--ph); text-align: center; font-variant-numeric: tabular-nums; }
  .h-s-flip .pal .h { top: 0; border-radius: 1.4vh 1.4vh 0 0; } .h-s-flip .pal .b { bottom: 0; border-radius: 0 0 1.4vh 1.4vh; }
  .h-s-flip .pal .b span { transform: translateY(-50%); }
  .h-s-flip .pal .volet { visibility: hidden; z-index: 2; }
  .h-s-flip .pal .volet.h { transform-origin: 50% 100%; } .h-s-flip .pal .volet.b { transform-origin: 50% 0; transform: rotateX(90deg); }
  .h-s-flip .pal.bascule .volet { visibility: visible; }
  .h-s-flip .pal.bascule .volet.h { animation: rabat-haut .3s ease-in forwards; }
  .h-s-flip .pal.bascule .volet.b { animation: rabat-bas .3s .3s ease-out forwards; }
  @keyframes rabat-haut { from { transform: rotateX(0); filter: brightness(1); } to { transform: rotateX(-90deg); filter: brightness(.7); } }
  @keyframes rabat-bas { from { transform: rotateX(90deg); filter: brightness(.7); } to { transform: rotateX(0); filter: brightness(1); } }
  .h-s-flip .pal::after { content: ""; position: absolute; left: 0; right: 0; top: calc(50% - 1px); height: 2px; background: var(--v-fond); z-index: 3; }
  .h-s-flip .deux { display: flex; flex-direction: column; gap: calc(var(--ph) * .22); }
  .h-s-flip .deux i { width: calc(var(--ph) * .06); height: calc(var(--ph) * .06); border-radius: 50%; background: var(--v-accent); }
  .h-s-flip .palettes + .date { margin-top: 4vh; }
  .h-s-empilee .pile { display: flex; align-items: center; gap: 3vh; }
  .h-s-empilee .etages { display: flex; flex-direction: column; align-items: center; line-height: .92; font-size: min(26vh, 30vw); font-weight: 500; }
  .h-s-empilee .mm { color: var(--v-accent); }
  .h-s-empilee .barre { position: relative; width: 5px; height: min(42vh, 46vw); border-radius: 3px; background: var(--v-ligne); overflow: hidden; }
  .h-s-empilee .barre i { position: absolute; left: 0; right: 0; bottom: 0; height: 0; background: var(--v-accent); transition: height 1s linear; }
`;

const CSS_HORLOGES = `
  .horloge .cadran { width: min(62vh, 78vw); height: min(62vh, 78vw); overflow: visible; }
  .horloge .cadran + .date { margin-top: 3.5vh; }
  .h-circuit .cadran { margin-top: 6vh; } .h-circuit .cadran + .date { margin-top: 0; }
  .horloge svg text { font-family: inherit; fill: var(--v-texte); }
  .horloge .t1 { stroke: var(--v-texte2); stroke-width: .7; opacity: .7; }
  .horloge .t2 { stroke: var(--v-texte); stroke-width: 1.8; }
  .horloge .ah, .horloge .am { stroke: var(--v-texte); stroke-width: 3; stroke-linecap: round; fill: var(--v-texte); }
  .horloge .am { stroke-width: 2; }
  .horloge .as { stroke: var(--v-accent); stroke-width: 1; stroke-linecap: round; }
  .horloge .axe { fill: var(--v-accent); }
  .horloge .bord { fill: none; stroke: var(--v-texte); stroke-width: 1.6; }
  .horloge .bord.fin { stroke-width: .6; stroke: var(--v-accent); }
  .horloge .piste { fill: none; stroke: var(--v-ligne); stroke-width: 3; stroke-linecap: round; }
  .horloge .rond { position: relative; width: min(62vh, 78vw); height: min(62vh, 78vw); }
  .horloge .rond .cadran { position: absolute; inset: 0; width: 100%; height: 100%; }
  .horloge .rond .dedans { position: absolute; inset: 0; display: flex; flex-direction: column; align-items: center; justify-content: center; }
  .horloge .rond .heure { font-size: min(15vh, 19vw); }
  .horloge .rond .date { font-size: min(3vh, 4vw); margin-top: 1.2vh; }

  .h-sombre .heure { font-weight: 200; letter-spacing: .02em; }
  .h-sombre .fil { width: min(46vw, 70vh); height: 3px; background: var(--v-ligne); margin-top: 3vh; border-radius: 2px; overflow: hidden; }
  .h-sombre .fil i { display: block; height: 100%; width: 0; background: var(--v-accent); transition: width 1s linear; }

  .h-bleu-nuit .pt { fill: var(--v-texte2); } .h-bleu-nuit .pt.gros { fill: var(--v-accent); }
  .h-bleu-nuit .lune { fill: var(--v-accent); opacity: .85; }
  .h-bleu-nuit .ah { stroke-width: 2.6; } .h-bleu-nuit .am { stroke-width: 1.6; }

  .h-beige { font-family: Georgia, "Times New Roman", serif; }
  .h-beige .mots { display: flex; flex-direction: column; align-items: center; line-height: 1.06; }
  .h-beige .m1 { font-size: min(12vh, 11vw); font-style: italic; }
  .h-beige .m2 { font-size: min(12vh, 11vw); color: var(--v-accent); }
  .h-beige .filet { width: 9vh; height: 1.5px; background: var(--v-texte2); margin: 4vh 0 0; }
  .h-beige .date { font-style: italic; } .h-beige .date b { font-style: normal; font-weight: 400; color: var(--v-texte); }

  .h-sauge .etages { display: flex; flex-direction: column; align-items: center; line-height: .86; font-weight: 700; font-size: min(34vh, 40vw); letter-spacing: -.03em; }
  .h-sauge .mm { color: var(--v-accent); }
  .h-sauge .date { margin-top: 3vh; }

  .h-salon .bord { stroke-width: 3.4; }
  .h-salon .ch { font-size: 22px; font-weight: 300; fill: var(--v-accent); }
  .h-salon .t2 { stroke-width: 2.4; }
  .h-salon .ah, .h-salon .am { stroke: none; } .h-salon .axe { fill: var(--v-texte); }

  .h-rose-poudre .piste { stroke-width: 2; }
  .h-rose-poudre .tour { fill: none; stroke: var(--v-accent); stroke-width: 5; stroke-linecap: round; }
  .h-rose-poudre .heure { font-weight: 200; }

  .h-tactique { font-family: Bahnschrift, "DIN Alternate", "Roboto Condensed", Impact, sans-serif; }
  .h-tactique .bloc { transform: skewX(-9deg); display: flex; flex-direction: column; align-items: flex-start; }
  .h-tactique .rep { font-size: min(2.8vh, 4vw); letter-spacing: .32em; color: var(--v-accent); margin-bottom: 1vh; }
  .h-tactique .heure { font-size: min(34vh, 34vw); font-weight: 700; line-height: .9; letter-spacing: -.01em; clip-path: polygon(0 0, 100% 0, 100% 84%, 96% 100%, 0 100%); }
  .h-tactique .barres { display: flex; gap: 1vh; margin-top: 2.4vh; width: 100%; }
  .h-tactique .barres i { height: 1vh; background: var(--v-accent); flex: 5; } .h-tactique .barres i:nth-child(2) { flex: 1; } .h-tactique .barres i:nth-child(3) { flex: .5; background: var(--v-texte); }
  .h-tactique .sec { font-size: min(3vh, 4.4vw); letter-spacing: .3em; color: var(--v-texte2); margin-top: 1.6vh; font-variant-numeric: tabular-nums; }

  .h-briques .mur { width: min(78vw, 118vh); }
  .h-briques .briques { width: 100%; height: auto; display: block; overflow: visible; }
  .h-briques .b1 { fill: var(--v-accent); } .h-briques .b2 { fill: var(--v-texte); }
  .h-briques .bp { fill: #fff; opacity: .22; }
  .h-briques .date { margin-top: 4vh; font-weight: 700; }

  .h-corsaire .rose { fill: none; stroke: var(--v-texte2); stroke-width: .8; opacity: .8; }
  .h-corsaire .rose2 { fill: var(--v-texte2); opacity: .22; }
  .h-corsaire .ch { font-size: 11px; font-family: Georgia, serif; fill: var(--v-accent); }
  .h-corsaire .ah, .h-corsaire .am { stroke: none; }
  .h-corsaire .date { font-family: Georgia, serif; font-style: italic; } .h-corsaire .date b { font-style: normal; color: var(--v-texte); }

  .h-royale .pile { position: relative; transform: rotate(-4deg) skewX(-6deg); }
  .h-royale .heure { font-size: min(36vh, 36vw); font-weight: 900; line-height: 1; letter-spacing: -.02em; font-family: "Segoe UI Black", "Arial Black", Impact, sans-serif; }
  .h-royale .ombre { position: absolute; left: .09em; top: .08em; color: var(--v-carte); -webkit-text-stroke: .03em var(--v-ligne); }
  .h-royale .face { position: relative; background: linear-gradient(180deg, var(--v-texte) 8%, var(--v-accent) 78%); -webkit-background-clip: text; background-clip: text; color: transparent; }
  .h-royale .pastille { margin-top: 3vh; padding: 1.1vh 3vh; border-radius: 99px; background: var(--v-accent); color: var(--v-fond); font-weight: 800; font-size: min(3vh, 4.4vw); text-transform: uppercase; letter-spacing: .08em; transform: rotate(-4deg); }

  .h-circuit .piste { stroke-width: 7; stroke-linecap: butt; } .h-circuit .rouge { fill: none; stroke: var(--v-accent); stroke-width: 7; }
  .h-circuit .ch { font-size: 10px; fill: var(--v-texte2); font-weight: 600; }
  .h-circuit .aig path { fill: var(--v-accent); } .h-circuit .aig { transition: transform 1s linear; }
  .h-circuit .axe { fill: var(--v-carte); stroke: var(--v-texte); stroke-width: 1.5; }
  .h-circuit .num { font-size: 26px; font-weight: 700; font-variant-numeric: tabular-nums; } .h-circuit .unite { font-size: 8px; letter-spacing: .4em; fill: var(--v-texte2); }

  .h-cockpit { font-family: Consolas, "Roboto Mono", "DejaVu Sans Mono", monospace; }
  .h-cockpit .bord { stroke-width: 2.6; } .h-cockpit .ch { font-size: 9.5px; fill: var(--v-texte); }
  .h-cockpit .a24 path { fill: var(--v-accent); }
  .h-cockpit .num { font-size: 21px; font-weight: 700; } .h-cockpit .unite { font-size: 6.5px; letter-spacing: .5em; fill: var(--v-texte2); }
  .h-cockpit .utc { font-size: 11px; fill: var(--v-accent); letter-spacing: .12em; }

  .h-bourse { font-family: Consolas, "Roboto Mono", "DejaVu Sans Mono", monospace; }
  .h-bourse .tele { display: flex; align-items: baseline; }
  .h-bourse .tele .heure { font-size: min(26vh, 26vw); font-weight: 700; letter-spacing: -.02em; }
  .h-bourse .tele .sec { font-size: min(9vh, 9vw); color: var(--v-accent); margin-left: .12em; }
  .h-bourse .batons { display: flex; align-items: flex-end; gap: .35vw; height: 11vh; width: min(70vw, 110vh); margin-top: 2vh; }
  .h-bourse .batons i { flex: 1; background: var(--v-ligne); border-radius: 1px; } .h-bourse .batons i.fait { background: var(--v-accent); }
  .h-bourse .date { letter-spacing: .16em; font-size: min(2.8vh, 3.8vw); margin-top: 3vh; }

  .h-ecrin { font-family: Georgia, "Times New Roman", serif; }
  .h-ecrin .bord { stroke: var(--v-accent); stroke-width: 1.2; } .h-ecrin .t1 { stroke: var(--v-accent); }
  .h-ecrin .ch { font-size: 12.5px; fill: var(--v-accent); letter-spacing: .04em; }
  .h-ecrin .marque { font-size: 5.6px; letter-spacing: .5em; fill: var(--v-texte2); }
  .h-ecrin .ah, .h-ecrin .am { stroke: none; fill: var(--v-texte); } .h-ecrin .as { stroke-width: .6; } .h-ecrin .axe { fill: var(--v-accent); }
  .h-ecrin .date { font-style: italic; letter-spacing: .04em; }

  .h-prairie .ciel-arc { width: min(72vw, 96vh); height: auto; overflow: visible; }
  .h-prairie .piste { stroke-dasharray: 1 6; stroke-width: 2; } .h-prairie .sol { stroke: var(--v-texte2); stroke-width: 1; }
  .h-prairie .astre circle { fill: var(--v-accent); } .h-prairie .astre.lune circle { fill: var(--v-texte2); }
  .h-prairie .heure { font-size: min(20vh, 24vw); margin-top: 2vh; }

  .h-large .heure { font-weight: 200; }
  .h-large .houle { width: min(80vw, 120vh); height: 9vh; margin-top: 3vh; overflow: hidden; }
  .h-large .houle path { fill: none; stroke: var(--v-accent); stroke-width: 2.2; stroke-linecap: round; animation: houle 7s linear infinite; }
  .h-large .houle .v2 { stroke: var(--v-texte2); opacity: .55; animation-duration: 11s; animation-direction: reverse; }
  @keyframes houle { from { transform: translateX(0); } to { transform: translateX(-100px); } }

  .h-sommet .heure { font-weight: 600; letter-spacing: .01em; }
  .h-sommet .crete { width: min(84vw, 126vh); height: 20vh; margin-top: 2vh; }
  .h-sommet .loin { fill: var(--v-ligne); } .h-sommet .pres { fill: var(--v-texte2); opacity: .75; } .h-sommet .neige { fill: var(--v-carte); }

  .h-sous-bois .piste { stroke-width: 1; }
  .h-sous-bois .c1, .h-sous-bois .c2, .h-sous-bois .c3 { fill: none; stroke-linecap: round; stroke-width: 6; stroke: var(--v-accent); }
  .h-sous-bois .c2 { stroke: var(--v-texte); stroke-width: 4.5; } .h-sous-bois .c3 { stroke: var(--v-texte2); stroke-width: 3; }
  .h-sous-bois .rond .heure { font-size: min(11vh, 14vw); font-weight: 300; }
  .h-code { position: relative; overflow: hidden; font-family: Consolas, "Roboto Mono", "DejaVu Sans Mono", monospace; }
  .h-code .pluie { position: absolute; inset: 0; width: 100%; height: 100%; }
  .h-code .voile { position: absolute; inset: 0; background: radial-gradient(ellipse 44% 30% at 50% 50%, var(--v-fond) 30%, transparent 100%); opacity: .9; }
  .h-code .heure { position: relative; font-size: min(26vh, 28vw); font-weight: 700; color: var(--v-accent); text-shadow: 0 0 2.4vh var(--v-accent); }
  .h-code .date { position: relative; color: var(--v-texte); letter-spacing: .12em; font-size: min(3vh, 4vw); }
  .h-code .date b { color: var(--v-accent); font-weight: 400; } .h-code .date u { text-decoration: none; color: var(--v-accent); animation: curseur 1s steps(2) infinite; }
  @keyframes curseur { 50% { opacity: 0; } }
  .h-plume { font-family: Georgia, "Times New Roman", serif; }
  .h-plume .vol { width: min(16vh, 20vw); margin-bottom: 1vh; animation: plume 11s ease-in-out infinite alternate; }
  .h-plume .vol path { fill: var(--v-texte2); opacity: .55; } .h-plume .vol .tige { fill: none; stroke: var(--v-texte); stroke-width: .8; opacity: .8; }
  @keyframes plume { from { transform: translate(-9vw, -1vh) rotate(-14deg); } to { transform: translate(9vw, 2vh) rotate(10deg); } }
  .h-plume .heure { font-size: min(24vh, 28vw); font-weight: 400; }
  .h-plume .banc { width: min(34vw, 50vh); height: .5vh; border-radius: 9px; background: var(--v-accent); margin-top: 2.4vh; }
  .h-plume .date { font-style: italic; }
  .h-galaxie { position: relative; }
  .h-galaxie .etoiles { position: absolute; inset: 0; } .h-galaxie .etoiles i { position: absolute; border-radius: 50%; background: var(--v-texte); }
  .h-galaxie .heure { position: relative; font-size: min(27vh, 30vw); font-weight: 800; letter-spacing: .04em; color: transparent; -webkit-text-stroke: .5vh var(--v-accent); }
  .h-galaxie .date { position: relative; text-transform: uppercase; letter-spacing: .5em; color: var(--v-accent); font-size: min(2.8vh, 3.6vw); margin-top: 4vh; }
  .h-retro-85 { font-family: Consolas, "Roboto Mono", "DejaVu Sans Mono", monospace; }
  .h-retro-85 .tableau { display: flex; flex-wrap: wrap; justify-content: center; max-width: 88vw; box-sizing: border-box; row-gap: 2.6vh; gap: 2.6vh 1.6vw; padding: 3vh 2.4vw; border: 2px solid var(--v-ligne); border-radius: 1.2vh; background: var(--v-carte); }
  .h-retro-85 .case { display: flex; flex-direction: column; align-items: center; gap: 1.4vh; }
  .h-retro-85 .case b { min-width: 2.2em; text-align: center; padding: 1vh 1.2vw; background: var(--v-fond); border-radius: .6vh; font-size: min(11vh, 8.4vw); font-weight: 700; color: var(--v-accent); text-shadow: 0 0 1.6vh var(--v-accent); }
  .h-retro-85 .case.hh b, .h-retro-85 .case.mm b { color: var(--v-texte); text-shadow: 0 0 1.6vh var(--v-texte2); }
  .h-retro-85 .case span { font-size: min(2vh, 2.4vw); letter-spacing: .2em; color: var(--v-fond); background: var(--v-accent); padding: .3vh 1vh; border-radius: 2px; }
  .h-retro-85 .date { margin-top: 4vh; letter-spacing: .14em; text-transform: uppercase; font-size: min(2.8vh, 3.6vw); }
  .h-grimoire { font-family: Georgia, "Times New Roman", serif; }
  .h-grimoire .filet { display: flex; align-items: center; gap: 1.4vw; width: min(52vw, 80vh); color: var(--v-accent); font-size: min(3vh, 4vw); }
  .h-grimoire .filet::before, .h-grimoire .filet::after { content: ""; flex: 1; height: 1px; background: var(--v-accent); }
  .h-grimoire .heure { font-size: min(25vh, 28vw); font-weight: 400; letter-spacing: .03em; margin: 1vh 0 2vh; }
  .h-grimoire .date { font-style: italic; font-variant: small-caps; letter-spacing: .08em; margin-top: 3vh; }
  .h-feerie .voute { width: min(70vh, 76vw); overflow: visible; margin-bottom: -2vh; }
  .h-feerie .et { fill: var(--v-accent); opacity: .45; transition: opacity 1s ease; } .h-feerie .et.vif { opacity: 1; }
  .h-feerie .heure { font-size: min(24vh, 28vw); font-weight: 300; }
  .h-banquise .flocon { width: min(15vh, 18vw); margin-bottom: 1vh; }
  .h-banquise .flocon line { stroke: var(--v-accent); stroke-width: 2.4; stroke-linecap: round; } .h-banquise .tour { transition: transform 1s linear; }
  .h-banquise .heure { font-size: min(26vh, 30vw); font-weight: 200; letter-spacing: .02em; }
  .h-lagon .rond { position: relative; width: min(74vh, 84vw); height: min(74vh, 84vw); }
  .h-lagon .rond .cadran { width: 100%; height: 100%; }
  .h-lagon .spire { fill: none; stroke: var(--v-ligne); stroke-width: 2.2; stroke-linecap: round; }
  .h-lagon .perle circle { fill: var(--v-accent); }
  .h-lagon .dedans { position: absolute; inset: 0; display: flex; flex-direction: column; align-items: center; justify-content: center; }
  .h-lagon .rond .heure { font-size: min(13vh, 16vw); font-weight: 600; text-shadow: 0 0 3vh var(--v-fond), 0 0 3vh var(--v-fond); }
  .h-lagon .rond .date { text-shadow: 0 0 2vh var(--v-fond); }
  .h-etincelle { font-family: "Arial Rounded MT Bold", "Segoe UI Black", "Segoe UI", Roboto, sans-serif; }
  .h-etincelle .duo { display: flex; align-items: center; font-size: min(27vh, 26vw); font-weight: 900; line-height: 1; }
  .h-etincelle .eclair { height: .8em; margin: 0 .06em; } .h-etincelle .eclair path { fill: var(--v-accent); transition: opacity .6s ease; }
  .h-etincelle.flash .eclair path { opacity: .45; }
  .h-etincelle .pastille { margin-top: 3.4vh; padding: 1vh 3vh; border-radius: 99px; background: var(--v-accent); color: var(--v-carte); font-weight: 800; font-size: min(3vh, 4.2vw); }
`;


/*
 * Veille-lot — les cases typées du contrat (docs/veille-contrat.md §4.2) dans la page : météo et prévisions,
 * agenda, image (picture-elements : fond, textes en %, calques), radar, caméra (image fixe : la télé pose le direct
 * par-dessus), « Derniers passages », iframe. Elles arrivent à côté des cartes (« typees » de chaque tableau) ; sans
 * elles (Home Assistant plus ancien), chaque carte reste dessinée par Home Assistant, comme avant.
 *
 * Les images ne viennent que de `/api/pc_parental/veille/media/<clé>`, en POST avec le ticket de CET appareil (celui
 * de la passerelle), jamais avec un jeton Home Assistant, jamais d'une autre adresse.
 */
const GENRES_TYPES = ["meteo", "agenda", "image", "radar", "camera", "clips", "iframe"];
const AVEC_IMAGES = ["image", "radar", "camera", "clips"];
const RE_MEDIA = /^\/api\/pc_parental\/veille\/media\/[0-9a-f]{8,64}$/;
// Le rythme de chaque image (ms) : une caméra se renouvelle, un fond de plan presque jamais.
const AGE_MEDIA = { camera: 10000, calque: 60000, vignette: 60000, fond: 600000 };
// Les mots des cases typées : dans le catalogue de la carte (TEXTES), six langues ; relus à chaque usage (la langue suit HA).
const MOTS_VT = { get auj() { return tr("vt_auj"); }, get demain() { return tr("vt_demain"); }, get journee() { return tr("vt_journee"); },
  get vide() { return tr("vt_vide"); }, get passages() { return tr("vt_passages"); }, get instant() { return tr("vt_instant"); },
  min: (n) => tr("vt_min", { n }), h: (n) => tr("vt_h", { n }), get hier() { return tr("vt_hier"); }, j: (n) => tr("vt_j", { n }) };
const cielDe = (etat) => (CIEL[etat] ? tr(CIEL[etat]) : "");
const echapper = (s) => String(s == null ? "" : s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
const degres = (v) => (typeof v === "number" && isFinite(v) ? `${Math.round(v)}°` : "–");
// « AAAA-MM-JJ » est une date du jour local (pas minuit UTC) ; le reste, de l'ISO 8601 avec fuseau.
const lireDate = (s) => { const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(s || "")); if (m) return new Date(+m[1], +m[2] - 1, +m[3]); const d = new Date(s); return isNaN(d) ? null : d; };
const memeJour = (a, b) => a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
const jourCourt = (s) => { const d = lireDate(s); return d ? majuscule(d.toLocaleDateString(LANGUE, { weekday: "short" }).replace(/\.$/, "")) : ""; };
const quandEvenement = (ev, maintenant) => {
  const d = lireDate(ev.debut); if (!d) return "";
  const demain = new Date(maintenant.getFullYear(), maintenant.getMonth(), maintenant.getDate() + 1);
  const jour = memeJour(d, maintenant) ? MOTS_VT.auj : memeJour(d, demain) ? MOTS_VT.demain : majuscule(d.toLocaleDateString(LANGUE, { weekday: "long" }));
  return `${jour}  ·  ${ev.journee ? MOTS_VT.journee : hm(d)}`;
};
// Un évènement fini ne s'affiche plus (la page peut rester ouverte longtemps avec le même relevé).
const pasFini = (ev, maintenant) => {
  const f = lireDate(ev.fin || ev.debut); if (!f) return true;
  return ev.journee ? f > new Date(maintenant.getFullYear(), maintenant.getMonth(), maintenant.getDate()) : f > maintenant;
};
const ecartLisible = (s, maintenant) => {
  const d = lireDate(s); if (!d) return "";
  const min = Math.floor((maintenant - d) / 60000);
  if (min < 1) return MOTS_VT.instant;
  if (min < 60) return MOTS_VT.min(min);
  if (min < 24 * 60) return MOTS_VT.h(Math.floor(min / 60));
  return min < 48 * 60 ? MOTS_VT.hier : MOTS_VT.j(Math.floor(min / 1440));
};
// Les icônes du ciel, au trait, aux couleurs du thème (soleil et éclair en accent, nuages en texte secondaire).
const ICONES_CIEL = (() => {
  const soleil = (x = 0, y = 0, r = 7) => `<g class="i-soleil"><circle cx="${x}" cy="${y}" r="${r}"/>${[0, 45, 90, 135, 180, 225, 270, 315].map((a) => { const [x1, y1] = pt(r + 3, a), [x2, y2] = pt(r + 6, a); return `<line x1="${+x1 + x}" y1="${+y1 + y}" x2="${+x2 + x}" y2="${+y2 + y}"/>`; }).join("")}</g>`;
  const nuage = (dx = 0, dy = 0) => `<path class="i-nuage" transform="translate(${dx} ${dy})" d="M -11 8 H 11 A 6 6 0 0 0 10 -4 A 9 9 0 0 0 -7 -3 A 6 6 0 0 0 -11 8 Z"/>`;
  const gouttes = (n, neige) => Array.from({ length: n }, (_, i) => { const x = -7 + i * 7; return neige ? `<circle class="i-neige" cx="${x}" cy="15" r="1.6"/>` : `<line class="i-pluie" x1="${x}" y1="12" x2="${x - 2}" y2="18"/>`; }).join("");
  const eclair = `<path class="i-eclair" d="M 1 9 L -3 16 H 1 L -1 21 L 5 13 H 1 Z"/>`;
  return {
    sunny: soleil(), "clear-night": `<path class="i-lune" d="M 4 -10 A 10 10 0 1 0 10 6 A 8 8 0 0 1 4 -10 Z"/>`,
    partlycloudy: soleil(-5, -5, 5) + nuage(3, 3), cloudy: nuage(), fog: `<g class="i-brume"><line x1="-11" y1="-4" x2="11" y2="-4"/><line x1="-13" y1="2" x2="9" y2="2"/><line x1="-9" y1="8" x2="13" y2="8"/></g>`,
    rainy: nuage(0, -4) + gouttes(3), pouring: nuage(0, -4) + gouttes(4), snowy: nuage(0, -4) + gouttes(3, true), "snowy-rainy": nuage(0, -4) + gouttes(2) + `<circle class="i-neige" cx="7" cy="15" r="1.6"/>`,
    hail: nuage(0, -4) + gouttes(3, true), lightning: nuage(0, -4) + eclair, "lightning-rainy": nuage(0, -4) + eclair + `<line class="i-pluie" x1="-7" y1="12" x2="-9" y2="18"/>`,
    windy: `<g class="i-brume"><path d="M -12 -4 H 6 A 4 4 0 1 0 2 -8"/><path d="M -12 3 H 10 A 4 4 0 1 1 6 7"/></g>`, "windy-variant": nuage(0, -3) + `<g class="i-brume"><path d="M -12 12 H 8"/></g>`, exceptional: `<g class="i-eclair"><circle r="9" fill="none"/><line x1="0" y1="-5" x2="0" y2="2"/><circle cy="5.5" r="1"/></g>`,
  };
})();
const iconeCiel = (etat) => `<svg class="vt-icone" viewBox="-16 -16 32 40" aria-hidden="true">${ICONES_CIEL[etat] || ICONES_CIEL.cloudy}</svg>`;
// Répartit `total` pixels entre `n` parts égales séparées par `ecart` : pixels entiers, écarts tous égaux, le pixel
// de reste aux premières parts (même règle que GrilleVeille.repartir de l'appli).
const repartir = (total, n, ecart) => {
  const dispo = Math.max(0, total - ecart * (n - 1)), base = Math.floor(dispo / n), reste = dispo - base * n;
  return Array.from({ length: n }, (_, i) => base + (i < reste ? 1 : 0));
};
// La mise en page compte en 1/64 de px : des valeurs déjà rondes à 1/64 ne sont plus arrondies, et les erreurs ne
// s'additionnent pas d'une piste à l'autre. Pistes de `larg` px de l'écran séparées de `ecart`, à l'échelle k (px de
// l'écran par px de la page) : chaque piste commence au plus près d'un pixel entier de l'écran.
const q64 = (v) => Math.round(v * 64) / 64;
const pistesAuPixel = (larg, ecart, k) => {
  const g = q64(ecart / k), debuts = [];
  let pos = 0;
  for (const l of larg) { debuts.push(q64(pos / k)); pos += l + ecart; }
  const fin = q64((pos - ecart) / k);
  const largeurs = debuts.map((d, i) => (i + 1 < debuts.length ? debuts[i + 1] - g : fin) - d);
  return { largeurs: largeurs.map((l) => `${l}px`).join(" "), ecart: g, total: fin };
};
// Le ticket de veille de CET appareil (« vv1.<id>.<fin>.<signature> »), rangé par la page d'entrée. Un vrai jeton
// de Home Assistant (navigateur connecté) n'a pas cette forme : il n'est jamais envoyé.
const ticketVeille = (hass) => {
  let t = "";
  try { t = (hass && hass.auth && hass.auth.data && hass.auth.data.access_token) || ""; } catch (e) { t = ""; }
  if (!t) { try { t = (JSON.parse(localStorage.getItem("hassTokens") || "{}") || {}).access_token || ""; } catch (e) { t = ""; } }
  return /^vv1\.[A-Za-z0-9_-]+\.\d+\.[0-9a-f]{40}$/.test(t) ? t : "";
};
const CSS_TYPEES = /* i18n-ignorer : feuille de style */ `
  .vt { position: relative; box-sizing: border-box; overflow: hidden; padding: 16px 18px 18px; color: var(--v-texte); break-inside: avoid;
        background: var(--ha-card-background, var(--card-background-color)); border-radius: var(--ha-card-border-radius, 18px);
        border: var(--ha-card-border-width, 0px) solid var(--ha-card-border-color, transparent); }
  .vt-nom { font-size: 13px; font-weight: 700; letter-spacing: .1em; text-transform: uppercase; color: var(--v-texte2); margin: 0 0 12px;
            white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .vt-media { position: relative; overflow: hidden; border-radius: 10px; background: var(--v-doux); }
  .vt-fond { position: absolute; left: 0; top: 0; width: 100%; height: 100%; object-fit: contain; opacity: 0; transition: opacity .5s ease; }
  .vt-fond.vu { opacity: 1; }
  .vt-camera .vt-fond, .vt-clips .vt-fond { object-fit: cover; }
  .vt-cadre { position: absolute; left: 0; top: 0; width: 100%; height: 100%; }
  .vt-calque { position: absolute; transform: translate(-50%, -50%); opacity: 0; transition: opacity .5s ease; }
  .vt-calque.vu { opacity: 1; }
  .vt-etiquette { position: absolute; transform: translate(-50%, -50%); padding: 2px 8px; border-radius: 7px; white-space: nowrap; font-size: 14px;
                  background: rgba(var(--rgb-card-background-color, 40, 40, 40), .88); color: var(--v-texte); }
  .vt-attente { position: absolute; left: 50%; top: 50%; transform: translate(-50%, -50%); display: flex; gap: 7px; }
  .vt-attente i { width: 7px; height: 7px; border-radius: 50%; background: var(--v-accent); animation: vt-bat 1.2s ease-in-out infinite; }
  .vt-attente i:nth-child(2) { animation-delay: .2s; } .vt-attente i:nth-child(3) { animation-delay: .4s; }
  .vt-media.pret .vt-attente { display: none; }
  @keyframes vt-bat { 0%, 100% { opacity: .35; } 50% { opacity: 1; } }
  .vt-point { position: absolute; right: 12px; top: 12px; width: 8px; height: 8px; border-radius: 50%; background: var(--v-accent); }
  .vt-icone { width: 1em; height: 1.25em; overflow: visible; fill: none; stroke-linecap: round; stroke-linejoin: round; }
  .vt-icone .i-soleil circle, .vt-icone .i-soleil line { stroke: var(--v-accent); stroke-width: 2.2; }
  .vt-icone .i-soleil circle { fill: var(--v-accent-doux); }
  .vt-icone .i-lune { fill: var(--v-accent-doux); stroke: var(--v-accent); stroke-width: 2; }
  .vt-icone .i-nuage { fill: var(--v-carte); stroke: var(--v-texte2); stroke-width: 2; }
  .vt-icone .i-pluie, .vt-icone .i-brume line, .vt-icone .i-brume path { stroke: var(--v-texte2); stroke-width: 2; }
  .vt-icone .i-neige { fill: var(--v-texte2); }
  .vt-icone .i-eclair { fill: var(--v-accent); stroke: var(--v-accent); stroke-width: 1.2; }
  .vt-maintenant { display: flex; align-items: center; gap: 14px; min-width: 0; }
  .vt-maintenant .vt-icone { font-size: 58px; flex: none; }
  .vt-temp { font-size: 52px; font-weight: 300; line-height: 1.05; font-variant-numeric: tabular-nums; flex: none; }
  .vt-ciel { font-size: 19px; color: var(--v-texte2); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; min-width: 0; }
  .vt-prev { display: grid; gap: 8px; margin-top: 16px; text-align: center; }
  .vt-prev .vt-jour { font-size: 13px; font-weight: 700; letter-spacing: .06em; color: var(--v-texte2); }
  .vt-prev .vt-icone { font-size: 30px; display: block; margin: 6px auto 4px; }
  .vt-prev .vt-mm { font-size: 16px; font-variant-numeric: tabular-nums; white-space: nowrap; }
  .vt-prev .vt-mm span { color: var(--v-texte2); margin-left: 6px; }
  .vt-ev { padding: 7px 0; } .vt-ev + .vt-ev { border-top: 1px solid var(--v-ligne); }
  .vt-quand { display: inline-block; font-size: 13px; font-weight: 700; letter-spacing: .04em; color: var(--v-accent); }
  .vt-titre { font-size: 18px; margin-top: 2px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .vt-vide { font-size: 17px; color: var(--v-texte2); padding: 6px 0; }
  .vt-vignettes { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
  .vt-legende { font-size: 13px; color: var(--v-texte2); margin-top: 6px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .vt-legende b { color: var(--v-texte); font-weight: 600; }
  /* La pastille (réglage de l'appareil) : un fond doux derrière les valeurs. */
  :host([data-pastille="1"]) .vt-temp { background: var(--v-doux); border-radius: .2em; padding: 0 .16em; }
  :host([data-pastille="1"]) .vt-quand { background: var(--v-doux); border-radius: 6px; padding: 1px 8px; }
  :host([data-pastille="1"]) .vt-prev .vt-mm { background: var(--v-doux); border-radius: 6px; padding: 1px 6px; }
  :host([data-style="neoretro"]) .vt-nom { color: var(--v-accent); letter-spacing: .2em; font-weight: 400; }
  :host([data-style="neoretro"]) .vt-media, :host([data-style="neoretro"]) .vt-etiquette { border-radius: 2px; }
`;

class VisionVeillePanel extends HTMLElement {
  constructor() {
    super();
    this.attachShadow({ mode: "open" });
    this._cartes = [];
    this._ecrans = [];
    this._indice = 0;
    this._pret = false;
    this._medias = new Map();
  }

  set hass(hass) {
    // Home Assistant recrée parfois le panneau (quand sa liste de panneaux se met à jour) : la veille repartait
    // alors de zéro, horloge comprise. Le premier panneau créé reste donc le seul ; les suivants le reprennent.
    const premier = window.__visionVeille;
    if (premier && premier !== this) {
      if (premier.parentNode !== this.parentNode && this.parentNode) this.parentNode.appendChild(premier);
      this.style.display = "none";
      premier.hass = hass;
      return;
    }
    window.__visionVeille = this;
    this._hass = hass;
    // La langue de l'utilisateur Home Assistant de la veille, relue à chaque hass : si elle change, les horloges
    // à l'écran sont redessinées (le reste suit au prochain écran).
    const langue = suivreLangue(hass);
    if (this._pret && langue !== this._langue) { this._langue = langue; this._relangue(); }
    this._langue = langue;
    for (const c of this._cartes) { try { c.hass = this._pourCartes(); } catch (e) { /* une carte en panne ne bloque pas les autres */ } }
    if (!this._pret) { this._pret = true; this._demarrer(); }
  }
  set narrow(v) {} set route(v) {} set panel(v) {}

  // Les cartes reçoivent Home Assistant avec le mode clair ou sombre du thème de l'appareil : la carte du lieu,
  // les graphiques et les cartes de la communauté s'y fient pour choisir leurs propres teintes.
  _pourCartes() {
    const h = this._hass;
    if (!h || this._sombre === undefined) return h;
    if (this._hassVu !== h) { this._hassVu = h; this._hassCartes = { ...h, themes: { ...(h.themes || {}), darkMode: this._sombre } }; }
    return this._hassCartes;
  }

  // Un mélange de deux couleurs : « part » de la seconde dans la première.
  _melange(a, b, part) {
    const x = [1, 3, 5].map((i) => parseInt(a.slice(i, i + 2), 16)), y = [1, 3, 5].map((i) => parseInt(b.slice(i, i + 2), 16));
    return "#" + x.map((v, i) => Math.round(v + (y[i] - v) * part).toString(16).padStart(2, "0")).join("");
  }

  _couleur(nom, defaut) {
    const v = this._q.get(nom);
    return v && /^[0-9a-fA-F]{6}$/.test(v) ? "#" + v : defaut;
  }

  _theme() {
    const fond = this._couleur("fond", "#120609"), carte = this._couleur("carte", "#27111A"), texte = this._couleur("texte", "#FBF3EE");
    const texte2 = this._couleur("texte2", "#CDA8A6"), accent = this._couleur("accent", "#F2C14E"), ligne = this._couleur("ligne", "#4C2231");
    const vars = {
      "--primary-background-color": fond, "--secondary-background-color": carte, "--lovelace-background": fond,
      "--card-background-color": carte, "--ha-card-background": carte, "--ha-card-border-color": ligne, "--ha-card-border-radius": "18px",
      "--ha-card-box-shadow": "none", "--divider-color": ligne, "--outline-color": ligne,
      "--primary-text-color": texte, "--secondary-text-color": texte2, "--disabled-text-color": texte2, "--text-primary-color": fond,
      "--primary-color": accent, "--accent-color": accent, "--state-icon-color": accent, "--paper-item-icon-color": accent,
      "--state-active-color": accent, "--mdc-theme-primary": accent, "--mdc-theme-surface": carte, "--mdc-theme-on-surface": texte,
      "--ha-color-text-primary": texte, "--ha-color-text-secondary": texte2, "--ha-color-surface-default": carte,
      "--chip-background": carte, "--rgb-primary-text-color": this._rvb(texte), "--rgb-primary-color": this._rvb(accent),
      "--rgb-card-background-color": this._rvb(carte), "--rgb-secondary-text-color": this._rvb(texte2),
      "--v-fond": fond, "--v-carte": carte, "--v-texte": texte, "--v-texte2": texte2, "--v-accent": accent,
    };
    // Tout ce que Home Assistant colore de lui-même d'après son propre mode (champs, listes, interrupteurs,
    // curseurs, états éteints, graphiques) est ramené aux couleurs de l'appareil : sinon un thème clair
    // garde des champs noirs, et un thème sombre des aplats gris.
    const doux = this._melange(carte, texte, 0.07), moyen = this._melange(carte, texte, 0.14), fort = this._melange(carte, texte, 0.28);
    const accentDoux = this._melange(carte, accent, 0.22), eteint = this._melange(carte, texte2, 0.55);
    Object.assign(vars, {
      "--input-fill-color": doux, "--input-ink-color": texte, "--input-label-ink-color": texte2, "--input-idle-line-color": ligne,
      "--input-hover-line-color": texte2, "--input-disabled-fill-color": doux, "--input-disabled-ink-color": texte2, "--input-dropdown-icon-color": texte2,
      "--mdc-text-field-fill-color": doux, "--mdc-text-field-ink-color": texte, "--mdc-text-field-label-ink-color": texte2,
      "--mdc-text-field-idle-line-color": ligne, "--mdc-select-fill-color": doux, "--mdc-select-ink-color": texte,
      "--mdc-select-label-ink-color": texte2, "--mdc-select-dropdown-icon-color": texte2, "--mdc-select-idle-line-color": ligne,
      "--mdc-theme-text-primary-on-background": texte, "--mdc-theme-text-secondary-on-background": texte2, "--mdc-theme-on-primary": fond,
      "--ha-color-form-background": doux, "--clear-background-color": fond, "--primary-background-color-rgb": this._rvb(fond),
      "--state-inactive-color": eteint, "--state-unavailable-color": fort, "--disabled-color": fort, "--state-off-color": eteint,
      "--switch-checked-button-color": accent, "--switch-checked-track-color": accent, "--switch-unchecked-button-color": eteint,
      "--switch-unchecked-track-color": fort, "--switch-checked-color": accent, "--slider-track-color": moyen, "--slider-color": accent,
      "--md-sys-color-primary": accent, "--md-sys-color-on-primary": fond, "--md-sys-color-surface": carte, "--md-sys-color-on-surface": texte,
      "--md-sys-color-on-surface-variant": texte2, "--md-sys-color-surface-variant": moyen, "--md-sys-color-outline": ligne,
      "--md-sys-color-surface-container": doux, "--md-sys-color-surface-container-high": doux, "--md-sys-color-surface-container-highest": moyen,
      "--md-sys-color-secondary-container": accentDoux, "--md-sys-color-on-secondary-container": texte,
      "--control-select-background": moyen, "--control-slider-background": accent, "--control-number-buttons-background-color": texte,
      "--info-color": accent, "--scrollbar-thumb-color": "transparent", "--rgb-disabled": this._rvb(fort), "--rgb-state-inactive-color": this._rvb(eteint),
      "--graph-color-1": accent, "--graph-color-2": "#5B8FD9", "--graph-color-3": "#4FAE8C", "--graph-color-4": "#D9705F", "--graph-color-5": "#9C7AD1", "--graph-color-6": "#49A9C2",
      "--v-ligne": ligne, "--v-doux": doux, "--v-moyen": moyen, "--v-fort": fort, "--v-eteint": eteint, "--v-accent-doux": this._melange(carte, accent, 0.38),
    });
    for (const fam of ["neutral", "primary"]) {
      const base = fam === "primary" ? accent : texte;
      const fills = { quiet: this._melange(carte, base, 0.08), normal: this._melange(carte, base, 0.16), loud: fam === "primary" ? accent : this._melange(carte, base, 0.6) };
      for (const [force, c] of Object.entries(fills)) {
        for (const etat of ["resting", "hover", "active"]) vars[`--ha-color-fill-${fam}-${force}-${etat}`] = c;
        vars[`--ha-color-on-${fam}-${force}`] = force === "loud" ? fond : (fam === "primary" ? accent : texte);
        vars[`--ha-color-border-${fam}-${force}`] = force === "quiet" ? ligne : this._melange(carte, base, 0.4);
      }
    }
    // L'échelle de la couleur principale (interrupteurs, curseurs) part de l'accent de l'appareil.
    for (const n of [5, 10, 20, 30, 40, 50, 60, 70, 80, 90, 95]) {
      vars[`--ha-color-primary-${String(n).padStart(2, "0")}`] = n <= 50 ? this._melange("#000000", accent, 0.25 + 0.75 * n / 50) : this._melange(accent, carte, (n - 50) / 50 * 0.85);
    }
    for (const n of ["default", "low", "lower", "lowest", "raised"]) vars[`--ha-color-surface-${n}`] = carte;
    // Le style graphique (formes, bordures, lettres) est distinct des couleurs : chacun choisit les deux.
    this._style = this._q.get("style") === "neoretro" ? "neoretro" : "doux";
    if (this._style === "neoretro") {
      Object.assign(vars, {
        "--ha-card-border-radius": "3px", "--ha-card-border-width": "0px", "--ha-card-border-color": "transparent",
        "--ha-font-family-body": "Bahnschrift, 'DIN Alternate', 'Roboto Condensed', 'Segoe UI', sans-serif",
        "--primary-font-family": "Bahnschrift, 'DIN Alternate', 'Roboto Condensed', 'Segoe UI', sans-serif",
        "--paper-font-common-base_-_font-family": "Bahnschrift, 'DIN Alternate', 'Roboto Condensed', 'Segoe UI', sans-serif",
        "--chip-border-radius": "3px", "--chip-border-width": "0px", "--mush-chip-border-radius": "3px",
        "--ha-card-header-font-family": "Bahnschrift, 'DIN Alternate', 'Roboto Condensed', 'Segoe UI', sans-serif",
        "--ha-font-family-heading": "Bahnschrift, 'DIN Alternate', 'Roboto Condensed', 'Segoe UI', sans-serif",
        "--mush-control-border-radius": "3px", "--mush-icon-border-radius": "3px", "--ha-border-radius-md": "3px", "--ha-border-radius-lg": "3px",
        "--ha-border-radius-sm": "2px", "--ha-border-radius-xl": "3px", "--ha-border-radius-pill": "3px", "--control-button-border-radius": "3px",
        "--feature-border-radius": "3px", "--tile-icon-border-radius": "3px", "--ha-tile-icon-border-radius": "3px",
      });
    }
    // Chacun décide : un fond sous ses cartes ou non, un contour ou non.
    const net = this._style === "neoretro";
    if (this._q.get("cfond") === "0") {
      Object.assign(vars, { "--ha-card-background": "transparent", "--card-background-color": "transparent", "--chip-background": "transparent", "--mush-chip-background": "transparent" });
    }
    const contour = this._q.get("ccontour") === "1";
    Object.assign(vars, {
      "--ha-card-border-width": contour ? (net ? "1.5px" : "1px") : "0px",
      "--ha-card-border-color": contour ? ligne : "transparent",
      "--chip-border-width": contour ? (net ? "1.5px" : "1px") : "0px", "--chip-border-color": contour ? ligne : "transparent",
    });
    this._sombre = [1, 3, 5].map((i) => parseInt(fond.slice(i, i + 2), 16)).reduce((a, b) => a + b, 0) < 384;
    // La carte du lieu : des teintes calmes, proches du fond, dans les deux modes.
    this.style.setProperty("--v-plan", this._sombre ? "grayscale(1) contrast(0.9)" : "saturate(0.55) contrast(0.94) sepia(0.12)");
    this._feuille = null;
    this._jetons = Object.entries(vars).filter(([k]) => k.startsWith("--ha-color-") || k.startsWith("--md-sys-color-")).map(([k, v]) => `${k}: ${v} !important;`).join(" ");
    // Home Assistant passe lui-même en clair ou en sombre, comme le thème de l'appareil : ses composants
    // (interrupteurs, curseurs, carte du lieu) choisissent alors les bonnes nuances d'eux-mêmes.
    try { this.dispatchEvent(new CustomEvent("settheme", { detail: { dark: this._sombre }, bubbles: true, composed: true })); } catch (e) { /* le thème du compte reste */ }
    this.setAttribute("data-style", this._style);
    // Posées aussi à la racine de la page : les teintes que Home Assistant en déduit là-haut (interrupteurs,
    // boutons) se recalculent alors avec les couleurs de l'appareil.
    for (const [k, v] of Object.entries(vars)) { this.style.setProperty(k, v); document.documentElement.style.setProperty(k, v); }
    document.body.style.background = fond;
  }
  _rvb(h) { return [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16)).join(", "); }

  // Une veille se regarde, elle ne se touche pas : les boutons, menus, flèches et champs de saisie des cartes
  // n'ont rien à y faire. Cette feuille de style est posée dans chaque carte, jusque dans ses éléments internes.
  _regles() {
    const net = this._style === "neoretro";
    return `
      :host { ${this._jetons} }
      :host(ha-switch) {
        --ha-switch-checked-background-color: var(--v-accent-doux) !important; --ha-switch-checked-background-color-hover: var(--v-accent-doux) !important;
        --ha-switch-checked-border-color: var(--v-accent-doux) !important; --ha-switch-checked-border-color-hover: var(--v-accent-doux) !important;
        --ha-switch-checked-thumb-background-color: var(--v-accent) !important; --ha-switch-checked-thumb-background-color-hover: var(--v-accent) !important;
        --ha-switch-checked-thumb-border-color: var(--v-accent) !important; --ha-switch-checked-thumb-border-color-hover: var(--v-accent) !important;
        --ha-switch-background-color: var(--v-moyen) !important; --ha-switch-background-color-hover: var(--v-moyen) !important;
        --ha-switch-border-color: var(--v-fort) !important; --ha-switch-thumb-background-color: var(--v-eteint) !important;
        --ha-switch-thumb-background-color-hover: var(--v-eteint) !important; --ha-switch-thumb-border-color: var(--v-eteint) !important; --ha-switch-thumb-border-color-hover: var(--v-eteint) !important;
      }
      :host(ha-slider), :host(wa-slider), ha-slider {
        --track-color-inactive: var(--v-moyen) !important; --track-color-active: var(--v-accent) !important; --thumb-color: var(--v-accent) !important;
        --ha-slider-track-color: var(--v-moyen) !important; --ha-slider-indicator-color: var(--v-accent) !important; --ha-slider-thumb-color: var(--v-accent) !important;
        --wa-color-neutral-fill-normal: var(--v-moyen) !important; --wa-color-brand-fill-loud: var(--v-accent) !important; --wa-form-control-activated-color: var(--v-accent) !important;
        --md-sys-color-surface-container-highest: var(--v-moyen) !important; --md-slider-inactive-track-color: var(--v-moyen) !important;
      }
      ::-webkit-scrollbar { display: none !important; }
      * { scrollbar-width: none !important; }
      ha-icon-button, ha-button-menu, ha-icon-next, ha-icon-button-next, ha-icon-button-prev, mwc-icon-button, .more-info { display: none !important; }
      hui-target-temperature-card-feature, hui-numeric-input-card-feature, hui-select-options-card-feature, hui-counter-actions-card-feature,
      hui-update-actions-card-feature, hui-cover-open-close-card-feature, hui-valve-open-close-card-feature, hui-button-card-feature,
      hui-climate-hvac-modes-card-feature, hui-climate-preset-modes-card-feature, hui-climate-fan-modes-card-feature,
      hui-water-heater-operation-modes-card-feature, hui-alarm-modes-card-feature, hui-vacuum-commands-card-feature,
      hui-lawn-mower-commands-card-feature, hui-lock-commands-card-feature, hui-media-player-playback-card-feature { display: none !important; }
      mushroom-number-value-control, mushroom-select-option-control, mushroom-climate-temperature-control, mushroom-climate-hvac-modes-control,
      mushroom-media-player-media-control, mushroom-media-player-volume-control, mushroom-cover-buttons-control, mushroom-fan-percentage-control { display: none !important; }
      :host(mushroom-number-card) .actions, :host(mushroom-select-card) .actions, :host(mushroom-climate-card) .actions,
      :host(mushroom-media-player-card) .actions, :host(mushroom-cover-card) .actions, :host(mushroom-fan-card) .actions { display: none !important; }
      :host(ha-full-calendar) .header { display: none !important; }
      :host(hui-todo-list-card) .addRow { display: none !important; }
      .leaflet-top, .leaflet-control-zoom { display: none !important; }
      .leaflet-tile-pane { filter: var(--v-plan) !important; }
      :host(hui-media-control-card) .background.off, :host(hui-media-control-card) .background.unavailable { display: none !important; }
      :host(hui-media-control-card) .background.off ~ .player, :host(hui-media-control-card) .background.unavailable ~ .player { color: var(--v-texte) !important; }
      :host(hui-media-control-card) .controls { display: none !important; }
      ${net ? `
      .card-header { text-transform: uppercase; letter-spacing: .16em; font-size: 15px !important; font-weight: 400 !important; color: var(--v-accent) !important; line-height: 1.4 !important; padding-bottom: 8px !important; }
      :host(ha-card) { border-radius: 3px !important; }
      ` : ""}`;
  }

  _percer(racine) {
    if (!this._feuille) { try { this._feuille = new CSSStyleSheet(); this._feuille.replaceSync(this._regles()); } catch (e) { this._feuille = false; } }
    if (!this._feuille) return;
    const voir = (noeud) => {
      const r = noeud.shadowRoot;
      if (r) {
        try { if (!r.adoptedStyleSheets.includes(this._feuille)) r.adoptedStyleSheets = [...r.adoptedStyleSheets, this._feuille]; } catch (e) { /* cette carte garde son allure */ }
        for (const x of r.children) voir(x);
      }
      for (const x of noeud.children) voir(x);
    };
    try { voir(racine); } catch (e) { /* sans gravité */ }
  }

  // Avec « diag=1 » dans l'adresse, les temps de chargement s'écrivent en haut de l'écran.
  _note(texte) {
    if (!this._q || !["1", "3"].includes(this._q.get("diag"))) return;
    let d = this.shadowRoot.querySelector(".diag");
    if (!d) { d = document.createElement("div"); d.className = "diag"; d.style.cssText = "position:absolute;left:1vw;top:1vh;z-index:9;font:14px Consolas,monospace;color:#fff;white-space:pre"; this.shadowRoot.appendChild(d); }
    d.textContent += `${((Date.now() - this._debut) / 1000).toFixed(1)} s  ${texte}\n`;
  }

  async _ressources() {
    // Les cartes de Home Assistant et celles de la communauté (HACS) se chargent avec le moteur des tableaux de bord.
    try {
      if (!window.loadCardHelpers) {
        await customElements.whenDefined("partial-panel-resolver");
        const r = document.createElement("partial-panel-resolver");
        r.hass = { panels: [{ url_path: "tmp", component_name: "lovelace" }] };
        r._updateRoutes();
        this._note("moteur : demandé");
        await r.routerOptions.routes.tmp.load();
        this._note("moteur : chargé");
      }
    } catch (e) { /* on tente quand même */ }
    try {
      const res = await this._hass.callWS({ type: "lovelace/resources" });
      this._note(`ressources : ${(res || []).length} à charger`);
      // Les cartes de la communauté se chargent en même temps ; on ne les attend pas plus de cinq secondes.
      await Promise.race([pause(5000), Promise.all((res || []).map((x) => {
        if (x.type === "module") return import(x.url).catch(() => null);
        if (x.type === "js") return new Promise((ok) => { const s = document.createElement("script"); s.src = x.url; s.onload = s.onerror = ok; document.head.appendChild(s); });
        if (x.type === "css") { const l = document.createElement("link"); l.rel = "stylesheet"; l.href = x.url; document.head.appendChild(l); }
        return null;
      }))]);
    } catch (e) { /* sans ressources, les cartes d'origine suffisent */ }
    this._note("ressources : finies, aides " + (window.loadCardHelpers ? "présentes" : "absentes"));
    for (let i = 0; i < 24 && !window.loadCardHelpers; i++) await pause(250);
    this._note("aides : " + (window.loadCardHelpers ? "ok" : "jamais venues"));  // i18n-ignorer : diagnostic (?diag=…), lu par le mainteneur seulement, reste en français comme les journaux
    return window.loadCardHelpers ? window.loadCardHelpers() : null;
  }

  async _demarrer() {
    this._debut = Date.now();
    this._q = new URLSearchParams(location.search);
    try {
      const n = (parseInt(sessionStorage.getItem("visionVeille") || "0", 10) || 0) + 1;
      sessionStorage.setItem("visionVeille", String(n));
      const nav = (performance.getEntriesByType("navigation")[0] || {}).type || "?";
      setTimeout(() => this._note(`chargement n° ${n} (${nav}) à ${new Date().toLocaleTimeString(LOCALE)}, page ouverte depuis ${(performance.now() / 1000).toFixed(1)} s`), 0);  // i18n-ignorer : diagnostic (?diag=…), lu par le mainteneur seulement, reste en français comme les journaux
    } catch (e) { /* sans importance */ }
    this._theme();
    // Veille-lot : la pastille derrière les valeurs (réglage de la télé) ; le ticket de l'appareil pour les images des
    // cases typées ; la dérive, seulement si la télé ne la fait pas déjà sur toute la page (elle envoie « pastille »).
    this.setAttribute("data-pastille", this._q.get("pastille") === "1" ? "1" : "0");
    // 1.93.05 (l'auteur, retour 39) : la télé dit où se pose l'œil (un coin, ou « cache ») ; quand un de ses coins de droite
    // est pris (QR des invités, logo, bandeau), la demande d'en bas et la petite heure d'en haut se décalent d'autant.
    const coin = this._q.get("logo") || "";
    this.setAttribute("data-logo", ["haut_gauche", "haut_droite", "bas_droite", "cache"].includes(coin) ? coin : "bas_gauche");
    const vw = (k) => { const v = parseFloat(this._q.get(k) || ""); return v > 0 && v < 60 ? `${v}vw` : ""; };
    this.style.setProperty("--v-adroite", vw("adroite") || "3.5vw");
    this.style.setProperty("--v-pdroite", vw("pdroite") || "3.5vw");
    this._ticket = ticketVeille(this._hass);
    this._deriveLocale = !(window.VisionAndroid && this._q.has("pastille"));
    this.shadowRoot.innerHTML = `
      <style>
        :host { position: fixed; inset: 0; z-index: 9999; background: var(--v-fond); color: var(--v-texte); overflow: hidden; cursor: none;
                font-family: "Segoe UI", Roboto, sans-serif; }
        * { cursor: none !important; }
        .scene { position: absolute; inset: 0; opacity: 0; transition: opacity .6s ease; pointer-events: none; }
        .scene.vue { opacity: 1; }
        .horloge { display: flex; flex-direction: column; align-items: center; justify-content: center; height: 100%; }
        .heure { font-size: min(26vh, 30vw); font-weight: 300; line-height: 1; letter-spacing: -.02em; }
        .date { font-size: min(4.6vh, 6vw); color: var(--v-texte2); margin-top: 2vh; }
        .ciel { font-size: min(3.8vh, 5vw); color: var(--v-accent); margin-top: 3vh; }
        /* Anti-marquage : la dérive se fait par pixels entiers, une fois par seconde au plus (_deriver), plus par une
           animation continue (elle recomposait l'écran à chaque image, et le texte tombait entre deux pixels). */
        .annonce { position: absolute; right: var(--v-adroite, 3.5vw); bottom: 2.6vh; z-index: 6; max-width: 60vw; padding: .9vh 2.2vh; border-radius: 99px; background: var(--v-carte); color: var(--v-texte2);
                   font-size: min(2.4vh, 3.6vw); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; opacity: 0; transition: opacity .5s ease; }
        .annonce::before { content: ""; display: inline-block; width: 1.1vh; height: 1.1vh; border-radius: 50%; background: var(--v-accent); margin-right: 1.2vh; }
        .annonce.vu { opacity: .96; }
        :host([data-style="neoretro"]) .annonce { border-radius: 3px; letter-spacing: .06em; }
        :host([data-style="neoretro"]) .annonce::before { border-radius: 0; }
        .fige { position: absolute; left: 0; right: 0; text-align: center; bottom: 3vh; z-index: 6; pointer-events: none; font-size: min(2.4vh, 4vw); letter-spacing: .14em; text-transform: uppercase; color: var(--v-texte2); opacity: 0; transition: opacity .4s ease; }
        .fige.vu { opacity: .95; }
        .tete { display: flex; justify-content: space-between; align-items: baseline; padding: 3.2vh var(--v-pdroite, 3.5vw) 1.6vh 3.5vw; }
        .titre { font-size: min(3.6vh, 6.5vw); font-weight: 600; }
        .petite { font-size: min(3.2vh, 5.5vw); color: var(--v-texte2); }
        .cadre { position: absolute; left: 3.5vw; right: 3.5vw; top: 11vh; bottom: 9.5vh; overflow: hidden; }
        .grille { position: absolute; left: 0; top: 0; display: grid; gap: 34px 44px; align-items: start; transform-origin: top left; }
        .section { display: grid; grid-template-columns: repeat(12, 1fr); gap: 18px; align-items: start; }
        .section > * { min-width: 0; }
        .sec { grid-column: 1 / -1; font-size: 13px; font-weight: 700; letter-spacing: .08em; text-transform: uppercase; color: var(--v-accent); margin: 2px 2px 0; }
        /* Néo-rétro : filets d'or, angles nets, capitales espacées, chiffres de tableau de bord. */
        :host([data-style="neoretro"]) { font-family: Bahnschrift, "DIN Alternate", "Roboto Condensed", "Segoe UI", sans-serif; }
        :host([data-style="neoretro"]) .tete { margin: 0 3.5vw; padding: 3.2vh 0 1.4vh; border-bottom: 1.5px solid var(--v-accent); align-items: flex-end; }
        :host([data-style="neoretro"]) .titre { text-transform: uppercase; letter-spacing: .22em; font-weight: 400; font-size: 3vh; }
        :host([data-style="neoretro"]) .titre::before { content: ""; display: inline-block; width: 1.5vh; height: 1.5vh; background: var(--v-accent); margin-right: 1.6vh; }
        :host([data-style="neoretro"]) .petite { letter-spacing: .12em; font-variant-numeric: tabular-nums; color: var(--v-texte); }
        :host([data-style="neoretro"]) .sec { letter-spacing: .26em; font-weight: 400; }
        :host([data-style="neoretro"]) .h-defaut .heure { font-weight: 300; letter-spacing: .04em; font-variant-numeric: tabular-nums; font-size: min(30vh, 28vw); }
        :host([data-style="neoretro"]) .h-defaut::before { content: "VISION"; letter-spacing: .6em; font-size: 2.2vh; color: var(--v-accent); margin-bottom: 4vh; padding-left: .6em; }
        :host([data-style="neoretro"]) .h-defaut .date { text-transform: uppercase; letter-spacing: .3em; font-size: min(3vh, 4.2vw); margin-top: 3vh; padding-top: 3vh; border-top: 1.5px solid var(--v-accent); min-width: 46vw; text-align: center; }
        :host([data-style="neoretro"]) .h-defaut .ciel { text-transform: uppercase; letter-spacing: .3em; font-size: min(2.6vh, 3.8vw); }
        /* La marque, en bas à gauche : l'œil de Vision (le même que sur les télés) et son nom. */
        .marque { position: absolute; left: 3.5vw; bottom: 1.6vh; height: 7vh; display: flex; align-items: center; z-index: 5; pointer-events: none; }
        .marque span { margin-left: 1vh; font-size: 2.6vh; font-weight: 700; letter-spacing: .12em; color: var(--v-texte2); opacity: .9; }
        /* 1.93.05 (retour 39) : l'œil dans le coin choisi sur la télé (en bas à gauche par défaut), ou masqué. */
        :host([data-logo="haut_gauche"]) .marque { bottom: auto; top: 1.6vh; }
        :host([data-logo="haut_droite"]) .marque, :host([data-logo="bas_droite"]) .marque { left: auto; right: 3.5vw; flex-direction: row-reverse; }
        :host([data-logo="haut_droite"]) .marque { bottom: auto; top: 1.6vh; }
        :host([data-logo="haut_droite"]) .marque span, :host([data-logo="bas_droite"]) .marque span { margin-left: 0; margin-right: 1vh; }
        :host([data-logo="cache"]) .marque { display: none; }
        mushroom-chips-card { margin-bottom: 4px; }
        ${CSS_HORLOGES}
        ${CSS_STYLES_HORLOGE}
        ${CSS_TYPEES}
        .vide { display: flex; align-items: center; justify-content: center; height: 100%; color: var(--v-texte2); font-size: 3vh; }
      </style>
      <div class="marque"><canvas></canvas><span>Vision</span></div><div class="fige"></div><div class="annonce"></div>
      <div class="scene vue" id="s0">${this._horloge("")}</div>`;
    // L'heure s'affiche tout de suite ; les tableaux arrivent dès que Home Assistant a répondu.
    this._tic();
    // L'appli Android garde son propre dessin tant que cette page n'a pas dit qu'elle est là.
    try { if (window.VisionAndroid && window.VisionAndroid.pret) window.VisionAndroid.pret(); } catch (e) { /* hors de l'appli */ }
    // Plusieurs écrans : jamais la même chose sur deux d'entre eux. L'heure est sur le premier dès l'ouverture ;
    // les autres attendent leur tableau sans la répéter.
    const nbEcrans = parseInt(this._q.get("ecrans") || "1", 10) || 1;
    this._rang = parseInt(this._q.get("dec") || "0", 10) || 0;
    if (nbEcrans > 1 && this._rang > 0) this.shadowRoot.querySelector("#s0 .horloge").style.visibility = "hidden";
    let page = null, aides = null;
    try {
      [page, aides] = await Promise.all([
        // 1.77.0 : sur un appareil, la passerelle de la veille donne sa page ; dans un navigateur connecté, l'API.
        this._hass.callWS({ type: "pc_parental/veille_page" })
          .catch(() => this._hass.callApi("GET", `pc_parental/veille/page?id=${encodeURIComponent(this._q.get("id") || "")}&ecran=${encodeURIComponent(this._q.get("ecran") || "tele")}&essai=${encodeURIComponent(this._q.get("essai") || "")}`))
          .then((r) => { this._note("page : reçue"); return r; }),
        this._ressources(),
      ]);
    } catch (e) { page = null; this._note("erreur : " + e); }
    this._note("page et aides : prêtes");  // i18n-ignorer : diagnostic (?diag=…), lu par le mainteneur seulement, reste en français comme les journaux
    this._page = page || { horloge: { actif: true, duree: 30 }, tableaux: [] };
    this._aides = aides;
    const masques = new Set((this._q.get("masques") || "").split(",").filter(Boolean));
    const cachees = new Set((this._q.get("cartes") || "").split(",").filter(Boolean));
    // Les animations des cartes : toutes (« anim=0 » les coupe), sauf celles qu'on a choisi de laisser fixes (« figees »).
    this._anim = this._q.get("anim") !== "0";
    const figees = new Set((this._q.get("figees") || "").split(",").filter(Boolean));
    // La liste des écrans à enchaîner : l'horloge, puis les tableaux (découpés à l'affichage).
    const liste = [];
    // Chaque appareil peut donner sa durée à chaque tableau (« durees=horloge:20,dash_x_y:35 »).
    const durees = {};
    for (const m of (this._q.get("durees") || "").split(",")) { const k = m.lastIndexOf(":"); const v = parseInt(m.slice(k + 1), 10); if (k > 0 && v > 0) durees[m.slice(0, k)] = Math.max(5, Math.min(600, v)); }
    if (this._page.horloge && this._page.horloge.actif && !masques.has("horloge")) liste.push({ genre: "horloge", duree: durees.horloge || this._page.horloge.duree || 15 });
    for (const t of this._page.tableaux || []) {
      if (masques.has(t.id)) continue;
      const sections = [];
      for (const s of t.sections || []) {
        const cartes = (s.cartes || []).filter((c) => !cachees.has(`${t.id}|${c.cle}`) && !(c.cles && c.cles.length && c.cles.every((k) => cachees.has(`${t.id}|${k}`))));
        for (const c of cartes) c.fige = figees.has(`${t.id}|${c.cle}`) || !!(c.cles && c.cles.length && c.cles.every((k) => figees.has(`${t.id}|${k}`)));
        // Veille-lot : la case typée de la carte, si Home Assistant l'a jointe (rangée à côté des cartes).
        for (const c of cartes) c.typee = this._typeeUtile(t.typees && typeof t.typees === "object" ? t.typees[c.cle] : null);
        // Une section dont il ne reste que le titre (toutes ses cartes sont décochées) disparaît avec lui.
        if (cartes.some((c) => c.config && c.config.type !== "heading")) sections.push({ titre: s.titre, span: s.span || 1, cartes });
      }
      if (durees[t.id]) t.duree = durees[t.id];
      if (parseInt(this._q.get("duree") || "0", 10) > 0) t.duree = parseInt(this._q.get("duree"), 10);
      if (sections.length) liste.push({ genre: "tableau", duree: t.duree || 25, titre: t.titre, colonnes: t.colonnes || 4, sections, pages: 1, page: 0 });
    }
    if (!liste.length) liste.push({ genre: "horloge", duree: 30 });
    this._ecrans = liste;
    this._indice = this._rang % liste.length;
    setInterval(() => this._tic(), 1000);
    this._guetterGel();
    this._oeil();
    // Les écrans avancent ensemble, au même pas, chacun décalé d'un cran : l'écran 1 montre l'horloge quand le 2
    // montre le premier tableau, puis le 1 passe au premier tableau et le 2 au suivant, et ainsi de suite.
    // L'heure de départ commune (donnée par l'appareil) et l'horloge du système tiennent les écrans d'accord.
    this._synchro = nbEcrans > 1 && liste.length > 1;
    if (this._synchro) {
      this._t0 = parseInt(this._q.get("t0") || "0", 10) || 0;
      const impose = parseInt(this._q.get("duree") || "0", 10);
      this._pas = impose > 0 ? Math.max(5, impose) : Math.max(12, Math.round(liste.reduce((a, x) => a + (x.duree || 20), 0) / liste.length));
      this._montrer();
      return;
    }
    // L'heure est à l'écran depuis l'ouverture : dès que les cartes sont prêtes (et après quatre secondes
    // d'horloge au moins), on passe au premier tableau au lieu de refaire un tour d'horloge.
    const premier = liste[this._indice];
    if (premier.genre === "horloge" && liste.length > 1) {
      this._indice = (this._indice + 1) % liste.length;
      // Après un rechargement de la page (Home Assistant en fait un à la première ouverture), on n'attend pas.
      const recharge = ((performance.getEntriesByType("navigation")[0] || {}).type === "reload");
      setTimeout(() => this._montrer(), recharge ? 0 : Math.max(0, 4000 - (Date.now() - this._debut)));
    } else this._montrer();
  }

  // L'œil de Vision, dessiné comme sur les télés : une amande claire, l'iris pourpre et or qui regarde
  // ici et là, la paupière qui cligne vite et se rouvre plus doucement, parfois deux fois de suite.
  _oeil() {
    const toile = this.shadowRoot.querySelector(".marque canvas");
    const g = toile.getContext("2d");
    const pourpre = this._sombre ? "#C2577B" : "#8C2F4B";
    const or = this._couleur("accent", "#F2C14E"), encre = this._sombre ? "#1B1418" : this._couleur("texte", "#2A2026");
    let prochainClin = 0, clinDebut = -9, prochainRegard = 0, cibleX = 0, cibleY = 0, rx = 0, ry = 0;
    let dernier = 0;
    const pas = () => {
      // Trente images par seconde suffisent à un clin d'œil ; au-delà, c'est de la carte graphique gaspillée.
      if (performance.now() - dernier < 30) { requestAnimationFrame(pas); return; }
      dernier = performance.now();
      const t = performance.now() / 1000, H = window.innerHeight, dpr = window.devicePixelRatio || 1;
      const demiL = H * 0.03, demiH = H * 0.017, L = demiL * 2 + H * 0.012, HH = demiH * 4 + H * 0.012;
      if (toile.width !== Math.round(L * dpr)) { toile.width = Math.round(L * dpr); toile.height = Math.round(HH * dpr); toile.style.width = `${L}px`; toile.style.height = `${HH}px`; }
      g.setTransform(dpr, 0, 0, dpr, 0, 0);
      g.clearRect(0, 0, L, HH);
      if (t >= prochainClin) { clinDebut = t; prochainClin = t + (Math.random() < 0.18 ? 0.4 : 2.5 + Math.random() * 5); }
      const tt = t - clinDebut;
      const fermeture = tt < 0 ? 0 : tt < 0.10 ? tt / 0.10 : tt < 0.26 ? 1 - (tt - 0.10) / 0.16 : 0;
      if (t >= prochainRegard) { cibleX = (Math.random() - 0.5) * 1.6; cibleY = (Math.random() - 0.5) * 0.9; prochainRegard = t + 1.5 + Math.random() * 4; }
      rx += (cibleX - rx) * 0.3; ry += (cibleY - ry) * 0.3;
      const cx = L / 2, cy = HH / 2, ouverture = demiH * (1 - fermeture * 0.96), bas = demiH * 2 * (1 - fermeture * 0.5);
      const amande = new Path2D();
      amande.moveTo(cx - demiL, cy); amande.quadraticCurveTo(cx, cy - ouverture * 2, cx + demiL, cy); amande.quadraticCurveTo(cx, cy + bas, cx - demiL, cy); amande.closePath();
      g.globalAlpha = 1; g.fillStyle = this._sombre ? "#E9E2D6" : "#FFFBF2"; g.fill(amande);
      g.save(); g.clip(amande);
      const ix = cx + rx * demiL * 0.45, iy = cy + ry * demiH * 0.5, ri = demiH * 0.95;
      const disque = (x, y, r, c, a = 1) => { g.globalAlpha = a; g.fillStyle = c; g.beginPath(); g.arc(x, y, r, 0, Math.PI * 2); g.fill(); };
      disque(ix, iy, ri, pourpre); disque(ix, iy, ri * 0.62, or); disque(ix, iy, ri * 0.34, encre);
      disque(ix - ri * 0.28, iy - ri * 0.3, ri * 0.14, "#FFFFFF", 0.78);
      g.restore();
      g.lineCap = "round"; g.strokeStyle = pourpre;
      g.globalAlpha = 1; g.lineWidth = H * 0.004; g.beginPath(); g.moveTo(cx - demiL, cy); g.quadraticCurveTo(cx, cy - ouverture * 2, cx + demiL, cy); g.stroke();
      g.globalAlpha = 0.59; g.lineWidth = H * 0.002; g.beginPath(); g.moveTo(cx - demiL, cy); g.quadraticCurveTo(cx, cy + bas, cx + demiL, cy); g.stroke();
      g.globalAlpha = 1;
      requestAnimationFrame(pas);
    };
    pas();
  }

  // Chaque thème a son horloge (« horloge=<thème> » dans l'adresse) ; sans thème connu, celle de Vision. Un style
  // choisi sur l'appareil (« hstyle=fine », mêmes noms que l'appli et le PC) passe avant ; sans lui, rien ne change.
  _horloge(ciel) {
    const style = styleHorloge(this._q.get("hstyle") || "");
    const cle = style ? "s-" + style : HORLOGES[this._q.get("horloge") || ""] ? this._q.get("horloge") : "defaut";
    const d = new Date(), maj = (t) => t.charAt(0).toUpperCase() + t.slice(1);
    const jour = maj(trDate(d, { weekday: "long", day: "numeric", month: "long" }));
    const jourCourt = maj(trDate(d, { weekday: "short", day: "numeric", month: "short" }));
    return `<div class="horloge h-${cle}" data-cle="${cle}">${HORLOGES[cle].html({ jour, jourCourt, ciel: ciel || "" })}</div>`;
  }

  // La langue a changé : chaque horloge à l'écran est redessinée dans la nouvelle (date, ciel, inscriptions).
  _relangue() {
    this.shadowRoot.querySelectorAll(".horloge").forEach((h) => {
      try {
        const x = document.createElement("div");
        x.innerHTML = this._horloge("");
        const neuve = x.firstElementChild;
        neuve.style.visibility = h.style.visibility;
        h.replaceWith(neuve);
      } catch (e) { /* l'horloge garde sa langue jusqu'au prochain écran */ }
    });
    this._tic();
  }

  _tic() {
    const d = new Date();
    const h = trHeure(d);
    this.shadowRoot.querySelectorAll(".petite").forEach((e) => { if (e.textContent !== h) e.textContent = h; });
    this.shadowRoot.querySelectorAll(".horloge").forEach((e) => { try { HORLOGES[e.dataset.cle].tic(e, d); } catch (err) { /* l'horloge garde son dernier dessin */ } });
    if (!this._q) return;
    try { this._majMeteo(); this._rafraichirMedias(); this._deriver(); } catch (err) { /* la seconde suivante réessaie */ }
  }

  _carte(config, typee) {
    if (typee) { try { const el = this._carteTypee(typee, config || {}); if (el) return el; } catch (e) { /* Home Assistant la dessine */ } }
    if (config && config.type === "iframe") return this._cadreWeb(config);
    let el;
    try { el = this._aides ? this._aides.createCardElement(config) : null; } catch (e) { el = null; }
    if (!el) return null;
    try { el.hass = this._pourCartes(); } catch (e) { /* idem */ }
    this._cartes.push(el);
    // La largeur voulue dans Home Assistant (sur 12), reprise telle quelle.
    let colonnes = 12;
    const o = config.grid_options || config.layout_options || {};
    const v = o.columns ?? o.grid_columns;
    if (v === "full") colonnes = 12;
    else if (typeof v === "number") colonnes = Math.max(3, Math.min(12, v));
    else { try { const g = el.getGridOptions && el.getGridOptions(); if (g && typeof g.columns === "number") colonnes = Math.max(3, Math.min(12, g.columns)); } catch (e) { /* 12 */ } }
    el.style.gridColumn = `span ${colonnes}`;
    // Sans largeur écrite dans le tableau de bord, c'est la carte qui dit la sienne ; elle ne la connaît
    // qu'une fois chargée, on la relit donc au moment de mesurer.
    if (v === undefined) el._largeurLibre = true;
    // Un titre ne reste jamais seul en bas d'une colonne : il suit sa carte.
    // Une page web embarquée (carte météo animée…) est vue de deux fois plus loin :
    // le site dispose d'une vraie largeur et ses boutons ne se chevauchent plus.
    if (config.type === "heading") { el.style.breakAfter = "avoid"; el.style.display = "block"; }
    return el;
  }

  // ------------------------------------------------------------------ Les cartes web (iframe)
  //
  // Une page web dans la veille est une page dans une page : sur une petite télé (Chromecast HD), elle tourne
  // dans le même fil que la veille, et un site lourd (un radar animé en WebGL) la fige le temps qu'il calcule.
  // Elle ne doit donc jamais vivre hors de l'écran : son cadre est posé vide pendant la préparation, la page n'y
  // est chargée que quand son tableau se montre, et elle est déchargée dès qu'il s'efface. Une page qui ne se
  // charge pas à temps, ou qui fige la veille, est remplacée par une mention : la rotation ne l'attend jamais.

  // Veille-lot : l'iframe d'une case typée (« typee ») n'ouvre que l'adresse https de la case, hors de l'origine de
  // Home Assistant, dans un bac à sable strict (scripts seulement : ni formulaires, ni fenêtres, ni navigation de la
  // veille) ; une seule à la fois, les autres gardent leur nom.
  _cadreWeb(config, typee) {
    const boite = document.createElement("div");
    boite.className = "web";
    let colonnes = 12;
    const o = config.grid_options || config.layout_options || {};
    const v = o.columns ?? o.grid_columns;
    if (typeof v === "number") colonnes = Math.max(3, Math.min(12, v));
    // La proportion voulue dans Home Assistant : « 50% », « 16:9 » ou « 16x9 ».
    let ratio = 56.25;
    const a = String(config.aspect_ratio || "").trim();
    const m = a.match(/^(\d+(?:\.\d+)?)\s*[:x]\s*(\d+(?:\.\d+)?)$/);
    if (/%$/.test(a) && parseFloat(a) > 0) ratio = parseFloat(a);
    else if (m && parseFloat(m[1]) > 0) ratio = (parseFloat(m[2]) / parseFloat(m[1])) * 100;
    boite.style.cssText = "position:relative;overflow:hidden;width:100%;border-radius:var(--ha-card-border-radius,18px);" +
      `break-inside:avoid;padding-top:${ratio}%;background:var(--ha-card-background,var(--card-background-color));`;
    boite.style.gridColumn = `span ${colonnes}`;
    boite._url = String(config.url || "");
    if (typee) {
      boite._strict = true; boite._nom = String(typee.nom || "");
      let sure = false;
      try { const u = new URL(boite._url); sure = u.protocol === "https:" && u.origin !== location.origin && !u.username && !u.password; } catch (e) { sure = false; }
      if (!sure) boite._url = "";
    }
    boite._mention = document.createElement("div");
    boite._mention.className = "web-mention";
    boite._mention.style.cssText = "position:absolute;inset:0;display:flex;align-items:center;justify-content:center;" +
      "text-align:center;padding:1em;opacity:.55;font-size:.9em;";
    boite.appendChild(boite._mention);
    this._mentionWeb(boite, boite._strict && !boite._url ? boite._nom : "");
    return boite;
  }

  _mentionWeb(boite, texte) {
    boite._mention.textContent = texte;
    boite._mention.style.display = texte ? "flex" : "none";
  }

  // Les sites trop lourds pour cet appareil, retenus un jour (localStorage, propre à l'appareil).
  _lourds() {
    try {
      const brut = JSON.parse(localStorage.getItem("vision-veille-lourds") || "{}");
      const jour = Date.now() - 24 * 3600 * 1000;
      for (const k of Object.keys(brut)) if (brut[k] < jour) delete brut[k];
      return brut;
    } catch (e) { return {}; }
  }

  _retenirLourd(url) {
    try { const l = this._lourds(); l[url] = Date.now(); localStorage.setItem("vision-veille-lourds", JSON.stringify(l)); } catch (e) { /* sans mémoire, on retirera la page à chaque gel */ }
  }

  // Le tableau se montre : ses cartes web visibles se chargent, celles des parties cachées se déchargent.
  _allumerWeb(scene) {
    let stricte = false;
    for (const boite of scene.querySelectorAll(".web")) {
      const section = boite.closest(".section");
      const visible = !section || section.style.visibility !== "hidden";
      if (!visible) { this._eteindreBoite(boite); continue; }
      if (boite._strict && boite._url) {
        if (stricte) { this._eteindreBoite(boite); this._mentionWeb(boite, boite._nom); continue; }
        stricte = true;
      }
      if (boite._cadre || !boite._url) continue;
      if (this._lourds()[boite._url]) {
        this._mentionWeb(boite, tr("web_lourde"));
        continue;
      }
      const cadre = document.createElement("iframe");
      // Par un zoom, pas par une transformation : une page réduite par « transform » se redessine en quatre fois
      // plus de points puis se recompose à chaque image. Le site dispose ainsi d'une vraie largeur.
      cadre.style.cssText = "position:absolute;top:0;left:0;width:200%;height:200%;border:0;zoom:0.5;";
      cadre.setAttribute("referrerpolicy", "no-referrer");
      cadre.setAttribute("tabindex", "-1");
      if (boite._strict) { cadre.setAttribute("sandbox", "allow-scripts allow-same-origin"); cadre.setAttribute("allow", ""); }  // i18n-ignorer : technique (attribut, classe, sélecteur)
      boite._charge = false;
      // Le témoin : si la veille est relancée pendant que cette page est allumée (une page qui fige tout fait
      // relancer la veille par l'appli), on le retrouvera au redémarrage et la page sera mise de côté.
      try { localStorage.setItem("vision-veille-web-en-cours", boite._url); } catch (e) { /* sans témoin */ }
      cadre.addEventListener("load", () => { boite._charge = true; this._mentionWeb(boite, ""); });
      this._mentionWeb(boite, tr("chargement"));
      cadre.src = boite._url;
      boite._cadre = cadre;
      boite.appendChild(cadre);
      // Garde-fou : une page qui ne s'est pas chargée à temps est retirée, la veille continue sans elle.
      clearTimeout(boite._delai);
      boite._delai = setTimeout(() => {
        if (boite._cadre === cadre && !boite._charge) {
          this._note("carte web sans réponse : " + boite._url);  // i18n-ignorer : diagnostic (?diag=…), lu par le mainteneur seulement, reste en français comme les journaux
          this._eteindreBoite(boite);
          this._mentionWeb(boite, tr("web_injoignable"));
        }
      }, 12000);
    }
  }

  _eteindreBoite(boite) {
    clearTimeout(boite._delai);
    if (boite._cadre) {
      try { if (localStorage.getItem("vision-veille-web-en-cours") === boite._url) localStorage.removeItem("vision-veille-web-en-cours"); } catch (e) { /* idem */ }
      try { boite._cadre.src = "about:blank"; } catch (e) { /* on la retire quand même */ }
      boite._cadre.remove();
      boite._cadre = null;
    }
    boite._charge = false;
    this._mentionWeb(boite, "");
  }

  // Le tableau s'efface (ou la veille s'endort) : ses pages web sont déchargées tout de suite.
  _eteindreWeb(racine) {
    if (!racine) return;
    for (const boite of racine.querySelectorAll(".web")) this._eteindreBoite(boite);
  }

  // Le détecteur de gel : une seconde qui en dure plus de quatre, c'est que quelque chose a figé la veille.
  // Si une page web était allumée, c'est elle : elle est retirée, et retenue comme trop lourde pour un jour.
  _guetterGel() {
    if (this._guetGel) return;
    this._guetGel = true;
    // Au démarrage : un témoin resté là, c'est que la veille a été relancée avec cette page allumée.
    try {
      const restee = localStorage.getItem("vision-veille-web-en-cours");
      if (restee) {
        localStorage.removeItem("vision-veille-web-en-cours");
        this._retenirLourd(restee);
        this._note("la veille a redémarré pendant que cette page était allumée, elle est mise de côté : " + restee);  // i18n-ignorer : diagnostic (?diag=…), lu par le mainteneur seulement, reste en français comme les journaux
      }
    } catch (e) { /* sans mémoire */ }
    let avant = Date.now();
    setInterval(() => {
      const maintenant = Date.now();
      const retard = maintenant - avant - 1000;
      avant = maintenant;
      if (retard < 4000 || this._dort) return;
      const allumees = [...this.shadowRoot.querySelectorAll(".web")].filter((b) => b._cadre);
      this._note(`veille figée ${Math.round(retard / 1000)} s` + (allumees.length ? ` — page web en cause : ${allumees.map((b) => b._url).join(", ")}` : ""));  // i18n-ignorer : diagnostic (?diag=…), lu par le mainteneur seulement, reste en français comme les journaux
      for (const boite of allumees) {
        this._retenirLourd(boite._url);
        this._eteindreBoite(boite);
        this._mentionWeb(boite, tr("web_figeait"));
      }
    }, 1000);
  }

  // L'appli garde cette page chargée entre deux veilles, pour qu'elle apparaisse tout de suite. Entre-temps
  // elle dort : plus de défilement, plus de cartes à tenir à jour, seule l'horloge reste prête.
  dormir() {
    this._dort = true; this._fige = false;
    this._passage = (this._passage || 0) + 1;
    this._eteindreWeb(this.shadowRoot);
    clearTimeout(this._minuteur);
    this._cartes = [];
    for (const e of this._ecrans) { if (e.genre === "tableau") { e.page = 0; e._colonnes = null; } }
    this.shadowRoot.querySelectorAll(".scene").forEach((x) => x.remove());
    const scene = document.createElement("div");
    scene.className = "scene vue";  // i18n-ignorer : noms de classes CSS
    scene.innerHTML = this._horloge("");
    this.shadowRoot.appendChild(scene);
    const f = this.shadowRoot.querySelector(".fige"); if (f) f.classList.remove("vu");
    this._tic();
  }
  // La veille s'ouvre : on repart du début (l'horloge si elle est montrée), avec des cartes fraîches.
  reprendre() {
    const dormait = this._dort;
    this._dort = false; this._fige = false;
    if (!this._ecrans.length) return;
    if (!dormait && this.shadowRoot.querySelector(".scene .grille")) return;
    this._indice = this._rang % this._ecrans.length;
    for (const e of this._ecrans) { if (e.genre === "tableau") e.page = 0; }
    this._tic();
    this._montrer();
  }

  // Les gestes de l'appareil (télécommande, doigt) : précédent, suivant, figer.
  aller(delta) {
    if (!this._ecrans.length) return;
    clearTimeout(this._minuteur);
    if (this._synchro) { this._montrer(); return; }
    if (delta > 0) { this._suivant(true); return; }
    const e = this._ecrans[this._indice];
    if (e.genre === "tableau" && e.page > 0) e.page -= 1;
    else { if (e.genre === "tableau") e.page = 0; const n = this._ecrans.length; this._indice = (this._indice - 1 + n) % n; const p = this._ecrans[this._indice]; if (p.genre === "tableau") p.page = 0; }
    this._montrer();
  }
  // Un mot de l'appareil, en bas à droite (une demande d'accès en attente, par exemple) ; vide pour l'effacer.
  annoncer(texte) {
    const b = this.shadowRoot.querySelector(".annonce");
    if (!b) return;
    if (texte) b.textContent = String(texte).slice(0, 140);
    b.classList.toggle("vu", !!texte);
  }
  figer() {
    this._fige = !this._fige;
    const f = this.shadowRoot.querySelector(".fige");
    if (f) { f.textContent = this._fige ? tr("fige") : tr("reprise"); f.classList.add("vu"); clearTimeout(this._figeT); if (!this._fige) this._figeT = setTimeout(() => f.classList.remove("vu"), 1500); }
    clearTimeout(this._minuteur);
    if (!this._fige) this._minuteur = setTimeout(() => (this._synchro ? this._montrer() : this._suivant()), 4000);
    return this._fige;
  }

  // Avec « diag=3 », le nombre d'images dessinées et celles qui tardent (plus de 34 ms) s'écrit toutes les deux
  // secondes, à l'écran et dans le titre de la fenêtre : c'est la mesure des secousses.
  _sonde() {
    if (this._sondeLancee || !this._q || this._q.get("diag") !== "3") return;
    this._sondeLancee = true;
    let avant = performance.now(), n = 0, lentes = 0, pire = 0;
    const bilan = [];
    const pas = (t) => { const d = t - avant; avant = t; n++; if (d > 34) { lentes++; pire = Math.max(pire, d); } requestAnimationFrame(pas); };
    requestAnimationFrame(pas);
    setInterval(() => {
      const e = this._ecrans && this._ecrans[this._indice];
      bilan.push(`${n}/${lentes}`); document.title = "VISION " + bilan.join(" ");
      this._note(`${n} images, ${lentes} lentes, pire ${Math.round(pire)} ms  [${e ? e.titre || e.genre : "-"}]`);
      n = 0; lentes = 0; pire = 0;
    }, 2000);
  }

  // L'entrée en scène d'un tableau : les cartes arrivent l'une après l'autre, et ce qu'elles montrent s'anime
  // avec elles : les jauges montent, les courbes se tracent de gauche à droite, les nombres comptent jusqu'à leur
  // valeur. Une carte marquée fixe arrive d'un coup, telle quelle.
  _animer(e) {
    const pg = (e._pages && (e._pages[e.page] || e._pages[0])) || null;
    const elements = [...e._colonnes.querySelectorAll(".section > *")].filter((el) => !pg || pg.sections.includes(el.parentElement));
    const nombres = [];
    let rang = 0;
    for (const el of elements) {
      if (el._fige) continue;
      const delai = 140 + Math.min(rang++, 26) * 45;
      el.animate([{ opacity: 0, transform: "translateY(18px)" }, { opacity: 1, transform: "none" }], { duration: 480, delay: delai, easing: "cubic-bezier(.2,.7,.2,1)", fill: "backwards" });
      if (el.classList.contains("sec") || el.classList.contains("web")) continue;
      // Les nombres ne comptent que dans les cartes de Home Assistant (capteur, jauge, tuile, entités) : dans un
      // agenda ou un emploi du temps, ce sont des heures et des dates, qui n'ont pas à défiler.
      const compter = /^hui-/.test(el.localName || "") && !/calendar|clock|weather/.test(el.localName);
      const fouiller = (n, dansCourbe) => {
        const nom = n.localName || "";
        if (nom === "ha-gauge" && typeof n.value === "number" && typeof n.min === "number") {
          const v = n.value; n.value = n.min;
          setTimeout(() => { if (n.value === n.min) n.value = v; }, delai + 120);
        }
        const courbe = dansCourbe || nom === "ha-chart-base" || nom === "hui-graph-header-footer" || nom === "ha-sparkline";
        if (courbe && !dansCourbe) n.animate([{ clipPath: "inset(0 100% 0 0)" }, { clipPath: "inset(0 0 0 0)" }], { duration: 1100, delay: delai + 150, easing: "ease-out", fill: "backwards" });
        if (nom === "style" || nom === "script") return;
        const visiter = (parent) => {
          for (const x of parent.childNodes) {
            if (x.nodeType === 1) fouiller(x, courbe);
            else if (x.nodeType === 3 && compter && !courbe && nombres.length < 40) {
              const m = /^(\s*)(-?)(\d{1,3}(?:[   ]\d{3})+|\d+)(?:([.,])(\d+))?(\s?[^\d\s:.][^\d:.]{0,5})?(\s*)$/.exec(x.data);
              if (m && !(m[3].length === 4 && !m[4] && !m[6])) nombres.push({ n: x, m, fin: x.data, delai: delai + 100 });
            }
          }
        };
        if (n.shadowRoot) visiter(n.shadowRoot);
        visiter(n);
      };
      fouiller(el, false);
    }
    if (!nombres.length) return;
    const ecrire = (o, part) => {
      const m = o.m, entier = m[3].replace(/\D/g, ""), sep = (m[3].match(/\D/) || [""])[0], dec = m[5] ? m[5].length : 0;
      const v = parseFloat(entier + (dec ? "." + m[5] : "")) * part;
      let [a, b] = v.toFixed(dec).split(".");
      if (sep) a = a.replace(/\B(?=(\d{3})+$)/g, sep);
      return m[1] + m[2] + a + (dec ? m[4] + b : "") + (m[6] || "") + m[7];
    };
    for (const o of nombres) { o.dit = ecrire(o, 0); o.n.data = o.dit; }
    const debut = performance.now();
    let dernier = 0;
    const tour = (t) => {
      let reste = false;
      if (t - dernier >= 33) {
        dernier = t;
        for (const o of nombres) {
          if (o.fini) continue;
          // La carte a reçu une nouvelle valeur entre-temps : on la lui laisse.
          if (o.n.data !== o.dit) { o.fini = true; continue; }
          const p = Math.max(0, Math.min(1, (t - debut - o.delai) / 900));
          o.dit = p >= 1 ? o.fin : ecrire(o, 1 - Math.pow(1 - p, 3));
          o.n.data = o.dit;
          if (p >= 1) o.fini = true;
        }
      }
      for (const o of nombres) if (!o.fini) reste = true;
      if (reste) requestAnimationFrame(tour);
    };
    requestAnimationFrame(tour);
    // Filet de sécurité : quoi qu'il arrive (page mise en pause), chaque nombre retrouve sa vraie valeur.
    setTimeout(() => { for (const o of nombres) if (!o.fini && o.n.data === o.dit) { o.n.data = o.fin; o.fini = true; } }, 4000);
  }

  // L'en-tête et le cadre d'un tableau, avec sa grille de sections dedans.
  _garnir(scene, e, colonnes) {
    const tete = document.createElement("div");
    tete.className = "tete";
    tete.innerHTML = `<div class="titre"></div><div class="petite"></div>`;
    scene.appendChild(tete);
    const cadre = document.createElement("div");
    cadre.className = "cadre";
    cadre.appendChild(colonnes);
    scene.appendChild(cadre);
  }

  async _montrer(reste) {
    if (this._dort) return;
    this._sonde();
    const passage = this._passage = (this._passage || 0) + 1;
    let cran = 0;
    if (this._synchro) {
      const n = this._ecrans.length;
      cran = Math.max(0, Math.floor((Date.now() / 1000 - this._t0) / this._pas));
      const p = cran + this._rang;
      this._indice = p % n; this._tour = Math.floor(p / n);
      if (this._ecrans[this._indice].genre === "tableau") this._ecrans[this._indice].page = 0;
    }
    const e = this._ecrans[this._indice];
    const ancienne = this.shadowRoot.querySelector(".scene.vue");
    const scene = document.createElement("div");
    scene.className = "scene";
    if (e.genre === "horloge") {
      // 1.93.05 (l'auteur, retour 35) : l'horloge seule — ni température ni ciel, quel que soit le style.
      scene.innerHTML = this._horloge("");
    } else {
      if (e.page === 0 || !e._colonnes) {
        e.page = 0;
        // Les cartes sont créées une fois par passage, pour des valeurs à jour.
        this._cartes = [];
        // La disposition du tableau de bord est gardée telle quelle : ses sections côte à côte, dans le même
        // ordre et sur le même nombre de colonnes que dans Home Assistant. Seule la taille s'adapte à l'écran.
        const colonnes = document.createElement("div");
        colonnes.className = "grille";
        const blocs = [];
        for (const s of e.sections) {
          if (!s.titre && !(s.cartes || []).length) continue;
          const bloc = document.createElement("div");
          bloc.className = "section";
          bloc._span = Math.max(1, Math.min(4, s.span || 1));
          bloc._cartes = s.cartes || [];
          if (s.titre) { const t = document.createElement("div"); t.className = "sec"; t.textContent = s.titre; bloc.appendChild(t); }
          blocs.push(bloc);
        }
        // Écran en hauteur (téléphone, tablette debout) : les sections passent les unes sous les autres, comme
        // Home Assistant le fait lui-même sur un téléphone ; l'ordre reste celui du tableau de bord.
        const debout = window.innerWidth < window.innerHeight;
        const voulu = debout ? (window.innerWidth >= 700 ? 2 : 1) : Math.max(1, Math.min(6, e.colonnes || 4));
        const n = Math.max(1, Math.min(voulu, blocs.reduce((a, x) => a + x._span, 0)));
        colonnes.style.gridTemplateColumns = `repeat(${n}, 460px)`;
        colonnes.style.width = `${n * 460 + (n - 1) * 44}px`;
        colonnes._n = n; colonnes._nominale = n * 460 + (n - 1) * 44;
        for (const bloc of blocs) { bloc.style.gridColumn = `span ${Math.min(n, bloc._span)}`; colonnes.appendChild(bloc); }
        // Le tableau est posé tout de suite, encore invisible, et ses cartes y arrivent une à une en rendant la
        // main entre deux : fabriquées et dessinées d'un bloc, elles figeaient l'écran deux dixièmes de seconde,
        // et l'horloge ou le tableau en place donnait un à-coup.
        this._garnir(scene, e, colonnes);
        this.shadowRoot.appendChild(scene);
        const limite = Date.now() + 8000;
        let coupe = false;
        for (const bloc of blocs) {
          if (coupe) break;
          for (const c of bloc._cartes) {
            if (Date.now() > limite) { coupe = true; this._note("préparation trop longue : « " + e.titre + " » montré sans ses dernières cartes"); break; }  // i18n-ignorer : diagnostic (?diag=…), lu par le mainteneur seulement, reste en français comme les journaux
            const el = this._carte(c.config, c.typee); if (el) { el._fige = !!c.fige; bloc.appendChild(el); }
            await Promise.race([new Promise((r) => requestAnimationFrame(() => setTimeout(r, 0))), pause(80)]);
            if (passage !== this._passage) { scene.remove(); return; }
          }
          if (!bloc.children.length) bloc.remove();
        }
        e._colonnes = colonnes;
      }
      if (!scene.firstChild) {
        if (ancienne && ancienne.contains(e._colonnes)) {
          // La suite du même tableau reprend les mêmes cartes : l'écran s'efface, puis revient sur la suite.
          ancienne.classList.remove("vue");
          await pause(620);
          if (passage !== this._passage) return;
        }
        this._garnir(scene, e, e._colonnes);
      }
      scene.querySelector(".titre").textContent = e.titre + (e.pages > 1 ? `   ${e.page + 1}/${e.pages}` : "");
    }
    if (!scene.isConnected) this.shadowRoot.appendChild(scene);
    this._tic();
    if (e.genre === "tableau") {
      const c = e._colonnes;
      if (e.page === 0) {
        // Le temps que les cartes se dessinent, puis on compte les écrans nécessaires.
        await pause(900);
        if (passage !== this._passage) { scene.remove(); return; }
        this._percer(c);
        await pause(300);
        if (passage !== this._passage) { scene.remove(); return; }
        const cadre = c.parentElement, L = cadre.clientWidth, H = cadre.clientHeight;
        for (const el of c.querySelectorAll(".section > *")) {
          if (!el._largeurLibre) continue;
          if (this._q.get("diag") === "1") try { this._note(`${el.localName} : ${el.getGridOptions ? JSON.stringify(el.getGridOptions()) : "sans largeur propre"}`); } catch (err) { this._note(`${el.localName} : ${err}`); }  // i18n-ignorer : diagnostic (?diag=…), lu par le mainteneur seulement, reste en français comme les journaux
          try { const g = el.getGridOptions && el.getGridOptions(); if (g && typeof g.columns === "number") el.style.gridColumn = `span ${Math.max(3, Math.min(12, g.columns))}`; } catch (err) { /* reste pleine largeur */ }
        }
        await pause(120);
        this._uneLigne(c);
        // Veille-lot : la grille au pixel, à l'échelle qui remplit la largeur (avant de compter les écrans).
        this._aligner(c, Math.min(L / (c._nominale || c.offsetWidth || 1), 2.2) * 0.985);
        // Les rangées de sections, de haut en bas ; ce qui ne tient pas en hauteur passe sur l'écran suivant.
        const sections = [...c.querySelectorAll(".section")];
        const rangees = [];
        for (const s of sections) {
          const derniere = rangees[rangees.length - 1];
          if (derniere && Math.abs(derniere.haut - s.offsetTop) < 4) { derniere.sections.push(s); derniere.bas = Math.max(derniere.bas, s.offsetTop + s.offsetHeight); }
          else rangees.push({ haut: s.offsetTop, bas: s.offsetTop + s.offsetHeight, sections: [s] });
        }
        const larg = c.offsetWidth || 1, haut = c.offsetHeight || 1;
        const zl = Math.min(L / larg, 2.2);
        e._pages = [];
        if (haut * zl <= H || H / haut >= zl * 0.62) {
          // Tout tient sur un écran, quitte à réduire un peu.
          e._pages.push({ haut: 0, bas: haut, sections });
        } else {
          let courante = null;
          for (const r of rangees) {
            if (courante && (r.bas - courante.haut) * zl <= H) { courante.bas = r.bas; courante.sections.push(...r.sections); }
            else { courante = { haut: r.haut, bas: r.bas, sections: [...r.sections] }; e._pages.push(courante); }
          }
        }
        e._pages = e._pages.slice(0, 8);
        e._larg = larg; e._L = L; e._H = H;
        e.pages = e._pages.length || 1;
        scene.querySelector(".titre").textContent = e.titre + (e.pages > 1 ? `   1/${e.pages}` : "");
      }
      // À plusieurs écrans, un tableau trop grand pour un écran montre sa partie suivante à chaque tour.
      if (this._synchro) { e.page = this._tour % (e.pages || 1); scene.querySelector(".titre").textContent = e.titre + (e.pages > 1 ? `   ${e.page + 1}/${e.pages}` : ""); }
      // L'écran demandé : ses sections seules, à la plus grande taille qui tient, centrées.
      const pg = e._pages[e.page] || e._pages[0];
      if (pg) {
        const hp = Math.max(1, pg.bas - pg.haut);
        const z = Math.min(e._L / e._larg, e._H / hp, 2.2) * 0.985;
        for (const s of c.querySelectorAll(".section")) s.style.visibility = pg.sections.includes(s) ? "visible" : "hidden";
        if (Math.abs(z - (c._zAligne || 0)) > 1e-4) this._aligner(c, z);
        const dx = Math.max(0, (e._L - e._larg * z) / 2), dy = Math.max(0, Math.min((e._H - hp * z) / 2, e._H * 0.1));
        const [tx, ty] = this._auPixel(c, dx, dy - pg.haut * z);
        c.style.transform = `translate(${tx}px, ${ty}px) scale(${z})`;
        c._zVu = z;
      }
    }
    if (e.genre === "tableau") { this._percer(e._colonnes); for (const t of [700, 2000, 4500]) setTimeout(() => this._percer(e._colonnes), t); }
    if (e.genre === "tableau" && this._anim) { try { this._animer(e); } catch (err) { /* le tableau s'affiche sans animation */ } }
    // Le navigateur doit avoir vu la scène à zéro avant qu'on la montre, sinon il n'y a pas de fondu : l'horloge,
    // posée et montrée dans la même image, arrivait d'un coup à la fin du tour, comme une coupure.
    void scene.offsetWidth;
    requestAnimationFrame(() => {
      scene.classList.add("vue");
      if (ancienne) { this._eteindreWeb(ancienne); ancienne.classList.remove("vue"); setTimeout(() => ancienne.remove(), 700); }
      this._allumerWeb(scene);
    });
    clearTimeout(this._minuteur);
    if (this._fige) return;
    if (this._synchro) { this._minuteur = setTimeout(() => this._montrer(), Math.max(2500, ((cran + 1) * this._pas + this._t0) * 1000 - Date.now())); return; }
    this._minuteur = setTimeout(() => this._suivant(), reste || Math.max(5, e.duree) * 1000);
  }

  // Une rangée de pastilles reste sur une ligne : si elle est trop large, elle rétrécit au lieu de passer dessous.
  // Rien à changer dans le tableau de bord : c'est la veille qui s'adapte.
  _uneLigne(racine) {
    for (const el of racine.querySelectorAll("mushroom-chips-card")) {
      try {
        const rangee = el.shadowRoot && el.shadowRoot.querySelector(".chip-container");
        if (!rangee) continue;
        rangee.style.flexWrap = "nowrap"; rangee.style.justifyContent = "flex-start"; rangee.style.whiteSpace = "nowrap";
        const dispo = el.clientWidth, besoin = rangee.scrollWidth;
        if (besoin > dispo + 2) {
          rangee.style.width = `${besoin}px`;
          rangee.style.transformOrigin = "left center";  // i18n-ignorer : valeur CSS
          rangee.style.transform = `scale(${dispo / besoin})`;
        }
      } catch (e) { /* la carte reste telle quelle */ }
    }
  }

  // ------------------------------------------------------------------ Les cases typées (veille-lot)

  // Une case typée qu'on sait dessiner ; celles qui ont des images demandent le ticket de l'appareil (sinon la carte
  // reste dessinée par Home Assistant, comme avant).
  _typeeUtile(x) {
    if (!x || typeof x !== "object" || !GENRES_TYPES.includes(x.genre)) return null;
    if (AVEC_IMAGES.includes(x.genre) && !this._ticket) return null;
    return x;
  }

  // Une image servie par l'intégration : la clé seule, jamais une autre adresse.
  _img(url, role, classe = "vt-fond", style = "") {
    const u = String(url || "");
    if (!RE_MEDIA.test(u)) return "";
    return `<img class="${classe}" alt="" decoding="async" data-media="${u}" data-age="${AGE_MEDIA[role] || AGE_MEDIA.fond}"${style ? ` style="${style}"` : ""}>`;
  }

  _carteTypee(t, config) {
    if (t.genre === "iframe") return this._cadreWeb({ ...config, url: t.url }, t);
    const el = document.createElement("div");
    el.className = `vt vt-${t.genre}`;  // i18n-ignorer : technique (attribut, classe, sélecteur)
    let colonnes = 12;
    const o = (config && (config.grid_options || config.layout_options)) || {};
    const v = o.columns ?? o.grid_columns;
    if (typeof v === "number") colonnes = Math.max(3, Math.min(12, v));
    el.style.gridColumn = `span ${colonnes}`;
    const tete = t.nom ? `<div class="vt-nom">${echapper(t.nom)}</div>` : "";
    const attente = `<div class="vt-attente"><i></i><i></i><i></i></div>`;
    const maintenant = new Date();
    if (t.genre === "meteo") {
      const minuit = new Date(maintenant.getFullYear(), maintenant.getMonth(), maintenant.getDate());
      const prev = (Array.isArray(t.previsions) ? t.previsions : []).filter((p) => { const d = lireDate(p && p.quand); return !d || d >= minuit; }).slice(0, 5);
      el.dataset.entite = String(t.entite || "");
      el._etat = t.etat; el._temp = t.temperature;
      el.innerHTML = tete + `<div class="vt-maintenant">${iconeCiel(t.etat)}<span class="vt-temp">${degres(t.temperature)}</span><span class="vt-ciel">${echapper(t.etat_texte || cielDe(t.etat))}</span></div>` +
        (prev.length ? `<div class="vt-prev" style="grid-template-columns: repeat(${prev.length}, minmax(0, 1fr))">${prev.map((p) =>
          `<div><div class="vt-jour">${echapper(jourCourt(p.quand))}</div>${iconeCiel(p.etat)}<div class="vt-mm">${degres(p.max)}<span>${degres(p.min)}</span></div></div>`).join("")}</div>` : "");
    } else if (t.genre === "agenda") {
      const evs = (Array.isArray(t.evenements) ? t.evenements : []).filter((ev) => ev && pasFini(ev, maintenant)).slice(0, 6);
      el.innerHTML = tete + (evs.length
        ? evs.map((ev) => `<div class="vt-ev"><span class="vt-quand">${echapper(quandEvenement(ev, maintenant))}</span><div class="vt-titre">${echapper(ev.titre)}</div></div>`).join("")
        : `<div class="vt-vide">${MOTS_VT.vide}</div>`);
    } else if (t.genre === "image" || t.genre === "radar") {
      // Le fond entier (contenir) ; textes et calques posés à leurs pourcentages du fond, pas de la case.
      let dedans = this._img(t.url, "fond");
      if (t.genre === "radar") dedans += t.calque ? this._img(t.calque.url, "calque") : "";
      else {
        for (const k of (Array.isArray(t.calques) ? t.calques : []).slice(0, 4)) {
          const x = Number(k.x), y = Number(k.y), l = Number(k.largeur) || 100;
          if (isFinite(x) && isFinite(y)) dedans += this._img(k.url, "calque", "vt-calque", `left:${x}%;top:${y}%;width:${Math.max(1, Math.min(100, l))}%`);
        }
        for (const e of (Array.isArray(t.elements) ? t.elements : []).slice(0, 12)) {
          const x = Number(e.x), y = Number(e.y);
          if (isFinite(x) && isFinite(y) && e.texte) dedans += `<div class="vt-etiquette" style="left:${x}%;top:${y}%">${echapper(e.texte)}</div>`;
        }
      }
      el.innerHTML = tete + `<div class="vt-media" style="padding-top:56.25%"><div class="vt-cadre">${dedans}</div>${attente}</div>`;
    } else if (t.genre === "camera") {
      el.innerHTML = tete + `<div class="vt-media" style="padding-top:56.25%">${this._img(t.url, "camera")}${attente}</div><div class="vt-point"></div>`;
    } else if (t.genre === "clips") {
      const liste = (Array.isArray(t.liste) ? t.liste : []).slice(0, 4);
      el.innerHTML = tete + (liste.length ? `<div class="vt-vignettes">${liste.map((k) =>
        `<div><div class="vt-media" style="padding-top:56.25%">${this._img(k.vignette, "vignette")}${attente}</div>` +
        `<div class="vt-legende"><b>${echapper(k.camera)}</b>  ·  ${echapper(k.objet_nom || k.objet)}  ·  ${echapper(ecartLisible(k.quand, maintenant))}</div></div>`).join("")}</div>`
        : `<div class="vt-vide">${MOTS_VT.passages}</div>`);
    }
    el._ne = Date.now();
    for (const img of el.querySelectorAll("img[data-media]")) this._poserMedia(img);
    return el;
  }

  // Une image déjà reçue se pose tout de suite (sans attendre) ; le fond d'une image règle aussi le cadre des
  // éléments posés dessus, et la hauteur de la case tant qu'elle n'est pas encore mesurée.
  _poserMedia(img) {
    const m = this._medias && this._medias.get(img.dataset.media);
    if (!m) return;
    if (img.getAttribute("src") !== m.u) img.src = m.u;
    img.classList.add("vu");
    const boite = img.closest(".vt-media");
    if (boite) boite.classList.add("pret");
    const cadre = img.parentElement;
    if (!cadre || !cadre.classList.contains("vt-cadre") || !img.classList.contains("vt-fond") || img !== cadre.firstElementChild || !m.l || !m.h) return;
    const carte = img.closest(".vt");
    const r = Math.max(25, Math.min(100, (m.h / m.l) * 100));
    if (carte && Date.now() - (carte._ne || 0) < 1000 && boite) boite.style.paddingTop = `${r.toFixed(3)}%`;
    const R = parseFloat(boite && boite.style.paddingTop) || 56.25;
    if (r <= R) { const hh = (r / R) * 100; cadre.style.cssText = `left:0;width:100%;top:${((100 - hh) / 2).toFixed(3)}%;height:${hh.toFixed(3)}%`; }
    else { const ll = (R / r) * 100; cadre.style.cssText = `top:0;height:100%;left:${((100 - ll) / 2).toFixed(3)}%;width:${ll.toFixed(3)}%`; }
  }

  // Une image demandée à l'intégration (POST, ticket de l'appareil), décodée avant d'être montrée.
  async _chargerMedia(url) {
    const m = this._medias.get(url) || {};
    this._medias.set(url, m);
    m.enCours = true; m.essai = Date.now();
    this._mediasEnCours = (this._mediasEnCours || 0) + 1;
    try {
      const r = await fetch(url, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ ticket: this._ticket }), credentials: "omit", cache: "no-store", referrerPolicy: "no-referrer" });
      if (!r.ok) return;
      const u = URL.createObjectURL(await r.blob());
      const essai = new Image();
      essai.src = u;
      try { await essai.decode(); } catch (e) { URL.revokeObjectURL(u); return; }
      const avant = m.u;
      Object.assign(m, { u, l: essai.naturalWidth, h: essai.naturalHeight, recu: Date.now() });
      // Les plus anciennes images vues partent (un petit cache, comme sur la télé).
      this._medias.delete(url); this._medias.set(url, m);
      while (this._medias.size > 24) { const [k, x] = this._medias.entries().next().value; this._medias.delete(k); if (x.u) setTimeout(() => URL.revokeObjectURL(x.u), 2000); }
      for (const img of this.shadowRoot.querySelectorAll(`img[data-media="${url}"]`)) this._poserMedia(img);
      if (avant) setTimeout(() => URL.revokeObjectURL(avant), 2000);
    } catch (e) { /* l'image reviendra au prochain essai */ } finally {
      m.enCours = false;
      this._mediasEnCours -= 1;
    }
  }

  // Chaque seconde au plus : les images à l'écran (et celles de l'écran qui se prépare) qui manquent ou ont passé
  // leur âge sont redemandées, deux à la fois, et pas plus d'une fois toutes les 10 s si elles ne viennent pas.
  _rafraichirMedias() {
    if (!this._ticket || this._dort) return;
    const maintenant = Date.now();
    const vues = new Set();
    for (const img of this.shadowRoot.querySelectorAll(".scene img[data-media]")) {  // i18n-ignorer : technique (attribut, classe, sélecteur)
      const url = img.dataset.media;
      if (vues.has(url)) continue;
      vues.add(url);
      const s = img.closest(".section");
      if (s && s.style.visibility === "hidden") continue;
      const m = this._medias.get(url);
      if (m && (m.enCours || maintenant - (m.recu || 0) < Number(img.dataset.age) || maintenant - (m.essai || 0) < 10000)) continue;
      if ((this._mediasEnCours || 0) >= 2) break;
      this._chargerMedia(url);
    }
  }

  // La météo du moment suit l'entité (reçue en direct) ; les prévisions restent celles du relevé.
  _majMeteo() {
    const vue = this.shadowRoot.querySelector(".scene.vue");
    if (!vue || !this._hass) return;
    for (const el of vue.querySelectorAll(".vt-meteo[data-entite]")) {
      const st = this._hass.states && this._hass.states[el.dataset.entite];
      if (!st || ["unavailable", "unknown"].includes(st.state)) continue;
      const temp = st.attributes && typeof st.attributes.temperature === "number" ? st.attributes.temperature : el._temp;
      if (st.state === el._etat && temp === el._temp) continue;
      if (st.state !== el._etat) {
        el._etat = st.state;
        const i = el.querySelector(".vt-maintenant .vt-icone"); if (i) i.outerHTML = iconeCiel(st.state);
        const c = el.querySelector(".vt-ciel"); if (c) c.textContent = cielDe(st.state) || majuscule(st.state.replace(/-/g, " "));
      }
      el._temp = temp;
      poser(el, ".vt-temp", degres(temp));
    }
  }

  // ------------------------------------------------------------------ Grille au pixel (veille-lot)
  //
  // Le tableau est mis en page à sa taille d'origine (colonnes de 460) puis réduit ou agrandi d'un bloc (« z »).
  // Pour que colonnes et cartes tombent sur des pixels entiers de l'écran, à l'échelle z : colonnes de même largeur
  // (pixels entiers), 12 pistes par section réparties au pixel (le reste aux premières), écarts tous égaux, et la
  // hauteur de chaque carte arrondie au pixel supérieur. Rien ne bouge d'une image à l'autre : une fois par écran.
  _aligner(c, z) {
    const dpr = window.devicePixelRatio || 1, k = z * dpr;
    if (!(k > 0) || !c._n) return;
    const n = c._n;
    const ecart = Math.max(1, Math.round(44 * k)), rangee = Math.max(1, Math.round(34 * k)), interne = Math.max(1, Math.round(18 * k));
    const col = Math.max(1, Math.floor((Math.round(c._nominale * k) - ecart * (n - 1)) / n));
    const cols = pistesAuPixel(Array(n).fill(col), ecart, k);
    c.style.gridTemplateColumns = cols.largeurs; c.style.columnGap = `${cols.ecart}px`; c.style.rowGap = `${q64(rangee / k)}px`;
    c.style.width = `${cols.total}px`;
    const marge = q64(Math.round(2 * k) / k);
    const cartes = [];
    for (const s of c.querySelectorAll(".section")) {
      const span = Math.max(1, Math.min(n, s._span || 1));
      const pistes = pistesAuPixel(repartir(col * span + ecart * (span - 1), 12, interne), interne, k);
      s.style.gridTemplateColumns = pistes.largeurs; s.style.gap = `${pistes.ecart}px`;
      for (const x of s.children) {
        // La hauteur minimale compte bordure et marges intérieures comprises (une page web garde sa proportion).
        x.style.minHeight = ""; x.style.boxSizing = "border-box";
        if (x.classList.contains("sec")) x.style.margin = `${marge}px ${marge}px 0`;
        cartes.push(x);
      }
    }
    // Une lecture pour toutes les cartes, puis une écriture pour toutes : une seule mise en page de plus.
    const hauteurs = cartes.map((x) => x.getBoundingClientRect().height);
    const zv = c._zVu || 1;
    cartes.forEach((x, i) => { const dev = (hauteurs[i] / zv) * k; if (dev > 0) x.style.minHeight = `${Math.ceil((Math.ceil(dev - 0.02) / k) * 64) / 64}px`; });
    c._zAligne = z;
  }

  // Le décalage final (en px de la page) arrondi pour que le coin du tableau tombe sur un pixel entier.
  _auPixel(c, dx, dy) {
    const dpr = window.devicePixelRatio || 1, r = c.parentElement.getBoundingClientRect();
    return [Math.round((r.left + dx) * dpr) / dpr - r.left, Math.round((r.top + dy) * dpr) / dpr - r.top];
  }

  // ------------------------------------------------------------------ Dérive anti-marquage (veille-lot)
  //
  // Tout le contenu se déplace de quelques pixels entiers, très lentement (moins d'un pixel toutes les vingt secondes),
  // comme le dessin de l'appli (DeriveVeille) : imperceptible, et le texte reste net. Une télé récente (qui envoie
  // « pastille ») la fait déjà sur toute la page : la page ne la double pas.
  _deriver() {
    if (!this._deriveLocale) return;
    const dpr = window.devicePixelRatio || 1, amp = Math.max(2, Math.min(8, Math.round(3 * dpr))), t = Date.now() / 1000;
    const dx = Math.round(amp * Math.sin((2 * Math.PI * t) / 1427)), dy = Math.round(amp * Math.cos((2 * Math.PI * t) / 1913));
    if (dx === this._dx && dy === this._dy) return;
    this._dx = dx; this._dy = dy;
    this.style.transform = `translate(${dx / dpr}px, ${dy / dpr}px)`;
  }

  _suivant(force) {
    if (this._dort) return;
    const e = this._ecrans[this._indice];
    if (e.genre === "tableau" && e.page + 1 < e.pages) e.page += 1;
    else { if (e.genre === "tableau") e.page = 0; this._indice = (this._indice + 1) % this._ecrans.length; }
    if (this._ecrans.length === 1 && e.genre === "horloge" && !force) { this._minuteur = setTimeout(() => this._suivant(), 60000); return; }
    this._montrer();
  }
}

if (!customElements.get("vision-veille-coeur")) customElements.define("vision-veille-coeur", VisionVeillePanel);
