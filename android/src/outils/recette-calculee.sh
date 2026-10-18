#!/usr/bin/env bash
# Recette calculée (17/10, demande d'essai : une recette en 30 à 60 min au lieu de plusieurs heures) : les écrans tournent
# sur la JVM (Robolectric, rendu natif), sans émulateur, à toutes les tailles de la recette (360 et 412 dp, 360 dp en
# paysage, tablette en portrait et en paysage, télé 720p et 1080p) et polices 100 et 130 %. Chaque capture est vérifiée
# (« … », lignes cachées, texte coupé, mot coupé, vue hors de l'écran, bouton − / + rogné ou déformé).
#
# Usage : outils/recette-calculee.sh [-o <dossier>] [-l <langue, défaut fr>] [-c <classe, ex. WidgetsCaptureTest>]
# Images et rapports : app/build/captures-recette/ (recette-calc-<écran>-<taille>-<police>.png, -rapport-*.txt),
# recopiés dans <dossier> si -o est donné. Le témoin (DebordementsCaptureTest) et la fidélité (FideliteCaptureTest)
# passent en premier : s'ils échouent, les captures ne valent rien.
#
# Reste sur l'émulateur (outils/recette-decoupee.sh) : rotation réelle, encoche, barres du système, clavier,
# télécommande, services, réseau, et un passage d'échantillon aux extrêmes (360 dp et télé 1080p à 130 %).
set -u
ICI="$(cd "$(dirname "$0")" && pwd)"
SORTIE=""; LANGUE="fr"; CLASSE="*"
while getopts "o:l:c:" opt; do
  case "$opt" in
    o) SORTIE="$OPTARG" ;; l) LANGUE="$OPTARG" ;; c) CLASSE="$OPTARG" ;;
    *) sed -n '2,13p' "$0"; exit 2 ;;
  esac
done
export JAVA_HOME="${JAVA_HOME:-/c/Program Files/Android/Android Studio/jbr}"
GRADLE="$(command -v gradle || ls ~/.gradle/wrapper/dists/gradle-8.9-bin/*/gradle-8.9/bin/gradle 2>/dev/null | head -1)"
[ -n "$GRADLE" ] || { echo "gradle introuvable."; exit 1; }
cd "$ICI/.." || exit 1
DOSSIER="app/build/captures-recette"
rm -rf "$DOSSIER"
debut=$(date +%s)
"$GRADLE" :app:testDebugUnitTest -Pcaptures "-Pcaptures.langue=$LANGUE" --tests "org.soprez.vision.captures.$CLASSE" -q
code=$?
echo "Recette calculée : $(( $(date +%s) - debut )) s, $(ls "$DOSSIER"/*.png 2>/dev/null | wc -l) images."
for r in "$DOSSIER"/recette-calc-rapport-*.txt; do
  [ -f "$r" ] || continue
  n=$(tail -n +2 "$r" | grep -vc "^Aucun défaut")
  echo "  $(basename "$r" .txt | sed 's/recette-calc-rapport-//') : $([ "$n" = 0 ] && echo OK || echo "$n défaut(s)")"
done
[ -n "$SORTIE" ] && mkdir -p "$SORTIE" && cp "$DOSSIER"/* "$SORTIE"/ && echo "Recopié dans $SORTIE"
exit $code
