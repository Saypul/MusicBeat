package com.musicbeat.app.data.sources

import com.musicbeat.app.BuildConfig
import com.musicbeat.app.data.MUSICBEAT_REPO_URL
import com.musicbeat.app.data.TrackLog
import com.musicbeat.app.data.model.Song
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.net.URLEncoder
import java.util.Locale

private const val TAG = "MusicBeat"

/**
 * archive.org's audio collections: live concerts taped with the bands'
 * blessing (the etree collection), public-domain recordings and netlabel
 * releases, many of them as FLAC and some at 24-bit.
 *
 * Unlike every other source, this one is never asked to stand in for a track
 * from somewhere else — see [SourceKind.matchesOtherCatalogues]. What it holds
 * is mostly *other recordings* of songs YouTube also has, a 1977 concert tape
 * under the studio single's title, and a match made on title and runtime
 * would swap one for the other without saying so. Its rows are reached by
 * searching for them, and play as themselves.
 *
 * Searched from the Addons tab of Search rather than the main one, because a
 * search is two steps: the archive indexes items (a concert, an album) and not
 * the files in them, so it is the items that match and hold FLAC, then each
 * item's file list, read in parallel. A track id is
 * `identifier/filename`, which is also its download path.
 */
class InternetArchiveSource(
    override val config: SourceConfig,
) : MusicSource, SourceRegistry.ConfigBacked {

    override val configId: String get() = config.id
    override val kind: SourceKind get() = SourceKind.INTERNET_ARCHIVE
    override val displayName: String get() = config.label.ifBlank { SourceKind.INTERNET_ARCHIVE.label }

    override suspend fun health(): SourceHealth = runCatching {
        val response = client.get("advancedsearch.php") {
            parameter("q", "mediatype:audio")
            parameter("fl[]", "identifier")
            parameter("rows", 1)
            parameter("output", "json")
        }
        if (response.status.isSuccess()) SourceHealth.Ok() else SourceHealth.Unreachable("HTTP ${response.status.value}")
    }.getOrElse { SourceHealth.Unreachable(it.message ?: it.javaClass.simpleName) }

    /** [waitForAll] and [request] are moot: one catalogue, and every row is offered at its best. */
    override suspend fun search(
        query: String,
        limit: Int,
        waitForAll: Boolean,
        request: StreamRequest?,
    ): List<Song> {
        val terms = query.trim()
        if (terms.isEmpty()) return emptyList()
        TrackLog.d(TAG, "▶ Internet Archive search query=\"$terms\"")
        val items = searchItems(terms)
        if (items.isEmpty()) return emptyList()
        val perItem = coroutineScope {
            items.map { item ->
                async { withTimeoutOrNull(ITEM_TIMEOUT_MS) { runCatching { tracksOf(item) }.getOrNull() }.orEmpty() }
            }.awaitAll()
        }
        val narrowed = perItem.map { InternetArchiveTracks.narrow(it, terms) }
        // Round-robin, so the first concert's two-hour set doesn't bury the rest.
        val merged = buildList {
            val longest = narrowed.maxOfOrNull { it.size } ?: 0
            for (i in 0 until longest) narrowed.forEach { rows -> rows.getOrNull(i)?.let(::add) }
        }
        TrackLog.d(TAG, "  ✓ Internet Archive: ${merged.size} tracks from ${items.size} items")
        return merged.take(limit).map { it.toSong() }
    }

    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? {
        val identifier = trackId.substringBefore('/')
        val name = trackId.substringAfter('/', "")
        if (identifier.isBlank() || name.isBlank()) return null
        val files = runCatching { metadata(identifier) }.getOrNull()?.files ?: return null
        val flac = files.firstOrNull { it.name == name } ?: return null
        // Under a ceiling, the archive's own MP3 of the same file is the copy
        // that fits; it derives one for nearly every upload.
        val chosen = if (request is StreamRequest.Lossless) {
            flac
        } else {
            files.firstOrNull { it.original == name && it.format.contains("MP3", ignoreCase = true) } ?: flac
        }
        val lossless = chosen === flac
        TrackLog.d(TAG, "▶ Internet Archive stream $identifier/${chosen.name} (${chosen.format})")
        return SourceStream(
            url = downloadUrl(identifier, chosen.name),
            format = StreamFormat(
                codec = if (lossless) "flac" else "mp3",
                bitDepth = if (lossless && InternetArchiveTracks.isHiResFormat(flac.format)) 24 else null,
            ),
            durationSec = flac.lengthSec,
        )
    }

    private data class Item(val identifier: String, val title: String?, val creator: String?)

    private suspend fun searchItems(terms: String): List<Item> {
        val response = withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
            client.get("advancedsearch.php") {
                parameter("q", "($terms) AND mediatype:(audio) AND format:(Flac)")
                parameter("fl[]", "identifier")
                parameter("fl[]", "title")
                parameter("fl[]", "creator")
                parameter("rows", MAX_ITEMS)
                // The archive's own relevance ranks a tribute band's tape of
                // a song level with the band's; how often an item is played
                // is the better guide to which one was meant.
                parameter("sort[]", "downloads desc")
                parameter("output", "json")
            }
        } ?: return emptyList()
        if (!response.status.isSuccess()) return emptyList()
        val docs = runCatching {
            json.parseToJsonElement(response.bodyAsText()).jsonObject["response"]
                ?.jsonObject?.get("docs")?.jsonArray
        }.getOrNull() ?: return emptyList()
        return docs.mapNotNull { doc ->
            val o = doc as? JsonObject ?: return@mapNotNull null
            val id = o.text("identifier") ?: return@mapNotNull null
            Item(id, o.text("title"), o.text("creator"))
        }
    }

    private class Metadata(val metadata: JsonObject, val files: List<InternetArchiveTracks.File>)

    private suspend fun metadata(identifier: String): Metadata? {
        val response = client.get("metadata/$identifier")
        if (!response.status.isSuccess()) return null
        val root = json.parseToJsonElement(response.bodyAsText()) as? JsonObject ?: return null
        val files = (root["files"] as? JsonArray).orEmpty().mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            InternetArchiveTracks.File(
                name = o.text("name") ?: return@mapNotNull null,
                format = o.text("format").orEmpty(),
                title = o.text("title"),
                artist = o.text("artist") ?: o.text("creator"),
                track = o.text("track"),
                length = o.text("length"),
                original = o.text("original"),
            )
        }
        return Metadata(root["metadata"] as? JsonObject ?: JsonObject(emptyMap()), files)
    }

    private suspend fun tracksOf(item: Item): List<InternetArchiveTracks.Track> {
        val meta = metadata(item.identifier) ?: return emptyList()
        return InternetArchiveTracks.tracks(
            identifier = item.identifier,
            album = meta.metadata.text("title") ?: item.title,
            creator = meta.metadata.text("creator") ?: item.creator,
            files = meta.files,
        )
    }

    private fun InternetArchiveTracks.Track.toSong() = Song(
        videoId = SourceRegistry.trackKey(config.id, "$identifier/${file.name}"),
        title = title,
        artist = artist ?: "Unknown Artist",
        thumbnailUrl = "https://archive.org/services/img/$identifier",
        durationText = file.lengthSec?.let { "%d:%02d".format(Locale.ROOT, it / 60, it % 60) },
        albumName = album,
        sourceQuality = ModuleSource.LOSSLESS,
        sourceHiRes = InternetArchiveTracks.isHiResFormat(file.format),
    )

    private companion object {
        const val BASE_URL = "https://archive.org/"

        /** Together inside the Addons search's 12s budget. */
        const val SEARCH_TIMEOUT_MS = 5_000L
        const val ITEM_TIMEOUT_MS = 5_000L
        const val MAX_ITEMS = 6

        val json = Json { ignoreUnknownKeys = true }

        val client by lazy {
            HttpClient(OkHttp) {
                install(HttpTimeout) {
                    requestTimeoutMillis = 8_000
                    connectTimeoutMillis = 4_000
                    socketTimeoutMillis = 8_000
                }
                defaultRequest {
                    url(BASE_URL)
                    headers.append(HttpHeaders.Accept, "application/json")
                    headers.append(
                        HttpHeaders.UserAgent,
                        "MusicBeat/${BuildConfig.VERSION_NAME} (Android; +$MUSICBEAT_REPO_URL)",
                    )
                }
                expectSuccess = false
            }
        }

        fun downloadUrl(identifier: String, name: String): String =
            BASE_URL + "download/" + identifier + "/" +
                name.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

        /** A metadata field as text: the archive stores many as either a string or a list of them. */
        fun JsonObject.text(key: String): String? = when (val value: JsonElement? = this[key]) {
            is JsonPrimitive -> value.contentOrNull
            is JsonArray -> value.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }
            else -> null
        }?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/**
 * The file-list half of [InternetArchiveSource], kept free of I/O so it can be
 * tested on what the archive actually returns.
 */
internal object InternetArchiveTracks {

    data class File(
        val name: String,
        val format: String,
        val title: String? = null,
        val artist: String? = null,
        val track: String? = null,
        val length: String? = null,
        /** For a derived file, the name of the file it was made from. */
        val original: String? = null,
    ) {
        /** `length` comes as seconds (`"312.45"`) or as a clock (`"5:12"`). */
        val lengthSec: Int?
            get() {
                val raw = length?.trim() ?: return null
                if (':' in raw) {
                    return raw.split(':').fold(0) { acc, part -> acc * 60 + (part.toDoubleOrNull()?.toInt() ?: return null) }
                        .takeIf { it > 0 }
                }
                return raw.toDoubleOrNull()?.toInt()?.takeIf { it > 0 }
            }
    }

    data class Track(
        val identifier: String,
        val file: File,
        val title: String,
        val artist: String?,
        val album: String?,
    )

    /**
     * "Flac" and "24bit Flac" — the two the archive files lossless uploads
     * under. Matched whole, not as a substring: "Flac FingerPrint" is the
     * item's checksum list.
     */
    fun isFlacFormat(format: String) = FLAC_FORMAT.matches(format.trim())

    private val FLAC_FORMAT = Regex("(24bit )?flac", RegexOption.IGNORE_CASE)

    fun isHiResFormat(format: String) = format.contains("24bit", ignoreCase = true)

    /** The item's FLAC files as tracks, in running order. */
    fun tracks(identifier: String, album: String?, creator: String?, files: List<File>): List<Track> {
        val flacs = files.filter { isFlacFormat(it.format) }
        // A 24-bit upload often sits beside a 16-bit derivative of itself;
        // only the better copy of each is a track.
        val shadowed = flacs.filter { isHiResFormat(it.format) }.mapTo(HashSet()) { it.name }
        return flacs
            .filterNot { it.original != null && it.original in shadowed }
            .sortedWith(compareBy<File> { trackNumber(it.track) ?: Int.MAX_VALUE }.thenBy { it.name })
            .map { file ->
                Track(
                    identifier = identifier,
                    file = file,
                    title = file.title ?: file.name.substringAfterLast('/').substringBeforeLast('.'),
                    artist = file.artist ?: creator,
                    album = album,
                )
            }
    }

    /**
     * The tracks of one item a query was about. A query naming a song keeps
     * the tracks whose titles carry its words; one that only named the item —
     * the band, the venue, the date — matches no title and keeps them all.
     */
    fun narrow(tracks: List<Track>, query: String): List<Track> {
        val words = words(query)
        if (words.isEmpty()) return tracks
        val itemWords = tracks.firstOrNull()?.let { words("${it.album.orEmpty()} ${it.artist.orEmpty()}") }.orEmpty()
        val songWords = words - itemWords
        if (songWords.isEmpty()) return tracks
        val hits = tracks.filter { track -> words(track.title).any { it in songWords } }
        return hits.ifEmpty { tracks }
    }

    private fun words(text: String): Set<String> =
        text.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 }.toSet()

    private fun trackNumber(raw: String?): Int? = raw?.substringBefore('/')?.trim()?.toIntOrNull()
}
