#!/usr/bin/env bash
# Installe Vision sur une TV Android / Google TV / Fire TV via adb, et accorde
# les autorisations qui ne sont pas proposees a l'ecran sur ces appareils.
#
# Usage :
#   1. TV : Parametres > Options developpeur > Debogage USB (ou ADB reseau) = ON
#   2. PC : ./install-tv.sh <IP-de-la-TV>      (IP de la TV)
#      ou   ./install-tv.sh                      (TV deja branchee en USB)
#
# Rejouable sans risque : reaccorde simplement les autorisations.
set -e

APK="$(dirname "$0")/../app/build/outputs/apk/release/app-release.apk"
PKG="org.soprez.vision"
IP="$1"

command -v adb >/dev/null || { echo "adb introuvable. Installe les platform-tools Android."; exit 1; }
[ -f "$APK" ] || { echo "APK introuvable : $APK"; exit 1; }

if [ -n "$IP" ]; then
  echo "Connexion a $IP..."
  adb connect "$IP:5555"
  T="-s $IP:5555"
else
  T=""
fi

echo "Installation de Vision..."
adb $T install -r -g "$APK" || adb $T install -r "$APK"

echo "Autorisations (certaines sont ignorees si deja accordees)..."
adb $T shell appops set $PKG GET_USAGE_STATS allow || true
adb $T shell appops set $PKG SYSTEM_ALERT_WINDOW allow || true
adb $T shell dumpsys deviceidle whitelist +$PKG || true
# Accessibilite : on l'active directement (contourne l'ecran grise).
SVC="$PKG/$PKG.VisionAccessibility"
CUR=$(adb $T shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r')
case "$CUR" in
  *"$SVC"*) : ;;
  null|"") adb $T shell settings put secure enabled_accessibility_services "$SVC" ;;
  *) adb $T shell settings put secure enabled_accessibility_services "$CUR:$SVC" ;;
esac
adb $T shell settings put secure accessibility_enabled 1 || true

echo "Lancement de l'ecran de configuration..."
adb $T shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true

cat <<MSG

Termine. Sur la TV, ouvre Vision et :
  - saisis l'adresse de Home Assistant et la cle d'inscription,
  - accorde le VPN (demande a l'ecran, obligatoire a valider sur l'appareil),
  - definis le code parent.

Le filtre VPN et l'administrateur d'appareil ne peuvent pas etre accordes en
adb : Vision les demandera a l'ecran au premier lancement.
MSG
