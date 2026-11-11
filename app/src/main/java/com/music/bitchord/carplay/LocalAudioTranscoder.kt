package com.music.bitchord.carplay

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import com.music.bitchord.data.DebugLog as Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Transcodes downloaded audio that iOS AVPlayer cannot decode natively
 * (such as WebM Opus) into AAC in an M4A container on Android locally.
 *
 * This ensures zero mobile data / tethering usage when iPhone CarPlay
 * plays songs that are downloaded on Android.
 */
object LocalAudioTranscoder {
    private const val TAG = "LocalAudioTranscoder"
    private val inFlight = ConcurrentHashMap<String, Mutex>()

    fun getCachedFile(context: Context, videoId: String): File? {
        val dir = File(context.cacheDir, "transcoded_ios")
        val file = File(dir, "$videoId.m4a")
        return if (file.exists() && file.length() > 1024L) file else null
    }

    suspend fun getOrTranscode(context: Context, videoId: String, sourceUri: Uri): File? = withContext(Dispatchers.IO) {
        val cached = getCachedFile(context, videoId)
        if (cached != null) return@withContext cached

        val mutex = inFlight.computeIfAbsent(videoId) { Mutex() }
        mutex.withLock {
            val again = getCachedFile(context, videoId)
            if (again != null) return@withLock again

            transcodeInternal(context, videoId, sourceUri)
        }
    }

    private fun transcodeInternal(context: Context, videoId: String, sourceUri: Uri): File? {
        val dir = File(context.cacheDir, "transcoded_ios")
        if (!dir.exists()) dir.mkdirs()
        val tempFile = File(dir, ".$videoId.tmp")
        val destFile = File(dir, "$videoId.m4a")
        tempFile.delete()

        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null

        try {
            extractor = MediaExtractor()
            if (sourceUri.scheme == "file") {
                val path = sourceUri.path ?: return null
                extractor.setDataSource(path)
            } else {
                extractor.setDataSource(context, sourceUri, null)
            }

            var audioTrackIndex = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    inputFormat = format
                    break
                }
            }

            if (audioTrackIndex < 0 || inputFormat == null) {
                Log.w(TAG, "No audio track found in $sourceUri")
                return null
            }

            extractor.selectTrack(audioTrackIndex)
            val inputMime = inputFormat.getString(MediaFormat.KEY_MIME) ?: "audio/opus"
            val sampleRate = if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else 48000
            val channelCount = if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else 2

            // Configure Opus decoder
            decoder = MediaCodec.createDecoderByType(inputMime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            // Configure AAC encoder (standard LC-AAC at 192kbps)
            val outputFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            muxer = MediaMuxer(tempFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var muxerTrackIndex = -1
            var muxerStarted = false

            val decBufferInfo = MediaCodec.BufferInfo()
            val encBufferInfo = MediaCodec.BufferInfo()

            var extractorDone = false
            var decoderDone = false
            var encoderDone = false

            val timeoutUs = 5000L

            while (!encoderDone) {
                // 1. Feed extractor into decoder
                if (!extractorDone) {
                    val inIndex = decoder.dequeueInputBuffer(timeoutUs)
                    if (inIndex >= 0) {
                        val inBuf = decoder.getInputBuffer(inIndex)
                        if (inBuf != null) {
                            val sampleSize = extractor.readSampleData(inBuf, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                extractorDone = true
                            } else {
                                val presentationTimeUs = extractor.sampleTime
                                decoder.queueInputBuffer(inIndex, 0, sampleSize, presentationTimeUs, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                // 2. Drain decoder PCM and feed into encoder
                if (!decoderDone) {
                    val outIndex = decoder.dequeueOutputBuffer(decBufferInfo, timeoutUs)
                    if (outIndex >= 0) {
                        val decBuf = decoder.getOutputBuffer(outIndex)
                        val isEos = (decBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0

                        if (decBufferInfo.size > 0 && decBuf != null) {
                            var encInIndex = encoder.dequeueInputBuffer(timeoutUs)
                            while (encInIndex < 0 && !encoderDone) {
                                val (t, s, d) = drainEncoder(encoder, muxer, encBufferInfo, muxerTrackIndex, muxerStarted)
                                muxerTrackIndex = t
                                muxerStarted = s
                                if (d) encoderDone = true
                                if (encoderDone) break
                                encInIndex = encoder.dequeueInputBuffer(timeoutUs)
                            }
                            if (encInIndex >= 0) {
                                val encInBuf = encoder.getInputBuffer(encInIndex)
                                if (encInBuf != null) {
                                    decBuf.position(decBufferInfo.offset)
                                    decBuf.limit(decBufferInfo.offset + decBufferInfo.size)
                                    encInBuf.put(decBuf)
                                    val flags = if (isEos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                                    encoder.queueInputBuffer(encInIndex, 0, decBufferInfo.size, decBufferInfo.presentationTimeUs, flags)
                                }
                            }
                        } else if (isEos) {
                            val encInIndex = encoder.dequeueInputBuffer(timeoutUs)
                            if (encInIndex >= 0) {
                                encoder.queueInputBuffer(encInIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            }
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                        if (isEos) decoderDone = true
                    }
                }

                // 3. Drain encoder to muxer
                val (t, s, d) = drainEncoder(encoder, muxer, encBufferInfo, muxerTrackIndex, muxerStarted)
                muxerTrackIndex = t
                muxerStarted = s
                if (d) encoderDone = true
            }

            if (muxerStarted) {
                muxer.stop()
            }
            muxer.release()
            muxer = null

            if (tempFile.exists() && tempFile.length() > 1024L) {
                if (tempFile.renameTo(destFile) || destFile.exists()) {
                    Log.i(TAG, "Successfully transcoded $videoId to local AAC (${destFile.length()} bytes)")
                    return destFile
                }
            }
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Transcode failed for $videoId: ${e.message}", e)
            tempFile.delete()
            return null
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { extractor?.release() }
            runCatching { muxer?.release() }
        }
    }

    private data class DrainResult(val trackIndex: Int, val started: Boolean, val eos: Boolean)

    private fun drainEncoder(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        bufferInfo: MediaCodec.BufferInfo,
        initialTrack: Int,
        initialStarted: Boolean,
    ): DrainResult {
        var track = initialTrack
        var started = initialStarted
        var eos = false
        val timeoutUs = 2000L

        while (true) {
            val status = encoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
            if (status == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break
            } else if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val newFormat = encoder.outputFormat
                track = muxer.addTrack(newFormat)
                muxer.start()
                started = true
            } else if (status >= 0) {
                val encodedData = encoder.getOutputBuffer(status)
                if (encodedData != null && bufferInfo.size > 0 && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && started) {
                    encodedData.position(bufferInfo.offset)
                    encodedData.limit(bufferInfo.offset + bufferInfo.size)
                    muxer.writeSampleData(track, encodedData, bufferInfo)
                }
                encoder.releaseOutputBuffer(status, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    eos = true
                    break
                }
            }
        }
        return DrainResult(track, started, eos)
    }
}
