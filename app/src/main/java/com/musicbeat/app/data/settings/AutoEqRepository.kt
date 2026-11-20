package com.musicbeat.app.data.settings

import android.content.Context
import com.musicbeat.app.data.Http
import com.musicbeat.app.playback.AutoEqEntry
import com.musicbeat.app.playback.AutoEqFormat
import com.musicbeat.app.playback.AutoEqIndex
import com.musicbeat.app.playback.EqImport
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * AutoEQ's published headphone corrections, fetched on demand.
 *
 * The index is the one large thing here (most of a megabyte, nearly nine
 * thousand headphones), so it is kept in the cache directory and only fetched
 * again once it is a week old. A stale copy is still better than none when the
 * network is not there, so it is what a failed refresh falls back to.
 */
object AutoEqRepository {

    @Volatile
    private var entries: List<AutoEqEntry>? = null

    suspend fun index(context: Context): List<AutoEqEntry> = withContext(Dispatchers.IO) {
        entries?.let { return@withContext it }
        val file = File(context.cacheDir, CACHE_FILE)
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < MAX_AGE_MS
        val text = if (fresh) {
            file.readText()
        } else {
            runCatching { fetch(AutoEqIndex.INDEX_URL).also { file.writeText(it) } }
                .getOrElse { error -> if (file.exists()) file.readText() else throw error }
        }
        AutoEqIndex.parse(text).also { if (it.isNotEmpty()) entries = it }
    }

    /** The headphone's parametric correction, or an [IOException] saying why there isn't one. */
    suspend fun correction(entry: AutoEqEntry): EqImport = withContext(Dispatchers.IO) {
        AutoEqFormat.parse(fetch(entry.parametricUrl))
            ?: throw IOException("No parametric correction for ${entry.name}")
    }

    private fun fetch(url: String): String {
        val request = Request.Builder().url(url).build()
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            return response.body?.string() ?: throw IOException("Empty response for $url")
        }
    }

    private const val CACHE_FILE = "autoeq-index.md"
    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
}
