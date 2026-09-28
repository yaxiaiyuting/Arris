#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""method_size.py —— 单方法 code unit 大小监控（v3.2.1 · 铁律 24/25）。

## 为什么存在

`AGENTS.md` 铁律 24：巨型 composable（> 2000 code unit）必须拆分，超阈值监控。
本脚本是这条规矩的**可执行落点**：直接读 DEX 的 `code_item.insns_size`，
不依赖任何第三方库，也不需要设备。

## 为什么要看 dex，而不看源码行数

源码行数与编译后的方法体大小**不是一回事**：一个 200 行的 composable 如果全是
inline lambda（`Row { Column { ... } }`），编译后仍然是一个巨大的方法体；
反过来，一个 800 行的文件可能由几十个小函数组成。v2.0.0 · HF1（dex 参数寄存器
上限 255）与 v3.2.0（`PlayerCard` 单方法 9092 code unit 触发 45MB JIT 编译）
两次事故都**只能**从 dex 看出来。

## 为什么自己解析而不是 dexdump

`dexdump -d` 也能给出 `insns size`，但：
  1. 它把每个方法的反汇编都打出来（v3.2.0 的 release dex 是 333 万行），
     再把文本解析回去既慢又脆；
  2. 不同 build-tools 版本的 dexdump 输出格式有过变化；
  3. 我们需要和 `mapping.txt` 关联（R8 混淆后的名字 → 源码名字），
     这需要在同一个进程里同时持有两边。
DEX 的 `code_item` 是**定长头部 + `insns_size`（16-bit code units）**，直接读
文件偏移是最稳的（本脚本在 Python 标准库上实现，约 200 行）。

## 阈值

`CODE_UNIT_LIMIT = 2000`（铁律 24）。实测依据：
  - v3.2.0 拆分前 `PlayerCard` = **9092** code unit / 191 寄存器；
  - 拆分后各子 composable 见 `docs/verification/v3.2.1/method-size-report.md`。

## 用法

    python3 tools/method-size/method_size.py dist/Ncrust-v3.2.1-gpl-release.apk
    python3 tools/method-size/method_size.py app/build/outputs/apk/release/app-release.apk \
        --mapping app/build/outputs/mapping/release/mapping.txt --top 40
    python3 tools/method-size/method_size.py x.apk --json report.json --baseline baseline.json

退出码：0 = 全部方法 <= 阈值；1 = 有方法超阈值（CI 可直接用）；2 = 输入/解析错误。
"""

from __future__ import annotations

import argparse
import json
import os
import struct
import sys
import zipfile
from dataclasses import dataclass, field
from typing import Dict, Iterable, List, Optional, Tuple

# ── 阈值（铁律 24）────────────────────────────────────────────────────────────
CODE_UNIT_LIMIT = 2000
# 拆分目标水位：子 composable 建议低于阈值的一半，留出后续加字段/加分支的余量。
WARN_UNIT_LIMIT = 1200

NO_INDEX = 0xFFFFFFFF


# ── DEX 解析 ─────────────────────────────────────────────────────────────────


class DexError(Exception):
    pass


def _uleb128(data: bytes, off: int) -> Tuple[int, int]:
    """读一个 ULEB128，返回 (value, 新的偏移)。"""
    result = 0
    shift = 0
    while True:
        if off >= len(data):
            raise DexError("ULEB128 越界")
        byte = data[off]
        off += 1
        result |= (byte & 0x7F) << shift
        if byte < 0x80:
            return result, off
        shift += 7
        if shift > 35:
            raise DexError("ULEB128 过长")


def _read_uleb128_pairs(data: bytes, off: int, count: int) -> Tuple[List[int], int]:
    out = []
    for _ in range(count):
        v, off = _uleb128(data, off)
        out.append(v)
    return out, off


@dataclass
class DexMethod:
    class_desc: str          # 'LR4/b1;'
    name: str                # 'c'
    proto: str               # '(Lcom/…;Z)V'
    access_flags: int
    code_units: int          # insns_size，单位 = 16-bit code unit
    registers: int
    ins: int
    outs: int

    @property
    def class_binary(self) -> str:
        """'LR4/b1;' -> 'R4.b1'（与 mapping.txt 左侧的混淆名同形）。"""
        d = self.class_desc
        if d.startswith("L") and d.endswith(";"):
            d = d[1:-1]
        return d.replace("/", ".")

    @property
    def java_class(self) -> str:
        d = self.class_desc
        if d.startswith("L") and d.endswith(";"):
            d = d[1:-1]
        return d.replace("/", ".")

    def render(self, mapping: Optional["Mapping"] = None) -> str:
        if mapping:
            origin = mapping.lookup(self)
            if origin:
                return origin
        return f"{self.class_binary}.{self.name}{self.proto}"


class DexFile:
    def __init__(self, data: bytes, label: str):
        self.data = data
        self.label = label
        if data[:4] != b"dex\n":
            raise DexError(f"{label}: 不是 DEX（magic={data[:4]!r}）")
        (self.string_ids_size, self.string_ids_off,
         self.type_ids_size, self.type_ids_off,
         self.proto_ids_size, self.proto_ids_off,
         self.field_ids_size, self.field_ids_off,
         self.method_ids_size, self.method_ids_off,
         self.class_defs_size, self.class_defs_off) = struct.unpack_from(
            "<12I", data, 0x38)
        self._strings: List[str] = [""] * self.string_ids_size
        self._types: List[str] = [""] * self.type_ids_size
        self._protos: List[str] = [""] * self.proto_ids_size

    # -- 惰性表 --
    def string(self, idx: int) -> str:
        if idx == NO_INDEX:
            return ""
        cached = self._strings[idx]
        if cached:
            return cached
        off = struct.unpack_from("<I", self.data, self.string_ids_off + idx * 4)[0]
        size, p = _uleb128(self.data, off)
        # MUTF-8：以 0 结尾；用 utf-8 + surrogateescape 解码，足够展示用途
        raw = self.data[p:p + size]
        try:
            s = raw.decode("utf-8", "surrogateescape")
        except Exception:
            s = raw.decode("latin-1")
        self._strings[idx] = s
        return s

    def type_desc(self, idx: int) -> str:
        if idx == NO_INDEX:
            return ""
        cached = self._types[idx]
        if cached:
            return cached
        str_idx = struct.unpack_from("<I", self.data, self.type_ids_off + idx * 4)[0]
        d = self.string(str_idx)
        self._types[idx] = d
        return d

    def proto_desc(self, idx: int) -> str:
        if idx == NO_INDEX:
            return ""
        cached = self._protos[idx]
        if cached:
            return cached
        # proto_id_item: shorty_idx(u4) return_type_idx(u4) parameters_off(u4)
        shorty_idx, return_idx, params_off = struct.unpack_from(
            "<3I", self.data, self.proto_ids_off + idx * 12)
        ret = self.type_desc(return_idx)
        args: List[str] = []
        if params_off != 0:
            n = struct.unpack_from("<I", self.data, params_off)[0]
            for i in range(n):
                t_idx = struct.unpack_from("<H", self.data, params_off + 4 + i * 2)[0]
                args.append(self.type_desc(t_idx))
        self._protos[idx] = "(" + "".join(args) + ")" + ret
        return self._protos[idx]

    def method_name(self, idx: int) -> Tuple[str, str, str]:
        """method_id -> (class_desc, name, proto)。"""
        cls_idx, proto_idx, name_idx = struct.unpack_from(
            "<HHI", self.data, self.method_ids_off + idx * 8)
        return (self.type_desc(cls_idx), self.string(name_idx),
                self.proto_desc(proto_idx))



def _u16(data: bytes, off: int) -> int:
    return struct.unpack_from("<H", data, off)[0]


def parse_code_item(data: bytes, code_off: int) -> Tuple[int, int, int, int]:
    """返回 (insns_size, registers_size, ins_size, outs_size)。"""
    if code_off == 0 or code_off + 16 > len(data):
        raise DexError(f"code_item 越界 @0x{code_off:x}")
    registers, ins, outs, tries = struct.unpack_from("<4H", data, code_off)
    insns_size = struct.unpack_from("<I", data, code_off + 12)[0]
    return insns_size, registers, ins, outs


def iter_methods(dex: DexFile) -> Iterable[DexMethod]:
    """按 class_data 遍历所有带代码的方法（正确还原 method_idx 差分）。

    DEX 规范：`encoded_method.method_idx_diff` 是相对**同一个 class 内、
    同一组（direct / virtual）**中上一个方法索引的差分。两组的基准都是 0。
    """
    for i in range(dex.class_defs_size):
        base = dex.class_defs_off + i * 32
        class_idx = struct.unpack_from("<I", dex.data, base)[0]
        class_desc = dex.type_desc(class_idx)
        data_off = struct.unpack_from("<I", dex.data, base + 24)[0]
        if data_off == 0:
            continue
        p = data_off
        static_fields, p = _uleb128(dex.data, p)
        instance_fields, p = _uleb128(dex.data, p)
        direct_methods, p = _uleb128(dex.data, p)
        virtual_methods, p = _uleb128(dex.data, p)
        # 跳过字段
        for _ in range(static_fields + instance_fields):
            _diff, p = _uleb128(dex.data, p)
            _acc, p = _uleb128(dex.data, p)
        for group_size, group_base in ((direct_methods, 0), (virtual_methods, 0)):
            last = group_base
            for _ in range(group_size):
                diff, p = _uleb128(dex.data, p)
                access_flags, p = _uleb128(dex.data, p)
                code_off, p = _uleb128(dex.data, p)
                last += diff
                if code_off == 0:
                    continue
                c, n, pr = dex.method_name(last)
                insns, regs, ins, outs = parse_code_item(dex.data, code_off)
                yield DexMethod(
                    class_desc=c or class_desc,
                    name=n,
                    proto=pr,
                    access_flags=access_flags,
                    code_units=insns,
                    registers=regs,
                    ins=ins,
                    outs=outs,
                )


# ── mapping.txt 反查 ─────────────────────────────────────────────────────────


class Mapping:
    """R8 `mapping.txt` 的**反查**表：混淆名 → 原始名。

    格式（R8 / ProGuard）：
        com.example.Foo -> a.b.c:
            int field -> a
            12:34:void bar(java.lang.String) -> d
    我们只关心类行（`X -> Y:`）与方法行（`… name(args) -> z`）。
    """

    def __init__(self) -> None:
        # (混淆类, 混淆方法名) -> [原始签名, …]（重载会落进同一个短名）
        self.methods: Dict[Tuple[str, str], List[str]] = {}
        self.classes: Dict[str, str] = {}

    @classmethod
    def load(cls, path: str) -> "Mapping":
        m = cls()
        current_obf: Optional[str] = None
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            for line in fh:
                if not line.strip():
                    continue
                if not line.startswith(" ") and line.rstrip().endswith(":"):
                    left, _, right = line.rstrip()[:-1].rpartition(" -> ")
                    if right:
                        current_obf = right
                        m.classes[right] = left
                    continue
                if current_obf is None or " -> " not in line:
                    continue
                body, _, obf_name = line.strip().rpartition(" -> ")
                sig = body
                # 去掉行号前缀（'12:34:void bar(java.lang.String)'）
                parts = sig.split(":")
                if len(parts) >= 3 and parts[0].isdigit():
                    sig = ":".join(parts[2:])
                m.methods.setdefault((current_obf, obf_name), []).append(sig)
        return m

    def lookup(self, method: DexMethod) -> Optional[str]:
        originals = self.methods.get((method.class_binary, method.name))
        if not originals:
            return None
        cls = self.classes.get(method.class_binary, method.class_binary)
        # R8 会把内联链上的行号条目全部写进同一个方法的 mapping 行，原样打印会有
        # 上千个候选（v3.2.0 的 `PlayerCard` 映射行就是这样，808 条）。
        # 收敛两步：
        #   ① 只保留「属主 == 这个混淆类映射回的原始类」的候选 —— 内联别人的方法虽然
        #      也写进同一行，但方法体归属仍是它自己的类；
        #   ② 按「属主.方法名」去重（重载合并成一条），优先本应用属主，其余只计数。
        same_class = [s for s in originals if self._owner_of(s) == cls]
        pool = same_class or originals
        # R8 会把内联链上的行号条目全部写进同一个方法的 mapping 行（`PlayerCard` 的
        # mapping 行有 808 条候选），原样打印无法阅读。收敛规则：
        #   ① 属主 == 这个混淆类映射回的原始类 的候选优先（方法体归属它自己）；
        #   ② 库属主（androidx / kotlin / …）与合成 lambda（`$lambda$`）降级；
        #   ③ 同档内**保持 mapping 文件里的原始顺序** —— R8 把它自己那个真实方法
        #      写在最前面，按长度排序反而会挑到 `PlayerCard$lambda$24` 这种合成名。
        library_prefixes = (
            "androidx.", "android.", "kotlin.", "kotlinx.", "com.google.",
            "okhttp3.", "retrofit2.", "coil.", "com.squareup.", "org.",
        )

        def _rank(item: tuple) -> tuple:
            idx, sig = item
            owner = self._owner_of(sig)
            library = owner.startswith(library_prefixes)
            synthetic = "$lambda$" in sig or "$" in self._short_name(sig)
            return (owner != cls, library, synthetic, idx)

        ranked = [sig for _idx, sig in sorted(enumerate(pool), key=_rank)]
        # 一律带上**属主类**再看方法名：候选里混着被内联进来的别人的方法，
        # 只打方法名会读成「PlayerCardKt 里有个 getAboutSectionCredits」这种假事实。
        head = self._short_name(ranked[0])
        owner, _, name = head.rpartition(".")
        owner = owner or cls
        distinct = {self._short_name(x) for x in ranked}
        more = "" if len(distinct) <= 1 else f" (+{len(distinct) - 1} inline/overload)"
        return f"{owner}.{name}{more}"

    @staticmethod
    def _short_name(signature: str) -> str:
        """'void foo.Bar.baz(int)' -> 'foo.Bar.baz'。"""
        paren = signature.find("(")
        head = signature[:paren] if paren >= 0 else signature
        parts = head.split()
        return parts[-1] if parts else signature

    @staticmethod
    def _owner_of(signature: str) -> str:
        """从 'void foo.Bar.baz(int)' 抽出属主 'foo.Bar'（用于挑同名重载）。"""
        paren = signature.find("(")
        head = signature[:paren] if paren >= 0 else signature
        parts = head.split()
        if not parts:
            return ""
        fq = parts[-1]           # 'foo.Bar.baz'
        owner, _, _name = fq.rpartition(".")
        return owner


# ── 输入装载 ─────────────────────────────────────────────────────────────────


def load_dex_sources(path: str) -> List[Tuple[str, bytes]]:
    if os.path.isdir(path):
        out = []
        for name in sorted(os.listdir(path)):
            if name.endswith(".dex"):
                with open(os.path.join(path, name), "rb") as fh:
                    out.append((name, fh.read()))
        if not out:
            raise DexError(f"{path}: 目录里没有 .dex")
        return out
    if zipfile.is_zipfile(path):
        out = []
        with zipfile.ZipFile(path) as zf:
            for name in sorted(zf.namelist()):
                if name.startswith("classes") and name.endswith(".dex"):
                    out.append((name, zf.read(name)))
        if not out:
            raise DexError(f"{path}: APK 里没有 classes*.dex")
        return out
    with open(path, "rb") as fh:
        return [(os.path.basename(path), fh.read())]


def collect(path: str, mapping: Optional[Mapping]) -> List[Tuple[str, DexMethod]]:
    found: List[Tuple[str, DexMethod]] = []
    for label, data in load_dex_sources(path):
        dex = DexFile(data, label)
        for m in iter_methods(dex):
            found.append((label, m))
    return found


# ── 报告 ─────────────────────────────────────────────────────────────────────


@dataclass
class Report:
    source: str
    limit: int
    total_methods: int
    violations: List[dict] = field(default_factory=list)
    warnings: List[dict] = field(default_factory=list)
    top: List[dict] = field(default_factory=list)

    def to_json(self) -> dict:
        return {
            "source": self.source,
            "limit": self.limit,
            "warnLimit": WARN_UNIT_LIMIT,
            "totalMethods": self.total_methods,
            "violations": self.violations,
            "warnings": self.warnings,
            "top": self.top,
        }


def build_report(path: str, mapping: Optional[Mapping], top_n: int,
                 limit: int, only: Optional[List[str]] = None) -> Report:
    methods = collect(path, mapping)
    if only:
        needles = tuple(only)
        methods = [
            (label, m) for label, m in methods
            if any(n in m.java_class or
                   (mapping and n in (mapping.classes.get(m.class_binary) or ""))
                   for n in needles)
        ]
    rep = Report(source=os.path.abspath(path), limit=limit,
                 total_methods=len(methods))
    rows = []
    for label, m in methods:
        rows.append({
            "name": m.render(mapping),
            "obfuscated": f"{m.class_binary}.{m.name}",
            "class": m.java_class,
            "codeUnits": m.code_units,
            "registers": m.registers,
            "dex": label,
        })
    rows.sort(key=lambda r: r["codeUnits"], reverse=True)
    rep.top = rows[:top_n]
    rep.violations = [r for r in rows if r["codeUnits"] > limit]
    rep.warnings = [r for r in rows
                    if WARN_UNIT_LIMIT < r["codeUnits"] <= limit]
    return rep


def print_human(rep: Report) -> None:
    print(f"method_size —— 单方法 code unit 监控（阈值 {rep.limit}）")
    print(f"  输入: {rep.source}")
    print(f"  方法总数（含代码）: {rep.total_methods}")
    print(f"  超阈值: {len(rep.violations)}   预警(>{WARN_UNIT_LIMIT}): {len(rep.warnings)}")
    print("  ---- Top %d ----" % len(rep.top))
    print("  %8s %6s  %s" % ("codeUnit", "regs", "method"))
    for r in rep.top:
        print("  %8d %6d  %s" % (r["codeUnits"], r["registers"], r["name"]))
    if rep.violations:
        print("  ---- 超阈值（必须拆分，铁律 24）----")
        for r in rep.violations:
            print("  %8d %6d  %s" % (r["codeUnits"], r["registers"], r["name"]))
    elif rep.warnings:
        print("  ---- 预警（接近阈值，留出余量）----")
        for r in rep.warnings:
            print("  %8d %6d  %s" % (r["codeUnits"], r["registers"], r["name"]))
    else:
        print("  ✅ 全部方法 <= %d code unit" % rep.limit)


def compare_baseline(rep: Report, path: str) -> int:
    with open(path, "r", encoding="utf-8") as fh:
        base = json.load(fh)
    prev = {r["obfuscated"] if "obfuscated" in r else r["name"]: r["codeUnits"]
            for r in base.get("top", []) + base.get("violations", [])}
    print(f"  ---- 与基线对比: {path} ----")
    worse = 0
    for r in rep.top:
        key = r["obfuscated"]
        old = prev.get(key, prev.get(r["name"]))
        if old is None:
            continue
        delta = r["codeUnits"] - old
        flag = "↑" if delta > 0 else ("=" if delta == 0 else "↓")
        print("  %8d (%+6d %s)  %s" % (r["codeUnits"], delta, flag, r["name"]))
        if delta > 0:
            worse += 1
    return worse


def main(argv: Optional[List[str]] = None) -> int:
    ap = argparse.ArgumentParser(description="单方法 code unit 大小监控")
    ap.add_argument("input", help="APK / classes.dex / 含 .dex 的目录")
    ap.add_argument("--mapping", help="R8 mapping.txt（可选，用于还原源码名）")
    ap.add_argument("--top", type=int, default=20, help="输出的 Top N（默认 20）")
    ap.add_argument("--limit", type=int, default=CODE_UNIT_LIMIT,
                    help=f"code unit 阈值（默认 {CODE_UNIT_LIMIT}）")
    ap.add_argument("--json", dest="json_out", help="把报告写成 JSON")
    ap.add_argument("--baseline", help="与既有 JSON 报告逐方法对比")
    ap.add_argument("--only", action="append", default=None,
                    help="只统计类名包含该子串的方法（可重复；用于只看本应用代码）")
    ap.add_argument("--quiet", action="store_true", help="只输出结论")
    args = ap.parse_args(argv)

    mapping = Mapping.load(args.mapping) if args.mapping else None
    try:
        rep = build_report(args.input, mapping, args.top, args.limit,
                           only=args.only)
    except (DexError, OSError, zipfile.BadZipFile) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2

    if not args.quiet:
        print_human(rep)
    if args.json_out:
        with open(args.json_out, "w", encoding="utf-8") as fh:
            json.dump(rep.to_json(), fh, ensure_ascii=False, indent=2)
        print(f"  报告已写入 {args.json_out}")
    if args.baseline:
        compare_baseline(rep, args.baseline)

    if rep.violations:
        print(f"❌ {len(rep.violations)} 个方法超过 {args.limit} code unit"
              f"（铁律 24：巨型 composable 必须拆分）", file=sys.stderr)
        return 1
    print(f"✅ 全部方法 <= {args.limit} code unit")
    return 0


if __name__ == "__main__":
    sys.exit(main())
