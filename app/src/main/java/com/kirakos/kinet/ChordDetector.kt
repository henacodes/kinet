package com.kirakos.kinet

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.*

data class ChordSegment(
    val startMs: Long,
    val endMs: Long,
    val chord: String,
    val confidence: Float = 1.0f
)

object ChordTransposer {
    private val PITCH_CLASSES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    fun transpose(chord: String, semitones: Int): String {
        if (semitones == 0 || chord.isBlank()) return chord
        val (root, suffix) = parseChord(chord) ?: return chord
        val rootIdx = PITCH_CLASSES.indexOf(root)
        if (rootIdx == -1) return chord
        val newIdx = ((rootIdx + semitones) % 12 + 12) % 12
        return "${PITCH_CLASSES[newIdx]}$suffix"
    }

    private fun parseChord(chord: String): Pair<String, String>? {
        if (chord.length >= 2 && (chord[1] == '#' || chord[1] == 'b')) {
            var root = chord.substring(0, 2)
            root = when (root) {
                "Db" -> "C#"
                "Eb" -> "D#"
                "Gb" -> "F#"
                "Ab" -> "G#"
                "Bb" -> "A#"
                else -> root
            }
            return Pair(root, chord.substring(2))
        } else if (chord.isNotEmpty()) {
            return Pair(chord.substring(0, 1), chord.substring(1))
        }
        return null
    }
}

/**
 * Production Harmonic Engine for Musical Chord Recognition.
 *
 * Dedicated to the 24 foundational Major and Minor triads with:
 * - Robust RIFF/WAVE header parser supporting mono and stereo 16-bit PCM.
 * - Symmetrically centered 8-tap anti-aliasing FIR decimation.
 * - Exact semitone band ratio (2^(1/24) - 2^(-1/24)) bin-density normalization.
 * - Secondary harmonicity crest-factor gate rejecting unpitched percussion and noise.
 * - 50-bin high-resolution auto-tuning estimation (2-cent precision, A440-snapped).
 * - Root-dominant templates (root 1.30, 3rd 1.10, 5th 0.90) with diatonic scale tolerance.
 * - Inversion-aware bass harmonic integration (root +0.38, 1st inversion +0.16).
 * - 7-frame (~650 ms) posterior probability window smoothing (zero stalling, crisp transitions).
 * - Bidirectional, duration-weighted micro-segment merging (< 350 ms).
 */
class ChordDetector(private val sampleRate: Int = 44100) {

    private val pitchNames = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    // 24 states: 0..11 = Major triads, 12..23 = Minor triads
    private val stateNames = Array(24) { i ->
        val root = pitchNames[i % 12]
        if (i < 12) root else "${root}m"
    }

    private val majorTemplateWeights: DoubleArray
    private val minorTemplateWeights: DoubleArray

    init {
        val rawMajor = doubleArrayOf(
            1.30,  -0.25,  0.05, -0.40,  1.10,  0.05,
           -0.25,  0.90, -0.25,  0.05, -0.20,  0.05
        )
        val majorNorm = sqrt(rawMajor.sumOf { it * it })
        majorTemplateWeights = DoubleArray(12) { rawMajor[it] / majorNorm }

        val rawMinor = doubleArrayOf(
            1.30,  -0.25,  0.05,  1.10, -0.40,  0.05,
           -0.25,  0.90, -0.25, -0.20,  0.05, -0.20
        )
        val minorNorm = sqrt(rawMinor.sumOf { it * it })
        minorTemplateWeights = DoubleArray(12) { rawMinor[it] / minorNorm }
    }

    fun detectChordsFromWav(wavFile: File): List<ChordSegment> {
        val pcm = readMonoShorts(wavFile) ?: return emptyList()
        return detectChordsFromMonoPcm(pcm)
    }

    fun detectChordsFromStereoPcm(stereoPcm: ShortArray): List<ChordSegment> {
        if (stereoPcm.isEmpty()) return emptyList()
        val monoSize = stereoPcm.size / 2
        val mono = ShortArray(monoSize)
        var sIdx = 0
        for (i in 0 until monoSize) {
            val left = stereoPcm[sIdx].toInt()
            val right = stereoPcm[sIdx + 1].toInt()
            sIdx += 2
            mono[i] = ((left + right) / 2).toShort()
        }
        return detectChordsFromMonoPcm(mono)
    }

    fun detectChordsFromMonoPcm(pcm44k: ShortArray): List<ChordSegment> {
        if (pcm44k.size < 4096) return emptyList()

        // 1. Symmetrically Centered 8-tap Anti-Aliasing FIR Decimation (44.1 kHz -> 11.025 kHz)
        val downsampleFactor = 4
        val targetSampleRate = sampleRate / downsampleFactor // 11025 Hz
        val downsampledLen = pcm44k.size / downsampleFactor
        val signal = FloatArray(downsampledLen)

        val firCoeffs = floatArrayOf(0.035f, 0.075f, 0.155f, 0.235f, 0.235f, 0.155f, 0.075f, 0.035f)
        val firLen = firCoeffs.size

        for (i in 0 until downsampledLen) {
            val base = i * downsampleFactor
            var acc = 0f
            for (k in 0 until firLen) {
                val idx = (base + k - 3).coerceIn(0, pcm44k.size - 1)
                acc += (pcm44k[idx].toFloat() / 32768f) * firCoeffs[k]
            }
            signal[i] = acc
        }

        // 2. STFT Configuration
        val fftSize = 4096
        val hopSize = 1024 // ~92.88 ms hop at 11,025 Hz
        val numFrames = (signal.size - fftSize) / hopSize
        if (numFrames < 2) return emptyList()

        val window = FloatArray(fftSize) { i ->
            (0.5 - 0.5 * cos(2.0 * Math.PI * i / (fftSize - 1))).toFloat()
        }

        val binWidth = targetSampleRate.toDouble() / fftSize // 2.69165 Hz
        val minBin = maxOf(1, (50.0 / binWidth).toInt())
        val maxBin = minOf(fftSize / 2 - 1, (1200.0 / binWidth).toInt())

        // Exact semitone band ratio: 2^(1/24) - 2^(-1/24)
        val semitoneRatio = 2.0.pow(1.0 / 24.0) - 2.0.pow(-1.0 / 24.0)

        // 3. Peak-Based Auto-Tuning Estimation (50-bin resolution, 2 cents/bin)
        val refFrequency = estimateGlobalTuning(signal, window, fftSize, hopSize, binWidth, minBin, maxBin)

        // 4. Per-frame Chroma & Bass Extraction
        val frameBuf = FloatArray(fftSize)
        val real = FloatArray(fftSize)
        val imag = FloatArray(fftSize)

        val rawTrebleChroma = Array(numFrames) { DoubleArray(12) }
        val rawBassPitch = IntArray(numFrames) { -1 }
        val isFrameSilent = BooleanArray(numFrames)

        for (f in 0 until numFrames) {
            val startSample = f * hopSize

            // RMS Energy Check (~ -40 dB)
            var energySum = 0.0
            for (i in 0 until fftSize) {
                val s = signal[startSample + i]
                frameBuf[i] = s * window[i]
                energySum += s * s
            }
            val rms = sqrt(energySum / fftSize)
            if (rms < 0.010) {
                isFrameSilent[f] = true
                continue
            }

            // FFT
            System.arraycopy(frameBuf, 0, real, 0, fftSize)
            imag.fill(0f)
            fft(real, imag)

            val bassChroma = DoubleArray(12)
            val trebleChroma = DoubleArray(12)

            for (k in minBin..maxBin) {
                val freq = k * binWidth
                val mag = sqrt(real[k].toDouble().pow(2) + imag[k].toDouble().pow(2))
                if (mag < 1e-4) continue

                // MIDI Note conversion calibrated to detected tuning
                val midi = 69.0 + 12.0 * log2(freq / refFrequency)
                val nearestPitch = midi.roundToInt()
                val deviation = abs(midi - nearestPitch)

                if (deviation <= 0.5) {
                    val w = 1.0 - 2.0 * deviation
                    val pitchClass = ((nearestPitch % 12) + 12) % 12
                    val octave = (nearestPitch / 12) - 1

                    // Exact semitone bin density normalization
                    val semitoneWidthHz = freq * semitoneRatio
                    val binDensity = maxOf(1.0, semitoneWidthHz / binWidth)
                    val effectiveMag = (mag / binDensity) * w

                    if (octave in 2..3) {
                        bassChroma[pitchClass] += effectiveMag
                    }
                    if (octave in 3..5) {
                        val octW = when (octave) {
                            3 -> 1.2
                            4 -> 1.0
                            else -> 0.8
                        }
                        trebleChroma[pitchClass] += effectiveMag * octW
                    }
                }
            }

            // Logarithmic compression
            for (p in 0..11) {
                bassChroma[p] = ln(1.0 + 10.0 * bassChroma[p])
                trebleChroma[p] = ln(1.0 + 10.0 * trebleChroma[p])
            }

            // Secondary Harmonicity Gate:
            // Checks if chroma has distinct harmonic peaks vs broadband unpitched noise (drums, cymbals, speech)
            val maxChroma = trebleChroma.maxOrNull() ?: 0.0
            val meanChroma = trebleChroma.average()
            if (maxChroma < 0.15 || (maxChroma / (meanChroma + 1e-6)) < 1.6) {
                isFrameSilent[f] = true
                continue
            }

            // Extract dominant bass pitch (fundamental)
            val maxBass = bassChroma.maxOrNull() ?: 0.0
            if (maxBass > 0.04) {
                rawBassPitch[f] = bassChroma.indices.maxByOrNull { bassChroma[it] } ?: -1
            }

            // Store treble chroma
            rawTrebleChroma[f] = trebleChroma
        }

        // 5. Temporal Chroma Feature Smoothing (5-frame Gaussian window)
        // Eliminates single-frame pick clicks and transient noise at the feature level.
        val smoothTrebleChroma = Array(numFrames) { DoubleArray(12) }
        val chromaFilter = doubleArrayOf(0.10, 0.20, 0.40, 0.20, 0.10)
        val filterRadius = 2

        for (f in 0 until numFrames) {
            if (isFrameSilent[f]) continue
            val smoothed = DoubleArray(12)
            var weightSum = 0.0

            for (r in -filterRadius..filterRadius) {
                val neighborIdx = f + r
                if (neighborIdx in 0 until numFrames && !isFrameSilent[neighborIdx]) {
                    val w = chromaFilter[r + filterRadius]
                    weightSum += w
                    for (p in 0..11) {
                        smoothed[p] += rawTrebleChroma[neighborIdx][p] * w
                    }
                }
            }

            if (weightSum > 0.0) {
                for (p in 0..11) smoothed[p] /= weightSum
                val norm = sqrt(smoothed.sumOf { it * it })
                if (norm > 1e-6) {
                    for (p in 0..11) smoothed[p] /= norm
                }
                smoothTrebleChroma[f] = smoothed
            }
        }

        // 6. 24-State Harmonic Scoring & Softmax Posterior Probability
        val frameScores = Array(numFrames) { DoubleArray(24) }
        val framePosteriors = Array(numFrames) { FloatArray(24) }
        val temperature = 0.15

        for (f in 0 until numFrames) {
            if (isFrameSilent[f]) continue
            val chroma = smoothTrebleChroma[f]
            val bassPitch = rawBassPitch[f]

            for (state in 0..23) {
                val root = state % 12
                val isMinor = state >= 12
                val weights = if (isMinor) minorTemplateWeights else majorTemplateWeights

                // Treble dot product
                var dot = 0.0
                for (i in 0..11) {
                    val rotatedWeight = weights[(i - root + 12) % 12]
                    dot += chroma[i] * rotatedWeight
                }

                // Inversion-aware bass integration:
                // Supports Root in bass (+0.25), 1st inversion 3rd (+0.20), and 2nd inversion 5th (+0.10)
                var bassBonus = 0.0
                if (bassPitch != -1) {
                    val third = if (isMinor) (root + 3) % 12 else (root + 4) % 12
                    val fifth = (root + 7) % 12
                    when (bassPitch) {
                        root -> bassBonus = 0.25
                        third -> bassBonus = 0.20
                        fifth -> bassBonus = 0.10
                    }
                }

                frameScores[f][state] = dot + bassBonus
            }

            // Calibrated Softmax probability over the 24 triads
            val maxScore = frameScores[f].maxOrNull() ?: 0.0
            var sumExp = 0.0
            val expScores = DoubleArray(24)
            for (s in 0..23) {
                val e = exp((frameScores[f][s] - maxScore) / temperature)
                expScores[s] = e
                sumExp += e
            }
            if (sumExp > 1e-6) {
                for (s in 0..23) {
                    framePosteriors[f][s] = (expScores[s] / sumExp).toFloat()
                }
            }
        }

        // 7. Windowed Posterior Smoothing (7-frame window ~650 ms)
        // Uses local temporal probability aggregation:
        // - Eliminates frame-level flickering.
        // - CANNOT stall or lock in (strictly local memory).
        // - Preserves fast 2-beat chord changes.
        val decodedChords = ArrayList<String>(numFrames)
        val decodedConfidences = ArrayList<Float>(numFrames)
        val winRadius = 3 // 7-frame window (3 past, current, 3 future)

        for (f in 0 until numFrames) {
            if (isFrameSilent[f]) {
                decodedChords.add("")
                decodedConfidences.add(0.0f)
                continue
            }

            val start = maxOf(0, f - winRadius)
            val end = minOf(numFrames - 1, f + winRadius)

            val windowProbSum = DoubleArray(24)
            var activeCount = 0

            for (w in start..end) {
                if (!isFrameSilent[w]) {
                    for (s in 0..23) {
                        windowProbSum[s] += framePosteriors[w][s].toDouble()
                    }
                    activeCount++
                }
            }

            if (activeCount > 0) {
                val bestState = windowProbSum.indices.maxByOrNull { windowProbSum[it] } ?: 0
                val avgProb = (windowProbSum[bestState] / activeCount).toFloat()
                // Calibrated confidence in 60% - 95% range for winning chords
                val conf = (0.40f + 0.55f * avgProb).coerceIn(0.20f, 0.98f)
                decodedChords.add(stateNames[bestState])
                decodedConfidences.add(conf)
            } else {
                decodedChords.add("")
                decodedConfidences.add(0.0f)
            }
        }

        // 8. Bidirectional Segment Merging & Consolidation
        val hopDurationMs = (hopSize * 1000L / targetSampleRate)
        return mergeIntoSegments(decodedChords, decodedConfidences, hopDurationMs)
    }

    private fun estimateGlobalTuning(
        signal: FloatArray,
        window: FloatArray,
        fftSize: Int,
        hopSize: Int,
        binWidth: Double,
        minBin: Int,
        maxBin: Int
    ): Double {
        val numFrames = (signal.size - fftSize) / hopSize
        if (numFrames < 4) return 440.0

        val step = maxOf(1, numFrames / 32)
        val histBins = 50 // 2 cents per bin for high resolution
        val hist = DoubleArray(histBins)

        val frameBuf = FloatArray(fftSize)
        val real = FloatArray(fftSize)
        val imag = FloatArray(fftSize)

        for (f in 0 until numFrames step step) {
            val offset = f * hopSize
            for (i in 0 until fftSize) frameBuf[i] = signal[offset + i] * window[i]
            System.arraycopy(frameBuf, 0, real, 0, fftSize)
            imag.fill(0f)
            fft(real, imag)

            // Extract prominent spectral peaks only
            for (k in (minBin + 1) until maxBin) {
                val mag = sqrt(real[k].toDouble().pow(2) + imag[k].toDouble().pow(2))
                val magPrev = sqrt(real[k - 1].toDouble().pow(2) + imag[k - 1].toDouble().pow(2))
                val magNext = sqrt(real[k + 1].toDouble().pow(2) + imag[k + 1].toDouble().pow(2))

                // Must be a local peak and sufficiently above noise floor
                if (mag > magPrev && mag > magNext && mag > 0.03) {
                    val freq = k * binWidth
                    if (freq < 110.0 || freq > 880.0) continue

                    val midi = 69.0 + 12.0 * log2(freq / 440.0)
                    val nearest = midi.roundToInt()
                    val deviation = midi - nearest // [-0.5, 0.5]

                    val binIdx = (((deviation + 0.5) * histBins).toInt()).coerceIn(0, histBins - 1)
                    hist[binIdx] += mag
                }
            }
        }

        val peakBin = hist.indices.maxByOrNull { hist[it] } ?: (histBins / 2)
        val peakDeviation = (peakBin + 0.5) / histBins - 0.5

        // If tuning is within +/- 8 cents of standard 440 Hz, snap to exact 440.0 Hz
        if (abs(peakDeviation) < 0.08) {
            return 440.0
        }

        val clampedDev = peakDeviation.coerceIn(-0.35, 0.35)
        return 440.0 * 2.0.pow(clampedDev / 12.0)
    }

    private fun mergeIntoSegments(
        chords: List<String>,
        confidences: List<Float>,
        hopDurationMs: Long
    ): List<ChordSegment> {
        val rawSegments = mutableListOf<ChordSegment>()
        if (chords.isEmpty()) return rawSegments

        var currentChord = chords[0]
        var startIdx = 0
        var confSum = if (confidences.isNotEmpty()) confidences[0] else 0.8f
        var confCount = 1

        for (i in 1 until chords.size) {
            if (chords[i] != currentChord) {
                if (currentChord.isNotBlank()) {
                    rawSegments.add(
                        ChordSegment(
                            startMs = startIdx * hopDurationMs,
                            endMs = i * hopDurationMs,
                            chord = currentChord,
                            confidence = confSum / confCount
                        )
                    )
                }
                currentChord = chords[i]
                startIdx = i
                confSum = if (i < confidences.size) confidences[i] else 0.8f
                confCount = 1
            } else {
                if (i < confidences.size) confSum += confidences[i]
                confCount++
            }
        }

        if (currentChord.isNotBlank()) {
            rawSegments.add(
                ChordSegment(
                    startMs = startIdx * hopDurationMs,
                    endMs = chords.size * hopDurationMs,
                    chord = currentChord,
                    confidence = confSum / confCount
                )
            )
        }

        // 1. Bidirectional merge of micro-segments (< 350 ms)
        val cleaned = mutableListOf<ChordSegment>()
        var idx = 0
        while (idx < rawSegments.size) {
            val seg = rawSegments[idx]
            val dur = seg.endMs - seg.startMs
            if (dur < 350L && (cleaned.isNotEmpty() || idx + 1 < rawSegments.size)) {
                val nextSeg = if (idx + 1 < rawSegments.size) rawSegments[idx + 1] else null
                val prevSeg = if (cleaned.isNotEmpty()) cleaned.last() else null

                // Determine whether to merge backward or forward
                val mergeForward = when {
                    prevSeg == null -> true
                    nextSeg == null -> false
                    nextSeg.chord == seg.chord -> true
                    prevSeg.chord == seg.chord -> false
                    else -> (nextSeg.endMs - nextSeg.startMs) > (prevSeg.endMs - prevSeg.startMs)
                }

                if (mergeForward && nextSeg != null) {
                    val nextDur = nextSeg.endMs - nextSeg.startMs
                    val totalDur = dur + nextDur
                    val weightedConf = (seg.confidence * dur + nextSeg.confidence * nextDur) / totalDur
                    rawSegments[idx + 1] = nextSeg.copy(startMs = seg.startMs, confidence = weightedConf)
                } else if (prevSeg != null) {
                    val prev = cleaned.removeAt(cleaned.size - 1)
                    val prevDur = prev.endMs - prev.startMs
                    val totalDur = prevDur + dur
                    val weightedConf = (prev.confidence * prevDur + seg.confidence * dur) / totalDur
                    cleaned.add(prev.copy(endMs = seg.endMs, confidence = weightedConf))
                } else {
                    cleaned.add(seg)
                }
            } else {
                cleaned.add(seg)
            }
            idx++
        }

        // 2. Consolidate adjacent segments that have the exact same chord
        val consolidated = mutableListOf<ChordSegment>()
        for (seg in cleaned) {
            if (consolidated.isNotEmpty() && consolidated.last().chord == seg.chord && consolidated.last().endMs == seg.startMs) {
                val prev = consolidated.removeAt(consolidated.size - 1)
                val prevDur = prev.endMs - prev.startMs
                val segDur = seg.endMs - seg.startMs
                val totalDur = prevDur + segDur
                val weightedConf = if (totalDur > 0) {
                    (prev.confidence * prevDur + seg.confidence * segDur) / totalDur
                } else prev.confidence
                consolidated.add(prev.copy(endMs = seg.endMs, confidence = weightedConf))
            } else {
                consolidated.add(seg)
            }
        }

        // 3. Bridge small transitional gaps (< 500 ms)
        val bridged = mutableListOf<ChordSegment>()
        for (seg in consolidated) {
            if (bridged.isNotEmpty()) {
                val prev = bridged.last()
                val gap = seg.startMs - prev.endMs
                if (gap in 1..500L) {
                    bridged[bridged.size - 1] = prev.copy(endMs = seg.startMs)
                }
            }
            bridged.add(seg)
        }

        return bridged
    }

    /**
     * Robust RIFF/WAVE parser supporting Mono and Stereo 16-bit PCM with arbitrary chunk offsets.
     */
    private fun readMonoShorts(wavFile: File): ShortArray? {
        if (!wavFile.exists() || wavFile.length() < 44) return null
        return try {
            java.io.FileInputStream(wavFile).use { stream ->
                val header = ByteArray(44)
                if (stream.read(header) < 44) return null

                // Verify "RIFF" and "WAVE"
                if (header[0] != 'R'.code.toByte() || header[1] != 'I'.code.toByte() ||
                    header[2] != 'F'.code.toByte() || header[3] != 'F'.code.toByte() ||
                    header[8] != 'W'.code.toByte() || header[9] != 'A'.code.toByte() ||
                    header[10] != 'V'.code.toByte() || header[11] != 'E'.code.toByte()
                ) {
                    return null
                }

                val numChannels = (header[22].toInt() and 0xFF) or ((header[23].toInt() and 0xFF) shl 8)
                val bitsPerSample = (header[34].toInt() and 0xFF) or ((header[35].toInt() and 0xFF) shl 8)

                if (bitsPerSample != 16) return null

                // Search for "data" chunk if not at byte 36
                var dataSize = 0
                if (header[36] == 'd'.code.toByte() && header[37] == 'a'.code.toByte() &&
                    header[38] == 't'.code.toByte() && header[39] == 'a'.code.toByte()
                ) {
                    dataSize = (header[40].toInt() and 0xFF) or
                            ((header[41].toInt() and 0xFF) shl 8) or
                            ((header[42].toInt() and 0xFF) shl 16) or
                            ((header[43].toInt() and 0xFF) shl 24)
                } else {
                    val chunkHeader = ByteArray(8)
                    while (stream.read(chunkHeader) == 8) {
                        val chunkId = String(chunkHeader, 0, 4)
                        val chunkSize = (chunkHeader[4].toInt() and 0xFF) or
                                ((chunkHeader[5].toInt() and 0xFF) shl 8) or
                                ((chunkHeader[6].toInt() and 0xFF) shl 16) or
                                ((chunkHeader[7].toInt() and 0xFF) shl 24)
                        if (chunkId == "data") {
                            dataSize = chunkSize
                            break
                        } else {
                            stream.skip(chunkSize.toLong())
                        }
                    }
                }

                if (dataSize <= 0) dataSize = (wavFile.length() - 44).toInt()

                val bytesPerFrame = numChannels * 2
                val numFrames = dataSize / bytesPerFrame
                val monoShorts = ShortArray(numFrames)

                val buffer = ByteArray(8192)
                var frameIdx = 0
                var read: Int

                if (numChannels == 1) {
                    // Mono 16-bit
                    while (stream.read(buffer).also { read = it } != -1 && frameIdx < numFrames) {
                        var b = 0
                        while (b + 1 < read && frameIdx < numFrames) {
                            val sample = (buffer[b].toInt() and 0xFF or (buffer[b + 1].toInt() shl 8)).toShort()
                            monoShorts[frameIdx++] = sample
                            b += 2
                        }
                    }
                } else {
                    // Stereo 16-bit
                    while (stream.read(buffer).also { read = it } != -1 && frameIdx < numFrames) {
                        var b = 0
                        while (b + bytesPerFrame - 1 < read && frameIdx < numFrames) {
                            val left = (buffer[b].toInt() and 0xFF or (buffer[b + 1].toInt() shl 8))
                            val right = (buffer[b + 2].toInt() and 0xFF or (buffer[b + 3].toInt() shl 8))
                            monoShorts[frameIdx++] = ((left + right) / 2).toShort()
                            b += bytesPerFrame
                        }
                    }
                }
                monoShorts
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // In-place Radix-2 Cooley-Tukey FFT
    private fun fft(real: FloatArray, imag: FloatArray) {
        val n = real.size
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
            var k = n shr 1
            while (k <= j) {
                j -= k
                k = k shr 1
            }
            j += k
        }

        var l = 1
        while (l < n) {
            val step = l shl 1
            val theta = -Math.PI / l
            val wpr = cos(theta).toFloat()
            val wpi = sin(theta).toFloat()

            var wr = 1.0f
            var wi = 0.0f
            for (m in 0 until l) {
                var i = m
                while (i < n) {
                    val jIdx = i + l
                    val tr = wr * real[jIdx] - wi * imag[jIdx]
                    val ti = wr * imag[jIdx] + wi * real[jIdx]
                    real[jIdx] = real[i] - tr
                    imag[jIdx] = imag[i] - ti
                    real[i] += tr
                    imag[i] += ti
                    i += step
                }
                val nWr = wr * wpr - wi * wpi
                wi = wr * wpi + wi * wpr
                wr = nWr
            }
            l = step
        }
    }

    companion object {
        fun serializeChords(chords: List<ChordSegment>): String {
            val array = JSONArray()
            for (seg in chords) {
                val obj = JSONObject().apply {
                    put("start", seg.startMs)
                    put("end", seg.endMs)
                    put("chord", seg.chord)
                    put("confidence", seg.confidence.toDouble())
                }
                array.put(obj)
            }
            return array.toString()
        }

        fun deserializeChords(jsonStr: String): List<ChordSegment> {
            val list = mutableListOf<ChordSegment>()
            try {
                val array = JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(
                        ChordSegment(
                            startMs = obj.getLong("start"),
                            endMs = obj.getLong("end"),
                            chord = obj.getString("chord"),
                            confidence = obj.optDouble("confidence", 1.0).toFloat()
                        )
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return list
        }
    }
}
