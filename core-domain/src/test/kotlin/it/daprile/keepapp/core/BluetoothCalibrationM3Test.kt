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

import it.daprile.keepapp.core.audio.BluetoothCalibrationMath
import it.daprile.keepapp.core.audio.CalibrationMethod
import org.testng.annotations.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Test unitari JVM per gli algoritmi di calibrazione Bluetooth della Milestone 3.
 */
class BluetoothCalibrationM3Test {

    @Test
    fun `rileva fronte d attacco impulso sopra soglia SNR e ignora silenzio`() {
        val silence = ShortArray(4800) { 30 }
        val floor = BluetoothCalibrationMath.computeRms(silence)
        assertNull(
            BluetoothCalibrationMath.detectImpulseOnsetSample(
                pcmWindow = silence,
                noiseFloorRms = floor
            )
        )

        val signal = ShortArray(4800) { 25 }
        // Impulso che parte al campione 1200 e raggiunge il picco al 1215
        signal[1200] = 4000
        signal[1205] = 9000
        signal[1215] = 15000
        signal[1240] = 5000

        val onset = BluetoothCalibrationMath.detectImpulseOnsetSample(
            pcmWindow = signal,
            noiseFloorRms = 30f
        )
        assertNotNull(onset)
        assertTrue(onset in 1200..1210, "Fronte attacco atteso vicino a 1200, trovato $onset")
    }

    @Test
    fun `calibrazione batti a tempo stima latenza reale e scarta warmup e outlier`() {
        val baseNs = 10_000_000_000L
        val periodNs = 500_000_000L // 120 BPM
        val trueLatencyNs = 215_000_000L // 215 ms

        val beats = (0 until 12).map { i -> baseNs + i * periodNs }
        // L'utente batte a tempo con ritardo reale di ~215 ms (più piccolo jitter ±8 ms)
        // Aggiungiamo anche 1 tap sballato durante i primi 2 beat (warmup) e 1 outlier
        val taps = mutableListOf<Long>()
        taps.add(beats[0] + 50_000_000L) // warmup (scartato)
        taps.add(beats[1] + 90_000_000L) // warmup (scartato)
        for (i in 2 until 12) {
            val jitterNs = ((i % 3) - 1) * 6_000_000L
            taps.add(beats[i] + trueLatencyNs + jitterNs)
        }
        taps[5] = beats[5] + 410_000_000L // outlier simulato

        val summary = BluetoothCalibrationMath.computeTapToBeatLatency(
            beatOutputTimesNs = beats,
            tapTimesNs = taps,
            beatPeriodMs = 500f,
            warmupBeatsToSkip = 2
        )

        assertNotNull(summary)
        assertEquals(CalibrationMethod.TAP_TO_BEAT, summary.method)
        assertTrue(
            abs(summary.medianLatencyMs - 215f) <= 10f,
            "Mediana attesa ~215 ms, ottenuta ${summary.medianLatencyMs} ms"
        )
        assertTrue(summary.validSamples in 8..10)
    }

    @Test
    fun `summarizeLatencies filtra outlier singolo su 5 impulsi a contatto`() {
        val samples = listOf(224f, 226f, 225f, 480f, 223f)
        val summary = BluetoothCalibrationMath.summarizeLatencies(
            latenciesMs = samples,
            totalAttempts = 5,
            method = CalibrationMethod.CONTACT_IMPULSE
        )
        assertNotNull(summary)
        assertEquals(4, summary.validSamples)
        assertTrue(abs(summary.medianLatencyMs - 224.5f) <= 1f)
        assertTrue(summary.stdDevMs < 2.0f)
    }
}
