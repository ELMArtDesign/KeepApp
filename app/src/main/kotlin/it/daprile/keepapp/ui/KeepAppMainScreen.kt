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

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import it.daprile.keepapp.platform.session.KeepAppSessionService

@Composable
fun KeepAppMainScreen(holder: KeepAppUiStateHolder) {
    val context = LocalContext.current
    val liveState by KeepAppSessionService.liveState.collectAsState()
    val manualTelemetry by holder.manualAudioEngine.telemetry.collectAsState()
    val calibState by holder.calibrator.state.collectAsState()
    val earSyncState by holder.earSyncTester.state.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }

    val sessionPermsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { holder.toggleLiveSession(liveState.isActive) }

    fun requestPermsAndToggleLive() {
        if (liveState.isActive) {
            holder.toggleLiveSession(true)
            return
        }
        val needed = buildList {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION)
                != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.ACTIVITY_RECOGNITION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isEmpty()) holder.toggleLiveSession(false) else sessionPermsLauncher.launch(needed.toTypedArray())
    }

    Scaffold(
        topBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "KeepApp — Fase 1A",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 6.dp)
                )
                Button(
                    onClick = {
                        if (selectedTab == 2) holder.toggleManualRun(manualTelemetry.isRunning) else requestPermsAndToggleLive()
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (liveState.isActive || manualTelemetry.isRunning) {
                            MaterialTheme.colorScheme.error
                        } else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(
                        when {
                            selectedTab == 2 && manualTelemetry.isRunning -> "FERMA AUDIO MANUALE"
                            selectedTab == 2 -> "AVVIA AUDIO MANUALE"
                            liveState.isActive -> "FERMA SESSIONE LIVE"
                            else -> "AVVIA SESSIONE LIVE"
                        },
                        fontWeight = FontWeight.Bold
                    )
                }
                TabRow(selectedTabIndex = selectedTab) {
                    listOf("Live", "Storico", "Test audio", "Calibrazione").forEachIndexed { idx, label ->
                        Tab(selected = selectedTab == idx, onClick = { selectedTab = idx }, text = { Text(label) })
                    }
                }
            }
        }
    ) { innerPadding ->
        when (selectedTab) {
            0 -> LiveSessionTab(
                modifier = Modifier.padding(innerPadding),
                patterns = holder.patterns,
                liveState = liveState,
                onTogglePause = { KeepAppSessionService.togglePause(context) },
                onSelectMode = holder::setLiveMode,
                onSelectPattern = holder::setLivePattern,
                onToggleAutoLevel = holder::setLiveAutoLevel,
                onToggleVoiceCoach = holder::setLiveVoiceCoach,
                onSelectManualLevel = holder::setLiveManualLevel,
                onToggleDataCollectionMode = holder::setDataCollectionMode,
                onAddMarker = holder::addManualMarker,
                onSaveGroundTruth = { dir, steps, pos, act, env, note, markers ->
                    holder.saveGroundTruthJson(dir, steps, pos, act, env, note, markers)?.absolutePath
                }
            )
            1 -> SessionHistoryTab(modifier = Modifier.padding(innerPadding))
            2 -> AudioEngineTab(
                modifier = Modifier.padding(innerPadding),
                patterns = holder.patterns,
                telemetry = manualTelemetry,
                onSelectMode = { holder.manualAudioEngine.setMode(it) },
                onSelectPattern = { holder.manualAudioEngine.setPattern(it) },
                onSelectLevel = { holder.manualAudioEngine.setLevel(it) },
                onBpmChange = { holder.manualAudioEngine.setBpm(it) },
                onVolumeChange = { holder.manualAudioEngine.setVolume(it) },
                onMutedChange = { holder.manualAudioEngine.setMuted(it) },
                onAttenuatedChange = { holder.manualAudioEngine.setAttenuated(it) }
            )
            else -> BluetoothCalibrationTab(
                modifier = Modifier.padding(innerPadding),
                calibState = calibState,
                earSyncState = earSyncState,
                profileStore = holder.profileStore,
                onStartContactCalibration = holder::startContactCalibration,
                onStartTapCalibration = holder::startTapCalibration,
                onStartSpeakerCheck = holder::startSpeakerCheck,
                onStartBodyTapCheck = holder::startBodyTapCheck,
                onUserTap = { holder.calibrator.registerUserTap() },
                onCancelCalibration = { holder.calibrator.cancel() },
                onStartEarSync = holder::startEarSync,
                onStopEarSync = { holder.earSyncTester.stop() },
                onEarSyncAdvanceChange = { holder.earSyncTester.setAdvanceMs(it) }
            )
        }
    }
}
