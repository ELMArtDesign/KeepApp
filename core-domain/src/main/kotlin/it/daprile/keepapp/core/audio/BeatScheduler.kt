/*
 * Copyright 2026 Adriano D'Aprile
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package it.daprile.keepapp.core.audio

import it.daprile.keepapp.core.config.KeepAppConfig
import it.daprile.keepapp.core.rhythm.DrumInstrument
import it.daprile.keepapp.core.rhythm.RhythmPatternEngine
import kotlin.math.roundToLong

/**
 * Modalità di generazione sonora dell'AudioEngine in Fase 1A (M2).
 */
enum class AudioMode {
    /** Pattern di batteria/percussioni sintetizzati da JSON (decisione A9 / D41) */
    RHYTHM_PATTERN,
    /** Metronomo semplice sample-accurate (alternativa prevista in M2 / D44) */
    SIMPLE_METRONOME
}

/**
 * Singolo evento sonoro programmato al campione esatto per l'AudioEngine (D39 / D41).
 */
data class ScheduledAudioEvent(
    /** Indice di campione assoluto nello stream a 48.000 Hz in cui inizia il suono */
    val sampleIndex: Long,
    /** Strumento da sommare al buffer */
    val instrument: DrumInstrument,
    /** Intensità [0.0 .. 1.0] */
    val velocity: Float,
    /** True se è un colpo principale sul battito (downbeat o quarter-beat) */
    val isMainBeat: Boolean,
    /** Indice della battuta dall'avvio */
    val barIndex: Long,
    /** Indice della suddivisione nella battuta (0 .. stepsPerBar - 1) */
    val stepIndex: Int,
    /** Correzione di fase applicata al battito (in microsecondi, <= ±15.000 us) */
    val phaseCorrectionUs: Int,
    /** True se in corrispondenza di questo step è avvenuto un cambio di livello del pattern */
    val levelChangedNow: Boolean = false,
    /** Livello di intensità attivo del pattern al momento di questo evento */
    val activeLevelIndex: Int = 1
)

/**
 * Pianificatore dei battiti e delle suddivisioni ritmiche (`BeatScheduler` — D39 / D41).
 *
 * Funzionamento:
 * - Opera in Kotlin puro (`:core-domain`) sull'asse discreto dei campioni PCM a `sampleRateHz` (48.000 Hz).
 * - Conosce la relazione istantanea tra campione scritto e tempo d'uscita stimato tramite la funzione
 *   `sampleIndexForOutputTimeNs` fornita dallo stimatore PLL di `AudioTrack.getTimestamp()`.
 * - Programma il colpo principale a:
 *   `targetOutputNs = predictedNextFootstrikeNs - btLatencyNs - positionPhaseOffsetNs`
 * - Applica correzioni di fase graduali **limitate a `±maxPhaseCorrectionPerBeatMs` (15 ms) per battito**
 *   per evitare salti ritmici udibili (D39).
 * - Se la confidenza di fase è sotto soglia (`phaseLockConfidenceThreshold`), mantiene comunque il
 *   tempo esatto (`targetBpm`) senza forzare la fase.
 */
class BeatScheduler(
    private val sampleRateHz: Int = 48_000,
    private val config: KeepAppConfig = KeepAppConfig(),
    private val patternEngine: RhythmPatternEngine
) {
    @Volatile
    var mode: AudioMode = AudioMode.RHYTHM_PATTERN

    @Volatile
    var targetBpm: Float = 160.0f

    @Volatile
    var btLatencyNs: Long = 0L

    @Volatile
    var positionOffsetNs: Long = 0L

    @Volatile
    var predictedFootstrikeNs: Long = 0L

    @Volatile
    var phaseConfidence: Float = 0f

    private var nextBeatSampleIndex: Long = -1L
    private var barIndex: Long = 0L
    private var beatInBar: Int = 0
    private var lastGeneratedSampleIndex: Long = -1L

    private val pendingEvents = ArrayDeque<ScheduledAudioEvent>(64)

    fun reset(startSampleIndex: Long = 0L) {
        nextBeatSampleIndex = startSampleIndex
        barIndex = 0L
        beatInBar = 0
        lastGeneratedSampleIndex = startSampleIndex - 1L
        pendingEvents.clear()
    }

    /**
     * Aggiorna i parametri fisiologici correnti (chiamato ad ogni frame della StateMachine).
     */
    fun updateTargets(
        bpm: Float,
        predictedNextFootstrikeNs: Long,
        phaseConfidence: Float,
        btLatencyMs: Float,
        positionOffsetMs: Float
    ) {
        if (bpm >= config.minCadenceSpm && bpm <= config.maxCadenceSpm) {
            this.targetBpm = bpm
        }
        this.predictedFootstrikeNs = predictedNextFootstrikeNs
        this.phaseConfidence = phaseConfidence
        this.btLatencyNs = (btLatencyMs.coerceAtLeast(0f) * 1_000_000.0).roundToLong()
        this.positionOffsetNs = (positionOffsetMs * 1_000_000.0).roundToLong()
    }

    /**
     * Genera e restituisce tutti gli eventi sonori che ricadono nella finestra di campioni
     * `[windowStartSample, windowEndSampleExclusive)`.
     *
     * @param outputTimeNsForSample Funzione inversa del clock PLL (`sample -> elapsedRealtimeNanos` d'uscita)
     */
    fun pullEventsForWindow(
        windowStartSample: Long,
        windowEndSampleExclusive: Long,
        outputTimeNsForSample: (Long) -> Long?
    ): List<ScheduledAudioEvent> {
        if (nextBeatSampleIndex < 0L) {
            nextBeatSampleIndex = windowStartSample
        }

        val currentBpm = targetBpm.coerceIn(config.minCadenceSpm, config.maxCadenceSpm)
        val samplesPerBeat = (sampleRateHz.toDouble() * 60.0) / currentBpm.toDouble()

        // Finché il prossimo battito cade dentro (o prima) della fine della finestra corrente, generiamo
        // il battito principale e tutte le sue suddivisioni (es. 4 sedicesimi per battito)
        while (nextBeatSampleIndex < windowEndSampleExclusive) {
            val beatSample = nextBeatSampleIndex.coerceAtLeast(windowStartSample)
            val phaseCorrectionSamples = computePhaseCorrectionSamples(beatSample, samplesPerBeat, outputTimeNsForSample)
            val correctedBeatSample = (beatSample + phaseCorrectionSamples).coerceAtLeast(lastGeneratedSampleIndex + 1L)
            val correctionUs = ((phaseCorrectionSamples.toDouble() / sampleRateHz.toDouble()) * 1_000_000.0).toInt()

            val pattern = patternEngine.currentPattern
            val beatsPerBar = pattern.beatsPerBar
            val subsPerBeat = pattern.subdivisionsPerBeat

            if (mode == AudioMode.SIMPLE_METRONOME) {
                val isDownbeat = (beatInBar == 0)
                val velocity = if (isDownbeat) 1.0f else 0.72f
                pendingEvents.addLast(
                    ScheduledAudioEvent(
                        sampleIndex = correctedBeatSample,
                        instrument = if (isDownbeat) DrumInstrument.ACCENT else DrumInstrument.METRONOME_CLICK,
                        velocity = velocity,
                        isMainBeat = true,
                        barIndex = barIndex,
                        stepIndex = beatInBar * subsPerBeat,
                        phaseCorrectionUs = correctionUs,
                        levelChangedNow = false,
                        activeLevelIndex = patternEngine.activeLevelIndex
                    )
                )
            } else {
                val samplesPerSub = samplesPerBeat / subsPerBeat.toDouble()
                for (sub in 0 until subsPerBeat) {
                    val stepIdx = beatInBar * subsPerBeat + sub
                    val subSample = correctedBeatSample + (sub * samplesPerSub).roundToLong()
                    val (hits, levelChanged) = patternEngine.hitsForStep(stepIdx)
                    for (hit in hits) {
                        pendingEvents.addLast(
                            ScheduledAudioEvent(
                                sampleIndex = subSample,
                                instrument = hit.instrument,
                                velocity = hit.velocity,
                                isMainBeat = (sub == 0),
                                barIndex = barIndex,
                                stepIndex = stepIdx,
                                phaseCorrectionUs = if (sub == 0) correctionUs else 0,
                                levelChangedNow = levelChanged,
                                activeLevelIndex = patternEngine.activeLevelIndex
                            )
                        )
                    }
                }
            }

            lastGeneratedSampleIndex = correctedBeatSample
            beatInBar++
            if (beatInBar >= beatsPerBar) {
                beatInBar = 0
                barIndex++
            }
            nextBeatSampleIndex = correctedBeatSample + samplesPerBeat.roundToLong()
        }

        if (pendingEvents.isEmpty()) return emptyList()
        val out = ArrayList<ScheduledAudioEvent>()
        while (pendingEvents.isNotEmpty() && pendingEvents.first().sampleIndex < windowEndSampleExclusive) {
            val ev = pendingEvents.removeFirst()
            if (ev.sampleIndex >= windowStartSample) {
                out.add(ev)
            }
        }
        return out
    }

    /**
     * Calcola la correzione di fase (in campioni) da applicare al prossimo battito,
     * limitata rigidamente a `±maxPhaseCorrectionPerBeatMs` (15 ms — D39).
     */
    private fun computePhaseCorrectionSamples(
        nominalBeatSample: Long,
        samplesPerBeat: Double,
        outputTimeNsForSample: (Long) -> Long?
    ): Long {
        if (phaseConfidence < config.phaseLockConfidenceThreshold || predictedFootstrikeNs <= 0L) {
            return 0L
        }
        val nominalOutputNs = outputTimeNsForSample(nominalBeatSample) ?: return 0L

        // Istante desiderato di emissione (D39): appoggio previsto (già compensato per posizione in PhaseTracker) - latenza BT
        val targetEmissionNs = predictedFootstrikeNs - btLatencyNs
        val periodNs = ((samplesPerBeat / sampleRateHz.toDouble()) * 1_000_000_000.0).roundToLong()
        if (periodNs <= 0L) return 0L

        // Riporta l'errore di fase nell'intervallo [-period/2, +period/2] (allineamento al colpo più vicino)
        var errorNs = (targetEmissionNs - nominalOutputNs) % periodNs
        if (errorNs > periodNs / 2L) errorNs -= periodNs
        if (errorNs < -periodNs / 2L) errorNs += periodNs

        val errorMs = errorNs / 1_000_000.0
        val maxSlewMs = config.maxPhaseCorrectionPerBeatMs.toDouble()
        // Guadagno proporzionale morbido (0.5) con saturazione rigida a ±15 ms
        val clampedStepMs = (errorMs * 0.5).coerceIn(-maxSlewMs, maxSlewMs)
        return ((clampedStepMs / 1_000.0) * sampleRateHz.toDouble()).roundToLong()
    }
}
