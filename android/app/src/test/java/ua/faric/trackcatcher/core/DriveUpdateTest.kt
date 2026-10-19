package ua.faric.trackcatcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Оновлення з Google Диска (faric 14.10 20:22, як Dorama T88): посилання, файл версії, перевірки APK. */
class DriveUpdateTest {
    private val id = "1AbCdEfGhIjKlMnOpQrStUvWxYz012345"
    private val sha = "a".repeat(64)
    private val pkg = "ua.faric.trackcatcher"

    @Test
    fun linksFromDriveApp() {
        assertEquals(id, DriveUpdate.fileIdFromLink("https://drive.google.com/file/d/$id/view?usp=sharing"))
        assertEquals(id, DriveUpdate.fileIdFromLink("  https://drive.google.com/open?id=$id  "))
        assertEquals(id, DriveUpdate.fileIdFromLink("https://drive.google.com/uc?id=$id&export=download"))
        assertEquals(id, DriveUpdate.fileIdFromLink(id))
    }

    @Test
    fun notFileLinks() {
        assertNull(DriveUpdate.fileIdFromLink("https://drive.google.com/drive/folders/$id"))
        assertNull(DriveUpdate.fileIdFromLink("https://example.com/file/d/$id/view"))
        assertNull(DriveUpdate.fileIdFromLink("привіт"))
        assertNull(DriveUpdate.fileIdFromLink(""))
        assertNull(DriveUpdate.fileIdFromLink(null))
    }

    @Test
    fun manifestRoundTrip() {
        val m = DriveUpdate.Manifest(pkg, 16, "0.1.15", sha, 7_400_000, id, 1_760_000_000_000)
        val json = m.toJson()
        assertTrue(json.contains("\"format\": \"track-catcher-update/1\""))
        assertTrue(json.contains("\"apkLink\": \"https://drive.google.com/file/d/$id/view\""))
        assertEquals(m, DriveUpdate.Manifest.parse(json))
    }

    @Test
    fun manifestWithEscapesAndExtraFields() {
        val text = "{\"format\":\"track-catcher-update/1\",\"package\":\"$pkg\",\"versionCode\":16," +
            "\"versionName\":\"0.1.15 \\\"тест\\\" \\u0041\",\"apkSha256\":\"${sha.uppercase()}\",\"apkSize\":10," +
            "\"apkId\":\"$id\",\"note\":null,\"beta\":false}"
        val m = DriveUpdate.Manifest.parse(text)
        assertEquals("0.1.15 \"тест\" A", m.versionName)
        assertEquals(sha, m.apkSha256)
        assertEquals(0L, m.publishedAt)
    }

    @Test
    fun placeholderSaysNotPublishedYet() {
        assertMessage(DriveUpdate.PLACEHOLDER, "ще не опублікував")
    }

    @Test
    fun badManifests() {
        assertMessage("<!doctype html><html>", "не файл версії")
        assertMessage("{\"format\":\"dorama-update/1\"}", "інший формат")
        assertMessage("{\"format\":\"track-catcher-update/1\",\"package\":\"$pkg\"}", "неповний")
        assertMessage("{\"format\":\"track-catcher-update/1\",\"a\":{\"b\":1}}", "не файл версії")
        assertMessage("{\"format\":\"track-catcher-update/1\"} хвіст", "не файл версії")
    }

    /** T46: не той файл — посилання забувається; «ще не опубліковано» / «неповний» — ні (це справа автора). */
    @Test
    fun wrongFileIsSeparate() {
        assertTrue(isWrongFile("<!doctype html><html>"))
        assertTrue(isWrongFile("{\"format\":\"dorama-update/1\"}"))
        assertTrue(isWrongFile("PK\u0003\u0004бінарне"))
        assertFalse(isWrongFile(DriveUpdate.PLACEHOLDER))
        assertFalse(isWrongFile("{\"format\":\"track-catcher-update/1\",\"package\":\"$pkg\"}"))
    }

    /** T46 (14.10 22:19): faric вставив посилання на APK → «Файл на Диску завеликий (6,1 МБ)» без пояснення. */
    @Test
    fun apkLinkMessageExplains() {
        val text = DriveUpdate.linkIsApkMessage(6_420_826)
        assertTrue(text, text.contains("APK, 6,1 МБ") || text.contains("APK, 6.1 МБ"))
        assertTrue(text.contains(DriveUpdate.VERSION_FILE_NAME))
        assertTrue(text.contains("Поділитися посиланням на оновлення"))
        assertFalse(DriveUpdate.linkIsApkMessage(-1).contains("МБ)"))
    }

    @Test
    fun candidatesOrder() {
        val built = "B".repeat(33)
        assertEquals(listOf(id), DriveUpdate.manifestCandidates(id, "S".repeat(33), built))
        assertEquals(listOf("S".repeat(33), built), DriveUpdate.manifestCandidates(null, "S".repeat(33), built))
        assertEquals(listOf(built), DriveUpdate.manifestCandidates(null, built, built))
        assertEquals(listOf(built), DriveUpdate.manifestCandidates(" ", "", built))
        assertEquals(emptyList<String>(), DriveUpdate.manifestCandidates(null, null, ""))
    }

    @Test
    fun newestWinsOverSavedOldFile() {
        // T56: збережене посилання на старий файл (0.1.15) не має перекривати вбудований новий (0.1.21).
        fun m(code: Long) = DriveUpdate.Manifest("ua.faric.trackcatcher", code, "0.1.$code", "a".repeat(64), 1, "X".repeat(33), 0)
        assertEquals("built", DriveUpdate.newest(listOf("saved" to m(16), "built" to m(22)))!!.first)
        assertEquals("saved", DriveUpdate.newest(listOf("saved" to m(22), "built" to m(22)))!!.first)
        assertEquals("saved", DriveUpdate.newest(listOf("saved" to m(23), "built" to m(22)))!!.first)
        assertEquals(null, DriveUpdate.newest(emptyList()))
    }

    private fun isWrongFile(text: String): Boolean = try {
        DriveUpdate.Manifest.parse(text)
        fail("мало не прочитатись: $text")
        false
    } catch (e: DriveUpdate.WrongFileException) {
        true
    } catch (e: IllegalArgumentException) {
        false
    }

    @Test
    fun verdicts() {
        val m = DriveUpdate.Manifest(pkg, 16, "0.1.15", sha, 10, id, 0)
        assertEquals(DriveUpdate.Verdict.NEWER, DriveUpdate.compare(m, pkg, 15))
        assertEquals(DriveUpdate.Verdict.SAME, DriveUpdate.compare(m, pkg, 16))
        assertEquals(DriveUpdate.Verdict.OLDER, DriveUpdate.compare(m, pkg, 17))
        assertEquals(DriveUpdate.Verdict.OTHER_PACKAGE, DriveUpdate.compare(m, "$pkg.dev", 1))
    }

    @Test
    fun downloadedChecks() {
        val m = DriveUpdate.Manifest(pkg, 16, "0.1.15", sha, 10, id, 0)
        val cert = "c".repeat(64)
        assertNull(DriveUpdate.checkDownloaded(m, sha, 10, pkg, 16, cert, cert))
        assertNull(DriveUpdate.checkDownloaded(m, sha.uppercase(), 10, pkg, 16, null, cert)) // ключ не прочитався — вирішить Android
        assertNotNull(DriveUpdate.checkDownloaded(m, sha, 11, pkg, 16, cert, cert))
        assertNotNull(DriveUpdate.checkDownloaded(m, "b".repeat(64), 10, pkg, 16, cert, cert))
        assertNotNull(DriveUpdate.checkDownloaded(m, sha, 10, null, 16, cert, cert))
        assertNotNull(DriveUpdate.checkDownloaded(m, sha, 10, "com.other", 16, cert, cert))
        assertNotNull(DriveUpdate.checkDownloaded(m, sha, 10, pkg, 15, cert, cert))
        assertTrue(DriveUpdate.checkDownloaded(m, sha, 10, pkg, 16, "d".repeat(64), cert)!!.contains("іншим ключем"))
    }

    @Test
    fun responseSniffing() {
        val html = "  \n<!DOCTYPE html><html>".toByteArray()
        assertTrue(DriveUpdate.looksLikeHtml(null, html, html.size))
        assertTrue(DriveUpdate.looksLikeHtml("text/html; charset=utf-8", ByteArray(0), 0))
        val zip = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4)
        assertFalse(DriveUpdate.looksLikeHtml("application/octet-stream", zip, zip.size))
        assertTrue(DriveUpdate.looksLikeApk(zip, zip.size))
        assertFalse(DriveUpdate.looksLikeApk(html, html.size))
    }

    @Test
    fun shaAndUrls() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            DriveUpdate.sha256Hex(DriveUpdate.newSha256().digest(ByteArray(0))),
        )
        assertEquals("https://drive.usercontent.google.com/download?id=$id&export=download&confirm=t", DriveUpdate.downloadUrl(id))
        assertEquals("7,1 МБ", DriveUpdate.formatMb(7_444_889))
    }

    private fun assertMessage(text: String, part: String) {
        try {
            DriveUpdate.Manifest.parse(text)
            fail("мало не прочитатись: $text")
        } catch (e: IllegalArgumentException) {
            assertTrue("«${e.message}» має містити «$part»", e.message!!.contains(part))
        }
    }

    /** faric 20.10 00:59: «Google Диск відповів кодом 500» — такі коди пробуємо ще раз. */
    @Test fun temporaryGoogleErrorsAreRetried() {
        assertEquals(true, DriveUpdate.retryable(500))
        assertEquals(true, DriveUpdate.retryable(503))
        assertEquals(true, DriveUpdate.retryable(429))
        assertEquals(false, DriveUpdate.retryable(404))
        assertEquals(false, DriveUpdate.retryable(200))
    }
}
