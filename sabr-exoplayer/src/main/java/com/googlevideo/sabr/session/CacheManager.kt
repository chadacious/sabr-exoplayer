package com.googlevideo.sabr.session

import java.util.concurrent.ConcurrentHashMap

/**
 * Lightweight in-memory cache for SABR segments.
 *
 * Mimics the behaviour of the TypeScript CacheManager closely enough for streaming: initialization
 * segments are retained and refreshed on access, while regular media segments are single-use.
 */
class CacheManager(
    private val maxCacheSizeBytes: Long = DEFAULT_MAX_CACHE_SIZE_BYTES,
    private val maxEntryAgeMs: Long = DEFAULT_MAX_ENTRY_AGE_MS,
) {

    private val initSegments = ConcurrentHashMap<String, CacheEntry>()
    private val mediaSegments = ConcurrentHashMap<String, CacheEntry>()
    private var currentSize = 0L

    fun putInitSegment(key: String, data: ByteArray) {
        putEntry(initSegments, key, data)
    }

    fun getInitSegment(key: String): ByteArray? {
        return getEntry(initSegments, key, retainOnHit = true)
    }

    fun putSegment(key: String, data: ByteArray) {
        putEntry(mediaSegments, key, data)
    }

    fun getSegment(key: String): ByteArray? {
        return getEntry(mediaSegments, key, retainOnHit = false)
    }

    fun clear() {
        initSegments.clear()
        mediaSegments.clear()
        currentSize = 0L
    }

    private fun putEntry(
        map: ConcurrentHashMap<String, CacheEntry>,
        key: String,
        data: ByteArray,
    ) {
        val entrySize = data.size.toLong()
        val entry = CacheEntry(data.copyOf(), System.currentTimeMillis())
        val previous = map.put(key, entry)
        currentSize += entrySize
        if (previous != null) {
            currentSize -= previous.data.size
        }
        evictIfNecessary()
    }

    private fun getEntry(
        map: ConcurrentHashMap<String, CacheEntry>,
        key: String,
        retainOnHit: Boolean,
    ): ByteArray? {
        val entry = map[key] ?: return null
        if (isExpired(entry)) {
            map.remove(key)
            currentSize -= entry.data.size
            return null
        }
        entry.timestampMs = System.currentTimeMillis()
        if (!retainOnHit) {
            map.remove(key)
            currentSize -= entry.data.size
        }
        return entry.data.copyOf()
    }

    private fun isExpired(entry: CacheEntry): Boolean {
        return System.currentTimeMillis() - entry.timestampMs > maxEntryAgeMs
    }

    private fun evictIfNecessary() {
        if (currentSize <= maxCacheSizeBytes) return

        removeExpiredEntries()
        if (currentSize <= maxCacheSizeBytes) return

        val allEntries = buildList {
            addAll(initSegments.entries)
            addAll(mediaSegments.entries)
        }.sortedBy { it.value.timestampMs }

        val iterator = allEntries.iterator()
        while (currentSize > maxCacheSizeBytes && iterator.hasNext()) {
            val entry = iterator.next()
            if (initSegments.remove(entry.key) != null || mediaSegments.remove(entry.key) != null) {
                currentSize -= entry.value.data.size
            }
        }
    }

    private fun removeExpiredEntries() {
        val now = System.currentTimeMillis()
        pruneExpiredEntries(initSegments, now)
        pruneExpiredEntries(mediaSegments, now)
    }

    // Avoid java.util.Collection#removeIf so this works on API 21 without desugaring.
    private fun pruneExpiredEntries(map: MutableMap<String, CacheEntry>, now: Long) {
        val iterator = map.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.timestampMs > maxEntryAgeMs) {
                currentSize -= entry.value.data.size
                iterator.remove()
            }
        }
    }

    private data class CacheEntry(
        val data: ByteArray,
        var timestampMs: Long,
    )

    companion object {
        private const val DEFAULT_MAX_CACHE_SIZE_BYTES = 3L * 1024 * 1024
        private const val DEFAULT_MAX_ENTRY_AGE_MS = 3L * 60 * 1000
    }
}
