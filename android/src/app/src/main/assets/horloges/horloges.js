// Copie des horloges des thèmes de la page de veille de Home Assistant (pc_parental/frontend/vision-veille-coeur.js, HA 2.5.2, lignes 1 à 727 : textes, outils, HORLOGES, CSS_HORLOGES).
// Ne pas modifier ici : recopier depuis la page (outils/copier-horloges-web.sh).
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
    "reprise": "Reprise"
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
    "reprise": "Resumed"
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
    "reprise": "Weiter"
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
    "reprise": "Reanudado"
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
    "reprise": "Ripreso"
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
    "reprise": "Hervat"
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

