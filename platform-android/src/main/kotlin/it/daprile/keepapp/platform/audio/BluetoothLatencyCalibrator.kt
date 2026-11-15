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

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import it.daprile.keepapp.core.audio.BluetoothCalibrationMath
import it.daprile.keepapp.core.audio.CalibrationMethod
import it.daprile.keepapp.core.audio.CalibrationSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Stato osservabile della procedura di calibrazione latenza Bluetooth (M3).
 */
data class CalibrationUiState(
    val isRunning: Boolean = false,
    val method: CalibrationMethod? = null,
    val progressStep: Int = 0,
    val totalSteps: Int = 0,
    val statusMessage: String = "Pronto per la calibrazione",
    val noiseFloorRms: Float = 0f,
    val liveSamplesMs: List<Float> = emptyList(),
    val summary: CalibrationSummary? = null,
    val errorMessage: String? = null
)

/**
 * Calibratore della latenza Bluetooth (Milestone 3 / D35):
 * - Metodo 1: "A contatto" (`AudioTrack` emette 5 impulsi @ 48 kHz + `AudioRecord` li capta dal microfono
 *   usando entrambi i clock in `TIMEBASE_BOOTTIME`).
 * - Metodo 2: "Batti a tempo" (`AudioTrack` emette 16 battiti @ 120 BPM e l'utente tocca lo schermo
 *   in sincronia con ciò che sente in cuffia).
 */
class BluetoothLatencyCalibrator(
    private val sampleRateHz: Int = DrumSynthWavetable.SAMPLE_RATE_HZ
) {
    companion object {
        private const val CHUNK_FRAMES = 480 // 10 ms
        private const val CONTACT_IMPULSE_COUNT = 5
        private const val CONTACT_PERIOD_MS = 1100
        private const val TAP_BEAT_COUNT = 16
        private const val TAP_PERIOD_MS = 500 // 120 BPM
    }

    private val _state = MutableStateFlow(CalibrationUiState())
    val state: StateFlow<CalibrationUiState> = _state.asStateFlow()

    @Volatile
    private var running = false
    private var workerThread: Thread? = null

    private val tapTimestampsNs = ArrayList<Long>(32)
    private val tapLock = Any()

    /**
     * Avvia la calibrazione automatica "A contatto" (richiede permesso `RECORD_AUDIO` già concesso).
     */
    @Synchronized
    fun startContactImpulseCalibration() {
        if (running) return
        running = true
        _state.value = CalibrationUiState(
            isRunning = true,
            method = CalibrationMethod.CONTACT_IMPULSE,
            progressStep = 0,
            totalSteps = CONTACT_IMPULSE_COUNT,
            statusMessage = "Appoggia le cuffie al microfono: misuro il rumore di fondo..."
        )
        workerThread = Thread({ runContactCalibration() }, "KeepApp-BtCalib-Contact").also { it.start() }
    }

    /**
     * Avvia la calibrazione interattiva "Batti a tempo" (16 battiti a 120 BPM).
     */
    @Synchronized
    fun startTapToBeatCalibration() {
        if (running) return
        running = true
        synchronized(tapLock) {
            tapTimestampsNs.clear()
        }
        _state.value = CalibrationUiState(
            isRunning = true,
            method = CalibrationMethod.TAP_TO_BEAT,
            progressStep = 0,
            totalSteps = TAP_BEAT_COUNT,
            statusMessage = "Ascolta i primi 2 colpi, poi batti il dito a tempo sul pulsante!"
        )
        workerThread = Thread({ runTapToBeatCalibration() }, "KeepApp-BtCalib-Tap").also { it.start() }
    }

    /**
     * Registra un tocco dell'utente durante la calibrazione "Batti a tempo".
     */
    fun registerUserTap() {
        if (!running || _state.value.method != CalibrationMethod.TAP_TO_BEAT) return
        val nowNs = SystemClock.elapsedRealtimeNanos()
        synchronized(tapLock) {
            tapTimestampsNs.add(nowNs)
        }
    }

    @Synchronized
    fun cancel() {
        running = false
        val t = workerThread
        workerThread = null
        try {
            t?.join(1500)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        val cur = _state.value
        if (cur.isRunning) {
            _state.value = cur.copy(
                isRunning = false,
                statusMessage = "Calibrazione annullata"
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun runContactCalibration() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (_: Throwable) {
        }

        val minRecBytes = AudioRecord.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val recBufBytes = max(minRecBytes * 2, sampleRateHz * 2) // >= 1s di buffer circolare

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.UNPROCESSED,
                sampleRateHz,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                recBufBytes
            ).takeIf { it.state == AudioRecord.STATE_INITIALIZED } ?: AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRateHz,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                recBufBytes
            )
        } catch (_: Throwable) {
            null
        }

        if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder?.release()
            running = false
            _state.value = _state.value.copy(
                isRunning = false,
                errorMessage = "Impossibile aprire il microfono (permesso RECORD_AUDIO mancante?)"
            )
            return
        }

        val track = createOutputTrack()
        val trackEstimator = AudioClockEstimator(sampleRateHz)
        val recEstimator = AudioClockEstimator(sampleRateHz)
        val trackTs = AudioTimestamp()
        val recTs = AudioTimestamp()

        // Buffer cattura continua per 1 impulso: 650 ms a 48 kHz = 31.200 campioni
        val captureWindowSamples = (sampleRateHz * 0.65).toInt()
        val captureRing = ShortArray(sampleRateHz * 2) // 2 secondi circolari
        var totalRecSamples = 0L

        val recThread = Thread({
            val tmp = ShortArray(CHUNK_FRAMES)
            try {
                recorder.startRecording()
                while (running) {
                    val read = recorder.read(tmp, 0, tmp.size, AudioRecord.READ_BLOCKING)
                    if (read > 0) {
                        // Usiamo direttamente (totalRecSamples + read, elapsedRealtimeNanos) così l'asse
                        // dell'indice campione di captureRing coincide esattamente con TIMEBASE_BOOTTIME.
                        recEstimator.updateFromTimestamp(totalRecSamples + read, SystemClock.elapsedRealtimeNanos())
                        for (i in 0 until read) {
                            captureRing[((totalRecSamples + i) % captureRing.size).toInt()] = tmp[i]
                        }
                        totalRecSamples += read
                    } else if (read < 0) {
                        break
                    }
                }
            } catch (_: Throwable) {
            }
        }, "KeepApp-BtCalib-Mic")

        try {
            recThread.start()
            track.play()

            val silence = ShortArray(CHUNK_FRAMES)
            val impulse = buildProbeImpulse(sampleRateHz, freqHz = 1100.0, durationMs = 30)
            val outChunk = ShortArray(CHUNK_FRAMES)

            // 1. Warmup silenzioso di 700 ms per agganciare PLL playback + record e misurare rumore di fondo
            var writtenSamples = 0L
            val warmupChunks = 70
            for (c in 0 until warmupChunks) {
                if (!running) break
                val w = track.write(silence, 0, CHUNK_FRAMES, AudioTrack.WRITE_BLOCKING)
                if (w > 0) writtenSamples += w
                if (track.getTimestamp(trackTs)) {
                    trackEstimator.updateFromTimestamp(
                        trackTs.framePosition,
                        AudioClockEstimator.normalizeTrackTimestampToBoottimeNs(trackTs.nanoTime)
                    )
                }
            }

            // Stima rumore di fondo dagli ultimi ~400 ms catturati
            val floorLen = minOf(19_200, totalRecSamples.toInt())
            val floorBuf = ShortArray(floorLen)
            val floorStart = max(0L, totalRecSamples - floorLen)
            for (i in 0 until floorLen) {
                floorBuf[i] = captureRing[((floorStart + i) % captureRing.size).toInt()]
            }
            val noiseFloor = BluetoothCalibrationMath.computeRms(floorBuf)
            _state.value = _state.value.copy(
                noiseFloorRms = noiseFloor,
                statusMessage = "Emetto $CONTACT_IMPULSE_COUNT impulsi: tieni ferme le cuffie vicino al microfono..."
            )

            val measuredMs = ArrayList<Float>(CONTACT_IMPULSE_COUNT)
            val periodSamples = (sampleRateHz * CONTACT_PERIOD_MS) / 1000
            val analyzeWindowBuf = ShortArray(captureWindowSamples)
            var maxObservedPeak = 0

            // 2. Emetti 5 impulsi e misura ogni eco
            for (k in 0 until CONTACT_IMPULSE_COUNT) {
                if (!running) break

                val impulseStartSample = writtenSamples
                var impRead = 0
                val chunksInPeriod = periodSamples / CHUNK_FRAMES
                var analyzedThisImpulse = false

                for (c in 0 until chunksInPeriod) {
                    if (!running) break
                    for (i in 0 until CHUNK_FRAMES) {
                        outChunk[i] = if (impRead < impulse.size) impulse[impRead++] else 0
                    }
                    val w = track.write(outChunk, 0, CHUNK_FRAMES, AudioTrack.WRITE_BLOCKING)
                    if (w > 0) writtenSamples += w
                    if (track.getTimestamp(trackTs)) {
                        trackEstimator.updateFromTimestamp(
                            trackTs.framePosition,
                            AudioClockEstimator.normalizeTrackTimestampToBoottimeNs(trackTs.nanoTime)
                        )
                    }

                    // Dopo 850 ms dall'emissione dell'impulso analizziamo la finestra catturata dal microfono
                    if (!analyzedThisImpulse && c == 85) {
                        analyzedThisImpulse = true
                        val trackOutNs = trackEstimator.outputTimeNsForSample(impulseStartSample)
                        if (trackOutNs != null) {
                            val nowBootNs = SystemClock.elapsedRealtimeNanos()
                            val elapsedSinceOutSamples = (((nowBootNs - trackOutNs).toDouble() / 1_000_000_000.0) * sampleRateHz).toLong()
                            val recStartSample = max(0L, totalRecSamples - elapsedSinceOutSamples)
                            val availableSamples = (totalRecSamples - recStartSample).toInt().coerceIn(0, captureWindowSamples)
                            if (availableSamples >= sampleRateHz / 4) {
                                for (i in 0 until availableSamples) {
                                    val sIdx = recStartSample + i
                                    val sampleVal = captureRing[(sIdx % captureRing.size).toInt()]
                                    analyzeWindowBuf[i] = sampleVal
                                    val absVal = kotlin.math.abs(sampleVal.toInt())
                                    if (absVal > maxObservedPeak) maxObservedPeak = absVal
                                }
                                val onsetOffset = BluetoothCalibrationMath.detectImpulseOnsetSample(
                                    pcmWindow = analyzeWindowBuf,
                                    startIdx = 0,
                                    endIdxExclusive = availableSamples,
                                    noiseFloorRms = noiseFloor
                                )
                                if (onsetOffset != null) {
                                    val latMs = ((onsetOffset.toDouble() / sampleRateHz.toDouble()) * 1000.0).toFloat()
                                    if (latMs in 0f..650f) {
                                        measuredMs.add(latMs)
                                    }
                                }
                            }
                        }
                        _state.value = _state.value.copy(
                            progressStep = k + 1,
                            liveSamplesMs = measuredMs.toList(),
                            statusMessage = "Impulso ${k + 1}/$CONTACT_IMPULSE_COUNT (${measuredMs.size} rilevati)"
                        )
                    }
                }
            }

            val summary = BluetoothCalibrationMath.summarizeLatencies(
                latenciesMs = measuredMs,
                totalAttempts = CONTACT_IMPULSE_COUNT,
                method = CalibrationMethod.CONTACT_IMPULSE
            )

            running = false
            _state.value = _state.value.copy(
                isRunning = false,
                summary = summary,
                statusMessage = if (summary != null) {
                    "Calibrazione completata: ${"%.1f".format(summary.medianLatencyMs)} ms (±${"%.1f".format(summary.stdDevMs)} ms)"
                } else {
                    "Nessun impulso rilevato (trackPLL=${trackEstimator.sampleCount}, recPLL=${recEstimator.sampleCount}, fondo=${noiseFloor.toInt()}, maxPicco=$maxObservedPeak). Avvicina la cuffia al microfono."
                }
            )
        } finally {
            running = false
            try {
                recThread.join(800)
            } catch (_: InterruptedException) {
            }
            try {
                recorder.stop()
            } catch (_: Throwable) {
            } finally {
                recorder.release()
            }
            releaseTrack(track)
        }
    }

    private fun runTapToBeatCalibration() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (_: Throwable) {
        }

        val track = createOutputTrack()
        val trackEstimator = AudioClockEstimator(sampleRateHz)
        val trackTs = AudioTimestamp()
        val beatClick = buildProbeImpulse(sampleRateHz, freqHz = 1000.0, durationMs = 14)

        val beatSamples = ArrayList<Long>(TAP_BEAT_COUNT)
        val outChunk = ShortArray(CHUNK_FRAMES)
        val periodSamples = (sampleRateHz * TAP_PERIOD_MS) / 1000
        val chunksPerBeat = periodSamples / CHUNK_FRAMES

        try {
            track.play()
            var writtenSamples = 0L

            // Breve warmup di 300 ms per agganciare il timestamp HAL
            val silence = ShortArray(CHUNK_FRAMES)
            for (c in 0 until 30) {
                if (!running) break
                val w = track.write(silence, 0, CHUNK_FRAMES, AudioTrack.WRITE_BLOCKING)
                if (w > 0) writtenSamples += w
                if (track.getTimestamp(trackTs)) {
                    trackEstimator.updateFromTimestamp(
                        trackTs.framePosition,
                        AudioClockEstimator.normalizeTrackTimestampToBoottimeNs(trackTs.nanoTime)
                    )
                }
            }

            for (beat in 0 until TAP_BEAT_COUNT) {
                if (!running) break
                beatSamples.add(writtenSamples)
                var clickPos = 0
                for (c in 0 until chunksPerBeat) {
                    if (!running) break
                    for (i in 0 until CHUNK_FRAMES) {
                        outChunk[i] = if (clickPos < beatClick.size) beatClick[clickPos++] else 0
                    }
                    val w = track.write(outChunk, 0, CHUNK_FRAMES, AudioTrack.WRITE_BLOCKING)
                    if (w > 0) writtenSamples += w
                    if (track.getTimestamp(trackTs)) {
                        trackEstimator.updateFromTimestamp(
                            trackTs.framePosition,
                            AudioClockEstimator.normalizeTrackTimestampToBoottimeNs(trackTs.nanoTime)
                        )
                    }
                }
                _state.value = _state.value.copy(
                    progressStep = beat + 1,
                    statusMessage = if (beat < 2) {
                        "Ascolta il ritmo... (${beat + 1}/2)"
                    } else {
                        "Batti a tempo! Battito ${beat + 1}/$TAP_BEAT_COUNT"
                    }
                )
            }

            // Attendi 300 ms finali per raccogliere l'ultimo tap
            for (c in 0 until 30) {
                if (!running) break
                track.write(silence, 0, CHUNK_FRAMES, AudioTrack.WRITE_BLOCKING)
                if (track.getTimestamp(trackTs)) {
                    trackEstimator.updateFromTimestamp(
                        trackTs.framePosition,
                        AudioClockEstimator.normalizeTrackTimestampToBoottimeNs(trackTs.nanoTime)
                    )
                }
            }

            val beatOutputTimesNs = beatSamples.mapNotNull { trackEstimator.outputTimeNsForSample(it) }
            val tapsCopy = synchronized(tapLock) { tapTimestampsNs.toList() }
            val summary = BluetoothCalibrationMath.computeTapToBeatLatency(
                beatOutputTimesNs = beatOutputTimesNs,
                tapTimesNs = tapsCopy,
                beatPeriodMs = TAP_PERIOD_MS.toFloat(),
                warmupBeatsToSkip = 2
            )

            running = false
            _state.value = _state.value.copy(
                isRunning = false,
                summary = summary,
                statusMessage = if (summary != null) {
                    "Calibrazione Tap completata: ${"%.1f".format(summary.medianLatencyMs)} ms (${summary.validSamples} tap validi)"
                } else {
                    "Tap insufficienti o fuori tempo (tap=${tapsCopy.size}, beat=${beatOutputTimesNs.size}, trackPLL=${trackEstimator.sampleCount}). Riprova."
                }
            )
        } finally {
            running = false
            releaseTrack(track)
        }
    }

    private fun createOutputTrack(): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        return AudioTrack.Builder()
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
            .setBufferSizeInBytes(max(minBuf, CHUNK_FRAMES * 2 * 6))
            .setSessionId(AudioManager.AUDIO_SESSION_ID_GENERATE)
            .build()
    }

    private fun releaseTrack(track: AudioTrack) {
        try {
            track.pause()
            track.flush()
            track.stop()
        } catch (_: IllegalStateException) {
        } finally {
            track.release()
        }
    }

    /**
     * Genera un impulso netto a 1.400 Hz con finestra Hann (10 ms) ad alta visibilità per microfono.
     */
    private fun buildProbeImpulse(
        sr: Int,
        freqHz: Double = 1400.0,
        durationMs: Int = 10
    ): ShortArray {
        val len = (sr * durationMs) / 1000
        return ShortArray(len) { i ->
            val t = i.toDouble() / sr
            val hann = 0.5 * (1.0 - cos(2.0 * PI * i / (len - 1)))
            val v = sin(2.0 * PI * freqHz * t) * hann * 0.90
            (v * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
        }
    }
}
