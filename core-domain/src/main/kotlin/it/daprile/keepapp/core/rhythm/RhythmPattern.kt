/*
 * Copyright 2026 Adriano D'Aprile
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package it.daprile.keepapp.core.rhythm

/**
 * Strumenti percussivi sintetizzati dall'app (D41 — nessun file audio di terze parti).
 */
enum class DrumInstrument(val jsonKey: String) {
    /** Cassa ottimizzata per conduzione ossea (110–240 Hz + attacco 0,9–1,4 kHz) */
    KICK("kick"),
    /** Rullante sintetizzato (corpo ~210 Hz + rumore filtrato 1–4 kHz) */
    SNARE("snare"),
    /** Charleston / shaker ad alta frequenza (4–9 kHz) */
    HIHAT("hihat"),
    /** Accento di rinforzo / legnetto (usato per l'ancora elastica D40/D41 e metronomo) */
    ACCENT("accent"),
    /** Click del metronomo semplice (alternativa al pattern ritmico) */
    METRONOME_CLICK("metronome");

    companion object {
        fun fromJsonKey(key: String): DrumInstrument? =
            entries.find { it.jsonKey.equals(key.trim(), ignoreCase = true) }
    }
}

/**
 * Singolo colpo programmato all'interno di una suddivisione della battuta.
 * @param stepIndex Indice della suddivisione nella battuta (`0 .. stepsPerBar - 1`)
 * @param instrument Strumento da suonare
 * @param velocity Intensità relativa [0.0 .. 1.0]
 */
data class PatternHit(
    val stepIndex: Int,
    val instrument: DrumInstrument,
    val velocity: Float
)

/**
 * Un livello di intensità di un pattern ritmico (es. 0=Defaticamento, 1=Base, 2=Spinta, 3=Rinforzo).
 */
data class RhythmLevel(
    val levelIndex: Int,
    val name: String,
    val hits: List<PatternHit>
)

/**
 * Definizione completa di un pattern ritmico caricato da file JSON (`assets/patterns/` — D41).
 */
data class RhythmPattern(
    val id: String,
    val name: String,
    val description: String,
    val beatsPerBar: Int,
    val subdivisionsPerBeat: Int,
    val levels: List<RhythmLevel>
) {
    val stepsPerBar: Int get() = beatsPerBar * subdivisionsPerBeat

    fun levelOrClosest(levelIndex: Int): RhythmLevel {
        if (levels.isEmpty()) return RhythmLevel(0, "Empty", emptyList())
        return levels.find { it.levelIndex == levelIndex }
            ?: levels.minBy { kotlin.math.abs(it.levelIndex - levelIndex) }
    }
}
