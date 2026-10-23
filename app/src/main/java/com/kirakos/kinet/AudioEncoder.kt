package com.kirakos.kinet

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.io.FileInputStream

object AudioEncoder {

    private const val TAG = "AudioEncoder"

    /**
     * Encodes a 16-bit 44.1kHz Stereo PCM WAV file into an AAC M4A file
     * using Android's native hardware-accelerated MediaCodec and MediaMuxer.
     */
    fun encodeWavToM4a(
        wavFile: File,
        outputM4aFile: File,
        sampleRate: Int = 44100,
        channels: Int = 2,
        bitRate: Int = 256000,
        onProgress: ((Float) -> Unit)? = null
    ): Boolean {
        if (!wavFile.exists() || wavFile.length() <= 44) {
            Log.e(TAG, "Invalid or empty WAV file: ${wavFile.absolutePath}")
            return false
        }

        outputM4aFile.parentFile?.mkdirs()
        if (outputM4aFile.exists()) {
            outputM4aFile.delete()
        }

        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var fis: FileInputStream? = null

        try {
            val totalPcmBytes = wavFile.length() - 44
            var bytesReadTotal = 0L

            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                sampleRate,
                channels
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }

            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            muxer = MediaMuxer(outputM4aFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var audioTrackIndex = -1
            var muxerStarted = false

            fis = FileInputStream(wavFile)
            // Skip standard 44-byte WAV header
            fis.skip(44)

            val bufferInfo = MediaCodec.BufferInfo()
            val tempBuffer = ByteArray(8192)
            var isInputDone = false
            var isOutputDone = false
            var presentationTimeUs = 0L
            val bytesPerSample = 2 * channels // 16-bit stereo = 4 bytes per frame

            while (!isOutputDone) {
                // Feed input to codec
                if (!isInputDone) {
                    val inputBufferIndex = codec.dequeueInputBuffer(10_000L)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                        if (inputBuffer != null) {
                            inputBuffer.clear()
                            val bytesToRead = minOf(tempBuffer.size, inputBuffer.remaining())
                            val read = fis.read(tempBuffer, 0, bytesToRead)

                            if (read <= 0) {
                                codec.queueInputBuffer(
                                    inputBufferIndex,
                                    0,
                                    0,
                                    presentationTimeUs,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                isInputDone = true
                            } else {
                                inputBuffer.put(tempBuffer, 0, read)
                                codec.queueInputBuffer(
                                    inputBufferIndex,
                                    0,
                                    read,
                                    presentationTimeUs,
                                    0
                                )
                                val frames = read / bytesPerSample
                                presentationTimeUs += (frames * 1_000_000L) / sampleRate
                                bytesReadTotal += read

                                if (totalPcmBytes > 0) {
                                    onProgress?.invoke((bytesReadTotal.toFloat() / totalPcmBytes).coerceIn(0f, 1f))
                                }
                            }
                        }
                    }
                }

                // Drain output from codec
                var outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                while (outputBufferIndex >= 0) {
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        bufferInfo.size = 0
                    }

                    if (bufferInfo.size > 0 && muxerStarted) {
                        val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                        if (outputBuffer != null) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(audioTrackIndex, outputBuffer, bufferInfo)
                        }
                    }

                    codec.releaseOutputBuffer(outputBufferIndex, false)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isOutputDone = true
                        break
                    }

                    outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0L)
                }

                if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!muxerStarted) {
                        val newFormat = codec.outputFormat
                        audioTrackIndex = muxer.addTrack(newFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                }
            }

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error encoding WAV to M4A: ${e.message}", e)
            if (outputM4aFile.exists()) {
                outputM4aFile.delete()
            }
            return false
        } finally {
            try { fis?.close() } catch (_: Exception) {}
            try {
                codec?.stop()
                codec?.release()
            } catch (_: Exception) {}
            try {
                if (muxer != null) {
                    muxer.stop()
                    muxer.release()
                }
            } catch (_: Exception) {}
        }
    }
}
