/*
 * Copyright 2026 Adriano D'Aprile
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package it.daprile.keepapp.core.replay

import it.daprile.keepapp.core.anchor.AnchorState
import it.daprile.keepapp.core.config.KeepAppConfig
import it.daprile.keepapp.core.format.BinaryFormat
import it.daprile.keepapp.core.format.BinarySessionReader
import it.daprile.keepapp.core.format.ParsedManifestSummary
import it.daprile.keepapp.core.format.PhonePosition
import it.daprile.keepapp.core.session.SessionFrameSnapshot
import it.daprile.keepapp.core.session.SessionStateMachine
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * Singola riga del file CSV prodotto dal Replay Harness (`:replay` — M1).
 */
data class ReplayCsvRow(
    val timeSeconds: Float,
    val estimatedCadenceSpm: Float,
    val confidence: Float,
    val position: PhonePosition,
    val predictedNextFootstrikeSeconds: Float,
    val anchorState: AnchorState,
    val initialAnchorBpm: Float,
    val targetBpm: Float,
    val reinforcementActive: Boolean,
    val isStopped: Boolean,
    val gainDb: Float,
    val totalSteps: Long
)

/**
 * Riepilogo completo di un'esecuzione del Replay Harness su una sessione.
 */
data class SessionReplayReport(
    val manifest: ParsedManifestSummary,
    val totalRecordsRead: Int,
    val durationSeconds: Float,
    val meanCadenceSpm: Float,
    val medianCadenceSpm: Float,
    val initialLockedAnchorBpm: Float,
    val finalTargetBpm: Float,
    val dominantPosition: PhonePosition,
    val totalStepDetectorEvents: Int,
    val totalEstimatedSteps: Long,
    val totalStopsDetected: Int,
    val medianFootstrikeErrorMs: Float,
    val rows: List<ReplayCsvRow>
) {
    /**
     * Genera il contenuto CSV completo pronto per essere salvato su disco e letto da Python.
     */
    fun toCsvString(): String {
        val sb = StringBuilder(rows.size * 80 + 128)
        sb.appendLine("time_s,cadence_spm,confidence,position,predicted_footstrike_s,anchor_state,anchor_locked_bpm,target_bpm,reinforcement,is_stopped,gain_db,total_steps")
        for (r in rows) {
            sb.appendLine(
                String.format(
                    Locale.US,
                    "%.3f,%.2f,%.3f,%s,%.3f,%s,%.2f,%.2f,%d,%d,%.1f,%d",
                    r.timeSeconds,
                    r.estimatedCadenceSpm,
                    r.confidence,
                    r.position.code,
                    r.predictedNextFootstrikeSeconds,
                    r.anchorState.name,
                    r.initialAnchorBpm,
                    r.targetBpm,
                    if (r.reinforcementActive) 1 else 0,
                    if (r.isStopped) 1 else 0,
                    r.gainDb,
                    r.totalSteps
                )
            )
        }
        return sb.toString()
    }

    /**
     * Genera il riepilogo testuale formattato in italiano.
     */
    fun toTextSummary(): String = buildString {
        appendLine("==========================================================================")
        appendLine(" KEEPAPP FASE 1A (M1) — RIEPILOGO REPLAY HARNESS: ${manifest.sessionFolder}")
        appendLine("==========================================================================")
        appendLine(" Dispositivo         : ${manifest.deviceModel}")
        appendLine(" Posizione dichiarata: ${manifest.position.labelIt} (${manifest.position.code})")
        appendLine(" Posizione stimata   : ${dominantPosition.labelIt} (${dominantPosition.code})")
        appendLine(" Attività dichiarata : ${manifest.activityKind.labelIt} (${manifest.activityKind.code})")
        appendLine(String.format(Locale.US, " Durata analizzata   : %.2f s (%d record da 32 byte)", durationSeconds, totalRecordsRead))
        appendLine("--------------------------------------------------------------------------")
        appendLine(String.format(Locale.US, " Cadenza media valida: %.2f SPM (Mediana: %.2f SPM)", meanCadenceSpm, medianCadenceSpm))
        appendLine(String.format(Locale.US, " BPM agganciato (D40): %.2f BPM -> BPM obiettivo finale: %.2f BPM", initialLockedAnchorBpm, finalTargetBpm))
        appendLine(String.format(Locale.US, " Passi StepDetector  : %d | Passi totali stimati: %d", totalStepDetectorEvents, totalEstimatedSteps))
        appendLine(String.format(Locale.US, " Soste rilevate (D42): %d", totalStopsDetected))
        appendLine(String.format(Locale.US, " Errore mediano fase : %.2f ms (predizione vs appoggio successivo)", medianFootstrikeErrorMs))
        appendLine("==========================================================================")
    }
}

/**
 * Motore di Replay JVM (`:core-domain`) che esegue `SessionStateMachine` su una cartella di sessione
 * (`sensors.bin` + `manifest.json`) producendo un `SessionReplayReport` deterministico.
 */
object SessionReplayEngine {

    fun replaySessionDir(
        sessionDir: File,
        config: KeepAppConfig = KeepAppConfig()
    ): SessionReplayReport {
        val binFile = File(sessionDir, "sensors.bin")
        val manifestFile = File(sessionDir, "manifest.json")

        val (header, records) = BinarySessionReader.readSensorsBin(binFile)
        val manifest = BinarySessionReader.readManifestSummary(manifestFile, header.monotonicStartNs)

        val stateMachine = SessionStateMachine(
            config = config,
            initialPositionHint = manifest.position
        )

        val firstTs = records.firstOrNull()?.timestampNs ?: header.monotonicStartNs
        val lastTs = records.lastOrNull()?.timestampNs ?: firstTs
        val durationSec = if (lastTs > firstTs) (lastTs - firstTs) / 1_000_000_000f else 0f

        val csvRows = ArrayList<ReplayCsvRow>(records.size / 40 + 16)
        val validCadences = ArrayList<Float>()
        val positionCounts = IntArray(4)
        var stepDetectorRecords = 0

        // Per valutare l'errore di predizione del prossimo appoggio (D39)
        var pendingPredictedFootstrikeNs = 0L
        var lastRecordedImpactNs = 0L
        val footstrikePredictionErrorsMs = ArrayList<Float>()

        for (rec in records) {
            if (rec.recordType == BinaryFormat.TYPE_STEP) {
                stepDetectorRecords++
            }
            val frame: SessionFrameSnapshot? = stateMachine.processBinaryRecord(rec)

            // Verifica accuratezza predizione d'appoggio appena viene rilevato un nuovo impatto
            val phase = stateMachine.currentSnapshot(rec.timestampNs).phaseState
            if (phase.lastFootstrikeNs > 0L && phase.lastFootstrikeNs != lastRecordedImpactNs) {
                if (pendingPredictedFootstrikeNs > 0L) {
                    val errMs = abs(phase.lastFootstrikeNs - pendingPredictedFootstrikeNs) / 1_000_000f
                    if (errMs < 250f) {
                        footstrikePredictionErrorsMs.add(errMs)
                    }
                }
                lastRecordedImpactNs = phase.lastFootstrikeNs
                pendingPredictedFootstrikeNs = phase.predictedNextFootstrikeNs
            }

            if (frame != null) {
                val relTimeSec = (frame.timestampNs - firstTs) / 1_000_000_000f
                val predStrikeRelSec = if (frame.phaseState.predictedNextFootstrikeNs > firstTs) {
                    (frame.phaseState.predictedNextFootstrikeNs - firstTs) / 1_000_000_000f
                } else {
                    0f
                }

                if (frame.cadenceEstimate.isLockedOrValid) {
                    validCadences.add(frame.cadenceEstimate.cadenceSpm)
                }
                positionCounts[frame.positionEstimate.position.index]++

                csvRows.add(
                    ReplayCsvRow(
                        timeSeconds = relTimeSec,
                        estimatedCadenceSpm = frame.cadenceEstimate.cadenceSpm,
                        confidence = frame.cadenceEstimate.confidence,
                        position = frame.positionEstimate.position,
                        predictedNextFootstrikeSeconds = predStrikeRelSec,
                        anchorState = frame.anchorSnapshot.state,
                        initialAnchorBpm = frame.anchorSnapshot.initialLockedBpm,
                        targetBpm = frame.anchorSnapshot.targetBpm,
                        reinforcementActive = frame.anchorSnapshot.reinforcementActive,
                        isStopped = frame.stopSnapshot.isStopped,
                        gainDb = frame.stopSnapshot.currentGainDb,
                        totalSteps = frame.totalStepsDetected
                    )
                )
            }
        }

        val finalSnap = stateMachine.currentSnapshot(lastTs)
        val meanSpm = if (validCadences.isNotEmpty()) validCadences.average().toFloat() else 0f
        val medianSpm = if (validCadences.isNotEmpty()) {
            val s = validCadences.sorted()
            s[s.size / 2]
        } else {
            0f
        }
        val medianPhaseErrMs = if (footstrikePredictionErrorsMs.isNotEmpty()) {
            val s = footstrikePredictionErrorsMs.sorted()
            s[s.size / 2]
        } else {
            0f
        }

        var domPosIdx = manifest.position.index
        var maxPosCount = -1
        for (i in 0..3) {
            if (positionCounts[i] > maxPosCount) {
                maxPosCount = positionCounts[i]
                domPosIdx = i
            }
        }

        return SessionReplayReport(
            manifest = manifest,
            totalRecordsRead = records.size,
            durationSeconds = durationSec,
            meanCadenceSpm = meanSpm,
            medianCadenceSpm = medianSpm,
            initialLockedAnchorBpm = finalSnap.anchorSnapshot.initialLockedBpm,
            finalTargetBpm = finalSnap.anchorSnapshot.targetBpm,
            dominantPosition = PhonePosition.fromIndex(domPosIdx),
            totalStepDetectorEvents = stepDetectorRecords,
            totalEstimatedSteps = finalSnap.totalStepsDetected,
            totalStopsDetected = finalSnap.stopSnapshot.totalStopsCount,
            medianFootstrikeErrorMs = medianPhaseErrMs,
            rows = csvRows
        )
    }
}
