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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import it.daprile.keepapp.core.audio.CalibrationMethod
import it.daprile.keepapp.platform.audio.BtLatencyProfileStore
import it.daprile.keepapp.platform.audio.CalibrationUiState
import it.daprile.keepapp.platform.audio.EarSyncUiState
import it.daprile.keepapp.platform.audio.SavedBtLatencyProfile
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun BluetoothCalibrationTab(
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
    var savedPhoneRMicMs by remember { mutableStateOf(profileStore.loadPhoneMicDelayMs()) }
    var savedPhoneXVeroMs by remember { mutableStateOf(profileStore.loadPhoneSpeakerDelayMs()) }
    var autoSavedMsg by remember { mutableStateOf("") }
    var pendingSpeakerCheck by remember { mutableStateOf(false) }
    var pendingBodyTapCheck by remember { mutableStateOf(false) }

    LaunchedEffect(calibState.rMicMedianMs) {
        calibState.rMicMedianMs?.let { measured ->
            profileStore.savePhoneMicDelayMs(measured)
            savedPhoneRMicMs = measured
        }
    }

    LaunchedEffect(calibState.speakerXRawMs, savedPhoneRMicMs) {
        calibState.speakerXRawMs?.let { xRaw ->
            val xVero = (xRaw - (savedPhoneRMicMs ?: 0f)).coerceAtLeast(0f)
            profileStore.savePhoneSpeakerDelayMs(xVero)
            savedPhoneXVeroMs = xVero
        }
    }

    // Salvataggio automatico della calibrazione a contatto (mediana loopback - R_mic)
    LaunchedEffect(calibState.summary, calibState.isSpeakerCheck, calibState.isBodyTapCheck) {
        val res = calibState.summary ?: return@LaunchedEffect
        if (!calibState.isSpeakerCheck && !calibState.isBodyTapCheck &&
            res.method == CalibrationMethod.CONTACT_IMPULSE
        ) {
            val rMic = calibState.rMicMedianMs ?: savedPhoneRMicMs ?: 0f
            val netMs = (res.medianLatencyMs - rMic).coerceAtLeast(0f)
            val profile = SavedBtLatencyProfile(
                deviceKey = activeDevice.key,
                deviceName = activeDevice.displayName,
                latencyMs = netMs,
                stdDevMs = res.stdDevMs,
                method = res.method,
                calibratedAtMs = System.currentTimeMillis()
            )
            profileStore.save(profile)
            savedProfile = profile
            autoSavedMsg = String.format(
                Locale.US,
                "Profilo salvato automaticamente: %.1f ms (%.1f − %.1f R_mic)",
                netMs,
                res.medianLatencyMs,
                rMic
            )
        }
    }

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
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                        ) {
                            Text("BATTI QUI A TEMPO!", fontWeight = FontWeight.Bold)
                        }
                    }
                    OutlinedButton(
                        onClick = onCancelCalibration,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Annulla")
                    }
                }

                Text(text = calibState.statusMessage, style = MaterialTheme.typography.bodyMedium)
                calibState.errorMessage?.let { errMsg ->
                    Text(
                        text = errMsg,
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
            CalibrationResultCard(
                res = res,
                calibState = calibState,
                savedPhoneRMicMs = savedPhoneRMicMs,
                autoSavedMsg = autoSavedMsg,
                activeDevice = activeDevice,
                onSaveProfile = { profile ->
                    profileStore.save(profile)
                    savedProfile = profile
                }
            )
        }

        LatencyComparisonSummaryCard(
            calibState = calibState,
            savedPhoneRMicMs = savedPhoneRMicMs,
            savedPhoneXVeroMs = savedPhoneXVeroMs,
            profileStore = profileStore
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
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

        EarSyncSection(
            activeDeviceName = activeDevice.displayName,
            savedBtLatencyMs = savedProfile?.latencyMs ?: 120.6f,
            speakerXVeroMs = savedPhoneXVeroMs ?: 0f,
            earSyncState = earSyncState,
            profileStore = profileStore,
            onStartEarSync = onStartEarSync,
            onStopEarSync = onStopEarSync,
            onEarSyncAdvanceChange = onEarSyncAdvanceChange
        )
    }
}
