"""Інструкція користувача (assets/guide): зібрана з джерела, посилання й картинки на місці."""
import re
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
import build_guide  # noqa: E402

APP = ROOT / "android/app/src/main/assets/guide/index.html"


class GuideTests(unittest.TestCase):
    def setUp(self):
        self.html = APP.read_text(encoding="utf-8")

    def test_built_from_source(self):
        self.assertEqual(build_guide.page(), self.html, "запусти python3 scripts/build_guide.py")

    def test_standards_mode_and_version(self):
        self.assertTrue(self.html.startswith("<!doctype html>"))
        self.assertNotIn("__VERSION__", self.html)
        self.assertIn(f"Track Catcher {build_guide.version()}", self.html)

    def test_every_tile_has_section_and_links_resolve(self):
        ids = set(re.findall(r'id="([^"]+)"', self.html))
        tiles = re.findall(r'<a class="tile[^"]*" href="#([^"]+)"', self.html)
        self.assertGreaterEqual(len(tiles), 16)
        self.assertEqual(len(tiles), len(set(tiles)))
        for t in tiles:
            self.assertIn(t, ids)
            self.assertRegex(self.html, rf'<section id="{t}" class="page"')
        for target in re.findall(r'href="#([A-Za-z][\w-]*)"', self.html):
            self.assertIn(target, ids, target)

    def test_sections_opened_from_app_exist(self):
        kotlin = "\n".join(p.read_text(encoding="utf-8") for p in (ROOT / "android/app/src/main/java").rglob("*.kt"))
        for section in re.findall(r'GuideActivity\.open\(host, "([^"]+)"\)', kotlin):
            self.assertRegex(self.html, rf'<section id="{section}" class="page"')

    def test_images_exist_and_offline(self):
        for src in re.findall(r'src="([^"]+)"', self.html):
            self.assertFalse(src.startswith("http"), src)
            self.assertTrue((APP.parent / src).is_file(), src)
        self.assertNotIn("fonts.googleapis", self.html)
        self.assertNotIn("<script src=", self.html)


if __name__ == "__main__":
    unittest.main()
