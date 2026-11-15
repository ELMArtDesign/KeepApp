/*
 * Copyright 2026 Adriano D'Aprile
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package it.daprile.keepapp.audio

import android.content.Context
import it.daprile.keepapp.core.rhythm.RhythmPattern
import it.daprile.keepapp.core.rhythm.RhythmPatternEngine

/**
 * Carica i pattern ritmici JSON dalla cartella `assets/patterns/` di `:app`.
 */
object PatternRepository {
    private const val PATTERNS_DIR = "patterns"

    fun loadAll(context: Context): List<RhythmPattern> {
        val assetManager = context.assets
        val files = assetManager.list(PATTERNS_DIR)
            ?.filter { it.endsWith(".json", ignoreCase = true) }
            ?.sorted()
            ?: emptyList()

        return files.mapNotNull { fileName ->
            runCatching {
                val json = assetManager.open("$PATTERNS_DIR/$fileName")
                    .bufferedReader()
                    .use { it.readText() }
                RhythmPatternEngine.parseJson(json)
            }.getOrNull()
        }
    }
}
