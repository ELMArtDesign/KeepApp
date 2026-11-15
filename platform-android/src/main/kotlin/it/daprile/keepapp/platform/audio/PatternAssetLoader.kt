/*
 * Copyright 2026 Adriano D'Aprile
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package it.daprile.keepapp.platform.audio

import android.content.Context
import it.daprile.keepapp.core.rhythm.DrumInstrument
import it.daprile.keepapp.core.rhythm.PatternHit
import it.daprile.keepapp.core.rhythm.RhythmLevel
import it.daprile.keepapp.core.rhythm.RhythmPattern
import it.daprile.keepapp.core.rhythm.RhythmPatternEngine

/**
 * Caricatore condiviso dei pattern ritmici JSON dalla cartella `assets/patterns/`.
 */
object PatternAssetLoader {
    private const val PATTERNS_DIR = "patterns"

    fun loadAll(context: Context): List<RhythmPattern> {
        val assetManager = context.assets
        val files = assetManager.list(PATTERNS_DIR)
            ?.filter { it.endsWith(".json", ignoreCase = true) }
            ?.sorted()
            ?: emptyList()

        val loaded = files.mapNotNull { fileName ->
            runCatching {
                val json = assetManager.open("$PATTERNS_DIR/$fileName")
                    .bufferedReader()
                    .use { it.readText() }
                RhythmPatternEngine.parseJson(json)
            }.getOrNull()
        }
        return if (loaded.isNotEmpty()) loaded else listOf(fallbackPattern())
    }

    fun fallbackPattern(): RhythmPattern = RhythmPattern(
        id = "fallback",
        name = "Base 4/4",
        description = "Pattern di emergenza",
        beatsPerBar = 4,
        subdivisionsPerBeat = 4,
        levels = listOf(
            RhythmLevel(
                levelIndex = 1,
                name = "Base",
                hits = listOf(
                    PatternHit(0, DrumInstrument.KICK, 0.9f),
                    PatternHit(4, DrumInstrument.KICK, 0.8f),
                    PatternHit(8, DrumInstrument.KICK, 0.85f),
                    PatternHit(12, DrumInstrument.KICK, 0.8f)
                )
            )
        )
    )
}
