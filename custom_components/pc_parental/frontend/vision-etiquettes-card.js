/*
 * vision-etiquettes-card — étiqueter sans taper un mot.
 *
 * Avant : choisir un site, choisir une étiquette, cliquer « Ajouter »,
 * recommencer pour la deuxième. Rien ne montrait qu'un site pouvait en
 * porter plusieurs, et poser trois étiquettes sur douze sites demandait
 * trente-six gestes. On n'étiquetait donc pas.
 *
 * Ici : une ligne par site ou logiciel, ses étiquettes en pastilles, un
 * menu qui coche et décoche. Plusieurs lignes cochées d'un coup, et la
 * même étiquette leur tombe dessus ensemble.
 *
 * Les étiquettes sont celles de Home Assistant (Paramètres → Zones et
 * étiquettes) : même liste, mêmes couleurs, mêmes icônes que partout
 * ailleurs dans la maison.
 */
// ---- i18n:debut — les textes de cette carte, dans les six langues de Vision (fr source, en repli) ----
const TEXTES = /*i18n-json*/{
  "fr": {
    "afficher_suite": "Afficher les {n} suivants",
    "bascule_ouvert": "Ouvert",
    "bascule_bloque": "Bloqué",
    "bascule_neutre": "Non décidé",
    "erreur_admin": "Réservé aux administrateurs de Home Assistant.",
    "erreur_loading": "Vision démarre : réessaie dans un instant.",
    "erreur_pc": "Cet appareil n'existe plus dans Vision.",
    "erreur_json": "La demande n'a pas pu être lue : réessaie.",
    "erreur_module": "Cette fonction est désactivée dans Vision.",
    "erreur_minutes": "Indique une durée.",
    "erreur_defaut": "Ce n'est pas passé : rien n'a changé, réessaie dans un instant.",
    "geste_en_cours": "En cours…",
    "geste_fait": "C'est fait.",
    "carte_nom": "Vision — étiquettes",
    "carte_description": "Étiqueter sites et logiciels avec les étiquettes de Home Assistant, au clic et en lot.",
    "titre": "Étiquettes",
    "etat_bloque": "bloqué",
    "etat_autorise": "autorisé",
    "etat_a_classer": "à classer",
    "catalogue_injoignable": "Catalogue injoignable — Vision est-il chargé ? {detail}",
    "erreur_service": "Ce n'est pas passé : rien n'a changé, réessaie.",
    "categorie_ajoutee": "Catégorie ajoutée à Vision.",
    "categorie_retiree": "Catégorie retirée de Vision.",
    "choix_non_enregistre": "Le choix n'a pas été enregistré ; réessaie.",
    "cat_range_one": "elle range {n} site ou logiciel",
    "cat_range_other": "elle range {n} sites et logiciels",
    "cat_main": "Catégorie de Vision (cochée à la main).",
    "cat_code": "Catégorie de Vision, cochée d'office : Vision s'en sert lui-même.",
    "cat_office": "Catégorie de Vision, cochée d'office : {range}.",
    "cat_sep": " ; ",
    "cat_encore": "Décochée, mais elle compte encore : {liste}. Décocher ne la retire de nulle part ; elle n'est simplement plus proposée pour un nouveau choix. Pour qu'elle cesse de compter, retire-la de ces endroits.",
    "cat_non": "Pas une catégorie de Vision : elle n'est ni proposée au planning ni affichée sur les appareils. Coche-la pour t'en servir.",
    "supprimer_elements_one": "Supprimer « {nom} » ? Elle sera retirée de {n} élément, des règles et des plages qui s'y appuient.",
    "supprimer_elements_other": "Supprimer « {nom} » ? Elle sera retirée de {n} éléments, des règles et des plages qui s'y appuient.",
    "supprimer_question": "Supprimer « {nom} » ?",
    "retirer_un": "Retirer « {nom} » de la liste ?\n\nSeules les inscriptions faites à la main se retirent : ce qu'une machine a constaté reviendra au prochain relevé.",
    "retirer_plusieurs_one": "Retirer {n} élément de la liste ?\n\nSeules les inscriptions faites à la main se retirent : ce qu'une machine a constaté reviendra au prochain relevé.",
    "retirer_plusieurs_other": "Retirer {n} éléments de la liste ?\n\nSeules les inscriptions faites à la main se retirent : ce qu'une machine a constaté reviendra au prochain relevé.",
    "retirer_pastille": "Retirer cette étiquette",
    "tous_appareils": "Tous appareils",
    "pc": "PC",
    "filtre_tout": "Tout",
    "filtre_classer": "À classer",
    "filtre_nus": "Sans étiquette",
    "filtre_etiquetes": "Étiquetés",
    "filtre_sites": "Sites",
    "filtre_apps": "Logiciels",
    "titre_filtre": "Afficher",
    "titre_plateforme": "Appareils",
    "titre_decision": "Décision",
    "titre_pcs_bloques": "Bloquée sur ces PC",
    "chercher": "Chercher un site, un logiciel…",
    "selectionnes_one": "{n} sélectionné",
    "selectionnes_other": "{n} sélectionnés",
    "poser": "Poser",
    "retirer": "Retirer",
    "tout_decocher": "Tout décocher",
    "remettre_a_classer": "Remettre à classer",
    "dec_fermer": "Fermer",
    "dec_autoriser": "Autoriser",
    "dec_plus_tard": "Plus tard",
    "titre_plus_tard": "Laisser à classer, décider plus tard",
    "rien_ici": "Rien ici. Change de filtre ou de recherche.",
    "genre_site": "site",
    "genre_logiciel": "logiciel",
    "ajouter_etiquette": "+ étiquette",
    "bloquer_tous": "Bloquer pour tous les appareils",
    "autoriser_tous": "Autoriser pour tous les appareils",
    "retirer_liste": "Retirer de la liste (inscriptions à la main seulement)",
    "aucune_etiquette_creer": "Aucune étiquette : crée la première →",
    "nouvelle_etiquette": "Nouvelle étiquette",
    "creer_poser": "Créer et poser",
    "aide_etiquettes": "Ce sont les étiquettes de Home Assistant : la couleur et l'icône se règlent dans <b>Paramètres → Zones et étiquettes</b>, et le nom s'y renomme — Vision suit. Un bouton allumé en face d'un PC veut dire « tout ce qui porte cette étiquette est coupé sur cette machine ». <br><b>Catégorie Vision</b> : coche les étiquettes qui servent à classer sites et logiciels. Seules celles-là se choisissent au planning et ont un bouton sur chaque appareil. Celles qui rangent déjà quelque chose sont cochées d'office ; « Chauffage » ou « Lumières » n'ont rien à faire ici.",
    "nom_etiquette": "Nom de l'étiquette",
    "creer": "Créer",
    "aucune_etiquette": "Aucune étiquette pour l'instant.",
    "bloquee_pc": "Bloquée sur ce PC",
    "bloquer_pc": "Bloquer sur ce PC",
    "categorie_vision_titre": "Catégorie de Vision",
    "categorie_vision": "Catégorie Vision",
    "compte_sites_one": "{n} site",
    "compte_sites_other": "{n} sites",
    "compte_logiciels_one": "{n} logiciel",
    "compte_logiciels_other": "{n} logiciels",
    "bloquer_partout": "Bloquer partout",
    "partout_bloquer": "Partout : bloquer",
    "autoriser_partout": "Autoriser partout",
    "autoriser": "autoriser",
    "supprimer_etiquette": "Supprimer l'étiquette",
    "aide_pcs": "Les étiquettes <b>portées par la machine elle-même</b> : elles la groupent avec les autres pour les notifications et les automatisations. Ce sont les mêmes étiquettes que partout ailleurs dans Home Assistant.",
    "aucun_pc": "Aucun PC inscrit.",
    "aucune_etiquette_pc": "aucune étiquette",
    "lecture_catalogue": "Lecture du catalogue…",
    "onglet_elements": "Sites & logiciels",
    "onglet_etiquettes": "Étiquettes"
  },
  "en": {
    "afficher_suite": "Show the next {n}",
    "bascule_ouvert": "Open",
    "bascule_bloque": "Blocked",
    "bascule_neutre": "Undecided",
    "erreur_admin": "Only Home Assistant administrators can do this.",
    "erreur_loading": "Vision is starting: try again in a moment.",
    "erreur_pc": "This device no longer exists in Vision.",
    "erreur_json": "The request could not be read: try again.",
    "erreur_module": "This feature is turned off in Vision.",
    "erreur_minutes": "Enter a duration.",
    "erreur_defaut": "That didn't work: nothing changed, try again in a moment.",
    "geste_en_cours": "Working…",
    "geste_fait": "Done.",
    "carte_nom": "Vision — labels",
    "carte_description": "Label sites and apps with Home Assistant labels, with a click or in bulk.",
    "titre": "Labels",
    "etat_bloque": "blocked",
    "etat_autorise": "allowed",
    "etat_a_classer": "to sort",
    "catalogue_injoignable": "Catalogue unreachable — is Vision loaded? {detail}",
    "erreur_service": "That didn't work: nothing changed, try again.",
    "categorie_ajoutee": "Category added to Vision.",
    "categorie_retiree": "Category removed from Vision.",
    "choix_non_enregistre": "The choice wasn't saved; try again.",
    "cat_range_one": "it sorts {n} site or app",
    "cat_range_other": "it sorts {n} sites and apps",
    "cat_main": "Vision category (ticked by hand).",
    "cat_code": "Vision category, ticked automatically: Vision uses it itself.",
    "cat_office": "Vision category, ticked automatically: {range}.",
    "cat_sep": "; ",
    "cat_encore": "Unticked, but it still counts: {liste}. Unticking doesn't remove it from anywhere; it just isn't offered for new choices any more. To make it stop counting, remove it from these places.",
    "cat_non": "Not a Vision category: it isn't offered in the schedule or shown on devices. Tick it to use it.",
    "supprimer_elements_one": "Delete “{nom}”? It will be removed from {n} item, and from the rules and time slots that rely on it.",
    "supprimer_elements_other": "Delete “{nom}”? It will be removed from {n} items, and from the rules and time slots that rely on it.",
    "supprimer_question": "Delete “{nom}”?",
    "retirer_un": "Remove “{nom}” from the list?\n\nOnly entries added by hand can be removed: anything a device has detected will come back at its next check-in.",
    "retirer_plusieurs_one": "Remove {n} item from the list?\n\nOnly entries added by hand can be removed: anything a device has detected will come back at its next check-in.",
    "retirer_plusieurs_other": "Remove {n} items from the list?\n\nOnly entries added by hand can be removed: anything a device has detected will come back at its next check-in.",
    "retirer_pastille": "Remove this label",
    "tous_appareils": "All devices",
    "pc": "PC",
    "filtre_tout": "All",
    "filtre_classer": "To sort",
    "filtre_nus": "No label",
    "filtre_etiquetes": "Labelled",
    "filtre_sites": "Sites",
    "filtre_apps": "Apps",
    "titre_filtre": "Show",
    "titre_plateforme": "Devices",
    "titre_decision": "Decision",
    "titre_pcs_bloques": "Blocked on these PCs",
    "chercher": "Search for a site or an app…",
    "selectionnes_one": "{n} selected",
    "selectionnes_other": "{n} selected",
    "poser": "Add",
    "retirer": "Remove",
    "tout_decocher": "Untick all",
    "remettre_a_classer": "Back to unsorted",
    "dec_fermer": "Block",
    "dec_autoriser": "Allow",
    "dec_plus_tard": "Later",
    "titre_plus_tard": "Leave unsorted, decide later",
    "rien_ici": "Nothing here. Change the filter or the search.",
    "genre_site": "site",
    "genre_logiciel": "app",
    "ajouter_etiquette": "+ label",
    "bloquer_tous": "Block on all devices",
    "autoriser_tous": "Allow on all devices",
    "retirer_liste": "Remove from the list (hand-added entries only)",
    "aucune_etiquette_creer": "No labels yet: create the first one →",
    "nouvelle_etiquette": "New label",
    "creer_poser": "Create and add",
    "aide_etiquettes": "These are Home Assistant's labels: their colour and icon are set in <b>Settings → Areas, labels &amp; zones</b>, where they can also be renamed — Vision follows. A lit button next to a PC means “everything with this label is blocked on this machine”. <br><b>Vision category</b>: tick the labels used to sort sites and apps. Only those can be chosen in the schedule and get a button on each device. Labels that already sort something are ticked automatically; “Heating” or “Lights” don't belong here.",
    "nom_etiquette": "Label name",
    "creer": "Create",
    "aucune_etiquette": "No labels yet.",
    "bloquee_pc": "Blocked on this PC",
    "bloquer_pc": "Block on this PC",
    "categorie_vision_titre": "Vision category",
    "categorie_vision": "Vision category",
    "compte_sites_one": "{n} site",
    "compte_sites_other": "{n} sites",
    "compte_logiciels_one": "{n} app",
    "compte_logiciels_other": "{n} apps",
    "bloquer_partout": "Block everywhere",
    "partout_bloquer": "Everywhere: block",
    "autoriser_partout": "Allow everywhere",
    "autoriser": "allow",
    "supprimer_etiquette": "Delete the label",
    "aide_pcs": "Labels <b>carried by the machine itself</b>: they group it with the others for notifications and automations. They are the same labels as everywhere else in Home Assistant.",
    "aucun_pc": "No PC enrolled.",
    "aucune_etiquette_pc": "no labels",
    "lecture_catalogue": "Reading the catalogue…",
    "onglet_elements": "Sites & apps",
    "onglet_etiquettes": "Labels"
  },
  "de": {
    "afficher_suite": "Die nächsten {n} anzeigen",
    "bascule_ouvert": "Offen",
    "bascule_bloque": "Gesperrt",
    "bascule_neutre": "Unentschieden",
    "erreur_admin": "Nur für Home Assistant-Administratoren.",
    "erreur_loading": "Vision startet gerade: versuch es gleich noch einmal.",
    "erreur_pc": "Dieses Gerät gibt es in Vision nicht mehr.",
    "erreur_json": "Die Anfrage konnte nicht gelesen werden: versuch es noch einmal.",
    "erreur_module": "Diese Funktion ist in Vision ausgeschaltet.",
    "erreur_minutes": "Gib eine Dauer an.",
    "erreur_defaut": "Das hat nicht geklappt: nichts wurde geändert, versuch es gleich noch einmal.",
    "geste_en_cours": "Wird ausgeführt…",
    "geste_fait": "Erledigt.",
    "carte_nom": "Vision — Labels",
    "carte_description": "Websites und Apps mit den Labels von Home Assistant versehen, per Klick und gesammelt.",
    "titre": "Labels",
    "etat_bloque": "gesperrt",
    "etat_autorise": "erlaubt",
    "etat_a_classer": "zu sortieren",
    "catalogue_injoignable": "Katalog nicht erreichbar — ist Vision geladen? {detail}",
    "erreur_service": "Das hat nicht geklappt: nichts wurde geändert, versuch es noch einmal.",
    "categorie_ajoutee": "Kategorie zu Vision hinzugefügt.",
    "categorie_retiree": "Kategorie aus Vision entfernt.",
    "choix_non_enregistre": "Die Auswahl wurde nicht gespeichert; versuch es noch einmal.",
    "cat_range_one": "es ordnet {n} Website oder App ein",
    "cat_range_other": "es ordnet {n} Websites und Apps ein",
    "cat_main": "Vision-Kategorie (von Hand angehakt).",
    "cat_code": "Vision-Kategorie, automatisch angehakt: Vision nutzt es selbst.",
    "cat_office": "Vision-Kategorie, automatisch angehakt: {range}.",
    "cat_sep": "; ",
    "cat_encore": "Nicht angehakt, zählt aber noch: {liste}. Das Abhaken entfernt es nirgends; es wird nur nicht mehr für eine neue Auswahl angeboten. Damit es nicht mehr zählt, entferne es an diesen Stellen.",
    "cat_non": "Keine Vision-Kategorie: Es wird weder im Zeitplan angeboten noch auf den Geräten angezeigt. Hake es an, um es zu nutzen.",
    "supprimer_elements_one": "„{nom}“ löschen? Es wird von {n} Eintrag entfernt und aus den Regeln und Zeitfenstern, die darauf aufbauen.",
    "supprimer_elements_other": "„{nom}“ löschen? Es wird von {n} Einträgen entfernt und aus den Regeln und Zeitfenstern, die darauf aufbauen.",
    "supprimer_question": "„{nom}“ löschen?",
    "retirer_un": "„{nom}“ aus der Liste entfernen?\n\nNur von Hand hinzugefügte Einträge lassen sich entfernen: Was ein Gerät erkannt hat, kommt bei seiner nächsten Meldung zurück.",
    "retirer_plusieurs_one": "{n} Eintrag aus der Liste entfernen?\n\nNur von Hand hinzugefügte Einträge lassen sich entfernen: Was ein Gerät erkannt hat, kommt bei seiner nächsten Meldung zurück.",
    "retirer_plusieurs_other": "{n} Einträge aus der Liste entfernen?\n\nNur von Hand hinzugefügte Einträge lassen sich entfernen: Was ein Gerät erkannt hat, kommt bei seiner nächsten Meldung zurück.",
    "retirer_pastille": "Dieses Label entfernen",
    "tous_appareils": "Alle Geräte",
    "pc": "PC",
    "filtre_tout": "Alle",
    "filtre_classer": "Zu sortieren",
    "filtre_nus": "Ohne Label",
    "filtre_etiquetes": "Mit Label",
    "filtre_sites": "Websites",
    "filtre_apps": "Apps",
    "titre_filtre": "Anzeigen",
    "titre_plateforme": "Geräte",
    "titre_decision": "Entscheidung",
    "titre_pcs_bloques": "Auf diesen PCs gesperrt",
    "chercher": "Website oder App suchen…",
    "selectionnes_one": "{n} ausgewählt",
    "selectionnes_other": "{n} ausgewählt",
    "poser": "Hinzufügen",
    "retirer": "Entfernen",
    "tout_decocher": "Alle abwählen",
    "remettre_a_classer": "Wieder auf „zu sortieren“",
    "dec_fermer": "Sperren",
    "dec_autoriser": "Erlauben",
    "dec_plus_tard": "Später",
    "titre_plus_tard": "Unsortiert lassen, später entscheiden",
    "rien_ici": "Hier ist nichts. Ändere den Filter oder die Suche.",
    "genre_site": "Website",
    "genre_logiciel": "App",
    "ajouter_etiquette": "+ Label",
    "bloquer_tous": "Auf allen Geräten sperren",
    "autoriser_tous": "Auf allen Geräten erlauben",
    "retirer_liste": "Aus der Liste entfernen (nur von Hand hinzugefügte Einträge)",
    "aucune_etiquette_creer": "Noch keine Labels: Erstelle das erste →",
    "nouvelle_etiquette": "Neues Label",
    "creer_poser": "Erstellen und hinzufügen",
    "aide_etiquettes": "Das sind die Labels von Home Assistant: Farbe und Symbol stellst du unter <b>Einstellungen → Bereiche, Labels &amp; Zonen</b> ein, dort lässt sich auch der Name ändern — Vision zieht mit. Ein leuchtender Knopf neben einem PC bedeutet „alles mit diesem Label ist auf diesem Gerät gesperrt“. <br><b>Vision-Kategorie</b>: Hake die Labels an, mit denen Websites und Apps eingeordnet werden. Nur diese lassen sich im Zeitplan auswählen und haben auf jedem Gerät einen Knopf. Labels, die schon etwas einordnen, sind automatisch angehakt; „Heizung“ oder „Licht“ haben hier nichts zu suchen.",
    "nom_etiquette": "Name des Labels",
    "creer": "Erstellen",
    "aucune_etiquette": "Noch keine Labels.",
    "bloquee_pc": "Auf diesem PC gesperrt",
    "bloquer_pc": "Auf diesem PC sperren",
    "categorie_vision_titre": "Vision-Kategorie",
    "categorie_vision": "Vision-Kategorie",
    "compte_sites_one": "{n} Website",
    "compte_sites_other": "{n} Websites",
    "compte_logiciels_one": "{n} App",
    "compte_logiciels_other": "{n} Apps",
    "bloquer_partout": "Überall sperren",
    "partout_bloquer": "Überall: sperren",
    "autoriser_partout": "Überall erlauben",
    "autoriser": "erlauben",
    "supprimer_etiquette": "Label löschen",
    "aide_pcs": "Die Labels, <b>die das Gerät selbst trägt</b>: Sie gruppieren es mit den anderen für Benachrichtigungen und Automatisierungen. Es sind dieselben Labels wie überall sonst in Home Assistant.",
    "aucun_pc": "Kein PC registriert.",
    "aucune_etiquette_pc": "keine Labels",
    "lecture_catalogue": "Katalog wird gelesen…",
    "onglet_elements": "Websites & Apps",
    "onglet_etiquettes": "Labels"
  },
  "es": {
    "afficher_suite": "Mostrar los {n} siguientes",
    "bascule_ouvert": "Abierto",
    "bascule_bloque": "Bloqueado",
    "bascule_neutre": "Sin decidir",
    "erreur_admin": "Solo para administradores de Home Assistant.",
    "erreur_loading": "Vision se está iniciando: inténtalo de nuevo en un momento.",
    "erreur_pc": "Este dispositivo ya no existe en Vision.",
    "erreur_json": "No se pudo leer la solicitud: inténtalo de nuevo.",
    "erreur_module": "Esta función está desactivada en Vision.",
    "erreur_minutes": "Indica una duración.",
    "erreur_defaut": "No ha funcionado: no ha cambiado nada, inténtalo de nuevo en un momento.",
    "geste_en_cours": "En curso…",
    "geste_fait": "Hecho.",
    "carte_nom": "Vision — etiquetas",
    "carte_description": "Etiquetar sitios y apps con las etiquetas de Home Assistant, con un clic y en lote.",
    "titre": "Etiquetas",
    "etat_bloque": "bloqueado",
    "etat_autorise": "permitido",
    "etat_a_classer": "por clasificar",
    "catalogue_injoignable": "Catálogo inaccesible — ¿está cargado Vision? {detail}",
    "erreur_service": "No ha funcionado: no ha cambiado nada, inténtalo de nuevo.",
    "categorie_ajoutee": "Categoría añadida a Vision.",
    "categorie_retiree": "Categoría quitada de Vision.",
    "choix_non_enregistre": "No se ha guardado la elección; inténtalo de nuevo.",
    "cat_range_one": "clasifica {n} sitio o app",
    "cat_range_other": "clasifica {n} sitios y apps",
    "cat_main": "Categoría de Vision (marcada a mano).",
    "cat_code": "Categoría de Vision, marcada automáticamente: la usa el propio Vision.",
    "cat_office": "Categoría de Vision, marcada automáticamente: {range}.",
    "cat_sep": "; ",
    "cat_encore": "Desmarcada, pero sigue contando: {liste}. Desmarcarla no la quita de ningún sitio; simplemente ya no se ofrece para una nueva elección. Para que deje de contar, quítala de esos lugares.",
    "cat_non": "No es una categoría de Vision: no se ofrece en el horario ni se muestra en los dispositivos. Márcala para usarla.",
    "supprimer_elements_one": "¿Eliminar «{nom}»? Se quitará de {n} elemento y de las reglas y franjas horarias que dependen de ella.",
    "supprimer_elements_other": "¿Eliminar «{nom}»? Se quitará de {n} elementos y de las reglas y franjas horarias que dependen de ella.",
    "supprimer_question": "¿Eliminar «{nom}»?",
    "retirer_un": "¿Quitar «{nom}» de la lista?\n\nSolo se pueden quitar las entradas añadidas a mano: lo que un dispositivo haya detectado volverá en su próximo informe.",
    "retirer_plusieurs_one": "¿Quitar {n} elemento de la lista?\n\nSolo se pueden quitar las entradas añadidas a mano: lo que un dispositivo haya detectado volverá en su próximo informe.",
    "retirer_plusieurs_other": "¿Quitar {n} elementos de la lista?\n\nSolo se pueden quitar las entradas añadidas a mano: lo que un dispositivo haya detectado volverá en su próximo informe.",
    "retirer_pastille": "Quitar esta etiqueta",
    "tous_appareils": "Todos los dispositivos",
    "pc": "PC",
    "filtre_tout": "Todo",
    "filtre_classer": "Por clasificar",
    "filtre_nus": "Sin etiqueta",
    "filtre_etiquetes": "Con etiqueta",
    "filtre_sites": "Sitios",
    "filtre_apps": "Apps",
    "titre_filtre": "Mostrar",
    "titre_plateforme": "Dispositivos",
    "titre_decision": "Decisión",
    "titre_pcs_bloques": "Bloqueada en estos PC",
    "chercher": "Buscar un sitio, una app…",
    "selectionnes_one": "{n} seleccionado",
    "selectionnes_other": "{n} seleccionados",
    "poser": "Poner",
    "retirer": "Quitar",
    "tout_decocher": "Desmarcar todo",
    "remettre_a_classer": "Volver a «por clasificar»",
    "dec_fermer": "Bloquear",
    "dec_autoriser": "Permitir",
    "dec_plus_tard": "Más tarde",
    "titre_plus_tard": "Dejar por clasificar, decidir más tarde",
    "rien_ici": "Aquí no hay nada. Cambia el filtro o la búsqueda.",
    "genre_site": "sitio",
    "genre_logiciel": "app",
    "ajouter_etiquette": "+ etiqueta",
    "bloquer_tous": "Bloquear en todos los dispositivos",
    "autoriser_tous": "Permitir en todos los dispositivos",
    "retirer_liste": "Quitar de la lista (solo entradas añadidas a mano)",
    "aucune_etiquette_creer": "No hay etiquetas: crea la primera →",
    "nouvelle_etiquette": "Nueva etiqueta",
    "creer_poser": "Crear y poner",
    "aide_etiquettes": "Son las etiquetas de Home Assistant: el color y el icono se ajustan en <b>Ajustes → Áreas, etiquetas y zonas</b>, donde también se puede cambiar el nombre — Vision lo sigue. Un botón encendido junto a un PC significa «todo lo que lleva esta etiqueta está bloqueado en este equipo». <br><b>Categoría de Vision</b>: marca las etiquetas que sirven para clasificar sitios y apps. Solo esas se pueden elegir en el horario y tienen un botón en cada dispositivo. Las que ya clasifican algo están marcadas automáticamente; «Calefacción» o «Luces» no pintan nada aquí.",
    "nom_etiquette": "Nombre de la etiqueta",
    "creer": "Crear",
    "aucune_etiquette": "Aún no hay etiquetas.",
    "bloquee_pc": "Bloqueada en este PC",
    "bloquer_pc": "Bloquear en este PC",
    "categorie_vision_titre": "Categoría de Vision",
    "categorie_vision": "Categoría de Vision",
    "compte_sites_one": "{n} sitio",
    "compte_sites_other": "{n} sitios",
    "compte_logiciels_one": "{n} app",
    "compte_logiciels_other": "{n} apps",
    "bloquer_partout": "Bloquear en todas partes",
    "partout_bloquer": "En todas partes: bloquear",
    "autoriser_partout": "Permitir en todas partes",
    "autoriser": "permitir",
    "supprimer_etiquette": "Eliminar la etiqueta",
    "aide_pcs": "Las etiquetas <b>que lleva el propio equipo</b>: lo agrupan con los demás para las notificaciones y las automatizaciones. Son las mismas etiquetas que en el resto de Home Assistant.",
    "aucun_pc": "Ningún PC registrado.",
    "aucune_etiquette_pc": "ninguna etiqueta",
    "lecture_catalogue": "Leyendo el catálogo…",
    "onglet_elements": "Sitios y apps",
    "onglet_etiquettes": "Etiquetas"
  },
  "it": {
    "afficher_suite": "Mostra i {n} successivi",
    "bascule_ouvert": "Aperto",
    "bascule_bloque": "Bloccato",
    "bascule_neutre": "Da decidere",
    "erreur_admin": "Riservato agli amministratori di Home Assistant.",
    "erreur_loading": "Vision si sta avviando: riprova tra un momento.",
    "erreur_pc": "Questo dispositivo non esiste più in Vision.",
    "erreur_json": "Non è stato possibile leggere la richiesta: riprova.",
    "erreur_module": "Questa funzione è disattivata in Vision.",
    "erreur_minutes": "Indica una durata.",
    "erreur_defaut": "Non è andata: non è cambiato nulla, riprova tra un momento.",
    "geste_en_cours": "In corso…",
    "geste_fait": "Fatto.",
    "carte_nom": "Vision — etichette",
    "carte_description": "Etichettare siti e app con le etichette di Home Assistant, con un clic e in blocco.",
    "titre": "Etichette",
    "etat_bloque": "bloccato",
    "etat_autorise": "consentito",
    "etat_a_classer": "da classificare",
    "catalogue_injoignable": "Catalogo non raggiungibile — Vision è caricato? {detail}",
    "erreur_service": "Non è andata: non è cambiato nulla, riprova.",
    "categorie_ajoutee": "Categoria aggiunta a Vision.",
    "categorie_retiree": "Categoria tolta da Vision.",
    "choix_non_enregistre": "La scelta non è stata salvata; riprova.",
    "cat_range_one": "classifica {n} sito o app",
    "cat_range_other": "classifica {n} siti e app",
    "cat_main": "Categoria di Vision (spuntata a mano).",
    "cat_code": "Categoria di Vision, spuntata d'ufficio: Vision la usa direttamente.",
    "cat_office": "Categoria di Vision, spuntata d'ufficio: {range}.",
    "cat_sep": "; ",
    "cat_encore": "Non spuntata, ma conta ancora: {liste}. Togliere la spunta non la rimuove da nessuna parte; semplicemente non viene più proposta per una nuova scelta. Perché smetta di contare, toglila da questi punti.",
    "cat_non": "Non è una categoria di Vision: non viene proposta nel programma né mostrata sui dispositivi. Spuntala per usarla.",
    "supprimer_elements_one": "Eliminare «{nom}»? Verrà tolta da {n} elemento e dalle regole e fasce orarie che si basano su di essa.",
    "supprimer_elements_other": "Eliminare «{nom}»? Verrà tolta da {n} elementi e dalle regole e fasce orarie che si basano su di essa.",
    "supprimer_question": "Eliminare «{nom}»?",
    "retirer_un": "Togliere «{nom}» dall'elenco?\n\nSi possono togliere solo le voci aggiunte a mano: ciò che un dispositivo ha rilevato tornerà al suo prossimo aggiornamento.",
    "retirer_plusieurs_one": "Togliere {n} elemento dall'elenco?\n\nSi possono togliere solo le voci aggiunte a mano: ciò che un dispositivo ha rilevato tornerà al suo prossimo aggiornamento.",
    "retirer_plusieurs_other": "Togliere {n} elementi dall'elenco?\n\nSi possono togliere solo le voci aggiunte a mano: ciò che un dispositivo ha rilevato tornerà al suo prossimo aggiornamento.",
    "retirer_pastille": "Togli questa etichetta",
    "tous_appareils": "Tutti i dispositivi",
    "pc": "PC",
    "filtre_tout": "Tutto",
    "filtre_classer": "Da classificare",
    "filtre_nus": "Senza etichetta",
    "filtre_etiquetes": "Con etichetta",
    "filtre_sites": "Siti",
    "filtre_apps": "App",
    "titre_filtre": "Mostra",
    "titre_plateforme": "Dispositivi",
    "titre_decision": "Decisione",
    "titre_pcs_bloques": "Bloccata su questi PC",
    "chercher": "Cerca un sito, un'app…",
    "selectionnes_one": "{n} selezionato",
    "selectionnes_other": "{n} selezionati",
    "poser": "Applica",
    "retirer": "Togli",
    "tout_decocher": "Deseleziona tutto",
    "remettre_a_classer": "Rimetti «da classificare»",
    "dec_fermer": "Blocca",
    "dec_autoriser": "Consenti",
    "dec_plus_tard": "Più tardi",
    "titre_plus_tard": "Lasciare da classificare, decidere più tardi",
    "rien_ici": "Qui non c'è niente. Cambia filtro o ricerca.",
    "genre_site": "sito",
    "genre_logiciel": "app",
    "ajouter_etiquette": "+ etichetta",
    "bloquer_tous": "Blocca su tutti i dispositivi",
    "autoriser_tous": "Consenti su tutti i dispositivi",
    "retirer_liste": "Togli dall'elenco (solo voci aggiunte a mano)",
    "aucune_etiquette_creer": "Nessuna etichetta: crea la prima →",
    "nouvelle_etiquette": "Nuova etichetta",
    "creer_poser": "Crea e applica",
    "aide_etiquettes": "Sono le etichette di Home Assistant: colore e icona si impostano in <b>Impostazioni → Aree, etichette e zone</b>, dove si può anche cambiare il nome — Vision si adegua. Un pulsante acceso accanto a un PC significa «tutto ciò che ha questa etichetta è bloccato su questo computer». <br><b>Categoria di Vision</b>: spunta le etichette che servono a classificare siti e app. Solo quelle si possono scegliere nel programma e hanno un pulsante su ogni dispositivo. Quelle che classificano già qualcosa sono spuntate d'ufficio; «Riscaldamento» o «Luci» non c'entrano niente qui.",
    "nom_etiquette": "Nome dell'etichetta",
    "creer": "Crea",
    "aucune_etiquette": "Ancora nessuna etichetta.",
    "bloquee_pc": "Bloccata su questo PC",
    "bloquer_pc": "Blocca su questo PC",
    "categorie_vision_titre": "Categoria di Vision",
    "categorie_vision": "Categoria di Vision",
    "compte_sites_one": "{n} sito",
    "compte_sites_other": "{n} siti",
    "compte_logiciels_one": "{n} app",
    "compte_logiciels_other": "{n} app",
    "bloquer_partout": "Blocca ovunque",
    "partout_bloquer": "Ovunque: blocca",
    "autoriser_partout": "Consenti ovunque",
    "autoriser": "consenti",
    "supprimer_etiquette": "Elimina l'etichetta",
    "aide_pcs": "Le etichette <b>portate dal computer stesso</b>: lo raggruppano con gli altri per le notifiche e le automazioni. Sono le stesse etichette di tutto il resto di Home Assistant.",
    "aucun_pc": "Nessun PC registrato.",
    "aucune_etiquette_pc": "nessuna etichetta",
    "lecture_catalogue": "Lettura del catalogo…",
    "onglet_elements": "Siti e app",
    "onglet_etiquettes": "Etichette"
  },
  "nl": {
    "afficher_suite": "Volgende {n} tonen",
    "bascule_ouvert": "Open",
    "bascule_bloque": "Geblokkeerd",
    "bascule_neutre": "Onbeslist",
    "erreur_admin": "Alleen voor beheerders van Home Assistant.",
    "erreur_loading": "Vision start op: probeer het zo opnieuw.",
    "erreur_pc": "Dit apparaat bestaat niet meer in Vision.",
    "erreur_json": "Het verzoek kon niet worden gelezen: probeer het opnieuw.",
    "erreur_module": "Deze functie staat uit in Vision.",
    "erreur_minutes": "Geef een duur op.",
    "erreur_defaut": "Dat is niet gelukt: er is niets veranderd, probeer het zo opnieuw.",
    "geste_en_cours": "Bezig…",
    "geste_fait": "Klaar.",
    "carte_nom": "Vision — labels",
    "carte_description": "Sites en apps labelen met de labels van Home Assistant, met een klik en in één keer.",
    "titre": "Labels",
    "etat_bloque": "geblokkeerd",
    "etat_autorise": "toegestaan",
    "etat_a_classer": "in te delen",
    "catalogue_injoignable": "Catalogus niet bereikbaar — is Vision geladen? {detail}",
    "erreur_service": "Dat is niet gelukt: er is niets veranderd, probeer het opnieuw.",
    "categorie_ajoutee": "Categorie toegevoegd aan Vision.",
    "categorie_retiree": "Categorie verwijderd uit Vision.",
    "choix_non_enregistre": "De keuze is niet opgeslagen; probeer het opnieuw.",
    "cat_range_one": "er valt {n} site of app onder",
    "cat_range_other": "er vallen {n} sites en apps onder",
    "cat_main": "Vision-categorie (met de hand aangevinkt).",
    "cat_code": "Vision-categorie, automatisch aangevinkt: Vision gebruikt die zelf.",
    "cat_office": "Vision-categorie, automatisch aangevinkt: {range}.",
    "cat_sep": "; ",
    "cat_encore": "Uitgevinkt, maar telt nog mee: {liste}. Uitvinken haalt het label nergens weg; het wordt alleen niet meer aangeboden voor een nieuwe keuze. Wil je dat het niet meer meetelt, haal het dan op deze plekken weg.",
    "cat_non": "Geen Vision-categorie: het wordt niet aangeboden in de planning en niet getoond op de apparaten. Vink het aan om het te gebruiken.",
    "supprimer_elements_one": "„{nom}” verwijderen? Het wordt weggehaald bij {n} item en uit de regels en tijdvakken die erop steunen.",
    "supprimer_elements_other": "„{nom}” verwijderen? Het wordt weggehaald bij {n} items en uit de regels en tijdvakken die erop steunen.",
    "supprimer_question": "„{nom}” verwijderen?",
    "retirer_un": "„{nom}” van de lijst halen?\n\nAlleen met de hand toegevoegde items kunnen weg: wat een apparaat heeft gezien, komt bij de volgende melding terug.",
    "retirer_plusieurs_one": "{n} item van de lijst halen?\n\nAlleen met de hand toegevoegde items kunnen weg: wat een apparaat heeft gezien, komt bij de volgende melding terug.",
    "retirer_plusieurs_other": "{n} items van de lijst halen?\n\nAlleen met de hand toegevoegde items kunnen weg: wat een apparaat heeft gezien, komt bij de volgende melding terug.",
    "retirer_pastille": "Dit label weghalen",
    "tous_appareils": "Alle apparaten",
    "pc": "pc",
    "filtre_tout": "Alles",
    "filtre_classer": "In te delen",
    "filtre_nus": "Zonder label",
    "filtre_etiquetes": "Met label",
    "filtre_sites": "Sites",
    "filtre_apps": "Apps",
    "titre_filtre": "Tonen",
    "titre_plateforme": "Apparaten",
    "titre_decision": "Beslissing",
    "titre_pcs_bloques": "Geblokkeerd op deze pc’s",
    "chercher": "Zoek een site, een app…",
    "selectionnes_one": "{n} geselecteerd",
    "selectionnes_other": "{n} geselecteerd",
    "poser": "Toevoegen",
    "retirer": "Weghalen",
    "tout_decocher": "Alles uitvinken",
    "remettre_a_classer": "Terug naar „in te delen”",
    "dec_fermer": "Blokkeren",
    "dec_autoriser": "Toestaan",
    "dec_plus_tard": "Later",
    "titre_plus_tard": "Laten staan als in te delen, later beslissen",
    "rien_ici": "Hier staat niets. Kies een ander filter of een andere zoekopdracht.",
    "genre_site": "site",
    "genre_logiciel": "app",
    "ajouter_etiquette": "+ label",
    "bloquer_tous": "Blokkeren op alle apparaten",
    "autoriser_tous": "Toestaan op alle apparaten",
    "retirer_liste": "Van de lijst halen (alleen met de hand toegevoegde items)",
    "aucune_etiquette_creer": "Nog geen labels: maak het eerste →",
    "nouvelle_etiquette": "Nieuw label",
    "creer_poser": "Maken en toevoegen",
    "aide_etiquettes": "Dit zijn de labels van Home Assistant: kleur en pictogram stel je in bij <b>Instellingen → Ruimtes, labels &amp; zones</b>, waar je ook de naam kunt wijzigen — Vision volgt. Een verlichte knop naast een pc betekent „alles met dit label is op deze computer geblokkeerd”. <br><b>Vision-categorie</b>: vink de labels aan waarmee sites en apps worden ingedeeld. Alleen die kun je kiezen in de planning en die hebben een knop op elk apparaat. Labels waar al iets onder valt, zijn automatisch aangevinkt; „Verwarming” of „Lampen” horen hier niet bij.",
    "nom_etiquette": "Naam van het label",
    "creer": "Maken",
    "aucune_etiquette": "Nog geen labels.",
    "bloquee_pc": "Geblokkeerd op deze pc",
    "bloquer_pc": "Blokkeren op deze pc",
    "categorie_vision_titre": "Vision-categorie",
    "categorie_vision": "Vision-categorie",
    "compte_sites_one": "{n} site",
    "compte_sites_other": "{n} sites",
    "compte_logiciels_one": "{n} app",
    "compte_logiciels_other": "{n} apps",
    "bloquer_partout": "Overal blokkeren",
    "partout_bloquer": "Overal: blokkeren",
    "autoriser_partout": "Overal toestaan",
    "autoriser": "toestaan",
    "supprimer_etiquette": "Label verwijderen",
    "aide_pcs": "De labels <b>van de computer zelf</b>: die groeperen hem met de andere voor meldingen en automatiseringen. Het zijn dezelfde labels als overal elders in Home Assistant.",
    "aucun_pc": "Geen pc ingeschreven.",
    "aucune_etiquette_pc": "geen labels",
    "lecture_catalogue": "Catalogus lezen…",
    "onglet_elements": "Sites & apps",
    "onglet_etiquettes": "Labels"
  }
}/*i18n-json:fin*/;
// Les catégories (étiquettes de la maison) : copie de pc_parental/i18n/categories.json, pour trCat().
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

const VERSION = "1.60.0";

// Les trois états d'un élément, tels que l'intégration les nomme.
const ETATS = {
  "Bloqués": { texte: "etat_bloque", classe: "mal" },  // i18n-ignorer : clés de données de l'intégration (classes « Bloqués » / « Autorisés »)
  "Autorisés": { texte: "etat_autorise", classe: "bien" },  // i18n-ignorer : clés de données de l'intégration
  "À classer": { texte: "etat_a_classer", classe: "neutre" },
};

// Le nuancier des étiquettes de Home Assistant, pour retrouver la même
// couleur que dans Paramètres. Une étiquette sans couleur reste neutre.
const COULEURS = {
  primary: "#03a9f4", accent: "#ff9800", red: "#f44336", pink: "#e91e63",
  purple: "#926bc7", deep_purple: "#6e41ab", indigo: "#3f51b5",
  blue: "#2196f3", light_blue: "#03a9f4", cyan: "#00bcd4", teal: "#009688",
  green: "#4caf50", light_green: "#8bc34a", lime: "#cddc39",
  yellow: "#ffeb3b", amber: "#ffc107", orange: "#ff9800",
  deep_orange: "#ff6f22", brown: "#795548", grey: "#9e9e9e",
  blue_grey: "#607d8b", black: "#000000", white: "#ffffff",
};

const esc = (s) =>
  String(s ?? "").replace(/[&<>"']/g, (c) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
  })[c]);
const PAS_LIGNES = 150;  // lignes dessinées par tranche dans la liste des éléments (2.5.12.12)
// ---- bascule:debut — l'interrupteur d'ouverture et de blocage, le même dans toutes les cartes (essai/son_interface_test.py le vérifie) ----
// 2.5.12.12 : vert = ouvert (ou actif), rouge = bloqué, gris = non décidé. Un <button role="switch"> léger (aucun
// élément à shadow DOM par ligne : des milliers restent rapides), au clavier (Entrée, Espace) et au toucher (44 px).
const basculeHtml = ({ etat, attrs = "", inactif = false, libelle = "", titre = "" }) =>
  `<button type="button" class="vb vb-${etat}" role="switch" aria-checked="${etat === "ouvert" ? "true" : "false"}"` +  // i18n-ignorer : balisage
  ` aria-label="${esc(libelle)}"${titre ? ` title="${esc(titre)}"` : ""} ${attrs} ${inactif ? "disabled" : ""}>` +
  `<span class="vb-piste" aria-hidden="true"><span class="vb-bouton"></span></span></button>`;
const BASCULE_CSS = `
  .vb { position:relative; flex:none; display:inline-flex; align-items:center; justify-content:center; min-width:44px; min-height:44px; padding:0; border:0; background:none; cursor:pointer; }
  .vb-piste { position:relative; width:44px; height:24px; border-radius:12px; background:var(--disabled-color,#9e9e9e); transition:background .15s; }
  .vb-bouton { position:absolute; top:2px; left:2px; width:20px; height:20px; border-radius:50%; background:#fff; box-shadow:0 1px 2px rgba(0,0,0,.3); transition:transform .15s; }
  .vb-ouvert .vb-piste { background:var(--success-color,#2e7d32); }
  .vb-ouvert .vb-bouton { transform:translateX(20px); }
  .vb-bloque .vb-piste { background:var(--error-color,#c62828); }
  .vb-neutre .vb-bouton { transform:translateX(10px); }
  .vb:focus-visible { outline:2px solid var(--primary-color); outline-offset:2px; border-radius:14px; }
  .vb:disabled { cursor:not-allowed; opacity:.55; }
  @media (prefers-reduced-motion: reduce) { .vb-piste, .vb-bouton { transition:none; } }`;
// ---- bascule:fin ----

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

// 1.93.2 (l'auteur, 17/10 : « quand je clique sur une catégorie, la page remonte tout en haut ») : la carte se redessine
// en entier (innerHTML) à chaque geste ; pendant un instant elle est plus courte (les ha-icon, ha-card, vision-choix
// s'initialisent après coup) et le navigateur ramène la page vers le haut, et le bouton cliqué disparaît avec son
// focus. Avant le dessin : on retient la place de chaque conteneur qui défile (y compris à travers les shadow roots
// de Home Assistant) et le bouton qui a le focus, et la carte garde sa hauteur. Après : on remet tout, puis encore à
// l'image suivante, quand les éléments ont pris leur taille.
function vxGarderPlace(carte) {
  const places = [];
  let n = carte;
  while (n) {
    if (n.nodeType === 1 && n.scrollHeight > n.clientHeight) {
      const oy = getComputedStyle(n).overflowY;
      if (oy === "auto" || oy === "scroll" || oy === "overlay") places.push([n, n.scrollTop]);
    }
    n = n.parentNode || n.host || null;
  }
  const doc = document.scrollingElement || document.documentElement;
  places.push([doc, doc.scrollTop]);
  const actif = carte.shadowRoot && carte.shadowRoot.activeElement;
  let focus = null;
  if (actif && actif.attributes) {
    const cles = [...actif.attributes].filter((a) => a.name.startsWith("data-"));
    if (cles.length) focus = actif.tagName.toLowerCase() + cles.map((a) => `[${a.name}="${CSS.escape(a.value)}"]`).join("");
  }
  // Pendant un geste, le bouton est grisé un instant et ne peut pas garder le focus : on se souvient de lui.
  if (focus) carte._vxFocus = { sel: focus, t: Date.now() };
  else if (carte._vxFocus && Date.now() - carte._vxFocus.t < 5000) focus = carte._vxFocus.sel;
  const hauteur = carte.offsetHeight;
  if (hauteur) carte.style.minHeight = hauteur + "px";
  return { places, focus };
}

function vxRendrePlace(carte, garde) {
  const remettre = () => {
    for (const [el, haut] of garde.places) if (Math.abs(el.scrollTop - haut) > 1) el.scrollTop = haut;
  };
  remettre();
  if (garde.focus && carte.shadowRoot) {
    const b = carte.shadowRoot.querySelector(garde.focus);
    if (b && typeof b.focus === "function" && !b.disabled) b.focus({ preventScroll: true });
  }
  requestAnimationFrame(() => { remettre(); carte.style.minHeight = ""; requestAnimationFrame(remettre); });
}

class VisionEtiquettesCard extends HTMLElement {
  constructor() {
    super();
    this.attachShadow({ mode: "open" });
    this._geste = retourGeste(this);
    this._donnees = null;
    this._charge = false;
    this._erreur = "";
    this._recherche = "";
    this._filtre = "tout";
    this._limite = PAS_LIGNES;
    this._plateforme = "tout";
    this._vue = "elements";
    this._coches = new Set();
    this._menu = null;
    this._occupe = false;
  }

  setConfig(config) {
    this._config = config || {};
    this._titre = this._config.title || "";
  }

  getCardSize() {
    return 12;
  }

  set hass(hass) {
    const premier = !this._hass;
    this._hass = hass;
    const langue = suivreLangue(hass);
    if (premier) { this._langue = langue; this._charger(); }
    else if (langue !== this._langue) { this._langue = langue; this._rendre(); }
  }

  connectedCallback() {
    // Un autre onglet, une automatisation, un renommage dans Paramètres :
    // la carte ne peut pas tout savoir. Elle relit régulièrement plutôt
    // que d'afficher un état qui a vieilli sans le dire.
    this._minuteur = setInterval(() => {
      if (!this._menu && !this._occupe) this._charger(true, true);
    }, 30000);
  }

  disconnectedCallback() {
    clearInterval(this._minuteur);
  }

  async _charger(silencieux = false, minuteur = false) {
    if (!this._hass) return;
    if (!silencieux) this._charge = false;
    try {
      // 2.5.12 : la relecture des 30 s envoie l'empreinte connue ; si rien n'a changé, HA ne renvoie pas le catalogue.
      const connue = minuteur && this._charge && !this._erreur && this._revision ? this._revision : "";
      const reponse = await this._hass.callApi("GET", "pc_parental/catalogue" + (connue ? "?revision=" + encodeURIComponent(connue) : ""));
      if (reponse && reponse.inchange) return;
      // 1.93.2 : la relecture des 30 s ne redessine rien si rien n'a changé (la page ne bouge pas sous les doigts).
      const empreinte = JSON.stringify(reponse);
      this._revision = (reponse && reponse.revision) || "";
      if (minuteur && this._charge && !this._erreur && empreinte === this._empreinte) return;
      this._empreinte = empreinte;
      this._donnees = reponse;
      this._erreur = "";
    } catch (err) {
      this._erreur = tr("catalogue_injoignable", { detail: err && err.message ? err.message : "" });
    }
    this._charge = true;
    this._rendre();
  }

  async _appeler(service, donnees) {
    this._geste.debut();
    this._occupe = true;
    try {
      await this._hass.callService("pc_parental", service, donnees);
      this._geste.ok(tr("geste_fait"));
    } catch (err) {
      this._geste.echec((err && err.message) || tr("erreur_service"));
    }
    this._occupe = false;
    await this._charger(true);
  }

  // ------------------------------------------------------------ Données

  get _etiquettes() {
    return (this._donnees && this._donnees.etiquettes) || [];
  }

  get _pcs() {
    return (this._donnees && this._donnees.pcs) || [];
  }

  _couleur(nom) {
    const e = this._etiquettes.find(
      (x) => x.nom.toLowerCase() === String(nom).toLowerCase()
    );
    if (!e) return "var(--secondary-text-color)";
    return COULEURS[e.couleur] || "var(--primary-color)";
  }

  // Lot 1 (A7) : l'encre posée sur la couleur d'une étiquette, noire ou blanche, celle qui se lit le mieux (une
  // puce jaune ou ambre porte un texte foncé). Une couleur de thème (variable CSS) garde l'encre du thème.
  _encre(nom) {
    const fond = this._couleur(nom);
    const m = /^#([0-9a-f]{6})$/i.exec(fond);
    if (!m) return "var(--text-primary-color, #fff)";
    const lin = (v) => { v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); };
    const n = parseInt(m[1], 16);
    const l = 0.2126 * lin((n >> 16) & 255) + 0.7152 * lin((n >> 8) & 255) + 0.0722 * lin(n & 255);
    return (l + 0.05) / 0.05 >= 1.05 / (l + 0.05) ? "#000" : "#fff";
  }

  _elementsVisibles() {
    const tous = (this._donnees && this._donnees.elements) || [];
    const q = this._recherche.trim().toLowerCase();
    return tous.filter((el) => {
      if (this._filtre === "sites" && el.genre !== "sites") return false;
      if (this._filtre === "apps" && el.genre !== "apps") return false;
      if (this._filtre === "classer" && el.classe !== "À classer") return false;
      if (this._filtre === "etiquetes" && !el.etiquettes.length) return false;
      if (this._filtre === "nus" && el.etiquettes.length) return false;
      if (this._plateforme !== "tout" && !(el.plateformes || []).includes(this._plateforme)) return false;
      if (!q) return true;
      return (
        (el.libelle || "").toLowerCase().includes(q) ||
        el.nom.toLowerCase().includes(q) ||
        el.etiquettes.some((e) => e.toLowerCase().includes(q))
      );
    });
  }

  _cle(el) {
    return el.genre + "|" + el.nom;
  }

  // ------------------------------------------------------------- Gestes

  async _basculerEtiquette(el, etiquette, poser) {
    await this._appeler("tag_items", {
      elements: [{ genre: el.genre, nom: el.nom }],
      etiquettes: [etiquette],
      action: poser ? "ajouter" : "retirer",
    });
  }

  async _lot(etiquette, action) {
    const elements = [];
    for (const cle of this._coches) {
      const i = cle.indexOf("|");
      const genre = cle.slice(0, i);
      const nom = cle.slice(i + 1);
      elements.push({ genre, nom });
    }
    if (!elements.length) return;
    await this._appeler("tag_items", { elements, etiquettes: [etiquette], action });
  }

  async _creerEtiquette(nom) {
    if (!nom || !nom.trim()) return;
    await this._appeler("create_tag", { etiquette: nom.trim() });
  }

  // Catégorie de Vision ou non. Même convention que l'écran de veille : un POST
  // à la vue de la carte, réservé aux adultes ; le magasin ne garde que les
  // écarts au défaut. Décocher ne retire l'étiquette de rien.
  async _cocherCategorie(nom, coche) {
    this._geste.debut();
    this._occupe = true;
    try {
      const reponse = await this._hass.callApi("POST", "pc_parental/catalogue", {
        categories: { [nom]: coche },
      });
      this._donnees = reponse;
      this._erreur = "";
      this._geste.ok(coche ? tr("categorie_ajoutee") : tr("categorie_retiree"));
    } catch (err) {
      this._geste.echec(err, tr("choix_non_enregistre"));
    }
    this._occupe = false;
    this._rendre();
  }

  // Ce que dit une étiquette de son état de catégorie. C'est ce texte qui porte
  // la règle : une étiquette décochée qui s'applique encore doit le dire.
  _etatCategorie(e) {
    const n = (e.apps || 0) + (e.sites || 0);
    const range = n ? trn("cat_range", n) : "";
    const usages = e.usages || [];
    if (e.categorie) {
      if (!e.defaut) return { classe: "cat-oui", texte: tr("cat_main") };
      if (e.lue_par_le_code)
        return { classe: "cat-oui", texte: tr("cat_code") };
      return { classe: "cat-oui", texte: tr("cat_office", { range }) };
    }
    const encore = [];
    if (range) encore.push(range);
    for (const u of usages) encore.push(u);
    if (encore.length) {
      return {
        classe: "cat-encore",
        texte: tr("cat_encore", { liste: encore.join(tr("cat_sep")) }),
      };
    }
    return {
      classe: "cat-non",
      texte: tr("cat_non"),
    };
  }

  async _supprimerEtiquette(nom) {
    const combien = this._etiquettes.find((e) => e.nom === nom) || {};
    const total = (combien.apps || 0) + (combien.sites || 0);
    const message =
      total > 0
        ? trn("supprimer_elements", total, { nom: trCat(nom) })
        : tr("supprimer_question", { nom: trCat(nom) });
    if (!window.confirm(message)) return;
    await this._appeler("delete_tag", { etiquette: nom });
  }

  async _etiquettesDuPc(pc, etiquettes) {
    await this._appeler("set_pc_labels", { pc: pc.nom, etiquettes });
  }

  // Trancher pour la maison, sur un nom précis. Même geste que sur une
  // étiquette, et même portée : ce qui est bloqué pour la famille l'est
  // partout, sans qu'une machine puisse le rouvrir.
  async _decider(elements, decision) {
    if (!elements.length) return;
    if (decision === "retirer") {
      const question =
        elements.length === 1
          ? tr("retirer_un", { nom: elements[0].nom })
          : trn("retirer_plusieurs", elements.length);
      if (!window.confirm(question))
        return;
    }
    await this._appeler("decider_elements", { elements, decision });
  }

  _cochesEnElements() {
    return Array.from(this._coches).map((cle) => {
      const i = cle.indexOf("|");
      return { genre: cle.slice(0, i), nom: cle.slice(i + 1) };
    });
  }

  async _maison(etiquette, decision) {
    await this._appeler("set_house_tag", { etiquette, decision });
  }

  async _basculerPc(etiquette, pcNom, bloquer) {
    await this._appeler("set_pc_tag", {
      pc: pcNom,
      etiquette,
      bloquer,
    });
  }

  // ------------------------------------------------------------- Styles

  // Lot 1 (HA-02) : sous 600 px de large (la carte, pas l'écran : @container), chaque ligne devient une petite carte :
  // le nom, puis les étiquettes, puis les décisions en boutons pleine largeur (44 px), sans rien qui déborde à 320 px.
  _styles() {
    return `${BASCULE_CSS}
      :host { display: block;
        --vision-texte-alerte: color-mix(in srgb, var(--warning-color, #ef6c00) 50%, var(--primary-text-color, #212121));
        --vision-texte-succes: color-mix(in srgb, var(--success-color, #2e7d32) 65%, var(--primary-text-color, #212121));
        --vision-texte-erreur: color-mix(in srgb, var(--error-color, #c62828) 70%, var(--primary-text-color, #212121)); }
      ha-card { display:block; padding: 12px 12px 16px; container-type: inline-size; }
      .entete { display:flex; align-items:center; gap:8px; flex-wrap:wrap;
                margin-bottom:8px; }
      .titre { font-size:1.15rem; font-weight:500; flex:1; }
      .onglets { display:flex; gap:4px; flex-wrap:wrap; }
      .onglet { padding:4px 12px; border-radius:16px; cursor:pointer; min-height:32px;
                font-size:.85rem; border:1px solid var(--divider-color);
                background:none; color:var(--primary-text-color); }
      .onglet[aria-selected="true"] { background:var(--primary-color);
                color:var(--text-primary-color); border-color:transparent; }
      .barre { display:flex; gap:8px; align-items:center; flex-wrap:wrap;
               margin-bottom:8px; }
      input[type="text"], input[type="search"] {
        padding:7px 10px; border-radius:8px; font:inherit;
        border:1px solid var(--divider-color);
        background:var(--card-background-color);
        color:var(--primary-text-color); }
      .filtres { display:flex; gap:4px; flex-wrap:wrap; }
      .filtre { padding:3px 10px; border-radius:14px; cursor:pointer; min-height:28px;
                font-size:.78rem; border:1px solid var(--divider-color);
                background:none; color:var(--secondary-text-color); }
      .filtre[aria-pressed="true"] { background:var(--secondary-background-color);
                color:var(--primary-text-color); }
      .lot { display:flex; gap:8px; align-items:center; flex-wrap:wrap;
             padding:8px 10px; border-radius:10px; margin-bottom:8px;
             background:var(--secondary-background-color); }
      .lignes { max-height:520px; overflow:auto; }
      .ligne { display:flex; gap:8px; align-items:flex-start;
               padding:6px 4px; border-bottom:1px solid var(--divider-color); }
      .ligne:hover { background:var(--secondary-background-color); }
      .nom { min-width:170px; word-break:break-all; }
      .nom small { display:block; color:var(--secondary-text-color);
                   font-size:.75rem; }
      .pastilles { display:flex; gap:4px; flex-wrap:wrap; flex:1;
                   align-items:center; }
      .pastille { display:inline-flex; align-items:center; gap:4px; min-height:24px;
                  padding:2px 8px; border-radius:12px; font-size:.78rem;
                  color:var(--encre, #fff); cursor:pointer; border:none; }
      .pastille .x { opacity:.75; font-weight:700; }
      .ajouter { padding:2px 9px; border-radius:12px; font-size:.78rem; min-height:24px;
                 cursor:pointer; border:1px dashed var(--divider-color);
                 background:none; color:var(--secondary-text-color); }
      .choix { padding:8px; margin:2px 0 8px 26px; border-radius:10px;
               background:var(--secondary-background-color);
               display:flex; gap:6px; flex-wrap:wrap; align-items:center; }
      .choix .creer { display:flex; gap:4px; align-items:center;
                      margin-left:auto; }
      .opt { padding:3px 10px; border-radius:12px; font-size:.8rem; min-height:24px;
             cursor:pointer; border:1px solid transparent; color:var(--encre, #fff); }
      .opt[aria-pressed="false"] { background:none !important;
             color:var(--primary-text-color);
             border-color:var(--divider-color); }
      .etq { display:flex; gap:10px; align-items:center; flex-wrap:wrap;
             padding:8px 4px; border-bottom:1px solid var(--divider-color); }
      .point { width:12px; height:12px; border-radius:50%; flex:none; }
      .compte { color:var(--secondary-text-color); font-size:.78rem; }
      .maison { display:flex; gap:4px; flex-wrap:wrap; margin-left:auto; }
      .pcs { display:flex; gap:4px; flex-wrap:wrap; }
      .cat { display:flex; gap:4px; align-items:center; font-size:.78rem; cursor:pointer; }
      .cat-etat { flex-basis:100%; font-size:.78rem; color:var(--secondary-text-color); }
      .cat-encore .cat-etat { color:var(--vision-texte-alerte); }
      .cat-non b, .cat-non .compte { color:var(--secondary-text-color); font-weight:400; }
      .cat-non .maison, .cat-non .pcs { display:none; }

      .pc { padding:3px 9px; border-radius:12px; font-size:.75rem; min-height:24px;
            cursor:pointer; border:1px solid var(--divider-color);
            background:none; color:var(--secondary-text-color); }
      .pc[aria-pressed="true"], .dec.bloquer[aria-pressed="true"] {
            background:color-mix(in srgb, var(--error-color, #db4437) 16%, transparent);
            color:var(--vision-texte-erreur); border:2px solid var(--error-color, #db4437); font-weight:600; }
      .pc.ok[aria-pressed="true"], .dec.autoriser[aria-pressed="true"] {
            background:color-mix(in srgb, var(--success-color, #43a047) 16%, transparent);
            color:var(--vision-texte-succes); border:2px solid var(--success-color, #43a047); font-weight:600; }
      .dec { padding:3px 10px; border-radius:12px; font-size:.8rem; min-height:28px;
             cursor:pointer; border:1px solid var(--divider-color);
             background:none; color:var(--primary-text-color);
             white-space:nowrap; }
      .dec.sup-liste { min-width:32px; }
      .dec.plus-tard[aria-pressed="true"] { color:var(--primary-text-color);
             background:var(--secondary-background-color); border-color:var(--secondary-text-color); }
      .dec:hover { background:var(--secondary-background-color); }
      .decisions { display:flex; gap:4px; align-items:center; flex:none; }
      .etat { font-size:.75rem; padding:1px 7px; border-radius:10px;
              border:1px solid var(--divider-color); white-space:nowrap; }
      .etat.mal { color:var(--vision-texte-erreur);
                  border-color:var(--error-color,#db4437); }
      .etat.bien { color:var(--vision-texte-succes);
                   border-color:var(--success-color,#43a047); }
      .etat.neutre { color:var(--secondary-text-color); }
      .sup { background:none; border:none; cursor:pointer;
             color:var(--secondary-text-color); font-size:1rem; }
      .vide, .erreur { padding:16px 4px; color:var(--secondary-text-color); }
      .erreur { color:var(--error-color, #db4437); }
      .aide { font-size:.78rem; color:var(--secondary-text-color);
              margin:6px 2px 10px; }
      button { font:inherit; }
      input[type="checkbox"] { margin-top:3px; width:20px; height:20px; flex:none; }
      @container (max-width: 600px) {
        .lignes { max-height:none; overflow:visible; }
        .ligne { flex-wrap:wrap; gap:8px; padding:12px 4px; }
        .ligne .nom { flex:1 1 calc(100% - 40px); min-width:0; }
        .ligne .pastilles { flex:1 1 100%; }
        .ligne .decisions, .lot .decisions { flex:1 1 100%; margin-left:0 !important;
          display:grid; grid-template-columns:repeat(3, minmax(0, 1fr)) auto; gap:6px; }
        .decisions .etat { grid-column:1 / -1; justify-self:start; }
        .dec { min-height:44px; font-size:.9rem; white-space:normal; padding:6px 8px; }
        .dec.sup-liste { min-width:44px; }
        .onglet { min-height:44px; }
        .filtre { min-height:36px; }
        .barre input[type="search"], .barre input[type="text"] { flex:1 1 100%; min-width:0 !important; box-sizing:border-box; }
        .choix { margin-left:0; }
        .choix .creer { margin-left:0; flex:1 1 100%; }
        .choix .creer input { flex:1; min-width:0; }
        .maison { margin-left:0; }
        .pastille, .ajouter, .opt, .pc { min-height:32px; }
      }
    `;
  }

  // -------------------------------------------------------------- Rendu

  _pastille(nom, avecCroix) {
    return (
      `<button class="pastille" data-etiquette="${esc(nom)}" style="background:${this._couleur(nom)};--encre:${this._encre(nom)}" title="${avecCroix ? esc(tr("retirer_pastille")) : ""}">${esc(trCat(nom))}${avecCroix ? '<span class="x">×</span>' : ""}</button>`
    );
  }

  _filtresPlateforme() {
    const choix = [
      ["tout", tr("tous_appareils"), ""],
      ["pc", tr("pc"), "mdi:desktop-classic"],
      ["android", "Android", "mdi:android"],
    ];
    // Un choix (vision-choix) : étiquettes s'il tient sur une ligne, liste déroulante sinon.
    return `<vision-choix class="filtres" data-plateforme titre="${esc(tr("titre_plateforme"))}" valeur="${esc(this._plateforme)}"
      options="${esc(JSON.stringify(choix.map(([cle, texte, icone]) => (icone ? { cle, libelle: texte, icone } : { cle, libelle: texte }))))}"></vision-choix>`;
  }

  _iconesPlateforme(el) {
    return (el.plateformes || [])
      .map((p) =>
        p === "android"
          ? `<ha-icon icon="mdi:android" title="Android" style="--mdc-icon-size:16px;color:#3DDC84;vertical-align:-3px;margin-right:4px"></ha-icon>`
          : `<ha-icon icon="mdi:desktop-classic" title="${esc(tr("pc"))}" style="--mdc-icon-size:16px;vertical-align:-3px;margin-right:4px"></ha-icon>`
      )
      .join("");
  }

  _rendreElements() {
    const visibles = this._elementsVisibles();
    const filtres = [
      ["tout", tr("filtre_tout")],
      ["classer", tr("filtre_classer")],
      ["nus", tr("filtre_nus")],
      ["etiquetes", tr("filtre_etiquetes")],
      ["sites", tr("filtre_sites")],
      ["apps", tr("filtre_apps")],
    ];
    let html = `<div class="barre">
      <input type="search" id="q" placeholder="${esc(tr("chercher"))}"
             value="${esc(this._recherche)}" style="flex:1;min-width:180px">
      <vision-choix class="filtres" data-filtre titre="${esc(tr("titre_filtre"))}" valeur="${esc(this._filtre)}"
        options="${esc(JSON.stringify(filtres.map(([cle, texte]) => ({ cle, libelle: texte }))))}"></vision-choix>${this._filtresPlateforme()}</div>`;

    if (this._coches.size) {
      html += `<div class="lot">
        <b>${esc(trn("selectionnes", this._coches.size))}</b>
        <select id="lot-etq">${this._etiquettes
          .map((e) => `<option value="${esc(e.nom)}">${esc(trCat(e.nom))}</option>`)
          .join("")}</select>
        <button class="filtre" id="lot-ajout">${esc(tr("poser"))}</button>
        <button class="filtre" id="lot-retrait">${esc(tr("retirer"))}</button>
        <button class="filtre" id="lot-vider">${esc(tr("tout_decocher"))}</button>
        <span class="decisions" style="margin-left:auto">
          <button class="dec bloquer" data-lotdec="bloquer" title="${esc(tr("bloquer_tous"))}">${esc(tr("dec_fermer"))}</button>
          <button class="dec autoriser" data-lotdec="autoriser" title="${esc(tr("autoriser_tous"))}">${esc(tr("dec_autoriser"))}</button>
          <button class="dec plus-tard" data-lotdec="neutre" title="${esc(tr("remettre_a_classer"))}">${esc(tr("dec_plus_tard"))}</button>
        </span>
      </div>`;
    }

    if (!visibles.length) {
      html += `<div class="vide">${esc(tr("rien_ici"))}</div>`;
      return html;
    }

    html += '<div class="lignes">';
    // 2.5.12.12 : par tranches (des milliers d'éléments ne se dessinent pas d'un coup) ; la suite au clic.
    for (const el of visibles.slice(0, this._limite)) {
      const cle = this._cle(el);
      const posees = el.etiquettes || [];
      html += `<div class="ligne">
        <input type="checkbox" data-coche="${esc(cle)}" ${
        this._coches.has(cle) ? "checked" : ""
      }>
        <div class="nom">${this._iconesPlateforme(el)}${esc(el.libelle || el.nom)}
          <small>${el.libelle ? `${esc(el.nom)} · ` : ""}${esc(el.genre === "sites" ? tr("genre_site") : tr("genre_logiciel"))}</small></div>
        <div class="pastilles">
          ${posees.map((n) => this._pastille(n, true)).join("")}
          <button class="ajouter" data-menu="${esc(cle)}">${esc(tr("ajouter_etiquette"))}</button>
        </div>
        ${this._rendreDecisions(el, cle)}</div>`;
      if (this._menu === cle) html += this._rendreChoix(el, posees);
    }
    html += "</div>";
    if (visibles.length > this._limite) {
      html += `<button class="filtre suite" data-suite>${esc(tr("afficher_suite", { n: Math.min(PAS_LIGNES, visibles.length - this._limite) }))}</button>`;
    }
    return html;
  }

  // Les boutons au bout de la ligne : la décision se prend là où l'on
  // lit le nom, et le bouton allumé dit l'état. Rappuyer sur l'état en
  // cours remet à classer — c'est le geste qu'on attend d'un
  // interrupteur, et cela évite un quatrième bouton.
  _rendreDecisions(el, cle) {
    const etat = ETATS[el.classe] || ETATS["À classer"];
    // Lot 1 (HA-02) : des mots plutôt que des icônes (« Fermer », « Autoriser », « Plus tard ») ; « Plus tard » laisse
    // l'élément à classer (décision « neutre », comme rappuyer sur l'état en cours).
    // 15-10 : un choix (vision-choix) ; « Plus tard » remet à classer, comme rappuyer sur l'état en cours.
    const actuelle =
      el.classe === "Bloqués" ? "bloquer"  // i18n-ignorer : valeur de données comparée
      : el.classe === "Autorisés" ? "autoriser"  // i18n-ignorer : valeur de données comparée
      : "neutre";
    return `<div class="decisions">
      <span class="etat ${etat.classe}">${esc(tr(etat.texte))}</span>
      ${basculeHtml({ etat: actuelle === "autoriser" ? "ouvert" : actuelle === "bloquer" ? "bloque" : "neutre",
        attrs: `data-bascule-el="${esc(cle)}" data-actuelle="${actuelle}"`,
        libelle: `${el.libelle || el.nom} : ${tr(actuelle === "autoriser" ? "bascule_ouvert" : actuelle === "bloquer" ? "bascule_bloque" : "bascule_neutre")}`,
        titre: tr("titre_decision") })}
      ${actuelle !== "neutre" ? `<button class="dec plus-tard" data-decider="${esc(cle)}" data-decision="neutre" title="${esc(tr("remettre_a_classer"))}" aria-label="${esc(tr("remettre_a_classer"))}">↺</button>` : ""}
      <button class="dec sup-liste" data-decider="${esc(cle)}" data-decision="retirer"
        title="${esc(tr("retirer_liste"))}" aria-label="${esc(tr("retirer_liste"))}">🗑</button>
    </div>`;
  }

  _rendreChoix(el, posees) {
    const bas = posees.map((p) => p.toLowerCase());
    const options = this._etiquettes
      .map((e) => {
        const active = bas.includes(e.nom.toLowerCase());
        return (
          `<button class="opt" data-poser="${esc(e.nom)}" aria-pressed="${active}" style="background:${this._couleur(e.nom)};--encre:${this._encre(e.nom)}">${esc(trCat(e.nom))}</button>`
        );
      })
      .join("");
    return `<div class="choix">
      ${options || `<span class="compte">${esc(tr("aucune_etiquette_creer"))}</span>`}
      <span class="creer">
        <input type="text" id="neuve" placeholder="${esc(tr("nouvelle_etiquette"))}">
        <button class="filtre" id="creer">${esc(tr("creer_poser"))}</button>
      </span></div>`;
  }

  _rendreEtiquettes() {
    let html = `<div class="aide">
      ${tr("aide_etiquettes")}
    </div>
    <div class="barre">
      <input type="text" id="neuve-globale" placeholder="${esc(tr("nom_etiquette"))}">
      <button class="filtre" id="creer-globale">${esc(tr("creer"))}</button>
    </div>`;
    if (!this._etiquettes.length) {
      html += `<div class="vide">${esc(tr("aucune_etiquette"))}</div>`;
      return html;
    }
    html += '<div class="lignes">';
    const triees = [...this._etiquettes].sort(
      (a, b) => (b.categorie ? 1 : 0) - (a.categorie ? 1 : 0)
    );
    for (const e of triees) {
      const etat = this._etatCategorie(e);
      // Les PC où l'étiquette est bloquée : un choix multiple (vision-choix).
      const pcs = this._pcs.length
        ? `<vision-choix multiple data-pcs-etq="${esc(e.nom)}" titre="${esc(tr("titre_pcs_bloques"))}"
            valeurs="${esc(JSON.stringify(this._pcs.filter((pc) => (e.pcs || []).includes(pc.id)).map((pc) => pc.nom)))}"
            options="${esc(JSON.stringify(this._pcs.map((pc) => ({ cle: pc.nom, libelle: pc.nom }))))}"></vision-choix>`
        : "";
      html += `<div class="etq ${etat.classe}">
        <label class="cat" title="${esc(tr("categorie_vision_titre"))}">
          <input type="checkbox" data-categorie="${esc(e.nom)}" ${e.categorie ? "checked" : ""}>
          ${esc(tr("categorie_vision"))}
        </label>
        <span class="point" style="background:${this._couleur(e.nom)}"></span>
        <b>${esc(trCat(e.nom))}</b>
        <span class="compte">${esc(trn("compte_sites", e.sites || 0))} · ${esc(trn("compte_logiciels", e.apps || 0))}</span>
        <span class="cat-etat">${esc(etat.texte)}</span>
        <span class="maison">
          ${basculeHtml({ etat: e.bannie ? "bloque" : e.autorisee ? "ouvert" : "neutre",
            attrs: `data-bascule-etq="${esc(e.nom)}" data-actuelle="${e.bannie ? "bloquer" : e.autorisee ? "autoriser" : "neutre"}"`,
            libelle: `${trCat(e.nom)} : ${tr(e.bannie ? "bascule_bloque" : e.autorisee ? "bascule_ouvert" : "bascule_neutre")}`,
            titre: e.bannie ? tr("autoriser_partout") : tr("bloquer_partout") })}
          ${
            e.bannie || e.autorisee
              ? `<button class="pc" data-maison="neutre" data-etq="${esc(
                  e.nom
                )}" aria-pressed="false" title="${esc(tr("remettre_a_classer"))}">↺</button>`
              : ""
          }
        </span>
        <span class="pcs">${pcs}</span>
        <button class="sup" data-supprimer="${esc(e.nom)}"
                title="${esc(tr("supprimer_etiquette"))}">🗑</button>
      </div>`;
    }
    html += "</div>";
    return html;
  }

  _rendrePcs() {
    // Ce sont les étiquettes **de la machine** : celles qui la groupent
    // avec les autres (Parents, Enfants) pour viser d'un coup. À ne pas
    // confondre avec les étiquettes qui classent sites et logiciels —
    // même liste, deux usages, et c'est bien ce qui les rend pratiques.
    let html = `<div class="aide">
      ${tr("aide_pcs")}
    </div>`;
    if (!this._pcs.length) {
      html += `<div class="vide">${esc(tr("aucun_pc"))}</div>`;
      return html;
    }
    html += '<div class="lignes">';
    for (const pc of this._pcs) {
      const prises = (pc.etiquettes || []).map((e) => e.toLowerCase());
      const chips = this._etiquettes
        .map(
          (e) =>
            `<button class="opt" data-pcnom="${esc(pc.nom)}" data-etq="${esc(e.nom)}" aria-pressed="${prises.includes(e.nom.toLowerCase())}" style="background:${this._couleur(e.nom)};--encre:${this._encre(e.nom)}">${esc(trCat(e.nom))}</button>`
        )
        .join("");
      html += `<div class="ligne">
        <div class="nom">${esc(pc.nom)}
          <small>${esc((pc.etiquettes || []).map(trCat).join(", ") || tr("aucune_etiquette_pc"))}</small>
        </div>
        <div class="pastilles">${chips}</div></div>`;
    }
    html += "</div>";
    return html;
  }

  _rendre() {
    if (!this.shadowRoot) return;
    const corps = !this._charge
      ? `<div class="vide">${esc(tr("lecture_catalogue"))}</div>`
      : this._vue === "elements"
      ? this._rendreElements()
      : this._vue === "pcs"
      ? this._rendrePcs()
      : this._rendreEtiquettes();

    const garde = vxGarderPlace(this);
    this.shadowRoot.innerHTML = `<style>${this._styles()}</style>
      <ha-card>
        <div class="entete">
          <span class="titre">${esc(this._titre || tr("titre"))}</span>
          <div class="onglets">
            <button class="onglet" data-vue="elements"
              aria-selected="${this._vue === "elements"}">${esc(tr("onglet_elements"))}</button>
            <button class="onglet" data-vue="etiquettes"
              aria-selected="${this._vue === "etiquettes"}">${esc(tr("onglet_etiquettes"))}</button>
            <button class="onglet" data-vue="pcs"
              aria-selected="${this._vue === "pcs"}">${esc(tr("pc"))}</button>
          </div>
        </div>
        ${this._erreur ? `<div class="erreur">${esc(this._erreur)}</div>` : ""}
        ${corps}
      </ha-card>`;
    this._brancher();
    vxRendrePlace(this, garde);
  }

  // ------------------------------------------------------------ Écoutes

  _brancher() {
    const r = this.shadowRoot;
    const el = (s) => r.querySelector(s);
    const tous = (s) => Array.from(r.querySelectorAll(s));

    tous("[data-vue]").forEach((b) =>
      b.addEventListener("click", () => {
        this._vue = b.dataset.vue;
        this._menu = null;
        this._rendre();
      })
    );

    const q = el("#q");
    if (q) {
      q.addEventListener("input", () => {
        this._recherche = q.value;
        this._limite = PAS_LIGNES;
        this._menu = null;
        this._rendre();
        const neuf = this.shadowRoot.querySelector("#q");
        if (neuf) {
          neuf.focus();
          neuf.setSelectionRange(neuf.value.length, neuf.value.length);
        }
      });
    }

    tous("[data-filtre]").forEach((c) =>
      c.addEventListener("vision-choix", (ev) => {
        this._filtre = ev.detail.valeur;
        this._limite = PAS_LIGNES;
        this._menu = null;
        this._rendre();
      })
    );
    tous("[data-plateforme]").forEach((c) =>
      c.addEventListener("vision-choix", (ev) => {
        this._plateforme = ev.detail.valeur;
        this._menu = null;
        this._rendre();
      })
    );

    tous("[data-coche]").forEach((c) =>
      c.addEventListener("change", () => {
        if (c.checked) this._coches.add(c.dataset.coche);
        else this._coches.delete(c.dataset.coche);
        this._rendre();
      })
    );

    const lot = (action) => async () => {
      const choix = el("#lot-etq");
      if (choix && choix.value) await this._lot(choix.value, action);
    };
    if (el("#lot-ajout")) el("#lot-ajout").addEventListener("click", lot("ajouter"));
    if (el("#lot-retrait"))
      el("#lot-retrait").addEventListener("click", lot("retirer"));
    if (el("#lot-vider"))
      el("#lot-vider").addEventListener("click", () => {
        this._coches.clear();
        this._rendre();
      });

    tous("[data-lotdec]").forEach((b) =>
      b.addEventListener("click", () =>
        this._decider(this._cochesEnElements(), b.dataset.lotdec)
      )
    );

    tous("[data-menu]").forEach((b) =>
      b.addEventListener("click", () => {
        this._menu = this._menu === b.dataset.menu ? null : b.dataset.menu;
        this._rendre();
      })
    );

    const elementDe = (cle) => {
      const i = String(cle).indexOf("|");
      const genre = String(cle).slice(0, i);
      const nom = String(cle).slice(i + 1);
      return { genre, nom };
    };

    // La décision : un choix (vision-choix) ; la corbeille reste un bouton.
    tous("[data-decider]").forEach((c) =>
      c.addEventListener("click", () => this._decider([elementDe(c.dataset.decider)], c.dataset.decision)));
    // 2.5.12.12 : l'interrupteur bascule entre autorisé et fermé (à classer → autorisé).
    tous("[data-bascule-el]").forEach((c) =>
      c.addEventListener("click", () =>
        this._decider([elementDe(c.dataset.basculeEl)], c.dataset.actuelle === "autoriser" ? "bloquer" : "autoriser")));
    tous("[data-suite]").forEach((b) =>
      b.addEventListener("click", () => { this._limite += PAS_LIGNES; this._rendre(); }));

    // Retirer en cliquant la pastille de la ligne.
    tous(".ligne .pastille").forEach((b) =>
      b.addEventListener("click", () => {
        const ligne = b.closest(".ligne");
        const coche = ligne.querySelector("[data-coche]");
        this._basculerEtiquette(
          elementDe(coche.dataset.coche),
          b.dataset.etiquette,
          false
        );
      })
    );

    // Cocher / décocher dans le menu ouvert.
    tous("[data-poser]").forEach((b) =>
      b.addEventListener("click", () => {
        if (!this._menu) return;
        const actif = b.getAttribute("aria-pressed") === "true";
        this._basculerEtiquette(
          elementDe(this._menu),
          b.dataset.poser,
          !actif
        );
      })
    );

    if (el("#creer")) {
      const creer = async () => {
        const champ = el("#neuve");
        const nom = champ ? champ.value.trim() : "";
        if (!nom || !this._menu) return;
        const cible = elementDe(this._menu);
        await this._creerEtiquette(nom);
        await this._basculerEtiquette(cible, nom, true);
      };
      el("#creer").addEventListener("click", creer);
      el("#neuve").addEventListener("keydown", (ev) => {
        if (ev.key === "Enter") creer();
      });
    }

    if (el("#creer-globale")) {
      const creer = () => {
        const champ = el("#neuve-globale");
        if (champ && champ.value.trim()) this._creerEtiquette(champ.value);
      };
      el("#creer-globale").addEventListener("click", creer);
      el("#neuve-globale").addEventListener("keydown", (ev) => {
        if (ev.key === "Enter") creer();
      });
    }

    tous("[data-categorie]").forEach((c) =>
      c.addEventListener("change", () => this._cocherCategorie(c.dataset.categorie, c.checked))
    );

    tous("[data-supprimer]").forEach((b) =>
      b.addEventListener("click", () => this._supprimerEtiquette(b.dataset.supprimer))
    );

    tous("[data-pcnom]").forEach((b) =>
      b.addEventListener("click", () => {
        const pc = this._pcs.find((x) => x.nom === b.dataset.pcnom);
        if (!pc) return;
        const nom = b.dataset.etq;
        const actuelles = (pc.etiquettes || []).slice();
        const i = actuelles.findIndex(
          (x) => x.toLowerCase() === nom.toLowerCase()
        );
        if (i >= 0) actuelles.splice(i, 1);
        else actuelles.push(nom);
        this._etiquettesDuPc(pc, actuelles);
      })
    );

    tous("[data-maison]").forEach((b) =>
      b.addEventListener("click", () => {
        // Redire ce qui est déjà dit ne change rien : on renvoie « neutre »
        // quand on rappuie sur l'état en cours, plutôt que de ne rien faire
        // — c'est le geste qu'on attend d'un interrupteur.
        const actif = b.getAttribute("aria-pressed") === "true";
        this._maison(b.dataset.etq, actif ? "neutre" : b.dataset.maison);
      })
    );

    // Le PC qu'on vient de cocher ou de décocher : la différence avec ce qui était coché.
    tous("[data-pcs-etq]").forEach((c) => {
      let avant = JSON.parse(c.getAttribute("valeurs") || "[]");
      c.addEventListener("vision-choix", (ev) => {
        const apres = ev.detail.valeurs;
        const ajoute = apres.find((n) => !avant.includes(n));
        const retire = avant.find((n) => !apres.includes(n));
        avant = apres;
        if (ajoute !== undefined) this._basculerPc(c.dataset.pcsEtq, ajoute, true);
        else if (retire !== undefined) this._basculerPc(c.dataset.pcsEtq, retire, false);
      });
    });
    // 2.5.12.13 : un interrupteur pour tout le lieu (ouvert, bloqué, non décidé).
    tous("[data-bascule-etq]").forEach((c) =>
      c.addEventListener("click", () => this._maison(c.dataset.basculeEtq, c.dataset.actuelle === "autoriser" ? "bloquer" : "autoriser")));
  }
}

// Un seul exemplaire, même si une ancienne copie (/local) est encore chargée à côté.
const _dejaVisionEtiquettesCard = !!customElements.get("vision-etiquettes-card");
if (!_dejaVisionEtiquettesCard) customElements.define("vision-etiquettes-card", VisionEtiquettesCard);
// Ancien nom, pour les tableaux de bord écrits avant : la même carte.
if (!customElements.get("comvision-etiquettes-card")) customElements.define("comvision-etiquettes-card", class extends VisionEtiquettesCard {});

window.customCards = window.customCards || [];
if (!_dejaVisionEtiquettesCard) window.customCards.push({
  type: "vision-etiquettes-card",
  name: tr("carte_nom"),
  description: tr("carte_description"),
});

console.info(`%c VISION-ETIQUETTES-CARD %c ${VERSION} `,
  "color:#fff;background:#3f51b5;font-weight:700",
  "color:#3f51b5;background:#fff");
