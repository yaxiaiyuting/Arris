#!/usr/bin/env python3
"""v3.2.2 探针图表渲染器（**只画像素，不算逻辑**）。

为什么把渲染单独放在这里、而不是在探针单测里顺手画：单测负责"算出结论"，
本脚本负责"把结论画出来"。所有颜色、权重、时间戳都来自探针单测落盘的 CSV
（`docs/verification/v3.2.2/probe/*.csv`、`color-roles-measurements.md`）——
本脚本不重新推导任何一步，所以它画出来的东西不可能与测出来的数字不一致。

用法（仓库根目录）：
    python3 tools/probe-v322/render_charts.py

产出（`docs/verification/v3.2.2/probe/`）：
    color-transition-compare.png   三种颜色切换方式的轨迹对照（硬切 / 80ms / 100ms / 400ms）
    color-roles-swatches.png       HCT 三角色在 6 个预设主题色下的色板
"""

import csv
import os
import re
import sys

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
PROBE = os.path.join(REPO, "docs", "verification", "v3.2.2", "probe")

LANE_H = 46
GAP = 6
LABEL_W = 190
PAD = 16
BG = (16, 16, 16)
FG = (232, 232, 232)
DIM = (150, 150, 150)


def font(size=14):
    for path in (
        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
        "/usr/share/fonts/noto-cjk/NotoSansCJK-Regular.ttc",
        "/usr/share/fonts/TTF/DejaVuSans.ttf",
    ):
        if os.path.exists(path):
            try:
                return ImageFont.truetype(path, size)
            except Exception:
                pass
    return ImageFont.load_default()


def hex_to_rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def load_trajectories():
    """读 color-transition-trajectories.csv -> {transition_ms: [(t_ms, w0,w1,w2, rgb), ...]}"""
    path = os.path.join(PROBE, "color-transition-trajectories.csv")
    data = {}
    with open(path, newline="", encoding="utf-8") as fh:
        for row in csv.reader(fh):
            if not row or row[0].startswith("#"):
                continue
            genre, tms, t, w0, w1, w2, argb = row
            if genre != "electronic":       # 电子乐的切换最密，窗口最有代表性
                continue
            data.setdefault(float(tms), []).append(
                (int(t), float(w0), float(w1), float(w2), hex_to_rgb(argb))
            )
    for k in data:
        data[k].sort()
    return data


def pick_window(frames, span_ms=3200):
    """挑一个切换最密的窗口（按相邻帧颜色变化次数）。"""
    best_i, best_score = 0, -1
    n = len(frames)
    for i in range(0, max(1, n - span_ms // 16)):
        seg = frames[i:i + span_ms // 16]
        score = sum(1 for a, b in zip(seg, seg[1:]) if a[4] != b[4])
        if score > best_score:
            best_score, best_i = score, i
    return best_i, frames[best_i:best_i + span_ms // 16]


def render_transition_compare():
    data = load_trajectories()
    if not data:
        print("no trajectory csv", file=sys.stderr)
        return None
    order = sorted(data)
    # 以 100ms（本版采用）为准选窗口，其余方式用同一时间窗（可比性）
    base = data.get(100.0, data[order[0]])
    start, window = pick_window(base)
    t0 = window[0][0]
    t1 = window[-1][0]
    width = 1100
    plot_w = width - LABEL_W - PAD * 2
    rows = len(order) * (LANE_H + GAP) + 3 * 18 + 60
    img = Image.new("RGB", (width, rows + PAD * 2), BG)
    d = ImageDraw.Draw(img)
    f14 = font(15)
    f12 = font(13)

    d.text((PAD, PAD), "v3.2.2 探针 §2.4 颜色切换方式对照（真实音乐：Faded / Alan Walker，电子）",
           font=f14, fill=FG)
    y = PAD + 26
    d.text((PAD, y), f"时间窗 {t0/1000:.2f}s – {t1/1000:.2f}s（每帧 16ms，60fps 判定 / 11Hz 输入）；"
                     f"条带颜色 = 该帧实际绘制的混合色", font=f12, fill=DIM)
    y += 24

    for tms in order:
        frames = data[tms]
        seg = [f for f in frames if t0 <= f[0] <= t1]
        if not seg:
            continue
        label = {0.0: "a. 硬切 0ms", 100.0: "c. 短过渡 100ms（采用）"}.get(
            tms, f"过渡 {tms:.0f}ms")
        d.text((PAD, y + LANE_H // 2 - 8), label, font=f12, fill=FG)
        for i in range(len(seg) - 1):
            xa = LABEL_W + PAD + int(plot_w * (seg[i][0] - t0) / max(1, t1 - t0))
            xb = LABEL_W + PAD + int(plot_w * (seg[i + 1][0] - t0) / max(1, t1 - t0))
            d.rectangle([xa, y, max(xa + 1, xb), y + LANE_H - 1], fill=seg[i][4])
        y += LANE_H + GAP

    y += 6
    d.text((PAD, y), "每帧最大色差（CAM16-UCS ΔE）与混合态占比见 color-transition-measurements.md",
           font=f12, fill=DIM)
    out = os.path.join(PROBE, "color-transition-compare.png")
    img.save(out)
    print("wrote", out)
    return out


def render_role_swatches():
    """从 color-roles-measurements.md 的最优变体表里读三色，画成色板。"""
    md = open(os.path.join(PROBE, "color-roles-measurements.md"), encoding="utf-8").read()
    m = re.search(r"## 最优变体逐样本（(.+?)）\n\n(.*?)\n\n## ", md, re.S)
    if not m:
        print("no best-variant table", file=sys.stderr)
        return None
    variant, body = m.group(1), m.group(2)
    rows = []
    for line in body.splitlines():
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) < 8 or cells[0] in ("种子", "") or set(cells[0]) <= set("-"):
            continue
        rows.append(cells)
    presets = [r for r in rows if r[0].startswith("预设")]
    if not presets:
        presets = rows[:12]

    cw, ch = 260, 60
    width = PAD * 2 + cw * 3 + 40
    height = PAD * 2 + 40 + len(presets) * (ch + 10)
    img = Image.new("RGB", (width, height), BG)
    d = ImageDraw.Draw(img)
    d.text((PAD, PAD), f"v3.2.2 探针 §2.4 HCT 三角色色板（最优变体 {variant}）", font=font(15), fill=FG)
    d.text((PAD, PAD + 22), "低(secondary) / 中(primary=主题色) / 高(tertiary)", font=font(12), fill=DIM)
    y = PAD + 46
    for r in presets:
        name, mode = r[0], r[1]
        cols = [r[3], r[4], r[5]]
        de, ct = r[6], r[7]
        d.text((PAD, y + 8), f"{name} ({mode})", font=font(13), fill=FG)
        d.text((PAD, y + 30), f"最小 ΔE {de} / 对比度 {ct}", font=font(11), fill=DIM)
        x = PAD + 220
        for c in cols:
            d.rectangle([x, y, x + 150, y + ch - 1], fill=hex_to_rgb(c))
            d.text((x + 8, y + ch - 20), c, font=font(12), fill=(0, 0, 0))
            x += 160
        y += ch + 10
    out = os.path.join(PROBE, "color-roles-swatches.png")
    img.save(out)
    print("wrote", out)
    return out


if __name__ == "__main__":
    render_transition_compare()
    render_role_swatches()
