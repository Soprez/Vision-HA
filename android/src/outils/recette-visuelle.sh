#!/usr/bin/env bash
# Recette visuelle Android (règle permanente de l'auteur, 2026-10-11) : le test AntiDebordementTest à chaque taille
# d'écran × police 100 % / 130 %, sur un émulateur donné, en mode avion. Les captures et les rapports sont rapatriés
# dans etude-ux/captures (recette-lot1-android-<écran>-<taille>-<police>.png, recette-lot1-android-rapport-*.txt).
#
# Usage :
#   outils/recette-visuelle.sh -s emulator-5554                 # émulateur téléphone/tablette : 360, 412, tablette
#   outils/recette-visuelle.sh -s emulator-5556                 # émulateur TV (détecté) : 720p et 1080p
#   options : -o <dossier des captures>  -l <langue, défaut de ; « systeme » = celle du système>
#             -t <tailles, ex. 360dp,tablette-paysage>  -e <écrans, ex. accueil,blocage>  -i (installer les APK debug)
#             -p <polices, défaut 1.0,1.3>
#
# Tailles (dp = px / (densité/160)) :
#   360dp             1080x2340 @480  → 360×780 dp
#   412dp             1236x2745 @480  → 412×915 dp
#   tablette-portrait 1600x2560 @320  → 800×1280 dp
#   tablette-paysage  1600x2560 @320, rotation 90° → 1280×800 dp
#   tv-720p           1280x720  @213  → 960×540 dp (densité d'une vraie TV 720p)
#   tv-1080p          1920x1080 @320  → 960×540 dp (densité d'une vraie TV 1080p)
# Les tailles « tv-* » ne tournent que sur un émulateur TV (leanback), les autres que sur un émulateur non TV :
# l'appli choisit ses écrans TV d'après le mode de l'appareil, pas d'après la taille.
#
# Les APK ne sont PAS compilés ici (builds suspendus) : à la reprise,
#   gradle :app:assembleDebug :app:assembleDebugAndroidTest   puis ce script avec -i.
# Tout est remis comme avant à la fin (taille, densité, rotation, police, mode avion), même en cas d'erreur.
set -u
# Git Bash (Windows) réécrit les chemins « /sdcard/… » en chemins Windows : adb doit les recevoir tels quels.
export MSYS_NO_PATHCONV=1
# … et les chemins locaux sont donc convertis à la main (cygpath), quand il existe.
ICI_LOCAL() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else echo "$1"; fi; }

PKG="org.soprez.vision"
RUNNER="$PKG.test/android.test.InstrumentationTestRunner"
CLASSE="$PKG.AntiDebordementTest"
ICI="$(cd "$(dirname "$0")" && pwd)"
SERIE=""; SORTIE="./captures"; LANGUE="de"; TAILLES=""; ECRANS=""; INSTALLER=0; POLICES="1.0,1.3"
while getopts "s:o:l:t:e:p:i" opt; do
  case "$opt" in
    s) SERIE="$OPTARG" ;; o) SORTIE="$OPTARG" ;; l) LANGUE="$OPTARG" ;; t) TAILLES="$OPTARG" ;; e) ECRANS="$OPTARG" ;; p) POLICES="$OPTARG" ;; i) INSTALLER=1 ;;
    *) sed -n '2,23p' "$0"; exit 2 ;;
  esac
done
[ -n "$SERIE" ] || { echo "Précise l'émulateur : -s emulator-5554 (adb devices)."; exit 2; }
command -v adb >/dev/null || { echo "adb introuvable (platform-tools Android)."; exit 1; }
A() { adb -s "$SERIE" "$@"; }
SH() { adb -s "$SERIE" shell "$@" | tr -d '\r'; }
case "$SERIE" in emulator-*) : ;; *) echo "Refus : la recette ne tourne que sur un émulateur (pas $SERIE)."; exit 2 ;; esac
A get-state >/dev/null 2>&1 || { echo "$SERIE injoignable."; exit 1; }

if [ "$INSTALLER" = 1 ]; then
  APK="$ICI/../app/build/outputs/apk/debug/app-debug.apk"
  APKT="$ICI/../app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
  [ -f "$APK" ] && [ -f "$APKT" ] || { echo "APK debug absents : compiler d'abord (gradle :app:assembleDebug :app:assembleDebugAndroidTest)."; exit 1; }
  A install -r -g "$(ICI_LOCAL "$APK")" && A install -r "$(ICI_LOCAL "$APKT")" || exit 1
fi
SH pm list instrumentation | grep -q "$PKG.test" || { echo "APK d'essai absent sur $SERIE (option -i)."; exit 1; }

# L'état d'origine, remis à la fin quoi qu'il arrive.
AVION_AVANT="$(SH settings get global airplane_mode_on)"
ROT_AUTO_AVANT="$(SH settings get system accelerometer_rotation)"
ROT_AVANT="$(SH settings get system user_rotation)"
remettre() {
  SH wm size reset >/dev/null; SH wm density reset >/dev/null
  SH settings put system font_scale 1.0 >/dev/null
  [ "$ROT_AVANT" = "null" ] || { SH settings put system user_rotation "$ROT_AVANT" >/dev/null; SH wm user-rotation lock "$ROT_AVANT" >/dev/null 2>&1; }
  [ "$ROT_AUTO_AVANT" = "null" ] || SH settings put system accelerometer_rotation "$ROT_AUTO_AVANT" >/dev/null
  [ "$AVION_AVANT" = "1" ] || SH cmd connectivity airplane-mode disable >/dev/null 2>&1
}
trap remettre EXIT

# Mode avion : rien ne part sur le réseau (le test a son propre faux Home Assistant sur 127.0.0.1).
SH cmd connectivity airplane-mode enable >/dev/null 2>&1 || { SH settings put global airplane_mode_on 1 >/dev/null; }

if SH pm has-feature android.software.leanback | grep -q true; then TELE=1; else TELE=0; fi
TOUTES_TEL="360dp:1080x2340:480:0 412dp:1236x2745:480:0 tablette-portrait:1600x2560:320:0 tablette-paysage:1600x2560:320:1"
TOUTES_TV="tv-720p:1280x720:213:0 tv-1080p:1920x1080:320:0"
if [ "$TELE" = 1 ]; then LISTE="$TOUTES_TV"; else LISTE="$TOUTES_TEL"; fi

DISTANT="/sdcard/Android/data/$PKG/files/recette"
mkdir -p "$SORTIE"
ARGS_LANGUE=""; [ "$LANGUE" = "systeme" ] || ARGS_LANGUE="-e langue $LANGUE"
ARGS_ECRANS=""; [ -z "$ECRANS" ] || ARGS_ECRANS="-e ecrans $ECRANS"
ECHECS=0; BILAN=""
for cas in $LISTE; do
  IFS=: read -r NOM TAILLE DENSITE ROT <<<"$cas"
  if [ -n "$TAILLES" ] && ! echo ",$TAILLES," | grep -q ",$NOM,"; then continue; fi
  echo "=== $NOM ($TAILLE @$DENSITE, rotation $ROT) ==="
  SH wm size "$TAILLE" >/dev/null; SH wm density "$DENSITE" >/dev/null
  SH settings put system accelerometer_rotation 0 >/dev/null; SH settings put system user_rotation "$ROT" >/dev/null
  # « settings user_rotation » seul ne suffit pas toujours (Android 14+) : verrouillage explicite, puis vérification.
  SH wm user-rotation lock "$ROT" >/dev/null 2>&1
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    sleep 1; SH dumpsys window displays | grep -q "mRotation=$ROT\|mCurrentRotation=ROTATION_$((ROT * 90))" && break
  done
  sleep 2
  SH rm -rf "$DISTANT" >/dev/null 2>&1
  # Les deux polices dans un même passage : le test pose font_scale lui-même et écrit un rapport par police.
  # shellcheck disable=SC2086
  SORTIE_TEST="$(A shell am instrument -w -e class "$CLASSE" -e taille "$NOM" -e police "$POLICES" $ARGS_LANGUE $ARGS_ECRANS "$RUNNER" | tr -d '\r')"
  echo "$SORTIE_TEST" | tail -n 15
  if echo "$SORTIE_TEST" | grep -q "^OK ("; then BILAN="$BILAN\n  $NOM : OK"; else ECHECS=$((ECHECS + 1)); BILAN="$BILAN\n  $NOM : DÉFAUTS (voir recette-lot1-android-rapport-$NOM-*.txt)"; fi
  # Rapatriement à plat dans etude-ux/captures (les noms portent déjà écran, taille et police).
  TMPD="$(mktemp -d)"
  A pull "$DISTANT/." "$(ICI_LOCAL "$TMPD")" >/dev/null 2>&1 || A pull "$DISTANT" "$(ICI_LOCAL "$TMPD")" >/dev/null 2>&1
  find "$TMPD" -type f \( -name 'recette-lot1-android-*.png' -o -name 'recette-lot1-android-*.txt' \) -exec cp {} "$SORTIE/" \;
  rm -rf "$TMPD"
done

echo -e "\nRecette visuelle Android sur $SERIE ($([ "$TELE" = 1 ] && echo TV || echo téléphone/tablette), langue $LANGUE) :$BILAN"
echo "Captures et rapports : $SORTIE (recette-lot1-android-*)"
du -sh "$SORTIE" 2>/dev/null | sed 's/^/Taille du dossier des captures : /'
[ "$ECHECS" = 0 ]
