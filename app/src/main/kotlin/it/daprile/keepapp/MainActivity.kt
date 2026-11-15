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
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import it.daprile.keepapp.core.audio.AudioMode
import it.daprile.keepapp.core.audio.CalibrationMethod
import it.daprile.keepapp.core.rhythm.RhythmPattern
import it.daprile.keepapp.core.session.SessionRunState
import it.daprile.keepapp.platform.audio.AudioEngineTelemetry
import it.daprile.keepapp.platform.audio.BluetoothLatencyCalibrator
import it.daprile.keepapp.platform.audio.BtLatencyProfileStore
import it.daprile.keepapp.platform.audio.CalibrationUiState
import it.daprile.keepapp.platform.audio.KeepAppAudioEngine
import it.daprile.keepapp.platform.audio.PatternAssetLoader
import it.daprile.keepapp.platform.audio.SavedBtLatencyProfile
import it.daprile.keepapp.platform.session.KeepAppSessionService
import it.daprile.keepapp.platform.session.LiveSessionUiState
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        patterns = PatternAssetLoader.loadAll(this)
        manualAudioEngine = KeepAppAudioEngine(initialPattern = patterns.first())
        calibrator = BluetoothLatencyCalibrator()
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

                    KeepAppMainScreen(
                        patterns = patterns,
                        liveState = liveState,
                        manualTelemetry = manualTelemetry,
                        calibState = calibState,
                        profileStore = profileStore,
                        onToggleLiveSession = {
                            if (liveState.isActive) {
                                KeepAppSessionService.stopSession(this)
                            } else {
                                manualAudioEngine.stop()
                                calibrator.cancel()
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
                            calibrator.startContactImpulseCalibration()
                        },
                        onStartTapCalibration = {
                            KeepAppSessionService.stopSession(this)
                            manualAudioEngine.stop()
                            calibrator.startTapToBeatCalibration()
                        },
                        onUserTap = { calibrator.registerUserTap() },
                        onCancelCalibration = { calibrator.cancel() }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        calibrator.cancel()
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
    profileStore: BtLatencyProfileStore,
    onToggleLiveSession: () -> Unit,
    onToggleLivePause: () -> Unit,
    onLiveSelectMode: (AudioMode) -> Unit,
    onLiveSelectPattern: (RhythmPattern) -> Unit,
    onLiveToggleAutoLevel: (Boolean) -> Unit,
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
    onUserTap: () -> Unit,
    onCancelCalibration: () -> Unit
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
                            1 -> Button(
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
                            text = { Text("Live M4") }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text("Audio M2") }
                        )
                        Tab(
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2 },
                            text = { Text("BT M3") }
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
                onSelectManualLevel = onLiveSelectManualLevel
            )
            1 -> AudioEngineTab(
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
                profileStore = profileStore,
                onStartContactCalibration = onStartContactCalibration,
                onStartTapCalibration = onStartTapCalibration,
                onUserTap = onUserTap,
                onCancelCalibration = onCancelCalibration
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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Livello Automatico (da Ancora/Sosta)")
                    Switch(checked = liveState.autoLevelEnabled, onCheckedChange = onToggleAutoLevel)
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
    profileStore: BtLatencyProfileStore,
    onStartContactCalibration: () -> Unit,
    onStartTapCalibration: () -> Unit,
    onUserTap: () -> Unit,
    onCancelCalibration: () -> Unit
) {
    val context = LocalContext.current
    var activeDevice by remember { mutableStateOf(profileStore.detectActiveOutputDevice()) }
    var savedProfile by remember { mutableStateOf(profileStore.load(activeDevice.key)) }
    var manualLatencySlider by remember(savedProfile) {
        mutableFloatStateOf(savedProfile?.latencyMs ?: 120f)
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            onStartContactCalibration()
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
                Text("Procedure di Calibrazione (D35)", fontWeight = FontWeight.SemiBold)

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
                    Text("1. A contatto (5 impulsi + Microfono)")
                }

                Button(
                    onClick = {
                        refreshDevice()
                        onStartTapCalibration()
                    },
                    enabled = !calibState.isRunning,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("2. Batti a tempo (16 battiti @ 120 BPM)")
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
                    Text("Risultato Calibrazione", fontWeight = FontWeight.Bold)
                    Text(
                        text = String.format(
                            Locale.US,
                            "Mediana: %.1f ms\nMedia: %.1f ms  |  Dev. Std: %.1f ms\nCampioni validi: %d / %d",
                            res.medianLatencyMs,
                            res.meanLatencyMs,
                            res.stdDevMs,
                            res.validSamples,
                            res.totalSamples
                        ),
                        fontFamily = FontFamily.Monospace
                    )
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
    }
}
