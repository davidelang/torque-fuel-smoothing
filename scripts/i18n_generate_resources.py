#!/usr/bin/env python3
"""Write build-output string resources and the S() id map from the catalog.

Does not translate, does not use the network, and does not edit the catalog
or a call-site sentence.
"""
import sys
from pathlib import Path

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))

from i18n_catalog import (
    APP_NAME,
    GEN_KT,
    catalog_files,
    definition_sentences,
    escape_android,
    formats_cleanly,
    same_tokens,
    kotlin_quote,
    load_catalog,
    load_overrides,
    resource_name,
    res_dir_for,
)


def _write_xml(path: Path, rows: list[tuple[str, str, bool]]) -> None:
    lines = ['<?xml version="1.0" encoding="utf-8"?>', "<resources>"]
    for name, text, formatted in rows:
        attr = "" if formatted else ' formatted="false"'
        lines.append(f'    <string name="{name}"{attr}>{escape_android(text)}</string>')
    lines.append("</resources>")
    lines.append("")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines), encoding="utf-8")


def _choose(sentence: str, entry: dict | None, overrides: list[tuple[str, str]]) -> tuple[str, bool]:
    """Return (text, fell_back_to_english)."""
    if entry is None or entry.get("pending"):
        return sentence, True
    for okey, otext in overrides:
        if okey == sentence:
            if same_tokens(otext, sentence):
                return otext, False
            return sentence, True
    return entry["text"], False


def _write_kotlin(pairs: list[tuple[str, str]]) -> None:
    lines = [
        "package com.davidlang.torque.fuelsmoothing",
        "",
        "internal object I18nSentences {",
        "    fun id(sentence: String): Int = map[sentence] ?: 0",
        "",
        f"    private val map: Map<String, Int> = HashMap<String, Int>({max(len(pairs), 1)}).also {{ into ->",
    ]
    step = 80
    chunks = [pairs[i : i + step] for i in range(0, len(pairs), step)] or [[]]
    for index in range(len(chunks)):
        lines.append(f"        fill{index}(into)")
    lines.append("    }")
    lines.append("")
    for index, chunk in enumerate(chunks):
        lines.append(f"    private fun fill{index}(into: MutableMap<String, Int>) {{")
        for sentence, name in chunk:
            lines.append(f"        into[{kotlin_quote(sentence)}] = R.string.{name}")
        lines.append("    }")
        lines.append("")
    lines.append("}")
    lines.append("")
    GEN_KT.parent.mkdir(parents=True, exist_ok=True)
    GEN_KT.write_text("\n".join(lines), encoding="utf-8")


def main() -> None:
    order = definition_sentences()
    catalog = load_catalog()
    if not catalog_files():
        raise SystemExit("no catalog under app/src/main/i18n")
    by_pref: dict[str, dict[str, dict]] = {}
    for pref, entries in catalog.items():
        by_pref[pref] = {entry["key"]: entry for entry in entries}
    used: set[str] = set()
    names = {sentence: resource_name(sentence, used) for sentence in order}
    fallback: set[str] = set()
    default_rows = []
    for sentence in order:
        default_rows.append((names[sentence], sentence, formats_cleanly(sentence)))
    _write_xml(res_dir_for("values") / "strings.xml", default_rows)
    for pref in sorted(by_pref):
        overrides = load_overrides(pref)
        rows = []
        for sentence in order:
            if sentence == APP_NAME:
                continue
            text, fell = _choose(sentence, by_pref[pref].get(sentence), overrides)
            if fell:
                fallback.add(sentence)
            rows.append((names[sentence], text, formats_cleanly(sentence)))
        _write_xml(res_dir_for(pref) / "strings.xml", rows)
    _write_kotlin([(sentence, names[sentence]) for sentence in order])
    print(f"i18n fallback: {len(fallback)}")


if __name__ == "__main__":
    main()
