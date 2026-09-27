#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Ncrust · B站音源调研 —— Wbi 算法**官方向量自检**（可复现）
==========================================================

为什么要这个文件：`wbi_ab.py` 证明了「服务端确实校验 w_rid」，但一个错误实现
也可能「碰巧」被服务端接受（如果它只校验长度）。本脚本用
`SocialSisterYi/bilibili-API-collect` 的 `docs/misc/sign/wbi.md` 里给出的
**官方示例输入/输出**，逐字节比对 `mixin_key`、排序后的 query、以及最终 `w_rid`。

三组断言全部 PASS ⇒ 实现对「文档定义的算法」是**逐字节等价**的，
配合 `wbi_ab.py` 的服务端 A/B，构成完整证据链：
    实现正确（本文件） + 服务端真的验签（wbi_ab.py）

用法：
    env -u https_proxy -u http_proxy python3 wbi_golden.py     # 纯离线，无需网络
    env -u https_proxy -u http_proxy python3 wbi_golden.py --live  # 额外用实时 key 跑一次
"""

import hashlib
import re
import sys
import urllib.parse

from wbi_sign_reference import MIXIN_KEY_ENC_TAB, get_mixin_key

# ── 官方示例向量（bilibili-API-collect docs/misc/sign/wbi.md）──────────────
OFFICIAL_IMG_KEY = "653657f524a547ac981ded72ea172057"
OFFICIAL_SUB_KEY = "6e4909c702f846728e64f6007736a338"
OFFICIAL_MIXIN_KEY = "72136226c6a73669787ee4fd02a74c27"
OFFICIAL_PARAMS = {"foo": "114", "bar": "514", "zab": 1919810, "wts": 1684746387}
OFFICIAL_QUERY = "bar=514&foo=114&wts=1684746387&zab=1919810"
OFFICIAL_W_RID = "90efcab09403023875b8516f07e9f9de"
OFFICIAL_PARAMS2 = {"foo": "one one four", "bar": "五一四", "baz": 1919810}
OFFICIAL_QUERY2 = "bar=%E4%BA%94%E4%B8%80%E5%9B%9B&baz=1919810&foo=one%20one%20four"


def build_query(params: dict) -> str:
    """按 wbi 规则构造待签名 query：过滤 !'()* → key 升序 → RFC3986 编码。"""
    cleaned = {k: re.sub(r"[!'()*]", "", str(v)) for k, v in params.items()}
    return urllib.parse.urlencode(
        sorted(cleaned.items()), quote_via=urllib.parse.quote, safe=""
    )


def sign(params: dict, mixin_key: str) -> str:
    return hashlib.md5((build_query(params) + mixin_key).encode("utf-8")).hexdigest()


def main() -> int:
    ok = True
    print("=" * 78)
    print("Wbi 算法 · 官方向量自检（bilibili-API-collect docs/misc/sign/wbi.md）")
    print("=" * 78)

    print("\n[1] 乱序表")
    print("    MIXIN_KEY_ENC_TAB 长度 =", len(MIXIN_KEY_ENC_TAB), "(期望 64)")
    print("    取值 =", MIXIN_KEY_ENC_TAB)
    ok &= len(MIXIN_KEY_ENC_TAB) == 64
    ok &= sorted(MIXIN_KEY_ENC_TAB) == list(range(64))
    print("    是否为 0..63 的一个排列 :", sorted(MIXIN_KEY_ENC_TAB) == list(range(64)))

    print("\n[2] mixin_key 推导")
    raw = OFFICIAL_IMG_KEY + OFFICIAL_SUB_KEY
    got = get_mixin_key(OFFICIAL_IMG_KEY, OFFICIAL_SUB_KEY)
    print("    img_key  =", OFFICIAL_IMG_KEY)
    print("    sub_key  =", OFFICIAL_SUB_KEY)
    print("    img+sub  =", raw, "(len=%d)" % len(raw))
    print("    计算值   =", got)
    print("    期望值   =", OFFICIAL_MIXIN_KEY)
    print("    MATCH    =", got == OFFICIAL_MIXIN_KEY)
    ok &= got == OFFICIAL_MIXIN_KEY

    print("\n[3] 排序 + 过滤 + 编码（数字参数）")
    print("    输入     =", OFFICIAL_PARAMS)
    q = build_query(OFFICIAL_PARAMS)
    print("    计算 query =", q)
    print("    期望 query =", OFFICIAL_QUERY)
    print("    MATCH      =", q == OFFICIAL_QUERY)
    ok &= q == OFFICIAL_QUERY

    print("\n[4] w_rid = md5(query + mixin_key)")
    rid = sign(OFFICIAL_PARAMS, OFFICIAL_MIXIN_KEY)
    print("    md5 输入 =", q + OFFICIAL_MIXIN_KEY)
    print("    计算 w_rid =", rid)
    print("    期望 w_rid =", OFFICIAL_W_RID)
    print("    MATCH      =", rid == OFFICIAL_W_RID)
    ok &= rid == OFFICIAL_W_RID

    print("\n[5] 中文 / 空格编码用例（最容易踩坑的一步）")
    q2 = build_query(OFFICIAL_PARAMS2)
    print("    计算 query =", q2)
    print("    期望 query =", OFFICIAL_QUERY2)
    print("    MATCH      =", q2 == OFFICIAL_QUERY2)
    ok &= q2 == OFFICIAL_QUERY2
    print("\n    对照：不同 URL 编码器的空格表现")
    print("      urllib.parse.urlencode 默认 (quote_plus) :", urllib.parse.urlencode({"a": "one two"}))
    print("      quote_via=quote, safe=''  （正确）        :",
          urllib.parse.urlencode({"a": "one two"}, quote_via=urllib.parse.quote, safe=""))
    print("      ⇒ 必须用后者（%20）。用 '+' 会导致 w_rid 与服务端算的不一致。")

    print("\n[6] 过滤 !'()* 的行为")
    t = {"k": "a!b'c(d)e*f"}
    print("    输入值     =", repr(t["k"]))
    print("    过滤后参与 =", repr(re.sub(r"[!'()*]", "", t["k"])), "(期望 'abcdef')")
    ok &= re.sub(r"[!'()*]", "", t["k"]) == "abcdef"

    print("\n" + "=" * 78)
    print("总判定 :", "全部 PASS ✅ —— 实现与文档定义逐字节一致" if ok else "存在 FAIL ❌")
    print("=" * 78)

    if "--live" in sys.argv:
        from wbi_sign_reference import get_wbi_keys, enc_wbi
        print("\n[附] 用**实时** nav key 再跑一次（需要网络）")
        nav_code, img, sub = get_wbi_keys()
        mk = get_mixin_key(img, sub)
        print("    nav.code =", nav_code)
        print("    img_key  =", img)
        print("    sub_key  =", sub)
        print("    mixin_key=", mk, "(len=%d)" % len(mk))
        sq, dbg = enc_wbi({"search_type": "video", "keyword": "周杰伦", "page": "1"}, img, sub)
        print("    wts      =", dbg["wts"])
        print("    query    =", dbg["signed_query"])
        print("    md5 输入 =", dbg["md5_input"])
        print("    w_rid    =", dbg["w_rid"])
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
