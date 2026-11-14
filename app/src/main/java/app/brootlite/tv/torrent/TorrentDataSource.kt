package app.brootlite.tv.torrent

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.EOFException
import java.io.IOException

@UnstableApi
class TorrentDataSource(
    private val engine: SlidingWindowEngine
) : BaseDataSource(/* isNetwork = */ true) {

    private var uri: Uri? = null
    private var readPosition: Long = 0L
    private var bytesRemaining: Long = 0L
    private var opened: Boolean = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)

        val totalFileLength = engine.awaitMetadataReady()
        if (dataSpec.position > totalFileLength) {
            throw EOFException("Position ${dataSpec.position} beyond torrent file size $totalFileLength")
        }

        readPosition = dataSpec.position
        engine.onDataSourceOpen(readPosition)

        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            totalFileLength - dataSpec.position
        }

        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val toRead = minOf(length.toLong(), bytesRemaining).toInt()
        val bytesRead = engine.readAt(readPosition, buffer, offset, toRead)
        if (bytesRead <= 0) {
            return C.RESULT_END_OF_INPUT
        }

        readPosition += bytesRead
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            bytesRemaining -= bytesRead
        }
        bytesTransferred(bytesRead)
        return bytesRead
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    class Factory(private val engine: SlidingWindowEngine) : DataSource.Factory {
        override fun createDataSource(): DataSource = TorrentDataSource(engine)
    }
}
