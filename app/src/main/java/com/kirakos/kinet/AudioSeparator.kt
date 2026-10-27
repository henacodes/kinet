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
    val drumsFile: File? = null,
    val bassFile: File? = null,
    val otherFile: File? = null,
    val accompanimentFile: File,
    val durationSeconds: Float,
    val processingTimeSeconds: Float,
    val fileSizeBytes: Long = 0L,
    val isFourStem: Boolean = true
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
    private var drumsSession: OrtSession? = null
    private var bassSession: OrtSession? = null
    private var otherSession: OrtSession? = null

    init {
        loadModels()
    }

    private fun loadModels() {
        val sessionOptions = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
        }
        val vocalsBytes = context.assets.open("vocals.fp16.onnx").use { it.readBytes() }
        vocalsSession = env.createSession(vocalsBytes, sessionOptions)

        val drumsBytes = context.assets.open("drums.fp16.onnx").use { it.readBytes() }
        drumsSession = env.createSession(drumsBytes, sessionOptions)

        val bassBytes = context.assets.open("bass.fp16.onnx").use { it.readBytes() }
        bassSession = env.createSession(bassBytes, sessionOptions)

        val otherBytes = context.assets.open("other.fp16.onnx").use { it.readBytes() }
        otherSession = env.createSession(otherBytes, sessionOptions)

        Log.i(tag, "4-Stem ONNX sessions loaded successfully")
    }

    fun close() {
        try {
            vocalsSession?.close()
            drumsSession?.close()
            bassSession?.close()
            otherSession?.close()
        } catch (e: Exception) {
            Log.w(tag, "Error closing OrtSessions", e)
        }
    }

    suspend fun separate(
        audioUri: Uri,
        isFourStem: Boolean = true,
        onProgress: (String) -> Unit = {}
    ): SeparationResult = withContext(Dispatchers.Default) {
        val inputSize = try {
            context.contentResolver.openFileDescriptor(audioUri, "r")?.use { it.statSize } ?: 0L
        } catch (e: Exception) {
            0L
        }

        onProgress("Preparing audio...")
        val (c0, c1) = decodeAudioToStereoPcm(audioUri)
        separatePcm(c0, c1, inputSize, isFourStem, onProgress)
    }

    suspend fun separatePcm(
        c0: FloatArray,
        c1: FloatArray,
        inputSize: Long = 0L,
        isFourStem: Boolean = true,
        onProgress: (String) -> Unit = {}
    ): SeparationResult = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        val audioDuration = c0.size.toFloat() / sampleRate
        Log.i(tag, "Processing ${c0.size} samples (~${String.format("%.1f", audioDuration)}s), 4-stem = $isFourStem")

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

        // Prepare direct output files in cache
        val vocalsFile = File(context.cacheDir, "vocals.wav")
        val accompFile = File(context.cacheDir, "accompaniment.wav")
        val drumsFile = if (isFourStem) File(context.cacheDir, "drums.wav") else null
        val bassFile = if (isFourStem) File(context.cacheDir, "bass.wav") else null
        val otherFile = if (isFourStem) File(context.cacheDir, "other.wav") else null

        val vocOut = java.io.BufferedOutputStream(FileOutputStream(vocalsFile), 64 * 1024)
        val accompOut = java.io.BufferedOutputStream(FileOutputStream(accompFile), 64 * 1024)
        val drumOut = drumsFile?.let { java.io.BufferedOutputStream(FileOutputStream(it), 64 * 1024) }
        val bassOut = bassFile?.let { java.io.BufferedOutputStream(FileOutputStream(it), 64 * 1024) }
        val otherOut = otherFile?.let { java.io.BufferedOutputStream(FileOutputStream(it), 64 * 1024) }

        // Write 44-byte placeholder header
        val dummyHeader = ByteArray(44)
        vocOut.write(dummyHeader)
        accompOut.write(dummyHeader)
        drumOut?.write(dummyHeader)
        bassOut?.write(dummyHeader)
        otherOut?.write(dummyHeader)

        // Overlap buffer: 512 frames * 1024 + 3072 overlap tail = 527,360 floats (~2.1MB per channel)
        val chunkOverlap = nFft - hopLength
        val maxChunkSamples = chunkSize * hopLength + chunkOverlap

        val vocBuf0 = FloatArray(maxChunkSamples)
        val vocBuf1 = FloatArray(maxChunkSamples)
        val drumBuf0 = if (isFourStem) FloatArray(maxChunkSamples) else null
        val drumBuf1 = if (isFourStem) FloatArray(maxChunkSamples) else null
        val bassBuf0 = if (isFourStem) FloatArray(maxChunkSamples) else null
        val bassBuf1 = if (isFourStem) FloatArray(maxChunkSamples) else null
        val otherBuf0 = if (isFourStem) FloatArray(maxChunkSamples) else null
        val otherBuf1 = if (isFourStem) FloatArray(maxChunkSamples) else null
        val accompBuf0 = if (!isFourStem) FloatArray(maxChunkSamples) else null
        val accompBuf1 = if (!isFourStem) FloatArray(maxChunkSamples) else null

        // Reusable memory buffers for 1 chunk of 512 frames (only ~4MB for standard float32)
        val chunkElements = 2 * 1 * chunkSize * numBins
        val byteBuffer = ByteBuffer.allocateDirect(chunkElements * 4).order(ByteOrder.nativeOrder())
        val floatBuffer = byteBuffer.asFloatBuffer()
        val tensorShape = longArrayOf(2, 1, chunkSize.toLong(), numBins.toLong())

        val vocalsInputName = vocalsSession!!.inputNames.iterator().next()
        val drumsInputName = if (isFourStem) drumsSession!!.inputNames.iterator().next() else ""
        val bassInputName = if (isFourStem) bassSession!!.inputNames.iterator().next() else ""
        val otherInputName = if (isFourStem) otherSession!!.inputNames.iterator().next() else ""

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
        val drumComplexRe = if (isFourStem) FloatArray(nFft) else null
        val drumComplexIm = if (isFourStem) FloatArray(nFft) else null
        val bassComplexRe = if (isFourStem) FloatArray(nFft) else null
        val bassComplexIm = if (isFourStem) FloatArray(nFft) else null
        val otherComplexRe = if (isFourStem) FloatArray(nFft) else null
        val otherComplexIm = if (isFourStem) FloatArray(nFft) else null

        // Preallocate inference output arrays once
        val vocalsRaw = FloatArray(chunkElements)
        val drumsRaw = if (isFourStem) FloatArray(chunkElements) else null
        val bassRaw = if (isFourStem) FloatArray(chunkElements) else null
        val otherRaw = if (isFourStem) FloatArray(chunkElements) else null

        val eps = 1e-10f

        // Overlap-add window sum normalization curves:
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

        val writeSampleBytes = ByteArray(4096 * 4)
        val writeBb = ByteBuffer.wrap(writeSampleBytes).order(ByteOrder.LITTLE_ENDIAN)

        fun flushChannel(stream: java.io.OutputStream, ch0: FloatArray, ch1: FloatArray, count: Int) {
            var idx = 0
            while (idx < count) {
                val batch = minOf(4096, count - idx)
                writeBb.position(0)
                for (i in 0 until batch) {
                    val s0 = (ch0[idx + i].coerceIn(-1.0f, 1.0f) * 32767f).toInt().toShort()
                    val s1 = (ch1[idx + i].coerceIn(-1.0f, 1.0f) * 32767f).toInt().toShort()
                    writeBb.putShort(s0)
                    writeBb.putShort(s1)
                }
                stream.write(writeSampleBytes, 0, batch * 4)
                idx += batch
            }
        }

        fun flushAccompaniment(
            stream: java.io.OutputStream,
            d0: FloatArray, d1: FloatArray,
            b0: FloatArray, b1: FloatArray,
            o0: FloatArray, o1: FloatArray,
            count: Int
        ) {
            var idx = 0
            while (idx < count) {
                val batch = minOf(4096, count - idx)
                writeBb.position(0)
                for (i in 0 until batch) {
                    val s0 = ((d0[idx + i] + b0[idx + i] + o0[idx + i]).coerceIn(-1.0f, 1.0f) * 32767f).toInt().toShort()
                    val s1 = ((d1[idx + i] + b1[idx + i] + o1[idx + i]).coerceIn(-1.0f, 1.0f) * 32767f).toInt().toShort()
                    writeBb.putShort(s0)
                    writeBb.putShort(s1)
                }
                stream.write(writeSampleBytes, 0, batch * 4)
                idx += batch
            }
        }

        try {
            // Process audio in 512-frame chunks
            for (chunkIdx in 0 until totalChunks) {
                currentCoroutineContext().ensureActive()

                val startFrame = chunkIdx * chunkSize
                val framesInChunk = minOf(chunkSize, numFrames - startFrame)
                val progressPercent = ((chunkIdx.toFloat() / totalChunks) * 100).toInt()

                if (isFourStem) {
                    onProgress("Separating 4 stems... $progressPercent%")
                } else {
                    onProgress("Separating 2 stems... $progressPercent%")
                }

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
                for (f in 0 until chunkSize) {
                    if (f < framesInChunk) {
                        for (k in 0 until numBins) {
                            val r = chunkReal0[f][k]
                            val im = chunkImag0[f][k]
                            floatBuffer.put(sqrt(r * r + im * im))
                        }
                    } else {
                        for (k in 0 until numBins) floatBuffer.put(0.0f)
                    }
                }
                for (f in 0 until chunkSize) {
                    if (f < framesInChunk) {
                        for (k in 0 until numBins) {
                            val r = chunkReal1[f][k]
                            val im = chunkImag1[f][k]
                            floatBuffer.put(sqrt(r * r + im * im))
                        }
                    } else {
                        for (k in 0 until numBins) floatBuffer.put(0.0f)
                    }
                }
                floatBuffer.rewind()
                val inputTensor = OnnxTensor.createTensor(env, floatBuffer, tensorShape)

                // 3. Run ONNX Inference
                val vocalOutputs = vocalsSession!!.run(mapOf(vocalsInputName to inputTensor))
                val vocalsOutTensor = vocalOutputs[0] as OnnxTensor
                vocalsOutTensor.floatBuffer.get(vocalsRaw)
                vocalOutputs.close()

                if (isFourStem && drumsSession != null && bassSession != null && otherSession != null) {
                    val drumOutputs = drumsSession!!.run(mapOf(drumsInputName to inputTensor))
                    val drumOutTensor = drumOutputs[0] as OnnxTensor
                    drumOutTensor.floatBuffer.get(drumsRaw!!)
                    drumOutputs.close()

                    val bassOutputs = bassSession!!.run(mapOf(bassInputName to inputTensor))
                    val bassOutTensor = bassOutputs[0] as OnnxTensor
                    bassOutTensor.floatBuffer.get(bassRaw!!)
                    bassOutputs.close()

                    val otherOutputs = otherSession!!.run(mapOf(otherInputName to inputTensor))
                    val otherOutTensor = otherOutputs[0] as OnnxTensor
                    otherOutTensor.floatBuffer.get(otherRaw!!)
                    otherOutputs.close()
                }

                inputTensor.close()

                // 4. Invert STFT & Overlap-add (Wiener filtering)
                val channelOffset = chunkSize * numBins
                for (ch in 0..1) {
                    val chBase = ch * channelOffset
                    val (real, imag) = if (ch == 0) Pair(chunkReal0, chunkImag0) else Pair(chunkReal1, chunkImag1)
                    val vocBuf = if (ch == 0) vocBuf0 else vocBuf1

                    if (isFourStem) {
                        val drumBuf = if (ch == 0) drumBuf0!! else drumBuf1!!
                        val bassBuf = if (ch == 0) bassBuf0!! else bassBuf1!!
                        val otherBuf = if (ch == 0) otherBuf0!! else otherBuf1!!

                        for (f in 0 until framesInChunk) {
                            val audioOffsetInChunk = f * hopLength
                            val frameBase = chBase + f * numBins

                            vocComplexRe.fill(0f); vocComplexIm.fill(0f)
                            drumComplexRe!!.fill(0f); drumComplexIm!!.fill(0f)
                            bassComplexRe!!.fill(0f); bassComplexIm!!.fill(0f)
                            otherComplexRe!!.fill(0f); otherComplexIm!!.fill(0f)

                            for (k in 0 until numBins) {
                                val v = vocalsRaw[frameBase + k]
                                val d = drumsRaw!![frameBase + k]
                                val b = bassRaw!![frameBase + k]
                                val o = otherRaw!![frameBase + k]

                                val v2 = v * v
                                val d2 = d * d
                                val b2 = b * b
                                val o2 = o * o
                                val sum2 = v2 + d2 + b2 + o2 + eps

                                val vMask = (v2 + eps / 4f) / sum2
                                val dMask = (d2 + eps / 4f) / sum2
                                val bMask = (b2 + eps / 4f) / sum2
                                val oMask = (o2 + eps / 4f) / sum2

                                val r = real[f][k]
                                val im = imag[f][k]

                                vocComplexRe[k] = vMask * r
                                vocComplexIm[k] = vMask * im

                                drumComplexRe[k] = dMask * r
                                drumComplexIm[k] = dMask * im

                                bassComplexRe[k] = bMask * r
                                bassComplexIm[k] = bMask * im

                                otherComplexRe[k] = oMask * r
                                otherComplexIm[k] = oMask * im
                            }

                            irfft4096(vocComplexRe, vocComplexIm, timeBuf)
                            for (i in 0 until nFft) vocBuf[audioOffsetInChunk + i] += timeBuf[i] * hannWindow[i]

                            irfft4096(drumComplexRe, drumComplexIm, timeBuf)
                            for (i in 0 until nFft) drumBuf[audioOffsetInChunk + i] += timeBuf[i] * hannWindow[i]

                            irfft4096(bassComplexRe, bassComplexIm, timeBuf)
                            for (i in 0 until nFft) bassBuf[audioOffsetInChunk + i] += timeBuf[i] * hannWindow[i]

                            irfft4096(otherComplexRe, otherComplexIm, timeBuf)
                            for (i in 0 until nFft) otherBuf[audioOffsetInChunk + i] += timeBuf[i] * hannWindow[i]
                        }
                    } else {
                        // 2-Stem mode: Soft-mask vocals, compute accompaniment by mix subtraction
                        for (f in 0 until framesInChunk) {
                            val audioOffsetInChunk = f * hopLength
                            val frameBase = chBase + f * numBins

                            vocComplexRe.fill(0f); vocComplexIm.fill(0f)

                            for (k in 0 until numBins) {
                                val r = real[f][k]
                                val im = imag[f][k]
                                val mixMag = sqrt(r * r + im * im)
                                val v = vocalsRaw[frameBase + k]
                                val res = maxOf(0f, mixMag - v)

                                val vMask = ((v * v + eps / 2f) / (v * v + res * res + eps)).coerceIn(0f, 1f)

                                vocComplexRe[k] = vMask * r
                                vocComplexIm[k] = vMask * im
                            }

                            irfft4096(vocComplexRe, vocComplexIm, timeBuf)
                            for (i in 0 until nFft) vocBuf[audioOffsetInChunk + i] += timeBuf[i] * hannWindow[i]
                        }
                    }
                }

                // 5. Finalize samples for this chunk and stream to files
                val globalChunkStart = startFrame * hopLength
                val isLastChunk = (chunkIdx == totalChunks - 1)
                val samplesToFinalize = if (isLastChunk) {
                    minOf(c0.size - globalChunkStart, framesInChunk * hopLength + chunkOverlap)
                } else {
                    framesInChunk * hopLength
                }

                for (s in 0 until samplesToFinalize) {
                    val globalIdx = globalChunkStart + s
                    val norm = if (globalIdx < nFft) headNorm[globalIdx]
                               else if (globalIdx >= tailStart) tailNorm[globalIdx - tailStart]
                               else 1.5f
                    val safeNorm = if (norm > 1e-4f) norm else 1.5f
                    val inv = 1.0f / safeNorm

                    vocBuf0[s] *= inv
                    vocBuf1[s] *= inv

                    if (isFourStem) {
                        drumBuf0!![s] *= inv
                        drumBuf1!![s] *= inv
                        bassBuf0!![s] *= inv
                        bassBuf1!![s] *= inv
                        otherBuf0!![s] *= inv
                        otherBuf1!![s] *= inv
                    } else {
                        accompBuf0!![s] = c0[globalIdx] - vocBuf0[s]
                        accompBuf1!![s] = c1[globalIdx] - vocBuf1[s]
                    }
                }

                flushChannel(vocOut, vocBuf0, vocBuf1, samplesToFinalize)
                if (isFourStem && drumOut != null && bassOut != null && otherOut != null &&
                    drumBuf0 != null && drumBuf1 != null && bassBuf0 != null && bassBuf1 != null && otherBuf0 != null && otherBuf1 != null) {
                    flushChannel(drumOut, drumBuf0, drumBuf1, samplesToFinalize)
                    flushChannel(bassOut, bassBuf0, bassBuf1, samplesToFinalize)
                    flushChannel(otherOut, otherBuf0, otherBuf1, samplesToFinalize)
                    flushAccompaniment(accompOut, drumBuf0, drumBuf1, bassBuf0, bassBuf1, otherBuf0, otherBuf1, samplesToFinalize)
                } else if (accompBuf0 != null && accompBuf1 != null) {
                    flushChannel(accompOut, accompBuf0, accompBuf1, samplesToFinalize)
                }

                if (!isLastChunk) {
                    System.arraycopy(vocBuf0, samplesToFinalize, vocBuf0, 0, chunkOverlap)
                    vocBuf0.fill(0f, chunkOverlap, maxChunkSamples)
                    System.arraycopy(vocBuf1, samplesToFinalize, vocBuf1, 0, chunkOverlap)
                    vocBuf1.fill(0f, chunkOverlap, maxChunkSamples)

                    if (isFourStem && drumBuf0 != null && drumBuf1 != null && bassBuf0 != null && bassBuf1 != null && otherBuf0 != null && otherBuf1 != null) {
                        System.arraycopy(drumBuf0, samplesToFinalize, drumBuf0, 0, chunkOverlap)
                        drumBuf0.fill(0f, chunkOverlap, maxChunkSamples)
                        System.arraycopy(drumBuf1, samplesToFinalize, drumBuf1, 0, chunkOverlap)
                        drumBuf1.fill(0f, chunkOverlap, maxChunkSamples)

                        System.arraycopy(bassBuf0, samplesToFinalize, bassBuf0, 0, chunkOverlap)
                        bassBuf0.fill(0f, chunkOverlap, maxChunkSamples)
                        System.arraycopy(bassBuf1, samplesToFinalize, bassBuf1, 0, chunkOverlap)
                        bassBuf1.fill(0f, chunkOverlap, maxChunkSamples)

                        System.arraycopy(otherBuf0, samplesToFinalize, otherBuf0, 0, chunkOverlap)
                        otherBuf0.fill(0f, chunkOverlap, maxChunkSamples)
                        System.arraycopy(otherBuf1, samplesToFinalize, otherBuf1, 0, chunkOverlap)
                        otherBuf1.fill(0f, chunkOverlap, maxChunkSamples)
                    }
                }
            }
        } finally {
            vocOut.close()
            accompOut.close()
            drumOut?.close()
            bassOut?.close()
            otherOut?.close()
        }

        finalizeWavHeader(vocalsFile, c0.size, sampleRate)
        finalizeWavHeader(accompFile, c0.size, sampleRate)
        if (isFourStem && drumsFile != null && bassFile != null && otherFile != null) {
            finalizeWavHeader(drumsFile, c0.size, sampleRate)
            finalizeWavHeader(bassFile, c0.size, sampleRate)
            finalizeWavHeader(otherFile, c0.size, sampleRate)
        }

        val totalTime = (System.currentTimeMillis() - startTime) / 1000f
        onProgress("Separation finished")

        SeparationResult(
            vocalsFile = vocalsFile,
            drumsFile = drumsFile,
            bassFile = bassFile,
            otherFile = otherFile,
            accompanimentFile = accompFile,
            durationSeconds = audioDuration,
            processingTimeSeconds = totalTime,
            fileSizeBytes = inputSize,
            isFourStem = isFourStem
        )
    }

    private fun finalizeWavHeader(file: File, numSamples: Int, sampleRate: Int = 44100, channels: Int = 2) {
        val dataSize = numSamples * channels * 2
        val totalSize = 36 + dataSize
        java.io.RandomAccessFile(file, "rw").use { raf ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt(totalSize)
            header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray())
            header.putInt(16)
            header.putShort(1.toShort()) // PCM format
            header.putShort(channels.toShort())
            header.putInt(sampleRate)
            header.putInt(sampleRate * channels * 2)
            header.putShort((channels * 2).toShort())
            header.putShort(16.toShort())
            header.put("data".toByteArray())
            header.putInt(dataSize)
            raf.seek(0)
            raf.write(header.array())
        }
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
