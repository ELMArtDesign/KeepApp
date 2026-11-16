/*
 * Copyright 2026 Adriano D'Aprile
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package it.daprile.keepapp.core.phase

import it.daprile.keepapp.core.config.KeepAppConfig
import it.daprile.keepapp.core.format.PhonePosition
import kotlin.math.abs

/**
 * Stato istantaneo della fase del passo e previsione del prossimo appoggio del piede (D39).
 */
data class PhaseState(
    /** Istante stimato dell'ultimo appoggio del piede rilevato (in nanosecondi monotonici) */
    val lastFootstrikeNs: Long,
    /** Istante previsto del prossimo appoggio del piede (in nanosecondi monotonici) */
    val predictedNextFootstrikeNs: Long,
    /** Confidenza della stima di fase in [0, 1] */
    val phaseConfidence: Float,
    /** Offset di fase applicato per la posizione corrente (in ms) */
    val positionOffsetMs: Float,
    /** Numero totale di picchi d'impatto rilevati dall'accelerometro */
    val totalImpactsDetected: Long,
    /** True se nell'ultimo campione è stato rilevato un nuovo appoggio del piede */
    val justDetectedImpact: Boolean = false
)

/**
 * Stimatore della fase del passo e del prossimo appoggio del piede (`PhaseTracker` — D39).
 *
 * Rileva i picchi d'impatto sul segnale accelerometrico filtrato $|a|_{\text{bp}}$,
 * ne verifica la coerenza di fase rispetto al periodo corrente $T = 60 / \text{SPM}$ e
 * prevede l'istante del prossimo appoggio applicando l'offset di fase della posizione.
 */
class PhaseTracker(
    private val config: KeepAppConfig = KeepAppConfig()
) {
    private var prevPrevVal = 0f
    private var prevVal = 0f
    private var prevTimestampNs = 0L

    private var runningEnvelope = 0.8f
    private var lastImpactNs = 0L
    private var smoothedAnchorPhaseNs = 0L
    private var phaseConfidence = 0f
    private var totalImpacts = 0L

    private val recentPhaseErrorsMs = ArrayDeque<Float>(8)

    /**
     * Aggiorna il tracker di fase con il nuovo campione filtrato passa-banda di $|a|$.
     */
    fun onFilteredAccelSample(
        timestampNs: Long,
        filteredAccel: Float,
        currentCadenceSpm: Float,
        cadenceConfidence: Float,
        position: PhonePosition,
        filterGroupDelayNs: Long = 0L
    ): PhaseState {
        val posOffsetMs = config.phaseOffsetMsFor(position)
        val posOffsetNs = (posOffsetMs * 1_000_000f).toLong()

        val absVal = abs(filteredAccel)
        runningEnvelope = if (absVal > runningEnvelope) {
            0.85f * runningEnvelope + 0.15f * absVal
        } else {
            0.995f * runningEnvelope + 0.005f * absVal
        }

        val periodNs = if (currentCadenceSpm >= config.minCadenceSpm) {
            (60_000_000_000.0 / currentCadenceSpm.toDouble()).toLong()
        } else {
            0L
        }

        val minSpacingNs = if (periodNs > 0L) {
            (periodNs.toDouble() * config.minImpactSpacingPeriodFraction.toDouble()).toLong()
        } else {
            270_000_000L
        }

        val dynamicThreshold = (runningEnvelope * 0.45f).coerceAtLeast(config.minDynamicAccelRmsMps2 * 0.85f)
        var detectedImpactNow = false

        // Rilevamento massimo locale su `prevVal` (al tempo `prevTimestampNs`)
        if (prevTimestampNs > 0L &&
            prevVal > prevPrevVal &&
            prevVal >= filteredAccel &&
            prevVal >= dynamicThreshold &&
            (lastImpactNs == 0L || (prevTimestampNs - lastImpactNs) >= minSpacingNs)
        ) {
            detectedImpactNow = true
            totalImpacts++

            // L'istante dell'appoggio reale precede il picco filtrato per il ritardo di gruppo del filtro causale
            // e per l'offset meccanico della posizione del telefono (D39)
            val estimatedFootstrikeNs = prevTimestampNs - filterGroupDelayNs - posOffsetNs

            if (periodNs > 0L && smoothedAnchorPhaseNs > 0L) {
                // Calcola l'errore di fase rispetto alla griglia periodica prevista [-T/2, +T/2]
                val k = ((estimatedFootstrikeNs - smoothedAnchorPhaseNs).toDouble() / periodNs.toDouble()).let {
                    kotlin.math.round(it).toLong()
                }.coerceAtLeast(1L)
                val predictedThisStrikeNs = smoothedAnchorPhaseNs + k * periodNs
                val phaseErrorNs = estimatedFootstrikeNs - predictedThisStrikeNs
                val phaseErrorMs = phaseErrorNs / 1_000_000f

                if (abs(phaseErrorNs) <= periodNs / 2) {
                    // Aggiorna la griglia di fase con filtro PLL (guadagno 0.35)
                    smoothedAnchorPhaseNs = predictedThisStrikeNs + (phaseErrorNs * 35L) / 100L
                    recentPhaseErrorsMs.addLast(abs(phaseErrorMs))
                    while (recentPhaseErrorsMs.size > 6) {
                        recentPhaseErrorsMs.removeFirst()
                    }
                    val meanAbsErrMs = recentPhaseErrorsMs.average().toFloat()
                    val periodMs = periodNs / 1_000_000f
                    val consistency = (1f - (meanAbsErrMs / (periodMs * 0.25f))).coerceIn(0f, 1f)
                    phaseConfidence = (0.6f * consistency + 0.4f * cadenceConfidence).coerceIn(0f, 1f)
                } else {
                    // Ri-aggancia la fase se c'è stata una discontinuità
                    smoothedAnchorPhaseNs = estimatedFootstrikeNs
                    phaseConfidence *= 0.7f
                }
            } else {
                smoothedAnchorPhaseNs = estimatedFootstrikeNs
                phaseConfidence = (cadenceConfidence * 0.6f).coerceIn(0f, 1f)
            }

            lastImpactNs = estimatedFootstrikeNs
        }

        prevPrevVal = prevVal
        prevVal = filteredAccel
        prevTimestampNs = timestampNs

        // Se non vediamo impatti da più di 2,5 secondi, la confidenza di fase decade
        if (lastImpactNs > 0L && timestampNs - lastImpactNs > 2_500_000_000L) {
            phaseConfidence = 0f
        }

        val nextStrikeNs = if (periodNs > 0L && smoothedAnchorPhaseNs > 0L) {
            var next = smoothedAnchorPhaseNs
            while (next <= timestampNs) {
                next += periodNs
            }
            next
        } else {
            0L
        }

        return PhaseState(
            lastFootstrikeNs = lastImpactNs,
            predictedNextFootstrikeNs = nextStrikeNs,
            phaseConfidence = phaseConfidence,
            positionOffsetMs = posOffsetMs,
            totalImpactsDetected = totalImpacts,
            justDetectedImpact = detectedImpactNow
        )
    }
}
