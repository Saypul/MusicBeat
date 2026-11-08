package com.musicbeat.app

import com.musicbeat.app.data.AppUpdateChecker
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The phone's updater reads the shared release list and must only ever pick a phone release. */
class AppUpdateCheckerTest {

    private fun release(tag: String, vararg assets: String, draft: Boolean = false, prerelease: Boolean = false) = """
        {"tag_name": "$tag", "draft": $draft, "prerelease": $prerelease,
         "assets": [${assets.joinToString(",") { """{"name": "$it", "state": "uploaded", "browser_download_url": "https://example/$it"}""" }}]}
    """

    private fun list(vararg releases: String): List<JsonObject> =
        Json.parseToJsonElement("[${releases.joinToString(",")}]").jsonArray.map { it.jsonObject }

    private fun tagOf(release: JsonObject?) = release?.get("tag_name")?.jsonPrimitive?.content

    @Test
    fun aNewerWindowsReleaseIsNotAPhoneUpdate() {
        val releases = list(
            release("windows-v1.13.3", "MusicBeat-1.13.3-windows-x64-setup.exe"),
            release("v1.13.2", "MusicBeat-v1.13.2.apk", "MusicBeat-v1.13.2_arm64-v8a.apk"),
        )
        assertEquals("v1.13.2", tagOf(AppUpdateChecker.phoneRelease(releases)))
    }

    @Test
    fun draftsPreReleasesAndApklessReleasesAreSkipped() {
        val releases = list(
            release("v1.15", "MusicBeat-v1.15.apk", draft = true),
            release("v1.14.1", "MusicBeat-v1.14.1.apk", prerelease = true),
            release("v1.14", "MusicBeatTV-v1.14.apk"),
            release("v1.13.2", "MusicBeat-v1.13.2.apk"),
        )
        assertEquals("v1.13.2", tagOf(AppUpdateChecker.phoneRelease(releases)))
        assertNull(AppUpdateChecker.phoneRelease(list(release("windows-v1.13.3", "MusicBeat-1.13.3-windows-x64.msi"))))
    }
}
