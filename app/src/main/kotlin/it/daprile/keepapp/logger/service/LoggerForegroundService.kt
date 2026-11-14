/*
 * Copyright 2026 Adriano D'Aprile
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package it.daprile.keepapp.logger.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import it.daprile.keepapp.core.format.ActivityKind
import it.daprile.keepapp.core.format.BinaryFormat
import it.daprile.keepapp.core.format.BinaryRecord
import it.daprile.keepapp.core.format.PhonePosition
import it.daprile.keepapp.core.format.SessionConfig
import it.daprile.keepapp.logger.MainActivity
import it.daprile.keepapp.logger.data.DeviceInfoCollector
import it.daprile.keepapp.logger.data.SessionFileWriter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Stato in tempo reale della sessione di registrazione esposto alla UI Jetpack Compose.
 */
data class LoggerRuntimeState(
    val isRecording: Boolean = false,
    val sessionFolderName: String = "",
    val elapsedSeconds: Long = 0L,
    val accelHz: Float = 0f,
    val gyroHz: Float = 0f,
    val magHz: Float = 0f,
    val stepCount: Long = 0L,
    val gpsFixCount: Long = 0L,
    val lastGpsSpeedMps: Float = 0f,
    val lastGpsAccuracyM: Float = 0f,
    val heartbeatCount: Long = 0L,
    val batteryPercent: Float = 0f,
    val batteryTempC: Float = 0f,
    val thermalStatus: Int = 0,
    val recordsWritten: Long = 0L,
    val markerCount: Long = 0L
)

/**
 * Foreground Service di registrazione per la Fase 0 (F2).
 *
 * Caratteristiche tecniche:
 * - Si avvia come Foreground Service con notifica esplicita ("Corsa attiva: guida vocale e ritmo in funzione").
 * - Acquisisce un `PowerManager.PARTIAL_WAKE_LOCK` per continuare a campionare a schermo spento.
 * - Registra i sensori inerziali su un `HandlerThread` dedicato (`KeepApp-SensorThread`)
 *   così da non toccare mai il Main Thread della UI:
 *   - Accelerometro: periodo di campionamento `10_000 us` (~100 Hz), `maxReportLatencyUs = 0`
 *   - Giroscopio: periodo di campionamento `10_000 us` (~100 Hz), `maxReportLatencyUs = 0`
 *   - Magnetometro: periodo di campionamento `20_000 us` (~50 Hz), `maxReportLatencyUs = 0`
 *   - Step Detector (`TYPE_STEP_DETECTOR`): `maxReportLatencyUs = 0` (niente batching FIFO)
 *   - Barometro (`TYPE_PRESSURE`): se presente sul telefono
 * - Registra il GPS nativo (`LocationManager.GPS_PROVIDER`, zero Google Play Services) a 1 Hz (`1000 ms`, `0 m`).
 * - Emette ogni 10 secondi un record `HEARTBEAT` e un record `THERMAL` (batteria, temperatura, stato termico).
 */
class LoggerForegroundService : Service(), SensorEventListener, LocationListener {

    private var wakeLock: PowerManager.WakeLock? = null
    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager
    private var fileWriter: SessionFileWriter? = null

    private var sessionStartNs: Long = 0L
    private var lastWindowNs: Long = 0L
    private var heartbeatSeq: Long = 0L

    // Contatori atomici per il calcolo delle frequenze reali e delle statistiche
    private val windowAccelEvents = AtomicLong(0L)
    private val windowGyroEvents = AtomicLong(0L)
    private val windowMagEvents = AtomicLong(0L)
    private val totalStepEvents = AtomicLong(0L)
    private val totalGpsFixes = AtomicLong(0L)
    private val totalMarkers = AtomicLong(0L)

    @Volatile
    private var lastGpsSpeed = 0f

    @Volatile
    private var lastGpsAccuracy = 0f

    private var lastAccelHz = 0f
    private var lastGyroHz = 0f
    private var lastMagHz = 0f

    private val tickerRunnable = object : Runnable {
        override fun run() {
            if (!_runtimeState.value.isRecording) return
            onPeriodicTick()
            mainHandler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SESSION -> {
                if (!_runtimeState.value.isRecording) {
                    val posCode = intent.getStringExtra(EXTRA_POSITION) ?: PhonePosition.ARM.code
                    val actCode = intent.getStringExtra(EXTRA_ACTIVITY) ?: ActivityKind.WALK.code
                    val headphones = intent.getStringExtra(EXTRA_HEADPHONES) ?: "Mankiw Bone Conduction"

                    val position = PhonePosition.entries.find { it.code == posCode } ?: PhonePosition.ARM
                    val activityKind = ActivityKind.entries.find { it.code == actCode } ?: ActivityKind.WALK

                    startLoggingSession(
                        SessionConfig(
                            position = position,
                            activityKind = activityKind,
                            headphonesName = headphones,
                            enableMicrophone = false
                        )
                    )
                }
            }

            ACTION_ADD_UI_MARKER -> {
                if (_runtimeState.value.isRecording) {
                    recordManualMarker()
                }
            }

            ACTION_STOP_SESSION -> {
                stopLoggingSession()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (_runtimeState.value.isRecording) {
            stopLoggingSession()
        }
        super.onDestroy()
    }

    @SuppressLint("MissingPermission", "WakelockTimeout")
    private fun startLoggingSession(config: SessionConfig) {
        // 1. Avvia il servizio in primo piano con i tipi compatibili con i permessi concessi
        val notification = buildNotification("Registrazione sensori e GPS attiva a schermo spento")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            if (hasLocationPermission()) {
                fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            if (config.enableMicrophone && hasAudioPermission()) {
                fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // 2. Acquisisci il WakeLock parziale per impedire la sospensione della CPU a schermo spento
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "KeepAppLogger::SessionWakeLock"
        ).apply {
            setReferenceCounted(false)
            // Timeout di sicurezza di 4 ore (14.400.000 ms)
            acquire(4 * 60 * 60 * 1000L)
        }

        // 3. Inizializza contatori e scrittore su thread dedicato
        val nowElapsedNs = SystemClock.elapsedRealtimeNanos()
        val nowUnixMs = System.currentTimeMillis()
        sessionStartNs = nowElapsedNs
        lastWindowNs = nowElapsedNs
        heartbeatSeq = 0L

        windowAccelEvents.set(0L)
        windowGyroEvents.set(0L)
        windowMagEvents.set(0L)
        totalStepEvents.set(0L)
        totalGpsFixes.set(0L)
        totalMarkers.set(0L)
        lastGpsSpeed = 0f
        lastGpsAccuracy = 0f

        val snapshot = DeviceInfoCollector.collectDeviceSnapshot(this)
        val sessionsRoot = SessionFileWriter.getSessionsRootDir(filesDir)
        val writer = SessionFileWriter(
            sessionsRootDir = sessionsRoot,
            config = config,
            deviceSnapshot = snapshot,
            monotonicStartNs = nowElapsedNs,
            unixStartMs = nowUnixMs
        )
        writer.start()
        fileWriter = writer

        // 4. Crea l'HandlerThread dedicato per la ricezione dei sensori ad alta frequenza
        val thread = HandlerThread("KeepApp-SensorThread").apply { start() }
        val handler = Handler(thread.looper)
        sensorThread = thread
        sensorHandler = handler

        // 5. Registra Accelerometro (~100 Hz = 10_000 us), Giroscopio (~100 Hz = 10_000 us),
        //    Magnetometro (~50 Hz = 20_000 us), Step Detector (maxReportLatencyUs = 0) e Barometro
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { accel ->
            sensorManager.registerListener(this, accel, 10_000, 0, handler)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { gyro ->
            sensorManager.registerListener(this, gyro, 10_000, 0, handler)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let { mag ->
            sensorManager.registerListener(this, mag, 20_000, 0, handler)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)?.let { step ->
            sensorManager.registerListener(this, step, SensorManager.SENSOR_DELAY_FASTEST, 0, handler)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let { baro ->
            sensorManager.registerListener(this, baro, SensorManager.SENSOR_DELAY_NORMAL, 0, handler)
        }

        // 6. Registra GPS nativo (LocationManager.GPS_PROVIDER) a 1 Hz (1000 ms, 0 metri)
        if (hasLocationPermission()) {
            try {
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        1_000L,
                        0f,
                        this,
                        thread.looper
                    )
                }
            } catch (_: Exception) {
                // Ignora se il provider non è disponibile
            }
        }

        // 7. Emetti subito il primo HEARTBEAT (sequenza 0) e il primo THERMAL all'avvio
        emitHeartbeatAndThermal(nowElapsedNs)

        _runtimeState.value = LoggerRuntimeState(
            isRecording = true,
            sessionFolderName = writer.sessionDir.name
        )

        mainHandler.postDelayed(tickerRunnable, 1_000L)
    }

    private fun stopLoggingSession() {
        mainHandler.removeCallbacks(tickerRunnable)

        try {
            sensorManager.unregisterListener(this)
        } catch (_: Exception) {
        }

        try {
            locationManager.removeUpdates(this)
        } catch (_: Exception) {
        }

        sensorThread?.quitSafely()
        sensorThread = null
        sensorHandler = null

        val endNs = SystemClock.elapsedRealtimeNanos()
        fileWriter?.stopAndClose(endNs)
        fileWriter = null

        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null

        _runtimeState.value = _runtimeState.value.copy(isRecording = false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    /**
     * Eseguito ogni secondo per aggiornare la UI e, ogni 10 secondi, per scrivere
     * i record HEARTBEAT e THERMAL nel file binario.
     */
    private fun onPeriodicTick() {
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val elapsedSec = (nowNs - sessionStartNs) / 1_000_000_000L
        val windowDurationSec = (nowNs - lastWindowNs) / 1_000_000_000f

        // Ogni 10 secondi calcoliamo le frequenze medie della finestra ed emettiamo HEARTBEAT + THERMAL
        if (windowDurationSec >= 10.0f) {
            val accCount = windowAccelEvents.getAndSet(0L)
            val gyrCount = windowGyroEvents.getAndSet(0L)
            val magCount = windowMagEvents.getAndSet(0L)

            lastAccelHz = accCount / windowDurationSec
            lastGyroHz = gyrCount / windowDurationSec
            lastMagHz = magCount / windowDurationSec
            lastWindowNs = nowNs

            emitHeartbeatAndThermal(nowNs)
        } else if (windowDurationSec >= 1.0f && heartbeatSeq <= 1L) {
            // Nei primi 10 secondi mostra comunque una stima istantanea sulla UI
            lastAccelHz = windowAccelEvents.get() / windowDurationSec
            lastGyroHz = windowGyroEvents.get() / windowDurationSec
            lastMagHz = windowMagEvents.get() / windowDurationSec
        }

        val thermalSnap = DeviceInfoCollector.readThermalAndBattery(this)

        _runtimeState.value = LoggerRuntimeState(
            isRecording = true,
            sessionFolderName = fileWriter?.sessionDir?.name ?: "",
            elapsedSeconds = elapsedSec,
            accelHz = lastAccelHz,
            gyroHz = lastGyroHz,
            magHz = lastMagHz,
            stepCount = totalStepEvents.get(),
            gpsFixCount = totalGpsFixes.get(),
            lastGpsSpeedMps = lastGpsSpeed,
            lastGpsAccuracyM = lastGpsAccuracy,
            heartbeatCount = heartbeatSeq,
            batteryPercent = thermalSnap.batteryLevelPercent,
            batteryTempC = thermalSnap.batteryTemperatureCelsius,
            thermalStatus = thermalSnap.thermalStatus,
            recordsWritten = fileWriter?.recordsWrittenCount?.get() ?: 0L,
            markerCount = totalMarkers.get()
        )
    }

    private fun emitHeartbeatAndThermal(nowNs: Long) {
        val writer = fileWriter ?: return
        val elapsedSec = (nowNs - sessionStartNs) / 1_000_000_000f
        val seq = heartbeatSeq++

        val hbRecord = BinaryRecord.ofFloats(
            timestampNs = nowNs,
            recordType = BinaryFormat.TYPE_HEARTBEAT,
            flags = 1,
            reserved = 0,
            f0 = seq.toFloat(),
            f1 = elapsedSec,
            f2 = lastAccelHz,
            f3 = lastGyroHz,
            f4 = totalGpsFixes.get().toFloat()
        )
        writer.enqueue(hbRecord)

        val thermal = DeviceInfoCollector.readThermalAndBattery(this)
        val thRecord = BinaryRecord.ofFloats(
            timestampNs = nowNs,
            recordType = BinaryFormat.TYPE_THERMAL,
            flags = thermal.thermalStatus.coerceIn(0, 255).toByte(),
            reserved = 0,
            f0 = thermal.batteryLevelPercent,
            f1 = thermal.batteryTemperatureCelsius,
            f2 = thermal.thermalStatus.toFloat(),
            f3 = thermal.thermalHeadroom10s,
            f4 = 0f
        )
        writer.enqueue(thRecord)
    }

    private fun recordManualMarker() {
        val writer = fileWriter ?: return
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val markerIdx = totalMarkers.incrementAndGet()
        val record = BinaryRecord.ofFloats(
            timestampNs = nowNs,
            recordType = BinaryFormat.TYPE_MARKER,
            flags = 3, // 3 = Pulsante UI a schermo
            reserved = 0,
            f0 = markerIdx.toFloat(),
            f1 = 0f,
            f2 = 1f,
            f3 = 0f,
            f4 = 0f
        )
        writer.enqueue(record)
        _runtimeState.value = _runtimeState.value.copy(markerCount = markerIdx)
    }

    // === Callback SensorEventListener (eseguite su KeepApp-SensorThread) ===

    override fun onSensorChanged(event: SensorEvent) {
        val writer = fileWriter ?: return
        val sensorTs = event.timestamp

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                if (windowAccelEvents.get() == 0L && heartbeatSeq <= 1L) {
                    writer.recordSensorClockCheck(sensorTs, SystemClock.elapsedRealtimeNanos())
                }
                windowAccelEvents.incrementAndGet()
                writer.enqueue(
                    BinaryRecord.ofVector3D(
                        timestampNs = sensorTs,
                        recordType = BinaryFormat.TYPE_ACCEL,
                        accuracy = event.accuracy,
                        x = event.values[0],
                        y = event.values[1],
                        z = event.values[2]
                    )
                )
            }

            Sensor.TYPE_GYROSCOPE -> {
                windowGyroEvents.incrementAndGet()
                writer.enqueue(
                    BinaryRecord.ofVector3D(
                        timestampNs = sensorTs,
                        recordType = BinaryFormat.TYPE_GYRO,
                        accuracy = event.accuracy,
                        x = event.values[0],
                        y = event.values[1],
                        z = event.values[2]
                    )
                )
            }

            Sensor.TYPE_MAGNETIC_FIELD -> {
                windowMagEvents.incrementAndGet()
                writer.enqueue(
                    BinaryRecord.ofVector3D(
                        timestampNs = sensorTs,
                        recordType = BinaryFormat.TYPE_MAG,
                        accuracy = event.accuracy,
                        x = event.values[0],
                        y = event.values[1],
                        z = event.values[2]
                    )
                )
            }

            Sensor.TYPE_PRESSURE -> {
                writer.enqueue(
                    BinaryRecord.ofFloats(
                        timestampNs = sensorTs,
                        recordType = BinaryFormat.TYPE_BARO,
                        flags = event.accuracy.coerceIn(0, 255).toByte(),
                        f0 = event.values[0]
                    )
                )
            }

            Sensor.TYPE_STEP_DETECTOR -> {
                val totalSteps = totalStepEvents.incrementAndGet()
                writer.enqueue(
                    BinaryRecord.ofFloats(
                        timestampNs = sensorTs,
                        recordType = BinaryFormat.TYPE_STEP,
                        flags = 1,
                        f0 = totalSteps.toFloat()
                    )
                )
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // L'accuratezza è già salvata in ogni singolo record
    }

    // === Callback LocationListener (GPS_PROVIDER nativo a 1 Hz) ===

    override fun onLocationChanged(location: Location) {
        val writer = fileWriter ?: return
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val locNs = if (location.elapsedRealtimeNanos > 0L) location.elapsedRealtimeNanos else nowNs
        val fixAgeSec = (nowNs - locNs) / 1_000_000_000f

        val satellites = location.extras?.getInt("satellites", 0) ?: 0
        val altitude = if (location.hasAltitude()) location.altitude.toFloat() else Float.NaN
        val speed = if (location.hasSpeed()) location.speed else 0f
        val bearing = if (location.hasBearing()) location.bearing else 0f
        val horizAcc = if (location.hasAccuracy()) location.accuracy else Float.NaN
        val vertAcc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasVerticalAccuracy()) {
            location.verticalAccuracyMeters
        } else {
            Float.NaN
        }

        var flagsB = 0
        if (location.hasSpeed()) flagsB = flagsB or 1
        if (location.hasBearing()) flagsB = flagsB or 2
        if (location.hasAccuracy()) flagsB = flagsB or 4

        writer.enqueue(
            BinaryRecord.ofGpsA(
                timestampNs = locNs,
                satellites = satellites,
                latitude = location.latitude,
                longitude = location.longitude,
                altitudeMeters = altitude
            )
        )
        writer.enqueue(
            BinaryRecord.ofGpsB(
                timestampNs = locNs,
                hasSpeedAndBearingFlags = flagsB.toByte(),
                speedMps = speed,
                bearingDeg = bearing,
                horizontalAccuracyM = horizAcc,
                verticalAccuracyM = vertAcc,
                fixAgeSeconds = fixAgeSec
            )
        )

        lastGpsSpeed = speed
        lastGpsAccuracy = if (horizAcc.isNaN()) 0f else horizAcc
        totalGpsFixes.incrementAndGet()
    }

    @Deprecated("Richiesto per compatibilità con API < 29")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "KeepApp Sessione Attiva",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Corsa attiva: guida vocale e ritmo in funzione (Fase 0 Logger)"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Corsa attiva: guida vocale e ritmo in funzione")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "keepapp_logger_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_SESSION = "it.daprile.keepapp.logger.action.START_SESSION"
        const val ACTION_STOP_SESSION = "it.daprile.keepapp.logger.action.STOP_SESSION"
        const val ACTION_ADD_UI_MARKER = "it.daprile.keepapp.logger.action.ADD_UI_MARKER"

        const val EXTRA_POSITION = "extra_position"
        const val EXTRA_ACTIVITY = "extra_activity"
        const val EXTRA_HEADPHONES = "extra_headphones"

        private val _runtimeState = MutableStateFlow(LoggerRuntimeState())
        val runtimeState: StateFlow<LoggerRuntimeState> = _runtimeState.asStateFlow()
    }
}
