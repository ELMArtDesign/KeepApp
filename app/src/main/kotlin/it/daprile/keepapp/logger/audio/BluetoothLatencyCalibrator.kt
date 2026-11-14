/*
 * Copyright 2026 Adriano D'Aprile
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package it.daprile.keepapp.logger.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Risultato statistico di una sessione di calibrazione ("Batti a tempo" o "A contatto").
 */
data class CalibrationResult(
    val method: String,
    val headphonesName: String,
    val validSamples: Int,
    val totalSamples: Int,
    val meanOffsetMs: Float,
    val stdDevMs: Float,
    val minOffsetMs: Float,
    val maxOffsetMs: Float,
    val notes: String
)

/**
 * Stato del calibratore Bluetooth (F6 — Milestone 3).
 */
data class CalibratorUiState(
    val isTapTestRunning: Boolean = false,
    val tapCount: Int = 0,
    val tapTarget: Int = 16,
    val tapResult: CalibrationResult? = null,
    val isContactTestRunning: Boolean = false,
    val contactPulseIndex: Int = 0,
    val contactResult: CalibrationResult? = null,
    val savedProfiles: List<String> = emptyList()
)

/**
 * Calibratore della latenza Bluetooth con due metodologie a confronto (F6 / D11 / D29):
 * (a) "Batti a tempo": 16 tocchi sullo schermo a tempo con un metronomo a 120 BPM (periodo 500 ms).
 * (b) "A contatto": 5 impulsi emessi nella cuffia appoggiata vicino al microfono interno (BUILTIN_MIC).
 */
class BluetoothLatencyCalibrator(private val context: Context) {

    private val _uiState = MutableStateFlow(CalibratorUiState(savedProfiles = loadSavedProfilesSummary()))
    val uiState: StateFlow<CalibratorUiState> = _uiState.asStateFlow()

    private var tapMetronome: SampleAccurateMetronome? = null
    private val tapOffsetsMs = mutableListOf<Float>()
    private var currentHeadphonesName = "MANKIW-X15"

    // === (a) Modalità "Batti a tempo" (16 click a 120 BPM = 500 ms) ===

    fun startTapCalibration(headphonesName: String) {
        stopAll()
        currentHeadphonesName = headphonesName
        tapOffsetsMs.clear()

        val metro = SampleAccurateMetronome()
        tapMetronome = metro
        metro.start(initialBpm = TAP_TEST_BPM)

        _uiState.value = _uiState.value.copy(
            isTapTestRunning = true,
            tapCount = 0,
            tapTarget = 16,
            tapResult = null
        )
    }

    /**
     * Chiamato quando l'utente tocca il pulsante sullo schermo a tempo con i click.
     */
    fun registerUserTap() {
        val state = _uiState.value
        if (!state.isTapTestRunning) return
        val metro = tapMetronome ?: return

        val tapNs = SystemClock.elapsedRealtimeNanos()
        val lastClickScheduledNs = metro.telemetry.value.lastClickOutputElapsedNs
        if (lastClickScheduledNs <= 0L) return

        // Calcola la differenza rispetto al battito emesso (a 120 BPM il periodo è 500 ms)
        val periodMs = 60_000f / TAP_TEST_BPM.toFloat() // 500.0 ms
        var diffMs = (tapNs - lastClickScheduledNs) / 1_000_000f

        // Riporta lo scarto nell'intervallo [-100 ms, +400 ms] (poiché la latenza BT è positiva)
        while (diffMs < -100f) diffMs += periodMs
        while (diffMs > periodMs - 100f) diffMs -= periodMs

        tapOffsetsMs.add(diffMs)
        val count = tapOffsetsMs.size

        if (count >= 16) {
            // Fine dei 16 tocchi: ferma il metronomo e calcola media e dispersione (escludendo i primi 2 di warm-up)
            metro.stop()
            tapMetronome = null

            val analyzed = if (tapOffsetsMs.size > 4) tapOffsetsMs.drop(2) else tapOffsetsMs.toList()
            val stats = computeStats(
                method = "Batti a tempo (16 click)",
                headphonesName = currentHeadphonesName,
                values = analyzed,
                totalAttempted = 16,
                notes = "Primi 2 tocchi esclusi per aggancio ritmo"
            )
            saveCalibrationResult(stats)

            _uiState.value = _uiState.value.copy(
                isTapTestRunning = false,
                tapCount = 16,
                tapResult = stats,
                savedProfiles = loadSavedProfilesSummary()
            )
        } else {
            _uiState.value = _uiState.value.copy(tapCount = count)
        }
    }

    // === (b) Modalità "A contatto" (5 impulsi cuffia → microfono BUILTIN_MIC) ===

    @SuppressLint("MissingPermission")
    fun startContactCalibration(headphonesName: String) {
        stopAll()
        currentHeadphonesName = headphonesName
        _uiState.value = _uiState.value.copy(
            isContactTestRunning = true,
            contactPulseIndex = 0,
            contactResult = null
        )

        Thread({
            val sampleRate = 48_000
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.mode = AudioManager.MODE_NORMAL

            val minRecBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(sampleRate * 2 / 5)

            val recorder = try {
                AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build()
                    )
                    .setBufferSizeInBytes(minRecBuf * 2)
                    .build()
            } catch (_: Exception) {
                finishContactWithError("Errore apertura microfono")
                return@Thread
            }

            // Forza il microfono interno (BUILTIN_MIC)
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                ?.let { recorder.setPreferredDevice(it) }

            val minPlayBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4800)

            val player = try {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build()
                    )
                    .setBufferSizeInBytes(minPlayBuf)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } catch (_: Exception) {
                recorder.release()
                finishContactWithError("Errore apertura AudioTrack")
                return@Thread
            }

            // Prepara un impulso ben percepibile anche per vibrazione (18 ms a 1.100 Hz a pieno volume)
            val pulseSamples = (sampleRate * 18) / 1000 // 864 campioni
            val pulseWave = ShortArray(pulseSamples) { i ->
                val w = 0.5 * (1.0 - cos((2.0 * PI * i) / (pulseSamples - 1)))
                (sin(2.0 * PI * 1100.0 * (i.toDouble() / sampleRate)) * w * 32000.0).toInt().toShort()
            }
            val silenceChunk = ShortArray(480) // 10 ms di silenzio

            recorder.startRecording()
            player.play()

            // 1. Warm-up di 400 ms per misurare il pavimento di rumore del microfono
            val readBuf = ShortArray(480) // blocchi da 10 ms
            var noisePeakSum = 0.0
            var noiseBlocks = 0
            for (k in 0 until 40) {
                player.write(silenceChunk, 0, silenceChunk.size, AudioTrack.WRITE_BLOCKING)
                val r = recorder.read(readBuf, 0, readBuf.size)
                if (r > 0) {
                    var maxAmp = 0
                    for (j in 0 until r) {
                        val a = abs(readBuf[j].toInt())
                        if (a > maxAmp) maxAmp = a
                    }
                    noisePeakSum += maxAmp
                    noiseBlocks++
                }
            }
            val avgNoisePeak = if (noiseBlocks > 0) (noisePeakSum / noiseBlocks).toInt() else 500
            // Soglia di attivazione: almeno 4x il picco di rumore di fondo (e minimo 1800 su 32767)
            val detectionThreshold = (avgNoisePeak * 4).coerceIn(1800, 18000)

            val detectedDelaysMs = mutableListOf<Float>()
            val audioTs = AudioTimestamp()

            // 2. Emette 5 impulsi distanziati di ~650 ms
            for (pulseIdx in 1..5) {
                if (!_uiState.value.isContactTestRunning) break
                _uiState.value = _uiState.value.copy(contactPulseIndex = pulseIdx)

                // Svuota eventuali campioni residui nel buffer di registrazione prima dell'impulso
                recorder.read(readBuf, 0, readBuf.size, AudioRecord.READ_NON_BLOCKING)

                val emitStartNs = SystemClock.elapsedRealtimeNanos()
                player.write(pulseWave, 0, pulseWave.size, AudioTrack.WRITE_BLOCKING)

                // Finestra di ascolto di 550 ms (55 blocchi da 10 ms)
                var detectedDelay: Float? = null
                var samplesElapsedSinceEmit = 0L

                for (windowStep in 0 until 55) {
                    player.write(silenceChunk, 0, silenceChunk.size, AudioTrack.WRITE_BLOCKING)
                    val r = recorder.read(readBuf, 0, readBuf.size)
                    if (r > 0) {
                        if (detectedDelay == null) {
                            for (j in 0 until r) {
                                if (abs(readBuf[j].toInt()) >= detectionThreshold) {
                                    val totalSamplesDelay = samplesElapsedSinceEmit + j
                                    val delayMs = (totalSamplesDelay * 1000f) / sampleRate.toFloat()
                                    // Filtra solo latenze fisicamente plausibili per Bluetooth (25 ms .. 480 ms)
                                    if (delayMs in 25f..480f) {
                                        detectedDelay = delayMs
                                        break
                                    }
                                }
                            }
                        }
                        samplesElapsedSinceEmit += r
                    }
                }

                // Se getTimestamp di AudioRecord fornisce la latenza d'ingresso, sottrai ~10 ms di buffer input
                if (detectedDelay != null) {
                    val inputCompensatedMs = (detectedDelay - 10f).coerceAtLeast(15f)
                    detectedDelaysMs.add(inputCompensatedMs)
                }

                // Pausa di 150 ms tra un impulso e il successivo
                for (p in 0 until 15) {
                    player.write(silenceChunk, 0, silenceChunk.size, AudioTrack.WRITE_BLOCKING)
                    recorder.read(readBuf, 0, readBuf.size)
                }
            }

            try {
                player.stop()
                recorder.stop()
            } catch (_: Exception) {
            }
            player.release()
            recorder.release()

            val result = if (detectedDelaysMs.isNotEmpty()) {
                computeStats(
                    method = "A contatto (5 impulsi)",
                    headphonesName = currentHeadphonesName,
                    values = detectedDelaysMs,
                    totalAttempted = 5,
                    notes = "Soglia mic: $detectionThreshold (fondo: $avgNoisePeak)"
                )
            } else {
                CalibrationResult(
                    method = "A contatto (5 impulsi)",
                    headphonesName = currentHeadphonesName,
                    validSamples = 0,
                    totalSamples = 5,
                    meanOffsetMs = 0f,
                    stdDevMs = 0f,
                    minOffsetMs = 0f,
                    maxOffsetMs = 0f,
                    notes = "Nessun impulso rilevato sopra soglia ($detectionThreshold). Per conduzione ossea premi più forte il trasduttore sul microfono o usa 'Batti a tempo'."
                )
            }

            if (result.validSamples > 0) {
                saveCalibrationResult(result)
            }

            _uiState.value = _uiState.value.copy(
                isContactTestRunning = false,
                contactPulseIndex = 5,
                contactResult = result,
                savedProfiles = loadSavedProfilesSummary()
            )
        }, "KeepApp-ContactCalibrator").start()
    }

    fun stopAll() {
        tapMetronome?.stop()
        tapMetronome = null
        _uiState.value = _uiState.value.copy(
            isTapTestRunning = false,
            isContactTestRunning = false
        )
    }

    private fun finishContactWithError(msg: String) {
        _uiState.value = _uiState.value.copy(
            isContactTestRunning = false,
            contactResult = CalibrationResult(
                method = "A contatto",
                headphonesName = currentHeadphonesName,
                validSamples = 0,
                totalSamples = 5,
                meanOffsetMs = 0f,
                stdDevMs = 0f,
                minOffsetMs = 0f,
                maxOffsetMs = 0f,
                notes = msg
            )
        )
    }

    private fun computeStats(
        method: String,
        headphonesName: String,
        values: List<Float>,
        totalAttempted: Int,
        notes: String
    ): CalibrationResult {
        if (values.isEmpty()) {
            return CalibrationResult(method, headphonesName, 0, totalAttempted, 0f, 0f, 0f, 0f, notes)
        }
        val mean = values.average().toFloat()
        val variance = values.map { (it - mean) * (it - mean) }.average().toFloat()
        val stdDev = sqrt(variance)
        val minVal = values.minOrNull() ?: 0f
        val maxVal = values.maxOrNull() ?: 0f

        return CalibrationResult(
            method = method,
            headphonesName = headphonesName,
            validSamples = values.size,
            totalSamples = totalAttempted,
            meanOffsetMs = mean,
            stdDevMs = stdDev,
            minOffsetMs = minVal,
            maxOffsetMs = maxVal,
            notes = notes
        )
    }

    private fun getCalibrationFile(): File = File(context.filesDir, "headphones_calibration.json")

    private fun saveCalibrationResult(res: CalibrationResult) {
        try {
            val file = getCalibrationFile()
            val array = if (file.exists()) {
                JSONArray(file.readText(Charsets.UTF_8))
            } else {
                JSONArray()
            }
            array.put(JSONObject().apply {
                put("timestamp_ms", System.currentTimeMillis())
                put("headphones", res.headphonesName)
                put("method", res.method)
                put("valid_samples", res.validSamples)
                put("total_samples", res.totalSamples)
                put("mean_offset_ms", res.meanOffsetMs.toDouble())
                put("std_dev_ms", res.stdDevMs.toDouble())
                put("min_ms", res.minOffsetMs.toDouble())
                put("max_ms", res.maxOffsetMs.toDouble())
                put("notes", res.notes)
            })
            file.writeText(array.toString(2), Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }

    private fun loadSavedProfilesSummary(): List<String> {
        return try {
            val file = getCalibrationFile()
            if (!file.exists()) return emptyList()
            val array = JSONArray(file.readText(Charsets.UTF_8))
            val list = mutableListOf<String>()
            for (i in (array.length() - 1) downTo 0) {
                val obj = array.getJSONObject(i)
                val hp = obj.optString("headphones", "?")
                val method = obj.optString("method", "?")
                val mean = obj.optDouble("mean_offset_ms", 0.0)
                val std = obj.optDouble("std_dev_ms", 0.0)
                val valid = obj.optInt("valid_samples", 0)
                val total = obj.optInt("total_samples", 0)
                list.add("$hp • $method → ${String.format(java.util.Locale.US, "%.1f ms (±%.1f ms, %d/%d)", mean, std, valid, total)}")
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        const val TAP_TEST_BPM = 120
    }
}
