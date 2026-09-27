#!/bin/bash
# ============================================================================
# Ncrust · B站音源调研 · 实测取证脚本（v3.1.0）
# ----------------------------------------------------------------------------
# 一条命令复现 EVIDENCE.md 里的全部原始证据。
#
#   * **直连**：显式 unset 掉环境里的 http_proxy/https_proxy（127.0.0.1:10808）
#   * 每条请求记录：时间戳 / HTTP 状态 / 耗时 / 出口 IP / 原始响应体
#   * 需要走代理的 GitHub 调用不在本脚本内（见 community-implementations.md）
#
# 用法：
#   bash collect_evidence.sh              # 全跑
#   bash collect_evidence.sh songinfo     # 只跑名字里含 songinfo 的
#
# 输出：./evidence/<NN>-<name>.txt  +  ./evidence/90-wbi-ab.txt
# ============================================================================
set -u
unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY

UA='Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
AUDIO_REF='https://www.bilibili.com/audio/home'
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/evidence"
mkdir -p "$OUT"
ONLY="${1:-all}"
IDX=0

req() {  # req <name> <url> [extra curl args...]
  local name="$1"; shift
  local url="$1"; shift
  if [ "$ONLY" != "all" ] && [[ "$name" != *"$ONLY"* ]]; then return; fi
  IDX=$((IDX+1))
  local f; f=$(printf "%s/%02d-%s.txt" "$OUT" "$IDX" "$name")
  {
    echo "################################################################"
    echo "# name   : $name"
    echo "# time   : $(date -Is)   (epoch $(date +%s))"
    echo "# egress : DIRECT (http_proxy/https_proxy unset)"
    echo "# cmd    : curl -sS --compressed -m 25 '<url>' -H 'User-Agent: <Chrome120>' -H 'Referer: <ref>'"
    echo "################################################################"
    echo "--- url"
    echo "$url"
    echo "--- selected response headers"
    curl -sS --compressed -m 25 -D - -o /dev/null "$url" \
      -H "User-Agent: $UA" -H "Referer: $AUDIO_REF" "$@" 2>&1 \
      | grep -iE '^(HTTP/|content-type|content-length|content-range|server|x-cache|set-cookie)' | head -20
    echo "--- body"
    curl -sS --compressed -m 25 -o /tmp/.ev.body \
      -w 'HTTP=%{http_code} time=%{time_total}s ip=%{remote_ip} bytes=%{size_download}' \
      "$url" -H "User-Agent: $UA" -H "Referer: $AUDIO_REF" "$@" 2>&1
    echo ""
    cat /tmp/.ev.body
    echo ""
  } > "$f" 2>&1
  printf '%-38s %s\n' "$name" "$(grep -m1 '^HTTP=' "$f")"
}

echo "=== [1] nav / wbi key（未登录也下发） ==="
req nav "https://api.bilibili.com/x/web-interface/nav"

echo "=== [2] 音频区 song/info（真实 auid + 不存在 auid） ==="
for sid in 39 13 15 11624 17315 127015 130878 1124992 1131678 4059094 999999999; do
  req "songinfo-au$sid" "https://www.bilibili.com/audio/music-service-c/web/song/info?sid=$sid"
done

echo "=== [3] 音频流 WEB 端点 song/url·匿名·qn 0..3（只给 192K） ==="
for qn in 0 1 2 3; do
  req "songurl-web-au39-qn$qn" "https://www.bilibili.com/audio/music-service-c/web/url?sid=39&quality=$qn&privilege=2&mid=0&platform=web"
done

echo "=== [4] 音频流 APP 端点 /audio/music-service-c/url·匿名·qn 0..3（给到 320K） ==="
for qn in 0 1 2 3; do
  req "songurl-app-au39-qn$qn" "https://api.bilibili.com/audio/music-service-c/url?songid=39&quality=$qn&privilege=2&mid=0&platform=pc"
done

echo "=== [5] 歌词 lyric ==="
for sid in 39 11624 17315 13; do
  req "lyric-au$sid" "https://www.bilibili.com/audio/music-service-c/web/song/lyric?sid=$sid"
done

echo "=== [6] 浏览：热门歌单 / 榜单 / 歌单详情 / 歌单内歌曲 ==="
req "menu-hit"     "https://www.bilibili.com/audio/music-service-c/web/menu/hit?pn=1&ps=20"
req "menu-rank"    "https://www.bilibili.com/audio/music-service-c/web/menu/rank?pn=1&ps=20"
req "menu-info-10624"     "https://www.bilibili.com/audio/music-service-c/web/menu/info?sid=10624"
req "song-of-menu-10624"  "https://www.bilibili.com/audio/music-service-c/web/song/of-menu?sid=10624&pn=1&ps=5"
req "song-of-menu-10627"  "https://www.bilibili.com/audio/music-service-c/web/song/of-menu?sid=10627&pn=1&ps=12"

echo "=== [7] 歌曲元数据补充：tag / member / stat ==="
req "tag-song-39"    "https://www.bilibili.com/audio/music-service-c/web/tag/song?sid=39"
req "member-song-39" "https://www.bilibili.com/audio/music-service-c/web/member/song?sid=39"
req "stat-song-39"   "https://www.bilibili.com/audio/music-service-c/web/stat/song?sid=39"

echo "=== [8] 需登录才可用的音频区端点（4511003） ==="
req "login-collections-list" "https://www.bilibili.com/audio/music-service-c/web/collections/list?uid=14452610&pn=1&ps=5"
req "login-collections-info" "https://www.bilibili.com/audio/music-service-c/web/collections/info?sid=1"
req "login-coin-audio"       "https://www.bilibili.com/audio/music-service-c/web/coin/audio?sid=39"

echo "=== [9] 已下线/不存在（404）与空壳（code 0 + data null） ==="
for p in "tag/list" "tag/type" "song/new?pn=1&ps=5" "song/search?keyword=x&pn=1&ps=5" \
         "song/recommend" "song/hot" "rank/list" "home" "search/song?keyword=x" "menu/search?keyword=x"; do
  req "probe-$(echo "$p" | tr '/?=&' '----')" "https://www.bilibili.com/audio/music-service-c/web/$p"
done

echo "=== [10] search_type 取值矩阵（music / audio 均非法） ==="
for st in video music audio foobar media_bangumi bili_user live_room article topic photo; do
  req "searchtype-$st" "https://api.bilibili.com/x/web-interface/wbi/search/type?search_type=$st&keyword=test&page=1"
done
req "search-video-tids3"  "https://api.bilibili.com/x/web-interface/wbi/search/type?search_type=video&keyword=%E5%91%A8%E6%9D%B0%E4%BC%A6&page=1&tids=3"
req "search-all-v2"       "https://api.bilibili.com/x/web-interface/wbi/search/all/v2?keyword=test"
req "search-nonwbi-path"  "https://api.bilibili.com/x/web-interface/search/type?search_type=video&keyword=test&page=1"

echo "=== [11] 音乐推广/新歌（非音频区，但可作为发现源） ==="
req "newmusic-centralization" "https://api.bilibili.com/x/centralization/interface/new/music?plat=2&web_location=333.1351"
req "toplist-music-list-76"   "https://api.bilibili.com/x/copyright-music-publicity/toplist/music_list?list_id=76&pn=1&ps=3"
req "toplist-detail-76"       "https://api.bilibili.com/x/copyright-music-publicity/toplist/detail?list_id=76"

echo "=== [12] 视频 DASH 备选路径 ==="
req "pagelist"          "https://api.bilibili.com/x/player/pagelist?bvid=BV1GJ411x7h7"
req "finger-spi"        "https://api.bilibili.com/x/frontend/finger/spi"
req "playurl-legacy-dash" "https://api.bilibili.com/x/player/playurl?bvid=BV1GJ411x7h7&cid=137649199&fnval=4048&fnver=0&fourk=1" \
    -H "Cookie: buvid3=A0F86246-BEB0-3FFA-20CD-F4E6F1ABE34529812infoc" -H "Referer: https://www.bilibili.com/video/BV1GJ411x7h7"
req "playurl-wbi-nosign"  "https://api.bilibili.com/x/player/wbi/playurl?bvid=BV1GJ411x7h7&cid=137649199&fnval=4048&fnver=0&fourk=1" \
    -H "Cookie: buvid3=A0F86246-BEB0-3FFA-20CD-F4E6F1ABE34529812infoc" -H "Referer: https://www.bilibili.com/video/BV1GJ411x7h7"

echo "=== [13] 音频 CDN 的 Referer 强依赖（403 对照） ==="
SURL=$(curl -sS --compressed -m 25 -H "User-Agent: $UA" -H "Referer: $AUDIO_REF" \
  "https://www.bilibili.com/audio/music-service-c/web/url?sid=39&quality=0&privilege=2&mid=0&platform=web" | jq -r '.data.cdns[0]')
if [ -n "$SURL" ] && [ "$SURL" != "null" ]; then
  {
    echo "# 音频 CDN 直链的 Referer 强依赖实测   $(date -Is)"
    echo "# URL: ${SURL:0:120}..."
    echo "--- A) 带 Referer: https://www.bilibili.com/audio/home"
    curl -sS -m 25 -r 0-1023 -o /dev/null -w 'HTTP=%{http_code} bytes=%{size_download}\n' -H "User-Agent: $UA" -H "Referer: $AUDIO_REF" "$SURL"
    echo "--- B) 完全不带头"
    curl -sS -m 25 -r 0-1023 -o /dev/null -w 'HTTP=%{http_code} bytes=%{size_download}\n' "$SURL"
    echo "--- C) 带外部 Referer: https://example.com/"
    curl -sS -m 25 -r 0-1023 -o /dev/null -w 'HTTP=%{http_code} bytes=%{size_download}\n' -H "User-Agent: $UA" -H "Referer: https://example.com/" "$SURL"
    echo "--- D) 只带 UA（无 Referer）"
    curl -sS -m 25 -r 0-1023 -o /dev/null -w 'HTTP=%{http_code} bytes=%{size_download}\n' -H "User-Agent: $UA" "$SURL"
    echo "--- E) Range 请求的响应头（ExoPlayer 用）"
    curl -sS -m 25 -r 0-1023 -D - -o /dev/null -H "User-Agent: $UA" -H "Referer: $AUDIO_REF" "$SURL" 2>&1 | grep -iE '^(HTTP/|content-type|content-range|content-length|access-control)'
    echo "--- F) deadline 与 timeout 的精确差值（连测 3 次）"
    for i in 1 2 3; do
      T0=$(date +%s)
      RR=$(curl -sS --compressed -m 25 -H "User-Agent: $UA" -H "Referer: $AUDIO_REF" \
        "https://www.bilibili.com/audio/music-service-c/web/url?sid=39&quality=0&privilege=2&mid=0&platform=web")
      DL=$(echo "$RR" | jq -r '.data.cdns[0]' | grep -oE 'deadline=[0-9]+' | cut -d= -f2)
      echo "  #$i now=$T0 deadline=$DL timeout=$(echo "$RR"|jq -r '.data.timeout') deadline-now=$((DL-T0))s"
    done
    echo "--- G) 320K 文件落盘 + ffprobe 实测码率"
    AURL=$(curl -sS --compressed -m 25 -H "User-Agent: $UA" -H "Referer: $AUDIO_REF" \
      "https://api.bilibili.com/audio/music-service-c/url?songid=39&quality=2&privilege=2&mid=0&platform=pc" | jq -r '.data.cdns[0]')
    curl -sS -m 90 -o /tmp/.ev320.m4a -w 'HTTP=%{http_code} bytes=%{size_download}\n' -H "User-Agent: $UA" -H "Referer: $AUDIO_REF" "$AURL"
    ( file /tmp/.ev320.m4a; ffprobe -v error -show_entries format=duration,bit_rate -of default=nw=1 /tmp/.ev320.m4a ) 2>&1 | head -5
  } > "$OUT/80-cdn-referer-and-ttl.txt" 2>&1
  echo "cdn-referer-and-ttl -> $OUT/80-cdn-referer-and-ttl.txt"
fi

echo "=== [14] wbi 签名 A/B（Python，现算 w_rid） ==="
env -u https_proxy -u http_proxy python3 "$HERE/wbi_ab.py" > "$OUT/90-wbi-ab.txt" 2>&1
echo "wbi A/B -> $OUT/90-wbi-ab.txt"
echo "=== [15] wbi 官方向量自检 ==="
env -u https_proxy -u http_proxy python3 "$HERE/wbi_golden.py" --live \
  > "$OUT/91-wbi-golden-vector.txt" 2>&1
echo "golden -> $OUT/91-wbi-golden-vector.txt"

echo
echo "全部证据写入 $OUT/（curl 条目 $IDX 条 + 3 份脚本产物）"
