/*
 * Vision — carte « Réglages réservés » (refonte UX, lot 5 : architecture cible § 2), réservée aux administrateurs.
 *
 * Réglages › « Réservé aux Décideurs 🔒 » : Lieu et vocabulaire, Fonctions (les modules rangés par objectif, un
 * interrupteur chacun, leurs dépendances dites avant de basculer), Rôles et droits (une case par droit). Chaque
 * changement part tout de suite (API reglages/admin) ; rien ne se ferme à chaque formulaire.
 * 2.5.12.10 : « Niveau sonore » (module allumé) : par appareil, mesure, réaction, seuil général ou par plage, plancher.
 */
// ---- i18n:debut — les textes de cette carte, dans les six langues de Vision (fr source, en repli) ----
const TEXTES = /*i18n-json*/{
  "fr": {
    "carte_nom": "Vision — Réglages réservés",
    "carte_description": "Lieu et vocabulaire, fonctions par objectif, rôles et droits (administrateurs).",
    "chargement": "Chargement…",
    "intro": "Ce qui vaut pour tout le {lieu}. Chaque changement est enregistré tout de suite et noté dans le journal.",
    "partie_lieu": "Lieu et vocabulaire",
    "partie_fonctions": "Fonctions",
    "partie_droits": "Rôles et droits",
    "lieu_aide": "Le nom s'affiche dans les applis, le tableau de bord et les notifications ({max} caractères au plus). Vide : le mot par défaut.",
    "lieu_nom": "Nom du lieu",
    "mots_aide": "Les mots des deux rôles. Vide : le mot par défaut, montré en gris ({max} caractères au plus).",
    "mot_decideur": "Qui décide (un)",
    "mot_decideurs": "Qui décide (plusieurs)",
    "mot_demandeur": "Qui demande (un)",
    "mot_demandeurs": "Qui demande (plusieurs)",
    "enregistrer": "Enregistrer",
    "fonctions_aide": "Une fonction coupée disparaît partout ; ses réglages sont gardés pour son retour. Ce dont chacune a besoin est dit avant de basculer.",
    "groupe_horaires": "Horaires et temps d'écran",
    "groupe_contenus": "Contenus",
    "groupe_demandes": "Demandes et réponses à distance",
    "groupe_ecrans": "Écrans partagés",
    "groupe_autres": "Autres fonctions",
    "requiert": "A besoin de : {liste}",
    "requiert_un": "A besoin d'au moins une de : {liste}",
    "droits_aide": "Ce que chaque rôle permet. ★ : un droit sensible. Il reste toujours au moins un rôle qui peut gérer et approuver.",
    "sensible": "Droit sensible",
    "couper_quand_meme": "Couper quand même",
    "geste_fait": "C'est fait.",
    "erreur_admin": "Réservé aux administrateurs de Home Assistant.",
    "erreur_loading": "Vision démarre : réessaie dans un instant.",
    "erreur_defaut": "Ça n'a pas marché. Réessaie.",
    "partie_son": "Niveau sonore",
    "son_aide": "Le niveau maximum autorisé, par appareil : un seuil général, ou un seuil par plage (la plus stricte des plages en cours s'applique, sinon le général). En dBFS : 0 = pleine échelle du micro ; plus c'est bas, plus c'est strict.",
    "son_mesurer": "Mesurer",
    "son_reagir": "Réagir (messages, coupure)",
    "son_mode": "Mode du seuil",
    "son_mode_general": "Général",
    "son_mode_plages": "Par plage",
    "son_general": "Seuil général (dBFS)",
    "son_plage_vide": "vide = seuil général",
    "son_aucune_plage": "Cet appareil n'a aucune plage : le seuil général s'applique.",
    "son_plancher": "Plancher de la pièce (dBFS)",
    "son_plancher_aide": "Mesuré sur 24 h : {mesure}. Vide : le plancher mesuré.",
    "son_plancher_inconnu": "pas encore mesuré",
    "son_marge": "soit plancher + {n} dB",
    "son_moment": "En ce moment : {seuil} dBFS ({source}) ; niveau {niveau}.",
    "son_source_general": "seuil général",
    "son_niveau_inconnu": "inconnu",
    "son_eteint": "Mesure éteinte."
  },
  "en": {
    "carte_nom": "Vision — Restricted settings",
    "carte_description": "Place and words, features by goal, roles and rights (administrators).",
    "chargement": "Loading…",
    "intro": "What applies to the whole {lieu}. Each change is saved at once and written to the log.",
    "partie_lieu": "Place and words",
    "partie_fonctions": "Features",
    "partie_droits": "Roles and rights",
    "lieu_aide": "The name shows in the apps, the dashboard and notifications ({max} characters at most). Empty: the default word.",
    "lieu_nom": "Name of the place",
    "mots_aide": "The words for the two roles. Empty: the default word, shown in grey ({max} characters at most).",
    "mot_decideur": "Who decides (one)",
    "mot_decideurs": "Who decides (several)",
    "mot_demandeur": "Who asks (one)",
    "mot_demandeurs": "Who asks (several)",
    "enregistrer": "Save",
    "fonctions_aide": "A feature turned off disappears everywhere; its settings are kept for when it comes back. What each one needs is shown before you switch.",
    "groupe_horaires": "Schedules and screen time",
    "groupe_contenus": "Content",
    "groupe_demandes": "Requests and remote answers",
    "groupe_ecrans": "Shared screens",
    "groupe_autres": "Other features",
    "requiert": "Needs: {liste}",
    "requiert_un": "Needs at least one of: {liste}",
    "droits_aide": "What each role allows. ★: a sensitive right. At least one role can always manage and approve.",
    "sensible": "Sensitive right",
    "couper_quand_meme": "Turn off anyway",
    "geste_fait": "Done.",
    "erreur_admin": "For Home Assistant administrators only.",
    "erreur_loading": "Vision is starting: try again in a moment.",
    "erreur_defaut": "That didn't work. Try again.",
    "partie_son": "Sound level",
    "son_aide": "The maximum allowed level, per device: one general threshold, or one per time slot (the strictest running slot applies, otherwise the general one). In dBFS: 0 = microphone full scale; lower is stricter.",
    "son_mesurer": "Measure",
    "son_reagir": "React (messages, cut-off)",
    "son_mode": "Threshold mode",
    "son_mode_general": "General",
    "son_mode_plages": "Per time slot",
    "son_general": "General threshold (dBFS)",
    "son_plage_vide": "empty = general threshold",
    "son_aucune_plage": "This device has no time slot: the general threshold applies.",
    "son_plancher": "Room floor (dBFS)",
    "son_plancher_aide": "Measured over 24 h: {mesure}. Empty: the measured floor.",
    "son_plancher_inconnu": "not measured yet",
    "son_marge": "that is floor + {n} dB",
    "son_moment": "Right now: {seuil} dBFS ({source}); level {niveau}.",
    "son_source_general": "general threshold",
    "son_niveau_inconnu": "unknown",
    "son_eteint": "Measuring is off."
  },
  "de": {
    "carte_nom": "Vision — Geschützte Einstellungen",
    "carte_description": "Ort und Wörter, Funktionen nach Ziel, Rollen und Rechte (Administratoren).",
    "chargement": "Wird geladen…",
    "intro": "Was für den ganzen {lieu} gilt. Jede Änderung wird sofort gespeichert und protokolliert.",
    "partie_lieu": "Ort und Wörter",
    "partie_fonctions": "Funktionen",
    "partie_droits": "Rollen und Rechte",
    "lieu_aide": "Der Name erscheint in den Apps, im Dashboard und in Benachrichtigungen (höchstens {max} Zeichen). Leer: das Standardwort.",
    "lieu_nom": "Name des Ortes",
    "mots_aide": "Die Wörter für die beiden Rollen. Leer: das Standardwort, grau angezeigt (höchstens {max} Zeichen).",
    "mot_decideur": "Wer entscheidet (eine Person)",
    "mot_decideurs": "Wer entscheidet (mehrere)",
    "mot_demandeur": "Wer anfragt (eine Person)",
    "mot_demandeurs": "Wer anfragt (mehrere)",
    "enregistrer": "Speichern",
    "fonctions_aide": "Eine ausgeschaltete Funktion verschwindet überall; ihre Einstellungen bleiben für später erhalten. Was jede braucht, steht vor dem Umschalten da.",
    "groupe_horaires": "Zeiten und Bildschirmzeit",
    "groupe_contenus": "Inhalte",
    "groupe_demandes": "Anfragen und Antworten aus der Ferne",
    "groupe_ecrans": "Gemeinsame Bildschirme",
    "groupe_autres": "Weitere Funktionen",
    "requiert": "Braucht: {liste}",
    "requiert_un": "Braucht mindestens eine von: {liste}",
    "droits_aide": "Was jede Rolle erlaubt. ★: ein sensibles Recht. Mindestens eine Rolle kann immer verwalten und genehmigen.",
    "sensible": "Sensibles Recht",
    "couper_quand_meme": "Trotzdem ausschalten",
    "geste_fait": "Erledigt.",
    "erreur_admin": "Nur für Home-Assistant-Administratoren.",
    "erreur_loading": "Vision startet: gleich noch einmal versuchen.",
    "erreur_defaut": "Das hat nicht geklappt. Bitte erneut versuchen.",
    "partie_son": "Lautstärke",
    "son_aide": "Der höchste erlaubte Pegel je Gerät: eine allgemeine Schwelle oder eine je Zeitplan (der strengste laufende gilt, sonst die allgemeine). In dBFS: 0 = Vollaussteuerung des Mikrofons; je niedriger, desto strenger.",
    "son_mesurer": "Messen",
    "son_reagir": "Reagieren (Meldungen, Sperre)",
    "son_mode": "Schwellenmodus",
    "son_mode_general": "Allgemein",
    "son_mode_plages": "Je Zeitplan",
    "son_general": "Allgemeine Schwelle (dBFS)",
    "son_plage_vide": "leer = allgemeine Schwelle",
    "son_aucune_plage": "Dieses Gerät hat keinen Zeitplan: die allgemeine Schwelle gilt.",
    "son_plancher": "Grundpegel des Raums (dBFS)",
    "son_plancher_aide": "Über 24 h gemessen: {mesure}. Leer: der gemessene Grundpegel.",
    "son_plancher_inconnu": "noch nicht gemessen",
    "son_marge": "also Grundpegel + {n} dB",
    "son_moment": "Gerade: {seuil} dBFS ({source}); Pegel {niveau}.",
    "son_source_general": "allgemeine Schwelle",
    "son_niveau_inconnu": "unbekannt",
    "son_eteint": "Messung aus."
  },
  "es": {
    "carte_nom": "Vision — Ajustes reservados",
    "carte_description": "Lugar y palabras, funciones por objetivo, roles y derechos (administradores).",
    "chargement": "Cargando…",
    "intro": "Lo que vale para todo el {lieu}. Cada cambio se guarda al momento y queda en el registro.",
    "partie_lieu": "Lugar y palabras",
    "partie_fonctions": "Funciones",
    "partie_droits": "Roles y derechos",
    "lieu_aide": "El nombre aparece en las apps, el panel y las notificaciones ({max} caracteres como máximo). Vacío: la palabra por defecto.",
    "lieu_nom": "Nombre del lugar",
    "mots_aide": "Las palabras de los dos roles. Vacío: la palabra por defecto, en gris ({max} caracteres como máximo).",
    "mot_decideur": "Quién decide (uno)",
    "mot_decideurs": "Quién decide (varios)",
    "mot_demandeur": "Quién pide (uno)",
    "mot_demandeurs": "Quién pide (varios)",
    "enregistrer": "Guardar",
    "fonctions_aide": "Una función desactivada desaparece en todas partes; sus ajustes se conservan para cuando vuelva. Lo que necesita cada una se dice antes de cambiarla.",
    "groupe_horaires": "Horarios y tiempo de pantalla",
    "groupe_contenus": "Contenidos",
    "groupe_demandes": "Solicitudes y respuestas a distancia",
    "groupe_ecrans": "Pantallas compartidas",
    "groupe_autres": "Otras funciones",
    "requiert": "Necesita: {liste}",
    "requiert_un": "Necesita al menos una de: {liste}",
    "droits_aide": "Lo que permite cada rol. ★: un derecho sensible. Siempre queda al menos un rol que puede gestionar y aprobar.",
    "sensible": "Derecho sensible",
    "couper_quand_meme": "Desactivar de todos modos",
    "geste_fait": "Hecho.",
    "erreur_admin": "Solo para administradores de Home Assistant.",
    "erreur_loading": "Vision se está iniciando: inténtalo en un momento.",
    "erreur_defaut": "No ha funcionado. Inténtalo de nuevo.",
    "partie_son": "Nivel de ruido",
    "son_aide": "El nivel máximo permitido por dispositivo: un umbral general o uno por franja (se aplica la franja en curso más estricta, si no el general). En dBFS: 0 = escala completa del micrófono; cuanto más bajo, más estricto.",
    "son_mesurer": "Medir",
    "son_reagir": "Reaccionar (mensajes, corte)",
    "son_mode": "Modo del umbral",
    "son_mode_general": "General",
    "son_mode_plages": "Por franja",
    "son_general": "Umbral general (dBFS)",
    "son_plage_vide": "vacío = umbral general",
    "son_aucune_plage": "Este dispositivo no tiene franjas: se aplica el umbral general.",
    "son_plancher": "Ruido de fondo de la sala (dBFS)",
    "son_plancher_aide": "Medido en 24 h: {mesure}. Vacío: el fondo medido.",
    "son_plancher_inconnu": "aún sin medir",
    "son_marge": "es decir fondo + {n} dB",
    "son_moment": "Ahora: {seuil} dBFS ({source}); nivel {niveau}.",
    "son_source_general": "umbral general",
    "son_niveau_inconnu": "desconocido",
    "son_eteint": "Medición apagada."
  },
  "it": {
    "carte_nom": "Vision — Impostazioni riservate",
    "carte_description": "Luogo e parole, funzioni per obiettivo, ruoli e diritti (amministratori).",
    "chargement": "Caricamento…",
    "intro": "Ciò che vale per tutto il {lieu}. Ogni modifica si salva subito e viene annotata nel registro.",
    "partie_lieu": "Luogo e parole",
    "partie_fonctions": "Funzioni",
    "partie_droits": "Ruoli e diritti",
    "lieu_aide": "Il nome compare nelle app, nella dashboard e nelle notifiche (al massimo {max} caratteri). Vuoto: la parola predefinita.",
    "lieu_nom": "Nome del luogo",
    "mots_aide": "Le parole dei due ruoli. Vuoto: la parola predefinita, in grigio (al massimo {max} caratteri).",
    "mot_decideur": "Chi decide (uno)",
    "mot_decideurs": "Chi decide (più persone)",
    "mot_demandeur": "Chi chiede (uno)",
    "mot_demandeurs": "Chi chiede (più persone)",
    "enregistrer": "Salva",
    "fonctions_aide": "Una funzione spenta sparisce ovunque; le sue impostazioni restano per quando torna. Ciò di cui ha bisogno ciascuna è detto prima di cambiarla.",
    "groupe_horaires": "Orari e tempo davanti allo schermo",
    "groupe_contenus": "Contenuti",
    "groupe_demandes": "Richieste e risposte a distanza",
    "groupe_ecrans": "Schermi condivisi",
    "groupe_autres": "Altre funzioni",
    "requiert": "Ha bisogno di: {liste}",
    "requiert_un": "Ha bisogno di almeno una tra: {liste}",
    "droits_aide": "Ciò che ogni ruolo permette. ★: un diritto sensibile. Resta sempre almeno un ruolo che può gestire e approvare.",
    "sensible": "Diritto sensibile",
    "couper_quand_meme": "Spegni comunque",
    "geste_fait": "Fatto.",
    "erreur_admin": "Solo per gli amministratori di Home Assistant.",
    "erreur_loading": "Vision si sta avviando: riprova tra un attimo.",
    "erreur_defaut": "Non ha funzionato. Riprova.",
    "partie_son": "Livello sonoro",
    "son_aide": "Il livello massimo consentito per dispositivo: una soglia generale o una per fascia (vale la fascia in corso più severa, altrimenti la generale). In dBFS: 0 = fondo scala del microfono; più basso è più severo.",
    "son_mesurer": "Misurare",
    "son_reagir": "Reagire (messaggi, blocco)",
    "son_mode": "Modalità della soglia",
    "son_mode_general": "Generale",
    "son_mode_plages": "Per fascia",
    "son_general": "Soglia generale (dBFS)",
    "son_plage_vide": "vuoto = soglia generale",
    "son_aucune_plage": "Questo dispositivo non ha fasce: vale la soglia generale.",
    "son_plancher": "Rumore di fondo della stanza (dBFS)",
    "son_plancher_aide": "Misurato su 24 h: {mesure}. Vuoto: il fondo misurato.",
    "son_plancher_inconnu": "non ancora misurato",
    "son_marge": "cioè fondo + {n} dB",
    "son_moment": "Ora: {seuil} dBFS ({source}); livello {niveau}.",
    "son_source_general": "soglia generale",
    "son_niveau_inconnu": "sconosciuto",
    "son_eteint": "Misura spenta."
  },
  "nl": {
    "carte_nom": "Vision — Afgeschermde instellingen",
    "carte_description": "Plek en woorden, functies per doel, rollen en rechten (beheerders).",
    "chargement": "Laden…",
    "intro": "Wat voor de hele {lieu} geldt. Elke wijziging wordt meteen opgeslagen en in het logboek genoteerd.",
    "partie_lieu": "Plek en woorden",
    "partie_fonctions": "Functies",
    "partie_droits": "Rollen en rechten",
    "lieu_aide": "De naam verschijnt in de apps, het dashboard en de meldingen (hoogstens {max} tekens). Leeg: het standaardwoord.",
    "lieu_nom": "Naam van de plek",
    "mots_aide": "De woorden voor de twee rollen. Leeg: het standaardwoord, grijs getoond (hoogstens {max} tekens).",
    "mot_decideur": "Wie beslist (één)",
    "mot_decideurs": "Wie beslist (meerdere)",
    "mot_demandeur": "Wie vraagt (één)",
    "mot_demandeurs": "Wie vraagt (meerdere)",
    "enregistrer": "Opslaan",
    "fonctions_aide": "Een uitgeschakelde functie verdwijnt overal; haar instellingen blijven bewaard voor als ze terugkomt. Wat elke functie nodig heeft, staat erbij vóór je omschakelt.",
    "groupe_horaires": "Tijden en schermtijd",
    "groupe_contenus": "Inhoud",
    "groupe_demandes": "Verzoeken en antwoorden op afstand",
    "groupe_ecrans": "Gedeelde schermen",
    "groupe_autres": "Andere functies",
    "requiert": "Heeft nodig: {liste}",
    "requiert_un": "Heeft minstens één nodig van: {liste}",
    "droits_aide": "Wat elke rol toestaat. ★: een gevoelig recht. Er blijft altijd minstens één rol die kan beheren en goedkeuren.",
    "sensible": "Gevoelig recht",
    "couper_quand_meme": "Toch uitschakelen",
    "geste_fait": "Klaar.",
    "erreur_admin": "Alleen voor beheerders van Home Assistant.",
    "erreur_loading": "Vision start op: probeer het zo opnieuw.",
    "erreur_defaut": "Dat is niet gelukt. Probeer het opnieuw.",
    "partie_son": "Geluidsniveau",
    "son_aide": "Het hoogst toegestane niveau per apparaat: één algemene drempel, of één per tijdvak (het strengste lopende tijdvak geldt, anders de algemene). In dBFS: 0 = volle schaal van de microfoon; lager is strenger.",
    "son_mesurer": "Meten",
    "son_reagir": "Reageren (berichten, blokkering)",
    "son_mode": "Drempelmodus",
    "son_mode_general": "Algemeen",
    "son_mode_plages": "Per tijdvak",
    "son_general": "Algemene drempel (dBFS)",
    "son_plage_vide": "leeg = algemene drempel",
    "son_aucune_plage": "Dit apparaat heeft geen tijdvak: de algemene drempel geldt.",
    "son_plancher": "Achtergrondniveau van de kamer (dBFS)",
    "son_plancher_aide": "Over 24 u gemeten: {mesure}. Leeg: het gemeten niveau.",
    "son_plancher_inconnu": "nog niet gemeten",
    "son_marge": "dus bodem + {n} dB",
    "son_moment": "Nu: {seuil} dBFS ({source}); niveau {niveau}.",
    "son_source_general": "algemene drempel",
    "son_niveau_inconnu": "onbekend",
    "son_eteint": "Meten staat uit."
  }
}/*i18n-json:fin*/;
// Les catégories : cette carte n'en affiche pas.
const CATEGORIES_I18N = /*i18n-categories*/{}/*i18n-categories:fin*/;
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
const API = "pc_parental/reglages/admin";
const esc = (t) =>
  String(t ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const ERREURS_GESTE = { admin: "erreur_admin", loading: "erreur_loading" };
const messageGeste = (e) => {
  const b = e && e.body;
  if (b && b.retour) return String(b.retour);
  if (b && ERREURS_GESTE[b.error]) return tr(ERREURS_GESTE[b.error]);
  if (e && e.status_code === 403) return tr(ERREURS_GESTE.admin);
  return tr("erreur_defaut");
};
const MOTS = ["decideur", "decideurs", "demandeur", "demandeurs"];
const PARTIES = ["lieu", "fonctions", "droits", "son"];

class VisionReglagesCard extends HTMLElement {
  constructor() {
    super();
    this.attachShadow({ mode: "open" });
    this._d = null;
    this._erreur = "";
    this._fait = "";
    this._occupe = false;
    this._ouvertes = new Set(["lieu"]);
    this._confirmer = "";   // le module dont la coupure attend une confirmation (protections)
  }

  setConfig(config) {
    this._config = Object.assign({}, config || {});
    // « ouvertes » : les parties dépliées au départ (par défaut Lieu et vocabulaire).
    if (Array.isArray(this._config.ouvertes)) this._ouvertes = new Set(this._config.ouvertes.filter((p) => PARTIES.includes(p)));
  }

  set hass(hass) {
    const premier = !this._hass;
    this._hass = hass;
    const langue = LANGUE;
    suivreLangue(hass);
    if (premier) this._charger();
    else if (langue !== LANGUE) this._rendre();
  }

  getCardSize() { return 10; }

  async _charger() {
    try {
      this._d = await this._hass.callApi("GET", API);
      this._erreur = "";
      if (this._d && this._d.lieu) poserVocabulaire({ lieu: this._d.lieu.nom, mots: { [LANGUE]: this._d.mots.actuels } });
    } catch (e) {
      this._erreur = messageGeste(e);
    }
    this._rendre();
  }

  async _envoyer(corps) {
    if (this._occupe) return;
    this._occupe = true; this._erreur = ""; this._fait = ""; this._rendre();
    try {
      const r = await this._hass.callApi("POST", API, corps);
      this._d = r; this._fait = r.fait || tr("geste_fait"); this._confirmer = "";
      poserVocabulaire({ lieu: r.lieu.nom, mots: { [LANGUE]: r.mots.actuels } });
    } catch (e) {
      this._erreur = messageGeste(e);
      if (corps.op === "module" && corps.module === "protections" && !corps.actif && !corps.confirmer) this._confirmer = "protections";
    } finally {
      this._occupe = false; this._rendre();
      clearTimeout(this._minute);
      this._minute = setTimeout(() => { this._fait = ""; this._rendre(); }, 6000);
    }
  }

  // --- rendu ----------------------------------------------------------------

  _rendre() {
    const d = this._d;
    if (!d) {
      this.shadowRoot.innerHTML = `${this._styles()}<ha-card><div class="vide">${esc(this._erreur || tr("chargement"))}</div></ha-card>`;
      return;
    }
    this.shadowRoot.innerHTML = `${this._styles()}
      <ha-card>
        <div class="corps">
          <p class="aide">${esc(tr("intro"))}</p>
          ${this._erreur ? `<div class="erreur" role="alert">${esc(this._erreur)}
            ${this._confirmer ? `<div class="boutons"><button class="danger" data-action="confirmer">${esc(tr("couper_quand_meme"))}</button></div>` : ""}</div>` : ""}
          ${this._fait ? `<div class="fait" role="status">${esc(this._fait)}</div>` : ""}
          ${PARTIES.filter((p) => p !== "son" || Array.isArray(d.son)).map((p) => this._partie(p)).join("")}
        </div>
      </ha-card>`;
    this._brancher();
  }

  _partie(p) {
    const ouverte = this._ouvertes.has(p);
    return `<section class="partie">
      <button class="pli" data-pli="${p}" aria-expanded="${ouverte}" aria-controls="z-${p}">
        <span class="titre">${esc(tr("partie_" + p))}</span><span class="chevron" aria-hidden="true">${ouverte ? "▾" : "▸"}</span>
      </button>
      ${ouverte ? `<div class="zone" id="z-${p}">${p === "lieu" ? this._lieu() : p === "fonctions" ? this._fonctions() : p === "son" ? this._son() : this._droits()}</div>` : ""}
    </section>`;
  }

  _lieu() {
    const d = this._d;
    return `<p class="aide">${esc(tr("lieu_aide", { max: d.lieu.max }))}</p>
      <label class="champ"><span>${esc(tr("lieu_nom"))}</span>
        <input data-champ="lieu" maxlength="${esc(d.lieu.max)}" value="${esc(d.lieu.nom)}" placeholder="${esc(d.lieu.defaut)}"></label>
      <p class="aide">${esc(tr("mots_aide", { max: d.mots.max }))}</p>
      ${MOTS.map((m) => `<label class="champ"><span>${esc(tr("mot_" + m))}</span>
        <input data-mot="${m}" maxlength="${esc(d.mots.max)}" value="${esc(d.mots.actuels[m] || "")}" placeholder="${esc(d.mots.defauts[m])}"></label>`).join("")}
      <div class="boutons"><button class="principal" data-action="lieu" ${this._occupe ? "disabled" : ""}>${esc(tr("enregistrer"))}</button></div>`;
  }

  _module(m) {
    const besoin = m.requiert.length ? tr("requiert", { liste: m.requiert.join(", ") })
      : m.requiert_un.length ? tr("requiert_un", { liste: m.requiert_un.join(", ") }) : "";
    return `<div class="module">
      <label class="interrupteur">
        <input type="checkbox" role="switch" data-module="${esc(m.id)}" ${m.actif ? "checked" : ""} ${this._occupe ? "disabled" : ""}>
        <span class="nom">${esc(m.nom)}</span>
      </label>
      <div class="phrase">${esc(m.phrase)}</div>
      ${besoin ? `<div class="besoin">${esc(besoin)}</div>` : ""}
    </div>`;
  }

  _fonctions() {
    const f = this._d.fonctions;
    return `<p class="aide">${esc(tr("fonctions_aide"))}</p>
      ${f.groupes.map((g) => `<div class="groupe"><h3>${esc(tr("groupe_" + g.id))}</h3>${g.modules.map((m) => this._module(m)).join("")}</div>`).join("")}
      ${f.autres.length ? `<div class="groupe"><h3>${esc(tr("groupe_autres"))}</h3>${f.autres.map((m) => this._module(m)).join("")}</div>` : ""}`;
  }

  _droits() {
    const r = this._d.roles;
    return `<p class="aide">${esc(tr("droits_aide"))}</p>
      ${r.liste.map((role) => `<fieldset class="role"><legend>${esc(role.nom)}</legend>
        ${r.fonctions.map((f) => `<label class="choix">
          <input type="checkbox" data-role="${esc(role.id)}" data-fonction="${esc(f.id)}" ${role.fonctions.includes(f.id) ? "checked" : ""} ${this._occupe ? "disabled" : ""}>
          <span>${esc(f.nom)}${f.sensible ? ` <span class="sensible" title="${esc(tr("sensible"))}" aria-label="${esc(tr("sensible"))}">★</span>` : ""}</span>
        </label>`).join("")}
      </fieldset>`).join("")}`;
  }

  // 2.5.12.10 : niveau sonore par appareil (mesure, réaction, seuil général ou par plage, plancher de la pièce).
  _son() {
    const nb = (v) => (v === null || v === undefined || v === "" ? "" : String(v));
    const marge = (v, plancher) => (v === null || v === undefined || plancher === null || plancher === undefined) ? ""
      : `<span class="marge">${esc(tr("son_marge", { n: Math.round(v - plancher) }))}</span>`;
    const dis = this._occupe ? "disabled" : "";
    return `<p class="aide">${esc(tr("son_aide"))}</p>
      ${(this._d.son || []).map((a) => `<fieldset class="role son" data-son="${esc(a.id)}"><legend>${esc(a.nom)}</legend>
        <label class="choix"><input type="checkbox" data-son-champ="actif" ${a.actif ? "checked" : ""} ${dis}><span>${esc(tr("son_mesurer"))}</span></label>
        <label class="choix"><input type="checkbox" data-son-champ="reagir" ${a.reagir ? "checked" : ""} ${dis}><span>${esc(tr("son_reagir"))}</span></label>
        <div class="champ"><span>${esc(tr("son_mode"))}</span>
          <div class="boutons">
            <label class="choix"><input type="radio" name="mode-${esc(a.id)}" value="general" data-son-champ="mode" ${a.mode === "general" ? "checked" : ""} ${dis}><span>${esc(tr("son_mode_general"))}</span></label>
            <label class="choix"><input type="radio" name="mode-${esc(a.id)}" value="plages" data-son-champ="mode" ${a.mode === "plages" ? "checked" : ""} ${dis}><span>${esc(tr("son_mode_plages"))}</span></label>
          </div></div>
        <label class="champ"><span>${esc(tr("son_general"))}</span>
          <input type="number" min="-70" max="0" step="1" data-son-general value="${esc(nb(a.general_dbfs))}" ${dis}>
          ${marge(a.general_dbfs, a.plancher_dbfs)}</label>
        ${a.mode === "plages" ? (a.plages.length ? a.plages.map((pl) => `<label class="champ"><span>${esc(pl.nom)}</span>
          <input type="number" min="-70" max="0" step="1" data-son-plage="${esc(pl.id)}" value="${esc(nb(pl.seuil_dbfs))}" placeholder="${esc(tr("son_plage_vide"))}" ${dis}>
          ${marge(pl.seuil_dbfs, a.plancher_dbfs)}</label>`).join("") : `<p class="aide">${esc(tr("son_aucune_plage"))}</p>`) : ""}
        <label class="champ"><span>${esc(tr("son_plancher"))}</span>
          <input type="number" min="-120" max="-10" step="1" data-son-plancher value="${esc(nb(a.plancher_manuel_dbfs))}"
            placeholder="${esc(nb(a.plancher_mesure_dbfs))}" ${dis}></label>
        <p class="aide">${esc(tr("son_plancher_aide", { mesure: a.plancher_mesure_dbfs === null ? tr("son_plancher_inconnu") : a.plancher_mesure_dbfs + " dBFS" }))}</p>
        <p class="aide">${a.actif ? esc(tr("son_moment", { seuil: a.moment_dbfs, source: a.source || tr("son_source_general"),
          niveau: a.niveau_dbfs === null ? tr("son_niveau_inconnu") : a.niveau_dbfs + " dBFS" })) : esc(tr("son_eteint"))}</p>
        <div class="boutons"><button class="principal" data-son-enregistrer="${esc(a.id)}" ${dis}>${esc(tr("enregistrer"))}</button></div>
      </fieldset>`).join("")}`;
  }

  _brancher() {
    const r = this.shadowRoot;
    r.querySelectorAll("[data-pli]").forEach((b) => b.addEventListener("click", () => {
      const p = b.dataset.pli;
      if (this._ouvertes.has(p)) this._ouvertes.delete(p); else this._ouvertes.add(p);
      this._rendre();
      const nouveau = this.shadowRoot.querySelector(`[data-pli="${p}"]`);
      if (nouveau) nouveau.focus();
    }));
    const lieu = r.querySelector('[data-action="lieu"]');
    if (lieu) lieu.addEventListener("click", () => {
      const mots = {};
      r.querySelectorAll("[data-mot]").forEach((i) => { mots[i.dataset.mot] = i.value; });
      this._envoyer({ op: "lieu", lieu: r.querySelector('[data-champ="lieu"]').value, mots });
    });
    r.querySelectorAll("[data-module]").forEach((i) => i.addEventListener("change", () =>
      this._envoyer({ op: "module", module: i.dataset.module, actif: i.checked })));
    r.querySelectorAll("[data-fonction]").forEach((i) => i.addEventListener("change", () =>
      this._envoyer({ op: "droit", role: i.dataset.role, fonction: i.dataset.fonction, actif: i.checked })));
    r.querySelectorAll("[data-son]").forEach((bloc) => {
      const pc = bloc.dataset.son;
      bloc.querySelectorAll('[data-son-champ="actif"], [data-son-champ="reagir"]').forEach((i) => i.addEventListener("change", () =>
        this._envoyer({ op: "son", pc, [i.dataset.sonChamp]: i.checked })));
      bloc.querySelectorAll('[data-son-champ="mode"]').forEach((i) => i.addEventListener("change", () => {
        if (i.checked) this._envoyer({ op: "son", pc, mode: i.value });
      }));
      const bouton = bloc.querySelector("[data-son-enregistrer]");
      if (bouton) bouton.addEventListener("click", () => {
        const plages = {};
        bloc.querySelectorAll("[data-son-plage]").forEach((i) => { plages[i.dataset.sonPlage] = i.value === "" ? null : Number(i.value); });
        const g = bloc.querySelector("[data-son-general]").value;
        const pl = bloc.querySelector("[data-son-plancher]").value;
        const corps = { op: "son", pc, plancher_manuel_dbfs: pl === "" ? null : Number(pl) };
        if (g !== "") corps.general_dbfs = Number(g);
        if (Object.keys(plages).length) corps.plages = plages;
        this._envoyer(corps);
      });
    });
    const conf = r.querySelector('[data-action="confirmer"]');
    if (conf) conf.addEventListener("click", () => this._envoyer({ op: "module", module: "protections", actif: false, confirmer: true }));
  }

  _styles() {
    return `<style>
      :host { display:block; }
      .corps { padding:16px; }
      .aide { color:var(--secondary-text-color); margin:0 0 12px; line-height:1.4; }
      .vide { padding:16px; color:var(--secondary-text-color); }
      .erreur { background:rgba(219,68,55,.12); color:var(--error-color); padding:10px 12px; border-radius:8px; margin-bottom:12px; overflow-wrap:anywhere; }
      .fait { background:rgba(67,160,71,.12); color:var(--primary-text-color); padding:10px 12px; border-radius:8px; margin-bottom:12px; }
      .partie { border-top:1px solid var(--divider-color); }
      .pli { display:flex; width:100%; align-items:center; gap:12px; min-height:48px; padding:8px 0; background:none; border:0;
        color:var(--primary-text-color); font:inherit; font-weight:500; text-align:start; cursor:pointer; }
      .pli .titre { flex:1; min-width:0; overflow-wrap:anywhere; }
      .pli:focus-visible, button:focus-visible, input:focus-visible { outline:2px solid var(--primary-color); outline-offset:2px; }
      .zone { padding:4px 0 16px; }
      .champ { display:flex; flex-direction:column; gap:4px; margin:0 0 12px; }
      .champ span { font-size:.9em; color:var(--secondary-text-color); }
      .champ input { font:inherit; padding:10px 12px; border:1px solid var(--divider-color); border-radius:8px; min-width:0;
        background:var(--card-background-color); color:var(--primary-text-color); }
      .boutons { display:flex; flex-wrap:wrap; gap:8px; margin-top:8px; }
      button.principal, button.danger { font:inherit; min-height:40px; padding:8px 16px; border-radius:20px; border:0; cursor:pointer;
        color:var(--text-primary-color, #fff); max-width:100%; overflow-wrap:anywhere; }
      button.principal { background:var(--primary-color); }
      button.danger { background:var(--error-color); }
      button:disabled { opacity:.6; cursor:default; }
      .groupe h3 { font-size:1em; margin:16px 0 8px; }
      .module { padding:8px 0; border-bottom:1px solid var(--divider-color); }
      .interrupteur, .choix { display:flex; align-items:flex-start; gap:10px; min-height:32px; cursor:pointer; }
      .interrupteur input, .choix input { flex:none; width:20px; height:20px; margin:2px 0 0; }
      .interrupteur .nom { font-weight:500; min-width:0; overflow-wrap:anywhere; }
      .choix span { min-width:0; overflow-wrap:anywhere; }
      .phrase, .besoin { margin:2px 0 0 30px; color:var(--secondary-text-color); font-size:.92em; overflow-wrap:anywhere; }
      .besoin { font-style:italic; }
      fieldset.role { border:1px solid var(--divider-color); border-radius:8px; margin:0 0 12px; padding:8px 12px; min-width:0; }
      fieldset.role legend { font-weight:500; padding:0 4px; overflow-wrap:anywhere; max-width:100%; }
      .choix { padding:6px 0; }
      .sensible { color:var(--warning-color); }
      .son .champ input { max-width:12em; }
      .marge { font-size:.85em; color:var(--secondary-text-color); }
    </style>`;
  }
}

if (!customElements.get("vision-reglages-card")) customElements.define("vision-reglages-card", VisionReglagesCard);
window.customCards = window.customCards || [];
if (!window.customCards.some((c) => c.type === "vision-reglages-card")) {
  window.customCards.push({ type: "vision-reglages-card", name: tr("carte_nom"), description: tr("carte_description") });
}
