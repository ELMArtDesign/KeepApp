/*
 * Copyright 2026 Adriano D'Aprile
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package it.daprile.keepapp.platform.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import it.daprile.keepapp.core.audio.AudioClockEstimator
import it.daprile.keepapp.core.audio.AudioMode
import it.daprile.keepapp.core.audio.BeatScheduler
import it.daprile.keepapp.core.config.KeepAppConfig
import it.daprile.keepapp.core.rhythm.RhythmPattern
import it.daprile.keepapp.core.rhythm.RhythmPatternEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Telemetria live dell'AudioEngine per diagnostica M2 e calibrazione M3.
 */
data class AudioEngineTelemetry(
    val isRunning: Boolean = false,
    val mode: AudioMode = AudioMode.RHYTHM_PATTERN,
    val bpm: Float = 150f,
    val activePatternId: String = "",
    val activePatternName: String = "",
    val activeLevelIndex: Int = 1,
    val pendingLevelIndex: Int? = null,
    val isMuted: Boolean = false,
    val isAttenuated: Boolean = false,
    val gainDb: Float = 0f,
    val underrunCount: Int = 0,
    val pllLocked: Boolean = false,
    val pllSampleCount: Int = 0,
    val pllResidualRmsMs: Float = 0f,
    val rawHalJitterMs: Float = 0f,
    val pllSlopePpm: Float = 0f,
    val lastPhaseCorrectionUs: Int = 0
)

/**
 * Motore audio real-time a singolo stream `AudioTrack` (48.000 Hz, `WRITE_BLOCKING` — D36 / D39 / D41).
 *
 * Caratteristiche chiave:
 * - Un unico `AudioTrack` aperto per tutta la durata della sessione (nessun `SoundPool`, nessun `Media3`).
 * - Thread dedicato `Process.THREAD_PRIORITY_URGENT_AUDIO`.
 * - Buffer di mixing (`FloatArray`), buffer PCM (`ShortArray`), pool di voci (`VoiceSlot`) e `AudioTimestamp`
 *   preallocati all'avvio: nessuna allocazione di buffer audio dentro il loop di scrittura.
 * - Rampe sample-by-sample per volume, attenuazione (-20 dB) e mute (zero click/pop).
 */
class KeepAppAudioEngine(
    initialPattern: RhythmPattern,
    private val config: KeepAppConfig = KeepAppConfig(),
    val sampleRateHz: Int = DrumSynthWavetable.SAMPLE_RATE_HZ
) {
    companion object {
        private const val CHUNK_FRAMES = 480 // 10 ms a 48.000 Hz
        private const val MAX_VOICES = 16
        private const val GAIN_RAMP_MS = 25f
        private const val ATTENUATED_GAIN = 0.1f // -20 dB
    }

    private class VoiceSlot {
        var active: Boolean = false
        var wavetable: FloatArray? = null
        var readPos: Int = 0
        var delaySamples: Int = 0
        var gain: Float = 1f
    }

    val clockEstimator = AudioClockEstimator(sampleRateHz)
    val patternEngine = RhythmPatternEngine(initialPattern, initialLevelIndex = 1)
    val scheduler = BeatScheduler(sampleRateHz, config, patternEngine)

    private val wavetables = DrumSynthWavetable.buildDefaultBank(sampleRateHz)
    private val markerBeepWavetable = buildMarkerBeepWavetable(sampleRateHz)

    // Preallocazione fissa per il thread audio
    private val mixBuffer = FloatArray(CHUNK_FRAMES)
    private val pcmBuffer = ShortArray(CHUNK_FRAMES)
    private val voices = Array(MAX_VOICES) { VoiceSlot() }
    private val audioTimestamp = AudioTimestamp()

    private val pendingMarkerBeeps = AtomicInteger(0)
    private var markerBeepReadPos: Int = -1
    private var markerBeepGapSamples: Int = 0

    private val _telemetry = MutableStateFlow(
        AudioEngineTelemetry(
            bpm = scheduler.targetBpm,
            activePatternId = initialPattern.id,
            activePatternName = initialPattern.name,
            activeLevelIndex = patternEngine.activeLevelIndex
        )
    )
    val telemetry: StateFlow<AudioEngineTelemetry> = _telemetry.asStateFlow()

    @Volatile
    private var running = false
    private var audioThread: Thread? = null

    @Volatile
    private var userVolume: Float = 1.0f

    @Volatile
    private var muted: Boolean = false

    @Volatile
    private var attenuated: Boolean = false

    @Volatile
    private var lastPhaseCorrectionUs: Int = 0

    // Rampa di guadagno gestita esclusivamente sul thread audio
    private var currentGain: Float = 1.0f
    private val maxGainStepPerSample: Float = 1.0f / (sampleRateHz * (GAIN_RAMP_MS / 1000f))

    @Synchronized
    fun start() {
        if (running) return
        running = true
        clockEstimator.reset()
        scheduler.reset(startSampleIndex = 0L)
        for (slot in voices) {
            slot.active = false
            slot.wavetable = null
        }
        currentGain = computeTargetGain()

        val thread = Thread({ runAudioLoop() }, "KeepApp-AudioEngine")
        audioThread = thread
        thread.start()
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        val t = audioThread
        audioThread = null
        try {
            t?.join(1500)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        publishTelemetry(isRunningNow = false, underruns = 0)
    }

    fun setMode(mode: AudioMode) {
        scheduler.mode = mode
    }

    fun setBpm(bpm: Float) {
        scheduler.targetBpm = bpm.coerceIn(config.minCadenceSpm, config.maxCadenceSpm)
    }

    fun setPattern(pattern: RhythmPattern, levelIndex: Int = patternEngine.activeLevelIndex) {
        patternEngine.requestPattern(pattern, levelIndex)
    }

    fun setLevel(levelIndex: Int) {
        patternEngine.requestLevel(levelIndex)
    }

    fun setVolume(volume: Float) {
        userVolume = volume.coerceIn(0f, 1f)
    }

    fun setMuted(isMuted: Boolean) {
        muted = isMuted
    }

    fun setAttenuated(isAttenuated: Boolean) {
        attenuated = isAttenuated
    }

    /**
     * Accoda `count` (1..4) brevi beep di conferma marker T1 nello stesso flusso `AudioTrack`
     * senza allocazioni sul thread audio.
     */
    fun playMarkerConfirmationBeeps(count: Int) {
        pendingMarkerBeeps.set(count.coerceIn(1, 4))
    }

    fun updateSyncTargets(
        bpm: Float,
        predictedNextFootstrikeNs: Long,
        phaseConfidence: Float,
        btLatencyMs: Float = 120f,
        positionOffsetMs: Float = 0f
    ) {
        scheduler.updateTargets(
            bpm = bpm,
            predictedNextFootstrikeNs = predictedNextFootstrikeNs,
            phaseConfidence = phaseConfidence,
            btLatencyMs = btLatencyMs,
            positionOffsetMs = positionOffsetMs
        )
    }

    private fun computeTargetGain(): Float {
        if (muted) return 0f
        val att = if (attenuated) ATTENUATED_GAIN else 1f
        return (userVolume * att).coerceIn(0f, 1f)
    }

    private fun runAudioLoop() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (_: Throwable) {
            // Fallback silenzioso se la piattaforma rifiuta la priorità elevata
        }

        val minBufBytes = AudioTrack.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        // Buffer ~60 ms (minimo 4x chunk da 10 ms) per assorbire jitter Bluetooth senza underrun
        val targetBufBytes = max(minBufBytes, CHUNK_FRAMES * 2 * 6)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRateHz)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(targetBufBytes)
            .setSessionId(AudioManager.AUDIO_SESSION_ID_GENERATE)
            .build()

        var writtenSamples = 0L
        var chunksSinceTelemetry = 0

        try {
            track.play()

            while (running) {
                // 1. Interroga getTimestamp() per aggiornare il PLL campione<->tempo (D36)
                if (track.getTimestamp(audioTimestamp)) {
                    clockEstimator.updateFromTimestamp(
                        framePosition = audioTimestamp.framePosition,
                        nanoTimeNs = AudioClockTimestampNormalizer.normalizeTrackTimestampToBoottimeNs(audioTimestamp.nanoTime)
                    )
                }

                // 2. Pianifica eventuali colpi nella finestra [writtenSamples, writtenSamples + CHUNK_FRAMES)
                val windowEnd = writtenSamples + CHUNK_FRAMES
                val events = scheduler.pullEventsForWindow(
                    windowStartSample = writtenSamples,
                    windowEndSampleExclusive = windowEnd,
                    outputTimeNsForSample = { s -> clockEstimator.outputTimeNsForSample(s) }
                )
                for (i in events.indices) {
                    val ev = events[i]
                    val offsetInChunk = (ev.sampleIndex - writtenSamples).toInt()
                    if (offsetInChunk in 0 until CHUNK_FRAMES) {
                        triggerVoice(
                            wavetable = wavetables.forInstrument(ev.instrument),
                            delaySamples = offsetInChunk,
                            gain = ev.velocity
                        )
                    }
                    if (ev.isMainBeat) {
                        lastPhaseCorrectionUs = ev.phaseCorrectionUs
                    }
                }

                // 3. Azzera mixBuffer e somma tutte le voci attive
                mixBuffer.fill(0f)
                for (vIdx in 0 until MAX_VOICES) {
                    val slot = voices[vIdx]
                    if (!slot.active) continue
                    val wt = slot.wavetable ?: continue
                    val wtLen = wt.size

                    var outIdx = slot.delaySamples
                    var rPos = slot.readPos
                    val vGain = slot.gain
                    while (outIdx < CHUNK_FRAMES && rPos < wtLen) {
                        mixBuffer[outIdx] += wt[rPos] * vGain
                        outIdx++
                        rPos++
                    }

                    if (rPos >= wtLen) {
                        slot.active = false
                        slot.wavetable = null
                        slot.readPos = 0
                        slot.delaySamples = 0
                    } else {
                        slot.readPos = rPos
                        slot.delaySamples = 0
                    }
                }

                // 4. Rampa volume/mute liscia sample-by-sample + mix beep marker + soft clipping + PCM16
                if (markerBeepReadPos < 0 && markerBeepGapSamples <= 0 && pendingMarkerBeeps.get() > 0) {
                    pendingMarkerBeeps.decrementAndGet()
                    markerBeepReadPos = 0
                }
                val beepGain = (userVolume.coerceIn(0.3f, 1f)) * 0.30f
                val targetGain = computeTargetGain()
                for (i in 0 until CHUNK_FRAMES) {
                    val diff = targetGain - currentGain
                    if (abs(diff) <= maxGainStepPerSample) {
                        currentGain = targetGain
                    } else if (diff > 0f) {
                        currentGain += maxGainStepPerSample
                    } else {
                        currentGain -= maxGainStepPerSample
                    }

                    var beepSample = 0f
                    if (markerBeepReadPos >= 0) {
                        beepSample = markerBeepWavetable[markerBeepReadPos] * beepGain
                        markerBeepReadPos++
                        if (markerBeepReadPos >= markerBeepWavetable.size) {
                            markerBeepReadPos = -1
                            markerBeepGapSamples = (sampleRateHz * 0.090).toInt()
                        }
                    } else if (markerBeepGapSamples > 0) {
                        markerBeepGapSamples--
                        if (markerBeepGapSamples == 0 && pendingMarkerBeeps.get() > 0) {
                            pendingMarkerBeeps.decrementAndGet()
                            markerBeepReadPos = 0
                        }
                    }

                    val scaled = mixBuffer[i] * currentGain + beepSample
                    val limited = if (abs(scaled) > 0.85f) {
                        tanh(scaled.toDouble()).toFloat()
                    } else {
                        scaled
                    }
                    val pcm = (limited * 32767f).toInt().coerceIn(-32768, 32767)
                    pcmBuffer[i] = pcm.toShort()
                }

                // 5. Scrittura bloccante su AudioTrack
                val written = track.write(pcmBuffer, 0, CHUNK_FRAMES, AudioTrack.WRITE_BLOCKING)
                if (written > 0) {
                    writtenSamples += written
                } else if (written < 0) {
                    break
                }

                chunksSinceTelemetry++
                if (chunksSinceTelemetry >= 20) { // ~200 ms
                    chunksSinceTelemetry = 0
                    val underruns = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        track.underrunCount
                    } else {
                        0
                    }
                    publishTelemetry(isRunningNow = true, underruns = underruns)
                }
            }
        } finally {
            try {
                track.pause()
                track.flush()
                track.stop()
            } catch (_: IllegalStateException) {
            } finally {
                track.release()
            }
        }
    }

    private fun triggerVoice(wavetable: FloatArray, delaySamples: Int, gain: Float) {
        var freeSlot: VoiceSlot? = null
        var oldestSlot: VoiceSlot = voices[0]
        var maxPos = -1
        for (i in 0 until MAX_VOICES) {
            val slot = voices[i]
            if (!slot.active) {
                freeSlot = slot
                break
            }
            if (slot.readPos > maxPos) {
                maxPos = slot.readPos
                oldestSlot = slot
            }
        }
        val target = freeSlot ?: oldestSlot
        target.active = true
        target.wavetable = wavetable
        target.readPos = 0
        target.delaySamples = delaySamples
        target.gain = gain
    }

    private fun publishTelemetry(isRunningNow: Boolean, underruns: Int) {
        val g = max(currentGain, 1e-4f)
        val db = if (currentGain <= 1e-4f) -80f else (20f * log10(g))
        val pat = patternEngine.currentPattern
        _telemetry.value = AudioEngineTelemetry(
            isRunning = isRunningNow,
            mode = scheduler.mode,
            bpm = scheduler.targetBpm,
            activePatternId = pat.id,
            activePatternName = pat.name,
            activeLevelIndex = patternEngine.activeLevelIndex,
            pendingLevelIndex = patternEngine.pendingLevelIndex,
            isMuted = muted,
            isAttenuated = attenuated,
            gainDb = db,
            underrunCount = underruns,
            pllLocked = clockEstimator.isLocked,
            pllSampleCount = clockEstimator.sampleCount,
            pllResidualRmsMs = clockEstimator.residualErrorRmsMs,
            rawHalJitterMs = clockEstimator.rawHalJitterRmsMs,
            pllSlopePpm = clockEstimator.estimatedSlopePpm,
            lastPhaseCorrectionUs = lastPhaseCorrectionUs
        )
    }

    private fun buildMarkerBeepWavetable(sr: Int): FloatArray {
        val len = (sr * 0.055).toInt() // 55 ms tono breve a 1760 Hz
        val fadeSamples = (sr * 0.005).toInt().coerceAtLeast(1)
        return FloatArray(len) { i ->
            val env = when {
                i < fadeSamples -> 0.5f * (1f - cos(PI * i / fadeSamples).toFloat())
                i >= len - fadeSamples -> 0.5f * (1f - cos(PI * (len - 1 - i) / fadeSamples).toFloat())
                else -> 1f
            }
            sin(2.0 * PI * 1760.0 * i / sr).toFloat() * env
        }
    }
}
