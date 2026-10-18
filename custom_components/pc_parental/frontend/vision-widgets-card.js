/*
 * Vision — carte « Widgets HA » (1.94.0, point 16.1), réservée aux administrateurs.
 *
 * Ce que chaque personne voit de Home Assistant dans Vision (accueil, widgets du téléphone) : ses entités,
 * en lecture ou pilotables, et les exceptions d'un appareil (en plus, en moins). Les mêmes réglages que
 * Configurer → Entités Home Assistant, qui reste un accès secondaire.
 */
// ---- i18n:debut — les textes de cette carte, dans les six langues de Vision (fr source, en repli) ----
const TEXTES = /*i18n-json*/{
  "fr": {
    "service_turn_on": "Allumer", "service_turn_off": "Éteindre", "service_toggle": "Basculer", "service_press": "Appuyer",
    "service_open_cover": "Ouvrir", "service_close_cover": "Fermer", "service_stop_cover": "Arrêter",
    "service_set_temperature": "Température", "service_set_hvac_mode": "Mode", "service_select_option": "Choisir",
    "service_set_value": "Valeur", "service_media_play_pause": "Lecture / pause", "service_media_next_track": "Suivant",
    "service_media_previous_track": "Précédent", "service_volume_set": "Volume", "service_trigger": "Déclencher",
    "erreur_admin": "Réservé aux administrateurs de Home Assistant.",
    "erreur_loading": "Vision démarre : réessaie dans un instant.",
    "erreur_pc": "Cet appareil n'existe plus dans Vision.",
    "erreur_json": "La demande n'a pas pu être lue : réessaie.",
    "erreur_module": "Cette fonction est désactivée dans Vision.",
    "erreur_minutes": "Indique une durée.",
    "erreur_defaut": "Ce n'est pas passé : rien n'a changé, réessaie dans un instant.",
    "geste_en_cours": "En cours…",
    "geste_fait": "C'est fait.",
    "erreur_injoignable": "Home Assistant ne répond pas ; réessaie dans un instant.",
    "enregistre": "Enregistré : les appareils le voient au prochain relevé.",
    "echec": "Le réglage n'a pas été enregistré ; réessaie.",
    "pilotable": "Pilotable : {services}", "lecture_seule": "Lecture seule", "action": "Action", "lecture": "Lecture",
    "introuvable": "introuvable", "retirer": "Retirer", "retirer_nom": "Retirer {nom}",
    "suggestions": "Entités de Home Assistant", "aucune_suggestion": "Aucune entité ne correspond.",
    "mep": "Mise en page", "mep_h": "Alignement horizontal", "mep_v": "Alignement vertical", "mep_taille": "Taille de la valeur", "mep_h_gauche": "À gauche", "mep_h_centre": "Au centre", "mep_h_droite": "À droite", "mep_v_haut": "En haut", "mep_v_centre": "Au milieu", "mep_v_bas": "En bas", "mep_t_remplir": "Remplir", "mep_t_s": "S", "mep_t_m": "M", "mep_t_l": "L", "mep_t_xl": "XL", "mep_titre": "Montrer le titre", "mep_icone": "Montrer l'icône", "mep_aide": "La même mise en page sur tous les appareils ; un {decideur} peut aussi la régler depuis son téléphone. « Remplir » : la plus grande valeur qui tient.", "tous_titre": "Pour tout le monde", "tous_aide": "Vues sur tous les appareils, en plus de la liste de chaque personne.", "ajout_tous": "+ Ajouter pour tout le monde", "regler": "Régler", "regler_nom": "Régler {nom}", "role": "Rôle", "role_lecture": "Lecture", "role_interrupteur": "Interrupteur", "role_bouton": "Bouton", "role_reglage": "Réglage", "qui": "Qui peut agir", "qui_decideurs": "Seulement les {decideurs}", "qui_tous": "Tout le monde", "confirmer": "Demander confirmation avant d'agir", "nom_affiche": "Nom affiché", "icone": "Icône (mdi:…)", "texte": "Ligne sous la valeur (entité)", "texte_aide": "Une entité dont l'état s'écrit sous la valeur, par exemple le mode et l'horaire du chauffage. Vide : rien.", "affichage": "Affichage", "aff_valeur": "Valeur seule", "aff_detail": "Détail", "aff_valeur_detail": "Valeur + détail", "attributs": "Attributs du détail", "attributs_aide": "Aucun coché : tous les attributs.", "liste_aide": "Le détail montre les éléments à faire de la liste.", "enregistrer": "Enregistrer", "fermer": "Fermer", "badge_tous": "tout le monde", "badge_confirmer": "avec confirmation", "toucher": "Au toucher", "toucher_role": "Action du rôle", "toucher_details": "Ouvrir les détails de l'entité", "toucher_vue": "Ouvrir un tableau de bord ou une vue", "toucher_rien": "Rien", "chemin": "Chemin dans Home Assistant (par exemple /lovelace/0)", "chemin_liste": "Choisir un tableau de bord ou une vue…", "toucher_aide": "Détails et vues : seulement pour qui peut agir sur l'entité ; les autres gardent l'action du rôle.",
    "chargement": "Chargement…", "rien": "Rien pour l'instant.", "ajouter_entite": "+ Ajouter une entité",
    "en_plus": "En plus", "en_moins": "En moins",
    "ajout_plus": "+ En plus sur cet appareil", "ajout_moins": "+ En moins sur cet appareil",
    "module_coupe": "Le module « Entités Home Assistant » est coupé : ces réglages sont gardés et reviennent avec lui (Vision → Réglages → Fonctions).",
    "aucune_personne": "Aucune personne dans Home Assistant.",
    "exceptions": "Exceptions par appareil",
    "titre": "Widgets Home Assistant",
    "aide": "Ce que chaque personne voit et peut piloter dans Vision : accueil, widgets du téléphone.",
    "en_savoir_plus": "En savoir plus",
    "details": "Une entité ajoutée à une personne apparaît sur tous ses appareils ; « Pour tout le monde », sur tous les appareils ; une exception ajoute ou retire une entité sur un seul appareil. « Régler » choisit son rôle (Lecture, Interrupteur, Bouton, Réglage), qui peut agir ({decideurs} seulement, ou tout le monde), la confirmation, le nom, l'icône et l'affichage (valeur ou détail). Serrures et alarmes restent en lecture.",
    "carte_nom": "Vision — Widgets HA",
    "carte_description": "Les entités Home Assistant visibles et pilotables dans Vision, par personne et par appareil."
  },
  "en": {
    "service_turn_on": "Turn on", "service_turn_off": "Turn off", "service_toggle": "Toggle", "service_press": "Press",
    "service_open_cover": "Open", "service_close_cover": "Close", "service_stop_cover": "Stop",
    "service_set_temperature": "Temperature", "service_set_hvac_mode": "Mode", "service_select_option": "Choose",
    "service_set_value": "Value", "service_media_play_pause": "Play / pause", "service_media_next_track": "Next",
    "service_media_previous_track": "Previous", "service_volume_set": "Volume", "service_trigger": "Trigger",
    "erreur_admin": "Only Home Assistant administrators can do this.",
    "erreur_loading": "Vision is starting: try again in a moment.",
    "erreur_pc": "This device no longer exists in Vision.",
    "erreur_json": "The request could not be read: try again.",
    "erreur_module": "This feature is turned off in Vision.",
    "erreur_minutes": "Enter a duration.",
    "erreur_defaut": "That didn't work: nothing changed, try again in a moment.",
    "geste_en_cours": "Working…",
    "geste_fait": "Done.",
    "erreur_injoignable": "Home Assistant isn't responding; try again in a moment.",
    "enregistre": "Saved: devices will see it at their next check-in.",
    "echec": "The setting wasn't saved; try again.",
    "pilotable": "Controllable: {services}", "lecture_seule": "Read only", "action": "Action", "lecture": "Read",
    "introuvable": "not found", "retirer": "Remove", "retirer_nom": "Remove {nom}",
    "suggestions": "Home Assistant entities", "aucune_suggestion": "No entity matches.",
    "mep": "Layout", "mep_h": "Horizontal alignment", "mep_v": "Vertical alignment", "mep_taille": "Value size", "mep_h_gauche": "Left", "mep_h_centre": "Center", "mep_h_droite": "Right", "mep_v_haut": "Top", "mep_v_centre": "Middle", "mep_v_bas": "Bottom", "mep_t_remplir": "Fill", "mep_t_s": "S", "mep_t_m": "M", "mep_t_l": "L", "mep_t_xl": "XL", "mep_titre": "Show the title", "mep_icone": "Show the icon", "mep_aide": "The same layout on every device; a {decideur} can also set it from their phone. “Fill”: the largest value that fits.", "tous_titre": "For everyone", "tous_aide": "Shown on every device, in addition to each person's list.", "ajout_tous": "+ Add for everyone", "regler": "Settings", "regler_nom": "Settings for {nom}", "role": "Role", "role_lecture": "Read", "role_interrupteur": "Switch", "role_bouton": "Button", "role_reglage": "Setting", "qui": "Who can act", "qui_decideurs": "Only {decideurs}", "qui_tous": "Everyone", "confirmer": "Ask for confirmation before acting", "nom_affiche": "Displayed name", "icone": "Icon (mdi:…)", "texte": "Line under the value (entity)", "texte_aide": "An entity whose state is written under the value, for example the heating mode and schedule. Empty: nothing.", "affichage": "Display", "aff_valeur": "Value only", "aff_detail": "Details", "aff_valeur_detail": "Value + details", "attributs": "Attributes in details", "attributs_aide": "None ticked: all attributes.", "liste_aide": "Details show the list's items still to do.", "enregistrer": "Save", "fermer": "Close", "badge_tous": "everyone", "badge_confirmer": "with confirmation", "toucher": "On tap", "toucher_role": "Role action", "toucher_details": "Open the entity's details", "toucher_vue": "Open a dashboard or a view", "toucher_rien": "Nothing", "chemin": "Path in Home Assistant (for example /lovelace/0)", "chemin_liste": "Choose a dashboard or a view…", "toucher_aide": "Details and views: only for those who may act on the entity; others keep the role action.",
    "chargement": "Loading…", "rien": "Nothing yet.", "ajouter_entite": "+ Add an entity",
    "en_plus": "Extra", "en_moins": "Hidden",
    "ajout_plus": "+ Extra on this device", "ajout_moins": "+ Hidden on this device",
    "module_coupe": "The “Home Assistant entities” module is off: these settings are kept and come back with it (Vision → Settings → Features).",
    "aucune_personne": "No people in Home Assistant.",
    "exceptions": "Exceptions per device",
    "titre": "Home Assistant widgets",
    "aide": "What each person sees and can control in Vision: home screen, phone widgets.",
    "en_savoir_plus": "Learn more",
    "details": "An entity added to a person appears on all their devices; “For everyone”, on every device; an exception adds or hides an entity on one device. “Settings” chooses its role (Read, Switch, Button, Setting), who can act ({decideurs} only, or everyone), confirmation, name, icon and display (value or details). Locks and alarms stay read-only.",
    "carte_nom": "Vision — HA widgets",
    "carte_description": "The Home Assistant entities visible and controllable in Vision, per person and per device."
  },
  "de": {
    "service_turn_on": "Einschalten", "service_turn_off": "Ausschalten", "service_toggle": "Umschalten", "service_press": "Drücken",
    "service_open_cover": "Öffnen", "service_close_cover": "Schließen", "service_stop_cover": "Stoppen",
    "service_set_temperature": "Temperatur", "service_set_hvac_mode": "Modus", "service_select_option": "Auswählen",
    "service_set_value": "Wert", "service_media_play_pause": "Wiedergabe / Pause", "service_media_next_track": "Weiter",
    "service_media_previous_track": "Zurück", "service_volume_set": "Lautstärke", "service_trigger": "Auslösen",
    "erreur_admin": "Nur für Home Assistant-Administratoren.",
    "erreur_loading": "Vision startet gerade: versuch es gleich noch einmal.",
    "erreur_pc": "Dieses Gerät gibt es in Vision nicht mehr.",
    "erreur_json": "Die Anfrage konnte nicht gelesen werden: versuch es noch einmal.",
    "erreur_module": "Diese Funktion ist in Vision ausgeschaltet.",
    "erreur_minutes": "Gib eine Dauer an.",
    "erreur_defaut": "Das hat nicht geklappt: nichts wurde geändert, versuch es gleich noch einmal.",
    "geste_en_cours": "Wird ausgeführt…",
    "geste_fait": "Erledigt.",
    "erreur_injoignable": "Home Assistant antwortet nicht; versuch es gleich noch einmal.",
    "enregistre": "Gespeichert: die Geräte sehen es bei der nächsten Abfrage.",
    "echec": "Die Einstellung wurde nicht gespeichert; versuch es noch einmal.",
    "pilotable": "Steuerbar: {services}", "lecture_seule": "Nur lesen", "action": "Aktion", "lecture": "Lesen",
    "introuvable": "nicht gefunden", "retirer": "Entfernen", "retirer_nom": "{nom} entfernen",
    "suggestions": "Home-Assistant-Entitäten", "aucune_suggestion": "Keine passende Entität.",
    "mep": "Layout", "mep_h": "Horizontale Ausrichtung", "mep_v": "Vertikale Ausrichtung", "mep_taille": "Größe des Werts", "mep_h_gauche": "Links", "mep_h_centre": "Mittig", "mep_h_droite": "Rechts", "mep_v_haut": "Oben", "mep_v_centre": "Mitte", "mep_v_bas": "Unten", "mep_t_remplir": "Ausfüllen", "mep_t_s": "S", "mep_t_m": "M", "mep_t_l": "L", "mep_t_xl": "XL", "mep_titre": "Titel anzeigen", "mep_icone": "Symbol anzeigen", "mep_aide": "Dasselbe Layout auf allen Geräten; ein {decideur} kann es auch am Handy einstellen. „Ausfüllen“: der größte Wert, der passt.", "tous_titre": "Für alle", "tous_aide": "Auf allen Geräten sichtbar, zusätzlich zur Liste jeder Person.", "ajout_tous": "+ Für alle hinzufügen", "regler": "Einstellen", "regler_nom": "{nom} einstellen", "role": "Rolle", "role_lecture": "Nur lesen", "role_interrupteur": "Schalter", "role_bouton": "Taste", "role_reglage": "Regler", "qui": "Wer darf bedienen", "qui_decideurs": "Nur {decideurs}", "qui_tous": "Alle", "confirmer": "Vor dem Ausführen bestätigen lassen", "nom_affiche": "Angezeigter Name", "icone": "Symbol (mdi:…)", "texte": "Zeile unter dem Wert (Entität)", "texte_aide": "Eine Entität, deren Zustand unter dem Wert steht, zum Beispiel Heizmodus und Zeitplan. Leer: nichts.", "affichage": "Anzeige", "aff_valeur": "Nur Wert", "aff_detail": "Details", "aff_valeur_detail": "Wert + Details", "attributs": "Attribute in den Details", "attributs_aide": "Keines angehakt: alle Attribute.", "liste_aide": "Die Details zeigen die offenen Einträge der Liste.", "enregistrer": "Speichern", "fermer": "Schließen", "badge_tous": "alle", "badge_confirmer": "mit Bestätigung", "toucher": "Beim Antippen", "toucher_role": "Aktion der Rolle", "toucher_details": "Details der Entität öffnen", "toucher_vue": "Ein Dashboard oder eine Ansicht öffnen", "toucher_rien": "Nichts", "chemin": "Pfad in Home Assistant (zum Beispiel /lovelace/0)", "chemin_liste": "Dashboard oder Ansicht wählen…", "toucher_aide": "Details und Ansichten: nur für alle, die die Entität bedienen dürfen; die anderen behalten die Aktion der Rolle.",
    "chargement": "Wird geladen…", "rien": "Noch nichts.", "ajouter_entite": "+ Entität hinzufügen",
    "en_plus": "Zusätzlich", "en_moins": "Ausgeblendet",
    "ajout_plus": "+ Zusätzlich auf diesem Gerät", "ajout_moins": "+ Ausgeblendet auf diesem Gerät",
    "module_coupe": "Das Modul „Home Assistant-Entitäten“ ist ausgeschaltet: diese Einstellungen bleiben erhalten und kommen mit ihm zurück (Vision → Einstellungen → Funktionen).",
    "aucune_personne": "Keine Personen in Home Assistant.",
    "exceptions": "Ausnahmen pro Gerät",
    "titre": "Home Assistant-Widgets",
    "aide": "Was jede Person in Vision sieht und steuern kann: Startbildschirm, Handy-Widgets.",
    "en_savoir_plus": "Mehr erfahren",
    "details": "Eine Entität, die einer Person hinzugefügt wird, erscheint auf allen ihren Geräten; „Für alle“ auf jedem Gerät; eine Ausnahme fügt eine Entität auf einem einzigen Gerät hinzu oder blendet sie aus. „Einstellen“ wählt die Rolle (Nur lesen, Schalter, Taste, Regler), wer bedienen darf (nur {decideurs} oder alle), die Bestätigung, Name, Symbol und Anzeige (Wert oder Details). Schlösser und Alarmanlagen bleiben nur lesbar.",
    "carte_nom": "Vision — HA-Widgets",
    "carte_description": "Die Home Assistant-Entitäten, die in Vision sichtbar und steuerbar sind, pro Person und pro Gerät."
  },
  "es": {
    "service_turn_on": "Encender", "service_turn_off": "Apagar", "service_toggle": "Alternar", "service_press": "Pulsar",
    "service_open_cover": "Abrir", "service_close_cover": "Cerrar", "service_stop_cover": "Detener",
    "service_set_temperature": "Temperatura", "service_set_hvac_mode": "Modo", "service_select_option": "Elegir",
    "service_set_value": "Valor", "service_media_play_pause": "Reproducir / pausa", "service_media_next_track": "Siguiente",
    "service_media_previous_track": "Anterior", "service_volume_set": "Volumen", "service_trigger": "Activar",
    "erreur_admin": "Solo para administradores de Home Assistant.",
    "erreur_loading": "Vision se está iniciando: inténtalo de nuevo en un momento.",
    "erreur_pc": "Este dispositivo ya no existe en Vision.",
    "erreur_json": "No se pudo leer la solicitud: inténtalo de nuevo.",
    "erreur_module": "Esta función está desactivada en Vision.",
    "erreur_minutes": "Indica una duración.",
    "erreur_defaut": "No ha funcionado: no ha cambiado nada, inténtalo de nuevo en un momento.",
    "geste_en_cours": "En curso…",
    "geste_fait": "Hecho.",
    "erreur_injoignable": "Home Assistant no responde; inténtalo de nuevo en un momento.",
    "enregistre": "Guardado: los dispositivos lo verán en su próxima consulta.",
    "echec": "No se ha guardado el ajuste; inténtalo de nuevo.",
    "pilotable": "Controlable: {services}", "lecture_seule": "Solo lectura", "action": "Acción", "lecture": "Lectura",
    "introuvable": "no encontrada", "retirer": "Quitar", "retirer_nom": "Quitar {nom}",
    "suggestions": "Entidades de Home Assistant", "aucune_suggestion": "Ninguna entidad coincide.",
    "mep": "Diseño", "mep_h": "Alineación horizontal", "mep_v": "Alineación vertical", "mep_taille": "Tamaño del valor", "mep_h_gauche": "Izquierda", "mep_h_centre": "Centro", "mep_h_droite": "Derecha", "mep_v_haut": "Arriba", "mep_v_centre": "En medio", "mep_v_bas": "Abajo", "mep_t_remplir": "Llenar", "mep_t_s": "S", "mep_t_m": "M", "mep_t_l": "L", "mep_t_xl": "XL", "mep_titre": "Mostrar el título", "mep_icone": "Mostrar el icono", "mep_aide": "El mismo diseño en todos los dispositivos; un {decideur} también puede ajustarlo desde su teléfono. «Llenar»: el valor más grande que cabe.", "tous_titre": "Para todos", "tous_aide": "Visibles en todos los dispositivos, además de la lista de cada persona.", "ajout_tous": "+ Añadir para todos", "regler": "Ajustar", "regler_nom": "Ajustar {nom}", "role": "Función", "role_lecture": "Lectura", "role_interrupteur": "Interruptor", "role_bouton": "Botón", "role_reglage": "Ajuste", "qui": "Quién puede actuar", "qui_decideurs": "Solo {decideurs}", "qui_tous": "Todos", "confirmer": "Pedir confirmación antes de actuar", "nom_affiche": "Nombre mostrado", "icone": "Icono (mdi:…)", "texte": "Línea bajo el valor (entidad)", "texte_aide": "Una entidad cuyo estado se escribe bajo el valor, por ejemplo el modo y el horario de la calefacción. Vacío: nada.", "affichage": "Visualización", "aff_valeur": "Solo el valor", "aff_detail": "Detalle", "aff_valeur_detail": "Valor + detalle", "attributs": "Atributos del detalle", "attributs_aide": "Ninguno marcado: todos los atributos.", "liste_aide": "El detalle muestra los elementos pendientes de la lista.", "enregistrer": "Guardar", "fermer": "Cerrar", "badge_tous": "todos", "badge_confirmer": "con confirmación", "toucher": "Al tocar", "toucher_role": "Acción de la función", "toucher_details": "Abrir los detalles de la entidad", "toucher_vue": "Abrir un panel o una vista", "toucher_rien": "Nada", "chemin": "Ruta en Home Assistant (por ejemplo /lovelace/0)", "chemin_liste": "Elegir un panel o una vista…", "toucher_aide": "Detalles y vistas: solo para quien puede actuar sobre la entidad; los demás conservan la acción de la función.",
    "chargement": "Cargando…", "rien": "Nada por ahora.", "ajouter_entite": "+ Añadir una entidad",
    "en_plus": "De más", "en_moins": "De menos",
    "ajout_plus": "+ De más en este dispositivo", "ajout_moins": "+ De menos en este dispositivo",
    "module_coupe": "El módulo «Entidades de Home Assistant» está desactivado: estos ajustes se guardan y vuelven con él (Vision → Ajustes → Funciones).",
    "aucune_personne": "Ninguna persona en Home Assistant.",
    "exceptions": "Excepciones por dispositivo",
    "titre": "Widgets de Home Assistant",
    "aide": "Lo que cada persona ve y puede controlar en Vision: pantalla de inicio, widgets del teléfono.",
    "en_savoir_plus": "Más información",
    "details": "Una entidad añadida a una persona aparece en todos sus dispositivos; «Para todos», en todos los dispositivos; una excepción añade u oculta una entidad en un solo dispositivo. «Ajustar» elige su función (Lectura, Interruptor, Botón, Ajuste), quién puede actuar (solo {decideurs} o todos), la confirmación, el nombre, el icono y la visualización (valor o detalle). Cerraduras y alarmas siguen en solo lectura.",
    "carte_nom": "Vision — Widgets HA",
    "carte_description": "Las entidades de Home Assistant visibles y controlables en Vision, por persona y por dispositivo."
  },
  "it": {
    "service_turn_on": "Accendi", "service_turn_off": "Spegni", "service_toggle": "Commuta", "service_press": "Premi",
    "service_open_cover": "Apri", "service_close_cover": "Chiudi", "service_stop_cover": "Ferma",
    "service_set_temperature": "Temperatura", "service_set_hvac_mode": "Modalità", "service_select_option": "Scegli",
    "service_set_value": "Valore", "service_media_play_pause": "Riproduci / pausa", "service_media_next_track": "Successivo",
    "service_media_previous_track": "Precedente", "service_volume_set": "Volume", "service_trigger": "Attiva",
    "erreur_admin": "Riservato agli amministratori di Home Assistant.",
    "erreur_loading": "Vision si sta avviando: riprova tra un momento.",
    "erreur_pc": "Questo dispositivo non esiste più in Vision.",
    "erreur_json": "Non è stato possibile leggere la richiesta: riprova.",
    "erreur_module": "Questa funzione è disattivata in Vision.",
    "erreur_minutes": "Indica una durata.",
    "erreur_defaut": "Non è andata: non è cambiato nulla, riprova tra un momento.",
    "geste_en_cours": "In corso…",
    "geste_fait": "Fatto.",
    "erreur_injoignable": "Home Assistant non risponde; riprova tra un momento.",
    "enregistre": "Salvato: i dispositivi lo vedranno al prossimo controllo.",
    "echec": "L'impostazione non è stata salvata; riprova.",
    "pilotable": "Controllabile: {services}", "lecture_seule": "Sola lettura", "action": "Azione", "lecture": "Lettura",
    "introuvable": "non trovata", "retirer": "Rimuovi", "retirer_nom": "Rimuovi {nom}",
    "suggestions": "Entità di Home Assistant", "aucune_suggestion": "Nessuna entità corrispondente.",
    "mep": "Layout", "mep_h": "Allineamento orizzontale", "mep_v": "Allineamento verticale", "mep_taille": "Dimensione del valore", "mep_h_gauche": "A sinistra", "mep_h_centre": "Al centro", "mep_h_droite": "A destra", "mep_v_haut": "In alto", "mep_v_centre": "Al centro", "mep_v_bas": "In basso", "mep_t_remplir": "Riempi", "mep_t_s": "S", "mep_t_m": "M", "mep_t_l": "L", "mep_t_xl": "XL", "mep_titre": "Mostra il titolo", "mep_icone": "Mostra l'icona", "mep_aide": "Lo stesso layout su tutti i dispositivi; un {decideur} può impostarlo anche dal telefono. «Riempi»: il valore più grande che ci sta.", "tous_titre": "Per tutti", "tous_aide": "Visibili su tutti i dispositivi, oltre all'elenco di ogni persona.", "ajout_tous": "+ Aggiungi per tutti", "regler": "Imposta", "regler_nom": "Imposta {nom}", "role": "Ruolo", "role_lecture": "Sola lettura", "role_interrupteur": "Interruttore", "role_bouton": "Pulsante", "role_reglage": "Regolazione", "qui": "Chi può agire", "qui_decideurs": "Solo {decideurs}", "qui_tous": "Tutti", "confirmer": "Chiedere conferma prima di agire", "nom_affiche": "Nome mostrato", "icone": "Icona (mdi:…)", "texte": "Riga sotto il valore (entità)", "texte_aide": "Un'entità il cui stato si scrive sotto il valore, ad esempio la modalità e l'orario del riscaldamento. Vuoto: niente.", "affichage": "Visualizzazione", "aff_valeur": "Solo valore", "aff_detail": "Dettaglio", "aff_valeur_detail": "Valore + dettaglio", "attributs": "Attributi del dettaglio", "attributs_aide": "Nessuno selezionato: tutti gli attributi.", "liste_aide": "Il dettaglio mostra le voci ancora da fare dell'elenco.", "enregistrer": "Salva", "fermer": "Chiudi", "badge_tous": "tutti", "badge_confirmer": "con conferma", "toucher": "Al tocco", "toucher_role": "Azione del ruolo", "toucher_details": "Apri i dettagli dell'entità", "toucher_vue": "Apri una plancia o una vista", "toucher_rien": "Niente", "chemin": "Percorso in Home Assistant (ad esempio /lovelace/0)", "chemin_liste": "Scegli una plancia o una vista…", "toucher_aide": "Dettagli e viste: solo per chi può agire sull'entità; gli altri mantengono l'azione del ruolo.",
    "chargement": "Caricamento…", "rien": "Ancora niente.", "ajouter_entite": "+ Aggiungi un'entità",
    "en_plus": "In più", "en_moins": "In meno",
    "ajout_plus": "+ In più su questo dispositivo", "ajout_moins": "+ In meno su questo dispositivo",
    "module_coupe": "Il modulo «Entità di Home Assistant» è disattivato: queste impostazioni vengono conservate e tornano con lui (Vision → Impostazioni → Funzioni).",
    "aucune_personne": "Nessuna persona in Home Assistant.",
    "exceptions": "Eccezioni per dispositivo",
    "titre": "Widget di Home Assistant",
    "aide": "Ciò che ogni persona vede e può controllare in Vision: schermata Home, widget del telefono.",
    "en_savoir_plus": "Scopri di più",
    "details": "Un'entità aggiunta a una persona compare su tutti i suoi dispositivi; «Per tutti», su ogni dispositivo; un'eccezione aggiunge o nasconde un'entità su un solo dispositivo. «Imposta» sceglie il ruolo (Sola lettura, Interruttore, Pulsante, Regolazione), chi può agire (solo {decideurs} o tutti), la conferma, il nome, l'icona e la visualizzazione (valore o dettaglio). Serrature e allarmi restano in sola lettura.",
    "carte_nom": "Vision — Widget HA",
    "carte_description": "Le entità di Home Assistant visibili e controllabili in Vision, per persona e per dispositivo."
  },
  "nl": {
    "service_turn_on": "Aanzetten", "service_turn_off": "Uitzetten", "service_toggle": "Omschakelen", "service_press": "Indrukken",
    "service_open_cover": "Openen", "service_close_cover": "Sluiten", "service_stop_cover": "Stoppen",
    "service_set_temperature": "Temperatuur", "service_set_hvac_mode": "Modus", "service_select_option": "Kiezen",
    "service_set_value": "Waarde", "service_media_play_pause": "Afspelen / pauze", "service_media_next_track": "Volgende",
    "service_media_previous_track": "Vorige", "service_volume_set": "Volume", "service_trigger": "Starten",
    "erreur_admin": "Alleen voor beheerders van Home Assistant.",
    "erreur_loading": "Vision start op: probeer het zo opnieuw.",
    "erreur_pc": "Dit apparaat bestaat niet meer in Vision.",
    "erreur_json": "Het verzoek kon niet worden gelezen: probeer het opnieuw.",
    "erreur_module": "Deze functie staat uit in Vision.",
    "erreur_minutes": "Geef een duur op.",
    "erreur_defaut": "Dat is niet gelukt: er is niets veranderd, probeer het zo opnieuw.",
    "geste_en_cours": "Bezig…",
    "geste_fait": "Klaar.",
    "erreur_injoignable": "Home Assistant reageert niet; probeer het zo opnieuw.",
    "enregistre": "Opgeslagen: de apparaten zien het bij hun volgende controle.",
    "echec": "De instelling is niet opgeslagen; probeer het opnieuw.",
    "pilotable": "Bedienbaar: {services}", "lecture_seule": "Alleen lezen", "action": "Actie", "lecture": "Lezen",
    "introuvable": "niet gevonden", "retirer": "Verwijderen", "retirer_nom": "{nom} verwijderen",
    "suggestions": "Home Assistant-entiteiten", "aucune_suggestion": "Geen overeenkomende entiteit.",
    "mep": "Opmaak", "mep_h": "Horizontale uitlijning", "mep_v": "Verticale uitlijning", "mep_taille": "Grootte van de waarde", "mep_h_gauche": "Links", "mep_h_centre": "Midden", "mep_h_droite": "Rechts", "mep_v_haut": "Boven", "mep_v_centre": "Midden", "mep_v_bas": "Onder", "mep_t_remplir": "Vullen", "mep_t_s": "S", "mep_t_m": "M", "mep_t_l": "L", "mep_t_xl": "XL", "mep_titre": "Titel tonen", "mep_icone": "Pictogram tonen", "mep_aide": "Dezelfde opmaak op alle apparaten; een {decideur} kan hem ook op zijn telefoon instellen. „Vullen”: de grootste waarde die past.", "tous_titre": "Voor iedereen", "tous_aide": "Zichtbaar op alle apparaten, naast de lijst van elke persoon.", "ajout_tous": "+ Toevoegen voor iedereen", "regler": "Instellen", "regler_nom": "{nom} instellen", "role": "Rol", "role_lecture": "Alleen lezen", "role_interrupteur": "Schakelaar", "role_bouton": "Knop", "role_reglage": "Instelling", "qui": "Wie mag bedienen", "qui_decideurs": "Alleen {decideurs}", "qui_tous": "Iedereen", "confirmer": "Om bevestiging vragen voor het uitvoeren", "nom_affiche": "Getoonde naam", "icone": "Pictogram (mdi:…)", "texte": "Regel onder de waarde (entiteit)", "texte_aide": "Een entiteit waarvan de status onder de waarde staat, bijvoorbeeld de verwarmingsmodus en het schema. Leeg: niets.", "affichage": "Weergave", "aff_valeur": "Alleen waarde", "aff_detail": "Details", "aff_valeur_detail": "Waarde + details", "attributs": "Attributen in de details", "attributs_aide": "Niets aangevinkt: alle attributen.", "liste_aide": "De details tonen de open items van de lijst.", "enregistrer": "Opslaan", "fermer": "Sluiten", "badge_tous": "iedereen", "badge_confirmer": "met bevestiging", "toucher": "Bij aantikken", "toucher_role": "Actie van de rol", "toucher_details": "De details van de entiteit openen", "toucher_vue": "Een dashboard of weergave openen", "toucher_rien": "Niets", "chemin": "Pad in Home Assistant (bijvoorbeeld /lovelace/0)", "chemin_liste": "Kies een dashboard of weergave…", "toucher_aide": "Details en weergaven: alleen voor wie de entiteit mag bedienen; de anderen houden de actie van de rol.",
    "chargement": "Laden…", "rien": "Nog niets.", "ajouter_entite": "+ Entiteit toevoegen",
    "en_plus": "Extra", "en_moins": "Verborgen",
    "ajout_plus": "+ Extra op dit apparaat", "ajout_moins": "+ Verborgen op dit apparaat",
    "module_coupe": "De module „Home Assistant-entiteiten” staat uit: deze instellingen blijven bewaard en komen terug met de module (Vision → Instellingen → Functies).",
    "aucune_personne": "Geen personen in Home Assistant.",
    "exceptions": "Uitzonderingen per apparaat",
    "titre": "Home Assistant-widgets",
    "aide": "Wat elke persoon in Vision ziet en kan bedienen: startscherm, widgets van de telefoon.",
    "en_savoir_plus": "Meer weten",
    "details": "Een entiteit die aan een persoon wordt toegevoegd, verschijnt op al zijn apparaten; ‘Voor iedereen’ op elk apparaat; een uitzondering voegt een entiteit toe of verbergt die op één apparaat. ‘Instellen’ kiest de rol (Alleen lezen, Schakelaar, Knop, Instelling), wie mag bedienen (alleen {decideurs} of iedereen), de bevestiging, naam, pictogram en weergave (waarde of details). Sloten en alarmen blijven alleen-lezen.",
    "carte_nom": "Vision — HA-widgets",
    "carte_description": "De Home Assistant-entiteiten die zichtbaar en bedienbaar zijn in Vision, per persoon en per apparaat."
  }
}/*i18n-json:fin*/;
// Les catégories (étiquettes de la maison) : cette carte n'en affiche pas.
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
const API_WIDGETS = "pc_parental/widgets/admin";
const ech = (t) =>
  String(t ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const NOMS_SERVICES = {
  turn_on: "service_turn_on", turn_off: "service_turn_off", toggle: "service_toggle", press: "service_press",
  open_cover: "service_open_cover", close_cover: "service_close_cover", stop_cover: "service_stop_cover",
  set_temperature: "service_set_temperature", set_hvac_mode: "service_set_hvac_mode", select_option: "service_select_option",
  set_value: "service_set_value",
  // 1.98.0 : le widget Lecteur.
  media_play_pause: "service_media_play_pause", media_next_track: "service_media_next_track",
  media_previous_track: "service_media_previous_track", volume_set: "service_volume_set",
  trigger: "service_trigger",
};

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

class VisionWidgetsCard extends HTMLElement {
  constructor() {
    super();
    this.attachShadow({ mode: "open" });
    this._geste = retourGeste(this);
    this._d = null;
    this._erreur = "";
    this._occupe = false;
    this._ouverts = new Set();
  }

  setConfig(config) { this._config = Object.assign({}, config || {}); }
  getCardSize() { return 8; }
  getGridOptions() { return { columns: "full" }; }

  // 2.2.13 : la carte ne lisait l'état qu'une fois, au premier hass. Pendant une mise à jour, Home Assistant
  // redémarre : la lecture tombe (réseau coupé, ou 503 « loading » tant que Vision démarre) et l'appli compagnon,
  // qui garde la page ouverte et se reconnecte seulement, laissait la carte en erreur pour toujours. Maintenant
  // une lecture ratée est reprise : au hass suivant (reconnexion), au retour sur l'onglet, et toutes les 10 s.
  set hass(hass) {
    const premier = !this._hass;
    this._hass = hass;
    const langue = suivreLangue(hass);
    if (premier || this._aReprendre()) { this._langue = langue; this._charger(); }
    else if (langue !== this._langue) { this._langue = langue; this._dessiner(); }
  }

  connectedCallback() { if (this._hass && this._aReprendre()) this._charger(); }
  disconnectedCallback() { clearTimeout(this._reprise); }

  _aReprendre() {
    return !this._d && !!this._erreur && !this._lecture && !this._refus && Date.now() - (this._essai || 0) > 3000;
  }

  async _charger() {
    if (this._lecture) return;
    this._lecture = true;
    this._essai = Date.now();
    clearTimeout(this._reprise);
    try {
      this._d = await this._hass.callApi("GET", API_WIDGETS);
      this._erreur = "";
    } catch (e) {
      this._refus = !!(e && e.status_code === 403);  // réservé aux administrateurs : inutile de reprendre
      this._erreur = (e && e.body && e.body.retour) || (this._refus ? tr("erreur_admin") : tr("erreur_injoignable"));
      if (!this._refus && !this._d) this._reprise = setTimeout(() => { if (this.isConnected) this._charger(); }, 10000);
    } finally {
      this._lecture = false;
    }
    this._dessiner();
  }

  /** Envoie un réglage ; vrai s'il est enregistré (la carte se redessine avec l'état rendu par le serveur). */
  async _envoyer(corps) {
    if (this._occupe) return false;
    let ok = false;
    this._geste.debut();
    this._occupe = true;
    this._dessiner();
    try {
      this._d = await this._hass.callApi("POST", API_WIDGETS, corps);
      this._erreur = "";
      this._geste.ok((this._d && this._d.retour) || tr("enregistre"));
      ok = true;
    } catch (e) {
      this._erreur = "";
      this._geste.echec(e, tr("echec"));
    }
    this._occupe = false;
    if (ok) { this._regle = ""; this._brouillon = null; }
    this._dessiner();
    return ok;
  }

  _personne(entite) { return (this._d.personnes || []).find((p) => p.entite === entite); }
  _appareil(id) { return (this._d.appareils || []).find((a) => a.id === id); }

  // 2.4.0 : le badge dit le rôle (et « tout le monde », « avec confirmation ») ; « Régler » ouvre le panneau.
  _pastille(f, retrait, section) {
    const rg = f.reglage || { role: f.permises.length ? "interrupteur" : "lecture", qui: "decideurs", confirmer: false };
    const cle = section + "|" + f.id;
    const marques = [rg.role !== "lecture" && rg.qui === "tous" ? tr("badge_tous") : "", rg.role !== "lecture" && rg.confirmer ? tr("badge_confirmer") : ""]
      .filter(Boolean).join(" · ");
    const mode = `<span class="mode ${rg.role === "lecture" ? "fixe" : "action"}">${ech(tr("role_" + rg.role))}${marques ? ` <span class="marques">· ${ech(marques)}</span>` : ""}</span>
        <button class="regler" data-regler="${ech(cle)}" aria-expanded="${this._regle === cle}" ${this._occupe ? "disabled" : ""}
          aria-label="${ech(tr("regler_nom", { nom: f.nom }))}">${ech(tr("regler"))}</button>`;
    return `<span class="pastille ${f.existe ? "" : "absente"}">
        <span class="nom">${ech((f.reglage && f.reglage.nom) || f.nom)}</span>
        <span class="valeur">${f.existe ? ech(f.valeur) : ech(tr("introuvable"))}</span>
        ${mode}
        <button class="retirer" ${retrait} ${this._occupe ? "disabled" : ""} title="${ech(tr("retirer"))}" aria-label="${ech(tr("retirer_nom", { nom: f.nom }))}">✕</button>
      </span>`;
  }

  // 2.3.8 (capture de l'auteur, appli Compagnon) : la liste <datalist> du navigateur s'affichait en haut de l'écran, sans fond,
  // par-dessus la barre et le contenu. Une liste propre à la carte : ancrée sous le champ, opaque, défilante, hauteur
  // bornée à la place visible au-dessus du clavier ; nom sur deux lignes au plus, puis l'entity_id (texte complet dit).
  _panneau(liste, section) {
    const f = liste.find((x) => this._regle === section + "|" + x.id);
    if (!f) return "";
    const rg = f.reglage || {};
    const v = Object.assign({}, rg, this._brouillon || {});
    // 15-10 (règle UX de l'auteur) : chaque groupe de choix passe par <vision-choix> — étiquettes s'il y a 3 options ou moins et
    // qu'elles tiennent sur une ligne, sinon liste déroulante qui montre le choix.
    const radios = (nom, valeurs, etiquette, titre) => `<vision-choix data-choix="${nom}" titre="${ech(titre)}" valeur="${ech(v[nom] || "")}"
        options="${ech(JSON.stringify(valeurs.map((x) => ({ cle: x, libelle: etiquette(x) }))))}"></vision-choix>`;
    const agit = v.role && v.role !== "lecture";
    // 2.5.3 : la mise en page (défaut : valeur « remplir », centrée, titre et icône montrés).
    const mep = Object.assign({ align_h: "centre", align_v: "centre", taille: "remplir", titre: true, icone: true }, v.mise_en_page || {});
    const radiosMep = (cle, valeurs, etiquette) => valeurs.map((x) => `<label class="choix"><input type="radio" name="mep_${cle}" value="${ech(x)}"
        ${mep[cle] === x ? "checked" : ""}> ${ech(etiquette(x))}</label>`).join("");
    const attributs = !f.liste && v.affichage !== "valeur" && (f.attributs_dispo || []).length ? `
        <fieldset><legend>${ech(tr("attributs"))}</legend>
          <vision-choix data-choix="attributs" multiple titre="${ech(tr("attributs"))}" valeurs="${ech(JSON.stringify(v.attributs || []))}"
            options="${ech(JSON.stringify((f.attributs_dispo || []).map((a) => ({ cle: a, libelle: a }))))}"></vision-choix>
          <p class="petit">${ech(tr("attributs_aide"))}</p></fieldset>` : "";
    return `<div class="panneau" data-panneau="${ech(section + "|" + f.id)}">
        <h4>${ech(tr("regler_nom", { nom: (rg.nom || f.nom) }))}</h4>
        <fieldset><legend>${ech(tr("role"))}</legend>${radios("role", f.roles || ["lecture"], (x) => tr("role_" + x), tr("role"))}</fieldset>
        ${agit ? `<fieldset><legend>${ech(tr("qui"))}</legend>${radios("qui", ["decideurs", "tous"], (x) => tr("qui_" + x), tr("qui"))}</fieldset>
          <label class="choix"><input type="checkbox" name="confirmer" ${v.confirmer ? "checked" : ""}> ${ech(tr("confirmer"))}</label>` : ""}
        <label class="champ">${ech(tr("nom_affiche"))}<input type="text" name="nom" maxlength="60" value="${ech(v.nom || "")}" placeholder="${ech(f.nom)}"></label>
        <label class="champ">${ech(tr("icone"))}<input type="text" name="icone" maxlength="64" value="${ech(v.icone || "")}" placeholder="mdi:…"></label>
        <label class="champ">${ech(tr("texte"))}<input type="text" name="texte" maxlength="255" value="${ech(v.texte || "")}" placeholder="sensor."></label>
        <p class="petit">${ech(tr("texte_aide"))}</p>
        <fieldset><legend>${ech(tr("affichage"))}</legend>${radios("affichage", ["valeur", "detail", "valeur_detail"], (x) => tr("aff_" + x), tr("affichage"))}
          ${f.liste && v.affichage !== "valeur" ? `<p class="petit">${ech(tr("liste_aide"))}</p>` : ""}</fieldset>
        ${attributs}
        <fieldset><legend>${ech(tr("mep"))}</legend>
          <div class="petit">${ech(tr("mep_h"))}</div>${radiosMep("align_h", ["gauche", "centre", "droite"], (x) => tr("mep_h_" + x))}
          <div class="petit">${ech(tr("mep_v"))}</div>${radiosMep("align_v", ["haut", "centre", "bas"], (x) => tr("mep_v_" + x))}
          <div class="petit">${ech(tr("mep_taille"))}</div>${radiosMep("taille", ["remplir", "s", "m", "l", "xl"], (x) => tr("mep_t_" + x))}
          <div class="ligne-entiere"><label class="choix"><input type="checkbox" name="mep_titre" ${mep.titre !== false ? "checked" : ""}> ${ech(tr("mep_titre"))}</label>
          <label class="choix"><input type="checkbox" name="mep_icone" ${mep.icone !== false ? "checked" : ""}> ${ech(tr("mep_icone"))}</label></div>
          <p class="petit">${ech(tr("mep_aide"))}</p></fieldset>
        <fieldset><legend>${ech(tr("toucher"))}</legend>${radios("toucher", ["role", "details", "vue", "rien"], (x) => tr("toucher_" + x), tr("toucher"))}
          ${(v.toucher || "role") === "vue" ? `
          ${(this._d.vues || []).length ? `<p class="petit">${ech(tr("chemin_liste"))}</p>
          <div class="vues" role="listbox" aria-label="${ech(tr("chemin_liste"))}">
            ${(this._d.vues || []).map((x) => `<button type="button" class="vue" role="option" data-chemin="${ech(x.chemin)}"
              aria-selected="${x.chemin === v.chemin}"><span>${ech(x.titre)}</span><span class="petit">${ech(x.chemin)}</span></button>`).join("")}
          </div>` : ""}
          <label class="champ">${ech(tr("chemin"))}<input type="text" name="chemin" maxlength="200" value="${ech(v.chemin || "")}" placeholder="/lovelace/0"></label>` : ""}
          <p class="petit">${ech(tr("toucher_aide"))}</p></fieldset>
        <div class="boutons"><button class="principal" data-enregistrer="${ech(f.id)}" ${this._occupe ? "disabled" : ""}>${ech(tr("enregistrer"))}</button>
          <button class="secondaire" data-fermer="1">${ech(tr("fermer"))}</button></div>
      </div>`;
  }

  /** Ce que le panneau ouvert dit maintenant (gardé d'un redessin à l'autre, pour les choix qui changent ce qu'il montre). */
  _lirePanneau() {
    const z = this.shadowRoot.querySelector("[data-panneau]");
    if (!z) return null;
    // Les groupes de choix (<vision-choix>) : la valeur de l'élément (ou ses attributs s'il n'est pas encore défini).
    const groupe = (n) => z.querySelector(`[data-choix="${n}"]`);
    const val = (n) => {
      const x = groupe(n);
      if (!x) return undefined;
      const c = typeof x.valeur === "string" ? x.valeur : (x.getAttribute("valeur") || "");
      return c || undefined;
    };
    const plusieurs = (n) => {
      const x = groupe(n);
      if (!x) return [];
      if (Array.isArray(x.valeurs)) return x.valeurs;
      try { const l = JSON.parse(x.getAttribute("valeurs") || "[]"); return Array.isArray(l) ? l : []; } catch (e) { return []; }
    };
    const sortie = { role: val("role"), affichage: val("affichage"), toucher: val("toucher") || "role", nom: z.querySelector('input[name="nom"]').value.trim(),
      icone: z.querySelector('input[name="icone"]').value.trim(),
      texte: z.querySelector('input[name="texte"]').value.trim(),
      attributs: plusieurs("attributs") };
    if (groupe("qui")) sortie.qui = val("qui");
    const chemin = z.querySelector('input[name="chemin"]');
    if (chemin) sortie.chemin = chemin.value.trim();
    const c = z.querySelector('input[name="confirmer"]');
    if (c) sortie.confirmer = c.checked;
    sortie.mise_en_page = { align_h: val("mep_align_h") || "centre", align_v: val("mep_align_v") || "centre", taille: val("mep_taille") || "remplir",
      titre: z.querySelector('input[name="mep_titre"]').checked, icone: z.querySelector('input[name="mep_icone"]').checked };
    return sortie;
  }

  _ajout(cle, libelle) {
    const id = "vw-l-" + cle.replace(/[^a-zA-Z0-9_-]/g, "_");
    return `<div class="ajout">
        <input type="text" role="combobox" aria-autocomplete="list" aria-expanded="false" aria-controls="${ech(id)}" autocomplete="off"
          data-ajout="${ech(cle)}" placeholder="${ech(libelle)}" aria-label="${ech(libelle)}" ${this._occupe ? "disabled" : ""}>
        <ul class="sugg" id="${ech(id)}" role="listbox" aria-label="${ech(tr("suggestions"))}" hidden></ul>
      </div>`;
  }

  /** Les entités proposées qui correspondent à la saisie (nom ou entity_id, sans accents ni casse), 50 au plus. */
  _candidats(texte) {
    const plat = (t) => String(t || "").normalize("NFKD").replace(/[\u0300-\u036f]/g, "").toLowerCase();
    const mots = plat(texte).split(/\s+/).filter(Boolean);
    return (this._d.candidates || []).filter((c) => { const t = plat(c.nom + " " + c.id); return mots.every((m) => t.includes(m)); }).slice(0, 50);
  }

  _ouvrirListe(input) {
    const ul = input.parentElement.querySelector(".sugg");
    const liste = this._candidats(input.value);
    ul.innerHTML = liste.length ? liste.map((c, i) => `<li role="option" id="${ech(ul.id)}-${i}" data-e="${ech(c.id)}" aria-label="${ech(c.nom + " (" + c.id + ")")}">
        <span class="s-nom" data-texte-complet="${ech(c.nom)}">${ech(c.nom)}</span><span class="s-id" data-texte-complet="${ech(c.id)}">${ech(c.id)}</span></li>`).join("")
      : `<li class="s-vide" role="option" aria-disabled="true">${ech(tr("aucune_suggestion"))}</li>`;
    ul.hidden = false;
    input.setAttribute("aria-expanded", "true");
    this._actif = -1;
    this._placerListe(input, ul);
  }

  _fermerListe(input) {
    const ul = input.parentElement.querySelector(".sugg");
    ul.hidden = true; ul.innerHTML = "";
    input.setAttribute("aria-expanded", "false"); input.removeAttribute("aria-activedescendant");
  }

  /** La liste tient dans la place visible sous le champ (au-dessus du clavier : visualViewport), jamais au-dessus. */
  _placerListe(input, ul) {
    const vv = window.visualViewport;
    const bas = vv ? vv.offsetTop + vv.height : window.innerHeight;
    const place = Math.floor(bas - input.getBoundingClientRect().bottom - 12);
    if (place < 160) input.scrollIntoView({ block: "start", behavior: "smooth" });
    ul.style.maxHeight = Math.max(160, Math.min(320, place)) + "px";
  }

  _choisir(input, eid) {
    this._fermerListe(input);
    input.value = "";
    if (!eid || !eid.includes(".")) return;
    const ajout = input.dataset.ajout;
    const [genre, cle] = [ajout.split(":")[0], ajout.slice(ajout.indexOf(":") + 1)];
    if (genre === "t") {
      const tous = (this._d.tous || []).map((f) => f.id);
      if (!tous.includes(eid)) this._envoyer({ op: "tous", entites: [...tous, eid] });
      return;
    }
    if (genre === "p") {
      const p = this._personne(cle);
      if (p.entites.some((f) => f.id === eid)) return;
      this._envoyer({ op: "personne", personne: cle, entites: [...p.entites.map((f) => f.id), eid] });
    } else {
      const a = this._appareil(cle);
      const plus = a.plus.map((f) => f.id), moins = a.moins.map((f) => f.id);
      if (genre === "a+") plus.push(eid); else moins.push(eid);
      this._envoyer({ op: "appareil", pc: cle, plus: [...new Set(plus)], moins: [...new Set(moins)] });
    }
  }

  _dessiner() {
    const d = this._d;
    let corps = "";
    if (this._erreur && !d) {
      corps = `<div class="erreur">${ech(this._erreur)}</div>`;
    } else if (!d) {
      corps = `<div class="vide">${ech(tr("chargement"))}</div>`;
    } else {
      const tous = d.tous || [];
      const sectionTous = `
        <section class="tous">
          <h3>${ech(tr("tous_titre"))}</h3>
          <p class="petit">${ech(tr("tous_aide"))}</p>
          <div class="pastilles">${tous.length ? tous.map((f) => this._pastille(f, `data-retirer-t="1" data-e="${ech(f.id)}"`, "t")).join("")
            : `<span class="vide">${ech(tr("rien"))}</span>`}</div>
          ${this._panneau(tous, "t")}
          ${this._ajout("t:tous", tr("ajout_tous"))}
        </section>`;
      // Refonte lot 3 : intégrée dans la fiche d'une personne (« personne »), la carte ne montre qu'elle et ses appareils.
      const seule = this._config.personne || "";
      const personnes = (d.personnes || []).filter((p) => !seule || p.entite === seule).map((p) => `
        <section>
          <h3>${ech(p.prenom)}</h3>
          <div class="pastilles">${p.entites.length ? p.entites.map((f) => this._pastille(f, `data-retirer-p="${ech(p.entite)}" data-e="${ech(f.id)}"`, "p:" + p.entite)).join("")
            : `<span class="vide">${ech(tr("rien"))}</span>`}</div>
          ${this._panneau(p.entites, "p:" + p.entite)}
          ${this._ajout("p:" + p.entite, tr("ajouter_entite"))}
        </section>`).join("");
      const ouvert = this._ouverts.has("exceptions");
      const exceptions = ouvert ? (d.appareils || []).filter((a) => !seule || a.personne === seule).map((a) => `
        <div class="appareil">
          <h4>${ech(a.nom)}${a.prenom ? ` <span class="qui">· ${ech(a.prenom)}</span>` : ""}</h4>
          <div class="ligne"><span class="etiquette">${ech(tr("en_plus"))}</span><div class="pastilles">${a.plus.map((f) => this._pastille(f, `data-retirer-a="${ech(a.id)}" data-sens="plus" data-e="${ech(f.id)}"`, "a+:" + a.id)).join("") || '<span class="vide">—</span>'}</div></div>
          ${this._panneau(a.plus, "a+:" + a.id)}
          ${this._ajout("a+:" + a.id, tr("ajout_plus"))}
          <div class="ligne"><span class="etiquette">${ech(tr("en_moins"))}</span><div class="pastilles">${a.moins.map((f) => this._pastille(f, `data-retirer-a="${ech(a.id)}" data-sens="moins" data-e="${ech(f.id)}"`, "a-:" + a.id)).join("") || '<span class="vide">—</span>'}</div></div>
          ${this._ajout("a-:" + a.id, tr("ajout_moins"))}
        </div>`).join("") : "";
      corps = `
        ${d.module ? "" : `<div class="erreur">${ech(tr("module_coupe"))}</div>`}
        ${this._erreur ? `<div class="erreur">${ech(this._erreur)}</div>` : ""}
        ${seule ? "" : sectionTous}
        ${personnes || `<div class="vide">${ech(tr("aucune_personne"))}</div>`}
        <button class="lien" data-ouvrir="exceptions">${ouvert ? "▾" : "▸"} ${ech(tr("exceptions"))}</button>
        ${exceptions}`;
    }
    this.shadowRoot.innerHTML = `
      <style>
        :host { display:block; }
        ha-card { padding:16px; }
        h2 { margin:0 0 4px; font-size:1.2em; }
        .aide { color:var(--secondary-text-color); margin:0 0 12px; }
        details { color:var(--secondary-text-color); margin:-6px 0 12px; font-size:.92em; }
        section { padding:10px 0; border-top:1px solid var(--divider-color); }
        h3 { margin:0 0 8px; font-size:1.05em; }
        h4 { margin:10px 0 6px; font-size:1em; }
        .qui { color:var(--secondary-text-color); font-weight:normal; }
        .pastilles { display:flex; flex-wrap:wrap; gap:8px; }
        .pastille { display:inline-flex; flex-wrap:wrap; max-width:100%; box-sizing:border-box; align-items:center; gap:4px 8px; padding:6px 6px 6px 12px; border-radius:18px;
          background:var(--secondary-background-color); border:1px solid var(--divider-color); }
        .pastille.absente { opacity:.6; }
        .nom { font-weight:600; overflow-wrap:anywhere; }
        .valeur { overflow-wrap:anywhere; }
        .valeur { color:var(--secondary-text-color); }
        .mode { border:1px solid var(--divider-color); background:none; border-radius:12px; padding:3px 10px; cursor:pointer; color:var(--primary-text-color); font:inherit; font-size:.85em; }
        .mode.action { background:var(--primary-color); color:var(--text-primary-color, #fff); border-color:var(--primary-color); }
        .mode.fixe { cursor:default; color:var(--secondary-text-color); }
        .marques { font-weight:normal; opacity:.85; }
        .regler { border:1px solid var(--divider-color); background:none; border-radius:12px; padding:4px 10px; min-height:28px;
          cursor:pointer; color:var(--primary-color); font:inherit; font-size:.85em; }
        .panneau { margin:10px 0; padding:12px; border:1px solid var(--primary-color); border-radius:12px; display:grid; gap:10px; }
        .panneau h4 { margin:0; overflow-wrap:anywhere; }
        .panneau fieldset { border:none; margin:0; padding:0; display:flex; flex-wrap:wrap; gap:6px 14px; }
        .panneau legend { font-weight:600; padding:0 0 4px; }
        .panneau vision-choix { flex:1 1 100%; min-width:0; }
        .choix { display:inline-flex; align-items:center; gap:6px; min-height:32px; overflow-wrap:anywhere; }
        .choix input { width:20px; height:20px; margin:0; flex:none; }
        .champ { display:grid; gap:4px; }
        .vues { display:grid; gap:4px; max-height:220px; overflow-y:auto; margin:4px 0 8px; max-width:420px; }
        .vue { display:grid; gap:2px; text-align:start; padding:6px 10px; border-radius:8px; border:1px solid var(--divider-color);
          background:var(--card-background-color); color:var(--primary-text-color); font:inherit; cursor:pointer; overflow-wrap:anywhere; min-height:36px; }
        .vue[aria-selected="true"] { border-color:var(--primary-color); box-shadow:inset 0 0 0 1px var(--primary-color); }
        .champ input { padding:8px 10px; min-width:0; max-width:100%; border-radius:8px; border:1px solid var(--divider-color); background:var(--card-background-color);
          color:var(--primary-text-color); font:inherit; max-width:420px; box-sizing:border-box; width:100%; }
        .petit { color:var(--secondary-text-color); font-size:.88em; margin:0; flex-basis:100%; }
        .ligne-entiere { flex-basis:100%; display:flex; flex-wrap:wrap; gap:6px 14px; }
        .boutons { display:flex; gap:8px; flex-wrap:wrap; }
        .principal { background:var(--primary-color); color:var(--text-primary-color, #fff); border:none; border-radius:10px; padding:8px 16px; min-height:36px; cursor:pointer; font:inherit; }
        .secondaire { background:none; border:1px solid var(--divider-color); color:var(--primary-text-color); border-radius:10px; padding:8px 14px; min-height:36px; cursor:pointer; font:inherit; }
        .retirer { border:none; background:none; cursor:pointer; color:var(--secondary-text-color); font-size:1em; padding:2px 6px; }
        .ajout { margin-top:8px; position:relative; max-width:420px; }
        /* La liste des suggestions : ancrée sous le champ, opaque (même si le fond de carte est translucide), au-dessus
           du reste de la carte, défilante ; hauteur posée par _placerListe (place visible au-dessus du clavier). */
        .sugg { position:absolute; top:calc(100% + 4px); left:0; right:0; z-index:3; margin:0; padding:4px 0; list-style:none;
          max-height:320px; overflow-y:auto; overscroll-behavior:contain; border-radius:10px; border:1px solid var(--divider-color);
          background-color:var(--card-background-color, #fff); box-shadow:0 6px 18px rgba(0,0,0,.28); }
        .sugg li { display:flex; flex-direction:column; gap:2px; padding:8px 12px; min-height:44px; box-sizing:border-box; justify-content:center; cursor:pointer; }
        .sugg li[aria-selected="true"], .sugg li:hover { background:var(--secondary-background-color); }
        .sugg .s-nom { font-weight:500; overflow:hidden; display:-webkit-box; -webkit-box-orient:vertical; -webkit-line-clamp:2; overflow-wrap:anywhere; }
        .sugg .s-id { font-size:.82em; color:var(--secondary-text-color); overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
        .sugg .s-vide { color:var(--secondary-text-color); cursor:default; }
        .ajout input { width:100%; box-sizing:border-box; padding:8px 10px; border-radius:8px; border:1px solid var(--divider-color);
          background:var(--card-background-color); color:var(--primary-text-color); font:inherit; }
        .ligne { display:flex; gap:10px; align-items:flex-start; margin-top:6px; flex-wrap:wrap; }
        .etiquette { color:var(--secondary-text-color); min-width:70px; padding-top:6px; }
        .lien { border:none; background:none; color:var(--primary-color); cursor:pointer; padding:10px 0 4px; font:inherit; }
        .appareil { padding:6px 0 10px; border-top:1px dashed var(--divider-color); }
        .erreur { color:var(--error-color, #c62828); margin:8px 0; }
        .vide { color:var(--secondary-text-color); }
      </style>
      <ha-card>
        ${this._config.integre ? "" : `<h2>${ech(tr("titre"))}</h2>`}
        <p class="aide">${ech(tr("aide"))}</p>
        <details><summary>${ech(tr("en_savoir_plus"))}</summary>
          ${ech(tr("details"))}
        </details>
        ${corps}
      </ha-card>`;
    this._brancher();
  }

  _brancher() {
    const r = this.shadowRoot;
    r.querySelectorAll("[data-ouvrir]").forEach((b) => b.addEventListener("click", () => {
      const k = b.dataset.ouvrir;
      if (this._ouverts.has(k)) this._ouverts.delete(k); else this._ouverts.add(k);
      this._dessiner();
    }));
    r.querySelectorAll("[data-retirer-p]").forEach((b) => b.addEventListener("click", () => {
      const p = this._personne(b.dataset.retirerP);
      this._envoyer({ op: "personne", personne: p.entite, entites: p.entites.map((f) => f.id).filter((e) => e !== b.dataset.e) });
    }));
    r.querySelectorAll("[data-retirer-a]").forEach((b) => b.addEventListener("click", () => {
      const a = this._appareil(b.dataset.retirerA);
      const plus = a.plus.map((f) => f.id), moins = a.moins.map((f) => f.id);
      const garde = (l) => l.filter((e) => e !== b.dataset.e);
      this._envoyer({ op: "appareil", pc: a.id, plus: b.dataset.sens === "plus" ? garde(plus) : plus, moins: b.dataset.sens === "moins" ? garde(moins) : moins });
    }));
    r.querySelectorAll("[data-retirer-t]").forEach((b) => b.addEventListener("click", () => {
      this._envoyer({ op: "tous", entites: (this._d.tous || []).map((f) => f.id).filter((e) => e !== b.dataset.e) });
    }));
    r.querySelectorAll("[data-regler]").forEach((b) => b.addEventListener("click", () => {
      this._regle = this._regle === b.dataset.regler ? "" : b.dataset.regler;
      this._brouillon = null;
      this._dessiner();
      const z = this.shadowRoot.querySelector("[data-panneau]");
      if (z) z.scrollIntoView({ block: "nearest" });
    }));
    const panneau = r.querySelector("[data-panneau]");
    if (panneau) {
      // Un choix qui change ce que montre le panneau (rôle, affichage) le redessine, sans perdre le reste.
      panneau.addEventListener("vision-choix", (ev) => {
        const n = ev.target && ev.target.dataset ? ev.target.dataset.choix : "";
        if (!["role", "affichage", "toucher"].includes(n)) return;
        this._brouillon = this._lirePanneau(); this._dessiner();
      });
      // 2.4.3 : un tableau de bord ou une vue choisi dans la liste remplit le chemin (qui reste modifiable).
      panneau.querySelectorAll("button.vue[data-chemin]").forEach((b) => b.addEventListener("click", () => {
        panneau.querySelector('input[name="chemin"]').value = b.dataset.chemin;
        panneau.querySelectorAll("button.vue").forEach((x) => x.setAttribute("aria-selected", x === b ? "true" : "false"));
      }));
      panneau.querySelector("[data-fermer]").addEventListener("click", () => { this._regle = ""; this._brouillon = null; this._dessiner(); });
      panneau.querySelector("[data-enregistrer]").addEventListener("click", async (ev) => {
        const entite = ev.currentTarget.dataset.enregistrer;
        const voulu = this._lirePanneau();
        this._brouillon = voulu;  // en cas d'échec, le panneau reste ouvert avec ce qui a été saisi
        await this._envoyer({ op: "reglage", entite, ...voulu });
      });
    }
    r.querySelectorAll("[data-ajout]").forEach((i) => {
      const ul = i.parentElement.querySelector(".sugg");
      const options = () => [...ul.querySelectorAll("li[data-e]")];
      const marquer = (n) => {
        const l = options();
        if (!l.length) return;
        this._actif = (n + l.length) % l.length;
        l.forEach((li, k) => li.setAttribute("aria-selected", k === this._actif ? "true" : "false"));
        i.setAttribute("aria-activedescendant", l[this._actif].id);
        l[this._actif].scrollIntoView({ block: "nearest" });
      };
      i.addEventListener("focus", () => this._ouvrirListe(i));
      i.addEventListener("input", () => this._ouvrirListe(i));
      i.addEventListener("keydown", (ev) => {
        if (ev.key === "ArrowDown") { ev.preventDefault(); if (ul.hidden) this._ouvrirListe(i); marquer((this._actif ?? -1) + 1); }
        else if (ev.key === "ArrowUp") { ev.preventDefault(); marquer((this._actif ?? 0) - 1); }
        else if (ev.key === "Escape") { this._fermerListe(i); }
        else if (ev.key === "Enter") {
          ev.preventDefault();
          const l = options();
          const choisi = l[this._actif] || (l.length === 1 ? l[0] : null);
          this._choisir(i, choisi ? choisi.dataset.e : String(i.value || "").trim());
        }
      });
      // Toucher ou cliquer une suggestion : pointerdown garde le focus (la liste ne se ferme pas avant le choix).
      ul.addEventListener("pointerdown", (ev) => ev.preventDefault());
      ul.addEventListener("click", (ev) => { const li = ev.target.closest("li[data-e]"); if (li) this._choisir(i, li.dataset.e); });
      i.addEventListener("blur", () => setTimeout(() => { if (this.shadowRoot.activeElement !== i) this._fermerListe(i); }, 150));
    });
  }
}

const _dejaVisionWidgetsCard = !!customElements.get("vision-widgets-card");
if (!_dejaVisionWidgetsCard) customElements.define("vision-widgets-card", VisionWidgetsCard);
window.customCards = window.customCards || [];
if (!window.customCards.some((c) => c.type === "vision-widgets-card"))
  window.customCards.push({ type: "vision-widgets-card", name: tr("carte_nom"), description: tr("carte_description") });
