"""Збірка й реліз: правила з Renault/Dorama/Visualizer, зведені для Track Catcher."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GRADLE = (ROOT / "android/app/build.gradle.kts").read_text(encoding="utf-8")
ROOT_GRADLE = (ROOT / "android/build.gradle.kts").read_text(encoding="utf-8")
CI = (ROOT / ".github/workflows/android.yml").read_text(encoding="utf-8")
MANIFEST = (ROOT / "android/app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")


class BuildContract(unittest.TestCase):
    def test_versions_match_family(self):
        self.assertIn('"com.android.application") version "8.13.2"', ROOT_GRADLE)
        self.assertIn('"org.jetbrains.kotlin.android") version "2.3.21"', ROOT_GRADLE)
        self.assertIn("compileSdk = 36", GRADLE)
        self.assertIn("targetSdk = 36", GRADLE)

    def test_min_sdk_allows_playback_capture(self):
        # AudioPlaybackCapture з'явився в Android 10 (API 29).
        self.assertIn("minSdk = 29", GRADLE)

    def test_own_package_and_signer(self):
        self.assertIn('applicationId = "ua.faric.trackcatcher"', GRADLE)
        self.assertNotRegex(GRADLE + CI, r"RENAULT_|VISUALIZER_|YTM_|dorama-alpha")

    def test_release_is_not_debuggable_and_not_minified(self):
        block = re.search(r"release \{(.*?)\n        \}", GRADLE, re.S).group(1)
        self.assertIn("isMinifyEnabled = false", block)
        self.assertNotIn("isDebuggable = true", block)

    def test_mp3_without_ndk(self):
        self.assertIn('implementation("de.sciss:jump3r:1.0.5")', GRADLE)
        self.assertNotIn("externalNativeBuild", GRADLE)

    def test_publish_only_from_main_and_only_signed(self):
        self.assertIn("github.ref == 'refs/heads/main' && github.event_name != 'pull_request'", CI)
        self.assertIn("if: env.PUBLISH == 'true' && env.SIGNED == 'true'", CI)
        self.assertIn("--prerelease", CI)
        self.assertIn("уже опубліковано", CI)  # тег не перезаписується
        self.assertIn('"$CERT" != "$TC_EXPECTED_CERT"', CI)
        self.assertIn("retention-days: 14", CI)

    def test_errors_are_annotations(self):
        # Claude не бачить логів Actions — помилки мають виходити анотаціями.
        self.assertIn("::error title=Unit tests::", CI)
        self.assertIn("::error title=Android build::", CI)
        self.assertIn("::notice title=APK_SHA256::", CI)

    def test_manifest_capture_setup(self):
        for perm in ("RECORD_AUDIO", "FOREGROUND_SERVICE_MEDIA_PROJECTION", "POST_NOTIFICATIONS"):
            self.assertIn(f"android.permission.{perm}", MANIFEST)
        self.assertIn('android:foregroundServiceType="mediaProjection|dataSync"', MANIFEST)
        self.assertIn("BIND_NOTIFICATION_LISTENER_SERVICE", MANIFEST)
        self.assertIn('android:allowBackup="false"', MANIFEST)


if __name__ == "__main__":
    unittest.main()
