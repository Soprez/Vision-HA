/* vision-planning-card — grille semaine des plages Vision (pc_parental).
   Lit sensor.<pc>_plages (Vision >= 1.44.0) et pilote les services
   add_schedule / update_schedule / remove_schedule.
   Un bloc plein verrouille la session ; un bloc rayé ferme des étiquettes.
   Créer : glisser sur une colonne (souris) ou bouton +.
   Modifier : glisser une plage (déplacer), tirer ses bords (redimensionner),
   cliquer (éditeur : nom, jours, horaires, portée, étiquettes, couleur). */
(() => {
  "use strict";
// ---- i18n:debut — les textes de cette carte, dans les six langues de Vision (fr source, en repli) ----
const TEXTES = /*i18n-json*/{
  "fr": {
    "jour_lun": "Lun", "jour_mar": "Mar", "jour_mer": "Mer", "jour_jeu": "Jeu", "jour_ven": "Ven", "jour_sam": "Sam", "jour_dim": "Dim",
    "erreur_admin": "Réservé aux administrateurs de Home Assistant.",
    "erreur_loading": "Vision démarre : réessaie dans un instant.",
    "erreur_pc": "Cet appareil n'existe plus dans Vision.",
    "erreur_json": "La demande n'a pas pu être lue : réessaie.",
    "erreur_module": "Cette fonction est désactivée dans Vision.",
    "erreur_minutes": "Indique une durée.",
    "erreur_defaut": "Ce n'est pas passé : rien n'a changé, réessaie dans un instant.",
    "geste_en_cours": "En cours…",
    "geste_fait": "C'est fait.",
    "config_entite": "Indique l'entité : sensor.<pc>_plages",
    "entite_introuvable": "Entité introuvable : {entite}",
    "titre": "Planning — {pc}",
    "plus_plage": "+ Plage",
    "aide": "Bloc plein = session verrouillée, bloc rayé = étiquettes fermées. Glisser sur une colonne pour créer, déplacer/étirer un bloc pour le modifier, cliquer pour tout régler.",
    "nouvelle_plage": "Nouvelle plage",
    "plage_defaut": "Plage",
    "enregistre": "Enregistré.",
    "erreur_svc": "Ce n'est pas passé : rien n'a changé, réessaie.",
    "couleur_auto": "Couleur automatique",
    "autre_couleur": "Autre couleur",
    "modifier_plage": "Modifier la plage",
    "nom": "Nom", "debut": "Début", "fin": "Fin", "jours": "Jours",
    "plage_ferme": "Cette plage ferme",
    "portee_session": "La session (appareil verrouillé)",
    "portee_etiquettes": "Des étiquettes",
    "etiquettes_a_fermer": "Étiquettes à fermer",
    "aucune_etiquette": "Aucune étiquette pour l'instant — crée-la dans l'onglet Étiquettes, ou choisis un site ci-dessous.",
    "viser_element": "…ou viser un site / un logiciel précis",
    "filtrer": "Filtrer la liste…",
    "ajouter": "Ajouter",
    "site_l_emporte": "Une plage qui nomme un site l'emporte sur « autorisé » : c'est ce qu'on peut dire de plus précis sur lui.",
    "sauf_sur": "Sauf sur",
    "sauf_aide": "Le planning vaut pour tous les appareils de la personne. Coche ceux que cette plage épargne.",
    "couleur": "Couleur",
    "plage_active": "Plage active",
    "enregistrer": "Enregistrer", "supprimer": "Supprimer", "annuler": "Annuler",
    "aucun_nom_vise": "Aucun nom visé.",
    "retirer": "Retirer",
    "groupe_sites": "Sites", "groupe_logiciels": "Logiciels",
    "rien_a_proposer": "— rien à proposer —",
    "heures_incompletes": "Heures incomplètes.",
    "debut_fin_differents": "Le début et la fin doivent être différents.",
    "cocher_jour": "Coche au moins un jour.",
    "choisir_etiquette": "Choisis une étiquette, ou un site à fermer.",
    "carte_nom": "Planning Vision (plages d'une personne)",
    "carte_description": "Grille semaine des plages d'une personne : session ou étiquettes, création au glisser, déplacement, redimensionnement, couleurs.",
    "vue_liste": "Liste",
    "vue_grille": "Grille",
    "vue_choix": "Affichage du planning",
    "aide_liste": "Les plages de chaque jour, dans l'ordre. Touche une plage pour la modifier ; « + Plage » en ajoute une.",
    "aucune_plage_jour": "Aucune plage",
    "plage_inactive": "désactivée"
  },
  "en": {
    "jour_lun": "Mon", "jour_mar": "Tue", "jour_mer": "Wed", "jour_jeu": "Thu", "jour_ven": "Fri", "jour_sam": "Sat", "jour_dim": "Sun",
    "erreur_admin": "Only Home Assistant administrators can do this.",
    "erreur_loading": "Vision is starting: try again in a moment.",
    "erreur_pc": "This device no longer exists in Vision.",
    "erreur_json": "The request could not be read: try again.",
    "erreur_module": "This feature is turned off in Vision.",
    "erreur_minutes": "Enter a duration.",
    "erreur_defaut": "That didn't work: nothing changed, try again in a moment.",
    "geste_en_cours": "Working…",
    "geste_fait": "Done.",
    "config_entite": "Set the entity: sensor.<pc>_plages",
    "entite_introuvable": "Entity not found: {entite}",
    "titre": "Schedule — {pc}",
    "plus_plage": "+ Time slot",
    "aide": "Solid block = session locked, striped block = categories blocked. Drag on a column to create, move/stretch a block to change it, click to set everything.",
    "nouvelle_plage": "New time slot",
    "plage_defaut": "Time slot",
    "enregistre": "Saved.",
    "erreur_svc": "That didn't work: nothing changed, try again.",
    "couleur_auto": "Automatic colour",
    "autre_couleur": "Other colour",
    "modifier_plage": "Edit time slot",
    "nom": "Name", "debut": "Start", "fin": "End", "jours": "Days",
    "plage_ferme": "This time slot blocks",
    "portee_session": "The session (device locked)",
    "portee_etiquettes": "Categories",
    "etiquettes_a_fermer": "Categories to block",
    "aucune_etiquette": "No categories yet — create one in the Categories tab, or pick a site below.",
    "viser_element": "…or target a specific site / program",
    "filtrer": "Filter the list…",
    "ajouter": "Add",
    "site_l_emporte": "A time slot that names a site wins over “allowed”: it's the most precise thing you can say about it.",
    "sauf_sur": "Except on",
    "sauf_aide": "The schedule applies to all of this person's devices. Tick the ones this time slot leaves alone.",
    "couleur": "Colour",
    "plage_active": "Time slot on",
    "enregistrer": "Save", "supprimer": "Delete", "annuler": "Cancel",
    "aucun_nom_vise": "Nothing targeted.",
    "retirer": "Remove",
    "groupe_sites": "Sites", "groupe_logiciels": "Programs",
    "rien_a_proposer": "— nothing to offer —",
    "heures_incompletes": "Times are incomplete.",
    "debut_fin_differents": "Start and end must be different.",
    "cocher_jour": "Tick at least one day.",
    "choisir_etiquette": "Pick a category, or a site to block.",
    "carte_nom": "Vision schedule (a person's time slots)",
    "carte_description": "Weekly grid of a person's time slots: session or categories, drag to create, move, resize, colours.",
    "vue_liste": "List",
    "vue_grille": "Grid",
    "vue_choix": "Schedule view",
    "aide_liste": "Each day's time slots, in order. Tap a slot to change it; \"+ Time slot\" adds one.",
    "aucune_plage_jour": "No time slot",
    "plage_inactive": "turned off"
  },
  "de": {
    "jour_lun": "Mo", "jour_mar": "Di", "jour_mer": "Mi", "jour_jeu": "Do", "jour_ven": "Fr", "jour_sam": "Sa", "jour_dim": "So",
    "erreur_admin": "Nur für Home Assistant-Administratoren.",
    "erreur_loading": "Vision startet gerade: versuch es gleich noch einmal.",
    "erreur_pc": "Dieses Gerät gibt es in Vision nicht mehr.",
    "erreur_json": "Die Anfrage konnte nicht gelesen werden: versuch es noch einmal.",
    "erreur_module": "Diese Funktion ist in Vision ausgeschaltet.",
    "erreur_minutes": "Gib eine Dauer an.",
    "erreur_defaut": "Das hat nicht geklappt: nichts wurde geändert, versuch es gleich noch einmal.",
    "geste_en_cours": "Wird ausgeführt…",
    "geste_fait": "Erledigt.",
    "config_entite": "Gib die Entität an: sensor.<pc>_plages",
    "entite_introuvable": "Entität nicht gefunden: {entite}",
    "titre": "Zeitplan — {pc}",
    "plus_plage": "+ Zeitfenster",
    "aide": "Voller Block = Sitzung gesperrt, gestreifter Block = Kategorien gesperrt. Auf einer Spalte ziehen zum Anlegen, einen Block verschieben/dehnen zum Ändern, anklicken für alle Einstellungen.",
    "nouvelle_plage": "Neues Zeitfenster",
    "plage_defaut": "Zeitfenster",
    "enregistre": "Gespeichert.",
    "erreur_svc": "Das hat nicht geklappt: nichts wurde geändert, versuch es noch einmal.",
    "couleur_auto": "Automatische Farbe",
    "autre_couleur": "Andere Farbe",
    "modifier_plage": "Zeitfenster bearbeiten",
    "nom": "Name", "debut": "Beginn", "fin": "Ende", "jours": "Tage",
    "plage_ferme": "Dieses Zeitfenster sperrt",
    "portee_session": "Die Sitzung (Gerät gesperrt)",
    "portee_etiquettes": "Kategorien",
    "etiquettes_a_fermer": "Zu sperrende Kategorien",
    "aucune_etiquette": "Noch keine Kategorie — leg sie im Tab Kategorien an oder wähl unten eine Website.",
    "viser_element": "…oder eine bestimmte Website / ein Programm wählen",
    "filtrer": "Liste filtern…",
    "ajouter": "Hinzufügen",
    "site_l_emporte": "Ein Zeitfenster, das eine Website nennt, hat Vorrang vor „erlaubt“: genauer lässt sich über sie nichts sagen.",
    "sauf_sur": "Außer auf",
    "sauf_aide": "Der Zeitplan gilt für alle Geräte der Person. Hak die an, die dieses Zeitfenster auslässt.",
    "couleur": "Farbe",
    "plage_active": "Zeitfenster aktiv",
    "enregistrer": "Speichern", "supprimer": "Löschen", "annuler": "Abbrechen",
    "aucun_nom_vise": "Nichts ausgewählt.",
    "retirer": "Entfernen",
    "groupe_sites": "Websites", "groupe_logiciels": "Programme",
    "rien_a_proposer": "— nichts vorzuschlagen —",
    "heures_incompletes": "Uhrzeiten unvollständig.",
    "debut_fin_differents": "Beginn und Ende müssen verschieden sein.",
    "cocher_jour": "Hak mindestens einen Tag an.",
    "choisir_etiquette": "Wähl eine Kategorie oder eine Website zum Sperren.",
    "carte_nom": "Vision-Zeitplan (Zeitfenster einer Person)",
    "carte_description": "Wochenraster der Zeitfenster einer Person: Sitzung oder Kategorien, Anlegen durch Ziehen, Verschieben, Größe ändern, Farben.",
    "vue_liste": "Liste",
    "vue_grille": "Raster",
    "vue_choix": "Ansicht des Zeitplans",
    "aide_liste": "Die Zeitfenster jedes Tages, der Reihe nach. Tippe auf ein Zeitfenster, um es zu ändern; „+ Zeitfenster“ fügt eines hinzu.",
    "aucune_plage_jour": "Kein Zeitfenster",
    "plage_inactive": "ausgeschaltet"
  },
  "es": {
    "jour_lun": "Lun", "jour_mar": "Mar", "jour_mer": "Mié", "jour_jeu": "Jue", "jour_ven": "Vie", "jour_sam": "Sáb", "jour_dim": "Dom",
    "erreur_admin": "Solo para administradores de Home Assistant.",
    "erreur_loading": "Vision se está iniciando: inténtalo de nuevo en un momento.",
    "erreur_pc": "Este dispositivo ya no existe en Vision.",
    "erreur_json": "No se pudo leer la solicitud: inténtalo de nuevo.",
    "erreur_module": "Esta función está desactivada en Vision.",
    "erreur_minutes": "Indica una duración.",
    "erreur_defaut": "No ha funcionado: no ha cambiado nada, inténtalo de nuevo en un momento.",
    "geste_en_cours": "En curso…",
    "geste_fait": "Hecho.",
    "config_entite": "Indica la entidad: sensor.<pc>_plages",
    "entite_introuvable": "Entidad no encontrada: {entite}",
    "titre": "Horario — {pc}",
    "plus_plage": "+ Franja",
    "aide": "Bloque lleno = sesión bloqueada, bloque rayado = categorías bloqueadas. Arrastra sobre una columna para crear, mueve/estira un bloque para cambiarlo, haz clic para ajustarlo todo.",
    "nouvelle_plage": "Nueva franja horaria",
    "plage_defaut": "Franja horaria",
    "enregistre": "Guardado.",
    "erreur_svc": "No ha funcionado: no ha cambiado nada, inténtalo de nuevo.",
    "couleur_auto": "Color automático",
    "autre_couleur": "Otro color",
    "modifier_plage": "Editar la franja horaria",
    "nom": "Nombre", "debut": "Inicio", "fin": "Fin", "jours": "Días",
    "plage_ferme": "Esta franja bloquea",
    "portee_session": "La sesión (dispositivo bloqueado)",
    "portee_etiquettes": "Categorías",
    "etiquettes_a_fermer": "Categorías que bloquear",
    "aucune_etiquette": "Todavía no hay categorías — crea una en la pestaña Categorías, o elige un sitio abajo.",
    "viser_element": "…o elegir un sitio / un programa concreto",
    "filtrer": "Filtrar la lista…",
    "ajouter": "Añadir",
    "site_l_emporte": "Una franja que nombra un sitio prevalece sobre «permitido»: es lo más preciso que se puede decir de él.",
    "sauf_sur": "Excepto en",
    "sauf_aide": "El horario vale para todos los dispositivos de la persona. Marca los que esta franja deja libres.",
    "couleur": "Color",
    "plage_active": "Franja activa",
    "enregistrer": "Guardar", "supprimer": "Eliminar", "annuler": "Cancelar",
    "aucun_nom_vise": "Nada elegido.",
    "retirer": "Quitar",
    "groupe_sites": "Sitios", "groupe_logiciels": "Programas",
    "rien_a_proposer": "— nada que proponer —",
    "heures_incompletes": "Horas incompletas.",
    "debut_fin_differents": "El inicio y el fin deben ser distintos.",
    "cocher_jour": "Marca al menos un día.",
    "choisir_etiquette": "Elige una categoría, o un sitio que bloquear.",
    "carte_nom": "Horario de Vision (franjas de una persona)",
    "carte_description": "Cuadrícula semanal de las franjas de una persona: sesión o categorías, creación arrastrando, mover, cambiar tamaño, colores.",
    "vue_liste": "Lista",
    "vue_grille": "Cuadrícula",
    "vue_choix": "Vista del horario",
    "aide_liste": "Las franjas de cada día, en orden. Toca una franja para cambiarla; «+ Franja» añade una.",
    "aucune_plage_jour": "Ninguna franja",
    "plage_inactive": "desactivada"
  },
  "it": {
    "jour_lun": "Lun", "jour_mar": "Mar", "jour_mer": "Mer", "jour_jeu": "Gio", "jour_ven": "Ven", "jour_sam": "Sab", "jour_dim": "Dom",
    "erreur_admin": "Riservato agli amministratori di Home Assistant.",
    "erreur_loading": "Vision si sta avviando: riprova tra un momento.",
    "erreur_pc": "Questo dispositivo non esiste più in Vision.",
    "erreur_json": "Non è stato possibile leggere la richiesta: riprova.",
    "erreur_module": "Questa funzione è disattivata in Vision.",
    "erreur_minutes": "Indica una durata.",
    "erreur_defaut": "Non è andata: non è cambiato nulla, riprova tra un momento.",
    "geste_en_cours": "In corso…",
    "geste_fait": "Fatto.",
    "config_entite": "Indica l'entità: sensor.<pc>_plages",
    "entite_introuvable": "Entità non trovata: {entite}",
    "titre": "Programma — {pc}",
    "plus_plage": "+ Fascia",
    "aide": "Blocco pieno = sessione bloccata, blocco a righe = categorie bloccate. Trascina su una colonna per creare, sposta/allunga un blocco per modificarlo, fai clic per regolare tutto.",
    "nouvelle_plage": "Nuova fascia oraria",
    "plage_defaut": "Fascia oraria",
    "enregistre": "Salvato.",
    "erreur_svc": "Non è andata: non è cambiato nulla, riprova.",
    "couleur_auto": "Colore automatico",
    "autre_couleur": "Altro colore",
    "modifier_plage": "Modifica la fascia oraria",
    "nom": "Nome", "debut": "Inizio", "fin": "Fine", "jours": "Giorni",
    "plage_ferme": "Questa fascia blocca",
    "portee_session": "La sessione (dispositivo bloccato)",
    "portee_etiquettes": "Categorie",
    "etiquettes_a_fermer": "Categorie da bloccare",
    "aucune_etiquette": "Ancora nessuna categoria — creala nella scheda Categorie, oppure scegli un sito qui sotto.",
    "viser_element": "…oppure scegliere un sito / un programma preciso",
    "filtrer": "Filtra l'elenco…",
    "ajouter": "Aggiungi",
    "site_l_emporte": "Una fascia che nomina un sito prevale su «consentito»: è la cosa più precisa che si possa dire su quel sito.",
    "sauf_sur": "Tranne su",
    "sauf_aide": "Il programma vale per tutti i dispositivi della persona. Spunta quelli che questa fascia risparmia.",
    "couleur": "Colore",
    "plage_active": "Fascia attiva",
    "enregistrer": "Salva", "supprimer": "Elimina", "annuler": "Annulla",
    "aucun_nom_vise": "Nessun nome scelto.",
    "retirer": "Rimuovi",
    "groupe_sites": "Siti", "groupe_logiciels": "Programmi",
    "rien_a_proposer": "— niente da proporre —",
    "heures_incompletes": "Orari incompleti.",
    "debut_fin_differents": "L'inizio e la fine devono essere diversi.",
    "cocher_jour": "Spunta almeno un giorno.",
    "choisir_etiquette": "Scegli una categoria, o un sito da bloccare.",
    "carte_nom": "Programma di Vision (fasce di una persona)",
    "carte_description": "Griglia settimanale delle fasce di una persona: sessione o categorie, creazione trascinando, spostamento, ridimensionamento, colori.",
    "vue_liste": "Elenco",
    "vue_grille": "Griglia",
    "vue_choix": "Vista del planning",
    "aide_liste": "Le fasce di ogni giorno, in ordine. Tocca una fascia per modificarla; «+ Fascia» ne aggiunge una.",
    "aucune_plage_jour": "Nessuna fascia",
    "plage_inactive": "disattivata"
  },
  "nl": {
    "jour_lun": "Ma", "jour_mar": "Di", "jour_mer": "Wo", "jour_jeu": "Do", "jour_ven": "Vr", "jour_sam": "Za", "jour_dim": "Zo",
    "erreur_admin": "Alleen voor beheerders van Home Assistant.",
    "erreur_loading": "Vision start op: probeer het zo opnieuw.",
    "erreur_pc": "Dit apparaat bestaat niet meer in Vision.",
    "erreur_json": "Het verzoek kon niet worden gelezen: probeer het opnieuw.",
    "erreur_module": "Deze functie staat uit in Vision.",
    "erreur_minutes": "Geef een duur op.",
    "erreur_defaut": "Dat is niet gelukt: er is niets veranderd, probeer het zo opnieuw.",
    "geste_en_cours": "Bezig…",
    "geste_fait": "Klaar.",
    "config_entite": "Geef de entiteit op: sensor.<pc>_plages",
    "entite_introuvable": "Entiteit niet gevonden: {entite}",
    "titre": "Planning — {pc}",
    "plus_plage": "+ Tijdvak",
    "aide": "Vol blok = sessie vergrendeld, gestreept blok = categorieën geblokkeerd. Sleep over een kolom om te maken, verschuif/rek een blok om het te wijzigen, klik om alles in te stellen.",
    "nouvelle_plage": "Nieuw tijdvak",
    "plage_defaut": "Tijdvak",
    "enregistre": "Opgeslagen.",
    "erreur_svc": "Dat is niet gelukt: er is niets veranderd, probeer het opnieuw.",
    "couleur_auto": "Automatische kleur",
    "autre_couleur": "Andere kleur",
    "modifier_plage": "Tijdvak bewerken",
    "nom": "Naam", "debut": "Begin", "fin": "Einde", "jours": "Dagen",
    "plage_ferme": "Dit tijdvak blokkeert",
    "portee_session": "De sessie (apparaat vergrendeld)",
    "portee_etiquettes": "Categorieën",
    "etiquettes_a_fermer": "Te blokkeren categorieën",
    "aucune_etiquette": "Nog geen categorieën — maak er een in het tabblad Categorieën, of kies hieronder een site.",
    "viser_element": "…of een bepaalde site / een bepaald programma kiezen",
    "filtrer": "Lijst filteren…",
    "ajouter": "Toevoegen",
    "site_l_emporte": "Een tijdvak dat een site noemt, gaat voor op „toegestaan”: preciezer kun je er niets over zeggen.",
    "sauf_sur": "Behalve op",
    "sauf_aide": "De planning geldt voor alle apparaten van de persoon. Vink de apparaten aan die dit tijdvak overslaat.",
    "couleur": "Kleur",
    "plage_active": "Tijdvak actief",
    "enregistrer": "Opslaan", "supprimer": "Verwijderen", "annuler": "Annuleren",
    "aucun_nom_vise": "Niets gekozen.",
    "retirer": "Verwijderen",
    "groupe_sites": "Sites", "groupe_logiciels": "Programma's",
    "rien_a_proposer": "— niets voor te stellen —",
    "heures_incompletes": "Tijden onvolledig.",
    "debut_fin_differents": "Begin en einde moeten verschillen.",
    "cocher_jour": "Vink minstens één dag aan.",
    "choisir_etiquette": "Kies een categorie, of een site om te blokkeren.",
    "carte_nom": "Vision-planning (tijdvakken van een persoon)",
    "carte_description": "Weekraster van de tijdvakken van een persoon: sessie of categorieën, maken door te slepen, verschuiven, formaat wijzigen, kleuren.",
    "vue_liste": "Lijst",
    "vue_grille": "Raster",
    "vue_choix": "Weergave van het rooster",
    "aide_liste": "De tijdvakken van elke dag, op volgorde. Tik op een tijdvak om het te wijzigen; „+ Tijdvak” voegt er een toe.",
    "aucune_plage_jour": "Geen tijdvak",
    "plage_inactive": "uitgeschakeld"
  }
}/*i18n-json:fin*/;
// Les catégories (étiquettes de la maison) : copie de pc_parental/i18n/categories.json, pour les pastilles et les blocs.
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

  const PALETTE = [
    "#e5405e", "#3b82f6", "#f59e0b", "#10b981", "#8b5cf6",
    "#ec4899", "#06b6d4", "#84cc16", "#f97316", "#64748b",
  ];
  const JOURS_COURTS = ["jour_lun", "jour_mar", "jour_mer", "jour_jeu", "jour_ven", "jour_sam", "jour_dim"];
  const JOURS_SVC = ["lun", "mar", "mer", "jeu", "ven", "sam", "dim"];
  const SNAP = 900; // 15 minutes
  const H_HEURE = 26; // pixels par heure
  const H_TOTAL = 24 * H_HEURE;
  const JOUR_S = 86400;

  const clamp = (v, a, b) => Math.min(b, Math.max(a, v));
  const snap = (s) => Math.round(s / SNAP) * SNAP;
  const s2y = (s) => (s / 3600) * H_HEURE;

  function hLisible(s) {
    s = ((s % JOUR_S) + JOUR_S) % JOUR_S;
    return trHeure(new Date(2000, 0, 1, Math.floor(s / 3600), Math.floor((s % 3600) / 60)));
  }
  function hService(s) {
    s = ((s % JOUR_S) + JOUR_S) % JOUR_S;
    const h = String(Math.floor(s / 3600)).padStart(2, "0");
    const m = String(Math.floor((s % 3600) / 60)).padStart(2, "0");
    return h + ":" + m + ":00";
  }
  function hDepuisInput(v) {
    if (!v) return null;
    const [h, m] = v.split(":").map((x) => parseInt(x, 10));
    if (isNaN(h) || isNaN(m)) return null;
    return h * 3600 + m * 60;
  }
  function hVersInput(s) {
    s = ((s % JOUR_S) + JOUR_S) % JOUR_S;
    const h = String(Math.floor(s / 3600)).padStart(2, "0");
    const m = String(Math.floor((s % 3600) / 60)).padStart(2, "0");
    return h + ":" + m;
  }
  const echap = (t) =>
    String(t == null ? "" : t)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");

  // Un choix multiple (vision-choix) rendu à la liste d'origine : ce que la liste ne propose pas reste tel quel, ce qui
  // reste choisi garde sa place et son écriture, ce qui vient d'être choisi s'ajoute au bout.
  function fusionnerChoix(anciens, choisis, connus, cle) {
    const proposes = new Set(connus.map(cle));
    const gardes = new Set(choisis.map(cle));
    const sortie = (anciens || []).filter((x) => !proposes.has(cle(x)) || gardes.has(cle(x)));
    for (const c of choisis) if (!sortie.some((x) => cle(x) === cle(c))) sortie.push(c);
    return sortie;
  }

  function cibles(p) {
    const noms = (p.etiquettes || []).map(trCat).concat(
      (p.elements || []).map((e) => e.nom)
    );
    return noms.join(", ");
  }

  function couleurDefaut(id) {
    return PALETTE[Math.abs(parseInt(id, 10) || 0) % PALETTE.length];
  }

  // Lot 1 (A7) : le texte d'une plage est noir ou blanc selon la clarté de sa couleur (le plus lisible des deux,
  // au moins 4,5:1 sur les couleurs de la palette) ; une couleur qu'on ne sait pas lire garde le blanc.
  function encreSur(couleur) {
    const m = /^#([0-9a-f]{6})$/i.exec(String(couleur || "").trim());
    if (!m) return "#fff";
    const lin = (v) => { v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); };
    const n = parseInt(m[1], 16);
    const l = 0.2126 * lin((n >> 16) & 255) + 0.7152 * lin((n >> 8) & 255) + 0.0722 * lin(n & 255);
    return (l + 0.05) / 0.05 >= 1.05 / (l + 0.05) ? "#000" : "#fff";
  }

// 1.97.3 : le retour visible de chaque geste, le même dans toutes les cartes Vision. Au clic, le bouton touché dit
// « En cours… » ; puis une ligne en tête de carte dit le résultat (la réponse du serveur) ou l'échec, en clair.
// Elle survit aux redessins de la carte (observateur) et le résultat s'efface seul après 6 s.
const ERREURS_GESTE = {
  admin: "erreur_admin", loading: "erreur_loading", pc: "erreur_pc", json: "erreur_json",
  module: "erreur_module", minutes: "erreur_minutes",
};
const messageGeste = (e, defaut) => {
  const b = e && e.body;
  if (b && b.retour) return String(b.retour);
  if (b && ERREURS_GESTE[b.error]) return tr(ERREURS_GESTE[b.error]);
  if (e && e.status_code === 403) return tr(ERREURS_GESTE.admin);
  return defaut || tr("erreur_defaut");
};
const retourGeste = (hote) => {
  const r = hote.shadowRoot;
  let clic = "", enCours = "", texte = "", ko = false, minute = 0, pose = false;
  const cle = (b) => JSON.stringify(Object.assign({}, b.dataset)) + "|" + b.textContent.trim();
  r.addEventListener("click", (ev) => {
    const b = ev.composedPath().find((x) => x && x.tagName === "BUTTON");
    if (b) clic = cle(b);
  }, true);
  const appliquer = () => {
    if (pose) return;
    pose = true;
    try {
      if (enCours) {
        for (const b of r.querySelectorAll("button")) {
          if (cle(b) === enCours) { b.textContent = tr("geste_en_cours"); b.disabled = true; b.setAttribute("aria-busy", "true"); }
        }
      }
      let z = r.querySelector(".vision-retour");
      if (!texte) { if (z) z.remove(); return; }
      if (!z) {
        z = document.createElement("div");
        z.className = "vision-retour";
        const c = r.querySelector("ha-card") || r;
        c.insertBefore(z, c.firstChild);
      }
      const couleur = ko ? "var(--error-color, #c62828)" : "var(--success-color, #2e7d32)";
      z.setAttribute("role", ko ? "alert" : "status");
      const style = `position:sticky;top:0;z-index:2;margin:8px 12px;padding:8px 12px;border-radius:10px;font-weight:500;color:${couleur};` +
        `background:var(--card-background-color, #fff);border:1px solid ${couleur};`;
      if (z.getAttribute("style") !== style) z.setAttribute("style", style);
      if (z.textContent !== texte) z.textContent = texte;
    } finally {
      pose = false;
    }
  };
  new MutationObserver(appliquer).observe(r, { childList: true, subtree: true });
  return {
    debut() { enCours = clic; clic = ""; texte = ""; clearTimeout(minute); appliquer(); },
    ok(t) {
      enCours = ""; ko = false; texte = t || tr("geste_fait"); appliquer();
      clearTimeout(minute); minute = setTimeout(() => { texte = ""; appliquer(); }, 6000);
    },
    echec(e, defaut) { enCours = ""; ko = true; texte = typeof e === "string" ? e : messageGeste(e, defaut); clearTimeout(minute); appliquer(); },
  };
};

  class VisionPlanningCard extends HTMLElement {
    constructor() {
      super();
      this.attachShadow({ mode: "open" });
    this._geste = retourGeste(this);
      this._sel = null;   // plage en cours d'édition (copie de travail)
      this._drag = null;  // glissement en cours
      this._rendered = false;
      this._cat = null;      // catalogue sites + logiciels
      this._filtreEl = "";   // filtre du selecteur d'elements
      // Refonte lot 6 (HA-21) : en liste par jour sur un écran étroit (la grille y est illisible), au choix sinon.
      this._vue = "auto";
      this._largeur = 0;
    }

    connectedCallback() {
      if (this._obs || typeof ResizeObserver === "undefined") return;
      this._obs = new ResizeObserver((e) => {
        const l = Math.round(e[0].contentRect.width);
        const avant = this._vueEffective();
        this._largeur = l;
        if (this._rendered && this._vueEffective() !== avant && !this._drag && !this._sel) this._render();
      });
      this._obs.observe(this);
    }

    disconnectedCallback() {
      if (this._obs) { this._obs.disconnect(); this._obs = null; }
    }

    _vueEffective() {
      if (this._vue !== "auto") return this._vue;
      return this._largeur && this._largeur < 520 ? "liste" : "grille";
    }

    async _catalogue() {
      // Les noms des sites et des logiciels ne voyagent pas dans les
      // attributs du capteur : plusieurs centaines de lignes recopiees a
      // chaque changement, ecrites en base pour rien. On les demande.
      if (this._cat || !this._hass) return this._cat;
      try {
        const r = await this._hass.callApi("GET", "pc_parental/catalogue");
        this._cat = (r && r.elements) || [];
      } catch (e) {
        this._cat = [];
      }
      return this._cat;
    }

    setConfig(config) {
      if (!config || !config.entity) {
        throw new Error(tr("config_entite"));
      }
      this._config = config;
      this._rendered = false;
    }

    getCardSize() {
      return 14;
    }

    static getStubConfig(hass) {
      const e = Object.keys(hass.states).find(
        (k) => k.startsWith("sensor.") && k.endsWith("_plages")
      );
      return { entity: e || "sensor.choisissez_un_appareil_plages" };
    }

    set hass(hass) {
      const ancien = this._etat;
      this._hass = hass;
      const langue = suivreLangue(hass);
      const etat = hass.states[(this._config || {}).entity];
      this._etat = etat;
      if (!etat) {
        this._renderErreur();
        return;
      }
      if (this._drag || this._sel) return; // ne pas casser un geste en cours
      if (!this._rendered || ancien !== etat || langue !== this._langue) this._render();
    }

    _plages() {
      const attrs = (this._etat || {}).attributes || {};
      return (attrs.plages || []).map((p) => ({
        id: p.id,
        nom: p.nom || "",
        debut: ((p.debut % JOUR_S) + JOUR_S) % JOUR_S,
        fin: ((p.fin % JOUR_S) + JOUR_S) % JOUR_S,
        jours: (() => {
          const j = (p.jours || []).slice(0, 7).map(Boolean);
          while (j.length < 7) j.push(false);
          return j;
        })(),
        active: !!p.active,
        portee: p.portee === "etiquettes" ? "etiquettes" : "session",
        etiquettes: (p.etiquettes || []).slice(),
        elements: (p.elements || []).map((e) => ({
          genre: e.genre,
          nom: e.nom,
        })),
        couleur: p.couleur || null,
        libelle: p.libelle || "",
      }));
    }

    _pc() {
      return ((this._etat || {}).attributes || {}).pc || "";
    }

    // Les appareils de la personne : le planning est le sien, une plage peut en épargner certains.
    _appareils() {
      return ((this._etat || {}).attributes || {}).appareils || [];
    }

    _vocabulaire() {
      return ((this._etat || {}).attributes || {}).vocabulaire || [];
    }

    /* ---------------- segments (une plage = 1 ou 2 blocs par jour) -------- */

    _segments(surcharge) {
      const segs = [];
      for (const p0 of this._plages()) {
        const p =
          surcharge && surcharge.id === p0.id
            ? Object.assign({}, p0, {
                debut: surcharge.debut,
                fin: surcharge.fin,
              })
            : p0;
        const couleur = p.couleur || couleurDefaut(p.id);
        const nuit = p.fin <= p.debut; // enjambe minuit (appartient au jour de début)
        for (let d = 0; d < 7; d++) {
          if (!p.jours[d]) continue;
          if (!nuit) {
            segs.push({ p, jour: d, de: p.debut, a: p.fin, bordHaut: true, bordBas: true, couleur });
          } else {
            segs.push({ p, jour: d, de: p.debut, a: JOUR_S, bordHaut: true, bordBas: p.fin === 0, couleur });
            if (p.fin > 0) {
              segs.push({ p, jour: (d + 1) % 7, de: 0, a: p.fin, bordHaut: false, bordBas: true, couleur });
            }
          }
        }
      }
      return segs;
    }

    /* Répartit les segments qui se chevauchent en colonnes parallèles. */
    _voies(segs) {
      const parJour = new Map();
      for (const s of segs) {
        if (!parJour.has(s.jour)) parJour.set(s.jour, []);
        parJour.get(s.jour).push(s);
      }
      for (const liste of parJour.values()) {
        liste.sort((a, b) => a.de - b.de || b.a - a.a);
        const fins = []; // fin de la derniere plage de chaque voie
        let grappe = [];
        let finGrappe = -1;
        const clore = () => {
          const n = fins.length;
          for (const s of grappe) s.nVoies = n;
          grappe = [];
          fins.length = 0;
        };
        for (const s of liste) {
          if (s.de >= finGrappe && grappe.length) clore();
          let v = fins.findIndex((f) => f <= s.de);
          if (v === -1) {
            v = fins.length;
            fins.push(s.a);
          } else {
            fins[v] = s.a;
          }
          s.voie = v;
          grappe.push(s);
          finGrappe = Math.max(finGrappe, s.a);
        }
        if (grappe.length) clore();
      }
      return segs;
    }

    /* ------------------------------ rendu --------------------------------- */

    _renderErreur() {
      this.shadowRoot.innerHTML =
        '<ha-card><div style="padding:16px;">' +
        echap(tr("entite_introuvable", { entite: (this._config || {}).entity || "?" })) +
        "</div></ha-card>";
      this._rendered = false;
    }

    _style() {
      return `
      <style>
        :host { display: block; }
        ha-card { overflow: hidden; position: relative; }
        .entete { display: flex; align-items: center; gap: 8px; padding: 12px 16px 4px; flex-wrap: wrap; }
        .titre { font-size: 1.15em; font-weight: 500; flex: 1 1 14em; min-width: 0; overflow-wrap: anywhere; }
        .vues { display: flex; border: 1px solid var(--divider-color); border-radius: 8px; overflow: hidden; }
        .vue { border: none; background: none; color: var(--primary-text-color); font: inherit; font-size: 14px;
          padding: 6px 10px; min-height: 32px; cursor: pointer; }
        .vue[aria-pressed="true"] { background: var(--primary-color); color: var(--text-primary-color, #fff); font-weight: 500; }
        .vue:focus-visible, .ligne:focus-visible { outline: 2px solid var(--primary-color); outline-offset: 2px; }
        .liste { padding: 4px 16px 16px; }
        .jour h3 { font-size: 1em; margin: 12px 0 4px; text-transform: capitalize; }
        .jour .vide { color: var(--secondary-text-color); font-size: .92em; padding: 4px 0; }
        .ligne { display: flex; align-items: flex-start; gap: 10px; width: 100%; text-align: start; font: inherit;
          color: var(--primary-text-color); background: none; border: 0; border-bottom: 1px solid var(--divider-color);
          padding: 10px 0; min-height: 48px; cursor: pointer; }
        .ligne .pastille { flex: none; width: 14px; height: 14px; border-radius: 50%; margin-top: 3px; }
        .ligne .txt { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
        .ligne .nom { font-weight: 500; overflow-wrap: anywhere; }
        .ligne .h { color: var(--secondary-text-color); font-size: .92em; overflow-wrap: anywhere; }
        .ligne.inactive .nom { color: var(--secondary-text-color); }
        .btn-plus {
          border: none; border-radius: 8px; cursor: pointer;
          background: var(--primary-color); color: var(--text-primary-color, #fff);
          font-size: 14px; padding: 6px 12px; font-weight: 500;
        }
        .aide { padding: 0 16px 8px; font-size: 12px; color: var(--secondary-text-color); }
        .grille-conteneur { padding: 0 12px 12px; }
        .jours-entete { display: flex; margin-left: 34px; }
        .jours-entete div {
          flex: 1; text-align: center; font-size: 12px; font-weight: 500;
          color: var(--secondary-text-color); padding: 4px 0;
        }
        .jours-entete div.vac { color: var(--primary-color); }
        .corps { display: flex; }
        .heures { width: 34px; position: relative; height: ${H_TOTAL}px; flex: none; }
        .heures div {
          position: absolute; right: 6px; transform: translateY(-50%);
          font-size: 12px; color: var(--secondary-text-color);
        }
        .cols {
          flex: 1; display: flex; position: relative; height: ${H_TOTAL}px;
          border-top: 1px solid var(--divider-color);
          background:
            repeating-linear-gradient(
              to bottom,
              transparent 0, transparent ${H_HEURE - 1}px,
              var(--divider-color) ${H_HEURE - 1}px, var(--divider-color) ${H_HEURE}px
            );
        }
        .col { flex: 1; position: relative; border-right: 1px solid var(--divider-color); }
        .col:first-child { border-left: 1px solid var(--divider-color); }
        .col.vac { background: rgba(120, 120, 200, 0.06); }
        .seg {
          position: absolute; left: 2%; width: 96%; border-radius: 6px;
          box-sizing: border-box; overflow: hidden; cursor: grab;
          color: #fff; font-size: 12px; line-height: 1.25;
          padding: 3px 4px; user-select: none; -webkit-user-select: none;
          touch-action: none;
        }
        .seg.inactive { opacity: 0.35; border: 1px dashed rgba(255,255,255,0.9); }
        .seg.etiq {
          background-image: repeating-linear-gradient(
            135deg, rgba(255,255,255,0.30) 0 5px, transparent 5px 11px);
        }
        .seg.etiq.encre-claire {
          background-image: repeating-linear-gradient(
            135deg, rgba(0,0,0,0.18) 0 5px, transparent 5px 11px);
        }
        .suggestion { font-size: 12px; color: var(--secondary-text-color); margin-top: 4px; }
        .seg .nom { font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
        .seg .h { opacity: 0.9; white-space: nowrap; }
        .seg.sans-haut { border-top-left-radius: 0; border-top-right-radius: 0; }
        .seg.sans-bas { border-bottom-left-radius: 0; border-bottom-right-radius: 0; }
        .poignee { position: absolute; left: 0; right: 0; height: 7px; cursor: ns-resize; }
        .poignee.haut { top: 0; }
        .poignee.bas { bottom: 0; }
        .maintenant {
          position: absolute; left: 0; right: 0; height: 0;
          border-top: 2px solid var(--error-color, #e5405e); z-index: 5; pointer-events: none;
        }
        .fantome {
          position: absolute; left: 2%; width: 96%; border-radius: 6px;
          background: var(--primary-color); opacity: 0.45; pointer-events: none; z-index: 6;
        }
        .voile {
          position: absolute; inset: 0; background: rgba(0,0,0,0.45);
          display: flex; align-items: center; justify-content: center; z-index: 10;
        }
        .editeur {
          background: var(--card-background-color, #fff);
          color: var(--primary-text-color);
          border-radius: 12px; padding: 16px; width: min(340px, 92%);
          box-shadow: 0 8px 32px rgba(0,0,0,0.35);
          max-height: 92%; overflow-y: auto; box-sizing: border-box;
        }
        .editeur h3 { margin: 0 0 12px; font-size: 1.05em; }
        .champ { margin-bottom: 12px; }
        .champ label { display: block; font-size: 12px; color: var(--secondary-text-color); margin-bottom: 4px; }
        .champ input[type="text"] {
          width: 100%; box-sizing: border-box; padding: 8px;
          border: 1px solid var(--divider-color); border-radius: 8px;
          background: var(--card-background-color); color: var(--primary-text-color);
          font-size: 14px;
        }
        .lignes-h { display: flex; gap: 10px; }
        .lignes-h .champ { flex: 1; }
        .champ input[type="time"] {
          width: 100%; box-sizing: border-box; padding: 7px;
          border: 1px solid var(--divider-color); border-radius: 8px;
          background: var(--card-background-color); color: var(--primary-text-color);
          font-size: 14px;
        }
        .chips { display: flex; flex-wrap: wrap; gap: 6px; }
        .chip {
          border: 1px solid var(--divider-color); border-radius: 14px;
          padding: 5px 10px; font-size: 12px; cursor: pointer;
          background: none; color: var(--primary-text-color);
        }
        .chip.on { background: var(--primary-color); color: var(--text-primary-color, #fff); border-color: var(--primary-color); }
        .nuancier { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
        .nuance {
          width: 26px; height: 26px; border-radius: 50%; cursor: pointer;
          border: 2px solid transparent; box-sizing: border-box; padding: 0;
        }
        .nuance.on { border-color: var(--primary-text-color); box-shadow: 0 0 0 2px var(--card-background-color) inset; }
        .nuance.auto {
          background: conic-gradient(#e5405e, #f59e0b, #10b981, #3b82f6, #8b5cf6, #e5405e);
          position: relative;
        }
        input[type="color"].nuance { border: 2px solid var(--divider-color); }
        .interrupteur { display: flex; align-items: center; gap: 8px; font-size: 13px; cursor: pointer; }
        .boutons { display: flex; gap: 8px; margin-top: 14px; }
        .boutons button {
          border: none; border-radius: 8px; padding: 9px 14px; font-size: 13px;
          cursor: pointer; font-weight: 500;
        }
        .b-ok { background: var(--primary-color); color: var(--text-primary-color, #fff); flex: 1; }
        .b-suppr { background: var(--error-color, #b3261e); color: #fff; }
        .b-annul { background: none; color: var(--primary-text-color); border: 1px solid var(--divider-color) !important; }
        .toast {
          position: absolute; bottom: 10px; left: 50%; transform: translateX(-50%);
          background: var(--error-color, #b3261e); color: #fff; padding: 8px 14px;
          border-radius: 8px; font-size: 12px; z-index: 20; max-width: 90%;
        }
      </style>`;
    }

    _render() {
      this._langue = LANGUE;
      const titre =
        (this._config || {}).title ||
        tr("titre", { pc: this._pc() || "?" });
      let heures = "";
      for (let h = 2; h < 24; h += 2) {
        heures += `<div style="top:${s2y(h * 3600)}px">${echap(trDate(new Date(2000, 0, 1, h), { hour: "2-digit" }))}</div>`;
      }
      const liste = this._vueEffective() === "liste";
      let entetes = "";
      let cols = "";
      for (let d = 0; d < 7; d++) {
        entetes += `<div>${echap(tr(JOURS_COURTS[d]))}</div>`;
        cols += `<div class="col" data-jour="${d}"></div>`;
      }
      this.shadowRoot.innerHTML = `
        ${this._style()}
        <ha-card>
          <div class="entete">
            <div class="titre">${titre}</div>
            <div class="vues" role="group" aria-label="${echap(tr("vue_choix"))}">
              <button class="vue" data-vue="liste" aria-pressed="${liste}">${echap(tr("vue_liste"))}</button>
              <button class="vue" data-vue="grille" aria-pressed="${!liste}">${echap(tr("vue_grille"))}</button>
            </div>
            <button class="btn-plus" id="plus">${echap(tr("plus_plage"))}</button>
          </div>
          <div class="aide">${echap(tr(liste ? "aide_liste" : "aide"))}</div>
          ${liste ? this._liste() : `<div class="grille-conteneur">
            <div class="jours-entete">${entetes}</div>
            <div class="corps">
              <div class="heures">${heures}</div>
              <div class="cols" id="cols">${cols}</div>
            </div>
          </div>${this._legende()}`}
          <div id="zone-editeur"></div>
          <div id="zone-toast"></div>
        </ha-card>`;
      this._rendered = true;
      this._renderSegs();
      this._ligneMaintenant();
      const cols_el = this.shadowRoot.getElementById("cols");
      if (cols_el) cols_el.addEventListener("pointerdown", (ev) => this._pointerDown(ev));
      this.shadowRoot.querySelectorAll("[data-vue]").forEach((b) => b.addEventListener("click", () => {
        this._vue = b.dataset.vue;
        this._render();
        const n = this.shadowRoot.querySelector(`[data-vue="${b.dataset.vue}"]`);
        if (n) n.focus();
      }));
      this.shadowRoot.querySelectorAll("[data-plage]").forEach((b) => b.addEventListener("click", () => {
        const p = this._plages().find((x) => String(x.id) === b.dataset.plage);
        if (p) this._ouvrirEditeur(Object.assign({}, p, { jours: p.jours.slice(), etiquettes: p.etiquettes.slice(),
                                                         elements: p.elements.slice() }));
      }));
      this.shadowRoot.getElementById("plus").addEventListener("click", () => {
        this._ouvrirEditeur({
          id: null,
          nom: tr("nouvelle_plage"),
          debut: 17 * 3600,
          fin: 19 * 3600,
          jours: [true, true, true, true, true, true, true],
          active: true,
          portee: "session",
          etiquettes: [],
          elements: [],
          sauf: [],
          couleur: null,
        });
      });
    }

    /* Liste par jour (lot 6, HA-21) : une ligne par plage, touchée pour l'éditer ; lundi d'abord. */
    _liste() {
      const segs = this._segments();
      let html = '<div class="liste">';
      for (let d = 0; d < 7; d++) {
        const jour = trDate(new Date(2024, 0, 1 + d), { weekday: "long" });  // le 1er janvier 2024 est un lundi
        const lignes = segs.filter((x) => x.jour === d).sort((a, b) => a.de - b.de);
        html += `<section class="jour"><h3>${echap(jour)}</h3>`;
        if (!lignes.length) html += `<div class="vide">${echap(tr("aucune_plage_jour"))}</div>`;
        for (const x of lignes) {
          const sous = x.p.portee === "etiquettes" ? cibles(x.p) : "";
          html += `<button class="ligne${x.p.active ? "" : " inactive"}" data-plage="${echap(String(x.p.id))}">
            <span class="pastille" style="background:${echap(x.couleur)}"></span>
            <span class="txt"><span class="nom">${echap(x.p.nom) || echap(tr("plage_defaut"))}${x.p.active ? "" : ` · ${echap(tr("plage_inactive"))}`}</span>
              <span class="h">${hLisible(x.de)} → ${hLisible(x.a % JOUR_S)}</span>
              ${sous ? `<span class="h">${echap(sous)}</span>` : ""}</span>
          </button>`;
        }
        html += "</section>";
      }
      return html + "</div>";
    }

    /* Sous la grille : une ligne par plage (nom complet, horaires, jours), qui ouvre l'éditeur. */
    _legende() {
      const plages = this._plages();
      if (!plages.length) return "";
      const jours = (p) => p.jours.map((j, d) => (j ? tr(JOURS_COURTS[d]) : "")).filter(Boolean).join(" ");
      return `<div class="liste legende">${plages.map((p) => {
        const sous = p.portee === "etiquettes" ? cibles(p) : "";
        return `<button class="ligne${p.active ? "" : " inactive"}" data-plage="${echap(String(p.id))}">
          <span class="pastille" style="background:${echap(p.couleur || couleurDefaut(p.id))}"></span>
          <span class="txt"><span class="nom">${echap(p.nom) || echap(tr("plage_defaut"))}${p.active ? "" : ` · ${echap(tr("plage_inactive"))}`}</span>
            <span class="h">${hLisible(p.debut)} → ${hLisible(p.fin)} · ${echap(jours(p))}</span>
            ${sous ? `<span class="h">${echap(sous)}</span>` : ""}</span>
        </button>`;
      }).join("")}</div>`;
    }

    _renderSegs(surcharge) {
      const cols = this.shadowRoot.getElementById("cols");
      if (!cols) return;
      cols.querySelectorAll(".seg").forEach((n) => n.remove());
      const segs = this._voies(this._segments(surcharge));
      for (const s of segs) {
        const col = cols.querySelector(`.col[data-jour="${s.jour}"]`);
        if (!col) continue;
        const el = document.createElement("div");
        el.className =
          "seg" +
          (s.p.active ? "" : " inactive") +
          (s.p.portee === "etiquettes" ? " etiq" : "") +
          (s.bordHaut ? "" : " sans-haut") +
          (s.bordBas ? "" : " sans-bas");
        const nV = s.nVoies || 1;
        const largeur = 96 / nV;
        el.style.left = 2 + largeur * (s.voie || 0) + "%";
        el.style.width = largeur + "%";
        el.style.top = s2y(s.de) + "px";
        el.style.height = Math.max(6, s2y(s.a - s.de) - 2) + "px";
        el.style.background = s.couleur;
        el.style.color = encreSur(s.couleur);
        if (el.style.color === "rgb(255, 255, 255)") el.classList.add("encre-claire");
        el.dataset.id = s.p.id;
        el.dataset.bordHaut = s.bordHaut ? "1" : "0";
        el.dataset.bordBas = s.bordBas ? "1" : "0";
        // Lot 6 : pas de texte rogné dans un bloc étroit — le nom complet est dans l'étiquette du bloc et dans la
        // légende sous la grille (une ligne par plage, qui ouvre l'éditeur).
        const etiquette = `${s.p.nom || tr("plage_defaut")}, ${hLisible(s.p.debut)} → ${hLisible(s.p.fin)}`;
        el.title = etiquette;
        el.setAttribute("aria-label", etiquette);
        el.innerHTML =
          (s.bordHaut ? '<div class="poignee haut"></div>' : "") +
          (s.bordBas ? '<div class="poignee bas"></div>' : "");
        col.appendChild(el);
      }
    }

    _ligneMaintenant() {
      const cols = this.shadowRoot.getElementById("cols");
      if (!cols) return;
      const now = new Date();
      const jour = (now.getDay() + 6) % 7; // lundi = 0
      const s = now.getHours() * 3600 + now.getMinutes() * 60;
      const col = cols.querySelector(`.col[data-jour="${jour}"]`);
      if (!col) return;
      const l = document.createElement("div");
      l.className = "maintenant";
      l.style.top = s2y(s) + "px";
      col.appendChild(l);
    }

    _toast(msg) {
      const zone = this.shadowRoot.getElementById("zone-toast");
      if (!zone) return;
      zone.innerHTML = `<div class="toast">${echap(msg)}</div>`;
      clearTimeout(this._toastT);
      this._toastT = setTimeout(() => (zone.innerHTML = ""), 5000);
    }

    /* --------------------------- interactions ----------------------------- */

    _pointerDown(ev) {
      if (this._sel) return;
      const seg = ev.target.closest(".seg");
      const col = ev.target.closest(".col");
      if (!seg && !col) return;
      ev.preventDefault();
      const cols = this.shadowRoot.getElementById("cols");
      const rect = cols.getBoundingClientRect();
      const ySec = (ev.clientY - rect.top) / H_HEURE * 3600;

      if (seg) {
        const id = parseInt(seg.dataset.id, 10);
        const p = this._plages().find((x) => x.id === id);
        if (!p) return;
        const r = seg.getBoundingClientRect();
        let zone = "deplacer";
        if (ev.target.classList.contains("poignee")) {
          zone = ev.target.classList.contains("haut") ? "debut" : "fin";
        } else if (ev.clientY - r.top < 8 && seg.dataset.bordHaut === "1") {
          zone = "debut";
        } else if (r.bottom - ev.clientY < 8 && seg.dataset.bordBas === "1") {
          zone = "fin";
        }
        this._drag = {
          type: zone, p, ySec, bouge: false,
          debut: p.debut, fin: p.fin,
        };
      } else {
        if (ev.pointerType !== "mouse") return; // au doigt : bouton + Plage
        const jour = parseInt(col.dataset.jour, 10);
        this._drag = {
          type: "creer", jour, ySec, bouge: false,
          de: clamp(snap(ySec), 0, JOUR_S - SNAP), a: null, col,
        };
      }
      this._surMove = (e) => this._pointerMove(e, rect);
      this._surUp = (e) => this._pointerUp(e);
      window.addEventListener("pointermove", this._surMove);
      window.addEventListener("pointerup", this._surUp, { once: true });
    }

    _pointerMove(ev, rect) {
      const d = this._drag;
      if (!d) return;
      const ySec = (ev.clientY - rect.top) / H_HEURE * 3600;
      const delta = ySec - d.ySec;
      if (Math.abs(delta) > 600) d.bouge = true; // ~ 10 minutes = seuil de clic
      if (!d.bouge) return;

      if (d.type === "creer") {
        const a = clamp(snap(ySec), 0, JOUR_S);
        d.a = a;
        let de = d.de;
        let fin = a;
        if (fin < de) [de, fin] = [fin, de];
        if (fin - de < SNAP) fin = de + SNAP;
        let f = d.col.querySelector(".fantome");
        if (!f) {
          f = document.createElement("div");
          f.className = "fantome";
          d.col.appendChild(f);
        }
        f.style.top = s2y(de) + "px";
        f.style.height = s2y(fin - de) + "px";
        d.resultat = { de, fin };
        return;
      }

      const p = d.p;
      const nuit = d.fin <= d.debut;
      let debut = d.debut;
      let fin = d.fin;
      if (d.type === "deplacer") {
        let dd = snap(delta);
        if (!nuit) dd = clamp(dd, -d.debut, JOUR_S - d.fin);
        debut = (d.debut + dd + JOUR_S) % JOUR_S;
        fin = (d.fin + dd + JOUR_S) % JOUR_S;
      } else if (d.type === "debut") {
        debut = snap(d.debut + delta);
        debut = nuit
          ? clamp(debut, d.fin + SNAP, JOUR_S - SNAP)
          : clamp(debut, 0, d.fin - SNAP);
      } else if (d.type === "fin") {
        fin = snap(d.fin + delta);
        fin = nuit
          ? clamp(fin, SNAP, d.debut - SNAP)
          : clamp(fin, d.debut + SNAP, JOUR_S);
      }
      d.apercu = { id: p.id, debut, fin: fin === JOUR_S ? 0 : fin };
      if (fin === JOUR_S && debut === 0) d.apercu.fin = JOUR_S - SNAP; // garde-fou
      if (!d._rafPrevu) {
        d._rafPrevu = true;
        requestAnimationFrame(() => {
          d._rafPrevu = false;
          if (this._drag === d && d.apercu) this._renderSegs(d.apercu);
        });
      }
    }

    _pointerUp() {
      const d = this._drag;
      window.removeEventListener("pointermove", this._surMove);
      this._drag = null;
      if (!d) return;

      if (d.type === "creer") {
        const f = d.col && d.col.querySelector(".fantome");
        if (f) f.remove();
        if (!d.bouge || !d.resultat) return;
        const jours = [false, false, false, false, false, false, false];
        jours[d.jour] = true;
        this._ouvrirEditeur({
          id: null,
          nom: tr("nouvelle_plage"),
          debut: d.resultat.de,
          fin: d.resultat.fin === JOUR_S ? 0 : d.resultat.fin,
          jours,
          active: true,
          portee: "session",
          etiquettes: [],
          elements: [],
          couleur: null,
        });
        return;
      }

      if (!d.bouge) {
        this._ouvrirEditeur(
          Object.assign({}, d.p, {
            jours: d.p.jours.slice(),
            etiquettes: (d.p.etiquettes || []).slice(),
            sauf: (d.p.sauf || []).slice(),
            elements: (d.p.elements || []).map((e) => ({
              genre: e.genre,
              nom: e.nom,
            })),
          })
        );
        return;
      }
      if (!d.apercu) return;
      this._svc("update_schedule", {
        pc: this._pc(),
        id: d.p.id,
        debut: hService(d.apercu.debut),
        fin: hService(d.apercu.fin),
      });
    }

    async _svc(nom, data) {
      this._geste.debut();
      try {
        await this._hass.callService("pc_parental", nom, data);
        this._geste.ok(tr("enregistre"));
      } catch (e) {
        this._geste.echec((e && e.message) || tr("erreur_svc"));
        this._render();
      }
    }

    /* ------------------------------ éditeur -------------------------------- */

    _ouvrirEditeur(p) {
      this._sel = p;
      const zone = this.shadowRoot.getElementById("zone-editeur");
      const neuf = p.id === null;
      const couleurCourante = p.couleur || (neuf ? PALETTE[0] : couleurDefaut(p.id));
      let nuancier = `<button class="nuance auto ${p.couleur ? "" : "on"}" data-c="" title="${echap(tr("couleur_auto"))}"></button>`;
      for (const c of PALETTE) {
        nuancier += `<button class="nuance ${p.couleur === c ? "on" : ""}" data-c="${c}" style="background:${c}"></button>`;
      }
      nuancier += `<input type="color" class="nuance" id="ed-cpick" value="${couleurCourante}" title="${echap(tr("autre_couleur"))}">`;
      let chips = "";
      for (let d = 0; d < 7; d++) {
        chips += `<button class="chip ${p.jours[d] ? "on" : ""}" data-j="${d}">${echap(tr(JOURS_COURTS[d]))}</button>`;
      }
      const vocab = this._vocabulaire();
      zone.innerHTML = `
        <div class="voile" id="ed-voile">
          <div class="editeur">
            <h3>${echap(neuf ? tr("nouvelle_plage") : tr("modifier_plage"))}</h3>
            <div class="champ"><label>${echap(tr("nom"))}</label>
              <input type="text" id="ed-nom" value="${echap(p.nom)}" maxlength="40"></div>
            <div class="lignes-h">
              <div class="champ"><label>${echap(tr("debut"))}</label><input type="time" id="ed-debut" step="900" value="${hVersInput(p.debut)}"></div>
              <div class="champ"><label>${echap(tr("fin"))}</label><input type="time" id="ed-fin" step="900" value="${hVersInput(p.fin)}"></div>
            </div>
            <div class="champ"><label>${echap(tr("jours"))}</label><div class="chips" id="ed-jours">${chips}</div></div>
            <div class="champ"><label>${echap(tr("plage_ferme"))}</label><vision-choix id="ed-portee" titre="${echap(tr("plage_ferme"))}"
              valeur="${p.portee === "etiquettes" ? "etiquettes" : "session"}" options="${echap(JSON.stringify([
                { cle: "session", libelle: tr("portee_session") },
                { cle: "etiquettes", libelle: tr("portee_etiquettes") },
              ]))}"></vision-choix></div>
            <div class="champ" id="ed-zone-etiq" ${p.portee === "etiquettes" ? "" : "hidden"}>
              <label>${echap(tr("etiquettes_a_fermer"))}</label>
              ${this._choixEtiq()}
              ${vocab.length ? "" : `<div class="suggestion">${echap(tr("aucune_etiquette"))}</div>`}
              <label style="margin-top:10px">${echap(tr("viser_element"))}</label>
              <div class="chips" id="ed-els">${this._chipsEls()}</div>
              <div class="lignes-h">
                <input type="search" id="ed-el-filtre" placeholder="${echap(tr("filtrer"))}">
                <select id="ed-el-choix"></select>
                <button class="chip" id="ed-el-ajout" type="button">${echap(tr("ajouter"))}</button>
              </div>
              <div class="suggestion">${echap(tr("site_l_emporte"))}</div>
            </div>
            ${this._appareils().length > 1 ? `<div class="champ"><label>${echap(tr("sauf_sur"))}</label>
              <vision-choix id="ed-sauf" multiple titre="${echap(tr("sauf_sur"))}" valeurs="${echap(JSON.stringify(p.sauf || []))}"
                options="${echap(JSON.stringify(this._appareils().map((a) => ({ cle: a.id, libelle: a.nom }))))}"></vision-choix>
              <div class="suggestion">${echap(tr("sauf_aide"))}</div></div>` : ""}
            <div class="champ"><label>${echap(tr("couleur"))}</label><div class="nuancier" id="ed-couleurs">${nuancier}</div></div>
            <div class="champ"><label class="interrupteur">
              <input type="checkbox" id="ed-active" ${p.active ? "checked" : ""}> ${echap(tr("plage_active"))}</label></div>
            <div class="boutons">
              <button class="b-ok" id="ed-ok">${echap(tr("enregistrer"))}</button>
              ${neuf ? "" : `<button class="b-suppr" id="ed-suppr">${echap(tr("supprimer"))}</button>`}
              <button class="b-annul" id="ed-annul">${echap(tr("annuler"))}</button>
            </div>
          </div>
        </div>`;

      const $ = (id) => zone.querySelector("#" + id);
      zone.querySelector("#ed-voile").addEventListener("pointerdown", (e) => {
        if (e.target.id === "ed-voile") this._fermerEditeur();
      });
      $("ed-annul").addEventListener("click", () => this._fermerEditeur());
      $("ed-jours").addEventListener("click", (e) => {
        const chip = e.target.closest(".chip");
        if (!chip) return;
        const j = parseInt(chip.dataset.j, 10);
        this._sel.jours[j] = !this._sel.jours[j];
        chip.classList.toggle("on", this._sel.jours[j]);
      });
      $("ed-portee").addEventListener("vision-choix", (e) => {
        this._sel.portee = e.detail.valeur;
        zone.querySelector("#ed-zone-etiq").hidden =
          this._sel.portee !== "etiquettes";
      });
      $("ed-couleurs").addEventListener("click", (e) => {
        const b = e.target.closest("button.nuance");
        if (!b) return;
        this._sel.couleur = b.dataset.c || null;
        zone.querySelectorAll(".nuance").forEach((n) => n.classList.remove("on"));
        b.classList.add("on");
      });
      $("ed-cpick").addEventListener("input", (e) => {
        this._sel.couleur = e.target.value;
        zone.querySelectorAll(".nuance").forEach((n) => n.classList.remove("on"));
        e.target.classList.add("on");
      });
      const zSauf = zone.querySelector("#ed-sauf");
      if (zSauf) {
        const connus = this._appareils().map((a) => String(a.id));
        zSauf.addEventListener("vision-choix", (e) => {
          this._sel.sauf = fusionnerChoix(this._sel.sauf, e.detail.valeurs, connus, String);
        });
      }
      const zEtiq = zone.querySelector("#ed-etiq");
      if (zEtiq) {
        const connues = this._vocabulaire();
        const bas = (x) => String(x).toLowerCase();
        zEtiq.addEventListener("vision-choix", (e) => {
          this._sel.etiquettes = fusionnerChoix(this._sel.etiquettes, e.detail.valeurs, connues, bas);
        });
      }
      const zEls = zone.querySelector("#ed-els");
      if (zEls) {
        zEls.addEventListener("click", (e) => {
          const b = e.target.closest(".chip");
          if (!b || b.dataset.el === undefined) return;
          this._sel.elements.splice(parseInt(b.dataset.el, 10), 1);
          this._majEls();
        });
      }
      const filtre = zone.querySelector("#ed-el-filtre");
      if (filtre) {
        filtre.addEventListener("input", () => {
          this._filtreEl = filtre.value;
          const choix = zone.querySelector("#ed-el-choix");
          if (choix) choix.innerHTML = this._optionsEls();
        });
      }
      const ajout = zone.querySelector("#ed-el-ajout");
      if (ajout) {
        ajout.addEventListener("click", () => {
          const choix = zone.querySelector("#ed-el-choix");
          const v = choix ? choix.value : "";
          const i = v.indexOf("|");
          if (i < 0) return;
          this._sel.elements = this._sel.elements || [];
          this._sel.elements.push({
            genre: v.slice(0, i),
            nom: v.slice(i + 1),
          });
          this._majEls();
        });
      }
      // Le catalogue arrive apres coup : la liste se remplit toute seule.
      this._catalogue().then(() => this._majEls());

      $("ed-ok").addEventListener("click", () => this._enregistrer());
      if (!neuf) {
        $("ed-suppr").addEventListener("click", () => {
          this._svc("remove_schedule", { pc: this._pc(), id: this._sel.id });
          this._fermerEditeur();
        });
      }
    }


    /* --- choisir plutot que taper : pastilles et liste deroulante ------- */

    // Les étiquettes à fermer : un choix multiple (vision-choix) ; la casse ne compte pas.
    _choixEtiq() {
      const vocab = this._vocabulaire();
      if (!vocab.length) return "";
      const prises = (this._sel.etiquettes || []).map((e) => e.toLowerCase());
      const valeurs = vocab.filter((nom) => prises.includes(nom.toLowerCase()));
      return `<vision-choix id="ed-etiq" multiple titre="${echap(tr("etiquettes_a_fermer"))}" valeurs="${echap(JSON.stringify(valeurs))}"
        options="${echap(JSON.stringify(vocab.map((nom) => ({ cle: nom, libelle: trCat(nom) }))))}"></vision-choix>`;
    }

    _chipsEls() {
      const els = this._sel.elements || [];
      if (!els.length) {
        return `<span class="suggestion">${echap(tr("aucun_nom_vise"))}</span>`;
      }
      return els
        .map(
          (e, i) =>
            `<button class="chip on" data-el="${i}" type="button" title="${echap(tr("retirer"))}">${echap(e.nom)} ×</button>`
        )
        .join("");
    }

    _optionsEls() {
      const cat = this._cat || [];
      const q = (this._filtreEl || "").trim().toLowerCase();
      const deja = new Set(
        (this._sel.elements || []).map((e) => e.genre + "|" + e.nom.toLowerCase())
      );
      const groupe = (genre, titre) => {
        const lignes = cat
          .filter((e) => e.genre === genre)
          .filter((e) => !deja.has(e.genre + "|" + e.nom.toLowerCase()))
          .filter((e) => !q || e.nom.toLowerCase().includes(q))
          .slice(0, 300)
          .map(
            (e) =>
              `<option value="${echap(e.genre + "|" + e.nom)}">${echap(
                e.nom
              )}</option>`
          )
          .join("");
        return lignes ? `<optgroup label="${echap(titre)}">${lignes}</optgroup>` : "";
      };
      const corps = groupe("sites", tr("groupe_sites")) + groupe("apps", tr("groupe_logiciels"));
      return corps || `<option value="">${echap(tr("rien_a_proposer"))}</option>`;
    }

    _majEls() {
      const zone = this.shadowRoot.getElementById("zone-editeur");
      if (!zone || !this._sel) return;
      const choix = zone.querySelector("#ed-el-choix");
      const chips = zone.querySelector("#ed-els");
      if (choix) choix.innerHTML = this._optionsEls();
      if (chips) chips.innerHTML = this._chipsEls();
    }

    _fermerEditeur() {
      this._sel = null;
      const zone = this.shadowRoot.getElementById("zone-editeur");
      if (zone) zone.innerHTML = "";
      this._render();
    }

    _enregistrer() {
      const zone = this.shadowRoot.getElementById("zone-editeur");
      const $ = (id) => zone.querySelector("#" + id);
      const p = this._sel;
      const nom = ($("ed-nom").value || "").trim() || tr("plage_defaut");
      const debut = hDepuisInput($("ed-debut").value);
      const fin = hDepuisInput($("ed-fin").value);
      if (debut === null || fin === null) {
        this._toast(tr("heures_incompletes"));
        return;
      }
      if (debut === fin) {
        this._toast(tr("debut_fin_differents"));
        return;
      }
      if (!p.jours.some(Boolean)) {
        this._toast(tr("cocher_jour"));
        return;
      }
      const jours = JOURS_SVC.filter((_, i) => p.jours[i]);
      const portee = p.portee === "etiquettes" ? "etiquettes" : "session";
      const etiquettes = (p.etiquettes || []).slice();
      const elements = (p.elements || []).map((e) => ({
        genre: e.genre,
        nom: e.nom,
      }));
      if (portee === "etiquettes" && !etiquettes.length && !elements.length) {
        this._toast(tr("choisir_etiquette"));
        return;
      }
      const donnees = {
        pc: this._pc(),
        nom,
        debut: hService(debut),
        fin: hService(fin),
        jours,
        active: $("ed-active").checked,
        portee,
        etiquettes: portee === "etiquettes" ? etiquettes : [],
        elements: portee === "etiquettes" ? elements : [],
        sauf: (p.sauf || []).slice(),
      };
      if (p.id === null) {
        if (p.couleur) donnees.couleur = p.couleur;
        this._svc("add_schedule", donnees);
      } else {
        donnees.id = p.id;
        donnees.couleur = p.couleur || "";
        this._svc("update_schedule", donnees);
      }
      this._fermerEditeur();
    }
  }

  // Un seul exemplaire, même si une ancienne copie (/local) est encore chargée à côté.
  const _dejaVisionPlanningCard = !!customElements.get("vision-planning-card");
  if (!_dejaVisionPlanningCard) customElements.define("vision-planning-card", VisionPlanningCard);
  // Ancien nom, pour les tableaux de bord écrits avant : la même carte.
  if (!customElements.get("comvision-planning-card")) customElements.define("comvision-planning-card", class extends VisionPlanningCard {});
  window.customCards = window.customCards || [];
  if (!_dejaVisionPlanningCard) window.customCards.push({
    type: "vision-planning-card",
    name: tr("carte_nom"),
    description: tr("carte_description"),
  });
})();
