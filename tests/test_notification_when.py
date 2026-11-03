"""T114: повідомлення Track Catcher не стрибають у шторці.

Кожен новий Notification.Builder бере час «зараз»; часто оновлюване повідомлення шторка пересортовує серед інших.
Тому в кожній функції notification(...) — .setWhen(notifWhen) і .setShowWhen(false), а сам час присвоюється
один раз на процес (запис / робота з файлами / відтворення). Як у Audio Converter (tests/test_notification_when.py).
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "android/app/src/main/java/ua/faric/trackcatcher"
SERVICES = ["capture/CaptureService.kt", "capture/FlacBatchService.kt", "playback/PlaybackService.kt"]


def function_body(text: str, start: int) -> str:
    i = text.index("{", start)
    depth = 0
    for j in range(i, len(text)):
        if text[j] == "{":
            depth += 1
        elif text[j] == "}":
            depth -= 1
            if depth == 0:
                return text[i:j + 1]
    raise AssertionError("незакрита функція")


class NotificationWhenTest(unittest.TestCase):
    def test_every_builder_has_fixed_when(self):
        for rel in SERVICES:
            text = (SRC / rel).read_text(encoding="utf-8")
            m = re.search(r"private fun notification\(", text)
            self.assertIsNotNone(m, f"{rel}: нема notification(...)")
            body = function_body(text, m.end())
            self.assertIn(".setWhen(notifWhen)", body, rel)
            self.assertIn(".setShowWhen(false)", body, rel)
            self.assertIn("if (notifWhen == 0L) notifWhen = System.currentTimeMillis()", body, rel)

    def test_time_set_once_per_process(self):
        for rel in SERVICES:
            text = (SRC / rel).read_text(encoding="utf-8")
            self.assertEqual(len(re.findall(r"notifWhen\s*=\s*System\.", text)), 1, f"{rel}: час присвоюється не один раз")

    def test_no_other_builders(self):
        for path in SRC.rglob("*.kt"):
            rel = path.relative_to(SRC).as_posix()
            if rel in SERVICES:
                continue
            self.assertNotIn("Notification.Builder(", path.read_text(encoding="utf-8"), f"{rel}: новий Builder — теж з notifWhen")


if __name__ == "__main__":
    unittest.main()
