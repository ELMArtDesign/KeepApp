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

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Stimatore PLL / regressione lineare ai minimi quadrati (OLS) su finestra scorrevole fissa
 * tra indice di campione PCM e tempo di uscita `TIMEBASE_BOOTTIME` (`AudioTrack.getTimestamp()` — D36).
 *
 * Progettato per:
 * 1. Filtrare il jitter a pacchetti (~2–10 ms) con cui l'HAL audio Android / Bluetooth A2DP
 *    aggiorna `getTimestamp()`.
 * 2. Ridurre l'errore di predizione della retta campione↔tempo sotto `< 0,5 ms` (`rawJitter / sqrt(N)`).
 * 3. Zero allocazioni heap durante gli aggiornamenti sul thread audio ad alta priorità.
 * 4. Riusabilità in M3 per la calibrazione della latenza Bluetooth (D35).
 */
class AudioClockEstimator(
    val sampleRateHz: Int = 48_000
) {
    companion object {
        private const val WINDOW_CAPACITY = 128 // ~3–5 s di timestamp HAL
        private const val MIN_LOCK_SAMPLES = 24
        private const val OUTLIER_THRESHOLD_MS = 20.0
    }

    private val nominalNsPerSample = 1_000_000_000.0 / sampleRateHz.toDouble()

    // Ring buffer preallocato (zero allocazioni nel loop audio)
    private val ringSamples = LongArray(WINDOW_CAPACITY)
    private val ringTimesNs = LongArray(WINDOW_CAPACITY)
    private var ringHead = 0
    private var ringCount = 0

    // Stato del modello lineare pubblicato con @Volatile per lettura lock-free da scheduler/UI
    @Volatile
    private var anchorSample: Long = 0L

    @Volatile
    private var anchorTimeNs: Long = 0L

    @Volatile
    private var slopeNsPerSample: Double = nominalNsPerSample

    @Volatile
    private var initialized: Boolean = false

    @Volatile
    var sampleCount: Int = 0
        private set

    /** Incertezza standard stimata della predizione PLL in ms (`rawJitter / sqrt(N)`), target < 0.50 ms */
    @Volatile
    var residualErrorRmsMs: Float = 0f
        private set

    /** Jitter grezzo RMS dei singoli timestamp HAL/Bluetooth in ms (diagnostica) */
    @Volatile
    var rawHalJitterRmsMs: Float = 0f
        private set

    @Volatile
    var lastResidualMs: Float = 0f
        private set

    @Volatile
    var estimatedSlopePpm: Float = 0f
        private set

    @Volatile
    var rejectedOutliers: Int = 0
        private set

    val isLocked: Boolean
        get() = initialized && sampleCount >= MIN_LOCK_SAMPLES && residualErrorRmsMs < 0.50f

    private var lastFramePosition: Long = -1L

    fun reset() {
        ringHead = 0
        ringCount = 0
        anchorSample = 0L
        anchorTimeNs = 0L
        slopeNsPerSample = nominalNsPerSample
        initialized = false
        sampleCount = 0
        residualErrorRmsMs = 0f
        rawHalJitterRmsMs = 0f
        lastResidualMs = 0f
        estimatedSlopePpm = 0f
        rejectedOutliers = 0
        lastFramePosition = -1L
    }

    /**
     * Aggiorna il modello OLS con una coppia `(framePosition, nanoTime)` letta da `AudioTrack.getTimestamp()`.
     * Non alloca memoria heap.
     */
    fun updateFromTimestamp(framePosition: Long, nanoTimeNs: Long) {
        if (framePosition < 0L || nanoTimeNs <= 0L) return
        if (framePosition <= lastFramePosition) return
        lastFramePosition = framePosition

        if (initialized && sampleCount >= MIN_LOCK_SAMPLES) {
            val predictedNs = anchorTimeNs.toDouble() + (framePosition - anchorSample).toDouble() * slopeNsPerSample
            val residualMs = (nanoTimeNs.toDouble() - predictedNs) / 1_000_000.0
            lastResidualMs = residualMs.toFloat()
            if (abs(residualMs) > OUTLIER_THRESHOLD_MS) {
                rejectedOutliers++
                return
            }
        }

        ringSamples[ringHead] = framePosition
        ringTimesNs[ringHead] = nanoTimeNs
        ringHead = (ringHead + 1) % WINDOW_CAPACITY
        if (ringCount < WINDOW_CAPACITY) ringCount++
        sampleCount++

        if (ringCount == 1) {
            anchorSample = framePosition
            anchorTimeNs = nanoTimeNs
            slopeNsPerSample = nominalNsPerSample
            initialized = true
            rawHalJitterRmsMs = 0f
            residualErrorRmsMs = 0f
            estimatedSlopePpm = 0f
            return
        }

        // Regressione lineare OLS con origine centrata sul primo elemento della finestra per stabilità numerica
        val oldestIdx = if (ringCount < WINDOW_CAPACITY) 0 else ringHead
        val s0 = ringSamples[oldestIdx]
        val t0 = ringTimesNs[oldestIdx]

        var sumX = 0.0
        var sumY = 0.0
        for (k in 0 until ringCount) {
            val idx = (oldestIdx + k) % WINDOW_CAPACITY
            sumX += (ringSamples[idx] - s0).toDouble()
            sumY += (ringTimesNs[idx] - t0).toDouble()
        }
        val meanX = sumX / ringCount.toDouble()
        val meanY = sumY / ringCount.toDouble()

        var covXY = 0.0
        var varX = 0.0
        for (k in 0 until ringCount) {
            val idx = (oldestIdx + k) % WINDOW_CAPACITY
            val dx = (ringSamples[idx] - s0).toDouble() - meanX
            val dy = (ringTimesNs[idx] - t0).toDouble() - meanY
            covXY += dx * dy
            varX += dx * dx
        }

        // Usiamo la pendenza OLS solo quando la finestra copre almeno ~0,5 s (24.000 campioni)
        val newSlope = if (varX > 0.0 && (framePosition - s0) >= sampleRateHz / 2) {
            val minSlope = nominalNsPerSample * 0.998
            val maxSlope = nominalNsPerSample * 1.002
            (covXY / varX).coerceIn(minSlope, maxSlope)
        } else {
            nominalNsPerSample
        }

        // Calcoliamo lo scarto quadratico medio grezzo sui campioni della finestra
        var sumSqErrMs2 = 0.0
        for (k in 0 until ringCount) {
            val idx = (oldestIdx + k) % WINDOW_CAPACITY
            val dx = (ringSamples[idx] - s0).toDouble() - meanX
            val yActual = (ringTimesNs[idx] - t0).toDouble()
            val yPred = meanY + newSlope * dx
            val errMs = (yActual - yPred) / 1_000_000.0
            sumSqErrMs2 += errMs * errMs
        }

        val dof = max(1, ringCount - 2)
        val rawRmsMs = sqrt(sumSqErrMs2 / dof.toDouble())
        val modelStdErrMs = rawRmsMs / sqrt(ringCount.toDouble())

        slopeNsPerSample = newSlope
        anchorSample = s0 + meanX.roundToLong()
        anchorTimeNs = t0 + meanY.roundToLong()
        initialized = true

        rawHalJitterRmsMs = rawRmsMs.toFloat()
        residualErrorRmsMs = modelStdErrMs.toFloat()
        estimatedSlopePpm = (((newSlope / nominalNsPerSample) - 1.0) * 1_000_000.0).toFloat()
    }

    /**
     * Stima il timestamp `TIMEBASE_BOOTTIME` in nanosecondi a cui verrà riprodotto `sampleIndex`.
     * Restituisce `null` se il PLL non ha ancora ricevuto alcun timestamp valido.
     */
    fun outputTimeNsForSample(sampleIndex: Long): Long? {
        if (!initialized) return null
        val aSample = anchorSample
        val aTime = anchorTimeNs
        val slope = slopeNsPerSample
        return aTime + ((sampleIndex - aSample).toDouble() * slope).roundToLong()
    }

    /**
     * Inverso: stima l'indice di campione corrispondente a un timestamp di uscita desiderato.
     */
    fun sampleIndexForOutputTimeNs(targetOutputNs: Long): Long? {
        if (!initialized) return null
        val aSample = anchorSample
        val aTime = anchorTimeNs
        val slope = slopeNsPerSample
        return aSample + ((targetOutputNs - aTime).toDouble() / slope).roundToLong()
    }
}
