package dev.lumastream.torrent

import android.system.ErrnoException
import android.system.Os
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

internal object SparseFiles {
    init {
        System.loadLibrary("lumastream_storage")
    }

    @Throws(IOException::class)
    fun punch(path: String, offset: Long, length: Long) {
        if (offset < 0 || length < 0 || offset > Long.MAX_VALUE - length) {
            throw IOException("Invalid sparse-file range: offset=$offset, length=$length")
        }
        punchNative(nativePath(path), offset, length)
    }

    @Throws(IOException::class)
    fun allocatedBytes(path: String): Long = allocatedBytesNative(nativePath(path))

    /** Fail before downloading if this filesystem cannot actually reclaim cache blocks. */
    @Throws(IOException::class)
    fun verifySupport(directory: File) {
        val probe = File.createTempFile(".sparse-probe-", ".tmp", directory)
        var failure: Throwable? = null
        try {
            try {
                Os.chmod(probe.absolutePath, 0x180) // Owner read/write only (0600).
            } catch (error: ErrnoException) {
                throw IOException("Cannot secure sparse-cache probe", error)
            }
            val blockSize = 64 * 1024
            val retained = ByteArray(blockSize) { ((it % 251) + 1).toByte() }
            RandomAccessFile(probe, "rw").use { file ->
                repeat(3) { file.write(retained) }
                file.fd.sync()
                val allocatedBefore = allocatedBytes(probe.absolutePath)
                punch(probe.absolutePath, blockSize.toLong(), blockSize.toLong())
                file.fd.sync()
                if (file.length() != 3L * blockSize) {
                    throw IOException("Sparse cache hole punching changed the file length")
                }
                file.seek(0)
                val actual = ByteArray(blockSize)
                repeat(3) { block ->
                    file.readFully(actual)
                    val correct = if (block == 1) actual.all { it == 0.toByte() }
                        else actual.contentEquals(retained)
                    if (!correct) {
                        throw IOException("Sparse cache hole punching corrupted file contents")
                    }
                }
                if (allocatedBytes(probe.absolutePath) >= allocatedBefore) {
                    throw IOException("Sparse cache hole punching did not reclaim physical disk space")
                }
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            if (!probe.delete()) {
                val cleanupError = IOException("Cannot remove sparse-cache probe")
                if (failure != null) failure.addSuppressed(cleanupError) else throw cleanupError
            }
        }
    }

    private fun nativePath(path: String): ByteArray {
        if (path.isEmpty() || '\u0000' in path) throw IOException("Invalid cache file path")
        return (path + '\u0000').toByteArray(Charsets.UTF_8)
    }

    private external fun punchNative(path: ByteArray, offset: Long, length: Long)
    private external fun allocatedBytesNative(path: ByteArray): Long
}
