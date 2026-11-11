#!/usr/bin/env python3
"""Audit zh-Hant coverage of every UI string the app writes, call site by call site.

This is the reviewer's question made executable: *for every* `tr(zh, ...)` / `t(zh)` call and
every Chinese literal used by the shared copy tables, can the runtime table actually render it?

Classification (printed as a table, and available as JSON with `--json`):

  EXACT      the Chinese argument is a complete string and the table has an exact row
  TEMPLATE   the argument carries `${...}`; the table has a matching template row and the
             captured values are copied verbatim (the audit re-renders the real call shape)
  SAME       the argument is already Traditional-only (no conversion needed)
  MISSING    app copy that the table cannot render -- the actual leak list
  RAW        not app copy (a variable, a regex, an identifier): reported, never translated

Neither this script nor the runtime converts a message that is not one of the app's own strings:
an unknown diagnostic has no row and therefore stays as it was received.

Usage: python3 tools/language/audit-traditional.py [--json path] [--quiet]
"""
import glob
import importlib.util
import io
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
spec = importlib.util.spec_from_file_location("gen_traditional", os.path.join(HERE, "gen-traditional.py"))
G = importlib.util.module_from_spec(spec)
spec.loader.exec_module(G)

HANT = re.compile("[\u4e00-\u9fff]")
KANA = re.compile("[\u3040-\u30ff\uff66-\uff9d]")


def load_table():
    """The generated Kotlin table, read back as (exact keys, template sources)."""
    src = io.open(G.OUT, encoding="utf-8").read()
    exact = set()
    templates = []
    for m in re.finditer(r'put\("((?:[^"\\]|\\.)*)", ', src):
        exact.add(unesc(m.group(1)))
    for m in re.finditer(r'Template\("((?:[^"\\]|\\.)*)", ', src):
        templates.append(unesc(m.group(1)))
    return exact, templates


def unesc(text):
    return text.replace('\\\\', '\\').replace('\\"', '"').replace('\\$', '$')


def render(template, values):
    """Renders a template with sample values, exactly like TraditionalChinese.textTemplate."""
    parts = template.split('#')
    out = [parts[0]]
    for k, value in enumerate(values):
        if k + 1 >= len(parts):
            break
        out.append(value)
        out.append(parts[k + 1])
    return ''.join(out)


# Source templates that are identical in both scripts: the runtime returns the source, which is
# already correct, so they need no row. Matched on the exact template text, never by "looks fine".
SAME_TEMPLATES = {
    "# 人",          # "4 人" is 4 人 in both scripts
    "切 #",          # 切 is the same character
    "碰 #",
    "吃 #",
    "和率 #%",
    "立直 · 切 #",
}


def main():
    exact, templates = load_table()
    rows, counts = [], {}
    for path in G.source_files():
        src = io.open(path, encoding="utf-8").read()
        base = os.path.basename(path)
        for callee, arg in G.first_args(src):
            got = G.template_of(arg)
            if got is None:
                if HANT.search(arg):
                    rows.append((base, "RAW", " ".join(arg.split())[:70], ""))
                    counts["RAW"] = counts.get("RAW", 0) + 1
                continue
            text, dyn = got
            if not HANT.search(text) or KANA.search(text):
                continue
            # the real shape the app passes: a sample value for every interpolation
            if dyn:
                values = ["0.15.2" if "版本" in text else "X"] * dyn
                probe = render(text, values)
                # a template fits when its literal segments match the message with any values
                # in between, i.e. the same rule the runtime uses in matchOne()
                hit = next((t for t in templates if _matches(t, probe)), None)
                if hit is not None:
                    kind, target = "TEMPLATE", hit
                elif text in SAME_TEMPLATES:
                    kind, target = "SAME", text
                else:
                    kind, target = "MISSING", text
            else:
                if text in exact:
                    kind, target = "EXACT", text
                elif G.convert(text) == text:
                    kind, target = "SAME", text
                else:
                    kind, target = "MISSING", text
            counts[kind] = counts.get(kind, 0) + 1
            rows.append((base, kind, text, target))
    # dedupe on (kind, text) so one phrase seen twice does not inflate the numbers
    seen, uniq = set(), []
    for r in rows:
        key = (r[2], r[1])
        if key in seen:
            continue
        seen.add(key)
        uniq.append(r)
    rec = {}
    for r in uniq:
        rec[r[1]] = rec.get(r[1], 0) + 1
    if "--json" in sys.argv:
        json.dump({"counts": rec, "rows": uniq}, io.open(sys.argv[sys.argv.index("--json") + 1],
                                                          "w", encoding="utf-8"),
                  ensure_ascii=False, indent=1)
    if "--quiet" not in sys.argv:
        print("zh-Hant coverage per unique call argument")
        for kind in ("EXACT", "TEMPLATE", "SAME", "MISSING", "RAW"):
            print(f"  {kind:<9} {rec.get(kind, 0)}")
        missing = [r for r in uniq if r[1] == "MISSING"]
        if missing:
            print("\nMISSING (app copy the table cannot render):")
            for r in missing:
                print(f"  {r[0]:<26} {r[2]}")
        raw = [r for r in uniq if r[1] == "RAW"]
        if raw:
            print("\nRAW (not app copy, deliberately left alone):")
            for r in raw:
                print(f"  {r[0]:<26} {r[2]}")
    return 1 if rec.get("MISSING") else 0


def _matches(template, probe):
    parts = template.split('#')
    if not probe.startswith(parts[0]) or not probe.endswith(parts[-1]):
        return False
    at = len(parts[0])
    for part in parts[1:-1]:
        found = probe.find(part, at)
        if found < 0:
            return False
        at = found + len(part)
    return True


if __name__ == "__main__":
    sys.exit(main())
