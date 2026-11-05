#!/usr/bin/env bash
set -euo pipefail
serial=emulator-5588
pkg=com.takahashirinta.ncrust
fixture=/tmp/arris-player-redesign-fixture
out=/home/duanjb666/deepseek/arris/dist/player-redesign-preview
adb1() { adb -s "$serial" "$@"; }
mkdir -p "$out"
node_xy() {
  local mode=$1 label=$2
  adb1 shell uiautomator dump /sdcard/window.xml >/dev/null
  adb1 shell cat /sdcard/window.xml > "$fixture/window.xml"
  python3 - "$fixture/window.xml" "$mode" "$label" <<'PY'
import re, sys, xml.etree.ElementTree as ET
doc, key, expected = sys.argv[1:]
items = [n for n in ET.parse(doc).iter('node') if n.get(key) == expected]
if not items:
    raise SystemExit(f'No {key}={expected}')
node=items[-1]
x0,y0,x1,y1=map(int,re.findall(r'\d+',node.get('bounds','')))
assert x0<x1 and y0<y1,node.attrib
print((x0+x1)//2,(y0+y1)//2)
PY
}
tap_desc() {
  local coords
  coords=$(node_xy content-desc "$1")
  adb1 shell input tap $coords
  sleep 1.0
}
launch() {
  local style=$1
  adb1 shell am force-stop "$pkg"
  adb1 shell -T "run-as $pkg sh -c 'cat > shared_prefs/ncrust_settings.xml'" < "$fixture/settings-$style.xml"
  adb1 shell am start -W -n "$pkg/.MainActivity" >/dev/null
  sleep 2
  local coords y
  coords=$(node_xy text 'Night Tide · 夜潮')
  y=${coords##* }
  adb1 shell input tap 80 "$y"
  sleep 2.5
  node_xy content-desc '收起' >/dev/null
}
capture() { adb1 exec-out screencap -p > "$out/$1.png"; }
adb1 install -r /home/duanjb666/deepseek/arris/repos/Arris-ui-refresh/app/build/outputs/apk/debug/app-debug.apk
adb1 shell cmd window user-rotation lock 0
adb1 shell wm size 900x1950
adb1 shell wm density 420
launch DARK
if node_xy content-desc '分享歌词' >/dev/null 2>&1; then tap_desc '歌词'; fi
capture portrait-dark
tap_desc '歌词'
node_xy content-desc '分享歌词' >/dev/null
capture portrait-lyrics
tap_desc '队列'
node_xy content-desc '清空队列' >/dev/null
capture portrait-queue
tap_desc '大屏幕模式'
sleep 1
node_xy content-desc '退出大屏幕模式' >/dev/null
capture landscape
tap_desc '退出大屏幕模式'
sleep 1
adb1 shell cmd window user-rotation lock 0
launch LIGHT
if node_xy content-desc '分享歌词' >/dev/null 2>&1; then tap_desc '歌词'; fi
capture portrait-light
tap_desc '收起'
node_xy text 'Night Tide · 夜潮' >/dev/null
adb1 shell wm size 800x1100
adb1 shell wm density 400
launch DARK
if node_xy content-desc '分享歌词' >/dev/null 2>&1; then tap_desc '歌词'; fi
sleep 1
capture compact
for item in portrait-dark portrait-lyrics portrait-queue landscape portrait-light compact; do
  magick identify "$out/$item.png"
done
magick "$out/portrait-dark.png" "$out/portrait-lyrics.png" "$out/portrait-light.png" \
  -resize 330x704 -background '#dce8e1' -gravity center -extent 350x730 +append "$out/player-preview.png"
