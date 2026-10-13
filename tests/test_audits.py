"""Аудити мають проходити на репо і ловити порушення на зіпсованій копії."""
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

import audit_repo  # noqa: E402
import audit_ui  # noqa: E402


class AuditTests(unittest.TestCase):
    def test_ui_audit_passes(self):
        self.assertEqual([], audit_ui.audit(ROOT))

    def test_repo_audit_passes(self):
        self.assertEqual([], audit_repo.audit(ROOT))

    def _copy(self) -> Path:
        tmp = Path(tempfile.mkdtemp())
        shutil.copytree(ROOT / "android", tmp / "android", ignore=shutil.ignore_patterns("build", ".gradle"))
        shutil.copy(ROOT / ".gitignore", tmp / ".gitignore")
        self.addCleanup(shutil.rmtree, tmp)
        return tmp

    def test_ui_audit_catches_stock_dialog_and_checkmark(self):
        tmp = self._copy()
        f = tmp / "android/app/src/main/java/ua/faric/trackcatcher/ui/Bad.kt"
        f.write_text('package x\nval a = android.app.AlertDialog.Builder(null)\nval b = "✓ вибрано"\nval c = 0xFF123456.toInt()\n', encoding="utf-8")
        rules = {r for r, _ in audit_ui.audit(tmp)}
        self.assertTrue({"UI-01", "UI-02", "UI-03"} <= rules, rules)

    def test_ui_audit_catches_missing_landscape(self):
        tmp = self._copy()
        f = tmp / "android/app/src/main/java/ua/faric/trackcatcher/ui/PlainScreen.kt"
        f.write_text("package x\nclass PlainScreen\n", encoding="utf-8")
        rules = {r for r, _ in audit_ui.audit(tmp)}
        self.assertIn("UI-05", rules)

    def test_repo_audit_catches_key_and_email(self):
        tmp = self._copy()
        (tmp / "release.jks").write_bytes(b"x")
        # Зразки складаються з частин, щоб у самому репо не було «пошти» й «пароля».
        email = "someone.private" + "@" + "gmail.com"
        secret = "store" + "Password=" + "abcdefgh1234"
        (tmp / "notes.md").write_text(f"пиши на {email}\n{secret}\n", encoding="utf-8")
        rules = [r for r, _ in audit_repo.audit(tmp)]
        self.assertIn("REPO-01", rules)
        self.assertGreaterEqual(rules.count("REPO-02"), 2)


if __name__ == "__main__":
    unittest.main()
