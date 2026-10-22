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

    private var audioTrack: AudioTrack? = null
    private var vocalsPcm: ShortArray? = null
    private var accompPcm: ShortArray? = null
    private var totalFrames: Int = 0

    private var playbackJob: Job? = null
    @Volatile private var isPlayingInternal = false
    @Volatile private var currentFrame = 0

    // State Flows
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _vocalsVolume = MutableStateFlow(1.0f)
    val vocalsVolume: StateFlow<Float> = _vocalsVolume.asStateFlow()

    private val _accompVolume = MutableStateFlow(1.0f)
    val accompVolume: StateFlow<Float> = _accompVolume.asStateFlow()

    private val _isVocalsMuted = MutableStateFlow(false)
    val isVocalsMuted: StateFlow<Boolean> = _isVocalsMuted.asStateFlow()

    private val _isAccompMuted = MutableStateFlow(false)
    val isAccompMuted: StateFlow<Boolean> = _isAccompMuted.asStateFlow()

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

    fun load(project: SongProject) {
        stop()
        releaseAudioTrack()

        val vocFile = project.vocalsFile
        val accFile = project.accompanimentFile

        vocalsPcm = readWavShorts(vocFile)
        accompPcm = readWavShorts(accFile)

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

        computeWaveform()

        initAudioTrack()
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
        val bufSize = maxOf(minBuf * 4, 65536)

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
            .build()

        applyPitchParams()
    }

    fun play() {
        if (totalFrames == 0 || isPlayingInternal) return
        isPlayingInternal = true
        _isPlaying.value = true

        audioTrack?.play()

        playbackJob = CoroutineScope(Dispatchers.Default).launch {
            val voc = vocalsPcm ?: return@launch
            val acc = accompPcm ?: return@launch
            val chunkSizeFrames = 2048
            val writeBuffer = ShortArray(chunkSizeFrames * frameSizeShorts)

            while (isActive && isPlayingInternal) {
                // Check A/B Loop
                val loopStart = _loopStartMs.value
                val loopEnd = _loopEndMs.value
                val isLoopActive = _isLooping.value && loopStart != null && loopEnd != null && loopEnd > loopStart

                val loopEndFrame = if (isLoopActive) ((loopEnd / 1000.0) * sampleRate).toInt() else totalFrames
                val loopStartFrame = if (isLoopActive) ((loopStart / 1000.0) * sampleRate).toInt().coerceIn(0, totalFrames) else 0

                if (currentFrame >= loopEndFrame || currentFrame >= totalFrames) {
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

                val vVol = if (_isVocalsMuted.value) 0f else _vocalsVolume.value
                val aVol = if (_isAccompMuted.value) 0f else _accompVolume.value

                var pcmIdx = currentFrame * frameSizeShorts
                for (i in 0 until shortsToWrite) {
                    val vs = voc[pcmIdx + i] * vVol
                    val asamp = acc[pcmIdx + i] * aVol
                    writeBuffer[i] = (vs + asamp).toInt().coerceIn(-32768, 32767).toShort()
                }

                audioTrack?.write(writeBuffer, 0, shortsToWrite)
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
        try {
            audioTrack?.pause()
            audioTrack?.flush()
        } catch (e: Exception) {
            e.printStackTrace()
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

    fun seekTo(targetMs: Long) {
        val clampedMs = targetMs.coerceIn(0L, _durationMs.value)
        val targetFrame = ((clampedMs / 1000.0) * sampleRate).toInt().coerceIn(0, totalFrames)
        currentFrame = targetFrame
        _positionMs.value = clampedMs

        if (isPlayingInternal) {
            try {
                audioTrack?.flush()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun setVocalsVolume(volume: Float) {
        _vocalsVolume.value = volume.coerceIn(0f, 1f)
    }

    fun setAccompanimentVolume(volume: Float) {
        _accompVolume.value = volume.coerceIn(0f, 1f)
    }

    fun toggleVocalsMute() {
        _isVocalsMuted.value = !_isVocalsMuted.value
    }

    fun toggleAccompanimentMute() {
        _isAccompMuted.value = !_isAccompMuted.value
    }

    fun setPitchSemitones(semitones: Int) {
        val clamped = semitones.coerceIn(-6, 6)
        _pitchSemitones.value = clamped
        applyPitchParams()
    }

    private fun applyPitchParams() {
        val semitones = _pitchSemitones.value
        val pitchFactor = 2.0.pow(semitones / 12.0).toFloat()
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
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            // ignore
        }
        audioTrack = null
    }

    fun release() {
        stop()
        releaseAudioTrack()
        vocalsPcm = null
        accompPcm = null
    }
}
