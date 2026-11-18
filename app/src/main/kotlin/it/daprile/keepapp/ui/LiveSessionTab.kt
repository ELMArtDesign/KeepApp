/*
 * Copyright 2026 Adriano D'Aprile
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package it.daprile.keepapp.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.daprile.keepapp.FeatureFlags
import it.daprile.keepapp.core.audio.AudioMode
import it.daprile.keepapp.core.rhythm.RhythmPattern
import it.daprile.keepapp.core.session.SessionRunState
import it.daprile.keepapp.platform.session.KeepAppSessionService
import it.daprile.keepapp.platform.session.LiveSessionUiState
import it.daprile.keepapp.platform.session.SessionHistoryStore
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LiveSessionTab(
    modifier: Modifier = Modifier,
    patterns: List<RhythmPattern>,
    liveState: LiveSessionUiState,
    onTogglePause: () -> Unit,
    onSelectMode: (AudioMode) -> Unit,
    onSelectPattern: (RhythmPattern) -> Unit,
    onToggleAutoLevel: (Boolean) -> Unit,
    onToggleVoiceCoach: (Boolean) -> Unit,
    onSelectManualLevel: (Int) -> Unit,
    onToggleDataCollectionMode: (Boolean) -> Unit,
    onAddMarker: () -> Unit,
    onSaveGroundTruth: (dir: String, steps: Int, pos: String, act: String, env: String, note: String, markers: List<Float>) -> String?
) {
    var selectedPatternId by remember { mutableStateOf(patterns.first().id) }
    val activePattern = patterns.find { it.id == selectedPatternId } ?: patterns.first()

    var prevActive by remember { mutableStateOf(liveState.isActive) }
    var showGroundTruthDialog by remember { mutableStateOf(false) }
    var groundTruthSavedMsg by remember { mutableStateOf("") }

    LaunchedEffect(liveState.isActive) {
        if (prevActive && !liveState.isActive && liveState.lastCompletedSessionDir != null) {
            showGroundTruthDialog = true
        }
        prevActive = liveState.isActive
    }

    if (showGroundTruthDialog && liveState.lastCompletedSessionDir != null) {
        GroundTruthDialog(
            defaultPositionCode = liveState.lastCompletedSummary?.dominantPosition?.code ?: "arm",
            markerRelTimesSec = liveState.lastMarkerRelTimesSec,
            onDismiss = { showGroundTruthDialog = false },
            onSave = { steps, pos, act, env, note ->
                val savedPath = onSaveGroundTruth(
                    liveState.lastCompletedSessionDir!!,
                    steps,
                    pos,
                    act,
                    env,
                    note,
                    liveState.lastMarkerRelTimesSec
                )
                groundTruthSavedMsg = if (savedPath != null) {
                    "ground_truth.json salvato ($steps passi, $pos, $act, $env)"
                } else {
                    "Errore nel salvataggio di ground_truth.json"
                }
                showGroundTruthDialog = false
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (liveState.isActive) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            )
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val stateLabel = when {
                    !liveState.isActive -> "INATTIVA"
                    liveState.runState == SessionRunState.PAUSED -> "IN PAUSA (1 click cuffia per riprendere)"
                    liveState.targetBpm < 60f -> "IN ATTESA PRIMI PASSI..."
                    liveState.runState == SessionRunState.ACTIVE_STOPPED -> "SOSTA RILEVATA (-20 dB)"
                    else -> "IN CORSA (${liveState.anchorState.name})"
                }
                Text("Stato: $stateLabel", fontWeight = FontWeight.Bold)
                Text(
                    text = String.format(
                        Locale.US,
                        "Cadenza rilevata: %.1f SPM (conf %.0f%%)\nTarget Ancora: %.1f BPM %s\nPosizione: %s  |  Passi: %d\nDurata: %.0f s  |  GPS: %.2f m/s",
                        liveState.cadenceSpm,
                        liveState.cadenceConfidence * 100f,
                        liveState.targetBpm,
                        if (liveState.reinforcementActive) "[RINFORZO]" else "",
                        liveState.estimatedPosition.name,
                        liveState.totalSteps,
                        liveState.elapsedSeconds,
                        liveState.gpsSpeedMps
                    ),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Raccolta dati T1 (1 tocco = Marker)", fontWeight = FontWeight.Medium)
                        Text(
                            text = "1° inizio conteggio · 2° inizio sosta · 3° fine sosta · 4° fine conteggio",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = liveState.dataCollectionMode,
                        onCheckedChange = onToggleDataCollectionMode
                    )
                }

                if (liveState.isActive) {
                    if (liveState.dataCollectionMode) {
                        Button(
                            onClick = onAddMarker,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Segna Marker (${liveState.markerRelTimesSec.size}/4)")
                        }
                    }
                    OutlinedButton(
                        onClick = onTogglePause,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (liveState.runState == SessionRunState.PAUSED) "Riprendi (o premi tasto cuffia)"
                            else "Pausa (o premi tasto cuffia)"
                        )
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Guida Sonora", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = liveState.audioTelemetry.mode == AudioMode.RHYTHM_PATTERN,
                        onClick = { onSelectMode(AudioMode.RHYTHM_PATTERN) },
                        label = { Text("Pattern Ritmico") }
                    )
                    FilterChip(
                        selected = liveState.audioTelemetry.mode == AudioMode.SIMPLE_METRONOME,
                        onClick = { onSelectMode(AudioMode.SIMPLE_METRONOME) },
                        label = { Text("Metronomo") }
                    )
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val displayedPatternId = if (liveState.isActive && liveState.audioTelemetry.activePatternId.isNotEmpty()) {
                        liveState.audioTelemetry.activePatternId
                    } else {
                        selectedPatternId
                    }
                    patterns.forEach { pat ->
                        FilterChip(
                            selected = displayedPatternId == pat.id,
                            onClick = {
                                selectedPatternId = pat.id
                                onSelectPattern(pat)
                            },
                            label = { Text(pat.name) }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Livello Automatico (da Ancora/Sosta)")
                    Switch(checked = liveState.autoLevelEnabled, onCheckedChange = onToggleAutoLevel)
                }

                if (FeatureFlags.ENABLE_VOICE_COACH_TTS) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Avvisi Vocali Discreti (TTS)")
                        Switch(checked = liveState.voiceCoachEnabled, onCheckedChange = onToggleVoiceCoach)
                    }
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    activePattern.levels.forEach { lvl ->
                        FilterChip(
                            selected = liveState.audioTelemetry.activeLevelIndex == lvl.levelIndex,
                            onClick = { onSelectManualLevel(lvl.levelIndex) },
                            label = { Text("L${lvl.levelIndex} ${lvl.name}") }
                        )
                    }
                }
            }
        }

        if (FeatureFlags.ENABLE_TRAINING_PLAN) {
            val ctx = LocalContext.current
            TrainingPlanCard(
                initialPlan = liveState.trainingPlan,
                onPlanChanged = { plan ->
                    val store = SessionHistoryStore(ctx)
                    store.savePreferences(store.loadPreferences().copy(trainingPlan = plan))
                    ctx.startService(
                        Intent(ctx, KeepAppSessionService::class.java)
                            .setAction(KeepAppSessionService.ACTION_SET_TRAINING_PLAN)
                            .putExtra(KeepAppSessionService.EXTRA_TRAINING_MODE, plan.mode.name)
                            .putExtra(KeepAppSessionService.EXTRA_FIXED_BPM, plan.fixedTargetBpm)
                            .putExtra(KeepAppSessionService.EXTRA_RAMP_START_BPM, plan.rampStartBpm)
                            .putExtra(KeepAppSessionService.EXTRA_RAMP_END_BPM, plan.rampEndBpm)
                            .putExtra(KeepAppSessionService.EXTRA_RAMP_MINUTES, plan.rampDurationMinutes)
                    )
                }
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("Sincronizzazione BT & AudioEngine", fontWeight = FontWeight.SemiBold)
                val pllStatus = if (liveState.audioTelemetry.pllLocked) "AGGANCIO OK" else "In aggancio..."
                Text(
                    text = String.format(
                        Locale.US,
                        "Cuffie: %s (latenza %.1f ms)\nPLL: %s (err %.3f ms)\nCorrezione fase beat: %+d us\nUnderrun AudioTrack: %d",
                        liveState.activeDeviceName,
                        liveState.appliedBtLatencyMs,
                        pllStatus,
                        liveState.audioTelemetry.pllResidualRmsMs,
                        liveState.audioTelemetry.lastPhaseCorrectionUs,
                        liveState.audioTelemetry.underrunCount
                    ),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (!liveState.isActive) {
            if (liveState.lastCompletedSessionDir != null) {
                OutlinedButton(
                    onClick = { showGroundTruthDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Compila / Modifica Dati di riferimento")
                }
            }
            if (groundTruthSavedMsg.isNotEmpty()) {
                Text(
                    text = groundTruthSavedMsg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            liveState.lastCompletedSummary?.let { summary ->
                SummaryCard(title = "Riepilogo Ultima Sessione", summary = summary)
            }
        }
    }
}
