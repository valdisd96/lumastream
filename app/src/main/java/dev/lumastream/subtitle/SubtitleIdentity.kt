package dev.lumastream.subtitle

import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import java.security.MessageDigest
import java.util.Locale

fun subtitleVideoIdentity(infoHash: String, fileIndex: Int, path: String, sizeBytes: Long): String {
    val hash = infoHash.lowercase(Locale.ROOT)
    require((hash.length == 40 || hash.length == 64) && hash.all { it in '0'..'9' || it in 'a'..'f' }) { "A resolved torrent hash is required for subtitle timing." }
    require(fileIndex >= 0 && path.isNotBlank() && sizeBytes >= 0) { "An exact torrent video is required for subtitle timing." }
    return "video:" + identityDigest(hash, fileIndex.toString(), path, sizeBytes.toString())
}

/** Content, not a temporary URI or display name, identifies imported and downloaded text. */
fun externalSubtitleIdentity(bytes: ByteArray): String = "external:" + MessageDigest.getInstance("SHA-256").digest(bytes).hex()

@androidx.annotation.OptIn(UnstableApi::class)
fun embeddedSubtitleIdentity(groupId: String, trackIndex: Int, format: Format): String {
    require(trackIndex >= 0)
    val digest = MessageDigest.getInstance("SHA-256")
    listOf(groupId, trackIndex.toString(), format.id, format.language, format.label,
        format.sampleMimeType, format.containerMimeType, format.codecs,
        format.roleFlags.toString(), format.selectionFlags.toString(), format.accessibilityChannel.toString(),
    ).forEach { digest.field(it?.toByteArray(Charsets.UTF_8)) }
    format.initializationData.forEach { digest.field(it) }
    return "embedded:" + digest.digest().hex()
}

internal fun identityDigest(vararg fields: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fields.forEach { digest.field(it.toByteArray(Charsets.UTF_8)) }
    return digest.digest().hex()
}

private fun MessageDigest.field(bytes: ByteArray?) {
    val length = bytes?.size ?: -1
    update((length ushr 24).toByte())
    update((length ushr 16).toByte())
    update((length ushr 8).toByte())
    update(length.toByte())
    if (bytes != null) update(bytes)
}

private fun ByteArray.hex(): String {
    val alphabet = "0123456789abcdef"
    return buildString(size * 2) {
        for (byte in this@hex) {
            val value = byte.toInt() and 0xff
            append(alphabet[value ushr 4])
            append(alphabet[value and 15])
        }
    }
}
