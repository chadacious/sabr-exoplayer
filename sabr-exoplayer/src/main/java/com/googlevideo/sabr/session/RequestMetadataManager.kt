package com.googlevideo.sabr.session

import java.net.URI
import java.net.URISyntaxException
import java.util.concurrent.ConcurrentHashMap
private const val CLEANUP_INTERVAL_MS = 30_000L
private const val ENTRY_EXPIRATION_MS = 180_000L

/**
 * Mirrors the behaviour of the TypeScript `RequestMetadataManager`.
 *
 * Stores SABR request metadata keyed by the request number appended to SABR URLs (`rn` query param).
 * Entries automatically expire after a short window to avoid leaking memory when requests fail.
 */
class RequestMetadataManager {

    private val metadataMap: MutableMap<String, SabrRequestMetadata> = ConcurrentHashMap()
    private var lastCleanupAtMs: Long = System.currentTimeMillis()

    fun get(url: String, remove: Boolean = false): SabrRequestMetadata? {
        val requestNumber = requestNumber(url) ?: return null
        val metadata = metadataMap[requestNumber] ?: return null

        if (isExpired(metadata)) {
            metadataMap.remove(requestNumber)
            return null
        }

        if (remove) {
            metadataMap.remove(requestNumber)
        }

        conditionalCleanup()
        return metadata
    }

    fun put(url: String, metadata: SabrRequestMetadata) {
        val requestNumber = requestNumber(url) ?: return
        metadataMap[requestNumber] = metadata
        conditionalCleanup()
    }

    private fun requestNumber(url: String): String? {
        return try {
            URI(url).let { uri ->
                val query = uri.rawQuery ?: return null
                query.split("&")
                    .mapNotNull {
                        val (key, value) = it.split("=", limit = 2).let { parts ->
                            parts.getOrNull(0) to parts.getOrNull(1)
                        }
                        if (key == "rn") value else null
                    }
                    .firstOrNull()
            }
        } catch (_: URISyntaxException) {
            null
        }
    }

    private fun isExpired(metadata: SabrRequestMetadata): Boolean {
        return System.currentTimeMillis() - metadata.timestampMs > ENTRY_EXPIRATION_MS
    }

    private fun conditionalCleanup() {
        val now = System.currentTimeMillis()
        if (now - lastCleanupAtMs < CLEANUP_INTERVAL_MS) return
        lastCleanupAtMs = now
        cleanup()
    }

    private fun cleanup() {
        val iterator = metadataMap.entries.iterator()
        val now = System.currentTimeMillis()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.timestampMs > ENTRY_EXPIRATION_MS) {
                iterator.remove()
            }
        }
    }

    fun clear() {
        metadataMap.clear()
        lastCleanupAtMs = System.currentTimeMillis()
    }
}
