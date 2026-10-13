#!/usr/bin/env python3
"""Аудит публічного репозиторію: ні ключів, ні паролів, ні записів, ні особистих даних.

Запуск: python3 scripts/audit_repo.py [--root ШЛЯХ]
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

FORBIDDEN_FILES = re.compile(r"\.(jks|keystore|p12|pem|key|wav|mp3|m4a|flac|ogg|pcm)$|signing\.properties$", re.I)
SECRET_PATTERNS = [
    ("приватний ключ", re.compile("BEGIN (RSA |OPENSSH |EC )?PRIVATE" + " KEY")),
    ("токен GitHub", re.compile("gh" + "p_[A-Za-z0-9]{20,}|github" + "_pat_[A-Za-z0-9_]{20,}")),
    ("пароль ключа", re.compile(r"(store|key)Password\s*=\s*[A-Za-z0-9]{8,}")),
    ("особиста пошта", re.compile(r"[A-Za-z0-9._%+-]+@gmail\.com", re.I)),
]
REQUIRED_IGNORES = ["*.jks", "*.keystore", "*signing.properties", "*.wav", "*.mp3", "local.properties"]


def tracked(root: Path) -> list[str]:
    try:
        out = subprocess.run(["git", "-C", str(root), "ls-files"], capture_output=True, text=True, check=True).stdout
        return [l for l in out.splitlines() if l]
    except Exception:
        return [p.relative_to(root).as_posix() for p in root.rglob("*") if p.is_file() and ".git/" not in p.as_posix()]


def audit(root: Path) -> list[tuple[str, str]]:
    problems: list[tuple[str, str]] = []
    files = tracked(root)
    for f in files:
        if FORBIDDEN_FILES.search(f):
            problems.append(("REPO-01", f"{f}: ключ або аудіо не можна класти в публічний репо"))
    for f in files:
        p = root / f
        if not p.is_file() or p.stat().st_size > 2_000_000:
            continue
        try:
            text = p.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        for name, pat in SECRET_PATTERNS:
            m = pat.search(text)
            if m:
                problems.append(("REPO-02", f"{f}: {name} ({m.group(0)[:12]}…)"))
    gi = root / ".gitignore"
    ignores = gi.read_text(encoding="utf-8") if gi.exists() else ""
    for pat in REQUIRED_IGNORES:
        if pat not in ignores:
            problems.append(("REPO-03", f".gitignore: немає «{pat}»"))
    gradle = root / "android/app/build.gradle.kts"
    if gradle.exists():
        g = gradle.read_text(encoding="utf-8")
        if not re.search(r'versionName = "\d+\.\d+\.\d+"', g) or not re.search(r"versionCode = \d+", g):
            problems.append(("REPO-04", "build.gradle.kts: versionName має бути X.Y.Z, versionCode — число"))
    return problems


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=str(Path(__file__).resolve().parents[1]))
    args = ap.parse_args()
    problems = audit(Path(args.root))
    print("Аудит публічного репо Track Catcher:", "PASS" if not problems else "FAIL")
    for rule, msg in problems:
        print(f"::error title={rule}::{msg}")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
