package com.example.data.engine

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext

/**
 * Lossless media muxer utilizing Android's native MediaMuxer and MediaExtractor.
 * Remuxes separated video and audio tracks (e.g., downloaded 1080p video + audio)
 * into a single MP4 container with zero transcoding overhead and zero quality loss.
 */
class MediaMuxerHelper {

    companion object {
        private const val TAG = "MediaMuxerHelper"
        private const val DEFAULT_BUFFER_SIZE = 8 * 1024 // 8 KB constant buffer for OOM prevention
    }

    /**
     * Muxes separate video and audio files into an output MP4 file.
     *
     * @param videoFile The downloaded video-only file.
     * @param audioFile The downloaded audio-only file.
     * @param outputFile Target combined output file.
     * @param onProgress Optional progress callback [0.0f - 1.0f].
     * @return Result containing the muxed File or failure exception.
     */
    suspend fun mux(
        videoFile: File,
        audioFile: File,
        outputFile: File,
        onProgress: ((Float) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        if (!videoFile.exists() || videoFile.length() == 0L) {
            return@withContext Result.failure(
                IllegalArgumentException("Video dosyası bulunamadı veya boş: ${videoFile.absolutePath}")
            )
        }
        if (!audioFile.exists() || audioFile.length() == 0L) {
            return@withContext Result.failure(
                IllegalArgumentException("Ses dosyası bulunamadı veya boş: ${audioFile.absolutePath}")
            )
        }

        outputFile.parentFile?.mkdirs()
        if (outputFile.exists()) {
            outputFile.delete()
        }

        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var isMuxerStarted = false

        try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            audioExtractor.setDataSource(audioFile.absolutePath)

            // 1. Locate video track
            var videoTrackIndex = -1
            var videoFormat: MediaFormat? = null
            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i
                    videoFormat = format
                    break
                }
            }

            if (videoTrackIndex == -1 || videoFormat == null) {
                return@withContext Result.failure(
                    IllegalStateException("Video dosyasında geçerli bir video parçası bulunamadı: ${videoFile.name}")
                )
            }

            // 2. Locate audio track
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until audioExtractor.trackCount) {
                val format = audioExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }

            if (audioTrackIndex == -1 || audioFormat == null) {
                return@withContext Result.failure(
                    IllegalStateException("Ses dosyasında geçerli bir ses parçası bulunamadı: ${audioFile.name}")
                )
            }

            videoExtractor.selectTrack(videoTrackIndex)
            audioExtractor.selectTrack(audioTrackIndex)

            // Extract durations for progress calculation
            val videoDurationUs = videoFormat.optLong(MediaFormat.KEY_DURATION, 0L)
            val audioDurationUs = audioFormat.optLong(MediaFormat.KEY_DURATION, 0L)
            val maxDurationUs = maxOf(videoDurationUs, audioDurationUs).coerceAtLeast(1L)

            // 3. Initialize MediaMuxer (MPEG-4 container)
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerVideoTrack = muxer.addTrack(videoFormat)
            val muxerAudioTrack = muxer.addTrack(audioFormat)

            muxer.start()
            isMuxerStarted = true

            // Allocate fixed 8KB buffer to prevent OutOfMemory errors
            val buffer = ByteBuffer.allocateDirect(DEFAULT_BUFFER_SIZE)
            val bufferInfo = MediaCodec.BufferInfo()

            var lastProgressUpdate = 0L

            // 4. Copy video samples (lossless bitstream copying)
            while (coroutineContext.isActive) {
                buffer.clear()
                val sampleSize = videoExtractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = videoExtractor.sampleTime
                bufferInfo.flags = videoExtractor.sampleFlags

                muxer.writeSampleData(muxerVideoTrack, buffer, bufferInfo)

                if (onProgress != null) {
                    val now = System.currentTimeMillis()
                    if (now - lastProgressUpdate > 100) {
                        val vProgress = (bufferInfo.presentationTimeUs.toFloat() / maxDurationUs * 0.5f).coerceIn(0f, 0.5f)
                        onProgress(vProgress)
                        lastProgressUpdate = now
                    }
                }

                if (!videoExtractor.advance()) break
            }

            // 5. Copy audio samples (lossless bitstream copying)
            while (coroutineContext.isActive) {
                buffer.clear()
                val sampleSize = audioExtractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = audioExtractor.sampleTime
                bufferInfo.flags = audioExtractor.sampleFlags

                muxer.writeSampleData(muxerAudioTrack, buffer, bufferInfo)

                if (onProgress != null) {
                    val now = System.currentTimeMillis()
                    if (now - lastProgressUpdate > 100) {
                        val aProgress = 0.5f + (bufferInfo.presentationTimeUs.toFloat() / maxDurationUs * 0.5f).coerceIn(0f, 0.5f)
                        onProgress(aProgress.coerceIn(0f, 1f))
                        lastProgressUpdate = now
                    }
                }

                if (!audioExtractor.advance()) break
            }

            if (!coroutineContext.isActive) {
                if (outputFile.exists()) outputFile.delete()
                return@withContext Result.failure(java.util.concurrent.CancellationException("Muxer işlemi iptal edildi."))
            }

            onProgress?.invoke(1.0f)
            Result.success(outputFile)
        } catch (e: Exception) {
            Log.e(TAG, "Media muxing hatası: ${e.message}", e)
            if (outputFile.exists()) {
                outputFile.delete()
            }
            Result.failure(e)
        } finally {
            try {
                videoExtractor.release()
            } catch (ignored: Exception) {}
            try {
                audioExtractor.release()
            } catch (ignored: Exception) {}
            if (isMuxerStarted) {
                try {
                    muxer?.stop()
                } catch (ignored: Exception) {}
            }
            try {
                muxer?.release()
            } catch (ignored: Exception) {}
        }
    }

    suspend fun mux(
        videoPath: String,
        audioPath: String,
        outputPath: String,
        onProgress: ((Float) -> Unit)? = null
    ): Result<File> = mux(File(videoPath), File(audioPath), File(outputPath), onProgress)

    private fun MediaFormat.optLong(key: String, default: Long): Long {
        return if (containsKey(key)) {
            try { getLong(key) } catch (_: Exception) { default }
        } else default
    }

    private fun MediaFormat.optInt(key: String, default: Int): Int {
        return if (containsKey(key)) {
            try { getInteger(key) } catch (_: Exception) { default }
        } else default
    }
}
