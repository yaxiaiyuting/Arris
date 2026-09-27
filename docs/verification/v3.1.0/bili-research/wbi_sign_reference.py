#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Ncrust · B站音源调研 —— Wbi 签名参考实现（可复现）
==================================================

本脚本是本仓库调研文档 docs/verification/v3.1.0/bili-research/ 的**可复现证据**：
EVIDENCE.md 里贴出的 wbi 签名串、w_rid、以及"无签名 → 有签名"的 A/B 对照，
全部由本脚本真实产生。

用法（**直连**，显式忽略环境里的 127.0.0.1:10808 代理）：

    env -u https_proxy -u http_proxy python3 wbi_sign_reference.py

    # 只算签名、不发请求：
    env -u https_proxy -u http_proxy python3 wbi_sign_reference.py --dry-run --keyword 周杰伦

输出：
    1) 从 nav 接口取到的 img_key / sub_key / mixin_key
    2) 对一个真实业务请求算出的 wts + w_rid + 完整 query
    3) 该请求的真实 HTTP 响应（前若干字节）

依赖：仅标准库（urllib / hashlib）。
"""

import argparse
import hashlib
import json
import re
import sys
import time
import urllib.parse
import urllib.request

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
)
NAV_URL = "https://api.bilibili.com/x/web-interface/nav"

# ── 固定 64 位乱序表（mix-in key table）────────────────────────────────────
# 来源：SocialSisterYi/bilibili-API-collect docs/misc/sign/wbi.md
# 语义：mixin_key = "".join((img_key + sub_key)[i] for i in TAB)[:32]
MIXIN_KEY_ENC_TAB = [
    46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
    33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
    61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
    36, 20, 34, 44, 52,
]


def http_get(url: str, headers: dict | None = None, timeout: int = 20):
    """直连 GET，返回 (http_status, body_text)。"""
    h = {"User-Agent": UA, "Referer": "https://www.bilibili.com/"}
    if headers:
        h.update(headers)
    req = urllib.request.Request(url, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


def get_wbi_keys():
    """从 nav 取 wbi key。**未登录也返回**（实测 code=-101 但 data.wbi_img 存在）。"""
    status, body = http_get(NAV_URL)
    j = json.loads(body)
    img_url = j["data"]["wbi_img"]["img_url"]
    sub_url = j["data"]["wbi_img"]["sub_url"]
    # 取 URL 末尾的无扩展名文件名，如 .../7cd084941338484aae1ad9425b84077c.png
    img_key = img_url.rsplit("/", 1)[-1].split(".")[0]
    sub_key = sub_url.rsplit("/", 1)[-1].split(".")[0]
    return j.get("code"), img_key, sub_key


def get_mixin_key(img_key: str, sub_key: str) -> str:
    """重排 (img_key + sub_key) 后取前 32 字符。"""
    orig = img_key + sub_key
    return "".join(orig[i] for i in MIXIN_KEY_ENC_TAB)[:32]


def enc_wbi(params: dict, img_key: str, sub_key: str, wts: int | None = None):
    """
    对 params 做 wbi 签名，返回 (签名后的完整 query 字符串, 调试信息 dict)。

    要点（与文档逐条对应）：
      1. 混入 wts（秒级 Unix 时间戳）；
      2. key 按**字典序升序**排序；
      3. 值里的 `!'()*` 五个字符**先删除**（不是编码）；
      4. URL 编码用 quote(safe="")，**百分号后必须大写**，空格编码成 %20（不是 +）；
      5. w_rid = md5(query + mixin_key) 小写十六进制。
    """
    mixin_key = get_mixin_key(img_key, sub_key)
    p = dict(params)
    p["wts"] = int(time.time()) if wts is None else int(wts)
    # 过滤 !'()* —— 先过滤再编码
    items = {
        k: re.sub(r"[!'()*]", "", str(v))
        for k, v in p.items()
    }
    # key 升序排序 + RFC3986 编码（空格 -> %20，大写十六进制）
    query = urllib.parse.urlencode(
        sorted(items.items()), quote_via=urllib.parse.quote, safe=""
    )
    w_rid = hashlib.md5((query + mixin_key).encode("utf-8")).hexdigest()
    return query + "&w_rid=" + w_rid, {
        "mixin_key": mixin_key,
        "wts": p["wts"],
        "signed_query": query,
        "w_rid": w_rid,
        "md5_input": query + mixin_key,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--keyword", default="周杰伦")
    ap.add_argument("--search-type", default="video")
    ap.add_argument("--page", default="1")
    ap.add_argument("--dry-run", action="store_true", help="只算签名，不发业务请求")
    ap.add_argument("--playurl", action="store_true", help="改为对 player/wbi/playurl 做 A/B")
    ap.add_argument("--bvid", default="BV1GJ411x7h7")
    ap.add_argument("--cid", default="137649199")
    args = ap.parse_args()

    print("=" * 78)
    print("[1] GET", NAV_URL)
    nav_code, img_key, sub_key = get_wbi_keys()
    print("    nav.code      =", nav_code, "（-101=未登录，但 wbi key 照常下发）")
    print("    img_key       =", img_key)
    print("    sub_key       =", sub_key)
    mixin_key = get_mixin_key(img_key, sub_key)
    print("    mixin_key     =", mixin_key, "(len=%d)" % len(mixin_key))
    print("    img_key+sub_key 长度 =", len(img_key + sub_key))

    if args.playurl:
        base = "https://api.bilibili.com/x/player/wbi/playurl"
        params = {"bvid": args.bvid, "cid": args.cid, "fnval": "16"}
    else:
        base = "https://api.bilibili.com/x/web-interface/wbi/search/type"
        params = {
            "search_type": args.search_type,
            "keyword": args.keyword,
            "page": args.page,
        }

    print()
    print("[2] 待签名参数:", json.dumps(params, ensure_ascii=False))
    signed, dbg = enc_wbi(params, img_key, sub_key)
    print("    wts           =", dbg["wts"])
    print("    signed_query  =", dbg["signed_query"])
    print("    md5 输入       =", dbg["md5_input"][:120], "...")
    print("    w_rid         =", dbg["w_rid"])
    if args.dry_run:
        return

    def show(tag, url):
        status, body = http_get(url)
        print("    HTTP=%s" % status)
        try:
            j = json.loads(body)
            brief = {k: j.get(k) for k in ("code", "message", "msg") if k in j}
            d = j.get("data")
            if isinstance(d, dict):
                brief["data.keys"] = sorted(d.keys())[:12]
                if "result" in d:
                    brief["data.numResults"] = d.get("numResults")
                    brief["data.result.len"] = len(d.get("result") or [])
                if "dash" in d:
                    brief["data.dash.audio.len"] = len(((d.get("dash") or {}).get("audio")) or [])
            print("    ", json.dumps(brief, ensure_ascii=False)[:500])
        except Exception:
            print("    ", body[:300].replace("\n", " "))
        return body

    print()
    print("[3-A] 无 w_rid（原始参数，不带 wts/w_rid）")
    show("unsigned", base + "?" + urllib.parse.urlencode(params))
    print()
    print("[3-B] 有 w_rid（本脚本签名）")
    show("signed", base + "?" + signed)


if __name__ == "__main__":
    sys.exit(main())
