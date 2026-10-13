#!/usr/bin/env python3
"""Аудит інтерфейсу Track Catcher (правила — docs/UI_STYLE_CONTRACT.md).

Запуск: python3 scripts/audit_ui.py [--root ШЛЯХ]
Вихід 0 — усе гаразд, 1 — є порушення (список з номерами правил).
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ALLOWED_LATIN_LABELS = {"MP3", "WAV", "MP3 + WAV", "Track Catcher"}
CANCEL_WORDS = ("Скасувати", "Закрити", "Не зараз", "Назад")


def kotlin_sources(root: Path) -> list[Path]:
    return sorted((root / "android/app/src/main/java").rglob("*.kt"))


def audit(root: Path) -> list[tuple[str, str]]:
    problems: list[tuple[str, str]] = []
    sources = kotlin_sources(root)
    if not sources:
        return [("UI-00", "не знайдено жодного .kt у android/app/src/main/java")]

    for path in sources:
        rel = path.relative_to(root).as_posix()
        text = path.read_text(encoding="utf-8")

        # UI-01: ніяких стандартних сірих діалогів і кнопок.
        for pat in (r"AlertDialog", r"MaterialAlertDialogBuilder", r"import android\.widget\.Button\b",
                    r"ProgressDialog", r"DatePickerDialog"):
            if re.search(pat, text):
                problems.append(("UI-01", f"{rel}: стандартний елемент Android ({pat})"))

        # UI-02: кольори — лише в Palette.kt.
        if path.name != "Palette.kt":
            for m in re.finditer(r"0x[0-9A-Fa-f]{8}|Color\.parseColor|Color\.rgb\(|Color\.argb\(", text):
                line = text.count("\n", 0, m.start()) + 1
                problems.append(("UI-02", f"{rel}:{line}: колір поза Palette.kt ({m.group(0)})"))

        # UI-03: вибране — зелена рамка, без галочок.
        for ch in ("✓", "✔", "☑"):
            if ch in text:
                problems.append(("UI-03", f"{rel}: галочка «{ch}» в інтерфейсі"))

        # UI-06: орієнтацію не блокуємо.
        if "setRequestedOrientation" in text:
            problems.append(("UI-06", f"{rel}: блокування орієнтації"))

        # UI-07: без фіксованої висоти кнопок/тексту.
        for m in re.finditer(r"\.height\s*=|setHeight\(", text):
            line = text.count("\n", 0, m.start()) + 1
            problems.append(("UI-07", f"{rel}:{line}: фіксована висота — довгий підпис обріжеться"))

        # UI-09: підписи українською.
        for m in re.finditer(r'ui\.(button|choice|title|label)\(\s*"([^"]*)"', text):
            label = m.group(2)
            if "$" in label or label in ALLOWED_LATIN_LABELS:
                continue
            if re.search(r"[A-Za-z]", label) and not re.search(r"[А-Яа-яІіЇїЄєҐґ]", label):
                line = text.count("\n", 0, m.start()) + 1
                problems.append(("UI-09", f"{rel}:{line}: підпис не українською: «{label}»"))

        # UI-10: «Скасувати/Закрити» — останньою (праворуч).
        for m in re.finditer(r"actions\s*=\s*listOf\((.*?)\)\s*,\s*\n", text, re.S):
            labels = re.findall(r'"([^"]+)"\s+to\s+Ui\.Kind', m.group(1))
            for i, lab in enumerate(labels):
                if lab in CANCEL_WORDS and i != len(labels) - 1:
                    problems.append(("UI-10", f"{rel}: «{lab}» має бути останньою кнопкою"))

    ui_kit = root / "android/app/src/main/java/ua/faric/trackcatcher/ui/Ui.kt"
    if ui_kit.exists():
        kit = ui_kit.read_text(encoding="utf-8")
        m = re.search(r"MIN_TOUCH_DP\s*=\s*(\d+)", kit)
        # UI-04: зона дотику ≥ 48 dp.
        if not m or int(m.group(1)) < 48:
            problems.append(("UI-04", "Ui.MIN_TOUCH_DP має бути ≥ 48"))
        # UI-07: кнопки переносять текст.
        if "maxLines = 3" not in kit:
            problems.append(("UI-07", "Ui.button має дозволяти 3 рядки"))
    else:
        problems.append(("UI-04", "немає ui/Ui.kt"))

    # UI-05: кожна вкладка (клас-нащадок Screen) має книжковий і альбомний варіант.
    for path in sources:
        body = path.read_text(encoding="utf-8")
        if re.search(r"\)\s*:\s*Screen\(host\)", body) or (path.name.endswith("Screen.kt") and "Screen(host)" in body):
            if "twoPane" not in body:
                problems.append(("UI-05", f"{path.relative_to(root).as_posix()}: немає альбомного варіанта (twoPane)"))

    # UI-08: вікна з ключем відновлюються після повороту.
    for path in sources:
        text = path.read_text(encoding="utf-8")
        if "overlay.show(" in text and path.name.endswith("Screen.kt") and "override fun restoreDialog" not in text:
            problems.append(("UI-08", f"{path.relative_to(root).as_posix()}: показує вікно, але не відновлює після повороту"))

    # UI-06: маніфест не блокує орієнтацію.
    manifest = root / "android/app/src/main/AndroidManifest.xml"
    if manifest.exists() and "screenOrientation" in manifest.read_text(encoding="utf-8"):
        problems.append(("UI-06", "AndroidManifest.xml: screenOrientation заблоковано"))
    return problems


RULES = {
    "UI-01": "Без стандартних сірих діалогів/кнопок Android",
    "UI-02": "Кольори лише з Palette.kt",
    "UI-03": "Вибране — зелена рамка, без галочок",
    "UI-04": "Зона дотику ≥ 48 dp",
    "UI-05": "Кожен екран: книжковий і альбомний варіант",
    "UI-06": "Орієнтацію не блокуємо",
    "UI-07": "Довгі підписи переносяться (без фіксованої висоти)",
    "UI-08": "Відкрите вікно переживає поворот екрана",
    "UI-09": "Підписи українською",
    "UI-10": "«Скасувати/Закрити» — остання кнопка (праворуч)",
}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=str(Path(__file__).resolve().parents[1]))
    args = ap.parse_args()
    problems = audit(Path(args.root))
    failed = {rule for rule, _ in problems}
    print("Аудит інтерфейсу Track Catcher")
    for rule, title in RULES.items():
        print(f"  {'FAIL' if rule in failed else 'PASS'}  {rule}  {title}")
    for rule, msg in problems:
        print(f"::error title={rule}::{msg}")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
