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
import it.daprile.keepapp.core.format.PhonePosition
import it.daprile.keepapp.core.replay.ReplayConfigAndTruthLoader
import it.daprile.keepapp.core.replay.SessionReplayEngine
import it.daprile.keepapp.core.replay.SessionReplayReport
import java.io.File
import java.util.Locale
import kotlin.system.exitProcess

/**
 * CLI JVM del Replay Harness (`:replay` — Step A2).
 *
 * Uso:
 * - Singola sessione: `--session <cartella_sessione> --out <cartella_esterna> [--config <config.json>]`
 * - Cartella multi-sessione: `--dir <cartella_sessioni> --out <cartella_esterna> [--config <config.json>]`
 */
fun main(args: Array<String>) {
    val parsed = parseArgs(args)
    val sessionPath = parsed["--session"]
    val dirPath = parsed["--dir"]
    val outPath = parsed["--out"]
    val configPath = parsed["--config"]

    if (outPath.isNullOrBlank() || (sessionPath.isNullOrBlank() && dirPath.isNullOrBlank())) {
        printUsage()
        exitProcess(1)
    }

    val outDir = File(outPath).canonicalFile
    val repoRoot = findRepoRoot(File(".").canonicalFile)
    if (repoRoot != null && isInsideDir(outDir, repoRoot)) {
        System.err.println(
            "ERRORE: --out non può puntare dentro la cartella del repository (${repoRoot.absolutePath}). " +
                "Indica una cartella esterna (es. C:\\temp\\keepapp_replay)."
        )
        exitProcess(1)
    }
    outDir.mkdirs()

    val baseConfig = KeepAppConfig()
    val configFile = configPath?.let { File(it) }
    val config = ReplayConfigAndTruthLoader.applyConfigOverride(baseConfig, configFile)
    val configHash = ReplayConfigAndTruthLoader.configHashSha256(config)

    val sessionDirs = collectSessionDirs(sessionPath, dirPath)
    if (sessionDirs.isEmpty()) {
        System.err.println("ERRORE: Nessuna sessione valida trovata (richiesto sensors.bin + manifest.json).")
        exitProcess(1)
    }

    val totalStartMs = System.currentTimeMillis()
    val reports = ArrayList<SessionReplayReport>(sessionDirs.size)
    for (dir in sessionDirs) {
        val report = SessionReplayEngine.replaySessionDir(dir, config)
        reports.add(report)
        val csvFile = File(outDir, "${report.manifest.sessionFolder}.csv")
        csvFile.writeText(report.toCsvString(), Charsets.UTF_8)
        println(report.toTextSummary())
    }
    val totalElapsedMs = System.currentTimeMillis() - totalStartMs

    val mdContent = buildMarkdownSummary(reports, config, configHash, totalElapsedMs)
    File(outDir, "riepilogo.md").writeText(mdContent, Charsets.UTF_8)

    val jsonContent = buildJsonSummary(reports, configHash, totalElapsedMs)
    File(outDir, "riepilogo.json").writeText(jsonContent, Charsets.UTF_8)

    println("Output salvato in: ${outDir.absolutePath}")
    println(" - riepilogo.md, riepilogo.json e ${reports.size} file CSV")

    val failed = reports.filter { it.groundTruth?.steps != null && !it.passesAcceptanceThresholds }
    if (failed.isNotEmpty()) {
        System.err.println(
            "ATTENZIONE: ${failed.size} sessioni con ground truth non rispettano le soglie 1A: " +
                failed.joinToString { it.manifest.sessionFolder }
        )
        exitProcess(2)
    }
}

private fun parseArgs(args: Array<String>): Map<String, String> {
    val map = LinkedHashMap<String, String>()
    var i = 0
    while (i < args.size) {
        val k = args[i]
        if (k.startsWith("--") && i + 1 < args.size) {
            map[k] = args[i + 1]
            i += 2
        } else {
            i++
        }
    }
    return map
}

private fun collectSessionDirs(sessionPath: String?, dirPath: String?): List<File> {
    val result = ArrayList<File>()
    if (!sessionPath.isNullOrBlank()) {
        val f = File(sessionPath)
        if (File(f, "sensors.bin").exists()) result.add(f)
    }
    if (!dirPath.isNullOrBlank()) {
        val root = File(dirPath)
        if (File(root, "sensors.bin").exists()) {
            result.add(root)
        } else if (root.isDirectory) {
            root.walkTopDown()
                .filter { it.isDirectory && File(it, "sensors.bin").exists() && File(it, "manifest.json").exists() }
                .sortedBy { it.name }
                .forEach { result.add(it) }
        }
    }
    return result.distinctBy { it.canonicalPath }
}

private fun findRepoRoot(start: File): File? {
    var cur: File? = start
    while (cur != null) {
        if (File(cur, "settings.gradle.kts").exists() || File(cur, ".git").exists()) {
            return cur
        }
        cur = cur.parentFile
    }
    return null
}

private fun isInsideDir(candidate: File, parent: File): Boolean {
    val c = candidate.canonicalPath
    val p = parent.canonicalPath
    return c == p || c.startsWith(p + File.separator)
}

private fun buildMarkdownSummary(
    reports: List<SessionReplayReport>,
    config: KeepAppConfig,
    configHash: String,
    totalElapsedMs: Long
): String = buildString {
    appendLine("# Riepilogo Replay Harness KeepApp (`Step A2`)")
    appendLine()
    appendLine("- **Sessioni analizzate:** ${reports.size}")
    appendLine("- **Tempo totale di elaborazione:** ${totalElapsedMs} ms")
    appendLine("- **Hash configurazione (`KeepAppConfig`):** `$configHash`")
    appendLine()
    appendLine("> **Nota metodologica (appoggio indiretto):** con il telefono al braccio (`ARM`) l'appoggio stimato dall'IMU offline (`FootstrikeReferenceDetector`) è indiretto; la conferma finale dell'allineamento ritmico resta la validazione a orecchio (altoparlante vs cuffia).")
    appendLine()
    appendLine("## Tabella Sessioni")
    appendLine()
    appendLine("| Sessione | Durata (s) | Err. Cadenza | Err. StepDet | Fase Med / P90 / Segno (ms) | Aggancio (s) | Soste (False/Manc.) | Salti >5% (Stima/Target) | Conf. Bassa (%) | Posizione Stimata (Cambi) | Soglie 1A |")
    appendLine("| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :--- | :---: |")
    for (r in reports) {
        val cadErr = r.cadenceErrorPct?.let { String.format(Locale.US, "%+.2f%%", it) } ?: "n/d"
        val sysErr = r.systemStepDetectorErrorPct?.let { String.format(Locale.US, "%+.2f%%", it) } ?: "n/d"
        val phaseStr = String.format(Locale.US, "%.1f / %.1f / %+.1f", r.phaseErrorMedianAbsMs, r.phaseErrorP90AbsMs, r.phaseErrorMeanSignedMs)
        val lockStr = r.lockTimeSeconds?.let { String.format(Locale.US, "%.1f", it) } ?: "n/d"
        val stopsStr = "${r.totalStopsDetected} (${r.falseStopsCount}/${r.missedStopsCount?.toString() ?: "n/d"})"
        val jumpsStr = "${r.estimatedCadenceJumpsOver5Pct} / ${r.targetBpmJumpsOver5Pct}"
        val lowConfStr = String.format(Locale.US, "%.1f%%", r.lowConfidenceMovementPct)
        val posStr = PhonePosition.entries.joinToString(" ") { p ->
            String.format(Locale.US, "%s:%.0f%%", p.code, r.positionDistributionPct[p] ?: 0f)
        } + " (${r.positionChangesCount})"
        val passStr = if (r.groundTruth?.steps != null) {
            if (r.passesAcceptanceThresholds) "OK" else "FAIL"
        } else "n/d"
        appendLine(
            String.format(
                Locale.US,
                "| `%s` | %.1f | %s | %s | %s | %s | %s | %s | %s | %s | %s |",
                r.manifest.sessionFolder,
                r.durationSeconds,
                cadErr,
                sysErr,
                phaseStr,
                lockStr,
                stopsStr,
                jumpsStr,
                lowConfStr,
                posStr,
                passStr
            )
        )
    }
    appendLine()
    appendLine("## Parametri Utilizzati (`hash=$configHash`)")
    appendLine("```text")
    appendLine(config.toString())
    appendLine("```")
}

private fun buildJsonSummary(
    reports: List<SessionReplayReport>,
    configHash: String,
    totalElapsedMs: Long
): String = buildString {
    appendLine("{")
    appendLine("  \"config_hash\": \"$configHash\",")
    appendLine("  \"processing_time_ms\": $totalElapsedMs,")
    appendLine("  \"sessions\": [")
    reports.forEachIndexed { idx, r ->
        val comma = if (idx < reports.lastIndex) "," else ""
        appendLine("    {")
        appendLine("      \"session_id\": \"${r.manifest.sessionFolder}\",")
        appendLine(String.format(Locale.US, "      \"duration_seconds\": %.3f,", r.durationSeconds))
        appendLine("      \"reference_steps\": ${r.groundTruth?.steps ?: "null"},")
        appendLine(String.format(Locale.US, "      \"integrated_cadence_steps\": %.2f,", r.integratedCadenceSteps))
        appendLine("      \"cadence_error_pct\": ${r.cadenceErrorPct?.let { String.format(Locale.US, "%.3f", it) } ?: "null"},")
        appendLine("      \"system_step_detector_error_pct\": ${r.systemStepDetectorErrorPct?.let { String.format(Locale.US, "%.3f", it) } ?: "null"},")
        appendLine(String.format(Locale.US, "      \"phase_error_median_abs_ms\": %.3f,", r.phaseErrorMedianAbsMs))
        appendLine(String.format(Locale.US, "      \"phase_error_p90_abs_ms\": %.3f,", r.phaseErrorP90AbsMs))
        appendLine(String.format(Locale.US, "      \"phase_error_mean_signed_ms\": %.3f,", r.phaseErrorMeanSignedMs))
        appendLine("      \"lock_time_seconds\": ${r.lockTimeSeconds?.let { String.format(Locale.US, "%.3f", it) } ?: "null"},")
        appendLine("      \"false_stops_count\": ${r.falseStopsCount},")
        appendLine("      \"missed_stops_count\": ${r.missedStopsCount ?: "null"},")
        appendLine("      \"estimated_cadence_jumps_over_5pct\": ${r.estimatedCadenceJumpsOver5Pct},")
        appendLine("      \"target_bpm_jumps_over_5pct\": ${r.targetBpmJumpsOver5Pct},")
        appendLine(String.format(Locale.US, "      \"low_confidence_movement_pct\": %.2f,", r.lowConfidenceMovementPct))
        appendLine("      \"position_changes_count\": ${r.positionChangesCount},")
        appendLine("      \"passes_acceptance_thresholds\": ${r.passesAcceptanceThresholds}")
        appendLine("    }$comma")
    }
    appendLine("  ]")
    appendLine("}")
}

private fun printUsage() {
    println(
        """
        KeepApp Replay Harness (Step A2)
        Uso:
          --session <cartella_sessione>   Analizza una singola sessione
          --dir <cartella_sessioni>       Analizza tutte le sottocartelle di sessione
          --out <cartella_esterna>        OBBLIGATORIO (deve essere fuori dal repository)
          --config <file.json>            Facoltativo: sovrascrive campi di KeepAppConfig
        """.trimIndent()
    )
}
