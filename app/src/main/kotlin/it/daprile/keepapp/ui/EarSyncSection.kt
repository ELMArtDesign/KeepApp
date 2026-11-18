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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import it.daprile.keepapp.R
import it.daprile.keepapp.platform.audio.BtLatencyProfileStore
import it.daprile.keepapp.platform.audio.EarCheckLogEntry
import it.daprile.keepapp.platform.audio.EarSyncUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Sezione "Prova a orecchio" (altoparlante interno vs cuffia Bluetooth ogni 1 secondo).
 *
 * Compensazione di riferimento a Fine = 0 ms: `(savedBtLatencyMs - speakerXVeroMs)`, così
 * il click in cuffia e il click dell'altoparlante interno arrivano all'orecchio nello stesso istante.
 */
@Composable
fun EarSyncSection(
    activeDeviceName: String,
    savedBtLatencyMs: Float,
    speakerXVeroMs: Float,
    earSyncState: EarSyncUiState,
    profileStore: BtLatencyProfileStore,
    onStartEarSync: (Float) -> Unit,
    onStopEarSync: () -> Unit,
    onEarSyncAdvanceChange: (Float) -> Unit
) {
    val context = LocalContext.current
    var earCompensationOn by remember { mutableStateOf(true) }
    var earFineTuneMs by remember { mutableFloatStateOf(0f) }
    var earCheckLogs by remember { mutableStateOf(profileStore.loadEarCheckLogs()) }
    var earLogExportStatus by remember { mutableStateOf("") }

    val refAdvanceMs = (savedBtLatencyMs - speakerXVeroMs).coerceAtLeast(0f)
    val totalAppliedMs = (if (earCompensationOn) refAdvanceMs else 0f) + earFineTuneMs
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
                            String.format(
                                Locale.US,
                                "Attiva: %.1f ms (cuffia %.1f − X_vero %.1f ms)",
                                refAdvanceMs,
                                savedBtLatencyMs,
                                speakerXVeroMs
                            )
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
                        val newTotal = (if (checked) refAdvanceMs else 0f) + earFineTuneMs
                        if (earSyncState.isRunning) {
                            onEarSyncAdvanceChange(newTotal)
                        }
                    }
                )
            }

            Text(
                text = String.format(
                    Locale.US,
                    "Regolazione fine: %+.0f ms  |  Anticipo applicato: %.1f ms",
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
                    val newTotal = (if (earCompensationOn) refAdvanceMs else 0f) + snapped
                    if (earSyncState.isRunning) {
                        onEarSyncAdvanceChange(newTotal)
                    }
                },
                valueRange = -150f..150f,
                steps = 59
            )

            Button(
                onClick = {
                    if (earSyncState.isRunning) onStopEarSync() else onStartEarSync(totalAppliedMs)
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
                    color = if (routingWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text("Esito ascolto:", fontWeight = FontWeight.Medium)
            val saveOutcome: (String) -> Unit = { outcome ->
                val entry = EarCheckLogEntry(
                    timestampMs = System.currentTimeMillis(),
                    deviceName = activeDeviceName,
                    compensationOn = earCompensationOn,
                    fineTuneMs = earFineTuneMs,
                    totalAppliedMs = totalAppliedMs,
                    outcome = outcome
                )
                profileStore.addEarCheckLog(entry)
                earCheckLogs = profileStore.loadEarCheckLogs()
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { saveOutcome("Sento 1 colpo") }, modifier = Modifier.fillMaxWidth()) {
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
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val fullText = earCheckLogs.joinToString("\n") { formatLogEntry(it) }
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val appLabel = context.getString(R.string.app_name)
                            cm.setPrimaryClip(ClipData.newPlainText("$appLabel Prova a orecchio", fullText))
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
