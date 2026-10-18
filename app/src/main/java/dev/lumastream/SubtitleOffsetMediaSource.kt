package dev.lumastream

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator

/** Shifts text on the media clock, without shifting audio/video or creating another torrent reader. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class SubtitleOffsetMediaSource(source: MediaSource, delayMs: Long) : WrappingMediaSource(source) {
    private val timing = SubtitleTiming(delayMs * 1_000)

    override fun createPeriod(id: MediaSource.MediaPeriodId, allocator: Allocator, startPositionUs: Long): MediaPeriod =
        OffsetPeriod(super.createPeriod(id, allocator, timing.prerollPosition(startPositionUs)), timing)

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        super.releasePeriod((mediaPeriod as OffsetPeriod).child)
    }
}

internal class SubtitleTiming(val delayUs: Long) {
    fun prerollPosition(positionUs: Long): Long = (positionUs - delayUs.coerceAtLeast(0)).coerceAtLeast(0)
    fun subtitlePosition(positionUs: Long): Long = (positionUs - delayUs).coerceAtLeast(0)
    fun sampleTime(timeUs: Long): Long = timeUs + delayUs
}

@androidx.annotation.OptIn(UnstableApi::class)
internal class OffsetPeriod(val child: MediaPeriod, private val timing: SubtitleTiming) : MediaPeriod by child, MediaPeriod.Callback {
    private lateinit var callback: MediaPeriod.Callback
    private var requestedPositionUs = 0L
    private var hasText = false

    override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
        this.callback = callback
        requestedPositionUs = positionUs
        child.prepare(this, timing.prerollPosition(positionUs))
    }

    override fun onPrepared(mediaPeriod: MediaPeriod) = callback.onPrepared(this)
    override fun onContinueLoadingRequested(source: MediaPeriod) = callback.onContinueLoadingRequested(this)

    override fun selectTracks(
        selections: Array<out ExoTrackSelection?>,
        mayRetainStreamFlags: BooleanArray,
        streams: Array<out SampleStream?>,
        streamResetFlags: BooleanArray,
        positionUs: Long,
    ): Long {
        val childStreams = Array<SampleStream?>(streams.size) { (streams[it] as? OffsetStream)?.child }
        val preroll = timing.prerollPosition(positionUs)
        val selectedPosition = child.selectTracks(selections, mayRetainStreamFlags, childStreams, streamResetFlags, preroll)
        hasText = false
        for (index in streams.indices) {
            val stream = childStreams[index]
            val text = selections[index]?.trackGroup?.type == C.TRACK_TYPE_TEXT
            hasText = hasText || text
            @Suppress("UNCHECKED_CAST")
            (streams as Array<SampleStream?>)[index] = if (stream == null) null else {
                val previous = streams[index] as? OffsetStream
                if (previous?.child === stream && previous.text == text) previous else OffsetStream(stream, text, timing)
            }
        }
        // Keep the renderer reset position on the AV clock. Media3 1.5.1 classifies codec
        // output before that position as decode-only, including audio bypass batches.
        requestedPositionUs = positionUs + selectedPosition - preroll
        // Earlier source samples are retained only to recover delayed subtitle cues.
        return requestedPositionUs
    }

    override fun seekToUs(positionUs: Long): Long {
        val preroll = timing.prerollPosition(positionUs)
        requestedPositionUs = positionUs + child.seekToUs(preroll) - preroll
        return requestedPositionUs
    }

    override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long {
        val preroll = timing.prerollPosition(positionUs)
        return positionUs + child.getAdjustedSeekPositionUs(preroll, seekParameters) - preroll
    }

    override fun readDiscontinuity(): Long {
        val position = child.readDiscontinuity()
        return if (position == C.TIME_UNSET) position else position.coerceAtLeast(requestedPositionUs)
    }

    override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) =
        child.discardBuffer(timing.prerollPosition(positionUs), toKeyframe)

    override fun reevaluateBuffer(positionUs: Long) = child.reevaluateBuffer(timing.prerollPosition(positionUs))

    override fun continueLoading(loadingInfo: LoadingInfo): Boolean = child.continueLoading(
        loadingInfo.buildUpon().setPlaybackPositionUs(timing.prerollPosition(loadingInfo.playbackPositionUs)).build(),
    )

    // Earlier subtitles need data ahead of the AV clock; do not overstate the playable buffer.
    private fun bufferedPosition(positionUs: Long): Long =
        if (positionUs == C.TIME_END_OF_SOURCE || positionUs == C.TIME_UNSET) positionUs
        else (positionUs + if (hasText) timing.delayUs.coerceAtMost(0) else 0).coerceAtLeast(0)

    override fun getBufferedPositionUs(): Long = bufferedPosition(child.bufferedPositionUs)
    override fun getNextLoadPositionUs(): Long = bufferedPosition(child.nextLoadPositionUs)
}

@androidx.annotation.OptIn(UnstableApi::class)
internal class OffsetStream(
    val child: SampleStream,
    val text: Boolean,
    private val timing: SubtitleTiming,
) : SampleStream by child {
    override fun readData(formatHolder: FormatHolder, buffer: DecoderInputBuffer, readFlags: Int): Int {
        val result = child.readData(formatHolder, buffer, readFlags)
        if (text) {
            if (result == C.RESULT_BUFFER_READ && !buffer.isEndOfStream) buffer.timeUs = timing.sampleTime(buffer.timeUs)
            if (result == C.RESULT_FORMAT_READ) {
                formatHolder.format = formatHolder.format?.let { shiftSubtitleFormat(it, timing.delayUs) }
            }
        }
        return result
    }

    override fun skipData(positionUs: Long): Int = child.skipData(if (text) timing.subtitlePosition(positionUs) else positionUs)
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun shiftSubtitleFormat(format: Format, delayUs: Long): Format =
    if (format.subsampleOffsetUs == Format.OFFSET_SAMPLE_RELATIVE) format
    else format.buildUpon().setSubsampleOffsetUs(format.subsampleOffsetUs + delayUs).build()
