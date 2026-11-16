/*
 * Copyright 2026 Adriano D'Aprile
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package it.daprile.keepapp.replay

import it.daprile.keepapp.core.config.KeepAppConfig
import it.daprile.keepapp.core.replay.ReplayConfigAndTruthLoader
import it.daprile.keepapp.core.replay.SessionReplayEngine
import org.testng.Assert.assertTrue
import org.testng.SkipException
import org.testng.annotations.Test
import java.io.File
import kotlin.math.abs

/**
 * Step A3.2 — Test di regressione locale sulle sessioni reali (`KEEPAPP_SESSIONS_DIR`).
 *
 * Le soglie di accettazione 1A (`|err cadenza| < 2%`, `fase mediana < 30 ms`) vengono fatte valere
 * esclusivamente sulle sessioni con `ground_truth.json` aventi `environment == "outdoor"` e
 * `activity == "walk" | "run"`. Le sessioni indoor / `in_place` vengono riportate a scopo diagnostico
 * senza bloccare il test.
 */
class ReplayRegressionTest {

    @Test
    fun testAcceptanceThresholdsOnOutdoorWalkAndRunSessions() {
        val rawDir = System.getenv("KEEPAPP_SESSIONS_DIR")?.trim().orEmpty()
        if (rawDir.isEmpty()) {
            throw SkipException("KEEPAPP_SESSIONS_DIR non impostata: test di regressione locale saltato.")
        }
        val sessionsRoot = File(rawDir)
        if (!sessionsRoot.isDirectory) {
            throw SkipException("KEEPAPP_SESSIONS_DIR non esiste: ${sessionsRoot.absolutePath}")
        }

        val sessionDirs = sessionsRoot.listFiles { f ->
            f.isDirectory && File(f, "sensors.bin").isFile && File(f, "manifest.json").isFile
        }?.sortedBy { it.name }.orEmpty()

        if (sessionDirs.isEmpty()) {
            throw SkipException("Nessuna sessione valida trovata in ${sessionsRoot.absolutePath}")
        }

        val failures = ArrayList<String>()
        var outdoorChecked = 0
        val config = KeepAppConfig()

        for (dir in sessionDirs) {
            val res = SessionReplayEngine.replaySessionDir(dir, config)
            val gt = ReplayConfigAndTruthLoader.loadGroundTruth(dir)
            val isOutdoorWalkOrRun = gt != null &&
                gt.environment.equals("outdoor", ignoreCase = true) &&
                (gt.activity.equals("walk", ignoreCase = true) || gt.activity.equals("run", ignoreCase = true))

            println(res.toTextSummary())
            if (isOutdoorWalkOrRun) {
                outdoorChecked++
                val cadErr = res.cadenceErrorPct
                if (cadErr != null && abs(cadErr) >= 2.0f) {
                    failures.add("${dir.name}: errore cadenza ${"%.2f".format(cadErr)}% >= 2.0%")
                }
                if (res.phaseErrorMedianAbsMs >= 30.0f) {
                    failures.add("${dir.name}: errore fase mediano ${"%.2f".format(res.phaseErrorMedianAbsMs)} ms >= 30.0 ms")
                }
            } else {
                println("[ReplayRegression] -> Diagnostica (non bloccante): env=${gt?.environment}, activity=${gt?.activity}")
            }
        }

        println("[ReplayRegression] Sessioni outdoor walk/run verificate con soglie 1A: $outdoorChecked / ${sessionDirs.size}")
        assertTrue(failures.isEmpty(), "Violazioni soglie 1A:\n${failures.joinToString("\n")}")
    }
}
