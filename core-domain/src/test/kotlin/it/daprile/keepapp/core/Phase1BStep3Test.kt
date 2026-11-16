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
import it.daprile.keepapp.core.session.TrainingMode
import it.daprile.keepapp.core.session.TrainingPlanConfig
import it.daprile.keepapp.core.stop.StopDetectorSnapshot
import org.testng.annotations.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Test unitari JVM per la Fase 1B — Step 3 (TrainingPlan + TracePoints per grafici).
 */
class Phase1BStep3Test {

    @Test
    fun `TrainingPlanConfig calcola target fisso e rampa progressiva lineare`() {
        val fixed = TrainingPlanConfig(mode = TrainingMode.FIXED_TARGET, fixedTargetBpm = 168f)
        assertEquals(168f, fixed.scheduledTargetBpm(0f))
        assertEquals(168f, fixed.scheduledTargetBpm(600f))

        val ramp = TrainingPlanConfig(
            mode = TrainingMode.PROGRESSIVE_RAMP,
            rampStartBpm = 150f,
            rampEndBpm = 170f,
            rampDurationMinutes = 10
        )
        assertTrue(abs(ramp.scheduledTargetBpm(0f) - 150f) < 0.01f)
        assertTrue(abs(ramp.scheduledTargetBpm(300f) - 160f) < 0.01f)
        assertTrue(abs(ramp.scheduledTargetBpm(600f) - 170f) < 0.01f)
        assertTrue(abs(ramp.scheduledTargetBpm(900f) - 170f) < 0.01f)
    }

    @Test
    fun `SessionMetricsTracker registra tracePoints ogni 5 secondi per il grafico`() {
        val tracker = SessionMetricsTracker("t1", 1000L)
        for (sec in listOf(0f, 2f, 5f, 7f, 10f, 15f)) {
            val snap = SessionFrameSnapshot(
                timestampNs = (sec * 1_000_000_000f).toLong(),
                elapsedSeconds = sec,
                runState = SessionRunState.ACTIVE_RUNNING,
                positionEstimate = PositionEstimate(PhonePosition.ARM, 0.9f, 0f, 0f),
                cadenceEstimate = CadenceEstimate(160f + sec, 0.9f, 1.5f, 160f, true, 0f),
                phaseState = PhaseState(0L, 0L, 0.8f, 18f, 10L),
                anchorSnapshot = ElasticAnchorSnapshot(AnchorState.LOCKED, 162f, 162f, false, 0f),
                stopSnapshot = StopDetectorSnapshot(false, 0f, 1f, 0f, 0, 0, false),
                totalStepsDetected = 20L
            )
            tracker.onSnapshot(snap)
        }
        val summary = tracker.buildSummary("MANKIW-X15", 153.4f, "pulse_steady")
        assertEquals(4, summary.tracePoints.size) // 0, 5, 10, 15 s
        assertEquals(0, summary.tracePoints[0].elapsedSec)
        assertEquals(15, summary.tracePoints[3].elapsedSec)
    }
}
