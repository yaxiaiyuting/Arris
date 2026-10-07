#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
v3.4.8 线上验证探针 —— 三个用户问题各自的**服务端事实**。

它**不是**在跑 Kotlin，而是把 Kotlin 侧的三段纯逻辑（BiliParse.parseDashAudios /
BiliQuality.selectStream + levelOf / BiliSubtitle.preferredTags + pickTrack）
按同一份规则在 Python 里重放，输入是**真实线上响应**。

两侧共享的证据是：Kotlin 单测（BiliHiResTest / BiliSubtitleLangTest）里内嵌的
JSON 样本就是本脚本落盘的响应（字段级逐字）。所以：
  · 探针证明「服务端真的会给这些字段」；
  · 单测证明「代码真的按这些字段决策」；
  · 两者用同一份样本 ⇒ 合起来才是「用户点播放会拿到 Hi-Res」的完整证据链。

用法：
    env -u https_proxy -u http_proxy -u all_proxy python3 probe-v348.py [--cookie-file PATH]
"""
import argparse
import json
import os
import subprocess
import sys
import urllib.request

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
REFERER = "https://www.bilibili.com"
DEFAULT_COOKIE = os.path.expanduser("~/.cache/ncrust-v348-cookie.txt")

# ── Kotlin 侧的档位表（QualityLadder.LEVELS，逐字） ───────────────────────────
LEVELS = ["standard", "higher", "exhigh", "lossless", "hires", "jyeffect", "jymaster", "dolby"]


def http(url, cookie=None):
    req = urllib.request.Request(url)
    req.add_header("User-Agent", UA)
    req.add_header("Referer", REFERER)
    if cookie:
        req.add_header("Cookie", cookie)
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read().decode("utf-8", "replace")


# ── BiliParse.parseDashAudios 的镜像 ─────────────────────────────────────────
def parse_dash_audios(body):
    root = json.loads(body)
    dash = (root.get("data") or {}).get("dash") or {}
    out = []

    def one(node, kind):
        if not node:
            return None
        url = node.get("baseUrl") or (node.get("backupUrl") or [None])[0]
        if not url:
            return None
        return {"kind": kind, "url": url, "br": node.get("bandwidth") or 0,
                "codecs": node.get("codecs") or ""}

    flac = one((dash.get("flac") or {}).get("audio"), "FLAC")
    if flac:
        out.append(flac)
    for a in ((dash.get("dolby") or {}).get("audio") or []):
        d = one(a, "DOLBY")
        if d:
            out.append(d)
    aac = [x for x in (one(a, "AAC") for a in (dash.get("audio") or [])) if x]
    out.extend(sorted(aac, key=lambda s: -s["br"]))  # 稳定降序（Python 的 sort 稳定）
    return out


# ── BiliQuality.selectStream 的镜像 ─────────────────────────────────────────
def select_stream(streams, level, prefer_flac=True):
    if not streams:
        return None
    wants_lossless = level in ("lossless", "hires", "jyeffect", "jymaster") or level == "dolby"
    if wants_lossless:
        if level == "dolby":
            order = ["DOLBY", "FLAC", "AAC"]
        elif prefer_flac:
            order = ["FLAC", "DOLBY", "AAC"]
        else:
            order = ["DOLBY", "AAC", "FLAC"]
    else:
        order = ["AAC", "FLAC", "DOLBY"]
    for kind in order:
        for s in streams:
            if s["kind"] == kind:
                return s
    return streams[0]


# ── BiliQuality.levelOf 的镜像 ──────────────────────────────────────────────
def level_of(s):
    if s["kind"] == "FLAC":
        return "hires" if s["br"] >= 1_400_000 else "lossless"
    if s["kind"] == "DOLBY":
        return "dolby"
    return "higher" if s["br"] >= 190_000 else "standard"


# ── BiliQualityCapRules.applyCap 的镜像 ─────────────────────────────────────
def apply_cap(level, cap):
    if cap == "auto":
        return level
    target = {"hires": "hires", "exhigh": "exhigh", "higher": "higher"}[cap]
    if level not in LEVELS:
        return target
    i, t = LEVELS.index(level), LEVELS.index(target)
    if cap == "hires":
        return level if i >= t else target
    return level if i <= t else target


# ── BiliSubtitle 的镜像 ─────────────────────────────────────────────────────
AUTO_TAGS = {
    "zh-CN": ["zh-CN", "zh-Hans", "zh-SG"], "zh-TW": ["zh-Hant", "zh-HK", "zh-TW"],
    "en-US": ["en-US", "en-GB", "en"], "ja-JP": ["ja"], "ja-MY": ["ja"],
    "ko-KP": ["ko"], "de-DE": ["de-DE", "de"], "ru-RU": ["ru"],
}
LANG_TAGS = {
    "zh-Hans": ["zh-CN", "zh-Hans", "zh-SG", "zh"], "zh-Hant": ["zh-Hant", "zh-HK", "zh-TW", "zh"],
    "en": ["en-US", "en-GB", "en"], "ja": ["ja"], "ko": ["ko"], "off": [],
}


def preferred_tags(lang, app_lang):
    if lang == "off":
        return []
    if lang != "auto":
        return LANG_TAGS[lang]
    out = list(AUTO_TAGS.get(app_lang or "", []))
    for t in ["zh-CN", "zh-Hans", "zh", "en-US", "en"]:
        if t not in out:
            out.append(t)
    return out


def parse_tracks(body):
    root = json.loads(body)
    subs = ((root.get("data") or {}).get("subtitle") or {}).get("subtitles") or []
    out = []
    for o in subs:
        lan, url = (o.get("lan") or "").strip(), (o.get("subtitle_url") or "").strip()
        if not lan or not url:
            continue
        out.append({"lan": lan, "doc": o.get("lan_doc") or "", "url": url,
                    "ai": lan.lower().startswith("ai-") or o.get("type") == 1})
    return out


def pick_track(tracks, tags, allow_ai=True):
    if not tracks:
        return None
    for t in tags:
        for tr in tracks:
            if tr["lan"].lower() == t.lower():
                return tr
    for t in tags:
        head = t.split("-")[0].lower()
        for tr in tracks:
            if tr["lan"].split("-")[0].lower() == head:
                return tr
    for tr in tracks:
        if not tr["ai"]:
            return tr
    return tracks[0] if allow_ai else None


def ffprobe(url):
    """取前 2 MB 落到临时文件再 ffprobe —— 证明拿到的是**真的**音频而不是一段 JSON 错误页。"""
    try:
        import tempfile
        req = urllib.request.Request(url, headers={
            "User-Agent": UA, "Referer": REFERER, "Range": "bytes=0-2000000"})
        with urllib.request.urlopen(req, timeout=60) as r:
            data = r.read()
        with tempfile.NamedTemporaryFile(suffix=".m4s", delete=False) as f:
            f.write(data)
            path = f.name
        out = subprocess.run(
            ["ffprobe", "-v", "error", "-show_entries",
             "stream=codec_name,bit_rate,sample_rate,channels",
             "-of", "default=nw=1", path],
            capture_output=True, text=True, timeout=60).stdout.strip()
        os.unlink(path)
        return f"bytes={len(data)} {out.replace(chr(10), ' ')}"
    except Exception as e:  # noqa: BLE001
        return f"ffprobe 不可用/失败：{e}"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cookie-file", default=DEFAULT_COOKIE)
    ap.add_argument("--no-download", action="store_true")
    args = ap.parse_args()

    cookie = None
    if os.path.exists(args.cookie_file):
        cookie = "; ".join(l.strip() for l in open(args.cookie_file, encoding="utf-8") if l.strip())
    print(f"# 登录态：{'有 Cookie' if cookie else '匿名'}\n")

    # ── 问题 1：大会员 Hi-Res ────────────────────────────────────────────────
    print("=" * 72)
    print("问题 1 · 大会员 Hi-Res（dash.flac.audio）")
    print("=" * 72)
    for bvid, cid, note in [
        ("BV1EC4y1R7ax", 25931154534, "【HiRes】崔健《新长征路上的摇滚》"),
        ("BV1BZbSzZEGT", 31268997329, "【Hi-Res无损】周杰伦《晴天》"),
    ]:
        body = http(f"https://api.bilibili.com/x/player/playurl?bvid={bvid}&cid={cid}"
                    f"&fnval=4048&fnver=0&fourk=1", cookie)
        cands = parse_dash_audios(body)
        print(f"\n## {bvid} ({note})")
        for s in cands:
            print(f"   {s['kind']:5s} br={s['br']:>8d} codecs={s['codecs']}")
        for level in ["standard", "exhigh", "lossless", "hires"]:
            picked = select_stream(cands, level)
            print(f"   请求 {level:9s} → {picked['kind']:5s} "
                  f"{picked['url'].split('?')[0].split('/')[-1]:32s} "
                  f"实际档位={level_of(picked)}")
        print(f"   上限 hires + 全局 higher → "
              f"{select_stream(cands, apply_cap('higher', 'hires'))['kind']}")
        print(f"   上限 exhigh + 全局 lossless → "
              f"{select_stream(cands, apply_cap('lossless', 'exhigh'))['kind']}")
        print(f"   preferFlac=false + lossless → "
              f"{select_stream(cands, 'lossless', prefer_flac=False)['kind']}")
        if not args.no_download:
            flac = select_stream(cands, "lossless")
            print(f"   下载前 2MB 实测：{ffprobe(flac['url'])}")

    # ── 问题 2：多语言字幕 ───────────────────────────────────────────────────
    print("\n" + "=" * 72)
    print("问题 2 · 多语言字幕（player/wbi/v2 的 subtitles[]）")
    print("=" * 72)
    for bvid, cid in [("BV1GJ411x7h7", 137649199), ("BV1uT4y1P7CX", 287639008)]:
        body = http(f"https://api.bilibili.com/x/player/wbi/v2?bvid={bvid}&cid={cid}", cookie)
        tracks = parse_tracks(body)
        print(f"\n## {bvid} 共 {len(tracks)} 条语言轨")
        print("   " + " / ".join(t["lan"] for t in tracks[:18]) + (" …" if len(tracks) > 18 else ""))
        for lang in ["auto", "zh-Hans", "zh-Hant", "en", "ja", "ko", "off"]:
            for app in (["zh-CN", "zh-TW", "en-US"] if lang == "auto" else [None]):
                tags = preferred_tags(lang, app)
                label = f"{lang}" + (f"({app})" if app else "")
                if lang == "off":
                    # 「不抓取」的落点不在这里：`BiliSourceProvider.fetchLyric` 在
                    # **取 cid 之前**就返回了（连 player/wbi/v2 都不请求）。
                    # 本探针只能证明「偏好表为空」，请求侧由
                    # `BiliSubtitleLyricFetchTest.选中不抓取字幕时一个请求都不发` 钉住。
                    print(f"   {label:14s} → （不抓取 · 零请求，见 JVM 单测）")
                    continue
                picked = pick_track(tracks, tags)
                print(f"   {label:14s} → "
                      + (f"{picked['lan']:8s} {picked['doc']}" if picked else "（无匹配）"))

    # ── 问题 1 补：音频区被服务端静默降级时也要如实回显 ───────────────────────
    print("\n" + "=" * 72)
    print("问题 1 补 · 音频区（au）的档位回显")
    print("=" * 72)
    for auid in (39, 4055594):
        body = http(f"https://api.bilibili.com/audio/music-service-c/url"
                    f"?songid={auid}&quality=3&privilege=2&mid=0&platform=pc", cookie)
        data = json.loads(body).get("data") or {}
        cdns = data.get("cdns") or []
        name = cdns[0].split("?")[0].split("/")[-1] if cdns else "(无流)"
        # `BiliParse.qualityLabelOfFileName` 的镜像：从文件名反推档位标签。
        label = "未知"
        if "-320k." in name:
            label = "320K"
        elif "-192k." in name:
            label = "192K"
        elif "-128k." in name:
            label = "128K"
        elif name.endswith(".flac"):
            label = "FLAC"
        # `BiliQuality.levelOfAudioLabel` 的镜像。
        actual = {"FLAC": "lossless", "320K": "exhigh", "192K": "higher", "128K": "standard"}.get(label)
        has_flac = any((q or {}).get("type") == 3 for q in (data.get("qualities") or []))
        print(f"\n## au{auid}  请求 qn=3（FLAC）")
        print(f"   服务端返回文件：{name}")
        print(f"   qualities[] 里有 type:3 吗：{'有' if has_flac else '没有'}")
        print(f"   ★ 修复前回显：lossless（按请求档位，界面写「无损」）")
        print(f"   ★ 修复后回显：{actual}（按文件名反推，界面如实显示降级）")

    print("\n完成。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
