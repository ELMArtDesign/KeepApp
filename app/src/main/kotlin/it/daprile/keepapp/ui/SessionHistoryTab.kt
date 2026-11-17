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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import it.daprile.keepapp.core.session.CadenceTracePoint
import it.daprile.keepapp.core.session.SessionSummaryRecord
import it.daprile.keepapp.platform.session.SessionHistoryStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun SessionHistoryTab(
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

@Composable
fun SummaryCard(
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

@Composable
fun CadenceTraceChart(tracePoints: List<CadenceTracePoint>) {
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
