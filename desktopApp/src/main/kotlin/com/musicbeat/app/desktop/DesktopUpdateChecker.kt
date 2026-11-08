package com.musicbeat.app.desktop

import com.musicbeat.app.data.MUSICBEAT_REPO_URL
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import kotlin.system.exitProcess
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Looks at the repo's releases on GitHub once per launch and says whether there is a newer build
 * *for this platform*. The phone and the desktop publish separately in the same repository: the
 * phone under `v1.x` tags, which are the ones GitHub marks "latest", and the desktop under
 * `windows-v1.x`, so neither updater offers the other's release.
 *
 * On Windows the installer is fetched in-app, checked against the release's
 * `SHA256SUMS-windows-<version>` file, and run once the user says so — the app quits first so the
 * installer can replace its files, and opens again when the installer is done. Elsewhere the
 * dialog opens the asset in the browser.
 *
 * GitHub's "latest" skips pre-releases and drafts, so a `-beta` desktop build is only ever
 * nudged toward a published release.
 */
internal object DesktopUpdateChecker {

    data class UpdateInfo(
        val version: String,
        val releaseUrl: String,
        /** The installer for this platform. */
        val downloadUrl: String,
        val fileName: String,
        /** The release's checksum list for this platform, when it has one. */
        val checksumsUrl: String?,
        val notes: String?,
    )

    /** Where an in-app download stands, for the dialog. */
    sealed interface DownloadState {
        data object Idle : DownloadState
        /** [progress] is null while the size is unknown. */
        data class Downloading(val progress: Float?) : DownloadState
        data class Ready(val installer: Path) : DownloadState
        data class Failed(val reason: String) : DownloadState
    }

    private val RELEASES_URL =
        MUSICBEAT_REPO_URL.replace("https://github.com/", "https://api.github.com/repos/") + "/releases?per_page=30"

    /** Tags the desktop releases under; the phone's `v` tags are never read here. */
    internal const val TAG_PREFIX = "windows-v"

    val currentVersion: String = System.getProperty("musicbeat.version") ?: "1.8-beta1"

    /** Whether this platform installs in-app rather than through the browser. */
    val installsInApp: Boolean get() = DesktopPlatform.isWindows

    private val json = Json { ignoreUnknownKeys = true }

    private val http = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 10_000
        }
    }

    /** The installer download itself: long, and redirected to GitHub's object store. */
    private val downloads = java.net.http.HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
        .build()

    private val _download = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val download: StateFlow<DownloadState> = _download.asStateFlow()

    suspend fun check(): UpdateInfo? = runCatching {
        val response = http.get(RELEASES_URL) {
            header("Accept", "application/vnd.github+json")
        }
        if (!response.status.isSuccess()) return@runCatching null
        val releases = (json.parseToJsonElement(response.bodyAsText()) as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?: return@runCatching null
        val release = desktopRelease(releases) ?: return@runCatching null
        val tag = release["tag_name"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
        val url = release["html_url"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
        val latest = tag.removePrefix(TAG_PREFIX)
        if (!isNewer(latest, currentVersion)) return@runCatching null
        val installer = installerAsset(release) ?: return@runCatching null
        UpdateInfo(
            version = latest,
            releaseUrl = url,
            downloadUrl = installer.second,
            fileName = installer.first,
            checksumsUrl = checksumsUrl(release),
            notes = release["body"]?.jsonPrimitive?.contentOrNull,
        )
    }.getOrNull()

    /**
     * The newest published desktop release with an installer for this platform. GitHub lists
     * releases newest first.
     */
    internal fun desktopRelease(releases: List<JsonObject>): JsonObject? = releases.firstOrNull { release ->
        release["tag_name"]?.jsonPrimitive?.contentOrNull.orEmpty().startsWith(TAG_PREFIX) &&
            release["draft"]?.jsonPrimitive?.booleanOrNull != true &&
            release["prerelease"]?.jsonPrimitive?.booleanOrNull != true &&
            installerAsset(release) != null
    }

    /**
     * The release names its files `MusicBeat-<version>-windows-x64-setup.exe` and
     * `MusicBeat-<version>-linux-x86_64.AppImage`; match on the platform part so a version change
     * does not matter.
     */
    private fun installerAsset(release: JsonObject): Pair<String, String>? {
        val suffixes = when {
            DesktopPlatform.isWindows -> listOf("-windows-x64-setup.exe")
            DesktopPlatform.isLinux -> listOf(".AppImage", "-linux-amd64.deb")
            else -> return null
        }
        for (suffix in suffixes) {
            uploadedAssets(release).firstOrNull { (name, _) -> name.endsWith(suffix, ignoreCase = true) }
                ?.let { return it }
        }
        return null
    }

    private fun checksumsUrl(release: JsonObject): String? = when {
        DesktopPlatform.isWindows -> uploadedAssets(release)
            .firstOrNull { (name, _) -> name.startsWith("SHA256SUMS-windows", ignoreCase = true) }
            ?.second
        else -> null
    }

    private fun uploadedAssets(release: JsonObject): List<Pair<String, String>> =
        release["assets"]?.jsonArray?.mapNotNull { it as? JsonObject }.orEmpty().mapNotNull { asset ->
            if (asset["state"]?.jsonPrimitive?.contentOrNull != "uploaded") return@mapNotNull null
            val name = asset["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val link = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            name to link
        }

    /**
     * Fetches [update]'s installer and checks it against the release's checksum list. A release
     * without one, or a file that does not match it, is refused: this file is about to be run.
     */
    suspend fun downloadInstaller(update: UpdateInfo): Unit = withContext(Dispatchers.IO) {
        _download.value = DownloadState.Downloading(null)
        _download.value = runCatching {
            val checksumsUrl = update.checksumsUrl ?: error("this release has no checksum list")
            val expected = expectedDigest(fetchText(checksumsUrl), update.fileName)
                ?: error("the checksum list does not name ${update.fileName}")
            val directory = updatesDirectory()
            Files.createDirectories(directory)
            Files.list(directory).use { old -> old.forEach { runCatching { Files.delete(it) } } }
            val target = directory.resolve(update.fileName)
            val digest = MessageDigest.getInstance("SHA-256")
            val request = HttpRequest.newBuilder(URI.create(update.downloadUrl))
                .header("User-Agent", "MusicBeat/$currentVersion")
                .GET()
                .build()
            val response = downloads.send(request, HttpResponse.BodyHandlers.ofInputStream())
            check(response.statusCode() in 200..299) { "download failed: HTTP ${response.statusCode()}" }
            val total = response.headers().firstValueAsLong("content-length").orElse(-1L)
            response.body().use { input ->
                Files.newOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        read += n
                        if (total > 0) _download.value = DownloadState.Downloading((read.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(expected, ignoreCase = true)) {
                runCatching { Files.delete(target) }
                error("the download does not match its published checksum")
            }
            DownloadState.Ready(target)
        }.getOrElse { failure ->
            DesktopTrackLog.log("update download failed: ${failure.message}")
            DownloadState.Failed(failure.message ?: "download failed")
        }
    }

    /**
     * Runs the installer and quits. A hidden PowerShell waits for the installer and then starts
     * this app again from the launcher it was started from, so the update ends where it began.
     */
    fun installAndQuit(installer: Path) {
        val relaunch = System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() }
        val script = buildString {
            append("Start-Process -Wait -FilePath ${psQuote(installer.toString())}")
            if (relaunch != null) append("; Start-Process -FilePath ${psQuote(relaunch)}")
        }
        ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", script)
            .start()
        DesktopTrayMenu.quitApp()
        // The tray was never bound: nothing native is running yet.
        exitProcess(0)
    }

    fun resetDownload() {
        _download.value = DownloadState.Idle
    }

    /** The hex digest [fileName] is listed with in a `sha256sum` output. */
    internal fun expectedDigest(checksums: String, fileName: String): String? =
        checksums.lineSequence()
            .map { it.trim().split(Regex("\\s+"), limit = 2) }
            .firstOrNull { it.size == 2 && it[1].removePrefix("*") == fileName }
            ?.get(0)
            ?.takeIf { it.length == 64 && it.all { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' } }

    private fun psQuote(value: String) = "'" + value.replace("'", "''") + "'"

    private fun updatesDirectory(): Path {
        val base = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
            ?: System.getProperty("java.io.tmpdir")
        return Path.of(base, "MusicBeat", "updates")
    }

    private fun fetchText(url: String): String {
        val request = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", "MusicBeat/$currentVersion")
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build()
        val response = downloads.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) { "checksum list: HTTP ${response.statusCode()}" }
        return response.body()
    }

    private class Parsed(val parts: List<Int>, val preRelease: Boolean)

    private fun parse(raw: String): Parsed {
        val dash = raw.indexOf('-')
        val base = if (dash >= 0) raw.substring(0, dash) else raw
        return Parsed(base.split('.').map { it.toIntOrNull() ?: 0 }, dash >= 0)
    }

    /** Numeric comparison, with a `-betaN` build counted as older than the plain release it leads up to. */
    internal fun isNewer(latest: String, current: String): Boolean {
        val l = parse(latest)
        val c = parse(current)
        for (i in 0 until maxOf(l.parts.size, c.parts.size)) {
            val a = l.parts.getOrElse(i) { 0 }
            val b = c.parts.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return c.preRelease && !l.preRelease
    }
}
