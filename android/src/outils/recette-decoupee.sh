#!/usr/bin/env bash
# Recette visuelle découpée (16-10) : la passe TV 1080p s'arrêtait en route (« Process crashed », ou émulateur à bout
# après plusieurs heures sur un PC saturé). Ici chaque morceau (taille × police × lot d'écrans) tourne sur un émulateur
# fraîchement démarré, puis éteint : une passe longue n'use plus la mémoire de l'émulateur ni celle du PC.
#
# Usage (Windows, Git Bash) :
#   outils/recette-decoupee.sh -a ux-tv -p 5572 -t tv-1080p -g tv -o <dossier>
#   outils/recette-decoupee.sh -a ux-phone -p 5570 -t 360dp,412dp -g tous -o <dossier> -c
#   -a AVD  -p port  -t tailles (celles de recette-visuelle.sh)  -o dossier des captures (un sous-dossier par morceau)
#   -g lots : « tv » (trois lots d'écrans), « tous » (sans filtre), ou une liste « lotA;lotB » (préfixes séparés par des
#             virgules dans un lot)  -P polices (défaut « 1.0;1.3 » : chacune sur son propre démarrage ; « 1.0,1.3 » : les deux d'un coup)  -l langue (fr)
#   -c : simuler l'encoche « hole » (téléphones)  -m Go libres exigés avant chaque démarrage (défaut 4)
#   -x classes : essais d'écran à passer en plus (ex. WidgetsTeleEcranTest,WidgetsInteractifsTest), après le premier morceau
# Les APK debug doivent être compilés. Un seul émulateur à la fois : refus si un autre tourne.
set -u
export MSYS_NO_PATHCONV=1
ICI="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_SDK_ROOT:-$LOCALAPPDATA/Android/Sdk}"
ADB="$SDK/platform-tools/adb.exe"; EMU="$SDK/emulator/emulator.exe"
AVD=""; PORT=""; TAILLES=""; SORTIE=""; LOTS="tous"; POLICES="1.0;1.3"; LANGUE="fr"; ENCOCHE=0; GO_MIN=4; EXTRAS=""
while getopts "a:p:t:o:g:P:l:cm:x:" opt; do
  case "$opt" in
    a) AVD="$OPTARG" ;; p) PORT="$OPTARG" ;; t) TAILLES="$OPTARG" ;; o) SORTIE="$OPTARG" ;; g) LOTS="$OPTARG" ;;
    P) POLICES="$OPTARG" ;; l) LANGUE="$OPTARG" ;; c) ENCOCHE=1 ;; m) GO_MIN="$OPTARG" ;; x) EXTRAS="$OPTARG" ;;
    *) sed -n '2,15p' "$0"; exit 2 ;;
  esac
done
[ -n "$AVD" ] && [ -n "$PORT" ] && [ -n "$TAILLES" ] && [ -n "$SORTIE" ] || { sed -n '2,15p' "$0"; exit 2; }
D="emulator-$PORT"
case "$LOTS" in
  tv)   LOTS="accueil,clavier,reaction,widget,widgets,editeur;reglages,principal,gestion,categorie,categories,choix,couleurs,autorisations,fiche,fermes,espace,niveau,message,demande;bienvenue,assistant,liaison,appairage,blocage,deverrouillage,party,reveil,reveils" ;;
  tous) LOTS="" ;;
esac

libre_go() { powershell -NoProfile -c "[math]::Floor((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1MB*10)/10" | tr -d '\r' | tr ',' '.'; }
eteindre() {
  "$ADB" -s "$D" emu kill >/dev/null 2>&1
  for _ in $(seq 1 30); do "$ADB" devices | grep -q "^$D" || return 0; sleep 2; done
}
trap eteindre EXIT

demarrer() {
  if "$ADB" devices | grep -q "^emulator-"; then echo "Refus : un émulateur tourne déjà ($("$ADB" devices | grep ^emulator- | cut -f1 | tr '\n' ' '))."; return 2; fi
  for _ in $(seq 1 60); do
    awk -v l="$(libre_go)" -v m="$GO_MIN" 'BEGIN{exit !(l+0 > m+0)}' && break
    echo "  mémoire libre $(libre_go) Go (il en faut plus de $GO_MIN) : j'attends"; sleep 30
  done
  awk -v l="$(libre_go)" -v m="$GO_MIN" 'BEGIN{exit !(l+0 > m+0)}' || { echo "Mémoire insuffisante, abandon."; return 1; }
  ("$EMU" -avd "$AVD" -port "$PORT" -no-window -gpu host -memory 1536 -no-snapshot-save -no-snapshot-load -no-audio > "$SORTIE/emu-$AVD.log" 2>&1 &)
  timeout 300 "$ADB" -s "$D" wait-for-device || return 1
  for _ in $(seq 1 60); do [ "$("$ADB" -s "$D" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break; sleep 5; done
  sleep 15
  "$ADB" -s "$D" shell svc power stayon true
  "$ADB" -s "$D" shell settings put system screen_off_timeout 1800000
  [ "$ENCOCHE" = 1 ] && "$ADB" -s "$D" shell cmd overlay enable-exclusive --category com.android.internal.display.cutout.emulation.hole
  return 0
}

mkdir -p "$SORTIE"
BILAN=""; ECHECS=0
IFS=',' read -ra LT <<<"$TAILLES"; IFS=';' read -ra LP <<<"$POLICES"
if [ -z "$LOTS" ]; then LL=("") ; else IFS=';' read -ra LL <<<"$LOTS"; fi
for t in "${LT[@]}"; do for p in "${LP[@]}"; do for i in "${!LL[@]}"; do
  lot="${LL[$i]}"; nom="$t-${p//[.,]/}${lot:+-lot$((i + 1))}"
  echo "=== $nom ($(date +%H:%M), $(libre_go) Go libres)"
  demarrer || { BILAN="$BILAN\n  $nom : NON LANCÉ"; ECHECS=$((ECHECS + 1)); eteindre; continue; }
  O="$SORTIE/$nom"; mkdir -p "$O"
  E=(); [ -n "$lot" ] && E=(-e "$lot")
  PATH="$PATH:$SDK/platform-tools" timeout 5400 bash "$ICI/recette-visuelle.sh" -s "$D" -i -l "$LANGUE" -t "$t" -p "$p" "${E[@]}" -o "$O" > "$O/journal.txt" 2>&1
  r=$?
  grep -E "^  $t : " "$O/journal.txt" | sed "s/^  $t/  $nom/"
  if [ $r = 0 ]; then BILAN="$BILAN\n  $nom : OK"; else ECHECS=$((ECHECS + 1)); BILAN="$BILAN\n  $nom : ÉCHEC (code $r, voir $O)"; fi
  if [ -n "$EXTRAS" ]; then
    IFS=',' read -ra LX <<<"$EXTRAS"; EXTRAS=""
    for c in "${LX[@]}"; do
      timeout 1200 "$ADB" -s "$D" shell am instrument -w -e class "org.soprez.vision.$c" org.soprez.vision.test/android.test.InstrumentationTestRunner \
        2>&1 | tr -d '\r' > "$SORTIE/essai-$c.txt"
      if grep -q "^OK (" "$SORTIE/essai-$c.txt"; then BILAN="$BILAN\n  $c : $(grep "^OK (" "$SORTIE/essai-$c.txt")"
      else ECHECS=$((ECHECS + 1)); BILAN="$BILAN\n  $c : ÉCHEC (voir $SORTIE/essai-$c.txt)"; fi
      echo "  $c : $(grep -E "^OK|^FAILURES|Tests run|shortMsg" "$SORTIE/essai-$c.txt" | head -2 | tr '\n' ' ')"
    done
  fi
  eteindre
done; done; done
echo -e "\nRecette découpée $AVD :$BILAN"
[ "$ECHECS" = 0 ]
