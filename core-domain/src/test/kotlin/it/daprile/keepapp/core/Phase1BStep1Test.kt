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

import it.daprile.keepapp.core.cadence.CadenceEstimator
import it.daprile.keepapp.core.config.KeepAppConfig
import it.daprile.keepapp.core.format.PhonePosition
import it.daprile.keepapp.core.rhythm.RhythmPatternEngine
import org.testng.annotations.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Test unitari JVM per la Fase 1B — Step 1 (guardia anti-dimezzamento HAND + 6 pattern JSON).
 */
class Phase1BStep1Test {

    private fun loadPatternAsset(fileName: String): String {
        val candidates = listOf(
            File("app/src/main/assets/patterns/$fileName"),
            File("../app/src/main/assets/patterns/$fileName")
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("File pattern asset non trovato: $fileName")
        return file.readText()
    }

    @Test
    fun `tutti i 6 pattern JSON vengono caricati correttamente`() {
        val ids = listOf(
            "pulse_steady",
            "cadence_drive",
            "tribal_stride",
            "electro_stride",
            "rock_cadence",
            "march_focus"
        )
        for (id in ids) {
            val pat = RhythmPatternEngine.parseJson(loadPatternAsset("$id.json"))
            assertEquals(id, pat.id)
            assertEquals(4, pat.levels.size)
            assertTrue(pat.levels.all { it.hits.isNotEmpty() })
        }
    }

    @Test
    fun `guardia anti-dimezzamento in HAND impedisce crollo da 116 SPM a 58-68 BPM`() {
        val sampleRateHz = 104.0f
        val dtNs = (1_000_000_000.0 / sampleRateHz).toLong()
        val estimator = CadenceEstimator(KeepAppConfig())
        var nowNs = 1_000_000_000L

        // Fase 1 (6 s): camminata netta a 116 SPM (1.933 Hz)
        val stepFreq = 116.0 / 60.0
        for (i in 0 until (6 * sampleRateHz).toInt()) {
            val t = i / sampleRateHz
            val sig = 2.5f * sin(2.0 * PI * stepFreq * t).toFloat()
            estimator.onAccelerometer(nowNs, 0.4f, 9.81f + sig, 0.3f, PhonePosition.HAND)
            nowNs += dtNs
        }
        val lockedSpm = estimator.currentEstimate().cadenceSpm
        assertTrue(lockedSpm in 108f..124f, "Atteso ~116 SPM dopo fase 1, ottenuto $lockedSpm")

        // Fase 2 (5 s): forte componente di falcata dimezzata (58 BPM = 0.967 Hz) in mano
        val halfFreq = 58.0 / 60.0
        for (i in 0 until (5 * sampleRateHz).toInt()) {
            val t = i / sampleRateHz
            val sig = 2.6f * sin(2.0 * PI * halfFreq * t).toFloat() +
                0.8f * sin(2.0 * PI * stepFreq * t).toFloat()
            estimator.onAccelerometer(nowNs, 0.4f, 9.81f + sig, 0.3f, PhonePosition.HAND)
            nowNs += dtNs
        }

        val afterSpm = estimator.currentEstimate().cadenceSpm
        assertTrue(
            afterSpm >= 95f,
            "La guardia anti-dimezzamento in HAND doveva evitare il crollo a ~58-68 BPM, ottenuto $afterSpm"
        )
    }
}
