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
    val processingTimeSeconds: Float
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

        onProgress("Decoding audio to 44.1kHz stereo...")
        val (c0, c1) = decodeAudioToStereoPcm(audioUri)
        val audioDuration = c0.size.toFloat() / sampleRate
        Log.i(tag, "Decoded ${c0.size} samples (~${String.format("%.1f", audioDuration)}s)")

        val numFrames = (c0.size - nFft) / hopLength + 1
        if (numFrames <= 0) {
            throw IllegalArgumentException("Audio too short: minimum duration is ${String.format("%.1f", nFft / sampleRate.toFloat())}s")
        }

        val totalChunks = (numFrames + chunkSize - 1) / chunkSize
        Log.i(tag, "Total frames: $numFrames, divided into $totalChunks chunk(s)")

        // Output audio accumulation arrays
        val vocalsWav0 = FloatArray(c0.size)
        val vocalsWav1 = FloatArray(c1.size)
        val accompWav0 = FloatArray(c0.size)
        val accompWav1 = FloatArray(c1.size)
        val windowSum = FloatArray(c0.size)

        // Precompute overlap-add window sum
        for (f in 0 until numFrames) {
            val offset = f * hopLength
            for (i in 0 until nFft) {
                windowSum[offset + i] += hannWindow[i] * hannWindow[i]
            }
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
        val accComplexRe = FloatArray(nFft)
        val accComplexIm = FloatArray(nFft)

        val eps = 1e-10f

        // Process audio in 512-frame chunks
        for (chunkIdx in 0 until totalChunks) {
            val startFrame = chunkIdx * chunkSize
            val framesInChunk = minOf(chunkSize, numFrames - startFrame)
            val progressPercent = ((chunkIdx.toFloat() / totalChunks) * 100).toInt()

            onProgress("Processing chunk ${chunkIdx + 1} of $totalChunks ($progressPercent%)...")

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
            val vocalsRaw = FloatArray(chunkElements)
            vocalsOutTensor.floatBuffer.get(vocalsRaw)
            vocalOutputs.close()

            val accompOutputs = accompanimentSession!!.run(mapOf(accompInputName to inputTensor))
            val accompOutTensor = accompOutputs[0] as OnnxTensor
            val accompRaw = FloatArray(chunkElements)
            accompOutTensor.floatBuffer.get(accompRaw)
            accompOutputs.close()
            inputTensor.close()

            // 4. Invert STFT & Overlap-add
            val channelOffset = chunkSize * numBins

            for (ch in 0..1) {
                val chBase = ch * channelOffset
                val (real, imag) = if (ch == 0) Pair(chunkReal0, chunkImag0) else Pair(chunkReal1, chunkImag1)
                val outVoc = if (ch == 0) vocalsWav0 else vocalsWav1
                val outAcc = if (ch == 0) accompWav0 else accompWav1

                for (f in 0 until framesInChunk) {
                    val globalFrame = startFrame + f
                    val audioOffset = globalFrame * hopLength
                    val frameBase = chBase + f * numBins

                    vocComplexRe.fill(0f)
                    vocComplexIm.fill(0f)
                    accComplexRe.fill(0f)
                    accComplexIm.fill(0f)

                    for (k in 0 until numBins) {
                        val v = vocalsRaw[frameBase + k]
                        val a = accompRaw[frameBase + k]
                        val v2 = v * v
                        val a2 = a * a
                        val sum2 = v2 + a2 + eps
                        val vMask = (v2 + eps / 2f) / sum2
                        val aMask = (a2 + eps / 2f) / sum2

                        val r = real[f][k]
                        val im = imag[f][k]

                        vocComplexRe[k] = vMask * r
                        vocComplexIm[k] = vMask * im
                        accComplexRe[k] = aMask * r
                        accComplexIm[k] = aMask * im
                    }

                    // Vocals iSTFT
                    irfft4096(vocComplexRe, vocComplexIm, timeBuf)
                    for (i in 0 until nFft) {
                        outVoc[audioOffset + i] += timeBuf[i] * hannWindow[i]
                    }

                    // Accompaniment iSTFT
                    irfft4096(accComplexRe, accComplexIm, timeBuf)
                    for (i in 0 until nFft) {
                        outAcc[audioOffset + i] += timeBuf[i] * hannWindow[i]
                    }
                }
            }
        }

        onProgress("Normalizing and finalizing audio stems...")
        // Overlap-add normalization
        for (i in 0 until c0.size) {
            val norm = if (windowSum[i] > 1e-4f) windowSum[i] else 1.0f
            vocalsWav0[i] /= norm
            vocalsWav1[i] /= norm
            accompWav0[i] /= norm
            accompWav1[i] /= norm
        }

        onProgress("Exporting WAV files...")
        val vocalsFile = File(context.cacheDir, "vocals.wav")
        val accompFile = File(context.cacheDir, "accompaniment.wav")
        writeWav16BitStereo(vocalsFile, vocalsWav0, vocalsWav1, sampleRate)
        writeWav16BitStereo(accompFile, accompWav0, accompWav1, sampleRate)

        val totalTime = (System.currentTimeMillis() - startTime) / 1000f
        onProgress("Complete!")

        SeparationResult(
            vocalsFile = vocalsFile,
            accompanimentFile = accompFile,
            durationSeconds = audioDuration,
            processingTimeSeconds = totalTime
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
     */
    private fun decodeAudioToStereoPcm(uri: Uri): Pair<FloatArray, FloatArray> {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)

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
            throw IllegalArgumentException("No audio track found in $uri")
        }

        extractor.selectTrack(audioTrackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val sampleRateIn = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        val tempPcmFile = File(context.cacheDir, "temp_decode.pcm")
        if (tempPcmFile.exists()) tempPcmFile.delete()

        val fos = FileOutputStream(tempPcmFile)
        val fileChannel = fos.channel
        val bufferInfo = MediaCodec.BufferInfo()
        var isEOS = false

        try {
            while (!isEOS) {
                val inIndex = codec.dequeueInputBuffer(10000)
                if (inIndex >= 0) {
                    val inputBuf = codec.getInputBuffer(inIndex)!!
                    val sampleSize = extractor.readSampleData(inputBuf, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        isEOS = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }

                var outIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                while (outIndex >= 0) {
                    val outputBuf = codec.getOutputBuffer(outIndex)!!
                    outputBuf.position(bufferInfo.offset)
                    outputBuf.limit(bufferInfo.offset + bufferInfo.size)
                    fileChannel.write(outputBuf)

                    codec.releaseOutputBuffer(outIndex, false)
                    outIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                }
            }
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
            fos.close()
        }

        val totalBytes = tempPcmFile.length()
        val bytesPerFrame = channelCount * 2
        val numInputFrames = (totalBytes / bytesPerFrame).toInt()

        val pcmStream = tempPcmFile.inputStream().buffered(65536)
        val readBuf = ByteArray(65536)
        val byteBuf = ByteBuffer.wrap(readBuf).order(ByteOrder.LITTLE_ENDIAN)

        val c0 = FloatArray(numInputFrames)
        val c1 = FloatArray(numInputFrames)

        var frameIdx = 0
        var bytesRead = pcmStream.read(readBuf)
        while (bytesRead > 0 && frameIdx < numInputFrames) {
            byteBuf.position(0)
            byteBuf.limit(bytesRead)
            val shortsAvailable = bytesRead / 2
            var shortPos = 0
            while (shortPos + channelCount <= shortsAvailable && frameIdx < numInputFrames) {
                val s0 = byteBuf.short / 32768.0f
                val s1 = if (channelCount > 1) byteBuf.short / 32768.0f else s0
                for (ch in 2 until channelCount) byteBuf.short

                c0[frameIdx] = s0
                c1[frameIdx] = s1
                frameIdx++
                shortPos += channelCount
            }
            bytesRead = pcmStream.read(readBuf)
        }
        pcmStream.close()
        tempPcmFile.delete()

        // Resample if not 44.1 kHz
        if (sampleRateIn != sampleRate && c0.isNotEmpty()) {
            val ratio = sampleRate.toDouble() / sampleRateIn
            val newLen = (c0.size * ratio).toInt()
            val newC0 = FloatArray(newLen)
            val newC1 = FloatArray(newLen)
            for (i in 0 until newLen) {
                val srcIdx = i / ratio
                val i0 = srcIdx.toInt().coerceIn(0, c0.size - 2)
                val frac = (srcIdx - i0).toFloat()
                newC0[i] = c0[i0] + frac * (c0[i0 + 1] - c0[i0])
                newC1[i] = c1[i0] + frac * (c1[i0 + 1] - c1[i0])
            }
            return Pair(newC0, newC1)
        }

        return Pair(c0, c1)
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
}
