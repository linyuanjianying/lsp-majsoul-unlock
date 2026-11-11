#!/usr/bin/env python3
"""Generate TraditionalChinese.kt for lsp-majsoul-unlock (zh-Hant copy table).

Design
------
* Source of truth is the Simplified Chinese copy the app already ships: the first argument of
  every `tr(zh, en, ja)`, every `t(zh)` call (in-game control / floating window) and every
  Chinese row of the shared copy tables (ManagerLanguageText, GameMenuText).
* The Traditional form of each string is produced **at generation time** with OpenCC `s2twp`
  (Taiwan phrase dictionary) plus an explicit override table for mahjong / product wording, and
  emitted as a Kotlin constant map. The shipped APK therefore needs no Python, no OpenCC and no
  runtime dictionary flip: `TraditionalChinese.text(source)` is a pure `Map` lookup on whole
  strings, so a number, a tile code, a path or a variable inside a dynamic message can never be
  damaged (the dynamic parts are composed by the caller, never converted).
* Calls whose Chinese argument carries `${...}` are recovered by a real lexical scan (nested
  template blocks, adjacent `+` concatenation, `trimIndent` blocks) and emitted as **template
  rows**: static wording converted, every interpolated value replaced by a `#` placeholder and
  copied byte for byte at runtime. A title such as `发现新版本 ${v}` therefore translates while
  the version string stays untouched.
* Strings that are not in either table (engine errors, game text, unknown diagnostics) are
  returned verbatim, which is what keeps the "unknown log stays original" contract.

Run from anywhere:  python3 tools/language/gen-traditional.py
"""
import glob
import io
import json
import os
import re
import sys

PROJ = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(PROJ, "hook-probe/src/main/java/com/yefeng/majmax/hookprobe")
OUT = os.path.join(SRC, "manager/TraditionalChinese.kt")

from opencc import OpenCC  # generation-time only; nothing of this ships

cc = OpenCC("s2twp")
HANT = re.compile(r"[\u4e00-\u9fff]")

# Whole-string replacements applied after s2twp. Each entry is tried on the converted text and on
# the raw Simplified text, so a key written in either script keeps working.
OVERRIDES = [
    ("全域性", "全域"),            # s2twp maps 全局 -> 全域性 for the log wording
    ("分割槽", "分頁"),            # s2twp renders 分区 (UI tab) as 分割槽 (disk partition)
    ("透過", "通過"),              # s2twp maps 通过 -> 透過, which reads as "through" here
    ("點選", "點擊"),              # product wording: 點選 is "select", the app means "tap"
    ("賬號", "帳號"),
    ("裡寶", "裏寶"),              # riichi 裏寶牌
    ("隻影響", "僅影響"),          # s2twp maps 只 -> 隻 (measure word) in adverbial position
    ("隻呼叫", "僅呼叫"),
    ("隻控制", "僅控制"),
    ("隻使用", "僅使用"),
    ("隻讀取", "僅讀取"),
    ("許可權", "權限"),            # Taiwan Android calls it 權限
    # Natural Taiwan product wording. These keys were previously written in Simplified, which
    # never matched the already-converted text; they are now spelled the way s2twp emits it (plus
    # a Simplified twin, see PAIRS below).
    ("懸浮窗", "懸浮視窗"),        # bare "floating window" -> the product's 懸浮視窗
    ("自定義", "自訂"),            # Taiwan writes 自訂 (自訂模型 / 自訂策略)
    ("開源許可", "開源授權"),      # the license screen is 開源授權
]
# Keys that only exist in Simplified and must be replaced *before* s2twp sees them.
PRE_OVERRIDES = [
    ("自定义", "自訂"),
    ("开源许可", "開源授權"),
]
# Phrase-level wording s2twp leaves Simplified. Applied to the converted text (and to the raw text
# before conversion), so "内置 Akagi" reads 內建 and "内置模型" reads 內建模型 like the rest of the UI.
PHRASE_FIXES = [
    ("內置", "內建"),
    ("內置模型", "內建模型"),
    ("內置策略", "內建策略"),
    ("內建 Akagi", "內建 Akagi"),
]
# Single-character fixes s2twp gets wrong in a few positions; applied last on the converted text.
# "隻" only means the measure word in a few places: after a comma/semicolon clause it is the
# adverb 只 and must become 僅; anywhere else 隻 (a bird/boat counter) is left alone.
CHAR_FIXES = []
STATEFUL_FIXES = [
    # 隻 is the measure word here: after a comma/semicolon clause it is the adverb 只 -> 僅.
    (re.compile(r"(?<=[，；。！？])\s*隻"), "僅"),
    # Clause-initial or after 它: the app's own sentence starts with 只… ("只控制…", "它只影响…").
    (re.compile(r"(?<=[，；。！？])\s*只"), "僅"),
    # 只 + verb at the head of the clause ("只控制…") or right after 它 ("它只影响…").
    # clause-initial "只 <verb>" -> 僅; "只有" (only-have) keeps its idiomatic spelling.
    (re.compile(r"^只(?=[^有\s])"), "僅"),
    (re.compile(r"它只"), "它僅"),
]

# Mahjong / product terms spelled the Traditional way explicitly, keyed on the Simplified source
# (they are applied before conversion, so s2twp cannot "fix" them differently).
TERMS = {
    "四人麻将": "四人麻將",
    "三人麻将": "三人麻將",
    "无人值守": "無人值守",
    "自动打牌": "自動打牌",
    "自动续打": "自動續打",
    "悬浮窗": "懸浮視窗",
    "软件": "軟體",
    "设置": "設定",
    "自定义": "自訂",
    "开源许可": "開源授權",
}

SKIP = ("http", "android.", "com.yefeng", "\\d", "\\$")
# Japanese rows are almost entirely kanji, so a Han-only filter lets them in and `s2twp` would
# rewrite kana-adjacent kanji (状態 -> 狀態, 保存 -> 儲存). The Japanese rendering is not a
# Traditional Chinese source: the zh-Hant branch always converts the *Chinese* argument instead.
KANA = re.compile(r"[\u3040-\u30ff\uff66-\uff9d]")
# A literal that differs from a source string only by enclosing spaces/quotes cannot be a lookup
# key (the callers normalise before matching), so it is dropped instead of shipped dead weight.


def convert(text: str) -> str:
    """Traditional rendering of one *static* string (no interpolation markers inside)."""
    src = text
    for a, b in PRE_OVERRIDES:
        src = src.replace(a, b)
    out = cc.convert(src)
    # Terms first on both spellings, so a key written Simplified replaces the converted result.
    for a, b in TERMS.items():
        out = out.replace(a, b)
        out = out.replace(cc.convert(a), b)
    for a, b in OVERRIDES:
        out = out.replace(a, b)
        out = out.replace(cc.convert(a), b)
    for a, b in PHRASE_FIXES:
        out = out.replace(a, b)
        out = out.replace(cc.convert(a), b)
    for a, b in CHAR_FIXES:
        out = out.replace(a, b)
    for rx, b in STATEFUL_FIXES:
        out = rx.sub(b, out)
    return out


def convert_template(template: str) -> str:
    """Convert only the literal segments of a `#`-placeholdered template."""
    return "#".join(convert(part) for part in template.split("#"))


# ---------------------------------------------------------------------------------------------
# Kotlin lexical scan: literals, templates, call arguments.
# ---------------------------------------------------------------------------------------------
class Literal:
    """One string literal, already unescaped, with its position in the source."""

    def __init__(self, raw, start, end, is_raw=False):
        self.raw = raw            # unescaped text; ${...} kept verbatim
        self.start = start
        self.end = end
        self.is_raw = is_raw


_ESCAPES = {"n": "\n", "t": "\t", "r": "\r", '"': '"', "'": "'", "\\": "\\",
            "$": "$", "b": "\b"}


def unescape(text: str) -> str:
    out, i, n = [], 0, len(text)
    while i < n:
        c = text[i]
        if c == "\\" and i + 1 < n:
            nxt = text[i + 1]
            if nxt in _ESCAPES:
                out.append(_ESCAPES[nxt])
                i += 2
                continue
            m = re.match(r"u([0-9a-fA-F]{4})", text[i + 1:])
            if m:
                out.append(chr(int(m.group(1), 16)))
                i += 6
                continue
            out.append(nxt)
            i += 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


def scan_literals(src: str):
    """Every ordinary / raw string literal, with ``${...}`` blocks kept intact.

    A single lexical pass: comments are skipped, escapes are honoured, and a ``${`` opens a block
    that is scanned to its matching brace, so a quote inside ``${if (a) "x" else "y"}`` can never
    truncate the outer literal (which is exactly what a regex gets wrong).
    """
    out = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if c == "/" and i + 1 < n and src[i + 1] == "/":
            j = src.find("\n", i)
            i = n if j < 0 else j + 1
            continue
        if c == "/" and i + 1 < n and src[i + 1] == "*":
            j = src.find("*/", i + 2)
            i = n if j < 0 else j + 2
            continue
        if src.startswith('"""', i):
            j = src.find('"""', i + 3)
            if j < 0:
                break
            out.append(Literal(src[i + 3:j], i, j + 3, is_raw=True))
            i = j + 3
            continue
        if c == '"':
            j, buf = i + 1, []
            while j < n:
                ch = src[j]
                if ch == "\\" and j + 1 < n:
                    buf.append(src[j:j + 2])
                    j += 2
                    continue
                if ch == '"':
                    break
                if ch == "$" and j + 1 < n and src[j + 1] == "{":
                    depth, k = 1, j + 2
                    while k < n and depth:
                        if src[k] == "{":
                            depth += 1
                        elif src[k] == "}":
                            depth -= 1
                        k += 1
                    buf.append(src[j:k])
                    j = k
                    continue
                buf.append(ch)
                j += 1
            out.append(Literal(unescape("".join(buf)), i, j + 1))
            i = j + 1
            continue
        i += 1
    return out


# A call whose first argument is copy: bare or member `tr`, `t`, `ui`, and the `put` rows.
CALL_RE = re.compile(r"(?<![A-Za-z0-9_])(?:[A-Za-z_][A-Za-z0-9_]*\.)*(tr|t|ui)\s*\(")
PUT_RE = re.compile(r"(?<![A-Za-z0-9_])put\s*\(")


def first_args(src: str):
    """(callee, raw first argument source text) for every tr/t/ui/put call in one file."""
    for m in list(CALL_RE.finditer(src)) + list(PUT_RE.finditer(src)):
        callee = m.group(1) if m.groups() else "put"
        start = m.end()
        depth, i, n, in_str = 0, start, len(src), None
        while i < n:
            ch = src[i]
            if in_str:
                if ch == "\\":
                    i += 2
                    continue
                if ch == in_str:
                    in_str = None
                i += 1
                continue
            if ch == '"':
                in_str = '"'
            elif ch in "([{":
                depth += 1
            elif ch in ")]}":
                if depth == 0:
                    break
                depth -= 1
            elif ch == "," and depth == 0:
                break
            i += 1
        yield callee, src[start:i]


def template_of(arg: str):
    """Normalise one call argument into ``(template, dynamicCount)`` or None.

    The argument must be pure copy: string literals joined with ``+`` (optionally through
    ``trimIndent()`` / ``trimMargin()``). Anything else -- a variable, a call, a conditional --
    is not a static string and returns None. Each interpolation collapses to a single ``$``.
    """
    arg = arg.strip()
    arg = re.sub(r"\s*\+\s*", "", arg)
    arg = re.sub(r"\s*\.trimIndent\(\)\s*", "", arg)
    arg = re.sub(r"\s*\.trimMargin\([^)]*\)\s*", "", arg)
    lits = scan_literals(arg)
    if not lits:
        return None
    rest = arg
    for lit in reversed(lits):
        rest = rest[:lit.start] + "\0" + rest[lit.end:]
    if rest.replace("\0", "").strip():
        return None
    text = "".join(lit.raw for lit in lits)
    out, i, n, dyn = [], 0, len(text), 0
    while i < n:
        if text[i] == "$" and i + 1 < n and text[i + 1] == "{":
            depth, k = 1, i + 2
            while k < n and depth:
                if text[k] == "{":
                    depth += 1
                elif text[k] == "}":
                    depth -= 1
                k += 1
            out.append("#")
            dyn += 1
            i = k
            continue
        if text[i] == "$" and i + 1 < n and re.match(r"[A-Za-z_]", text[i + 1]):
            k = i + 1
            while k < n and re.match(r"[A-Za-z0-9_]", text[k]):
                k += 1
            out.append("#")
            dyn += 1
            i = k
            continue
        out.append(text[i])
        i += 1
    return "".join(out), dyn


def source_files():
    return sorted(glob.glob(SRC + "/**/*.kt", recursive=True) + glob.glob(SRC + "/*.java"))


# Files whose Chinese literals are compared at display time (not only passed to tr()).
TABLE_FILES = {
    "ManagerLanguage.kt", "GameMenuText.kt", "AiScreen.kt", "MainActivity.kt",
    "AiFloatingWindow.kt", "RecommendationPanelOverlay.kt", "GameTopControl.java",
    "AiEndpointProvider.kt", "InGameLocalizer.kt",
}
# Markers that make a literal code rather than copy: a regex anchor, an escape, a format
# placeholder or a bare template notation. Kept minimal on purpose: "(和牌)" and "(tile)" are real
# copy and must stay collectable.
HARD_NOT_COPY = ("^", "\\", "|", "%s", "${", "<reason>", "<min>", "<max>", "<score>")
# Tiles and the win/call/discard words are short on purpose: they are one or two characters and
# would otherwise be dropped by the "too short to be copy" filter below.
TILE_WORDS = {"东", "南", "西", "北", "白", "发", "中", "万", "筒", "索", "赤",
              "和", "吃", "碰", "杠", "切", "摸", "荣", "自摸", "立直", "拔北"}


def collect():
    """(static, templated, skipped) over every Chinese copy argument the app passes to tr/t/ui."""
    static, templated, skipped = set(), set(), []
    for path in source_files():
        src = io.open(path, encoding="utf-8").read()
        base = os.path.basename(path)
        for callee, arg in first_args(src):
            got = template_of(arg)
            if got is None:
                if HANT.search(arg):
                    skipped.append((base, " ".join(arg.split())[:90], "not a literal"))
                continue
            text, dyn = got
            if not HANT.search(text):
                continue
            if dyn:
                templated.add(text)
            else:
                static.add(text)
        if base in TABLE_FILES:
            for lit in scan_literals(src):
                text = lit.raw
                if not HANT.search(text) or "#" in text or "$" in text or KANA.search(text):
                    continue
                # A regex anchor or a format placeholder marks code; parentheses and '<name>'
                # notation do not (the tiles tables use 和 (a win) and the panel uses (tile)).
                if any(t in text for t in HARD_NOT_COPY):
                    continue
                if len(text.strip()) <= 1 and text not in TILE_WORDS:
                    continue
                static.add(text)
    return static, templated, skipped


def kotlin_escape(s: str) -> str:
    return s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$")


# Number-only shapes that arrive without interpolation (built by callers with `+ n`).
SHAPES_EXTRA = [
    ("随机等待 ", " 秒 · 可随时关闭", "隨機等候 ", " 秒 · 可隨時關閉"),
    ("放铳风险指数 ", "", "放銃風險指數 ", ""),
    ("和牌率 ", "%", "和牌率 ", "%"),
    ("进张 ", " 枚", "進張 ", " 枚"),
    ("拔北 ×", "：已计入三麻番数", "拔北 ×", "：已計入三麻番數"),
    ("", "向听 · 听牌后显示番点", "", "向聽 · 聽牌後顯示番點"),
    ("", " 人", "", " 人"),
    ("模型文件超过 ", " MiB 限制", "模型檔案超過 ", " MiB 限制"),
]


def emit(static_pairs, template_pairs):
    L = []
    A = L.append
    A("package com.yefeng.majmax.hookprobe.manager")
    A("")
    A("/**")
    A(" * Compiled Traditional Chinese (zh-Hant) copy for the manager, the in-game Hook menu, the")
    A(" * recommendation window and the floating window.")
    A(" *")
    A(" * Generated by `tools/language/gen-traditional.py` (OpenCC `s2twp` plus an explicit mahjong /")
    A(" * product wording table) and checked in, so the APK never needs Python, OpenCC or a network")
    A(" * round trip at runtime.")
    A(" *")
    A(" * Three tables, all keyed on text the app itself wrote:")
    A(" *")
    A(" *  - [EXACT] holds complete strings. Only the app's own copy is a key; engine errors, game")
    A(" *    text and unknown diagnostics are not, so [text] returns them verbatim.")
    A(" *  - [TEMPLATES] holds the same copy with every interpolated value replaced by a `#`")
    A(" *    placeholder (`发现新版本 #`). The value the caller passed -- a version, a size, a date,")
    A(" *    an event id -- is copied byte for byte, so a dynamic message can never be damaged by a")
    A(" *    character conversion; only the wording around it is translated.")
    A(" *  - [SHAPES] holds number-only shapes that are composed without interpolation (`放铳风险指数 `")
    A(" *    + n), with the number copied untouched.")
    A(" *")
    A(" * All three are constant tables compiled into the dex; this is not a general text translator.")
    A(" */")
    A("internal object TraditionalChinese {")
    A("")
    A("    /** Placeholder standing for one interpolated value inside a [TEMPLATES] row. */")
    A("    private const val VALUE = '#'")
    A("")
    A("    /** Complete source string to its compiled Traditional rendering. */")
    A("    private val EXACT: Map<String, String> = buildMap {")
    for s, t in static_pairs:
        A(f'        put("{kotlin_escape(s)}", "{kotlin_escape(t)}")')
    A("    }")
    A("")
    A("    /**")
    A("     * Template rows: the source template with `#` placeholders and its Traditional rendering")
    A("     * with the same placeholders. A message is matched against the literal segments left to")
    A("     * right, so the captured values keep their exact bytes.")
    A("     */")
    A("    private class Template(val source: String, val traditional: String)")
    A("")
    A("    private val TEMPLATES: List<Template> = listOf(")
    for s, t in template_pairs:
        A(f'        Template("{kotlin_escape(s)}", "{kotlin_escape(t)}"),')
    A("    )")
    A("")
    A("    /** `<head>` / `<tail>` pairs for the number-only shapes; the value between is copied. */")
    A("    private class Shape(val zhHead: String, val zhTail: String, val head: String, val tail: String)")
    A("")
    A("    private val SHAPES: List<Shape> = listOf(")
    for zh_head, zh_tail, head, tail in SHAPES_EXTRA:
        A(f'        Shape("{kotlin_escape(zh_head)}", "{kotlin_escape(zh_tail)}", '
          f'"{kotlin_escape(head)}", "{kotlin_escape(tail)}"),')
    A("    )")
    A("")
    A("    /** Traditional copy of one known source string, or [source] itself when unknown. */")
    A("    fun text(source: String): String =")
    A("        EXACT[source] ?: textTemplate(source) ?: textShape(source) ?: source")
    A("")
    A("    /** True when this table can render [source] in Traditional Chinese. */")
    A("    fun knows(source: String): Boolean =")
    A("        EXACT.containsKey(source) || matchTemplate(source) != null || textShape(source) != null")
    A("")
    A("    /**")
    A("     * Renders a templated message: the static wording is converted and every value the caller")
    A("     * interpolated is copied unchanged. `null` when the shape is unknown, so the caller keeps")
    A("     * the original text.")
    A("     */")
    A("    private fun textTemplate(source: String): String? {")
    A("        val hit = matchTemplate(source) ?: return null")
    A("        val row = hit.first")
    A("        val values = hit.second")
    A("        val out = StringBuilder(row.traditional.length + source.length)")
    A("        var at = 0")
    A("        for (part in row.traditional.split(VALUE)) {")
    A("            out.append(part)")
    A("            if (at < values.size) out.append(values[at++])")
    A("        }")
    A("        return out.toString()")
    A("    }")
    A("")
    A("    /**")
    A("     * Matches [source] against [TEMPLATES]; the captured values run left to right. The rows")
    A("     * are tried in their Simplified form first, then in their converted form, so both a caller")
    A("     * that interpolates the raw Chinese text and one that interpolates into already-translated")
    A("     * wording are covered.")
    A("     */")
    A("    private fun matchTemplate(source: String): Pair<Template, List<String>>? {")
    A("        // the Simplified form the app wrote")
    A("        for (row in TEMPLATES) {")
    A("            val values = matchOneForm(row.source, source) ?: continue")
    A("            return row to values")
    A("        }")
    A("        // the same row with its static wording already converted (a caller that translated")
    A("        // the shell first and interpolated after it); a 0- or 1-value row is checked as a")
    A("        // whole here, because its text is the right side of an EXACT row and the extra")
    A("        // character fixes would otherwise be lost")
    A("        for (row in TEMPLATES) {")
    A("            if (row.traditional == row.source) continue")
    A("            val count = row.source.count { it == VALUE }")
    A("            val values = if (count <= 1) {")
    A("                if (matchOneForm(row.traditional, source) == null) continue")
    A("                listOf()")
    A("            } else {")
    A("                matchOneForm(row.traditional, source) ?: continue")
    A("            }")
    A("            return row to values")
    A("        }")
    A("        return null")
    A("    }")
    A("")
    A("    /**")
    A("     * Anchored match of one template form: the first literal starts the message, the last one")
    A("     * ends it, a value is whatever sits between two literals, and the tail value must start")
    A("     * after the last literal. A message with extra text around the known wording is not a hit.")
    A("     */")
    A("    private fun matchOneForm(template: String, source: String): List<String>? {")
    A("        val parts = template.split(VALUE)")
    A("        if (!source.startsWith(parts.first()) || !source.endsWith(parts.last())) return null")
    A("        val values = ArrayList<String>(parts.size - 1)")
    A("        var at = parts.first().length")
    A("        for (k in 1 until parts.size - 1) {")
    A("            val part = parts[k]")
    A("            val found = source.indexOf(part, at)")
    A("            if (found < 0) return null")
    A("            values.add(source.substring(at, found))")
    A("            at = found + part.length")
    A("        }")
    A("        val tailStart = source.length - parts.last().length")
    A("        if (tailStart < at) return null")
    A("        values.add(source.substring(at, tailStart))")
    A("        return values")
    A("    }")
    A("")
    A("    /**")
    A("     * Renders the known `<head><value><tail>` shapes of [SHAPES]; `null` when the source is")
    A("     * none of them, so the caller keeps the original text untouched. The value between head")
    A("     * and tail is copied without any conversion.")
    A("     */")
    A("    private fun textShape(source: String): String? {")
    A("        for (shape in SHAPES) {")
    A("            if (!source.startsWith(shape.zhHead) || !source.endsWith(shape.zhTail)) continue")
    A("            if (source.length < shape.zhHead.length + shape.zhTail.length) continue")
    A("            val middle = source.substring(shape.zhHead.length, source.length - shape.zhTail.length)")
    A("            return shape.head + middle + shape.tail")
    A("        }")
    A("        return null")
    A("    }")
    A("}")
    A("")
    return "\n".join(L)


def main():
    static, templated, skipped = collect()
    static_pairs = [(s, convert(s)) for s in static if convert(s) != s]
    template_pairs = []
    for s in sorted(templated):
        t = convert_template(s)
        if t != s:
            template_pairs.append((s, t))
    # Every literal segment of a template is also a row of its own: a caller that translates the
    # shell first and interpolates after it ("${t(\"发现新版本 \")} 0.15.2") is then covered too.
    extra = {}
    template_pairs_src = list(template_pairs)
    for s, t in template_pairs_src:
        for part in t.split("#"):
            if not part.strip() or part in extra:
                continue
            converted = convert(part)
            if converted != part:
                extra[part] = converted
        # the literal segments are taken from the source and the converted row alike: the source
        # side is what a caller interpolates into the Chinese shell
        for part in s.split("#"):
            if part.strip() and part not in extra:
                converted = convert(part)
                if converted != part:
                    extra[part] = converted
    have = {src for src, _ in static_pairs}
    static_pairs.sort()
    all_pairs = sorted([(k, v) for k, v in extra.items() if k not in have] + static_pairs)
    # long templates first, so a shorter prefix row can never shadow a more specific one
    template_pairs.sort(key=lambda p: (-len(p[0]), p[0]))
    io.open(OUT, "w", encoding="utf-8").write(emit(all_pairs, template_pairs))

    identical = sorted(s for s in templated if convert_template(s) == s)
    report = {
        "static": len(static_pairs),
        "templateRows": [row for row, _ in template_pairs],
        "templates": len(template_pairs),
        "templateIdentical": identical,
        "notLiteral": skipped,
    }
    print(f"wrote {OUT}: {len(static_pairs)} exact + {len(template_pairs)} templates + "
          f"{len(SHAPES_EXTRA)} shapes")
    print(f"  sources: {len(static)} static, {len(templated)} templated "
          f"({len(identical)} already Traditional), {len(skipped)} non-literal arguments")
    if "--report" in sys.argv:
        path = sys.argv[sys.argv.index("--report") + 1]
        io.open(path, "w", encoding="utf-8").write(json.dumps(report, ensure_ascii=False, indent=1))
        print("  report:", path)
    return report


if __name__ == "__main__":
    main()
