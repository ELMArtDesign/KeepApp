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

import it.daprile.keepapp.core.anchor.AnchorState
import it.daprile.keepapp.core.anchor.ElasticAnchor
import it.daprile.keepapp.core.cadence.CadenceEstimator
import it.daprile.keepapp.core.config.KeepAppConfig
import it.daprile.keepapp.core.format.ActivityKind
import it.daprile.keepapp.core.format.BinaryFormat
import it.daprile.keepapp.core.format.BinaryRecord
import it.daprile.keepapp.core.format.PhonePosition
import it.daprile.keepapp.core.phase.PhaseTracker
import it.daprile.keepapp.core.position.PositionClassifier
import it.daprile.keepapp.core.replay.SessionReplayEngine
import it.daprile.keepapp.core.session.SessionRunState
import it.daprile.keepapp.core.session.SessionStateMachine
import it.daprile.keepapp.core.stop.StopDetector
import org.testng.SkipException
import org.testng.annotations.Test
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Suite di test unitari JVM per `:core-domain` (Fase 1A — Milestone 1).
 *
 * Verifica:
 * 1. `CadenceEstimator` (D38): errore < 2% su cadenze note (115 SPM camminata, 165 SPM corsa, 180 SPM corsa),
 *    disambiguazione dell'armonica in tasca/mano e blocco del BPM sotto soglia di confidenza (rumore).
 * 2. `PositionClassifier` (D21): distinzione tra Vita (`WAIST`), Tasca (`POCKET`), Mano (`HAND`) e Braccio (`ARM`).
 * 3. `PhaseTracker` (D39): stima del prossimo appoggio del piede con errore < 25 ms.
 * 4. `ElasticAnchor` (D40): aggancio mediana dopo 24 s, rinforzo su calo breve (< 20 s, > 3%),
 *    adattamento graduale (<= 1 BPM / 10 s) su calo prolungato (> 60 s) e limiti ±15%.
 * 5. `StopDetector` & `SessionStateMachine` (D42, D22): rilevamento sosta in <= 3,5 s, attenuazione a -20 dB,
 *    ripresa dopo 4 passi consecutivi e ri-aggancio dell'ancora se la sosta supera 2 minuti.
 * 6. `SessionReplayEngine`: test end-to-end su file `sensors.bin` sintetico temporaneo + test di regressione
 *    sulle sessioni reali se la variabile d'ambiente `KEEPAPP_SESSIONS_DIR` è impostata.
 */
class CoreDomainM1Test {

    private val sampleRateHz = 104.05f
    private val dtNs = (1_000_000_000.0 / sampleRateHz.toDouble()).toLong()

    @Test
    fun testCadenceEstimatorAccuracyWalkAndRun() {
        val targetCadences = listOf(116.0f, 164.0f, 182.0f)

        for (trueSpm in targetCadences) {
            val estimator = CadenceEstimator(KeepAppConfig())
            val freqHz = trueSpm / 60.0f
            var nowNs = 1_000_000_000L
            val totalSeconds = 12
            val totalSamples = (totalSeconds * sampleRateHz).toInt()

            var lastEstimate = estimator.currentEstimate()
            for (i in 0 until totalSamples) {
                val tSec = i / sampleRateHz
                // Segnale realistico: gravità 9,81 + fondamentale del passo + 2a armonica d'impatto
                val impactWave = 3.2f * sin(2.0 * PI * freqHz * tSec).toFloat() +
                        1.1f * cos(4.0 * PI * freqHz * tSec).toFloat()
                val ay = 9.81f + impactWave
                lastEstimate = estimator.onAccelerometer(nowNs, 0.4f, ay, 0.3f, PhonePosition.ARM)
                nowNs += dtNs
            }

            val relErrorPct = abs(lastEstimate.cadenceSpm - trueSpm) / trueSpm * 100.0f
            assertTrue(
                lastEstimate.isLockedOrValid,
                "La stima deve essere valida per $trueSpm SPM (confidenza=${lastEstimate.confidence})"
            )
            assertTrue(
                relErrorPct < 1.5f,
                "Errore cadenza per $trueSpm SPM troppo alto: stimato=${lastEstimate.cadenceSpm} SPM (errore=${relErrorPct}%)"
            )
        }
    }

    @Test
    fun testCadenceHarmonicDisambiguationInPocket() {
        // In tasca (su una sola coscia), durante una corsa a 168 SPM (2,8 Hz) c'è una forte componente
        // alla frequenza di falcata (84 SPM = 1,4 Hz) più l'impatto di ogni passo a 168 SPM (2,8 Hz).
        val trueStepSpm = 168.0f
        val stepFreqHz = trueStepSpm / 60.0f
        val strideFreqHz = stepFreqHz / 2.0f

        val estimator = CadenceEstimator(KeepAppConfig())
        var nowNs = 1_000_000_000L
        val totalSamples = (12 * sampleRateHz).toInt()

        var lastEstimate = estimator.currentEstimate()
        for (i in 0 until totalSamples) {
            val tSec = i / sampleRateHz
            // Componente sub-armonica di falcata (1,4 Hz) leggermente più ampia della fondamentale (2,8 Hz)
            val signal = 3.0f * sin(2.0 * PI * strideFreqHz * tSec).toFloat() +
                    2.4f * sin(2.0 * PI * stepFreqHz * tSec).toFloat()
            lastEstimate = estimator.onAccelerometer(
                timestampNs = nowNs,
                ax = 0.5f,
                ay = 9.81f + signal,
                az = 0.5f,
                position = PhonePosition.POCKET
            )
            nowNs += dtNs
        }

        val errorPct = abs(lastEstimate.cadenceSpm - trueStepSpm) / trueStepSpm * 100.0f
        assertTrue(
            errorPct < 2.0f,
            "In tasca deve scegliere l'armonica del passo ($trueStepSpm SPM) e non la falcata dimezzata: ottenuto ${lastEstimate.cadenceSpm} SPM"
        )
    }

    @Test
    fun testCadenceHoldsValueUnderLowConfidenceNoise() {
        val estimator = CadenceEstimator(KeepAppConfig())
        val trueSpm = 160.0f
        val freqHz = trueSpm / 60.0f
        var nowNs = 1_000_000_000L

        // 1. Primi 8 secondi di corsa pulita a 160 SPM
        for (i in 0 until (8 * sampleRateHz).toInt()) {
            val tSec = i / sampleRateHz
            val ay = 9.81f + 3.5f * sin(2.0 * PI * freqHz * tSec).toFloat()
            estimator.onAccelerometer(nowNs, 0f, ay, 0f, PhonePosition.ARM)
            nowNs += dtNs
        }
        val lockedEstimate = estimator.currentEstimate()
        assertTrue(abs(lockedEstimate.cadenceSpm - trueSpm) < 2.0f)

        // 2. Transizione di 1,5 s in cui il segnale entra nel rumore a bassa confidenza
        val rng = Random(42)
        for (i in 0 until (2 * sampleRateHz).toInt()) {
            val noise = (rng.nextFloat() - 0.5f) * 0.25f
            estimator.onAccelerometer(nowNs, noise, 9.81f + noise, noise, PhonePosition.ARM)
            nowNs += dtNs
        }
        val heldDuringNoiseStart = estimator.currentEstimate()
        assertEquals(0.0f, heldDuringNoiseStart.confidence, 0.01f, "La confidenza deve scendere a zero nel rumore")

        // 3. Successivi 5 secondi di rumore casuale sotto soglia: il BPM deve restare IDENTICO (congelato)
        var afterNoiseEstimate = heldDuringNoiseStart
        for (i in 0 until (5 * sampleRateHz).toInt()) {
            val noise = (rng.nextFloat() - 0.5f) * 0.25f
            afterNoiseEstimate = estimator.onAccelerometer(nowNs, noise, 9.81f + noise, noise, PhonePosition.ARM)
            nowNs += dtNs
        }

        assertEquals(
            heldDuringNoiseStart.cadenceSpm,
            afterNoiseEstimate.cadenceSpm,
            0.0001f,
            "Sotto soglia di confidenza il BPM stimato deve restare bloccato all'ultimo valore valido"
        )
        assertTrue(abs(afterNoiseEstimate.cadenceSpm - trueSpm) < 1.0f)
    }

    @Test
    fun testPositionClassifierDistinguishesPositions() {
        val config = KeepAppConfig()

        // 1. Test WAIST (Vita): gravità verticale, oscillazione dinamica presente ma giroscopio molto basso
        val waistClassifier = PositionClassifier(config, PhonePosition.ARM)
        var nowNs = 1_000_000_000L
        for (i in 0 until 350) {
            val t = i / sampleRateHz
            val ay = 9.81f + 2.5f * sin(2.0 * PI * 2.5 * t).toFloat()
            waistClassifier.onAccelerometer(nowNs, 0.2f, ay, 0.2f)
            waistClassifier.onGyroscope(nowNs, 0.15f * sin(2.0 * PI * 2.5 * t).toFloat(), 0.1f, 0.1f)
            nowNs += dtNs
        }
        assertEquals(PhonePosition.WAIST, waistClassifier.evaluate().position)

        // 2. Test POCKET (Tasca): forte oscillazione pendolare della coscia sul giroscopio
        val pocketClassifier = PositionClassifier(config, PhonePosition.ARM)
        for (i in 0 until 350) {
            val t = i / sampleRateHz
            val ay = 9.0f + 3.0f * sin(2.0 * PI * 2.6 * t).toFloat()
            pocketClassifier.onAccelerometer(nowNs, 1.0f, ay, 1.5f)
            pocketClassifier.onGyroscope(nowNs, 2.4f * sin(2.0 * PI * 1.3 * t).toFloat(), 0.4f, 0.3f)
            nowNs += dtNs
        }
        assertEquals(PhonePosition.POCKET, pocketClassifier.evaluate().position)

        // 3. Test HAND (Mano): telefono più orizzontale (forte componente Z) e giroscopio attivo
        val handClassifier = PositionClassifier(config, PhonePosition.ARM)
        for (i in 0 until 350) {
            val t = i / sampleRateHz
            val az = 8.2f + 2.2f * sin(2.0 * PI * 2.6 * t).toFloat()
            handClassifier.onAccelerometer(nowNs, 2.5f, 3.5f, az)
            handClassifier.onGyroscope(nowNs, 0.9f * sin(2.0 * PI * 1.3 * t).toFloat(), 0.8f, 0.9f)
            nowNs += dtNs
        }
        assertEquals(PhonePosition.HAND, handClassifier.evaluate().position)
    }

    @Test
    fun testPhaseTrackerNextFootstrikePrediction() {
        val config = KeepAppConfig()
        val tracker = PhaseTracker(config)
        val spm = 160.0f // periodo = 375 ms
        val periodNs = (60_000_000_000.0 / spm).toLong()
        val freqHz = spm / 60.0f

        var nowNs = 1_000_000_000L
        var lastPhase = tracker.onFilteredAccelSample(nowNs, 0f, spm, 0.9f, PhonePosition.WAIST)

        for (i in 0 until (6 * sampleRateHz).toInt()) {
            val tSec = i / sampleRateHz
            // Cosinusoidale con picchi positivi esatti a t = 0, T, 2T, 3T...
            val filtered = 3.0f * cos(2.0 * PI * freqHz * tSec).toFloat()
            lastPhase = tracker.onFilteredAccelSample(nowNs, filtered, spm, 0.9f, PhonePosition.WAIST)
            nowNs += dtNs
        }

        assertTrue(lastPhase.phaseConfidence >= 0.5f, "La confidenza di fase deve essere alta su segnale periodico")
        assertTrue(lastPhase.predictedNextFootstrikeNs > nowNs - dtNs, "Il prossimo appoggio deve essere nel futuro")
        val distanceToNextStrikeNs = lastPhase.predictedNextFootstrikeNs - lastPhase.lastFootstrikeNs
        assertTrue(
            abs(distanceToNextStrikeNs - periodNs) < 20_000_000L,
            "Il prossimo appoggio previsto deve distare un periodo (~375 ms) dall'ultimo appoggio"
        )
    }

    @Test
    fun testElasticAnchorLockReinforceAndProlongedDecay() {
        val config = KeepAppConfig(anchorLockStableSeconds = 24.0f)
        val anchor = ElasticAnchor(config)
        var nowNs = 1_000_000_000L
        val stepNs = 500_000_000L // aggiornamenti ogni 0,5 s

        // 1. Primi 25 s stabili a 160 SPM -> deve agganciare initialLockedBpm = 160 BPM
        var snap = anchor.currentSnapshot()
        for (k in 0..50) {
            snap = anchor.update(nowNs, cadenceSpm = 160.0f, cadenceConfidence = 0.85f, isStopped = false)
            nowNs += stepNs
        }
        assertEquals(AnchorState.LOCKED, snap.state)
        assertEquals(160.0f, snap.initialLockedBpm, 0.5f)
        assertEquals(160.0f, snap.targetBpm, 0.5f)

        // 2. Rallentamento breve (10 s) a 152 SPM (-5%, oltre la soglia del 3%):
        //    Deve attivare REINFORCING ma mantenere targetBpm == 160.0 BPM!
        for (k in 1..20) {
            snap = anchor.update(nowNs, cadenceSpm = 152.0f, cadenceConfidence = 0.85f, isStopped = false)
            nowNs += stepNs
        }
        assertEquals(AnchorState.REINFORCING, snap.state)
        assertTrue(snap.reinforcementActive)
        assertEquals(160.0f, snap.targetBpm, 0.01f, "Durante il rallentamento breve l'obiettivo non deve scendere")

        // 3. Rallentamento prolungato oltre i 60 s (altri 60 s a 130 SPM):
        //    Dopo 60 s totali entra in ADAPTING_DOWN, scendendo al massimo di 1 BPM ogni 10 s
        //    e fermandosi comunque al limite del -15% (160 * 0.85 = 136 BPM)
        for (k in 1..120) {
            snap = anchor.update(nowNs, cadenceSpm = 130.0f, cadenceConfidence = 0.85f, isStopped = false)
            nowNs += stepNs
        }
        assertEquals(AnchorState.ADAPTING_DOWN, snap.state)
        // Nei ~10 s oltre la soglia dei 60 s può essere sceso di ~1 BPM (da 160 a ~159 BPM)
        assertTrue(snap.targetBpm in 158.5f..159.5f, "Discesa graduale attesa ~159 BPM, trovato: ${snap.targetBpm}")

        // Continuando per altri 300 s a 130 SPM, non deve MAI scendere sotto il -15% (136 BPM)
        for (k in 1..600) {
            snap = anchor.update(nowNs, cadenceSpm = 130.0f, cadenceConfidence = 0.85f, isStopped = false)
            nowNs += stepNs
        }
        assertEquals(136.0f, snap.targetBpm, 0.1f, "Il target non deve mai superare il limite del -15% (136 BPM)")
    }

    @Test
    fun testStopDetectorAndRelockAfterTwoMinutes() {
        val config = KeepAppConfig()
        val stopDetector = StopDetector(config)
        var nowNs = 1_000_000_000L

        // Soggetto in corsa con passi ogni 0,4 s e GPS a 3,0 m/s
        for (i in 0 until 10) {
            stopDetector.onGpsSpeed(nowNs, 3.0f)
            stopDetector.onStepEvent(nowNs)
            nowNs += 400_000_000L
        }
        assertEquals(false, stopDetector.evaluate(nowNs).isStopped)

        // Si ferma al semaforo: velocità GPS = 0,2 m/s (< 0,5 m/s) e nessun passo per 3,5 s
        stopDetector.onGpsSpeed(nowNs, 0.2f)
        for (i in 1..7) {
            nowNs += 500_000_000L
            stopDetector.onGpsSpeed(nowNs, 0.2f)
            stopDetector.evaluate(nowNs)
        }
        val stoppedSnap = stopDetector.evaluate(nowNs)
        assertTrue(stoppedSnap.isStopped, "La sosta deve essere rilevata entro 3,5 s")

        // Dopo 1,5 s in sosta, il volume deve essere sceso a -20 dB (gainLinear = 0.1)
        for (i in 1..3) {
            nowNs += 500_000_000L
            stopDetector.evaluate(nowNs)
        }
        val attenuatedSnap = stopDetector.evaluate(nowNs)
        assertEquals(-20.0f, attenuatedSnap.currentGainDb, 0.1f)
        assertEquals(0.10f, attenuatedSnap.currentGainLinear, 0.01f)

        // Resta fermo per oltre 2 minuti (125 s)
        nowNs += 125_000_000_000L
        stopDetector.evaluate(nowNs)

        // Riparte con 4 passi consecutivi -> deve uscire dalla sosta e segnalare il ri-aggancio dell'ancora!
        for (step in 1..4) {
            nowNs += 450_000_000L
            stopDetector.onStepEvent(nowNs)
        }
        val resumedSnap = stopDetector.evaluate(nowNs)
        assertEquals(false, resumedSnap.isStopped, "Deve uscire dalla sosta dopo 4 passi consecutivi")
        assertTrue(
            resumedSnap.justResumedRequiringAnchorRelock,
            "Dopo una sosta > 2 minuti deve richiedere il nuovo aggancio dell'ancora (D42/D22)"
        )
    }

    @Test
    fun testReplayHarnessOnSyntheticBinarySession() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "keepapp_test_replay_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        try {
            val binFile = File(tempDir, "sensors.bin")
            val manifestFile = File(tempDir, "manifest.json")
            val startNs = 10_000_000_000L

            manifestFile.writeText(
                """
                {
                  "session_folder": "${tempDir.name}",
                  "position_code": "waist",
                  "activity_code": "run",
                  "headphones_name": "MANKIW-X15",
                  "monotonic_start_ns": $startNs,
                  "duration_seconds": 30.0,
                  "model": "Redmi Note 11 Pro 5G",
                  "has_barometer": false,
                  "pure_survival_mode": false
                }
                """.trimIndent()
            )

            FileOutputStream(binFile).use { fos ->
                fos.write(BinaryFormat.createFileHeader(flags = 0, monotonicStartNs = startNs, unixStartMs = 1700000000000L))
                var nowNs = startNs
                val spm = 170.0f
                val freqHz = spm / 60.0f
                val totalSamples = (30 * sampleRateHz).toInt()
                var nextStepNs = startNs

                for (i in 0 until totalSamples) {
                    val tSec = i / sampleRateHz
                    val ay = 9.81f + 3.4f * cos(2.0 * PI * freqHz * tSec).toFloat()
                    fos.write(
                        BinaryRecord.ofVector3D(
                            timestampNs = nowNs,
                            recordType = BinaryFormat.TYPE_ACCEL,
                            accuracy = 3,
                            x = 0.2f,
                            y = ay,
                            z = 0.2f
                        ).bytes
                    )
                    if (nowNs >= nextStepNs) {
                        fos.write(
                            BinaryRecord.ofFloats(
                                timestampNs = nowNs,
                                recordType = BinaryFormat.TYPE_STEP,
                                f0 = 1f
                            ).bytes
                        )
                        nextStepNs += (60_000_000_000.0 / spm).toLong()
                    }
                    nowNs += dtNs
                }
            }

            val report = SessionReplayEngine.replaySessionDir(tempDir)
            assertTrue(report.rows.isNotEmpty(), "Il replay deve produrre righe CSV")
            assertTrue(abs(report.medianCadenceSpm - 170.0f) < 2.0f, "Cadenza mediana attesa ~170 SPM, trovata ${report.medianCadenceSpm}")
            assertTrue(abs(report.initialLockedAnchorBpm - 170.0f) < 2.0f, "Ancora attesa ~170 BPM, trovata ${report.initialLockedAnchorBpm}")
            assertTrue(report.medianFootstrikeErrorMs < 30.0f, "Errore mediano di fase atteso < 30 ms, trovato ${report.medianFootstrikeErrorMs} ms")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testRealSessionsFromEnvVarIfPresent() {
        val envDir = System.getenv("KEEPAPP_SESSIONS_DIR")
        if (envDir.isNullOrBlank()) {
            throw SkipException("Variabile KEEPAPP_SESSIONS_DIR non impostata: test di regressione su sessioni reali saltato.")
        }
        val root = File(envDir)
        if (!root.exists()) {
            throw SkipException("Cartella KEEPAPP_SESSIONS_DIR non trovata (${root.absolutePath}): test saltato.")
        }
        val sessions = if (File(root, "sensors.bin").exists()) {
            listOf(root)
        } else {
            root.listFiles { f -> f.isDirectory && File(f, "sensors.bin").exists() }?.toList() ?: emptyList()
        }
        if (sessions.isEmpty()) {
            throw SkipException("Nessuna sessione con sensors.bin in ${root.absolutePath}: test saltato.")
        }
        for (s in sessions) {
            val report = SessionReplayEngine.replaySessionDir(s)
            assertTrue(report.totalRecordsRead > 0, "La sessione reale ${s.name} deve contenere record")
        }
    }
}
