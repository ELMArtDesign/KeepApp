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
import kotlin.math.roundToInt

/**
 * Pianificatore puro in `:core-domain` per gli avvisi vocali discreti durante la corsa (Fase 1B — Step 2).
 *
 * Evita sovrapposizioni e ripetizioni eccessive tramite cooldown minimi tra annunci consecutivi.
 */
class VoiceAnnouncementPlanner(
    private val minIntervalSeconds: Float = 6.0f,
    private val periodicSummaryIntervalSeconds: Float = 300.0f // ogni 5 minuti
) {
    private var prevAnchorState: AnchorState? = null
    private var prevReinforcement: Boolean = false
    private var prevRunState: SessionRunState? = null
    private var lastAnnouncementElapsedSec: Float = -100f
    private var lastReinforcementAnnouncementSec: Float = -100f
    private var nextPeriodicSummarySec: Float = periodicSummaryIntervalSeconds

    fun reset() {
        prevAnchorState = null
        prevReinforcement = false
        prevRunState = null
        lastAnnouncementElapsedSec = -100f
        lastReinforcementAnnouncementSec = -100f
        nextPeriodicSummarySec = periodicSummaryIntervalSeconds
    }

    /**
     * Valuta uno snapshot di sessione e restituisce un breve messaggio vocale in italiano se opportuno,
     * oppure `null` se non serve annunciare nulla.
     */
    fun onSnapshot(snapshot: SessionFrameSnapshot): String? {
        val t = snapshot.elapsedSeconds
        val canSpeakNormal = (t - lastAnnouncementElapsedSec) >= minIntervalSeconds

        val curAnchor = snapshot.anchorSnapshot.state
        val curRun = snapshot.runState
        val curReinforce = snapshot.anchorSnapshot.reinforcementActive

        var message: String? = null

        // 1. Transizioni di stato sosta / ripresa
        if (prevRunState != null && prevRunState != curRun) {
            message = when (curRun) {
                SessionRunState.ACTIVE_STOPPED -> "Sosta rilevata."
                SessionRunState.ACTIVE_RUNNING -> if (prevRunState == SessionRunState.ACTIVE_STOPPED) "Ripresa." else null
                SessionRunState.PAUSED -> null
            }
        }

        // 2. Primo aggancio dell'ancora elastica
        if (message == null && canSpeakNormal &&
            prevAnchorState == AnchorState.WARMING_UP &&
            curAnchor == AnchorState.LOCKED &&
            snapshot.anchorSnapshot.targetBpm >= 60f
        ) {
            val bpm = snapshot.anchorSnapshot.targetBpm.roundToInt()
            message = "Ritmo agganciato a $bpm battiti."
        }

        // 3. Attivazione rinforzo (cooldown lungo >= 45 s per non disturbare)
        if (message == null && canSpeakNormal &&
            !prevReinforcement && curReinforce &&
            (t - lastReinforcementAnnouncementSec) >= 45f &&
            snapshot.anchorSnapshot.targetBpm >= 60f
        ) {
            val bpm = snapshot.anchorSnapshot.targetBpm.roundToInt()
            message = "Mantieni il passo, $bpm battiti."
            lastReinforcementAnnouncementSec = t
        }

        // 4. Riepilogo discreto ogni 5 minuti
        if (message == null && canSpeakNormal &&
            curRun == SessionRunState.ACTIVE_RUNNING &&
            t >= nextPeriodicSummarySec
        ) {
            val mins = (t / 60f).roundToInt()
            val bpm = snapshot.cadenceEstimate.cadenceSpm.roundToInt()
            message = "$mins minuti, cadenza $bpm, ${snapshot.totalStepsDetected} passi."
            nextPeriodicSummarySec += periodicSummaryIntervalSeconds
        }

        prevAnchorState = curAnchor
        prevReinforcement = curReinforce
        prevRunState = curRun

        if (message != null) {
            lastAnnouncementElapsedSec = t
        }
        return message
    }
}
