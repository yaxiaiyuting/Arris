#!/usr/bin/env bash
# 把站点 URL 推给 Bing / Yandex / IndexNow 聚合端点。
#
# 为什么不用 Worker 里的 /indexnow：Cloudflare 的共享出口 IP 经常被这三家限流
# （实测 429），而本机出口 IP 通常干净（Bing 202 / api.indexnow.org 200）。
# 发版后在本机跑一次即可，不依赖任何站长平台账号。
#
# 用法：tools/indexnow-submit.sh [额外 URL ...]
#   tools/indexnow-submit.sh                       # 只推首页与机器可读入口
#   tools/indexnow-submit.sh https://arris.de5.net/#download
set -uo pipefail

HOST="arris.de5.net"
KEY="arris-de5-net-20261119"
KEY_LOCATION="https://${HOST}/${KEY}.txt"
ORIGIN="https://${HOST}"

urls=("${ORIGIN}/" "${ORIGIN}/llms.txt" "${ORIGIN}/llms-full.txt" "${ORIGIN}/feed.xml" "${ORIGIN}/api/releases")
for extra in "$@"; do
  # IndexNow 要求 payload 里所有 URL 都属于声明的 host；混入别的域名会被整批拒绝（422）。
  case "$extra" in
    "https://${HOST}"/*) urls+=("$extra") ;;
    *) echo "skip $extra（不属于 ${HOST}，IndexNow 会整批 422）" >&2 ;;
  esac
done

payload=$(printf '%s\n' "${urls[@]}" | python3 -c 'import json,sys; u=[l.strip() for l in sys.stdin if l.strip()]; print(json.dumps({"host":"arris.de5.net","key":"arris-de5-net-20261119","keyLocation":"https://arris.de5.net/arris-de5-net-20261119.txt","urlList":u}, ensure_ascii=False))')

for endpoint in "https://www.bing.com/indexnow" "https://yandex.com/indexnow" "https://api.indexnow.org/indexnow"; do
  code=$(curl -sS -m 60 -o /dev/null -w '%{http_code}' -X POST "$endpoint" \
    -H 'content-type: application/json; charset=utf-8' --data "$payload" 2>/dev/null || echo "000")
  case "$code" in
    200|202) echo "ok   $endpoint -> $code" ;;
    429)     echo "warn $endpoint -> 429（出口 IP 被限流，换网络或稍后再试）" ;;
    *)       echo "fail $endpoint -> $code" ;;
  esac
done
