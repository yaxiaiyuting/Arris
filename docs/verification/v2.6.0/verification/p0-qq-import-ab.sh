#!/usr/bin/env bash
# v2.6.0 · P0 真机 A/B：**QQ 曲目「加入库」后，刷新与重启都不消失**。
#
# ## 为什么用「注入 + 真实刷新」而不是纯 UI 点击
#
# 用户报告的现象是「入库 → 短暂可见 → 刷新后消失」，根因在**存储与同步层**
# （`LibraryManager.refreshFromCloud` 用云端 likedIds 重建整张表）。
# 这条 A/B 直接构造那个根因的**输入条件**：
#
#   磁盘上有一条「本地来源、云端没有」的条目（QQ 曲目就是这一类，
#   因为它的合成 id 带 bit62、永远进不了 ncm 的 likedIds），
#   然后让**真实的 refreshFromCloud**（真实 cookie + 真实网络）跑一遍，
#   看这条条目还在不在。
#
# 注入的形状是 **v1 裸 `SongItem` 数组**（`[{"id":<QQ合成id>,"name":…}]`）——
# 那正是 v2.5.6 在「用户刚点完加入库」那一刻磁盘上的真实形状
# （S6 实测：147 条里每条的 key 恰好是 al/ar/dt/id/name 五个）。
# 所以这个 A/B 的**输入在两个版本上逐字节相同**，差别只在被测代码。
#
# ## 为什么这不是「伪造证据」
#
# - 注入的是**磁盘状态**（用户数据），不是任何测试结果；
# - 「能不能存活」由**被测应用自己的代码路径**（启动 → 进库页 → refreshFromCloud
#   → scheduleFlush）决定，脚本只读回磁盘；
# - 每一次运行都把 BEFORE / AFTER 的原始 prefs 片段与判定打印出来，可复核。
#
# 用法：p0-qq-import-ab.sh <serial> <label>
#   例：p0-qq-import-ab.sh 0715f763f54c023a BEFORE-v2.5.6
set -uo pipefail

SERIAL="${1:?usage: p0-qq-import-ab.sh <serial> <label>}"
LABEL="${2:?usage: p0-qq-import-ab.sh <serial> <label>}"
PKG=com.takahashirinta.ncrust
PREFS=/data/data/$PKG/shared_prefs/ncrust_library.xml
OUT="${OUT_DIR:-/tmp/v260-ab}"
mkdir -p "$OUT"

# 注入用的 QQ 合成 id：`(1L shl 62) or 1234567890` —— 与 `SourceIds.qqId` 同式。
QQ_ID=$(( (1 << 62) + 1234567890 ))
QQ_NAME="AB探针曲目"

say() { printf '%s\n' "$*"; }
prefs() { adb -s "$SERIAL" shell "su -c 'cat $PREFS'" 2>/dev/null; }

say "=== [$LABEL] device=$SERIAL ==="
adb -s "$SERIAL" shell getprop ro.product.model
adb -s "$SERIAL" shell "dumpsys package $PKG | grep -E 'versionName|versionCode' | head -2"
adb -s "$SERIAL" shell "dumpsys deviceidle | grep -m1 mScreenOn" >/dev/null 2>&1 || true

# --- 1) 备份 + 读 BEFORE -----------------------------------------------------
adb -s "$SERIAL" shell "su -c 'cp $PREFS /data/local/tmp/ncrust_library.bak'" >/dev/null 2>&1
prefs > "$OUT/$LABEL-before.xml"
python3 - "$OUT/$LABEL-before.xml" "$LABEL" "$QQ_ID" <<'PY'
import html, json, re, sys
path, label, qq = sys.argv[1], sys.argv[2], int(sys.argv[3])
raw = open(path, encoding='utf-8', errors='replace').read()
m = re.search(r'<string name="saved_songs">(.*?)</string>', raw, re.S)
arr = json.loads(html.unescape(m.group(1))) if m else []
print(f"[{label}] BEFORE entries={len(arr)}  qq_present={any(e.get('id')==qq for e in arr)}")
print(f"[{label}] BEFORE qq_ids(>=2^62)={sum(1 for e in arr if e.get('id',0) >= (1<<62))}")
PY

# --- 2) 注入一条「本地来源的 QQ 曲目」（v1 形状，与 v2.5.6 自己写出的一致） ----
adb -s "$SERIAL" shell "am force-stop $PKG"
python3 - "$OUT/$LABEL-before.xml" "$OUT/$LABEL-injected.json" "$QQ_ID" "$QQ_NAME" <<'PY'
import html, json, re, sys
src, dst, qq, name = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4]
raw = open(src, encoding='utf-8', errors='replace').read()
m = re.search(r'<string name="saved_songs">(.*?)</string>', raw, re.S)
arr = json.loads(html.unescape(m.group(1))) if m else []
arr = [e for e in arr if e.get('id') != qq]
# 插到最前：与 `saveSong` 的「新加的放最前」一致。
arr.insert(0, {"al": {"id": 1, "name": "AB", "picUrl": None}, "ar": [{"id": 1, "name": "AB"}],
               "dt": 200000, "id": qq, "name": name})
open(dst, 'w', encoding='utf-8').write(json.dumps(arr, ensure_ascii=False))
print(f"injected -> {dst} (entries={len(arr)})")
PY

# 写回 prefs（XML 转义后整表替换 saved_songs 的值）
python3 - "$OUT/$LABEL-before.xml" "$OUT/$LABEL-injected.json" "$OUT/$LABEL-injected.xml" <<'PY'
import html, re, sys
src, js, dst = sys.argv[1], sys.argv[2], sys.argv[3]
raw = open(src, encoding='utf-8', errors='replace').read()
val = html.escape(open(js, encoding='utf-8').read(), quote=True)
out = re.sub(r'(<string name="saved_songs">).*?(</string>)', lambda m: m.group(1) + val + m.group(2),
             raw, count=1, flags=re.S)
assert out != raw, "saved_songs key not found"
open(dst, 'w', encoding='utf-8').write(out)
print(f"wrote {dst}")
PY

adb -s "$SERIAL" push "$OUT/$LABEL-injected.xml" /data/local/tmp/ncrust_library.new >/dev/null 2>&1
adb -s "$SERIAL" shell "su -c 'cp /data/local/tmp/ncrust_library.new $PREFS && chown \$(stat -c %u:%g $PREFS) $PREFS && chmod 660 $PREFS'" >/dev/null 2>&1
PREFS_AFTER_INJECT="$(prefs | md5sum | cut -d' ' -f1)"
say "[$LABEL] prefs md5 after inject = $PREFS_AFTER_INJECT"

# --- 3) 真实启动 → 进库页（触发 refreshFromCloud）→ 等落盘 ---------------------
adb -s "$SERIAL" shell "monkey -p $PKG -c android.intent.category.LAUNCHER 1" >/dev/null 2>&1
# ⚠️ 等待长度是**实测**定的，不是拍的：S6 上这个账号有 1063 首红心歌，
#    `refreshFromCloud` 要等 `getLikedTrackIds`（全量 id）+ `getSongsByIds`（首批 50 首详情）
#    回来才会走到重建那一步 —— logcat 实测 `I LibraryManager: refreshFromCloud: likedIds=1063`
#    出现在**启动后约 40s**。等太短会得到「条目存活」的**假阳性**（因为 flush 根本没发生），
#    本脚本第一版就是这样骗了自己一次。
sleep 70
# 底部导航「库」。坐标由 uiautomator 的语义树找，不写死。
BASH_SRC="$(dirname "$0")/ui-drive.sh"
if bash "$BASH_SRC" "$SERIAL" tap "库" >/dev/null 2>&1; then
  say "[$LABEL] tapped 库 tab"
else
  say "[$LABEL] WARN: 找不到「库」tab 节点，退化为直接等待（首页也会做 preload，但 refreshFromCloud 只在库页）"
fi
# 再等一轮：切到库页会**再**触发一次 `LaunchedEffect(Unit)`，refreshFromCloud 是幂等的，
# 但落盘要等它跑完。
sleep 70
adb -s "$SERIAL" shell "am force-stop $PKG"
sleep 2

# --- 4) 读 AFTER（进程已杀，落盘的是 refresh 之后的结果） --------------------
prefs > "$OUT/$LABEL-after-refresh.xml"
python3 - "$OUT/$LABEL-after-refresh.xml" "$LABEL" "$QQ_ID" <<'PY'
import html, json, re, sys
path, label, qq = sys.argv[1], sys.argv[2], int(sys.argv[3])
raw = open(path, encoding='utf-8', errors='replace').read()
m = re.search(r'<string name="saved_songs">(.*?)</string>', raw, re.S)
arr = json.loads(html.unescape(m.group(1))) if m else []
present = any(e.get('id') == qq for e in arr)
# v2 信封形状判定：新版本落盘的元素带 trackKey/origin/tombstoned。
envelope = bool(arr) and isinstance(arr[0], dict) and ('trackKey' in arr[0] or 'origin' in arr[0])
print(f"[{label}] AFTER-REFRESH entries={len(arr)}  qq_present={present}  v2_envelope={envelope}")
print(f"[{label}] VERDICT(refresh): {'SURVIVED' if present else 'LOST'}")
if present:
    e = next(x for x in arr if x.get('id') == qq)
    print(f"[{label}] entry={json.dumps(e, ensure_ascii=False)[:300]}")
PY

# --- 5) 重启一次再读（任务书要求「刷新、重启均不消失」） ---------------------
adb -s "$SERIAL" shell "monkey -p $PKG -c android.intent.category.LAUNCHER 1" >/dev/null 2>&1
sleep 70
bash "$BASH_SRC" "$SERIAL" tap "库" >/dev/null 2>&1 || true
sleep 70
adb -s "$SERIAL" shell "am force-stop $PKG"
sleep 2
prefs > "$OUT/$LABEL-after-restart.xml"
python3 - "$OUT/$LABEL-after-restart.xml" "$LABEL" "$QQ_ID" <<'PY'
import html, json, re, sys
path, label, qq = sys.argv[1], sys.argv[2], int(sys.argv[3])
raw = open(path, encoding='utf-8', errors='replace').read()
m = re.search(r'<string name="saved_songs">(.*?)</string>', raw, re.S)
arr = json.loads(html.unescape(m.group(1))) if m else []
present = any(e.get('id') == qq for e in arr)
print(f"[{label}] AFTER-RESTART entries={len(arr)}  qq_present={present}")
print(f"[{label}] VERDICT(restart): {'SURVIVED' if present else 'LOST'}")
PY
say "=== [$LABEL] done; raw files in $OUT ==="
