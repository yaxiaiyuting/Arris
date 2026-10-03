#!/usr/bin/env bash
# v2.1.6 · 华为控制中心媒体卡 A/B 定点探针（基线 / 改动后共用）
#
# 为什么单独写一个脚本，而不是只用 regression/capture.sh 的 S6：
#   WGR-W09 的 ROM 会把「历史媒体应用」（华为白名单里的那批）**自己拉起来播放**，
#   于是 capture.sh 跑到 S6 时，卡片显示的可能是 ncm 而不是被测应用。
#   实测踩过两次：一次卡片是 `Dangerous Michael Jackson`、一次是 `The Final Countdown`，
#   而当时 Ncrust 才是在播的那个。这不是脚本 bug，是这台设备的行为，必须显式隔离。
#
# 本脚本的做法：
#   1. 把其它媒体应用的进程停掉（force-stop，可逆，不动数据）；
#   2. **验证**被测应用此刻真的在播且是 media button session —— 不满足就中止；
#   3. 立刻开控制中心、dump、截图；
#   4. 采完再验一次「期间被测应用仍在播」，否则本次无效；
#   5. 同时抓 ROM 对**被测应用**的白名单判定日志。
#
# 用法: card-ab.sh <serial> <label>

set -u
ADB=/usr/bin/adb
S="${1:?usage: card-ab.sh <serial> <label>}"
LABEL="${2:?usage: card-ab.sh <serial> <label>}"
PKG=com.takahashirinta.ncrust
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
D="$ROOT/$LABEL/$S"
mkdir -p "$D"
F="$D/CARD-AB.txt"

OTHER_APPS="com.netease.cloudmusic com.huawei.music com.android.mediacenter com.tencent.qqmusic com.kugou.android tv.danmaku.bili"

# WGR-W09 竖屏锁定时 mini bar 播放键的位置（2560x1600 物理坐标系下实测）。
# 只作媒体键失效时的兜底；坐标变了就是兜底失效 → 脚本会如实中止，不会伪造。
MINI_PLAY_X="${MINI_PLAY_X:-2418}"
MINI_PLAY_Y="${MINI_PLAY_Y:-1479}"

states() {
  $ADB -s "$S" shell dumpsys media_session 2>/dev/null \
    | grep -oE "state=PlaybackState \{state=([A-Z_]+\([0-9]+\)|[0-9]+)" \
    | sed -E 's/.*[=(]([0-9]+)\)?$/\1/' | head -8 | tr '\n' ' '
}
titles() {
  $ADB -s "$S" shell dumpsys media_session 2>/dev/null \
    | grep -E "^\s+metadata:\s*size=" | head -8 | sed 's/^ *//' | tr '\n' '|'
}
mbs() {
  $ADB -s "$S" shell dumpsys media_session 2>/dev/null | grep -m1 "Media button session is" | tr -d '\r'
}
nc_playing() {
  local ds; ds=$($ADB -s "$S" shell dumpsys media_session 2>/dev/null)
  echo "$ds" | grep -qE "^[[:space:]]*[^[:space:]]+ $PKG/" || return 1
  echo "$ds" | awk -v pkg="$PKG" '
    /^[[:space:]]*[^[:space:]]+ [A-Za-z0-9_.]+\/[A-Za-z0-9_.]+/ {
      if (inblk && found) exit 0
      inblk = index($0, pkg"/") > 0; next
    }
    inblk && /state=PlaybackState/ { if ($0 ~ /state=3,/ || $0 ~ /\(3\)/) found = 1 }
    END { exit found ? 0 : 1 }'
}

{
  echo "# 华为控制中心媒体卡 A/B 定点探针 — $LABEL"
  echo "# 设备: $S   $(date -Is)"
  echo
  echo "## 0. ROM 对该应用的白名单判定（本次采集期间的 logcat 原文）"
  $ADB -s "$S" logcat -c >/dev/null 2>&1
  sleep 1
  echo '```'
  $ADB -s "$S" logcat -d 2>/dev/null | grep -iE "media white list" | head -5 || true
  echo '```'
  echo
  echo "## 1. 隔离：停掉其它媒体应用（force-stop，可逆，不动数据）"
  for p in $OTHER_APPS; do
    if [ -n "$($ADB -s "$S" shell pm list packages "$p" 2>/dev/null | tr -d '\r')" ]; then
      echo "\$ adb -s $S shell am force-stop $p"
      $ADB -s "$S" shell am force-stop "$p" 2>&1
    fi
  done
  sleep 2
  echo
  echo "## 2. 让被测应用起播"
  #
  # ⚠️ 真机踩到的两个坑，都记在这里，因为它们直接决定了这个脚本长什么样：
  #
  # 坑 1（ROM 抢焦点）：WGR-W09 的 ROM 有 `startHistoryMediaApp` 行为 —— 一旦没有白名单
  #   内的应用在播，它就把历史媒体应用（这台机器上恒是 ncm）拉起来播。实测：只要把
  #   被测应用 force-stop 掉，随后的媒体键**必然**被 ncm 接走
  #   （连续两次采到的都是 `Dangerous Michael Jackson`）。
  #   ⇒ 所以本脚本**不 force-stop 被测应用**，只隔离别的应用。代价：拿不到「应用冷启时的
  #     白名单判定日志」——那一条改用第 5 步的 pause/play 重新触发（实测会重新打）。
  #
  # 坑 2（坐标随方向变）：`input tap` 用的是**当前朝向的帧缓冲**坐标，而 screencap 的尺寸
  #   会随前台应用翻转（本机 Ncrust 前台=1600x2560 竖屏，桌面/控制中心=2560x1600 横屏）。
  #   写死一组坐标会在换向后打空。⇒ 现算，并按朝向取两套实测比例。
  echo "\$ adb -s $S shell am start -n $PKG/.MainActivity"
  $ADB -s "$S" shell am start -n $PKG/.MainActivity 2>&1 | head -2
  sleep 5

  DIM=$($ADB -s "$S" exec-out screencap -p 2>/dev/null | python3 -c "
import sys
try:
    from PIL import Image
    print(*Image.open(sys.stdin.buffer).size)
except Exception:
    print('0 0')" 2>/dev/null)
  SW=$(echo "$DIM" | awk '{print $1}'); SH=$(echo "$DIM" | awk '{print $2}')
  if [ "${SW:-0}" -gt 0 ] && [ "${SH:-0}" -gt 0 ]; then
    if [ "$SW" -lt "$SH" ]; then
      TAP_X=$(( SW * 914 / 1000 )); TAP_Y=$(( SH * 954 / 1000 )); ORIENT=portrait
    else
      TAP_X=$(( SW * 945 / 1000 )); TAP_Y=$(( SH * 924 / 1000 )); ORIENT=landscape
    fi
    echo "  当前帧缓冲 ${SW}x${SH}（$ORIENT）⇒ mini bar 播放键取 ($TAP_X, $TAP_Y)"
  else
    TAP_X=$MINI_PLAY_X; TAP_Y=$MINI_PLAY_Y; ORIENT=unknown
    echo "  帧缓冲尺寸取不到，退回内置坐标 ($TAP_X, $TAP_Y)"
  fi

  for attempt in 1 2 3; do
    nc_playing && break
    echo "  （第 $attempt 次起播尝试：tap $TAP_X $TAP_Y）"
    $ADB -s "$S" shell input tap "$TAP_X" "$TAP_Y" 2>&1
    sleep 8
  done
  if ! nc_playing; then
    echo "  （点按未起播，退到媒体键 keyevent 126）"
    $ADB -s "$S" shell input keyevent 126 2>&1
    sleep 8
  fi
  # 起播后再清一次历史应用，避免采集期间被 ROM 拉起来抢卡片
  for p in $OTHER_APPS; do
    $ADB -s "$S" shell am force-stop "$p" >/dev/null 2>&1
  done
  sleep 1
  echo "session states: $(states)"
  echo "session titles: $(titles)"
  echo "$(mbs)"
  echo
  if ! nc_playing; then
    echo "⇒ 前置不成立：被测应用不在播放/不是当前播放器。**本次采集无效**，不写入结论。"
    echo "   states=$(states)"
    echo "   titles=$(titles)"
    echo "   mbs=$(mbs)"
    exit 3
  fi
  echo "⇒ 前置成立：$(mbs)"
  echo
  echo "## 3. 打开控制中心并读取媒体卡文本节点"
  # 手势坐标同样必须按**当前帧缓冲**算（坑 2）。先回桌面让朝向稳定，再重新量一次。
  # 实测：Ncrust 前台是 1600x2560 竖屏，桌面/控制中心是 2560x1600 横屏；
  # 用竖屏那套坐标去下拉，只会打到屏幕外，uiautomator 里根本没有 music_item。
  $ADB -s "$S" shell input keyevent 4 >/dev/null 2>&1; sleep 1
  $ADB -s "$S" shell input keyevent 3 >/dev/null 2>&1; sleep 2
  DIM2=$($ADB -s "$S" exec-out screencap -p 2>/dev/null | python3 -c "
import sys
try:
    from PIL import Image
    print(*Image.open(sys.stdin.buffer).size)
except Exception:
    print('0 0')" 2>/dev/null)
  W=$(echo "$DIM2" | awk '{print $1}'); H=$(echo "$DIM2" | awk '{print $2}')
  [ "${W:-0}" -gt 0 ] || { W=2560; H=1600; }
  X=$(( W - 20 )); Y=$(( H * 7 / 8 ))
  CC_XML=""
  for G in "$X 3 $X $Y 250" "$X 3 $X $(( H * 85 / 100 )) 300" "$(( W * 3 / 5 )) 3 $(( W * 35 / 100 )) $Y 250"; do
    echo "\$ adb -s $S shell input swipe $G   # 帧缓冲 ${W}x${H}，右上角下拉=控制中心"
    $ADB -s "$S" shell input swipe $G >/dev/null 2>&1
    sleep 2
    $ADB -s "$S" shell uiautomator dump /sdcard/card.xml >/dev/null 2>&1
    $ADB -s "$S" pull /sdcard/card.xml "$D/CARD-AB.xml" >/dev/null 2>&1
    if grep -q 'com.android.systemui:id/music_item' "$D/CARD-AB.xml" 2>/dev/null; then
      CC_XML="$D/CARD-AB.xml"; echo "  手势命中：$G"; break
    fi
    echo "  手势未命中：$G"
    $ADB -s "$S" shell input keyevent 4 >/dev/null 2>&1; sleep 1
  done
  [ -n "$CC_XML" ] && $ADB -s "$S" exec-out screencap -p >"$D/CARD-AB.png" 2>/dev/null
  echo
  echo "### 媒体卡节点（uiautomator 原文整行）"
  grep -oE '<node[^>]*resource-id="com.android.systemui:id/(music_item|not_playing|pause_music|previous_music|next_music)"[^>]*>' "$D/CARD-AB.xml" 2>/dev/null \
    || echo "  <未取到 —— 本次无效>"
  echo
  echo "### 判定"
  if grep -q 'resource-id="com.android.systemui:id/music_item"' "$D/CARD-AB.xml" 2>/dev/null; then
    DESC=$(grep -oE '<node[^>]*resource-id="com.android.systemui:id/music_item"[^>]*>' "$D/CARD-AB.xml" | grep -oE 'content-desc="[^"]*"' | head -1)
    echo "  music_item $DESC"
    if grep -q 'resource-id="com.android.systemui:id/not_playing"' "$D/CARD-AB.xml" 2>/dev/null; then
      echo "  ⇒ ❌ 卡片显示「未在播放」（not_playing 节点存在）"
    else
      echo "  ⇒ ✅ 卡片显示内容（not_playing 节点不存在）"
    fi
    echo "  ⚠️ 该文本属于哪个应用必须与下面第 4 步一起读 —— ROM 可能显示的是别人。"
  else
    echo "  ⇒ ⚠️ 未取到 music_item，本次无效"
  fi
  echo
  echo "## 4. 采完后复核：被测应用是否仍在播（期间被 ROM 换掉 ⇒ 本次无效）"
  echo "session states: $(states)  titles: $(titles)"
  echo "$(mbs)"
  echo
  echo "## 5. ROM 对本应用的白名单判定日志（pause/play 重新触发）"
  # ROM 的判定日志打在**会话注册/激活**那一刻。被测应用已经在跑时它不会再打，
  # 而冷启又会输给坑 1 的抢焦点 —— 实测 pause 再 play 会重新触发，用它取新鲜证据。
  $ADB -s "$S" logcat -c >/dev/null 2>&1; sleep 1
  $ADB -s "$S" shell input keyevent 127 >/dev/null 2>&1; sleep 2
  $ADB -s "$S" shell input keyevent 126 >/dev/null 2>&1; sleep 4
  echo "匹配行数 = $($ADB -s "$S" logcat -d 2>/dev/null | grep -icE 'media white list')"
  echo '```'
  $ADB -s "$S" logcat -d 2>/dev/null | grep -iE "media white list" | head -10 || true
  echo '```'
  echo
  echo "对照：官方 ncm / 抖音（都在 ROM 名单内）在同一台设备上**不会**出现这行。"
  $ADB -s "$S" shell input keyevent 4 >/dev/null 2>&1
} >"$F" 2>&1
echo "wrote $F"
