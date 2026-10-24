# À proposer à Music Assistant

Depuis la 2.5.12.14.2 (revue de HACS, hacs/default#12284), Vision n'utilise plus le client interne de l'intégration Music
Assistant de Home Assistant (`entry.runtime_data.mass`) : il passe uniquement par les services de l'intégration
(`music_assistant.search`, `get_library`, `play_media`, `get_queue`, `transfer_queue`) et les entités de ses lecteurs.

Ce qui n'a pas de service Home Assistant n'est donc plus fait par Vision : l'appareil ou l'écran répond « À faire dans
Music Assistant », et ce qu'il faut y faire se fait dans l'interface de Music Assistant. Cette page liste ce qu'on pourrait lui
proposer (aucune demande n'est ouverte chez eux pour l'instant).

| Fonction perdue dans Vision | Ce qui manque côté Home Assistant / Music Assistant | Pourquoi c'est utile |
|---|---|---|
| Cœur : aimer ou retirer un favori pour n'importe quel titre (liste, recherche, file) | Un service `music_assistant.favorite_item` (add/remove) prenant l'uri d'un média | Aujourd'hui seul le bouton « Ajouter la chanson en cours aux favoris » existe ; il ne retire rien et ne vaut que pour le titre qui joue |
| File d'attente complète (à venir, déjà joués) | Que `get_queue` rende aussi la liste des éléments (avec décalage et limite) | Voir et choisir dans la file d'une soirée longue (centaines de titres) |
| Gestes sur la file : retirer un titre, le déplacer (ensuite, à la fin, d'un rang), vider la suite, rejouer un titre | Services `music_assistant.queue_remove_item`, `queue_move_item`, `queue_clear` et `play_media` avec un identifiant d'élément de la file | Organiser une soirée sans ouvrir Music Assistant |
| Ajout automatique de titres (« Don't stop the music ») | Un service ou une entité `switch` par file | Éviter que 28 titres que personne n'a choisis s'ajoutent à la fin |
| Fondu enchaîné / lecture sans blanc | Une entité `select` / `number` par file (durée du fondu) | Réglage de soirée |
| Codec de sortie d'un lecteur | Une entité de configuration du lecteur | Lecteur Squeezelite de Vision : MP3 plutôt que FLAC |
| Appairage Sendspin automatique (PIN) | Un service qui ouvre l'appairage du lecteur et en soumet le PIN | Appairer une télé sans ouvrir Music Assistant |
| Volume de groupe | Que `media_player.volume_set` d'un chef de groupe règle le volume de groupe, ou un service dédié | Vision règle maintenant chaque enceinte, en gardant leurs écarts |
| Fournisseurs de musique reliés, nombre de titres d'une playlist | Un service qui liste les fournisseurs et leur état, et un compte d'éléments dans `get_library` | Filtre par plateforme, message « un service est hors service » |

Rien de tout cela n'est ouvert chez Music Assistant : à décider quand le projet sera dans le catalogue HACS.
