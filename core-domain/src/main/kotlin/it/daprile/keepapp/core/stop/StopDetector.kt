/*
 * Copyright 2026 Adriano D'Aprile
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package it.daprile.keepapp.core.stop

import it.daprile.keepapp.core.config.KeepAppConfig
import kotlin.math.pow

/**
 * Fotografia istantanea del rilevatore di soste (`StopDetector` — D42).
 */
data class StopDetectorSnapshot(
    /** True se il soggetto è attualmente considerato in sosta */
    val isStopped: Boolean,
    /** Attenuazione corrente del ritmo in dB (0,0 dB in corsa, scende a -20,0 dB in ~1 s durante la sosta) */
    val currentGainDb: Float,
    /** Guadagno lineare corrispondente in [0.1, 1.0] per l'AudioEngine */
    val currentGainLinear: Float,
    /** Durata della sosta corrente (o appena conclusa) in secondi */
    val currentStopDurationSeconds: Float,
    /** Passi consecutivi contati durante la sosta verso la soglia di ripresa (4 passi) */
    val resumeStepsCounted: Int,
    /** Numero totale di soste rilevate dall'inizio della sessione */
    val totalStopsCount: Int,
    /** True nell'istante in cui il soggetto riparte dopo una sosta >= 2 minuti (richiede nuovo aggancio ancora D42/D22) */
    val justResumedRequiringAnchorRelock: Boolean = false
)

/**
 * Rilevatore di soste e gestore dell'attenuazione del volume (`StopDetector` — D42):
 * - **Condizione di sosta:** velocità GPS $< 0{,}5\text{ m/s}$ (o assenza di velocità GPS alta)
 *   **E** nessun passo per più di $3{,}0\text{ s}$.
 * - **In sosta:** il volume del ritmo scende a $-20\text{ dB}$ con una rampa morbida di $\sim 1\text{ s}$.
 * - **Ripresa:** dopo $4$ passi consecutivi il soggetto esce dalla sosta e il volume torna a $0\text{ dB}$;
 *   se la sosta ha superato $\sim 2\text{ min}$ ($120\text{ s}$), segnala il ri-aggancio dell'ancora elastica.
 */
class StopDetector(
    private val config: KeepAppConfig = KeepAppConfig()
) {
    private var lastStepTimestampNs: Long = 0L
    private var lastGpsSpeedMps: Float = 0f
    private var lastGpsUpdateNs: Long = 0L

    private var isStopped: Boolean = false
    private var stopStartedNs: Long = 0L
    private var lastEvalNs: Long = 0L

    private var currentGainDb: Float = 0f
    private var resumeConsecutiveSteps: Int = 0
    private var lastResumeStepNs: Long = 0L
    private var totalStopsCount: Int = 0

    /**
     * Registra la velocità GPS aggiornata.
     */
    fun onGpsSpeed(timestampNs: Long, speedMps: Float) {
        lastGpsUpdateNs = timestampNs
        lastGpsSpeedMps = speedMps.coerceAtLeast(0f)
    }

    /**
     * Registra un passo rilevato (da `TYPE_STEP_DETECTOR` o da un picco d'impatto ad alta confidenza).
     */
    fun onStepEvent(timestampNs: Long) {
        if (!isStopped) {
            lastStepTimestampNs = timestampNs
            resumeConsecutiveSteps = 0
        } else {
            // Se siamo in sosta, contiamo i passi consecutivi (distanziati < 2,0 s l'uno dall'altro)
            if (lastResumeStepNs == 0L || (timestampNs - lastResumeStepNs) <= 2_000_000_000L) {
                resumeConsecutiveSteps++
            } else {
                resumeConsecutiveSteps = 1
            }
            lastResumeStepNs = timestampNs
            lastStepTimestampNs = timestampNs
        }
    }

    /**
     * Valuta lo stato di sosta e aggiorna la rampa di volume a $-20\text{ dB}$ in $\sim 1\text{ s}$.
     */
    fun evaluate(timestampNs: Long): StopDetectorSnapshot {
        if (lastStepTimestampNs == 0L) {
            lastStepTimestampNs = timestampNs
        }
        val dtSec = if (lastEvalNs > 0L && timestampNs > lastEvalNs) {
            (timestampNs - lastEvalNs) / 1_000_000_000f
        } else {
            0f
        }
        lastEvalNs = timestampNs

        var justResumedWithRelock = false

        if (!isStopped) {
            val noStepSec = (timestampNs - lastStepTimestampNs) / 1_000_000_000f
            // Se il GPS non ha fix recenti (> 10 s), ci basiamo sull'assenza di passi; altrimenti chiediamo GPS < 0,5 m/s
            val gpsRecent = lastGpsUpdateNs > 0L && (timestampNs - lastGpsUpdateNs) <= 10_000_000_000L
            val gpsSaysStopped = !gpsRecent || (lastGpsSpeedMps < config.stopMaxGpsSpeedMps)

            if (gpsSaysStopped && noStepSec > config.stopNoStepTimeoutSeconds) {
                isStopped = true
                stopStartedNs = timestampNs
                resumeConsecutiveSteps = 0
                lastResumeStepNs = 0L
                totalStopsCount++
            }
        } else {
            // Verifica ripresa dopo 4 passi consecutivi (D42)
            if (resumeConsecutiveSteps >= config.stopResumeConsecutiveSteps) {
                val stopDurationSec = (timestampNs - stopStartedNs) / 1_000_000_000f
                if (stopDurationSec >= config.stopRelockThresholdSeconds) {
                    justResumedWithRelock = true
                }
                isStopped = false
                stopStartedNs = 0L
                resumeConsecutiveSteps = 0
                lastResumeStepNs = 0L
            }
        }

        // Aggiornamento rampa di guadagno in dB (0 dB <-> -20 dB in ~1 s)
        val rampRateDbPerSec = kotlin.math.abs(config.stopAttenuationDb) / config.stopVolumeRampSeconds.coerceAtLeast(0.1f)
        currentGainDb = if (isStopped) {
            (currentGainDb - rampRateDbPerSec * dtSec).coerceAtLeast(config.stopAttenuationDb)
        } else {
            (currentGainDb + rampRateDbPerSec * dtSec).coerceAtMost(0.0f)
        }

        val gainLinear = 10.0f.pow(currentGainDb / 20.0f).coerceIn(0.0f, 1.0f)
        val stopDurationSec = if (isStopped && stopStartedNs > 0L) {
            (timestampNs - stopStartedNs) / 1_000_000_000f
        } else {
            0f
        }

        return StopDetectorSnapshot(
            isStopped = isStopped,
            currentGainDb = currentGainDb,
            currentGainLinear = gainLinear,
            currentStopDurationSeconds = stopDurationSec,
            resumeStepsCounted = resumeConsecutiveSteps,
            totalStopsCount = totalStopsCount,
            justResumedRequiringAnchorRelock = justResumedWithRelock
        )
    }
}
