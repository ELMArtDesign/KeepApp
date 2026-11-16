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
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import it.daprile.keepapp.core.audio.AudioClockEstimator
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
    val isSpeakerCheck: Boolean = false,
    val isBodyTapCheck: Boolean = false,
    val progressStep: Int = 0,
    val totalSteps: Int = 0,
    val statusMessage: String = "Pronto per la calibrazione",
    val noiseFloorRms: Float = 0f,
    val liveSamplesMs: List<Float> = emptyList(),
    val summary: CalibrationSummary? = null,
    val errorMessage: String? = null,
    val rMicMedianMs: Float? = null,
    val rMicMadMs: Float? = null,
    val imuFifoDelayMs: Float? = null,
    val speakerXRawMs: Float? = null,
    val speakerXMadMs: Float? = null,
    val btLoopbackMs: Float? = null,
    val btLoopbackMadMs: Float? = null,
    val recHwTimestampActive: Boolean = false
)

/**
 * Calibratore della latenza Bluetooth (Milestone 3 / D35):
 * - Metodo 1: "A contatto" (`AudioTrack` emette 5 impulsi @ 48 kHz + `AudioRecord` li capta dal microfono
 *   usando entrambi i clock in `TIMEBASE_BOOTTIME`).
 * - Metodo 2: "Batti a tempo" (`AudioTrack` emette 16 battiti @ 120 BPM e l'utente tocca lo schermo
 *   in sincronia con ciò che sente in cuffia).
 */
class BluetoothLatencyCalibrator(
    private val context: Context? = null,
    private val sampleRateHz: Int = DrumSynthWavetable.SAMPLE_RATE_HZ
) {
    companion object {
        private const val CHUNK_FRAMES = 480 // 10 ms
        private const val CONTACT_IMPULSE_COUNT = 8 // D43: almeno 8 impulsi con mediana e MAD
        private const val CONTACT_PERIOD_MS = 1100
        private const val BODY_TAP_COUNT = 10 // Test del colpo per misurare R_mic
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
        _state.value = _state.value.copy(
            isRunning = true,
            method = CalibrationMethod.CONTACT_IMPULSE,
            isSpeakerCheck = false,
            isBodyTapCheck = false,
            progressStep = 0,
            totalSteps = CONTACT_IMPULSE_COUNT,
            liveSamplesMs = emptyList(),
            summary = null,
            errorMessage = null,
            statusMessage = "Appoggia le cuffie al microfono: misuro il rumore di fondo..."
        )
        workerThread = Thread({ runContactCalibration(targetSpeaker = false) }, "KeepApp-BtCalib-Contact").also { it.start() }
    }

    /**
     * Esegue la stessa misura di loopback (D35) sull'altoparlante interno del telefono (`TYPE_BUILTIN_SPEAKER`)
     * per misurare l'eventuale ritardo hardware X dell'altoparlante stesso.
     */
    @Synchronized
    fun startSpeakerLoopbackCheck() {
        if (running) return
        running = true
        _state.value = _state.value.copy(
            isRunning = true,
            method = CalibrationMethod.CONTACT_IMPULSE,
            isSpeakerCheck = true,
            isBodyTapCheck = false,
            progressStep = 0,
            totalSteps = CONTACT_IMPULSE_COUNT,
            liveSamplesMs = emptyList(),
            summary = null,
            errorMessage = null,
            statusMessage = "Verifica altoparlante interno (X): stanza silenziosa, misuro il rumore di fondo..."
        )
        workerThread = Thread({ runContactCalibration(targetSpeaker = true) }, "KeepApp-SpkCheck-Contact").also { it.start() }
    }

    /**
     * Test del colpo: Adriano batte 10 volte un dito sulla scocca del telefono sul tavolo
     * per misurare R_mic (ritardo microfono rispetto a SensorEvent.timestamp) e il ritardo FIFO D36.
     */
    @Synchronized
    fun startBodyTapMicDelayTest() {
        if (running) return
        running = true
        _state.value = _state.value.copy(
            isRunning = true,
            method = CalibrationMethod.CONTACT_IMPULSE,
            isSpeakerCheck = false,
            isBodyTapCheck = true,
            progressStep = 0,
            totalSteps = BODY_TAP_COUNT,
            liveSamplesMs = emptyList(),
            summary = null,
            errorMessage = null,
            statusMessage = "Appoggia il telefono sul tavolo: batti un dito sulla scocca 10 volte (~1/s)..."
        )
        workerThread = Thread({ runBodyTapMicDelayTest() }, "KeepApp-BodyTap-RMic").also { it.start() }
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
        _state.value = _state.value.copy(
            isRunning = true,
            method = CalibrationMethod.TAP_TO_BEAT,
            isSpeakerCheck = false,
            isBodyTapCheck = false,
            progressStep = 0,
            totalSteps = TAP_BEAT_COUNT,
            liveSamplesMs = emptyList(),
            summary = null,
            errorMessage = null,
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
    private fun runContactCalibration(targetSpeaker: Boolean = false) {
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

        val track = createOutputTrack(targetSpeaker)
        if (targetSpeaker && context != null) {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val spk = am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                ?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (spk != null) {
                track.preferredDevice = spk
            }
        }
        val trackEstimator = AudioClockEstimator(sampleRateHz)
        val recEstimator = AudioClockEstimator(sampleRateHz)
        val trackTs = AudioTimestamp()
        val recTs = AudioTimestamp()

        // Buffer cattura continua per 1 impulso: 650 ms a 48 kHz = 31.200 campioni
        val captureWindowSamples = (sampleRateHz * 0.65).toInt()
        val captureRing = ShortArray(sampleRateHz * 2) // 2 secondi circolari
        var totalRecSamples = 0L
        var recHwTsCount = 0

        val recThread = Thread({
            val tmp = ShortArray(CHUNK_FRAMES)
            try {
                recorder.startRecording()
                while (running) {
                    val read = recorder.read(tmp, 0, tmp.size, AudioRecord.READ_BLOCKING)
                    if (read > 0) {
                        val gotRecTs = recorder.getTimestamp(recTs, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS
                        if (gotRecTs && recTs.nanoTime > 0L) {
                            recHwTsCount++
                            recEstimator.updateFromTimestamp(recTs.framePosition, recTs.nanoTime)
                        } else {
                            recEstimator.updateFromTimestamp(totalRecSamples + read, SystemClock.elapsedRealtimeNanos())
                        }
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
                        AudioClockTimestampNormalizer.normalizeTrackTimestampToBoottimeNs(trackTs.nanoTime)
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
                            AudioClockTimestampNormalizer.normalizeTrackTimestampToBoottimeNs(trackTs.nanoTime)
                        )
                    }

                    // Dopo 850 ms dall'emissione dell'impulso analizziamo la finestra catturata dal microfono
                    if (!analyzedThisImpulse && c == 85) {
                        analyzedThisImpulse = true
                        val trackOutNs = trackEstimator.outputTimeNsForSample(impulseStartSample)
                        if (trackOutNs != null) {
                            val recStartSample = recEstimator.sampleIndexForOutputTimeNs(trackOutNs)?.coerceAtLeast(0L)
                                ?: run {
                                    val nowBootNs = SystemClock.elapsedRealtimeNanos()
                                    val elapsedSinceOutSamples = (((nowBootNs - trackOutNs).toDouble() / 1_000_000_000.0) * sampleRateHz).toLong()
                                    max(0L, totalRecSamples - elapsedSinceOutSamples)
                                }
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
            val hwTsActive = recHwTsCount > 0
            _state.value = _state.value.copy(
                isRunning = false,
                summary = summary,
                recHwTimestampActive = hwTsActive,
                speakerXRawMs = if (targetSpeaker && summary != null) summary.medianLatencyMs else _state.value.speakerXRawMs,
                speakerXMadMs = if (targetSpeaker && summary != null) summary.madMs else _state.value.speakerXMadMs,
                btLoopbackMs = if (!targetSpeaker && summary != null) summary.medianLatencyMs else _state.value.btLoopbackMs,
                btLoopbackMadMs = if (!targetSpeaker && summary != null) summary.madMs else _state.value.btLoopbackMadMs,
                statusMessage = if (summary != null) {
                    val tsTag = if (hwTsActive) "getTimestamp OK" else "fallback read()"
                    "Completata ($tsTag): med ${"%.1f".format(summary.medianLatencyMs)} ms (MAD ±${"%.1f".format(summary.madMs)} ms)"
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

    @SuppressLint("MissingPermission")
    private fun runBodyTapMicDelayTest() {
        val sm = context?.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val accel = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sm == null || accel == null) {
            running = false
            _state.value = _state.value.copy(
                isRunning = false,
                errorMessage = "Accelerometro non disponibile su questo dispositivo."
            )
            return
        }

        val minRecBytes = AudioRecord.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val recBufBytes = max(minRecBytes * 2, sampleRateHz * 2)
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

        val recEstimator = AudioClockEstimator(sampleRateHz)
        val recTs = AudioTimestamp()
        val captureRing = ShortArray(sampleRateHz * 3)
        var totalRecSamples = 0L
        var recHwTsCount = 0

        val recThread = Thread({
            val tmp = ShortArray(CHUNK_FRAMES)
            try {
                recorder.startRecording()
                while (running) {
                    val read = recorder.read(tmp, 0, tmp.size, AudioRecord.READ_BLOCKING)
                    if (read > 0) {
                        val gotRecTs = recorder.getTimestamp(recTs, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS
                        if (gotRecTs && recTs.nanoTime > 0L) {
                            recHwTsCount++
                            recEstimator.updateFromTimestamp(recTs.framePosition, recTs.nanoTime)
                        } else {
                            recEstimator.updateFromTimestamp(totalRecSamples + read, SystemClock.elapsedRealtimeNanos())
                        }
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
        }, "KeepApp-BodyTap-Mic")

        val tapLockObj = Any()
        val pendingTapAccelNs = ArrayDeque<Long>()
        val fifoDelaysMs = ArrayList<Float>(16)
        var gravityEma = 9.81f
        var lastTapNs = 0L
        var warmupDoneNs = 0L

        val accelListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (!running) return
                val mag = kotlin.math.sqrt(
                    event.values[0] * event.values[0] +
                        event.values[1] * event.values[1] +
                        event.values[2] * event.values[2]
                )
                if (warmupDoneNs == 0L || event.timestamp < warmupDoneNs) {
                    gravityEma = 0.9f * gravityEma + 0.1f * mag
                    return
                }
                val dyn = kotlin.math.abs(mag - gravityEma)
                gravityEma = 0.98f * gravityEma + 0.02f * mag
                if (dyn >= 2.2f && (lastTapNs == 0L || event.timestamp - lastTapNs >= 650_000_000L)) {
                    lastTapNs = event.timestamp
                    val fifoLagMs = ((SystemClock.elapsedRealtimeNanos() - event.timestamp) / 1_000_000f).coerceAtLeast(0f)
                    synchronized(tapLockObj) {
                        pendingTapAccelNs.addLast(event.timestamp)
                        fifoDelaysMs.add(fifoLagMs)
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        try {
            recThread.start()
            sm.registerListener(accelListener, accel, SensorManager.SENSOR_DELAY_FASTEST, 0)

            Thread.sleep(750L)
            warmupDoneNs = SystemClock.elapsedRealtimeNanos()

            val floorLen = minOf(19_200, totalRecSamples.toInt())
            val floorBuf = ShortArray(floorLen)
            val floorStart = max(0L, totalRecSamples - floorLen)
            for (i in 0 until floorLen) {
                floorBuf[i] = captureRing[((floorStart + i) % captureRing.size).toInt()]
            }
            val noiseFloor = BluetoothCalibrationMath.computeRms(floorBuf)
            _state.value = _state.value.copy(
                noiseFloorRms = noiseFloor,
                statusMessage = "Pronto! Batti un dito sulla scocca 10 volte (~1/s)..."
            )

            val rMicSamples = ArrayList<Float>(BODY_TAP_COUNT)
            val preWindowSamples = (sampleRateHz * 0.04).toInt() // -40 ms
            val totalWinSamples = (sampleRateHz * 0.26).toInt()  // [-40 ms, +220 ms]
            val analyzeBuf = ShortArray(totalWinSamples)
            var detectedTaps = 0
            val deadlineNs = SystemClock.elapsedRealtimeNanos() + 25_000_000_000L

            while (running && detectedTaps < BODY_TAP_COUNT && SystemClock.elapsedRealtimeNanos() < deadlineNs) {
                val nextTapNs: Long? = synchronized(tapLockObj) {
                    val first = pendingTapAccelNs.firstOrNull()
                    if (first != null && SystemClock.elapsedRealtimeNanos() - first >= 240_000_000L) {
                        pendingTapAccelNs.removeFirst()
                    } else null
                }
                if (nextTapNs != null) {
                    detectedTaps++
                    val winStartNs = nextTapNs - 40_000_000L
                    val recStartSample = recEstimator.sampleIndexForOutputTimeNs(winStartNs)?.coerceAtLeast(0L)
                        ?: run {
                            val nowNs = SystemClock.elapsedRealtimeNanos()
                            val elapsedSamples = (((nowNs - winStartNs).toDouble() / 1_000_000_000.0) * sampleRateHz).toLong()
                            max(0L, totalRecSamples - elapsedSamples)
                        }
                    val avail = (totalRecSamples - recStartSample).toInt().coerceIn(0, totalWinSamples)
                    if (avail > preWindowSamples + 200) {
                        for (i in 0 until avail) {
                            analyzeBuf[i] = captureRing[((recStartSample + i) % captureRing.size).toInt()]
                        }
                        val onsetIdx = BluetoothCalibrationMath.detectImpulseOnsetSample(
                            pcmWindow = analyzeBuf,
                            startIdx = 0,
                            endIdxExclusive = avail,
                            noiseFloorRms = noiseFloor,
                            snrThreshold = 2.5f,
                            minPeakAmplitude = 180f
                        )
                        if (onsetIdx != null) {
                            val tMicNs = recEstimator.outputTimeNsForSample(recStartSample + onsetIdx)
                                ?: (winStartNs + ((onsetIdx.toDouble() / sampleRateHz.toDouble()) * 1_000_000_000.0).toLong())
                            val rMicMs = ((tMicNs - nextTapNs).toDouble() / 1_000_000.0).toFloat()
                            if (rMicMs in -30f..250f) {
                                rMicSamples.add(rMicMs)
                            }
                        }
                    }
                    _state.value = _state.value.copy(
                        progressStep = detectedTaps,
                        liveSamplesMs = rMicSamples.toList(),
                        statusMessage = "Colpo $detectedTaps/$BODY_TAP_COUNT (R_mic validi: ${rMicSamples.size})"
                    )
                } else {
                    Thread.sleep(25L)
                }
            }

            val summary = BluetoothCalibrationMath.summarizeLatencies(
                latenciesMs = rMicSamples,
                totalAttempts = BODY_TAP_COUNT,
                method = CalibrationMethod.CONTACT_IMPULSE,
                minPlausibleMs = -30f
            )
            val fifoMed = if (fifoDelaysMs.isNotEmpty()) {
                val sorted = fifoDelaysMs.sorted()
                sorted[sorted.size / 2]
            } else 0f

            running = false
            val hwTsActive = recHwTsCount > 0
            _state.value = _state.value.copy(
                isRunning = false,
                summary = summary,
                recHwTimestampActive = hwTsActive,
                rMicMedianMs = summary?.medianLatencyMs ?: _state.value.rMicMedianMs,
                rMicMadMs = summary?.madMs ?: _state.value.rMicMadMs,
                imuFifoDelayMs = fifoMed,
                statusMessage = if (summary != null) {
                    "Test del colpo: R_mic=${"%.1f".format(summary.medianLatencyMs)} ms (MAD ±${"%.1f".format(summary.madMs)} ms) | FIFO IMU=${"%.1f".format(fifoMed)} ms"
                } else {
                    "Colpi insufficienti ($detectedTaps/$BODY_TAP_COUNT). Batti più deciso sulla scocca."
                }
            )
        } finally {
            running = false
            sm.unregisterListener(accelListener)
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
        }
    }

    private fun runTapToBeatCalibration() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (_: Throwable) {
        }

        val track = createOutputTrack(targetSpeaker = false)
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
                        AudioClockTimestampNormalizer.normalizeTrackTimestampToBoottimeNs(trackTs.nanoTime)
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
                            AudioClockTimestampNormalizer.normalizeTrackTimestampToBoottimeNs(trackTs.nanoTime)
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
                        AudioClockTimestampNormalizer.normalizeTrackTimestampToBoottimeNs(trackTs.nanoTime)
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

    private fun createOutputTrack(targetSpeaker: Boolean = false): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val attrs = if (targetSpeaker) {
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        } else {
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        }
        return AudioTrack.Builder()
            .setAudioAttributes(attrs)
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
