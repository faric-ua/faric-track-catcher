"""Меню Termux: номери не зсуваються, скрипти існують, оновлення лише fast-forward."""
import os
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MENU = (ROOT / "menu.sh").read_text(encoding="utf-8")
STABLE_ITEMS = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "0"}


def git(cwd, *args):
    return subprocess.run(["git", "-C", str(cwd), *args], capture_output=True, text=True, check=True).stdout.strip()


class MenuContract(unittest.TestCase):
    def test_numbers_are_stable(self):
        shown = set(re.findall(r'echo "\s*(\d+) — ', MENU))
        handled = set(re.findall(r"^\s+(\d+)(?:\s*\|[^)]*)?\)", MENU, re.M))
        self.assertTrue(STABLE_ITEMS <= shown, shown)
        self.assertTrue(STABLE_ITEMS <= handled, handled)

    def test_every_tool_exists(self):
        for name in re.findall(r"(tc-[a-z-]+\.sh)", MENU):
            self.assertTrue((ROOT / "tools/termux" / name).is_file(), name)

    def test_no_destructive_git(self):
        for f in [ROOT / "menu.sh", *(ROOT / "tools/termux").glob("*.sh")]:
            text = f.read_text(encoding="utf-8")
            for bad in ("reset --hard", "git clean", "push --force", "rm -rf"):
                self.assertNotIn(bad, text, f"{f.name}: {bad}")

    def test_widget_launcher_syncs_then_opens_menu(self):
        w = (ROOT / "termux-widget/Track Catcher").read_text(encoding="utf-8")
        self.assertTrue(w.startswith("#!/data/data/com.termux/files/usr/bin/bash"))
        self.assertLess(w.index("tc-sync.sh"), w.index('exec bash "$MENU"'))
        self.assertIn("TC_MENU_SYNCED=1", w)
        self.assertIn('PROJECT="$HOME/faric-track-catcher"', w)
        self.assertIn("tc-widget.sh\" --quiet", MENU)
        self.assertNotIn("rm -", (ROOT / "tools/termux/tc-widget.sh").read_text(encoding="utf-8"))

    def test_shell_returns_to_menu_and_update_restarts(self):
        shell = MENU[MENU.index("    6)"):MENU.index("    7)")]
        self.assertIn("bash -l", shell)
        self.assertIn('exec bash "$REPO/menu.sh"', shell)
        upd = MENU[MENU.index("    1)"):MENU.index("    2)")]
        self.assertLess(upd.index('if [ "$rc" = 0 ]'), upd.index('exec bash "$REPO/menu.sh"'))

    def test_local_build_shows_progress(self):
        b = (ROOT / "tools/termux/tc-local-build.sh").read_text(encoding="utf-8")
        self.assertIn('kill -0 "$pid"', b)
        self.assertIn('wait "$pid"', b)

    def test_second_phone_never_creates_second_key(self):
        setup = (ROOT / "tools/termux/tc-signing-setup.sh").read_text(encoding="utf-8")
        guard = setup.index("gh secret list")
        create = setup.index("keytool -genkeypair")
        self.assertLess(guard, create, "перевірка «ключ уже в GitHub» має бути до створення ключа")

    def test_phone_build_goes_to_separate_release(self):
        pub = (ROOT / "tools/termux/tc-publish-phone-apk.sh").read_text(encoding="utf-8")
        inst = (ROOT / "tools/termux/tc-install-latest.sh").read_text(encoding="utf-8")
        self.assertIn('TAG="phone-build"', pub)
        self.assertIn("git diff --quiet HEAD -- android", pub)  # опис = код у Git
        self.assertNotIn("--force", pub)
        self.assertIn("grep -v '^phone-build$'", inst)  # пункт 2 — лише офіційні релізи
        build = (ROOT / "tools/termux/tc-local-build.sh").read_text(encoding="utf-8")
        self.assertIn("tc-publish-phone-apk.sh", build)

    def test_phone_and_ci_use_same_gradle_and_signing_names(self):
        build = (ROOT / "tools/termux/tc-local-build.sh").read_text(encoding="utf-8")
        ci = (ROOT / ".github/workflows/android.yml").read_text(encoding="utf-8")
        gradle = (ROOT / "android/app/build.gradle.kts").read_text(encoding="utf-8")
        setup = (ROOT / "tools/termux/tc-signing-setup.sh").read_text(encoding="utf-8")
        self.assertIn("gradle-8.13", build)
        self.assertIn('gradle-version: "8.13"', ci)
        for env in ("TC_KEYSTORE_PATH", "TC_STORE_PASSWORD", "TC_KEY_ALIAS", "TC_KEY_PASSWORD"):
            self.assertIn(env, gradle)
            self.assertIn(env, ci)
        for prop in ("storeFile", "storePassword", "keyAlias", "keyPassword"):
            self.assertIn(f'"{prop}"', gradle)
            self.assertIn(f"{prop}=", setup)
        for secret in ("TC_KEYSTORE_B64", "TC_STORE_PASSWORD", "TC_KEY_ALIAS", "TC_KEY_PASSWORD", "TC_CERT_SHA256"):
            self.assertIn(secret, setup)
            self.assertIn(f"secrets.{secret}", ci)


@unittest.skipUnless(shutil.which("git") and shutil.which("bash"), "потрібні git і bash")
class SyncBehaviour(unittest.TestCase):
    """tc-sync.sh на справжніх тимчасових репо."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)
        self.origin = self.tmp / "origin.git"
        subprocess.run(["git", "init", "-q", "--bare", "-b", "main", str(self.origin)], check=True)
        self.work = self.tmp / "work"
        subprocess.run(["git", "clone", "-q", str(self.origin), str(self.work)], check=True, capture_output=True)
        for k, v in (("user.name", "t"), ("user.email", "t@example.com")):
            git(self.work, "config", k, v)
        git(self.work, "checkout", "-q", "-b", "main")
        (self.work / "tools/termux").mkdir(parents=True)
        for f in ("tc-common.sh", "tc-sync.sh"):
            shutil.copy(ROOT / "tools/termux" / f, self.work / "tools/termux" / f)
        (self.work / "a.txt").write_text("1")
        git(self.work, "add", "-A")
        git(self.work, "commit", "-qm", "init")
        git(self.work, "push", "-q", "origin", "main")
        self.phone = self.tmp / "phone"
        subprocess.run(["git", "clone", "-q", "-b", "main", str(self.origin), str(self.phone)], check=True, capture_output=True)

    def sync(self):
        env = dict(os.environ, TC_REPO=str(self.phone), HOME=str(self.tmp))
        return subprocess.run(["bash", str(self.phone / "tools/termux/tc-sync.sh")], cwd=self.phone, env=env, capture_output=True, text=True)

    def push_change(self, text="2"):
        (self.work / "a.txt").write_text(text)
        git(self.work, "commit", "-qam", "change")
        git(self.work, "push", "-q", "origin", "main")

    def test_up_to_date(self):
        self.assertEqual(10, self.sync().returncode)

    def test_fast_forward(self):
        self.push_change()
        r = self.sync()
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertEqual("2", (self.phone / "a.txt").read_text())

    def test_dirty_is_not_touched(self):
        self.push_change()
        (self.phone / "a.txt").write_text("локальна правка")
        self.assertEqual(4, self.sync().returncode)
        self.assertEqual("локальна правка", (self.phone / "a.txt").read_text())

    def test_wrong_branch(self):
        git(self.phone, "checkout", "-q", "-b", "other")
        self.assertEqual(3, self.sync().returncode)

    def test_diverged_is_not_reset(self):
        self.push_change()
        for k, v in (("user.name", "t"), ("user.email", "t@example.com")):
            git(self.phone, "config", k, v)
        (self.phone / "b.txt").write_text("телефон")
        git(self.phone, "add", "-A")
        git(self.phone, "commit", "-qm", "phone")
        before = git(self.phone, "rev-parse", "HEAD")
        self.assertEqual(7, self.sync().returncode)
        self.assertEqual(before, git(self.phone, "rev-parse", "HEAD"))


if __name__ == "__main__":
    unittest.main()
