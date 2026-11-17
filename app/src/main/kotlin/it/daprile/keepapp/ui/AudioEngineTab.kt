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
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.daprile.keepapp.core.audio.AudioMode
import it.daprile.keepapp.core.rhythm.RhythmPattern
import it.daprile.keepapp.platform.audio.AudioEngineTelemetry
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AudioEngineTab(
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
