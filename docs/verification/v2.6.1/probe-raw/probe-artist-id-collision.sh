#!/usr/bin/env bash
# v2.6.1 探针 P2：QQ 数字 singer id 与 ncm artist id 的值域撞号取证。
#
# 回答三个问题：
#   1. QQ 搜索响应里的 `singer[]` 到底有没有 `mid`（singerMID）？→ 决定「参数是在映射层丢的」还是「服务端就不给」
#   2. QQ 数字 singer id 是多少？把它当**ncm** artist id 查 `api/artist/{id}` 会得到谁？
#   3. 换几个 QQ 艺人（林俊杰 / 陈奕迅）复核，判断是普遍现象还是周杰伦个例
#
# 只读、不发写请求、不带任何账号凭证（两条腿都是匿名可用的公开接口）。
set -uo pipefail
UA='Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36'
OUT_DIR="$(cd "$(dirname "$0")" && pwd)"

qq_search() {
  local kw="$1"
  curl -sS --max-time 20 -A "$UA" \
    -H 'Referer: https://y.qq.com/' \
    "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?p=1&n=3&w=$(python3 -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))" "$kw")&format=json&cr=1&new_json=1"
}

ne_artist() {
  curl -sS --max-time 20 -A "$UA" -H 'Referer: https://music.163.com/' \
    "https://music.163.com/api/artist/$1" 2>/dev/null \
    | python3 -c "import json,sys;d=json.load(sys.stdin);a=d.get('artist') or {};print(json.dumps({'code':d.get('code'),'id':a.get('id'),'name':a.get('name'),'albumSize':a.get('albumSize'),'musicSize':a.get('musicSize')},ensure_ascii=False))" 2>/dev/null || echo '{"code":"PARSE_FAIL"}'
}

for kw in 稻香 林俊杰 陈奕迅; do
  echo "=================== QQ 搜索：$kw ==================="
  raw="$(qq_search "$kw")"
  echo "$raw" > "$OUT_DIR/qq-search-$kw.json"
  python3 - "$OUT_DIR/qq-search-$kw.json" "$kw" <<'PY'
import json,sys
path,kw=sys.argv[1],sys.argv[2]
d=json.load(open(path))
lst=(d.get('data',{}).get('song',{}) or {}).get('list') or []
if not lst:
    print("  !! 搜索无结果"); raise SystemExit
for s in lst[:3]:
    singers=s.get('singer') or []
    print(f"  song={s.get('name')!r} songmid={s.get('mid')!r} songid={s.get('id')}")
    for sg in singers:
        keys=sorted(sg.keys())
        print(f"     singer: id={sg.get('id')!r} mid={sg.get('mid')!r} name={sg.get('name')!r}")
        print(f"             (该 singer 对象的全部字段: {keys})")
PY
done

echo
echo "=================== 撞号验证：把 QQ 的 singer.id 当 ncm artist id 查 ==================="
for kw in 稻香 林俊杰 陈奕迅; do
  f="$OUT_DIR/qq-search-$kw.json"
  [ -f "$f" ] || continue
  while read -r sid sname; do
    ne="$(ne_artist "$sid")"
    printf '  QQ singer id=%-8s name=%-12s → ncm api/artist/%s = %s\n' "$sid" "$sname" "$sid" "$ne"
  done < <(python3 -c "
import json,sys
d=json.load(open('$f'))
lst=(d.get('data',{}).get('song',{}) or {}).get('list') or []
seen=set()
for s in lst[:3]:
    for sg in (s.get('singer') or []):
        i=sg.get('id')
        if i and i not in seen:
            seen.add(i); print(i, sg.get('name'))
")
done
