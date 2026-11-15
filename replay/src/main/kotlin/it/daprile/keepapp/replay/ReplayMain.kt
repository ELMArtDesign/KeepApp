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
import it.daprile.keepapp.core.replay.SessionReplayEngine
import java.io.File
import kotlin.system.exitProcess

/**
 * Strumento JVM da riga di comando (`:replay` — Fase 1A Milestone 1) che esegue gli algoritmi
 * di `:core-domain` su una cartella di sessione (o su una cartella contenente più sessioni),
 * producendo:
 * - `replay_output.csv` (tempo, cadenza stimata, confidenza, posizione, appoggio previsto, stato ancora, soste)
 * - `replay_summary.txt` (riepilogo testuale completo)
 *
 * Uso:
 *   .\gradlew.bat :replay:run --args="C:\percorso\session_YYYYMMDD_HHMMSS_pos"
 */
fun main(args: Array<String>) {
    val targetPath = args.firstOrNull() ?: System.getenv("KEEPAPP_SESSIONS_DIR")
    if (targetPath.isNullOrBlank()) {
        println("Uso: gradlew :replay:run --args=\"<percorso_cartella_sessione>\"")
        println("Oppure imposta la variabile d'ambiente KEEPAPP_SESSIONS_DIR.")
        return
    }

    val root = File(targetPath)
    if (!root.exists()) {
        System.err.println("[ERRORE] Percorso non trovato: ${root.absolutePath}")
        exitProcess(1)
    }

    val sessionDirs = if (File(root, "sensors.bin").exists()) {
        listOf(root)
    } else {
        root.listFiles { f -> f.isDirectory && File(f, "sensors.bin").exists() }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    if (sessionDirs.isEmpty()) {
        System.err.println("[ERRORE] Nessun file sensors.bin trovato in: ${root.absolutePath}")
        exitProcess(1)
    }

    val config = KeepAppConfig()
    for (dir in sessionDirs) {
        val report = SessionReplayEngine.replaySessionDir(dir, config)
        val csvFile = File(dir, "replay_output.csv")
        val summaryFile = File(dir, "replay_summary.txt")

        csvFile.writeText(report.toCsvString(), Charsets.UTF_8)
        summaryFile.writeText(report.toTextSummary(), Charsets.UTF_8)

        println(report.toTextSummary())
        println("[OK] CSV salvato in     : ${csvFile.absolutePath}")
        println("[OK] Riepilogo salvato in: ${summaryFile.absolutePath}")
        println()
    }
}
