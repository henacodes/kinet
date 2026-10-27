package com.kirakos.kinet

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

class DualStemPlayer {

    private val tag = "DualStemPlayer"
    private val sampleRate = 44100
    private val channels = 2
    private val frameSizeShorts = 2 // stereo: 1 left, 1 right

    private val audioLock = Any()
    private val audioScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var audioTrack: AudioTrack? = null
    private var vocalsPcm: ShortArray? = null
    private var drumsPcm: ShortArray? = null
    private var bassPcm: ShortArray? = null
    private var otherPcm: ShortArray? = null
    private var accompPcm: ShortArray? = null
    private var totalFrames: Int = 0

    private var playbackJob: Job? = null
    @Volatile private var isPlayingInternal = false
    @Volatile private var currentFrame = 0

    @Volatile private var isScratching = false
    @Volatile private var wasPlayingBeforeScratch = false
    private val scratchWriteBuffer = ShortArray(1024 * frameSizeShorts)

    // State Flows
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    // 4-Stem Volumes
    private val _vocalsVolume = MutableStateFlow(1.0f)
    val vocalsVolume: StateFlow<Float> = _vocalsVolume.asStateFlow()

    private val _drumsVolume = MutableStateFlow(1.0f)
    val drumsVolume: StateFlow<Float> = _drumsVolume.asStateFlow()

    private val _bassVolume = MutableStateFlow(1.0f)
    val bassVolume: StateFlow<Float> = _bassVolume.asStateFlow()

    private val _otherVolume = MutableStateFlow(1.0f)
    val otherVolume: StateFlow<Float> = _otherVolume.asStateFlow()

    private val _accompVolume = MutableStateFlow(1.0f)
    val accompVolume: StateFlow<Float> = _accompVolume.asStateFlow()

    // 4-Stem Mutes
    private val _isVocalsMuted = MutableStateFlow(false)
    val isVocalsMuted: StateFlow<Boolean> = _isVocalsMuted.asStateFlow()

    private val _isDrumsMuted = MutableStateFlow(false)
    val isDrumsMuted: StateFlow<Boolean> = _isDrumsMuted.asStateFlow()

    private val _isBassMuted = MutableStateFlow(false)
    val isBassMuted: StateFlow<Boolean> = _isBassMuted.asStateFlow()

    private val _isOtherMuted = MutableStateFlow(false)
    val isOtherMuted: StateFlow<Boolean> = _isOtherMuted.asStateFlow()

    private val _isAccompMuted = MutableStateFlow(false)
    val isAccompMuted: StateFlow<Boolean> = _isAccompMuted.asStateFlow()

    // 4-Stem Solos
    private val _isVocalsSolo = MutableStateFlow(false)
    val isVocalsSolo: StateFlow<Boolean> = _isVocalsSolo.asStateFlow()

    private val _isDrumsSolo = MutableStateFlow(false)
    val isDrumsSolo: StateFlow<Boolean> = _isDrumsSolo.asStateFlow()

    private val _isBassSolo = MutableStateFlow(false)
    val isBassSolo: StateFlow<Boolean> = _isBassSolo.asStateFlow()

    private val _isOtherSolo = MutableStateFlow(false)
    val isOtherSolo: StateFlow<Boolean> = _isOtherSolo.asStateFlow()

    private val _pitchSemitones = MutableStateFlow(0)
    val pitchSemitones: StateFlow<Int> = _pitchSemitones.asStateFlow()

    private val _loopStartMs = MutableStateFlow<Long?>(null)
    val loopStartMs: StateFlow<Long?> = _loopStartMs.asStateFlow()

    private val _loopEndMs = MutableStateFlow<Long?>(null)
    val loopEndMs: StateFlow<Long?> = _loopEndMs.asStateFlow()

    private val _isLooping = MutableStateFlow(false)
    val isLooping: StateFlow<Boolean> = _isLooping.asStateFlow()

    private val _waveform = MutableStateFlow<FloatArray>(FloatArray(0))
    val waveform: StateFlow<FloatArray> = _waveform.asStateFlow()

    private val _chordSegments = MutableStateFlow<List<ChordSegment>>(emptyList())
    val chordSegments: StateFlow<List<ChordSegment>> = _chordSegments.asStateFlow()

    private var chordJob: Job? = null

    var loadedProject: SongProject? = null
        private set

    private val _currentProject = MutableStateFlow<SongProject?>(null)
    val currentProject: StateFlow<SongProject?> = _currentProject.asStateFlow()

    fun load(project: SongProject) {
        loadedProject = project
        _currentProject.value = project
        stop()
        releaseAudioTrack()

        val vocFile = project.vocalsFile
        val accFile = project.accompanimentFile

        vocalsPcm = readWavShorts(vocFile)
        accompPcm = readWavShorts(accFile)
        drumsPcm = project.drumsFile?.let { if (it.exists()) readWavShorts(it) else null }
        bassPcm = project.bassFile?.let { if (it.exists()) readWavShorts(it) else null }
        otherPcm = project.otherFile?.let { if (it.exists()) readWavShorts(it) else null }

        val vLen = (vocalsPcm?.size ?: 0) / frameSizeShorts
        val aLen = (accompPcm?.size ?: 0) / frameSizeShorts
        totalFrames = minOf(vLen, aLen)

        val totalMs = (totalFrames.toDouble() / sampleRate * 1000).toLong()
        _durationMs.value = totalMs
        _positionMs.value = 0L
        currentFrame = 0

        _loopStartMs.value = null
        _loopEndMs.value = null
        _isLooping.value = false
        _pitchSemitones.value = 0

        // Reset volume and mute/solo states
        _vocalsVolume.value = 1.0f
        _drumsVolume.value = 1.0f
        _bassVolume.value = 1.0f
        _otherVolume.value = 1.0f
        _accompVolume.value = 1.0f

        _isVocalsMuted.value = false
        _isDrumsMuted.value = false
        _isBassMuted.value = false
        _isOtherMuted.value = false
        _isAccompMuted.value = false

        _isVocalsSolo.value = false
        _isDrumsSolo.value = false
        _isBassSolo.value = false
        _isOtherSolo.value = false

        computeWaveform()

        chordJob?.cancel()
        _chordSegments.value = emptyList()

        chordJob = CoroutineScope(Dispatchers.Default).launch {
            if (project.chordsFile.exists()) {
                try {
                    val json = project.chordsFile.readText()
                    val parsed = ChordDetector.deserializeChords(json)
                    val hasBuggySeventhChords = parsed.any { it.chord.contains("7") }
                    if (parsed.isNotEmpty() && !hasBuggySeventhChords) {
                        _chordSegments.value = parsed
                        return@launch
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            val pcm = accompPcm
            val detector = ChordDetector()
            val detected = if (pcm != null && pcm.isNotEmpty()) {
                detector.detectChordsFromStereoPcm(pcm)
            } else {
                detector.detectChordsFromWav(project.accompanimentFile)
            }

            if (isActive && detected.isNotEmpty()) {
                _chordSegments.value = detected
                try {
                    project.chordsFile.writeText(ChordDetector.serializeChords(detected))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        initAudioTrack()
    }

    fun reanalyzeChords(project: SongProject) {
        chordJob?.cancel()
        _chordSegments.value = emptyList()
        try {
            if (project.chordsFile.exists()) {
                project.chordsFile.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        chordJob = CoroutineScope(Dispatchers.Default).launch {
            val pcm = accompPcm
            val detector = ChordDetector()
            val detected = if (pcm != null && pcm.isNotEmpty()) {
                detector.detectChordsFromStereoPcm(pcm)
            } else {
                detector.detectChordsFromWav(project.accompanimentFile)
            }

            if (isActive && detected.isNotEmpty()) {
                _chordSegments.value = detected
                try {
                    project.chordsFile.writeText(ChordDetector.serializeChords(detected))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun computeWaveform() {
        val numBars = 64
        val peaks = FloatArray(numBars) { 0.15f }
        val pcm = accompPcm ?: vocalsPcm
        if (pcm != null && pcm.isNotEmpty()) {
            val totalSamples = pcm.size
            val step = totalSamples / numBars
            var maxPeak = 0.01f
            for (i in 0 until numBars) {
                val start = i * step
                val end = minOf(start + step, totalSamples)
                var sumSquares = 0.0
                var count = 0
                for (j in start until end step 8) {
                    val sample = pcm[j].toFloat() / 32768f
                    sumSquares += sample * sample
                    count++
                }
                val rms = if (count > 0) kotlin.math.sqrt(sumSquares / count).toFloat() else 0.05f
                peaks[i] = rms
                if (rms > maxPeak) maxPeak = rms
            }
            for (i in 0 until numBars) {
                peaks[i] = (peaks[i] / maxPeak).coerceIn(0.12f, 1.0f)
            }
        }
        _waveform.value = peaks
    }

    private fun initAudioTrack() {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf * 2, 16384)

        synchronized(audioLock) {
            try {
                audioTrack?.stop()
                audioTrack?.release()
            } catch (e: Exception) {
                // ignore
            }
            audioTrack = null

            try {
                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build()
            } catch (e: Exception) {
                // Fallback without LOW_LATENCY if unsupported by vendor ROM
                try {
                    audioTrack = AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build()
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(sampleRate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                                .build()
                        )
                        .setBufferSizeInBytes(maxOf(minBuf * 4, 32768))
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()
                } catch (ex: Exception) {
                    ex.printStackTrace()
                }
            }
        }

        applyPitchParams()
    }

    fun play() {
        if (totalFrames == 0 || isPlayingInternal) return
        isPlayingInternal = true
        _isPlaying.value = true
        isScratching = false

        playbackJob?.cancel()
        playbackJob = audioScope.launch {
            val voc = vocalsPcm ?: return@launch
            val acc = accompPcm ?: return@launch
            val drums = drumsPcm
            val bass = bassPcm
            val other = otherPcm
            val isFourStem = drums != null && bass != null && other != null

            val maxPcmIndex = minOf(voc.size, acc.size) - 2
            if (maxPcmIndex < 0) return@launch

            val chunkSizeFrames = 2048
            val writeBuffer = ShortArray(chunkSizeFrames * frameSizeShorts)

            synchronized(audioLock) {
                try {
                    if (audioTrack?.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        audioTrack?.play()
                    }
                } catch (e: Exception) {
                    // ignore
                }
            }

            while (isActive && isPlayingInternal) {
                // Check A/B Loop
                val loopStart = _loopStartMs.value
                val loopEnd = _loopEndMs.value
                val isLoopActive = _isLooping.value && loopStart != null && loopEnd != null && loopEnd > loopStart

                val loopEndFrame = if (isLoopActive) ((loopEnd / 1000.0) * sampleRate).toInt() else totalFrames
                val loopStartFrame = if (isLoopActive) ((loopStart / 1000.0) * sampleRate).toInt().coerceIn(0, totalFrames) else 0

                if (currentFrame >= loopEndFrame || currentFrame >= totalFrames || (isLoopActive && currentFrame < loopStartFrame)) {
                    if (isLoopActive) {
                        currentFrame = loopStartFrame
                    } else {
                        // Reached end of audio
                        currentFrame = 0
                        withContext(Dispatchers.Main) {
                            pause()
                            _positionMs.value = 0L
                        }
                        break
                    }
                }

                val framesToWrite = minOf(chunkSizeFrames, loopEndFrame - currentFrame)
                val shortsToWrite = framesToWrite * frameSizeShorts

                val anySolo = _isVocalsSolo.value || _isDrumsSolo.value || _isBassSolo.value || _isOtherSolo.value
                val vVol = if (anySolo) (if (_isVocalsSolo.value) _vocalsVolume.value else 0f) else (if (_isVocalsMuted.value) 0f else _vocalsVolume.value)
                val dVol = if (anySolo) (if (_isDrumsSolo.value) _drumsVolume.value else 0f) else (if (_isDrumsMuted.value) 0f else _drumsVolume.value)
                val bVol = if (anySolo) (if (_isBassSolo.value) _bassVolume.value else 0f) else (if (_isBassMuted.value) 0f else _bassVolume.value)
                val oVol = if (anySolo) (if (_isOtherSolo.value) _otherVolume.value else 0f) else (if (_isOtherMuted.value) 0f else _otherVolume.value)
                val aVol = if (anySolo) 0f else (if (_isAccompMuted.value) 0f else _accompVolume.value)

                var pcmIdx = currentFrame * frameSizeShorts
                for (i in 0 until shortsToWrite) {
                    val idx = (pcmIdx + i).coerceIn(0, maxPcmIndex + 1)
                    val mixed = if (drums != null && bass != null && other != null) {
                        voc[idx] * vVol + drums[idx] * dVol + bass[idx] * bVol + other[idx] * oVol
                    } else {
                        voc[idx] * vVol + acc[idx] * aVol
                    }
                    writeBuffer[i] = mixed.toInt().coerceIn(-32768, 32767).toShort()
                }

                synchronized(audioLock) {
                    if (!isActive || !isPlayingInternal) return@launch
                    try {
                        audioTrack?.write(writeBuffer, 0, shortsToWrite)
                    } catch (e: Exception) {
                        // ignore
                    }
                }

                currentFrame += framesToWrite

                val currentMs = (currentFrame.toDouble() / sampleRate * 1000).toLong()
                _positionMs.value = currentMs
            }
        }
    }

    fun pause() {
        isPlayingInternal = false
        _isPlaying.value = false
        playbackJob?.cancel()
        audioScope.launch {
            synchronized(audioLock) {
                try {
                    audioTrack?.pause()
                    audioTrack?.flush()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun togglePlayPause() {
        if (_isPlaying.value) pause() else play()
    }

    fun stop() {
        pause()
        currentFrame = 0
        _positionMs.value = 0L
    }

    fun clearProject() {
        stop()
        loadedProject = null
        _currentProject.value = null
    }

    fun seekTo(targetMs: Long) {
        val loopStart = _loopStartMs.value
        val loopEnd = _loopEndMs.value
        val isLoopActive = _isLooping.value && loopStart != null && loopEnd != null && loopEnd > loopStart

        val clampedMs = if (isLoopActive) {
            targetMs.coerceIn(loopStart, loopEnd)
        } else {
            targetMs.coerceIn(0L, _durationMs.value)
        }
        val targetFrame = ((clampedMs / 1000.0) * sampleRate).toInt().coerceIn(0, totalFrames)
        currentFrame = targetFrame
        _positionMs.value = clampedMs

        if (isPlayingInternal) {
            audioScope.launch {
                synchronized(audioLock) {
                    try {
                        audioTrack?.flush()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    fun seekRelative(deltaMs: Long) {
        seekTo(_positionMs.value + deltaMs)
    }

    fun jumpToLoopStart() {
        val loopStart = _loopStartMs.value
        if (loopStart != null) {
            seekTo(loopStart)
        } else {
            seekTo(0L)
        }
    }

    fun startScratch() {
        if (!isScratching) {
            wasPlayingBeforeScratch = isPlayingInternal || _isPlaying.value
        }
        isScratching = true
        if (isPlayingInternal) {
            isPlayingInternal = false
            _isPlaying.value = false
            playbackJob?.cancel()
            audioScope.launch {
                synchronized(audioLock) {
                    try {
                        audioTrack?.flush()
                    } catch (e: Exception) {
                        // ignore
                    }
                }
            }
        }
    }

    /**
     * Real-time vinyl scratching with instantaneous hardware audio write.
     * Moving in reverse reads audio PCM in reverse, creating the authentic analog DJ scratch sound.
     */
    fun scratch(deltaDegrees: Float) {
        val voc = vocalsPcm ?: return
        val acc = accompPcm ?: return
        val drums = drumsPcm
        val bass = bassPcm
        val other = otherPcm
        val isFourStem = drums != null && bass != null && other != null
        if (totalFrames == 0) return

        val deltaFrames = (deltaDegrees * 220f).toInt()
        if (deltaFrames == 0) return

        // Prevent scratch from pushing playhead past the loop bounds (or track end threshold)
        val loopStart = _loopStartMs.value
        val loopEnd = _loopEndMs.value
        val isLoopActive = _isLooping.value && loopStart != null && loopEnd != null && loopEnd > loopStart

        val minSafeFrame = if (isLoopActive) ((loopStart / 1000.0) * sampleRate).toInt().coerceIn(0, totalFrames) else 0
        val maxSafeFrame = if (isLoopActive) ((loopEnd / 1000.0) * sampleRate).toInt().coerceIn(0, totalFrames) else maxOf(0, totalFrames - 2048)
        val targetFrame = (currentFrame + deltaFrames).coerceIn(minSafeFrame, maxSafeFrame)
        val framesToRead = kotlin.math.abs(targetFrame - currentFrame)
        if (framesToRead < 4) return

        val anySolo = _isVocalsSolo.value || _isDrumsSolo.value || _isBassSolo.value || _isOtherSolo.value
        val vVol = if (anySolo) (if (_isVocalsSolo.value) _vocalsVolume.value else 0f) else (if (_isVocalsMuted.value) 0f else _vocalsVolume.value)
        val dVol = if (anySolo) (if (_isDrumsSolo.value) _drumsVolume.value else 0f) else (if (_isDrumsMuted.value) 0f else _drumsVolume.value)
        val bVol = if (anySolo) (if (_isBassSolo.value) _bassVolume.value else 0f) else (if (_isBassMuted.value) 0f else _bassVolume.value)
        val oVol = if (anySolo) (if (_isOtherSolo.value) _otherVolume.value else 0f) else (if (_isOtherMuted.value) 0f else _otherVolume.value)
        val aVol = if (anySolo) 0f else (if (_isAccompMuted.value) 0f else _accompVolume.value)

        val bufferCapacityFrames = scratchWriteBuffer.size / frameSizeShorts
        val actualFrames = minOf(framesToRead, bufferCapacityFrames)

        val isForward = deltaFrames > 0
        val step = if (isForward) (framesToRead.toFloat() / actualFrames) else -(framesToRead.toFloat() / actualFrames)

        val speed = (framesToRead.toFloat() / actualFrames).coerceIn(0.2f, 3.5f)
        val scratchGain = (speed * 0.95f).coerceIn(0.5f, 1.25f)

        val maxPcmIndex = minOf(voc.size, acc.size) - 2
        if (maxPcmIndex < 0) return

        for (i in 0 until actualFrames) {
            val frameOffset = (currentFrame + (i * step).toInt()).coerceIn(0, totalFrames - 1)
            val pcmIdx = (frameOffset * frameSizeShorts).coerceIn(0, maxPcmIndex)

            val mixedL = if (drums != null && bass != null && other != null) {
                voc[pcmIdx] * vVol + drums[pcmIdx] * dVol + bass[pcmIdx] * bVol + other[pcmIdx] * oVol
            } else {
                voc[pcmIdx] * vVol + acc[pcmIdx] * aVol
            }

            val mixedR = if (drums != null && bass != null && other != null) {
                voc[pcmIdx + 1] * vVol + drums[pcmIdx + 1] * dVol + bass[pcmIdx + 1] * bVol + other[pcmIdx + 1] * oVol
            } else {
                voc[pcmIdx + 1] * vVol + acc[pcmIdx + 1] * aVol
            }

            scratchWriteBuffer[i * 2] = (mixedL * scratchGain).toInt().coerceIn(-32768, 32767).toShort()
            scratchWriteBuffer[i * 2 + 1] = (mixedR * scratchGain).toInt().coerceIn(-32768, 32767).toShort()
        }

        synchronized(audioLock) {
            try {
                audioTrack?.let { track ->
                    if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        track.play()
                    }
                    track.write(scratchWriteBuffer, 0, actualFrames * frameSizeShorts)
                }
            } catch (e: Exception) {
                // ignore
            }
        }

        currentFrame = targetFrame
        _positionMs.value = (currentFrame.toDouble() / sampleRate * 1000).toLong()
    }

    fun endScratch() {
        isScratching = false
        if (wasPlayingBeforeScratch) {
            play()
        }
    }

    fun setVocalsVolume(volume: Float) {
        _vocalsVolume.value = volume.coerceIn(0f, 1f)
    }

    fun setDrumsVolume(volume: Float) {
        _drumsVolume.value = volume.coerceIn(0f, 1f)
    }

    fun setBassVolume(volume: Float) {
        _bassVolume.value = volume.coerceIn(0f, 1f)
    }

    fun setOtherVolume(volume: Float) {
        _otherVolume.value = volume.coerceIn(0f, 1f)
    }

    fun setAccompanimentVolume(volume: Float) {
        _accompVolume.value = volume.coerceIn(0f, 1f)
    }

    fun toggleVocalsMute() {
        _isVocalsMuted.value = !_isVocalsMuted.value
    }

    fun toggleDrumsMute() {
        _isDrumsMuted.value = !_isDrumsMuted.value
    }

    fun toggleBassMute() {
        _isBassMuted.value = !_isBassMuted.value
    }

    fun toggleOtherMute() {
        _isOtherMuted.value = !_isOtherMuted.value
    }

    fun toggleAccompanimentMute() {
        _isAccompMuted.value = !_isAccompMuted.value
    }

    fun toggleVocalsSolo() {
        _isVocalsSolo.value = !_isVocalsSolo.value
    }

    fun toggleDrumsSolo() {
        _isDrumsSolo.value = !_isDrumsSolo.value
    }

    fun toggleBassSolo() {
        _isBassSolo.value = !_isBassSolo.value
    }

    fun toggleOtherSolo() {
        _isOtherSolo.value = !_isOtherSolo.value
    }

    fun setPitchSemitones(semitones: Int) {
        val clamped = semitones.coerceIn(-6, 6)
        _pitchSemitones.value = clamped
        applyPitchParams()
    }

    private fun applyPitchParams() {
        val semitones = _pitchSemitones.value
        val pitchFactor = 2.0.pow(semitones / 12.0).toFloat()
        audioScope.launch {
            synchronized(audioLock) {
                try {
                    audioTrack?.let {
                        val params = PlaybackParams()
                            .setPitch(pitchFactor)
                            .setSpeed(1.0f)
                        it.playbackParams = params
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Failed to apply pitch params: ${e.message}")
                }
            }
        }
    }

    fun setLoopPointA(ms: Long = _positionMs.value) {
        _loopStartMs.value = ms
        val end = _loopEndMs.value
        if (end != null && end <= ms) {
            _loopEndMs.value = null
        }
        checkAutoEnableLoop()
    }

    fun setLoopPointB(ms: Long = _positionMs.value) {
        val start = _loopStartMs.value
        if (start != null && ms > start) {
            _loopEndMs.value = ms
            checkAutoEnableLoop()
        }
    }

    fun clearLoop() {
        _loopStartMs.value = null
        _loopEndMs.value = null
        _isLooping.value = false
    }

    fun toggleLoop() {
        _isLooping.value = !_isLooping.value
    }

    private fun checkAutoEnableLoop() {
        if (_loopStartMs.value != null && _loopEndMs.value != null) {
            _isLooping.value = true
        }
    }

    private fun readWavShorts(file: File): ShortArray {
        val bytes = file.readBytes()
        if (bytes.size <= 44) return ShortArray(0)
        val shortCount = (bytes.size - 44) / 2
        val shorts = ShortArray(shortCount)
        ByteBuffer.wrap(bytes, 44, bytes.size - 44)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(shorts)
        return shorts
    }

    private fun releaseAudioTrack() {
        synchronized(audioLock) {
            try {
                audioTrack?.stop()
                audioTrack?.release()
            } catch (e: Exception) {
                // ignore
            }
            audioTrack = null
        }
    }

    fun release() {
        stop()
        chordJob?.cancel()
        _chordSegments.value = emptyList()
        releaseAudioTrack()
        vocalsPcm = null
        drumsPcm = null
        bassPcm = null
        otherPcm = null
        accompPcm = null
    }
}
