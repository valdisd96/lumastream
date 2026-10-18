package dev.lumastream

import android.content.Context
import android.media.MediaCodecList
import dev.lumastream.catalog.CatalogSource

/** Provider labels and advertised decoder limits are hints, not a playback guarantee. */
class SourceCompatibility(@Suppress("UNUSED_PARAMETER") context: Context) {
    // MediaCodecList is process-wide; do not retain an Activity just to inspect it.
    private val inventory = readDecoderInventory()

    fun warnings(source: CatalogSource): List<String> {
        val hints = parseSourceHints(source)
        return assessSourceCompatibility(hints, inventory).map { warning ->
            when (warning) {
                SourceCompatibilityWarning.STEREOSCOPIC ->
                    "Provider labels this source as 3D/stereoscopic. It may appear split or doubled; choose a 2D source for normal playback."
                SourceCompatibilityWarning.CODEC_UNAVAILABLE ->
                    "Provider reports ${hints.codec!!.label}, but this device advertises no decoder for it. Try a source with a different codec."
                SourceCompatibilityWarning.RESOLUTION_UNSUPPORTED ->
                    "Provider reports ${hints.dimensions!!.label} ${hints.codec!!.label}, beyond this device's advertised decoder support. Try a lower-resolution source or another codec."
            }
        }
    }
}

internal enum class SourceVideoCodec(val mimeType: String, val label: String, aliases: String) {
    AVC("video/avc", "H.264", "[hx][ ._-]?264|avc"),
    HEVC("video/hevc", "HEVC/H.265", "[hx][ ._-]?265|hevc"),
    AV1("video/av01", "AV1", "av1"),
    VP9("video/x-vnd.on2.vp9", "VP9", "vp[ ._-]?9"),
    VP8("video/x-vnd.on2.vp8", "VP8", "vp[ ._-]?8"),
    MPEG2("video/mpeg2", "MPEG-2", "mpeg[ ._-]?2"),
    MPEG4("video/mp4v-es", "MPEG-4", "mpeg[ ._-]?4|xvid|divx"),
    VC1("video/wvc1", "VC-1", "vc[ ._-]?1");

    val pattern = Regex("(?i)(?<![a-z0-9])(?:$aliases)(?![a-z0-9])")
}

// A 1080p label reports a height, not a width or aspect ratio.
internal data class SourceDimensions(val width: Int?, val height: Int) {
    val label: String get() = if (width == null) "${height}p" else "${width}×$height"
}

internal data class SourceHints(
    val stereoscopic: Boolean,
    val codec: SourceVideoCodec?,
    val dimensions: SourceDimensions?,
)

internal enum class SourceCompatibilityWarning { STEREOSCOPIC, CODEC_UNAVAILABLE, RESOLUTION_UNSUPPORTED }

/** Null size support means the capability query failed or was unavailable. */
internal data class SourceDecoderInventory(
    val decoders: Map<SourceVideoCodec, List<(SourceDimensions) -> Boolean?>>,
    val complete: Boolean = true,
)

internal fun assessSourceCompatibility(
    hints: SourceHints,
    inventory: SourceDecoderInventory,
): List<SourceCompatibilityWarning> = buildList {
    if (hints.stereoscopic) add(SourceCompatibilityWarning.STEREOSCOPIC)
    val codec = hints.codec ?: return@buildList
    // A decoder whose types could not be inspected might support this source.
    if (!inventory.complete) return@buildList
    val decoders = inventory.decoders[codec].orEmpty()
    if (decoders.isEmpty()) {
        add(SourceCompatibilityWarning.CODEC_UNAVAILABLE)
        return@buildList
    }
    val dimensions = hints.dimensions ?: return@buildList
    // Warn only when every candidate explicitly rejects the size. One unknown or
    // supporting decoder is enough to avoid claiming the device cannot decode it.
    if (decoders.all { supports -> supports(dimensions) == false }) {
        add(SourceCompatibilityWarning.RESOLUTION_UNSUPPORTED)
    }
}

internal fun parseSourceHints(source: CatalogSource): SourceHints {
    val metadata = listOfNotNull(source.filename, source.displayName, source.description, source.quality)
        .joinToString("\n")
    val codecs = SourceVideoCodec.entries.filter { it.pattern.containsMatchIn(metadata) }
    val sizes = DIMENSIONS.findAll(metadata).map { match ->
        SourceDimensions(match.groupValues[1].toInt(), match.groupValues[2].toInt())
    }.distinct().toList()
    val heights = HEIGHT.findAll(metadata).map { match ->
        when (val label = match.groupValues[1].lowercase()) {
            "4k" -> 2160
            "8k" -> 4320
            else -> label.dropLast(1).toInt()
        }
    }.distinct().toList()
    // Exact dimensions can legitimately differ from a quality tier (1920x800
    // labelled 1080p). Conflicting exact sizes or codecs remain unknown.
    val dimensions = if (sizes.isNotEmpty()) sizes.singleOrNull()
        else heights.singleOrNull()?.let { SourceDimensions(null, it) }
    return SourceHints(STEREOSCOPIC.containsMatchIn(metadata), codecs.singleOrNull(), dimensions)
}

private val DIMENSIONS = Regex("(?i)(?<![a-z0-9])([1-9][0-9]{2,4})[ ]*[x×][ ]*([1-9][0-9]{2,4})(?![a-z0-9])")
private val HEIGHT = Regex("(?i)(?<![a-z0-9])(480p|576p|720p|1080p|1440p|2160p|4320p|4k|8k)(?![a-z0-9])")
private val STEREOSCOPIC = Regex(
    "(?i)(?<![a-z0-9])(?:3d|stereoscopic|[hf]sbs|[hf]tab|[hf]ou|sbs|" +
        "(?:half|full)[ ._-]*(?:sbs|tab|ou)|side[ ._-]+by[ ._-]+side|top[ ._-]+(?:and[ ._-]+)?bottom)(?![a-z0-9])",
)

private fun readDecoderInventory(): SourceDecoderInventory {
    val decoders = mutableMapOf<SourceVideoCodec, MutableList<(SourceDimensions) -> Boolean?>>()
    val infos = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
    } catch (_: Exception) {
        return SourceDecoderInventory(emptyMap(), complete = false)
    }
    var complete = true
    for (info in infos) {
        val types = try {
            if (info.isEncoder) continue
            info.supportedTypes
        } catch (_: Exception) {
            complete = false
            continue
        }
        for (codec in SourceVideoCodec.entries) {
            val type = types.firstOrNull { it.equals(codec.mimeType, ignoreCase = true) } ?: continue
            val video = try {
                info.getCapabilitiesForType(type).videoCapabilities
            } catch (_: Exception) {
                null
            }
            decoders.getOrPut(codec) { mutableListOf() }.add { dimensions ->
                if (video == null) null else try {
                    val width = dimensions.width
                    if (width == null) video.supportedHeights.contains(dimensions.height)
                    else video.isSizeSupported(width, dimensions.height)
                } catch (_: Exception) {
                    null
                }
            }
        }
    }
    return SourceDecoderInventory(decoders, complete)
}
