package com.googlevideo.sabr.datasource

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import kotlin.OptIn
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.googlevideo.sabr.SabrSession
import com.googlevideo.sabr.logging.SabrLogger
import com.googlevideo.sabr.logging.d
import com.googlevideo.sabr.logging.e
import com.googlevideo.sabr.logging.w
import com.googlevideo.sabr.session.SabrSessionManager
import java.io.IOException
import kotlin.math.min

@UnstableApi
internal class SabrDataSource(
    private val delegateFactory: DataSource.Factory,
    private val sessionManager: SabrSessionManager,
    private val sessionProvider: () -> SabrSession,
    private val segmentFetcher: SabrSegmentFetcher,
    private val logger: SabrLogger,
) : DataSource {

    private val transferListeners = mutableListOf<TransferListener>()
    private var delegate: DataSource? = null
    private var sabrData: ByteArray? = null
    private var sabrReadPosition = 0
    private var currentUri: Uri? = null

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners += transferListener
        delegate?.addTransferListener(transferListener)
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        currentUri = dataSpec.uri
        if (!isSabrUri(dataSpec.uri)) {
            val newDelegate = delegateFactory.createDataSource()
            transferListeners.forEach(newDelegate::addTransferListener)
            delegate = newDelegate
            logger.d(TAG) { "Delegated open() to fallback data source for uri=${dataSpec.uri}" }
            return newDelegate.open(dataSpec)
        }

        delegate = null
        try {
            val session = sessionProvider()
            sessionManager.applyManifest(session.manifestInfo)
            val request = buildSegmentRequest(dataSpec)
            logger.d(TAG) {
                "Opening SABR dataSpec uri=${dataSpec.uri} position=${dataSpec.position} length=${dataSpec.length}"
            }
            val result = segmentFetcher.fetch(request, session, sessionManager)
            sabrData = result.data
            sabrReadPosition = 0
            result.metadata?.let(sessionManager::recordMetadata)
            return sabrData?.size?.toLong() ?: 0L
        } catch (error: UnsupportedOperationException) {
            logger.e(TAG, error) { "SABR segment fetcher unsupported for uri=${dataSpec.uri}" }
            throw IOException("SABR segment fetcher not configured", error)
        }
    }

    private fun buildSegmentRequest(dataSpec: DataSpec): SabrSegmentRequest {
        val key = dataSpec.uri.getQueryParameter("key")
            ?: run {
                logger.w(TAG) { "SABR URI missing key parameter uri=${dataSpec.uri}" }
                throw IOException("SABR URI missing key parameter: ${dataSpec.uri}")
            }
        val format = sessionManager.formatForKey(key)
        return SabrSegmentRequest(
            dataSpec = dataSpec,
            key = key,
            format = format,
        )
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val data = sabrData
        if (data != null) {
            if (sabrReadPosition >= data.size) {
                return C.RESULT_END_OF_INPUT
            }
            val bytesToCopy = min(length, data.size - sabrReadPosition)
            System.arraycopy(data, sabrReadPosition, buffer, offset, bytesToCopy)
            sabrReadPosition += bytesToCopy
            return bytesToCopy
        }
        val activeDelegate = delegate ?: return C.RESULT_END_OF_INPUT
        return activeDelegate.read(buffer, offset, length)
    }

    override fun getUri(): Uri? {
        return delegate?.uri ?: currentUri
    }

    @Throws(IOException::class)
    override fun close() {
        try {
            delegate?.close()
        } finally {
            delegate = null
            sabrData = null
            sabrReadPosition = 0
            currentUri = null
            logger.d(TAG) { "DataSource closed" }
        }
    }

    private fun isSabrUri(uri: Uri?): Boolean {
        return uri?.scheme?.equals(SABR_SCHEME, ignoreCase = true) == true
    }

    companion object {
        private const val SABR_SCHEME = "sabr"
        private const val TAG = "SabrDataSource"
    }
}
