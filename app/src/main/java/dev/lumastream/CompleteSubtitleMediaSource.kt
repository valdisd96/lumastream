package dev.lumastream

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator

/**
 * A bounded in-memory subtitle file must retain cues that started before an import/seek position.
 * SubtitleExtractor's initial nonzero seek otherwise omits an already-active cue. Decode the whole
 * text timeline from zero while reporting the requested clock to the merged audio/video period.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class CompleteSubtitleMediaSource(source: MediaSource) : WrappingMediaSource(source) {
    override fun createPeriod(id: MediaSource.MediaPeriodId, allocator: Allocator, startPositionUs: Long): MediaPeriod =
        CompleteSubtitlePeriod(super.createPeriod(id, allocator, 0))

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        super.releasePeriod((mediaPeriod as CompleteSubtitlePeriod).child)
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
private class CompleteSubtitlePeriod(val child: MediaPeriod) : MediaPeriod by child, MediaPeriod.Callback {
    private lateinit var callback: MediaPeriod.Callback
    private var requestedPositionUs = 0L

    override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
        this.callback = callback
        requestedPositionUs = positionUs
        child.prepare(this, 0)
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
        child.selectTracks(selections, mayRetainStreamFlags, streams, streamResetFlags, 0)
        requestedPositionUs = positionUs
        return positionUs
    }

    override fun seekToUs(positionUs: Long): Long {
        child.seekToUs(0)
        requestedPositionUs = positionUs
        return positionUs
    }

    override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long = positionUs
    override fun readDiscontinuity(): Long = if (child.readDiscontinuity() == C.TIME_UNSET) C.TIME_UNSET else requestedPositionUs

    // The complete text source is bounded before construction, unlike the torrent video source.
    override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) = Unit
    override fun reevaluateBuffer(positionUs: Long) = child.reevaluateBuffer(0)
    override fun continueLoading(loadingInfo: LoadingInfo): Boolean =
        child.continueLoading(loadingInfo.buildUpon().setPlaybackPositionUs(0).build())
}
