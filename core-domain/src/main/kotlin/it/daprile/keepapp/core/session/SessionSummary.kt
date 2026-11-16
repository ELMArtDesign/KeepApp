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
import it.daprile.keepapp.core.format.PhonePosition

/**
 * Riepilogo persistibile di una sessione di corsa/camminata completata (Milestone 5).
 */
data class SessionSummaryRecord(
    val id: String,
    val startedAtMs: Long,
    val durationSeconds: Float,
    val totalSteps: Long,
    val avgCadenceSpm: Float,
    val initialLockedBpm: Float,
    val finalTargetBpm: Float,
    val stopCount: Int,
    val pauseCount: Int,
    val dominantPosition: PhonePosition,
    val deviceName: String,
    val appliedBtLatencyMs: Float,
    val patternId: String
)

/**
 * Accumulatore incrementale di metriche per produrre il riepilogo post-sessione in `:core-domain`.
 */
class SessionMetricsTracker(
    private val sessionId: String,
    private val startedAtMs: Long
) {
    private var lastDurationSec = 0f
    private var lastTotalSteps = 0L
    private var activeCadenceSum = 0.0
    private var activeCadenceSamples = 0
    private var initialLockedBpm = 0f
    private var finalTargetBpm = 0f
    private var stopCount = 0
    private var pauseCount = 0
    private var prevRunState: SessionRunState? = null
    private val positionCounts = IntArray(PhonePosition.entries.size)

    fun onSnapshot(snapshot: SessionFrameSnapshot) {
        lastDurationSec = snapshot.elapsedSeconds
        lastTotalSteps = snapshot.totalStepsDetected

        if (snapshot.runState == SessionRunState.ACTIVE_RUNNING && snapshot.cadenceEstimate.cadenceSpm >= 60f) {
            activeCadenceSum += snapshot.cadenceEstimate.cadenceSpm.toDouble()
            activeCadenceSamples++
        }

        if (initialLockedBpm <= 0f && snapshot.anchorSnapshot.state == AnchorState.LOCKED) {
            initialLockedBpm = snapshot.anchorSnapshot.initialLockedBpm
        }
        if (snapshot.anchorSnapshot.targetBpm >= 60f) {
            finalTargetBpm = snapshot.anchorSnapshot.targetBpm
        }

        if (prevRunState != snapshot.runState) {
            when (snapshot.runState) {
                SessionRunState.ACTIVE_STOPPED -> stopCount++
                SessionRunState.PAUSED -> pauseCount++
                SessionRunState.ACTIVE_RUNNING -> Unit
            }
            prevRunState = snapshot.runState
        }

        val posIdx = snapshot.positionEstimate.position.ordinal
        positionCounts[posIdx]++
    }

    fun buildSummary(
        deviceName: String,
        appliedBtLatencyMs: Float,
        patternId: String
    ): SessionSummaryRecord {
        val avgCadence = if (activeCadenceSamples > 0) {
            (activeCadenceSum / activeCadenceSamples.toDouble()).toFloat()
        } else {
            0f
        }

        var bestPosIdx = 0
        var bestCount = -1
        for (i in positionCounts.indices) {
            if (positionCounts[i] > bestCount) {
                bestCount = positionCounts[i]
                bestPosIdx = i
            }
        }

        return SessionSummaryRecord(
            id = sessionId,
            startedAtMs = startedAtMs,
            durationSeconds = lastDurationSec,
            totalSteps = lastTotalSteps,
            avgCadenceSpm = avgCadence,
            initialLockedBpm = initialLockedBpm,
            finalTargetBpm = finalTargetBpm,
            stopCount = stopCount,
            pauseCount = pauseCount,
            dominantPosition = PhonePosition.entries[bestPosIdx],
            deviceName = deviceName,
            appliedBtLatencyMs = appliedBtLatencyMs,
            patternId = patternId
        )
    }
}
