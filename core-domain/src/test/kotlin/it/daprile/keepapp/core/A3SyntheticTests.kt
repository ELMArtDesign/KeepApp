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

import it.daprile.keepapp.core.audio.AudioClockEstimator
import it.daprile.keepapp.core.config.KeepAppConfig
import it.daprile.keepapp.core.format.BinaryFormat
import it.daprile.keepapp.core.format.BinaryRecord
import it.daprile.keepapp.core.replay.AccelSample
import it.daprile.keepapp.core.replay.FootstrikeReferenceDetector
import it.daprile.keepapp.core.replay.SessionReplayEngine
import org.testng.Assert.assertEquals
import org.testng.Assert.assertNotNull
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * Step A3.1 — Test sintetici su `AudioClockEstimator`, `FootstrikeReferenceDetector` e determinismo del replay.
 */
class A3SyntheticTests {

    @Test
    fun testAudioClockEstimatorDriftJitterAndOutliers() {
        val sr = 48_000
        val estimator = AudioClockEstimator(sampleRateHz = sr)
        val trueDriftPpm = 140.0
        val trueSlopeNs = (1_000_000_000.0 / sr) * (1.0 + trueDriftPpm / 1_000_000.0)
        val t0Ns = 5_000_000_000L
        val rng = Random(42)

        for (i in 1..90) {
            val framePos = i * 2400L // ogni 50 ms
            val jitterNs = ((rng.nextDouble() - 0.5) * 4_000_000.0).toLong() // ±2 ms
            val outlierNs = if (i == 45 || i == 70) 35_000_000L else 0L // +35 ms spike
            val tNs = t0Ns + (framePos * trueSlopeNs).toLong() + jitterNs + outlierNs
            estimator.updateFromTimestamp(framePos, tNs)
        }

        assertTrue(estimator.isLocked, "AudioClockEstimator deve agganciarsi dopo 90 campioni")
        assertTrue(estimator.residualErrorRmsMs < 0.50f, "Errore residuo atteso < 0.50 ms, trovato ${estimator.residualErrorRmsMs}")
        assertEquals(estimator.rejectedOutliers, 2, "I 2 outlier > 20 ms devono essere scartati")
        assertTrue(abs(estimator.estimatedSlopePpm - trueDriftPpm.toFloat()) < 35f, "Drift stimato fuori tolleranza: ${estimator.estimatedSlopePpm}")

        val testSample = 240_000L
        val outNs = estimator.outputTimeNsForSample(testSample)
        assertNotNull(outNs)
        val roundTripSample = estimator.sampleIndexForOutputTimeNs(outNs!!)
        assertNotNull(roundTripSample)
        assertTrue(abs(roundTripSample!! - testSample) <= 2L, "Round-trip sample mismatch: $roundTripSample vs $testSample")
    }

    @Test
    fun testFootstrikeReferenceDetectorKnownCadenceNoiseAndHarmonics() {
        val fs = 104.0f
        val dtNs = (1_000_000_000.0 / fs).toLong()
        val durationSec = 60.0
        val n = (durationSec * fs).toInt()
        val rng = Random(1234)

        fun buildSignal(cadenceSpm: Double): List<AccelSample> {
            val fStep = cadenceSpm / 60.0
            return List(n) { i ->
                val tSec = i / fs.toDouble()
                val fundamental = 3.2 * sin(2.0 * PI * fStep * tSec)
                val harmonic2 = 0.7 * sin(2.0 * PI * (2.0 * fStep) * tSec + 0.4)
                val noise = (rng.nextDouble() - 0.5) * 0.6
                val az = (9.81 + fundamental + harmonic2 + noise).toFloat()
                AccelSample(
                    timestampNs = 1_000_000_000L + i * dtNs,
                    ax = (0.2 * sin(2.0 * PI * fStep * tSec)).toFloat(),
                    ay = 0.1f,
                    az = az
                )
            }
        }

        // 160 SPM per 60 s -> attesi ~160 appoggi (±2% = 157..163)
        val runStrikes = FootstrikeReferenceDetector.detectFootstrikesNs(buildSignal(160.0))
        assertTrue(runStrikes.size in 157..163, "160 SPM: attesi ~160 appoggi, trovati ${runStrikes.size}")

        // 110 SPM per 60 s -> attesi ~110 appoggi (±2% = 108..112)
        val walkStrikes = FootstrikeReferenceDetector.detectFootstrikesNs(buildSignal(110.0))
        assertTrue(walkStrikes.size in 108..112, "110 SPM: attesi ~110 appoggi, trovati ${walkStrikes.size}")
    }

    @Test
    fun testReplayEngineIsDeterministic() {
        val tmpDir = Files.createTempDirectory("keepapp_det_test").toFile()
        try {
            val sessionDir = File(tmpDir, "session_synth_1").apply { mkdirs() }
            File(sessionDir, "manifest.json").writeText(
                """{"session_id":"session_synth_1","position":"arm","headphones_name":"MANKIW-X15","bt_latency_ms":120.6,"device_model":"TestPhone"}"""
            )
            File(sessionDir, "ground_truth.json").writeText(
                """{"steps":160,"interval":"session","position":"arm","activity":"run","environment":"outdoor"}"""
            )

            // Scrivi header + 60 s di accelerometro sintetico a 160 SPM in sensors.bin
            val binFile = File(sessionDir, "sensors.bin")
            val dtNs = 9_615_384L // ~104 Hz
            BufferedOutputStream(FileOutputStream(binFile)).use { bos ->
                bos.write(BinaryFormat.createFileHeader(flags = 0, monotonicStartNs = 1_000_000_000L, unixStartMs = 1_700_000_000_000L))
                var tNs = 1_000_000_000L
                val totalSamples = 60 * 104
                for (i in 0 until totalSamples) {
                    val tSec = i / 104.0
                    val az = (9.81 + 3.5 * sin(2.0 * PI * (160.0 / 60.0) * tSec)).toFloat()
                    bos.write(
                        BinaryRecord.ofFloats(
                            timestampNs = tNs,
                            recordType = BinaryFormat.TYPE_ACCEL,
                            f0 = 0.2f,
                            f1 = 0.1f,
                            f2 = az
                        ).bytes
                    )
                    tNs += dtNs
                }
            }

            val res1 = SessionReplayEngine.replaySessionDir(sessionDir, KeepAppConfig())
            val res2 = SessionReplayEngine.replaySessionDir(sessionDir, KeepAppConfig())

            assertEquals(res1.integratedCadenceSteps, res2.integratedCadenceSteps, 0.0001f)
            assertEquals(res1.cadenceErrorPct, res2.cadenceErrorPct)
            assertEquals(res1.phaseErrorMedianAbsMs, res2.phaseErrorMedianAbsMs, 0.0001f)
            assertEquals(res1.phaseErrorP90AbsMs, res2.phaseErrorP90AbsMs, 0.0001f)
            assertEquals(res1.phaseErrorMeanSignedMs, res2.phaseErrorMeanSignedMs, 0.0001f)
            assertEquals(res1.targetBpmJumpsOver5Pct, res2.targetBpmJumpsOver5Pct)
            assertEquals(res1.rows.size, res2.rows.size)
            assertEquals(res1.toCsvString(), res2.toCsvString())
        } finally {
            tmpDir.deleteRecursively()
        }
    }
}
