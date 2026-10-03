#!/usr/bin/env bash
# probe-device-cold-warm.sh —— 真机「首次 vs 第二次」搜索耗时（读 app 自己无条件打的 aggregate 日志行）
#
# 为什么这么测：release 包（BuildConfig.DEBUG=false）里 QQ 侧的 Log.d 全部被关掉，
# 唯一还活着的埋点是 SearchViewModel 的 Log.i（SearchViewModel.kt:202-207），
# 它给出**一轮聚合搜索的总耗时**（ncm leg + QQ leg，因为两者串行）与两侧条数。
#
# 用法: probe-device-cold-warm.sh <serial> <rounds> <outfile>
set -u
ADB=/usr/bin/adb
DEV="${1:?serial}"
ROUNDS="${2:-3}"
OUT="${3:-/tmp/cold-warm.txt}"
PKG=com.takahashirinta.ncrust

TAP_SEARCH_TAB="762 2686"   # 底部导航「搜索」（1272x2800）
TAP_HISTORY="570 705"       # 历史里第一条「晴天」
TAP_CLEAR="1080 267"        # 搜索框右侧 ×

log() { echo "$@" | tee -a "$OUT"; }

grab() { # 读最近一轮的 aggregate 行
  local pid
  pid=$($ADB -s "$DEV" shell pidof $PKG | tr -d '\r')
  $ADB -s "$DEV" shell "logcat -d --pid=$pid -v threadtime 2>/dev/null" \
    | grep -E "aggregate query|netease search failed|qq search failed|QqMusicSource" | tail -6
}

: > "$OUT"
log "# device=$DEV rounds=$ROUNDS  $(date -Iseconds)"

for phase in cold warm; do
  for i in $(seq 1 "$ROUNDS"); do
    if [ "$phase" = cold ]; then
      $ADB -s "$DEV" shell am force-stop $PKG; sleep 1.5
      $ADB -s "$DEV" shell am start -n $PKG/.MainActivity >/dev/null 2>&1
      sleep 8
      $ADB -s "$DEV" shell input tap $TAP_SEARCH_TAB; sleep 2
    else
      $ADB -s "$DEV" shell input tap $TAP_CLEAR; sleep 1.4
    fi
    $ADB -s "$DEV" shell "logcat -c"
    log "--- $phase #$i  tap@host $(date +%H:%M:%S.%3N) ---"
    $ADB -s "$DEV" shell input tap $TAP_HISTORY
    sleep 9
    grab | tee -a "$OUT"
  done
done
log "# done $(date -Iseconds)"
