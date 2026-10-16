#!/usr/bin/env bash
# Install the LYNK&CO (DiPlay) APK onto the Lynk & Co 03 head unit (or any adb device).
#
# Usage:
#   scripts/install-lynk03.sh [apk-path]
#
#   - APK defaults to the newest DiPlay*.apk in the workspace root.
#   - Device auto-detected (head unit USB serial, or any single device, or wireless adb).
#   - Install strategies, tried in order (the head unit's adb rejects streamed installs):
#       1. adb install --no-streaming -r
#       2. adb install -r -d  (downgrade tolerated)
#       3. push to /data/local/tmp + pm install -r   (works even when abb_exec is broken)
#   - If the old nomfi build (a known radio interferer) is present, it is reported.
set -euo pipefail

APK="${1:-}"
SERIAL="${LYNK03_SERIAL:-736e58ba}"   # Lynk 03 head unit USB serial
PKG="com.shihab.diplay"
WIFI_HOST="${LYNK03_WIFI_HOST:-172.20.10.3}"
WIFI_PORTS_DEFAULT="5555"

if [[ -z "$APK" ]]; then
  APK=$(ls -t "C:/Users/IT/.zcode/workspace/default/"DiPlay*.apk 2>/dev/null | head -1 || true)
  if [[ -z "$APK" ]]; then
    echo "ERROR: no DiPlay*.apk found in workspace; pass one as argument 1" >&2
    exit 1
  fi
fi
if [[ ! -f "$APK" ]]; then
  echo "ERROR: APK not found: $APK" >&2
  exit 1
fi

echo "== APK: $APK ($(du -h "$APK" | cut -f1)) =="

# --- adb server: a stale server breaks streamed installs on the head unit ---
if ! adb devices 2>/dev/null | grep -q "device$"; then
  adb kill-server >/dev/null 2>&1 || true
  sleep 2
  adb start-server >/dev/null 2>&1 || true
  sleep 2
fi

# --- find a device -------------------------------------------------------
device=""
if adb -s "$SERIAL" get-state 2>/dev/null | grep -q device; then
  device="$SERIAL"
else
  list=$(adb devices | awk 'NR>1 && $2=="device" {print $1}')
  others=$(echo "$list" | grep -v "^$SERIAL$" | grep -v '^$' || true)
  if [[ -n "$others" ]]; then
    device=$(echo "$others" | head -1)
  fi
fi
if [[ -z "$device" ]]; then
  echo "== no device attached; trying wireless adb on $WIFI_HOST =="
  for port in ${LYNK03_WIFI_PORTS:-$WIFI_PORTS_DEFAULT}; do
    adb connect "$WIFI_HOST:$port" >/dev/null 2>&1 || true
    if adb devices | awk 'NR>1 {print $1" "$2}' | grep -q "$WIFI_HOST:$port device"; then
      device="$WIFI_HOST:$port"; break
    fi
  done
fi
if [[ -z "$device" ]]; then
  echo "ERROR: no adb device found. Plug in USB or enable wireless adb and retry." >&2
  exit 1
fi
echo "== device: $device =="

# --- model guard (informational) -----------------------------------------
model=$(adb -s "$device" shell getprop ro.product.model 2>/dev/null | tr -d '\r' || echo "?")
android=$(adb -s "$device" shell getprop ro.build.version.release 2>/dev/null | tr -d '\r' || echo "?")
echo "== target: $model / Android $android =="

# --- report the known radio interferer if it came back --------------------
if adb -s "$device" shell pm list packages 2>/dev/null | grep -q "com.shilapi.xcertplay.nomfi"; then
  echo "WARNING: com.shilapi.xcertplay.nomfi is installed — it fights the Wi-Fi radio in the"
  echo "         background. Uninstall it: adb -s $device shell pm uninstall com.shilapi.xcertplay.nomfi"
fi

# --- preinstall: stop the app so the install does not fight a live session -
adb -s "$device" shell am force-stop "$PKG" 2>/dev/null || true

# --- install --------------------------------------------------------------
install_push_pm() {
  echo "== install strategy 3: push + pm install =="
  adb -s "$device" push "$APK" /data/local/tmp/diplay-install.apk || return 1
  adb -s "$device" shell pm install -r /data/local/tmp/diplay-install.apk || return 1
  adb -s "$device" shell rm -f /data/local/tmp/diplay-install.apk || true
  return 0
}

echo "== installing (in-place, -r) =="
installed=0
echo "-- strategy 1: install --no-streaming --"
if adb -s "$device" install --no-streaming -r "$APK"; then
  installed=1
else
  echo "-- strategy 2: install -r -d (downgrade tolerated) --"
  if adb -s "$device" install -r -d "$APK"; then
    installed=1
  else
    if install_push_pm; then
      installed=1
    fi
  fi
fi
if [[ "$installed" != "1" ]]; then
  echo "ERROR: all install strategies failed." >&2
  exit 1
fi

# --- verify + launch ------------------------------------------------------
echo "== installed =="
adb -s "$device" shell dumpsys package "$PKG" | grep -E "versionName|versionCode|lastUpdateTime" | head -3
adb -s "$device" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
sleep 2
adb -s "$device" shell dumpsys activity activities | grep -E "topResumedActivity|mResumedActivity" | head -2
echo "== done =="
