/*
 * Copyright 2026 Adriano D'Aprile
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package it.daprile.keepapp.core.replay

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Singolo campione accelerometrico per l'analisi offline di riferimento.
 */
data class AccelSample(
    val timestampNs: Long,
    val ax: Float,
    val ay: Float,
    val az: Float
)

/**
 * Rilevatore OFFLINE e NON CAUSALE degli appoggi di riferimento (`FootstrikeReferenceDetector` — Step A2).
 *
 * Caratteristiche chiave:
 * - Completamente indipendente da `PhaseTracker` (nessuna circolarità di valutazione).
 * - Non causale: usa finestre centrate sia per la rimozione della gravità (±0,50 s) sia per lo
 *   smoothing passa-basso (±0,03 s), garantendo zero ritardo di fase.
 * - Usato esclusivamente dal Replay Harness (`:replay`) e dai test di regressione, mai dall'app live.
 */
object FootstrikeReferenceDetector {

    /**
     * Restituisce i timestamp (`ns`) degli appoggi di riferimento rilevati offline.
     */
    fun detectFootstrikesNs(samples: List<AccelSample>): List<Long> {
        val n = samples.size
        if (n < 30) return emptyList()

        val spanNs = samples.last().timestampNs - samples.first().timestampNs
        if (spanNs <= 0L) return emptyList()
        val fs = ((n - 1).toDouble() * 1_000_000_000.0 / spanNs.toDouble()).toFloat().coerceIn(25f, 250f)

        val mag = FloatArray(n)
        for (i in 0 until n) {
            val s = samples[i]
            mag[i] = sqrt(s.ax * s.ax + s.ay * s.ay + s.az * s.az)
        }

        // 1. Rimozione gravità / DC centrata (±0,50 s) -> zero phase shift
        val hpHalfWin = max(5, (fs * 0.50f).toInt())
        val hp = FloatArray(n)
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) {
            prefix[i + 1] = prefix[i] + mag[i]
        }
        for (i in 0 until n) {
            val lo = max(0, i - hpHalfWin)
            val hi = min(n - 1, i + hpHalfWin)
            val mean = (prefix[hi + 1] - prefix[lo]) / (hi - lo + 1).toDouble()
            hp[i] = (mag[i] - mean).toFloat()
        }

        // 2. Smoothing passa-basso centrato (±0,03 s) per eliminare micro-picchi ad alta frequenza
        val lpHalfWin = max(1, (fs * 0.03f).toInt())
        val filtered = FloatArray(n)
        val hpPrefix = DoubleArray(n + 1)
        for (i in 0 until n) {
            hpPrefix[i + 1] = hpPrefix[i] + hp[i]
        }
        for (i in 0 until n) {
            val lo = max(0, i - lpHalfWin)
            val hi = min(n - 1, i + lpHalfWin)
            filtered[i] = ((hpPrefix[hi + 1] - hpPrefix[lo]) / (hi - lo + 1).toDouble()).toFloat()
        }

        // 3. Inviluppo locale centrato (±0,80 s) per soglia adattiva
        val envHalfWin = max(10, (fs * 0.80f).toInt())
        val absFiltered = FloatArray(n) { kotlin.math.abs(filtered[it]) }
        val absPrefix = DoubleArray(n + 1)
        for (i in 0 until n) {
            absPrefix[i + 1] = absPrefix[i] + absFiltered[i]
        }

        val minSpacingNs = 260_000_000L // <= ~230 SPM
        val peaks = ArrayList<Int>(n / 30)
        for (i in 1 until n - 1) {
            val v = filtered[i]
            if (v > filtered[i - 1] && v >= filtered[i + 1]) {
                val lo = max(0, i - envHalfWin)
                val hi = min(n - 1, i + envHalfWin)
                val localMeanAbs = ((absPrefix[hi + 1] - absPrefix[lo]) / (hi - lo + 1).toDouble()).toFloat()
                val threshold = max(0.55f, localMeanAbs * 1.15f)
                if (v >= threshold) {
                    if (peaks.isEmpty()) {
                        peaks.add(i)
                    } else {
                        val lastIdx = peaks.last()
                        val dtNs = samples[i].timestampNs - samples[lastIdx].timestampNs
                        if (dtNs >= minSpacingNs) {
                            peaks.add(i)
                        } else if (v > filtered[lastIdx]) {
                            peaks[peaks.lastIndex] = i
                        }
                    }
                }
            }
        }

        return peaks.map { samples[it].timestampNs }
    }
}
