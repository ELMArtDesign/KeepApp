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
import it.daprile.keepapp.core.phase.PhaseTracker
import it.daprile.keepapp.core.position.PositionClassifier
import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Punto 5 — Test sintetici su compensazione ritardo di gruppo (`PhaseTracker`) e posizione `WRIST`.
 */
class WristAndPhaseSyntheticTests {

    @Test
    fun testPhaseTrackerGroupDelayCompensationAcrossCadences() {
        val fs = 104.05f
        val dtNs = (1_000_000_000.0 / fs).roundToLong()

        for (spm in listOf(90f, 120f, 150f, 180f)) {
            val config = KeepAppConfig(phaseOffsetArmMs = 0f)
            val estimator = CadenceEstimator(config)
            val tracker = PhaseTracker(config)
            val fStep = spm / 60.0
            val periodNs = (1_000_000_000.0 / fStep).toLong()
            val t0Ns = 1_000_000_000L

            val errorsMs = ArrayList<Float>()
            val totalSamples = (22.0 * fs).toInt()
            for (i in 0 until totalSamples) {
                val tSec = i / fs.toDouble()
                val tNs = t0Ns + i * dtNs
                // Picco vero della sinusoide a fase 2*pi*f*t = pi/2 + 2*pi*k -> t_peak = (k + 0.25) / fStep
                val az = (9.81 + 3.0 * sin(2.0 * PI * fStep * tSec)).toFloat()
                val cad = estimator.onAccelerometer(tNs, 0f, 0f, az, PhonePosition.ARM)
                val phase = tracker.onFilteredAccelSample(
                    timestampNs = tNs,
                    filteredAccel = cad.latestFilteredAccel,
                    currentCadenceSpm = cad.cadenceSpm,
                    cadenceConfidence = cad.confidence,
                    position = PhonePosition.ARM,
                    filterGroupDelayNs = cad.filterGroupDelayNs
                )
                if (tSec >= 8.0 && phase.justDetectedImpact && phase.lastFootstrikeNs > 0L) {
                    val estRelNs = phase.lastFootstrikeNs - t0Ns
                    val k = kotlin.math.round((estRelNs.toDouble() / periodNs.toDouble()) - 0.25).toLong()
                    val truePeakRelNs = ((k + 0.25) * periodNs.toDouble()).toLong()
                    val errMs = (estRelNs - truePeakRelNs) / 1_000_000f
                    if (abs(errMs) < 100f) {
                        errorsMs.add(errMs)
                    }
                }
            }

            assertTrue(errorsMs.isNotEmpty(), "Nessun impatto rilevato a $spm SPM")
            val meanErrMs = errorsMs.average().toFloat()
            assertTrue(
                abs(meanErrMs) <= 5.0f,
                "Errore medio di fase a $spm SPM fuori da ±5 ms: $meanErrMs ms"
            )
        }
    }

    @Test
    fun testWristPositionClassificationAndAntiHalvingGuard() {
        val config = KeepAppConfig()
        val classifier = PositionClassifier(config, initialPositionHint = PhonePosition.ARM)
        val estimator = CadenceEstimator(config)
        val fs = 104.05f
        val dtNs = (1_000_000_000.0 / fs).roundToLong()

        // 1. Segnale sintetico da polso: ampia oscillazione avambraccio (gy < 0.50, horizontalRatio >= 0.50, gyroTotalVar >= 1.2)
        var tNs = 1_000_000_000L
        var lastPos = PhonePosition.ARM
        for (i in 0 until 400) {
            val tSec = i / fs.toDouble()
            // Gravità laterale da polso (gx dominante, gy < 0.45, gz < 0.48)
            val ax = (8.5 + 1.5 * sin(2.0 * PI * 1.033 * tSec)).toFloat()
            val ay = (3.2 + 0.8 * sin(2.0 * PI * 2.067 * tSec)).toFloat()
            val az = (1.5 + 1.4 * sin(2.0 * PI * 2.067 * tSec)).toFloat()
            classifier.onAccelerometer(tNs, ax, ay, az)
            val gx = (1.3 * sin(2.0 * PI * 1.033 * tSec)).toFloat()
            val gy = (0.9 * sin(2.0 * PI * 1.033 * tSec + 0.7)).toFloat()
            val gz = (0.7 * sin(2.0 * PI * 2.067 * tSec)).toFloat()
            classifier.onGyroscope(tNs, gx, gy, gz)
            if (i % 10 == 0) {
                lastPos = classifier.evaluate().position
            }
            tNs += dtNs
        }
        assertEquals(lastPos, PhonePosition.WRIST, "PositionClassifier deve riconoscere WRIST su oscillazione avambraccio")

        // 2. Guardia anti-dimezzamento in WRIST a 124 SPM reali con forte sub-armonica a 62 SPM (oscillazione braccio 1x)
        val trueSpm = 124.0
        val fStep = trueSpm / 60.0
        tNs = 1_000_000_000L
        var lastEst = estimator.currentEstimate()
        for (i in 0 until (12.0 * fs).toInt()) {
            val tSec = i / fs.toDouble()
            val armSwing1x = 2.2 * sin(2.0 * PI * (fStep * 0.5) * tSec)
            val footImpact2x = 1.9 * sin(2.0 * PI * fStep * tSec)
            val az = (9.81 + armSwing1x + footImpact2x).toFloat()
            lastEst = estimator.onAccelerometer(tNs, 0.3f, 0.2f, az, PhonePosition.WRIST)
            tNs += dtNs
        }
        assertTrue(
            lastEst.cadenceSpm in 118f..130f,
            "Anti-dimezzamento WRIST: attesa cadenza ~124 SPM, stimata ${lastEst.cadenceSpm} SPM"
        )
    }
}
