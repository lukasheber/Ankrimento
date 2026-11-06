// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.annotation.WorkerThread
import java.io.File
import java.nio.ByteBuffer

/**
 * A segment of a local video copied into an MP4, for desktop's "Create Mobile Clip". Desktop re-encodes
 * with FFmpeg; the phone has no FFmpeg, so the samples are copied as they are (no re-encoding, fast,
 * no quality loss). A copy must start on a key frame, so the clip starts at the last one at or before
 * the chosen start: [cut] returns where it really starts.
 */
internal object VideoClipper {
    /** The video's codecs cannot go into an MP4 (a WebM's VP8/VP9, for instance). */
    class UnsupportedFormat(
        cause: Throwable,
    ) : Exception(cause)

    /** Copies [startSeconds]..[endSeconds] of [source] into [destination]; returns the clip's real start in seconds. */
    @WorkerThread
    fun cut(
        source: File,
        destination: File,
        startSeconds: Double,
        endSeconds: Double,
    ): Double {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(source.path)
            val tracks = mutableMapOf<Int, Int>()
            var bufferSize = DEFAULT_BUFFER
            var rotation = 0
            val output =
                try {
                    MediaMuxer(destination.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                } catch (error: Exception) {
                    throw UnsupportedFormat(error)
                }
            muxer = output
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                tracks[index] =
                    try {
                        output.addTrack(format)
                    } catch (error: Exception) {
                        throw UnsupportedFormat(error)
                    }
                extractor.selectTrack(index)
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    bufferSize = maxOf(bufferSize, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                }
                if (mime.startsWith("video/") && format.containsKey(MediaFormat.KEY_ROTATION)) {
                    rotation = format.getInteger(MediaFormat.KEY_ROTATION)
                }
            }
            if (tracks.isEmpty()) throw UnsupportedFormat(IllegalStateException("no audio or video track"))
            output.setOrientationHint(rotation)
            output.start()

            val endUs = (endSeconds * MICROSECONDS).toLong()
            extractor.seekTo((startSeconds * MICROSECONDS).toLong(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val firstUs = extractor.sampleTime.coerceAtLeast(0)
            val buffer = ByteBuffer.allocate(bufferSize)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val time = extractor.sampleTime
                if (time > endUs) break
                // A track's samples from before the first key frame (audio, interleaved) are left out.
                if (time >= firstUs) {
                    info.set(
                        0,
                        size,
                        time - firstUs,
                        if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0,
                    )
                    output.writeSampleData(tracks.getValue(extractor.sampleTrackIndex), buffer, info)
                }
                extractor.advance()
            }
            output.stop()
            return firstUs / MICROSECONDS.toDouble()
        } finally {
            runCatching { muxer?.release() }
            extractor.release()
        }
    }

    private const val MICROSECONDS = 1_000_000L
    private const val DEFAULT_BUFFER = 1 shl 20
}
