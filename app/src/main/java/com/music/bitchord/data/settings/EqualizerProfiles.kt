package com.music.bitchord.data.settings

import android.media.AudioDeviceInfo
import com.music.bitchord.playback.AudioRouting
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.math.abs

/** A Manual-tab curve saved under a name of the listener's own. */
@Serializable
data class UserEqPreset(val name: String, val bands: List<Float>) {
    /** Whether [bands] are this curve, give or take what a slider can't show. */
    fun matches(bands: List<Float>): Boolean =
        this.bands.size == bands.size && this.bands.indices.all { abs(this.bands[it] - bands[it]) < BAND_EPSILON_DB }
}

/**
 * Everything the equaliser screen sets, as one value: what a per-device profile
 * keeps. The mode is held by name so a build that renames or drops one reads an
 * old profile back as the default rather than failing to read it at all.
 */
@Serializable
data class EqSnapshot(
    val enabled: Boolean,
    val mode: String,
    val toneX: Int,
    val toneY: Int,
    val focused: Boolean,
    val balance: Float,
    val bands: List<Float>,
)

/**
 * The output a per-device profile belongs to.
 *
 * Keyed on what the device *is* rather than the framework's id, which is
 * reassigned every time something reconnects: the phone's speaker and wired
 * headphones by kind alone (neither has a name of its own — see
 * [AudioRouting]), everything else by kind and product name, so two pairs of
 * Bluetooth earbuds keep two profiles.
 */
data class EqOutput(val key: String, val kind: AudioRouting.Kind, val name: String) {
    companion object {
        fun of(info: AudioDeviceInfo?): EqOutput {
            val kind = info?.let { AudioRouting.kindOf(it.type) } ?: AudioRouting.Kind.PHONE
            val name = when (kind) {
                AudioRouting.Kind.PHONE, AudioRouting.Kind.WIRED -> ""
                else -> info?.productName?.toString()?.trim().orEmpty()
            }
            val key = if (name.isEmpty()) kind.name else "${kind.name}:$name"
            return EqOutput(key, kind, name)
        }
    }
}

internal object EqualizerCodec {
    private val json = Json { ignoreUnknownKeys = true }
    private val presets = ListSerializer(UserEqPreset.serializer())
    private val profiles = MapSerializer(String.serializer(), EqSnapshot.serializer())

    fun presets(text: String?): List<UserEqPreset> =
        text?.let { runCatching { json.decodeFromString(presets, it) }.getOrNull() }.orEmpty()

    fun presets(value: List<UserEqPreset>): String = json.encodeToString(presets, value)

    fun profiles(text: String?): Map<String, EqSnapshot> =
        text?.let { runCatching { json.decodeFromString(profiles, it) }.getOrNull() }.orEmpty()

    fun profiles(value: Map<String, EqSnapshot>): String = json.encodeToString(profiles, value)
}

/** Longest name a saved preset keeps; the row it is shown on has room for about this much. */
const val USER_EQ_PRESET_NAME_MAX = 32

private const val BAND_EPSILON_DB = 0.05f
