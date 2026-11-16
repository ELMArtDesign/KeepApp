/*
 * Copyright 2026 Adriano D'Aprile
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package it.daprile.keepapp.platform.session

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
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import it.daprile.keepapp.core.anchor.AnchorState
import it.daprile.keepapp.core.audio.AudioMode
import it.daprile.keepapp.core.config.KeepAppConfig
import it.daprile.keepapp.core.format.PhonePosition
import it.daprile.keepapp.core.session.HeadsetGesture
import it.daprile.keepapp.core.session.HeadsetTapDetector
import it.daprile.keepapp.core.session.SessionFrameSnapshot
import it.daprile.keepapp.core.session.SessionMetricsTracker
import it.daprile.keepapp.core.session.SessionRunState
import it.daprile.keepapp.core.session.SessionStateMachine
import it.daprile.keepapp.core.session.SessionSummaryRecord
import it.daprile.keepapp.core.session.TrainingMode
import it.daprile.keepapp.core.session.TrainingPlanConfig
import it.daprile.keepapp.core.session.VoiceAnnouncementPlanner
import it.daprile.keepapp.platform.audio.AudioEngineTelemetry
import it.daprile.keepapp.platform.audio.BtLatencyProfileStore
import it.daprile.keepapp.platform.audio.KeepAppAudioEngine
import it.daprile.keepapp.platform.audio.PatternAssetLoader
import it.daprile.keepapp.platform.audio.VoiceCoach
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stato osservabile dalla UI per la sessione live end-to-end (Milestone 4).
 */
data class LiveSessionUiState(
    val isActive: Boolean = false,
    val runState: SessionRunState = SessionRunState.ACTIVE_RUNNING,
    val elapsedSeconds: Float = 0f,
    val cadenceSpm: Float = 0f,
    val cadenceConfidence: Float = 0f,
    val targetBpm: Float = 155f,
    val anchorState: AnchorState = AnchorState.WARMING_UP,
    val reinforcementActive: Boolean = false,
    val estimatedPosition: PhonePosition = PhonePosition.ARM,
    val totalSteps: Long = 0L,
    val activeDeviceName: String = "—",
    val appliedBtLatencyMs: Float = 120f,
    val gpsSpeedMps: Float = 0f,
    val autoLevelEnabled: Boolean = true,
    val voiceCoachEnabled: Boolean = true,
    val trainingPlan: TrainingPlanConfig = TrainingPlanConfig(),
    val audioTelemetry: AudioEngineTelemetry = AudioEngineTelemetry(),
    val lastCompletedSummary: SessionSummaryRecord? = null
)

/**
 * Servizio in primo piano (`ForegroundService`) della Milestone 4:
 * integra sensori IMU (~100 Hz), GPS nativo (`GPS_PROVIDER` D42), `SessionStateMachine` (`:core-domain`),
 * `KeepAppAudioEngine` (48 kHz + PLL), profilo di latenza Bluetooth calibrato (M3),
 * `AudioFocus` (D22) e controllo pausa/ripresa da singolo click cuffie (D34/D44).
 */
class KeepAppSessionService : Service(), SensorEventListener, LocationListener {

    companion object {
        const val ACTION_START = "it.daprile.keepapp.action.START_SESSION"
        const val ACTION_STOP = "it.daprile.keepapp.action.STOP_SESSION"
        const val ACTION_TOGGLE_PAUSE = "it.daprile.keepapp.action.TOGGLE_PAUSE"
        const val ACTION_SET_MODE = "it.daprile.keepapp.action.SET_MODE"
        const val ACTION_SET_PATTERN = "it.daprile.keepapp.action.SET_PATTERN"
        const val ACTION_SET_AUTO_LEVEL = "it.daprile.keepapp.action.SET_AUTO_LEVEL"
        const val ACTION_SET_MANUAL_LEVEL = "it.daprile.keepapp.action.SET_MANUAL_LEVEL"
        const val ACTION_SET_VOICE_COACH = "it.daprile.keepapp.action.SET_VOICE_COACH"
        const val ACTION_SET_TRAINING_PLAN = "it.daprile.keepapp.action.SET_TRAINING_PLAN"

        const val EXTRA_AUDIO_MODE = "extra_audio_mode"
        const val EXTRA_PATTERN_ID = "extra_pattern_id"
        const val EXTRA_AUTO_LEVEL = "extra_auto_level"
        const val EXTRA_LEVEL_INDEX = "extra_level_index"
        const val EXTRA_VOICE_COACH = "extra_voice_coach"
        const val EXTRA_TRAINING_MODE = "extra_training_mode"
        const val EXTRA_FIXED_BPM = "extra_fixed_bpm"
        const val EXTRA_RAMP_START_BPM = "extra_ramp_start_bpm"
        const val EXTRA_RAMP_END_BPM = "extra_ramp_end_bpm"
        const val EXTRA_RAMP_MINUTES = "extra_ramp_minutes"

        private const val CHANNEL_ID = "keepapp_live_session"
        private const val NOTIFICATION_ID = 4001

        private val _liveState = MutableStateFlow(LiveSessionUiState())
        val liveState: StateFlow<LiveSessionUiState> = _liveState.asStateFlow()

        fun startSession(context: Context) {
            val intent = Intent(context, KeepAppSessionService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopSession(context: Context) {
            val intent = Intent(context, KeepAppSessionService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }

        fun togglePause(context: Context) {
            val intent = Intent(context, KeepAppSessionService::class.java).setAction(ACTION_TOGGLE_PAUSE)
            context.startService(intent)
        }
    }

    private val config = KeepAppConfig()
    private var sensorManager: SensorManager? = null
    private var locationManager: LocationManager? = null
    private var audioManager: AudioManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaSession: MediaSession? = null
    private var focusRequest: AudioFocusRequest? = null

    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null

    private var stateMachine: SessionStateMachine? = null
    private var audioEngine: KeepAppAudioEngine? = null
    private var profileStore: BtLatencyProfileStore? = null
    private var historyStore: SessionHistoryStore? = null
    private var metricsTracker: SessionMetricsTracker? = null
    private var voiceCoach: VoiceCoach? = null
    private val voicePlanner = VoiceAnnouncementPlanner()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val headsetTapDetector = HeadsetTapDetector(
        doubleTapWindowMs = config.headsetDoubleTapWindowMs
    ) { gesture, _ ->
        when (gesture) {
            HeadsetGesture.SINGLE_TAP -> handleTogglePause()
            HeadsetGesture.DOUBLE_TAP -> {
                // D44: doppio tocco riservato al recap vocale della Fase 1B (nessuna funzione in 1A)
                if (!doubleTapSupported) {
                    doubleTapSupported = true
                    profileStore?.markDoubleTapSupported(activeDeviceKey, true)
                }
            }
        }
    }
    private val pendingSingleTapRunnable = Runnable {
        headsetTapDetector.onWindowExpired(SystemClock.elapsedRealtime())
    }

    @Volatile
    private var appliedBtLatencyMs: Float = 120f

    @Volatile
    private var activeDeviceName: String = "Altoparlante / Jack"

    @Volatile
    private var activeDeviceKey: String = "local_speaker"

    @Volatile
    private var doubleTapSupported: Boolean = false

    @Volatile
    private var autoLevelEnabled: Boolean = true

    @Volatile
    private var voiceCoachEnabled: Boolean = true

    @Volatile
    private var trainingPlan: TrainingPlanConfig = TrainingPlanConfig()

    @Volatile
    private var lastGpsSpeedMps: Float = 0f

    private var isStarted = false
    private var hasInitialCadenceLock = false
    private var lastNotificationUpdateMs = 0L

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        val engine = audioEngine ?: return@OnAudioFocusChangeListener
        val sm = stateMachine ?: return@OnAudioFocusChangeListener
        val nowNs = SystemClock.elapsedRealtimeNanos()
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS -> {
                sm.pause(nowNs)
                engine.setMuted(true)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                engine.setAttenuated(true)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                engine.setAttenuated(false)
                sm.resume(nowNs)
                engine.setMuted(false)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        profileStore = BtLatencyProfileStore(this)
        historyStore = SessionHistoryStore(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (!isStarted) beginSession()
            ACTION_STOP -> endSession()
            ACTION_TOGGLE_PAUSE -> handleTogglePause()
            ACTION_SET_MODE -> {
                val modeName = intent.getStringExtra(EXTRA_AUDIO_MODE)
                val mode = runCatching { AudioMode.valueOf(modeName ?: "") }.getOrNull()
                if (mode != null) {
                    audioEngine?.setMode(mode)
                    historyStore?.let { store ->
                        store.savePreferences(store.loadPreferences().copy(audioMode = mode))
                    }
                }
            }
            ACTION_SET_PATTERN -> {
                val patId = intent.getStringExtra(EXTRA_PATTERN_ID)
                val pat = PatternAssetLoader.loadAll(this).find { it.id == patId }
                if (pat != null) {
                    audioEngine?.setPattern(pat)
                    historyStore?.let { store ->
                        store.savePreferences(store.loadPreferences().copy(patternId = pat.id))
                    }
                }
            }
            ACTION_SET_AUTO_LEVEL -> {
                autoLevelEnabled = intent.getBooleanExtra(EXTRA_AUTO_LEVEL, true)
                _liveState.value = _liveState.value.copy(autoLevelEnabled = autoLevelEnabled)
                historyStore?.let { store ->
                    store.savePreferences(store.loadPreferences().copy(autoLevelEnabled = autoLevelEnabled))
                }
            }
            ACTION_SET_MANUAL_LEVEL -> {
                val lvl = intent.getIntExtra(EXTRA_LEVEL_INDEX, 1)
                autoLevelEnabled = false
                audioEngine?.setLevel(lvl)
                _liveState.value = _liveState.value.copy(autoLevelEnabled = false)
            }
            ACTION_SET_VOICE_COACH -> {
                voiceCoachEnabled = intent.getBooleanExtra(EXTRA_VOICE_COACH, true)
                voiceCoach?.enabled = voiceCoachEnabled
                _liveState.value = _liveState.value.copy(voiceCoachEnabled = voiceCoachEnabled)
                historyStore?.let { store ->
                    store.savePreferences(store.loadPreferences().copy(voiceCoachEnabled = voiceCoachEnabled))
                }
            }
            ACTION_SET_TRAINING_PLAN -> {
                val modeName = intent.getStringExtra(EXTRA_TRAINING_MODE)
                val mode = runCatching { TrainingMode.valueOf(modeName ?: "") }.getOrDefault(TrainingMode.ADAPTIVE_ANCHOR)
                val plan = TrainingPlanConfig(
                    mode = mode,
                    fixedTargetBpm = intent.getFloatExtra(EXTRA_FIXED_BPM, 160f),
                    rampStartBpm = intent.getFloatExtra(EXTRA_RAMP_START_BPM, 150f),
                    rampEndBpm = intent.getFloatExtra(EXTRA_RAMP_END_BPM, 175f),
                    rampDurationMinutes = intent.getIntExtra(EXTRA_RAMP_MINUTES, 20)
                )
                trainingPlan = plan
                if (plan.mode != TrainingMode.ADAPTIVE_ANCHOR) {
                    hasInitialCadenceLock = true
                }
                _liveState.value = _liveState.value.copy(trainingPlan = plan)
                historyStore?.let { store ->
                    store.savePreferences(store.loadPreferences().copy(trainingPlan = plan))
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        endSession()
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun beginSession() {
        isStarted = true

        // 1. Carica profilo latenza Bluetooth calibrato (M3)
        val store = profileStore ?: BtLatencyProfileStore(this)
        val dev = store.detectActiveOutputDevice()
        val saved = store.load(dev.key)
        activeDeviceKey = dev.key
        activeDeviceName = dev.displayName
        appliedBtLatencyMs = saved?.latencyMs ?: if (dev.isBluetooth) 150f else 10f
        doubleTapSupported = saved?.supportsDoubleTap ?: store.isDoubleTapSupported(dev.key)

        // 2. Avvia ForegroundService
        val initialNotif = buildNotification("Sessione avviata · $activeDeviceName")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val fgsType = if (hasLocationPermission()) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            }
            startForeground(NOTIFICATION_ID, initialNotif, fgsType)
        } else {
            startForeground(NOTIFICATION_ID, initialNotif)
        }

        // 3. WakeLock parziale per sopravvivenza a schermo spento (validato in Fase 0)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KeepApp::LiveSessionLock").apply {
            setReferenceCounted(false)
            acquire(4 * 60 * 60 * 1000L) // timeout sicurezza 4h
        }

        // 4. Inizializza StateMachine + AudioEngine + Preferenze + MetricsTracker + VoiceCoach
        val prefs = (historyStore ?: SessionHistoryStore(this)).loadPreferences()
        autoLevelEnabled = prefs.autoLevelEnabled
        voiceCoachEnabled = prefs.voiceCoachEnabled
        trainingPlan = prefs.trainingPlan
        val patterns = PatternAssetLoader.loadAll(this)
        val initialPat = patterns.find { it.id == prefs.patternId } ?: patterns.first()
        val engine = KeepAppAudioEngine(initialPattern = initialPat, config = config)
        engine.setMode(prefs.audioMode)
        val sm = SessionStateMachine(config = config, initialPositionHint = PhonePosition.ARM)
        val nowMs = System.currentTimeMillis()
        metricsTracker = SessionMetricsTracker(sessionId = "sess_$nowMs", startedAtMs = nowMs)
        voicePlanner.reset()
        voiceCoach = VoiceCoach(this) { speaking ->
            audioEngine?.setVolume(if (speaking) 0.30f else 1.0f)
        }.apply {
            enabled = voiceCoachEnabled
        }
        audioEngine = engine
        stateMachine = sm

        requestAudioFocus()
        setupMediaSession()

        hasInitialCadenceLock = (trainingPlan.mode != TrainingMode.ADAPTIVE_ANCHOR)
        if (hasInitialCadenceLock) {
            engine.setMuted(false)
            engine.setBpm(trainingPlan.scheduledTargetBpm(0f))
        } else {
            engine.setMuted(true)
            engine.setBpm(155f)
        }
        engine.start()

        // 5. Thread dedicato sensori ~100 Hz
        val thread = HandlerThread("KeepApp-Sensors", Process.THREAD_PRIORITY_MORE_FAVORABLE).also { it.start() }
        val handler = Handler(thread.looper)
        sensorThread = thread
        sensorHandler = handler

        val sMgr = sensorManager
        sMgr?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { acc ->
            sMgr.registerListener(this, acc, 10_000, handler)
        }
        sMgr?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { gyro ->
            sMgr.registerListener(this, gyro, 10_000, handler)
        }
        sMgr?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)?.let { step ->
            sMgr.registerListener(this, step, SensorManager.SENSOR_DELAY_FASTEST, handler)
        }

        // 6. GPS nativo per rilevamento soste (D42) se permesso concesso
        if (hasLocationPermission()) {
            runCatching {
                locationManager?.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1_000L,
                    0f,
                    this,
                    thread.looper
                )
            }
        }

        _liveState.value = LiveSessionUiState(
            isActive = true,
            activeDeviceName = activeDeviceName,
            appliedBtLatencyMs = appliedBtLatencyMs,
            autoLevelEnabled = autoLevelEnabled,
            voiceCoachEnabled = voiceCoachEnabled,
            trainingPlan = trainingPlan,
            audioTelemetry = engine.telemetry.value
        )
    }

    private fun endSession() {
        if (!isStarted && audioEngine == null) {
            stopSelf()
            return
        }
        isStarted = false

        runCatching { sensorManager?.unregisterListener(this) }
        runCatching { locationManager?.removeUpdates(this) }
        runCatching {
            sensorThread?.quitSafely()
            sensorThread = null
            sensorHandler = null
        }

        releaseMediaSession()
        abandonAudioFocus()
        mainHandler.removeCallbacks(pendingSingleTapRunnable)
        headsetTapDetector.reset()
        voiceCoach?.shutdown()
        voiceCoach = null

        val completedSummary = metricsTracker?.buildSummary(
            deviceName = activeDeviceName,
            appliedBtLatencyMs = appliedBtLatencyMs,
            patternId = audioEngine?.patternEngine?.currentPattern?.id ?: "pulse_steady"
        )?.takeIf { it.durationSeconds >= 5f || it.totalSteps >= 5L }
        if (completedSummary != null) {
            (historyStore ?: SessionHistoryStore(this)).addSession(completedSummary)
        }
        metricsTracker = null

        audioEngine?.stop()
        audioEngine = null
        stateMachine = null

        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null

        _liveState.value = LiveSessionUiState(
            isActive = false,
            activeDeviceName = activeDeviceName,
            appliedBtLatencyMs = appliedBtLatencyMs,
            autoLevelEnabled = autoLevelEnabled,
            voiceCoachEnabled = voiceCoachEnabled,
            trainingPlan = trainingPlan,
            lastCompletedSummary = completedSummary ?: _liveState.value.lastCompletedSummary
        )

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun handleTogglePause() {
        val sm = stateMachine ?: return
        val engine = audioEngine ?: return
        val paused = sm.togglePauseResume(SystemClock.elapsedRealtimeNanos())
        engine.setMuted(paused)
        voiceCoach?.speak(if (paused) "Sessione in pausa." else "Sessione ripresa.")
        _liveState.value = _liveState.value.copy(
            runState = if (paused) SessionRunState.PAUSED else SessionRunState.ACTIVE_RUNNING,
            audioTelemetry = engine.telemetry.value
        )
        updateNotification(if (paused) "In pausa (premi cuffia per riprendere)" else "Sessione ripresa")
    }

    override fun onSensorChanged(event: SensorEvent) {
        val sm = stateMachine ?: return
        val tsNs = event.timestamp
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val snapshot = sm.onAccelerometer(tsNs, event.values[0], event.values[1], event.values[2])
                if (snapshot != null) {
                    applySnapshotToAudioAndUi(snapshot)
                }
            }
            Sensor.TYPE_GYROSCOPE -> {
                sm.onGyroscope(tsNs, event.values[0], event.values[1], event.values[2])
            }
            Sensor.TYPE_STEP_DETECTOR -> {
                sm.onStepDetectorEvent(tsNs)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onLocationChanged(location: Location) {
        val sm = stateMachine ?: return
        val speed = if (location.hasSpeed()) location.speed else 0f
        lastGpsSpeedMps = speed
        val tsNs = if (location.elapsedRealtimeNanos > 0L) {
            location.elapsedRealtimeNanos
        } else {
            SystemClock.elapsedRealtimeNanos()
        }
        sm.onGpsFix(tsNs, speed)
    }

    private fun applySnapshotToAudioAndUi(snapshot: SessionFrameSnapshot) {
        val engine = audioEngine ?: return
        val plan = trainingPlan
        val scheduledBpm = if (plan.mode != TrainingMode.ADAPTIVE_ANCHOR) {
            plan.scheduledTargetBpm(snapshot.elapsedSeconds)
        } else {
            null
        }
        metricsTracker?.onSnapshot(snapshot, overrideTargetBpm = scheduledBpm)
        voicePlanner.onSnapshot(snapshot)?.let { announcement ->
            voiceCoach?.speak(announcement)
        }

        if (!hasInitialCadenceLock && snapshot.cadenceEstimate.cadenceSpm >= config.minCadenceSpm) {
            hasInitialCadenceLock = true
            engine.setBpm(snapshot.cadenceEstimate.cadenceSpm)
        }

        val effectiveBpm = when {
            scheduledBpm != null -> scheduledBpm
            snapshot.anchorSnapshot.targetBpm >= config.minCadenceSpm -> snapshot.anchorSnapshot.targetBpm
            snapshot.cadenceEstimate.cadenceSpm >= config.minCadenceSpm -> snapshot.cadenceEstimate.cadenceSpm
            else -> 0f
        }

        val posOffsetMs = config.phaseOffsetMsFor(snapshot.positionEstimate.position)
        if (effectiveBpm >= config.minCadenceSpm) {
            engine.updateSyncTargets(
                bpm = effectiveBpm,
                predictedNextFootstrikeNs = snapshot.phaseState.predictedNextFootstrikeNs,
                phaseConfidence = snapshot.phaseState.phaseConfidence,
                btLatencyMs = appliedBtLatencyMs,
                positionOffsetMs = posOffsetMs
            )
        }

        when {
            !hasInitialCadenceLock || snapshot.runState == SessionRunState.PAUSED -> {
                engine.setMuted(true)
            }
            snapshot.runState == SessionRunState.ACTIVE_STOPPED -> {
                engine.setMuted(false)
                engine.setAttenuated(true) // -20 dB in sosta (semaforo/incrocio)
            }
            else -> {
                engine.setMuted(false)
                engine.setAttenuated(false)
            }
        }

        if (autoLevelEnabled) {
            val targetLevel = when {
                snapshot.runState == SessionRunState.ACTIVE_STOPPED -> 0
                snapshot.anchorSnapshot.reinforcementActive -> 3
                snapshot.anchorSnapshot.state == AnchorState.LOCKED -> 2
                else -> 1
            }
            engine.setLevel(targetLevel)
        }

        val audioTel = engine.telemetry.value
        _liveState.value = LiveSessionUiState(
            isActive = true,
            runState = snapshot.runState,
            elapsedSeconds = snapshot.elapsedSeconds,
            cadenceSpm = snapshot.cadenceEstimate.cadenceSpm,
            cadenceConfidence = snapshot.cadenceEstimate.confidence,
            targetBpm = effectiveBpm,
            anchorState = snapshot.anchorSnapshot.state,
            reinforcementActive = snapshot.anchorSnapshot.reinforcementActive,
            estimatedPosition = snapshot.positionEstimate.position,
            totalSteps = snapshot.totalStepsDetected,
            activeDeviceName = activeDeviceName,
            appliedBtLatencyMs = appliedBtLatencyMs,
            gpsSpeedMps = lastGpsSpeedMps,
            autoLevelEnabled = autoLevelEnabled,
            voiceCoachEnabled = voiceCoachEnabled,
            trainingPlan = trainingPlan,
            audioTelemetry = audioTel
        )

        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastNotificationUpdateMs >= 2_000L) {
            lastNotificationUpdateMs = nowMs
            val stateLabel = when (snapshot.runState) {
                SessionRunState.PAUSED -> "PAUSA"
                SessionRunState.ACTIVE_STOPPED -> "SOSTA -20 dB"
                SessionRunState.ACTIVE_RUNNING -> "${effectiveBpm.toInt()} BPM (${snapshot.anchorSnapshot.state.name})"
            }
            updateNotification("$stateLabel · ${snapshot.totalStepsDetected} passi")
        }
    }

    private fun onHeadsetRawSingleTap() {
        mainHandler.post {
            if (!doubleTapSupported) {
                // Cuffia senza doppio tocco (es. MANKIW-X15): singolo tocco immediato senza ritardo
                handleTogglePause()
                return@post
            }
            val nowMs = SystemClock.elapsedRealtime()
            mainHandler.removeCallbacks(pendingSingleTapRunnable)
            val openedWindow = headsetTapDetector.onSingleTap(nowMs)
            if (openedWindow) {
                mainHandler.postDelayed(pendingSingleTapRunnable, config.headsetDoubleTapWindowMs)
            }
        }
    }

    private fun onHeadsetHardwareDoubleTap() {
        mainHandler.post {
            mainHandler.removeCallbacks(pendingSingleTapRunnable)
            headsetTapDetector.reset()
            if (!doubleTapSupported) {
                doubleTapSupported = true
                profileStore?.markDoubleTapSupported(activeDeviceKey, true)
            }
            // D44: doppio tocco senza funzione in 1A (riservato al recap vocale della 1B)
        }
    }

    private fun setupMediaSession() {
        val session = MediaSession(this, "KeepAppSession")
        session.setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                val ke = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                }
                if (ke != null && ke.action == KeyEvent.ACTION_DOWN) {
                    when (ke.keyCode) {
                        KeyEvent.KEYCODE_MEDIA_PLAY,
                        KeyEvent.KEYCODE_MEDIA_PAUSE,
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                        KeyEvent.KEYCODE_HEADSETHOOK -> {
                            onHeadsetRawSingleTap()
                            return true
                        }
                        KeyEvent.KEYCODE_MEDIA_NEXT -> {
                            onHeadsetHardwareDoubleTap()
                            return true
                        }
                    }
                }
                return super.onMediaButtonEvent(mediaButtonIntent)
            }

            override fun onPlay() = onHeadsetRawSingleTap()
            override fun onPause() = onHeadsetRawSingleTap()
            override fun onSkipToNext() = onHeadsetHardwareDoubleTap()
        })
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE
                )
                .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
                .build()
        )
        session.isActive = true
        mediaSession = session
    }

    private fun releaseMediaSession() {
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
    }

    private fun requestAudioFocus() {
        val am = audioManager ?: return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        focusRequest = req
        am.requestAudioFocus(req)
    }

    private fun abandonAudioFocus() {
        val req = focusRequest ?: return
        audioManager?.abandonAudioFocusRequest(req)
        focusRequest = null
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun createNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Sessione Live KeepApp",
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)
    }

    private fun updateNotification(contentText: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    private fun buildNotification(contentText: String): Notification {
        val pauseIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, KeepAppSessionService::class.java).setAction(ACTION_TOGGLE_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, KeepAppSessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("KeepApp — Sessione Attiva")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Pausa/Riprendi", pauseIntent).build())
            .addAction(Notification.Action.Builder(null, "Ferma", stopIntent).build())
            .build()
    }
}
