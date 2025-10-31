package com.googlevideo.sabr

import com.googlevideo.sabr.session.SabrFormat
import video_streaming.StreamerContextOuterClass

/**
 * Public-facing handle representing SABR playback state.
 *
 * The library will expand this holder with the metadata extracted from SABR manifests and
 * any session context required while streaming. Host applications should treat this as an
 * immutable snapshot and pass it to the SABR data source factory.
 */
data class SabrSession(
    val manifestInfo: ManifestInfo = ManifestInfo.Empty,
) {

    /**
      * Container for information extracted from a SABR-enabled DASH manifest.
      *
      * The fields will be populated by the manifest parser in later phases.
      */
    data class ManifestInfo(
        val rawMpd: String? = null,
        val supplementalPropertyValue: String? = null,
        val sabrPayloadJson: String? = null,
        val sabrBaseUrls: List<String> = emptyList(),
        val sabrFormats: List<SabrFormat> = emptyList(),
        val serverAbrStreamingUrl: String? = null,
        val ustreamerConfig: String? = null,
        val poTokenBase64: String? = null,
        val clientInfo: StreamerContextOuterClass.StreamerContext.ClientInfo? = null,
    ) {
        companion object {
            val Empty = ManifestInfo()
        }
    }

    companion object {
        val Empty = SabrSession()
    }
}
