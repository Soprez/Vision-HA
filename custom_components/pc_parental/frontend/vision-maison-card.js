/*
 * Vision — carte « Maison » et carte « Personne »
 *
 * mode: maison    une ligne par personne : ce que font ses appareils en ce
 *                 moment ; un clic ouvre la page de la personne.
 * mode: personne  la fiche d'une personne : du temps en plus (durée, puis
 *                 appareil), ses appareils, les siens comme ceux qu'elle
 *                 partage, et ses
 *                 catégories, en accordéon : on ouvre, on voit le contenu,
 *                 on coupe ou on rouvre. Une catégorie coupée par une règle
 *                 (maison, moyenne, planning) se montre cochée, avec la
 *                 raison, et ne se décoche pas ici.
 */
// ---- i18n:debut — les textes de cette carte, dans les six langues de Vision (fr source, en repli) ----
const TEXTES = /*i18n-json*/{
  "fr": {
    "pg_rouvrir": "Rouvrir",
    "octroi_titre": "Code de déverrouillage",
    "octroi_champ": "Code",
    "octroi_valider": "Valider",
    "octroi_annuler": "Annuler",
    "erreur_admin": "Réservé aux administrateurs de Home Assistant.",
    "erreur_loading": "Vision démarre : réessaie dans un instant.",
    "erreur_pc": "Cet appareil n'existe plus dans Vision.",
    "erreur_json": "La demande n'a pas pu être lue : réessaie.",
    "erreur_module": "Cette fonction est désactivée dans Vision.",
    "erreur_minutes": "Indique une durée.",
    "erreur_defaut": "Ce n'est pas passé : rien n'a changé, réessaie dans un instant.",
    "erreur_connexion": "Home Assistant ne répond pas : rien n'a changé, réessaie.",
    "geste_en_cours": "En cours…",
    "geste_fait": "C'est fait.",
    "erreur_charger": "Vision ne répond pas : {detail}",
    "ancienne": "mettre Vision à jour : son accueil n'est pas encore vérifié",
    "ancienne_version": "mettre Vision à jour ({version}) : son accueil n'est pas encore vérifié",
    "duree_min": "{m} min",
    "duree_h": "{h} h",
    "duree_h_min": "{h} h {mm}",
    "code_securite": "Sécurité",
    "code_moyenne": "Moyenne",
    "code_plage": "Planning",
    "code_age": "Âge",
    "code_regle": "Gestion",
    "code_lieu": "Règle du {lieu}",
    "sans_personne": "Sans personne",
    "act_inactif": "inactif depuis {duree}",
    "act_sur": "sur {quoi}",
    "etat_ouvert": "Ouvert",
    "etat_ferme": "Fermé",
    "etat_hors_ligne": "Hors ligne",
    "etat_avec": "{etat} · {detail}",
    "acces_injoignable": "Agent injoignable : rien n'est appliqué.",
    "acces_ouvert_gest": "Ouvert par un {decideur}.",
    "acces_ouvert_gest_fin": "Ouvert par un {decideur} jusqu'à {heure}.",
    "acces_ferme_gest": "Fermé par un {decideur}.",
    "acces_ferme_gest_fin": "Fermé par un {decideur} jusqu'à {heure}.",
    "acces_ferme_planning": "Fermé par le planning.",
    "acces_ferme_planning_ouvre": "Fermé par le planning, ouvre à {heure}.",
    "acces_ouvert_ferme": "Ouvert, ferme à {heure}.",
    "acces_ouvert_journee": "Ouvert toute la journée.",
    "appareil_partage": "Appareil partagé",
    "aqui_tous": "À tout le monde",
    "aqui_partage": "Partagé : {noms}",
    "moyenne_valeur": "moyenne {valeur}",
    "aucun_appareil_min": "aucun appareil",
    "aucun_inscrit": "Aucun appareil inscrit.",
    "chargement": "Chargement…",
    "badge_demandes_one": "{n} demande",
    "badge_demandes_other": "{n} demandes",
    "demande_acces": "<b>{prenom}</b> demande <b>{quoi}</b>",
    "demande_temps": "<b>{prenom}</b> demande <b>{quoi}</b> de plus",
    "demande_deverrouillage": "déverrouillage",
    "demande_site": "site : {site}",
    "motif_cite": "« {motif} »",
    "bouton_accorder": "Accorder {quoi}",
    "bouton_accorder_min": "Accorder {minutes} min",
    "bouton_non": "Non",
    "bouton_refuser": "Refuser",
    "bouton_toujours": "Toujours",
    "tuile_accessibilite": "rouvrir l'accessibilité de Vision",
    "tuile_usage": "autoriser les données d'usage",
    "tuile_vpn": "relancer le filtre des sites",
    "tuile_admin": "protéger la désinstallation",
    "tuile_batterie": "retirer l'économie de batterie pour Vision",
    "tuile_accueil": "faire de Vision l'écran d'accueil",
    "tuile_accueil_tv": "activer l'accueil forcé dans les réglages de Vision",
    "prot_hors_ligne": "Hors ligne : rien n'est appliqué pour l'instant.",
    "prot_ok": "Tout est en place.",
    "prot_a_faire": "À faire sur l'appareil : {liste}.",
    "aucun_appareil": "Aucun appareil.",
    "alerte_accessibilite": "l'accessibilité est coupée (une appli fermée met quelques secondes à se fermer)",
    "alerte_usage": "les données d'usage sont refusées (pas de temps d'écran)",
    "alerte_vpn": "le filtre des sites est arrêté",
    "alerte_admin": "la désinstallation n'est pas protégée",
    "alerte_batterie": "l'économie de batterie peut endormir Vision",
    "alerte_accueil": "Vision n'est pas l'écran d'accueil",
    "alerte_accueil_tv": "l'accueil forcé de Vision est désactivé",
    "alerte_a_verifier": "À vérifier sur l'appareil : {liste}.",
    "separateur_liste": " ; ",
    "bouton_fermer": "Fermer",
    "bouton_revenir_planning": "Revenir au planning",
    "bouton_mot": "Un mot",
    "mot_placeholder": "Un mot sur son écran…",
    "bouton_envoyer": "Envoyer",
    "rien_ferme": "Rien de fermé sur cet appareil.",
    "tout_le_monde": "Tout le monde",
    "partage_ses_regles": " (ses règles s'y appliquent)",
    "partage_legende": "Partager ne change aucune règle : l'appareil apparaît chez chacun, et chacun peut y recevoir du temps.",
    "bouton_retirer_fiche": "Retirer de la fiche de {prenom}",
    "temps_actif_titre": "Temps d'écran actif aujourd'hui",
    "lien_fermes": "Ce qui est fermé sur cet appareil ({n})",
    "lien_a_qui": "À qui est cet appareil",
    "bouton_retirer_appareil": "Retirer…",
    "retrait_titre_bouton": "Retirer cet appareil de Vision",
    "retrait_titre": "Retirer « {nom} » ?",
    "retrait_perdus": "Seront perdus :",
    "retrait_plages_one": "{n} plage horaire de cet appareil",
    "retrait_plages_other": "{n} plages horaires de cet appareil",
    "retrait_limites_one": "{n} limite de temps par appli",
    "retrait_limites_other": "{n} limites de temps par appli",
    "retrait_regles": "ses règles propres (exceptions, catégories coupées sur cet appareil)",
    "retrait_historique": "son historique (temps d'écran, sites bloqués)",
    "retrait_agent": "Vision se désinstalle de l'appareil au prochain contact et le rend libre. Un appareil éteint garde cette consigne trois mois.",
    "retrait_saisie": "Pour confirmer, tape son nom : {nom}",
    "retrait_confirmer": "Retirer",
    "retrait_fait": "« {nom} » est retiré de Vision.",
    "titre_ajouter_temps": "Ajouter du temps",
    "saisie_duree": "Durée",
    "unite_min": "min",
    "question_appareil": "Pour quel appareil ?",
    "choix_hors_ligne": " (hors ligne)",
    "bouton_aucun": "Aucun",
    "bouton_tous": "Tous",
    "bouton_donner": "Donner {duree}",
    "bouton_annuler": "Annuler",
    "bonus_jusqua": "<b>{nom}</b> ouvert jusqu'à {heure}",
    "bonus_creneau": "<b>{nom}</b> ouvert jusqu'au prochain créneau",
    "bouton_retirer": "Retirer",
    "bouton_autre": "Autre…",
    "bouton_ajouter_appareil": "Ajouter un appareil",
    "ajout_tous_deja": "Tous les appareils sont déjà sur cette fiche. Un nouvel appareil s’inscrit en y installant Vision.",
    "ajout_chez_tous": "aujourd’hui à tout le monde",
    "ajout_chez": "aujourd’hui à {prenom}",
    "ajout_chez_quelquun": "aujourd’hui à quelqu’un",
    "ajout_chez_personne": "aujourd’hui à personne",
    "bouton_le_sien": "Le sien",
    "titre_le_sien": "Il devient le sien et suit ses règles et son planning",
    "bouton_partage": "Partagé",
    "titre_partage": "Il garde ses règles, mais apparaît ici et peut recevoir du temps",
    "ajout_legende": "« Le sien » : l’appareil suit les règles et le planning de {prenom}, et quitte la personne qui l’avait. « Partagé » : il garde ses propres règles, mais apparaît aussi sur cette fiche.",
    "badge_selon_appareil": " · selon l'appareil",
    "badge_autorisee": "Autorisée",
    "compte_applis_one": "{n} appli",
    "compte_applis_other": "{n} applis",
    "compte_sites_one": "{n} site",
    "compte_sites_other": "{n} sites",
    "groupe_applis": "Applis",
    "groupe_sites": "Sites",
    "rien_classe": "Rien de classé ici pour l’instant.",
    "inter_rouvrir": "Rouvrir pour cette personne",
    "inter_couper": "Couper pour cette personne, sur tous ses appareils",
    "limite_passe": "{passe} sur {total} aujourd’hui",
    "limite_atteinte": "Fermé jusqu’à demain : {passe} sur {total} aujourd’hui",
    "limite_min_jour_titre": "Minutes par jour",
    "unite_min_jour": "min/jour",
    "limite_retirer_titre": "Retirer la limite",
    "bouton_limiter": "Limiter",
    "limite_choisir_appli": "Choisir…",
    "titre_temps_appli": "Temps par appli",
    "n_limites_one": "{n} limite",
    "n_limites_other": "{n} limites",
    "limites_legende": "Tant de minutes par jour pour {prenom}, tous ses appareils confondus. Le temps passé, l’appli se ferme jusqu’au lendemain. Une autorisation donnée en réponse à une demande la rouvre pour la durée accordée.",
    "aucune_limite": "Aucune limite pour l’instant.",
    "personne_sans_appareil": "{nom} n'a encore aucun appareil dans Vision. Pour en ajouter un : Vision → Configurer → Ajouter un appareil.",
    "moyenne_regle": "Règle de moyenne : {etat}",
    "moyenne_regle_valeur": "Règle de moyenne : {etat} ({valeur}, seuil {seuil})",
    "retour_liste": "Retour à la liste",
    "titre_appareils": "Appareils",
    "aucun_appareil_instant": "Aucun appareil pour l’instant.",
    "titre_categories": "Catégories",
    "cats_coupees_one": "{n} coupée sur {total}",
    "cats_coupees_other": "{n} coupées sur {total}",
    "cats_legende": "Couper une catégorie vaut pour {prenom} sur tous ses appareils. Ce que ferment les interdits communs, la moyenne ou le planning reste coché, avec la raison.",
    "carte_nom": "Vision — Personnes",
    "carte_description": "Une ligne par personne, et la page d'une personne : appareils, accès, catégories.",
    "titre_autorisations": "Autorisations",
    "autorisations_legende": "Pour un temps : sur un appareil, même pendant une plage ou temps d’écran épuisé, puis se referme. Exception : sur tous les appareils de {prenom}, passe la catégorie fermée mais suit les plages et le temps d’écran. Toujours disponible : sur tous ses appareils, rien ne le ferme. Les blocages de sécurité et d’âge ne se passent jamais.",
    "aut_groupe_toujours": "Toujours disponible",
    "aut_groupe_exception": "Exceptions",
    "aut_groupe_temps": "Pour un temps",
    "aucune_autorisation": "Aucune autorisation pour l’instant.",
    "aut_jusqua": "jusqu’à {heure} sur {appareil}",
    "aut_appli": "Appli",
    "aut_site": "Site",
    "aut_vers_toujours": "Toujours disponible",
    "aut_vers_exception": "En exception",
    "aut_retirer_titre": "Retirer cette autorisation",
    "aut_nom_placeholder": "Appli ou site (ex. minecraft)",
    "aut_choix_min": "{minutes} min",
    "aut_choix_h": "{h} h",
    "mode_exception": "Exception",
    "mode_toujours": "Toujours disponible",
    "aut_sur_titre": "Sur quel appareil (pour un temps)",
    "bouton_accorder_aut": "Accorder",
    "bouton_refuser": "Refuser",
    "reprise_attente": "Les trois modes s’appliqueront pleinement après la reprise des autorisations : regarde d’abord ce qui change, puis applique.",
    "bouton_reprise_simuler": "Voir ce qui change",
    "bouton_reprise_appliquer": "Appliquer les nouvelles règles",
    "bouton_reprise_annuler": "Revenir aux anciennes règles",
    "reprise_confirmer": "Appliquer les nouvelles règles d’autorisation ? L’état d’avant est sauvegardé ; tu peux revenir en arrière.",
    "reprise_annuler_confirmer": "Revenir aux anciennes règles d’autorisation ?",
    "aut_sites_lies": "Ouvre aussi : {sites}",
    "aut_lier_titre": "Ouvrir aussi ce site avec l’appli (pour tout le PC)",
    "aut_delier_titre": "Ne plus ouvrir ce site avec l’appli",
    "el_logiciels": "Applis et logiciels",
    "el_sites_seuls": "Sites sans logiciel · {n}",
    "el_services": "Services",
    "el_services_suivent": "Ses services suivent toujours le logiciel.",
    "el_sites": "Sites",
    "el_suit": "Suit {nom}",
    "el_auto": "Lié automatiquement",
    "el_attente": "Lié alors qu’il était bloqué : attend une décision",
    "el_delier": "Délier",
    "el_suivre": "Suivre {nom}",
    "el_badge_ouvert": "Ouvert",
    "el_badge_par_logiciel": "Ouvert avec {nom}",
    "el_badge_temps": "Pour un temps · jusqu’à {heure}",
    "el_badge_nomme": "Bloqué (nommé)",
    "el_badge_categorie": "Fermé par la catégorie",
    "el_badge_plage": "Fermé par une plage",
    "el_badge_temps_epuise": "Temps du jour épuisé",
    "el_badge_securite": "Fermé (sécurité ou âge)",
    "el_badge_lieu": "Fermé pour tous",
    "el_badge_liste_noire": "Signalé dangereux",
    "el_mode_choisir": "{nom} : choisir",
    "el_mode_categorie": "Suivre la catégorie",
    "el_mode_bloquer": "Bloquer",
    "el_voir_autres_one": "Voir {n} autre",
    "el_voir_autres_other": "Voir les {n} autres",
    "el_recherche": "Chercher un site, une appli, un logiciel",
    "el_recherche_rien": "Rien de trouvé.",
    "el_logiciel_de": "Logiciel : {nom}",
    "el_service_de": "Service : {nom}",
    "el_categories_de": "Catégories : {liste}",
    "el_categorie": "Catégorie",
    "el_recents_titre": "Bloqués récemment",
    "el_recents_vide": "Rien de bloqué récemment.",
    "el_tentatives_one": "{n} tentative",
    "el_tentatives_other": "{n} tentatives",
    "el_groupe_sites_one": "{n} site bloqué avec lui",
    "el_groupe_sites_other": "{n} sites bloqués avec lui",
    "el_demande_inclus": "Compris dans la réponse :",
    "el_cat_fermee": "Fermée",
    "el_cat_ouverte": "Ouverte",
    "el_recherche_court": "Chercher"
  },
  "en": {
    "pg_rouvrir": "Allow again",
    "octroi_titre": "Unlock code",
    "octroi_champ": "Code",
    "octroi_valider": "Confirm",
    "octroi_annuler": "Cancel",
    "erreur_admin": "Only Home Assistant administrators can do this.",
    "erreur_loading": "Vision is starting: try again in a moment.",
    "erreur_pc": "This device no longer exists in Vision.",
    "erreur_json": "The request could not be read: try again.",
    "erreur_module": "This feature is turned off in Vision.",
    "erreur_minutes": "Enter a duration.",
    "erreur_defaut": "That didn't work: nothing changed, try again in a moment.",
    "erreur_connexion": "Home Assistant isn't responding: nothing changed, try again.",
    "geste_en_cours": "Working…",
    "geste_fait": "Done.",
    "erreur_charger": "Vision isn't responding: {detail}",
    "ancienne": "update Vision: its home screen can't be checked yet",
    "ancienne_version": "update Vision ({version}): its home screen can't be checked yet",
    "duree_min": "{m} min",
    "duree_h": "{h} h",
    "duree_h_min": "{h} h {mm} min",
    "code_securite": "Safety",
    "code_moyenne": "Average",
    "code_plage": "Schedule",
    "code_age": "Age",
    "code_regle": "Management",
    "code_lieu": "{lieu} rule",
    "sans_personne": "No one",
    "act_inactif": "idle for {duree}",
    "act_sur": "on {quoi}",
    "etat_ouvert": "Open",
    "etat_ferme": "Closed",
    "etat_hors_ligne": "Offline",
    "etat_avec": "{etat} · {detail}",
    "acces_injoignable": "Agent unreachable: nothing is applied.",
    "acces_ouvert_gest": "Allowed by a {decideur}.",
    "acces_ouvert_gest_fin": "Allowed by a {decideur} until {heure}.",
    "acces_ferme_gest": "Blocked by a {decideur}.",
    "acces_ferme_gest_fin": "Blocked by a {decideur} until {heure}.",
    "acces_ferme_planning": "Blocked by the schedule.",
    "acces_ferme_planning_ouvre": "Blocked by the schedule, allowed at {heure}.",
    "acces_ouvert_ferme": "Allowed, blocked at {heure}.",
    "acces_ouvert_journee": "Allowed all day.",
    "appareil_partage": "Shared device",
    "aqui_tous": "Everyone's",
    "aqui_partage": "Shared: {noms}",
    "moyenne_valeur": "average {valeur}",
    "aucun_appareil_min": "no device",
    "aucun_inscrit": "No device enrolled.",
    "chargement": "Loading…",
    "badge_demandes_one": "{n} request",
    "badge_demandes_other": "{n} requests",
    "demande_acces": "<b>{prenom}</b> asks for <b>{quoi}</b>",
    "demande_temps": "<b>{prenom}</b> asks for <b>{quoi}</b> more",
    "demande_deverrouillage": "unlock",
    "demande_site": "site: {site}",
    "motif_cite": "“{motif}”",
    "bouton_accorder": "Grant {quoi}",
    "bouton_accorder_min": "Grant {minutes} min",
    "bouton_non": "No",
    "bouton_refuser": "Refuse",
    "bouton_toujours": "Always",
    "tuile_accessibilite": "turn Vision's accessibility back on",
    "tuile_usage": "allow usage data",
    "tuile_vpn": "restart the site filter",
    "tuile_admin": "protect against uninstalling",
    "tuile_batterie": "turn off battery saving for Vision",
    "tuile_accueil": "make Vision the home screen",
    "tuile_accueil_tv": "turn on the forced home screen in Vision's settings",
    "prot_hors_ligne": "Offline: nothing is applied for now.",
    "prot_ok": "Everything is in place.",
    "prot_a_faire": "To do on the device: {liste}.",
    "aucun_appareil": "No devices.",
    "alerte_accessibilite": "accessibility is off (a blocked app takes a few seconds to close)",
    "alerte_usage": "usage data is denied (no screen time)",
    "alerte_vpn": "the site filter is stopped",
    "alerte_admin": "uninstalling isn't protected",
    "alerte_batterie": "battery saving can put Vision to sleep",
    "alerte_accueil": "Vision isn't the home screen",
    "alerte_accueil_tv": "Vision's forced home screen is off",
    "alerte_a_verifier": "To check on the device: {liste}.",
    "separateur_liste": "; ",
    "bouton_fermer": "Block",
    "bouton_revenir_planning": "Back to schedule",
    "bouton_mot": "A message",
    "mot_placeholder": "A message on their screen…",
    "bouton_envoyer": "Send",
    "rien_ferme": "Nothing blocked on this device.",
    "tout_le_monde": "Everyone",
    "partage_ses_regles": " (their rules apply here)",
    "partage_legende": "Sharing doesn't change any rule: the device shows up for each person, and each can be given time on it.",
    "bouton_retirer_fiche": "Remove from {prenom}'s page",
    "temps_actif_titre": "Active screen time today",
    "lien_fermes": "What's blocked on this device ({n})",
    "lien_a_qui": "Whose device this is",
    "bouton_retirer_appareil": "Remove…",
    "retrait_titre_bouton": "Remove this device from Vision",
    "retrait_titre": "Remove “{nom}”?",
    "retrait_perdus": "Will be lost:",
    "retrait_plages_one": "{n} time slot of this device",
    "retrait_plages_other": "{n} time slots of this device",
    "retrait_limites_one": "{n} daily app limit",
    "retrait_limites_other": "{n} daily app limits",
    "retrait_regles": "its own rules (exceptions, categories turned off on this device)",
    "retrait_historique": "its history (screen time, blocked sites)",
    "retrait_agent": "Vision uninstalls itself from the device at its next contact and leaves it free. A device that is switched off keeps this instruction for three months.",
    "retrait_saisie": "To confirm, type its name: {nom}",
    "retrait_confirmer": "Remove",
    "retrait_fait": "“{nom}” has been removed from Vision.",
    "titre_ajouter_temps": "Add time",
    "saisie_duree": "Duration",
    "unite_min": "min",
    "question_appareil": "For which device?",
    "choix_hors_ligne": " (offline)",
    "bouton_aucun": "None",
    "bouton_tous": "All",
    "bouton_donner": "Give {duree}",
    "bouton_annuler": "Cancel",
    "bonus_jusqua": "<b>{nom}</b> allowed until {heure}",
    "bonus_creneau": "<b>{nom}</b> allowed until the next time slot",
    "bouton_retirer": "Remove",
    "bouton_autre": "Other…",
    "bouton_ajouter_appareil": "Add a device",
    "ajout_tous_deja": "All devices are already on this page. A new device enrolls by installing Vision on it.",
    "ajout_chez_tous": "currently everyone's",
    "ajout_chez": "currently {prenom}'s",
    "ajout_chez_quelquun": "currently someone's",
    "ajout_chez_personne": "currently no one's",
    "bouton_le_sien": "Their own",
    "titre_le_sien": "It becomes theirs and follows their rules and schedule",
    "bouton_partage": "Shared",
    "titre_partage": "It keeps its rules, but shows up here and can be given time",
    "ajout_legende": "“Their own”: the device follows {prenom}'s rules and schedule, and leaves the person who had it. “Shared”: it keeps its own rules, but also shows up on this page.",
    "badge_selon_appareil": " · depends on the device",
    "badge_autorisee": "Allowed",
    "compte_applis_one": "{n} app",
    "compte_applis_other": "{n} apps",
    "compte_sites_one": "{n} site",
    "compte_sites_other": "{n} sites",
    "groupe_applis": "Apps",
    "groupe_sites": "Sites",
    "rien_classe": "Nothing filed here yet.",
    "inter_rouvrir": "Allow again for this person",
    "inter_couper": "Block for this person, on all their devices",
    "limite_passe": "{passe} of {total} today",
    "limite_atteinte": "Blocked until tomorrow: {passe} of {total} today",
    "limite_min_jour_titre": "Minutes per day",
    "unite_min_jour": "min/day",
    "limite_retirer_titre": "Remove the limit",
    "bouton_limiter": "Limit",
    "limite_choisir_appli": "Choose…",
    "titre_temps_appli": "Time per app",
    "n_limites_one": "{n} limit",
    "n_limites_other": "{n} limits",
    "limites_legende": "So many minutes a day for {prenom}, across all their devices. Once the time is up, the app is blocked until the next day. Approving a request allows it again for the time granted.",
    "aucune_limite": "No limits yet.",
    "personne_sans_appareil": "{nom} doesn't have any device in Vision yet. To add one: Vision → Configure → Add a device.",
    "moyenne_regle": "Average rule: {etat}",
    "moyenne_regle_valeur": "Average rule: {etat} ({valeur}, threshold {seuil})",
    "retour_liste": "Back to the list",
    "titre_appareils": "Devices",
    "aucun_appareil_instant": "No devices yet.",
    "titre_categories": "Categories",
    "cats_coupees_one": "{n} blocked out of {total}",
    "cats_coupees_other": "{n} blocked out of {total}",
    "cats_legende": "Blocking a category applies to {prenom} on all their devices. What shared bans, the average or the schedule block stays checked, with the reason.",
    "carte_nom": "Vision — People",
    "carte_description": "One row per person, and a page for each person: devices, access, categories.",
    "titre_autorisations": "Permissions",
    "autorisations_legende": "For a while: on one device, even during a time slot or with screen time used up, then it closes again. Exception: on all of {prenom}’s devices, gets past a blocked category but follows time slots and screen time. Always available: on all their devices, nothing closes it. Safety and age blocks are never bypassed.",
    "aut_groupe_toujours": "Always available",
    "aut_groupe_exception": "Exceptions",
    "aut_groupe_temps": "For a while",
    "aucune_autorisation": "No permissions yet.",
    "aut_jusqua": "until {heure} on {appareil}",
    "aut_appli": "App",
    "aut_site": "Site",
    "aut_vers_toujours": "Always available",
    "aut_vers_exception": "As exception",
    "aut_retirer_titre": "Remove this permission",
    "aut_nom_placeholder": "App or site (e.g. minecraft)",
    "aut_choix_min": "{minutes} min",
    "aut_choix_h": "{h} h",
    "mode_exception": "Exception",
    "mode_toujours": "Always available",
    "aut_sur_titre": "On which device (for a while)",
    "bouton_accorder_aut": "Grant",
    "bouton_refuser": "Decline",
    "reprise_attente": "The three modes fully apply after the permissions takeover: first look at what changes, then apply.",
    "bouton_reprise_simuler": "See what changes",
    "bouton_reprise_appliquer": "Apply the new rules",
    "bouton_reprise_annuler": "Back to the previous rules",
    "reprise_confirmer": "Apply the new permission rules? The previous state is saved; you can roll back.",
    "reprise_annuler_confirmer": "Go back to the previous permission rules?",
    "aut_sites_lies": "Also opens: {sites}",
    "aut_lier_titre": "Also open this site with the app (for the whole PC)",
    "aut_delier_titre": "Stop opening this site with the app",
    "el_logiciels": "Apps and programs",
    "el_sites_seuls": "Sites without a program · {n}",
    "el_services": "Services",
    "el_services_suivent": "Its services always follow the program.",
    "el_sites": "Sites",
    "el_suit": "Follows {nom}",
    "el_auto": "Linked automatically",
    "el_attente": "Linked while blocked: waits for a decision",
    "el_delier": "Unlink",
    "el_suivre": "Follow {nom}",
    "el_badge_ouvert": "Open",
    "el_badge_par_logiciel": "Open with {nom}",
    "el_badge_temps": "For a while · until {heure}",
    "el_badge_nomme": "Blocked (named)",
    "el_badge_categorie": "Blocked by the category",
    "el_badge_plage": "Blocked by a time slot",
    "el_badge_temps_epuise": "Daily time used up",
    "el_badge_securite": "Blocked for safety or age",
    "el_badge_lieu": "Blocked for everyone",
    "el_badge_liste_noire": "Reported dangerous",
    "el_mode_choisir": "{nom}: choose",
    "el_mode_categorie": "Follow the category",
    "el_mode_bloquer": "Block",
    "el_voir_autres_one": "See {n} more",
    "el_voir_autres_other": "See {n} more",
    "el_recherche": "Search a site, app or program",
    "el_recherche_rien": "Nothing found.",
    "el_logiciel_de": "Program: {nom}",
    "el_service_de": "Service: {nom}",
    "el_categories_de": "Categories: {liste}",
    "el_categorie": "Category",
    "el_recents_titre": "Recently blocked",
    "el_recents_vide": "Nothing blocked recently.",
    "el_tentatives_one": "{n} attempt",
    "el_tentatives_other": "{n} attempts",
    "el_groupe_sites_one": "{n} site blocked with it",
    "el_groupe_sites_other": "{n} sites blocked with it",
    "el_demande_inclus": "Included in the answer:",
    "el_cat_fermee": "Blocked",
    "el_cat_ouverte": "Open",
    "el_recherche_court": "Search"
  },
  "de": {
    "pg_rouvrir": "Wieder freigeben",
    "octroi_titre": "Entsperrcode",
    "octroi_champ": "Code",
    "octroi_valider": "Bestätigen",
    "octroi_annuler": "Abbrechen",
    "erreur_admin": "Nur für Home Assistant-Administratoren.",
    "erreur_loading": "Vision startet gerade: versuch es gleich noch einmal.",
    "erreur_pc": "Dieses Gerät gibt es in Vision nicht mehr.",
    "erreur_json": "Die Anfrage konnte nicht gelesen werden: versuch es noch einmal.",
    "erreur_module": "Diese Funktion ist in Vision ausgeschaltet.",
    "erreur_minutes": "Gib eine Dauer an.",
    "erreur_defaut": "Das hat nicht geklappt: nichts wurde geändert, versuch es gleich noch einmal.",
    "erreur_connexion": "Home Assistant antwortet nicht: nichts wurde geändert, versuch es noch einmal.",
    "geste_en_cours": "Wird ausgeführt…",
    "geste_fait": "Erledigt.",
    "erreur_charger": "Vision antwortet nicht: {detail}",
    "ancienne": "Vision aktualisieren: sein Startbildschirm wird noch nicht geprüft",
    "ancienne_version": "Vision aktualisieren ({version}): sein Startbildschirm wird noch nicht geprüft",
    "duree_min": "{m} Min.",
    "duree_h": "{h} Std.",
    "duree_h_min": "{h} Std. {mm} Min.",
    "code_securite": "Sicherheit",
    "code_moyenne": "Durchschnitt",
    "code_plage": "Zeitplan",
    "code_age": "Alter",
    "code_regle": "Verwaltung",
    "code_lieu": "Regel von {lieu}",
    "sans_personne": "Niemandem zugeordnet",
    "act_inactif": "seit {duree} inaktiv",
    "act_sur": "in {quoi}",
    "etat_ouvert": "Offen",
    "etat_ferme": "Geschlossen",
    "etat_hors_ligne": "Offline",
    "etat_avec": "{etat} · {detail}",
    "acces_injoignable": "Agent nicht erreichbar: nichts wird angewendet.",
    "acces_ouvert_gest": "Von einem {decideur} freigegeben.",
    "acces_ouvert_gest_fin": "Von einem {decideur} freigegeben bis {heure}.",
    "acces_ferme_gest": "Von einem {decideur} gesperrt.",
    "acces_ferme_gest_fin": "Von einem {decideur} gesperrt bis {heure}.",
    "acces_ferme_planning": "Durch den Zeitplan gesperrt.",
    "acces_ferme_planning_ouvre": "Durch den Zeitplan gesperrt, frei ab {heure}.",
    "acces_ouvert_ferme": "Freigegeben, gesperrt ab {heure}.",
    "acces_ouvert_journee": "Den ganzen Tag freigegeben.",
    "appareil_partage": "Gemeinsames Gerät",
    "aqui_tous": "Gehört allen",
    "aqui_partage": "Geteilt: {noms}",
    "moyenne_valeur": "Durchschnitt {valeur}",
    "aucun_appareil_min": "kein Gerät",
    "aucun_inscrit": "Kein Gerät registriert.",
    "chargement": "Wird geladen…",
    "badge_demandes_one": "{n} Anfrage",
    "badge_demandes_other": "{n} Anfragen",
    "demande_acces": "<b>{prenom}</b> fragt nach <b>{quoi}</b>",
    "demande_temps": "<b>{prenom}</b> fragt nach <b>{quoi}</b> mehr",
    "demande_deverrouillage": "Entsperren",
    "demande_site": "Website: {site}",
    "motif_cite": "„{motif}“",
    "bouton_accorder": "{quoi} gewähren",
    "bouton_accorder_min": "{minutes} Min. gewähren",
    "bouton_non": "Nein",
    "bouton_refuser": "Ablehnen",
    "bouton_toujours": "Immer",
    "tuile_accessibilite": "die Bedienungshilfe von Vision wieder einschalten",
    "tuile_usage": "Nutzungsdaten erlauben",
    "tuile_vpn": "den Website-Filter neu starten",
    "tuile_admin": "die Deinstallation schützen",
    "tuile_batterie": "das Energiesparen für Vision ausschalten",
    "tuile_accueil": "Vision zum Startbildschirm machen",
    "tuile_accueil_tv": "den erzwungenen Startbildschirm in den Vision-Einstellungen einschalten",
    "prot_hors_ligne": "Offline: im Moment wird nichts angewendet.",
    "prot_ok": "Alles ist eingerichtet.",
    "prot_a_faire": "Auf dem Gerät zu erledigen: {liste}.",
    "aucun_appareil": "Keine Geräte.",
    "alerte_accessibilite": "die Bedienungshilfe ist aus (eine gesperrte App braucht ein paar Sekunden, bis sie sich schließt)",
    "alerte_usage": "Nutzungsdaten sind verweigert (keine Bildschirmzeit)",
    "alerte_vpn": "der Website-Filter ist gestoppt",
    "alerte_admin": "die Deinstallation ist nicht geschützt",
    "alerte_batterie": "das Energiesparen kann Vision schlafen legen",
    "alerte_accueil": "Vision ist nicht der Startbildschirm",
    "alerte_accueil_tv": "der erzwungene Startbildschirm von Vision ist aus",
    "alerte_a_verifier": "Auf dem Gerät prüfen: {liste}.",
    "separateur_liste": "; ",
    "bouton_fermer": "Sperren",
    "bouton_revenir_planning": "Zurück zum Zeitplan",
    "bouton_mot": "Eine Nachricht",
    "mot_placeholder": "Eine Nachricht auf den Bildschirm…",
    "bouton_envoyer": "Senden",
    "rien_ferme": "Auf diesem Gerät ist nichts gesperrt.",
    "tout_le_monde": "Alle",
    "partage_ses_regles": " (die eigenen Regeln gelten hier)",
    "partage_legende": "Teilen ändert keine Regel: Das Gerät erscheint bei jeder Person, und jede kann darauf Zeit bekommen.",
    "bouton_retirer_fiche": "Von der Seite von {prenom} entfernen",
    "temps_actif_titre": "Aktive Bildschirmzeit heute",
    "lien_fermes": "Was auf diesem Gerät gesperrt ist ({n})",
    "lien_a_qui": "Wem dieses Gerät gehört",
    "bouton_retirer_appareil": "Entfernen…",
    "retrait_titre_bouton": "Dieses Gerät aus Vision entfernen",
    "retrait_titre": "„{nom}“ entfernen?",
    "retrait_perdus": "Verloren gehen:",
    "retrait_plages_one": "{n} Zeitfenster dieses Geräts",
    "retrait_plages_other": "{n} Zeitfenster dieses Geräts",
    "retrait_limites_one": "{n} tägliches App-Limit",
    "retrait_limites_other": "{n} tägliche App-Limits",
    "retrait_regles": "seine eigenen Regeln (Ausnahmen, auf diesem Gerät gesperrte Kategorien)",
    "retrait_historique": "sein Verlauf (Bildschirmzeit, gesperrte Seiten)",
    "retrait_agent": "Vision deinstalliert sich beim nächsten Kontakt vom Gerät und gibt es frei. Ein ausgeschaltetes Gerät behält diese Anweisung drei Monate lang.",
    "retrait_saisie": "Zur Bestätigung den Namen eingeben: {nom}",
    "retrait_confirmer": "Entfernen",
    "retrait_fait": "„{nom}“ wurde aus Vision entfernt.",
    "titre_ajouter_temps": "Zeit hinzufügen",
    "saisie_duree": "Dauer",
    "unite_min": "Min.",
    "question_appareil": "Für welches Gerät?",
    "choix_hors_ligne": " (offline)",
    "bouton_aucun": "Keines",
    "bouton_tous": "Alle",
    "bouton_donner": "{duree} geben",
    "bouton_annuler": "Abbrechen",
    "bonus_jusqua": "<b>{nom}</b> freigegeben bis {heure}",
    "bonus_creneau": "<b>{nom}</b> freigegeben bis zum nächsten Zeitfenster",
    "bouton_retirer": "Entfernen",
    "bouton_autre": "Andere…",
    "bouton_ajouter_appareil": "Gerät hinzufügen",
    "ajout_tous_deja": "Alle Geräte sind schon auf dieser Seite. Ein neues Gerät wird registriert, indem man Vision darauf installiert.",
    "ajout_chez_tous": "derzeit für alle",
    "ajout_chez": "derzeit von {prenom}",
    "ajout_chez_quelquun": "derzeit von jemandem",
    "ajout_chez_personne": "derzeit niemandem zugeordnet",
    "bouton_le_sien": "Eigenes",
    "titre_le_sien": "Es wird das eigene Gerät und folgt den eigenen Regeln und dem Zeitplan",
    "bouton_partage": "Geteilt",
    "titre_partage": "Es behält seine Regeln, erscheint aber hier und kann Zeit bekommen",
    "ajout_legende": "„Eigenes“: Das Gerät folgt den Regeln und dem Zeitplan von {prenom} und verlässt die Person, die es hatte. „Geteilt“: Es behält seine eigenen Regeln, erscheint aber auch auf dieser Seite.",
    "badge_selon_appareil": " · je nach Gerät",
    "badge_autorisee": "Erlaubt",
    "compte_applis_one": "{n} App",
    "compte_applis_other": "{n} Apps",
    "compte_sites_one": "{n} Website",
    "compte_sites_other": "{n} Websites",
    "groupe_applis": "Apps",
    "groupe_sites": "Websites",
    "rien_classe": "Hier ist noch nichts eingeordnet.",
    "inter_rouvrir": "Für diese Person wieder freigeben",
    "inter_couper": "Für diese Person sperren, auf allen ihren Geräten",
    "limite_passe": "{passe} von {total} heute",
    "limite_atteinte": "Gesperrt bis morgen: {passe} von {total} heute",
    "limite_min_jour_titre": "Minuten pro Tag",
    "unite_min_jour": "Min./Tag",
    "limite_retirer_titre": "Limit entfernen",
    "bouton_limiter": "Begrenzen",
    "limite_choisir_appli": "Auswählen…",
    "titre_temps_appli": "Zeit pro App",
    "n_limites_one": "{n} Limit",
    "n_limites_other": "{n} Limits",
    "limites_legende": "So viele Minuten pro Tag für {prenom}, auf allen Geräten zusammen. Ist die Zeit um, wird die App bis zum nächsten Tag gesperrt. Eine Freigabe als Antwort auf eine Anfrage gibt sie für die gewährte Dauer wieder frei.",
    "aucune_limite": "Noch keine Limits.",
    "personne_sans_appareil": "{nom} hat noch kein Gerät in Vision. Um eines hinzuzufügen: Vision → Konfigurieren → Gerät hinzufügen.",
    "moyenne_regle": "Durchschnittsregel: {etat}",
    "moyenne_regle_valeur": "Durchschnittsregel: {etat} ({valeur}, Schwelle {seuil})",
    "retour_liste": "Zurück zur Liste",
    "titre_appareils": "Geräte",
    "aucun_appareil_instant": "Noch keine Geräte.",
    "titre_categories": "Kategorien",
    "cats_coupees_one": "{n} von {total} gesperrt",
    "cats_coupees_other": "{n} von {total} gesperrt",
    "cats_legende": "Eine Kategorie zu sperren gilt für {prenom} auf allen Geräten. Was gemeinsame Verbote, der Durchschnitt oder der Zeitplan sperren, bleibt angehakt, mit dem Grund.",
    "carte_nom": "Vision — Personen",
    "carte_description": "Eine Zeile pro Person und die Seite einer Person: Geräte, Zugang, Kategorien.",
    "titre_autorisations": "Erlaubnisse",
    "autorisations_legende": "Für eine Weile: auf einem Gerät, auch während eines Zeitfensters oder bei aufgebrauchter Bildschirmzeit, danach wieder zu. Ausnahme: auf allen Geräten von {prenom}, kommt an der gesperrten Kategorie vorbei, folgt aber Zeitfenstern und Bildschirmzeit. Immer verfügbar: auf allen Geräten, nichts schließt es. Sperren für Sicherheit und Alter gelten immer.",
    "aut_groupe_toujours": "Immer verfügbar",
    "aut_groupe_exception": "Ausnahmen",
    "aut_groupe_temps": "Für eine Weile",
    "aucune_autorisation": "Noch keine Erlaubnisse.",
    "aut_jusqua": "bis {heure} auf {appareil}",
    "aut_appli": "App",
    "aut_site": "Website",
    "aut_vers_toujours": "Immer verfügbar",
    "aut_vers_exception": "Als Ausnahme",
    "aut_retirer_titre": "Diese Erlaubnis entfernen",
    "aut_nom_placeholder": "App oder Website (z. B. minecraft)",
    "aut_choix_min": "{minutes} Min.",
    "aut_choix_h": "{h} Std.",
    "mode_exception": "Ausnahme",
    "mode_toujours": "Immer verfügbar",
    "aut_sur_titre": "Auf welchem Gerät (für eine Weile)",
    "bouton_accorder_aut": "Erlauben",
    "bouton_refuser": "Ablehnen",
    "reprise_attente": "Die drei Modi gelten voll nach der Übernahme der Erlaubnisse: zuerst ansehen, was sich ändert, dann anwenden.",
    "bouton_reprise_simuler": "Ansehen, was sich ändert",
    "bouton_reprise_appliquer": "Neue Regeln anwenden",
    "bouton_reprise_annuler": "Zurück zu den vorherigen Regeln",
    "reprise_confirmer": "Die neuen Erlaubnisregeln anwenden? Der vorherige Zustand wird gesichert; du kannst zurück.",
    "reprise_annuler_confirmer": "Zu den vorherigen Erlaubnisregeln zurückkehren?",
    "aut_sites_lies": "Öffnet auch: {sites}",
    "aut_lier_titre": "Diese Website mit der App mitöffnen (für den ganzen PC)",
    "aut_delier_titre": "Diese Website nicht mehr mit der App öffnen",
    "el_logiciels": "Apps und Programme",
    "el_sites_seuls": "Websites ohne Programm · {n}",
    "el_services": "Dienste",
    "el_services_suivent": "Seine Dienste folgen immer dem Programm.",
    "el_sites": "Websites",
    "el_suit": "Folgt {nom}",
    "el_auto": "Automatisch verknüpft",
    "el_attente": "Verknüpft, als es gesperrt war: wartet auf eine Entscheidung",
    "el_delier": "Lösen",
    "el_suivre": "{nom} folgen",
    "el_badge_ouvert": "Offen",
    "el_badge_par_logiciel": "Offen mit {nom}",
    "el_badge_temps": "Eine Weile · bis {heure}",
    "el_badge_nomme": "Gesperrt (namentlich)",
    "el_badge_categorie": "Durch die Kategorie gesperrt",
    "el_badge_plage": "Durch ein Zeitfenster gesperrt",
    "el_badge_temps_epuise": "Tageszeit aufgebraucht",
    "el_badge_securite": "Gesperrt (Sicherheit oder Alter)",
    "el_badge_lieu": "Für alle gesperrt",
    "el_badge_liste_noire": "Als gefährlich gemeldet",
    "el_mode_choisir": "{nom}: auswählen",
    "el_mode_categorie": "Der Kategorie folgen",
    "el_mode_bloquer": "Sperren",
    "el_voir_autres_one": "{n} weitere anzeigen",
    "el_voir_autres_other": "{n} weitere anzeigen",
    "el_recherche": "Website, App oder Programm suchen",
    "el_recherche_rien": "Nichts gefunden.",
    "el_logiciel_de": "Programm: {nom}",
    "el_service_de": "Dienst: {nom}",
    "el_categories_de": "Kategorien: {liste}",
    "el_categorie": "Kategorie",
    "el_recents_titre": "Kürzlich gesperrt",
    "el_recents_vide": "Kürzlich nichts gesperrt.",
    "el_tentatives_one": "{n} Versuch",
    "el_tentatives_other": "{n} Versuche",
    "el_groupe_sites_one": "{n} Website damit gesperrt",
    "el_groupe_sites_other": "{n} Websites damit gesperrt",
    "el_demande_inclus": "In der Antwort enthalten:",
    "el_cat_fermee": "Gesperrt",
    "el_cat_ouverte": "Offen",
    "el_recherche_court": "Suchen"
  },
  "es": {
    "pg_rouvrir": "Volver a permitir",
    "octroi_titre": "Código de desbloqueo",
    "octroi_champ": "Código",
    "octroi_valider": "Confirmar",
    "octroi_annuler": "Cancelar",
    "erreur_admin": "Solo para administradores de Home Assistant.",
    "erreur_loading": "Vision se está iniciando: inténtalo de nuevo en un momento.",
    "erreur_pc": "Este dispositivo ya no existe en Vision.",
    "erreur_json": "No se pudo leer la solicitud: inténtalo de nuevo.",
    "erreur_module": "Esta función está desactivada en Vision.",
    "erreur_minutes": "Indica una duración.",
    "erreur_defaut": "No ha funcionado: no ha cambiado nada, inténtalo de nuevo en un momento.",
    "erreur_connexion": "Home Assistant no responde: no ha cambiado nada, inténtalo de nuevo.",
    "geste_en_cours": "En curso…",
    "geste_fait": "Hecho.",
    "erreur_charger": "Vision no responde: {detail}",
    "ancienne": "actualizar Vision: su pantalla de inicio aún no se comprueba",
    "ancienne_version": "actualizar Vision ({version}): su pantalla de inicio aún no se comprueba",
    "duree_min": "{m} min",
    "duree_h": "{h} h",
    "duree_h_min": "{h} h {mm} min",
    "code_securite": "Seguridad",
    "code_moyenne": "Media",
    "code_plage": "Horario",
    "code_age": "Edad",
    "code_regle": "Gestión",
    "code_lieu": "Regla de {lieu}",
    "sans_personne": "Sin persona",
    "act_inactif": "inactivo desde hace {duree}",
    "act_sur": "en {quoi}",
    "etat_ouvert": "Abierto",
    "etat_ferme": "Cerrado",
    "etat_hors_ligne": "Sin conexión",
    "etat_avec": "{etat} · {detail}",
    "acces_injoignable": "Agente inaccesible: no se aplica nada.",
    "acces_ouvert_gest": "Permitido por un {decideur}.",
    "acces_ouvert_gest_fin": "Permitido por un {decideur} hasta las {heure}.",
    "acces_ferme_gest": "Bloqueado por un {decideur}.",
    "acces_ferme_gest_fin": "Bloqueado por un {decideur} hasta las {heure}.",
    "acces_ferme_planning": "Bloqueado por el horario.",
    "acces_ferme_planning_ouvre": "Bloqueado por el horario, se permite a las {heure}.",
    "acces_ouvert_ferme": "Permitido, se bloquea a las {heure}.",
    "acces_ouvert_journee": "Permitido todo el día.",
    "appareil_partage": "Dispositivo compartido",
    "aqui_tous": "De todos",
    "aqui_partage": "Compartido: {noms}",
    "moyenne_valeur": "media {valeur}",
    "aucun_appareil_min": "ningún dispositivo",
    "aucun_inscrit": "Ningún dispositivo registrado.",
    "chargement": "Cargando…",
    "badge_demandes_one": "{n} solicitud",
    "badge_demandes_other": "{n} solicitudes",
    "demande_acces": "<b>{prenom}</b> pide <b>{quoi}</b>",
    "demande_temps": "<b>{prenom}</b> pide <b>{quoi}</b> más",
    "demande_deverrouillage": "desbloqueo",
    "demande_site": "sitio: {site}",
    "motif_cite": "«{motif}»",
    "bouton_accorder": "Conceder {quoi}",
    "bouton_accorder_min": "Conceder {minutes} min",
    "bouton_non": "No",
    "bouton_refuser": "Rechazar",
    "bouton_toujours": "Siempre",
    "tuile_accessibilite": "volver a activar la accesibilidad de Vision",
    "tuile_usage": "permitir los datos de uso",
    "tuile_vpn": "reiniciar el filtro de sitios",
    "tuile_admin": "proteger la desinstalación",
    "tuile_batterie": "quitar el ahorro de batería para Vision",
    "tuile_accueil": "hacer de Vision la pantalla de inicio",
    "tuile_accueil_tv": "activar la pantalla de inicio forzada en los ajustes de Vision",
    "prot_hors_ligne": "Sin conexión: por ahora no se aplica nada.",
    "prot_ok": "Todo está en orden.",
    "prot_a_faire": "Pendiente en el dispositivo: {liste}.",
    "aucun_appareil": "Ningún dispositivo.",
    "alerte_accessibilite": "la accesibilidad está desactivada (una app bloqueada tarda unos segundos en cerrarse)",
    "alerte_usage": "los datos de uso están denegados (sin tiempo de pantalla)",
    "alerte_vpn": "el filtro de sitios está detenido",
    "alerte_admin": "la desinstalación no está protegida",
    "alerte_batterie": "el ahorro de batería puede dormir a Vision",
    "alerte_accueil": "Vision no es la pantalla de inicio",
    "alerte_accueil_tv": "la pantalla de inicio forzada de Vision está desactivada",
    "alerte_a_verifier": "Revisar en el dispositivo: {liste}.",
    "separateur_liste": "; ",
    "bouton_fermer": "Bloquear",
    "bouton_revenir_planning": "Volver al horario",
    "bouton_mot": "Un mensaje",
    "mot_placeholder": "Un mensaje en su pantalla…",
    "bouton_envoyer": "Enviar",
    "rien_ferme": "Nada bloqueado en este dispositivo.",
    "tout_le_monde": "Todos",
    "partage_ses_regles": " (aquí se aplican sus reglas)",
    "partage_legende": "Compartir no cambia ninguna regla: el dispositivo aparece para cada persona y cada una puede recibir tiempo en él.",
    "bouton_retirer_fiche": "Quitar de la ficha de {prenom}",
    "temps_actif_titre": "Tiempo de pantalla activo hoy",
    "lien_fermes": "Lo que está bloqueado en este dispositivo ({n})",
    "lien_a_qui": "De quién es este dispositivo",
    "bouton_retirer_appareil": "Quitar…",
    "retrait_titre_bouton": "Quitar este dispositivo de Vision",
    "retrait_titre": "¿Quitar «{nom}»?",
    "retrait_perdus": "Se perderá:",
    "retrait_plages_one": "{n} franja horaria de este dispositivo",
    "retrait_plages_other": "{n} franjas horarias de este dispositivo",
    "retrait_limites_one": "{n} límite diario de app",
    "retrait_limites_other": "{n} límites diarios de apps",
    "retrait_regles": "sus propias reglas (excepciones, categorías cortadas en este dispositivo)",
    "retrait_historique": "su historial (tiempo de pantalla, sitios bloqueados)",
    "retrait_agent": "Vision se desinstala del dispositivo en el próximo contacto y lo deja libre. Un dispositivo apagado conserva esta orden tres meses.",
    "retrait_saisie": "Para confirmar, escribe su nombre: {nom}",
    "retrait_confirmer": "Quitar",
    "retrait_fait": "«{nom}» se ha quitado de Vision.",
    "titre_ajouter_temps": "Añadir tiempo",
    "saisie_duree": "Duración",
    "unite_min": "min",
    "question_appareil": "¿Para qué dispositivo?",
    "choix_hors_ligne": " (sin conexión)",
    "bouton_aucun": "Ninguno",
    "bouton_tous": "Todos",
    "bouton_donner": "Dar {duree}",
    "bouton_annuler": "Cancelar",
    "bonus_jusqua": "<b>{nom}</b> permitido hasta las {heure}",
    "bonus_creneau": "<b>{nom}</b> permitido hasta la próxima franja horaria",
    "bouton_retirer": "Quitar",
    "bouton_autre": "Otro…",
    "bouton_ajouter_appareil": "Añadir un dispositivo",
    "ajout_tous_deja": "Todos los dispositivos ya están en esta ficha. Un dispositivo nuevo se registra instalando Vision en él.",
    "ajout_chez_tous": "ahora de todos",
    "ajout_chez": "ahora de {prenom}",
    "ajout_chez_quelquun": "ahora de alguien",
    "ajout_chez_personne": "ahora de nadie",
    "bouton_le_sien": "Suyo",
    "titre_le_sien": "Pasa a ser suyo y sigue sus reglas y su horario",
    "bouton_partage": "Compartido",
    "titre_partage": "Mantiene sus reglas, pero aparece aquí y puede recibir tiempo",
    "ajout_legende": "«Suyo»: el dispositivo sigue las reglas y el horario de {prenom}, y deja a la persona que lo tenía. «Compartido»: mantiene sus propias reglas, pero también aparece en esta ficha.",
    "badge_selon_appareil": " · según el dispositivo",
    "badge_autorisee": "Permitida",
    "compte_applis_one": "{n} app",
    "compte_applis_other": "{n} apps",
    "compte_sites_one": "{n} sitio",
    "compte_sites_other": "{n} sitios",
    "groupe_applis": "Apps",
    "groupe_sites": "Sitios",
    "rien_classe": "Aún no hay nada clasificado aquí.",
    "inter_rouvrir": "Volver a permitir para esta persona",
    "inter_couper": "Bloquear para esta persona, en todos sus dispositivos",
    "limite_passe": "{passe} de {total} hoy",
    "limite_atteinte": "Bloqueado hasta mañana: {passe} de {total} hoy",
    "limite_min_jour_titre": "Minutos al día",
    "unite_min_jour": "min/día",
    "limite_retirer_titre": "Quitar el límite",
    "bouton_limiter": "Limitar",
    "limite_choisir_appli": "Elegir…",
    "titre_temps_appli": "Tiempo por app",
    "n_limites_one": "{n} límite",
    "n_limites_other": "{n} límites",
    "limites_legende": "Tantos minutos al día para {prenom}, sumando todos sus dispositivos. Cuando se acaba el tiempo, la app se bloquea hasta el día siguiente. Una autorización dada en respuesta a una solicitud la vuelve a permitir durante el tiempo concedido.",
    "aucune_limite": "Aún no hay límites.",
    "personne_sans_appareil": "{nom} aún no tiene ningún dispositivo en Vision. Para añadir uno: Vision → Configurar → Añadir un dispositivo.",
    "moyenne_regle": "Regla de la media: {etat}",
    "moyenne_regle_valeur": "Regla de la media: {etat} ({valeur}, umbral {seuil})",
    "retour_liste": "Volver a la lista",
    "titre_appareils": "Dispositivos",
    "aucun_appareil_instant": "Aún no hay dispositivos.",
    "titre_categories": "Categorías",
    "cats_coupees_one": "{n} bloqueada de {total}",
    "cats_coupees_other": "{n} bloqueadas de {total}",
    "cats_legende": "Bloquear una categoría vale para {prenom} en todos sus dispositivos. Lo que bloquean las prohibiciones comunes, la media o el horario sigue marcado, con el motivo.",
    "carte_nom": "Vision — Personas",
    "carte_description": "Una fila por persona y la página de cada persona: dispositivos, acceso, categorías.",
    "titre_autorisations": "Autorizaciones",
    "autorisations_legende": "Por un tiempo: en un aparato, incluso durante una franja o con el tiempo de pantalla agotado, y luego se cierra. Excepción: en todos los aparatos de {prenom}, salta la categoría cerrada pero sigue las franjas y el tiempo de pantalla. Siempre disponible: en todos sus aparatos, nada lo cierra. Los bloqueos de seguridad y de edad no se saltan nunca.",
    "aut_groupe_toujours": "Siempre disponible",
    "aut_groupe_exception": "Excepciones",
    "aut_groupe_temps": "Por un tiempo",
    "aucune_autorisation": "Ninguna autorización por ahora.",
    "aut_jusqua": "hasta las {heure} en {appareil}",
    "aut_appli": "Aplicación",
    "aut_site": "Sitio",
    "aut_vers_toujours": "Siempre disponible",
    "aut_vers_exception": "Como excepción",
    "aut_retirer_titre": "Quitar esta autorización",
    "aut_nom_placeholder": "Aplicación o sitio (p. ej. minecraft)",
    "aut_choix_min": "{minutes} min",
    "aut_choix_h": "{h} h",
    "mode_exception": "Excepción",
    "mode_toujours": "Siempre disponible",
    "aut_sur_titre": "En qué aparato (por un tiempo)",
    "bouton_accorder_aut": "Conceder",
    "bouton_refuser": "Rechazar",
    "reprise_attente": "Los tres modos se aplicarán por completo tras el traspaso de las autorizaciones: mira primero qué cambia y luego aplica.",
    "bouton_reprise_simuler": "Ver qué cambia",
    "bouton_reprise_appliquer": "Aplicar las nuevas reglas",
    "bouton_reprise_annuler": "Volver a las reglas anteriores",
    "reprise_confirmer": "¿Aplicar las nuevas reglas de autorización? Se guarda el estado anterior; puedes volver atrás.",
    "reprise_annuler_confirmer": "¿Volver a las reglas de autorización anteriores?",
    "aut_sites_lies": "También abre: {sites}",
    "aut_lier_titre": "Abrir también este sitio con la aplicación (para todo el PC)",
    "aut_delier_titre": "Dejar de abrir este sitio con la aplicación",
    "el_logiciels": "Apps y programas",
    "el_sites_seuls": "Sitios sin programa · {n}",
    "el_services": "Servicios",
    "el_services_suivent": "Sus servicios siempre siguen al programa.",
    "el_sites": "Sitios",
    "el_suit": "Sigue a {nom}",
    "el_auto": "Vinculado automáticamente",
    "el_attente": "Vinculado cuando estaba bloqueado: espera una decisión",
    "el_delier": "Desvincular",
    "el_suivre": "Seguir a {nom}",
    "el_badge_ouvert": "Abierto",
    "el_badge_par_logiciel": "Abierto con {nom}",
    "el_badge_temps": "Por un tiempo · hasta las {heure}",
    "el_badge_nomme": "Bloqueado (por nombre)",
    "el_badge_categorie": "Cerrado por la categoría",
    "el_badge_plage": "Cerrado por una franja",
    "el_badge_temps_epuise": "Tiempo del día agotado",
    "el_badge_securite": "Cerrado (seguridad o edad)",
    "el_badge_lieu": "Cerrado para todos",
    "el_badge_liste_noire": "Señalado como peligroso",
    "el_mode_choisir": "{nom}: elegir",
    "el_mode_categorie": "Seguir la categoría",
    "el_mode_bloquer": "Bloquear",
    "el_voir_autres_one": "Ver {n} más",
    "el_voir_autres_other": "Ver {n} más",
    "el_recherche": "Buscar un sitio, una app o un programa",
    "el_recherche_rien": "No se encontró nada.",
    "el_logiciel_de": "Programa: {nom}",
    "el_service_de": "Servicio: {nom}",
    "el_categories_de": "Categorías: {liste}",
    "el_categorie": "Categoría",
    "el_recents_titre": "Bloqueados recientemente",
    "el_recents_vide": "Nada bloqueado recientemente.",
    "el_tentatives_one": "{n} intento",
    "el_tentatives_other": "{n} intentos",
    "el_groupe_sites_one": "{n} sitio bloqueado con él",
    "el_groupe_sites_other": "{n} sitios bloqueados con él",
    "el_demande_inclus": "Incluido en la respuesta:",
    "el_cat_fermee": "Cerrada",
    "el_cat_ouverte": "Abierta",
    "el_recherche_court": "Buscar"
  },
  "it": {
    "pg_rouvrir": "Consenti di nuovo",
    "octroi_titre": "Codice di sblocco",
    "octroi_champ": "Codice",
    "octroi_valider": "Conferma",
    "octroi_annuler": "Annulla",
    "erreur_admin": "Riservato agli amministratori di Home Assistant.",
    "erreur_loading": "Vision si sta avviando: riprova tra un momento.",
    "erreur_pc": "Questo dispositivo non esiste più in Vision.",
    "erreur_json": "Non è stato possibile leggere la richiesta: riprova.",
    "erreur_module": "Questa funzione è disattivata in Vision.",
    "erreur_minutes": "Indica una durata.",
    "erreur_defaut": "Non è andata: non è cambiato nulla, riprova tra un momento.",
    "erreur_connexion": "Home Assistant non risponde: non è cambiato nulla, riprova.",
    "geste_en_cours": "In corso…",
    "geste_fait": "Fatto.",
    "erreur_charger": "Vision non risponde: {detail}",
    "ancienne": "aggiornare Vision: la sua schermata Home non è ancora verificata",
    "ancienne_version": "aggiornare Vision ({version}): la sua schermata Home non è ancora verificata",
    "duree_min": "{m} min",
    "duree_h": "{h} h",
    "duree_h_min": "{h} h {mm} min",
    "code_securite": "Sicurezza",
    "code_moyenne": "Media",
    "code_plage": "Programma",
    "code_age": "Età",
    "code_regle": "Gestione",
    "code_lieu": "Regola di {lieu}",
    "sans_personne": "Senza persona",
    "act_inactif": "inattivo da {duree}",
    "act_sur": "su {quoi}",
    "etat_ouvert": "Aperto",
    "etat_ferme": "Chiuso",
    "etat_hors_ligne": "Offline",
    "etat_avec": "{etat} · {detail}",
    "acces_injoignable": "Agente non raggiungibile: non viene applicato nulla.",
    "acces_ouvert_gest": "Consentito da un {decideur}.",
    "acces_ouvert_gest_fin": "Consentito da un {decideur} fino alle {heure}.",
    "acces_ferme_gest": "Bloccato da un {decideur}.",
    "acces_ferme_gest_fin": "Bloccato da un {decideur} fino alle {heure}.",
    "acces_ferme_planning": "Bloccato dal programma.",
    "acces_ferme_planning_ouvre": "Bloccato dal programma, consentito alle {heure}.",
    "acces_ouvert_ferme": "Consentito, si blocca alle {heure}.",
    "acces_ouvert_journee": "Consentito tutto il giorno.",
    "appareil_partage": "Dispositivo condiviso",
    "aqui_tous": "Di tutti",
    "aqui_partage": "Condiviso: {noms}",
    "moyenne_valeur": "media {valeur}",
    "aucun_appareil_min": "nessun dispositivo",
    "aucun_inscrit": "Nessun dispositivo registrato.",
    "chargement": "Caricamento…",
    "badge_demandes_one": "{n} richiesta",
    "badge_demandes_other": "{n} richieste",
    "demande_acces": "<b>{prenom}</b> chiede <b>{quoi}</b>",
    "demande_temps": "<b>{prenom}</b> chiede <b>{quoi}</b> in più",
    "demande_deverrouillage": "sblocco",
    "demande_site": "sito: {site}",
    "motif_cite": "«{motif}»",
    "bouton_accorder": "Concedi {quoi}",
    "bouton_accorder_min": "Concedi {minutes} min",
    "bouton_non": "No",
    "bouton_refuser": "Rifiuta",
    "bouton_toujours": "Sempre",
    "tuile_accessibilite": "riattivare l'accessibilità di Vision",
    "tuile_usage": "consentire i dati di utilizzo",
    "tuile_vpn": "riavviare il filtro dei siti",
    "tuile_admin": "proteggere la disinstallazione",
    "tuile_batterie": "togliere il risparmio batteria per Vision",
    "tuile_accueil": "rendere Vision la schermata Home",
    "tuile_accueil_tv": "attivare la schermata Home forzata nelle impostazioni di Vision",
    "prot_hors_ligne": "Offline: per ora non viene applicato nulla.",
    "prot_ok": "È tutto a posto.",
    "prot_a_faire": "Da fare sul dispositivo: {liste}.",
    "aucun_appareil": "Nessun dispositivo.",
    "alerte_accessibilite": "l'accessibilità è disattivata (un'app bloccata impiega qualche secondo a chiudersi)",
    "alerte_usage": "i dati di utilizzo sono negati (niente tempo di utilizzo)",
    "alerte_vpn": "il filtro dei siti è fermo",
    "alerte_admin": "la disinstallazione non è protetta",
    "alerte_batterie": "il risparmio batteria può addormentare Vision",
    "alerte_accueil": "Vision non è la schermata Home",
    "alerte_accueil_tv": "la schermata Home forzata di Vision è disattivata",
    "alerte_a_verifier": "Da verificare sul dispositivo: {liste}.",
    "separateur_liste": "; ",
    "bouton_fermer": "Blocca",
    "bouton_revenir_planning": "Torna al programma",
    "bouton_mot": "Un messaggio",
    "mot_placeholder": "Un messaggio sul suo schermo…",
    "bouton_envoyer": "Invia",
    "rien_ferme": "Niente di bloccato su questo dispositivo.",
    "tout_le_monde": "Tutti",
    "partage_ses_regles": " (qui valgono le sue regole)",
    "partage_legende": "Condividere non cambia nessuna regola: il dispositivo compare per ognuno e ognuno può riceverci del tempo.",
    "bouton_retirer_fiche": "Togli dalla scheda di {prenom}",
    "temps_actif_titre": "Tempo di utilizzo attivo oggi",
    "lien_fermes": "Cosa è bloccato su questo dispositivo ({n})",
    "lien_a_qui": "Di chi è questo dispositivo",
    "bouton_retirer_appareil": "Rimuovi…",
    "retrait_titre_bouton": "Rimuovi questo dispositivo da Vision",
    "retrait_titre": "Rimuovere «{nom}»?",
    "retrait_perdus": "Andranno persi:",
    "retrait_plages_one": "{n} fascia oraria di questo dispositivo",
    "retrait_plages_other": "{n} fasce orarie di questo dispositivo",
    "retrait_limites_one": "{n} limite giornaliero per app",
    "retrait_limites_other": "{n} limiti giornalieri per app",
    "retrait_regles": "le sue regole (eccezioni, categorie bloccate su questo dispositivo)",
    "retrait_historique": "la sua cronologia (tempo di utilizzo, siti bloccati)",
    "retrait_agent": "Vision si disinstalla dal dispositivo al prossimo contatto e lo lascia libero. Un dispositivo spento conserva questa istruzione per tre mesi.",
    "retrait_saisie": "Per confermare, scrivi il suo nome: {nom}",
    "retrait_confirmer": "Rimuovi",
    "retrait_fait": "«{nom}» è stato rimosso da Vision.",
    "titre_ajouter_temps": "Aggiungi tempo",
    "saisie_duree": "Durata",
    "unite_min": "min",
    "question_appareil": "Per quale dispositivo?",
    "choix_hors_ligne": " (offline)",
    "bouton_aucun": "Nessuno",
    "bouton_tous": "Tutti",
    "bouton_donner": "Dai {duree}",
    "bouton_annuler": "Annulla",
    "bonus_jusqua": "<b>{nom}</b> consentito fino alle {heure}",
    "bonus_creneau": "<b>{nom}</b> consentito fino alla prossima fascia oraria",
    "bouton_retirer": "Togli",
    "bouton_autre": "Altro…",
    "bouton_ajouter_appareil": "Aggiungi un dispositivo",
    "ajout_tous_deja": "Tutti i dispositivi sono già su questa scheda. Un nuovo dispositivo si registra installandoci Vision.",
    "ajout_chez_tous": "oggi di tutti",
    "ajout_chez": "oggi di {prenom}",
    "ajout_chez_quelquun": "oggi di qualcuno",
    "ajout_chez_personne": "oggi di nessuno",
    "bouton_le_sien": "Suo",
    "titre_le_sien": "Diventa suo e segue le sue regole e il suo programma",
    "bouton_partage": "Condiviso",
    "titre_partage": "Mantiene le sue regole, ma compare qui e può ricevere tempo",
    "ajout_legende": "«Suo»: il dispositivo segue le regole e il programma di {prenom} e lascia la persona che lo aveva. «Condiviso»: mantiene le sue regole, ma compare anche su questa scheda.",
    "badge_selon_appareil": " · a seconda del dispositivo",
    "badge_autorisee": "Consentita",
    "compte_applis_one": "{n} app",
    "compte_applis_other": "{n} app",
    "compte_sites_one": "{n} sito",
    "compte_sites_other": "{n} siti",
    "groupe_applis": "App",
    "groupe_sites": "Siti",
    "rien_classe": "Ancora niente di classificato qui.",
    "inter_rouvrir": "Consenti di nuovo per questa persona",
    "inter_couper": "Blocca per questa persona, su tutti i suoi dispositivi",
    "limite_passe": "{passe} su {total} oggi",
    "limite_atteinte": "Bloccato fino a domani: {passe} su {total} oggi",
    "limite_min_jour_titre": "Minuti al giorno",
    "unite_min_jour": "min/giorno",
    "limite_retirer_titre": "Togli il limite",
    "bouton_limiter": "Limita",
    "limite_choisir_appli": "Scegli…",
    "titre_temps_appli": "Tempo per app",
    "n_limites_one": "{n} limite",
    "n_limites_other": "{n} limiti",
    "limites_legende": "Tanti minuti al giorno per {prenom}, su tutti i suoi dispositivi insieme. Finito il tempo, l'app si blocca fino al giorno dopo. Un'autorizzazione data in risposta a una richiesta la consente di nuovo per la durata concessa.",
    "aucune_limite": "Ancora nessun limite.",
    "personne_sans_appareil": "{nom} non ha ancora nessun dispositivo in Vision. Per aggiungerne uno: Vision → Configura → Aggiungi un dispositivo.",
    "moyenne_regle": "Regola della media: {etat}",
    "moyenne_regle_valeur": "Regola della media: {etat} ({valeur}, soglia {seuil})",
    "retour_liste": "Torna all'elenco",
    "titre_appareils": "Dispositivi",
    "aucun_appareil_instant": "Ancora nessun dispositivo.",
    "titre_categories": "Categorie",
    "cats_coupees_one": "{n} bloccata su {total}",
    "cats_coupees_other": "{n} bloccate su {total}",
    "cats_legende": "Bloccare una categoria vale per {prenom} su tutti i suoi dispositivi. Ciò che bloccano i divieti comuni, la media o il programma resta selezionato, con il motivo.",
    "carte_nom": "Vision — Persone",
    "carte_description": "Una riga per persona e la pagina di ogni persona: dispositivi, accesso, categorie.",
    "titre_autorisations": "Autorizzazioni",
    "autorisations_legende": "Per un po’: su un dispositivo, anche durante una fascia oraria o a tempo schermo esaurito, poi si richiude. Eccezione: su tutti i dispositivi di {prenom}, supera la categoria chiusa ma segue fasce orarie e tempo schermo. Sempre disponibile: su tutti i suoi dispositivi, niente lo chiude. I blocchi di sicurezza e di età non si superano mai.",
    "aut_groupe_toujours": "Sempre disponibile",
    "aut_groupe_exception": "Eccezioni",
    "aut_groupe_temps": "Per un po’",
    "aucune_autorisation": "Nessuna autorizzazione per ora.",
    "aut_jusqua": "fino alle {heure} su {appareil}",
    "aut_appli": "App",
    "aut_site": "Sito",
    "aut_vers_toujours": "Sempre disponibile",
    "aut_vers_exception": "Come eccezione",
    "aut_retirer_titre": "Rimuovi questa autorizzazione",
    "aut_nom_placeholder": "App o sito (es. minecraft)",
    "aut_choix_min": "{minutes} min",
    "aut_choix_h": "{h} h",
    "mode_exception": "Eccezione",
    "mode_toujours": "Sempre disponibile",
    "aut_sur_titre": "Su quale dispositivo (per un po’)",
    "bouton_accorder_aut": "Concedi",
    "bouton_refuser": "Rifiuta",
    "reprise_attente": "I tre modi si applicheranno pienamente dopo il passaggio delle autorizzazioni: guarda prima cosa cambia, poi applica.",
    "bouton_reprise_simuler": "Vedi cosa cambia",
    "bouton_reprise_appliquer": "Applica le nuove regole",
    "bouton_reprise_annuler": "Torna alle regole precedenti",
    "reprise_confirmer": "Applicare le nuove regole di autorizzazione? Lo stato precedente viene salvato; puoi tornare indietro.",
    "reprise_annuler_confirmer": "Tornare alle regole di autorizzazione precedenti?",
    "aut_sites_lies": "Apre anche: {sites}",
    "aut_lier_titre": "Apri anche questo sito con l’app (per tutto il PC)",
    "aut_delier_titre": "Non aprire più questo sito con l’app",
    "el_logiciels": "App e programmi",
    "el_sites_seuls": "Siti senza programma · {n}",
    "el_services": "Servizi",
    "el_services_suivent": "I suoi servizi seguono sempre il programma.",
    "el_sites": "Siti",
    "el_suit": "Segue {nom}",
    "el_auto": "Collegato automaticamente",
    "el_attente": "Collegato mentre era bloccato: attende una decisione",
    "el_delier": "Scollega",
    "el_suivre": "Segui {nom}",
    "el_badge_ouvert": "Aperto",
    "el_badge_par_logiciel": "Aperto con {nom}",
    "el_badge_temps": "Per un po’ · fino alle {heure}",
    "el_badge_nomme": "Bloccato (per nome)",
    "el_badge_categorie": "Chiuso dalla categoria",
    "el_badge_plage": "Chiuso da una fascia oraria",
    "el_badge_temps_epuise": "Tempo del giorno esaurito",
    "el_badge_securite": "Chiuso (sicurezza o età)",
    "el_badge_lieu": "Chiuso per tutti",
    "el_badge_liste_noire": "Segnalato come pericoloso",
    "el_mode_choisir": "{nom}: scegli",
    "el_mode_categorie": "Seguire la categoria",
    "el_mode_bloquer": "Blocca",
    "el_voir_autres_one": "Vedi altri {n}",
    "el_voir_autres_other": "Vedi altri {n}",
    "el_recherche": "Cerca un sito, un’app o un programma",
    "el_recherche_rien": "Nessun risultato.",
    "el_logiciel_de": "Programma: {nom}",
    "el_service_de": "Servizio: {nom}",
    "el_categories_de": "Categorie: {liste}",
    "el_categorie": "Categoria",
    "el_recents_titre": "Bloccati di recente",
    "el_recents_vide": "Niente bloccato di recente.",
    "el_tentatives_one": "{n} tentativo",
    "el_tentatives_other": "{n} tentativi",
    "el_groupe_sites_one": "{n} sito bloccato con esso",
    "el_groupe_sites_other": "{n} siti bloccati con esso",
    "el_demande_inclus": "Incluso nella risposta:",
    "el_cat_fermee": "Chiusa",
    "el_cat_ouverte": "Aperta",
    "el_recherche_court": "Cerca"
  },
  "nl": {
    "pg_rouvrir": "Weer toestaan",
    "octroi_titre": "Ontgrendelcode",
    "octroi_champ": "Code",
    "octroi_valider": "Bevestigen",
    "octroi_annuler": "Annuleren",
    "erreur_admin": "Alleen voor beheerders van Home Assistant.",
    "erreur_loading": "Vision start op: probeer het zo opnieuw.",
    "erreur_pc": "Dit apparaat bestaat niet meer in Vision.",
    "erreur_json": "Het verzoek kon niet worden gelezen: probeer het opnieuw.",
    "erreur_module": "Deze functie staat uit in Vision.",
    "erreur_minutes": "Geef een duur op.",
    "erreur_defaut": "Dat is niet gelukt: er is niets veranderd, probeer het zo opnieuw.",
    "erreur_connexion": "Home Assistant reageert niet: er is niets veranderd, probeer het opnieuw.",
    "geste_en_cours": "Bezig…",
    "geste_fait": "Klaar.",
    "erreur_charger": "Vision reageert niet: {detail}",
    "ancienne": "Vision bijwerken: het startscherm wordt nog niet gecontroleerd",
    "ancienne_version": "Vision bijwerken ({version}): het startscherm wordt nog niet gecontroleerd",
    "duree_min": "{m} min",
    "duree_h": "{h} u",
    "duree_h_min": "{h} u {mm} min",
    "code_securite": "Veiligheid",
    "code_moyenne": "Gemiddelde",
    "code_plage": "Planning",
    "code_age": "Leeftijd",
    "code_regle": "Beheer",
    "code_lieu": "Regel van {lieu}",
    "sans_personne": "Zonder persoon",
    "act_inactif": "{duree} inactief",
    "act_sur": "op {quoi}",
    "etat_ouvert": "Open",
    "etat_ferme": "Dicht",
    "etat_hors_ligne": "Offline",
    "etat_avec": "{etat} · {detail}",
    "acces_injoignable": "Agent onbereikbaar: er wordt niets toegepast.",
    "acces_ouvert_gest": "Toegestaan door een {decideur}.",
    "acces_ouvert_gest_fin": "Toegestaan door een {decideur} tot {heure}.",
    "acces_ferme_gest": "Geblokkeerd door een {decideur}.",
    "acces_ferme_gest_fin": "Geblokkeerd door een {decideur} tot {heure}.",
    "acces_ferme_planning": "Geblokkeerd door de planning.",
    "acces_ferme_planning_ouvre": "Geblokkeerd door de planning, toegestaan om {heure}.",
    "acces_ouvert_ferme": "Toegestaan, geblokkeerd om {heure}.",
    "acces_ouvert_journee": "De hele dag toegestaan.",
    "appareil_partage": "Gedeeld apparaat",
    "aqui_tous": "Van iedereen",
    "aqui_partage": "Gedeeld: {noms}",
    "moyenne_valeur": "gemiddelde {valeur}",
    "aucun_appareil_min": "geen apparaat",
    "aucun_inscrit": "Geen apparaat geregistreerd.",
    "chargement": "Laden…",
    "badge_demandes_one": "{n} verzoek",
    "badge_demandes_other": "{n} verzoeken",
    "demande_acces": "<b>{prenom}</b> vraagt om <b>{quoi}</b>",
    "demande_temps": "<b>{prenom}</b> vraagt om <b>{quoi}</b> extra",
    "demande_deverrouillage": "ontgrendelen",
    "demande_site": "site: {site}",
    "motif_cite": "“{motif}”",
    "bouton_accorder": "{quoi} geven",
    "bouton_accorder_min": "{minutes} min geven",
    "bouton_non": "Nee",
    "bouton_refuser": "Weigeren",
    "bouton_toujours": "Altijd",
    "tuile_accessibilite": "de toegankelijkheid van Vision weer aanzetten",
    "tuile_usage": "gebruiksgegevens toestaan",
    "tuile_vpn": "het sitefilter opnieuw starten",
    "tuile_admin": "verwijderen beveiligen",
    "tuile_batterie": "batterijbesparing voor Vision uitzetten",
    "tuile_accueil": "Vision het startscherm maken",
    "tuile_accueil_tv": "het verplichte startscherm aanzetten in de instellingen van Vision",
    "prot_hors_ligne": "Offline: er wordt nu niets toegepast.",
    "prot_ok": "Alles is in orde.",
    "prot_a_faire": "Te doen op het apparaat: {liste}.",
    "aucun_appareil": "Geen apparaten.",
    "alerte_accessibilite": "toegankelijkheid staat uit (een geblokkeerde app sluit pas na een paar seconden)",
    "alerte_usage": "gebruiksgegevens zijn geweigerd (geen schermtijd)",
    "alerte_vpn": "het sitefilter is gestopt",
    "alerte_admin": "verwijderen is niet beveiligd",
    "alerte_batterie": "batterijbesparing kan Vision laten slapen",
    "alerte_accueil": "Vision is niet het startscherm",
    "alerte_accueil_tv": "het verplichte startscherm van Vision staat uit",
    "alerte_a_verifier": "Controleren op het apparaat: {liste}.",
    "separateur_liste": "; ",
    "bouton_fermer": "Blokkeren",
    "bouton_revenir_planning": "Terug naar planning",
    "bouton_mot": "Een bericht",
    "mot_placeholder": "Een bericht op het scherm…",
    "bouton_envoyer": "Versturen",
    "rien_ferme": "Niets geblokkeerd op dit apparaat.",
    "tout_le_monde": "Iedereen",
    "partage_ses_regles": " (de eigen regels gelden hier)",
    "partage_legende": "Delen verandert geen enkele regel: het apparaat verschijnt bij iedereen en iedereen kan er tijd op krijgen.",
    "bouton_retirer_fiche": "Verwijderen van de pagina van {prenom}",
    "temps_actif_titre": "Actieve schermtijd vandaag",
    "lien_fermes": "Wat er geblokkeerd is op dit apparaat ({n})",
    "lien_a_qui": "Van wie dit apparaat is",
    "bouton_retirer_appareil": "Verwijderen…",
    "retrait_titre_bouton": "Dit apparaat uit Vision verwijderen",
    "retrait_titre": "„{nom}” verwijderen?",
    "retrait_perdus": "Gaat verloren:",
    "retrait_plages_one": "{n} tijdvak van dit apparaat",
    "retrait_plages_other": "{n} tijdvakken van dit apparaat",
    "retrait_limites_one": "{n} daglimiet voor een app",
    "retrait_limites_other": "{n} daglimieten voor apps",
    "retrait_regles": "de eigen regels (uitzonderingen, op dit apparaat geblokkeerde categorieën)",
    "retrait_historique": "de geschiedenis (schermtijd, geblokkeerde sites)",
    "retrait_agent": "Vision verwijdert zichzelf bij het volgende contact van het apparaat en laat het vrij. Een uitgeschakeld apparaat bewaart deze opdracht drie maanden.",
    "retrait_saisie": "Typ ter bevestiging de naam: {nom}",
    "retrait_confirmer": "Verwijderen",
    "retrait_fait": "„{nom}” is uit Vision verwijderd.",
    "titre_ajouter_temps": "Tijd toevoegen",
    "saisie_duree": "Duur",
    "unite_min": "min",
    "question_appareil": "Voor welk apparaat?",
    "choix_hors_ligne": " (offline)",
    "bouton_aucun": "Geen",
    "bouton_tous": "Alle",
    "bouton_donner": "{duree} geven",
    "bouton_annuler": "Annuleren",
    "bonus_jusqua": "<b>{nom}</b> toegestaan tot {heure}",
    "bonus_creneau": "<b>{nom}</b> toegestaan tot het volgende tijdvak",
    "bouton_retirer": "Verwijderen",
    "bouton_autre": "Anders…",
    "bouton_ajouter_appareil": "Apparaat toevoegen",
    "ajout_tous_deja": "Alle apparaten staan al op deze pagina. Een nieuw apparaat registreer je door er Vision op te installeren.",
    "ajout_chez_tous": "nu van iedereen",
    "ajout_chez": "nu van {prenom}",
    "ajout_chez_quelquun": "nu van iemand",
    "ajout_chez_personne": "nu van niemand",
    "bouton_le_sien": "Eigen",
    "titre_le_sien": "Het wordt van hen en volgt hun regels en planning",
    "bouton_partage": "Gedeeld",
    "titre_partage": "Het houdt zijn regels, maar verschijnt hier en kan tijd krijgen",
    "ajout_legende": "“Eigen”: het apparaat volgt de regels en planning van {prenom} en verlaat de persoon die het had. “Gedeeld”: het houdt zijn eigen regels, maar verschijnt ook op deze pagina.",
    "badge_selon_appareil": " · per apparaat",
    "badge_autorisee": "Toegestaan",
    "compte_applis_one": "{n} app",
    "compte_applis_other": "{n} apps",
    "compte_sites_one": "{n} site",
    "compte_sites_other": "{n} sites",
    "groupe_applis": "Apps",
    "groupe_sites": "Sites",
    "rien_classe": "Hier is nog niets ingedeeld.",
    "inter_rouvrir": "Weer toestaan voor deze persoon",
    "inter_couper": "Blokkeren voor deze persoon, op al hun apparaten",
    "limite_passe": "{passe} van {total} vandaag",
    "limite_atteinte": "Geblokkeerd tot morgen: {passe} van {total} vandaag",
    "limite_min_jour_titre": "Minuten per dag",
    "unite_min_jour": "min/dag",
    "limite_retirer_titre": "Limiet verwijderen",
    "bouton_limiter": "Beperken",
    "limite_choisir_appli": "Kiezen…",
    "titre_temps_appli": "Tijd per app",
    "n_limites_one": "{n} limiet",
    "n_limites_other": "{n} limieten",
    "limites_legende": "Zoveel minuten per dag voor {prenom}, op al hun apparaten samen. Is de tijd op, dan wordt de app geblokkeerd tot de volgende dag. Een toestemming als antwoord op een verzoek staat hem weer toe voor de gegeven tijd.",
    "aucune_limite": "Nog geen limieten.",
    "personne_sans_appareil": "{nom} heeft nog geen apparaat in Vision. Om er een toe te voegen: Vision → Configureren → Apparaat toevoegen.",
    "moyenne_regle": "Gemiddelderegel: {etat}",
    "moyenne_regle_valeur": "Gemiddelderegel: {etat} ({valeur}, drempel {seuil})",
    "retour_liste": "Terug naar de lijst",
    "titre_appareils": "Apparaten",
    "aucun_appareil_instant": "Nog geen apparaten.",
    "titre_categories": "Categorieën",
    "cats_coupees_one": "{n} van {total} geblokkeerd",
    "cats_coupees_other": "{n} van {total} geblokkeerd",
    "cats_legende": "Een categorie blokkeren geldt voor {prenom} op al hun apparaten. Wat gedeelde verboden, het gemiddelde of de planning blokkeren, blijft aangevinkt, met de reden.",
    "carte_nom": "Vision — Personen",
    "carte_description": "Eén regel per persoon, en de pagina van een persoon: apparaten, toegang, categorieën.",
    "titre_autorisations": "Toestemmingen",
    "autorisations_legende": "Voor even: op één apparaat, ook tijdens een tijdvak of als de schermtijd op is, daarna weer dicht. Uitzondering: op alle apparaten van {prenom}, komt langs een gesloten categorie maar volgt tijdvakken en schermtijd. Altijd beschikbaar: op al die apparaten, niets sluit het. Blokkeringen voor veiligheid en leeftijd gaan nooit open.",
    "aut_groupe_toujours": "Altijd beschikbaar",
    "aut_groupe_exception": "Uitzonderingen",
    "aut_groupe_temps": "Voor even",
    "aucune_autorisation": "Nog geen toestemmingen.",
    "aut_jusqua": "tot {heure} op {appareil}",
    "aut_appli": "App",
    "aut_site": "Website",
    "aut_vers_toujours": "Altijd beschikbaar",
    "aut_vers_exception": "Als uitzondering",
    "aut_retirer_titre": "Deze toestemming intrekken",
    "aut_nom_placeholder": "App of website (bijv. minecraft)",
    "aut_choix_min": "{minutes} min",
    "aut_choix_h": "{h} u",
    "mode_exception": "Uitzondering",
    "mode_toujours": "Altijd beschikbaar",
    "aut_sur_titre": "Op welk apparaat (voor even)",
    "bouton_accorder_aut": "Toestaan",
    "bouton_refuser": "Weigeren",
    "reprise_attente": "De drie modi gelden volledig na de overname van de toestemmingen: kijk eerst wat er verandert, pas dan toe.",
    "bouton_reprise_simuler": "Bekijk wat er verandert",
    "bouton_reprise_appliquer": "Nieuwe regels toepassen",
    "bouton_reprise_annuler": "Terug naar de vorige regels",
    "reprise_confirmer": "De nieuwe toestemmingsregels toepassen? De vorige toestand wordt bewaard; je kunt terug.",
    "reprise_annuler_confirmer": "Terug naar de vorige toestemmingsregels?",
    "aut_sites_lies": "Opent ook: {sites}",
    "aut_lier_titre": "Deze website ook openen met de app (voor de hele pc)",
    "aut_delier_titre": "Deze website niet meer met de app openen",
    "el_logiciels": "Apps en programma's",
    "el_sites_seuls": "Sites zonder programma · {n}",
    "el_services": "Diensten",
    "el_services_suivent": "Zijn diensten volgen altijd het programma.",
    "el_sites": "Sites",
    "el_suit": "Volgt {nom}",
    "el_auto": "Automatisch gekoppeld",
    "el_attente": "Gekoppeld toen het geblokkeerd was: wacht op een beslissing",
    "el_delier": "Loskoppelen",
    "el_suivre": "{nom} volgen",
    "el_badge_ouvert": "Open",
    "el_badge_par_logiciel": "Open met {nom}",
    "el_badge_temps": "Even · tot {heure}",
    "el_badge_nomme": "Geblokkeerd (op naam)",
    "el_badge_categorie": "Gesloten door de categorie",
    "el_badge_plage": "Gesloten door een tijdvak",
    "el_badge_temps_epuise": "Tijd van vandaag op",
    "el_badge_securite": "Gesloten (veiligheid of leeftijd)",
    "el_badge_lieu": "Voor iedereen gesloten",
    "el_badge_liste_noire": "Als gevaarlijk gemeld",
    "el_mode_choisir": "{nom}: kiezen",
    "el_mode_categorie": "De categorie volgen",
    "el_mode_bloquer": "Blokkeren",
    "el_voir_autres_one": "Nog {n} bekijken",
    "el_voir_autres_other": "Nog {n} bekijken",
    "el_recherche": "Zoek een site, app of programma",
    "el_recherche_rien": "Niets gevonden.",
    "el_logiciel_de": "Programma: {nom}",
    "el_service_de": "Dienst: {nom}",
    "el_categories_de": "Categorieën: {liste}",
    "el_categorie": "Categorie",
    "el_recents_titre": "Onlangs geblokkeerd",
    "el_recents_vide": "Onlangs niets geblokkeerd.",
    "el_tentatives_one": "{n} poging",
    "el_tentatives_other": "{n} pogingen",
    "el_groupe_sites_one": "{n} site samen ermee geblokkeerd",
    "el_groupe_sites_other": "{n} sites samen ermee geblokkeerd",
    "el_demande_inclus": "Inbegrepen in het antwoord:",
    "el_cat_fermee": "Gesloten",
    "el_cat_ouverte": "Open",
    "el_recherche_court": "Zoeken"
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
const API = "pc_parental/maison";
// 1.97.3 : un échec en français, jamais un code brut.
const ERREURS_VISION = {
  admin: "erreur_admin", loading: "erreur_loading", pc: "erreur_pc", json: "erreur_json",
  module: "erreur_module", minutes: "erreur_minutes",
};
const messageErreur = (e) => {
  const b = e && e.body;
  if (b && b.retour) return String(b.retour);
  if (b && ERREURS_VISION[b.error]) return tr(ERREURS_VISION[b.error]);
  if (e && (e.status_code === 403 || (b && b.error === "admin"))) return tr(ERREURS_VISION.admin);
  // « connection lost » : le code d'erreur du websocket de Home Assistant (une valeur comparée, pas un texte affiché).
  if (e && /^connection lost$/.test(e.error)) return tr("erreur_connexion");
  return tr("erreur_defaut");
};
// 1.97.9 (point 7) : un appareil d'enfant dont l'appli est trop ancienne pour dire si Vision tient l'accueil (avant
// Android 1.63.0, ou 1.78.3 sur une télé). On le dit plutôt que d'afficher « tout est en place ».
const appliTropAncienne = (a) => {
  const p = a.protections || {};
  if (!a.android || a.parent || !a.en_ligne || "accueil" in p) return "";
  return a.version_appli ? tr("ancienne_version", { version: a.version_appli }) : tr("ancienne");
};
const RAFRAICHIR_MS = 30000;
// Les créations de demandes (même source que les notifications des décideurs).
const EVENEMENTS_DEMANDES = ["pc_parental_demande_acces", "pc_parental_demande_temps", "pc_parental_deverrouillage_demande"];

const esc = (t) =>
  String(t ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

const duree = (min) => {
  const m = Math.max(0, Math.round(min || 0));
  if (m < 60) return tr("duree_min", { m });
  const h = Math.floor(m / 60);
  const r = m % 60;
  return r ? tr("duree_h_min", { h, mm: String(r).padStart(2, "0") }) : tr("duree_h", { h });
};

const heure = (iso) => {
  if (!iso) return "";
  const d = new Date(iso);
  if (isNaN(d)) return "";
  return trHeure(d);
};

// 1.86.6 : l'icône suit le type de l'appareil (une télé n'est plus montrée comme un téléphone).
const ICONES_TYPE = { tv: "mdi:television", tablette: "mdi:tablet", telephone: "mdi:cellphone", android: "mdi:cellphone", pc: "mdi:desktop-classic" };
const iconeAppareil = (a) => ICONES_TYPE[a.type] || (a.android ? "mdi:cellphone" : "mdi:desktop-classic");
const heureTs = (ts) => (ts ? heure(new Date(ts * 1000).toISOString()) : "");

const CODES = {
  securite: { libelle: "code_securite", teinte: "var(--error-color, #c62828)" },
  // Lot 1 (A6 / HA-11) : une règle commune du lieu se dit « Règle du {lieu} », plus le seul nom du lieu (« Maison »).
  maison: { libelle: "code_lieu", teinte: "var(--error-color, #c62828)" },
  moyenne: { libelle: "code_moyenne", teinte: "var(--warning-color, #ef6c00)" },
  plage: { libelle: "code_plage", teinte: "var(--info-color, #1565c0)" },
  age: { libelle: "code_age", teinte: "var(--primary-color)" },
  regle: { libelle: "code_regle", teinte: "var(--primary-color)" },
};

// FAILLE DE SÉCURITÉ (l'auteur, 16/10) : accorder (accepter, déverrouiller, donner du temps, ouvrir, autoriser, valider un
// appareil, donner un rôle) exige le code de déverrouillage du Décideur À CHAQUE geste. Home Assistant le réclame (403 « code_octroi »,
// octroi.py) : on le demande, puis on renvoie le même geste avec lui. Le code n'est jamais gardé.
const ERREURS_CODE_OCTROI = ["code_octroi", "code_octroi_faux"];
const demanderCodeOctroi = (racine, message) => new Promise((resoudre) => {
  const d = document.createElement("dialog");
  d.setAttribute("aria-label", tr("octroi_titre"));
  d.style.cssText = "border:0;border-radius:16px;padding:20px;max-width:min(360px,calc(100vw - 48px));box-sizing:border-box;"
    + "background:var(--card-background-color,#fff);color:var(--primary-text-color,#000);";
  d.innerHTML = `<form method="dialog" style="display:grid;gap:12px;margin:0">
      <b style="font-size:18px">${esc(tr("octroi_titre"))}</b>
      <div style="color:var(--secondary-text-color);overflow-wrap:anywhere">${esc(message || "")}</div>
      <input name="code" type="password" autocomplete="off" aria-label="${esc(tr("octroi_champ"))}" placeholder="${esc(tr("octroi_champ"))}"
        style="font:inherit;padding:10px;border-radius:10px;border:1px solid var(--divider-color);min-height:44px;box-sizing:border-box;width:100%;background:var(--card-background-color);color:var(--primary-text-color)">
      <div style="display:flex;gap:8px;justify-content:flex-end;flex-wrap:wrap">
        <button value="" type="button" data-annuler style="font:inherit;min-height:44px;padding:8px 14px;border-radius:999px;border:1px solid var(--divider-color);background:none;color:var(--primary-text-color)">${esc(tr("octroi_annuler"))}</button>
        <button value="ok" style="font:inherit;min-height:44px;padding:8px 14px;border-radius:999px;border:0;background:var(--primary-color);color:var(--text-primary-color)">${esc(tr("octroi_valider"))}</button>
      </div></form>`;
  const champ = d.querySelector("input");
  const fin = (v) => { d.remove(); resoudre(v); };
  d.querySelector("[data-annuler]").addEventListener("click", () => { d.close(); fin(""); });
  d.addEventListener("cancel", () => fin(""));
  d.querySelector("form").addEventListener("submit", () => fin(champ.value));
  racine.appendChild(d);
  d.showModal();
  champ.focus();
});
const avecCodeOctroi = async (hass, racine, chemin, corps) => {
  try { return await hass.callApi("POST", chemin, corps); } catch (e) {
    let erreur = e;
    for (let essai = 0; essai < 3; essai += 1) {
      const b = erreur && erreur.body;
      if (!b || !ERREURS_CODE_OCTROI.includes(b.error)) throw erreur;
      const code = await demanderCodeOctroi(racine, b.retour);
      if (!code) throw erreur;
      try { return await hass.callApi("POST", chemin, Object.assign({}, corps, { code_octroi: code })); } catch (e2) { erreur = e2; }
    }
    throw erreur;
  }
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

class VisionMaisonCard extends HTMLElement {
  constructor() {
    super();
    this.attachShadow({ mode: "open" });
    this._donnees = null;
    this._erreur = "";
    this._fait = "";
    this._occupe = false;
    this._clic = "";
    // 1.97.3 : le bouton cliqué est retenu (avant son propre gestionnaire) pour afficher « En cours… » dessus.
    this.shadowRoot.addEventListener("click", (ev) => {
      const b = ev.composedPath().find((x) => x && x.tagName === "BUTTON");
      if (b) this._clic = JSON.stringify(Object.assign({}, b.dataset)) + "|" + b.textContent.trim();
    }, true);
    this._ouverts = new Set();
    this._details = new Set();
    // HA 2.5.1 : les catégories détaillées (étiquette → réponse), les logiciels dépliés, les catégories montrées en
    // entier, la ligne dont le choix du mode est ouvert, la recherche et les blocages récents de la personne.
    this._elCats = new Map();
    this._elCharge = new Set();
    this._elDeplies = new Set();
    this._elTous = new Set();
    this._elMode = "";
    this._elRech = { texte: "", resultats: null, focus: false };
    this._elRecents = null;
    this._mots = new Map();
    this._partages = new Set();
    this._ajout = false;
    // HA-01 : l'appareil qu'on s'apprête à retirer, et le nom tapé pour confirmer ({ id, saisie }) ou null.
    this._retrait = null;
    // Le temps qu'on s'apprête à donner : { minutes, autre, choix:Set d'appareils } ou null.
    this._temps = null;
    this._minuteur = null;
  }

  setConfig(config) {
    this._config = Object.assign({ mode: "maison", chemin: "/vision-maison" }, config || {});
  }

  getCardSize() {
    return this._config && this._config.mode === "personne" ? 12 : 4;
  }

  // Toute la largeur de la section, même quand elle enjambe deux colonnes.
  getGridOptions() {
    return { columns: "full" };
  }

  connectedCallback() {
    if (!this._minuteur) this._minuteur = setInterval(() => this._charger(), RAFRAICHIR_MS);
    if (this._hass) this._abonner();
  }

  disconnectedCallback() {
    if (this._minuteur) clearInterval(this._minuteur);
    this._minuteur = null;
    this._desabonner();
  }

  // 1.97.8 : une demande (accès, temps, déverrouillage) s'affiche dès sa création, sans attendre le rafraîchissement.
  async _abonner() {
    if (this._abonnements || !this._hass || !this._hass.connection) return;
    this._abonnements = [];
    for (const type of EVENEMENTS_DEMANDES) {
      try {
        this._abonnements.push(await this._hass.connection.subscribeEvents(() => this._charger(), type));
      } catch (e) { /* pas le droit d'écouter : le rafraîchissement régulier reste */ }
    }
  }

  _desabonner() {
    for (const fin of this._abonnements || []) { try { fin(); } catch (e) { /* déjà fermé */ } }
    this._abonnements = null;
  }

  set hass(hass) {
    const premier = !this._hass;
    this._hass = hass;
    // Mode « rouvrir » : « Tout fermer » commence ou finit (le capteur change) → on relit tout de suite.
    if (this._config && this._config.mode === "rouvrir" && this._config.entity) {
      const etat = hass.states[this._config.entity] && hass.states[this._config.entity].state;
      if (!premier && etat !== this._etatPause) { this._etatPause = etat; this._charger(); }
      this._etatPause = etat;
    }
    const langue = suivreLangue(hass);
    if (premier) { this._langue = langue; this._charger(); this._abonner(); }
    else if (langue !== this._langue) { this._langue = langue; this._rendre(); }
  }

  async _charger() {
    if (!this._hass) return;
    try {
      this._donnees = await this._hass.callApi("GET", API);
      this._erreur = "";
    } catch (e) {
      this._erreur = tr("erreur_charger", { detail: e.message || e });
    }
    // HA-01 : le dialogue de retrait ouvert n'est pas redessiné sous les doigts (la saisie du nom serait coupée).
    if (this._retrait && !this._occupe) return;
    this._rendre();
  }

  // 1.97.3 : chaque geste se voit tout de suite (« En cours… » sur le bouton), puis la carte repart de la réponse
  // du serveur (le nouvel état) avec la phrase du résultat ; un échec le dit en clair et rien ne change.
  async _agir(corps) {
    if (this._occupe) return;
    this._occupe = true;
    this._enCours = this._clic;
    this._erreur = "";
    this._fait = "";
    this._rendre();
    try {
      const r = await avecCodeOctroi(this._hass, this.shadowRoot, API, corps);
      if (r && r.appareils) this._donnees = r;
      this._fait = (r && r.retour) || tr("geste_fait");
      // HA 2.5.1 : un geste change l'état des lignes : catégories détaillées, recherche et blocages récents relus.
      // 1.93.2 (l'auteur : « la page remonte tout en haut ») : les catégories ouvertes gardent leur contenu pendant qu'on les
      // relit. Les vider faisait tout retomber à « Chargement… » : la page raccourcissait et repartait en haut.
      this._elRelire(); this._elRecents = null; this._elMode = "";
      if (this._elRech.texte.length >= 2) this._elRechercher(corps.pc || this._pcLimites);
    } catch (e) {
      this._erreur = messageErreur(e);
    }
    this._occupe = false;
    this._enCours = "";
    this._rendre();
    clearTimeout(this._minuteFait);
    if (this._fait) this._minuteFait = setTimeout(() => { this._fait = ""; this._rendre(); }, 6000);
  }

  _naviguer(chemin) {
    history.pushState(null, "", chemin);
    window.dispatchEvent(new CustomEvent("location-changed", { bubbles: true, composed: true }));
  }

  // --- lecture ------------------------------------------------------------

  // Le nom du lieu choisi par l'administrateur (« Maison » par défaut), tel que le renvoie Home Assistant. Il est
  // aussi passé au vocabulaire commun : le champ {lieu} des textes le reprend.
  _lieu() {
    poserVocabulaire({ lieu: (this._donnees && this._donnees.lieu) || "" });
    return vocabulaire().lieu;
  }

  _personnes() {
    const d = this._donnees;
    if (!d) return [];
    const appareils = d.appareils || [];
    const liste = (d.personnes || []).map((p) => ({
      ...p,
      // Les siens d'abord, puis ceux qu'elle partage (la télé du salon est à tout le monde).
      appareils: appareils
        .filter((a) => (a.proprietaires ? a.proprietaires.includes(p.entite) : a.personne === p.entite))
        .sort((a, b) => (a.personne === p.entite ? 0 : 1) - (b.personne === p.entite ? 0 : 1)),
    }));
    // Une personne de Home Assistant sans appareil a sa ligne aussi : c'est d'ici qu'on lui en donne un.
    for (const m of d.maison || []) {
      if (!liste.some((p) => p.entite === m.entite)) liste.push({ entite: m.entite, cle: m.entite.split(".").pop(), prenom: m.prenom, photo: "", appareils: [], moyenne: null });
    }
    const orphelins = appareils.filter((a) => (a.proprietaires ? !a.proprietaires.length : !a.personne));
    if (orphelins.length) liste.push({ entite: "", cle: "autres", prenom: tr("sans_personne"), appareils: orphelins, orphelins: true });
    // Les parents en dernier : on ouvre la page pour les enfants.
    liste.sort((a, b) => {
      const pa = !!a.parent;
      const pb = !!b.parent;
      if (pa !== pb) return pa ? 1 : -1;
      if (!!a.orphelins !== !!b.orphelins) return a.orphelins ? 1 : -1;
      return a.prenom.localeCompare(b.prenom, LOCALE);
    });
    return liste;
  }

  _activite(a) {
    // Lot 1 (A7) : le point de couleur est toujours doublé d'un mot (Ouvert, Fermé, Hors ligne) : la couleur seule
    // ne dit rien à qui la distingue mal.
    if (!a.en_ligne) return { texte: tr("etat_hors_ligne"), classe: "off" };
    if (a.verrouille) return { texte: tr("etat_ferme"), classe: "lock" };
    const ouvert = (detail) => tr("etat_avec", { etat: tr("etat_ouvert"), detail });
    if (a.inactif_s > 300) return { texte: ouvert(tr("act_inactif", { duree: duree(a.inactif_s / 60) })), classe: "idle" };
    const brut = String(a.focus || "").replace(/\.exe$/i, "");
    const quoi = a.focus_libelle || (brut ? brut.charAt(0).toUpperCase() + brut.slice(1) : "");
    return { texte: quoi ? ouvert(tr("act_sur", { quoi })) : tr("etat_ouvert"), classe: "on" };
  }

  _acces(a) {
    const d = a.derogation;
    if (!a.en_ligne) return tr("acces_injoignable");
    if (d && d.mode) {
      const cle = d.mode === "open" ? "acces_ouvert_gest" : "acces_ferme_gest";
      return d.fin ? tr(cle + "_fin", { heure: heureTs(d.fin) }) : tr(cle);
    }
    const prochain = a.prochain ? heure(a.prochain) : "";
    if (a.verrouille) return prochain ? tr("acces_ferme_planning_ouvre", { heure: prochain }) : tr("acces_ferme_planning");
    return prochain ? tr("acces_ouvert_ferme", { heure: prochain }) : tr("acces_ouvert_journee");
  }

  _categoriesDe(personne) {
    const d = this._donnees;
    if (!d) return [];
    // Les catégories suivent la personne : seuls ses propres appareils comptent, pas ceux qu'elle partage.
    const apps = personne.appareils.filter((a) => a.personne === personne.entite);
    return (d.categories || [])
      .map((c) => {
        const etats = apps.map((a) => (a.etiquettes_etat || {})[c.nom]).filter(Boolean);
        const base = etats[0] || { choix: "neutre", coupe: false, code: "", raison: "", verrou: false };
        const coupe = etats.find((e) => e.coupe) || base;
        return {
          ...c,
          choix: base.choix,
          coupe: !!coupe.coupe,
          code: coupe.code,
          raison: coupe.raison,
          verrou: !!coupe.verrou,
          partiel: etats.length > 1 && etats.some((e) => e.coupe !== coupe.coupe),
        };
      })
      .filter((c) => c.apps.length || c.sites.length || c.coupe || c.choix !== "neutre");
  }

  // --- rendu : maison ----------------------------------------------------

  _avatar(p) {
    if (p.photo) return `<img class="avatar" src="${esc(p.photo)}" alt="">`;
    const lettre = (p.prenom || "?").trim().charAt(0).toUpperCase();
    return `<div class="avatar lettre ${p.orphelins ? "gris" : ""}">${esc(lettre)}</div>`;
  }

  _puce(a) {
    const act = this._activite(a);
    const icone = iconeAppareil(a);
    const temps = a.usage && a.usage.actif ? ` · ${duree(a.usage.actif)}` : "";
    return `
      <div class="puce ${act.classe}" title="${esc(a.nom)}">
        <ha-icon icon="${icone}"></ha-icon>
        <span class="point" aria-hidden="true"></span>
        <span class="nom">${esc(a.nom)}</span>
        ${this._partage(a) ? `<ha-icon class="part" icon="mdi:account-multiple-outline" title="${esc(tr("appareil_partage"))}"></ha-icon>` : ""}
        <span class="act">${esc(act.texte)}${esc(temps)}</span>
      </div>`;
  }

  _partage(a) {
    return !!a.partage_tous || (a.proprietaires || []).length > 1;
  }

  _aQui(a) {
    if (a.partage_tous) return tr("aqui_tous");
    const d = this._donnees || {};
    const noms = (a.proprietaires || []).map((e) => ((d.maison || []).find((m) => m.entite === e) || {}).prenom || e.split(".").pop());
    return noms.length > 1 ? tr("aqui_partage", { noms: noms.join(", ") }) : "";
  }

  _demande(d) {
    const quoi = d.libelle || d.cle || d.nom || "";
    const lien = d.lien ? `<a href="${esc(d.lien)}" target="_blank" rel="noopener">${esc(d.lien)}</a>` : "";
    const motif = d.motif ? `<span class="motif">${esc(tr("motif_cite", { motif: d.motif }))}</span>` : "";
    const raison = d.raison ? `<span class="pourquoi">${esc(d.raison)}</span>` : "";
    const b = (dec, txt, min, mode) =>
      `<button data-acces="${esc(d.id)}" data-pc="${esc(d.pc)}" data-dec="${dec}" ${min ? `data-min="${min}"` : ""} ${mode ? `data-mode="${mode}"` : ""} ${this._occupe ? "disabled" : ""}>${txt}</button>`;
    return `
      <div class="demande">
        <div class="corps">
          ${tr(d.genre === "temps" ? "demande_temps" : "demande_acces", { prenom: esc(d.prenom), quoi: esc(quoi) })}${d.genre === "deverrouillage" ? ` <span class="pourquoi">${esc(tr("demande_deverrouillage"))}</span>` : ""}
          ${d.genre === "sites" && d.cle && d.cle !== quoi ? `<span class="pourquoi">${esc(tr("demande_site", { site: d.cle }))}</span>` : ""}
          ${lien} ${motif} ${raison}
          ${(this._donnees || {}).elements && d.genre === "apps" && (d.sites || []).length ? `
            <div class="groupe-dem"><b>${esc(trn("el_groupe_sites", d.sites.length))}</b><span class="sous">${esc(tr("el_demande_inclus"))}</span>
              <span class="puces">${["app:" + d.cle, ...d.sites].map((x) => `<label class="puce"><input type="checkbox" data-el-case="${esc(d.id)}" value="${esc(x)}" checked><span class="nom">${esc(x.startsWith("app:") ? quoi : x)}</span></label>`).join("")}</span></div>` : ""}
        </div>
        <div class="actions">${d.genre === "temps"
          ? b("temporaire", esc(tr("bouton_accorder", { quoi })), d.minutes) + b("non", esc(tr("bouton_refuser")))
          : d.genre === "deverrouillage"
          ? b("temporaire", esc(tr("bouton_accorder_min", { minutes: String(d.minutes) })), d.minutes) + b("non", esc(tr("bouton_refuser")))
          : b("temporaire", esc(tr("aut_choix_min", { minutes: "15" })), 15) + b("temporaire", esc(tr("duree_h", { h: 1 })), 60)
            + b("accorder", esc(tr("mode_exception")), 0, "exception") + b("accorder", esc(tr("mode_toujours")), 0, "toujours")
            + b("non", esc(tr("bouton_refuser")))}</div>
      </div>`;
  }

  // 1.96.0 (point 16.3) : l'onglet Protection, une tuile par appareil : tout est en place, ou ce qu'il faut faire.
  _rendreProtections() {
    const d = this._donnees;
    if (!d) return `<ha-card><div class="vide">${esc(this._erreur || tr("chargement"))}</div></ha-card>`;
    const libelles = {
      accessibilite: "tuile_accessibilite",
      usage: "tuile_usage",
      vpn: "tuile_vpn",
      admin: "tuile_admin",
      batterie: "tuile_batterie",
      accueil: "tuile_accueil",
    };
    const tuiles = (d.appareils || []).map((a) => {
      const p = a.protections || {};
      // 1.97.6 (point 7) : une télé n'a pas d'accueil par défaut ; Vision s'y impose par son « accueil forcé ».
      const lib = (k) => tr(k === "accueil" && a.type === "tv" ? "tuile_accueil_tv" : libelles[k]);
      const manquent = Object.keys(libelles).filter((k) => p[k] === false).map(lib);
      // Une télé trop ancienne pour dire si Vision tient l'accueil : on le dit plutôt que d'afficher « tout est en place ».
      const ancienne = appliTropAncienne(a);
      if (ancienne) manquent.push(ancienne);
      const ok = a.en_ligne && !manquent.length;
      const etat = !a.en_ligne ? tr("prot_hors_ligne") : ok ? tr("prot_ok") : tr("prot_a_faire", { liste: manquent.join(", ") });
      return `<div class="tuile-p ${ok ? "ok" : a.en_ligne ? "ko" : "off"}">
          <ha-icon icon="${iconeAppareil(a)}"></ha-icon>
          <div><b>${esc(a.nom)}</b>${a.prenom ? `<span class="qui"> · ${esc(a.prenom)}</span>` : ""}
            <div class="etat-p"><ha-icon icon="${ok ? "mdi:check-circle" : a.en_ligne ? "mdi:alert" : "mdi:power-plug-off-outline"}"></ha-icon>${esc(etat)}</div></div>
        </div>`;
    }).join("");
    return `<ha-card><div class="tuiles-p">${tuiles || `<div class="vide">${esc(tr("aucun_appareil"))}</div>`}</div></ha-card>`;
  }

  _rendreMaison() {
    const d = this._donnees;
    const personnes = this._personnes();
    const demandes = (d && d.demandes) || [];
    const lots = (d && d.a_decider_lots) || [];
    // Le badge compte les demandes (et les appareils qui attendent d'être validés) : les éléments à classer et les
    // logiciels à décider ne sont pas des demandes, ils ont chacun leur ligne et leur nombre juste en dessous.
    const aDecider = demandes.length + lots.filter((l) => l.genre === "appairage").reduce((s, l) => s + (Number(l.nombre) || 0), 0);
    const lignes = personnes
      .map((p) => {
        const cible = `${this._config.chemin}/personne-${esc(p.cle)}`;
        const moy = p.moyenne && p.moyenne.active && !p.moyenne.ignore && p.moyenne.valeur != null
          ? `<span class="moy ${p.moyenne.valeur < p.moyenne.seuil ? "bas" : ""}">${esc(tr("moyenne_valeur", { valeur: trNombre(Number(p.moyenne.valeur), { maximumFractionDigits: 1 }) }))}</span>`
          : "";
        return `
          <div class="ligne" data-nav="${cible}" role="link" tabindex="0">
            ${this._avatar(p)}
            <div class="qui"><div class="prenom">${esc(p.prenom)}</div>${moy}</div>
            <div class="puces">${p.appareils.length ? p.appareils.map((a) => this._puce(a)).join("") : `<span class="vide">${esc(tr("aucun_appareil_min"))}</span>`}</div>
            <ha-icon class="chevron" icon="mdi:chevron-right"></ha-icon>
          </div>`;
      })
      .join("");
    return `
      <ha-card>
        <div class="entete">
          <h2>${esc(this._config.titre || this._lieu())}</h2>
          ${aDecider ? `<span class="badge">${esc(trn("badge_demandes", aDecider))}</span>` : ""}
        </div>
        ${this._erreur ? `<div class="erreur" role="alert">${esc(this._erreur)}</div>` : ""}${this._fait ? `<div class="fait" role="status">${esc(this._fait)}</div>` : ""}
        ${demandes.length ? `<div class="demandes">${demandes.map((x) => this._demande(x)).join("")}</div>` : ""}
        ${lots.length ? `<div class="lots">${lots.map((l) => `<div class="lot" data-nav="${esc(l.ou)}" role="link" tabindex="0"><ha-icon icon="mdi:tray-full"></ha-icon><span>${esc(l.libelle)}</span><ha-icon class="chevron" icon="mdi:chevron-right"></ha-icon></div>`).join("")}</div>` : ""}
        ${d ? lignes || `<div class="vide">${esc(tr("aucun_inscrit"))}</div>` : `<div class="vide">${esc(tr("chargement"))}</div>`}
      </ha-card>`;
  }

  // --- rendu : « Rouvrir » (« Tout fermer » en cours) -------------------------
  // FAILLE DE SÉCURITÉ (l'auteur, 16/10) : rouvrir avant l'heure accorde : le geste passe par /maison et exige le code
  // de déverrouillage (avecCodeOctroi). Rien ne s'affiche quand rien n'est fermé.
  _rendreRouvrir() {
    const pg = this._donnees && this._donnees.pause_generale;
    if (!pg || !(Number(pg.fin) * 1000 > Date.now())) return "";
    return `<ha-card class="rouvrir">
        <button class="ferme" data-action="fin_pause_generale" ${this._occupe ? "disabled" : ""}>
          <ha-icon icon="mdi:lock-open-variant-outline"></ha-icon> ${esc(tr("pg_rouvrir"))}</button>
        ${this._erreur ? `<div class="erreur" role="alert">${esc(this._erreur)}</div>` : ""}${this._fait ? `<div class="fait" role="status">${esc(this._fait)}</div>` : ""}
      </ha-card>`;
  }

  // --- rendu : personne --------------------------------------------------

  // 1.92.0 (point 7) : ce qui manque sur l'appareil, en clair (les protections que l'appli dit tombées).
  _protections(a) {
    const p = a.protections || {};
    const libelles = {
      accessibilite: "alerte_accessibilite",
      usage: "alerte_usage",
      vpn: "alerte_vpn",
      admin: "alerte_admin",
      batterie: "alerte_batterie",
      accueil: a.type === "tv" ? "alerte_accueil_tv" : "alerte_accueil",
    };
    const manquent = Object.keys(libelles).filter((k) => p[k] === false).map((k) => tr(libelles[k]));
    const ancienne = appliTropAncienne(a);
    if (ancienne) manquent.push(ancienne);
    if (!a.en_ligne || !manquent.length) return "";
    return `<div class="alerte"><ha-icon icon="mdi:alert-outline"></ha-icon><span>${esc(tr("alerte_a_verifier", { liste: manquent.join(tr("separateur_liste")) }))}</span></div>`;
  }

  _appareil(a) {
    const act = this._activite(a);
    const icone = iconeAppareil(a);
    const b = (action, txt, min, classe = "") =>
      `<button class="${classe}" data-action="${action}" data-pc="${esc(a.id)}" ${min ? `data-min="${min}"` : ""} ${this._occupe || !a.en_ligne ? "disabled" : ""}>${txt}</button>`;
    // Le temps en plus se donne plus haut, pour la personne : ici ne restent que les gestes propres à l'appareil.
    const boutons = [];
    if (!a.verrouille) boutons.push(b("fermer", esc(tr("bouton_fermer")), 0, "ferme"));
    if (a.derogation && a.derogation.mode) boutons.push(b("annuler", esc(tr("bouton_revenir_planning"))));
    boutons.push(`<button data-mot="${esc(a.id)}" ${this._occupe ? "disabled" : ""}><ha-icon icon="mdi:message-text-outline"></ha-icon> ${esc(tr("bouton_mot"))}</button>`);
    const mot = this._mots.has(a.id)
      ? `<form class="mot" data-envoi="${esc(a.id)}">
          <input type="text" maxlength="200" placeholder="${esc(tr("mot_placeholder"))}" value="${esc(this._mots.get(a.id) || "")}">
          <button type="submit" class="principal" ${this._occupe ? "disabled" : ""}>${esc(tr("bouton_envoyer"))}</button>
        </form>`
      : "";
    const ouvert = this._details.has(a.id);
    const ferme = [...a.apps.map((x) => ({ ...x, genre: "apps" })), ...a.sites.map((x) => ({ ...x, genre: "sites" }))];
    const motif = (x) => {
      const i = a.raisons && a.raisons[x.nom];
      const m = i != null && a.motifs && a.motifs[i] ? String(a.motifs[i]).split("|").pop() : "";
      return m ? `<span class="pourquoi">${esc(m)}</span>` : "";
    };
    const detail = ouvert
      ? `<div class="liste">${ferme.length
          ? ferme.map((x) => `<div class="item"><ha-icon icon="${x.genre === "apps" ? "mdi:application-outline" : "mdi:web"}"></ha-icon><span>${esc(x.libelle)}</span>${motif(x)}</div>`).join("")
          : `<div class="vide">${esc(tr("rien_ferme"))}</div>`}</div>`
      : "";
    const aQui = this._aQui(a);
    const partageOuvert = this._partages.has(a.id);
    const d = this._donnees || {};
    const partage = partageOuvert
      ? `<div class="partage" data-partage="${esc(a.id)}">
          <label class="coche"><input type="checkbox" data-tous ${a.partage_tous ? "checked" : ""} ${this._occupe ? "disabled" : ""}> ${esc(tr("tout_le_monde"))}</label>
          ${(d.maison || []).map((m) => `<label class="coche"><input type="checkbox" data-qui="${esc(m.entite)}" ${(a.proprietaires || []).includes(m.entite) ? "checked" : ""} ${this._occupe || a.partage_tous || m.entite === a.personne ? "disabled" : ""}> ${esc(m.prenom)}${m.entite === a.personne ? esc(tr("partage_ses_regles")) : ""}</label>`).join("")}
          <div class="legende">${esc(tr("partage_legende"))}</div>
          ${this._personne && this._personne.entite && !a.partage_tous ? `<button data-attribuer="retirer" data-pc="${esc(a.id)}" ${this._occupe ? "disabled" : ""}>${esc(tr("bouton_retirer_fiche", { prenom: this._personne.prenom }))}</button>` : ""}
        </div>`
      : "";
    return `
      <div class="appareil">
        <div class="tete">
          <ha-icon icon="${icone}"></ha-icon>
          <div class="titre"><b>${esc(a.nom)}</b> <span class="etat ${act.classe}"><span class="point" aria-hidden="true"></span>${esc(act.texte)}</span>${aQui ? `<span class="aqui">${esc(aQui)}</span>` : ""}</div>
          <span class="temps" title="${esc(tr("temps_actif_titre"))}"><ha-icon icon="mdi:timer-outline"></ha-icon>${duree(a.usage ? a.usage.actif : 0)}</span>
        </div>
        <div class="acces">${esc(this._acces(a))}</div>
        ${this._protections(a)}
        <div class="boutons">${boutons.join("")}</div>
        ${mot}
        <button class="lien" data-detail="${esc(a.id)}">
          <ha-icon icon="${ouvert ? "mdi:chevron-down" : "mdi:chevron-right"}"></ha-icon>
          ${esc(tr("lien_fermes", { n: ferme.length }))}
        </button>
        ${detail}
        <button class="lien" data-apartage="${esc(a.id)}">
          <ha-icon icon="${partageOuvert ? "mdi:chevron-down" : "mdi:chevron-right"}"></ha-icon>
          ${esc(tr("lien_a_qui"))}
        </button>
        ${partage}
        <button class="lien retirer" data-retirer="${esc(a.id)}" title="${esc(tr("retrait_titre_bouton"))}" aria-haspopup="dialog" ${this._occupe ? "disabled" : ""}>
          <ha-icon icon="mdi:delete-outline"></ha-icon>
          ${esc(tr("bouton_retirer_appareil"))}
        </button>
      </div>`;
  }

  // HA-01 : retirer un appareil n'est plus un appui sur une tuile. Un dialogue dit ce qui sera perdu, puis demande
  // de taper le nom de l'appareil ; « Retirer » reste inactif tant que le nom ne correspond pas. Aucune cible
  // n'est choisie d'avance : on part toujours du bloc de l'appareil visé.
  _appareilARetirer() {
    const r = this._retrait;
    const d = this._donnees || {};
    return r ? (d.appareils || []).find((a) => a.id === r.id) || null : null;
  }

  _nomConfirme(a, saisie) {
    const net = (t) => String(t ?? "").normalize("NFC").trim().replace(/\s+/g, " ").toLocaleLowerCase(LOCALE);
    return !!a && net(saisie) !== "" && net(saisie) === net(a.nom);
  }

  _dialogueRetrait() {
    const a = this._appareilARetirer();
    if (!a) return "";
    const saisie = this._retrait.saisie || "";
    const ok = this._nomConfirme(a, saisie) && !this._occupe;
    const plages = (a.plages || []).length;
    const limites = (a.limites || []).length;
    const pertes = [
      plages ? trn("retrait_plages", plages) : "",
      limites ? trn("retrait_limites", limites) : "",
      tr("retrait_regles"),
      tr("retrait_historique"),
    ].filter(Boolean);
    return `
      <dialog class="retrait" aria-labelledby="retrait-titre" aria-describedby="retrait-pertes">
        <form data-retrait-form>
          <h2 id="retrait-titre">${esc(tr("retrait_titre", { nom: a.nom }))}</h2>
          <div id="retrait-pertes">
            <p>${esc(tr("retrait_perdus"))}</p>
            <ul>${pertes.map((x) => `<li>${esc(x)}</li>`).join("")}</ul>
            <p class="sous">${esc(tr("retrait_agent"))}</p>
          </div>
          ${this._erreur ? `<div class="erreur" role="alert">${esc(this._erreur)}</div>` : ""}
          <label for="retrait-nom">${esc(tr("retrait_saisie", { nom: a.nom }))}</label>
          <input id="retrait-nom" type="text" data-retrait-saisie autocomplete="off" autocapitalize="off" spellcheck="false" value="${esc(saisie)}" ${this._occupe ? "disabled" : ""}>
          <div class="boutons">
            <button type="button" data-retrait-annuler ${this._occupe ? "disabled" : ""}>${esc(tr("bouton_annuler"))}</button>
            <button type="submit" class="danger" data-retrait-ok ${ok ? "" : "disabled"}>${esc(tr("retrait_confirmer"))}</button>
          </div>
        </form>
      </dialog>`;
  }

  async _retirer() {
    const a = this._appareilARetirer();
    if (!a || this._occupe || !this._nomConfirme(a, this._retrait.saisie)) return;
    this._occupe = true;
    this._enCours = this._clic;
    this._erreur = "";
    this._fait = "";
    this._rendre();
    try {
      // Le service de toujours (pc_parental.delete_pc), avec le nom de l'appareil visé : rien n'est lu ailleurs.
      await this._hass.callService("pc_parental", "delete_pc", { pc: a.nom });
      this._retrait = null;
      this._fait = tr("retrait_fait", { nom: a.nom });
    } catch (e) {
      this._erreur = messageErreur(e);
    }
    this._occupe = false;
    this._enCours = "";
    if (this._retrait) this._rendre();
    else await this._charger();
    clearTimeout(this._minuteFait);
    if (this._fait) this._minuteFait = setTimeout(() => { this._fait = ""; this._rendre(); }, 6000);
  }

  // Donner du temps : d'abord la durée, puis l'appareil. Un seul appareil : pas de seconde question.
  _blocTemps(p) {
    const apps = p.appareils;
    if (!apps.length) return "";
    const t = this._temps;
    const off = this._occupe ? "disabled" : "";
    // La durée : un choix (vision-choix, étiquettes ou liste selon la place).
    const durees = [
      { cle: "30", libelle: "+" + tr("duree_min", { m: 30 }) },
      { cle: "60", libelle: "+" + tr("duree_h", { h: 1 }) },
      { cle: "autre", libelle: tr("bouton_autre") },
    ];
    const dureeChoisie = !t ? "" : t.autre ? "autre" : String(t.minutes);
    let etape = "";
    if (t) {
      const saisie = t.autre
        ? `<label class="saisie">${esc(tr("saisie_duree"))} <input type="number" data-minutes min="5" max="720" step="5" value="${esc(t.minutes || "")}" placeholder="45"> ${esc(tr("unite_min"))}</label>`
        : "";
      const choix = apps.length > 1
        ? `<div class="question">${esc(tr("question_appareil"))}</div>
           <div class="choix">
             <vision-choix data-choix multiple titre="${esc(tr("question_appareil"))}" valeurs="${esc(JSON.stringify([...t.choix]))}"
               options="${esc(JSON.stringify(apps.map((a) => ({ cle: a.id, libelle: a.nom + (a.en_ligne ? "" : tr("choix_hors_ligne")) }))))}"></vision-choix>
             <button class="lien" data-tous-choix>${esc(tr(t.choix.size === apps.length ? "bouton_aucun" : "bouton_tous"))}</button>
           </div>`
        : "";
      const m = Number(t.minutes || 0);
      const bon = m >= 5 && m <= 720 && t.choix.size > 0;
      etape = `
        <div class="etape">
          ${saisie}${choix}
          <div class="boutons">
            <button class="principal" data-donner ${bon && !this._occupe ? "" : "disabled"}>${esc(tr("bouton_donner", { duree: m ? duree(m) : "…" }))}</button>
            <button data-temps-annuler ${off}>${esc(tr("bouton_annuler"))}</button>
          </div>
        </div>`;
    }
    const encours = apps
      .filter((a) => a.derogation && a.derogation.mode === "open")
      .map((a) => `<div class="bonus"><ha-icon icon="mdi:timer-sand"></ha-icon><span>${a.derogation.fin ? tr("bonus_jusqua", { nom: esc(a.nom), heure: esc(heureTs(a.derogation.fin)) }) : tr("bonus_creneau", { nom: esc(a.nom) })}</span><button data-action="annuler" data-pc="${esc(a.id)}" ${off}>${esc(tr("bouton_retirer"))}</button></div>`)
      .join("");
    return `
      <h3>${esc(tr("titre_ajouter_temps"))}</h3>
      <div class="temps-bloc">
        <vision-choix data-duree titre="${esc(tr("titre_ajouter_temps"))}" valeur="${esc(dureeChoisie)}" options="${esc(JSON.stringify(durees))}"></vision-choix>
        ${etape}
        ${encours}
      </div>`;
  }

  // Donner un appareil à la personne : le sien (il suit ses règles), ou partagé (il garde les siennes).
  _blocAjout(p) {
    const d = this._donnees || {};
    const off = this._occupe ? "disabled" : "";
    const autres = (d.appareils || []).filter((a) => !p.appareils.some((x) => x.id === a.id));
    const tete = `<button class="lien ajout" data-ajout><ha-icon icon="${this._ajout ? "mdi:chevron-down" : "mdi:plus"}"></ha-icon> ${esc(tr("bouton_ajouter_appareil"))}</button>`;
    if (!this._ajout) return tete;
    if (!autres.length) return tete + `<div class="vide">${esc(tr("ajout_tous_deja"))}</div>`;
    const prenomDe = (e) => ((d.maison || []).find((m) => m.entite === e) || {}).prenom || "";
    const lignes = autres.map((a) => {
      const qui = a.partage_tous ? tr("ajout_chez_tous")
        : a.personne ? (prenomDe(a.personne) ? tr("ajout_chez", { prenom: prenomDe(a.personne) }) : tr("ajout_chez_quelquun"))
        : tr("ajout_chez_personne");
      return `
        <div class="candidat">
          <ha-icon icon="${iconeAppareil(a)}"></ha-icon>
          <span class="nomc"><b>${esc(a.nom)}</b> <span class="motif">${esc(qui)}</span></span>
          <button data-attribuer="principal" data-pc="${esc(a.id)}" ${off} title="${esc(tr("titre_le_sien"))}">${esc(tr("bouton_le_sien"))}</button>
          <button data-attribuer="partage" data-pc="${esc(a.id)}" ${off} title="${esc(tr("titre_partage"))}">${esc(tr("bouton_partage"))}</button>
        </div>`;
    }).join("");
    return `${tete}
      <div class="ajouts">
        <div class="legende">${esc(tr("ajout_legende", { prenom: p.prenom }))}</div>
        ${lignes}
      </div>`;
  }

  _choisirDuree(p, valeur) {
    const apps = p.appareils;
    const autre = valeur === "autre";
    const minutes = autre ? (this._temps && this._temps.autre ? this._temps.minutes : 0) : Number(valeur);
    // Un seul appareil et une durée toute faite : rien d'autre à demander.
    if (!autre && apps.length === 1) {
      this._temps = null;
      this._agir({ action: "temps", appareils: [apps[0].id], minutes });
      return;
    }
    // Cochés d'avance : ce qui sert en ce moment ; sinon tout.
    let choix = this._temps ? this._temps.choix : null;
    if (!choix) {
      const actifs = apps.filter((a) => a.en_ligne && !(a.inactif_s > 300)).map((a) => a.id);
      choix = new Set(actifs.length ? actifs : apps.map((a) => a.id));
    }
    this._temps = { minutes, autre, choix };
    this._rendre();
    if (autre) {
      const champ = this.shadowRoot.querySelector("[data-minutes]");
      if (champ) champ.focus();
    }
  }

  // ------------------------------------------------------------------ HA 2.5.1 : par élément

  async _elLire(corps) {
    return this._hass.callApi("POST", API, corps);
  }

  _chargerCategorie(nom, pc) {
    const cle = nom + "|" + pc;
    if (this._elCharge.has(cle)) return;
    this._elCharge.add(cle);
    this._elLire({ action: "categorie", pc, etiquette: nom, sites_max: this._elTous.has(nom) ? 400 : 8 })
      .then((r) => { this._elCats.set(cle, r); })
      .catch((e) => { this._elCats.set(cle, { erreur: messageErreur(e) }); })
      .finally(() => { this._elCharge.delete(cle); this._rendre(); });
  }

  // 1.93.2 : relit les catégories déjà détaillées (toutes, ou celles de ce nom) sans effacer ce qui est affiché.
  _elRelire(nom) {
    for (const cle of [...this._elCats.keys()]) {
      const i = cle.lastIndexOf("|");
      const n = cle.slice(0, i);
      if (nom === undefined || n === nom) this._chargerCategorie(n, cle.slice(i + 1));
    }
  }

  _elRechercher(pc) {
    const texte = this._elRech.texte;
    clearTimeout(this._elMinuteRech);
    this._elMinuteRech = setTimeout(() => {
      this._elLire({ action: "recherche", pc, texte })
        .then((r) => { if (texte === this._elRech.texte) { this._elRech.resultats = r.resultats || []; this._rendre(); } })
        .catch(() => {});
    }, 350);
  }

  /** L'état d'une ligne en toutes lettres. */
  _elBadge(o, logiciel) {
    if (o.ouvert) {
      if (o.mode === "temps") return tr("el_badge_temps", { heure: heureTs(o.fin) });
      if (o.mode === "exception") return tr("mode_exception");
      if (o.mode === "toujours") return tr("mode_toujours");
      return o.par_logiciel && logiciel ? tr("el_badge_par_logiciel", { nom: logiciel }) : tr("el_badge_ouvert");
    }
    const cles = { nomme: "el_badge_nomme", plage: "el_badge_plage", temps: "el_badge_temps_epuise", securite: "el_badge_securite",
      lieu: "el_badge_lieu", liste_noire: "el_badge_liste_noire" };
    return tr(cles[o.ferme_par] || "el_badge_categorie");
  }

  /**
   * Une ligne : le nom, son état (badge), la raison s'il est fermé, et l'interrupteur (vert : ouvert) qui ouvre le
   * choix du mode — Suivre la catégorie (le défaut), 15 min, 1 h, 2 h, Exception, Toujours disponible, Bloquer.
   */
  _elLigne(o, genre, pc, logiciel = "", nomLogiciel = "") {
    const nom = o.nom;
    const libelle = o.libelle || nom;
    const cle = genre + ":" + nom;
    const verrou = ["lieu", "securite", "liste_noire"].includes(o.ferme_par);
    const dis = this._occupe ? "disabled" : "";
    const puces = [
      `<span class="badge-el ${o.ouvert ? "ok" : "ko"}">${esc(this._elBadge(o, logiciel))}</span>`,
      o.suit && !o.par_logiciel && logiciel ? `<span class="badge-el">${esc(tr("el_suit", { nom: logiciel }))}</span>` : "",
      o.attente ? `<span class="badge-el attente">${esc(tr("el_attente"))}</span>` : o.auto ? `<span class="badge-el">${esc(tr("el_auto"))}</span>` : "",
    ].join("");
    const actuel = o.mode || "categorie";
    const choix = (mode, texte, min) => `<button data-el-choix="${esc(cle)}" data-el-genre="${esc(genre)}" data-el-nom="${esc(nom)}" data-pc="${esc(pc)}" data-el-m="${mode}" ${min ? `data-min="${min}"` : ""} aria-pressed="${mode === actuel && !min ? "true" : "false"}" ${dis}>${esc(texte)}</button>`;
    const modes = this._elMode === cle && !verrou ? `
      <div class="puces modes" role="group" aria-label="${esc(tr("el_mode_choisir", { nom: libelle }))}">
        ${choix("categorie", tr("el_mode_categorie"))}${choix("temps", tr("aut_choix_min", { minutes: "15" }), 15)}${choix("temps", tr("duree_h", { h: 1 }), 60)}${choix("temps", tr("duree_h", { h: 2 }), 120)}
        ${choix("exception", tr("mode_exception"))}${choix("toujours", tr("mode_toujours"))}${choix("bloquer", tr("el_mode_bloquer"))}
      </div>` : "";
    const liens = nomLogiciel && genre === "sites" ? `<span class="puces">${o.attente ? `<button class="lien" data-lier="${esc(nom)}" data-aut-app="${esc(nomLogiciel)}" data-pc="${esc(pc)}" ${dis}>+ ${esc(tr("el_suivre", { nom: logiciel }))}</button>` : ""}<button class="lien" data-delier="${esc(nom)}" data-aut-app="${esc(nomLogiciel)}" data-pc="${esc(pc)}" ${dis}>✕ ${esc(tr("el_delier"))}</button></span>` : "";
    return `
      <div class="el">
        <div class="elnom"><span class="nomel">${esc(libelle)}</span><span class="puces">${puces}</span>
          ${!o.ouvert && o.raison ? `<span class="sous">${esc(o.raison)}</span>` : ""}${liens}</div>
        <button class="inter-el ${o.ouvert ? "on" : ""}" role="switch" aria-checked="${o.ouvert ? "true" : "false"}" aria-expanded="${this._elMode === cle}" data-el-mode="${esc(cle)}" title="${esc(tr("el_mode_choisir", { nom: libelle }))}" ${verrou || this._occupe ? "disabled" : ""}><span class="glissiere"></span></button>
        ${modes}
      </div>`;
  }

  _contenuElements(nom, pc) {
    const det = this._elCats.get(nom + "|" + pc);
    if (!det) { this._chargerCategorie(nom, pc); return `<div class="vide">${esc(tr("chargement"))}</div>`; }
    if (det.erreur) return `<div class="erreur">${esc(det.erreur)}</div>`;
    const logiciels = (det.logiciels || []).map((lg) => {
      const lib = lg.libelle || lg.nom;
      const ouvert = this._elDeplies.has(lg.nom);
      const dedans = ouvert ? `
        <div class="el-dedans">
          ${(lg.services || []).length ? `<div class="g">${esc(tr("el_services"))}</div>${lg.services.map((x) => `<div class="sous">${esc(x.nom)}</div>`).join("")}<div class="sous">${esc(tr("el_services_suivent"))}</div>` : ""}
          ${(lg.sites || []).length ? `<div class="g">${esc(tr("el_sites"))}</div>${lg.sites.map((x) => this._elLigne(x, "sites", pc, lib, lg.nom)).join("")}` : ""}
        </div>` : "";
      return `<div class="el-logiciel"><button class="ouvrir" data-el-deplier="${esc(lg.nom)}" aria-expanded="${ouvert}"><ha-icon icon="${ouvert ? "mdi:chevron-down" : "mdi:chevron-right"}"></ha-icon></button>${this._elLigne(lg, "apps", pc)}${dedans}</div>`;
    }).join("");
    const sites = det.sites || [];
    const total = sites.length + (det.autres || 0);
    return `
      ${logiciels ? `<div class="g">${esc(tr("el_logiciels"))}</div>${logiciels}` : ""}
      ${sites.length ? `<div class="g">${esc(tr("el_sites_seuls", { n: trNombre(total) }))}</div>${sites.map((x) => this._elLigne(x, "sites", pc, x.logiciel || "")).join("")}` : ""}
      ${det.autres ? `<button class="lien" data-el-tous="${esc(nom)}">${esc(trn("el_voir_autres", det.autres))}</button>` : ""}
      ${!logiciels && !sites.length ? `<div class="vide">${esc(tr("rien_classe"))}</div>` : ""}`;
  }

  _blocRecherche(pc) {
    const r = this._elRech;
    let res = "";
    if (r.texte.length >= 2) {
      res = r.resultats === null ? `<div class="vide">${esc(tr("chargement"))}</div>`
        : !r.resultats.length ? `<div class="vide">${esc(tr("el_recherche_rien"))}</div>`
        : r.resultats.map((o) => {
          if (o.genre === "categorie") {
            return `<div class="el"><div class="elnom"><span class="nomel">${esc(tr("el_categorie"))} · ${esc(trCat(o.nom))}</span></div><span class="badge-el ${o.fermee ? "ko" : "ok"}">${esc(tr(o.fermee ? "el_cat_fermee" : "el_cat_ouverte"))}</span></div>`;
          }
          const infos = [o.service ? tr("el_service_de", { nom: o.service }) : "", o.logiciel ? tr("el_logiciel_de", { nom: o.logiciel }) : "",
            (o.categories || []).length ? tr("el_categories_de", { liste: o.categories.map(trCat).join(", ") }) : ""].filter(Boolean);
          return this._elLigne(o, o.genre, pc, o.logiciel || "") + (infos.length ? `<div class="sous el-infos">${infos.map(esc).join("<br>")}</div>` : "");
        }).join("");
    }
    return `
      <input class="el-recherche" type="search" data-el-recherche="${esc(pc)}" value="${esc(r.texte)}" placeholder="${esc(tr("el_recherche_court"))}" aria-label="${esc(tr("el_recherche"))}" title="${esc(tr("el_recherche"))}">
      ${res}`;
  }

  _blocRecents(pc) {
    if (this._elRecents === null) {
      this._elRecents = { charge: false, liste: [] };
      this._elLire({ action: "recents", pc })
        .then((r) => { this._elRecents = { charge: true, liste: r.recents || [] }; this._rendre(); })
        .catch(() => { this._elRecents = { charge: true, liste: [] }; });
    }
    const liste = this._elRecents.liste || [];
    const lignes = liste.slice(0, 8).map((o) => {
      const detail = [heureTs(o.quand), o.appareil_nom, o.logiciel, trn("el_tentatives", o.fois || 1)].filter(Boolean).join(" · ");
      return this._elLigne(o, "sites", pc, o.logiciel || "") + `<div class="sous el-infos">${esc(detail)}</div>`;
    }).join("");
    return `
      <h3>${esc(tr("el_recents_titre"))} <span class="n">${esc(String(liste.length))}</span></h3>
      ${!this._elRecents.charge ? `<div class="vide">${esc(tr("chargement"))}</div>` : lignes || `<div class="vide">${esc(tr("el_recents_vide"))}</div>`}`;
  }

  _categorie(c, pc) {
    const ouvert = this._ouverts.has(c.nom);
    const code = c.coupe ? CODES[c.code] || CODES.regle : null;
    const badge = code
      ? `<span class="badge-cat" style="--t:${code.teinte}" title="${esc(c.raison)}">${esc(tr(code.libelle))}${c.partiel ? esc(tr("badge_selon_appareil")) : ""}</span>`
      : c.choix === "autoriser" ? `<span class="badge-cat" style="--t:var(--success-color,#2e7d32)">${esc(tr("badge_autorisee"))}</span>` : "";
    const compte = [c.apps.length ? trn("compte_applis", c.apps.length) : "", c.sites.length ? trn("compte_sites", c.sites.length) : ""]
      .filter(Boolean).join(" · ");
    const contenu = ouvert && (this._donnees || {}).elements
      ? `<div class="contenu">${c.raison ? `<div class="raison">${esc(c.raison)}</div>` : ""}${this._contenuElements(c.nom, pc)}</div>`
      : ouvert
      ? `<div class="contenu">
          ${c.raison ? `<div class="raison">${esc(c.raison)}</div>` : ""}
          ${c.apps.length ? `<div class="groupe"><span class="g">${esc(tr("groupe_applis"))}</span>${c.apps.map((x) => `<span class="chip">${esc(x.libelle)}</span>`).join("")}</div>` : ""}
          ${c.sites.length ? `<div class="groupe"><span class="g">${esc(tr("groupe_sites"))}</span>${c.sites.map((x) => `<span class="chip">${esc(x)}</span>`).join("")}</div>` : ""}
          ${!c.apps.length && !c.sites.length ? `<div class="vide">${esc(tr("rien_classe"))}</div>` : ""}
        </div>`
      : "";
    const bloque = c.verrou || this._occupe || !pc;
    return `
      <div class="cat ${c.coupe ? "coupee" : ""}">
        <button class="ouvrir" data-cat="${esc(c.nom)}" aria-expanded="${ouvert}">
          <ha-icon icon="${ouvert ? "mdi:chevron-down" : "mdi:chevron-right"}"></ha-icon>
          <span class="nomcat">${esc(trCat(c.nom))}</span>
          <span class="compte">${esc(compte)}</span>
        </button>
        ${badge}
        <label class="inter ${bloque ? "fige" : ""}" title="${c.verrou ? esc(c.raison) : esc(tr(c.coupe ? "inter_rouvrir" : "inter_couper"))}">
          <input type="checkbox" data-etq="${esc(c.nom)}" data-pc="${esc(pc || "")}" ${c.coupe ? "checked" : ""} ${bloque ? "disabled" : ""}>
          <span class="glissiere"></span>
        </label>
        ${contenu}
      </div>`;
  }

  // Tant de minutes par jour pour une appli : la liste, et de quoi en ajouter une.
  _blocLimites(p, pc) {
    if (!pc) return "";
    const propres = p.appareils.filter((a) => a.personne === p.entite);
    const source = propres.find((a) => a.id === pc) || propres[0];
    if (!source) return "";
    const limites = source.limites || [];
    // Ce qu'on peut limiter : ce que ses appareils ont vu tourner, le plus utilisé d'abord.
    const connues = new Map();
    for (const a of propres) {
      const t = a.temps || {};
      for (const x of t.total || []) connues.set(x.nom, { libelle: x.libelle || x.nom, s: (connues.get(x.nom)?.s || 0) + (x.secondes || 0) });
      for (const j of t.jours || []) for (const x of j.apps || []) if (!connues.has(x.nom)) connues.set(x.nom, { libelle: x.libelle || x.nom, s: x.secondes || 0 });
    }
    const deja = new Set(limites.map((l) => l.nom));
    const choix = [...connues.entries()].filter(([n]) => !deja.has(n)).sort((a, b) => b[1].s - a[1].s);
    const lignes = limites.map((l) => {
      const passe = Math.floor((l.passe || 0) / 60);
      const atteint = (l.passe || 0) >= l.minutes * 60;
      const part = Math.min(100, Math.round(((l.passe || 0) / (l.minutes * 60)) * 100));
      return `
        <div class="limite ${atteint ? "atteinte" : ""}">
          <div class="lnom"><b>${esc(l.libelle)}</b><span class="sous">${esc(tr(atteint ? "limite_atteinte" : "limite_passe", { passe: duree(passe), total: duree(l.minutes) }))}</span>
            <div class="jauge"><i style="width:${part}%"></i></div></div>
          <input type="number" min="5" max="1440" step="5" value="${l.minutes}" data-lim-min="${esc(l.nom)}" title="${esc(tr("limite_min_jour_titre"))}" ${this._occupe ? "disabled" : ""}>
          <span class="sous">${esc(tr("unite_min_jour"))}</span>
          <button data-lim-retirer="${esc(l.nom)}" title="${esc(tr("limite_retirer_titre"))}" ${this._occupe ? "disabled" : ""}>✕</button>
        </div>`;
    }).join("");
    const ajout = choix.length
      ? `<form class="limite ajout" data-lim-ajout="${esc(pc)}">
           <select data-lim-nom required><option value="" selected disabled>${esc(tr("limite_choisir_appli"))}</option>${choix.map(([n, v]) => `<option value="${esc(n)}">${esc(v.libelle)}</option>`).join("")}</select>
           <input type="number" min="5" max="1440" step="5" value="60" data-lim-neuf title="${esc(tr("limite_min_jour_titre"))}">
           <span class="sous">${esc(tr("unite_min_jour"))}</span>
           <button type="submit" ${this._occupe ? "disabled" : ""}>${esc(tr("bouton_limiter"))}</button>
         </form>`
      : "";
    return `
      <h3>${esc(tr("titre_temps_appli"))} <span class="n">${esc(trn("n_limites", limites.length))}</span></h3>
      <div class="legende">${esc(tr("limites_legende", { prenom: p.prenom }))}</div>
      ${lignes || `<div class="vide">${esc(tr("aucune_limite"))}</div>`}
      ${ajout}`;
  }

  // 2.5.0 (refonte des autorisations) : ce qui est accordé à la personne, selon les trois modes, et le geste pour
  // accorder, changer de mode ou retirer. Une exception et « toujours disponible » valent sur toutes ses machines ;
  // « pour un temps » sur l'appareil choisi.
  _blocAutorisations(p, pc) {
    const d = this._donnees || {};
    const liste = p.autorisations || [];
    const dis = this._occupe ? "disabled" : "";
    const propres = p.appareils.filter((a) => a.personne === p.entite);
    const ligne = (f) => {
      const quand = f.mode === "temps" ? tr("aut_jusqua", { heure: heureTs(f.fin), appareil: f.appareil || "" }) : "";
      const vers = f.mode === "toujours" ? "exception" : "toujours";
      const changer = f.mode === "temps" ? ""
        : `<button data-aut-changer="${esc(f.nom)}" data-aut-genre="${esc(f.genre)}" data-aut-vers="${vers}" data-pc="${esc(pc)}" ${dis}>${esc(tr(vers === "toujours" ? "aut_vers_toujours" : "aut_vers_exception"))}</button>`;
      return `
        <div class="limite">
          <div class="lnom"><b>${esc(f.nom)}</b><span class="sous">${esc(tr(f.genre === "apps" ? "aut_appli" : "aut_site"))}${quand ? " · " + esc(quand) : ""}</span>
            ${f.genre === "apps" && (f.lies || []).length ? `<span class="sous">${esc(tr("aut_sites_lies", { sites: f.lies.join(", ") }))}</span>` : ""}
            ${f.genre === "apps" ? `<span class="puces">${(f.lies_valides || []).map((x) => `<button class="lien" data-delier="${esc(x)}" data-aut-app="${esc(f.nom)}" data-pc="${esc(f.pc || pc)}" title="${esc(tr("aut_delier_titre"))}" ${dis}>✕ ${esc(x)}</button>`).join("")}${(f.proposes || []).map((x) => `<button class="lien" data-lier="${esc(x)}" data-aut-app="${esc(f.nom)}" data-pc="${esc(f.pc || pc)}" title="${esc(tr("aut_lier_titre"))}" ${dis}>+ ${esc(x)}</button>`).join("")}</span>` : ""}</div>
          ${changer}
          <button data-aut-retirer="${esc(f.nom)}" data-aut-genre="${esc(f.genre)}" data-aut-mode="${esc(f.mode)}" data-pc="${esc(f.pc || pc)}" title="${esc(tr("aut_retirer_titre"))}" ${dis}>✕</button>
        </div>`;
    };
    const groupe = (mode, titre) => {
      const lignes = liste.filter((f) => f.mode === mode);
      return lignes.length ? `<div class="sous aut-groupe">${esc(tr(titre))}</div>${lignes.map(ligne).join("")}` : "";
    };
    // Ce qu'on peut accorder : ce que ses appareils ont vu tourner (comme pour les limites), et tout nom tapé.
    const vus = new Map();
    for (const a of propres) for (const x of ((a.temps || {}).total || [])) vus.set(x.nom, x.libelle || x.nom);
    // Le choix de l'appareil (pour un temps) : un choix (vision-choix, étiquettes ou liste selon la place).
    const appareils = `<vision-choix data-aut-pc titre="${esc(tr("aut_sur_titre"))}" valeur="${esc(pc)}"
      options="${esc(JSON.stringify(propres.map((a) => ({ cle: a.id, libelle: a.nom }))))}"></vision-choix>`;
    const reprise = d.autorisations_v2 ? "" : `
      <div class="alerte"><ha-icon icon="mdi:information-outline"></ha-icon><span>${esc(tr("reprise_attente"))}</span></div>
      <div class="boutons">
        <button data-reprise="simuler" ${dis}>${esc(tr("bouton_reprise_simuler"))}</button>
        <button class="principal" data-reprise="appliquer" ${dis}>${esc(tr("bouton_reprise_appliquer"))}</button>
      </div>`;
    return `
      <h3>${esc(tr("titre_autorisations"))} <span class="n">${esc(String(liste.length))}</span></h3>
      <div class="legende">${esc(tr("autorisations_legende", { prenom: p.prenom }))}</div>
      ${reprise}
      ${groupe("toujours", "aut_groupe_toujours")}${groupe("exception", "aut_groupe_exception")}${groupe("temps", "aut_groupe_temps")}
      ${liste.length ? "" : `<div class="vide">${esc(tr("aucune_autorisation"))}</div>`}
      <form class="limite ajout" data-aut-ajout="${esc(pc)}">
        <input data-aut-nom list="vision-aut-${esc(p.cle)}" required placeholder="${esc(tr("aut_nom_placeholder"))}">
        <datalist id="vision-aut-${esc(p.cle)}">${[...vus.entries()].map(([n, l]) => `<option value="${esc(n)}">${esc(l)}</option>`).join("")}</datalist>
        <select data-aut-choix>
          <option value="t15">${esc(tr("aut_choix_min", { minutes: "15" }))}</option>
          <option value="t60" selected>${esc(tr("aut_choix_h", { h: "1" }))}</option>
          <option value="t120">${esc(tr("aut_choix_h", { h: "2" }))}</option>
          <option value="exception">${esc(tr("mode_exception"))}</option>
          <option value="toujours">${esc(tr("mode_toujours"))}</option>
        </select>
        ${propres.length > 1 ? appareils : ""}
        <button type="submit" ${dis}>${esc(tr("bouton_accorder_aut"))}</button>
      </form>
      ${d.autorisations_v2 ? `<button class="lien" data-reprise="annuler" ${dis}>${esc(tr("bouton_reprise_annuler"))}</button>` : ""}`;
  }

  _rendrePersonne() {
    const d = this._donnees;
    const voulu = this._config.personne || "";
    const personnes = this._personnes();
    let p = personnes.find((x) => x.entite === voulu || x.cle === voulu || x.cle === String(voulu).split(".").pop());
    if (!d) return `<ha-card><div class="vide">${esc(tr("chargement"))}</div></ha-card>`;
    // Une personne de Home Assistant qui n'a encore aucun appareil a quand même sa fiche : on peut lui en donner un.
    const sans = !p && (d.maison || []).find((m) => m.entite === voulu || m.entite.split(".").pop() === String(voulu).split(".").pop());
    if (sans) p = { entite: sans.entite, cle: sans.entite.split(".").pop(), prenom: sans.prenom, photo: "", appareils: [], moyenne: null };
    if (!p) return `<ha-card><div class="vide">${esc(tr("personne_sans_appareil", { nom: (this._hass && this._hass.states[voulu] && this._hass.states[voulu].attributes.friendly_name) || voulu }))}</div></ha-card>`;
    // Les règles de la personne se posent sur un appareil à elle, jamais sur un appareil partagé.
    const propre = p.appareils.find((a) => a.personne === p.entite);
    const pc = p.principal || (propre ? propre.id : "");
    this._personne = p;
    this._pcLimites = pc;
    const cats = this._categoriesDe(p);
    const coupees = cats.filter((c) => c.coupe).length;
    const moy = p.moyenne
      ? `<div class="sous">${esc(p.moyenne.valeur != null
        ? tr("moyenne_regle_valeur", { etat: p.moyenne.etat, valeur: trNombre(Number(p.moyenne.valeur), { maximumFractionDigits: 2 }), seuil: trNombre(Number(p.moyenne.seuil)) })
        : tr("moyenne_regle", { etat: p.moyenne.etat }))}</div>`
      : "";
    // Refonte lot 3 : la fiche personne (vision-personne-card) intègre cette carte par parties, sans son en-tête :
    // « parties » = ["resume"] (temps, appareils, ajout, limites) et/ou ["categories"] ; sans option, tout (comme avant).
    const parties = Array.isArray(this._config.parties) ? this._config.parties : ["resume", "categories"];
    const resume = parties.includes("resume");
    const categories = parties.includes("categories");
    return `
      <ha-card>
        ${this._config.integre ? (moy ? `<div class="moyenne">${moy}</div>` : "") : `<div class="entete personne">
          ${this._avatar(p)}
          <div><h2>${esc(p.prenom)}</h2>${moy}</div>
          <button class="retour" data-nav="${this._config.chemin}/maison" title="${esc(tr("retour_liste"))}"><ha-icon icon="mdi:home-outline"></ha-icon></button>
        </div>`}
        ${this._erreur ? `<div class="erreur" role="alert">${esc(this._erreur)}</div>` : ""}${this._fait ? `<div class="fait" role="status">${esc(this._fait)}</div>` : ""}
        ${d.retour ? `<div class="retour-texte">${esc(d.retour)}</div>` : ""}
        ${resume ? `${this._blocTemps(p)}
        <h3>${esc(tr("titre_appareils"))}</h3>
        ${p.appareils.length ? p.appareils.map((a) => this._appareil(a)).join("") : `<div class="vide">${esc(tr("aucun_appareil_instant"))}</div>`}
        ${p.entite ? this._blocAjout(p) : ""}
        ${p.entite ? this._blocLimites(p, pc) : ""}` : ""}
        ${categories ? `<h3>${esc(tr("titre_categories"))} <span class="n">${esc(trn("cats_coupees", coupees, { total: cats.length }))}</span></h3>
        <div class="legende">${esc(tr("cats_legende", { prenom: p.prenom }))}</div>
        ${(d.elements && pc) ? this._blocRecherche(pc) : ""}
        ${this._elRech.texte.length >= 2 && d.elements ? "" : cats.map((c) => this._categorie(c, pc)).join("")}
        ${(d.elements && pc) ? this._blocRecents(pc) : ""}` : ""}
        ${categories && p.entite ? this._blocAutorisations(p, pc) : ""}
      </ha-card>
      ${this._dialogueRetrait()}`;
  }

  // --- styles et branchement --------------------------------------------

  _styles() {
    return `
      <style>
        :host { display:block;
          --vision-texte-alerte: color-mix(in srgb, var(--warning-color, #ef6c00) 50%, var(--primary-text-color, #212121));
          --vision-texte-succes: color-mix(in srgb, var(--success-color, #2e7d32) 65%, var(--primary-text-color, #212121));
          --vision-texte-erreur: color-mix(in srgb, var(--error-color, #c62828) 70%, var(--primary-text-color, #212121)); }
        ha-card { padding: 14px 16px; }
        .limite { display:flex; align-items:center; gap:8px; padding:8px 0; border-top:1px solid var(--divider-color); flex-wrap:wrap; }
        .limite .lnom { flex:1; min-width:160px; display:grid; gap:2px; }
        .limite input[type=number] { width:74px; padding:5px 6px; border:1px solid var(--divider-color); border-radius:6px; background:var(--card-background-color); color:var(--primary-text-color); }
        .limite select { flex:1; min-width:160px; max-width:100%; padding:6px 8px; border:1px solid var(--divider-color); border-radius:6px; background:var(--card-background-color); color:var(--primary-text-color); }
        .limite button { border:1px solid var(--divider-color); background:var(--secondary-background-color); color:var(--primary-text-color); border-radius:6px; padding:5px 10px; cursor:pointer; }
        .limite .jauge { height:4px; border-radius:2px; background:var(--divider-color); overflow:hidden; }
        .limite .jauge i { display:block; height:100%; background:var(--primary-color); }
        .alerte { display:flex; gap:6px; align-items:flex-start; color:var(--vision-texte-alerte); font-size:.9em; margin:6px 0; }
        .alerte ha-icon { --mdc-icon-size:18px; flex:none; }
        .limite.atteinte .jauge i { background:var(--error-color,#c62828); }
        .limite.atteinte .sous { color:var(--vision-texte-erreur); }
        h2 { margin: 0; font-size: 1.25em; font-weight: 500; }
        h3 { margin: 18px 0 6px; font-size: .95em; font-weight: 600; text-transform: uppercase; letter-spacing: .04em; color: var(--secondary-text-color); }
        h3 .n { text-transform:none; letter-spacing:0; font-weight:400; margin-left:6px; }
        .entete { display:flex; align-items:center; gap:10px; margin-bottom: 6px; }
        .entete.personne { gap:14px; }
        .entete.personne .avatar { width:46px; height:46px; font-size:1.3em; }
        .sous { color: var(--secondary-text-color); font-size:.88em; margin-top:2px; }
        .retour { margin-left:auto; border:none; background:none; color: var(--secondary-text-color); cursor:pointer; min-width:36px; min-height:36px; }
        .badge { background: var(--primary-color); color: var(--text-primary-color,#fff); border-radius: 12px; padding: 2px 10px; font-size:.86em; }
        .avatar { width:38px; height:38px; border-radius:50%; object-fit:cover; flex:none; }
        .avatar.lettre { display:flex; align-items:center; justify-content:center; background: var(--primary-color); color: var(--text-primary-color,#fff); font-weight:600; }
        .avatar.gris { background: var(--secondary-text-color, #616161); }
        .ligne { display:grid; grid-template-columns: 38px minmax(90px, 140px) 1fr 24px; gap:12px; align-items:center; padding:10px 6px; border-top:1px solid var(--divider-color); cursor:pointer; border-radius:10px; }
        .ligne:hover { background: var(--secondary-background-color); }
        .ligne:first-of-type { border-top:none; }
        .prenom { font-weight:600; }
        .moy { font-size:.86em; color: var(--secondary-text-color); }
        .moy.bas { color: var(--vision-texte-alerte); }
        .puces { display:flex; flex-wrap:wrap; gap:6px; }
        .puce { display:flex; flex-wrap:wrap; align-items:center; gap:2px 6px; min-width:0; border:1px solid var(--divider-color); border-radius:14px; padding:3px 10px 3px 7px; font-size:.86em; max-width:100%; }
        .puce ha-icon { --mdc-icon-size: 17px; color: var(--secondary-text-color); }
        .puce .nom { font-weight:500; overflow-wrap:anywhere; min-width:0; }
        .puce .act { color: var(--secondary-text-color); overflow-wrap:anywhere; }  /* recette : jamais d'ellipse, la pastille passe à la ligne */
        .point { width:8px; height:8px; border-radius:50%; background: var(--disabled-color,#9e9e9e); flex:none; }
        .on .point { background: var(--success-color,#2e7d32); }
        .idle .point { background: var(--warning-color,#ef6c00); }
        .lock .point { background: var(--error-color,#c62828); }
        .puce.off { border-style:dashed; }
        .off .point { background: transparent; border:1.5px solid var(--secondary-text-color); box-sizing:border-box; }
        .chevron { color: var(--secondary-text-color); }
        .vide { padding:14px 0; color: var(--secondary-text-color); text-align:center; }
        .erreur { color: var(--vision-texte-erreur); margin:6px 0; }
        .fait { color: var(--vision-texte-succes); margin:6px 0; font-weight:500; }
        button.en-cours { opacity:.75; cursor:progress; }
        .retour-texte { color: var(--vision-texte-succes); font-size:.9em; margin:4px 0; white-space:pre-line; }
        .aut-groupe { margin:8px 0 2px; font-weight:600; }
        .limite.ajout input[data-aut-nom] { flex:1 1 100%; min-width:0; padding:6px 8px; border:1px solid var(--divider-color); border-radius:6px; background:var(--card-background-color); color:var(--primary-text-color); font: inherit; }
        .demandes { margin: 6px 0 10px; }
        .lots { margin: 0 0 10px; display: flex; flex-direction: column; gap: 4px; }
        .lot { display: flex; align-items: center; gap: 8px; padding: 8px 10px; border-radius: 8px; cursor: pointer; min-height: 40px;
          background: var(--secondary-background-color); color: var(--primary-text-color); overflow-wrap: anywhere; }
        .lot span { flex: 1; min-width: 0; }
        .demande { display:flex; gap:10px; align-items:center; padding:8px 10px; border-radius:10px; background: var(--secondary-background-color); margin-bottom:6px; flex-wrap:wrap; }
        .demande .corps { flex:1; min-width:200px; }
        .demande a { color: var(--primary-color); word-break:break-all; }
        .motif, .pourquoi { color: var(--secondary-text-color); font-size:.88em; margin-left:6px; }
        .actions, .boutons { display:flex; gap:6px; flex-wrap:wrap; }
        button { border:1px solid var(--divider-color); background: var(--card-background-color); color: var(--primary-text-color); border-radius:8px; padding:5px 11px; cursor:pointer; font: inherit; font-size:.9em; }
        button:disabled { opacity:.45; cursor:default; }
        button.principal { background: var(--primary-color); color: var(--text-primary-color,#fff); border-color: var(--primary-color); }
        button.ferme { border-color: var(--error-color,#c62828); color: var(--vision-texte-erreur); }
        ha-card.rouvrir { padding: 12px 16px; } ha-card.rouvrir button { width: 100%; min-height: 44px; justify-content: center; }
        .appareil { border:1px solid var(--divider-color); border-radius:12px; padding:10px 12px; margin-bottom:8px; }
        .tete { display:flex; flex-wrap:wrap; align-items:center; gap:6px 10px; }
        .tete ha-icon { color: var(--secondary-text-color); }
        .titre { flex:1; display:flex; gap:10px; align-items:baseline; flex-wrap:wrap; }
        .etat { display:inline-flex; align-items:center; gap:5px; font-size:.86em; color: var(--secondary-text-color); }
        .temps { display:inline-flex; align-items:center; gap:3px; font-size:.86em; color: var(--secondary-text-color); }
        .temps ha-icon { --mdc-icon-size: 16px; }
        .acces { font-size:.9em; margin:6px 0 8px; }
        .mot { display:flex; gap:6px; margin-top:8px; }
        .mot input { flex:1; padding:6px 10px; border:1px solid var(--divider-color); border-radius:8px; background: var(--card-background-color); color: var(--primary-text-color); font: inherit; }
        .boutons button ha-icon { --mdc-icon-size: 16px; vertical-align: -3px; }
        .lien { border:none; background:none; padding:6px 0 0; min-height:28px; color: var(--secondary-text-color); display:flex; align-items:center; gap:2px; font-size:.86em; }
        .liste { padding:4px 0 0 4px; }
        .item { display:flex; gap:8px; align-items:center; padding:3px 0; font-size:.9em; }
        .item ha-icon { --mdc-icon-size: 16px; color: var(--secondary-text-color); }
        .legende { font-size:.86em; color: var(--secondary-text-color); margin-bottom:8px; }
        .cat { display:grid; grid-template-columns: minmax(0,1fr) auto 48px; gap:10px; align-items:center; padding:6px 4px; border-top:1px solid var(--divider-color); }
        .cat.coupee .nomcat { font-weight:600; }
        .cat .ouvrir { border:none; background:none; text-align:left; display:flex; align-items:center; gap:4px; padding:4px 0; color: var(--primary-text-color); font-size:1em; }
        .cat .ouvrir ha-icon { color: var(--secondary-text-color); --mdc-icon-size: 20px; }
        .compte { color: var(--secondary-text-color); font-size:.86em; margin-left:6px; }
        .badge-cat { color: color-mix(in srgb, var(--t) 70%, var(--primary-text-color, #212121)); border:1px solid var(--t); border-radius:10px; padding:1px 8px; font-size:.86em; white-space:nowrap; }
        .inter { position:relative; width:44px; height:24px; display:inline-block; cursor:pointer; }
        .inter input { opacity:0; width:0; height:0; }
        .glissiere { position:absolute; inset:0; background: var(--secondary-text-color,#757575); border-radius:24px; transition: background .15s; }
        .glissiere:before { content:""; position:absolute; width:20px; height:20px; left:2px; top:2px; background:#fff; border-radius:50%; transition: transform .15s; }
        .inter input:checked + .glissiere { background: var(--error-color,#c62828); }
        .inter input:checked + .glissiere:before { transform: translateX(20px); }
        .inter.fige { cursor:not-allowed; }
        /* HA 2.5.1 : par élément. Les noms longs passent à la ligne, jamais d'ellipse (recette). */
        .el { display:grid; grid-template-columns: minmax(0,1fr) 48px; gap:6px 10px; align-items:center; padding:6px 0; border-top:1px solid var(--divider-color); }
        .el .elnom { display:grid; gap:3px; min-width:0; }
        .el .nomel { font-weight:500; overflow-wrap:anywhere; }
        .el .modes { grid-column: 1 / -1; }
        .el .modes button { border:1px solid var(--divider-color); background:var(--secondary-background-color); color:var(--primary-text-color); border-radius:14px; padding:4px 10px; cursor:pointer; min-height:32px; }
        .el .modes button[aria-pressed=true] { border-color: var(--primary-color); font-weight:600; }
        .badge-el { border:1px solid var(--divider-color); border-radius:10px; padding:1px 8px; font-size:.86em; overflow-wrap:anywhere; max-width:100%; box-sizing:border-box; color: var(--secondary-text-color); }
        .badge-el.ok { color: color-mix(in srgb, var(--success-color,#2e7d32) 75%, var(--primary-text-color,#212121)); border-color: var(--success-color,#2e7d32); }
        .badge-el.ko { color: var(--vision-texte-erreur, var(--error-color,#c62828)); border-color: var(--error-color,#c62828); }
        .badge-el.attente { border-style:dashed; }
        .inter-el { position:relative; width:44px; height:24px; border:none; padding:0; background:none; cursor:pointer; }
        .inter-el .glissiere { background: var(--secondary-text-color,#757575); }
        .inter-el.on .glissiere { background: var(--success-color,#2e7d32); }
        .inter-el.on .glissiere:before { transform: translateX(20px); }
        .inter-el:disabled { cursor:not-allowed; opacity:.55; }
        .el-logiciel { display:grid; grid-template-columns: 28px minmax(0,1fr); align-items:start; min-width:0; }
        .el-logiciel > .ouvrir { border:none; background:none; padding:10px 0 0; color: var(--secondary-text-color); cursor:pointer; }
        .el-logiciel > .el { grid-column: 2; }
        .el-dedans { grid-column: 2; min-width:0; padding-left:12px; border-left:2px solid var(--divider-color); }
        .el .puces { min-width:0; }
        .el .lien { overflow-wrap:anywhere; text-align:left; min-width:0; max-width:100%; }
        .contenu .g, .el-dedans .g { font-size:.8em; letter-spacing:.08em; text-transform:uppercase; color: var(--secondary-text-color); margin-top:8px; }
        .el-infos { padding-bottom:6px; overflow-wrap:anywhere; }
        .el-recherche { width:100%; box-sizing:border-box; padding:8px 10px; margin:6px 0; border:1px solid var(--divider-color); border-radius:8px; background:var(--card-background-color); color:var(--primary-text-color); font: inherit; }
        .groupe-dem { display:grid; gap:4px; margin-top:6px; }
        .inter.fige .glissiere { opacity:.6; }
        .contenu { grid-column: 1 / -1; padding: 2px 0 8px 28px; min-width:0; }
        .raison { font-size:.86em; color: var(--secondary-text-color); margin-bottom:6px; }
        .groupe { display:flex; flex-wrap:wrap; gap:5px; align-items:center; margin:4px 0; }
        .g { font-size:.78em; text-transform:uppercase; letter-spacing:.04em; color: var(--secondary-text-color); margin-right:4px; }
        .chip { font-size:.86em; border:1px solid var(--divider-color); border-radius:10px; padding:1px 8px; }
        .puce .part { --mdc-icon-size: 14px; }
        .aqui { font-size:.86em; color: var(--secondary-text-color); border:1px solid var(--divider-color); border-radius:10px; padding:0 8px; }
        .temps-bloc { border:1px solid var(--divider-color); border-radius:12px; padding:10px 12px; margin-bottom:8px; }
        .etape { margin-top:10px; padding-top:10px; border-top:1px solid var(--divider-color); }
        .question { font-size:.9em; font-weight:600; margin-bottom:4px; }
        .choix, .partage { display:flex; flex-wrap:wrap; gap:4px 16px; align-items:center; margin-bottom:10px; }
        .partage { margin:6px 0 0 4px; }
        .partage .legende { flex-basis:100%; margin:4px 0 0; }
        .coche { display:inline-flex; align-items:center; gap:6px; font-size:.92em; cursor:pointer; padding:3px 0; min-height:24px; }
        .coche input { width:20px; height:20px; accent-color: var(--primary-color); }
        .saisie { display:flex; align-items:center; gap:8px; font-size:.92em; margin-bottom:10px; }
        .saisie input { width:84px; padding:6px 8px; border:1px solid var(--divider-color); border-radius:8px; background: var(--card-background-color); color: var(--primary-text-color); font: inherit; }
        .lien.ajout { font-size:.95em; color: var(--primary-color); padding:6px 0; }
        .ajouts { border:1px solid var(--divider-color); border-radius:12px; padding:10px 12px; margin:4px 0 8px; }
        .candidat { display:flex; align-items:center; gap:8px; padding:6px 0; border-top:1px solid var(--divider-color); flex-wrap:wrap; }
        .candidat ha-icon { --mdc-icon-size: 18px; color: var(--secondary-text-color); }
        .candidat .nomc { flex:1; min-width:150px; }
        .bonus { display:flex; align-items:center; gap:8px; font-size:.9em; margin-top:8px; }
        .bonus span { flex:1; }
        .bonus ha-icon { --mdc-icon-size: 17px; color: var(--secondary-text-color); }
        .lien.retirer { color: var(--vision-texte-erreur); margin-top:2px; }
        dialog.retrait { border:none; border-radius:16px; padding:20px; width:min(440px, calc(100vw - 32px)); box-sizing:border-box;
          background: var(--card-background-color,#fff); color: var(--primary-text-color); box-shadow:0 12px 40px rgba(0,0,0,.4); }
        dialog.retrait::backdrop { background: rgba(0,0,0,.55); }
        dialog.retrait h2 { font-size:1.2em; margin:0 0 10px; overflow-wrap:anywhere; }
        dialog.retrait p { margin:6px 0; }
        dialog.retrait ul { margin:4px 0 10px; padding-left:22px; }
        dialog.retrait li { margin:2px 0; }
        dialog.retrait label { display:block; font-weight:500; margin:12px 0 6px; overflow-wrap:anywhere; }
        dialog.retrait input { width:100%; box-sizing:border-box; min-height:40px; padding:8px 10px; border:1px solid var(--secondary-text-color);
          border-radius:8px; background: var(--card-background-color); color: var(--primary-text-color); font: inherit; }
        dialog.retrait .boutons { justify-content:flex-end; margin-top:16px; }
        dialog.retrait .boutons button { min-height:40px; padding:8px 16px; }
        button.danger { background: var(--error-color,#c62828); border-color: var(--error-color,#c62828); color:#fff; font-weight:500; }
        button.danger:disabled { background: var(--card-background-color); color: var(--secondary-text-color); border-color: var(--divider-color); opacity:1; }
        .tuiles-p { display:grid; grid-template-columns:repeat(auto-fill, minmax(240px, 1fr)); gap:10px; padding:12px; }
        .tuile-p { display:flex; gap:10px; align-items:flex-start; padding:12px; border-radius:14px; border:1px solid var(--divider-color);
          background:var(--secondary-background-color); }
        .tuile-p > ha-icon { --mdc-icon-size:26px; color:var(--secondary-text-color); flex:none; }
        .tuile-p .qui { color:var(--secondary-text-color); }
        .etat-p { display:flex; gap:6px; align-items:flex-start; margin-top:4px; font-size:.92em; }
        .etat-p ha-icon { --mdc-icon-size:18px; flex:none; }
        .tuile-p.ok .etat-p ha-icon { color:var(--success-color,#2e7d32); }
        .tuile-p.ko .etat-p { color:var(--vision-texte-alerte); }
        .tuile-p.off .etat-p { color:var(--secondary-text-color); }
        @media (max-width: 520px) {
          .ligne { grid-template-columns: 38px 1fr 24px; }
          .ligne .puces { grid-column: 1 / -1; }
        }
      </style>`;
  }

  _rendre() {
    this._lieu();
    const corps = this._config.mode === "personne" ? this._rendrePersonne()
      : this._config.mode === "protections" ? this._rendreProtections()
      : this._config.mode === "rouvrir" ? this._rendreRouvrir() : this._rendreMaison();
    const garde = vxGarderPlace(this);
    this.shadowRoot.innerHTML = this._styles() + corps;
    this._brancher();
    vxRendrePlace(this, garde);
    if (this._enCours) {
      for (const b of this.shadowRoot.querySelectorAll("button")) {
        if (JSON.stringify(Object.assign({}, b.dataset)) + "|" + b.textContent.trim() === this._enCours) {
          b.textContent = tr("geste_en_cours");
          b.setAttribute("aria-busy", "true");
          b.classList.add("en-cours");
        }
      }
    }
  }

  _brancher() {
    const r = this.shadowRoot;
    r.querySelectorAll("[data-nav]").forEach((el) => {
      const aller = (ev) => { ev.stopPropagation(); this._naviguer(el.dataset.nav); };
      el.addEventListener("click", aller);
      el.addEventListener("keydown", (ev) => { if (ev.key === "Enter" || ev.key === " ") aller(ev); });
    });
    r.querySelectorAll("[data-acces]").forEach((b) =>
      b.addEventListener("click", (ev) => {
        ev.stopPropagation();
        // HA 2.5.1 : une demande groupée dont un élément est décoché : la réponse ne vaut que pour les cochés.
        const cases = [...r.querySelectorAll(`[data-el-case="${CSS.escape(b.dataset.acces)}"]`)];
        const elements = cases.some((c) => !c.checked) && b.dataset.dec !== "non" ? cases.filter((c) => c.checked).map((c) => c.value) : undefined;
        this._agir({ action: "acces", pc: b.dataset.pc, demande: b.dataset.acces, decision: b.dataset.dec, minutes: Number(b.dataset.min || 0), mode: b.dataset.mode || "", elements });
      }));
    r.querySelectorAll("[data-action]").forEach((b) =>
      b.addEventListener("click", () =>
        this._agir({ action: b.dataset.action, pc: b.dataset.pc, minutes: Number(b.dataset.min || 0) })));
    // --- temps : durée, puis appareil
    r.querySelectorAll("[data-duree]").forEach((c) =>
      c.addEventListener("vision-choix", (ev) => {
        if (this._occupe) return;
        this._choisirDuree(this._personne, ev.detail.valeur);
      }));
    r.querySelectorAll("[data-minutes]").forEach((c) =>
      c.addEventListener("input", () => {
        if (!this._temps) return;
        this._temps.minutes = Math.round(Number(c.value) || 0);
        const m = this._temps.minutes;
        const ok = r.querySelector("[data-donner]");
        if (ok) {
          ok.disabled = !(m >= 5 && m <= 720 && this._temps.choix.size > 0);
          ok.textContent = tr("bouton_donner", { duree: m ? duree(m) : "…" });
        }
      }));
    r.querySelectorAll("[data-choix]").forEach((c) =>
      c.addEventListener("vision-choix", (ev) => {
        if (!this._temps) return;
        this._temps.choix = new Set(ev.detail.valeurs);
        // Sans tout redessiner (la liste dépliée resterait ouverte) : le bouton « Donner » et « Tous / Aucun ».
        const m = Number(this._temps.minutes || 0);
        const ok = r.querySelector("[data-donner]");
        if (ok) ok.disabled = !(m >= 5 && m <= 720 && this._temps.choix.size > 0) || this._occupe;
        const tous = r.querySelector("[data-tous-choix]");
        if (tous && this._personne) tous.textContent = tr(this._temps.choix.size === this._personne.appareils.length ? "bouton_aucun" : "bouton_tous");
      }));
    r.querySelectorAll("[data-tous-choix]").forEach((b) =>
      b.addEventListener("click", () => {
        if (!this._temps || !this._personne) return;
        const tous = this._personne.appareils.map((a) => a.id);
        this._temps.choix = this._temps.choix.size === tous.length ? new Set() : new Set(tous);
        this._rendre();
      }));
    r.querySelectorAll("[data-temps-annuler]").forEach((b) =>
      b.addEventListener("click", () => { this._temps = null; this._rendre(); }));
    r.querySelectorAll("[data-donner]").forEach((b) =>
      b.addEventListener("click", () => {
        const t = this._temps;
        if (!t) return;
        this._temps = null;
        this._agir({ action: "temps", appareils: [...t.choix], minutes: Number(t.minutes) });
      }));
    // --- donner ou retirer un appareil
    r.querySelectorAll("[data-ajout]").forEach((b) =>
      b.addEventListener("click", () => { this._ajout = !this._ajout; this._rendre(); }));
    r.querySelectorAll("[data-attribuer]").forEach((b) =>
      b.addEventListener("click", () => {
        if (!this._personne) return;
        this._agir({ action: "attribuer", pc: b.dataset.pc, personne: this._personne.entite, mode: b.dataset.attribuer });
      }));
    // --- à qui est l'appareil
    r.querySelectorAll("[data-apartage]").forEach((b) =>
      b.addEventListener("click", () => {
        const id = b.dataset.apartage;
        this._partages.has(id) ? this._partages.delete(id) : this._partages.add(id);
        this._rendre();
      }));
    r.querySelectorAll("[data-partage]").forEach((bloc) =>
      bloc.querySelectorAll("input").forEach((c) =>
        c.addEventListener("change", () => {
          const tous = bloc.querySelector("[data-tous]").checked;
          // « Toute la maison » qu'on vient de cocher ou de décocher : la liste repart de zéro.
          const personnes = tous || c.hasAttribute("data-tous")
            ? []
            : [...bloc.querySelectorAll("[data-qui]")].filter((x) => x.checked && !x.disabled).map((x) => x.dataset.qui);
          this._agir({ action: "partage", pc: bloc.dataset.partage, personnes, tous });
        })));
    r.querySelectorAll("[data-mot]").forEach((b) =>
      b.addEventListener("click", () => {
        const id = b.dataset.mot;
        this._mots.has(id) ? this._mots.delete(id) : this._mots.set(id, "");
        this._rendre();
        const champ = this.shadowRoot.querySelector(`[data-envoi="${id}"] input`);
        if (champ) champ.focus();
      }));
    r.querySelectorAll("[data-envoi]").forEach((f) => {
      const champ = f.querySelector("input");
      champ.addEventListener("input", () => this._mots.set(f.dataset.envoi, champ.value));
      f.addEventListener("submit", async (ev) => {
        ev.preventDefault();
        const texte = champ.value.trim();
        if (!texte) return;
        this._mots.delete(f.dataset.envoi);
        await this._agir({ action: "message", pc: f.dataset.envoi, texte });
      });
    });
    // --- HA-01 : retirer un appareil, avec le nom tapé pour confirmer
    r.querySelectorAll("[data-retirer]").forEach((b) =>
      b.addEventListener("click", () => {
        this._retrait = { id: b.dataset.retirer, saisie: "" };
        this._erreur = "";
        this._rendre();
      }));
    const dialogue = r.querySelector("dialog.retrait");
    if (dialogue) {
      const fermer = () => {
        const id = this._retrait && this._retrait.id;
        this._retrait = null;
        this._erreur = "";
        this._rendre();
        const retour = id && [...this.shadowRoot.querySelectorAll("[data-retirer]")].find((x) => x.dataset.retirer === id);
        if (retour) retour.focus();
      };
      if (!dialogue.open) {
        try { dialogue.showModal(); } catch (e) { dialogue.setAttribute("open", ""); }
      }
      dialogue.addEventListener("cancel", (ev) => { ev.preventDefault(); if (!this._occupe) fermer(); });
      const champ = dialogue.querySelector("[data-retrait-saisie]");
      const ok = dialogue.querySelector("[data-retrait-ok]");
      champ.addEventListener("input", () => {
        if (!this._retrait) return;
        this._retrait.saisie = champ.value;
        ok.disabled = this._occupe || !this._nomConfirme(this._appareilARetirer(), champ.value);
      });
      if (!this._occupe) {
        champ.focus();
        champ.setSelectionRange(champ.value.length, champ.value.length);
      }
      dialogue.querySelector("[data-retrait-annuler]").addEventListener("click", () => { if (!this._occupe) fermer(); });
      dialogue.querySelector("[data-retrait-form]").addEventListener("submit", (ev) => {
        ev.preventDefault();
        this._retirer();
      });
    }
    r.querySelectorAll("[data-detail]").forEach((b) =>
      b.addEventListener("click", () => {
        const id = b.dataset.detail;
        this._details.has(id) ? this._details.delete(id) : this._details.add(id);
        this._rendre();
      }));
    r.querySelectorAll("[data-cat]").forEach((b) =>
      b.addEventListener("click", () => {
        const nom = b.dataset.cat;
        this._ouverts.has(nom) ? this._ouverts.delete(nom) : this._ouverts.add(nom);
        this._rendre();
      }));
    // --- limites de temps par appli
    r.querySelectorAll("[data-lim-retirer]").forEach((b) =>
      b.addEventListener("click", () => {
        const f = r.querySelector("[data-lim-ajout]");
        const pc = (f && f.dataset.limAjout) || this._pcLimites;
        this._agir({ action: "limite", pc, nom: b.dataset.limRetirer, minutes: 0 });
      }));
    r.querySelectorAll("[data-lim-min]").forEach((c) =>
      c.addEventListener("change", () => {
        const m = Math.round(Number(c.value) || 0);
        if (m < 5 || m > 1440) { this._rendre(); return; }
        this._agir({ action: "limite", pc: this._pcLimites, nom: c.dataset.limMin, minutes: m });
      }));
    r.querySelectorAll("[data-lim-ajout]").forEach((f) =>
      f.addEventListener("submit", (ev) => {
        ev.preventDefault();
        const nom = f.querySelector("[data-lim-nom]").value;
        const m = Math.round(Number(f.querySelector("[data-lim-neuf]").value) || 0);
        if (!nom || m < 5 || m > 1440) return;
        this._agir({ action: "limite", pc: f.dataset.limAjout, nom, minutes: m });
      }));
    r.querySelectorAll("[data-aut-ajout]").forEach((f) =>
      f.addEventListener("submit", (ev) => {
        ev.preventDefault();
        const nom = f.querySelector("[data-aut-nom]").value.trim();
        const choix = f.querySelector("[data-aut-choix]").value;
        const sel = f.querySelector("[data-aut-pc]");
        const pcChoisi = sel ? sel.value : "";
        if (!nom) return;
        const temps = choix.startsWith("t");
        this._agir({ action: "accorder", pc: temps && pcChoisi ? pcChoisi : f.dataset.autAjout, nom,
          mode: temps ? "temps" : choix, minutes: temps ? Number(choix.slice(1)) : 0 });
      }));
    r.querySelectorAll("[data-aut-retirer]").forEach((b) =>
      b.addEventListener("click", () =>
        this._agir({ action: "retirer_autorisation", pc: b.dataset.pc, nom: b.dataset.autRetirer, genre: b.dataset.autGenre, mode: b.dataset.autMode })));
    r.querySelectorAll("[data-aut-changer]").forEach((b) =>
      b.addEventListener("click", () =>
        this._agir({ action: "accorder", pc: b.dataset.pc, nom: b.dataset.autChanger, genre: b.dataset.autGenre, mode: b.dataset.autVers })));
    r.querySelectorAll("[data-lier], [data-delier]").forEach((b) =>
      b.addEventListener("click", () =>
        this._agir({ action: "lier_domaine", pc: b.dataset.pc, nom: b.dataset.autApp, domaine: b.dataset.lier || b.dataset.delier, lier: !!b.dataset.lier })));
    r.querySelectorAll("[data-reprise]").forEach((b) =>
      b.addEventListener("click", () => {
        const etape = b.dataset.reprise;
        if (etape !== "simuler" && !window.confirm(tr(etape === "appliquer" ? "reprise_confirmer" : "reprise_annuler_confirmer"))) return;
        this._agir({ action: "reprise_autorisations", etape });
      }));
    // HA 2.5.1 : par élément.
    r.querySelectorAll("[data-el-mode]").forEach((b) =>
      b.addEventListener("click", () => { this._elMode = this._elMode === b.dataset.elMode ? "" : b.dataset.elMode; this._rendre(); }));
    r.querySelectorAll("[data-el-choix]").forEach((b) =>
      b.addEventListener("click", () =>
        this._agir({ action: "accorder", pc: b.dataset.pc, genre: b.dataset.elGenre, nom: b.dataset.elNom, mode: b.dataset.elM, minutes: Number(b.dataset.min || 0) })));
    r.querySelectorAll("[data-el-deplier]").forEach((b) =>
      b.addEventListener("click", () => { const n = b.dataset.elDeplier; this._elDeplies.has(n) ? this._elDeplies.delete(n) : this._elDeplies.add(n); this._rendre(); }));
    r.querySelectorAll("[data-el-tous]").forEach((b) =>
      b.addEventListener("click", () => { this._elTous.add(b.dataset.elTous); this._elRelire(b.dataset.elTous); }));
    r.querySelectorAll("[data-el-recherche]").forEach((champ) => {
      champ.addEventListener("input", () => {
        this._elRech = { texte: champ.value.trim(), resultats: null, focus: true };
        if (this._elRech.texte.length >= 2) this._elRechercher(champ.dataset.elRecherche);
        this._rendre();
      });
      champ.addEventListener("blur", () => { this._elRech.focus = false; });
      // Le dessin refait la carte : le champ retrouve le focus et le curseur en fin de saisie.
      if (this._elRech.focus) { champ.focus(); const n = champ.value.length; try { champ.setSelectionRange(n, n); } catch (e) { /* type search */ } }
    });
    r.querySelectorAll("[data-etq]").forEach((c) =>
      c.addEventListener("change", () =>
        this._agir({ action: "etiquette", pc: c.dataset.pc, etiquette: c.dataset.etq, etat: c.checked ? "bloquer" : "neutre" })));
  }
}

const _dejaVisionMaisonCard = !!customElements.get("vision-maison-card");
if (!_dejaVisionMaisonCard) customElements.define("vision-maison-card", VisionMaisonCard);
window.customCards = window.customCards || [];
if (!_dejaVisionMaisonCard) window.customCards.push({
  type: "vision-maison-card",
  name: tr("carte_nom"),
  description: tr("carte_description"),
});
