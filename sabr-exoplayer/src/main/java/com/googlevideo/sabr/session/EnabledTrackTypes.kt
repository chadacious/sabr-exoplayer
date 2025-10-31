package com.googlevideo.sabr.session

/**
 * Mirrors the bitfield values used by the TypeScript SABR adapter for enabling track types.
 *
 * The values originate from `EnabledTrackTypes` in the Shaka reference implementation.
 */
internal object EnabledTrackTypes {
    const val VIDEO_AND_AUDIO = 0
    const val AUDIO_ONLY = 1
    const val VIDEO_ONLY = 2
}
