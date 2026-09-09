package net.dexxicon.reader.core.media

/** Custom [androidx.media3.session.SessionCommand] actions understood by [PlaybackService]. */
object PlaybackCommands {
    const val SET_SKIP_SILENCE = "net.dexxicon.reader.SET_SKIP_SILENCE"
    const val ARG_ENABLED = "enabled"

    /** Route playback to a specific output. [ARG_DEVICE_ID] < 0 clears the preference. */
    const val SET_AUDIO_OUTPUT = "net.dexxicon.reader.SET_AUDIO_OUTPUT"
    const val ARG_DEVICE_ID = "device_id"
}
