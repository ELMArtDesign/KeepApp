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
import it.daprile.keepapp.core.session.SessionRunState
import it.daprile.keepapp.core.session.VoiceAnnouncementPlanner
import it.daprile.keepapp.core.stop.StopDetectorSnapshot
import org.testng.annotations.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Test unitari JVM per gli avvisi vocali discreti della Fase 1B — Step 2.
 */
class Phase1BStep2Test {

    private fun snap(
        sec: Float,
        runState: SessionRunState = SessionRunState.ACTIVE_RUNNING,
        anchorState: AnchorState = AnchorState.WARMING_UP,
        targetBpm: Float = 162f,
        reinforce: Boolean = false,
        steps: Long = 100L
    ): SessionFrameSnapshot = SessionFrameSnapshot(
        timestampNs = (sec * 1_000_000_000f).toLong(),
        elapsedSeconds = sec,
        runState = runState,
        positionEstimate = PositionEstimate(PhonePosition.ARM, 0.9f, 0f, 0f),
        cadenceEstimate = CadenceEstimate(targetBpm, 0.9f, 1.5f, targetBpm, true, 0f),
        phaseState = PhaseState(0L, 0L, 0.8f, 18f, steps),
        anchorSnapshot = ElasticAnchorSnapshot(anchorState, targetBpm, targetBpm, reinforce, 0f),
        stopSnapshot = StopDetectorSnapshot(runState == SessionRunState.ACTIVE_STOPPED, 0f, 1f, 0f, 0, 0, false),
        totalStepsDetected = steps
    )

    @Test
    fun `annuncia aggancio ancora, sosta, ripresa e riepilogo 5 min senza spam`() {
        val planner = VoiceAnnouncementPlanner(minIntervalSeconds = 6f, periodicSummaryIntervalSeconds = 300f)

        assertNull(planner.onSnapshot(snap(5f, anchorState = AnchorState.WARMING_UP)))

        val lockMsg = planner.onSnapshot(snap(25f, anchorState = AnchorState.LOCKED, targetBpm = 162f))
        assertEquals("Ritmo agganciato a 162 battiti.", lockMsg)

        // Troppo vicino (27 s < 6 s cooldown) -> nessun annuncio rinforzo
        assertNull(planner.onSnapshot(snap(27f, anchorState = AnchorState.LOCKED, reinforce = true)))

        // Sosta e ripresa
        assertEquals("Sosta rilevata.", planner.onSnapshot(snap(40f, runState = SessionRunState.ACTIVE_STOPPED, anchorState = AnchorState.LOCKED)))
        assertEquals("Ripresa.", planner.onSnapshot(snap(48f, runState = SessionRunState.ACTIVE_RUNNING, anchorState = AnchorState.LOCKED)))

        // Riepilogo ai 5 minuti (300 s)
        val summaryMsg = planner.onSnapshot(snap(301f, anchorState = AnchorState.LOCKED, targetBpm = 164f, steps = 820L))
        assertTrue(
            summaryMsg != null && summaryMsg.contains("5 minuti") && summaryMsg.contains("820 passi"),
            "Atteso riepilogo 5 minuti, ottenuto: $summaryMsg"
        )
    }
}
