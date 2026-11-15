/*
 * Copyright 2026 Adriano D'Aprile
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package it.daprile.keepapp.core.audio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Metodo utilizzato per stimare la latenza di uscita delle cuffie Bluetooth (D35).
 */
enum class CalibrationMethod(val label: String) {
    CONTACT_IMPULSE("A contatto (Microfono)"),
    TAP_TO_BEAT("Batti a tempo (Tap)"),
    MANUAL("Manuale")
}

/**
 * Risultato aggregato di una sessione di calibrazione Bluetooth.
 */
data class CalibrationSummary(
    val medianLatencyMs: Float,
    val meanLatencyMs: Float,
    val stdDevMs: Float,
    val validSamples: Int,
    val totalSamples: Int,
    val method: CalibrationMethod
)

/**
 * Algoritmi puri in `:core-domain` per la stima della latenza Bluetooth (Milestone 3 / D35):
 * 1. Rilevazione del fronte d'attacco dell'impulso registrato dal microfono ("A contatto").
 * 2. Associazione tap-to-beat con esclusione warmup e filtro outlier MAD ("Batti a tempo").
 */
object BluetoothCalibrationMath {

    /**
     * Analizza una finestra di campioni PCM16 del microfono alla ricerca del fronte d'attacco di un impulso.
     *
     * Restituisce l'indice assoluto dentro `pcmWindow` del primo campione che supera il 35% del picco
     * locale (fronte di salita netto, più preciso del picco massimo contro eco/riverbero), oppure `null`
     * se il segnale non supera la soglia SNR rispetto al rumore di fondo.
     */
    fun detectImpulseOnsetSample(
        pcmWindow: ShortArray,
        startIdx: Int = 0,
        endIdxExclusive: Int = pcmWindow.size,
        noiseFloorRms: Float,
        snrThreshold: Float = 2.8f,
        minPeakAmplitude: Float = 250f
    ): Int? {
        val lo = startIdx.coerceIn(0, pcmWindow.size)
        val hi = endIdxExclusive.coerceIn(lo, pcmWindow.size)
        if (hi <= lo) return null

        var maxAbs = 0f
        var maxIdx = -1
        for (i in lo until hi) {
            val a = abs(pcmWindow[i].toFloat())
            if (a > maxAbs) {
                maxAbs = a
                maxIdx = i
            }
        }

        val requiredPeak = max(noiseFloorRms * snrThreshold, minPeakAmplitude)
        if (maxIdx < 0 || maxAbs < requiredPeak) return null

        // Risaliamo indietro fino a ~5 ms (240 campioni a 48 kHz) per trovare l'inizio del fronte d'attacco
        val onsetThreshold = max(noiseFloorRms * 3.0f, maxAbs * 0.35f)
        val searchStart = max(lo, maxIdx - 240)
        for (i in searchStart..maxIdx) {
            if (abs(pcmWindow[i].toFloat()) >= onsetThreshold) {
                return i
            }
        }
        return maxIdx
    }

    /**
     * Calcola il valore RMS del rumore di fondo su un blocco PCM16.
     */
    fun computeRms(pcm: ShortArray, startIdx: Int = 0, endIdxExclusive: Int = pcm.size): Float {
        val lo = startIdx.coerceIn(0, pcm.size)
        val hi = endIdxExclusive.coerceIn(lo, pcm.size)
        val count = hi - lo
        if (count <= 0) return 0f
        var sumSq = 0.0
        for (i in lo until hi) {
            val v = pcm[i].toDouble()
            sumSq += v * v
        }
        return sqrt(sumSq / count.toDouble()).toFloat()
    }

    /**
     * Stima la latenza Bluetooth dal test "Batti a tempo" (D35):
     * confronta i timestamp dei tap dell'utente (`tapTimesNs`) con i timestamp di uscita stimati
     * dei battiti emessi dal PLL (`beatOutputTimesNs`), scartando i primi `warmupBeatsToSkip` battiti.
     */
    fun computeTapToBeatLatency(
        beatOutputTimesNs: List<Long>,
        tapTimesNs: List<Long>,
        beatPeriodMs: Float = 500f,
        warmupBeatsToSkip: Int = 2
    ): CalibrationSummary? {
        if (beatOutputTimesNs.size <= warmupBeatsToSkip || tapTimesNs.isEmpty()) return null

        val activeBeats = beatOutputTimesNs.drop(warmupBeatsToSkip)
        val minValidDeltaMs = -80f // tolleranza piccola anticipazione umana se latenza BT è bassissima
        val maxValidDeltaMs = min(550f, beatPeriodMs * 0.90f)

        val candidates = ArrayList<Float>(tapTimesNs.size)
        for (tapNs in tapTimesNs) {
            var bestDeltaMs: Float? = null
            var bestScore = Float.MAX_VALUE
            for (beatNs in activeBeats) {
                val deltaMs = (tapNs - beatNs).toDouble() / 1_000_000.0
                val d = deltaMs.toFloat()
                if (d in minValidDeltaMs..maxValidDeltaMs) {
                    // Preferiamo il battito emesso più recente prima del tap (d >= 0)
                    val score = if (d >= 0f) d else abs(d) + 200f
                    if (score < bestScore) {
                        bestScore = score
                        bestDeltaMs = d.coerceAtLeast(0f)
                    }
                }
            }
            if (bestDeltaMs != null) {
                candidates.add(bestDeltaMs)
            }
        }

        return summarizeLatencies(
            latenciesMs = candidates,
            totalAttempts = tapTimesNs.size,
            method = CalibrationMethod.TAP_TO_BEAT
        )
    }

    /**
     * Riassume una lista di misure di latenza (in ms), filtrando gli outlier tramite MAD
     * se sono disponibili almeno 4 campioni.
     */
    fun summarizeLatencies(
        latenciesMs: List<Float>,
        totalAttempts: Int,
        method: CalibrationMethod
    ): CalibrationSummary? {
        val plausible = latenciesMs.filter { it in 0f..650f }
        if (plausible.isEmpty()) return null

        val filtered = if (plausible.size >= 4) {
            val med = median(plausible)
            val absDevs = plausible.map { abs(it - med) }
            val mad = max(4.0f, median(absDevs))
            val limit = 2.8f * mad
            val inliers = plausible.filter { abs(it - med) <= limit }
            if (inliers.isNotEmpty()) inliers else plausible
        } else {
            plausible
        }

        val med = median(filtered)
        val mean = filtered.sum() / filtered.size.toFloat()
        var varSum = 0.0
        for (v in filtered) {
            val d = (v - mean).toDouble()
            varSum += d * d
        }
        val std = sqrt(varSum / filtered.size.toDouble()).toFloat()

        return CalibrationSummary(
            medianLatencyMs = med,
            meanLatencyMs = mean,
            stdDevMs = std,
            validSamples = filtered.size,
            totalSamples = max(totalAttempts, filtered.size),
            method = method
        )
    }

    private fun median(values: List<Float>): Float {
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) {
            sorted[n / 2]
        } else {
            (sorted[n / 2 - 1] + sorted[n / 2]) * 0.5f
        }
    }
}
