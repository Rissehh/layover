#!/usr/bin/env bash
# Puts LayoverTV and this home screen on a Sony Bravia (written for the XBR-49X800H, BRAVIA_UR3, Android 10,
# 32-bit ARM; other models need DEVICE= set to their ro.product.device, other brands see README.md).
# Run from Git Bash (Windows) or a shell (Mac/Linux) with the TV on the same Wi-Fi and ADB debugging on:
#   TV_HOST=<tv ip> ./install-on-sony.sh            install / update both apps, addons, history import, make this Home
#   TV_HOST=<tv ip> ./install-on-sony.sh restore    give Home back to Sony's launcher (apps stay installed)
# Optional: ~/.layover/addons.txt (one Stremio addon manifest URL per line) and ~/.layover/stremio_auth.txt
# (your Stremio authKey, to import watch history). Both are personal: never commit them.
#
# Order matters: LayoverTV defines the signature permission this launcher needs to read its feed, so it
# goes first; a launcher installed before it never gets the permission (reinstalling the launcher fixes it).
# Every step that changes the TV is recorded first in $STATE, so you can see what it was before.
set -euo pipefail
export MSYS_NO_PATHCONV=1

TV="${SERIAL:-${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.50}:5555}"   # SERIAL=emulator-5554 REHEARSAL=1 for a dry run on the emulator
ADB="${ADB:-adb}"   # or the full path to adb from the Android SDK platform-tools
HERE="$(cd "$(dirname "$0")" && pwd -W 2>/dev/null || pwd)"
LAYOVER_TV_APK="${LAYOVER_TV_APK:-$HERE/../tv/app/build/outputs/apk/full/release/app-full-armeabi-v7a-release.apk}"
HOME_APK="${HOME_APK:-$HERE/app/build/outputs/apk/release/app-release.apk}"
ADDONS="${ADDONS:-$HOME/.layover/addons.txt}"          # RD-keyed addon URLs: never print them
STREMIO_KEY="${STREMIO_KEY:-$HOME/.layover/stremio_auth.txt}"
STOCK=com.google.android.tvlauncher
RECS=com.google.android.tvrecommendations   # only the stock launcher reads it; it wakes apps to fill its rows
STATE="${STATE:-$HERE/install-state-$(date +%Y%m%d-%H%M%S).txt}"
DEVICE="${DEVICE:-BRAVIA_UR3}"

tv() { "$ADB" -s "$TV" shell "$@" </dev/null; }
home_now() { tv cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1 | tr -d '\r'; }

case "$TV" in emulator-*) ;; *) "$ADB" connect "$TV" >/dev/null ;; esac
[ -n "${REHEARSAL:-}" ] || [ "$(tv getprop ro.product.device | tr -d '\r')" = "$DEVICE" ] || { echo "Not a $DEVICE at $TV, stopping (set DEVICE= to override)." >&2; exit 1; }

if [ "${1:-}" = restore ]; then
  tv pm enable --user 0 "$STOCK" >/dev/null
  tv pm enable --user 0 "$RECS" >/dev/null
  tv cmd package set-home-activity --user 0 "$STOCK/.MainActivity" >/dev/null
  echo "Home now: $(home_now)"
  exit 0
fi

{
  echo "# before install-on-sony.sh, $(date)"
  echo "home=$(home_now)"
  echo "recommendations_enabled=$(tv pm list packages -e "$RECS" | tr -d '\r')"
  echo "stock_launcher_enabled=$(tv pm list packages -e "$STOCK" | tr -d '\r')"
  echo "layover_tv=$(tv pm list packages com.layover.tv | tr -d '\r')"
  echo "tvhome=$(tv pm list packages com.gathin.tvhome | tr -d '\r')"
  echo "screensaver=$(tv settings get secure screensaver_components | tr -d '\r')"
} > "$STATE"
echo "Recorded the TV's state in $STATE"

echo "1/6 LayoverTV"
"$ADB" -s "$TV" install -r "$LAYOVER_TV_APK" | tail -n 1
echo "2/6 Home screen"
"$ADB" -s "$TV" install -r "$HOME_APK" | tail -n 1
if ! tv dumpsys package com.gathin.tvhome | grep -q "com.layover.permission.READ_HOME: granted=true"; then
  echo "   feed permission missing (installed before LayoverTV once): reinstalling the home screen"
  "$ADB" -s "$TV" uninstall com.gathin.tvhome >/dev/null || true
  "$ADB" -s "$TV" install "$HOME_APK" | tail -n 1
fi

echo "3/6 Addons into LayoverTV"
if [ -f "$ADDONS" ]; then
  while IFS= read -r url; do
    url="${url%$'\r'}"; [ -n "$url" ] || continue
    tv "am start -a android.intent.action.VIEW -d '${url/https:\/\//stremio://}' com.layover.tv" >/dev/null 2>&1 || true
    sleep 5
  done < "$ADDONS"
fi

echo "4/6 Stremio watch history into LayoverTV"
if [ -f "$STREMIO_KEY" ]; then
  key="$(tr -d '\r\n' < "$STREMIO_KEY")"
  tv "am broadcast -n com.layover.tv/com.nuvio.tv.layover.importer.StremioImportReceiver --es authKey '$key'" >/dev/null 2>&1 || true
  unset key
  sleep 8
  "$ADB" -s "$TV" logcat -d -s LayoverImport | grep LayoverImport | tail -n 1 || true
fi

echo "5/6 Home screen takes over Home"
tv am start -W -a android.intent.action.MAIN -c android.intent.category.HOME -n com.gathin.tvhome/.MainActivity | grep -E "Status" || true
tv pm grant com.gathin.tvhome android.permission.ACCESS_FINE_LOCATION >/dev/null 2>&1 || true   # Wi-Fi name in the pill
tv cmd package compile -m speed -f com.gathin.tvhome >/dev/null 2>&1 || true
tv pm disable-user --user 0 "$STOCK" >/dev/null
tv cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity >/dev/null
sleep 2
now="$(home_now)"
case "$now" in
  com.gathin.tvhome/*) echo "   Home now: $now"; tv pm disable-user --user 0 "$RECS" >/dev/null ;;
  *) echo "   Home is '$now', not the new launcher: putting Sony's back." >&2
     tv pm enable --user 0 "$STOCK" >/dev/null
     tv cmd package set-home-activity --user 0 "$STOCK/.MainActivity" >/dev/null
     exit 1 ;;
esac

echo "6/6 Check"
sleep 60   # pm disable-user is saved lazily: a reboot right away can undo it
echo "   Done. Press Home on the remote. Undo: ./install-on-sony.sh restore"
