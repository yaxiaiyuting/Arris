#!/bin/sh
# 把 androidTest 渲染出的界面 PNG 变成站点配图（webp，630 宽）。
#
# 为什么不在手机上截图：状态栏、通知、电量和系统手势条全在画面里，主题一换还得重来。
# 渲染侧见 app/src/androidTest/.../ArrisKaraokeReviewTest.kt（离线渲染真组件），
# 这里只负责「裁系统栏 → 统一宽度 → 转 webp → 落到 relay/public/assets」。
#
# 用法：tools/site-shots.sh [来源目录]（默认 /tmp/shots2）
set -e
SRC=${1:-/tmp/shots2}
OUT=relay/public/assets
W=630
mkdir -p "$OUT"
for n in light dark; do
  IN="$SRC/karaoke-$n.png"
  [ -f "$IN" ] || { echo "缺少 $IN"; exit 2; }
  python3 - "$IN" "$OUT/karaoke-$n.webp" "$W" <<'PY'
import sys
from PIL import Image
src, dst, width = sys.argv[1], sys.argv[2], int(sys.argv[3])
im = Image.open(src).convert('RGB')
# 上下两条纯色带就是系统栏区域（渲染侧加了 systemBarsPadding，那里没有内容）
w, h = im.size
px = im.load()
def uniform(y):
    c = px[5, y]
    return all(px[x, y] == c for x in range(0, w, 37))
top = 0
while top < h and uniform(top): top += 1
bot = h - 1
while bot > 0 and uniform(bot): bot -= 1
im = im.crop((0, top, w, bot + 1))
ratio = width / im.width
im = im.resize((width, round(im.height * ratio)), Image.LANCZOS)
im.save(dst, 'WEBP', quality=88, method=6)
print(f'{dst}  {im.size[0]}x{im.size[1]}')
PY
done
