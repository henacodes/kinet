package com.kirakos.kinet

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class ChordDetectorTest {

    private fun generateSineChordPcm(
        chordFrequencies: List<Double>,
        bassFreq: Double,
        durationSeconds: Double,
        sampleRate: Int = 44100
    ): ShortArray {
        val totalSamples = (durationSeconds * sampleRate).toInt()
        val pcm = ShortArray(totalSamples)

        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate
            var sample = 0.0

            // Bass note (strong fundamental + 2nd harmonic)
            sample += 0.40 * sin(2.0 * PI * bassFreq * t)
            sample += 0.20 * sin(2.0 * PI * (bassFreq * 2.0) * t)

            // Chord notes (with fundamental + 2nd harmonic + small 3rd harmonic)
            for (f in chordFrequencies) {
                sample += 0.25 * sin(2.0 * PI * f * t)
                sample += 0.12 * sin(2.0 * PI * (f * 2.0) * t)
                sample += 0.05 * sin(2.0 * PI * (f * 3.0) * t)
            }

            pcm[i] = (sample * 16000.0).coerceIn(-32768.0, 32767.0).toInt().toShort()
        }
        return pcm
    }

    @Test
    fun testLetItBeProgression() {
        val sampleRate = 44100
        val chordDuration = 2.0 // 2 seconds per chord

        // "Let It Be" classic progression: C -> G -> Am -> F
        // C Major: C3 (130.81), E3 (164.81), G3 (196.00) with bass C2 (65.41)
        val pcmC = generateSineChordPcm(listOf(130.81, 164.81, 196.00), 65.41, chordDuration, sampleRate)

        // G Major: G3 (196.00), B3 (246.94), D4 (293.66) with bass G2 (98.00)
        val pcmG = generateSineChordPcm(listOf(196.00, 246.94, 293.66), 98.00, chordDuration, sampleRate)

        // A Minor: A3 (220.00), C4 (261.63), E4 (329.63) with bass A2 (110.00)
        val pcmAm = generateSineChordPcm(listOf(220.00, 261.63, 329.63), 110.00, chordDuration, sampleRate)

        // F Major: F3 (174.61), A3 (220.00), C4 (261.63) with bass F2 (87.31)
        val pcmF = generateSineChordPcm(listOf(174.61, 220.00, 261.63), 87.31, chordDuration, sampleRate)

        // Concatenate full song progression: C -> G -> Am -> F
        val fullPcm = ShortArray(pcmC.size + pcmG.size + pcmAm.size + pcmF.size)
        System.arraycopy(pcmC, 0, fullPcm, 0, pcmC.size)
        System.arraycopy(pcmG, 0, fullPcm, pcmC.size, pcmG.size)
        System.arraycopy(pcmAm, 0, fullPcm, pcmC.size + pcmG.size, pcmAm.size)
        System.arraycopy(pcmF, 0, fullPcm, pcmC.size + pcmG.size + pcmAm.size, pcmF.size)

        val detector = ChordDetector(sampleRate = sampleRate)
        val segments = detector.detectChordsFromMonoPcm(fullPcm)

        println("Detected segments:")
        for (seg in segments) {
            println("${seg.startMs}ms - ${seg.endMs}ms: ${seg.chord} (confidence: ${(seg.confidence * 100).toInt()}%)")
        }

        val chords = segments.map { it.chord }
        assertTrue("Progression should contain C", chords.contains("C"))
        assertTrue("Progression should contain G", chords.contains("G"))
        assertTrue("Progression should contain Am", chords.contains("Am"))
        assertTrue("Progression should contain F", chords.contains("F"))

        // Verify that it NEVER outputs 7ths
        assertFalse("Should not output C7", chords.contains("C7"))
        assertFalse("Should not output G7", chords.contains("G7"))
        assertFalse("Should not output A7", chords.contains("A7"))
        assertFalse("Should not output F7", chords.contains("F7"))
        assertFalse("Should not output Cmaj7", chords.contains("Cmaj7"))
        assertFalse("Should not output Gmaj7", chords.contains("Gmaj7"))
    }

    @Test
    fun testDetunedChord() {
        val sampleRate = 44100
        val chordDuration = 2.0
        // A432 tuning (-31.8 cents)
        val tuningFactor = 432.0 / 440.0
        val pcmC = generateSineChordPcm(
            listOf(130.81 * tuningFactor, 164.81 * tuningFactor, 196.00 * tuningFactor),
            65.41 * tuningFactor,
            chordDuration,
            sampleRate
        )
        val detector = ChordDetector(sampleRate = sampleRate)
        val segments = detector.detectChordsFromMonoPcm(pcmC)
        println("Detuned (432Hz) segments:")
        for (seg in segments) {
            println("${seg.startMs}ms - ${seg.endMs}ms: ${seg.chord} (confidence: ${(seg.confidence * 100).toInt()}%)")
        }
    }

    @Test
    fun testCsus4Chord() {
        val sampleRate = 44100
        val chordDuration = 2.0
        // Csus4: C3 (130.81), F3 (174.61), G3 (196.00), bass C2 (65.41)
        val pcmCsus4 = generateSineChordPcm(
            listOf(130.81, 174.61, 196.00),
            65.41,
            chordDuration,
            sampleRate
        )
        val detector = ChordDetector(sampleRate = sampleRate)
        val segments = detector.detectChordsFromMonoPcm(pcmCsus4)
        println("Csus4 chord segments:")
        for (seg in segments) {
            println("${seg.startMs}ms - ${seg.endMs}ms: ${seg.chord} (confidence: ${(seg.confidence * 100).toInt()}%)")
        }
    }

    @Test
    fun testBassInversion() {
        val sampleRate = 44100
        val chordDuration = 2.0
        // C Major chord with E2 (82.41 Hz) in bass (1st inversion C/E)
        val pcmCwithE = generateSineChordPcm(
            listOf(130.81, 164.81, 196.00),
            82.41, // E2 in bass
            chordDuration,
            sampleRate
        )
        val detector = ChordDetector(sampleRate = sampleRate)
        val segments = detector.detectChordsFromMonoPcm(pcmCwithE)
        println("Bass inversion (C/E) segments:")
        for (seg in segments) {
            println("${seg.startMs}ms - ${seg.endMs}ms: ${seg.chord} (confidence: ${(seg.confidence * 100).toInt()}%)")
        }
        val chords = segments.map { it.chord }
        assertTrue("Inversion C/E should be identified as C", chords.contains("C"))
    }

    @Test
    fun testMinorBassInversion() {
        val sampleRate = 44100
        val chordDuration = 2.0
        // Am chord with C3 in bass (Am/C)
        val pcmAmWithC = generateSineChordPcm(
            listOf(220.00, 261.63, 329.63),
            65.41, // C2 in bass
            chordDuration,
            sampleRate
        )
        val detector = ChordDetector(sampleRate = sampleRate)
        val segments = detector.detectChordsFromMonoPcm(pcmAmWithC)
        println("Bass inversion (Am/C) segments:")
        for (seg in segments) {
            println("${seg.startMs}ms - ${seg.endMs}ms: ${seg.chord} (confidence: ${(seg.confidence * 100).toInt()}%)")
        }
        val chords = segments.map { it.chord }
        assertTrue("Inversion Am/C should be identified as Am", chords.contains("Am"))
    }

    @Test
    fun testSeventhChordResolvesToTriad() {
        val sampleRate = 44100
        val chordDuration = 2.0
        // Cmaj7: C3 (130.81), E3 (164.81), G3 (196.00), B3 (246.94) with bass C2 (65.41)
        val pcmCmaj7 = generateSineChordPcm(
            listOf(130.81, 164.81, 196.00, 246.94),
            65.41,
            chordDuration,
            sampleRate
        )
        val detector = ChordDetector(sampleRate = sampleRate)
        val segments = detector.detectChordsFromMonoPcm(pcmCmaj7)
        println("Cmaj7 segments:")
        for (seg in segments) {
            println("${seg.startMs}ms - ${seg.endMs}ms: ${seg.chord} (confidence: ${(seg.confidence * 100).toInt()}%)")
        }
        val chords = segments.map { it.chord }
        assertTrue("Cmaj7 should resolve cleanly to C", chords.contains("C"))
        assertFalse("Should not output Cmaj7 string", chords.contains("Cmaj7"))
    }

    @Test
    fun testLetItBe8BarProgression() {
        val sampleRate = 44100
        val chordDuration = 2.0 // 2 seconds per chord

        // 8-bar verse: C -> G -> Am -> F -> C -> G -> F -> C
        val pcmC = generateSineChordPcm(listOf(130.81, 164.81, 196.00), 65.41, chordDuration, sampleRate)
        val pcmG = generateSineChordPcm(listOf(196.00, 246.94, 293.66), 98.00, chordDuration, sampleRate)
        val pcmAm = generateSineChordPcm(listOf(220.00, 261.63, 329.63), 110.00, chordDuration, sampleRate)
        val pcmF = generateSineChordPcm(listOf(174.61, 220.00, 261.63), 87.31, chordDuration, sampleRate)

        val fullPcm = ShortArray(pcmC.size * 8)
        var offset = 0
        // 1. C
        System.arraycopy(pcmC, 0, fullPcm, offset, pcmC.size); offset += pcmC.size
        // 2. G
        System.arraycopy(pcmG, 0, fullPcm, offset, pcmG.size); offset += pcmG.size
        // 3. Am
        System.arraycopy(pcmAm, 0, fullPcm, offset, pcmAm.size); offset += pcmAm.size
        // 4. F
        System.arraycopy(pcmF, 0, fullPcm, offset, pcmF.size); offset += pcmF.size
        // 5. C
        System.arraycopy(pcmC, 0, fullPcm, offset, pcmC.size); offset += pcmC.size
        // 6. G
        System.arraycopy(pcmG, 0, fullPcm, offset, pcmG.size); offset += pcmG.size
        // 7. F
        System.arraycopy(pcmF, 0, fullPcm, offset, pcmF.size); offset += pcmF.size
        // 8. C
        System.arraycopy(pcmC, 0, fullPcm, offset, pcmC.size); offset += pcmC.size

        val detector = ChordDetector(sampleRate = sampleRate)
        val segments = detector.detectChordsFromMonoPcm(fullPcm)

        println("8-bar Let It Be detected segments:")
        for (seg in segments) {
            println("${seg.startMs}ms - ${seg.endMs}ms: ${seg.chord} (confidence: ${(seg.confidence * 100).toInt()}%)")
        }
    }
}

