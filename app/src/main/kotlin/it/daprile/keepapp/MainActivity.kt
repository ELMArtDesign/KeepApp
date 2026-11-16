/*
 * Copyright 2026 Adriano D'Aprile
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package it.daprile.keepapp

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import it.daprile.keepapp.core.audio.AudioMode
import it.daprile.keepapp.core.audio.CalibrationMethod
import it.daprile.keepapp.core.rhythm.RhythmPattern
import it.daprile.keepapp.core.session.CadenceTracePoint
import it.daprile.keepapp.core.session.SessionRunState
import it.daprile.keepapp.core.session.SessionSummaryRecord
import it.daprile.keepapp.core.session.TrainingMode
import it.daprile.keepapp.core.session.TrainingPlanConfig
import it.daprile.keepapp.platform.audio.AudioEngineTelemetry
import it.daprile.keepapp.platform.audio.BluetoothLatencyCalibrator
import it.daprile.keepapp.platform.audio.BtLatencyProfileStore
import it.daprile.keepapp.platform.audio.CalibrationUiState
import it.daprile.keepapp.platform.audio.EarCheckLogEntry
import it.daprile.keepapp.platform.audio.EarSyncTester
import it.daprile.keepapp.platform.audio.EarSyncUiState
import it.daprile.keepapp.platform.audio.KeepAppAudioEngine
import it.daprile.keepapp.platform.audio.PatternAssetLoader
import it.daprile.keepapp.platform.audio.SavedBtLatencyProfile
import it.daprile.keepapp.platform.session.KeepAppSessionService
import it.daprile.keepapp.platform.session.LiveSessionUiState
import it.daprile.keepapp.platform.session.SessionHistoryStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Schermata principale di KeepApp (Fase 1A — M4 Sessione Live + M2 AudioEngine + M3 Calibrazione BT).
 */
class MainActivity : ComponentActivity() {

    private lateinit var patterns: List<RhythmPattern>
    private lateinit var manualAudioEngine: KeepAppAudioEngine
    private lateinit var calibrator: BluetoothLatencyCalibrator
    private lateinit var profileStore: BtLatencyProfileStore
    private lateinit var earSyncTester: EarSyncTester

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        patterns = PatternAssetLoader.loadAll(this)
        manualAudioEngine = KeepAppAudioEngine(initialPattern = patterns.first())
        calibrator = BluetoothLatencyCalibrator(context = this)
        earSyncTester = EarSyncTester(this)

        // Step A0: congela le funzioni sperimentali se i rispettivi FeatureFlags sono disattivati
        val prefStore = SessionHistoryStore(this)
        val curPrefs = prefStore.loadPreferences()
        if (!FeatureFlags.ENABLE_TRAINING_PLAN || !FeatureFlags.ENABLE_VOICE_COACH_TTS) {
            prefStore.savePreferences(
                curPrefs.copy(
                    voiceCoachEnabled = if (FeatureFlags.ENABLE_VOICE_COACH_TTS) curPrefs.voiceCoachEnabled else false,
                    trainingPlan = if (FeatureFlags.ENABLE_TRAINING_PLAN) curPrefs.trainingPlan else TrainingPlanConfig()
                )
            )
        }
        profileStore = BtLatencyProfileStore(this)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val liveState by KeepAppSessionService.liveState.collectAsState()
                    val manualTelemetry by manualAudioEngine.telemetry.collectAsState()
                    val calibState by calibrator.state.collectAsState()
                    val earSyncState by earSyncTester.state.collectAsState()

                    KeepAppMainScreen(
                        patterns = patterns,
                        liveState = liveState,
                        manualTelemetry = manualTelemetry,
                        calibState = calibState,
                        earSyncState = earSyncState,
                        profileStore = profileStore,
                        onToggleLiveSession = {
                            if (liveState.isActive) {
                                KeepAppSessionService.stopSession(this)
                            } else {
                                manualAudioEngine.stop()
                                calibrator.cancel()
                                earSyncTester.stop()
                                KeepAppSessionService.startSession(this)
                            }
                        },
                        onToggleLivePause = { KeepAppSessionService.togglePause(this) },
                        onLiveSelectMode = { mode ->
                            startService(
                                Intent(this, KeepAppSessionService::class.java)
                                    .setAction(KeepAppSessionService.ACTION_SET_MODE)
                                    .putExtra(KeepAppSessionService.EXTRA_AUDIO_MODE, mode.name)
                            )
                        },
                        onLiveSelectPattern = { pat ->
                            startService(
                                Intent(this, KeepAppSessionService::class.java)
                                    .setAction(KeepAppSessionService.ACTION_SET_PATTERN)
                                    .putExtra(KeepAppSessionService.EXTRA_PATTERN_ID, pat.id)
                            )
                        },
                        onLiveToggleAutoLevel = { auto ->
                            startService(
                                Intent(this, KeepAppSessionService::class.java)
                                    .setAction(KeepAppSessionService.ACTION_SET_AUTO_LEVEL)
                                    .putExtra(KeepAppSessionService.EXTRA_AUTO_LEVEL, auto)
                            )
                        },
                        onLiveToggleVoiceCoach = { enabled ->
                            startService(
                                Intent(this, KeepAppSessionService::class.java)
                                    .setAction(KeepAppSessionService.ACTION_SET_VOICE_COACH)
                                    .putExtra(KeepAppSessionService.EXTRA_VOICE_COACH, enabled)
                            )
                        },
                        onLiveSelectManualLevel = { lvl ->
                            startService(
                                Intent(this, KeepAppSessionService::class.java)
                                    .setAction(KeepAppSessionService.ACTION_SET_MANUAL_LEVEL)
                                    .putExtra(KeepAppSessionService.EXTRA_LEVEL_INDEX, lvl)
                            )
                        },
                        onToggleManualRun = {
                            if (manualTelemetry.isRunning) {
                                manualAudioEngine.stop()
                            } else {
                                KeepAppSessionService.stopSession(this)
                                calibrator.cancel()
                                earSyncTester.stop()
                                manualAudioEngine.start()
                            }
                        },
                        onSelectMode = { manualAudioEngine.setMode(it) },
                        onSelectPattern = { manualAudioEngine.setPattern(it) },
                        onSelectLevel = { manualAudioEngine.setLevel(it) },
                        onBpmChange = { manualAudioEngine.setBpm(it) },
                        onVolumeChange = { manualAudioEngine.setVolume(it) },
                        onMutedChange = { manualAudioEngine.setMuted(it) },
                        onAttenuatedChange = { manualAudioEngine.setAttenuated(it) },
                        onStartContactCalibration = {
                            KeepAppSessionService.stopSession(this)
                            manualAudioEngine.stop()
                            earSyncTester.stop()
                            calibrator.startContactImpulseCalibration()
                        },
                        onStartTapCalibration = {
                            KeepAppSessionService.stopSession(this)
                            manualAudioEngine.stop()
                            earSyncTester.stop()
                            calibrator.startTapToBeatCalibration()
                        },
                        onStartSpeakerCheck = {
                            KeepAppSessionService.stopSession(this)
                            manualAudioEngine.stop()
                            earSyncTester.stop()
                            calibrator.startSpeakerLoopbackCheck()
                        },
                        onStartBodyTapCheck = {
                            KeepAppSessionService.stopSession(this)
                            manualAudioEngine.stop()
                            earSyncTester.stop()
                            calibrator.startBodyTapMicDelayTest()
                        },
                        onUserTap = { calibrator.registerUserTap() },
                        onCancelCalibration = { calibrator.cancel() },
                        onStartEarSync = { advanceMs ->
                            KeepAppSessionService.stopSession(this)
                            manualAudioEngine.stop()
                            calibrator.cancel()
                            earSyncTester.start(advanceMs)
                        },
                        onStopEarSync = { earSyncTester.stop() },
                        onEarSyncAdvanceChange = { earSyncTester.setAdvanceMs(it) }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        calibrator.cancel()
        earSyncTester.stop()
        manualAudioEngine.stop()
        super.onDestroy()
    }
}

@Composable
private fun KeepAppMainScreen(
    patterns: List<RhythmPattern>,
    liveState: LiveSessionUiState,
    manualTelemetry: AudioEngineTelemetry,
    calibState: CalibrationUiState,
    earSyncState: EarSyncUiState,
    profileStore: BtLatencyProfileStore,
    onToggleLiveSession: () -> Unit,
    onToggleLivePause: () -> Unit,
    onLiveSelectMode: (AudioMode) -> Unit,
    onLiveSelectPattern: (RhythmPattern) -> Unit,
    onLiveToggleAutoLevel: (Boolean) -> Unit,
    onLiveToggleVoiceCoach: (Boolean) -> Unit,
    onLiveSelectManualLevel: (Int) -> Unit,
    onToggleManualRun: () -> Unit,
    onSelectMode: (AudioMode) -> Unit,
    onSelectPattern: (RhythmPattern) -> Unit,
    onSelectLevel: (Int) -> Unit,
    onBpmChange: (Float) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onMutedChange: (Boolean) -> Unit,
    onAttenuatedChange: (Boolean) -> Unit,
    onStartContactCalibration: () -> Unit,
    onStartTapCalibration: () -> Unit,
    onStartSpeakerCheck: () -> Unit,
    onStartBodyTapCheck: () -> Unit,
    onUserTap: () -> Unit,
    onCancelCalibration: () -> Unit,
    onStartEarSync: (Float) -> Unit,
    onStopEarSync: () -> Unit,
    onEarSyncAdvanceChange: (Float) -> Unit
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }

    val sessionPermsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        onToggleLiveSession()
    }

    fun requestPermsAndToggleLive() {
        if (liveState.isActive) {
            onToggleLiveSession()
            return
        }
        val needed = buildList {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.ACTIVITY_RECOGNITION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isEmpty()) {
            onToggleLiveSession()
        } else {
            sessionPermsLauncher.launch(needed.toTypedArray())
        }
    }

    Scaffold(
        topBar = {
            Surface(tonalElevation = 3.dp) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "KeepApp — Fase 1A",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        when (selectedTab) {
                            0 -> Button(
                                onClick = { requestPermsAndToggleLive() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                colors = if (liveState.isActive) {
                                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                } else {
                                    ButtonDefaults.buttonColors()
                                }
                            ) {
                                Text(
                                    text = if (liveState.isActive) "FERMA SESSIONE LIVE" else "AVVIA SESSIONE LIVE",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            2 -> Button(
                                onClick = onToggleManualRun,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                colors = if (manualTelemetry.isRunning) {
                                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                } else {
                                    ButtonDefaults.buttonColors()
                                }
                            ) {
                                Text(
                                    text = if (manualTelemetry.isRunning) "FERMA AUDIO ENGINE" else "AVVIA AUDIO ENGINE",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    TabRow(selectedTabIndex = selectedTab) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("Live") }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text("Storico") }
                        )
                        Tab(
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2 },
                            text = { Text("Test audio") }
                        )
                        Tab(
                            selected = selectedTab == 3,
                            onClick = { selectedTab = 3 },
                            text = { Text("Calibrazione") }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        when (selectedTab) {
            0 -> LiveSessionTab(
                modifier = Modifier.padding(innerPadding),
                patterns = patterns,
                liveState = liveState,
                onTogglePause = onToggleLivePause,
                onSelectMode = onLiveSelectMode,
                onSelectPattern = onLiveSelectPattern,
                onToggleAutoLevel = onLiveToggleAutoLevel,
                onToggleVoiceCoach = onLiveToggleVoiceCoach,
                onSelectManualLevel = onLiveSelectManualLevel
            )
            1 -> SessionHistoryTab(
                modifier = Modifier.padding(innerPadding)
            )
            2 -> AudioEngineTab(
                modifier = Modifier.padding(innerPadding),
                patterns = patterns,
                telemetry = manualTelemetry,
                onSelectMode = onSelectMode,
                onSelectPattern = onSelectPattern,
                onSelectLevel = onSelectLevel,
                onBpmChange = onBpmChange,
                onVolumeChange = onVolumeChange,
                onMutedChange = onMutedChange,
                onAttenuatedChange = onAttenuatedChange
            )
            else -> BluetoothCalibrationTab(
                modifier = Modifier.padding(innerPadding),
                calibState = calibState,
                earSyncState = earSyncState,
                profileStore = profileStore,
                onStartContactCalibration = onStartContactCalibration,
                onStartTapCalibration = onStartTapCalibration,
                onStartSpeakerCheck = onStartSpeakerCheck,
                onStartBodyTapCheck = onStartBodyTapCheck,
                onUserTap = onUserTap,
                onCancelCalibration = onCancelCalibration,
                onStartEarSync = onStartEarSync,
                onStopEarSync = onStopEarSync,
                onEarSyncAdvanceChange = onEarSyncAdvanceChange
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LiveSessionTab(
    modifier: Modifier = Modifier,
    patterns: List<RhythmPattern>,
    liveState: LiveSessionUiState,
    onTogglePause: () -> Unit,
    onSelectMode: (AudioMode) -> Unit,
    onSelectPattern: (RhythmPattern) -> Unit,
    onToggleAutoLevel: (Boolean) -> Unit,
    onToggleVoiceCoach: (Boolean) -> Unit,
    onSelectManualLevel: (Int) -> Unit
) {
    var selectedPatternId by remember { mutableStateOf(patterns.first().id) }
    val activePattern = patterns.find { it.id == selectedPatternId } ?: patterns.first()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Card stato corsa & ancora elastica
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

                if (liveState.isActive) {
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

        // Controlli pattern e livello in corsa
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

        // Telemetria Bluetooth & AudioEngine
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
            liveState.lastCompletedSummary?.let { summary ->
                SummaryCard(title = "Riepilogo Ultima Sessione", summary = summary)
            }
        }
    }
}

@Composable
private fun SummaryCard(
    title: String,
    summary: SessionSummaryRecord
) {
    val dateStr = remember(summary.startedAtMs) {
        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY).format(Date(summary.startedAtMs))
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("$title ($dateStr)", fontWeight = FontWeight.Bold)
            Text(
                text = String.format(
                    Locale.US,
                    "Durata: %.0f s  |  Passi: %d\nCadenza media: %.1f SPM\nAncora: iniz. %.1f -> fin. %.1f BPM\nSoste: %d  |  Pause: %d  |  Pos: %s\nCuffie: %s (%.1f ms)",
                    summary.durationSeconds,
                    summary.totalSteps,
                    summary.avgCadenceSpm,
                    summary.initialLockedBpm,
                    summary.finalTargetBpm,
                    summary.stopCount,
                    summary.pauseCount,
                    summary.dominantPosition.name,
                    summary.deviceName,
                    summary.appliedBtLatencyMs
                ),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
            if (summary.tracePoints.size >= 2) {
                Spacer(modifier = Modifier.height(4.dp))
                CadenceTraceChart(tracePoints = summary.tracePoints)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrainingPlanCard(
    initialPlan: TrainingPlanConfig,
    onPlanChanged: (TrainingPlanConfig) -> Unit
) {
    val context = LocalContext.current
    val persisted = remember { SessionHistoryStore(context).loadPreferences().trainingPlan }
    var plan by remember(initialPlan) { mutableStateOf(if (initialPlan == TrainingPlanConfig()) persisted else initialPlan) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Modalità Allenamento", fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TrainingMode.entries.forEach { m ->
                    FilterChip(
                        selected = plan.mode == m,
                        onClick = {
                            val updated = plan.copy(mode = m)
                            plan = updated
                            onPlanChanged(updated)
                        },
                        label = { Text(m.label) }
                    )
                }
            }

            when (plan.mode) {
                TrainingMode.ADAPTIVE_ANCHOR -> {
                    Text(
                        text = "Il ritmo si aggancia alla tua cadenza naturale e ti sostiene nei cali (D40).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TrainingMode.FIXED_TARGET -> {
                    Text(
                        text = "Target Fisso: ${plan.fixedTargetBpm.roundToInt()} BPM",
                        fontWeight = FontWeight.Medium
                    )
                    Slider(
                        value = plan.fixedTargetBpm,
                        onValueChange = {
                            val updated = plan.copy(fixedTargetBpm = it)
                            plan = updated
                            onPlanChanged(updated)
                        },
                        valueRange = 90f..200f
                    )
                }
                TrainingMode.PROGRESSIVE_RAMP -> {
                    Text(
                        text = "Da ${plan.rampStartBpm.roundToInt()} a ${plan.rampEndBpm.roundToInt()} BPM in ${plan.rampDurationMinutes} min",
                        fontWeight = FontWeight.Medium
                    )
                    Slider(
                        value = plan.rampStartBpm,
                        onValueChange = {
                            val updated = plan.copy(rampStartBpm = it)
                            plan = updated
                            onPlanChanged(updated)
                        },
                        valueRange = 90f..190f
                    )
                    Slider(
                        value = plan.rampEndBpm,
                        onValueChange = {
                            val updated = plan.copy(rampEndBpm = it)
                            plan = updated
                            onPlanChanged(updated)
                        },
                        valueRange = 100f..200f
                    )
                    Slider(
                        value = plan.rampDurationMinutes.toFloat(),
                        onValueChange = {
                            val updated = plan.copy(rampDurationMinutes = it.roundToInt().coerceIn(5, 60))
                            plan = updated
                            onPlanChanged(updated)
                        },
                        valueRange = 5f..60f
                    )
                }
            }
        }
    }
}

@Composable
private fun CadenceTraceChart(tracePoints: List<CadenceTracePoint>) {
    val validVals = tracePoints.flatMap { listOf(it.cadenceSpm, it.targetBpm) }.filter { it >= 50f }
    val minVal = ((validVals.minOrNull() ?: 80f) - 10f).coerceAtLeast(40f)
    val maxVal = ((validVals.maxOrNull() ?: 180f) + 10f).coerceAtLeast(minVal + 20f)
    val primaryColor = MaterialTheme.colorScheme.primary
    val targetColor = Color(0xFF2E7D32)

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "Andamento: Blu = Cadenza SPM  |  Verde = Target BPM (${minVal.roundToInt()}–${maxVal.roundToInt()})",
            style = MaterialTheme.typography.labelSmall
        )
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(84.dp)
        ) {
            val w = size.width
            val h = size.height
            val n = tracePoints.size
            if (n < 2) return@Canvas

            val cadencePath = Path()
            val targetPath = Path()
            var hasCadenceStarted = false
            var hasTargetStarted = false

            for (i in 0 until n) {
                val x = (i.toFloat() / (n - 1).toFloat()) * w
                val pt = tracePoints[i]
                if (pt.cadenceSpm >= 50f) {
                    val y = h - ((pt.cadenceSpm - minVal) / (maxVal - minVal)).coerceIn(0f, 1f) * h
                    if (!hasCadenceStarted) {
                        cadencePath.moveTo(x, y)
                        hasCadenceStarted = true
                    } else {
                        cadencePath.lineTo(x, y)
                    }
                }
                if (pt.targetBpm >= 50f) {
                    val y = h - ((pt.targetBpm - minVal) / (maxVal - minVal)).coerceIn(0f, 1f) * h
                    if (!hasTargetStarted) {
                        targetPath.moveTo(x, y)
                        hasTargetStarted = true
                    } else {
                        targetPath.lineTo(x, y)
                    }
                }
            }

            drawLine(
                color = Color.Gray.copy(alpha = 0.35f),
                start = Offset(0f, h / 2f),
                end = Offset(w, h / 2f),
                strokeWidth = 1f
            )
            if (hasTargetStarted) {
                drawPath(
                    path = targetPath,
                    color = targetColor,
                    style = Stroke(width = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f)))
                )
            }
            if (hasCadenceStarted) {
                drawPath(
                    path = cadencePath,
                    color = primaryColor,
                    style = Stroke(width = 4f)
                )
            }
        }
    }
}

@Composable
private fun SessionHistoryTab(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val store = remember { SessionHistoryStore(context) }
    var history by remember { mutableStateOf(store.loadHistory()) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Storico Sessioni (${history.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { history = store.loadHistory() }) {
                    Text("Aggiorna")
                }
                if (history.isNotEmpty()) {
                    OutlinedButton(onClick = {
                        store.clearHistory()
                        history = emptyList()
                    }) {
                        Text("Svuota", maxLines = 1)
                    }
                }
            }
        }

        if (history.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Nessuna sessione salvata finora. Avvia e completa una sessione nella scheda Live per vederla qui.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        } else {
            history.forEachIndexed { idx, item ->
                SummaryCard(title = "Sessione #${history.size - idx}", summary = item)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AudioEngineTab(
    modifier: Modifier = Modifier,
    patterns: List<RhythmPattern>,
    telemetry: AudioEngineTelemetry,
    onSelectMode: (AudioMode) -> Unit,
    onSelectPattern: (RhythmPattern) -> Unit,
    onSelectLevel: (Int) -> Unit,
    onBpmChange: (Float) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onMutedChange: (Boolean) -> Unit,
    onAttenuatedChange: (Boolean) -> Unit
) {
    var bpmSlider by remember { mutableFloatStateOf(telemetry.bpm) }
    var volumeSlider by remember { mutableFloatStateOf(1f) }
    var selectedPatternId by remember { mutableStateOf(patterns.first().id) }

    val activePattern = patterns.find { it.id == selectedPatternId } ?: patterns.first()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Modalità Sonora", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = telemetry.mode == AudioMode.RHYTHM_PATTERN,
                        onClick = { onSelectMode(AudioMode.RHYTHM_PATTERN) },
                        label = { Text("Pattern Ritmico") }
                    )
                    FilterChip(
                        selected = telemetry.mode == AudioMode.SIMPLE_METRONOME,
                        onClick = { onSelectMode(AudioMode.SIMPLE_METRONOME) },
                        label = { Text("Metronomo Semplice") }
                    )
                }
            }
        }

        if (telemetry.mode == AudioMode.RHYTHM_PATTERN) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Pattern (JSON assets)", fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        patterns.forEach { pat ->
                            FilterChip(
                                selected = selectedPatternId == pat.id,
                                onClick = {
                                    selectedPatternId = pat.id
                                    onSelectPattern(pat)
                                },
                                label = { Text(pat.name) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    val pendingText = telemetry.pendingLevelIndex?.let { " (in coda: L$it al downbeat)" } ?: ""
                    Text(
                        text = "Livello Intensità: L${telemetry.activeLevelIndex}$pendingText",
                        fontWeight = FontWeight.SemiBold
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        activePattern.levels.forEach { lvl ->
                            FilterChip(
                                selected = telemetry.activeLevelIndex == lvl.levelIndex,
                                onClick = { onSelectLevel(lvl.levelIndex) },
                                label = { Text("L${lvl.levelIndex} ${lvl.name}") }
                            )
                        }
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Cadenza Target", fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "${bpmSlider.roundToInt()} BPM",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Slider(
                    value = bpmSlider,
                    onValueChange = {
                        bpmSlider = it
                        onBpmChange(it)
                    },
                    valueRange = 100f..200f
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(-5f, -1f, 1f, 5f).forEach { delta ->
                        OutlinedButton(
                            onClick = {
                                val next = (bpmSlider + delta).coerceIn(100f, 200f)
                                bpmSlider = next
                                onBpmChange(next)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (delta > 0) "+${delta.toInt()}" else "${delta.toInt()}")
                        }
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Volume & Rampe Smooth", fontWeight = FontWeight.SemiBold)
                    Text(String.format(Locale.US, "%.1f dB", telemetry.gainDb))
                }
                Slider(
                    value = volumeSlider,
                    onValueChange = {
                        volumeSlider = it
                        onVolumeChange(it)
                    },
                    valueRange = 0f..1f
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Mute (rampa 25 ms)")
                    Switch(checked = telemetry.isMuted, onCheckedChange = onMutedChange)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Attenuazione -20 dB (es. micro-pausa)")
                    Switch(checked = telemetry.isAttenuated, onCheckedChange = onAttenuatedChange)
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("Telemetria AudioEngine (D36)", fontWeight = FontWeight.SemiBold)
                val pllStatus = if (telemetry.pllLocked) "AGGANCIO OK" else "In aggancio..."
                Text(
                    text = String.format(
                        Locale.US,
                        "PLL: %s (%d campioni)\nErrore modello PLL: %.3f ms (target < 0.50 ms)\nJitter grezzo HAL BT: %.2f ms\nDeriva clock: %+.1f ppm\nUnderrun AudioTrack: %d",
                        pllStatus,
                        telemetry.pllSampleCount,
                        telemetry.pllResidualRmsMs,
                        telemetry.rawHalJitterMs,
                        telemetry.pllSlopePpm,
                        telemetry.underrunCount
                    ),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun BluetoothCalibrationTab(
    modifier: Modifier = Modifier,
    calibState: CalibrationUiState,
    earSyncState: EarSyncUiState,
    profileStore: BtLatencyProfileStore,
    onStartContactCalibration: () -> Unit,
    onStartTapCalibration: () -> Unit,
    onStartSpeakerCheck: () -> Unit,
    onStartBodyTapCheck: () -> Unit,
    onUserTap: () -> Unit,
    onCancelCalibration: () -> Unit,
    onStartEarSync: (Float) -> Unit,
    onStopEarSync: () -> Unit,
    onEarSyncAdvanceChange: (Float) -> Unit
) {
    val context = LocalContext.current
    var activeDevice by remember { mutableStateOf(profileStore.detectActiveOutputDevice()) }
    var savedProfile by remember { mutableStateOf(profileStore.load(activeDevice.key)) }
    var manualLatencySlider by remember(savedProfile) {
        mutableFloatStateOf(savedProfile?.latencyMs ?: 120f)
    }
    var earCompensationOn by remember { mutableStateOf(true) }
    var earFineTuneMs by remember { mutableFloatStateOf(0f) }
    var earCheckLogs by remember { mutableStateOf(profileStore.loadEarCheckLogs()) }
    var earLogExportStatus by remember { mutableStateOf("") }
    var pendingSpeakerCheck by remember { mutableStateOf(false) }
    var pendingBodyTapCheck by remember { mutableStateOf(false) }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            when {
                pendingBodyTapCheck -> {
                    pendingBodyTapCheck = false
                    onStartBodyTapCheck()
                }
                pendingSpeakerCheck -> {
                    pendingSpeakerCheck = false
                    onStartSpeakerCheck()
                }
                else -> onStartContactCalibration()
            }
        } else {
            pendingSpeakerCheck = false
            pendingBodyTapCheck = false
        }
    }

    fun refreshDevice() {
        val dev = profileStore.detectActiveOutputDevice()
        activeDevice = dev
        savedProfile = profileStore.load(dev.key)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Dispositivo Audio Attivo", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "${activeDevice.displayName} (${if (activeDevice.isBluetooth) "Bluetooth" else "Locale"})",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    OutlinedButton(onClick = { refreshDevice() }) {
                        Text("Aggiorna")
                    }
                }

                val p = savedProfile
                if (p != null) {
                    Text(
                        text = String.format(
                            Locale.US,
                            "Latenza salvata: %.1f ms (±%.1f ms) — %s",
                            p.latencyMs,
                            p.stdDevMs,
                            p.method.label
                        ),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                } else {
                    Text(
                        text = "Nessuna calibrazione salvata (default provvisorio: 120 ms)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Procedure di Calibrazione", fontWeight = FontWeight.SemiBold)

                Button(
                    onClick = {
                        refreshDevice()
                        val hasMic = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        if (hasMic) {
                            onStartContactCalibration()
                        } else {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    enabled = !calibState.isRunning,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("1. Calibrazione a contatto (8 impulsi)")
                }

                Button(
                    onClick = {
                        refreshDevice()
                        onStartTapCalibration()
                    },
                    enabled = !calibState.isRunning,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("2. Calibrazione battendo a tempo")
                }

                OutlinedButton(
                    onClick = {
                        val hasMic = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        if (hasMic) {
                            onStartBodyTapCheck()
                        } else {
                            pendingBodyTapCheck = true
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    enabled = !calibState.isRunning,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Test del colpo (10 tocchi su scocca — R_mic)")
                }

                OutlinedButton(
                    onClick = {
                        val hasMic = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        if (hasMic) {
                            onStartSpeakerCheck()
                        } else {
                            pendingSpeakerCheck = true
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    enabled = !calibState.isRunning,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Verifica ritardo altoparlante interno (X)")
                }

                if (calibState.isRunning) {
                    val progress = if (calibState.totalSteps > 0) {
                        calibState.progressStep.toFloat() / calibState.totalSteps.toFloat()
                    } else 0f
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (calibState.method == CalibrationMethod.TAP_TO_BEAT) {
                        Button(
                            onClick = onUserTap,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(84.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.tertiary
                            )
                        ) {
                            Text(
                                text = "BATTI A TEMPO QUI",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = onCancelCalibration,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Annulla Calibrazione")
                    }
                }

                Text(
                    text = calibState.statusMessage,
                    style = MaterialTheme.typography.bodyMedium
                )
                calibState.errorMessage?.let { err ->
                    Text(
                        text = err,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                if (calibState.liveSamplesMs.isNotEmpty()) {
                    Text(
                        text = "Campioni rilevati (ms): " + calibState.liveSamplesMs.joinToString(", ") {
                            String.format(Locale.US, "%.1f", it)
                        },
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        calibState.summary?.let { res ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        when {
                            calibState.isBodyTapCheck -> "Risultato Test del Colpo (R_mic)"
                            calibState.isSpeakerCheck -> "Risultato Altoparlante Interno (X)"
                            else -> "Risultato Calibrazione"
                        },
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = String.format(
                            Locale.US,
                            "Mediana: %.1f ms (MAD ±%.1f ms)\nMedia: %.1f ms  |  Dev. Std: %.1f ms\nCampioni validi: %d / %d",
                            res.medianLatencyMs,
                            res.madMs,
                            res.meanLatencyMs,
                            res.stdDevMs,
                            res.validSamples,
                            res.totalSamples
                        ),
                        fontFamily = FontFamily.Monospace
                    )
                    if (!calibState.isSpeakerCheck && !calibState.isBodyTapCheck) {
                        Button(
                            onClick = {
                                val profile = SavedBtLatencyProfile(
                                    deviceKey = activeDevice.key,
                                    deviceName = activeDevice.displayName,
                                    latencyMs = res.medianLatencyMs,
                                    stdDevMs = res.stdDevMs,
                                    method = res.method,
                                    calibratedAtMs = System.currentTimeMillis()
                                )
                                profileStore.save(profile)
                                savedProfile = profile
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("SALVA PROFILO PER ${activeDevice.displayName}")
                        }
                    }
                }
            }
        }

        // Riepilogo confronto latenze (1d): R_mic, X_vero, BT loopback, BT da orecchio, Scarto
        if (calibState.rMicMedianMs != null || calibState.speakerXRawMs != null || calibState.btLoopbackMs != null) {
            val rMic = calibState.rMicMedianMs
            val xRaw = calibState.speakerXRawMs
            val xVero = if (xRaw != null) xRaw - (rMic ?: 0f) else null
            val earFusionMs = earCheckLogs.firstOrNull { it.outcome.contains("1 colpo") }?.totalAppliedMs ?: 78.0f
            val btOrecchio = if (xVero != null) earFusionMs + xVero else null
            val btLoop = calibState.btLoopbackMs
            val scarto = if (btLoop != null && btOrecchio != null) btLoop - btOrecchio else null

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Riepilogo Confronto Latenze (1d)", fontWeight = FontWeight.Bold)
                    Text(
                        text = buildString {
                            appendLine("AudioRecord.getTimestamp: ${if (calibState.recHwTimestampActive) "ATTIVO" else "fallback read()"}")
                            appendLine("R_mic (colpo scocca): ${rMic?.let { String.format(Locale.US, "%.1f ms (MAD ±%.1f ms)", it, calibState.rMicMadMs ?: 0f) } ?: "da misurare"}")
                            appendLine("Ritardo FIFO IMU (D36): ${calibState.imuFifoDelayMs?.let { String.format(Locale.US, "%.1f ms", it) } ?: "da misurare"}")
                            appendLine("X_raw (altoparlante): ${xRaw?.let { String.format(Locale.US, "%.1f ms (MAD ±%.1f ms)", it, calibState.speakerXMadMs ?: 0f) } ?: "da misurare"}")
                            appendLine("X_vero (X_raw - R_mic): ${xVero?.let { String.format(Locale.US, "%.1f ms", it) } ?: "n/d"}")
                            appendLine("BT da loopback: ${btLoop?.let { String.format(Locale.US, "%.1f ms (MAD ±%.1f ms)", it, calibState.btLoopbackMadMs ?: 0f) } ?: "da misurare"}")
                            appendLine("BT da orecchio (${String.format(Locale.US, "%.1f", earFusionMs)} + X_vero): ${btOrecchio?.let { String.format(Locale.US, "%.1f ms", it) } ?: "n/d"}")
                            append("Scarto (loopback - orecchio): ${scarto?.let { String.format(Locale.US, "%+.1f ms", it) } ?: "n/d"}")
                        },
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Override Manuale (opzionale)", fontWeight = FontWeight.SemiBold)
                    Text("${manualLatencySlider.roundToInt()} ms", fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = manualLatencySlider,
                    onValueChange = { manualLatencySlider = it },
                    valueRange = 0f..400f
                )
                OutlinedButton(
                    onClick = {
                        val profile = SavedBtLatencyProfile(
                            deviceKey = activeDevice.key,
                            deviceName = activeDevice.displayName,
                            latencyMs = manualLatencySlider,
                            stdDevMs = 0f,
                            method = CalibrationMethod.MANUAL,
                            calibratedAtMs = System.currentTimeMillis()
                        )
                        profileStore.save(profile)
                        savedProfile = profile
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Salva valore manuale (${manualLatencySlider.roundToInt()} ms)")
                }
            }
        }

        // Prova a orecchio (altoparlante interno vs cuffia Bluetooth ogni 1 secondo)
        val baseLatencyMs = savedProfile?.latencyMs ?: 120f
        val totalAppliedMs = (if (earCompensationOn) baseLatencyMs else 0f) + earFineTuneMs
        val logTimeFmt = remember { SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()) }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Prova a orecchio", fontWeight = FontWeight.SemiBold)
                Text(
                    text = "Stanza silenziosa, telefono sul tavolo, volume a metà, cuffie indossate. Ascolta con la compensazione spenta e poi accesa.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Compensazione ON/OFF", fontWeight = FontWeight.Medium)
                        Text(
                            text = if (earCompensationOn) {
                                String.format(Locale.US, "Attiva (latenza salvata: %.1f ms)", baseLatencyMs)
                            } else {
                                "Spenta (0.0 ms)"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = earCompensationOn,
                        onCheckedChange = { checked ->
                            earCompensationOn = checked
                            val newTotal = (if (checked) baseLatencyMs else 0f) + earFineTuneMs
                            if (earSyncState.isRunning) {
                                onEarSyncAdvanceChange(newTotal)
                            }
                        }
                    )
                }

                Text(
                    text = String.format(
                        Locale.US,
                        "Regolazione fine: %+.0f ms  |  Totale applicato: %.1f ms",
                        earFineTuneMs,
                        totalAppliedMs
                    ),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
                Slider(
                    value = earFineTuneMs,
                    onValueChange = { raw ->
                        val snapped = ((raw / 5f).roundToInt() * 5f).coerceIn(-150f, 150f)
                        earFineTuneMs = snapped
                        val newTotal = (if (earCompensationOn) baseLatencyMs else 0f) + snapped
                        if (earSyncState.isRunning) {
                            onEarSyncAdvanceChange(newTotal)
                        }
                    },
                    valueRange = -150f..150f,
                    steps = 59
                )

                Button(
                    onClick = {
                        if (earSyncState.isRunning) {
                            onStopEarSync()
                        } else {
                            onStartEarSync(totalAppliedMs)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (earSyncState.isRunning) {
                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    } else {
                        ButtonDefaults.buttonColors()
                    }
                ) {
                    Text(if (earSyncState.isRunning) "Ferma" else "Avvia")
                }

                if (earSyncState.statusText.isNotEmpty()) {
                    val routingWarning = earSyncState.isRunning &&
                        (!earSyncState.speakerRoutedCorrectly || !earSyncState.btRoutedCorrectly)
                    Text(
                        text = earSyncState.statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (routingWarning) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                Text("Esito ascolto:", fontWeight = FontWeight.Medium)
                val saveOutcome: (String) -> Unit = { outcome ->
                    val entry = EarCheckLogEntry(
                        timestampMs = System.currentTimeMillis(),
                        deviceName = activeDevice.displayName,
                        compensationOn = earCompensationOn,
                        fineTuneMs = earFineTuneMs,
                        totalAppliedMs = totalAppliedMs,
                        outcome = outcome
                    )
                    profileStore.addEarCheckLog(entry)
                    earCheckLogs = profileStore.loadEarCheckLogs()
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = { saveOutcome("Sento 1 colpo") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Sento 1 colpo")
                    }
                    OutlinedButton(
                        onClick = { saveOutcome("Sento 2 colpi – prima il telefono") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Sento 2 colpi – prima il telefono")
                    }
                    OutlinedButton(
                        onClick = { saveOutcome("Sento 2 colpi – prima la cuffia") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Sento 2 colpi – prima la cuffia")
                    }
                }

                if (earCheckLogs.isNotEmpty()) {
                    Text(
                        text = "Registro prove (${earCheckLogs.size} totali, ultimi 10 mostrati):",
                        fontWeight = FontWeight.Medium,
                        style = MaterialTheme.typography.bodySmall
                    )
                    val formatLogEntry: (EarCheckLogEntry) -> String = { entry ->
                        val compLabel = if (entry.compensationOn) "ON" else "OFF"
                        String.format(
                            Locale.US,
                            "%s | %s | Comp:%s Fine:%+.0f ms (Tot:%.1f ms) -> %s",
                            logTimeFmt.format(Date(entry.timestampMs)),
                            entry.deviceName,
                            compLabel,
                            entry.fineTuneMs,
                            entry.totalAppliedMs,
                            entry.outcome
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        earCheckLogs.take(10).forEach { entry ->
                            Text(
                                text = formatLogEntry(entry),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val fullText = earCheckLogs.joinToString("\n") { formatLogEntry(it) }
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("KeepApp Prova a orecchio", fullText))
                                earLogExportStatus = "Copiati tutti i ${earCheckLogs.size} esiti negli appunti."
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Copia tutto")
                        }
                        OutlinedButton(
                            onClick = {
                                val fullText = earCheckLogs.joinToString("\n") { formatLogEntry(it) } + "\n"
                                val fileName = "keepapp_prova_orecchio_${System.currentTimeMillis() / 1000}.txt"
                                earLogExportStatus = exportTextToDownloads(context, fileName, fullText)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Esporta in Download")
                        }
                    }
                    if (earLogExportStatus.isNotEmpty()) {
                        Text(
                            text = earLogExportStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

private fun exportTextToDownloads(context: Context, fileName: String, content: String): String {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return "Errore: impossibile creare file in Download."
            context.contentResolver.openOutputStream(uri)?.use { os ->
                os.write(content.toByteArray(Charsets.UTF_8))
            }
            "Esportato in Download/$fileName"
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            val out = java.io.File(dir, fileName)
            out.writeText(content, Charsets.UTF_8)
            "Esportato in ${out.absolutePath}"
        }
    } catch (t: Throwable) {
        "Errore esportazione: ${t.message}"
    }
}
