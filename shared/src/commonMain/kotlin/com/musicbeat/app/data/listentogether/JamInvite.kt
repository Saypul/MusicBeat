package com.musicbeat.app.data.listentogether

/**
 * The public invite URL for a party, and the only shape that is accepted back.
 *
 * Shared because both applications have to agree on it exactly: a link built on one and opened on
 * the other must resolve to the same code, and a link that is not this shape must be refused on
 * both rather than half-parsed into a join attempt.
 */
object JamInvite {

    /**
     * Invites are app links rather than web ones: the web domain the parties
     * used to be shared on is BitChord's, and MusicBeat has none of its own.
     */
    const val ORIGIN = "musicbeat://party"

    /** How many characters a party code carries. */
    const val CODE_LENGTH = 6

    /** The normalized party code, for the invite link shape only. */
    fun parse(value: String?): String? {
        val text = value?.trim() ?: return null
        val prefix = "$ORIGIN/"
        if (!text.startsWith(prefix, ignoreCase = true)) return null
        val code = text.substring(prefix.length).substringBefore('?').substringBefore('#')
        if (code.length != CODE_LENGTH || !code.all(Char::isLetterOrDigit)) return null
        return code.uppercase()
    }

    fun url(code: String): String = "$ORIGIN/${code.uppercase()}"
}
