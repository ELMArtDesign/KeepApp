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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * Schermata/dialog "Dati di riferimento" a fine sessione: scrive `ground_truth.json`
 * direttamente nella cartella della sessione senza modificare file a mano.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GroundTruthDialog(
    defaultPositionCode: String,
    markerRelTimesSec: List<Float>,
    onDismiss: () -> Unit,
    onSave: (steps: Int, position: String, activity: String, environment: String, note: String) -> Unit
) {
    var stepsText by remember { mutableStateOf("") }
    var position by remember { mutableStateOf(defaultPositionCode) }
    var activity by remember { mutableStateOf("run") }
    var environment by remember { mutableStateOf("outdoor") }
    var note by remember { mutableStateOf("") }

    val parsedSteps = stepsText.trim().toIntOrNull()
    val intervalDesc = when {
        markerRelTimesSec.size >= 4 -> String.format(
            java.util.Locale.US,
            "4 marker -> interval=\"markers\", sosta [%.1f s .. %.1f s]",
            markerRelTimesSec[1],
            markerRelTimesSec[2]
        )
        markerRelTimesSec.size >= 2 -> "${markerRelTimesSec.size} marker -> interval=\"markers\" (senza sosta)"
        else -> "${markerRelTimesSec.size} marker -> interval=\"session\""
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Dati di riferimento", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = intervalDesc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )

                OutlinedTextField(
                    value = stepsText,
                    onValueChange = { ch -> stepsText = ch.filter { it.isDigit() } },
                    label = { Text("Passi contati (es. 200)") },
                    supportingText = { Text("Suggerimento: conta i passi del piede destro × 2") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Posizione del telefono", fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "arm" to "Braccio",
                        "hand" to "Mano",
                        "waist" to "Vita",
                        "pocket" to "Tasca"
                    ).forEach { (code, label) ->
                        FilterChip(
                            selected = position == code,
                            onClick = { position = code },
                            label = { Text(label) }
                        )
                    }
                }

                Text("Attività", fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = activity == "walk",
                        onClick = { activity = "walk" },
                        label = { Text("Camminata") }
                    )
                    FilterChip(
                        selected = activity == "run",
                        onClick = { activity = "run" },
                        label = { Text("Corsa") }
                    )
                }

                Text("Ambiente", fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = environment == "outdoor",
                        onClick = { environment = "outdoor" },
                        label = { Text("Aperto") }
                    )
                    FilterChip(
                        selected = environment == "indoor",
                        onClick = { environment = "indoor" },
                        label = { Text("Chiuso") }
                    )
                }

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note (facoltativo)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (parsedSteps != null && parsedSteps > 0) {
                        onSave(parsedSteps, position, activity, environment, note)
                    }
                },
                enabled = (parsedSteps != null && parsedSteps > 0)
            ) {
                Text("Salva ground_truth.json")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annulla")
            }
        }
    )
}
