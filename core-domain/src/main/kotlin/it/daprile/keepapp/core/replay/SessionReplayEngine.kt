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
import it.daprile.keepapp.core.audio.AudioMode
import it.daprile.keepapp.core.audio.BeatScheduler
import it.daprile.keepapp.core.config.KeepAppConfig
import it.daprile.keepapp.core.format.BinaryFormat
import it.daprile.keepapp.core.format.BinarySessionReader
import it.daprile.keepapp.core.format.ParsedManifestSummary
import it.daprile.keepapp.core.format.PhonePosition
import it.daprile.keepapp.core.rhythm.DrumInstrument
import it.daprile.keepapp.core.rhythm.PatternHit
import it.daprile.keepapp.core.rhythm.RhythmLevel
import it.daprile.keepapp.core.rhythm.RhythmPattern
import it.daprile.keepapp.core.rhythm.RhythmPatternEngine
import it.daprile.keepapp.core.session.SessionFrameSnapshot
import it.daprile.keepapp.core.session.SessionStateMachine
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Singola riga del file CSV prodotto dal Replay Harness (`:replay` — M1 / Step A2).
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
 * Riepilogo completo di un'esecuzione del Replay Harness su una sessione (M1 + Step A2).
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
    val rows: List<ReplayCsvRow>,
    // Nuovi campi Step A2
    val groundTruth: GroundTruthData? = null,
    val referenceFootstrikesCount: Int = 0,
    val integratedCadenceSteps: Float = 0f,
    val systemStepsInInterval: Int = 0,
    val cadenceErrorPct: Float? = null,
    val systemStepDetectorErrorPct: Float? = null,
    val phaseErrorMedianAbsMs: Float = 0f,
    val phaseErrorP90AbsMs: Float = 0f,
    val phaseErrorMeanSignedMs: Float = 0f,
    val lockTimeSeconds: Float? = null,
    val firstValidCadenceTimeSeconds: Float? = null,
    val falseStopsCount: Int = 0,
    val missedStopsCount: Int? = null,
    val estimatedCadenceJumpsOver5Pct: Int = 0,
    val targetBpmJumpsOver5Pct: Int = 0,
    val lowConfidenceMovementPct: Float = 0f,
    val positionDistributionPct: Map<PhonePosition, Float> = emptyMap(),
    val positionChangesCount: Int = 0,
    val configHash: String = "",
    val processingTimeMs: Long = 0L,
    val btLatencyUsedMs: Float = 0f,
    val passesAcceptanceThresholds: Boolean = true
) {
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

    fun toTextSummary(): String = buildString {
        appendLine("==========================================================================")
        appendLine(" KEEPAPP FASE 1A — RIEPILOGO REPLAY HARNESS: ${manifest.sessionFolder}")
        appendLine("==========================================================================")
        appendLine(" Dispositivo / Config : ${manifest.deviceModel} (hash $configHash)")
        appendLine(" Posizione dichiarata : ${manifest.position.labelIt} -> Stimata: ${dominantPosition.labelIt} (cambi: $positionChangesCount)")
        appendLine(String.format(Locale.US, " Durata analizzata    : %.2f s (%d record) | Elaborazione: %d ms", durationSeconds, totalRecordsRead, processingTimeMs))
        appendLine(String.format(Locale.US, " Cadenza media/mediana: %.2f / %.2f SPM | Integrale passi: %.1f (Ref offline: %d)", meanCadenceSpm, medianCadenceSpm, integratedCadenceSteps, referenceFootstrikesCount))
        if (cadenceErrorPct != null) {
            appendLine(String.format(Locale.US, " Errore cadenza       : %+.2f%% (StepDetector sistema: %+.2f%%)", cadenceErrorPct, systemStepDetectorErrorPct ?: 0f))
        }
        appendLine(String.format(Locale.US, " Fase (vs ref offline): med %.2f ms | p90 %.2f ms | media segno %+.2f ms", phaseErrorMedianAbsMs, phaseErrorP90AbsMs, phaseErrorMeanSignedMs))
        appendLine(String.format(Locale.US, " Tempo aggancio       : %s (prima stima valida: %s)", lockTimeSeconds?.let { String.format(Locale.US, "%.2f s", it) } ?: "n/d", firstValidCadenceTimeSeconds?.let { String.format(Locale.US, "%.2f s", it) } ?: "n/d"))
        appendLine(String.format(Locale.US, " Soste rilevate/false : %d / %d | Salti >5%% (stima/target): %d / %d", totalStopsDetected, falseStopsCount, estimatedCadenceJumpsOver5Pct, targetBpmJumpsOver5Pct))
        appendLine(String.format(Locale.US, " Confidenza bassa mov.: %.1f%%", lowConfidenceMovementPct))
        appendLine("==========================================================================")
    }
}

/**
 * Motore di Replay JVM (`:core-domain`) deterministico (M1 + Step A2).
 *
 * Usa esattamente `SessionStateMachine`, `BeatScheduler` e `KeepAppConfig` dell'app,
 * confrontandoli con il riferimento offline indipendente `FootstrikeReferenceDetector`.
 */
object SessionReplayEngine {

    private const val SAMPLE_RATE_HZ = 48_000

    fun replaySessionDir(
        sessionDir: File,
        config: KeepAppConfig = KeepAppConfig(),
        btLatencyOverrideMs: Float? = null
    ): SessionReplayReport {
        val startWallMs = System.currentTimeMillis()
        val binFile = File(sessionDir, "sensors.bin")
        val manifestFile = File(sessionDir, "manifest.json")

        val (header, records) = BinarySessionReader.readSensorsBin(binFile)
        val manifest = BinarySessionReader.readManifestSummary(manifestFile, header.monotonicStartNs)
        val groundTruth = ReplayConfigAndTruthLoader.loadGroundTruth(sessionDir)

        val btLatencyMs = btLatencyOverrideMs ?: inferBtLatencyMs(manifestFile, manifest.headphonesName)
        val btLatencyNs = (btLatencyMs * 1_000_000.0).toLong()

        // 1. Riferimento offline non causale sugli appoggi reali (indipendente da PhaseTracker!)
        val accelSamples = ArrayList<AccelSample>(records.size / 2 + 16)
        val markerTimesNs = ArrayList<Long>()
        for (rec in records) {
            when (rec.recordType) {
                BinaryFormat.TYPE_ACCEL -> accelSamples.add(AccelSample(rec.timestampNs, rec.f0, rec.f1, rec.f2))
                BinaryFormat.TYPE_MARKER -> markerTimesNs.add(rec.timestampNs)
            }
        }
        val refFootstrikesNs = FootstrikeReferenceDetector.detectFootstrikesNs(accelSamples)

        val firstTs = records.firstOrNull()?.timestampNs ?: header.monotonicStartNs
        val lastTs = records.lastOrNull()?.timestampNs ?: firstTs
        val durationSec = if (lastTs > firstTs) (lastTs - firstTs) / 1_000_000_000f else 0f

        // Intervallo di valutazione per cadenza/passi (markers vs intera sessione)
        val useMarkers = groundTruth?.interval == GroundTruthInterval.MARKERS && markerTimesNs.size >= 2
        val evalStartNs = if (useMarkers) markerTimesNs.first() else firstTs
        val evalEndNs = if (useMarkers) markerTimesNs.last() else lastTs

        val stateMachine = SessionStateMachine(
            config = config,
            initialPositionHint = manifest.position
        )

        // Simulatore BeatScheduler deterministico a 48.000 Hz
        val dummyPattern = RhythmPattern(
            id = "replay_beat",
            name = "Replay",
            description = "",
            beatsPerBar = 4,
            subdivisionsPerBeat = 4,
            levels = listOf(RhythmLevel(1, "Base", listOf(PatternHit(0, DrumInstrument.KICK, 1f))))
        )
        val scheduler = BeatScheduler(SAMPLE_RATE_HZ, config, RhythmPatternEngine(dummyPattern, 1))
        scheduler.mode = AudioMode.SIMPLE_METRONOME
        scheduler.reset(0L)
        var nextSchedSample = 0L
        val chunkSamples = 480L // 10 ms
        val earBeatTimesNs = ArrayList<Long>()

        val csvRows = ArrayList<ReplayCsvRow>(records.size / 40 + 16)
        val validCadences = ArrayList<Float>()
        val positionCounts = IntArray(PhonePosition.entries.size)
        var positionChanges = 0
        var lastPos: PhonePosition? = null

        var stepDetectorTotal = 0
        var stepDetectorInInterval = 0
        var integratedCadenceSteps = 0.0
        var prevFrameInIntervalNs = 0L

        // Metriche tempo aggancio & confidenza in movimento
        val firstRefFootstrikeNs = refFootstrikesNs.firstOrNull() ?: firstTs
        var firstValidCadenceSec: Float? = null
        var lockTimeSec: Float? = null
        var movingFrames = 0
        var lowConfMovingFrames = 0

        // Soste rilevate (intervalli [startSec, endSec])
        data class StopSpan(val startSec: Float, var endSec: Float)
        val stopSpans = ArrayList<StopSpan>()
        var activeStopSpan: StopSpan? = null

        // Predizione PhaseTracker (campo legacy medianFootstrikeErrorMs)
        var pendingPredictedFootstrikeNs = 0L
        var lastRecordedImpactNs = 0L
        val phaseTrackerSelfErrorsMs = ArrayList<Float>()

        for (rec in records) {
            if (rec.recordType == BinaryFormat.TYPE_STEP) {
                stepDetectorTotal++
                if (rec.timestampNs in evalStartNs..evalEndNs) {
                    stepDetectorInInterval++
                }
            }

            val frame: SessionFrameSnapshot? = stateMachine.processBinaryRecord(rec)

            val phase = stateMachine.currentSnapshot(rec.timestampNs).phaseState
            if (phase.lastFootstrikeNs > 0L && phase.lastFootstrikeNs != lastRecordedImpactNs) {
                if (pendingPredictedFootstrikeNs > 0L) {
                    val errMs = abs(phase.lastFootstrikeNs - pendingPredictedFootstrikeNs) / 1_000_000f
                    if (errMs < 250f) phaseTrackerSelfErrorsMs.add(errMs)
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

                // Integrale della cadenza stimata sull'intervallo di valutazione
                if (frame.timestampNs in evalStartNs..evalEndNs) {
                    if (prevFrameInIntervalNs > 0L && !frame.stopSnapshot.isStopped && frame.cadenceEstimate.cadenceSpm >= config.minCadenceSpm) {
                        val dtSec = (frame.timestampNs - prevFrameInIntervalNs) / 1_000_000_000.0
                        integratedCadenceSteps += (frame.cadenceEstimate.cadenceSpm / 60.0) * dtSec
                    }
                    prevFrameInIntervalNs = frame.timestampNs
                }

                // Tempo di aggancio dal primo appoggio di riferimento
                if (firstValidCadenceSec == null && frame.cadenceEstimate.isLockedOrValid && frame.timestampNs >= firstRefFootstrikeNs) {
                    firstValidCadenceSec = (frame.timestampNs - firstRefFootstrikeNs) / 1_000_000_000f
                }
                if (lockTimeSec == null && frame.anchorSnapshot.state == AnchorState.LOCKED && frame.timestampNs >= firstRefFootstrikeNs) {
                    lockTimeSec = (frame.timestampNs - firstRefFootstrikeNs) / 1_000_000_000f
                }

                // % confidenza bassa calcolata SOLO in movimento
                if (!frame.stopSnapshot.isStopped) {
                    movingFrames++
                    if (frame.cadenceEstimate.confidence < config.cadenceConfidenceThreshold) {
                        lowConfMovingFrames++
                    }
                }

                // Tracciamento intervalli di sosta
                if (frame.stopSnapshot.isStopped) {
                    val cur = activeStopSpan
                    if (cur == null) {
                        activeStopSpan = StopSpan(relTimeSec, relTimeSec)
                    } else {
                        cur.endSec = relTimeSec
                    }
                } else {
                    activeStopSpan?.let {
                        stopSpans.add(it)
                        activeStopSpan = null
                    }
                }

                if (frame.cadenceEstimate.isLockedOrValid) {
                    validCadences.add(frame.cadenceEstimate.cadenceSpm)
                }

                val curPos = frame.positionEstimate.position
                positionCounts[curPos.index]++
                if (lastPos != null && lastPos != curPos) {
                    positionChanges++
                }
                lastPos = curPos

                // Avanza BeatScheduler fino a frame.timestampNs
                val targetBpmForAudio = when {
                    frame.anchorSnapshot.targetBpm >= config.minCadenceSpm -> frame.anchorSnapshot.targetBpm
                    frame.cadenceEstimate.cadenceSpm >= config.minCadenceSpm -> frame.cadenceEstimate.cadenceSpm
                    else -> 0f
                }
                if (targetBpmForAudio >= config.minCadenceSpm) {
                    val posOffsetMs = config.phaseOffsetMsFor(curPos)
                    scheduler.updateTargets(
                        bpm = targetBpmForAudio,
                        predictedNextFootstrikeNs = frame.phaseState.predictedNextFootstrikeNs,
                        phaseConfidence = frame.phaseState.phaseConfidence,
                        btLatencyMs = btLatencyMs,
                        positionOffsetMs = posOffsetMs
                    )
                    val targetSample = (((frame.timestampNs - firstTs).toDouble() / 1_000_000_000.0) * SAMPLE_RATE_HZ).toLong()
                    while (nextSchedSample < targetSample) {
                        val events = scheduler.pullEventsForWindow(nextSchedSample, nextSchedSample + chunkSamples) { s ->
                            firstTs + ((s.toDouble() / SAMPLE_RATE_HZ) * 1_000_000_000.0).toLong()
                        }
                        if (!frame.stopSnapshot.isStopped) {
                            for (ev in events) {
                                if (ev.isMainBeat) {
                                    val outNs = firstTs + ((ev.sampleIndex.toDouble() / SAMPLE_RATE_HZ) * 1_000_000_000.0).toLong()
                                    earBeatTimesNs.add(outNs + btLatencyNs)
                                }
                            }
                        }
                        nextSchedSample += chunkSamples
                    }
                }

                csvRows.add(
                    ReplayCsvRow(
                        timeSeconds = relTimeSec,
                        estimatedCadenceSpm = frame.cadenceEstimate.cadenceSpm,
                        confidence = frame.cadenceEstimate.confidence,
                        position = curPos,
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
        activeStopSpan?.let { stopSpans.add(it) }

        // 2. Errore di fase indipendente: (istante colpo all'orecchio) - (appoggio di riferimento più vicino)
        val signedPhaseErrorsMs = ArrayList<Float>(earBeatTimesNs.size)
        var refIdx = 0
        for (earNs in earBeatTimesNs) {
            if (refFootstrikesNs.isEmpty()) break
            while (refIdx + 1 < refFootstrikesNs.size &&
                abs(refFootstrikesNs[refIdx + 1] - earNs) <= abs(refFootstrikesNs[refIdx] - earNs)
            ) {
                refIdx++
            }
            val signedErrMs = (earNs - refFootstrikesNs[refIdx]) / 1_000_000f
            if (abs(signedErrMs) <= 220f) {
                signedPhaseErrorsMs.add(signedErrMs)
            }
        }
        val absPhaseSorted = signedPhaseErrorsMs.map { abs(it) }.sorted()
        val phaseMedAbsMs = if (absPhaseSorted.isNotEmpty()) absPhaseSorted[absPhaseSorted.size / 2] else 0f
        val phaseP90AbsMs = if (absPhaseSorted.isNotEmpty()) {
            absPhaseSorted[((absPhaseSorted.size - 1) * 0.90f).roundToInt().coerceIn(0, absPhaseSorted.lastIndex)]
        } else 0f
        val phaseMeanSignedMs = if (signedPhaseErrorsMs.isNotEmpty()) signedPhaseErrorsMs.average().toFloat() else 0f

        // 3. False soste & soste mancate
        var falseStops = 0
        for (span in stopSpans) {
            val spanStartNs = firstTs + (span.startSec * 1_000_000_000.0).toLong()
            val spanEndNs = firstTs + (span.endSec * 1_000_000_000.0).toLong()
            val refStepsInside = refFootstrikesNs.count { it in spanStartNs..spanEndNs }
            val matchesRealStop = groundTruth?.realStops?.any { rs ->
                span.endSec >= rs.startSeconds - 2f && span.startSec <= rs.endSeconds + 2f
            } ?: false
            if (!matchesRealStop && refStepsInside >= 2) {
                falseStops++
            }
        }
        val missedStops: Int? = groundTruth?.realStops?.takeIf { it.isNotEmpty() }?.let { reals ->
            reals.count { rs ->
                stopSpans.none { span -> span.endSec >= rs.startSeconds - 2f && span.startSec <= rs.endSeconds + 2f }
            }
        }

        // 4. Salti > 5% in 2 s (cadenza stimata vs BPM obiettivo)
        var estJumps = 0
        var targetJumps = 0
        var prevWinIdx = 0
        for (i in csvRows.indices) {
            val cur = csvRows[i]
            while (prevWinIdx < i && cur.timeSeconds - csvRows[prevWinIdx].timeSeconds > 2.2f) {
                prevWinIdx++
            }
            val refRow = csvRows[prevWinIdx]
            val dt = cur.timeSeconds - refRow.timeSeconds
            if (dt in 1.5f..2.2f) {
                if (!cur.isStopped && !refRow.isStopped &&
                    refRow.estimatedCadenceSpm >= config.minCadenceSpm &&
                    cur.estimatedCadenceSpm >= config.minCadenceSpm
                ) {
                    val rel = abs(cur.estimatedCadenceSpm - refRow.estimatedCadenceSpm) / refRow.estimatedCadenceSpm
                    if (rel > 0.05f) estJumps++
                }
                if (refRow.anchorState != AnchorState.WARMING_UP &&
                    cur.anchorState != AnchorState.WARMING_UP &&
                    refRow.targetBpm >= config.minCadenceSpm &&
                    cur.targetBpm >= config.minCadenceSpm
                ) {
                    val relT = abs(cur.targetBpm - refRow.targetBpm) / refRow.targetBpm
                    if (relT > 0.05f) targetJumps++
                }
            }
        }

        val finalSnap = stateMachine.currentSnapshot(lastTs)
        val meanSpm = if (validCadences.isNotEmpty()) validCadences.average().toFloat() else 0f
        val medianSpm = if (validCadences.isNotEmpty()) validCadences.sorted().let { it[it.size / 2] } else 0f
        val legacyPhaseMed = if (phaseTrackerSelfErrorsMs.isNotEmpty()) phaseTrackerSelfErrorsMs.sorted().let { it[it.size / 2] } else 0f

        val totalPosSamples = max(1, positionCounts.sum())
        val posPct = PhonePosition.entries.associateWith { pos ->
            (positionCounts[pos.index] * 100f) / totalPosSamples.toFloat()
        }
        var domPosIdx = manifest.position.index
        var maxPosCount = -1
        for (i in positionCounts.indices) {
            if (positionCounts[i] > maxPosCount) {
                maxPosCount = positionCounts[i]
                domPosIdx = i
            }
        }

        val refSteps = groundTruth?.steps
        val cadenceErrPct = if (refSteps != null && refSteps > 0) {
            ((integratedCadenceSteps.toFloat() - refSteps) / refSteps.toFloat()) * 100f
        } else null
        val sysErrPct = if (refSteps != null && refSteps > 0) {
            ((stepDetectorInInterval - refSteps).toFloat() / refSteps.toFloat()) * 100f
        } else null

        val lowConfPct = if (movingFrames > 0) (lowConfMovingFrames * 100f) / movingFrames.toFloat() else 0f

        val passes = if (refSteps != null && refSteps > 0) {
            abs(cadenceErrPct ?: 0f) < 2.0f && (phaseMedAbsMs < 30.0f || earBeatTimesNs.isEmpty())
        } else {
            true
        }

        val elapsedMs = System.currentTimeMillis() - startWallMs
        return SessionReplayReport(
            manifest = manifest,
            totalRecordsRead = records.size,
            durationSeconds = durationSec,
            meanCadenceSpm = meanSpm,
            medianCadenceSpm = medianSpm,
            initialLockedAnchorBpm = finalSnap.anchorSnapshot.initialLockedBpm,
            finalTargetBpm = finalSnap.anchorSnapshot.targetBpm,
            dominantPosition = PhonePosition.fromIndex(domPosIdx),
            totalStepDetectorEvents = stepDetectorTotal,
            totalEstimatedSteps = finalSnap.totalStepsDetected,
            totalStopsDetected = finalSnap.stopSnapshot.totalStopsCount,
            medianFootstrikeErrorMs = legacyPhaseMed,
            rows = csvRows,
            groundTruth = groundTruth,
            referenceFootstrikesCount = refFootstrikesNs.size,
            integratedCadenceSteps = integratedCadenceSteps.toFloat(),
            systemStepsInInterval = stepDetectorInInterval,
            cadenceErrorPct = cadenceErrPct,
            systemStepDetectorErrorPct = sysErrPct,
            phaseErrorMedianAbsMs = phaseMedAbsMs,
            phaseErrorP90AbsMs = phaseP90AbsMs,
            phaseErrorMeanSignedMs = phaseMeanSignedMs,
            lockTimeSeconds = lockTimeSec,
            firstValidCadenceTimeSeconds = firstValidCadenceSec,
            falseStopsCount = falseStops,
            missedStopsCount = missedStops,
            estimatedCadenceJumpsOver5Pct = estJumps,
            targetBpmJumpsOver5Pct = targetJumps,
            lowConfidenceMovementPct = lowConfPct,
            positionDistributionPct = posPct,
            positionChangesCount = positionChanges,
            configHash = ReplayConfigAndTruthLoader.configHashSha256(config),
            processingTimeMs = elapsedMs,
            btLatencyUsedMs = btLatencyMs,
            passesAcceptanceThresholds = passes
        )
    }

    private fun inferBtLatencyMs(manifestFile: File, headphonesName: String): Float {
        if (manifestFile.exists()) {
            val txt = manifestFile.readText()
            val match = Regex("\"bt_latency_ms\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)").find(txt)
            val parsed = match?.groupValues?.getOrNull(1)?.toFloatOrNull()
            if (parsed != null) return parsed
        }
        return if (headphonesName.contains("MANKIW", ignoreCase = true)) 153.4f else 0f
    }
}
