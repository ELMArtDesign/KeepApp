/*
 * Copyright 2026 Adriano D'Aprile
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package it.daprile.keepapp.core

import it.daprile.keepapp.core.anchor.AnchorState
import it.daprile.keepapp.core.anchor.ElasticAnchorSnapshot
import it.daprile.keepapp.core.cadence.CadenceEstimate
import it.daprile.keepapp.core.format.PhonePosition
import it.daprile.keepapp.core.phase.PhaseState
import it.daprile.keepapp.core.position.PositionEstimate
import it.daprile.keepapp.core.session.SessionFrameSnapshot
import it.daprile.keepapp.core.session.SessionMetricsTracker
import it.daprile.keepapp.core.session.SessionRunState
import it.daprile.keepapp.core.stop.StopDetectorSnapshot
import org.testng.annotations.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Test unitari JVM per il riepilogo post-sessione della Milestone 5.
 */
class SessionSummaryM5Test {

    private fun makeSnapshot(
        elapsedSec: Float,
        runState: SessionRunState,
        cadenceSpm: Float,
        anchorState: AnchorState,
        targetBpm: Float,
        pos: PhonePosition,
        steps: Long
    ): SessionFrameSnapshot = SessionFrameSnapshot(
        timestampNs = (elapsedSec * 1_000_000_000f).toLong(),
        elapsedSeconds = elapsedSec,
        runState = runState,
        positionEstimate = PositionEstimate(pos, 0.9f, 0f, 0f),
        cadenceEstimate = CadenceEstimate(cadenceSpm, 0.9f, 0f, 2f, true, 0f),
        phaseState = PhaseState(0L, 0L, 0.8f, 18f, steps),
        anchorSnapshot = ElasticAnchorSnapshot(anchorState, 162f, targetBpm, false, 0f),
        stopSnapshot = StopDetectorSnapshot(runState == SessionRunState.ACTIVE_STOPPED, 0f, 1f, 0f, 0, 0, false),
        totalStepsDetected = steps
    )

    @Test
    fun `SessionMetricsTracker calcola cadenza media, soste, pause e posizione dominante`() {
        val tracker = SessionMetricsTracker(sessionId = "s1", startedAtMs = 1000L)

        tracker.onSnapshot(makeSnapshot(10f, SessionRunState.ACTIVE_RUNNING, 160f, AnchorState.WARMING_UP, 160f, PhonePosition.ARM, 26))
        tracker.onSnapshot(makeSnapshot(25f, SessionRunState.ACTIVE_RUNNING, 164f, AnchorState.LOCKED, 162f, PhonePosition.ARM, 66))
        tracker.onSnapshot(makeSnapshot(30f, SessionRunState.ACTIVE_STOPPED, 0f, AnchorState.LOCKED, 162f, PhonePosition.ARM, 66))
        tracker.onSnapshot(makeSnapshot(35f, SessionRunState.PAUSED, 0f, AnchorState.LOCKED, 162f, PhonePosition.HAND, 66))
        tracker.onSnapshot(makeSnapshot(45f, SessionRunState.ACTIVE_RUNNING, 162f, AnchorState.LOCKED, 162f, PhonePosition.ARM, 93))

        val summary = tracker.buildSummary(
            deviceName = "MANKIW-X15",
            appliedBtLatencyMs = 153.4f,
            patternId = "pulse_steady"
        )

        assertEquals("s1", summary.id)
        assertEquals(45f, summary.durationSeconds)
        assertEquals(93L, summary.totalSteps)
        assertTrue(abs(summary.avgCadenceSpm - 162f) < 0.1f)
        assertEquals(162f, summary.initialLockedBpm)
        assertEquals(162f, summary.finalTargetBpm)
        assertEquals(1, summary.stopCount)
        assertEquals(1, summary.pauseCount)
        assertEquals(PhonePosition.ARM, summary.dominantPosition)
        assertEquals("MANKIW-X15", summary.deviceName)
    }
}
