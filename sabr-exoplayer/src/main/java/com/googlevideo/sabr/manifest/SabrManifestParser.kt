package com.googlevideo.sabr.manifest

import android.util.Base64
import com.googlevideo.sabr.SabrSession
import com.googlevideo.sabr.session.SabrFormat
import java.io.StringReader
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import video_streaming.StreamerContextOuterClass

/**
 * Entry point for turning a DASH manifest string into a [SabrSession].
 *
 * The actual extraction logic will be implemented in later phases. For now this class provides a
 * stable API surface that host applications can depend on.
 */
object SabrManifestParser {

    private const val SUPPLEMENTAL_PROPERTY = "SupplementalProperty"
    private const val BASE_URL = "BaseURL"
    private const val ATTR_SCHEME_ID_URI = "schemeIdUri"
    private const val ATTR_VALUE = "value"
    private const val SABR_SCHEME = "urn:youtube:sabr"
    private const val SABR_URI_SCHEME = "sabr://"

    data class Result(
        val session: SabrSession,
        val isSabrStream: Boolean,
        val warnings: List<String> = emptyList(),
    )

    /**
     * Parses the supplied DASH manifest string.
     *
     * @param mpd Raw DASH MPD contents.
     * @return A result containing a [SabrSession] placeholder and flags indicating whether the
     *         manifest advertises SABR features.
     */
    fun parse(mpd: String): Result {
        val warnings = mutableListOf<String>()
        var sabrPropertyValue: String? = null
        var sabrPayloadJson: String? = null
        var sabrPayload: ParsedPayload? = null
        val sabrBaseUrls = mutableListOf<String>()

        try {
            val parser = XmlPullParserFactory.newInstance().apply {
                isNamespaceAware = true
            }.newPullParser()
            parser.setInput(StringReader(mpd))

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        SUPPLEMENTAL_PROPERTY -> {
                            val schemeIdUri = parser.getAttributeValue(null, ATTR_SCHEME_ID_URI)
                                ?: parser.getAttributeValue("", ATTR_SCHEME_ID_URI)
                            if (schemeIdUri == SABR_SCHEME) {
                                val value = parser.getAttributeValue(null, ATTR_VALUE)
                                    ?: parser.getAttributeValue("", ATTR_VALUE)
                                if (!value.isNullOrBlank() && sabrPropertyValue == null) {
                                    sabrPropertyValue = value
                                    sabrPayloadJson = decodeBase64ToUtf8(value, warnings)
                                    sabrPayload = sabrPayloadJson?.let { parseSabrPayload(it, warnings) }
                                }
                            }
                        }

                        BASE_URL -> {
                            val text = readElementText(parser).trim()
                            if (text.startsWith(SABR_URI_SCHEME)) {
                                sabrBaseUrls += text
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (error: Exception) {
            warnings += "Manifest parsing failed: ${error.message}"
        }

        val manifestInfo = SabrSession.ManifestInfo(
            rawMpd = mpd,
            supplementalPropertyValue = sabrPropertyValue,
            sabrPayloadJson = sabrPayloadJson,
            sabrBaseUrls = sabrBaseUrls.toList(),
            sabrFormats = sabrPayload?.formats.orEmpty(),
            serverAbrStreamingUrl = sabrPayload?.serverAbrStreamingUrl,
            ustreamerConfig = sabrPayload?.ustreamerConfig,
            poTokenBase64 = sabrPayload?.poTokenBase64,
            clientInfo = sabrPayload?.clientInfo,
        )

        val isSabrStream = sabrPayload != null || sabrPropertyValue != null || sabrBaseUrls.isNotEmpty()

        return Result(
            session = SabrSession(manifestInfo = manifestInfo),
            isSabrStream = isSabrStream,
            warnings = warnings,
        )
    }

    private fun decodeBase64ToUtf8(value: String, warnings: MutableList<String>): String? {
        return try {
            val decodedBytes = Base64.decode(value, Base64.DEFAULT)
            decodedBytes.toString(Charsets.UTF_8)
        } catch (error: IllegalArgumentException) {
            warnings += "Failed to decode SABR supplemental property: ${error.message}"
            null
        }
    }

    private fun readElementText(parser: XmlPullParser): String {
        var text = ""
        if (parser.next() == XmlPullParser.TEXT) {
            text = parser.text ?: ""
            parser.nextTag()
        }
        return text
    }

    private fun parseSabrPayload(json: String, warnings: MutableList<String>): ParsedPayload? {
        return try {
            val root = JSONObject(json)
            val adaptiveFormats = root.optJSONArray("adaptiveFormats")
            val baseFormats = root.optJSONArray("formats")
            val formats = parseFormats(adaptiveFormats) + parseFormats(baseFormats)
            val serverUrl = root.optString("serverAbrStreamingUrl").takeIf { it.isNotBlank() }
            val ustreamerConfig = root.optString("ustreamerConfig").takeIf { it.isNotBlank() }
            val poToken = root.optString("poToken").takeIf { it.isNotBlank() }
            val streamerConfig = root.optJSONObject("streamerConfig")
            val clientInfoJson = root.optJSONObject("clientInfo")
                ?: streamerConfig?.optJSONObject("clientInfo")
            val clientInfo = clientInfoJson?.let(::parseClientInfo)
            ParsedPayload(
                formats = formats,
                serverAbrStreamingUrl = serverUrl,
                ustreamerConfig = ustreamerConfig,
                poTokenBase64 = poToken,
                clientInfo = clientInfo,
            )
        } catch (error: Exception) {
            warnings += "Failed to parse SABR payload: ${error.message}"
            null
        }
    }

    private fun parseFormats(array: JSONArray?): List<SabrFormat> {
        if (array == null) return emptyList()
        val formats = mutableListOf<SabrFormat>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val itag = obj.optInt("itag", -1)
            if (itag <= 0) continue
            formats += SabrFormat(
                itag = itag,
                xtags = obj.optString("xtags").takeIf { it.isNotBlank() }
                    ?: obj.optString("xTags").takeIf { it.isNotBlank() },
                lastModified = obj.optString("lastModified").takeIf { it.isNotBlank() }
                    ?: obj.optString("last_modified_ms").takeIf { it.isNotBlank() },
                width = obj.optInt("width").takeIf { it > 0 },
                height = obj.optInt("height").takeIf { it > 0 },
                contentLength = obj.optString("contentLength")
                    .takeIf { it.isNotBlank() }?.toLongOrNull()
                    ?: obj.optString("content_length").takeIf { it.isNotBlank() }?.toLongOrNull(),
                audioTrackId = obj.optJSONObject("audioTrack")?.optString("id")
                    ?: obj.optString("audioTrackId").takeIf { it.isNotBlank() },
                mimeType = obj.optString("mimeType").takeIf { it.isNotBlank() }
                    ?: obj.optString("mime_type").takeIf { it.isNotBlank() },
                isDrc = obj.optBoolean("isDrc", obj.optBoolean("is_drc", false)),
                quality = obj.optString("quality").takeIf { it.isNotBlank() },
                qualityLabel = obj.optString("qualityLabel").takeIf { it.isNotBlank() }
                    ?: obj.optString("quality_label").takeIf { it.isNotBlank() },
                averageBitrate = obj.optInt("averageBitrate", obj.optInt("average_bitrate", -1)).takeIf { it >= 0 },
                bitrate = obj.optInt("bitrate", -1).takeIf { it >= 0 },
                audioQuality = obj.optString("audioQuality").takeIf { it.isNotBlank() }
                    ?: obj.optString("audio_quality").takeIf { it.isNotBlank() },
                approxDurationMs = obj.optString("approxDurationMs")
                    .takeIf { it.isNotBlank() }?.toLongOrNull()
                    ?: obj.optString("approx_duration_ms").takeIf { it.isNotBlank() }?.toLongOrNull(),
                language = obj.optString("language").takeIf { it.isNotBlank() },
                isDubbed = when {
                    obj.has("isDubbed") -> obj.optBoolean("isDubbed")
                    obj.has("is_dubbed") -> obj.optBoolean("is_dubbed")
                    else -> null
                },
                isAutoDubbed = when {
                    obj.has("isAutoDubbed") -> obj.optBoolean("isAutoDubbed")
                    obj.has("is_auto_dubbed") -> obj.optBoolean("is_auto_dubbed")
                    else -> null
                },
                isDescriptive = when {
                    obj.has("isDescriptive") -> obj.optBoolean("isDescriptive")
                    obj.has("is_descriptive") -> obj.optBoolean("is_descriptive")
                    else -> null
                },
                isSecondary = when {
                    obj.has("isSecondary") -> obj.optBoolean("isSecondary")
                    obj.has("is_secondary") -> obj.optBoolean("is_secondary")
                    else -> null
                },
                isOriginal = when {
                    obj.has("isOriginal") -> obj.optBoolean("isOriginal")
                    obj.has("is_original") -> obj.optBoolean("is_original")
                    else -> null
                },
                audioChannels = obj.optInt("audioChannels", obj.optInt("audio_channels", -1)).takeIf { it > 0 },
            )
        }
        return formats
    }

    private data class ParsedPayload(
        val formats: List<SabrFormat>,
        val serverAbrStreamingUrl: String?,
        val ustreamerConfig: String?,
        val poTokenBase64: String?,
        val clientInfo: StreamerContextOuterClass.StreamerContext.ClientInfo?,
    )

    private fun parseClientInfo(json: JSONObject): StreamerContextOuterClass.StreamerContext.ClientInfo? {
        val builder = StreamerContextOuterClass.StreamerContext.ClientInfo.newBuilder()
        var populated = false

        fun JSONObject.optStringOrNull(key: String): String? =
            optString(key).takeIf { !it.isNullOrBlank() }

        fun JSONObject.optIntOrNull(key: String): Int? =
            if (has(key)) optInt(key) else null

        json.optStringOrNull("deviceMake")?.let {
            builder.deviceMake = it
            populated = true
        }
        json.optStringOrNull("deviceModel")?.let {
            builder.deviceModel = it
            populated = true
        }
        json.optIntOrNull("clientName")?.let {
            builder.clientName = it
            populated = true
        }
        json.optStringOrNull("clientVersion")?.let {
            builder.clientVersion = it
            populated = true
        }
        json.optStringOrNull("osName")?.let {
            builder.osName = it
            populated = true
        }
        json.optStringOrNull("osVersion")?.let {
            builder.osVersion = it
            populated = true
        }
        json.optStringOrNull("acceptLanguage")?.let {
            builder.acceptLanguage = it
            populated = true
        }
        json.optStringOrNull("acceptRegion")?.let {
            builder.acceptRegion = it
            populated = true
        }
        if (json.has("screenWidthPoints")) {
            builder.screenWidthPoints = json.optInt("screenWidthPoints")
            populated = true
        }
        if (json.has("screenHeightPoints")) {
            builder.screenHeightPoints = json.optInt("screenHeightPoints")
            populated = true
        }
        if (json.has("screenWidthInches")) {
            builder.screenWidthInches = json.optDouble("screenWidthInches").toFloat()
            populated = true
        }
        if (json.has("screenHeightInches")) {
            builder.screenHeightInches = json.optDouble("screenHeightInches").toFloat()
            populated = true
        }
        if (json.has("screenPixelDensity")) {
            builder.screenPixelDensity = json.optInt("screenPixelDensity")
            populated = true
        }
        if (json.has("windowWidthPoints")) {
            builder.windowWidthPoints = json.optInt("windowWidthPoints")
            populated = true
        }
        if (json.has("windowHeightPoints")) {
            builder.windowHeightPoints = json.optInt("windowHeightPoints")
            populated = true
        }
        if (json.has("clientFormFactor")) {
            val enumValue = StreamerContextOuterClass.StreamerContext.ClientFormFactor.forNumber(
                json.optInt("clientFormFactor")
            )
            if (enumValue != null) {
                builder.clientFormFactor = enumValue
                populated = true
            }
        }
        if (json.has("gmscoreVersionCode")) {
            builder.gmscoreVersionCode = json.optInt("gmscoreVersionCode")
            populated = true
        }
        if (json.has("androidSdkVersion")) {
            builder.androidSdkVersion = json.optInt("androidSdkVersion")
            populated = true
        }
        if (json.has("screenDensityFloat")) {
            builder.screenDensityFloat = json.optDouble("screenDensityFloat").toFloat()
            populated = true
        }
        if (json.has("utcOffsetMinutes")) {
            builder.utcOffsetMinutes = json.optLong("utcOffsetMinutes")
            populated = true
        }
        json.optStringOrNull("timeZone")?.let {
            builder.timeZone = it
            populated = true
        }
        json.optStringOrNull("chipset")?.let {
            builder.chipset = it
            populated = true
        }

        json.optJSONObject("glDeviceInfo")?.let { glInfo ->
            val glBuilder = StreamerContextOuterClass.StreamerContext.GLDeviceInfo.newBuilder()
            var glPopulated = false
            glInfo.optStringOrNull("glRenderer")?.let {
                glBuilder.glRenderer = it
                glPopulated = true
            }
            glInfo.optIntOrNull("glEsVersionMajor")?.let {
                glBuilder.glEsVersionMajor = it
                glPopulated = true
            }
            glInfo.optIntOrNull("glEsVersionMinor")?.let {
                glBuilder.glEsVersionMinor = it
                glPopulated = true
            }
            if (glPopulated) {
                builder.glDeviceInfo = glBuilder.build()
                populated = true
            }
        }

        return if (populated) builder.build() else null
    }
}
