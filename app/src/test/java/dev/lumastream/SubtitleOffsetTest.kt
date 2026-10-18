package dev.lumastream

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.SampleStream
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

@androidx.annotation.OptIn(UnstableApi::class)
class SubtitleOffsetTest {
    @Test fun positiveAndNegativeOffsetsShiftCueBoundariesButNotAudioVideo() {
        for (delay in listOf(-2_000_000L, 2_000_000L)) {
            val timing = SubtitleTiming(delay)
            val subtitles = OffsetStream(Timestamps(5_000_000, 7_000_000), true, timing)
            val video = OffsetStream(Timestamps(5_000_000, 7_000_000), false, timing)
            val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
            val holder = FormatHolder()
            for (boundary in listOf(5_000_000L, 7_000_000L)) {
                assertEquals(C.RESULT_BUFFER_READ, subtitles.readData(holder, buffer, 0))
                assertEquals(boundary + delay, buffer.timeUs)
                assertEquals(C.RESULT_BUFFER_READ, video.readData(holder, buffer, 0))
                assertEquals(boundary, buffer.timeUs)
            }
            subtitles.readData(holder, buffer, 0)
            assertEquals(true, buffer.isEndOfStream)
            assertEquals(0L, buffer.timeUs)
        }
    }

    @Test fun skippingUsesSubtitleClockAndRetainsExactBoundary() {
        val later = OffsetStream(Timestamps(5_000_000, 7_000_000), true, SubtitleTiming(2_000_000))
        assertEquals(0, later.skipData(7_000_000))
        assertEquals(1, later.skipData(7_000_001))
        val earlier = OffsetStream(Timestamps(5_000_000, 7_000_000), true, SubtitleTiming(-2_000_000))
        assertEquals(0, earlier.skipData(3_000_000))
        assertEquals(1, earlier.skipData(3_000_001))
    }

    @Test fun seekingRetainsDelayedCuesWithoutMovingThePlaybackPosition() {
        var sourcePosition = -1L
        val source = Proxy.newProxyInstance(MediaPeriod::class.java.classLoader, arrayOf(MediaPeriod::class.java)) { _, method, args ->
            when (method.name) {
                "seekToUs" -> (args!![0] as Long).also { sourcePosition = it }
                "readDiscontinuity" -> C.TIME_UNSET
                else -> error("Unexpected call: ${method.name}")
            }
        } as MediaPeriod
        val later = OffsetPeriod(source, SubtitleTiming(2_000_000))
        assertEquals(10_000_000L, later.seekToUs(10_000_000))
        assertEquals(8_000_000L, sourcePosition)
        assertEquals(1_000_000L, later.seekToUs(1_000_000))
        assertEquals(0L, sourcePosition)
        assertEquals(C.TIME_UNSET, later.readDiscontinuity())
        val earlier = OffsetPeriod(source, SubtitleTiming(-2_000_000))
        assertEquals(10_000_000L, earlier.seekToUs(10_000_000))
        assertEquals(10_000_000L, sourcePosition)
    }

    @Test fun sampleRelativeAndAbsoluteSubtitleOffsetsAreNotDoubleShifted() {
        val relative = Format.Builder().setSubsampleOffsetUs(Format.OFFSET_SAMPLE_RELATIVE).build()
        assertSame(relative, shiftSubtitleFormat(relative, 2_000_000))
        val absolute = Format.Builder().setSubsampleOffsetUs(8_000_000).build()
        assertEquals(10_000_000L, shiftSubtitleFormat(absolute, 2_000_000).subsampleOffsetUs)
        assertEquals(6_000_000L, shiftSubtitleFormat(absolute, -2_000_000).subsampleOffsetUs)
    }

    private class Timestamps(vararg val times: Long) : SampleStream {
        private var index = 0
        override fun isReady() = true
        override fun maybeThrowError() = Unit
        override fun skipData(positionUs: Long): Int {
            val before = index
            while (index < times.size && times[index] < positionUs) index++
            return index - before
        }
        override fun readData(holder: FormatHolder, buffer: DecoderInputBuffer, flags: Int): Int {
            buffer.clear()
            if (index == times.size) {
                buffer.timeUs = 0
                buffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM)
            } else {
                buffer.timeUs = times[index++]
            }
            return C.RESULT_BUFFER_READ
        }
    }
}
