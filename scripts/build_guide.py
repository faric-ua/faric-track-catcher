#!/usr/bin/env python3
"""Інструкція користувача: docs/guide/guide.html → android/app/src/main/assets/guide/index.html.

Джерело — один файл-фрагмент (без <html>/<head>/<body>): так його ж можна опублікувати
артефактом. Для застосунку скрипт додає <!doctype html> і <head> (інакше WebView у «режимі
сумісності»), і підставляє версію з android/app/build.gradle.kts замість __VERSION__.

  python3 scripts/build_guide.py                 # перезібрати сторінку в застосунку
  python3 scripts/build_guide.py --fragment OUT  # фрагмент з версією (для артефакту)
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "docs/guide/guide.html"
OUT = ROOT / "android/app/src/main/assets/guide/index.html"
GRADLE = ROOT / "android/app/build.gradle.kts"
BODY_START = '<svg width="0" height="0"'


def version() -> str:
    m = re.search(r'versionName\s*=\s*"([^"]+)"', GRADLE.read_text(encoding="utf-8"))
    return m.group(1) if m else "?"


def fragment() -> str:
    return SRC.read_text(encoding="utf-8").replace("__VERSION__", version())


def page() -> str:
    text = fragment()
    cut = text.index(BODY_START)
    head, body = text[:cut].rstrip(), text[cut:].rstrip()
    head = head.replace('<meta name="claude:viewport-fit-sides" content="cover">\n', "")
    return (
        "<!doctype html>\n<!-- Зібрано scripts/build_guide.py з docs/guide/guide.html — редагувати там. -->\n"
        '<html lang="uk">\n<head>\n<meta charset="utf-8">\n'
        '<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">\n'
        f"{head}\n</head>\n<body>\n{body}\n</body>\n</html>\n"
    )


def main(argv: list[str]) -> int:
    if len(argv) == 2 and argv[0] == "--fragment":
        Path(argv[1]).write_text(fragment(), encoding="utf-8")
        print(f"фрагмент → {argv[1]}")
        return 0
    OUT.write_text(page(), encoding="utf-8")
    print(f"{OUT.relative_to(ROOT)}: {OUT.stat().st_size // 1024} КБ, версія {version()}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
