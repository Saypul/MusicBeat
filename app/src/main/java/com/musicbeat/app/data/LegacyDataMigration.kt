package com.musicbeat.app.data

import android.app.NotificationManager
import android.content.Context
import java.io.File

/**
 * Carries an install over from before the app's own names were MusicBeat's.
 *
 * Releases up to 1.10 kept their preferences under BitChord's names
 * (`bitchord_settings.xml` and so on). Left there, the renamed code would open
 * empty files under the new names, and an update would read as a reset: signed
 * out, every setting back to its default, local playlists gone.
 *
 * Plain preference files are moved before anything opens them, which is why
 * [movePreferences] runs from `attachBaseContext` — ahead of the content
 * providers, App Startup's included, that the framework creates before
 * `Application.onCreate`. The two encrypted stores cannot be moved as files:
 * their keys are sealed with the file's own name, so [EncryptedPrefs] re-encrypts
 * them under the new name when they are first opened — see [LEGACY_ENCRYPTED].
 */
object LegacyDataMigration {

    /** Preference files moved as they are, old name to new. */
    private val PLAIN = listOf(
        "settings", "party_queue_stash", "original_versions", "last_played", "bounded_context",
        "playlist_sync", "listen_together", "offline_playlists", "local_playlists", "widget",
        // An encrypted store's plain fallback is an ordinary file.
        "auth_plain", "sources_plain",
    ).map { "bitchord_$it" to "musicbeat_$it" }

    /** Encrypted stores, new name to old; read by [EncryptedPrefs] on first open. */
    val LEGACY_ENCRYPTED = mapOf(
        "musicbeat_auth" to "bitchord_auth",
        "musicbeat_sources" to "bitchord_sources",
    )

    /**
     * Identifiers the old build wrote into its own values: the package in a
     * stored component or class name, and the app's internal URI scheme. Only
     * these lower-case forms are touched, never the word on its own, so a
     * playlist somebody named "BitChord favourites" keeps its name.
     */
    private val VALUE_RENAMES = listOf(
        "com.music.bitchord" to "com.musicbeat.app",
        "bitchord://" to "musicbeat://",
    )

    private const val OLD_PLAYBACK_CHANNEL = "bitchord_playback"

    /** Moves each old preference file to its new name, where the new one does not exist yet. */
    fun movePreferences(context: Context) {
        val dir = File(context.applicationInfo.dataDir, "shared_prefs")
        if (!dir.isDirectory) return
        for ((old, new) in PLAIN) {
            val from = File(dir, "$old.xml")
            val to = File(dir, "$new.xml")
            if (!from.isFile || to.exists()) continue
            val text = runCatching { from.readText() }.getOrNull() ?: continue
            val rewritten = VALUE_RENAMES.fold(text) { acc, (a, b) -> acc.replace(a, b) }
            // Written whole and then swapped in, so a process killed half-way
            // leaves the old file standing and the next launch tries again.
            val partial = File(dir, "$new.xml.migrating")
            if (runCatching { partial.writeText(rewritten) }.isFailure || !partial.renameTo(to)) {
                partial.delete()
                continue
            }
            from.delete()
            File(dir, "$old.xml.bak").delete()
        }
    }

    /** Drops the notification channel the old build created under its own id. */
    fun removeOldChannels(context: Context) {
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.deleteNotificationChannel(OLD_PLAYBACK_CHANNEL)
        }
    }
}
