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
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Stimatore PLL / regressione lineare online tra indice di campione PCM e tempo di uscita
 * `TIMEBASE_BOOTTIME` (`AudioTrack.getTimestamp()` — D36).
 *
 * Progettato per:
 * 1. Assorbire il jitter a scatti (~5–10 ms) con cui l'HAL audio Android aggiorna `getTimestamp()`.
 * 2. Offrire una predizione liscia `< 0,5 ms` di errore residuo dopo la fase di aggancio iniziale.
 * 3. Zero allocazioni heap durante gli aggiornamenti sul thread audio ad alta priorità.
 * 4. Riusabilità in M3 per la calibrazione della latenza Bluetooth (D35).
 */
class AudioClockEstimator(
    val sampleRateHz: Int = 48_000
) {
    private val nominalNsPerSample = 1_000_000_000.0 / sampleRateHz.toDouble()

    // Stato del filtro lineare (acceso dal thread audio; pubblicato con @Volatile per lettura lock-free)
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

    @Volatile
    var residualErrorRmsMs: Float = 0f
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
        get() = initialized && sampleCount >= 8 && residualErrorRmsMs < 1.5f

    private var lastFramePosition: Long = -1L
    private var residualVarMs2: Double = 0.0

    fun reset() {
        anchorSample = 0L
        anchorTimeNs = 0L
        slopeNsPerSample = nominalNsPerSample
        initialized = false
        sampleCount = 0
        residualErrorRmsMs = 0f
        lastResidualMs = 0f
        estimatedSlopePpm = 0f
        rejectedOutliers = 0
        lastFramePosition = -1L
        residualVarMs2 = 0.0
    }

    /**
     * Aggiorna il modello con una coppia `(framePosition, nanoTime)` letta da `AudioTrack.getTimestamp()`.
     * Non alloca memoria heap.
     */
    fun updateFromTimestamp(framePosition: Long, nanoTimeNs: Long) {
        if (framePosition < 0L || nanoTimeNs <= 0L) return
        if (framePosition == lastFramePosition) return
        lastFramePosition = framePosition

        if (!initialized) {
            anchorSample = framePosition
            anchorTimeNs = nanoTimeNs
            slopeNsPerSample = nominalNsPerSample
            initialized = true
            sampleCount = 1
            lastResidualMs = 0f
            residualErrorRmsMs = 0f
            estimatedSlopePpm = 0f
            return
        }

        val deltaSamples = (framePosition - anchorSample).toDouble()
        if (deltaSamples <= 0.0) return

        val predictedNs = anchorTimeNs.toDouble() + deltaSamples * slopeNsPerSample
        val residualNs = nanoTimeNs.toDouble() - predictedNs
        val residualMs = residualNs / 1_000_000.0

        // Rigetto outlier dopo warmup iniziale (es. glitch HAL > 15 ms)
        if (sampleCount >= 8 && abs(residualMs) > 15.0) {
            rejectedOutliers++
            return
        }

        // Guadagno adattivo: più rapido durante i primi campioni, più stabile a regime
        val alphaOffset = if (sampleCount < 10) 0.30 else 0.06
        val alphaSlope = if (sampleCount < 10) 0.0 else 0.002

        // Aggiorniamo l'ancora traslandola al punto corrente con correzione filtrata
        val filteredTimeNs = predictedNs + alphaOffset * residualNs
        if (alphaSlope > 0.0 && deltaSamples >= 480.0) {
            val instantSlope = (nanoTimeNs - anchorTimeNs).toDouble() / deltaSamples
            // Clamp della deriva clock fisica plausibile (±2000 ppm = ±0.2%)
            val minSlope = nominalNsPerSample * 0.998
            val maxSlope = nominalNsPerSample * 1.002
            val updatedSlope = slopeNsPerSample + alphaSlope * (instantSlope - slopeNsPerSample)
            slopeNsPerSample = updatedSlope.coerceIn(minSlope, maxSlope)
        }

        anchorSample = framePosition
        anchorTimeNs = filteredTimeNs.roundToLong()
        sampleCount++

        lastResidualMs = residualMs.toFloat()
        val res2 = residualMs * residualMs
        residualVarMs2 = if (sampleCount <= 2) res2 else (0.90 * residualVarMs2 + 0.10 * res2)
        residualErrorRmsMs = sqrt(residualVarMs2).toFloat()
        estimatedSlopePpm = (((slopeNsPerSample / nominalNsPerSample) - 1.0) * 1_000_000.0).toFloat()
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
