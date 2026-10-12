# Un radar de pluie dans l'écran de veille : une image, pas une page web

## Pourquoi pas une page web

Une carte `iframe` qui affiche un site de radar (WetterOnline, par exemple) fait tourner une application
de cartographie animée (WebGL) **dans la télé**. Sur un appareil modeste (Chromecast HD), dans une
WebView Android, elle tourne dans le même fil que l'écran de veille et le fige : c'est ce qui bloquait
la veille d'une installation d'essai. L'écran de veille se protège désormais (aucune page web hors
de l'écran, garde-fou de 12 s, détecteur de gel), mais la bonne réponse reste de **montrer une image**.

## La source : RainViewer

API publique : <https://www.rainviewer.com/api.html>.

> **Conditions d'utilisation — à lire avant d'activer quoi que ce soit.**
> - Usage **personnel ou éducatif uniquement**. **L'usage commercial est interdit.** Vision ne fournit
>   pas ce radar : chaque utilisateur qui le met en place le fait pour lui-même et sous ces conditions.
> - **La source doit être citée à l'écran** (« RainViewer », avec un lien quand c'est possible).
> - **Aucune garantie de disponibilité** ; les fournisseurs de données peuvent retirer ou modifier leurs
>   données.
> - Offre gratuite : **zoom 7 au plus** (environ 400 km de côté), plus de prévision, les deux dernières
>   heures seulement (une image toutes les 10 min).

Le fond de carte vient d'**OpenStreetMap** : il est fabriqué **une seule fois** (quatre tuiles) et
doit porter la mention « © les contributeurs d'OpenStreetMap ». Ne pas aller chercher les tuiles à chaque
affichage : la politique d'usage d'OpenStreetMap l'interdit.

## La mise en place (éléments standard de Home Assistant)

1. **Le capteur** — `packages/vision_radar.yaml` : un capteur REST qui lit toutes les 10 min le chemin de
   la dernière image (`radar.past[-1].path` de `weather-maps.json`). Si l'intégration REST n'était pas
   déjà chargée, il faut **un redémarrage** de Home Assistant pour qu'elle démarre ; ensuite,
   `rest.reload` suffit.
2. **La caméra** — intégration « Image générique » (interface), adresse d'image en modèle :
   `https://tilecache.rainviewer.com{{ states('sensor.radar_rainviewer_chemin') }}/512/7/<lat>/<lon>/2/1_1.png`,
   type `image/png`, option « ne recharger que si l'adresse change » (une requête toutes les 10 min, pas
   à chaque affichage).
3. **Le fond de carte** — `www/vision/radar-fond-<lieu>.jpg`, 512 × 512 px, centré sur le lieu, avec
   les deux mentions inscrites dans l'image (elles restent visibles quoi que fasse la carte).
4. **La carte** — `picture-elements` : le fond en image, la caméra par-dessus en élément `image`
   (`camera_image`), sur toute la largeur.

Pour revenir à l'ancien affichage : remettre la carte d'origine du tableau et retirer le package.

## Évolution possible : l'animation

L'image montrée est la dernière (rafraîchie toutes les 10 min) ; l'animation des deux dernières heures
est perdue. Piste : Vision assemble côté serveur les 13 dernières images sur le fond en **une image
animée** (un seul fichier, peu coûteux à afficher pour la télé). Charge estimée : **une demi-journée**.
Les conditions de RainViewer s'appliqueraient de la même façon (usage personnel, source citée).
