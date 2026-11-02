#!/usr/bin/env bash
# Phone-side smoke test for Bento. Run by .github/workflows/tests.yml while an Android emulator is running.
#
#   ui_smoke.sh <app.apk> <results folder>
#
# Optional environment:
#   OLD_APK / NEW_APK        two releases that were published one after the other, to test updating from one to the next
#   LATEST_RELEASE_NUMBER    number of the newest release, to know whether the "Version x is available" banner must show
#
# It does not stop at the first problem. Every check is written down, and the exit status is 1 if any failed.
# Failures are also printed as GitHub annotations, so they show on the run page without opening the logs.

set -u
export PATH="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}/platform-tools:$PATH"

APK="${1:?usage: ui_smoke.sh <app.apk> <results folder>}"
OUT="${2:?usage: ui_smoke.sh <app.apk> <results folder>}"
OLD_APK="${OLD_APK:-}"
NEW_APK="${NEW_APK:-}"
LATEST_RELEASE_NUMBER="${LATEST_RELEASE_NUMBER:-}"

HERE="$(cd "$(dirname "$0")" && pwd)"
PKG=com.example.pssender
ACT="$PKG/.MainActivity"
XML="$OUT/ui.xml"
SUMMARY="$OUT/summary.md"
SHOTS="$OUT/shots"
RTL_LANGS=" ar he fa ur "
# Languages that also get a walk through every tab (the rest only get a look at the first screen)
TOUR_LANGS=" ar de ja th hi fi "

mkdir -p "$SHOTS"
: > "$SUMMARY"
PASS=0
FAIL=0
SOFT=0

ui() { python3 -I "$HERE/uitool.py" "$@"; }

# ---------------------------------------------------------------------------------------------
# Recording results
# ---------------------------------------------------------------------------------------------

clean() { printf '%s' "$1" | tr '\r\n' '  ' | sed 's/%/%25/g' | cut -c1-1800; }

pass() {
  PASS=$((PASS + 1))
  echo "ok     $1"
  echo "| ✅ | $1 |" >> "$SUMMARY"
}

fail() { # name, what was seen
  FAIL=$((FAIL + 1))
  echo "FAILED $1 -- $2"
  echo "| ❌ | $1 — $(clean "$2") |" >> "$SUMMARY"
  if [ "$FAIL" -le 8 ]; then echo "::error title=Phone test failed::$(clean "$1 -- $2")"; fi
}

# A check that depends on the internet or on timing: it is shown as a warning and does not turn the run red
soft() { # name, what was seen
  SOFT=$((SOFT + 1))
  echo "WARN   $1 -- $2"
  echo "| ⚠️ | $1 — $(clean "$2") |" >> "$SUMMARY"
  if [ "$SOFT" -le 6 ]; then echo "::warning title=Phone test note::$(clean "$1 -- $2")"; fi
}

# ---------------------------------------------------------------------------------------------
# Talking to the phone
# ---------------------------------------------------------------------------------------------

shot() { adb exec-out screencap -p > "$SHOTS/$1.png" 2> /dev/null || true; }

# Fills $XML with what is on screen. The system sometimes answers "could not get idle state", so it tries a few times.
dump() {
  local i
  for i in 1 2 3 4; do
    rm -f "$XML"
    adb shell rm -f /sdcard/ui.xml > /dev/null 2>&1
    adb shell uiautomator dump /sdcard/ui.xml > "$OUT/dump.log" 2>&1
    if adb pull /sdcard/ui.xml "$XML" > /dev/null 2>&1 && [ -s "$XML" ]; then return 0; fi
    sleep 1.5
  done
  return 1
}

alive() { [ -n "$(adb shell pidof "$PKG" 2> /dev/null | tr -d '\r')" ]; }

focused() { adb shell dumpsys window 2> /dev/null | tr -d '\r' | grep -E 'mCurrentFocus|mFocusedApp' | grep -q "$PKG"; }

# Says what went wrong (a crash or "not responding") since the last `adb logcat -c`; prints nothing when all is well
trouble() {
  local c a
  c="$(adb logcat -d -b crash 2> /dev/null | tr -d '\r' | grep -E "Process: $PKG" | head -2)"
  a="$(adb logcat -d -b main 2> /dev/null | tr -d '\r' | grep -E "ANR in $PKG" | head -1)"
  printf '%s %s' "$c" "$a" | tr '\n' ' ' | sed 's/  */ /g; s/^ //; s/ $//'
}

launch() {
  adb logcat -c > /dev/null 2>&1
  adb shell am start -W -n "$ACT" > "$OUT/start.log" 2>&1
  sleep 3
}

tap_text() { # taps the lowest thing on screen that shows one of the texts
  local pos
  pos="$(ui find "$XML" "$@")" || return 1
  # shellcheck disable=SC2086
  set -- $pos
  adb shell input tap "$1" "$2"
}

NAV_KEYS="nav_install nav_payload nav_upload nav_files nav_web nav_memory"

# The six names on the bottom bar, in the language of the folder given (default English)
nav_label() { ui string "$1" "${2:-values}" || ui string "$1"; }

bar_visible() { # [values folder]: the screen is in $XML (dump first)
  local k
  for k in $NAV_KEYS; do
    ui find "$XML" "$(nav_label "$k" "${1:-values}")" > /dev/null || return 1
  done
}

wait_for_bar() { # [seconds] [values folder]
  local i
  for ((i = 0; i < ${1:-20}; i += 2)); do
    if dump && bar_visible "${2:-values}"; then return 0; fi
    sleep 2
  done
  return 1
}

# Is the app up, in front, free of crashes, and showing its bottom bar? Records one result under $1.
healthy() { # name [values folder]
  local why="" t
  alive || why="the app is not running. "
  focused || why="${why}the app is not in front. "
  t="$(trouble)"
  [ -n "$t" ] && why="${why}crash/ANR: $t. "
  if [ -z "$why" ]; then
    if wait_for_bar 10 "${2:-values}"; then
      pass "$1"
    else
      dump > /dev/null 2>&1
      fail "$1" "the bottom bar is not on screen. Layout: $(ui layout "$XML" 2> /dev/null)"
      shot "fail-$(echo "$1" | tr -c 'A-Za-z0-9' '-' | cut -c1-40)"
    fi
  else
    fail "$1" "$why"
    shot "fail-$(echo "$1" | tr -c 'A-Za-z0-9' '-' | cut -c1-40)"
  fi
}

# ---------------------------------------------------------------------------------------------
# 0. The phone itself
# ---------------------------------------------------------------------------------------------

echo "== phone: $(adb shell getprop ro.product.model | tr -d '\r') / Android $(adb shell getprop ro.build.version.release | tr -d '\r') (API $(adb shell getprop ro.build.version.sdk | tr -d '\r'))"
SIZE="$(adb shell wm size 2> /dev/null | tr -d '\r' | grep -oE '[0-9]+x[0-9]+' | tail -1)"
WIDTH="${SIZE%x*}"
adb shell settings put system accelerometer_rotation 0 > /dev/null 2>&1
adb shell settings put system user_rotation 0 > /dev/null 2>&1
adb shell settings put global always_finish_activities 0 > /dev/null 2>&1
adb uninstall "$PKG" > /dev/null 2>&1

{
  echo "## Bento on a phone (emulator)"
  echo
  echo "| | check |"
  echo "|---|---|"
} > "$SUMMARY"

# ---------------------------------------------------------------------------------------------
# 1. Updating from one release to the next (the way you do it: install the new APK over the old one)
# ---------------------------------------------------------------------------------------------

if [ -n "$OLD_APK" ] && [ -f "$OLD_APK" ] && [ -n "$NEW_APK" ] && [ -f "$NEW_APK" ]; then
  echo "== update: $(basename "$OLD_APK") -> $(basename "$NEW_APK")"
  r="$(adb install -r -g "$OLD_APK" 2>&1)"
  case "$r" in
    *Success*) pass "the older release ($(basename "$OLD_APK")) installs" ;;
    *) fail "the older release ($(basename "$OLD_APK")) installs" "$r" ;;
  esac
  launch
  sleep 4
  adb shell am force-stop "$PKG"
  r="$(adb install -r -g "$NEW_APK" 2>&1)"
  case "$r" in
    *Success*) pass "the next release ($(basename "$NEW_APK")) installs over it, keeping the app's data" ;;
    *UPDATE_INCOMPATIBLE*) fail "the next release installs over the older one" "different signing key: $r" ;;
    *VERSION_DOWNGRADE*) soft "update check skipped" "the two newest releases are not in order: $r" ;;
    *) fail "the next release installs over the older one" "$r" ;;
  esac
  launch
  healthy "the app starts after being updated, with the data of the older release"
  adb uninstall "$PKG" > /dev/null 2>&1
else
  soft "update check skipped" "fewer than two releases to compare"
fi

# ---------------------------------------------------------------------------------------------
# 2. A fresh install of the build from this commit
# ---------------------------------------------------------------------------------------------

echo "== fresh install: $APK"
r="$(adb install -r -g "$APK" 2>&1)"
case "$r" in
  *Success*) pass "this build installs" ;;
  *)
    fail "this build installs" "$r"
    echo "Nothing more can be tested without an installed app."
    echo "::notice title=Phone test::$PASS passed, $FAIL failed"
    exit 1
    ;;
esac

launch
# A fresh install opens on the welcome page; its button closes it for good. (Wait for the logo animation first: a tap
# while the page is still moving into place can miss the button.)
sleep 2
shot "00-welcome"
if dump && tap_text "$(ui string welcome_go)"; then
  sleep 2
  # Still there? Tap once more
  if dump && ui find "$XML" "$(ui string welcome_go)" > /dev/null 2>&1; then
    tap_text "$(ui string welcome_go)" || true
    sleep 2
  fi
  pass "the welcome page shows on the first start and closes with its button"
else
  soft "welcome page" "no welcome page on the first start (an older build?). Seen: $(ui texts "$XML" 2> /dev/null)"
fi
healthy "the app starts and shows its six tabs"
shot "01-start"

# The "Version x is available" banner: this build has a small version number, so a published release is newer
APK_CODE=""
AAPT2="$(ls -d "${ANDROID_HOME:-/usr/local/lib/android/sdk}"/build-tools/*/aapt2 2> /dev/null | sort -V | tail -1)"
[ -n "$AAPT2" ] && APK_CODE="$("$AAPT2" dump badging "$APK" 2> /dev/null | grep -oE "versionCode='[0-9]+'" | head -1 | tr -dc '0-9')"
if [ -n "$APK_CODE" ] && [ -n "$LATEST_RELEASE_NUMBER" ] && [ "$APK_CODE" -lt "$LATEST_RELEASE_NUMBER" ] 2> /dev/null; then
  found=""
  for _ in $(seq 1 12); do
    if dump && ui findfmt "$XML" "$(ui string update_available)" > /dev/null; then found=1; break; fi
    sleep 3
  done
  if [ -n "$found" ]; then
    pass "the update check finds the newer release on GitHub and shows the banner"
  else
    soft "update banner" "build $APK_CODE is older than release $LATEST_RELEASE_NUMBER but no banner showed (network?). Seen: $(ui texts "$XML" 2> /dev/null)"
  fi
else
  echo "(no banner check: build ${APK_CODE:-?} / newest release ${LATEST_RELEASE_NUMBER:-?})"
fi

# ---------------------------------------------------------------------------------------------
# 3. Every tab
# ---------------------------------------------------------------------------------------------

tab_keys() { # what each tab shows on a fresh install (any of these texts will do)
  case "$1" in
    nav_install) echo "install_links_label install_paste install_port_label" ;;
    nav_payload) echo "payload_shelf_title payload_loader_port store_open" ;;
    nav_upload) echo "ftp_port ftp_user ftp_password upload_choose upload_start upload_help sync_title" ;;
    nav_files) echo "files_ftp_settings files_refresh files_need_ip files_up files_view_apps" ;;
    nav_web) echo "web_add web_bookmarks web_find web_reload" ;;
    nav_memory) echo "memory_connect memory_port memory_intro" ;;
  esac
}

# Is any of the tab's own texts on screen (in $XML)? Prints the one it found.
content_visible() { # nav key
  local k v
  for k in $(tab_keys "$1"); do
    v="$(ui string "$k")" || continue
    case "$v" in
      *%*) if ui findfmt "$XML" "$v" > /dev/null; then echo "$v"; return 0; fi ;;
      *) if ui find "$XML" "$v" > /dev/null; then echo "$v"; return 0; fi ;;
    esac
  done
  return 1
}

visit_tab() { # nav key, values folder, label prefix
  local key="$1" dir="${2:-values}" prefix="${3:-}" label texts k v
  label="$(nav_label "$key" "$dir")"
  adb logcat -c > /dev/null 2>&1
  dump || true
  if ! tap_text "$label"; then
    fail "${prefix}tab \"$label\" can be tapped" "not on screen. Seen: $(ui texts "$XML" 2> /dev/null)"
    return
  fi
  sleep 3
  dump || true
  if ! ui haspkg "$XML" "$PKG"; then
    fail "${prefix}tab \"$label\" opens" "the app is not showing. Seen: $(ui texts "$XML" 2> /dev/null)"
    shot "fail-tab-$key"
    return
  fi
  if [ "$dir" = values ]; then
    local hit
    if hit="$(content_visible "$key")"; then
      pass "${prefix}tab \"$label\" shows its screen (\"$hit\")"
    else
      fail "${prefix}tab \"$label\" shows its screen" "none of the expected texts. Layout: $(ui layout "$XML" 2> /dev/null)"
      shot "fail-tab-$key"
    fi
  fi
  healthy "${prefix}tab \"$label\" leaves the app healthy" "$dir"
  shot "tab-${prefix:+$(echo "$prefix" | tr -c 'A-Za-z0-9' '-')}$key"
}

for key in $NAV_KEYS; do
  visit_tab "$key" values ""
done

# ---------------------------------------------------------------------------------------------
# 4. Turning the phone, leaving and coming back, starting again
# ---------------------------------------------------------------------------------------------

rotate() { # 0 = upright, 1 = on its side
  adb shell settings put system accelerometer_rotation 0 > /dev/null 2>&1
  adb shell settings put system user_rotation "$1" > /dev/null 2>&1
  sleep 3
}

echo "== turning, leaving, restarting"
# The tour ends on the last tab (Memory); this part turns the Web tab
dump || true
tap_text "$(nav_label nav_web)" || true
sleep 2
adb logcat -c > /dev/null 2>&1
rotate 1
healthy "the Web tab on its side"
if hit="$(content_visible nav_web)"; then
  pass "the Web tab still shows its own controls on its side (\"$hit\")"
else
  fail "the Web tab still shows its own controls on its side" "Layout: $(ui layout "$XML" 2> /dev/null)"
fi
shot "web-landscape"
rotate 0
healthy "the Web tab upright again"

dump || true
tap_text "$(nav_label nav_install)" || true
sleep 2
adb logcat -c > /dev/null 2>&1
rotate 1
healthy "the Install tab on its side"
if hit="$(content_visible nav_install)"; then
  pass "the Install tab still shows its own fields on its side (\"$hit\")"
else
  fail "the Install tab still shows its own fields on its side" "Layout: $(ui layout "$XML" 2> /dev/null)"
fi
shot "install-landscape"
rotate 0
healthy "the Install tab upright again"

# "Don't keep activities": the phone throws the screen away whenever the app is left, like a phone short on memory
adb shell settings put global always_finish_activities 1 > /dev/null 2>&1
adb logcat -c > /dev/null 2>&1
adb shell input keyevent KEYCODE_HOME
sleep 2
adb shell am start -n "$ACT" > /dev/null 2>&1
sleep 4
healthy "the app comes back after the phone threw its screen away"
adb shell settings put global always_finish_activities 0 > /dev/null 2>&1

adb shell am force-stop "$PKG"
launch
healthy "the app starts again after being stopped completely"

# Opened from the share sheet with a link (how a link from a browser arrives)
adb logcat -c > /dev/null 2>&1
adb shell am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "https://example.com/a.pkg" -n "$ACT" > /dev/null 2>&1
sleep 4
dump || true
if ui findfmt "$XML" "$(ui string choose_install)" > /dev/null; then
  pass "a link shared from another app opens the \"Send link to ... installer\" choice"
else
  fail "a link shared from another app opens the \"Send link to ... installer\" choice" "Layout: $(ui layout "$XML" 2> /dev/null)"
fi
shot "shared-link"
adb shell input keyevent KEYCODE_BACK
sleep 2
healthy "the app is fine after closing that choice"

# ---------------------------------------------------------------------------------------------
# 5. All the languages
# ---------------------------------------------------------------------------------------------

echo "== languages"
adb shell am force-stop "$PKG"
launch
sleep 2
LANG_OK=0
LANG_BAD=0
for tag in $(ui langs); do
  dir="$(ui resdir "$tag")" || { soft "language $tag" "no translation folder found for it"; continue; }
  adb logcat -c > /dev/null 2>&1
  adb shell cmd locale set-app-locales "$PKG" --locales "$tag" > "$OUT/locale.log" 2>&1
  sleep 2.5
  dump || true
  problems=""
  alive || problems="${problems}the app is not running. "
  t="$(trouble)"
  [ -n "$t" ] && problems="${problems}crash/ANR: $t. "
  missing=""
  first=""
  last=""
  for k in $NAV_KEYS; do
    label="$(nav_label "$k" "$dir")"
    ui find "$XML" "$label" > /dev/null || missing="$missing \"$label\""
  done
  [ -n "$missing" ] && problems="${problems}tab names not on screen:$missing. "
  # Right-to-left languages put the first tab on the right, the others on the left
  first="$(ui find "$XML" "$(nav_label nav_install "$dir")")" && last="$(ui find "$XML" "$(nav_label nav_memory "$dir")")"
  if [ -n "$first" ] && [ -n "$last" ]; then
    fx="${first%% *}"
    lx="${last%% *}"
    case "$RTL_LANGS" in
      *" $tag "*) [ "$fx" -gt "$lx" ] || problems="${problems}right-to-left language but the first tab is not on the right. " ;;
      *) [ "$fx" -lt "$lx" ] || problems="${problems}left-to-right language but the first tab is not on the left. " ;;
    esac
    # Every tab name must lie fully on screen
    if [ -n "${WIDTH:-}" ]; then
      for k in $NAV_KEYS; do
        pos="$(ui find "$XML" "$(nav_label "$k" "$dir")")" || continue
        # shellcheck disable=SC2086
        set -- $pos
        if [ "$3" -lt 0 ] || [ "$4" -gt "$WIDTH" ]; then problems="${problems}tab \"$(nav_label "$k" "$dir")\" runs off the screen. "; fi
      done
    fi
  fi
  if [ -z "$problems" ]; then
    LANG_OK=$((LANG_OK + 1))
  else
    LANG_BAD=$((LANG_BAD + 1))
    fail "language $tag ($dir)" "$problems Seen: $(ui texts "$XML" 2> /dev/null)"
    shot "fail-lang-$tag"
  fi
  case "$TOUR_LANGS" in
    *" $tag "*)
      shot "lang-$tag"
      for k in $NAV_KEYS; do visit_tab "$k" "$dir" "[$tag] "; done
      # back to the first tab for the next language
      dump || true
      tap_text "$(nav_label nav_install "$dir")" || true
      sleep 1
      ;;
  esac
done
if [ "$LANG_BAD" -eq 0 ]; then
  pass "all $LANG_OK languages show the six tabs correctly (right order, nothing cut off, no crash)"
fi
adb shell cmd locale set-app-locales "$PKG" --locales en > /dev/null 2>&1

# ---------------------------------------------------------------------------------------------
# 6. Using a pretend console (fake_console.py on the build machine, which the emulator reaches at 10.0.2.2)
# ---------------------------------------------------------------------------------------------

FAKE_LOG="${FAKE_CONSOLE_LOG:-}"
FAKE_HOST=10.0.2.2

# Waits until one of the texts is on screen (in $XML). Prints what it found.
wait_text() { # seconds text...
  local secs="$1" i
  shift
  for ((i = 0; i < secs; i += 2)); do
    if dump && ui find "$XML" "$@" > /dev/null; then return 0; fi
    sleep 2
  done
  return 1
}

wait_fmt() { # seconds format
  local i
  for ((i = 0; i < $1; i += 2)); do
    if dump && ui findfmt "$XML" "$2" > /dev/null; then return 0; fi
    sleep 2
  done
  return 1
}

# Like wait_text, but scrolls the screen down a little between looks (the emulator's screen is small)
scroll_find() { # tries text...
  local tries="$1" i
  shift
  for ((i = 0; i < tries; i++)); do
    if dump && ui find "$XML" "$@" > /dev/null; then return 0; fi
    adb shell input swipe 160 460 160 220 400 > /dev/null 2>&1
    sleep 1.5
  done
  return 1
}

# The Files tab remembers its last view: back to the folder view, scrolled to the top
files_folders() {
  dump || true
  tap_text "$(nav_label nav_files)" || true
  sleep 3
  dump && tap_text "$(ui string files_view_browse)" || true
  sleep 3
  adb shell input swipe 160 220 160 600 300 > /dev/null 2>&1
  adb shell input swipe 160 220 160 600 300 > /dev/null 2>&1
  sleep 1
}

wait_log() { # seconds text: waits for the pretend console to print a line with the text
  local i
  for ((i = 0; i < $1; i += 2)); do
    grep -qF "$2" "$FAKE_LOG" 2> /dev/null && return 0
    sleep 2
  done
  return 1
}

if [ -n "$FAKE_LOG" ] && [ -f "$FAKE_LOG" ] && grep -q "Fake console running" "$FAKE_LOG"; then
  echo "== pretend console at $FAKE_HOST"
  # A saved console pointing at it, written into the app's settings the way the app saves them (a debug build lets
  # the test reach the app's own files). The app's other settings stay as they are.
  adb shell am force-stop "$PKG"
  adb shell run-as "$PKG" cat shared_prefs/settings.xml > "$OUT/settings-before.xml" 2> /dev/null || true
  python3 -I - "$OUT/settings-before.xml" "$OUT/settings.xml" "$FAKE_HOST" << 'PY'
import json, sys, xml.etree.ElementTree as ET
src, dst, host = sys.argv[1:4]
try:
    root = ET.parse(src).getroot()
    if root.tag != "map":
        raise ValueError
except Exception:
    root = ET.Element("map")
def put(kind, name, value):
    for e in list(root):
        if e.get("name") == name:
            root.remove(e)
    e = ET.SubElement(root, kind, name=name)
    e.text = value
console = {"id": "fake", "name": "Pretend PS5", "host": host, "installPort": "12800", "payloadPort": "9021",
           "ftpPort": "2121", "ftpUser": "anonymous", "ftpDir": "/data", "color": 2, "type": "ps5"}
put("string", "profiles_v1", json.dumps([console]))
put("string", "active_profile", "fake")
put("string", "debug_port", "7744")
ET.ElementTree(root).write(dst, encoding="utf-8", xml_declaration=True)
PY
  adb push "$OUT/settings.xml" /data/local/tmp/bento-settings.xml > /dev/null 2>&1
  adb shell "cat /data/local/tmp/bento-settings.xml | run-as $PKG sh -c 'mkdir -p shared_prefs && cat > shared_prefs/settings.xml'"
  if adb shell run-as "$PKG" cat shared_prefs/settings.xml 2> /dev/null | grep -q "$FAKE_HOST"; then
    pass "a console at the pretend console's address is saved in the app"
  else
    fail "a console at the pretend console's address is saved in the app" "run-as could not write the settings"
  fi
  launch
  healthy "the app starts with the pretend console selected"

  # Files: the console's /data folder over FTP
  files_folders
  if ! scroll_find 6 notes.txt sample.bin; then
    files_folders
    dump && tap_text "$(ui string files_refresh)" || true
    sleep 4
  fi
  if scroll_find 8 notes.txt sample.bin; then
    pass "Files shows the console's /data folder over FTP"
  else
    fail "Files shows the console's /data folder over FTP" "Seen: $(ui texts "$XML" 2> /dev/null)"
    shot "fail-fake-files"
  fi
  shot "fake-files"

  # Files > Games & apps: the installed games, read from the console over FTP
  dump && tap_text "$(ui string files_view_apps)" || true
  if wait_text 30 "Fake Game" "Fake Game (PS5)" CUSA01234 PPSA01234; then
    pass "Games & apps lists the console's installed games"
  else
    fail "Games & apps lists the console's installed games" "Seen: $(ui texts "$XML" 2> /dev/null)"
    shot "fail-fake-apps"
  fi
  shot "fake-apps"
  healthy "the app is fine after browsing the console"

  # Install: a link shared from a browser, sent to the console's installer
  LINK="http://$FAKE_HOST:12800/sample.pkg"
  adb logcat -c > /dev/null 2>&1
  adb shell am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "$LINK" -n "$ACT" > /dev/null 2>&1
  sleep 4
  if wait_fmt 10 "$(ui string choose_install)"; then
    pos="$(ui findfmt "$XML" "$(ui string choose_install)")"
    # shellcheck disable=SC2086
    set -- $pos
    adb shell input tap "$1" "$2"
    if wait_log 20 "[install/etaHEN] url=$LINK"; then
      pass "a shared link reaches the console's installer"
    else
      fail "a shared link reaches the console's installer" "the pretend console got no install request. Its log: $(tail -5 "$FAKE_LOG" | tr '\n' ' ')"
    fi
    if wait_fmt 15 "$(ui string install_accepted)"; then
      pass "the app says the installer accepted it"
    else
      fail "the app says the installer accepted it" "Seen: $(ui texts "$XML" 2> /dev/null)"
    fi
    shot "fake-install"
  else
    fail "a shared link offers to send it to the installer" "Seen: $(ui texts "$XML" 2> /dev/null)"
  fi
  healthy "the app is fine after sending a link"

  # Memory: connect the debugger
  dump || true
  tap_text "$(nav_label nav_memory)" || true
  sleep 3
  if wait_text 10 "$(ui string memory_connect)"; then
    tap_text "$(ui string memory_connect)" || true
    # Connected shows the game the pretend console runs (and its firmware line, when it fits on screen)
    if wait_text 20 "Fake Game" || wait_fmt 4 "$(ui string memory_connected)"; then
      pass "Memory connects to the console's debugger"
    else
      fail "Memory connects to the console's debugger" "Seen: $(ui texts "$XML" 2> /dev/null)"
      shot "fail-fake-memory"
    fi
  else
    fail "Memory shows its Connect button" "Seen: $(ui texts "$XML" 2> /dev/null)"
  fi
  shot "fake-memory"
  healthy "the app is fine after connecting the debugger"

  # --- Newer checks: shown as notes (they don't turn the run red) until they have been seen to work ---

  # Payload: one from the shelf goes to the console's loader (9021)
  head -c 4096 /dev/urandom > "$OUT/bento-test.elf"
  adb push "$OUT/bento-test.elf" /data/local/tmp/bento-test.elf > /dev/null 2>&1
  adb shell "cat /data/local/tmp/bento-test.elf | run-as $PKG sh -c 'mkdir -p files/payloads && cat > files/payloads/bento-test.elf'"
  dump || true
  tap_text "$(nav_label nav_payload)" || true
  sleep 3
  if wait_text 10 bento-test.elf && tap_text "$(ui string action_send)"; then
    if wait_log 20 "[payload] received 4096 bytes"; then
      pass "a payload from the shelf reaches the console's loader"
    else
      soft "payload send" "the pretend console got no payload. Its log: $(tail -3 "$FAKE_LOG" | tr '\n' ' ')"
    fi
  else
    soft "payload send" "the test payload or its Send button is not on screen. Seen: $(ui texts "$XML" 2> /dev/null)"
  fi
  shot "fake-payload"

  # etaHEN settings: read from the console over FTP and shown as switches
  files_folders
  if scroll_find 8 "$(ui string etahen_title)" && tap_text "$(ui string etahen_title)"; then
    if wait_text 15 "$(ui string etahen_ftp)"; then
      pass "etaHEN settings are read from the console and shown"
    else
      soft "etaHEN settings" "the settings did not show. Seen: $(ui texts "$XML" 2> /dev/null)"
    fi
    shot "fake-etahen"
    adb shell input keyevent KEYCODE_BACK
    sleep 2
  else
    soft "etaHEN settings" "no etaHEN settings button. Seen: $(ui texts "$XML" 2> /dev/null)"
  fi

  # Zip: the /data folder downloaded as one zip (go up to /, hold "data", Download as zip)
  files_folders
  dump && tap_text "$(ui string files_up)" || true
  sleep 3
  if scroll_find 8 data user && pos="$(ui find "$XML" data)"; then
    # shellcheck disable=SC2086
    set -- $pos
    adb shell input swipe "$1" "$2" "$1" "$2" 900
    sleep 2
    if wait_text 6 "$(ui string zip_download)" && tap_text "$(ui string zip_download)"; then
      if wait_fmt 40 "$(ui string zip_done)"; then
        pass "a console folder downloads as a zip"
      else
        soft "zip download" "no \"saved\" message. Seen: $(ui texts "$XML" 2> /dev/null)"
      fi
    else
      soft "zip download" "no \"Download as zip\" in the folder's menu. Seen: $(ui texts "$XML" 2> /dev/null)"
    fi
  else
    soft "zip download" "the console's top folder did not show. Seen: $(ui texts "$XML" 2> /dev/null)"
  fi
  shot "fake-zip"
  healthy "the app is fine after the newer checks"
else
  soft "pretend console" "not running (FAKE_CONSOLE_LOG unset or the console did not start), so nothing was used for real"
fi

# ---------------------------------------------------------------------------------------------
# 6. Wrap up
# ---------------------------------------------------------------------------------------------

adb logcat -d > "$OUT/logcat.txt" 2> /dev/null || true
adb shell cmd locale set-app-locales "$PKG" --locales "" > /dev/null 2>&1 || true

{
  echo
  echo "**$PASS passed, $FAIL failed, $SOFT notes.** Screenshots and the phone's log are in the run's \"phone-test-results\" download."
} >> "$SUMMARY"

echo "== $PASS passed, $FAIL failed, $SOFT notes"
echo "::notice title=Phone test::$PASS passed, $FAIL failed, $SOFT notes"
[ "$FAIL" -eq 0 ]
