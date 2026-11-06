package com.musicbeat.app.data.discord

/**
 * MusicBeat's Discord application, shared by the phone and desktop presence.
 *
 * Discord reads more off this id than the code suggests. The local socket on
 * the desktop titles the activity with the application's registered name, some
 * Discord surfaces do the same for a gateway activity that carries it, and an
 * activity with no `large_image` shows the application's icon. Both come from
 * the developer portal entry, so the id has to be one registered as "MusicBeat".
 *
 * Blank until there is one. The id this was inherited with is BitChord's own
 * application, which would have shown every MusicBeat listener as listening on
 * BitChord, so Discord presence stays off ([available]) rather than borrow it.
 */
object DiscordApplication {
    const val ID = ""

    /** Whether there is an application to publish under at all. */
    val available: Boolean get() = ID.isNotBlank()
}
