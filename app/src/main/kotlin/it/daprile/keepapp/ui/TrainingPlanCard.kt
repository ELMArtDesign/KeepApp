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
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.daprile.keepapp.core.session.TrainingMode
import it.daprile.keepapp.core.session.TrainingPlanConfig
import it.daprile.keepapp.platform.session.SessionHistoryStore
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrainingPlanCard(
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
                TrainingMode.ADAPTIVE_ANCHOR -> Text(
                    text = "Il ritmo si aggancia alla tua cadenza naturale e ti sostiene nei cali (D40).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TrainingMode.FIXED_TARGET -> {
                    Text("Target Fisso: ${plan.fixedTargetBpm.roundToInt()} BPM", fontWeight = FontWeight.Medium)
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
