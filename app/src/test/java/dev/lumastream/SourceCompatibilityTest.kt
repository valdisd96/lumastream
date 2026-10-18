package dev.lumastream

import dev.lumastream.catalog.CatalogSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceCompatibilityTest {
    private val source = CatalogSource("Provider", "a".repeat(40), null, null, "", "", null,
        emptyList(), null, null, emptyList())
    private val avc = SourceVideoCodec.AVC
    private val fullHd: (SourceDimensions) -> Boolean? = { size ->
        size.height <= 1080 && (size.width == null || size.width <= 1920)
    }
    private val inventory = SourceDecoderInventory(mapOf(avc to listOf(fullHd)))

    @Test fun titleYearsEpisodeNumbersAndBareNumbersAreNotVideoHints() {
        val hints = parseSourceHints(source.copy(
            filename = "2001.A.Space.Odyssey.1968.mkv",
            displayName = "Provider 264 265 2160",
            description = "S01E02 1x02 1920 1080 3days 4kids",
        ))
        assertNull(hints.codec)
        assertNull(hints.dimensions)
        assertFalse(hints.stereoscopic)
        assertEquals(emptyList<SourceCompatibilityWarning>(), assessSourceCompatibility(hints, inventory))
    }

    @Test fun explicitCodecAndDimensionsAreReadFromReleaseFilename() {
        val hints = parseSourceHints(source.copy(filename = "Sintel.3D.1920x2160.H264.mkv"))
        assertEquals(avc, hints.codec)
        assertEquals(SourceDimensions(1920, 2160), hints.dimensions)
        assertEquals(listOf(SourceCompatibilityWarning.STEREOSCOPIC,
            SourceCompatibilityWarning.RESOLUTION_UNSUPPORTED), assessSourceCompatibility(hints, inventory))
    }

    @Test fun releaseCodecAliasesAreEquivalentButSubstringsAreNot() {
        for (alias in listOf("x265", "H.265", "h-265", "HEVC")) {
            assertEquals(SourceVideoCodec.HEVC, parseSourceHints(source.copy(filename = "Movie.$alias.mkv")).codec)
        }
        assertNull(parseSourceHints(source.copy(description = "AV10 x2650 myhevc avcollection")).codec)
    }

    @Test fun providerDisplayNameDescriptionAndQualityCanSupplyHints() {
        val hints = parseSourceHints(source.copy(displayName = "Provider HEVC", description = "Half-SBS", quality = "2160p"))
        assertEquals(SourceVideoCodec.HEVC, hints.codec)
        assertEquals(SourceDimensions(null, 2160), hints.dimensions)
        assertTrue(hints.stereoscopic)
    }

    @Test fun stereoscopicWarningsDoNotRequireKnownCodecOrDimensions() {
        for (marker in listOf("3D", "HSBS", "FSBS", "HTAB", "Half-TAB", "Full-OU", "side-by-side", "top-and-bottom")) {
            val hints = parseSourceHints(source.copy(filename = "Movie.$marker.mkv"))
            assertEquals(marker, listOf(SourceCompatibilityWarning.STEREOSCOPIC),
                assessSourceCompatibility(hints, SourceDecoderInventory(emptyMap(), complete = false)))
        }
        assertFalse(parseSourceHints(source.copy(description = "TAB OU 3days SBSnews")).stereoscopic)
    }

    @Test fun qualityTiersDoNotInventAspectRatioAndBareUhdIsUnknown() {
        for ((marker, height) in listOf("720p" to 720, "1080p" to 1080, "4K" to 2160, "8k" to 4320)) {
            assertEquals(SourceDimensions(null, height), parseSourceHints(source.copy(quality = marker)).dimensions)
        }
        assertNull(parseSourceHints(source.copy(description = "UHD HDR 10bit Dolby Vision")).dimensions)
    }

    @Test fun exactCroppedDimensionsTakePrecedenceOverQualityTier() {
        val hints = parseSourceHints(source.copy(filename = "Movie.1920x800.H264.mkv", quality = "1080p"))
        assertEquals(SourceDimensions(1920, 800), hints.dimensions)
        assertEquals(emptyList<SourceCompatibilityWarning>(), assessSourceCompatibility(hints, inventory))
    }

    @Test fun contradictoryCodecAndResolutionHintsStayUnknown() {
        val hints = parseSourceHints(source.copy(filename = "Movie.H264.1920x1080.mkv",
            description = "HEVC 3840x2160 3D", quality = "2160p"))
        assertNull(hints.codec)
        assertNull(hints.dimensions)
        assertEquals(listOf(SourceCompatibilityWarning.STEREOSCOPIC),
            assessSourceCompatibility(hints, SourceDecoderInventory(emptyMap())))
        assertNull(parseSourceHints(source.copy(description = "720p / 1080p")).dimensions)
    }

    @Test fun missingCodecDiffersFromUnsupportedSizeEvenWhenDimensionsUnknown() {
        val hints = parseSourceHints(source.copy(filename = "Movie.AV1.mkv"))
        assertEquals(listOf(SourceCompatibilityWarning.CODEC_UNAVAILABLE), assessSourceCompatibility(hints, inventory))
    }

    @Test fun unknownCodecDoesNotAssumeH264ToRejectResolution() {
        val hints = parseSourceHints(source.copy(filename = "Movie.7680x4320.mkv"))
        assertEquals(emptyList<SourceCompatibilityWarning>(), assessSourceCompatibility(hints, inventory))
    }

    @Test fun knownCodecWithUnknownResolutionDoesNotRejectSource() {
        val hints = parseSourceHints(source.copy(filename = "Movie.H264.mkv"))
        assertEquals(emptyList<SourceCompatibilityWarning>(), assessSourceCompatibility(hints, inventory))
    }

    @Test fun exactLimitsAreAcceptedAndEitherExceededDimensionWarns() {
        val hints = SourceHints(false, avc, SourceDimensions(1920, 1080))
        assertEquals(emptyList<SourceCompatibilityWarning>(), assessSourceCompatibility(hints, inventory))
        for (size in listOf(SourceDimensions(1921, 1080), SourceDimensions(1920, 1081), SourceDimensions(null, 1081))) {
            assertEquals(listOf(SourceCompatibilityWarning.RESOLUTION_UNSUPPORTED),
                assessSourceCompatibility(hints.copy(dimensions = size), inventory))
        }
    }

    @Test fun oneSupportingDecoderPreventsWarningRegardlessOfEnumerationOrder() {
        val hints = SourceHints(false, avc, SourceDimensions(3840, 2160))
        val ultraHd: (SourceDimensions) -> Boolean? = { true }
        for (decoders in listOf(listOf(fullHd, ultraHd), listOf(ultraHd, fullHd))) {
            assertEquals(emptyList<SourceCompatibilityWarning>(),
                assessSourceCompatibility(hints, SourceDecoderInventory(mapOf(avc to decoders))))
        }
    }

    @Test fun failedCapabilityQueryIsNotUnsupportedEvenIfOtherDecoderRejectsSize() {
        val hints = SourceHints(false, avc, SourceDimensions(3840, 2160))
        val unknown: (SourceDimensions) -> Boolean? = { null }
        for (decoders in listOf(listOf(unknown), listOf(fullHd, unknown), listOf(unknown, fullHd))) {
            assertEquals(emptyList<SourceCompatibilityWarning>(),
                assessSourceCompatibility(hints, SourceDecoderInventory(mapOf(avc to decoders))))
        }
    }

    @Test fun incompleteInventoryCannotProveMissingCodecOrUnsupportedSize() {
        val incomplete = inventory.copy(complete = false)
        for (codec in listOf(avc, SourceVideoCodec.HEVC)) {
            assertEquals(emptyList<SourceCompatibilityWarning>(),
                assessSourceCompatibility(SourceHints(false, codec, SourceDimensions(3840, 2160)), incomplete))
        }
    }
}
