/*
 * Copyright 2026 Adriano D'Aprile
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package it.daprile.keepapp

/**
 * Interruttori interni di compilazione/UI (Step A0 — non esposti all'utente).
 *
 * Mantengono congelate dalla UI le funzioni sperimentali in attesa di validazione
 * architetturale (D52 per Modalità Allenamento; Step C per mattoni vocali Kokoro D07/D30).
 */
object FeatureFlags {
    const val ENABLE_TRAINING_PLAN: Boolean = false
    const val ENABLE_VOICE_COACH_TTS: Boolean = false
}
