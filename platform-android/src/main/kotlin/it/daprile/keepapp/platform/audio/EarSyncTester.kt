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

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Stato osservabile della "Prova a orecchio" (altoparlante del telefono + cuffia Bluetooth).
 */
data class EarSyncUiState(
    val isRunning: Boolean = false,
    val speakerRoutedCorrectly: Boolean = true,
    val btRoutedCorrectly: Boolean = true,
    val statusText: String = "Pronto per la prova a orecchio"
)

/**
 * Generatore di click di confronto altoparlante vs cuffia Bluetooth (1 Hz).
 *
 * Ogni secondo emette un click di riferimento sull'altoparlante interno del telefono e un click
 * compensato sulle cuffie Bluetooth, allineati sullo stesso asse temporale `TIMEBASE_BOOTTIME`.
 * Verifica inoltre `AudioTrack.routedDevice` per rilevare se Android/MIUI instrada davvero
 * i due stream su dispositivi fisici distinti.
 */
class EarSyncTester(
    context: Context,
    private val sampleRateHz: Int = DrumSynthWavetable.SAMPLE_RATE_HZ
) {
    companion object {
        private const val CHUNK_FRAMES = 480 // 10 ms a 48 kHz
        private const val PERIOD_MS = 1000L
    }

    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _state = MutableStateFlow(EarSyncUiState())
    val state: StateFlow<EarSyncUiState> = _state.asStateFlow()

    @Volatile
    private var running = false

    @Volatile
    private var totalAdvanceMs: Float = 0f

    private var workerThread: Thread? = null

    fun setAdvanceMs(advanceMs: Float) {
        totalAdvanceMs = advanceMs
    }

    @Synchronized
    fun start(advanceMs: Float) {
        if (running) return
        totalAdvanceMs = advanceMs

        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val speakerDev = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        val btDev = outputs.firstOrNull { dev ->
            dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    (dev.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        dev.type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                        dev.type == AudioDeviceInfo.TYPE_HEARING_AID))
        }

        if (speakerDev == null || btDev == null) {
            _state.value = EarSyncUiState(
                isRunning = false,
                speakerRoutedCorrectly = speakerDev != null,
                btRoutedCorrectly = btDev != null,
                statusText = if (btDev == null) {
                    "Collega prima le cuffie Bluetooth per eseguire la prova a orecchio."
                } else {
                    "Altoparlante interno non disponibile."
                }
            )
            return
        }

        running = true
        _state.value = EarSyncUiState(
            isRunning = true,
            statusText = "Avvio doppio click (altoparlante + cuffia)..."
        )
        workerThread = Thread({ runLoop(speakerDev, btDev) }, "KeepApp-EarSync").also { it.start() }
    }

    @Synchronized
    fun stop() {
        running = false
        val t = workerThread
        workerThread = null
        try {
            t?.join(1500)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        _state.value = _state.value.copy(
            isRunning = false,
            statusText = "Prova a orecchio fermata"
        )
    }

    private fun runLoop(speakerDev: AudioDeviceInfo, btDev: AudioDeviceInfo) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (_: Throwable) {
        }

        val trackSpeaker = buildTrack(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION, speakerDev)
        val trackBt = buildTrack(AudioAttributes.USAGE_MEDIA, btDev)

        val estSpeaker = AudioClockEstimator(sampleRateHz)
        val estBt = AudioClockEstimator(sampleRateHz)
        val tsSpeaker = AudioTimestamp()
        val tsBt = AudioTimestamp()

        val clickSpeaker = buildClick(1200.0, 12) // leggermente più acuto per l'altoparlante
        val clickBt = buildClick(900.0, 12)       // leggermente più caldo per la cuffia

        val bufSpeaker = ShortArray(CHUNK_FRAMES)
        val bufBt = ShortArray(CHUNK_FRAMES)

        var writtenSpeaker = 0L
        var writtenBt = 0L

        var spkClickPos = clickSpeaker.size
        var btClickPos = clickBt.size
        var spkTriggerSample = -1L
        var btTriggerSample = -1L

        // Griglia di riferimento ogni 1 secondo in TIMEBASE_BOOTTIME (parte tra 1,2 s)
        var nextReferenceClickNs = SystemClock.elapsedRealtimeNanos() + 1_200_000_000L
        var lastRouteCheckMs = 0L

        try {
            trackSpeaker.play()
            trackBt.play()

            while (running) {
                if (trackSpeaker.getTimestamp(tsSpeaker)) {
                    estSpeaker.updateFromTimestamp(
                        tsSpeaker.framePosition,
                        AudioClockEstimator.normalizeTrackTimestampToBoottimeNs(tsSpeaker.nanoTime)
                    )
                }
                if (trackBt.getTimestamp(tsBt)) {
                    estBt.updateFromTimestamp(
                        tsBt.framePosition,
                        AudioClockEstimator.normalizeTrackTimestampToBoottimeNs(tsBt.nanoTime)
                    )
                }

                // Controlla ogni 1s se il routing effettivo corrisponde ai due dispositivi separati
                val nowMs = SystemClock.elapsedRealtime()
                if (nowMs - lastRouteCheckMs >= 1000L) {
                    lastRouteCheckMs = nowMs
                    val routedSpk = trackSpeaker.routedDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    val routedBt = trackBt.routedDevice?.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    val msg = if (routedSpk && routedBt) {
                        "Click attivo ogni 1 s (altoparlante + cuffia separati)"
                    } else {
                        "ATTENZIONE: il sistema sta instradando i due suoni sullo stesso canale!"
                    }
                    _state.value = EarSyncUiState(
                        isRunning = true,
                        speakerRoutedCorrectly = routedSpk,
                        btRoutedCorrectly = routedBt,
                        statusText = msg
                    )
                }

                // Pianifica il prossimo battito sul clock comune TIMEBASE_BOOTTIME
                val nowBootNs = SystemClock.elapsedRealtimeNanos()
                if (nowBootNs > nextReferenceClickNs + 300_000_000L) {
                    nextReferenceClickNs += PERIOD_MS * 1_000_000L
                }

                if (spkTriggerSample < 0L && btTriggerSample < 0L) {
                    val sSpk = estSpeaker.sampleIndexForOutputTimeNs(nextReferenceClickNs)
                    val advanceNs = (totalAdvanceMs.toDouble() * 1_000_000.0).roundToLong()
                    val sBt = estBt.sampleIndexForOutputTimeNs(nextReferenceClickNs - advanceNs)
                    if (sSpk != null && sBt != null && sSpk >= writtenSpeaker && sBt >= writtenBt) {
                        spkTriggerSample = sSpk
                        btTriggerSample = sBt
                        nextReferenceClickNs += PERIOD_MS * 1_000_000L
                    }
                }

                // Riempi chunk altoparlante
                bufSpeaker.fill(0)
                if (spkTriggerSample in writtenSpeaker until (writtenSpeaker + CHUNK_FRAMES)) {
                    spkClickPos = 0
                    val startOffset = (spkTriggerSample - writtenSpeaker).toInt()
                    for (i in startOffset until CHUNK_FRAMES) {
                        if (spkClickPos < clickSpeaker.size) bufSpeaker[i] = clickSpeaker[spkClickPos++]
                    }
                    spkTriggerSample = -1L
                } else if (spkClickPos < clickSpeaker.size) {
                    for (i in 0 until CHUNK_FRAMES) {
                        if (spkClickPos < clickSpeaker.size) bufSpeaker[i] = clickSpeaker[spkClickPos++]
                    }
                }

                // Riempi chunk Bluetooth
                bufBt.fill(0)
                if (btTriggerSample in writtenBt until (writtenBt + CHUNK_FRAMES)) {
                    btClickPos = 0
                    val startOffset = (btTriggerSample - writtenBt).toInt()
                    for (i in startOffset until CHUNK_FRAMES) {
                        if (btClickPos < clickBt.size) bufBt[i] = clickBt[btClickPos++]
                    }
                    btTriggerSample = -1L
                } else if (btClickPos < clickBt.size) {
                    for (i in 0 until CHUNK_FRAMES) {
                        if (btClickPos < clickBt.size) bufBt[i] = clickBt[btClickPos++]
                    }
                }

                val wS = trackSpeaker.write(bufSpeaker, 0, CHUNK_FRAMES, AudioTrack.WRITE_BLOCKING)
                val wB = trackBt.write(bufBt, 0, CHUNK_FRAMES, AudioTrack.WRITE_BLOCKING)
                if (wS > 0) writtenSpeaker += wS
                if (wB > 0) writtenBt += wB
                if (wS < 0 || wB < 0) break
            }
        } finally {
            releaseTrack(trackSpeaker)
            releaseTrack(trackBt)
        }
    }

    private fun buildTrack(usage: Int, preferredDevice: AudioDeviceInfo): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
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
            .build()
            .apply {
                setPreferredDevice(preferredDevice)
            }
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

    private fun buildClick(freqHz: Double, durationMs: Int): ShortArray {
        val len = (sampleRateHz * durationMs) / 1000
        return ShortArray(len) { i ->
            val t = i.toDouble() / sampleRateHz
            val hann = 0.5 * (1.0 - cos(2.0 * PI * i / (len - 1)))
            val v = sin(2.0 * PI * freqHz * t) * hann * 0.85
            (v * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
        }
    }
}
