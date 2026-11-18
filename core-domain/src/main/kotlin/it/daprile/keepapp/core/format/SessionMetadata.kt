/*
 * Copyright 2026 Adriano D'Aprile
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package it.daprile.keepapp.core.format

/**
 * Posizione fisica dello smartphone sul corpo durante la sessione (D21 / D24).
 */
enum class PhonePosition(val code: String, val labelIt: String, val index: Int) {
    ARM("arm", "Braccio (Fascia)", 0),
    HAND("hand", "Mano", 1),
    WAIST("waist", "Vita (Marsupio)", 2),
    POCKET("pocket", "Tasca", 3),
    WRIST("wrist", "Polso", 4);

    companion object {
        fun fromCode(code: String?): PhonePosition =
            entries.find { it.code.equals(code, ignoreCase = true) } ?: ARM

        fun fromIndex(idx: Int): PhonePosition =
            entries.find { it.index == idx } ?: ARM
    }
}

/**
 * Tipo di attività svolta durante la sessione di test (D24).
 */
enum class ActivityKind(val code: String, val labelIt: String) {
    WALK("walk", "Camminata"),
    RUN("run", "Corsa"),
    STAND("stand", "Sosta / Fermo"),
    FALL_SIM("fall_sim", "Simulazione Caduta");

    companion object {
        fun fromCode(code: String?): ActivityKind =
            entries.find { it.code.equals(code, ignoreCase = true) } ?: WALK
    }
}

/**
 * Informazioni descrittive su un singolo sensore hardware rilevato sul dispositivo (F1).
 */
data class SensorDescriptor(
    val name: String,
    val stringType: String,
    val typeId: Int,
    val vendor: String,
    val resolution: Float,
    val maximumRange: Float,
    val minDelayUs: Int,
    val maxFrequencyHz: Float,
    val isWakeUpSensor: Boolean,
    val fifoMaxEventCount: Int
)

/**
 * Informazioni su un dispositivo audio di input o output collegato (F1 / F2).
 */
data class AudioDeviceDescriptor(
    val id: Int,
    val productName: String,
    val typeId: Int,
    val typeName: String,
    val isSource: Boolean,
    val isSink: Boolean
)

/**
 * Configurazione scelta dall'utente prima di avviare una sessione di registrazione (F2 / D33d).
 */
data class SessionConfig(
    val position: PhonePosition,
    val activityKind: ActivityKind,
    val headphonesName: String,
    val enableMicrophone: Boolean = false,
    val pureSurvivalMode: Boolean = false
)
