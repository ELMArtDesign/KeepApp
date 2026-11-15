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

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Activity principale della nuova applicazione **KeepApp** (`it.daprile.keepapp` — Fase 1A "Cuore ritmico").
 *
 * In Milestone 0/1 fornisce lo scheletro del modulo `:app` separato da `:logger`.
 * Nelle Milestone M2–M5 integra `AudioEngine`, calibrazione cuffia, `KeepAppSessionService` e la UI minima.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    KeepAppPlaceholderScreen()
                }
            }
        }
    }
}

@Composable
private fun KeepAppPlaceholderScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "KeepApp — Fase 1A (Cuore Ritmico)",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Struttura a 5 moduli attiva (:core-domain, :platform-android, :logger, :app, :replay)",
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Per la raccolta dati su strada della Fase 0 usa l'app 'KeepApp Logger (Fase 0)' (modulo :logger). " +
                            "Questa app ('KeepApp', modulo :app) ospiterà il motore ritmico sintetizzato, la calibrazione e la sessione completa nelle Milestone M2–M5.",
                    fontSize = 14.sp
                )
            }
        }
    }
}
