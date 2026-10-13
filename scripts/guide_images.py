#!/usr/bin/env python3
"""Знімки з емулятора (workflow «UI screens», гілка diag/ui-screens-out) → картинки інструкції.

Запуск: python3 scripts/guide_images.py ШЛЯХ_ДО_ui-screens
Пише WebP у android/app/src/main/assets/guide/img/ (демо-дані, без особистого).
"""
import sys
from pathlib import Path

from PIL import Image

MAP = {
    "p01-first-run.png": "first-run.webp",
    "p02-record-ready.png": "ready.webp",
    "p03-record-recording.png": "recording.webp",
    "p03b-night-screen.png": "night.webp",
    "p04-history.png": "history.webp",
    "p05-history-detail.png": "detail.webp",
    "p06-delete-confirm.png": "delete.webp",
    "p07-settings.png": "settings.webp",
    "l03-record-two-pane.png": "land-record.webp",
    "l02-history-two-pane.png": "land-history.webp",
}


def main() -> int:
    src = Path(sys.argv[1])
    out = Path(__file__).resolve().parents[1] / "android/app/src/main/assets/guide/img"
    out.mkdir(parents=True, exist_ok=True)
    total = 0
    for name, target in MAP.items():
        im = Image.open(src / name).convert("RGB")
        width = 960 if im.width > im.height else 540
        im = im.resize((width, round(im.height * width / im.width)), Image.LANCZOS)
        path = out / target
        im.save(path, "WEBP", quality=78, method=6)
        total += path.stat().st_size
        print(f"{target}: {path.stat().st_size // 1024} КБ")
    print(f"Разом: {total // 1024} КБ")
    return 0


if __name__ == "__main__":
    sys.exit(main())
