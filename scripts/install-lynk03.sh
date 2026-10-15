#!/usr/bin/env bash
# Install the LYNK&CO (DiPlay) APK onto the Lynk & Co 03 head unit (or any adb device).
# Usage:
#   scripts/install-lynk03.sh [apk-path]
#   - APK defaults to the newest DiPlay*-release.apk in the workspace root.
#   - Device auto-detected; if none, tries common wireless-adb ports on 172.20.10.3.
set -euo pipefail

APK="${1:-}"
SERIAL="${LYNK03_SERIAL:-736e58ba}"   # Lynk 03 head unit USB serial
WIFI_HOST="${LYNK03_WIFI_HOST:-172.20.10.3}"
WIFI_PORTS_DEFAULT="5555"

if [[ -z "$APK" ]]; then
  # Newest DiPlay .apk in the workspace root, whatever its tag suffix.
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

# --- find a device -------------------------------------------------------
device=""
pick_device() {
  # Prefer the head unit serial, then any single attached device.
  if adb -s "$SERIAL" get-state 2>/dev/null | grep -q device; then
    device="$SERIAL"; return
  fi
  local list others
  list=$(adb devices | awk 'NR>1 && $2=="device" {print $1}')
  others=$(echo "$list" | grep -v "^$SERIAL$" | grep -v '^$' || true)
  if [[ -n "$others" ]]; then
    device=$(echo "$others" | head -1)
  fi
}

pick_device
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

# --- preinstall: stop the app so the install does not fight a live session -
adb -s "$device" shell am force-stop com.shihab.diplay 2>/dev/null || true

# --- install --------------------------------------------------------------
echo "== installing (in-place, -r) =="
if ! adb -s "$device" install -r "$APK"; then
  echo "install -r failed; retrying with -r -d (downgrade tolerated)"
  adb -s "$device" install -r -d "$APK"
fi

# --- verify + launch ------------------------------------------------------
adb -s "$device" shell dumpsys package com.shihab.diplay | grep -E "versionName|versionCode|lastUpdateTime" | head -3
adb -s "$device" shell monkey -p com.shihab.diplay -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
sleep 2
adb -s "$device" shell dumpsys activity activities | grep -E "topResumedActivity|mResumedActivity" | head -2
echo "== done =="
