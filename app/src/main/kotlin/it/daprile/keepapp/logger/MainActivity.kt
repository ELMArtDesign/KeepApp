/*
 * Copyright 2026 Adriano D'Aprile
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package it.daprile.keepapp.logger

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import it.daprile.keepapp.core.format.ActivityKind
import it.daprile.keepapp.core.format.PhonePosition
import it.daprile.keepapp.logger.data.DeviceInfoCollector
import it.daprile.keepapp.logger.data.DeviceSnapshot
import it.daprile.keepapp.logger.data.SessionFileWriter
import it.daprile.keepapp.logger.service.LoggerForegroundService
import java.io.File
import java.util.Locale

/**
 * Activity principale di KeepApp Logger (Fase 0 – Milestone 2).
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    KeepAppLoggerRoot()
                }
            }
        }
    }
}

@Composable
fun KeepAppLoggerRoot() {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    var deviceSnapshot by remember { mutableStateOf(DeviceInfoCollector.collectDeviceSnapshot(context)) }

    val permissionsToRequest = remember {
        buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(Manifest.permission.ACTIVITY_RECOGNITION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    var permissionsGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionsGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
    }

    LaunchedEffect(Unit) {
        if (!permissionsGranted) {
            permLauncher.launch(permissionsToRequest)
        }
    }

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF102027))
                    .padding(top = 12.dp)
            ) {
                Text(
                    text = "KeepApp Logger — Fase 0 (M2)",
                    color = Color.White,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                Text(
                    text = "Sensori 100Hz • GPS • Mic BUILTIN 48kHz • Test A2DP & Marker Cuffie",
                    color = Color(0xFFB0BEC5),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color(0xFF102027),
                    contentColor = Color.White
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("1. Sessione (F2–F4)") }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = {
                            deviceSnapshot = DeviceInfoCollector.collectDeviceSnapshot(context)
                            selectedTab = 1
                        },
                        text = { Text("2. Info Dispositivo (F1)") }
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (!permissionsGranted) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Permesso Microfono e Posizione GPS richiesti per M2",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFE65100)
                        )
                        Text(
                            text = "Premi qui sotto per autorizzare la cattura dal microfono interno (48 kHz) e verificare che le cuffie restino in A2DP.",
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { permLauncher.launch(permissionsToRequest) }) {
                            Text("Concedi Permessi (Incluso Microfono)")
                        }
                    }
                }
            }

            if (selectedTab == 0) {
                SessionRecordingScreen()
            } else {
                DeviceInfoScreen(
                    snapshot = deviceSnapshot,
                    onRefresh = {
                        deviceSnapshot = DeviceInfoCollector.collectDeviceSnapshot(context)
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SessionRecordingScreen() {
    val context = LocalContext.current
    val runtimeState by LoggerForegroundService.runtimeState.collectAsState()

    var selectedPosition by remember { mutableStateOf(PhonePosition.ARM) }
    var selectedActivity by remember { mutableStateOf(ActivityKind.WALK) }
    var headphonesName by remember { mutableStateOf("Mankiw Bone Conduction") }
    var enableMic by remember { mutableStateOf(true) }

    val sessionsRoot = remember { SessionFileWriter.getSessionsRootDir(context.filesDir) }
    var savedSessions by remember { mutableStateOf(listSavedSessions(sessionsRoot)) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(runtimeState.isRecording) {
        savedSessions = listSavedSessions(sessionsRoot)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Pannello Stato in Tempo Reale (Sensori + Audio A2DP + Marker)
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (runtimeState.isRecording) Color(0xFFE8F5E9) else Color(0xFFF5F5F5)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = if (runtimeState.isRecording) "🔴 REGISTRAZIONE M2 ATTIVA (Puoi spegnere lo schermo)"
                    else "⚪ PRONTO PER REGISTRARE",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = if (runtimeState.isRecording) Color(0xFF1B5E20) else Color(0xFF424242)
                )
                if (runtimeState.sessionFolderName.isNotEmpty()) {
                    Text(
                        text = "Cartella: ${runtimeState.sessionFolderName}",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = String.format(
                        Locale.US,
                        "Durata: %02d:%02d  |  Record: %d  |  Heartbeat: %d",
                        runtimeState.elapsedSeconds / 60,
                        runtimeState.elapsedSeconds % 60,
                        runtimeState.recordsWritten,
                        runtimeState.heartbeatCount
                    ),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = String.format(
                        Locale.US,
                        "ACCEL: %.1f Hz  |  GYRO: %.1f Hz  |  MAG: %.1f Hz",
                        runtimeState.accelHz,
                        runtimeState.gyroHz,
                        runtimeState.magHz
                    ),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = String.format(
                        Locale.US,
                        "Passi: %d  |  Fix GPS: %d (vel: %.1f m/s, acc: %.1f m)",
                        runtimeState.stepCount,
                        runtimeState.gpsFixCount,
                        runtimeState.lastGpsSpeedMps,
                        runtimeState.lastGpsAccuracyM
                    ),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

                // Sezione Audio M2: A2DP vs SCO + AUDIO_SYNC + Microfono
                val btStatusText = when {
                    runtimeState.isScoActive -> "⚠️ ATTENZIONE: BLUETOOTH SCO/HFP ATTIVO!"
                    runtimeState.isA2dpActive -> "✅ CUFFIA IN BLUETOOTH A2DP (Alta qualità OK)"
                    else -> "ℹ️ Nessuna cuffia A2DP rilevata (Altoparlante)"
                }
                val btStatusColor = when {
                    runtimeState.isScoActive -> Color(0xFFC62828)
                    runtimeState.isA2dpActive -> Color(0xFF1B5E20)
                    else -> Color(0xFF455A64)
                }

                Text(
                    text = btStatusText,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = btStatusColor
                )
                Text(
                    text = "Input Mic: ${runtimeState.activeInputSummary}",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "Output: ${runtimeState.activeOutputSummary} (RouteChanges: ${runtimeState.routeChangeCount})",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
                val micSeconds = runtimeState.micFramesWritten / 48_000f
                Text(
                    text = String.format(
                        Locale.US,
                        "WAV 48kHz: %.1f s  |  AUDIO_SYNC: %d  |  Clock Drift: %+.2f ms",
                        micSeconds,
                        runtimeState.audioSyncCount,
                        runtimeState.audioClockDriftMs
                    ),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

                // Sezione Marker F4 (Tasti cuffia Mankiw + Pulsante UI)
                Text(
                    text = "🎯 Marker totali: ${runtimeState.markerCount}  |  Ultimo: ${runtimeState.lastMarkerInfo}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = Color(0xFF0D47A1)
                )
                Text(
                    text = String.format(
                        Locale.US,
                        "Batteria: %.0f%% (%.1f °C)  |  ThermalStatus: %d",
                        runtimeState.batteryPercent,
                        runtimeState.batteryTempC,
                        runtimeState.thermalStatus
                    ),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // Controlli di configurazione o di sessione attiva
        if (!runtimeState.isRecording) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("1. Posizione del telefono:", fontWeight = FontWeight.Bold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PhonePosition.entries.forEach { pos ->
                            FilterChip(
                                selected = selectedPosition == pos,
                                onClick = { selectedPosition = pos },
                                label = { Text(pos.labelIt) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text("2. Attività:", fontWeight = FontWeight.Bold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActivityKind.entries.forEach { act ->
                            FilterChip(
                                selected = selectedActivity == act,
                                onClick = { selectedActivity = act },
                                label = { Text(act.labelIt) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("3. Registra Microfono (mic_raw.wav 48 kHz):", fontWeight = FontWeight.Bold)
                            Text("Forza BUILTIN_MIC + verifica tenuta A2DP", fontSize = 12.sp, color = Color.DarkGray)
                        }
                        Switch(
                            checked = enableMic,
                            onCheckedChange = { enableMic = it }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = headphonesName,
                        onValueChange = { headphonesName = it },
                        label = { Text("Cuffia in uso") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            val startIntent = Intent(context, LoggerForegroundService::class.java).apply {
                                action = LoggerForegroundService.ACTION_START_SESSION
                                putExtra(LoggerForegroundService.EXTRA_POSITION, selectedPosition.code)
                                putExtra(LoggerForegroundService.EXTRA_ACTIVITY, selectedActivity.code)
                                putExtra(LoggerForegroundService.EXTRA_HEADPHONES, headphonesName)
                                putExtra(LoggerForegroundService.EXTRA_ENABLE_MIC, enableMic)
                            }
                            ContextCompat.startForegroundService(context, startIntent)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                    ) {
                        Text("▶ AVVIA SESSIONE DI REGISTRAZIONE", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            // Pulsanti grandi di riserva a schermo (1x Evento e 2x Incrocio) oltre ai tasti fisici delle cuffie
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        val markerIntent = Intent(context, LoggerForegroundService::class.java).apply {
                            action = LoggerForegroundService.ACTION_ADD_UI_MARKER
                            putExtra(LoggerForegroundService.EXTRA_MARKER_TYPE, 1)
                        }
                        context.startService(markerIntent)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(78.dp)
                ) {
                    Text(
                        text = "🚗 MARKER 1x\n(Evento)",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = {
                        val markerIntent = Intent(context, LoggerForegroundService::class.java).apply {
                            action = LoggerForegroundService.ACTION_ADD_UI_MARKER
                            putExtra(LoggerForegroundService.EXTRA_MARKER_TYPE, 2)
                        }
                        context.startService(markerIntent)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6A1B9A)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(78.dp)
                ) {
                    Text(
                        text = "🔀 MARKER 2x\n(Incrocio)",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Text(
                text = "💡 Puoi premere il tasto fisico sulle cuffie Mankiw: 1 tocco = Evento (1 beep), 2 tocchi = Incrocio (2 beep).",
                fontSize = 12.sp,
                color = Color(0xFF1B5E20)
            )

            Button(
                onClick = {
                    val stopIntent = Intent(context, LoggerForegroundService::class.java).apply {
                        action = LoggerForegroundService.ACTION_STOP_SESSION
                    }
                    context.startService(stopIntent)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
            ) {
                Text("⏹ FERMA E SALVA SESSIONE", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }

        HorizontalDivider()

        // Elenco sessioni salvate in locale
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Sessioni salvate in locale (${savedSessions.size})",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            OutlinedButton(onClick = { savedSessions = listSavedSessions(sessionsRoot) }) {
                Text("Aggiorna")
            }
        }

        if (savedSessions.isEmpty()) {
            Text(
                text = "Nessuna sessione presente nella memoria locale dell'app.",
                fontSize = 13.sp,
                color = Color.Gray
            )
        } else {
            savedSessions.forEach { dir ->
                val binFile = File(dir, "sensors.bin")
                val wavFile = File(dir, "mic_raw.wav")
                val binKb = if (binFile.exists()) binFile.length() / 1024L else 0L
                val wavKb = if (wavFile.exists()) wavFile.length() / 1024L else 0L
                val recordCount = if (binFile.exists() && binFile.length() >= 32L) {
                    (binFile.length() - 32L) / 32L
                } else 0L

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFAFAFA)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = dir.name,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "sensors.bin: $binKb KB ($recordCount rec) • mic_raw.wav: ${if (wavFile.exists()) "$wavKb KB" else "assente"}",
                            fontSize = 12.sp,
                            color = Color(0xFF455A64)
                        )
                    }
                }
            }

            if (!runtimeState.isRecording) {
                OutlinedButton(
                    onClick = { showDeleteDialog = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFC62828)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("🗑️ Cancella tutte le sessioni")
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Cancellare tutte le sessioni?") },
            text = {
                Text("Tutti i file sensors.bin, manifest.json e mic_raw.wav registrati nella memoria dell'app verranno eliminati definitivamente.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        sessionsRoot.deleteRecursively()
                        sessionsRoot.mkdirs()
                        savedSessions = listSavedSessions(sessionsRoot)
                        showDeleteDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))
                ) {
                    Text("Sì, cancella tutto")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Annulla")
                }
            }
        )
    }
}

@Composable
fun DeviceInfoScreen(
    snapshot: DeviceSnapshot,
    onRefresh: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Riepilogo Sistema & Barometro",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
                OutlinedButton(onClick = onRefresh) {
                    Text("Rileggi")
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Modello: ${snapshot.manufacturer} ${snapshot.model} (${snapshot.device})", fontWeight = FontWeight.Bold)
                    Text("Android: ${snapshot.androidRelease} (API ${snapshot.sdkInt})")
                    Text("Build / HyperOS: ${snapshot.buildId} (${snapshot.incrementalVersion})")
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (snapshot.hasBarometer) "✅ BAROMETRO HARDWARE: PRESENTE"
                        else "❌ BAROMETRO HARDWARE: ASSENTE (Usare quota GPS + Copernicus)",
                        fontWeight = FontWeight.Bold,
                        color = if (snapshot.hasBarometer) Color(0xFF2E7D32) else Color(0xFFC62828)
                    )
                }
            }
        }

        item {
            Text(
                text = "Dispositivi Audio Collegati (${snapshot.audioDevices.size})",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }

        items(snapshot.audioDevices) { audio ->
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F8E9)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        text = "${audio.productName} (ID: ${audio.id})",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        text = "Tipo: ${audio.typeName} [${audio.typeId}] • " +
                                (if (audio.isSource) "INGRESSO (Mic) " else "") +
                                (if (audio.isSink) "USCITA (Cuffia/Speaker)" else ""),
                        fontSize = 12.sp
                    )
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Elenco Completo Sensori (${snapshot.sensors.size})",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }

        items(snapshot.sensors) { s ->
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        text = "${s.name} (type=${s.typeId})",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        text = "Fornitore: ${s.vendor} • Tipo: ${s.stringType}",
                        fontSize = 12.sp
                    )
                    Text(
                        text = String.format(
                            Locale.US,
                            "Freq. Max: %.1f Hz (minDelay=%d us) • Wake-up: %s • Risoluzione: %g",
                            s.maxFrequencyHz,
                            s.minDelayUs,
                            if (s.isWakeUpSensor) "SÌ" else "NO",
                            s.resolution
                        ),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

private fun listSavedSessions(rootDir: File): List<File> {
    if (!rootDir.exists()) return emptyList()
    return rootDir.listFiles { f -> f.isDirectory && f.name.startsWith("session_") }
        ?.sortedByDescending { it.name }
        ?: emptyList()
}
