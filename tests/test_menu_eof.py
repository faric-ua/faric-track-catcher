"""Меню не крутиться по колу, коли Termux закрили не пунктом 0 (GitHub Storage 2026-10-30).

Звіт телефона 30.10: bash menu.sh набрав 2+ години процесора у фоні — read без екрана
повертав помилку, і меню малювало себе ~50 разів на секунду. Тепер — вихід.
"""
import os
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class MenuEofTest(unittest.TestCase):
    def test_menu_exits_when_input_closed(self):
        env = dict(os.environ, TC_MENU_SYNCED="1", TERM="dumb", HOME="/tmp/tc-eof-home")
        r = subprocess.run(["bash", str(ROOT / "menu.sh")], stdin=subprocess.DEVNULL,
                           capture_output=True, text=True, env=env, timeout=20)
        self.assertEqual(r.returncode, 0)
        self.assertEqual(r.stdout.count("0 — Вийти"), 1)


if __name__ == "__main__":
    unittest.main()
