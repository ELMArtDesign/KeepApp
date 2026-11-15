/*
 * Copyright 2026 Adriano D'Aprile
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package it.daprile.keepapp.core.session

import it.daprile.keepapp.core.anchor.AnchorState
import it.daprile.keepapp.core.anchor.ElasticAnchor
import it.daprile.keepapp.core.anchor.ElasticAnchorSnapshot
import it.daprile.keepapp.core.cadence.CadenceEstimate
import it.daprile.keepapp.core.cadence.CadenceEstimator
import it.daprile.keepapp.core.config.KeepAppConfig
import it.daprile.keepapp.core.format.BinaryFormat
import it.daprile.keepapp.core.format.ParsedBinaryRecord
import it.daprile.keepapp.core.format.PhonePosition
import it.daprile.keepapp.core.phase.PhaseState
import it.daprile.keepapp.core.phase.PhaseTracker
import it.daprile.keepapp.core.position.PositionClassifier
import it.daprile.keepapp.core.position.PositionEstimate
import it.daprile.keepapp.core.stop.StopDetector
import it.daprile.keepapp.core.stop.StopDetectorSnapshot

/**
 * Stato globale della macchina a stati di sessione (`SessionStateMachine` — D37 / D22).
 */
enum class SessionRunState {
    /** Sessione attiva (in movimento o in aggancio) */
    ACTIVE_RUNNING,

    /** In sosta temporanea (semaforo/incrocio: ritmo attenuato a -20 dB) */
    ACTIVE_STOPPED,

    /** In pausa manuale da utente o da 1 tocco cuffia (D44) o interruzione audio (D22) */
    PAUSED
}

/**
 * Fotografia completa prodotta da `SessionStateMachine` a ogni passo di aggiornamento (~0,5 s).
 */
data class SessionFrameSnapshot(
    val timestampNs: Long,
    val elapsedSeconds: Float,
    val runState: SessionRunState,
    val positionEstimate: PositionEstimate,
    val cadenceEstimate: CadenceEstimate,
    val phaseState: PhaseState,
    val anchorSnapshot: ElasticAnchorSnapshot,
    val stopSnapshot: StopDetectorSnapshot,
    val totalStepsDetected: Long
)

/**
 * Macchina a stati centrale di KeepApp in Kotlin puro (`:core-domain` — D37, D22).
 *
 * Coordina:
 * - `PositionClassifier` (D21)
 * - `CadenceEstimator` (D38)
 * - `PhaseTracker` (D39)
 * - `ElasticAnchor` (D40)
 * - `StopDetector` (D42)
 * - Gestione di Pausa/Ripresa (1 tocco cuffia D44 e interruzioni di sistema D22: se la pausa
 *   supera i 2 minuti, al resume esegue un nuovo aggancio dell'ancora elastica).
 */
class SessionStateMachine(
    val config: KeepAppConfig = KeepAppConfig(),
    initialPositionHint: PhonePosition = PhonePosition.ARM
) {
    val positionClassifier = PositionClassifier(config, initialPositionHint)
    val cadenceEstimator = CadenceEstimator(config)
    val phaseTracker = PhaseTracker(config)
    val elasticAnchor = ElasticAnchor(config)
    val stopDetector = StopDetector(config)

    private var sessionStartNs: Long = 0L
    private var lastFrameEmitNs: Long = 0L
    private var isPaused: Boolean = false
    private var pausedAtNs: Long = 0L
    private var totalStepsCount: Long = 0L
    private var lastHardwareStepNs: Long = 0L

    private var latestPosition = PositionEstimate(initialPositionHint, 0.5f, 0f, 0f)
    private var latestCadence = CadenceEstimate(0f, 0f, 0f, 0f, false, 0f)
    private var latestPhase = PhaseState(0L, 0L, 0f, config.phaseOffsetMsFor(initialPositionHint), 0L, false)
    private var latestAnchor = ElasticAnchorSnapshot(AnchorState.WARMING_UP, 0f, 0f, false, 0f)
    private var latestStop = StopDetectorSnapshot(false, 0f, 1.0f, 0f, 0, 0, false)

    /**
     * Commuta tra Pausa e Ripresa (es. 1 tocco sul tasto della cuffia Bluetooth — D44,
     * oppure chiamata telefonica / perdita temporanea dell'audio focus — D22).
     */
    fun togglePauseResume(timestampNs: Long): Boolean {
        return if (!isPaused) {
            pause(timestampNs)
            true
        } else {
            resume(timestampNs)
            false
        }
    }

    fun pause(timestampNs: Long) {
        if (isPaused) return
        isPaused = true
        pausedAtNs = timestampNs
    }

    fun resume(timestampNs: Long) {
        if (!isPaused) return
        isPaused = false
        val pauseDurationSec = if (pausedAtNs > 0L && timestampNs > pausedAtNs) {
            (timestampNs - pausedAtNs) / 1_000_000_000f
        } else {
            0f
        }
        pausedAtNs = 0L
        // D22: se l'interruzione o la pausa supera ~2 minuti (120 s), nuovo aggancio dell'ancora
        if (pauseDurationSec >= config.stopRelockThresholdSeconds) {
            latestAnchor = elasticAnchor.resetForRelock(timestampNs)
        }
    }

    fun onStepDetectorEvent(timestampNs: Long) {
        ensureStartTimestamp(timestampNs)
        totalStepsCount++
        lastHardwareStepNs = timestampNs
        cadenceEstimator.onStepDetected(timestampNs)
        stopDetector.onStepEvent(timestampNs)
    }

    fun onGpsFix(timestampNs: Long, speedMps: Float) {
        ensureStartTimestamp(timestampNs)
        stopDetector.onGpsSpeed(timestampNs, speedMps)
    }

    fun onGyroscope(timestampNs: Long, gx: Float, gy: Float, gz: Float) {
        ensureStartTimestamp(timestampNs)
        positionClassifier.onGyroscope(timestampNs, gx, gy, gz)
    }

    /**
     * Ingerisce un campione dell'accelerometro. Se è trascorso l'intervallo di aggiornamento
     * (`cadenceUpdateIntervalSeconds` = 0,5 s), restituisce il nuovo `SessionFrameSnapshot` completo.
     */
    fun onAccelerometer(timestampNs: Long, ax: Float, ay: Float, az: Float): SessionFrameSnapshot? {
        ensureStartTimestamp(timestampNs)
        positionClassifier.onAccelerometer(timestampNs, ax, ay, az)

        val prevCadenceTimeNs = lastFrameEmitNs
        latestCadence = cadenceEstimator.onAccelerometer(
            timestampNs = timestampNs,
            ax = ax,
            ay = ay,
            az = az,
            position = latestPosition.position
        )

        latestPhase = phaseTracker.onFilteredAccelSample(
            timestampNs = timestampNs,
            filteredAccel = latestCadence.latestFilteredAccel,
            currentCadenceSpm = latestCadence.cadenceSpm,
            cadenceConfidence = latestCadence.confidence,
            position = latestPosition.position
        )

        // Se il sensore hardware TYPE_STEP_DETECTOR non ha emesso eventi negli ultimi 1,8 s ma
        // l'accelerometro rileva un impatto netto con alta confidenza di cadenza, alimentiamo StopDetector
        // e il contatore passi di fallback (fondamentale per sessioni dove TYPE_STEP_DETECTOR ha latenza o è assente)
        if (latestPhase.justDetectedImpact &&
            latestCadence.dynamicRmsMps2 >= config.minDynamicAccelRmsMps2 * 1.15f &&
            (lastHardwareStepNs == 0L || timestampNs - lastHardwareStepNs > 1_800_000_000L)
        ) {
            totalStepsCount++
            stopDetector.onStepEvent(timestampNs)
        }

        val updateIntervalNs = (config.cadenceUpdateIntervalSeconds * 1_000_000_000f).toLong()
        if (prevCadenceTimeNs == 0L || timestampNs - prevCadenceTimeNs >= updateIntervalNs) {
            lastFrameEmitNs = timestampNs
            latestPosition = positionClassifier.evaluate()
            latestStop = stopDetector.evaluate(timestampNs)

            if (latestStop.justResumedRequiringAnchorRelock) {
                latestAnchor = elasticAnchor.resetForRelock(timestampNs)
            }

            if (!isPaused) {
                latestAnchor = elasticAnchor.update(
                    timestampNs = timestampNs,
                    cadenceSpm = latestCadence.cadenceSpm,
                    cadenceConfidence = latestCadence.confidence,
                    isStopped = latestStop.isStopped
                )
            }

            return currentSnapshot(timestampNs)
        }

        return null
    }

    /**
     * Alimenta la macchina a stati con un singolo `ParsedBinaryRecord` letto da `sensors.bin`
     * (usato dal Replay Harness JVM `:replay` e dai test di regressione).
     */
    fun processBinaryRecord(record: ParsedBinaryRecord): SessionFrameSnapshot? {
        return when (record.recordType) {
            BinaryFormat.TYPE_ACCEL -> onAccelerometer(record.timestampNs, record.f0, record.f1, record.f2)
            BinaryFormat.TYPE_GYRO -> {
                onGyroscope(record.timestampNs, record.f0, record.f1, record.f2)
                null
            }
            BinaryFormat.TYPE_STEP -> {
                onStepDetectorEvent(record.timestampNs)
                null
            }
            BinaryFormat.TYPE_GPS_B -> {
                val hasSpeed = (record.flags.toInt() and 1) != 0
                if (hasSpeed) {
                    onGpsFix(record.timestampNs, record.f0)
                }
                null
            }
            else -> null
        }
    }

    fun currentSnapshot(timestampNs: Long = lastFrameEmitNs): SessionFrameSnapshot {
        val elapsedSec = if (sessionStartNs > 0L && timestampNs >= sessionStartNs) {
            (timestampNs - sessionStartNs) / 1_000_000_000f
        } else {
            0f
        }
        val runState = when {
            isPaused -> SessionRunState.PAUSED
            latestStop.isStopped -> SessionRunState.ACTIVE_STOPPED
            else -> SessionRunState.ACTIVE_RUNNING
        }
        return SessionFrameSnapshot(
            timestampNs = timestampNs,
            elapsedSeconds = elapsedSec,
            runState = runState,
            positionEstimate = latestPosition,
            cadenceEstimate = latestCadence,
            phaseState = latestPhase,
            anchorSnapshot = latestAnchor,
            stopSnapshot = latestStop,
            totalStepsDetected = totalStepsCount
        )
    }

    private fun ensureStartTimestamp(timestampNs: Long) {
        if (sessionStartNs == 0L && timestampNs > 0L) {
            sessionStartNs = timestampNs
        }
    }
}
