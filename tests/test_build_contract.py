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

    def test_start_never_stops_own_service(self):
        """Регрес v0.1.0–0.1.3: recoverOrphans() на старті робив stopSelf() — служба запису закривалась."""
        svc = (ROOT / "android/app/src/main/java/ua/faric/trackcatcher/capture/CaptureService.kt").read_text(encoding="utf-8")
        start = svc[svc.index("private fun startRecording"):svc.index("private fun failStart")]
        self.assertIn("recoverOrphans(stopWhenNothing = false)", start)
        self.assertNotIn("stopSelf()", start.replace("failStart", ""))
        rec = svc[svc.index("private fun recoverOrphans"):]
        self.assertIn("if (stopWhenNothing &&", rec)
        destroy = svc[svc.index("override fun onDestroy"):svc.index("override fun onDestroy") + 900]
        self.assertIn("Phase.IDLE", destroy)

    def test_guide_served_via_asset_loader(self):
        g = (ROOT / "android/app/src/main/java/ua/faric/trackcatcher/ui/GuideActivity.kt").read_text(encoding="utf-8")
        self.assertIn("WebViewAssetLoader", g)
        self.assertNotIn('"file:///android_asset', g)  # з file:// картинки не вантажились
        html = (ROOT / "android/app/src/main/assets/guide/index.html").read_text(encoding="utf-8")
        import re
        for src in re.findall(r'src="(img/[^"]+)"', html):
            self.assertTrue((ROOT / "android/app/src/main/assets/guide" / src).is_file(), src)

    def test_manifest_capture_setup(self):
        for perm in ("RECORD_AUDIO", "FOREGROUND_SERVICE_MEDIA_PROJECTION", "POST_NOTIFICATIONS"):
            self.assertIn(f"android.permission.{perm}", MANIFEST)
        self.assertIn('android:foregroundServiceType="mediaProjection|dataSync"', MANIFEST)
        self.assertIn("BIND_NOTIFICATION_LISTENER_SERVICE", MANIFEST)
        self.assertIn('android:allowBackup="false"', MANIFEST)

    def test_playback_notification_setup(self):
        """v0.1.18: шторка під час прослуховування — служба mediaPlayback з дозволом; свій MediaSession не пишемо."""
        self.assertIn("android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK", MANIFEST)
        self.assertRegex(MANIFEST, r'\.playback\.PlaybackService"\s+android:exported="false"\s+android:foregroundServiceType="mediaPlayback"')
        tracker = (ROOT / "android/app/src/main/java/ua/faric/trackcatcher/capture/NowPlayingTracker.kt").read_text(encoding="utf-8")
        self.assertIn("it.packageName != own", tracker)
        player = (ROOT / "android/app/src/main/java/ua/faric/trackcatcher/ui/InAppPlayer.kt").read_text(encoding="utf-8")
        self.assertIn("ALLOW_CAPTURE_BY_NONE", player)

    def test_drive_update_setup(self):
        # Оновлення з Google Диска (faric 14.10 20:22): інтернет, встановлення, FileProvider лише для cache/updates.
        for perm in ("INTERNET", "REQUEST_INSTALL_PACKAGES"):
            self.assertIn(f"android.permission.{perm}", MANIFEST)
        self.assertIn('android:authorities="${applicationId}.updates"', MANIFEST)
        paths = (ROOT / "android/app/src/main/res/xml/update_paths.xml").read_text(encoding="utf-8")
        self.assertIn('<cache-path name="updates" path="updates/" />', paths)
        ui = (ROOT / "android/app/src/main/java/ua/faric/trackcatcher/ui/UpdateUi.kt").read_text(encoding="utf-8")
        self.assertIn('packageName + ".updates"', ui)
        self.assertIn("checkDownloaded", ui)  # APK перевіряється до вікна встановлення


if __name__ == "__main__":
    unittest.main()


class BuiltInUpdateLinkTest(unittest.TestCase):
    """T45: вбудоване посилання на файл версії — справжній ID файлу Google Диска."""

    def test_built_in_manifest_id(self):
        import re
        src = (ROOT / "android/app/src/main/java/ua/faric/trackcatcher/ui/UpdateUi.kt").read_text(encoding="utf-8")
        m = re.search(r'const val BUILT_IN_MANIFEST_ID = "([^"]*)"', src)
        self.assertIsNotNone(m)
        self.assertRegex(m.group(1), r"^[A-Za-z0-9_-]{25,}$")
