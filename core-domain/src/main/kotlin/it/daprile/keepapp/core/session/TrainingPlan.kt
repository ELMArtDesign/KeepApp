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

/**
 * Modalità di allenamento / guida ritmica di KeepApp (Fase 1B — Step 3).
 */
enum class TrainingMode(val label: String) {
    /** Ancora elastica adattiva (modalità classica di KeepApp) */
    ADAPTIVE_ANCHOR("Ancora Elastica"),
    /** Cadenza target fissa scelta dall'utente (es. 165 BPM) */
    FIXED_TARGET("Target Fisso"),
    /** Progressione lineare dal BPM iniziale al BPM finale in N minuti */
    PROGRESSIVE_RAMP("Progressione")
}

/**
 * Configurazione del piano di allenamento selezionato dall'utente.
 */
data class TrainingPlanConfig(
    val mode: TrainingMode = TrainingMode.ADAPTIVE_ANCHOR,
    val fixedTargetBpm: Float = 160f,
    val rampStartBpm: Float = 150f,
    val rampEndBpm: Float = 175f,
    val rampDurationMinutes: Int = 20
) {
    /**
     * Calcola il BPM target al secondo `elapsedSeconds` per modalità `FIXED_TARGET` o `PROGRESSIVE_RAMP`.
     */
    fun scheduledTargetBpm(elapsedSeconds: Float): Float = when (mode) {
        TrainingMode.ADAPTIVE_ANCHOR -> 0f
        TrainingMode.FIXED_TARGET -> fixedTargetBpm.coerceIn(60f, 220f)
        TrainingMode.PROGRESSIVE_RAMP -> {
            val totalSec = (rampDurationMinutes.coerceAtLeast(1) * 60f)
            val progress = (elapsedSeconds / totalSec).coerceIn(0f, 1f)
            (rampStartBpm + (rampEndBpm - rampStartBpm) * progress).coerceIn(60f, 220f)
        }
    }
}
