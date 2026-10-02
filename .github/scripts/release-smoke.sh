#!/usr/bin/env bash
# Installs the minified release APK on the emulator and drives it like a teacher would:
# open app -> tap "Cathy" -> tap "Ready!" -> check the passage screen appears and the app is still alive.
set -u
APK="$1"; OUT="$2"; PKG=vn.softworld.readingrobot
mkdir -p "$OUT"
adb uninstall $PKG >/dev/null 2>&1 || true
adb install -r -g "$APK" || exit 1
adb logcat -c
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null
sleep 8

tap_text() {   # find a text on screen via the accessibility tree and tap its centre
  for i in 1 2 3 4 5 6; do
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    adb shell cat /sdcard/ui.xml > /tmp/ui.xml
    B=$(python3 - "$1" <<'PY'
import re,sys,html
xml=open('/tmp/ui.xml',encoding='utf-8',errors='ignore').read()
for m in re.finditer(r'<node [^>]*>', xml):
    n=m.group(0)
    t=re.search(r' text="([^"]*)"', n); d=re.search(r'content-desc="([^"]*)"', n)
    if (t and html.unescape(t.group(1))==sys.argv[1]) or (d and html.unescape(d.group(1))==sys.argv[1]):
        b=re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if b:
            x1,y1,x2,y2=map(int,b.groups()); print((x1+x2)//2,(y1+y2)//2); break
PY
)
    if [ -n "$B" ]; then adb shell input tap $B; return 0; fi
    sleep 2
  done
  echo "text not found: $1"; return 1
}
has_text() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml | grep -q "$1"; }
alive() { adb shell pidof $PKG >/dev/null; }

ok=1
adb shell screencap -p /sdcard/rel-1-home.png
tap_text "Cathy" || ok=0
sleep 4; adb shell screencap -p /sdcard/rel-2-greet.png
tap_text 'hand' || ok=0          # the "Raise your hand" button
sleep 12; adb shell screencap -p /sdcard/rel-3-passage.png
has_text "LEVEL B" || { echo "passage screen not shown"; ok=0; }
alive || { echo "app process died"; ok=0; }
adb pull /sdcard/rel-1-home.png "$OUT"/ ; adb pull /sdcard/rel-2-greet.png "$OUT"/ ; adb pull /sdcard/rel-3-passage.png "$OUT"/
adb logcat -d | grep -E "AndroidRuntime|FATAL|ReadingRobot|vosk|Vosk" | tail -80 > "$OUT/release-logcat.txt"
if grep -q "FATAL EXCEPTION" "$OUT/release-logcat.txt"; then echo "crash in release build"; ok=0; fi
[ $ok = 1 ] && echo "RELEASE SMOKE OK" || { echo "RELEASE SMOKE FAILED"; exit 1; }
