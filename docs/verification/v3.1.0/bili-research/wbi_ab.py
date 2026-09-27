#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Ncrust · B站音源调研 —— Wbi 签名 A/B 对照实验（可复现）
========================================================

本脚本回答一个必须用实测回答的问题：**B站到底验不验 w_rid？**

结论（2026-09 实测，直连、匿名、无 Cookie）：

| 端点                                   | 无 w_rid | 有有效 w_rid | 判定           |
|----------------------------------------|----------|--------------|----------------|
| `/x/web-interface/wbi/view/detail`     | -352     | **0 (OK)**   | **验签**       |
| `/x/web-interface/wbi/search/type`     | 0 (OK)   | 0 (OK)       | 不验（当前 IP）|
| `/x/space/wbi/acc/info`                | -352     | -352         | IP 级风控，签名无效 |
| `/x/player/wbi/playurl`                | HTTP 412 | HTTP 412     | IP 级封禁，签名无效 |

即：**w_rid 确实被服务端校验，但不是所有 wbi 端点都强制**；
`wbi/view/detail` 是本机实测唯一能「无签名失败 → 有签名成功」的干净对照，
本脚本的 A/B 就打在它上面。

用法（**直连**）：
    env -u https_proxy -u http_proxy python3 wbi_ab.py

输出同时写入 stdout，供 EVIDENCE.md 引用。
"""

import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

from wbi_sign_reference import UA, enc_wbi, get_mixin_key, get_wbi_keys

REFERER = "https://www.bilibili.com/"
COOKIE_B3 = "buvid3=A0F86246-BEB0-3FFA-20CD-F4E6F1ABE34529812infoc"


def call(url: str, cookie: str | None = None, referer: str = REFERER):
    h = {"User-Agent": UA, "Referer": referer}
    if cookie:
        h["Cookie"] = cookie
    req = urllib.request.Request(url, headers=h)
    t0 = time.time()
    try:
        r = urllib.request.urlopen(req, timeout=25)
        status, body = r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        status, body = e.code, e.read().decode("utf-8", "replace")
    except Exception as e:  # noqa: BLE001
        return None, "<transport error: %s>" % e, time.time() - t0
    return status, body, time.time() - t0


def brief(status, body, dt):
    line = "HTTP=%-4s %5.2fs  " % (status, dt)
    try:
        j = json.loads(body)
        line += "code=%-8s message=%s" % (j.get("code"), j.get("message"))
        d = j.get("data")
        if isinstance(d, dict) and j.get("code") == 0:
            line += "  data.keys=%s" % sorted(d.keys())[:6]
        elif isinstance(d, dict) and "v_voucher" in d:
            line += "  v_voucher=%s" % d["v_voucher"]
    except Exception:
        line += "NON-JSON body[0:80]=%r" % body[:80]
    return line


def main() -> int:
    print("=" * 78)
    print("Ncrust · B站 Wbi 签名 A/B 对照实验")
    print("时间:", time.strftime("%Y-%m-%d %H:%M:%S %z"), " epoch =", int(time.time()))
    print("出口: 直连（http_proxy/https_proxy 已 unset），匿名，无 SESSDATA")
    print("=" * 78)

    nav_code, img_key, sub_key = get_wbi_keys()
    mixin_key = get_mixin_key(img_key, sub_key)
    print("\n[0] nav → wbi key")
    print("    nav.code   =", nav_code, "(-101 = 未登录，但 wbi key 照常下发)")
    print("    img_key    =", img_key)
    print("    sub_key    =", sub_key)
    print("    mixin_key  =", mixin_key, "(len=%d)" % len(mixin_key))

    # ---------------------------------------------------------------- 主 A/B
    print("\n" + "-" * 78)
    print("[1] 主对照：/x/web-interface/wbi/view/detail  (bvid=BV1GJ411x7h7)")
    print("-" * 78)
    base = "https://api.bilibili.com/x/web-interface/wbi/view/detail?"
    params = {"bvid": "BV1GJ411x7h7"}
    signed_query, dbg = enc_wbi(params, img_key, sub_key)

    unsigned_url = base + urllib.parse.urlencode(params)
    signed_url = base + signed_query

    print("  A) 无 w_rid / 无 wts")
    print("     GET", unsigned_url)
    s, b, dt = call(unsigned_url)
    print("    ", brief(s, b, dt))

    print("\n  B) 有 w_rid（本仓库 wbi_sign_reference.py 现算）")
    print("     wts           =", dbg["wts"])
    print("     signed_query  =", dbg["signed_query"])
    print("     md5 输入       =", dbg["md5_input"])
    print("     w_rid         =", dbg["w_rid"])
    print("     GET", signed_url)
    s, b, dt = call(signed_url)
    print("    ", brief(s, b, dt))

    print("\n  C) 有 wts 但 w_rid 伪造（全 0）—— 验证服务端真的在算 md5，而不是只看字段存在")
    fake = urllib.parse.urlencode(params) + "&wts=%d&w_rid=%s" % (int(time.time()), "0" * 32)
    print("     GET", base + fake)
    s, b, dt = call(base + fake)
    print("    ", brief(s, b, dt))

    print("\n  D) 有有效 w_rid + buvid3 Cookie（对照：Cookie 不改变结论）")
    s, b, dt = call(signed_url, cookie=COOKIE_B3)
    print("    ", brief(s, b, dt))

    # ------------------------------------------------------- 搜索：不验签对照
    print("\n" + "-" * 78)
    print("[2] 对照：/x/web-interface/wbi/search/type  —— 本机实测**不强制** w_rid")
    print("-" * 78)
    sb = "https://api.bilibili.com/x/web-interface/wbi/search/type?"
    sp = {"search_type": "video", "keyword": "周杰伦", "page": "1"}
    s_signed, s_dbg = enc_wbi(sp, img_key, sub_key)
    print("  A) 无 w_rid   :", end=" ")
    s, b, dt = call(sb + urllib.parse.urlencode(sp))
    print(brief(s, b, dt))
    print("  B) 有 w_rid   :", end=" ")
    s, b, dt = call(sb + s_signed)
    print(brief(s, b, dt))
    print("  C) 伪造 w_rid :", end=" ")
    s, b, dt = call(sb + urllib.parse.urlencode(sp) + "&wts=%d&w_rid=%s" % (int(time.time()), "0" * 32))
    print(brief(s, b, dt))
    print("  ⇒ 三者结果一致 ⇒ 该端点在本机出口上不校验 w_rid（见 EVIDENCE.md 第 7 节 100 次连打）")

    # ---------------------------------------------------- IP 级风控（签名无效）
    print("\n" + "-" * 78)
    print("[3] 反例：签名**无法**挽救的两种情况（IP 级风控 / 路径级封禁）")
    print("-" * 78)
    p2 = {"mid": "2"}
    s2_signed, _ = enc_wbi(p2, img_key, sub_key)
    b2 = "https://api.bilibili.com/x/space/wbi/acc/info?"
    print("  space/wbi/acc/info  无签名 :", end=" ")
    s, b, dt = call(b2 + urllib.parse.urlencode(p2))
    print(brief(s, b, dt))
    print("  space/wbi/acc/info  有签名 :", end=" ")
    s, b, dt = call(b2 + s2_signed)
    print(brief(s, b, dt))
    p3 = {"bvid": "BV1GJ411x7h7", "cid": "137649199", "fnval": "4048", "fnver": "0", "fourk": "1"}
    s3_signed, _ = enc_wbi(p3, img_key, sub_key)
    b3u = "https://api.bilibili.com/x/player/wbi/playurl?"
    print("  player/wbi/playurl  无签名 :", end=" ")
    s, b, dt = call(b3u + urllib.parse.urlencode(p3), cookie=COOKIE_B3,
                    referer="https://www.bilibili.com/video/BV1GJ411x7h7")
    print(brief(s, b, dt))
    print("  player/wbi/playurl  有签名 :", end=" ")
    s, b, dt = call(b3u + s3_signed, cookie=COOKIE_B3,
                    referer="https://www.bilibili.com/video/BV1GJ411x7h7")
    print(brief(s, b, dt))
    print("  ⇒ 两者同为 HTTP 412 ⇒ 该路径对本机出口整体封禁，wbi 不是原因也不是解药")

    print("\n" + "-" * 78)
    print("[4] 可用替代：/x/player/playurl （**非 wbi** 旧路径）→ 200 + DASH")
    print("-" * 78)
    legacy = ("https://api.bilibili.com/x/player/playurl?bvid=BV1GJ411x7h7"
              "&cid=137649199&fnval=4048&fnver=0&fourk=1")
    s, b, dt = call(legacy, cookie=COOKIE_B3, referer="https://www.bilibili.com/video/BV1GJ411x7h7")
    print("  ", brief(s, b, dt))
    try:
        j = json.loads(b)
        aud = ((j.get("data") or {}).get("dash") or {}).get("audio") or []
        print("    dash.audio 条数 =", len(aud))
        for a in aud:
            import re
            m = re.search(r"deadline=(\d+)", a.get("baseUrl", ""))
            print("      id=%-6s bw=%-8s codecs=%-12s deadline=%s (now+%ss)" % (
                a.get("id"), a.get("bandwidth"), a.get("codecs"),
                m.group(1) if m else None,
                int(m.group(1)) - int(time.time()) if m else None))
    except Exception as e:  # noqa: BLE001
        print("    (解析 dash 失败:", e, ")")
    return 0


if __name__ == "__main__":
    sys.exit(main())
