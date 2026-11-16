/*
 * Copyright 2026 Adriano D'Aprile
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package it.daprile.keepapp.core.config

import it.daprile.keepapp.core.format.PhonePosition

/**
 * Unica classe di configurazione per tutti gli algoritmi di `:core-domain` (Fase 1A — M1).
 *
 * NOTA ARCHITETTURALE (D33 / M1):
 * Tutti i parametri numerici qui raccolti hanno valori **PROVVISORI ("da tarare")**
 * fino al completamento del dataset su strada della Fase 0 (60 min a schermo spento,
 * camminata e corsa nelle 4 posizioni, conteggio di riferimento dei 200 passi).
 */
data class KeepAppConfig(
    // =========================================================================
    // 1. STIMA DELLA CADENZA (CadenceEstimator — D38) [DA TARARE]
    // =========================================================================
    /** Frequenza nominale attesa dell'accelerometro (misurata su Redmi Note 11 Pro 5G: 104.05 Hz) */
    val nominalAccelSampleRateHz: Float = 104.05f, // da tarare

    /** Taglio passa-alto sul modulo dell'accelerazione |a| per rimuovere la gravità (D38: 0,5 Hz) */
    val cadenceHighPassCutoffHz: Float = 0.5f, // da tarare

    /** Taglio passa-basso su |a| per isolare la fondamentale del passo (D38: ~5,0 Hz) */
    val cadenceLowPassCutoffHz: Float = 5.0f, // da tarare

    /** Durata della finestra scorrevole di autocorrelazione (D38: 4,0 s per risoluzione armonica) */
    val cadenceWindowSeconds: Float = 4.0f, // da tarare

    /** Intervallo di aggiornamento della stima di cadenza (0,25 s per reazione rapida) */
    val cadenceUpdateIntervalSeconds: Float = 0.25f, // da tarare

    /** Cadenza minima fisiologica ricercata (D38: 60 passi/min = 1,0 Hz) */
    val minCadenceSpm: Float = 60.0f, // da tarare

    /** Cadenza massima fisiologica ricercata (D38: 220 passi/min = 3,67 Hz) */
    val maxCadenceSpm: Float = 220.0f, // da tarare

    /** Energia dinamica RMS minima (m/s^2) su |a| filtrato per considerare il soggetto in movimento */
    val minDynamicAccelRmsMps2: Float = 0.28f, // da tarare

    /** Soglia minima di confidenza [0..1] sotto la quale il BPM stimato resta bloccato (D38) */
    val cadenceConfidenceThreshold: Float = 0.32f, // da tarare

    /** Coefficiente di smussamento esponenziale (EMA) tra aggiornamenti consecutivi di cadenza valida */
    val cadenceSmoothingAlpha: Float = 0.65f, // da tarare

    // =========================================================================
    // 2. CLASSIFICATORE DI POSIZIONE (PositionClassifier — D21) [DA TARARE]
    // =========================================================================
    /** Finestra di osservazione per gravità media e varianza giroscopio (2,0 s) */
    val positionWindowSeconds: Float = 2.0f, // da tarare

    /** Soglia di varianza giroscopica (rad/s)^2 sotto cui si classifica VITA/MARSUPIO (bacino stabile) */
    val waistMaxGyroVarianceRad2: Float = 0.55f, // da tarare

    /** Soglia di oscillazione pendolare della coscia (rad/s)^2 sopra cui si favorisce TASCA */
    val pocketMinPitchGyroVarianceRad2: Float = 1.40f, // da tarare

    /** Soglia di inclinazione orizzontale e rotazione multi-asse per classificare MANO */
    val handMinHorizontalGravityRatio: Float = 0.58f, // da tarare

    // =========================================================================
    // 3. FASE E APPOGGIO DEL PIEDE (PhaseTracker & BeatScheduler — D39) [DA TARARE]
    // =========================================================================
    /** Frazione minima del periodo del passo tra due picchi d'impatto consecutivi (periodo refrattario) */
    val minImpactSpacingPeriodFraction: Float = 0.62f, // da tarare

    /** Soglia minima di confidenza di fase [0..1] per attivare l'aggancio di fase (D39) */
    val phaseLockConfidenceThreshold: Float = 0.38f, // da tarare

    /** Correzione massima di fase consentita per singolo battito (D39: max ~15 ms per battito) */
    val maxPhaseCorrectionPerBeatMs: Float = 15.0f, // da tarare

    /** Offset di fase provvisori (in ms) tra picco IMU e appoggio reale per le 4 posizioni (D39) */
    val phaseOffsetArmMs: Float = 18.0f,    // da tarare
    val phaseOffsetHandMs: Float = 22.0f,   // da tarare
    val phaseOffsetWaistMs: Float = 8.0f,   // da tarare
    val phaseOffsetPocketMs: Float = 12.0f, // da tarare

    // =========================================================================
    // 4. ANCORA ELASTICA (ElasticAnchor — D40) [DA TARARE]
    // =========================================================================
    /** Durata di cadenza stabile iniziale richiesta per agganciare l'ancora (D40: 20–30 s -> 24 s) */
    val anchorLockStableSeconds: Float = 24.0f, // da tarare

    /** Deviazione standard massima (SPM) negli ultimi campioni per considerare stabile l'aggancio */
    val anchorLockMaxStdSpm: Float = 5.5f, // da tarare

    /** Soglia di rallentamento relativo rispetto al target per attivare il rinforzo (D40: > 3%) */
    val anchorDropThresholdFraction: Float = 0.03f, // da tarare

    /** Durata massima del rallentamento breve con rinforzo ritmico (D40: ~20 s) */
    val anchorShortDropMaxSeconds: Float = 20.0f, // da tarare

    /** Durata di rallentamento prolungato oltre la quale l'obiettivo scende (D40: > ~60 s) */
    val anchorProlongedDropSeconds: Float = 60.0f, // da tarare

    /** Velocità massima di discesa del BPM obiettivo (D40: massimo ~1 BPM ogni 10 s = 0,1 BPM/s) */
    val anchorMaxDecayBpmPer10s: Float = 1.0f, // da tarare

    /** Escursione massima consentita del BPM obiettivo dal valore di aggancio (D40: ±15%) */
    val anchorMaxDeviationFraction: Float = 0.15f, // da tarare

    // =========================================================================
    // 5. RILEVAMENTO SOSTE (StopDetector — D42) [DA TARARE]
    // =========================================================================
    /** Velocità GPS sotto la quale (insieme a zero passi) si dichiara la sosta (D42: < 0,5 m/s) */
    val stopMaxGpsSpeedMps: Float = 0.5f, // da tarare

    /** Tempo senza passi per entrare in stato di sosta (3,5 s per evitare false soste in camminata) */
    val stopNoStepTimeoutSeconds: Float = 3.5f, // da tarare

    /** Numero di passi consecutivi richiesti per uscire dalla sosta e riprendere il ritmo (D42: 4 passi) */
    val stopResumeConsecutiveSteps: Int = 4, // da tarare

    /** Attenuazione del volume del ritmo durante la sosta (D42: -20 dB) */
    val stopAttenuationDb: Float = -20.0f, // da tarare

    /** Tempo di rampa di attenuazione/ripresa del volume in sosta (D42: ~1,0 s) */
    val stopVolumeRampSeconds: Float = 1.0f, // da tarare

    /** Durata della sosta oltre la quale si esegue un nuovo aggancio dell'ancora (D42/D22: ~120 s = 2 min) */
    val stopRelockThresholdSeconds: Float = 120.0f // da tarare
) {
    /**
     * Restituisce l'offset di fase provvisorio in millisecondi per la posizione indicata (D39).
     */
    fun phaseOffsetMsFor(position: PhonePosition): Float = when (position) {
        PhonePosition.ARM -> phaseOffsetArmMs
        PhonePosition.HAND -> phaseOffsetHandMs
        PhonePosition.WAIST -> phaseOffsetWaistMs
        PhonePosition.POCKET -> phaseOffsetPocketMs
    }
}
