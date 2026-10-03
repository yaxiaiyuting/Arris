#!/usr/bin/env bash
# v2.1.6 · 媒体承重结构回归采集（基线 / 改动后共用同一份脚本，保证可比）
#
# 用法: capture.sh <serial> <label>
#   serial = adb 序列号
#   label  = baseline | after   （只决定输出目录，不改行为）
#
# 设计原则：
#   1. 只读 + 媒体按键注入。不安装、不卸载、不清数据、不改设置。
#   2. 每个场景都先记录「注入前状态」，再注入，再记录「注入后状态」——
#      否则无法证明是这次注入起的作用。
#   3. 原文落盘（stdout+stderr+exit code），不做任何改写或美化。
#   4. 拿不到的场景写 FAILED + 原因，绝不静默跳过、绝不伪造。
#
# 覆盖任务书 3.1 的回归表：
#   通知随歌词刷新 / 锁屏 / 蓝牙 AVRCP / 耳机按键 / 控制中心卡片 /
#   播放暂停 seek 立即刷新 / 息屏控制
#   （车机、小米 vivo Pixel 无设备 —— 由 REGRESSION.md 明确标未验证）

set -u
ADB=/usr/bin/adb
S="${1:?usage: capture.sh <serial> <label>}"
LABEL="${2:?usage: capture.sh <serial> <label>}"
PKG=com.takahashirinta.ncrust

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
D="$ROOT/$LABEL/$S"
mkdir -p "$D"

log() { echo "[$(date +%H:%M:%S)] $*"; }
# 每份证据都带自己的命令原文，便于回溯
sh_() { # sh_ <outfile> <cmd...>
  local f="$1"; shift
  {
    echo "\$ $*"
    echo "--- stdout/stderr ---"
    "$@" 2>&1
    echo "--- exit=$? ---"
  } >>"$f"
}

########################################################################
# 0. provenance
########################################################################
PROV="$D/PROVENANCE.md"
{
  echo "# 采集溯源 — $LABEL / $S"
  echo
  echo "- 采集时间(宿主): $(date -Is)"
  echo "- 设备时间: $($ADB -s "$S" shell date 2>&1 | tr -d '\r')"
  echo "- 采集脚本: docs/verification/v2.1.6/regression/capture.sh"
  echo
  echo "| 属性 | 值 |"
  echo "|---|---|"
  for p in ro.product.model ro.product.brand ro.product.manufacturer \
           ro.build.version.release ro.build.version.sdk ro.build.display.id \
           ro.build.characteristics ro.product.cpu.abi; do
    echo "| $p | $($ADB -s "$S" shell getprop $p 2>&1 | tr -d '\r') |"
  done
  echo "| 屏幕 | $($ADB -s "$S" shell wm size 2>&1 | tr -d '\r' | tr '\n' ' ') |"
  echo "| 密度 | $($ADB -s "$S" shell wm density 2>&1 | tr -d '\r' | tr '\n' ' ') |"
  echo
  echo "## 被测应用版本"
  echo '```'
  $ADB -s "$S" shell dumpsys package $PKG 2>&1 | grep -E "versionCode|versionName|firstInstallTime|lastUpdateTime"
  echo '```'
  echo
  echo "## 签名（本应用自己的 cert，仅用于证明「同一签名的前后两版」）"
  echo '```'
  $ADB -s "$S" shell dumpsys package $PKG 2>&1 | grep -A2 "signatures" | head -6
  echo '```'
} >"$PROV" 2>&1

log "provenance -> $PROV"

########################################################################
# 辅助：会话快照（只抽关键行，全文另有 dump）
########################################################################
sessions_brief() {
  $ADB -s "$S" shell dumpsys media_session 2>/dev/null | grep -E \
    "Media button session is|^\s+(NcrustSession|.*androidx\.media3\.session\.id\.)|^\s+state=PlaybackState|^\s+metadata:|^\s+mediaButtonReceiver=|uid=.*packages=" \
    | grep -v "HeadsetMediaButton" | head -40
}

# 本应用所有会话的 state（0=NONE 1=STOPPED 2=PAUSED 3=PLAYING 6=BUFFERING）
nc_states() {
  # 两种 state 打印格式都要认（都在真机上见过）：
  #   AOSP/EMUI  : `state=PlaybackState {state=3, position=...`
  #   ColorOS 16 : `state=PlaybackState {state=PLAYING(3), position=...`
  # 只匹配前一种会让 ColorOS 上 nc_states() 恒为空 —— 那道前提闸就会把
  # **明明在播**的采集判成失败（v2.1.6 采集时真踩过）。
  $ADB -s "$S" shell dumpsys media_session 2>/dev/null \
    | grep -oE "state=PlaybackState \{state=([A-Z_]+\([0-9]+\)|[0-9]+)" \
    | sed -E 's/.*[=(]([0-9]+)\)?$/\1/' | head -8 | tr '\n' ' '
}

# 本应用所有会话的两行文案（description）。切歌 / 歌词推进**只能靠它观测** ——
# 切歌时 state 一直是 3，光看 state 证明不了「下一首真的被按动了」。
nc_titles() {
  # API 24 打的是 `metadata:size=8, ...`（冒号后无空格），API 31/36 打的是 `metadata: size=8`。
  # 两种都要认，否则旧机型上「切歌成功」会因为读不到标题而被误判成失败。
  $ADB -s "$S" shell dumpsys media_session 2>/dev/null \
    | grep -E "^\s+metadata:\s*size=" | head -8 | sed 's/^ *//' | tr '\n' '|'
}

# 被测应用是否存在「state=3 的那条会话」。
# 不能用 `grep -A N state=3` 近似 —— 会话块之间会串（旧机型上第一个块的 state 会把
# 第二个块的空缺补上）。这里按「会话头 = 一行 `名字 包名/类名`」切块，只在**属于本包
# 的那个块内**找 state=3。
nc_has_state3() {
  $ADB -s "$S" shell dumpsys media_session 2>/dev/null | awk -v pkg="$PKG" '
    /^[[:space:]]*[^[:space:]]+ [A-Za-z0-9_.]+\/[A-Za-z0-9_.]+/ {
      if (inblk && found) exit 0
      inblk = index($0, pkg"/") > 0
      next
    }
    inblk && /state=PlaybackState/ {
      if ($0 ~ /state=3,/ || $0 ~ /\(3\)/) found = 1
    }
    END { exit found ? 0 : 1 }
  '
}

# 「被测应用确实在播、且它是系统认定的当前播放器」—— 这是全部结论的前提。
#
# 为什么必须有这道闸：v2.1.6 采集期间踩过一次真事故 —— 另一个 session 在同一台设备上
# 做 ncm 的 A/B，我的脚本照样跑完，抓到的却是**ncm 的会话**
# （`metadata: size=11, description=The Final Countdown, Europe`）。
# 若不检测，这批数据会被当成 Ncrust 的证据写进文档 —— 那就是伪造证据。
under_test_playing() {
  local ds mb api
  ds=$($ADB -s "$S" shell dumpsys media_session 2>/dev/null)
  # (a) 本包必须有会话，且那条会话在播
  echo "$ds" | grep -qE "^[[:space:]]*[^[:space:]]+ $PKG/" || return 1
  nc_has_state3 || return 1
  # (b) 「当前 media button session」必须是被测应用 —— 否则媒体键会发给别人，
  #     控制中心那张卡也会显示别人。API 24 的 dumpsys 根本不打印这一行，那时跳过 (b)，
  #     并在证据里显式记下「本机型无该字段」。
  mb=$(echo "$ds" | grep -m1 "Media button session is" | tr -d '\r')
  if [ -n "$mb" ]; then
    echo "$mb" | grep -q "$PKG" || return 1
  else
    api=$($ADB -s "$S" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')
    echo "  [guard] 本机(API $api) dumpsys 无 'Media button session' 字段，跳过该项校验" >>"$D/GUARD-NOTES.txt"
  fi
  return 0
}

assert_under_test() {
  local where="$1" i
  # 给一点沉降时间：真机上「起播」不是一个瞬时事件 —— 质量档 404 后应用会自己降档重试
  # （S6 实测 hires→lossless），那几百毫秒里 PlaybackState 会短暂是 ERROR(7) / BUFFERING(6)。
  # 单次采样会把这种**正常的**瞬态判成失败。这里最多等 ~40s，仍然不满足才中止。
  for i in 1 2 3 4 5 6 7 8; do
    under_test_playing && return 0
    sleep 5
  done
  {
    echo "PRECONDITION FAILED at $where"
    echo "  被测应用（$PKG）此刻不是「正在播放 + 当前 media button session」。"
    echo "  当前 Media button session: $($ADB -s "$S" shell dumpsys media_session 2>/dev/null | grep -m1 'Media button session is' | tr -d '\r')"
    echo "  当前所有会话 state: $(nc_states)"
    echo "  当前所有会话 titles: $(nc_titles)"
    echo
    echo "  ⇒ 本次采集的后续数据**不可信**，已中止。绝不把别的应用的证据当成 Ncrust 的。"
  } | tee "$D/PRECONDITION-FAILED.txt" >&2
  exit 3
}

# 会话条数（本包）
nc_session_count() {
  $ADB -s "$S" shell dumpsys media_session 2>/dev/null \
    | grep -cE "^\s+\S+ $PKG/" || true
}

########################################################################
# 场景 1 — 会话清单：本包到底暴露几条 MediaSession
########################################################################
log "S1 session inventory"
F="$D/S1-session-inventory.txt"; : >"$F"
sh_ "$F" $ADB -s "$S" shell dumpsys media_session
{
  echo
  echo "===== 本包会话条数 = $(nc_session_count) ====="
  echo "===== 摘要 ====="
  sessions_brief
} >>"$F"

########################################################################
# 场景 2 — 起播 + 通知随歌词刷新
########################################################################
log "S2 playback + notification lyric refresh"
F="$D/S2-notification-lyric-refresh.txt"; : >"$F"
{
  echo "# 场景：让应用在播，间隔 8s 采两次通知，比对正文是否随歌词推进"
  echo
  echo "## 起播命令"
} >>"$F"
sh_ "$F" $ADB -s "$S" shell am start -n $PKG/.MainActivity
sleep 4
sh_ "$F" $ADB -s "$S" shell input keyevent 126   # KEYCODE_MEDIA_PLAY
sleep 5
assert_under_test "S2 起播之后"

for i in 1 2 3; do
  {
    echo
    echo "########## 采样 $i  (t+$(( (i-1)*8 ))s) ##########"
    echo "### 播放状态"
    nc_states
    echo "### 通知正文（android.title / android.text）"
    $ADB -s "$S" shell dumpsys notification --noredact 2>/dev/null \
      | grep -E "pkg=$PKG|android.title=|android.text=|channelId=|id=|isForegroundService=" \
      | head -40
    echo "### 通知栏里属于本包的通知条数（id 行）"
    $ADB -s "$S" shell dumpsys notification --noredact 2>/dev/null \
      | sed -n "/pkg=$PKG/,/^  *$/p" | grep -cE "NotificationRecord" || true
  } >>"$F"
  sleep 8
done

{
  echo
  echo "########## 通知全文（最后一次采样，未裁剪） ##########"
  $ADB -s "$S" shell dumpsys notification --noredact
} >"$D/S2-notification-full.txt" 2>&1

########################################################################
# 场景 3 — 媒体按键矩阵（蓝牙 AVRCP / 耳机按键 的同一条平台路径）
#
# 说明：AVRCP 与耳机按键在平台侧都走 MediaSessionService.dispatchMediaKeyEvent
# -> media button session。本设备没有蓝牙音频外设，用 `input keyevent` 注入
# 同一组 keycode；这是**代理观测**，不是真蓝牙链路，REGRESSION.md 里如实标注。
########################################################################
log "S3 media key matrix"
F="$D/S3-media-keys.txt"; : >"$F"
{
  echo "# 媒体按键矩阵"
  echo "# 注入方式: adb shell input keyevent <code>（InputManager -> MediaSessionService -> media button session）"
  echo "# 每次都是  前状态 -> 注入 -> 后状态，证明因果。"
  echo "# KEYCODE: 85=HEADSETHOOK/PLAY_PAUSE 86=STOP 87=NEXT 88=PREVIOUS 126=PLAY 127=PAUSE"
  echo
} >>"$F"

key_test() {
  local name="$1" code="$2"
  {
    echo "---------- $name (keyevent $code) ----------"
    echo "前 state : $(nc_states)"
    echo "前 titles: $(nc_titles)"
    echo "\$ adb -s $S shell input keyevent $code"
    $ADB -s "$S" shell input keyevent "$code" 2>&1
    sleep 4
    echo "后 state : $(nc_states)"
    echo "后 titles: $(nc_titles)"
    echo "media button session: $($ADB -s "$S" shell dumpsys media_session 2>/dev/null | grep -m1 'Media button session is' | tr -d '\r')"
    echo
  } >>"$F"
}

# 先确保在播
$ADB -s "$S" shell input keyevent 126 >/dev/null 2>&1; sleep 3
assert_under_test "S3 媒体按键矩阵之前"
key_test "PAUSE(127)" 127
key_test "PLAY(126)" 126
key_test "PLAY_PAUSE(85)" 85
key_test "PLAY_PAUSE(85) 第二次" 85
key_test "NEXT(87)" 87
key_test "PREVIOUS(88)" 88
key_test "HEADSETHOOK(79)" 79

########################################################################
# 场景 4 — 播放/暂停/seek 立即刷新
########################################################################
log "S4 immediate refresh"
F="$D/S4-immediate-refresh.txt"; : >"$F"
{
  echo "# 场景：暂停态 -> 播放 -> 暂停 的 position/speed 是否立即反映（不等 2Hz ticker）"
  echo
} >>"$F"
$ADB -s "$S" shell input keyevent 126 >/dev/null 2>&1; sleep 3
{
  echo "## 播放中，连续 3 次 1s 间隔采样（position 应在涨）"
  for i in 1 2 3; do
    echo "--- 采样 $i ---"
    $ADB -s "$S" shell dumpsys media_session 2>/dev/null | grep -E "state=PlaybackState" | head -3
    sleep 1
  done
  echo
  echo "## 注入 PAUSE 后 立刻(0.3s) 与 3s 后 的对比"
  echo "\$ input keyevent 127"
  $ADB -s "$S" shell input keyevent 127
  sleep 0.3
  echo "--- 0.3s 后 ---"
  $ADB -s "$S" shell dumpsys media_session 2>/dev/null | grep -E "state=PlaybackState" | head -3
  sleep 3
  echo "--- 3s 后 ---"
  $ADB -s "$S" shell dumpsys media_session 2>/dev/null | grep -E "state=PlaybackState" | head -3
  echo
  echo "## 恢复播放"
  $ADB -s "$S" shell input keyevent 126
} >>"$F" 2>&1

########################################################################
# 场景 5 — 息屏控制
########################################################################
log "S5 screen-off control"
F="$D/S5-screen-off.txt"; : >"$F"
{
  echo "# 场景：熄屏后注入媒体键，播放状态是否仍然改变（前台服务 + 媒体会话在息屏可用）"
  echo
  echo "## 先确保在播"; nc_states
  echo "\$ input keyevent 26 (POWER -> 熄屏)"
  $ADB -s "$S" shell input keyevent 26
  sleep 3
  echo "屏幕状态: $($ADB -s "$S" shell dumpsys power 2>/dev/null | grep -m1 -E 'mWakefulness=' | tr -d '\r')"
  echo "## 熄屏下注入 PAUSE"
  $ADB -s "$S" shell input keyevent 127
  sleep 3
  echo "后: $(nc_states)"
  echo "## 熄屏下注入 PLAY"
  $ADB -s "$S" shell input keyevent 126
  sleep 3
  echo "后: $(nc_states)"
  echo "## 唤醒(不锁屏密码时)"
  $ADB -s "$S" shell input keyevent 26
  sleep 2
  echo "屏幕状态: $($ADB -s "$S" shell dumpsys power 2>/dev/null | grep -m1 -E 'mWakefulness=' | tr -d '\r')"
} >>"$F" 2>&1

########################################################################
# 场景 6 — 控制中心媒体卡（华为 WGR-W09 专有；其它机型自动跳过并说明）
########################################################################
log "S6 control center card"
F="$D/S6-control-center-card.txt"; : >"$F"
{
  echo "# 场景：控制中心媒体卡文本节点（uiautomator dump，不靠肉眼猜截图）"
  echo
} >>"$F"

IS_HUAWEI=$($ADB -s "$S" shell getprop ro.product.brand 2>/dev/null | tr -d '\r')
if [ "$IS_HUAWEI" = "HUAWEI" ] || [ "$IS_HUAWEI" = "HONOR" ]; then
  $ADB -s "$S" shell input keyevent 126 >/dev/null 2>&1; sleep 4
  assert_under_test "S6 控制中心卡片之前"
  {
    echo "## 采集时播放状态: $(nc_states)"
    echo
  } >>"$F"

  # 关掉可能开着的面板，回桌面，再从右上角下拉控制中心
  $ADB -s "$S" shell input keyevent 4 >/dev/null 2>&1; sleep 1
  $ADB -s "$S" shell input keyevent 3 >/dev/null 2>&1; sleep 1
  # ⚠️ 实测坑：`input swipe` 的坐标走的是**物理**屏幕坐标系（2560x1600），
  # 不是 `wm size` 里的 Override（1600x2560）。用 Override 宽（1580）下拉会打开
  # **通知栏**（左侧面板）而不是控制中心，于是 uiautomator 里根本没有 music_item。
  # 所以这里取 Physical size，并对多个候选手势逐个验证，取第一个真的出卡的。
  SIZE=$($ADB -s "$S" shell wm size 2>/dev/null | sed -n 's/.*Physical size: \([0-9]*\)x\([0-9]*\).*/\1 \2/p')
  W=$(echo "$SIZE" | awk '{print $1}'); H=$(echo "$SIZE" | awk '{print $2}')
  [ -z "$W" ] && W=2560; [ -z "$H" ] && H=1600
  X=$(( W - 20 ))
  Y=$(( H * 6 / 10 ))

  {
    echo "\$ adb -s $S shell input swipe <变体>   # 从右上角下拉 = 控制中心（物理坐标系）"
  } >>"$F"
  # 候选手势逐个试，取第一个能拿到 music_item 的；三个都不行就如实记「本次采集无效」，不伪造。
  CC_XML=""
  # 实测（WGR-W09, 物理 2560x1600）：只有「右边缘、从顶划到 7/8 高度」这一条稳定出卡；
  # 划到 6/10 高度有时只展开通知栏。所以把已验证的那条放第一个，其余作兜底。
  for G in "$X 3 $X $(( H * 7 / 8 )) 250" "$X 3 $X $(( H * 85 / 100 )) 300" "$(( W * 3 / 5 )) 3 $(( W * 35 / 100 )) $(( H * 7 / 8 )) 250"; do
    $ADB -s "$S" shell input keyevent 4 >/dev/null 2>&1; sleep 1
    $ADB -s "$S" shell input keyevent 3 >/dev/null 2>&1; sleep 1
    echo "  \$ adb -s $S shell input swipe $G" >>"$F"
    $ADB -s "$S" shell input swipe $G >/dev/null 2>&1
    sleep 2
    $ADB -s "$S" shell uiautomator dump /sdcard/cc_dump.xml >/dev/null 2>&1
    $ADB -s "$S" pull /sdcard/cc_dump.xml "$D/S6-control-center.xml" >/dev/null 2>&1
    if grep -q 'com.android.systemui:id/music_item' "$D/S6-control-center.xml" 2>/dev/null; then
      CC_XML="$D/S6-control-center.xml"; echo "  手势命中：$G" >>"$F"; break
    fi
    echo "  手势未命中：$G" >>"$F"
  done
  [ -n "$CC_XML" ] && $ADB -s "$S" exec-out screencap -p >"$D/S6-control-center.png" 2>/dev/null
  {
    echo "焦点: $($ADB -s "$S" shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus | tr -d '\r')"
  } >>"$F"
  {
    echo
    echo "## uiautomator 原文中的媒体卡节点（完整 <node> 行）"
    grep -oE '<node[^>]*resource-id="com.android.systemui:id/(music_item|not_playing|pause_music|previous_music|next_music|qs_music_view|not_playing_view)"[^>]*>' \
      "$D/S6-control-center.xml" 2>/dev/null || echo "  <NO MATCH — 面板可能没打开>"
    echo
    echo "## 判定"
    if grep -q 'resource-id="com.android.systemui:id/music_item"' "$D/S6-control-center.xml" 2>/dev/null; then
      DESC=$(grep -oE '<node[^>]*resource-id="com.android.systemui:id/music_item"[^>]*>' "$D/S6-control-center.xml" \
             | grep -oE 'content-desc="[^"]*"' | head -1)
      echo "  music_item 的 $DESC"
      grep -q 'resource-id="com.android.systemui:id/not_playing"' "$D/S6-control-center.xml" \
        && echo "  ⇒ ❌ 卡片处于「未在播放」态（not_playing 节点存在）" \
        || echo "  ⇒ ✅ 卡片未处于「未在播放」态（not_playing 节点不存在）"
    else
      echo "  ⇒ ⚠️ 未取到 music_item 节点，本次控制中心采集无效"
    fi
    echo
    echo "## 关闭面板"
  } >>"$F"
  $ADB -s "$S" shell input keyevent 4 >/dev/null 2>&1
else
  {
    echo "SKIPPED: 本机 brand=$IS_HUAWEI，不是华为/荣耀，没有该控制中心媒体卡。"
    echo "（非华为机型的媒体面板证据见 S7）"
  } >>"$F"
fi

########################################################################
# 场景 7 — 非华为机型的系统媒体面板（通知栏 / 锁屏的数据源）
########################################################################
log "S7 aosp media panel"
F="$D/S7-aosp-media-panel.txt"; : >"$F"
{
  echo "# 场景：非华为机型的系统媒体面板证据 = 通知栏媒体通知 + 会话元数据"
  echo "# （Android 11+ 的媒体面板直接消费 MediaSession 的 metadata/token，没有独立的 ROM 白名单）"
  echo
  echo "## 会话元数据（面板显示什么，这里就应该是什么）"
  $ADB -s "$S" shell dumpsys media_session 2>/dev/null | grep -E "Media button session is|state=PlaybackState|metadata:|^\s+\S+ $PKG/" | head -20
  echo
  echo "## 通知栏里本包的媒体通知（含 MediaStyle token 指向）"
  $ADB -s "$S" shell dumpsys notification --noredact 2>/dev/null \
    | sed -n "/pkg=$PKG/,/^  *$/p" | head -60
  echo
  echo "## 系统媒体面板（Android 11+ 的 MediaCarouselController / MediaDataManager，若有）"
  $ADB -s "$S" shell dumpsys activity service SystemUIService 2>/dev/null | grep -iA6 "MediaDataManager" | head -40 \
    || echo "  <无该服务或无 MediaDataManager>"
} >>"$F" 2>&1

########################################################################
# 场景 8 — 锁屏
########################################################################
log "S8 lockscreen"
F="$D/S8-lockscreen.txt"; : >"$F"
{
  echo "# 场景：熄屏再亮屏后的锁屏媒体控件"
  echo "# 注意：本机若有锁屏密码，截屏只会拿到密码界面 —— 那就如实记录「拿不到」，不伪造。"
  echo
  echo "## 采集前播放状态: $(nc_states)"
  $ADB -s "$S" shell input keyevent 26 >/dev/null 2>&1; sleep 3
  $ADB -s "$S" shell input keyevent 26 >/dev/null 2>&1; sleep 2
  echo "## 亮屏后焦点: $($ADB -s "$S" shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus | tr -d '\r')"
  echo "## 是否有锁屏密码: $($ADB -s "$S" shell locksettings get-disabled 2>/dev/null | tr -d '\r')"
} >>"$F" 2>&1
$ADB -s "$S" exec-out screencap -p >"$D/S8-lockscreen.png" 2>/dev/null
$ADB -s "$S" shell uiautomator dump /sdcard/ls.xml >/dev/null 2>&1
$ADB -s "$S" pull /sdcard/ls.xml "$D/S8-lockscreen.xml" >/dev/null 2>&1
{
  echo
  echo "## 锁屏 uiautomator 里与本包/媒体相关的节点"
  grep -oE '<node[^>]*(music|media|album|title|artist)[^>]*>' "$D/S8-lockscreen.xml" 2>/dev/null | head -20 \
    || echo "  <NO MATCH>"
  echo
  echo "## 本包在锁屏上是否有节点（package=$PKG）"
  grep -c "$PKG" "$D/S8-lockscreen.xml" 2>/dev/null || echo 0
} >>"$F" 2>&1

########################################################################
# 场景 9 — logcat：会话稳定性（"Media button session is changed to" 抖动）
#
# 这是 v2.1.6 会话合并**唯一可被直接观测**的收益指标：
# 合并前 Ncrust 同时暴露 NcrustSession 与 androidx.media3.session.id.*，
# ROM 的 media button session 在两者之间反复跳；合并后应当**一次都不跳**。
########################################################################
log "S9 logcat session stability"
F="$D/S9-logcat-session.txt"; : >"$F"
{
  echo "# 场景：播放期间抓 logcat，统计「Media button session 被改写」的次数"
  echo "# 判据：合并后 Ncrust 只应有一条会话 ⇒ 不应再出现「在两条 Ncrust 会话之间跳」"
  echo
} >>"$F"
$ADB -s "$S" shell input keyevent 126 >/dev/null 2>&1; sleep 2
$ADB -s "$S" logcat -c >/dev/null 2>&1
for i in 1 2 3 4 5 6; do
  $ADB -s "$S" shell input keyevent 126 >/dev/null 2>&1
  sleep 4
done
$ADB -s "$S" logcat -d >"$D/S9-logcat-full.txt" 2>&1
{
  echo "## 原始 logcat 全文: S9-logcat-full.txt ($(wc -l <"$D/S9-logcat-full.txt") 行)"
  echo
  echo "## MediaSessionService / 华为媒体判定相关行"
  grep -iE "Media button session|HwMediaSession|MediaControlUtils|media white list|HwMediaSessionService" "$D/S9-logcat-full.txt" | head -60 \
    || echo "  <无匹配>"
  echo
  echo "## 「Media button session is changed to」出现次数"
  grep -c "Media button session is changed to" "$D/S9-logcat-full.txt" || true
  echo
  echo "## 其中涉及本应用的行"
  grep "Media button session is changed to" "$D/S9-logcat-full.txt" | grep -F "$PKG" | head -20 \
    || echo "  <没有涉及本应用的改写 —— 合并生效>"
  echo
  echo "## 华为媒体白名单判定行（本包）"
  grep -iE "media white list" "$D/S9-logcat-full.txt" | grep -F "$PKG" | head -10 \
    || echo "  <本机没有该判定日志（非华为 ROM）>"
} >>"$F" 2>&1

########################################################################
# 收尾：留下最终状态，别让设备停在奇怪的地方
########################################################################
$ADB -s "$S" shell input keyevent 3 >/dev/null 2>&1
$ADB -s "$S" shell input keyevent 126 >/dev/null 2>&1

log "DONE -> $D"
ls -la "$D"
