package com.kirakos.kinet

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class SeparationResult(
    val vocalsFile: File,
    val accompanimentFile: File,
    val durationSeconds: Float,
    val processingTimeSeconds: Float,
    val fileSizeBytes: Long = 0L
)

class AudioSeparator(private val context: Context) {

    private val tag = "AudioSeparator"
    private val sampleRate = 44100
    private val nFft = 4096
    private val hopLength = 1024
    private val numBins = 1024 // Model expects first 1024 frequency bins
    private val chunkSize = 512 // Model operates on chunks of 512 frames (~11.9s)

    // Precomputed Hann window and bit-reversal lookup
    private val hannWindow: FloatArray = FloatArray(nFft) { i ->
        (0.5 * (1.0 - cos(2.0 * PI * i / nFft))).toFloat()
    }
    private val bitRev: IntArray = IntArray(nFft) { i ->
        var rev = 0
        var x = i
        for (b in 0 until 12) { // 2^12 = 4096
            rev = (rev shl 1) or (x and 1)
            x = x shr 1
        }
        rev
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var vocalsSession: OrtSession? = null
    private var accompanimentSession: OrtSession? = null

    init {
        loadModels()
    }

    private fun loadModels() {
        val sessionOptions = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
        }
        val vocalsBytes = context.assets.open("vocals.fp16.onnx").use { it.readBytes() }
        val accompanimentBytes = context.assets.open("accompaniment.fp16.onnx").use { it.readBytes() }

        vocalsSession = env.createSession(vocalsBytes, sessionOptions)
        accompanimentSession = env.createSession(accompanimentBytes, sessionOptions)
        Log.i(tag, "ONNX sessions loaded successfully")
    }

    suspend fun separate(
        audioUri: Uri,
        onProgress: (String) -> Unit = {}
    ): SeparationResult = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        val inputSize = try {
            context.contentResolver.openFileDescriptor(audioUri, "r")?.use { it.statSize } ?: 0L
        } catch (e: Exception) {
            0L
        }

        onProgress("Preparing audio...")
        val (c0, c1) = decodeAudioToStereoPcm(audioUri)
        val audioDuration = c0.size.toFloat() / sampleRate
        Log.i(tag, "Decoded ${c0.size} samples (~${String.format("%.1f", audioDuration)}s)")

        if (c0.size < nFft) {
            throw IllegalArgumentException("Audio file is too short: minimum duration is ${String.format("%.1f", nFft / sampleRate.toFloat())}s.")
        }

        val numFrames = (c0.size - nFft) / hopLength + 1
        if (numFrames <= 0) {
            throw IllegalArgumentException("Audio file is too short: minimum duration is ${String.format("%.1f", nFft / sampleRate.toFloat())}s.")
        }

        val totalChunks = (numFrames + chunkSize - 1) / chunkSize
        Log.i(tag, "Total frames: $numFrames, divided into $totalChunks chunk(s)")

        // Reclaim any unreferenced decoder buffers before allocating stems
        System.gc()

        // Output audio accumulation arrays (vocals only, accompaniment is streamed on-the-fly to save ~120MB heap)
        val vocalsWav0: FloatArray
        val vocalsWav1: FloatArray
        try {
            vocalsWav0 = FloatArray(c0.size)
            vocalsWav1 = FloatArray(c1.size)
        } catch (e: OutOfMemoryError) {
            throw IllegalStateException("Track is too long to process on this device. Please try a shorter track.", e)
        }

        // Reusable memory buffers for 1 chunk of 512 frames (only ~4MB for standard float32)
        val chunkElements = 2 * 1 * chunkSize * numBins
        val byteBuffer = ByteBuffer.allocateDirect(chunkElements * 4).order(ByteOrder.nativeOrder())
        val floatBuffer = byteBuffer.asFloatBuffer()
        val tensorShape = longArrayOf(2, 1, chunkSize.toLong(), numBins.toLong())

        val vocalsInputName = vocalsSession!!.inputNames.iterator().next()
        val accompInputName = accompanimentSession!!.inputNames.iterator().next()

        val frameBuf = FloatArray(nFft)
        val fftRe = FloatArray(nFft)
        val fftIm = FloatArray(nFft)

        val chunkReal0 = Array(chunkSize) { FloatArray(numBins) }
        val chunkImag0 = Array(chunkSize) { FloatArray(numBins) }
        val chunkReal1 = Array(chunkSize) { FloatArray(numBins) }
        val chunkImag1 = Array(chunkSize) { FloatArray(numBins) }

        val timeBuf = FloatArray(nFft)
        val vocComplexRe = FloatArray(nFft)
        val vocComplexIm = FloatArray(nFft)

        // Preallocate inference output arrays once to avoid garbage-collection churn on every chunk
        val vocalsRaw = FloatArray(chunkElements)
        val accompRaw = FloatArray(chunkElements)

        val eps = 1e-10f

        // Process audio in 512-frame chunks
        for (chunkIdx in 0 until totalChunks) {
            currentCoroutineContext().ensureActive()

            val startFrame = chunkIdx * chunkSize
            val framesInChunk = minOf(chunkSize, numFrames - startFrame)
            val progressPercent = ((chunkIdx.toFloat() / totalChunks) * 100).toInt()

            onProgress("Separating vocals and accompaniment... $progressPercent%")

            // 1. Compute STFT for this chunk
            for (f in 0 until framesInChunk) {
                val globalFrame = startFrame + f
                val offset = globalFrame * hopLength

                // Channel 0
                for (i in 0 until nFft) frameBuf[i] = c0[offset + i] * hannWindow[i]
                rfft4096(frameBuf, fftRe, fftIm)
                for (k in 0 until numBins) {
                    chunkReal0[f][k] = fftRe[k]
                    chunkImag0[f][k] = fftIm[k]
                }

                // Channel 1
                for (i in 0 until nFft) frameBuf[i] = c1[offset + i] * hannWindow[i]
                rfft4096(frameBuf, fftRe, fftIm)
                for (k in 0 until numBins) {
                    chunkReal1[f][k] = fftRe[k]
                    chunkImag1[f][k] = fftIm[k]
                }
            }

            // 2. Prepare Float32 tensor for this chunk
            floatBuffer.position(0)

            // Channel 0 magnitude
            for (f in 0 until chunkSize) {
                if (f < framesInChunk) {
                    for (k in 0 until numBins) {
                        val r = chunkReal0[f][k]
                        val im = chunkImag0[f][k]
                        val mag = sqrt(r * r + im * im)
                        floatBuffer.put(mag)
                    }
                } else {
                    for (k in 0 until numBins) floatBuffer.put(0.0f)
                }
            }

            // Channel 1 magnitude
            for (f in 0 until chunkSize) {
                if (f < framesInChunk) {
                    for (k in 0 until numBins) {
                        val r = chunkReal1[f][k]
                        val im = chunkImag1[f][k]
                        val mag = sqrt(r * r + im * im)
                        floatBuffer.put(mag)
                    }
                } else {
                    for (k in 0 until numBins) floatBuffer.put(0.0f)
                }
            }

            floatBuffer.rewind()
            val inputTensor = OnnxTensor.createTensor(env, floatBuffer, tensorShape)

            // 3. Run ONNX Inference using the model's actual input name ("x")
            val vocalOutputs = vocalsSession!!.run(mapOf(vocalsInputName to inputTensor))
            val vocalsOutTensor = vocalOutputs[0] as OnnxTensor
            vocalsOutTensor.floatBuffer.get(vocalsRaw)
            vocalOutputs.close()

            val accompOutputs = accompanimentSession!!.run(mapOf(accompInputName to inputTensor))
            val accompOutTensor = accompOutputs[0] as OnnxTensor
            accompOutTensor.floatBuffer.get(accompRaw)
            accompOutputs.close()
            inputTensor.close()

            // 4. Invert STFT & Overlap-add (Wiener filtering)
            val channelOffset = chunkSize * numBins

            for (ch in 0..1) {
                val chBase = ch * channelOffset
                val (real, imag) = if (ch == 0) Pair(chunkReal0, chunkImag0) else Pair(chunkReal1, chunkImag1)
                val outVoc = if (ch == 0) vocalsWav0 else vocalsWav1

                for (f in 0 until framesInChunk) {
                    val globalFrame = startFrame + f
                    val audioOffset = globalFrame * hopLength
                    val frameBase = chBase + f * numBins

                    vocComplexRe.fill(0f)
                    vocComplexIm.fill(0f)

                    for (k in 0 until numBins) {
                        val v = vocalsRaw[frameBase + k]
                        val a = accompRaw[frameBase + k]
                        val v2 = v * v
                        val a2 = a * a
                        val sum2 = v2 + a2 + eps
                        val vMask = (v2 + eps / 2f) / sum2

                        val r = real[f][k]
                        val im = imag[f][k]

                        vocComplexRe[k] = vMask * r
                        vocComplexIm[k] = vMask * im
                    }

                    // Vocals iSTFT
                    irfft4096(vocComplexRe, vocComplexIm, timeBuf)
                    for (i in 0 until nFft) {
                        outVoc[audioOffset + i] += timeBuf[i] * hannWindow[i]
                    }
                }
            }
        }

        onProgress("Normalizing and finalizing audio stems...")
        // Overlap-add window sum:
        // Inside the audio body, 4-fold Hann overlap sum is identically 1.5f.
        // We only need small 4096-sample buffers for the boundary ramps.
        val headNorm = FloatArray(nFft)
        for (f in 0 until minOf(4, numFrames)) {
            val offset = f * hopLength
            for (i in 0 until nFft) {
                if (offset + i < nFft) {
                    headNorm[offset + i] += hannWindow[i] * hannWindow[i]
                }
            }
        }
        val tailStart = maxOf(0, (numFrames - 4) * hopLength)
        val tailNormLen = maxOf(1, c0.size - tailStart)
        val tailNorm = FloatArray(tailNormLen)
        for (f in maxOf(0, numFrames - 4) until numFrames) {
            val offset = f * hopLength
            for (i in 0 until nFft) {
                val idx = (offset + i) - tailStart
                if (idx in 0 until tailNormLen) {
                    tailNorm[idx] += hannWindow[i] * hannWindow[i]
                }
            }
        }

        for (i in 0 until c0.size) {
            val norm = if (i < nFft) {
                if (headNorm[i] > 1e-4f) headNorm[i] else 1.5f
            } else if (i >= tailStart) {
                val tIdx = i - tailStart
                if (tIdx in 0 until tailNormLen && tailNorm[tIdx] > 1e-4f) tailNorm[tIdx] else 1.5f
            } else {
                1.5f
            }
            vocalsWav0[i] /= norm
            vocalsWav1[i] /= norm
        }

        onProgress("Saving separated tracks...")
        val vocalsFile = File(context.cacheDir, "vocals.wav")
        val accompFile = File(context.cacheDir, "accompaniment.wav")
        writeWav16BitStereo(vocalsFile, vocalsWav0, vocalsWav1, sampleRate)
        writeAccompanimentWav16BitStereo(accompFile, c0, c1, vocalsWav0, vocalsWav1, sampleRate)

        val totalTime = (System.currentTimeMillis() - startTime) / 1000f
        onProgress("Separation finished")

        SeparationResult(
            vocalsFile = vocalsFile,
            accompanimentFile = accompFile,
            durationSeconds = audioDuration,
            processingTimeSeconds = totalTime,
            fileSizeBytes = inputSize
        )
    }

    private fun rfft4096(input: FloatArray, outRe: FloatArray, outIm: FloatArray) {
        val re = FloatArray(nFft)
        val im = FloatArray(nFft)

        for (i in 0 until nFft) {
            val rev = bitRev[i]
            re[rev] = input[i]
            im[rev] = 0f
        }

        var len = 2
        while (len <= nFft) {
            val halfLen = len / 2
            val angle = -2.0 * PI / len
            val wStepRe = cos(angle).toFloat()
            val wStepIm = sin(angle).toFloat()

            var i = 0
            while (i < nFft) {
                var wRe = 1.0f
                var wIm = 0.0f
                for (j in 0 until halfLen) {
                    val uRe = re[i + j]
                    val uIm = im[i + j]
                    val vRe = re[i + j + halfLen] * wRe - im[i + j + halfLen] * wIm
                    val vIm = re[i + j + halfLen] * wIm + im[i + j + halfLen] * wRe

                    re[i + j] = uRe + vRe
                    im[i + j] = uIm + vIm
                    re[i + j + halfLen] = uRe - vRe
                    im[i + j + halfLen] = uIm - vIm

                    val nextWRe = wRe * wStepRe - wIm * wStepIm
                    wIm = wRe * wStepIm + wIm * wStepRe
                    wRe = nextWRe
                }
                i += len
            }
            len = len shl 1
        }

        System.arraycopy(re, 0, outRe, 0, numBins)
        System.arraycopy(im, 0, outIm, 0, numBins)
    }

    private fun irfft4096(inRe: FloatArray, inIm: FloatArray, outTime: FloatArray) {
        val re = FloatArray(nFft)
        val im = FloatArray(nFft)

        for (k in 0 until numBins) {
            re[k] = inRe[k]
            im[k] = inIm[k]
        }
        for (k in 1 until nFft / 2) {
            re[nFft - k] = re[k]
            im[nFft - k] = -im[k]
        }

        val revRe = FloatArray(nFft)
        val revIm = FloatArray(nFft)
        for (i in 0 until nFft) {
            val rev = bitRev[i]
            revRe[rev] = re[i]
            revIm[rev] = im[i]
        }

        var len = 2
        while (len <= nFft) {
            val halfLen = len / 2
            val angle = 2.0 * PI / len
            val wStepRe = cos(angle).toFloat()
            val wStepIm = sin(angle).toFloat()

            var i = 0
            while (i < nFft) {
                var wRe = 1.0f
                var wIm = 0.0f
                for (j in 0 until halfLen) {
                    val uRe = revRe[i + j]
                    val uIm = revIm[i + j]
                    val vRe = revRe[i + j + halfLen] * wRe - revIm[i + j + halfLen] * wIm
                    val vIm = revRe[i + j + halfLen] * wIm + revIm[i + j + halfLen] * wRe

                    revRe[i + j] = uRe + vRe
                    revIm[i + j] = uIm + vIm
                    revRe[i + j + halfLen] = uRe - vRe
                    revIm[i + j + halfLen] = uIm - vIm

                    val nextWRe = wRe * wStepRe - wIm * wStepIm
                    wIm = wRe * wStepIm + wIm * wStepRe
                    wRe = nextWRe
                }
                i += len
            }
            len = len shl 1
        }

        val invN = 1.0f / nFft
        for (i in 0 until nFft) {
            outTime[i] = revRe[i] * invN
        }
    }

    /**
     * Decodes audio directly to disk stream to prevent RAM exhaustion.
     * Robust against mono/stereo/surround, 16-bit / 32-bit float PCM,
     * and dynamic sample rate/channel count changes.
     */
    private fun decodeAudioToStereoPcm(uri: Uri): Pair<FloatArray, FloatArray> {
        val extractor = MediaExtractor()
        val pfd = try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (e: Exception) {
            null
        }

        try {
            if (pfd != null) {
                extractor.setDataSource(pfd.fileDescriptor)
            } else {
                extractor.setDataSource(context, uri, null)
            }
        } catch (e: Exception) {
            extractor.release()
            pfd?.close()
            throw IllegalArgumentException("Cannot open audio source: ${e.localizedMessage ?: "File inaccessible"}")
        }

        var audioTrackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val trackFormat = extractor.getTrackFormat(i)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                audioTrackIndex = i
                format = trackFormat
                break
            }
        }

        if (audioTrackIndex == -1 || format == null) {
            extractor.release()
            pfd?.close()
            throw IllegalArgumentException("No audio stream found in the selected file.")
        }

        extractor.selectTrack(audioTrackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)
            ?: throw IllegalArgumentException("Audio stream has unknown format.")

        val codec = try {
            MediaCodec.createDecoderByType(mime)
        } catch (e: Exception) {
            extractor.release()
            pfd?.close()
            throw IllegalStateException("Device lacks a decoder for audio format '$mime': ${e.message}")
        }

        try {
            codec.configure(format, null, null, 0)
            codec.start()
        } catch (e: Exception) {
            codec.release()
            extractor.release()
            pfd?.close()
            throw IllegalStateException("Failed to configure audio decoder for '$mime': ${e.message}")
        }

        var sampleRateIn = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            try { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) } catch (e: Exception) { 44100 }
        } else 44100

        var channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            try { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) } catch (e: Exception) { 2 }
        } else 2

        var isFloatPcm = false
        if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            val enc = try { format.getInteger(MediaFormat.KEY_PCM_ENCODING) } catch (e: Exception) { android.media.AudioFormat.ENCODING_PCM_16BIT }
            isFloatPcm = (enc == android.media.AudioFormat.ENCODING_PCM_FLOAT)
        }

        val tempPcmFile = File(context.cacheDir, "temp_decode.pcm")
        if (tempPcmFile.exists()) tempPcmFile.delete()

        val fos = FileOutputStream(tempPcmFile)
        val fileChannel = fos.channel
        val bufferInfo = MediaCodec.BufferInfo()
        var isEOS = false
        var eosTryCount = 0

        try {
            while (!isEOS || (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM == 0)) {
                if (!isEOS) {
                    val inIndex = codec.dequeueInputBuffer(10000)
                    if (inIndex >= 0) {
                        val inputBuf = codec.getInputBuffer(inIndex)
                        if (inputBuf != null) {
                            val sampleSize = extractor.readSampleData(inputBuf, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                isEOS = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                var outIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                while (outIndex >= 0 || outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val newFormat = codec.outputFormat
                        Log.i(tag, "Decoder output format changed: $newFormat")
                        if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sampleRateIn = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channelCount = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        if (newFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            val enc = newFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            isFloatPcm = (enc == android.media.AudioFormat.ENCODING_PCM_FLOAT)
                        }
                        outIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                        continue
                    }

                    val outputBuf = codec.getOutputBuffer(outIndex)
                    if (outputBuf != null && bufferInfo.size > 0) {
                        outputBuf.position(bufferInfo.offset)
                        outputBuf.limit(bufferInfo.offset + bufferInfo.size)
                        fileChannel.write(outputBuf)
                    }

                    val eosReached = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0)
                    codec.releaseOutputBuffer(outIndex, false)

                    if (eosReached) {
                        isEOS = true
                        break
                    }

                    outIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                }

                if (isEOS) {
                    eosTryCount++
                    if (eosTryCount > 60) break // Guard against encoder stall
                }
            }
        } finally {
            try { codec.stop() } catch (e: Exception) {}
            try { codec.release() } catch (e: Exception) {}
            try { extractor.release() } catch (e: Exception) {}
            try { pfd?.close() } catch (e: Exception) {}
            try { fos.close() } catch (e: Exception) {}
        }

        channelCount = maxOf(1, channelCount)
        sampleRateIn = maxOf(8000, sampleRateIn)

        val bytesPerSample = if (isFloatPcm) 4 else 2
        val bytesPerFrame = channelCount * bytesPerSample
        val totalBytes = tempPcmFile.length()

        if (totalBytes < bytesPerFrame * 2) {
            tempPcmFile.delete()
            throw IllegalArgumentException("Audio file contains no playable audio data.")
        }

        val numInputFrames = (totalBytes / bytesPerFrame).toInt()
        val c0: FloatArray
        val c1: FloatArray
        try {
            c0 = FloatArray(numInputFrames)
            c1 = FloatArray(numInputFrames)
        } catch (e: OutOfMemoryError) {
            tempPcmFile.delete()
            throw IllegalStateException("Track is too long to decode in device memory. Please choose a shorter track.", e)
        }

        val framesPerChunk = 4096
        val chunkBytes = framesPerChunk * bytesPerFrame
        val readBuf = ByteArray(chunkBytes)
        val byteBuf = ByteBuffer.wrap(readBuf).order(ByteOrder.LITTLE_ENDIAN)

        var frameIdx = 0
        try {
            tempPcmFile.inputStream().buffered(65536).use { pcmStream ->
                while (frameIdx < numInputFrames) {
                    var bytesRead = 0
                    while (bytesRead < chunkBytes) {
                        val r = pcmStream.read(readBuf, bytesRead, chunkBytes - bytesRead)
                        if (r < 0) break
                        bytesRead += r
                    }
                    val framesInThisRead = bytesRead / bytesPerFrame
                    if (framesInThisRead == 0) break

                    byteBuf.position(0)
                    byteBuf.limit(framesInThisRead * bytesPerFrame)
                    for (f in 0 until framesInThisRead) {
                        if (frameIdx >= numInputFrames) break
                        val s0: Float
                        val s1: Float
                        if (isFloatPcm) {
                            s0 = byteBuf.float
                            s1 = if (channelCount > 1) byteBuf.float else s0
                            for (ch in 2 until channelCount) byteBuf.float
                        } else {
                            s0 = byteBuf.short / 32768.0f
                            s1 = if (channelCount > 1) byteBuf.short / 32768.0f else s0
                            for (ch in 2 until channelCount) byteBuf.short
                        }
                        c0[frameIdx] = s0.coerceIn(-1.0f, 1.0f)
                        c1[frameIdx] = s1.coerceIn(-1.0f, 1.0f)
                        frameIdx++
                    }
                }
            }
        } finally {
            tempPcmFile.delete()
        }

        val actualC0 = if (frameIdx < numInputFrames) c0.copyOf(frameIdx) else c0
        val actualC1 = if (frameIdx < numInputFrames) c1.copyOf(frameIdx) else c1

        // Resample if not 44.1 kHz
        if (sampleRateIn != sampleRate && actualC0.size >= 2) {
            val ratio = sampleRate.toDouble() / sampleRateIn
            val newLen = maxOf(2, (actualC0.size * ratio).toInt())
            val newC0: FloatArray
            val newC1: FloatArray
            try {
                newC0 = FloatArray(newLen)
                newC1 = FloatArray(newLen)
            } catch (e: OutOfMemoryError) {
                throw IllegalStateException("Ran out of memory while resampling audio.", e)
            }
            val maxSrc = actualC0.size - 2
            for (i in 0 until newLen) {
                val srcIdx = i / ratio
                val i0 = srcIdx.toInt().coerceIn(0, maxSrc)
                val frac = (srcIdx - i0).toFloat().coerceIn(0f, 1f)
                newC0[i] = actualC0[i0] + frac * (actualC0[i0 + 1] - actualC0[i0])
                newC1[i] = actualC1[i0] + frac * (actualC1[i0 + 1] - actualC1[i0])
            }
            return Pair(newC0, newC1)
        }

        return Pair(actualC0, actualC1)
    }

    private fun writeWav16BitStereo(file: File, c0: FloatArray, c1: FloatArray, sampleRate: Int) {
        val numSamples = c0.size
        val numChannels = 2
        val bitsPerSample = 16
        val byteRate = sampleRate * numChannels * (bitsPerSample / 8)
        val dataSize = numSamples * numChannels * 2
        val totalSize = 36 + dataSize

        FileOutputStream(file).use { out ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt(totalSize)
            header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray())
            header.putInt(16)
            header.putShort(1.toShort())
            header.putShort(numChannels.toShort())
            header.putInt(sampleRate)
            header.putInt(byteRate)
            header.putShort((numChannels * (bitsPerSample / 8)).toShort())
            header.putShort(bitsPerSample.toShort())
            header.put("data".toByteArray())
            header.putInt(dataSize)

            out.write(header.array())

            val chunkSamples = 16384
            val sampleBytes = ByteArray(chunkSamples * 4)
            val bb = ByteBuffer.wrap(sampleBytes).order(ByteOrder.LITTLE_ENDIAN)

            var i = 0
            while (i < numSamples) {
                val count = minOf(chunkSamples, numSamples - i)
                bb.position(0)
                for (j in 0 until count) {
                    val idx = i + j
                    val s0 = (c0[idx].coerceIn(-1.0f, 1.0f) * 32767f).toInt().toShort()
                    val s1 = (c1[idx].coerceIn(-1.0f, 1.0f) * 32767f).toInt().toShort()
                    bb.putShort(s0)
                    bb.putShort(s1)
                }
                out.write(sampleBytes, 0, count * 4)
                i += count
            }
        }
    }

    /**
     * Streams accompaniment WAV directly to disk by subtracting vocals from the original mix.
     * Avoids holding 80-100MB of extra FloatArrays in heap memory.
     */
    private fun writeAccompanimentWav16BitStereo(
        file: File,
        mix0: FloatArray,
        mix1: FloatArray,
        voc0: FloatArray,
        voc1: FloatArray,
        sampleRate: Int
    ) {
        val numSamples = mix0.size
        val numChannels = 2
        val bitsPerSample = 16
        val byteRate = sampleRate * numChannels * (bitsPerSample / 8)
        val dataSize = numSamples * numChannels * 2
        val totalSize = 36 + dataSize

        FileOutputStream(file).use { out ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt(totalSize)
            header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray())
            header.putInt(16)
            header.putShort(1.toShort())
            header.putShort(numChannels.toShort())
            header.putInt(sampleRate)
            header.putInt(byteRate)
            header.putShort((numChannels * (bitsPerSample / 8)).toShort())
            header.putShort(bitsPerSample.toShort())
            header.put("data".toByteArray())
            header.putInt(dataSize)

            out.write(header.array())

            val chunkSamples = 16384
            val sampleBytes = ByteArray(chunkSamples * 4)
            val bb = ByteBuffer.wrap(sampleBytes).order(ByteOrder.LITTLE_ENDIAN)

            var i = 0
            while (i < numSamples) {
                val count = minOf(chunkSamples, numSamples - i)
                bb.position(0)
                for (j in 0 until count) {
                    val idx = i + j
                    val acc0 = (mix0[idx] - voc0[idx]).coerceIn(-1.0f, 1.0f)
                    val acc1 = (mix1[idx] - voc1[idx]).coerceIn(-1.0f, 1.0f)
                    val s0 = (acc0 * 32767f).toInt().toShort()
                    val s1 = (acc1 * 32767f).toInt().toShort()
                    bb.putShort(s0)
                    bb.putShort(s1)
                }
                out.write(sampleBytes, 0, count * 4)
                i += count
            }
        }
    }
}
