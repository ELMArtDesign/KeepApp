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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.daprile.keepapp.core.audio.CalibrationMethod
import it.daprile.keepapp.core.audio.CalibrationSummary
import it.daprile.keepapp.platform.audio.BtLatencyProfileStore
import it.daprile.keepapp.platform.audio.BtOutputDevice
import it.daprile.keepapp.platform.audio.CalibrationUiState
import it.daprile.keepapp.platform.audio.SavedBtLatencyProfile
import java.util.Locale

@Composable
fun CalibrationResultCard(
    res: CalibrationSummary,
    calibState: CalibrationUiState,
    savedPhoneRMicMs: Float?,
    autoSavedMsg: String,
    activeDevice: BtOutputDevice,
    onSaveProfile: (SavedBtLatencyProfile) -> Unit
) {
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
            val effectiveRMic = calibState.rMicMedianMs ?: savedPhoneRMicMs ?: 0f
            val isContactSummary = !calibState.isSpeakerCheck && !calibState.isBodyTapCheck &&
                res.method == CalibrationMethod.CONTACT_IMPULSE
            val netLatencyMs = if (isContactSummary) {
                (res.medianLatencyMs - effectiveRMic).coerceAtLeast(0f)
            } else {
                res.medianLatencyMs
            }
            Text(
                text = buildString {
                    append(
                        String.format(
                            Locale.US,
                            "Mediana: %.1f ms (MAD ±%.1f ms)\nMedia: %.1f ms  |  Dev. Std: %.1f ms\nCampioni validi: %d / %d",
                            res.medianLatencyMs,
                            res.madMs,
                            res.meanLatencyMs,
                            res.stdDevMs,
                            res.validSamples,
                            res.totalSamples
                        )
                    )
                    if (isContactSummary) {
                        append(
                            String.format(
                                Locale.US,
                                "\nR_mic telefono: %.1f ms → Netto salvato: %.1f ms",
                                effectiveRMic,
                                netLatencyMs
                            )
                        )
                    }
                },
                fontFamily = FontFamily.Monospace
            )
            if (autoSavedMsg.isNotEmpty() && isContactSummary) {
                Text(
                    text = autoSavedMsg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (!calibState.isSpeakerCheck && !calibState.isBodyTapCheck && !isContactSummary) {
                Button(
                    onClick = {
                        onSaveProfile(
                            SavedBtLatencyProfile(
                                deviceKey = activeDevice.key,
                                deviceName = activeDevice.displayName,
                                latencyMs = netLatencyMs,
                                stdDevMs = res.stdDevMs,
                                method = res.method,
                                calibratedAtMs = System.currentTimeMillis()
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("SALVA PROFILO PER ${activeDevice.displayName}")
                }
            }
        }
    }
}

@Composable
fun LatencyComparisonSummaryCard(
    calibState: CalibrationUiState,
    savedPhoneRMicMs: Float?,
    savedPhoneXVeroMs: Float?,
    profileStore: BtLatencyProfileStore
) {
    val displayRMic = calibState.rMicMedianMs ?: savedPhoneRMicMs
    if (displayRMic == null && calibState.speakerXRawMs == null && calibState.btLoopbackMs == null) return

    val rMic = displayRMic
    val xRaw = calibState.speakerXRawMs
    val xVero = if (xRaw != null) xRaw - (rMic ?: 0f) else savedPhoneXVeroMs
    val btLoop = calibState.btLoopbackMs
    val earFusionMs = profileStore.loadEarCheckLogs()
        .firstOrNull { it.outcome.contains("1 colpo") }?.totalAppliedMs ?: 85.3f
    val btOrecchio = if (xVero != null) earFusionMs + xVero else null
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
                    appendLine("AudioRecord.getTimestamp: ${if (calibState.recHwTimestampActive) "ATTIVO" else "n/d"}")
                    appendLine("R_mic (colpo scocca): ${rMic?.let { String.format(Locale.US, "%.1f ms", it) } ?: "da misurare"}")
                    appendLine("Ritardo FIFO IMU (D36): ${calibState.imuFifoDelayMs?.let { String.format(Locale.US, "%.1f ms", it) } ?: "da misurare"}")
                    appendLine("X_raw (altoparlante): ${xRaw?.let { String.format(Locale.US, "%.1f ms", it) } ?: "da misurare"}")
                    appendLine("X_vero (altoparlante): ${xVero?.let { String.format(Locale.US, "%.1f ms", it) } ?: "n/d"}")
                    appendLine("BT da loopback: ${btLoop?.let { String.format(Locale.US, "%.1f ms", it) } ?: "da misurare"}")
                    appendLine("BT da orecchio: ${btOrecchio?.let { String.format(Locale.US, "%.1f ms", it) } ?: "n/d"}")
                    append("Scarto (loopback - orecchio): ${scarto?.let { String.format(Locale.US, "%+.1f ms", it) } ?: "n/d"}")
                },
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
