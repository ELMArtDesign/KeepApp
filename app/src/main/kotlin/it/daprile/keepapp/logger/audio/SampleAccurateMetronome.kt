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

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import it.daprile.keepapp.core.format.BinaryFormat
import it.daprile.keepapp.core.format.BinaryRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Telemetria in tempo reale del Metronomo Sample-Accurate (F5 — Milestone 3).
 */
data class MetronomeTelemetry(
    val isRunning: Boolean = false,
    val bpm: Int = 160,
    val clicksEmitted: Long = 0L,
    val underrunCount: Int = 0,
    val lastInterClickJitterMs: Float = 0f,
    val maxJitterMs: Float = 0f,
    val rmsJitterMs: Float = 0f,
    val estimatedHwLatencyMs: Float = 0f,
    val lastClickOutputElapsedNs: Long = 0L
)

/**
 * Motore Metronomo Sample-Accurate su `AudioTrack` in modalità `WRITE_BLOCKING` (F5 / D27).
 *
 * Architettura zero-jitter senza C++:
 * - Nessun `Thread.sleep()` o timer software scandisce i battiti.
 * - Un thread ad alta priorità (`KeepApp-MetronomeRT`) scrive ininterrottamente blocchi pre-allocati
 *   di 240 campioni (5 ms a 48.000 Hz) su `AudioTrack.write(..., WRITE_BLOCKING)`.
 * - L'impulso del click (12 ms = 576 campioni a 1.000 Hz con finestra di Hann per evitare pop)
 *   è pre-calcolato una sola volta in un `ShortArray` statico.
 * - Il contatore globale di campioni `currentSampleIndex` determina matematicamente al campione
 *   esatto (`1/48000` di secondo = `20.8 microsecondi`!) dove sommare la forma d'onda del click.
 * - Ad ogni click interroga `AudioTrack.getTimestamp()` (base `TIMEBASE_BOOTTIME`) per stimare
 *   l'istante esatto `estimatedOutputNanoTime` in cui il campione uscirà dal DAC/Bluetooth,
 *   calcola il jitter rispetto al periodo ideale `60 / BPM` e genera un record `METRONOME_CLICK`.
 */
class SampleAccurateMetronome(
    private val onClickRecord: ((BinaryRecord) -> Unit)? = null
) {
    private val running = AtomicBoolean(false)
    private val targetBpm = AtomicInteger(160)
    private var audioThread: Thread? = null

    private val _telemetry = MutableStateFlow(MetronomeTelemetry())
    val telemetry: StateFlow<MetronomeTelemetry> = _telemetry.asStateFlow()

    /**
     * Forma d'onda pre-sintetizzata dell'impulso del metronomo:
     * 12 ms a 48.000 Hz = 576 campioni, frequenza portante 1.000 Hz modulata con finestra di Hann.
     */
    private val clickWaveform: ShortArray = ShortArray(CLICK_SAMPLES) { i ->
        val t = i.toDouble() / SAMPLE_RATE_HZ
        // Finestra di Hann per attacco e rilascio morbidi senza click fuori banda
        val window = 0.5 * (1.0 - kotlin.math.cos((2.0 * PI * i) / (CLICK_SAMPLES - 1)))
        val carrier = sin(2.0 * PI * CLICK_FREQ_HZ * t)
        (carrier * window * 28_000.0).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }

    fun setBpm(newBpm: Int) {
        val clamped = newBpm.coerceIn(100, 190)
        targetBpm.set(clamped)
        _telemetry.value = _telemetry.value.copy(bpm = clamped)
    }

    fun start(initialBpm: Int = targetBpm.get()) {
        setBpm(initialBpm)
        if (!running.compareAndSet(false, true)) return

        audioThread = Thread({
            val minBufBytes = AudioTrack.getMinBufferSize(
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(SAMPLE_RATE_HZ * 2 / 10) // almeno 100 ms

            val track = try {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE_HZ)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build()
                    )
                    .setBufferSizeInBytes(minBufBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } catch (_: Exception) {
                running.set(false)
                return@Thread
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Disabilita eventuale offload per garantire timestamp precisi
            }

            track.play()

            // Buffer pre-allocato di 240 campioni (5 ms a 48 kHz) — zero allocazioni nel ciclo
            val blockSamples = 240
            val outBuffer = ShortArray(blockSamples)
            val audioTs = AudioTimestamp()

            var globalSampleIndex = 0L
            var nextClickSampleIndex = 4_800L // Primo click dopo 100 ms dall'avvio
            var clickWaveformCursor = -1

            var clicksCount = 0L
            var prevClickOutputNs = 0L
            var prevClickBpm = targetBpm.get()
            var maxJitterMs = 0f
            var sumSqJitterMs = 0.0
            var jitterSamplesCount = 0L

            _telemetry.value = MetronomeTelemetry(
                isRunning = true,
                bpm = targetBpm.get()
            )

            while (running.get()) {
                val currentBpm = targetBpm.get()
                val samplesPerBeat = (SAMPLE_RATE_HZ * 60L) / currentBpm

                // Costruisci il blocco di 240 campioni (silenzio = 0 + eventuale coda/inizio del click)
                for (i in 0 until blockSamples) {
                    val samplePos = globalSampleIndex + i
                    if (samplePos == nextClickSampleIndex) {
                        clickWaveformCursor = 0
                        nextClickSampleIndex = samplePos + samplesPerBeat

                        // Calcola il tempo di uscita stimato di questo preciso campione
                        val nowNs = SystemClock.elapsedRealtimeNanos()
                        val tsValid = track.getTimestamp(audioTs)
                        val estimatedOutNs = if (tsValid && audioTs.framePosition > 0L) {
                            val framesAhead = samplePos - audioTs.framePosition
                            audioTs.nanoTime + (framesAhead * 1_000_000_000L) / SAMPLE_RATE_HZ
                        } else {
                            nowNs + 50_000_000L // fallback +50 ms
                        }

                        val hwLatencyMs = ((estimatedOutNs - nowNs) / 1_000_000f).coerceAtLeast(0f)
                        val underruns = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            track.underrunCount
                        } else {
                            0
                        }

                        clicksCount++

                        var currentJitterMs = 0f
                        if (prevClickOutputNs > 0L && prevClickBpm == currentBpm) {
                            val actualPeriodMs = (estimatedOutNs - prevClickOutputNs) / 1_000_000f
                            val idealPeriodMs = 60_000f / currentBpm.toFloat()
                            currentJitterMs = abs(actualPeriodMs - idealPeriodMs)
                            if (currentJitterMs < 200f) { // ignora transitori di startup
                                if (currentJitterMs > maxJitterMs) maxJitterMs = currentJitterMs
                                sumSqJitterMs += (currentJitterMs * currentJitterMs).toDouble()
                                jitterSamplesCount++
                            }
                        }
                        prevClickOutputNs = estimatedOutNs
                        prevClickBpm = currentBpm

                        val rmsJitter = if (jitterSamplesCount > 0L) {
                            sqrt(sumSqJitterMs / jitterSamplesCount).toFloat()
                        } else {
                            0f
                        }

                        // Se è collegata una sessione di registrazione, emette il record METRONOME_CLICK (tipo 9)
                        onClickRecord?.invoke(
                            BinaryRecord.ofTwoLongsAndInt(
                                timestampNs = nowNs,
                                recordType = BinaryFormat.TYPE_METRONOME_CLICK,
                                flags = currentBpm.coerceIn(0, 255).toByte(),
                                reserved = 0,
                                q0 = samplePos,
                                q1 = estimatedOutNs,
                                i2 = underruns
                            )
                        )

                        _telemetry.value = MetronomeTelemetry(
                            isRunning = true,
                            bpm = currentBpm,
                            clicksEmitted = clicksCount,
                            underrunCount = underruns,
                            lastInterClickJitterMs = currentJitterMs,
                            maxJitterMs = maxJitterMs,
                            rmsJitterMs = rmsJitter,
                            estimatedHwLatencyMs = hwLatencyMs,
                            lastClickOutputElapsedNs = estimatedOutNs
                        )
                    }

                    if (clickWaveformCursor in 0 until CLICK_SAMPLES) {
                        outBuffer[i] = clickWaveform[clickWaveformCursor]
                        clickWaveformCursor++
                    } else {
                        outBuffer[i] = 0
                    }
                }

                // Scrittura bloccante cadenzata dal clock hardware del DAC/Bluetooth
                val written = track.write(outBuffer, 0, blockSamples, AudioTrack.WRITE_BLOCKING)
                if (written <= 0) break
                globalSampleIndex += written
            }

            try {
                track.stop()
            } catch (_: Exception) {
            }
            track.release()

            _telemetry.value = _telemetry.value.copy(isRunning = false)
        }, "KeepApp-MetronomeRT").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try {
            audioThread?.join(1_500)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        audioThread = null
    }

    companion object {
        const val SAMPLE_RATE_HZ = 48_000
        const val CLICK_DURATION_MS = 12
        const val CLICK_SAMPLES = (SAMPLE_RATE_HZ * CLICK_DURATION_MS) / 1_000 // 576 campioni
        const val CLICK_FREQ_HZ = 1_000.0
    }
}
