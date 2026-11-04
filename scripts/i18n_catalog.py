"""Sentence catalog shared by the resource generator and i18n-outstanding.

Definition sites are S("…") literals and `label = "…"` sentences. The catalog
is not edited here.
"""
import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main"
CATALOG = APP / "i18n"
GEN_RES = ROOT / "app/build/generated/i18n/res"
GEN_KT = (
    ROOT
    / "app/build/generated/i18n/kotlin/com/davidlang/torque/fuelsmoothing/I18nSentences.kt"
)
APP_NAME = "Fuel Smoothing for Torque"

# Java Formatter conversion, not an arbitrary letter. A prose "%" (for example
# "Corrected %, Remaining") is not a conversion: String.format rejects it.
# Flags are the repeatable ones that do not swallow the next word ("%, Remaining",
# "% (Chunk)"). Index, width, and precision still match %1$.1f and %2$.0f.
FORMAT_RE = re.compile(r"%(?:\d+\$)?[-#+0]*\d*(?:\.\d+)?[bBhHsScCdoxXeEfgGaAn%]")


def format_tokens(text: str) -> list[str]:
    """Format tokens in source order. Compare with same_tokens; word order may move a token."""
    tokens = []
    i = 0
    while i < len(text):
        if text[i] != "%":
            i += 1
            continue
        match = FORMAT_RE.match(text, i)
        if not match:
            i += 1
            continue
        tokens.append(match.group(0))
        i = match.end()
    return tokens


def same_tokens(a: str, b: str) -> bool:
    return sorted(format_tokens(a)) == sorted(format_tokens(b))


def formats_cleanly(text: str) -> bool:
    """True when every % is %% or a printf conversion."""
    i = 0
    while i < len(text):
        if text[i] != "%":
            i += 1
            continue
        match = FORMAT_RE.match(text, i)
        if not match:
            return False
        i = match.end()
    return True


def kotlin_quote(text: str) -> str:
    out = ['"']
    for ch in text:
        if ch == "\\":
            out.append("\\\\")
        elif ch == '"':
            out.append('\\"')
        elif ch == "$":
            out.append("\\$")
        elif ch == "\n":
            out.append("\\n")
        elif ch == "\r":
            out.append("\\r")
        elif ch == "\t":
            out.append("\\t")
        else:
            out.append(ch)
    out.append('"')
    return "".join(out)


def escape_android(text: str) -> str:
    out = []
    for ch in text:
        if ch == "\\":
            out.append("\\\\")
        elif ch == "\n":
            out.append("\\n")
        elif ch == "\r":
            out.append("\\r")
        elif ch == "\t":
            out.append("\\t")
        elif ch == "'":
            out.append("\\'")
        elif ch == '"':
            out.append('\\"')
        elif ch == "&":
            out.append("&amp;")
        elif ch == "<":
            out.append("&lt;")
        else:
            out.append(ch)
    escaped = "".join(out)
    if escaped.startswith("@") or escaped.startswith("?"):
        escaped = "\\" + escaped
    return escaped


def parse_kotlin_string(text: str, i: int) -> tuple[str, int]:
    if text.startswith('"""', i):
        j = text.find('"""', i + 3)
        if j < 0:
            raise ValueError("unterminated triple string")
        return text[i + 3 : j], j + 3
    if text[i] != '"':
        raise ValueError("expected string")
    i += 1
    chars: list[str] = []
    while i < len(text):
        ch = text[i]
        if ch == '"':
            return "".join(chars), i + 1
        if ch == "\\":
            i += 1
            if i >= len(text):
                raise ValueError("truncated escape")
            n = text[i]
            chars.append(
                {"n": "\n", "r": "\r", "t": "\t", "$": "$", '"': '"', "\\": "\\", "'": "'"}.get(n, n)
            )
            i += 1
            continue
        chars.append(ch)
        i += 1
    raise ValueError("unterminated string")


def _skip_char(text: str, i: int) -> int:
    i += 1
    if i < len(text) and text[i] == "\\":
        i += 2
    elif i < len(text):
        i += 1
    if i < len(text) and text[i] == "'":
        i += 1
    return i


def _call_name(text: str, i: int, name: str) -> bool:
    if i >= 4 and text[i - 4 : i] == "fun ":
        return False
    if i > 0 and (text[i - 1].isalnum() or text[i - 1] == "_"):
        return False
    return text.startswith(name + "(", i)


def _is_label_assign(text: str, i: int) -> bool:
    if not text.startswith("label", i):
        return False
    if i > 0 and (text[i - 1].isalnum() or text[i - 1] == "_"):
        return False
    j = i + len("label")
    while j < len(text) and text[j].isspace():
        j += 1
    return j < len(text) and text[j] == "="


def iter_kotlin_files() -> list[Path]:
    files = []
    for path in sorted(APP.rglob("*.kt")):
        if "build" in path.parts:
            continue
        files.append(path)
    return files


def scan_file(text: str) -> list[str]:
    found: list[str] = []
    i = 0
    n = len(text)
    while i < n:
        if text.startswith("//", i):
            j = text.find("\n", i)
            i = n if j < 0 else j + 1
            continue
        if text.startswith("/*", i):
            j = text.find("*/", i + 2)
            i = n if j < 0 else j + 2
            continue
        if text.startswith('"""', i) or text[i] == '"':
            _, i = parse_kotlin_string(text, i)
            continue
        if text[i] == "'":
            i = _skip_char(text, i)
            continue
        if text.startswith("S(", i) and _call_name(text, i, "S"):
            j = i + 2
            while j < n and text[j].isspace():
                j += 1
            if j < n and (text[j] == '"' or text.startswith('"""', j)):
                sentence, _nxt = parse_kotlin_string(text, j)
                found.append(sentence)
            i += 2
            continue
        if _is_label_assign(text, i):
            j = i + len("label")
            while j < n and text[j].isspace():
                j += 1
            j += 1  # '='
            while j < n and text[j].isspace():
                j += 1
            if j < n and (text[j] == '"' or text.startswith('"""', j)):
                sentence, nxt = parse_kotlin_string(text, j)
                found.append(sentence)
                i = nxt
                continue
        i += 1
    return found


def definition_sentences() -> list[str]:
    seen: set[str] = set()
    ordered: list[str] = []
    for path in iter_kotlin_files():
        for sentence in scan_file(path.read_text(encoding="utf-8")):
            if sentence not in seen:
                seen.add(sentence)
                ordered.append(sentence)
    return ordered


def read_keep_terms() -> list[str]:
    path = CATALOG / "keep-terms.txt"
    if not path.is_file():
        return []
    return [line.strip() for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def _boundary(ch: str) -> bool:
    return ch == "" or not (ch.isalnum() or ch == "_")


def _replace_term(text: str, term: str) -> str:
    if not term:
        return text
    out: list[str] = []
    start = 0
    while True:
        i = text.find(term, start)
        if i < 0:
            out.append(text[start:])
            break
        before = text[i - 1] if i > 0 else ""
        after_i = i + len(term)
        after = text[after_i] if after_i < len(text) else ""
        if _boundary(before) and _boundary(after):
            out.append(text[start:i])
            out.append(" ")
            start = after_i
        else:
            start = i + 1
    return "".join(out)


def worth_sending(sentence: str, terms: list[str]) -> bool:
    stripped = sentence
    for term in sorted(terms, key=len, reverse=True):
        stripped = _replace_term(stripped, term)
    for token in sorted(set(format_tokens(stripped)), key=len, reverse=True):
        stripped = stripped.replace(token, " ")
    return any(ch.isalnum() for ch in stripped)


def catalog_files() -> list[Path]:
    if not CATALOG.is_dir():
        return []
    files = []
    for path in sorted(CATALOG.glob("*.json")):
        if path.name.endswith(".override.json"):
            continue
        files.append(path)
    return files


def load_entries(path: Path) -> list[dict]:
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, list):
        raise SystemExit(f"{path} is not a list")
    entries = []
    for item in data:
        entry = {
            "key": item["key"],
            "source": item["source"],
            "text": item["text"],
            "pending": bool(item.get("pending", False)),
        }
        entries.append(entry)
    return entries


def load_catalog() -> dict[str, list[dict]]:
    out: dict[str, list[dict]] = {}
    for path in catalog_files():
        out[path.stem] = load_entries(path)
    return out


def load_overrides(pref: str) -> list[tuple[str, str]]:
    path = CATALOG / f"{pref}.override.json"
    if not path.is_file():
        return []
    data = json.loads(path.read_text(encoding="utf-8"))
    items = data if isinstance(data, list) else data.get("entries", [])
    ordered: list[tuple[str, str]] = []
    for item in items:
        if "key" in item and "text" in item:
            ordered.append((item["key"], item["text"]))
    return ordered


def write_entries(path: Path, entries: list[dict]) -> None:
    payload = []
    for entry in entries:
        item = {"key": entry["key"], "source": entry["source"], "text": entry["text"]}
        if entry.get("pending"):
            item["pending"] = True
        payload.append(item)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def head_texts(path: Path) -> dict[str, str]:
    rel = path.relative_to(ROOT).as_posix()
    try:
        raw = subprocess.check_output(
            ["git", "show", f"HEAD:{rel}"],
            cwd=ROOT,
            stderr=subprocess.DEVNULL,
        )
    except subprocess.CalledProcessError:
        return {}
    data = json.loads(raw.decode("utf-8"))
    if not isinstance(data, list):
        return {}
    return {item["key"]: item.get("text", "") for item in data if "key" in item}


def resource_name(sentence: str, used: set[str]) -> str:
    if sentence == APP_NAME:
        name = "app_name"
    else:
        import hashlib

        name = "s_" + hashlib.sha256(sentence.encode("utf-8")).hexdigest()[:12]
    if name in used and used:
        raise SystemExit(f"resource name collision {name}")
    used.add(name)
    return name


def res_dir_for(pref: str) -> Path:
    if pref == "values":
        return GEN_RES / "values"
    return GEN_RES / f"values-{pref}"
