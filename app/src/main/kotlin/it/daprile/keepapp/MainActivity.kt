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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import it.daprile.keepapp.ui.KeepAppMainScreen
import it.daprile.keepapp.ui.KeepAppUiStateHolder

/**
 * Activity principale di KeepApp (`Step B` — < 60 righe).
 */
class MainActivity : ComponentActivity() {

    private lateinit var stateHolder: KeepAppUiStateHolder

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        stateHolder = KeepAppUiStateHolder(applicationContext)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    KeepAppMainScreen(holder = stateHolder)
                }
            }
        }
    }

    override fun onDestroy() {
        stateHolder.release()
        super.onDestroy()
    }
}
