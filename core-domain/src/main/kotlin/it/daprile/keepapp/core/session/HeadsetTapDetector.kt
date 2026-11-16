/*
 * Copyright 2026 Adriano D'Aprile
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package it.daprile.keepapp.core.session

/**
 * Gesto riconosciuto a partire da eventi di pressione singola del tasto cuffie Bluetooth (Step A1.3).
 */
enum class HeadsetGesture {
    SINGLE_TAP,
    DOUBLE_TAP
}

/**
 * Riconoscitore deterministico di singolo/doppio tocco da soli click singoli AVRCP (Step A1.3).
 *
 * Regole:
 * - 2 eventi "1 tocco" ricevuti entro `doubleTapWindowMs` (default 450 ms) → `DOUBLE_TAP` immediato.
 * - 1 evento "1 tocco" viene confermato come `SINGLE_TAP` **solo allo scadere della finestra**
 *   (`onWindowExpired(nowMs)`).
 */
class HeadsetTapDetector(
    private val doubleTapWindowMs: Long = 450L,
    private val onGestureConfirmed: (HeadsetGesture, Long) -> Unit
) {
    private var pendingTapAtMs: Long? = null

    val hasPendingTap: Boolean
        get() = pendingTapAtMs != null

    /**
     * Registra un evento di pressione singola al timestamp `eventTimeMs`.
     * Restituisce `true` se è stato aperto un nuovo timer di attesa per la finestra `doubleTapWindowMs`,
     * oppure `false` se l'evento ha completato subito un `DOUBLE_TAP`.
     */
    fun onSingleTap(eventTimeMs: Long): Boolean {
        val firstAt = pendingTapAtMs
        if (firstAt != null && (eventTimeMs - firstAt) in 0L..doubleTapWindowMs) {
            pendingTapAtMs = null
            onGestureConfirmed(HeadsetGesture.DOUBLE_TAP, eventTimeMs)
            return false
        }
        // Se c'era un vecchio tap scaduto prima di questo evento, confermalo prima di aprire il nuovo
        if (firstAt != null && (eventTimeMs - firstAt) > doubleTapWindowMs) {
            pendingTapAtMs = null
            onGestureConfirmed(HeadsetGesture.SINGLE_TAP, firstAt + doubleTapWindowMs)
        }
        pendingTapAtMs = eventTimeMs
        return true
    }

    /**
     * Da chiamare allo scadere del timer `doubleTapWindowMs` (o su tick periodico).
     * Se c'è un singolo tap in sospeso la cui finestra è trascorsa, conferma `SINGLE_TAP`.
     */
    fun onWindowExpired(nowMs: Long) {
        val firstAt = pendingTapAtMs ?: return
        if ((nowMs - firstAt) >= doubleTapWindowMs) {
            pendingTapAtMs = null
            onGestureConfirmed(HeadsetGesture.SINGLE_TAP, nowMs)
        }
    }

    fun reset() {
        pendingTapAtMs = null
    }
}
