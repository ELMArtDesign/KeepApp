/*
 * Copyright 2026 Adriano D'Aprile
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package it.daprile.keepapp.core.cadence

import it.daprile.keepapp.core.config.KeepAppConfig
import it.daprile.keepapp.core.format.PhonePosition
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Risultato di un singolo ciclo di stima della cadenza (D38).
 */
data class CadenceEstimate(
    /** Cadenza stimata in passi al minuto (SPM / BPM). Sotto soglia di confidenza resta ferma all'ultimo valore valido */
    val cadenceSpm: Float,
    /** Confidenza della stima nell'intervallo [0, 1] */
    val confidence: Float,
    /** Energia dinamica RMS (m/s^2) del segnale accelerometrico filtrato nella finestra di 4 s */
    val dynamicRmsMps2: Float,
    /** Cadenza grezza calcolata dagli eventi di TYPE_STEP_DETECTOR (0 se non disponibile) */
    val stepDetectorSpm: Float,
    /** Indica se la stima corrente ha superato la soglia di confidenza (altrimenti è in stato 'hold') */
    val isLockedOrValid: Boolean,
    /** Ultimo campione |a| filtrato passa-banda (utile al PhaseTracker per i picchi d'impatto) */
    val latestFilteredAccel: Float = 0f,
    /** Ritardo di gruppo (ns) del passa-banda causale alla cadenza corrente */
    val filterGroupDelayNs: Long = 0L
)

/**
 * Stimatore della cadenza del passo in Kotlin puro (D38 — `:core-domain`).
 *
 * Catena di elaborazione (D38):
 * 1. Modulo dell'accelerazione $|a| = \sqrt{a_x^2 + a_y^2 + a_z^2}$.
 * 2. Filtro IIR Butterworth del 2° ordine passa-alto a 0,5 Hz + passa-basso a ~5,0 Hz.
 * 3. Autocorrelazione normalizzata su finestra scorrevole di 4,0 s aggiornata ogni 0,5 s.
 * 4. Ricerca nell'intervallo 60–220 passi/min con selezione del primo picco fondamentale significativo
 *    (evitando multipli interi del periodo $2T, 3T$) e interpolazione parabolica sub-campione.
 * 5. Scelta dell'armonica corretta (fondamentale del passo vs periodo di falcata doppia in tasca/mano)
 *    tramite l'identità armonica $R(T_{\text{stride}}) + R(T_{\text{stride}}/2) = 2 E_{\text{step}}$,
 *    `TYPE_STEP_DETECTOR`, `PositionClassifier` ed energia d'impatto.
 * 6. Sotto la soglia di confidenza (`cadenceConfidenceThreshold`), il BPM resta fermo all'ultimo
 *    valore valido senza mai inseguire il rumore.
 */
class CadenceEstimator(
    private val config: KeepAppConfig = KeepAppConfig()
) {
    private data class FilteredSample(val timestampNs: Long, val value: Float)

    private val windowSamples = ArrayDeque<FilteredSample>(512)
    private val stepTimestampsNs = ArrayDeque<Long>(64)

    private var sampleRateHz: Float = config.nominalAccelSampleRateHz
    private var prevSampleTimestampNs: Long = 0L

    private var highPassBiquad = BiquadFilter.highPass(sampleRateHz, config.cadenceHighPassCutoffHz)
    private var lowPassBiquad = BiquadFilter.lowPass(sampleRateHz, config.cadenceLowPassCutoffHz)

    private var lastUpdateTimestampNs: Long = 0L
    private var heldCadenceSpm: Float = 0f
    private var lastConfidence: Float = 0f
    private var lastDynamicRms: Float = 0f
    private var lastStepDetectorSpm: Float = 0f
    private var lastFilteredValue: Float = 0f

    fun onStepDetected(timestampNs: Long) {
        stepTimestampsNs.addLast(timestampNs)
        val cutoffNs = timestampNs - 6_000_000_000L
        while (stepTimestampsNs.isNotEmpty() && stepTimestampsNs.first() < cutoffNs) {
            stepTimestampsNs.removeFirst()
        }
    }

    fun onAccelerometer(
        timestampNs: Long,
        ax: Float,
        ay: Float,
        az: Float,
        position: PhonePosition = PhonePosition.ARM
    ): CadenceEstimate {
        if (prevSampleTimestampNs > 0L) {
            val dtNs = timestampNs - prevSampleTimestampNs
            if (dtNs in 2_000_000L..50_000_000L) {
                val instHz = 1_000_000_000f / dtNs.toFloat()
                sampleRateHz = 0.98f * sampleRateHz + 0.02f * instHz
            }
        }
        prevSampleTimestampNs = timestampNs

        val magnitude = sqrt(ax * ax + ay * ay + az * az)
        val hpOut = highPassBiquad.process(magnitude)
        val bpOut = lowPassBiquad.process(hpOut)
        lastFilteredValue = bpOut

        windowSamples.addLast(FilteredSample(timestampNs, bpOut))
        val windowDurationNs = (config.cadenceWindowSeconds * 1_000_000_000f).toLong()
        val cutoffNs = timestampNs - windowDurationNs
        while (windowSamples.isNotEmpty() && windowSamples.first().timestampNs < cutoffNs) {
            windowSamples.removeFirst()
        }

        val updateIntervalNs = (config.cadenceUpdateIntervalSeconds * 1_000_000_000f).toLong()
        if (lastUpdateTimestampNs == 0L || timestampNs - lastUpdateTimestampNs >= updateIntervalNs) {
            lastUpdateTimestampNs = timestampNs
            computeAutocorrelationCadence(timestampNs, position)
        }

        return currentEstimate()
    }

    fun currentEstimate(): CadenceEstimate {
        val groupDelayNs = if (heldCadenceSpm >= config.minCadenceSpm) {
            BiquadFilter.computeBandpassGroupDelayNs(
                sampleRateHz = sampleRateHz,
                hpCutoffHz = config.cadenceHighPassCutoffHz,
                lpCutoffHz = config.cadenceLowPassCutoffHz,
                frequencyHz = heldCadenceSpm / 60f
            )
        } else 0L
        return CadenceEstimate(
            cadenceSpm = heldCadenceSpm,
            confidence = lastConfidence,
            dynamicRmsMps2 = lastDynamicRms,
            stepDetectorSpm = lastStepDetectorSpm,
            isLockedOrValid = lastConfidence >= config.cadenceConfidenceThreshold && heldCadenceSpm >= config.minCadenceSpm,
            latestFilteredAccel = lastFilteredValue,
            filterGroupDelayNs = groupDelayNs
        )
    }

    private fun computeAutocorrelationCadence(nowNs: Long, position: PhonePosition) {
        val stepCutoffNs = nowNs - 5_000_000_000L
        while (stepTimestampsNs.isNotEmpty() && stepTimestampsNs.first() < stepCutoffNs) {
            stepTimestampsNs.removeFirst()
        }
        lastStepDetectorSpm = computeStepDetectorSpm(nowNs)

        val n = windowSamples.size
        if (n < (sampleRateHz * 1.6f).toInt()) {
            lastConfidence = 0f
            return
        }

        val spanNs = windowSamples.last().timestampNs - windowSamples.first().timestampNs
        if (spanNs <= 1_000_000_000L) {
            lastConfidence = 0f
            return
        }
        val effectiveFs = ((n - 1).toDouble() * 1_000_000_000.0 / spanNs.toDouble()).toFloat()
            .coerceIn(50f, 250f)

        val x = FloatArray(n)
        var mean = 0f
        var idx = 0
        for (s in windowSamples) {
            x[idx++] = s.value
            mean += s.value
        }
        mean /= n.toFloat()

        var sumSq = 0f
        val recentCount = (effectiveFs * 1.2f).toInt().coerceIn(16, n)
        val recentStart = n - recentCount
        var recentSumSq = 0f

        for (i in 0 until n) {
            x[i] -= mean
            val sq = x[i] * x[i]
            sumSq += sq
            if (i >= recentStart) {
                recentSumSq += sq
            }
        }
        val rms = sqrt(sumSq / n.toFloat())
        val recentRms = sqrt(recentSumSq / recentCount.toFloat())
        lastDynamicRms = min(rms, recentRms)

        // Se l'energia dinamica complessiva o quella dell'ultimo secondo è sotto soglia,
        // l'utente si è fermato -> confidenza = 0 e BPM bloccato immediatamente (D38)
        if (rms < config.minDynamicAccelRmsMps2 || recentRms < config.minDynamicAccelRmsMps2) {
            lastConfidence = 0f
            return
        }

        val minLag = max(4, (effectiveFs * 60f / config.maxCadenceSpm).roundToInt())
        val maxLag = min(n * 2 / 3, (effectiveFs * 60f / config.minCadenceSpm).roundToInt())
        val maxSearchLag = min(n - 8, (maxLag * 2).coerceAtLeast(maxLag + 2))

        if (maxLag <= minLag + 2) {
            lastConfidence = 0f
            return
        }

        val r = FloatArray(maxSearchLag + 2)
        for (lag in (minLag / 2).coerceAtLeast(2)..maxSearchLag) {
            var num = 0.0
            var denA = 0.0
            var denB = 0.0
            val limit = n - lag
            for (i in 0 until limit) {
                val a = x[i].toDouble()
                val b = x[i + lag].toDouble()
                num += a * b
                denA += a * a
                denB += b * b
            }
            val den = sqrt(denA * denB)
            r[lag] = if (den > 1e-9) (num / den).toFloat() else 0f
        }

        // Raccogli tutti i massimi locali significativi nel range fisiologico [minLag..maxLag]
        data class Peak(val lag: Int, val value: Float)
        val peaks = ArrayList<Peak>()
        for (lag in minLag..maxLag) {
            if (r[lag] > r[lag - 1] && r[lag] >= r[lag + 1] && r[lag] > 0.12f) {
                peaks.add(Peak(lag, r[lag]))
            }
        }

        if (peaks.isEmpty()) {
            lastConfidence = 0f
            return
        }

        val globalMaxPeak = peaks.maxBy { it.value }
        // McLeod / ACF Fundamental Rule: scegli il PRIMO picco (periodo più breve L0) che raggiunge
        // almeno l'85% del picco massimo globale, evitando di scegliere i multipli interi 2*L0 o 3*L0!
        val firstStrongPeak = peaks.firstOrNull { it.value >= globalMaxPeak.value * 0.85f } ?: globalMaxPeak
        var chosenLag = firstStrongPeak.lag
        var chosenPeakVal = firstStrongPeak.value
        var switchedToDoubleHarmonic = false

        // Scelta dell'armonica giusta (D38):
        // Caso A: In TASCA o MANO un singolo arto oscilla a frequenza di falcata (metà della cadenza dei passi).
        // Per un segnale composto da falcata (f_stride) + passo (f_step = 2 * f_stride):
        //   R(T_stride)   = +E_stride + E_step
        //   R(T_stride/2) = -E_stride + E_step (massimo locale, anche se abbassato da -E_stride!)
        //   Somma: R(T_stride) + R(T_stride/2) = 2 * E_step > 0!
        val candidateSpm = (60f * effectiveFs) / chosenLag.toFloat()
        val halfLag = (chosenLag / 2.0f).roundToInt()
        if (halfLag >= minLag && candidateSpm < 115f) {
            val refinedHalfLag = refineLocalPeakOrInflection(r, halfLag, minLag, maxLag)
            val halfVal = r[refinedHalfLag]
            val quarterLag = (chosenLag / 4.0f).roundToInt().coerceAtLeast(2)
            val prominenceOverQuarter = halfVal - r[quarterLag]
            val harmonicEnergySum = chosenPeakVal + halfVal // = 2 * E_step
            val doubleSpm = (60f * effectiveFs) / refinedHalfLag.toFloat()

            if (doubleSpm <= config.maxCadenceSpm) {
                val stepDetPrefersDouble = lastStepDetectorSpm > 0f &&
                        abs(doubleSpm - lastStepDetectorSpm) < abs(candidateSpm - lastStepDetectorSpm)
                val asymmetricPosition = (position == PhonePosition.POCKET || position == PhonePosition.HAND || position == PhonePosition.WRIST)
                val hasStepHarmonic = harmonicEnergySum > 0.20f && prominenceOverQuarter > 0.12f

                if (stepDetPrefersDouble || ((asymmetricPosition || rms > 2.2f) && hasStepHarmonic)) {
                    chosenLag = refinedHalfLag
                    chosenPeakVal = maxOf(chosenPeakVal * 0.85f, halfVal)
                    switchedToDoubleHarmonic = true
                }
            }
        }

        // Caso B: Se firstStrongPeak è caduto sulla 2a armonica durante una camminata lenta
        // (es. 190..220 SPM ma il picco fondamentale a 2*chosenLag è più alto e l'energia è da camminata)
        val doubleLag = chosenLag * 2
        if (!switchedToDoubleHarmonic && doubleLag <= maxLag) {
            val refinedDouble = refineLocalPeakOrInflection(r, doubleLag, minLag, maxLag)
            val doubleVal = r[refinedDouble]
            val halfSpm = (60f * effectiveFs) / refinedDouble.toFloat()
            if (halfSpm >= config.minCadenceSpm) {
                val stepDetPrefersHalf = lastStepDetectorSpm > 0f &&
                        abs(halfSpm - lastStepDetectorSpm) + 8f < abs(candidateSpm - lastStepDetectorSpm)
                val lowWalkEnergy = rms < 1.65f && candidateSpm > 155f && doubleVal > chosenPeakVal * 1.03f
                if (stepDetPrefersHalf || lowWalkEnergy) {
                    chosenLag = refinedDouble
                    chosenPeakVal = doubleVal
                }
            }
        }

        val fractionalLag = parabolicInterpolateLag(r, chosenLag)
        var rawSpm = ((60f * effectiveFs) / fractionalLag).coerceIn(config.minCadenceSpm, config.maxCadenceSpm)

        // Guardia anti-dimezzamento (Fase 1B / A10): in HAND/POCKET/WRIST evita crolli improvvisi sulla sub-armonica
        // (es. ~68 BPM di oscillazione del braccio/falcata rispetto a ~110-136 SPM reali)
        val isAsymmetric = (position == PhonePosition.HAND || position == PhonePosition.POCKET || position == PhonePosition.WRIST)
        if (isAsymmetric && heldCadenceSpm >= 90f && rawSpm < heldCadenceSpm * 0.62f) {
            val doubled = rawSpm * 2f
            if (doubled <= config.maxCadenceSpm &&
                (lastStepDetectorSpm <= 0f || abs(doubled - lastStepDetectorSpm) <= abs(rawSpm - lastStepDetectorSpm))
            ) {
                rawSpm = doubled
            } else {
                // Scarta il salto sub-armonico spurio mantenendo la cadenza agganciata
                lastConfidence = 0f
                return
            }
        }

        val energyFactor = ((lastDynamicRms - config.minDynamicAccelRmsMps2) / 1.1f).coerceIn(0.45f, 1.0f)
        val stepAgreementBonus = if (lastStepDetectorSpm > 0f && abs(rawSpm - lastStepDetectorSpm) <= 8f) 0.14f else 0f
        val confidence = (chosenPeakVal * energyFactor + stepAgreementBonus).coerceIn(0f, 1f)
        lastConfidence = confidence

        // Regola D38: Sotto la soglia di confidenza il BPM resta fermo (nessun inseguimento del rumore)
        if (confidence >= config.cadenceConfidenceThreshold) {
            heldCadenceSpm = if (heldCadenceSpm <= 0f) {
                rawSpm
            } else {
                val alpha = config.cadenceSmoothingAlpha.coerceIn(0.1f, 1.0f)
                (1f - alpha) * heldCadenceSpm + alpha * rawSpm
            }
        } else if (lastStepDetectorSpm >= config.minCadenceSpm && lastDynamicRms >= config.minDynamicAccelRmsMps2) {
            // Fallback su TYPE_STEP_DETECTOR quando l'utente cammina piano e l'ACF è ancora indecisa
            heldCadenceSpm = if (heldCadenceSpm <= 0f) {
                lastStepDetectorSpm
            } else {
                0.5f * heldCadenceSpm + 0.5f * lastStepDetectorSpm
            }
            lastConfidence = config.cadenceConfidenceThreshold
        }
    }

    private fun refineLocalPeakOrInflection(r: FloatArray, centerLag: Int, minLag: Int, maxLag: Int): Int {
        var best = centerLag.coerceIn(minLag, maxLag)
        val from = (centerLag - 2).coerceAtLeast(minLag)
        val to = (centerLag + 2).coerceAtMost(maxLag)
        for (k in from..to) {
            if (r[k] > r[best]) {
                best = k
            }
        }
        return best
    }

    private fun parabolicInterpolateLag(r: FloatArray, lag: Int): Float {
        if (lag <= 1 || lag >= r.size - 1) return lag.toFloat()
        val y1 = r[lag - 1]
        val y2 = r[lag]
        val y3 = r[lag + 1]
        val denom = 2f * (y1 - 2f * y2 + y3)
        if (abs(denom) < 1e-6f) return lag.toFloat()
        val delta = ((y1 - y3) / denom).coerceIn(-0.5f, 0.5f)
        return lag.toFloat() + delta
    }

    private fun computeStepDetectorSpm(nowNs: Long): Float {
        if (stepTimestampsNs.size < 3) return 0f
        val lastStepNs = stepTimestampsNs.last()
        if (nowNs - lastStepNs > 2_500_000_000L) return 0f

        val intervalsNs = ArrayList<Long>(stepTimestampsNs.size - 1)
        var prev = -1L
        for (ts in stepTimestampsNs) {
            if (prev > 0L) {
                val dt = ts - prev
                if (dt in 220_000_000L..1_200_000_000L) {
                    intervalsNs.add(dt)
                }
            }
            prev = ts
        }
        if (intervalsNs.size < 2) return 0f
        intervalsNs.sort()
        val medianDtNs = intervalsNs[intervalsNs.size / 2].toFloat()
        return (60_000_000_000f / medianDtNs).coerceIn(config.minCadenceSpm, config.maxCadenceSpm)
    }

    private class BiquadFilter(
        private val b0: Float,
        private val b1: Float,
        private val b2: Float,
        private val a1: Float,
        private val a2: Float
    ) {
        private var x1 = 0f
        private var x2 = 0f
        private var y1 = 0f
        private var y2 = 0f
        private var initialized = false

        fun process(x0: Float): Float {
            if (!initialized) {
                x1 = x0
                x2 = x0
                y1 = 0f
                y2 = 0f
                initialized = true
            }
            val y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x0
            y2 = y1
            y1 = y0
            return y0
        }

        companion object {
            fun highPass(sampleRateHz: Float, cutoffHz: Float): BiquadFilter {
                val fs = sampleRateHz.coerceAtLeast(20f).toDouble()
                val fc = cutoffHz.toDouble().coerceIn(0.1, fs * 0.45)
                val w0 = 2.0 * PI * fc / fs
                val cosW0 = cos(w0)
                val sinW0 = sin(w0)
                val alpha = sinW0 / sqrt(2.0)

                val a0 = 1.0 + alpha
                val b0 = ((1.0 + cosW0) / 2.0) / a0
                val b1 = -(1.0 + cosW0) / a0
                val b2 = ((1.0 + cosW0) / 2.0) / a0
                val a1 = (-2.0 * cosW0) / a0
                val a2 = (1.0 - alpha) / a0
                return BiquadFilter(b0.toFloat(), b1.toFloat(), b2.toFloat(), a1.toFloat(), a2.toFloat())
            }

            fun lowPass(sampleRateHz: Float, cutoffHz: Float): BiquadFilter {
                val fs = sampleRateHz.coerceAtLeast(20f).toDouble()
                val fc = cutoffHz.toDouble().coerceIn(0.5, fs * 0.45)
                val w0 = 2.0 * PI * fc / fs
                val cosW0 = cos(w0)
                val sinW0 = sin(w0)
                val alpha = sinW0 / sqrt(2.0)

                val a0 = 1.0 + alpha
                val b0 = ((1.0 - cosW0) / 2.0) / a0
                val b1 = (1.0 - cosW0) / a0
                val b2 = ((1.0 - cosW0) / 2.0) / a0
                val a1 = (-2.0 * cosW0) / a0
                val a2 = (1.0 - alpha) / a0
                return BiquadFilter(b0.toFloat(), b1.toFloat(), b2.toFloat(), a1.toFloat(), a2.toFloat())
            }

            /**
             * Calcola il ritardo di gruppo analitico (in nanosecondi) della cascata passa-banda
             * (`highPass` + `lowPass`) alla frequenza `frequencyHz` (cadenza in passi/secondo).
             */
            fun computeBandpassGroupDelayNs(
                sampleRateHz: Float,
                hpCutoffHz: Float,
                lpCutoffHz: Float,
                frequencyHz: Float
            ): Long {
                val fs = sampleRateHz.coerceAtLeast(20f).toDouble()
                val f = frequencyHz.toDouble().coerceIn(0.2, fs * 0.40)
                val hp = highPass(fs.toFloat(), hpCutoffHz)
                val lp = lowPass(fs.toFloat(), lpCutoffHz)
                val df = 0.01
                val p1 = totalPhaseRad(hp, lp, (f - df).coerceAtLeast(0.05), fs)
                val p2 = totalPhaseRad(hp, lp, (f + df).coerceAtMost(fs * 0.45), fs)
                var dp = p2 - p1
                while (dp > PI) dp -= 2.0 * PI
                while (dp < -PI) dp += 2.0 * PI
                val gdSec = (-dp / (2.0 * PI * 2.0 * df)).coerceIn(0.0, 0.5)
                return (gdSec * 1_000_000_000.0).toLong()
            }

            private fun totalPhaseRad(hp: BiquadFilter, lp: BiquadFilter, f: Double, fs: Double): Double {
                val w = 2.0 * PI * f / fs
                val cw = cos(w)
                val sw = -sin(w)
                val c2w = cos(2.0 * w)
                val s2w = -sin(2.0 * w)
                fun biquadPhase(flt: BiquadFilter): Double {
                    val nr = flt.b0 + flt.b1 * cw + flt.b2 * c2w
                    val ni = flt.b1 * sw + flt.b2 * s2w
                    val dr = 1.0 + flt.a1 * cw + flt.a2 * c2w
                    val di = flt.a1 * sw + flt.a2 * s2w
                    return kotlin.math.atan2(ni, nr) - kotlin.math.atan2(di, dr)
                }
                return biquadPhase(hp) + biquadPhase(lp)
            }
        }
    }
}
