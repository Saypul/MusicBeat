package com.musicbeat.app.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopUpdateCheckerTest {

    private val sums = """
        312ec7f45152090541ae350843b6df85275c66d63a82b7a868e56258692bbf90  MusicBeat-1.13.1-windows-x64.msi
        91aa1376a41f8947609111421c0fbded36a7e65fea2afb5e067a39cce7fbd01c  MusicBeat-1.13.1-windows-x64-portable.zip
        82e721a7660f7e55d9d2f2f3fa05e9f962c48242eba456c2bfd140191e7b5f0e *MusicBeat-1.13.1-windows-x64-setup.exe
    """.trimIndent()

    @Test
    fun `the installer's digest is read from sha256sum output`() {
        assertEquals(
            "82e721a7660f7e55d9d2f2f3fa05e9f962c48242eba456c2bfd140191e7b5f0e",
            DesktopUpdateChecker.expectedDigest(sums, "MusicBeat-1.13.1-windows-x64-setup.exe"),
        )
        // A name that only starts the same way is not the file.
        assertNull(DesktopUpdateChecker.expectedDigest(sums, "MusicBeat-1.13.1-windows-x64"))
        assertNull(DesktopUpdateChecker.expectedDigest("not-a-digest  MusicBeat-1.13.1-windows-x64.msi", "MusicBeat-1.13.1-windows-x64.msi"))
    }

    @Test
    fun `versions compare numerically`() {
        assertTrue(DesktopUpdateChecker.isNewer("1.13.2", "1.13.1"))
        assertTrue(DesktopUpdateChecker.isNewer("1.14", "1.13.1"))
        assertFalse(DesktopUpdateChecker.isNewer("1.13.1", "1.13.1"))
        assertFalse(DesktopUpdateChecker.isNewer("1.9", "1.13.1"))
    }

    // Installer names for every desktop platform, so the pick does not depend on the test host.
    private fun release(tag: String, installer: Boolean = true, prerelease: Boolean = false): String {
        val assets = if (installer) {
            listOf("MusicBeat-1-windows-x64-setup.exe", "MusicBeat-1-linux-x86_64.AppImage")
        } else {
            listOf("MusicBeat-v1.apk")
        }
        return """{"tag_name": "$tag", "draft": false, "prerelease": $prerelease, "assets": [${
            assets.joinToString(",") { """{"name": "$it", "state": "uploaded", "browser_download_url": "https://example/$it"}""" }
        }]}"""
    }

    private fun list(vararg releases: String): List<JsonObject> =
        Json.parseToJsonElement("[${releases.joinToString(",")}]").jsonArray.map { it.jsonObject }

    @Test
    fun `a phone release is never a desktop update`() {
        val picked = DesktopUpdateChecker.desktopRelease(
            list(
                release("v1.14", installer = false),
                release("windows-v1.14", prerelease = true),
                release("windows-v1.13.3"),
                release("windows-v1.13.2"),
            ),
        )
        assertEquals("windows-v1.13.3", picked?.get("tag_name")?.jsonPrimitive?.content)
        assertNull(DesktopUpdateChecker.desktopRelease(list(release("v1.14", installer = false))))
    }
}
