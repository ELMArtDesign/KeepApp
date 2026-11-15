/*
 * Copyright 2026 Adriano D'Aprile
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package it.daprile.keepapp.core

import it.daprile.keepapp.core.audio.AudioMode
import it.daprile.keepapp.core.audio.BeatScheduler
import it.daprile.keepapp.core.config.KeepAppConfig
import it.daprile.keepapp.core.rhythm.DrumInstrument
import it.daprile.keepapp.core.rhythm.RhythmPatternEngine
import org.testng.annotations.Test
import java.io.File
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Test unitari JVM per la Milestone 2 (`RhythmPatternEngine` + `BeatScheduler`).
 */
class RhythmAndSchedulerM2Test {

    private fun loadPatternAsset(fileName: String): String {
        val candidates = listOf(
            File("app/src/main/assets/patterns/$fileName"),
            File("../app/src/main/assets/patterns/$fileName")
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("File pattern asset non trovato: $fileName (cwd=${File(".").absolutePath})")
        return file.readText()
    }

    @Test
    fun `parsing dei 3 pattern JSON ufficiali in assets`() {
        val ids = listOf("pulse_steady", "cadence_drive", "tribal_stride")
        for (id in ids) {
            val pattern = RhythmPatternEngine.parseJson(loadPatternAsset("$id.json"))
            assertEquals(id, pattern.id)
            assertEquals(4, pattern.beatsPerBar)
            assertEquals(4, pattern.subdivisionsPerBeat)
            assertEquals(4, pattern.levels.size, "Pattern $id deve avere 4 livelli")
            for (lvl in pattern.levels) {
                assertTrue(lvl.hits.isNotEmpty(), "Livello ${lvl.levelIndex} di $id vuoto")
            }
        }
    }

    @Test
    fun `cambio livello in RhythmPatternEngine avviene solo sul downbeat`() {
        val pattern = RhythmPatternEngine.parseJson(loadPatternAsset("pulse_steady.json"))
        val engine = RhythmPatternEngine(pattern, initialLevelIndex = 1)

        // Downbeat iniziale (step 0) -> livello 1
        val (step0Hits, changed0) = engine.hitsForStep(0)
        assertFalse(changed0)
        assertTrue(step0Hits.any { it.instrument == DrumInstrument.KICK })
        assertEquals(1, engine.activeLevelIndex)

        // Richiesta cambio a livello 3 durante la battuta (prima dello step 1)
        engine.requestLevel(3)
        assertEquals(3, engine.pendingLevelIndex)

        // Step 1..15 devono mantenere il livello 1
        for (s in 1 until pattern.stepsPerBar) {
            val (_, changedMid) = engine.hitsForStep(s)
            assertFalse(changedMid, "Non deve cambiare livello allo step $s")
            assertEquals(1, engine.activeLevelIndex)
        }

        // Al successivo step 0 (downbeat) il livello passa a 3
        val (nextDownbeatHits, changedDownbeat) = engine.hitsForStep(0)
        assertTrue(changedDownbeat)
        assertEquals(3, engine.activeLevelIndex)
        assertTrue(nextDownbeatHits.any { it.instrument == DrumInstrument.ACCENT })
    }

    @Test
    fun `BeatScheduler limita la correzione di fase a max 15 ms per battito`() {
        val pattern = RhythmPatternEngine.parseJson(loadPatternAsset("pulse_steady.json"))
        val engine = RhythmPatternEngine(pattern, initialLevelIndex = 1)
        val config = KeepAppConfig()
        val scheduler = BeatScheduler(sampleRateHz = 48_000, config = config, patternEngine = engine)

        scheduler.mode = AudioMode.RHYTHM_PATTERN
        scheduler.reset(startSampleIndex = 0L)

        val baseTimeNs = 1_000_000_000_000L
        val sampleRate = 48_000.0
        val outputTimeForSample: (Long) -> Long = { s ->
            baseTimeNs + ((s / sampleRate) * 1_000_000_000.0).toLong()
        }

        // A 120 BPM un beat dura 500 ms (24.000 campioni).
        // Primo pull per inizializzare lo stato su beat 0 (sample 0).
        val firstEvents = scheduler.pullEventsForWindow(0L, 4_800L, outputTimeForSample)
        assertTrue(firstEvents.isNotEmpty())

        // Impostiamo target footstrike spostato di +60 ms rispetto al prossimo beat nominale (500 ms -> 560 ms)
        // con alta confidenza (0.95) e latenza BT = 0 ms, offset posizione = 0 ms.
        scheduler.updateTargets(
            bpm = 120f,
            predictedNextFootstrikeNs = baseTimeNs + 560_000_000L,
            phaseConfidence = 0.95f,
            btLatencyMs = 0f,
            positionOffsetMs = 0f
        )

        // Avanziamo di ~600 ms in finestre da 10 ms (480 campioni)
        val mainBeats = mutableListOf<it.daprile.keepapp.core.audio.ScheduledAudioEvent>()
        var s = 4_800L
        while (s < 32_000L) {
            val evs = scheduler.pullEventsForWindow(s, s + 480L, outputTimeForSample)
            mainBeats.addAll(evs.filter { it.isMainBeat })
            s += 480L
        }

        assertTrue(mainBeats.isNotEmpty(), "Deve schedulare il main beat successivo")
        for (beat in mainBeats) {
            assertTrue(
                abs(beat.phaseCorrectionUs) <= 15_005,
                "Correzione di fase ${beat.phaseCorrectionUs} us supera il limite di 15 ms"
            )
        }
    }

    @Test
    fun `BeatScheduler in bassa confidenza mantiene il tempo senza correzioni di fase`() {
        val pattern = RhythmPatternEngine.parseJson(loadPatternAsset("pulse_steady.json"))
        val engine = RhythmPatternEngine(pattern, initialLevelIndex = 1)
        val scheduler = BeatScheduler(sampleRateHz = 48_000, patternEngine = engine)

        scheduler.mode = AudioMode.SIMPLE_METRONOME
        scheduler.reset(startSampleIndex = 0L)

        val baseTimeNs = 2_000_000_000_000L
        val outputTimeForSample: (Long) -> Long = { s ->
            baseTimeNs + ((s / 48_000.0) * 1_000_000_000.0).toLong()
        }

        // Confidenza bassa (0.15f < soglia) -> nessuna correzione di fase, solo tempo a 150 BPM (400 ms = 19.200 campioni)
        scheduler.updateTargets(
            bpm = 150f,
            predictedNextFootstrikeNs = baseTimeNs + 999_000_000L,
            phaseConfidence = 0.15f,
            btLatencyMs = 150f,
            positionOffsetMs = 0f
        )

        val events = mutableListOf<it.daprile.keepapp.core.audio.ScheduledAudioEvent>()
        var s = 0L
        while (s < 48_000L) {
            events.addAll(scheduler.pullEventsForWindow(s, s + 480L, outputTimeForSample))
            s += 480L
        }

        assertTrue(events.size >= 2)
        assertEquals(DrumInstrument.ACCENT, events[0].instrument)
        assertEquals(DrumInstrument.METRONOME_CLICK, events[1].instrument)
        assertTrue(events.all { it.phaseCorrectionUs == 0 })
        val deltaSamples = events[1].sampleIndex - events[0].sampleIndex
        assertTrue(abs(deltaSamples - 19_200L) <= 2L, "Intervallo atteso ~19200 campioni, ottenuto $deltaSamples")
    }
}
