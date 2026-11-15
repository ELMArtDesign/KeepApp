/*
 * Copyright 2026 Adriano D'Aprile
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package it.daprile.keepapp.core.anchor

import it.daprile.keepapp.core.config.KeepAppConfig
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Stati operativi dell'Ancora Elastica (D40).
 */
enum class AnchorState {
    /** Fase iniziale (primi 20–30 s stabili): raccolta campioni per calcolare la mediana di aggancio */
    WARMING_UP,

    /** Ancora agganciata: il runner mantiene la cadenza entro la tolleranza del 3% */
    LOCKED,

    /** Rallentamento breve (< ~20 s, oltre il 3%): rinforzo ritmico attivo, BPM obiettivo invariato */
    REINFORCING,

    /** Rallentamento prolungato (> ~60 s): il BPM obiettivo scende gradualmente (max ~1 BPM ogni 10 s) */
    ADAPTING_DOWN
}

/**
 * Codici evento diagnostici dell'Ancora Elastica per `sensors.bin` (D45) e per il Replay Harness.
 */
enum class AnchorEventCode(val code: Short) {
    NONE(0),
    LOCKED_INITIAL(1),
    REINFORCEMENT_STARTED(2),
    REINFORCEMENT_ENDED(3),
    ADAPTING_DOWN_STARTED(4),
    RESET_FOR_RELOCK(5)
}

/**
 * Fotografia istantanea dello stato dell'Ancora Elastica (D40).
 */
data class ElasticAnchorSnapshot(
    val state: AnchorState,
    /** BPM mediano agganciato al termine dei primi 20–30 s stabili (0 se ancora in WARMING_UP) */
    val initialLockedBpm: Float,
    /** BPM obiettivo attuale per il BeatScheduler (segue il runner in WARMING_UP, poi applica l'ancora) */
    val targetBpm: Float,
    /** True quando è richiesto il livello/strato di rinforzo ritmico (D40 / D41) */
    val reinforcementActive: Boolean,
    /** Durata continua (in secondi) dell'attuale rallentamento oltre il 3% */
    val dropDurationSeconds: Float,
    /** Ultimo evento di transizione verificatosi in questo ciclo */
    val event: AnchorEventCode = AnchorEventCode.NONE
)

/**
 * Algoritmo dell'Ancora Elastica (`ElasticAnchor` — D40):
 * - **Aggancio:** mediana della cadenza nei primi 20–30 s stabili (`anchorLockStableSeconds`).
 * - **Rallentamento breve (< ~20 s, oltre il 3%):** attiva il rinforzo ritmico (accento o strato di
 *   percussione in più), mantenendo invariato il BPM obiettivo.
 * - **Rallentamento prolungato (> ~60 s):** il BPM obiettivo scende gradualmente (massimo ~1 BPM ogni 10 s)
 *   verso la cadenza reale del runner.
 * - **Limiti:** $\pm 15\%$ dal valore di aggancio iniziale (`initialLockedBpm`).
 * - **Salite:** escluse dalla Fase 1A (verranno integrate in Fase 2 con pendenze Copernicus).
 */
class ElasticAnchor(
    private val config: KeepAppConfig = KeepAppConfig()
) {
    private data class TimedCadence(val timestampNs: Long, val spm: Float)

    private val warmupSamples = ArrayDeque<TimedCadence>(128)

    private var state: AnchorState = AnchorState.WARMING_UP
    private var initialLockedBpm: Float = 0f
    private var currentTargetBpm: Float = 0f

    private var dropStartTimestampNs: Long = 0L
    private var lastUpdateTimestampNs: Long = 0L

    /**
     * Riporta l'ancora nello stato `WARMING_UP` per effettuare un nuovo aggancio
     * (chiamato ad esempio dopo una sosta superiore a ~2 minuti — D42 / D22).
     */
    fun resetForRelock(nowNs: Long = 0L): ElasticAnchorSnapshot {
        warmupSamples.clear()
        state = AnchorState.WARMING_UP
        initialLockedBpm = 0f
        dropStartTimestampNs = 0L
        lastUpdateTimestampNs = nowNs
        return ElasticAnchorSnapshot(
            state = state,
            initialLockedBpm = 0f,
            targetBpm = currentTargetBpm,
            reinforcementActive = false,
            dropDurationSeconds = 0f,
            event = AnchorEventCode.RESET_FOR_RELOCK
        )
    }

    /**
     * Aggiorna lo stato dell'Ancora Elastica con la nuova stima di cadenza.
     */
    fun update(
        timestampNs: Long,
        cadenceSpm: Float,
        cadenceConfidence: Float,
        isStopped: Boolean
    ): ElasticAnchorSnapshot {
        val dtSec = if (lastUpdateTimestampNs > 0L && timestampNs > lastUpdateTimestampNs) {
            (timestampNs - lastUpdateTimestampNs) / 1_000_000_000f
        } else {
            0f
        }
        lastUpdateTimestampNs = timestampNs

        // Durante una sosta o se la confidenza è sotto soglia, congeliamo i timer dell'ancora
        if (isStopped || cadenceConfidence < config.cadenceConfidenceThreshold || cadenceSpm < config.minCadenceSpm) {
            if (dropStartTimestampNs > 0L && dtSec > 0f) {
                // Non facciamo avanzare il timer di rallentamento mentre il soggetto è fermo al semaforo
                dropStartTimestampNs += (dtSec * 1_000_000_000f).toLong()
            }
            return currentSnapshot(timestampNs, AnchorEventCode.NONE)
        }

        // FASE 1: WARMING_UP -> raccolta dei primi 20–30 s stabili per calcolare la mediana
        if (state == AnchorState.WARMING_UP) {
            currentTargetBpm = cadenceSpm
            warmupSamples.addLast(TimedCadence(timestampNs, cadenceSpm))

            val windowNs = (config.anchorLockStableSeconds * 1_000_000_000f).toLong()
            val cutoffNs = timestampNs - windowNs
            while (warmupSamples.isNotEmpty() && warmupSamples.first().timestampNs < cutoffNs - 2_000_000_000L) {
                warmupSamples.removeFirst()
            }

            val spanNs = if (warmupSamples.size >= 2) {
                warmupSamples.last().timestampNs - warmupSamples.first().timestampNs
            } else {
                0L
            }

            if (spanNs >= windowNs * 95L / 100L && warmupSamples.size >= 10) {
                val values = warmupSamples.map { it.spm }
                val std = computeStdDev(values)
                if (std <= config.anchorLockMaxStdSpm) {
                    val medianBpm = computeMedian(values)
                    initialLockedBpm = medianBpm
                    currentTargetBpm = medianBpm
                    state = AnchorState.LOCKED
                    dropStartTimestampNs = 0L
                    return currentSnapshot(timestampNs, AnchorEventCode.LOCKED_INITIAL)
                }
            }
            return currentSnapshot(timestampNs, AnchorEventCode.NONE)
        }

        // FASE 2: ANCORA AGGANCIATA (LOCKED / REINFORCING / ADAPTING_DOWN)
        val minAllowedBpm = (initialLockedBpm * (1f - config.anchorMaxDeviationFraction))
            .coerceAtLeast(config.minCadenceSpm)
        val maxAllowedBpm = (initialLockedBpm * (1f + config.anchorMaxDeviationFraction))
            .coerceAtMost(config.maxCadenceSpm)

        val dropThresholdBpm = currentTargetBpm * (1f - config.anchorDropThresholdFraction)
        val isBelowThreshold = cadenceSpm < dropThresholdBpm

        var event = AnchorEventCode.NONE

        if (isBelowThreshold) {
            if (dropStartTimestampNs == 0L) {
                dropStartTimestampNs = timestampNs
            }
            val dropSec = (timestampNs - dropStartTimestampNs) / 1_000_000_000f

            if (dropSec <= config.anchorShortDropMaxSeconds) {
                // Rallentamento breve (< ~20 s, oltre il 3%): rinforzo ritmico attivo, obiettivo invariato
                if (state != AnchorState.REINFORCING) {
                    state = AnchorState.REINFORCING
                    event = AnchorEventCode.REINFORCEMENT_STARTED
                }
            } else if (dropSec < config.anchorProlongedDropSeconds) {
                // Tra 20 s e 60 s: mantiene il rinforzo ritmico in attesa di capire se il calo è definitivo
                if (state != AnchorState.REINFORCING) {
                    state = AnchorState.REINFORCING
                }
            } else {
                // Rallentamento prolungato (> ~60 s): il BPM obiettivo scende gradualmente (max 1 BPM ogni 10 s)
                if (state != AnchorState.ADAPTING_DOWN) {
                    state = AnchorState.ADAPTING_DOWN
                    event = AnchorEventCode.ADAPTING_DOWN_STARTED
                }
                val maxStepDown = (config.anchorMaxDecayBpmPer10s / 10.0f) * dtSec.coerceAtLeast(0f)
                val desiredTarget = max(cadenceSpm, minAllowedBpm)
                currentTargetBpm = max(desiredTarget, currentTargetBpm - maxStepDown)
            }
        } else {
            // Il runner è tornato sopra la soglia (-3% dal targetBpm)
            if (state == AnchorState.REINFORCING || state == AnchorState.ADAPTING_DOWN) {
                event = AnchorEventCode.REINFORCEMENT_ENDED
            }
            state = AnchorState.LOCKED
            dropStartTimestampNs = 0L

            // Se il runner accelera stabilmente sopra il target (ma entro +15%), seguiamo dolcemente verso l'alto
            if (cadenceSpm > currentTargetBpm * (1f + config.anchorDropThresholdFraction)) {
                val maxStepUp = (config.anchorMaxDecayBpmPer10s / 10.0f) * dtSec.coerceAtLeast(0f)
                val desiredUp = min(cadenceSpm, maxAllowedBpm)
                currentTargetBpm = min(desiredUp, currentTargetBpm + maxStepUp)
            }
        }

        currentTargetBpm = currentTargetBpm.coerceIn(minAllowedBpm, maxAllowedBpm)
        return currentSnapshot(timestampNs, event)
    }

    fun currentSnapshot(nowNs: Long = lastUpdateTimestampNs, event: AnchorEventCode = AnchorEventCode.NONE): ElasticAnchorSnapshot {
        val dropSec = if (dropStartTimestampNs > 0L && nowNs >= dropStartTimestampNs) {
            (nowNs - dropStartTimestampNs) / 1_000_000_000f
        } else {
            0f
        }
        return ElasticAnchorSnapshot(
            state = state,
            initialLockedBpm = initialLockedBpm,
            targetBpm = currentTargetBpm,
            reinforcementActive = (state == AnchorState.REINFORCING),
            dropDurationSeconds = dropSec,
            event = event
        )
    }

    private fun computeMedian(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[mid]
        } else {
            0.5f * (sorted[mid - 1] + sorted[mid])
        }
    }

    private fun computeStdDev(values: List<Float>): Float {
        if (values.size < 2) return 0f
        val mean = values.average().toFloat()
        var sumSq = 0f
        for (v in values) {
            val d = v - mean
            sumSq += d * d
        }
        return sqrt(sumSq / values.size.toFloat())
    }
}
